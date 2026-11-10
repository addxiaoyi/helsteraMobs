param(
    [ValidatePattern('^r[1-9]\d*$')]
    [string]$EvidenceRevision = 'r1',
    [string]$ServerDirectory = ''
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$reportStem = "worldguard-region-paper-1.21.1-20261110-$EvidenceRevision"
$defaultServerDirectory = "srv-paper-worldguard-parity-20261110-$EvidenceRevision"
$ServerDirectory = if ([string]::IsNullOrWhiteSpace($ServerDirectory)) {
    $defaultServerDirectory
} else {
    $ServerDirectory
}
$server = if ([System.IO.Path]::IsPathRooted($ServerDirectory)) {
    [System.IO.Path]::GetFullPath($ServerDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repo $ServerDirectory))
}
$paper = if ([string]::IsNullOrWhiteSpace($env:PAPER_JAR_PATH)) {
    Join-Path $repo 'srv-paper-skilltree-atomic-strict-20261110-r1/paper.jar'
} else {
    [System.IO.Path]::GetFullPath($env:PAPER_JAR_PATH)
}
$paperLibraries = if ([string]::IsNullOrWhiteSpace($env:PAPER_LIBRARIES_PATH)) {
    Join-Path $repo 'srv-paper-spawner-natural-restart-20261106-r5/libraries'
} else {
    [System.IO.Path]::GetFullPath($env:PAPER_LIBRARIES_PATH)
}
$plugin = Join-Path $repo 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
$fixtureJar = Join-Path $PSScriptRoot 'worldguard-fixture/target/worldguard-paper-fixture.jar'
$reportPath = Join-Path $PSScriptRoot "reports/$reportStem.json"
$compressedLogPath = Join-Path $PSScriptRoot "reports/$reportStem.log.gz"
$cache = Join-Path $PSScriptRoot '.external-cache/worldguard'

if (-not (Test-Path -LiteralPath $paper -PathType Leaf)) {
    throw "Paper jar not found: $paper"
}
if (-not (Test-Path -LiteralPath $paperLibraries -PathType Container)) {
    throw "Paper runtime libraries not found: $paperLibraries (set PAPER_LIBRARIES_PATH to this Paper build's libraries directory)."
}
if (-not (Test-Path -LiteralPath $plugin -PathType Leaf)) {
    throw "Build the full plugin first: $plugin"
}
if (-not (Test-Path -LiteralPath $fixtureJar -PathType Leaf)) {
    throw "Build the fixture first: $fixtureJar"
}
if (Test-Path -LiteralPath $server) {
    throw "Refusing to reuse or replace an existing Paper fixture directory: $server"
}
if ((Test-Path -LiteralPath $reportPath) -or (Test-Path -LiteralPath $compressedLogPath)) {
    throw 'WorldGuard evidence output already exists; choose a new report revision before rerunning.'
}

function Get-PinnedArtifact([string]$Name, [string]$Url, [string]$Sha256) {
    $path = Join-Path $cache $Name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        New-Item -ItemType Directory -Path $cache -Force | Out-Null
        $curlCommand = Get-Command curl.exe -ErrorAction SilentlyContinue
        if ($null -eq $curlCommand) {
            $curlCommand = Get-Command curl -CommandType Application -ErrorAction Stop |
                Select-Object -First 1
        }
        & $curlCommand.Source --fail --location --silent --show-error --retry 3 --output $path $Url
        if ($LASTEXITCODE -ne 0) { throw "Could not download pinned artifact $Name (curl exit $LASTEXITCODE)." }
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $Sha256) {
        throw "$Name SHA-256 mismatch: $actual (expected $Sha256)"
    }
    return $path
}

function Get-PaperImplementationVersion([string]$JarPath) {
    Add-Type -AssemblyName System.IO.Compression
    $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if ($null -eq $entry) { return $null }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try {
            $manifest = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
        $match = [regex]::Match($manifest, '(?m)^Implementation-Version:\s*(.+?)\s*$')
        if ($match.Success) { return $match.Groups[1].Value.Trim() }
        return $null
    } finally {
        $archive.Dispose()
    }
}

