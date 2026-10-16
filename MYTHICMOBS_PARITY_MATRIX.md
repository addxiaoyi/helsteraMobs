# MythicMobs Parity Matrix

This matrix records the current compatibility boundary. A passing unit test does
not prove Paper runtime parity.

Status values:

- `implemented-runtime`: runtime path exists and focused tests cover it.
- `mapped-unverified`: configuration/import mapping exists, but Paper runtime behavior is not verified.
- `unsupported-preserved`: the source value is retained with a migration warning.
- `missing`: no meaningful implementation exists.
- `blocked-by-external-plugin`: behavior requires a Mythic add-on or another external plugin.

Evidence baseline: 2026-10-14, Paper API 1.21.1-R0.1-SNAPSHOT.

## 1. Skills

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Skill loading and metaskill references | implemented-runtime | `SkillService`, nested `cast-skill`, recursion guards, shared `SkillTreeContext` tests |
| Delay, repeat, repeat interval, cast duration | implemented-runtime | deterministic scheduler and `SkillServiceTest`; Paper scheduling still unverified |
| TargetConditions and TriggerConditions | implemented-runtime | separate entity contexts and per-target filtering in `SkillService` |
| Variables and location variables | implemented-runtime | scoped variables, `variable-unset`, `VariableLocations`, `@VariableLocation` tests |
| Inline conditions | mapped-unverified | parser and repeat guards exist; full Mythic condition catalog is not covered |
| Context targeters | mapped-unverified | `@trigger`, `@attacker`, `@owner`, `@threat`, `@target`, `@targets`, `@VariableLocation`, radius/filter/chain targeters have runtime paths and focused API tests; Paper fixtures verify `@owner`, `@attacker`, threat ordering, and `srv-paper-integration-spatial-targeters-20261016` verifies real `sphere`, `ring`, `cone`, and `line-of-sight` selections; complete Mythic targeter parameter parity and broader Paper-native selection evidence remain open |
| Audience actions | mapped-unverified | message/title/actionbar/particle delivery plus dual-client Paper evidence for radius, permission, and explicit faction include/exclude; broader audience combinations remain unverified |
| Mechanics catalog | mapped-unverified | common actions including caster `remove`, material/slot/amount `equip`/`set-item`, caster-location `drop-item`, potion duration/amplifier/flags, caster-target `mount`, caster `dismount`, caster/location `teleport` aliases, event-aware `cancel-event` for damage/interact triggers, colored visible/hidden `set-name`, and the plugin custom item resolver are registered and importer-tested; isolated Paper self-tests prove real teleport dispatch, configured custom ItemStack resolution, `dismount` against a real Boat, and `is-cancelled` after `cancel-event` in the same damage-event nested-skill chain; Mythic entity `disguise`/`removedisguise` are explicitly preserved as unsupported because model appearance is not equivalent; broader mechanic/event parity remains unverified |
| Conditions catalog | mapped-unverified | high-frequency subset now includes world/biome/time/weather, variable, target, permission, entity type, direct entity category aliases (`isLiving`/`isPlayer`/`isMob`/`isMonster`/`isAnimal`/`isVillager`/`isTameable`/`isRaider`), undead/creature and age aliases (`isUndead`/`isCreature`/`isBaby`/`isAdult`/`isChild`), tame/shear/saddle/powered state aliases (`isTamed`/`isSheared`/`isSaddled`/`isPowered`), target-prefixed aliases and importer mappings (`target-is-undead`, `target-is-creature`, `target-is-baby`, `target-is-adult`, `target-is-tamed`, `target-is-sheared`, `target-is-saddled`, `target-is-powered`, `target-is-sneaking`, `target-is-sprinting`, `target-is-flying`, `target-is-blocking`, `target-is-gliding`, `target-is-swimming`, `target-on-fire`, `target-in-air`, `target-in-water`, `target-in-lava`), target categories (`mob`/`monster`/`animal`/`villager`/`tameable`/`raider`), stance, radius, line-of-sight, fire, game-mode, `food-level`, `has-item`, `air-level`, `damage-cause`, potion, invulnerability, common no-separator aliases, keyed caster/target health and distance comparisons, target raw-health/percent comparators, player state aliases (`is-flying`, `is-sneaking`, `is-gliding`), and nested `all`/`any`/`not`; variable checks include typed string/list equality and contains, numeric List index `gte/lte/in-range`, Map value numeric `gte/lte/in-range` and negative range checks, positive/negative Map key and value checks, scope isolation for caster/target/world/global, and unset fail-closed coverage; time supports named windows, wrapping ranges and comparators, and weather accepts thunder aliases; fixtures `srv-paper-integration-condition-states-20261016` and `srv-paper-integration-entity-states-20261016` verify real online-player and real entity state conditions, including tame/sheared/saddle/powered, with cleanup; `srv-paper-integration-food-level-20261016` verifies real online-player food levels 14/15 against `food-level <= 14` and restores the original value; `srv-paper-integration-has-item-20261016-r2` verifies a real online player with a temporary Shield and the positive/negative `has-item SHIELD >=1` result, restoring the original item; `srv-paper-integration-air-level-20261016` verifies real online-player remaining air values 100/101 against `air-level <= 100` and restores the original value; `srv-paper-integration-damage-cause-20261016` verifies `damage-cause ENTITY_ATTACK` on the same real Bukkit damage-event context used by `on-damage`; `is-blocking` has API/unit coverage but its real shield-use Paper probe remains false even with Survival mode, preloaded shield, and repeated client `use_item` packets; full Mythic placeholders, comparator edge cases, and the complete catalog remain unverified |
| Trigger catalog | mapped-unverified | `on-damage` and `on-attacked` dispatch from entity damage events, with a separate environmental path; `on-teleport` has real Paper evidence in `srv-paper-integration-teleport-trigger`; bound-player block break/place and `on-item-pickup` have real Mineflayer evidence in `srv-paper-integration-item-pickup`; `on-vehicle-enter`/`on-vehicle-exit`/`on-vehicle-destroy` and `dismount` have real Paper Boat evidence in `srv-paper-integration-vehicle-fix-20261015`; `cancel-event` followed by nested `is-cancelled` now has real Paper evidence in `srv-paper-integration-cancelled-20261016-r5`; `on-potion-effect-end` has a registered listener and mapping tests, the Zombie probe `srv-paper-integration-potion-trigger-20261016-r4` recorded `potion-events=0`, while the real online-player API probe `srv-paper-integration-player-potion-20261016-r2` recorded `player-potion-events=true`; real drinking and natural expiry remain unverified; clean delayed native-target fixture `srv-paper-integration-target-reason-clean-20261016` proves `target-events=1`, `target-find-misses=0`, `target-reason=CLOSEST_PLAYER`, `target-cancelled=false`, and delayed `entity-target-trigger=true`; environmental damage remains unobserved; several triggers are derived or still lack natural-event Paper evidence |

