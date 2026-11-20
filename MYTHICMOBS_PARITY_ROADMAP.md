Reentrant mob commands (2026-11-20): synchronous onDespawn command callbacks
can remove their own caster or peers, run nested named/global/radius cleanup,
load the affected chunk and create a new mob. Each selected original UUID fires
one terminal callback; a child survives the original command snapshot unless a
later nested command selects it. Loading a chunk during removal cannot reattach
the caster. New retained entities in an unloaded chunk save their variables and
SkillTree before releasing their temporary runtime instance.

`srv-paper-reentrant-command-20261120-r3` verifies 1,920 observations across
60 direct/imported, loaded/unloading scenarios and three real Paper boots,
with 432 distinct spawned UUIDs and 432 terminal callbacks.
UUIDs, live callback variables, callback depth, explicit callback chunk loads,
visual counts, saved state, restart restoration and final cleanup are checked.
All 46 evidence mutations are rejected. Observer r1 remains diagnostic evidence
of lost child state, missed nested child selection and caster reattachment.
Observer r2 stopped at the client’s five-minute deadline and remains incomplete;
the runner now grants this longer suite a fifteen-minute client budget.
Observer r1 also exposes a native pending-unregister race; the accepted
fixture waits 40 ticks before checking stable state and explicitly reloading.
Native callbacks remain deferred; Helstera callbacks remain synchronous. See the
[command contract](docs/MOB_COMMAND_COMPATIBILITY.md) for this verified scope and remaining limits.

The current artifact passes 2,423 Maven tests and SpotBugs. Full strict
`srv-paper-reentrant-command-strict-20261120-r1` passes 471 probes (452 passed,
19 expected rejections, zero failed/incomplete). The exact same JAR passes
unloaded commands `srv-paper-unloaded-command-20261120-r7` (3,910 observations,
42 mutations), loaded commands `srv-paper-mob-command-20261120-r6` (2,600,
29 mutations), distance `srv-paper-despawn-distance-20261120-r10` (448,
44 mutations) and precedence `srv-paper-despawn-precedence-20261120-r5`
(1,296, twelve boots, 30 mutations). Release now requires all five focused
reports plus the full strict catalog, pinned to this plugin and Paper build.

Matrix totals remain 116 implemented-runtime / 33 mapped-unverified /
3 unsupported-preserved / 0 missing / 2 blocked-by-external-plugin. Delayed or
visual callback mechanics, child SkillTree inheritance, native rapid-reload
registry races, crash recovery, broader catalogs, integrations and other Paper
versions remain open. Full MythicMobs parity is incomplete.

Previous-artifact unloaded mob commands (2026-11-20): cleanup now preserves native selection
through the brief unload transition. Named kill and forced killall remove saved
NEVER/PERSISTENT targets; default killall exempts PERSISTENT. Matching radius
commands load the selected chunk. Stable unloaded targets remain saved and
restore their original UUID, variables and SkillTree. Terminal callbacks retain
caster context, then cancel newly queued work and delete state for removed mobs.

`srv-paper-unloaded-command-20261120-r6` verifies 3,910 observations over 23
scenarios and three Paper 1.21.1-133/MM 5.7.2 boots, using 460 distinct UUIDs.
Samples at ticks 1/4/5/6/10, independent native/runtime chunks, radius/world
negative controls, 460 callbacks across removal and final cleanup, snapshots and
final restart absence pass. All 42 evidence mutations are rejected. Native
callback scheduling and inactive-registry representation are explicit differences;
synchronous command callbacks are the verified scope. Renderer teardown now
removes owned entity objects even when UUID lookup is already empty during unload,
including attachments. Observers r2/r3 exposed the
original gap; r4 exposed missing callback context and premature tick-5 expiry;
rejected r5 exposed transient orphan visuals after rapid radius-triggered reload.
See the [command contract](docs/MOB_COMMAND_COMPATIBILITY.md).

That artifact passes 2,421 Maven tests and SpotBugs. Full strict
`srv-paper-unloaded-command-strict-20261120-r1` passes 471 probes (452 passed,
19 expected rejections, none failed/incomplete). The same JAR passes loaded
commands `srv-paper-mob-command-20261120-r5` (2,600 observations, 29 mutations),
distance `srv-paper-despawn-distance-20261120-r9` (448 observations, 44 mutations)
and precedence `srv-paper-despawn-precedence-20261120-r4` (1,296 observations,
twelve boots, 30 mutations). The release gate requires all four focused archives
for the exact plugin JAR and pinned server build.

Matrix totals remain 116 implemented-runtime / 33 mapped-unverified /
3 unsupported-preserved / 0 missing / 2 blocked-by-external-plugin. Callback
recursion and delayed/visual callback mechanics, mode-specific crash recovery,
native chunk callback replay, broader catalogs, external integrations and other
versions remain open. Full MythicMobs parity is incomplete.

Previous-artifact loaded mob commands (2026-11-20): `/helstera mob kill` and `killall` now
remove loaded definition-owned mobs with native name/faction filters and mode
exemptions. `killall` exempts NPC/PERSISTENT; `-p` bypasses this. Named kill includes
all modes. Player radius is inclusive and same-world. Misleading named/zero/console
radius syntax is explicitly rejected. Imported `Faction` now reaches the runtime.
Callbacks retain their live variable context; model/base entities, variable/tree
state and UUID/numeric snapshots are cleaned without onDeath. Players, unrelated
bound entities and pure models are protected.

`srv-paper-mob-command-20261120-r4` passes 2,600 state/restart observations,
1,040 callbacks and 117 ownership checks over 13 actual-command scenarios and
two Paper 1.21.1-133/MM 5.7.2 boots. All 29 evidence mutation controls are rejected.
Observers r1/r2 and rejected r3 remain diagnostics; r3 used an incorrect display
count for unnamed bound controls. See the [command contract](docs/MOB_COMMAND_COMPATIBILITY.md).

That artifact passes 2,416 Maven tests and SpotBugs. Full strict
`srv-paper-mob-command-strict-20261120-r1` passes 471 probes (452 passed,
19 expected rejections, zero failed/incomplete). The same JAR passes distance
`srv-paper-despawn-distance-20261120-r8` (448 observations, ten player controls,
44 mutations) and precedence `srv-paper-despawn-precedence-20261120-r3`
(1,296 observations, twelve boots, 30 mutations). The release gate requires all
three focused archives to match the exact release JAR and pinned Paper build.

Matrix totals remain 116 implemented-runtime / 33 mapped-unverified /
3 unsupported-preserved / 0 missing / 2 blocked-by-external-plugin. Unloaded
command targets, callback recursion, mode-specific crash recovery, native chunk
callback replay, broader catalogs, external integrations and other versions
remain open. Full MythicMobs parity is incomplete.

Previous-artifact Despawn precedence update (2026-11-20): effective `Persistent=true` now overrides
explicit Despawn; Options fields override top-level fields. Global Despawn accepts
YAML booleans only. Invalid values are preserved with warnings and native fallback.
Modern `config/config-mobs.yml` is read before the legacy root file.
`srv-paper-despawn-precedence-20261120-r2` passes 1,296 observations over twelve
Paper 1.21.1-133/MM 5.7.2 boots: 72 configurations, six global defaults, 864 entity
UUIDs, actual distance/save flags, 436 deaths/callbacks and 214 runtime shutdown
callbacks. Thirty mutation controls pass. The 214 native shutdown scheduling
exceptions are explicit and validated, not counted as successful native callbacks.

That artifact passes 2,408 Maven tests and SpotBugs. Full strict
`srv-paper-despawn-precedence-strict-20261120-r1` passes 471 probes (452 passed,
19 expected rejections, zero failed/incomplete). At that checkpoint, distance fixture
`srv-paper-despawn-distance-20261120-r7` passes 448 observations, ten player binding
controls and 44 mutation controls. That release gate required both distance
and precedence archives for that plugin JAR and pinned server build.
Previous unload/SkillTree evidence retains its original artifact hashes.

Matrix totals remain 116 implemented-runtime / 33 mapped-unverified /
3 unsupported-preserved / 0 missing / 2 blocked-by-external-plugin. At that checkpoint, kill-command
rules, mode-specific crash recovery, native chunk callback replay, broader
catalogs, external integrations and other versions remain open. Full parity is
incomplete. See the [precedence contract](docs/DESPAWN_PRECEDENCE_COMPATIBILITY.md).

Previous-artifact distance policy update (2026-11-20): default NORMAL now delegates distance
removal to Paper's entity family/world policy. CHUNK/NPC/NEVER/PERSISTENT explicitly
disable distance removal; save-to-disk flags follow mode. Natural despawn executes
`onDespawn` exactly once before controller cleanup, without `onDeath`.
`srv-paper-despawn-distance-20261120-r6` verifies 448 observations: 416 native parity rows and
32 explicit-radius extension controls, four entity families, direct/imported
configuration, 16/64/192-block player positions, empty/spectator worlds and
34 real callbacks. Ten actual-player binding controls preserve both saving states. All 44 evidence mutation controls are rejected.

