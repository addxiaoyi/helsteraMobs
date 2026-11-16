# MythicMobs Parity Roadmap

This is the ordered worklist for closing the compatibility gaps recorded in the
[Parity Matrix](MYTHICMOBS_PARITY_MATRIX.md). It is an evidence-driven roadmap,
not a promise of identical behavior where Paper has no public API or a feature
requires another plugin.

## Baseline

Audited against the matrix on 2026-11-16:

| Matrix status | Rows | Meaning |
| --- | ---: | --- |
| `implemented-runtime` | 117 | A runtime implementation exists; the row's evidence still limits the claim. |
| `mapped-unverified` | 33 | Mapping or partial runtime support exists, but the full stated behavior is not verified. |
| `unsupported-preserved` | 2 | The source is intentionally preserved with a reason; it is not applied. |
| `missing` | 0 | No meaningful implementation exists. |
| `blocked-by-external-plugin` | 2 | A capability depends on an external integration that is not active. |

The latest full release fixture is `srv-paper-directional-velocity-strict-20261116-r1` on Paper 1.21.1-133, with 466 probes: 447 passed, 19 expected rejections, and zero failed or incomplete. Its strict production evidence covers MythicMobs `Velocity` and `DirectionalVelocity` mechanics, including regular and directional modes, aliases, dynamic placeholders, invalid-input preservation, and restoration; the catalog also retains HasInventorySpace, Inline Conditions and Altitude evidence, interactable client cancellation, spawning-distance client movement, ShowHealth delivery, player-scoped spawner limits, and the full runtime/client workflows. MythicMobs 5.7.2 has separate focused evidence; the full release fixture does not load MythicMobs. See the [release evidence index](tools/paper-integration/release-evidence.json), [Paper evidence](tools/paper-integration/STARTUP_EVIDENCE.md), and [version matrix](tools/paper-integration/version-matrix.json].

The next targeter contract slice is now implemented in the shared API: spatial
targeters reject malformed, negative, non-finite, duplicate, and unknown private
arguments instead of falling back to handler defaults. API coverage includes
inline and fallback arguments for sphere, ring, cone, line-of-sight, and line.
Focused exploratory Paper fixture `srv-paper-targeter-context-alias-strict-20261115-r3`
also verifies conditional-targeter hyphen/underscore aliases, `condition`/`c`
argument aliases, and fail-closed private-argument validation with two real
players. This does not replace the full strict catalog, so the Context targeters
row remains `mapped-unverified`.

The shared API now also rejects inactive direct context entities for `@target`,
`@targets`, `@trigger`, and `@attacker`, including projectile shooters. API and
cross-module tests cover scalar fallback, inherited target lists, multi-target
filtering, direct/projectile triggers, and dead or removed entities. The strict
Paper fixture `srv-paper-context-targeter-inactive-strict-20261115-r1` now
verifies entity removal and trigger cleanup for this slice. The broader Context
targeters row still remains `mapped-unverified` until the complete targeter
catalog and its parameters have been verified.

The cone targeter family is expanded in the shared API and verified in the
strict release fixture `srv-paper-cone-targeter-families-strict-20261115-r2` with
`@PlayersInCone`, `@MobsInCone`, `@MonstersInCone`, `@AnimalsInCone`,
`@VillagersInCone`, and `@TamedInCone`. The aliases apply the existing
angle/length grammar and geometry after the requested entity-family filter. API
and Paper coverage include positional, named, mixed-family, boundary, and
malformed-input paths. This closes the cone-family slice only; the broader
Context targeters row remains `mapped-unverified` until the complete targeter
catalog and its parameters have been verified.

The canonical environment conditions now validate named arguments at both the
direct runtime and `SkillService` binding boundaries. `world-is` accepts
`world`/`w`/`name`, `biome-is` accepts `biome`/`b` plus `exact`/`e`,
`weather-is` accepts `weather`/`type`, `time-is` accepts `time`/`t`, and
`dimension-is` accepts `dimension`/`environment`/`env`/`world`. Unknown,
duplicate, empty, mixed positional/named, and malformed arguments fail closed;
legacy positional forms remain supported. AI and plugin contract tests cover
the direct and imported paths. No Paper evidence was added, so the Conditions
catalog remains `mapped-unverified`.