$paperVersion = Get-PaperImplementationVersion $paper
if ($paperVersion -notmatch '^1\.21\.1-133(?:-|$)') {
    throw "This fixture requires the release-validated Paper 1.21.1-133 build; found '$paperVersion'."
}

$worldGuardJar = Get-PinnedArtifact 'worldguard-bukkit-7.0.12-dist.jar' `
    'https://mediafilez.forgecdn.net/files/5719/698/worldguard-bukkit-7.0.12-dist.jar' `
    '48b136938b3cd4816a95887847ff28e56a06fb110103344bb574739c35f9e86c'
$worldEditJar = Get-PinnedArtifact 'worldedit-bukkit-7.3.8.jar' `
    'https://mediafilez.forgecdn.net/files/5830/450/worldedit-bukkit-7.3.8.jar' `
    'b11e40546987c1c9c5f8af216bba89a265a5ffac8dccd92273f61ebb86040556'

New-Item -ItemType Directory -Path $server -Force | Out-Null
Copy-Item -LiteralPath $paper -Destination (Join-Path $server 'paper.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination (Join-Path $server 'server.properties')
Set-Content -LiteralPath (Join-Path $server 'eula.txt') -Value 'eula=true' -Encoding ascii
$plugins = Join-Path $server 'plugins'
New-Item -ItemType Directory -Path $plugins -Force | Out-Null
Copy-Item -LiteralPath $plugin -Destination (Join-Path $plugins 'helsteraMobs.jar')
Copy-Item -LiteralPath $worldEditJar -Destination (Join-Path $plugins 'WorldEdit.jar')
Copy-Item -LiteralPath $worldGuardJar -Destination (Join-Path $plugins 'WorldGuard.jar')
Copy-Item -LiteralPath $fixtureJar -Destination (Join-Path $plugins 'WorldGuardParityFixture.jar')
$pluginData = Join-Path $plugins 'helsteraMobs'
New-Item -ItemType Directory -Path $pluginData -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'config.yml') -Destination (Join-Path $pluginData 'config.yml')
$configPath = Join-Path $pluginData 'config.yml'
$config = [System.IO.File]::ReadAllText($configPath)
$config = [regex]::Replace($config, '(?m)^(  self-test(?:-[A-Za-z0-9-]+)?):\s*(?:true|false)\s*$', '$1: false')
[System.IO.File]::WriteAllText($configPath, $config, [System.Text.UTF8Encoding]::new($false))

$logDirectory = Join-Path $server 'logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$logPath = Join-Path $logDirectory 'latest.log'
$psi = [System.Diagnostics.ProcessStartInfo]::new()
$psi.FileName = 'java'
$libraryJars = @(Get-ChildItem -LiteralPath $paperLibraries -Recurse -File -Filter '*.jar' |
    ForEach-Object { $_.FullName })
if ($libraryJars.Count -eq 0) { throw "Paper runtime libraries directory is empty: $paperLibraries" }
$separator = [System.IO.Path]::PathSeparator
$classPathEntries = @((Join-Path $server 'paper.jar')) + $libraryJars
$classPath = [string]::Join($separator, [string[]]$classPathEntries)
$psi.Arguments = '-Xms1G -Xmx2G -cp "' + $classPath + '" org.bukkit.craftbukkit.Main nogui'
$psi.WorkingDirectory = $server
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $psi
if (-not $process.Start()) { throw 'Failed to start the isolated Paper server.' }

