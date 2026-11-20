# Despawn lifecycle compatibility

This contract covers actual chunk/world unload, reload, clean process restart and subsequent death on
Paper 1.21.1-133 with public MythicMobs 5.7.2. The deterministic runtime
policy is separated from the unresolved native replay observation below. Both directly configured Helstera mobs
and production importer output use real managed instances. It does not establish
the full Mob Options or Triggers catalogs.

| Mode | Accepted chunk/world unload | Reload | `onDespawn` | Subsequent death |
| --- | --- | --- | --- | --- |
| NORMAL | Remove entity, controller and UUID variables | Absent | Once | No death callback |
| CHUNK | Remove entity, controller and UUID variables | Absent | Once | No death callback |
| NPC | Remove entity, controller and UUID variables | Absent | Once | No death callback |
| NEVER | Suspend controller; keep entity and UUID variables | Same UUID reattached | None | `onDeath` once; state and snapshot deleted |
| PERSISTENT | Suspend controller; keep entity and UUID variables | Same UUID reattached | None | `onDeath` once; state and snapshot deleted |

NEVER and PERSISTENT retain scalar/location variables and the current SkillTree.
Their PDC marker supports reattachment; only PERSISTENT retains the existing
force-loaded-chunk policy. NEVER does not request force loading. Their temporary
removal still uses `ModelRemoveEvent.unloading()` and the existing
`persistent_chunk_unload` / `persistent_world_unload` causes for API compatibility.

`~onDespawn` imports to `mob.triggers.on-despawn`; the existing short
`~onDespawn:Skill` extension also maps. Invalid selectors and multiple event
suffixes remain preserved with warnings. A permanent removal callback runs before
model invalidation and AI detachment, after removal from the instance registry to
prevent recursive duplicate removal. Confirmed death, including its delayed animation cleanup, and temporary suspension do not emit
`onDespawn`. Native `@self` stays addressable in its death/despawn callback even
when Bukkit has already marked the caster dead/unloaded; ordinary execution still
excludes dead/unloaded targets. Callback targets remain subject to explicit filters.

All visuals bound to a base entity are now unsaved. The retained mob recreates them
when reattached; disposable mobs cannot leave saved orphan displays. Standalone
models retain their configured persistence. This does not migrate orphan visuals
created by older builds.

World unloading can be cancelled. Helstera preserves all five modes, controllers,
variables, trees and visuals when it is cancelled. **MM 5.7.2 ignores that
cancellation:** NORMAL/CHUNK/NPC are removed and emit `onDespawn`; NEVER/PERSISTENT
keep their entities but lose their active registries. The probe records this as
`MM-5.7.2-cancelled-world-unload`, an intentional compatibility exception. It uses
an independent cancelled-world scenario so this native mutation cannot contaminate
the accepted-world comparison. Paper 1.21.1 `ChunkUnloadEvent` is not cancellable;
the chunk negative control instead requests unloading a force-loaded chunk and
checks that it remains loaded with all state intact.

Evidence: [final report](../tools/paper-integration/reports/paper-1.21.1-despawn-lifecycle-20261120-r12.json)
and its [redacted logs and snapshots](../tools/paper-integration/reports/paper-1.21.1-despawn-lifecycle-20261120-r12-logs.zip).
The final artifact SHA-256 is
`8912a5f9ebe520ee5cfd2b49dbd29075d9e860acbee6e3adc15365ea54a90542`.
There are 90 observations: 80 matching native/runtime outcomes and ten explicit
cancellation exceptions. The independent validator checks the full Cartesian
coverage, UUID identities, actual unload/load/death events, mapping results,
46 configured command callbacks, nine visual checkpoints, eight disk trees and
shutdown registry membership. Schema 3 requires only the four cancelled-world
NEVER/PERSISTENT registries after the later shutdown; cancellation itself still
preserves all ten mobs until shutdown starts. Location observations compare the complete world
and coordinates; death observations wait beyond the delayed cleanup task. Visual counts are 110 initially, 44 after accepted
reload and zero after death. Forty-three mutation controls reject missing, duplicate
or altered evidence. The earlier
[baseline diagnostic](../tools/paper-integration/reports/paper-1.21.1-despawn-lifecycle-20261120-r3.json)
contains observed failures on the previous artifact and is not passing evidence.