The direct `lunar-phase` and `ender-dragon-phase` bindings now apply the
importer's strict `phase`/`phases`/`p` argument contract before passing the
phase list to their legacy positional factories. Unknown, duplicate, empty,
mixed, invalid, and out-of-range numeric phase values fail closed. AI and
plugin contract tests cover the numeric lunar cycle and Ender Dragon phase
list. No Paper evidence was added, so the Conditions catalog remains
`mapped-unverified`.

The direct `mythic-mob-type` and `template-type` bindings now validate their
named value and flag aliases before runtime matching. Unknown, duplicate,
empty, mixed, and invalid values fail closed, including the
`exactmatch`/`em` boolean contract. AI and plugin contract tests cover Mythic
type matching and template lineage matching. No Paper evidence was added, so
the Conditions catalog remains `mapped-unverified`.

The direct `field-of-view`, `looking-at`, and `has-potion-effect` bindings now
preserve named aliases before legacy positional factories receive them.
Importer and runtime factories reject unknown, duplicate, empty, mixed,
non-finite, and out-of-range values; valid positional forms remain compatible.
AI, migration, and plugin tests cover direct binding and imported runtime
paths. No Paper evidence was added, so the Conditions catalog remains
`mapped-unverified`.

## Completion contract

A capability can leave `mapped-unverified` only when all applicable checks below
are complete and the matrix, README, and evidence index agree:

1. Record the official source behavior and the MythicMobs/Minecraft/Paper version
   boundary being claimed.
2. Import valid syntax into a canonical representation; preserve malformed,
   conflicting, unknown, and out-of-scope source values with actionable
   diagnostics.
3. Connect that representation to a runtime consumer, or document why the
   feature cannot be applied on the declared platform.
4. Cover aliases, defaults, boundaries, negative cases, and fail-closed
   behavior in importer, runtime, and cross-module tests as applicable.
5. Verify observable behavior on Paper with production configuration and real
   entities/events. Include inverse controls, cleanup/state restoration, and
   client observation when the claim includes client-visible behavior.
6. Register required observations in the strict release catalog, validate the
   report and artifact hashes, and archive reproducible logs. Label synthetic
   dispatch, exploratory versions, and installed-plugin runs accurately.
7. Update the exact matrix row and the user-facing documentation. Do not infer
   catalog completion from a passing aggregate probe.

`unsupported-preserved` is acceptable only with a verified technical boundary,
preserved source data, and a warning. `blocked-by-external-plugin` is acceptable
only with a capability/version contract, fail-closed behavior, and an explicit
statement of missing installed-plugin evidence. Neither status counts as full
parity.

## Ordered work packages

### 0. Keep the evidence ledger trustworthy

- Reconcile all matrix rows with importer fields, runtime consumers, tests, and
  current reports; distinguish current evidence from historical fixture names.
- Keep release evidence manifests, report schemas, README, matrix, and Paper
  version declarations consistent.
- Make every release probe mandatory in the strict report contract, including
  dynamically registered probes; treat absent evidence as not observed.
- Keep the clean Maven, SpotBugs, schema, and strict Paper gates reproducible.

**Exit:** no stale or unverifiable evidence is presented as current proof, and a
clean checkout can run the documented quality gates.

### 1. Close Conditions and expression semantics

Matrix rows: `Conditions catalog`, `Full Mythic condition/filter grammar`, and
`Inline conditions`.

- Inventory the official Conditions catalog for the declared MythicMobs range;
  track aliases, parameters, defaults, entity scope, and version gates per item.
- Finish mapped-but-unverified conditions and identify truly missing conditions
  instead of silently treating unknown names as supported.
- Complete comparator, placeholder, quoting, nesting, negation, and list
  semantics; cover caster, target, trigger, location, and missing-context cases.
- Verify imported conditions through real Paper entities, with positive,
  negative, malformed, and state-restoration controls.
