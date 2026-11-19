param(
    [ValidatePattern('^r[1-9][0-9]*$')]
    [string]$EvidenceRevision = 'r1',
    [string]$PaperJarPath = '',
    [string]$MythicMobsJarPath = '',
    [string]$MythicLibrariesPath = '',
    [string]$MojangCachePath = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$reports = Join-Path $PSScriptRoot 'reports'
$runDate = [DateTime]::UtcNow.ToString('yyyyMMdd')
$fixtureId = "srv-paper-potion-restart-strict-$runDate-$EvidenceRevision"
$reportName = "paper-1.21.1-potion-restart-strict-$runDate-$EvidenceRevision"
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
    throw 'The Potion restart fixture requires the pinned Paper 1.21.1 release combination.'
}
if (-not (Test-Path -LiteralPath $paper -PathType Leaf) -or
        (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant() -cne
        [string]$release[0].serverJarSha256) {
    throw 'Paper jar does not match the pinned 1.21.1-133 release artifact.'
}
if (-not (Test-Path -LiteralPath $plugin -PathType Leaf)) {
    throw 'Build the Helstera plugin first: ./mvnw -o -q -pl helstera-plugin -am verify'
}
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
    throw 'Provide the pinned MythicMobs 5.7.2 artifact using -MythicMobsJarPath.'
}
if ($MythicLibrariesPath -and -not (Test-Path -LiteralPath $MythicLibrariesPath -PathType Container)) {
    throw 'MythicLibrariesPath must identify a directory of cached MythicMobs dependencies.'
}
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'node_modules/mineflayer') -PathType Container)) {
    throw 'Install integration dependencies first: npm ci --prefix tools/paper-integration'
}
& node --test (Join-Path $PSScriptRoot 'test-potion-restart-contract.js')
if ($LASTEXITCODE -ne 0) { throw 'Potion restart contract tests failed.' }

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
if ($MythicLibrariesPath) {
    Copy-Item -LiteralPath $MythicLibrariesPath -Destination $server -Recurse
}
$mythicSkills = Join-Path $plugins 'MythicMobs/Skills'
[void][System.IO.Directory]::CreateDirectory($mythicSkills)
Copy-Item -LiteralPath (Join-Path $root 'helstera-plugin/src/main/resources/probes/potion-sync-skills.yml') -Destination $mythicSkills
$installedPlugin = Join-Path $plugins 'helsteraMobs.jar'
Copy-Item -LiteralPath $plugin -Destination $installedPlugin
$pluginSha256 = (Get-FileHash -LiteralPath $installedPlugin -Algorithm SHA256).Hash.ToLowerInvariant()
$data = Join-Path $plugins 'helsteraMobs'
[void][System.IO.Directory]::CreateDirectory($data)
$configPath = Join-Path $data 'config.yml'
$configText = [System.IO.File]::ReadAllText((Join-Path $root 'helstera-plugin/src/main/resources/config.yml'))
$configText = [regex]::Replace($configText, '(?m)^(web:\r?\n)  enabled: true\s*$', '$1  enabled: false')
$configText = [regex]::Replace($configText, '(?m)^  potion-restart-stage:.*$', '  potion-restart-stage: ""')
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

function Redact-Text([string]$Text) {
    $Text = [regex]::Replace($Text, '(?i)([?&]token=)[^&\s"''<>]+', '$1[REDACTED]')
    $Text = [regex]::Replace($Text, '(?i)(__cf_bm=)[^;\s"''<>]+', '$1[REDACTED]')
    return [regex]::Replace($Text, '(?im)(令牌|token)(\s*[:=]\s*)[A-Za-z0-9._~-]+', '$1$2[REDACTED]')
}

