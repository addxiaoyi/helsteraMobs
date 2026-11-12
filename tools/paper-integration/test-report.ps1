$ErrorActionPreference = 'Stop'
$previousRawPotionWorkflow = $env:PAPER_RAW_POTION_WORKFLOW
$previousRawPotionShort = $env:PAPER_RAW_POTION_SHORT
$previousSpawningDistanceWalkWorkflow = $env:PAPER_SPAWNING_DISTANCE_WALK_WORKFLOW
$previousInteractableWorkflow = $env:PAPER_INTERACTABLE_WORKFLOW
$previousShowHealthWorkflow = $env:PAPER_SHOW_HEALTH_WORKFLOW
$env:PAPER_RAW_POTION_WORKFLOW = 'false'
$env:PAPER_RAW_POTION_SHORT = 'false'
$env:PAPER_SPAWNING_DISTANCE_WALK_WORKFLOW = 'true'
$env:PAPER_INTERACTABLE_WORKFLOW = 'true'
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
        '[Server thread/INFO]: [helsteraMobs] [自检] creeper-options result=true imported=true explosion-radius=5 fuse-ticks=60 super-charged=true prevent-suicide=true protected-cancelled=true control-allowed=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] cat-options result=true imported=true type=SIAMESE sitting=true collar=BLUE control-type=TABBY control-sitting=false control-collar=RED',
        '[Server thread/INFO]: [helsteraMobs] [自检] horse-options result=true imported=true color=GRAY style=WHITE_DOTS domestication=5 max-domestication=20 jump-strength=0.8 saddled=true armor=DIAMOND_HORSE_ARMOR control-color=BLACK control-style=BLACK_DOTS control-domestication=1 control-max-domestication=12 control-jump-strength=0.4 control-saddled=false control-armor=AIR donkey-chest=true control-donkey-chest=false pig-saddled=true control-pig-saddled=false strider-saddled=true control-strider-saddled=false',
        '[Server thread/INFO]: [helsteraMobs] [自检] fox-options result=true imported=true type=SNOW control-type=RED wrong-type-preserved=true invalid-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] frog-options result=true imported=true type=COLD alias-type=WARM control-type=TEMPERATE wrong-type-preserved=true invalid-preserved=true conflict-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] rabbit-options result=true imported=true type=SALT_AND_PEPPER control-type=BROWN killer-type=THE_KILLER_BUNNY angry-type=THE_KILLER_BUNNY false-killer-type=SALT_AND_PEPPER wrong-type-preserved=true invalid-preserved=true conflict-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] falling-block-options-imported result=true imported=true material=OBSIDIAN control-material=SAND drop-item=false hurt-entities=true cancel-drop=true wrong-type-preserved=true invalid-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] boat-options-imported result=true imported=true boat-type=SPRUCE control-type=OAK chest-type=BAMBOO wrong-type-preserved=true invalid-preserved=true conflict-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] bee-anger-imported result=true imported=true configured-anger=1200 control-anger=0',
        '[Server thread/INFO]: [helsteraMobs] [自检] bee-options-imported result=true imported=true nectar=true stung=false control-nectar=false control-stung=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] bee-stinger-loss-imported result=true imported=true target-entity=ZOMBIE target-ai=false target-invulnerable=false target-resistance-amplifier=3 protected-option=true control-option=false protected-attacks=1 control-attacks=1 protected-events=1 control-events=1 protected-cancelled=0 control-cancelled=0 protected-restored=true control-retained=true protected-near=true control-near=true trigger=entity-target-api event=native-ai',
        '[Server thread/INFO]: [helsteraMobs] [自检] heal-on-reload-options result=true mapped=true reload=true protected-option=true control-option=false protected-before=15.0 protected-after=20.0 control-before=15.0 control-after=15.0',
        '[Server thread/INFO]: [helsteraMobs] [自检] skill-scheduler result=true delay=2 repeat=3 interval=2 executions=4 pre-delay-count=0',
        '[Server thread/INFO]: [helsteraMobs] [自检] skill-tree-branches result=true condition-denied=true condition-unlock=true prerequisite-blocked=true prerequisite-unlock=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] pin-targeters result=true multi-dispatch=true random-single=true move-single=true reject-multi-move=true region-set-break=true region-cap-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] pin-conditions result=true distance=true origin-distance=true origin-inside=true origin-outside=true origin-missing-rejected=true region=true',
        '[Server thread/INFO]: [helsteraMobs] survival-mode-condition positive=true negative=true restored=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] skill-tree-cast-persistence result=true blocked-before-unlock=true prerequisite-unlock-blocked=true snapshot-saved=true advanced-unlocked=true casts=1',
        '[Server thread/INFO]: skill-tree-queue submitted=true restored=true',
        '[Server thread/INFO]: skill-lifecycle reload=true scheduled=true cancelled=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true bounds-condition=true bounding-box-overlap-condition=true',
        '[Server thread/INFO]: skill-actions invulnerable-condition=true invulnerable-aliases=true',
        '[Server thread/INFO]: [helsteraMobs] mythic-teleport imported=true entity-target=true location-target=true safe-blocked=true unsafe-bypass=true spread-bounded=true yaw-preserved=true orientation-override=true invalid-preserved=true warnings=true restored=true passed=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-looking-at-condition primary-mapped=true alias-mapped=true entity-mapped=true positive=true alias-positive=true wrong-block-rejected=true short-block-distance-rejected=true negative=true entity-positive=true wrong-entity-rejected=true short-entity-distance-rejected=true invalid-rejected=true restored=true',
        '[Server thread/INFO]: [helsteraMobs] execution-audience-mutation imported=true explicit-empty=true added=true persisted=true member-visible=true outsider-hidden=true removed=true empty-persisted=true unset=true unrestricted=true passed=true',
        '[Server thread/INFO]: [helsteraMobs] execution-audience-mob-spawn imported=true mapped=true spawned=true selected-member=true outsider-excluded=true persisted=true member-visible=true outsider-hidden=true passed=true',
        '[Server thread/INFO]: [helsteraMobs] execution-audience-mob-mutation imported=true targeter-removed=true empty-persisted=true hidden=true targeter-added=true restored-persisted=true member-visible=true outsider-hidden=true passed=true',
        '[Server thread/INFO]: [helsteraMobs] execution-audience-mob-summon imported=true action-bound=true spawned=true inherited=true parent-linked=true visuals=true member-visible=true outsider-hidden=true passed=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-mobility=true moving-default-stationary=true moving-exact-fractional=true moving-alias-fractional=true moving-approx-same-block=true moving-approx-block=true moving-exact-argument=true moving-e-argument=true moving-invalid-rejected=true moving-imported=true moving-restored=true drowning-condition=true leashed-condition=true has-owner-condition=true has-tag-condition=true target-has-tag-condition=true scoreboard-tag-mechanics=true score-mechanics=true global-score-condition=true score-condition=true custom-owner-relation=true owner-condition=true children-condition=true inside-outside-conditions=true distance-from-location-condition=true coordinate-difference-condition=true origin-location-condition=true line-of-sight-from-origin-condition=true bounds-condition=true enchanting-conditions=true plugin-condition=true field-of-view-condition=true spawn-reason-condition=true water-mob-condition=true zombie-condition=true entity-type-condition=true aerial-condition=true enderman-condition=true enchantment-condition=true color-condition=true material-cooldown-condition=true material-cooldown-mechanic=true set-game-mode-mechanic=true jump-mechanic=true force-pull-mechanic=true set-offhand-mechanic=true set-armor-mechanic=true clear-inventory-mechanic=true remove-held-item-mechanic=true raw-experience-mechanic=true set-absorption-mechanic=true trigger-item-type-condition=true entity-item-type-condition=true entity-item-similar-condition=true item-similar-condition=true targets-condition=true item-type-condition=true structure-condition=true mythic-mob-type-condition=true trigger-block-type-condition=true frozen-condition=true dialogue-condition=true player-kills-condition=true absorption-condition=true health-absorption-condition=true potion-effect-condition=true aura-stacks-condition=true level-condition=true lunar-phase-condition=true string-equals-condition=true string-empty-condition=true compare-values-condition=true is-skill-condition=true owner-online-condition=true parent-relation=true is-child-condition=true is-child-import-condition=true adult-parent-positive=true adult-not-baby=true unrelated-baby-not-child=true baby-positive=true parent-derived-conditions=true player-experience-mechanics=true oxygen-mechanic=true consume-slot-mechanic=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true target-categories=true',
        '[Server thread/INFO]: skill-actions mythic-aura-action=true mythic-has-aura-condition=true mythic-target-has-aura-condition=true mythic-has-aura-stacks-condition=true mythic-has-aura-type-condition=true mythic-aura-remove=true mythic-aura-invalid-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] line-of-sight-condition result=true imported=true aliases=true direct-positive=true direct-blocked=true target-positive=true target-blocked=true target-negative=true restored-positive=true restored=true invalid-preserved=true warning=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] condition-mobility-states result=true falling-positive=true falling-negative=true climbing-positive=true climbing-negative=true charged-positive=true charged-negative=true restored=true aliases=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] grounded-condition imported=true primary-mapped=true grounded-alias-mapped=true positive=true alias-positive=true airborne=true negative=true alias-negative=true invalid-preserved=true restored=true passed=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] template-type-imported result=true imported=true primary-mapped=true alias-mapped=true direct-positive=true transitive-positive=true unrelated-rejected=true control-rejected=true field-inheritance=true exclude-inherited-removed=true exclude-own-values=true missing-config-rejected=true unmanaged-rejected=true invalid-preserved=true passed=true',
        '[Server thread/INFO]: raider-patrol-leader-condition positive=true negative=true entity=PILLAGER restored=true',
        '[Server thread/INFO]: creeper-primed-condition positive=true negative=true restored=true',
        '[Server thread/INFO]: ender-dragon-alive-condition positive=true negative=true removed=true',
        '[Server thread/INFO]: ender-dragon-phase-condition positive=true list=true negative=true invalid-rejected=true restored=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-has-enchantment-condition primary-mapped=true alias-mapped=true positive=true any-positive=true level-rejected=true type-rejected=true restored=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-trigger-item-type-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true constructed-context=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-trigger-block-type-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true state-rejected=true constructed-context=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-origin-location-condition primary-mapped=true alias-mapped=true positive=true exact-positive=true negative=true invalid-rejected=true constructed-context=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-item-recharging-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true non-human-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-execution-audience-condition primary-mapped=true alias-mapped=true contains-mapped=true no-audience-player-allowed=true member-allowed=true outsider-rejected=true empty-rejected=true non-player-rejected=true invalid-preserved=true per-target-unattached=true per-target-explicit=true result=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-target-relationships-condition has-owner-mapped=true owner-player-mapped=true owner-online-mapped=true leashed-mapped=true leashed-alias-mapped=true owned-positive=true owner-player-positive=true owner-online-positive=true leashed-positive=true leashed-alias-positive=true leash-cleared-rejected=true owner-cleared-rejected=true missing-target-rejected=true result=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-frozen-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true thawed=true alias-thawed=true invalid-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-burning-condition actions=true target-priority=true target-positive=true target-negative=true composed-negation=true caster-fallback=true invalid-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-entity-category-condition water-primary-mapped=true water-alias-mapped=true water-positive=true water-zombie-rejected=true zombie-primary-mapped=true zombie-alias-mapped=true zombie-positive=true zombie-cod-rejected=true invalid-preserved=true cod=COD zombie=ZOMBIE',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-entity-type-variant-condition primary-mapped=true alias-mapped=true mobtype-mapped=true positive=true alias-positive=true mobtype-positive=true variant-alternative=true wrong-variant-rejected=true wrong-type-rejected=true invalid-preserved=true cat=CAT variant=TABBY',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-entity-type-traits mapped=true horse=true wrong-style-rejected=true panda=true villager=true zombie-villager=true rainbow-sheep=true rainbow-sheep-white-rejected=true shulker=true parrot=true llama=true tropical-fish=true iron-golem-crack=true inapplicable-trait-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-spyglass-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true inactive-rejected=true non-player-rejected=true invalid-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-survival-mode-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true alias-negative=true non-player-rejected=true invalid-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] imported-free-inventory-slot primary-mapped=true alias-mapped=true positive=true negative=true invalid-preserved=true result=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-name-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true default-name-rejected=true fallback=true custom-name-rejected=true invalid-preserved=true result=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-server-conditions paper-primary=true paper-alias=true exact=true exact-negative=true after-inclusive=true after-exclusive-rejected=true after-higher-rejected=true before-inclusive=true before-exclusive-rejected=true invalid-preserved=true result=true',
        '[Server thread/INFO]: [helsteraMobs] imported-coordinate-difference-condition primary-mapped=true alias-mapped=true x-positive=true y-positive=true z-positive=true out-of-range-rejected=true invalid-preserved=true result=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-population-conditions players-online-mapped=true online-alias-mapped=true players-world-mapped=true mobs-world-mapped=true players-online-negative-mapped=true online-alias-negative-mapped=true players-world-negative-mapped=true mobs-world-negative-mapped=true players-online-positive=true online-alias-positive=true players-world-positive=true mobs-world-positive=true players-online-negative=true online-alias-negative=true players-world-negative=true mobs-world-negative=true invalid-preserved=true result=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-variable-conditions result=true primary-mapped=true aliases=true scopes=true ranges=true missing-rejected=true wrong-type-rejected=true quoted-value=true dynamic-placeholder=true invalid-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] attack-cooldown-placeholder result=true in-range=true recharging=true caster=0.5 target=0.5 aliases=true non-player-unresolved=true',
        '[Server thread/INFO]: [helsteraMobs] target-fluids-delayed imported=true water=true lava=true invalid-preserved=true restored=true passed=true',
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
        '[Server thread/INFO]: [自检] skill-trigger damage=true attack=true attacked=false teleport=true teleport-before=false bukkit-damage=true damage-cause-condition=true damage-amount-condition=true damage-tag-condition=true bow-tension-condition=true bukkit-attacked=true cancelled-condition=true cancelled-before=false cancelled-after=true entity-valid=true',
        '[Server thread/INFO]: [自检] skill-trigger damage=true attack=true last-damager-targeter=true last-damager-projectile=true last-damager-environmental-rejected=true last-damager-removed-rejected=true teleport=true teleport-before=false bukkit-damage=true bukkit-attacked=true cancelled-before=false cancelled-after=false entity-valid=true',
        '[Server thread/INFO]: [自检] skill-trigger damage=true attack=true target-of-target=true target-of-target-aliases=true target-of-target-non-mob-rejected=true target-of-target-missing-rejected=true target-of-target-invalid-rejected=true teleport=true teleport-before=false bukkit-damage=true bukkit-attacked=true cancelled-before=false cancelled-after=false entity-valid=true',
        '[Server thread/INFO]: block-events break=true place=true player=HelsteraTester',
        '[Server thread/INFO]: skill-trigger damage=true attack=true attacked=false teleport=true potion-effect-end=true teleport-before=false bukkit-damage=true bukkit-attacked=true cancelled-before=false cancelled-after=false entity-valid=true',
        '[Server thread/INFO]: potion-natural-expiry=true expired=true event-observed=false action-observed=true item-marker-observed=false',
        '[Server thread/INFO]: kill-trigger imported=true mapped=true natural=true victim-dead=true action-observed=true passed=true',
        '[Server thread/INFO]: player-kill-trigger imported=true mapped=true natural=true victim-player=true victim-death-observed=true action-observed=true player-kills-positive=true player-kills-negative=true passed=true',
        '[Server thread/INFO]: imported-signal-trigger imported=true mapped=true generic=true selector=true payload=true sender=true trigger=true invalid-preserved=true last-signal=true passed=true',
        '[Server thread/INFO]: imported-timer-trigger imported=true mapped=true aligned=true independent=true invalid-preserved=true source=true passed=true',
        '[Server thread/INFO]: imported-player-kills-condition primary-mapped=true range-mapped=true positive=true negative=true invalid-preserved=true',
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
        '[Server thread/INFO]: [helsteraMobs] [自检] entity-size-range result=true samples=64 configured=2..5 slime=2..5 magma-cube=2..5 phantom=2..5 imported=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] reinforcements-chance-options result=true imported=true applied=true base=0.38 zero-mapped=true upper-bound-mapped=true wrong-type-preserved=true invalid-preserved=true wrong-entity-rejected=true invalid-runtime-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] health-options result=true health=42 max-health=42',
        '[Server thread/INFO]: [helsteraMobs] [自检] movement-speed-options result=true base=0.4',
        '[Server thread/INFO]: [helsteraMobs] [自检] knockback-resistance-options result=true base=0.75',
        '[Server thread/INFO]: [helsteraMobs] [自检] follow-range-options result=true base=40',
        '[Server thread/INFO]: [helsteraMobs] [自检] damage-options result=true base=12',
        '[Server thread/INFO]: [helsteraMobs] [自检] armor-options result=true base=8',
        '[Server thread/INFO]: [helsteraMobs] [自检] armor-toughness-stats-imported result=true imported=true base=4 attack-damage=12 movement-speed=0.4 armor=8 knockback-resistance=0.25 follow-range=36 unsupported-preserved=true modifier-preserved=true root-preserved=true controls=0,0',
        '[Server thread/INFO]: [helsteraMobs] [自检] entity-config-flags result=true no-ai=true invulnerable=true silent=true gravity=false collidable=false',
        '[Server thread/INFO]: [helsteraMobs] [自检] exp-options result=true experience=13 event=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] exp-natural-death result=true dead=true experience=13 cause=BukkitDamage',
        '[Server thread/INFO]: [helsteraMobs] [自检] exp-tier-death result=true low=2 qualified=7 low-dead=true qualified-dead=true low-bread=4 qualified-bread=4',
        '[Server thread/INFO]: [helsteraMobs] [自检] revive-natural result=true alive=true attached=true health=8.0',
        '[Server thread/INFO]: [helsteraMobs] [自检] revive-late result=true cancelled=true alive=true attached=true health=8.0 no-drops=true',
        '[Server thread/INFO]: [helsteraMobs] damage-imported mapped=true entity-damage=true attacker-name=true attacker-equipment=true environmental-damage=true environmental-no-trigger=true passed=true',
        '[Server thread/INFO]: [helsteraMobs] damage-attribution player-present=true player-attack=true target-is-player=true environmental-no-trigger=true restored=true passed=true',
        '[Server thread/INFO]: absorption-player-condition imported=true dispatched=true positive=true exact=true negative=true observed=true restored=true passed=true',
        '[Server thread/INFO]: radius-condition-details imported=true players-positive=true players-negative=true entities-positive=true entities-negative=true mobs-positive=true mobs-negative=true living-positive=true living-negative=true player-within-positive=true player-within-negative=true player-not-within-positive=true player-not-within-negative=true restored=true passed=true',
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
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-item-similarity-conditions entity-primary-mapped=true entity-alias-mapped=true entity-positive=true entity-alias-positive=true entity-negative=true entity-invalid-preserved=true inventory-primary-mapped=true inventory-alias-mapped=true inventory-positive=true inventory-alias-positive=true inventory-negative=true inventory-invalid-preserved=true result=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] visibility-options result=true visible-by-default=true invisible=true show-name=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] visibility-options-imported result=true imported=true configured=true controls=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] age-options result=true age=-24000 baby=true adult=true age-lock=true zombie-baby=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] armor-stand-options result=true can-move=false can-tick=false items=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] armor-stand-options-imported result=true imported=true configured=true controls=true item-body=true item-feet=true item-hand=true item-offhand=true item-head=true item-legs=true wrong-type-preserved=true invalid-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] entity-flag-options result=true tamed=true angry=true can-join-raid-setter=true can-join-raid=false patrol-leader=true patrol-spawn-point=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] entity-flag-options result=true imported=true wolf-tamed=true cat-tamed=true control-cat-tamed=false horse-tamed=true control-horse-tamed=false parrot-tamed=true control-parrot-tamed=false wolf-angry=true pig-zombie-angry=true can-join-raid-setter=true can-join-raid=false control-can-join-raid=true patrol-leader=true patrol-spawn-point=true wrong-type-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] glowing-option-imported result=true imported=true enabled-configured=true control-configured=true invalid-preserved=true enabled-entity=true control-entity=true invalid-entity=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] boolean-mob-options-imported result=true imported=true configured=true controls=true invalid-preserved=true entity-states=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] prevent-random-equipment-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true random-cleared=true explicit-equipment-preserved=true control-preserved=true invalid-fallback=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] environmental-damage-options-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true drowning-protected=true fall-protected=true controls-allowed=true invalid-fallback=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] fire-protection-options-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true blanket-combust-cancelled=true blanket-fire-tick-cancelled=true selective-fire-tick-cancelled=true unlisted-fire-allowed=true cross-cause-allowed=true controls-allowed=true invalid-fallback=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] dig-out-of-ground-imported result=true imported=true configured=true policies=true suffocation-cancelled=true rise=2.00 fall-control=true disabled-allowed=true invalid-fallback=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] prevent-mob-kill-drops-imported result=true imported=true configured=true policies=true killers-resolved=true drops-suppressed=true controls-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] prevent-other-drops-imported result=true imported=true configured=true policies=true native-suppressed=true custom-retained=true controls-preserved=true invalid-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] tamed-family-options result=true imported=true donkey-tamed=true control-donkey-tamed=false mule-tamed=true control-mule-tamed=false llama-tamed=true control-llama-tamed=false trader-llama-tamed=true control-trader-llama-tamed=false skeleton-horse-tamed=true control-skeleton-horse-tamed=false zombie-horse-tamed=true control-zombie-horse-tamed=false camel-tamed=true control-camel-tamed=true camel-false-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] experience-orb-options result=true imported=true experience=17 wrong-type-preserved=true invalid-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] panda-options result=true imported=true main-gene=LAZY hidden-gene=WORRIED control-main-gene=NORMAL control-hidden-gene=NORMAL wrong-type-preserved=true invalid-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] villager-options result=true imported=true type=DESERT profession=MASON level=4 has-trades=true trades-available=true control-type=PLAINS control-profession=LIBRARIAN control-level=2 control-has-trades=false no-trades=true acquire-allowed=true acquire-denied=true trading-interactable=true wrong-type-preserved=true invalid-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] passthrough-damage result=true imported=true parent-linked=true transferred=true source-preserved=true disabled-control=true orphan-fallback=true cancelled-respected=true cycle-safe=true invalid-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] parrot-flying-speed result=true imported=true configured=0.8 sentinel-unchanged=true wrong-type-preserved=true invalid-preserved=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] no-gravity-imported result=true imported=true configured=false control=true has-gravity=false has-gravity-control=true has-gravity-wrong-type-preserved=true invalid-preserved=true gravity-invalid-preserved=true has-gravity-invalid-preserved=true fallback=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] enderman-held-block result=true material=OBSIDIAN',
        '[Server thread/INFO]: attribute-options result=true modifiers=1',
        '[Server thread/INFO]: attribute-options-composite result=true attack=true speed=true armor=true dedup=true invalid-rejected=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] mob-pickup-option result=true disabled=true explicit-overrides-default=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] spawn-option-runtime result=true pickup-disabled=true explicit-pickup-overrides-fallback=true random-equipment-cleared=true explicit-equipment-preserved=true no-damage-ticks=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] imported-mob-defaults result=true imported=true builtin-pickup-disabled=true builtin-no-damage-ticks=true builtin-despawn-normal=true builtin-prevent-leashing=true builtin-prevent-transformation=true global-pickup-disabled=true global-no-damage-ticks=true global-prevent-leashing=true global-default-prevent-transformation=false global-default-ignored=true global-prevent-transformation=true global-despawn-chunk=true global-prevent-other-drops=true global-prevent-vanilla-damage=true global-unsupported-preserved=true explicit-pickup-enabled=true explicit-pickup-disabled=true pickup-wrong-type-preserved=true pickup-invalid-preserved=true explicit-jockey-prevented=true global-jockey-allowed=true explicit-no-damage-ticks=true explicit-prevent-leashing=true explicit-prevent-transformation-disabled=true explicit-despawn-never=true explicit-prevent-other-drops-disabled=true explicit-prevent-vanilla-damage-disabled=true builtin-transform-cancelled=true global-transform-cancelled=true explicit-transform-allowed=true',
        '[Server thread/INFO]: item-attribute-options result=true modifiers=1',
        '[Server thread/INFO]: item-attribute-options-composite result=true attack=true speed=true armor=true dedup=true invalid-rejected=true modifiers=3',
        '[Server thread/INFO]: [helsteraMobs] [自检] legacy-numeric-items imported=true items-material=true items-durability=true items-potion=true items-block-data=true items-amount=true legacy-id-boundaries=true equipment-material=true equipment-durability=true equipment-potion=true equipment-block-data=true equipment-amount=true invalid-preserved=true result=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] item-metadata imported=true display-name=true item-name=true lore=true enchantment=true custom-model-data=true flags=true restrictions=true potion=true skull=true invalid-preserved=true result=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] events creeper-explode-cancelled=true bee-attack-cancelled=true zombie-transform-cancelled=true prevent-transformation-reasons=true prevent-skeleton-conversion=true prevent-block-infection=true prevent-snow-formation=true tame-breed-tame=true tame-breed-breed=true leash-unleash-leash=true leash-unleash-unleash=true prevent-leash=true prevent-rename=true prevent-target=true prevent-vanilla-damage=true prevent-projectiles=true prevent-entity-explosions=true prevent-block-explosion-damage=true prevent-drowning=true prevent-fall-damage=true prevent-fire=true prevent-fire-causes=true prevent-pickup=true prevent-item-use=true interactable=true interactable-armor-stand=true prevent-teleport=true prevent-sunburn=true prevent-other-drops=true prevent-mob-kill-drops=true prevent-mob-kill-custom-loot=true shear-trigger=true bed-enter-leave-enter=true bed-enter-leave-leave=true trade-trigger=true quit-trigger=true item-drop-break-damage=true player-actions-interact=true world-change=true jump=true swing=true fish-trigger=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] prevent-teleport-imported result=true imported=true mapped=true invalid-preserved=true spawned=true protected-api-blocked=true control-api-teleported=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] explosion-options-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true blanket-protected=true block-only=true entity-only=true projectile-only=true controls-allowed=true invalid-fallback=true',
        '[Server thread/INFO]: mob-options-natural creeper-fuse=true exploded=true max-fuse=20 passed=true',
        '[Server thread/INFO]: mob-options-natural bee-stinger=true bee-event=synthetic bee-attacks=1/1 zombie-conversion=true zombie-control=true zombie-protected=true passed=true',
        '[Server thread/INFO]: mob-options-skeleton-conversion protected-events=1 control-events=1 protected-cancelled=true control-cancelled=false protected-type=SKELETON protected-valid=true control-stray=true control-converted=true elapsed-ticks=21 restored=true passed=true',
        '[Server thread/INFO]: mob-options-zombie-conversion protected-events=1 control-events=1 protected-cancelled=true control-cancelled=false protected-type=ZOMBIE protected-valid=true control-drowned=true control-converted=true elapsed-ticks=21 restored=true passed=true',
        '[Server thread/INFO]: mob-options-zombie-villager-cured protected-events=1 control-events=1 protected-cancelled=true control-cancelled=false protected-type=ZOMBIE_VILLAGER protected-valid=true control-villager=true control-converted=true elapsed-ticks=21 restored=true passed=true',
        '[Server thread/INFO]: mob-options-villager-infection protected-events=1 control-events=1 protected-cancelled=true control-cancelled=false protected-type=VILLAGER protected-villager=true protected-alive=false protected-zombie-villager=false control-zombie-villager=true control-converted=true native-attacks=true/true difficulty-restored=true elapsed-ticks=21 restored=true passed=true',
        '[Server thread/INFO]: prevent-sunburn-natural imported=true mapped=true protected-option=true control-option=false daylight=true weather-clear=true observer-present=true protected-player-distance=7 control-player-distance=15 sky-exposed=true protected-ai=true control-ai=true protected-age-ticks=90 control-age-ticks=110 protected-events=2 protected-cancelled=2 protected-fire-ticks=0 control-events=1 control-cancelled=0 control-fire-ticks=154 elapsed-ticks=40 restored=true passed=true',
        '[Server thread/INFO]: mob-options-mount-event prevented=true allowed=true natural-prevented=true natural-allowed=true natural-events=1/1 natural-cancelled=true/false native-finalize=true/true generic-prevented=true generic-allowed=true jockey-instance=true jockey-type=ZOMBIE jockey-valid=true jockey-dead=false jockey-health=20.0 jockey-vehicle=none jockey-found=true jockey-found-prevent=true jockey-ticks=240 jockey-passenger-types= passed=true',
        '[Server thread/INFO]: mob-options-slime-split prevented=true allowed=true passed=true',
        '[Server thread/INFO]: mob-options-slime-split-natural protected-size=4 control-size=4 protected-event=true protected-cancelled=true protected-event-count=4 control-event=true control-cancelled=false control-event-count=3 protected-dead=true control-dead=true protected-children=0 control-children=3 monster-spawning-enabled=true spawn-flags-restored=true damage=api passed=true',
        '[Server thread/INFO]: pathfinder-clear probe=path_clear_probe found=true reachable=true started=true active-before=true accepted=true cleared=true stop-calls=1 passed=true',
        '[Server thread/INFO]: ai-priority-probe attack-first=true flee-first=true attack-state=ATTACK flee-state=FLEE',
        '[Server thread/INFO]: ai-priority-detail probe=priority_attack_probe target-assigned=true target-valid=true health-ratio=0.5 attack-priority=0 flee-priority=1 selector=mode=PLAYERS can-attack=true can-chase=true can-flee=true target-matches=true',
        '[Server thread/INFO]: ai-selector-core goals=true player-target=true monster-target=true clear-no-fallback=true owner-target=true faction-target=true conditional-target=true players-in-world=true players-on-server=true player-aliases=true mobs-in-world=true world-caster-excluded=true world-chain-filtered=true nearest-living=true nearest-mob=true targeter-syntax=true set-target-mechanic=true clear-target-mechanic=true context-chain-action=true native-target=true selector=mode=PLAYERS can-attack=true can-chase=false conditional=is-player=>is-player entity=PLAYER passed=true passed=true',
        '[Server thread/INFO]: ai-route-probe token=followpath active-before=true moving=true advanced=true waypoint-before=route=followpath waypoint=103,64,0 direction=forward speed=1.0 tolerance=3.0 finished=false waypoint-after=route=followpath waypoint=105,64,0 direction=forward speed=1.0 tolerance=3.0 finished=false patrol-mutation=true patrol-before=route=patrol waypoint=103,64,0 direction=forward speed=1.0 tolerance=0.5 finished=false patrol-after=route=patrol waypoint=105,64,0 direction=forward speed=1.0 tolerance=0.5 finished=false patrol-loop=route=patrol waypoint=103,64,0 direction=backward speed=1.0 tolerance=0.5 finished=false passed=true',
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
        '[Server thread/INFO]: mythic-spawner-chance imported=true valid-mapped=true invalid-mapped=true valid-natural=true invalid-rejected=true warning=true valid-spawned=1 invalid-spawned=0 passed=true',
        '[Server thread/INFO]: mythic-spawner-random-pool imported=true mapped=true sampled=true expected-emberling=0.250000 expected-emberling_boss=0.750000 observed-emberling=0.240000 observed-emberling_boss=0.760000 max-abs-deviation=0.010000 samples=100 passed=true',
        '[Server thread/INFO]: mythic-spawner-drop-table imported=true references=true weighted=true tables-loaded=true spawned=32 variants=true assigned=true common-table-reloaded=true rare-table-reloaded=true common-alive=true rare-alive=true common-natural=true rare-natural=true common-bread=1 common-diamond=0 rare-bread=0 rare-diamond=1 restored=true passed=true',
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
    $lines += '[Server thread/INFO]: [helsteraMobs] [自检] interactable-client result=true false-event=true false-cancelled=true true-event=true true-cancelled=false interactions=2'
    Set-Content -LiteralPath (Join-Path $logDirectory 'latest.log') -Value $lines -Encoding utf8
    Set-Content -LiteralPath (Join-Path $temp 'menu-client.stdout.log') -Value @(
        'PAPER_MENU_REPORT {"status":"passed","observations":{"pageOne":true,"pageOneRedstone":true,"deniedClickStayedOpen":true,"paginationClick":true,"pageTwo":true,"pageTwoEmerald":true,"allowedActionMessage":true,"actionClosedInventory":true,"deniedActionMessageAbsent":true}}',
        'PAPER_INTERACTABLE_CLIENT_REPORT {"status":"passed","blockedEntityId":41,"allowedEntityId":42,"observations":{"distinctEntities":true,"blockedInteractionSent":true,"blockedEventObserved":true,"blockedCancelled":true,"allowedInteractionSent":true,"allowedEventObserved":true,"allowedCancelled":false}}'
    ) -Encoding utf8
    Set-Content -LiteralPath (Join-Path $temp 'audience-client.stdout.log') -Value @(
        'PAPER_AUDIENCE_REPORT {"status":"passed","observations":{"message":true,"title":true,"actionbar":true,"particle":true}}',
        'PAPER_AUDIENCE_FILTER_REPORT {"status":"passed","observations":{"radiusIncludedTester":true,"radiusIncludedGuest":true,"radiusExcludedTester":true,"radiusExcludedGuest":true,"permissionIncludedTester":true,"permissionIncludedGuest":true,"permissionNegativeTesterOmitted":true,"permissionNegativeGuestIncluded":true,"factionIncludedTester":true,"factionIncludedGuest":true,"factionNegativeTesterIncluded":true,"factionNegativeGuestExcluded":true}}'
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
    $audienceClientLog = Join-Path $temp 'audience-client.stdout.log'
    $validAudienceClientLines = @(Get-Content -LiteralPath $audienceClientLog -Encoding utf8)
    $falseNegativeFilterLines = @($validAudienceClientLines | ForEach-Object {
        $_ -replace '"permissionNegativeGuestIncluded":true', '"permissionNegativeGuestIncluded":false'
    })
    Set-Content -LiteralPath $audienceClientLog -Value $falseNegativeFilterLines -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null
    $falseNegativeFilterReport = Get-Content -LiteralPath (Join-Path $temp 'integration-report.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    Assert-True (($falseNegativeFilterReport.probes | Where-Object id -eq 'audience-filter-delivery').status -eq 'not-observed') 'audience filter rejects an included permission-negative tester/guest result'
    $missingNegativeFilterLines = @($validAudienceClientLines | ForEach-Object {
        $_ -replace ',"factionNegativeGuestExcluded":true', ''
    })
    Set-Content -LiteralPath $audienceClientLog -Value $missingNegativeFilterLines -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null
    $missingNegativeFilterReport = Get-Content -LiteralPath (Join-Path $temp 'integration-report.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    Assert-True (($missingNegativeFilterReport.probes | Where-Object id -eq 'audience-filter-delivery').status -eq 'not-observed') 'audience filter rejects a missing faction-negative client observation'
    Set-Content -LiteralPath $audienceClientLog -Value $validAudienceClientLines -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null
    $logPath = Join-Path $logDirectory 'latest.log'
    Set-Content -LiteralPath $logPath -Value ($lines -replace '12=GOLD_INGOT', '12=SPONGE') -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null
    $collisionFailure = Get-Content -LiteralPath (Join-Path $temp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($collisionFailure.probes | Where-Object id -eq 'custom-menu-player-workflow').status -ne 'passed') 'menu workflow rejects vanilla material in custom item collision'
    Set-Content -LiteralPath $logPath -Value $lines -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null
    $failedLookingAtLines = @($lines | ForEach-Object {
        $_ -replace 'short-entity-distance-rejected=true', 'short-entity-distance-rejected=false'
    })
    Set-Content -LiteralPath $logPath -Value $failedLookingAtLines -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null
    $lookingAtFailureReport = Get-Content -LiteralPath (Join-Path $temp 'integration-report.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    Assert-True (($lookingAtFailureReport.probes | Where-Object id -eq 'lookingAtCondition').status -eq 'not-observed') 'LookingAt probe rejects a missing entity-distance negative control'
    Set-Content -LiteralPath $logPath -Value $lines -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null

    $interactableClientLog = Join-Path $temp 'menu-client.stdout.log'
    $validInteractableClientLines = @(Get-Content -LiteralPath $interactableClientLog -Encoding utf8)
    $validInteractableClientLine = $validInteractableClientLines |
        Where-Object { $_ -match '^PAPER_INTERACTABLE_CLIENT_REPORT ' } | Select-Object -Last 1
    $invalidInteractableClientLines = @($validInteractableClientLines | ForEach-Object {
        $_ -replace '"blockedCancelled":true', '"blockedCancelled":false'
    })
    Set-Content -LiteralPath $interactableClientLog -Value $invalidInteractableClientLines -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null
    $interactableReport = Get-Content -LiteralPath (Join-Path $temp 'integration-report.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    Assert-True (($interactableReport.probes | Where-Object id -eq 'interactableClient').status -eq 'not-observed') 'Interactable rejects client evidence with an allowed protected click'
    Set-Content -LiteralPath $interactableClientLog -Value $validInteractableClientLines -Encoding utf8
    $wrongInteractableServerLines = @($lines | ForEach-Object {
        $_ -replace 'false-cancelled=true', 'false-cancelled=false'
    })
    Set-Content -LiteralPath $logPath -Value $wrongInteractableServerLines -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null
    $interactableReport = Get-Content -LiteralPath (Join-Path $temp 'integration-report.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    Assert-True (($interactableReport.probes | Where-Object id -eq 'interactableClient').status -eq 'not-observed') 'Interactable requires Paper cancellation evidence as well as the client report'
    $failedAttributeCompositeLines = @($lines | ForEach-Object {
        $_ -replace 'attribute-options-composite result=true attack=true speed=true',
            'attribute-options-composite result=false attack=false speed=false'
    })
    Set-Content -LiteralPath $logPath -Value $failedAttributeCompositeLines -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp -PluginSha256 $testPluginHash | Out-Null
    $failedAttributeCompositeReport = Get-Content -LiteralPath (Join-Path $temp 'integration-report.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    Assert-True (($failedAttributeCompositeReport.probes | Where-Object id -eq 'attributesComposite').status -eq 'not-observed') 'attribute composite failure cannot be reported as passed'
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

    $env:PAPER_SHOW_HEALTH_WORKFLOW = 'true'
    $showHealthTemp = Join-Path $temp 'show-health-client-fixture'
    New-Item -ItemType Directory -Path (Join-Path $showHealthTemp 'logs') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $showHealthTemp 'logs/latest.log') -Value @(
        'This server is running Paper version 1.21.1-133 (Implementing API version 1.21.1-R0.1-SNAPSHOT)',
        'show-health-delivery result=true imported=true spawned=true first-crossing=true second-crossing=true healing-recross=true below-threshold-quiet=true inside-band-quiet=true near-inside=true outsider-outside=true radius=25 environmental-damage=true entity-damage=true'
    ) -Encoding utf8
    $showHealthClientPayload = @{
        status = 'passed'
        observations = @{
            nearPlayerReceivedFirstThreshold = $true
            nearPlayerReceivedSecondThreshold = $true
            outsidePlayerExcluded = $true
            belowThresholdQuiet = $true
            withinBandQuiet = $true
            healingRearmedThreshold = $true
        }
        testerMessages = @('SHOW_HEALTH_PROBE|ShowHealthProbe|89|89|100', 'SHOW_HEALTH_PROBE|ShowHealthProbe|79|79|100')
        outsideMessages = @()
    } | ConvertTo-Json -Compress -Depth 5
    Set-Content -LiteralPath (Join-Path $showHealthTemp 'audience-client.stdout.log') `
        -Value "PAPER_SHOW_HEALTH_CLIENT_REPORT $showHealthClientPayload" -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $showHealthTemp | Out-Null
    $showHealthReport = Get-Content -LiteralPath (Join-Path $showHealthTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True ($showHealthReport.expectedProbeIds -contains 'showHealthDelivery' -and
        $showHealthReport.expectedProbeIds -contains 'showHealthClientDelivery') 'ShowHealth server and client probes are registered when requested'
    Assert-True (($showHealthReport.probes | Where-Object id -eq 'showHealthDelivery').status -eq 'passed') 'ShowHealth Paper runtime observation is parsed'
    Assert-True (($showHealthReport.probes | Where-Object id -eq 'showHealthClientDelivery').status -eq 'passed') 'ShowHealth real-client delivery observation is parsed'
    $showHealthClientPayload = $showHealthClientPayload | ConvertFrom-Json
    $showHealthClientPayload.observations.outsidePlayerExcluded = $false
    Set-Content -LiteralPath (Join-Path $showHealthTemp 'audience-client.stdout.log') `
        -Value ('PAPER_SHOW_HEALTH_CLIENT_REPORT ' + ($showHealthClientPayload | ConvertTo-Json -Compress -Depth 5)) -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $showHealthTemp | Out-Null
    $showHealthReport = Get-Content -LiteralPath (Join-Path $showHealthTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($showHealthReport.probes | Where-Object id -eq 'showHealthClientDelivery').status -eq 'not-observed') 'ShowHealth client probe rejects an outside-player delivery failure'
    $env:PAPER_SHOW_HEALTH_WORKFLOW = 'false'

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
            'importedIsFrozenCondition', 'importedBurningCondition', 'importedEntityCategoryCondition',
            'importedExecutionAudienceCondition', 'executionAudienceMutation', 'executionAudienceMobSpawn',
            'executionAudienceMobMutation', 'executionAudienceMobSummon', 'passthroughDamageOptions',
            'importedTargetRelationshipsCondition',
            'importedEntityTypeVariantCondition',
            'importedEntityTypeTraitsCondition',
            'importedSpyglassCondition', 'importedSurvivalModeCondition', 'importedVariableConditions',
            'importedFreeInventorySlotCondition',
            'importedNameCondition',
            'importedMobDefaults', 'healOnReloadOptions', 'mobOptionsSkeletonConversion', 'mobOptionsZombieConversion',
            'mobOptionsZombieVillagerCured', 'mobOptionsVillagerInfection',
            'importedServerConditions',
            'importedCoordinateDifferenceCondition',
            'importedPopulationConditions',
            'importedHasEnchantmentCondition', 'importedTriggerItemTypeCondition',
            'importedTriggerBlockTypeCondition', 'importedOriginLocationCondition',
            'importedMaterialCooldownCondition', 'importedItemRechargingCondition',
            'importedItemSimilarityConditions',
            'importedPlayerKillsCondition', 'importedTimerTrigger', 'contextTargeterRelations', 'lastDamagerTargeter', 'targetOfTargetTargeter', 'beeStingerLossImported', 'foxOptionsImported', 'rabbitOptionsImported', 'frogOptionsImported', 'digOutOfGroundImported', 'fireProtectionOptionsImported', 'explosionOptionsImported', 'preventMobKillDropsImported', 'preventOtherDropsImported', 'itemMetadata', 'legacyNumericItems', 'attributesComposite', 'itemAttributesComposite', 'mythicStatsBias')) {
        Assert-True ($report.expectedProbeIds -contains $requiredProbeId) "probe catalog contains $requiredProbeId"
        Assert-True (@($report.probes | Where-Object id -eq $requiredProbeId).Count -gt 0) "report contains $requiredProbeId"
    }
    Assert-True (($report.probes | Where-Object id -eq 'frogOptionsImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'frogOptionsImported').observations[0] -match
        '^frog-options result=true imported=true type=COLD alias-type=WARM control-type=TEMPERATE wrong-type-preserved=true invalid-preserved=true conflict-preserved=true$') 'imported Frog variants require mapped, alias, control, and preservation evidence'
    Assert-True $report.pluginEnabled 'plugin enabled detection'
    Assert-True (($report.probes | Where-Object id -eq 'pathfinderClear').status -eq 'passed') 'native Pathfinder clear probe'
    Assert-True (($report.probes | Where-Object id -eq 'interactableClient').status -eq 'passed') 'Interactable client/server evidence requires blocked and allowed real-client interactions'
    Assert-True ($report.expectedProbeIds -contains 'interactableClient') 'Interactable client probe is registered in the strict report catalog'
    Assert-True (($report.probes | Where-Object id -eq 'spawningDistanceClientWalk').status -eq 'passed') 'real client movement despawn probe'
    Assert-True (($report.probes | Where-Object id -eq 'importedAerialCondition').status -eq 'passed') 'imported Aerial aliases require strict Paper positive and negative evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedIsFrozenCondition').status -eq 'passed') 'imported IsFrozen aliases require strict Paper frozen/thawed and malformed-input evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedTimerTrigger').status -eq 'passed') 'imported Mythic timers require global phase, independent intervals, and invalid-value preservation'
    Assert-True (($report.probes | Where-Object id -eq 'importedBurningCondition').status -eq 'passed') 'imported Burning requires target-priority, composed-negation, caster-fallback, and invalid-preservation evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedEntityCategoryCondition').status -eq 'passed') 'imported IsWaterMob/IsZombie aliases require strict Paper type and malformed-input evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedExecutionAudienceCondition').status -eq 'passed') 'imported execution audience requires strict Paper membership and per-target filter evidence'
    Assert-True (($report.probes | Where-Object id -eq 'executionAudienceMutation').status -eq 'passed') 'execution audience mutation requires imported action, PDC, and two-client visibility evidence'
    Assert-True (($report.probes | Where-Object id -eq 'executionAudienceMobSpawn').status -eq 'passed') 'mob execution audience requires importer, real spawn, PDC, target selection, and two-client visibility evidence'
    Assert-True (($report.probes | Where-Object id -eq 'executionAudienceMobMutation').status -eq 'passed') 'mob audience mutations require imported AddToAudience/RemoveFromAudience, targeter selection, PDC, and two-client visibility evidence'
    Assert-True (($report.probes | Where-Object id -eq 'executionAudienceMobSummon').status -eq 'passed') 'summoned mobs require imported summon execution, inherited audience, PDC, and two-client visibility evidence'
    Assert-True (($report.probes | Where-Object id -eq 'passthroughDamageOptions').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'passthroughDamageOptions').observations[0] -match
        '^passthrough-damage result=true imported=true parent-linked=true transferred=true source-preserved=true disabled-control=true orphan-fallback=true cancelled-respected=true cycle-safe=true invalid-preserved=true$') 'PassthroughDamage requires imported production transfer, source, fallback, cancellation, and cycle evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedTargetRelationshipsCondition').status -eq 'passed') 'imported target relationship conditions require strict Paper owner, online-player, leash, and fail-closed evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedEntityTypeVariantCondition').status -eq 'passed') 'imported EntityType variants require strict Paper positive, alternative, wrong-variant, wrong-type, and malformed-input evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedEntityTypeTraitsCondition').status -eq 'passed') 'imported EntityType traits require strict Paper getter coverage, negative controls, and all documented API-backed trait families'
    Assert-True (($report.probes | Where-Object id -eq 'importedSpyglassCondition').status -eq 'passed') 'imported Spyglass aliases require strict Paper active/inactive, target-type, and malformed-input evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedSurvivalModeCondition').status -eq 'passed') 'imported survival aliases require strict Paper Survival/Adventure, target-type, and malformed-input evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedVariableConditions').status -eq 'passed') 'imported variable conditions require strict Paper scope, alias, range, missing-value, and quoted-argument evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedFreeInventorySlotCondition').status -eq 'passed') 'imported free-slot aliases require strict Paper empty/full inventory and invalid-argument evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedNameCondition').status -eq 'passed') 'imported Name aliases require custom-name priority, entity-name fallback, and malformed-input evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedServerConditions').status -eq 'passed') 'imported server conditions require Paper identity, version comparisons, boundary controls, and malformed-input evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedCoordinateDifferenceCondition').status -eq 'passed') 'imported coordinate difference conditions require strict Paper mapping, positive axes, out-of-range rejection, and malformed-input evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedPopulationConditions').status -eq 'passed') 'imported population conditions require strict Paper aliases, positive counts, out-of-range rejection, and malformed-input evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedColorCondition').status -eq 'passed') 'imported Color and clr require strict Paper positive and negative evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedHasEnchantmentCondition').status -eq 'passed') 'imported HasEnchantment requires strict Paper mapping, level and type evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedTriggerItemTypeCondition').status -eq 'passed') 'imported TriggerItemType requires strict Paper mapping and positive/negative evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedTriggerBlockTypeCondition').status -eq 'passed') 'imported TriggerBlockType requires strict Paper mapping, material, and state evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedOriginLocationCondition').status -eq 'passed') 'imported OriginLocation requires strict Paper mapping, exact, negative, and malformed-input evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedMaterialCooldownCondition').status -eq 'passed') 'imported MaterialIsOnCooldown aliases require strict Paper positive and negative evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedItemRechargingCondition').status -eq 'passed') 'imported ItemRecharging aliases require strict Paper positive and non-HumanEntity rejection evidence'
    Assert-True (($report.probes | Where-Object id -eq 'importedItemSimilarityConditions').status -eq 'passed') 'imported ItemIsSimilar and EntityItemIsSimilar require strict Paper mapping, positive/negative, and preservation evidence'
    Assert-True (($report.probes | Where-Object id -eq 'aiPriority').status -eq 'passed') 'AI selector priority arbitration probe'
    Assert-True (($report.probes | Where-Object id -eq 'aiPriorityTargets').status -eq 'passed') 'AI priority probe target setup'
    Assert-True (($report.probes | Where-Object id -eq 'aiSelectorCore').status -eq 'passed') 'AI selector core Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'aiRoute').status -eq 'passed') 'AI route waypoint Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'contextTargeterControls').status -eq 'passed') 'context targeter controls probe'
    Assert-True (($report.probes | Where-Object id -eq 'contextTargeterChain').status -eq 'passed') 'context targeter chain probe'
    Assert-True (($report.probes | Where-Object id -eq 'lastDamagerTargeter').status -eq 'passed') 'LastDamager targeter Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'targetOfTargetTargeter').status -eq 'passed') 'TargetOfTarget targeter Paper probe'

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
    Assert-True (($report.probes | Where-Object id -eq 'horseOptionsImported').status -eq 'passed') 'imported Horse option probe'
    Assert-True (($report.probes | Where-Object id -eq 'foxOptionsImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'foxOptionsImported').observations[0] -ceq
        'fox-options result=true imported=true type=SNOW control-type=RED wrong-type-preserved=true invalid-preserved=true') 'imported Fox option probe'
    Assert-True (($report.probes | Where-Object id -eq 'creeper').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'creeper').observations[0] -match
        'imported=true explosion-radius=5 fuse-ticks=60 super-charged=true prevent-suicide=true protected-cancelled=true control-allowed=true') 'imported Creeper options require production Paper evidence'
    Assert-True (($report.probes | Where-Object id -eq 'cat').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'cat').observations[0] -match
        'imported=true type=SIAMESE sitting=true collar=BLUE control-type=TABBY control-sitting=false control-collar=RED') 'imported Cat options require production Paper evidence'
    Assert-True (($report.probes | Where-Object id -eq 'rabbitOptionsImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'rabbitOptionsImported').observations[0] -match
        '^rabbit-options result=true imported=true type=SALT_AND_PEPPER control-type=BROWN killer-type=THE_KILLER_BUNNY angry-type=THE_KILLER_BUNNY false-killer-type=SALT_AND_PEPPER wrong-type-preserved=true invalid-preserved=true conflict-preserved=true$') 'imported Rabbit options require production Paper evidence'
    Assert-True (($report.probes | Where-Object id -eq 'fallingBlockOptionsImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'fallingBlockOptionsImported').observations[0] -ceq
        'falling-block-options-imported result=true imported=true material=OBSIDIAN control-material=SAND drop-item=false hurt-entities=true cancel-drop=true wrong-type-preserved=true invalid-preserved=true') 'imported FallingBlock options require production Paper evidence'
    Assert-True (($report.probes | Where-Object id -eq 'boatOptionsImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'boatOptionsImported').observations[0] -ceq
        'boat-options-imported result=true imported=true boat-type=SPRUCE control-type=OAK chest-type=BAMBOO wrong-type-preserved=true invalid-preserved=true conflict-preserved=true') 'imported Boat options require production Paper evidence'
    Assert-True (($report.probes | Where-Object id -eq 'entitySizeRange').status -eq 'passed') 'imported entity size ranges across supported families'
    Assert-True (($report.probes | Where-Object id -eq 'healOnReloadOptions').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'healOnReloadOptions').observations[0] -match
        '^heal-on-reload-options result=true mapped=true reload=true protected-option=true control-option=false protected-before=15(?:\.0+)? protected-after=20(?:\.0+)? control-before=15(?:\.0+)? control-after=15(?:\.0+)?$') 'HealOnReload runtime reload probe'
    Assert-True (($report.probes | Where-Object id -eq 'reinforcementsChanceOptions').status -eq 'passed') 'imported Zombie reinforcement chance and invalid-input evidence'
    Assert-True ($report.expectedProbeIds -contains 'reinforcementsChanceOptions') 'reinforcement chance probe is part of every report catalog'
    Assert-True (($report.probes | Where-Object id -eq 'ageOptions').status -eq 'passed') 'age options probe'
    Assert-True (($report.probes | Where-Object id -eq 'armorStand').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'armorStand').observations[0] -match
        '^armor-stand-options-imported result=true imported=true configured=true controls=true item-body=true item-feet=true item-hand=true item-offhand=true item-head=true item-legs=true wrong-type-preserved=true invalid-preserved=true$') 'Armor Stand Mythic options require imported production Paper readback and preservation controls'
    Assert-True (($report.probes | Where-Object id -eq 'entityFlagOptions').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'entityFlagOptions').observations[0] -match
        '^entity-flag-options result=true imported=true wolf-tamed=true cat-tamed=true control-cat-tamed=false horse-tamed=true control-horse-tamed=false parrot-tamed=true control-parrot-tamed=false wolf-angry=true pig-zombie-angry=true can-join-raid-setter=true can-join-raid=false control-can-join-raid=true patrol-leader=true patrol-spawn-point=true wrong-type-preserved=true$') 'imported entity flag options require production Paper evidence'
    Assert-True (($report.probes | Where-Object id -eq 'glowingOptionImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'glowingOptionImported').observations[0] -ceq
        'glowing-option-imported result=true imported=true enabled-configured=true control-configured=true invalid-preserved=true enabled-entity=true control-entity=true invalid-entity=true') 'Mythic Glowing requires imported Paper state readback and invalid-value preservation'
    Assert-True (($report.probes | Where-Object id -eq 'booleanMobOptionsImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'booleanMobOptionsImported').observations[0] -ceq
        'boolean-mob-options-imported result=true imported=true configured=true controls=true invalid-preserved=true entity-states=true') 'common Mythic boolean options require imported Paper state readback and malformed-value preservation'
    Assert-True (($report.probes | Where-Object id -eq 'preventRandomEquipmentImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'preventRandomEquipmentImported').observations[0] -ceq
        'prevent-random-equipment-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true random-cleared=true explicit-equipment-preserved=true control-preserved=true invalid-fallback=true') 'Mythic PreventRandomEquipment requires imported policy readback, deterministic equipment clearing, controls, and malformed-value preservation'
    Assert-True (($report.probes | Where-Object id -eq 'environmentalDamageOptionsImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'environmentalDamageOptionsImported').observations[0] -ceq
        'environmental-damage-options-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true drowning-protected=true fall-protected=true controls-allowed=true invalid-fallback=true') 'Mythic environmental damage options require imported policy readback, cause-specific cancellation, controls, and malformed-value preservation'
    Assert-True (($report.probes | Where-Object id -eq 'fireProtectionOptionsImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'fireProtectionOptionsImported').observations[0] -ceq
        'fire-protection-options-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true blanket-combust-cancelled=true blanket-fire-tick-cancelled=true selective-fire-tick-cancelled=true unlisted-fire-allowed=true cross-cause-allowed=true controls-allowed=true invalid-fallback=true') 'Mythic fire protection requires imported blanket and per-cause behavior, inverse/cross-cause controls, and malformed-value preservation'
    Assert-True (($report.probes | Where-Object id -eq 'explosionOptionsImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'explosionOptionsImported').observations[0] -ceq
        'explosion-options-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true blanket-protected=true block-only=true entity-only=true projectile-only=true controls-allowed=true invalid-fallback=true') 'Mythic projectile/explosion guards require imported cause-specific behavior, controls, and malformed-value preservation'
    Assert-True (($report.probes | Where-Object id -eq 'digOutOfGroundImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'digOutOfGroundImported').observations[0] -match
        '^dig-out-of-ground-imported result=true imported=true configured=true policies=true suffocation-cancelled=true rise=2\.00 fall-control=true disabled-allowed=true invalid-fallback=true$') 'Mythic DigOutOfGround requires a two-block suffocation response, cause/disabled controls, and malformed-value fallback'
    Assert-True (($report.probes | Where-Object id -eq 'preventMobKillDropsImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'preventMobKillDropsImported').observations[0] -ceq
        'prevent-mob-kill-drops-imported result=true imported=true configured=true policies=true killers-resolved=true drops-suppressed=true controls-preserved=true') 'Mythic PreventMobKillDrops requires imported killer policy, suppressed target drops, and false/malformed controls'
    Assert-True (($report.probes | Where-Object id -eq 'preventOtherDropsImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'preventOtherDropsImported').observations[0] -ceq
        'prevent-other-drops-imported result=true imported=true configured=true policies=true native-suppressed=true custom-retained=true controls-preserved=true invalid-preserved=true') 'Mythic PreventOtherDrops requires imported native-drop suppression, custom DropTable retention, false control, and malformed-value preservation'
    Assert-True (($report.probes | Where-Object id -eq 'tamedFamilyOptions').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'tamedFamilyOptions').observations[0] -match
        '^tamed-family-options result=true imported=true donkey-tamed=true control-donkey-tamed=false mule-tamed=true control-mule-tamed=false llama-tamed=true control-llama-tamed=false trader-llama-tamed=true control-trader-llama-tamed=false skeleton-horse-tamed=true control-skeleton-horse-tamed=false zombie-horse-tamed=true control-zombie-horse-tamed=false camel-tamed=true control-camel-tamed=true camel-false-preserved=true$') 'Tamed option family probe requires supported readback plus Camel false-state preservation'
    Assert-True (($report.probes | Where-Object id -eq 'experienceOrbOptions').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'experienceOrbOptions').observations[0] -match
        '^experience-orb-options result=true imported=true experience=17 wrong-type-preserved=true invalid-preserved=true$') 'Experience option requires imported orb readback and wrong-type/invalid preservation'
    Assert-True (($report.probes | Where-Object id -eq 'pandaOptions').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'pandaOptions').observations[0] -match
        '^panda-options result=true imported=true main-gene=LAZY hidden-gene=WORRIED control-main-gene=NORMAL control-hidden-gene=NORMAL wrong-type-preserved=true invalid-preserved=true$') 'Panda genes require imported Paper readback plus wrong-entity and invalid-value preservation'
    Assert-True (($report.probes | Where-Object id -eq 'villagerOptions').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'villagerOptions').observations[0] -match
        '^villager-options result=true imported=true type=DESERT profession=MASON level=4 has-trades=true trades-available=true control-type=PLAINS control-profession=LIBRARIAN control-level=2 control-has-trades=false no-trades=true acquire-allowed=true acquire-denied=true trading-interactable=true wrong-type-preserved=true invalid-preserved=true$') 'Villager options require imported Paper readback plus wrong-entity and invalid-value preservation'
    Assert-True (($report.probes | Where-Object id -eq 'parrotFlyingSpeed').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'parrotFlyingSpeed').observations[0] -match
        '^parrot-flying-speed result=true imported=true configured=0\.8 sentinel-unchanged=true wrong-type-preserved=true invalid-preserved=true$') 'Parrot FlyingSpeed requires imported attribute readback, sentinel control, and preservation'
    Assert-True (($report.probes | Where-Object id -eq 'noGravityImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'noGravityImported').observations[0] -match
        '^no-gravity-imported result=true imported=true configured=false control=true has-gravity=false has-gravity-control=true has-gravity-wrong-type-preserved=true invalid-preserved=true gravity-invalid-preserved=true has-gravity-invalid-preserved=true fallback=true$') 'imported NoGravity requires production readback and malformed-value preservation'
    Assert-True (($report.probes | Where-Object id -eq 'expOptions').status -eq 'passed') 'experience option probe'
    Assert-True (($report.probes | Where-Object id -eq 'expNaturalDeath').status -eq 'passed') 'natural experience death probe'
    Assert-True (($report.probes | Where-Object id -eq 'expTierDeath').status -eq 'passed') 'natural tiered experience death probe'
    Assert-True (($report.probes | Where-Object id -eq 'reviveNatural').status -eq 'passed') 'natural revive probe'
    Assert-True (($report.probes | Where-Object id -eq 'reviveLate').status -eq 'passed') 'late cancelled revive probe'
    Assert-True (($report.probes | Where-Object id -eq 'damageImported').status -eq 'passed') 'imported damage trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'damageAttribution').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'damageAttribution').observations[0] -match
        '^damage-attribution player-present=true player-attack=true target-is-player=true environmental-no-trigger=true restored=true passed=true$') 'real Player damage attribution probe'
    Assert-True (($report.probes | Where-Object id -eq 'itemMetadata').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'itemMetadata').observations[0] -match
        '^item-metadata imported=true display-name=true item-name=true lore=true enchantment=true custom-model-data=true flags=true(?: flag-values=\[[^]]*\])? restrictions=true potion=true skull=true invalid-preserved=true result=true$') 'imported item metadata probe'
    $dynamicMetadataTemp = Join-Path $temp 'dynamic-item-metadata'
    New-Item -ItemType Directory -Path (Join-Path $dynamicMetadataTemp 'logs') -Force | Out-Null
    @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: Done (12.345s)! For help, type "help"',
        '[Server thread/INFO]: [helsteraMobs] [自检] item-metadata imported=true display-name=true item-name=true lore=true enchantment=true custom-model-data=true flags=true flag-values=[HIDE_ENCHANTS, HIDE_UNBREAKABLE] restrictions=true potion=true skull=true invalid-preserved=true result=true'
    ) | Set-Content -LiteralPath (Join-Path $dynamicMetadataTemp 'logs/latest.log') -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $dynamicMetadataTemp | Out-Null
    $dynamicMetadataReport = Get-Content -LiteralPath (Join-Path $dynamicMetadataTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($dynamicMetadataReport.probes | Where-Object id -eq 'itemMetadata').status -eq 'passed' -and
        ($dynamicMetadataReport.probes | Where-Object id -eq 'itemMetadata').observations[0] -match
        'flags=true flag-values=\[HIDE_ENCHANTS, HIDE_UNBREAKABLE\] restrictions=true') 'dynamic imported item metadata probe'
    Assert-True (($report.probes | Where-Object id -eq 'absorptionPlayerCondition').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'absorptionPlayerCondition').observations[0] -ceq
        'absorption-player-condition imported=true dispatched=true positive=true exact=true negative=true observed=true restored=true passed=true') 'real Player absorption condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'radiusConditions').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'radiusConditions').observations[0] -ceq
        'radius-condition-details imported=true players-positive=true players-negative=true entities-positive=true entities-negative=true mobs-positive=true mobs-negative=true living-positive=true living-negative=true player-within-positive=true player-within-negative=true player-not-within-positive=true player-not-within-negative=true restored=true passed=true') 'Paper radius condition probe'
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
    Assert-True (($report.probes | Where-Object id -eq 'visibilityOptions').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'visibilityOptions').observations[0] -match
        '^visibility-options-imported result=true imported=true configured=true controls=true$') 'visibility option probe requires imported production Paper readback and inverse controls'
    Assert-True (($report.probes | Where-Object id -eq 'endermanHeldBlock').status -eq 'passed') 'Enderman held block option probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnOptionRuntime').status -eq 'passed') 'spawn option runtime probe'
    Assert-True (($report.probes | Where-Object id -eq 'mobPickupOption').status -eq 'passed') 'mob pickup option probe'
    Assert-True (($report.probes | Where-Object id -eq 'importedMobDefaults').status -eq 'passed') 'imported mob defaults Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'sheep').status -eq 'not-observed') 'missing probe'
    $sheepOptionsTemp = Join-Path $temp 'sheep-options-fixture'
    $sheepOptionsLogDirectory = Join-Path $sheepOptionsTemp 'logs'
    New-Item -ItemType Directory -Path $sheepOptionsLogDirectory -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $sheepOptionsLogDirectory 'latest.log') -Value @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: Done (12.345s)! For help, type "help"',
        '[Server thread/INFO]: [helsteraMobs] [自检] sheep-options result=true imported=true color=RED sheared=true control-color=WHITE control-sheared=false'
    ) -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $sheepOptionsTemp | Out-Null
    $sheepOptionsReport = Get-Content -LiteralPath (Join-Path $sheepOptionsTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($sheepOptionsReport.probes | Where-Object id -eq 'sheep').status -eq 'passed' -and
        ($sheepOptionsReport.probes | Where-Object id -eq 'sheep').observations[0] -match
        'imported=true color=RED sheared=true control-color=WHITE control-sheared=false') 'report writer requires imported Sheep option evidence'
    $horseOptionsTemp = Join-Path $temp 'horse-options-fixture'
    $horseOptionsLogDirectory = Join-Path $horseOptionsTemp 'logs'
    New-Item -ItemType Directory -Path $horseOptionsLogDirectory -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $horseOptionsLogDirectory 'latest.log') -Value @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: Done (12.345s)! For help, type "help"',
        '[Server thread/INFO]: [helsteraMobs] [自检] horse-options result=true imported=true color=GRAY style=WHITE_DOTS domestication=5 max-domestication=20 jump-strength=0.8 saddled=true armor=DIAMOND_HORSE_ARMOR control-color=BLACK control-style=BLACK_DOTS control-domestication=1 control-max-domestication=12 control-jump-strength=0.4 control-saddled=false control-armor=AIR donkey-chest=true control-donkey-chest=false pig-saddled=true control-pig-saddled=false strider-saddled=true control-strider-saddled=false'
    ) -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $horseOptionsTemp | Out-Null
    $horseOptionsReport = Get-Content -LiteralPath (Join-Path $horseOptionsTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($horseOptionsReport.probes | Where-Object id -eq 'horseOptionsImported').status -eq 'passed' -and
        ($horseOptionsReport.probes | Where-Object id -eq 'horseOptionsImported').observations[0] -match
        'imported=true color=GRAY style=WHITE_DOTS domestication=5 max-domestication=20 jump-strength=0\.8 saddled=true armor=DIAMOND_HORSE_ARMOR control-color=BLACK control-style=BLACK_DOTS control-domestication=1 control-max-domestication=12 control-jump-strength=0\.4 control-saddled=false control-armor=AIR donkey-chest=true control-donkey-chest=false pig-saddled=true control-pig-saddled=false strider-saddled=true control-strider-saddled=false') 'report writer requires imported Horse/Donkey inventory options and Pig/Strider saddle true/false controls'
    $interactionOptionsTemp = Join-Path $temp 'interaction-options-fixture'
    $interactionOptionsLogDirectory = Join-Path $interactionOptionsTemp 'logs'
    New-Item -ItemType Directory -Path $interactionOptionsLogDirectory -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $interactionOptionsLogDirectory 'latest.log') -Value @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: Done (12.345s)! For help, type "help"',
        '[Server thread/INFO]: [helsteraMobs] [自检] interaction-options-imported result=true imported=true configured-height=2.5 configured-width=3.25 responsive=false height-only-width=1.75 defaults-applied=true wrong-type-preserved=true invalid-preserved=true'
    ) -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $interactionOptionsTemp | Out-Null
    $interactionOptionsReport = Get-Content -LiteralPath (Join-Path $interactionOptionsTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($interactionOptionsReport.probes | Where-Object id -eq 'interactionOptionsImported').status -eq 'passed' -and
        ($interactionOptionsReport.probes | Where-Object id -eq 'interactionOptionsImported').observations[0] -match
        '^interaction-options-imported result=true imported=true configured-height=2\.5 configured-width=3\.25 responsive=false height-only-width=1\.75 defaults-applied=true wrong-type-preserved=true invalid-preserved=true$') 'report writer requires imported Interaction option evidence'
    $goatOptionsTemp = Join-Path $temp 'goat-options-fixture'
    $goatOptionsLogDirectory = Join-Path $goatOptionsTemp 'logs'
    New-Item -ItemType Directory -Path $goatOptionsLogDirectory -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $goatOptionsLogDirectory 'latest.log') -Value @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: Done (12.345s)! For help, type "help"',
        '[Server thread/INFO]: [helsteraMobs] [自检] goat-options result=true imported=true screaming=true control-screaming=false default-screaming=false wrong-type-preserved=true invalid-preserved=true'
    ) -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $goatOptionsTemp | Out-Null
    $goatOptionsReport = Get-Content -LiteralPath (Join-Path $goatOptionsTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($goatOptionsReport.probes | Where-Object id -eq 'goatOptionsImported').status -eq 'passed' -and
        ($goatOptionsReport.probes | Where-Object id -eq 'goatOptionsImported').observations[0] -match
        '^goat-options result=true imported=true screaming=true control-screaming=false default-screaming=false wrong-type-preserved=true invalid-preserved=true$') 'report writer requires imported Goat option evidence'
    $tameableOptionsTemp = Join-Path $temp 'tameable-options-fixture'
    $tameableOptionsLogDirectory = Join-Path $tameableOptionsTemp 'logs'
    New-Item -ItemType Directory -Path $tameableOptionsLogDirectory -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $tameableOptionsLogDirectory 'latest.log') -Value @(
        'This server is running Paper version 1.21.1-test (Implementing API version 1.21.1-test-api)',
        '[Server thread/INFO]: [helsteraMobs] Enabling helsteraMobs v1.0.0',
        '[Server thread/INFO]: Done (12.345s)! For help, type "help"',
        '[Server thread/INFO]: [helsteraMobs] [自检] tameable-options result=true imported=true allow-cancelled=false deny-cancelled=true default-cancelled=true invalid-cancelled=true unmanaged-cancelled=false wrong-type-preserved=true invalid-preserved=true global-default-ignored=true'
    ) -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $tameableOptionsTemp | Out-Null
    $tameableOptionsReport = Get-Content -LiteralPath (Join-Path $tameableOptionsTemp 'integration-report.json') -Raw -Encoding utf8 | ConvertFrom-Json
    Assert-True (($tameableOptionsReport.probes | Where-Object id -eq 'tameableOptionsImported').status -eq 'passed' -and
        ($tameableOptionsReport.probes | Where-Object id -eq 'tameableOptionsImported').observations[0] -match
        '^tameable-options result=true imported=true allow-cancelled=false deny-cancelled=true default-cancelled=true invalid-cancelled=true unmanaged-cancelled=false wrong-type-preserved=true invalid-preserved=true global-default-ignored=true$') 'report writer requires imported Tameable option evidence'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:reject').status -eq 'observed-rejected') 'rejected probe'
    Assert-True (($report.probes | Where-Object id -eq 'lootDynamicChance').status -eq 'passed') 'dynamic chance probe'
    $events = @($report.probes | Where-Object id -eq 'events')
    Assert-True ($events.Count -eq 1 -and $events[0].status -eq 'passed') 'single passing events probe'
    Assert-True ($events[0].observations[0] -match 'creeper-explode-cancelled=true bee-attack-cancelled=true zombie-transform-cancelled=true .*prevent-transformation-reasons=true .*prevent-skeleton-conversion=true .*prevent-block-infection=true .*prevent-snow-formation=true .*prevent-leash=true .*prevent-rename=true .*prevent-target=true .*prevent-vanilla-damage=true .*prevent-projectiles=true .*prevent-entity-explosions=true .*prevent-block-explosion-damage=true .*prevent-drowning=true .*prevent-fall-damage=true .*prevent-fire=true .*prevent-fire-causes=true .*prevent-pickup=true .*prevent-item-use=true .*interactable=true .*interactable-armor-stand=true .*prevent-teleport=true .*prevent-sunburn=true .*prevent-other-drops=true .*prevent-mob-kill-drops=true .*prevent-mob-kill-custom-loot=true') 'all event policies cancelled'
    Assert-True (($report.probes | Where-Object id -eq 'preventTeleportImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'preventTeleportImported').observations[0] -ceq
        'prevent-teleport-imported result=true imported=true mapped=true invalid-preserved=true spawned=true protected-api-blocked=true control-api-teleported=true') 'imported PreventTeleport requires real Paper Entity#teleport evidence and an inverse control'
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
    Assert-True (($report.probes | Where-Object id -eq 'mobOptionsNatural').status -eq 'passed') 'synthetic Bee attack event and Zombie conversion probe'
    Assert-True (($report.probes | Where-Object id -eq 'mobOptionsNatural').observations[0] -match 'bee-event=synthetic') 'combined Mob Options probe labels the Bee event as synthetic'
    Assert-True (($report.probes | Where-Object id -eq 'mobOptionsSkeletonConversion').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'mobOptionsSkeletonConversion').observations[0] -match
        '^mob-options-skeleton-conversion protected-events=[1-9][0-9]* control-events=[1-9][0-9]* protected-cancelled=true control-cancelled=false protected-type=SKELETON protected-valid=true control-stray=true control-converted=true elapsed-ticks=[1-9][0-9]* restored=true passed=true$') 'native Skeleton conversion timing probe'
    Assert-True (($report.probes | Where-Object id -eq 'mobOptionsZombieConversion').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'mobOptionsZombieConversion').observations[0] -match
        '^mob-options-zombie-conversion protected-events=[1-9][0-9]* control-events=[1-9][0-9]* protected-cancelled=true control-cancelled=false protected-type=ZOMBIE protected-valid=true control-drowned=true control-converted=true elapsed-ticks=[1-9][0-9]* restored=true passed=true$') 'native Zombie conversion timing probe'
    Assert-True (($report.probes | Where-Object id -eq 'mobOptionsZombieVillagerCured').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'mobOptionsZombieVillagerCured').observations[0] -match
        '^mob-options-zombie-villager-cured protected-events=[1-9][0-9]* control-events=[1-9][0-9]* protected-cancelled=true control-cancelled=false protected-type=ZOMBIE_VILLAGER protected-valid=true control-villager=true control-converted=true elapsed-ticks=[1-9][0-9]* restored=true passed=true$') 'native Zombie Villager CURED conversion timing probe'
    Assert-True (($report.probes | Where-Object id -eq 'mobOptionsVillagerInfection').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'mobOptionsVillagerInfection').observations[0] -match
        '^mob-options-villager-infection protected-events=[1-9][0-9]* control-events=[1-9][0-9]* protected-cancelled=true control-cancelled=false protected-type=VILLAGER protected-villager=true protected-alive=false protected-zombie-villager=false control-zombie-villager=true control-converted=true native-attacks=true/true difficulty-restored=true elapsed-ticks=[1-9][0-9]* restored=true passed=true$') 'native Villager INFECTION conversion timing probe'
    Assert-True (($report.probes | Where-Object id -eq 'beeAngerImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'beeAngerImported').observations[0] -match
        '^bee-anger-imported result=true imported=true configured-anger=1200 control-anger=0$') 'Bee anger probe requires imported values and inverse control'
    Assert-True (($report.probes | Where-Object id -eq 'preventSunburnNatural').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'preventSunburnNatural').observations[0] -match
        '^prevent-sunburn-natural imported=true mapped=true protected-option=true control-option=false daylight=true weather-clear=true observer-present=true protected-player-distance=[0-9]+ control-player-distance=[0-9]+ sky-exposed=true protected-ai=true control-ai=true protected-age-ticks=[1-9][0-9]* control-age-ticks=[1-9][0-9]* protected-events=[1-9][0-9]* protected-cancelled=[1-9][0-9]* protected-fire-ticks=(-1|0) control-events=[1-9][0-9]* control-cancelled=0 control-fire-ticks=[1-9][0-9]* elapsed-ticks=[1-9][0-9]* restored=true passed=true$') 'PreventSunburn requires nearby player, ticking entities, native daylight combustion, and inverse control'
    Assert-True (($report.probes | Where-Object id -eq 'beeStingerLossImported').status -eq 'passed' -and
        ($report.probes | Where-Object id -eq 'beeStingerLossImported').observations[0] -match
        '^bee-stinger-loss-imported result=true imported=true target-entity=ZOMBIE target-ai=false target-invulnerable=false target-resistance-amplifier=3 protected-option=true control-option=false protected-attacks=[1-9][0-9]* control-attacks=[1-9][0-9]* protected-events=[1-9][0-9]* control-events=[1-9][0-9]* protected-cancelled=0 control-cancelled=0 protected-restored=true control-retained=true protected-near=true control-near=true trigger=entity-target-api event=native-ai$') 'imported Bee stinger option requires real AI attacks and an inverse control'
    Assert-True (($report.probes | Where-Object id -eq 'mobOptionsMountEvent').status -eq 'passed') 'jockey mount event probe'
    Assert-True (($report.probes | Where-Object id -eq 'mobOptionsMountEvent').observations[0] -match
        'natural-prevented=true natural-allowed=true natural-events=[1-9][0-9]*/[1-9][0-9]* natural-cancelled=true/false native-finalize=(true|false)/true') 'natural AI jockey mount probe'
    Assert-True (($report.probes | Where-Object id -eq 'mobOptionsSlimeSplit').status -eq 'passed') 'slime split option probe'
    $naturalSlimeSplit = @($report.probes | Where-Object id -eq 'mobOptionsSlimeSplitNatural')
    Assert-True ($naturalSlimeSplit.Count -eq 1 -and $naturalSlimeSplit[0].status -eq 'passed' -and
        $naturalSlimeSplit[0].observations[0] -match '^mob-options-slime-split-natural protected-size=4 control-size=4 protected-event=true protected-cancelled=true protected-event-count=[1-9][0-9]* control-event=true control-cancelled=false control-event-count=[1-9][0-9]* protected-dead=true control-dead=true protected-children=0 control-children=[1-9][0-9]* .*monster-spawning-enabled=true spawn-flags-restored=true damage=api passed=true$') 'natural Slime split requires native death, cancelled protected event, real control children, and restored spawn flags'
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
    $mythicStatsProbe = @($report.probes | Where-Object id -eq 'mythicStatsBias')
    Assert-True ($mythicStatsProbe.Count -eq 1 -and $report.expectedProbeIds -contains 'mythicStatsBias') 'native Mythic stats probe is registered as required evidence'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootTriggerConditions').status -eq 'passed') 'killer trigger conditions probe'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootTriggerConditions').observations[0] -match 'effective-luck=1(?:\.0+)? luck-scaled-drop=true') 'Luck attribute drives a chance-scaled natural drop'
    Assert-True (($report.probes | Where-Object id -eq 'skillTeleport').status -eq 'passed') 'teleport mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'mythicTeleport').status -eq 'passed') 'imported Mythic teleport Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'mythicTeleport').observations[0] -match
        'imported=true entity-target=true location-target=true safe-blocked=true unsafe-bypass=true spread-bounded=true yaw-preserved=true orientation-override=true invalid-preserved=true warnings=true restored=true passed=true') 'Mythic teleport probe requires complete importer and runtime evidence'
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
    Assert-True (($report.probes | Where-Object id -eq 'importedPlayerKillsCondition').status -eq 'passed') 'imported player kills condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'mythicMobTypeCondition').status -eq 'passed') 'mythic mob type condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'variableContainsCondition').status -eq 'passed') 'variable contains condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionCatalog').status -eq 'passed') 'condition catalog probe'
    Assert-True (($report.probes | Where-Object id -eq 'enderDragonAliveCondition').status -eq 'passed') 'EnderDragonAlive positive/negative probe'
    Assert-True (($report.probes | Where-Object id -eq 'projectileHasEndedCondition').status -eq 'passed') 'projectile ended condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'absorptionCondition').status -eq 'passed') 'absorption condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'healthAbsorptionCondition').status -eq 'passed') 'health absorption condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'potionEffectCondition').status -eq 'passed') 'potion effect condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'auraStacksCondition').status -eq 'passed') 'aura stacks condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'mythicAuraRuntime').status -eq 'passed') 'Mythic Aura runtime probe'
    Assert-True ($report.expectedProbeIds -contains 'mythicAuraRuntime') 'Mythic Aura runtime is required in the strict catalog'
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
    Assert-True (($report.probes | Where-Object id -eq 'lineOfSightCondition').status -eq 'passed') 'line of sight condition probe'
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
    Assert-True (($report.probes | Where-Object id -eq 'invulnerableCondition').status -eq 'passed') 'imported invulnerability condition probe'
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
    Assert-True (($report.probes | Where-Object id -eq 'conditionMobilityStates').status -eq 'passed') 'condition mobility states probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionMobilityStates').observations[0] -ceq 'condition-mobility-states result=true falling-positive=true falling-negative=true climbing-positive=true climbing-negative=true charged-positive=true charged-negative=true restored=true aliases=true') 'condition mobility states require native state evidence'
    Assert-True (($report.probes | Where-Object id -eq 'groundedCondition').status -eq 'passed') 'imported Grounded condition Paper probe'
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
    $lookingAtProbe = $report.probes | Where-Object id -eq 'lookingAtCondition'
    Assert-True ($lookingAtProbe.status -eq 'passed') 'imported LookingAt probe'
    Assert-True ($lookingAtProbe.observations[0] -match 'primary-mapped=true alias-mapped=true entity-mapped=true positive=true alias-positive=true wrong-block-rejected=true short-block-distance-rejected=true negative=true entity-positive=true wrong-entity-rejected=true short-entity-distance-rejected=true invalid-rejected=true restored=true') 'imported LookingAt requires mapped selectors, real ray positives, negative controls, invalid preservation, and cleanup'
    Assert-True (($report.probes | Where-Object id -eq 'itemRechargingCondition').status -eq 'passed') 'item recharging condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'gameplayPlaceholders').status -eq 'passed') 'gameplay placeholder probe'
    Assert-True ((($report.probes | Where-Object id -eq 'gameplayPlaceholders').observations -join ' ') -match 'attack-cooldown-placeholder result=true in-range=true recharging=true caster=\S+ target=\S+ aliases=true non-player-unresolved=true') 'gameplay placeholders require live scoped attack-cooldown evidence'
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
    Assert-True (($report.probes | Where-Object id -eq 'mythicSpawnerChanceImported').status -eq 'passed') 'imported Mythic spawner Chance natural scheduler probe'
    Assert-True (($report.probes | Where-Object id -eq 'mythicSpawnerRandomPoolImported').status -eq 'passed') 'imported Mythic weighted random pool probe'
    Assert-True ($report.expectedProbeIds -contains 'mythicSpawnerRandomPoolImported') 'imported Mythic weighted random pool is required in the catalog'
    Assert-True (($report.probes | Where-Object id -eq 'mythicSpawnerRandomPoolDropTables').status -eq 'passed') 'imported Mythic random pool variants retain their own natural DropTables'
    Assert-True ($report.expectedProbeIds -contains 'mythicSpawnerRandomPoolDropTables') 'random pool DropTable coupling is required in the catalog'
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
            'deathLootBonuses', 'deathLootImportedDropTable', 'mythicStatsBias')) {
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

    $syntheticStatsLogPath = Join-Path $syntheticDeathLootTemp 'logs/latest.log'
    $syntheticStatsLine = '[Server thread/INFO]: [helsteraMobs] [自检] mythic-stats result=true default=true base=0.0 killer-priority=true unknown-rejected=true disabled-rejected=true positive-weighted=true positive-rare=684 negative-weighted=true unknown-fail-closed=true restored=true dropper-removed=true'
    Add-Content -LiteralPath $syntheticStatsLogPath -Encoding utf8 -Value $syntheticStatsLine
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $syntheticDeathLootTemp | Out-Null
    $syntheticStatsReport = Get-Content -LiteralPath $syntheticDeathLootReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $syntheticStatsProbe = @($syntheticStatsReport.probes | Where-Object id -eq 'mythicStatsBias')
    Assert-True ($syntheticStatsProbe.Count -eq 1 -and $syntheticStatsProbe[0].status -eq 'passed') 'complete native Mythic stats observation passes report generation'
    Assert-True ($syntheticStatsReport.expectedProbeIds -contains 'mythicStatsBias') 'native Mythic stats remains required in the release catalog'

    $syntheticStatsLine = $syntheticStatsLine.Replace('restored=true', 'restored=false')
    $syntheticStatsLog = @(Get-Content -LiteralPath $syntheticStatsLogPath -Encoding utf8)
    $syntheticStatsLog[-1] = "[Server thread/INFO]: [helsteraMobs] [自检] $($syntheticStatsLine -replace '^\[Server thread/INFO\]: \[helsteraMobs\] \[自检\] ', '')"
    Set-Content -LiteralPath $syntheticStatsLogPath -Encoding utf8 -Value $syntheticStatsLog
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $syntheticDeathLootTemp | Out-Null
    $syntheticStatsReport = Get-Content -LiteralPath $syntheticDeathLootReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $syntheticStatsProbe = @($syntheticStatsReport.probes | Where-Object id -eq 'mythicStatsBias')
    Assert-True ($syntheticStatsProbe.Count -eq 1 -and $syntheticStatsProbe[0].status -eq 'not-observed') 'stats cleanup failure is rejected by report generation'

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
        paperVersion = '1.21.1-133-test'
        paperApiVersion = '1.21.1-R0.1-SNAPSHOT'
        pluginEnabled = $true
        log = 'logs/latest.log'
        expectedProbeIds = @('positive')
        probes = @(@{ id = 'positive'; status = 'passed'; observations = @('ok') })
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $targetGateReportPath -Encoding utf8
    & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $targetGateReportPath `
        -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1'
    & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $targetGateReportPath `
        -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1' -ExpectedPaperBuild '1.21.1-133'
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
    $fullCatalogVisibility = @($fullCatalogReport.probes | Where-Object id -eq 'visibilityOptions')
    Assert-True ($fullCatalogVisibility.Count -eq 1) 'synthetic full catalog contains the visibility options probe'
    $fullCatalogVisibility[0].observations = @('visibility-options-imported result=true imported=true configured=true controls=true')
    $fullCatalogArmorStand = @($fullCatalogReport.probes | Where-Object id -eq 'armorStand')
    Assert-True ($fullCatalogArmorStand.Count -eq 1) 'synthetic full catalog contains the Armor Stand options probe'
    $fullCatalogArmorStand[0].observations = @(
        'armor-stand-options-imported result=true imported=true configured=true controls=true item-body=true item-feet=true item-hand=true item-offhand=true item-head=true item-legs=true wrong-type-preserved=true invalid-preserved=true')
    $fullCatalogInvulnerability = @($fullCatalogReport.probes | Where-Object id -eq 'invulnerableCondition')
    Assert-True ($fullCatalogInvulnerability.Count -eq 1) 'synthetic full catalog contains the invulnerability condition probe'
    $fullCatalogInvulnerability[0].observations = @(
        'skill-actions invulnerable-condition=true invulnerable-aliases=true')
    $fullCatalogTargetFluids = @($fullCatalogReport.probes | Where-Object id -eq 'targetFluidConditions')
    Assert-True ($fullCatalogTargetFluids.Count -eq 1) 'synthetic full catalog contains the target fluid condition probe'
    $fullCatalogTargetFluids[0].observations = @(
        'target-fluids-delayed imported=true water=true lava=true invalid-preserved=true restored=true passed=true')
    $gameplayPlaceholderProbe = $fullCatalogReport.probes | Where-Object id -eq 'gameplayPlaceholders'
    $gameplayPlaceholderProbe.observations += 'attack-cooldown-placeholder result=true in-range=true recharging=true caster=0.5 target=0.5 aliases=true non-player-unresolved=true'
    $fullCatalogConditionMobility = @($fullCatalogReport.probes | Where-Object id -eq 'conditionMobility')
    Assert-True ($fullCatalogConditionMobility.Count -eq 1) 'synthetic full catalog contains the condition mobility probe'
    $fullCatalogConditionMobility[0].observations = @($fullCatalogConditionMobility[0].observations | ForEach-Object {
        $_ -replace 'condition-mobility=true', 'condition-mobility=true moving-default-stationary=true moving-exact-fractional=true moving-alias-fractional=true moving-approx-same-block=true moving-approx-block=true moving-exact-argument=true moving-e-argument=true moving-invalid-rejected=true moving-imported=true moving-restored=true'
    })
    $fullCatalogSkillTrigger = @($fullCatalogReport.probes | Where-Object id -eq 'skillTrigger')
    Assert-True ($fullCatalogSkillTrigger.Count -eq 1) 'synthetic full catalog contains the skill trigger probe'
    $fullCatalogSkillTrigger[0].observations = @($fullCatalogSkillTrigger[0].observations | ForEach-Object {
        if ($_ -match 'attack=true') { $_ } else { "$_ attack=true" }
    })
    foreach ($requiredKillProbe in @('killTrigger', 'playerKillTrigger')) {
        $fullCatalogKillProbe = @($fullCatalogReport.probes | Where-Object id -eq $requiredKillProbe)
        Assert-True ($fullCatalogKillProbe.Count -eq 1) "synthetic full catalog contains the $requiredKillProbe probe"
        $fullCatalogKillProbe[0].observations = @($fullCatalogKillProbe[0].observations | ForEach-Object {
            if ($_ -match 'kill-trigger imported=true mapped=true natural=true' -or
                    $_ -match 'player-kill-trigger imported=true mapped=true natural=true') {
                $_
            } elseif ($requiredKillProbe -eq 'killTrigger') {
                $_ -replace 'kill-trigger natural=true', 'kill-trigger imported=true mapped=true natural=true'
            } else {
                $_ -replace 'player-kill-trigger natural=true', 'player-kill-trigger imported=true mapped=true natural=true'
            }
        })
    }
    $fullCatalogReport.expectedProbeIds += 'importedSignalTrigger'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedSignalTrigger'; status = 'passed'; observations = @('imported-signal-trigger imported=true mapped=true generic=true selector=true payload=true sender=true trigger=true invalid-preserved=true last-signal=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedTimerTrigger'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedTimerTrigger'; status = 'passed'; observations = @('imported-timer-trigger imported=true mapped=true aligned=true independent=true invalid-preserved=true source=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'localDifficultyCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'localDifficultyCondition'; status = 'passed'; observations = @('local-difficulty-condition imported=true positive=true range-positive=true high-rejected=true fallback-positive=true missing-context-rejected=true invalid-preserved=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'groundedCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'groundedCondition'; status = 'passed'; observations = @('grounded-condition imported=true primary-mapped=true grounded-alias-mapped=true positive=true alias-positive=true airborne=true negative=true alias-negative=true invalid-preserved=true restored=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'templateTypeImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'templateTypeImported'; status = 'passed'; observations = @('template-type-imported result=true imported=true primary-mapped=true alias-mapped=true direct-positive=true transitive-positive=true unrelated-rejected=true control-rejected=true field-inheritance=true exclude-inherited-removed=true exclude-own-values=true missing-config-rejected=true unmanaged-rejected=true invalid-preserved=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'serverNmsVersionCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'serverNmsVersionCondition'; status = 'passed'; observations = @('server-nms-version-condition imported=true positive=true alias-positive=true scalar-positive=true wrong-rejected=true default-rejected=true invalid-preserved=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'spawnReasonCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'spawnReasonCondition'; status = 'passed'; observations = @('spawn-reason-condition-details actual=CUSTOM primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'absorptionPlayerCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'absorptionPlayerCondition'; status = 'passed'; observations = @('absorption-player-condition imported=true dispatched=true positive=true exact=true negative=true observed=true restored=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'radiusConditions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'radiusConditions'; status = 'passed'; observations = @('radius-condition-details imported=true players-positive=true players-negative=true entities-positive=true entities-negative=true mobs-positive=true mobs-negative=true living-positive=true living-negative=true player-within-positive=true player-within-negative=true player-not-within-positive=true player-not-within-negative=true restored=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedPlayerKillsCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedPlayerKillsCondition'; status = 'passed'; observations = @('imported-player-kills-condition primary-mapped=true range-mapped=true positive=true negative=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'mobOptionsSlimeSplitNatural'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mobOptionsSlimeSplitNatural'; status = 'passed'; observations = @('mob-options-slime-split-natural protected-size=4 control-size=4 protected-event=true protected-cancelled=true protected-event-count=4 control-event=true control-cancelled=false control-event-count=3 protected-dead=true control-dead=true protected-children=0 control-children=3 monster-spawning-enabled=true spawn-flags-restored=true damage=api passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'mythicTeleport'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mythicTeleport'; status = 'passed'; observations = @('mythic-teleport imported=true entity-target=true location-target=true safe-blocked=true unsafe-bypass=true spread-bounded=true yaw-preserved=true orientation-override=true invalid-preserved=true warnings=true restored=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'contextTargeterRelations'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'contextTargeterRelations'; status = 'passed'; observations = @('context-targeter-relations passenger=true passengers=true mount=true vehicle=true owner-location=true parent-location=true invalid-rejected=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'lastDamagerTargeter'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'lastDamagerTargeter'; status = 'passed'; observations = @('skill-trigger last-damager-targeter=true last-damager-projectile=true last-damager-environmental-rejected=true last-damager-removed-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'targetOfTargetTargeter'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'targetOfTargetTargeter'; status = 'passed'; observations = @('skill-trigger target-of-target=true target-of-target-aliases=true target-of-target-non-mob-rejected=true target-of-target-missing-rejected=true target-of-target-invalid-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'legacyNumericItems'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'legacyNumericItems'; status = 'passed'; observations = @('legacy-numeric-items imported=true items-material=true items-durability=true items-potion=true items-block-data=true items-amount=true legacy-id-boundaries=true equipment-material=true equipment-durability=true equipment-potion=true equipment-block-data=true equipment-amount=true invalid-preserved=true result=true')
    }
    $fullCatalogReport.expectedProbeIds += 'itemMetadata'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'itemMetadata'; status = 'passed'; observations = @('item-metadata imported=true display-name=true item-name=true lore=true enchantment=true custom-model-data=true flags=true restrictions=true potion=true skull=true invalid-preserved=true result=true')
    }
    $fullCatalogReport.expectedProbeIds += 'attributesComposite'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'attributesComposite'; status = 'passed'; observations = @('attribute-options-composite result=true attack=true speed=true armor=true dedup=true invalid-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'itemAttributesComposite'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'itemAttributesComposite'; status = 'passed'; observations = @('item-attribute-options-composite result=true attack=true speed=true armor=true dedup=true invalid-rejected=true modifiers=3')
    }
    $fullCatalogReport.expectedProbeIds += 'mythicStatsBias'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mythicStatsBias'; status = 'passed'; observations = @('mythic-stats result=true default=true base=0.0 killer-priority=true unknown-rejected=true disabled-rejected=true positive-weighted=true positive-rare=684 negative-weighted=true unknown-fail-closed=true restored=true dropper-removed=true')
    }
    $fullCatalogAudienceFilter = @($fullCatalogReport.probes | Where-Object id -eq 'audience-filter-delivery')
    if ($fullCatalogAudienceFilter.Count -ne 1) {
        throw 'Synthetic full catalog report must contain audience-filter-delivery.'
    }
    $fullCatalogAudienceFilter[0].observations = @('{"permissionNegativeTesterOmitted":true,"permissionNegativeGuestIncluded":true,"factionNegativeTesterIncluded":true,"factionNegativeGuestExcluded":true}')
    $fullCatalogAiRoute = @($fullCatalogReport.probes | Where-Object id -eq 'aiRoute')
    if ($fullCatalogAiRoute.Count -ne 1) {
        throw 'Synthetic full catalog report must contain aiRoute.'
    }
    $fullCatalogAiRoute[0].observations = @('ai-route-probe token=followpath active-before=true moving=true advanced=true waypoint-before=route=followpath waypoint=103,64,0 direction=forward speed=1.0 tolerance=3.0 finished=false waypoint-after=route=followpath waypoint=105,64,0 direction=forward speed=1.0 tolerance=3.0 finished=false patrol-mutation=true patrol-before=route=patrol waypoint=103,64,0 direction=forward speed=1.0 tolerance=0.5 finished=false patrol-after=route=patrol waypoint=105,64,0 direction=forward speed=1.0 tolerance=0.5 finished=false patrol-loop=route=patrol waypoint=103,64,0 direction=backward speed=1.0 tolerance=0.5 finished=false passed=true')
    $fullCatalogNaturalMobOptions = @($fullCatalogReport.probes | Where-Object id -eq 'mobOptionsNatural')
    if ($fullCatalogNaturalMobOptions.Count -ne 1) {
        throw 'Synthetic full catalog report must contain mobOptionsNatural.'
    }
    $fullCatalogNaturalMobOptions[0].observations = @(
        $fullCatalogNaturalMobOptions[0].observations | ForEach-Object {
            $_ -replace 'bee-stinger=true ', 'bee-stinger=true bee-event=synthetic '
        })
    $fullCatalogReport.expectedProbeIds += 'mobOptionsSkeletonConversion'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mobOptionsSkeletonConversion'; status = 'passed'; observations = @('mob-options-skeleton-conversion protected-events=1 control-events=1 protected-cancelled=true control-cancelled=false protected-type=SKELETON protected-valid=true control-stray=true control-converted=true elapsed-ticks=21 restored=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'mobOptionsZombieConversion'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mobOptionsZombieConversion'; status = 'passed'; observations = @('mob-options-zombie-conversion protected-events=1 control-events=1 protected-cancelled=true control-cancelled=false protected-type=ZOMBIE protected-valid=true control-drowned=true control-converted=true elapsed-ticks=21 restored=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'mobOptionsZombieVillagerCured'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mobOptionsZombieVillagerCured'; status = 'passed'; observations = @('mob-options-zombie-villager-cured protected-events=1 control-events=1 protected-cancelled=true control-cancelled=false protected-type=ZOMBIE_VILLAGER protected-valid=true control-villager=true control-converted=true elapsed-ticks=21 restored=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'mobOptionsVillagerInfection'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mobOptionsVillagerInfection'; status = 'passed'; observations = @('mob-options-villager-infection protected-events=1 control-events=1 protected-cancelled=true control-cancelled=false protected-type=VILLAGER protected-villager=true protected-alive=false protected-zombie-villager=false control-zombie-villager=true control-converted=true native-attacks=true/true difficulty-restored=true elapsed-ticks=21 restored=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'preventSunburnNatural'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'preventSunburnNatural'; status = 'passed'; observations = @('prevent-sunburn-natural imported=true mapped=true protected-option=true control-option=false daylight=true weather-clear=true observer-present=true protected-player-distance=7 control-player-distance=15 sky-exposed=true protected-ai=true control-ai=true protected-age-ticks=90 control-age-ticks=110 protected-events=2 protected-cancelled=2 protected-fire-ticks=0 control-events=1 control-cancelled=0 control-fire-ticks=154 elapsed-ticks=40 restored=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedIsChildCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedIsChildCondition'; status = 'passed'; observations = @('skill-actions is-child-import-condition=true adult-parent-positive=true adult-not-baby=true unrelated-baby-not-child=true baby-positive=true')
    }
    $fullCatalogReport.expectedProbeIds += 'mythicAuraRuntime'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mythicAuraRuntime'; status = 'passed'; observations = @('skill-actions mythic-aura-action=true mythic-has-aura-condition=true mythic-target-has-aura-condition=true mythic-has-aura-stacks-condition=true mythic-has-aura-type-condition=true mythic-aura-remove=true mythic-aura-invalid-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedMobDefaults'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedMobDefaults'; status = 'passed'; observations = @('imported-mob-defaults result=true imported=true builtin-pickup-disabled=true builtin-no-damage-ticks=true builtin-despawn-normal=true builtin-prevent-leashing=true builtin-prevent-transformation=true global-pickup-disabled=true global-no-damage-ticks=true global-prevent-leashing=true global-default-prevent-transformation=false global-default-ignored=true global-prevent-transformation=true global-despawn-chunk=true global-prevent-other-drops=true global-prevent-vanilla-damage=true global-unsupported-preserved=true explicit-pickup-enabled=true explicit-pickup-disabled=true pickup-wrong-type-preserved=true pickup-invalid-preserved=true explicit-jockey-prevented=true global-jockey-allowed=true explicit-no-damage-ticks=true explicit-prevent-leashing=true explicit-prevent-transformation-disabled=true explicit-despawn-never=true explicit-prevent-other-drops-disabled=true explicit-prevent-vanilla-damage-disabled=true builtin-transform-cancelled=true global-transform-cancelled=true explicit-transform-allowed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'playerCreatedImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'playerCreatedImported'; status = 'passed'; observations = @('player-created-imported result=true imported=true created=true control-created=false wrong-entity-preserved=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'noGravityImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'noGravityImported'; status = 'passed'; observations = @('no-gravity-imported result=true imported=true configured=false control=true has-gravity=false has-gravity-control=true has-gravity-wrong-type-preserved=true invalid-preserved=true gravity-invalid-preserved=true has-gravity-invalid-preserved=true fallback=true')
    }
    $fullCatalogReport.expectedProbeIds += 'healOnReloadOptions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'healOnReloadOptions'; status = 'passed'; observations = @('heal-on-reload-options result=true mapped=true reload=true protected-option=true control-option=false protected-before=15.0 protected-after=20.0 control-before=15.0 control-after=15.0')
    }
    $fullCatalogReport.expectedProbeIds += 'interactableClient'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'interactableClient'; status = 'passed'; observations = @('interactable-client result=true false-event=true false-cancelled=true true-event=true true-cancelled=false interactions=2')
    }
    $fullCatalogReport.expectedProbeIds += 'mythicSpawnerChanceImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mythicSpawnerChanceImported'; status = 'passed'; observations = @('mythic-spawner-chance imported=true valid-mapped=true invalid-mapped=true valid-natural=true invalid-rejected=true warning=true valid-spawned=1 invalid-spawned=0 passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'mythicSpawnerGroupSizeImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mythicSpawnerGroupSizeImported'; status = 'passed'; observations = @('mythic-spawner-group-size imported=true mapped=true natural=true requested=3 spawned=2 alive=2 max-alive=2 passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'mythicSpawnerConditionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mythicSpawnerConditionsImported'; status = 'passed'; observations = @('mythic-spawner-conditions imported=true mapped=true world-mapped=true biome-mapped=true biome-natural=true biome-rejected=true biome-pass-spawned=1 biome-reject-spawned=0 world-natural=true world-rejected=true rain=true thunder=false natural=true rejected=true pass-spawned=1 reject-spawned=0 world-pass-spawned=1 world-reject-spawned=0 instance=true restored=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'mythicSpawnerRandomPoolImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mythicSpawnerRandomPoolImported'; status = 'passed'; observations = @('mythic-spawner-random-pool imported=true mapped=true sampled=true expected-emberling=0.250000 expected-emberling_boss=0.750000 observed-emberling=0.240000 observed-emberling_boss=0.760000 max-abs-deviation=0.010000 samples=100 passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'mythicSpawnerRandomPoolDropTables'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'mythicSpawnerRandomPoolDropTables'; status = 'passed'; observations = @('mythic-spawner-drop-table imported=true references=true weighted=true tables-loaded=true spawned=32 variants=true assigned=true common-table-reloaded=true rare-table-reloaded=true common-alive=true rare-alive=true common-natural=true rare-natural=true common-bread=1 common-diamond=0 rare-bread=0 rare-diamond=1 restored=true passed=true')
    }
    $fullCatalogLookingAt = @($fullCatalogReport.probes | Where-Object id -eq 'lookingAtCondition')
    if ($fullCatalogLookingAt.Count -ne 1) { throw 'Synthetic full catalog report must contain lookingAtCondition.' }
    $fullCatalogLookingAt[0].observations = @('imported-looking-at-condition primary-mapped=true alias-mapped=true entity-mapped=true positive=true alias-positive=true wrong-block-rejected=true short-block-distance-rejected=true negative=true entity-positive=true wrong-entity-rejected=true short-entity-distance-rejected=true invalid-rejected=true restored=true')
    $fullCatalogAiSelector = @($fullCatalogReport.probes | Where-Object id -eq 'aiSelectorCore')
    if ($fullCatalogAiSelector.Count -ne 1) { throw 'Synthetic full catalog report must contain aiSelectorCore.' }
    $fullCatalogAiSelector[0].observations = @($fullCatalogAiSelector[0].observations) +
        @('ai-selector-core targeter-syntax=true')
    $fullCatalogAgeOptions = @($fullCatalogReport.probes | Where-Object id -eq 'ageOptions')
    if ($fullCatalogAgeOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain ageOptions.' }
    $fullCatalogAgeOptions[0].observations = @(
        'age-options result=true age=-24000 baby=true adult=true age-lock=true zombie-baby=true')
    $fullCatalogEntityFlags = @($fullCatalogReport.probes | Where-Object id -eq 'entityFlagOptions')
    if ($fullCatalogEntityFlags.Count -ne 1) { throw 'Synthetic full catalog report must contain entityFlagOptions.' }
    $fullCatalogEntityFlags[0].observations = @(
        'entity-flag-options result=true imported=true wolf-tamed=true cat-tamed=true control-cat-tamed=false horse-tamed=true control-horse-tamed=false parrot-tamed=true control-parrot-tamed=false wolf-angry=true pig-zombie-angry=true can-join-raid-setter=true can-join-raid=false control-can-join-raid=true patrol-leader=true patrol-spawn-point=true wrong-type-preserved=true')
    $fullCatalogReport.expectedProbeIds += 'glowingOptionImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'glowingOptionImported'; status = 'passed'; observations = @('glowing-option-imported result=true imported=true enabled-configured=true control-configured=true invalid-preserved=true enabled-entity=true control-entity=true invalid-entity=true')
    }
    $fullCatalogReport.expectedProbeIds += 'booleanMobOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'booleanMobOptionsImported'; status = 'passed'; observations = @('boolean-mob-options-imported result=true imported=true configured=true controls=true invalid-preserved=true entity-states=true')
    }
    $fullCatalogReport.expectedProbeIds += 'preventRandomEquipmentImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'preventRandomEquipmentImported'; status = 'passed'; observations = @('prevent-random-equipment-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true random-cleared=true explicit-equipment-preserved=true control-preserved=true invalid-fallback=true')
    }
    $fullCatalogReport.expectedProbeIds += 'environmentalDamageOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'environmentalDamageOptionsImported'; status = 'passed'; observations = @('environmental-damage-options-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true drowning-protected=true fall-protected=true controls-allowed=true invalid-fallback=true')
    }
    $fullCatalogReport.expectedProbeIds += 'fireProtectionOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'fireProtectionOptionsImported'; status = 'passed'; observations = @('fire-protection-options-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true blanket-combust-cancelled=true blanket-fire-tick-cancelled=true selective-fire-tick-cancelled=true unlisted-fire-allowed=true cross-cause-allowed=true controls-allowed=true invalid-fallback=true')
    }
    $fullCatalogReport.expectedProbeIds += 'explosionOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'explosionOptionsImported'; status = 'passed'; observations = @('explosion-options-imported result=true imported=true configured=true controls=true invalid-preserved=true policies=true blanket-protected=true block-only=true entity-only=true projectile-only=true controls-allowed=true invalid-fallback=true')
    }
    $fullCatalogReport.expectedProbeIds += 'digOutOfGroundImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'digOutOfGroundImported'; status = 'passed'; observations = @('dig-out-of-ground-imported result=true imported=true configured=true policies=true suffocation-cancelled=true rise=2.00 fall-control=true disabled-allowed=true invalid-fallback=true')
    }
    $fullCatalogReport.expectedProbeIds += 'preventMobKillDropsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'preventMobKillDropsImported'; status = 'passed'; observations = @('prevent-mob-kill-drops-imported result=true imported=true configured=true policies=true killers-resolved=true drops-suppressed=true controls-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'preventOtherDropsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'preventOtherDropsImported'; status = 'passed'; observations = @('prevent-other-drops-imported result=true imported=true configured=true policies=true native-suppressed=true custom-retained=true controls-preserved=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'tamedFamilyOptions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'tamedFamilyOptions'; status = 'passed'; observations = @('tamed-family-options result=true imported=true donkey-tamed=true control-donkey-tamed=false mule-tamed=true control-mule-tamed=false llama-tamed=true control-llama-tamed=false trader-llama-tamed=true control-trader-llama-tamed=false skeleton-horse-tamed=true control-skeleton-horse-tamed=false zombie-horse-tamed=true control-zombie-horse-tamed=false camel-tamed=true control-camel-tamed=true camel-false-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'experienceOrbOptions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'experienceOrbOptions'; status = 'passed'; observations = @('experience-orb-options result=true imported=true experience=17 wrong-type-preserved=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'pandaOptions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'pandaOptions'; status = 'passed'; observations = @('panda-options result=true imported=true main-gene=LAZY hidden-gene=WORRIED control-main-gene=NORMAL control-hidden-gene=NORMAL wrong-type-preserved=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'villagerOptions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'villagerOptions'; status = 'passed'; observations = @('villager-options result=true imported=true type=DESERT profession=MASON level=4 has-trades=true trades-available=true control-type=PLAINS control-profession=LIBRARIAN control-level=2 control-has-trades=false no-trades=true acquire-allowed=true acquire-denied=true trading-interactable=true wrong-type-preserved=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'parrotFlyingSpeed'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'parrotFlyingSpeed'; status = 'passed'; observations = @('parrot-flying-speed result=true imported=true configured=0.8 sentinel-unchanged=true wrong-type-preserved=true invalid-preserved=true')
    }
    $fullCatalogCatOptions = @($fullCatalogReport.probes | Where-Object id -eq 'cat')
    if ($fullCatalogCatOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain cat.' }
    $fullCatalogCatOptions[0].observations = @(
        'cat-options result=true imported=true type=SIAMESE sitting=true collar=BLUE control-type=TABBY control-sitting=false control-collar=RED')
    $fullCatalogSheepOptions = @($fullCatalogReport.probes | Where-Object id -eq 'sheep')
    if ($fullCatalogSheepOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain sheep.' }
    $fullCatalogSheepOptions[0].observations = @(
        'sheep-options result=true imported=true color=RED sheared=true control-color=WHITE control-sheared=false')
    $fullCatalogReport.expectedProbeIds += 'horseOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'horseOptionsImported'; status = 'passed'; observations = @('horse-options result=true imported=true color=GRAY style=WHITE_DOTS domestication=5 max-domestication=20 jump-strength=0.8 saddled=true armor=DIAMOND_HORSE_ARMOR control-color=BLACK control-style=BLACK_DOTS control-domestication=1 control-max-domestication=12 control-jump-strength=0.4 control-saddled=false control-armor=AIR donkey-chest=true control-donkey-chest=false pig-saddled=true control-pig-saddled=false strider-saddled=true control-strider-saddled=false')
    }
    $fullCatalogReport.expectedProbeIds += 'beeOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'beeOptionsImported'; status = 'passed'; observations = @('bee-options-imported result=true imported=true nectar=true stung=false control-nectar=false control-stung=true')
    }
    $fullCatalogReport.expectedProbeIds += 'beeAngerImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'beeAngerImported'; status = 'passed'; observations = @('bee-anger-imported result=true imported=true configured-anger=1200 control-anger=0')
    }
    $fullCatalogReport.expectedProbeIds += 'beeStingerLossImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'beeStingerLossImported'; status = 'passed'; observations = @('bee-stinger-loss-imported result=true imported=true target-entity=ZOMBIE target-ai=false target-invulnerable=false target-resistance-amplifier=3 protected-option=true control-option=false protected-attacks=1 control-attacks=1 protected-events=1 control-events=1 protected-cancelled=0 control-cancelled=0 protected-restored=true control-retained=true protected-near=true control-near=true trigger=entity-target-api event=native-ai')
    }
    $fullCatalogHorseOptions = @($fullCatalogReport.probes | Where-Object id -eq 'horseOptionsImported')
    if ($fullCatalogHorseOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain horseOptionsImported.' }
    $fullCatalogReport.expectedProbeIds += 'foxOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'foxOptionsImported'; status = 'passed'; observations = @('fox-options result=true imported=true type=SNOW control-type=RED wrong-type-preserved=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'rabbitOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'rabbitOptionsImported'; status = 'passed'; observations = @('rabbit-options result=true imported=true type=SALT_AND_PEPPER control-type=BROWN killer-type=THE_KILLER_BUNNY angry-type=THE_KILLER_BUNNY false-killer-type=SALT_AND_PEPPER wrong-type-preserved=true invalid-preserved=true conflict-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'fallingBlockOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'fallingBlockOptionsImported'; status = 'passed'; observations = @('falling-block-options-imported result=true imported=true material=OBSIDIAN control-material=SAND drop-item=false hurt-entities=true cancel-drop=true wrong-type-preserved=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'boatOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'boatOptionsImported'; status = 'passed'; observations = @('boat-options-imported result=true imported=true boat-type=SPRUCE control-type=OAK chest-type=BAMBOO wrong-type-preserved=true invalid-preserved=true conflict-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'interactionOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'interactionOptionsImported'; status = 'passed'; observations = @('interaction-options-imported result=true imported=true configured-height=2.5 configured-width=3.25 responsive=false height-only-width=1.75 defaults-applied=true wrong-type-preserved=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'goatOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'goatOptionsImported'; status = 'passed'; observations = @('goat-options result=true imported=true screaming=true control-screaming=false default-screaming=false wrong-type-preserved=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'frogOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'frogOptionsImported'; status = 'passed'; observations = @('frog-options result=true imported=true type=COLD alias-type=WARM control-type=TEMPERATE wrong-type-preserved=true invalid-preserved=true conflict-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'tameableOptionsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'tameableOptionsImported'; status = 'passed'; observations = @('tameable-options result=true imported=true allow-cancelled=false deny-cancelled=true default-cancelled=true invalid-cancelled=true unmanaged-cancelled=false wrong-type-preserved=true invalid-preserved=true global-default-ignored=true')
    }
    $fullCatalogCreeperOptions = @($fullCatalogReport.probes | Where-Object id -eq 'creeper')
    if ($fullCatalogCreeperOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain creeper.' }
    $fullCatalogCreeperOptions[0].observations = @(
        'creeper-options result=true imported=true explosion-radius=5 fuse-ticks=60 super-charged=true prevent-suicide=true protected-cancelled=true control-allowed=true')
    $fullCatalogSizeOptions = @($fullCatalogReport.probes | Where-Object id -eq 'entitySizeRange')
    if ($fullCatalogSizeOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain entitySizeRange.' }
    $fullCatalogSizeOptions[0].observations = @(
        'entity-size-range result=true samples=64 configured=2..5 slime=2..5 magma-cube=2..5 phantom=2..5 imported=true')
    $fullCatalogReport.expectedProbeIds += 'reinforcementsChanceOptions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'reinforcementsChanceOptions'; status = 'passed'; observations = @('reinforcements-chance-options result=true imported=true applied=true base=0.38 zero-mapped=true upper-bound-mapped=true wrong-type-preserved=true invalid-preserved=true wrong-entity-rejected=true invalid-runtime-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'armorToughnessStatsImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'armorToughnessStatsImported'; status = 'passed'; observations = @('armor-toughness-stats-imported result=true imported=true base=4.0 attack-damage=12.0 movement-speed=0.4 armor=8.0 knockback-resistance=0.25 follow-range=36.0 unsupported-preserved=true modifier-preserved=true root-preserved=true controls=0.0,0.0')
    }
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
    $fullCatalogReport.expectedProbeIds += 'lineOfSightCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'lineOfSightCondition'; status = 'passed'; observations = @('line-of-sight-condition result=true imported=true aliases=true direct-positive=true direct-blocked=true target-positive=true target-blocked=true target-negative=true restored-positive=true restored=true invalid-preserved=true warning=true')
    }
    $fullCatalogReport.expectedProbeIds += 'conditionMobilityStates'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'conditionMobilityStates'; status = 'passed'; observations = @('condition-mobility-states result=true falling-positive=true falling-negative=true climbing-positive=true climbing-negative=true charged-positive=true charged-negative=true restored=true aliases=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedMaterialCooldownCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedMaterialCooldownCondition'; status = 'passed'; observations = @('imported-material-cooldown-condition primary-mapped=true alias-mapped=true positive=true wrong-material-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedItemRechargingCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedItemRechargingCondition'; status = 'passed'; observations = @('imported-item-recharging-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true non-human-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedItemSimilarityConditions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedItemSimilarityConditions'; status = 'passed'; observations = @('imported-item-similarity-conditions entity-primary-mapped=true entity-alias-mapped=true entity-positive=true entity-alias-positive=true entity-negative=true entity-invalid-preserved=true inventory-primary-mapped=true inventory-alias-mapped=true inventory-positive=true inventory-alias-positive=true inventory-negative=true inventory-invalid-preserved=true result=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedIsFrozenCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedIsFrozenCondition'; status = 'passed'; observations = @('imported-frozen-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true thawed=true alias-thawed=true invalid-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedBurningCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedBurningCondition'; status = 'passed'; observations = @('imported-burning-condition actions=true target-priority=true target-positive=true target-negative=true composed-negation=true caster-fallback=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedEntityCategoryCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedEntityCategoryCondition'; status = 'passed'; observations = @('imported-entity-category-condition water-primary-mapped=true water-alias-mapped=true water-positive=true water-zombie-rejected=true zombie-primary-mapped=true zombie-alias-mapped=true zombie-positive=true zombie-cod-rejected=true invalid-preserved=true cod=COD zombie=ZOMBIE')
    }
    $fullCatalogReport.expectedProbeIds += 'importedExecutionAudienceCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedExecutionAudienceCondition'; status = 'passed'; observations = @('imported-execution-audience-condition primary-mapped=true alias-mapped=true contains-mapped=true no-audience-player-allowed=true member-allowed=true outsider-rejected=true empty-rejected=true non-player-rejected=true invalid-preserved=true per-target-unattached=true per-target-explicit=true result=true')
    }
    $fullCatalogReport.expectedProbeIds += 'executionAudienceMutation'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'executionAudienceMutation'; status = 'passed'; observations = @('execution-audience-mutation imported=true explicit-empty=true added=true persisted=true member-visible=true outsider-hidden=true removed=true empty-persisted=true unset=true unrestricted=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'executionAudienceMobSpawn'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'executionAudienceMobSpawn'; status = 'passed'; observations = @('execution-audience-mob-spawn imported=true mapped=true spawned=true selected-member=true outsider-excluded=true persisted=true member-visible=true outsider-hidden=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'executionAudienceMobMutation'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'executionAudienceMobMutation'; status = 'passed'; observations = @('execution-audience-mob-mutation imported=true targeter-removed=true empty-persisted=true hidden=true targeter-added=true restored-persisted=true member-visible=true outsider-hidden=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'executionAudienceMobSummon'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'executionAudienceMobSummon'; status = 'passed'; observations = @('execution-audience-mob-summon imported=true action-bound=true spawned=true inherited=true parent-linked=true visuals=true member-visible=true outsider-hidden=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds += 'passthroughDamageOptions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'passthroughDamageOptions'; status = 'passed'; observations = @('passthrough-damage result=true imported=true parent-linked=true transferred=true source-preserved=true disabled-control=true orphan-fallback=true cancelled-respected=true cycle-safe=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedTargetRelationshipsCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedTargetRelationshipsCondition'; status = 'passed'; observations = @('imported-target-relationships-condition has-owner-mapped=true owner-player-mapped=true owner-online-mapped=true leashed-mapped=true leashed-alias-mapped=true owned-positive=true owner-player-positive=true owner-online-positive=true leashed-positive=true leashed-alias-positive=true leash-cleared-rejected=true owner-cleared-rejected=true missing-target-rejected=true result=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedEntityTypeVariantCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedEntityTypeVariantCondition'; status = 'passed'; observations = @('imported-entity-type-variant-condition primary-mapped=true alias-mapped=true mobtype-mapped=true positive=true alias-positive=true mobtype-positive=true variant-alternative=true wrong-variant-rejected=true wrong-type-rejected=true invalid-preserved=true cat=CAT variant=TABBY')
    }
    $fullCatalogReport.expectedProbeIds += 'importedEntityTypeTraitsCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedEntityTypeTraitsCondition'; status = 'passed'; observations = @('imported-entity-type-traits mapped=true horse=true wrong-style-rejected=true panda=true villager=true zombie-villager=true rainbow-sheep=true rainbow-sheep-white-rejected=true shulker=true parrot=true llama=true tropical-fish=true iron-golem-crack=true inapplicable-trait-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedSpyglassCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedSpyglassCondition'; status = 'passed'; observations = @('imported-spyglass-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true inactive-rejected=true non-player-rejected=true invalid-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedSurvivalModeCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedSurvivalModeCondition'; status = 'passed'; observations = @('imported-survival-mode-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true alias-negative=true non-player-rejected=true invalid-rejected=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedVariableConditions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedVariableConditions'; status = 'passed'; observations = @('imported-variable-conditions result=true primary-mapped=true aliases=true scopes=true ranges=true missing-rejected=true wrong-type-rejected=true quoted-value=true dynamic-placeholder=true invalid-preserved=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedFreeInventorySlotCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedFreeInventorySlotCondition'; status = 'passed'; observations = @('imported-free-inventory-slot primary-mapped=true alias-mapped=true positive=true negative=true invalid-preserved=true result=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedNameCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedNameCondition'; status = 'passed'; observations = @('imported-name-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true default-name-rejected=true fallback=true custom-name-rejected=true invalid-preserved=true result=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedServerConditions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedServerConditions'; status = 'passed'; observations = @('imported-server-conditions paper-primary=true paper-alias=true exact=true exact-negative=true after-inclusive=true after-exclusive-rejected=true after-higher-rejected=true before-inclusive=true before-exclusive-rejected=true invalid-preserved=true result=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedCoordinateDifferenceCondition'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedCoordinateDifferenceCondition'; status = 'passed'; observations = @('imported-coordinate-difference-condition primary-mapped=true alias-mapped=true x-positive=true y-positive=true z-positive=true out-of-range-rejected=true invalid-preserved=true result=true')
    }
    $fullCatalogReport.expectedProbeIds += 'importedPopulationConditions'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'importedPopulationConditions'; status = 'passed'; observations = @('imported-population-conditions players-online-mapped=true online-alias-mapped=true players-world-mapped=true mobs-world-mapped=true players-online-negative-mapped=true online-alias-negative-mapped=true players-world-negative-mapped=true mobs-world-negative-mapped=true players-online-positive=true online-alias-positive=true players-world-positive=true mobs-world-positive=true players-online-negative=true online-alias-negative=true players-world-negative=true mobs-world-negative=true invalid-preserved=true result=true')
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
        id = 'locationTargeterDispatch'; status = 'passed'; observations = @(
            'location-targeter-dispatch valid=true invalid-rejected=true self-aliases=true self-eye=true caster-spawn=true spawn=true target-aliases=true trigger=true forward=true projectile-forward=true predicted=true',
            'tracked-location-parity action=true targeter=true distance=true missing-rejected=true')
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
        id = 'dropItemImported'; status = 'passed'; observations = @('drop-item-import valid=true missing-rejected=true invalid-preserved=true owner=true target-owner=true')
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
        id = 'healthImportedCondition'; status = 'passed'; observations = @('health-imported result=true comparisons=true duplicate-rejected=true overflow-preserved=true invalid-preserved=true')
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
    $fullCatalogReport.expectedProbeIds += 'showHealthDelivery'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'showHealthDelivery'; status = 'passed'; observations = @('show-health-delivery result=true imported=true spawned=true first-crossing=true second-crossing=true healing-recross=true below-threshold-quiet=true inside-band-quiet=true near-inside=true outsider-outside=true radius=25 environmental-damage=true entity-damage=true')
    }
    $fullCatalogReport.expectedProbeIds += 'showHealthClientDelivery'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'showHealthClientDelivery'; status = 'passed'; observations = @('{"nearPlayerReceivedFirstThreshold":true,"nearPlayerReceivedSecondThreshold":true,"outsidePlayerExcluded":true,"belowThresholdQuiet":true,"withinBandQuiet":true,"healingRearmedThreshold":true}')
    }
    $fullCatalogReport.expectedProbeIds += 'preventTeleportImported'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'preventTeleportImported'; status = 'passed'; observations = @('prevent-teleport-imported result=true imported=true mapped=true invalid-preserved=true spawned=true protected-api-blocked=true control-api-teleported=true')
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
    $audienceFilterObservations = $fullCatalogAudienceFilter[0].observations
    $fullCatalogAudienceFilter[0].observations = @('{"permissionNegativeTesterOmitted":true,"permissionNegativeGuestIncluded":false,"factionNegativeTesterIncluded":true,"factionNegativeGuestExcluded":true}')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badAudienceFilterRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badAudienceFilterRejected = $_.Exception.Message -match 'required observation for audience-filter-delivery' }
    Assert-True $badAudienceFilterRejected 'full release gate rejects a false dual-client Audience exclusion observation'
    $fullCatalogAudienceFilter[0].observations = $audienceFilterObservations
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
    $entitySizeProbe = $fullCatalogReport.probes | Where-Object id -eq 'entitySizeRange'
    $entitySizeObservations = $entitySizeProbe.observations
    $entitySizeProbe.observations = @('entity-size-range result=true samples=64 configured=2..5 observed=2..5')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $oldEntitySizeEvidenceRejected = $false
    $oldEntitySizeEvidenceRejectionMessage = ''
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $oldEntitySizeEvidenceRejectionMessage = $_.Exception.Message
        $oldEntitySizeEvidenceRejected = $oldEntitySizeEvidenceRejectionMessage -match 'required observation for entitySizeRange'
    }
    Assert-True $oldEntitySizeEvidenceRejected "full release gate rejects size evidence missing MagmaCube and Phantom (actual: $oldEntitySizeEvidenceRejectionMessage)"
    $entitySizeProbe.observations = $entitySizeObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $reinforcementsProbe = @($fullCatalogReport.probes | Where-Object id -eq 'reinforcementsChanceOptions')
    if ($reinforcementsProbe.Count -ne 1) { throw 'Synthetic full catalog report must contain reinforcementsChanceOptions.' }
    $reinforcementsObservations = $reinforcementsProbe[0].observations
    $reinforcementsProbe[0].observations = @('reinforcements-chance-options result=true imported=true applied=true base=0.38 invalid-runtime-rejected=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteReinforcementsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteReinforcementsRejected = $_.Exception.Message -match 'required observation for reinforcementsChanceOptions' }
    Assert-True $incompleteReinforcementsRejected 'full release gate rejects reinforcement evidence missing boundaries and preservation controls'
    $reinforcementsProbe[0].observations = $reinforcementsObservations
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
    $fullCatalogReport.expectedProbeIds += 'damageAttribution'
    $fullCatalogReport.probes += [pscustomobject]@{
        id = 'damageAttribution'; status = 'passed'; observations = @('damage-attribution player-present=true player-attack=true target-is-player=true environmental-no-trigger=true restored=true passed=true')
    }
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'biomeImported' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'biomeImported' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingBiomeRejected = $false
    $missingBiomeFailure = ''
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $missingBiomeFailure = $_.Exception.Message
        $missingBiomeRejected = $missingBiomeFailure -match 'biomeImported'
    }
    Assert-True $missingBiomeRejected "full release gate rejects missing imported biome evidence (actual failure: $missingBiomeFailure)"
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
    $missingQueueFailure = ''
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch {
        $missingQueueFailure = $_.Exception.Message
        $missingQueueRejected = $missingQueueFailure -match 'skillTreeQueueSnapshot'
    }
    if (-not $missingQueueRejected) {
        throw "Queue snapshot negative test failed for an unexpected reason: $missingQueueFailure"
    }
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
        id = 'healthImportedCondition'; status = 'passed'; observations = @('health-imported result=true comparisons=true duplicate-rejected=true overflow-preserved=true invalid-preserved=true')
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
    $importedCoordinateProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedCoordinateDifferenceCondition'
    $importedCoordinateObservations = @($importedCoordinateProbe.observations)
    $failedImportedCoordinateObservation = ([string]$importedCoordinateObservations[0]) -replace 'z-positive=true', 'z-positive=false'
    $importedCoordinateProbe.observations = @($failedImportedCoordinateObservation)
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedCoordinateRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedCoordinateRejected = $_.Exception.Message -match 'required observation for importedCoordinateDifferenceCondition' }
    Assert-True $badImportedCoordinateRejected 'full release gate rejects a failed imported coordinate difference axis'
    $importedCoordinateProbe.observations = $importedCoordinateObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedCoordinateDifferenceCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedCoordinateDifferenceCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedCoordinateRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedCoordinateRejected = $_.Exception.Message -match 'importedCoordinateDifferenceCondition' }
    Assert-True $missingImportedCoordinateRejected 'full release gate rejects missing imported coordinate difference evidence'
    $fullCatalogReport.expectedProbeIds += 'importedCoordinateDifferenceCondition'
    $fullCatalogReport.probes += $importedCoordinateProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $importedPopulationProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedPopulationConditions'
    $importedPopulationObservations = @($importedPopulationProbe.observations)
    $failedImportedPopulationObservation = ([string]$importedPopulationObservations[0]) -replace 'mobs-world-positive=true', 'mobs-world-positive=false'
    $importedPopulationProbe.observations = @($failedImportedPopulationObservation)
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedPopulationRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedPopulationRejected = $_.Exception.Message -match 'required observation for importedPopulationConditions' }
    Assert-True $badImportedPopulationRejected 'full release gate rejects failed imported population condition evidence'
    $importedPopulationProbe.observations = $importedPopulationObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedPopulationConditions' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedPopulationConditions' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedPopulationRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedPopulationRejected = $_.Exception.Message -match 'importedPopulationConditions' }
    Assert-True $missingImportedPopulationRejected 'full release gate rejects missing imported population condition evidence'
    $fullCatalogReport.expectedProbeIds += 'importedPopulationConditions'
    $fullCatalogReport.probes += $importedPopulationProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $catOptionsObservations = $fullCatalogCatOptions[0].observations
    $fullCatalogCatOptions[0].observations = @('cat-options result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteCatOptionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteCatOptionsRejected = $_.Exception.Message -match 'required observation for cat' }
    Assert-True $incompleteCatOptionsRejected 'full release gate rejects incomplete imported Cat option evidence'
    $fullCatalogCatOptions[0].observations = $catOptionsObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $sheepOptionsObservations = $fullCatalogSheepOptions[0].observations
    $fullCatalogSheepOptions[0].observations = @('sheep-options result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteSheepOptionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteSheepOptionsRejected = $_.Exception.Message -match 'required observation for sheep' }
    Assert-True $incompleteSheepOptionsRejected 'full release gate rejects incomplete imported Sheep option evidence'
    $fullCatalogSheepOptions[0].observations = $sheepOptionsObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $horseOptionsObservations = @('horse-options result=true imported=true color=GRAY style=WHITE_DOTS domestication=5 max-domestication=20 jump-strength=0.8 saddled=true armor=DIAMOND_HORSE_ARMOR control-color=BLACK control-style=BLACK_DOTS control-domestication=1 control-max-domestication=12 control-jump-strength=0.4 control-saddled=false control-armor=AIR donkey-chest=true control-donkey-chest=false pig-saddled=true control-pig-saddled=false strider-saddled=true control-strider-saddled=false')
    $fullCatalogHorseOptions[0].observations = @('horse-options result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteHorseOptionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteHorseOptionsRejected = $_.Exception.Message -match 'required observation for horseOptionsImported' }
    Assert-True $incompleteHorseOptionsRejected 'full release gate rejects incomplete imported Horse option evidence'
    $fullCatalogHorseOptions[0].observations = $horseOptionsObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogFallingBlockOptions = @($fullCatalogReport.probes | Where-Object id -eq 'fallingBlockOptionsImported')
    if ($fullCatalogFallingBlockOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain fallingBlockOptionsImported.' }
    $fallingBlockOptionsObservations = $fullCatalogFallingBlockOptions[0].observations
    $fullCatalogFallingBlockOptions[0].observations = @('falling-block-options-imported result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteFallingBlockOptionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteFallingBlockOptionsRejected = $_.Exception.Message -match 'required observation for fallingBlockOptionsImported' }
    Assert-True $incompleteFallingBlockOptionsRejected 'full release gate rejects incomplete imported FallingBlock option evidence'
    $fullCatalogFallingBlockOptions[0].observations = $fallingBlockOptionsObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogBoatOptions = @($fullCatalogReport.probes | Where-Object id -eq 'boatOptionsImported')
    if ($fullCatalogBoatOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain boatOptionsImported.' }
    $boatOptionsObservations = $fullCatalogBoatOptions[0].observations
    $fullCatalogBoatOptions[0].observations = @('boat-options-imported result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteBoatOptionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteBoatOptionsRejected = $_.Exception.Message -match 'required observation for boatOptionsImported' }
    Assert-True $incompleteBoatOptionsRejected 'full release gate rejects incomplete imported Boat option evidence'
    $fullCatalogBoatOptions[0].observations = $boatOptionsObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogInteractionOptions = @($fullCatalogReport.probes | Where-Object id -eq 'interactionOptionsImported')
    if ($fullCatalogInteractionOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain interactionOptionsImported.' }
    $interactionOptionsObservations = $fullCatalogInteractionOptions[0].observations
    $fullCatalogInteractionOptions[0].observations = @('interaction-options-imported result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteInteractionOptionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteInteractionOptionsRejected = $_.Exception.Message -match 'required observation for interactionOptionsImported' }
    Assert-True $incompleteInteractionOptionsRejected 'full release gate rejects incomplete imported Interaction option evidence'
    $fullCatalogInteractionOptions[0].observations = $interactionOptionsObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogGoatOptions = @($fullCatalogReport.probes | Where-Object id -eq 'goatOptionsImported')
    if ($fullCatalogGoatOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain goatOptionsImported.' }
    $goatOptionsObservations = $fullCatalogGoatOptions[0].observations
    $fullCatalogGoatOptions[0].observations = @('goat-options result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteGoatOptionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteGoatOptionsRejected = $_.Exception.Message -match 'required observation for goatOptionsImported' }
    Assert-True $incompleteGoatOptionsRejected 'full release gate rejects incomplete imported Goat option evidence'
    $fullCatalogGoatOptions[0].observations = $goatOptionsObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogTameableOptions = @($fullCatalogReport.probes | Where-Object id -eq 'tameableOptionsImported')
    if ($fullCatalogTameableOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain tameableOptionsImported.' }
    $tameableOptionsObservations = $fullCatalogTameableOptions[0].observations
    $fullCatalogTameableOptions[0].observations = @('tameable-options result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteTameableOptionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteTameableOptionsRejected = $_.Exception.Message -match 'required observation for tameableOptionsImported' }
    Assert-True $incompleteTameableOptionsRejected 'full release gate rejects incomplete imported Tameable option evidence'
    $fullCatalogTameableOptions[0].observations = $tameableOptionsObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogTamedFamilies = @($fullCatalogReport.probes | Where-Object id -eq 'tamedFamilyOptions')
    if ($fullCatalogTamedFamilies.Count -ne 1) { throw 'Synthetic full catalog report must contain tamedFamilyOptions.' }
    $tamedFamilyObservations = $fullCatalogTamedFamilies[0].observations
    $fullCatalogTamedFamilies[0].observations = @('tamed-family-options result=true imported=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteTamedFamiliesRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteTamedFamiliesRejected = $_.Exception.Message -match 'required observation for tamedFamilyOptions' }
    Assert-True $incompleteTamedFamiliesRejected 'full release gate rejects incomplete Tamed family evidence'
    $fullCatalogTamedFamilies[0].observations = $tamedFamilyObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogExperienceOrbOptions = @($fullCatalogReport.probes | Where-Object id -eq 'experienceOrbOptions')
    if ($fullCatalogExperienceOrbOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain experienceOrbOptions.' }
    $experienceOrbObservations = $fullCatalogExperienceOrbOptions[0].observations
    $fullCatalogExperienceOrbOptions[0].observations = @('experience-orb-options result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteExperienceOrbRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteExperienceOrbRejected = $_.Exception.Message -match 'required observation for experienceOrbOptions' }
    Assert-True $incompleteExperienceOrbRejected 'full release gate rejects Experience option evidence without readback and preservation controls'
    $fullCatalogExperienceOrbOptions[0].observations = $experienceOrbObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogPandaOptions = @($fullCatalogReport.probes | Where-Object id -eq 'pandaOptions')
    if ($fullCatalogPandaOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain pandaOptions.' }
    $pandaObservations = $fullCatalogPandaOptions[0].observations
    $fullCatalogPandaOptions[0].observations = @('panda-options result=true imported=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompletePandaOptionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompletePandaOptionsRejected = $_.Exception.Message -match 'required observation for pandaOptions' }
    Assert-True $incompletePandaOptionsRejected 'full release gate rejects Panda option evidence without gene readback and preservation controls'
    $fullCatalogPandaOptions[0].observations = $pandaObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogVillagerOptions = @($fullCatalogReport.probes | Where-Object id -eq 'villagerOptions')
    if ($fullCatalogVillagerOptions.Count -ne 1) { throw 'Synthetic full catalog report must contain villagerOptions.' }
    $villagerObservations = $fullCatalogVillagerOptions[0].observations
    $fullCatalogVillagerOptions[0].observations = @('villager-options result=true imported=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteVillagerOptionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteVillagerOptionsRejected = $_.Exception.Message -match 'required observation for villagerOptions' }
    Assert-True $incompleteVillagerOptionsRejected 'full release gate rejects Villager option evidence without readback and preservation controls'
    $fullCatalogVillagerOptions[0].observations = $villagerObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogParrotFlyingSpeed = @($fullCatalogReport.probes | Where-Object id -eq 'parrotFlyingSpeed')
    if ($fullCatalogParrotFlyingSpeed.Count -ne 1) { throw 'Synthetic full catalog report must contain parrotFlyingSpeed.' }
    $parrotFlyingSpeedObservations = $fullCatalogParrotFlyingSpeed[0].observations
    $fullCatalogParrotFlyingSpeed[0].observations = @('parrot-flying-speed result=true imported=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteParrotFlyingSpeedRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteParrotFlyingSpeedRejected = $_.Exception.Message -match 'required observation for parrotFlyingSpeed' }
    Assert-True $incompleteParrotFlyingSpeedRejected 'full release gate rejects Parrot FlyingSpeed evidence without attribute readback and controls'
    $fullCatalogParrotFlyingSpeed[0].observations = $parrotFlyingSpeedObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogPlayerCreated = @($fullCatalogReport.probes | Where-Object id -eq 'playerCreatedImported')
    if ($fullCatalogPlayerCreated.Count -ne 1) { throw 'Synthetic full catalog report must contain playerCreatedImported.' }
    $playerCreatedObservations = $fullCatalogPlayerCreated[0].observations
    $fullCatalogPlayerCreated[0].observations = @('player-created-imported result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompletePlayerCreatedRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompletePlayerCreatedRejected = $_.Exception.Message -match 'required observation for playerCreatedImported' }
    Assert-True $incompletePlayerCreatedRejected 'full release gate rejects incomplete imported Iron Golem PlayerCreated evidence'
    $fullCatalogPlayerCreated[0].observations = $playerCreatedObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogNoGravity = @($fullCatalogReport.probes | Where-Object id -eq 'noGravityImported')
    if ($fullCatalogNoGravity.Count -ne 1) { throw 'Synthetic full catalog report must contain noGravityImported.' }
    $noGravityObservations = $fullCatalogNoGravity[0].observations
    $fullCatalogNoGravity[0].observations = @('no-gravity-imported result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteNoGravityRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteNoGravityRejected = $_.Exception.Message -match 'required observation for noGravityImported' }
    Assert-True $incompleteNoGravityRejected 'full release gate rejects incomplete imported NoGravity evidence'
    $fullCatalogNoGravity[0].observations = $noGravityObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $fullCatalogArmorStand = @($fullCatalogReport.probes | Where-Object id -eq 'armorStand')
    if ($fullCatalogArmorStand.Count -ne 1) { throw 'Synthetic full catalog report must contain armorStand.' }
    $armorStandObservations = $fullCatalogArmorStand[0].observations
    $fullCatalogArmorStand[0].observations = @('armor-stand-options-imported result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteArmorStandRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteArmorStandRejected = $_.Exception.Message -match 'required observation for armorStand' }
    Assert-True $incompleteArmorStandRejected 'full release gate rejects incomplete imported Armor Stand option evidence'
    $fullCatalogArmorStand[0].observations = $armorStandObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $creeperOptionsObservations = $fullCatalogCreeperOptions[0].observations
    $fullCatalogCreeperOptions[0].observations = @('creeper-options result=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $incompleteCreeperOptionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $incompleteCreeperOptionsRejected = $_.Exception.Message -match 'required observation for creeper' }
    Assert-True $incompleteCreeperOptionsRejected 'full release gate rejects incomplete imported Creeper option evidence'
    $fullCatalogCreeperOptions[0].observations = $creeperOptionsObservations
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
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
    $importedDropProbe.observations = @('drop-item-import valid=true missing-rejected=true invalid-preserved=false owner=true target-owner=true')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedDropRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedDropRejected = $_.Exception.Message -match 'required observation for dropItemImported' }
    Assert-True $badImportedDropRejected 'full release gate rejects incomplete imported drop evidence'
    $importedDropProbe.observations = @('drop-item-import valid=true missing-rejected=true invalid-preserved=true owner=true target-owner=true')
    $importedDropProbe.observations = @('drop-item-import valid=true missing-rejected=true invalid-preserved=true owner=true target-owner=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badTargetOwnerRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badTargetOwnerRejected = $_.Exception.Message -match 'required observation for dropItemImported' }
    Assert-True $badTargetOwnerRejected 'full release gate rejects missing target-scoped dropped-item owner evidence'
    $importedDropProbe.observations = @('drop-item-import valid=true missing-rejected=true invalid-preserved=true owner=true target-owner=true')
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
    $mythicSpawnerChanceProbe = $fullCatalogReport.probes | Where-Object id -eq 'mythicSpawnerChanceImported'
    $mythicSpawnerChanceObservations = $mythicSpawnerChanceProbe.observations
    $mythicSpawnerChanceProbe.observations = @('mythic-spawner-chance imported=true valid-mapped=true invalid-mapped=true valid-natural=false invalid-rejected=true warning=true passed=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badMythicSpawnerChanceRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badMythicSpawnerChanceRejected = $_.Exception.Message -match 'required observation for mythicSpawnerChanceImported' }
    Assert-True $badMythicSpawnerChanceRejected 'full release gate rejects imported Chance without natural generation'
    $mythicSpawnerChanceProbe.observations = $mythicSpawnerChanceObservations
    $mythicSpawnerGroupProbe = $fullCatalogReport.probes | Where-Object id -eq 'mythicSpawnerGroupSizeImported'
    $mythicSpawnerGroupObservations = $mythicSpawnerGroupProbe.observations
    $mythicSpawnerGroupProbe.observations = @('mythic-spawner-group-size imported=true mapped=true natural=false requested=3 spawned=1 alive=1 max-alive=2 passed=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badMythicSpawnerGroupRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badMythicSpawnerGroupRejected = $_.Exception.Message -match 'required observation for mythicSpawnerGroupSizeImported' }
    Assert-True $badMythicSpawnerGroupRejected 'full release gate rejects imported GroupSize without capped natural generation'
    $mythicSpawnerGroupProbe.observations = $mythicSpawnerGroupObservations
    $mythicSpawnerConditionsProbe = $fullCatalogReport.probes | Where-Object id -eq 'mythicSpawnerConditionsImported'
    $mythicSpawnerConditionsObservations = $mythicSpawnerConditionsProbe.observations
    $mythicSpawnerConditionsProbe.observations = @('mythic-spawner-conditions imported=true mapped=true world-mapped=true biome-mapped=true biome-natural=true biome-rejected=true biome-pass-spawned=1 biome-reject-spawned=0 world-natural=false world-rejected=false rain=true thunder=false natural=false rejected=true pass-spawned=0 reject-spawned=0 world-pass-spawned=0 world-reject-spawned=1 instance=false restored=true passed=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badMythicSpawnerConditionsRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badMythicSpawnerConditionsRejected = $_.Exception.Message -match 'required observation for mythicSpawnerConditionsImported' }
    Assert-True $badMythicSpawnerConditionsRejected 'full release gate rejects imported Spawner conditions without natural pass and fail evidence'
    $mythicSpawnerConditionsProbe.observations = $mythicSpawnerConditionsObservations
    $mythicSpawnerConditionsProbe.observations = @('mythic-spawner-conditions imported=true mapped=true world-mapped=true biome-mapped=true biome-natural=false biome-rejected=false biome-pass-spawned=0 biome-reject-spawned=1 world-natural=true world-rejected=true rain=true thunder=false natural=true rejected=true pass-spawned=1 reject-spawned=0 world-pass-spawned=1 world-reject-spawned=0 instance=true restored=true passed=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badMythicSpawnerBiomeRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badMythicSpawnerBiomeRejected = $_.Exception.Message -match 'required observation for mythicSpawnerConditionsImported' }
    Assert-True $badMythicSpawnerBiomeRejected 'full release gate rejects imported Spawner BiomeType without positive and negative evidence'
    $mythicSpawnerConditionsProbe.observations = $mythicSpawnerConditionsObservations
    $mythicSpawnerRandomPoolProbe = $fullCatalogReport.probes | Where-Object id -eq 'mythicSpawnerRandomPoolImported'
    $mythicSpawnerRandomPoolObservations = $mythicSpawnerRandomPoolProbe.observations
    $mythicSpawnerRandomPoolProbe.observations = @('mythic-spawner-random-pool imported=true mapped=true sampled=false expected-emberling=0.250000 expected-emberling_boss=0.750000 observed-emberling=0.240000 observed-emberling_boss=0.760000 max-abs-deviation=0.010000 samples=99 passed=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badMythicSpawnerRandomPoolRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badMythicSpawnerRandomPoolRejected = $_.Exception.Message -match 'required observation for mythicSpawnerRandomPoolImported' }
    Assert-True $badMythicSpawnerRandomPoolRejected 'full release gate rejects an imported weighted pool without 100 successful samples'
    $mythicSpawnerRandomPoolProbe.observations = $mythicSpawnerRandomPoolObservations
    $mythicSpawnerDropTablesProbe = $fullCatalogReport.probes | Where-Object id -eq 'mythicSpawnerRandomPoolDropTables'
    $mythicSpawnerDropTablesObservations = $mythicSpawnerDropTablesProbe.observations
    $mythicSpawnerDropTablesProbe.observations = @('mythic-spawner-drop-table imported=true references=true weighted=true tables-loaded=true spawned=32 variants=true assigned=true common-table-reloaded=true rare-table-reloaded=true common-alive=true rare-alive=true common-natural=true rare-natural=true common-bread=1 common-diamond=0 rare-bread=0 rare-diamond=1 restored=false passed=false')
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badMythicSpawnerDropTablesRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badMythicSpawnerDropTablesRejected = $_.Exception.Message -match 'required observation for mythicSpawnerRandomPoolDropTables' }
    Assert-True $badMythicSpawnerDropTablesRejected 'full release gate rejects mismatched natural drops or failed fixture cleanup'
    $mythicSpawnerDropTablesProbe.observations = $mythicSpawnerDropTablesObservations
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
    $importedSpyglassProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedSpyglassCondition'
    $importedSpyglassObservations = @($importedSpyglassProbe.observations)
    $failedImportedSpyglassObservation = ([string]$importedSpyglassObservations[0]) -replace 'inactive-rejected=true', 'inactive-rejected=false'
    $importedSpyglassProbe.observations = @($failedImportedSpyglassObservation)
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedSpyglassRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedSpyglassRejected = $_.Exception.Message -match 'required observation for importedSpyglassCondition' }
    Assert-True $badImportedSpyglassRejected 'full release gate rejects a failed imported Spyglass inactive-state control'
    $importedSpyglassProbe.observations = $importedSpyglassObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedSpyglassCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedSpyglassCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedSpyglassRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedSpyglassRejected = $_.Exception.Message -match 'importedSpyglassCondition' }
    Assert-True $missingImportedSpyglassRejected 'full release gate rejects missing imported Spyglass evidence'
    $fullCatalogReport.expectedProbeIds += 'importedSpyglassCondition'
    $fullCatalogReport.probes += $importedSpyglassProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $importedSurvivalProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedSurvivalModeCondition'
    $importedSurvivalObservations = @($importedSurvivalProbe.observations)
    $failedImportedSurvivalObservation = ([string]$importedSurvivalObservations[0]) -replace 'invalid-rejected=true', 'invalid-rejected=false'
    $importedSurvivalProbe.observations = @($failedImportedSurvivalObservation)
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedSurvivalRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedSurvivalRejected = $_.Exception.Message -match 'required observation for importedSurvivalModeCondition' }
    Assert-True $badImportedSurvivalRejected 'full release gate rejects malformed-argument acceptance for imported survival conditions'
    $importedSurvivalProbe.observations = $importedSurvivalObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedSurvivalModeCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedSurvivalModeCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedSurvivalRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedSurvivalRejected = $_.Exception.Message -match 'importedSurvivalModeCondition' }
    Assert-True $missingImportedSurvivalRejected 'full release gate rejects missing imported survival condition evidence'
    $fullCatalogReport.expectedProbeIds += 'importedSurvivalModeCondition'
    $fullCatalogReport.probes += $importedSurvivalProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $importedVariableProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedVariableConditions'
    $importedVariableObservations = @($importedVariableProbe.observations)
    $failedImportedVariableObservation = ([string]$importedVariableObservations[0]) -replace 'scopes=true', 'scopes=false'
    $importedVariableProbe.observations = @($failedImportedVariableObservation)
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedVariableRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedVariableRejected = $_.Exception.Message -match 'required observation for importedVariableConditions' }
    Assert-True $badImportedVariableRejected 'full release gate rejects incomplete imported variable scope evidence'
    $importedVariableProbe.observations = $importedVariableObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedVariableConditions' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedVariableConditions' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedVariableRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedVariableRejected = $_.Exception.Message -match 'importedVariableConditions' }
    Assert-True $missingImportedVariableRejected 'full release gate rejects missing imported variable condition evidence'
    $fullCatalogReport.expectedProbeIds += 'importedVariableConditions'
    $fullCatalogReport.probes += $importedVariableProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $importedBurningProbe = $fullCatalogReport.probes | Where-Object id -eq 'importedBurningCondition'
    $importedBurningObservations = @($importedBurningProbe.observations)
    $failedImportedBurningObservation = ([string]$importedBurningObservations[0]) -replace 'target-negative=true', 'target-negative=false'
    $importedBurningProbe.observations = @($failedImportedBurningObservation)
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $badImportedBurningRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $badImportedBurningRejected = $_.Exception.Message -match 'required observation for importedBurningCondition' }
    Assert-True $badImportedBurningRejected 'full release gate rejects a failed imported Burning target-state observation'
    $importedBurningProbe.observations = $importedBurningObservations
    $fullCatalogReport.expectedProbeIds = @($fullCatalogReport.expectedProbeIds | Where-Object { $_ -ne 'importedBurningCondition' })
    $fullCatalogReport.probes = @($fullCatalogReport.probes | Where-Object { $_.id -ne 'importedBurningCondition' })
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $missingImportedBurningRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
            -RequireTargetPaperVersion -RequireFullReleaseCatalog
    } catch { $missingImportedBurningRejected = $_.Exception.Message -match 'importedBurningCondition' }
    Assert-True $missingImportedBurningRejected 'full release gate rejects missing imported Burning evidence'
    $fullCatalogReport.expectedProbeIds += 'importedBurningCondition'
    $fullCatalogReport.probes += $importedBurningProbe
    $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
    $naturalSlimeSplitProbe = $fullCatalogReport.probes | Where-Object id -eq 'mobOptionsSlimeSplitNatural'
    $naturalSlimeSplitObservations = $naturalSlimeSplitProbe.observations
    foreach ($invalidSlimeSplitObservation in @(
            'mob-options-slime-split-natural protected-size=4 control-size=4 protected-event=true protected-cancelled=true protected-event-count=4 control-event=true control-cancelled=false control-event-count=3 protected-dead=true control-dead=true protected-children=1 control-children=3 monster-spawning-enabled=true spawn-flags-restored=true damage=api passed=true',
            'mob-options-slime-split-natural protected-size=4 control-size=4 protected-event=true protected-cancelled=true protected-event-count=4 control-event=true control-cancelled=false control-event-count=3 protected-dead=true control-dead=true protected-children=0 control-children=0 monster-spawning-enabled=true spawn-flags-restored=true damage=api passed=true')) {
        $naturalSlimeSplitProbe.observations = @($invalidSlimeSplitObservation)
        $fullCatalogReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $fullCatalogPath -Encoding utf8
        $invalidSlimeSplitRejected = $false
        $invalidSlimeSplitError = ''
        try {
            & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $fullCatalogPath `
                -RequireTargetPaperVersion -RequireFullReleaseCatalog
        } catch {
            $invalidSlimeSplitError = $_.Exception.Message
            $invalidSlimeSplitRejected = $invalidSlimeSplitError -match 'required observation for mobOptionsSlimeSplitNatural'
        }
        Assert-True $invalidSlimeSplitRejected "full release gate rejects invalid natural Slime split evidence: $invalidSlimeSplitError"
    }
    $naturalSlimeSplitProbe.observations = $naturalSlimeSplitObservations
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

    $wrongBuildReportPath = Join-Path $temp 'wrong-build-report.json'
    $wrongBuildReport = Get-Content -LiteralPath $targetGateReportPath -Raw -Encoding utf8 | ConvertFrom-Json
    $wrongBuildReport.paperVersion = '1.21.1-134-test'
    $wrongBuildReport | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $wrongBuildReportPath -Encoding utf8
    $wrongBuildRejected = $false
    try {
        & (Join-Path $PSScriptRoot 'assert-report-complete.ps1') -ReportPath $wrongBuildReportPath `
            -RequireTargetPaperVersion -ExpectedPaperVersion '1.21.1' -ExpectedPaperBuild '1.21.1-133'
    } catch {
        $wrongBuildRejected = $_.Exception.Message -match 'Paper implementation build mismatch'
    }
    Assert-True $wrongBuildRejected 'strict gate rejects the wrong Paper build within the expected Minecraft version'

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
            'mythicTeleport',
            'spawningDistanceClientWalk', 'interactableClient')) {
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
    $env:PAPER_INTERACTABLE_WORKFLOW = $previousInteractableWorkflow
    $env:PAPER_SHOW_HEALTH_WORKFLOW = $previousShowHealthWorkflow
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $tempPath = [System.IO.Path]::GetFullPath($temp)
    $isOwnedTemp = $tempPath.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $tempPath).StartsWith('helstera-paper-report-', [System.StringComparison]::Ordinal)
    if ($isOwnedTemp) {
        Remove-Item -LiteralPath $tempPath -Recurse -Force -ErrorAction SilentlyContinue
    }
}
