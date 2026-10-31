# MythicMobs Parity Matrix

This matrix records the current compatibility boundary. A passing unit test does
not prove Paper runtime parity.

Status values:

- `implemented-runtime`: runtime path exists and focused tests cover it.
- `mapped-unverified`: configuration/import mapping exists, but Paper runtime behavior is not verified.
- `unsupported-preserved`: the source value is retained with a migration warning.
- `missing`: no meaningful implementation exists.
- `blocked-by-external-plugin`: behavior requires a Mythic add-on or another external plugin.

Evidence baseline: 2026-10-21, Paper API 1.21.1-R0.1-SNAPSHOT.

Incremental condition-boundary update: `compare-values` now rejects `NaN`,
`Infinity`, and `-Infinity` for DOUBLE/FLOAT/NUMBER comparisons instead of
letting `Double.compare` treat them as ordered values. The new regression test
passes alongside the full `SkillConditionsTest`; this improves fail-closed
coverage but does not establish Paper or complete-catalog parity.

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

Migration compatibility update: common Mythic mechanic mappings now preserve
positional arguments as well as keyed arguments for item/equipment, player
inventory/experience/oxygen, block, entity-state, movement, explosion, potion,
animation, and audience mechanics. Importer regression coverage now exercises
both forms; keyed mappings remain unchanged.

The existing `take-item` runtime now also accepts official `RemoveItem` aliases
(`removeitem`/`remove-item`), preserving the same target-aware inventory
semantics without introducing a second removal implementation.

Incremental trigger update: killer-side `on-kill` and `on-kill-player` now
resolve managed mob killers from the damage event and unwrap projectile
shooters. `PlayerKills` now adds a lifetime counter per managed mob instance,
records only player victims on the killer-side path, and clears the counter at
instance teardown. Exploratory Paper 1.21.4 killprobe records the real-player
`on-kill-player` path and `PlayerKills` range checks as passed; strict Paper
1.21.1 evidence remains pending.

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

Incremental condition update: official `HasAuraStacks` now maps Bukkit potion
amplifier `0` to one stack, accepts aura/type aliases and numeric stack ranges,
and fails closed for missing effects. Migration, binder, unit, and condition
catalog self-test coverage are included. Exploratory Paper 1.21.4 fixture r8 and
strict Paper 1.21.1-133 fixture `srv-paper-integration-final-r8` record
`auraStacksCondition=passed`.

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
coordinates, and world isolation. Pin names and malformed/unknown shapes fail
closed because this adapter has no pin registry. Migration, binder, runtime,
and self-test coverage are included. Exploratory Paper 1.21.4 fixture
`srv-paper-integration-exploratory-1.21.4-r121` records
`bounds-condition=true`; strict Paper 1.21.1-133 fixture
`srv-paper-integration-final-r8` also records the bounds probe passed.

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
`itemRechargingCondition=passed` after a real Bukkit attack; strict Paper
1.21.1 evidence remains pending.

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
distance. Migration and runtime coverage are included; real client view
direction and Paper evidence remain pending.

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

Incremental view-condition update: official `LookingAt` now uses a target
Player's Bukkit block/entity ray trace with type and distance arguments.
Exploratory Paper 1.21.4 fixture r2 records `lookingAtCondition=passed` after
setting and restoring a real target block and player rotation; strict Paper
1.21.1-133 fixture `srv-paper-integration-final-r8` also records
`lookingAtCondition=passed`. Natural client-originated view direction remains
unverified.

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
phase lists `0..7`, and fails closed for invalid phases. Migration, runtime,
unit, and condition-catalog self-test coverage are included. Exploratory Paper
1.21.4 fixture r8 records `lunarPhaseCondition=passed`; strict Paper 1.21.1
evidence remains pending.

Incremental signal-condition update: official `LastSignal` now records the
most recent signal delivered to each managed instance, reads it through a
fail-closed condition, and clears it with instance teardown. Migration, unit,
and the existing live signal self-test cover named and default forms.
Exploratory Paper 1.21.4 fixture r3 records `signalMechanics=passed`; strict
Paper 1.21.1 evidence remains pending.

Incremental condition update: common environment and state aliases now include
`night`, `raining`, `thundering`, `falling`, `drowning`, `has-auras`, and
`target-within`; importer mappings and unit coverage are included, while the
complete Mythic condition catalog remains open.

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
`isInCinematic`/`inCinematic` aliases and migration coverage. Exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r107` verifies player-target
open/closed transitions through the combined dialogue probe. Strict Paper
1.21.1 evidence remains open.

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

The same target-only family now includes `targetHasPotionEffect`,
`targetHasAuras`, `targetHolding`, and `targetWearing`, with dedicated
canonical names, importer regression coverage, and exploratory Paper 1.21.11
r30 evidence through the aggregate target-state probe.

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
map to the registered `entity-type-is` evaluator, preserving named and
positional type arguments.

Official condition alias update: Mythic `Burning`, `Blocking`, `Charged`,
`Crouching`, movement-state aliases, `Chance`, `Day`, `Dawn`, `Dusk`, and
`Sunny` now map to the existing Bukkit/time/weather evaluators, with migration
regression coverage.

Food saturation parity update: Mythic `FoodSaturation`/`hungerSaturation` now
map to caster-context `food-saturation` (and explicit target context maps to
`target-food-saturation`) using Bukkit `HumanEntity#getSaturation`; migration,
unit, and Paper self-test coverage protect comparator behavior.

