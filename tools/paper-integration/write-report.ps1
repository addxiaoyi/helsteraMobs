param(
    [Parameter(Mandatory = $true)]
    [string]$ServerDirectory,
    [double]$RandomSpawnTolerance = 0.15
)

$ErrorActionPreference = 'Stop'
if ([double]::IsNaN($RandomSpawnTolerance) -or [double]::IsInfinity($RandomSpawnTolerance) -or
        $RandomSpawnTolerance -lt 0 -or $RandomSpawnTolerance -gt 1) {
    throw 'RandomSpawnTolerance must be finite and between 0 and 1.'
}
$server = (Resolve-Path -LiteralPath $ServerDirectory).Path
$logPath = Join-Path $server 'logs/latest.log'
$reportPath = Join-Path $server 'integration-report.json'
$logText = if (Test-Path -LiteralPath $logPath -PathType Leaf) {
    Get-Content -LiteralPath $logPath -Raw -Encoding utf8
} else {
    ''
}

$paperMatch = [regex]::Match($logText,
    'This server is running Paper version (.+?) \(Implementing API version (.+?)\)')
$probeSpecs = [ordered]@{
    modelDisplay = '结果=通过.*Display实体数=[1-9][0-9]*.*实例有效=true'
    horse = 'horse-options result=true'
    sheep = 'sheep-options result=true'
    armorStand = 'armor-stand-options result=true'
    cat = 'cat-options result=true'
    fallingBlock = 'falling-block-options result=true'
    creeper = 'creeper-options result=true'
    bee = 'bee-options result=true'
    zombieAge = 'zombie-age-options result=true'
    attributes = 'attribute-options result=true modifiers=1'
    itemAttributes = 'item-attribute-options result=true modifiers=1'
    skillEquipment = 'skill-actions equipment=true'
    skillPotion = 'skill-actions .*potion=true'
    skillDropItem = 'skill-actions .*drop-item=true'
    skillTeleport = 'skill-actions .*teleport=true'
    conditionCatalog = 'skill-actions .*conditions=true'
    worldBiome = 'skill-actions .*world-biome=true'
    worldBiomeReject = 'skill-actions .*world-biome-reject=true'
    targetCategories = 'skill-actions .*target-categories=true'
    targetHealthComparators = 'skill-actions .*target-health=true'
    targetEnvironmentalConditions = 'skill-actions .*target-environment=true'
    targetFluidConditions = 'skill-actions .*target-fluids=true'
    targetBlockedCondition = 'skill-actions .*target-blocked=true'
    conditionComposition = 'skill-actions .*condition-composition=true'
    conditionMobility = 'skill-actions .*condition-mobility=true'
    conditionLeashed = 'skill-actions .*leashed-condition=true'
    conditionHasOwner = 'skill-actions .*has-owner-condition=true'
    conditionHasTag = 'skill-actions .*has-tag-condition=true'
    customOwnerRelation = 'skill-actions .*custom-owner-relation=true'
    parentRelation = 'skill-actions .*parent-relation=true'
    conditionIsChild = 'skill-actions .*is-child-condition=true'
    parentDerivedConditions = 'skill-actions .*parent-derived-conditions=true'
    timeWeather = 'skill-actions .*time-weather=true'
    customItemResolver = 'skill-actions .*custom-item=true'
    casterPermission = 'skill-actions .*caster-permission=true'
    foodLevelCondition = 'skill-actions .*food-level=true'
    hasItemCondition = 'skill-actions .*has-item=true'
    airLevelCondition = 'skill-actions .*air-level=true'
    playerPotionEvents = 'skill-actions .*player-potion-events=true'
    stateConditions = 'skill-actions .*state-conditions=true .*state-flying=true .*state-sneaking=true .*state-gliding=true'
    entityStateConditions = 'skill-actions .*entity-state-conditions=true'
    stateBlocking = 'skill-actions .*state-blocking=true'
    invulnerableCondition = 'skill-actions .*invulnerable-condition=true'
    targetInvulnerable = 'skill-actions .*target-invulnerable=true'
    ownerTargeter = 'skill-actions .*owner-targeter=true'
    spatialTargeters = 'skill-actions .*spatial-targeters=true'
    spatialTargetersNegative = 'spatial-targeter-details .*lineofsight-blocked=true'
    spatialTargetersBoundaries = 'spatial-targeter-details .*sphere-boundary=true .*ring-boundary=true .*cone-boundary=true'
    lineTargeterParameters = 'spatial-targeter-details .*line-named=true .*line-width-boundary=true .*line-length-boundary=true'
    targeterControls = 'spatial-targeter-details .*targeter-controls=true'
    targeterControlsNegative = 'spatial-targeter-details .*targeter-limit-zero=true .*targeter-negative-filter=true'
    deathLootItems = 'death-loot items=[1-9][0-9]*'
    deathLootExperience = 'death-loot .*experience=[1-9][0-9]*'
    deathLootConditions = 'death-loot .*conditional-drop=true.*rejected-drop-absent=true'
    deathLootDynamicChance = 'death-loot .*dynamic-chance-drop=true'
    lootMaterialization = 'loot table=.+materialized=[1-9][0-9]*'
    lootDynamicChance = 'loot table=.+dynamic-chance=true'
    events = 'events creeper-explode-cancelled=true bee-attack-cancelled=true zombie-transform-cancelled=true'
    pathfinderClear = 'pathfinder-clear probe=path_clear_probe .*accepted=true cleared=true .*passed=true'
    pathfinderStopCalls = 'pathfinder-clear .*stop-calls=[1-9][0-9]*'
    aiPriority = 'ai-priority-probe attack-first=true flee-first=true attack-state=ATTACK flee-state=FLEE'
    aiPriorityTargets = 'ai-priority-detail probe=priority_attack_probe target-assigned=true target-valid=true health-ratio=0.5 attack-priority=0 flee-priority=1 selector=mode=PLAYERS can-attack=true can-chase=true can-flee=true target-matches=true'
    skillTrigger = 'skill-trigger .*damage=true .*teleport=true .*bukkit-attacked=true'
    damageCauseCondition = 'skill-trigger .*damage-cause-condition=true'
    cancelledCondition = 'skill-trigger .*cancelled-condition=true .*cancelled-after=true'
    potionEffectEndTrigger = 'skill-trigger .*potion-effect-end=true'
    potionNaturalExpiry = 'potion-natural-expiry=true .*expired=true .*(?:action-observed=true|item-marker-observed=true)'
    attackerTargeter = 'skill-trigger .*attacker-targeter=true'
    threatTargeter = 'skill-targeter threat-targeter=true'
    threatFactionFilters = 'skill-targeter .*faction-filters=true'
    blockEvents = 'block-events break=true place=true player=\S+'
    itemPickup = 'item-pickup trigger=true player=\S+'
    vehicleEvents = 'vehicle-events .*enter=true .*exit=true .*dismount=true .*player=\S+'
    environmentalDamage = 'environmental-damage trigger=true fire-ticks=[0-9]+'
    spawnerMaxSpawns = 'spawner-max-spawns id=max_spawn_probe first=true second=false total=1'
    spawnerScopedLimits = 'spawner-scoped-limits id=scoped_limit_probe first=true second=false total=1 world-limit=1 failure=scoped spawn limits reached'
    spawnerPlayerLimits = 'spawner-player-limits id=player_limit_probe first=true second=false total=1 player-limit=1 failure=scoped spawn limits reached'
    spawnerCooldown = 'spawner-cooldown id=cooldown_probe .*remaining-first=[1-9][0-9]* remaining-second=[0-9]+'
    spawnerLunar = 'spawner-lunar id=lunar_probe requested=true phase=FULL failure=null'
    spawnerHeightmap = 'spawner-heightmap id=heightmap_ground_pass requested=true surface=\S+ surface-y=-?\d+ failure=null'
    spawnerGroundRestored = 'spawner-ground id=ground_missing_fail requested=false failure=no valid spawn location restored=true saved-blocks=[1-9][0-9]*'
    spawnerBorderClipped = 'spawner-border id=border_clipped_inside_pass requested=true configured-radius=16(?:\.0+)? effective-radius=10(?:\.0+)? border-size=20(?:\.0+)? restored=true failure=null'
    spawnerBorderOutside = 'spawner-border id=border_outside_fail requested=false configured-radius=0\.1 effective-radius=0(?:\.0+)? border-size=20(?:\.0+)? restored=true failure=no valid spawn location'
    tempt = 'tempt-probe .* native-target=true .*passed=true'
    entityTargetSynthetic = 'tempt-probe .* synthetic-entity-target=true synthetic-before=false'
    entityTargetBukkit = 'tempt-probe .* entity-target-trigger=true bukkit-target-before=false'
    entityTargetDelayed = 'tempt-probe-delayed entity-target-trigger=true target-events=[1-9][0-9]* target-find-misses=0'
    audienceDispatch = 'audience player=\S+ message-dispatched=true title-dispatched=true actionbar-dispatched=true particle-dispatched=true radius-included-dispatched=true radius-excluded-dispatched=true permission-included-dispatched=true'
}
$probes = @(
    foreach ($entry in $probeSpecs.GetEnumerator()) {
        $matches = [regex]::Matches($logText, $entry.Value)
        [ordered]@{
            id = $entry.Key
            status = if ($matches.Count -gt 0) { 'passed' } else { 'not-observed' }
            observations = @($matches | ForEach-Object { $_.Value })
        }
    }
)

