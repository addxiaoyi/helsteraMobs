$ErrorActionPreference = 'Stop'

$resolver = Join-Path $PSScriptRoot 'resolve-python.ps1'
if (-not (Test-Path -LiteralPath $resolver -PathType Leaf)) {
    throw "Python resolver is missing: $resolver"
}

$resolved = & $resolver
if ($LASTEXITCODE -ne 0 -or $null -eq $resolved) {
    throw 'Python resolver did not return a usable candidate.'
}

$source = [string]$resolved.Source
$prefix = @($resolved.Prefix)
if ([string]::IsNullOrWhiteSpace($source)) {
    throw 'Python resolver returned an empty executable path.'
}

$null = & $source @($prefix) -c 'import encodings, jsonschema'
if ($LASTEXITCODE -ne 0) {
    throw "Resolved Python cannot import encodings/jsonschema: $source"
}

Write-Host "Paper Python resolver passed: $source"
