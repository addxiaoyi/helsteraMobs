param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^srv-paper-[A-Za-z0-9][A-Za-z0-9_.-]*$')]
    [string]$FixtureDirectory,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^paper-1\.21\.1-[a-z0-9-]+-strict-[0-9]{8}-r[1-9][0-9]*$')]
    [string]$ReportName
)

$ErrorActionPreference = 'Stop'

function Protect-Log([string]$Text) {
    $safe = [regex]::Replace($Text, '(?i)([?&]token=)[^&\s"''<>]+', '$1[REDACTED]')
    $safe = [regex]::Replace($safe, '(?i)(__cf_bm=)[^;\s"''<>]+', '$1[REDACTED]')
    # A bare token= is also used for selector IDs in probe evidence, not authentication.
    $safe = [regex]::Replace($safe, '(令牌[ \t]*[:：][ \t]*)[A-Za-z0-9._~-]+', '$1[REDACTED]')
    return [regex]::Replace($safe, '(?i)(\bauthorization\s*:\s*bearer\s+)[^\s"''<>]+', '$1[REDACTED]')
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$fixturePath = [System.IO.Path]::GetFullPath((Join-Path $root $FixtureDirectory))
if (-not (Test-Path -LiteralPath $fixturePath -PathType Container) -or
        (Get-Item -LiteralPath $fixturePath).LinkType) {
    throw 'Fixture directory must be an existing srv-paper directory inside the repository.'
}

$reportSource = Join-Path $fixturePath 'integration-report.json'
if (-not (Test-Path -LiteralPath $reportSource -PathType Leaf) -or
        (Get-Item -LiteralPath $reportSource).LinkType) {
    throw 'The integration report must be a regular file inside its fixture directory.'
}
$reportText = Get-Content -LiteralPath $reportSource -Raw -Encoding utf8
if ((Protect-Log $reportText) -cne $reportText) { throw 'Report contains unredacted credential values.' }
$report = $reportText | ConvertFrom-Json
if ([string]$report.fixtureDirectory -cne $FixtureDirectory -or [string]$report.evidenceMode -cne 'release') {
    throw 'Only a release report from the selected fixture directory can be archived.'
}
$versionMatrix = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'version-matrix.json') -Raw -Encoding utf8 | ConvertFrom-Json
$releaseCombinations = @($versionMatrix.combinations | Where-Object { $_.releaseValidated -eq $true })
if ($versionMatrix.schemaVersion -ne 1 -or $releaseCombinations.Count -ne 1) {
    throw 'Exactly one release-validated Paper combination is required.'
}
$release = $releaseCombinations[0]
$pluginPath = Join-Path $root 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
if (-not (Test-Path -LiteralPath $pluginPath -PathType Leaf)) {
    throw 'The built full plugin JAR is required to archive release evidence.'
}
$pluginSha256 = (Get-FileHash -LiteralPath $pluginPath -Algorithm SHA256).Hash.ToLowerInvariant()
$python = & (Join-Path $PSScriptRoot 'resolve-python.ps1')
$validator = Join-Path $PSScriptRoot 'validate-report.py'
$schema = Join-Path $PSScriptRoot 'integration-report.schema.json'
& $python.Source @($python.Prefix) $validator $schema $reportSource
if ($LASTEXITCODE -ne 0) { throw 'Release report does not match the integration report schema.' }
& (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $reportSource `
    -RequireTargetPaperVersion -RequireFullReleaseCatalog `
    -ExpectedPaperVersion ([string]$release.minecraftVersion) `
    -ExpectedPaperBuild ([string]$release.serverVersion) `
    -ExpectedPluginSha256 $pluginSha256

if ([string]$report.log -cne 'logs/latest.log') {
    throw 'Expected the full runner server log at logs/latest.log.'
}
$sourceLog = Join-Path $fixturePath 'logs/latest.log'
$clientLogs = @('audience-client.stdout.log', 'menu-client.process.stdout.log', 'menu-client.stderr.log',
    'menu-client.stdout.log', 'raw-potion-client.stdout.log')
if (-not (Test-Path -LiteralPath $sourceLog -PathType Leaf) -or
        (Get-Item -LiteralPath (Split-Path -Parent $sourceLog)).LinkType -or
        (Get-Item -LiteralPath $sourceLog).LinkType) {
    throw 'The full runner server log must exist inside its fixture directory.'
}
foreach ($clientLog in $clientLogs) {
    $clientPath = Join-Path $fixturePath $clientLog
    if (-not (Test-Path -LiteralPath $clientPath -PathType Leaf) -or (Get-Item -LiteralPath $clientPath).LinkType) {
        throw "Missing client workflow log: $clientLog"
    }
}

$reportsPath = Join-Path $PSScriptRoot 'reports'
[void][System.IO.Directory]::CreateDirectory($reportsPath)
if ((Get-Item -LiteralPath $reportsPath).LinkType) {
    throw 'The reports directory must not be a symbolic link.'
}
$reportPath = Join-Path $reportsPath "$ReportName.json"
$logArchivePath = Join-Path $reportsPath "$ReportName.log.gz"
$clientArchivePath = Join-Path $reportsPath "$ReportName.client-logs.zip"
foreach ($path in @($reportPath, $logArchivePath, $clientArchivePath)) {
    if (Test-Path -LiteralPath $path) { throw "Refusing to overwrite existing evidence: $path" }
}

$createdPaths = [System.Collections.Generic.List[string]]::new()
try {
    $serverText = Protect-Log (Get-Content -LiteralPath $sourceLog -Raw -Encoding utf8)
    if ((Protect-Log $serverText) -cne $serverText) { throw 'Server log redaction is incomplete.' }
    $gzipFile = [System.IO.File]::Open($logArchivePath, [System.IO.FileMode]::CreateNew)
    $createdPaths.Add($logArchivePath)
    try {
        $gzip = [System.IO.Compression.GzipStream]::new($gzipFile,
            [System.IO.Compression.CompressionLevel]::Optimal)
        try {
            $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($serverText)
            $gzip.Write($bytes, 0, $bytes.Length)
        } finally { $gzip.Dispose() }
    } finally { $gzipFile.Dispose() }

    $clientFile = [System.IO.File]::Open($clientArchivePath, [System.IO.FileMode]::CreateNew)
    $createdPaths.Add($clientArchivePath)
    try {
        $archive = [System.IO.Compression.ZipArchive]::new($clientFile,
            [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($clientLog in $clientLogs) {
                $clientText = Protect-Log (Get-Content -LiteralPath (Join-Path $fixturePath $clientLog) -Raw -Encoding utf8)
                if ((Protect-Log $clientText) -cne $clientText) { throw "Client log redaction is incomplete: $clientLog" }
                $entry = $archive.CreateEntry($clientLog, [System.IO.Compression.CompressionLevel]::Optimal)
                $stream = $entry.Open()
                try {
                    $writer = [System.IO.StreamWriter]::new($stream, [System.Text.UTF8Encoding]::new($false))
                    try { $writer.Write($clientText) } finally { $writer.Dispose() }
                } finally { $stream.Dispose() }
            }
        } finally { $archive.Dispose() }
    } finally { $clientFile.Dispose() }

    [System.IO.File]::Copy($reportSource, $reportPath)
    $createdPaths.Add($reportPath)
} catch {
    foreach ($path in $createdPaths) {
        if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
    }
    throw
}

Write-Output "Report: $reportPath"
Write-Output "Report SHA-256: $((Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Output "Server log: $logArchivePath"
Write-Output "Server log SHA-256: $((Get-FileHash -LiteralPath $logArchivePath -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Output "Client logs: $clientArchivePath"
Write-Output "Client logs SHA-256: $((Get-FileHash -LiteralPath $clientArchivePath -Algorithm SHA256).Hash.ToLowerInvariant())"