## 2. Mob Options

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Universal options | mapped-unverified | `SpawnOptions` and entity application cover many fields; live event order is unverified |
| Age, Baby, AgeLock | mapped-unverified | `Ageable` setter path and proxy tests |
| Angry, Tamed, Raider flags | mapped-unverified | Wolf/PigZombie/Raider/Tameable setters and tests |
| Size, Slime split | mapped-unverified | Slime/Phantom size and `SlimeSplitEvent` policy tests |
| Creeper options | mapped-unverified | explosion radius, fuse, powered setters, and Creeper-only `PreventSuicide` event cancellation are implemented; fixture `srv-paper-integration-events-final-5595adb29ae14e24b5b6485cc0507a56` verifies the specialized suicide-prevention policy on the Paper event bus, while natural fuse timing remains unverified |
| Bee options | mapped-unverified | nectar/stung setters; the same Paper fixture verifies configured Bee attack cancellation with the Bee as damager; stinger-loss restoration is scheduler based and unverified |
| Zombie conversion and jockey mounts | mapped-unverified | the same Paper fixture verifies configured transformation cancellation; natural conversion timing and Zombie-specific mount guard remain unverified |
| Remaining entity-specific options | mapped-unverified | Horse, ArmorStand, Cat, Sheep, Wolf/Tameable, Raider and FallingBlock paths exist; isolated Paper self-test now proves setter/read-back for Horse, Sheep, ArmorStand, Cat and FallingBlock, while event/order and full mob-config spawn verification remain incomplete |

