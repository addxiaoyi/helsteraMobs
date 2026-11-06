$ErrorActionPreference = 'Stop'
$previousRawPotionWorkflow = $env:PAPER_RAW_POTION_WORKFLOW
$previousRawPotionShort = $env:PAPER_RAW_POTION_SHORT
$previousSpawningDistanceWalkWorkflow = $env:PAPER_SPAWNING_DISTANCE_WALK_WORKFLOW
$env:PAPER_RAW_POTION_WORKFLOW = 'false'
$env:PAPER_RAW_POTION_SHORT = 'false'
$env:PAPER_SPAWNING_DISTANCE_WALK_WORKFLOW = 'true'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$temp = Join-Path ([System.IO.Path]::GetTempPath()) ('helstera-paper-report-' + [guid]::NewGuid())
$logDirectory = Join-Path $temp 'logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

$pythonInfo = & (Join-Path $PSScriptRoot 'resolve-python.ps1')
if ($LASTEXITCODE -ne 0 -or $null -eq $pythonInfo) {
    throw 'Python 3 with encodings/jsonschema is required for report validation.'
}
$pythonCommand = [string]$pythonInfo.Source
$pythonPrefix = @($pythonInfo.Prefix)

function Assert-True([bool]$condition, [string]$message) {
    if (-not $condition) { throw "ASSERTION FAILED: $message" }
}

function Assert-ReportShape($report, $schema) {
    Assert-True ($report.schemaVersion -eq $schema.properties.schemaVersion.const) 'schema version'
    foreach ($field in $schema.required) {
        Assert-True ($report.PSObject.Properties.Name -contains $field) "missing report field $field"
    }
    $allowedProbeFields = @($schema.properties.probes.items.properties.PSObject.Properties.Name)
    $allowedStatuses = @($schema.properties.probes.items.properties.status.enum)
    foreach ($probe in $report.probes) {
        foreach ($field in $schema.properties.probes.items.required) {
            Assert-True ($probe.PSObject.Properties.Name -contains $field) "probe '$($probe.id)' missing $field"
        }
        Assert-True ($probe.status -in $allowedStatuses) "probe '$($probe.id)' has invalid status '$($probe.status)'"
        foreach ($property in $probe.PSObject.Properties.Name) {
            Assert-True ($property -in $allowedProbeFields) "probe '$($probe.id)' has undeclared property '$property'"
        }
        Assert-True ($probe.observations -is [array]) "probe '$($probe.id)' observations must be an array"
        if ($probe.id -eq 'random-spawn-distribution') {
            Assert-True ($probe.sampleCount -is [int] -or $probe.sampleCount -is [long]) 'random sampleCount must be integer'
            foreach ($field in @('expected', 'observed', 'deviation')) {
                Assert-True (-not [string]::IsNullOrWhiteSpace($probe.$field)) "random probe missing $field"
            }
            if ($probe.sampleCount -ge 100) {
                $expectedStatus = if ($probe.maxDeviation -le $probe.tolerance) { 'passed' } else { 'failed' }
                Assert-True ($probe.status -eq $expectedStatus) 'random deviation status must match configured tolerance'
            } elseif ($probe.status -ne 'not-observed') {
                Assert-True ($probe.status -eq 'insufficient-sample') 'under-sampled random probe status'
            }
        }
    }
}

