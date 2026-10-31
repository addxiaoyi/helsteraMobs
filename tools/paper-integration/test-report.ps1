$ErrorActionPreference = 'Stop'
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
        '[Server thread/INFO]: [helsteraMobs] [自检] skill-tree-cast-persistence result=true blocked-before-unlock=true prerequisite-unlock-blocked=true snapshot-saved=true advanced-unlocked=true casts=1',
        '[Server thread/INFO]: [helsteraMobs] [自检] skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true bounds-condition=true bounding-box-overlap-condition=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-mobility=true leashed-condition=true has-owner-condition=true has-tag-condition=true target-has-tag-condition=true scoreboard-tag-mechanics=true score-mechanics=true global-score-condition=true score-condition=true custom-owner-relation=true owner-condition=true children-condition=true inside-outside-conditions=true distance-from-location-condition=true coordinate-difference-condition=true origin-location-condition=true line-of-sight-from-origin-condition=true bounds-condition=true enchanting-conditions=true plugin-condition=true field-of-view-condition=true spawn-reason-condition=true water-mob-condition=true zombie-condition=true aerial-condition=true enderman-condition=true enchantment-condition=true color-condition=true material-cooldown-condition=true material-cooldown-mechanic=true set-game-mode-mechanic=true jump-mechanic=true force-pull-mechanic=true set-offhand-mechanic=true set-armor-mechanic=true clear-inventory-mechanic=true remove-held-item-mechanic=true raw-experience-mechanic=true set-absorption-mechanic=true trigger-item-type-condition=true entity-item-type-condition=true entity-item-similar-condition=true item-similar-condition=true targets-condition=true item-type-condition=true structure-condition=true mythic-mob-type-condition=true trigger-block-type-condition=true frozen-condition=true dialogue-condition=true player-kills-condition=true absorption-condition=true health-absorption-condition=true potion-effect-condition=true aura-stacks-condition=true level-condition=true lunar-phase-condition=true string-equals-condition=true string-empty-condition=true compare-values-condition=true is-skill-condition=true owner-online-condition=true parent-relation=true is-child-condition=true parent-derived-conditions=true player-experience-mechanics=true oxygen-mechanic=true consume-slot-mechanic=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true target-categories=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true target-categories=true target-health=true faction-conditions=true faction-mechanic=true level-mechanic=true weather-mechanic=true time-mechanic=true lightning-mechanic=true signal-mechanics=true target-kill=true damage-melee=true condition-utility=true block-type-radius-condition=true player-radius-conditions=true cooldown-mechanics=true last-damage-cause=true motion-conditions=true directional-velocity-condition=true fall-speed-condition=true target-environment=true target-fluids=true target-blocked=true',
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
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-composition=true caster-permission=true food-level=true food-saturation=true free-inventory-slot=true has-item=true air-level=true custom-item=true target-state-conditions=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true spyglass-condition=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true name-condition=true',
        '[Server thread/INFO]: [自检] skill-trigger damage=true attacked=false teleport=true teleport-before=false bukkit-damage=true damage-cause-condition=true damage-amount-condition=true bow-tension-condition=true bukkit-attacked=true cancelled-condition=true cancelled-before=false cancelled-after=true entity-valid=true',
        '[Server thread/INFO]: block-events break=true place=true player=HelsteraTester',
        '[Server thread/INFO]: skill-trigger damage=true attacked=false teleport=true potion-effect-end=true teleport-before=false bukkit-damage=true bukkit-attacked=true cancelled-before=false cancelled-after=false entity-valid=true',
        '[Server thread/INFO]: potion-natural-expiry=true expired=true event-observed=false action-observed=true item-marker-observed=false',
        '[Server thread/INFO]: kill-trigger natural=true victim-dead=true action-observed=true passed=true',
        '[Server thread/INFO]: player-kill-trigger natural=true victim-player=true victim-death-observed=true action-observed=true player-kills-positive=true player-kills-negative=true passed=true',
        '[Server thread/INFO]: tempt-probe-delayed entity-target-trigger=true target-events=1 target-find-misses=0',
        '[Server thread/INFO]: item-pickup trigger=true player=HelsteraTester',
        '[Server thread/INFO]: vehicle-events enter=true exit=true dismount=true eject=true destroy=false player=HelsteraTester',
        '[Server thread/INFO]: environmental-damage trigger=true fire-ticks=0',
        '[Server thread/INFO]: [helsteraMobs] [trigger-debug] player-potion-consume event=true player=HelsteraTester item=POTION',
        '[Server thread/INFO]: player-potion-natural-expiry=true expired=true action-observed=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true looking-at-condition=true item-recharging-condition=true variable-contains-condition=true before=1.00,2.00,3.00 after=1.00,2.00,3.00 offset-distance=1.000 moved-distance=0.000',
        '[Server thread/INFO]: projectile-has-ended=true observed=true ticks=3 projectile=00000000-0000-0000-0000-000000000000',
        '[Server thread/INFO]: [helsteraMobs] [自检] horse-options result=true saddled=true armor=true chest=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] scale-options result=true scale=1.25',
        '[Server thread/INFO]: [helsteraMobs] [自检] health-options result=true health=42 max-health=42',
        '[Server thread/INFO]: [helsteraMobs] [自检] movement-speed-options result=true base=0.4',
        '[Server thread/INFO]: [helsteraMobs] [自检] knockback-resistance-options result=true base=0.75',
        '[Server thread/INFO]: [helsteraMobs] [自检] follow-range-options result=true base=40',
        '[Server thread/INFO]: [helsteraMobs] [自检] damage-options result=true base=12',
        '[Server thread/INFO]: [helsteraMobs] [自检] armor-options result=true base=8',
        '[Server thread/INFO]: [helsteraMobs] [自检] entity-config-flags result=true no-ai=true invulnerable=true silent=true gravity=false collidable=false',
        '[Server thread/INFO]: [helsteraMobs] [自检] exp-options result=true experience=13 event=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] age-options result=true age=-24000 baby=true adult=true age-lock=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] armor-stand-options result=true can-move=false can-tick=false items=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] entity-flag-options result=true tamed=true angry=true can-join-raid-setter=true patrol-leader=true patrol-spawn-point=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] enderman-held-block result=true material=OBSIDIAN',
        '[Server thread/INFO]: attribute-options result=true modifiers=1',
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
        '[Server thread/INFO]: ai-selector-core goals=true player-target=true monster-target=true clear-no-fallback=true owner-target=true faction-target=true conditional-target=true native-target=true selector=mode=PLAYERS can-attack=true can-chase=false conditional=is-player=>is-player entity=PLAYER passed=true passed=true',
        '[Server thread/INFO]: ai-route-probe token=followpath active-before=true moving=true advanced=true waypoint-before=route=followpath waypoint=103,64,0 finished=false waypoint-after=route=followpath waypoint=105,64,0 finished=false passed=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] death-loot items=1 conditional-drop=true rejected-drop-absent=true dynamic-chance-drop=true experience=10',
        '[Server thread/INFO]: [helsteraMobs] [自检] spawner-probe id=pass requested=true failure=null',
        '[Server thread/INFO]: [helsteraMobs] [自检] spawner-probe id=reject requested=false failure=player gate closed',
        '[Server thread/INFO]: spawner-max-spawns id=max_spawn_probe first=true second=false total=1 failure=max spawns reached',
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
        '[Server thread/INFO]: spawner-border id=border_clipped_inside_pass requested=true configured-radius=16.0 effective-radius=10.0 border-size=20.0 restored=true failure=null',
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
    $lines += '[Server thread/INFO]: menu-render id=parity-page-1 slot=10=REDSTONE'
    $lines += '[Server thread/INFO]: menu-render id=parity-page-2 slot=13=EMERALD'
    $lines += '[Server thread/INFO]: menu-click id=parity-page-1 slot=10 permitted=false ran=false'
    Set-Content -LiteralPath (Join-Path $logDirectory 'latest.log') -Value $lines -Encoding utf8
    Set-Content -LiteralPath (Join-Path $temp 'menu-client.stdout.log') -Value @(
        'PAPER_MENU_REPORT {"status":"passed","observations":{"permissionDeniedClickStayedOpen":true,"pageOne":true,"pageTwo":true,"actionClosedInventory":true,"allowedActionMessage":true,"deniedActionMessageAbsent":true}}'
    ) -Encoding utf8
    Set-Content -LiteralPath (Join-Path $temp 'audience-client.stdout.log') -Value @(
        'PAPER_AUDIENCE_REPORT {"status":"passed","observations":{"message":true,"title":true,"actionbar":true,"particle":true}}',
        'PAPER_AUDIENCE_FILTER_REPORT {"status":"passed","observations":{"radiusIncludedTester":true,"radiusIncludedGuest":true,"radiusExcludedTester":true,"radiusExcludedGuest":true,"permissionIncludedTester":true,"permissionIncludedGuest":true,"factionIncludedTester":true,"factionIncludedGuest":true,"factionExcludedTester":true,"factionExcludedGuest":true}}'
    ) -Encoding utf8
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $temp | Out-Null

    $schemaPath = Join-Path $PSScriptRoot 'integration-report.schema.json'
    $reportPath = Join-Path $temp 'integration-report.json'
    & $pythonCommand @pythonPrefix (Join-Path $PSScriptRoot 'validate-report.py') $schemaPath $reportPath
    if ($LASTEXITCODE -ne 0) { throw "JSON schema validation failed" }

    $report = Get-Content -LiteralPath $reportPath -Raw -Encoding utf8 |
        ConvertFrom-Json
    $schema = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'integration-report.schema.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    Assert-ReportShape $report $schema
    foreach ($requiredProbeId in @('custom-menu-player-workflow', 'audience-player-delivery',
            'audience-filter-delivery', 'random-spawn-distribution', 'skillTeleportCoordinates')) {
        Assert-True ($report.expectedProbeIds -contains $requiredProbeId) "probe catalog contains $requiredProbeId"
        Assert-True (@($report.probes | Where-Object id -eq $requiredProbeId).Count -gt 0) "report contains $requiredProbeId"
    }
    Assert-True $report.pluginEnabled 'plugin enabled detection'
    Assert-True (($report.probes | Where-Object id -eq 'pathfinderClear').status -eq 'passed') 'native Pathfinder clear probe'
    Assert-True (($report.probes | Where-Object id -eq 'aiPriority').status -eq 'passed') 'AI selector priority arbitration probe'
    Assert-True (($report.probes | Where-Object id -eq 'aiPriorityTargets').status -eq 'passed') 'AI priority probe target setup'
    Assert-True (($report.probes | Where-Object id -eq 'aiSelectorCore').status -eq 'passed') 'AI selector core Paper probe'
    Assert-True (($report.probes | Where-Object id -eq 'aiRoute').status -eq 'passed') 'AI route waypoint Paper probe'
    Assert-True ($report.paperVersion -eq '1.21.1-test') 'Paper version extraction'
    Assert-True (($report.probes | Where-Object id -eq 'horse').status -eq 'passed') 'positive probe'
    Assert-True (($report.probes | Where-Object id -eq 'ageOptions').status -eq 'passed') 'age options probe'
    Assert-True (($report.probes | Where-Object id -eq 'entityFlagOptions').status -eq 'passed') 'entity flag options probe'
    Assert-True (($report.probes | Where-Object id -eq 'expOptions').status -eq 'passed') 'experience option probe'
    Assert-True (($report.probes | Where-Object id -eq 'endermanHeldBlock').status -eq 'passed') 'Enderman held block option probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnOptionRuntime').status -eq 'passed') 'spawn option runtime probe'
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
    Assert-True (($report.probes | Where-Object id -eq 'deathLootDynamicChance').status -eq 'passed') 'death loot dynamic chance probe'
    Assert-True (($report.probes | Where-Object id -eq 'skillTeleport').status -eq 'passed') 'teleport mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'skillScheduling').status -eq 'passed') 'Paper tick scheduler probe'
    Assert-True (($report.probes | Where-Object id -eq 'skillTreeBranches').status -eq 'passed') 'Paper SkillTree branch gates probe'
    Assert-True (($report.probes | Where-Object id -eq 'skillTreeCastPersistence').status -eq 'passed') 'Paper persisted SkillTree cast gate probe'
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
    Assert-True (($report.probes | Where-Object id -eq 'conditionUtilityAliases').status -eq 'passed') 'condition utility alias probe'
    Assert-True (($report.probes | Where-Object id -eq 'spyglassCondition').status -eq 'passed') 'spyglass condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'foodSaturationCondition').status -eq 'passed') 'food saturation condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'freeInventorySlotCondition').status -eq 'passed') 'free inventory slot condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'cooldownMechanics').status -eq 'passed') 'cooldown mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'lastDamageCauseCondition').status -eq 'passed') 'last damage cause condition probe'
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
    Assert-True (($report.probes | Where-Object id -eq 'scoreboardTagMechanics').status -eq 'passed') 'scoreboard tag mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'scoreMechanics').status -eq 'passed') 'score mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'customOwnerRelation').status -eq 'passed') 'custom owner relation probe'
    Assert-True (($report.probes | Where-Object id -eq 'ownerOnlineCondition').status -eq 'passed') 'owner online condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'parentRelation').status -eq 'passed') 'parent relation probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionIsChild').status -eq 'passed') 'is-child condition probe'
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
    Assert-True (($report.probes | Where-Object id -eq 'blockEvents').status -eq 'passed') 'block event trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'itemPickup').status -eq 'passed') 'item pickup trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'vehicleEvents').status -eq 'passed') 'vehicle event trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'ejectPassenger').status -eq 'passed') 'eject passenger mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'environmentalDamage').status -eq 'passed') 'environmental damage probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerMaxSpawns').status -eq 'passed') 'spawner max-spawns probe'
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
            'audience-filter-delivery', 'random-spawn-distribution', 'skillTeleportCoordinates')) {
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
        '[Server thread/INFO]: Done (1.000s)! For help, type "help"'
    )
    & (Join-Path $PSScriptRoot 'write-report.ps1') -ServerDirectory $dynamicSpawnerTemp | Out-Null
    $dynamicSpawnerReport = Get-Content -LiteralPath (Join-Path $dynamicSpawnerTemp 'integration-report.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    $aggregateProbes = @($dynamicSpawnerReport.probes | Where-Object id -eq 'spawner:aggregate')
    Assert-True ($aggregateProbes.Count -eq 1) 'dynamic spawner evidence is aggregated by ID'
    Assert-True ($aggregateProbes[0].status -eq 'passed') 'aggregated dynamic spawner keeps passing evidence'
    Assert-True ($dynamicSpawnerReport.expectedProbeIds -contains 'spawner:aggregate') 'dynamic spawner is registered in the strict catalog'

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
    $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $tempPath = [System.IO.Path]::GetFullPath($temp)
    $isOwnedTemp = $tempPath.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $tempPath).StartsWith('helstera-paper-report-', [System.StringComparison]::Ordinal)
    if ($isOwnedTemp) {
        Remove-Item -LiteralPath $tempPath -Recurse -Force -ErrorAction SilentlyContinue
    }
}