## 3. Items, Equipment, Attributes

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Equipment slots, amount, chance | implemented-runtime | importer fixtures, `EntityEquipment` adapter tests, and SkillExtras `equip/set-item` actions |
| Inline names, lore, enchants, potions, skulls | mapped-unverified | importer, ItemMeta paths, plugin custom-item resolver integration, and isolated Paper `custom-item=true` evidence for a configured GOLD_INGOT ItemStack; client/server rendering and full mechanic dispatch remain unverified |
| Legacy numeric IDs and data | mapped-unverified | common tools, armor, blocks, colors, logs, stairs and slabs covered; table is incomplete |
| Item Options and PDC identity | implemented-runtime | ItemMeta/PDC proxy tests; anvil and inventory behavior unverified |
| Entity and item attributes | mapped-unverified | alias/slot/operation normalization and stable UUID deduplication exist; fixture `srv-paper-integration-item-attributes-50ba050d42e7442db9a58584ae06270e` proves Paper entity base 3.0 -> value 3.3 with one `ADD_SCALAR` modifier and one ItemMeta modifier, while full attribute catalog and client coverage remain unverified |
| Drops and DropTables | mapped-unverified | amount ranges, weighted tiers, luck scaling, nested table expansion with cycle protection, entry conditions, table/mob experience ranges, explicit dynamic-chance evaluator hooks, and isolated Paper LootService plus EntityDeathEvent item/experience evidence are implemented; `MythicDropSpec` and `MythicDropTableProjection` are wired into importer list Drops, safe entries generate `loot.yml` tables and mob references, unsafe/external entries remain unsupported, and MigrationServiceImpl merges same-target YAML entries; full Mythic placeholder expressions and broader death/drop parity remain incomplete |
| External drop types | blocked-by-external-plugin | Vault, MythicCrucible, MMOItems and other integrations require their plugins |

## 4. Custom AI

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Factions and threat targeting | mapped-unverified | `FactionService`, threat table and selector tests; fixture `srv-paper-integration-threat-faction-20261016` dispatches real `EntityDamageByEntityEvent` instances with 5 vs 20 damage, verifies `@threat` ordering, same-faction selection, and hostile-faction exclusion; native faction/threat composition beyond these filters remains open |
| Basic goals and target selectors | mapped-unverified | `AiProfile` supports a subset including `panic`, `follow_owner`, and `tempt <material>`; isolated Paper fixture `srv-paper-integration-tempt-native-target` proves an online player holding WHEAT is selected by a live PIG controller and Paper `Mob#getTarget()` returns that player; full Pathfinder equivalence remains unverified |
| Ranged/leap/owner/faction selectors | mapped-unverified | implemented subset; numeric priority sorting, ordered `clear`, read-only ordered selector views, and category priority lookup have unit coverage; isolated Paper fixture `srv-paper-integration-ai-priority` verifies opposite configured priorities select `ATTACK` versus `FLEE` at 50% health, while native Pathfinder goal equivalence remains unverified |
| Full AIGoalSelectors/AITargetSelectors | mapped-unverified | importer now accepts official `AITargetSelectors` alongside legacy `AIGoalTargets`; runtime supports a subset with priority/clear arbitration, while many Mythic selectors and native goal composition remain unimplemented |
| Pathfinding malus and native goal mutation | mapped-unverified | custom selector results synchronize to Paper `Mob#setTarget`; fixture `srv-paper-integration-path-stop-20261016` records real Paper `stop-calls=1` from the clear/idle path, while `srv-paper-integration-path-native-compare-20261016` shows both an unbound native Zombie and model-bound probe return `findPath=null`; no complete goal registry, malus adapter, or full post-clear path evidence exists |

## 5. Skill Trees, Conditions, Audience

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Shared skill-tree variables and locations | implemented-runtime | `SkillTreeContext`, nested skill tests, immutable snapshot/restore, and YAML snapshot-store round-trip coverage for primitive variables and locations |
| Audience parsing and delivery | mapped-unverified | isolated dual-client Paper workflow proves message/title/actionbar/particle delivery, radius include/exclude, permission include/exclude, and explicit faction include/exclude; broader combinations remain unverified |
| Full Mythic condition/filter grammar | mapped-unverified | nested `all`/`any`/`not`, unary `!`, grouped parentheses, and top-level `&&`/`||` composition with `||` precedence now have runtime tests; caster/target permission conditions and entity-state placeholders cover common Bukkit flags and game mode, with Paper `caster-permission=true` evidence from an online trigger context; unsupported condition names, remaining placeholders, and operators remain |
| Full SkillTree semantics | mapped-unverified | isolated two-boot Paper fixture proves stable PDC persistent mob reattachment and SkillTree marker restoration; branch/branch-requires gates and condition-aware `branch-unlock` now exist with tests, while production restart coverage and complete Mythic semantics remain unproven |

