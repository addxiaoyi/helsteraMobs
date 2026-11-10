param(
    [Parameter(Mandatory = $true)]
    [string]$ReportPath,
    [switch]$RequireTargetPaperVersion,
    [switch]$RequireFullReleaseCatalog,
    [string]$ExpectedPluginSha256 = '',
    [string]$ExpectedPaperVersion = '1.21.1',
    [string]$ExpectedPaperBuild = ''
)

$ErrorActionPreference = 'Stop'
$report = Get-Content -LiteralPath $ReportPath -Raw -Encoding utf8 | ConvertFrom-Json

if ($ExpectedPluginSha256) {
    if ($ExpectedPluginSha256 -cnotmatch '^[0-9a-f]{64}$' -or
            [string]$report.pluginSha256 -cne $ExpectedPluginSha256) {
        throw 'Paper integration report plugin SHA-256 is missing, invalid, or mismatched'
    }
}

if ($RequireTargetPaperVersion -or $RequireFullReleaseCatalog) {
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
    if ($ExpectedPaperBuild -and
            ([string]::IsNullOrWhiteSpace([string]$report.paperVersion) -or
                $report.paperVersion -notmatch ('^' + [regex]::Escape($ExpectedPaperBuild) + '(?:-|$)'))) {
        throw "Paper implementation build mismatch: $($report.paperVersion) (expected $ExpectedPaperBuild)"
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

if ($RequireFullReleaseCatalog) {
    $catalogPath = Join-Path $PSScriptRoot 'release-probe-catalog.json'
    $catalog = Get-Content -LiteralPath $catalogPath -Raw -Encoding utf8 | ConvertFrom-Json
    if ($catalog.catalogVersion -ne 1 -or $catalog.paperVersion -ne $ExpectedPaperVersion -or
            @($catalog.probes).Count -eq 0) {
        throw 'Invalid or incompatible release probe catalog'
    }
    $catalogIds = @($catalog.probes | ForEach-Object { [string]$_.id })
    if (@($catalogIds | Sort-Object -Unique).Count -ne $catalogIds.Count) {
        throw 'Release probe catalog contains duplicate IDs'
    }
    foreach ($required in $catalog.probes) {
        $requiredId = [string]$required.id
        if ([string]::IsNullOrWhiteSpace($requiredId) -or
                $required.status -notin @('passed', 'observed-rejected')) {
            throw "Invalid release probe catalog entry: $requiredId"
        }
        $probeMatches = @($report.probes | Where-Object { $_.id -ceq $requiredId })
        if ($requiredId -cnotin @($report.expectedProbeIds) -or $probeMatches.Count -ne 1 -or
                $probeMatches[0].status -cne $required.status) {
            throw "Paper integration report lacks required release probe: $requiredId (expected $($required.status))"
        }
        if ($null -ne $required.requiredObservation) {
            $observation = [string]$required.requiredObservation
            if ([string]::IsNullOrWhiteSpace($observation) -or
                    $observation -cnotin @($probeMatches[0].observations)) {
                throw "Paper integration report lacks required observation for ${requiredId}: $observation"
            }
        }
        if ($null -ne $required.requiredObservationContains) {
            $fragment = [string]$required.requiredObservationContains
            if ([string]::IsNullOrWhiteSpace($fragment) -or
                    @($probeMatches[0].observations | Where-Object { ([string]$_).Contains($fragment) }).Count -eq 0) {
                throw "Paper integration report lacks required observation for ${requiredId}: $fragment"
            }
        }
        if ($null -ne $required.requiredObservationPattern) {
            $pattern = [string]$required.requiredObservationPattern
            if ([string]::IsNullOrWhiteSpace($pattern) -or
                    @($probeMatches[0].observations | Where-Object { ([string]$_) -cmatch $pattern }).Count -eq 0) {
                throw "Paper integration report lacks required observation for ${requiredId}: $pattern"
            }
        }
        if ($null -ne $required.elapsedTicksField -or $null -ne $required.maximumTicksField) {
            $elapsedField = [string]$required.elapsedTicksField
            $maximumField = [string]$required.maximumTicksField
            if ([string]::IsNullOrWhiteSpace($elapsedField) -or
                    [string]::IsNullOrWhiteSpace($maximumField)) {
                throw "Release probe catalog has an incomplete tick bound: $requiredId"
            }
            $tickPattern = '(?:^|\s)' + [regex]::Escape($elapsedField) + '=(?<elapsed>[0-9]+)(?=\s|$).*?(?:^|\s)' +
                [regex]::Escape($maximumField) + '=(?<maximum>[0-9]+)(?:\s|$)'
            $tickMatch = $null
            foreach ($observation in $probeMatches[0].observations) {
                $candidate = [regex]::Match([string]$observation, $tickPattern)
                if ($candidate.Success) { $tickMatch = $candidate; break }
            }
            [long]$elapsedTicks = 0
            [long]$maximumTicks = 0
            if ($null -eq $tickMatch -or
                    -not [long]::TryParse($tickMatch.Groups['elapsed'].Value, [ref]$elapsedTicks) -or
                    -not [long]::TryParse($tickMatch.Groups['maximum'].Value, [ref]$maximumTicks) -or
                    $maximumTicks -le 0 -or $elapsedTicks -gt $maximumTicks) {
                throw "Paper integration probe exceeds or lacks its required tick bound: $requiredId"
            }
        }
        if ($required.status -ceq 'observed-rejected') {
            $reason = [string]$required.rejectionReason
            if ([string]::IsNullOrWhiteSpace($reason)) {
                throw "Release probe catalog lacks rejection reason: $requiredId"
            }
            $reasonPattern = '(?:^|\s)requested=false\s+failure=' + [regex]::Escape($reason) + '\s*$'
            if (@($probeMatches[0].observations | Where-Object { $_ -cmatch $reasonPattern }).Count -eq 0) {
                throw "Paper integration report lacks expected rejection reason for ${requiredId}: $reason"
            }
        }
    }
}

$invalidStatus = @($report.probes | Where-Object {
    $_.status -cnotin @('passed', 'observed-rejected', 'observed', 'failed', 'insufficient-sample', 'not-observed')
})
if ($invalidStatus.Count -gt 0) {
    throw "Paper integration report contains invalid probe status: $(($invalidStatus | ForEach-Object { $_.id }) -join ', ')"
}

$incomplete = @($report.probes | Where-Object {
    $_.status -in @('not-observed', 'insufficient-sample', 'failed')
})

if ($incomplete.Count -gt 0) {
    $details = $incomplete | ForEach-Object { "$($_.id)=$($_.status)" }
    throw "Paper integration evidence incomplete ($($incomplete.Count) probes): $($details -join ', ')"
}

Write-Host "Paper integration evidence complete: $($report.probes.Count) probes observed."
