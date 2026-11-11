param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
    [switch]$RequirePresent,
    [switch]$ReleaseGate,
    [string]$ReleasePluginJar = ''
)

$ErrorActionPreference = 'Stop'
if ($ReleaseGate) {
    if ([string]::IsNullOrWhiteSpace($ReleasePluginJar)) {
        throw 'Release gate requires -ReleasePluginJar to identify the exact artifact.'
    }
    $RequirePresent = $true
}
$documents = @(
    (Join-Path $Root 'README.md'),
    (Join-Path $Root 'MYTHICMOBS_PARITY_MATRIX.md'),
    (Join-Path $Root 'tools/paper-integration/README.md'),
    (Join-Path $Root 'tools/paper-integration/STARTUP_EVIDENCE.md')
)
$references = [System.Collections.Generic.Dictionary[string, System.Collections.Generic.List[string]]]::new([System.StringComparer]::OrdinalIgnoreCase)
$pattern = '(?<![A-Za-z0-9_-])(?<id>srv-paper-[A-Za-z0-9](?:[A-Za-z0-9_-]|\.[A-Za-z0-9_-])*)(?![A-Za-z0-9_.-])'

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
$directoryOnly = [System.Collections.Generic.List[string]]::new()
$reportPresent = [System.Collections.Generic.List[string]]::new()
foreach ($fixtureId in ($references.Keys | Sort-Object)) {
    $path = Join-Path $Root $fixtureId
    if (-not (Test-Path -LiteralPath $path -PathType Container)) {
        $missing.Add($fixtureId)
    } elseif (Test-Path -LiteralPath (Join-Path $path 'integration-report.json') -PathType Leaf) {
        $reportPresent.Add($fixtureId)
    } else {
        $directoryOnly.Add($fixtureId)
    }
}

$readme = Get-Content -LiteralPath (Join-Path $Root 'README.md') -Raw -Encoding utf8
$inventoryPattern = 'Current fixture inventory: (?<total>\d+) cited IDs; (?<reports>\d+) report files; (?<directoryOnly>\d+) directories\s+without reports; (?<missing>\d+) missing directories; (?<archived>\d+) archived release reports\.'
$inventoryMatch = [regex]::Match($readme, $inventoryPattern)
if (-not $inventoryMatch.Success) {
    throw 'README.md must contain a Current fixture inventory line generated from this audit.'
}
if ([int]$inventoryMatch.Groups['total'].Value -ne $references.Count -or
        [int]$inventoryMatch.Groups['reports'].Value -ne $reportPresent.Count -or
        [int]$inventoryMatch.Groups['directoryOnly'].Value -ne $directoryOnly.Count -or
        [int]$inventoryMatch.Groups['missing'].Value -ne $missing.Count) {
    throw ('README fixture inventory is stale: documented {0}/{1}/{2}/{3}, actual {4}/{5}/{6}/{7}.' -f
        $inventoryMatch.Groups['total'].Value,
        $inventoryMatch.Groups['reports'].Value,
        $inventoryMatch.Groups['directoryOnly'].Value,
        $inventoryMatch.Groups['missing'].Value,
        $references.Count, $reportPresent.Count, $directoryOnly.Count, $missing.Count)
}

$matrixText = Get-Content -LiteralPath (Join-Path $Root 'MYTHICMOBS_PARITY_MATRIX.md') -Raw -Encoding utf8
$mappedRowPattern = '(?m)^\|[^|\r\n]+\|\s*mapped-unverified\s*\|'
$mappedRowCount = [regex]::Matches($matrixText, $mappedRowPattern).Count
$mappedClaimPattern = 'matrix currently has\s+`(?<count>\d+)`\s+`mapped-unverified`\s+rows'
$mappedClaim = [regex]::Match($readme, $mappedClaimPattern)
if (-not $mappedClaim.Success) {
    throw 'README.md must state the mapped-unverified row count from the parity matrix.'
}
if ([int]$mappedClaim.Groups['count'].Value -ne $mappedRowCount) {
    throw ('README mapped-unverified row count is stale: documented {0}, actual {1}.' -f
        $mappedClaim.Groups['count'].Value, $mappedRowCount)
}

