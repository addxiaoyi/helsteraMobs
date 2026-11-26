# Random locations near origin, inherited targets and caster

Baseline: Paper 1.21.1 / public MythicMobs 5.7.2, native SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
T09 has runtime selection, positional skill dispatch and importer support.

| Selector and aliases | Sampling centers | Vertical sampling | Collection |
| --- | --- | --- | --- |
| `randomLocationsNearOrigin`, `RLO`, `randomLocationsOrigin`, `RLNO` | Skill origin | Keep origin Y | Unique positions |
| `randomLocationsNearTargets`, `RLNT` | Every inherited entity, including nonliving entities; use inherited locations when entity collection is empty | Keep each center's Y | Preserve duplicate positions |
| `randomLocationsNearCaster`, `RLNC`, `randomLocations` | Caster's current position | Center Y minus 1, inclusive, to Y plus 1, exclusive | Unique positions |

Empty inherited selection does not fall back to the caster or origin. The
native metadata setters replace the opposing entity/location collection;
switching from location selection back to entity selection uses the entities.
Repeated inherited inputs each receive their own sampling budget.

## Native numeric arguments

| Argument aliases, in priority order | Default | Parsing |
| --- | --- | --- |
| `amount`, `a` | 5 | Public PlaceholderInt for all three selectors |
| `radius`, `r`, `maxradius`, `maxr` | 5 | Static integer for origin/caster; public PlaceholderInt for inherited targets |
| `minradius`, `minr` | 0 | Static integer for origin/caster; public PlaceholderInt for inherited targets |
| `spacing`, `s` | 0 | Static integer for all three selectors |

The first present alias wins, including when its value is invalid. Static
integer parsing retains its default for invalid, quoted, ranged, dynamic or
overflowing text. Public PlaceholderInt accepts integers and supported inclusive
integer ranges; other public-build inputs fall back to 1. Numeric placeholders
are not evaluated as a Premium feature. This means `r="2"` yields radius 5
around origin/caster and radius 1 around inherited targets. `a="3"` yields 1.

Nonpositive amounts return no points. Each center can make at most `amount²`
attempts to accept `amount` points. A minimum-distance or spacing rejection
consumes an attempt. An accepted duplicate consumes an acceptance slot even
when the origin/caster collection discards the duplicate. The inherited-target
collection retains duplicates through post-filtering and direct positional
action dispatch; a later selector such as `@targetedLoc` can apply its own
deduplication contract.

## Sampling, collision checks and filtering

X and Z are independently uniform over `[center - radius, center + radius)`.
This is a square, not a disc. Radius zero or negative becomes 1 inside the
native spawn-location helper. The caster form also samples Y as shown above;
origin and target forms do not sample Y. Sampling resets yaw and pitch to zero.

The helper tests `Material.isSolid()` at the sampled point. It redraws all
sampled axes on a solid block, with ten retries. If the tenth redraw is reached,
that candidate is discarded without testing it and the helper returns exactly
`(center.x, center.y + 1, center.z)`, even if that point is solid too. There is
no ground-height adjustment, support-block requirement, headroom check or
passability substitute in these native calls. Water and other nonsolid blocks
are valid samples. The runtime uses actual Paper block access, which can load
chunks, and does not force chunks to remain loaded.

Minimum distance is a 3D squared distance to the unmodified center. A positive
minimum rejects distances strictly below its square. Origin/caster use native
32-bit integer multiplication, including its overflow; inherited targets use
the double square. Positive spacing uses a native 32-bit square and compares
the unmodified candidate against every previously accepted **mutated** result,
across all inherited centers. Thus offsetting each accepted point can allow
another identical unmodified candidate through spacing. Nonpositive minimum
or spacing disables that check.

Accepted points then receive the shared native location mutation (offsets,
coordinate overrides, centering and orientation). Block allow/ignore filters
and the final static sorting/limit run after the entire sampling pass. These
filters do not refill a reduced collection. Public native inline conditions
are ignored; Helstera's supported positional guards are an explicit extension.

## Explicit runtime bounds

The runtime rejects the complete query if its requested total attempt budget
exceeds 262,144, actual block reads exceed 262,144, spacing comparisons exceed
262,144, or collected results exceed 4,096. Counts use wide arithmetic so
`amount²` cannot overflow into accidental acceptance. These are bounded-work
extensions, not native limits, and do not guarantee an elapsed-time limit for
Paper chunk loading. Invalid/nonfinite coordinates and API argument/state
failures return no result. Native cross-world spacing throws; the runtime
contains that error and returns an empty collection. Zero-spacing multiworld
selection remains supported.

## Evidence and acceptance boundary

`./mvnw -q verify` passes 3,068 tests with zero failures/errors/skips and 11 clean
SpotBugs reports. Twenty focused selector cases cover deterministic draw order,
square/vertical geometry, alias/default parsing, ranges, multiplicity,
`amount²` exhaustion, minimum/spacing order and overflow, collision fallback,
post-filters, cross-world behavior, invalid inputs, attempt/output caps and
nested skill/guard dispatch. Importer coverage preserves all nine aliases and
raw numeric options.

[Paper 2026-11-26 r2](../tools/paper-integration/reports/paper-1.21.1-random-location-targeter-20261126-r2.json)
passes 136 observations: 68 cases on each direct/imported route. The fixture
uses real Paper worlds, inherited living/nonliving entities, air/solid/mixed
block scenes, native targeters and native/runtime SetBlock actions. It scopes
and restores the current thread's RNG seed around each query; production code
uses ordinary randomness. The independent Python oracle calculates the JDK 21
random sequence from the seed and thread ID and checks exact coordinates,
height, orientation, counts, duplicate multiplicity, rejection rules, parsing,
filters and block effects. It scans the full prepared volume for unexpected
effects. Three seeds exercise ordinary and collision sampling. Range cases
use collapsed ranges and solid scenes; broad distribution claims are excluded.
Two observations explicitly preserve the native inline-guard difference.

Eleven auditor tests reject tampered coordinates even when both implementations
agree, lost duplicates, wrong height/world/orientation, altered RNG witnesses,
incorrect minimum/spacing/mutation results, missing or extra effects, wrong
arguments and incomplete source/log/client hashes. Observer join/completion
and successful process exits are recorded; client rendering is not claimed.
`--check-current` passes against 31 source hashes and plugin SHA-256
`7e3d49ca2d836d6178762505e549128fc9062dd409473734a459a6dd2702f1c4`.
The [archive](../tools/paper-integration/reports/paper-1.21.1-random-location-targeter-20261126-r2-logs.zip)
SHA-256 is `8e51588887086c75448e88ac692f240284a6889f83e777ad2e3440691775e014`.

Rejected r1 remains intact: its two entity-priority rows set native locations
after entities, inadvertently clearing the entity collection. The corrected
r2 exercises native and runtime location-to-entity reselection. No runtime
change was needed between these fixture revisions.

Reproduce after building the plugin:

```sh
python3 tools/paper-integration/test-random-location-targeter-audit.py
python3 tools/paper-integration/run-random-location-targeter-fixture.py --revision rN --cache-directory srv-paper-structure-targeter-20261126-r2
python3 tools/paper-integration/audit-random-location-targeter.py REPORT --check-current
```

Full native dispatcher scheduling, noncollapsed range/distribution studies,
unloaded-chunk generation cost, lifecycle/reload/client rendering and the final
strict release gate remain separate acceptance. T10 circular selectors and
T11 live threat locations remain separate implementation groups. Historical
reports retain their own artifacts and do not certify this later JAR.
