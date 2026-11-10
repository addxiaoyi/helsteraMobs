$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$matrixPath = Join-Path $PSScriptRoot 'version-matrix.json'
$evidencePath = Join-Path $PSScriptRoot 'release-evidence.json'
$matrix = Get-Content -Raw $matrixPath | ConvertFrom-Json
$evidence = Get-Content -Raw $evidencePath | ConvertFrom-Json

if ($matrix.schemaVersion -ne 1) { throw 'Unsupported version matrix schema.' }
if ($matrix.releasePolicy.requiresExactServerBuild -ne $true) {
    throw 'Version matrix must require an exact server build for release evidence.'
}
if ($matrix.releasePolicy.requiresMatchingPluginSha256 -ne $true) {
    throw 'Version matrix must require a matching plugin SHA-256.'
}

$release = @($matrix.combinations | Where-Object { $_.releaseValidated -eq $true })
if ($release.Count -ne 1) { throw "Expected exactly one release-validated combination, found $($release.Count)." }
$entry = $release[0]
$baselineValid = $entry.serverFamily -eq 'Paper' -and $entry.serverVersion -eq '1.21.1-133' -and $entry.minecraftVersion -eq '1.21.1' -and $entry.evidenceLevel -eq 'strict'
if (-not $baselineValid) {
    throw 'The release baseline must be Paper 1.21.1-133 / Minecraft 1.21.1 with strict evidence.'
}
if ([string]$entry.serverJarSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        [string]$entry.serverJarSha256 -cne '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9') {
    throw 'Paper 1.21.1-133 release baseline must pin the verified official Paperclip SHA-256.'
}
if (@($evidence | Select-Object -First 1).fixtureId -ne $entry.releaseFixtureId) {
    throw 'Version matrix release baseline must match the newest release evidence manifest entry.'
}

$manifestEntry = @($evidence | Where-Object { $_.fixtureId -eq $entry.releaseFixtureId })
if ($manifestEntry.Count -ne 1) { throw "Release fixture is missing from release-evidence.json: $($entry.releaseFixtureId)" }
if ($manifestEntry[0].report -ne $entry.releaseReport) {
    throw 'Version matrix report does not match the release evidence manifest.'
}
$reportPath = Join-Path $root $entry.releaseReport
if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
    throw "Version matrix report file does not exist: $($entry.releaseReport)"
}
$report = Get-Content -LiteralPath $reportPath -Raw -Encoding utf8 | ConvertFrom-Json
$expectedBuildPattern = '^' + [regex]::Escape([string]$entry.serverVersion) + '(?:-|$)'
$expectedMinecraftPattern = '^' + [regex]::Escape([string]$entry.minecraftVersion) + '(?:-|$)'
$manifestPluginSha256 = [string]$manifestEntry[0].pluginSha256
if ($manifestEntry[0].fullReleaseCatalog -isnot [bool] -or -not $manifestEntry[0].fullReleaseCatalog) {
    throw 'Version matrix release report must be marked as a full release catalog in release-evidence.json.'
}
if ($manifestPluginSha256 -cnotmatch '^[0-9a-f]{64}$' -or
        [string]$report.pluginSha256 -cne $manifestPluginSha256) {
    throw 'Version matrix release report plugin SHA-256 does not match its release evidence manifest entry.'
}
if ($report.schemaVersion -ne 1 -or [string]$report.evidenceMode -cne 'release' -or
        -not $report.pluginEnabled -or
        [string]$report.paperVersion -notmatch $expectedBuildPattern -or
        [string]$report.paperApiVersion -notmatch $expectedMinecraftPattern) {
    throw ('Version matrix release report metadata mismatch: expected Paper build {0}, Minecraft API {1}, and release evidence.' -f
        $entry.serverVersion, $entry.minecraftVersion)
}
& (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $reportPath `
    -RequireTargetPaperVersion -RequireFullReleaseCatalog `
    -ExpectedPaperVersion ([string]$entry.minecraftVersion) `
    -ExpectedPaperBuild ([string]$entry.serverVersion) `
    -ExpectedPluginSha256 $manifestPluginSha256

$readme = Get-Content -Raw (Join-Path $root 'README.md')
$parity = Get-Content -Raw (Join-Path $root 'MYTHICMOBS_PARITY_MATRIX.md')
$startup = Get-Content -Raw (Join-Path $PSScriptRoot 'STARTUP_EVIDENCE.md')
$paperReadme = Get-Content -Raw (Join-Path $PSScriptRoot 'README.md')
$fixture = [regex]::Escape([string]$entry.releaseFixtureId)
if ($readme -notmatch "Latest strict fixture ``$fixture``" -or
        $paperReadme -notmatch "Latest strict fixture ``$fixture``" -or
        $startup -notmatch "(?s)^# Paper Startup Evidence\s+Latest fixture ``$fixture``" -or
        $parity -notmatch "(?m)^\| Paper integration suite \| mapped-unverified \| Current release fixture ``$fixture``") {
    throw 'Release fixture references in README, parity matrix, or Paper evidence do not match the version matrix.'
}

foreach ($combination in $matrix.combinations) {
    if (-not $combination.releaseValidated -and ($null -ne $combination.releaseFixtureId -or $null -ne $combination.releaseReport)) {
        throw "Non-release combination has release evidence: $($combination.id)"
    }
}

Write-Host "Version matrix contract passed: $($matrix.combinations.Count) combinations, baseline=$($entry.id)."