Exploratory Paper 1.21.11 r47 records `foodSaturationCondition=true` after
checking real saturation values at 1.0 and 2.0 and restoring the player's
original saturation.

Last-damage-cause parity update: official Mythic `LastDamageCause` now has a
separate entity-state evaluator reading `Entity#getLastDamageCause`, distinct
from the current event-context `damage-cause`; importer, unit, and exploratory
Paper self-test coverage protect both semantics.

Exploratory Paper 1.21.11 r48 records `lastDamageCauseCondition=true` after a
real managed Zombie stores a FIRE damage event and rejects ENTITY_ATTACK.

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

Inside/outside parity update: official `Inside` and `Outside` now inspect the
unified target/caster location for a non-air head block and open sky. Unit,
migration, and exploratory Paper r89 coverage include temporary roof placement
and restoration.

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
unit, and Paper self-test coverage include positive and full-inventory negative
states.

Exploratory Paper 1.21.11 r53 records `freeInventorySlotCondition=passed` for
the same live player: an empty storage slot passes, filling the storage slots
rejects the condition, and restoring the inventory returns the positive state.
This is compatibility evidence only; the Paper 1.21.1 strict release target
remains open.

Name condition parity update: official `Name`/`name{n=...}` now maps to
`name-is`. Runtime matching is exact and prefers `Entity#getCustomName()`;
when no custom name exists it falls back to Bukkit `Entity#getName()`, while
missing names and empty arguments fail closed.

Exploratory Paper 1.21.11 r54 records `nameCondition=passed` after a real
managed Zombie matches `NameProbe` and rejects its default `Zombie` name while
the custom name is active. This remains compatibility evidence only; the Paper
1.21.1 strict release target remains open.

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
same bound condition catalog as AI selectors; broader targeter parity remains
open.

Incremental state-mechanic update: common Mythic player/entity state actions now
include `set-sneaking`, `set-gliding`, `set-swimming`, `set-sprinting`,
`set-flying`, `set-no-damage-ticks`, `set-maximum-no-damage-ticks`, and
`set-walk-speed`, with targeter-bound variants, execution-time numeric
placeholders, importer aliases, and Bukkit unit coverage; live Paper timing and
the complete mechanic catalog remain unverified.

Exploratory Paper 1.21.11 r126 records `stateConditions=passed` for real
flying, sneaking, and gliding state after isolating the player from earlier
natural damage probes. `stateBlocking` remains unverified because Paper's
`Player#isBlocking` requires a client-driven Shield use window; mocked/API
coverage remains separate.

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
`TimeWeatherConditionTest` verifies registration and fail-closed behavior without
a context; the full Mythic condition catalog remains open.

Shear update: `on-shear` now also consumes `PlayerShearEntityEvent`, preserving
the player actor instead of relying only on `EntityTransformEvent`. The
exploratory event self-test now verifies the configured action on a real
managed Sheep; natural event timing remains unverified.

