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
    $fullReport.expectedProbeIds += 'importedMaterialCooldownCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedMaterialCooldownCondition'; status = 'passed'; observations = @('imported-material-cooldown-condition primary-mapped=true alias-mapped=true positive=true wrong-material-rejected=true')
    }
    $fullReport.expectedProbeIds += 'importedItemRechargingCondition'
    $fullReport.probes += [pscustomobject]@{
        id = 'importedItemRechargingCondition'; status = 'passed'; observations = @('imported-item-recharging-condition primary-mapped=true alias-mapped=true positive=true alias-positive=true non-human-rejected=true')
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
    $aiSelectorCore = @($fullReport.probes | Where-Object id -eq 'aiSelectorCore')
    if ($aiSelectorCore.Count -ne 1) { throw 'Synthetic release report must contain aiSelectorCore.' }
    $aiSelectorCore[0].observations = @($aiSelectorCore[0].observations) +
        @('ai-selector-core targeter-syntax=true')
    $targeterArgumentValidation = @($fullReport.probes | Where-Object id -eq 'targeterArgumentValidation')
    if ($targeterArgumentValidation.Count -ne 1) {
        throw 'Synthetic release report must contain targeterArgumentValidation.'
    }
    $targeterArgumentValidation[0].observations = @(
        'targeter-argument-validation rejected=true chain=true control=true target-players=true ignore-players=true target-players-specific=true target-players-disabled=true invalid-filter=true target-ground=true ignore-ground=true target-water=true ignore-water=true target-flying=true ignore-flying=true target-invulnerable-disabled=true target-invulnerable-priority=true target-armorstands-disabled=true target-armorstands-priority=true target-animals-disabled=true target-animals-priority=true target-creatures-disabled=true target-creatures-priority=true target-villagers-disabled=true target-villagers-priority=true target-all=true target-creative=true ignore-creative=true target-creative-priority=true target-creative-disabled=true target-spectator=true ignore-spectator=true target-spectator-priority=true target-spectator-disabled=true')
    $locationTargeterProbe = @($fullReport.probes | Where-Object id -eq 'locationTargeterDispatch')
    if ($locationTargeterProbe.Count -ne 1) { throw 'Synthetic release report must contain locationTargeterDispatch.' }
    $locationTargeterProbe[0].observations = @('location-targeter-dispatch valid=true invalid-rejected=true self-aliases=true self-eye=true caster-spawn=true spawn=true target-aliases=true trigger=true forward=true projectile-forward=true predicted=true')
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
