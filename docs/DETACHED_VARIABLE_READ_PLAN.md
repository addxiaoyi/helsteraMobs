# Detached variable reads and terminal action scope plan

Status: native MM 5.7.2 / Paper 1.21.1-133 observations cover terminal condition
and location reads as well as placeholders. The runtime preserves detached
target/trigger tokens, fails closed for expired condition/location reads, and
keeps the removed caster available only through its observed terminal window.
Explicit trigger variable reads are supported as read-only; unknown dotted scopes
no longer fall through to skill variables. This remains scoped work in progress,
not full parity or a release promotion. See the
[observation contract](TERMINAL_READ_OBSERVATIONS.md) for the report and limits.

## Current evidence boundary

The terminal-variable contract proves caster set/add/unset, expiry, and rejection
of detached `target.*` writes. The condition/location observer now covers detached
target/trigger reads through condition dispatch and variable-location targeters.
`EntityVariableContext.find` can still return the retained caster snapshot for a
shared entity identity; role-based readers check whether that registry is
eligible before looking up a value. The public placeholder renderer preserves the
original token if a target or trigger has detached, including tokens with a
default. Regression tests also guard typed scalar reads and public location reads
and expiry. Direct tests also cover explicit read-only `trigger.*`, unknown dotted
scopes, and trigger variable-location targeters; these runtime extensions are not
claimed as native scope parity.

