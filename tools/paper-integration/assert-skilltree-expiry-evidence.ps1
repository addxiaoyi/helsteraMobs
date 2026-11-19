param([Parameter(Mandatory = $true)][string]$FixturePath)
$ErrorActionPreference = 'Stop'
$evidence = Get-Content -LiteralPath (Join-Path $FixturePath 'skilltree-restart-evidence.json') -Raw | ConvertFrom-Json
if ($evidence.schemaVersion -ne 2 -or $evidence.timedVariables.result -cne 'passed' -or
        $evidence.timedVariables.checks -ne 115 -or $evidence.thirdBoot.result -cne 'passed') {
    throw 'Timed SkillTree evidence requires schema 2 and all three passing boots.'
}
foreach ($field in @('originalDeadlinesPreserved', 'expiredDuringDowntime', 'parentStateChecked', 'expiredValuesNotResaved')) {
    if ($evidence.timedVariables.$field -ne $true) { throw "Missing timed-variable assertion: $field" }
}
foreach ($field in @('survivingEntityRestored', 'expiredValuesAbsent', 'permanentControlsRetained', 'deadEntitySnapshotAbsent')) {
    if ($evidence.thirdBoot.$field -ne $true) { throw "Missing third-boot assertion: $field" }
}
$offline = [long]$evidence.timedVariables.firstBoot.offlineDeadline
$retained = [long]$evidence.timedVariables.firstBoot.retainedDeadline
if ($offline -le 1 -or $retained -le $offline -or
        [long]$evidence.timedVariables.firstShutdownAt -ge $offline -or
        [long]$evidence.timedVariables.secondShutdownAt -lt $offline -or
        [long]$evidence.timedVariables.secondShutdownAt -ge $retained) {
    throw 'Shutdown times do not prove expiration during downtime.'
}
$total = 0
$expectedNames = @('expiry.parent', 'expiry.parent-location', 'expiry.permanent', 'expiry.replaced',
    'expiry.unset', 'expiry.replaced-location', 'expiry.unset-location')