$menuClientLog = Join-Path $server 'menu-client.stdout.log'
if (Test-Path -LiteralPath $menuClientLog -PathType Leaf) {
    $menuLine = Get-Content -LiteralPath $menuClientLog -Encoding utf8 |
        Where-Object { $_ -match '^PAPER_MENU_REPORT ' } | Select-Object -Last 1
    $menuStatus = 'not-observed'
    $menuObservation = 'client report missing'
    if ($menuLine -match '^PAPER_MENU_REPORT (.+)$') {
        try {
            $menu = $Matches[1] | ConvertFrom-Json
            $menuObservation = $Matches[1]
            $required = @('permissionDeniedClickStayedOpen', 'pageOne', 'pageTwo',
                'actionClosedInventory', 'allowedActionMessage', 'deniedActionMessageAbsent')
            $allPassed = $menu.status -eq 'passed'
            foreach ($field in $required) { $allPassed = $allPassed -and $menu.observations.$field -eq $true }
            if ($allPassed) { $menuStatus = 'passed' }
        } catch {
            $menuObservation = "invalid client report: $($_.Exception.Message)"
        }
    }
    $probes += [ordered]@{
        id = 'custom-menu-player-workflow'
        status = $menuStatus
        observations = @($menuObservation)
    }
}

$audienceClientLog = Join-Path $server 'audience-client.stdout.log'
if (Test-Path -LiteralPath $audienceClientLog -PathType Leaf) {
    $audienceLine = Get-Content -LiteralPath $audienceClientLog -Encoding utf8 |
        Where-Object { $_ -match '^PAPER_AUDIENCE_REPORT ' } | Select-Object -Last 1
    $audienceStatus = 'not-observed'
    $audienceObservation = 'client report missing'
    if ($audienceLine -match '^PAPER_AUDIENCE_REPORT (.+)$') {
        try {
            $audience = $Matches[1] | ConvertFrom-Json
            $audienceObservation = $Matches[1]
            $allPassed = $audience.status -eq 'passed' -and
                $audience.observations.message -eq $true -and
                $audience.observations.title -eq $true -and
                $audience.observations.actionbar -eq $true -and
                $audience.observations.particle -eq $true
            if ($allPassed) { $audienceStatus = 'passed' }
        } catch {
            $audienceObservation = "invalid client report: $($_.Exception.Message)"
        }
    }
    $probes += [ordered]@{
        id = 'audience-player-delivery'
        status = $audienceStatus
        observations = @($audienceObservation)
    }

    $filterLine = Get-Content -LiteralPath $audienceClientLog -Encoding utf8 |
        Where-Object { $_ -match '^PAPER_AUDIENCE_FILTER_REPORT ' } | Select-Object -Last 1
    $filterStatus = 'not-observed'
    $filterObservation = 'audience filter report missing'
    if ($filterLine -match '^PAPER_AUDIENCE_FILTER_REPORT (.+)$') {
        try {
            $filter = $Matches[1] | ConvertFrom-Json
            $filterObservation = $Matches[1]
            $required = @('radiusIncludedTester', 'radiusIncludedGuest',
                'radiusExcludedTester', 'radiusExcludedGuest',
                'permissionIncludedTester', 'permissionIncludedGuest',
                'factionIncludedTester', 'factionIncludedGuest',
                'factionExcludedTester', 'factionExcludedGuest')
            $allPassed = $filter.status -eq 'passed'
            foreach ($field in $required) { $allPassed = $allPassed -and $filter.observations.$field -eq $true }
            if ($allPassed) { $filterStatus = 'passed' }
        } catch {
            $filterObservation = "invalid audience filter report: $($_.Exception.Message)"
        }
    }
    $probes += [ordered]@{
        id = 'audience-filter-delivery'
        status = $filterStatus
        observations = @($filterObservation)
    }
}

