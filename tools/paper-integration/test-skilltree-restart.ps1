param(
    [string]$FixtureName = '',
    [switch]$Exploratory,
    [string]$PaperJarPath = ''
)

$ErrorActionPreference = 'Stop'
$fixture = if ([string]::IsNullOrWhiteSpace($FixtureName)) {
    'srv-paper-skilltree-restart-' + [guid]::NewGuid().ToString('N')
} else {
    $FixtureName
}
$previousFixture = $env:PAPER_INTEGRATION_DIR
$previousReuse = $env:PAPER_SKILLTREE_REUSE
$previousStage = $env:PAPER_SKILLTREE_STAGE
$previousSkillTreeOnly = $env:PAPER_SKILLTREE_ONLY
$previousMenu = $env:PAPER_MENU_WORKFLOW
$previousPaperJar = $env:PAPER_JAR_PATH
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$fixturePath = [System.IO.Path]::GetFullPath((Join-Path $repo $fixture))
$repoPath = [System.IO.Path]::GetFullPath($repo + [System.IO.Path]::DirectorySeparatorChar)
if (-not $fixturePath.StartsWith($repoPath, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Fixture must be inside workspace: $fixturePath"
}
if (Test-Path -LiteralPath (Join-Path $fixturePath 'integration-world') -PathType Container) {
    throw "Fixture already contains a persistent test world; choose a fresh FixtureName: $fixture"
}

function Assert-ExpiryLog([string]$Path, [string]$Stage, [int]$Entities) {
    $text = [System.IO.File]::ReadAllText($Path)
    $checks = [regex]::Matches($text, "(?m)^.*skilltree-expiry-check stage=$Stage [^\r\n]+$")
    $summaries = [regex]::Matches($text, "skilltree-expiry stage=$Stage entity=([0-9a-f-]{36}) checks=23 offline-deadline=(\d+) retained-deadline=(\d+) observed-at=(\d+) result=passed")
    if ($checks.Count -ne (23 * $Entities) -or $summaries.Count -ne $Entities -or
            @($checks | Where-Object { $_.Value -notmatch 'result=passed$' }).Count -ne 0 -or
            $text -match 'skilltree-[^\r\n]*result=failed') {
        throw "Incomplete timed SkillTree observations during $Stage"
    }
    if (@($summaries | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique).Count -ne $Entities -or
            @($summaries | ForEach-Object { $_.Groups[2].Value } | Select-Object -Unique).Count -ne 1 -or
            @($summaries | ForEach-Object { $_.Groups[3].Value } | Select-Object -Unique).Count -ne 1) {
        throw "Timed SkillTree entity identities or deadlines disagree during $Stage"
    }
    return [ordered]@{
        checks = $checks.Count
        offlineDeadline = [long]$summaries[0].Groups[2].Value
        retainedDeadline = [long]$summaries[0].Groups[3].Value
        entities = @($summaries | ForEach-Object { $_.Groups[1].Value })
        observedAt = @($summaries | ForEach-Object { [long]$_.Groups[4].Value })
    }
}

function Wait-ForDeadline([long]$Deadline) {
    while ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -le $Deadline) {
        Start-Sleep -Milliseconds 250
    }
}