function Invoke-RestartBoot([string]$Stage, [string]$OwnerId, [string]$WatcherId, [string]$CowId) {
    $configText = [System.IO.File]::ReadAllText($configPath)
    $configText = [regex]::Replace($configText, '(?m)^  potion-restart-stage:.*$', "  potion-restart-stage: `"$Stage`"")
    [System.IO.File]::WriteAllText($configPath, $configText, [System.Text.UTF8Encoding]::new($false))
    $process = New-FixtureProcess 'java' @('-Xms1G', '-Xmx2G', '-jar', 'paper.jar', 'nogui') $server
    $client = $null
    $clientOutputTask = $null
    $stdoutTask = $null
    $stderrTask = $null
    $clientLog = ''
    $latestLog = Join-Path $server 'logs/latest.log'
    if (Test-Path -LiteralPath $latestLog) {
        Move-Item -LiteralPath $latestLog -Destination (Join-Path $server "potion-restart-before-$Stage.log")
    }
    if (-not $process.Start()) { throw "Failed to start Paper for $Stage." }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    try {
        $startupDeadline = [DateTime]::UtcNow.AddMinutes(5)
        while ([DateTime]::UtcNow -lt $startupDeadline -and -not $process.HasExited) {
            if ((Test-Path -LiteralPath $latestLog) -and (Select-String -LiteralPath $latestLog -Pattern 'Done \(' -Quiet)) { break }
            Start-Sleep -Milliseconds 500
        }
        if ($process.HasExited -or -not (Test-Path -LiteralPath $latestLog) -or
                -not (Select-String -LiteralPath $latestLog -Pattern 'Done \(' -Quiet)) {
            throw "Paper did not finish startup for $Stage."
        }
        $clientArgs = @('potion-restart-client.js', '127.0.0.1', '25731', $Stage, $server,
            $OwnerId, $WatcherId, $CowId)
        $client = New-FixtureProcess 'node' $clientArgs $PSScriptRoot
        if (-not $client.Start()) { throw "Failed to start restart clients for $Stage." }
        $clientOutputTask = $client.StandardOutput.ReadToEndAsync()
        $clientErrorTask = $client.StandardError.ReadToEndAsync()
        $clientDeadline = [DateTime]::UtcNow.AddSeconds(150)
        while (-not $client.HasExited -and [DateTime]::UtcNow -lt $clientDeadline -and -not $process.HasExited) {
            Start-Sleep -Milliseconds 250
        }
        if (-not $client.HasExited) { throw "Restart clients timed out during $Stage." }
        $clientLog = $clientOutputTask.GetAwaiter().GetResult() + $clientErrorTask.GetAwaiter().GetResult()
        $expectedClient = "PAPER_POTION_RESTART_CLIENT passed=true stage=$Stage roles=2"
        if ($client.ExitCode -ne 0 -or $clientLog -notmatch [regex]::Escape($expectedClient)) {
            throw "Restart client contract failed during $Stage."
        }
        $probePattern = "(?m)^.*\[自检\] potion-restart stage=$Stage .*passed=true\s*$"
        $probeDeadline = [DateTime]::UtcNow.AddSeconds(10)
        while ([DateTime]::UtcNow -lt $probeDeadline -and
                -not (Select-String -LiteralPath $latestLog -Pattern "\[自检\] potion-restart stage=$Stage .*passed=(true|false)" -Quiet)) {
            Start-Sleep -Milliseconds 200
        }
        if (-not (Select-String -LiteralPath $latestLog -Pattern $probePattern -Quiet)) {
            throw "Paper-side restart probe failed during $Stage."
        }
        [System.IO.File]::WriteAllText((Join-Path $server "potion-restart-$Stage.client.log"), $clientLog,
            [System.Text.UTF8Encoding]::new($false))
        $process.StandardInput.WriteLine('stop')
        $process.StandardInput.Flush()
        if (-not $process.WaitForExit(45000)) { throw "Paper did not stop normally after $Stage." }
    } finally {
        if ($null -ne $client -and -not $client.HasExited) { $client.Kill(); $client.WaitForExit() }
        if ($null -ne $clientOutputTask) {
            $clientLog = $clientOutputTask.GetAwaiter().GetResult() + $clientErrorTask.GetAwaiter().GetResult()
            [System.IO.File]::WriteAllText((Join-Path $server "potion-restart-$Stage.client.log"), $clientLog,
                [System.Text.UTF8Encoding]::new($false))
        }
        if (-not $process.HasExited) {
            try { $process.StandardInput.WriteLine('stop'); $process.StandardInput.Flush() } catch { }
            if (-not $process.WaitForExit(20000)) { $process.Kill(); $process.WaitForExit() }
        }
        if ($null -ne $stdoutTask) {
            $consoleLog = $stdoutTask.GetAwaiter().GetResult() + $stderrTask.GetAwaiter().GetResult()
            [System.IO.File]::WriteAllText((Join-Path $server "potion-restart-$Stage.console.log"), (Redact-Text $consoleLog),
                [System.Text.UTF8Encoding]::new($false))
        }
    }
    if ($process.ExitCode -ne 0) { throw "Paper exited with code $($process.ExitCode) during $Stage." }
    $diskLog = Redact-Text (Get-Content -LiteralPath $latestLog -Raw -Encoding utf8)
    [System.IO.File]::WriteAllText($latestLog, $diskLog, [System.Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath $latestLog -Destination (Join-Path $server "potion-restart-$Stage.log")
    return Get-Content -LiteralPath (Join-Path $server "potion-restart-$Stage.log") -Raw -Encoding utf8
}

function Read-Snapshot([string]$Mode, [string]$OwnerId, [string]$CowId, [string]$Previous = '') {
    $arguments = @((Join-Path $PSScriptRoot 'potion-restart-contract.js'), $Mode, $server, $OwnerId, $CowId)
    if ($Previous) { $arguments += $Previous }
    $output = & node @arguments
    if ($LASTEXITCODE -ne 0) { throw "Persisted $Mode Potion snapshot validation failed." }
    return (($output -join "`n") | ConvertFrom-Json)
}

function Write-RedactedArchive([string]$Text, [string]$Destination) {
    $redacted = Redact-Text $Text
    if ((Redact-Text $redacted) -cne $redacted) {
        throw 'Log redaction is incomplete; refusing to archive the Paper log.'
    }
    $file = [System.IO.File]::Open($Destination, [System.IO.FileMode]::CreateNew)
    try {
        $gzip = [System.IO.Compression.GzipStream]::new($file, [System.IO.Compression.CompressionLevel]::Optimal)
        try {
            $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($redacted)
            $gzip.Write($bytes, 0, $bytes.Length)
        } finally { $gzip.Dispose() }
    } finally { $file.Dispose() }
}

$prepareLog = Invoke-RestartBoot 'prepare' '' '' ''
$identityMatch = [regex]::Match($prepareLog,
    '(?m)^.*\[自检\] potion-restart stage=prepare owner=([0-9a-f-]{36}) watcher=([0-9a-f-]{36}) cow=([0-9a-f-]{36}) .*passed=true\s*$')
if (-not $identityMatch.Success) { throw 'First boot did not publish stable player and Cow UUIDs.' }
$ownerId, $watcherId, $cowId = $identityMatch.Groups[1].Value, $identityMatch.Groups[2].Value, $identityMatch.Groups[3].Value
$baseline = Read-Snapshot 'active' $ownerId $cowId
$baselinePath = Join-Path $server 'potion-restart-active-before.json'
[System.IO.File]::WriteAllText($baselinePath, ($baseline.snapshot | ConvertTo-Json -Depth 8) + "`n",
    [System.Text.UTF8Encoding]::new($false))

$verifyLog = Invoke-RestartBoot 'verify-clear' $ownerId $watcherId $cowId
$identityCheck = [regex]::Match($verifyLog,
    "(?m)^.*\[自检\] potion-restart stage=verify-clear owner=$ownerId watcher=$watcherId cow=$cowId .*passed=true\s*$")
if (-not $identityCheck.Success) { throw 'Second boot did not restore the same owner, watcher, and Cow identities.' }
$restoredMatch = [regex]::Match($verifyLog,
    'potion-restart stage=verify-clear .*owner-haste-restored=(\d+) cow-haste-restored=(\d+) owner-invisibility-restored=(\d+) cow-invisibility-restored=(\d+) passed=true')
if (-not $restoredMatch.Success) { throw 'Second boot did not record restored effect durations.' }
foreach ($index in 0..3) {
    $role = if ($index % 2 -eq 0) { 'owner' } else { 'cow' }
    $effect = if ($index -lt 2) { 'minecraft:haste' } else { 'minecraft:invisibility' }
    $beforeEntity = if ($role -eq 'owner') { $baseline.snapshot.owner } else { $baseline.snapshot.cow }
    $beforeDuration = @($beforeEntity.active_effects | Where-Object { $_.id -eq $effect })[0].duration
    $restoredDuration = [int]$restoredMatch.Groups[$index + 1].Value
    if ($restoredDuration -le 0 -or $restoredDuration -ge $beforeDuration) {
        throw "Effect duration did not persist and tick across restart: $role $effect ($beforeDuration -> $restoredDuration)."
    }
}
$cleared = Read-Snapshot 'clean' $ownerId $cowId $baselinePath

$cleanLog = Invoke-RestartBoot 'clean' $ownerId $watcherId $cowId
if (-not [regex]::IsMatch($cleanLog,
        "(?m)^.*\[自检\] potion-restart stage=clean owner=$ownerId watcher=$watcherId cow=$cowId .*passed=true\s*$")) {
    throw 'Third boot did not preserve the cleared Potion state.'
}
$cleanSnapshot = Read-Snapshot 'clean' $ownerId $cowId $baselinePath

$paperMatch = [regex]::Match($cleanLog, 'This server is running Paper version (.+?) \(Implementing API version (.+?)\)')
if (-not $paperMatch.Success -or $paperMatch.Groups[1].Value -notmatch '^1\.21\.1-133(?:-|$)') {
    throw 'Restart evidence did not run on the pinned Paper 1.21.1-133 build.'
}
$serverLogs = @('prepare', 'verify-clear', 'clean' | ForEach-Object {
    "===== $($_) =====`n" + (Get-Content -LiteralPath (Join-Path $server "potion-restart-$_.log") -Raw -Encoding utf8)
}) -join "`n"
$clientLogs = @('prepare', 'verify-clear', 'clean' | ForEach-Object {
    "===== $($_) =====`n" + (Get-Content -LiteralPath (Join-Path $server "potion-restart-$_.client.log") -Raw -Encoding utf8)
}) -join "`n"
Write-RedactedArchive $serverLogs $logArchivePath
Write-RedactedArchive $clientLogs $clientArchivePath
$report = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    fixtureDirectory = $fixtureId
    evidenceMode = 'strict-focused'
    paperVersion = $paperMatch.Groups[1].Value
    paperApiVersion = $paperMatch.Groups[2].Value
    paperJarSha256 = (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant()
    pluginSha256 = $pluginSha256
    externalPlugin = 'MythicMobs 5.7.2 (Modrinth FTWvGuE5)'
    externalPluginSha256 = $mythicSha256
    identities = [ordered]@{ owner = $ownerId; watcher = $watcherId; cow = $cowId }
    boots = @(
        [ordered]@{ stage = 'prepare'; result = 'passed'; nativePlayerEffects = 2; importedCowEffects = 2; log = 'potion-restart-prepare.log' },
        [ordered]@{ stage = 'verify-clear'; result = 'passed'; persistedUUIDs = $true; loginEffectPacketsMatchedSavedNBT = $true;
            effectDurationTicked = $true; effectsRemoved = $true; log = 'potion-restart-verify-clear.log' },
        [ordered]@{ stage = 'clean'; result = 'passed'; effectsResurrected = $false; observerPacketsClean = $true;
            log = 'potion-restart-clean.log' }
    )
    activeEffectsBeforeRestart = $baseline.effects
    cleanEffectsAfterClear = $cleanSnapshot.effects
    packetAssertions = [ordered]@{ prepare = 2; verifyClear = 2; clean = 2; observerEffectLeaks = 0 }
    serverLog = [System.IO.Path]::GetFileName($logArchivePath)
    clientLog = [System.IO.Path]::GetFileName($clientArchivePath)
    contractSha256 = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'potion-restart-contract.js') -Algorithm SHA256).Hash.ToLowerInvariant()
    clientSha256 = (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'potion-restart-client.js') -Algorithm SHA256).Hash.ToLowerInvariant()
    runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
}
$json = $report | ConvertTo-Json -Depth 10
[System.IO.File]::WriteAllText($reportPath, $json + "`n", [System.Text.UTF8Encoding]::new($false))
Write-Output "Three-boot Potion restart Paper/client probe passed: $fixtureId"
Write-Output "Report SHA-256: $((Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Output "Server log SHA-256: $((Get-FileHash -LiteralPath $logArchivePath -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Output "Client log SHA-256: $((Get-FileHash -LiteralPath $clientArchivePath -Algorithm SHA256).Hash.ToLowerInvariant())"
