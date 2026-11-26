# Aiming and inherited target selectors

Baseline: Paper 1.21.1 / public MythicMobs 5.7.2, native SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
T05 now has runtime dispatch, importer aliases and scoped native observations.
The three selectors have distinct inputs and recipient types:

| Selector | Input | Output |
| --- | --- | --- |
| `@targetBlock` | Player block aiming, or a managed caster's saved target state | Locations |
| `@targetedLocation/@targetedLocations/@targetedLoc` | Inherited location collection | Locations |
| `@targetedTarget/@targeted` | Inherited generic entity collection | Entities, including nonliving entities |

Inherited selectors never substitute nearby entities, a vanilla combat target,
the skill origin, or the caster. An empty collection stays empty.
`@targetedLocation` does not project inherited entities; use `@LOT` for that.

## Block aiming and managed casters

An ordinary player uses Bukkit `getTargetBlock(Set<Material>, int)`, producing
integer block coordinates, including the last visited block when nothing blocks
the query. This differs from the shape intersection used by VariableLocation
player aiming. The distance is a static integer: `maxdistance`, `max`, `distance`,
then `d`, default 64. A malformed or quoted value falls back to 64 even when a
lower-priority alias contains a valid value.

`ignoreTransparent` precedes `it`, default true. The default material set is
`isTransparent || !isSolid || !isOccluding`; false supplies only AIR. In the
fixture, glass at distance 3 is skipped by the default query, stone at distance
6 stops it, and `it=false` stops at glass. A distance of 2 produces the block
at distance 2. An unobstructed default query ends at distance 64.

Managed-caster state takes precedence over the ordinary-player branch. The
AiManager provider reads the threat table's saved selection without ranking or
advancing decay. An existing empty table returns no location. Without a table,
a Creature uses its Bukkit target and other managed casters use the controller's
last aggro cause. The current controller records that cause on damage; full
native aggro-event production is not established by this selector work. Provider
installation and removal are owned by the manager, including restart and older
manager shutdown. A managed identity without a controller can use its Creature
target; an unmanaged nonplayer has no result.

The Paper comparison prepares empty/near/far native threat state and matching
runtime provider state. Unit tests additionally exercise the production manager
provider with a saved target below a higher score, an empty table, a wrapped
Player, last-aggro lookup and provider ownership. Natural threat production,
switching, wrapped-player lifecycle and terminal reads remain S09/S10 work.

## Collections and dispatch

BehaviorContext stores defensive copies of location targets separately from
named location variables and entity targets. Selecting one kind clears the
other. Location sets survive context copies, nested skills, delays, condition
scopes and skill-tree recreation. An ordinary positional mechanic receives each
location while retaining the full inherited collection. A nested skill receives
the collection once, so a delayed child `@targetedLoc` does not multiply calls.
Metaskill target conditions filter every location, including when the first
location fails and a later one passes. Existing Pin skills retain their earlier
per-point invocation contract and give each invocation a singleton collection.

Native setters also clear the other target kind. Native `deepClone` shares
location objects, and some coordinate mutations modify those shared objects.
Helstera retains defensive copies. The fixture gives every independent native
case fresh coordinates and records both native and runtime input collections;
incidental mutation across sibling native dispatcher calls is outside this
comparison. No stable order is promised for native HashSet ties.

## Options and filters

Entity filtering shares the dropped-item selector's raw option parser and
native filter implementation. It handles self, player game modes, armor stands,
markers, NPCs, animal/creature/monster categories, ground/water/flying, villagers,
managed identity, owner and faction flags. Explicit flags override the parsed
`target`/`ignore` defaults. `ignoretype` is a pinned native no-op. Positive limits
enable sorting/truncation; entity limits use the public integer/range parser.
Mixed health sorts use zero for nonliving entities. Native targeter filtering
alone does not reject removed entities, but Helstera's explicit conditions and
mechanic recipients retain their validity checks.

Location options support native offsets, directional offsets, length, centering,
coordinate and orientation overrides, material filters, sorting and limits.
Aliases retain their raw priority, quotes and duplicate-key behavior through
import. Numeric location mutations use the public literal/range parser;
malformed, dynamic and quoted values fall back to 1. Samples are taken per
location. Coordinate zero is a native no-op, and `rotatex/y/z` are parsed native
no-ops. Location `limit` is a static integer with default 0, so a quoted limit
does not truncate. Material filters run before a positive limit. Non-finite
locations and invalid worlds fail closed.

Public native inline selector conditions are ignored. Helstera's supported
guards are an explicit extension, including generic entity guards before limits
and positional guards through SkillService. General nonliving predicates and
mechanic-specific creative/spectator gating remain bounded by their own
contracts. The fixture invokes native entity `filter(metadata, true)` to test
the pure selector, not the native dispatcher gate for every mechanic.

## Validation and remaining acceptance

`./mvnw -B -o clean verify` passes 3,009 tests with zero failures, errors or
skips and 11 clean SpotBugs checks. Offline coverage includes plural metaskill
conditions, delayed inherited selection, generic setters, raw alias priority,
managed-provider ownership and the existing Pin regression.

[2026-11-26 r2](../tools/paper-integration/reports/paper-1.21.1-aiming-targeter-20261126-r2.json)
passes 134 observations: 67 cases through direct and imported runtime paths,
each compared with native selection and actual TagAdd or SetBlockType effects.
Six observations record the explicit public-build inline-guard difference.
Coverage includes mixed entities, real player game modes, cross-world entity
inheritance, location deduplication, coordinates, limits/material filters, real
player block aiming and prepared managed threat state. The independent auditor
checks inputs, bindings, effects, block-ray geometry, completion and hashes.
Nine auditor tests reject consistent-but-wrong results and missing/tampered
evidence. The observer client and server exit cleanly.

Both the aiming r2 and dropped-item r5 auditors pass `--check-current` against
plugin SHA-256
`7f50522e9f6709631058b19aa9fa89c34e84f9ad3c6009e193b3e8a9ad54f874`.
The latter retains all 132 dropped-item observations after sharing the filters.
The aiming r2 archive hash is
`a67f811b11caf88fc460f456e4367c9ebbba773d5d5608e1bb406556858d59ff`.

Rejected r1 remains archived. Its fixture cleared native entity targets by
setting an empty location collection, and reused mutable native location inputs
across cases. The auditor rejected it; r2 repairs the preparation and records
both actual input collections without weakening the expected selections.

Reproduce after building the plugin:

```sh
python3 tools/paper-integration/test-aiming-targeter-audit.py
python3 tools/paper-integration/run-aiming-targeter-fixture.py --revision rN --cache-directory srv-paper-item-targeter-20261126-r4
python3 tools/paper-integration/audit-aiming-targeter.py REPORT --check-current
```

Full native metaskill scheduling, client rendering, random sampling distributions,
nondefault native global filters, reload/removal lifecycle, external faction and
ModelEngine behavior, and the strict release gate remain separate acceptance.
This slice does not complete the remaining variable contexts or threat policy.
