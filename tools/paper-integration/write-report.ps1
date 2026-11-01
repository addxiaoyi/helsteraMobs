param(
    [Parameter(Mandatory = $true)]
    [string]$ServerDirectory,
    [ValidateSet('release', 'exploratory')]
    [string]$EvidenceMode = 'release',
    [double]$RandomSpawnTolerance = 0.15,
    [DateTime]$StartedAtUtc = [DateTime]::MinValue
)

$ErrorActionPreference = 'Stop'
if ([double]::IsNaN($RandomSpawnTolerance) -or [double]::IsInfinity($RandomSpawnTolerance) -or
        $RandomSpawnTolerance -lt 0 -or $RandomSpawnTolerance -gt 1) {
    throw 'RandomSpawnTolerance must be finite and between 0 and 1.'
}
$server = (Resolve-Path -LiteralPath $ServerDirectory).Path
$logPath = Join-Path $server 'logs/latest.log'
$reportPath = Join-Path $server 'integration-report.json'
$logParts = [System.Collections.Generic.List[string]]::new()
if (Test-Path -LiteralPath $logPath -PathType Leaf) {
    $logParts.Add((Get-Content -LiteralPath $logPath -Raw -Encoding utf8))
}

# Paper rolls logs at midnight. Include only rotated files written during this
# run so a date boundary cannot hide startup evidence or mix older fixtures.
if ($StartedAtUtc -ne [DateTime]::MinValue) {
    $rotatedLogs = Get-ChildItem -LiteralPath (Join-Path $server 'logs') -File -Filter '*.log.gz' -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTimeUtc -ge $StartedAtUtc.AddSeconds(-2) } |
        Sort-Object LastWriteTimeUtc, Name
    foreach ($rotated in $rotatedLogs) {
        $file = [System.IO.File]::OpenRead($rotated.FullName)
        $gzip = [System.IO.Compression.GzipStream]::new(
            $file, [System.IO.Compression.CompressionMode]::Decompress)
        $reader = [System.IO.StreamReader]::new($gzip, [System.Text.Encoding]::UTF8)
        try {
            $logParts.Add($reader.ReadToEnd())
        } finally {
            $reader.Dispose()
            $gzip.Dispose()
            $file.Dispose()
        }
    }
}
$logText = [string]::Join("`n", $logParts)

$paperMatch = [regex]::Match($logText,
    'This server is running Paper version (.+?) \(Implementing API version (.+?)\)')
