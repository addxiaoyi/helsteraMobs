param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
    [switch]$RequirePresent
)

$ErrorActionPreference = 'Stop'
$documents = @(
    (Join-Path $Root 'README.md'),
    (Join-Path $Root 'MYTHICMOBS_PARITY_MATRIX.md'),
    (Join-Path $PSScriptRoot 'README.md'),
    (Join-Path $PSScriptRoot 'STARTUP_EVIDENCE.md')
)
$fixtureIds = [System.Collections.Generic.HashSet[string]]::new()
$pattern = '(?<![A-Za-z0-9_-])(srv-[A-Za-z0-9][A-Za-z0-9_-]*)(?![A-Za-z0-9_-])'

foreach ($document in $documents) {
    if (-not (Test-Path -LiteralPath $document -PathType Leaf)) {
        throw "Evidence document not found: $document"
    }
    $text = Get-Content -LiteralPath $document -Raw -Encoding utf8
    foreach ($match in [regex]::Matches($text, $pattern)) {
        [void]$fixtureIds.Add($match.Groups[1].Value)
    }
}

$missing = [System.Collections.Generic.List[string]]::new()
$present = [System.Collections.Generic.List[string]]::new()
foreach ($fixtureId in ($fixtureIds | Sort-Object)) {
    $path = Join-Path $Root $fixtureId
    if (Test-Path -LiteralPath $path -PathType Container) {
        $present.Add($fixtureId)
    } else {
        $missing.Add($fixtureId)
    }
}

Write-Output ("Evidence fixture references: {0}" -f $fixtureIds.Count)
Write-Output ("Present: {0}" -f $present.Count)
Write-Output ("Missing: {0}" -f $missing.Count)
if ($missing.Count -gt 0) {
    Write-Output 'Missing fixture directories:'
    $missing | ForEach-Object { Write-Output ("- {0}" -f $_) }
    if ($RequirePresent) {
        exit 1
    }
    Write-Output 'Historical or external fixture references are reported but do not fail the default audit.'
}
Write-Output 'Evidence fixture audit passed.'
