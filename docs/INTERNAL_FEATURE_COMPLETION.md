# Internal feature completion checklist

Inventory date: 2026-11-21; development status updated 2026-11-26.
Baseline: Paper 1.21.1 / public MythicMobs 5.7.2.
This is a finite, source-checked development backlog, not a claim that the 33
broad `mapped-unverified` matrix rows constitute 33 missing implementations.
An item is complete only after its native contract, migration, direct runtime,
negative controls and appropriate Paper evidence agree.

## Already implemented in the current development batch

These need validation and release evidence, not another implementation:

- `AIPathfindingMalus` mob option: list parser, all 26 pinned PathTypes,
  version-gated native setter, production spawn and saved-entity restoration.
  Its skill mechanic is now implemented in the setter slice below.
- Model bridge `modelmount`, `modelunmount`, `modelheal`: present alongside
  `modelspawn`, `modelremove`, `modelplay`, `modelstop`, `modelscale`.
- Condition actions `REQUIRED`, `CANCEL`, `CAST`, `CASTINSTEAD`, `ORELSECAST`,
  the pinned `LEVEL` handling and finite float `POWER` metadata propagation are present.
- Aura termination policies for give/take damage, target/caster death,
  teleport, world change and quit are present. Base Aura charge state,
  explicit charge consumption, BossBar timers and callback-state isolation are
  also implemented. Event-specific Aura subclasses and attachment ownership
  remain separate work. See [AURA_LIFECYCLE_CONTRACT.md](AURA_LIFECYCLE_CONTRACT.md).
- `variableSubtract` and aliases now use typed INTEGER/FLOAT arithmetic and
  duration handling. The public native build gates quoted/dynamic operands
  behind Premium and falls back to `1`; Helstera supports those operands
  itself. This intentional difference is recorded separately from matching
  observations. Overflow to non-finite float values is rejected by Helstera.
- Variable locations for ordinary player aiming are already implemented.
- Public setter slice M01/M08–M12/M14–M16 is implemented with shared argument
  validation, importer mapping and generic direct entity recipients. See
  [ENTITY_SETTER_CONTRACT.md](ENTITY_SETTER_CONTRACT.md) for the deliberately
  bounded selector/condition scope. The 2026-11-22 r3 report passes 42 scoped
  observations; broader selectors, nonliving conditions and release evidence remain.
- `equipCopy` and `giveitemfromslot/givefromslot` (M04a) are implemented;
  2026-11-26 r9 passes 165 two-client inventory/packet observations. Multiple
  recipients, actual reload and disconnect cleanup still need acceptance.
  Generated item grants (`giveitemfromtarget`, M04b) now have runtime/importer
  implementations and 90 passing two-client observations in 2026-11-26 r3;
  the detailed live evidence and remaining
  acceptance are in [GENERATED_LOOT_CONTRACT.md](GENERATED_LOOT_CONTRACT.md).
- `stopusingitem/releaseitem`, `matchrotation/matchrot` and `GoatRam` have
  implementation and offline tests. Their previously missing production
  registration and importer wiring are now connected and covered by binding
  tests. Their complete Paper/client acceptance
  remains pending; see [ENTITY_INTERACTION_CONTRACT.md](ENTITY_INTERACTION_CONTRACT.md).
- The 2026-11-26 focused reports pass 212 scoreboard, 66 context-targeter and
  300 variable-subtraction observations. The native dynamic placeholder
  fixtures now wait for initialization; public numeric-parser and float-overflow
  differences remain explicit. These reports do not complete the strict
  release gate. See the [evidence index](../tools/paper-integration/STARTUP_EVIDENCE.md).
- JSON messages (M05) now have runtime and importer implementations, including
  structured components, recipient placeholders and audience filtering. Offline
  tests pass; Paper/client acceptance remains pending. See
  [JSON_MESSAGE_CONTRACT.md](JSON_MESSAGE_CONTRACT.md).

## Concrete public-build mechanics inventory

Native names and aliases below come from `@MythicMechanic` in the pinned
5.7.2 JAR. These entry points were missing when the original inventory was taken;
the current status column and implemented list above record subsequent work.
Reusable lower-level capabilities alone do not establish the native argument,
recipient or persistence contract. No paid plugin is required to implement
these items. Implemented rows need their remaining validation and explicit
contract extensions, not duplicate implementations.