A separate [rejected diagnostic](../tools/paper-integration/reports/paper-1.21.1-despawn-lifecycle-20261120-r7.json)
recorded native chunk `onDespawn` commands executing 2–4 times, with runtime
commands remaining exactly once. The validator rejected all 12 affected rows.
The cause is unresolved; do not infer an always-once native callback guarantee.
Subsequent instrumented runs r8 and r9 each passed and recorded 18 native public
MythicMobDespawnEvents, with one event and one command per actual native despawn.
Further historical runs r10/r11 recorded active-object identities, native
event thread/stack traces and chunk-delay offsets of 0 and 4 ticks, without
reproducing the replay. The current-artifact r12 passes the same event checks.
Those passes do not erase the failing observation. The final probe retains public
event identity logs and duplicate-call stacks for further diagnosis. No native
scheduler, clock or plugin bytecode is patched by the fixture.

Clean process restart additionally covers all five modes through directly configured
and imported real mobs. NORMAL/CHUNK/NPC are removed before shutdown serialization;
their base entities, visuals and UUID registries stay absent after both restarts.
NEVER/PERSISTENT restore the same UUID, saved scalar/location values and SkillTree.
NEVER's chunks remain unloaded and unforced until explicitly requested, then the
ordinary entity-load path reattaches the controller. Only PERSISTENT forces chunks.
Each live entity has exactly eleven recreated visuals. After clearing a live tree,
shutdown writes an empty UUID snapshot, preventing an earlier tree or legacy mob
snapshot from being restored. Deleting values and killing the retained mobs leaves
no surviving registry or UUID snapshot at the end of the third boot. The fixture
records `save=false` entity values according to the existing native entity registry
contract; it does not infer player retention rules from entity behavior.

The command-based shutdown `onDespawn` path has an additional explicit exception:
**MM 5.7.2 attempts to schedule six commands after its plugin is disabled** and
throws six `IllegalPluginAccessException` errors; none of those native commands
execute. Helstera executes its six synchronous commands once before discarding
state. This is recorded as `MM-5.7.2-shutdown-command-scheduling`, not a matching
native callback result. The validator checks shutdown order and each error's
native worker stack. Delayed/asynchronous shutdown skills are outside this claim.
Post-restart death commands agree: eight actual deaths and eight configured
commands across both implementations, with no despawn command for these deaths.

The [restart report](../tools/paper-integration/reports/paper-1.21.1-despawn-restart-20261120-r5.json)
and its [redacted logs and snapshots](../tools/paper-integration/reports/paper-1.21.1-despawn-restart-20261120-r5-logs.zip)
record fifty observations over three clean boots, six UUID snapshot files and
four lazy-load controls. The tested plugin SHA-256 is
`8912a5f9ebe520ee5cfd2b49dbd29075d9e860acbee6e3adc15365ea54a90542`.
The independent validator rejects 36 altered-evidence controls, including hidden
native errors, errors outside shutdown, wrong exception stacks, missing callbacks,
resurrected deleted values/trees, eagerly loaded NEVER chunks and duplicated visuals.
The earlier restart r2 is a pre-fix observation. Restart r3 was rejected because
its validator incorrectly expected a dead PERSISTENT mob's chunk to remain forced;
that expectation was corrected to follow entity liveness without changing the
production artifact. Neither diagnostic is passing evidence.

Reproduce serially with unused revision identifiers (fixtures share port 25731):

```bash
./mvnw verify
python3 tools/paper-integration/run-despawn-lifecycle-fixture.py --revision r13 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-despawn-lifecycle-evidence.py tools/paper-integration/reports/paper-1.21.1-despawn-lifecycle-20261120-r13.json
python3 tools/paper-integration/run-despawn-restart-fixture.py --revision r6 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-despawn-restart-evidence.py tools/paper-integration/reports/paper-1.21.1-despawn-restart-20261120-r6.json
```

Open work: intermittent native chunk callback replay, native player-distance
behavior and entity-family defaults, kill-command immunity differences between
NPC/PERSISTENT and other modes, crash recovery for these five modes, external
removal paths, late event cancellation, non-living casters, delayed/asynchronous
shutdown skills and other Paper/MM versions. Clean restart evidence does not
establish crash or mid-write recovery. Existing timed PERSISTENT restart and
variable-lifetime suites provide separate evidence.