Read-only inspection of pinned native JAR
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`
found distinct paths:

- `CasterVariablePlaceholder` resolves CASTER through PlaceholderMeta, whose
  retained ActiveMob can supply its variable registry.
- `TargetVariablePlaceholder` resolves TARGET through the target AbstractEntity
  and VariableManager's active-mob/player lookup.
- `TriggerPlaceholder` wraps a target/entity placeholder with `getTrigger()`;
  there is no separate TriggerVariablePlaceholder class in this artifact.
- `MobExecutor.isActiveMob` queries the active registry (plus the optional
  ModelEngine sub-hitbox parent fallback). It does not establish that a removed
  entity can access the retained caster object used by the first path.

The first observer confirmed target reads; the second added actual self-inflicted
and peer-inflicted deaths and confirmed trigger reads. Their raw archives remain
observation-only, including all failures. UUIDs in the first observer incorrectly
used an unsupported fallback suffix; its identity claims are limited to the
separate command argument and ID events. The expanded observer uses plain UUID
placeholders and distinguishes a retained caster from a separate live trigger.

## Comparison design

1. Seed distinct caster, live peer and trigger scalar markers and location
   variables. Record each entity UUID, native active membership, runtime live
   registry membership, retained execution count and expiration deadlines.
2. Exercise ordinary living controls, attached death callbacks, synchronous
   cancelled death, confirmed death, loaded command removal, CHUNK unload and
   removal during the NEVER unloaded window. Include delayed nested skills and
   repeated callbacks; keep native command dispatch's existing timing exception
   explicitly bounded.
3. Observe caster/target/trigger placeholders with and without fallbacks while
   each role names the detached caster, a separate live peer or no entity. Verify
   condition reads separately from string interpolation and location reads.
   Missing-registry exceptions must not silently disappear from evidence.
4. Mutate retained caster values between observations and cross real expiry
   deadlines. Verify whether detached role reads track the mutation, become
   unavailable, or differ by phase; never infer the answer from output formatting.
5. Confirm reads do not recreate a live registry, SkillTree state, active mob,
   unloaded chunk or model instance. Preserve separate live target/trigger access.
6. Implement only the behavior demonstrated by native/runtime/direct/imported
   comparisons. Add negative evidence controls for wrong UUIDs, resurrected
   registries, falsely preserved values, lost fallback, missing callbacks,
   wrong deadlines and artifacts. Refresh the exact-JAR gate before any promotion.

## Variable action scope import (2026-11-21)

Native VariableScope contains SKILL, CASTER, TARGET, WORLD and GLOBAL; TRIGGER is
an entity read role, not a declared mutation scope. The runtime now accepts
`trigger.*` for reads and location selection while keeping trigger writes inert.
Explicit unknown or empty canonical runtime scopes fail closed instead of falling
through to skill scope. The importer preserves supported explicit mutation scopes
and warns while retaining unsupported sources. Raw native brace syntax now rejects
unsupported references during binding, with a diagnostic containing the source.

Inspection of the pinned 5.7.2 `VariableMechanic` and `LineConfig` classes, followed
by a real Paper comparison, establishes these reference-selection rules:

- Names use the alias order `name`, `n`, `variable`, `var`, `key`, `k`.
- `scope` takes precedence over `s`. A repeated identical key (case insensitive)
  uses its last value, including an empty or invalid value.
- The first dot supplies the default scope; the rest is the literal key.
  An explicit scope overrides the prefix after the prefix is stripped.
  For example, `var=skill.Counter.Part;scope=target` imports as
  `var=Counter.Part scope=target`. Native accepts an otherwise invalid prefix
  when a valid explicit scope overrides it.
- Omitted scope defaults to skill. Invalid, empty, quoted or trigger mutation
  scopes are preserved with an import warning and rejected by direct binding.
  Native emits configuration errors and throws on these invalid casts; neither
  runtime path emits an executable action.

The importer and native direct binder share the literal reference parser for set,
add, unset, location-set, location-unset and `variablemath`.
The importer retains ordinary unscoped and prefixed output. Canonical Helstera
actions bypass native prefix stripping, so `var=skill.Counter.Part scope=target`
continues to address the literal key `skill.Counter.Part`. The direct binder also
recognizes `unsetlocation`. Tests retain positional extensions, condition reads,
targeters, inline conditions, delay and repeat execution.

Literal variable names containing letters, numbers, `_`, `.`, `:`, or `-` are
accepted; templates and names requiring quoting remain unsupported because runtime
keys are literal. Missing names cannot consume an execution targeter. Import
rejections run before mob event projection and carry the original source and
warning into the migration report; invalid direct references cannot mutate a
registry.

Fixture `srv-paper-variable-action-scope-20261121-r2` records 428 observations:
31 valid reference forms × four operations × imported/native-direct/canonical-direct
paths, plus seven invalid forms × four operations × imported/native-direct paths.
Native-direct feeds the original source verbatim to the production binder. Each
row records both backends' relevant scalar/location keys before and after dispatch
across skill, caster, target, separate trigger, caster world, other world and
global registries; snapshots include keys containing that case's unique prefix.
Twelve distinct
entity UUIDs, native parsed scopes, native results/errors, source, mapped action
and binding/import diagnostics are retained in 433 archived files.
The 372 valid executions and 56 rejections pass. All 56 native invalid-scope
diagnostics and 28 direct binding rejections remain in the archive. The independent
audit passed against that run's source and JAR and rejects 36 altered-evidence
controls. Plugin SHA-256:
`6bb8cb7c0360bce6aaa69110615f7dfe1f523e6cfd97523c2cb60a7676ce033c`.
[Report](../tools/paper-integration/reports/paper-1.21.1-variable-action-scope-20261121-r2.json)
· [Raw archive](../tools/paper-integration/reports/paper-1.21.1-variable-action-scope-20261121-r2-logs.zip)

The earlier [r1 report](../tools/paper-integration/reports/paper-1.21.1-variable-action-scope-20261121-r1.json)
and [archive](../tools/paper-integration/reports/paper-1.21.1-variable-action-scope-20261121-r1-logs.zip)
retain their original 276-row imported/canonical-direct scope and still pass the
backward-compatible audit without checking against the changed current artifact.

The Paper comparison uses `setvariable`, `variableadd`, `variableunset` and
`setvariablelocation`; it does not establish native location-unset or mechanic
alias parity. This scope fixture does not exercise `variablemath`; the equation
comparison is documented below. Location selection is held constant at the
same origin/target point, so this fixture establishes registry selection only.
Dynamic keys, broader argument parsing, detached write behavior and the full
exact-artifact strict refresh remain outside this slice. The report stays
observation-only and promotes no parity matrix or release status.

This slice passed `./mvnw -B -ntp verify` with 2,519 tests and 11 SpotBugs checks.
The subsequent equation implementation changes the source and artifact; audit
this historical archive without `--check-current`.
Reproduce the scope fixture and archive checks with:

```sh
python3 tools/paper-integration/run-variable-action-scope-fixture.py --revision r3 --cache-directory <existing-paper-cache>
python3 tools/paper-integration/audit-variable-action-scope.py tools/paper-integration/reports/paper-1.21.1-variable-action-scope-20261121-r2.json
python3 tools/paper-integration/test-variable-action-scope-audit.py tools/paper-integration/reports/paper-1.21.1-variable-action-scope-20261121-r2.json
```

## Variable math equations (2026-11-21)

Native `variablemath{var=skill.Counter;equation=x*2+1}` now imports and binds to
the separate canonical `variable-math` action. Its equation uses the existing
INTEGER or FLOAT value as `x` and casts the result back to the same type. Missing,
expired and unsupported variable types do not create or replace a value. The
native reference rules above select the registry; canonical actions keep literal
dotted names. Delayed/repeated calls evaluate the current value and placeholders
on each execution, as covered by regression tests.

The shared equation parser implements:

- `equation`, `eq`, `e` in that priority order, defaulting to `x`. Repeated keys
  are case insensitive and last-wins, including empty values. One matching outer
  pair of single or double quotes is removed by the native placeholder parser.
- exp4j arithmetic, parentheses, powers, `x`, constants, functions and numeric
  comparisons. Unlike numeric mechanic arguments, an equation is always parsed
  as an expression. Syntax errors reject direct binding and preserve the original
  imported source with a warning, including mob event lines.
- Duration selection in the order `duration`, `d`, `expire`, `e`. The first
  present alias is parsed as an integral tick count; invalid or quoted text
  becomes zero without trying lower-priority aliases. `e` participates in both
  selections: `e=40` writes 40 with a 40-tick deadline, `e=x+1` has no new
  deadline, and `eq=x+1;e=40` increments with a 40-tick deadline. `e="40"`
  evaluates to 40 but supplies no new deadline.
- A positive duration replaces the deadline only after successful mutation;
  omitted, zero and negative durations and `save` preserve the old deadline.
  Durations that would overflow the supported millisecond deadline are rejected.

Configuration migration: native brace `variablemath`/`varmath` ignores `op` and
`value` and defaults to `x`, matching MythicMobs. The previous implementation
treated these options as a Helstera arithmetic extension. To retain that extension
use `var-math var=skill.Counter op=* value=2`; to use the native equation use
`variablemath{var=skill.Counter;equation=x*2}`. Existing positional extensions are
retained separately and are not native equation compatibility claims.

Fixture `srv-paper-variable-math-20261121-r1` records 30 cases × five scopes ×
native-direct/imported paths, totaling 300 observations. It dispatches native
`VariableMathMechanic.castAtEntity` with real Paper entities and feeds the raw
source to the production direct binder or importer. The archive retains 304 files,
eight unique entity UUIDs, 12 source hashes, the selected equation/duration, typed
before/after values for the case key in seven candidate registries, and deadline
and dispatch timestamps. Positive replacement deadlines must fall inside each
backend's dispatch interval plus the configured duration; all other deadlines
must remain unchanged. This does not cross a real-time expiry boundary.

270 rows have equal final values. The other 30 rows explicitly retain native
NaN-to-integer-zero, FLOAT NaN and FLOAT Infinity writes, while the runtime leaves
the previous value and deadline unchanged. Dispatch outcomes also differ: native
division by zero throws in 10 rows and missing/DOUBLE/STRING values return
`INVALID_CONFIG` in 30 rows; runtime dispatch returns without mutation. These
outcomes are recorded separately and are not counted as full behavior parity.

The independent archive audit passed against that run's source and JAR, and 36 altered
evidence controls are rejected. Plugin SHA-256:
`ee4333f20c596741aae64f662ae90c7a7e4f79c97667b0bdc33ef969d02a983f`.
[Report](../tools/paper-integration/reports/paper-1.21.1-variable-math-20261121-r1.json)
· [Raw archive](../tools/paper-integration/reports/paper-1.21.1-variable-math-20261121-r1-logs.zip)

That slice passed `./mvnw -B -ntp verify` with 2,548 tests and 11 clean SpotBugs checks.
The Paper cases cover finite expressions, option selection, numeric types and
deadline changes. Placeholder execution, malformed equations, `varmath`, canonical
extensions and detached-target rejection additionally have unit coverage; they
are not established by this Paper fixture. Dynamic variable names, wider
placeholder grammar, wider location selectors, complete native skill dispatch and the
full exact-artifact release refresh remain open. No parity matrix or release
status is promoted.

```sh
python3 tools/paper-integration/run-variable-math-fixture.py --revision r2 --cache-directory <existing-paper-cache>
python3 tools/paper-integration/audit-variable-math.py tools/paper-integration/reports/paper-1.21.1-variable-math-20261121-r1.json
python3 tools/paper-integration/test-variable-math-audit.py tools/paper-integration/reports/paper-1.21.1-variable-math-20261121-r1.json
```

The later location-value change updates the source and artifact. The equation
archive remains valid historical evidence; omit `--check-current` for that archive.

## Variable location values (2026-11-21)

Native `setvariablelocation{var=target.Point}` stores the caster's location in the
recipient's variable registry. The outer entity or location target chooses the
dispatch context, not the position to store. The previous implementation ignored
`value` and used that outer target's location. Raw native brace syntax and imported
actions now use a shared literal selector parser, and the importer explicitly
emits the selected `value` so canonical actions retain the same meaning.

Supported mechanic names are native `variablesetlocation`, `setvariablelocation`
and `setvarloc`, plus the existing Helstera `variablelocationset` alias. Value
arguments follow native priority `value`, `val`, `v`, `amount`, `a`; repeated
identical keys are case insensitive and last-wins. The supported static selectors
are normalized without losing their position source:

| Source selectors | Stored position |
| --- | --- |
| Omitted, `@self`, `@caster`, `@boss`, `@mob`, `@selflocation`, `@casterlocation`, `@bosslocation`, `@moblocation` | Caster |
| `@origin`, `@source` | Skill origin |
| `@trigger`, `@triggerlocation` | Trigger |

Coordinates, world, yaw and pitch are retained, and execution reads the selected
entity's current location. A missing position returns before scalar replacement.
Location dispatch has no entity recipient, so TARGET scope leaves all registries
unchanged instead of borrowing the caster or an earlier target. Duration/save
use the existing location lifetime behavior: `save=true` with zero duration gives
deadline 0, ordinary zero duration gives marker 1, and nonzero duration is stored
as raw `ticks * 50`. In particular, `duration=20` stores the absolute deadline
1000, which is already expired; this is the observed native behavior, not a
20-tick future lifetime.

Raw quoted selectors, empty values, unknown selectors, coordinates, dynamic
selectors, options and unimplemented target selectors fail direct binding and
retain their imported source with warnings. Native does not share all these
rejections: unknown/quoted names can enter custom-targeter resolution, and an
empty value can throw. The supported subset intentionally does not reproduce that
fallback. Existing strict duration/save validation remains unchanged; this slice
does not establish complete native malformed/duplicate lifetime-option handling.

Migration: use `setvariablelocation{var=target.Point;value=@origin}` to save the
skill origin, or `value=@trigger` for the trigger position. To keep the old outer
recipient position behavior, use canonical
`variable-location-set var=Point scope=target @target` without `value`. Canonical
actions without a value, literal dotted keys and non-brace positional extensions
retain their previous behavior. Native `variableunset` deletes location values;
`locationunset`/`unsetlocation` remain Helstera extensions, not newly established
native mechanic aliases.

Fixture `srv-paper-variable-location-20261121-r1` records 15 cases × five scopes ×
entity/location dispatch × native-direct/imported paths, totaling 300 observations.
Caster, recipient, trigger, origin and outer location have distinct XYZ, yaw and
pitch. Native calls construct `VariableSetLocationMechanic` and invoke
`castAtEntity` or `castAtLocation`; runtime calls bind the original or imported
action in the corresponding entity/location context. This does not exercise the
full native skill parser or scheduler.

All 300 live value and deadline comparisons pass. Native dispatch records 252
`SUCCESS`, 30 TARGET/location `INVALID_CONFIG` outcomes and 18 missing-trigger
`NullPointerException` outcomes for `value=@triggerlocation`. Runtime records dispatch without mutation for
the latter 48 cases, so the report does not claim equivalent exception behavior.
Within the successful writes, 18 raw deadline-1000 location values disappear on
read. Native raw stored values and subsequent live reads are recorded separately;
the runtime live read matches. Snapshots cover each case's relevant keys in seven
candidate registries, not all server state. The 304-file archive retains eight
unique entity identities and 13 source hashes.

The independent audit passed against that run's source and JAR, and all 49
rehashed altered-evidence controls are rejected. Plugin SHA-256:
`a607c685d8cb066cfc619c7885dfc612d2d62294998edc61eb0d522228178968`.
[Report](../tools/paper-integration/reports/paper-1.21.1-variable-location-20261121-r1.json)
· [Raw archive](../tools/paper-integration/reports/paper-1.21.1-variable-location-20261121-r1-logs.zip)

`./mvnw -B -ntp verify` passes 2,576 tests and 11 clean SpotBugs checks. Regression
tests additionally cover all static selector aliases, cross-world values,
unsupported-input preservation, canonical/positional controls and delayed/repeated
location reads. Native mechanic aliases are confirmed by inspection of the pinned
JAR's registration metadata, not full skill parsing in this fixture.
A pre-existing numeric-recipient test now retains its mock world
strongly, preventing garbage collection of Bukkit Location's weak world reference.
Dynamic location selectors, broader target/AI selection, real-time future expiry crossing,
complete native dispatch and the full exact-artifact strict refresh remain open.
This comparison is observation-only and promotes no parity or release status.

```sh
python3 tools/paper-integration/run-variable-location-fixture.py --revision r2 --cache-directory <existing-paper-cache>
python3 tools/paper-integration/audit-variable-location.py tools/paper-integration/reports/paper-1.21.1-variable-location-20261121-r1.json
python3 tools/paper-integration/test-variable-location-audit.py tools/paper-integration/reports/paper-1.21.1-variable-location-20261121-r1.json
```

## Variable location AI targets (2026-11-21)

Native location mutations now also accept bare `value=@target` / `@T` and
`value=@targetlocation` / `@targetLoc` / `@TL`, case insensitively. Direct binding
and imports use the same parser and preserve the distinction between the two
selectors. Options, quoted and dynamic selectors remain unsupported.

The first AI-target increment supports model casters backed by Bukkit `Creature`
with no runtime threat table. Both selectors read that creature's current `getTarget()`
when the action executes, including delayed and repeated actions. The stored
position includes the selected entity's world, XYZ, yaw and pitch. The outer
recipient still chooses the TARGET variable registry; it never supplies this
value. An outer location supplies no TARGET registry.

If the creature has no AI target, `@targetlocation` stores the skill origin,
matching the native empty-collection fallback in `SkillExecutor`. In contrast,
native `@target` produces a null entity and throws; the runtime leaves the old
scalar/location and deadline unchanged. It also preserves state if no usable
origin is available. Canonical actions without `value` keep their existing outer
target/location behavior.

That increment leaves the variable unchanged for players, non-`Creature` entities,
missing/invalid casters, retained terminal contexts and all threat-backed casters.
The later threat-target increment below extends this boundary. Terminal continuations can outlive the controller
that identifies whether a threat table exists, so they are excluded here.
An enabled empty threat table is also unsupported; it must not fall back to the
creature's vanilla target. Native threat selection reads a stored target, whereas
the runtime table's `top()` recomputes ranks and decay. Native player aiming and
non-creature last-aggro selection also require separate implementations. These
boundaries apply to both raw native syntax and imported canonical actions; they
do not change generic outer targeters.

For example, `setvariablelocation{var=target.Point;value=@targetlocation}` stores
the caster's AI target position (or its skill origin if no target exists) in the
receiving entity's `Point` variable, within the supported caster subset above.
The earlier static-selector archive remains historical evidence and is audited
without `--check-current` after this change.

Fixture `srv-paper-variable-location-target-20261121-r1` records 12 cases × five
scopes × entity/location dispatch × native-direct/imported paths, totaling 240
observations. It checks nine candidate registries, 12 entity UUIDs, distinct AI
target/recipient/trigger/origin positions and both actions' binding before target
changes. The native outcomes are 198 `SUCCESS`, 24 TARGET/location
`INVALID_CONFIG` and 18 missing-`@target` `NullPointerException` results. All live
values and deadlines match, including 36 origin fallbacks and 54 changed-target
writes. The runtime leaves state unchanged for native failures and records its
dispatch separately.

The 244-file archive retains 15 source hashes. The independent audit passed against
that run's source and JAR, and rejects all 56 rehashed altered-evidence controls. Plugin SHA-256:
`88f82621a911078edad0f0e39a2760ee513824538367a99666602f1ebaf99f1d`.
[Report](../tools/paper-integration/reports/paper-1.21.1-variable-location-target-20261121-r1.json)
· [Raw archive](../tools/paper-integration/reports/paper-1.21.1-variable-location-target-20261121-r1-logs.zip)

`./mvnw -B -ntp verify` passes 2,596 tests and 11 clean SpotBugs checks. The 20 new
runtime test executions also cover unsupported caster contexts, terminal
continuations after controller removal, cross-world values and delayed/repeated
target reads. The Paper comparison directly constructs native mechanics and
calls their cast methods; it does not exercise full native parsing/scheduling or
the unsupported caster contexts. Snapshots contain case keys in the nine selected
registries, not all server state. This remains observation-only and promotes no
parity or release status.

```sh
python3 tools/paper-integration/run-variable-location-target-fixture.py --revision r2 --cache-directory <existing-paper-cache>
python3 tools/paper-integration/audit-variable-location-target.py tools/paper-integration/reports/paper-1.21.1-variable-location-target-20261121-r1.json
python3 tools/paper-integration/test-variable-location-target-audit.py tools/paper-integration/reports/paper-1.21.1-variable-location-target-20261121-r1.json
```

## Variable location threat targets (2026-11-21)

Location `value=@target` / `@T` and `value=@targetlocation` / `@targetLoc` / `@TL`
now also read the saved threat target of a live non-player model caster. A threat
table takes precedence over vanilla Creature AI, including when the table is
empty or has entries but no selected target. An absent selection preserves the
old variable for `@target`, and stores the skill origin for `@targetlocation`.
If a saved UUID can no longer be resolved, both selectors preserve the old value
and deadline. That increment excludes players, missing/invalid casters and retained
terminal contexts; ordinary player support is described below. Without a threat table, the previous Creature-only rule applies.

`ThreatTable.currentTarget()` is a pure read: it does not consult the clock,
rank entries, advance decay or resolve entities. `selectTarget(UUID)` explicitly
commits an existing positive entry. AI decisions commit the accepted combat
target before target/state callbacks; recorded damage and explicit target
changes also update it. Focus commits immediately. Removing the selected entry,
clearing combat/the table, death and profile-driven target removal clear it.
Ranking/score queries never select a successor, although their existing decay
cleanup can invalidate a removed selected entry. Incidental flee targets do not
replace the saved threat selection. Ordinary threat additions change scores;
they do not commit a target before the existing AI decision path accepts one.

This is deliberately separate from implementing the native threat engine.
MythicMobs 5.7.2 selects the first threat entry immediately and switches to a new
holder only when its value exceeds the saved target's value by more than 10%.
Its `clearTarget()` can even retain a saved target after removing that target's
map entry while others remain. Helstera retains its existing AI target policy,
focus amount, ranking and decay behavior. These differences are not claimed as
compatible; the new contract is reading the currently committed target without
changing the selection as a side effect of a variable write.

Fixture `srv-paper-variable-location-threat-20261121-r1` records 240 observations:
12 cases × five scopes × entity/location dispatch × native-direct/imported paths.
Native setup gains 100 threat for A, then 105 for B, retaining A under native
switching rules. Runtime setup explicitly commits A with the same scores. Both
actions are then bound before the selection changes to B or the table is cleared.
A third, distinct vanilla AI target remains installed even for empty-table cases.
Snapshots verify that selected UUIDs and both score maps stay unchanged during
the variable reads.

The native outcomes are 198 `SUCCESS`, 24 TARGET/location `INVALID_CONFIG` and
18 missing-`@target` exceptions; the runtime preserves state for those failures.
All live values and deadlines match, including 36 origin fallbacks and 54 writes
after changing the saved selection. The 244-file archive retains 14 entity UUIDs,
17 source hashes and case-key snapshots across ten candidate registries.
The independent audit passed against that run's source and JAR and rejects all
73 rehashed altered-evidence controls. Plugin SHA-256:
`da83199884e855b632a567a69d2e61c9a33d1f654aa319e35a0ad4fc4e2400ce`.
[Report](../tools/paper-integration/reports/paper-1.21.1-variable-location-threat-20261121-r1.json)
· [Raw archive](../tools/paper-integration/reports/paper-1.21.1-variable-location-threat-20261121-r1-logs.zip)

`./mvnw -B -ntp verify` passes 2,620 tests and 11 clean SpotBugs checks. The 24 new
test executions cover table selection lifecycle, production AI decisions and
callback ordering, blocked decisions, unresolved targets, non-Creature casters
with tables, terminal guards and delayed/repeated location reads. The Paper
fixture uses a real runtime table with an injected resolver, fixed clock and zero
decay; it verifies reading prepared selections, not the AI tick wiring, threat
mutation equivalence or native switching policy. Native mechanics are constructed
and cast directly. Player targeting, non-Creature last-aggro without a table,
terminal target selection, full native dispatch and full parity remain open.
The earlier AI-target archive is historical; omit `--check-current` for it.
These observations promote no release or compatibility-matrix status.

```sh
python3 tools/paper-integration/run-variable-location-threat-fixture.py --revision r2 --cache-directory <existing-paper-cache>
python3 tools/paper-integration/audit-variable-location-threat.py tools/paper-integration/reports/paper-1.21.1-variable-location-threat-20261121-r1.json
python3 tools/paper-integration/test-variable-location-threat-audit.py tools/paper-integration/reports/paper-1.21.1-variable-location-threat-20261121-r1.json
```

## Variable location player targets (2026-11-21)

Ordinary player casters without a runtime threat table now support the same bare
target-value aliases. These nested selectors execute on the server thread and
use player aiming independently of the skill recipient, trigger and outer target
location. The recipient still chooses the TARGET variable registry. Canonical
actions without `value` keep their existing outer-target behavior.

`value=@target` / `@T` traverses 32 blocks from the player's eye using Bukkit's
`BlockIterator(player, 32)`. It considers nearby living entities in the returned
order, using feet coordinates within each traversed block's inclusive expanded
region: X/Z from block minus 0.75 to block plus 1.75, Y from block minus 1 to block
plus 2.5. Solid or occluding materials stop traversal before testing that block's
candidates, except BARRIER. Creative players are skipped; the native algorithm
adds no explicit spectator, dead or invalid filter. The first eligible entity
supplies its complete location and orientation. A miss or unavailable iterator
falls back to the skill origin, unlike the missing mob-target exception case.

`value=@targetlocation` / `@targetLoc` / `@TL` traverses 64 blocks in the current
eye direction. Empty blocks and the native default transparent materials are
skipped; the latter use `isTransparent() || !isSolid() || !isOccluding()`.
Remaining blocks use their shape's `rayTrace` with `FluidCollisionMode.ALWAYS`.
The first intersection supplies the position; a miss uses the eye position plus
64 times the normalized look direction. Both results have yaw/pitch zero. This
explains why glass blocks entity aiming but is skipped by location aiming.
The native transparent set is generated from material properties, not a YAML
setting. Direct modifications to that native set by other plugins are outside
this implementation's default-rule contract.

Fixture `srv-paper-variable-location-player-20261121-r1` records 15 cases × five
scopes × entity/location dispatch × native-direct/imported paths: 300 observations.
Two real Mineflayer players use native player profiles; they are not ActiveMob
wrappers. Tests cover entity hit/miss, stone/glass obstruction, BARRIER passage,
creative-player exclusion, survival-player selection, block intersections,
unobstructed endpoints, aliases, save retention and rotation after binding.
There are 270 successful native writes and 30 TARGET/location `INVALID_CONFIG`
outcomes; runtime preserves state for those unavailable registries. All live
values and deadlines match. The 305-file archive retains two client sessions,
12 distinct entity UUIDs (players explicitly reused across paths), 17 source
hashes and case keys in nine candidate registries.

The independent audit passes with `--check-current` and rejects all 67 rehashed
altered-evidence controls. Plugin SHA-256:
`59e9a1cc8012712bb2a8352e38ccf454376cae0cb1040f8bb167ce4d83ec8b3f`.
[Report](../tools/paper-integration/reports/paper-1.21.1-variable-location-player-20261121-r1.json)
· [Raw archive](../tools/paper-integration/reports/paper-1.21.1-variable-location-player-20261121-r1-logs.zip)

`./mvnw -B -ntp verify` passes 2,639 tests and 11 clean SpotBugs checks. The 19 new
test executions additionally cover inclusive feet boundaries, candidate order,
spectators, delayed aiming, shape misses, terminal guards and canonical controls.
Overlapping-candidate ordering and spectator targets have unit/native-bytecode
coverage, not Paper coverage in this fixture. Native mechanics are constructed
and cast directly; full native skill dispatch/scheduling is not exercised. The
fixture uses one server-thread task for each complete comparison run so player
movement packets cannot intervene between its native and runtime reads.

Player models with threat tables, ActiveMob-wrapped players, terminal target
selection, non-Creature last-aggro without a table, selector options, external
transparent-set changes and full variable parity remain outside this scope.
Earlier archives retain their original artifacts and are audited without
`--check-current`. These observations promote no release or matrix status.

```sh
python3 tools/paper-integration/run-variable-location-player-fixture.py --revision r2 --cache-directory <existing-paper-cache>
python3 tools/paper-integration/audit-variable-location-player.py tools/paper-integration/reports/paper-1.21.1-variable-location-player-20261121-r1.json --check-current
python3 tools/paper-integration/test-variable-location-player-audit.py tools/paper-integration/reports/paper-1.21.1-variable-location-player-20261121-r1.json
```
