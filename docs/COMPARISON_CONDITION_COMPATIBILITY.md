# Comparison condition compatibility

This slice covers Distance, YDiff, FieldOfView, IsCaster and
BoundingBoxesOverlap. It compares public MythicMobs 5.7.2
(SHA-256 `9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`)
on Paper 1.21.1-133 with imported and directly authored Helstera skills.
It does not establish parity for the complete Conditions catalog.

## Dispatch and geometry

| Condition | Caster gate | Entity target / trigger | Explicit location target |
| --- | --- | --- | --- |
| Distance | Caster compared with itself | Caster feet to subject feet | Caster feet to target point |
| YDiff | Rejects before outcome inversion | Subject Y minus caster Y | Point Y minus caster Y |
| FieldOfView | Rejects before outcome inversion | Caster eye to subject eye; non-living subjects use entity position | Native 5.7.2 removes a matching point for outcome true; outcome false reverses that filtering |
| IsCaster | Rejects before outcome inversion | UUID equality with caster | Retained unchanged for both outcomes |
| BoundingBoxesOverlap | Rejects before outcome inversion | Caster and subject bounding boxes | Retained unchanged for both outcomes |

These are the actual native `evaluateCaster`, `evaluateTrigger` and
`evaluateTargets` behaviors. A comparison-only predicate has no applicable
caster interface: `!iscaster` still rejects a caster gate. A condition without a
location interface leaves location targets untouched, including under inversion.
Dispatch decisions are carried through the condition registry separately from
ordinary predicate booleans. Helstera boolean composition and nested skill
requirements continue checking subsequent conjuncts after retaining a location;
that composition syntax has regression coverage, not a native equivalence claim.

Entity TargetConditions reject invalid/dead targets before outcome inversion.
Missing triggers reject before inversion; removed trigger entities are still
evaluated. Selected action targets and execution origin do not replace either
comparison subject. Named previews use the same gates, while executed actions
retain their ordinary context, trigger, targets, origin and payload.

Distance checks the world, narrows squared coordinate distance to float as native
MM does, and squares finite threshold endpoints. Thus `d=4` also matches a
coordinate distance of `4.00000001` in the fixture; `d=-4` matches distance four.
Strict `<` and `>` retain their strictness. Range endpoints are ordered before
squaring, including reverse `5to3`; negative squared ranges follow that order.
A different world produces ordinary false, so a false outcome can pass.

YDiff, FieldOfView and BoundingBoxesOverlap have no native world check. FieldOfView
uses eye height and float angle computation, including the native Y rotation.
Coincident eye positions produce no matching cone. BoundingBoxesOverlap
normalizes the full facing vector, including pitch, then shifts the caster box
only along X/Z: flattening before normalization would incorrectly increase the
offset for pitched casters. The implementation clones the box before shifting.

Unknown/duplicate named arguments and non-finite numeric inputs fail binding and
are preserved by migration where unsupported. This is deliberate Helstera input
validation, not a claim that native malformed-input behavior is identical.
Inclusive/equality/inequality operators, scientific scalar distance notation,
XDiff/ZDiff and positional conveniences retain their extension status where the
pinned native parser does not support them. Unscoped AI `distance` continues
using cached target distance and its existing positional comparison convention.

## Evidence

`ComparisonDispatchAudit` calls native framework methods without replacing
predicates or licensing checks. It requires 6,720 matching rows:

- 16 expressions across the five condition families, each with true/false outcome.
- 21 scenes: self, front/back/above/below/side, float boundary, overlap, pitched
  caster, cross-world, eye-level/below-eye items, removed item/living entity,
  missing trigger, mixed/removed targets, and four explicit location targets.
- Named caster/target/trigger gates and inline caster/trigger guards.
- Both migrated source skills and directly authored runtime definitions.

The runner checks the exact row count, every match flag and the final summary
before creating a report. Source/JAR/runner/reference hashes accompany the
report. Temporary entities are removed in `finally`. Existing radius, non-living
trigger and location-guard boundary comparisons remain required. The current
artifact IDs, report/log hashes and full strict release result are recorded in
[Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md).

After building, use an unused revision to reproduce the focused suite:

```powershell
./mvnw -B -pl helstera-plugin -am verify
pwsh -NoProfile -File tools/paper-integration/run-random-spawn-replace-fixture.ps1 -EvidenceRevision r57 -MobRadiusStrict -VanillaCachePath <verified-vanilla-cache>
```

## Open boundaries

Cuboid, faction/owner/parent/line-of-sight and vehicle comparison interfaces,
dynamic distance placeholders, the broader generic entity condition catalog,
FOV angles beyond the existing 0..180 binding range, condition action suffixes
beyond true/false, Premium expressions and other MM/Paper versions remain open.
The complete Conditions and Context Targeters rows remain `mapped-unverified`.

Public MM 5.7.2 gates bracketed targeter-condition activation behind Premium;
this fixture proves the named target-condition framework, not Premium targeter
list execution. Inline `?@` remains a Helstera extension. See the
[trigger contract](TRIGGER_CONDITION_COMPATIBILITY.md) and
[location/radius contract](MOB_RADIUS_CONDITION_COMPATIBILITY.md).