try {
    $lines = @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: Done (12.345s)! For help, type "help"',
        '[Server thread/INFO]: [helsteraMobs] [自检] skill-scheduler result=true delay=2 repeat=3 interval=2 executions=4 pre-delay-count=0',
        '[Server thread/INFO]: [helsteraMobs] [自检] skill-tree-branches result=true condition-denied=true condition-unlock=true prerequisite-blocked=true prerequisite-unlock=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] pin-targeters result=true multi-dispatch=true random-single=true move-single=true reject-multi-move=true region-set-break=true region-cap-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] pin-conditions result=true distance=true origin-distance=true origin-inside=true origin-outside=true origin-missing-rejected=true region=true',
        '[Server thread/INFO]: [helsteraMobs] survival-mode-condition positive=true negative=true restored=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] skill-tree-cast-persistence result=true blocked-before-unlock=true prerequisite-unlock-blocked=true snapshot-saved=true advanced-unlocked=true casts=1',
        '[Server thread/INFO]: skill-tree-queue submitted=true restored=true',
        '[Server thread/INFO]: skill-lifecycle reload=true scheduled=true cancelled=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true bounds-condition=true bounding-box-overlap-condition=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-mobility=true drowning-condition=true leashed-condition=true has-owner-condition=true has-tag-condition=true target-has-tag-condition=true scoreboard-tag-mechanics=true score-mechanics=true global-score-condition=true score-condition=true custom-owner-relation=true owner-condition=true children-condition=true inside-outside-conditions=true distance-from-location-condition=true coordinate-difference-condition=true origin-location-condition=true line-of-sight-from-origin-condition=true bounds-condition=true enchanting-conditions=true plugin-condition=true field-of-view-condition=true spawn-reason-condition=true water-mob-condition=true zombie-condition=true aerial-condition=true enderman-condition=true enchantment-condition=true color-condition=true material-cooldown-condition=true material-cooldown-mechanic=true set-game-mode-mechanic=true jump-mechanic=true force-pull-mechanic=true set-offhand-mechanic=true set-armor-mechanic=true clear-inventory-mechanic=true remove-held-item-mechanic=true raw-experience-mechanic=true set-absorption-mechanic=true trigger-item-type-condition=true entity-item-type-condition=true entity-item-similar-condition=true item-similar-condition=true targets-condition=true item-type-condition=true structure-condition=true mythic-mob-type-condition=true trigger-block-type-condition=true frozen-condition=true dialogue-condition=true player-kills-condition=true absorption-condition=true health-absorption-condition=true potion-effect-condition=true aura-stacks-condition=true level-condition=true lunar-phase-condition=true string-equals-condition=true string-empty-condition=true compare-values-condition=true is-skill-condition=true owner-online-condition=true parent-relation=true is-child-condition=true is-child-import-condition=true adult-parent-positive=true adult-not-baby=true unrelated-baby-not-child=true baby-positive=true parent-derived-conditions=true player-experience-mechanics=true oxygen-mechanic=true consume-slot-mechanic=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true target-categories=true',
        '[Server thread/INFO]: raider-patrol-leader-condition positive=true negative=true entity=PILLAGER restored=true',
        '[Server thread/INFO]: creeper-primed-condition positive=true negative=true restored=true',
        '[Server thread/INFO]: ender-dragon-alive-condition positive=true negative=true removed=true',
        '[Server thread/INFO]: ender-dragon-phase-condition positive=true list=true negative=true invalid-rejected=true restored=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-has-enchantment-condition primary-mapped=true alias-mapped=true positive=true any-positive=true level-rejected=true type-rejected=true restored=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-trigger-item-type-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true constructed-context=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-trigger-block-type-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true state-rejected=true constructed-context=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-origin-location-condition primary-mapped=true alias-mapped=true positive=true exact-positive=true negative=true invalid-rejected=true constructed-context=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-item-recharging-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true non-human-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-frozen-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true thawed=true alias-thawed=true invalid-rejected=true',
        '[Server thread/INFO]: in-combat-condition managed-path=bukkit-mob-target managed-positive=true managed-no-target-rejected=true managed-restored=true native-positive=true native-no-target-rejected=true native-restored=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true target-categories=true target-health=true faction-conditions=true faction-mechanic=true level-mechanic=true weather-mechanic=true time-mechanic=true lightning-mechanic=true signal-mechanics=true target-kill=true damage-melee=true condition-utility=true in-combat-condition=true mobs-in-chunk-condition=true block-type-radius-condition=true biome-condition=true player-radius-conditions=true cooldown-mechanics=true last-damage-cause=true motion-conditions=true directional-velocity-condition=true fall-speed-condition=true target-environment=true target-fluids=true target-blocked=true',
        '[Server thread/INFO]: condition-utility-details .*spawn-distance-exact=true .*spawn-distance-over-rejected=true .*mobs-chunk=true .*mobs-chunk-high-rejected=true .*mobs-chunk-zero=true .*mobs-chunk-occupied=true .*mobs-chunk-adjacent-excluded=true .*mobs-chunk-boundaries=true .*mobs-in-chunk-condition=true .*chance-100=true .*chance-0-rejected=true .*chance-invalid-rejected=true',
        '[Server thread/INFO]: spatial-condition-details biome-exact=true biome-partial=true biome-reject=true biome-type-category=true',
        '[Server thread/INFO]: biome-imported partial=true exact-rejected=true invalid-preserved=true',
        '[Server thread/INFO]: invalid-imported-conditions valid-skill=true invalid-skill=true invalid-damage=true warnings=true',
        '[Server thread/INFO]: skill-dot damage-observed=true bounded=true expired=true initial=100 after-intervals=94 at-duration=92 after-expiry=92',
        '[Server thread/INFO]: skill-targeter threat-targeter=true source=damage-events faction-filters=true threat-actions=true first=high expected=high',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-composition=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-composition=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true give-item=true take-item=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true give-item=true take-item=true block-set=true block-break=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true give-item=true take-item=true block-set=true block-break=true set-ai=true set-gravity=true set-silent=true set-collidable=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true give-item=true take-item=true block-set=true block-break=true set-ai=true set-gravity=true set-silent=true set-collidable=true set-fire=true clear-fire=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true give-item=true take-item=true block-set=true block-break=true set-ai=true set-gravity=true set-silent=true set-collidable=true set-fire=true clear-fire=true set-rotation=true look-at=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true give-item=true take-item=true dynamic-placeholder=true block-set=true block-break=true set-ai=true set-gravity=true set-silent=true set-collidable=true set-fire=true clear-fire=true set-rotation=true look-at=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true dynamic-placeholder=true gameplay-placeholders=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-composition=true time-weather=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true world-biome=true condition-composition=true time-weather=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true world-biome=true world-biome-reject=true condition-composition=true time-weather=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-composition=true caster-permission=true food-level=true food-saturation=true free-inventory-slot=true has-item=true air-level=true custom-item=true target-state-conditions=true state-blocking=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true spyglass-condition=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true name-condition=true',
        '[Server thread/INFO]: [自检] skill-trigger damage=true attacked=false teleport=true teleport-before=false bukkit-damage=true damage-cause-condition=true damage-amount-condition=true damage-tag-condition=true bow-tension-condition=true bukkit-attacked=true cancelled-condition=true cancelled-before=false cancelled-after=true entity-valid=true',
        '[Server thread/INFO]: block-events break=true place=true player=HelsteraTester',
        '[Server thread/INFO]: skill-trigger damage=true attacked=false teleport=true potion-effect-end=true teleport-before=false bukkit-damage=true bukkit-attacked=true cancelled-before=false cancelled-after=false entity-valid=true',
        '[Server thread/INFO]: potion-natural-expiry=true expired=true event-observed=false action-observed=true item-marker-observed=false',
        '[Server thread/INFO]: kill-trigger natural=true victim-dead=true action-observed=true passed=true',
        '[Server thread/INFO]: player-kill-trigger natural=true victim-player=true victim-death-observed=true action-observed=true player-kills-positive=true player-kills-negative=true passed=true',
        '[Server thread/INFO]: tempt-probe-delayed entity-target-trigger=true target-events=1 target-find-misses=0',
        '[Server thread/INFO]: item-pickup trigger=true player=HelsteraTester',
        '[Server thread/INFO]: vehicle-events enter=true exit=true dismount=true eject=true destroy=true player=HelsteraTester',
        '[Server thread/INFO]: environmental-damage natural=true source=LAVA action-observed=true elapsed-ticks=7 lava=true passed=true',
        '[Server thread/INFO]: [helsteraMobs] [trigger-debug] player-potion-consume event=true player=HelsteraTester item=POTION',
        '[Server thread/INFO]: player-potion-natural-expiry=true expired=true action-observed=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true looking-at-condition=true item-recharging-condition=true variable-contains-condition=true before=1.00,2.00,3.00 after=1.00,2.00,3.00 offset-distance=1.000 moved-distance=0.000',
        '[Server thread/INFO]: projectile-has-ended=true observed=true ticks=3 projectile=00000000-0000-0000-0000-000000000000',
        '[Server thread/INFO]: [helsteraMobs] [自检] horse-options result=true saddled=true armor=true chest=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] max-combat-distance result=true inside-allowed=true outside-blocked=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] scale-options result=true scale=1.25',
        '[Server thread/INFO]: [helsteraMobs] [自检] health-options result=true health=42 max-health=42',
        '[Server thread/INFO]: [helsteraMobs] [自检] movement-speed-options result=true base=0.4',
        '[Server thread/INFO]: [helsteraMobs] [自检] knockback-resistance-options result=true base=0.75',
        '[Server thread/INFO]: [helsteraMobs] [自检] follow-range-options result=true base=40',
        '[Server thread/INFO]: [helsteraMobs] [自检] damage-options result=true base=12',
        '[Server thread/INFO]: [helsteraMobs] [自检] armor-options result=true base=8',
        '[Server thread/INFO]: [helsteraMobs] [自检] entity-config-flags result=true no-ai=true invulnerable=true silent=true gravity=false collidable=false',
        '[Server thread/INFO]: [helsteraMobs] [自检] exp-options result=true experience=13 event=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] exp-natural-death result=true dead=true experience=13 cause=BukkitDamage',
        '[Server thread/INFO]: [helsteraMobs] [自检] exp-tier-death result=true low=2 qualified=7 low-dead=true qualified-dead=true low-bread=4 qualified-bread=4',
        '[Server thread/INFO]: [helsteraMobs] [自检] revive-natural result=true alive=true attached=true health=8.0',
        '[Server thread/INFO]: [helsteraMobs] [自检] revive-late result=true cancelled=true alive=true attached=true health=8.0 no-drops=true',
        '[Server thread/INFO]: [helsteraMobs] damage-imported mapped=true entity-damage=true attacker-name=true attacker-equipment=true environmental-damage=true environmental-no-trigger=true passed=true',
        '[Server thread/INFO]: [helsteraMobs] immunity-imported result=true reduced=true condition-rejected=true invalid-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] immunity-native-invalid result=true false-rejected=true null-rejected=true unknown-rejected=true null-modifier-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] immunity-invalid-fields null-immunity=true null-modifier-field=true unknown-modifier-field=true invalid-immune=true null-immune=true',
        '[Server thread/INFO]: [helsteraMobs] immunity-preservation nested-preserved=true nested-closed=true list-null-preserved=true list-null-closed=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] entity-angry-pollination result=true bee-angry-positive=true bee-angry-negative=true wolf-angry-positive=true wolf-angry-negative=true vex-angry-positive=true vex-angry-negative=true bee-pollinated-positive=true bee-pollinated-negative=true unsupported-angry-rejected=true missing-context-rejected=true aliases=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] entity-sheared-states result=true sheep-self-positive=true sheep-target-positive=true sheep-self-negative=true sheep-target-negative=true bogged-self-positive=true bogged-target-positive=true bogged-self-negative=true bogged-target-negative=true snow-golem-self-positive=true snow-golem-target-positive=true snow-golem-self-negative=true snow-golem-target-negative=true unsupported-rejected=true missing-context-rejected=true aliases=true',
        '[Server thread/INFO]: [helsteraMobs] condition-arguments living-rejected=true baby-valid=true baby-rejected=true frozen-valid=true frozen-rejected=true warnings=true',
        '[Server thread/INFO]: [helsteraMobs] condition-state-arguments valid=true movement-rejected=true category-rejected=true alive-rejected=true negated-rejected=true preserved=true warnings=true',
        '[Server thread/INFO]: [helsteraMobs] target-sneaking-mob false-rejected=true true-passed=true',
        '[Server thread/INFO]: [helsteraMobs] time-weather-arguments time-valid=true time-opposite=true weather-valid=true weather-opposite=true time-rejected=true rain-rejected=true negated-rejected=true preserved=true warnings=true',
        '[Server thread/INFO]: [helsteraMobs] visibility-state-arguments valid=true rejected=true preserved=true warnings=true',
        '[Server thread/INFO]: [helsteraMobs] drop-null-conditions rejected=true warnings=true sibling=true',
        '[Server thread/INFO]: [helsteraMobs] drop-native-null-conditions rejected=true sibling=true warnings=true',
        '[Server thread/INFO]: [helsteraMobs] drop-native-null-numeric rejected=true fallback=true overrides=true warnings=true',
        '[Server thread/INFO]: [helsteraMobs] drop-unsafe-references warnings=true stale-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] drop-merged-null rejected=true warnings=true stale-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] despawn-options result=true mode=PERSISTENT distance=0 persistent=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] spawning-distance-options result=true mode=NORMAL distance=8',
        '[Server thread/INFO]: [helsteraMobs] [自检] spawning-distance-runtime result=true inside-retained=true outside-removed=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] spawning-distance-client-walk result=true player-displacement=14.25 player-entity-distance=12.25 instance-removed=true entity-removed=true despawn-elapsed-ticks=3 decision-period-ticks=10',
        '[Server thread/INFO]: [helsteraMobs] [自检] is-in-cinematic-condition player-open=true player-out-of-range-rejected=true non-player-rejected=true closed=true',
        '[Server thread/INFO]: [helsteraMobs] enderman-condition-details positive=true negative=true',
        '[Server thread/INFO]: [helsteraMobs] aerial-condition-details positive=true alias-positive=true negative=true ground-entity=player ground=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-aerial-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true ground=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-color-condition red-mapped=true blue-alias-mapped=true positive=true negative=true entity-color=RED',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-material-cooldown-condition primary-mapped=true alias-mapped=true positive=true wrong-material-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] visibility-options result=true visible-by-default=true invisible=true show-name=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] age-options result=true age=-24000 baby=true adult=true age-lock=true zombie-baby=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] armor-stand-options result=true can-move=false can-tick=false items=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] entity-flag-options result=true tamed=true angry=true can-join-raid-setter=true patrol-leader=true patrol-spawn-point=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] enderman-held-block result=true material=OBSIDIAN',
        '[Server thread/INFO]: attribute-options result=true modifiers=1',
        '[Server thread/INFO]: [helsteraMobs] [自检] mob-pickup-option result=true disabled=true explicit-overrides-default=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] spawn-option-runtime result=true pickup-disabled=true explicit-pickup-overrides-fallback=true random-equipment-cleared=true explicit-equipment-preserved=true no-damage-ticks=true',
        '[Server thread/INFO]: item-attribute-options result=true modifiers=1',
        '[Server thread/INFO]: [helsteraMobs] [自检] events creeper-explode-cancelled=true bee-attack-cancelled=true zombie-transform-cancelled=true prevent-transformation-reasons=true prevent-skeleton-conversion=true prevent-block-infection=true prevent-snow-formation=true tame-breed-tame=true tame-breed-breed=true leash-unleash-leash=true leash-unleash-unleash=true prevent-leash=true prevent-rename=true prevent-target=true prevent-vanilla-damage=true prevent-projectiles=true prevent-entity-explosions=true prevent-block-explosion-damage=true prevent-drowning=true prevent-fall-damage=true prevent-fire=true prevent-fire-causes=true prevent-pickup=true prevent-item-use=true interactable=true interactable-armor-stand=true prevent-teleport=true prevent-sunburn=true prevent-other-drops=true prevent-mob-kill-drops=true prevent-mob-kill-custom-loot=true shear-trigger=true bed-enter-leave-enter=true bed-enter-leave-leave=true trade-trigger=true quit-trigger=true item-drop-break-damage=true player-actions-interact=true world-change=true jump=true swing=true fish-trigger=true',
        '[Server thread/INFO]: mob-options-natural creeper-fuse=true exploded=true max-fuse=20 passed=true',
        '[Server thread/INFO]: mob-options-natural bee-stinger=true bee-attacks=1/1 zombie-conversion=true zombie-control=true zombie-protected=true passed=true',
        '[Server thread/INFO]: mob-options-mount-event prevented=true allowed=true generic-prevented=true generic-allowed=true jockey-instance=true jockey-type=ZOMBIE jockey-valid=true jockey-dead=false jockey-health=20.0 jockey-vehicle=none jockey-found=true jockey-found-prevent=true jockey-ticks=240 jockey-passenger-types= passed=true',
        '[Server thread/INFO]: mob-options-slime-split prevented=true allowed=true passed=true',
        '[Server thread/INFO]: pathfinder-clear probe=path_clear_probe found=true reachable=true started=true active-before=true accepted=true cleared=true stop-calls=1 passed=true',
        '[Server thread/INFO]: ai-priority-probe attack-first=true flee-first=true attack-state=ATTACK flee-state=FLEE',
        '[Server thread/INFO]: ai-priority-detail probe=priority_attack_probe target-assigned=true target-valid=true health-ratio=0.5 attack-priority=0 flee-priority=1 selector=mode=PLAYERS can-attack=true can-chase=true can-flee=true target-matches=true',
        '[Server thread/INFO]: ai-selector-core goals=true player-target=true monster-target=true clear-no-fallback=true owner-target=true faction-target=true conditional-target=true players-in-world=true players-on-server=true player-aliases=true mobs-in-world=true world-caster-excluded=true world-chain-filtered=true nearest-living=true nearest-mob=true targeter-syntax=true set-target-mechanic=true clear-target-mechanic=true context-chain-action=true native-target=true selector=mode=PLAYERS can-attack=true can-chase=false conditional=is-player=>is-player entity=PLAYER passed=true passed=true',
        '[Server thread/INFO]: ai-route-probe token=followpath active-before=true moving=true advanced=true waypoint-before=route=followpath waypoint=103,64,0 finished=false waypoint-after=route=followpath waypoint=105,64,0 finished=false passed=true',
        '[Server thread/INFO]: spatial-targeter-details targeter-controls=true targeter-sorts=true targeter-skip-index=true targeter-limit-zero=true targeter-negative-filter=true targeter-invalid-limits=true',
        '[Server thread/INFO]: context-targeter-controls order=true radius=true invalid-rejected=true',
        '[Server thread/INFO]: context-targeter-chain targets=true trigger=true excluded=true condition=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] death-loot natural=true dead=true cause=BukkitDamage items=2 conditional-drop=true rejected-drop-absent=true dynamic-chance-drop=true exclusive-range-drop=true range-amount=1 experience=10',
        '[Server thread/INFO]: [helsteraMobs] [自检] death-loot-count natural=true total-items=128 diamonds=31 emeralds=97 count-passed=true weights-passed=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] death-loot-bonus natural=true level-only=4 luck-only=3 combined=5 zero=2 weighted-max=4 potion-luck=3 potion-effective-luck=1.0 weighted-item-count=true fractional-samples=128 fractional-extra-lines=64 fractional-range=true passed=true',
        '[Server thread/INFO]: [helsteraMobs] death-loot-imported natural=true killer-player=true imported=true conditions-mapped=true conflict-preserved=true nested-mapped=true diamond=1 gold-range-amount=2 experience=5 restored=true passed=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] death-loot-trigger natural=true killer-player=true permission-allowed=true permission-denied=true nested-allowed-drop=true effective-luck=1.0 luck-scaled-drop=true nested-rejected-drop-absent=true nested-rejected-experience=1 parent-rejected-drop-absent=true parent-rejected-experience=0',
        '[Server thread/INFO]: [helsteraMobs] [自检] spawner-probe id=pass requested=true failure=null',
        '[Server thread/INFO]: [helsteraMobs] [自检] spawner-probe id=reject requested=false failure=player gate closed',
        '[Server thread/INFO]: spawner-max-spawns id=max_spawn_probe first=true second=false total=1 failure=max spawns reached',
        '[Server thread/INFO]: spawner-group id=group_probe requested=true alive=2 total=2 max-alive=2 group-min=3 group-max=5 limit=true',
        '[Server thread/INFO]: spawner-natural-group id=natural_group_probe mode=live-scheduler before=0 after=2 alive=2 max-alive=2 group-min=3 group-max=5 limit=true',
        '[Server thread/INFO]: spawner-recycle id=recycle_probe before=1 after=2 released=true respawned=true alive=1 interval=40',
        '[Server thread/INFO]: spawner-region id=region_probe requested=true region=integration_box failure=null',
        '[Server thread/INFO]: spawner-region-outside id=region_probe_outside requested=false region=integration_box failure=conditions rejected location',
        '[Server thread/INFO]: spawner-scoped-limits id=scoped_limit_probe first=true second=false total=1 world-limit=1 failure=scoped spawn limits reached',
        '[Server thread/INFO]: spawner-player-limits id=player_limit_probe first=true second=false total=1 player-limit=1 failure=scoped spawn limits reached',
        '[Server thread/INFO]: spawner-cooldown id=cooldown_probe before=0 after-first=0 after-second=0 remaining-first=40 remaining-second=39',
        '[Server thread/INFO]: spawner-failure-cooldown id=failed_cooldown_probe before=0 after=0 total-before=0 total-after=0 rejected=true failure=no-valid-location',
        '[Server thread/INFO]: spawner-natural-timing id=cooldown_probe mode=live-scheduler before=1 early=1 due=2 remaining-at-start=20 before-due=true after-due=true',
        '[Server thread/INFO]: spawner-lunar id=lunar_probe requested=true phase=FULL failure=null',
        '[Server thread/INFO]: spawner-heightmap id=heightmap_ground_pass requested=true surface=STONE surface-y=64 failure=null',
        '[Server thread/INFO]: spawner-ground id=ground_missing_fail requested=false failure=no valid spawn location restored=true saved-blocks=384',
        '[Server thread/INFO]: spawner-unloaded-chunk id=unloaded_chunk_fail requested=false chunk-loaded=false failure=no valid spawn location restored=true',
        '[Server thread/INFO]: spawner-light-fail-closed id=light_unloaded_fail requested=false chunk-loaded=false light-readable=false result=false restored=true',
        '[Server thread/INFO]: spawner-border id=border_clipped_inside_pass requested=true configured-radius=16.0 effective-radius=10.0 border-size=20.0 attempts=1 restored=true failure=null',
        '[Server thread/INFO]: spawner-border id=border_outside_fail requested=false configured-radius=0.1 effective-radius=0.0 border-size=20.0 restored=true failure=no valid spawn location',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=border_clipped_inside_pass mode=natural-location requested=true failure=null',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=border_outside_fail mode=natural-location requested=false failure=no valid spawn location',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=heightmap_ground_pass mode=natural-location requested=true failure=null',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=ground_missing_fail mode=natural-location requested=false failure=no valid spawn location',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=unloaded_chunk_fail mode=natural-location requested=false failure=no valid spawn location',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=light_unloaded_fail mode=safe-override requested=false failure=conditions rejected location',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=weather_rain_pass mode=safe-override requested=true failure=null',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=weather_rain_fail mode=safe-override requested=false failure=conditions rejected location',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=time_window_pass mode=safe-override requested=true failure=null',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=time_window_fail mode=safe-override requested=false failure=conditions rejected location',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=biome_desert_pass mode=safe-override requested=true failure=null',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=biome_plains_fail mode=safe-override requested=false failure=conditions rejected location',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=condition_combo_pass mode=safe-override requested=true failure=null',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=null_condition_base mode=safe-override requested=false failure=conditions rejected location',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=null_condition_merged mode=safe-override requested=false failure=conditions rejected location',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=null_condition_override mode=safe-override requested=true failure=null',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=condition_combo_fail mode=safe-override requested=false failure=conditions rejected location',
        '[Server thread/INFO]: tempt-probe holder=HelsteraTester target=00000000-0000-0000-0000-000000000000 native-target=true entity-target-trigger=false synthetic-entity-target=true synthetic-before=false passed=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] random-spawn expected={a=0.5, b=0.5} observed={a=0.5, b=0.5} deviation={a=0.0, b=0.0} total=99',
        '[Server thread/INFO]: [helsteraMobs] [自检] random-spawn expected={a=0.5, b=0.5} observed={a=0.52, b=0.48} deviation={a=0.02, b=-0.02} total=100',
        '[Server thread/INFO]: [helsteraMobs] [自检] loot table=common hits=1 materialized=1 entities=1 amounts=true experience=0 dynamic-chance=true'
    )
    $lines = @($lines | ForEach-Object {
        $_ -replace 'random-spawn expected=', 'random-spawn id=random_distribution_probe expected=' `
           -replace ' total=(\d+)$', ' max-abs-deviation=0.02 total=$1'
    })
    $lines += '[Server thread/INFO]: random-spawn id=random_distribution_probe expected={a=0.5, b=0.5} observed={a=0.65, b=0.35} deviation={a=0.15, b=-0.15} max-abs-deviation=0.15 total=100'
    $lines += '[Server thread/INFO]: random-spawn id=random_distribution_probe expected={a=0.5, b=0.5} observed={a=0.650001, b=0.349999} deviation={a=0.150001, b=-0.150001} max-abs-deviation=0.150001 total=100'
    $lines += '[Server thread/INFO]: menu-render id=parity-page-1 slots=10=REDSTONE,12=GOLD_INGOT'
    $lines += '[Server thread/INFO]: menu-render id=parity-page-2 slot=13=EMERALD'
    $lines += '[Server thread/INFO]: menu-click id=parity-page-1 slot=10 permitted=false ran=false'
    $lines += '[Server thread/INFO]: menu-click id=parity-page-1 slot=26 permitted=true ran=true pagination=next'
    $lines += '[Server thread/INFO]: menu-click id=parity-page-2 slot=13 permitted=true ran=true close=true'
    Set-Content -LiteralPath (Join-Path $logDirectory 'latest.log') -Value $lines -Encoding utf8
    Set-Content -LiteralPath (Join-Path $temp 'menu-client.stdout.log') -Value @(
        'PAPER_MENU_REPORT {"status":"passed","observations":{"pageOne":true,"pageOneRedstone":true,"deniedClickStayedOpen":true,"paginationClick":true,"pageTwo":true,"pageTwoEmerald":true,"allowedActionMessage":true,"actionClosedInventory":true,"deniedActionMessageAbsent":true}}'
    ) -Encoding utf8
    Set-Content -LiteralPath (Join-Path $temp 'audience-client.stdout.log') -Value @(
        'PAPER_AUDIENCE_REPORT {"status":"passed","observations":{"message":true,"title":true,"actionbar":true,"particle":true}}',
        'PAPER_AUDIENCE_FILTER_REPORT {"status":"passed","observations":{"radiusIncludedTester":true,"radiusIncludedGuest":true,"radiusExcludedTester":true,"radiusExcludedGuest":true,"permissionIncludedTester":true,"permissionIncludedGuest":true,"factionIncludedTester":true,"factionIncludedGuest":true,"factionExcludedTester":true,"factionExcludedGuest":true}}'
    ) -Encoding utf8
    $env:PAPER_RAW_POTION_WORKFLOW = 'true'
    $testPluginHash = 'a' * 64
    $oldWebProbe = $env:PAPER_WEB_RELOAD_PROBE
    try {
        $env:PAPER_WEB_RELOAD_PROBE = 'true'
        $webPath = Join-Path $temp 'web-reload-probe.json'
        $web = @{ pluginSha256 = $testPluginHash; invalidOk = $false; restoredOk = $true; serverThread = $true; restoredSpawnerCount = 43 }
        $web | ConvertTo-Json | Set-Content -LiteralPath $webPath -Encoding utf8
        $webLog = @($lines) + @(
            '[Server thread/WARN]: [helsteraMobs] 刷怪点重载失败',
            '[Server thread/INFO]: [helsteraMobs] 已装载刷怪点 43 个'
        )
        Set-Content -LiteralPath (Join-Path $logDirectory 'latest.log') -Value $webLog -Encoding utf8
        $started = [DateTime]::UtcNow.AddSeconds(-1)
        & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash -StartedAtUtc $started | Out-Null
        $webReport = Get-Content -LiteralPath (Join-Path $temp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
        Assert-True (($webReport.probes | Where-Object id -eq 'webSpawnerReload').status -eq 'passed') 'web reload evidence passes with current hash and main-thread logs'
        $web.pluginSha256 = 'b' * 64
        $web | ConvertTo-Json | Set-Content -LiteralPath $webPath -Encoding utf8
        & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash -StartedAtUtc $started | Out-Null
        $webReport = Get-Content -LiteralPath (Join-Path $temp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
        Assert-True (($webReport.probes | Where-Object id -eq 'webSpawnerReload').status -eq 'not-observed') 'web reload rejects mismatched plugin hash'
        $web.pluginSha256 = $testPluginHash
        $web | ConvertTo-Json | Set-Content -LiteralPath $webPath -Encoding utf8
        (Get-Item -LiteralPath $webPath).LastWriteTimeUtc = $started.AddMinutes(-1)
        & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash -StartedAtUtc $started | Out-Null
        $webReport = Get-Content -LiteralPath (Join-Path $temp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
        Assert-True (($webReport.probes | Where-Object id -eq 'webSpawnerReload').status -eq 'not-observed') 'web reload rejects stale evidence'
        $web | ConvertTo-Json | Set-Content -LiteralPath $webPath -Encoding utf8
        Set-Content -LiteralPath (Join-Path $logDirectory 'latest.log') -Value $lines -Encoding utf8
        & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash -StartedAtUtc $started | Out-Null
        $webReport = Get-Content -LiteralPath (Join-Path $temp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
        Assert-True (($webReport.probes | Where-Object id -eq 'webSpawnerReload').status -eq 'not-observed') 'web reload rejects missing main-thread logs'
    } finally {
        $env:PAPER_WEB_RELOAD_PROBE = $oldWebProbe
        Set-Content -LiteralPath (Join-Path $logDirectory 'latest.log') -Value $lines -Encoding utf8
    }
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null
    $logPath = Join-Path $logDirectory 'latest.log'
    Set-Content -LiteralPath $logPath -Value ($lines -replace '12=GOLD_INGOT', '12=SPONGE') -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null
    $collisionFailure = Get-Content -LiteralPath (Join-Path $temp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($collisionFailure.probes | Where-Object id -eq 'custom-menu-player-workflow').status -ne 'passed') 'menu workflow rejects vanilla material in custom item collision'
    Set-Content -LiteralPath $logPath -Value $lines -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null
    $env:PAPER_RAW_POTION_WORKFLOW = 'false'

    $schemaPath = Join-Path $PSScriptRoot 'integration-report.schema.json'
    $reportPath = Join-Path $temp 'integration-report.json'
    & $pythonCommand @pythonPrefix (Join-Path $PSScriptRoot 'validate-report.py') $schemaPath $reportPath
    if ($LASTEXITCODE -ne 0) { throw "JSON schema validation failed" }

    $report = Get-Content -LiteralPath $reportPath -Raw -Encoding utf8 |
        ConvertFrom-Json
    $schema = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'integration-report.schema.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    Assert-ReportShape $report $schema
    $messageTemp = Join-Path $temp 'message-client-fixture'
    New-Item -ItemType Directory -Path (Join-Path $messageTemp 'logs') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $messageTemp 'logs/latest.log') -Value 'imported-message-client dispatched=true' -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $messageTemp | Out-Null
    $messageReport = Get-Content -LiteralPath (Join-Path $messageTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($messageReport.probes | Where-Object id -eq 'importedMessageClient').status -eq 'not-observed') 'message probe requires client receipt'
    Set-Content -LiteralPath (Join-Path $messageTemp 'menu-client.process.stdout.log') -Value 'PAPER_IMPORTED_MESSAGE_RECEIVED exact=true' -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $messageTemp | Out-Null
    $messageReport = Get-Content -LiteralPath (Join-Path $messageTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($messageReport.probes | Where-Object id -eq 'importedMessageClient').status -eq 'passed') 'message probe requires both server and client evidence'
    Set-Content -LiteralPath (Join-Path $messageTemp 'logs/latest.log') -Value 'imported-message-client dispatched=false' -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $messageTemp | Out-Null
    $messageReport = Get-Content -LiteralPath (Join-Path $messageTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($messageReport.probes | Where-Object id -eq 'importedMessageClient').status -eq 'not-observed') 'message probe requires server dispatch'
    Set-Content -LiteralPath (Join-Path $messageTemp 'logs/latest.log') -Value 'imported-escaped-message-client dispatched=true' -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $messageTemp | Out-Null
    $messageReport = Get-Content -LiteralPath (Join-Path $messageTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($messageReport.probes | Where-Object id -eq 'importedEscapedMessageClient').status -eq 'not-observed') 'escaped message probe requires its own client receipt'
    Set-Content -LiteralPath (Join-Path $messageTemp 'menu-client.process.stdout.log') -Value 'PAPER_IMPORTED_ESCAPED_MESSAGE_RECEIVED exact=true' -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $messageTemp | Out-Null
    $messageReport = Get-Content -LiteralPath (Join-Path $messageTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($messageReport.probes | Where-Object id -eq 'importedEscapedMessageClient').status -eq 'passed') 'escaped message probe accepts both observations'
    Set-Content -LiteralPath (Join-Path $messageTemp 'logs/latest.log') -Value 'imported-escaped-message-client dispatched=false' -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $messageTemp | Out-Null
    $messageReport = Get-Content -LiteralPath (Join-Path $messageTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($messageReport.probes | Where-Object id -eq 'importedEscapedMessageClient').status -eq 'not-observed') 'escaped message probe requires server dispatch'
    Set-Content -LiteralPath (Join-Path $messageTemp 'logs/latest.log') -Value 'named-condition-failure-closed bound=true blocked=true control=true' -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $messageTemp | Out-Null
    $messageReport = Get-Content -LiteralPath (Join-Path $messageTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($messageReport.probes | Where-Object id -eq 'namedConditionFailureClosed').status -eq 'passed') 'named condition probe accepts its observation'
    Assert-True (($messageReport.probes | Where-Object id -eq 'conditionFailureClosed').status -eq 'not-observed') 'named condition probe cannot satisfy the independent condition probe'
    Assert-True ($report.pluginSha256 -ceq $testPluginHash) 'report records plugin SHA-256'
    $wrongPluginHashRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $reportPath -ExpectedPluginSha256 ('b' * 64)
    } catch { $wrongPluginHashRejected = $_.Exception.Message -match 'plugin SHA-256' }
    Assert-True $wrongPluginHashRejected 'strict gate rejects mismatched plugin SHA-256'
    foreach ($requiredProbeId in @('custom-menu-player-workflow', 'audience-player-delivery',
            'audience-filter-delivery', 'random-spawn-distribution', 'skillTeleportCoordinates',
            'spawningDistanceClientWalk', 'isInCinematicCondition', 'aerialCondition',
            'importedAerialCondition', 'endermanCondition', 'importedColorCondition',
            'importedIsFrozenCondition',
            'importedHasEnchantmentCondition', 'importedTriggerItemTypeCondition',
            'importedTriggerBlockTypeCondition', 'importedOriginLocationCondition',
            'importedMaterialCooldownCondition', 'importedItemRechargingCondition')) {
        Assert-True ($report.expectedProbeIds -contains $requiredProbeId) "probe catalog contains $requiredProbeId"
        Assert-True (@($report.probes | Where-Object id -eq $requiredProbeId).Count -gt 0) "report contains $requiredProbeId"
    }
    Assert-True $report.pluginEnabled 'plugin enabled detection'
    Assert-True (($report.probes | Where-Object id -eq 'pathfinderClear').status -eq 'passed') 'native Pathfinder clear probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawningDistanceClientWalk').status -eq 'passed') 'real client movement despawn probe'
    Assert-True (($report.probes | Where-Object id -eq 'importedAerialCondition').status -eq 'passed') 'imported Aerial aliases require strict Paper positive and negative evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedIsFrozenCondition').status -eq 'passed') 'imported IsFrozen aliases require strict Paper frozen/thawed and malformed-input evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedColorCondition').status -eq 'passed') 'imported Color and clr require strict Paper positive and negative evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedHasEnchantmentCondition').status -eq 'passed') 'imported HasEnchantment requires strict Paper mapping, level and type evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedTriggerItemTypeCondition').status -eq 'passed') 'imported TriggerItemType requires strict Paper mapping and positive/negative evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedTriggerBlockTypeCondition').status -eq 'passed') 'imported TriggerBlockType requires strict Paper mapping, material, and state evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedOriginLocationCondition').status -eq 'passed') 'imported OriginLocation requires strict Paper mapping, exact, negative, and malformed-input evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedMaterialCooldownCondition').status -eq 'passed') 'imported MaterialIsOnCooldown aliases require strict Paper positive and negative evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedItemRechargingCondition').status -eq 'passed') 'imported ItemRecharging aliases require strict Paper positive and non-HumanEntity rejection evidence'
    Assert-True (($report.probes | Where-Object id -eq 'aiPriority').status -eq 'passed') 'AI selector priority arbitration probe'
    Assert-True (($report.probes | Where-Object id -eq 'aiPriorityTargets').status -eq 'passed') 'AI priority probe target setup'
    Assert-True (($report.probes | Where-Object id -eq 'aiSelectorCore').status -eq 'passed') 'AI selector core Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'aiRoute').status -eq 'passed') 'AI route waypoint Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'contextTargeterControls').status -eq 'passed') 'context targeter controls probe'
    Assert-True (($report.probes | Where-Object id -eq 'contextTargeterChain').status -eq 'passed') 'context targeter chain probe'

    $releaseManifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release-evidence.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    foreach ($releaseEntry in $releaseManifest) {
        $archivedReport = Join-Path $root ([string]$releaseEntry.report)
        if (-not (Test-Path -LiteralPath $archivedReport -PathType Leaf)) {
            throw "Archived release report is missing: $($releaseEntry.report)"
        }
        & $pythonCommand @pythonPrefix (Join-Path $PSScriptRoot 'validate-report.py') $schemaPath $archivedReport
        if ($LASTEXITCODE -ne 0) { throw "Archived report schema validation failed: $($releaseEntry.report)" }
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $archivedReport -RequireTargetPaperVersion
    }
    Assert-True (($report.probes | Where-Object id -eq 'targeterControlsNegative').status -eq 'passed') 'invalid targeter limits fail closed'
    Assert-True (($report.probes | Where-Object id -eq 'targeterSorts').status -eq 'passed') 'generic target sorting behavior'
    Assert-True ($report.paperVersion -eq '1.21.1-test') 'Paper version extraction'
    Assert-True (($report.probes | Where-Object id -eq 'horse').status -eq 'passed') 'positive probe'
    Assert-True (($report.probes | Where-Object id -eq 'ageOptions').status -eq 'passed') 'age options probe'
    Assert-True (($report.probes | Where-Object id -eq 'entityFlagOptions').status -eq 'passed') 'entity flag options probe'
    Assert-True (($report.probes | Where-Object id -eq 'expOptions').status -eq 'passed') 'experience option probe'
    Assert-True (($report.probes | Where-Object id -eq 'expNaturalDeath').status -eq 'passed') 'natural experience death probe'
    Assert-True (($report.probes | Where-Object id -eq 'expTierDeath').status -eq 'passed') 'natural tiered experience death probe'
    Assert-True (($report.probes | Where-Object id -eq 'reviveNatural').status -eq 'passed') 'natural revive probe'
    Assert-True (($report.probes | Where-Object id -eq 'reviveLate').status -eq 'passed') 'late cancelled revive probe'
    Assert-True (($report.probes | Where-Object id -eq 'damageImported').status -eq 'passed') 'imported damage trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'immunityImported').status -eq 'passed') 'imported immunity probe'
    Assert-True (($report.probes | Where-Object id -eq 'immunityNativeInvalid').status -eq 'passed') 'invalid native immunity probe'
    Assert-True (($report.probes | Where-Object id -eq 'immunityInvalidFields').status -eq 'passed') 'invalid immunity fields probe'
    Assert-True (($report.probes | Where-Object id -eq 'immunityPreservation').status -eq 'passed') 'immunity preservation probe'
    Assert-True (($report.probes | Where-Object id -eq 'entityAngryPollination').status -eq 'passed') 'angry and pollinated state probe'
    Assert-True (($report.probes | Where-Object id -eq 'entityShearedStates').status -eq 'passed') 'sheared entity state probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionArguments').status -eq 'passed') 'condition arguments probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionStateArguments').status -eq 'passed') 'condition state arguments probe'
    Assert-True (($report.probes | Where-Object id -eq 'targetSneakingMob').status -eq 'passed') 'target sneaking mob probe'
    Assert-True (($report.probes | Where-Object id -eq 'timeWeatherArguments').status -eq 'passed') 'time and weather arguments probe'
    Assert-True (($report.probes | Where-Object id -eq 'visibilityStateArguments').status -eq 'passed') 'visibility state arguments probe'
    Assert-True (($report.probes | Where-Object id -eq 'dropNullConditions').status -eq 'passed') 'null drop conditions probe'
    Assert-True (($report.probes | Where-Object id -eq 'dropNativeNullConditions').status -eq 'passed') 'native null drop conditions probe'
    Assert-True (($report.probes | Where-Object id -eq 'dropNativeNullNumeric').status -eq 'passed') 'native null numeric probe'
    Assert-True (($report.probes | Where-Object id -eq 'dropUnsafeReferences').status -eq 'passed') 'unsafe drop references probe'
    Assert-True (($report.probes | Where-Object id -eq 'dropMergedNull').status -eq 'passed') 'merged null drop probe'
    Assert-True (($report.probes | Where-Object id -eq 'despawnOptions').status -eq 'passed') 'despawn option probe'
    Assert-True (($report.probes | Where-Object id -eq 'visibilityOptions').status -eq 'passed') 'visibility option probe'
    Assert-True (($report.probes | Where-Object id -eq 'endermanHeldBlock').status -eq 'passed') 'Enderman held block option probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnOptionRuntime').status -eq 'passed') 'spawn option runtime probe'
    Assert-True (($report.probes | Where-Object id -eq 'mobPickupOption').status -eq 'passed') 'mob pickup option probe'
    Assert-True (($report.probes | Where-Object id -eq 'sheep').status -eq 'not-observed') 'missing probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:reject').status -eq 'observed-rejected') 'rejected probe'
    Assert-True (($report.probes | Where-Object id -eq 'lootDynamicChance').status -eq 'passed') 'dynamic chance probe'
    $events = @($report.probes | Where-Object id -eq 'events')
    Assert-True ($events.Count -eq 1 -and $events[0].status -eq 'passed') 'single passing events probe'
    Assert-True ($events[0].observations[0] -match 'creeper-explode-cancelled=true bee-attack-cancelled=true zombie-transform-cancelled=true .*prevent-transformation-reasons=true .*prevent-skeleton-conversion=true .*prevent-block-infection=true .*prevent-snow-formation=true .*prevent-leash=true .*prevent-rename=true .*prevent-target=true .*prevent-vanilla-damage=true .*prevent-projectiles=true .*prevent-entity-explosions=true .*prevent-block-explosion-damage=true .*prevent-drowning=true .*prevent-fall-damage=true .*prevent-fire=true .*prevent-fire-causes=true .*prevent-pickup=true .*prevent-item-use=true .*interactable=true .*interactable-armor-stand=true .*prevent-teleport=true .*prevent-sunburn=true .*prevent-other-drops=true .*prevent-mob-kill-drops=true .*prevent-mob-kill-custom-loot=true') 'all event policies cancelled'
    Assert-True (($report.probes | Where-Object id -eq 'tameBreed').status -eq 'passed') 'tame and breed trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'leashLifecycle').status -eq 'passed') 'leash lifecycle trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'shearTrigger').status -eq 'passed') 'shear trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'bedLifecycle').status -eq 'passed') 'bed lifecycle trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'tradeTrigger').status -eq 'passed') 'trade trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'quitTrigger').status -eq 'passed') 'quit trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'itemLifecycle').status -eq 'passed') 'item lifecycle trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'playerActions').status -eq 'passed') 'player action trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'fishTrigger').status -eq 'passed') 'fish trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'creeperNaturalFuse').status -eq 'passed') 'natural Creeper fuse probe'
    Assert-True (($report.probes | Where-Object id -eq 'mobOptionsNatural').status -eq 'passed') 'natural Bee/Zombie probe'
    Assert-True (($report.probes | Where-Object id -eq 'mobOptionsMountEvent').status -eq 'passed') 'jockey mount event probe'
    Assert-True (($report.probes | Where-Object id -eq 'mobOptionsSlimeSplit').status -eq 'passed') 'slime split option probe'
    Assert-True (($report.probes | Where-Object id -eq 'custom-menu-player-workflow').status -eq 'passed') 'menu client workflow probe'
    Assert-True (($report.probes | Where-Object id -eq 'audience-player-delivery').status -eq 'passed') 'audience client workflow probe'
    Assert-True (($report.probes | Where-Object id -eq 'audience-filter-delivery').status -eq 'passed') 'audience filter workflow probe'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootConditions').status -eq 'passed') 'death loot conditions probe'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootConditions').observations[0] -match 'exclusive-range-drop=true range-amount=[12]') 'exclusive Mythic amount range reaches natural death drops'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootDynamicChance').status -eq 'passed') 'death loot dynamic chance probe'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootGeneratedLines').status -eq 'passed') 'generated drop-line count and relative weights probe'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootBonuses').status -eq 'passed') 'natural death BonusLevelItems and BonusLuckItems probe'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootBonuses').observations[0] -match 'weighted-max=4 .*weighted-item-count=true') 'bonus probe requires weighted single-bound evidence'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootBonuses').observations[0] -match 'potion-luck=3 potion-effective-luck=1(?:\.0+)?') 'Luck potion contributes to effective Luck and BonusLuckItems'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootBonuses').observations[0] -match 'fractional-samples=128 fractional-extra-lines=64 fractional-range=true') 'fractional bonus probe records bounded natural-death samples'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootImportedDropTable').status -eq 'passed') 'imported Mythic DropTable natural death probe'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootImportedDropTable').observations[0] -match 'nested-mapped=true') 'cross-file nested DropTable reference reaches the death probe'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootImportedDropTable').observations[0] -match 'gold-range-amount=[12]') 'imported exclusive amount range reaches natural death drops'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootTriggerConditions').status -eq 'passed') 'killer trigger conditions probe'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootTriggerConditions').observations[0] -match 'effective-luck=1(?:\.0+)? luck-scaled-drop=true') 'Luck attribute drives a chance-scaled natural drop'
    Assert-True (($report.probes | Where-Object id -eq 'skillTeleport').status -eq 'passed') 'teleport mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'skillScheduling').status -eq 'passed') 'Paper tick scheduler probe'
    Assert-True (($report.probes | Where-Object id -eq 'skillTreeBranches').status -eq 'passed') 'Paper SkillTree branch gates probe'
    Assert-True (($report.probes | Where-Object id -eq 'skillTreeCastPersistence').status -eq 'passed') 'Paper persisted SkillTree cast gate probe'
    Assert-True (($report.probes | Where-Object id -eq 'skillTreeQueueSnapshot').status -eq 'passed') 'Paper queued SkillTree snapshot probe'
    Assert-True (($report.probes | Where-Object id -eq 'skillLifecycleCleanup').status -eq 'passed') 'Paper skill lifecycle cleanup probe'
    Assert-True (($report.probes | Where-Object id -eq 'playerExperienceMechanics').status -eq 'passed') 'player experience mechanics probe'
    Assert-True (($report.probes | Where-Object id -eq 'materialCooldownMechanic').status -eq 'passed') 'material cooldown mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'setGameModeMechanic').status -eq 'passed') 'set game mode mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'jumpMechanic').status -eq 'passed') 'jump mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'forcePullMechanic').status -eq 'passed') 'force pull mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'setOffhandMechanic').status -eq 'passed') 'set offhand mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'setArmorMechanic').status -eq 'passed') 'set armor mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'clearInventoryMechanic').status -eq 'passed') 'clear inventory mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'removeHeldItemMechanic').status -eq 'passed') 'remove held item mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'rawExperienceMechanic').status -eq 'passed') 'raw experience mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'setAbsorptionMechanic').status -eq 'passed') 'set absorption mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'oxygenMechanic').status -eq 'passed') 'oxygen mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'consumeSlotMechanic').status -eq 'passed') 'consume slot mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'cancelledCondition').status -eq 'passed') 'cancelled condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'damageCauseCondition').status -eq 'passed') 'damage cause condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'bowTensionCondition').status -eq 'passed') 'bow tension condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'potionNaturalExpiry').status -eq 'passed') 'natural potion expiry probe'
    Assert-True (($report.probes | Where-Object id -eq 'playerKillTrigger').status -eq 'passed') 'player kill trigger and counter probe'
    Assert-True (($report.probes | Where-Object id -eq 'playerKillsCondition').status -eq 'passed') 'player kills condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'mythicMobTypeCondition').status -eq 'passed') 'mythic mob type condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'variableContainsCondition').status -eq 'passed') 'variable contains condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionCatalog').status -eq 'passed') 'condition catalog probe'
    Assert-True (($report.probes | Where-Object id -eq 'enderDragonAliveCondition').status -eq 'passed') 'EnderDragonAlive positive/negative probe'
    Assert-True (($report.probes | Where-Object id -eq 'projectileHasEndedCondition').status -eq 'passed') 'projectile ended condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'absorptionCondition').status -eq 'passed') 'absorption condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'healthAbsorptionCondition').status -eq 'passed') 'health absorption condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'potionEffectCondition').status -eq 'passed') 'potion effect condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'auraStacksCondition').status -eq 'passed') 'aura stacks condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'levelCondition').status -eq 'passed') 'level condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'lunarPhaseCondition').status -eq 'passed') 'lunar phase condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'stringEqualsCondition').status -eq 'passed') 'string equals condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'stringEmptyCondition').status -eq 'passed') 'string empty condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'compareValuesCondition').status -eq 'passed') 'compare values condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'isSkillCondition').status -eq 'passed') 'is skill condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'frozenCondition').status -eq 'passed') 'frozen condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'boundsCondition').status -eq 'passed') 'bounds condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'boundingBoxOverlapCondition').status -eq 'passed') 'bounding box overlap condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'itemSimilarCondition').status -eq 'passed') 'item similarity condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'entityItemTypeCondition').status -eq 'passed') 'entity item type condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'entityItemSimilarCondition').status -eq 'passed') 'entity item similarity condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'targetsCondition').status -eq 'passed') 'targets condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'itemTypeCondition').status -eq 'passed') 'item type condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'structureCondition').status -eq 'passed') 'structure condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'lineOfSightFromOriginCondition').status -eq 'passed') 'line of sight from origin probe'
    Assert-True (($report.probes | Where-Object id -eq 'originLocationCondition').status -eq 'passed') 'origin location condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'triggerBlockTypeCondition').status -eq 'passed') 'trigger block type condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'worldBiome').status -eq 'passed') 'world and biome condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'worldBiomeReject').status -eq 'passed') 'world and biome rejection probe'
    Assert-True (($report.probes | Where-Object id -eq 'targetCategories').status -eq 'passed') 'target categories probe'
    Assert-True (($report.probes | Where-Object id -eq 'raiderPatrolLeaderCondition').status -eq 'passed') 'raider patrol leader condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'creeperPrimedCondition').status -eq 'passed') 'creeper primed condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'enderDragonPhaseCondition').status -eq 'passed') 'ender dragon phase condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'targetHealthComparators').status -eq 'passed') 'target health comparators probe'
    Assert-True (($report.probes | Where-Object id -eq 'targetFluidConditions').status -eq 'passed') 'target fluid conditions probe'
    Assert-True (($report.probes | Where-Object id -eq 'factionConditions').status -eq 'passed') 'faction conditions probe'
    Assert-True (($report.probes | Where-Object id -eq 'factionMechanic').status -eq 'passed') 'faction mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'levelMechanic').status -eq 'passed') 'level mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'weatherMechanic').status -eq 'passed') 'weather mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'timeMechanic').status -eq 'passed') 'time mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'lightningMechanic').status -eq 'passed') 'lightning mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'signalMechanics').status -eq 'passed') 'signal mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'globalScoreCondition').status -eq 'passed') 'global score condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'scoreCondition').status -eq 'passed') 'score condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'targetKillMechanic').status -eq 'passed') 'target kill mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'damageMeleeMechanic').status -eq 'passed') 'damage melee mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'inCombatCondition').status -eq 'passed') 'in-combat condition Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionUtilityAliases').status -eq 'passed') 'condition utility alias probe'
    Assert-True (($report.probes | Where-Object id -eq 'survivalModeCondition').status -eq 'passed') 'survival mode condition Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'distanceFromSpawnCondition').status -eq 'passed') 'distance from spawn boundary probe'
    Assert-True (($report.probes | Where-Object id -eq 'mobsInChunkCondition').status -eq 'passed') 'mobs in chunk condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'chanceCondition').status -eq 'passed') 'chance condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'biomeCondition').status -eq 'passed') 'biome condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'biomeImported').status -eq 'passed') 'imported biome condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'invalidConditionsImported').status -eq 'passed') 'invalid imported conditions probe'
    Assert-True (($report.probes | Where-Object id -eq 'spyglassCondition').status -eq 'passed') 'spyglass condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'foodSaturationCondition').status -eq 'passed') 'food saturation condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'freeInventorySlotCondition').status -eq 'passed') 'free inventory slot condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'cooldownMechanics').status -eq 'passed') 'cooldown mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'lastDamageCauseCondition').status -eq 'passed') 'last damage cause condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'damageTagCondition').status -eq 'passed') 'damage tag condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'motionConditions').status -eq 'passed') 'motion condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'nameCondition').status -eq 'passed') 'name condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'playerRadiusConditions').status -eq 'passed') 'player radius condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'dotMechanic').status -eq 'passed') 'DoT mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'threatActions').status -eq 'passed') 'threat action probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionComposition').status -eq 'passed') 'condition composition probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionMobility').status -eq 'passed') 'condition mobility probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionLeashed').status -eq 'passed') 'leashed condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionHasOwner').status -eq 'passed') 'has-owner condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionHasTag').status -eq 'passed') 'has-tag condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'targetHasTagCondition').status -eq 'passed') 'target has-tag condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'targetStateConditions').status -eq 'passed') 'target state condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'stateBlocking').status -eq 'passed') 'real shield blocking condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'scoreboardTagMechanics').status -eq 'passed') 'scoreboard tag mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'scoreMechanics').status -eq 'passed') 'score mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'customOwnerRelation').status -eq 'passed') 'custom owner relation probe'
    Assert-True (($report.probes | Where-Object id -eq 'ownerOnlineCondition').status -eq 'passed') 'owner online condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'parentRelation').status -eq 'passed') 'parent relation probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionIsChild').status -eq 'passed') 'is-child condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'importedIsChildCondition').status -eq 'passed') 'imported isChild condition Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'importedIsChildCondition').observations[0] -match 'is-child-import-condition=true adult-parent-positive=true adult-not-baby=true unrelated-baby-not-child=true baby-positive=true') 'imported isChild probe requires parent-relation and baby-age evidence'
    Assert-True (($report.probes | Where-Object id -eq 'parentDerivedConditions').status -eq 'passed') 'parent-derived conditions probe'
    Assert-True (($report.probes | Where-Object id -eq 'timeWeather').status -eq 'passed') 'time and weather condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'customItemResolver').status -eq 'passed') 'custom item resolver probe'
    Assert-True (($report.probes | Where-Object id -eq 'casterPermission').status -eq 'passed') 'caster permission probe'
    Assert-True (($report.probes | Where-Object id -eq 'foodLevelCondition').status -eq 'passed') 'food level condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'hasItemCondition').status -eq 'passed') 'has item condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'airLevelCondition').status -eq 'passed') 'air level condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'lookingAtCondition').status -eq 'passed') 'looking at condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'itemRechargingCondition').status -eq 'passed') 'item recharging condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'gameplayPlaceholders').status -eq 'passed') 'gameplay placeholder probe'
    Assert-True (($report.probes | Where-Object id -eq 'playerPotionConsume').status -eq 'passed') 'raw player potion consume probe'
    Assert-True ($report.expectedProbeIds -contains 'playerPotionConsume') 'raw player potion consume catalog entry'
    Assert-True (($report.probes | Where-Object id -eq 'playerPotionNaturalExpiry').status -eq 'passed') 'raw player potion natural expiry probe'
    Assert-True ($report.expectedProbeIds -contains 'playerPotionNaturalExpiry') 'raw player potion natural expiry catalog entry'
    Assert-True (($report.probes | Where-Object id -eq 'skillTrigger').status -eq 'passed') 'skill trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'pinTargeters').status -eq 'passed') 'pin targeters Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'pinConditions').status -eq 'passed') 'pin conditions Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'blockEvents').status -eq 'passed') 'block event trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'itemPickup').status -eq 'passed') 'item pickup trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'vehicleEvents').status -eq 'passed') 'vehicle event trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'ejectPassenger').status -eq 'passed') 'eject passenger mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'environmentalDamage').status -eq 'passed') 'environmental damage probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerMaxSpawns').status -eq 'passed') 'spawner max-spawns probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerGroup').status -eq 'passed') 'spawner group limit probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerNaturalGroup').status -eq 'passed') 'natural spawner group limit probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerRecycle').status -eq 'passed') 'spawner natural recycle probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerRegion').status -eq 'passed') 'spawner region probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerRegionOutside').status -eq 'passed') 'spawner region outside negative probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerScopedLimits').status -eq 'passed') 'spawner scoped-limits probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerPlayerLimits').status -eq 'passed') 'spawner player-limits probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerCooldown').status -eq 'passed') 'spawner cooldown probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerFailureCooldown').status -eq 'passed') 'failed spawn does not consume cooldown probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerNaturalTiming').status -eq 'passed') 'spawner natural scheduler timing probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerLunar').status -eq 'passed') 'spawner lunar probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerHeightmap').status -eq 'passed') 'spawner heightmap probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerGroundRestored').status -eq 'passed') 'spawner missing-ground cleanup probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerUnloadedChunk').status -eq 'passed') 'unloaded chunk fail-closed probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerLightFailClosed').status -eq 'passed') 'light fail-closed probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:heightmap_ground_pass').status -eq 'passed') 'natural heightmap ground positive control'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:unloaded_chunk_fail').status -eq 'observed-rejected') 'unloaded chunk negative control'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:ground_missing_fail').status -eq 'observed-rejected') 'natural no-ground negative control'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:light_unloaded_fail').status -eq 'observed-rejected') 'light fail-closed negative control'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerBorderClipped').status -eq 'passed') 'world border clipping positive probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerBorderOutside').status -eq 'passed') 'world border outside rejection probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:border_clipped_inside_pass').status -eq 'passed') 'in-border natural spawn probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:border_outside_fail').status -eq 'observed-rejected') 'outside-border natural spawn negative control'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:weather_rain_pass').status -eq 'passed') 'rain-positive spawner probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:weather_rain_fail').status -eq 'observed-rejected') 'rain-negative spawner probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:time_window_pass').status -eq 'passed') 'time-positive spawner probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:time_window_fail').status -eq 'observed-rejected') 'time-negative spawner probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:biome_desert_pass').status -eq 'passed') 'biome-positive spawner probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:biome_plains_fail').status -eq 'observed-rejected') 'biome-negative spawner probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:condition_combo_pass').status -eq 'passed') 'positive composite spawner condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:condition_combo_fail').status -eq 'observed-rejected') 'negative composite spawner condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'tempt').status -eq 'passed') 'tempt probe'
    Assert-True (($report.probes | Where-Object id -eq 'entityTargetSynthetic').status -eq 'passed') 'synthetic entity target handler probe'
    $teleportCoordinates = @($report.probes | Where-Object id -eq 'skillTeleportCoordinates')
    Assert-True ($teleportCoordinates.Count -eq 1 -and
        $teleportCoordinates[0].offsetDistance -gt 0.5 -and
        $teleportCoordinates[0].movedDistance -eq 0) 'teleport coordinate evidence probe'
    $random = @($report.probes | Where-Object id -eq 'random-spawn-distribution')
    Assert-True ($random.Count -eq 4) 'random sample records'
    Assert-True ($random[0].status -eq 'insufficient-sample' -and $random[0].sampleCount -eq 99) 'under-sampled record'
    Assert-True ($random[1].status -eq 'passed' -and $random[1].sampleCount -eq 100) 'in-tolerance random record'
    Assert-True ($random[2].status -eq 'passed' -and $random[2].maxDeviation -eq $random[2].tolerance) 'inclusive tolerance boundary'
    Assert-True ($random[3].status -eq 'failed' -and $random[3].maxDeviation -gt $random[3].tolerance) 'over-tolerance random record'

    $syntheticDeathLootTemp = Join-Path $temp 'synthetic-death-loot-fixture'
    New-Item -ItemType Directory -Path (Join-Path $syntheticDeathLootTemp 'logs') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $syntheticDeathLootTemp 'logs/latest.log') -Encoding utf8 -Value @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: [helsteraMobs] [自检] death-loot items=1 conditional-drop=true rejected-drop-absent=true dynamic-chance-drop=true experience=10',
        '[Server thread/INFO]: Done (1.000s)! For help, type "help"'
    )
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $syntheticDeathLootTemp | Out-Null
    $syntheticDeathLootReportPath = Join-Path $syntheticDeathLootTemp 'integration-report.json'
    $syntheticDeathLootReport = Get-Content -LiteralPath $syntheticDeathLootReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($deathLootProbeId in @('deathLootItems', 'deathLootExperience', 'deathLootConditions',
            'deathLootDynamicChance', 'deathLootTriggerConditions', 'deathLootGeneratedLines',
            'deathLootBonuses', 'deathLootImportedDropTable')) {
        $deathLootProbe = @($syntheticDeathLootReport.probes | Where-Object id -eq $deathLootProbeId)
        Assert-True ($deathLootProbe.Count -eq 1 -and $deathLootProbe[0].status -eq 'not-observed') "synthetic death-loot does not satisfy $deathLootProbeId"
        Assert-True ($syntheticDeathLootReport.expectedProbeIds -contains $deathLootProbeId) "synthetic death-loot keeps $deathLootProbeId in the required catalog"
    }
    $syntheticDeathLootRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $syntheticDeathLootReportPath `
            -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1'
    } catch {
        $syntheticDeathLootRejected = $_.Exception.Message -match 'deathLootItems=not-observed' -and
            $_.Exception.Message -match 'deathLootExperience=not-observed' -and
            $_.Exception.Message -match 'deathLootConditions=not-observed' -and
            $_.Exception.Message -match 'deathLootDynamicChance=not-observed' -and
            $_.Exception.Message -match 'deathLootTriggerConditions=not-observed' -and
            $_.Exception.Message -match 'deathLootGeneratedLines=not-observed' -and
            $_.Exception.Message -match 'deathLootBonuses=not-observed' -and
            $_.Exception.Message -match 'deathLootImportedDropTable=not-observed'
    }
    Assert-True $syntheticDeathLootRejected 'strict gate rejects synthetic-only death-loot evidence'

    $strictReportPath = Join-Path $temp 'strict-report.json'
    @{ probes = @(
        @{ id = 'positive'; status = 'passed' },
        @{ id = 'negative-control'; status = 'observed-rejected' }
    ) } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $strictReportPath -Encoding utf8
    & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $strictReportPath
    $strictReportPath = Join-Path $temp 'incomplete-report.json'
    @{ probes = @(
        @{ id = 'missing-paper-evidence'; status = 'not-observed' },
        @{ id = 'under-sampled'; status = 'insufficient-sample' },
        @{ id = 'out-of-tolerance'; status = 'failed' }
    ) } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $strictReportPath -Encoding utf8
    $strictRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $strictReportPath
    } catch {
        $strictRejected = $_.Exception.Message -match 'missing-paper-evidence=not-observed' -and
            $_.Exception.Message -match 'under-sampled=insufficient-sample' -and
            $_.Exception.Message -match 'out-of-tolerance=failed'
    }
    Assert-True $strictRejected 'strict gate rejects missing, under-sampled, and failed Paper evidence'

    $targetGateReportPath = Join-Path $temp 'target-gate-report.json'
    @{
        schemaVersion = 1
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        fixtureDirectory = 'target-gate'
        evidenceMode = 'release'
        paperVersion = '1.21.1-test'
        paperApiVersion = '1.21.1-test-api'
        pluginEnabled = $true
        log = 'logs/latest.log'
        expectedProbeIds = @('positive')
        probes = @(@{ id = 'positive'; status = 'passed'; observations = @('ok') })
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $targetGateReportPath -Encoding utf8
    & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $targetGateReportPath `
        -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1'
    foreach ($invalidStatus in @('unknown', '')) {
        $invalidStatusReport = Get-Content -LiteralPath $targetGateReportPath -Raw -Encoding utf8 | ConvertFrom-Json
        $invalidStatusReport.probes[0].status = $invalidStatus
        $invalidStatusPath = Join-Path $temp 'invalid-status-report.json'
        $invalidStatusReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $invalidStatusPath -Encoding utf8
        $invalidStatusRejected = $false
        try {
            & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $invalidStatusPath -RequireTargetPaperVersion
        } catch { $invalidStatusRejected = $_.Exception.Message -match 'invalid probe status' }
        Assert-True $invalidStatusRejected 'strict gate rejects unknown or missing probe status'
    }
    $missingPluginHashRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $targetGateReportPath `
            -ExpectedPluginSha256 $testPluginHash
    } catch { $missingPluginHashRejected = $_.Exception.Message -match 'plugin SHA-256' }
    Assert-True $missingPluginHashRejected 'strict gate rejects missing plugin SHA-256'
    $targetGateWithHash = Get-Content -LiteralPath $targetGateReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $targetGateWithHash | Add-Member -NotePropertyName pluginSha256 -NotePropertyValue $testPluginHash
    $targetGateWithHash | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $targetGateReportPath -Encoding utf8
    & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $targetGateReportPath `
        -RequireTargetPaperVersion -ExpectedPluginSha256 $testPluginHash

    $missingFullCatalogRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $targetGateReportPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $missingFullCatalogRejected = $_.Exception.Message -match 'required release probe'
    }
    Assert-True $missingFullCatalogRejected 'full release gate rejects omitted Potion probes'
    $fullCatalogReport = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'reports/paper-1.21.1-imported-drop-contract-20261102.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $fullCatalogReport.expectedProbeIds += 'importedIsChildCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedIsChildCondition'; status = 'passed'; observations = @('skill-actions is-child-import-condition=true adult-parent-positive=true adult-not-baby=true unrelated-baby-not-child=true baby-positive=true')
    }
    $fullCatalogAiSelector = @($fullCatalogReport.probes | Where-Object id -eq 'aiSelectorCore')
    if ($fullCatalogAiSelector.Count -ne 1) { throw 'Synthetic full catalog report must contain aiSelectorCore.' }
    $fullCatalogAiSelector[0].observations = @($fullCatalogAiSelector[0].observations) +
        @('ai-selector-core targeter-syntax=true')
    $fullCatalogAgeOptions = @($fullCatalogReport.probes | Where-Object id -eq 'ageOptions')
    if ($fullCatalogAgeOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain ageOptions.' }
    $fullCatalogAgeOptions[0].observations = @(
        'age-options result=true age=-24000 baby=true adult=true age-lock=true zombie-baby=true')
    $fullCatalogReport.expectedProbeIds += 'inCombatCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'inCombatCondition'; status = 'passed'; observations = @('in-combat-condition managed-path=bukkit-mob-target managed-positive=true managed-no-target-rejected=true managed-restored=true native-positive=true native-no-target-rejected=true native-restored=true')
    }
    $fullCatalogReport.expectedProbeIds += 'isInCinematicCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'isInCinematicCondition'; status = 'passed'; observations = @('is-in-cinematic-condition player-open=true player-out-of-range-rejected=true non-player-rejected=true closed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'endermanCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'endermanCondition'; status = 'passed'; observations = @('enderman-condition-details positive=true negative=true')
    }
    $fullCatalogReport.expectedProbeIds += 'aerialCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'aerialCondition'; status = 'passed'; observations = @('aerial-condition-details positive=true alias-positive=true negative=true ground-entity=player ground=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedAerialCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedAerialCondition'; status = 'passed'; observations = @('imported-aerial-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true ground=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedColorCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedColorCondition'; status = 'passed'; observations = @('imported-color-condition red-mapped=true blue-alias-mapped=true positive=true negative=true entity-color=RED')
    }
    $fullCatalogReport.expectedProbeIds += 'importedHasEnchantmentCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedHasEnchantmentCondition'; status = 'passed'; observations = @('imported-has-enchantment-condition primary-mapped=true alias-mapped=true positive=true any-positive=true level-rejected=true type-rejected=true restored=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedTriggerItemTypeCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedTriggerItemTypeCondition'; status = 'passed'; observations = @('imported-trigger-item-type-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true constructed-context=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedTriggerBlockTypeCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedTriggerBlockTypeCondition'; status = 'passed'; observations = @('imported-trigger-block-type-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true state-rejected=true constructed-context=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedOriginLocationCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedOriginLocationCondition'; status = 'passed'; observations = @('imported-origin-location-condition primary-mapped=true alias-mapped=true positive=true exact-positive=true negative=true invalid-rejected=true constructed-context=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedMaterialCooldownCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedMaterialCooldownCondition'; status = 'passed'; observations = @('imported-material-cooldown-condition primary-mapped=true alias-mapped=true positive=true wrong-material-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedItemRechargingCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedItemRechargingCondition'; status = 'passed'; observations = @('imported-item-recharging-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true non-human-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedIsFrozenCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedIsFrozenCondition'; status = 'passed'; observations = @('imported-frozen-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true thawed=true alias-thawed=true invalid-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'spawningDistanceClientWalk'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'spawningDistanceClientWalk'; status = 'passed'; observations = @('spawning-distance-client-walk result=true player-displacement=14.25 player-entity-distance=12.25 instance-removed=true entity-removed=true despawn-elapsed-ticks=3 decision-period-ticks=10')
    }
    $fullCatalogReport.expectedProbeIds += 'webSpawnerReload'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'webSpawnerReload'; status = 'passed'; observations = @('web-reload invalid=false restored=true server-thread=true')
    }
    foreach ($id in @('null_condition_base', 'null_condition_merged', 'null_condition_override')) {
        $fullCatalogReport.expectedProbeIds += "spawner:$id"
        $accepted = $id -eq 'null_condition_override'
        $status = if ($accepted) { 'passed' } else { 'observed-rejected' }
        $observation = if ($accepted) {
            "spawner-probe id=$id mode=safe-override requested=true failure=null"
        } else {
            "spawner-probe id=$id mode=safe-override requested=false failure=conditions rejected location"
        }
        $fullCatalogReport.probes += [pscustomobject]@{
            id = "spawner:$id"; status = $status; observations = @($observation)
        }
    }
    $fullCatalogReport.expectedProbeIds += 'immunityImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'immunityImported'; status = 'passed'; observations = @('immunity-imported result=true reduced=true condition-rejected=true invalid-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'immunityNativeInvalid'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'immunityNativeInvalid'; status = 'passed'; observations = @('immunity-native-invalid result=true false-rejected=true null-rejected=true unknown-rejected=true null-modifier-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'immunityInvalidFields'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'immunityInvalidFields'; status = 'passed'; observations = @('immunity-invalid-fields null-immunity=true null-modifier-field=true unknown-modifier-field=true invalid-immune=true null-immune=true')
    }
    $fullCatalogReport.expectedProbeIds += 'immunityPreservation'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'immunityPreservation'; status = 'passed'; observations = @('immunity-preservation nested-preserved=true nested-closed=true list-null-preserved=true list-null-closed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'entityAngryPollination'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'entityAngryPollination'; status = 'passed'; observations = @('entity-angry-pollination result=true bee-angry-positive=true bee-angry-negative=true wolf-angry-positive=true wolf-angry-negative=true vex-angry-positive=true vex-angry-negative=true bee-pollinated-positive=true bee-pollinated-negative=true unsupported-angry-rejected=true missing-context-rejected=true aliases=true')
    }
    $fullCatalogReport.expectedProbeIds += 'entityShearedStates'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'entityShearedStates'; status = 'passed'; observations = @('entity-sheared-states result=true sheep-self-positive=true sheep-target-positive=true sheep-self-negative=true sheep-target-negative=true bogged-self-positive=true bogged-target-positive=true bogged-self-negative=true bogged-target-negative=true snow-golem-self-positive=true snow-golem-target-positive=true snow-golem-self-negative=true snow-golem-target-negative=true unsupported-rejected=true missing-context-rejected=true aliases=true')
    }
    $fullCatalogReport.expectedProbeIds += 'conditionArguments'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'conditionArguments'; status = 'passed'; observations = @('condition-arguments living-rejected=true baby-valid=true baby-rejected=true frozen-valid=true frozen-rejected=true warnings=true')
    }
    $fullCatalogReport.expectedProbeIds += 'conditionStateArguments'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'conditionStateArguments'; status = 'passed'; observations = @('condition-state-arguments valid=true movement-rejected=true category-rejected=true alive-rejected=true negated-rejected=true preserved=true warnings=true')
    }
    $fullCatalogReport.expectedProbeIds += 'targetSneakingMob'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'targetSneakingMob'; status = 'passed'; observations = @('target-sneaking-mob false-rejected=true true-passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'timeWeatherArguments'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'timeWeatherArguments'; status = 'passed'; observations = @('time-weather-arguments time-valid=true time-opposite=true weather-valid=true weather-opposite=true time-rejected=true rain-rejected=true negated-rejected=true preserved=true warnings=true')
    }
    $fullCatalogReport.expectedProbeIds += 'snowingCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'snowingCondition'; status = 'passed'; observations = @('snowing-condition result=true target-location-isolated=true target-positive=true alias-positive=true warm-rejected=true clear-rejected=true missing-context-rejected=true invalid-arguments-rejected=true restored=true')
    }
    $fullCatalogReport.expectedProbeIds += 'insideOutsideCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'insideOutsideCondition'; status = 'passed'; observations = @('inside-outside-condition-details inside-positive=true inside-negative=true outside-positive=true outside-negative=true restored=true')
    }
    $fullCatalogReport.expectedProbeIds += 'visibilityStateArguments'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'visibilityStateArguments'; status = 'passed'; observations = @('visibility-state-arguments valid=true rejected=true preserved=true warnings=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropNullConditions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropNullConditions'; status = 'passed'; observations = @('drop-null-conditions rejected=true warnings=true sibling=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropNativeNullConditions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropNativeNullConditions'; status = 'passed'; observations = @('drop-native-null-conditions rejected=true sibling=true warnings=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropNativeNullNumeric'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropNativeNullNumeric'; status = 'passed'; observations = @('drop-native-null-numeric rejected=true fallback=true overrides=true warnings=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropNativeDynamic'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropNativeDynamic'; status = 'passed'; observations = @('drop-native-dynamic rejected=true warnings=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropNativeTier'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropNativeTier'; status = 'passed'; observations = @('drop-native-tier rejected=true warnings=true')
    }
    $fullCatalogReport.expectedProbeIds += 'targeterArgumentValidation'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'targeterArgumentValidation'; status = 'passed'; observations = @('targeter-argument-validation rejected=true chain=true control=true target-players=true ignore-players=true target-players-specific=true target-players-disabled=true invalid-filter=true target-ground=true ignore-ground=true target-water=true ignore-water=true target-flying=true ignore-flying=true target-invulnerable-disabled=true target-invulnerable-priority=true target-armorstands-disabled=true target-armorstands-priority=true target-animals-disabled=true target-animals-priority=true target-creatures-disabled=true target-creatures-priority=true target-villagers-disabled=true target-villagers-priority=true target-all=true target-creative=true ignore-creative=true target-creative-priority=true target-creative-disabled=true target-spectator=true ignore-spectator=true target-spectator-priority=true target-spectator-disabled=true')
    }
    $fullCatalogReport.expectedProbeIds += 'targeterIdentityFilter'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'targeterIdentityFilter'; status = 'passed'; observations = @('targeter-identity-filter managed=true target-mythic=true target-nonmythic=true ignore-mythic=true ignore-vanilla=true targetvanilla=true targetnonmythic=true targetnonmythic-false=true duplicate-rejected=true owner-target=true owner-target-disabled=true owner-ignore=true owner-priority=true faction-target=true faction-ignore=true same-faction=true same-faction-disabled=true faction-priority=true same-faction-world=true targetall-relations=true relation-options-rejected=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'locationTargeterDispatch'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'locationTargeterDispatch'; status = 'passed'; observations = @('location-targeter-dispatch valid=true invalid-rejected=true self-aliases=true self-eye=true caster-spawn=true spawn=true target-aliases=true trigger=true forward=true projectile-forward=true predicted=true')
    }
    $fullCatalogReport.expectedProbeIds += 'variableLocationDispatch'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'variableLocationDispatch'; status = 'passed'; observations = @('variable-location-dispatch valid=true invalid-rejected=true import-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'explosionLocationDispatch'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'explosionLocationDispatch'; status = 'passed'; observations = @('explosion-location-dispatch valid=true missing-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropItemLocation'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropItemLocation'; status = 'passed'; observations = @('drop-item-location valid=true invalid-rejected=true missing-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropItemImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropItemImported'; status = 'passed'; observations = @('drop-item-import valid=true missing-rejected=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropItemNaturalImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropItemNaturalImported'; status = 'passed'; observations = @('drop-item-natural-import exact=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropItemInlineImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropItemInlineImported'; status = 'passed'; observations = @('drop-item-inline-import valid=true invalid-preserved=true dynamic=true missing-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropItemListImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropItemListImported'; status = 'passed'; observations = @('drop-item-list-import valid=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedMessageClient'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedMessageClient'; status = 'passed'; observations = @('imported-message-client dispatched=true client-exact=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedEscapedMessageClient'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedEscapedMessageClient'; status = 'passed'; observations = @('imported-escaped-message-client dispatched=true client-exact=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedSkillReference'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedSkillReference'; status = 'passed'; observations = @('imported-skill-reference valid=true rejected=true preserved=true warnings=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedSkillTiming'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedSkillTiming'; status = 'passed'; observations = @('imported-skill-timing submitted=true before-delay=true first=true repeated=true invalid-rejected=true preserved=true warnings=true duplicate-rejected=true mechanic-preserved=true positional-chance=true positional-condition=true suffix-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'unroutedEventSuffix'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'unroutedEventSuffix'; status = 'passed'; observations = @('unrouted-event-suffix rejected=true warnings=true')
    }
    $fullCatalogReport.expectedProbeIds += 'eventSuffixVariants'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'eventSuffixVariants'; status = 'passed'; observations = @('event-suffix-variants rejected=true warnings=true literal=true')
    }
    $fullCatalogReport.expectedProbeIds += 'orphanSuffix'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'orphanSuffix'; status = 'passed'; observations = @('orphan-suffix preserved=true warnings=true rejected=true unchanged=true')
    }
    $fullCatalogReport.expectedProbeIds += 'conditionFailureClosed'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'conditionFailureClosed'; status = 'passed'; observations = @('condition-failure-closed bound=true blocked=true control=true')
    }
    $fullCatalogReport.expectedProbeIds += 'namedConditionFailureClosed'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'namedConditionFailureClosed'; status = 'passed'; observations = @('named-condition-failure-closed bound=true blocked=true control=true')
    }
    $fullCatalogReport.expectedProbeIds += 'namedSkillReload'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'namedSkillReload'; status = 'passed'; observations = @('named-skill-reload bound=true initial=true blocked=true restored=true')
    }
    $fullCatalogReport.expectedProbeIds += 'namedSkillReference'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'namedSkillReference'; status = 'passed'; observations = @('named-skill-reference forward=true missing-rejected=true cycle-blocked=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropNativeTierRange'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropNativeTierRange'; status = 'passed'; observations = @('drop-native-tier-range valid=true fallback=true warnings=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropUnsafeReferences'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropUnsafeReferences'; status = 'passed'; observations = @('drop-unsafe-references warnings=true stale-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropMergedNull'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropMergedNull'; status = 'passed'; observations = @('drop-merged-null rejected=true warnings=true stale-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'dropChanceImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'dropChanceImported'; status = 'passed'; observations = @('drop-chance-imported rejected=true warnings=true')
    }
    $fullCatalogReport.expectedProbeIds += 'skillLifecycleCleanup'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'skillLifecycleCleanup'; status = 'passed'; observations = @('synthetic lifecycle evidence')
    }
    $fullCatalogReport.expectedProbeIds += 'skillTreeQueueSnapshot'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'skillTreeQueueSnapshot'; status = 'passed'; observations = @('synthetic queue snapshot evidence')
    }
    $fullCatalogReport.expectedProbeIds += 'healthImportedCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'healthImportedCondition'; status = 'passed'; observations = @('health-imported result=true comparisons=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'healthRangeImportedCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'healthRangeImportedCondition'; status = 'passed'; observations = @('health-range-imported result=true ranges=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'comparisonBoundaryImportedCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'comparisonBoundaryImportedCondition'; status = 'passed'; observations = @('comparison-boundary-imported result=true boundaries=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'targetHealthImportedCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'targetHealthImportedCondition'; status = 'passed'; observations = @('target-health-imported result=true comparisons=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'environmentImportedCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'environmentImportedCondition'; status = 'passed'; observations = @('environment-imported result=true valid=true invalid-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'invalidConditionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'invalidConditionsImported'; status = 'passed'; observations = @('synthetic invalid condition evidence')
    }
    $fullCatalogReport.expectedProbeIds += 'biomeImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'biomeImported'; status = 'passed'; observations = @('synthetic imported biome test evidence')
    }
    $fullCatalogReport.expectedProbeIds += 'expTierDeath'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'expTierDeath'; status = 'passed'; observations = @('exp-tier-death result=true low=2 qualified=7 low-dead=true qualified-dead=true low-bread=4 qualified-bread=4')
    }
    $fullCatalogPath = Join-Path $temp 'full-catalog-report.json'
    $originalPluginEnabled = $fullCatalogReport.pluginEnabled
    $fullCatalogReport.pluginEnabled = $false
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $disabledPluginRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireFullReleaseCatalog
    } catch {
        $disabledPluginRejected = $_.Exception.Message -match 'enabled successfully'
    }
    Assert-True $disabledPluginRejected 'full release catalog alone rejects a disabled plugin'
    $fullCatalogReport.pluginEnabled = $originalPluginEnabled
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $endermanProbe = $fullCatalogReport.probes | Where-Object id -eq 'endermanCondition'
    $endermanObservations = $endermanProbe.observations
    $endermanProbe.observations = @('enderman-condition-details positive=true negative=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badEndermanRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badEndermanRejected = $_.Exception.Message -match 'required observation for endermanCondition' }
    Assert-True $badEndermanRejected 'full release gate rejects a false Enderman negative control'
    $endermanProbe.observations = $endermanObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'endermanCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'endermanCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingEndermanRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingEndermanRejected = $_.Exception.Message -match 'endermanCondition' }
    Assert-True $missingEndermanRejected 'full release gate rejects missing Enderman condition evidence'
    $fullCatalogReport.expectedProbeIds += 'endermanCondition'
    $fullCatalogReport.probes += $endermanProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $ageOptionsProbe = $fullCatalogReport.probes | Where-Object id -eq 'ageOptions'
    $ageOptionsObservations = $ageOptionsProbe.observations
    $ageOptionsProbe.observations = @(
        'age-options result=true age=-24000 baby=true adult=true age-lock=true zombie-baby=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badAgeOptionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badAgeOptionsRejected = $_.Exception.Message -match 'required observation for ageOptions' }
    Assert-True $badAgeOptionsRejected 'full release gate rejects a false zombie Baby observation'
    $ageOptionsProbe.observations = $ageOptionsObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $aerialProbe = $fullCatalogReport.probes | Where-Object id -eq 'aerialCondition'
    $aerialObservations = $aerialProbe.observations
    $aerialProbe.observations = @('aerial-condition-details positive=true alias-positive=true negative=false ground=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badAerialRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badAerialRejected = $_.Exception.Message -match 'required observation for aerialCondition' }
    Assert-True $badAerialRejected 'full release gate rejects a false Aerial negative control'
    $aerialProbe.observations = $aerialObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'aerialCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'aerialCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingAerialRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingAerialRejected = $_.Exception.Message -match 'aerialCondition' }
    Assert-True $missingAerialRejected 'full release gate rejects missing Aerial condition evidence'
    $fullCatalogReport.expectedProbeIds += 'aerialCondition'
    $fullCatalogReport.probes += $aerialProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $importedAerialProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedAerialCondition'
    $importedAerialObservations = $importedAerialProbe.observations
    $importedAerialProbe.observations = @('imported-aerial-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=false ground=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedAerialRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedAerialRejected = $_.Exception.Message -match 'required observation for importedAerialCondition' }
    Assert-True $badImportedAerialRejected 'full release gate rejects a false imported Aerial negative control'
    $importedAerialProbe.observations = $importedAerialObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedAerialCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedAerialCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedAerialRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedAerialRejected = $_.Exception.Message -match 'importedAerialCondition' }
    Assert-True $missingImportedAerialRejected 'full release gate rejects missing imported Aerial evidence'
    $fullCatalogReport.expectedProbeIds += 'importedAerialCondition'
    $fullCatalogReport.probes += $importedAerialProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $importedColorProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedColorCondition'
    $importedColorObservations = $importedColorProbe.observations
    $importedColorProbe.observations = @('imported-color-condition red-mapped=true blue-alias-mapped=true positive=true negative=false entity-color=RED')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedColorRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedColorRejected = $_.Exception.Message -match 'required observation for importedColorCondition' }
    Assert-True $badImportedColorRejected 'full release gate rejects a false imported Color negative control'
    $importedColorProbe.observations = $importedColorObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedColorCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedColorCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedColorRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedColorRejected = $_.Exception.Message -match 'importedColorCondition' }
    Assert-True $missingImportedColorRejected 'full release gate rejects missing imported Color evidence'
    $fullCatalogReport.expectedProbeIds += 'importedColorCondition'
    $fullCatalogReport.probes += $importedColorProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $importedCooldownProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedMaterialCooldownCondition'
    $importedCooldownObservations = $importedCooldownProbe.observations
    $importedCooldownProbe.observations = @('imported-material-cooldown-condition primary-mapped=true alias-mapped=true positive=true wrong-material-rejected=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedCooldownRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedCooldownRejected = $_.Exception.Message -match 'required observation for importedMaterialCooldownCondition' }
    Assert-True $badImportedCooldownRejected 'full release gate rejects a false imported cooldown negative control'
    $importedCooldownProbe.observations = $importedCooldownObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedMaterialCooldownCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedMaterialCooldownCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedCooldownRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedCooldownRejected = $_.Exception.Message -match 'importedMaterialCooldownCondition' }
    Assert-True $missingImportedCooldownRejected 'full release gate rejects missing imported cooldown evidence'
    $fullCatalogReport.expectedProbeIds += 'importedMaterialCooldownCondition'
    $fullCatalogReport.probes += $importedCooldownProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $importedEnchantmentProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedHasEnchantmentCondition'
    $importedEnchantmentObservations = $importedEnchantmentProbe.observations
    $importedEnchantmentProbe.observations = @('imported-has-enchantment-condition primary-mapped=true alias-mapped=true positive=true any-positive=true level-rejected=false type-rejected=true restored=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedEnchantmentRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedEnchantmentRejected = $_.Exception.Message -match 'required observation for importedHasEnchantmentCondition' }
    Assert-True $badImportedEnchantmentRejected 'full release gate rejects a failed imported HasEnchantment level check'
    $importedEnchantmentProbe.observations = $importedEnchantmentObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedHasEnchantmentCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedHasEnchantmentCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedEnchantmentRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedEnchantmentRejected = $_.Exception.Message -match 'importedHasEnchantmentCondition' }
    Assert-True $missingImportedEnchantmentRejected 'full release gate rejects missing imported HasEnchantment evidence'
    $fullCatalogReport.expectedProbeIds += 'importedHasEnchantmentCondition'
    $fullCatalogReport.probes += $importedEnchantmentProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    foreach ($importedTriggerProbeId in @('importedTriggerItemTypeCondition', 'importedTriggerBlockTypeCondition')) {
        $importedTriggerProbe = $fullCatalogReport.probes | Where-Object id -eq $importedTriggerProbeId
        $importedTriggerObservations = $importedTriggerProbe.observations
        $importedTriggerProbe.observations = @(([string]$importedTriggerObservations[0]) -replace 'negative=true', 'negative=false')
        $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
        $badImportedTriggerRejected = $false
        try {
            & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
                -RequireTargetPaperVersion -RequireFullReleaseCatalog
        } catch { $badImportedTriggerRejected = $_.Exception.Message -match "required observation for $importedTriggerProbeId" }
        Assert-True $badImportedTriggerRejected "full release gate rejects a failed $importedTriggerProbeId negative control"
        $importedTriggerProbe.observations = $importedTriggerObservations
        $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne $importedTriggerProbeId })
        $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne $importedTriggerProbeId })
        $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
        $missingImportedTriggerRejected = $false
        try {
            & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
                -RequireTargetPaperVersion -RequireFullReleaseCatalog
        } catch { $missingImportedTriggerRejected = $_.Exception.Message -match $importedTriggerProbeId }
        Assert-True $missingImportedTriggerRejected "full release gate rejects missing $importedTriggerProbeId evidence"
        $fullCatalogReport.expectedProbeIds += $importedTriggerProbeId
        $fullCatalogReport.probes += $importedTriggerProbe
        $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    }
    $importedOriginProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedOriginLocationCondition'
    $importedOriginObservations = $importedOriginProbe.observations
    $importedOriginProbe.observations = @(([string]$importedOriginObservations[0]) -replace 'exact-positive=true', 'exact-positive=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedOriginRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedOriginRejected = $_.Exception.Message -match 'required observation for importedOriginLocationCondition' }
    Assert-True $badImportedOriginRejected 'full release gate rejects a failed imported OriginLocation exact-coordinate control'
    $importedOriginProbe.observations = $importedOriginObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedOriginLocationCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedOriginLocationCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedOriginRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedOriginRejected = $_.Exception.Message -match 'importedOriginLocationCondition' }
    Assert-True $missingImportedOriginRejected 'full release gate rejects missing imported OriginLocation evidence'
    $fullCatalogReport.expectedProbeIds += 'importedOriginLocationCondition'
    $fullCatalogReport.probes += $importedOriginProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingReviveRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $missingReviveRejected = $_.Exception.Message -match 'reviveNatural'
    }
    Assert-True $missingReviveRejected 'full release gate rejects missing natural revive evidence'
    $fullCatalogReport.expectedProbeIds += 'reviveNatural'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'reviveNatural'; status = 'passed'; observations = @('synthetic revive test evidence')
    }
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingLateReviveRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $missingLateReviveRejected = $_.Exception.Message -match 'reviveLate'
    }
    Assert-True $missingLateReviveRejected 'full release gate rejects missing late cancellation evidence'
    $fullCatalogReport.expectedProbeIds += 'reviveLate'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'reviveLate'; status = 'passed'; observations = @('synthetic late cancellation test evidence')
    }
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingDamageRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $missingDamageRejected = $_.Exception.Message -match 'damageImported'
    }
    Assert-True $missingDamageRejected 'full release gate rejects missing imported damage evidence'
    $fullCatalogReport.expectedProbeIds += 'damageImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'damageImported'; status = 'passed'; observations = @('synthetic imported damage test evidence')
    }
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'biomeImported' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'biomeImported' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingBiomeRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $missingBiomeRejected = $_.Exception.Message -match 'biomeImported'
    }
    Assert-True $missingBiomeRejected 'full release gate rejects missing imported biome evidence'
    $fullCatalogReport.expectedProbeIds += 'biomeImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'biomeImported'; status = 'passed'; observations = @('synthetic imported biome test evidence')
    }
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'invalidConditionsImported' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'invalidConditionsImported' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingInvalidConditionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $missingInvalidConditionsRejected = $_.Exception.Message -match 'invalidConditionsImported'
    }
    Assert-True $missingInvalidConditionsRejected 'full release gate rejects missing invalid condition evidence'
    $fullCatalogReport.expectedProbeIds += 'invalidConditionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'invalidConditionsImported'; status = 'passed'; observations = @('synthetic invalid condition evidence')
    }
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'skillTreeQueueSnapshot' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'skillTreeQueueSnapshot' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingQueueRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingQueueRejected = $_.Exception.Message -match 'skillTreeQueueSnapshot' }
    Assert-True $missingQueueRejected 'full release gate rejects missing queue snapshot evidence'
    $fullCatalogReport.expectedProbeIds += 'skillTreeQueueSnapshot'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'skillTreeQueueSnapshot'; status = 'passed'; observations = @('synthetic queue snapshot evidence')
    }
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'healthImportedCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'healthImportedCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingHealthRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingHealthRejected = $_.Exception.Message -match 'healthImportedCondition' }
    Assert-True $missingHealthRejected 'full release gate rejects missing imported health evidence'
    $fullCatalogReport.expectedProbeIds += 'healthImportedCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'healthImportedCondition'; status = 'passed'; observations = @('health-imported result=true comparisons=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'healthRangeImportedCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'healthRangeImportedCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingRangeRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingRangeRejected = $_.Exception.Message -match 'healthRangeImportedCondition' }
    Assert-True $missingRangeRejected 'full release gate rejects missing imported health range evidence'
    $fullCatalogReport.expectedProbeIds += 'healthRangeImportedCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'healthRangeImportedCondition'; status = 'passed'; observations = @('health-range-imported result=true ranges=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'comparisonBoundaryImportedCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'comparisonBoundaryImportedCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingComparisonRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingComparisonRejected = $_.Exception.Message -match 'comparisonBoundaryImportedCondition' }
    Assert-True $missingComparisonRejected 'full release gate rejects missing imported comparison boundary evidence'
    $fullCatalogReport.expectedProbeIds += 'comparisonBoundaryImportedCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'comparisonBoundaryImportedCondition'; status = 'passed'; observations = @('comparison-boundary-imported result=true boundaries=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'targetHealthImportedCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'targetHealthImportedCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingTargetHealthRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingTargetHealthRejected = $_.Exception.Message -match 'targetHealthImportedCondition' }
    Assert-True $missingTargetHealthRejected 'full release gate rejects missing imported target health evidence'
    $fullCatalogReport.expectedProbeIds += 'targetHealthImportedCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'targetHealthImportedCondition'; status = 'passed'; observations = @('target-health-imported result=true comparisons=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'environmentImportedCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'environmentImportedCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingEnvironmentRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingEnvironmentRejected = $_.Exception.Message -match 'environmentImportedCondition' }
    Assert-True $missingEnvironmentRejected 'full release gate rejects missing imported environment evidence'
    $fullCatalogReport.expectedProbeIds += 'environmentImportedCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'environmentImportedCondition'; status = 'passed'; observations = @('environment-imported result=true valid=true invalid-rejected=true')
    }
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
        -RequireTargetPaperVersion -RequireFullReleaseCatalog
    $spawningDistanceProbe = $fullCatalogReport.probes | Where-Object id -eq 'spawningDistanceClientWalk'
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'spawningDistanceClientWalk' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'spawningDistanceClientWalk' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingSpawningDistanceRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingSpawningDistanceRejected = $_.Exception.Message -match 'spawningDistanceClientWalk' }
    Assert-True $missingSpawningDistanceRejected 'full release gate rejects missing SpawningDistance client evidence'
    $fullCatalogReport.expectedProbeIds += 'spawningDistanceClientWalk'
    $fullCatalogReport.probes += $spawningDistanceProbe
    $originalSpawningDistanceObservations = $spawningDistanceProbe.observations
    $spawningDistanceProbe.observations = @('spawning-distance-client-walk result=true player-displacement=14.25 player-entity-distance=12.25 instance-removed=true entity-removed=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteSpawningDistanceRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteSpawningDistanceRejected = $_.Exception.Message -match 'required observation for spawningDistanceClientWalk' }
    Assert-True $incompleteSpawningDistanceRejected 'full release gate rejects incomplete SpawningDistance client evidence'
    foreach ($distance in @('7.99', '8', '8.0')) {
        $spawningDistanceProbe.observations = @(
            "spawning-distance-client-walk result=true player-displacement=14.25 player-entity-distance=$distance instance-removed=true entity-removed=true")
        $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
        $withinRangeRejected = $false
        try {
            & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath -RequireTargetPaperVersion -RequireFullReleaseCatalog
        } catch { $withinRangeRejected = $_.Exception.Message -match 'required observation for spawningDistanceClientWalk' }
        Assert-True $withinRangeRejected "full release gate rejects SpawningDistance distance $distance"
    }
    $spawningDistanceProbe.observations = @(
        'spawning-distance-client-walk result=true player-displacement=14.25 player-entity-distance=8.0001 instance-removed=true entity-removed=true despawn-elapsed-ticks=3 decision-period-ticks=10')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath -RequireTargetPaperVersion -RequireFullReleaseCatalog
    $spawningDistanceProbe.observations = @(
        'spawning-distance-client-walk result=true player-displacement=14.25 player-entity-distance=12.25 instance-removed=true entity-removed=true despawn-elapsed-ticks=11 decision-period-ticks=10')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $lateDespawnRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $lateDespawnRejected = $_.Exception.Message -match 'tick bound' }
    Assert-True $lateDespawnRejected 'full release gate rejects SpawningDistance removal beyond its AI decision period'
    $spawningDistanceProbe.observations = $originalSpawningDistanceObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $inCombatProbe = $fullCatalogReport.probes | Where-Object id -eq 'inCombatCondition'
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'inCombatCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'inCombatCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingInCombatRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingInCombatRejected = $_.Exception.Message -match 'inCombatCondition' }
    Assert-True $missingInCombatRejected 'full release gate rejects missing in-combat Paper evidence'
    $fullCatalogReport.expectedProbeIds += 'inCombatCondition'
    $fullCatalogReport.probes += $inCombatProbe
    $originalInCombatObservations = $inCombatProbe.observations
    $inCombatProbe.observations = @('in-combat-condition managed-positive=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteInCombatRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteInCombatRejected = $_.Exception.Message -match 'required observation for inCombatCondition' }
    Assert-True $incompleteInCombatRejected 'full release gate rejects incomplete in-combat evidence'
    $inCombatProbe.observations = $originalInCombatObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $naturalDeathProbe = $fullCatalogReport.probes | Where-Object id -eq 'expNaturalDeath'
    $originalNaturalDeathObservations = $naturalDeathProbe.observations
    $naturalDeathProbe.observations = @('incomplete evidence')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badNaturalEvidenceRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireFullReleaseCatalog
    } catch { $badNaturalEvidenceRejected = $_.Exception.Message -match 'required observation for expNaturalDeath' }
    Assert-True $badNaturalEvidenceRejected 'full release gate rejects incomplete natural death experience evidence'
    $naturalDeathProbe.observations = $originalNaturalDeathObservations
    $immunityProbe = $fullCatalogReport.probes | Where-Object id -eq 'immunityImported'
    $originalImmunityObservations = $immunityProbe.observations
    $immunityProbe.observations = @('immunity-imported result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImmunityEvidenceRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImmunityEvidenceRejected = $_.Exception.Message -match 'required observation for immunityImported' }
    Assert-True $badImmunityEvidenceRejected 'full release gate rejects incomplete imported immunity evidence'
    $immunityProbe.observations = $originalImmunityObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'immunityImported' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'immunityImported' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImmunityRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImmunityRejected = $_.Exception.Message -match 'immunityImported' }
    Assert-True $missingImmunityRejected 'full release gate rejects missing imported immunity evidence'
    $fullCatalogReport.expectedProbeIds += 'immunityImported'
    $fullCatalogReport.probes += $immunityProbe
    $nativeImmunityProbe = $fullCatalogReport.probes | Where-Object id -eq 'immunityNativeInvalid'
    $nativeImmunityObservations = $nativeImmunityProbe.observations
    $nativeImmunityProbe.observations = @('immunity-native-invalid result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badNativeImmunityRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badNativeImmunityRejected = $_.Exception.Message -match 'required observation for immunityNativeInvalid' }
    Assert-True $badNativeImmunityRejected 'full release gate rejects incomplete native immunity evidence'
    $nativeImmunityProbe.observations = $nativeImmunityObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'immunityNativeInvalid' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'immunityNativeInvalid' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingNativeImmunityRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingNativeImmunityRejected = $_.Exception.Message -match 'immunityNativeInvalid' }
    Assert-True $missingNativeImmunityRejected 'full release gate rejects missing native immunity evidence'
    $fullCatalogReport.expectedProbeIds += 'immunityNativeInvalid'
    $fullCatalogReport.probes += $nativeImmunityProbe
    $invalidFieldsProbe = $fullCatalogReport.probes | Where-Object id -eq 'immunityInvalidFields'
    $invalidFieldsObservations = $invalidFieldsProbe.observations
    $invalidFieldsProbe.observations = @('immunity-invalid-fields null-immunity=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badInvalidFieldsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badInvalidFieldsRejected = $_.Exception.Message -match 'required observation for immunityInvalidFields' }
    Assert-True $badInvalidFieldsRejected 'full release gate rejects incomplete invalid immunity field evidence'
    $invalidFieldsProbe.observations = $invalidFieldsObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'immunityInvalidFields' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'immunityInvalidFields' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingInvalidFieldsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingInvalidFieldsRejected = $_.Exception.Message -match 'immunityInvalidFields' }
    Assert-True $missingInvalidFieldsRejected 'full release gate rejects missing invalid immunity field evidence'
    $fullCatalogReport.expectedProbeIds += 'immunityInvalidFields'
    $fullCatalogReport.probes += $invalidFieldsProbe
    $preservationProbe = $fullCatalogReport.probes | Where-Object id -eq 'immunityPreservation'
    $preservationObservations = $preservationProbe.observations
    $preservationProbe.observations = @('immunity-preservation nested-preserved=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badPreservationRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badPreservationRejected = $_.Exception.Message -match 'required observation for immunityPreservation' }
    Assert-True $badPreservationRejected 'full release gate rejects incomplete immunity preservation evidence'
    $preservationProbe.observations = $preservationObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'immunityPreservation' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'immunityPreservation' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingPreservationRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingPreservationRejected = $_.Exception.Message -match 'immunityPreservation' }
    Assert-True $missingPreservationRejected 'full release gate rejects missing immunity preservation evidence'
    $fullCatalogReport.expectedProbeIds += 'immunityPreservation'
    $fullCatalogReport.probes += $preservationProbe
    $conditionArgumentsProbe = $fullCatalogReport.probes | Where-Object id -eq 'conditionArguments'
    $conditionArgumentsObservations = $conditionArgumentsProbe.observations
    $conditionArgumentsProbe.observations = @('condition-arguments living-rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badConditionArgumentsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badConditionArgumentsRejected = $_.Exception.Message -match 'required observation for conditionArguments' }
    Assert-True $badConditionArgumentsRejected 'full release gate rejects incomplete condition argument evidence'
    $conditionArgumentsProbe.observations = $conditionArgumentsObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'conditionArguments' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'conditionArguments' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingConditionArgumentsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingConditionArgumentsRejected = $_.Exception.Message -match 'conditionArguments' }
    Assert-True $missingConditionArgumentsRejected 'full release gate rejects missing condition argument evidence'
    $fullCatalogReport.expectedProbeIds += 'conditionArguments'
    $fullCatalogReport.probes += $conditionArgumentsProbe
    $stateArgumentsProbe = $fullCatalogReport.probes | Where-Object id -eq 'conditionStateArguments'
    $stateArgumentsObservations = $stateArgumentsProbe.observations
    $stateArgumentsProbe.observations = @('condition-state-arguments valid=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badStateArgumentsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badStateArgumentsRejected = $_.Exception.Message -match 'required observation for conditionStateArguments' }
    Assert-True $badStateArgumentsRejected 'full release gate rejects incomplete condition state evidence'
    $stateArgumentsProbe.observations = $stateArgumentsObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'conditionStateArguments' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'conditionStateArguments' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingStateArgumentsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingStateArgumentsRejected = $_.Exception.Message -match 'conditionStateArguments' }
    Assert-True $missingStateArgumentsRejected 'full release gate rejects missing condition state evidence'
    $fullCatalogReport.expectedProbeIds += 'conditionStateArguments'
    $fullCatalogReport.probes += $stateArgumentsProbe
    $sneakingProbe = $fullCatalogReport.probes | Where-Object id -eq 'targetSneakingMob'
    $sneakingObservations = $sneakingProbe.observations
    $sneakingProbe.observations = @('target-sneaking-mob false-rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badSneakingRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badSneakingRejected = $_.Exception.Message -match 'required observation for targetSneakingMob' }
    Assert-True $badSneakingRejected 'full release gate rejects incomplete target sneaking evidence'
    $sneakingProbe.observations = $sneakingObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'targetSneakingMob' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'targetSneakingMob' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingSneakingRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingSneakingRejected = $_.Exception.Message -match 'targetSneakingMob' }
    Assert-True $missingSneakingRejected 'full release gate rejects missing target sneaking evidence'
    $fullCatalogReport.expectedProbeIds += 'targetSneakingMob'
    $fullCatalogReport.probes += $sneakingProbe
    $timeWeatherProbe = $fullCatalogReport.probes | Where-Object id -eq 'timeWeatherArguments'
    $timeWeatherObservations = $timeWeatherProbe.observations
    $timeWeatherProbe.observations = @('time-weather-arguments time-valid=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badTimeWeatherRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badTimeWeatherRejected = $_.Exception.Message -match 'required observation for timeWeatherArguments' }
    Assert-True $badTimeWeatherRejected 'full release gate rejects incomplete time and weather evidence'
    $timeWeatherProbe.observations = $timeWeatherObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'timeWeatherArguments' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'timeWeatherArguments' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingTimeWeatherRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingTimeWeatherRejected = $_.Exception.Message -match 'timeWeatherArguments' }
    Assert-True $missingTimeWeatherRejected 'full release gate rejects missing time and weather evidence'
    $fullCatalogReport.expectedProbeIds += 'timeWeatherArguments'
    $fullCatalogReport.probes += $timeWeatherProbe
    $snowingProbe = $fullCatalogReport.probes | Where-Object id -eq 'snowingCondition'
    $snowingObservations = $snowingProbe.observations
    $snowingProbe.observations = @('snowing-condition result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badSnowingRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badSnowingRejected = $_.Exception.Message -match 'required observation for snowingCondition' }
    Assert-True $badSnowingRejected 'full release gate rejects incomplete snowing evidence'
    $snowingProbe.observations = $snowingObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'snowingCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'snowingCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingSnowingRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingSnowingRejected = $_.Exception.Message -match 'snowingCondition' }
    Assert-True $missingSnowingRejected 'full release gate rejects missing snowing evidence'
    $fullCatalogReport.expectedProbeIds += 'snowingCondition'
    $fullCatalogReport.probes += $snowingProbe
    $insideOutsideProbe = $fullCatalogReport.probes | Where-Object id -eq 'insideOutsideCondition'
    $insideOutsideObservations = $insideOutsideProbe.observations
    $insideOutsideProbe.observations = @('inside-outside-condition-details inside-positive=true inside-negative=true outside-positive=true outside-negative=false restored=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badInsideOutsideRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badInsideOutsideRejected = $_.Exception.Message -match 'required observation for insideOutsideCondition' }
    Assert-True $badInsideOutsideRejected 'full release gate rejects un-restored inside/outside evidence'
    $insideOutsideProbe.observations = $insideOutsideObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'insideOutsideCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'insideOutsideCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingInsideOutsideRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingInsideOutsideRejected = $_.Exception.Message -match 'insideOutsideCondition' }
    Assert-True $missingInsideOutsideRejected 'full release gate rejects missing inside/outside evidence'
    $fullCatalogReport.expectedProbeIds += 'insideOutsideCondition'
    $fullCatalogReport.probes += $insideOutsideProbe
    $visibilityProbe = $fullCatalogReport.probes | Where-Object id -eq 'visibilityStateArguments'
    $visibilityObservations = $visibilityProbe.observations
    $visibilityProbe.observations = @('visibility-state-arguments valid=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badVisibilityRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badVisibilityRejected = $_.Exception.Message -match 'required observation for visibilityStateArguments' }
    Assert-True $badVisibilityRejected 'full release gate rejects incomplete visibility state evidence'
    $visibilityProbe.observations = $visibilityObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'visibilityStateArguments' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'visibilityStateArguments' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingVisibilityRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingVisibilityRejected = $_.Exception.Message -match 'visibilityStateArguments' }
    Assert-True $missingVisibilityRejected 'full release gate rejects missing visibility state evidence'
    $fullCatalogReport.expectedProbeIds += 'visibilityStateArguments'
    $fullCatalogReport.probes += $visibilityProbe
    $nullDropProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropNullConditions'
    $nullDropObservations = $nullDropProbe.observations
    $nullDropProbe.observations = @('drop-null-conditions rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badNullDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badNullDropRejected = $_.Exception.Message -match 'required observation for dropNullConditions' }
    Assert-True $badNullDropRejected 'full release gate rejects incomplete null drop conditions evidence'
    $nullDropProbe.observations = $nullDropObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropNullConditions' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropNullConditions' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingNullDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingNullDropRejected = $_.Exception.Message -match 'dropNullConditions' }
    Assert-True $missingNullDropRejected 'full release gate rejects missing null drop conditions evidence'
    $fullCatalogReport.expectedProbeIds += 'dropNullConditions'
    $fullCatalogReport.probes += $nullDropProbe
    $unsafeDropProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropUnsafeReferences'
    $unsafeDropObservations = $unsafeDropProbe.observations
    $unsafeDropProbe.observations = @('drop-unsafe-references warnings=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badUnsafeDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badUnsafeDropRejected = $_.Exception.Message -match 'required observation for dropUnsafeReferences' }
    Assert-True $badUnsafeDropRejected 'full release gate rejects incomplete unsafe drop reference evidence'
    $unsafeDropProbe.observations = $unsafeDropObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropUnsafeReferences' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropUnsafeReferences' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingUnsafeDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingUnsafeDropRejected = $_.Exception.Message -match 'dropUnsafeReferences' }
    Assert-True $missingUnsafeDropRejected 'full release gate rejects missing unsafe drop reference evidence'
    $fullCatalogReport.expectedProbeIds += 'dropUnsafeReferences'
    $fullCatalogReport.probes += $unsafeDropProbe
    $nativeNullProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropNativeNullConditions'
    $nativeNullObservations = $nativeNullProbe.observations
    $nativeNullProbe.observations = @('drop-native-null-conditions rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badNativeNullRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badNativeNullRejected = $_.Exception.Message -match 'required observation for dropNativeNullConditions' }
    Assert-True $badNativeNullRejected 'full release gate rejects incomplete native null drop evidence'
    $nativeNullProbe.observations = $nativeNullObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropNativeNullConditions' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropNativeNullConditions' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingNativeNullRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingNativeNullRejected = $_.Exception.Message -match 'dropNativeNullConditions' }
    Assert-True $missingNativeNullRejected 'full release gate rejects missing native null drop evidence'
    $fullCatalogReport.expectedProbeIds += 'dropNativeNullConditions'
    $fullCatalogReport.probes += $nativeNullProbe
    $numericProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropNativeNullNumeric'
    $numericObservations = $numericProbe.observations
    $numericProbe.observations = @('drop-native-null-numeric rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badNumericRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badNumericRejected = $_.Exception.Message -match 'required observation for dropNativeNullNumeric' }
    Assert-True $badNumericRejected 'full release gate rejects incomplete native null numeric evidence'
    $numericProbe.observations = $numericObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropNativeNullNumeric' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropNativeNullNumeric' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingNumericRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingNumericRejected = $_.Exception.Message -match 'dropNativeNullNumeric' }
    Assert-True $missingNumericRejected 'full release gate rejects missing native null numeric evidence'
    $fullCatalogReport.expectedProbeIds += 'dropNativeNullNumeric'
    $fullCatalogReport.probes += $numericProbe
    $dynamicProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropNativeDynamic'
    $dynamicObservations = $dynamicProbe.observations
    $dynamicProbe.observations = @('drop-native-dynamic rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badDynamicRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badDynamicRejected = $_.Exception.Message -match 'required observation for dropNativeDynamic' }
    Assert-True $badDynamicRejected 'full release gate rejects incomplete native dynamic evidence'
    $dynamicProbe.observations = $dynamicObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropNativeDynamic' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropNativeDynamic' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingDynamicRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingDynamicRejected = $_.Exception.Message -match 'dropNativeDynamic' }
    Assert-True $missingDynamicRejected 'full release gate rejects missing native dynamic evidence'
    $fullCatalogReport.expectedProbeIds += 'dropNativeDynamic'
    $fullCatalogReport.probes += $dynamicProbe
    $tierProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropNativeTier'
    $tierObservations = $tierProbe.observations
    $tierProbe.observations = @('drop-native-tier rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badTierRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badTierRejected = $_.Exception.Message -match 'required observation for dropNativeTier' }
    Assert-True $badTierRejected 'full release gate rejects incomplete native tier evidence'
    $tierProbe.observations = $tierObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropNativeTier' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropNativeTier' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingTierRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingTierRejected = $_.Exception.Message -match 'dropNativeTier' }
    Assert-True $missingTierRejected 'full release gate rejects missing native tier evidence'
    $fullCatalogReport.expectedProbeIds += 'dropNativeTier'
    $fullCatalogReport.probes += $tierProbe
    $targeterArgumentsProbe = $fullCatalogReport.probes | Where-Object id -eq 'targeterArgumentValidation'
    $targeterArgumentsObservations = $targeterArgumentsProbe.observations
    $targeterArgumentsProbe.observations = @('targeter-argument-validation rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badTargeterArgumentsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badTargeterArgumentsRejected = $_.Exception.Message -match 'required observation for targeterArgumentValidation' }
    Assert-True $badTargeterArgumentsRejected 'full release gate rejects incomplete targeter argument evidence'
    $targeterArgumentsProbe.observations = $targeterArgumentsObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'targeterArgumentValidation' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'targeterArgumentValidation' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingTargeterArgumentsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingTargeterArgumentsRejected = $_.Exception.Message -match 'targeterArgumentValidation' }
    Assert-True $missingTargeterArgumentsRejected 'full release gate rejects missing targeter argument evidence'
    $fullCatalogReport.expectedProbeIds += 'targeterArgumentValidation'
    $fullCatalogReport.probes += $targeterArgumentsProbe
    $targeterIdentityProbe = $fullCatalogReport.probes | Where-Object id -eq 'targeterIdentityFilter'
    $targeterIdentityObservations = $targeterIdentityProbe.observations
    $targeterIdentityProbe.observations = @('targeter-identity-filter managed=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badTargeterIdentityRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badTargeterIdentityRejected = $_.Exception.Message -match 'required observation for targeterIdentityFilter' }
    Assert-True $badTargeterIdentityRejected 'full release gate rejects incomplete targeter identity evidence'
    $targeterIdentityProbe.observations = $targeterIdentityObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $locationProbe = $fullCatalogReport.probes | Where-Object id -eq 'locationTargeterDispatch'
    $locationObservations = $locationProbe.observations
    $locationProbe.observations = @('location-targeter-dispatch valid=true invalid-rejected=true self-aliases=true self-eye=false caster-spawn=true spawn=true target-aliases=true trigger=true forward=true projectile-forward=true predicted=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badLocationRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badLocationRejected = $_.Exception.Message -match 'required observation for locationTargeterDispatch' }
    Assert-True $badLocationRejected 'full release gate rejects incomplete location evidence'
    $locationProbe.observations = $locationObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'locationTargeterDispatch' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'locationTargeterDispatch' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingLocationRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingLocationRejected = $_.Exception.Message -match 'locationTargeterDispatch' }
    Assert-True $missingLocationRejected 'full release gate rejects missing location evidence'
    $fullCatalogReport.expectedProbeIds += 'locationTargeterDispatch'
    $fullCatalogReport.probes += $locationProbe
    $variableLocationProbe = $fullCatalogReport.probes | Where-Object id -eq 'variableLocationDispatch'
    $variableLocationObservations = $variableLocationProbe.observations
    $variableLocationProbe.observations = @('variable-location-dispatch valid=true invalid-rejected=true import-preserved=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badVariableLocationRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badVariableLocationRejected = $_.Exception.Message -match 'required observation for variableLocationDispatch' }
    Assert-True $badVariableLocationRejected 'full release gate rejects incomplete variable location evidence'
    $variableLocationProbe.observations = $variableLocationObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'variableLocationDispatch' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'variableLocationDispatch' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingVariableLocationRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingVariableLocationRejected = $_.Exception.Message -match 'variableLocationDispatch' }
    Assert-True $missingVariableLocationRejected 'full release gate rejects missing variable location evidence'
    $fullCatalogReport.expectedProbeIds += 'variableLocationDispatch'
    $fullCatalogReport.probes += $variableLocationProbe
    $explosionProbe = $fullCatalogReport.probes | Where-Object id -eq 'explosionLocationDispatch'
    $explosionProbe.observations = @('explosion-location-dispatch valid=true missing-rejected=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badExplosionRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badExplosionRejected = $_.Exception.Message -match 'required observation for explosionLocationDispatch' }
    Assert-True $badExplosionRejected 'full release gate rejects incomplete explosion location evidence'
    $explosionProbe.observations = @('explosion-location-dispatch valid=true missing-rejected=true')
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'explosionLocationDispatch' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'explosionLocationDispatch' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingExplosionRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingExplosionRejected = $_.Exception.Message -match 'explosionLocationDispatch' }
    Assert-True $missingExplosionRejected 'full release gate rejects missing explosion location evidence'
    $fullCatalogReport.expectedProbeIds += 'explosionLocationDispatch'
    $fullCatalogReport.probes += $explosionProbe
    $dropLocationProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropItemLocation'
    $dropLocationProbe.observations = @('drop-item-location valid=true invalid-rejected=false missing-rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badDropLocationRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badDropLocationRejected = $_.Exception.Message -match 'required observation for dropItemLocation' }
    Assert-True $badDropLocationRejected 'full release gate rejects incomplete drop item location evidence'
    $dropLocationProbe.observations = @('drop-item-location valid=true invalid-rejected=true missing-rejected=true')
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropItemLocation' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropItemLocation' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingDropLocationRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingDropLocationRejected = $_.Exception.Message -match 'dropItemLocation' }
    Assert-True $missingDropLocationRejected 'full release gate rejects missing drop item location evidence'
    $fullCatalogReport.expectedProbeIds += 'dropItemLocation'
    $fullCatalogReport.probes += $dropLocationProbe
    $importedDropProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropItemImported'
    $importedDropProbe.observations = @('drop-item-import valid=true missing-rejected=true invalid-preserved=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedDropRejected = $_.Exception.Message -match 'required observation for dropItemImported' }
    Assert-True $badImportedDropRejected 'full release gate rejects incomplete imported drop evidence'
    $importedDropProbe.observations = @('drop-item-import valid=true missing-rejected=true invalid-preserved=true')
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropItemImported' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropItemImported' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedDropRejected = $_.Exception.Message -match 'dropItemImported' }
    Assert-True $missingImportedDropRejected 'full release gate rejects missing imported drop evidence'
    $fullCatalogReport.expectedProbeIds += 'dropItemImported'
    $fullCatalogReport.probes += $importedDropProbe
    $naturalDropProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropItemNaturalImported'
    $naturalDropProbe.observations = @('drop-item-natural-import exact=false invalid-preserved=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badNaturalDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badNaturalDropRejected = $_.Exception.Message -match 'required observation for dropItemNaturalImported' }
    Assert-True $badNaturalDropRejected 'full release gate rejects incomplete natural drop evidence'
    $naturalDropProbe.observations = @('drop-item-natural-import exact=true invalid-preserved=true')
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropItemNaturalImported' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropItemNaturalImported' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingNaturalDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingNaturalDropRejected = $_.Exception.Message -match 'dropItemNaturalImported' }
    Assert-True $missingNaturalDropRejected 'full release gate rejects missing natural drop evidence'
    $fullCatalogReport.expectedProbeIds += 'dropItemNaturalImported'
    $fullCatalogReport.probes += $naturalDropProbe
    $inlineDropProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropItemInlineImported'
    $inlineDropProbe.observations = @('drop-item-inline-import valid=true invalid-preserved=true dynamic=false missing-rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badInlineDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badInlineDropRejected = $_.Exception.Message -match 'required observation for dropItemInlineImported' }
    Assert-True $badInlineDropRejected 'full release gate rejects incomplete inline drop evidence'
    $inlineDropProbe.observations = @('drop-item-inline-import valid=true invalid-preserved=true dynamic=true missing-rejected=true')
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropItemInlineImported' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropItemInlineImported' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingInlineDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingInlineDropRejected = $_.Exception.Message -match 'dropItemInlineImported' }
    Assert-True $missingInlineDropRejected 'full release gate rejects missing inline drop evidence'
    $fullCatalogReport.expectedProbeIds += 'dropItemInlineImported'
    $fullCatalogReport.probes += $inlineDropProbe
    $listDropProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropItemListImported'
    $listDropProbe.observations = @('drop-item-list-import valid=true invalid-preserved=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badListDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badListDropRejected = $_.Exception.Message -match 'required observation for dropItemListImported' }
    Assert-True $badListDropRejected 'full release gate rejects incomplete list drop evidence'
    $listDropProbe.observations = @('drop-item-list-import valid=true invalid-preserved=true')
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropItemListImported' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropItemListImported' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingListDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingListDropRejected = $_.Exception.Message -match 'dropItemListImported' }
    Assert-True $missingListDropRejected 'full release gate rejects missing list drop evidence'
    $fullCatalogReport.expectedProbeIds += 'dropItemListImported'
    $fullCatalogReport.probes += $listDropProbe
    $messageProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedMessageClient'
    $messageProbe.observations = @('imported-message-client dispatched=true client-exact=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badMessageRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badMessageRejected = $_.Exception.Message -match 'required observation for importedMessageClient' }
    Assert-True $badMessageRejected 'full release gate rejects missing exact message receipt'
    $messageProbe.observations = @('imported-message-client dispatched=true client-exact=true')
    $escapedMessageProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedEscapedMessageClient'
    $escapedMessageProbe.observations = @('imported-escaped-message-client dispatched=true client-exact=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badEscapedMessageRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badEscapedMessageRejected = $_.Exception.Message -match 'required observation for importedEscapedMessageClient' }
    Assert-True $badEscapedMessageRejected 'full release gate rejects missing exact escaped message receipt'
    $escapedMessageProbe.observations = @('imported-escaped-message-client dispatched=true client-exact=true')
    $skillReferenceProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedSkillReference'
    $skillReferenceProbe.observations = @('imported-skill-reference valid=true rejected=false preserved=true warnings=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badSkillReferenceRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badSkillReferenceRejected = $_.Exception.Message -match 'required observation for importedSkillReference' }
    Assert-True $badSkillReferenceRejected 'full release gate rejects incomplete skill reference evidence'
    $skillReferenceProbe.observations = @('imported-skill-reference valid=true rejected=true preserved=true warnings=true')
    $skillTimingProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedSkillTiming'
    $skillTimingProbe.observations = @('imported-skill-timing submitted=true before-delay=true first=true repeated=false invalid-rejected=true preserved=true warnings=true duplicate-rejected=true mechanic-preserved=true positional-chance=true positional-condition=true suffix-rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badSkillTimingRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badSkillTimingRejected = $_.Exception.Message -match 'required observation for importedSkillTiming' }
    Assert-True $badSkillTimingRejected 'full release gate rejects incomplete skill timing evidence'
    $skillTimingProbe.observations = @('imported-skill-timing submitted=true before-delay=true first=true repeated=true invalid-rejected=true preserved=true warnings=true duplicate-rejected=false mechanic-preserved=true positional-chance=true positional-condition=true suffix-rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $duplicateTimingAccepted = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
        $duplicateTimingAccepted = $true
    } catch { if ($_.Exception.Message -notmatch 'required observation for importedSkillTiming') { throw } }
    Assert-True (-not $duplicateTimingAccepted) 'full release gate rejects ambiguous execution options'
    $skillTimingProbe.observations = @('imported-skill-timing submitted=true before-delay=true first=true repeated=true invalid-rejected=true preserved=true warnings=true duplicate-rejected=true mechanic-preserved=false positional-chance=true positional-condition=true suffix-rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badMechanicTimingAccepted = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
        $badMechanicTimingAccepted = $true
    } catch { if ($_.Exception.Message -notmatch 'required observation for importedSkillTiming') { throw } }
    Assert-True (-not $badMechanicTimingAccepted) 'full release gate rejects missing mechanic preservation evidence'
    $skillTimingProbe.observations = @('imported-skill-timing submitted=true before-delay=true first=true repeated=true invalid-rejected=true preserved=true warnings=true duplicate-rejected=true mechanic-preserved=true positional-chance=false positional-condition=true suffix-rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badPositionalChanceAccepted = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
        $badPositionalChanceAccepted = $true
    } catch { if ($_.Exception.Message -notmatch 'required observation for importedSkillTiming') { throw } }
    Assert-True (-not $badPositionalChanceAccepted) 'full release gate rejects missing positional chance evidence'
    $skillTimingProbe.observations = @('imported-skill-timing submitted=true before-delay=true first=true repeated=true invalid-rejected=true preserved=true warnings=true duplicate-rejected=true mechanic-preserved=true positional-chance=true positional-condition=false suffix-rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badPositionalConditionAccepted = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
        $badPositionalConditionAccepted = $true
    } catch { if ($_.Exception.Message -notmatch 'required observation for importedSkillTiming') { throw } }
    Assert-True (-not $badPositionalConditionAccepted) 'full release gate rejects missing positional condition evidence'
    $skillTimingProbe.observations = @('imported-skill-timing submitted=true before-delay=true first=true repeated=true invalid-rejected=true preserved=true warnings=true duplicate-rejected=true mechanic-preserved=true positional-chance=true positional-condition=true suffix-rejected=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badSuffixAccepted = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
        $badSuffixAccepted = $true
    } catch { if ($_.Exception.Message -notmatch 'required observation for importedSkillTiming') { throw } }
    Assert-True (-not $badSuffixAccepted) 'full release gate rejects missing ambiguous suffix evidence'
    $skillTimingProbe.observations = @('imported-skill-timing submitted=true before-delay=true first=true repeated=true invalid-rejected=true preserved=true warnings=true duplicate-rejected=true mechanic-preserved=true positional-chance=true positional-condition=true suffix-rejected=true')
    $eventSuffixProbe = $fullCatalogReport.probes | Where-Object id -eq 'unroutedEventSuffix'
    $eventSuffixProbe.observations = @('unrouted-event-suffix rejected=true warnings=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badEventSuffixRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badEventSuffixRejected = $_.Exception.Message -match 'required observation for unroutedEventSuffix' }
    Assert-True $badEventSuffixRejected 'full release gate rejects incomplete event suffix evidence'
    $eventSuffixProbe.observations = @('unrouted-event-suffix rejected=true warnings=true')
    $eventVariantsProbe = $fullCatalogReport.probes | Where-Object id -eq 'eventSuffixVariants'
    $eventVariantsProbe.observations = @('event-suffix-variants rejected=true warnings=true literal=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badEventVariantsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badEventVariantsRejected = $_.Exception.Message -match 'required observation for eventSuffixVariants' }
    Assert-True $badEventVariantsRejected 'full release gate rejects incomplete event suffix variant evidence'
    $eventVariantsProbe.observations = @('event-suffix-variants rejected=true warnings=true literal=true')
    $orphanProbe = $fullCatalogReport.probes | Where-Object id -eq 'orphanSuffix'
    $orphanProbe.observations = @('orphan-suffix preserved=true warnings=true rejected=true unchanged=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badOrphanRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badOrphanRejected = $_.Exception.Message -match 'required observation for orphanSuffix' }
    Assert-True $badOrphanRejected 'full release gate rejects incomplete orphan suffix evidence'
    $orphanProbe.observations = @('orphan-suffix preserved=true warnings=true rejected=true unchanged=true')
    $conditionFailureProbe = $fullCatalogReport.probes | Where-Object id -eq 'conditionFailureClosed'
    $conditionFailureProbe.observations = @('condition-failure-closed bound=true blocked=false control=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badConditionFailureRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badConditionFailureRejected = $_.Exception.Message -match 'required observation for conditionFailureClosed' }
    Assert-True $badConditionFailureRejected 'full release gate rejects condition failure bypass'
    $conditionFailureProbe.observations = @('condition-failure-closed bound=true blocked=true control=true')
    $namedFailureProbe = $fullCatalogReport.probes | Where-Object id -eq 'namedConditionFailureClosed'
    $namedFailureProbe.observations = @('named-condition-failure-closed bound=true blocked=false control=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badNamedFailureRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badNamedFailureRejected = $_.Exception.Message -match 'required observation for namedConditionFailureClosed' }
    Assert-True $badNamedFailureRejected 'full release gate rejects named condition failure bypass'
    $namedFailureProbe.observations = @('named-condition-failure-closed bound=true blocked=true control=true')
    $reloadProbe = $fullCatalogReport.probes | Where-Object id -eq 'namedSkillReload'
    $reloadProbe.observations = @('named-skill-reload bound=true initial=true blocked=false restored=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badReloadRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badReloadRejected = $_.Exception.Message -match 'required observation for namedSkillReload' }
    Assert-True $badReloadRejected 'full release gate rejects stale named skill execution'
    $reloadProbe.observations = @('named-skill-reload bound=true initial=true blocked=true restored=true')
    $referenceProbe = $fullCatalogReport.probes | Where-Object id -eq 'namedSkillReference'
    $referenceProbe.observations = @('named-skill-reference forward=true missing-rejected=true cycle-blocked=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badReferenceRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badReferenceRejected = $_.Exception.Message -match 'required observation for namedSkillReference' }
    Assert-True $badReferenceRejected 'full release gate rejects cyclic named skill execution'
    $referenceProbe.observations = @('named-skill-reference forward=true missing-rejected=true cycle-blocked=true')
    $rangeProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropNativeTierRange'
    $rangeProbe.observations = @('drop-native-tier-range valid=false fallback=true warnings=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badRangeRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badRangeRejected = $_.Exception.Message -match 'required observation for dropNativeTierRange' }
    Assert-True $badRangeRejected 'full release gate rejects invalid tier range behavior'
    $rangeProbe.observations = @('drop-native-tier-range valid=true fallback=true warnings=true')
    $mergedProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropMergedNull'
    $mergedObservations = $mergedProbe.observations
    $mergedProbe.observations = @('drop-merged-null rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badMergedRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badMergedRejected = $_.Exception.Message -match 'required observation for dropMergedNull' }
    Assert-True $badMergedRejected 'full release gate rejects incomplete merged null drop evidence'
    $mergedProbe.observations = $mergedObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropMergedNull' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropMergedNull' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingMergedRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingMergedRejected = $_.Exception.Message -match 'dropMergedNull' }
    Assert-True $missingMergedRejected 'full release gate rejects missing merged null drop evidence'
    $fullCatalogReport.expectedProbeIds += 'dropMergedNull'
    $fullCatalogReport.probes += $mergedProbe
    $chanceProbe = $fullCatalogReport.probes | Where-Object id -eq 'dropChanceImported'
    $chanceObservations = $chanceProbe.observations
    $chanceProbe.observations = @('drop-chance-imported rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badChanceRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badChanceRejected = $_.Exception.Message -match 'required observation for dropChanceImported' }
    Assert-True $badChanceRejected 'full release gate rejects incomplete imported chance evidence'
    $chanceProbe.observations = $chanceObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'dropChanceImported' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'dropChanceImported' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingChanceRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingChanceRejected = $_.Exception.Message -match 'dropChanceImported' }
    Assert-True $missingChanceRejected 'full release gate rejects missing imported chance evidence'
    $fullCatalogReport.expectedProbeIds += 'dropChanceImported'
    $fullCatalogReport.probes += $chanceProbe
    ($fullCatalogReport.probes | Where-Object id -eq 'expTierDeath').observations = @('incomplete evidence')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badTierEvidenceRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badTierEvidenceRejected = $_.Exception.Message -match 'required observation for expTierDeath' }
    Assert-True $badTierEvidenceRejected 'full release gate rejects incomplete tiered experience evidence'
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'expTierDeath' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'expTierDeath' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingTierRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingTierRejected = $_.Exception.Message -match 'expTierDeath' }
    Assert-True $missingTierRejected 'full release gate rejects missing tiered experience evidence'
    $fullCatalogReport.expectedProbeIds += 'expTierDeath'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'expTierDeath'; status = 'passed'; observations = @('exp-tier-death result=true low=2 qualified=7 low-dead=true qualified-dead=true low-bread=4 qualified-bread=4')
    }
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'skillLifecycleCleanup' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'skillLifecycleCleanup' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingLifecycleRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingLifecycleRejected = $_.Exception.Message -match 'skillLifecycleCleanup' }
    Assert-True $missingLifecycleRejected 'full release gate rejects missing lifecycle evidence'
    $fullCatalogReport.expectedProbeIds += 'skillLifecycleCleanup'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'skillLifecycleCleanup'; status = 'passed'; observations = @('synthetic lifecycle evidence')
    }
    ($fullCatalogReport.probes | Where-Object id -eq 'playerPotionNaturalExpiry').status = 'observed-rejected'
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $rejectedPotionRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $rejectedPotionRejected = $_.Exception.Message -match 'playerPotionNaturalExpiry'
    }
    Assert-True $rejectedPotionRejected 'full release gate requires Potion probes to pass'
    ($fullCatalogReport.probes | Where-Object id -eq 'playerPotionNaturalExpiry').status = 'passed'
    ($fullCatalogReport.probes | Where-Object id -eq 'spawner:condition_fail').status = 'passed'
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $unexpectedSpawnerPassRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $unexpectedSpawnerPassRejected = $_.Exception.Message -match 'spawner:condition_fail'
    }
    Assert-True $unexpectedSpawnerPassRejected 'full release gate requires negative controls to be rejected'
    ($fullCatalogReport.probes | Where-Object id -eq 'spawner:condition_fail').status = 'observed-rejected'
    $negativeProbe = $fullCatalogReport.probes | Where-Object id -eq 'spawner:condition_fail'
    $originalNegativeObservations = $negativeProbe.observations
    $negativeProbe.observations = @('spawner-probe id=condition_fail requested=false failure=player gate closed')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $wrongRejectionRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $wrongRejectionRejected = $_.Exception.Message -match 'expected rejection reason.*spawner:condition_fail'
    }
    Assert-True $wrongRejectionRejected 'full release gate rejects the wrong negative-control failure reason'
    $negativeProbe.observations = $originalNegativeObservations
    $importedRechargingProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedItemRechargingCondition'
    $importedRechargingObservations = @($importedRechargingProbe.observations)
    $failedImportedRechargingObservation = ([string]$importedRechargingObservations[0]) -replace 'non-human-rejected=true', 'non-human-rejected=false'
    $importedRechargingProbe.observations = @($failedImportedRechargingObservation)
    Assert-True ($importedRechargingProbe.observations -contains $failedImportedRechargingObservation -and
        $failedImportedRechargingObservation -match 'non-human-rejected=false') 'imported ItemRecharging failure fixture changes only its non-human control'
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedRechargingRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedRechargingRejected = $_.Exception.Message -match 'required observation for importedItemRechargingCondition' }
    Assert-True $badImportedRechargingRejected 'full release gate rejects a failed imported ItemRecharging non-human control'
    $importedRechargingProbe.observations = $importedRechargingObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedItemRechargingCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedItemRechargingCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedRechargingRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedRechargingRejected = $_.Exception.Message -match 'importedItemRechargingCondition' }
    Assert-True $missingImportedRechargingRejected 'full release gate rejects missing imported ItemRecharging evidence'
    $fullCatalogReport.expectedProbeIds += 'importedItemRechargingCondition'
    $fullCatalogReport.probes += $importedRechargingProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $importedFrozenProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedIsFrozenCondition'
    $importedFrozenObservations = @($importedFrozenProbe.observations)
    $failedImportedFrozenObservation = ([string]$importedFrozenObservations[0]) -replace 'alias-thawed=true', 'alias-thawed=false'
    $importedFrozenProbe.observations = @($failedImportedFrozenObservation)
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedFrozenRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedFrozenRejected = $_.Exception.Message -match 'required observation for importedIsFrozenCondition' }
    Assert-True $badImportedFrozenRejected 'full release gate rejects missing imported IsFrozen thawed-alias evidence'
    $importedFrozenProbe.observations = $importedFrozenObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedIsFrozenCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedIsFrozenCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedFrozenRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedFrozenRejected = $_.Exception.Message -match 'importedIsFrozenCondition' }
    Assert-True $missingImportedFrozenRejected 'full release gate rejects missing imported IsFrozen evidence'
    $fullCatalogReport.expectedProbeIds += 'importedIsFrozenCondition'
    $fullCatalogReport.probes += $importedFrozenProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'skillTeleportCoordinates' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'skillTeleportCoordinates' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $omittedProbeRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $omittedProbeRejected = $_.Exception.Message -match 'skillTeleportCoordinates'
    }
    Assert-True $omittedProbeRejected 'full release gate rejects a probe omitted from both report and report catalog'

    $exploratoryReportPath = Join-Path $temp 'exploratory-report.json'
    $exploratoryReport = Get-Content -LiteralPath $targetGateReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $exploratoryReport.evidenceMode = 'exploratory'
    $exploratoryReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $exploratoryReportPath -Encoding utf8
    $exploratoryRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $exploratoryReportPath `
            -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1'
    } catch {
        $exploratoryRejected = $_.Exception.Message -match 'not release evidence'
    }
    Assert-True $exploratoryRejected 'strict gate rejects exploratory evidence mode'

    $wrongTargetReportPath = Join-Path $temp 'wrong-target-report.json'
    $wrongTargetReport = Get-Content -LiteralPath $targetGateReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $wrongTargetReport.paperVersion = '1.21.4-test'
    $wrongTargetReport.paperApiVersion = '1.21.4-test-api'
    $wrongTargetReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $wrongTargetReportPath -Encoding utf8
    $wrongTargetRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $wrongTargetReportPath `
            -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1'
    } catch {
        $wrongTargetRejected = $_.Exception.Message -match 'Paper implementation mismatch'
    }
    Assert-True $wrongTargetRejected 'strict gate rejects a report from another Paper version'

    $missingTargetProbePath = Join-Path $temp 'missing-target-probe-report.json'
    $missingTargetProbe = Get-Content -LiteralPath $targetGateReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $missingTargetProbe.expectedProbeIds = @('positive', 'required-probe')
    $missingTargetProbe | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $missingTargetProbePath -Encoding utf8
    $missingTargetProbeRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $missingTargetProbePath `
            -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1'
    } catch {
        $missingTargetProbeRejected = $_.Exception.Message -match 'required-probe'
    }
    Assert-True $missingTargetProbeRejected 'strict gate rejects missing registered probe evidence'

    $duplicateProbePath = Join-Path $temp 'duplicate-probe-report.json'
    $duplicateProbe = Get-Content -LiteralPath $targetGateReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $duplicateProbe.probes = @(
        @{ id = 'positive'; status = 'passed'; observations = @('ok') },
        @{ id = 'positive'; status = 'passed'; observations = @('duplicate') }
    )
    $duplicateProbe | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $duplicateProbePath -Encoding utf8
    $duplicateRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $duplicateProbePath `
            -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1'
    } catch {
        $duplicateRejected = $_.Exception.Message -match 'duplicate probes'
    }
    Assert-True $duplicateRejected 'strict gate rejects duplicate probe IDs'

    $unexpectedProbePath = Join-Path $temp 'unexpected-probe-report.json'
    $unexpectedProbe = Get-Content -LiteralPath $targetGateReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $unexpectedProbe.probes = @(
        @{ id = 'positive'; status = 'passed'; observations = @('ok') },
        @{ id = 'unexpected'; status = 'passed'; observations = @('unregistered') }
    )
    $unexpectedProbe | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $unexpectedProbePath -Encoding utf8
    $unexpectedRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $unexpectedProbePath `
            -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1'
    } catch {
        $unexpectedRejected = $_.Exception.Message -match 'unregistered probes'
    }
    Assert-True $unexpectedRejected 'strict gate rejects unregistered probe IDs'

    $emptyTargetReportPath = Join-Path $temp 'empty-target-report.json'
    $emptyTargetReport = Get-Content -LiteralPath $targetGateReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $emptyTargetReport.probes = @()
    $emptyTargetReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $emptyTargetReportPath -Encoding utf8
    $emptyTargetRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $emptyTargetReportPath `
            -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1'
    } catch {
        $emptyTargetRejected = $_.Exception.Message -match 'contains no probe evidence'
    }
    Assert-True $emptyTargetRejected 'strict gate rejects a report with no probe evidence'

    $rawPotionTemp = Join-Path $temp 'raw-potion-fixture'
    New-Item -ItemType Directory -Path (Join-Path $rawPotionTemp 'logs') -Force | Out-Null
    $env:PAPER_RAW_POTION_WORKFLOW = 'true'
    Set-Content -LiteralPath (Join-Path $rawPotionTemp 'logs/latest.log') -Encoding utf8 -Value @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: player-potion-consume event=true player=HelsteraTester item=POTION',
        '[Server thread/INFO]: Done (1.000s)! For help, type "help"'
    )
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $rawPotionTemp | Out-Null
    $rawPotionReportPath = Join-Path $rawPotionTemp 'integration-report.json'
    $rawPotionReport = Get-Content -LiteralPath $rawPotionReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $rawPotionProbe = @($rawPotionReport.probes | Where-Object id -eq 'playerPotionConsume')
    Assert-True ($rawPotionProbe.Count -eq 1 -and $rawPotionProbe[0].status -eq 'passed') 'raw potion consume evidence is registered when observed'
    Assert-True ($rawPotionReport.expectedProbeIds -contains 'playerPotionConsume') 'observed raw potion probe is required'

    Set-Content -LiteralPath (Join-Path $rawPotionTemp 'logs/latest.log') -Encoding utf8 -Value @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: Done (1.000s)! For help, type "help"'
    )
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $rawPotionTemp | Out-Null
    $rawPotionMissing = Get-Content -LiteralPath $rawPotionReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $missingPotionProbe = @($rawPotionMissing.probes | Where-Object id -eq 'playerPotionConsume')
    Assert-True ($missingPotionProbe.Count -eq 1 -and $missingPotionProbe[0].status -eq 'not-observed') 'requested raw potion probe remains explicit when absent'
    Assert-True ($rawPotionMissing.expectedProbeIds -contains 'playerPotionConsume') 'requested raw potion probe remains in the strict catalog when absent'
    $missingPotionRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $rawPotionReportPath `
            -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1'
    } catch {
        $missingPotionRejected = $_.Exception.Message -match 'playerPotionConsume=not-observed'
    }
    Assert-True $missingPotionRejected 'strict gate rejects a requested raw potion probe without a consume event'

    $env:PAPER_RAW_POTION_SHORT = 'true'
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $rawPotionTemp | Out-Null
    $shortPotionMissing = Get-Content -LiteralPath $rawPotionReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $missingExpiryProbe = @($shortPotionMissing.probes | Where-Object id -eq 'playerPotionNaturalExpiry')
    Assert-True ($missingExpiryProbe.Count -eq 1 -and $missingExpiryProbe[0].status -eq 'not-observed') 'requested short Potion expiry remains explicit when absent'
    Assert-True ($shortPotionMissing.expectedProbeIds -contains 'playerPotionNaturalExpiry') 'requested short Potion expiry remains in the strict catalog when absent'
    $missingExpiryRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $rawPotionReportPath `
            -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1'
    } catch {
        $missingExpiryRejected = $_.Exception.Message -match 'playerPotionNaturalExpiry=not-observed'
    }
    Assert-True $missingExpiryRejected 'strict gate rejects a requested raw Potion without natural-expiry evidence'
    $env:PAPER_RAW_POTION_WORKFLOW = $previousRawPotionWorkflow
    $env:PAPER_RAW_POTION_SHORT = $previousRawPotionShort

    foreach ($chainState in @('false', 'missing')) {
        $chainTemp = Join-Path $temp "context-chain-$chainState"
        New-Item -ItemType Directory -Path (Join-Path $chainTemp 'logs') -Force | Out-Null
        $chainField = if ($chainState -eq 'false') { 'context-chain-action=false ' } else { '' }
        Set-Content -LiteralPath (Join-Path $chainTemp 'logs/latest.log') -Encoding utf8 -Value @(
            'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
            '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
            "[Server thread/INFO]: ai-selector-core player-target=true monster-target=true clear-no-fallback=true owner-target=true faction-target=true conditional-target=true players-in-world=true players-on-server=true player-aliases=true mobs-in-world=true world-chain-filtered=true nearest-living=true nearest-mob=true targeter-syntax=true set-target-mechanic=true clear-target-mechanic=true ${chainField}native-target=true passed=true",
            '[Server thread/INFO]: context-targeter-controls order=true radius=false invalid-rejected=true',
            '[Server thread/INFO]: context-targeter-chain targets=true trigger=true excluded=false condition=true',
            '[Server thread/INFO]: Done (1.000s)! For help, type "help"'
        )
        & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $chainTemp | Out-Null
        $chainReportPath = Join-Path $chainTemp 'integration-report.json'
        $chainReport = Get-Content -LiteralPath $chainReportPath -Raw -Encoding utf8 | ConvertFrom-Json
        $chainProbe = @($chainReport.probes | Where-Object id -eq 'aiSelectorCore')
        Assert-True ($chainProbe.Count -eq 1 -and $chainProbe[0].status -eq 'not-observed') "context chain $chainState is not passing evidence"
        Assert-True (($chainReport.probes | Where-Object id -eq 'contextTargeterControls').status -eq 'not-observed') 'context targeter radius failure is not passing evidence'
        Assert-True (($chainReport.probes | Where-Object id -eq 'contextTargeterChain').status -eq 'not-observed') 'context chain exclusion failure is not passing evidence'
        $chainRejected = $false
        try {
            & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $chainReportPath -RequireTargetPaperVersion
        } catch {
            $chainRejected = $_.Exception.Message -match 'aiSelectorCore=not-observed'
        }
        Assert-True $chainRejected "strict gate rejects context chain $chainState"
    }

    $targeterSyntaxTemp = Join-Path $temp 'targeter-syntax-missing'
    New-Item -ItemType Directory -Path (Join-Path $targeterSyntaxTemp 'logs') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $targeterSyntaxTemp 'logs/latest.log') -Encoding utf8 -Value @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: ai-selector-core player-target=true monster-target=true clear-no-fallback=true owner-target=true faction-target=true conditional-target=true players-in-world=true players-on-server=true player-aliases=true mobs-in-world=true world-caster-excluded=true world-chain-filtered=true nearest-living=true nearest-mob=true targeter-syntax=false set-target-mechanic=true clear-target-mechanic=true context-chain-action=true native-target=true passed=true',
        '[Server thread/INFO]: Done (1.000s)! For help, type "help"'
    )
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $targeterSyntaxTemp | Out-Null
    $targeterSyntaxReportPath = Join-Path $targeterSyntaxTemp 'integration-report.json'
    $targeterSyntaxReport = Get-Content -LiteralPath $targeterSyntaxReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $targeterSyntaxProbe = @($targeterSyntaxReport.probes | Where-Object id -eq 'aiSelectorCore')
    Assert-True ($targeterSyntaxProbe.Count -eq 1 -and $targeterSyntaxProbe[0].status -eq 'not-observed') 'targeter syntax failure is not passing evidence'
    $targeterSyntaxRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $targeterSyntaxReportPath -RequireTargetPaperVersion
    } catch {
        $targeterSyntaxRejected = $_.Exception.Message -match 'aiSelectorCore=not-observed'
    }
    Assert-True $targeterSyntaxRejected 'strict gate rejects missing targeter syntax evidence'

    $noClientTemp = Join-Path $temp 'no-client-fixture'
    New-Item -ItemType Directory -Path (Join-Path $noClientTemp 'logs') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $noClientTemp 'logs/latest.log') -Encoding utf8 -Value @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: Done (1.000s)! For help, type "help"'
    )
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $noClientTemp | Out-Null
    $noClientReport = Get-Content -LiteralPath (Join-Path $noClientTemp 'integration-report.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    foreach ($dynamicProbeId in @('custom-menu-player-workflow', 'audience-player-delivery',
            'audience-filter-delivery', 'random-spawn-distribution', 'skillTeleportCoordinates',
            'spawningDistanceClientWalk')) {
        $dynamicProbe = @($noClientReport.probes | Where-Object id -eq $dynamicProbeId)
        Assert-True ($dynamicProbe.Count -eq 1) "no-client report includes $dynamicProbeId"
        Assert-True ($dynamicProbe[0].status -eq 'not-observed') "no-client $dynamicProbeId is explicit missing evidence"
        Assert-True ($noClientReport.expectedProbeIds -contains $dynamicProbeId) "no-client catalog includes $dynamicProbeId"
    }

    $dynamicSpawnerTemp = Join-Path $temp 'dynamic-spawner-fixture'
    New-Item -ItemType Directory -Path (Join-Path $dynamicSpawnerTemp 'logs') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $dynamicSpawnerTemp 'logs/latest.log') -Encoding utf8 -Value @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: spawner-probe id=aggregate requested=false failure=first',
        '[Server thread/INFO]: spawner-probe id=aggregate requested=true failure=null',
        '[Server thread/INFO]: spawner-probe id=missing-request failure=conditions rejected location',
        '[Server thread/INFO]: spawner-probe id=missing-reason requested=false failure=null',
        '[Server thread/INFO]: spawner-probe id=explicit-reject requested=false failure=conditions rejected location',
        '[Server thread/INFO]: Done (1.000s)! For help, type "help"'
    )
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $dynamicSpawnerTemp | Out-Null
    $dynamicSpawnerReport = Get-Content -LiteralPath (Join-Path $dynamicSpawnerTemp 'integration-report.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    $aggregateProbes = @($dynamicSpawnerReport.probes | Where-Object id -eq 'spawner:aggregate')
    Assert-True ($aggregateProbes.Count -eq 1) 'dynamic spawner evidence is aggregated by ID'
    Assert-True ($aggregateProbes[0].status -eq 'passed') 'aggregated dynamic spawner keeps passing evidence'
    Assert-True ($dynamicSpawnerReport.expectedProbeIds -contains 'spawner:aggregate') 'dynamic spawner is registered in the strict catalog'
    foreach ($id in @('missing-request', 'missing-reason')) {
        $probe = $dynamicSpawnerReport.probes | Where-Object id -eq "spawner:$id"
        Assert-True ($probe.status -eq 'not-observed') "spawner $id is not evidence of rejection"
    }
    $explicitReject = $dynamicSpawnerReport.probes | Where-Object id -eq 'spawner:explicit-reject'
    Assert-True ($explicitReject.status -eq 'observed-rejected') 'explicit spawner rejection is recorded'

    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp `
        -RandomSpawnTolerance 0.10 | Out-Null
    $restrictedReport = Get-Content -LiteralPath $reportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $restrictedRandom = @($restrictedReport.probes | Where-Object id -eq 'random-spawn-distribution')
    Assert-True ($restrictedRandom[1].status -eq 'passed' -and
        $restrictedRandom[2].status -eq 'failed') 'configured random tolerance changes the boundary decision'
    $invalidToleranceRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp `
            -RandomSpawnTolerance 1.01
    } catch {
        $invalidToleranceRejected = $_.Exception.Message -match 'between 0 and 1'
    }
    Assert-True $invalidToleranceRejected 'random tolerance rejects values outside zero to one'
    Write-Host 'Paper integration report tests passed.'
} finally {
    $env:PAPER_RAW_POTION_WORKFLOW = $previousRawPotionWorkflow
    $env:PAPER_RAW_POTION_SHORT = $previousRawPotionShort
    $env:PAPER_SPAWNING_DISTANCE_WALK_WORKFLOW = $previousSpawningDistanceWalkWorkflow
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $tempPath = [System.IO.Path]::GetFullPath($temp)
    $isOwnedTemp = $tempPath.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $tempPath).StartsWith('helstera-paper-report-', [System.StringComparison]::Ordinal)
    if ($isOwnedTemp) {
        Remove-Item -LiteralPath $tempPath -Recurse -Force -ErrorAction SilentlyContinue
    }
}