## 1. Skills

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Skill loading and metaskill references | implemented-runtime | `SkillService`, nested `cast-skill`, recursion guards, shared `SkillTreeContext` tests, and standalone Mythic `skill{s=...}` importer references |
| Delay, repeat, repeat interval, cast duration | implemented-runtime | deterministic scheduler and `SkillServiceTest`; strict Paper 1.21.1-133 `skillScheduling` runs `delay=2;repeat=3;repeat-interval=2` through the production `SkillService`/Bukkit scheduler, verifies zero calls before the first delay and exactly four executions after the repeat interval |
| TargetConditions and TriggerConditions | implemented-runtime | separate entity contexts and per-target filtering in `SkillService` |
| Variables and location variables | implemented-runtime | scoped variables, `variable-unset`, `VariableLocations`, `@VariableLocation` tests |
| Inline conditions | mapped-unverified | parser and repeat guards exist; full Mythic condition catalog is not covered |
| Context targeters | mapped-unverified | `@trigger`, `@attacker`, `@owner`, `@parent`, `@children`, `@passengers`, `@vehicle`, `@threat`, `@target`, `@targets`, `@VariableLocation`, direct `@EntitiesInRadius`/`@LivingEntitiesInRadius`, `@NearestPlayer`/`@NearestMonster`/`@NearestAnimal`/`@NearestVillager`/`@NearestTamed`, radius/filter/chain targeters, and category selectors `@MonstersInRadius`, `@AnimalsInRadius`, `@VillagersInRadius`, `@TamedInRadius` have runtime paths and focused API/importer tests; strict Paper 1.21.1-133 fixture `srv-paper-integration-final-r16` records real category, nearest-category, and nearest-player selections alongside the existing spatial targeter assertions, while complete Mythic targeter parameter parity and broader Paper-native selection evidence remain open |
| Audience actions | mapped-unverified | message/full title with subtitle and timing/actionbar/particle delivery plus dual-client Paper evidence for radius, permission, explicit faction include/exclude, and combined `radius{r=...;permission=...;faction=...}` filters; `!permission`/`-faction` exclusion syntax now has parser/unit coverage, while broader Paper/client combinations remain unverified |
| Mechanics catalog | mapped-unverified | common actions including caster `remove`, material/slot/amount `equip`/`set-item`/`set-offhand`/`set-armor`, player `clear-inventory`/`set-material-cooldown`/`set-game-mode`, `jump`/`force-pull`, main/off-hand `swing`, entity state `set-ai`/`set-gravity`/`set-silent`/`set-collidable`/`set-sneaking`/`set-gliding`/`set-swimming`, player state `set-sprinting`/`set-flying`/`set-walk-speed`, no-damage-tick controls, caster `set-fire`/`clear-fire`, caster/target orientation `set-rotation`/`look-at` (`setrotation`/`lookat` importer aliases), execution-time numeric placeholders in damage/heal/health/movement mechanics, caster `pull`/`leap`/`set-tick-speed` and caster movement `dash`/`blink`, multi-target `damage`/`damage-percent`/`message`/`explode`, player-target `give-item`/`take-item` (`giveitem`/`consume` importer aliases), target/caster `set-block`/`break-block` (`setblock`/`breakblock` importer aliases), caster-location `drop-item`, potion duration/amplifier/flags, caster-target `mount`, caster `dismount`, caster/location `teleport` aliases, event-aware `cancel-event` for damage/interact triggers, colored visible/hidden `set-name`, and the plugin custom item resolver are registered and importer-tested; isolated Paper self-tests now include real entity state/fire/orientation, block set/break, and online-player inventory give/take probes alongside teleport dispatch, configured custom ItemStack resolution, `dismount` against a real Boat, and `is-cancelled` after `cancel-event` in the same damage-event nested-skill chain; Mythic entity `disguise`/`removedisguise` are explicitly preserved as unsupported because model appearance is not equivalent; broader mechanic/event parity remains unverified |
| Conditions catalog | mapped-unverified | high-frequency subset now includes world/biome/time/weather, variable, target, permission, entity type, direct entity category aliases (`isLiving`/`isPlayer`/`isMob`/`isMonster`/`isAnimal`/`isVillager`/`isTameable`/`isRaider`), undead/creature and age aliases (`isUndead`/`isCreature`/`isBaby`/`isAdult`/`isChild`→`is-baby`; the separate `is-child` name is a parent relation), tame/shear/saddle/powered/collidable/offhand state aliases (`isTamed`/`isSheared`/`isSaddled`/`isPowered`/`isCollidable`/`hasOffhand`), caster/target lifecycle state aliases (`has-ai`/`has-gravity`/`is-silent`/`is-dead`/`is-alive`/`target-is-alive`/`is-valid`), caster environmental aliases (`on-ground`, `in-water`, `in-lava`, `in-air`, `is-blocked`, `stand-on`, `daylight`, `facing-target`, `light-level-below/above`), exact farmland hydration aliases (`moist`/`ismoist` and `moisture-level`/`moistureness`/`moistness` with `l` ranges), size aliases (`mob-size`/`mobsize`/`size`) for Slime/MagmaCube/Phantom, exact block counts (`block-type-in-radius`) with material/radius/amount ranges, target-prefixed aliases and importer mappings (`target-is-undead`, `target-is-creature`, `target-is-baby`, `target-is-adult`, `target-is-tamed`, `target-is-sheared`, `target-is-saddled`, `target-is-powered`, `target-is-collidable`, `target-has-offhand`, `target-has-ai`, `target-has-gravity`, `target-is-silent`, `target-is-valid`, `target-is-sneaking`, `target-is-sprinting`, `target-is-flying`, `target-is-blocking`, `target-is-gliding`, `target-is-swimming`, `target-on-fire`, `target-in-air`, `target-in-water`, `target-in-lava`), target categories (`mob`/`monster`/`animal`/`villager`/`tameable`/`raider`), stance, radius, line-of-sight, fire, game-mode, `food-level`, `has-item`, `air-level`, `damage-cause`, `damage-amount`, potion, invulnerability, `player-kills`, common no-separator aliases, keyed caster/target health and distance comparisons, target raw-health/percent comparators, player state aliases (`is-flying`, `is-sneaking`, `is-gliding`), and nested `all`/`any`/`not`; variable checks include typed string/list equality and contains, numeric List index `gte/lte/in-range`, Map value numeric `gte/lte/in-range` and negative range checks, positive/negative Map key and value checks, scope isolation for caster/target/world/global, and unset fail-closed coverage; time supports named windows, wrapping ranges and comparators, and weather accepts thunder aliases; fixtures `srv-paper-integration-condition-states-20261016` and `srv-paper-integration-entity-states-20261016` verify real online-player and real entity state conditions, including tame/sheared/saddle/powered, with cleanup; `srv-paper-integration-food-level-20261016` verifies real online-player food levels 14/15 against `food-level <= 14` and restores the original value; `srv-paper-integration-has-item-20261016-r2` verifies a real online player with a temporary Shield and the positive/negative `has-item SHIELD >=1` result, restoring the original item; `srv-paper-integration-air-level-20261016` verifies real online-player remaining air values 100/101 against `air-level <= 100` and restores the original value; `srv-paper-integration-damage-cause-20261016` verifies `damage-cause ENTITY_ATTACK` on the same real Bukkit damage-event context used by `on-damage`; `damage-amount` now has event-context unit/migration coverage and exploratory r84 final-damage evidence; `is-blocking` has API/unit coverage, but exploratory r43 still reports false after retaining a Shield through evaluation and receiving repeated client `use_item` packets, so real shield-use evidence remains a client protocol/timing gap; full Mythic placeholders, comparator edge cases, and the complete catalog remain unverified |
| Trigger catalog | mapped-unverified | `on-damage` and `on-attacked` dispatch from entity damage events, with a separate environmental path; `on-interact` now listens to the Bukkit entity-interaction parent event so ordinary and `AtEntity` clicks share one path; killer-side `on-kill` and `on-kill-player` now resolve managed mob killers from the death damage event and unwrap projectile shooters, and exploratory Paper fixture `srv-paper-integration-exploratory-1.21.4-r27` passes the real-death `kill-trigger`; Paper 1.21.4 killprobe now passes the deterministic real-player death and `PlayerKills` range assertions; Mythic `onConsume`/`onEat` aliases now normalize to `on-consume` and dispatch from Bukkit `PlayerItemConsumeEvent`; `on-teleport` has real Paper evidence in `srv-paper-integration-teleport-trigger`; bound-player block break/place and `on-item-pickup` have real Mineflayer evidence in `srv-paper-integration-item-pickup`; `on-vehicle-enter`/`on-vehicle-exit`/`on-vehicle-destroy` and `dismount` have real Paper Boat evidence in `srv-paper-integration-vehicle-fix-20261015`; `cancel-event` followed by nested `is-cancelled` now has real Paper evidence in `srv-paper-integration-cancelled-20261016-r5`; `on-potion-effect-end` has a registered listener and mapping tests, natural managed-entity expiry passes in exploratory r27, and exploratory r33 verifies mechanic-driven removal dispatch, while Paper 1.21.1 release timing remains unverified; exploratory r136/r137 now provide stable real-client drinking and natural expiry evidence; clean delayed native-target fixture `srv-paper-integration-target-reason-clean-20261016` proves `target-events=1`, `target-find-misses=0`, `target-reason=CLOSEST_PLAYER`, `target-cancelled=false`, and delayed `entity-target-trigger=true`; `srv-paper-integration-environmental-event-20261015` proves a direct no-attacker `EntityDamageEvent` dispatch, while autonomous lava-tick timing and several other natural-event paths remain unverified |

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