try {
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    $probeLine = $null
    while ([DateTime]::UtcNow -lt $deadline -and -not $process.HasExited) {
        if (Test-Path -LiteralPath $logPath) {
            $probeLine = Get-Content -LiteralPath $logPath -Encoding utf8 |
                Where-Object { $_ -match 'WORLDGUARD_PARITY_PROBE ' } |
                Select-Object -Last 1
            if ($probeLine) { break }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $probeLine) {
        $tail = if (Test-Path -LiteralPath $logPath) {
            (Get-Content -LiteralPath $logPath -Tail 50 -Encoding utf8) -join "`n"
        } else { 'Paper did not create logs/latest.log.' }
        throw "WorldGuard fixture did not report within three minutes. Recent log:`n$tail"
    }

    $process.StandardInput.WriteLine('stop')
    $process.StandardInput.Flush()
    if (-not $process.WaitForExit(30000)) {
        $process.Kill()
        throw 'Paper did not stop after the WorldGuard fixture completed.'
    }
} finally {
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
if ($process.ExitCode -ne 0) { throw "Paper exited with code $($process.ExitCode)." }

$probeLine = Get-Content -LiteralPath $logPath -Encoding utf8 |
    Where-Object { $_ -match 'WORLDGUARD_PARITY_PROBE ' } |
    Select-Object -Last 1
$payload = [regex]::Match($probeLine, 'WORLDGUARD_PARITY_PROBE\s+(.+)$').Groups[1].Value
$observed = @{}
foreach ($token in ($payload -split '\s+')) {
    $pair = $token -split '=', 2
    if ($pair.Count -eq 2) { $observed[$pair[0]] = $pair[1] }
}
$requiredChecks = @(
    'provider_connected', 'region_query_capability', 'simple_inside', 'simple_outside',
    'union_inside_right', 'union_outside', 'intersection_inside_overlap',
    'intersection_outside_overlap', 'missing_region_false', 'imported_region_inside',
    'imported_region_outside', 'imported_not_region_inside', 'imported_not_region_outside'
)
if ($observed.result -cne 'passed' -or $observed.worldguard -notlike '7.0.12*' -or
        $observed.worldedit -notlike '7.3.8*') {
    throw "WorldGuard provider fixture failed or used unexpected plugin versions: $payload"
}
foreach ($check in $requiredChecks) {
    if ($observed[$check] -cne 'true') { throw "WorldGuard fixture check '$check' failed: $payload" }
}

$rawLog = [System.IO.File]::ReadAllText($logPath)
$redactedLog = [regex]::Replace($rawLog,
    '(?i)((?:令牌|token)\s*[:=]\s*)[a-f0-9]{8,}', '$1[REDACTED]')
$compressedStream = [System.IO.File]::Create($compressedLogPath)
$gzipStream = [System.IO.Compression.GZipStream]::new(
    $compressedStream, [System.IO.Compression.CompressionLevel]::Optimal)
$writer = [System.IO.StreamWriter]::new($gzipStream, [System.Text.UTF8Encoding]::new($false))
try {
    $writer.Write($redactedLog)
} finally {
    $writer.Dispose()
    $gzipStream.Dispose()
}

$assertions = foreach ($check in $requiredChecks) {
    [ordered]@{ id = $check; status = 'passed'; observed = $observed[$check] }
}
$report = [ordered]@{
    schemaVersion = 1
    fixture = "paper-1.21.1-worldguard-region-parity-20261110-$EvidenceRevision"
    generatedAtUtc = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    paper = [ordered]@{
        implementationVersion = $paperVersion
        sha256 = (Get-FileHash -LiteralPath $paper -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    helsteraMobsSha256 = (Get-FileHash -LiteralPath $plugin -Algorithm SHA256).Hash.ToLowerInvariant()
    externalPlugins = @(
        [ordered]@{
            name = 'WorldEdit'
            version = $observed.worldedit
            sha256 = (Get-FileHash -LiteralPath $worldEditJar -Algorithm SHA256).Hash.ToLowerInvariant()
        },
        [ordered]@{
            name = 'WorldGuard'
            version = $observed.worldguard
            sha256 = (Get-FileHash -LiteralPath $worldGuardJar -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    )
    probes = @($assertions)
    compressedLog = [ordered]@{
        path = "$reportStem.log.gz"
        sha256 = (Get-FileHash -LiteralPath $compressedLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}
$reportJson = $report | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($reportPath, $reportJson + "`n", [System.Text.UTF8Encoding]::new($false))
Write-Output "WorldGuard Paper fixture passed: $reportPath"
