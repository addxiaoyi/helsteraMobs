param([Parameter(Mandatory = $true)][string]$FixturePath)
$ErrorActionPreference = 'Stop'
$validator = Join-Path $PSScriptRoot 'assert-skilltree-expiry-evidence.ps1'
$temp = Join-Path ([System.IO.Path]::GetTempPath()) ('helstera-expiry-contract-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $source = Join-Path $FixturePath 'skilltree-restart-evidence.json'
    $original = [System.IO.File]::ReadAllText($source)
    $evidence = $original | ConvertFrom-Json
    foreach ($name in @('skilltree-write.log', 'skilltree-verify.log', 'skilltree-final.log') + @($evidence.snapshots.path)) {
        $destination = Join-Path $temp $name
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $FixturePath $name) -Destination $destination
    }
    $report = Join-Path $temp 'skilltree-restart-evidence.json'
    [System.IO.File]::WriteAllText($report, $original)
    & $validator -FixturePath $temp
    $cases = @(
        @{ name='old-schema'; change={param($e) $e.schemaVersion = 1} },
        @{ name='missing-final-boot'; change={param($e) $e.thirdBoot.result = 'incomplete'} },
        @{ name='reset-deadline'; change={param($e) $e.timedVariables.secondBoot.retainedDeadline += 5000} },
        @{ name='not-offline-expiry'; change={param($e) $e.timedVariables.firstShutdownAt = $e.timedVariables.firstBoot.offlineDeadline} },
        @{ name='missing-parent-check'; change={param($e) $e.timedVariables.parentStateChecked = $false} },
        @{ name='missing-snapshot'; change={param($e) $e.snapshots = @($e.snapshots)[0..2]} },
        @{ name='changed-snapshot'; change={param($e) $e.snapshots[0].sha256 = ('0' * 64)} },
        @{ name='wrong-survivor'; change={param($e) $e.timedVariables.thirdBoot.entities = @('00000000-0000-0000-0000-000000000000')} }
    )
    foreach ($case in $cases) {
        $changed = $original | ConvertFrom-Json
        & $case.change $changed
        $changed | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $report -Encoding utf8
        $rejected = $false
        try { & $validator -FixturePath $temp | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw "Evidence validator accepted $($case.name)" }
    }
    [System.IO.File]::WriteAllText($report, $original)
    $logPath = Join-Path $temp 'skilltree-verify.log'
    $log = [System.IO.File]::ReadAllText($logPath)
    $checkPattern = '(?m)^.*skilltree-expiry-check stage=verify [^\r\n]+$'
    $firstCheck = [regex]::Match($log, $checkPattern).Value
    $mutations = @(
        ([regex]::new($checkPattern)).Replace($log, '', 1),
        ($log + "`n" + $firstCheck + "`n"),
        ($log -replace 'expected-present=false actual-present=false', 'expected-present=true actual-present=true'),
        ($log -replace 'name=expiry.parent-location', 'name=expiry.unknown-location')
    )
    foreach ($changed in $mutations) {
        [System.IO.File]::WriteAllText($logPath, $changed)
        $rejected = $false
        try { & $validator -FixturePath $temp | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'Evidence validator accepted missing, duplicate or inconsistent observations' }
    }
    Write-Output 'SkillTree expiry evidence contract passed: valid fixture and 12 rejection cases.'
} finally {
    Remove-Item -LiteralPath $temp -Recurse -Force
}