Exploratory Paper 1.21.11 r142 records the real tempt selector and delayed
native target route: `tempt=passed`, `entityTargetBukkit=passed`, and
`entityTargetDelayed=passed`, with a Survival player holding WHEAT and Paper
`target-reason=TEMPT`.

## 2. Mob Options

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Universal options | mapped-unverified | `SpawnOptions` and entity application cover many fields; reason/cause options accept both YAML lists and Mythic delimited scalar values; strict fixtures r36/r37/r41 verify vanilla and killer-owned drop prevention, including custom victim loot suppression; r42/r43 verify Silverfish block infection and Snow Golem snow formation guards; r44 verifies family-aware `PreventConversion`; r47 verifies Enderman `HeldBlock` maps into `entity.held-block` and production `setCarriedBlock` reads OBSIDIAN back; `PreventSunburn` strict fixture verifies sunlight-only cancellation; event probes cover leash/rename/target/projectile/explosion/drowning/fall/fire/pickup/item-use/teleport/mount/transformation policies and cause allowlists; r38 spawn-option runtime probe verifies pickup precedence, random gear clearing while retaining explicit equipment, and `NoDamageTicks`; broader natural event ordering remains unverified |
| Interactable | mapped-unverified | Mythic `Options.Interactable` maps to `mob.interactable`, applying the documented Mythic default `false` on imported configs; managed entity and Armor Stand manipulation events are denied when false, while `PreventItemUse` continues to deny item interactions independently. Strict Paper 1.21.1-133 fixture `srv-paper-integration-armor-stand-20261031` dispatches real-player interaction and Armor Stand manipulation events against protected/control pairs, recording `interactable=true interactable-armor-stand=true`; broader client timing remains open |
| Age, Baby, Adult, AgeLock | mapped-unverified | `Ageable` setter path and proxy/importer tests now include explicit `Adult`; strict Paper 1.21.1-133 fixture `srv-paper-integration-patrol-spawn-20261031` applies `age=-24000`, `baby=true`, `adult=true`, and `age-lock=true` through the production path on real Sheep and records `age-options result=true`; non-Ageable entity-specific baby APIs remain separate |
| Angry, Tamed, Raider flags | mapped-unverified | Wolf/PigZombie/Raider/Tameable setters and tests; strict Paper 1.21.1-133 fixture `srv-paper-integration-patrol-spawn-20261031` applies and reads back real Wolf `Tamed`/`Angry` and Pillager `PatrolLeader`/`PatrolSpawnPoint`, while `CanJoinRaid` is verified as a successful production setter call because Paper 1.21.1 exposes no getter |
| Size, Slime split | mapped-unverified | fixed Mythic `Options.Size` and inclusive positive integer ranges now migrate to fixed `entity.size` or `entity.size-min`/`size-max`; Paper applies the range once at spawn to Slime/MagmaCube/Phantom. `PreventSlimeSplit` maps to the production `SlimeSplitEvent` cancellation path, with strict Paper 1.21.1-133 `mobOptionsSlimeSplit` verifying protected cancellation and control allowance; end-to-end natural split-size distribution remains open |
| Creeper options | mapped-unverified | explosion radius, fuse, powered setters, and Creeper-only `PreventSuicide` event cancellation are implemented; strict Paper 1.21.1-133 records the natural fuse probe as passed; broader option ordering remains open |
| Bee options | mapped-unverified | nectar/stung setters; strict Paper 1.21.1-133 runs protected/control Bees through real attack ticks and records stinger restoration as passed; broader option ordering remains open |
| Zombie/Skeleton conversion and jockey mounts | mapped-unverified | Mythic `PreventConversion` now maps by entity family: Zombie-family entities map DROWNED/CURED/INFECTION, Skeleton maps FROZEN, and unsupported families remain visible for manual handling; unit/importer tests validate enum reasons and unsupported preservation; strict r44 verifies a Skeleton FROZEN event is cancelled while CURED remains allowed, alongside protected/control Zombie conversion; natural AI jockey selection remains unverified |
| Remaining entity-specific options | mapped-unverified | Enderman `HeldBlock` migrates to `entity.held-block` and is applied as Bukkit BlockData; strict r47 confirms carried block reads back as OBSIDIAN. Horse, ArmorStand, Cat, Sheep, Wolf/Tameable, Raider, Snow Golem and FallingBlock paths exist; strict spawn-option evidence proves pickup precedence, random gear clearing while retaining explicit gear, and configured `NoDamageTicks`; r42/r43 prove Silverfish/Snow-Golem guards; full mob-config spawn verification remains incomplete |

