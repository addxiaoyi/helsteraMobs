param(
    [Parameter(Mandatory = $true)]
    [string]$ServerDirectory,
    [ValidateSet('release', 'exploratory')]
    [string]$EvidenceMode = 'release',
    [double]$RandomSpawnTolerance = 0.15,
    [DateTime]$StartedAtUtc = [DateTime]::MinValue,
    [string]$PluginSha256 = ''
)

$ErrorActionPreference = 'Stop'
if ($PluginSha256 -and $PluginSha256 -cnotmatch '^[0-9a-f]{64}$') {
    throw 'PluginSha256 must be a lowercase SHA-256 digest.'
}
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
    armorToughnessStatsImported = 'armor-toughness-stats-imported result=true imported=true base=4(?:\.0+)? attack-damage=12(?:\.0+)? movement-speed=0\.4(?:0*) armor=8(?:\.0+)? knockback-resistance=0\.25(?:0*) follow-range=36(?:\.0+)? unsupported-preserved=true modifier-preserved=true root-preserved=true controls=0(?:\.0+)?,0(?:\.0+)?'
    entityConfigFlags = 'entity-config-flags result=true no-ai=true invulnerable=true silent=true gravity=false collidable=false'
    tamedFamilyOptions = 'tamed-family-options result=true imported=true donkey-tamed=true control-donkey-tamed=false mule-tamed=true control-mule-tamed=false llama-tamed=true control-llama-tamed=false trader-llama-tamed=true control-trader-llama-tamed=false skeleton-horse-tamed=true control-skeleton-horse-tamed=false zombie-horse-tamed=true control-zombie-horse-tamed=false camel-tamed=true control-camel-tamed=true camel-false-preserved=true'
    expOptions = 'exp-options result=true experience=13 event=true'
    expNaturalDeath = 'exp-natural-death result=true dead=true experience=13 cause=BukkitDamage'
    expTierDeath = 'exp-tier-death result=true low=2 qualified=7 low-dead=true qualified-dead=true low-bread=4 qualified-bread=4'
    reviveNatural = 'revive-natural result=true alive=true attached=true health=8(?:\.0+)?(?:\s|$)'
    reviveLate = 'revive-late result=true cancelled=true alive=true attached=true health=8(?:\.0+)? no-drops=true'
    damageImported = 'damage-imported mapped=true entity-damage=true attacker-name=true attacker-equipment=true environmental-damage=true environmental-no-trigger=true passed=true'
    damageAttribution = 'damage-attribution player-present=true player-attack=true target-is-player=true environmental-no-trigger=true restored=true passed=true'
    immunityImported = 'immunity-imported result=true reduced=true condition-rejected=true invalid-rejected=true'
    immunityNativeInvalid = 'immunity-native-invalid result=true false-rejected=true null-rejected=true unknown-rejected=true null-modifier-rejected=true'
    immunityInvalidFields = 'immunity-invalid-fields null-immunity=true null-modifier-field=true unknown-modifier-field=true invalid-immune=true null-immune=true'
    immunityPreservation = 'immunity-preservation nested-preserved=true nested-closed=true list-null-preserved=true list-null-closed=true'
    entityAngryPollination = 'entity-angry-pollination result=true bee-angry-positive=true bee-angry-negative=true wolf-angry-positive=true wolf-angry-negative=true vex-angry-positive=true vex-angry-negative=true bee-pollinated-positive=true bee-pollinated-negative=true unsupported-angry-rejected=true missing-context-rejected=true aliases=true'
    entityShearedStates = 'entity-sheared-states result=true sheep-self-positive=true sheep-target-positive=true sheep-self-negative=true sheep-target-negative=true bogged-self-positive=true bogged-target-positive=true bogged-self-negative=true bogged-target-negative=true snow-golem-self-positive=true snow-golem-target-positive=true snow-golem-self-negative=true snow-golem-target-negative=true unsupported-rejected=true missing-context-rejected=true aliases=true'
    conditionArguments = 'condition-arguments living-rejected=true baby-valid=true baby-rejected=true frozen-valid=true frozen-rejected=true warnings=true'
    conditionStateArguments = 'condition-state-arguments valid=true movement-rejected=true category-rejected=true alive-rejected=true negated-rejected=true preserved=true warnings=true'
    targetSneakingMob = 'target-sneaking-mob false-rejected=true true-passed=true'
    timeWeatherArguments = 'time-weather-arguments time-valid=true time-opposite=true weather-valid=true weather-opposite=true time-rejected=true rain-rejected=true negated-rejected=true preserved=true warnings=true'
    snowingCondition = 'snowing-condition result=true target-location-isolated=true target-positive=true alias-positive=true warm-rejected=true clear-rejected=true missing-context-rejected=true invalid-arguments-rejected=true restored=true'
    visibilityStateArguments = 'visibility-state-arguments valid=true rejected=true preserved=true warnings=true'
    dropNullConditions = 'drop-null-conditions rejected=true warnings=true sibling=true'
    dropNativeNullConditions = 'drop-native-null-conditions rejected=true sibling=true warnings=true'
    dropNativeNullNumeric = 'drop-native-null-numeric rejected=true fallback=true overrides=true warnings=true'
    dropNativeDynamic = 'drop-native-dynamic rejected=true warnings=true'
    dropNativeTier = 'drop-native-tier rejected=true warnings=true'
    dropNativeTierRange = 'drop-native-tier-range valid=true fallback=true warnings=true'
    dropUnsafeReferences = 'drop-unsafe-references warnings=true stale-rejected=true'
    dropMergedNull = 'drop-merged-null rejected=true warnings=true stale-rejected=true'
    dropChanceImported = 'drop-chance-imported rejected=true warnings=true'
    despawnOptions = 'despawn-options result=true mode=PERSISTENT distance=0(?:\.0+)? persistent=true'
    spawningDistanceOptions = 'spawning-distance-options result=true mode=NORMAL distance=8(?:\.0+)?'
    spawningDistanceRuntime = 'spawning-distance-runtime result=true inside-retained=true outside-removed=true'
    spawningDistanceClientWalk = 'spawning-distance-client-walk result=true player-displacement=(?:8|9|[1-9][0-9]+)(?:\.[0-9]+)? player-entity-distance=[0-9]+(?:\.[0-9]+)? instance-removed=true entity-removed=true despawn-elapsed-ticks=[0-9]+ decision-period-ticks=[0-9]+'
    visibilityOptions = 'visibility-options-imported result=true imported=true configured=true controls=true'
    preventTeleportImported = 'prevent-teleport-imported result=true imported=true mapped=true invalid-preserved=true spawned=true protected-api-blocked=true control-api-teleported=true'
    sheep = 'sheep-options result=true imported=true color=RED sheared=true control-color=WHITE control-sheared=false'
    horseOptionsImported = 'horse-options result=true imported=true color=GRAY style=WHITE_DOTS domestication=5 max-domestication=20 jump-strength=0\.8 saddled=true armor=DIAMOND_HORSE_ARMOR control-color=BLACK control-style=BLACK_DOTS control-domestication=1 control-max-domestication=12 control-jump-strength=0\.4 control-saddled=false control-armor=AIR donkey-chest=true control-donkey-chest=false pig-saddled=true control-pig-saddled=false strider-saddled=true control-strider-saddled=false'
    interactionOptionsImported = 'interaction-options-imported result=true imported=true configured-height=2\.5 configured-width=3\.25 responsive=false height-only-width=1\.75 defaults-applied=true wrong-type-preserved=true invalid-preserved=true'
    goatOptionsImported = 'goat-options result=true imported=true screaming=true control-screaming=false default-screaming=false wrong-type-preserved=true invalid-preserved=true'
    frogOptionsImported = 'frog-options result=true imported=true type=COLD alias-type=WARM control-type=TEMPERATE wrong-type-preserved=true invalid-preserved=true conflict-preserved=true'
    tameableOptionsImported = 'tameable-options result=true imported=true allow-cancelled=false deny-cancelled=true default-cancelled=true invalid-cancelled=true unmanaged-cancelled=false wrong-type-preserved=true invalid-preserved=true global-default-ignored=true'
    foxOptionsImported = 'fox-options result=true imported=true type=SNOW control-type=RED wrong-type-preserved=true invalid-preserved=true'
    rabbitOptionsImported = 'rabbit-options result=true imported=true type=SALT_AND_PEPPER control-type=BROWN killer-type=THE_KILLER_BUNNY angry-type=THE_KILLER_BUNNY false-killer-type=SALT_AND_PEPPER wrong-type-preserved=true invalid-preserved=true conflict-preserved=true'
    armorStand = 'armor-stand-options-imported result=true imported=true configured=true controls=true item-body=true item-feet=true item-hand=true item-offhand=true item-head=true item-legs=true wrong-type-preserved=true invalid-preserved=true'
    cat = 'cat-options result=true imported=true type=SIAMESE sitting=true collar=BLUE control-type=TABBY control-sitting=false control-collar=RED'
    fallingBlock = 'falling-block-options result=true'
    fallingBlockOptionsImported = 'falling-block-options-imported result=true imported=true material=OBSIDIAN control-material=SAND drop-item=false hurt-entities=true cancel-drop=true wrong-type-preserved=true invalid-preserved=true'
    boatOptionsImported = 'boat-options-imported result=true imported=true boat-type=SPRUCE control-type=OAK chest-type=BAMBOO wrong-type-preserved=true invalid-preserved=true conflict-preserved=true'
    creeper = 'creeper-options result=true imported=true explosion-radius=5 fuse-ticks=60 super-charged=true prevent-suicide=true protected-cancelled=true control-allowed=true'
    bee = 'bee-options result=true'
    beeOptionsImported = 'bee-options-imported result=true imported=true nectar=true stung=false control-nectar=false control-stung=true'
    beeAngerImported = 'bee-anger-imported result=true imported=true configured-anger=1200 control-anger=0'
    beeStingerLossImported = 'bee-stinger-loss-imported result=true imported=true target-entity=ZOMBIE target-ai=false target-invulnerable=false target-resistance-amplifier=3 protected-option=true control-option=false protected-attacks=[1-9][0-9]* control-attacks=[1-9][0-9]* protected-events=[1-9][0-9]* control-events=[1-9][0-9]* protected-cancelled=0 control-cancelled=0 protected-restored=true control-retained=true protected-near=true control-near=true trigger=entity-target-api event=native-ai'
    healOnReloadOptions = 'heal-on-reload-options result=true mapped=true reload=true protected-option=true control-option=false protected-before=15(?:\.0+)? protected-after=20(?:\.0+)? control-before=15(?:\.0+)? control-after=15(?:\.0+)?'
    entitySizeRange = 'entity-size-range result=true samples=64 configured=2\.\.5 slime=[2-5]\.\.[2-5] magma-cube=[2-5]\.\.[2-5] phantom=[2-5]\.\.[2-5] imported=true'
    reinforcementsChanceOptions = 'reinforcements-chance-options result=true imported=true applied=true base=0\.38 zero-mapped=true upper-bound-mapped=true wrong-type-preserved=true invalid-preserved=true wrong-entity-rejected=true invalid-runtime-rejected=true'
    ageOptions = 'age-options result=true age=-24000 baby=true adult=true age-lock=true zombie-baby=true'
    entityFlagOptions = 'entity-flag-options result=true imported=true wolf-tamed=true cat-tamed=true control-cat-tamed=false horse-tamed=true control-horse-tamed=false parrot-tamed=true control-parrot-tamed=false wolf-angry=true pig-zombie-angry=true can-join-raid-setter=true can-join-raid=false control-can-join-raid=true patrol-leader=true patrol-spawn-point=true wrong-type-preserved=true'
    booleanMobOptionsImported = 'boolean-mob-options-imported result=true imported=true configured=true controls=true invalid-preserved=true entity-states=true'
    preventRandomEquipmentImported = 'prevent-random-equipment-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true random-cleared=true explicit-equipment-preserved=true control-preserved=true invalid-fallback=true'
    environmentalDamageOptionsImported = 'environmental-damage-options-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true drowning-protected=true fall-protected=true controls-allowed=true invalid-fallback=true'
    glowingOptionImported = 'glowing-option-imported result=true imported=true enabled-configured=true control-configured=true invalid-preserved=true enabled-entity=true control-entity=true invalid-entity=true'
    experienceOrbOptions = 'experience-orb-options result=true imported=true experience=17 wrong-type-preserved=true invalid-preserved=true'
    pandaOptions = 'panda-options result=true imported=true main-gene=LAZY hidden-gene=WORRIED control-main-gene=NORMAL control-hidden-gene=NORMAL wrong-type-preserved=true invalid-preserved=true'
    villagerOptions = 'villager-options result=true imported=true type=DESERT profession=MASON level=4 has-trades=true trades-available=true control-type=PLAINS control-profession=LIBRARIAN control-level=2 control-has-trades=false no-trades=true acquire-allowed=true acquire-denied=true trading-interactable=true wrong-type-preserved=true invalid-preserved=true'
    parrotFlyingSpeed = 'parrot-flying-speed result=true imported=true configured=0\.8 sentinel-unchanged=true wrong-type-preserved=true invalid-preserved=true'
    passthroughDamageOptions = 'passthrough-damage result=true imported=true parent-linked=true transferred=true source-preserved=true disabled-control=true orphan-fallback=true cancelled-respected=true cycle-safe=true invalid-preserved=true'
    showHealthDelivery = 'show-health-delivery result=true imported=true spawned=true first-crossing=true second-crossing=true healing-recross=true below-threshold-quiet=true inside-band-quiet=true near-inside=true outsider-outside=true .*environmental-damage=true entity-damage=true'
    endermanHeldBlock = 'enderman-held-block result=true material=OBSIDIAN'
    mobPickupOption = 'mob-pickup-option result=true disabled=true explicit-overrides-default=true'
    spawnOptionRuntime = 'spawn-option-runtime result=true pickup-disabled=true explicit-pickup-overrides-fallback=true random-equipment-cleared=true explicit-equipment-preserved=true no-damage-ticks=true'
    importedMobDefaults = 'imported-mob-defaults result=true imported=true builtin-pickup-disabled=true builtin-no-damage-ticks=true builtin-despawn-normal=true builtin-prevent-leashing=true builtin-prevent-transformation=true global-pickup-disabled=true global-no-damage-ticks=true global-prevent-leashing=true global-default-prevent-transformation=false global-default-ignored=true global-prevent-transformation=true global-despawn-chunk=true global-prevent-other-drops=true global-prevent-vanilla-damage=true global-unsupported-preserved=true explicit-pickup-enabled=true explicit-pickup-disabled=true pickup-wrong-type-preserved=true pickup-invalid-preserved=true explicit-jockey-prevented=true global-jockey-allowed=true explicit-no-damage-ticks=true explicit-prevent-leashing=true explicit-prevent-transformation-disabled=true explicit-despawn-never=true explicit-prevent-other-drops-disabled=true explicit-prevent-vanilla-damage-disabled=true builtin-transform-cancelled=true global-transform-cancelled=true explicit-transform-allowed=true'
    playerCreatedImported = 'player-created-imported result=true imported=true created=true control-created=false wrong-entity-preserved=true invalid-preserved=true'
    noGravityImported = 'no-gravity-imported result=true imported=true configured=false control=true has-gravity=false has-gravity-control=true has-gravity-wrong-type-preserved=true invalid-preserved=true gravity-invalid-preserved=true has-gravity-invalid-preserved=true fallback=true'
    attributes = 'attribute-options result=true modifiers=1'
    attributesComposite = '(?<!item-)attribute-options-composite result=true attack=true speed=true armor=true dedup=true invalid-rejected=true'
    itemAttributes = 'item-attribute-options result=true modifiers=1'
    itemAttributesComposite = 'item-attribute-options-composite result=true attack=true speed=true armor=true dedup=true invalid-rejected=true modifiers=3'
    itemMetadata = 'item-metadata imported=true display-name=true item-name=true lore=true enchantment=true custom-model-data=true flags=true(?: flag-values=\[[^]]*\])? restrictions=true potion=true skull=true invalid-preserved=true result=true'
    legacyNumericItems = 'legacy-numeric-items imported=true items-material=true items-durability=true items-potion=true items-block-data=true items-amount=true legacy-id-boundaries=true equipment-material=true equipment-durability=true equipment-potion=true equipment-block-data=true equipment-amount=true invalid-preserved=true result=true'
    skillEquipment = 'skill-actions equipment=true'
    playerExperienceMechanics = 'skill-actions .*player-experience-mechanics=true'
    oxygenMechanic = 'skill-actions .*oxygen-mechanic=true'
    consumeSlotMechanic = 'skill-actions .*consume-slot-mechanic=true'
    rawExperienceMechanic = 'skill-actions .*raw-experience-mechanic=true'
    skillPotion = 'skill-actions .*potion=true'
    skillDropItem = 'skill-actions .*drop-item=true'
    skillTeleport = 'skill-actions .*teleport=true'
    mythicTeleport = 'mythic-teleport imported=true entity-target=true location-target=true safe-blocked=true unsafe-bypass=true spread-bounded=true yaw-preserved=true orientation-override=true invalid-preserved=true warnings=true restored=true passed=true'
    skillScheduling = 'skill-scheduler result=true delay=2 repeat=3 interval=2 executions=4 pre-delay-count=0'
    skillTreeBranches = 'skill-tree-branches result=true condition-denied=true condition-unlock=true prerequisite-blocked=true prerequisite-unlock=true'
    skillTreeCastPersistence = 'skill-tree-cast-persistence result=true blocked-before-unlock=true prerequisite-unlock-blocked=true snapshot-saved=true advanced-unlocked=true casts=1'
    skillTreeQueueSnapshot = 'skill-tree-queue submitted=true restored=true'
    skillLifecycleCleanup = 'skill-lifecycle reload=true scheduled=true cancelled=true'
    insideOutsideCondition = 'inside-outside-condition-details inside-positive=true inside-negative=true outside-positive=true outside-negative=true restored=true'
    isInCinematicCondition = 'is-in-cinematic-condition player-open=true player-out-of-range-rejected=true non-player-rejected=true closed=true'
    aerialCondition = 'aerial-condition-details positive=true alias-positive=true negative=true ground-entity=player ground=true'
    importedAerialCondition = 'imported-aerial-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true ground=true'
    endermanCondition = 'enderman-condition-details positive=true negative=true'
    importedColorCondition = 'imported-color-condition red-mapped=true blue-alias-mapped=true positive=true negative=true entity-color=RED'
    importedHasEnchantmentCondition = 'imported-has-enchantment-condition primary-mapped=true alias-mapped=true positive=true any-positive=true level-rejected=true type-rejected=true restored=true'
    importedTriggerItemTypeCondition = 'imported-trigger-item-type-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true constructed-context=true'
    importedTriggerBlockTypeCondition = 'imported-trigger-block-type-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true state-rejected=true constructed-context=true'
    importedOriginLocationCondition = 'imported-origin-location-condition primary-mapped=true alias-mapped=true positive=true exact-positive=true negative=true invalid-rejected=true constructed-context=true'
    importedMaterialCooldownCondition = 'imported-material-cooldown-condition primary-mapped=true alias-mapped=true positive=true wrong-material-rejected=true'
    importedItemRechargingCondition = 'imported-item-recharging-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true non-human-rejected=true'
    importedIsFrozenCondition = 'imported-frozen-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true thawed=true alias-thawed=true invalid-rejected=true'
    templateTypeImported = 'template-type-imported result=true imported=true primary-mapped=true alias-mapped=true direct-positive=true transitive-positive=true unrelated-rejected=true control-rejected=true field-inheritance=true exclude-inherited-removed=true exclude-own-values=true missing-config-rejected=true unmanaged-rejected=true invalid-preserved=true passed=true'
    importedBurningCondition = 'imported-burning-condition actions=true target-priority=true target-positive=true target-negative=true composed-negation=true caster-fallback=true invalid-preserved=true'
    importedEntityCategoryCondition = 'imported-entity-category-condition water-primary-mapped=true water-alias-mapped=true water-positive=true water-zombie-rejected=true zombie-primary-mapped=true zombie-alias-mapped=true zombie-positive=true zombie-cod-rejected=true invalid-preserved=true cod=COD zombie=ZOMBIE'
    importedExecutionAudienceCondition = 'imported-execution-audience-condition primary-mapped=true alias-mapped=true contains-mapped=true no-audience-player-allowed=true member-allowed=true outsider-rejected=true empty-rejected=true non-player-rejected=true invalid-preserved=true per-target-unattached=true per-target-explicit=true result=true'
    executionAudienceMutation = 'execution-audience-mutation imported=true explicit-empty=true added=true persisted=true member-visible=true outsider-hidden=true removed=true empty-persisted=true unset=true unrestricted=true passed=true'
    executionAudienceMobSpawn = 'execution-audience-mob-spawn imported=true mapped=true spawned=true selected-member=true outsider-excluded=true persisted=true member-visible=true outsider-hidden=true passed=true'
    executionAudienceMobMutation = 'execution-audience-mob-mutation imported=true targeter-removed=true empty-persisted=true hidden=true targeter-added=true restored-persisted=true member-visible=true outsider-hidden=true passed=true'
    executionAudienceMobSummon = 'execution-audience-mob-summon imported=true action-bound=true spawned=true inherited=true parent-linked=true visuals=true member-visible=true outsider-hidden=true passed=true'
    importedTargetRelationshipsCondition = 'imported-target-relationships-condition has-owner-mapped=true owner-player-mapped=true owner-online-mapped=true leashed-mapped=true leashed-alias-mapped=true owned-positive=true owner-player-positive=true owner-online-positive=true leashed-positive=true leashed-alias-positive=true leash-cleared-rejected=true owner-cleared-rejected=true missing-target-rejected=true result=true'
    importedEntityTypeVariantCondition = 'imported-entity-type-variant-condition primary-mapped=true alias-mapped=true mobtype-mapped=true positive=true alias-positive=true mobtype-positive=true variant-alternative=true wrong-variant-rejected=true wrong-type-rejected=true invalid-preserved=true cat=CAT variant=TABBY'
    importedEntityTypeTraitsCondition = 'imported-entity-type-traits mapped=true horse=true wrong-style-rejected=true panda=true villager=true zombie-villager=true rainbow-sheep=true rainbow-sheep-white-rejected=true shulker=true parrot=true llama=true tropical-fish=true iron-golem-crack=true inapplicable-trait-rejected=true'
    importedSpyglassCondition = 'imported-spyglass-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true inactive-rejected=true non-player-rejected=true invalid-rejected=true'
    importedSurvivalModeCondition = 'imported-survival-mode-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true alias-negative=true non-player-rejected=true invalid-rejected=true'
    importedFreeInventorySlotCondition = 'imported-free-inventory-slot primary-mapped=true alias-mapped=true positive=true negative=true invalid-preserved=true result=true'
    importedNameCondition = 'imported-name-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true default-name-rejected=true fallback=true custom-name-rejected=true invalid-preserved=true result=true'
    importedServerConditions = 'imported-server-conditions paper-primary=true paper-alias=true exact=true exact-negative=true after-inclusive=true after-exclusive-rejected=true after-higher-rejected=true before-inclusive=true before-exclusive-rejected=true invalid-preserved=true result=true'
    importedCoordinateDifferenceCondition = 'imported-coordinate-difference-condition primary-mapped=true alias-mapped=true x-positive=true y-positive=true z-positive=true out-of-range-rejected=true invalid-preserved=true result=true'
    importedPopulationConditions = 'imported-population-conditions players-online-mapped=true online-alias-mapped=true players-world-mapped=true mobs-world-mapped=true players-online-negative-mapped=true online-alias-negative-mapped=true players-world-negative-mapped=true mobs-world-negative-mapped=true players-online-positive=true online-alias-positive=true players-world-positive=true mobs-world-positive=true players-online-negative=true online-alias-negative=true players-world-negative=true mobs-world-negative=true invalid-preserved=true result=true'
    localDifficultyCondition = 'local-difficulty-condition imported=true positive=true range-positive=true high-rejected=true fallback-positive=true missing-context-rejected=true invalid-preserved=true passed=true'
    serverNmsVersionCondition = 'server-nms-version-condition imported=true positive=true alias-positive=true scalar-positive=true wrong-rejected=true default-rejected=true invalid-preserved=true passed=true'
    spawnReasonCondition = 'spawn-reason-condition-details actual=\S+ primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true invalid-preserved=true'
    importedVariableConditions = 'imported-variable-conditions result=true primary-mapped=true aliases=true scopes=true ranges=true missing-rejected=true wrong-type-rejected=true quoted-value=true dynamic-placeholder=true invalid-preserved=true'
    conditionCatalog = 'skill-actions (?=.*conditions=true)(?=.*drowning-condition=true)(?=.*owner-condition=true)(?=.*children-condition=true)(?=.*inside-outside-conditions=true)(?=.*distance-from-location-condition=true)(?=.*coordinate-difference-condition=true)(?=.*origin-location-condition=true)(?=.*line-of-sight-from-origin-condition=true)(?=.*bounds-condition=true)(?=.*enchanting-conditions=true)(?=.*plugin-condition=true)(?=.*field-of-view-condition=true)(?=.*spawn-reason-condition=true)(?=.*water-mob-condition=true)(?=.*zombie-condition=true)(?=.*entity-type-condition=true)(?=.*aerial-condition=true)(?=.*enderman-condition=true)(?=.*enchantment-condition=true)(?=.*color-condition=true)(?=.*material-cooldown-condition=true)(?=.*trigger-item-type-condition=true)(?=.*entity-item-type-condition=true)(?=.*entity-item-similar-condition=true)(?=.*item-similar-condition=true)(?=.*targets-condition=true)(?=.*item-type-condition=true)(?=.*mythic-mob-type-condition=true)(?=.*trigger-block-type-condition=true)(?=.*frozen-condition=true)(?=.*dialogue-condition=true)(?=.*player-kills-condition=true)(?=.*absorption-condition=true)(?=.*health-absorption-condition=true)(?=.*potion-effect-condition=true)(?=.*aura-stacks-condition=true)(?=.*level-condition=true)(?=.*lunar-phase-condition=true)(?=.*string-equals-condition=true)(?=.*string-empty-condition=true)(?=.*compare-values-condition=true)(?=.*is-skill-condition=true)(?=.*global-score-condition=true)(?=.*score-condition=true)'
    enderDragonAliveCondition = 'ender-dragon-alive-condition positive=true negative=true removed=true'
    absorptionCondition = 'skill-actions .*absorption-condition=true'
    absorptionPlayerCondition = 'absorption-player-condition imported=true dispatched=true positive=true exact=true negative=true observed=true restored=true passed=true'
    healthAbsorptionCondition = 'skill-actions .*health-absorption-condition=true'
    healthImportedCondition = 'health-imported result=true comparisons=true duplicate-rejected=true overflow-preserved=true invalid-preserved=true'
    healthRangeImportedCondition = 'health-range-imported result=true ranges=true invalid-preserved=true'
    comparisonBoundaryImportedCondition = 'comparison-boundary-imported result=true boundaries=true invalid-preserved=true'
    targetHealthImportedCondition = 'target-health-imported result=true comparisons=true invalid-preserved=true'
    environmentImportedCondition = 'environment-imported result=true valid=true invalid-rejected=true'
    potionEffectCondition = 'skill-actions .*potion-effect-condition=true'
    auraStacksCondition = 'skill-actions .*aura-stacks-condition=true'
    mythicAuraRuntime = 'skill-actions .*mythic-aura-action=true .*mythic-has-aura-condition=true .*mythic-target-has-aura-condition=true .*mythic-has-aura-stacks-condition=true .*mythic-has-aura-type-condition=true .*mythic-aura-remove=true .*mythic-aura-invalid-rejected=true'
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
    lineOfSightCondition = 'line-of-sight-condition result=true imported=true aliases=true direct-positive=true direct-blocked=true target-positive=true target-blocked=true target-negative=true restored-positive=true restored=true invalid-preserved=true warning=true'
    lineOfSightFromOriginCondition = 'skill-actions .*line-of-sight-from-origin-condition=true'
    originLocationCondition = 'skill-actions .*origin-location-condition=true'
    triggerBlockTypeCondition = 'skill-actions .*trigger-block-type-condition=true'
    worldBiome = 'skill-actions .*world-biome=true'
    worldBiomeReject = 'skill-actions .*world-biome-reject=true'
    targetCategories = 'skill-actions .*target-categories=true'
    raiderPatrolLeaderCondition = 'raider-patrol-leader-condition positive=true negative=true entity=PILLAGER restored=true'
    creeperPrimedCondition = 'creeper-primed-condition positive=true negative=true restored=true'
    enderDragonPhaseCondition = 'ender-dragon-phase-condition positive=true list=true negative=true invalid-rejected=true restored=true'
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
    inCombatCondition = 'in-combat-condition managed-path=bukkit-mob-target managed-positive=true managed-no-target-rejected=true managed-restored=true native-positive=true native-no-target-rejected=true native-restored=true'
    conditionUtilityAliases = 'skill-actions .*condition-utility=true .*in-combat-condition=true .*block-type-radius-condition=true .*biome-condition=true'
    survivalModeCondition = 'survival-mode-condition positive=true negative=true restored=true'
    distanceFromSpawnCondition = 'condition-utility-details .*spawn-distance-exact=true .*spawn-distance-over-rejected=true'
    mobsInChunkCondition = 'condition-utility-details .*mobs-chunk=true .*mobs-chunk-high-rejected=true .*mobs-chunk-zero=true .*mobs-chunk-occupied=true .*mobs-chunk-adjacent-excluded=true .*mobs-chunk-boundaries=true .*mobs-in-chunk-condition=true'
    chanceCondition = 'condition-utility-details .*chance-100=true .*chance-0-rejected=true .*chance-invalid-rejected=true'
    biomeCondition = 'spatial-condition-details .*biome-exact=true .*biome-partial=true .*biome-reject=true .*biome-type-category=true'
    biomeImported = 'biome-imported partial=true exact-rejected=true invalid-preserved=true'
    invalidConditionsImported = 'invalid-imported-conditions valid-skill=true invalid-skill=true invalid-damage=true warnings=true'
    playerRadiusConditions = 'skill-actions .*player-radius-conditions=true'
    radiusConditions = 'radius-condition-details imported=true players-positive=true players-negative=true entities-positive=true entities-negative=true mobs-positive=true mobs-negative=true living-positive=true living-negative=true player-within-positive=true player-within-negative=true player-not-within-positive=true player-not-within-negative=true restored=true passed=true'
    cooldownMechanics = 'skill-actions .*cooldown-mechanics=true'
    lastDamageCauseCondition = 'skill-actions .*last-damage-cause=true'
    motionConditions = 'skill-actions .*motion-conditions=true .*directional-velocity-condition=true .*fall-speed-condition=true'
    nameCondition = 'skill-actions .*name-condition=true'
    dotMechanic = 'skill-dot damage-observed=true bounded=true expired=true'
    projectileHasEndedCondition = 'projectile-has-ended=true observed=true'
    targetEnvironmentalConditions = 'skill-actions .*target-environment=true'
    targetFluidConditions = 'target-fluids-delayed imported=true water=true lava=true invalid-preserved=true restored=true passed=true'
    targetBlockedCondition = 'skill-actions .*target-blocked=true'
    targetStateConditions = 'skill-actions .*target-state-conditions=true'
    conditionComposition = 'skill-actions .*condition-composition=true'
    conditionMobility = 'skill-actions .*condition-mobility=true .*moving-default-stationary=true .*moving-exact-fractional=true .*moving-alias-fractional=true .*moving-approx-same-block=true .*moving-approx-block=true .*moving-exact-argument=true .*moving-e-argument=true .*moving-invalid-rejected=true .*moving-imported=true .*moving-restored=true'
    conditionMobilityStates = 'condition-mobility-states result=true falling-positive=true falling-negative=true climbing-positive=true climbing-negative=true charged-positive=true charged-negative=true restored=true aliases=true'
    groundedCondition = 'grounded-condition imported=true primary-mapped=true grounded-alias-mapped=true positive=true alias-positive=true airborne=true negative=true alias-negative=true invalid-preserved=true restored=true passed=true'
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
    importedIsChildCondition = 'skill-actions .*is-child-import-condition=true adult-parent-positive=true adult-not-baby=true unrelated-baby-not-child=true baby-positive=true'
    parentDerivedConditions = 'skill-actions .*parent-derived-conditions=true'
    timeWeather = 'skill-actions .*time-weather=true'
    customItemResolver = 'skill-actions .*custom-item=true'
    giveItemAction = 'skill-actions .*give-item=true'
    takeItemAction = 'skill-actions .*take-item=true'
    dynamicPlaceholderAction = 'skill-actions .*dynamic-placeholder=true'
    gameplayPlaceholders = 'attack-cooldown-placeholder result=true in-range=true recharging=true caster=\S+ target=\S+ aliases=true non-player-unresolved=true'
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
    lookingAtCondition = 'imported-looking-at-condition primary-mapped=true alias-mapped=true entity-mapped=true positive=true alias-positive=true wrong-block-rejected=true short-block-distance-rejected=true negative=true entity-positive=true wrong-entity-rejected=true short-entity-distance-rejected=true invalid-rejected=true restored=true'
    itemRechargingCondition = 'skill-actions .*item-recharging-condition=true'
    importedItemSimilarityConditions = 'imported-item-similarity-conditions entity-primary-mapped=true entity-alias-mapped=true entity-positive=true entity-alias-positive=true entity-negative=true entity-invalid-preserved=true inventory-primary-mapped=true inventory-alias-mapped=true inventory-positive=true inventory-alias-positive=true inventory-negative=true inventory-invalid-preserved=true result=true'
    playerPotionEvents = 'skill-actions .*player-potion-events=true'
    stateConditions = 'skill-actions .*state-conditions=true .*state-flying=true .*state-sneaking=true .*state-gliding=true'
    entityStateConditions = 'skill-actions .*entity-state-conditions=true'
    stateBlocking = 'skill-actions .*state-blocking=true'
    spyglassCondition = 'skill-actions .*spyglass-condition=true'
    invulnerableCondition = 'skill-actions .*invulnerable-condition=true .*invulnerable-aliases=true'
    targetInvulnerable = 'skill-actions .*target-invulnerable=true'
    ownerTargeter = 'skill-actions .*owner-targeter=true'
    spatialTargeters = 'skill-actions .*spatial-targeters=true'
    spatialTargetersNegative = 'spatial-targeter-details .*lineofsight-blocked=true'
    spatialTargetersBoundaries = 'spatial-targeter-details .*sphere-boundary=true .*ring-boundary=true .*cone-boundary=true'
    lineTargeterParameters = 'spatial-targeter-details .*line-named=true .*line-width-boundary=true .*line-length-boundary=true'
    targeterControls = 'spatial-targeter-details .*targeter-controls=true'
    targeterSorts = 'spatial-targeter-details .*targeter-sorts=true'
    targeterControlsNegative = 'spatial-targeter-details .*targeter-skip-index=true .*targeter-limit-zero=true .*targeter-negative-filter=true .*targeter-invalid-limits=true'
    contextTargeterControls = 'context-targeter-controls order=true radius=true invalid-rejected=true'
    contextTargeterChain = 'context-targeter-chain targets=true trigger=true excluded=true condition=true'
    contextTargeterRelations = 'context-targeter-relations passenger=true passengers=true mount=true vehicle=true owner-location=true parent-location=true invalid-rejected=true passed=true'
    targeterArgumentValidation = 'targeter-argument-validation rejected=true chain=true control=true target-players=true ignore-players=true target-players-specific=true target-players-disabled=true invalid-filter=true target-ground=true ignore-ground=true target-water=true ignore-water=true target-flying=true ignore-flying=true target-invulnerable-disabled=true target-invulnerable-priority=true target-armorstands-disabled=true target-armorstands-priority=true target-animals-disabled=true target-animals-priority=true target-creatures-disabled=true target-creatures-priority=true target-villagers-disabled=true target-villagers-priority=true target-all=true target-creative=true ignore-creative=true target-creative-priority=true target-creative-disabled=true target-spectator=true ignore-spectator=true target-spectator-priority=true target-spectator-disabled=true'
    locationTargeterDispatch = 'location-targeter-dispatch valid=true invalid-rejected=true self-aliases=true self-eye=true caster-spawn=true spawn=true target-aliases=true trigger=true forward=true projectile-forward=true predicted=true|tracked-location-parity action=true targeter=true distance=true missing-rejected=true'
    variableLocationDispatch = 'variable-location-dispatch valid=true invalid-rejected=true import-preserved=true'
    explosionLocationDispatch = 'explosion-location-dispatch valid=true missing-rejected=true'
    dropItemLocation = 'drop-item-location valid=true invalid-rejected=true missing-rejected=true'
    dropItemImported = 'drop-item-import valid=true missing-rejected=true invalid-preserved=true owner=true target-owner=true'
    dropItemNaturalImported = 'drop-item-natural-import exact=true invalid-preserved=true'
    dropItemInlineImported = 'drop-item-inline-import valid=true invalid-preserved=true dynamic=true missing-rejected=true'
    dropItemListImported = 'drop-item-list-import valid=true invalid-preserved=true'
    importedSkillReference = 'imported-skill-reference valid=true rejected=true preserved=true warnings=true'
    importedSkillTiming = 'imported-skill-timing submitted=true before-delay=true first=true repeated=true invalid-rejected=true preserved=true warnings=true duplicate-rejected=true mechanic-preserved=true positional-chance=true positional-condition=true suffix-rejected=true'
    unroutedEventSuffix = 'unrouted-event-suffix rejected=true warnings=true'
    eventSuffixVariants = 'event-suffix-variants rejected=true warnings=true literal=true'
    orphanSuffix = 'orphan-suffix preserved=true warnings=true rejected=true unchanged=true'
    conditionFailureClosed = '(?<![a-z-])condition-failure-closed bound=true blocked=true control=true'
    namedConditionFailureClosed = 'named-condition-failure-closed bound=true blocked=true control=true'
    namedSkillReload = 'named-skill-reload bound=true initial=true blocked=true restored=true'
    namedSkillReference = 'named-skill-reference forward=true missing-rejected=true cycle-blocked=true'
    deathLootItems = 'death-loot natural=true dead=true cause=BukkitDamage items=[1-9][0-9]*'
    deathLootExperience = 'death-loot natural=true dead=true cause=BukkitDamage .*experience=1[4-6]'
    deathLootConditions = 'death-loot natural=true dead=true cause=BukkitDamage .*conditional-drop=true.*rejected-drop-absent=true.*exclusive-range-drop=true range-amount=[12]'
    deathLootDynamicChance = 'death-loot natural=true dead=true cause=BukkitDamage .*dynamic-chance-drop=true'
    deathLootGeneratedLines = 'death-loot-count natural=true total-items=128 diamonds=(?:1[6-9]|[2-3][0-9]|4[0-8]) emeralds=(?:8[0-9]|9[0-9]|10[0-9]|11[0-2]) count-passed=true weights-passed=true'
    deathLootBonuses = 'death-loot-bonus natural=true level-only=4 luck-only=3 combined=5 zero=2 weighted-max=4 potion-luck=3 potion-effective-luck=1(?:\.0+)? weighted-item-count=true fractional-samples=128 fractional-extra-lines=(?:3[5-9]|[4-8][0-9]|9[0-3]) fractional-range=true passed=true'
    deathLootImportedDropTable = 'death-loot-imported natural=true killer-player=true imported=true conditions-mapped=true conflict-preserved=true nested-mapped=true diamond=1 gold-range-amount=[12] experience=5 restored=true passed=true'
    mythicStatsBias = 'mythic-stats result=true default=true base=0(?:\.0+)? killer-priority=true unknown-rejected=true disabled-rejected=true positive-weighted=true positive-rare=[0-9]+ negative-weighted=true unknown-fail-closed=true restored=true dropper-removed=true'
    deathLootTriggerConditions = 'death-loot-trigger natural=true killer-player=true permission-allowed=true permission-denied=true nested-allowed-drop=true effective-luck=1(?:\.0+)? luck-scaled-drop=true nested-rejected-drop-absent=true nested-rejected-experience=[0-9]+ parent-rejected-drop-absent=true parent-rejected-experience=0'
    deathLootDropperConditions = 'death-loot-dropper-conditions natural=true killer-player=true monster-allowed=true animal-rejected=true allowed-experience=3 rejected-experience=0'
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
    mobOptionsNatural = 'mob-options-natural bee-stinger=true bee-event=synthetic .*zombie-conversion=true zombie-control=true zombie-protected=true .*passed=true'
    mobOptionsSkeletonConversion = 'mob-options-skeleton-conversion protected-events=[1-9][0-9]* control-events=[1-9][0-9]* protected-cancelled=true control-cancelled=false protected-type=SKELETON protected-valid=true control-stray=true control-converted=true elapsed-ticks=[1-9][0-9]* restored=true passed=true'
    mobOptionsZombieConversion = 'mob-options-zombie-conversion protected-events=[1-9][0-9]* control-events=[1-9][0-9]* protected-cancelled=true control-cancelled=false protected-type=ZOMBIE protected-valid=true control-drowned=true control-converted=true elapsed-ticks=[1-9][0-9]* restored=true passed=true'
    mobOptionsZombieVillagerCured = 'mob-options-zombie-villager-cured protected-events=[1-9][0-9]* control-events=[1-9][0-9]* protected-cancelled=true control-cancelled=false protected-type=ZOMBIE_VILLAGER protected-valid=true control-villager=true control-converted=true elapsed-ticks=[1-9][0-9]* restored=true passed=true'
    mobOptionsVillagerInfection = 'mob-options-villager-infection protected-events=[1-9][0-9]* control-events=[1-9][0-9]* protected-cancelled=true control-cancelled=false protected-type=VILLAGER protected-villager=true protected-alive=false protected-zombie-villager=false control-zombie-villager=true control-converted=true native-attacks=true/true difficulty-restored=true elapsed-ticks=[1-9][0-9]* restored=true passed=true'
    preventSunburnNatural = 'prevent-sunburn-natural imported=true mapped=true protected-option=true control-option=false daylight=true weather-clear=true observer-present=true protected-player-distance=[0-9]+ control-player-distance=[0-9]+ sky-exposed=true protected-ai=true control-ai=true protected-age-ticks=[1-9][0-9]* control-age-ticks=[1-9][0-9]* protected-events=[1-9][0-9]* protected-cancelled=[1-9][0-9]* protected-fire-ticks=(-1|0) control-events=[1-9][0-9]* control-cancelled=0 control-fire-ticks=[1-9][0-9]* elapsed-ticks=[1-9][0-9]* restored=true passed=true'
    mobOptionsMountEvent = 'mob-options-mount-event prevented=true allowed=true natural-prevented=true natural-allowed=true natural-events=[1-9][0-9]*/[1-9][0-9]* natural-cancelled=true/false native-finalize=(?:true|false)/true generic-prevented=true generic-allowed=true .*passed=true'
    mobOptionsSlimeSplit = 'mob-options-slime-split prevented=true allowed=true passed=true'
    mobOptionsSlimeSplitNatural = 'mob-options-slime-split-natural protected-size=4 control-size=4 protected-event=true protected-cancelled=true protected-event-count=[1-9][0-9]* control-event=true control-cancelled=false control-event-count=[1-9][0-9]* protected-dead=true control-dead=true protected-children=0 control-children=[1-9][0-9]* .*monster-spawning-enabled=true spawn-flags-restored=true damage=api passed=true'
    pathfinderClear = 'pathfinder-clear probe=path_clear_probe .*accepted=true .*cleared=true .*passed=true'
    pathfinderStopCalls = 'pathfinder-clear .*stop-calls=[1-9][0-9]*'
    aiPriority = 'ai-priority-probe attack-first=true flee-first=true attack-state=ATTACK flee-state=FLEE'
    aiPriorityTargets = 'ai-priority-detail probe=priority_attack_probe target-assigned=true target-valid=true health-ratio=0.5 attack-priority=0 flee-priority=1 selector=mode=PLAYERS can-attack=true can-chase=true can-flee=true target-matches=true'
    aiSelectorCore = 'ai-selector-core .*player-target=true .*monster-target=true .*clear-no-fallback=true .*owner-target=true .*faction-target=true .*conditional-target=true .*players-in-world=true .*players-on-server=true .*player-aliases=true .*mobs-in-world=true .*world-caster-excluded=true .*world-chain-filtered=true .*nearest-living=true .*nearest-mob=true .*targeter-syntax=true .*set-target-mechanic=true .*clear-target-mechanic=true .*context-chain-action=true .*native-target=true .*passed=true'
    targeterIdentityFilter = 'targeter-identity-filter managed=true target-mythic=true target-nonmythic=true ignore-mythic=true ignore-vanilla=true targetvanilla=true targetnonmythic=true targetnonmythic-false=true duplicate-rejected=true owner-target=true owner-target-disabled=true owner-ignore=true owner-priority=true faction-target=true faction-ignore=true same-faction=true same-faction-disabled=true faction-priority=true same-faction-world=true targetall-relations=true relation-options-rejected=true passed=true'
    aiRoute = 'ai-route-probe token=followpath .*active-before=true .*moving=true .*advanced=true .*patrol-mutation=true .*patrol-loop=route=patrol .*direction=backward .*finished=false .*passed=true'
    pinTargeters = '\[自检\] pin-targeters result=true multi-dispatch=true random-single=true move-single=true reject-multi-move=true region-set-break=true region-cap-rejected=true'
    pinConditions = '\[自检\] pin-conditions result=true distance=true origin-distance=true origin-inside=true origin-outside=true origin-missing-rejected=true region=true'
    skillTrigger = 'skill-trigger .*damage=true .*attack=true .*teleport=true .*bukkit-attacked=true'
    damageCauseCondition = 'skill-trigger .*damage-cause-condition=true .*damage-amount-condition=true .*bow-tension-condition=true'
    damageTagCondition = 'skill-trigger .*damage-tag-condition=true'
    bowTensionCondition = 'skill-trigger .*bow-tension-condition=true'
    cancelledCondition = 'skill-trigger .*cancelled-condition=true .*cancelled-after=true'
    lastDamagerTargeter = 'skill-trigger .*last-damager-targeter=true .*last-damager-projectile=true .*last-damager-environmental-rejected=true .*last-damager-removed-rejected=true'
    targetOfTargetTargeter = 'skill-trigger .*target-of-target=true .*target-of-target-aliases=true .*target-of-target-non-mob-rejected=true .*target-of-target-missing-rejected=true .*target-of-target-invalid-rejected=true'
    potionEffectEndTrigger = 'skill-trigger .*potion-effect-end=true'
    potionNaturalExpiry = 'potion-natural-expiry=true .*expired=true .*(?:action-observed=true|item-marker-observed=true)'
    killTrigger = 'kill-trigger imported=true mapped=true natural=true .*victim-dead=true .*action-observed=true passed=true'
    playerKillTrigger = 'player-kill-trigger imported=true mapped=true natural=true .*victim-player=true .*victim-death-observed=true .*action-observed=true .*player-kills-positive=true .*player-kills-negative=true passed=true'
    importedSignalTrigger = 'imported-signal-trigger imported=true mapped=true generic=true selector=true payload=true sender=true trigger=true invalid-preserved=true last-signal=true passed=true'
    importedTimerTrigger = 'imported-timer-trigger imported=true mapped=true aligned=true independent=true invalid-preserved=true source=true passed=true'
    importedPlayerKillsCondition = 'imported-player-kills-condition primary-mapped=true range-mapped=true positive=true negative=true invalid-preserved=true'
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
    environmentalDamage = 'environmental-damage natural=true source=LAVA action-observed=true elapsed-ticks=[1-9][0-9]* lava=true passed=true'
    spawnerMaxSpawns = 'spawner-max-spawns id=max_spawn_probe first=true second=false total=1'
    spawnerGroup = 'spawner-group id=group_probe requested=true alive=[12] total=[12] max-alive=2 group-min=3 group-max=5 limit=true'
    spawnerNaturalGroup = 'spawner-natural-group id=natural_group_probe mode=live-scheduler before=[0-9]+ after=[1-9][0-9]* alive=2 max-alive=2 group-min=3 group-max=5 limit=true'
    mythicSpawnerChanceImported = 'mythic-spawner-chance imported=true valid-mapped=true invalid-mapped=true valid-natural=true invalid-rejected=true warning=true .*passed=true'
    mythicSpawnerGroupSizeImported = 'mythic-spawner-group-size imported=true mapped=true natural=true requested=3 spawned=2 alive=2 max-alive=2 passed=true'
    mythicSpawnerConditionsImported = 'mythic-spawner-conditions imported=true mapped=true world-mapped=true biome-mapped=true biome-natural=true biome-rejected=true biome-pass-spawned=1 biome-reject-spawned=0 world-natural=true world-rejected=true rain=true thunder=false natural=true rejected=true pass-spawned=1 reject-spawned=0 world-pass-spawned=1 world-reject-spawned=0 instance=true restored=true passed=true'
    mythicSpawnerRandomPoolImported = 'mythic-spawner-random-pool imported=true mapped=true sampled=true expected-emberling=0\.250000 expected-emberling_boss=0\.750000 observed-emberling=[0-9]+\.[0-9]{6} observed-emberling_boss=[0-9]+\.[0-9]{6} max-abs-deviation=[0-9]+\.[0-9]{6} samples=100 passed=true'
    mythicSpawnerRandomPoolDropTables = 'mythic-spawner-drop-table imported=true references=true weighted=true tables-loaded=true spawned=32 variants=true assigned=true common-table-reloaded=true rare-table-reloaded=true common-alive=true rare-alive=true common-natural=true rare-natural=true common-bread=1 common-diamond=0 rare-bread=0 rare-diamond=1 restored=true passed=true'
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
    spawnerBorderClipped = 'spawner-border id=border_clipped_inside_pass requested=true configured-radius=16(?:\.0+)? effective-radius=10(?:\.0+)? border-size=20(?:\.0+)? attempts=[1-8] restored=true failure=null'
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
if ($env:PAPER_SPAWNING_DISTANCE_WALK_WORKFLOW -ne 'true') {
    $probeSpecs.Remove('spawningDistanceClientWalk')
}
if ($env:PAPER_SHOW_HEALTH_WORKFLOW -ne 'true') {
    $probeSpecs.Remove('showHealthDelivery')
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

if ($env:PAPER_WEB_RELOAD_PROBE -eq 'true') {
    $webPath = Join-Path $server 'web-reload-probe.json'
    $webStatus = 'not-observed'
    $webObservation = 'web reload evidence missing or invalid'
    if ((Test-Path -LiteralPath $webPath -PathType Leaf) -and $PluginSha256 -and
            $StartedAtUtc -ne [DateTime]::MinValue -and
            (Get-Item -LiteralPath $webPath).LastWriteTimeUtc -ge $StartedAtUtc.AddSeconds(-2)) {
        try {
            $web = Get-Content -LiteralPath $webPath -Raw -Encoding utf8 | ConvertFrom-Json
            if ([string]$web.pluginSha256 -ceq $PluginSha256 -and $web.invalidOk -is [bool] -and
                    $web.invalidOk -eq $false -and $web.restoredOk -is [bool] -and $web.restoredOk -eq $true -and
                    $web.serverThread -is [bool] -and $web.serverThread -eq $true -and
                    $web.restoredSpawnerCount -is [long] -and $web.restoredSpawnerCount -gt 0 -and
                    $logText -match '(?s)\[Server thread/WARN\]: \[helsteraMobs\] 刷怪点重载失败[^\r\n]*.*?\[Server thread/INFO\]: \[helsteraMobs\] 已装载刷怪点') {
                $webStatus = 'passed'
                $webObservation = 'web-reload invalid=false restored=true server-thread=true'
            }
        } catch { $webObservation = 'web reload evidence invalid' }
    }
    $probes += [ordered]@{ id = 'webSpawnerReload'; status = $webStatus; observations = @($webObservation) }
}

$menuClientLog = Join-Path $server 'menu-client.stdout.log'
$messageClientLog = Join-Path $server 'menu-client.process.stdout.log'
$importedMessageStatus = 'not-observed'
if ((Test-Path -LiteralPath $messageClientLog -PathType Leaf) -and
        $logText -match 'imported-message-client dispatched=true' -and
        (Get-Content -LiteralPath $messageClientLog -Raw -Encoding utf8) -match '(?m)^PAPER_IMPORTED_MESSAGE_RECEIVED exact=true\r?$') {
    $importedMessageStatus = 'passed'
}
$probes += [ordered]@{
    id = 'importedMessageClient'
    status = $importedMessageStatus
    observations = @($(if ($importedMessageStatus -eq 'passed') {
        'imported-message-client dispatched=true client-exact=true'
    } else { 'imported message dispatch or exact client receipt missing' }))
}
$escapedMessageStatus = 'not-observed'
if ((Test-Path -LiteralPath $messageClientLog -PathType Leaf) -and
        $logText -match 'imported-escaped-message-client dispatched=true' -and
        (Get-Content -LiteralPath $messageClientLog -Raw -Encoding utf8) -match '(?m)^PAPER_IMPORTED_ESCAPED_MESSAGE_RECEIVED exact=true\r?$') {
    $escapedMessageStatus = 'passed'
}
$probes += [ordered]@{
    id = 'importedEscapedMessageClient'
    status = $escapedMessageStatus
    observations = @($(if ($escapedMessageStatus -eq 'passed') {
        'imported-escaped-message-client dispatched=true client-exact=true'
    } else { 'escaped message dispatch or exact client receipt missing' }))
}
$menuStatus = 'not-observed'
$menuObservation = 'client report missing'
if (Test-Path -LiteralPath $menuClientLog -PathType Leaf) {
    $menuLine = Get-Content -LiteralPath $menuClientLog -Encoding utf8 |
        Where-Object { $_ -match '^PAPER_MENU_REPORT ' } | Select-Object -Last 1
    if ($menuLine -match '^PAPER_MENU_REPORT (.+)$') {
        try {
            $menu = $Matches[1] | ConvertFrom-Json
            $menuObservation = $Matches[1]
            $required = @('pageOne', 'deniedClickStayedOpen', 'paginationClick',
                'pageTwo', 'allowedActionMessage', 'actionClosedInventory',
                'deniedActionMessageAbsent')
            $allPassed = $menu.status -eq 'passed'
            foreach ($field in $required) { $allPassed = $allPassed -and $menu.observations.$field -eq $true }
            $allPassed = $allPassed -and
                ($logText -match 'menu-render id=parity-page-1 .*10=REDSTONE') -and
                ($logText -match 'menu-render id=parity-page-1 .*12=GOLD_INGOT') -and
                ($logText -match 'menu-render id=parity-page-2 .*13=EMERALD') -and
                ($logText -match 'menu-click id=parity-page-1 .*slot=10 permitted=false ran=false') -and
                ($logText -match 'menu-click id=parity-page-1 .*slot=26 permitted=true ran=true pagination=next') -and
                ($logText -match 'menu-click id=parity-page-2 .*slot=13 permitted=true ran=true close=true')
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

$interactableClientStatus = 'not-observed'
$interactableClientObservations = [System.Collections.Generic.List[string]]::new()
$interactableClientLog = Join-Path $server 'menu-client.stdout.log'
$interactableServerMatch = [regex]::Match($logText,
    'interactable-client result=true false-event=true false-cancelled=true true-event=true true-cancelled=false interactions=2')
if ($interactableServerMatch.Success) {
    $interactableClientObservations.Add($interactableServerMatch.Value)
}
if (Test-Path -LiteralPath $interactableClientLog -PathType Leaf) {
    $interactableLine = Get-Content -LiteralPath $interactableClientLog -Encoding utf8 |
        Where-Object { $_ -match '^PAPER_INTERACTABLE_CLIENT_REPORT ' } | Select-Object -Last 1
    if ($interactableLine -match '^PAPER_INTERACTABLE_CLIENT_REPORT (.+)$') {
        try {
            $interactableClient = $Matches[1] | ConvertFrom-Json
            $interactableClientObservations.Add($Matches[1])
            $requiredInteractableObservations = @('distinctEntities',
                'blockedInteractionSent', 'blockedEventObserved', 'blockedCancelled',
                'allowedInteractionSent', 'allowedEventObserved')
            $allInteractableObservationsPassed = $interactableClient.status -eq 'passed' -and
                $interactableClient.observations.allowedCancelled -eq $false -and
                $interactableClient.blockedEntityId -gt 0 -and $interactableClient.allowedEntityId -gt 0 -and
                $interactableClient.blockedEntityId -ne $interactableClient.allowedEntityId
            foreach ($field in $requiredInteractableObservations) {
                $allInteractableObservationsPassed = $allInteractableObservationsPassed -and
                    $interactableClient.observations.$field -eq $true
            }
            if ($allInteractableObservationsPassed -and $interactableServerMatch.Success) {
                $interactableClientStatus = 'passed'
            }
        } catch {
            $interactableClientObservations.Add("invalid client report: $($_.Exception.Message)")
        }
    }
}
if ($interactableClientObservations.Count -eq 0) {
    $interactableClientObservations.Add('client report or matching server cancellation evidence missing')
} elseif ($interactableClientStatus -ne 'passed') {
    $interactableClientObservations.Add('client observations and server cancellation state did not both pass')
}
$probes += [ordered]@{
    id = 'interactableClient'
    status = $interactableClientStatus
    observations = @($interactableClientObservations)
}

$audienceClientLog = Join-Path $server 'audience-client.stdout.log'
$showHealthClientStatus = 'not-observed'
$showHealthClientObservation = 'ShowHealth client report missing'
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
                'permissionNegativeTesterOmitted', 'permissionNegativeGuestIncluded',
                'factionIncludedTester', 'factionIncludedGuest',
                'factionNegativeTesterIncluded', 'factionNegativeGuestExcluded')
            $allPassed = $filter.status -eq 'passed'
            foreach ($field in $required) { $allPassed = $allPassed -and $filter.observations.$field -eq $true }
            if ($allPassed) { $filterStatus = 'passed' }
        } catch {
            $filterObservation = "invalid audience filter report: $($_.Exception.Message)"
        }
    }
    if ($env:PAPER_SHOW_HEALTH_WORKFLOW -eq 'true') {
        $showHealthLine = Get-Content -LiteralPath $audienceClientLog -Encoding utf8 |
            Where-Object { $_ -match '^PAPER_SHOW_HEALTH_CLIENT_REPORT ' } | Select-Object -Last 1
        if ($showHealthLine -match '^PAPER_SHOW_HEALTH_CLIENT_REPORT (.+)$') {
            try {
                $showHealth = $Matches[1] | ConvertFrom-Json
                $showHealthClientObservation = $Matches[1]
                $requiredShowHealth = @('nearPlayerReceivedFirstThreshold', 'nearPlayerReceivedSecondThreshold',
                    'outsidePlayerExcluded', 'belowThresholdQuiet', 'withinBandQuiet', 'healingRearmedThreshold')
                $allPassed = $showHealth.status -eq 'passed'
                foreach ($field in $requiredShowHealth) {
                    $allPassed = $allPassed -and $showHealth.observations.$field -eq $true
                }
                if ($allPassed) { $showHealthClientStatus = 'passed' }
            } catch {
                $showHealthClientObservation = "invalid client report: $($_.Exception.Message)"
            }
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
if ($env:PAPER_SHOW_HEALTH_WORKFLOW -eq 'true') {
    $probes += [ordered]@{
        id = 'showHealthClientDelivery'
        status = $showHealthClientStatus
        observations = @($showHealthClientObservation)
    }
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
$playerPotionNaturalExpiryRequested = $env:PAPER_RAW_POTION_WORKFLOW -eq 'true' -and
    $env:PAPER_RAW_POTION_SHORT -eq 'true'
$playerPotionNaturalExpiryObserved = $logText -match 'player-potion-natural-expiry=true'
if ($playerPotionNaturalExpiryRequested -or $playerPotionNaturalExpiryObserved) {
    $playerPotionNaturalExpiryObservations = [string[]]@(
        [regex]::Matches($logText, '.*player-potion-natural-expiry=true.*') |
            ForEach-Object { $_.Value.Trim() })
    if (-not $playerPotionNaturalExpiryObserved) {
        $playerPotionNaturalExpiryObservations = [string[]]@(
            'short raw Potion workflow was requested but natural expiry was not observed')
    }
    $probes += [ordered]@{
        id = 'playerPotionNaturalExpiry'
        status = if ($playerPotionNaturalExpiryObserved) { 'passed' } else { 'not-observed' }
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
        $spawnStatuses[$spawnId] = 'not-observed'
    }
    $spawnObservations[$spawnId] = @($spawnObservations[$spawnId]) + $line
    if ($line -match '(?:^|\s)requested=true(?:\s|$)') {
        $spawnStatuses[$spawnId] = 'passed'
    } elseif ($spawnStatuses[$spawnId] -ne 'passed' -and
            $line -match '(?:^|\s)requested=false\s+failure=(?!null(?:\s|$))\S') {
        $spawnStatuses[$spawnId] = 'observed-rejected'
    }
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
        'custom-menu-player-workflow', 'audience-player-delivery', 'importedMessageClient', 'importedEscapedMessageClient',
        'interactableClient', 'audience-filter-delivery', 'random-spawn-distribution',
        'skillTeleportCoordinates') + $(if ($env:PAPER_WEB_RELOAD_PROBE -eq 'true') {
            @('webSpawnerReload')
        } else { @() }) + $(if ($env:PAPER_SHOW_HEALTH_WORKFLOW -eq 'true') {
            @('showHealthClientDelivery')
        } else { @() }) + $(if ($playerPotionConsumeRequested) {
            @('playerPotionConsume')
        } else { @() }) + $(if ($playerPotionNaturalExpiryRequested -or $playerPotionNaturalExpiryObserved) {
            @('playerPotionNaturalExpiry')
        } else { @() }) + @(
        $spawnObservations.Keys | ForEach-Object { "spawner:$($_)" })
    probes = @($probes)
}
if ($PluginSha256) { $report.pluginSha256 = $PluginSha256 }
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding utf8
Write-Host "Paper integration report: $reportPath"
