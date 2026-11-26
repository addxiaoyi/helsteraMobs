# Nearest structure target selector

Baseline: Paper 1.21.1 / public MythicMobs 5.7.2, native SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
`@nearestStructure` (T08) has direct runtime dispatch and importer support.
The selector returns one location, or an empty collection when the query has
no result. It uses the caster's current entity position, not the skill origin,
current combat target or inherited location collection.

## Native arguments and query

| Argument | Alias | Native default and behavior |
| --- | --- | --- |
| `type` | `t` | STRONGHOLD; compare case-insensitively with legacy Bukkit StructureType names or their `toString()` values |
| `radius` | `r` | Static integer 5000; malformed, quoted, ranged, dynamic and overflowing values fall back to 5000 |
| `unexplored` | `u` | False; raw Boolean parsing, so quoted or malformed true values remain false |

The first present alias takes priority even when its value is invalid. Type is
static text; unknown, quoted, namespaced and placeholder names fall back to
STRONGHOLD rather than evaluating a placeholder. The 18 legacy names are
mineshaft, village, fortress, stronghold, jungle_pyramid, ocean_ruin,
desert_pyramid, igloo, swamp_hut, monument, end_city, mansion, buried_treasure,
shipwreck, pillager_outpost, nether_fossil, ruined_portal and bastion_remnant.
Names from the modern structure registry that are absent from this legacy set
are not silently treated as supported native names.

Runtime calls the same public legacy `World.locateNearestStructure` overload as
the pinned native selector. Radius is passed unchanged to Paper; it is not
converted to block distance. The native generator's structure-specific radius
rules remain authoritative, including stronghold lookup with radius zero and
signed negative values. Calls are not cached, so changes of caster position,
Paper listeners or unexplored state are visible on subsequent calls.

Paper owns `StructuresLocateEvent`, cancellation, supplied results, generation
and unexplored-reference side effects. The query is deliberately not restricted
to already loaded chunks: the generator may read or generate structure chunks.
The selector runs through the server-thread skill path. It neither forces
chunks to stay loaded nor creates a separate asynchronous query task.

The explicit upper radius limit is 5000, preserving the native default.
Larger explicit values fail binding/query validation rather than silently
clamping to a different search. This is an extension and only a numeric bound;
it is not a guarantee of a maximum execution time. Invalid or removed casters,
non-finite/out-of-range positions, no result and API argument/state rejection
return an empty collection.

## Location filtering and dispatch

Native nearest-structure lookup does not call the generic location mutation
step. Offsets, centering and coordinate overrides therefore do not alter its
result. Shared block allow/ignore filters and final static limits remain active.
The runtime copies the returned location, preserving the provider's object.
Positional actions receive it normally; nested skills can inherit it through
`@targetedLoc`. Empty/cancelled results do not invent a caster or origin location.

The public native targeter ignores inline conditions. Helstera's supported
positional conditions are an explicit extension applied through SkillService.
The false-guard cases therefore retain native SetBlock effects and suppress
runtime effects, while their raw location queries remain identical.

## Evidence and remaining acceptance

`./mvnw -q verify` passes 3,047 tests with zero failures/errors/skips and 11 clean
SpotBugs reports. Eighteen focused selector tests cover every legacy type,
alias priority, static fallback parsing, caps, caster removal, changed positions,
uncached queries, material filters, mutation no-ops and nested skill/guard
dispatch. The importer test preserves the raw native options and aliases.

[2026-11-26 r2](../tools/paper-integration/reports/paper-1.21.1-structure-targeter-20261126-r2.json)
passes 100 observations: 50 cases through direct and imported runtime
routes. It records native parsed fields and three actual Paper query events
per case: native selection, runtime selection and runtime action dispatch.
Most argument cases deliberately return a location or cancel through the event;
these establish API routing, parameter, filter and effect behavior, not natural
world generation. Separate natural cases leave the event unchanged and query
the seeded world. Stronghold lookup returns `(-224, 0, 1376)` for seed
281474976710655; `World.getStructures` confirms the generated stronghold in that
chunk. Native and runtime SetBlock actions change that selected block. A fortress
lookup in the normal world returns no location. These account for 6 natural
observations among the 100 total observations.

The independent auditor pins native defaults, all 18 legacy-to-modern structure
mappings, query centers, alias behavior, returned coordinates, actual block
effects, event cancellation, world seed and generated structure identity.
The fixed natural coordinate prevents consistently wrong native/runtime outputs
from passing together. Eleven negative-control tests reject tampered parsed
arguments, missing query events, wrong centers, invented effects, fabricated
natural coordinates and missing archive/client evidence.

The auditor passes `--check-current` against plugin SHA-256
`3aac05b2fab4b273a1f432a0baf68e84d14039b7ecd67e300f08cd96e3fe09fb`.
The report pins 30 source files; its archive hash is
`02163c9a3b2df11ac3d00dce3be4150640b9442ad86060f943c70c1be42987f1`.
Both server and observer client exit successfully, and the independent
redaction scan finds zero sensitive-value matches. Historical r1 also passes
at its recorded scope; r2 additionally fixes the seeded stronghold coordinate
in the independent oracle rather than accepting cross-case agreement alone.

Reproduce after building the plugin:

```sh
python3 tools/paper-integration/test-structure-targeter-audit.py
python3 tools/paper-integration/run-structure-targeter-fixture.py --revision rN --cache-directory srv-paper-aiming-targeter-20261126-r3
python3 tools/paper-integration/audit-structure-targeter.py REPORT --check-current
```

Full native metaskill/event scheduling, natural unexplored-reference consumption
over repeated searches, wider seeds/structure distributions, external generator
and listener combinations, disconnect/reload lifecycle, client rendering and the
final strict release gate remain separate acceptance. Event-supplied results
are not presented as proof that every legacy structure generates in every world.