- Preserve official per-condition defaults and validate argument aliases;
  `DamageCause` now defaults to `ENTITY_ATTACK` and has strict Paper release
  evidence for its imported damage-trigger path. `StatDamageModifier` now has
  importer/runtime coverage plus strict evidence from both synthetic dispatch
  and a naturally generated Paper damage event. The official `Distance` condition
  now has an independent `implemented-runtime` row with importer/runtime tests and
  strict Paper evidence; retain the full catalog as the remaining Conditions-level
  work.
- Official MythicMobs 5.7.2 `HasInventorySpace` is now mapped to a dedicated
  `has-inventory-space` runtime condition. It checks the selected Player target's
  `PlayerInventory#firstEmpty()` result, rejects arguments, and keeps the existing
  `hasFreeInventorySlot`/`hasFreeSlot` project aliases stable. Importer, AI, and
  plugin cross-module tests cover full, available, non-Player, missing-target,
  and malformed-input cases; no new Paper evidence is claimed yet.
- Official MythicMobs 5.7.2 `MythicKeyId`, `keyid`, and `ariKeyId` now map to
  `mythic-key-id`. The runtime reads the exact skill-tree `key-id` variable,
  applies the `minecraft:attack` default, resolves skill placeholders, and
  compares case-insensitively. The existing imported `setvariable` mechanic can
  populate this variable. Missing metadata, unresolved placeholders, duplicate
  or unknown arguments, and invalid values fail closed; importer, AI, and
  cross-module tests cover the contract. No new Paper evidence is claimed for
  this context-variable slice.
- Official MythicMobs 5.7.2 `factionsame` now joins `samefaction` as an alias
  for the exact `same-faction` evaluator. Importer, runtime, and cross-module
  tests cover the alias and reject supplied arguments. This closes a naming gap
  only; the existing exploratory Paper evidence remains the evidence boundary.
- Official MythicMobs 5.7.2 `creeperCharged` now joins `charged` and
  `isCharged` as aliases for the Bukkit `Creeper#isPowered()` evaluator.
  Importer, list-parser, runtime, and cross-module tests cover the alias and
  reject supplied arguments. Existing charged-state Paper evidence remains the
  evidence boundary.
- Official MythicMobs 5.7.2 `isNaturalBlock` now maps to `is-natural-block`.
  The audited upstream implementation only adapts the location and returns
  `true`; Helstera matches that behavior for a valid location and fails closed
  when location context is missing. Importer, list-parser, runtime, and
  cross-module tests cover the mapping and invalid arguments. No new Paper
  evidence is claimed for this upstream behavior boundary.
- Official MythicMobs 5.7.2 `food`, `hunger`, and `hungerlevel` now join the
  existing `foodlevel`/`foodLevelIs` mappings. The importer accepts the
  official `amount`/`a` and `food`/`f` forms, uses the upstream default `0`,
  and rejects unknown or duplicate arguments; runtime and cross-module tests
  cover Player-only evaluation. No new Paper evidence is claimed.
- Official MythicMobs 5.7.2 `sunny`/`isSunny` and
  `thundering`/`isThundering`/`stormy`/`isStormy` aliases now map through the
  importer, `SkillService`, and condition-list parser to the clear and thunder
  weather evaluators. Unknown and duplicate arguments remain preserved and
  fail closed; migration, API, AI, and plugin cross-module tests cover the
  alias paths and clear/thunder runtime states. No new Paper evidence is
  claimed, and the complete Conditions catalog remains `mapped-unverified`.
- Official MythicMobs 5.7.2 `sneaking`/`isCrouching`/`isSneaking` and
  `isWearing`/`wielding`/`isWielding` aliases now map through the importer,
  `SkillService`, and condition-list parser. Existing `is-sneaking` and
  `wearing` runtime evaluators are reused; wearing item arguments are strict,
  with unknown, duplicate, and empty values preserved and rejected. Migration,
  API, AI, and plugin cross-module tests cover Player sneaking, helmet matching,
  and alias preservation. No new Paper evidence is claimed, and the complete
  Conditions catalog remains `mapped-unverified`.
