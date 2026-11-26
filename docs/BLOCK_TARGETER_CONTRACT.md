# Block and vein target selectors

Baseline: Paper 1.21.1 / public MythicMobs 5.7.2, native SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
T06/T07 have runtime and importer bindings. They return locations, preserving
the inherited collection through the existing positional skill dispatcher.

| Selector | Query input | Result |
| --- | --- | --- |
| `@highestblock` | Skill origin | Highest block at the origin's X/Z, then location mutation and filtering |
| `@obstructingBlock` | Valid caster position and rounded yaw | First non-passable block in the native ten-position probe order |
| `@blocksInChunk/@BIC` | Inherited entities or locations; `nearorigin/no` selects origin | Every matching block coordinate in each selected chunk |
| `@blocksInRadius/@BIR` | Inherited entities or locations; `nearorigin/no` selects origin | Matching points in the native sphere/cube enumeration |
| `@blocksNearOrigin/@BNO` | Skill origin | The same radius enumeration around origin |
| `@blockVein/@vein/@bv` | Skill origin | Connected matching locations using all 26 neighboring directions |

Empty inherited collections stay empty. They do not fall back to the caster.
Duplicate inherited centers are queried once, so they cannot exhaust the work
budget by requesting the same chunk repeatedly. Native result order and sort
ties do not promise stable ordering.

## Geometry, materials and option priority

Radius uses `radius/r`, default 2; vertical radius uses
`radiusy/ry/yradius/yr`, defaulting to the radius argument. The public parser
accepts literals/ranges; quoted or malformed numeric input falls back to 1.
Offsets step by one from the negative radius and preserve fractional center
coordinates. Squared axes are truncated to integers before sphere comparison.
The native `sum > 1` exclusion accepts NaN from a zero squared axis: radius
zero produces the center, and vertical radius zero permits the full horizontal
square. `shape/s` defaults to SPHERE; other values select the cube path.
`noise/n` zero, one and negative values retain every candidate; positive values
below one sample candidates independently.

`onlyair/oa` overrides `noair/na`; the latter defaults true. These compare exactly
with `Material.AIR`, so CAVE_AIR and VOID_AIR are included by the default mask.
Radius mutations run before this mask. Chunk queries include `maxHeight`, where
Paper reports VOID_AIR, and cover negative chunk coordinates using floor division.
They ignore location mutation options. The live fixture separately checks this
unbuildable row without claiming that SetBlock can change it.

Obstruction uses the caster's rounded yaw quadrant, then checks front feet,
caster feet, front at heights one/two, and the two lateral columns at heights
zero/one/two. It uses block passability, not a ray trace, and ignores location
mutation options. Material post-filters may reject its single result.

Vein material aliases are `blocktypes/bt/t/material/materials/m/blocks/block/b`,
default STONE. Its traversal budget aliases are `limit/l/max/m`, default 10;
`m` therefore participates in both arguments. `originMustMatch/match` defaults
true. A lone matching origin does not select itself, but a connected neighbor
can discover it. The randomized 26-neighbor walk decrements its recursion
budget and checks size at entry, so `l=1` can return several direct neighbors.
The generic `limit` additionally truncates the final collection; `l` and `max`
do not. An iterative stack preserves the native entry-only checks without
unbounded Java recursion. Vein queries ignore location mutation options.

Shared location post-filters apply `blockignores/blockignore/bi` and
`blocktypes/blocktype/bt`, including `#contains` and `@prefix` patterns, before
positive `limit` sorting/truncation. Location limit uses static integer parsing;
quoted values do not truncate. Highest-block queries apply mutations before
filters. Native inline targeter conditions are ignored. Helstera applies its
supported positional conditions in SkillService, an explicit extension.

## Resource boundaries

Queries inspect loaded chunks only and never implicitly generate or load a
chunk. Non-finite or out-of-range locations and invalid arguments fail closed.
Each query has a 262,144 candidate/visit cap and a 131,072 unique-result cap.
Exceeding either rejects the complete result rather than exposing a partial
prefix. Radius/chunk queries reserve candidate work before enumeration; vein
queries count neighbor visits. These limits and unloaded-chunk behavior are
deliberate bounded-runtime extensions, not native parity claims. Material
queries remain on the server thread.

## Validation and remaining acceptance

The full `./mvnw -q verify` run passes 3,028 tests with zero failures, errors or
skips and 11 clean SpotBugs reports. The 18 block selector tests cover loaded
chunks, caps, duplicate inherited centers, geometry, negative coordinates,
obstruction order, diagonal vein connectivity and nested skill dispatch.
Importer tests cover every public alias.

[2026-11-26 r3](../tools/paper-integration/reports/paper-1.21.1-block-targeter-20261126-r3.json)
passes 180 observations, comparing 90 cases through direct and imported runtime
paths with native selection and actual SetBlock effects. An independent auditor
specifies the expected coordinates, materials, inputs and mutations; it also
checks aliases, pre-filter counts, chunk-loading invariants, the joined client,
process exits and evidence hashes. Its 15 negative-control tests reject altered
inputs, identical-but-wrong native/runtime results, missing evidence and writes
outside the selection. Whole native dispatcher scheduling and client rendering
are outside this fixture.

The independent audit passes `--check-current` against plugin SHA-256
`c68dd9ab0b5033c76aaffc7b550ea5c627cfe8e89efcf52ec493ce31bc74d015`.
Two observations explicitly retain the public native inline-guard difference.
The archive hash is
`1e2f56ef83e94311fe01bbe65e2b53a461778ed5eccb11cd374d8e8528f2c018`.
The [aiming/inherited r3 regression](../tools/paper-integration/reports/paper-1.21.1-aiming-targeter-20261126-r3.json)
also passes all 134 observations on this JAR after sharing the location filters.

The rejected r1 archive retains the zero-radius implementation defect and
incorrect fixture assumptions about air-only sections and maxHeight. The
fixture now keeps a non-air block in the CAVE_AIR section and asserts both air
variants. Rejected r2 has matching selections/effects but records negative-zero
pitch introduced by `setDirection`; the final fixture constructs yaw-only
locations with explicit zero pitch. Neither rejected report is promoted.

Reproduce after building the plugin:

```sh
python3 tools/paper-integration/test-block-targeter-audit.py
python3 tools/paper-integration/run-block-targeter-fixture.py --revision rN --cache-directory srv-paper-aiming-targeter-20261126-r2
python3 tools/paper-integration/audit-block-targeter.py REPORT --check-current
```

Random sampling distributions and long branching veins, live unload/reload
transitions, full event/dispatcher scheduling, external block providers and
the final strict release gate remain separate acceptance. T08 has subsequently
been implemented under [its own contract](STRUCTURE_TARGETER_CONTRACT.md).
T09–T11 and S01/S03–S10 remain implementation work.