Imported `Options.SpawningDistance` is now unsupported-preserved with a warning:
MM 5.7.2 does not use it as a radius. Explicit Helstera radius fields remain
extensions. Earlier native-support claims for that option are withdrawn.
Matrix totals are 116 implemented-runtime / 33 mapped-unverified /
3 unsupported-preserved / 0 missing / 2 blocked-by-external-plugin.
At that checkpoint, broader distances/families, Persistent/Despawn precedence, kill-command immunity,
crash recovery, external integrations and other versions remain open.

That JAR passes 2,400 Maven tests and SpotBugs. Full strict fixture
`srv-paper-despawn-distance-strict-20261120-r4` passes 471 probes (452 passed, 19 expected rejections,
zero failed/incomplete). The release gate also requires the pinned distance report
and archive to validate against this exact JAR/server. Same-artifact unload
`srv-paper-despawn-lifecycle-20261120-r15` passes 90 observations and 43 mutation controls;
three-boot restart `srv-paper-despawn-restart-20261120-r7` passes 50 observations and 36 controls.
Native cancelled-world and shutdown-command exceptions remain explicit.
See the [distance contract](docs/DESPAWN_DISTANCE_COMPATIBILITY.md) for limits, including the client's known
recipe-packet parser error and the withdrawn pre-fix evidence. Full parity is incomplete.

# MythicMobs Parity Roadmap

Previous-artifact shutdown/restart update (2026-11-20):
`srv-paper-despawn-restart-20261120-r5` verifies NORMAL/CHUNK/NPC/NEVER/PERSISTENT
through direct/imported real mobs over three Paper 1.21.1-133 boots with public
MM 5.7.2. Disposable mobs are removed before shutdown serialization; their base
entities, visuals and UUID state stay absent. NEVER/PERSISTENT restore the same
UUID, variables and SkillTree. NEVER remains unloaded and unforced until its chunk
is requested. Clearing a live SkillTree now writes an empty UUID snapshot, so an
older tree or legacy fallback cannot return. Fifty observations, six UUID snapshot
files, four lazy-load controls, eight real deaths and 36 evidence mutation controls
pass. Six synchronous runtime shutdown commands run exactly once. Native MM 5.7.2
instead throws six disabled-plugin scheduling errors and runs none: this is the
explicit `MM-5.7.2-shutdown-command-scheduling` exception, checked by shutdown order
and native worker stacks. Delayed/asynchronous shutdown skills remain unverified.

Previous-artifact unload regression `srv-paper-despawn-lifecycle-20261120-r12`
passes 90 observations (80 matching outcomes and ten documented native
cancelled-world exceptions), eight tree snapshots, nine visual checkpoints,
46 configured callbacks and 43 evidence mutation controls. Its schema 3 also
requires shutdown to retain only the four NEVER/PERSISTENT cancelled-world
registries. Schema 1/2 reports retain their historical shutdown expectations.
The earlier native chunk callback replay in r7 remains unresolved; instrumented
r10/r11 recorded event object IDs, thread/stack traces and two timing offsets
without reproducing it. Passing runs do not erase that rejected observation.

The preceding JAR passes 2,396 Maven tests, SpotBugs and full strict fixture
`srv-paper-despawn-restart-strict-20261120-r1`: 471 probes (452 passed,
19 expected rejections, none failed or incomplete). At that checkpoint, native distance/defaults,
kill-command immunity, crash recovery for these modes, external removal, broader
catalogs and other versions remain open. Matrix totals at that checkpoint were
117 implemented-runtime / 33 mapped-unverified / 2 unsupported-preserved /
0 missing / 2 blocked-by-external-plugin. Full MythicMobs parity remains incomplete.
See the [Despawn lifecycle contract](docs/DESPAWN_LIFECYCLE_COMPATIBILITY.md).

Next scoped target: mode-specific crash recovery. Compare retained UUID,
variables and SkillTree after an abrupt process termination against the last
completed save; distinguish guaranteed save boundaries from unsaved changes.
Then isolate the native rapid-unload/reload registry race and callback replay.
Delayed/asynchronous/visual callbacks, child SkillTree inheritance and PERSISTENT
callback-created children still need their own comparison evidence. Continue the
catalog, external-contract and version work packages below; this milestone does
not upgrade the broad Mob Options or complete SkillTree rows.

Previous-artifact timed SkillTree regression
`srv-paper-despawn-skilltree-restart-20261120-r3` also passes 115 observations
across three boots, four shutdown snapshots and twelve evidence mutation controls.
Original deadlines, parent expiry, branches/collections and dead-entity snapshot
cleanup were verified on that preceding production JAR.

Previous-artifact regressions retain their original hashes: full strict
`srv-paper-despawn-strict-20261120-r2` (471), native comparison
`srv-paper-random-spawn-replace-20261120-r77` (31), world/chunk persistence
`srv-paper-world-variable-life-20261120-r10` (256) and timed SkillTree restart
`srv-paper-despawn-skilltree-restart-20261120-r2` (115). They do not certify the
current JAR. Current and historical artifacts are distinguished in Paper evidence.

Earlier persistent mob unload/reload update (2026-11-20):
`srv-paper-world-variable-life-20261120-r8` passes 256 direct/imported variable
comparisons against public MM 5.7.2 on Paper 1.21.1-133. Actual managed persistent
mobs retain UUID scalar/location registries and SkillTree state across a real
chunk unload and two world unload/reloads. Temporary unload releases controllers
without running removal skills or discarding entity variables. UUID snapshots
retain the latest tree, including an empty tree; failed writes retain an in-memory
snapshot for reload and a shutdown retry. Bound persistent mobs recreate unsaved
visuals on reattachment, fixing display duplication: all four phases contain
exactly 22 visual entities. Eight tree checks, six disk snapshots and four actual deaths
verify restoration, cleared state staying absent and final cleanup. The independent
validator rejects 28 altered-evidence controls. The new production artifact passes
2,383 Maven tests and SpotBugs. Existing orphan displays from older builds are not
migrated by this change. Non-persistent entity cleanup, other despawn modes,
configured player death/respawn skill dispatch, asynchronous save/load races,
broader catalogs and other versions remain open. Matrix totals stay
117 implemented-runtime / 33 mapped-unverified / 2 unsupported-preserved /
0 missing / 2 blocked-by-external-plugin; full MythicMobs parity remains incomplete.

Previous-artifact regression refresh: full strict passes 471 probes (452 passed,
19 expected rejections), native MM comparison passes 31 probes, player death/respawn
passes 700 observations, player logout/restart 740, autosave/crash recovery 480,
entity/world/global restart 432, and timed SkillTree restart 115. These use the same
production JAR as the 256-observation world/chunk fixture. All 153 focused evidence
mutation controls and two client-order tests pass. See the latest hashes and scoped
claims in [Paper evidence](tools/paper-integration/STARTUP_EVIDENCE.md). No broad Matrix status is promoted by these regressions.

Earlier player death/respawn and immediate reconnect update (2026-11-20):
`srv-paper-player-life-20261120-r1` passes 700 observations on Paper 1.21.1-133
against public MM 5.7.2. Two real clients die, wait four seconds, respawn and each
reconnect four times directly from the socket-end callback. Values, types, locations,
original deadlines, death-time expiry, event writes, successive generations and
player isolation agree through direct/imported actions; deleted data stays absent.
All ten quits release both registries, and final disk files contain exactly the
six expected values per player. The independent validator rejects 41 altered-evidence
controls. Observed server quit-to-join intervals are 20–53 ms; client reconnect
initiation takes 0.018–0.050 ms. This establishes the observed local sessions,
not every native asynchronous save/load race. Event writes use fixture Bukkit
listeners, so configured death/respawn skill dispatch remains separately unverified.
That fixture left production code/JAR unchanged; its 2,377-test, SpotBugs and
471-probe strict results refer to the earlier artifact. Persistent mob unload/reload
now has the newer evidence above. Interrupted writes, broader catalogs and versions
remain open. Matrix totals remain
117 implemented-runtime / 33 mapped-unverified / 2 unsupported-preserved /
0 missing / 2 blocked-by-external-plugin; full MythicMobs parity is incomplete.

Earlier player autosave update (2026-11-20): `srv-paper-player-autosave-20261120-r2`
passes 480 direct/imported observations across three actual Paper 1.21.1-133
boots with public MM 5.7.2. Two clients stay online while the ordinary configured
saving clocks write successive values. Three online checkpoints and two SIGKILL
recoveries verify typed values, locations, unchanged deadlines, offline expiry,
updated generations and deleted data staying absent. The probe never calls native
save methods or patches clock behavior. All 29 altered-evidence controls are rejected.
Existing player lifecycle regression `srv-paper-player-variables-comparison-20261120-r1`
passes all 740 observations and 31 evidence mutation controls on this artifact.
Focused native regression `srv-paper-random-spawn-replace-20261120-r73` also
passes all 31 existing probes on the current artifact.
Player saving now defaults to 300 seconds and retries failed logout writes;
invalid intervals fall back safely, and one failed player does not prevent peers
from saving. Zero disables periodic saves while logout/shutdown saves remain.
That earlier artifact passes 2,377 Maven tests and SpotBugs. Full strict fixture
`srv-paper-player-autosave-strict-20261120-r1` passes all 471 checks
(452 passed, 19 expected rejections, zero failed or incomplete). Recovery is proven
for completed checkpoints; mid-write interruption, power loss, performance under
large player counts and asynchronous save/load races remain open. Persistent mob
unload/reload now has separate evidence above. Death/respawn and immediate local reconnects now have the scoped evidence above.
Full MythicMobs parity remains incomplete; matrix totals stay
117 implemented-runtime / 33 mapped-unverified / 2 unsupported-preserved /
0 missing / 2 blocked-by-external-plugin.
See the [variable lifecycle contract](./docs/VARIABLE_LIFECYCLE_COMPATIBILITY.md).

