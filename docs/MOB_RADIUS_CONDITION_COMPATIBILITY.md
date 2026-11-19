# MobsInRadius compatibility

The reference is the pinned public MythicMobs 5.7.2 build on Paper 1.21.1-133.
Its effective defaults differ from the nominal defaults in its constructor:
`SkillCondition.conditionVar` starts at `0` and takes precedence for both amount
and radius. The following forms now share one parser for migration and direct
named binding:

| Source | Runtime condition |
| --- | --- |
| `mobsInRadius` | `mobs-in-radius 0 0` |
| `mobsInRadius 2` | `mobs-in-radius 2 2` |
| `mobsInRadius{t=ZOMBIE} 2` | `mobs-in-radius 2 2 ZOMBIE` |
| `mobsInRadius{r=5;t=ZOMBIE} >1` | `mobs-in-radius >1 5 ZOMBIE` |
| `mobsInRadius{a=1;r=5} 2` | `mobs-in-radius 1 5` |

Named arguments override the legacy value independently. A comparison suffix
without a named radius cannot supply a numeric radius and is preserved as
unsupported. The normalized kebab-case runtime form keeps separate positional
amount, radius and optional type-list arguments.

Amounts are exact scalars, strict comparisons, or inclusive ascending ranges.
Fractional bounds are valid, e.g. `>1.5` and `1.5to2.5`; the population itself
is an integer. Unknown or duplicate argument names, malformed lists, reversed
ranges, negative/nonfinite literals and malformed radius expressions are not
silently defaulted. Migration preserves the original text and direct binding rejects it.

## Dynamic radius

Named radii accept the shared finite numeric expression grammar, including
`r=min(<caster.hp>, 5)` and `r=<target.hp{r=0}>`. Expressions containing spaces
and nested rounding braces survive migration and direct binding. Radius values
are resolved again at each evaluation; amount and type filters remain constant.

The metadata/caster path resolves target placeholders against the **caster**,
matching the entity argument used by the pinned native condition. A selected
skill target does not change that numeric scope. The location path evaluates
constant formulas without metadata; entity, variable and random placeholders
remain unresolved there. Negative, unresolved and nonfinite results, including
overflow of the squared radius, reject the whole condition even under negation.

This is an explicit extension relative to the pinned public MM 5.7.2 build:
its numeric factory replaces dynamic radii with static `1` behind the Premium
gate. The focused fixture observes this on actual native conditions, checks
the public `PlaceholderString` health/rounding results, and separately asserts
Helstera's dynamic execution. It neither changes licensing state nor proves
Premium numeric-condition parity. Native parsing failures may also fall back
to `1`; Helstera deliberately rejects them. See the
[numeric compatibility contract](NUMERIC_PLACEHOLDER_COMPATIBILITY.md).

Bare random intervals such as `2-3` and `2to3` sample a fresh double on every
evaluation, including the location path without metadata. The native factory
recognizes intervals before the Premium gate. Helstera preserves endpoint order
and uses the same `start + u * (end - start)` calculation: equal endpoints are
deterministic, and reversed intervals such as `3to2` are valid. Both endpoints
must be nonnegative with finite squares. Fractional endpoints follow the native
interval grammar; the second endpoint starts with a digit. Negative endpoints,
repeated separators and malformed ranges are rejected rather than defaulting.
Use `(2-3)` for subtraction (which produces a rejected negative radius here).
Native/runtime sample bounds and resampling checks do not claim matching random
draws or verify a distribution. Native condition comparisons use populations
that remain constant throughout each tested interval.

## Context and population

| Context | Center | Caster exclusion | Eligible types |
| --- | --- | --- | --- |
| Model instance without an explicit location target | Caster entity location; execution origin is ignored | UUID | Bukkit entity types or active registered MythicMob internal names |
| Explicit location target, or execution origin without an instance | Supplied location | None | Active registered MythicMob internal names |

This asymmetry follows the two native `check` overloads. In particular,
`t=ZOMBIE` on the location path does not count ordinary vanilla Zombies. A
registered MythicMob named `ZOMBIE` can still be matched by its internal name.
The caster path can count ArmorStands when `t=ARMOR_STAND` is specified; it is
not restricted to Bukkit `Mob` instances. Both paths use spherical distance,
including the radius boundary, and do not impose a 512-entity count limit.
An omitted type list selects nothing; it is not a wildcard.

Radius queries read the native active registry by UUID. They do not use the
API helper's `getMythicMobInstance(Entity)`, which can reload an entity from its
persistent data. A custom mob removed from the active registry therefore stops
counting even while its persistent Mythic marker remains; registering its saved
ActiveMob restores the count. A caster-path Bukkit type match still counts
independently of that registry. A missing active-query interface rejects evaluation even before querying an
empty scene; entity lookup failures also reject rather than pretending that the
population is zero.

Native MythicEntity aliases are valid filter inputs, resolved through the
installed MM adapter. A registered custom name takes precedence over an alias:
the focused fixture registers a COW named `MUSHROOM_COW` and counts it by name.
An ordinary baby Husk is not selected solely by `BABY_HUSK`; the native condition
requires an active Mythic mob before that part of the predicate. Valid aliases
with no selected entities still support zero-count and negated conditions.
Five native aliases and 216 comparisons cover active, unregistered and restored
states, three execution scopes, exact-zero/exact-one amounts and negation.

Missing context, invalid coordinates, failed world queries, unavailable custom
type registries and failed custom type lookups reject evaluation at the
condition-composition boundary. Negating such a failure does not permit the
skill. Unregistered custom names are conservatively rejected even with an
upper-bound or zero-count expression. Native invalid-type warnings are not
treated as evidence of valid input.

## Scope and remaining work

Inclusive `>=`/`<=` comparisons, namespaced Bukkit names and case-insensitive
custom-name lookup remain Helstera extensions; native equivalence for these
extensions is not claimed. Premium-enabled radius evaluation, the complete
MythicEntity alias catalog, ModelEngine hitbox-parent lookup and other MythicMobs
versions remain unverified. In the pinned native implementation, `isLoadedMob`
delegates to `getActiveMob().isPresent()`. With a stable registry, a loaded entity
therefore has an ActiveMob; the later native MythicEntity comparison fallback
for a missing ActiveMob was not reached. The fixture does not fabricate an
inconsistent registry to claim that fallback is verified.
`MobsNearOrigin` is a targeter in the pinned MM 5.7.2 build, with no same-named
entry in its built-in condition registry. The focused fixture reads that live
registry and resolves `@MobsNearOrigin` to the actual native targeter class.
Helstera retains its `mobs-near-origin` **condition extension**; prior statements
calling it an official MM condition are superseded. This does not establish
absence of third-party custom conditions or behavior in other MM versions.

The native fixture calls the pinned constructors, actual deferred type resolver
and both check overloads. It compares migrated and directly bound production
conditions with real Bukkit entities and an API-spawned custom MythicMob.
It does not prove every native skill/target-condition dispatch route.

Reproduce the focused strict comparison after building the production JAR:

```powershell
pwsh -NoProfile -File tools/paper-integration/run-random-spawn-replace-fixture.ps1 `
  -EvidenceRevision r43 -MobRadiusStrict `
  -VanillaCachePath srv-paper-random-spawn-replace-20261119-r21/cache/mojang_1.21.1.jar
```

Use a fresh revision for each run. `-MobRadiusAudit` instead records mismatches
as exploratory observations and cannot produce a passing release claim.
See [Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md) for verified
artifacts and the current full-catalog release report.