| ID | Exact mechanic names / aliases | Remaining work and completion boundary |
| --- | --- | --- |
| M01 | `setpathfindingmalus`, `setmalus`, `malus` | Implemented and covered by the 42-observation setter r3 slice. Broader selector and final release acceptance remains. |
| M02 | `variableSubtract`, `variableSub`, `subtractVariable`, `subVar`, `reduceVariable` | Implemented; 2026-11-26 r1 passes 300 scoped observations, with 20 public-parser and 10 non-finite overflow differences. Real-time expiry crossing and full release validation remain. |
| M03 | `setGlobalScore/sgs`, `setMobScore/sms`, `setTargetScore/sts`, `modifyGlobalScore/mgs`, `modifyMobScore/mms`, `modifyTargetScore/mts`, `modifyScore` | Implemented; 2026-11-26 r2 passes 212 scoped observations. Full native dispatcher scheduling and release validation remain. |
| M04 | `equipCopy`, `giveitemfromslot/givefromslot`, `giveitemfromtarget/givefromtarget/giveitemsfromtarget/itemgivefromtarget` | Equipment copy and slot grants pass 165 scoped two-client observations. Generated rewards pass 90 in 2026-11-26 r3; see the generated-loot contract. Randomized/native edge cases, multi-recipient/reload/disconnect acceptance and the final release gate remain. |
| M05 | `messagejson/jsonmessage/jmsg/jm` | Runtime/importer and offline tests implemented. Real Paper/client component, placeholder and audience acceptance remains. |
| M06 | `addTrade/setTrade/removeTrade/replaceTrade` | Villager recipe mutation with native mode/index/material/metadata handling and wrong-entity rejection. |
| M07 | `opentrades/opentrade/trade` | Open the selected villager's trade UI for the appropriate player; verify actor/recipient direction and UI closure. |
| M08 | `setleashholder/setleasher` | Implemented; the caster's holder becomes the selected entity. Setter r3 covers the scoped direction/unbinding cases; broader world/rejection cases and release validation remain. |
| M09 | `setchunkforceloaded` | Implemented; setter r3 covers selected-location force-load changes and cleanup. Full release validation remains. |
| M10 | `setTextDisplay/setText` | Implemented with generic entity dispatch and scoped setter r3 evidence. Broader selectors, nonliving conditions and client rendering acceptance remain. |
| M11 | `setDisplayEntityItem` | Implemented through production item resolution with scoped setter r3 evidence. Broader selectors, nonliving conditions and client rendering acceptance remain. |
| M12 | `setInteractionSize/interactionSize` | Implemented with scoped setter r3 evidence and an explicit public dynamic-float parser difference. Broader selectors/nonliving conditions and release validation remain. |
| M13 | `displaytransformation/settransformation/transformation` | Apply Display transformation, including translation, rotation and scale; compare interpolation and client rendering. |
| M14 | `setRaiderCanJoinRaid/setCanJoinRaid` | Implemented with scoped setter r3 evidence; full release validation remains. |
| M15 | `setRaiderPatrolBlock/setRaiderBlock` | Implemented with bounded nested-location selection and scoped setter r3 evidence. Plural/conditional nested selectors and release validation remain. |
| M16 | `setRaiderPatrolLeader/setRaiderLeader` | Implemented with scoped setter r3 evidence; broader equipment side effects and release validation remain. |
| M17 | `stopusingitem/releaseitem` | Release behavior is implemented through the pinned native adapter; complete bow/item-use/event acceptance remains. Paper `clearActiveItem` alone is not equivalent. |
| M18 | `shieldbreak/disableshield` | Native contract inspected; implementation remains. Requires blocking, cooldown, main-hand-first selection and one-tick shield removal/restoration, including occupied-slot/lifecycle handling. See [SHIELD_BREAK_CONTRACT.md](SHIELD_BREAK_CONTRACT.md). |
| M19 | `setpitch`, `matchrotation/matchrot` | Pinned native SetPitch is a no-op. MatchRotation is implemented with nested selection, non-player recipients and body/head yaw plus pitch; Paper/client acceptance remains. |
| M20 | `GoatRam` | Implemented through Paper `Goat.ram(LivingEntity)`; natural damage/event acceptance remains. |
| M21 | `summonAreaEffectCloud/summonCloud`, `summonFallingBlock`, `summonPassenger` | Add typed entity spawning with native argument and lifecycle rules; generic managed-model summon is a separate operation. |
| M22 | `runaitargetselector/aitarget` | Add native syntax mapping and selected-controller mutation, then test priority, clear/reset and rebind behavior. Do not claim native Goal equivalence from current controller mutation. |

