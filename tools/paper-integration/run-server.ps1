param(
    [int]$RunSeconds = 75,
    [switch]$Strict,
    [switch]$Exploratory,
    [double]$RandomSpawnTolerance = 0.15
)

$ErrorActionPreference = 'Stop'
if ($Strict -and $Exploratory) {
    throw 'Exploratory mode cannot be combined with Strict mode.'
}
if ($Strict -and ($env:PAPER_MENU_WORKFLOW -ne 'true' -or $env:PAPER_WEB_RELOAD_PROBE -ne 'true' -or
        $env:PAPER_RAW_POTION_WORKFLOW -ne 'true' -or $env:PAPER_RAW_POTION_SHORT -ne 'true')) {
    throw 'Strict mode requires PAPER_MENU_WORKFLOW, PAPER_WEB_RELOAD_PROBE, PAPER_RAW_POTION_WORKFLOW, and PAPER_RAW_POTION_SHORT set to true.'
}
$pythonInfo = & (Join-Path $PSScriptRoot 'resolve-python.ps1')
if ($LASTEXITCODE -ne 0 -or $null -eq $pythonInfo) {
    throw 'Python 3 with encodings/jsonschema is required for strict report validation.'
}
$pythonCommand = [string]$pythonInfo.Source
$pythonPrefix = @($pythonInfo.Prefix)
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
$paper = if ([string]::IsNullOrWhiteSpace($env:PAPER_JAR_PATH)) {
    Join-Path $repo 'srv-1.21.1/paper.jar'
} else {
    [System.IO.Path]::GetFullPath($env:PAPER_JAR_PATH)
}
$plugin = Join-Path $repo 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'

if (-not (Test-Path -LiteralPath $paper -PathType Leaf)) {
    throw "Paper fixture not found: $paper (set PAPER_JAR_PATH to an existing Paper 1.21.1 server jar)"
}

function Get-PaperImplementationVersion([string]$JarPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if ($null -ne $entry) {
            $reader = [System.IO.StreamReader]::new($entry.Open())
            try {
                $manifest = $reader.ReadToEnd()
            } finally {
                $reader.Dispose()
            }
            $match = [regex]::Match($manifest, '(?m)^Implementation-Version:\s*(.+?)\s*$')
            if ($match.Success) { return $match.Groups[1].Value.Trim() }
        }

        # Historical Paperclip jars may omit Implementation-Version but keep
        # the authoritative Minecraft version in version.json.
        $versionEntry = $archive.GetEntry('version.json')
        if ($null -ne $versionEntry) {
            $reader = [System.IO.StreamReader]::new($versionEntry.Open())
            try {
                $version = $reader.ReadToEnd() | ConvertFrom-Json
                if ($null -ne $version.id -and -not [string]::IsNullOrWhiteSpace([string]$version.id)) {
                    return [string]$version.id
                }
            } catch {
                return $null
            } finally {
                $reader.Dispose()
            }
        }
        return $null
    } finally {
        $archive.Dispose()
    }
}