Mythic `Options.Size` migration now accepts a positive integer range such as
`2-5` or `2to5`, emitting `entity.size-min`/`entity.size-max`. The spawn-time
adapter samples one inclusive value and applies it to Slime/MagmaCube or
Phantom. Invalid, reversed, or non-positive ranges remain preserved with a
warning instead of silently becoming size 1.

## 3. Items, Equipment, Attributes

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Equipment slots, amount, chance | implemented-runtime | importer fixtures, `EntityEquipment` adapter tests, and SkillExtras `equip/set-item` actions |
| Inline names, lore, enchants, potions, skulls | mapped-unverified | importer, namespaced vanilla material and multi-enchantment `material:amount:enchantment:level` parsing, ItemMeta paths, plugin custom-item resolver integration, and isolated Paper `custom-item=true` evidence for a configured GOLD_INGOT ItemStack; client/server rendering and full mechanic dispatch remain unverified |
| Legacy numeric IDs and data | mapped-unverified | common tools, armor, stable item IDs through 452, `minecraft:` namespaced vanilla material normalization, music discs `2256–2267`, numeric `ID:DATA` shorthand, common legacy spawn eggs (`383:DATA`), known potion/tipped/splash/lingering encodings with ordinary/long/strong `PotionType` output, classic water/lava and piston IDs, base blocks, colored Shulker/Glazed Terracotta blocks, colors, flowers, leaves (including decay bits), fish, banners, infested blocks, quartz, prismarine, logs, stairs, slabs, double plants and durability-bearing fishing rods/shears/shields/elytra are covered; unknown spawn-egg ids and unknown/invalid potion data remain explicit under `unsupported.legacy-id/data`, and the legacy table is still incomplete |
| Item Options and PDC identity | implemented-runtime | ItemMeta/PDC proxy tests; anvil and inventory behavior unverified. MythicMobs `ItemModel` and `DamageType` remain under `unsupported.options`: Paper 1.21.1 `ItemMeta` exposes no public component API for either value, so conversion requires a future version-gated adapter |
| Entity and item attributes | mapped-unverified | alias/slot/operation normalization and stable UUID deduplication exist; fixture `srv-paper-integration-item-attributes-50ba050d42e7442db9a58584ae06270e` proves Paper entity base 3.0 -> value 3.3 with one `ADD_SCALAR` modifier and one ItemMeta modifier, while full attribute catalog and client coverage remain unverified |
| Drops and DropTables | mapped-unverified | amount ranges, weighted tiers, luck scaling, nested table expansion with cycle protection, entry conditions, table/mob experience ranges, explicit dynamic-chance evaluator hooks, and isolated Paper LootService plus EntityDeathEvent item/experience evidence are implemented; `MythicDropSpec` and `MythicDropTableProjection` are wired into importer list Drops, safe entries generate `loot.yml` tables and mob references, unsafe/external entries remain unsupported, r36/r37/r41 verify native drop filtering, killer-owned filtering, and custom-table suppression before optional loot materialization, and MigrationServiceImpl merges same-target YAML entries; full Mythic placeholder expressions and broader natural death/drop parity remain incomplete |
| External drop types | blocked-by-external-plugin | Vault, MythicCrucible, MMOItems and other integrations require their plugins |

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
| Full Mythic condition/filter grammar | mapped-unverified | nested `all`/`any`/`not`, unary `!`, grouped parentheses, and top-level `&&`/`||` composition with `||` precedence now have runtime tests; caster/target permission conditions and entity-state placeholders now cover common Bukkit flags including age, AI, gravity, silent, validity, powered, movement and player state, with Paper `caster-permission=true` evidence from an online trigger context; unsupported condition names, remaining placeholders, and operators remain |
| Full SkillTree semantics | mapped-unverified | isolated two-boot Paper 1.21.11 exploratory fixture `srv-paper-skilltree-restart-034f7c7c184b48a9b2c57db5453effdb` proves force-loaded persistent-chunk loading, stable PDC mob reattachment, and SkillTree marker restoration; the focused Paper 1.21.1 restart fixture also restores branch requirements/unlocked state and continues by unlocking the dependent branch. Strict Paper 1.21.1-133 `skillTreeBranches` verifies condition-denied/condition-allowed `branch-unlock` and prerequisite-blocked/unlocked transitions, while `skillTreeCastPersistence` verifies a named skill is blocked before prerequisites, unlocks branches in order, snapshots per-instance state, and then executes the gated skill from a fresh context. Branch metadata is now predeclared from loaded skill definitions and branch condition evaluators are service-local. Death cleanup releases retained chunks; complete Mythic SkillTree semantics and broader reload/client parity remain open |

