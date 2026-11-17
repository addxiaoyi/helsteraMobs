# Numeric placeholders: precision, rounding, health, and random ranges

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
Explicit inline numeric configuration takes precedence over that display API.

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
Unconfigured text lookup retains Unicode variable names and wildcard permission
nodes; the numeric token grammar does not restrict these existing text keys.

## Upstream behavior and edition boundary

The pinned `EntityNumericPlaceholder` defaults to rounding `-1` and returns
`String.valueOf(number.doubleValue())`. Explicit configuration reads
`rounding/round/r`; Helstera now supports these options for scoped health,
absorption, coordinates, distance/deltas, armor/luck, saturation/exhaustion,
and the `random.float` family. Other numeric fields remain outside this slice. For example,
`<target.hp{rounding=2}>` turns `3.125` into `3.13`, and
`<random.float{r=2}.-0.125to-0.125>` produces `-0.13`. Empty `{}` uses the
configured default of two decimal places. Negative scales retain the exact
value; zero uses integer text, including native Java integer saturation;
positive scales use HALF_UP followed by double text, without padding zeros.
Unknown/duplicate options, malformed configuration, and positive scales above
340 remain unresolved. Text/boolean/variable placeholders and the integer and
`random.float.rounded` families do not accept numeric rounding configuration.
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

## Random families and upgrade

| Syntax | Contract | Constant example |
| --- | --- | --- |
| `<random.AtoB>` / `<random.A-B>` / `<random.N>` | Inclusive 32-bit integers | `<random.-5to-5>` → `-5` |
| `<random.float.AtoB>` / `.A-B` / `.N` | Native float sampling widened to double text | `<random.float.0.0125to0.0125>` → `0.012500000186264515` |
| `<random.float.rounded.AtoB>` / `.A-B` / `.N` | Float sampling with two decimal places, HALF_EVEN | `<random.float.rounded.-0.125to-0.125>` → `-0.12` |

Signed ranges use `to`; a dash range requires nonnegative endpoints. Static
negative numbers retain their sign. Float results use the pinned implementation's
float casts and arithmetic, so a decimal constant can expose its float precision.
The rounded family uses a locale-independent decimal point in Helstera; native
`DecimalFormat` uses the server locale. The fixture checks the tested server's
locale only. Range checks compare type/bounds of independent draws, not equality
of random samples. Invalid/non-finite endpoints or overflowing float spans stay
unresolved; Helstera does not reproduce upstream's invalid-float fallback to `1`.

**Upgrade:** bare `<random.1to10>` previously produced continuous doubles;
it now produces integers. Decimal ranges such as `<random.0.1to0.2>` must use
`<random.float.0.1to0.2>`. Earlier sign stripping could turn negative bounds
positive; that bug is removed. Reversed integer ranges, full-width integer
ranges, scientific float input, and `random.percent/chance` remain Helstera
extensions, not claimed Mythic parity. Random results, configured numeric values, and
fixed two-decimal random text retain their family formatting in `resolveDisplay`.

## Validation and remaining work

The full `./mvnw -o -q -pl helstera-plugin -am verify` build passes 2,243 tests,
zero failures/errors/skips, and SpotBugs. Tests cover stale context ratios,
raw/percentage units, very small values, coordinates/deltas/attributes, explicit
display formatting, multiple recipients of fractional healing, variable text,
and non-finite input. Rounding tests cover aliases, configured defaults, HALF_UP
ties of both signs, scale-zero saturation, display precedence, and invalid
configuration. Random tests cover signed integer/static/dash/to forms, float
precision, HALF_EVEN fixed decimals, extensions, and invalid/overflowing ranges.
Importer-to-runtime tests verify named and positional inline configuration,
signed random values, and fractional-health calculations. Shared Potion parsing
also retains configured dynamic duration/level tokens and rejects invalid results.

Focused Paper 1.21.1-133 fixture
`srv-paper-percent-health-exploratory-20261117-r9` passes 88 assertions with
actual MM 5.7.2: 14 existing identical-source static health comparisons,
38 exact native-string/Helstera-string comparisons, three sampled random
type/bounds checks (32 draws per engine per range), 18 Helstera imported
dynamic skill outcomes, the native Free fallback, and event/isolation/negative/
cleanup checks. Caster health is `0.125/20`; recipient health is `3.125/20`.
Native string and Helstera outputs agree exactly for all 19 health aliases and
19 configured/random examples, including `0.13`, `15.63`, `-5`, `-0.13`, and
`-0.12`. Imported rounded healing produces health `3.255000114440918` and
fractional healing `5.724999904632568`, within `1e-6` of their expected decimal
outcomes because Minecraft stores health as a float. These are Helstera dynamic
outcomes, separate from the edition-gated native numeric skill path.
Reports, sanitized logs, reproduction commands, and hashes are in
[Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md).

Actual Premium-enabled dynamic numeric skill comparison remains unverified.
Other placeholder families, expressions/math, PlaceholderAPI
integration, generic per-recipient evaluation,
timing, other versions, and integration into the full strict release catalog
remain open. This slice does not complete the Mechanics, Skills importer,
Conditions, or Targeters catalogs.
