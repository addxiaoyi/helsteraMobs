param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^srv-paper-[A-Za-z0-9][A-Za-z0-9_.-]*$')]
    [string]$FixtureDirectory,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^r[1-9][0-9]*$')]
    [string]$EvidenceRevision
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression

function Protect-Log([string]$Text) {
    $safe = [regex]::Replace($Text, '(?i)([?&]token=)[^&\s"<>]+', '$1[REDACTED]')
    $safe = [regex]::Replace($safe, '(?i)(__cf_bm=)[^;\s"''<>]+', '$1[REDACTED]')
    $safe = [regex]::Replace($safe, '(令牌[ \t]*[:：][ \t]*)[A-Za-z0-9._~-]+', '$1[REDACTED]')
    return [regex]::Replace($safe, '(?i)(\bauthorization\s*:\s*bearer\s+)[^\s"<>]+', '$1[REDACTED]')
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$rootPrefix = [System.IO.Path]::GetFullPath($root + [System.IO.Path]::DirectorySeparatorChar)
$fixturePath = [System.IO.Path]::GetFullPath((Join-Path $root $FixtureDirectory))
if (-not $fixturePath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $fixturePath -PathType Container) -or
        (Get-Item -LiteralPath $fixturePath).LinkType) {
    throw 'Fixture must be a regular directory inside the repository.'
}

$evidencePath = Join-Path $fixturePath 'skilltree-restart-evidence.json'
if (-not (Test-Path -LiteralPath $evidencePath -PathType Leaf) -or
        (Get-Item -LiteralPath $evidencePath).LinkType) {
    throw 'SkillTree restart evidence JSON is missing or not a regular file.'
}
$evidenceText = [System.IO.File]::ReadAllText($evidencePath)
if ((Protect-Log $evidenceText) -cne $evidenceText) {
    throw 'Evidence JSON contains an unredacted credential value.'
}
$evidence = $evidenceText | ConvertFrom-Json
$required = @(
    $evidence.schemaVersion -in @(1, 2)
    $evidence.fixtureDirectory -ceq $FixtureDirectory
    $evidence.firstBoot.result -ceq 'passed'
    $evidence.firstBoot.branchStateSaved -eq $true
    $evidence.firstBoot.unsupportedVariableRejected -eq $true
    $evidence.secondBoot.result -ceq 'passed'
    $evidence.secondBoot.uuidMarkersRestored -eq $true
    $evidence.secondBoot.dottedVariableNamesRestored -eq $true
    $evidence.secondBoot.dottedLocationNamesRestored -eq $true
    $evidence.secondBoot.reservedAndDottedBranchNamesRestored -eq $true
    $evidence.secondBoot.listVariableRestored -eq $true
    $evidence.secondBoot.setVariableRestored -eq $true
    $evidence.secondBoot.nestedMapVariableRestored -eq $true
    $evidence.secondBoot.collectionsRestored -eq $true
    $evidence.secondBoot.advancedBranchUnlocked -eq $true
    $evidence.entityDeath.result -ceq 'passed'
    $evidence.entityDeath.deadEntitySnapshotRemoved -eq $true
    $evidence.entityDeath.survivorSnapshotRetained -eq $true
)
if ($required -contains $false) { throw 'Fixture evidence is incomplete or does not pass every restart assertion.' }
$evidenceFiles = @('skilltree-write.log', 'skilltree-verify.log')
if ($evidence.schemaVersion -eq 2) {
    & (Join-Path $PSScriptRoot 'assert-skilltree-expiry-evidence.ps1') -FixturePath $fixturePath
    $evidenceFiles += @('skilltree-final.log') + @($evidence.snapshots.path)
    $matrix = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'version-matrix.json') -Raw | ConvertFrom-Json
    $baseline = @($matrix.combinations | Where-Object { $_.releaseValidated -eq $true })
    if ($baseline.Count -ne 1 -or $baseline[0].serverVersion -cne '1.21.1-133' -or
            $evidence.paperJarSha256 -cne $baseline[0].serverJarSha256 -or $evidence.paperVersion -notlike '1.21.1-133-*') {
        throw 'Timed restart evidence does not match the pinned Paper release baseline.'
    }
    $pluginJar = Join-Path $root 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    if ((Get-FileHash -LiteralPath $pluginJar -Algorithm SHA256).Hash.ToLowerInvariant() -cne $evidence.pluginSha256) {
        throw 'Timed restart evidence does not match the currently built plugin artifact.'
    }
}

$revisionDate = [datetime]::Parse([string]$evidence.generatedAtUtc).ToUniversalTime().ToString('yyyyMMdd')
$series = if ($evidence.schemaVersion -eq 2) { 'skilltree-expiry-restart' } else { 'skilltree-dotted-names-restart' }
$reportBase = "paper-1.21.1-$series-$revisionDate-$EvidenceRevision"
$reportsPath = Join-Path $PSScriptRoot 'reports'
$reportDestination = Join-Path $reportsPath "$reportBase.json"
$logsDestination = Join-Path $reportsPath "$reportBase-logs.zip"
if ((Get-Item -LiteralPath $reportsPath).LinkType) { throw 'Reports directory must not be a symbolic link.' }
foreach ($destination in @($reportDestination, $logsDestination)) {
    if (Test-Path -LiteralPath $destination) { throw "Refusing to overwrite evidence: $destination" }
}

$created = [System.Collections.Generic.List[string]]::new()
try {
    foreach ($name in $evidenceFiles) {
        $source = Join-Path $fixturePath $name
        if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or (Get-Item -LiteralPath $source).LinkType) {
            throw "Missing regular boot log: $name"
        }
    }
    [System.IO.File]::Copy($evidencePath, $reportDestination)
    $created.Add($reportDestination)
    $archiveFile = [System.IO.File]::Open($logsDestination, [System.IO.FileMode]::CreateNew)
    $created.Add($logsDestination)
    try {
        $archive = [System.IO.Compression.ZipArchive]::new(
            $archiveFile, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($name in $evidenceFiles) {
                $source = Join-Path $fixturePath $name
                $text = Protect-Log ([System.IO.File]::ReadAllText($source))
                if ((Protect-Log $text) -cne $text) { throw "Log redaction is incomplete: $name" }
                $entry = $archive.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
                $stream = $entry.Open()
                try {
                    $writer = [System.IO.StreamWriter]::new($stream, [System.Text.UTF8Encoding]::new($false))
                    try { $writer.Write($text) } finally { $writer.Dispose() }
                } finally { $stream.Dispose() }
            }
        } finally { $archive.Dispose() }
    } finally { $archiveFile.Dispose() }
} catch {
    foreach ($path in $created) {
        if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
    }
    throw
}

Write-Output "Evidence: $reportDestination"
Write-Output "Evidence SHA-256: $((Get-FileHash -LiteralPath $reportDestination -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Output "Redacted boot logs: $logsDestination"
Write-Output "Boot log archive SHA-256: $((Get-FileHash -LiteralPath $logsDestination -Algorithm SHA256).Hash.ToLowerInvariant())"