## 6. Spawner, Random Spawn, Custom Menu

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Spawner parsing, cooldown, max-alive, persistence | mapped-unverified | `SpawnerServiceTest`, state file and diagnostic APIs; Mythic `cooldown` now aliases `interval`; `max-per-world`/`max-per-player` plus `per-world-limit`/`per-player-limit` aliases enforce lifetime scoped quotas, scoped counters survive state round trips, and config reload retains alive-instance tracking; shared scheduler cooldowns now advance by real elapsed server ticks instead of multiplying the configured interval by the scheduler period; strict fixture `srv-paper-integration-armor-stand-20261031` verifies live scheduler timing (`spawnerNaturalTiming`), failed-spawn cooldown preservation, scoped quotas, and max-spawns; broader restart/natural lifecycle semantics remain open |
| Random weighted mob pools | mapped-unverified | `RandomSpawnPoolTest` plus strict fixture `srv-paper-integration-armor-stand-20261031`, which records 100 production samples near the expected 0.5/0.5 distribution with max deviation 0.02; broader sample sizes and drop-table coupling remain unverified |
| Spawner conditions and regions | mapped-unverified | condition/region hooks and composite `all{}` evaluation exist; scalar/list condition parsing preserves range and coordinate commas; strict fixture `srv-paper-integration-armor-stand-20261031` proves passing/rejected weather, time, biome, composite, world/Y, and player/world gate combinations with fail-closed results; broader randomized combinations remain unverified |
| Heightmap, ground, border and player gates | mapped-unverified | strict Paper 1.21.1-133 fixture `srv-paper-integration-armor-stand-20261031` proves natural ground spawn, heightmap surface selection, border clipping/rejection, closed player-gate rejection, player-scoped limit rejection, unloaded-chunk rejection, unreadable-light rejection, and `max-spawns=1`; `player-distance` is accepted as a runtime alias for `players-radius`, while broader randomized terrain combinations remain unverified |
| Custom menu pagination and visibility | mapped-unverified | parser/pagination tests plus an isolated Mineflayer + Paper workflow proving permission denial, page navigation, command dispatch and close behavior; player navigation history now clears on `PlayerQuitEvent`, and button/pagination visibility now supports separated positive clauses plus `!`/`-` exclusions for permission/world/faction/region, while broader Mythic menu conditions and client versions remain unverified |
| Mythic Random Spawns and DropTables | mapped-unverified | weighted random pools and nested loot expansion exist; Spawner conditions now include vanilla lunar phase aliases with unit coverage, and Paper fixture `srv-paper-integration-lunar` verifies `lunar-phase-is=FULL` accepts a real spawn request; full condition combinations, experience and broader Paper spawn/drop semantics remain incomplete |