- Official environment and region naming variants now join the same canonical
  condition paths: `environment` maps to `dimension-is`, `inregion` maps
  to `region`, and singular `playerinradius` maps to
  `players-in-radius`. The list parser recognizes these aliases plus
  `hasplugin` and `mmtype` as condition starts, preventing comma-delimited
  source entries from being merged. Migration, API, AI, and plugin tests cover
  the slice; no new Paper evidence is claimed, so the complete Conditions
  catalog remains `mapped-unverified`.
- The existing `snowing`/`isSnowing` aliases now bind directly through
  `SkillService`, and the condition-list parser recognizes the remaining
  weather/time aliases such as `isRaining`, `isThundering`, `daytime`,
  and `nighttime`. API and AI tests cover direct binding and malformed
  argument rejection; no new Paper evidence is claimed.
- A direct-binding audit closed migrated relationship/state aliases for
  mounted, leash, owner, parent, sibling, claim, patrol-leader, invulnerable,
  burning, and global-cooldown conditions. Unknown arguments are rejected
  before runtime factory creation; this adds no Paper evidence.
- Track Mythic-only or premium conditions separately from Bukkit-native ones.
- The official MythicMobs 5.7.2 `Moist` check requires farmland moisture exactly
  `7`; the runtime now distinguishes it from `MoistureLevel`, which retains its
  explicit range behavior. Unit coverage includes fully hydrated, partial, dry,
  and non-farmland states. Existing exploratory Paper evidence covers levels 7
  and 0 only; this semantic correction has no new Paper evidence yet.
- Official MythicMobs 5.7.2 `Altitude` walks downward from the target's
  current block until a non-passable block or the `maxHeight` scan boundary;
  using `World#getHighestBlockAt` changes cave/overhang behavior and produces a
  one-block offset. Runtime, importer, and direct-binding tests now pin the
  local block surface, scan boundary, aliases, and malformed/duplicate
  arguments. Strict Paper fixture `srv-paper-altitude-strict-20261116-r4` now verifies the imported and direct runtime
  paths, local cave/overhang surface selection, the scan boundary, duplicate
  preservation, block restoration, and temporary-entity cleanup. Exploratory r79
  remains historical evidence for the superseded highest-block-column behavior.

- Conditional targeter syntax now covers the official `cond`/
  `targetconditions` parameter aliases, `nearestconditional`/`nearestif`
  targeter aliases, and inline `- condition true|false` list entries. Legacy
  delimited entries remain default-true, and malformed or duplicate private
  arguments fail closed. AI selector `mustsee` is honored; `interval` follows
  Paper's native reduced goal-check probability and `mustreach` requires a
  complete public Paper Pathfinder result. Missing, cross-world, empty, and
  exceptional paths fail closed. This is covered by API, AI, and importer
  tests; no new Paper evidence is claimed, so the complete Context Targeters
  catalog remains `mapped-unverified`.

**Exit:** every condition in the declared catalog has a verified implementation,
an explicit preserved/unsupported explanation, or a documented external/version
block; no unexplained condition gap remains.

### 2. Close Skills, Mechanics, Targeters, and Triggers

Matrix rows: `Mechanics catalog`, `Skills importer`, `Context targeters`,
`Trigger catalog`, plus the skill-related portions of `Audience actions`.

- Audit each mechanic's accepted syntax, aliases, defaults, target behavior,
  placeholders, timing, and cancellation rules; connect supported imports to
  production runtime actions.
- Finish metaskill reference, parameter, inheritance, target-scope, and nested
  execution semantics; ensure unsupported lines remain visible.
- Nested `skill{s=...;cooldown=...}` now preserves validated cooldown
  metadata through Mythic import and applies a per-instance runtime gate;
  malformed, duplicate, and non-nested cooldowns remain preserved or rejected.
  Focused Paper 1.21.1-133 evidence is now recorded in
  `srv-paper-nested-skill-cooldown-strict-20261115-r3`, covering one execution
  per cooldown window, immediate blocking, reopening, and malformed-value
  preservation. The full Skills importer row remains `mapped-unverified` until
  the declared Skills and Mechanics catalogs are covered.
