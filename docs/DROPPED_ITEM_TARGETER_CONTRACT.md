# Dropped Item target selectors

Baseline: Paper 1.21.1 / public MythicMobs 5.7.2, native SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
T02 now has entity-valued queries, direct skill binding and importer aliases.
This contract scopes that implementation; broader entity selectors, the complete
condition catalog, client rendering and the final release gate remain separate.

## Query contract

| Selector | Query center | Distance center | Radius precedence |
| --- | --- | --- | --- |
| `@itemsInRadius/@IIR` | Caster model location | Caster entity location | `radius`, then `r`, default 5 |
| `@itemsNearOrigin/@INO` | Skill origin | Skill origin | Parse `radius` with default 5, then let `r` override it |

Both queries select Bukkit Item entities. Living entities, experience orbs and
display entities are not dropped items. The inherited recipient collection does
not replace the world query. Only loaded chunks in the inclusive range
`floor((center - radius) / 16)` through `floor((center + radius) / 16)` are
inspected. There is no preliminary Y box. Candidates must satisfy strict
`distanceSquared < radius²`, so a point exactly on the radius is excluded.

Helstera enumerates loaded chunks and checks those bounds rather than walking
an arbitrarily large coordinate rectangle. Selection never loads or generates
chunks. A negative radius retains native squared-distance and reversed-chunk
behavior: a small negative radius may still find items within the same chunk;
a reversed chunk interval is empty. Zero selects nothing. Non-finite radii and
invalid/missing world coordinates fail closed; extreme inputs are not a native
loop/overflow compatibility promise.

`bb` wins over `useboundingbox`, default false. When enabled, distance is the
shortest squared distance from the point to the Item bounding box. Chunk bounds
still use the supplied radius: an adjacent chunk is not added merely because
an entity's bounding box reaches into the radius. ModelEngine parent/remapped
hitboxes are external integration work.

Native static `Double.parseDouble` behavior is retained for radius values.
Malformed, quoted and placeholder strings fall back to the relevant default;
they are not dynamic radius expressions. Repeated identical keys use the last
value. Alias priority is independent of source order. Explicit booleans use
`Boolean.parseBoolean`, so malformed strings become false.

## Filters and limits

The relevant pinned defaults are self=false, ground=true, sameFaction=true,
owner=true and nonMythic=true. `target=self/caster` enables self, and explicit
`targetself` overrides it. `target=water/flying` excludes ordinary Items;
`target=players` does not disable the ground category and therefore still
selects Items. Native `ignoretype` is parsed but never consulted by the filter;
it is deliberately not implemented as a new effective item-type exclusion.
The query already deduplicates its results.

`ignore=vanilla/faction/owner` disables the corresponding flags. Explicit
`targetvanilla` precedes `targetnonmythic`. Managed identity is the mob identity
marker, not a detached variable registry. Owner exclusion applies to a managed
caster's recorded owner UUID. Same-faction exclusion uses case-sensitive names,
including equality between two empty names. Managed entities use the faction
service; unmanaged candidate Items can use Bukkit `Faction` metadata. A generic
nonplayer caster has the native empty faction. Native global-default overrides
and external player faction providers need their own integration acceptance.

Sorting and truncation occur only for a positive `limit`. `sort` precedes
`sortby`; unrecognized sort names retain discovery order. NEAREST/FURTHEST use
skill origin even for IIR. RANDOM chooses from eligible Items. Native health
for nonliving entities is zero, so both health sorts are ties. Native sets do
not guarantee tie/unsorted order; tests assert membership and count in those
cases, not one arbitrary UUID.

Integer limits follow the pinned public parser: static integers and inclusive
integer ranges are accepted; malformed, quoted, decimal and placeholder inputs
fall back to 1. Nonpositive limits do not truncate. Extremely large or malformed
ranges are bounded by normal Java parsing and reject/fallback behavior, not a
promise to reproduce native overflow exceptions.