foreach ($window in @('offline', 'retained')) {
    foreach ($type in @('integer', 'float', 'string', 'boolean', 'list', 'set', 'map', 'location')) {
        $expectedNames += "expiry.$window.$type"
    }
}
foreach ($phase in @(
    @{stage='write'; boot='firstBoot'; entities=2},
    @{stage='verify'; boot='secondBoot'; entities=2},
    @{stage='final'; boot='thirdBoot'; entities=1}
)) {
    $stage = $phase.stage
    $boot = $evidence.timedVariables.($phase.boot)
    $text = [System.IO.File]::ReadAllText((Join-Path $FixturePath "skilltree-$stage.log"))
    $rows = [regex]::Matches($text, "(?m)^.*skilltree-expiry-check stage=$stage entity=([0-9a-f-]{36}) name=([^ ]+) expected-present=(true|false) actual-present=(true|false) expected-deadline=(\d+) actual-deadline=(\d+) result=passed\s*$")
    $summaries = [regex]::Matches($text, "skilltree-expiry stage=$stage entity=([0-9a-f-]{36}) checks=23 offline-deadline=(\d+) retained-deadline=(\d+) observed-at=(\d+) result=passed")
    if ($boot.checks -ne 23 * $phase.entities -or $rows.Count -ne $boot.checks -or
            $summaries.Count -ne $phase.entities -or $text -match 'skilltree-[^\r\n]*result=failed' -or
            $boot.offlineDeadline -ne $offline -or $boot.retainedDeadline -ne $retained -or
            @($boot.entities | Select-Object -Unique).Count -ne $phase.entities -or
            @($boot.observedAt).Count -ne $phase.entities) { throw "Incomplete $stage observations" }
    foreach ($row in $rows) {
        $name = $row.Groups[2].Value
        $expectedDeadline = 0L
        $expectedPresent = $true
        if ($name.StartsWith('expiry.offline.')) {
            $expectedPresent = $stage -eq 'write'
            if ($expectedPresent) { $expectedDeadline = $offline }
        } elseif ($name.StartsWith('expiry.retained.') -or $name -in @('expiry.parent', 'expiry.parent-location')) {
            $expectedPresent = $stage -ne 'final'
            if ($expectedPresent) { $expectedDeadline = $retained }
        } elseif ($name.StartsWith('expiry.unset')) { $expectedPresent = $false }
        if ($row.Groups[3].Value -cne $row.Groups[4].Value -or $row.Groups[5].Value -cne $row.Groups[6].Value -or
                $row.Groups[1].Value -notin $boot.entities -or $name -cnotin $expectedNames -or
                $row.Groups[3].Value -cne $expectedPresent.ToString().ToLowerInvariant() -or
                [long]$row.Groups[5].Value -ne $expectedDeadline) { throw "Mismatched $stage observation" }
    }
    if (@($rows | ForEach-Object { $_.Groups[1].Value + ':' + $_.Groups[2].Value } | Select-Object -Unique).Count -ne $rows.Count) {
        throw "Duplicate $stage observation"
    }
    if (@($summaries | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique).Count -ne $phase.entities) {
        throw "Duplicate $stage entity summary"
    }
    foreach ($summary in $summaries) {
        $observed = [long]$summary.Groups[4].Value
        if ($summary.Groups[1].Value -notin $boot.entities -or [long]$summary.Groups[2].Value -ne $offline -or
                [long]$summary.Groups[3].Value -ne $retained -or $observed -notin $boot.observedAt -or
                ($stage -eq 'write' -and $observed -ge [long]$evidence.timedVariables.firstShutdownAt) -or
                ($stage -eq 'verify' -and ($observed -lt $offline -or $observed -ge [long]$evidence.timedVariables.secondShutdownAt)) -or
                ($stage -eq 'final' -and $observed -lt $retained)) { throw "Invalid $stage timing/identity" }
    }
    $total += $rows.Count
}
if (@(Compare-Object $evidence.timedVariables.firstBoot.entities $evidence.timedVariables.secondBoot.entities).Count -ne 0 -or
        $evidence.timedVariables.thirdBoot.entities[0] -notin $evidence.timedVariables.firstBoot.entities) {
    throw 'Entity identities changed between boots.'
}
if (@($evidence.snapshots).Count -ne 4 -or @($evidence.snapshots.path | Select-Object -Unique).Count -ne 4) {
    throw 'Expected four distinct shutdown snapshots.'
}
$expectedSnapshots = @($evidence.timedVariables.firstBoot.entities | ForEach-Object { "skilltree-write-snapshots/entity-$_.yml" })
$survivor = $evidence.timedVariables.thirdBoot.entities[0]
$expectedSnapshots += @("skilltree-verify-snapshots/entity-$survivor.yml", "skilltree-final-snapshots/entity-$survivor.yml")
if (@(Compare-Object $expectedSnapshots @($evidence.snapshots.path)).Count -ne 0) { throw 'Snapshot entity identities disagree.' }
foreach ($snapshot in $evidence.snapshots) {
    if ($snapshot.path -cnotmatch '^skilltree-(write|verify|final)-snapshots/entity-[0-9a-f-]{36}\.yml$') {
        throw 'Invalid shutdown snapshot path.'
    }
    $path = Join-Path $FixturePath $snapshot.path
    if ((Get-Item -LiteralPath $path).LinkType -or
            (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $snapshot.sha256) {
        throw 'Shutdown snapshot hash mismatch.'
    }
    $stage = [regex]::Match($snapshot.path, '^skilltree-(write|verify|final)-').Groups[1].Value
    $expectedCount = @{write=18; verify=10; final=0}[$stage]
    $text = [System.IO.File]::ReadAllText($path)
    $deadlines = [regex]::Matches($text, '(?m)^\s+expires-at: (\d+)\s*$')
    if ($deadlines.Count -ne $expectedCount -or
            ($stage -ne 'write' -and $text -match 'name: expiry\.offline\.') -or
            ($stage -eq 'final' -and $text -match 'name: expiry\.(retained\.|parent)')) {
        throw 'Shutdown snapshot retained expired values or lost deadlines.'
    }
    foreach ($deadline in $deadlines) {
        if ([long]$deadline.Groups[1].Value -notin @($offline, $retained) -or
                ($stage -eq 'verify' -and [long]$deadline.Groups[1].Value -ne $retained)) {
            throw 'Shutdown snapshot reset a deadline.'
        }
    }
}
Write-Output "Timed SkillTree restart evidence passed: $total observations across three real boots."
