param(
    [int]$RunSeconds = 75,
    [switch]$Strict,
    [double]$RandomSpawnTolerance = 0.15
)

$ErrorActionPreference = 'Stop'
if ([double]::IsNaN($RandomSpawnTolerance) -or [double]::IsInfinity($RandomSpawnTolerance) -or
        $RandomSpawnTolerance -lt 0 -or $RandomSpawnTolerance -gt 1) {
    throw 'RandomSpawnTolerance must be finite and between 0 and 1.'
}
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$serverName = if ([string]::IsNullOrWhiteSpace($env:PAPER_INTEGRATION_DIR)) {
    'srv-paper-integration'
} else {
    $env:PAPER_INTEGRATION_DIR
}
$server = Join-Path $repo $serverName
$paper = Join-Path $repo 'srv-1.21.1/paper.jar'
$plugin = Join-Path $repo 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'

if (-not (Test-Path -LiteralPath $paper -PathType Leaf)) { throw "Paper fixture not found: $paper" }
if (-not (Test-Path -LiteralPath $plugin -PathType Leaf)) { throw "Build plugin first: $plugin" }
if (-not (Test-Path -LiteralPath $server)) { New-Item -ItemType Directory -Path $server | Out-Null }
Copy-Item -LiteralPath $paper -Destination (Join-Path $server 'paper.jar') -Force
if ($env:PAPER_SKILLTREE_REUSE -ne 'true' -or
        -not (Test-Path -LiteralPath (Join-Path $server 'server.properties'))) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination (Join-Path $server 'server.properties') -Force
}
Set-Content -LiteralPath (Join-Path $server 'eula.txt') -Value 'eula=true' -Encoding ascii
$plugins = Join-Path $server 'plugins'
New-Item -ItemType Directory -Path $plugins -Force | Out-Null
Copy-Item -LiteralPath $plugin -Destination (Join-Path $plugins 'helsteraMobs.jar') -Force
$pluginData = Join-Path $plugins 'helsteraMobs'
if ($env:PAPER_SKILLTREE_REUSE -ne 'true' -and (Test-Path -LiteralPath $pluginData)) {
    Remove-Item -LiteralPath $pluginData -Recurse -Force
}
New-Item -ItemType Directory -Path $pluginData -Force | Out-Null
if ($env:PAPER_SKILLTREE_REUSE -ne 'true' -or
        -not (Test-Path -LiteralPath (Join-Path $pluginData 'config.yml'))) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'config.yml') -Destination (Join-Path $pluginData 'config.yml') -Force
}
if ($env:PAPER_SKILLTREE_STAGE) {
    $pluginConfig = Join-Path $pluginData 'config.yml'
    $yaml = Get-Content -LiteralPath $pluginConfig -Raw
    $yaml = [regex]::Replace($yaml, '(?m)^  self-test-skilltree-persistence:.*$',
        '  self-test-skilltree-persistence: true')
    $yaml = [regex]::Replace($yaml, '(?m)^  self-test-skilltree-stage:.*$',
        "  self-test-skilltree-stage: $env:PAPER_SKILLTREE_STAGE")
    Set-Content -LiteralPath $pluginConfig -Value $yaml -Encoding utf8
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'loot.yml') -Destination (Join-Path $pluginData 'loot.yml') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'items.yml') -Destination (Join-Path $pluginData 'items.yml') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'spawners.yml') -Destination (Join-Path $pluginData 'spawners.yml') -Force
$mobs = Join-Path $pluginData 'mobs'
New-Item -ItemType Directory -Path $mobs -Force | Out-Null
Copy-Item -Path (Join-Path $PSScriptRoot 'mobs/*') -Destination $mobs -Force
$clientFixture = Join-Path $PSScriptRoot 'menu.yml'
if (Test-Path -LiteralPath $clientFixture -PathType Leaf) {
    Copy-Item -LiteralPath $clientFixture -Destination (Join-Path $pluginData 'menu.yml') -Force
}

$opsPath = Join-Path $server 'ops.json'
if (Test-Path -LiteralPath $opsPath -PathType Leaf) {
    Remove-Item -LiteralPath $opsPath -Force
}

$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'java'
$psi.WorkingDirectory = $server
$psi.Arguments = '-Xms1G -Xmx2G -jar paper.jar nogui'
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $psi
$logPath = Join-Path $server 'logs/latest.log'
if (Test-Path -LiteralPath $logPath -PathType Leaf) {
    Remove-Item -LiteralPath $logPath -Force
}
if (-not $process.Start()) { throw 'Failed to start Paper process' }
$client = $null

