param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
    [switch]$RequirePresent
)

$ErrorActionPreference = 'Stop'
$documents = @(
    (Join-Path $Root 'README.md'),
    (Join-Path $Root 'MYTHICMOBS_PARITY_MATRIX.md'),
    (Join-Path $Root 'tools/paper-integration/README.md'),
    (Join-Path $Root 'tools/paper-integration/STARTUP_EVIDENCE.md')
)
$references = [System.Collections.Generic.Dictionary[string, System.Collections.Generic.List[string]]]::new([System.StringComparer]::OrdinalIgnoreCase)
$pattern = '(?<![A-Za-z0-9_-])(?<id>srv-[A-Za-z0-9](?:[A-Za-z0-9_-]|\.[A-Za-z0-9_-])*)(?![A-Za-z0-9_.-])'

foreach ($document in $documents) {
    if (-not (Test-Path -LiteralPath $document -PathType Leaf)) {
        throw "Evidence document not found: $document"
    }
    $lines = @(Get-Content -LiteralPath $document -Encoding utf8)
    for ($lineNumber = 0; $lineNumber -lt $lines.Count; $lineNumber++) {
        foreach ($match in [regex]::Matches($lines[$lineNumber], $pattern)) {
            $fixtureId = $match.Groups['id'].Value
            if (-not $references.ContainsKey($fixtureId)) {
                $references[$fixtureId] = [System.Collections.Generic.List[string]]::new()
            }
            $relativeDocument = [System.IO.Path]::GetRelativePath($Root, $document).Replace('\', '/')
            $references[$fixtureId].Add("${relativeDocument}:$($lineNumber + 1)")
        }
    }
}

$missing = [System.Collections.Generic.List[string]]::new()
$present = [System.Collections.Generic.List[string]]::new()
foreach ($fixtureId in ($references.Keys | Sort-Object)) {
    $path = Join-Path $Root $fixtureId
    if (Test-Path -LiteralPath $path -PathType Container) {
        $present.Add($fixtureId)
    } else {
        $missing.Add($fixtureId)
    }
}

Write-Output ("Evidence fixture references: {0}" -f $references.Count)
Write-Output ("Present: {0}" -f $present.Count)
Write-Output ("Missing: {0}" -f $missing.Count)
if ($missing.Count -gt 0) {
    Write-Output 'Missing fixture directories:'
    $missing | ForEach-Object {
        $locations = $references[$_] -join ', '
        Write-Output ("- {0} ({1})" -f $_, $locations)
    }
    if ($RequirePresent) {
        exit 1
    }
    Write-Output 'Historical or external fixture references are reported but do not fail the default audit.'
}
Write-Output 'Evidence fixture audit passed.'
