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
if (@($evidence | Select-Object -First 1).fixtureId -ne $entry.releaseFixtureId) {
    throw 'Version matrix release baseline must match the newest release evidence manifest entry.'
}

$manifestEntry = @($evidence | Where-Object { $_.fixtureId -eq $entry.releaseFixtureId })
if ($manifestEntry.Count -ne 1) { throw "Release fixture is missing from release-evidence.json: $($entry.releaseFixtureId)" }
if ($manifestEntry[0].report -ne $entry.releaseReport) {
    throw 'Version matrix report does not match the release evidence manifest.'
}
if (-not (Test-Path (Join-Path $root $entry.releaseReport))) {
    throw "Version matrix report file does not exist: $($entry.releaseReport)"
}

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
