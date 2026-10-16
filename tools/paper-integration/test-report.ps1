$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$temp = Join-Path ([System.IO.Path]::GetTempPath()) ('helstera-paper-report-' + [guid]::NewGuid())
$logDirectory = Join-Path $temp 'logs'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null

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
            } else {
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
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-mobility=true leashed-condition=true has-owner-condition=true has-tag-condition=true custom-owner-relation=true parent-relation=true is-child-condition=true parent-derived-conditions=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true target-categories=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true target-categories=true target-health=true target-environment=true target-fluids=true target-blocked=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-composition=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-composition=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-composition=true time-weather=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true world-biome=true condition-composition=true time-weather=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true world-biome=true world-biome-reject=true condition-composition=true time-weather=true custom-item=true',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true condition-composition=true caster-permission=true food-level=true has-item=true air-level=true custom-item=true',
        '[Server thread/INFO]: [自检] skill-trigger damage=true attacked=false teleport=true teleport-before=false bukkit-damage=true damage-cause-condition=true bukkit-attacked=true cancelled-condition=true cancelled-before=false cancelled-after=true entity-valid=true',
        '[Server thread/INFO]: block-events break=true place=true player=HelsteraTester',
        '[Server thread/INFO]: skill-trigger damage=true attacked=false teleport=true potion-effect-end=true teleport-before=false bukkit-damage=true bukkit-attacked=true cancelled-before=false cancelled-after=false entity-valid=true',
        '[Server thread/INFO]: potion-natural-expiry=true expired=true event-observed=false action-observed=true item-marker-observed=false',
        '[Server thread/INFO]: tempt-probe-delayed entity-target-trigger=true target-events=1 target-find-misses=0',
        '[Server thread/INFO]: item-pickup trigger=true player=HelsteraTester',
        '[Server thread/INFO]: vehicle-events enter=true exit=true dismount=true destroy=false player=HelsteraTester',
        '[Server thread/INFO]: environmental-damage trigger=true fire-ticks=0',
        '[Server thread/INFO]: skill-actions equipment=true potion=true drop-item=true teleport=true conditions=true before=1.00,2.00,3.00 after=1.00,2.00,3.00 offset-distance=1.000 moved-distance=0.000',
        '[Server thread/INFO]: [helsteraMobs] [自检] horse-options result=true',
        '[Server thread/INFO]: attribute-options result=true modifiers=1',
        '[Server thread/INFO]: item-attribute-options result=true modifiers=1',
        '[Server thread/INFO]: [helsteraMobs] [自检] events creeper-explode-cancelled=true bee-attack-cancelled=true zombie-transform-cancelled=true',
        '[Server thread/INFO]: pathfinder-clear probe=path_clear_probe found=true reachable=true started=true active-before=true accepted=true cleared=true stop-calls=1 passed=true',
        '[Server thread/INFO]: ai-priority-probe attack-first=true flee-first=true attack-state=ATTACK flee-state=FLEE',
        '[Server thread/INFO]: ai-priority-detail probe=priority_attack_probe target-assigned=true target-valid=true health-ratio=0.5 attack-priority=0 flee-priority=1 selector=mode=PLAYERS can-attack=true can-chase=true can-flee=true target-matches=true',
        '[Server thread/INFO]: [helsteraMobs] [自检] death-loot items=1 conditional-drop=true rejected-drop-absent=true dynamic-chance-drop=true experience=10',
        '[Server thread/INFO]: [helsteraMobs] [自检] spawner-probe id=pass requested=true failure=null',
        '[Server thread/INFO]: [helsteraMobs] [自检] spawner-probe id=reject requested=false failure=player gate closed',
        '[Server thread/INFO]: spawner-max-spawns id=max_spawn_probe first=true second=false total=1 failure=max spawns reached',
        '[Server thread/INFO]: spawner-scoped-limits id=scoped_limit_probe first=true second=false total=1 world-limit=1 failure=scoped spawn limits reached',
        '[Server thread/INFO]: spawner-player-limits id=player_limit_probe first=true second=false total=1 player-limit=1 failure=scoped spawn limits reached',
        '[Server thread/INFO]: spawner-cooldown id=cooldown_probe before=0 after-first=0 after-second=0 remaining-first=40 remaining-second=39',
        '[Server thread/INFO]: spawner-lunar id=lunar_probe requested=true phase=FULL failure=null',
        '[Server thread/INFO]: spawner-heightmap id=heightmap_ground_pass requested=true surface=STONE surface-y=64 failure=null',
        '[Server thread/INFO]: spawner-ground id=ground_missing_fail requested=false failure=no valid spawn location restored=true saved-blocks=384',
        '[Server thread/INFO]: spawner-border id=border_clipped_inside_pass requested=true configured-radius=16.0 effective-radius=10.0 border-size=20.0 restored=true failure=null',
        '[Server thread/INFO]: spawner-border id=border_outside_fail requested=false configured-radius=0.1 effective-radius=0.0 border-size=20.0 restored=true failure=no valid spawn location',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=border_clipped_inside_pass mode=natural-location requested=true failure=null',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=border_outside_fail mode=natural-location requested=false failure=no valid spawn location',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=heightmap_ground_pass mode=natural-location requested=true failure=null',
        '[Server thread/INFO]: [helsteraMobs] [SelfTest] spawner-probe id=ground_missing_fail mode=natural-location requested=false failure=no valid spawn location',
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
    & python (Join-Path $PSScriptRoot 'validate-report.py') $schemaPath $reportPath
    if ($LASTEXITCODE -ne 0) { throw "JSON schema validation failed" }

    $report = Get-Content -LiteralPath $reportPath -Raw -Encoding utf8 |
        ConvertFrom-Json
    $schema = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'integration-report.schema.json') -Raw -Encoding utf8 |
        ConvertFrom-Json
    Assert-ReportShape $report $schema
    Assert-True $report.pluginEnabled 'plugin enabled detection'
    Assert-True (($report.probes | Where-Object id -eq 'pathfinderClear').status -eq 'passed') 'native Pathfinder clear probe'
    Assert-True (($report.probes | Where-Object id -eq 'aiPriority').status -eq 'passed') 'AI selector priority arbitration probe'
    Assert-True (($report.probes | Where-Object id -eq 'aiPriorityTargets').status -eq 'passed') 'AI priority probe target setup'
    Assert-True ($report.paperVersion -eq '1.21.1-test') 'Paper version extraction'
    Assert-True (($report.probes | Where-Object id -eq 'horse').status -eq 'passed') 'positive probe'
    Assert-True (($report.probes | Where-Object id -eq 'sheep').status -eq 'not-observed') 'missing probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:reject').status -eq 'observed-rejected') 'rejected probe'
    Assert-True (($report.probes | Where-Object id -eq 'lootDynamicChance').status -eq 'passed') 'dynamic chance probe'
    $events = @($report.probes | Where-Object id -eq 'events')
    Assert-True ($events.Count -eq 1 -and $events[0].status -eq 'passed') 'single passing events probe'
    Assert-True ($events[0].observations[0] -match 'creeper-explode-cancelled=true bee-attack-cancelled=true zombie-transform-cancelled=true') 'all event policies cancelled'
    Assert-True (($report.probes | Where-Object id -eq 'custom-menu-player-workflow').status -eq 'passed') 'menu client workflow probe'
    Assert-True (($report.probes | Where-Object id -eq 'audience-player-delivery').status -eq 'passed') 'audience client workflow probe'
    Assert-True (($report.probes | Where-Object id -eq 'audience-filter-delivery').status -eq 'passed') 'audience filter workflow probe'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootConditions').status -eq 'passed') 'death loot conditions probe'
    Assert-True (($report.probes | Where-Object id -eq 'deathLootDynamicChance').status -eq 'passed') 'death loot dynamic chance probe'
    Assert-True (($report.probes | Where-Object id -eq 'skillTeleport').status -eq 'passed') 'teleport mechanic probe'
    Assert-True (($report.probes | Where-Object id -eq 'cancelledCondition').status -eq 'passed') 'cancelled condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'damageCauseCondition').status -eq 'passed') 'damage cause condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'potionNaturalExpiry').status -eq 'passed') 'natural potion expiry probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionCatalog').status -eq 'passed') 'condition catalog probe'
    Assert-True (($report.probes | Where-Object id -eq 'worldBiome').status -eq 'passed') 'world and biome condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'worldBiomeReject').status -eq 'passed') 'world and biome rejection probe'
    Assert-True (($report.probes | Where-Object id -eq 'targetCategories').status -eq 'passed') 'target categories probe'
    Assert-True (($report.probes | Where-Object id -eq 'targetHealthComparators').status -eq 'passed') 'target health comparators probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionComposition').status -eq 'passed') 'condition composition probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionMobility').status -eq 'passed') 'condition mobility probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionLeashed').status -eq 'passed') 'leashed condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionHasOwner').status -eq 'passed') 'has-owner condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionHasTag').status -eq 'passed') 'has-tag condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'customOwnerRelation').status -eq 'passed') 'custom owner relation probe'
    Assert-True (($report.probes | Where-Object id -eq 'parentRelation').status -eq 'passed') 'parent relation probe'
    Assert-True (($report.probes | Where-Object id -eq 'conditionIsChild').status -eq 'passed') 'is-child condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'parentDerivedConditions').status -eq 'passed') 'parent-derived conditions probe'
    Assert-True (($report.probes | Where-Object id -eq 'timeWeather').status -eq 'passed') 'time and weather condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'customItemResolver').status -eq 'passed') 'custom item resolver probe'
    Assert-True (($report.probes | Where-Object id -eq 'casterPermission').status -eq 'passed') 'caster permission probe'
    Assert-True (($report.probes | Where-Object id -eq 'foodLevelCondition').status -eq 'passed') 'food level condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'hasItemCondition').status -eq 'passed') 'has item condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'airLevelCondition').status -eq 'passed') 'air level condition probe'
    Assert-True (($report.probes | Where-Object id -eq 'skillTrigger').status -eq 'passed') 'skill trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'blockEvents').status -eq 'passed') 'block event trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'itemPickup').status -eq 'passed') 'item pickup trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'vehicleEvents').status -eq 'passed') 'vehicle event trigger probe'
    Assert-True (($report.probes | Where-Object id -eq 'environmentalDamage').status -eq 'passed') 'environmental damage probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerMaxSpawns').status -eq 'passed') 'spawner max-spawns probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerScopedLimits').status -eq 'passed') 'spawner scoped-limits probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerPlayerLimits').status -eq 'passed') 'spawner player-limits probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerCooldown').status -eq 'passed') 'spawner cooldown probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerLunar').status -eq 'passed') 'spawner lunar probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerHeightmap').status -eq 'passed') 'spawner heightmap probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawnerGroundRestored').status -eq 'passed') 'spawner missing-ground cleanup probe'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:heightmap_ground_pass').status -eq 'passed') 'natural heightmap ground positive control'
    Assert-True (($report.probes | Where-Object id -eq 'spawner:ground_missing_fail').status -eq 'observed-rejected') 'natural no-ground negative control'
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
