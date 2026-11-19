param(
    [ValidatePattern('^r[1-9][0-9]*$')]
    [string]$EvidenceRevision = 'r1',
    [string]$PaperJarPath = '',
    [string]$MythicMobsJarPath = '',
    [string]$MojangCachePath = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$reports = Join-Path $PSScriptRoot 'reports'
$runDate = [DateTime]::UtcNow.ToString('yyyyMMdd')
$fixtureId = "srv-paper-potion-sync-exploratory-$runDate-$EvidenceRevision"
$reportName = "paper-1.21.1-potion-sync-exploratory-$runDate-$EvidenceRevision"
$server = Join-Path $root $fixtureId
$reportPath = Join-Path $reports "$reportName.json"
$logArchivePath = Join-Path $reports "$reportName.log.gz"
$clientArchivePath = Join-Path $reports "$reportName.client.log.gz"
$paper = if ($PaperJarPath) { [System.IO.Path]::GetFullPath($PaperJarPath) }
    else { Join-Path $root 'srv-1.21.1/paper.jar' }
$plugin = Join-Path $root 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
$versionMatrix = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'version-matrix.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$release = @($versionMatrix.combinations | Where-Object { $_.releaseValidated -eq $true })
if ($release.Count -ne 1 -or $release[0].minecraftVersion -ne '1.21.1' -or
        $versionMatrix.releasePolicy.requiresExactServerBuild -ne $true) {
    throw 'The Potion synchronization fixture requires the pinned Paper 1.21.1 release combination.'
}
if (-not (Test-Path -LiteralPath $paper -PathType Leaf) -or
        (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant() -cne
        [string]$release[0].serverJarSha256) {
    throw 'Paper jar does not match the pinned 1.21.1-133 release artifact.'
}
if (-not (Test-Path -LiteralPath $plugin -PathType Leaf)) {
    throw 'Build the Helstera plugin first: ./mvnw -o -q -pl helstera-plugin -am verify'
}
# This is the vanilla download SHA-256 from the pinned Paperclip jar's
# META-INF/download-context, not the patched Paper server artifact.
if ($MojangCachePath -and (-not (Test-Path -LiteralPath $MojangCachePath -PathType Leaf) -or
        (Get-FileHash -LiteralPath $MojangCachePath -Algorithm SHA256).Hash.ToLowerInvariant() -cne
        'e3bc55693e93cda0188f2e60aea28113fc647c5e85a15fa3d1b347349231b4bb')) {
    throw 'Mojang cache does not match the pinned Paperclip vanilla 1.21.1 download.'
}
if ($server -eq $root -or -not $server.StartsWith($root + [System.IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture path escaped the repository root.' }
foreach ($path in @($server, $reportPath, $logArchivePath, $clientArchivePath)) {
    if (Test-Path -LiteralPath $path) { throw "Refusing to overwrite fixture evidence: $path" }
}
$mythic = if ($MythicMobsJarPath) { [System.IO.Path]::GetFullPath($MythicMobsJarPath) }
    else { Join-Path $PSScriptRoot '.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar' }
$mythicSha256 = '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f'
if (-not (Test-Path -LiteralPath $mythic -PathType Leaf) -or
        (Get-FileHash -LiteralPath $mythic -Algorithm SHA256).Hash.ToLowerInvariant() -cne $mythicSha256) {
    throw 'Provide the pinned MythicMobs 5.7.2 Modrinth FTWvGuE5 artifact using -MythicMobsJarPath.'
}
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'node_modules/mineflayer') -PathType Container)) {
    throw 'Install the pinned integration client dependencies first: npm ci --prefix tools/paper-integration'
}
& node --test (Join-Path $PSScriptRoot 'test-potion-sync-contract.js')
if ($LASTEXITCODE -ne 0) { throw 'Potion synchronization validator contract tests failed.' }
[void][System.IO.Directory]::CreateDirectory($server)
[void][System.IO.Directory]::CreateDirectory($reports)
if ($MojangCachePath) {
    $cache = Join-Path $server 'cache'
    [void][System.IO.Directory]::CreateDirectory($cache)
    Copy-Item -LiteralPath $MojangCachePath -Destination (Join-Path $cache 'mojang_1.21.1.jar')
}
Copy-Item -LiteralPath $paper -Destination (Join-Path $server 'paper.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination (Join-Path $server 'server.properties')
[System.IO.File]::WriteAllText((Join-Path $server 'eula.txt'), "eula=true`n", [System.Text.UTF8Encoding]::new($false))
$plugins = Join-Path $server 'plugins'
[void][System.IO.Directory]::CreateDirectory($plugins)
Copy-Item -LiteralPath $mythic -Destination (Join-Path $plugins 'MythicMobs.jar')
$mythicSkills = Join-Path $plugins 'MythicMobs/Skills'
[void][System.IO.Directory]::CreateDirectory($mythicSkills)
$sharedSource = Join-Path $root 'helstera-plugin/src/main/resources/probes/potion-sync-skills.yml'
Copy-Item -LiteralPath $sharedSource -Destination $mythicSkills
$installedPlugin = Join-Path $plugins 'helsteraMobs.jar'
Copy-Item -LiteralPath $plugin -Destination $installedPlugin
$pluginSha256 = (Get-FileHash -LiteralPath $installedPlugin -Algorithm SHA256).Hash.ToLowerInvariant()
$data = Join-Path $plugins 'helsteraMobs'
[void][System.IO.Directory]::CreateDirectory($data)
$configPath = Join-Path $data 'config.yml'
$configText = [System.IO.File]::ReadAllText((Join-Path $root 'helstera-plugin/src/main/resources/config.yml'))
$configText = [regex]::Replace($configText, '(?m)^(web:\r?\n)  enabled: true\s*$', '$1  enabled: false')
$configText = [regex]::Replace($configText, '(?m)^  self-test-potion-sync:\s*false\s*$', '  self-test-potion-sync: true')
if ($configText -notmatch '(?m)^  self-test-potion-sync:\s*true\s*$') { throw 'Could not enable the Potion synchronization self-test.' }
[System.IO.File]::WriteAllText($configPath, $configText, [System.Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $root 'helstera-plugin/src/main/resources/skills.yml') -Destination (Join-Path $data 'skills.yml')

function New-FixtureProcess([string]$FileName, [string[]]$Arguments, [string]$WorkingDirectory) {
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $FileName
    foreach ($argument in $Arguments) { [void]$info.ArgumentList.Add($argument) }
    $info.WorkingDirectory = $WorkingDirectory
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $result = [System.Diagnostics.Process]::new()
    $result.StartInfo = $info
    return $result
}
$process = New-FixtureProcess 'java' @('-Xms1G', '-Xmx2G', '-jar', 'paper.jar', 'nogui') $server
$client = $null
$latestLog = Join-Path $server 'logs/latest.log'
if (-not $process.Start()) { throw 'Failed to start isolated Paper server.' }
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
try {
    $startupDeadline = [DateTime]::UtcNow.AddMinutes(4)
    while ([DateTime]::UtcNow -lt $startupDeadline -and -not $process.HasExited) {
        if ((Test-Path -LiteralPath $latestLog) -and (Select-String -LiteralPath $latestLog -Pattern 'Done \(' -Quiet)) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($process.HasExited -or -not (Test-Path -LiteralPath $latestLog) -or
            -not (Select-String -LiteralPath $latestLog -Pattern 'Done \(' -Quiet)) {
        throw 'Paper did not finish startup; inspect the retained fixture logs.'
    }
    $client = New-FixtureProcess 'node' @('potion-sync-client.js', '127.0.0.1', '25731') $PSScriptRoot
    if (-not $client.Start()) { throw 'Failed to start the Potion client.' }
    $clientOutputTask = $client.StandardOutput.ReadToEndAsync()
    $clientErrorTask = $client.StandardError.ReadToEndAsync()
    $probePattern = '(?m)^.*\[自检\] potion-sync .*passed=true\s*$'
    $probeDeadline = [DateTime]::UtcNow.AddSeconds(200)
    while ([DateTime]::UtcNow -lt $probeDeadline -and -not $process.HasExited -and
            -not (Select-String -LiteralPath $latestLog -Pattern '\[自检\] potion-sync .*passed=(true|false)' -Quiet)) {
        Start-Sleep -Milliseconds 250
    }
    if (-not (Select-String -LiteralPath $latestLog -Pattern $probePattern -Quiet)) {
        throw 'The Potion synchronization Paper probe did not pass; inspect the retained fixture logs.'
    }
    if (-not $client.WaitForExit(15000)) { throw 'Potion client did not finish packet validation.' }
    $clientLog = $clientOutputTask.GetAwaiter().GetResult() + $clientErrorTask.GetAwaiter().GetResult()
    if ($client.ExitCode -ne 0 -or $clientLog -notmatch 'PAPER_POTION_SYNC_CLIENT passed=true comparisons=14 checkpoints=28 reconnections=6') {
        throw 'Client Potion synchronization comparison failed; inspect potion-sync-client.log.'
    }
    $process.StandardInput.WriteLine('stop')
    $process.StandardInput.Flush()
    if (-not $process.WaitForExit(30000)) { throw 'Paper did not stop after the focused probe.' }
} finally {
    if ($null -ne $client -and $null -ne $clientOutputTask) {
        if (-not $client.HasExited) { $client.Kill(); $client.WaitForExit() }
        $clientLog = $clientOutputTask.GetAwaiter().GetResult() + $clientErrorTask.GetAwaiter().GetResult()
        [System.IO.File]::WriteAllText((Join-Path $server 'potion-sync-client.log'), $clientLog, [System.Text.UTF8Encoding]::new($false))
    }
    if (-not $process.HasExited) {
        try { $process.StandardInput.WriteLine('stop'); $process.StandardInput.Flush() } catch { }
        if (-not $process.WaitForExit(15000)) { $process.Kill(); $process.WaitForExit() }
    }
    $consoleLog = $stdoutTask.GetAwaiter().GetResult() + $stderrTask.GetAwaiter().GetResult()
    [System.IO.File]::WriteAllText((Join-Path $server 'paper-console.log'), $consoleLog, [System.Text.UTF8Encoding]::new($false))
}
if ($process.ExitCode -ne 0) { throw "Paper exited with code $($process.ExitCode)." }
$null = $stdoutTask.GetAwaiter().GetResult()
$null = $stderrTask.GetAwaiter().GetResult()
$logText = Get-Content -LiteralPath $latestLog -Raw -Encoding utf8
$paperMatch = [regex]::Match($logText, 'This server is running Paper version (.+?) \(Implementing API version (.+?)\)')
$observationMatch = [regex]::Match($logText, '(?m)^.*\[自检\] (potion-sync .*assertions=118 passed=true)\s*$')
if (-not $paperMatch.Success -or $paperMatch.Groups[1].Value -notmatch '^1\.21\.1-133(?:-|$)' -or
        -not $observationMatch.Success -or -not $logText.Contains('Enabling helsteraMobs v1.0.0')) {
    throw 'Incomplete pinned Paper/plugin startup evidence.'
}
$validationText = & node (Join-Path $PSScriptRoot 'potion-sync-validate.js') $latestLog (Join-Path $server 'potion-sync-client.log')
if ($LASTEXITCODE -ne 0) { throw 'Complete server/client synchronization evidence replay failed.' }
$validation = ($validationText -join "`n") | ConvertFrom-Json
$clientObservation = @($clientLog -split "`r?`n" | Where-Object {
    $_ -ceq 'PAPER_POTION_SYNC_CLIENT passed=true comparisons=14 checkpoints=28 reconnections=6'
})
if ($validation.serverAssertions -ne 118 -or $validation.comparisons -ne 14 -or
        $validation.checkpoints -ne 28 -or $validation.reconnections -ne 6 -or $clientObservation.Count -ne 1) {
    throw 'Synchronization evidence totals are incomplete.'
}
function Write-RedactedArchive([string]$Text, [string]$Destination) {
    $redacted = [regex]::Replace($Text, '(?i)([?&]token=)[^&\s"''<>]+', '$1[REDACTED]')
    $redacted = [regex]::Replace($redacted, '(?i)(__cf_bm=)[^;\s"''<>]+', '$1[REDACTED]')
    $redacted = [regex]::Replace($redacted, '(令牌[:：]\s*)\S+', '$1[REDACTED]')
    $file = [System.IO.File]::Open($Destination, [System.IO.FileMode]::CreateNew)
    try {
        $gzip = [System.IO.Compression.GzipStream]::new($file, [System.IO.Compression.CompressionLevel]::Optimal)
        try {
            $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($redacted)
            $gzip.Write($bytes, 0, $bytes.Length)
        } finally { $gzip.Dispose() }
    } finally { $file.Dispose() }
}
Write-RedactedArchive $logText $logArchivePath
Write-RedactedArchive $clientLog $clientArchivePath
$report = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    fixtureDirectory = $fixtureId
    evidenceMode = 'exploratory'
    paperVersion = $paperMatch.Groups[1].Value
    paperApiVersion = $paperMatch.Groups[2].Value
    pluginEnabled = $true
    pluginSha256 = $pluginSha256
    externalPlugin = 'MythicMobs 5.7.2 (Modrinth FTWvGuE5)'
    externalPluginSha256 = $mythicSha256
    sharedSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $mythicSkills 'potion-sync-skills.yml') -Algorithm SHA256).Hash.ToLowerInvariant()
    clientSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'potion-sync-client.js') -Algorithm SHA256).Hash.ToLowerInvariant()
    paperJarSha256 = (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant()
    log = 'logs/latest.log'
    clientLog = 'potion-sync-client.log'
    syncContractSha256 = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'potion-sync-contract.js') -Algorithm SHA256).Hash.ToLowerInvariant()
    evidenceValidatorSha256 = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'potion-sync-validate.js') -Algorithm SHA256).Hash.ToLowerInvariant()
    runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    serverAssertionCount = $validation.serverAssertions
    clientComparisons = $validation.comparisons
    clientCheckpoints = $validation.checkpoints
    clientReconnections = $validation.reconnections
    playerMode = 'SURVIVAL (restored after probe)'
    expectedProbeIds = @('potionSyncRuntime', 'potionSyncClient')
    probes = @(
        [ordered]@{ id = 'potionSyncRuntime'; status = 'passed'; observations = @($validation.serverObservation) },
        [ordered]@{ id = 'potionSyncClient'; status = 'passed'; observations = @($clientObservation) }
    )
}
$json = $report | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText((Join-Path $server 'integration-report.json'), $json + "`n", [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($reportPath, $json + "`n", [System.Text.UTF8Encoding]::new($false))
Write-Output "Focused Potion synchronization Paper/client probe passed: $fixtureId"
Write-Output "Report SHA-256: $((Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Output "Server log SHA-256: $((Get-FileHash -LiteralPath $logArchivePath -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Output "Client log SHA-256: $((Get-FileHash -LiteralPath $clientArchivePath -Algorithm SHA256).Hash.ToLowerInvariant())"