## 7. Migration and Verification

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Mob Options mapping and preservation | mapped-unverified | Broad mapping with unsupported preservation; fixed Mythic `Options.Size` maps to `entity.size`, positive inclusive ranges (`2-5`/`2to5`) map to `entity.size-min/max` and are sampled once during Slime/MagmaCube/Phantom option application, while invalid/reversed ranges remain visible with migration warnings. `ImportersTest` covers these projections and strict Paper 1.21.1-133 `entitySizeRange` samples the production Slime path 64 times within 2..5; the complete Mythic Mob Options catalog remains unverified |
| Equipment/Items/Attributes mapping | mapped-unverified | importer and runtime tests cover equipment, item metadata, and mob drop-experience field preservation/consumption; complete Mythic catalog not covered |
| Skills importer | mapped-unverified | common mechanics normalized; standalone `skill{s=...}` references now become ordered `cast-skill` actions; `MigrationRuntimeConditionContractTest` binds importer-asserted canonical conditions and mechanics through `SkillService`; unsupported mechanics remain preserved |
| Spawner importer | mapped-unverified | core fields and random pools mapped; full Mythic spawner semantics incomplete |
| Unsupported report schema | implemented-runtime | `status`, `severity`, source/target/value assertions in importer tests |
| Paper integration suite | mapped-unverified | strict Paper 1.21.1-133 fixture `srv-paper-integration-patrol-spawn-20261031` completes the registered report contract with 230 observed probes (219 passed plus 11 expected negative controls); the run includes dual-client menu/Audience reports, condition utility aggregate, player-kill, Structure, LookingAt, target-fluid, Mob Options including PatrolSpawnPoint, jockey mount, Pathfinder-clear, AI route waypoint, and Interactable/Armor Stand event evidence. The suite still does not establish the complete Mythic catalog or broader natural/client semantics, so this row remains mapped-unverified |

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

The latest strict Paper 1.21.1-133 fixture `srv-paper-integration-patrol-spawn-20261031`
passed with 230 observed probes: 219 `passed`, 11 expected
`observed-rejected`, and zero incomplete. Its `entityFlagOptions` probe reads
back `patrol-spawn-point=true` on a real Pillager at its configured spawn
block; the same run retains the Interactable/Armor Stand event and
`aiRoute=passed` evidence.

Latest Paper evidence: `srv-paper-integration-vehicle-fix-20261015` reconfirmed
the implemented entity-option, condition, item, loot, spawner, and Audience
probes and recorded real player-driven `VehicleDestroyEvent` handling with
`enter=true exit=true dismount=true destroy=true`. A separate fixture also
verified the real no-attacker `EntityDamageEvent` path with
`environmental-damage trigger=true`; autonomous lava-tick timing remains
unverified, so the trigger row remains `mapped-unverified`.

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
target. This is not evidence for Mythic's age `isChild` condition; migration
maps that condition to Helstera's `is-baby`. `conditionIsChild`,
`parentRelation`, and aggregate `conditionCatalog` are reported as passed.

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
passing `conditionUtilityAliases` probe.

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

Official block/biome condition update: exact `BlockType` and `BiomeType` list
forms now map to active Bukkit block/biome keys. Wildcards, block tags, MMOItem
names, and `BiomeType exact=false` category semantics remain explicitly
unmapped. Migration/runtime tests cover exact forms, and exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r66` records
`block-type=true biome-type=true` with the existing spatial condition passes.

Server/player state condition update: official `ServerIsPaper` now recognizes
Paper-family server identity from Bukkit name/version metadata, while
`IsInSurvivalMode` checks only an explicit target Player in survival mode.
Exploratory Paper `srv-paper-integration-exploratory-1.21.11-r67` keeps the
aggregate `conditionUtilityAliases` probe passed; strict release evidence
remains open.

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

Spyglass state condition update: official `IsUsingSpyglass` now reads only an
explicit target Player's active Bukkit item and requires `Material.SPYGLASS`.
Runtime/migration tests protect the target-only and fail-closed behavior;
exploratory Paper `srv-paper-integration-exploratory-1.21.11-r76` records
`spyglassCondition=passed` for active-item positive/negative transitions.

Ender Dragon condition update: official `EnderDragonAlive` now scans the
target world's valid `EnderDragon` entities; exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r77` verifies a real temporary
dragon. `PlayerKills` is now implemented as a managed-instance lifetime
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
phase before removing the temporary dragon.

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

