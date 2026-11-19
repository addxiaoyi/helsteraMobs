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
ranges, negative/nonfinite numbers and placeholder radii are not silently
defaulted. Migration preserves the original text and direct binding rejects it.

## Context and population

| Context | Center | Caster exclusion | Eligible types |
| --- | --- | --- | --- |
| Model instance without an explicit location target | Caster entity location; execution origin is ignored | UUID | Bukkit entity types or registered MythicMob internal names |
| Explicit location target, or execution origin without an instance | Supplied location | None | Registered MythicMob internal names |

This asymmetry follows the two native `check` overloads. In particular,
`t=ZOMBIE` on the location path does not count ordinary vanilla Zombies. A
registered MythicMob named `ZOMBIE` can still be matched by its internal name.
The caster path can count ArmorStands when `t=ARMOR_STAND` is specified; it is
not restricted to Bukkit `Mob` instances. Both paths use spherical distance,
including the radius boundary, and do not impose a 512-entity count limit.
An omitted type list selects nothing; it is not a wildcard.

Missing context, invalid coordinates, failed world queries, unavailable custom
type registries and failed custom type lookups reject evaluation at the
condition-composition boundary. Negating such a failure does not permit the
skill. Unregistered custom names are conservatively rejected even with an
upper-bound or zero-count expression. Native invalid-type warnings are not
treated as evidence of valid input.

## Scope and remaining work

Inclusive `>=`/`<=` comparisons, namespaced Bukkit names and case-insensitive
custom-name lookup remain Helstera extensions; native equivalence for these
extensions is not claimed. Placeholder-backed radius evaluation, native
MythicEntity aliases outside Bukkit names, the native loaded-entity fallback
without an ActiveMob, and other MythicMobs versions remain unverified.
`MobsNearOrigin` is a separate condition and is not covered by this contract.

The native fixture calls the pinned constructors, actual deferred type resolver
and both check overloads. It compares migrated and directly bound production
conditions with real Bukkit entities and an API-spawned custom MythicMob.
It does not prove every native skill/target-condition dispatch route.

Reproduce the focused strict comparison after building the production JAR:

```powershell
pwsh -NoProfile -File tools/paper-integration/run-random-spawn-replace-fixture.ps1 `
  -EvidenceRevision r35 -MobRadiusStrict `
  -VanillaCachePath srv-paper-random-spawn-replace-20261119-r21/cache/mojang_1.21.1.jar
```

Use a fresh revision for each run. `-MobRadiusAudit` instead records mismatches
as exploratory observations and cannot produce a passing release claim.
See [Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md) for verified
artifacts and the current full-catalog release report.
