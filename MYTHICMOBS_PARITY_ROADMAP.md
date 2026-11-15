# MythicMobs Parity Roadmap

This is the ordered worklist for closing the compatibility gaps recorded in the
[Parity Matrix](MYTHICMOBS_PARITY_MATRIX.md). It is an evidence-driven roadmap,
not a promise of identical behavior where Paper has no public API or a feature
requires another plugin.

## Baseline

Audited against the matrix on 2026-11-15:

| Matrix status | Rows | Meaning |
| --- | ---: | --- |
| `implemented-runtime` | 112 | A runtime implementation exists; the row's evidence still limits the claim. |
| `mapped-unverified` | 33 | Mapping or partial runtime support exists, but the full stated behavior is not verified. |
| `unsupported-preserved` | 2 | The source is intentionally preserved with a reason; it is not applied. |
| `blocked-by-external-plugin` | 2 | A capability depends on an external integration that is not active. |

The latest full release fixture is `srv-paper-distance-strict-20261115-r1` on Paper 1.21.1-133, with 460
probes: 441 passed, 19 expected rejections, and zero failed or incomplete. It
adds production imported `Distance` condition evidence for `d`/`distance` aliases,
strict and inclusive boundaries, and fail-closed preservation of missing, NaN,
and percentage inputs. The existing Audience `@Targeter` dispatch and client
workflow coverage remains in the same full catalog. MythicMobs 5.7.2 has separate focused
evidence; the full release fixture does not load MythicMobs. See the
[release evidence index](tools/paper-integration/release-evidence.json),
[Paper evidence](tools/paper-integration/STARTUP_EVIDENCE.md), and
[version matrix](tools/paper-integration/version-matrix.json).

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
filtering, direct/projectile triggers, and dead or removed entities. A real Paper
fixture for entity removal and trigger cleanup is still required before this
boundary can be promoted beyond `mapped-unverified`.

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
- Track Mythic-only or premium conditions separately from Bukkit-native ones.

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
- Complete official targeter names, filters, modifiers, ordering, limits,
  chaining, location/entity distinctions, and invalid-input behavior.
- Verify trigger registration, event actor/target/trigger context, priority,
  cancellation, natural timing, and cleanup. Separate synthetic event evidence
  from naturally produced events.
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
