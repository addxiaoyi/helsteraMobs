$ErrorActionPreference = 'Stop'
$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("helstera-evidence-audit-{0}" -f [guid]::NewGuid().ToString('N'))
$documents = @(
    'README.md',
    'MYTHICMOBS_PARITY_MATRIX.md',
    'tools/paper-integration/README.md',
    'tools/paper-integration/STARTUP_EVIDENCE.md'
)

try {
    foreach ($document in $documents) {
        $path = Join-Path $temporaryRoot $document
        [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($path))
        $content = switch ($document) {
            'README.md' {
                'The matrix currently has `1`' + [Environment]::NewLine +
                    '`mapped-unverified` rows.' + [Environment]::NewLine +
                    'Latest strict report has 0 probes, zero incomplete' + [Environment]::NewLine +
                    'Current fixture inventory: 3 cited IDs; 1 report files; 1 directories without reports; 1 missing directories; 2 archived release reports.' + [Environment]::NewLine
            }
            'MYTHICMOBS_PARITY_MATRIX.md' {
                "| Area | Status | Evidence |`n| --- | --- | --- |`n| Example | mapped-unverified | pending |`n"
            }
            default { "" }
        }
        [System.IO.File]::WriteAllText($path, $content)
    }

    $evidencePath = Join-Path $temporaryRoot 'tools/paper-integration/STARTUP_EVIDENCE.md'
    [System.IO.File]::WriteAllText($evidencePath,
        "Paper jar: ``srv-1.21.1/paper.jar``.`nVersioned fixture: ``srv-paper-1.21.1-r4``.`nDirectory only: ``srv-paper-no-report``.`nMissing: ``srv-paper-old``.`n")
    [void][System.IO.Directory]::CreateDirectory((Join-Path $temporaryRoot 'srv-paper-1.21.1-r4'))
    [System.IO.File]::WriteAllText(
        (Join-Path $temporaryRoot 'srv-paper-1.21.1-r4/integration-report.json'), '{}')
    [void][System.IO.Directory]::CreateDirectory((Join-Path $temporaryRoot 'srv-paper-no-report'))
    $archivePath = Join-Path $temporaryRoot 'tools/paper-integration/reports/test-release.json'
    [void][System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($archivePath))
    $fullReport = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'reports/paper-1.21.1-drop-inline-20261104-r2.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $visibilityProbe = @($fullReport.probes | Where-Object id -eq 'visibilityOptions')
    if ($visibilityProbe.Count -ne 1) { throw 'Synthetic release report must contain visibilityOptions.' }
    $visibilityProbe[0].observations = @('visibility-options-imported result=true imported=true configured=true controls=true')
    $armorStandProbe = @($fullReport.probes | Where-Object id -eq 'armorStand')
    if ($armorStandProbe.Count -ne 1) { throw 'Synthetic release report must contain armorStand.' }
    $armorStandProbe[0].observations = @('armor-stand-options-imported result=true imported=true configured=true controls=true item-body=true item-feet=true item-hand=true item-offhand=true item-head=true item-legs=true wrong-type-preserved=true invalid-preserved=true')
    $gameplayPlaceholderProbe = @($fullReport.probes | Where-Object id -eq 'gameplayPlaceholders')
    if ($gameplayPlaceholderProbe.Count -ne 1) { throw 'Synthetic release report must contain gameplayPlaceholders.' }
    $gameplayPlaceholderProbe[0].observations = @($gameplayPlaceholderProbe[0].observations) + @(
        'attack-cooldown-placeholder result=true in-range=true recharging=true caster=0.5 target=0.5 aliases=true non-player-unresolved=true')
    $conditionMobilityProbe = @($fullReport.probes | Where-Object id -eq 'conditionMobility')
    if ($conditionMobilityProbe.Count -ne 1) { throw 'Synthetic release report must contain conditionMobility.' }
    $conditionMobilityProbe[0].observations = @($conditionMobilityProbe[0].observations | ForEach-Object {
        $_ -replace 'condition-mobility=true', 'condition-mobility=true moving-default-stationary=true moving-exact-fractional=true moving-alias-fractional=true moving-approx-same-block=true moving-approx-block=true moving-exact-argument=true moving-e-argument=true moving-invalid-rejected=true moving-imported=true moving-restored=true'
    })
    foreach ($requiredKillProbe in @('killTrigger', 'playerKillTrigger')) {
        $killProbe = @($fullReport.probes | Where-Object id -eq $requiredKillProbe)
        if ($killProbe.Count -ne 1) { throw "Synthetic release report must contain $requiredKillProbe." }
        $killProbe[0].observations = @($killProbe[0].observations | ForEach-Object {
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
    $skillTriggerProbe = @($fullReport.probes | Where-Object id -eq 'skillTrigger')
    if ($skillTriggerProbe.Count -ne 1) { throw 'Synthetic release report must contain skillTrigger.' }
    $skillTriggerProbe[0].observations = @($skillTriggerProbe[0].observations | ForEach-Object {
        if ($_ -match 'attack=true') { $_ } else { "$_ attack=true" }
    })
    $fullReport.expectedProbeIds += 'importedSignalTrigger'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedSignalTrigger'; status = 'passed'; observations = @('imported-signal-trigger imported=true mapped=true generic=true selector=true payload=true sender=true trigger=true invalid-preserved=true last-signal=true passed=true')
    }
    $fullReport.expectedProbeIds += 'importedTimerTrigger'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedTimerTrigger'; status = 'passed'; observations = @('imported-timer-trigger imported=true mapped=true aligned=true independent=true invalid-preserved=true source=true passed=true')
    }
    $fullReport.expectedProbeIds += 'localDifficultyCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'localDifficultyCondition'; status = 'passed'; observations = @('local-difficulty-condition imported=true positive=true range-positive=true high-rejected=true fallback-positive=true missing-context-rejected=true invalid-preserved=true passed=true')
    }
    $fullReport.expectedProbeIds += 'groundedCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'groundedCondition'; status = 'passed'; observations = @('grounded-condition imported=true primary-mapped=true grounded-alias-mapped=true positive=true alias-positive=true airborne=true negative=true alias-negative=true invalid-preserved=true restored=true passed=true')
    }
    $fullReport.expectedProbeIds += 'templateTypeImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'templateTypeImported'; status = 'passed'; observations = @('template-type-imported result=true imported=true primary-mapped=true alias-mapped=true direct-positive=true transitive-positive=true unrelated-rejected=true control-rejected=true field-inheritance=true exclude-inherited-removed=true exclude-own-values=true missing-config-rejected=true unmanaged-rejected=true invalid-preserved=true passed=true')
    }
    $invulnerableProbe = @($fullReport.probes | Where-Object id -eq 'invulnerableCondition')
    if ($invulnerableProbe.Count -ne 1) { throw 'Synthetic release report must contain invulnerableCondition.' }
    $invulnerableProbe[0].observations = @(
        'skill-actions invulnerable-condition=true invulnerable-aliases=true')
    $targetFluidProbe = @($fullReport.probes | Where-Object id -eq 'targetFluidConditions')
    if ($targetFluidProbe.Count -ne 1) { throw 'Synthetic release report must contain targetFluidConditions.' }
    $targetFluidProbe[0].observations = @(
        'target-fluids-delayed imported=true water=true lava=true invalid-preserved=true restored=true passed=true')
    $fullReport.expectedProbeIds += 'boatOptionsImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'boatOptionsImported'; status = 'passed'; observations = @('boat-options-imported result=true imported=true boat-type=SPRUCE control-type=OAK chest-type=BAMBOO wrong-type-preserved=true invalid-preserved=true conflict-preserved=true')
    }
    $fullReport.expectedProbeIds += 'serverNmsVersionCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'serverNmsVersionCondition'; status = 'passed'; observations = @('server-nms-version-condition imported=true positive=true alias-positive=true scalar-positive=true wrong-rejected=true default-rejected=true invalid-preserved=true passed=true')
    }
    $fullReport.expectedProbeIds += 'spawnReasonCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'spawnReasonCondition'; status = 'passed'; observations = @('spawn-reason-condition-details actual=CUSTOM primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true invalid-preserved=true')
    }
    $fullReport.expectedProbeIds += 'mobOptionsSlimeSplitNatural'
    $fullReport.probes += [pscustomobject]@{
        id = 'mobOptionsSlimeSplitNatural'; status = 'passed'; observations = @('mob-options-slime-split-natural protected-size=4 control-size=4 protected-event=true protected-cancelled=true protected-event-count=4 control-event=true control-cancelled=false control-event-count=3 protected-dead=true control-dead=true protected-children=0 control-children=3 monster-spawning-enabled=true spawn-flags-restored=true damage=api passed=true')
    }
    $fullReport.expectedProbeIds += 'mythicTeleport'
    $fullReport.probes += [pscustomobject]@{
        id = 'mythicTeleport'; status = 'passed'; observations = @('mythic-teleport imported=true entity-target=true location-target=true safe-blocked=true unsafe-bypass=true spread-bounded=true yaw-preserved=true orientation-override=true invalid-preserved=true warnings=true restored=true passed=true')
    }
    $audienceFilterProbe = @($fullReport.probes | Where-Object id -eq 'audience-filter-delivery')
    if ($audienceFilterProbe.Count -ne 1) { throw 'Synthetic release report must contain audience-filter-delivery.' }
    $audienceFilterProbe[0].observations = @('{"permissionNegativeTesterOmitted":true,"permissionNegativeGuestIncluded":true,"factionNegativeTesterIncluded":true,"factionNegativeGuestExcluded":true}')
    $naturalMobOptions = @($fullReport.probes | Where-Object id -eq 'mobOptionsNatural')
    if ($naturalMobOptions.Count -ne 1) { throw 'Synthetic release report must contain mobOptionsNatural.' }
    $naturalMobOptions[0].observations = @($naturalMobOptions[0].observations | ForEach-Object {
        $_ -replace 'bee-stinger=true ', 'bee-stinger=true bee-event=synthetic '
    })
    $fullReport.expectedProbeIds += 'mobOptionsSkeletonConversion'
    $fullReport.probes += [pscustomobject]@{
        id = 'mobOptionsSkeletonConversion'; status = 'passed'; observations = @('mob-options-skeleton-conversion protected-events=1 control-events=1 protected-cancelled=true control-cancelled=false protected-type=SKELETON protected-valid=true control-stray=true control-converted=true elapsed-ticks=21 restored=true passed=true')
    }
    $fullReport.expectedProbeIds += 'mobOptionsZombieConversion'
    $fullReport.probes += [pscustomobject]@{
        id = 'mobOptionsZombieConversion'; status = 'passed'; observations = @('mob-options-zombie-conversion protected-events=1 control-events=1 protected-cancelled=true control-cancelled=false protected-type=ZOMBIE protected-valid=true control-drowned=true control-converted=true elapsed-ticks=21 restored=true passed=true')
    }
    $fullReport.expectedProbeIds += 'mobOptionsZombieVillagerCured'
    $fullReport.probes += [pscustomobject]@{
        id = 'mobOptionsZombieVillagerCured'; status = 'passed'; observations = @('mob-options-zombie-villager-cured protected-events=1 control-events=1 protected-cancelled=true control-cancelled=false protected-type=ZOMBIE_VILLAGER protected-valid=true control-villager=true control-converted=true elapsed-ticks=21 restored=true passed=true')
    }
    $fullReport.expectedProbeIds += 'mobOptionsVillagerInfection'
    $fullReport.probes += [pscustomobject]@{
        id = 'mobOptionsVillagerInfection'; status = 'passed'; observations = @('mob-options-villager-infection protected-events=1 control-events=1 protected-cancelled=true control-cancelled=false protected-type=VILLAGER protected-villager=true protected-alive=false protected-zombie-villager=false control-zombie-villager=true control-converted=true native-attacks=true/true difficulty-restored=true elapsed-ticks=21 restored=true passed=true')
    }
    $entityFlagOptionsProbe = @($fullReport.probes | Where-Object id -eq 'entityFlagOptions')
    if ($entityFlagOptionsProbe.Count -ne 1) { throw 'Synthetic release report must contain entityFlagOptions.' }
    $entityFlagOptionsProbe[0].observations = @('entity-flag-options result=true imported=true wolf-tamed=true wolf-angry=true pig-zombie-angry=true can-join-raid-setter=true can-join-raid=false control-can-join-raid=true patrol-leader=true patrol-spawn-point=true wrong-type-preserved=true')
    $fullReport.expectedProbeIds += 'preventSunburnNatural'
    $fullReport.probes += [pscustomobject]@{
        id = 'preventSunburnNatural'; status = 'passed'; observations = @('prevent-sunburn-natural imported=true mapped=true protected-option=true control-option=false daylight=true weather-clear=true observer-present=true protected-player-distance=7 control-player-distance=15 sky-exposed=true protected-ai=true control-ai=true protected-age-ticks=90 control-age-ticks=110 protected-events=2 protected-cancelled=2 protected-fire-ticks=0 control-events=1 control-cancelled=0 control-fire-ticks=154 elapsed-ticks=40 restored=true passed=true')
    }
    $lookingAtProbe = @($fullReport.probes | Where-Object id -eq 'lookingAtCondition')
    if ($lookingAtProbe.Count -ne 1) { throw 'Synthetic release report must contain lookingAtCondition.' }
    $lookingAtProbe[0].observations = @('imported-looking-at-condition primary-mapped=true alias-mapped=true entity-mapped=true positive=true alias-positive=true wrong-block-rejected=true short-block-distance-rejected=true negative=true entity-positive=true wrong-entity-rejected=true short-entity-distance-rejected=true invalid-rejected=true restored=true')
    $fullReport.expectedProbeIds += 'importedIsChildCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedIsChildCondition'; status = 'passed'; observations = @('skill-actions is-child-import-condition=true adult-parent-positive=true adult-not-baby=true unrelated-baby-not-child=true baby-positive=true')
    }
    $fullReport.expectedProbeIds += 'mythicAuraRuntime'
    $fullReport.probes += [pscustomobject]@{
        id = 'mythicAuraRuntime'; status = 'passed'; observations = @('skill-actions mythic-aura-action=true mythic-has-aura-condition=true mythic-target-has-aura-condition=true mythic-has-aura-stacks-condition=true mythic-has-aura-type-condition=true mythic-aura-remove=true mythic-aura-invalid-rejected=true')
    }
    $fullReport.expectedProbeIds += 'dropItemListImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'dropItemListImported'; status = 'passed'; observations = @('drop-item-list-import valid=true invalid-preserved=true')
    }
    $fullReport.expectedProbeIds += 'importedMessageClient'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedMessageClient'; status = 'passed'; observations = @('imported-message-client dispatched=true client-exact=true')
    }
    $fullReport.expectedProbeIds += 'importedEscapedMessageClient'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedEscapedMessageClient'; status = 'passed'; observations = @('imported-escaped-message-client dispatched=true client-exact=true')
    }
    $fullReport.expectedProbeIds += 'importedSkillReference'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedSkillReference'; status = 'passed'; observations = @('imported-skill-reference valid=true rejected=true preserved=true warnings=true')
    }
    $fullReport.expectedProbeIds += 'importedSkillTiming'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedSkillTiming'; status = 'passed'; observations = @('imported-skill-timing submitted=true before-delay=true first=true repeated=true invalid-rejected=true preserved=true warnings=true duplicate-rejected=true mechanic-preserved=true positional-chance=true positional-condition=true suffix-rejected=true')
    }
    $fullReport.expectedProbeIds += 'unroutedEventSuffix'
    $fullReport.probes += [pscustomobject]@{
        id = 'unroutedEventSuffix'; status = 'passed'; observations = @('unrouted-event-suffix rejected=true warnings=true')
    }
    $fullReport.expectedProbeIds += 'eventSuffixVariants'
    $fullReport.probes += [pscustomobject]@{
        id = 'eventSuffixVariants'; status = 'passed'; observations = @('event-suffix-variants rejected=true warnings=true literal=true')
    }
    $fullReport.expectedProbeIds += 'orphanSuffix'
    $fullReport.probes += [pscustomobject]@{
        id = 'orphanSuffix'; status = 'passed'; observations = @('orphan-suffix preserved=true warnings=true rejected=true unchanged=true')
    }
    $fullReport.expectedProbeIds += 'conditionFailureClosed'
    $fullReport.probes += [pscustomobject]@{
        id = 'conditionFailureClosed'; status = 'passed'; observations = @('condition-failure-closed bound=true blocked=true control=true')
    }
    $fullReport.expectedProbeIds += 'namedConditionFailureClosed'
    $fullReport.probes += [pscustomobject]@{
        id = 'namedConditionFailureClosed'; status = 'passed'; observations = @('named-condition-failure-closed bound=true blocked=true control=true')
    }
    $fullReport.expectedProbeIds += 'namedSkillReload'
    $fullReport.probes += [pscustomobject]@{
        id = 'namedSkillReload'; status = 'passed'; observations = @('named-skill-reload bound=true initial=true blocked=true restored=true')
    }
    $fullReport.expectedProbeIds += 'namedSkillReference'
    $fullReport.probes += [pscustomobject]@{
        id = 'namedSkillReference'; status = 'passed'; observations = @('named-skill-reference forward=true missing-rejected=true cycle-blocked=true')
    }
    $fullReport.expectedProbeIds += 'dropNativeTierRange'
    $fullReport.probes += [pscustomobject]@{
        id = 'dropNativeTierRange'; status = 'passed'; observations = @('drop-native-tier-range valid=true fallback=true warnings=true')
    }
    $fullReport.expectedProbeIds += 'entityAngryPollination'
    $fullReport.probes += [pscustomobject]@{
        id = 'entityAngryPollination'; status = 'passed'; observations = @('entity-angry-pollination result=true bee-angry-positive=true bee-angry-negative=true wolf-angry-positive=true wolf-angry-negative=true vex-angry-positive=true vex-angry-negative=true bee-pollinated-positive=true bee-pollinated-negative=true unsupported-angry-rejected=true missing-context-rejected=true aliases=true')
    }
    $fullReport.expectedProbeIds += 'entityShearedStates'
    $fullReport.probes += [pscustomobject]@{
        id = 'entityShearedStates'; status = 'passed'; observations = @('entity-sheared-states result=true sheep-self-positive=true sheep-target-positive=true sheep-self-negative=true sheep-target-negative=true bogged-self-positive=true bogged-target-positive=true bogged-self-negative=true bogged-target-negative=true snow-golem-self-positive=true snow-golem-target-positive=true snow-golem-self-negative=true snow-golem-target-negative=true unsupported-rejected=true missing-context-rejected=true aliases=true')
    }
    $fullReport.expectedProbeIds += 'inCombatCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'inCombatCondition'; status = 'passed'; observations = @('in-combat-condition managed-path=bukkit-mob-target managed-positive=true managed-no-target-rejected=true managed-restored=true native-positive=true native-no-target-rejected=true native-restored=true')
    }
    $fullReport.expectedProbeIds += 'isInCinematicCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'isInCinematicCondition'; status = 'passed'; observations = @('is-in-cinematic-condition player-open=true player-out-of-range-rejected=true non-player-rejected=true closed=true')
    }
    $fullReport.expectedProbeIds += 'endermanCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'endermanCondition'; status = 'passed'; observations = @('enderman-condition-details positive=true negative=true')
    }
    $fullReport.expectedProbeIds += 'aerialCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'aerialCondition'; status = 'passed'; observations = @('aerial-condition-details positive=true alias-positive=true negative=true ground-entity=player ground=true')
    }
    $fullReport.expectedProbeIds += 'importedAerialCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedAerialCondition'; status = 'passed'; observations = @('imported-aerial-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true ground=true')
    }
    $fullReport.expectedProbeIds += 'importedColorCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedColorCondition'; status = 'passed'; observations = @('imported-color-condition red-mapped=true blue-alias-mapped=true positive=true negative=true entity-color=RED')
    }
    $fullReport.expectedProbeIds += 'importedHasEnchantmentCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedHasEnchantmentCondition'; status = 'passed'; observations = @('imported-has-enchantment-condition primary-mapped=true alias-mapped=true positive=true any-positive=true level-rejected=true type-rejected=true restored=true')
    }
    $fullReport.expectedProbeIds += 'importedTriggerItemTypeCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedTriggerItemTypeCondition'; status = 'passed'; observations = @('imported-trigger-item-type-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true constructed-context=true')
    }
    $fullReport.expectedProbeIds += 'importedTriggerBlockTypeCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedTriggerBlockTypeCondition'; status = 'passed'; observations = @('imported-trigger-block-type-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true state-rejected=true constructed-context=true')
    }
    $fullReport.expectedProbeIds += 'importedOriginLocationCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedOriginLocationCondition'; status = 'passed'; observations = @('imported-origin-location-condition primary-mapped=true alias-mapped=true positive=true exact-positive=true negative=true invalid-rejected=true constructed-context=true')
    }
    $fullReport.expectedProbeIds += 'lineOfSightCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'lineOfSightCondition'; status = 'passed'; observations = @('line-of-sight-condition result=true imported=true aliases=true direct-positive=true direct-blocked=true target-positive=true target-blocked=true target-negative=true restored-positive=true restored=true invalid-preserved=true warning=true')
    }
    $fullReport.expectedProbeIds += 'conditionMobilityStates'
    $fullReport.probes += [pscustomobject]@{
        id = 'conditionMobilityStates'; status = 'passed'; observations = @('condition-mobility-states result=true falling-positive=true falling-negative=true climbing-positive=true climbing-negative=true charged-positive=true charged-negative=true restored=true aliases=true')
    }
    $fullReport.expectedProbeIds += 'damageAttribution'
    $fullReport.probes += [pscustomobject]@{
        id = 'damageAttribution'; status = 'passed'; observations = @('damage-attribution player-present=true player-attack=true target-is-player=true environmental-no-trigger=true restored=true passed=true')
    }
    $fullReport.expectedProbeIds += 'importedMaterialCooldownCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedMaterialCooldownCondition'; status = 'passed'; observations = @('imported-material-cooldown-condition primary-mapped=true alias-mapped=true positive=true wrong-material-rejected=true')
    }
    $fullReport.expectedProbeIds += 'importedItemRechargingCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedItemRechargingCondition'; status = 'passed'; observations = @('imported-item-recharging-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true non-human-rejected=true')
    }
    $fullReport.expectedProbeIds += 'importedItemSimilarityConditions'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedItemSimilarityConditions'; status = 'passed'; observations = @('imported-item-similarity-conditions entity-primary-mapped=true entity-alias-mapped=true entity-positive=true entity-alias-positive=true entity-negative=true entity-invalid-preserved=true inventory-primary-mapped=true inventory-alias-mapped=true inventory-positive=true inventory-alias-positive=true inventory-negative=true inventory-invalid-preserved=true result=true')
    }
    $fullReport.expectedProbeIds += 'importedIsFrozenCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedIsFrozenCondition'; status = 'passed'; observations = @('imported-frozen-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true thawed=true alias-thawed=true invalid-rejected=true')
    }
    $fullReport.expectedProbeIds += 'importedBurningCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedBurningCondition'; status = 'passed'; observations = @('imported-burning-condition actions=true target-priority=true target-positive=true target-negative=true composed-negation=true caster-fallback=true invalid-preserved=true')
    }
    $fullReport.expectedProbeIds += 'importedEntityCategoryCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedEntityCategoryCondition'; status = 'passed'; observations = @('imported-entity-category-condition water-primary-mapped=true water-alias-mapped=true water-positive=true water-zombie-rejected=true zombie-primary-mapped=true zombie-alias-mapped=true zombie-positive=true zombie-cod-rejected=true invalid-preserved=true cod=COD zombie=ZOMBIE')
    }
    $fullReport.expectedProbeIds += 'importedExecutionAudienceCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedExecutionAudienceCondition'; status = 'passed'; observations = @('imported-execution-audience-condition primary-mapped=true alias-mapped=true contains-mapped=true no-audience-player-allowed=true member-allowed=true outsider-rejected=true empty-rejected=true non-player-rejected=true invalid-preserved=true per-target-unattached=true per-target-explicit=true result=true')
    }
    $fullReport.expectedProbeIds += 'executionAudienceMutation'
    $fullReport.probes += [pscustomobject]@{
        id = 'executionAudienceMutation'; status = 'passed'; observations = @('execution-audience-mutation imported=true explicit-empty=true added=true persisted=true member-visible=true outsider-hidden=true removed=true empty-persisted=true unset=true unrestricted=true passed=true')
    }
    $fullReport.expectedProbeIds += 'executionAudienceMobSpawn'
    $fullReport.probes += [pscustomobject]@{
        id = 'executionAudienceMobSpawn'; status = 'passed'; observations = @('execution-audience-mob-spawn imported=true mapped=true spawned=true selected-member=true outsider-excluded=true persisted=true member-visible=true outsider-hidden=true passed=true')
    }
    $fullReport.expectedProbeIds += 'executionAudienceMobMutation'
    $fullReport.probes += [pscustomobject]@{
        id = 'executionAudienceMobMutation'; status = 'passed'; observations = @('execution-audience-mob-mutation imported=true targeter-removed=true empty-persisted=true hidden=true targeter-added=true restored-persisted=true member-visible=true outsider-hidden=true passed=true')
    }
    $fullReport.expectedProbeIds += 'executionAudienceMobSummon'
    $fullReport.probes += [pscustomobject]@{
        id = 'executionAudienceMobSummon'; status = 'passed'; observations = @('execution-audience-mob-summon imported=true action-bound=true spawned=true inherited=true visuals=true member-visible=true outsider-hidden=true passed=true')
    }
    $fullReport.expectedProbeIds += 'importedTargetRelationshipsCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedTargetRelationshipsCondition'; status = 'passed'; observations = @('imported-target-relationships-condition has-owner-mapped=true owner-player-mapped=true owner-online-mapped=true leashed-mapped=true leashed-alias-mapped=true owned-positive=true owner-player-positive=true owner-online-positive=true leashed-positive=true leashed-alias-positive=true leash-cleared-rejected=true owner-cleared-rejected=true missing-target-rejected=true result=true')
    }
    $fullReport.expectedProbeIds += 'contextTargeterRelations'
    $fullReport.probes += [pscustomobject]@{
        id = 'contextTargeterRelations'; status = 'passed'; observations = @('context-targeter-relations passenger=true passengers=true mount=true vehicle=true owner-location=true parent-location=true invalid-rejected=true passed=true')
    }
    $fullReport.expectedProbeIds += 'lastDamagerTargeter'
    $fullReport.probes += [pscustomobject]@{
        id = 'lastDamagerTargeter'; status = 'passed'; observations = @('skill-trigger last-damager-targeter=true last-damager-projectile=true last-damager-environmental-rejected=true last-damager-removed-rejected=true')
    }
    $fullReport.expectedProbeIds += 'targetOfTargetTargeter'
    $fullReport.probes += [pscustomobject]@{
        id = 'targetOfTargetTargeter'; status = 'passed'; observations = @('skill-trigger target-of-target=true target-of-target-aliases=true target-of-target-non-mob-rejected=true target-of-target-missing-rejected=true target-of-target-invalid-rejected=true')
    }
    $fullReport.expectedProbeIds += 'importedPopulationConditions'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedPopulationConditions'; status = 'passed'; observations = @('imported-population-conditions players-online-mapped=true online-alias-mapped=true players-world-mapped=true mobs-world-mapped=true players-online-negative-mapped=true online-alias-negative-mapped=true players-world-negative-mapped=true mobs-world-negative-mapped=true players-online-positive=true online-alias-positive=true players-world-positive=true mobs-world-positive=true players-online-negative=true online-alias-negative=true players-world-negative=true mobs-world-negative=true invalid-preserved=true result=true')
    }
    $fullReport.expectedProbeIds += 'importedMobDefaults'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedMobDefaults'; status = 'passed'; observations = @('imported-mob-defaults result=true imported=true builtin-pickup-disabled=true builtin-no-damage-ticks=true builtin-despawn-normal=true builtin-prevent-leashing=true builtin-prevent-transformation=true global-pickup-disabled=true global-no-damage-ticks=true global-prevent-leashing=true global-default-prevent-transformation=false global-default-ignored=true global-prevent-transformation=true global-despawn-chunk=true global-prevent-other-drops=true global-prevent-vanilla-damage=true global-unsupported-preserved=true explicit-pickup-enabled=true explicit-pickup-disabled=true pickup-wrong-type-preserved=true pickup-invalid-preserved=true explicit-jockey-prevented=true global-jockey-allowed=true explicit-no-damage-ticks=true explicit-prevent-leashing=true explicit-prevent-transformation-disabled=true explicit-despawn-never=true explicit-prevent-other-drops-disabled=true explicit-prevent-vanilla-damage-disabled=true builtin-transform-cancelled=true global-transform-cancelled=true explicit-transform-allowed=true')
    }
    $fullReport.expectedProbeIds += 'playerCreatedImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'playerCreatedImported'; status = 'passed'; observations = @('player-created-imported result=true imported=true created=true control-created=false wrong-entity-preserved=true invalid-preserved=true')
    }
    $fullReport.expectedProbeIds += 'noGravityImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'noGravityImported'; status = 'passed'; observations = @('no-gravity-imported result=true imported=true configured=false control=true has-gravity=false has-gravity-control=true has-gravity-wrong-type-preserved=true invalid-preserved=true gravity-invalid-preserved=true has-gravity-invalid-preserved=true fallback=true')
    }
    $fullReport.expectedProbeIds += 'healOnReloadOptions'
    $fullReport.probes += [pscustomobject]@{
        id = 'healOnReloadOptions'; status = 'passed'; observations = @('heal-on-reload-options result=true mapped=true reload=true protected-option=true control-option=false protected-before=15.0 protected-after=20.0 control-before=15.0 control-after=15.0')
    }
    $fullReport.expectedProbeIds += 'interactableClient'
    $fullReport.probes += [pscustomobject]@{
        id = 'interactableClient'; status = 'passed'; observations = @('interactable-client result=true false-event=true false-cancelled=true true-event=true true-cancelled=false interactions=2')
    }
    $fullReport.expectedProbeIds += 'horseOptionsImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'horseOptionsImported'; status = 'passed'; observations = @('horse-options result=true imported=true color=GRAY style=WHITE_DOTS domestication=5 max-domestication=20 jump-strength=0.8 saddled=true armor=DIAMOND_HORSE_ARMOR control-color=BLACK control-style=BLACK_DOTS control-domestication=1 control-max-domestication=12 control-jump-strength=0.4 control-saddled=false control-armor=AIR donkey-chest=true control-donkey-chest=false pig-saddled=true control-pig-saddled=false strider-saddled=true control-strider-saddled=false')
    }
    $fullReport.expectedProbeIds += 'interactionOptionsImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'interactionOptionsImported'; status = 'passed'; observations = @('interaction-options-imported result=true imported=true configured-height=2.5 configured-width=3.25 responsive=false height-only-width=1.75 defaults-applied=true wrong-type-preserved=true invalid-preserved=true')
    }
    $fullReport.expectedProbeIds += 'goatOptionsImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'goatOptionsImported'; status = 'passed'; observations = @('goat-options result=true imported=true screaming=true control-screaming=false default-screaming=false wrong-type-preserved=true invalid-preserved=true')
    }
    $fullReport.expectedProbeIds += 'tameableOptionsImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'tameableOptionsImported'; status = 'passed'; observations = @('tameable-options result=true imported=true allow-cancelled=false deny-cancelled=true default-cancelled=true invalid-cancelled=true unmanaged-cancelled=false wrong-type-preserved=true invalid-preserved=true global-default-ignored=true')
    }
    $fullReport.expectedProbeIds += 'foxOptionsImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'foxOptionsImported'; status = 'passed'; observations = @('fox-options result=true imported=true type=SNOW control-type=RED wrong-type-preserved=true invalid-preserved=true')
    }
    $fullReport.expectedProbeIds += 'rabbitOptionsImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'rabbitOptionsImported'; status = 'passed'; observations = @('rabbit-options result=true imported=true type=SALT_AND_PEPPER control-type=BROWN killer-type=THE_KILLER_BUNNY angry-type=THE_KILLER_BUNNY false-killer-type=SALT_AND_PEPPER wrong-type-preserved=true invalid-preserved=true conflict-preserved=true')
    }
    $fullReport.expectedProbeIds += 'fallingBlockOptionsImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'fallingBlockOptionsImported'; status = 'passed'; observations = @('falling-block-options-imported result=true imported=true material=OBSIDIAN control-material=SAND drop-item=false hurt-entities=true cancel-drop=true wrong-type-preserved=true invalid-preserved=true')
    }
    $fullReport.expectedProbeIds += 'beeOptionsImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'beeOptionsImported'; status = 'passed'; observations = @('bee-options-imported result=true imported=true nectar=true stung=false control-nectar=false control-stung=true')
    }
    $fullReport.expectedProbeIds += 'beeAngerImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'beeAngerImported'; status = 'passed'; observations = @('bee-anger-imported result=true imported=true configured-anger=1200 control-anger=0')
    }
    $fullReport.expectedProbeIds += 'beeStingerLossImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'beeStingerLossImported'; status = 'passed'; observations = @('bee-stinger-loss-imported result=true imported=true target-entity=ZOMBIE target-ai=false target-invulnerable=false target-resistance-amplifier=3 protected-option=true control-option=false protected-attacks=1 control-attacks=1 protected-events=1 control-events=1 protected-cancelled=0 control-cancelled=0 protected-restored=true control-retained=true protected-near=true control-near=true trigger=entity-target-api event=native-ai')
    }
    $fullReport.expectedProbeIds += 'mythicSpawnerChanceImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'mythicSpawnerChanceImported'; status = 'passed'; observations = @('mythic-spawner-chance imported=true valid-mapped=true invalid-mapped=true valid-natural=true invalid-rejected=true warning=true valid-spawned=1 invalid-spawned=0 passed=true')
    }
    $fullReport.expectedProbeIds += 'mythicSpawnerGroupSizeImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'mythicSpawnerGroupSizeImported'; status = 'passed'; observations = @('mythic-spawner-group-size imported=true mapped=true natural=true requested=3 spawned=2 alive=2 max-alive=2 passed=true')
    }
    $fullReport.expectedProbeIds += 'mythicSpawnerConditionsImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'mythicSpawnerConditionsImported'; status = 'passed'; observations = @('mythic-spawner-conditions imported=true mapped=true world-mapped=true biome-mapped=true biome-natural=true biome-rejected=true biome-pass-spawned=1 biome-reject-spawned=0 world-natural=true world-rejected=true rain=true thunder=false natural=true rejected=true pass-spawned=1 reject-spawned=0 world-pass-spawned=1 world-reject-spawned=0 instance=true restored=true passed=true')
    }
    $fullReport.expectedProbeIds += 'mythicSpawnerRandomPoolImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'mythicSpawnerRandomPoolImported'; status = 'passed'; observations = @('mythic-spawner-random-pool imported=true mapped=true sampled=true expected-emberling=0.250000 expected-emberling_boss=0.750000 observed-emberling=0.240000 observed-emberling_boss=0.760000 max-abs-deviation=0.010000 samples=100 passed=true')
    }
    $fullReport.expectedProbeIds += 'mythicSpawnerRandomPoolDropTables'
    $fullReport.probes += [pscustomobject]@{
        id = 'mythicSpawnerRandomPoolDropTables'; status = 'passed'; observations = @('mythic-spawner-drop-table imported=true references=true weighted=true tables-loaded=true spawned=32 variants=true assigned=true common-table-reloaded=true rare-table-reloaded=true common-alive=true rare-alive=true common-natural=true rare-natural=true common-bread=1 common-diamond=0 rare-bread=0 rare-diamond=1 restored=true passed=true')
    }
    $fullReport.expectedProbeIds += 'mythicStatsBias'
    $fullReport.probes += [pscustomobject]@{
        id = 'mythicStatsBias'; status = 'passed'; observations = @('mythic-stats result=true default=true base=0.0 killer-priority=true unknown-rejected=true disabled-rejected=true positive-weighted=true positive-rare=684 negative-weighted=true unknown-fail-closed=true restored=true dropper-removed=true')
    }
    $fullReport.expectedProbeIds += 'importedEntityTypeVariantCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedEntityTypeVariantCondition'; status = 'passed'; observations = @('imported-entity-type-variant-condition primary-mapped=true alias-mapped=true mobtype-mapped=true positive=true alias-positive=true mobtype-positive=true variant-alternative=true wrong-variant-rejected=true wrong-type-rejected=true invalid-preserved=true cat=CAT variant=TABBY')
    }
    $fullReport.expectedProbeIds += 'importedEntityTypeTraitsCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedEntityTypeTraitsCondition'; status = 'passed'; observations = @('imported-entity-type-traits mapped=true horse=true wrong-style-rejected=true panda=true villager=true zombie-villager=true rainbow-sheep=true rainbow-sheep-white-rejected=true shulker=true parrot=true llama=true tropical-fish=true iron-golem-crack=true inapplicable-trait-rejected=true')
    }
    $fullReport.expectedProbeIds += 'importedSpyglassCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedSpyglassCondition'; status = 'passed'; observations = @('imported-spyglass-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true inactive-rejected=true non-player-rejected=true invalid-rejected=true')
    }
    $fullReport.expectedProbeIds += 'importedSurvivalModeCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedSurvivalModeCondition'; status = 'passed'; observations = @('imported-survival-mode-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true negative=true alias-negative=true non-player-rejected=true invalid-rejected=true')
    }
    $fullReport.expectedProbeIds += 'importedVariableConditions'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedVariableConditions'; status = 'passed'; observations = @('imported-variable-conditions result=true primary-mapped=true aliases=true scopes=true ranges=true missing-rejected=true wrong-type-rejected=true quoted-value=true dynamic-placeholder=true invalid-preserved=true')
    }
    $fullReport.expectedProbeIds += 'importedFreeInventorySlotCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedFreeInventorySlotCondition'; status = 'passed'; observations = @('imported-free-inventory-slot primary-mapped=true alias-mapped=true positive=true negative=true invalid-preserved=true result=true')
    }
    $fullReport.expectedProbeIds += 'importedNameCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedNameCondition'; status = 'passed'; observations = @('imported-name-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true default-name-rejected=true fallback=true custom-name-rejected=true invalid-preserved=true result=true')
    }
    $fullReport.expectedProbeIds += 'importedServerConditions'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedServerConditions'; status = 'passed'; observations = @('imported-server-conditions paper-primary=true paper-alias=true exact=true exact-negative=true after-inclusive=true after-exclusive-rejected=true after-higher-rejected=true before-inclusive=true before-exclusive-rejected=true invalid-preserved=true result=true')
    }
    $fullReport.expectedProbeIds += 'importedCoordinateDifferenceCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedCoordinateDifferenceCondition'; status = 'passed'; observations = @('imported-coordinate-difference-condition primary-mapped=true alias-mapped=true x-positive=true y-positive=true z-positive=true out-of-range-rejected=true invalid-preserved=true result=true')
    }
    $fullReport.expectedProbeIds += 'snowingCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'snowingCondition'; status = 'passed'; observations = @('snowing-condition result=true target-location-isolated=true target-positive=true alias-positive=true warm-rejected=true clear-rejected=true missing-context-rejected=true invalid-arguments-rejected=true restored=true')
    }
    $fullReport.expectedProbeIds += 'insideOutsideCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'insideOutsideCondition'; status = 'passed'; observations = @('inside-outside-condition-details inside-positive=true inside-negative=true outside-positive=true outside-negative=true restored=true')
    }
    $fullReport.expectedProbeIds += 'spawningDistanceClientWalk'
    $fullReport.probes += [pscustomobject]@{
        id = 'spawningDistanceClientWalk'; status = 'passed'; observations = @('spawning-distance-client-walk result=true player-displacement=14.25 player-entity-distance=12.25 instance-removed=true entity-removed=true despawn-elapsed-ticks=3 decision-period-ticks=10')
    }
    $fullReport.expectedProbeIds += 'targeterIdentityFilter'
    $fullReport.probes += [pscustomobject]@{
        id = 'targeterIdentityFilter'; status = 'passed'; observations = @('targeter-identity-filter managed=true target-mythic=true target-nonmythic=true ignore-mythic=true ignore-vanilla=true targetvanilla=true targetnonmythic=true targetnonmythic-false=true duplicate-rejected=true owner-target=true owner-target-disabled=true owner-ignore=true owner-priority=true faction-target=true faction-ignore=true same-faction=true same-faction-disabled=true faction-priority=true same-faction-world=true targetall-relations=true relation-options-rejected=true passed=true')
    }
    $ageOptionsProbe = @($fullReport.probes | Where-Object id -eq 'ageOptions')
    if ($ageOptionsProbe.Count -ne 1) { throw 'Synthetic release report must contain ageOptions.' }
    $ageOptionsProbe[0].observations = @(
        'age-options result=true age=-24000 baby=true adult=true age-lock=true zombie-baby=true')
    $creeperOptionsProbe = @($fullReport.probes | Where-Object id -eq 'creeper')
    if ($creeperOptionsProbe.Count -ne 1) { throw 'Synthetic release report must contain creeper.' }
    $creeperOptionsProbe[0].observations = @(
        'creeper-options result=true imported=true explosion-radius=5 fuse-ticks=60 super-charged=true prevent-suicide=true protected-cancelled=true control-allowed=true')
    $catOptionsProbe = @($fullReport.probes | Where-Object id -eq 'cat')
    if ($catOptionsProbe.Count -ne 1) { throw 'Synthetic release report must contain cat.' }
    $catOptionsProbe[0].observations = @(
        'cat-options result=true imported=true type=SIAMESE sitting=true collar=BLUE control-type=TABBY control-sitting=false control-collar=RED')
    $sheepOptionsProbe = @($fullReport.probes | Where-Object id -eq 'sheep')
    if ($sheepOptionsProbe.Count -ne 1) { throw 'Synthetic release report must contain sheep.' }
    $sheepOptionsProbe[0].observations = @(
        'sheep-options result=true imported=true color=RED sheared=true control-color=WHITE control-sheared=false')
    $entitySizeRangeProbe = @($fullReport.probes | Where-Object id -eq 'entitySizeRange')
    if ($entitySizeRangeProbe.Count -ne 1) { throw 'Synthetic release report must contain entitySizeRange.' }
    $entitySizeRangeProbe[0].observations = @(
        'entity-size-range result=true samples=64 configured=2..5 slime=2..5 magma-cube=2..5 phantom=2..5 imported=true')
    $fullReport.expectedProbeIds += 'reinforcementsChanceOptions'
    $fullReport.probes += [pscustomobject]@{
        id = 'reinforcementsChanceOptions'; status = 'passed'; observations = @('reinforcements-chance-options result=true imported=true applied=true base=0.38 zero-mapped=true upper-bound-mapped=true wrong-type-preserved=true invalid-preserved=true wrong-entity-rejected=true invalid-runtime-rejected=true')
    }
    $fullReport.expectedProbeIds += 'armorToughnessStatsImported'
    $fullReport.probes += [pscustomobject]@{
        id = 'armorToughnessStatsImported'; status = 'passed'; observations = @('armor-toughness-stats-imported result=true imported=true base=4.0 attack-damage=12.0 movement-speed=0.4 armor=8.0 knockback-resistance=0.25 follow-range=36.0 unsupported-preserved=true modifier-preserved=true root-preserved=true controls=0.0,0.0')
    }
    $aiSelectorCore = @($fullReport.probes | Where-Object id -eq 'aiSelectorCore')
    if ($aiSelectorCore.Count -ne 1) { throw 'Synthetic release report must contain aiSelectorCore.' }
    $aiSelectorCore[0].observations = @($aiSelectorCore[0].observations) +
        @('ai-selector-core targeter-syntax=true')
    $aiRoute = @($fullReport.probes | Where-Object id -eq 'aiRoute')
    if ($aiRoute.Count -ne 1) { throw 'Synthetic release report must contain aiRoute.' }
    $aiRoute[0].observations = @('ai-route-probe token=followpath active-before=true moving=true advanced=true waypoint-before=route=followpath waypoint=103,64,0 direction=forward speed=1.0 tolerance=3.0 finished=false waypoint-after=route=followpath waypoint=105,64,0 direction=forward speed=1.0 tolerance=3.0 finished=false patrol-mutation=true patrol-before=route=patrol waypoint=103,64,0 direction=forward speed=1.0 tolerance=0.5 finished=false patrol-after=route=patrol waypoint=105,64,0 direction=forward speed=1.0 tolerance=0.5 finished=false patrol-loop=route=patrol waypoint=103,64,0 direction=backward speed=1.0 tolerance=0.5 finished=false passed=true')
    $targeterArgumentValidation = @($fullReport.probes | Where-Object id -eq 'targeterArgumentValidation')
    if ($targeterArgumentValidation.Count -ne 1) {
        throw 'Synthetic release report must contain targeterArgumentValidation.'
    }
    $targeterArgumentValidation[0].observations = @(
        'targeter-argument-validation rejected=true chain=true control=true target-players=true ignore-players=true target-players-specific=true target-players-disabled=true invalid-filter=true target-ground=true ignore-ground=true target-water=true ignore-water=true target-flying=true ignore-flying=true target-invulnerable-disabled=true target-invulnerable-priority=true target-armorstands-disabled=true target-armorstands-priority=true target-animals-disabled=true target-animals-priority=true target-creatures-disabled=true target-creatures-priority=true target-villagers-disabled=true target-villagers-priority=true target-all=true target-creative=true ignore-creative=true target-creative-priority=true target-creative-disabled=true target-spectator=true ignore-spectator=true target-spectator-priority=true target-spectator-disabled=true')
    $locationTargeterProbe = @($fullReport.probes | Where-Object id -eq 'locationTargeterDispatch')
    if ($locationTargeterProbe.Count -ne 1) { throw 'Synthetic release report must contain locationTargeterDispatch.' }
    $locationTargeterProbe[0].observations = @(
        'location-targeter-dispatch valid=true invalid-rejected=true self-aliases=true self-eye=true caster-spawn=true spawn=true target-aliases=true trigger=true forward=true projectile-forward=true predicted=true',
        'tracked-location-parity action=true targeter=true distance=true missing-rejected=true')
    $fullReport.expectedProbeIds += 'absorptionPlayerCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'absorptionPlayerCondition'; status = 'passed'; observations = @('absorption-player-condition imported=true dispatched=true positive=true exact=true negative=true observed=true restored=true passed=true')
    }
    $fullReport.expectedProbeIds += 'radiusConditions'
    $fullReport.probes += [pscustomobject]@{
        id = 'radiusConditions'; status = 'passed'; observations = @('radius-condition-details imported=true players-positive=true players-negative=true entities-positive=true entities-negative=true mobs-positive=true mobs-negative=true living-positive=true living-negative=true player-within-positive=true player-within-negative=true player-not-within-positive=true player-not-within-negative=true restored=true passed=true')
    }
    $fullReport.expectedProbeIds += 'importedPlayerKillsCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedPlayerKillsCondition'; status = 'passed'; observations = @('imported-player-kills-condition primary-mapped=true range-mapped=true positive=true negative=true invalid-preserved=true')
    }
    $fullReport.expectedProbeIds += 'legacyNumericItems'
    $fullReport.probes += [pscustomobject]@{
        id = 'legacyNumericItems'; status = 'passed'; observations = @('legacy-numeric-items imported=true items-material=true items-durability=true items-potion=true items-block-data=true items-amount=true equipment-material=true equipment-durability=true equipment-potion=true equipment-block-data=true equipment-amount=true invalid-preserved=true result=true')
    }
    $fullReport.expectedProbeIds += 'itemMetadata'
    $fullReport.probes += [pscustomobject]@{
        id = 'itemMetadata'; status = 'passed'; observations = @('item-metadata imported=true display-name=true item-name=true lore=true enchantment=true custom-model-data=true flags=true flag-values=[HIDE_ENCHANTS] restrictions=true potion=true skull=true invalid-preserved=true result=true')
    }
    $fullReport.expectedProbeIds += 'attributesComposite'
    $fullReport.probes += [pscustomobject]@{
        id = 'attributesComposite'; status = 'passed'; observations = @('attribute-options-composite result=true attack=true speed=true armor=true dedup=true invalid-rejected=true')
    }
    $fullReport.expectedProbeIds += 'itemAttributesComposite'
    $fullReport.probes += [pscustomobject]@{
        id = 'itemAttributesComposite'; status = 'passed'; observations = @('item-attribute-options-composite result=true attack=true speed=true armor=true dedup=true invalid-rejected=true modifiers=3')
    }
    $dropItemOwnerProbe = @($fullReport.probes | Where-Object id -eq 'dropItemImported')
    if ($dropItemOwnerProbe.Count -ne 1) { throw 'Synthetic release report must contain dropItemImported.' }
    $dropItemOwnerProbe[0].observations = @(
        'drop-item-import valid=true missing-rejected=true invalid-preserved=true owner=true target-owner=true')
    $healthImportedProbe = @($fullReport.probes | Where-Object id -eq 'healthImportedCondition')
    if ($healthImportedProbe.Count -ne 1) { throw 'Synthetic release report must contain healthImportedCondition.' }
    $healthImportedProbe[0].observations = @(
        'health-imported result=true comparisons=true duplicate-rejected=true overflow-preserved=true invalid-preserved=true')
    $latestProbeCount = $fullReport.probes.Count
    $fullReport.fixtureDirectory = 'srv-paper-integration'
    $testJarPath = Join-Path $temporaryRoot 'release.jar'
    [System.IO.File]::WriteAllText($testJarPath, 'test release artifact')
    $testPluginHash = (Get-FileHash -LiteralPath $testJarPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $fullReport.pluginSha256 = $testPluginHash
    [System.IO.File]::WriteAllText($archivePath, ($fullReport | ConvertTo-Json -Depth 20))
    $testReportHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $historicalReport = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'reports/paper-1.21.1-orb-detail-20261102.json') -Raw -Encoding utf8 | ConvertFrom-Json
    $historicalReport.fixtureDirectory = 'srv-paper-historical-release'
    $historicalReport.pluginSha256 = $testPluginHash
    [System.IO.File]::WriteAllText((Join-Path $temporaryRoot 'tools/paper-integration/reports/historical.json'),
        ($historicalReport | ConvertTo-Json -Depth 20))
    $manifestPath = Join-Path $temporaryRoot 'tools/paper-integration/release-evidence.json'
    $testLogPath = Join-Path $temporaryRoot 'tools/paper-integration/reports/test.log.gz'
    [System.IO.File]::WriteAllText($testLogPath, 'test log')
    $testLogHash = (Get-FileHash -LiteralPath $testLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText($manifestPath, (@(@{
        fixtureId = 'srv-paper-current-release'
        reportFixtureDirectory = 'srv-paper-integration'
        report = 'tools/paper-integration/reports/test-release.json'
        reportSha256 = $testReportHash
        fullReleaseCatalog = $true
        pluginSha256 = $testPluginHash
        log = 'tools/paper-integration/reports/test.log.gz'
        logSha256 = $testLogHash
        clientLog = 'tools/paper-integration/reports/test.log.gz'
        clientLogSha256 = $testLogHash
    }, @{
        fixtureId = 'srv-paper-historical-release'
        report = 'tools/paper-integration/reports/historical.json'
        fullReleaseCatalog = $true
        pluginSha256 = $testPluginHash
    }) | ConvertTo-Json -Depth 4))
    $versionMatrixPath = Join-Path $temporaryRoot 'tools/paper-integration/version-matrix.json'
    [System.IO.File]::WriteAllText($versionMatrixPath, (@{
        schemaVersion = 1
        releasePolicy = @{
            requiresExactServerBuild = $true
            requiresMatchingPluginSha256 = $true
            unlistedCombinationsAreUnverified = $true
        }
        combinations = @(
            @{
                id = 'paper-1.21.1-133-minecraft-1.21.1'
                serverFamily = 'Paper'
                serverVersion = '1.21.1-133'
                minecraftVersion = '1.21.1'
                evidenceLevel = 'strict'
                releaseValidated = $true
                releaseFixtureId = 'srv-paper-current-release'
                releaseReport = 'tools/paper-integration/reports/test-release.json'
            },
            @{
                id = 'paper-1.21.4-minecraft-1.21.4'
                serverFamily = 'Paper'
                serverVersion = '1.21.4'
                minecraftVersion = '1.21.4'
                evidenceLevel = 'exploratory'
                releaseValidated = $false
                releaseFixtureId = $null
                releaseReport = $null
            }
        )
    } | ConvertTo-Json -Depth 6))

    $readmePath = Join-Path $temporaryRoot 'README.md'
    [System.IO.File]::WriteAllText($readmePath, [regex]::Replace(
        [System.IO.File]::ReadAllText($readmePath),
        'Latest strict report has \d+ probes, zero incomplete',
        "Latest strict report has $latestProbeCount probes, zero incomplete"))
    $auditScript = Join-Path $PSScriptRoot 'audit-evidence.ps1'
    $output = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Default audit failed unexpectedly: $output"
    }
    if ($output -notmatch 'Fixture report files: 1' -or
            $output -notmatch 'Directories without report files: 1' -or
            $output -notmatch 'Missing fixture directories: 1') {
        throw "Report, directory-only, and missing artifacts were not distinguished: $output"
    }
    if ($output -notmatch 'Historical fixture references without local reports remain informational' -or
            $output -notmatch 'Archived release reports: 2') {
        throw "Historical inventory and current release evidence were not distinguished: $output"
    }
    if ($output -notmatch 'srv-paper-old \(tools/paper-integration/STARTUP_EVIDENCE.md:4\)') {
        throw "Missing fixture source location was not reported: $output"
    }
    $invalidReportHashManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $invalidReportHashManifest[0].reportSha256 = 'b' * 64
    [System.IO.File]::WriteAllText($manifestPath, ($invalidReportHashManifest | ConvertTo-Json -Depth 4))
    $invalidReportHashOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $invalidReportHashOutput -notmatch 'Release report SHA-256 mismatch') {
        throw "Modified release report hash was not rejected: $invalidReportHashOutput"
    }
    $invalidReportHashManifest[0].PSObject.Properties.Remove('reportSha256')
    $invalidReportHashManifest[0].PSObject.Properties.Remove('reportFixtureDirectory')
    [System.IO.File]::WriteAllText($manifestPath, ($invalidReportHashManifest | ConvertTo-Json -Depth 4))
    $fullReport.fixtureDirectory = 'srv-paper-current-release'
    [System.IO.File]::WriteAllText($archivePath, ($fullReport | ConvertTo-Json -Depth 20))
    if ($output -match 'srv-1(?:\s|$)') {
        throw "Paper jar directory was treated as an evidence fixture: $output"
    }

    $originalReadme = [System.IO.File]::ReadAllText($readmePath)
    [System.IO.File]::WriteAllText($readmePath, $originalReadme.Replace(
        "Latest strict report has $latestProbeCount probes", 'Latest strict report has 290 probes'))
    $staleProbeOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $staleProbeOutput -notmatch 'README latest strict report probe count does not match') {
        throw "Stale latest report probe count was not rejected: $staleProbeOutput"
    }
    [System.IO.File]::WriteAllText($readmePath, $originalReadme)
    [System.IO.File]::WriteAllText($readmePath,
        'The matrix currently has `1`' + [Environment]::NewLine +
            '`mapped-unverified` rows.' + [Environment]::NewLine +
            "Latest strict report has $latestProbeCount probes, zero incomplete" + [Environment]::NewLine +
                    'Current fixture inventory: 4 cited IDs; 2 report files; 1 directories without reports; 1 missing directories; 2 archived release reports.' + [Environment]::NewLine)
    $staleOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $staleOutput -notmatch 'README fixture inventory is stale') {
        throw "Stale README inventory was not rejected: $staleOutput"
    }
    [System.IO.File]::WriteAllText($readmePath,
        'The matrix currently has `1`' + [Environment]::NewLine +
            '`mapped-unverified` rows.' + [Environment]::NewLine +
            "Latest strict report has $latestProbeCount probes, zero incomplete" + [Environment]::NewLine +
                    'Current fixture inventory: 3 cited IDs; 1 report files; 1 directories without reports; 1 missing directories; 2 archived release reports.' + [Environment]::NewLine)

    [System.IO.File]::WriteAllText($readmePath,
        'The matrix currently has `2`' + [Environment]::NewLine +
            '`mapped-unverified` rows.' + [Environment]::NewLine +
            "Latest strict report has $latestProbeCount probes, zero incomplete" + [Environment]::NewLine +
                    'Current fixture inventory: 3 cited IDs; 1 report files; 1 directories without reports; 1 missing directories; 2 archived release reports.' + [Environment]::NewLine)
    $staleStatusOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $staleStatusOutput -notmatch 'README mapped-unverified row count is stale') {
        throw "Stale matrix status count was not rejected: $staleStatusOutput"
    }
    [System.IO.File]::WriteAllText($readmePath,
        'The matrix currently has `1`' + [Environment]::NewLine +
            '`mapped-unverified` rows.' + [Environment]::NewLine +
            "Latest strict report has $latestProbeCount probes, zero incomplete" + [Environment]::NewLine +
                    'Current fixture inventory: 3 cited IDs; 1 report files; 1 directories without reports; 1 missing directories; 2 archived release reports.' + [Environment]::NewLine)

    $requiredOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -RequirePresent 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $requiredOutput -notmatch 'Required release evidence audit passed') {
        throw "-RequirePresent rejected a complete current release manifest: $requiredOutput"
    }
    $matchingJarOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -RequirePresent -ReleasePluginJar $testJarPath 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw "Matching release JAR was rejected: $matchingJarOutput"
    }
    $missingGateJarOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -ReleaseGate 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $missingGateJarOutput -notmatch 'Release gate requires -ReleasePluginJar') {
        throw "Release gate accepted a missing JAR: $missingGateJarOutput"
    }
    $gateOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -ReleaseGate -ReleasePluginJar $testJarPath 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $gateOutput -notmatch 'Release gate passed for the specified plugin JAR') {
        throw "Release gate rejected matching complete evidence: $gateOutput"
    }
    $wrongBuildEvidence = Get-Content -LiteralPath $archivePath -Raw -Encoding utf8 | ConvertFrom-Json
    $wrongBuildEvidence.paperVersion = '1.21.1-134-test'
    [System.IO.File]::WriteAllText($archivePath, ($wrongBuildEvidence | ConvertTo-Json -Depth 20))
    $wrongBuildManifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding utf8 | ConvertFrom-Json
    [System.IO.File]::WriteAllText($manifestPath, ($wrongBuildManifest | ConvertTo-Json -Depth 4))
    $wrongBuildAudit = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -ReleaseGate -ReleasePluginJar $testJarPath 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $wrongBuildAudit -notmatch 'Paper implementation build mismatch') {
        throw "Release audit accepted a report with the wrong Paper build: $wrongBuildAudit"
    }
    [System.IO.File]::WriteAllText($archivePath, ($fullReport | ConvertTo-Json -Depth 20))
    [System.IO.File]::WriteAllText($manifestPath, ($wrongBuildManifest | ConvertTo-Json -Depth 4))
    $missingTargeterEvidence = Get-Content -LiteralPath $archivePath -Raw -Encoding utf8 | ConvertFrom-Json
    $missingTargeterEvidenceProbe = @($missingTargeterEvidence.probes | Where-Object id -eq 'aiSelectorCore')
    $missingTargeterEvidenceProbe[0].observations = @($missingTargeterEvidenceProbe[0].observations |
        Where-Object { -not ([string]$_).Contains('targeter-syntax=true') })
    [System.IO.File]::WriteAllText($archivePath, ($missingTargeterEvidence | ConvertTo-Json -Depth 20))
    $missingTargeterOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -ReleaseGate -ReleasePluginJar $testJarPath 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $missingTargeterOutput -notmatch 'lacks required observation for aiSelectorCore' -or
            $missingTargeterOutput -notmatch 'targeter-syntax=true') {
        throw "Release gate accepted aiSelectorCore without targeter syntax evidence: $missingTargeterOutput"
    }
    [System.IO.File]::WriteAllText($archivePath, ($fullReport | ConvertTo-Json -Depth 20))
    $missingIdentityEvidence = Get-Content -LiteralPath $archivePath -Raw -Encoding utf8 | ConvertFrom-Json
    $missingIdentityEvidenceProbe = @($missingIdentityEvidence.probes | Where-Object id -eq 'targeterIdentityFilter')
    $missingIdentityEvidenceProbe[0].observations = @($missingIdentityEvidenceProbe[0].observations |
        Where-Object { -not ([string]$_).Contains('targeter-identity-filter') })
    [System.IO.File]::WriteAllText($archivePath, ($missingIdentityEvidence | ConvertTo-Json -Depth 20))
    $missingIdentityOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -ReleaseGate -ReleasePluginJar $testJarPath 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $missingIdentityOutput -notmatch 'targeterIdentityFilter' -or
            $missingIdentityOutput -notmatch 'targeter-identity-filter') {
        throw "Release gate accepted missing targeter identity evidence: $missingIdentityOutput"
    }
    [System.IO.File]::WriteAllText($archivePath, ($fullReport | ConvertTo-Json -Depth 20))
    $missingDropTableEvidence = Get-Content -LiteralPath $archivePath -Raw -Encoding utf8 | ConvertFrom-Json
    $missingDropTableEvidenceProbe = @($missingDropTableEvidence.probes | Where-Object id -eq 'mythicSpawnerRandomPoolDropTables')
    $missingDropTableEvidenceProbe[0].observations = @('mythic-spawner-drop-table imported=true references=true weighted=true tables-loaded=true spawned=32 variants=true assigned=true common-table-reloaded=true rare-table-reloaded=true common-alive=true rare-alive=true common-natural=true rare-natural=true common-bread=1 common-diamond=0 rare-bread=0 rare-diamond=1 restored=false passed=false')
    [System.IO.File]::WriteAllText($archivePath, ($missingDropTableEvidence | ConvertTo-Json -Depth 20))
    $missingDropTableOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -ReleaseGate -ReleasePluginJar $testJarPath 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $missingDropTableOutput -notmatch 'mythicSpawnerRandomPoolDropTables' -or
            $missingDropTableOutput -notmatch 'restored=true') {
        throw "Release gate accepted Mythic random-pool drops without restoration evidence: $missingDropTableOutput"
    }
    [System.IO.File]::WriteAllText($archivePath, ($fullReport | ConvertTo-Json -Depth 20))
    $missingStatsEvidence = Get-Content -LiteralPath $archivePath -Raw -Encoding utf8 | ConvertFrom-Json
    $missingStatsProbe = @($missingStatsEvidence.probes | Where-Object id -eq 'mythicStatsBias')
    $missingStatsProbe[0].observations = @('mythic-stats result=true default=true base=0.0 killer-priority=true unknown-rejected=true disabled-rejected=true positive-weighted=true positive-rare=684 negative-weighted=true unknown-fail-closed=true restored=false dropper-removed=true')
    [System.IO.File]::WriteAllText($archivePath, ($missingStatsEvidence | ConvertTo-Json -Depth 20))
    $missingStatsOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -ReleaseGate -ReleasePluginJar $testJarPath 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $missingStatsOutput -notmatch 'mythicStatsBias' -or
            $missingStatsOutput -notmatch 'restored=true') {
        throw "Release gate accepted incomplete native Mythic stats evidence: $missingStatsOutput"
    }
    [System.IO.File]::WriteAllText($archivePath, ($fullReport | ConvertTo-Json -Depth 20))
    [System.IO.File]::WriteAllText($testLogPath, 'modified log')
    $modifiedLogOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -RequirePresent 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $modifiedLogOutput -notmatch 'Release log SHA-256 mismatch') {
        throw "Modified release log was not rejected: $modifiedLogOutput"
    }
    [System.IO.File]::WriteAllText($testLogPath, 'test log')
    $invalidClientManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $invalidClientManifest[0].clientLogSha256 = 'b' * 64
    [System.IO.File]::WriteAllText($manifestPath, ($invalidClientManifest | ConvertTo-Json -Depth 4))
    $invalidClientOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -RequirePresent 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $invalidClientOutput -notmatch 'Release clientLog SHA-256 mismatch') {
        throw "Modified client log hash was not rejected: $invalidClientOutput"
    }
    $invalidClientManifest[0].clientLogSha256 = $testLogHash
    [System.IO.File]::WriteAllText($manifestPath, ($invalidClientManifest | ConvertTo-Json -Depth 4))
    foreach ($missingKind in @('log', 'clientLog')) {
        $missingLogManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        $missingLogManifest[0].PSObject.Properties.Remove($missingKind)
        $missingLogManifest[0].PSObject.Properties.Remove($missingKind + 'Sha256')
        [System.IO.File]::WriteAllText($manifestPath, ($missingLogManifest | ConvertTo-Json -Depth 4))
        $missingLogOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -RequirePresent 2>&1 | Out-String)
        if ($LASTEXITCODE -eq 0 -or $missingLogOutput -notmatch "requires $missingKind and its SHA-256") {
            throw "Missing $missingKind was not rejected: $missingLogOutput"
        }
        [System.IO.File]::WriteAllText($manifestPath, ($invalidClientManifest | ConvertTo-Json -Depth 4))
    }
    [System.IO.File]::WriteAllText($testJarPath, 'different artifact')
    $differentJarOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -RequirePresent -ReleasePluginJar $testJarPath 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $differentJarOutput -notmatch 'does not match release JAR') {
        throw "Mismatched release JAR was not rejected: $differentJarOutput"
    }
    $differentGateJarOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -ReleaseGate -ReleasePluginJar $testJarPath 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $differentGateJarOutput -notmatch 'does not match release JAR') {
        throw "Release gate accepted a mismatched JAR: $differentGateJarOutput"
    }
    [System.IO.File]::WriteAllText($manifestPath, (@(
        @{ fixtureId = 'srv-paper-missing-release'; report = 'tools/paper-integration/reports/missing-release.json'; fullReleaseCatalog = $true },
        @{ fixtureId = 'srv-paper-current-release'; report = 'tools/paper-integration/reports/test-release.json'; fullReleaseCatalog = $true; pluginSha256 = $testPluginHash }
    ) | ConvertTo-Json -Depth 4))
    $missingLatestOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -ReleasePluginJar $testJarPath 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $missingLatestOutput -notmatch 'latest full release report is missing') {
        throw "Missing latest full report was not rejected: $missingLatestOutput"
    }
    $missingLatestDefault = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $missingLatestDefault -notmatch 'latest full release report is missing') {
        throw "Default audit fell back to an older full report: $missingLatestDefault"
    }
    $missingHashManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $missingHashManifest[0] = $invalidClientManifest[0]
    $missingHashManifest[0].PSObject.Properties.Remove('pluginSha256')
    [System.IO.File]::WriteAllText($manifestPath, ($missingHashManifest | ConvertTo-Json -Depth 4))
    $missingHashOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $missingHashOutput -notmatch 'plugin SHA-256 is missing or mismatched') {
        throw "Missing latest report plugin hash was not rejected: $missingHashOutput"
    }
    [System.IO.File]::WriteAllText($manifestPath, (@(@{
        fixtureId = 'srv-paper-current-release'
        report = 'tools/paper-integration/reports/test-release.json'
        fullReleaseCatalog = $true
        pluginSha256 = ('b' * 64)
        log = 'tools/paper-integration/reports/test.log.gz'; logSha256 = $testLogHash
        clientLog = 'tools/paper-integration/reports/test.log.gz'; clientLogSha256 = $testLogHash
    }) | ConvertTo-Json -Depth 4))
    $mismatchedHashOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -RequirePresent 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $mismatchedHashOutput -notmatch 'plugin SHA-256') {
        throw "Mismatched manifest plugin SHA-256 was not rejected: $mismatchedHashOutput"
    }
    [System.IO.File]::WriteAllText($manifestPath, (@(@{
        fixtureId = 'srv-paper-current-release'
        report = 'tools/paper-integration/reports/test-release.json'
    }) | ConvertTo-Json -Depth 4))
    $noFullCatalogOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -RequirePresent 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $noFullCatalogOutput -notmatch 'Release evidence manifest must require') {
        throw "Missing full release catalog requirement was not rejected: $noFullCatalogOutput"
    }
    [System.IO.File]::WriteAllText($manifestPath, (@(
        @{ fixtureId = 'srv-paper-current-release'; report = 'tools/paper-integration/reports/test-release.json'; fullReleaseCatalog = $true; pluginSha256 = $testPluginHash; log = 'tools/paper-integration/reports/test.log.gz'; logSha256 = $testLogHash; clientLog = 'tools/paper-integration/reports/test.log.gz'; clientLogSha256 = $testLogHash },
        @{ fixtureId = 'srv-paper-other-release'; report = 'tools/paper-integration/reports/test-release.json'; fullReleaseCatalog = 'false' }
    ) | ConvertTo-Json -Depth 4))
    $invalidFlagOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -RequirePresent 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $invalidFlagOutput -notmatch 'fullReleaseCatalog must be a boolean') {
        throw "Invalid full release catalog flag was not rejected: $invalidFlagOutput"
    }
    $currentReadme = [System.IO.File]::ReadAllText($readmePath)
    [System.IO.File]::WriteAllText($readmePath, $currentReadme.Replace('2 archived release reports', '1 archived release reports'))
    [System.IO.File]::WriteAllText($manifestPath, (@(@{
        fixtureId = 'srv-paper-missing-release'
        report = 'tools/paper-integration/reports/missing-release.json'
        fullReleaseCatalog = $true
    }) | ConvertTo-Json -Depth 4))
    $missingReleaseOutput = @(& pwsh -NoProfile -File $auditScript -Root $temporaryRoot -RequirePresent 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $missingReleaseOutput -notmatch 'latest full release report is missing') {
        throw "-RequirePresent did not reject a missing current release report: $missingReleaseOutput"
    }
    Write-Output 'Evidence audit contract passed.'
}
finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