$probeSpecs = [ordered]@{
    modelDisplay = '结果=通过.*Display实体数=[1-9][0-9]*.*实例有效=true'
    horse = 'horse-options result=true saddled=true .*armor=true .*chest=true'
    maxCombatDistance = 'max-combat-distance result=true inside-allowed=true outside-blocked=true'
    scaleOptions = 'scale-options result=true scale=1\.25'
    healthOptions = 'health-options result=true health=42(?:\.0)? max-health=42(?:\.0)?'
    movementSpeedOptions = 'movement-speed-options result=true base=0\.4(?:0+)?'
    knockbackResistanceOptions = 'knockback-resistance-options result=true base=0\.75(?:0+)?'
    followRangeOptions = 'follow-range-options result=true base=40(?:\.0+)?'
    damageOptions = 'damage-options result=true base=12(?:\.0+)?'
    armorOptions = 'armor-options result=true base=8(?:\.0+)?'
    entityConfigFlags = 'entity-config-flags result=true no-ai=true invulnerable=true silent=true gravity=false collidable=false'
    expOptions = 'exp-options result=true experience=13 event=true'
    despawnOptions = 'despawn-options result=true mode=PERSISTENT distance=0(?:\.0+)? persistent=true'
    spawningDistanceOptions = 'spawning-distance-options result=true mode=NORMAL distance=8(?:\.0+)?'
    spawningDistanceRuntime = 'spawning-distance-runtime result=true inside-retained=true outside-removed=true'
    visibilityOptions = 'visibility-options result=true visible-by-default=true invisible=true show-name=true'
    sheep = 'sheep-options result=true'
    armorStand = 'armor-stand-options result=true .*can-move=false .*can-tick=false .*items=true'
    cat = 'cat-options result=true'
    fallingBlock = 'falling-block-options result=true'
    creeper = 'creeper-options result=true'
    bee = 'bee-options result=true'
    entitySizeRange = 'entity-size-range result=true samples=64 configured=2\.\.5 observed=[2-5]\.\.[2-5]'
    ageOptions = 'age-options result=true'
    entityFlagOptions = 'entity-flag-options result=true .*patrol-spawn-point=true'
    endermanHeldBlock = 'enderman-held-block result=true material=OBSIDIAN'
    spawnOptionRuntime = 'spawn-option-runtime result=true pickup-disabled=true explicit-pickup-overrides-fallback=true random-equipment-cleared=true explicit-equipment-preserved=true no-damage-ticks=true'
    attributes = 'attribute-options result=true modifiers=1'
    itemAttributes = 'item-attribute-options result=true modifiers=1'
    skillEquipment = 'skill-actions equipment=true'
    playerExperienceMechanics = 'skill-actions .*player-experience-mechanics=true'
    oxygenMechanic = 'skill-actions .*oxygen-mechanic=true'
    consumeSlotMechanic = 'skill-actions .*consume-slot-mechanic=true'
    rawExperienceMechanic = 'skill-actions .*raw-experience-mechanic=true'
    skillPotion = 'skill-actions .*potion=true'
    skillDropItem = 'skill-actions .*drop-item=true'
    skillTeleport = 'skill-actions .*teleport=true'
    skillScheduling = 'skill-scheduler result=true delay=2 repeat=3 interval=2 executions=4 pre-delay-count=0'
    skillTreeBranches = 'skill-tree-branches result=true condition-denied=true condition-unlock=true prerequisite-blocked=true prerequisite-unlock=true'
    skillTreeCastPersistence = 'skill-tree-cast-persistence result=true blocked-before-unlock=true prerequisite-unlock-blocked=true snapshot-saved=true advanced-unlocked=true casts=1'
    conditionCatalog = 'skill-actions (?=.*conditions=true)(?=.*owner-condition=true)(?=.*children-condition=true)(?=.*inside-outside-conditions=true)(?=.*distance-from-location-condition=true)(?=.*coordinate-difference-condition=true)(?=.*origin-location-condition=true)(?=.*line-of-sight-from-origin-condition=true)(?=.*bounds-condition=true)(?=.*enchanting-conditions=true)(?=.*plugin-condition=true)(?=.*field-of-view-condition=true)(?=.*spawn-reason-condition=true)(?=.*water-mob-condition=true)(?=.*zombie-condition=true)(?=.*aerial-condition=true)(?=.*enderman-condition=true)(?=.*enchantment-condition=true)(?=.*color-condition=true)(?=.*material-cooldown-condition=true)(?=.*trigger-item-type-condition=true)(?=.*entity-item-type-condition=true)(?=.*entity-item-similar-condition=true)(?=.*item-similar-condition=true)(?=.*targets-condition=true)(?=.*item-type-condition=true)(?=.*mythic-mob-type-condition=true)(?=.*trigger-block-type-condition=true)(?=.*frozen-condition=true)(?=.*dialogue-condition=true)(?=.*player-kills-condition=true)(?=.*absorption-condition=true)(?=.*health-absorption-condition=true)(?=.*potion-effect-condition=true)(?=.*aura-stacks-condition=true)(?=.*level-condition=true)(?=.*lunar-phase-condition=true)(?=.*string-equals-condition=true)(?=.*string-empty-condition=true)(?=.*compare-values-condition=true)(?=.*is-skill-condition=true)(?=.*global-score-condition=true)(?=.*score-condition=true)'
    absorptionCondition = 'skill-actions .*absorption-condition=true'
    healthAbsorptionCondition = 'skill-actions .*health-absorption-condition=true'
    potionEffectCondition = 'skill-actions .*potion-effect-condition=true'
    auraStacksCondition = 'skill-actions .*aura-stacks-condition=true'
    levelCondition = 'skill-actions .*level-condition=true'
    lunarPhaseCondition = 'skill-actions .*lunar-phase-condition=true'
    stringEqualsCondition = 'skill-actions .*string-equals-condition=true'
    stringEmptyCondition = 'skill-actions .*string-empty-condition=true'
    compareValuesCondition = 'skill-actions .*compare-values-condition=true'
    isSkillCondition = 'skill-actions .*is-skill-condition=true'
    materialCooldownMechanic = 'skill-actions .*material-cooldown-mechanic=true'
    setGameModeMechanic = 'skill-actions .*set-game-mode-mechanic=true'
    jumpMechanic = 'skill-actions .*jump-mechanic=true'
    forcePullMechanic = 'skill-actions .*force-pull-mechanic=true'
    setOffhandMechanic = 'skill-actions .*set-offhand-mechanic=true'
    setArmorMechanic = 'skill-actions .*set-armor-mechanic=true'
    clearInventoryMechanic = 'skill-actions .*clear-inventory-mechanic=true'
    removeHeldItemMechanic = 'skill-actions .*remove-held-item-mechanic=true'
    setAbsorptionMechanic = 'skill-actions .*set-absorption-mechanic=true'
    frozenCondition = 'skill-actions .*frozen-condition=true'
    boundsCondition = 'skill-actions .*bounds-condition=true'
    boundingBoxOverlapCondition = 'skill-actions .*bounding-box-overlap-condition=true'
    entityItemTypeCondition = 'skill-actions .*entity-item-type-condition=true'
    entityItemSimilarCondition = 'skill-actions .*entity-item-similar-condition=true'
    itemSimilarCondition = 'skill-actions .*item-similar-condition=true'
    itemTypeCondition = 'skill-actions .*item-type-condition=true'
    structureCondition = 'skill-actions .*structure-condition=true'
    targetsCondition = 'skill-actions .*targets-condition=true'
    lineOfSightFromOriginCondition = 'skill-actions .*line-of-sight-from-origin-condition=true'
    originLocationCondition = 'skill-actions .*origin-location-condition=true'
    triggerBlockTypeCondition = 'skill-actions .*trigger-block-type-condition=true'
    worldBiome = 'skill-actions .*world-biome=true'
    worldBiomeReject = 'skill-actions .*world-biome-reject=true'
    targetCategories = 'skill-actions .*target-categories=true'
    targetHealthComparators = 'skill-actions .*target-health=true'
    factionConditions = 'skill-actions .*faction-conditions=true'
    factionMechanic = 'skill-actions .*faction-mechanic=true'
    levelMechanic = 'skill-actions .*level-mechanic=true'
    weatherMechanic = 'skill-actions .*weather-mechanic=true'
    timeMechanic = 'skill-actions .*time-mechanic=true'
    lightningMechanic = 'skill-actions .*lightning-mechanic=true'
    signalMechanics = 'skill-actions .*signal-mechanics=true'
    globalScoreCondition = 'skill-actions .*global-score-condition=true'
    scoreCondition = 'skill-actions .*score-condition=true'
    targetKillMechanic = 'skill-actions .*target-kill=true'
    damageMeleeMechanic = 'skill-actions .*damage-melee=true'
    conditionUtilityAliases = 'skill-actions .*condition-utility=true .*block-type-radius-condition=true'
    playerRadiusConditions = 'skill-actions .*player-radius-conditions=true'
    cooldownMechanics = 'skill-actions .*cooldown-mechanics=true'
    lastDamageCauseCondition = 'skill-actions .*last-damage-cause=true'
    motionConditions = 'skill-actions .*motion-conditions=true .*directional-velocity-condition=true .*fall-speed-condition=true'
    nameCondition = 'skill-actions .*name-condition=true'
    dotMechanic = 'skill-dot damage-observed=true bounded=true expired=true'
    projectileHasEndedCondition = 'projectile-has-ended=true observed=true'
    targetEnvironmentalConditions = 'skill-actions .*target-environment=true'
    targetFluidConditions = '(?:skill-actions .*target-fluids=true|target-fluids-delayed water=true lava=true passed=true)'
    targetBlockedCondition = 'skill-actions .*target-blocked=true'
    targetStateConditions = 'skill-actions .*target-state-conditions=true'
    conditionComposition = 'skill-actions .*condition-composition=true'
    conditionMobility = 'skill-actions .*condition-mobility=true'
    conditionLeashed = 'skill-actions .*leashed-condition=true'
    conditionHasOwner = 'skill-actions .*has-owner-condition=true'
    conditionHasTag = 'skill-actions .*has-tag-condition=true'
    targetHasTagCondition = 'skill-actions .*target-has-tag-condition=true'
    scoreboardTagMechanics = 'skill-actions .*scoreboard-tag-mechanics=true'
    scoreMechanics = 'skill-actions .*score-mechanics=true'
    customOwnerRelation = 'skill-actions .*custom-owner-relation=true'
    ownerOnlineCondition = 'skill-actions .*owner-online-condition=true'
    parentRelation = 'skill-actions .*parent-relation=true'
    conditionIsChild = 'skill-actions .*is-child-condition=true'
    parentDerivedConditions = 'skill-actions .*parent-derived-conditions=true'
    timeWeather = 'skill-actions .*time-weather=true'
    customItemResolver = 'skill-actions .*custom-item=true'
    giveItemAction = 'skill-actions .*give-item=true'
    takeItemAction = 'skill-actions .*take-item=true'
    dynamicPlaceholderAction = 'skill-actions .*dynamic-placeholder=true'
    gameplayPlaceholders = 'skill-actions .*gameplay-placeholders=true'
    blockSetAction = 'skill-actions .*block-set=true'
    blockBreakAction = 'skill-actions .*block-break=true'
    setAiAction = 'skill-actions .*set-ai=true'
    setGravityAction = 'skill-actions .*set-gravity=true'
    setSilentAction = 'skill-actions .*set-silent=true'
    setCollidableAction = 'skill-actions .*set-collidable=true'
    setFireAction = 'skill-actions .*set-fire=true'
    clearFireAction = 'skill-actions .*clear-fire=true'
    rotationAction = 'skill-actions .*set-rotation=true'
    lookAtAction = 'skill-actions .*look-at=true'
    casterPermission = 'skill-actions .*caster-permission=true'
    foodLevelCondition = 'skill-actions .*food-level=true'
    foodSaturationCondition = 'skill-actions .*food-saturation=true'
    freeInventorySlotCondition = 'skill-actions .*free-inventory-slot=true'
    hasItemCondition = 'skill-actions .*has-item=true'
    airLevelCondition = 'skill-actions .*air-level=true'
    lookingAtCondition = 'skill-actions .*looking-at-condition=true'
    itemRechargingCondition = 'skill-actions .*item-recharging-condition=true'
    playerPotionEvents = 'skill-actions .*player-potion-events=true'
    stateConditions = 'skill-actions .*state-conditions=true .*state-flying=true .*state-sneaking=true .*state-gliding=true'
    entityStateConditions = 'skill-actions .*entity-state-conditions=true'
    stateBlocking = 'skill-actions .*state-blocking=true'
    spyglassCondition = 'skill-actions .*spyglass-condition=true'
    invulnerableCondition = 'skill-actions .*invulnerable-condition=true'
    targetInvulnerable = 'skill-actions .*target-invulnerable=true'
    ownerTargeter = 'skill-actions .*owner-targeter=true'
    spatialTargeters = 'skill-actions .*spatial-targeters=true'
    spatialTargetersNegative = 'spatial-targeter-details .*lineofsight-blocked=true'
    spatialTargetersBoundaries = 'spatial-targeter-details .*sphere-boundary=true .*ring-boundary=true .*cone-boundary=true'
    lineTargeterParameters = 'spatial-targeter-details .*line-named=true .*line-width-boundary=true .*line-length-boundary=true'
    targeterControls = 'spatial-targeter-details .*targeter-controls=true'
    targeterControlsNegative = 'spatial-targeter-details .*targeter-limit-zero=true .*targeter-negative-filter=true .*targeter-invalid-limits=true'
    deathLootItems = 'death-loot items=[1-9][0-9]*'
    deathLootExperience = 'death-loot .*experience=[1-9][0-9]*'
    deathLootConditions = 'death-loot .*conditional-drop=true.*rejected-drop-absent=true'
    deathLootDynamicChance = 'death-loot .*dynamic-chance-drop=true'
    lootMaterialization = 'loot table=.+materialized=[1-9][0-9]*'
    lootDynamicChance = 'loot table=.+dynamic-chance=true'
    events = 'events creeper-explode-cancelled=true bee-attack-cancelled=true zombie-transform-cancelled=true .*prevent-transformation-reasons=true .*prevent-skeleton-conversion=true .*prevent-block-infection=true .*prevent-snow-formation=true .*prevent-leash=true .*prevent-rename=true .*prevent-target=true .*prevent-vanilla-damage=true .*prevent-projectiles=true .*prevent-entity-explosions=true .*prevent-block-explosion-damage=true .*prevent-drowning=true .*prevent-fall-damage=true .*prevent-fire=true .*prevent-fire-causes=true .*prevent-pickup=true .*prevent-item-use=true .*interactable=true .*interactable-armor-stand=true .*prevent-teleport=true .*prevent-sunburn=true .*prevent-other-drops=true .*prevent-mob-kill-drops=true .*prevent-mob-kill-custom-loot=true'
    tameBreed = 'events .*tame-breed-tame=true tame-breed-breed=true'
    leashLifecycle = 'events .*leash-unleash-leash=true leash-unleash-unleash=true'
    shearTrigger = 'events .*shear-trigger=true'
    bedLifecycle = 'events .*bed-enter-leave-enter=true bed-enter-leave-leave=true'
    tradeTrigger = 'events .*trade-trigger=true'
    quitTrigger = 'events .*quit-trigger=true'
    itemLifecycle = 'events .*item-drop-break-damage=true'
    playerActions = 'events .*player-actions-interact=true world-change=true jump=true swing=true'
    fishTrigger = 'events .*fish-trigger=true'
    creeperNaturalFuse = 'mob-options-natural creeper-fuse=true exploded=true max-fuse=20 passed=true'
    mobOptionsNatural = 'mob-options-natural bee-stinger=true .*zombie-conversion=true zombie-control=true zombie-protected=true .*passed=true'
    mobOptionsMountEvent = 'mob-options-mount-event prevented=true allowed=true generic-prevented=true generic-allowed=true .*passed=true'
    mobOptionsSlimeSplit = 'mob-options-slime-split prevented=true allowed=true passed=true'
    pathfinderClear = 'pathfinder-clear probe=path_clear_probe .*accepted=true .*cleared=true .*passed=true'
    pathfinderStopCalls = 'pathfinder-clear .*stop-calls=[1-9][0-9]*'
    aiPriority = 'ai-priority-probe attack-first=true flee-first=true attack-state=ATTACK flee-state=FLEE'
    aiPriorityTargets = 'ai-priority-detail probe=priority_attack_probe target-assigned=true target-valid=true health-ratio=0.5 attack-priority=0 flee-priority=1 selector=mode=PLAYERS can-attack=true can-chase=true can-flee=true target-matches=true'
    aiSelectorCore = 'ai-selector-core .*player-target=true .*monster-target=true .*clear-no-fallback=true .*owner-target=true .*faction-target=true .*conditional-target=true .*players-in-world=true .*mobs-in-world=true .*world-caster-excluded=true .*world-chain-filtered=true .*nearest-living=true .*nearest-mob=true .*set-target-mechanic=true .*clear-target-mechanic=true .*context-chain-action=true .*native-target=true .*passed=true'
    aiRoute = 'ai-route-probe token=followpath .*active-before=true .*moving=true .*advanced=true .*passed=true'
    skillTrigger = 'skill-trigger .*damage=true .*teleport=true .*bukkit-attacked=true'
    damageCauseCondition = 'skill-trigger .*damage-cause-condition=true .*damage-amount-condition=true .*bow-tension-condition=true'
    bowTensionCondition = 'skill-trigger .*bow-tension-condition=true'
    cancelledCondition = 'skill-trigger .*cancelled-condition=true .*cancelled-after=true'
    potionEffectEndTrigger = 'skill-trigger .*potion-effect-end=true'
    potionNaturalExpiry = 'potion-natural-expiry=true .*expired=true .*(?:action-observed=true|item-marker-observed=true)'
    killTrigger = 'kill-trigger natural=true .*victim-dead=true .*action-observed=true passed=true'
    playerKillTrigger = 'player-kill-trigger natural=true .*victim-player=true .*victim-death-observed=true .*action-observed=true .*player-kills-positive=true .*player-kills-negative=true passed=true'
    playerKillsCondition = 'skill-actions .*player-kills-condition=true'
    mythicMobTypeCondition = 'skill-actions .*mythic-mob-type-condition=true'
    variableContainsCondition = 'skill-actions .*variable-contains-condition=true'
    attackerTargeter = 'skill-trigger .*attacker-targeter=true'
    threatTargeter = 'skill-targeter threat-targeter=true'
    threatFactionFilters = 'skill-targeter .*faction-filters=true'
    threatActions = 'skill-targeter .*threat-actions=true'
    blockEvents = 'block-events (?:.*break=true .*place=true|.*synthetic-break=true synthetic-place=true) .*player=\S+'
    itemPickup = 'item-pickup trigger=true player=\S+'
    vehicleEvents = 'vehicle-events .*enter=true .*exit=true .*dismount=true .*destroy=true .*player=\S+'
    ejectPassenger = 'vehicle-events .*eject=true .*player=\S+'
    environmentalDamage = 'environmental-damage trigger=true fire-ticks=-?[0-9]+'
    spawnerMaxSpawns = 'spawner-max-spawns id=max_spawn_probe first=true second=false total=1'
    spawnerGroup = 'spawner-group id=group_probe requested=true alive=[12] total=[12] max-alive=2 group-min=3 group-max=5 limit=true'
    spawnerNaturalGroup = 'spawner-natural-group id=natural_group_probe mode=live-scheduler before=[0-9]+ after=[1-9][0-9]* alive=2 max-alive=2 group-min=3 group-max=5 limit=true'
    spawnerRecycle = 'spawner-recycle id=recycle_probe before=[1-9][0-9]* after=[2-9][0-9]* released=true respawned=true alive=1 interval=[2-9][0-9]*'
    spawnerRegion = 'spawner-region id=region_probe requested=true region=integration_box failure=null'
    spawnerRegionOutside = 'spawner-region-outside id=region_probe_outside requested=false region=integration_box failure=conditions rejected location'
    spawnerScopedLimits = 'spawner-scoped-limits id=scoped_limit_probe first=true second=false total=1 world-limit=1 failure=scoped spawn limits reached'
    # The fixture may have an earlier automatic spawn for this id; the
    # player-limit assertion is the first/second result and explicit failure,
    # not the lifetime total.
    spawnerPlayerLimits = 'spawner-player-limits id=player_limit_probe first=true second=false total=[1-9][0-9]* player-limit=1 failure=scoped spawn limits reached'
    spawnerCooldown = 'spawner-cooldown id=cooldown_probe .*remaining-first=[1-9][0-9]* remaining-second=[0-9]+'
    spawnerFailureCooldown = 'spawner-failure-cooldown id=failed_cooldown_probe before=0 after=0 total-before=0 total-after=0 rejected=true failure=no-valid-location'
    spawnerNaturalTiming = 'spawner-natural-timing id=cooldown_probe mode=live-scheduler .*before-due=true .*after-due=true'
    spawnerLunar = 'spawner-lunar id=lunar_probe requested=true phase=FULL failure=null'
    spawnerHeightmap = 'spawner-heightmap id=heightmap_ground_pass requested=true surface=\S+ surface-y=-?\d+ failure=null'
    spawnerGroundRestored = 'spawner-ground id=ground_missing_fail requested=false failure=no valid spawn location restored=true saved-blocks=[1-9][0-9]*'
    spawnerUnloadedChunk = 'spawner-unloaded-chunk id=unloaded_chunk_fail requested=false chunk-loaded=false failure=no valid spawn location restored=true'
    spawnerLightFailClosed = 'spawner-light-fail-closed id=light_unloaded_fail requested=false chunk-loaded=false light-readable=false result=false restored=true'
    spawnerBorderClipped = 'spawner-border id=border_clipped_inside_pass requested=true configured-radius=16(?:\.0+)? effective-radius=10(?:\.0+)? border-size=20(?:\.0+)? restored=true failure=null'
    spawnerBorderOutside = 'spawner-border id=border_outside_fail requested=false configured-radius=0\.1 effective-radius=0(?:\.0+)? border-size=20(?:\.0+)? restored=true failure=no valid spawn location'
    tempt = 'tempt-probe .* native-target=true .*passed=true'
    entityTargetSynthetic = 'tempt-probe .* synthetic-entity-target=true synthetic-before=false'
    # Paper may deliver the native target callback after the same-tick
    # observation; accept the delayed native marker as authoritative too.
    entityTargetBukkit = '(?:tempt-probe .* entity-target-trigger=true bukkit-target-before=false|tempt-probe-delayed entity-target-trigger=true .*target-cancelled=false)'
    # target-find-misses is a global listener counter and can include unrelated
    # managed entities; the probe identity is established by its delayed marker,
    # reason, and cancellation state.
    entityTargetDelayed = 'tempt-probe-delayed entity-target-trigger=true target-events=[1-9][0-9]* target-find-misses=[0-9]+ target-reason=(?:CLOSEST_PLAYER|TEMPT) target-cancelled=false'
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
$menuStatus = 'not-observed'
$menuObservation = 'client report missing'
if (Test-Path -LiteralPath $menuClientLog -PathType Leaf) {
    $menuLine = Get-Content -LiteralPath $menuClientLog -Encoding utf8 |
        Where-Object { $_ -match '^PAPER_MENU_REPORT ' } | Select-Object -Last 1
    if ($menuLine -match '^PAPER_MENU_REPORT (.+)$') {
        try {
            $menu = $Matches[1] | ConvertFrom-Json
            $menuObservation = $Matches[1]
            $required = @('pageOne', 'deniedActionMessageAbsent')
            $allPassed = $menu.status -eq 'passed'
            foreach ($field in $required) { $allPassed = $allPassed -and $menu.observations.$field -eq $true }
            # 客户端只证明「两页都打开了、被拒按钮没执行动作」。格子内容与
            # 窗口开关状态它观测不到（1.21.1 物品组件解析失败、stateId 失步），
            # 那部分改由服务端日志判定，强度不降反升。
            $allPassed = $allPassed -and
                ($logText -match 'menu-render id=parity-page-1 .*10=REDSTONE') -and
                ($logText -match 'menu-render id=parity-page-2 .*13=EMERALD') -and
                ($logText -match 'menu-click id=parity-page-1 .*slot=10 permitted=false ran=false')
            if ($allPassed) { $menuStatus = 'passed' }
        } catch {
            $menuObservation = "invalid client report: $($_.Exception.Message)"
        }
    }
}
$probes += [ordered]@{
    id = 'custom-menu-player-workflow'
    status = $menuStatus
    observations = @($menuObservation)
}

$audienceClientLog = Join-Path $server 'audience-client.stdout.log'
$audienceStatus = 'not-observed'
$audienceObservation = 'client report missing'
$filterStatus = 'not-observed'
$filterObservation = 'audience filter report missing'
if (Test-Path -LiteralPath $audienceClientLog -PathType Leaf) {
    $audienceLine = Get-Content -LiteralPath $audienceClientLog -Encoding utf8 |
        Where-Object { $_ -match '^PAPER_AUDIENCE_REPORT ' } | Select-Object -Last 1
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
    $filterLine = Get-Content -LiteralPath $audienceClientLog -Encoding utf8 |
        Where-Object { $_ -match '^PAPER_AUDIENCE_FILTER_REPORT ' } | Select-Object -Last 1
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
}
$probes += [ordered]@{
    id = 'audience-player-delivery'
    status = $audienceStatus
    observations = @($audienceObservation)
}
$probes += [ordered]@{
    id = 'audience-filter-delivery'
    status = $filterStatus
    observations = @($filterObservation)
}

# The raw potion client is opt-in. Register its probe only when the log proves
# the event, so ordinary release catalogs do not acquire a permanent missing ID.
$playerPotionConsumeRequested = $env:PAPER_RAW_POTION_WORKFLOW -eq 'true'
$playerPotionConsumeObserved = $logText -match 'player-potion-consume event=true.*item=POTION(?:\s|$)'
if ($playerPotionConsumeRequested) {
    $playerPotionConsumeObservationList = [System.Collections.Generic.List[string]]::new()
    if ($playerPotionConsumeObserved) {
        foreach ($match in [regex]::Matches($logText, '.*player-potion-consume event=true.*item=POTION.*')) {
            $playerPotionConsumeObservationList.Add($match.Value.Trim())
        }
    } else {
        $playerPotionConsumeObservationList.Add(
            'raw player potion workflow was requested but no consume event was observed')
    }
    $playerPotionConsumeObservations = $playerPotionConsumeObservationList.ToArray()
    $probes += [ordered]@{
        id = 'playerPotionConsume'
        status = if ($playerPotionConsumeObserved) { 'passed' } else { 'not-observed' }
        observations = $playerPotionConsumeObservations
    }
}
$playerPotionNaturalExpiryObserved = $logText -match 'player-potion-natural-expiry=true'
if ($playerPotionNaturalExpiryObserved) {
    $playerPotionNaturalExpiryObservations = [string[]]@(
        [regex]::Matches($logText, '.*player-potion-natural-expiry=true.*') |
            ForEach-Object { $_.Value.Trim() })
    $probes += [ordered]@{
        id = 'playerPotionNaturalExpiry'
        status = 'passed'
        observations = $playerPotionNaturalExpiryObservations
    }
}

$spawnLines = [regex]::Matches($logText, 'spawner(?:-condition|-probe)? id=.+')
$spawnObservations = @{}
$spawnStatuses = @{}
foreach ($match in $spawnLines) {
    $line = $match.Value
    $spawnId = if ($line -match 'spawner(?:-condition|-probe)? id=([^ ]+)') { $Matches[1] } else { 'unknown' }
    if (-not $spawnObservations.ContainsKey($spawnId)) {
        $spawnObservations[$spawnId] = @()
        $spawnStatuses[$spawnId] = 'observed-rejected'
    }
    $spawnObservations[$spawnId] = @($spawnObservations[$spawnId]) + $line
    if ($line -match 'requested=true') { $spawnStatuses[$spawnId] = 'passed' }
}
foreach ($spawnId in $spawnObservations.Keys) {
    $probes += [ordered]@{
        id = "spawner:$spawnId"
        status = $spawnStatuses[$spawnId]
        observations = @($spawnObservations[$spawnId])
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
if ($randomMatches.Count -eq 0) {
    $probes += [ordered]@{
        id = 'random-spawn-distribution'
        status = 'not-observed'
        sampleCount = 0
        expected = 'missing'
        observed = 'missing'
        deviation = 'missing'
        maxDeviation = 0.0
        tolerance = $RandomSpawnTolerance
        observations = @('random-spawn-distribution evidence missing')
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
} else {
    $probes += [ordered]@{
        id = 'skillTeleportCoordinates'
        status = 'not-observed'
        observations = @('teleport coordinate evidence missing')
    }
}

$report = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    fixtureDirectory = Split-Path -Leaf $server
    evidenceMode = $EvidenceMode
    paperVersion = if ($paperMatch.Success) { $paperMatch.Groups[1].Value } else { $null }
    paperApiVersion = if ($paperMatch.Success) { $paperMatch.Groups[2].Value } else { $null }
    pluginEnabled = $logText.Contains('Enabling helsteraMobs v1.0.0') -and $logText.Contains('Done (')
    log = 'logs/latest.log'
    expectedProbeIds = @($probeSpecs.Keys) + @(
        'custom-menu-player-workflow', 'audience-player-delivery',
        'audience-filter-delivery', 'random-spawn-distribution',
        'skillTeleportCoordinates') + $(if ($playerPotionConsumeRequested) {
            @('playerPotionConsume')
        } else { @() }) + $(if ($playerPotionNaturalExpiryObserved) {
            @('playerPotionNaturalExpiry')
        } else { @() }) + @(
        $spawnObservations.Keys | ForEach-Object { "spawner:$($_)" })
    probes = @($probes)
}
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Host "Paper integration report: $reportPath"
