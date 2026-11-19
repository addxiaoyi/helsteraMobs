# Trigger condition compatibility

`TriggerConditions` and inline `?~` evaluate the triggering entity, including
non-living entities. The trigger is independent of the caster, selected entity
targets, explicit location target and execution origin. Missing triggers reject
the gate before inversion; removed triggers are still passed to the predicate.
TargetConditions retain their separate validity/death check before inversion.

The reference is public MythicMobs 5.7.2, SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`,
running on Paper 1.21.1-133. Its `SkillCondition.evaluateTrigger` dispatches in
this order: IEntityCondition, IEntityComparisonCondition, ILocationCondition,
ILocationComparisonCondition, IEntityLocationComparisonCondition. Each uses the
actual trigger entity or its location. There is no general LivingEntity gate.
This implementation and evidence cover a subset of those interfaces.

## Implemented behavior

- Entity type checks use the trigger even for Item and Arrow entities.
- Shared living-entity predicates return no living subject for Item/Arrow;
  they cannot fall back to the caster or selected target. `isLiving` also
  excludes ArmorStand, as the native predicate does.
- Absolute `health` uses zero for a non-living trigger, including absorption.
  Percentage predicates require a living health denominator; the context does
  not inherit a selected entity's health ratio.
- Shared location/environment predicates resolve the trigger's location. Radius
  conditions use their existing scope-aware native location path. Comparison
  helpers retain the original caster/origin rather than replacing both sides.
- On-ground, water/lava and basic entity-state predicates read the trigger.
  Glowing, dead, gravity, validity and related state predicates no longer read
  the caster in trigger scope. Water and glowing/dead predicates have trigger
  regression coverage only, not native Paper equivalence; other basic-state
  combinations still need focused non-living evidence.
- Trigger condition contexts carry TRIGGER scope and an empty active living
  target for non-living triggers. Custom predicates can read `ctx.trigger()`.
  Actions receive their ordinary execution context with original targets,
  origin, skill tree, event, damage cause, payload and audience.

## Verification scope

The focused fixture adds 864 comparisons: 12 condition expressions, nine
trigger scenes, true/false outcomes, named/inline gates and imported/direct
execution. It calls the actual native `evaluateTrigger` method and compares
action counts, retained context, and named-skill preview results. The conditions
are EntityType (Item/Arrow), IsLiving, Health (zero/positive), Height (exact/high),
OnGround, World, and MobsInRadius (custom zero/one and vanilla zero).
Scenes cover near/far Items and Arrows, ArmorStand, a live custom mob, removed
Item, removed living entity, and missing trigger.

The runner requires all 864 matching rows and their summary; failure aborts
report creation. All temporary entities are removed in `finally`. Existing
576 scope-dispatch comparisons, 672 location-guard boundary comparisons and
radius/RandomSpawns controls remain required. See the current
[Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md) for the exact
artifact, fixture IDs and hashes.

Cross-module regressions additionally test missing-trigger inversion, action
event/tree/audience preservation, and deliberately conflicting caster/target
values. Water/state aliases are not represented as native comparison evidence.

To rerun after building the plugin, choose an unused evidence revision:

```powershell
./mvnw -B -pl helstera-plugin -am verify
pwsh -NoProfile -File tools/paper-integration/run-random-spawn-replace-fixture.ps1 -EvidenceRevision r57 -MobRadiusStrict -VanillaCachePath <verified-vanilla-cache>
```

## Remaining work

Distance, YDiff, FieldOfView, IsCaster and BoundingBoxesOverlap now have scoped
framework evidence in the [comparison contract](COMPARISON_CONDITION_COMPATIBILITY.md).
The remaining comparison interfaces remain unverified, as do the rest of the condition catalog, action
suffixes beyond true/false, placeholders on non-living subjects, Premium
expressions and other MM/Paper versions. Unknown native conditions are not
treated as successful: Helstera continues to reject/preserve unsupported input.
The complete Conditions and inline-condition matrix rows remain
`mapped-unverified`. Non-living trigger conditions do not imply that ordinary
entity targeters or all mechanics accept non-living action targets.

Public MM 5.7.2 gates bracketed targeter-condition activation behind Premium;
Helstera location filtering has a separate condition-library reference.
`?@` is a Helstera extension, not native syntax equivalence. See
[the location/radius contract](MOB_RADIUS_CONDITION_COMPATIBILITY.md).
