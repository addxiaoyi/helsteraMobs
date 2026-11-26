# Public entity setter contract

This implementation targets the public MythicMobs 5.7.2 JAR
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`
on Paper 1.21.1. Constructor aliases, defaults and mutation direction were
checked in the native bytecode before implementing the shared parser and importer.
This document records implementation scope; it does not itself certify a server run.

| Native mechanic / aliases | Arguments and defaults | Mutation |
| --- | --- | --- |
| `setpathfindingmalus`, `setmalus`, `malus` | `malus/type/malustype=OPEN`, `value/amount=0`; static float | Selected mob's native PathType cost through the pinned adapter |
| `setleashholder`, `setleasher` | `remove/r/boolean/bool/b=false` | Caster LivingEntity's holder becomes the selected entity, or null |
| `setchunkforceloaded` | `loaded/l=true` | Selected location's chunk is loaded first, then force-loaded state changes |
| `setTextDisplay`, `setText` | `text/t=null`; placeholder string | Selected TextDisplay receives a MiniMessage component; absent text clears it |
| `setDisplayEntityItem` | `item/i/type/t/material/mat/m=AIR`; static item | Selected ItemDisplay receives a cloned production-resolved item; AIR clears it |
| `setInteractionSize`, `interactionSize` | `height/h=1`, `width/w=1`; placeholder floats | Selected Interaction; absent target uses caster Interaction |
| `setRaiderCanJoinRaid`, `setCanJoinRaid` | `bool/b/can/c=true` | Selected Raider's join flag |
| `setRaiderPatrolLeader`, `setRaiderLeader` | `bool/b/leader/l=true` | Selected Raider's leader flag |
| `setRaiderPatrolBlock`, `setRaiderBlock` | `location/l/block/b` | Selected Raider's patrol block from an explicit nested location selector |

Canonical Helstera action names are the corresponding kebab-case names, such
as `set-interaction-size`. Native spelling and aliases bind directly and import
through the same parser. Unknown arguments, duplicate aliases, invalid booleans,
unknown PathTypes, unresolved/static-placeholder misuse and non-finite numeric
inputs fail closed. Invalid imported lines remain in `unsupported` with a warning.
These strict invalid-input rules intentionally avoid native silent coercion.

Interaction dimensions are validated together before either write. Paper's
pinned setters impose no positive-only check: finite zero and negative floats
are retained. Placeholder floats resolve against the actual recipient, including
its location. Text retains MiniMessage tags while resolving known placeholders.
Native `target.location.x/y/z` and `target.l.x/y/z` read block coordinates
(`getBlockX/Y/Z`), for both living and nonliving recipients. They never add a
half-block offset. Their `.double` forms read exact coordinates; the existing
Helstera `target.loc.*` family also stays exact. Native `caster.location.x/y/z`,
`caster.l.x/y/z` and `c.l.x/y/z` read exact coordinates. Trigger axes delegate to
the native target coordinate contract. Thus a recipient at Y=170.9 supplies
`170.0` to `<target.l.y>` and `170.9` to `<target.l.y.double>`.
Item identifiers are static; the production item resolver runs on application
so imported/custom definitions can finish loading before their first use.
Unavailable items leave the display unchanged.

Direct `@self`, `@caster`, `@trigger`, `@target` and `@targets` dispatch now carries
an explicit generic entity recipient. Display and Interaction entities no longer
vanish merely because they do not implement LivingEntity. The existing living
API remains intact, and recipient copies retain skill power, execution audience,
trigger, origin and event metadata. Location-scoped entity setters do not fall
back to a caster or retained target. The chunk setter consumes positional targets
or selected entities' locations.

The general radius/chain selector engine still primarily enumerates LivingEntity;
this batch does not claim generic Display/Interaction selection through those
selectors. Target conditions on a nonliving recipient are conservatively skipped
because existing condition factories require LivingEntity. Nonliving entity
variable registries are also outside this slice. These boundaries require a
separate generic target/condition contract, rather than silently broadening filters.

The Raider patrol setter accepts the already-validated location selector subset,
including `@origin` and `@selflocation`; nested entity, Pin/multi-location and
conditional selectors remain unsupported. Native missing `location` does not
mean the outer recipient: its constructor tries to parse an empty selector and
fails. The importer therefore preserves that missing/unsupported configuration.

Focused tests cover parser defaults and rejection, caster/holder direction,
nonliving direct binding, recipient numeric placeholders, both-dimension
validation, MiniMessage text, custom-item cloning, chunk operation ordering,
Raider flags, and importer source preservation. A real Paper/native comparison
is still required before marking this whole setter slice verified.

The dedicated `run-entity-setters-fixture.py` fixture records 42 observations:
17 matching native mutations, one explicit public-build parser difference and
3 invalid-input controls on each of direct binding and imported paths.
The public native `PlaceholderFloat` parser falls back to `1` for dynamic
Interaction dimensions; Helstera resolves the target block coordinate itself.
A separate static-dimension case verifies the mutation without that parser gate.
Text observations compare plain text and component color, avoiding dependence
on MiniMessage's optional closing-tag serialization. Its independent audit fixes the
expected seeds, mutations, source and mapped strings, log correspondence and
artifact/source manifests. Run it after building both current JARs, then run
`audit-entity-setters.py REPORT --check-current-sources`. Invalid-input controls
are labeled as no native invocation, not native parity claims.

Rejected Paper runs are retained. The first run exposed unloaded fixture
entities: native direct calls could mutate an invalid entity's handle while
runtime selection correctly skipped it. Plugin chunk tickets and explicit
validity assertions fixed the fixture. The next run passed malus, leash and
chunk operations and exposed the coordinate/serialization issues above;
neither rejected report certifies the full setter slice.

A subsequent review closed three recipient-selection issues: living `@target`
uses the caster Mob's live combat target, unknown outer selector names reject
binding/import (including chained selectors), and empty native target/tracked
location selections in Raider patrol fall back to the skill origin. Missing
trigger or invalid world resolution does not gain that fallback.