- Official `DirectionalVelocity` / `directionalvelocity` / `dvelocity` is now an independent `implemented-runtime` row. It validates `yaw`, `pitch`, `velocity`/`v`, and `mode`/`m`, applies all six documented modes including `MINIMUM`, supports imported execution and dynamic placeholders, and preserves invalid source lines with warnings. Strict Paper fixture `srv-paper-directional-velocity-strict-20261116-r1` passes the full 466-probe catalog. The public MythicMobs reference does not publish the `MINIMUM` formula, so the implementation boundary is documented as minimum directional projection with lateral velocity retained.
- Numeric state mechanics now accept their imported named forms at the direct runtime boundary as well: `set-no-damage-ticks` and `set-maximum-no-damage-ticks` accept `ticks`/`duration`/`time`/`t`, `set-fire` accepts the same aliases, and `freeze` accepts `duration`/`ticks`/`time`/`t`. Dynamic placeholders are resolved at execution time, negative values clamp to zero, and non-finite values fail during binding. This closes a parser-to-runtime contract gap; the full Mechanics catalog remains `mapped-unverified`.
- Health state mechanics now accept the imported `amount`/`a`/`value` forms at the direct runtime boundary for both self and target scopes: `set-health`, `modify-health`, `set-max-health`, and `modify-max-health`. Positional arguments remain compatible, placeholders still resolve at execution time, and finite numeric validation remains eager. Runtime coverage includes max-health attribute updates, target dispatch, and malformed/non-finite rejection; no new Paper evidence is claimed, so the full Mechanics catalog remains `mapped-unverified`.
- Movement speed mechanics now accept imported `amount`/`a`/`speed`/`factor` forms at the direct runtime boundary for both self and target scopes: `modify-speed` and `set-tick-speed`. Positional arguments remain compatible, execution-time placeholders are preserved, and literal non-finite values fail during binding. Runtime coverage includes Bukkit movement-attribute scaling and target dispatch; no new Paper evidence is claimed, so the full Mechanics catalog remains `mapped-unverified`.
- Damage and healing mechanics now accept imported named numeric forms at the direct runtime boundary: absolute `damage-target`, `heal-targets`, and `heal-self` accept amount/value aliases, while `heal-percent` and `damage-percent` self/target paths accept amount/percent aliases. Positional arguments, target dispatch, execution-time placeholders, and eager non-finite rejection remain covered by runtime tests; no new Paper evidence is claimed, so the full Mechanics catalog remains `mapped-unverified`.
- Impact and displacement mechanics now accept their imported named numeric forms at the direct runtime boundary: `ignite-targets` accepts `ticks`/`duration`/`time`/`t`, `knockback-targets` accepts power and vertical aliases, and `pull`, `leap`, and `leap-targets` accept radius/velocity aliases. `damage-melee` now accepts amount and radius aliases and resolves both values at execution time, preserving positional syntax and rejecting non-finite literals. Runtime coverage includes target and self dispatch, vector outcomes, nearby-target damage, placeholder-compatible binding, and invalid-input rejection; no new Paper evidence is claimed, so the full Mechanics catalog remains `mapped-unverified`.
- Complete official targeter names, filters, modifiers, ordering, limits,
  chaining, location/entity distinctions, and invalid-input behavior.
- Verify trigger registration, event actor/target/trigger context, priority,
  cancellation, natural timing, and cleanup. Separate synthetic event evidence
  from naturally produced events.
- Keep direct profile loading aligned with Mythic import aliases: `onDamaged` /
  `onHurt`, `onPickup`, `onDrop`, `onShoot`, `onMount`, and `onDismount` now
  resolve to the existing canonical trigger routes. This closes a parser-level
  silent-failure path; it does not claim additional Paper event coverage.
- Exercise inline and trigger conditions through their actual skill dispatch
  paths, not only direct condition evaluation.

**Exit:** each supported catalog item has importer-to-action/event integration
coverage and Paper evidence appropriate to its claim; remaining plugin-dependent
or unavailable APIs are preserved and documented.

