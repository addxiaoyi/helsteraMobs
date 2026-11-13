param(
    [ValidatePattern('^r[1-9]\d*$')]
    [string]$EvidenceRevision = 'r1',
    [string]$ServerDirectory = ''
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$reportStem = "griefprevention-claims-paper-1.21.1-20261113-$EvidenceRevision"
$ServerDirectory = if ([string]::IsNullOrWhiteSpace($ServerDirectory)) {
    "srv-paper-griefprevention-claims-20261113-$EvidenceRevision"
} else { $ServerDirectory }
$server = if ([System.IO.Path]::IsPathRooted($ServerDirectory)) {
    [System.IO.Path]::GetFullPath($ServerDirectory)
} else { [System.IO.Path]::GetFullPath((Join-Path $repo $ServerDirectory)) }
$paper = if ([string]::IsNullOrWhiteSpace($env:PAPER_JAR_PATH)) {
    Join-Path $repo 'srv-paper-worldguard-parity-20261110-r1/paper.jar'
} else { [System.IO.Path]::GetFullPath($env:PAPER_JAR_PATH) }
$paperLibraries = if ([string]::IsNullOrWhiteSpace($env:PAPER_LIBRARIES_PATH)) {
    Join-Path $repo 'srv-paper-state-natural-repair-20261103-r2/libraries'
} else { [System.IO.Path]::GetFullPath($env:PAPER_LIBRARIES_PATH) }
$plugin = Join-Path $repo 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
$fixtureJar = Join-Path $PSScriptRoot 'griefprevention-fixture/target/griefprevention-paper-fixture.jar'
$reportPath = Join-Path $PSScriptRoot "reports/$reportStem.json"
$compressedLogPath = Join-Path $PSScriptRoot "reports/$reportStem.log.gz"
$cache = Join-Path $PSScriptRoot '.external-cache/griefprevention'
$gpJar = Join-Path $cache 'GriefPrevention-16.18.5.jar'
$gpSha1 = '5fd724fe7b70ca243381cfb7cc7dfa869eef1719'
$gpSha512 = '35e5bced4cec2ea13f2b7bdbd7ab11da04c2650e2b8b3c1aefff0d518e6605eece951bcb9fd33425cf3363bfc43683c5b224d4126515d8482847d376b0a5bca2'

foreach ($required in @($paper, $plugin, $fixtureJar)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required fixture artifact not found: $required"
    }
}
if (-not (Test-Path -LiteralPath $paperLibraries -PathType Container)) {
    throw "Paper runtime libraries not found: $paperLibraries"
}
if (Test-Path -LiteralPath $server) { throw "Refusing to reuse an existing Paper fixture directory: $server" }
if ((Test-Path -LiteralPath $reportPath) -or (Test-Path -LiteralPath $compressedLogPath)) {
    throw 'GriefPrevention evidence output already exists; choose a new revision.'
}

if (-not (Test-Path -LiteralPath $gpJar -PathType Leaf)) {
    New-Item -ItemType Directory -Path $cache -Force | Out-Null
    Invoke-WebRequest -Uri 'https://cdn.modrinth.com/data/O4o4mKaq/versions/iaJtn30B/GriefPrevention.jar' `
        -OutFile $gpJar
}
$actualGpSha1 = (Get-FileHash -LiteralPath $gpJar -Algorithm SHA1).Hash.ToLowerInvariant()
$actualGpSha512 = (Get-FileHash -LiteralPath $gpJar -Algorithm SHA512).Hash.ToLowerInvariant()
if ($actualGpSha1 -cne $gpSha1 -or $actualGpSha512 -cne $gpSha512) {
    throw "GriefPrevention artifact hash mismatch: SHA1=$actualGpSha1 SHA512=$actualGpSha512"
}

Add-Type -AssemblyName System.IO.Compression
$paperArchive = [System.IO.Compression.ZipFile]::OpenRead($paper)
try {
    $manifestEntry = $paperArchive.GetEntry('META-INF/MANIFEST.MF')
    if ($null -eq $manifestEntry) { throw 'Paper manifest not found.' }
    $reader = [System.IO.StreamReader]::new($manifestEntry.Open())
    try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $paperArchive.Dispose() }
$versionMatch = [regex]::Match($manifest, '(?m)^Implementation-Version:\s*(.+?)\s*$')
$paperVersion = if ($versionMatch.Success) { $versionMatch.Groups[1].Value.Trim() } else { '' }
if ($paperVersion -notmatch '^1\.21\.1-133(?:-|$)') {
    throw "This fixture requires Paper 1.21.1-133; found '$paperVersion'."
}

New-Item -ItemType Directory -Path $server -Force | Out-Null
Copy-Item -LiteralPath $paper -Destination (Join-Path $server 'paper.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination (Join-Path $server 'server.properties')
Set-Content -LiteralPath (Join-Path $server 'eula.txt') -Value 'eula=true' -Encoding ascii
$plugins = Join-Path $server 'plugins'
New-Item -ItemType Directory -Path $plugins -Force | Out-Null
Copy-Item -LiteralPath $plugin -Destination (Join-Path $plugins 'helsteraMobs.jar')
Copy-Item -LiteralPath $gpJar -Destination (Join-Path $plugins 'GriefPrevention.jar')
Copy-Item -LiteralPath $fixtureJar -Destination (Join-Path $plugins 'GriefPreventionParityFixture.jar')
$hostData = Join-Path $plugins 'helsteraMobs'
New-Item -ItemType Directory -Path $hostData -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'config.yml') -Destination (Join-Path $hostData 'config.yml')
$hostConfig = Join-Path $hostData 'config.yml'
$configText = [System.IO.File]::ReadAllText($hostConfig)
$configText = [regex]::Replace($configText, '(?m)^(  self-test(?:-[A-Za-z0-9-]+)?):\s*(?:true|false)\s*$', '$1: false')
[System.IO.File]::WriteAllText($hostConfig, $configText, [System.Text.UTF8Encoding]::new($false))

$logPath = Join-Path $server 'logs/latest.log'
$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'java'
$libraryJars = @(Get-ChildItem -LiteralPath $paperLibraries -Recurse -File -Filter '*.jar' |
    ForEach-Object { $_.FullName })
if ($libraryJars.Count -eq 0) { throw 'Paper runtime libraries directory is empty.' }
$classPathEntries = @((Join-Path $server 'paper.jar')) + $libraryJars
$classPath = [string]::Join([System.IO.Path]::PathSeparator, [string[]]$classPathEntries)
$psi.Arguments = '-Xms1G -Xmx2G -cp "' + $classPath + '" org.bukkit.craftbukkit.Main nogui'
$psi.WorkingDirectory = $server
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $psi
if (-not $process.Start()) { throw 'Failed to start isolated Paper.' }

try {
    $deadline = [DateTime]::UtcNow.AddMinutes(4)
    $probeLine = $null
    while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
        if (Test-Path -LiteralPath $logPath) {
            $probeLine = Get-Content -LiteralPath $logPath -Encoding utf8 |
                Where-Object { $_ -match 'GRIEFPREVENTION_PARITY_PROBE ' } | Select-Object -Last 1
            if ($probeLine) { break }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $probeLine) {
        $tail = if (Test-Path -LiteralPath $logPath) { (Get-Content -LiteralPath $logPath -Tail 60 -Encoding utf8) -join "`n" }
            else { 'Paper did not create logs/latest.log.' }
        throw "GriefPrevention fixture did not report. Recent log:`n$tail"
    }
    $process.StandardInput.WriteLine('stop')
    $process.StandardInput.Flush()
    if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'Paper did not stop after the probe.' }
} finally {
    if (-not $process.HasExited) {
        try { $process.StandardInput.WriteLine('stop'); $process.StandardInput.Flush() } catch { }
        if (-not $process.WaitForExit(15000)) { $process.Kill() }
    }
}
if ($process.ExitCode -ne 0) { throw "Paper exited with code $($process.ExitCode)." }

