# helsteraMobs

## MythicMobs Parity Development Status (2026-11-06)

This compatibility effort is still in progress on Paper 1.21.1. The detailed audit is tracked in [MYTHICMOBS_PARITY_MATRIX.md](MYTHICMOBS_PARITY_MATRIX.md), and runtime evidence is tracked in [tools/paper-integration/STARTUP_EVIDENCE.md](tools/paper-integration/STARTUP_EVIDENCE.md).

Latest strict fixture `srv-paper-is-survival-mode-import-strict-20261106-r1`
passes 361 probes (342 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. Its `importedSurvivalModeCondition` probe routes Mythic
`?isInSurvivalMode` and `?inSurvivalMode` through the importer and
`SkillService`, accepts Survival, rejects Adventure, a Zombie target, and
malformed arguments. Report:
`tools/paper-integration/reports/paper-1.21.1-is-survival-mode-import-strict-20261106-r1.json`.
The plugin SHA-256 is
`47dbd266c9437da0ed5cee53fb9c88618d682bb0af43c1eccc07ce42e1cf50bf`.

Previous strict fixture `srv-paper-is-spyglass-import-strict-20261106-r1`
passes 360 probes (341 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. Its `importedSpyglassCondition` verifies Mythic
`?isUsingSpyglass` and `?isScoping` through the importer and `SkillService`
while a real online Player actively uses a Spyglass, then rejects the inactive
state, a Zombie target, and malformed arguments. Report:
`tools/paper-integration/reports/paper-1.21.1-is-spyglass-import-strict-20261106-r1.json`.
Plugin SHA-256:
`38e0e9769a086950b3e4f5a6923d8f58f44a16fdf3bfaebf512f6d49462a7a20`.

Previous strict fixture `srv-paper-is-frozen-import-strict-20261106-r2`
passes 359 probes (340 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. Its `importedIsFrozenCondition` probe routes Mythic
`?isFrozen` and `?frozen` through the importer and `SkillService`, verifies
both aliases against a real frozen Zombie and after thawing, and rejects an
invalid argument. Report:
`tools/paper-integration/reports/paper-1.21.1-is-frozen-import-strict-20261106-r2.json`.
The plugin SHA-256 is
`4af92b5462a6d1bb533fdb14df654c7e9e9cea066eaef8da10c568bdd4976335`.

Previous strict fixture `srv-paper-item-recharging-import-strict-20261106-r1`
passes 358 probes (339 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. Its `importedItemRechargingCondition` probe routes Mythic
`?itemRecharging` and `?recharging` through the importer and `SkillService`,
checks both aliases after a real Bukkit attack lowers the Player's attack
cooldown, and rejects a Zombie target. Report:
`tools/paper-integration/reports/paper-1.21.1-item-recharging-import-strict-20261106-r1.json`.
The plugin SHA-256 is
`7e4b194a8beb0645e457108ab19d349c9888c783b74724d2cdb78ed533fecc91`.

Previous strict fixture `srv-paper-origin-location-import-strict-20261105-r4`
passes 357 probes (338 passed, 19 expected rejections, zero incomplete). Its
`importedOriginLocationCondition` probe verifies Mythic primary and alias
forms, exact origin coordinates, mismatch rejection, and malformed-input
rejection through `SkillService`. Report:
`tools/paper-integration/reports/paper-1.21.1-origin-location-import-strict-20261105-r4.json`.
Its plugin SHA-256 is
`42abd200b6cde48ca19471c180dbf2821e45d02eb2b12d993b8bd27b221c4bf0`.

Previous strict fixture `srv-paper-trigger-types-import-strict-20261105-r2`
passed 356 probes (337 passed, 19 expected rejections, zero incomplete).
Its imported TriggerItemType/TriggerBlockType probes verify primary and alias
mapping against a real Bukkit dropped Item and a real STONE block in
constructed `BehaviorContext` values; natural Bukkit event delivery remains
unverified. Report:
`tools/paper-integration/reports/paper-1.21.1-trigger-types-import-strict-20261105-r2.json`.
The plugin SHA-256 is
`41cf9e79c81657f05acb68a95a78ad5fdda073de60b7502d17b79e428538ec4a`.

Previous strict fixture `srv-paper-trigger-types-import-strict-20261105-r1`
also completes 356 probes (337 passed, 19 expected rejections, zero
incomplete). It exercised the same imported TriggerItemType/TriggerBlockType
paths against its own exact plugin artifact. Report:
`tools/paper-integration/reports/paper-1.21.1-trigger-types-import-strict-20261105-r1.json`.
Its exact plugin SHA-256 is
`071ceebd2da8a904dfbc3aef8a2594fb5d22c04d83e23acf4e798f9943ebe65e`.

Earlier strict fixture `srv-paper-has-enchantment-import-strict-20261105-r1`
completes 354 probes (335 passed, 19 expected rejections, zero incomplete).
Its `importedHasEnchantmentCondition` probe runs Mythic `?hasEnchantment` and
`?hasEnchant` through the importer and SkillService on a real managed Zombie:
Sharpness and `ANY` pass, level/type mismatches fail, and the temporary item is
restored. Report:
`tools/paper-integration/reports/paper-1.21.1-has-enchantment-import-strict-20261105-r1.json`.
The plugin SHA-256 is
`81492669538c1c4358a4ac7f8537e76791a31fb08d55c1246137ad6afe149fbe`.

Previous strict fixture `srv-paper-condition-import-strict-20261105-r1` completes
353 probes (334 passed, 19 expected rejections, zero incomplete). It verifies
imported Aerial, Color, and `MaterialIsOnCooldown` aliases with real Paper
entities and a player cooldown. Report:
`tools/paper-integration/reports/paper-1.21.1-condition-import-strict-20261105-r1.json`.
The plugin SHA-256 is
`75b5bf2bde256454caa90aa1bea652ac67754b0f65b3e38633d3ddbf7933104a`.

Previous strict fixture `srv-paper-color-import-strict-20261105-r1` completes
352 probes (333 passed, 19 expected rejections, zero incomplete). Its
`importedColorCondition` probe verifies `?color{c=RED}` and `?clr{color=BLUE}`
against a real red Sheep; it also retains the imported Aerial checks. Report:
`tools/paper-integration/reports/paper-1.21.1-color-import-strict-20261105-r1.json`.
The plugin SHA-256 is
`6411d68946e2d04082086609dba9ff8f74e5d404c99df577ae93d6588bf8a51e`.

Previous strict fixture `srv-paper-aerial-import-strict-20261105-r1` completes
351 probes (332 passed, 19 expected rejections, zero incomplete). Report:
`tools/paper-integration/reports/paper-1.21.1-aerial-import-strict-20261105-r1.json`.

Previous strict fixture `srv-paper-is-child-import-strict-20261105-r1` completes
350 probes (331 passed, 19 expected rejections, zero incomplete). Its
`importedIsChildCondition` probe executes Mythic `?isChild` through the importer
and SkillService, confirming parent-relation semantics remain distinct from
baby age checks on real Paper entities. Report:
`tools/paper-integration/reports/paper-1.21.1-is-child-import-strict-20261105-r1.json`.
The plugin SHA-256 is
`e0c6fdaf31e96003a68bd589d1ca88e0786dabb0ecdf8333022474d1d52ddc7b`.

Previous strict fixture `srv-paper-age-options-strict-20261105-r3` completes 349
probes (330 passed, 19 expected rejections, zero incomplete). Its `ageOptions`
probe verifies exact animal age and AgeLock, Adult/Baby setters on a real
Sheep, and the Zombie-specific Baby setter. Report:
`tools/paper-integration/reports/paper-1.21.1-age-options-strict-20261105-r3.json`.
The plugin SHA-256 is
`62051171e7637e3c9d1d40bcc7d254bc6d565d1cca3428a6fff62d019380b50e`.

Previous strict fixture `srv-paper-aerial-condition-strict-20261105-r2`
completes 349 probes (330 passed, 19 expected rejections, zero incomplete).
The dedicated `aerialCondition` probe verifies a floating ArmorStand through
both `aerial` and `isAerial`, then rejects a grounded player, explicitly
recording `ground-entity=player`. Report:
`tools/paper-integration/reports/paper-1.21.1-aerial-condition-strict-20261105-r2.json`.
The plugin SHA-256 is
`97f61a7392ad0051acc0ee55cdcc0272c0512d2675347c138be3ce3f918eaa6a`.

Previous strict fixture `srv-paper-aerial-condition-strict-20261105-r1`
completes 349 probes (330 passed, 19 expected rejections, zero incomplete)
with the original Aerial positive/negative probe. Report:
`tools/paper-integration/reports/paper-1.21.1-aerial-condition-strict-20261105-r1.json`.
The plugin SHA-256 is
`0c35898c00b2b52ebebe8aa8b0ee53665682188c53e10a8149d2fce67df98159`.

Previous strict fixture `srv-paper-enderman-condition-strict-20261105-r1`
completes 348 probes (329 passed, 19 expected rejections, zero incomplete).
The dedicated `endermanCondition` probe accepts a real Enderman and rejects a
managed Zombie. Report:
`tools/paper-integration/reports/paper-1.21.1-enderman-condition-strict-20261105-r1.json`.
The plugin SHA-256 is
`d575844f655ae2812475c18dd550d4e47aa7f235ed73dd974a8153ac23f51478`.

Previous strict fixture `srv-paper-cinematic-condition-20261105-r1`
completes 347 probes (328 passed, 19 expected rejections, zero incomplete).
`isInCinematicCondition` verifies that an active cinematic matches a player
inside its 16-block audience, rejects an out-of-range player and non-player,
and becomes false when the cinematic stops. Report:
`tools/paper-integration/reports/paper-1.21.1-is-in-cinematic-condition-strict-20261105-r1.json`.
The plugin SHA-256 is unchanged.

Previous strict fixture `srv-paper-spawning-timing-20261105-r3`
completes 346 probes (327 passed, 19 expected rejections, zero incomplete).
`spawningDistanceClientWalk` records a real player leaving the configured
radius and both managed/Bukkit entity removal 2 ticks later, within the
10-tick AI decision period. The same report verifies positive and negative
`inside`/`outside` results and restores the original block state. Report:
`tools/paper-integration/reports/paper-1.21.1-spawning-distance-timing-20261105-r3.json`.
The plugin SHA-256 is
`842a3dfd96d10e23c844652ed0fe05736ed3c10d2996d258d81bbd6c870ca4c9`.

Previous full strict fixture `srv-paper-snowing-strict-20261105-r2`
completes 345 probes (326 passed, 19 expected rejections, zero incomplete).
`snowingCondition` verifies target-location isolation, both aliases, warm and
clear rejection, missing context, invalid arguments, and state restoration.
The complete Conditions catalog remains open. Report:
`tools/paper-integration/reports/paper-1.21.1-snowing-strict-20261105-r2.json`.
The plugin SHA-256 is
`1b6fe0a823faadc268149f90df680f3550bab8a2059f41ac1477fa992fd31a44`.

Earlier strict fixture `srv-paper-in-combat-strict-20261105-r3`
completes 344 probes (325 passed, 19 expected rejections, zero incomplete).
The independent `inCombatCondition` probe records managed-caster and native-Mob
positive, no-target rejection, and target restoration. `conditionUtilityAliases`
also retains aggregate coverage. Sheep/Bogged/Snow Golem shear states,
angry/pollinated conditions, and owner/faction targeters remain in the release
catalog. Report: `tools/paper-integration/reports/paper-1.21.1-in-combat-strict-20261105-r3.json`.
The plugin SHA-256 is
`5a5df5c3a02a33f6b0f7fdbc9b4071f04df93613e3c05d1aba98bb290dc86548`.
The full MythicMobs Conditions catalog remains incomplete.

Previous full-catalog fixture `srv-paper-targeter-relations-strict-20261105-r3`
completes 340 probes (321 passed, 19 expected rejections, zero incomplete).
Its `targeterIdentityFilter` evidence verifies owner and same-faction filters,
their precedence over generic categories and `targetall`, managed identity,
and fail-closed relation syntax. The identity marker only recognizes mobs
spawned by Helstera; external MythicMobs/Citizens identity and premium filters
remain open.

Previous strict fixture `srv-paper-target-all-filter-strict-20261105-r1`
completes 339 probes (320 passed, 19 expected rejections, zero incomplete).
Its `targeterArgumentValidation` probe verifies that `targetall=true` overrides
conflicting `target`, `ignore`, and specific category filters within the real
candidate set, retaining animal, creature, villager, ArmorStand,
invulnerability, player-mode, ground, water, and flying checks. Invalid and
duplicate options fail closed. `targetall` preserves the targeter's radius and
world scope. Non-living display/interaction entities, NPC identity, external
MythicMobs identity, and premium filters remain open.

The same strict run verifies `locationTargeterDispatch` through production
`set-block` for SelfLocation and all five `@SelfEyeLocation` aliases, alongside
spawn, target, trigger, forward, projectile and predicted location targeters.
The report covers the JAR identified in `release-evidence.json`; subsequent
source changes require a new build and strict run before becoming release evidence.
It verifies delayed and repeated nested skills, preserves invalid timing options,
and rejects duplicate execution options during runtime binding with diagnostics.
Invalid timing on imported heal mechanics is preserved, warned and not executed.
Positional heal mechanics without targeters retain and obey 0% and 100% chances.
Target conditions and a braced targeter also retain their intended behavior.
The AI selector Paper probe rejects `@targets{limit=1;;}` and confirms
`@targets{limit=1}` selects the real online player; unmatched quotes and other
delimiter errors remain covered by API tests.
Ambiguous chance and targeter suffixes are preserved and rejected; a placeholder
and a valid targeter chain remain mapped.
Unrouted and duplicate event suffixes are rejected with diagnostics during binding.
Case variants, unknown and incomplete suffixes are rejected; quoted text still binds.
Standalone `?` and `@` are preserved and rejected without healing the test entity.
Throwing conditions remain closed through negation; a false-condition control heals.
An online client receives an imported message with commas and a semicolon intact.
Another verifies escaped quotes and backslashes with a `@trigger` targeter.
The drop-item location probe observes a real item spawn and checks rejection of
zero counts and missing location variables. A separate Paper probe imports and
executes Mythic YAML and checks invalid amount and unknown-argument preservation.
The imported `i` alias with `n=false` is verified to spawn at the exact target
location; invalid natural flags are preserved with warnings. The fixture also
verifies inline fixed and skill-variable counts, missing-variable rejection and
invalid inline-count preservation. It also verifies a two-item list and
preservation of invalid list counts and flags, including comma-separated `n=false`;
DropTable references remain open.
It verifies imported health ranges and distance and light comparison boundaries on real entities.
Targeter parsing rejects duplicate generic options, trailing syntax, incomplete
chains and unknown negated entity filters. Automated tests and this report's
Paper runtime probe exercise those controls.
Location parsing rejects malformed fields and non-finite coordinates; API and
teleport-action tests cover these changes. The Paper probe verifies location
dispatch to a block action and rejection of a duplicate non-finite coordinate.
The `@VariableLocation` probe checks block dispatch, duplicate-argument
rejection, and preservation with a migration warning on Paper.
The build includes official `@varLocation` parameter aliases and `@Location`
defaults, tuple, direction and aliases; the Paper probe does not exercise all
of those forms. Automated tests cover these additions.
The importer shares runtime syntax validation for explicit `@Location` and
preserves invalid skill and mob-event lines with warnings; this is unit-tested
but is not directly exercised by the Paper fixture.
Mythic `teleport`/`tp` lines are preserved with a migration warning until the
caster-target, safe landing, spread and orientation semantics are implemented.
The HTTP spawner reload probe verifies rejection and recovery on the server
thread; browser rendering and the loot failure path are not covered.
The strict report uses plugin SHA-256
`770102b11ad30db72aaa7fcd7dbbaac6dfb939598f76cb2f8c46c8586b96a171` and includes
the persisted spawner counter type and structure validation changes.
Numeric overflow and positional comparisons have unit coverage; the strict range probe also exercises duplicate parameters.
Imported `targetDistance` and `lightLevel` comparator boundaries have mapping,
runtime and real Paper entity tests in this report.
The `targetHealthPercent` multiplier correction also has real Paper target
entity and missing-target coverage in this report.
Environment-condition argument and parameter-block validation has automated
tests and a real Paper runtime probe in this report.
Drop-chance syntax validation is covered by migration tests and a Paper
import-and-roll probe, including Java-only hexadecimal and type suffix forms;
this probe does not exercise natural death.
Native loot parsing rejects null, blank, non-string, or conflicting dynamic
chance expressions instead of falling back to the static chance; this report
includes a real Paper file-loading and runtime probe for those cases.
Native loot validation rejects invalid `min-tier-level` entries instead of
dropping their restriction; the latest Paper probe verifies file loading and rolling.
The current build preserves a valid spawner-state backup during the first
save after recovery from a corrupt primary. Unit tests and focused two-boot
Paper fixture `srv-paper-spawner-backup-save-20261103-r1` cover this sequence;
the strict fixture does not specifically exercise it.
Later source changes also prevent saving after an unrecoverable state load or
unsupported state version, until a valid state is loaded. These changes have
unit coverage and are included in the latest strict build, but the failure cases
were not exercised by that fixture. The current build also pauses natural and
forced spawning until a valid state is loaded. Focused fixture
`srv-paper-state-corrupt-both-20261103-r1` verifies the console force command is
rejected and both corrupt files survive shutdown. Focused fixture
`srv-paper-state-natural-repair-20261103-r2` observes natural scheduling paused
at zero and resume after repair. Focused fixture `srv-paper-state-repair-20261103-r2` verifies
forced spawning resumes after repair and reload. Focused fixture `srv-paper-state-missing-primary-20261103-r1`
verifies backup recovery when the primary file is missing.
It verifies imported conditional damage reduction, a rejected condition, and an
invalid native multiplier through real Bukkit damage events. Explicit null
multipliers inside modifier sections and multiple rules of the same damage type
lack dedicated Paper probes. Real damage events also verify that false, null,
and unknown-field native immunity entries and null scalar modifiers are rejected.
Imported `isLiving`, `isBaby`, and `isFrozen` have real-entity positive controls
and reject unknown arguments. All aliases currently mapped as parameterless by
the importer now reject non-empty argument blocks; `MmConditionsTest` checks
their normal and empty-block forms alongside unknown-argument rejection. The
full Mythic condition catalog and parameter schemas for value-bearing
conditions still need auditing.
Age, taming, shearing, saddling and charged-state checks, including their target
variants, also reject unexpected arguments and have importer/runtime tests.
Movement and environmental state conditions, including target variants, now
reject unexpected arguments in migration and runtime binding; Paper verifies
rejection of an invalid target sneaking condition.
Target entity categories and caster/target lifecycle and AI-state checks have
the same argument validation, including negated and repeated invalid bindings;
Paper verifies target mob and alive conditions, rejection of invalid arguments
and a negated invalid mob condition. A Zombie target now passes the sneaking
condition only while sneaking, with the imported skill's action observed on Paper.
Other variants still need dedicated Paper evidence.
Time and weather aliases including `morning`, `clear`, `rain` and `thunder`
preserve and warn on unexpected arguments and reject them at runtime, including
negated conditions. Paper verifies valid morning and clear conditions against
the current world state and rejects invalid morning, rain and negated thunder.
Climbing, invisibility, glowing, falling and target frozen aliases also reject
unexpected arguments. Paper verifies a Zombie's glowing, invisible and frozen
states and rejection, source preservation and warnings for six invalid inputs,
including climbing, falling, charged and negated target invisibility. Positive
climbing, falling and charged states still need dedicated Paper evidence.
Unsupported immunity and damage-modifier sections now preserve nested YAML
under `mob.unsupported.Immunities` and `mob.unsupported.DamageModifiers`; this
change has importer/runtime tests but no dedicated Paper preservation probe.
Nested mapping nulls under immunity and modifier entries are now restored as
invalid markers, including in preserved unsupported sections; Paper verifies a
nested modifier field and real damage rejection. These markers do not
preserve the original null value, and other YAML shapes have not been verified.
Modifier sections and lists with unmapped conditions now preserve the original
entry under `mob.unsupported.DamageModifiers` and report its location; Paper
verifies list-form null conditions survive YAML reload and reject real damage.
Unknown direct fields, including explicit nulls, and invalid `immune` values in
native modifier sections fail closed. Real damage events verify null immunity
fields, null and true unknown modifier fields, and invalid and null `immune` values.
Recent migration scanner, backup, and failure-handling changes are included in
the tested JAR but do not have dedicated Paper probes.
The tested JAR includes recent `lastSignal`, immunity and profile reload changes;
`lastSignal` argument validation and profile reload lack dedicated Paper probes.
Recent malformed drop input and large dynamic weight boundaries do not have
dedicated Paper probes.
Earlier fixture `srv-paper-menu-collision-strict-20261102-r1` verifies real-client
menu pagination and permission checks and renders a custom item named `SPONGE`
as its configured `GOLD_INGOT`.
Earlier fixture `srv-paper-current-diagnostic-strict-20261102-r1` verifies natural
death experience and delayed revive with the probe chunk held loaded.
The immediately
preceding `srv-paper-immunity-preservation-20261103-r1` strict run failed with
13 event XP but 10 observed orb XP. Its report and log are archived as
`paper-1.21.1-immunity-preservation-20261103-r1-failed` in the reports directory.
A later non-strict diagnostic fixture `srv-paper-xp-spawn-diagnostic-20261103-r1`
recorded 13 event XP and three uncancelled orbs of 7, 3 and 3 at the death
location. Spawn notifications were duplicated per orb. Follow-up non-strict
fixture `srv-paper-xp-repeat-20261103-r3` sampled 400 deaths: eight produced
orbs `7x1,3x2`, whose face values sum to 10 but whose total is 13; all 400
matched 13 after accounting for orb count. The original failed run did not
record counts, so its exact cause cannot be established retrospectively. The
natural-death, death-loot, and imported drop-table probes now account for counts
and restore pre-existing orb state. The 400-death run is diagnostic, not release
evidence; the latest strict build includes the count-aware probes.
External item
integration and invalid custom-item collision paths are not covered by this run.
Earlier fixture `srv-paper-nested-import-strict-20261102-r1` verifies a bare
cross-file DropTable reference through natural Zombie death.
Earlier fixture `srv-paper-skill-lifecycle-20261102-r1` verifies in-flight reload
and cancellation after `forget` using an isolated service on a managed Zombie.
Earlier fixture `srv-paper-queue-snapshot-20261102-r2` verifies queued snapshot
restoration through an isolated service.
Earlier fixture `srv-paper-invalid-conditions-20261102-r2` checks imported skill
and damage modifier invalid conditions.
Earlier fixture `srv-paper-biome-import-20261102-r1` verifies translated Biome
condition evaluation in a real Paper world.
Named-skill trigger-context and preview fixes are included in the latest build,
but their specific behavior has unit coverage only.
Nested teardown, cast gates, and cast action ownership have unit coverage; the
latest fixture does not independently exercise those specific behaviors.
Earlier strict fixture `srv-paper-imported-drop-contract-strict-20261102-r1`
completes 284 probes and verifies imported DropTable conditions, preservation of
conflicting table fields, and a real natural managed-Zombie death producing one
Diamond, one Gold Ingot within the exclusive `1-3` range, and 5 XP. Its report
is `tools/paper-integration/reports/paper-1.21.1-imported-drop-contract-20261102.json`.
Earlier strict fixture `srv-paper-named-conditions-strict-20261102-r1` completes
284 probes, including valid negated world selection and rejected invalid world,
moon-phase, and biome arguments. Its archived report is
`tools/paper-integration/reports/paper-1.21.1-named-conditions-20261102.json`.
Earlier strict fixture `srv-paper-negated-conditions-strict-20261102-r1` completes
280 probes (266 passed, 14 expected negative controls, zero incomplete), including
valid negated height and invalid negated height and light conditions. Its report is
`tools/paper-integration/reports/paper-1.21.1-negated-conditions-20261102.json`.
Earlier strict fixture `srv-paper-plugin-hash-strict-20261102-r1` completes
277 probes (265 passed, 12 expected negative controls, zero incomplete), and
records the tested plugin JAR SHA-256 in
`tools/paper-integration/reports/paper-1.21.1-plugin-hash-20261102.json`.
Earlier strict fixture `srv-paper-threat-provider-strict-20261102-r1` completes
277 probes (265 passed, 12 expected negative controls, zero incomplete),
including threat ordering through the production provider after AI startup.
Its report is `tools/paper-integration/reports/paper-1.21.1-threat-provider-20261102.json`.
Earlier strict fixture `srv-paper-context-chain-strict-20261102-r7` completes
277 probes (265 passed, 12 expected negative controls, zero incomplete). It
verifies chained targets, trigger intersection, and production condition
evaluation after AI startup. Its report is
`tools/paper-integration/reports/paper-1.21.1-context-chain-20261102.json`.
Earlier strict fixture `srv-paper-context-targeters-strict-20261102-r1` completes
276 probes (264 passed, 12 expected negative controls, zero incomplete). It
verifies direct context targeter ordering, radius, and invalid-value rejection
with real Paper entities. The report is
`tools/paper-integration/reports/paper-1.21.1-context-targeters-20261102.json`.
Earlier strict fixture `srv-paper-full-catalog-strict-20261102-r2` completes 275
probes (263 passed, 12 expected negative controls, zero incomplete), including
real player Potion consumption and natural expiry. Its report is archived at
`tools/paper-integration/reports/paper-1.21.1-full-catalog-20261102.json`.
Earlier strict release evidence: Paper `1.21.1-133` boots the full plugin
and fixture `srv-paper-drops-imported-range-strict-20261102-r5` completes all
273 probes (262 passed, 11 expected negative controls, zero incomplete). It
verifies standalone `DropTables/` import through runtime loading and natural
death: one Diamond, two Gold Ingots from the imported exclusive `1-3` range,
and 5 XP. It also retains the registered Conditions, targeters, menus, drops,
spawners, AI, and player workflows. Earlier fixtures verify
autonomous Lava damage dispatch, DropTable bonus paths, and the total-items
fixture generates
exactly 128 drop lines, observed as 33 DIAMOND and 95 EMERALD by amount, within
the configured 1:3 relative-weight bounds.
Fixture `srv-paper-pin-targeters-strict-20261102-r2` records the earlier
265-probe catalog (`253 passed`, `12 expected negative-control observations`,
`0 incomplete`). It verifies Pin targeters alongside the player-gate rejection,
natural potion consume and expiry, and the registered Conditions, DropTable,
menu, Audience, AI, and Spawner workflows. The separate fixture
`srv-paper-spawning-distance-client-walk-strict-20261102-r3` verifies a real
client moving beyond eight blocks and production removal of the managed mob
instance and Bukkit entity.
Fixture `srv-paper-drops-range-strict-20261102-r4` records the inline Mythic
`1-3` drop projection as 1-2 GOLD_INGOT on natural death; its strict report
contains all 265 probes with zero incomplete.
Fixture `srv-paper-drops-luck-attribute-strict-20261102-r2` verifies a real
player death with `GENERIC_LUCK=1.0` causing a zero-base-chance entry to pass
the existing per-entry scaler. `BonusLevelItems` and `BonusLuckItems` have
natural-death Paper evidence for level-only, Luck-only, combined, and zero-bonus
cases. Standalone `DropTables/` import rejects explicit null `Conditions` and
`TriggerConditions` instead of treating them as absent; the migration warning
uses an invalid-condition marker for the null value, so consult the source YAML
for the original spelling. Importer and runtime contract tests verify that
rejected tables cannot be rolled and a valid sibling still can. The latest Paper
fixture verifies the same importer and runtime service behavior on the server;
the separate natural-death probe exercises a valid imported table. A later
automated contract also rejects references to such children, including when an
old destination table has the same name. The latest Paper fixture checks this
parent-table case in the importer and runtime service. The same source validation rejects references to tables
with unsupported conditions or case-conflicting keys, including direct mob
`DropTable-1` references; the direct mob case has automated tests only. References
to source tables with no safely projected drop lines are also
preserved as warnings, including through multiple levels of parent tables;
the latest Paper fixture covers an indirect parent reference. Malformed scalar
or explicit null table definitions also block references and
produce warnings; null is represented by a marker in the report. This later
change has automated coverage but no new Paper evidence yet. Standalone import
projects supported semantics and has an importer-to-runtime parser contract. Strict fixture
`srv-paper-drops-imported-range-strict-20261102-r5` verifies that imported table
on a real natural death: one Diamond, two Gold Ingots from `1-3` (runtime 1-2),
and 5 XP, within a complete 273-probe report.
When multiple source DropTables normalize to the same ID, even if one is
invalid, each definition is preserved with a warning rather than individually
projected. This collision handling has automated tests and awaits Paper evidence.
Standalone DropTables also reject null conditions inherited through YAML merges;
unsafe references propagate through parent tables, and explicit overrides follow
merge precedence. Cross-module tests apply the migration with a stale same-name
destination table and confirm the parent cannot roll it; this later importer
change also has a Paper server-side importer and runtime probe; invalid merged
conditions have not been exercised through natural death.
Native `loot.yml` loading also rejects explicit null table and indexed-entry
conditions instead of silently treating them as absent; failed reloads clear
the loaded tables. YAML merge keys and explicit overrides are covered as well.
Automated file-loading tests and a Paper server-side service probe cover this
change; natural death with invalid native conditions has not been tested.
Indexed native entries with explicit null `chance` are also rejected, including
merged values; a Paper server-side service probe verifies item and XP rejection.
Single mapping tiers on indexed entries likewise reject explicit null weights;
if no valid tier remains, the entry uses its base amount. The same Paper probe
checks the fallback and explicit overrides, but not natural death for invalid values.
Dropper `Conditions` now evaluate the
dead entity separately from killer `TriggerConditions`; unit and runtime
condition-binding tests cover accepted, rejected, and missing-evaluator paths.
Strict Paper fixture `srv-paper-dropper-conditions-strict-20261102-r1`
completes 268 release probes and verifies natural managed-Zombie deaths:
`is-monster` allows an Emerald and 3 XP, while `is-animal` rejects BREAD and
its configured 7 XP. The archived report is
`tools/paper-integration/reports/paper-1.21.1-dropper-conditions-20261102.json`.
Strict fixture `srv-paper-exp-lines-strict-20261102-r2` verifies an independent
5 XP `exp` entry during natural managed-Zombie death. Its archived report has
268 probes (256 passed, 12 expected negative controls, zero incomplete).
`BiasStat`, Mythic custom Luck enchantment/curses inputs, and the full catalog
remain incomplete. The official DropTables catalog does not define a separate
`Looting` table option. Fractional bonus multipliers now use the documented
per-level extra chance, and either Bonus option enables weighted selection.
Strict fixture `srv-paper-drops-bonus-semantics-strict-20261102-r1` proves the
natural-death bonus cases and single-bound weighted selection in 273 probes
(262 passed, 11 expected negative controls, zero incomplete). Its archived
report is
`tools/paper-integration/reports/paper-1.21.1-drops-bonus-semantics-20261102.json`.
Follow-up `srv-paper-fractional-bonus-strict-20261102-r4` samples 128 natural
managed-Zombie deaths at level 1 with a 0.5 bonus: 63 produced an extra line.
Its strict report completes 273 registered probes (261 passed, 12 expected
negative controls). The raw Potion workflow was not requested in this run;
the earlier full Potion evidence remains separate.
The earlier r4 report's ordered-selection claim was invalid and is historical.
The report contract covers registered release probes; it does not establish
complete MythicMobs catalog parity. Earlier probe runs are historical
checkpoints.

### Version Support Matrix

| Server / Minecraft | Evidence level | Release status |
| --- | --- | --- |
| Paper 1.21.1-133 / Minecraft 1.21.1 | Latest strict report has 361 probes, zero incomplete | Declared release baseline; this does not imply full MythicMobs parity |
| Paper 1.21.4 / Minecraft 1.21.4 | Exploratory fixtures only | Not release-validated |
| Paper 1.21.11 / Minecraft 1.21.11 | Exploratory fixtures only | Not release-validated |
| Other Paper, Spigot, Folia, or Minecraft versions | No complete strict matrix | Compatibility unverified |

The machine-readable declaration is [tools/paper-integration/version-matrix.json](tools/paper-integration/version-matrix.json). Its contract is checked by `tools/paper-integration/test-version-matrix.ps1`: exactly Paper 1.21.1-133 / Minecraft 1.21.1 is release-validated, and every other combination remains exploratory or unverified until it has its own exact strict report and matching plugin SHA-256.

### Current Development Goals

This is a partial parity release. The matrix currently has `40`
`mapped-unverified` rows (counted from the status column in
`MYTHICMOBS_PARITY_MATRIX.md` on 2026-11-05). The full remaining work is:

1. **Matrix and evidence audit**: reconcile every row with source configuration,
   target field, runtime consumer, tests, Paper evidence, and known boundary;
   remove stale claims and keep README, matrix, and strict report consistent.
   Current fixture inventory: 467 cited IDs; 379 report files; 4 directories
   without reports; 84 missing directories; 144 archived release reports. The
   archived release manifest is the gate's current evidence contract; older
   unavailable fixture references are inventoried as historical, not release
   proof. Restore or recreate any historical report before relying on it again.
2. **Conditions and grammar**: audit the official condition and placeholder
   catalogs, aliases, comparators, defaults, malformed input, and composition;
   add mapping, fail-closed, runtime, and applicable strict Paper coverage.
3. **Mechanics and Skills**: verify official parameters, defaults, positional
   syntax, targets, placeholders, event context, cancellation, repetition, and
   importer-to-runtime consumers.
4. **Targeters and AI**: complete names, filters, ordering, limits, chaining,
   and boundaries; verify Bukkit-expressible behavior and preserve warnings
   for Pathfinder operations unavailable through public APIs.
5. **Triggers and natural events**: verify event context, priority,
   cancellation, timing, and cleanup; distinguish natural server/client
   evidence from synthetic event dispatch.
6. **Mob Options**: cover remaining options, entity-specific defaults,
   application order, and interactions. `MaxCombatDistance` and the
   `SpawningDistance` near/far removal lifecycle now have strict evidence.
7. **Spawner and Random Spawn**: extend natural location, combined conditions,
   cooldowns, limits, player range, world border, restart, and random-pool / loot
   coupling coverage.
8. **Drops and DropTables**: verify placeholders, ranges, conditions, nested
   tables, chance, experience, and natural death timing; identify deterministic
   probes separately from natural-death evidence.
9. **Items, equipment, attributes, and legacy data**: expand metadata,
   attribute combinations, slots, operations, and numeric ID/data coverage;
   preserve unsupported components such as `ItemModel` and `DamageType` with
   explicit migration warnings where Paper 1.21.1 lacks public APIs.
10. **Audience and Custom Menus**: extend filter combinations, client protocol
    coverage, conditions, pagination, reconnect, and action semantics.
11. **SkillTree persistence**: cover restart, death, chunk unload/load, config
    reload, prerequisites, and client display.
12. **External integrations**: define versions and contracts for Vault,
    MythicCrucible, MMOItems, ModelEngine, ItemAdder, CraftEngine, MythicMobs,
    and WorldGuard; require installed-plugin runtime evidence or retain the
    `blocked-by-external-plugin` status.
13. **Version matrix**: declare supported Paper/Minecraft combinations and run
    key strict workflows per combination; Paper 1.21.1 evidence does not imply
    compatibility with other versions or servers.
14. **Strict release gate**: require every claimed supported capability to be
    represented in the report contract, reject missing evidence, and preserve
    reproducible fixtures and logs alongside Maven, schema, and Paper checks.

Completion requires no unexplained `mapped-unverified` rows, explicit
technical reasons and preserved source values for unsupported behavior,
documented external-plugin prerequisites, and passing applicable quality
gates. A passing strict report proves only its registered probe catalog, not
the entire MythicMobs feature catalog.

### Verified Progress

- Official `IsInSurvivalMode` and `InSurvivalMode` now have strict importer-to-runtime evidence. Both aliases pass for a real online Player in Survival, reject Adventure, a Zombie target, and malformed parameters through `SkillService`. Fixture `srv-paper-is-survival-mode-import-strict-20261106-r1` completes the 361-probe release catalog on Paper 1.21.1-133; the Conditions catalog remains open.
- Official `IsUsingSpyglass` and `IsScoping` now have strict importer-to-runtime evidence. Both aliases pass for a real online Player actively using a Spyglass, then reject the inactive state, a Zombie target, and malformed parameters through `SkillService`. Fixture `srv-paper-is-spyglass-import-strict-20261106-r1` completes the 360-probe release catalog on Paper 1.21.1-133; the Conditions catalog remains open.
- Official `IsFrozen` and `Frozen` aliases now have strict importer-to-runtime evidence: `?isFrozen` and `?frozen` both pass on a real frozen Zombie, fail after thawing, and reject malformed arguments through `SkillService`. Fixture `srv-paper-is-frozen-import-strict-20261106-r2` completes the full 359-probe release catalog on Paper 1.21.1-133; the broader Conditions catalog remains open.
- `@PlayersInWorld` now selects valid online players in the caster's world without implicit faction filtering; `@PlayersOnServer` and its `@server`/`@everyone` aliases select online players across worlds. `targetself=true` includes the caster. Unit/importer coverage and strict Paper fixture `srv-paper-targeter-players-strict-20261102-r1` verify filtering, server population and alias equivalence in a complete 272-probe report; full targeter-catalog and cross-world client coverage remain open.
- Generic targeter `skipTargetsUpToIndex` (`stuti`) now skips entries after sorting and before applying `limit`; negative or malformed values fail closed. Focused API, importer, Paper self-test, and report-contract coverage are included. Strict Paper fixture `srv-paper-targeter-skip-index-strict-20261102-r3` verifies the selector behavior on Paper 1.21.1-133; its archived 271-probe report has 259 passed, 12 expected negative controls, and zero incomplete. The complete official targeter catalog remains open.
- Generic targeter sorting now implements `NONE`, `RANDOM`, `NEAREST`/`CLOSEST`, `FURTHEST`, health and threat orderings, plus `OLDEST`/`NEWEST`; unknown modes and unavailable or non-finite threat values fail closed. API tests cover health, age, threat-provider boundaries, unsupported modes, and conditional-target skip/limit ordering. Strict Paper fixture `srv-paper-targeter-sorting-strict-20261102-r1` verifies real health and age ordering; its archived 272-probe report has 260 passed, 12 expected negative controls, and zero incomplete. The full official targeter catalog remains open.
- Official position targeters now resolve `@SelfLocation` and `@SelfEyeLocation` aliases, `@TargetLocation`/`@TL`, `@TargetPredictedLocation`, `@TriggerLocation`, `@SpawnLocation`, `@CasterSpawnLocation`, `@Forward`, and `@ProjectileForward`. API and importer tests cover aliases and fail-closed validation; strict Paper 1.21.1-133 fixture `srv-paper-self-eye-location-strict-20261104-r1` exercises real block-action dispatch for all five SelfEye aliases. Mythic's threat/combat/last-damage location fallbacks, remaining location targeters, and full mechanic-by-mechanic parity are still open.
- `HasCurrency` maps to a fail-closed runtime condition backed by the optional Vault Economy service; reflective discovery keeps Vault a soft dependency. Unit contract tests cover modern `OfflinePlayer` and legacy `String` balance signatures, but installed Vault service discovery, provider versions, and Paper evidence remain unverified.
- MMOItems, MythicCrucible, and ModelEngine are registered as explicit blocked external boundaries. Their diagnostics distinguish missing plugins from detected-but-unadapted plugins, while runtime capabilities remain empty and preserved external identifiers are never resolved as fake vanilla content.
- Mythic survival-mode aliases now have strict Paper 1.21.1 importer-to-runtime evidence: both aliases accept Survival, reject Adventure and non-player targets, malformed parameters fail import, and the original game mode is restored. The required `survivalModeCondition` probe remains independent; the same full strict report passes `enderDragonAliveCondition` with a real Dragon positive/removed-negative lifecycle.
- Skills: separate `TargetConditions` and `TriggerConditions`, nested `cast-skill`, delay/repeat/cast duration, scoped variables, `variable-unset`, location variables, `@VariableLocation`, conditional targeters such as `@nearestConditionalTarget`, target-aware `damage`/`heal` mechanics, and execution-time numeric placeholders such as `<target.hp.raw>`.
- Mechanics: `remove`, `suicide`, target-aware `kill`, bounded `dot` damage-over-time, radial `damage-melee` with caster exclusion, `setnameplate`, `equip`, `set-name`, `set-item`/`set-offhand`/`set-armor`, `set-health`, `set-max-health`, `modify-max-health`, `feed`, `swing` (main/off-hand), `disarm`/`remove-held-item`, `clear-inventory`, `set-material-cooldown`, `set-game-mode`, `jump`, target-aware `force-pull`, `addtag`/`removetag` scoreboard tags (including targeters), `setscore`/`addscore` instance scores, Mythic `setvariable`/`variableunset`/`variableadd`/`variablemath`, `setvariablelocation`/`locationunset`, `signal`/`~onSignal`, Mythic `shoot`/`shootprojectile`, `setintent`, `command`/`commandself`/`commandtarget`, `set-stance`, `randomskill`, `leap`, `set-tick-speed`, `pull`, actionbar, full `title`/`subtitle`/timing delivery, target-aware `damage`/`damage-percent`/`message`/`explode` multi-target dispatch, caster movement `dash`/`blink` with direction modes and placeholder distances, target-aware entity state actions including `set-ai`/`set-gravity`/`set-silent`/`set-collidable`/`set-sneaking`/`set-gliding`/`set-swimming`/`set-fire`/`clear-fire`/`invisible`/`glowing`/`set-invulnerable`, player `set-sprinting`/`set-flying`/`set-walk-speed`, no-damage-tick controls, `look-at`, `set-rotation`, `give-item`, `take-item`, `set-block`, `break-block`, `drop-item`, potion effects, teleport, mount/dismount, `cancel-event`, and custom item resolver integration.
- Scoreboard tag evidence: exploratory Paper 1.21.11 r22 passes real caster/targeter `addtag`/`removetag` mutations; the current strict Paper 1.21.1-133 aggregate also completes the registered tag probes.
- DoT evidence: exploratory Paper self-test verifies bounded `dot` damage across multiple intervals and confirms no damage after `duration`; the current strict Paper 1.21.1-133 aggregate completes the registered DoT probe.
- Conditions: world/biome/time/weather, `night`/`raining`/`thundering`, health/distance/permission/stance/entity state (including caster and target `on-ground`, `has-ai`, `has-gravity`, `is-silent`, `is-dead`, `is-alive`, `target-is-alive`, `is-valid`, `is-collidable`, and `has-offhand` from Bukkit entity state), `ownerIsPlayer`, exact faction conditions (`faction`, `targetfaction`, `samefaction`, `differentfaction`), Mythic `isChild`/`child` parent-relation semantics distinct from `isBaby` age checks (strict Paper fixture `srv-paper-is-child-import-strict-20261105-r1` verifies the importer-to-SkillService path), explicit `target-has-tag`/`targetHasScoreboardTag`, strict target-only permission/game-mode/food/air/item/potion/holding/wearing conditions, vehicle/passenger aliases (`hasPassenger`, `isVehicle`, `targetHasPassenger`, `targetIsVehicle`), `branch-unlocked`/`branch-locked` SkillTree gates, `falling`, `drowning`, `target-within`, equality/inequality comparators for health/target-health/distance/light-level, variables, composition, `food-level`, `has-item`, `air-level`, `damage-cause`, `damage-amount`, and `is-cancelled`. Mythic Aura conditions remain unmapped and are preserved with a migration warning until an Aura data provider is implemented.
- Mythic `isInAudience` (`inaudience`, `audiencecontains`) is preserved with an `unsupported` migration warning: it depends on a per-skill execution-audience set that Helstera does not currently model. The warning distinguishes that missing context from Helstera's per-action audience recipient filters; no runtime equivalence is claimed.
- Condition migration aliases now also cover Mythic `inCombat`, `playersInRadius`, `entitiesInRadius`, `mobsInRadius`, `randomChance`, `targetCount`, and `hungerBelow`; `mobsInRadius` uses a mob-only runtime evaluator.
- Official condition aliases `Burning`, `Blocking`, `Charged`, `Crouching`, movement states, `Chance`, `Day`, `Dawn`, `Dusk`, and `Sunny` now map to runtime evaluators; strict fixture `srv-paper-chance-condition-strict-20261101-r1` verifies 0%, 100%, and fail-closed rejection of 101% on Paper 1.21.1-133.
- Direct camel-case condition names such as `isOnGround`, `isInWater`, `isInLava`, `isInAir`, `isOnFire`, `isBlocked`, `standOn`, and `daylight` now bind to the same canonical Bukkit evaluators as migrated forms.
- Parameterless Mythic time/weather aliases `morning`, `noon`, `midnight`, `clear`, `sun`, `rain`, `storm`, `thunder`, and `thunderstorm` now translate to registered runtime conditions; `MmConditionsTest` and the cross-module runtime binding contract cover the importer path and fail-closed registration boundary.
- `TargetIsFrozen`/`TargetFrozen` now map to `target-is-frozen`, which reads Bukkit `Entity#isFrozen()` and fails closed without a target; importer and runtime tests cover both aliases and both state outcomes.
- Target relationship aliases now include `TargetIsLeashed`, `TargetHasOwner`, `TargetOwnerIsPlayer`, and `TargetOwnerIsOnline`; they use the explicit target entity and fail closed when target or owner data is absent. Paper strict evidence for these target relationship paths remains open.
- Mythic `FoodSaturation`/`hungerSaturation` conditions now use Bukkit saturation comparators, including explicit target context; strict Paper fixture `srv-paper-drowning-strict-20261102-r2` verifies `<=1` at saturation 1.0 and rejects it at 2.0, restoring the player's original saturation.
- Mythic `LastDamageCause` is mapped separately from current event `damage-cause` and reads the target entity's stored Bukkit damage event; strict Paper fixture `srv-paper-drowning-strict-20261102-r2` requires and passes the dedicated `lastDamageCauseCondition` probe, including FIRE acceptance and ENTITY_ATTACK rejection.
- Mythic `Drowning`/`IsDrowning` comparator input rejects malformed and non-finite air values during migration; runtime binding validates the numeric comparator and reads caster `LivingEntity#getRemainingAir()`. Unit and strict Paper verified by `srv-paper-drowning-strict-20261102-r2`; broader Conditions parity remains incomplete.
- Mythic `DamageTag`/`DamageHasTag` maps to a scoped runtime condition. `damage-target` carries normalized `tags`, case-preserving `rawtags`, and normalized `damagetype` tags through the synchronous Bukkit damage event; snapshots retain the tag set and old snapshots load with an empty set. Strict Paper 1.21.1-133 fixture `srv-paper-damage-tag-strict-20261102-r3` verifies tagged/untagged events, cause and final-damage checks, action execution, and cancellation. The overall Conditions catalog remains incomplete.
- Exploratory Paper r48 also verifies the stored FIRE cause path and rejects ENTITY_ATTACK for `LastDamageCause`.
- Mythic `MotionX`/`MotionY`/`MotionZ`/`Velocity` conditions now use target velocity comparator evaluators; exploratory Paper coverage is included in the skill self-test.
- Exploratory Paper r49 verifies all three velocity components and vector magnitude on a real target.
- Mythic `PlayersOnline`/`PlayersInWorld`/`MobsInWorld` conditions now use Bukkit world population comparators; exploratory Paper r50 verifies the live counts.
- Mythic `PlayerWithin`/`PlayerNotWithin` conditions now use world-safe online-player radius checks with explicit-target semantics.
- Exploratory Paper r51 verifies the positive/negative player-radius pair for a real online target.
- Mythic `isCaster` now compares active target identity with the managed caster explicitly.
- Mythic `OwnerIsOnline` now checks resolved Bukkit/PDC owner availability separately from `ownerIsPlayer`.
- Exploratory Paper r52 verifies `OwnerIsOnline` for a real online PDC-backed owner.
- Mythic `HasFreeInventorySlot`/`HasFreeSlot` now use Bukkit inventory empty-slot semantics, including full-inventory rejection.
- Strict fixture `srv-paper-runtime-alias-20261031-r2` verifies the positive and full-inventory-negative `HasFreeInventorySlot` paths and the positive/negative `HasItem` paths on a real online player, with inventory state restored.
- Exploratory Paper r59 verifies target water/lava conditions across real Paper ticks; the report uses the delayed probe because same-tick fluid-cache evaluation is false by design.
- Official `LivingInRadius`, `MobsInChunk`, and `Dimension` conditions now map to Bukkit living-entity, chunk mob, and world-environment evaluators; strict Paper 1.21.1 fixture `srv-paper-mobs-in-chunk-boundary-strict-20261102-r1` requires and passes `mobsInChunkCondition`, verifying zero count, occupied-chunk count, adjacent-chunk exclusion, and high-threshold rejection.
- Official `Height`, `HeightAbove`, `HeightBelow`, `Pitch`, `Yaw`, and `OnBlock` conditions now support Mythic numeric ranges, Bukkit entity orientation, and exact block-material lists; exploratory Paper r64 verifies the live paths.
- Official `BlockType` supports exact material lists, block tags, and material globs; `BiomeType` supports exact biome lists and documented non-exact categories (`taiga`, `extreme_hills`, `jungle`, `mesa`, `plains`, `savanna`, `icy`, `the_end`, `beach`, `forest`, `ocean`, `desert`, `river`, `swamp`, `mushroom`, `nether`, `underground`, `mountain`). Unknown categories are preserved with migration warnings. Strict Paper 1.21.1-133 fixture `srv-paper-biome-type-category-strict-20261101-r1` verifies category matching, rejection, alternatives, exact matching, and missing-context behavior; block-selector strict evidence remains in `srv-paper-block-selector-strict-20261101-r2`.
- `BlockType`, `OnBlock`, and `BlockTypeInRadius` importer schemas now reject unknown or duplicate arguments, empty values, and radius/count fields on conditions that do not accept them. Importer tests preserve official material aliases, comma lists, block tags/globs, positional selectors, and block-state selectors. These new parser probes have unit evidence only; the earlier Paper block-selector and radius reports do not cover the schema rejection cases.
- Official `ServerIsPaper` now detects Paper-family runtimes from Bukkit server identity; `IsInSurvivalMode` strictly checks an explicit target Player. Exploratory Paper r67 keeps the utility probe passed.
- Official `ServerVersion`, `ServerVersionAfterOrEqual`, and `ServerVersionBefore` now compare structured Bukkit Minecraft versions with inclusive flags; the self-test uses the live Bukkit version for its exact-version branch, so the aggregate utility probe remains valid across Paper 1.21.4 and 1.21.11. Exploratory Paper r72 and r2 keep the utility probe passed.
- Official `IsMythicMob` now uses the plugin-managed entity lookup, so it distinguishes real managed instances from ordinary Bukkit entities; exploratory Paper r68 keeps the utility probe passed.
- Official `IsRaiderPatrolLeader` now maps to Bukkit `Raider#isPatrolLeader()`; strict Paper fixture `srv-paper-raider-patrol-condition-strict-20261101-r1` verifies the positive and negative states on a real Pillager, restores its state, and requires the dedicated report probe.
- Official `MobsNearOrigin` now counts valid Bukkit mobs around the execution origin with amount/radius/type filtering; exploratory Paper r70 keeps the utility probe passed.
- Official `VehicleIsDead` now checks the caster's mounted vehicle through Bukkit vehicle state; runtime/migration coverage is present and exploratory Paper r80 keeps the fail-closed utility path passed.
- Official `DistanceFromSpawn` now compares the active target/origin location to its Bukkit world spawn using Mythic range syntax; exploratory Paper r71 keeps the utility probe passed.
- Official `BoundingBoxesOverlap` now uses Bukkit AABB overlap with optional caster forward offset; exploratory Paper r73 keeps the utility probe passed.
- Official `IsCreeperPrimed` now maps to Bukkit `Creeper#isIgnited()`; strict Paper fixture `srv-paper-creeper-primed-condition-strict-20261101-r1` verifies both boolean states on a real Creeper, restores its state, and requires a dedicated report probe. Official `IsFrozen` reads Paper's `Entity#isFrozen()` boolean; strict fixture `srv-paper-runtime-alias-20261031-r2` records `frozenCondition=passed`.
- Official `IsUsingSpyglass` reads only a target Player's active Bukkit item and matches SPYGLASS. Strict Paper fixture `srv-paper-is-spyglass-import-strict-20261106-r1` verifies imported `?isUsingSpyglass`/`?isScoping`, active/inactive transitions, Zombie rejection, and malformed-argument preservation.
- Official `EnderDragonAlive` checks for a valid EnderDragon in the target world's entity set. Strict Paper 1.21.1-133 fixture `srv-paper-ender-dragon-alive-strict-20261102-r1` passes the dedicated `enderDragonAliveCondition`: a real dragon is accepted, then removed and rejected; the complete report has 263 probes and zero incomplete.
- Official `PlayerKills` uses a lifetime counter owned by each managed mob instance and increments only when that instance is resolved as the killer of a Player death; aliases, range syntax, teardown cleanup, and migration coverage are implemented. Strict fixture `srv-paper-spawning-distance-client-walk-strict-20261102-r3` verifies the online-player death event with controlled `lastDamageCause` attribution, passes `player-kills >=1`, and rejects `player-kills >1`; this does not establish natural PvP damage attribution.
- Strict fixture `srv-paper-runtime-alias-20261031-r2` verifies `Absorption`, `HasPotionEffect`, the internal PotionEffect-backed `has-aura-stacks` evaluator, managed `Level`, health-with-absorption comparisons, `LunarPhase`, `LastSignal`, `LineOfSightFromOrigin`, `BowTension`, string and comparator conditions, `GlobalScore`, `Score`, `LookingAt`, `Structure`, Item type/similarity, `Targets`, `ItemRecharging`, and player experience/absorption/passenger condition paths. Fixture `srv-paper-item-recharging-import-strict-20261106-r1` additionally verifies imported `?itemRecharging`/`?recharging` aliases after a real Bukkit attack and rejects non-HumanEntity targets. Lunar phase parsing now rejects the whole condition when any phase token is invalid or empty, including a trailing comma. This does not verify Mythic Aura parity.
- Strict fixture `srv-paper-runtime-alias-20261031-r2` verifies Oxygen, ConsumeSlot, RemoveHeldItem, material cooldown, game mode, Jump, ForcePull, SetOffhand, SetArmor, SetFaction, SetLevel, SetWeather, SetTime, Lightning, Score, and target-aware dispatch.
- Mythic skill migration now preserves positional arguments alongside keyed arguments across common item, inventory, experience, oxygen, block, entity-state, movement, potion, animation, and audience mechanics; importer regression coverage protects both forms.
- Strict fixture `srv-paper-runtime-alias-20261031-r2` also verifies Freeze, CompareValues, IsSkill, Cuboid/InBounds, MythicMobType, VariableContains, and ProjectileHasEnded. `Score` preserves explicit objective/entry/value semantics and supports shorthand; `Targets` supports inherited skill-tree target counts and optional context targeters.
- Official `EnderDragonPhase` now matches Bukkit `EnderDragon.Phase` lists; exploratory Paper r78 verifies a real `CIRCLING` phase.
- Strict Paper fixture `srv-paper-ender-dragon-phase-strict-20261101-r3` verifies `EnderDragonPhase` against a real dragon, including a phase list, a negative match, fail-closed rejection of `CIRCLING,INVALID`, and restoration. All 260 registered probes pass their release outcomes with zero incomplete.
- Official `Altitude` now measures target height above the Bukkit highest-block surface with Mythic range and `maxHeight` handling; exploratory Paper r79 keeps the utility probe passed.
- Official `WorldTime`, `SkyLightLevel`, and `LightLevelFromBlocks` conditions now use Bukkit world time and block light-source APIs; exploratory Paper r65 keeps the live `conditionUtilityAliases` probe passed.
- Official `Moist`/`MoistureLevel` now read only Bukkit `Farmland` BlockData (`moisture > 0` or an `l` range); non-farmland blocks fail closed, and exploratory Paper r81 verifies hydrated, dry, and range paths with restoration.
- Official `MobSize` now reads Bukkit size APIs for `Slime`, `MagmaCube`, and `Phantom`, with Mythic numeric ranges and strict non-size-entity rejection; exploratory Paper r82 verifies a real Slime size transition.
- Official `BlockTypeInRadius` now counts exact Bukkit materials in a spherical block radius with Mythic amount/radius ranges; exploratory Paper r87 verifies positive STONE and negative DIRT paths with restoration.
- Official `FallSpeed` now matches positive downward velocity ranges; exploratory Paper r91 extends the live motion condition probe.
- Official `DirectionalVelocity` now preserves named x/y/z, absolute-axis, and relative-orientation checks; exploratory Paper r95 extends the motion probe.
- Official `FieldOfView` now evaluates caster/target view cones with angle and rotation offsets; exploratory Paper r96 verifies forward, side-rejection, and rotated paths.
- Official `DistanceFromLocation` now compares explicit target coordinates against x/y/z/world and distance ranges; exploratory Paper r97 verifies exact and out-of-range paths.
- Official `EnchantingLevel` / `EnchantingExperience` now read explicit target-player level and 0..1 experience progress; exploratory Paper r92 verifies level and progress comparators with restoration.
- Official `Plugin` / `PluginExists` / `HasPlugin` now check Bukkit PluginManager enabled state; exploratory Paper r93 verifies `helsteraMobs` positive and missing-plugin negative paths.
- Official `SpawnReason` now matches the target entity's Bukkit `Entity#getEntitySpawnReason()` with case-insensitive enum parsing and fail-closed missing/invalid values; exploratory Paper 1.21.11 r98 records `spawn-reason-condition=true` with actual `CUSTOM` and a different-enum negative control.
- Official `IsWaterMob` and `IsZombie` now use Bukkit's `WaterMob` marker and `Zombie` type checks; exploratory Paper 1.21.11 r99 records real Cod/Zombie positive paths and cross-type negative controls.
- Official `Aerial` evaluates the active target's Bukkit `isOnGround()` state with `aerial`/`isAerial` aliases; exploratory Paper 1.21.11 r102 and strict Paper 1.21.1-133 fixture `srv-paper-aerial-condition-strict-20261105-r2` verify floating-ArmorStand positives through both aliases and a grounded-player negative through the dedicated `aerialCondition` probe.
- Official `IsEnderman` now uses Bukkit's `Enderman` type check; exploratory Paper 1.21.11 r103 and strict Paper fixture `srv-paper-enderman-condition-strict-20261105-r1` verify a real Enderman positive path and managed Zombie negative control through the dedicated `endermanCondition` probe.
- Official `HasEnchantment` checks Bukkit equipment slots with `enchantment/type/e` and `level/l` range arguments; exploratory Paper 1.21.11 r104 and strict Paper 1.21.1-133 fixture `srv-paper-has-enchantment-import-strict-20261105-r1` verify imported `?hasEnchantment`/`?hasEnchant`, Sharpness and `ANY` positives, level/type negatives, and equipment restoration. The complete Conditions catalog remains open.
- Official `Color` now matches Bukkit Sheep/Shulker dye colors, Cat types, Parrot variants, Horse colors, and Llama colors; exploratory Paper 1.21.11 r108 verifies a real red Sheep positive, wrong-color rejection, and unsupported Zombie rejection.
- Official `MaterialIsOnCooldown` now reads target-player Bukkit material cooldowns with `material/m` arguments and an `ENDER_PEARL` default; exploratory Paper 1.21.11 r110 verifies positive, wrong-material, and cleared negative paths alongside the passing aggregate condition catalog.
- Official `TriggerItemType` now matches the Bukkit Item entity carried by the trigger context with `types/type/material/m` aliases; strict Paper 1.21.1 fixture `srv-paper-trigger-types-import-strict-20261105-r2` verifies imported primary/alias mapping and STONE/DIAMOND positive/negative paths on a real dropped Item. Its `BehaviorContext` is constructed by the probe, so natural Bukkit pickup-event delivery remains unverified.
- Official `TriggerBlockType` now matches the origin block for `on-block-break`/`on-block-place` trigger states with block-material aliases; the same strict fixture verifies imported primary/alias mapping, STONE/DIAMOND paths, and wrong-state rejection against a real block in a constructed context. Natural block-event dispatch remains unverified.
- Condition importer schemas for `LunarPhase`, `EnderDragonPhase`, `MythicMobType`, `ItemType`, `TriggerItemType`, `EntityItemType`, `EntityMaterialType`, `TriggerBlockType`, and `Structure` now parse all semicolon arguments, reject unknown keys, duplicate aliases, empty values/lists, and invalid booleans, while preserving positional and documented list forms. The official `ItemType` `strict`/`vanillaonly` options are preserved with migration warnings when enabled because the current material evaluator cannot distinguish custom-item identity; `EntityItemType` and `EntityMaterialType` no longer invent a `DIRT` default absent from their official schemas. Migration tests cover these importer cases; existing Paper runtime fixtures do not cover the new schema-rejection paths.
- Mythic condition-list suffix actions now preserve the documented `true`/`false` behavior: top-level `true` is the default, while `false` negates the condition and composes with `!`. The `on-fire` runtime evaluator now reads the active target before falling back to the caster, fixing imported `TargetConditions` such as `isburning false`. `MmConditionsTest` and `MigrationRuntimeConditionContractTest` cover parsing, import and both runtime states; no new Paper strict fixture covers this change. See the official [Conditions guide](https://wiki.mythiccraft.io/mythicmobs/Skills/Conditions) and [Burning condition](https://wiki.mythiccraft.io/mythicmobs/skills/conditions/burning). The complete Conditions catalog remains open.
- Official `XDiff`/`YDiff`/`ZDiff` now compare target-minus-caster Bukkit coordinates with Mythic numeric ranges; exploratory Paper 1.21.11 r114 verifies positive ranges and an out-of-range rejection.
- Official `OriginLocation` matches `BehaviorContext.origin()` by block coordinates or exact coordinates with `loc/l/c`, `x/y/z`, and `exact/e`; strict Paper 1.21.1-133 fixture `srv-paper-origin-location-import-strict-20261105-r4` verifies imported primary/alias paths, exact coordinates, mismatch rejection, and malformed number/boolean rejection. Importer tests also reject unknown/duplicate keys and mixing location and coordinate forms; these parser cases are not in that Paper report. The complete Conditions catalog remains open.
- Official `HasDialogOpen` bridges the active DialogueService state by model instance, with lifecycle-safe hook injection; strict fixture `srv-paper-cinematic-condition-20261105-r1` records the start/open/stop/closed transitions.
- Official `IsInCinematic` checks explicit player targets against the active DialogueService cinematic and same-world 16-block audience; strict fixture `srv-paper-cinematic-condition-20261105-r1` verifies in-range, out-of-range, non-player, and stopped states.
- Mythic `Name` now matches the active entity's exact custom name, falling back to its Bukkit entity name when no custom name is present.
- Strict fixture `srv-paper-runtime-alias-20261031-r2` verifies custom-name positive matching and default-name rejection on a real managed entity (`nameCondition=passed`).
- `TargetWithin`/`TargetNotWithin` and target-AI line-of-sight conditions now inspect the active entity's vanilla AI target; caster-to-target `LineOfSight` remains separate.
- Mythic `GlobalCooldown` aliases (`gcd`/`setgcd`/`setglobalcooldown`) and `offgcd` are mapped to instance-scoped runtime cooldown state.
- Strict fixture `srv-paper-runtime-alias-20261031-r2` verifies the live instance cooldown transition through `cooldownMechanics`.
- Mythic `SetSkillCooldown` aliases dynamically update named-skill cooldowns per instance, with zero-duration clearing and teardown cleanup; exploratory r46 verifies the live transition.
- Placeholders: caster/target health, location, state, permissions, entity flags, food/saturation/exhaustion, air, luck/armor attributes, main-hand/offhand item types; exploratory Paper r18 records `gameplay-placeholders=true`.
- Kill triggers: strict fixture `srv-paper-spawning-distance-client-walk-strict-20261102-r3` passes `killTrigger` through a real Bukkit mob-damage/death chain and `playerKillTrigger` through an online-player death event with controlled killer attribution, including PlayerKills positive/negative assertions.
- Taming/breeding, leash, shear, bed, trade, quit, item lifecycle, player action, and fishing dispatch probes pass in the strict Paper `events` fixture; natural player/client timing remains a separate gap.
- Potion effect end: strict fixture `srv-paper-potion-expiry-strict-20261101-r1` records `potionEffectEndTrigger`, managed-entity expiry, real-player `playerPotionConsume`, and real-player natural expiry as passed on Paper 1.21.1-133. A requested short raw-Potion workflow now keeps `playerPotionNaturalExpiry` in the strict report even when its event is missing, so the release gate cannot silently omit it.
- Player event triggers: strict fixture `srv-paper-runtime-alias-20261031-r2` passes the registered interact, world-change, jump, swing, bed, quit, item, and fishing event probes; natural client timing remains open.
- Entity interaction trigger: `on-interact` listens to `PlayerInteractEntityEvent`, covering ordinary entity right-clicks and the `PlayerInteractAtEntityEvent` child. Strict event dispatch is covered; natural client timing remains open.
- Reload lifecycle: plugin shutdown clears static SkillExtras/SkillCatalog hooks, state maps, delayed scheduler references, and the projectile bridge so a reload cannot retain the old plugin classloader.
- Strict fixture `srv-paper-runtime-alias-20261031-r2` verifies quit, bed enter/leave, item drop/break/damage, fishing, shearing, leash/unleash, and villager trade trigger dispatch on Paper. Natural disconnect and client-driven event timing remain open.
- Drops: amount ranges, conditions, nested tables, cycle protection, dynamic chance, experience, and external drop classification.
- Migration: legacy numeric IDs now cover common base blocks including `0 -> AIR`, classic water/lava and piston IDs, stable item IDs through 452, colored Shulker/Glazed Terracotta blocks, music discs `2256–2267`, common `383:DATA` spawn eggs, `ID:DATA` shorthand, and safe data variants/durability (including flowers, leaves, fish, banners, infested blocks, quartz, prismarine, double plants, fishing rods, shears, shields, and elytra); Mythic metaskill, standalone `skill{s=...}` references, DropTable, and spawner conditions are translated through the shared condition mapping, with unknown leaves preserved for manual review; unknown or unsafe data remains visible as an explicit warning.
- Structured Mythic Drops with any unmapped condition are preserved under `mob.unsupported.Drops.entries` and warned about; they are excluded from generated runtime loot tables so an unknown condition cannot silently turn a conditional drop into an unconditional one.
- Mythic inline drop ranges now honor the official exclusive upper bound: `1-3` and `1to3` project to 1..2, fixed amounts remain fixed, and empty/reversed ranges are preserved as unsupported. Migration tests cover the projection; strict Paper fixture `srv-paper-drops-imported-range-strict-20261102-r5` verifies an imported standalone DropTable through runtime loading and natural mob death. Broader importer range combinations remain open.
- Scalar condition compatibility now uses one parser across spawners, immunity/damage modifiers, drops, and Mythic imports: `day, !raining, time-between=0,12000` is split into separate conditions without corrupting range or coordinate commas; nested braces, quotes, YAML lists, and arrays remain intact.
- Spawner file loading and Mythic spawner import reject explicit null conditions, including inherited YAML merge values; explicit valid overrides and omitted conditions retain their normal behavior. File and migration-to-runtime tests cover these cases and failed reload cleanup. Paper strict fixture `srv-paper-spawner-null-20261103-r2` verifies native direct and merged null rejection and an empty-list override using forced spawn; imported and natural scheduled spawns are not covered by this fixture.
- Malformed spawner state YAML triggers backup recovery even when a Bukkit server logger is available. The independent two-boot Paper fixture `srv-paper-spawner-corrupt-restart-20261103-r1` verifies a generated count is restored after corrupting the primary state file; it is not a full strict release run.
- A failed spawner configuration reload suppresses state writes until a successful load so retry or shutdown cannot overwrite saved counts; a unit test covers recovery. Reload failures are reported to commands and the web editor. These changes have not yet been verified on Paper.
- Web-triggered loot and spawner reloads run on the Bukkit primary thread and return the actual reload result. Independent Paper fixture `srv-paper-web-reload-20261103-r2` verifies HTTP spawner reload rejects malformed YAML and restores 43 spawners after repair; it does not exercise the browser or the loot failure path.
- Skill-line probability grammar now accepts finite `25%`, `.5%`, and scientific `1e1%` forms; values outside `0..100`, malformed percent tokens such as `-1%` or `NaN%`, and trailing text after a closed argument block fail closed instead of becoming positional mechanic arguments. `SkillService` routes every line containing `%` through the structured parser, and `MythicSkillSpecTest` plus `SpecArgValidationTest` cover the parser and binding boundaries; the complete official skill grammar remains open.
- Mythic `Options.Size` migrates positive integer ranges such as `2-5`/`2to5` to spawn-time randomized Slime/MagmaCube/Phantom sizes; fixed sizes remain unchanged and invalid ranges stay visible with migration warnings. Official Paper 1.21.1-133 strict evidence sampled the production Slime option path 64 times within inclusive bounds `2..5`.
- Migration runtime contract audit now binds the canonical conditions and mechanics asserted by the importer tests through the real `SkillService`; missing runtime registrations fail `MigrationRuntimeConditionContractTest` instead of remaining a report-only mapping.
- Mob Options, Spawners, Random Spawn, Audience, Custom Menu, SkillTree, and AI have focused Paper or unit evidence recorded in the parity matrix.
- Exploratory Paper r55 now records natural Creeper fuse, Bee stinger restoration, Zombie conversion prevention, and jockey mount-event probes as passed; strict Paper 1.21.1 evidence and natural AI jockey selection remain open.
- AI selector core is represented by a typed priority/`clear` plan with core goal/target aliases, relation/faction target resolution, conditional selectors, native `Mob#setTarget` synchronization, and an exploratory Paper `ai-selector-core` probe. Paper 1.21.1 release evidence is still required.
- Exploratory Paper 1.21.11 r126 records `aiPriority=passed` and `aiPriorityTargets=passed` after the self-test revives and protects the online player before arbitration; `stateConditions=passed` covers real flying/sneaking/gliding state. The historical client Shield-use gap is closed by strict Paper r27, which records `stateBlocking=passed` from a real Mineflayer Shield-use window.
- Exploratory Paper 1.21.11 r127 records independent `itemPickup=passed` and `environmentalDamage=passed` probes after isolating the managed player from earlier natural damage and binding a dedicated no-attacker damage profile.
- Exploratory Paper 1.21.11 r128 records `lootDynamicChance=passed` with a deterministic runtime `<random.1to1>` expression; the probe no longer depends on a flaky random hit.
- Strict Paper 1.21.1-133 fixture `srv-paper-strict-potion-20261101-r5` closes the player Potion-consumption evidence gap: after the menu client disconnects, a raw protocol 1.21.1 client reconnects in Survival, drinks the server-equipped Swiftness Potion, and records `playerPotionConsume=passed`; the fixture completes 252 probes (241 passed, 11 expected negative controls rejected). The standalone exploratory fixtures remain separate from this strict release evidence.
- Strict Paper 1.21.1-133 fixture `srv-paper-potion-expiry-strict-20261101-r1` completes 255 probes and records both `playerPotionConsume=passed` and `playerPotionNaturalExpiry=passed`; the short-effect player event is observed as `expired=true action-observed=true`.
- Strict Paper follow-up `srv-paper-mob-max-combat-distance-20261101-r5` verifies imported per-mob `MaxCombatDistance=8`: a production-spawned Zombie allows the 7-block damage control and cancels the 9-block event. The complete 253-probe report has 241 passed, 12 expected negative controls rejected, and zero incomplete; raw player Potion consumption also passed.
- Strict Paper fixture `srv-paper-spawning-distance-client-walk-strict-20261102-r3` verifies imported legacy `SpawningDistance=8` through a real Mineflayer client moving 14.01 blocks; Paper observes 8.47 blocks of player displacement and removes both the managed instance and Bukkit entity. Its 264-probe report has 253 passed, 11 expected negative controls, and zero incomplete. Latest full strict fixture `srv-paper-spawning-timing-20261105-r3` additionally measures removal 2 server ticks after the client is first observed beyond range, against the configured 10-tick AI decision period. Earlier fixtures separately establish nearby retention and server-driven out-of-range removal.
- Strict Paper 1.21.1-133 fixture `srv-paper-biome-condition-strict-20261101-r1` verifies `Biome` exact namespaced matching, non-exact key substring matching, comma-separated alternatives, exact-mode rejection of the substring, and a negative mismatched key. Its 254-probe report has 242 passed, 12 expected negative controls, and zero incomplete.
- Raw Potion r136/r137 re-arm hotbar slot 1 immediately before use; r136 records `playerPotionConsume=passed` with `item=POTION`, and r137 records both `playerPotionConsume=passed` and `playerPotionNaturalExpiry=passed`.
- Exploratory Paper 1.21.11 r133 records `vehicleEvents=passed` and `ejectPassenger=passed`; the fixture now exercises a real Bukkit `VehicleDestroyEvent` dispatch in addition to enter/exit/dismount/eject coverage.
- Exploratory Paper 1.21.11 r138 records `blockEvents=passed` through immediate synthetic Bukkit `BlockBreakEvent`/`BlockPlaceEvent` dispatch; natural Mineflayer block timing remains separately unverified.
- Exploratory Paper 1.21.11 r139 records the spawner no-ground negative control with `requested=false`, `failure=no valid spawn location`, and all 384 block states restored.
- Exploratory Paper 1.21.11 r138 records delayed native entity targeting with `entity-target-trigger=true`, `target-reason=CLOSEST_PLAYER`, and `target-cancelled=false`; the report no longer treats unrelated global target lookup misses as this probe's failure.
- Exploratory Paper 1.21.11 r142 records `tempt=passed`, `entityTargetBukkit=passed`, and `entityTargetDelayed=passed` with a real Survival player holding WHEAT and Paper target reason `TEMPT`.
- Event routing now prefers the managed binding whose profile declares the incoming single trigger when one Bukkit entity has multiple managed bindings; Paper r138/r140 confirms block dispatch remains correct under that routing.
- Trigger dispatch audit now requires every `wired=true` trigger to have an auditable direct-event, derived-sampler, condition-sampler, potion-event, or signal-bus source; `TriggerDispatchAuditTest` covers the classification.
- Exploratory Paper 1.21.11 SkillTree restart fixture `srv-paper-skilltree-restart-034f7c7c184b48a9b2c57db5453effdb` now passes a two-boot persistent-chunk/entity/snapshot workflow; persistent chunk retention is released after the last managed persistent mob dies.
- Strict fixture `srv-paper-runtime-alias-20261031-r2` records `targetStateConditions=passed`, including target glowing positive and no-target negative checks; the separate exploratory client fixture also covers a real player's glowing state restoration.
- Mythic `Options.Interactable` now maps to the managed runtime policy with its documented imported default, and strict Paper 1.21.1 fixture `srv-paper-integration-armor-stand-20261031` records protected/control player interaction and Armor Stand manipulation pairs as `interactable=true` and `interactable-armor-stand=true`.
- Mythic `Options.PatrolSpawnPoint` now maps to Raider patrol target blocks, and strict Paper 1.21.1 fixture `srv-paper-integration-patrol-spawn-20261031` reads back a real Pillager patrol target at its spawn block.
- Mythic `Options.Adult` and `Baby` use Bukkit age setters, including Zombie/Piglin-specific baby APIs. Exact tick `Age` and `AgeLock` apply to `Animals`; for ageable non-animal types such as Zombies/Husks, those two values are preserved under `entity.unsupported.Options.*` with migration warnings while `Adult`/`Baby` remain mapped. Non-Ageable values are also preserved with warnings. Importer/runtime tests cover animal, Husk, and Allay cases; strict Paper 1.21.1-133 fixture `srv-paper-age-options-strict-20261105-r3` records `age-options result=true age=-24000 baby=true adult=true age-lock=true zombie-baby=true` on real Sheep and Zombie entities. Other Mob Options remain incomplete.
- Official Armor Stand options `HasArms`, `HasBasePlate`, `Marker`, `Small`, `CanMove`, and `CanTick` now map through the migration/runtime path; strict Paper 1.21.1 fixture `srv-paper-integration-armor-options-20261031-r3` reads back the production can-move/can-tick state.
- Official Armor Stand item options `ItemBody`, `ItemFeet`, `ItemHand`, `ItemOffhand`, `ItemHead`, and `ItemLegs` now resolve through the existing ItemStack/custom-item path; strict fixture `srv-paper-integration-armor-options-20261031-r3` reads back all six equipment slots.
- Mythic `Options.Saddled` now applies through Paper `Steerable` and `AbstractHorse` APIs; strict fixture `srv-paper-integration-saddled-20261031-r2` reads back a real Horse saddle.
- Mythic `Options.HorseArmor` now maps to `entity.horse-armor` and uses the existing ItemStack/custom-item resolver through Paper's `ArmoredHorseInventory`; strict fixture `srv-paper-integration-horse-armor-20261031` reads back a real `DIAMOND_HORSE_ARMOR` item alongside the saddle.
- Mythic `Options.CarryingChest` now maps to `entity.carrying-chest` for Horse/ChestedHorse entities; strict fixture `srv-paper-integration-horse-chest-20261031-r2` reads back `chest=true` from a real Donkey through the production option path.
- Mythic `Options.Scale` now has strict configuration-to-instance evidence: fixture `srv-paper-integration-scale-20261031-r2` reads back `scale=1.25` from a real configured `mobs/scale_probe.yml` spawn.
- Mythic `Options.Health` now has strict configuration-to-instance evidence: fixture `srv-paper-integration-health-20261031-r3` reads back `health=42.0 max-health=42.0` from a real configured `mobs/health_probe.yml` spawn through the production entity path.
- Mythic `Options.MovementSpeed` now applies the migrated `mob.move-speed` value to the real Bukkit `MOVEMENT_SPEED` base attribute; strict fixture `srv-paper-integration-movement-speed-20261031-r6` reads back `base=0.4` from `mobs/movement_speed_probe.yml`.
- Mythic `Options.KnockbackResistance` now maps to the production `mob.knockback-resistance` path and applies Bukkit `KNOCKBACK_RESISTANCE`; strict fixture `srv-paper-integration-knockback-resistance-20261031-r8` reads back `base=0.75` from `mobs/knockback_resistance_probe.yml`.
- Mythic `Options.FollowRange` now applies the migrated `mob.sight-radius` value to Bukkit `FOLLOW_RANGE`; strict fixture `srv-paper-integration-follow-range-20261031-r9` reads back `base=40.0` from `mobs/follow_range_probe.yml`.
- Mythic `Options.Damage` now applies the migrated `mob.attack-damage` value to Bukkit `ATTACK_DAMAGE`; strict fixture `srv-paper-integration-damage-20261031-r10` reads back `base=12.0` from `mobs/damage_probe.yml`.
- Mythic root `Armor` now applies the migrated `mob.armor` value to Bukkit `ARMOR`; strict fixture `srv-paper-integration-armor-20261031-r11` reads back `base=8.0` from `mobs/armor_probe.yml`.
- Entity option configuration now has strict real-entity coverage for `NoAI`, `Invincible`, `Silent`, `Gravity`, and `Collidable`; fixture `srv-paper-integration-entity-flags-20261031-r12` reads back all five configured states.
- Mythic root `Exp` now maps to an explicit mob death-experience override, including fixed and ranged `drops.experience*` values; strict fixture `srv-paper-integration-exp-20261031-r16` records `exp-options result=true experience=13 event=true`.
- Natural Mythic mob death now has separate strict Paper evidence: `srv-paper-exp-natural-death-20261101-r3` applies real Bukkit damage to a managed Zombie, observes its death and 13 spawned experience points, and passes the complete 255-probe report with zero incomplete probes. The synthetic `EntityDeathEvent` probe remains separate.
- Natural DropTable death behavior is now explicit in the strict contract: `srv-paper-drops-natural-death-20261101-r4` confirms the managed Zombie dies from Bukkit damage, emits the conditional Diamond stack, rejects the Bread entry, and spawns 9 experience points from the configured 9–11 range.
- DropTable `trigger-conditions` gate each table's items and its own experience against the killer; nested item expansion checks the child table's conditions, and missing killer context or condition evaluator fails closed. Child-table experience is not included in a parent roll, and nested reference amounts are not applied; loading reports these limitations, including experience deeper in the nested table graph. The transitive warning has unit coverage and has not been verified on Paper. Strict Paper 1.21.1-133 fixture `srv-paper-drops-trigger-conditions-20261101-r7` records real OP/non-OP permission allow/deny, nested rejection with 4 XP retained from the accepted parent, and zero item/XP output from a rejected top-level table.
- Mythic `Options.Despawn`/`Persistent` now has a dedicated production config-to-instance probe; strict fixture `srv-paper-integration-exp-20261031-r17` reads back `mode=PERSISTENT`, `distance=0`, and the real entity's persistent flag.
- Mythic `VisibleByDefault`, `Invisible`, and `AlwaysShowName` now have a dedicated real-entity config-to-instance probe; strict fixture `srv-paper-integration-exp-20261031-r19` records `visibility-options result=true visible-by-default=true invisible=true show-name=true`.
- Spawner group limits now have strict Paper evidence: `group-size=3-5` with `max-alive=2` produces `alive=2 total=2` and reports `limit=true` in fixture `srv-paper-integration-exp-20261031-r20`.
- Natural scheduler group limits now have strict Paper evidence: fixture `srv-paper-integration-exp-20261031-r21` records `before=2 after=4 alive=2 max-alive=2 group-min=3 group-max=5 limit=true`.
- Named Spawner region gates now have strict Paper evidence: fixture `srv-paper-integration-exp-20261031-r28` records both the in-region success and the out-of-region rejection (`spawner-region-outside id=region_probe_outside requested=false region=integration_box failure=conditions rejected location`).
- Mythic `Blocking`/`is-blocking` now has strict Paper evidence: fixture `srv-paper-integration-exp-20261031-r27` keeps a real online player in a client-driven Shield-use window and records `stateBlocking=passed`.
- Spawner natural recycling now has strict Paper evidence: fixture `srv-paper-integration-exp-20261031-r28` records `released=true respawned=true alive=1` after removing a real tracked instance.
- Spawner restart persistence now has Paper 1.21.1-133 evidence: fixture `srv-paper-spawner-restart-20261031-r2` performs two real plugin boots and records `stage=write before=0 after=1 generated=true result=true`, then `stage=verify before=1 after=1 restored=true result=true` from the same plugin data directory.
- `set-target` and `clear-target` mechanics now have strict Paper evidence: fixture `srv-paper-integration-targeters-20261031-r3` records `set-target-mechanic=true clear-target-mechanic=true` after dispatching both actions through the production `SkillService` to a managed Bukkit Mob.
- Runtime targeter aliases now have strict Paper evidence: fixture `srv-paper-runtime-alias-20261031-r2` executes `@Players_In_World`, `@Mobs_In_World`, `@Nearest_Living_Entity`, and `@Nearest_Mob` and records the complete `ai-selector-core` probe as passed.
- Vehicle enter/exit/dismount/eject/destroy trigger coverage is now lifecycle-independent from the online player probe; strict fixture `srv-paper-integration-exp-20261031-r17` records `vehicle-events enter=true exit=true dismount=true eject=true destroy=true`.

### Remaining Work

Many rows remain `mapped-unverified`; the project is not complete:

1. Complete official Conditions, Mechanics, placeholders, comparator edge cases, and grammar coverage.
2. Drops: `TotalItems`, paired `MinItems`/`MaxItems`, or either Bonus option enable weighted entry selection; single bounds without bonuses retain ordered selection. Fractional Bonus multipliers add the integer part plus one line probabilistically at the fractional remainder. Strict natural-death evidence covers level/Luck combinations and dropper conditions; the earlier single-bound ordering assertion is invalid and is being replaced. Standalone `DropTables/` files project supported settings with an importer-to-runtime contract, and `exp`/`xp`/`experience` rows have natural-death coverage. Remaining work includes `BiasStat`, Mythic custom Luck enchantment/curses inputs, broader placeholders and the complete official catalog.
3. Pin conditions resolve through the plugin's own `pins.yml`; location-valued `@Pin` resolves every configured point or one random point, `@BlocksInPinRegion` enumerates inclusive cuboids up to 16,384 blocks, and `MovePin` updates single-point pins in memory. Strict Paper fixture `srv-paper-pin-targeters-strict-20261102-r2` passes the required `pinTargeters` probe on Paper 1.21.1-133: multi-point/random dispatch, single-point movement, multi-point movement rejection, real cuboid `set-block`/`break-block` with block restoration, and fail-closed rejection of a 16,385-block region before action dispatch. Compatibility with Mythic pack pin-file formats remains unverified; in-memory movement resets when the plugin reloads its own `pins.yml`.
4. Complete the official targeter/filter catalog: ground, water, flying, player, Creative, Spectator, invulnerability, ArmorStand, animal, creature, villager, and Helstera-managed mob identity filters now have API and strict Paper coverage. External MythicMobs/Citizens identity, non-living targets, premium selectors, and native Pathfinder Goal mutation remain open or outside the public Paper API boundary.
5. Expand the remaining legacy numeric ID/data families; `ItemModel` and `DamageType` are explicitly preserved because Paper 1.21.1 `ItemMeta` has no public component API for them.
6. Strict Paper 1.21.1-133 now records the registered Creeper/Bee/Zombie and jockey mount-event probes; finish universal Mob Option ordering while natural AI jockey selection remains unverified.
7. Add remaining Bukkit event, Audience version, and SkillTree lifecycle/restart evidence.
8. Keep the Maven quality gate and strict Paper 1.21.1-133 release gate passing; the latest archived targeter follow-up `srv-paper-targeter-sorting-strict-20261102-r1` has 272 probes, while parity breadth and cross-version evidence remain open.

### Continue On Another Device

```bash
git clone https://github.com/addxiaoyi/helsteraMobs.git
cd helsteraMobs
./mvnw -o -q test
./mvnw -q -Ppaper-integration -pl helstera-plugin -am verify
```

On Windows PowerShell, use `.\mvnw.cmd` in place of `./mvnw`.

The repository wrapper pins Maven 3.9.16 (`./mvnw` on Unix-like systems, `.\mvnw.cmd` on Windows), so a system Maven installation is not required. The strict `verify` gate requires Maven 3.8.9+ (Maven 3.9+ recommended), the SpotBugs Maven plugin and
Python `jsonschema` 4.x; use the offline command only for the cached unit-test
and package checks below.

Useful Paper checks:

```powershell
.\mvnw.cmd -o -q -pl helstera-plugin -am package
powershell -ExecutionPolicy Bypass -File tools/paper-integration/test-report.ps1
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 75 -Strict
```

Preserve the dirty worktree and generated Paper evidence directories. Do not use `git reset`, `git checkout`, or cleanup commands while continuing this work.

Minecraft（Paper / Spigot）模型引擎插件。采用「资源包 + Display 实体（ItemDisplay / TextDisplay）」渲染方案，纯 Bukkit/Paper API，无 NMS 依赖。

## 功能

- 模型格式 `helstera-v1`：`manifest.yml` + `model.json` + `animations.json` + 纹理，支持骨骼层级、动画关键帧、父子变换。
- 渲染：骨骼 → ItemDisplay 层级，Interaction 碰撞体，玩家可见性订阅（hideEntity），统一 Tick 调度器（批量队列 / 预算 / LOD）。
- 资源包构建：Box-UV → 模型 JSON、CustomModelData overrides、zip 打包、SHA1、下发。
- AI 有限状态机（空闲 / 巡逻 / 追击 / 攻击 / 受伤 / 逃跑 / 施法 / 死亡）+ 感知器 + 行为注册表。
- 目标选择器（Targeter）：最近 / 最远 / 随机 / 最低血量 / 最高血量 / 仅玩家 / 仅怪物，并驱动 `aoe-damage`、`teleport-targets`、`effect-targets`、`ignite-targets`、`knockback-targets`、`message-targets` 等多目标动作。
- 掉落表（loot.yml）：权重概率、数量区间、幸运值加成、附魔 / 自定义名 / CustomModelData；死亡时自动投掷并可计入抢夺等级。
- 免疫与伤害倍率（对标 MythicMobs `Immunities` / `DamageModifiers`）：按档案配置，支持精确 cause 与类别两层匹配、`negate` 免疫、倍率与负倍率回血、单个倍率跨 tick 不衰减、条件化生效；详见下方「免疫 / 伤害倍率」。
- 网页开发器（Javalin 6 + Jackson）：模型列表 / 预览 / 动画播放 / 生物配置编辑 / 校验 / 保存 / 回滚 / 审计 / 模型 zip 导入导出；**技能**（skills.yml）、**掉落表**（loot.yml，含掷骰预览）、**刷怪点**（spawners.yml）可视化编辑；SSE 实时推送重载事件，保存后无需手动刷新。
- 外部插件检测与迁移中心（四插件导入器 + 自动备份）；ItemAdder、CraftEngine 物品 API 尚未适配，导入值保留为未支持项。
- 刷怪点（spawners.yml）：定时 + 半径随机 + 存活上限 + 累计上限 + 玩家门控 + 世界绑定；所有刷怪点共用一个调度任务。**支持 `ai-profile` 字段覆盖 mob 档案的 AI 配置**，无需修改原档案即可指定不同的行为配置。
- MythicMobs 模型 mechanic 与条件（反射注册，签名变动自动降级）：`modelspawn` / `modelremove` / `modelplay` / `modelstop` / `modelscale` / `modelmount` / `modelunmount` / `modelheal`，以及 `modelspawned` / `modelremoved` / `modelplaying`。

## 模块

| 模块 | 说明 |
| --- | --- |
| helstera-api | 公共 API |
| helstera-core | 模型解析 / 校验 / 注册表 |
| helstera-runtime | 实体运行时 |
| helstera-render-paper | Paper 端渲染 |
| helstera-resourcepack | 资源包构建 |
| helstera-ai | AI 行为 |
| helstera-integrations | 外部插件适配 |
| helstera-web | 网页开发器 |
| helstera-migration | 迁移中心 |
| helstera-platform | 平台校验 |
| helstera-plugin | 插件主模块（打包 uber jar） |

## 构建

### 离线构建先确认 Maven 仓库指向

`MAVEN_OPTS` 可能已把构建指向一个**不含** `paper-api:1.21.1-R0.1-SNAPSHOT` 的仓库，
此时 `-o` 会在 `helstera-api` 处直接解析失败。先确认：

```bash
echo "$MAVEN_OPTS"                    # 看是否带 -Dmaven.repo.local=...
ls ~/.m2/repository/io/papermc/paper/paper-api/
```

默认仓库里没有该版本时，显式指定一个有的仓库：

```bash
mvn -o -Dmaven.repo.local="$HOME/.m2/repository" test
```

**验证退出码不要写成 `mvn ... | tail`**：那样 `$?` 取到的是 `tail` 的退出码，
构建失败也会显示为 0。重定向到文件后再看，或用 `grep -c FAIL` 交叉确认。

要求：JDK 21；优先使用仓库自带 Maven Wrapper（Unix-like 系统 `./mvnw`，Windows `.\mvnw.cmd`，固定 Maven 3.9.16）。直接使用系统 Maven 时要求 3.8.9+（Maven 3.9+ 推荐；离线构建需在 `~/.m2` 预缓存 paper-api、adventure、joml、gson、snakeyaml 等）。

```bash
# 完整版（5.0，含网页开发器）
./mvnw -o clean install

# 跑测试（全仓库 12 模块）
./mvnw -o test

# 分阶段出包（仅含"已完成"模块，产物名 HelsteraMobs-<n>.0-<variant>.jar）
./mvnw -o clean package -P phase1   # 1.0：核心 api + core
./mvnw -o clean package -P phase2   # 2.0：+ runtime + render-paper
./mvnw -o clean package -P phase3   # 3.0：+ resourcepack + ai
./mvnw -o clean package -P phase4   # 4.0：+ integrations + migration
# 默认（不激活任何 -P）= 完整版 5.0（profile 定义在 helstera-plugin/pom.xml，不在根 pom）
```

产物实测体积（2026-06）：phase1 ≈ 0.89 MB、phase2 ≈ 0.95 MB、phase3 ≈ 1.19 MB、
phase4 ≈ 1.25 MB、full ≈ 9.50 MB。

各阶段 jar 的名字带 `-<variant>` 后缀而不是共用同一个名字——共用会导致按阶段验证
时静默覆盖，拿到的却是上一轮的残缺 jar。

产出的 uber jar 放入服务器 `plugins/` 目录即可。

所有 helstera 模块在 `helstera-plugin` 中以 `provided` 作用域声明（只进编译期类路径），
实际打进 jar 的模块由 `-P` profile 决定。因此各阶段都能编译通过，
运行期缺失的子系统由 `onEnable` 的 `try/catch` 优雅降级。

## 网页开发器

默认监听 `0.0.0.0:8765`（所有网卡），启动日志会打印带令牌的访问地址：

```
→ 远程访问: http://<服务器IP>:8765/?token=xxxx
```

- 同机访问：保持 `config.yml` 的 `web.host: 127.0.0.1`，打开 `http://127.0.0.1:8765/`。
- 跨机访问：`web.host: 0.0.0.0`，或用命令 `/helstera web start 0.0.0.0` 立即按 0.0.0.0 重启。
- 排障：`/helstera web doctor` 打印各网卡真实访问地址与防火墙提示；`/helstera web firewall` 尝试添加放行规则（需管理员权限）。
- 注意 `0.0.0.0` 会监听所有网卡，请配合防火墙——任何能连到该端口者凭令牌即可访问。

## 免疫 / 伤害倍率

对标 MythicMobs 的 `Immunities` / `DamageModifiers`，写在**行为档案**上（与 `faction`、
`region`、`pathfind`、`phases` 同处），mobs/*.yml 的 `ai` 节可整体覆盖档案级配置。

```yaml
ai:
  profiles:
    烈焰领主:
      immunities: [PROJECTILE]      # 免疫箭与所有弹丸
      damage-modifiers:
        fire: 0.5                   # 火伤减半（类别，覆盖 FIRE/FIRE_TICK/LAVA/...）
        drowning: 0.25              # 溺水只剩四分之一
        entity-attack: -1.0         # 近战伤害转为回血
        PROJECTILE:
          when: health-above 0.3    # 仅血量高于 30% 时免疫
          immune: true
```

### 排查前必须知道的五条规则

这五条决定「配置是否生效」，不看代码无法自行推断：

1. **倍率乘的是事件原始伤害**，不是已扣护甲后的最终值。因此 `0.5` 不会逐次衰减成
   `0.25` / `0.125`——这是有意为之。
2. **优先级：精确 cause > 类别**。写了 `FIRE_TICK` 就只挡 `FIRE_TICK`，类别 `fire` 仍管其余。
3. **同类目多条命中时，先写的那条生效**。书写顺序即优先级，保序是有意设计。
4. **同名键只保留一条**。`fire` 同时能当 cause 名和类别名，两种解释都会注册，
   但**同一键重复声明会被后者顶掉**——不会叠加。
5. **条件不成立 = 该条规则不存在**，并继续向下匹配下一条，而不是把伤害冻结。
   条件名用 `when`（单条）或 `conditions`（多条，全部满足才生效）。

技能造成的 Bukkit 伤害由受击实体的免疫监听器处理一次，匹配服务端事件实际报告的
`DamageCause`；技能内部标记的伤害类型不一定等于该事件类型。此行为尚需 Paper 实服验证。

### 静默失效的几种情况

免疫配错在服务端**完全没有症状**——规则不匹配与「没配这条」在现象上完全一致。
以下情况不会有任何报错或异常：

- 伤害类型名拼错（既不是 cause 也不是类别）→ 该条被跳过
- `when` / `conditions` 里的条件名不存在 → 条件恒不成立，该条永不生效
- 免疫监听器未注册（仅在 AI 层未启用等异常路径）→ 全部免疫规则静默失效

对应的排查入口：

```bash
/helstera immunity            # 逐条规则 + 逐 cause 试算（按 10 点原始伤害）
/helstera immunity <档案名>   # 只看某个档案
/helstera check               # 体检，4.3 段报告装载期告警与监听器状态
```

网页开发器「运营工具 → 免疫 / 伤害倍率诊断」面板提供同样的信息（`GET /api/immunity`）。

### 回血语义

负倍率即回血，走「伤害归零 + 显式加血」，**不依赖服务端对负伤害的处理差异**，
因此不会静默失效、也不会双重治疗。加血夹到 `maxHealth`——`setHealth` 越界会被服务端
夹到 0，也就是把生物打死。

### 从 MythicMobs 迁移

重新导入已有生物时，迁移中心先备份原文件，再以导入结果替换整个生物定义；
原文件中的手动修改不会自动合并，请在备份中检查需要保留的配置。

`immunities` 既支持 YAML 列表，也支持 `FIRE,LAVA|DROWNING` 这类分隔字符串。
迁移中心会自动落地 `Immunities` 与 `DamageModifiers`（含三种写法：裸数字、
带 `multiplier` 的节、带 `conditions` 的条目列表），并把条件名换算成 helstera 的写法
（`?health{<50%}` → `health-below 0.5`）。

不换算会让条件永远匹配不上，而条件求值是 fail-closed——表现为「配了条件免疫，
永远不免疫」，且没有任何报错。无等价物的条件会**原样保留并在报告中告警**，
不会静默丢弃。

## 仇恨与连杀

### 仇恨（threat）

`ThreatTable` 按玩家 UUID 记录，威胁值 = 伤害量 × 距离权重，并按秒指数衰减。
目标选择由它决定，因此「拉仇恨」「切目标」都是可配置行为而非巧合。

```yaml
ai:
  profiles:
    坦克 Boss:
      threat-enabled: true
      threat-decay: 0.05
      threat-distance-weight: 10
      skills:
        - trigger: on-target
          priority: 10
          require:
            - threat-above 800        # 某人的仇恨超过 800 才进入狂暴
          actions:
            - set-nameplate "&c&l狂暴！"
            - effect-self speed 2 200
```

**条件**：`threat-above <n>` / `threat-below <n>` / `has-threat [n]` /
`threat-targets-at-least <n>`——判定对象是**当前目标**在该实例仇恨表中的值。

**动作**：`threat-add <n>`（给当前目标加仇恨）/ `threat-clear [true]`（`true`
只清当前目标，缺省清空整表）/ `threat-focus`（把当前目标顶成首要目标）。

Paper 1.21.11 exploratory r41 已验证三种动作的真实仇恨表语义；严格
Paper 1.21.1 release evidence 仍待匹配 server jar。

排查前要知道的三条：

- **档案必须开 `threat-enabled`**，否则没有仇恨表，四条条件恒为 false。
  这与「仇恨值确实是 0」在现象上完全一致。
- **仇恨按实例存储**。同一个玩家对不同 Boss 的仇恨互不相同，
  条件读的是「这个实例眼中该玩家的值」。
- `threat-focus` 叠加一个足够大的增量，**不直接改写仇恨值**——直接赋值会
  绕过距离权重与衰减，破坏仇恨平衡。

目标选择器 `threat` 按仇恨降序排列，等仇恨时按距离兜底（否则模型会在
候选间反复横跳）。**未注入仇恨数据源时它返回空候选，不会悄悄退回「最近」**。

### 连杀（kill-streak）

按玩家 UUID 记连续击杀，**不按实例**——同一玩家杀 10 只不同的怪是 10 次连杀，
不是 10 个各为 1 的实例分数。默认窗口 30 秒，用 `System.nanoTime` 计时
（改系统时间不会让连杀算错或永不重置）。

窗口**惰性求值**：读取时比对时间戳，超时即视为 0。因此不存在
「定时器漏跑导致连杀不重置」这类只在卡顿时偶发的故障。

**条件**：`kill-streak-at-least <n>` / `kill-streak-active`
**动作**：`kill-streak-reset`（领奖一次后打回原形）

刻意**不提供「直接设连杀数」的动作**：连杀只能由真实击杀累积，
否则 `on-kill-player` 的奖励可以凭空刷出来。

判定对象是「击杀了该实例的玩家的连杀数」。因此：

- 只有**玩家**击杀计入；陷阱、投射物、环境致死都拿不到 Player 击杀者
- 该实例从未被玩家击杀过时判定为 0，**不会**在首次见面时就触发连杀奖励

## 目标选择器（targeter）

技能里的 `targeter:` 决定「挑谁」。内置排序 targeter 以及上下文 targeter 均可用，关系类包括 `@Owner`、`@Parent`、`@Children`、`@Passengers`、`@Passenger` 和 `@Vehicle`：

| 名字 | 排序依据 |
| --- | --- |
| `nearest` / `farthest` | 距离由近到远 / 由远到近 |
| `nearestentity` / `nearestliving` / `nearestlivingentity` / `nearestmob` | 最近的一个生物；`nearestmob` 排除玩家 |
| `random` | 随机打乱（不排序，避免可预测行为） |
| `players` / `mobs` / `living` | 类型过滤后按距离 |
| `lowest-health` / `highest-health` | **绝对血量** |
| `vulnerable` / `lowest-health-percent` | **血量比例**最低（等同 MM 的 vulnerable） |
| `highest-health-percent` | 血量比例最高 |
| `threat` | 仇恨值降序 |

两个容易踩的点：

- **`lowest-health` 与 `vulnerable` 不是一回事**。前者按绝对值，在混血队伍里几乎恒等于「挑玩家」；后者按比例，满血的坦克是否脆弱取决于比例而非数值。
- **`threat` 需要档案开 `threat-enabled`**，否则返回**空候选**而不是悄悄退回「最近」。这是刻意的——回落会让「按仇恨选目标」静默变成「选最近的」，而配置看上去完全正常。

等分时一律按距离兜底，保证排序确定：否则每 tick 遍历顺序的微小变化会让模型在候选之间反复横跳，表现为周期性抽搐转向。

## Boss 血条

写在 `mobs/<档案>.yml` 的 `bossbar` 节，随档案走。

```yaml
ai:
  profiles:
    巨龙:
      bossbar:
        enabled: true
        title: "&c远古巨龙"
        range: 64
        thresholds: [0.5, 0.2]
        colors: [GREEN, RED]
```

| 键 | 说明 |
| --- | --- |
| `enabled` | 关闭时不显示任何血条（默认值） |
| `title` | 主标题；留空则用档案名，再留空用「Boss」 |
| `range` | 可见距离；`0` 表示不限 |
| `thresholds` / `colors` | 分段配色，两者**必须等长**，否则告警并忽略分段 |
| `gradient` | `true` 时在档位间插值；见下方「Boss 血条渐变配色」 |

三段式接线：生成时挂条、受伤时更新、死亡时隐藏。

**快捷开关**：只想看到一条默认血条、不打算写 `bossbar` 节时，直接在 `mobs/<档案>.yml` 顶层写

```yaml
show-health-bar: true
```

它等价于开启血条，且**不需要档案有 `ai` 节**——血条是显示层能力，不依赖 AI 档案。与 `bossbar` 节同时存在时以 `bossbar` 为准（后者能表达「不要血条」）。

**四种显示**：名称 + 血量、分段变色、当前阶段名（读档案的 `phases`）、技能读条进度（技能带 `cast-duration` 时）。

### 排查前必须知道的三条

- **阶段名来自 `phases` 配置**。档案没写 `phases` 时，血条只显示名称和血量，不会出现 `[阶段]`。
- **`thresholds` 与 `colors` 数量必须一致**。不一致会告警并**忽略整段配色**，血条退回默认绿色，而不是部分生效。
- **比例低于所有档下界时回落到最低档**。所以把下界都写高时，「只剩一丝血」会显示最低档色而不是满血色——这是刻意的，否则濒死观感与实际相反。

## 阶段播报（Announcement）

写在 `profiles.<name>.phases[].announce`（文字）及可选的 `sound` / `particle` / `commands`。
播报范围是 Boss 周围 32 格内的玩家，而非全服广播。

```yaml
phases:
  - id: enraged
    min: 0
    max: 75
    announce: "&4[警告] Boss 进入狂暴状态！"
    sound: ENTITY_WITHER_SPAWN
    particle: DRIP_LAVA
    commands:
      - "say %mob-name% is now in phase %phase%"
      - "effect give @a nearby 8 speed 10 1"
  - id: dead_phase
    min: 75
    max: 100
    announce: ""          # 纯音效无文字
    sound: BLOCK_NOTE_BLOCK_PLING
```

| 字段 | 说明 |
| --- | --- |
| `announce` | 文字消息（支持 `%phase%` `/ %hp%` `/ %mob%` 等占位符，同阶段切换公告） |
| `sound` | Bukkit `Sound` 枚举名，在 Boss 位置播放 |
| `particle` | Bukkit `Particle` 枚举名，在 Boss 位置发射 64 个粒子 |
| `commands` | 列表；每条通过控制台执行，占位符同样展开 |

**规则**：
- 四项独立：文字为空仍可播 sound；command 为空仍可播粒子。
- 任何一项执行失败只打 warning，不影响其他项。
- 播报半径固定 32 格，不可配置——这是手感调校后的结论，避免野外 Boss 切阶段刷屏。

## Mob Levels 等级缩放

写在 `mobs/<档案>.yml` 的 `ai` 节里，**不是**写在 `ai.profiles.<name>` 里。

```yaml
ai:
  profile: 巨龙
  level: 10
  levels:
    - property: health
      base: 1000
      growthPerLevel: 1.05
    - property: damage
      base: 10
      growthPerLevel: 1.10
    - property: speed
      base: 0.3
      growthPerLevel: 1.02
```

| 键 | 说明 |
| --- | --- |
| `level` | 当前等级；1 = 基础档，不触发缩放 |
| `levels` | 每个等级的缩放配置列表；为空时不缩放任何属性 |
| `levels[*].property` | 属性名：`health` / `damage` / `speed`（缺省为其他值时静默忽略） |
| `levels[*].base` | 该属性在 1 级时的基准值 |
| `levels[*].growthPerLevel` | 每级的增长倍数；1.0 = 不随等级变化 |

**缩放公式**：`实际值 = base × growthPerLevel^(level-1)`

影响范围：
- **生命值**：生成时 `setMaxHealth` 与 `setHealth` 按缩放后值写入实体
- **攻击伤害**：`hitTarget()` 结算时用 `profile.attackDamage × damageScale`
- **移速**：`moveBy()` 用 `profile.moveSpeed × speedScale`
- **血条标题**：显示 `Lv.N 名称 [阶段]`，N 来自实例的 level 字段

排查点：
- **`level: 1` 或省略** 时，`growthPerLevel^0 = 1.0`，所有属性不缩放——这与「未启用等级系统」的语义一致，不会因为少写一个字段而报错
- **`growthPerLevel < 1`** 会被原样保留：若写 `0.5`，等级越高属性越低（衰减而非增长）。这是有意为之，让作者能表达「幼体 weaker、成体 stronger」的反向设计
- **属性名拼错**（如写 `hp` 而非 `health`）会静默忽略该属性，不影响其他属性

## 对话与电影脚本（Dialogs & Cinematics）

写在 `dialogs.yml` 的 `dialogues` 节里，通过 `start-dialogue` 技能动作触发。

```yaml
dialogues:
  guard_intro:
    cinematics:
      greeting:
        commands:
          - say 守卫 [严肃] 站住！此处禁止通行。
          - wait 60
          - look 90 0
          - say 守卫 [警告] 再不后退我将呼叫支援。
          - wait 80
          - say 守卫 [愤怒] 既然你不听……攻击！
      farewell:
        commands:
          - say 守卫 [疲惫] 看来你比我想象的要强……走吧。
```

| 命令 | 参数 | 说明 |
| --- | --- | --- |
| `say` | `<说话者> <文本>` | 向附近 16 格玩家发送聊天消息 |
| `move` | `<dx> <dy> <dz> <speed>` | 移动实例载体（世界坐标偏移） |
| `look` | `<yaw> <pitch>` | 设置实例载体朝向 |
| `wait` | `<ticks>` | 等待指定 tick 数（20 ticks = 1 秒） |

在 mobs/*.yml 中通过 `ai.triggers` 引用：

```yaml
ai:
  profile: default
  triggers:
    on-spawn:
      do: [start-dialogue guard_intro:greeting]
```

排查点：
- **同一实例同时只运行一条 cinematic**：重复触发会打断并重新开始
- **命令参数不足时静默跳过**：与技能系统一致，不会报错
- **未知命令记录告警**：不会中断整个 cinematic

## 技能读条（Cast Progress）

写在 `skills.yml` 的技能定义里，通过 `start-cast` 动作启动，读条期间 Boss 血条显示进度百分比。

```yaml
skills:
  fire-blast:
    cast-duration: 3s          # 读条时长
    cast-label: "蓄力中"        # 标题后缀文本
    on-decision:
      - start-cast fire-blast  # 开始读条
      - wait 3s                # 等待读条完成
      - aoe-damage players 8 1 20  # 读条结束后造成伤害
  cancel-fire-blast:
    on-decision:
      - cancel-cast            # 取消读条
```

| 字段 | 说明 |
| --- | --- |
| `cast-duration` | 读条时长（`3s` / `1500ms` / `90t`）；0 或省略 = 无读条 |
| `cast-label` | 血条标题后缀（如 "蓄力中"）；默认 "施法中" |

**配套动作**：
- `start-cast <技能名>`：启动读条计时，不执行技能动作
- `cancel-cast`：取消当前实例的所有活跃读条
- 死亡/ despawn 时自动清除该实例的所有读条

血条显示格式：`Lv.N 名称 [阶段] 蓄力中 67%`

## 刷怪点 AI 门控

`spawners.yml` 的每个刷怪点可加 `ai-profile` 字段，覆盖 mob 档案的 AI 配置，
无需修改原档案即可为同一 mob 指定不同的行为配置。

```yaml
spawners:
  elite_spawner:
    mob: dragon
    x: 0
    y: 64
    z: 0
    radius: 20
    max-alive: 2
    ai-profile: "dragon_elite"   # 覆盖 dragon.yml 的 AI 配置
  normal_spawner:
    mob: dragon
    x: 100
    y: 64
    z: 100
    radius: 15
    # 不写 ai-profile 则沿用 dragon.yml 的默认 AI 配置
```

优先级：`ai-profile`（刷怪点）> `ai.profile`（mobs/*.yml 的 ai 节）> `default`。

## 触发器接线状态

`/helstera check` 会区分三种情况，而不是笼统说「未知名」：

| 状态 | 含义 |
| --- | --- |
| 已接线 | 写进 `mobs/*.yml` 会正常触发 |
| 可写但未接线 | 名字认得，写了**不会报错也不会触发** |
| 无法识别 | 名字拼错了，改键名即可 |

**全部已接线**：所有触发器（含 `on-pre-target`、`on-damage-negation`、`on-death-skill`）均已接入真实事件来源。

### MM 条件补全

以下 MythicMobs 常用条件现已在 helstera 中可用：

| MM 条件 | helstera 写法 | 说明 |
| --- | --- | --- |
| `?onGround` | `on-ground` | 优先读取 Bukkit 实体的地面状态 |
| `?inWater` | `in-water` | 实体处于水中 |
| `?inLava` | `in-lava` | 实体处于岩浆中 |
| `?isSneaking` | `is-sneaking` | 实体是否潜行 |
| `?isGlowing` | `is-glowing` | 实体是否发光 |
| `?facing-{target}` | `facing-target [角度]` | 实体朝向目标（默认 ±45° 容差） |
| `?onFire` | `on-fire` | 实体处于燃烧状态 |
| `?isSprinting` | `is-sprinting` | 实体疾跑中（仅玩家载体） |
| `?inAir` | `in-air` | 实体离开地面 |
| `?isSwimming` | `is-swimming` | 实体在游泳（区别于仅在水中） |
| `?isClimbing` | `is-climbing` | 实体在攀爬墙/梯 |
| `?isInvisible` | `is-invisible` | 实体隐身中 |
| `?inBlock` | `is-blocked [高度]` | 实体被方块卡住（默认查头顶） |
| `?standingOn` | `stand-on <方块名>` | 脚下踩的是指定方块 |
| `?lightLevel` | `light-level-below/above` 或 `light-level =/!= <等级>` | 当前方块光照等级比较 |
| `?daylight` | `daylight [0..1]` | 太阳高度是否达到阈值 |
| `?targetHasPermission` / `?targetHasItem` | `target-has-permission` / `target-has-item` | 严格读取显式目标，不回退到 caster |
| `?targetGameMode` / `?targetFoodLevel` / `?targetAirLevel` | `target-game-mode-is` / `target-food-level` / `target-air-level` | 严格读取目标玩家或目标实体状态 |
| `?targetPotionEffectActive` / `?targetHasPotionEffect` | `target-potion-effect-active` | 严格读取显式目标的 Bukkit 药水效果；`?targetHasAuras` 保留原文并告警，等待 Aura provider |
| `?targetHolding` / `?targetWearing` | `target-holding` / `target-wearing` | 严格读取显式目标装备 |
| `?targetHealth` / `?targetHealthPercent` | `target-health` / `target-health-percent` | 目标原始血量或百分比比较 |
| `?owner` / `?isOwner` | `is-owner` | 检查显式目标是否为 caster 的 Bukkit/PDC owner |
| `?children` | `children <数量范围>` | 统计 caster 关联的有效 LivingEntity 子实体，默认数量为 1 |
| `?inside` / `?outside` | `inside` / `outside` | 检查目标头顶是否有方块，或上方是否开放天空 |
| `?livingInRadius` | `living-in-radius <数量> <半径>` | 统计活动位置附近的有效 LivingEntity |
| `?mobsInChunk` | `mobs-in-chunk <数量>` | 统计活动区块内的有效 Bukkit Mob |
| `?dimension` | `dimension-is <环境>` | 匹配 Bukkit 世界环境，如 `NORMAL` / `NETHER` / `THE_END` |
| `?height` / `?heightAbove` / `?heightBelow` | `height` / `height-above` / `height-below` | 匹配实体 Y 坐标范围或上下阈值 |
| `?pitch` / `?yaw` | `pitch` / `yaw` | 匹配实体俯仰角或偏航角范围 |
| `?onBlock` | `on-block <方块列表>` | 匹配实体脚下的精确 Bukkit 方块材质 |
| `?moist` / `?isMoist` | `moist` | 目标方块必须是 moisture 大于 0 的 Farmland |
| `?moisturelevel` / `?moistureness` / `?moistness` | `moisture-level <范围>` | 通过 `l` 参数匹配 Farmland moisture 等级（0–7） |
| `?mobsize` / `?size` | `mob-size <范围>` | 匹配 Slime/MagmaCube/Phantom 的 Bukkit size，其他实体失败关闭 |
| `?damageAmount` / `?amount` | `damage-amount <范围>` | 只在 damage event 上下文匹配 Bukkit final damage，默认 `>0` |
| `?blockTypeInRadius` | `block-type-in-radius <材质> <数量范围> <半径>` | 统计球形范围内的精确 Bukkit 方块材质 |
| `?fallSpeed` / `?fallingSpeed` | `fall-speed <范围>` | 匹配实体向下速度的正值 |
| `?directionalVelocity` / `?dvelocity` | `directional-velocity{x=...;y=...;z=...}` | 按世界轴或实体朝向匹配速度分量 |
| `?fieldOfView` / `?fov` | `field-of-view <角度> <旋转>` | 检查目标是否位于 caster 视野锥内，默认 90° |
| `?distanceFromLocation` | `distance-from-location x=... y=... z=... distance=...` | 按显式目标位置匹配坐标与距离范围 |
| `?enchantingLevel` / `?enchantingExperience` | `enchanting-level` / `enchanting-experience` | 匹配显式目标玩家的经验等级与升级进度 |
| `?plugin` / `?pluginexists` / `?hasplugin` | `plugin <插件名>` | 检查指定 Bukkit 插件是否已启用，默认 `MythicMobs` |
| — | `world-is <世界名>` | 实例所在世界匹配 |

配置示例：

```yaml
triggers:
  on-spawn:
    require: [on-ground]          # 只在地面生成时触发
    do: [sound ENTITY_WITHER_SPAWN]
  on-damage-negation:
    do: [ignite-targets players 4 1]  # 被免疫时点燃攻击者
```

写一个未接线的触发器不会有任何提示，所以配置体检应当习惯性跑一遍。

## 技能队列与优先级

当同一实例在同一 tick 内触发多个技能时，系统按 `priority` 字段降序排队执行，高优先级先执行完再执行下一个。

```yaml
skills:
  urgent-heal:
    priority: 100          # 高优先级：紧急回复
    on-decision:
      - heal-target players 20
  display-effect:
    priority: 10           # 低优先级：装饰性效果
    on-decision:
      - particle heart 5
```

- 默认 `priority` 为 0，数值越大越先执行。
- 队列仅在多技能同时触发时激活；单次触发不受影响。
- 队列中的技能独立计算冷却，已在冷却中的技能不入队。

## 技能预览（只判定，不执行）

排查「技能写了却永不触发」时最有用的一条命令。真服上验证一条技能只能等 Boss 挨打到那个血量——被动且慢，于是配置作者改错条件后继续等待，形成死循环。预览把判定链路走一遍并输出轨迹。

### 参数写错会在装载期报出来

`skills.yml` 里的**参数格式错误**（不是技能名写错，而是参数本身不合法）会直接显示在 `/helstera check` 与启动日志里：

```
✗ 技能定义 1 处问题 (改 skills.yml 后 /helstera reload skills)
  - 动作 "set-scale:abc" 参数非法: 第 1 个参数 "abc" 不是合法数值（应为 1 / 0.5 / -2 之类）
  - 动作 "heal-percent:百分比" 参数非法: 第 1 个参数 "百分比" 不是合法数值（...）
```

此前这些是**完全静默**的：参数解析失败会回退到默认值，技能照常执行、`/helstera check` 全绿、日志零警告。现场表现是「技能有时没效果」，且无法与「阈值设得本来就高」区分——这是本项目最常见的缺陷形态。

布尔参数另有一处更隐蔽的旧行为：`loop yes` / `loop 1` / `loop on` 曾被静默当成 `false`（`Boolean.parseBoolean` 对一切非 `true` 的输入都返回 false）。现在这些写法都接受，其余拼法会报「不是合法布尔值」。

**参数非法时该动作不会被注册**，因此不会以默认值悄悄执行。

### YAML 数值字段同样会被校验

`loot.yml` / `spawners.yml` / `mobs/*.yml` 里的数值字段也走同一套校验。原因在于 Bukkit 的 `getDouble(key, default)` 把三种不同的问题压成了同一种静默失败：

```yaml
chance: "很多"      # 键存在但值非法 -> 静默用默认值 1.0，即 100% 必掉
amount_chance: 0.5  # 键名拼错     -> 同样静默用默认值 1.0
chance: 5.0         # 值越界       -> 静默钳到 1.0
```

实测记录：`chance: "很多"`（作者本意「少量」）实际执行成 **100% 必掉**，直接影响服务器经济，而体检、日志、控制台全无提示。现在这些都会出现在 `/helstera check` 里：

```
✗ 掉落表 2 处问题
  - 掉落表 t 第 1 条 的 chance = "很多" 不是合法数值，已使用默认值...
  - 掉落表 u 第 1 条 的 chance = 5.0 超出 0..1，已夹到 1...
```

刷怪点侧同理，`world` 不存在、`max-alive: 0`（永远刷不出）、`radius: 0`（附近玩家判定恒不成立）等都在装载期报出来。

### 生物配置的两种检查

`mobs/<id>.yml` **每个文件只能放一条定义**（文件名即生物 ID），且插件启动时**不解析**它们——模型、掉落表、行为档案都要等真的生成才知道结果。往一个文件里塞多条定义会导致整个文件不生效，而命令报的却是「模型未加载: `<文件名>`」，把文件名当成模型 ID，作者会被引向完全错误的方向。

因此 `/helstera check` 对生物配置分两路：

- **静态引用完整性**（不必生成就能查）：`model` 缺失、`drops.table` 指向不存在的表、`ai.profile` 未定义、`ai.faction` 未在 `ai.factions` 里定义、顶层结构异常
- **生成时才暴露**（`entity.type` 非法、`entity.health` 非正数等）：出现在生成之后，体检里会提示「先 spawn 一次再跑本命令」

其中 `entity.health: 0` 此前会抛 `IllegalArgumentException: Max health amount must be greater than 0` 并穿透命令处理器——控制台/玩家看到的是「命令没反应」，日志里只有一段 Paper 堆栈。现在会兜住并告警，同时说明「想表达打不死请用别的方式」。

### 重载技能配置

`skills.yml` 是独立文件，`reload config` 与 `reload loot` 都不覆盖它，必须单独重载：

```
/helstera reload skills      # 只重载 skills.yml
/helstera reload all         # 含 models / config / loot / spawners / skills
```

```
/helstera skill list                      # 列出所有命名技能
/helstera skill info <技能名>               # 看条件条数、优先级、动作列表
/helstera skill preview <技能名> [血量%]      # 逐条条件求值 + 会不会触发
```

输出示例：

```
预览 fire-blast @ 血量 20%
  ✓ health-below:0.3
  ✗ on-ground
结论: 不会触发 — 未满足 on-ground
提示: 调高 /helstera skill preview 的血量% 参数试试哪一档开始满足
```

**预览绝不执行动作**：在真 Boss 上放一遍 AoE 会造成真实伤害。答案是「会不会触发」，不是「触发后打多少伤害」。

**`✗` 的两种含义**：标量条件（`health-below` / `state-is`）不满足说明配置或时机不对；世界状态条件（`on-ground` / `in-water` / `has-target`）在预览里恒为 `✗`，因为它需要真实实体——这类的 `✗` 含义是「需上真服验证」，不是配置错误。

网页端对应接口：`POST /api/skills/preview`，参数 `{name, health}`（血量接受 `0..1` 与 `0..100` 两种量纲）。

技能面板下方有预览入口：选技能 + 填血量百分比 → 点「预览」，直接看到逐条条件的 `✓` / `✗` 与结论。

**网页端端点双向对账**：`WebEndpointAuditTest` 同时检查两个方向 —— 前端调了服务端没实现（404），以及服务端实现了前端没入口（不报任何错，功能等于没做）。后一类更隐蔽，本项目已因此出现过：做完 `/helstera spawner toggle` 只接了命令行，网页端没有对应按钮。

豁免表 `BRIDGE_ONLY` 刻意保持极短，每条必须写清「为什么前端不需要它」，并有测试保证豁免条目不会随时间膨胀。

## 构建质量门

`./mvnw clean test` 开启编译器全部 lint（`-Xlint:all`）；`./mvnw clean verify` 额外跑 SpotBugs。

本项目此前**没有任何**静态分析环节：无 SpotBugs / Checkstyle / PMD / ErrorProne，编译器也未开 `-Xlint`。javac 不检查的那一类（未使用 import、未使用变量、冗余类型、潜在空指针）只有 IDEA 的 inspection 看得见，CI 与本地构建全都漏掉 —— IDEA 面板里长期挂着 160+ 个问题就是这么来的。

### 网络前提（重要）

本机 JDK 连不上 `repo.maven.apache.org`（`HttpConnectTimeoutException`，而 PowerShell 能通）。因此：

```bash
# 日常构建（依赖已缓存）：直接用
./mvnw -o clean test

# verify（SpotBugs）：必须指定镜像配置
./mvnw -s tools/settings-ci.xml clean verify
```

`tools/settings-ci.xml` 里 `mirrorOf` 必须写成 `*,!papermc`：Anthropic公共仓库不代理 PaperMC 的内容，写成 `*` 会让 `paper-api` 及其传递依赖解析失败。

> **`.gitignore` 里的 `tools/`**
>
> 这个目录**不能**整体忽略，但也不能整体放开：里面的 `settings-ci.xml` 与
> `spotbugs-exclude.xml` 是构建必需（`pom.xml` 用
> `${maven.multiModuleProjectDirectory}` 引用后者），而 `tools/jdk25/` 下
> 是当初为跨版本测试下载的 380MB JDK。
>
> 历史上有过整目录被 ignore，导致两个配置文件**从未入库** —— 本地构建正常，
> clone 下来直接失败，而 git 不会给出任何提示。因此改为逐项排除。
>
> 若你看到 `mvn -s tools/settings-ci.xml` 报「找不到文件」，先确认它已入库：
> `git ls-files tools/` 应当列出这两个文件。

### 前端双副本

`frontend/index.html` 与 `helstera-web/src/main/resources/web/index.html` 是同一份文件的两个副本，**后者才是打包进 jar 的那份**。

- `WebEndpointAuditTest` 在构建时校验两份内容一致。
- `node frontend/sync-back.cjs` 用于同步。脚本按**修改时间**推断方向，并支持 `--to-real` / `--to-edit` 强制指定；时间戳接近（无法判断）时以退出码 3 **中止**，而不是随便挑一个方向覆盖。

第二点是加固后的行为。旧实现无条件用编辑副本覆盖正式源 —— 一旦有人直接改了正式源（正确的改动位置），下次跑脚本就把改动静默覆盖掉，构建照样通过。

### -Xlint 基线

| 警告 | 数量 | 处置 |
| --- | --- | --- |
| Paper 标记的 removal API | 68 | 刻意不清 |
| 文本块在参数位置的 javac 误报 | 4 | 无需处理 |
| `whenComplete` 签名 | 1 | 正常 API 用法 |
| **本项目自身问题** | **0** | 已清零 |

那 68 条**刻意不清**：替换成新 API 需要反射加版本分支，风险远大于收益；Paper 的 removal 清单在 1.21 仍是「将来」，真要移除时集中迁移一次即可。

**看到警告数突然远超 73，说明引入了新问题。**

没有加 `-Werror`：本项目大量使用 Bukkit API 与反射，JDK 升级时可能冒出新警告。让警告可见但不阻断构建，比因为一条 deprecation 就卡住发布更实际。

### SpotBugs

只让 `threshold=High` 阻断，即 rank 1-9 的高置信度问题（CORRECTNESS / SECURITY 类）。其余 270+ 个实例不阻断 —— 绝大多数无法在不引入反射与版本分支的前提下修复，硬挡只会逼人加排除把工具关掉。

已由它发现并修复的真实缺陷：

- `AiProfile.parseLevels` / `HelsteraPlugin.loadLevelConfigs`：`levels` 配置缺 `property` 时 `property.isEmpty()` **NPE**，整个生物加载失败（同一 bug 复制到两处）
- `HelsteraPlugin.reloadModels`：判空只覆盖了 `clearErrors()`，紧接着 `unloadAll()` 仍 NPE
- `ModelRegistryImpl.hashDirectory`：哈希失败时返回固定 `"unknown"`，导致**所有**失败模型共享同一缓存指纹，改动互相被误判为「未变更」
- `ModelValidator`：纹理路径为根路径时 `getFileName()` 返回 null → NPE

### 排除清单的防腐化

排除清单是静态分析配置里最危险的东西 —— 加条目很容易，累积起来就是「显示全绿、实际什么都没查」。

`tools/spotbugs-exclude.xml` 每条都写了理由，并由 `SpotbugsExcludeAuditTest` 守住：条目必须有注释理由、数量不超过 12 条、禁止 `<Match/>` 屏蔽全部、以及**已修复的 bug 类型不得重新出现在清单里**。

### 资源包构建的原子性

真服日志里每次 `reload` 都会出现**两次**「资源包构建完成」—— 一次 12 个文件、一次 2 个文件。根因是 `/helstera reload all` 里 `buildResourcePack(true)` 与 `reloadModels()` 内部的构建重复，而模型加载是异步的，于是命令那一行执行时模型还没注册完，构建出的是空包。

**危害**：`buildSync` 的 `cleanGenerated -> buildAssets -> zip` 三步没有互斥，`zip` 又是用 `newOutputStream` **截断写** `pack.zip` —— 而那正是玩家通过 web 的 `/pack.zip` 下载的文件。构建中途下载会拿到半成品，客户端解析失败，模型变紫黑方块，而构建本身「成功」，日志里没有任何错误。

三层修复：

1. `reload all` 不再额外调 `buildResourcePack`。下发时机改由 `reloadModels(true)` 在模型加载完成时决定 —— 这样既消除了重复，也修正了顺序（原先是在模型就绪**前**下发）。
2. `buildSync` 加 `synchronized(buildLock)`，并用 `inFlight` 让并发调用复用同一次构建而不是排队重做。
3. `zip()` 改为**先写 `.building` 临时文件再 `ATOMIC_MOVE` 改名**。任何时刻打开 `pack.zip` 看到的都是上一次完整构建的产物；文件系统不支持原子改名时降级为普通替换并告警。

`ResourcePackConcurrencyTest` 覆盖产物完整性与无残留。**该测试是回归防护而非精确竞态检测器** —— 两个线程恰好不重叠时删掉锁也能通过；真正的确认来自真服日志（修复后每次 reload 只剩一次构建，且均为 12 文件）。

## Boss 血条渐变配色

`bossbar.gradient: true` 让血量在相邻档位之间线性插值，而不是到点跳变。

```yaml
bossbar:
  enabled: true
  gradient: true
  thresholds: [0.0, 0.5, 1.0]
  colors: [RED, YELLOW, GREEN]
```

**必须知道的限制**：Bukkit 的 `BossBar` 只接受 `PINK` / `BLUE` / `RED` / `GREEN` / `YELLOW` / `PURPLE` 六档，没有任意颜色通道。因此渐变是**插值后取最近可用色**，实际观感是在六个采样点之间跳变，而不是连续的橙→红过渡。写更多中间档位只会让跳变点更密，不会得到真正的连续渐变。

其余规则：
- 档位少于 2 时 `gradient` 自动失效并告警（无区间可插值）。
- 低于最低档下界时取最低档色，**不外推**——作者只声明了两点，外推会造出没配过的颜色。
- 空颜色名档位被跳过，不参与插值。

## 刷怪点运行期开关

```
/helstera spawner toggle <id>     # 启停单个刷怪点，无需改文件
/helstera spawner list            # 每行显示 [启用] / [停用]
```

- 运行期状态与 `spawners.yml` 的 `enabled` **分开存储**，不互相污染：`toggle` 只叠加在 YAML 之上。
- 对 YAML 里本就 `enabled: false` 的点，`toggle` 会明确提示「清除运行期开关但仍不产出，要启用请改 spawners.yml」，而不是骗你说「已启用」。
- `/helstera reload spawners` 会清空运行期开关，回到 YAML 的值。
- 已产出的生物不受影响，只影响后续产出。

**网页端**：刷怪点面板每条右侧有「启用 / 停用」按钮，与命令同一套语义，不改文件。面板同时提供 spawners.yml 编辑区（保存走 `/api/spawners/save`，带 YAML 语法校验与版本备份）。

列表接口的 `enabled` 是「现在会不会产出」，`yamlEnabled` 是 YAML 里的原始值 —— 两者不同说明是运行期开关关掉的。

### 关于 `on-entity-shoot`

该触发器**可用**：载体的 `entity.type` 可以是 `player`，这类载体确实能射箭，
由 `EntityShootBowEvent` 驱动。早期版本曾因「模型实例不会自己发射弹丸」
而标为未接线，导致体检命令劝服用户放弃一个本来能用的机制——现已修正。

## 许可证

见 [LICENSE](LICENSE)。