### 3. Close Mob Options and entity-specific behavior

Matrix rows: `Universal options` and `Remaining entity-specific options`, plus
`Mob Options mapping and preservation`.

- Inventory the official universal and entity-family option catalogs, defaults,
  aliases, and precedence against the selected Minecraft/Paper API.
- Verify full production spawn ordering and interactions among options,
  equipment, native defaults, and event policies.
- Add real-entity coverage for every supported family and controls for wrong
  entity types, malformed values, explicit false, and omitted defaults.
- Preserve source values when Paper does not expose a safe public setter or the
  option is outside the declared version range.

**Exit:** every claimed option has production-spawn readback or observable event
evidence, and unsupported values retain source and reason.

### 4. Close Items, Equipment, Attributes, Drops, and legacy data

Matrix rows: `Inline names, lore, enchants, potions, skulls`, `Legacy numeric IDs
and data`, `Entity and item attributes`, `Drops and DropTables`, `Equipment/Items/
Attributes mapping`, and `Spawner importer` where it shares loot behavior.

- Expand ItemMeta component coverage, potion/skull/enchantment combinations,
  equipment slots and chances, and production custom-item resolution.
- Complete the documented legacy item/data families with explicit invalid-ID,
  durability, potion, spawn-egg, and block-state boundaries.
- Cover entity and item attribute slots, operations, UUID identity, stacking,
  precedence, and client-visible results where applicable.
- Finish DropTables amount/range/weight/luck semantics, placeholders, nested
  expansion, conditions, XP, natural death, and custom enchantment inputs.
- Identify statistical/natural-drop tests separately from deterministic tests;
  retain sample sizes and observed results rather than claiming exactness from
  a small random sample.

**Exit:** claimed combinations are verified through the production importer,
resolver, and natural or explicitly synthetic runtime path; unsupported item
components are not replaced with plausible vanilla values.

### 5. Close Custom AI and target selection

Matrix rows: `Factions and threat targeting`, `Basic goals and target
selectors`, `Ranged/leap/owner/faction selectors`, `Full
AIGoalSelectors/AITargetSelectors`, and `Native Pathfinder Goal composition and
mutation`.

- Build an official selector/goal coverage inventory including parameters,
  priority, clear/rebind behavior, preemption, and target changes.
- Verify the supported typed controller paths on Paper for movement, targeting,
  owner/faction relations, and dynamic selector updates.
- Define a hard boundary for native Goal Selector APIs unavailable through
  Paper's public API; preserve those inputs with diagnostics instead of
  implying native composition equivalence.
- Keep `AIPathfindingMalus` explicitly preserved until a supported public API or
  an approved, version-specific adapter exists.

**Exit:** all public-API-supported selectors and goals have behavioral evidence;
native-only functionality has an explicit preservation boundary and is not
misrepresented as equivalent.

### 6. Close SkillTree, Audience, and Custom Menus

Matrix rows: `Audience parsing and delivery`, `Audience actions`, `Full SkillTree
semantics`, and `Custom menu pagination and visibility`.

- Verify audience filters in combinations across permission, faction, world,
  radius, region, and reconnect/client protocol boundaries; retain the current
  shared-targeter `limit`/entity-filter evidence while extending client-visible
  coverage to each supported selector family.
- Cover SkillTree prerequisites, unlock conditions, nested casts, persistence,
  atomic writes, restart, death, unload/reload, config changes, and client UI.
- Complete menu condition parsing, pagination, visibility, action permissions,
  inventory transitions, reconnect cleanup, and supported client versions.
- Preserve the difference between parser tests, Paper server observations, and
  real-client observations.

**Exit:** lifecycle and multi-client claims are supported by repeatable Paper
and client evidence, including restart and cleanup paths.

### 7. Close Spawners and Random Spawn

Matrix rows: `Spawner parsing, cooldown, max-alive, persistence`, `Random
weighted mob pools`, `Spawner conditions and regions`, `Heightmap, ground,
border and player gates`, and `Mythic Random Spawns and DropTables`.

- Complete official spawner fields, scheduling, cooldown recovery, limits,
  quotas, chunk/world gates, and restart behavior.