function Save-SnapshotEvidence([string]$Stage, [int]$ExpectedEntities, [int]$ExpectedDeadlines) {
    $files = @(Get-ChildItem -LiteralPath $snapshotDirectory -Filter 'entity-*.yml' -File)
    if ($files.Count -ne $ExpectedEntities) { throw "Unexpected snapshot count after $Stage" }
    $destination = Join-Path $fixturePath "skilltree-$Stage-snapshots"
    New-Item -ItemType Directory -Path $destination | Out-Null
    return @($files | ForEach-Object {
        $text = [System.IO.File]::ReadAllText($_.FullName)
        if ([regex]::Matches($text, '(?m)^\s+expires-at:').Count -ne $ExpectedDeadlines) {
            throw "Unexpected deadline count after $Stage in $($_.Name)"
        }
        if ($Stage -ne 'write' -and $text -match 'name: expiry\.offline\.') {
            throw 'Elapsed first-boot variables were saved again'
        }
        if ($Stage -eq 'final' -and $text -match 'name: expiry\.(retained\.|parent)') {
            throw 'Elapsed second-boot variables were saved again'
        }
        Copy-Item -LiteralPath $_.FullName -Destination $destination
        [ordered]@{ path = "skilltree-$Stage-snapshots/$($_.Name)"; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
}

try {
    $env:PAPER_INTEGRATION_DIR = $fixture
    if (-not [string]::IsNullOrWhiteSpace($PaperJarPath)) {
        $env:PAPER_JAR_PATH = [System.IO.Path]::GetFullPath($PaperJarPath)
    }
    $env:PAPER_SKILLTREE_REUSE = 'false'
    $env:PAPER_SKILLTREE_STAGE = 'write'
    $env:PAPER_SKILLTREE_ONLY = 'true'
    $env:PAPER_MENU_WORKFLOW = 'false'
    New-Item -ItemType Directory -Path $fixturePath -Force | Out-Null
    $serverProperties = Join-Path $fixturePath 'server.properties'
    if (-not (Test-Path -LiteralPath $serverProperties -PathType Leaf)) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'server.properties') -Destination $serverProperties
    }
    $properties = Get-Content -LiteralPath $serverProperties -Raw
    $properties = [regex]::Replace($properties, '(?m)^difficulty=.*$', 'difficulty=normal')
    $properties = [regex]::Replace($properties, '(?m)^spawn-monsters=.*$', 'spawn-monsters=false')
    Set-Content -LiteralPath $serverProperties -Value $properties -Encoding ascii
    $runParams = @{ RunSeconds = 45 }
    if ($Exploratory) { $runParams.Exploratory = $true }
    & (Join-Path $PSScriptRoot 'run-server.ps1') @runParams

    $writeLog = Join-Path $fixturePath 'logs/latest.log'
    if (-not (Select-String -LiteralPath $writeLog -Pattern 'skilltree-restart stage=write result=passed mob-id=skilltree_probe entities=2 .*foundation-unlocked=true unsupported-value-rejected=true' -Quiet)) {
        throw 'First boot did not persist the SkillTree probe state'
    }
    $snapshotDirectory = Join-Path $fixturePath 'plugins/helsteraMobs/skill-trees'
    $snapshots = @(Get-ChildItem -LiteralPath $snapshotDirectory -Filter 'entity-*.yml' -File)
    if ($snapshots.Count -ne 2) {
        throw "Expected two UUID SkillTree snapshots after first shutdown; found $($snapshots.Count)"
    }
    if (Test-Path -LiteralPath (Join-Path $snapshotDirectory 'mob-skilltree_probe.yml')) {
        throw 'New SkillTree snapshots must use entity UUIDs, not the legacy mob ID filename'
    }
    $writeEvidenceLog = Join-Path $fixturePath 'skilltree-write.log'
    Copy-Item -LiteralPath $writeLog -Destination $writeEvidenceLog -Force
    $writeExpiry = Assert-ExpiryLog $writeEvidenceLog 'write' 2
    $firstStoppedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    if ($firstStoppedAt -ge $writeExpiry.offlineDeadline) { throw 'First deadline elapsed before the server stopped' }
    $writeSnapshots = @(Save-SnapshotEvidence 'write' 2 18)
    $firstPluginHash = (Get-FileHash -LiteralPath (Join-Path $fixturePath 'plugins/helsteraMobs.jar') -Algorithm SHA256).Hash.ToLowerInvariant()
    Wait-ForDeadline $writeExpiry.offlineDeadline

    $env:PAPER_SKILLTREE_REUSE = 'true'
    $env:PAPER_SKILLTREE_STAGE = 'verify'
    & (Join-Path $PSScriptRoot 'run-server.ps1') @runParams

    $verifyLog = Join-Path $fixturePath 'logs/latest.log'
    $verifyEvidenceLog = Join-Path $fixturePath 'skilltree-verify.log'
    Copy-Item -LiteralPath $verifyLog -Destination $verifyEvidenceLog -Force
    if (-not (Select-String -LiteralPath $verifyLog -Pattern 'skilltree-restart stage=verify result=passed mob-id=skilltree_probe entities=2 uuid-markers=true foundation-restored=true prerequisite-restored=true dotted-names-restored=true collections-restored=true advanced-unlocked=true' -Quiet)) {
        throw 'Second boot did not independently restore both UUID SkillTree snapshots'
    }
    if (-not (Select-String -LiteralPath $verifyLog -Pattern 'skilltree-restart stage=death result=passed mob-id=skilltree_probe .*entity-dead=true removed-snapshot=true survivor-snapshot=true' -Quiet)) {
        throw 'Confirmed death did not delete only the dead entity SkillTree snapshot'
    }
    $deathLine = Select-String -LiteralPath $verifyLog -Pattern 'skilltree-restart stage=death result=passed mob-id=skilltree_probe .*removed-entity=([0-9a-f-]{36})' | Select-Object -First 1
    $removedEntity = [regex]::Match($deathLine.Line, 'removed-entity=([0-9a-f-]{36})').Groups[1].Value
    $removedSnapshot = Join-Path $snapshotDirectory "entity-$removedEntity.yml"
    if (Test-Path -LiteralPath $removedSnapshot) {
        throw 'Dead entity SkillTree snapshot was recreated during shutdown'
    }
    $remainingSnapshots = @(Get-ChildItem -LiteralPath $snapshotDirectory -Filter 'entity-*.yml' -File)
    if ($remainingSnapshots.Count -ne 1) {
        throw "Expected only the surviving entity SkillTree snapshot after second shutdown; found $($remainingSnapshots.Count)"
    }
    $verifyExpiry = Assert-ExpiryLog $verifyEvidenceLog 'verify' 2
    $secondStoppedAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    if ($secondStoppedAt -ge $writeExpiry.retainedDeadline -or
            $verifyExpiry.offlineDeadline -ne $writeExpiry.offlineDeadline -or
            $verifyExpiry.retainedDeadline -ne $writeExpiry.retainedDeadline -or
            @(Compare-Object $writeExpiry.entities $verifyExpiry.entities).Count -ne 0) {
        throw 'Second boot changed entity identity/deadlines or outlasted the retained deadline'
    }
    $verifySnapshots = @(Save-SnapshotEvidence 'verify' 1 10)
    if ((Get-FileHash -LiteralPath (Join-Path $fixturePath 'plugins/helsteraMobs.jar') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $firstPluginHash) {
        throw 'Plugin artifact changed between restart phases'
    }
    Wait-ForDeadline $writeExpiry.retainedDeadline
    $env:PAPER_SKILLTREE_STAGE = 'final'
    & (Join-Path $PSScriptRoot 'run-server.ps1') @runParams
    $finalLog = Join-Path $fixturePath 'skilltree-final.log'
    Copy-Item -LiteralPath (Join-Path $fixturePath 'logs/latest.log') -Destination $finalLog
    if (-not (Select-String -LiteralPath $finalLog -Pattern 'skilltree-restart stage=final result=passed mob-id=skilltree_probe entities=1 .*expiry-passed=true' -Quiet)) {
        throw 'Third boot did not retain permanent state and remove elapsed variables'
    }
    $finalExpiry = Assert-ExpiryLog $finalLog 'final' 1
    if ($finalExpiry.offlineDeadline -ne $writeExpiry.offlineDeadline -or
            $finalExpiry.retainedDeadline -ne $writeExpiry.retainedDeadline -or
            $finalExpiry.entities[0] -eq $removedEntity -or $finalExpiry.entities[0] -notin $writeExpiry.entities -or
            (Test-Path -LiteralPath $removedSnapshot)) {
        throw 'Third boot changed deadlines or resurrected the dead entity snapshot'
    }
    $finalSnapshots = @(Save-SnapshotEvidence 'final' 1 0)

    $paperMatch = [regex]::Match((Get-Content -LiteralPath $verifyLog -Raw),
        'This server is running Paper version ([^ ]+)')
    if (-not $paperMatch.Success) {
        throw 'Could not identify the Paper build in the restart verification log'
    }
    $pluginJar = Join-Path $fixturePath 'plugins/helsteraMobs.jar'
    $paperJar = Join-Path $fixturePath 'paper.jar'
    if ((Get-FileHash -LiteralPath $pluginJar -Algorithm SHA256).Hash.ToLowerInvariant() -cne $firstPluginHash) {
        throw 'Plugin artifact changed between restart phases'
    }
    $evidence = [ordered]@{
        schemaVersion = 2
        fixtureDirectory = $fixture
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        paperVersion = $paperMatch.Groups[1].Value
        paperJarSha256 = (Get-FileHash -LiteralPath $paperJar -Algorithm SHA256).Hash.ToLowerInvariant()
        pluginSha256 = (Get-FileHash -LiteralPath $pluginJar -Algorithm SHA256).Hash.ToLowerInvariant()
        firstBoot = [ordered]@{
            result = 'passed'
            entities = 2
            snapshotsAfterShutdown = 2
            branchStateSaved = $true
            unsupportedVariableRejected = $true
            log = 'skilltree-write.log'
        }
        secondBoot = [ordered]@{
            result = 'passed'
            uuidMarkersRestored = $true
            foundationRestored = $true
            prerequisiteRestored = $true
            dottedVariableNamesRestored = $true
            dottedLocationNamesRestored = $true
            reservedAndDottedBranchNamesRestored = $true
            listVariableRestored = $true
            setVariableRestored = $true
            nestedMapVariableRestored = $true
            collectionsRestored = $true
            advancedBranchUnlocked = $true
            log = 'skilltree-verify.log'
        }
        entityDeath = [ordered]@{
            result = 'passed'
            deadEntitySnapshotRemoved = $true
            survivorSnapshotRetained = $true
            snapshotsAfterShutdown = 1
        }
        timedVariables = [ordered]@{
            result = 'passed'
            checks = 115
            firstShutdownAt = $firstStoppedAt
            secondShutdownAt = $secondStoppedAt
            firstBoot = $writeExpiry
            secondBoot = $verifyExpiry
            thirdBoot = $finalExpiry
            originalDeadlinesPreserved = $true
            expiredDuringDowntime = $true
            parentStateChecked = $true
            expiredValuesNotResaved = $true
        }
        thirdBoot = [ordered]@{
            result = 'passed'
            survivingEntityRestored = $true
            expiredValuesAbsent = $true
            permanentControlsRetained = $true
            deadEntitySnapshotAbsent = $true
            snapshotsAfterShutdown = 1
            log = 'skilltree-final.log'
        }
        snapshots = @($writeSnapshots) + @($verifySnapshots) + @($finalSnapshots)
        runnerSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
        probeSourceSha256 = (Get-FileHash -LiteralPath (Join-Path $repo 'helstera-plugin/src/main/java/dev/helstera/plugin/SkillTreeExpiryProbe.java') -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $evidencePath = Join-Path $fixturePath 'skilltree-restart-evidence.json'
    $evidence | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $evidencePath -Encoding utf8
    & (Join-Path $PSScriptRoot 'assert-skilltree-expiry-evidence.ps1') -FixturePath $fixturePath
    Write-Host "SkillTree restart integration passed: $fixturePath"
} finally {
    $env:PAPER_INTEGRATION_DIR = $previousFixture
    $env:PAPER_SKILLTREE_REUSE = $previousReuse
    $env:PAPER_SKILLTREE_STAGE = $previousStage
    $env:PAPER_SKILLTREE_ONLY = $previousSkillTreeOnly
    $env:PAPER_MENU_WORKFLOW = $previousMenu
    $env:PAPER_JAR_PATH = $previousPaperJar
}