## 6. Spawner, Random Spawn, Custom Menu

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Spawner parsing, cooldown, max-alive, persistence | mapped-unverified | `SpawnerServiceTest`, state file and diagnostic APIs; Mythic `cooldown` now aliases `interval`; `max-per-world`/`max-per-player` plus `per-world-limit`/`per-player-limit` aliases enforce lifetime scoped quotas, and scoped counters now survive state round trips; Paper fixtures `srv-paper-integration-scoped-limits-3` and `srv-paper-integration-player-limits` verify world/player limits, while `srv-paper-integration-cooldown` verifies the live scheduler cooldown decreases between ticks; natural spawn timing remains unverified |
| Random weighted mob pools | mapped-unverified | `RandomSpawnPoolTest` plus isolated Paper evidence with 100 actual spawns observed `{emberling_boss=0.56, emberling=0.44}` against expected 0.5/0.5 and deviation +/-0.06; broader distributions and production conditions remain unverified |
| Spawner conditions and regions | mapped-unverified | condition/region hooks and composite `all{}` evaluation exist; isolated Paper fixtures prove passing/rejected world/Y and composite gates, `forceSpawn` success, and `max-spawns=1` rejection; broader random-location/ground/player gates and production combinations remain unverified |
| Heightmap, ground, border and player gates | mapped-unverified | isolated Paper fixtures prove natural ground spawn, border rejection, closed player-gate rejection, and `max-spawns=1`; `player-distance` is now accepted as a runtime alias for `players-radius`, while lunar phase, scoped limits, randomized terrain/border combinations and live-player acceptance remain unverified |
| Custom menu pagination and visibility | mapped-unverified | parser/pagination tests plus an isolated Mineflayer + Paper workflow proving permission denial, page navigation, command dispatch and close behavior; broader Mythic menu conditions and client versions remain unverified |
| Mythic Random Spawns and DropTables | mapped-unverified | weighted random pools and nested loot expansion exist; Spawner conditions now include vanilla lunar phase aliases with unit coverage, and Paper fixture `srv-paper-integration-lunar` verifies `lunar-phase-is=FULL` accepts a real spawn request; full condition combinations, experience and broader Paper spawn/drop semantics remain incomplete |

## 7. Migration and Verification

| Area | Status | Evidence / remaining work |
| --- | --- | --- |
| Mob Options mapping and preservation | mapped-unverified | broad mapping plus unsupported preservation |
| Equipment/Items/Attributes mapping | mapped-unverified | importer and runtime tests cover equipment, item metadata, and mob drop-experience field preservation/consumption; complete Mythic catalog not covered |
| Skills importer | mapped-unverified | common mechanics normalized; unsupported mechanics preserved |
| Spawner importer | mapped-unverified | core fields and random pools mapped; full Mythic spawner semantics incomplete |
| Unsupported report schema | implemented-runtime | `status`, `severity`, source/target/value assertions in importer tests |
| Paper integration suite | mapped-unverified | isolated `tools/paper-integration` fixtures boot Paper 1.21.1 with the current full plugin; entity setters, SkillService equipment/potion/drop-item dispatch, keyed health/health-percent/distance conditions, real Bukkit damage trigger dispatch for `on-damage`/`on-attacked`, loot/death drops, corrected synthetic Creeper/Bee/Zombie event dispatch, real Mineflayer menu workflow, and dual-client Audience delivery plus radius/permission/faction filters are probed; natural event timing, broader client versions, and several listed domains remain unverified |

## Release Gate

The overall objective remains open until every row is either
`implemented-runtime` with Paper evidence or explicitly accepted as an external
dependency. `mapped-unverified` and `unsupported-preserved` rows are not parity
completion.

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

Fixture `srv-paper-integration-is-child-20261016` additionally covers official
`isChild`/`child`: a real Pig was assigned the model-bound Wolf as parent and
the condition passed when evaluated against the Pig target. `conditionIsChild`,
`parentRelation`, and aggregate `conditionCatalog` are reported as passed.

Fixture `srv-paper-integration-parent-derived-20261016` covers `is-parent-alive`
and `is-sibling`: sibling comparison passes for two children sharing a live
parent, parent-alive passes before the parent is removed and fails afterward.
`parentDerivedConditions`, `parentRelation`, and aggregate `conditionCatalog`
passed on Paper 1.21.1.

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
passed.

Pathfinder fixture `srv-paper-integration-path-normal-20261016` completed the
native path probe after the isolated server was changed from PEACEFUL to NORMAL
while natural monster spawning remained disabled. A deterministic platform and
temporary entity target produced `found=true reachable=true accepted=true
cleared=true on-ground=true stop-calls=4`. This is the first real accepted and
cleared path evidence; full native goal parity remains open.