- Exercise natural spawn locations and combined condition gates over terrain,
  world-border, player-distance, and loaded/unloaded chunk boundaries.
- Verify weighted pool distributions with declared sampling plans and prove
  per-instance random mob to DropTable coupling through natural death.
- Keep randomized observations reproducible where possible and report
  uncertainty rather than promising a fixed distribution from a small sample.

**Exit:** supported spawner paths have multi-boot natural generation evidence,
negative gate controls, and documented statistical limits.

### 8. Resolve external plugin and version boundaries

Matrix rows: `External drop and economy types`, `External item and model
adapters`, `MythicMobs model bridge`, and `Lands claim provider`; also maintain
the external integration contract.

- For each adapter, declare tested plugin/API versions and activate capabilities
  only after discovery, version checks, and hook registration succeed.
- Add installed-plugin Paper fixtures for supported Vault providers, MythicMobs
  bridge capabilities, and Lands; add absent, incompatible, missing-service,
  invalid-ID, and throwing-provider controls.
- Implement public API bridges for ItemAdder/CraftEngine/MMOItems/
  MythicCrucible/ModelEngine only when the API contract and testable plugin
  artifacts exist. Until then retain identifiers and report blocked capability.
- Expand the Paper/Minecraft matrix one release target at a time. Keep Paper
  1.21.1-133 as the only release baseline until another version passes its own
  complete strict gate; do not infer Spigot/Folia compatibility.

**Exit:** each active capability is proven for the exact plugin and server
versions recorded; every other combination stays explicitly unavailable or
unverified.

### 9. Final parity release

- Re-run the full matrix audit and regenerate status counts from the status
  column.
- Resolve every `mapped-unverified` row and review every `unsupported-preserved`
  and `blocked-by-external-plugin` row against its documented boundary.
- Run Maven tests/quality checks, report schema and catalog audits, and the full
  strict Paper gate for each declared release target.
- Archive reports, redacted logs, client captures where required, and exact
  plugin/dependency hashes; update README, matrix, integration contracts, and
  release notes together.

**Exit:** no unexplained gaps remain, all supported claims have applicable
runtime evidence, blocked capabilities have explicit prerequisites, and a clean
checkout reproduces every declared release gate.

## Current 33 mapped-but-unverified rows

| Workstream | Rows |
| --- | --- |
| Skills | Inline conditions; Context targeters; Audience actions; Mechanics catalog; Conditions catalog; Trigger catalog |
| Mob Options | Universal options; Remaining entity-specific options |
| Items, Equipment, Attributes | Inline names/lore/enchants/potions/skulls; Legacy numeric IDs/data; Entity/item attributes; Drops/DropTables; MythicMobs model bridge; Lands claim provider |
| Custom AI | Factions/threat targeting; Basic goals/target selectors; Ranged/leap/owner/faction selectors; Full AI Goal/Target selectors; Native Pathfinder goal composition/mutation |
| Skill Trees, Conditions, Audience | Audience parsing/delivery; Full condition/filter grammar; Full SkillTree semantics |
| Spawner, Random Spawn, Custom Menu | Spawner parsing/cooldown/max-alive/persistence; Random weighted pools; Spawner conditions/regions; Heightmap/ground/border/player gates; Custom menu pagination/visibility; Mythic Random Spawns/DropTables |
| Migration and Verification | Mob Options mapping/preservation; Equipment/Items/Attributes mapping; Skills importer; Spawner importer; Paper integration suite |

## Explicitly preserved or externally blocked today

- `Options.Gravity`: not in the official Options reference; retained with a
  warning and not applied.
- `AIPathfindingMalus`: Paper's public API does not expose native PathType malus
  mutation; retained with a warning and not applied.
- External drop/economy types: only the documented Vault/Economy provider slice
  is active; other provider and MythicCrucible/MMOItems behavior is not implied.
- External item/model adapters: ItemAdder, CraftEngine, MMOItems,
  MythicCrucible, and ModelEngine capabilities stay inactive until their public
  API/version contracts and installed-plugin evidence exist.
