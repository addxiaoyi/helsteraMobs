param(
    [ValidatePattern('^r[1-9][0-9]*$')]
    [string]$EvidenceRevision = 'r7',
    [string]$PaperJarPath = '',
    [string]$MythicMobsJarPath = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$reports = Join-Path $PSScriptRoot 'reports'
$runDate = [DateTime]::UtcNow.ToString('yyyyMMdd')
$fixtureId = "srv-paper-mythic-pack-strict-$runDate-$EvidenceRevision"
$reportName = "paper-1.21.1-mythic-pack-condition-strict-$runDate-$EvidenceRevision"
$server = Join-Path $root $fixtureId
$reportPath = Join-Path $reports "$reportName.json"
$logArchivePath = Join-Path $reports "$reportName.log.gz"
$paper = if ($PaperJarPath) { [System.IO.Path]::GetFullPath($PaperJarPath) }
    else { Join-Path $root 'srv-1.21.1/paper.jar' }
$plugin = Join-Path $root 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
$mythicVersionId = 'FTWvGuE5'
$mythicUrl = 'https://cdn.modrinth.com/data/NLuc7Jjh/versions/FTWvGuE5/MythicMobs-5.7.2.jar'
$mythicSha256 = '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f'
$mythicSha512 = 'd297fe2bf4fa651b6fed87be2c9d5112129f78b5309ac4716e44e2fbe84308e5d6e2b5939b4c39b5e1b5c42ab2c5d7628498b5cfa9ad0faea24561cdfafdd2a0'
$versionMatrix = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'version-matrix.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$release = @($versionMatrix.combinations | Where-Object { $_.releaseValidated -eq $true })
if ($release.Count -ne 1 -or $release[0].minecraftVersion -ne '1.21.1' -or
        $versionMatrix.releasePolicy.requiresExactServerBuild -ne $true) {
    throw 'The focused MythicMobs fixture requires the single pinned Paper 1.21.1 release combination.'
}
if (-not (Test-Path -LiteralPath $paper -PathType Leaf) -or
        (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant() -cne
        [string]$release[0].serverJarSha256) {
    throw 'Paper jar does not match the pinned 1.21.1-133 release artifact.'
}
if (-not (Test-Path -LiteralPath $plugin -PathType Leaf)) {
    throw 'Build the Helstera plugin first: ./mvnw -o -q -pl helstera-plugin -am package -DskipTests'
}
if ($server -eq $root -or -not $server.StartsWith($root + [System.IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Fixture path escaped the repository root.'
}
if (Test-Path -LiteralPath $server) { throw "Refusing to reuse fixture directory: $server" }
if ((Test-Path -LiteralPath $reportPath) -or (Test-Path -LiteralPath $logArchivePath)) {
    throw "Refusing to overwrite archived evidence for $EvidenceRevision. Choose a new revision."
}

if ($MythicMobsJarPath) {
    $mythicJar = [System.IO.Path]::GetFullPath($MythicMobsJarPath)
} else {
    $cache = Join-Path $PSScriptRoot '.external-cache'
    [void][System.IO.Directory]::CreateDirectory($cache)
    $mythicJar = Join-Path $cache 'MythicMobs-5.7.2-FTWvGuE5.jar'
    if (-not (Test-Path -LiteralPath $mythicJar -PathType Leaf)) {
        Invoke-WebRequest -Uri $mythicUrl -OutFile $mythicJar
    }
}
if (-not (Test-Path -LiteralPath $mythicJar -PathType Leaf)) {
    throw "MythicMobs 5.7.2 JAR not found: $mythicJar"
}
$actualMythicSha256 = (Get-FileHash -LiteralPath $mythicJar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualMythicSha256 -cne $mythicSha256) { throw 'MythicMobs 5.7.2 SHA-256 mismatch.' }
$actualMythicSha512 = (Get-FileHash -LiteralPath $mythicJar -Algorithm SHA512).Hash.ToLowerInvariant()
if ($actualMythicSha512 -cne $mythicSha512) { throw 'MythicMobs 5.7.2 SHA-512 mismatch.' }

[void][System.IO.Directory]::CreateDirectory($server)
[void][System.IO.Directory]::CreateDirectory($reports)
Copy-Item -LiteralPath $paper -Destination (Join-Path $server 'paper.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination (Join-Path $server 'server.properties')
[System.IO.File]::WriteAllText((Join-Path $server 'eula.txt'), "eula=true`n",
    [System.Text.UTF8Encoding]::new($false))
$plugins = Join-Path $server 'plugins'
[void][System.IO.Directory]::CreateDirectory($plugins)
$installedPlugin = Join-Path $plugins 'helsteraMobs.jar'
$installedMythic = Join-Path $plugins 'MythicMobs.jar'
Copy-Item -LiteralPath $plugin -Destination $installedPlugin
Copy-Item -LiteralPath $mythicJar -Destination $installedMythic
$pluginSha256 = (Get-FileHash -LiteralPath $installedPlugin -Algorithm SHA256).Hash.ToLowerInvariant()
$mythicData = Join-Path $plugins 'MythicMobs'
$packDirectory = Join-Path $mythicData 'Packs/HelsteraParityProbe'
[void][System.IO.Directory]::CreateDirectory($packDirectory)
[System.IO.File]::WriteAllText((Join-Path $packDirectory 'packinfo.yml'), @'
Name: Helstera Parity Probe
Description:
  - Isolated integration fixture for MythicMobs pack conditions.
Author: Helstera
Version: 2.4.1
'@, [System.Text.UTF8Encoding]::new($false))
$helsteraData = Join-Path $plugins 'helsteraMobs'
[void][System.IO.Directory]::CreateDirectory($helsteraData)
Copy-Item -LiteralPath (Join-Path $root 'helstera-plugin/src/main/resources/config.yml') `
    -Destination (Join-Path $helsteraData 'config.yml')
$configPath = Join-Path $helsteraData 'config.yml'
$configText = [System.IO.File]::ReadAllText($configPath)
$configText = [regex]::Replace($configText, '(?m)^  self-test-mythic-pack-condition:\s*false\s*$',
    '  self-test-mythic-pack-condition: true')
if ($configText -notmatch '(?m)^  self-test-mythic-pack-condition:\s*true\s*$') {
    $configText = [regex]::Replace($configText, '(?m)^  self-test:\s*false\s*$',
        "  self-test: false`n  self-test-mythic-pack-condition: true", 1)
}
[System.IO.File]::WriteAllText($configPath, $configText, [System.Text.UTF8Encoding]::new($false))

$processInfo = [System.Diagnostics.ProcessStartInfo]::new()
$processInfo.FileName = 'java'
$processInfo.Arguments = '-Xms1G -Xmx2G -jar paper.jar nogui'
$processInfo.WorkingDirectory = $server
$processInfo.UseShellExecute = $false
$processInfo.RedirectStandardInput = $true
$processInfo.RedirectStandardOutput = $true
$processInfo.RedirectStandardError = $true
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $processInfo
$latestLog = Join-Path $server 'logs/latest.log'
if (-not $process.Start()) { throw 'Failed to start isolated Paper server.' }
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
try {
    $startupDeadline = [DateTime]::UtcNow.AddMinutes(4)
    while ([DateTime]::UtcNow -lt $startupDeadline -and -not $process.HasExited) {
        if ((Test-Path -LiteralPath $latestLog) -and
                (Select-String -LiteralPath $latestLog -Pattern 'Done \(' -Quiet)) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($process.HasExited -or -not (Test-Path -LiteralPath $latestLog) -or
            -not (Select-String -LiteralPath $latestLog -Pattern 'Done \(' -Quiet)) {
        throw 'Paper or MythicMobs did not finish startup; inspect the retained fixture logs.'
    }
    $probeDeadline = [DateTime]::UtcNow.AddSeconds(30)
    $probePattern = 'mythic-pack-condition actual-version=2\.4\.1 .*premium-api-available=true .*premium-arguments-preserved=true premium-runtime-arguments-rejected=true mythic-mob-api-available=true mythic-mob-oracle-positive=true mythic-mob-oracle-negative=true mythic-mob-runtime-positive=true mythic-mob-runtime-negative=true mythic-mob-imported-positive=true mythic-mob-imported-negative=true ismm-alias-positive=true ismm-alias-negative=true passed=true'
    while ([DateTime]::UtcNow -lt $probeDeadline -and -not $process.HasExited -and
            -not (Select-String -LiteralPath $latestLog -Pattern $probePattern -Quiet)) {
        Start-Sleep -Milliseconds 250
    }
    if (-not (Select-String -LiteralPath $latestLog -Pattern $probePattern -Quiet)) {
        throw 'The imported Mythic pack condition Paper probe did not pass; inspect the retained fixture logs.'
    }
    $process.StandardInput.WriteLine('stop')
    $process.StandardInput.Flush()
    if (-not $process.WaitForExit(30000)) { throw 'Paper did not stop after the focused probe.' }
} finally {
    if (-not $process.HasExited) {
        try { $process.StandardInput.WriteLine('stop'); $process.StandardInput.Flush() } catch { }
        if (-not $process.WaitForExit(15000)) { $process.Kill() }
    }
}
if ($process.ExitCode -ne 0) { throw "Paper exited with code $($process.ExitCode)." }
$null = $stdoutTask.GetAwaiter().GetResult()
$null = $stderrTask.GetAwaiter().GetResult()

$logText = Get-Content -LiteralPath $latestLog -Raw -Encoding utf8
$paperMatch = [regex]::Match($logText,
    'This server is running Paper version (.+?) \(Implementing API version (.+?)\)')
$observationMatch = [regex]::Match($logText,
    '(?m)^.*\[自检\] (mythic-pack-condition actual-version=.*passed=true)\s*$')
if (-not $paperMatch.Success -or $paperMatch.Groups[1].Value -notmatch '^1\.21\.1-133(?:-|$)' -or
        -not $observationMatch.Success -or
        -not $logText.Contains('Enabling MythicMobs') -or
        -not $logText.Contains('Enabling helsteraMobs v1.0.0')) {
    throw 'Focused report checks found incomplete Paper/plugin startup evidence.'
}

$redactedLog = Get-Content -LiteralPath $latestLog -Raw -Encoding utf8
$redactedLog = [regex]::Replace($redactedLog,
    '(?i)([?&]token=)[^&\s"''<>]+', '$1[REDACTED]')
$redactedLog = [regex]::Replace($redactedLog,
    '(?i)(__cf_bm=)[^;\s"''<>]+', '$1[REDACTED]')
$gzipFile = [System.IO.File]::Open($logArchivePath, [System.IO.FileMode]::CreateNew)
try {
    $gzip = [System.IO.Compression.GzipStream]::new($gzipFile,
        [System.IO.Compression.CompressionLevel]::Optimal)
    try {
        $logBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($redactedLog)
        $gzip.Write($logBytes, 0, $logBytes.Length)
    } finally { $gzip.Dispose() }
} finally { $gzipFile.Dispose() }

$report = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    fixtureDirectory = $fixtureId
    evidenceMode = 'release'
    paperVersion = $paperMatch.Groups[1].Value
    paperApiVersion = $paperMatch.Groups[2].Value
    pluginEnabled = $true
    pluginSha256 = $pluginSha256
    paperJarSha256 = (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant()
    externalPlugin = 'MythicMobs 5.7.2 (Modrinth version FTWvGuE5)'
    externalPluginSource = $mythicUrl
    externalPluginLicense = 'All Rights Reserved; local integration test only, not redistributed'
    externalPluginSha256 = $actualMythicSha256
    externalPluginSha512 = $actualMythicSha512
    log = 'logs/latest.log'
    expectedProbeIds = @('importedMythicPackCondition')
    probes = @([ordered]@{
        id = 'importedMythicPackCondition'
        status = 'passed'
        observations = @($observationMatch.Groups[1].Value)
    })
}
$json = $report | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText((Join-Path $server 'integration-report.json'), $json + "`n",
    [System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText($reportPath, $json + "`n", [System.Text.UTF8Encoding]::new($false))
$reportSha256 = (Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant()
$logSha256 = (Get-FileHash -LiteralPath $logArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "Focused MythicMobs Paper probe passed: $fixtureId"
Write-Output "Report SHA-256: $reportSha256"
Write-Output "Compressed log SHA-256: $logSha256"
Write-Output "MythicMobs 5.7.2 SHA-256: $actualMythicSha256"
