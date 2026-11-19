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

FieldOfView accepts every finite angle and rotation without clamping or wrapping
the supplied angle. The predicate compares the measured angle with half that
value: `a=-90` matches no direction, zero includes exactly forward, and
large angles such as 720 cover all non-coincident directions. Coincident eyes still
produce no match. Rotation is passed directly to the native-style Y-axis
trigonometric calculation, including negative and multiple-turn rotations.

Float precision remains observable at the half-plane and full-circle boundaries.
A perpendicular subject fails at `a=180` and `a=180.000005`, then passes at
`a=180.000006`. A directly rearward subject fails at `a=360` and `a=360.00001`,
then passes at `a=360.00002`. These are measured-angle rounding boundaries;
there is no special full-circle shortcut. Caster rejection, outcome inversion
and native matching-location removal remain the same at these wider angles.

Unknown/duplicate named arguments and non-finite numeric inputs fail binding and
are preserved by migration where unsupported. This is deliberate Helstera input
validation, not a claim that native malformed-input behavior is identical.
Inclusive/equality/inequality operators, scientific scalar distance notation,
XDiff/ZDiff and positional conveniences retain their extension status where the
pinned native parser does not support them. Unscoped AI `distance` continues
using cached target distance and its existing positional comparison convention.

## Dynamic Distance

Distance now accepts placeholder strings in exact values, comparisons and range
endpoints, including `d=<random.4to4>`, `d=<<caster.hp>` and
`d=0to<target.hp{r=0}>`. The doubled `<` in the second example is the strict
comparison followed by a placeholder. Nested numeric rounding configuration
survives direct binding and migration. Substitution happens on each evaluation;
identical tokens in one expression resolve once, matching native string replacement.
The resulting text must be a finite numeric comparison/range. This uses the
public native `PlaceholderString` path, not the Premium-gated numeric factory.

| Dispatch | Placeholder context |
| --- | --- |
| Named/inline caster condition | Metadata plus caster as the placeholder's target entity; a selected action target does not replace it |
| Entity TargetConditions / TriggerConditions / inline trigger | No metadata or entity context; general placeholders such as `random` can resolve |
| Explicit location TargetConditions | No metadata or entity context |
| Unscoped Helstera execution | Existing execution context and cached distance convention; extension behavior |

Consequently, `d=<<target.hp>` in a caster condition reads the caster's live
health. Entity placeholders remain unresolved in native target/trigger/location
comparisons. A parse failure rejects before true/false inversion; a false outcome
cannot turn it into a pass. Native target-set filtering catches that error outside
the entire filter, so a mixed set containing a same-world unresolved target and
a cross-world target rejects the skill, even if the cross-world target would pass
the false outcome. Helstera now aborts that target-set gate and leaves the caller's
targets intact. This failure signal also propagates through condition composition.

Same-world validation precedes resolution. A cross-world-only subject produces
ordinary false, so the false outcome can pass without resolving the placeholder.
Invalid/dead targets and missing triggers retain their existing dispatch rules.
No arithmetic is evaluated inside a dynamic distance string: expressions such as
`<caster.hp>+1` are preserved/rejected at binding. Malformed options, duplicate or
unknown arguments, missing values, and nonfinite resolved numbers fail closed.

The dynamic native suite contains 14,560 matching rows: 28 expressions, 26 scenes,
five named/inline modes, both outcomes, and imported/direct execution. It checks
health aliases and rounding, caster health changing from 3.125 to 0.4/0.6 on the
same compiled condition, constant integer/float/rounded random tokens, strict
comparisons and range endpoints, removed/non-living triggers, explicit points,
and mixed-world/removed target sets in both orders. Native unresolved-input error
logs are expected observations; their returned framework result is compared with
runtime preview and execution. Eight additional trigger health/max-health/position
expressions contribute 4,160 rows. Their native conditions are constructed at
enable and evaluated after real scheduler ticks, allowing the native delayed
trigger delegates to initialize. See the [trigger placeholder contract](TRIGGER_PLACEHOLDER_COMPATIBILITY.md).
This does not prove arbitrary variable/trigger placeholder families, stochastic
distribution equivalence, non-living casters,
arbitrary formulas or other versions.

## Evidence

`ComparisonDispatchAudit` calls native framework methods without replacing
predicates or licensing checks. It requires 14,280 matching rows:

- 34 expressions across the five condition families, each with true/false outcome.
  The original 16 expressions remain; 18 more cover negative/signed-zero angles,
  180/360-degree float boundaries, 181/270/720-degree cones, the maximum finite
  double angle, and positive/negative multiple-turn rotations (7,560 new rows).
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
pwsh -NoProfile -File tools/paper-integration/run-random-spawn-replace-fixture.ps1 -EvidenceRevision r60 -MobRadiusStrict -VanillaCachePath <verified-vanilla-cache>
```

## Open boundaries

Cuboid, faction/owner/parent/line-of-sight and vehicle comparisons have additional
scoped evidence in the [relational contract](RELATIONAL_CONDITION_COMPATIBILITY.md).
Broader distance placeholder families, the generic entity condition catalog,
condition action suffixes beyond true/false, Premium expressions and other
MM/Paper versions remain open. Wider finite FOV angles have scoped evidence;
non-living caster combinations and malformed native numeric fallbacks remain
outside the parity claim.
The complete Conditions and Context Targeters rows remain `mapped-unverified`.

Public MM 5.7.2 gates bracketed targeter-condition activation behind Premium;
this fixture proves the named target-condition framework, not Premium targeter
list execution. Inline `?@` remains a Helstera extension. See the
[trigger contract](TRIGGER_CONDITION_COMPATIBILITY.md) and
[location/radius contract](MOB_RADIUS_CONDITION_COMPATIBILITY.md).