Earlier player-variable lifecycle update (2026-11-19): focused fixture
`srv-paper-player-variables-comparison-20261119-r2` passes 740 observations across
three real Paper 1.21.1-133 boots against public MM 5.7.2. Two real clients verify
logout/login, offline expiry, typed values and locations, unchanged deadlines,
explicit unsets, trigger aliases and player isolation through direct/imported actions.
Quit-event mutations are saved before memory release; an online player's last
mutation survives shutdown. Independent per-player atomic files protect failed
writes and malformed data; committed legacy migration and empty tombstones prevent
stale shared data from returning. Pre/post-shutdown files and redacted server/client
logs are archived; all 31 incomplete/altered-evidence controls are rejected.
The same artifact passes 2,373 Maven tests and SpotBugs. Entity/world/global restart
regression `srv-paper-variable-persistence-comparison-20261119-r3` passes all 432
observations, eight stable UUIDs and three snapshots; its 12 evidence controls pass.
Full strict regression `srv-paper-player-variables-strict-20261119-r1` passes
471 probes (452 passed, 19 expected rejections, zero failed or incomplete).
Focused native regression `srv-paper-random-spawn-replace-20261119-r72` passes
all 31 probes on the same plugin artifact; both are separate from the custom
740-observation player lifecycle report.
Autosave and recovery from completed checkpoints now have the evidence above.
Death/respawn and immediate local reconnects now have scoped evidence above.
Asynchronous save/load races, non-persistent entity cleanup, broader catalogs and
other versions remain open. Persistent mob unload/reload has the newer evidence above.
Full MythicMobs parity is still incomplete. Matrix totals remain
117 implemented-runtime / 33 mapped-unverified / 2 unsupported-preserved /
0 missing / 2 blocked-by-external-plugin.
See the [variable lifecycle contract](./docs/VARIABLE_LIFECYCLE_COMPATIBILITY.md).

Timed SkillTree restart update (2026-11-19): `srv-paper-skilltree-expiry-restart-20261119-r1`
passes 115 observations across three real Paper 1.21.1-133 boots. Two persistent
mobs retain distinct UUID state; variables that expire during downtime stay absent,
while surviving scalar/collection/location values and parent state keep their
original absolute deadlines. Permanent replacements, explicit unsets, branch
restoration and death cleanup remain verified. Four shutdown snapshots and all
three boot logs are archived. Retained per-instance snapshots now discard elapsed
values on load/read/save, including parents. The evidence validator rejects 12
missing, duplicate, altered-deadline and changed-snapshot controls.
Focused native fixture `srv-paper-random-spawn-replace-20261119-r70` passes all 31 existing probes
on this artifact, including 420 variable lifecycle comparisons and six snapshot
observations. Full strict fixture `srv-paper-skilltree-expiry-strict-20261119-r1` passes
471 probes (452 passed, 19 expected rejections), with 2,358 Maven tests and SpotBugs.
This earlier milestone completed the timed persistent SkillTree restart slice.
Native scoped registries and connected-player restart now have separate evidence
above; remaining lifecycle cases and full MythicMobs parity remain open.
See the [variable lifecycle contract](docs/VARIABLE_LIFECYCLE_COMPATIBILITY.md) and [Paper evidence](tools/paper-integration/STARTUP_EVIDENCE.md).

Variable-lifecycle update (2026-11-19): focused strict fixture
`srv-paper-random-spawn-replace-20261119-r69` passes 31 probes on Paper 1.21.1-133
with public MythicMobs 5.7.2. Imported/direct duration aliases now reach shared
scalar/location stores and skill-tree snapshots. Expiration uses real elapsed
time; set replaces the deadline, while native addition retains or refreshes it
without recreating expired values. SetVariable defaults to INTEGER; native
INTEGER/FLOAT addition keeps its type.
The suite passes 420 lifecycle comparisons (five scopes, 14 cases, three actual-time
phases and two paths), plus six Paper snapshot-file observations. It retains
6,720 variable outputs, 1,020 trigger outputs and 17,680 dynamic Distance comparisons.
Skill-tree snapshot format 4 preserves absolute deadlines and reads formats 1–3.
Native location-mechanic deadline behavior and the separate Helstera arithmetic
extensions are documented. Native entity/world/global graceful restart is covered
by the newer scoped-registry fixture above. Connected players have separate evidence
above; broader catalogs and other versions remain open.
Full Conditions, Mechanics/Skills and Context Targeters remain `mapped-unverified`.
See the [variable lifecycle contract](docs/VARIABLE_LIFECYCLE_COMPATIBILITY.md) and [Paper evidence](tools/paper-integration/STARTUP_EVIDENCE.md).


This is the ordered worklist for closing the compatibility gaps recorded in the
[Parity Matrix](MYTHICMOBS_PARITY_MATRIX.md). It is an evidence-driven roadmap,
not a promise of identical behavior where Paper has no public API or a feature
requires another plugin.

## Baseline

Immediate continuation after player death/respawn and reconnect validation:

1. Extend native condition dispatch beyond the verified entity/location
   predicate slices for caster, target and living/non-living triggers. Location-targeter radius
   lists now have 672 condition-library/boundary comparisons; the earlier claim that inline guards were
   omitted is corrected: they were rejected at binding. Non-living trigger
   dispatch has 864 native comparisons; Distance/YDiff/FieldOfView/IsCaster/
   BoundingBoxesOverlap now have 14,280 framework comparisons, including 7,560
   wider-angle/rotation rows; Cuboid, faction, owner,
   parent, line-of-sight and vehicle add 6,820 more. All 11 pinned comparison
   classes have scoped dispatch evidence. Dynamic Distance has 17,680 comparisons
   for health/rounding, random, trigger and variable-default placeholders and whole-target-set failures.
   Trigger name/type/health/location retains 1,020 output checks (86 documented boundaries).
   Scoped variables add 6,720 output checks (818 documented boundaries), shared entity
   registries and location formatting through direct/imported runtime actions.
   Variable expiry and native INTEGER/FLOAT addition now add 420 lifecycle comparisons;
   six Paper file-round-trip checks retain skill-tree deadlines.
   Three actual Paper boots now verify 115 timed SkillTree observations and four shutdown snapshots.
   Native entity/world/global graceful restart now has a dedicated 432-observation comparison.
   Connected-player logout/login and online shutdown now have 740 observations across three boots.
   Periodic online saving and completed-checkpoint crash recovery now add 480 observations,
   three online checkpoints and two process kills. Real death/respawn and eight immediate
   local reconnects now add 700 observations and 41 evidence mutation controls.
   Persistent mob chunk/world unload/reload now adds 256 comparisons, eight tree
   checks, six snapshots, stable display counts and four actual deaths.
   Despawn unload/death coverage now adds 90 observations across all five modes:
   80 native/runtime matches and ten explicit native cancellation exceptions,
   with eight SkillTree disk snapshots, 46 actual callbacks and 43 mutation controls.
   Clean restart now adds fifty observations, six UUID snapshots and 36 mutation
   controls, with MM shutdown scheduling recorded as an explicit exception.
   See [the Despawn contract](docs/DESPAWN_LIFECYCLE_COMPATIBILITY.md).
   Paper 1.21.1 ChunkUnloadEvent is not cancellable; the negative control keeps a
   force-loaded chunk loaded. World cancellation is tested separately because
   native MM 5.7.2 cleans up despite cancellation; Helstera preserves its state.
   Next diagnose intermittent native chunk callback replay using public-event and
   duplicate-stack traces; then extend native distance boundaries/families, option precedence, kill-command immunity, mode-specific crash recovery and
   external removal paths, then configured player death/respawn skills and save/load races.
   Continue broader trigger/general placeholder families, player
   faction providers, relationship lifecycle/restart, non-living caster combinations
   and remaining generic entity
   predicates (UUID/name/velocity/passengers), non-living placeholders, condition
   actions beyond true/false and selection offsets/sorting/limits. Audit the remaining
   MythicEntity catalog: five aliases, custom-name priority and
   unregister/reregister lifecycle have scoped native evidence.
   Stable native `isLoadedMob` requires an ActiveMob;
   its later missing-ActiveMob fallback was not observed. ModelEngine hitbox-parent
   lookup, boundary-crossing stochastic populations and full dispatch remain open.
   The pinned 5.7.2 `MobsNearOrigin` name is a targeter; its Helstera condition is
   an extension.
2. Compare Premium-enabled dynamic radius when an appropriate native artifact is
   available. The public-build static-1 boundary and public string placeholders
   provide scoped evidence only; do not treat them as Premium condition parity.
