param(
    [ValidatePattern('^r[1-9][0-9]*$')]
    [string]$EvidenceRevision = 'r1',
    [string]$PaperJarPath = '',
    [string]$MythicMobsJarPath = ''
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$reports = Join-Path $PSScriptRoot 'reports'
$runDate = [DateTime]::UtcNow.ToString('yyyyMMdd')
$fixtureId = "srv-paper-percent-health-exploratory-$runDate-$EvidenceRevision"
$reportName = "paper-1.21.1-percent-health-exploratory-$runDate-$EvidenceRevision"
$server = Join-Path $root $fixtureId
$reportPath = Join-Path $reports "$reportName.json"
$logArchivePath = Join-Path $reports "$reportName.log.gz"
$paper = if ($PaperJarPath) { [System.IO.Path]::GetFullPath($PaperJarPath) }
    else { Join-Path $root 'srv-1.21.1/paper.jar' }
$plugin = Join-Path $root 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
$versionMatrix = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'version-matrix.json') -Raw -Encoding utf8 |
    ConvertFrom-Json
$release = @($versionMatrix.combinations | Where-Object { $_.releaseValidated -eq $true })
if ($release.Count -ne 1 -or $release[0].minecraftVersion -ne '1.21.1' -or
        $versionMatrix.releasePolicy.requiresExactServerBuild -ne $true) {
    throw 'The percent-health fixture requires the pinned Paper 1.21.1 release combination.'
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

$mythic = if ($MythicMobsJarPath) { [System.IO.Path]::GetFullPath($MythicMobsJarPath) }
    else { Join-Path $PSScriptRoot '.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar' }
$mythicSha256 = '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f'
if (-not (Test-Path -LiteralPath $mythic -PathType Leaf) -or
        (Get-FileHash -LiteralPath $mythic -Algorithm SHA256).Hash.ToLowerInvariant() -cne $mythicSha256) {
    throw 'Provide the pinned MythicMobs 5.7.2 Modrinth FTWvGuE5 artifact using -MythicMobsJarPath.'
}
[void][System.IO.Directory]::CreateDirectory($server)
[void][System.IO.Directory]::CreateDirectory($reports)
Copy-Item -LiteralPath $paper -Destination (Join-Path $server 'paper.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination (Join-Path $server 'server.properties')
[System.IO.File]::WriteAllText((Join-Path $server 'eula.txt'), "eula=true`n",
    [System.Text.UTF8Encoding]::new($false))
$plugins = Join-Path $server 'plugins'
[void][System.IO.Directory]::CreateDirectory($plugins)
Copy-Item -LiteralPath $mythic -Destination (Join-Path $plugins 'MythicMobs.jar')
$mythicSkills = Join-Path $plugins 'MythicMobs/Skills'
[void][System.IO.Directory]::CreateDirectory($mythicSkills)
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'percent-health-skills.yml') -Destination $mythicSkills
$installedPlugin = Join-Path $plugins 'helsteraMobs.jar'
Copy-Item -LiteralPath $plugin -Destination $installedPlugin
$pluginSha256 = (Get-FileHash -LiteralPath $installedPlugin -Algorithm SHA256).Hash.ToLowerInvariant()
$data = Join-Path $plugins 'helsteraMobs'
[void][System.IO.Directory]::CreateDirectory($data)
$configPath = Join-Path $data 'config.yml'
Copy-Item -LiteralPath (Join-Path $root 'helstera-plugin/src/main/resources/config.yml') -Destination $configPath
$configText = [System.IO.File]::ReadAllText($configPath)
$configText = [regex]::Replace($configText, '(?m)^(web:\r?\n)  enabled: true\s*$', '$1  enabled: false')
$configText = [regex]::Replace($configText, '(?m)^  self-test-percent-health:\s*false\s*$',
    '  self-test-percent-health: true')
if ($configText -notmatch '(?m)^  self-test-percent-health:\s*true\s*$') {
    throw 'Could not enable the percent-health Paper self-test.'
}
$configText = [regex]::Replace($configText, '(?m)^  self-test-delay-ticks:\s*\d+\s*$',
    '  self-test-delay-ticks: 100')
[System.IO.File]::WriteAllText($configPath, $configText, [System.Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $root 'helstera-plugin/src/main/resources/skills.yml') -Destination (Join-Path $data 'skills.yml')

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
        throw 'Paper did not finish startup; inspect the retained fixture logs.'
    }
    $probePattern = '(?m)^.*\[自检\] percent-health .*passed=true\s*$'
    $probeDeadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $probeDeadline -and -not $process.HasExited -and
            -not (Select-String -LiteralPath $latestLog -Pattern $probePattern -Quiet)) {
        Start-Sleep -Milliseconds 250
    }
    if (-not (Select-String -LiteralPath $latestLog -Pattern $probePattern -Quiet)) {
        throw 'The percent-health Paper probe did not pass; inspect the retained fixture logs.'
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
    '(?m)^.*\[自检\] (percent-health .*passed=true)\s*$')
if (-not $paperMatch.Success -or $paperMatch.Groups[1].Value -notmatch '^1\.21\.1-133(?:-|$)' -or
        -not $observationMatch.Success -or
        -not $logText.Contains('Enabling helsteraMobs v1.0.0')) {
    throw 'Focused report checks found incomplete Paper/plugin startup evidence.'
}
$redactedLog = [regex]::Replace($logText,
    '(?i)([?&]token=)[^&\s"''<>]+', '$1[REDACTED]')
$redactedLog = [regex]::Replace($redactedLog,
    '(?i)(__cf_bm=)[^;\s"''<>]+', '$1[REDACTED]')
$redactedLog = [regex]::Replace($redactedLog,
    '(令牌[:：]\s*)\S+', '$1[REDACTED]')
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
    evidenceMode = 'exploratory'
    paperVersion = $paperMatch.Groups[1].Value
    paperApiVersion = $paperMatch.Groups[2].Value
    pluginEnabled = $true
    pluginSha256 = $pluginSha256
    externalPlugin = 'MythicMobs 5.7.2 (Modrinth FTWvGuE5)'
    externalPluginSha256 = $mythicSha256
    sharedSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $mythicSkills 'percent-health-skills.yml') -Algorithm SHA256).Hash.ToLowerInvariant()
    paperJarSha256 = (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant()
    log = 'logs/latest.log'
    expectedProbeIds = @('percentHealthRuntime')
    probes = @([ordered]@{
        id = 'percentHealthRuntime'
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
Write-Output "Focused percent-health Paper probe passed: $fixtureId"
Write-Output "Report SHA-256: $reportSha256"
Write-Output "Compressed log SHA-256: $logSha256"
