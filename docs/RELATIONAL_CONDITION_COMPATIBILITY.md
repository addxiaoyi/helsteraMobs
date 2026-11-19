# Cuboid and relational condition compatibility

This contract extends the [comparison-condition contract](COMPARISON_CONDITION_COMPATIBILITY.md)
with Cuboid, Owner, IsParent, FactionSame, LineOfSight and VehicleIsDead.
The reference remains public MythicMobs 5.7.2, SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`,
on Paper 1.21.1-133. Complete condition-catalog parity remains open.

## Native dispatch

Cuboid implements both location and comparison interfaces. Entity targets and
triggers reach its entity comparison, where `relative=true` offsets the box by
the actual caster position. Caster gates and explicit location targets reach
its single-location predicate first, where `relative` is ignored. Execution
origin never replaces the caster reference for these scoped native paths.
Coordinates are compared without a world check. BoundingBox containment includes
minimum faces and excludes maximum faces, and reversed corners are normalized.
A box with zero width on any axis contains no point.

The other five predicates implement only entity comparison. Caster gates reject
before inversion; explicit location targets remain untouched for true and false
outcomes. They use the actual trigger, including Item and removed entities, in
trigger scope. Missing triggers and invalid/dead entity targets retain the
framework's earlier rejection rules.

| Condition | Scoped comparison |
| --- | --- |
| Owner | Target/trigger UUID equals the caster's stored owner UUID. Native reference uses a registered ActiveMob owner. |
| IsParent | Target/trigger UUID equals the caster's stored parent UUID; removal does not change this equality. |
| FactionSame | Exact case-sensitive faction-string equality. Missing faction becomes `NONE`; an empty string remains distinct. |
| LineOfSight | Living caster's Bukkit `hasLineOfSight(subject)` result, including non-living subjects. A non-living caster fails before outcome inversion. |
| VehicleIsDead | Subject has a vehicle that is dead or invalid. A missing vehicle returns false. |

Helstera keeps original faction spelling for condition comparisons while
continuing to normalize faction IDs for combat alliances and friendly-fire
rules. Consequently two unassigned entities can satisfy FactionSame while
remaining hostile under the default combat rules. FactionTable implementations
can provide `comparisonFactionOf`; its default uses their existing `factionOf`.

Owner/parent comparisons use Helstera's production persistent relationship
storage. This fixture compares equivalent explicitly configured native and
Helstera relationships; it does not prove native relationship discovery,
external-plugin bridging, summon wiring or cross-restart persistence.
Native Owner does not use vanilla tameable ownership as a fallback. Scoped
Helstera Owner therefore reads only the explicit relationship record.

Cuboid rejects malformed/duplicate/unknown arguments, incomplete corners,
non-finite coordinates and invalid boolean values at binding/migration.
That validation is a Helstera contract, not native malformed-input equivalence.
World-prefixed corners, location selectors and unscoped AI Cuboid retain their
existing Helstera extension geometry, including inclusive bounds. Unscoped
legacy owner/parent/faction/vehicle behavior remains separately scoped.

## Verification

The focused suite requires 6,820 matching relational rows: 11 expressions,
31 scenes, five named/inline modes, true/false outcomes, and imported/direct
skill execution. It invokes real native `evaluateCaster`, `evaluateTrigger` and
`evaluateTargets`, and compares named previews, action counts, retained UUIDs,
location targets, trigger, origin, payload and normal execution scope.

Six Cuboid expressions exercise absolute/relative boxes, reversed corners,
zero-size bounds and faces. Shared scenes include self, front/back/above/below,
side, overlapping positions, cross-world subjects, non-living items, removed
subjects, missing triggers, mixed targets and explicit positions. Additional
scenes configure related live/removed items and living entities, clear owner and
parent, change faction to absent/empty, unregister/reregister the native caster,
place a stone obstruction, and mount/dismount a subject. Native active mobs are
created through public spawn APIs; owner/parent/faction state uses public setters.
The suite restores the global faction table and exact obstruction block states,
then unregisters and removes its temporary mobs.

VehicleIsDead has real Paper negative controls for absent/live vehicles and
dismount cleanup. Dead/invalid vehicle references have unit coverage only:
normal Paper removal detaches passengers before the next scene. Non-living
caster line-of-sight adapter failure is likewise unit evidence only.
Cross-module tests also verify faction combat behavior, removed relation UUIDs,
Cuboid interface precedence/half-open boundaries, and malformed argument handling.

The preceding 6,720 comparison rows, 864 non-living trigger rows, 576 radius
rows and 672 location-guard boundary rows remain mandatory. Current artifact,
report hashes, full strict verification and diagnostic attempts are recorded in
[Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md).

## Remaining work

Dynamic distance placeholders, wider FOV angles, player faction providers,
non-living caster combinations, relationship lifecycle/restart, the remaining
entity predicates and condition action suffixes still require broader evidence.
Premium targeter lists and other MM/Paper versions retain their existing limits.
All eleven entity-comparison classes in the pinned artifact now have scoped
framework coverage; this does not complete their entire input or lifecycle
surface, the Conditions catalog, or the overall parity goal.