## Concrete target selectors still absent at audit time

Names come from native `@MythicTargeter` declarations and were checked against
`Targeters`, `MythicTargeterSpec`, `SkillService` and importer mappings.

| ID | Exact selector names / aliases | Required behavior |
| --- | --- | --- |
| T01 | `@playerByName/@specificplayer` | Implemented; 2026-11-26 r1 context fixture passes its exact-name, placeholder, missing-player and cross-world cases. Full native scheduling remains outside this slice. |
| T02 | `@itemsInRadius/@IIR`, `@itemsNearOrigin/@INO` | Select dropped Item entities. Current general selector collections are primarily `LivingEntity`; a name alias alone cannot add this capability. |
| T03 | `@interactionLastAttacker/@lastAttacker`, `@interactionLastInteract/@lastInteract` | Implemented; 2026-11-26 r1 passes prepared-history lookup cases. Actual client attack/interaction input is outside that evidence. |
| T04 | `@locationsOfTargets/@locationOfTarget/@LOT` | Implemented; 2026-11-26 r1 passes scoped projection/deduplication and location-selection cases. Native HashSet iteration does not promise stable ordering. |
| T05 | `@targetBlock`, `@targetedLocation/@targetedLocations/@targetedLoc`, `@targetedTarget/@targeted` | Implement their distinct native aiming/inherited-target contracts; existing VariableLocation player aiming is not automatically an implementation of these targeters. |
| T06 | `@highestblock`, `@obstructingBlock` | Establish world/heightmap or obstruction query semantics and unloaded-world/chunk behavior. |
| T07 | `@blocksinchunk/@BIC`, `@blocksinradius/@BIR`, `@blocksnearorigin/@BNO`, `@blockVein/@vein/@bv` | Bounded block-location enumeration with native geometry, material filters and explicit output caps. |
| T08 | `@nearestStructure` | Use public structure lookup with bounded radius and unloaded-generation policy; native defaults require inspection. |
| T09 | `@randomLocationsNearOrigin/@RLO/@randomLocationsOrigin/@RLNO`, `@randomLocationsNearTargets/@RLNT`, `@randomLocationsNearCaster/@RLNC/@randomLocations` | Deterministic testable sampling, native radius/count/height rules and distinct caster/origin/recipient scope. |
| T10 | `@randomRingPoint`, `@ringAroundOrigin/@ringOrigin/@RAO` | Location-valued ring sampling, which differs from filtering existing living entities with `ring`. |
| T11 | `@randomThreatTargetLocation/@RTTL` | Select from a live threat table and project to a location, including empty/stale-table and terminal behavior. |

## Larger internal implementation gaps

| ID | Explicit gap | Finite acceptance slice |
| --- | --- | --- |
| S01 | Native Goal composition | Add/remove goals with Paper `MobGoals`; compare priorities, shared GoalType conflicts, interrupt/preemption, clear/reset, rebind, disable and native target side effects. Public API exists; this is not an API-availability block. |
| S02 | Aura charges | Base tracker charge state and explicit consumption API implemented. Plain native Aura does not consume once per tick; event-subclass consumption remains in S04. Validate zero-charge termination, merge/overwrite and callback counts. |
| S03 | Aura attachments and visual components | Inspect native public contracts, then either implement each component with entity cleanup or preserve it explicitly. Existing start/tick/end callbacks do not implement attachment ownership. |
| S04 | Aura event-bound callbacks/cancellation | Distinguish cancelling an aura from cancelling its triggering Bukkit event; implement only observed callback and cancellation semantics. |
| S05 | RandomSpawn `Level` other than numeric `1` | Current importer rejects it. Add level propagation through ADD/REPLACE to generated mob stats and natural drops, with malformed level rejection. |
| S06 | RandomSpawn vanilla `Type/Types` | Current `parseRandomSpawnTypes` requires a resolved custom Mythic mob ID. Define a vanilla spawn path or retain the boundary explicitly. |
| S07 | RandomSpawn native generator semantics | Current opt-in ADD/CLUSTER is an approximation. Inventory and test cap/cluster, player-distance and LAND/SEA placement, cooldown/restart and condition combinations separately. |
| S08 | Stateful legacy item data | Nonzero data for doors, trapdoors, rails, bed orientation, furnace/dispenser/dropper facings, levers/buttons and other stateful IDs lack dedicated bit-to-BlockData conversion. Current zero/default material mappings do not establish these variants. |
| S09 | VariableLocation remaining contexts | Player models with threat tables, ActiveMob-wrapped players, non-Creature last-aggro without a table, terminal target selection and selector options remain outside the current implemented slice. |
| S10 | Native threat switching | Prepared threat selection reads are tested; full native threat mutation/switching policy and the production AI tick connection remain separate work. |