$probeLine = Get-Content -LiteralPath $logPath -Encoding utf8 |
    Where-Object { $_ -match 'GRIEFPREVENTION_PARITY_PROBE ' } | Select-Object -Last 1
$payload = [regex]::Match($probeLine, 'GRIEFPREVENTION_PARITY_PROBE\s+(.+)$').Groups[1].Value
$observed = @{}
foreach ($token in ($payload -split '\s+')) {
    $pair = $token -split '=', 2
    if ($pair.Count -eq 2) { $observed[$pair[0]] = $pair[1] }
}
$requiredChecks = @(
    'provider_connected', 'claim_query_capability', 'in_claim_inside', 'in_claim_subclaim',
    'in_claim_outside', 'near_claim_inside', 'near_claim_exact_block_boundary',
    'near_claim_below_boundary', 'near_claim_outside', 'near_claim_large_radius_fallback',
    'imported_in_claim_inside', 'imported_in_claim_outside', 'imported_near_claim_default',
    'imported_near_claim_outside', 'imported_near_claim_explicit', 'imported_near_claims_alias',
    'malformed_near_claim_preserved'
)
if ($observed.result -cne 'passed' -or $observed.griefprevention -cne '16.18.5') {
    throw "GriefPrevention fixture failed or used unexpected plugin version: $payload"
}
foreach ($check in $requiredChecks) {
    if ($observed[$check] -cne 'true') { throw "GriefPrevention check '$check' failed: $payload" }
}

$rawLog = [System.IO.File]::ReadAllText($logPath)
$redactedLog = [regex]::Replace($rawLog,
    '(?i)((?:令牌|token)\s*[:=]\s*)[a-f0-9]{8,}', '$1[REDACTED]')
$compressed = [System.IO.File]::Create($compressedLogPath)
$gzip = [System.IO.Compression.GZipStream]::new($compressed, [System.IO.Compression.CompressionLevel]::Optimal)
$writer = [System.IO.StreamWriter]::new($gzip, [System.Text.UTF8Encoding]::new($false))
try { $writer.Write($redactedLog) } finally { $writer.Dispose(); $gzip.Dispose() }
$assertions = foreach ($check in $requiredChecks) {
    [ordered]@{ id = $check; status = 'passed'; observed = $observed[$check] }
}
$report = [ordered]@{
    schemaVersion = 1
    fixture = "paper-1.21.1-griefprevention-claims-20261113-$EvidenceRevision"
    generatedAtUtc = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    paper = [ordered]@{ implementationVersion = $paperVersion; sha256 = (Get-FileHash $paper -Algorithm SHA256).Hash.ToLowerInvariant() }
    helsteraMobsSha256 = (Get-FileHash $plugin -Algorithm SHA256).Hash.ToLowerInvariant()
    externalPlugins = @([ordered]@{
        name = 'GriefPrevention'; version = $observed.griefprevention
        sha1 = $actualGpSha1; sha512 = $actualGpSha512
    })
    probes = @($assertions)
    compressedLog = [ordered]@{
        path = "$reportStem.log.gz"
        sha256 = (Get-FileHash $compressedLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
[System.IO.File]::WriteAllText($reportPath,
    ($report | ConvertTo-Json -Depth 8) + "`n", [System.Text.UTF8Encoding]::new($false))
Write-Output "GriefPrevention Paper fixture passed: $reportPath"
