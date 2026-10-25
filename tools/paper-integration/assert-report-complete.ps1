param(
    [Parameter(Mandatory = $true)]
    [string]$ReportPath,
    [switch]$RequireTargetPaperVersion,
    [string]$ExpectedPaperVersion = '1.21.1'
)

$ErrorActionPreference = 'Stop'
$report = Get-Content -LiteralPath $ReportPath -Raw -Encoding utf8 | ConvertFrom-Json

if ($RequireTargetPaperVersion) {
    if ($report.schemaVersion -ne 1) {
        throw "Paper integration report schema mismatch: $($report.schemaVersion)"
    }
    if (-not $report.pluginEnabled) {
        throw 'Paper integration report does not prove that helsteraMobs enabled successfully'
    }
    if ([string]$report.evidenceMode -ne 'release') {
        throw "Paper integration report is not release evidence: $($report.evidenceMode)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$report.paperVersion) -or
            $report.paperVersion -notmatch ('^' + [regex]::Escape($ExpectedPaperVersion) + '(?:-|$)')) {
        throw "Paper implementation mismatch: $($report.paperVersion) (expected $ExpectedPaperVersion)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$report.paperApiVersion) -or
            $report.paperApiVersion -notmatch ('^' + [regex]::Escape($ExpectedPaperVersion) + '(?:-|$)')) {
        throw "Paper API mismatch: $($report.paperApiVersion) (expected $ExpectedPaperVersion)"
    }
    $generatedAt = [DateTimeOffset]::MinValue
    $parsedTimestamp = [DateTimeOffset]::TryParse(
        [string]$report.generatedAtUtc,
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::RoundtripKind,
        [ref]$generatedAt)
    if (-not $parsedTimestamp) {
        throw "Paper integration report has an invalid generatedAtUtc: $($report.generatedAtUtc)"
    }
    if (@($report.probes).Count -eq 0) {
        throw 'Paper integration report contains no probe evidence'
    }
    $expectedProbeIds = @($report.expectedProbeIds)
    if ($expectedProbeIds.Count -eq 0) {
        throw 'Paper integration report contains no expected probe catalog'
    }
    $observedProbeIds = @($report.probes | ForEach-Object { [string]$_.id })
    $duplicateProbeIds = @($observedProbeIds | Group-Object | Where-Object { $_.Count -gt 1 })
    if ($duplicateProbeIds.Count -gt 0) {
        throw "Paper integration report contains duplicate probes: $(($duplicateProbeIds | ForEach-Object Name) -join ', ')"
    }
    $missingProbeIds = @($expectedProbeIds | Where-Object { $_ -notin $observedProbeIds })
    if ($missingProbeIds.Count -gt 0) {
        throw "Paper integration report is missing registered probes: $($missingProbeIds -join ', ')"
    }
    $unexpectedProbeIds = @($observedProbeIds | Where-Object { $_ -notin $expectedProbeIds })
    if ($unexpectedProbeIds.Count -gt 0) {
        throw "Paper integration report contains unregistered probes: $($unexpectedProbeIds -join ', ')"
    }
}

$incomplete = @($report.probes | Where-Object {
    $_.status -in @('not-observed', 'insufficient-sample', 'failed')
})

if ($incomplete.Count -gt 0) {
    $details = $incomplete | ForEach-Object { "$($_.id)=$($_.status)" }
    throw "Paper integration evidence incomplete ($($incomplete.Count) probes): $($details -join ', ')"
}

Write-Host "Paper integration evidence complete: $($report.probes.Count) probes observed."