## Verification work, not proven missing implementation

1. Refresh the full strict release gates on the final current JAR; old reports
   cannot certify a later build.
2. Run the new malus, condition-action, model-action and Aura fixtures through
   their production importer and event paths, then archive matching hashes.
3. Cover ordinary-player death/respawn configured skill dispatch, asynchronous
   save/load races and interrupted writes rather than only completed checkpoints.
4. Cover external entity removal, terminal visual callbacks, summoned-child
   inheritance and rapid-reload callback-created-child timing.
5. Cover audience filter combinations with real clients, reconnect and
   cross-world transitions; server recipient lists alone do not prove rendering.
6. Cover menu page transitions, visibility combinations and cleanup across
   reconnects on each declared client version.
7. Cover item attributes/metadata combinations through real equipment and
   natural drops; preserve samples and seeds for randomized observations.
8. Expand platform versions only with independent complete gates. Paper
   1.21.4/1.21.11 observations do not expand the 1.21.1 release baseline.

## Exclusions and boundaries that must not be turned into invented features

- `Options.Gravity` is not the official option; use supported `NoGravity` or
  entity-scoped `HasGravity`. `Options.SpawningDistance` is ignored as a radius
  by the pinned native version. Keep their source preservation.
- Root `ArmorToughness` is not an official Mob Option; the built-in stat is
  separate. The DropTables catalog defines no separate `Looting` table option.
- `AIPathfindingMalus: {WATER: -1, FIRE: 0}` is not the native list format and
  `FIRE` is not a PathType. This is invalid input, not a missing alias.
- RandomSpawn equal-entry `Types` and duplicates are native 5.7.2 behavior;
  do not invent explicit per-entry weight syntax merely to close a row.
  DENY-with-Type semantics likewise need native evidence before implementation.
- Paper 1.21.1 `ItemMeta` has no public ItemModel/DamageType component setters;
  Zombie does not expose the tested `ATTACK_SPEED` attribute. Preserve these
  version/entity limits, or scope a separate native adapter explicitly.
- Premium targeter lists/numeric features, external disguise semantics,
  ItemAdder/CraftEngine/MMOItems/MythicCrucible/ModelEngine, custom Mythic Luck
  enchantment/curses and installed Lands evidence are separate integration or
  artifact-contract work. They are not silently included in this internal batch.

## Recommended next implementation batches

1. Complete the remaining M04/M05/M17/M19/M20 Paper/client acceptance and the
   setter contract's selector/nonliving-condition boundaries. Refresh the full
   strict release gate only against the final artifact.
2. Implement M06/M07/M13/M18/M21/M22 with independent native contracts and
   recipient/ownership/UI checks.
3. Implement T02 and T05–T11 with bounded location/block enumeration and
   separate entity-valued versus location-valued dispatch.
4. Complete S01/S03–S10 as distinct runtime/state-machine and conversion slices;
   retain the existing base Aura charge implementation while extending its
   event-specific consumption behavior.

Sources inspected: `SkillCatalog`, `SkillExtras`, `SkillService`, `Targeters`,
`SelectorPlan`, `AiController`, `MythicMobsImporter`, model bridge registrations,
the roadmap and matrix, and pinned native annotations/bytecode. Native JAR
SHA-256: `9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
A literal-name scan found further candidates, but ambiguous aliases, Premium
gates and external effect families are deliberately not promoted to confirmed
missing features here. Completing this checklist alone does not prove the
unbounded official catalog; a complete item-level reference inventory remains
a separate release prerequisite.
