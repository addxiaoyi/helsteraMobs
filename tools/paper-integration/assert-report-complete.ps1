param(
    [Parameter(Mandatory = $true)]
    [string]$ReportPath
)

$ErrorActionPreference = 'Stop'
$report = Get-Content -LiteralPath $ReportPath -Raw -Encoding utf8 | ConvertFrom-Json
$incomplete = @($report.probes | Where-Object {
    $_.status -in @('not-observed', 'insufficient-sample', 'failed')
})

if ($incomplete.Count -gt 0) {
    $details = $incomplete | ForEach-Object { "$($_.id)=$($_.status)" }
    throw "Paper integration evidence incomplete ($($incomplete.Count) probes): $($details -join ', ')"
}

Write-Host "Paper integration evidence complete: $($report.probes.Count) probes observed."