$paperImplementation = Get-PaperImplementationVersion $paper
if ($Strict -and [string]::IsNullOrWhiteSpace($paperImplementation)) {
    throw 'Paper jar has no recognizable version metadata; strict mode cannot prove the target server version'
}
if (-not [string]::IsNullOrWhiteSpace($paperImplementation) -and
        $paperImplementation -notmatch '^1\.21\.1(?:-|$)') {
    if (-not $Exploratory) {
        throw "Paper version mismatch: $paperImplementation (expected 1.21.1; pass -Exploratory for non-release evidence)"
    }
    Write-Warning "Exploratory Paper run uses $paperImplementation; this run is not eligible for the 1.21.1 strict release gate."
}
$mineflayerVersion = $env:PAPER_MINEFLAYER_VERSION
if ([string]::IsNullOrWhiteSpace($mineflayerVersion)) {
    $versionMatch = [regex]::Match([string]$paperImplementation, '^(\d+\.\d+\.\d+)')
    $mineflayerVersion = if ($versionMatch.Success) { $versionMatch.Groups[1].Value } else { '1.21.1' }
}
$env:PAPER_MINEFLAYER_VERSION = $mineflayerVersion
if (-not [string]::IsNullOrWhiteSpace($env:PAPER_LIBRARIES_PATH) -and
        -not (Test-Path -LiteralPath $env:PAPER_LIBRARIES_PATH -PathType Container)) {
    throw "Paper libraries directory not found: $($env:PAPER_LIBRARIES_PATH)"
}
if (-not [string]::IsNullOrWhiteSpace($env:PAPER_LIBRARIES_PATH)) {
    $paperApiJars = @(Get-ChildItem -LiteralPath $env:PAPER_LIBRARIES_PATH -Recurse -File -Filter 'paper-api-*.jar')
    if ($paperApiJars.Count -gt 1) {
        $paths = ($paperApiJars | ForEach-Object { $_.FullName }) -join ', '
        throw "Paper libraries directory contains multiple paper-api jars; isolate one matching the server before running: $paths"
    }
}
if (-not (Test-Path -LiteralPath $plugin -PathType Leaf)) { throw "Build plugin first: $plugin" }
if (-not (Test-Path -LiteralPath $server)) { New-Item -ItemType Directory -Path $server | Out-Null }
$webProbeResultPath = Join-Path $server 'web-reload-probe.json'
if (Test-Path -LiteralPath $webProbeResultPath -PathType Leaf) {
    Remove-Item -LiteralPath $webProbeResultPath -Force
}
Copy-Item -LiteralPath $paper -Destination (Join-Path $server 'paper.jar') -Force
if ($env:PAPER_SKILLTREE_REUSE -ne 'true' -or
        -not (Test-Path -LiteralPath (Join-Path $server 'server.properties'))) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination (Join-Path $server 'server.properties') -Force
}
Set-Content -LiteralPath (Join-Path $server 'eula.txt') -Value 'eula=true' -Encoding ascii
$plugins = Join-Path $server 'plugins'
New-Item -ItemType Directory -Path $plugins -Force | Out-Null
$installedPlugin = Join-Path $plugins 'helsteraMobs.jar'
Copy-Item -LiteralPath $plugin -Destination $installedPlugin -Force
$pluginSha256 = (Get-FileHash -LiteralPath $installedPlugin -Algorithm SHA256).Hash.ToLowerInvariant()
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
if ($env:PAPER_SPAWNER_PERSISTENCE_STAGE) {
    $pluginConfig = Join-Path $pluginData 'config.yml'
    $yaml = Get-Content -LiteralPath $pluginConfig -Raw
    $yaml = [regex]::Replace($yaml, '(?m)^  self-test-spawner-persistence:.*$',
        '  self-test-spawner-persistence: true')
    $yaml = [regex]::Replace($yaml, '(?m)^  self-test-spawner-persistence-stage:.*$',
        "  self-test-spawner-persistence-stage: $env:PAPER_SPAWNER_PERSISTENCE_STAGE")
    Set-Content -LiteralPath $pluginConfig -Value $yaml -Encoding utf8
}
if ($env:PAPER_WEB_RELOAD_PROBE -eq 'true') {
    $pluginConfig = Join-Path $pluginData 'config.yml'
    $yaml = Get-Content -LiteralPath $pluginConfig -Raw
    $yaml = [regex]::Replace($yaml, '(?m)^web:\s*\r?\n\s+enabled:\s*false\s*$',
        "web:`n  enabled: true`n  host: 127.0.0.1`n  port: 18765`n  token: paper-web-probe")
    Set-Content -LiteralPath $pluginConfig -Value $yaml -Encoding utf8
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'loot.yml') -Destination (Join-Path $pluginData 'loot.yml') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'items.yml') -Destination (Join-Path $pluginData 'items.yml') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'spawners.yml') -Destination (Join-Path $pluginData 'spawners.yml') -Force
if ($env:PAPER_SPAWNER_NATURAL_REPAIR_PROBE -eq 'true') {
    $configPath = Join-Path $pluginData 'config.yml'
    $configText = [System.IO.File]::ReadAllText($configPath)
    $configText = [regex]::Replace($configText, '(?m)^  self-test-spawner: true[ \t]*\r?$', '  self-test-spawner: false')
    [System.IO.File]::WriteAllText($configPath, $configText)
    $spawnerPath = Join-Path $pluginData 'spawners.yml'
    $spawnerText = [System.IO.File]::ReadAllText($spawnerPath)
    $probe = "spawners:`n  guard_natural_probe:`n    mob: emberling`n    x: 20.5`n    y: 70`n    z: 6.5`n    radius: 1.0`n    interval: 20`n    max-alive: 1`n    max-spawns: 1`n    enabled: true`n"
    $spawnerText = [regex]::Replace($spawnerText, '^spawners:\s*\r?\n', $probe, 1)
    [System.IO.File]::WriteAllText($spawnerPath, $spawnerText)
}
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
$runStartedUtc = [DateTime]::UtcNow
$paperLaunch = '-Xms1G -Xmx2G -jar paper.jar nogui'
if (-not [string]::IsNullOrWhiteSpace($env:PAPER_LIBRARIES_PATH)) {
    $libraryJars = @(Get-ChildItem -LiteralPath $env:PAPER_LIBRARIES_PATH -Recurse -File -Filter '*.jar' |
        ForEach-Object { $_.FullName })
    if ($libraryJars.Count -eq 0) {
        throw "Paper libraries directory contains no jar files: $($env:PAPER_LIBRARIES_PATH)"
    }
    $separator = [System.IO.Path]::PathSeparator
    $classPathEntries = @((Join-Path $server 'paper.jar')) + $libraryJars
    $classPath = [string]::Join($separator, [string[]]$classPathEntries)
    $paperLaunch = '-Xms1G -Xmx2G -cp "' + $classPath + '" org.bukkit.craftbukkit.Main nogui'
}
$psi.Arguments = $paperLaunch
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
$potionClient = $null

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
    if ($env:PAPER_SPAWNER_STATE_GUARD_PROBE -eq 'true') {
        $process.StandardInput.WriteLine('helstera spawner force persistence_probe')
        $process.StandardInput.Flush()
    }
    if ($env:PAPER_SPAWNER_STATE_REPAIR_PROBE -eq 'true') {
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited -and
                -not (Select-String -LiteralPath $logPath -Pattern '强制生成 0 只（persistence_probe）' -Quiet)) {
            Start-Sleep -Milliseconds 250
        }
        if (-not (Select-String -LiteralPath $logPath -Pattern '强制生成 0 只（persistence_probe）' -Quiet)) {
            throw 'Guard probe did not reject the initial forced spawn'
        }
        Copy-Item -LiteralPath (Join-Path $server 'valid-spawner-state.yml') -Destination (Join-Path $pluginData 'spawner-state.yml') -Force
        $process.StandardInput.WriteLine('helstera spawner reload')
        $process.StandardInput.WriteLine('forceload add 20 6')
        $process.StandardInput.Flush()
        Start-Sleep -Seconds 3
        $process.StandardInput.WriteLine('helstera spawner force persistence_probe')
        $process.StandardInput.Flush()
    }
    if ($env:PAPER_SPAWNER_NATURAL_REPAIR_PROBE -eq 'true') {
        $process.StandardInput.WriteLine('forceload add 20 6')
        $process.StandardInput.Flush()
        Start-Sleep -Seconds 6
        $logOffset = ([System.IO.File]::ReadAllText($logPath)).Length
        $process.StandardInput.WriteLine('helstera spawner list')
        $process.StandardInput.Flush()
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            Start-Sleep -Milliseconds 250
            $pausedLog = ([System.IO.File]::ReadAllText($logPath)).Substring($logOffset)
        } while ([DateTime]::UtcNow -lt $deadline -and $pausedLog -notmatch 'guard_natural_probe[^\r\n]*累计 \d+')
        if ($pausedLog -notmatch 'guard_natural_probe[^\r\n]*累计 0\b') {
            throw 'Natural spawner did not remain at zero while state was unavailable'
        }
        Copy-Item -LiteralPath (Join-Path $server 'valid-spawner-state.yml') -Destination (Join-Path $pluginData 'spawner-state.yml') -Force
        $process.StandardInput.WriteLine('helstera spawner reload')
        $process.StandardInput.Flush()
        Start-Sleep -Seconds 8
        $logOffset = ([System.IO.File]::ReadAllText($logPath)).Length
        $process.StandardInput.WriteLine('helstera spawner list')
        $process.StandardInput.Flush()
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            Start-Sleep -Milliseconds 250
            $restoredLog = ([System.IO.File]::ReadAllText($logPath)).Substring($logOffset)
        } while ([DateTime]::UtcNow -lt $deadline -and $restoredLog -notmatch 'guard_natural_probe[^\r\n]*累计 \d+')
        if ($restoredLog -notmatch 'guard_natural_probe[^\r\n]*累计 1\b') {
            throw 'Natural spawner did not resume after state repair'
        }
        Write-Host 'Natural spawner guard passed: paused=0 restored=1'
    }
    if ($env:PAPER_WEB_RELOAD_PROBE -eq 'true') {
        $spawnerPath = Join-Path $pluginData 'spawners.yml'
        $originalSpawners = [System.IO.File]::ReadAllText($spawnerPath)
        $headers = @{ Authorization = 'Bearer paper-web-probe' }
        $endpoint = 'http://127.0.0.1:18765/api/spawners/reload'
        try {
            [System.IO.File]::WriteAllText($spawnerPath, 'spawners: [broken')
            $invalid = Invoke-RestMethod -Method Post -Uri $endpoint -Headers $headers -Body '{}' -ContentType 'application/json'
            if ($invalid.ok -ne $false) { throw 'Web reload accepted invalid spawner YAML' }
        } finally {
            [System.IO.File]::WriteAllText($spawnerPath, $originalSpawners)
        }
        $valid = Invoke-RestMethod -Method Post -Uri $endpoint -Headers $headers -Body '{}' -ContentType 'application/json'
        if ($valid.ok -ne $true -or @($valid.spawners).Count -eq 0) {
            throw 'Web reload did not restore valid spawners'
        }
        $reloadLog = Get-Content -LiteralPath $logPath -Raw -Encoding utf8
        if ($reloadLog -notmatch '(?s)\[Server thread/WARN\]: \[helsteraMobs\] 刷怪点重载失败[^\r\n]*.*?\[Server thread/INFO\]: \[helsteraMobs\] 已装载刷怪点') {
            throw 'Web reload did not record failure and recovery on the server thread'
        }
        [ordered]@{
            pluginSha256 = $pluginSha256
            invalidOk = $invalid.ok
            invalidMessage = $invalid.message
            restoredOk = $valid.ok
            restoredSpawnerCount = @($valid.spawners).Count
            serverThread = $true
        } | ConvertTo-Json | Set-Content -LiteralPath $webProbeResultPath -Encoding utf8
        Write-Host 'Web spawner reload probe passed: invalid=false restored=true'
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
            $clientProcessOut = Join-Path $server 'menu-client.process.stdout.log'
            $audienceOut = Join-Path $server 'audience-client.stdout.log'
            foreach ($clientLog in @($clientOut, $clientErr, $clientProcessOut, $audienceOut)) {
                if (Test-Path -LiteralPath $clientLog -PathType Leaf) {
                    Remove-Item -LiteralPath $clientLog -Force
                }
            }
            $playerOnly = $env:PAPER_MENU_WORKFLOW -ne 'true'
            $twoPlayers = $env:PAPER_TWO_PLAYER_WORKFLOW -eq 'true'
            $rawPotion = $env:PAPER_RAW_POTION_WORKFLOW -eq 'true'
            $clientScript = if ($playerOnly) {
                if ($rawPotion) { Join-Path $PSScriptRoot 'raw-potion-client.js' }
                elseif ($twoPlayers) { Join-Path $PSScriptRoot 'two-player-client.js' }
                else { Join-Path $PSScriptRoot 'player-client.js' }
            } else {
                Join-Path $PSScriptRoot 'menu-client-test.js'
            }
            $client = $null
            $clientArguments = if ($playerOnly -and $rawPotion) {
                @($clientScript, '127.0.0.1', '25731', $clientOut, 'HelsteraTester')
            } else {
                @($clientScript, '127.0.0.1', '25731', $clientOut, $audienceOut)
            }
            $joinDeadline = [DateTime]::UtcNow.AddSeconds(30)
            while ([DateTime]::UtcNow -lt $joinDeadline) {
                if ($null -eq $client -or $client.HasExited) {
                    $client = Start-Process -FilePath 'node' -ArgumentList $clientArguments `
                        -WorkingDirectory $PSScriptRoot -NoNewWindow -PassThru `
                        -RedirectStandardOutput $clientProcessOut -RedirectStandardError $clientErr
                }
                if (Select-String -LiteralPath $logPath -Pattern 'HelsteraTester joined the game' -Quiet) {
                    if ($playerOnly -and $env:PAPER_SKIP_PLAYER_POTION -ne 'true') {
                        if ($rawPotion) {
                            $process.StandardInput.WriteLine('gamemode survival HelsteraTester')
                        }
                        $potionComponent = if ($env:PAPER_RAW_POTION_SHORT -eq 'true') {
                            'minecraft:potion_contents={custom_effects:[{id:"minecraft:speed",duration:40,amplifier:0}]}'
                        } else {
                            'minecraft:potion_contents={potion:"minecraft:swiftness"}'
                        }
                        $process.StandardInput.WriteLine(
                            "give HelsteraTester minecraft:potion[$potionComponent] 1")
                        $process.StandardInput.Flush()
                        if ($rawPotion) {
                            # The server self-test temporarily rewrites the
                            # player's inventory. Re-arm hotbar slot 1 shortly
                            # before the raw client sends use_item.
                            Start-Sleep -Seconds 8
                            $process.StandardInput.WriteLine(
                                "item replace entity HelsteraTester hotbar.1 with minecraft:potion[$potionComponent] 1")
                            $process.StandardInput.Flush()
                        }
                    }
                    if (-not $playerOnly) {
                        $process.StandardInput.WriteLine('op HelsteraTester')
                        # Keep the menu client alive while delayed server-side
                        # self-tests exercise the same player context.
                        $process.StandardInput.WriteLine(
                            'effect give HelsteraTester minecraft:resistance 600 255 true')
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
            $adminWindowDeadline = [DateTime]::UtcNow.AddSeconds(90)
            while ([DateTime]::UtcNow -lt $adminWindowDeadline -and -not $client.HasExited) {
                if (Select-String -LiteralPath $logPath -Pattern 'MENU_PAGE_ONE_READY' -Quiet) {
                    if (-not (Select-String -LiteralPath $logPath -Pattern 'menu-render id=parity-page-1 .*10=REDSTONE' -Quiet)) {
                        throw 'Menu render check failed: parity-page-1 did not render REDSTONE at slot 10'
                    }
                    $process.StandardInput.WriteLine('deop HelsteraTester')
                    $process.StandardInput.WriteLine('say MENU_PERMISSION_REVOKED')
                    $process.StandardInput.Flush()
                    break
                }
                Start-Sleep -Milliseconds 250
            }
            $permissionCheckDeadline = [DateTime]::UtcNow.AddSeconds(90)
            while ([DateTime]::UtcNow -lt $permissionCheckDeadline -and -not $client.HasExited) {
                if (Select-String -LiteralPath $logPath -Pattern 'MENU_PERMISSION_CHECK_COMPLETE' -Quiet) {
                    if (-not (Select-String -LiteralPath $logPath -Pattern 'menu-click id=parity-page-1 .*slot=10 permitted=false ran=false' -Quiet)) {
                        throw 'Menu denial check failed: slot 10 click was not recorded as denied'
                    }
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
            $clientWaitMs = if ($env:PAPER_SPAWNING_DISTANCE_WALK_WORKFLOW -eq 'true') { 60000 } else { 45000 }
            if (-not $client.WaitForExit($clientWaitMs)) { $client.Kill() }
            if ($client.ExitCode -ne 0) { throw "Paper menu/audience client failed with exit code $($client.ExitCode)" }
            # Mineflayer does not decode the menu contents reliably on 1.21.1.
            # Verify rendered materials and accepted clicks from the server log;
            # the client report is limited to window and action observations.
            if (-not (Select-String -LiteralPath $logPath -Pattern 'menu-render id=parity-page-2 .*13=EMERALD' -Quiet)) {
                throw 'Menu pagination action failed: parity-page-2 did not render EMERALD at slot 13'
            }
            if (-not (Select-String -LiteralPath $logPath -Pattern 'menu-click id=parity-page-1 .*slot=26 permitted=true ran=true pagination=next' -Quiet)) {
                throw 'Menu pagination check failed: next-page click was not accepted'
            }
            if (-not (Select-String -LiteralPath $logPath -Pattern 'menu-click id=parity-page-2 .*slot=13 permitted=true ran=true close=true' -Quiet)) {
                throw 'Menu action check failed: allowed action did not run and close the inventory'
            }
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
            if ($menuPayload.status -ne 'passed' -or $menuPayload.observations.paginationClick -ne $true -or
                    $menuPayload.observations.allowedActionMessage -ne $true -or
                    $menuPayload.observations.actionClosedInventory -ne $true -or
                    $audiencePayload.status -ne 'passed' -or
                    $filterPayload.status -ne 'passed') {
                throw 'Paper menu, audience delivery, or audience filter assertions failed'
            }
            }
            if ($rawPotion -and -not $playerOnly) {
                $potionOut = Join-Path $server 'raw-potion-client.stdout.log'
                $potionErr = Join-Path $server 'raw-potion-client.stderr.log'
                $potionDeadline = [DateTime]::UtcNow.AddSeconds(35)
                while ([DateTime]::UtcNow -lt $potionDeadline) {
                    if (Select-String -LiteralPath $logPath -Pattern 'HelsteraTester left the game' -Quiet) { break }
                    Start-Sleep -Milliseconds 250
                }
                if (-not (Select-String -LiteralPath $logPath -Pattern 'HelsteraTester left the game' -Quiet)) {
                    throw 'Menu player did not disconnect before the raw potion client was started'
                }
                $joinCountBeforePotion = @(Select-String -LiteralPath $logPath `
                    -Pattern 'HelsteraTester joined the game').Count
                $potionClient = Start-Process -FilePath 'node' -ArgumentList @(
                    (Join-Path $PSScriptRoot 'raw-potion-client.js'), '127.0.0.1', '25731',
                    $potionOut, 'HelsteraTester'
                ) -WorkingDirectory $PSScriptRoot -NoNewWindow -PassThru
                $potionJoinDeadline = [DateTime]::UtcNow.AddSeconds(30)
                $potionPlayerJoined = $false
                while ([DateTime]::UtcNow -lt $potionJoinDeadline -and -not $potionClient.HasExited) {
                    $joinCount = @(Select-String -LiteralPath $logPath `
                        -Pattern 'HelsteraTester joined the game').Count
                    if ($joinCount -gt $joinCountBeforePotion) {
                        $potionPlayerJoined = $true
                        $potionComponent = if ($env:PAPER_RAW_POTION_SHORT -eq 'true') {
                            'minecraft:potion_contents={custom_effects:[{id:"minecraft:speed",duration:40,amplifier:0}]}'
                        } else {
                            'minecraft:potion_contents={potion:"minecraft:swiftness"}'
                        }
                        $process.StandardInput.WriteLine('gamemode survival HelsteraTester')
                        $process.StandardInput.WriteLine(
                            "item replace entity HelsteraTester hotbar.1 with minecraft:potion[$potionComponent] 1")
                        $process.StandardInput.Flush()
                        break
                    }
                    Start-Sleep -Milliseconds 250
                }
                if (-not $potionPlayerJoined) {
                    throw 'Raw potion client did not rejoin Paper before timeout'
                }
                $consumeDeadline = [DateTime]::UtcNow.AddSeconds(25)
                while ([DateTime]::UtcNow -lt $consumeDeadline -and -not $potionClient.HasExited) {
                    if (Select-String -LiteralPath $logPath -Pattern 'player-potion-consume event=true.*item=POTION' -Quiet) { break }
                    Start-Sleep -Milliseconds 250
                }
            }
        }
    }

    if ($RunSeconds -gt 0 -and -not $process.HasExited) {
        $remaining = [Math]::Max(0, $RunSeconds * 1000)
        $process.WaitForExit($remaining) | Out-Null
    }
} finally {
    if ($null -ne $potionClient -and -not $potionClient.HasExited) {
        try { $potionClient.Kill() } catch { }
    }
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
if (-not (Test-Path -LiteralPath $installedPlugin -PathType Leaf) -or
        (Get-FileHash -LiteralPath $installedPlugin -Algorithm SHA256).Hash.ToLowerInvariant() -cne $pluginSha256) {
    throw 'Installed plugin JAR changed or disappeared during the Paper run.'
}
$evidenceMode = if ($Exploratory) { 'exploratory' } else { 'release' }
& (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $server `
    -EvidenceMode $evidenceMode `
    -RandomSpawnTolerance $RandomSpawnTolerance `
    -StartedAtUtc $runStartedUtc -PluginSha256 $pluginSha256
if ($Strict) {
    $strictReportPath = Join-Path $server 'integration-report.json'
    $schemaPath = Join-Path $PSScriptRoot 'integration-report.schema.json'
    $validatorPath = Join-Path $PSScriptRoot 'validate-report.py'
    & $pythonCommand @pythonPrefix $validatorPath $schemaPath $strictReportPath
    if ($LASTEXITCODE -ne 0) {
        throw 'Paper integration strict gate rejected the report JSON schema'
    }
    & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') `
        -ReportPath $strictReportPath `
        -RequireTargetPaperVersion -RequireFullReleaseCatalog -ExpectedPaperVersion '1.21.1' `
        -ExpectedPluginSha256 $pluginSha256
}