3. Continue the remaining Conditions and RandomSpawns catalog with native
   positive/negative comparisons; do not promote the complete rows from focused tests.
4. Work through the remaining 33 mapped-unverified rows, the two intentionally
   preserved features and two external-plugin dependencies below, including
   cross-version Paper evidence and restart/lifecycle coverage.

Audited against the matrix on 2026-11-20:

| Matrix status | Rows | Meaning |
| --- | ---: | --- |
| `implemented-runtime` | 116 | A runtime implementation exists; the row's evidence still limits the claim. |
| `mapped-unverified` | 33 | Mapping or partial runtime support exists, but the full stated behavior is not verified. |
| `unsupported-preserved` | 3 | The source is intentionally preserved with a reason; it is not applied. |
| `missing` | 0 | No meaningful implementation exists. |
| `blocked-by-external-plugin` | 2 | A capability depends on an external integration that is not active. |

The latest full release fixture is `srv-paper-reentrant-command-strict-20261120-r1` on Paper 1.21.1-133, with 471 probes: 452 passed, 19 expected rejections, and zero failed or incomplete. It retains the 47 Potion lifecycle assertions and previous runtime/client workflows; imported variable-condition composition and the skill damage-trigger, tag, and cancellation probes pass on plugin SHA-256 `59dc5e9b21762a5306768f13f5ba6a1b1561e6e1c3f03d2a58b7f3421fe04675`. Focused native/imported MM 5.7.2 evidence has its own scope and does not replace the full release catalog. See the [release evidence index](tools/paper-integration/release-evidence.json), [Paper evidence](tools/paper-integration/STARTUP_EVIDENCE.md), and [version matrix](tools/paper-integration/version-matrix.json). This artifact passes 2,423 Maven tests and SpotBugs. The strict radius controls verify exact-zero mob defaults, explicit type selection, ArmorStand inclusion and restored caster state; the focused native comparison is scoped separately.

Condition-expression hardening: `SkillService` recognizes operators only at
the expression level, leaving quoted strings and nested arguments intact.
Explicit `;` or `|` lists can contain boolean subexpressions. Malformed groups,
quotes, and missing operands fail to bind instead of falling back to a leaf.
`SkillServiceTest` covers positive and negative cases;
`MigrationRuntimeConditionContractTest` composes imported `VariableEquals`
leaves using Helstera syntax. Native MythicMobs composition syntax is not
verified by these tests. The full Conditions catalog remains `mapped-unverified`.

The next targeter contract slice is now implemented in the shared API: spatial
targeters reject malformed, negative, non-finite, duplicate, and unknown private
arguments instead of falling back to handler defaults. API coverage includes
inline and fallback arguments for sphere, ring, cone, line-of-sight, and line.
Focused exploratory Paper fixture `srv-paper-targeter-context-alias-strict-20261115-r3`
also verifies conditional-targeter hyphen/underscore aliases, `condition`/`c`
argument aliases, and fail-closed private-argument validation with two real
players. This does not replace the full strict catalog, so the Context targeters
row remains `mapped-unverified`.

The shared API now also rejects inactive direct context entities for `@target`,
`@targets`, `@trigger`, and `@attacker`, including projectile shooters. API and
cross-module tests cover scalar fallback, inherited target lists, multi-target
filtering, direct/projectile triggers, and dead or removed entities. The strict
Paper fixture `srv-paper-context-targeter-inactive-strict-20261115-r1` now
verifies entity removal and trigger cleanup for this slice. The broader Context
targeters row still remains `mapped-unverified` until the complete targeter
catalog and its parameters have been verified.

The cone targeter family is expanded in the shared API and verified in the
strict release fixture `srv-paper-cone-targeter-families-strict-20261115-r2` with
`@PlayersInCone`, `@MobsInCone`, `@MonstersInCone`, `@AnimalsInCone`,
`@VillagersInCone`, and `@TamedInCone`. The aliases apply the existing
angle/length grammar and geometry after the requested entity-family filter. API
and Paper coverage include positional, named, mixed-family, boundary, and
malformed-input paths. This closes the cone-family slice only; the broader
Context targeters row remains `mapped-unverified` until the complete targeter
catalog and its parameters have been verified.

The canonical environment conditions now validate named arguments at both the
direct runtime and `SkillService` binding boundaries. `world-is` accepts
`world`/`w`/`name`, `biome-is` accepts `biome`/`b` plus `exact`/`e`,
`weather-is` accepts `weather`/`type`, `time-is` accepts `time`/`t`, and
`dimension-is` accepts `dimension`/`environment`/`env`/`world`. Unknown,
duplicate, empty, mixed positional/named, and malformed arguments fail closed;
legacy positional forms remain supported. AI and plugin contract tests cover
the direct and imported paths. No Paper evidence was added, so the Conditions
catalog remains `mapped-unverified`.

The direct `lunar-phase` and `ender-dragon-phase` bindings now apply the
importer's strict `phase`/`phases`/`p` argument contract before passing the
phase list to their legacy positional factories. Unknown, duplicate, empty,
mixed, invalid, and out-of-range numeric phase values fail closed. AI and
plugin contract tests cover the numeric lunar cycle and Ender Dragon phase
list. No Paper evidence was added, so the Conditions catalog remains
`mapped-unverified`.

The direct `mythic-mob-type` and `template-type` bindings now validate their
named value and flag aliases before runtime matching. Unknown, duplicate,
empty, mixed, and invalid values fail closed, including the
`exactmatch`/`em` boolean contract. AI and plugin contract tests cover Mythic
type matching and template lineage matching. No Paper evidence was added, so
the Conditions catalog remains `mapped-unverified`.

The direct `field-of-view`, `looking-at`, and `has-potion-effect` bindings now
preserve named aliases before legacy positional factories receive them.
Importer and runtime factories reject unknown, duplicate, empty, mixed,
non-finite, and out-of-range values; valid positional forms remain compatible.
AI, migration, and plugin tests cover direct binding and imported runtime
paths. No Paper evidence was added, so the Conditions catalog remains
`mapped-unverified`.

## Next targets after Potion observer/reconnect validation (updated 2026-11-18)

Focused `srv-paper-potion-sync-exploratory-20261117-r5` verifies identical-source
native MM 5.7.2/imported Haste and Invisibility on a player and Cow: 118 named
server assertions, 28 real-client checkpoints, 14 comparisons, and six
reconnections pass. Both effects retain exact quit/join duration traces for both
backends. The validator has 16 tests and requires actual metadata,
login packets, spawn/destruction, quit/join duration traces, all acknowledgments,
and cleanup. See the [synchronization contract](docs/POTION_SYNC_COMPATIBILITY.md).

The focused three-boot fixture `srv-paper-potion-restart-strict-20261118-r6`
now verifies Haste/Invisibility persistence for a player and Cow across one
restart, then confirms cleared effects stay absent after another restart. This
closes only that Paper 1.21.1-133/MM 5.7.2 persistence slice; see the
[restart evidence](tools/paper-integration/reports/paper-1.21.1-potion-restart-strict-20261118-r6.json).
Continue with remaining effect types and mechanic fields, long-disconnect
scenarios, Premium execution, native comparison release integration, complete
Conditions/Mechanics/Targeters catalogs, and other Paper versions. Graphical
rendering remains unverified. The original full worklist below and all 33 broad
`mapped-unverified` rows retain their scope.