$manifestPath = Join-Path $Root 'tools/paper-integration/release-evidence.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw 'Release evidence manifest not found: tools/paper-integration/release-evidence.json'
}
$versionMatrixPath = Join-Path $Root 'tools/paper-integration/version-matrix.json'
if (-not (Test-Path -LiteralPath $versionMatrixPath -PathType Leaf)) {
    throw 'Paper version matrix not found: tools/paper-integration/version-matrix.json'
}
$versionMatrix = Get-Content -LiteralPath $versionMatrixPath -Raw -Encoding utf8 | ConvertFrom-Json
if ($versionMatrix.schemaVersion -ne 1 -or
        $versionMatrix.releasePolicy.requiresExactServerBuild -ne $true -or
        $versionMatrix.releasePolicy.requiresMatchingPluginSha256 -ne $true) {
    throw 'Paper version matrix must require exact server builds and matching plugin SHA-256 for release evidence.'
}
$releaseCombinations = @($versionMatrix.combinations | Where-Object { $_.releaseValidated -eq $true })
if ($releaseCombinations.Count -ne 1) {
    throw "Evidence audit requires exactly one release-validated Paper combination, found $($releaseCombinations.Count)."
}
$releaseCombination = $releaseCombinations[0]
$expectedPaperVersion = [string]$releaseCombination.minecraftVersion
$expectedPaperBuild = [string]$releaseCombination.serverVersion
if ([string]$releaseCombination.serverFamily -cne 'Paper' -or
        [string]::IsNullOrWhiteSpace($expectedPaperVersion) -or
        [string]::IsNullOrWhiteSpace($expectedPaperBuild) -or
        [string]$releaseCombination.evidenceLevel -cne 'strict' -or
        [string]::IsNullOrWhiteSpace([string]$releaseCombination.releaseFixtureId) -or
        [string]::IsNullOrWhiteSpace([string]$releaseCombination.releaseReport)) {
    throw 'Paper version matrix release entry must identify a strict Paper build, Minecraft version, fixture, and report.'
}
$releaseEvidence = @(Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json)
if ($releaseEvidence.Count -eq 0) {
    throw 'Release evidence manifest must contain at least one report.'
}
if (@($releaseEvidence | Where-Object { $_.fullReleaseCatalog -is [bool] -and $_.fullReleaseCatalog }).Count -eq 0) {
    throw 'Release evidence manifest must require at least one full release catalog report.'
}
$archiveRoot = [System.IO.Path]::GetFullPath((Join-Path $Root 'tools/paper-integration/reports'))
$archivePrefix = $archiveRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
$missingReleaseReports = [System.Collections.Generic.List[string]]::new()
$latestFullReportChecked = $false
$releasePluginHash = ''
if ($ReleasePluginJar) {
    if (-not (Test-Path -LiteralPath $ReleasePluginJar -PathType Leaf)) {
        throw "Release plugin JAR not found: $ReleasePluginJar"
    }
    $releasePluginHash = (Get-FileHash -LiteralPath $ReleasePluginJar -Algorithm SHA256).Hash.ToLowerInvariant()
}
# The manifest lists the newest full release report first.
foreach ($item in $releaseEvidence) {
    $fixtureId = [string]$item.fixtureId
    $reportRelativePath = [string]$item.report
    if ($null -ne $item.fullReleaseCatalog -and $item.fullReleaseCatalog -isnot [bool]) {
        throw "Release evidence fullReleaseCatalog must be a boolean: $fixtureId"
    }
    if ($null -ne $item.pluginSha256 -and
            ($item.pluginSha256 -isnot [string] -or $item.pluginSha256 -cnotmatch '^[0-9a-f]{64}$')) {
        throw "Release evidence pluginSha256 must be a lowercase SHA-256 digest: $fixtureId"
    }
    if ($null -ne $item.reportFixtureDirectory -and
            ($item.reportFixtureDirectory -isnot [string] -or
                $item.reportFixtureDirectory -cnotmatch '^srv-paper-[A-Za-z0-9][A-Za-z0-9_.-]*$')) {
        throw "Release evidence reportFixtureDirectory is invalid: $fixtureId"
    }
    if ($null -ne $item.reportSha256 -and
            ($item.reportSha256 -isnot [string] -or $item.reportSha256 -cnotmatch '^[0-9a-f]{64}$')) {
        throw "Release evidence reportSha256 must be a lowercase SHA-256 digest: $fixtureId"
    }
    if ($fixtureId -notmatch '^srv-paper-[A-Za-z0-9][A-Za-z0-9_.-]*$' -or
            [string]::IsNullOrWhiteSpace($reportRelativePath)) {
        throw 'Release evidence manifest contains an invalid fixtureId or report path.'
    }
    $reportPath = [System.IO.Path]::GetFullPath((Join-Path $Root $reportRelativePath))
    if (-not $reportPath.StartsWith($archivePrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Release report must be archived under tools/paper-integration/reports: $reportRelativePath"
    }
    foreach ($logKind in @('log', 'clientLog')) {
        $hashKey = $logKind + 'Sha256'
        $logRelativePath = $item.$logKind
        $logHash = $item.$hashKey
        if ($null -eq $logRelativePath -and $null -eq $logHash) { continue }
        if ($logRelativePath -isnot [string] -or [string]::IsNullOrWhiteSpace($logRelativePath) -or
                $logHash -isnot [string] -or $logHash -cnotmatch '^[0-9a-f]{64}$') {
            throw "Release $logKind path and lowercase SHA-256 are both required: $fixtureId"
        }
        $logPath = [System.IO.Path]::GetFullPath((Join-Path $Root $logRelativePath))
        if (-not $logPath.StartsWith($archivePrefix, [StringComparison]::OrdinalIgnoreCase) -or
                -not (Test-Path -LiteralPath $logPath -PathType Leaf)) {
            throw "Release $logKind missing or outside archive: $fixtureId"
        }
        if ((Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $logHash) {
            throw "Release $logKind SHA-256 mismatch: $fixtureId"
        }
    }
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
        if ($item.fullReleaseCatalog -eq $true -and -not $latestFullReportChecked) {
            throw "Latest full release report is missing: $fixtureId"
        }
        $missingReleaseReports.Add("$fixtureId ($reportRelativePath)")
        continue
    }
    if ($item.reportSha256 -and
            (Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $item.reportSha256) {
        throw "Release report SHA-256 mismatch: $fixtureId"
    }
    if ($RequirePresent -and $item.fullReleaseCatalog -eq $true -and -not $latestFullReportChecked) {
        foreach ($requiredLog in @('log', 'clientLog')) {
            if ($null -eq $item.$requiredLog -or $null -eq $item.($requiredLog + 'Sha256')) {
                throw "Latest full release report requires $requiredLog and its SHA-256: $fixtureId"
            }
        }
    }
    $report = Get-Content -LiteralPath $reportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $reportFixtureDirectory = $fixtureId
    if ($item.reportFixtureDirectory) {
        $reportFixtureDirectory = [string]$item.reportFixtureDirectory
    }
    if ($report.schemaVersion -ne 1 -or [string]$report.fixtureDirectory -ne $reportFixtureDirectory -or
            [string]$report.evidenceMode -ne 'release' -or -not $report.pluginEnabled -or
            [string]$report.paperVersion -notmatch ('^' + [regex]::Escape($expectedPaperVersion) + '(?:-|$)') -or
            [string]$report.paperApiVersion -notmatch ('^' + [regex]::Escape($expectedPaperVersion) + '(?:-|$)')) {
        throw "Release report metadata does not match its manifest entry: $fixtureId"
    }
    $expectedIds = @($report.expectedProbeIds | ForEach-Object { [string]$_ })
    $probes = @($report.probes)
    $requireCurrentCatalog = $item.fullReleaseCatalog -eq $true -and -not $latestFullReportChecked
    if ($requireCurrentCatalog) {
        if ([string]$fixtureId -cne [string]$releaseCombination.releaseFixtureId -or
                [string]$reportRelativePath -cne [string]$releaseCombination.releaseReport) {
            throw 'Latest full release report does not match the release fixture and report declared by version-matrix.json.'
        }
        if ([string]$item.pluginSha256 -cnotmatch '^[0-9a-f]{64}$' -or
                [string]$report.pluginSha256 -cne [string]$item.pluginSha256) {
            throw "Latest full release report plugin SHA-256 is missing or mismatched: $fixtureId"
        }
        $summary = [regex]::Match($readme, 'Latest strict report has (?<count>\d+) probes, zero incomplete')
        if (-not $summary.Success -or [int]$summary.Groups['count'].Value -ne $probes.Count) {
            throw "README latest strict report probe count does not match release evidence: $fixtureId"
        }
        $latestReleaseDocuments = @(
            @{
                name = 'README.md'
                text = $readme
                pattern = '(?m)^Latest strict fixture `(?<fixture>[^`]+)`[^\r\n]*?(?<count>\d+) probes'
            },
            @{
                name = 'MYTHICMOBS_PARITY_MATRIX.md'
                text = $matrixText
                pattern = '(?m)^Latest strict release evidence: `(?<fixture>[^`]+)`\s*completes (?<count>\d+) probes'
            },
            @{
                name = 'tools/paper-integration/README.md'
                text = (Get-Content -LiteralPath (Join-Path $Root 'tools/paper-integration/README.md') -Raw -Encoding utf8)
                pattern = '(?m)^Latest strict fixture `(?<fixture>[^`]+)` passes all (?<count>\d+) probes'
            },
            @{
                name = 'tools/paper-integration/STARTUP_EVIDENCE.md'
                text = (Get-Content -LiteralPath (Join-Path $Root 'tools/paper-integration/STARTUP_EVIDENCE.md') -Raw -Encoding utf8)
                pattern = '(?m)^Latest fixture `(?<fixture>[^`]+)` passed the full strict gate[^\r\n]*?(?<count>\d+) registered probes'
            }
        )
        foreach ($document in $latestReleaseDocuments) {
            $documentSummary = [regex]::Match([string]$document.text, [string]$document.pattern)
            if (-not $documentSummary.Success -or
                    [string]$documentSummary.Groups['fixture'].Value -cne [string]$releaseCombination.releaseFixtureId -or
                    [int]$documentSummary.Groups['count'].Value -ne $probes.Count) {
                throw "Latest strict release documentation is stale in $($document.name): expected $($releaseCombination.releaseFixtureId) with $($probes.Count) probes."
            }
        }
        if ($releasePluginHash -and [string]$report.pluginSha256 -cne $releasePluginHash) {
            throw "Latest full release report plugin SHA-256 does not match release JAR: $fixtureId"
        }
        $latestFullReportChecked = $true
    }
    $observedIds = @($probes | ForEach-Object { [string]$_.id })
    if ($expectedIds.Count -eq 0 -or $probes.Count -ne $expectedIds.Count -or
            @($observedIds | Sort-Object -Unique).Count -ne $observedIds.Count -or
            @($expectedIds | Where-Object { $_ -notin $observedIds }).Count -gt 0 -or
            @($observedIds | Where-Object { $_ -notin $expectedIds }).Count -gt 0 -or
            @($probes | Where-Object { $_.status -in @('not-observed', 'insufficient-sample', 'failed') }).Count -gt 0) {
        throw "Release report has missing, duplicate, unexpected, or incomplete probes: $fixtureId"
    }
    $expectedBuildForReport = if ($requireCurrentCatalog) { $expectedPaperBuild } else { '' }
    & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $reportPath `
        -RequireTargetPaperVersion -RequireFullReleaseCatalog:$requireCurrentCatalog `
        -ExpectedPaperVersion $expectedPaperVersion -ExpectedPaperBuild $expectedBuildForReport `
        -ExpectedPluginSha256 ([string]$item.pluginSha256)
}
if ($releasePluginHash -and -not $latestFullReportChecked) {
    throw 'Cannot verify release JAR: latest full release report is missing.'
}

Write-Output ("Evidence fixture references: {0}" -f $references.Count)
Write-Output ("Fixture report files: {0}" -f $reportPresent.Count)
Write-Output ("Directories without report files: {0}" -f $directoryOnly.Count)
Write-Output ("Missing fixture directories: {0}" -f $missing.Count)
Write-Output ("Mapped-unverified matrix rows: {0}" -f $mappedRowCount)
Write-Output ("Archived release reports: {0}" -f $releaseEvidence.Count)
if ([int]$inventoryMatch.Groups['archived'].Value -ne $releaseEvidence.Count) {
    throw ('README archived release report count is stale: documented {0}, actual {1}.' -f
        $inventoryMatch.Groups['archived'].Value, $releaseEvidence.Count)
}
if ($directoryOnly.Count -gt 0) {
    Write-Output 'Fixture directories without integration-report.json:'
    $directoryOnly | ForEach-Object {
        $locations = $references[$_] -join ', '
        Write-Output ("- {0} ({1})" -f $_, $locations)
    }
}
if ($missing.Count -gt 0) {
    Write-Output 'Missing fixture directories:'
    $missing | ForEach-Object {
        $locations = $references[$_] -join ', '
        Write-Output ("- {0} ({1})" -f $_, $locations)
    }
}
if ($directoryOnly.Count -gt 0 -or $missing.Count -gt 0) {
    Write-Output 'Historical fixture references without local reports remain informational and are not release evidence.'
}
if ($missingReleaseReports.Count -gt 0) {
    Write-Output 'Missing required release reports:'
    $missingReleaseReports | ForEach-Object { Write-Output "- $_" }
    if ($RequirePresent) { exit 1 }
} elseif ($RequirePresent) {
    Write-Output 'Required release evidence audit passed.'
    if ($ReleaseGate) { Write-Output 'Release gate passed for the specified plugin JAR.' }
} else {
    Write-Output 'Release evidence manifest audit passed.'
}
