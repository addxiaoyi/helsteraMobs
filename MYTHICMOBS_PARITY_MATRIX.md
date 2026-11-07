# MythicMobs Parity Matrix

This matrix records the current compatibility boundary. A passing unit test does
not prove Paper runtime parity.

Status values:

- `implemented-runtime`: runtime path exists and focused tests cover it.
- `mapped-unverified`: configuration/import mapping exists, but Paper runtime behavior is not verified.
- `unsupported-preserved`: the source value is retained with a migration warning.
- `missing`: no meaningful implementation exists.
- `blocked-by-external-plugin`: behavior requires a Mythic add-on or another external plugin.

Evidence baseline: 2026-11-07, Paper API 1.21.1-R0.1-SNAPSHOT.

Snowing condition update: `snowing` and `isSnowing` map to a target-location
runtime check using world storm state and Paper's height-adjusted block
temperature (`< 0.15`). Unit tests cover target precedence, clear weather,
warm locations, missing context, and invalid arguments; strict Paper evidence
passed the required `snowingCondition` probe in strict Paper fixture
`srv-paper-snowing-strict-20261105-r2`. Bukkit exposes no public local precipitation
type query, so a custom cold biome configured without precipitation can still
be classified as snowing. This boundary is not claimed as complete equivalence.
The names and target-location semantics follow the [MythicCraft changelog](https://wiki.mythiccraft.io/mythicmobs/changelogs/Changelogs?version_id=da835bfecc765c07062dab44968b975b4d0e5644);
Paper's [Block API](https://jd.papermc.io/paper/1.21.1/org/bukkit/block/Block.html)
documents the height-adjusted temperature used by the evaluator.

The supported-version declaration is machine-readable at
`tools/paper-integration/version-matrix.json` and is checked by
`tools/paper-integration/test-version-matrix.ps1`. Only Paper 1.21.1-133 on
Minecraft 1.21.1 has strict release validation; Paper 1.21.4 and 1.21.11 are
exploratory entries, and unlisted combinations remain unverified.

Incremental condition grammar update: Mythic condition parameter blocks with
unbalanced braces are now kept verbatim and reported as untranslated instead
of allowing a malformed presence condition such as `isFrozen{` to map as a
valid no-argument condition. Balanced nested values remain accepted for
placeholder syntax. `MmConditionsTest` covers missing, extra, and unbalanced
braces plus a nested placeholder value; the importer continues to preserve
unknown condition source text with a migration warning.

Importer mappings that take no arguments now reject non-empty argument blocks
instead of silently discarding them. `MmConditionsTest` covers the parameterless
aliases' normal form, accepted empty block, and unknown-argument rejection.
This closes the parser boundary for currently mapped no-argument conditions;
it does not verify the complete official condition catalog or parameter schemas
for conditions that accept values.

Mythic condition-list `true`/`false` actions are now consumed only by
`translateAll`: a final top-level `true` keeps the condition, while `false`
inverts it and composes with an existing `!`. Unknown action text remains
preserved and warned. The `on-fire` runtime condition now checks the active
target first, then falls back to the caster, so imported `TargetConditions`
evaluate the entity Mythic specifies. `MmConditionsTest` covers action parsing
and inversion; `MigrationRuntimeConditionContractTest` imports `isburning false`
and `burning true` and verifies both states through `SkillService`. Strict
Paper 1.21.1-133 fixture `srv-paper-condition-action-suffix-strict-20261106-r1`
also verifies the imported action suffixes, target priority, caster fallback,
and invalid-suffix preservation. The complete Conditions catalog remains
`mapped-unverified`.

`distanceFromLocation` and `originLocation` now validate named argument schemas:
unknown keys, duplicate aliases, empty fields, and mixed `loc` plus `x/y/z`
forms remain untranslated. `MmConditionsTest` covers these importer failures.
The existing OriginLocation Paper fixture proves runtime matching and prior
malformed-value cases; it does not contain these newly added parser probes.

Incremental condition evidence: `distance-from-spawn` has unit coverage for an
exact 3-4-5 distance, inclusive/exclusive comparator boundaries, invalid
numeric input, and missing location context. Strict Paper 1.21.1-133 fixture
`srv-paper-distance-from-spawn-strict-20261102-r1` checks the production
condition service against a real entity and the world's spawn location; the
inclusive exact boundary passes and the exclusive over-boundary check rejects.
The full Conditions catalog remains `mapped-unverified`.

Location targeter validation: `@location` accepts finite `x`, `y`, `z` and an
optional `world`; malformed, duplicate or unknown fields and trailing text are
rejected. API tests cover parsing, and `SkillActionsTest` checks valid and
invalid destinations through the `teleport`, `teleport-caster` and
`teleport-targets` consumers. The official context-based location targeter
slice is listed below; other official location targeters and broader
importer-to-runtime behavior remain unverified.
`setblock{material=STONE} @location{x=4;y=5;z=6}` is mapped to `set-block`
with its location targeter preserved. SkillService dispatches that location
directly to the block action without selecting nearby entities; importer and
runtime unit tests cover the path and invalid-coordinate rejection. Strict Paper
fixture `srv-paper-location-dispatch-strict-20261104-r2` verifies block dispatch
and rejection of a duplicate non-finite coordinate; it does not exercise every
invalid input or the teleport-action consumers.
Subsequent source changes dispatch `@VariableLocation` to location-aware actions
and reject duplicate, unknown or malformed arguments. Unit tests cover skill-scope
resolution, block dispatch, missing variables and missing worlds.
`MigrationRuntimeConditionContractTest` also imports and executes a `setblock`
line with `@VariableLocation`. The importer uses the same syntax validation as
runtime, preserving malformed lines and issuing a warning; standalone skill and
mob-event tests cover this behavior. Strict Paper fixture
`srv-paper-variable-location-strict-20261104-r1` verifies dispatch, duplicate
argument rejection, and migration preservation with a warning. Other scopes,
argument variants and imported action execution are not covered by this probe.
Later source changes add official `@varLocation` and variable-name aliases
`variable`, `name`, `n`, `var`, `key`, `k`, plus scope alias `s`. API and
importer-to-runtime tests cover the aliases and reject conflicting aliases;
the Paper probes do not individually exercise every alias. The official
`@Location` now supports zero coordinate defaults, five-component location
tuples, yaw/pitch, `world` alias `w`, and targeter aliases `l`/`loc`. API tests
cover valid and malformed coordinates; SkillService and importer-to-runtime
tests cover the tuple alias. The Paper probes do not exercise every tuple or
alias; general location targeter modifiers remain unverified.
The resolver handles official `@SelfLocation` aliases (`@casterLocation`,
`@bossLocation`, `@mobLocation`) and `@SelfEyeLocation` aliases (`@eyeDirection`,
`@casterEyeLocation`, `@bossEyeLocation`, `@mobEyeLocation`). SelfEyeLocation
requires a living caster and fails closed for missing or non-living caster
context. API and importer tests cover all five aliases and invalid arguments;
strict Paper fixture `srv-paper-self-eye-location-strict-20261104-r1` exercises
real `set-block` dispatch through each alias. The resolver also handles
`@TargetLocation` aliases (`@targetloc`,
`@TL`), `@TargetPredictedLocation` aliases, `@TriggerLocation`,
`@SpawnLocation`, `@CasterSpawnLocation`/`@casterSpawn`, `@Forward`, and
`@ProjectileForward`. Forward targeters apply their documented distance,
rotation, eye-location and pitch options; predicted target locations use target
velocity, the configured tick horizon and max-distance cap, and stop at a block
ray trace. The runtime resolver and importer share strict targeter argument
validation, and `SkillService` dispatches resolved positions to location-aware
mechanics. API tests cover aliases, context resolution, movement, caps and
fail-closed arguments; importer tests cover preservation and invalid syntax;
`SkillServiceTest` verifies a Forward target reaches `set-block`. Strict Paper
fixture `srv-paper-self-eye-location-strict-20261104-r1` passed 339 probes on
Paper 1.21.1-133; its `locationTargeterDispatch` probe verifies the real block
action for SelfEyeLocation and SelfLocation aliases, caster/world spawn, explicit non-player target,
trigger, forward, a moving Arrow projectile, and velocity-predicted target
locations. Invalid coordinates remain rejected. Player `TargetLocation` ray
tracing is implemented; Mythic's threat/combat/last-damage fallbacks, additional
location modifiers and full mechanic-by-mechanic parity remain unverified.
The importer now shares the explicit `@Location` syntax validator with runtime;
malformed standalone skill and mob-event lines are preserved with warnings.
This importer validation is covered by automated tests, not the archived Paper fixture.
Imported `explosion{yield=3} @location{x=4;y=5;z=6}` now dispatches the
explosion at the selected location instead of the caster origin; an
importer-to-runtime contract verifies the world call. A separate imported
`@VariableLocation` test covers a stored position and confirms a missing value
does not cause an explosion at the origin. Paper fixture
`srv-paper-explosion-location-20261104-r3` observes the block explosion event
at the selected position and no event for a missing variable; the listener
cancels the event. The probe does not cover imported execution on Paper or
uninterrupted block damage. Broader explosion parity remains open.
The native `drop-item` action now consumes an explicit location target, and a
missing location variable produces no drop. `SkillServiceTest` and
`MigrationRuntimeConditionContractTest` cover both through the imported
`dropitem{item=DIAMOND;amount=3}` mapping and runtime consumer. Paper fixture
`srv-paper-drop-location-20261104-r2` observes the native action spawning the
requested item at the selected location and rejects zero count and a missing
location variable. Fixture `srv-paper-drop-import-20261104-r1` imports and
executes a Mythic skill on Paper, checks the missing variable, and verifies
preservation and warnings for zero amount and an unknown argument. Other
official parameters remain unverified.
Native `drop-item` now accepts only finite positive integer counts at execution;
zero, negative, fractional, overflow and unresolved placeholder counts do not
produce a drop. The importer preserves static invalid counts with warnings in
standalone skills and mob events; placeholder-backed counts are checked at
execution. Empty, repeated and conflicting amount aliases are also preserved
with warnings. Missing, empty or conflicting item/material identifiers are
preserved rather than being mapped to an unusable action. These checks have
automated coverage; the Paper import probe covers zero amount. Unmapped `dropitem` arguments are
preserved with a migration warning instead of being silently discarded;
delay/repeat execution options remain mapped when valid under current runtime
rules. Empty, malformed, conflicting and overflowing execution options are
preserved with warnings; official timing parity and Paper evidence remain open.
Official `items` and `i` aliases now import a single item through the same
runtime path as `item`; importer-to-runtime tests cover both aliases. Invalid
inline counts, empty list entries and conflicting item aliases are preserved
with a warning instead of silently truncating the value. A single inline count maps
to the existing count consumer; strict fixture `srv-paper-drop-inline-20261104-r2`
checks fixed and skill-variable counts, missing variables and invalid count
preservation. `naturally` and aliases
`natural`/`n` select natural or exact placement and default to natural;
invalid or conflicting values are preserved with warnings. Unit tests cover
caster and entity targets. Fixture `srv-paper-drop-natural-20261104-r1` imports
`i` and `n=false`, observes an exact-location drop, and checks invalid-flag
preservation. Comma-separated item lists execute as one action with per-item
counts; fixture `srv-paper-drop-list-20261104-r1` verifies two actual drops and
invalid-list preservation. Unresolved dynamic counts skip their own item; this
is unit-tested. Fixture `srv-paper-drop-list-comma-20261104-r1` verifies a
comma-separated `n=false` parameter after a list and preserves an invalid flag.
List-specific DropTable references, ambiguous global `amount`, `onDropSkill`,
`dynamic` and `owner` still require implementation and validation.
References: https://wiki.mythiccraft.io/mythicmobs/Skills/Targeters/VariableLocation
and https://wiki.mythiccraft.io/mythicmobs/Skills/Targeters/Location;
drop-item attributes: https://wiki.mythiccraft.io/mythicmobs/Skills/Mechanics/dropitem.

Mythic `teleport`/`tp` is preserved as unsupported with a migration warning:
the official mechanic moves the caster to the selected location and defines safe
landing, spread and orientation behavior. The former mapping to
`teleport-targets @target` did not implement that contract. Importer tests cover
standalone and mob-event lines; full mechanic implementation and Paper evidence
remain open. The separately named native teleport actions remain available.
The official defaults are zero horizontal and vertical spread, preserved yaw
and pitch, and safe landing enabled; `unsafe` bypasses safe landing and spread.
Reference: https://wiki.mythiccraft.io/mythicmobs/Skills/Mechanics/teleport.

Incremental targeter update: `@PlayersInWorld` now selects all valid online
players in the caster's world without applying implicit faction filtering;
`targetself=true` opts the caster in. `@PlayersOnServer` selects valid online
players across worlds, with `@server` and `@everyone` aliases. Unit and
importer tests cover world isolation, online/valid/dead boundaries, caster
inclusion, and alias normalization. Strict Paper 1.21.1-133 fixture
`srv-paper-targeter-players-strict-20261102-r1` observes the production
`ai-selector-core` path and verifies the server-wide player set and alias
equivalence; its archived report completes 272 probes (260 passed, 12
expected negative controls, zero incomplete). Cross-world live-client target
evidence and the full official targeter catalog remain open.

Incremental targeter-control update: direct context targeters including `@targets`,
`@children`, and `@passengers` now apply generic filter, radius, sorting, skip,
and limit controls instead of bypassing them. `TargeterOrderingTest` covers
combined controls, cross-world radius exclusion, and invalid values. Strict
Paper fixture `srv-paper-context-targeters-strict-20261102-r1` confirms
ordering, radius, and invalid-value rejection with real Zombie entities;
cross-world behavior remains covered by unit tests only.

Context-aware chains now retain the original trigger and condition context in
subsequent stages, intersect direct context targets with the previous stage,
and prevent later world selectors from expanding the set. API tests cover
matching and excluded triggers, inherited targets, and conditional selectors.
Strict Paper fixture `srv-paper-context-chain-strict-20261102-r7` verifies these
paths on real Zombie entities using the production condition provider. It also
exposed and verified a lifecycle fix: `AiManager.start()` reinstalls the
provider after `stop()` clears it; start, restart, and stop have unit coverage.
An additional unit test verifies that stopping an older manager does not clear
a newer manager's provider. The provider remains global, with the most recently
installed provider taking precedence; concurrent manager isolation is not
established by this test or the Paper fixture.
The threat targeter provider now also unregisters only its owning manager on
stop and is reinstalled on start. Unit tests cover restart, missing-provider
fail-closed behavior, and an older manager stopping after a newer registration;
the provider is still global. Strict fixture
`srv-paper-threat-provider-strict-20261102-r1` verifies production threat
sorting, faction filters and actions after startup using synthetic damage
events and real entities; it does not exercise restart or overlapping managers.

Generic targeter parameters support `skipTargetsUpToIndex` and alias `stuti`.
Selection ordering is explicit:
filter and sort candidates, skip the first N entries, then apply `limit`;
negative and malformed skip values fail closed. API tests cover the full name,
alias, out-of-range skip, and invalid values; importer tests verify that `stuti`
is preserved. Strict Paper 1.21.1-133 fixture
`srv-paper-targeter-skip-index-strict-20261102-r3` observes nearest-order skip
and alias equivalence through the production targeter path. Its archived
271-probe report has 259 passed, 12 expected negative controls, and zero
incomplete. The full targeter catalog and cross-world live-client coverage
remain open.

Incremental targeter-sorting update: generic `{sort=...}` now supports
`NONE`, `RANDOM`, `NEAREST`/`CLOSEST`, `FURTHEST`, `HIGHEST_HEALTH`,
`LOWEST_HEALTH`, `HIGHEST_THREAT`, `LOWEST_THREAT`, `OLDEST`, and `NEWEST`.
Unknown modes and missing or non-finite threat data fail closed. API coverage
checks ordering and rejection boundaries; strict Paper 1.21.1-133 fixture
`srv-paper-targeter-sorting-strict-20261102-r1` verifies real health and age
ordering and unsupported-mode rejection. Its archived report has 272 probes,
260 passed, 12 expected negative controls, and zero incomplete. Complete
targeter parameter and cross-world live-client coverage remain open.

Targeter argument parsing now rejects empty top-level semicolon clauses,
unmatched quotes and mismatched nested delimiters instead of silently dropping
malformed fragments. `TargeterOrderingTest` covers these fail-closed cases;
valid targeter paths retain the existing Paper evidence above. Full strict
fixture `srv-paper-targeter-syntax-20261104-r2` adds a Paper runtime assertion:
`@targets{limit=1;;}` returns no targets and valid `@targets{limit=1}` selects
the real online player. The release probe catalog requires the
`targeter-syntax=true` observation. Unmatched quotes and other delimiter errors
remain covered by API tests.

Incremental damage-tag update: Mythic `DamageTag`/`DamageHasTag` aliases now
map to a fail-closed `damage-tag` condition with the documented default
`value=true` and strict boolean parsing. `damage-target` scopes normalized
`tags`, case-preserving `rawtags`, and normalized `damagetype` values to the
matching victim's synchronous Bukkit damage event; the nested SkillTreeContext
snapshot also preserves tags, while older YAML snapshots default to no tags.
Migration, runtime, scope-isolation, and snapshot tests pass. Strict Paper
fixture `srv-paper-damage-tag-strict-20261102-r3` proves the full mechanic to
trigger path on Paper 1.21.1-133: an untagged event is rejected, a tagged
`damage-target` event passes `damage-tag`, cause, and final-damage checks, then
the configured action equips the probe and cancels the event. The shared-entity
event router now selects bindings that declare the specific `on-damage` or
`on-attacked` trigger. This capability has strict runtime evidence, while the
overall Conditions catalog remains `mapped-unverified`.

Incremental mob-option update: Mythic `Options.MaxCombatDistance` is mapped to
the per-mob AI profile and cancels melee damage beyond the configured radius.
Strict Paper fixture `srv-paper-mob-max-combat-distance-20261101-r5` exercises
the production spawn path and Bukkit damage dispatch at 7 and 9 blocks around
an 8-block limit; the in-range hit is allowed and the out-of-range hit is
cancelled (`maxCombatDistance=passed`).

Incremental legacy-option update: Mythic `Options.SpawningDistance` is retained
as `mob.spawning-distance` and consumed as the normal despawn radius fallback.
Strict Paper fixture `srv-paper-mob-spawning-distance-20261101-r2` reads the
production instance as `mode=NORMAL distance=8.0`. Follow-up strict fixtures
`srv-paper-spawning-distance-runtime-20261101-r4` and
`srv-paper-spawning-distance-runtime-20261101-r5` verify a real-player
nearby-retention check and the production AI scheduler removing both the
managed instance and Bukkit entity after it moves beyond the configured radius.
The latest r5 report records 254 observed probes with zero incomplete.
Strict client fixture `srv-paper-spawning-distance-client-walk-strict-20261102-r3`
then uses a Mineflayer player that moves 14.01 blocks; Paper observes 8.47
blocks of displacement and confirms both the managed instance and Bukkit
entity are removed. Its 264-probe report passes schema and release completeness
gates with 253 passed, 11 expected negative controls, and zero incomplete.
The latest full strict release fixture
`srv-paper-entity-type-variant-import-strict-20261106-r1` completes 364 probes
(345 passed, 19 expected rejections, zero incomplete). Its required
`importedEntityTypeVariantCondition` probe verifies `entityType`,
`entityTypeIs`, and `mobtype` against a real Cat, including variant alternatives,
wrong-variant/type rejection, and malformed/unsupported selector preservation.
The previous strict `srv-paper-entity-category-import-strict-20261106-r2`
fixture verifies `IsWaterMob`/`IsZombie` primary and alias paths against real
Cod/Zombie targets, both cross-type negatives, and malformed-argument
preservation. The previous strict
`srv-paper-condition-action-suffix-strict-20261106-r1` fixture verifies
imported Burning actions, target priority, caster fallback, and invalid-suffix
preservation. Earlier strict fixtures retain imported Survival-mode, Spyglass,
and frozen-Zombie evidence.
Earlier strict fixtures retain the real player movement and managed/Bukkit
entity removal evidence for `spawningDistanceClientWalk`.

Incremental condition-boundary update: `compare-values` now rejects `NaN`,
`Infinity`, and `-Infinity` for DOUBLE/FLOAT/NUMBER comparisons instead of
letting `Double.compare` treat them as ordered values. The new regression test
passes alongside the full `SkillConditionsTest`; this improves fail-closed
coverage but does not establish Paper or complete-catalog parity.

Incremental radius-condition update: `PlayersInRadius`, `EntitiesInRadius`,
`MobsInRadius`, `LivingInRadius`, `PlayerWithin`, and `PlayerNotWithin` now
reject negative, non-finite, or malformed radii during migration. Their count
and amount arguments also reject non-finite and malformed numeric ranges,
preventing invalid input from being silently projected into a runtime default.
`MmConditionsTest` covers the fail-closed cases; the full Conditions catalog
and a dedicated Paper radius fixture remain open.

Runtime parameter-boundary update: the shared `SpecArgs.num` loader now rejects
`NaN` and infinities before any condition or mechanic factory captures them.
`SpecArgValidationTest` covers the load-time rejection and warning path for
skill arguments; this closes a parser boundary across direct skill configs but
does not replace per-condition runtime and Paper evidence.

Runtime radius-condition update: the `SkillExtras` implementations for
`players-in-radius`, `entities-in-radius`, `mobs-in-radius`, `mobs-near-origin`,
and `living-in-radius` now reject negative or non-finite radii. The three
minimum-count variants also require a non-negative integer count instead of
silently truncating negative or fractional values. `SpecArgValidationTest`
covers these factory-boundary failures; the full Conditions catalog and a
dedicated Paper radius fixture remain open.

Incremental block-condition update: Mythic `BlockType`, `OnBlock`, and
`BlockTypeInRadius` now preserve exact material lists, Bukkit block tags
(`#namespace:tag`), and `*` material-name globs through migration. Runtime
resolves tags from Paper's block registry and fails closed for malformed or
unknown tags. Strict fixture `srv-paper-block-selector-strict-20261101-r2`
passes the production spatial condition probe with isolated real Paper tag and
wildcard checks (`on-block-tag=true block-wildcard=true block-tag=true`).

Incremental block-condition importer validation: `BlockType`, `OnBlock`, and
`BlockTypeInRadius` now validate their documented material/radius/amount
arguments, reject unknown keys, duplicate aliases, empty values, and options
used by the wrong condition, while retaining positional material lists and
block-state selectors. `MmConditionsTest` covers these accepted and rejected
forms. This adds importer-only evidence; the existing Paper block-selector and
radius fixtures do not exercise the new schema-rejection probes.

Incremental spawner-gate update: player-scoped ownership now applies the same
online, alive, non-spectator, non-creative filter as the player-count gate and
fails closed when a player location is unavailable. Focused regressions and the
full `SpawnerServiceTest` pass; natural spawn timing and broader Paper
combinations remain unverified.

Incremental player-mechanic update: official `RemoveHeldItem` now has a
dedicated canonical action and retains `disarm` as a compatibility alias. It
removes only the active target's main-hand item. `SetMaterialCooldown` now
applies a non-negative Bukkit material cooldown to target players, and
`SetGameMode` now applies named or numeric (`0..3`) Bukkit game modes to target
players. Importer mappings, target-aware runtime tests, and integration probes
cover all three paths. The strict online-player probe verifies `RemoveHeldItem`
clears the main hand while preserving the offhand. Exploratory Paper 1.21.4 fixture
`srv-paper-integration-exploratory-20261019-jump` (r128) records
`materialCooldownMechanic=passed` and `setGameModeMechanic=passed`; the strict
Paper 1.21.1-133 fixture `srv-paper-integration-size-range-strict` also records
both probes as passed; the same strict fixture records
`removeHeldItemMechanic=passed`.

The same cooldown runtime also accepts the official `SetItemCooldown` naming
aliases (`setitemcooldown`/`set-item-cooldown`) and the importer preserves them
as the canonical material-cooldown action. Exploratory Paper 1.21.4 fixture
r132 executes the alias successfully.

Incremental movement-mechanic update: official `Jump` now raises the caster or
selected targets while preserving horizontal velocity, and `ForcePull` now
sets selected targets' velocity toward the caster with optional vertical
override. `leap` and radial `pull` retain their existing semantics. Importer,
targeter, unit, self-test, and report-contract coverage are included. Fresh
Paper 1.21.4 exploratory fixture r128 and strict Paper 1.21.1-133 both record
`jumpMechanic=passed` and `forcePullMechanic=passed`.

Incremental equipment-mechanic update: official `SetOffhand` now reuses the
resolved ItemStack path to write `EquipmentSlot.OFF_HAND` on the caster or
selected targets. Importer, targeter, unit, self-test, and report-contract
coverage are included. Exploratory Paper 1.21.4 fixture r129 and strict Paper
1.21.1-133 both record `setOffhandMechanic=passed`.

Incremental equipment-mechanic update: official `SetArmor` now writes resolved
helmet, chestplate, leggings, and boots items through Bukkit armor slots;
`AIR`/`NONE` values clear a slot and targeter execution is supported. Importer,
unit, self-test, and report-contract coverage are included. Exploratory Paper
1.21.4 fixture r130 and strict Paper 1.21.1-133 both record
`setArmorMechanic=passed`.

Incremental inventory-mechanic update: official `ClearInventory` now clears
target players through `PlayerInventory#clear`, preserving targeter semantics.
Importer, unit, self-test, and report-contract coverage are included.
Exploratory Paper 1.21.4 fixture r131 and strict Paper 1.21.1-133 both record
`clearInventoryMechanic=passed`.

Incremental experience-mechanic update: official `GiveExperience` now grants
raw Bukkit experience points through `Player#giveExp`, separate from
`GiveExperienceLevels`. Importer, binder, unit, self-test, and report-contract
coverage are included. Exploratory Paper 1.21.4 fixture r136 records
`rawExperienceMechanic=passed`; strict Paper 1.21.1-133 also records
`rawExperienceMechanic=passed`.

Incremental absorption-mechanic update: official `SetAbsorption` now writes
target Bukkit absorption amounts through `Damageable#setAbsorptionAmount` and
raises Paper's `MAX_ABSORPTION` capacity when necessary so the requested value
is not clamped to zero. This remains separate from the `Absorption` condition.
Importer, binder, unit, self-test, and report-contract coverage are included.
Exploratory Paper 1.21.4 fixture r8 records
`setAbsorptionMechanic=passed`; strict Paper 1.21.1-133 also records
`setAbsorptionMechanic=passed`.

Incremental faction-mechanic update: official `SetFaction` now delegates
caster and targeter faction assignment to the runtime `FactionService` bridge;
`none`/`clear` removes the binding. Importer, binder, unit, self-test, and
report-contract coverage are included. Exploratory Paper 1.21.4 fixture r133
records `factionMechanic=passed`; strict Paper 1.21.1-133 also records
`factionMechanic=passed`.

Incremental level-mechanic update: official `SetLevel` now updates the managed
instance level used by `Level` conditions and AI damage/speed scaling.
Generation-time health scaling is intentionally not recomputed by the action;
this preserves the existing spawn-time scaling contract. Importer, binder, unit,
self-test, and report-contract coverage are included. Exploratory Paper 1.21.4
fixture r137 and strict Paper 1.21.1-133 both record
`levelMechanic=passed`.

Incremental world-mechanic update: official `SetWeather` now supports clear,
rain, and thunder modes with optional Bukkit weather durations; `SetTime`
supports named day-cycle values and numeric/placeholder ticks. Importer, unit,
self-test, and report-contract coverage are included. Exploratory Paper 1.21.4
fixture r134 and strict Paper 1.21.1-133 both record `weatherMechanic=passed`
and `timeMechanic=passed`.

Incremental world-effect mechanic update: official `Lightning` now strikes the
active target locations or execution origin through Bukkit, with an
effect-only/visual mode and targeter support. Importer, unit, self-test, and
report-contract coverage are included. Exploratory Paper 1.21.4 fixture r135
and strict Paper 1.21.1-133 both record `lightningMechanic=passed`.

The `strike-lightning-effect` compatibility alias now always uses Bukkit's
visual-only lightning API, including after migration; `strike-lightning` keeps
the damaging strike behavior. Regression tests cover both paths; an
importer-to-runtime contract also verifies selected-target execution and that
an empty explicit target does not strike the origin. This alias correction has
not yet been verified on a running Paper server.

Incremental mob-drop update: Mythic root `Exp` now maps to a managed instance
experience override, while explicit `drops.experience` and
`drops.experience-min/max` values take precedence over the root default. The
death hook applies fixed or inclusive ranged values even when no loot table is
configured or the referenced table is missing. Importer, overflow-safe range,
drop-policy, and report-contract tests are included. Strict Paper 1.21.1-133
fixture `srv-paper-integration-exp-20261031-r16` records the synthetic
`exp-options result=true experience=13 event=true` probe. Follow-up strict
fixture `srv-paper-exp-natural-death-20261101-r3` separately applies Bukkit
damage to a production managed Zombie and observes `dead=true experience=13`
from newly spawned experience orbs. Its 255-probe release report has 243
passed, 12 expected negative controls, and zero incomplete. This verifies the
root `Exp` natural-death path. Follow-up fixture
`srv-paper-drops-natural-death-20261101-r4` records a second real Bukkit
damage death using `integration_death`: the condition-matched Diamond stack
spawns, the rejected Bread entry stays absent, and 9 experience points are
spawned from the table's inclusive 9..11 range. Its 255-probe release report
also has zero incomplete. Natural DropTable death is now covered for this
condition/range case; looting interaction, broader placeholder expressions,
and the full official drop catalog remain incomplete.

Dynamic DropTable chance evaluations that return NaN or infinity now fail
closed for ordinary item drops, weighted selection, and experience entries.
Native loot parsing also rejects explicit null,
blank, non-string and conflicting dynamic chance expression aliases instead of
falling back to the static chance. `LootServiceTest` exercises YAML list and
indexed entries, inherited nulls, explicit overrides and valid siblings. Strict
fixture `srv-paper-drop-dynamic-strict-20261104-r1` verifies those file-loading
and rolling cases on Paper; natural death is not part of that probe.
Native loot validation rejects invalid `min-tier-level` values
instead of ignoring the restriction. `LootServiceTest` covers item and XP
entries, list and indexed YAML, inherited nulls, valid overrides and known-tier
boundaries. Unknown killer tiers retain the existing behavior. Strict fixture
`srv-paper-drop-tier-strict-20261104-r1` verifies native file loading and
rolling; this probe does not exercise natural death.
Drop migration rejects malformed
percent signs in both text and structured chance values, while retaining valid
siblings in the projected table. `MythicDropSpecTest` and
`MythicDropTableProjectionTest`, `ImportersTest` and
`MigrationRuntimeConditionContractTest` cover parsing, migration warnings and
runtime table consumption. `ImportersTest` also covers inline mob drops, including
mixed valid and invalid entries and tables with no safe entries. Strict fixture
`srv-paper-drop-chance-strict-20261104-r1` verifies independent-table import and
rolling for malformed percentages; inline mob drops are not part of that probe.
`LootServiceTest` covers non-finite dynamic chances; no separate Paper fixture
exercises those values.
Native DropTable entries with an explicitly invalid or non-finite static
`chance` are skipped with a diagnostic, including when luck scaling is enabled;
an omitted chance still defaults to one. Unit tests cover this parsing behavior,
but it has not been separately verified on Paper or established as Mythic parity.
Explicit null is detected in YAML list entries before conversion to a Bukkit
configuration section. Native file loading also restores null `chance` in indexed
entries, including YAML merges, so those entries are rejected with a diagnostic;
already parsed configuration sections cannot recover discarded null keys. The
indexed-file behavior has automated tests and a Paper service probe for merged
null item chance and explicit null XP chance; natural death remains untested.
Table-level experience ranges now use overflow-safe inclusive sampling across
the full nonnegative integer range; unit tests cover the maximum boundary.
Amount-tier weights reject non-finite values and use normalized sampling to
avoid overflowing the total weight; deterministic unit coverage checks large
equal weights. This boundary has no separate Paper fixture.
Amount-tier ranges now reject invalid, null, fractional, non-positive,
non-finite, out-of-range and reversed bounds with a warning instead of silently
substituting a different range. Valid sibling tiers remain eligible; when no
valid tiers remain, the existing base-amount fallback still applies. Tests cover
list entries and explicit null in native indexed single-tier files. This change
Strict Paper fixture `srv-paper-tier-range-20261104-r1` verifies invalid list
and indexed ranges, valid sibling selection and the null-indexed base fallback
through native file loading; no natural death event is part of this probe.
Explicit malformed or blank tier weights are also rejected with a diagnostic;
an omitted weight defaults to one. Unit tests cover parsing and selection.
Explicit null weights in list-form tiers are rejected before conversion to a
Bukkit section. Native file loading also restores a null weight in a single
mapping tier under an indexed entry, including a merged null; explicit overrides
and omitted weights retain their usual behavior. When no valid tier remains,
the entry falls back to its base amount. Strict Paper fixture
`srv-paper-native-numeric-20261103-r1` verifies the merged null weight, base
amount fallback, valid overrides, and diagnostics through the server-side
service (305 probes); it does not test natural death for invalid values.
Already parsed sections cannot recover
discarded null keys.
Weighted entry selection also normalizes finite dynamic weights before summing;
`LootServiceTest` covers two equal weights whose unscaled sum would overflow.
This numerical boundary has not been separately verified on Paper.
Migration preview also detects duplicate imported loot table IDs across write
plans, ignoring case, and apply rejects these conflicts before writing. Existing
target tables may still be replaced, including differently cased variants.
Unit tests and an importer-to-apply contract test cover conflict detection,
preservation of the existing file, and successful replacement after resolving
the conflict; this change has no separate Paper fixture.
Migration preview also rejects multiple mob write plans resolving to the same
case-insensitive target file before apply. An importer-to-apply contract test
checks normalized name collisions and preservation of an existing mob file;
reimport replaces the mob definition as a unit after backing up the old file,
so stale entity type and option fields cannot override the imported values. A
cross-module test checks backup and runtime type selection; no separate Paper
fixture covers this. Backup IDs include a unique suffix so rapid successive
imports retain separate snapshots; a cross-module test restores both after a
service restart. Rollback reports file restoration failures. Standalone skill migration now writes
to the runtime's `skills.yml`, replacing imported definitions without stale
fields and rejecting normalized skill ID collisions within and across source
files. An importer-to-apply contract loads the result into `SkillService`; no
separate Paper fixture covers this change. Item, spawner and named-region
normalized ID collisions within a source and across write plans now block apply;
replacing existing definitions preserves unrelated entries. Importer-to-apply
and merge tests cover these cases; no separate Paper fixture covers them.
Existing shared target YAML and generated write plans are parsed strictly before
any write; malformed target YAML aborts migration without overwriting that file
or writing sibling plans. A cross-module test covers this failure boundary.
Migration preview resolves spawner primary and random-pool mob references to
the IDs of mobs included in the same import. Unknown references are preserved
with a diagnostic; colliding resolved pool entries block apply. A cross-module
contract loads the written configuration into `SpawnerService`; no separate
Paper fixture verifies natural spawning through this migration path.
Standalone spawner region files now produce write plans, and `region=`
conditions referencing regions imported in the same batch follow normalized
IDs, including negation. An importer-to-runtime contract checks inside and
outside locations; external region names are preserved. This has no separate
Paper fixture.
Spawner condition parsing now fails closed for blank, non-string, null list
entries and delimiter-only values, even with a permissive external condition
tester; an empty list remains unconditional. Migration preserves malformed
entries as a fail-closed marker and emits an unsupported diagnostic. Unit and
importer-to-runtime tests cover these cases; no separate Paper fixture does.
Native spawner file loading and Mythic spawner import also preserve explicit
null condition fields, including merged YAML values, as fail-closed markers.
File-level and migration-to-runtime tests cover nested and direct roots, valid
overrides, absent fields, and failed reload cleanup. Paper strict fixture
`srv-paper-web-strict-20261103-r1` additionally verifies HTTP spawner reload
failure and recovery on the server thread; browser rendering and loot reload
failure are outside that probe. `srv-paper-spawner-null-20261103-r2` verifies direct and merged native null
rejection and an empty-list override through forced spawn, not natural scheduling
or imported spawners on Paper.
Spawner state loading now detects malformed YAML before Bukkit can silently
return an empty configuration, allowing the existing backup fallback to restore
spawn counters. A unit test supplies a Bukkit server logger to exercise the
production error-handling path. Independent two-boot Paper fixture
`srv-paper-spawner-corrupt-restart-20261103-r1` verifies primary YAML corruption,
backup fallback and restoration of one generated count; scoped counters and
cooldown restoration after corruption remain unverified on Paper.
After a failed spawner file reload, state writes are suppressed until a
successful load, preventing a subsequent reload or shutdown from overwriting
the saved counters with an empty snapshot. A unit test covers failure, retry,
restoration and later writes; this recovery sequence has no Paper fixture yet.
Independent Paper fixture `srv-paper-web-reload-20261103-r2` checks HTTP reload
failure for malformed spawner YAML and successful reload after repair. Server
logs show both reloads on the primary thread; browser rendering, loot failure,
and persisted counters across this particular failure are not covered.
Spawner height, count, time-range, and region conditions now reject invalid
arguments before negation, including unknown regions without an external
resolution; valid negation remains supported. Unit tests cover these paths.
Invalid moon phases, unknown biome enum names, and blank world names likewise
fail before negation; valid named negation is covered by unit tests. Strict
Paper 1.21.1 fixture `srv-paper-named-conditions-strict-20261102-r1` verifies
the three rejections and valid negated world selection through force-spawn;
natural scheduled spawning and valid biome/moon negation remain unit-only.
Spawner player-faction relation conditions now retain the full faction name;
blank arguments for faction equality, allied, and hostile checks fail closed
even when negated. Unit tests cover both relation directions and negation with a
mock faction table; this correction does not yet have a Paper fixture.
Negated light conditions now reject invalid comparisons, unloaded chunks and
light-read exceptions before applying negation; valid negation and `!=`
comparisons have unit coverage. Strict fixture
`srv-paper-negated-conditions-strict-20261102-r1` verifies a valid negated
height condition and rejects invalid negated height and light comparisons on
Paper 1.21.1 (280 probes, 266 passed, 14 expected rejections). Unloaded-chunk
and exception behavior for negated light still has unit coverage only.

Incremental DropTable condition update: table-level `TriggerConditions` are
parsed separately from entry conditions and evaluated against the killer
before that table rolls, including nested tables. Missing killer context or a
missing condition evaluator fails closed. Table-level trigger and dropper
conditions accept a single scalar as well as a list; scalar conditions and
nested tables have unit coverage. Blank or non-string list entries produce a
warning and fail closed even if an evaluator accepts them; empty lists remain
unconditional. This malformed-input behavior has unit coverage only. Drop
entry list maps with explicit `conditions: null` also fail closed for items
and experience. Native `loot.yml` file loading restores explicit null table
conditions and indexed entry conditions discarded by Bukkit, so they fail
closed as well, including conditions inherited through YAML merge keys; explicit
overrides and merge-list priority are respected. Invalid file reloads clear the loaded tables. The
strict Paper fixture `srv-paper-native-null-20261103-r1` verifies inherited null
conditions, indexed entries, experience rejection, and an explicit valid override
through the server-side service (304 probes); it does not test invalid conditions
on natural death. Merge-list priority and failed reload have automated coverage.
Callers
that pass an already parsed configuration section cannot recover discarded nulls.
Table projection also rejects blank, null, or non-string condition entries rather than
discarding them, with a migration warning; this has projection unit coverage.
Standalone tables containing case-insensitive duplicate fields are preserved
without projection so conflicting condition lists cannot be silently ignored.
Unmapped trigger-condition warnings retain the complete source table. Importer
and projection tests cover these cases; Paper validation remains pending.
Drop-entry `conditions`, `DropIf`, and `NoDropIf` reject malformed values, and
a bare inline `?` is rejected instead of losing its restriction; importer unit
tests cover these cases.
Structured drop entries now merge all recognized condition aliases, including
`Conditions`, instead of silently discarding later aliases. Unknown structured
entry fields and unrecognized text before the first inline condition are not
projected; standalone table migration reports the original unsafe entries.
Ambiguous item/table, amount, chance, and expression aliases, as well as blank
or non-scalar recognized values, are also rejected and preserved rather than
silently choosing a value or defaulting an empty chance to one.
When a mob has multiple `DropTable-1`, `DropTable1`, or `Drops` sources, all
are retained in `mob.unsupported.Drops.conflicting-sources` with a warning and
none is projected; the combined source semantics remain unverified. External
drop dependencies are still reported. Importer tests cover preservation and
the absence of generated loot; no Paper fixture covers this migration case.
Parser, projection, and importer tests cover these cases. A cross-module test
also loads the migrated table into `LootService` and rejects a roll when any
merged condition fails or the evaluator is absent; it uses a controlled
evaluator, and no Paper fixture verifies this migration change.
Standalone table import now validates original YAML drop objects when recording
unsupported entries, avoiding false warnings for mapped entries; a file-level
import test covers mixed valid and invalid mappings and the report contents.
Applying an imported loot table replaces that table as a unit, removing stale
entries and conditions while retaining other tables; this merge behavior has
unit coverage and no separate Paper fixture.
Experience uses the same top-level trigger gate, while a rejected nested child does not erase experience
from an accepted parent. The context-free experience overloads also fail
closed when killer or dropper conditions require unavailable context; this
overload behavior has unit coverage, not separate Paper evidence. Focused
`LootServiceTest` covers accepted, rejected,
nested, and unavailable-evaluator paths. Strict fixture
`srv-paper-drops-trigger-conditions-20261101-r5` uses natural player-caused
deaths to verify a passing nested table, a rejected nested table, and zero
experience for the rejected parent table.
Native loot entries also accept a fixed `amount` for items and XP, with
`amount-min` and `amount-max` taking precedence. Unit tests cover fixed values,
ranges and invalid values. Paper fixture `srv-paper-fixed-amount-strict-20261102-r1`
observed 4 Bread and the expected tiered XP in both real Bukkit-damage death
events. Its overall strict gate failed because the separate delayed
`deathLootExperience` orb observation recorded 8 XP; it is not release evidence.
Strict fixture `srv-paper-visibility-arguments-20261103-r1` passed 301 catalog probes
(284 passed and 17 expected rejections); its report is
`tools/paper-integration/reports/paper-1.21.1-visibility-arguments-20261103-r1.json`.
On a real Zombie, target mob, alive and gravity conditions pass; invalid target
sneaking, mob and alive arguments, including negated invalid mob, are rejected
after migration. A separate probe verifies a Zombie target's sneaking and
non-sneaking states gate an imported skill and its action. Other condition
variants still need Paper positive controls and dedicated argument evidence.
The parameterless day/time and weather aliases reject unexpected arguments at
import and runtime, preserving the original condition and warning on import;
tests include negated invalid conditions. Paper verifies valid morning and
clear against the current world state and rejects invalid morning, rain and
negated thunder while preserving their source and emitting warnings.
Climbing, invisibility, glowing, falling and target frozen aliases have the
same argument validation, with importer-to-runtime and negation tests. Paper
verifies glowing, invisible and frozen Zombie target states and rejects six
invalid inputs with individual preservation and warnings; positive climbing,
falling and charged states still need dedicated Paper evidence.
Real damage events cover imported conditional reduction, condition rejection,
and rejection of an invalid native multiplier. Explicit null multipliers and
multiple same-type rules lack dedicated Paper probes; profile reload also lacks one.
Native false, null, and unknown-field immunity entries and null scalar modifier
entries are rejected with warnings during real damage events. Null multipliers
inside modifier sections and multiple same-type rules need dedicated Paper evidence.
`isFrozen` and `projectileHasEnded` argument validation preserves unknown
arguments with migration warnings and rejects them at runtime; importer and
runtime tests cover both. Paper verifies `isLiving`, `isBaby` and `isFrozen`
positive controls and rejection of their unknown arguments on a real Zombie.
The same fail-closed argument checks cover `hasTarget`, night/rain/thunder
state and living/player/mob/monster/animal/villager/tameable/raider checks.
Age, taming, shearing, saddling and charged-state aliases and their target
variants have the same importer and runtime checks.
Movement and environmental states (sneaking, sprinting, flying, blocking,
gliding, swimming, water, lava, fire and air) and their target variants now
reject unexpected arguments during import and runtime binding. Tests cover
preservation, warnings, and execution rejection; this later change is not in the
archived Paper JAR.
Target entity categories and caster/target AI, gravity, silent, alive, dead and
valid states also reject unexpected arguments. Importer and runtime tests cover
aliases, retries, negation and prevented skill execution; this subsequent change
has no dedicated Paper probe and is not in the archived Paper JAR.
Other parameterless condition aliases remain to be audited.
Later migration changes preserve unsupported `Immunities` and `DamageModifiers`
sections as structured YAML under `mob.unsupported.Immunities` and
`mob.unsupported.DamageModifiers`, with warnings and importer/runtime tests.
Nested mapping nulls under immunity and modifier entries are restored as invalid
markers, including in preserved unsupported sections. Paper verifies a nested
modifier field and rejection in real damage events; markers
do not preserve the original null value and other YAML shapes remain unverified.
Modifier sections and lists with unmapped conditions preserve the original entry
under `mob.unsupported.DamageModifiers` and report that location. Importer-to-runtime
tests and Paper verify a list-form null condition survives YAML reload and rejects
real damage. Other preserved shapes are not covered by that Paper probe.
The preceding `srv-paper-immunity-preservation-20261103-r1` run failed the strict
gate: the death event reported 13 XP but only 10 orb XP was observed; its report
and log are archived as `paper-1.21.1-immunity-preservation-20261103-r1-failed`.
That historical run did not record orb counts.
Non-strict diagnostic fixture `srv-paper-xp-spawn-diagnostic-20261103-r1`
recorded 13 event XP and three uncancelled orbs of 7, 3 and 3 at the death
location; each orb generated duplicate spawn notifications. Its report and log
are archived under `tools/paper-integration/reports/`. Follow-up non-strict
fixture `srv-paper-xp-repeat-20261103-r3` observed eight `7x1,3x2` stacks in
400 deaths: face values total 10, while the count-weighted total is 13 for all
400. The natural-death, death-loot, and imported drop-table probes now count
stacks and restore pre-existing orb state; the latest strict run includes them.
The historical failed run did not record counts and cannot be conclusively
classified. The 400-death diagnostic is not itself release evidence.
Unknown direct immunity/modifier fields, including nulls, and invalid native
`immune` values fail closed with warnings. Real damage events verify null immunity
fields, null and true unknown modifier fields, and invalid and null `immune` values.
Recent migration backup and failure handling lack dedicated Paper probes.
`lastSignal` migration now preserves explicit blank, unknown, multiple, or
unrepresentable signal arguments as untranslated rather than defaulting or
discarding input. Runtime binding rejects invalid keyed or multiple arguments;
an importer-to-runtime test covers preserved invalid and duplicate-key skill gates. No Paper
fixture covers these boundaries.
Failed condition factories no longer leave a cached binding behind; repeated
invalid bindings and negated invalid conditions are covered by runtime tests.
Action factories likewise clear failed bindings, preventing retries from
returning unregistered action keys; this has unit coverage but no Paper probe.
Composite conditions reject empty operands, extra text or braces, and `not`
with multiple operands instead of discarding them. Runtime tests cover these
fail-closed syntax cases; no separate Paper fixture covers them.
Runtime composition respects parentheses and applies unary negation before
top-level `&&`/`||`; unit tests cover grouped and mixed expressions.
Scalar condition lists preserve `||` as a boolean operator while retaining
single `|` as a list separator; parser and runtime evaluation tests cover both.
Fail-closed scalar parsing now retains empty semicolon or single-pipe list
entries as invalid markers, including in spawner import and runtime loading.
Structured drop-entry condition validation also rejects these empty elements
instead of silently weakening `DropIf` or `NoDropIf`; importer tests cover it.
Native dropper, killer and entry conditions apply the same fail-closed scalar
parsing; runtime tests use permissive evaluators to verify empty entries reject.
Strict condition-list parsing also marks unmatched delimiters and quotes as
invalid, including cross-nested delimiter pairs; parser, drop and spawner tests
cover these cases without Paper evidence.
Previous-build strict fixture `srv-paper-current-strict-20261103-r2` passed
292 probes (275 passed and 17 expected rejections) on Paper 1.21.1-133; its
archived report is `tools/paper-integration/reports/paper-1.21.1-current-strict-20261103-r2.json`.
This reruns the existing catalog and does not independently verify every recent
drop-parser boundary.
Follow-up strict fixture `srv-paper-orb-detail-strict-20261102-r1` passed the
fixed amount checks and the full gate. Its separate death-loot event recorded
15 XP, with orbs 7+7+1 both immediately and at the delayed check; this does
not establish the cause of the earlier 8 XP observation.
Experience entries now apply `min-tier-level` using the same effective player
tier and mob level policy as item entries. Unit tests cover below-threshold,
matching, unknown, and mob-level cases; a simulated death event also verifies
the plugin handler passes mob level to the real loot service. Strict fixture
`srv-paper-exp-tier-strict-20261102-r1` observes real Bukkit-damage deaths with
no player killer: level 2 produces 2 XP and level 3 produces 7 XP. It does not
verify player tier integration or experience orb pickup.

Nested item expansion does not currently add child-table experience to the parent
experience roll, and the amount range or tiers on a nested reference are not consumed.
The importer now preserves nested references with a non-unit amount as unsupported
instead of projecting an amount that runtime ignores; mob imports retain the
source under `mob.unsupported.Drops.entries`, and standalone table imports
retain it in the migration report with a warning. Importer tests cover this;
standalone tests exercise both bare table names and structured references while
confirming that safe sibling drops remain projected. The change has not been
separately validated on Paper.
Nested table references whose normalized identifier contains no ASCII letters
or digits are preserved as unsupported rather than resolving to `unnamed`;
unit tests cover punctuation-only and prefixed punctuation-only names.
When multiple source DropTable names normalize to the same ID, bare and
structured references to that ID are preserved with a migration warning instead
of choosing a table. Importer tests cover collisions across source files; no
separate Paper fixture covers this migration boundary.
If the DropTables catalog cannot be read completely, structured table references
are also preserved because hidden ID collisions cannot be excluded; an unrelated
Items catalog failure does not block explicit table references. Importer tests
cover both paths.
If the Items catalog is incomplete, structured item drops are preserved rather
than projected with an unverified identifier. An incomplete DropTables catalog
does not bypass normalization and collision checks for structured item drops;
importer tests cover both boundaries. No Paper fixture validates this change.
Malformed or unreadable Mythic source YAML now produces a conversion conflict in
preview and blocks apply before any writes; a migration service contract test
covers a broken source alongside a valid mob. This is not Paper evidence.
Scanning also fails with a path-specific error for inaccessible or invalid
category directories instead of silently applying a partial result; a service
contract test covers a category path that is a file. Missing optional category
directories remain supported. Permission failures have not been tested on Paper.
Mythic source scanning and the drop/item reference catalog both recognize
`.yml` and `.yaml` extensions case-insensitively; an importer test covers
cross-file table resolution and excludes directories with YAML-like names.
Both now scan nested category directories without the previous three-level
limit. An importer test covers deep mobs, items, and cross-file drop references;
this change has no separate Paper fixture.
Migration service conversion now uses the scanned plugin root to identify the
top-level content category, so nested folders named `Items` or `Skills` cannot
change file classification. A service contract test covers nested category
names and custom-item drop resolution; direct context-free importer calls retain
their legacy path inference. This change has no Paper fixture.
Mob migration now accepts top-level definitions as well as the legacy `mobs:`
wrapper, including both in one file; a service test applies both forms. This
change has not yet been verified on Paper.
An importer test also covers a wrapped mob named `Skills` and a top-level mob
named `mobs`; unusual structurally ambiguous inputs remain a compatibility edge.
Non-section mob entries at the top level or inside the wrapper are now reported
with their source values while valid sibling mobs are still projected. This has
importer test coverage but no separate Paper evidence.
`MobType` and `EntityType` now project to the same `mob.entity-type` consumer as
`Type`. Multiple type aliases on one mob are preserved in the migration report
without generating that mob; importer tests cover both aliases and conflicts.
No separate Paper fixture covers these aliases.
Explicit invalid or non-spawnable Bukkit entity types are preserved as an
unsupported mob report instead of producing a runtime fallback entity. Importer
tests cover unknown and numeric types; no Paper fixture covers this boundary.
Valid entity types are normalized to their Bukkit enum names in generated YAML
and mapping reports, including lowercase values and surrounding whitespace;
importer tests cover the runtime enum lookup, without a separate Paper fixture.
Load warnings now inspect transitive child tables for omitted experience, with
cycle-safe unit coverage; this warning change has not yet been tested on Paper.
Loading now reports these limitations and missing child tables; unit tests cover the
diagnostics and current behavior. These cases have no Paper parity evidence and remain open.

Follow-up strict fixture `srv-paper-drops-trigger-conditions-20261101-r7`
adds real Bukkit permission-context evidence: an OP player passes
`has-permission loot.vip`, while a non-OP player fails it. The observed line
confirms both permission results, accepted nested output, rejected nested
output with the parent's 4 XP retained, and zero item/XP output for the parent
rejected by its own condition. The 258-probe report contains 246 passed and 12
expected negative controls, with zero incomplete. This still covers only the
registered DropTable fixtures; looting interaction, broader placeholder
expressions, importer-wide drop semantics, and the official drop catalog remain
incomplete.

Incremental importer safety update: structured Mythic Drop entries now enter a
generated runtime table only when every condition translates. If any condition
is unknown, the entire original entry is preserved under
`mob.unsupported.Drops.entries` with an importer warning; known sibling
conditions are not allowed to turn that entry into an unconditional drop.
`MythicDropTableProjectionTest` and `ImportersTest` cover both the excluded
runtime projection and preserved source data. This closes a silent data-loss
path, but does not expand the set of supported Mythic drop conditions.

Incremental amount-range update: official inline Mythic drop ranges use an
exclusive upper bound (`1-3` and `1to3` both mean 1..2). `MythicDropSpec`
now parses both separators, keeps fixed amounts unchanged, and rejects empty
or reversed ranges so the importer preserves the original source entry rather
than changing its meaning. `MythicDropTableProjectionTest` covers these cases.
Migration tests verify the importer converts the official `GOLD_INGOT 1-3 1`
entry to an exclusive 1..2 range. Strict fixture
`srv-paper-drops-range-strict-20261102-r4` verifies the corresponding runtime
range on natural Bukkit death: its fixture `loot.yml` contains the projected
`amount-min: 1` / `amount-max: 2` values and observes
`exclusive-range-drop=true range-amount=2`. This proves the runtime behavior
for the projected config, not an importer-to-Paper execution. That end-to-end
imported-range fixture remains open; weighted DropTable semantics and the
broader drop catalog also remain incomplete.

Official DropTable semantics audit (MythicCraft Wiki, updated 2026-08-19):
`TotalItems` counts generated drop lines and treats entry chances as weights;
`MinItems`/`MaxItems` also constrain drop-line count, with weighted selection
when both are set. `BonusLevelItems` and `BonusLuckItems` modify generated line
count from mob level and killer luck respectively, and require a table item
bound (`TotalItems`, `MinItems`, or `MaxItems`). Either bonus option also switches
entry chances to relative weights, including when only one bound is configured.
Fractional bonus results add their integer part plus one line with probability
equal to the fractional part; this follows the official statement that decimal
bonuses provide an additional chance per level. The documented luck input includes
the Bukkit Luck attribute and Luck-based effects/enchantments; `BiasStat`
affects rare-item weights. The death hook reads the killer's effective Bukkit
`GENERIC_LUCK` attribute; the plugin test verifies the value and missing-
attribute fallback. This proves the Bukkit attribute input only; conversion
from Mythic custom Luck enchantments/curses has no defined runtime provider or
verified formula yet. Helstera now
parses `TotalItems`/`total-items` as generated row count, treats eligible entry
chance as relative weight for `TotalItems`, two-sided `MinItems`/`MaxItems`, or
either Bonus option. Single-sided bounds without a Bonus option preserve entry
order. A row is an output `Hit`,
independent of the stack amount. Conditions and dynamic chance expressions are
resolved before weighted selection, and nested expansion is capped at the
parent's remaining row count. These semantics have unit coverage. Strict Paper
fixture `srv-paper-drops-total-items-strict-20261102-r1` verifies the production
generated-line path during a real player-caused managed Zombie death: exactly
128 items are produced, observed as 33 DIAMOND and 95 EMERALD by amount for
configured 1:3 weights. Its archived report requires and passes
`deathLootGeneratedLines`, with 266 probes complete and zero incomplete.
`BonusLevelItems` and `BonusLuckItems` parse fixed and ranged multipliers and
add `mob_level * multiplier` and `luck * multiplier` generated lines when any
item-count bound is configured. A single `MinItems` or `MaxItems` uses that
bound as the base count. Either bonus option switches eligible entry chances
to relative weights, even with a single bound. Fractional bonuses add their
integer part and probabilistically add one line at the fractional remainder;
negative combined results use the same floor-based sampling and generated line
count is clamped at zero. Unit tests cover fixed/ranged values, fractional
probabilities, single-sided weighted selection, negative Luck, and fail-closed
invalid/unbounded config. Strict Paper fixture
`srv-paper-drops-bonus-semantics-strict-20261102-r1` verifies natural-death
level-only, Luck-only, combined and zero-bonus cases plus single-bound weighted
selection; its 273-probe report has 262 passed, 11 expected negative controls,
and zero incomplete. The earlier strict fixture
`srv-paper-drops-bonus-order-strict-20261102-r4` incorrectly asserted ordered
selection under a single bound and is historical for that behavior. The Mythic
importer now scans standalone `DropTables/` files
and projects safe entries, line bounds, bonuses, and killer `TriggerConditions`;
the plugin contract test feeds that projection into the production `DropTable`
parser. Explicit null `Conditions` or `TriggerConditions` now block projection
and produce a warning; the report uses an invalid-condition marker, while the
source YAML retains the original null spelling. Importer tests also reject
inherited null table conditions and unsafe parent references,
including multi-level references and YAML merge priority. Strict Paper fixture
`srv-paper-merged-null-20261103-r1` checks inherited null rejection, cross-file
parent warnings, stale-table rejection and a valid sibling through the importer
and runtime service (306 probes); it does not test invalid merged conditions
through natural death. A cross-module apply-and-reload test
also confirms a stale same-name destination cannot be rolled by the imported
parent; another checks that a mob's direct reference remains unsupported.
Importer and runtime contract
tests verify that rejected tables cannot be rolled while a valid sibling can.
Paper 1.21.1 strict fixture `srv-paper-null-drop-conditions-20261103-r1`
confirms those importer, warning and runtime-service outcomes in the server
process; it does not kill a mob for the invalid tables. Its report has 302
probes (285 passed, 17 expected rejections), zero incomplete. A subsequent
importer-to-runtime test also rejects bare and structured parent references to
null-condition children, including with stale same-name destination tables;
the same validation now rejects unsupported conditions and case-conflicting
keys for parent and direct mob references. Tables with no projectable entries,
and parent-only chains depending on them, are also rejected as references so
stale target tables cannot replace them. Strict Paper fixture
`srv-paper-unsafe-drop-references-20261103-r1` verifies parent references to
null-condition, unknown-condition and indirect empty children are warned and
do not roll stale same-name tables; a valid sibling still rolls. The direct mob
reference and case-conflict variants have automated tests only. Malformed scalar
and explicit null table definitions also block parent and mob
references; null is represented by a marker in the warning report. This later
handling has automated tests but no new Paper evidence. Strict fixture
`srv-paper-drops-imported-range-strict-20261102-r5` verifies importer output,
runtime table loading, and a real player-caused natural death with one Diamond,
two Gold Ingots from the imported exclusive `1-3` range, and 5 XP in
`deathLootImportedDropTable` (273 probes, zero incomplete). Table-level
dropper conditions and killer trigger conditions are both present in the
source file and exercised by the imported table.
Source DropTables that normalize to the same ID are each preserved with a
warning, including collisions between a valid and an invalid definition; this
collision handling has automated coverage but not yet Paper evidence.
The follow-up strict fixture `srv-paper-imported-drop-contract-strict-20261102-r1`
extends this with condition-alias binding and conflicting-source preservation:
the runtime report records `conditions-mapped=true`,
`conflict-preserved=true`, and a real natural managed-Zombie death with one
Diamond, one Gold Ingot, and 5 XP. Its 284-probe report has 267 passed,
17 expected rejected, and zero incomplete. This verifies the migration
contract for ambiguous `DropTable-1`/`DropTable1`/`Drops` sources without
claiming that unsupported Mythic drop semantics are equivalent.
Table-level dropper `Conditions` now translate and evaluate against the
death entity independently of killer `TriggerConditions`; migration, fail-
closed evaluator, and runtime-binding tests cover this context. Strict fixture
`srv-paper-dropper-conditions-strict-20261102-r1` completes 268 release probes
and verifies natural deaths: `is-monster` allows Emerald and 3 XP, while
`is-animal` rejects BREAD and its 7 XP. Unsafe entries and unknown options are
surfaced as unsupported report values. Standalone `exp`/`xp`/`experience`
entries now project as conditional, chance-based runtime experience drops and
are excluded from item generation; importer and runtime tests cover the path.
Strict fixture `srv-paper-exp-lines-strict-20261102-r2` verifies an additional
5 XP from a natural managed-Zombie death; its 268-probe report is archived in
the release evidence manifest. Strict fixture
`srv-paper-fractional-bonus-strict-20261102-r4` samples 128 natural deaths
with a level-one 0.5 bonus; 63 produce an extra line. Its 273-probe report
omits the unrequested raw Potion workflow and is scoped to the recorded probes.
`BiasStat`, Mythic custom Luck
enchantment/curses inputs, and broader Mythic entry compatibility remain
incomplete. The official DropTables catalog does not define a separate
`Looting` table option.
See the official
[Drops](https://wiki.mythiccraft.io/mythicmobs/drops/Drops) and
[DropTables](https://wiki.mythiccraft.io/mythicmobs/drops/DropTables) references.

Strict Paper 1.21.1-133 fixture
`srv-paper-drops-luck-attribute-strict-20261102-r2` validates the runtime Luck
input on a real player-caused death. It temporarily sets the killer's base
`GENERIC_LUCK` to 1, records effective `luck=1.0`, and observes an IRON_INGOT
whose configured base chance is zero but whose existing per-entry `luck-factor`
raises it to one. The prior player attribute is restored immediately. The
266-probe report has 255 passed, 11 expected negative controls, and zero
incomplete. This is Bukkit Luck-to-current-scaler evidence only. Separate
strict fixture `srv-paper-drops-bonus-order-strict-20261102-r4` verifies
`BonusLuckItems` with the Bukkit Luck attribute; neither fixture covers Mythic
custom Luck enchantment/curses inputs or `BiasStat`.

Incremental condition update: official `IsRaiderPatrolLeader` and the
`isPatrolLeader` alias map to the Bukkit `Raider#isPatrolLeader()` state. Unit
tests cover both boolean states and fail-closed behavior without a raider;
importer tests cover both source names. Strict fixture
`srv-paper-raider-patrol-condition-strict-20261101-r1` verifies the positive
and negative branches on a real Pillager and restores the original flag. Its
dedicated `raiderPatrolLeaderCondition` report probe is required by the release
contract. The full Conditions catalog remains `mapped-unverified`.

Incremental condition update: official `IsCreeperPrimed` maps to Bukkit
`Creeper#isIgnited()`. Unit tests cover ignited and non-ignited states plus
fail-closed behavior without a Creeper; importer tests verify the Mythic name.
Strict fixture `srv-paper-creeper-primed-condition-strict-20261101-r1` checks
both states on a real Creeper, restores its ignited state, and requires the
dedicated `creeperPrimedCondition` report probe. The complete Conditions
catalog remains `mapped-unverified`.

Incremental vehicle-trigger update: the Paper self-test now uses an independent
managed ArmorStand passenger instead of the online player, so player death and
rebinding from adjacent probes cannot invalidate the delayed vehicle workflow.
Strict Paper 1.21.1-133 fixture `srv-paper-integration-exp-20261031-r16`
records `vehicle-events enter=true exit=true dismount=true eject=true
destroy=true`.

Incremental mob-option update: `Options.Despawn`/`Persistent` now has a
dedicated Paper fixture and self-test path that reads the managed instance's
normalized `PERSISTENT` mode, zero despawn distance, and Bukkit persistent flag.
The importer already maps `Options.Persistent` to `mob.despawn`, with regression
coverage; strict fixture `srv-paper-integration-exp-20261031-r17` records
`despawn-options result=true mode=PERSISTENT distance=0.0 persistent=true`.

Migration compatibility update: common Mythic mechanic mappings now preserve
positional arguments as well as keyed arguments for item/equipment, player
inventory/experience/oxygen, block, entity-state, movement, explosion, potion,
animation, and audience mechanics. Importer regression coverage now exercises
both forms; keyed mappings remain unchanged.

The existing `take-item` runtime now also accepts official `RemoveItem` aliases
(`removeitem`/`remove-item`), preserving the same target-aware inventory
semantics without introducing a second removal implementation.
For custom item identifiers, `has-item`, `target-has-item`, and `take-item`
now require item similarity instead of accepting any stack of the same material;
vanilla material identifiers retain type-based matching. Unit tests cover mixed
custom stacks and removal, but this change has not yet been verified on Paper
with an external item plugin. Identifiers that are also vanilla material names
retain vanilla matching semantics.

Entity equipment, horse armor, and armor-stand item options now try the full
custom item identifier before parsing vanilla material/amount syntax. The
vanilla parser rejects a nonnumeric suffix in the amount position, avoiding a
fallback from `stone:custom` to STONE; unit tests cover the parser and invoke
all three equipment paths with a mock integration adapter, including numeric
custom IDs, an unresolved ID, and vanilla fallback. A numeric
suffix such as `stone:2` remains ambiguous with the documented vanilla amount
syntax when no custom resolver accepts it. External-plugin and Paper runtime
verification of these equipment paths remains outstanding.

Incremental trigger update: killer-side `on-kill` and `on-kill-player` now
resolve managed mob killers from the damage event and unwrap projectile
shooters. `PlayerKills` now adds a lifetime counter per managed mob instance,
records only player victims on the killer-side path, and clears the counter at
instance teardown. Exploratory Paper 1.21.4 killprobe records the real-player
kill path and `PlayerKills` range checks as passed. Strict Paper 1.21.1-133
fixture `srv-paper-spawning-distance-client-walk-strict-20261102-r3` also
passes `killTrigger` through a real Bukkit mob-damage/death chain and
`playerKillTrigger` through the real online-player death event with controlled
`lastDamageCause` attribution; the same probe passes `player-kills >=1` and
rejects `player-kills >1`. This verifies death-event dispatch and the managed
counter, but not a naturally delivered PvP damage packet.

Death cancellation timing update: native killer-side triggers and persistent
chunk release run at `MONITOR` with `ignoreCancelled`; custom loot is added to
the death event instead of spawning items during the listener. Revive health
is applied at `HIGHEST` to deaths cancelled before that handler. A contract
test covers cancelled and confirmed killer-side dispatch. Paper 1.21.1-133
fixture `srv-paper-revive-late-20261102-r1` verifies real lethal damage
cancelled by a separate HIGH-priority self-test listener, followed by survival,
instance retention, 8 health and no observed diamond drop after 60 ticks.
Third-party plugin interoperability, cancellation later at the same `HIGHEST` priority, and plugins that
modify events at `MONITOR` remain unverified. Mythic `~onKill` skill lines are
still preserved as unsupported by the importer; native `on-kill` support and
the historical runtime evidence do not establish an importer mapping. The
official trigger catalog consulted on 2026-11-02 does not list `onKill`.

Mythic `~onDamaged` and its documented `~onHurt` alias now map supported
inline mob skill actions to `mob.triggers.on-damage.do`. Importer and runtime
contract tests cover both aliases and an entity attacker as `@Trigger`;
environmental damage supplies no trigger entity. Multiple event suffixes and
unsupported mechanics and direct uses of `<skill.var.damage-...>` remain
preserved. Official `<skill.var.damage-amount>`,
`<skill.var.damage-type>` and `<skill.var.damage-cause>` behavior remain
unverified. Paper fixture `srv-paper-damaged-import-20261102-r2` converts both
aliases and executes actions on a real attacker using Bukkit damage; a no-source
damage call lowers victim health without selecting it as `@Trigger`. Natural
combat AI and projectile semantics remain unverified. Source:
https://wiki.mythiccraft.io/mythicmobs/Skills/Triggers/ondamaged

Incremental condition update: official `Absorption` now reads Bukkit
`Damageable#getAbsorptionAmount()` through the active target/caster entity,
supports exact and comparator forms, and defaults to `>0`; migration, binding,
unit, and self-test coverage are included. Exploratory Paper 1.21.4 fixture r8
records the condition path passed; strict Paper 1.21.1 evidence remains
pending.

Incremental condition update: official `HasPotionEffect` now matches active
Bukkit potion effects by type, amplifier, and remaining duration ranges, with
`ANY` type support. Migration, binder, unit, and condition-catalog self-test
coverage are included. Exploratory Paper 1.21.4 fixture r8 and strict Paper
1.21.1-133 fixture `srv-paper-integration-final-r8` record
`potionEffectCondition=passed`.

Incremental Mythic Aura update: Mythic `Aura`/`Buff`/`Debuff` now tracks named
per-entity Aura state with tick expiry, stack caps, duration refresh, same-caster
or global merging, overwrite modes, and type metadata. `HasAura` (`hasBuff`,
`hasDebuff`), `HasAuraStacks` (`hasBuffStacks`, `hasDebuffStacks`, `auraStacks`,
`buffStacks`, `debuffStacks`), and `HasAuraType` (`hasBuffType`,
`hasDebuffType`) map to that registry, including target-condition contexts.
Migration and runtime binding reject unknown arguments and keep unsupported
source mechanics under `unsupported` with warnings. A required
`mythicAuraRuntime` probe passes in strict Paper 1.21.1-133 fixture
`srv-paper-aura-strict-20261106-r1`: it covers action dispatch, named presence,
same-caster stack merging, imported condition aliases, stack/type checks, and
malformed-condition rejection. The archived report records 366 complete probes
(347 passed, 19 expected rejections, zero incomplete). The historical
`auraStacksCondition` and target-state probes still verify Bukkit PotionEffect
paths; strict fixture `srv-paper-aura-target-strict-20261106-r1` additionally
verifies imported `targetHasAuras` against a named Aura and rejects missing
Aura names. Aura components, lifecycle skills, attachments, charges, and event
cancellation remain unsupported and preserved with warnings. Official
references: [HasAura](https://wiki.mythiccraft.io/mythicmobs/skills/conditions/hasaura),
[HasAuraStacks](https://wiki.mythiccraft.io/mythicmobs/skills/conditions/hasaurastacks),
[HasAuraType](https://wiki.mythiccraft.io/mythicmobs/skills/conditions/hasauratype).

Incremental condition update: official `Level` now reads the managed instance
level used by the existing mob scaling system, falls back to level 1 when no
explicit instance level is set, and rejects contexts without a managed model.
Migration, binder, unit, and condition-catalog self-test coverage are included.
Exploratory Paper 1.21.4 fixture r8 and strict Paper 1.21.1-133 fixture
`srv-paper-integration-final-r8` record `levelCondition=passed`.

Incremental managed-type condition update: official `MythicMobType` now matches
the managed model ID exactly or by containment when `exactmatch=false`, with
fail-closed unknown types. This is the adapter's model-ID representation, not a
claim of an external MythicMob registry; migration and runtime self-test
coverage are included; strict Paper 1.21.1-133 fixture
`srv-paper-integration-final-r8` records `mythicMobTypeCondition=passed`.

Incremental scoreboard-condition update: official `GlobalScore` now reads the
main Bukkit scoreboard objective and the standard fake entry `GLOBAL`, supports
numeric score ranges, and retains `__GLOBAL__` as a compatibility fallback for
existing Helstera fixtures. It fails closed for missing objectives or unset
scores. Migration and unit coverage are included. Exploratory Paper 1.21.4
fixture r3 and strict Paper 1.21.1-133 fixture
`srv-paper-integration-final-r8` record `globalScoreCondition=passed`.

Incremental scoreboard-condition update: official `Score` now accepts the
documented objective/entry/value form plus the adapter's objective/value
shorthand, keeps explicit target context, and fails closed for missing scores.
The native lookup and injected test bridge both preserve explicit entry values.
Exploratory Paper 1.21.4 fixture r3 and strict Paper 1.21.1-133 fixture
`srv-paper-integration-final-r8` record `scoreMechanics=passed` and
`scoreCondition=passed`.

Incremental skill-tree condition update: official `Targets` now matches the
inherited `BehaviorContext` target count against Mythic ranges and can select
from that context with a `targeter` argument. Migration, binder, runtime, and
fail-closed empty-context coverage are included; broader Mythic targeter source
semantics and fresh Paper evidence remain pending.

Incremental geometry-condition update: official `Cuboid` and `InBounds` now
evaluate inclusive cuboids, spheres, and upright cylinders against the active
target/origin using coordinate triples, location targeters, relative cuboid
coordinates, and world isolation. Named pin references in these geometry
conditions remain unsupported and fail closed. Migration, binder, runtime,
and self-test coverage are included. Exploratory Paper 1.21.4 fixture
`srv-paper-integration-exploratory-1.21.4-r121` records
`bounds-condition=true`; strict Paper 1.21.1-133 fixture
`srv-paper-integration-final-r8` also records the bounds probe passed.

Incremental Pins condition update: plugin-owned `plugins/helsteraMobs/pins.yml`
loads named single or multiple world locations, rejects unloaded worlds and
non-finite coordinates with warnings, resolves names case-insensitively, and
refreshes on `/helstera reload skills`. `DistanceFromPin`,
`OriginDistanceFromPin`, and `InPinRegion` now have importer mappings and
fail-closed runtime factories; finite comparator and inclusive range parsing
are covered by importer and runtime tests. `MovePin` now maps keyed and
positional pin names and updates a single-point pin to the active target's
location; unknown pins, absent targets, invalid destinations, and multi-point
pins are no-ops. Location-valued `@Pin` returns all named locations by default
or one random location with `random=true`; location contexts remain separate
from entity targets and are consumed by lightning, particle, sound, and
location-variable actions. `@BlocksInPinRegion` now enumerates inclusive
same-world cuboids and feeds block locations to `set-block`/`break-block`; an
enumeration above 16,384 blocks fails closed to bound synchronous work.
Registry, targeter, context, service, mechanic, and importer tests cover these
paths. Strict Paper 1.21.1-133 fixture
`srv-paper-pin-conditions-strict-20261102-r1` records `pinConditions` and
`pinTargeters` as passed in a complete 270-probe report with zero incomplete.
This is a project-native registry, not proof of Mythic pack `pins.yml`
serialization compatibility; official pack loading and full Conditions and
Targeters catalog coverage remain open.

Incremental skill-condition update: official `IsSkill` now checks the loaded
named-skill registry through a lifecycle-safe SkillService lookup, recognizes
`skill`/`n` aliases, and fails closed for unknown names. Migration, runtime,
and condition-catalog self-test coverage are included. Exploratory Paper 1.21.4
fixture r8 and strict Paper 1.21.1-133 fixture
`srv-paper-integration-final-r8` record `isSkillCondition=passed`.

Incremental variable-condition update: official `VariableContains` now supports
string `STARTS_WITH`/`ENDS_WITH` and collection/map `ANY`/`ALL` modes through
`compareType`, preserving the existing scoped variable store. Migration and
runtime unit coverage are included. The Paper self-test uses a real SkillTree
execution context for non-numeric variables. Exploratory Paper 1.21.4 fixture
r3 and strict Paper 1.21.1-133 fixture `srv-paper-integration-final-r8` record
`variableContainsCondition=passed`.

Incremental Mythic variable-condition update: `VariableEquals`,
`VariableInRange`, and `VariableIsSet` now import their documented aliases into
the scoped runtime variable store. Missing variables, non-numeric range values,
invalid scopes, malformed ranges, and unknown arguments fail closed or remain
preserved with a warning. Strict Paper 1.21.1-133 fixture
`srv-paper-variable-conditions-strict-20261106-r1` passes the new
`importedVariableConditions` probe across all five scopes, numeric boundaries,
quoted semicolon values, and dynamic placeholders. This verifies these imported
paths; the full Conditions catalog remains `mapped-unverified`.

Incremental value-condition update: official `CompareValues` now supports
integer, floating-point, string, and boolean comparisons with Mythic operator
aliases (`EQUALS`, `GT`, `LTE`, etc.). Migration, runtime, unit, and
condition-catalog self-test coverage are included. Exploratory Paper 1.21.4
fixture r8 and strict Paper 1.21.1-133 fixture
`srv-paper-integration-final-r8` record `compareValuesCondition=passed`.

Incremental projectile-condition update: official `ProjectileHasEnded` now
records projectile UUIDs when `ProjectileHitEvent` fires, checks the event
trigger projectile, and clears state on runtime reset. Migration and unit
coverage are included. Exploratory Paper 1.21.4 fixture r3 records the
independent `projectileHasEndedCondition` probe passed; strict Paper 1.21.1-133
fixture `srv-paper-integration-final-r8` also records it passed.

Incremental item-condition update: official `EntityItemIsSimilar` now compares
the Item trigger's carried stack against the resolved item template using
`ItemStack#isSimilar`, including custom resolver support and fail-closed
non-Item contexts. Migration, runtime, and condition-catalog self-test coverage
are included. Exploratory Paper 1.21.4 fixture r2 records
`entityItemSimilarCondition=passed`; strict Paper 1.21.1 evidence remains
pending.

Incremental inventory-condition update: official `ItemIsSimilar` now remains
separate from `EntityItemIsSimilar` and compares a target player's explicit
inventory or equipment slot against the resolved item template. Numeric slots
`0..35`, hand/armor slot aliases, custom item resolution, strict similarity,
migration aliases, and fail-closed invalid/non-player paths are covered by
runtime and migration tests. Exploratory Paper 1.21.4 fixture r2 records
`itemSimilarCondition=passed` and `targetsCondition=passed`; strict Paper
1.21.1 evidence remains pending.

Incremental cooldown-condition update: official `ItemRecharging` now reads
Bukkit/Paper `HumanEntity#getAttackCooldown()` and treats values below `1.0` as
recharging. Migration aliases, binder coverage, and fail-closed non-human
contexts are tested. Exploratory Paper 1.21.4 fixture r3 records
`itemRechargingCondition=passed` after a real Bukkit attack. Strict Paper
fixture `srv-paper-item-recharging-import-strict-20261106-r1` passes the
imported `?itemRecharging`/`?recharging` aliases after a real attack and rejects
a Zombie target (`importedItemRechargingCondition=passed`).

Incremental player-mechanic update: official `CloseInventory`,
`ClearExperience`, `ClearExperienceLevels`, and `GiveExperienceLevels` now use
the corresponding Bukkit player APIs through target-aware `itemTargets`.
Importer aliases, binder coverage, and targeted Mockito action tests are
included. Exploratory Paper 1.21.4 fixture
`srv-paper-integration-exploratory-1.21.4-r122` records
`playerExperienceMechanics=passed`; strict Paper 1.21.1 evidence remains
pending.

Incremental vehicle-mechanic update: official `EjectPassenger` now maps to
Bukkit `Entity#eject()` and remains caster-only, matching Mythic's no-target
semantics. The `eject_passenger` alias, importer support, binder coverage, and
caster-only action test are included. The vehicle fixture creates a separate
managed Boat caster and ArmorStand passenger; exploratory Paper 1.21.4 r124 and
strict Paper 1.21.1-133 `vehicleEvents` both record `eject=true`.

Incremental freeze-mechanic update: official `Freeze` now writes the target's
Paper freeze ticks through `LivingEntity#setFreezeTicks`, with dynamic numeric
arguments, targeter-compatible execution, importer aliases, and action/binder
tests. The existing Paper `IsFrozen` self-test now reaches the frozen state via
the mechanic before checking and restoring it. Exploratory Paper 1.21.4 fixture
`srv-paper-integration-exploratory-1.21.4-r123` and strict Paper 1.21.1-133
`frozenCondition` probe pass. The strict self-test applies Freeze, reads the
real frozen state, then restores the prior freeze ticks.

Incremental item-condition update: official `ItemType` now aliases the existing
`TriggerItemType` Bukkit Item trigger material evaluator, preserving material
list arguments. Migration and runtime coverage are included, and exploratory
Paper 1.21.11 r123 records the independent `itemTypeCondition=passed` probe.
Strict Paper 1.21.1 evidence remains pending.

Incremental player-mechanic update: official `Oxygen` now adds a configured
amount of air to target players through Bukkit `getRemainingAir`/
`setRemainingAir`, capped at `getMaximumAir`. Importer aliases, binder/action
tests, and the online-player self-test are included. Exploratory Paper 1.21.4
fixture `srv-paper-integration-exploratory-1.21.4-r125` and strict Paper
1.21.1-133 `oxygenMechanic` probe pass against the online player.

Incremental view-condition update: official `LookingAt` now uses a Player
target's Bukkit ray trace for block or entity type matching within a configured
distance. Migration and runtime coverage are included. Strict Paper fixture
`srv-paper-looking-at-import-strict-20261106-r1` verifies both imported
selectors and their distance/type negative controls; natural client-originated
view direction remains unverified.

Incremental world-condition update: official `Structure` now resolves Paper's
registered structures and uses `World#hasStructureAt` at the active location,
including namespaced values and wildcard matching. Migration and fail-closed
runtime coverage are included; exploratory Paper 1.21.11 r123 records
`structureCondition=passed` for a real Mineshaft exact, wildcard, and missing-
key path. Strict Paper 1.21.1 evidence remains pending.

Incremental item-condition update: official `EntityItemType` and
`EntityMaterialType` now read a Bukkit Item trigger's carried material in
`on-item-pickup` contexts, while non-Item contexts fail closed. Migration,
runtime, and condition-catalog self-test coverage are included. Exploratory
Paper 1.21.4 fixture r2 records `entityItemTypeCondition=passed`; strict Paper
1.21.1 evidence remains pending.

Incremental condition-schema update: importer parsing for `LunarPhase`,
`EnderDragonPhase`, `MythicMobType`, `ItemType`, `TriggerItemType`,
`EntityItemType`, `EntityMaterialType`, `TriggerBlockType`, and `Structure` now
consumes the complete semicolon-delimited argument list. Unknown keys, repeated
aliases, empty values/lists, and malformed booleans are preserved as unmapped
conditions with migration warnings. `ItemType` accepts its documented
`strict`/`exact` and `vanillaonly`/`vanilla` flags only when false; true values
remain preserved because the current Bukkit material evaluator cannot verify
custom-item identity. `EntityItemType` and `EntityMaterialType` require an
explicit material because their official schemas do not define a default.
Accepted forms, aliases, defaults, and rejected forms have migration-test
coverage; this update adds no Paper runtime evidence, and existing runtime
reports do not exercise these importer failure paths. Source schemas:
[MythicMobType](https://wiki.mythiccraft.io/mythicmobs/Skills/Conditions/mythicmobtype),
[ItemType](https://wiki.mythiccraft.io/mythicmobs/skills/conditions/ItemType),
[TriggerItemType](https://wiki.mythiccraft.io/mythicmobs/skills/conditions/TriggerItemType),
[EntityItemType](https://wiki.mythiccraft.io/mythicmobs/skills/conditions/EntityItemType),
[EntityMaterialType](https://wiki.mythiccraft.io/mythicmobs/skills/conditions/EntityMaterialType),
[TriggerBlockType](https://wiki.mythiccraft.io/mythicmobs/skills/conditions/TriggerBlockType),
and [EnderDragonPhase](https://wiki.mythiccraft.io/mythicmobs/Skills/conditions/EnderDragonPhase).

Incremental inventory-mechanic update: official `ConsumeSlot` now removes a
requested amount from a target player's exact numeric or equipment inventory
slot, distinct from the existing item-matching `take-item` mechanic. Importer
aliases, binder coverage, and slot-specific Mockito action tests are included.
The online-player self-test records `consumeSlotMechanic=passed` after reducing
a real main-hand stack. A Paper runtime fix now mutates the stack before
writing it back, avoiding Paper's inventory-copy behavior. Exploratory Paper
1.21.4 r127 confirms the decrement; strict Paper 1.21.1 evidence remains
pending.

Incremental value-condition update: official `StringEquals` now compares two
resolved strings with `value1/value2` and `val1/val2` aliases. Migration,
runtime, and condition-catalog self-test coverage are included. Exploratory
Paper 1.21.4 fixture r8 records `stringEqualsCondition=passed`; strict Paper
1.21.1 evidence remains pending.

Incremental value-condition update: official `StringEmpty` and
`StringNotEmpty` now check resolved text values with `value/val/v/string/s`
aliases. Migration, runtime, unit, and condition-catalog self-test coverage
are included. Exploratory Paper 1.21.4 fixture r8 and strict Paper 1.21.1-133
`stringEmptyCondition` probes pass.

Historical `LookingAt` runtime checks used a target Player's Bukkit block and
entity ray trace. They are superseded by strict imported-condition evidence in
`srv-paper-looking-at-import-strict-20261106-r1`, which records block and
entity selector mappings, aliases, real hit and rejection controls, invalid
input preservation, and cleanup. Natural client-originated view direction
remains unverified.

Incremental trigger-condition update: official `BowTension` now reads the
actual `EntityShootBowEvent#getForce()` retained in the `on-entity-shoot`
context, fails closed outside that event, and preserves the 0..1 migration
range. Runtime, migration, and trigger self-test coverage are included.
Exploratory Paper 1.21.4 fixture r3 records `bowTensionCondition=passed`; strict
Paper 1.21.1 evidence remains pending.

Incremental spatial-condition update: official `LineOfSightFromOrigin` now
ray-traces from the metaskill origin to the target eye location, rejects world
mismatches and missing context, and maps the `inlineofsightfromorigin` alias.
Migration, unit, and condition-catalog self-test coverage are included.
Exploratory Paper 1.21.4 fixture r2 records
`lineOfSightFromOriginCondition=passed`, `originLocationCondition=passed`, and
`triggerBlockTypeCondition=passed`; strict Paper 1.21.1 evidence remains
pending.

Incremental health-condition update: `Health` and `HealthPercent` now preserve
Mythic `includeabsorption`/`ia` and include Bukkit absorption in raw or
percentage comparisons. The migration path retains the flag through canonical
`health`, `health-below`, `health-above`, and `health-percent` forms; runtime,
unit, and self-test coverage are included, while fresh Paper evidence remains
pending.

Incremental condition update: official `LunarPhase` now reuses the existing
vanilla eight-phase full-time calculation used by spawners, accepts numeric
phase lists `0..7`, and fails closed for invalid phases. Both migration and
runtime reject a list containing any invalid or empty token, including a
trailing comma; focused regression coverage protects mixed, empty, and
out-of-range inputs. Strict Paper 1.21.1-133 fixture
`srv-paper-runtime-alias-20261031-r2` records
`lunarPhaseCondition=passed`; exploratory Paper 1.21.4 fixture r8 is
additional evidence.

Incremental signal-condition update: official `LastSignal` now records the
most recent signal delivered to each managed instance, reads it through a
fail-closed condition, and clears it with instance teardown. Migration, unit,
and the existing live signal self-test cover named and default forms.
Exploratory Paper 1.21.4 fixture r3 records `signalMechanics=passed`; strict
Paper 1.21.1 evidence remains pending.

Incremental condition update: common environment and state aliases now include
`night`, `raining`, `thundering`, `falling`, `drowning`, and `target-within`;
importer mappings and unit coverage are included, while the complete Mythic
condition catalog remains open. The internal `has-auras` Bukkit PotionEffect
condition is not an importer equivalent for Mythic Aura conditions.

Incremental comparator update: migration now preserves Mythic `=`/`==`/`!=`
comparators for health, target health, distance, and light-level leaves;
runtime binding and unit coverage include the corresponding equality and
inequality paths. Full comparator edge-case and Paper evidence coverage remains
open.

Incremental interaction update: `on-interact` now listens to Bukkit's
`PlayerInteractEntityEvent` parent event, covering both ordinary entity-right-
clicks and the `PlayerInteractAtEntityEvent` child event; natural Paper timing
evidence remains open.

Cinematic condition update: official `IsInCinematic` now requires an explicit
player target and reads the active DialogueService cinematic state, with
`isInCinematic`/`inCinematic` aliases and migration coverage. Strict Paper
fixture `srv-paper-cinematic-condition-20261105-r1` verifies the player is
inside the same-world 16-block dialogue audience, rejects an out-of-range
player and a non-player, and clears when the runner stops. Report:
`tools/paper-integration/reports/paper-1.21.1-is-in-cinematic-condition-strict-20261105-r1.json`.

Reload lifecycle update: `SkillExtras` and `SkillCatalog` now expose complete
runtime-hook reset paths, including plugin-owned services, static state maps,
delayed schedulers, and diagnostic bridges; `HelsteraPlugin.onDisable()` invokes
both resets and clears the projectile plugin reference. Source audits cover the
full cleanup boundary.

Incremental condition alias update: vehicle/passenger conditions now have
runtime evaluators and direct skill aliases for `hasPassenger`, `isVehicle`,
`targetHasPassenger`, and `targetIsVehicle`; Mythic importer translations and
unit coverage share the same canonical names.

Incremental farmland condition update: official `Moist`/`MoistureLevel` now
map to strict Bukkit `Farmland` BlockData checks, with `ismoist`,
`moistureness`, and `moistness` aliases, `l` range migration, and fail-closed
non-farmland behavior. Exploratory Paper 1.21.11 r81 verifies hydrated moisture
7, dry moisture 0, range matching, and BlockState restoration.

Incremental entity condition update: official `MobSize` now maps to Bukkit
`Slime#getSize()`/`Phantom#getSize()` (including MagmaCube through Slime), with
`mobsize`/`size` aliases, range migration, and strict rejection of unsupported
entities. Exploratory Paper 1.21.11 r82 verifies a real Slime transition from
size 4 to exact size 2.

Incremental mechanic update: Mythic `randomskill{skills=...}` now maps to a
runtime random-skill action that selects a loaded named skill and enters the
existing recursion, cooldown, target, and queue guards; importer and nested
skill tests cover the path.

Direct mechanic alias update: hand-written `remove`, owner/parent actions,
`teleportself`/`teleportcaster`, health/speed modifiers, `knockbackself`, cast
controls, and `setinvulnerable` now bind to the same runtime actions already
produced by the Mythic importer.

Potion mechanic parity update: `effect-self` now consumes Mythic-style
`ambient`, `particles`, and `icon` flags in addition to duration/amplifier;
targeted `effect` already uses the same six-argument runtime constructor, and
unit coverage now locks both paths.

Targeter context update: targeter-bound actions now retain the originating
cancellable event and Bukkit damage cause when their context is narrowed to the
selected target; `cancel-event @Target` has regression coverage.

Delayed action lifecycle update: per-instance delayed/repeated mechanics now
track pending continuations and reclaim execution-generation state after
cancellation or completion, preventing long-lived servers from accumulating
stale instance IDs.

Reload safety update: `SkillService.loadSkills()` now invalidates old delayed
continuations with generation tombstones instead of clearing the generation map;
multiple queued tasks from the previous skill definition cannot execute after a
skills reload.

Scheduler failure update: delayed mechanics now treat Bukkit scheduler runtime
exceptions as a fail-closed scheduling result, allowing pending continuation and
generation state to be reclaimed instead of leaking during plugin shutdown.

Delayed action exception update: continuation cleanup now runs in `finally`, so
an action that throws cannot strand pending scheduling or generation state; a
dedicated regression covers the throwing-action path.

Execution option validation now rejects malformed, negative, non-finite and
overflowing delay/repeat values at binding with a diagnostic containing the
source line. Valid tick, second and millisecond durations remain supported.
Unit tests cover the boundary; the timing fixture below exercises two invalid
values on Paper. Runtime parsing also rejects duplicate execution options and
conflicting interval aliases instead of silently overriding them. Strict fixture
`srv-paper-skill-duplicate-20261104-r1` verifies binding rejection and diagnostics
for duplicate delay, repeat and interval aliases on Paper. Named and random
skill references now enter the same execution wrapper as ordinary actions, and
the importer preserves execution metadata on nested references. Unit tests cover
delay, repeat and zero chance; an importer-to-runtime contract covers delayed
repeated nested damage. Unit tests also cover `skill`, `cast-skill` and
`randomskill` with an explicit target and a passing or failing inline
condition. The migration reference parser matches the mechanic's own closing
brace, and the cross-module delayed-damage test includes `@Target{limit=1}`;
broader combinations remain open.
The migration reference parser now respects quoted delimiters and preserves
invalid references, duplicate reference aliases and unsupported arguments with
a warning instead of executing a partial reference. Importer tests cover those
negative cases; an importer-to-runtime test verifies a quoted reference with
delay, repeat and targeter while rejected sibling lines do not execute. Strict
fixture `srv-paper-skill-reference-20261104-r1` verifies a quoted reference
with zero delay on a real entity, plus preservation, warnings and non-execution
for three invalid sibling references.
The importer additionally rejects invalid nested-skill delay and repeat values
with a warning and preserves the original line; negative, non-finite,
fractional count and overflow cases have importer tests, and the runtime
contract confirms invalid siblings do not execute. Strict fixture
`srv-paper-skill-timing-20261104-r1` observes a nonzero delay and one repeat
on a real entity, and verifies preservation, warnings and non-execution for
`delay=NaN` and `repeat=-1`. Other timing boundaries remain unit-tested only.
The same importer validation now applies to other mechanics: invalid or
duplicate timing options are preserved with a warning instead of being mapped
to an action that runtime binding rejects. Importer tests cover standalone
skills and mob-event lines; an importer-to-runtime contract verifies rejected
damage siblings do not execute. Strict fixture
`srv-paper-mechanic-timing-20261104-r1` verifies three invalid heal lines are
preserved with warnings and do not affect a real entity; it does not cover
every mechanic or timing boundary.
For positional mechanics without a targeter, migration now retains standalone
chance and inline-condition suffixes rather than dropping them; importer tests
cover both and an importer-to-runtime contract confirms `damage 40 0%` does
not execute. Strict fixture `srv-paper-positional-chance-20261104-r1` verifies
imported positional heal with 0% and 100% on a real entity. Positional lines with
braced targeter or condition suffixes now distinguish those braces from the
mechanic body, preventing suffix arguments from overriding action arguments.
Importer tests cover both forms, and an importer-to-runtime contract checks
passing and failing target conditions, including a braced condition. Strict
fixture `srv-paper-positional-condition-20261104-r1` also exercises these
conditions and a braced targeter on a real entity.
Runtime skill parsing rejects multiple independent targeters or chance values
instead of silently taking the last one. The importer preserves such lines with
a warning; focused tests cover valid targeter chains and quoted text as well
as rejected duplicates. Strict fixture `srv-paper-suffix-grammar-20261104-r1`
checks duplicate chance, independent targeters and an incomplete chain on Paper.
Incomplete targeter chains are likewise preserved with warnings during
migration, and an importer-to-runtime contract confirms ambiguous sibling
actions do not execute.
The migration chain scanner distinguishes targeter separators from `>` in
positional placeholders and braced comparisons; importer regressions cover
both alongside incomplete chains. The strict fixture checks that a positional
placeholder and valid targeter chain remain mapped; it does not execute them.
Invalid chance suffixes, including out-of-range and non-finite values, are now
preserved with migration warnings. Importer and importer-to-runtime tests cover
these cases. Strict fixture `srv-paper-invalid-chance-20261104-r1` checks
`101%`, `NaN%`, and `1e999%` are preserved, warned, rejected and do not heal
a real entity.
The subsequent direct `MythicSkillSpec` constructor check for non-finite
chance values has a unit test and is included in the newer strict fixture's JAR.
Direct action binding now rejects parsed `~on...` suffixes instead of silently
discarding event routing, and duplicate event suffixes are rejected. Importer
tests cover supported mob event routing and preservation of unsupported or
duplicate events; parser and binding unit tests cover the runtime rejection.
Strict fixture `srv-paper-event-suffix-20261104-r1` independently verifies
binding rejection and diagnostics; it does not test natural event dispatch.
Parser and importer tests additionally cover mixed-case event prefixes, unknown
and incomplete event suffixes, and quoted literal text. Strict fixture
`srv-paper-event-variants-20261104-r1` checks binding and diagnostics for these
variants, but does not test delivery of the quoted message to a client.
Standalone `?` and `@` suffixes are now preserved with migration warnings and
rejected by runtime binding instead of being treated as positional arguments.
Importer, parser, binding and importer-to-runtime tests cover these cases.
Strict fixture `srv-paper-orphan-suffix-20261104-r1` verifies preservation,
warnings, rejection and unchanged health on a real entity.
Composite conditions now propagate evaluation failures through nested `not`,
`all` and `any` until the outer registry fails closed. Unit tests exercise
throwing custom conditions and verify guarded actions do not run. Custom
`BehaviorRegistry` implementations must implement `testConditionForComposition`
to support composite conditions; its default rejects evaluation. Strict fixture
`srv-paper-condition-failure-20261104-r1` checks throwing registered conditions
block healing under nested negation and a normal false-condition control heals.
Named `skill:...` conditions now propagate throwing or invalid requirements to
outer composites rather than turning them into a negatable false; unit tests
cover nested negation, a normal false control and a guarded action. Strict
fixture `srv-paper-named-condition-20261104-r1` verifies throwing and unknown
named skill requirements block healing, with a normal false control on a real
entity through the production skill binder.
After configuration reload, stale named-skill registry entries now fail closed
as conditions and cannot execute old actions; a reload during evaluation or a
direct action chain also stops the old definition. Unit tests cover removal,
reintroduction and in-flight reload. Strict fixture
`srv-paper-named-reload-20261104-r1` verifies deletion and reintroduction on a
real entity through direct skill action and negated condition self-tests;
in-flight reload remains covered by unit tests.
Named-skill requirements can now reference definitions declared later in the
same configuration, including mixed-case names; unknown references fail binding
and cyclic condition evaluation fails closed. Unit tests cover the runtime
action, missing references and cycles. Strict fixture
`srv-paper-named-reference-20261104-r1` verifies a mixed-case forward reference,
unknown reference rejection and cyclic guard against a real entity.

Projectile targeter update: `@attacker` now unwraps a projectile's living
shooter when the Bukkit trigger entity is a Projectile; direct entity attackers
retain the existing path, with API regression coverage for both forms.

Projectile trigger update: `@trigger` now applies the same living-shooter
fallback for projectile-triggered skills, allowing `on-projectile-hit` actions
to address the firing entity through the context targeter.

Conditional targeter update: `@nearestConditionalPlayer` now enforces the
player entity filter before condition evaluation, so a nearby non-player living
entity cannot satisfy a player-only targeter; API regression coverage includes
mixed player/mob candidates.

Incremental placeholder update: `health.percent`, `health.raw`, `health.max`,
`is_alive`, and `has_permission.<node>` spellings now normalize to the same
runtime lookup as their existing aliases; broader Mythic placeholder coverage
remains open.

Incremental mechanic update: `setmaxhealth`/`modifymaxhealth` now map to
attribute-backed runtime mechanics with execution-time numeric placeholders;
full Mythic mechanic coverage remains open.

Incremental target-mechanic fix: targeter-bound Mythic `heal` now routes to
`heal-targets` instead of `heal-self`, so `heal @Target` heals the selected
entity rather than silently healing the caster; regression coverage is added.

Untargeted mechanic fix: Mythic-style `damage 5` now binds to the active-target
`damage-target` mechanic instead of being rejected as an unknown action.

Mechanic catalog fix: untargeted Mythic aliases `message`, `effect`, `ignite`,
and `knockback` now route to their existing target-aware factories instead of
being rejected as unknown actions.

Scoreboard-tag mechanic update: Mythic `addtag`/`removetag` and the explicit
`add-scoreboard-tag`/`remove-scoreboard-tag` aliases now map to caster and
targeter-aware `add-tag`/`remove-tag` actions backed by Bukkit scoreboard tags.
Runtime, targeter dispatch, and importer tests cover the path; Paper self-test
output now includes `scoreboard-tag-mechanics`, but a fresh matching Paper
release fixture is still required before promoting this evidence.

Score mechanic update: Mythic `setscore`/`addscore` and their explicit aliases
now map to the existing instance-scoped `score-set`/`score-add` actions. Runtime
conditions (`has-score`/`score-gte`), alias binding, and importer tests cover
the round trip; the isolated Paper self-test now records `score-mechanics=true`
on a managed instance. Scores remain Helstera instance state rather than native
Bukkit scoreboard objectives.

Target tag condition update: `targetHasTag`/`targetHasScoreboardTag` now map to
an explicit `target-has-tag` evaluator instead of reusing active-entity
`has-tag` semantics. Unit, migration, and isolated Paper target-condition
evidence cover absent/present/removed states.

Target state condition update: `targetHasPermission`, `targetHasItem`,
`targetGameMode`, `targetFoodLevel`, `targetAirLevel`, and
`targetPotionEffectActive` now map to dedicated target-only evaluators. They
fail closed when no explicit target exists and never read the caster as a
fallback; unit, migration, and exploratory Paper 1.21.11 r28 coverage protect
the distinction from the corresponding caster conditions.

The same target-only family includes `targetHasPotionEffect`, `targetHolding`,
and `targetWearing`, with dedicated canonical names, importer regression
coverage, and exploratory Paper 1.21.11 r30 evidence through the aggregate
target-state probe. Mythic `targetHasAuras{aura=...}` maps to the named Aura
registry and checks only the explicit target; this is distinct from the
historical internal PotionEffect-backed `target-has-auras` condition.

Faction condition update: Mythic `faction`, `targetfaction`, `samefaction`, and
`differentfaction` now map to caster/target exact faction evaluators. Exact
identity is deliberately separate from the existing allied/hostile faction
relations; unit, migration, and exploratory Paper 1.21.11 r32 evidence cover
same, different, and no-target fail-closed paths.

Owner condition update: Mythic `ownerIsPlayer` now checks both Bukkit
Tameable owners and Helstera PDC-backed custom owners; migration, unit, and
exploratory Paper 1.21.11 r33 custom-owner evidence cover the player-owner
path.

Importer alias update: common Mythic `shoot`/`shootprojectile` and `setintent`
mechanics now normalize to the existing `shoot-projectile` and `set-intent`
runtime factories. Importer regression tests cover keyed arguments and no
longer preserve these mapped mechanics as unsupported.

Command mechanic update: Mythic `command`, `commandself`, and
`commandtarget` now normalize to the existing console/self/target command
actions, preserving quoted command arguments instead of treating them as
unsupported mechanics.

Variable mechanic update: Mythic `setvariable`, `variableunset`,
`variableadd`, `variablemath`, `setvariablelocation`, and `locationunset` now
normalize to the existing typed variable/location actions, including named
values and the existing delay/repeat execution suffix.

Signal mechanic update: Mythic `signal{signal=...;payload=...;global=true}`
now maps to the existing scoped/global signal bus, while `~onSignal:Skill`
continues to map to the `on-signal` trigger. Importer regression coverage
protects payload and global-scope arguments.

Additional mechanic mappings: Mythic `suicide` and `setnameplate` now normalize
to the existing self-despawn and custom-nameplate runtime actions, with
importer regression coverage.

Target death mechanic update: Mythic `kill` now maps to a target-aware
`kill-targets` action, while `suicide` retains caster-only semantics.

Exploratory Paper 1.21.11 r38 verifies the live `target-kill=true` probe on a
real selected entity.

DoT mechanic update: Mythic `dot{damage=...;duration=...;interval=...}` now
maps to a bounded runtime DoT, including targeter-aware `@Target` execution;
legacy positional DoT syntax remains supported for compatibility. The
exploratory Paper self-test now verifies damage across multiple intervals and
no further damage after the configured duration (`dotMechanic=true`); strict
Paper 1.21.1 release evidence remains pending.

Broadcast mechanic update: Mythic `broadcast{message=...}` now maps to the
existing Adventure/Paper global broadcast action instead of being preserved as
an unsupported mechanic.

Runtime action audit update: Mythic `damage-melee`, threat aliases
(`addthreat`, `clearthreat`, `threatfocus`), and message aliases
(`messageall`, `messageself`, `randommessage`) now map to their existing
Helstera action factories. Direct short aliases bind through the same
canonical action names, and importer regression coverage protects the mapping.
Keyed message values now retain commas and quoted delimiters during migration;
importer tests cover message and radius isolation, and an importer-to-runtime
contract verifies delivery of the complete text to a mocked player. Strict
Paper fixture `srv-paper-message-comma-20261104-r1` imports a quoted message
with commas, an embedded key-like expression and a semicolon, and the online
client confirms exact receipt. Escaped double quotes and backslashes have
lexical, targeter-suffix and importer-to-runtime tests, including embedded
semicolons and braces. Strict fixture `srv-paper-message-escape-20261104-r2`
also verifies exact online-client receipt through `@trigger`. Other quoting
and escape forms remain unverified.
`threat-focus` now delegates to `ThreatTable.focus`, so it always promotes the
current target above the existing leader instead of adding a size-dependent
fixed amount. Exploratory Paper 1.21.11 r41 and strict Paper 1.21.1-133
`threatActions` verify all three threat actions against a live table.

Paper exploratory r42 and strict Paper 1.21.1-133 `damageMeleeMechanic` verify
a real nearby Sheep takes the configured damage while the managed caster
remains unharmed, covering the corrected caster-exclusion path.

Melee damage safety update: `damage-melee` now passes the caster entity as an
explicit exclusion to the nearby-entity query, preventing accidental self
damage while retaining nearby target damage; a Mockito regression covers both
the caster exclusion and target hit.

Condition migration update: common Mythic `inCombat`, `playersInRadius`,
`entitiesInRadius`, `mobsInRadius`, `randomChance`, `targetCount`, and
`hungerBelow` spellings now translate through `MmConditions` to registered
runtime evaluators. `mobs-in-radius` counts only valid non-player living mobs
and excludes the managed caster; migration and binder regression coverage is
included.

Exploratory Paper 1.21.11 r44 verifies the live evaluator chain for
`incombat`, `targetcount`, `mobsinradius`, and `randomchance 100` through the
structured `conditionUtilityAliases=true` probe.

The same migration table now covers caster-context Mythic `holding` and
`wearing` leaves, complementing the existing strict target-only variants;
translation regression coverage protects item arguments and fail-closed missing
arguments.

Target category migration update: Mythic `targetIsMonster`, `targetIsAnimal`,
`targetIsVillager`, `targetIsTameable`, and `targetIsRaider` now map to the
corresponding registered target-only evaluators, with importer regression
coverage.

Entity condition migration update: Mythic `entityType` and `entityTypeIs` now
map to the shared `entity-type-is` evaluator; `mobtype` is also an alias.
Nested `variant` constraints work for the variant getters exposed by Paper
1.21.1 (Axolotl, Cat, Fox, Frog, Mooshroom, Rabbit, Villager, Wolf, and Zombie
Villager). Importer, runtime, and migration-to-runtime tests cover alternatives,
wrong types, invalid variants, and preservation of unsupported constraints.
Strict fixture `srv-paper-entity-type-variant-import-strict-20261106-r1`
verifies all three imported names on a real Cat, including TABBY/BLACK matching
and RED rejection. Other official nested traits such as color, style,
profession/biome, genes, iron-golem cracks, and tropical-fish patterns remain
unimplemented.

Official condition alias update: Mythic `Burning`, `Blocking`, `Charged`,
`Crouching`, movement-state aliases, `Chance`, `Day`, `Dawn`, `Dusk`, and
`Sunny` now map to the existing Bukkit/time/weather evaluators, with migration
regression coverage.

Food saturation parity update: Mythic `FoodSaturation`/`hungerSaturation` now
map to caster-context `food-saturation` (and explicit target context maps to
`target-food-saturation`) using Bukkit `HumanEntity#getSaturation`; migration,
unit, and strict Paper self-test coverage protect comparator behavior. Strict
fixture `srv-paper-drowning-strict-20261102-r2` requires and passes the
`foodSaturationCondition` probe: a real online player at saturation 1.0 passes
`<=1`, 2.0 rejects it, and the original value is restored.

Exploratory Paper 1.21.11 r47 also records `foodSaturationCondition=true`.

Last-damage-cause parity update: official Mythic `LastDamageCause` now has a
separate entity-state evaluator reading `Entity#getLastDamageCause`, distinct
from the current event-context `damage-cause`; importer, unit, and strict Paper
coverage protect both semantics. Strict Paper 1.21.1-133 fixture
`srv-paper-drowning-strict-20261102-r2` requires the dedicated
`lastDamageCauseCondition` report probe and records it as `passed`; the probe
accepts stored FIRE and rejects ENTITY_ATTACK.

Exploratory Paper 1.21.11 r48 also records `lastDamageCauseCondition=true`.

Damage-amount parity update: official `DamageAmount` now reads the current
`EntityDamageEvent#getFinalDamage()` from the event-aware context, defaults to
`>0`, and fails closed outside damage-trigger contexts. Importer aliases,
unit coverage, and exploratory Paper r84 (`damage-amount-condition=true`) cover
the range and trigger paths.

Block-radius parity update: official `BlockTypeInRadius` now counts exact
Bukkit material matches in a spherical block radius, with `types`/`amount`/
`radius` migration arguments, material/tag rejection, and bounded scanning.
Unit/migration coverage and exploratory Paper r87 verify positive and negative
material paths with restoration.

Motion parity update: Mythic `MotionX`, `MotionY`, `MotionZ`, and `Velocity`
now map to target-aware velocity-component/vector comparators, with `motx`,
`moty`, and `motz` aliases preserved through the canonical binder. Unit,
migration, and Paper self-test coverage protect comparator direction and target
semantics.

World population parity update: official `PlayersOnline`, `PlayersInWorld`,
and `MobsInWorld` now map to global/world population comparators; the Mob
counter only includes valid Bukkit `Mob` entities. Exploratory Paper 1.21.11
r50 includes these checks in the passing `conditionUtilityAliases` probe.

Player radius parity update: official `PlayerWithin` and `PlayerNotWithin`
now evaluate online players around the explicit target when present, otherwise
the caster origin, with spectator filtering and world isolation. Migration and
unit coverage protect radius arguments; the Paper self-test verifies the
positive/negative pair around a real online player.

Exploratory Paper 1.21.11 r51 records `playerRadiusConditions=true` for the
same real online player used as the explicit target: `player-within 1` passes
and `player-not-within 1` fails, with world isolation applied.

Caster identity parity update: official Mythic `isCaster` now maps to an
identity comparison between the active target and managed caster, rather than
using generic target/caster fallback semantics; unit and migration coverage
protect same-entity and different-entity cases.

Owner availability parity update: official `OwnerIsOnline` now resolves both
PDC-backed and Bukkit Tameable owner IDs and requires an online Player, distinct
from `owner-is-player`; exploratory Paper 1.21.11 r52 records
`owner-online-condition=true` on a real online owner.

Owner comparison parity update: official `Owner`/`isOwner` now compares the
explicit target UUID with the caster's Bukkit Tameable or Helstera PDC owner;
unit, migration, and exploratory Paper r86 coverage include positive owner and
negative non-owner paths.

Children condition parity update: official `Children` now counts loaded valid
LivingEntity children whose parent UUID is held by the existing Bukkit/PDC
relation layer. Default amount 1, range migration, unit coverage, and
exploratory Paper r88 evidence cover positive one-child, `>=1`, and `>=2`
negative paths.

Inside/outside parity update: official `Inside` and `Outside` inspect the
target location for a non-air head block and open sky. Unit and migration
coverage plus latest strict Paper fixture `srv-paper-spawning-timing-20261105-r3`
verify both positive and negative results for each condition and confirm that
the original block type and BlockData are restored.

Fall-speed parity update: official `FallSpeed` now maps `speed`/`s` ranges to
the positive downward component of Bukkit velocity, with upward motion yielding
zero. Unit/migration coverage and exploratory Paper r91 motion evidence cover
both falling and upward paths.

Directional-velocity parity update: official `DirectionalVelocity`/`dvelocity`
now preserves named x/y/z ranges, per-axis absolute flags, and relative
orientation in the condition binder. Unit/migration coverage and exploratory
Paper r95 covers named-axis evaluation alongside existing motion checks.

Field-of-view parity update: official `FieldOfView`/`fov` now uses the caster
view vector, target vector, total cone angle, and Y-axis rotation offset. Unit,
migration, and exploratory Paper r96 cover forward, side rejection, and rotated
view paths.

Distance-from-location parity update: official `DistanceFromLocation` now
matches the explicit target against x/y/z/world and a distance range, failing
closed without a target or world mismatch. Unit/migration coverage and
exploratory Paper r97 verify exact and out-of-range paths.

Enchanting progress parity update: official `EnchantingLevel` and
`EnchantingExperience` now read explicit target Player level and Bukkit 0..1
experience progress, with default-zero and range migration. Unit/migration
coverage and exploratory Paper r92 verify positive/negative comparator paths
and restore the original player values.

Plugin condition parity update: official `Plugin`, `PluginExists`, and
`HasPlugin` now check Bukkit PluginManager enabled state with default
`MythicMobs`; unit/migration coverage and exploratory Paper r93 verify the
enabled `helsteraMobs` plugin and a missing-plugin negative path.

Inventory condition parity update: official `HasFreeInventorySlot` and
`HasFreeSlot` now map to `has-free-inventory-slot`, using Bukkit
`PlayerInventory#firstEmpty` with fail-closed non-player semantics; migration,
unit and Paper coverage include positive and full-inventory negative states.
Strict Paper 1.21.1-133 fixture
`srv-paper-free-inventory-slot-strict-20261106-r1` runs both imported aliases
through `SkillService` against a real player, rejects non-player and
invalid-argument cases, and restores inventory state. The exploratory Paper
1.21.11 r53 report remains compatibility evidence for that server version.

Name condition parity update: official `Name`/`name{n=...}` now maps to
`name-is`. Runtime matching is exact and prefers `Entity#getCustomName()`;
when no custom name exists it falls back to Bukkit `Entity#getName()`, while
missing names and empty arguments fail closed.

Strict Paper 1.21.1-133 fixture
`srv-paper-name-import-strict-20261106-r2` runs both imported aliases through
`SkillService` on a real managed Zombie. It verifies quoted custom-name matching,
default-name rejection while a custom name exists, Bukkit entity-name fallback
after clearing it, and preservation of unknown, duplicate, and empty arguments.
The full 369-probe report and compressed server/client logs are recorded in
`tools/paper-integration/release-evidence.json`. Exploratory Paper 1.21.11 r54
also records the existing runtime `nameCondition` probe.

Server condition parity update: `ServerIsPaper`/`IsPaper`, `ServerVersion`,
`ServerAfter`, and `ServerBefore` now map through the importer to registered
Paper identity and Minecraft-version conditions. Strict Paper 1.21.1-133
fixture `srv-paper-server-conditions-strict-20261106-r1` passes the exact
version, higher/lower controls, inclusive/exclusive equality boundaries, and
unknown/duplicate/invalid argument preservation checks. Its 370-probe report,
compressed server/client logs, and tested plugin hash are in
`tools/paper-integration/release-evidence.json`.

Target AI relation parity update: official `TargetWithin` now evaluates the
active entity's vanilla `Mob#getTarget()` distance, with compatibility fallback
to execution distance for non-Mob contexts; `TargetNotWithin` is its fail-closed
negative counterpart. `TargetInLineOfSight`/`TargetNotInLineOfSight` now inspect
the active entity's AI target separately from caster-to-target `LineOfSight`.
Migration and unit coverage protect the distinction.

Exploratory Paper 1.21.11 r49 records `motionConditions=true` after setting a
real managed target velocity to `(0.25, -0.5, 0.75)` and checking all three
components plus vector magnitude.

Cooldown parity update: official Mythic `GlobalCooldown` aliases (`gcd`,
`setgcd`, `setglobalcooldown`) now map from tick-based input to the existing
runtime cooldown action, and `offgcd` maps to a shared condition key. Cooldown
state is now instance-scoped and reclaimed by `SkillExtras.forget`; migration,
alias binding, and cleanup coverage protect cross-instance isolation.

Exploratory Paper 1.21.11 r45 verifies the live sequence
`offgcd -> gcd 40 -> blocked -> clear -> offgcd` through the structured
`cooldownMechanics=true` probe.

SetSkillCooldown parity update: official Mythic aliases (`setSkillCooldown`,
`skillCooldown`, `setskillcd`, `skillcd`) now update the existing named-skill
cooldown table by caster instance, support zero-duration clearing, and clean up
through `SkillService.forget`. Exploratory Paper 1.21.11 r46 verifies a named
skill transitions from ready to dynamically cooling and back to ready after
clearing.

Exploratory Paper 1.21.11 r37 additionally verifies the live runtime action:
the scoped signal reaches its subscription with the expected payload and source
instance ID, and the structured report records `signal-mechanics=true`.

Targeted percent-heal fix: `heal-percent @Target` now uses a target-aware
percent-heal factory rather than the caster-only implementation.

Targeted health-mechanic fix: targeter-bound `set-health`, `modify-health`,
`set-max-health`, and `modify-max-health` now modify selected entities rather
than silently falling back to the caster.

Targeted naming fix: `set-name @Target` now uses a target-aware name factory,
preserving visible/hidden name semantics on the selected entity.

Targeted entity-state fix: targeter-bound `set-ai`, `set-gravity`, `set-silent`,
`set-collidable`, `set-fire`, and `clear-fire` now operate on selected entities
instead of silently falling back to the caster.

Targeted visibility/protection fix: targeter-bound `invisible`, `glowing`, and
`set-invulnerable` now modify selected entities instead of the caster.

Targeted potion cleanup fix: targeter-bound `remove-effect` and
`remove-all-effects` now remove effects from selected entities instead of the
caster.

Targeted movement fix: targeter-bound `modify-speed` and `set-tick-speed` now
scale selected entity movement attributes instead of the caster.

Targeted leap fix: `leap @Target` now applies vertical velocity to selected
entities instead of the caster.

Targeted equipment fix: targeter-bound `equip` and `set-item` now place the
resolved item on selected entities rather than the caster.

Targeted drop fix: targeter-bound `drop-item` now drops at selected entity
locations rather than always using the caster location.

Multi-target mechanic fix: `damage @Targets`, `message @Targets`, and
`explode @Targets` now apply to every selected entity/player/location instead of
silently using only the first target; the targeter dispatch path and mechanics
have regression coverage.

Targeted percent-damage fix: `damage-percent @Targets` now has a dedicated
multi-target factory and applies the percentage of each selected entity's own
maximum health instead of silently damaging only the first target.

Self-movement fix: `dash` and `blink` remain caster movement mechanics when a
targeter is present; `forward`, `near`, and `away` direction modes now resolve
distinctly, and movement distances support execution-time placeholders.

Migration coverage fix: the importer now recognizes runtime-backed `leap` and
`set-tick-speed` mechanics (including named argument aliases) and the binder
accepts the no-separator `settickspeed` spelling.

Audience migration fix: Mythic `actionbar{m=...}` now maps to the existing
target-scoped actionbar delivery path. Runtime and importer paths also preserve
Mythic title, subtitle, fade-in, stay, and fade-out values for the active player
target.

Importer syntax fix: mechanics whose argument block ends at end-of-line no
longer fall through to legacy whitespace-suffix parsing, so values containing
spaces are not duplicated into the migrated action.

Metaskill condition migration fix: `TargetConditions` and `TriggerConditions`
now use the shared `MmConditions` translation table, while unknown leaves stay
in the generated YAML with explicit warnings instead of silently becoming
runtime no-ops.

Drop/spawner condition migration fix: shared condition translation now covers
world/biome/weather/time and health-percent leaves, and safe DropTable
projections translate Mythic `?` condition prefixes before runtime evaluation.

Condition grammar fix: bare Mythic leaves with whitespace arguments (for example
`?world-is arena` and `?permission loot.vip`) now split into a condition name
and argument before translation, matching brace-based forms.

Skill grammar boundary update: `MythicSkillSpec` now accepts finite chance
percentages in ordinary decimal, leading-decimal, and scientific notation forms
such as `25%`, `.5%`, and `1e1%`. Values outside `0..100`, non-numeric tokens,
and malformed percent tokens such as `-1%` or `NaN%` fail closed instead of
being passed through as positional mechanic arguments. `MythicSkillSpecTest`
covers accepted and rejected forms, including trailing text after a closed
argument block. `SkillService` now routes every skill line containing `%`
through the same parser, so malformed percentages cannot bypass validation at
the runtime binding entry point; complete official skill grammar remains
mapped-unverified.

Drop chance syntax rejects misplaced or repeated percent signs, preserves the
invalid source entries with warnings, and retains valid siblings. Strict Paper
fixture `srv-paper-drop-chance-strict-20261104-r1` verifies import and runtime
rolling for these cases; it does not verify them through natural death.
Fixture `srv-paper-drop-numeric-strict-20261104-r1` extends this check to Java
hexadecimal floating-point syntax and numeric type suffixes. Decimal and
scientific notation remain supported; inline mob death remains unverified.

Spawner importer fix: Mythic spawner condition lists now translate known leaves
while preserving Helstera-native lunar/faction/region predicates; unknown
conditions remain visible with warnings.

Scalar condition compatibility fix: spawner conditions, immunity/damage modifier
conditions, drop conditions, and Mythic importer lists now share a delimiter-aware
parser. Comma-separated scalar conditions are split without breaking
`time-between=0,12000`, coordinate regions, quoted values, or nested brace
arguments; YAML list forms remain supported.

Movement placeholder fix: `leap`, target-aware leap, speed modifiers, `pull`,
and relative self-teleport now resolve numeric placeholders at execution time;
`pull` also excludes the caster from its nearby-entity set.

Incremental condition update: positive `isAlive` and `targetIsAlive` aliases now
have runtime evaluation and migration mappings alongside their existing dead/
validity conditions.

Incremental mechanic update: Mythic `feed` now maps to a player food/saturation
mechanic, including target-aware execution and importer coverage.

Incremental mechanic update: Mythic `disarm` now removes the active target's
main-hand item, with target-aware runtime and importer coverage.

Incremental mechanic update: targeter-bound `damage-percent` now applies the
configured percentage to every selected entity's own maximum health, with
regression coverage alongside the existing multi-target damage mechanics.

Incremental trigger update: managed `remove-effect` and
`remove-all-effects` mechanics now explicitly bridge to `on-potion-effect-end`
when Paper does not emit a Bukkit removal callback; mechanic and event paths
are deduplicated per entity/effect/tick.

Configuration correctness update: YAML faction child sections now preserve
their own `display` and `allies` fields instead of accidentally reading the
parent section; a YAML-level regression test covers the parsed display names
and symmetric alliance closure.

Incremental stance update: Mythic `setstance` now stores an instance-scoped
stance independently from the AI FSM state, `stance`/`stanceIs` conditions read
that value, and importer/runtime regression coverage is present.

AI lifecycle update: `AiManager` now retains and unregisters its event-bus
listeners across reloads, detaches controllers when `ModelRemoveEvent` is
published after an instance despawns, and uses the same detach path during
stop. This prevents stale controller, profile, faction, and stance state from
surviving entity removal or plugin restart.

Exploratory Paper 1.21.4 fixture `srv-paper-integration-exploratory-1.21.4-r33`
recorded `potion-effect-end=true potion-effect-action=true potion-events=3` for
the mechanic-driven removal path, alongside natural expiry evidence. This does
not establish Paper 1.21.1 native drinking or release timing parity.

Incremental AI evidence update: exploratory Paper 1.21.4 fixture
`srv-paper-integration-exploratory-1.21.4-r27` passed the server-side
`ai-selector-core` probe for player/monster/clear/owner/faction/conditional
selection and native `Mob#setTarget` synchronization. This does not upgrade
the Paper 1.21.1 release evidence boundary.

SkillTree condition update: `branch-unlocked`/`branch-locked` conditions and
`hasBranch` alias now read the shared `SkillTreeContext` branch state, allowing
branch-gated skill logic to be expressed in normal condition chains.

Incremental targeter update: skill-side `@nearestConditionalTarget`/
`@nearestConditionalEntity` now preserve nested condition arguments and use the
same bound condition catalog as AI selectors. `MythicTargeterSpec` now parses
both quote styles, escaped quote characters, and strictly paired `[]`, `()`,
and `{}` nesting so delimiters inside arguments are not split accidentally;
focused parser and selector tests cover the behavior. Broader targeter parity
remains open.

Incremental state-mechanic update: common Mythic player/entity state actions now
include `set-sneaking`, `set-gliding`, `set-swimming`, `set-sprinting`,
`set-flying`, `set-no-damage-ticks`, `set-maximum-no-damage-ticks`, and
`set-walk-speed`, with targeter-bound variants, execution-time numeric
placeholders, importer aliases, and Bukkit unit coverage; live Paper timing and
the complete mechanic catalog remain unverified.

Exploratory Paper 1.21.11 r126 records `stateConditions=passed` for real
flying, sneaking, and gliding state after isolating the player from earlier
natural damage probes. The historical `stateBlocking` gap was caused by the
fixture cancelling the client's active-use state before evaluation; strict
Paper fixture `srv-paper-integration-exp-20261031-r27` now keeps a real
Mineflayer Shield-use window and records `stateBlocking=passed`.

Incremental condition update: `is-collidable`, `has-offhand`,
`target-is-collidable`, and `target-has-offhand` now have Bukkit evaluators,
Mythic importer aliases, and unit coverage; the complete condition catalog
remains unverified.

Incremental placeholder update: caster/target placeholders now expose
`is-collidable`, `has-offhand`, and `offhand.type`, with alias/catalog unit
coverage; the broader Mythic placeholder catalog remains open.

The same placeholder path now exposes player food/saturation/exhaustion,
remaining/max air, luck/armor attribute values, and main-hand item type;
exploratory Paper 1.21.11 r18 records `gameplay-placeholders=true` in the
structured self-test.

Exploratory Paper follow-up: `srv-paper-integration-exploratory-1.21.11-r8`
recorded `collidable-condition=true` and `offhand-condition=true` for the
caster/target aliases on a real online player; the run remains compatibility
evidence only because it uses Paper 1.21.11.

Incremental targeter update: context targeters now resolve Mythic
`@Children`, `@Passengers`/`@Passenger` and `@Vehicle` relations from the managed caster;
API and importer tests cover direct selection and suffix normalization.

Exploratory Paper follow-up: `srv-paper-integration-exploratory-1.21.11-r9`
records `relation-targeters=true` after resolving those relations with a real
online player, managed Wolf, and Pig vehicle; this remains version-specific
compatibility evidence.

Potion client follow-up: the opt-in raw protocol client avoids Mineflayer's
Potion component decoder. Fixture `srv-paper-integration-exploratory-1.21.11-r14`
received the server-issued Potion and Paper logged a real `PlayerItemConsumeEvent`
through the `on-consume` bridge; this remains exploratory 1.21.11 evidence.

This moves real player drinking out of the client-tool-only gap; native
`on-potion-effect-end` timing and the strict Paper 1.21.1 target remain open.

The follow-up r16 report registers dynamic probe `playerPotionConsume=passed`
from the explicit `player-potion-consume event=true` log, making the raw-client
drinking evidence auditable without changing the release probe catalog.

The short-effect r17 fixture also registers `playerPotionNaturalExpiry=passed`
with `expired=true action-observed=true`, proving a real player's natural
Potion expiry reaches the managed `on-potion-effect-end` action on Paper 1.21.11.

Raw-client fixtures r136/r137 re-arm hotbar slot 1 immediately before use.
r136 records `playerPotionConsume=passed` with `item=POTION`; r137 records
both `playerPotionConsume=passed` and `playerPotionNaturalExpiry=passed` with
`expired=true action-observed=true`. Stable exploratory Paper 1.21.11 evidence
is now available; strict Paper 1.21.1 timing remains open.

Potion trigger context update: `on-buff` and `on-potion-effect-end` now pass the
affected living entity as both the active target and inherited target list, so
target-only conditions/actions and target placeholders work consistently for
event-driven and polled expiry paths. The managed-entity expiry probe uses
`set-name-targets` to exercise this context.

Follow-up r10 records `children-targeter=true` for a real Pig child linked to
the managed Wolf, completing exploratory Paper coverage for the relation
targeter family.

Incremental legacy-data update: corrected the standard 1.12 item boundary to
`451=IRON_NUGGET` and `452=KNOWLEDGE_BOOK`, preserved `453` as unsupported, and
added white/red bed data variants; unknown numeric values remain explicit.

Incremental event update: managed entities now expose `on-tame` and `on-breed`
through Bukkit `EntityTameEvent`/`EntityBreedEvent`; natural Paper event timing
and broader event coverage remain unverified. Fixture
`srv-paper-integration-exploratory-1.21.11-r23` dispatches both events to real
managed Paper entities and verifies the configured actions; this is
event-dispatch evidence, not natural player-action timing.

Player event update: managed player entities now expose `on-world-change`,
`on-jump`, and `on-swing` through Bukkit/Paper player events; the exploratory
event self-test also verifies `on-interact` and all three player actions on
managed entities/player, while natural event timing remains unverified.

Player lifecycle update: managed players now expose `on-quit` through
`PlayerQuitEvent`; the exploratory event self-test now verifies the configured
action on a managed online player, while natural disconnect timing and restart
persistence evidence remain unverified.

Bed lifecycle update: managed players now expose `on-bed-enter` and
`on-bed-leave` through Bukkit bed events; natural Paper timing remains
unverified.

Bed trigger evidence update: the exploratory event self-test now exercises both
bed events against the managed online player and verifies their configured
actions. Natural player bed timing and the Paper 1.21.1 release target remain
open.

Leash lifecycle update: `on-unleash` now handles both `EntityUnleashEvent` and
`PlayerUnleashEntityEvent`, complementing the existing `on-leash` path; natural
Paper timing evidence remains unverified.

Trade event update: managed villagers now expose `on-trade` through Paper's
`PlayerTradeEvent`, with the player preserved as the target; natural event
timing evidence remains unverified. The exploratory event self-test now also
verifies the configured action on a real managed Villager with a real
`MerchantRecipe` event payload.

Leash trigger evidence update: fixture
`srv-paper-integration-exploratory-1.21.11-r24` binds a real Wolf instance to
`on-leash`/`on-unleash`, exercises player-driven leash events, and records the
dedicated `leashLifecycle=passed` probe. Natural leash timing and the Paper
1.21.1 release target remain open.

Item lifecycle update: managed players now expose `on-item-drop` and
`on-item-break` through Bukkit player events, complementing `on-item-pickup`;
`on-item-damage` is covered by the same Bukkit player event family. The
exploratory event self-test now verifies all three configured actions; natural
Paper timing evidence remains unverified.

Fishing/durability update: managed players now expose `on-fish` and
`on-item-damage` through Bukkit player events, with caught-entity targeting for
fishing; the exploratory event self-test now verifies `on-fish` and the
managed player target path alongside item damage, while natural event timing
remains unverified.

Direct condition alias update: official camel-case state names such as
`isOnGround`, `isInWater`, `isInLava`, `isInAir`, `isOnFire`, `isBlocked`,
`standOn`, and `daylight` now bind through the same canonical Bukkit evaluators
as their normalized forms. Runtime binding tests protect the direct hand-written
skill path in addition to importer mappings.

Time/weather catalog update: `day`, `daytime`, `dawn`, `morning`, `noon`, `dusk`,
`sunset`, `midnight`, `nighttime`, `clear`, `sun`, `sunny`, `rain`, `storm`,
`thunder`, and `thunderstorm` now have direct `SkillExtras` condition factories.
The importer also translates their parameterless Mythic spellings to registered
runtime conditions; `MmConditionsTest` covers the aliases and the runtime binding
contract keeps missing factories fail-closed.
`TimeWeatherConditionTest` verifies registration and fail-closed behavior without
a context; the full Mythic condition catalog remains open.

Shear update: `on-shear` now also consumes `PlayerShearEntityEvent`, preserving
the player actor instead of relying only on `EntityTransformEvent`. The
exploratory event self-test now verifies the configured action on a real
managed Sheep; natural event timing remains unverified.

## 1. Skills

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Snowing condition | implemented-runtime | `snowing` and `isSnowing` use the target location, world storm state, and height-adjusted block temperature. Importer/runtime tests and strict Paper 1.21.1-133 fixture `srv-paper-snowing-strict-20261105-r2` verify target precedence, both aliases, invalid arguments, missing context, warm-biome and clear-weather rejection, and full state restoration. The Bukkit API does not expose local precipitation type, so cold custom biomes configured without precipitation remain a documented boundary. |
| LookingAt condition | implemented-runtime | `?lookingAt{b=...}`/`block=...` and `?lookingAt{e=...}`/`entity=...` imports preserve selector type and distance. `MmConditionsTest`, `MigrationRuntimeConditionContractTest`, and report-parser tests cover aliases, material/entity-name ambiguity, malformed and conflicting arguments, wrong hit types, distance rejection, and fail-closed non-player contexts. Strict Paper 1.21.1-133 fixture `srv-paper-looking-at-import-strict-20261106-r1` verifies imported block and entity paths with real ray traces, positive and negative controls, invalid-input preservation, and cleanup in the complete 370-probe catalog. Natural client-originated view direction remains unverified. |
| EntityType / EntityTypeIs / MobType conditions | implemented-runtime | The shared selector supports Paper 1.21.1-readable `variant`, `color`, Horse `style`, Panda genes, Villager/Zombie Villager `biome` and `profession`, Iron Golem `crack` derived from health, and Tropical Fish `bodyColor`/`patternColor`/`pattern`. Runtime readers cover Horse, Sheep (including `jeb_` as `RAINBOW`), Shulker, Parrot, Llama/Trader Llama, Axolotl, Cat, Fox, Frog, Mooshroom, Rabbit, Villager, Zombie Villager, Wolf, Panda, Tropical Fish, and Iron Golem. Importer, selector, SkillConditions, and migration-to-runtime tests cover aliases, canonicalization, alternatives, all-traits-must-match, invalid values, wrong-type/trait rejection, and crack thresholds. Strict fixture `srv-paper-entity-type-traits-strict-20261106-r1` verifies the imported runtime paths on real Paper entities; `importedEntityTypeVariantCondition` separately retains the three input-name and Cat-variant checks. The official EntityType page also lists Cow/Chicken/Pig `variant`, but Paper 1.21.1 exposes no public getter for these traits, so those selectors remain untranslated with a migration warning; newer Zombie Nautilus and Copper Golem variants are outside this Minecraft version. See the [official EntityType constraints](https://wiki.mythiccraft.io/mythicmobs/Skills/Conditions/EntityType) and Paper 1.21.1 [Cow](https://jd.papermc.io/paper/1.21.1/org/bukkit/entity/Cow.html), [Chicken](https://jd.papermc.io/paper/1.21.1/org/bukkit/entity/Chicken.html), and [Pig](https://jd.papermc.io/paper/1.21.1/org/bukkit/entity/Pig.html) APIs. The complete Conditions catalog remains `mapped-unverified`. |
| IsUsingSpyglass / IsScoping conditions | implemented-runtime | Official `isUsingSpyglass` and `isScoping` aliases import to the `is-using-spyglass` SkillService evaluator, which requires an explicit target Player with an active item of `Material.SPYGLASS`; absent, inactive, non-player, and malformed inputs fail closed. Importer/runtime contract tests and strict Paper fixture `srv-paper-is-spyglass-import-strict-20261106-r1` verify both imported aliases against a real active Player and reject the inactive state, a Zombie target, and invalid arguments in the complete 360-probe release catalog. This implements Bukkit-observable active Spyglass use; client scope/zoom details beyond Paper's exposed active-item state are not claimed. The wider Conditions catalog remains `mapped-unverified`. |
| IsInSurvivalMode / InSurvivalMode conditions | implemented-runtime | Both official aliases import to `is-in-survival-mode`, which reads only an explicit target Player's `GameMode.SURVIVAL`; non-player or absent targets fail closed. Importer tests reject unknown parameters for both aliases, migration-to-runtime contract tests verify the aliases and target-type boundary, and strict Paper fixture `srv-paper-is-survival-mode-import-strict-20261106-r1` verifies Survival/Adventure states, both imported aliases, a Zombie target, malformed arguments, and game-mode restoration in the complete 361-probe release catalog. The complete Conditions catalog remains `mapped-unverified`. |
| IsFrozen condition | implemented-runtime | Official `isFrozen` and `frozen` aliases import to the `is-frozen` SkillService evaluator, which reads Paper `Entity#isFrozen()`. Importer/runtime contract coverage and strict Paper fixture `srv-paper-is-frozen-import-strict-20261106-r2` verify both aliases on a real frozen Zombie, reject them after thawing, and reject invalid arguments in the full 359-probe release catalog. The complete Conditions catalog remains `mapped-unverified`. |
| Angry and pollinated conditions | implemented-runtime | Mythic `angry`/`isAngry` evaluates Bee anger, Wolf anger, or Vex charging; `pollinated`/`isPollinated`/`hasNectar` evaluates Bee nectar. Import aliases, runtime consumers, missing-context and unsupported-entity fail-closed behavior have importer, SkillService, and migration-to-runtime contract tests. Strict Paper 1.21.1-133 fixture `srv-paper-angry-pollination-strict-20261105-r1` verifies real runtime consumers in the complete 341-probe release catalog. Supported entities and aliases match the [official MythicCraft changelog](https://wiki.mythiccraft.io/mythicmobs/changelogs/Changelogs?version_id=d7c47c878837c833a7602dcf02bca7b9b87056dd); the wider Conditions catalog remains open. |
| Sheared entity conditions | implemented-runtime | `isSheared` and `targetIsSheared` map into self/target runtime checks for Sheep and Bogged via `isSheared()`, and Snow Golems via `isDerp()`. API and importer-to-runtime tests cover positive/negative states, unsupported entities, and missing context. Strict Paper 1.21.1-133 fixture `srv-paper-is-sheared-strict-20261105-r1` verifies all three real entity types in both states and passes `entityShearedStates` in the complete 342-probe release catalog. The remaining Conditions catalog stays open. |
| Mythic `isChild` / `child` condition | implemented-runtime | Importer aliases map to the caster-to-target `is-child` parent relation, distinct from `isBaby` age state. Importer/runtime contract tests cover the translated condition. Strict Paper 1.21.1-133 fixture `srv-paper-is-child-import-strict-20261105-r1` executes `?isChild` through `MmConditions.translate` and `SkillService`: a related adult Pig passes, the unrelated baby Pig fails `isChild`, and age controls pass/fail independently in the complete 350-probe release catalog. The complete Conditions catalog remains `mapped-unverified`. |
| In-combat condition | implemented-runtime | Requires a valid, live target. For the managed caster entity, the runtime reads `AiController` state when available and otherwise checks Bukkit `Mob#getTarget()`; non-Mobs, missing targets, dead targets, and invalid targets fail closed. `AiControllerTest`, `SkillConditionsTest`, and `MigrationRuntimeConditionContractTest` cover controller/native behavior and runtime binding. Strict Paper 1.21.1-133 fixture `srv-paper-in-combat-strict-20261105-r3` independently verifies managed-caster and native-Mob positive, no-target, and restoration paths in `inCombatCondition`; aggregate coverage remains in `conditionUtilityAliases`. The full Conditions catalog remains `mapped-unverified`. |
| Owner and faction target filters | implemented-runtime | Generic entity targeters support `targetowner=true/false`, `targetsamefaction=true/false`, and `ignore=owner|faction`. Owner matching uses Helstera's mob-owner relation or Bukkit `Tameable` owner UUID; same-faction means exact faction ID equality, not allied factions. Positive relation options override generic `target=` categories; negative options exclude the relation, and `targetall=true` bypasses both. `target=owner|faction`, duplicate options, and malformed booleans fail closed. `TargeterOrderingTest` covers candidate ordering, category precedence, world targeters, and invalid syntax. Strict Paper fixture `srv-paper-targeter-relations-strict-20261105-r3` verifies the same behavior with real Paper entities in the complete 340-probe release catalog. External plugin identity and premium target filters remain open. |
| Skill loading and metaskill references | implemented-runtime | `SkillService`, nested `cast-skill`, recursion guards, shared `SkillTreeContext` tests, and standalone Mythic `skill{s=...}` importer references |
| Delay, repeat, repeat interval, cast duration | implemented-runtime | deterministic scheduler and `SkillServiceTest`; strict Paper 1.21.1-133 `skillScheduling` runs `delay=2;repeat=3;repeat-interval=2` through the production `SkillService`/Bukkit scheduler, verifies zero calls before the first delay and exactly four executions after the repeat interval |
| TargetConditions, TriggerConditions, and `?@` inline conditions | implemented-runtime | `?@condition` filters each entity after its line targeter runs; `?condition` remains a whole-skill gate. Location and Pin targeters reject `?@` because they do not produce entity targets. Parser, importer, and cross-module runtime tests cover filtering and malformed conditions. |
| Mythic `isInAudience` condition | implemented-runtime | `isInAudience`, `inaudience`, and `audiencecontains` map to a runtime condition: Player targets pass when no execution audience is attached, membership is checked when a set is present, and non-Player targets fail. `BehaviorContext` distinguishes an absent audience from an explicitly empty one and preserves it through location targeting, target scopes, nested skills, and queues. `?@isInAudience` applies this check after entity selection. Importer, runtime, context, and migration-to-runtime tests cover aliases, both audience states, filtering, and rejection boundaries. Strict Paper fixture `srv-paper-execution-audience-strict-20261106-r1` verifies the imported condition and per-target filtering. [Official changelog](https://wiki.mythiccraft.io/mythicmobs/changelogs/Changelogs?version_id=d7c47c878837c833a7602dcf02bca7b9b87056dd) |
| Mob/skill `ExecutionAudience` and audience mutation mechanics | implemented-runtime | Importer maps mob `Options.ExecutionAudience` to `mob.execution-audience` and skill `ExecutionAudience` to `skills.<id>.execution-audience`; runtime resolves `NONE`, built-in forms (`self`/`caster`, `target`, `world`, `nonselfworld`, `tracked`, `nearby`) and supported `@Targeter` selectors. `AddToAudience`, `RemoveFromAudience`, and `UnsetAudience` map to runtime actions with named audience and nested mob-targeter arguments. Runtime tests cover skill-scope changes, mob updates, summon inheritance, and absent-versus-empty audience persistence; entity audiences are stored in PDC and updates request visibility refresh. Strict Paper fixture `srv-paper-execution-audience-mutation-strict-20261106-r1` verifies imported skill configuration/actions, explicit-empty behavior, PDC writes, and two-client visibility transitions. Strict fixture `srv-paper-execution-audience-mob-strict-20261106-r5` verifies imported Mob `Options.ExecutionAudience`, production mob spawn, `@PlayersInRadius` member selection, outsider exclusion, PDC persistence, and member-visible/outsider-hidden client state; it also verifies imported `RemoveFromAudience`/`AddToAudience` with `@EntitiesInRadius`, empty/restored PDC state, two-client visibility transitions, and imported `summon` audience inheritance with member-only model visuals. A separate two-boot Paper report confirms the same persistent mob entity UUID, its exact non-empty PDC audience, and the rebound runtime instance audience survive a real server stop/start. Post-restart client visibility and complete MythicMobs catalog parity remain unverified. See the [restart evidence report](tools/paper-integration/reports/paper-1.21.1-execution-audience-restart-20261106-r3.json). |
| Variables and location variables | implemented-runtime | scoped variables, `variable-unset`, `VariableLocations`, `@VariableLocation` tests |
| Inline conditions | mapped-unverified | parser and repeat guards exist; full Mythic condition catalog is not covered |
| Context targeters | mapped-unverified | `@trigger`, `@attacker`, `@owner`, `@parent`, `@children`, `@passengers`, `@vehicle`, `@threat`, `@target`, `@targets`, `@VariableLocation`, location-valued `@Pin` (all named points by default; `random=true` selects one) and `@BlocksInPinRegion` (inclusive cuboids, capped at 16,384 block positions; both consumed by location-aware mechanics), direct `@EntitiesInRadius`/`@LivingEntitiesInRadius`, `@NearestEntity`/`@NearestLiving`/`@NearestLivingEntity`/`@NearestMob` aliases, `@NearestPlayer`/`@NearestMonster`/`@NearestAnimal`/`@NearestVillager`/`@NearestTamed`, world-scoped `@PlayersInWorld`/`@MobsInWorld`/`@EntitiesInWorld`/`@LivingEntitiesInWorld`, server-wide `@PlayersOnServer`/`@server`/`@everyone`, radius/filter/chain targeters, and category selectors `@MonstersInRadius`, `@AnimalsInRadius`, `@VillagersInRadius`, `@TamedInRadius` have runtime paths and focused API/importer tests; strict Paper fixtures `srv-paper-runtime-alias-20261031-r2` and `srv-paper-targeter-players-strict-20261102-r1` record real world/nearest/category and server-player selection; fixture `srv-paper-pin-targeters-strict-20261102-r2` requires and passes `pinTargeters`, exercising SkillService multi-point/random dispatch, `MovePin` single-point movement and multi-point rejection, cuboid block set/break/restoration, and fail-closed rejection of a 16,385-block region before action dispatch; project pin YAML now accepts `pins`/`Pins` section and point-field casing variants, while official Mythic pack serialization, cross-world live-client coverage, and complete targeter parameter parity remain unverified |
| Tracked location | implemented-runtime | `TrackLocation` stores the selected target location in the skill-tree snapshot; `@TrackedLocation` resolves it, and `DistanceFromTrackedLocation` checks the non-player caster's distance in the same world. Importer/runtime tests cover mechanic and targeter aliases, supported ranges, invalid syntax, missing locations, player-caster rejection, cross-world handling, and snapshot restore. Strict Paper 1.21.1-133 fixture `srv-paper-tracked-location-strict-20261107-r1` verifies the imported action, targeter, distance condition, and missing-position rejection in the complete 382-probe release catalog. Mythic documents that players cannot have tracked locations; broader official targeter/modifier parity remains part of the open targeter catalog. |
| SelfEyeLocation targeter | implemented-runtime | `@SelfEyeLocation`, `@eyeDirection`, `@casterEyeLocation`, `@bossEyeLocation`, and `@mobEyeLocation` resolve to the living caster's eye position; unknown arguments and missing/non-living caster contexts fail closed. API/importer tests cover aliases and rejection; strict Paper 1.21.1-133 fixture `srv-paper-self-eye-location-strict-20261104-r1` verifies all five aliases through the production `set-block` action. |
| Targeter argument validation | implemented-runtime | Runtime rejects duplicate generic and specific filter options, malformed booleans, trailing syntax, incomplete chains, and unknown entity filters instead of selecting unintended targets. `TargeterOrderingTest` covers direct, context and chained paths; strict Paper fixture `srv-paper-target-all-filter-strict-20261105-r1` verifies `targetall=true` against conflicting filters using real Paper candidates. This does not establish complete targeter grammar parity. |
| Entity `target`/`ignore` filters | implemented-runtime | Generic living-entity targeters accept comma-separated `target=` and `ignore=` filters for player, mob, living, animal, monster, creature, villager, tamed, creative, spectator, self/caster, ground, water, flying, and ArmorStand categories (`armorstand`/`armor_stand`), plus Bukkit `EntityType` names; `targetself=true/false` controls caster inclusion. The documented `targetplayers`, `targetcreative`, and `targetspectator` switches override matching generic category filters; creative/spectator switches have priority over `targetplayers` for those modes, while unrelated player-specific filters remain active. `targetinvulnerable=false` excludes invulnerable entities and `true` overrides generic `target`/`ignore` categories for them. `targetarmorstands=false` excludes ArmorStands and `true` overrides generic categories. `targetanimals`, `targetcreatures`, and `targetvillagers` each support explicit include/exclude; their `true` values override generic target/ignore filters for matching entities, with animal/villager options taking precedence over the broader creature option. `targetall=true` bypasses these entity-category, `target`, `ignore`, and specific filters for the candidates already selected by the targeter; it does not expand radius or world scope. `targetall=false` leaves ordinary filtering active. Player-specific options still apply unless `targetall=true`. The documented movement-state categories are mutually exclusive: ground is `isOnGround() && !isInWater()`, water is `isInWater() && !isOnGround()`, and flying is `!isOnGround() && !isInWater()`; overlap matches none. Empty, unknown, repeated, malformed, or non-boolean options fail closed. `TargeterOrderingTest` covers each state and mode, category boundaries, positive/negative filters, `targetall` precedence, quoted lists, caster inclusion/exclusion, and rejection. Strict Paper 1.21.1-133 fixture `srv-paper-target-all-filter-strict-20261105-r1` passed 339 probes (320 passed, 19 expected rejections, zero incomplete) and verifies `targetall` precedence with real candidates alongside Sheep, Zombie, Villager, player, ArmorStand, invulnerability, player-mode, movement-state, and flying Phantom evidence. Earlier fixtures `srv-paper-creature-target-filter-strict-20261105-r1`, `srv-paper-target-armorstands-strict-20261105-r1`, `srv-paper-target-invulnerable-strict-20261104-r1`, `srv-paper-game-mode-specific-target-filter-strict-20261104-r2`, `srv-paper-game-mode-target-filter-strict-20261104-r1`, `srv-paper-flight-target-filter-strict-20261104-r1`, and `srv-paper-target-filters-20261104-r1` retain historical coverage. Managed identity filters accept `target=mythic`, `target=vanilla`, and `target=nonmythic`, with matching `ignore=` values and the `targetvanilla`/`targetnonmythic` boolean alias. `ManagedMobIdentity` stores the mob-definition ID in entity PDC; strict fixture `srv-paper-targeter-identity-strict-20261105-r1` verifies the `targeterIdentityFilter` probe in the 340-probe release catalog. This identifies entities spawned by Helstera only. External MythicMobs/Citizens identity, non-living display/interaction targets, and premium filters remain uncovered; see [official Targeter filter documentation](https://wiki.mythiccraft.io/mythicmobs/Skills/Targeters?version_id=db66c312a642f87c8b03d36dac658be237715ea3). The overall targeter catalog remains incomplete. |
| Generic targeter skip/limit ordering | implemented-runtime | `skipTargetsUpToIndex`/`stuti` is applied after sorting and before `limit`; negative, malformed, and out-of-range values fail closed. Generic sorting supports none, random, nearest/farthest, health, threat, oldest, and newest with unknown modes and unavailable threat data rejected. API tests and strict Paper fixtures `srv-paper-targeter-skip-index-strict-20261102-r3` and `srv-paper-targeter-sorting-strict-20261102-r1` cover skip/limit semantics, health/age order, and invalid controls; the targeter sorting report has 272 probes, 260 passed, 12 expected negative controls, zero incomplete |
| Audience actions | mapped-unverified | message/full title with subtitle and timing/actionbar/particle delivery plus dual-client Paper evidence for radius, permission, explicit faction include/exclude, and combined `radius{r=...;permission=...;faction=...}` filters; `!permission`/`-faction` exclusion syntax now has parser/unit coverage, while broader Paper/client combinations remain unverified |
| Mechanics catalog | mapped-unverified | common actions including caster `remove`, material/slot/amount `equip`/`set-item`/`set-offhand`/`set-armor`, player `clear-inventory`/`set-material-cooldown`/`set-game-mode`, `jump`/`force-pull`, main/off-hand `swing`, entity state `set-ai`/`set-gravity`/`set-silent`/`set-collidable`/`set-sneaking`/`set-gliding`/`set-swimming`, player state `set-sprinting`/`set-flying`/`set-walk-speed`, no-damage-tick controls, caster `set-fire`/`clear-fire`, caster/target orientation `set-rotation`/`look-at` (`setrotation`/`lookat` importer aliases), execution-time numeric placeholders in damage/heal/health/movement mechanics, caster `pull`/`leap`/`set-tick-speed` and caster movement `dash`/`blink`, multi-target `damage`/`damage-percent`/`message`/`explode`, player-target `give-item`/`take-item` (`giveitem`/`consume` importer aliases), target/caster `set-block`/`break-block` (`setblock`/`breakblock` importer aliases), caster-location `drop-item`, potion duration/amplifier/flags, caster-target `mount`, caster `dismount`, caster/location `teleport` aliases, event-aware `cancel-event` for damage/interact triggers, colored visible/hidden `set-name`, and the plugin custom item resolver are registered and importer-tested; strict fixture `srv-paper-runtime-alias-20261031-r2` retains the production `set-target`/`clear-target`, oxygen, consume-slot, faction, level, weather, time, lightning, score, and targeter evidence; Mythic entity `disguise`/`removedisguise` are explicitly preserved as unsupported because model appearance is not equivalent; broader mechanic/event parity remains unverified |
| Conditions catalog | mapped-unverified | high-frequency subset now includes world/biome/time/weather, including parameterless `morning`/`noon`/`midnight`/`clear`/`sun`/`rain`/`storm`/`thunder`/`thunderstorm` importer aliases bound to registered runtime conditions, variable, target, permission, entity type, direct entity category aliases (`isLiving`/`isPlayer`/`isMob`/`isMonster`/`isAnimal`/`isVillager`/`isTameable`/`isRaider`), undead/creature and age aliases (`isUndead`/`isCreature`/`isBaby`→`is-baby`, `isAdult`→`is-adult`; Mythic `isChild`/`child` map to the caster-to-target `is-child` parent relation, with strict importer-to-SkillService Paper evidence in `srv-paper-is-child-import-strict-20261105-r1`), tame/shear/saddle/powered/collidable/offhand state aliases (`isTamed`/`isSheared`/`isSaddled`/`isPowered`/`isCollidable`/`hasOffhand`), caster/target lifecycle state aliases (`has-ai`/`has-gravity`/`is-silent`/`is-dead`/`is-alive`/`target-is-alive`/`is-valid`), caster environmental aliases (`on-ground`, `in-water`, `in-lava`, `in-air`, `is-blocked`, `stand-on`, `daylight`, `facing-target`, `light-level-below/above`), exact farmland hydration aliases (`moist`/`ismoist` and `moisture-level`/`moistureness`/`moistness` with `l` ranges), size aliases (`mob-size`/`mobsize`/`size`) for Slime/MagmaCube/Phantom, exact block counts (`block-type-in-radius`) with material/radius/amount ranges, exact `BiomeType` lists and the 18 documented `exact=false` categories (including alternatives; unknown categories fail migration rather than becoming false no-op conditions; strict Paper fixture `srv-paper-biome-type-category-strict-20261101-r1` verifies positive/negative category matching and exact mode), target-prefixed aliases and importer mappings (`target-is-undead`, `target-is-creature`, `target-is-baby`, `target-is-adult`, `target-is-tamed`, `target-is-sheared`, `target-is-saddled`, `target-is-powered`, `target-is-collidable`, `target-has-offhand`, `target-has-ai`, `target-has-gravity`, `target-is-silent`, `target-is-valid`, `target-is-sneaking`, `target-is-sprinting`, `target-is-flying`, `target-is-blocking`, `target-is-gliding`, `target-is-swimming`, `target-is-frozen`, `target-is-leashed`, `target-has-owner`, `target-owner-is-player`, `target-owner-is-online`, `target-on-fire`, `target-in-air`, `target-in-water`, `target-in-lava`), plus legacy project alias `targetIsChild`→`target-is-baby` (no official Mythic `targetIsChild` meaning verified); target categories (`mob`/`monster`/`animal`/`villager`/`tameable`/`raider`), stance, radius, line-of-sight, fire, game-mode, `food-level`, `has-item`, `air-level`, `damage-cause`, `damage-amount`, potion, invulnerability, `player-kills`, common no-separator aliases, keyed caster/target health and distance comparisons, target raw-health/percent comparators, player state aliases (`is-flying`, `is-sneaking`, `is-gliding`), and nested `all`/`any`/`not`; variable checks include typed string/list equality and contains, numeric List index `gte/lte/in-range`, Map value numeric `gte/lte/in-range` and negative range checks, positive/negative Map key and value checks, scope isolation for caster/target/world/global, and unset fail-closed coverage; time supports named windows, wrapping ranges and comparators, and weather accepts thunder aliases; fixtures `srv-paper-integration-condition-states-20261016` and `srv-paper-integration-entity-states-20261016` verify real online-player and real entity state conditions, including tame/sheared/saddle/powered, with cleanup; full Mythic placeholders, comparator edge cases, and the complete catalog remain unverified |
| Trigger catalog | mapped-unverified | `on-damage` and `on-attacked` dispatch from entity damage events, with a separate environmental path; strict Paper fixture `srv-paper-environmental-natural-strict-20261102-r1` proves autonomous Lava contact produces `DamageCause.LAVA` and executes the managed mob's configured `on-damage` action after 16 ticks; `on-interact` listens to the Bukkit entity-interaction parent event so ordinary and `AtEntity` clicks share one path; killer-side `on-kill` and `on-kill-player` resolve managed mob killers from death attribution and unwrap projectile shooters; strict Paper fixture `srv-paper-spawning-distance-client-walk-strict-20261102-r3` passes `killTrigger` through a real mob-damage/death chain and `playerKillTrigger` through a real online-player death event with controlled `lastDamageCause` attribution, including positive/negative `PlayerKills` ranges; this does not prove naturally delivered PvP damage attribution. Mythic `onConsume`/`onEat` aliases normalize to `on-consume` and dispatch from Bukkit `PlayerItemConsumeEvent`; `on-teleport` has real Paper evidence in `srv-paper-integration-teleport-trigger`; bound-player block break/place and `on-item-pickup` have real Mineflayer evidence in `srv-paper-integration-item-pickup`; `on-vehicle-enter`/`on-vehicle-exit`/`on-vehicle-destroy` and `dismount` have real Paper Boat evidence in `srv-paper-integration-vehicle-fix-20261015`; `cancel-event` followed by nested `is-cancelled` has real Paper evidence in `srv-paper-integration-cancelled-20261016-r5`; `on-potion-effect-end` has a registered listener and mapping tests, with managed-entity and real-player natural expiry verified on strict Paper 1.21.1-133 fixture `srv-paper-potion-expiry-strict-20261101-r1`; exploratory r33 verifies mechanic-driven removal dispatch and exploratory r136/r137 provide additional real-client evidence; clean delayed native-target fixture `srv-paper-integration-target-reason-clean-20261016` proves `target-events=1`, `target-find-misses=0`, `target-reason=CLOSEST_PLAYER`, `target-cancelled=false`, and delayed `entity-target-trigger=true`; real PvP attribution and several other natural-event paths remain unverified |

Event routing update: when multiple managed instances share one Bukkit entity,
single-trigger events now prefer the binding whose profile declares that exact
trigger instead of relying on instance insertion order. The Paper block-event
self-test exercises this path with immediate synthetic `BlockBreakEvent` and
`BlockPlaceEvent` dispatch; r138/r140 record `blockEvents=passed`.

Trigger wiring audit update: `TriggerDispatchAuditTest` now classifies every
`wired=true` trigger and requires a source contract for direct dispatch,
derived sampling, condition sampling, potion-event mapping, or the signal bus.
This prevents a newly marked trigger from appearing supported without an event,
bus subscription, or sampler route.

Current strict condition evidence supersedes the historical exploratory
`stateBlocking=false` observations above: fixture
`srv-paper-integration-exp-20261031-r27` records `stateBlocking=passed` from a
real client-driven Shield-use window, and `lookingAtCondition=passed` after a
deterministic positive/negative ray control.

Exploratory Paper 1.21.11 r142 records the real tempt selector and delayed
native target route: `tempt=passed`, `entityTargetBukkit=passed`, and
`entityTargetDelayed=passed`, with a Survival player holding WHEAT and Paper
`target-reason=TEMPT`.

Strict Paper 1.21.1-133 fixture `srv-paper-potion-expiry-strict-20261101-r1`
records managed and real-player natural potion expiry as passed. This closes
the version-specific expiry evidence gap in the Trigger catalog; autonomous
lava-tick timing and other uncovered natural-event paths remain open.

## 2. Mob Options

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Universal options | mapped-unverified | `SpawnOptions` and entity application cover many fields; reason/cause options accept YAML lists and Mythic-delimited scalars; strict fixtures r36/r37/r41 verify vanilla and killer-owned drop prevention, including custom victim loot suppression; r42/r43 verify Silverfish infection and Snow Golem snow guards; r44 verifies family-aware `PreventConversion`; r47 verifies Enderman `HeldBlock`; `PreventSunburn` verifies sunlight-only cancellation; event probes cover leash/rename/target/projectile/explosion/drowning/fall/fire/pickup/item-use/teleport/mount/transformation policies; r38 verifies pickup precedence, random-equipment clearing, explicit-equipment retention, and `NoDamageTicks`. Strict Paper fixture `srv-paper-imported-mob-defaults-strict-20261106-r4` closes the importer/config/runtime path for built-in and global `PreventLeashing` defaults, single-Mob override, `PreventItemPickup`, `NoDamageTicks`, and built-in/global/explicit `PreventTransformation` outcomes. Other options, entity-family defaults, and broader natural event ordering remain unverified |
| `Options.ReinforcementsChance` | implemented-runtime | Valid finite values from `0` through `1` map only for Bukkit Zombie-family entity types to Paper's `ZOMBIE_SPAWN_REINFORCEMENTS` attribute; other entity types and invalid values are retained under `mob.unsupported.Options.ReinforcementsChance` with migration warnings. Importer and runtime tests cover Zombie/Husk, non-Zombie, malformed/range values, boundaries, non-finite values, and runtime rejection. Strict Paper 1.21.1-133 fixture `srv-paper-reinforcements-chance-strict-20261107-r1` reads back `0.38` from a real Zombie attribute and verifies zero/one mapping plus non-Zombie, out-of-range, and runtime rejection controls. Vanilla reinforcement event frequency distribution and other Mob Options remain outside this probe |
| `Options.SpawningDistance` | implemented-runtime | Importer maps the Mythic option to `mob.spawning-distance`; `configuredDespawnDistance` consumes it into `SpawnOptions.despawnDistance` when explicit `mob.despawn-distance` is absent, with a 32-block fallback. Importer and precedence tests cover legacy and explicit values. Strict Paper fixtures `srv-paper-spawning-distance-runtime-20261101-r4`/`r5` verify production config loading and nearby retention. The current full strict release fixture `srv-paper-mythic-spawner-group-size-strict-20261106-r5` also records a real client's 14.36-block movement and 15.00-block player/entity distance; the managed instance and Bukkit entity are removed 2 ticks after the first outside-radius sample, within the 10-tick AI decision period. The focused earlier fixture `srv-paper-spawning-timing-20261105-r3` remains historical evidence. Remaining boundary: other entity-family defaults and interactions are part of the unfinished universal Mob Options catalog. |
| Interactable | implemented-runtime | Mythic `Options.Interactable` maps to `mob.interactable` and defaults to `false` on imported configs; importer tests cover explicit and default values. Managed entity right-clicks are cancelled when false and allowed when true; `PreventItemUse` remains independent. Strict Paper 1.21.1-133 fixture `srv-paper-interactable-strict-20261107-r2` verifies both states from real client packets and Paper event observations (`interactions=2`). Earlier fixture `srv-paper-integration-armor-stand-20261031` verifies Armor Stand manipulation cancellation/control through Paper events; real-client Armor Stand manipulation and broader option semantics remain open. |
| Age, Baby, Adult, AgeLock | implemented-runtime | Exact tick `Age` and `AgeLock` map for Bukkit `Animals`; `Adult`/`Baby` map for every `Ageable`, using Zombie/Piglin-specific baby setters. For ageable non-animal types (including Zombie/Husk), exact `Age` and `AgeLock` are preserved under `entity.unsupported.Options.*` with migration warnings; non-Ageable inputs are preserved the same way. Importer/runtime tests cover animals, Husk, and Allay. Strict Paper 1.21.1-133 fixture `srv-paper-age-options-strict-20261105-r3` records `age-options result=true age=-24000 baby=true adult=true age-lock=true zombie-baby=true` on real Sheep and Zombie entities. Other Mob Options remain incomplete |
| Sheep options | implemented-runtime | Mythic `Options.SheepColor` and `Options.Sheared` map through the importer to production entity options. Strict Paper fixture `srv-paper-sheep-options-strict-20261107-r1` imports red/sheared and white/unshorn control mobs, spawns both through the production mob path, and reads back both configured states in the 384-probe release catalog. Other Sheep option defaults and unrelated Mob Options remain open |
| Horse color, style, domestication, and jump strength | implemented-runtime | Mythic Horse `Color`, `Style`, `Domestication`, `MaxDomestication`, and `JumpStrength` import and apply through production mob spawning. Strict Paper 1.21.1-133 fixture `srv-paper-horse-options-strict-20261107-r1` reads back GRAY/WHITE_DOTS, 5/20, and 0.8 against BLACK/BLACK_DOTS, 1/12, and 0.4 controls in the complete 385-probe release catalog. Broader Horse defaults and unrelated Mob Options remain open |
| Angry, Tamed, Raider flags | mapped-unverified | Wolf/PigZombie/Raider/Tameable setters and tests; strict Paper 1.21.1-133 fixture `srv-paper-integration-patrol-spawn-20261031` applies and reads back real Wolf `Tamed`/`Angry` and Pillager `PatrolLeader`/`PatrolSpawnPoint`, while `CanJoinRaid` is verified as a successful production setter call because Paper 1.21.1 exposes no getter |
| Size, Slime split | mapped-unverified | Fixed Mythic `Options.Size` and inclusive positive integer ranges migrate to `entity.size` or `entity.size-min`/`size-max`; Paper API confirms `MagmaCube extends Slime`, so the Slime setter covers both families. Strict Paper 1.21.1-133 fixture `srv-paper-entity-size-range-strict-20261107-r1` imports `2to5` configs and samples real Slime, MagmaCube, and Phantom entities 64 times each, with every observation in range and both endpoints reached. `PreventSlimeSplit` maps to the production `SlimeSplitEvent` cancellation path, with strict `mobOptionsSlimeSplit` verifying protected cancellation and control allowance; natural split-size distribution remains unverified |
| Creeper options | implemented-runtime | Importer tests cover `ExplosionRadius`, `FuseTicks`, `SuperCharged`, and `PreventSuicide`. Strict Paper fixture `srv-paper-creeper-options-strict-20261107-r1` imports all four options, spawns protected/control Creepers through production mob configuration, reads back radius 5, fuse 60, and powered state, and confirms only the protected Creeper's explosion event is cancelled. The separate natural-fuse Paper probe also passes. Vanilla explosion damage/radius effects and broader option ordering remain unverified |
| Bee nectar/stung options | implemented-runtime | Mythic `Options.HasNectar` and `Options.HasStung` map to `entity.has-nectar`/`entity.has-stung` and are applied on the production Bee spawn path. Strict Paper 1.21.1-133 fixture `srv-paper-bee-options-strict-20261107-r1` verifies imported `true/false` state against the inverse control on real Bees in the complete 386-probe release catalog. This verifies these two imported fields, not all Bee defaults or option-order interactions |
| Bee stinger-loss prevention | mapped-unverified | `Options.PreventStingerLoss` maps to `mob.prevent-stinger-loss`, and the runtime has natural Bee attack evidence for the protected/control event path. Mythic-imported Paper evidence for this option is still needed before its row can be marked implemented |
| Zombie/Skeleton conversion and jockey mounts | mapped-unverified | Mythic `PreventConversion` now maps by entity family: Zombie-family entities map DROWNED/CURED/INFECTION, Skeleton maps FROZEN, and unsupported families remain visible for manual handling; unit/importer tests validate enum reasons and unsupported preservation; strict r44 verifies a Skeleton FROZEN event is cancelled while CURED remains allowed, alongside protected/control Zombie conversion; natural AI jockey selection remains unverified |
| Remaining entity-specific options | mapped-unverified | Enderman `HeldBlock` migrates to `entity.held-block` and is applied as Bukkit BlockData; strict r47 confirms carried block reads back as OBSIDIAN. Cat `Type`, `Sitting`, and `CollarColor` import through the production mob spawn path; strict Paper 1.21.1-133 fixture `srv-paper-cat-options-strict-20261107-r3` reads back `SIAMESE / sitting=true / BLUE` against a `TABBY / sitting=false / RED` control in the complete 384-probe catalog. Horse, ArmorStand, Wolf/Tameable, Raider, Snow Golem and FallingBlock paths exist; Sheep `SheepColor`/`Sheared` are covered by the dedicated implemented row above. Official Armor Stand aliases `HasArms`, `HasBasePlate`, `Marker`, `Small`, `CanMove`, and `CanTick` now map to production setters, while `ItemBody`, `ItemFeet`, `ItemHand`, `ItemOffhand`, `ItemHead`, and `ItemLegs` resolve through the existing ItemStack/custom-item path; official `Saddled` maps to Paper `Steerable`/`AbstractHorse` saddle APIs and strict fixture `srv-paper-integration-saddled-20261031-r2` reads back a real Horse saddle; official `HorseArmor` maps to `entity.horse-armor` and uses `ArmoredHorseInventory#setArmor` with the same ItemStack/custom-item resolver, while strict fixture `srv-paper-integration-horse-armor-20261031` reads back `DIAMOND_HORSE_ARMOR`; official `CarryingChest` maps to `entity.carrying-chest` and applies through Paper `Horse`/`ChestedHorse#setCarryingChest`, while strict fixture `srv-paper-integration-horse-chest-20261031-r2` reads back `chest=true` from a real Donkey; strict fixture `srv-paper-integration-armor-options-20261031-r3` reads back `can-move=false can-tick=false items=true`; strict spawn-option evidence proves pickup precedence, random gear clearing while retaining explicit gear, and configured `NoDamageTicks`; r42/r43 prove Silverfish/Snow-Golem guards; full mob-config spawn verification remains incomplete |

Mythic `Options.Size` migration now accepts a positive integer range such as
`2-5` or `2to5`, emitting `entity.size-min`/`entity.size-max`. The spawn-time
adapter samples one inclusive value and applies it to Slime/MagmaCube or
Phantom. Invalid, reversed, or non-positive ranges remain preserved with a
warning instead of silently becoming size 1.

## 3. Items, Equipment, Attributes

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Equipment slots, amount, chance | implemented-runtime | importer fixtures, `EntityEquipment` adapter tests, and SkillExtras `equip/set-item` actions |
| Inline names, lore, enchants, potions, skulls | mapped-unverified | importer, namespaced vanilla material and multi-enchantment `material:amount:enchantment:level` parsing, ItemMeta paths, internal `items.yml` configured-item resolver, and isolated Paper `custom-item=true` evidence for a configured GOLD_INGOT ItemStack; this is not external ItemAdder/CraftEngine API evidence, and client/server rendering plus full mechanic dispatch remain unverified |
| Legacy numeric IDs and data | mapped-unverified | common tools, armor, stable item IDs through 452, `minecraft:` namespaced vanilla material normalization, music discs `2256–2267`, numeric `ID:DATA` shorthand, common legacy spawn eggs (`383:DATA`), known potion/tipped/splash/lingering encodings with ordinary/long/strong `PotionType` output, classic water/lava and piston IDs, base blocks, colored Shulker/Glazed Terracotta blocks, colors, flowers, leaves (including decay bits), fish, banners, infested blocks, quartz, prismarine, logs, stairs, slabs, double plants and durability-bearing fishing rods/shears/shields/elytra are covered; unknown spawn-egg ids and unknown/invalid potion data remain explicit under `unsupported.legacy-id/data`, and the legacy table is still incomplete |
| Item Options and PDC identity | implemented-runtime | ItemMeta/PDC proxy tests; anvil and inventory behavior unverified. MythicMobs `ItemModel` and `DamageType` remain under `unsupported.options`: Paper 1.21.1 `ItemMeta` exposes no public component API for either value, so conversion requires a future version-gated adapter |
| Entity and item attributes | mapped-unverified | alias/slot/operation normalization and stable UUID deduplication exist; fixture `srv-paper-integration-item-attributes-50ba050d42e7442db9a58584ae06270e` proves Paper entity base 3.0 -> value 3.3 with one `ADD_SCALAR` modifier and one ItemMeta modifier, while full attribute catalog and client coverage remain unverified |
| Drops and DropTables | mapped-unverified | Amount ranges, weighted tiers, luck scaling, generated-line `TotalItems`, two-sided bounds, bonus-weighted single-sided selection, nested expansion with cycle protection and parent line caps, table-level killer `TriggerConditions`, dropper `Conditions`, entry conditions, table/mob experience, root `Exp`, and per-entry experience drops have importer/runtime and fail-closed tests. Strict fixture `srv-paper-drops-bonus-semantics-strict-20261102-r1` verifies natural-death level-only (4), Luck-only (3), combined (5), and zero-bonus (2) cases plus single-bound weighted selection in 273 probes (262 passed, 11 expected negative controls). Fixture `srv-paper-drops-imported-range-strict-20261102-r5` verifies standalone `DropTables/` import through runtime loading and real natural death: one Diamond, two Gold Ingots from imported exclusive `1-3`, and 5 XP; all 273 probes complete. Earlier strict fixture `srv-paper-imported-drop-contract-strict-20261102-r1` additionally verifies condition aliases, ambiguous source preservation, and imported natural-death output in 284 probes (267 passed, 17 expected rejected, zero incomplete). Separate strict fixtures cover natural `TotalItems` output, exclusive inline ranges, Bukkit Luck scaling, dropper conditions, and XP entries. Strict fixture `srv-paper-fractional-bonus-strict-20261102-r4` observes 63 extra lines across 128 natural deaths at level 1 with a 0.5 bonus; its report omits the unrequested raw Potion workflow; `BiasStat`, custom Mythic Luck enchantment/curses, broader placeholders, and the full official catalog remain incomplete. The official DropTables catalog does not define a separate `Looting` table option |
| External drop and economy types | blocked-by-external-plugin | Vault `HasCurrency` has a reflective Economy service adapter and fail-closed runtime condition; contract tests now exercise both `getBalance(OfflinePlayer)` and legacy `getBalance(String)` provider signatures, but no installed Vault/provider fixture verifies service discovery or a provider version range. MythicCrucible mechanics/items and MMOItems drops remain preserved and are not materialized as vanilla placeholders |
| External item and model adapters | blocked-by-external-plugin | ItemAdder and CraftEngine are detected as installed but no supported API bridge is implemented; adapters declare no capabilities and resolve no placeholder item. MMOItems, MythicCrucible, and ModelEngine now have explicit runtime `BlockedExternalAdapter` entries: diagnostics report whether each plugin is absent or detected, but capabilities remain empty and no item/model result is materialized. Vault exposes `ECONOMY_BALANCE` only after reflective service registration succeeds; installed-provider integration evidence is still required. ModelEngine runtime rendering and external model state, plus MMOItems/MythicCrucible runtime behavior, remain blocked pending API/version contracts and installed-plugin fixtures |
| MythicMobs model bridge | mapped-unverified | Reflection adapter binds configured MythicMobSpawnEvent entities and registers model mechanics/conditions only when their external registrars accept them; declared `5.3.x-5.7.x` range is not installed-plugin CI-verified, and no Paper fixture runs with MythicMobs present |
| WorldGuard region provider | mapped-unverified | Reflection adapter probes the WorldGuard 7.x region API at startup and queries named/union/intersection regions; its `REGION_QUERY` capability is exposed only while connected, but no installed-WorldGuard runtime fixture verifies version behavior |

Drop import follow-up: entry conditions containing only delimiters, or a delimiter-separated
empty `?` condition, are rejected and preserved in migration diagnostics instead of
becoming unconditional drops. Unit and importer tests cover this behavior; the change
has not yet been verified in a new Paper fixture.
Runtime loot materialization now rejects unresolved non-`minecraft` namespaces instead of
turning their suffix into a vanilla item. Unit tests cover missing and throwing resolvers,
an accepted custom resolver, and vanilla fallback; no new Paper fixture covers this change.
Structured nested table references now use the same ID normalization as imported table
definitions. A migration-apply-to-runtime test verifies `Boss-A` resolves to `boss_a`
and produces the child table's item; this normalization change has no new Paper evidence.
Scalar mob `DropTable-1` references use the same normalization and have a migration-to-roll
contract test. Bare drop-line names (for example `- rewards`) are resolved against source
DropTables across files for mob and table drops. Duplicate names and collisions with source
items or vanilla materials are preserved as unsupported rather than guessed. Importer and
migration-to-roll tests cover these paths. Strict fixture
`srv-paper-nested-import-strict-20261102-r1` verifies a bare reference across files,
including a parent in a subdirectory, through natural Zombie death.
If a source Items or DropTables catalog cannot be read or parsed, the migration report
identifies the source and preserves bare drop lines; explicit structured entries remain
eligible for projection. This failure path has importer coverage only.
Known source custom-item references in bare and structured drops use the same normalized
ID as imported `items.yml`; collisions after normalization are preserved as unsupported.
Importer tests and a runtime resolver contract test cover this change. The resolver in
the contract test is controlled; no new Paper fixture verifies custom-item drops.
Item `Template`/`Parent` references are normalized to the imported item ID, including
across source files. A migration-apply test verifies the resulting reference and target;
invalid non-string or blank template values are preserved with a warning. Runtime
template inheritance rejects missing, blank, non-string, cyclic, and preserved
unsupported templates with a warning instead of constructing the child material;
unit tests cover valid inheritance and importer-to-runtime rejection. Declared
invalid item IDs block external and vanilla fallback in equipment, loot, and
skills, including collisions with vanilla material names. Custom-menu buttons
and pagination also prefer custom resolution, reject declared invalid items at
load time, and leave unresolved items empty at render time; empty slots cannot
execute actions. Unit tests cover item resolution and load rejection; these
include a simulated inventory click proving an empty slot cannot execute a
button command while a populated slot can, and a pagination click proving an
empty icon does not navigate while a populated next icon opens the next page.
Strict fixture `srv-paper-menu-collision-strict-20261102-r1` verifies real-client
permission, pagination, and button workflows. Its Paper render log also shows a
custom `SPONGE` item rendered as `GOLD_INGOT` in a menu button. Invalid-item
collision paths and external-plugin integration have no Paper evidence.
Files in scanned content subdirectories are now classified by their containing content
directory, and bare-table lookup uses that directory's MythicMobs root. Importer tests
cover nested Items, Skills, and Spawners; a migration-to-roll test covers nested Mobs
and DropTables across directories. The strict fixture above verifies the DropTables
subdirectory case; the other content types have no separate Paper fixture.

The earlier strict fixture `srv-paper-integration-exp-20261031-r14` recorded
the root `Exp` event probe in a complete 239-probe report. It is superseded by
the current 240-probe fixture `srv-paper-integration-exp-20261031-r16`, which
also proves the independent managed vehicle lifecycle.

## 4. Custom AI

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Factions and threat targeting | mapped-unverified | `FactionService`, threat table and selector tests; fixture `srv-paper-integration-threat-faction-20261016` dispatches real `EntityDamageByEntityEvent` instances with 5 vs 20 damage, verifies `@threat` ordering, same-faction selection, and hostile-faction exclusion; native faction/threat composition beyond these filters remains open |
| Basic goals and target selectors | mapped-unverified | `AiProfile` supports a subset including `panic`, `follow_owner`, and `tempt <material>`; strict Paper 1.21.1-133 now proves the registered player/monster/clear/owner/faction/conditional/native target paths and a live WHEAT tempt target; full Pathfinder equivalence remains unverified |
| Ranged/leap/owner/faction selectors | mapped-unverified | implemented subset; numeric priority sorting, ordered `clear`, read-only ordered selector views, and category priority lookup have unit coverage; isolated Paper fixture `srv-paper-integration-ai-priority` verifies opposite configured priorities select `ATTACK` versus `FLEE` at 50% health, while native Pathfinder goal equivalence remains unverified |
| Full AIGoalSelectors/AITargetSelectors | mapped-unverified | importer now accepts official `AITargetSelectors` alongside legacy `AIGoalTargets`; typed runtime plans now cover the core melee/ranged/leap/chase/patrol/look/door/swim/sun/flee goals, conditional target selectors backed by the shared SkillService condition catalog, and player/monster/villager/iron-golem/attacker/owner/parent/faction targets with deterministic priority/clear composition; strict Paper 1.21.1-133 completes the registered selector probe and `srv-paper-integration-route-20261030` proves the route goal path, while premium selectors remain open |
| `followroute` / `followpath` goals | mapped-unverified | valid integer waypoint routes are now active `SelectorPlan` goals instead of false unsupported diagnostics; malformed routes fail closed with actionable diagnostics, and `AiController` advances ordered waypoints while producing velocity. Strict Paper 1.21.1-133 fixture `srv-paper-integration-route-20261030` records `aiRoute=passed` from a real managed entity (`active-before=true moving=true advanced=true`); route looping, dynamic route mutation, and native Pathfinder goal equivalence remain open |
| Pathfinding malus and native goal mutation | mapped-unverified | custom selector results synchronize to Paper `Mob#setTarget`; `AIPathfindingMalus` is now preserved with an explicit migration warning because Paper's public API does not expose native PathType malus mutation; fixture `srv-paper-integration-path-stop-20261016` still records real Paper `stop-calls=1`, while full native Goal composition and malus evidence remain open |

Target state condition update: Mythic `targetIsGlowing` now maps to the Bukkit
target `Entity#isGlowing` evaluator through `target-is-glowing`, with migration,
canonical binding, and unit coverage. The online-player `target-state-conditions`
Paper self-test now also sets and restores a real glowing state. Exploratory
fixture `srv-paper-integration-exploratory-20261020-target-glowing-client`
records `target-state-conditions=true` and `targetStateConditions=passed`; strict
Paper 1.21.1-133 fixture `srv-paper-integration-remove-held-strict` also records
`targetStateConditions=passed`. Its online-player self-test sets glowing true,
checks `target-is-glowing`, verifies the missing-target path fails closed, and
restores the player's prior glowing state.

## 5. Skill Trees, Conditions, Audience

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Shared skill-tree variables and locations | implemented-runtime | `SkillTreeContext`, nested skill tests, immutable snapshot/restore, and YAML snapshot-store round-trip coverage for primitive variables and locations |
| Audience parsing and delivery | mapped-unverified | isolated dual-client Paper workflow proves message/title/actionbar/particle delivery, radius include/exclude, permission include/exclude, explicit faction include/exclude, and combined filter parsing; `!permission`/`-faction` exclusion syntax now has parser/unit coverage, while broader combinations remain unverified |
| `DamageTag` / `DamageHasTag` and scoped damage tags | implemented-runtime | Mythic aliases map to fail-closed `damage-tag` (`value=true` by default); `damage-target` scopes normalized `tags`, case-preserving `rawtags`, and normalized `damagetype` to the synchronous victim damage event, and SkillTree snapshots preserve the tag set. `MmConditionsTest`, `SkillConditionsTest`, `SkillCatalogDamageTagsTest`, and `SkillTreeContextTest` cover mapping, malformed values, event scope, and snapshot restore; strict Paper 1.21.1-133 fixture `srv-paper-damage-tag-strict-20261102-r3` verifies untagged rejection, tagged action, cause/final-damage conditions, event cancellation, and unchanged victim health. Raw tags remain case-sensitive, and asynchronous damage or non-Bukkit damage pipelines are outside this implementation |
| Named Mythic Aura state, removal, and conditions | implemented-runtime | `Aura`/`Buff`/`Debuff`, `AuraRemove`/`RemoveAura`/`RemoveBuff`/`RemoveDebuff`, and `HasAura`/stack/type aliases use a tick-expiring per-entity registry; `targetHasAuras{aura=...}` reads named Aura state from the explicit target and fails closed without one. Removal supports the default name `default`, positive partial stack counts across casters, full removal when `stacks` is omitted, and `ANY` removal. Unit/importer-to-runtime tests cover target-only semantics, duration, stack caps, merge/overwrite modes, removal, type checks, invalid-argument preservation, and warnings. Strict Paper 1.21.1-133 fixture `srv-paper-aura-target-strict-20261106-r1` passes `mythicAuraRuntime` in the complete 366-probe catalog; archived report and server/client logs are in `tools/paper-integration/reports/`. Aura visual components, lifecycle skills, attachments, charges, and event cancellation remain unsupported and preserved with warnings |
| Mythic condition-list suffix actions and `Burning` | implemented-runtime | `true` keeps the condition, `false` negates it, and suffix negation composes with `!`; unknown actions remain preserved with a warning. The `on-fire` evaluator prioritizes the active target and falls back to the caster when no target exists. Unit and importer-to-runtime contract tests cover parsing and both states; strict Paper 1.21.1-133 fixture `srv-paper-condition-action-suffix-strict-20261106-r1` verifies target priority, positive/negative states, composed negation, caster fallback, and invalid-suffix preservation. Other Conditions and their full grammar remain open |
| Full Mythic condition/filter grammar | mapped-unverified | nested `all`/`any`/`not`, unary `!`, grouped parentheses, and top-level `&&`/`||` composition with `||` precedence now have runtime tests; caster/target permission conditions and entity-state placeholders now cover common Bukkit flags including age, AI, gravity, silent, validity, powered, movement and player state, with Paper `caster-permission=true` evidence from an online trigger context; strict Paper fixture `srv-paper-chance-condition-strict-20261101-r1` verifies `Chance` at 0% and 100% and rejects 101%; unsupported condition names, remaining placeholders, and operators remain |

Health migration correction: official [`health`](https://wiki.mythiccraft.io/mythicmobs/Skills/Conditions/health) compares absolute health, while [`healthpercent`](https://wiki.mythiccraft.io/mythicmobs/Skills/Conditions/healthpercent) accepts percentages and multipliers. Scalar comparisons map to `health` and `health-percent`, preserving strict operators and absorption; `MmConditionsTest` and `MigrationRuntimeConditionContractTest` cover migration and runtime boundaries. Paper fixture `srv-paper-health-import-strict-20261104-r1` verifies translated comparisons and invalid-input preservation against a real Zombie; `healthImportedCondition` is required by the strict catalog. Percent syntax on `health` remains unmapped.

Paper fixture `srv-paper-health-range-strict-20261104-r1` additionally verifies translated absolute and percentage ranges, inclusive endpoints, absorption and invalid range preservation on a real Zombie. The strict catalog requires `healthRangeImportedCondition`.

After that fixture, `targetDistance` and `lightLevel` scalar comparisons were corrected to preserve strict and inclusive operators through the registered `distance` and `light-level` runtime consumers. `MmConditionsTest` and `MigrationRuntimeConditionContractTest` verify boundary behavior and preservation of invalid input; strict fixture `srv-paper-comparison-boundary-strict-20261104-r1` additionally verifies these boundaries on real entities and block light levels through required `comparisonBoundaryImportedCondition` evidence.

Subsequently, target health percent migration was aligned with the percentage runtime consumer: bare multipliers are multiplied by 100 and explicit percentages remain unchanged; percentages on absolute target health and overflowing values remain unmapped. Mapping and cross-module tests cover boundary comparisons and missing targets. Paper fixture `srv-paper-target-health-strict-20261104-r1` verifies these behaviors on a real target, and the strict catalog requires `targetHealthImportedCondition`.

After that fixture, migration rejects multiple outer parameter blocks and rejects duplicate or unknown arguments in `world`, `weather` and `time` conditions, preserving source text for the migration warning. Runtime binding also rejects invalid arguments so unprefixed or negated preserved conditions cannot silently run. `MmConditionsTest` and `MigrationRuntimeConditionContractTest` cover source import, warnings and skill execution. Strict fixture `srv-paper-environment-validation-strict-20261104-r1` verifies mapped conditions against real world state and rejects invalid bindings; `environmentImportedCondition` is required by the release catalog.

Subsequent source changes reject duplicate health/absorption arguments and percentage conversion overflow, and preserve large finite scalar thresholds without integer truncation. `MmConditionsTest` covers these cases; they have not yet been exercised by a new Paper strict fixture.

The subsequent positional health comparison plus `includeabsorption` combination is covered by migration and importer-to-runtime tests. Paper fixture `srv-paper-health-validation-strict-20261104-r1` predates this source change, so a new strict run is required for the current build.

Health and health-percent ranges using `to` or `-` now translate into inclusive `all{}` comparisons, including absorption and percentage multiplier conversion. Negative bounds require `to`; reversed, mixed-unit, non-finite and unsupported percentage health ranges remain unmapped. Migration and runtime contract tests cover bounds and rejection; no Paper range probe has been run on this build.

Incremental Conditions closure: Mythic `Drowning`/`IsDrowning` maps its
optional air comparator to the canonical `drowning` condition, whose runtime
consumer reads Bukkit `LivingEntity#getRemainingAir()`. Importer coverage accepts
finite numeric comparators and rejects malformed/non-finite values; runtime
binding rejects invalid numeric comparators rather than creating an always-false
predicate. `MmConditionsTest`, `EntityStateConditionTest`, and
`MigrationRuntimeConditionContractTest` cover translation and runtime binding.
Strict Paper 1.21.1-133 fixture `srv-paper-drowning-strict-20261102-r2` verifies
both sides of the caster remaining-air threshold through the production
condition service; its report completes 262 probes (250 passed, 12 expected
negative controls, zero incomplete). The condition now has strict runtime
evidence, while the full Conditions catalog remains `mapped-unverified`.
| Full SkillTree semantics | mapped-unverified | Two-boot Paper 1.21.1-133 fixture `srv-paper-skilltree-uuid-restart-20261107-r4` creates two persistent mobs with the same ID, verifies separate UUID snapshot files and independent marker/branch restore, then confirms a real Bukkit death event removes only the victim snapshot and shutdown does not recreate it. `PersistentMobSkillTreeStoreTest` covers same-ID isolation, UUID-over-legacy precedence, legacy fallback, and single-entity deletion. Earlier Paper 1.21.11 exploratory fixture `srv-paper-skilltree-restart-034f7c7c184b48a9b2c57db5453effdb` proves force-loaded chunk loading and stable PDC reattachment. Strict Paper 1.21.1-133 `skillTreeBranches` verifies condition-denied/condition-allowed `branch-unlock` and prerequisite-blocked/unlocked transitions, while `skillTreeCastPersistence` verifies a named skill is gated by prerequisites and executes from a fresh context. Branch metadata is predeclared from loaded skill definitions and condition evaluators are service-local. Complete Mythic SkillTree semantics and broader reload/client parity remain open; see `tools/paper-integration/STARTUP_EVIDENCE.md` |

## 6. Spawner, Random Spawn, Custom Menu

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Spawner parsing, cooldown, max-alive, persistence | mapped-unverified | `SpawnerServiceTest`, state file and diagnostic APIs; Mythic `cooldown` aliases `interval`; scoped world/player quotas persist and reload retains live-instance tracking; strict fixture `srv-paper-runtime-alias-20261031-r2` verifies scheduler timing, failed-spawn cooldown preservation, quotas, max-spawns, and natural recycling. Four independent two-boot Paper 1.21.1-133 fixtures, `srv-paper-spawner-natural-restart-20261106-r1`, `-r2`, `-r4`, and `-r5`, verify natural generation before shutdown, persist and restore 580/600 cooldown ticks, then naturally generate after 579 observed ticks through the production scheduler without a force command; r2/r4/r5 confirm the one-scheduler-period timing bound. Native `Chance: 1` spawns and `Chance: 0` remains at zero through restart; r5 also verifies `Chance: "Infinity"` logs a warning and remains at zero across both boots. Latest full strict fixture `srv-paper-mythic-spawner-world-strict-20261107-r1` verifies imported `GroupSize: 3` capped by `MaxMobs: 2`, valid and invalid imported `Chance`, a weighted pool, variant-specific natural DropTables, and imported world/weather conditions. Unit tests cover malformed, non-finite, and out-of-range Chance values failing closed to zero. Full lifecycle and condition combinations remain open; see the [latest imported Spawner report](tools/paper-integration/reports/paper-1.21.1-mythic-spawner-world-strict-20261107-r1.json) and [latest restart report](tools/paper-integration/reports/paper-1.21.1-spawner-natural-restart-20261106-r5.json) |
| Random weighted mob pools | mapped-unverified | `ImportersTest` and `MigrationRuntimeSpawnerContractTest` verify Mythic `RandomMobs` maps into `random-mobs`, preserves 1:3 weights, and reaches `SpawnerService`. Strict fixture `srv-paper-mythic-random-drops-strict-20261106-r4` samples 100 successful managed-model spawns at exactly 0.25/0.75. Its imported DropTable coupling probe samples 32 variants, verifies per-instance table assignment, then observes natural Bread and Diamond drops from the matching variants only. The complete Mythic pool catalog, broader sample sizes/seeds, and other spawner/loot interactions remain unverified |
| Spawner conditions and regions | mapped-unverified | condition/region hooks and composite `all{}` evaluation exist; scalar/list condition parsing preserves range and coordinate commas; strict fixture `srv-paper-integration-exp-20261031-r27` proves passing/rejected weather, time, biome, composite, world/Y, player/world, and named-region gates with fail-closed results. Latest strict Paper 1.21.1-133 fixture `srv-paper-mythic-spawner-biome-strict-20261107-r2` imports `BiomeType{type=plains;exact=false}`, naturally spawns one managed mob in a matching biome, rejects `desert` with zero spawns, and restores both original biomes. It also verifies imported matching/missing world and rain/thunder conditions through generated YAML and `SpawnerService`. Other imported condition combinations and randomized combinations remain unverified; see [condition import report](tools/paper-integration/reports/paper-1.21.1-mythic-spawner-biome-strict-20261107-r2.json) |
| Heightmap, ground, border and player gates | mapped-unverified | strict Paper 1.21.1-133 fixtures `srv-paper-integration-armor-stand-20261031` and `srv-paper-parity-continuation-strict-20261102-r1` prove natural ground spawn, heightmap surface selection, border clipping/rejection, `player_gate_fail` rejection (`requested=false`, `failure=player gate closed`), player-scoped limit rejection, unloaded-chunk rejection, unreadable-light rejection, and `max-spawns=1`; `player-distance` is accepted as a runtime alias for `players-radius`, while broader randomized terrain combinations remain unverified |
| Custom menu pagination and visibility | mapped-unverified | parser/pagination tests plus an isolated Mineflayer + Paper workflow proving permission denial, page navigation, command dispatch and close behavior; player navigation history now clears on `PlayerQuitEvent`, and button/pagination visibility now supports separated positive clauses plus `!`/`-` exclusions for permission/world/faction/region, while broader Mythic menu conditions and client versions remain unverified |
| Mythic Random Spawns and DropTables | mapped-unverified | Weighted random pools and nested loot expansion exist; strict fixture `srv-paper-mythic-random-drops-strict-20261106-r4` verifies 1:3 Mythic `RandomMobs` weights reach production spawns and that each imported variant retains its distinct `DropTable-1` through natural death (common yields Bread only; rare yields Diamond only). Strict fixture `srv-paper-drops-bonus-semantics-strict-20261102-r1` verifies natural-death bonus counts and weighted selection with a single bound in 273 probes (262 passed, 11 expected negative controls, zero incomplete). `TotalItems`, exclusive range, Bukkit Luck input, and dropper `Conditions` have separate strict evidence. Standalone DropTables import has an importer-to-runtime parser contract, with unmapped table conditions rejected fail-closed. Strict fixture `srv-paper-fractional-bonus-strict-20261102-r4` observes 63 extra lines across 128 natural deaths at level 1 with a 0.5 bonus; `BiasStat`, custom Mythic Luck enchantment/curses integration, complete condition combinations, and the broader official catalog remain incomplete |

## 7. Migration and Verification

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Mob Options mapping and preservation | mapped-unverified | Broad mapping with unsupported preservation; fixed Mythic `Options.Size` maps to `entity.size`, positive inclusive ranges (`2-5`/`2to5`) map to `entity.size-min/max` and are sampled once during Slime/MagmaCube/Phantom option application, while invalid/reversed ranges remain visible with migration warnings. Strict Paper fixture `srv-paper-entity-size-range-strict-20261107-r1` imports `2to5` and samples all three supported entity families 64 times each within range, with both endpoints observed. `Options.ReinforcementsChance` now maps valid Zombie-family values from `0` through `1` to Paper's native Zombie reinforcement attribute; the strict Paper 1.21.1-133 fixture `srv-paper-reinforcements-chance-strict-20261107-r1` reads back `0.38` from a real Zombie and verifies boundary, wrong-type, out-of-range, and runtime-rejection controls. `Options.Scale` maps to the production model `SpawnOptions.scale` path, and strict Paper 1.21.1-133 fixture `srv-paper-integration-scale-20261031-r2` reads back `scale-options result=true scale=1.25` from `mobs/scale_probe.yml`; `Options.Health` uses the production entity health/max-health path, and strict fixture `srv-paper-integration-health-20261031-r3` reads back `health-options result=true health=42.0 max-health=42.0` from `mobs/health_probe.yml`; `Options.MovementSpeed` maps to `mob.move-speed` and is applied to the production Bukkit `MOVEMENT_SPEED` base attribute, with strict fixture `srv-paper-integration-movement-speed-20261031-r6` reading back `movement-speed-options result=true base=0.4` from `mobs/movement_speed_probe.yml`; `Options.AttackSpeed` is preserved as unsupported because Paper 1.21.1 exposes no `ATTACK_SPEED` attribute on the tested Zombie entity (strict diagnostic `srv-paper-mob-attack-speed-20261101-r1` returned no attribute), rather than claiming a no-op mapping; `Options.KnockbackResistance` maps to `mob.knockback-resistance` and is applied to Bukkit `KNOCKBACK_RESISTANCE`, with strict fixture `srv-paper-integration-knockback-resistance-20261031-r8` reading back `knockback-resistance-options result=true base=0.75` from `mobs/knockback_resistance_probe.yml`; `Options.FollowRange` maps to `mob.sight-radius` and is applied to Bukkit `FOLLOW_RANGE`, with strict fixture `srv-paper-integration-follow-range-20261031-r9` reading back `follow-range-options result=true base=40.0` from `mobs/follow_range_probe.yml`; `Options.Damage` maps to `mob.attack-damage` and is applied to Bukkit `ATTACK_DAMAGE`, with strict fixture `srv-paper-integration-damage-20261031-r10` reading back `damage-options result=true base=12.0` from `mobs/damage_probe.yml`; root `Armor` maps to `mob.armor` and is applied to Bukkit `ARMOR`, with strict fixture `srv-paper-integration-armor-20261031-r11` reading back `armor-options result=true base=8.0` from `mobs/armor_probe.yml`; configured entity flags `NoAI`, `Invincible`, `Silent`, `Gravity`, and `Collidable` are read back by strict fixture `srv-paper-integration-entity-flags-20261031-r12`; the complete Mythic Mob Options catalog remains unverified |
| Equipment/Items/Attributes mapping | mapped-unverified | importer and runtime tests cover equipment, item metadata, and mob drop-experience field preservation/consumption; strict Paper fixture `srv-paper-integration-exp-20261031-r16` verifies the configured value reaches `EntityDeathEvent`; complete Mythic catalog not covered |
| Skills importer | mapped-unverified | common mechanics normalized; standalone `skill{s=...}` references now become ordered `cast-skill` actions; `MigrationRuntimeConditionContractTest` binds importer-asserted canonical conditions and mechanics through `SkillService`; unsupported mechanics remain preserved |
| Spawner importer | mapped-unverified | `GroupSize` maps to runtime `group-size`, official `MaxMobs` maps to `max-alive`, `RandomMobs` weights reach the production pool, and Mythic `Conditions` including `World{w=...}` and categorized `BiomeType` translate to runtime conditions. `MigrationRuntimeSpawnerContractTest` verifies the imported world name reaches `world-is <name>`, matching worlds spawn and missing worlds fail closed, alongside fixed/ranged groups and normalized 1:3 weights. Latest strict Paper fixture `srv-paper-mythic-spawner-biome-strict-20261107-r2` verifies natural matching-world/weather/biome generation, missing-world, thunder, and `desert` category rejection, group cap, weighted pool, and variant-specific natural DropTables. Full Mythic spawner semantics and the complete condition catalog remain incomplete |
| Unsupported report schema | implemented-runtime | `status`, `severity`, source/target/value assertions in importer tests |
| Paper integration suite | mapped-unverified | Current release fixture `srv-paper-bee-options-strict-20261107-r1` completes 386 probes (367 passed, 19 expected rejections, zero incomplete) on Paper 1.21.1-133 and verifies imported Bee nectar/stung state against an inverse control through production spawning. Previous strict fixture `srv-paper-horse-options-strict-20261107-r1` verifies imported Horse color/style/domestication/jump values; earlier fixtures verify Sheep color/sheared values, Cat `Type`/`Sitting`/`CollarColor`, Creeper radius/fuse/charged settings and `PreventSuicide` cancellation, Zombie reinforcement chance against the native attribute, and ranged Size on Slime, MagmaCube, and Phantom. Other fixtures verify real-client `Interactable=false` cancellation and `true` allowance, imported `TrackLocation`/`@TrackedLocation`/`DistanceFromTrackedLocation`, matching-world and biome Spawner conditions, weighted pools, variant-specific natural DropTables, imported group caps, and Chance handling. Other strict fixtures cover imported skill audience mutation and imported Mob `Options.ExecutionAudience` through production spawn, target selection, PDC persistence, and two-client visibility transitions; Mythic Mob defaults, natural Potion consumption/expiry, real-client movement beyond `SpawningDistance`, web reload, menus, `isInAudience`, `LookingAt`, server-version boundaries, name/inventory/Aura/entity-state conditions, production location-targeter dispatch, natural DropTables, autonomous Lava damage, dual-client Audience workflows, Pin targeters, sorting, and in-combat behavior. Exact reports, compressed server/client logs, and plugin hashes are in `tools/paper-integration/release-evidence.json`. The complete Mythic catalog and broader Paper/client-version semantics remain open |

An earlier archived strict fixture is `srv-paper-plugin-hash-strict-20261102-r1`:
277 probes, 265 passed, 12 expected negative controls, and zero incomplete.
Its report is `tools/paper-integration/reports/paper-1.21.1-plugin-hash-20261102.json`
and records the tested input JAR SHA-256.
The earlier archived strict fixture is `srv-paper-threat-provider-strict-20261102-r1`:
277 probes, 265 passed, 12 expected negative controls, and zero incomplete.
Its report is `tools/paper-integration/reports/paper-1.21.1-threat-provider-20261102.json`.
The earlier archived strict fixture is `srv-paper-context-chain-strict-20261102-r7`:
277 probes, 265 passed, 12 expected negative controls, and zero incomplete.
Its report is `tools/paper-integration/reports/paper-1.21.1-context-chain-20261102.json`.
The earlier archived strict fixture is `srv-paper-context-targeters-strict-20261102-r1`:
276 probes, 264 passed, 12 expected negative controls, and zero incomplete.
Its `contextTargeterControls` probe checks ordering, radius, and invalid values
with real Zombie entities. The archived report is
`tools/paper-integration/reports/paper-1.21.1-context-targeters-20261102.json`.
The earlier archived strict fixture is `srv-paper-full-catalog-strict-20261102-r2`:
275 probes, 263 passed, 12 expected negative controls, and zero incomplete.
It includes real player Potion consumption and natural expiry, with an observed
expiry event and skill action, and 69 extra fractional Bonus lines in 128
natural deaths. The report is
`tools/paper-integration/reports/paper-1.21.1-full-catalog-20261102.json`.
The earlier archived strict follow-up is `srv-paper-fractional-bonus-strict-20261102-r4`:
273 probes, 261 passed, 12 expected negative controls, and zero incomplete.
It records 63 extra lines in 128 natural-death samples with a level-one 0.5
bonus; the raw Potion workflow was not requested. The previous archived report is
`srv-paper-drops-imported-range-strict-20261102-r5`: 273 probes, 262 passed,
11 expected negative controls, and zero incomplete. Its imported DropTable
probe verifies migration, runtime parsing, and natural-death drops. The latest
targeter-specific report is
`srv-paper-targeter-sorting-strict-20261102-r1`: 272 probes, 260 passed, 12
expected negative controls, and zero incomplete. It verifies health and age
sorting with live Bukkit entities and rejects an unsupported sort mode. The previous targeter report
`srv-paper-targeter-skip-index-strict-20261102-r3` has 271 probes, 259 passed,
12 expected negative controls, and zero incomplete; it verifies sorted
skip-before-limit behavior for `skipTargetsUpToIndex` and `stuti`. The older
targeter report `srv-paper-targeter-players-strict-20261102-r1`
has 272 probes, 260 passed, 12 expected negative controls, and zero incomplete;
it adds `@PlayersOnServer` and `@everyone` runtime evidence to the registered
AI selector probe. The full baseline report `srv-paper-integration` has
271 probes, 259 passed, 12 expected negative controls, and zero incomplete.
It adds required Survival-mode and Ender Dragon alive condition evidence,
completes the dual-player menu workflow, and retains the autonomous Lava-damage
and Pin targeter paths, including fail-closed rejection of an oversized pin
region. Its archived report is
`tools/paper-integration/reports/paper-1.21.1-full-strict-20261102.json`.
The focused strict movement report is
`srv-paper-spawning-distance-client-walk-strict-20261102-r3`: 264 probes, 253
passed, 11 expected negative controls, and zero incomplete.
The focused strict block-selector report
`srv-paper-block-selector-strict-20261101-r2` has 253 probes, 242 passed, 11
expected negative controls, and zero incomplete. It includes isolated Paper
block-tag and wildcard condition checks plus the legacy SpawningDistance
production despawn lifecycle, world-target validity, context-chain,
nearest-selection, runtime-alias, and fail-closed parameter probes.

Strict fixture `srv-paper-integration-exp-20261031-r17` adds
`despawnOptions` for the production `Options.Despawn`/`Persistent`
configuration-to-instance path and records
`despawn-options result=true mode=PERSISTENT distance=0.0 persistent=true`.

Strict fixture `srv-paper-integration-exp-20261031-r19` adds
`visibilityOptions` for `VisibleByDefault`, `Invisible`, and `AlwaysShowName`,
recording `visibility-options result=true visible-by-default=true
invisible=true show-name=true` on a real configured Zombie.

Incremental Spawner evidence update: strict fixture
`srv-paper-integration-exp-20261031-r20` verifies `group-size=3-5` respects
`max-alive=2` through the production `forceSpawn` path, recording
`alive=2 total=2 limit=true`.

Strict fixture `srv-paper-integration-exp-20261031-r21` extends this to the
live scheduler path, recording `before=2 after=4 alive=2 max-alive=2
group-min=3 group-max=5 limit=true`.

Earlier strict fixture `srv-paper-integration-exp-20261031-r25` verifies the named
region condition through the production Spawner gate, recording the positive
line `spawner-region id=region_probe requested=true region=integration_box
failure=null` and the negative line
`spawner-region-outside id=region_probe_outside requested=false
region=integration_box failure=conditions rejected location`.

Strict fixture `srv-paper-integration-exp-20261031-r28` adds natural Spawner
instance recycling, recording `before=2 after=3 released=true respawned=true
alive=1 interval=40`. The probe removes a real tracked instance, observes the
`max-alive=1` slot release, and verifies the shared scheduler creates the next
instance.

## Release Gate

The overall objective remains open until every row is either
`implemented-runtime` with Paper evidence or explicitly accepted as an external
dependency. `mapped-unverified` and `unsupported-preserved` rows are not parity
completion.

The strict report gate also requires the generated report's `expectedProbeIds`
catalog to be present and every registered static/dynamic probe to appear in the
report; missing client, random-distribution, or coordinate evidence is reported
as `not-observed` instead of being silently omitted.

The Maven quality gate passed with Apache Maven 3.9.16, including SpotBugs and
the Paper report-contract profile. On 2026-10-21, the strict Paper 1.21.1-133
release gate also passed against the archived official Paperclip: fixture
`srv-paper-integration-final-r47` contains 224 observed probes, with 216
`passed` and 8 expected `observed-rejected` negative controls. The fixture is
reproducible through
`tools/paper-integration/fetch-paper-1.21.1.ps1`, which verifies `version.json`
and SHA-256 before writing `srv-1.21.1/paper.jar`.

The earlier strict Paper 1.21.1-133 fixture `srv-paper-integration-horse-chest-20261031-r2`
passed with 230 observed probes: 220 `passed`, 10 expected
`observed-rejected`, and zero incomplete. Its Horse probe records
`horse-options result=true saddled=true armor=true chest=true` through the
production option and equipment paths; `HorseArmor` is read back through
`ArmoredHorseInventory#getArmor`, and `CarryingChest` is read back from a real
Donkey through `ChestedHorse#isCarryingChest`.

The preceding strict Paper 1.21.1-133 fixture `srv-paper-integration-scale-20261031-r2`
passed with 231 observed probes: 220 `passed`, 11 expected
`observed-rejected`, and zero incomplete. Its Scale probe records
`scale-options result=true scale=1.25` from the real configured
`mobs/scale_probe.yml` path. The same run retains Horse, Adult,
PatrolSpawnPoint, Armor Stand, Interactable/Armor Stand event, vehicle, and
`aiRoute=passed` evidence.

The preceding strict Paper 1.21.1-133 fixture
`srv-paper-integration-health-20261031-r3` passed with 232 observed probes:
222 `passed`, 10 expected `observed-rejected`, and zero incomplete. Its Health
probe records:

```text
health-options result=true health=42.0 max-health=42.0
```

This reads back the configured `mob.health: 42` from the real
`mobs/health_probe.yml` through `spawnMobCore`, including both the entity's
current and maximum health values.

The preceding strict Paper 1.21.1-133 fixture
`srv-paper-integration-movement-speed-20261031-r6` passed with 233 observed
probes: 222 `passed`, 11 expected `observed-rejected`, and zero incomplete.
Its MovementSpeed option line is:

```text
movement-speed-options result=true base=0.4
```

This reads back the configured `mob.move-speed: 0.4` from the real
`mobs/movement_speed_probe.yml` through `spawnMobCore` and Bukkit's
`MOVEMENT_SPEED` attribute.

The preceding strict Paper 1.21.1-133 fixture
`srv-paper-integration-knockback-resistance-20261031-r8` passed with 234
observed probes: 224 `passed`, 10 expected `observed-rejected`, and zero
incomplete. Its KnockbackResistance option line is:

```text
knockback-resistance-options result=true base=0.75
```

This reads back the configured `mob.knockback-resistance: 0.75` from the real
`mobs/knockback_resistance_probe.yml` through `spawnMobCore` and Bukkit's
`KNOCKBACK_RESISTANCE` attribute.

The preceding strict Paper 1.21.1-133 fixture
`srv-paper-integration-follow-range-20261031-r9` passed with 235 observed
probes: 224 `passed`, 11 expected `observed-rejected`, and zero incomplete.
Its FollowRange option line is:

```text
follow-range-options result=true base=40.0
```

This reads back the configured `mob.sight-radius: 40` from the real
`mobs/follow_range_probe.yml` through `spawnMobCore` and Bukkit's
`FOLLOW_RANGE` attribute.

The preceding strict Paper 1.21.1-133 fixture
`srv-paper-integration-damage-20261031-r10` passed with 236 observed probes:
226 `passed`, 10 expected `observed-rejected`, and zero incomplete. Its Damage
option line is:

```text
damage-options result=true base=12.0
```

This reads back the configured `mob.attack-damage: 12` from the real
`mobs/damage_probe.yml` through `spawnMobCore` and Bukkit's `ATTACK_DAMAGE`
attribute.

The preceding strict Paper 1.21.1-133 fixture
`srv-paper-integration-armor-20261031-r11` passed with 237 observed probes:
227 `passed`, 10 expected `observed-rejected`, and zero incomplete. Its Armor
option line is:

```text
armor-options result=true base=8.0
```

This reads back the configured root `Armor: 8` from the real
`mobs/armor_probe.yml` through `spawnMobCore` and Bukkit's `ARMOR` attribute.

The historical strict Paper 1.21.1-133 fixture
`srv-paper-integration-entity-flags-20261031-r12` passed with 238 observed
probes: 227 `passed`, 11 expected `observed-rejected`, and zero incomplete.
Its configured entity flag line is:

```text
entity-config-flags result=true no-ai=true invulnerable=true silent=true gravity=false collidable=false
```

This reads back the configured `entity.no-ai`, `entity.invulnerable`,
`entity.silent`, `entity.gravity`, and `entity.collidable` values from the real
`mobs/entity_flags_probe.yml` through `spawnMobCore`.

The strict follow-up fixture `srv-paper-integration-exp-20261031-r14` passed
with 239 observed probes: 228 `passed`, 11 expected `observed-rejected`, and
zero incomplete. Its additional root-`Exp` line is:

```text
exp-options result=true experience=13 event=true
```

Latest Paper evidence: `srv-paper-integration-vehicle-fix-20261015` reconfirmed
the implemented entity-option, condition, item, loot, spawner, and Audience
probes and recorded real player-driven `VehicleDestroyEvent` handling with
`enter=true exit=true dismount=true destroy=true`. A separate fixture also
verified the real no-attacker `EntityDamageEvent` path with
`environmental-damage trigger=true`. Follow-up strict fixture
`srv-paper-environmental-natural-strict-20261102-r1` now observes natural Lava
damage and its configured action; this closes only the Lava-tick evidence gap,
not the overall Trigger catalog.

Runtime/importer follow-up coverage also now includes target environmental and
visibility aliases: `target-is-on-fire`, `target-in-air`, `target-in-water`,
`target-in-lava`, `target-is-invisible`, `target-is-climbing`, and
`target-is-blocked`, with runtime and migration tests. These additions do not
change the row status because the complete Mythic condition catalog and broad
Paper evidence are still incomplete.

Target ground-state aliases `target-is-on-ground` and `target-is-airborne` now
use the Bukkit `LivingEntity#isOnGround()` state directly, with runtime and
migration coverage; they remain within the mapped-unverified Conditions row
until full Paper catalog evidence is complete.

Paper fixture `srv-paper-integration-target-env-20261016` records
`target-environment=true` for target fire and visibility conditions; the full
Conditions row remains mapped-unverified until the complete catalog is covered.

Paper fixture `srv-paper-integration-target-fluids-20261016` records
`target-fluids=true` for target water/lava conditions with block cleanup.
Fixture `srv-paper-integration-target-blocked-20261016` additionally records
`target-blocked=true` with the temporary obstruction restored after evaluation.

Latest real-player rerun: `srv-paper-integration-followup-20261016` used Paper
1.21.1-133 and Mineflayer 4.39.0. Its report recorded 69 passed probes and 10
`not-observed` probes. The player workflow exercised SkillService equipment,
potion, drop-item, teleport, and custom-item resolution; condition composition,
target categories/health/environment, state and spatial targeters; real block
break/place and item pickup; delayed entity-target triggering; and Audience
dispatch. `state-blocking` remained false, vehicle destroy remained false, and
potion consumption was skipped due the client item-component limitation. Strict
report validation correctly rejected the run because 10 registered probes
remain unobserved. These results do not upgrade the catalog rows to complete.

Latest two-client UI rerun: `srv-paper-integration-menu-followup-20261016-r7`
used Paper 1.21.1-133 and Mineflayer 4.39.0. Its structured report marks
`custom-menu-player-workflow`, `audience-player-delivery`, and
`audience-filter-delivery` as `passed`. Both clients observed message, title,
actionbar, particle, radius, permission, and faction selection behavior; the
tester verified denied-click retention, pagination, allowed command execution,
and inventory close. The overall integration matrix remains incomplete.

Latest random-spawn sample: `srv-paper-integration-random-distribution-20261016-r2`
used Paper 1.21.1-133 and a dedicated `random_distribution_probe` pool. All 100
forced spawns were accepted; the report records expected
`{emberling=0.5, emberling_boss=0.5}`, observed `{emberling=0.52,
emberling_boss=0.48}`, and deviations `+0.02/-0.02` with status `observed`.
This is a sample observation, not a claim that every random-spawn condition or
distribution is complete.

Tolerance-gated rerun: `srv-paper-integration-random-tolerance-20261016` ran
the current build with `-RandomSpawnTolerance 0.15`; 100 accepted spawns yielded
an observed 0.50/0.50 split, maximum absolute deviation 0, and probe status
`passed`. The report records the threshold and max deviation. The strict report
gate still rejected the full fixture because 44 other registered probes were
not observed.

Latest Conditions addition: Paper fixture
`srv-paper-integration-condition-mobility-20261016-r2` exercised the newly
mapped `mounted`, `moving`, and `has-passenger` conditions with a real online
player workflow and real Paper Zombie/Pig entities. Its `conditionMobility`
probe passed after verifying unmounted/empty states, mounting the Pig onto the
Zombie, observing a passenger, and applying non-zero velocity. These conditions
are covered runtime/importer aliases, but the full official Conditions catalog
remains incomplete.

Paper fixture `srv-paper-integration-leashed-condition-20261016-r2` also
verifies official `isLeashed` runtime/importer aliases with a real Paper Wolf:
the self-test attached a leash to the online player, evaluated `is-leashed`,
and restored the original leash state. The structured `conditionLeashed` and
aggregate `conditionCatalog` probes passed. The official Conditions catalog
remains incomplete.

Paper fixture `srv-paper-integration-has-owner-20261016` verifies `has-owner`
and importer alias `hasOwner` on a real tamed Wolf with an online Paper player
owner; `conditionHasOwner` and `conditionCatalog` passed. Runtime behavior
currently matches Bukkit `Tameable#getOwner()`. Mythic's separate `SetOwner`
metadata for non-tameable MythicMobs remains unsupported and must not be treated
as covered by this condition probe.

The follow-up fixture `srv-paper-integration-owner-relations-20261016` now
implements and verifies custom owner relations on non-tameable Mythic mobs:
PDC-backed `set-owner`/`remove-owner`, `has-owner`, and `@Owner` resolve the
online player, then clear the relationship. Its `customOwnerRelation` probe
passed. The relation survives entity serialization through PDC; owner target
resolution is available while that owner entity is loaded. Custom Parent
relationships remain unimplemented.

The follow-up fixture `srv-paper-integration-parent-relations-20261016` now
implements and verifies `set-parent`, `remove-parent`, `has-parent`,
`is-parent`/`parent`, and `@Parent`. The real-player Paper probe covered
relationship set, condition/comparison/targeter lookup, simulated player-quit
cleanup, and explicit removal. Player parents are session scoped; non-player
parent UUIDs are stored on the child in PDC and resolve only while loaded.

Fixture `srv-paper-integration-is-child-20261016` covers Helstera's custom
parent relation aliases `is-child`/`child`: a real Pig was assigned the
model-bound Wolf as parent and the relation condition passed against the Pig
target. Mythic `isChild`/`child` also means that the target is a child of the
caster, while `isBaby` is the age check; the importer and runtime aliases now
preserve that distinction. This historical fixture predates the importer fix
and verifies runtime relation behavior only, not imported Mythic skills.
`conditionIsChild`, `parentRelation`, and aggregate `conditionCatalog` are
reported as passed; an imported-condition Paper probe remains open.

Fixture `srv-paper-integration-parent-derived-20261016` covers `is-parent-alive`
and `is-sibling`: sibling comparison passes for two children sharing a live
parent, parent-alive passes before the parent is removed and fails afterward.
`parentDerivedConditions`, `parentRelation`, and aggregate `conditionCatalog`
passed on Paper 1.21.1.

Parent condition semantics update: `has-parent` now reports a stored relation
even when its parent entity is unloaded, while `is-parent-alive` requires the
parent to resolve to a valid living entity. API regression coverage also makes
relation lookup fail closed before Bukkit is initialized.

Spawner composite control rerun: `srv-paper-integration-spawner-composite-20261016-r2`
uses registered condition names (`world-is ...` and `y-level-above ...`) inside
the positive `all{}` expression. Paper observed the positive gate as
`requested=true failure=null` and the negative gate as
`requested=false failure=conditions rejected location`. The report test asserts
both outcomes, so a rejected positive control cannot pass silently.

Environment gate fixture `srv-paper-integration-spawner-environment-20261016`
verified real Paper Spawner requests under temporary world-state changes:
`raining` accepted while storm was enabled and rejected while clear;
`time-between=2000,10000` accepted at 6000 and
`time-between=13000,23000` rejected at 6000. The fixture restored original
storm/thunder/full-time state after each request. Corresponding probes were
recorded as `passed` or `observed-rejected`.

Biome gate fixture `srv-paper-integration-spawner-biome-20261016` temporarily
set the force-spawn location biome to DESERT and restored the original biome.
`biome-is=DESERT` spawned successfully; `biome-is=PLAINS` was rejected. Both
real Paper outcomes are included in the structured report.

Spawner max-spawn control rerun: `srv-paper-integration-maxspawns-20261016`
disables scheduler spawning for `max_spawn_probe` while retaining direct
`forceSpawn` calls. Paper recorded first=true, second=false, total=1; the
`spawnerMaxSpawns` probe passed. This avoids the timer consuming the quota
before the assertion.

Official Conditions `HasTag` is now implemented as `has-tag` with Mythic aliases
`hasTag` and `hasScoreboardTag`, including the `tag`/`t` attribute mapping.
Paper fixture `srv-paper-integration-has-tag-20261016` verified absent -> added
-> removed scoreboard-tag states on a real Zombie; `conditionHasTag` and
`conditionCatalog` passed. The full condition catalog remains incomplete.

Spawner heightmap/ground fixture `srv-paper-integration-spawner-ground-20261016-r2`
verified natural-location sampling through Paper's OCEAN_FLOOR heightmap: the
positive probe spawned above SAND at Y=64; a negative probe temporarily cleared
the selected column from minHeight through maxHeight, received `no valid spawn
location`, and restored all 384 captured block states. Both heightmap and
ground-restoration report probes passed.

Exploratory rerun `srv-paper-integration-exploratory-1.21.11-r139` repeats the
negative control with `requested=false`, `failure=no valid spawn location`, and
`restored=true`. Its small positive radius keeps sampling inside the cleared
column; zero radius is normalized to the service default and is not suitable
for this isolated negative probe.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r138` records
delayed native targeting with `entity-target-trigger=true`,
`target-reason=CLOSEST_PLAYER`, and `target-cancelled=false`. The report
contract now treats `target-find-misses` as diagnostic global listener noise,
not as a per-probe failure condition.

World-border fixture `srv-paper-integration-spawner-border-20261016-r2` moved
the isolated world's border to a 20-block square and restored its original
center/size. An inside spawner with configured radius 16 was clipped to radius
10 and spawned successfully; an outside spawner was rejected with no valid
location. Both dedicated border probes passed and report restoration.

AI priority rerun `srv-paper-integration-ai-priority-20261016-r3` fixed a real
decision bug: an explicitly assigned combat target was discarded in favor of a
fresh sensing pass. `preferredCombatTarget` now retains a valid
selector-compatible combat target. Paper verified attack/flee priority in both
directions, with `aiPriority` and target-setup detail probes passed.

Diagnostic rerun `srv-paper-integration-ai-priority-diagnostic-20261016` on the
current build confirmed both probe inputs: PLAYERS mode, attack/chase/flee
enabled, selector-compatible online combat targets, and inverse priorities
0/1 and 1/0. Paper recorded the expected attack and flee states; both AI probes
passed again in exploratory fixture `srv-paper-integration-exploratory-1.21.11-r126`
after the self-test revived/protected a player killed by earlier natural probes.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r127` also
records `itemPickup=passed` and `environmentalDamage=passed`. The event
self-test now keeps its managed player alive through the scheduled pickup
workflow, and the no-attacker damage probe attaches a dedicated `on-damage`
profile instead of reusing the ENTITY_ATTACK-only trigger fixture.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r128` records
`lootDynamicChance=passed`; the dynamic chance self-test uses a runtime
`<random.1to1>` expression so the evaluator path is exercised deterministically
without probabilistic report flakiness.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r133` records
`vehicleEvents=passed` and `ejectPassenger=passed`. Enter, exit, dismount, and
eject remain real Boat paths; the fixture additionally calls the real Bukkit
`VehicleDestroyEvent` for the managed vehicle, isolating event dispatch from
client attack timing.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r138` records
`blockEvents=passed` from immediate synthetic Bukkit `BlockBreakEvent` and
`BlockPlaceEvent` dispatch. Natural Mineflayer block break/place timing remains
separate from the verified server event route.
passed.

Pathfinder fixture `srv-paper-integration-path-normal-20261016` completed the
native path probe after the isolated server was changed from PEACEFUL to NORMAL
while natural monster spawning remained disabled. A deterministic platform and
temporary entity target produced `found=true reachable=true accepted=true
cleared=true on-ground=true stop-calls=4`. This is the first real accepted and
cleared path evidence; full native goal parity remains open.

Exploratory Paper follow-up `srv-paper-integration-exploratory-1.21.11-r22`
connected a real player and recorded `scoreboard-tag-mechanics=true` in the
`skill-actions` self-test. The structured report marked both `conditionHasTag`
and `scoreboardTagMechanics` as passed after real caster and targeter-selected
scoreboard-tag mutations. This remains Paper 1.21.11 compatibility evidence and
does not change the Paper 1.21.1 strict release boundary.

Natural Mob Option evidence update: exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r55` now records
`creeperNaturalFuse`, `mobOptionsNatural`, and `mobOptionsMountEvent` as passed.
The conversion probe leaves native Zombie AI enabled, observes the unmanaged
control Zombie convert in water, and confirms the managed
`preventTransformation` Zombie remains valid; Bee stinger restoration and
protected/control mount-event cancellation are also observed. Natural AI jockey
selection is still not claimed, and the Mob Options row remains
`mapped-unverified` pending broader ordering and strict Paper 1.21.1 evidence.

Inventory condition evidence update: exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r56` records
`hasItemCondition=passed` after the self-test restores the player's original
inventory, inserts a temporary Shield for the positive comparator, removes it
for the negative comparator, and restores the inventory again. Strict Paper
1.21.1 evidence remains open.

Target-fluid evidence update: exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r59` records
`target-fluids-delayed water=true lava=true passed=true` after evaluating a
real target on successive Paper ticks and restoring the temporary blocks. The
same-tick diagnostic remains false because Paper's cached fluid state updates
on the next tick; the report probe intentionally accepts only the delayed
observation. Strict Paper 1.21.1 evidence remains open.

Official population/dimension condition update: `LivingInRadius` now counts
valid Bukkit living entities around the active location, `MobsInChunk` counts
valid Bukkit `Mob` entities in the active chunk, and `Dimension` matches the
Bukkit world environment with `OVERWORLD`/`END` aliases. Migration and runtime
unit coverage protect the argument mappings; exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r60` verifies all three through the
passing `conditionUtilityAliases` probe. Strict Paper 1.21.1-133 fixture
`srv-paper-drowning-strict-20261102-r2` passes the aggregate utility probe, and
follow-up `srv-paper-mobs-in-chunk-condition-strict-20261102-r1` adds the
required dedicated `mobsInChunkCondition`: a real valid Bukkit Mob passes
`mobs-in-chunk >=1`, while `>=999999` rejects. Boundary fixture
`srv-paper-mobs-in-chunk-boundary-strict-20261102-r1` adds zero-count and
adjacent-chunk isolation checks with two explicitly loaded neighboring chunks;
only the occupied chunk receives the controlled Mob. The required probe passes
all boundary assertions on Paper 1.21.1-133.

Official spatial condition update: `Height`, `HeightAbove`, `HeightBelow`,
`Pitch`, `Yaw`, and `OnBlock` now use Mythic numeric-range parsing and Bukkit
entity/location state. Migration preserves `to`/hyphen/comparator ranges,
orientation aliases, clamp flags, and exact material lists; wildcard/block-tag
forms remain explicitly unmapped instead of being guessed. Exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r64` records
`spatial-condition-details height=true orientation=true on-block=true` and
keeps the aggregate `conditionUtilityAliases` probe passed.

Official time/light condition update: `WorldTime`, `SkyLightLevel`, and
`LightLevelFromBlocks` now use the active world's normalized time and Bukkit
`Block#getLightFromSky()`/`getLightFromBlocks()` values with Mythic range
syntax. Migration and unit coverage protect aliases and ranges; exploratory
Paper `srv-paper-integration-exploratory-1.21.11-r65` keeps the aggregate
`conditionUtilityAliases` probe passed.

Official block/biome condition update: `BlockType` supports exact material
lists, Bukkit block tags, and material-name globs through migration and runtime.
Strict Paper fixture `srv-paper-block-selector-strict-20261101-r2` records the
aggregate spatial condition probe passed with isolated tag and wildcard checks.
MMOItem names remain explicitly unmapped. The earlier exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r66` records the exact
`block-type=true biome-type=true` path; the later category fixture is described
in the Conditions catalog above.

Official `Biome` conditions now preserve namespaced and comma-separated biome
IDs plus the `exact`/`e` flag through migration. Runtime matching defaults
unqualified IDs to `minecraft`, and non-exact mode matches a biome key
substring within the same namespace. Malformed IDs fail closed; `BiomeType`
category matching remains separate. Strict Paper 1.21.1-133 fixture
`srv-paper-biome-condition-strict-20261101-r1` verifies the namespaced exact,
comma-alternative, non-exact substring, exact rejection, and mismatched-key
paths through the production `SkillService`; its `biomeCondition` and
`conditionUtilityAliases` report probes pass with zero incomplete probes.
Importer tests and a cross-module runtime contract cover reversed `exact`
and biome argument order, whitespace around alternatives, and preservation of
malformed or unknown arguments. Strict fixture `srv-paper-biome-import-20261102-r1`
also evaluates translated reversed arguments in a real world and checks an
invalid list remains untranslated; it does not exercise a complete YAML import.
The cross-module contract now additionally imports a `Skills/` YAML file and
checks successful and rejected target-condition casts. Unbindable named-skill
conditions block casting, preview, and direct registered skill actions instead
of being silently discarded; the tests cover all three condition fields. This
runtime change is covered by unit and cross-module tests.
Blank and non-string entries in imported skill and standalone DropTable
condition lists are retained as invalid gates or reject table projection, with
warnings; runtime named-skill condition lists also reject these entries. These
malformed-input paths have importer and cross-module tests.
Damage modifier condition lists now preserve malformed entries with an explicit
invalid marker for both section and list forms. Immunity evaluation rejects that
marker even when its caller supplies a permissive condition evaluator; importer
and cross-module tests cover null and numeric entries. Strict Paper fixture
`srv-paper-invalid-conditions-20261102-r2` imports YAML and invokes valid and
invalid target-condition skills on a Zombie; it also evaluates both damage
modifier forms with a permissive evaluator and checks warnings. It does not
exercise a natural trigger or a Bukkit damage event. Non-player trigger context
preservation has unit coverage.
Named skills with `TriggerConditions` now reject a missing or non-living
trigger rather than evaluating against the selected target. Skill preview also
checks target and trigger gates without changing the skill tree's targets.
These newer changes have unit coverage; their specific behavior has not been
independently verified on Paper.
Branch-gated named skills now check the branch before recording cooldown or
entering a busy instance's queue. Unit tests cover rejection while locked,
unlock followed by casting, and queued execution after unlocking. This change
has unit coverage; queue timing and cross-restart behavior still require
separate runtime evidence.
Queued skills whose tree was created by the service now restore the latest
per-instance snapshot when execution starts, preventing a preceding cast's
variables from being overwritten by the stale enqueue-time tree. An explicit
caller-supplied tree remains shared. Unit tests cover both paths; conditions and
branch admission are still evaluated at enqueue time. Strict fixture
`srv-paper-queue-snapshot-20261102-r2` verifies a preceding timed cast sets a
variable and an automatically managed queued skill adds to it, using an
isolated service and real managed Zombie; it does not test a restart.
Instance teardown now invalidates delayed skill actions, including repeats when
teardown occurs inside an executing action. Unit tests cover cancellation,
isolation from another instance, and reuse of an instance ID. This change
is partly verified by `srv-paper-skill-lifecycle-20261102-r1`: an isolated
service directly calls `forget` and confirms one delayed action does not run.
Repeat cancellation and natural entity teardown remain unit-only.
Teardown during a named skill also stops its remaining actions, including
enclosing nested chains, and prevents the removed instance's SkillTree snapshot
from being written again. These paths have unit coverage, not Paper evidence.
Reloading skill definitions while a named skill is running now invalidates that
old action chain, including instance-free contexts; a new skill started during
the reload can execute and retain its state. Unit tests cover both paths; this
reload behavior is partly verified by `srv-paper-skill-lifecycle-20261102-r1`,
which calls `loadSkills(null)` during a managed Zombie's skill and observes the
remaining old action is skipped. New definitions and instance-free contexts
remain unit-only.
The `start-cast` action now uses the same named-skill conditions, branch,
cooldown and queue gates as `castSkill`; unit tests cover the action and direct
entry point. This newer change has not yet been verified on Paper.
Actions bound by `SkillService` for `start-cast` and `cancel-cast` also use
their owning service instead of a global service hook. A two-service unit test
checks both actions; direct catalog factories still use the global hook.

Server/player state condition update: official `ServerIsPaper` now recognizes
Paper-family server identity from Bukkit name/version metadata, while
`IsInSurvivalMode` checks only an explicit target Player in survival mode.
Strict Paper fixture `srv-paper-is-survival-mode-import-strict-20261106-r1`
verifies both `?isInSurvivalMode` and `?inSurvivalMode` through the importer and
`SkillService` against a real online Player in Survival and Adventure, rejects
a Zombie target and malformed arguments, and restores the original mode. The
earlier exploratory Paper `srv-paper-integration-exploratory-1.21.11-r67`
remains historical evidence for `conditionUtilityAliases`.

Managed-entity condition update: official `IsMythicMob` now uses an injected
plugin entity lookup rather than treating every living Bukkit entity as a
Mythic mob. Runtime fallback recognizes only the active managed instance,
migration and unit tests cover the fail-closed path, and exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r68` keeps
`conditionUtilityAliases` passed.

Origin population condition update: official `MobsNearOrigin` now evaluates
valid Bukkit `Mob` entities around `BehaviorContext.origin`, with Mythic amount
range, radius, and exact Bukkit entity-type filters. Unknown type filters fail
closed; MythicMob custom type names are not guessed. Migration/runtime tests
cover the structured arguments, and exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r70` keeps the aggregate utility
probe passed.

Spawn-distance condition update: official `DistanceFromSpawn` now compares the
active target/origin location to `World#getSpawnLocation()` with Mythic range
syntax. Migration/runtime tests cover range arguments, and exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r71` keeps `conditionUtilityAliases`
passed.

Bounding-box condition update: official `BoundingBoxesOverlap` now compares
caster and target Bukkit `BoundingBox` values, with optional `shiftforward`
offset applied along the caster's horizontal facing direction. Migration/runtime
tests cover the offset form; exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r73` keeps the utility probe passed,
and strict Paper 1.21.1-133 `boundingBoxOverlapCondition` verifies real overlap
and separated negative states for two Bukkit entities.

Creeper state condition update: official `IsCreeperPrimed` now maps directly to
Bukkit `Creeper#isIgnited()` and exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r74` verifies a real ignited Creeper.
`IsFrozen` now maps directly to Paper's `Entity#isFrozen()` boolean, with
`isFrozen`/`frozen` aliases, migration and unit coverage, and the condition
catalog self-test toggling freeze ticks before restoring the original state.
Strict Paper 1.21.1-133 fixture `srv-paper-integration-final-r8` records
`frozenCondition=passed`.

Raider state condition update: official `IsRaiderPatrolLeader` now maps to
Bukkit `Raider#isPatrolLeader()`. Unit/migration coverage and exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r75` verify the exact Pillager patrol
leader state through the passing `targetCategories` probe.

Spyglass state condition update: official `IsUsingSpyglass` and `IsScoping`
aliases now import to the target-only `is-using-spyglass` SkillService
evaluator, requiring an active item of `Material.SPYGLASS`. Importer, runtime,
and migration-to-runtime contract tests cover both aliases and fail-closed
behavior. Strict Paper 1.21.1-133 fixture
`srv-paper-is-spyglass-import-strict-20261106-r1` verifies the imported
positive/negative paths on a real Player, a non-player target, and malformed
arguments; exploratory Paper 1.21.11 evidence remains historical.

Ender Dragon condition update: official `EnderDragonAlive` now scans the
target world's valid `EnderDragon` entities; exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r77` verifies a real temporary
dragon. Strict Paper 1.21.1-133 fixture
`srv-paper-ender-dragon-alive-strict-20261102-r1` passes the dedicated
`enderDragonAliveCondition`: a real dragon is accepted, then removed and
rejected, and entity cleanup is confirmed. Its release report has 263 probes
(252 passed, 11 expected negative controls, zero incomplete). `PlayerKills` is
now implemented as a managed-instance lifetime
counter rather than a Bukkit player statistic. The runtime records one count
only when a managed killer causes a real Player death; `playerKills`/
`player-kills` aliases, range parsing, migration, condition tests, and instance
teardown cleanup are covered. The new Paper self-test logs positive `>=1` and
negative `>1` checks, but no new Paper fixture has been run yet.

The same condition self-test now checks official `Absorption` on the managed
Zombie with a temporary value of 4: `>=4` and `=4` pass while `>4` rejects, and
the original absorption value is restored. The report catalog requires
`absorption-condition=true`; this new runtime observation still awaits a fresh
Paper fixture.

Ender Dragon phase update: official `EnderDragonPhase` now matches one or more
Bukkit `EnderDragon.Phase` enum names on the explicit target. Migration/runtime
tests cover phase lists, and exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r78` verifies the live `CIRCLING`
phase before removing the temporary dragon. Strict Paper 1.21.1-133 fixture
`srv-paper-ender-dragon-phase-strict-20261101-r1` additionally verifies a real
dragon positive match, a phase list, a negative match, rejection of the mixed
list `CIRCLING,INVALID`, and restoration. The dedicated
`enderDragonPhaseCondition` report probe passed. The aggregate strict report
has one unrelated incomplete probe, `spawnerBorderClipped`, so this fixture is
not a complete release-gate pass.

Strict follow-up `srv-paper-ender-dragon-phase-strict-20261101-r3` passes the
full Paper 1.21.1-133 release report with all 260 probes observed: 248 passed,
12 expected negative controls, and zero incomplete. It retains the real-dragon
phase and invalid-list assertions, and now also passes the natural
border-clipped spawn probe and real-player `LookingAt` condition. The border
fixture retries up to eight random ground samples only for the positive
natural-location probe when a sample has no valid spawn location; the final
observed attempt count is recorded in the log.

Altitude condition update: official `Altitude` now measures the active target
height above `World#getHighestBlockAt` surface, applies the Mythic height range,
and rejects values above `maxHeight`. Migration/runtime tests cover the range
and maximum arguments; exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r79` keeps the utility probe passed.

Vehicle state update: official `VehicleIsDead` now checks the caster's mounted
vehicle only, using Bukkit `Entity#getVehicle()` and `isDead()`. Runtime and
migration tests cover fail-closed/no-vehicle behavior; exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r80` keeps the utility probe passed.
Persistent dead-vehicle timing is not claimed because Bukkit removes passenger
relations when the vehicle is removed.

Altitude condition update: official `Altitude` now measures the active target
height above `World#getHighestBlockAt` surface, applies the Mythic height range,
and rejects values above `maxHeight`. Migration/runtime tests cover the range
and maximum arguments; exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r79` keeps the utility probe passed.

Server-version condition update: official `ServerVersion`,
`ServerVersionAfterOrEqual`, and `ServerVersionBefore` now parse Bukkit's
Minecraft version into numeric components and honor inclusive comparisons.
Paper-family/NMS-specific conditions remain separate. The Paper self-test now
uses the live Bukkit version for its exact-version branch instead of assuming
1.21.11, so the aggregate utility probe remains valid on both 1.21.4 and
1.21.11. Exploratory Paper r72 and
`srv-paper-integration-exploratory-20261019-utility-r2` keep the utility probe
passed.

Spawn-reason condition update: official `SpawnReason` now reads the active
target entity's Bukkit `Entity#getEntitySpawnReason()`, accepts the `reason`/
`r` aliases case-insensitively, and fails closed for missing entities, missing
arguments, and invalid enum names. Unit/migration coverage protects the
canonical `spawn-reason` binding; exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r98` records actual `CUSTOM` matching
and a different-enum negative control through `spawn-reason-condition=true`.
This remains exploratory Paper 1.21.11 evidence; strict Paper 1.21.1 evidence
is still blocked by the unavailable release jar.

Entity-category condition update: official `IsWaterMob` and `IsZombie` use the
Paper API's `WaterMob` marker and `Zombie` type checks, with direct aliases and
migration coverage. Strict Paper 1.21.1-133 fixture
`srv-paper-entity-category-import-strict-20261106-r2` verifies all primary and
alias imports on a real Cod/Zombie pair, both cross-type negative paths, and
fail-closed preservation of malformed arguments. Earlier exploratory Paper
1.21.11 fixture `srv-paper-integration-exploratory-1.21.11-r99` verifies the
native condition paths. The full Conditions catalog remains `mapped-unverified`.

Aerial condition update: official `Aerial` now evaluates the active target's
Bukkit `isOnGround()` state and supports `aerial`/`isAerial` migration aliases.
Exploratory Paper `srv-paper-integration-exploratory-1.21.11-r102` verifies a
floating ArmorStand positive and a grounded online-player negative through
`aerial-condition=true`. Strict Paper 1.21.1-133 fixture
`srv-paper-aerial-condition-strict-20261105-r2` verifies both aliases on the
floating ArmorStand and a grounded-player negative through the dedicated
`aerialCondition` release probe, with the target type recorded as player.
The latest strict Paper 1.21.1-133 fixture
`srv-paper-aerial-import-strict-20261105-r1` additionally translates Mythic
`?aerial` and `?isAerial` with `MmConditions`, binds the imported conditions in
`SkillService`, and verifies positive floating and negative grounded results in
the required `importedAerialCondition` release probe. The Conditions catalog
remains mapped-unverified.

Enderman condition update: official `IsEnderman` now uses Bukkit's
`Enderman` type with `isEnderman`/`enderman` aliases and migration coverage.
Exploratory Paper `srv-paper-integration-exploratory-1.21.11-r103` verifies a
real Enderman positive and managed Zombie negative through
`enderman-condition=true`. Strict Paper 1.21.1-133 fixture
`srv-paper-enderman-condition-strict-20261105-r1` now passes the independent
`endermanCondition` release probe with a real Enderman positive and managed
Zombie negative. The overall Conditions catalog remains mapped-unverified.

Enchantment condition update: official `HasEnchantment` now checks Bukkit
main-hand, off-hand, and armor equipment with `enchantment/type/e` selection,
`ANY`, and Mythic numeric level ranges. Unknown enchantments and unavailable
registry state fail closed; exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r104` verifies Sharpness/ANY positive
paths plus level/type negatives through `enchantment-condition=true`. Strict
Paper 1.21.1-133 fixture
`srv-paper-has-enchantment-import-strict-20261105-r1` verifies imported
`?hasEnchantment` and `?hasEnchant` through `SkillService`: Sharpness and `ANY`
positives, level/type negatives, and item restoration pass in the required
`importedHasEnchantmentCondition` probe. The overall Conditions catalog remains
mapped-unverified.

Color condition update: official `Color` now matches Bukkit dye colors and
entity variants for Sheep/Shulker, Cat, Parrot, Horse, and Llama targets, with
`color`/`clr` migration aliases. Exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r108` verifies red Sheep positive,
wrong-color negative, and unsupported Zombie negative paths through
`color-condition=true`. Strict Paper 1.21.1-133 fixture
`srv-paper-color-import-strict-20261105-r1` verifies the importer mappings for
`?color{c=RED}` and `?clr{color=BLUE}`, then confirms a red Sheep passes the
first and rejects the second through required `importedColorCondition` evidence.
The complete Conditions catalog remains mapped-unverified.

Material cooldown condition update: official `MaterialIsOnCooldown` now reads
target-player Bukkit material cooldowns, defaults to `ENDER_PEARL`, and accepts
`material/m` aliases. `ItemGroupOnCooldown` remains separate because its custom
item-group state has no Bukkit equivalent. Exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r110` verifies positive, wrong-item,
and cleared negative paths through `material-cooldown-condition=true`; both
aggregate condition probes pass after correcting self-test ordering. Strict
Paper 1.21.1-133 fixture
`srv-paper-condition-import-strict-20261105-r1` verifies imported
`?materialIsOnCooldown{m=ENDER_PEARL}` and `?matCooldown{material=DIAMOND}` on a
real player's active cooldown through required
`importedMaterialCooldownCondition` evidence. The complete Conditions catalog
remains mapped-unverified.

Trigger item condition update: official `TriggerItemType` now reads a Bukkit
Item entity from `BehaviorContext.trigger()` and matches comma-separated
material names with `types/type/material/m` aliases. Exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r113` verifies STONE/DIRT positive
and DIAMOND negative trigger paths through `trigger-item-type-condition=true`.
Strict Paper 1.21.1-133 fixture
`srv-paper-trigger-types-import-strict-20261105-r2` verifies the Mythic
`?triggerItemType` and `?triggeringItemType` importer paths against a real
dropped Bukkit Item. Official `TriggerBlockType` maps to the origin block for
block trigger states; the same fixture verifies `?triggerBlockType` and
`?triggeringBlockType`, STONE positive, DIAMOND negative, and wrong-state
rejection against a real block. Both probes construct `BehaviorContext` values
for their event states, so they prove importer-to-evaluator behavior but do not
prove natural Bukkit pickup/break event delivery. The report records the exact
plugin SHA-256 from the successful Paper run. The overall Conditions catalog
remains mapped-unverified.

Coordinate difference update: official `XDiff`, `YDiff`, and `ZDiff` now
compare target-minus-caster Bukkit coordinates with Mythic numeric ranges and
`difference/diff/d` aliases. Exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r114` verifies positive X/Y/Z ranges
and an out-of-range X rejection through
`coordinate-difference-condition=true`. Strict Paper 1.21.1 evidence remains
open.

Origin-location update: official `OriginLocation` matches
`BehaviorContext.origin()` by block or exact coordinates with `loc/l/c`,
`x/y/z`, and `exact/e` aliases. Strict Paper 1.21.1-133 fixture
`srv-paper-origin-location-import-strict-20261105-r4` verifies imported primary
and alias forms through `SkillService`, exact coordinates, mismatch rejection,
and malformed number/boolean rejection in `importedOriginLocationCondition`.
The complete Conditions catalog remains mapped-unverified.

Dialogue condition update: official `HasDialogOpen` now reads the active
DialogueService runner for the model instance, with `hasDialogOpen`/
`dialogOpen` aliases and migration coverage. Dialogue hooks are injected after
the service is created, fixing the prior initialization-order gap. The strict
Paper fixture `srv-paper-cinematic-condition-20261105-r1` records successful
start/open/stop/closed transitions and independently checks player audience
scope through `isInCinematicCondition`. Earlier exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r106` also covers the combined
`dialogue-condition=true` path.

### Event skill migration update

Mob skill lines with top-level `~onSpawn` or `~onDeath` are routed to the
corresponding mob trigger, preserving their action metadata. Other event
suffixes, parameterized or conflicting triggers, and event lines in standalone
skill files are preserved as unsupported with a warning. Standalone skill
trigger fields are not consumed by the runtime. Importer tests cover routing
and preservation. A cross-module test loads a migrated mob trigger into an AI
profile and executes its bound nested skill with a targeter, delay and repeat.
Another cross-module test posts a synthetic `ModelSpawnEvent` through the event
bus and verifies the migrated `@Self` action executes. A synthetic Bukkit death
callback also verifies migrated `@Target` and `@Trigger` actions against a
player killer and a non-player projectile shooter, and no trigger target when
there is no killer. The [official onDeath documentation](https://wiki.mythiccraft.io/mythicmobs/Skills/Triggers/ondeath)
specifies the killer as `@Trigger`. Synchronous `cancel-event` is now passed
the Paper death event before Helstera loot handling; the loot and AI cleanup
listeners ignore already-cancelled events. A synthetic cross-module test checks
the cancellation call and listener ordering. Explicit `Options.ReviveHealth`
values map to `mob.revive-health`: `-1` uses maximum health, positive values
are capped at maximum health, and invalid values are preserved with a warning.
Synthetic cross-module tests exercise these values after synchronous cancellation.
Exploratory Paper 1.21.1-133 run `srv-paper-revive-20261102-r4` applied lethal
damage to a `despawn: NEVER` zombie and observed `alive=true attached=true
health=8.0` after 60 ticks. Strict run `srv-paper-revive-strict-20261102-r2`
also passed the new mandatory `reviveNatural` probe, but its full gate failed
because `expNaturalDeath` observed 10 rather than 13 XP. This uses native
Helstera configuration and is not an imported configuration test. Default revive health,
delayed cancellation, and interactions with other plugins that cancel later in
the event pipeline remain open.

Strict follow-up `srv-paper-exp-revive-strict-20261102-r1` observed 13 XP and
passed `reviveNatural`, but failed the full gate because the
`spawner:player_gate_fail` negative control spawned. The experience probe now
accounts for increases in existing nearby orbs; the earlier discrepancy's exact
cause is not established by this run.

Strict follow-up `srv-paper-gate-strict-20261102-r1` passes all 285 report
probes (268 passed, 17 expected rejections). The player gate negative control
now uses a remote loaded location: the nearest player was approximately 4367
blocks away and the configured radius was 8, producing `player gate closed`.
The same run observed 13 XP and successful natural revive. Its report is
archived at `tools/paper-integration/reports/paper-1.21.1-revive-player-gate-20261102.json`.

Earlier strict fixture `srv-paper-death-cancel-20261102-r2` passes the same 285
probes after the death event timing changes. The synthetic custom-loot probe
checks the event drop list, while natural death verifies actual item output.
It does not test cancellation by another plugin. The archived report is
`tools/paper-integration/reports/paper-1.21.1-death-cancel-20261102.json`.

Earlier strict fixture `srv-paper-revive-late-20261102-r1` passes 286 probes
(269 passed, 17 expected rejections), including the HIGH-priority self-test
listener cancellation described above. Its archived report is
`tools/paper-integration/reports/paper-1.21.1-revive-late-20261102.json`.

Earlier strict fixture `srv-paper-damaged-alias-20261102-r1` passes the same
286 probes with the importer and environmental damage changes. The imported
aliases are covered by contract tests, not this Paper fixture. Its report is
`tools/paper-integration/reports/paper-1.21.1-damaged-alias-20261102.json`.

Earlier strict fixture `srv-paper-damaged-import-20261102-r2` passes 287 probes
(270 passed, 17 expected rejections), including the imported damage trigger
probe described above. Its report is
`tools/paper-integration/reports/paper-1.21.1-damaged-import-20261102.json`.
