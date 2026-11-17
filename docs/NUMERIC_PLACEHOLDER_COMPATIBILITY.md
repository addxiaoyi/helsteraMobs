# Numeric placeholder precision and health aliases

Audit date: 2026-11-17. The upstream oracle is MythicMobs 5.7.2, Modrinth
`FTWvGuE5`, SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
The external JAR is not redistributed. Obtain the
[official artifact](https://cdn.modrinth.com/data/NLuc7Jjh/versions/FTWvGuE5/MythicMobs-5.7.2.jar).

## Runtime contract and upgrade

`Placeholders.resolve` and `lookup` now preserve finite numeric values with
`Double.toString`, including health, absorption, ordinary coordinates, distance,
coordinate deltas, numeric attributes, and sampled random ranges. Previously,
many fields were rounded to one decimal before mechanics consumed them:
`0.125` became `0.1`, and `0.0125` became `0.0`. Messages and commands also use
the precise default; large or small doubles may use scientific notation.
`resolveDisplay` is an explicit Java API for compact one-decimal presentation.
It is not an implementation of Mythic's inline rounding configuration.

The following aliases read the live scoped entity, independent of the context's
cached health ratio. Both `caster` and `target` support the listed fields.

| Meaning | Scoped fields | Caster shorthand |
| --- | --- | --- |
| Absolute health | `health`, `hp`, `thp`, legacy `hp.raw` | `c.h` |
| Maximum health | `maxhealth`, `mhp`, legacy `hp.max` | `c.mh` |
| Percentage from 0 to 100 | `healthpercent`, `percenthealth`, `php`, legacy `hp.percent` | `c.ph` |

**Upgrade:** earlier Helstera versions incorrectly interpreted `caster.hp` and
`target.hp` as percentages. Profiles relying on that behavior must use
`caster.hp.percent` / `target.hp.percent`, or the official `healthpercent` /
`php` aliases. With health `3.125/20`, `<target.hp>` now resolves to `3.125`,
whereas `<target.php>` resolves to `15.625`. This also applies to legacy
`mob`/`player` scope aliases. The legacy `%hp%` boss-announcement token retains
its explicit percentage calculation; `skill.health` retains its legacy context
percentage meaning. ShowHealth's separate presentation formatter is unchanged.

Unknown keys, missing entities, exceptions, and non-finite numeric entity data
remain unresolved. `resolveNumber` returns its caller-provided fallback for
unresolved/non-finite values. Validated numeric mechanics skip invalid dynamic
input rather than treating it as zero; this prevents an invalid health value
from becoming a zero-health write. Variable text retains its original value;
it still passes through finite validation when consumed as a number.

## Upstream behavior and edition boundary

The pinned `EntityNumericPlaceholder` defaults to rounding `-1` and returns
`String.valueOf(number.doubleValue())`. Explicit configuration reads
`rounding/round/r`; these options remain unimplemented in Helstera.
Inspect that class and `CasterHealthPlaceholder`, `TargetHealthPlaceholder`,
their max-health/health-percent counterparts, and their alias annotations with
`javap -c -p` / `javap -v` against the pinned artifact.

The tested MM Free build gates `PlaceholderDouble.of` for dynamic numeric
arguments behind `MythicBukkit.isVolatile()`. It logs a Premium requirement and
substitutes static `1`. For example, from recipient health `3.125`, native
`heal{a=<caster.hp>}` yields `4.125` even when caster health is `0.125`.
Helstera deliberately executes the resolved `0.125` and yields `3.25`.
This fallback is an observed edition boundary, not a numeric parity result.
The public `PlaceholderString` API resolves the same health aliases without
that gate. No licensing state is changed or bypassed by the fixture.

## Validation and remaining work

The full `./mvnw -o -q -pl helstera-plugin -am verify` build passes 2,235 tests,
zero failures/errors/skips, and SpotBugs. Tests cover stale context ratios,
raw/percentage units, very small values, coordinates/deltas/attributes, explicit
display formatting, multiple recipients of fractional healing, variable text,
and non-finite input. Importer-to-runtime tests verify the aliases survive
production conversion and preserve small numeric results.

Focused Paper 1.21.1-133 fixture
`srv-paper-percent-health-exploratory-20261117-r7` passes 60 assertions with
actual MM 5.7.2: 14 existing identical-source static health comparisons,
19 exact native-string/Helstera-string alias comparisons, 12 Helstera imported
dynamic skill outcomes, the native Free fallback, and event/isolation/negative/
cleanup checks. Caster health is `0.125/20`; recipient health is `3.125/20`.
Native string and Helstera outputs agree exactly for all 19 aliases, including
`0.125`, `0.625`, and `15.625`. The dynamic imported cases produce values such
as `3.25`, `5.625`, `0.625`, and `2.734375` without one-decimal rounding.
Reports, sanitized logs, reproduction commands, and hashes are in
[Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md).

Actual Premium-enabled dynamic numeric skill comparison remains unverified.
Other placeholder families, inline rounding, expressions/math, PlaceholderAPI
integration, signed random-range grammar, generic per-recipient evaluation,
timing, other versions, and integration into the full strict release catalog
remain open. This slice does not complete the Mechanics, Skills importer,
Conditions, or Targeters catalogs.