Aura follow-up: simple named Mythic `onStartSkill`/`onStart`/`os` options now
import to a runtime callback that casts once when a new Aura state starts and
does not re-fire for a merged stack. Importer/runtime tests cover the alias,
target context, first-start behavior, and preservation of inline skill lists.
Focused Paper 1.21.1-133 + MythicMobs 5.7.2 fixture
`srv-paper-mythic-pack-strict-20261118-r21` now exercises a Mythic `onStart`
Aura imported into the production action path. It verifies one dispatch on
first start, no redispatch on a merged stack, and preservation of the affected
entity as target context. This narrow callback slice does not promote the broad
Skills/Mechanics rows. Follow-up fixture `srv-paper-mythic-pack-strict-20261118-r23`
now additionally verifies imported `onTick`/`onTickSkill` with a 3-tick
interval, preserved schedule on stack merge, affected-target context, and no
retained trigger/cancellable event. Importer and runtime tests cover alias,
invalid interval, inline-skill preservation, tick cadence, expiry, and merge
behavior. These narrow callback slices do not promote the broad Skills/Mechanics
rows. Follow-up fixture `srv-paper-mythic-pack-strict-20261119-r1` now also
verifies imported `onEndSkill`/`onEnd` for explicit removal, the
`DoEndSkillOnTerminate=false` suppression option, expiry and overwrite, plus
fresh affected-target context without the prior event. Aura components,
attachments, charges, and cancellation remain open. See [Paper evidence](tools/paper-integration/STARTUP_EVIDENCE.md#mythic-aura-onendskill).

Earlier protocol milestone:

Focused `srv-paper-potion-packets-exploratory-20261117-r2` now compares native
MM 5.7.2 and imported skills on a real client: 84 server assertions, 12 paired
cases, 24 phases, and 48 packets pass. All eight ambient/particles/icon flag
combinations, exact duration/level, natural/explicit removal, force ordering,
and cancelled addition are verified. Sixteen validator contract tests reject
incorrect or missing evidence. See the [packet contract](docs/POTION_PACKET_COMPATIBILITY.md).
This establishes protocol delivery; graphical rendering remains unverified.

At that milestone, remaining work included observer/reconnect synchronization and other effect types, the
remaining numeric mechanic fields, Premium execution, native comparison release
integration, and other versions. The remaining work packages below still define
the full completion target; the 33 broad `mapped-unverified` rows retain their
evidence boundaries.

Earlier lifecycle milestone:

The lifecycle milestone JAR passes 2,261 Maven tests, SpotBugs, and the 471-probe
strict Paper catalog, including all 47 Potion lifecycle assertions. Focused
`srv-paper-potion-mechanic-exploratory-20261117-r18` passes 144 assertions:
28 runtime, 22 timing, 47 imported lifecycle, and 47 native MM 5.7.2 lifecycle
checks against identical packaged YAML. Report contracts reject omitted or
false lifecycle assertions and missing cleanup. Remaining work includes client
rendering, other effect types and event-plugin interactions, remaining
numeric fields, Premium execution, native release integration, and other versions.
The 33 broad `mapped-unverified` rows retain their evidence boundaries.

Earlier numeric/timing milestone:

The numeric/timing artifact passes 2,261 Maven tests, SpotBugs, all 470 strict
Paper probes, the version-matrix check, and the evidence audit against its exact
JAR hash. The strict catalog now requires all 38 numeric and 28 Potion assertions.
It also requires 19 numeric and 22 Potion timing assertions for imported live
delay/repeat execution, changing recipient properties and variables, removed
targets, invalid-input effect preservation, bounded repeats, cancellation, and
cleanup. Focused numeric r9 and Potion r13 pass on the same JAR. The earlier
strict baseline `srv-paper-numeric-potion-strict-20261117-r3` remains archived.
The same JAR separately passes the 107-assertion native MM 5.7.2 fixture.
Report contract tests reject omitted probes and incomplete per-field/cleanup
evidence even when an aggregate success flag is present. The historical
milestones below retain the tests and artifact boundaries of each earlier slice.

The covered numeric factories now use a shared argument contract through direct
calls, `SkillService`, and migration. Reordered/omitted fields, explicit target
variants, invalid-source preservation, and fail-closed dynamic resolution have
API, AI, migration, and cross-module tests. Focused exploratory fixture
`srv-paper-numeric-mechanic-exploratory-20261117-r1` adds 22 real Paper assertions
for impact vectors, damage/healing, target isolation, speed/status, and cleanup.

Mythic `healpercent`/`percentheal`/`hp` and `damagepercent`/`percentdamage` now
use fractional units with default `0.1`, separate from Helstera's existing
percentage-point actions. The correction includes current-health damage and
5.7.2 overheal absorption behavior. See the [compatibility audit](docs/PERCENT_HEALTH_COMPATIBILITY.md)
for the exact version, accepted fields, upgrade instructions, and remaining
event/placeholder boundaries.
Focused fixture `srv-paper-percent-health-exploratory-20261117-r4` passes 27
assertions with real MM 5.7.2, including 14 identical-source native/imported
comparisons plus damage event modification/cancellation. It uses explicit
`@Trigger` recipients and leaves the distinct MM `@Target` selection contract
open. The full regression suite passes 2,220 tests and SpotBugs.

Official `setspeed` now sets absolute WALK/FLY `0.2f/0.1f * multiplier` through
player APIs and mob attribute bases, with static float defaults/aliases/type
selection audited against 5.7.2. Fixture
`srv-paper-set-speed-exploratory-20261117-r2` passes 41 server assertions,
including 16 native/imported mob and 16 player comparisons; the real client
verifies 32 ability-packet phases. The final artifact passes 2,229 tests and
SpotBugs. See the [speed audit](docs/SET_SPEED_COMPATIBILITY.md). This leaves
global target eligibility, physics/timing, and complete catalogs open.

Numeric placeholders now preserve full finite precision; raw-health, max-health,
and live-percentage aliases match the pinned upstream names and units. Existing
`hp`-as-percent profiles require migration. Inline `rounding/round/r` and signed
integer/float/fixed-decimal random families now pass importer and runtime checks.
Bare random ranges produce inclusive integers; decimal ranges require the
`random.float` family. The full build passes 2,243 tests
and SpotBugs. Focused fixture `srv-paper-percent-health-exploratory-20261117-r9`
passes 88 assertions, including 38 exact native-string comparisons, three
random type/bounds checks, and 18 imported dynamic outcomes. MM Free substitutes `1` for Premium-gated numeric
placeholders; the probe records this boundary without claiming Premium numeric
skill parity. See the [precision audit](docs/NUMERIC_PLACEHOLDER_COMPATIBILITY.md).

Selected dynamic numeric factories and Potion now resolve each recipient's
parameters independently, preserving execution metadata and inherited target
lists. Invalid recipient data cannot reuse the first target's values or remove
an existing Potion effect before validation. Numeric scheduling-hook tests
verify resolution at each delayed/repeated invocation; real Paper numeric/Potion
timing is covered by the current timing probes. Other timing interactions remain open.
See the [recipient contract](docs/NUMERIC_PLACEHOLDER_COMPATIBILITY.md#recipient-context).
The regression suite passes 2,250 tests and SpotBugs. Final-artifact Paper
fixtures `srv-paper-numeric-mechanic-exploratory-20261117-r4` and
`srv-paper-potion-mechanic-exploratory-20261117-r8` pass 32 and 26 assertions,
including per-recipient values, invalid-input isolation, and removal callbacks
that invalidate the current or later recipient. The same artifact retains all
88 MM comparison/regression assertions in
`srv-paper-percent-health-exploratory-20261117-r10`. These remain exploratory evidence.

Shared numeric mechanics, fractional-health mechanics, and Potion duration/level
now support exp4j expressions with the native 5.7.2 functions/operators and
rounded placeholders. Syntax validation, whitespace-preserving migration,
per-recipient execution, invalid-result no-ops, and Potion effect preservation
have API, AI, migration, and cross-module coverage. Generic single-token numeric
helpers retain Unicode and arbitrary variable-name support. Full regression
passes 2,259 tests and SpotBugs. Final-artifact exploratory Paper fixtures numeric
r6, Potion r10, and percent-health r12 pass 38, 28, and 107 assertions, including 19
native math-engine comparisons. Premium dynamic numeric skill execution remains
unverified. See the [expression contract](docs/NUMERIC_EXPRESSION_COMPATIBILITY.md).

1. Extend expressions/math to remaining numeric fields, other placeholder families, and recipient audits
   for remaining mechanics; add actual Premium numeric skill comparison when available.
   Keep the documented invalid-input, locale, and extended-random-range boundaries explicit.
   Audit skill-power/damage modifiers, healing events, and version-specific
   options against the declared upstream boundary. Complete MM global target
   eligibility, including creative/spectator defaults and `@Target` semantics;
   the speed comparison uses an eligible survival player and explicit `@Trigger`.
   Keep legacy speed multiplication explicit: imported `modifyspeed` defaults
   to `0`, direct calls to `1`, and `set-tick-speed` scales movement speed.
2. Numeric/Potion delayed/repeated recipient resolution is covered by real Paper
   probes, including changing variables, removed recipients, invalid-input effect
   preservation, bounded repeats, cancellation, and cleanup. Potion lifecycle
   now has 47 imported Paper assertions plus the same 47 native MM 5.7.2
   assertions for expiration, hidden chains, force, cancellation, instant effects,
   and cleanup. See the [lifecycle contract](docs/POTION_LIFECYCLE_COMPATIBILITY.md).
   Continue with client particles/icons, remaining effect types and event-plugin
   combinations, other versions, and the explicit cancelled-force-removal difference.
3. The full strict catalog now includes the 38 numeric and 28 Potion assertions,
   verified on the exact release artifact by `srv-paper-numeric-potion-strict-20261117-r4`.
   Integrate the remaining native fractional-health/placeholder and SetSpeed
   comparisons into release validation, then run the remaining Paper combinations.
   Other timing interactions, player motion/flight physics, complete max-health
   variants, and client-observed movement still require focused evidence. Keep broader
   matrix rows open until their complete catalogs meet the contract below.

## Completion contract

A capability can leave `mapped-unverified` only when all applicable checks below
are complete and the matrix, README, and evidence index agree:

1. Record the official source behavior and the MythicMobs/Minecraft/Paper version
   boundary being claimed.
2. Import valid syntax into a canonical representation; preserve malformed,
   conflicting, unknown, and out-of-scope source values with actionable
   diagnostics.
3. Connect that representation to a runtime consumer, or document why the
   feature cannot be applied on the declared platform.
4. Cover aliases, defaults, boundaries, negative cases, and fail-closed
   behavior in importer, runtime, and cross-module tests as applicable.
5. Verify observable behavior on Paper with production configuration and real
   entities/events. Include inverse controls, cleanup/state restoration, and
   client observation when the claim includes client-visible behavior.
6. Register required observations in the strict release catalog, validate the
   report and artifact hashes, and archive reproducible logs. Label synthetic
   dispatch, exploratory versions, and installed-plugin runs accurately.
7. Update the exact matrix row and the user-facing documentation. Do not infer
   catalog completion from a passing aggregate probe.

`unsupported-preserved` is acceptable only with a verified technical boundary,
preserved source data, and a warning. `blocked-by-external-plugin` is acceptable
only with a capability/version contract, fail-closed behavior, and an explicit
statement of missing installed-plugin evidence. Neither status counts as full
parity.

## Ordered work packages

### 0. Keep the evidence ledger trustworthy

- Reconcile all matrix rows with importer fields, runtime consumers, tests, and
  current reports; distinguish current evidence from historical fixture names.
- Keep release evidence manifests, report schemas, README, matrix, and Paper
  version declarations consistent.
- Make every release probe mandatory in the strict report contract, including
  dynamically registered probes; treat absent evidence as not observed.
- Keep the clean Maven, SpotBugs, schema, and strict Paper gates reproducible.

**Exit:** no stale or unverifiable evidence is presented as current proof, and a
clean checkout can run the documented quality gates.

### 1. Close Conditions and expression semantics

Matrix rows: `Conditions catalog`, `Full Mythic condition/filter grammar`, and
`Inline conditions`.

- Inventory the official Conditions catalog for the declared MythicMobs range;
  track aliases, parameters, defaults, entity scope, and version gates per item.
- Finish mapped-but-unverified conditions and identify truly missing conditions
  instead of silently treating unknown names as supported.
- Complete comparator, placeholder, quoting, nesting, negation, and list
  semantics; cover caster, target, trigger, location, and missing-context cases.
- Verify imported conditions through real Paper entities, with positive,
  negative, malformed, and state-restoration controls.
- Preserve official per-condition defaults and validate argument aliases;
  `DamageCause` now defaults to `ENTITY_ATTACK` and has strict Paper release
  evidence for its imported damage-trigger path. `StatDamageModifier` now has
  importer/runtime coverage plus strict evidence from both synthetic dispatch
  and a naturally generated Paper damage event. The official `Distance` condition
  now has an independent `implemented-runtime` row with importer/runtime tests and
  strict Paper evidence; retain the full catalog as the remaining Conditions-level
  work.
- Official MythicMobs 5.7.2 `HasInventorySpace` is now mapped to a dedicated
  `has-inventory-space` runtime condition. It checks the selected Player target's
  `PlayerInventory#firstEmpty()` result, rejects arguments, and keeps the existing
  `hasFreeInventorySlot`/`hasFreeSlot` project aliases stable. Importer, AI, and
  plugin cross-module tests cover full, available, non-Player, missing-target,
  and malformed-input cases; no new Paper evidence is claimed yet.
- Official MythicMobs 5.7.2 `MythicKeyId`, `keyid`, and `ariKeyId` now map to
  `mythic-key-id`. The runtime reads the exact skill-tree `key-id` variable,
  applies the `minecraft:attack` default, resolves skill placeholders, and
  compares case-insensitively. The existing imported `setvariable` mechanic can
  populate this variable. Missing metadata, unresolved placeholders, duplicate
  or unknown arguments, and invalid values fail closed; importer, AI, and
  cross-module tests cover the contract. Focused Paper fixture
  `srv-paper-mythic-pack-strict-20261118-r18` additionally runs the production
  importer on the `setvariable` skill, executes its generated action, and checks
  the shared key variable before verifying direct/imported match and mismatch
  cases, aliases, the default key, missing metadata, malformed arguments, and
  unresolved placeholders on Paper 1.21.1-133 with MythicMobs 5.7.2. This
  remains a narrow evidence slice; the full Conditions catalog is still
  `mapped-unverified`.
- Official MythicMobs 5.7.2 `factionsame` now joins `samefaction` as an alias
  for the exact `same-faction` evaluator. Importer, runtime, and cross-module
  tests cover the alias and reject supplied arguments. This closes a naming gap
  only; the existing exploratory Paper evidence remains the evidence boundary.
- Official MythicMobs 5.7.2 `creeperCharged` now joins `charged` and
  `isCharged` as aliases for the Bukkit `Creeper#isPowered()` evaluator.
  Importer, list-parser, runtime, and cross-module tests cover the alias and
  reject supplied arguments. Existing charged-state Paper evidence remains the
  evidence boundary.
- Official MythicMobs 5.7.2 `isNaturalBlock` now maps to `is-natural-block`.
  The audited upstream implementation only adapts the location and returns
  `true`; Helstera matches that behavior for a valid location and fails closed
  when location context is missing. Importer, list-parser, runtime, and
  cross-module tests cover the mapping and invalid arguments. No new Paper
  evidence is claimed for this upstream behavior boundary.
- Official MythicMobs 5.7.2 `food`, `hunger`, and `hungerlevel` now join the
  existing `foodlevel`/`foodLevelIs` mappings. The importer accepts the
  official `amount`/`a` and `food`/`f` forms, uses the upstream default `0`,
  and rejects unknown or duplicate arguments; runtime and cross-module tests
  cover Player-only evaluation. No new Paper evidence is claimed.
- Official MythicMobs 5.7.2 `sunny`/`isSunny` and
  `thundering`/`isThundering`/`stormy`/`isStormy` aliases now map through the
  importer, `SkillService`, and condition-list parser to the clear and thunder
  weather evaluators. Unknown and duplicate arguments remain preserved and
  fail closed; migration, API, AI, and plugin cross-module tests cover the
  alias paths and clear/thunder runtime states. No new Paper evidence is
  claimed, and the complete Conditions catalog remains `mapped-unverified`.
- Official MythicMobs 5.7.2 `sneaking`/`isCrouching`/`isSneaking` and
  `isWearing`/`wielding`/`isWielding` aliases now map through the importer,
  `SkillService`, and condition-list parser. Existing `is-sneaking` and
  `wearing` runtime evaluators are reused; wearing item arguments are strict,
  with unknown, duplicate, and empty values preserved and rejected. Migration,
  API, AI, and plugin cross-module tests cover Player sneaking, helmet matching,
  and alias preservation. No new Paper evidence is claimed, and the complete
  Conditions catalog remains `mapped-unverified`.
- Official environment and region naming variants now join the same canonical
  condition paths: `environment` maps to `dimension-is`, `inregion` maps
  to `region`, and singular `playerinradius` maps to
  `players-in-radius`. The list parser recognizes these aliases plus
  `hasplugin` and `mmtype` as condition starts, preventing comma-delimited
  source entries from being merged. Migration, API, AI, and plugin tests cover
  the slice; no new Paper evidence is claimed, so the complete Conditions
  catalog remains `mapped-unverified`.
- The existing `snowing`/`isSnowing` aliases now bind directly through
  `SkillService`, and the condition-list parser recognizes the remaining
  weather/time aliases such as `isRaining`, `isThundering`, `daytime`,
  and `nighttime`. API and AI tests cover direct binding and malformed
  argument rejection; no new Paper evidence is claimed.
- A direct-binding audit closed migrated relationship/state aliases for
  mounted, leash, owner, parent, sibling, claim, patrol-leader, invulnerable,
  burning, and global-cooldown conditions. Unknown arguments are rejected
  before runtime factory creation; this adds no Paper evidence.
- Track Mythic-only or premium conditions separately from Bukkit-native ones.
- The official MythicMobs 5.7.2 `Moist` check requires farmland moisture exactly
  `7`; the runtime now distinguishes it from `MoistureLevel`, which retains its
  explicit range behavior. Unit coverage includes fully hydrated, partial, dry,
  and non-farmland states. Existing exploratory Paper evidence covers levels 7
  and 0 only; this semantic correction has no new Paper evidence yet.
- Official MythicMobs 5.7.2 `Altitude` walks downward from the target's
  current block until a non-passable block or the `maxHeight` scan boundary;
  using `World#getHighestBlockAt` changes cave/overhang behavior and produces a
  one-block offset. Runtime, importer, and direct-binding tests now pin the
  local block surface, scan boundary, aliases, and malformed/duplicate
  arguments. Strict Paper fixture `srv-paper-altitude-strict-20261116-r4` now verifies the imported and direct runtime
  paths, local cave/overhang surface selection, the scan boundary, duplicate
  preservation, block restoration, and temporary-entity cleanup. Exploratory r79
  remains historical evidence for the superseded highest-block-column behavior.

- Conditional targeter syntax now covers the official `cond`/
  `targetconditions` parameter aliases, `nearestconditional`/`nearestif`
  targeter aliases, and inline `- condition true|false` list entries. Legacy
  delimited entries remain default-true, and malformed or duplicate private
  arguments fail closed. AI selector `mustsee` is honored; `interval` follows
  Paper's native reduced goal-check probability and `mustreach` requires a
  complete public Paper Pathfinder result. Missing, cross-world, empty, and
  exceptional paths fail closed. This is covered by API, AI, and importer
  tests; no new Paper evidence is claimed, so the complete Context Targeters
  catalog remains `mapped-unverified`.

**Exit:** every condition in the declared catalog has a verified implementation,
an explicit preserved/unsupported explanation, or a documented external/version
block; no unexplained condition gap remains.

### 2. Close Skills, Mechanics, Targeters, and Triggers

Matrix rows: `Mechanics catalog`, `Skills importer`, `Context targeters`,
`Trigger catalog`, plus the skill-related portions of `Audience actions`.

- Audit each mechanic's accepted syntax, aliases, defaults, target behavior,
  placeholders, timing, and cancellation rules; connect supported imports to
  production runtime actions.
- The numeric argument pipeline now retains keys through `SkillService` and
  uses the same validation at migration/direct-factory boundaries. This fixes
  swapped knockback fields and incorrect omitted defaults in pull/melee damage.
  Invalid dynamic numeric values skip that recipient before mutation for the
  covered selected-entity factories; caster-only/radial factories retain their
  single invocation context. The
  focused 22-assertion Paper numeric fixture above supersedes the earlier
  no-Paper status for those specific cases; extended percent semantics, player variants,
  timing, and the complete Mechanics/Skills catalogs remain open.
- Finish metaskill reference, parameter, inheritance, target-scope, and nested
  execution semantics; ensure unsupported lines remain visible.
- Nested `skill{s=...;cooldown=...}` now preserves validated cooldown
  metadata through Mythic import and applies a per-instance runtime gate;
  malformed, duplicate, and non-nested cooldowns remain preserved or rejected.
  Focused Paper 1.21.1-133 evidence is now recorded in
  `srv-paper-nested-skill-cooldown-strict-20261115-r3`, covering one execution
  per cooldown window, immediate blocking, reopening, and malformed-value
  preservation. The full Skills importer row remains `mapped-unverified` until
  the declared Skills and Mechanics catalogs are covered.
- Official `DirectionalVelocity` / `directionalvelocity` / `dvelocity` is now an independent `implemented-runtime` row. It validates `yaw`, `pitch`, `velocity`/`v`, and `mode`/`m`, applies all six documented modes including `MINIMUM`, supports imported execution and dynamic placeholders, and preserves invalid source lines with warnings. Strict Paper fixture `srv-paper-directional-velocity-strict-20261116-r1` passes the full 466-probe catalog. The public MythicMobs reference does not publish the `MINIMUM` formula, so the implementation boundary is documented as minimum directional projection with lateral velocity retained.
- Numeric state mechanics now accept their imported named forms at the direct runtime boundary as well: `set-no-damage-ticks` and `set-maximum-no-damage-ticks` accept `ticks`/`duration`/`time`/`t`, `set-fire` accepts the same aliases, and `freeze` accepts `duration`/`ticks`/`time`/`t`. Dynamic placeholders are resolved at execution time, negative values clamp to zero, and non-finite values fail during binding. This closes a parser-to-runtime contract gap; the full Mechanics catalog remains `mapped-unverified`.
- Health state mechanics now accept the imported `amount`/`a`/`value` forms at the direct runtime boundary for both self and target scopes: `set-health`, `modify-health`, `set-max-health`, and `modify-max-health`. Positional arguments remain compatible, placeholders still resolve at execution time, and finite numeric validation remains eager. Runtime coverage includes max-health attribute updates, target dispatch, and malformed/non-finite rejection; no new Paper evidence is claimed, so the full Mechanics catalog remains `mapped-unverified`.
- Movement speed mechanics now accept imported `amount`/`a`/`speed`/`factor` forms at the direct runtime boundary for both self and target scopes: `modify-speed` and `set-tick-speed`. Positional arguments remain compatible, execution-time placeholders are preserved, and literal non-finite values fail during binding. Runtime coverage includes Bukkit movement-attribute scaling and target dispatch; no new Paper evidence is claimed, so the full Mechanics catalog remains `mapped-unverified`.
- Damage and healing mechanics now accept imported named numeric forms at the direct runtime boundary: absolute `damage-target`, `heal-targets`, and `heal-self` accept amount/value aliases, while `heal-percent` and `damage-percent` self/target paths accept amount/percent aliases. Positional arguments, target dispatch, execution-time placeholders, and eager non-finite rejection remain covered by runtime tests; no new Paper evidence is claimed, so the full Mechanics catalog remains `mapped-unverified`.
- Impact and displacement mechanics now accept their imported named numeric forms at the direct runtime boundary: `ignite-targets` accepts `ticks`/`duration`/`time`/`t`, `knockback-targets` accepts power and vertical aliases, and `pull`, `leap`, and `leap-targets` accept radius/velocity aliases. `damage-melee` now accepts amount and radius aliases and resolves both values at execution time, preserving positional syntax and rejecting non-finite literals. Runtime coverage includes target and self dispatch, vector outcomes, nearby-target damage, placeholder-compatible binding, and invalid-input rejection; no new Paper evidence is claimed, so the full Mechanics catalog remains `mapped-unverified`.
- Potion now uses a shared API parser across migration and self/target runtime.
  Official aliases, SLOWNESS/100-tick named defaults, the legacy 60-tick positional
  default, flags, force replacement, dynamic type/duration/level, duplicate and
  malformed-input rejection, and original-line preservation are covered.
  Focused exploratory fixture `srv-paper-potion-mechanic-exploratory-20261117-r5`
  verifies real Paper 1.21.1-133 execution, inverse controls, cancelled removal,
  multiple targets, and cleanup. It does not replace the full strict release
  catalog; the Mechanics and Skills importer rows remain `mapped-unverified`.
- Complete official targeter names, filters, modifiers, ordering, limits,
  chaining, location/entity distinctions, and invalid-input behavior.
- Verify trigger registration, event actor/target/trigger context, priority,
  cancellation, natural timing, and cleanup. Separate synthetic event evidence
  from naturally produced events.
- Keep direct profile loading aligned with Mythic import aliases: `onDamaged` /
  `onHurt`, `onPickup`, `onDrop`, `onShoot`, `onMount`, and `onDismount` now
  resolve to the existing canonical trigger routes. This closes a parser-level
  silent-failure path; it does not claim additional Paper event coverage.
- Exercise inline and trigger conditions through their actual skill dispatch
  paths, not only direct condition evaluation.

**Exit:** each supported catalog item has importer-to-action/event integration
coverage and Paper evidence appropriate to its claim; remaining plugin-dependent
or unavailable APIs are preserved and documented.

### 3. Close Mob Options and entity-specific behavior

Matrix rows: `Universal options` and `Remaining entity-specific options`, plus
`Mob Options mapping and preservation`.

- Inventory the official universal and entity-family option catalogs, defaults,
  aliases, and precedence against the selected Minecraft/Paper API.
- Verify full production spawn ordering and interactions among options,
  equipment, native defaults, and event policies.
- Add real-entity coverage for every supported family and controls for wrong
  entity types, malformed values, explicit false, and omitted defaults.
- Preserve source values when Paper does not expose a safe public setter or the
  option is outside the declared version range.

**Exit:** every claimed option has production-spawn readback or observable event
evidence, and unsupported values retain source and reason.

### 4. Close Items, Equipment, Attributes, Drops, and legacy data

Matrix rows: `Inline names, lore, enchants, potions, skulls`, `Legacy numeric IDs
and data`, `Entity and item attributes`, `Drops and DropTables`, `Equipment/Items/
Attributes mapping`, and `Spawner importer` where it shares loot behavior.

- Expand ItemMeta component coverage, potion/skull/enchantment combinations,
  equipment slots and chances, and production custom-item resolution.
- Complete the documented legacy item/data families with explicit invalid-ID,
  durability, potion, spawn-egg, and block-state boundaries.
- Cover entity and item attribute slots, operations, UUID identity, stacking,
  precedence, and client-visible results where applicable.
- Finish DropTables amount/range/weight/luck semantics, placeholders, nested
  expansion, conditions, XP, natural death, and custom enchantment inputs.
- Identify statistical/natural-drop tests separately from deterministic tests;
  retain sample sizes and observed results rather than claiming exactness from
  a small random sample.

**Exit:** claimed combinations are verified through the production importer,
resolver, and natural or explicitly synthetic runtime path; unsupported item
components are not replaced with plausible vanilla values.

### 5. Close Custom AI and target selection

Matrix rows: `Factions and threat targeting`, `Basic goals and target
selectors`, `Ranged/leap/owner/faction selectors`, `Full
AIGoalSelectors/AITargetSelectors`, and `Native Pathfinder Goal composition and
mutation`.

- Build an official selector/goal coverage inventory including parameters,
  priority, clear/rebind behavior, preemption, and target changes.
- Verify the supported typed controller paths on Paper for movement, targeting,
  owner/faction relations, and dynamic selector updates.
- Define a hard boundary for native Goal Selector APIs unavailable through
  Paper's public API; preserve those inputs with diagnostics instead of
  implying native composition equivalence.
- Keep `AIPathfindingMalus` explicitly preserved until a supported public API or
  an approved, version-specific adapter exists.

**Exit:** all public-API-supported selectors and goals have behavioral evidence;
native-only functionality has an explicit preservation boundary and is not
misrepresented as equivalent.

### 6. Close SkillTree, Audience, and Custom Menus

Matrix rows: `Audience parsing and delivery`, `Audience actions`, `Full SkillTree
semantics`, and `Custom menu pagination and visibility`.

- Verify audience filters in combinations across permission, faction, world,
  radius, region, and reconnect/client protocol boundaries; retain the current
  shared-targeter `limit`/entity-filter evidence while extending client-visible
  coverage to each supported selector family.
- Cover SkillTree prerequisites, unlock conditions, nested casts, persistence,
  atomic writes, restart, death, unload/reload, config changes, and client UI.
- Complete menu condition parsing, pagination, visibility, action permissions,
  inventory transitions, reconnect cleanup, and supported client versions.
- Preserve the difference between parser tests, Paper server observations, and
  real-client observations.

**Exit:** lifecycle and multi-client claims are supported by repeatable Paper
and client evidence, including restart and cleanup paths.

### 7. Close Spawners and Random Spawn

Matrix rows: `Spawner parsing, cooldown, max-alive, persistence`, `Random
weighted mob pools`, `Spawner conditions and regions`, `Heightmap, ground,
border and player gates`, and `Mythic Random Spawns and DropTables`.

- Complete official spawner fields, scheduling, cooldown recovery, limits,
  quotas, chunk/world gates, and restart behavior.
- Exercise natural spawn locations and combined condition gates over terrain,
  world-border, player-distance, and loaded/unloaded chunk boundaries.
- Verify weighted pool distributions with declared sampling plans and prove
  per-instance random mob to DropTable coupling through natural death.
- Keep randomized observations reproducible where possible and report
  uncertainty rather than promising a fixed distribution from a small sample.

Progress (2026-11-19): `REPLACE`, an event-driven `DENY` subset, and an opt-in
bounded `ADD/CLUSTER` subset now flow
from nested `RandomSpawns` file discovery through migration output into the
runtime service. `REPLACE` covers custom Mythic mob IDs, equal-entry `Type`/
`Types` alternatives in comma-separated or YAML-list form (repeated entries
retain their relative weight), chance, priority, cooldown, world/biome/reason
filters, and conditions; failed custom spawns do not cancel the vanilla event.
`DENY` cancels matching creature-spawn events, while DENY-with-`Type` is
preserved as unsupported. `ADD/CLUSTER` is disabled by default and can be
explicitly enabled; its bounded sampler uses loaded chunks near Survival or
Adventure players, LAND/SEA, world/biome filters, chance, priority, cooldown,
and per-cycle/world caps. Candidate-point world, time, and weather conditions
map through the production location-only context. Entity-dependent and unknown
ADD conditions still fail closed; explicit weighted syntax, non-default levels, and
other unmapped options likewise produce no runtime projection. Focused
importer, runtime, and cross-module contract tests pass. Pinned Paper
1.21.1-133 fixture `srv-paper-random-spawn-replace-20261119-r16` passes all
eleven feature probes, including one managed ADD spawn near a real Survival
player, day/rain/wrong-world rejection controls, a clear-night positive case,
replacement and DENY controls, cooldown rejection/recovery, and preservation
of unsupported definitions. Five native Paper `NATURAL` attempts were
observed on a dark pad with a real player online; the imported DENY rule
cancelled all five. This
bounded approximation is not Mythic's exact generator or full RandomSpawns
parity, and the fixture is not the global strict release catalog; the matrix
row remains `mapped-unverified`.

Follow-up Paper fixture `srv-paper-random-spawn-replace-20261119-r18` adds a
world-border negative control for ADD: candidates outside the live Paper border
are rejected before the spawn adapter is called. Fixture r19 then exercises
equal-entry YAML-list `Types` alternatives through the production importer and
runtime; all 13 focused probes pass, with a 10:6 observation over 16 valid events
from a 1:1 entry pool. Comma syntax and duplicate entries have unit/contract
coverage. This is not a statistical distribution claim. The runtime unit test
covers the world-border fail-closed path. The broader terrain, player-distance,
multi-boot, and native generator gaps remain open. See the [r19 focused
report](tools/paper-integration/reports/paper-1.21.1-random-spawn-replace-20261119-r19.json).

The subsequent `srv-paper-random-spawn-replace-20261119-r27` passes 17 focused
probes, including 132 matching native/imported MM 5.7.2 evaluations. It fixes
omitted player-count amounts to exact zero, legacy positional amounts, and
zero-distance within/not-within defaults. Duplicate/unknown arguments and
fractional/overflow counts remain preserved. Inclusive count comparators remain
documented Helstera extensions; the native parser rejects them. Eleven NATURAL
attempts are denied after the dark pad's lighting settles. Full Conditions and
RandomSpawns rows remain open; see the
[compatibility contract](docs/PLAYER_RADIUS_CONDITION_COMPATIBILITY.md).

Fixture `srv-paper-random-spawn-replace-20261119-r21` adds ADD player-distance
condition evidence: PlayersInRadius, PlayerWithin and PlayerNotWithin each
pass independent spawn/reject controls after real condition evaluation.
All 15 focused probes pass, including four player modes, spherical distance,
exact zero/one counts and state restoration. Runtime failures remain rejected
under negation; counts above 513 are not truncated. These correct the pinned
5.7.2 contract and close the three ADD condition gates, while full generator
distance rules, terrain, multi-boot and native-algorithm coverage remain open.
The general strict radius probes now explicitly use Survival and restore the
prior mode. The same artifact passes all 471 strict release probes in
`srv-paper-player-radius-strict-20261119-r1`, plus 2,298 Maven tests and SpotBugs.

**Exit:** supported spawner paths have multi-boot natural generation evidence,
negative gate controls, and documented statistical limits.

### 8. Resolve external plugin and version boundaries

Matrix rows: `External drop and economy types`, `External item and model
adapters`, `MythicMobs model bridge`, and `Lands claim provider`; also maintain
the external integration contract.

- For each adapter, declare tested plugin/API versions and activate capabilities
  only after discovery, version checks, and hook registration succeed.
- Add installed-plugin Paper fixtures for supported Vault providers, MythicMobs
  bridge capabilities, and Lands; add absent, incompatible, missing-service,
  invalid-ID, and throwing-provider controls.
- Implement public API bridges for ItemAdder/CraftEngine/MMOItems/
  MythicCrucible/ModelEngine only when the API contract and testable plugin
  artifacts exist. Until then retain identifiers and report blocked capability.
- Expand the Paper/Minecraft matrix one release target at a time. Keep Paper
  1.21.1-133 as the only release baseline until another version passes its own
  complete strict gate; do not infer Spigot/Folia compatibility.

**Exit:** each active capability is proven for the exact plugin and server
versions recorded; every other combination stays explicitly unavailable or
unverified.

### 9. Final parity release

- Re-run the full matrix audit and regenerate status counts from the status
  column.
- Resolve every `mapped-unverified` row and review every `unsupported-preserved`
  and `blocked-by-external-plugin` row against its documented boundary.
- Run Maven tests/quality checks, report schema and catalog audits, and the full
  strict Paper gate for each declared release target.
- Archive reports, redacted logs, client captures where required, and exact
  plugin/dependency hashes; update README, matrix, integration contracts, and
  release notes together.

**Exit:** no unexplained gaps remain, all supported claims have applicable
runtime evidence, blocked capabilities have explicit prerequisites, and a clean
checkout reproduces every declared release gate.

## Current 33 mapped-but-unverified rows

| Workstream | Rows |
| --- | --- |
| Skills | Inline conditions; Context targeters; Audience actions; Mechanics catalog; Conditions catalog; Trigger catalog |
| Mob Options | Universal options; Remaining entity-specific options |
| Items, Equipment, Attributes | Inline names/lore/enchants/potions/skulls; Legacy numeric IDs/data; Entity/item attributes; Drops/DropTables; MythicMobs model bridge; Lands claim provider |
| Custom AI | Factions/threat targeting; Basic goals/target selectors; Ranged/leap/owner/faction selectors; Full AI Goal/Target selectors; Native Pathfinder goal composition/mutation |
| Skill Trees, Conditions, Audience | Audience parsing/delivery; Full condition/filter grammar; Full SkillTree semantics |
| Spawner, Random Spawn, Custom Menu | Spawner parsing/cooldown/max-alive/persistence; Random weighted pools; Spawner conditions/regions; Heightmap/ground/border/player gates; Custom menu pagination/visibility; Mythic Random Spawns/DropTables |
| Migration and Verification | Mob Options mapping/preservation; Equipment/Items/Attributes mapping; Skills importer; Spawner importer; Paper integration suite |

## Explicitly preserved or externally blocked today

- `Options.Gravity`: not in the official Options reference; retained with a
  warning and not applied.
- `AIPathfindingMalus`: Paper's public API does not expose native PathType malus
  mutation; retained with a warning and not applied.
- External drop/economy types: only the documented Vault/Economy provider slice
  is active; other provider and MythicCrucible/MMOItems behavior is not implied.
- External item/model adapters: ItemAdder, CraftEngine, MMOItems,
  MythicCrucible, and ModelEngine capabilities stay inactive until their public
  API/version contracts and installed-plugin evidence exist.