try {
    $startupDeadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $startupDeadline -and -not $process.HasExited) {
        if ((Test-Path -LiteralPath $logPath) -and
                (Select-String -LiteralPath $logPath -Pattern 'Done \(' -Quiet)) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($process.HasExited) { throw 'Paper exited before startup completed' }
    if (-not (Test-Path -LiteralPath $logPath) -or
            -not (Select-String -LiteralPath $logPath -Pattern 'Done \(' -Quiet)) {
        throw 'Paper startup timed out before the server became ready'
    }

    # Paper can log Done before the local socket accepts the first client.
    # Give the loopback listener a short settling window before Mineflayer starts.
    Start-Sleep -Seconds 2

    $playerWorkflow = $env:PAPER_MENU_WORKFLOW -eq 'true' -or
        $env:PAPER_PLAYER_WORKFLOW -eq 'true' -or $env:PAPER_TWO_PLAYER_WORKFLOW -eq 'true'
    if ($playerWorkflow -and
            (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'package.json'))) {
        $nodeModules = Join-Path $PSScriptRoot 'node_modules/mineflayer'
        if (Test-Path -LiteralPath $nodeModules) {
            $clientOut = Join-Path $server 'menu-client.stdout.log'
            $clientErr = Join-Path $server 'menu-client.stderr.log'
            $audienceOut = Join-Path $server 'audience-client.stdout.log'
            $playerOnly = $env:PAPER_MENU_WORKFLOW -ne 'true'
            $twoPlayers = $env:PAPER_TWO_PLAYER_WORKFLOW -eq 'true'
            $clientScript = if ($playerOnly) {
                if ($twoPlayers) { Join-Path $PSScriptRoot 'two-player-client.js' }
                else { Join-Path $PSScriptRoot 'player-client.js' }
            } else {
                Join-Path $PSScriptRoot 'menu-client-test.js'
            }
            $client = $null
            $joinDeadline = [DateTime]::UtcNow.AddSeconds(30)
            while ([DateTime]::UtcNow -lt $joinDeadline) {
                if ($null -eq $client -or $client.HasExited) {
                    $client = Start-Process -FilePath 'node' -ArgumentList @(
                        $clientScript, '127.0.0.1', '25731', $clientOut, $audienceOut
                    ) -WorkingDirectory $PSScriptRoot -NoNewWindow -PassThru
                }
                if (Select-String -LiteralPath $logPath -Pattern 'HelsteraTester joined the game' -Quiet) {
                    if ($playerOnly -and $env:PAPER_SKIP_PLAYER_POTION -ne 'true') {
                        $process.StandardInput.WriteLine('give HelsteraTester minecraft:potion[minecraft:potion_contents={potion:"minecraft:swiftness"}] 1')
                        $process.StandardInput.Flush()
                    }
                    if (-not $playerOnly) {
                        $process.StandardInput.WriteLine('op HelsteraTester')
                        $process.StandardInput.Flush()
                    }
                    break
                }
                Start-Sleep -Milliseconds 250
            }
            if (-not (Select-String -LiteralPath $logPath -Pattern 'HelsteraTester joined the game' -Quiet)) {
                throw 'Paper player client did not join before timeout'
            }
            if (-not $playerOnly) {
              $guestDeadline = [DateTime]::UtcNow.AddSeconds(30)
            while ([DateTime]::UtcNow -lt $guestDeadline -and -not $client.HasExited) {
                if (Select-String -LiteralPath $logPath -Pattern 'AudienceGuest joined the game' -Quiet) {
                    $process.StandardInput.WriteLine('tp AudienceGuest 1000 100 1000')
                    $process.StandardInput.Flush()
                    break
                }
                Start-Sleep -Milliseconds 250
            }
            if (-not (Select-String -LiteralPath $logPath -Pattern 'AudienceGuest joined the game' -Quiet)) {
                throw 'Audience guest did not join Paper before timeout'
            }
            $adminWindowDeadline = [DateTime]::UtcNow.AddSeconds(30)
            while ([DateTime]::UtcNow -lt $adminWindowDeadline -and -not $client.HasExited) {
                if (Select-String -LiteralPath $logPath -Pattern 'MENU_PAGE_ONE_READY' -Quiet) {
                    $process.StandardInput.WriteLine('deop HelsteraTester')
                    $process.StandardInput.WriteLine('say MENU_PERMISSION_REVOKED')
                    $process.StandardInput.Flush()
                    break
                }
                Start-Sleep -Milliseconds 250
            }
            $permissionCheckDeadline = [DateTime]::UtcNow.AddSeconds(30)
            while ([DateTime]::UtcNow -lt $permissionCheckDeadline -and -not $client.HasExited) {
                if (Select-String -LiteralPath $logPath -Pattern 'MENU_PERMISSION_CHECK_COMPLETE' -Quiet) {
                    $process.StandardInput.WriteLine('op HelsteraTester')
                    $process.StandardInput.WriteLine('say MENU_PERMISSION_RESTORED')
                    $process.StandardInput.Flush()
                    break
                }
                Start-Sleep -Milliseconds 250
            }
            if (-not (Select-String -LiteralPath $logPath -Pattern 'MENU_PERMISSION_CHECK_COMPLETE' -Quiet)) {
                throw 'Menu client did not finish the permission-denial check'
            }
            $filterDeadline = [DateTime]::UtcNow.AddSeconds(30)
            while ([DateTime]::UtcNow -lt $filterDeadline -and -not $client.HasExited) {
                if (Select-String -LiteralPath $logPath -Pattern 'MENU_FILTER_READY' -Quiet) {
                    $process.StandardInput.WriteLine('op HelsteraTester')
                    $process.StandardInput.Flush()
                    break
                }
                Start-Sleep -Milliseconds 250
            }
            if (-not $client.WaitForExit(45000)) { $client.Kill() }
            if ($client.ExitCode -ne 0) { throw "Paper menu/audience client failed with exit code $($client.ExitCode)" }
            if (-not (Test-Path -LiteralPath $clientOut -PathType Leaf) -or
                    -not (Select-String -LiteralPath $clientOut -Pattern '^PAPER_MENU_REPORT ' -Quiet)) {
                throw 'Paper menu client did not produce a passing report'
            }
            if (-not (Test-Path -LiteralPath $audienceOut -PathType Leaf) -or
                    -not (Select-String -LiteralPath $audienceOut -Pattern '^PAPER_AUDIENCE_REPORT ' -Quiet)) {
                throw 'Paper audience client did not produce a passing report'
            }
            $menuReport = Get-Content -LiteralPath $clientOut -Encoding utf8 |
                Where-Object { $_ -match '^PAPER_MENU_REPORT ' } | Select-Object -Last 1
            $audienceReports = Get-Content -LiteralPath $audienceOut -Encoding utf8
            $menuPayload = ($menuReport -replace '^PAPER_MENU_REPORT ', '') | ConvertFrom-Json
            $audiencePayload = ($audienceReports | Where-Object { $_ -match '^PAPER_AUDIENCE_REPORT ' } |
                Select-Object -Last 1) -replace '^PAPER_AUDIENCE_REPORT ', '' | ConvertFrom-Json
            $filterPayload = ($audienceReports | Where-Object { $_ -match '^PAPER_AUDIENCE_FILTER_REPORT ' } |
                Select-Object -Last 1) -replace '^PAPER_AUDIENCE_FILTER_REPORT ', '' | ConvertFrom-Json
            if ($menuPayload.status -ne 'passed' -or $audiencePayload.status -ne 'passed' -or
                    $filterPayload.status -ne 'passed') {
                throw 'Paper menu, audience delivery, or audience filter assertions failed'
            }
            }
        }
    }

    if ($RunSeconds -gt 0 -and -not $process.HasExited) {
        $remaining = [Math]::Max(0, $RunSeconds * 1000)
        $process.WaitForExit($remaining) | Out-Null
    }
} finally {
    if ($null -ne $client -and -not $client.HasExited) {
        try { $client.Kill() } catch { }
    }
    if (-not $process.HasExited) {
        try {
            $process.StandardInput.WriteLine('stop')
            $process.StandardInput.Flush()
            if (-not $process.WaitForExit(15000)) { $process.Kill() }
        } catch {
            if (-not $process.HasExited) { $process.Kill() }
        }
    }
}
if ($process.ExitCode -ne 0) { throw "Paper exited with code $($process.ExitCode)" }
& (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $server `
    -RandomSpawnTolerance $RandomSpawnTolerance
if ($Strict) {
    & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath (Join-Path $server 'integration-report.json')
}
