# Numeric expression compatibility

This slice implements finite numeric expressions for the shared numeric mechanic
factories, fractional-health mechanics, and Potion duration/level. The reference
is the pinned MythicMobs 5.7.2 Modrinth `FTWvGuE5` artifact on Paper 1.21.1-133.
It bundles exp4j 0.4.8 and adds four functions and five comparison operators.
Helstera packages its own relocated exp4j, so expression evaluation does not
require MythicMobs and does not share its library classes.

## Supported input

| Syntax | Example | Result |
| --- | --- | --- |
| Arithmetic and precedence | `2+3*4` | `14` |
| Right-associative power | `2^3^2` | `512` |
| Unary minus below power | `-2^2`, `(-2)^2` | `-4`, `4` |
| Implicit multiplication | `2(3+4)` | `14` |
| exp4j functions/constants | `sin(pi/2)+sqrt(9)` | `4` |
| Native custom functions | `max(2,min(8,3))`, `atan2(0,-1)` | `3`, pi |
| Modulo with math enabled | `(7%4)` | `3` |
| Numeric comparisons | `(2<3)`, `(2<=2)`, `(3>2)`, `(2>=2)`, `(2==2)` | `1` |
| Rounded placeholders in formulas | `max(1, <target.hp{r=0}> / 2)` | Per-recipient value |
| Continuous random function | `random(-2,3)` | `-2 <= value < 3` |

All five comparison operators are left associative at precedence 499, below
addition/subtraction (500). They return `1` or `0`; they do not introduce the
condition system's boolean grammar. For example, `(2>1<2)` evaluates to `1`.
The numeric `random(min,max)` function follows the native continuous formula;
the separate `<random...>` placeholder families retain their documented
integer/float/rounding contracts.

Native numeric parsing enables math when the source contains `+`, `-`, `*`,
`/`, or `^`, or both `(` and `)`. Bare `1==1`, `7%4`, `pi`, and `e` do not
enable it; wrap them in parentheses. The pinned parser also recognizes leading
`=`/`eval` as math markers but passes those strings unchanged to exp4j, which
rejects them. Helstera rejects these unsupported forms during binding/import
instead of guessing a different grammar.

Placeholders are replaced as text, once per distinct spelling in each numeric
argument at each invocation. Values are not automatically parenthesized:
with a variable containing `-2`, `<skill.var.value>^2` yields `-4`, while
`(<skill.var.value>)^2` yields `4`. Each recipient resolves its own arguments.
Repeated random placeholder tokens share a sample within one argument;
separate arguments and `random(...)` function calls remain independent.

The generic `Placeholders.resolveNumber` helper retains its pre-existing single
token behavior, including Unicode and arbitrary variable names. Shared mechanic
specs retain their stricter numeric token grammar. A single resolved value must
be finite numeric text; formula text in a variable is evaluated only when the
source expression itself enables math.

Example Mythic inputs:

```yaml
FormulaHeal:
  Skills:
    - 'heal{a=max(1, <target.hp{r=0}> / 2)} @Targets'
FormulaFire:
  Skills:
    - 'setfire{(<target.hp> >= 4) * 20} @Targets'
FormulaPotion:
  Skills:
    - 'potion{t=HASTE;d=(20 + <target.hp{r=0}>);l=max(1, <target.hp>/2)} @Targets'
```

Migration quotes canonical positional arguments containing whitespace, including
Potion formulas. Inline rounding configuration and comparison `=` signs remain
part of the expression. Direct positional arguments containing spaces must also
be quoted, for example `heal-targets "max(1, <target.hp> / 2)" @Target`.

## Failure and edition boundaries

Validation checks syntax without evaluating the formula, so a runtime domain
error such as `1/0` or `sqrt(-1)` can bind but cannot mutate an entity. Missing
placeholders, invalid resolved expressions, and non-finite results skip the
affected recipient. Multi-field numeric mechanics validate all fields before
mutation; Potion validates type, duration, and level before forced removal of
an existing effect. Malformed source syntax is preserved with migration warnings.
Helstera deliberately does not reproduce MM's invalid-input fallback to `1`.

Potion retains its existing bounded integer contract: duration must be an exact
integer from 1 through `Integer.MAX_VALUE`, and level an exact integer from 0
through `Integer.MAX_VALUE`. Fractional, negative, overflowing, and non-finite
results are skipped. This is not a claim of native `PlaceholderInt` truncation
parity. Fractional-health units remain fractions (`0.1` means 10%).

MM Free gates nonliteral numeric skill arguments behind Premium. The native
comparison probe invokes MM's loaded exp4j builder with its own function and
operator arrays for 19 deterministic formulas, independently of that factory.
This verifies the math engine, not a Premium-enabled numeric skill execution.
The original Free fallback and static native skill comparisons remain covered.

The full Maven regression suite passes 2,261 tests, with no failures, errors,
or skips, and SpotBugs passes. Focused Paper evidence and exact artifact hashes
are recorded in [STARTUP_EVIDENCE.md](../tools/paper-integration/STARTUP_EVIDENCE.md).
The numeric and Potion probes exercise imported formulas with real entities,
recipient isolation, invalid-result no-ops, and cleanup. Additional timing probes
verify live Bukkit delay/repeat execution: each invocation re-reads recipient
health/absorption and shared variables; removed targets are skipped, invalid
Potion inputs preserve active effects, repeat counts are bounded, and cancelled
continuations leave state unchanged. Temporary chunk tickets keep entities
loaded for these tests and are released during cleanup.

Earlier full strict fixture `srv-paper-numeric-potion-strict-20261117-r4` runs its
release artifact through all 470 probes (451 passed, 19 expected rejections,
zero incomplete). The catalog requires all 38 numeric and 28 Potion assertions;
missing assertions or cleanup cannot be masked by an aggregate `passed=true`.
It additionally requires all 19 numeric and 22 Potion timing assertions.
The strict fixture does not load MM; native comparisons retain separate evidence.

The subsequent [Potion lifecycle contract](POTION_LIFECYCLE_COMPATIBILITY.md)
adds static-argument native comparisons for expiry, hidden effects, force,
cancellation, and instant effects. It preserves the duration/level bounds above
and does not claim Premium dynamic numeric skill equivalence.

Actual Premium dynamic numeric skill comparison, remaining mechanic fields,
other placeholder families/PlaceholderAPI, other timing/condition interactions, client
effects, other Paper/MM versions, and release integration of native comparisons remain
open. Conditions, variables, drop chances, and other existing numeric consumers
are not automatically promoted to this argument contract. Broad matrix statuses
remain unchanged.