Threat sorting reads UUID scores from the caster's live AI threat table through
an owned provider installed/cleared with AiManager. No table returns no targets
for a positive threat-sorted limit; it does not silently become a distance sort.
The Paper fixture prepares a living leader at 100 and Item scores at 5 and 10.
A native Item becoming the combat target throws in ActiveMob.setTarget; the
fixture's score-read evidence does not claim Item combat targeting or complete
threat switching. The runtime fixture supplies prepared scores to the production
query; full AI threat production remains S10.

## Conditions and generic dispatch

The public native build ignores inline selector condition lists. Helstera
supports them as an explicit extension: `targetconditions`, `conditions`,
`cond`, then `c`. Supported audited built-in nonliving target predicates are
`hastag`, `entitytype`, `uuid` and `distance`, including aliases and boolean
composition. Conditions run against each actual Item before generic filters
and the positive limit. Unsupported built-in predicates fail before inversion,
so `health false` cannot accidentally select Items or inspect the caster.
Malformed lists and unknown conditions reject skill binding. Custom registered
conditions are responsible for their own entity type contract.

The same generic predicate scope works for nested skill target conditions and
mechanic target guards. Generic collections retain Items through skill-tree
copies, delayed bare `@targets` and `@LOT`. Velocity, teleport, tags and name
mechanics consume the complete collection; existing living-only mechanics
retain their type boundaries. Entity setters visit every selected Item and
apply their own recipient-type checks. General living radius/chain selectors,
parameterized inherited selectors, arbitrary nonliving predicates, selector
sudo caster changes and nonliving variable registries are not certified by T02.

## Validation

Offline tests cover all four names, strict sphere and bounding-box boundaries,
origin/caster separation, chunk selection, negative/static/malformed radii,
boolean and alias priority, managed owner/faction filters, native integer limits,
threat-provider reads, guard-before-limit behavior, unsupported inversion,
imported raw argument preservation and nested delayed Item mutations. The full
`./mvnw -B -o clean verify` run passed 3,009 tests with no failures, errors or
skips and 11 clean SpotBugs checks.

The independent Paper auditor fixes selection and mutation expectations,
source/imported strings, Item geometry and UUIDs, living/cross-world controls,
loaded-chunk stability, threat setup, process/client completion and artifact
manifests. Native/direct/imported effects are actual Item scoreboard tags.
RANDOM and health ties use eligible-set/count checks. Client join evidence is
not a rendering or pickup test. Inline-guard differences are explicit rather
than counted as native parity.

[2026-11-26 r5](../tools/paper-integration/reports/paper-1.21.1-item-targeter-20261126-r5.json)
passes 132 observations (66 cases through direct and imported runtime paths,
each compared with native selection and mutation). Fourteen observations record
intentional guard differences. The audit also passes `--check-current` for
plugin SHA-256
`7f50522e9f6709631058b19aa9fa89c34e84f9ad3c6009e193b3e8a9ad54f874`.
This rerun verifies shared filters after T05 aiming/inherited selectors were
added; its manifest includes the extracted raw parser and entity filters.
Nine audit-control tests reject altered outputs, missing evidence, fake native
guard parity, invalid random membership and omitted threat setup.

Reproduce after building the plugin:

```sh
python3 tools/paper-integration/test-item-targeter-audit.py
python3 tools/paper-integration/run-item-targeter-fixture.py --revision rN --cache-directory srv-paper-ai-target-selector-20261126-r4
python3 tools/paper-integration/audit-item-targeter.py REPORT --check-current
```

Rejected reports remain archived. r1 used a client name beyond Minecraft's
16-character limit. r2 exposed native Item combat-target conversion during
threat preparation. r3 recorded all 132 selections/effects but failed the
independent geometry audit because Paper quantized non-binary fixture positions.
The corrected fixture uses exactly representable positions while retaining the
same radius and chunk-boundary negative controls.