$spawnLines = [regex]::Matches($logText, 'spawner(?:-condition|-probe)? id=.+')
foreach ($match in $spawnLines) {
    $line = $match.Value
    $spawnId = if ($line -match 'spawner(?:-condition|-probe)? id=([^ ]+)') { $Matches[1] } else { 'unknown' }
    $probes += [ordered]@{
        id = "spawner:$spawnId"
        status = if ($line -match 'requested=true') { 'passed' } else { 'observed-rejected' }
        observations = @($line)
    }
}

$randomMatches = [regex]::Matches($logText,
    'random-spawn (?:id=[^ ]+ )?expected=(.+?) observed=(.+?) deviation=(.+?) max-abs-deviation=([0-9.]+) total=(\d+)')
foreach ($match in $randomMatches) {
    $sampleCount = [int]$match.Groups[5].Value
    $maxDeviation = [double]::Parse($match.Groups[4].Value,
        [System.Globalization.CultureInfo]::InvariantCulture)
    $randomStatus = if ($sampleCount -lt 100) {
        'insufficient-sample'
    } elseif ($maxDeviation -le $RandomSpawnTolerance) {
        'passed'
    } else {
        'failed'
    }
    $probes += [ordered]@{
        id = 'random-spawn-distribution'
        status = $randomStatus
        sampleCount = $sampleCount
        expected = $match.Groups[1].Value.Trim()
        observed = $match.Groups[2].Value.Trim()
        deviation = $match.Groups[3].Value.Trim()
        maxDeviation = $maxDeviation
        tolerance = $RandomSpawnTolerance
        observations = @($match.Value)
    }
}

$teleportMatch = [regex]::Match($logText,
    'skill-actions .*teleport=true .*before=([^ ]+) after=([^ ]+) offset-distance=([0-9.]+) moved-distance=([0-9.]+)')
if ($teleportMatch.Success) {
    $probes += [ordered]@{
        id = 'skillTeleportCoordinates'
        status = 'passed'
        before = $teleportMatch.Groups[1].Value
        after = $teleportMatch.Groups[2].Value
        offsetDistance = [double]$teleportMatch.Groups[3].Value
        movedDistance = [double]$teleportMatch.Groups[4].Value
        observations = @($teleportMatch.Value)
    }
}

$report = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    fixtureDirectory = Split-Path -Leaf $server
    paperVersion = if ($paperMatch.Success) { $paperMatch.Groups[1].Value } else { $null }
    paperApiVersion = if ($paperMatch.Success) { $paperMatch.Groups[2].Value } else { $null }
    pluginEnabled = $logText.Contains('Enabling helsteraMobs v1.0.0') -and $logText.Contains('Done (')
    log = 'logs/latest.log'
    probes = @($probes)
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Host "Paper integration report: $reportPath"