Entity-category condition update: official `IsWaterMob` and `IsZombie` now use
the Paper API's `WaterMob` marker and `Zombie` type checks, with direct aliases
and migration coverage. Exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r99` verifies a real Cod/Zombie pair,
including cross-type negative controls through
`water-mob-condition=true zombie-condition=true`. This remains exploratory
Paper 1.21.11 evidence; strict Paper 1.21.1 evidence is still open.

Aerial condition update: official `Aerial` now evaluates the active target's
Bukkit `isOnGround()` state and supports `aerial`/`isAerial` migration aliases.
Exploratory Paper `srv-paper-integration-exploratory-1.21.11-r102` verifies a
floating ArmorStand positive and a grounded online-player negative through
`aerial-condition=true`. Strict Paper 1.21.1 evidence remains open.

Enderman condition update: official `IsEnderman` now uses Bukkit's
`Enderman` type with `isEnderman`/`enderman` aliases and migration coverage.
Exploratory Paper `srv-paper-integration-exploratory-1.21.11-r103` verifies a
real Enderman positive and managed Zombie negative through
`enderman-condition=true`. Strict Paper 1.21.1 evidence remains open.

Enchantment condition update: official `HasEnchantment` now checks Bukkit
main-hand, off-hand, and armor equipment with `enchantment/type/e` selection,
`ANY`, and Mythic numeric level ranges. Unknown enchantments and unavailable
registry state fail closed; exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r104` verifies Sharpness/ANY positive
paths plus level/type negatives through `enchantment-condition=true`. Strict
Paper 1.21.1 evidence remains open.

Color condition update: official `Color` now matches Bukkit dye colors and
entity variants for Sheep/Shulker, Cat, Parrot, Horse, and Llama targets, with
`color`/`clr` migration aliases. Exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r108` verifies red Sheep positive,
wrong-color negative, and unsupported Zombie negative paths through
`color-condition=true`. Strict Paper 1.21.1 evidence remains open.

Material cooldown condition update: official `MaterialIsOnCooldown` now reads
target-player Bukkit material cooldowns, defaults to `ENDER_PEARL`, and accepts
`material/m` aliases. `ItemGroupOnCooldown` remains separate because its custom
item-group state has no Bukkit equivalent. Exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r110` verifies positive, wrong-item,
and cleared negative paths through `material-cooldown-condition=true`; both
aggregate condition probes pass after correcting self-test ordering. Strict
Paper 1.21.1 evidence remains open.

Trigger item condition update: official `TriggerItemType` now reads a Bukkit
Item entity from `BehaviorContext.trigger()` and matches comma-separated
material names with `types/type/material/m` aliases. Exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r113` verifies STONE/DIRT positive
and DIAMOND negative trigger paths through `trigger-item-type-condition=true`.
The same fixture maps `TriggerBlockType` to the origin block for block trigger
states and verifies positive, material-negative, and wrong-state paths through
`trigger-block-type-condition=true`. Strict Paper 1.21.1 evidence remains
open.

Coordinate difference update: official `XDiff`, `YDiff`, and `ZDiff` now
compare target-minus-caster Bukkit coordinates with Mythic numeric ranges and
`difference/diff/d` aliases. Exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r114` verifies positive X/Y/Z ranges
and an out-of-range X rejection through
`coordinate-difference-condition=true`. Strict Paper 1.21.1 evidence remains
open.

Origin-location update: official `OriginLocation` now matches
`BehaviorContext.origin()` by block or exact coordinates with `loc/l/c`,
`x/y/z`, and `exact/e` aliases. Exploratory Paper
`srv-paper-integration-exploratory-1.21.11-r116` verifies block, exact, and
mismatch paths through `origin-location-condition=true`. Strict Paper 1.21.1
evidence remains open; the `loc=` migration path also passed after preserving
comma-separated coordinates.

Dialogue condition update: official `HasDialogOpen` now reads the active
DialogueService runner for the model instance, with `hasDialogOpen`/
`dialogOpen` aliases and migration coverage. Dialogue hooks are injected after
the service is created, fixing the prior initialization-order gap. Exploratory
Paper `srv-paper-integration-exploratory-1.21.11-r106` verifies start/open/stop/
closed transitions through `dialogue-condition=true`; strict Paper 1.21.1
evidence remains open.
