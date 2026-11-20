# Rapid chunk reload and native registration

Scope: public Paper 1.21.1-133 and MythicMobs 5.7.2, COW mobs, all five Despawn
modes, direct/imported Helstera configuration, separate native/runtime chunks.
The fixture requests ordinary chunk unloads and reloads them 1, 4, 5, 6, 10, 11
or 40 server ticks after the real ChunkUnloadEvent. Every combination runs three
times. PERSISTENT force-loading is explicitly released to permit the test.
No native clocks, handlers, private registries or bytecode are modified.

Verified comparison: [r3 report](../tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r3.json)
and [redacted raw archive](../tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r3-logs.zip).
The production artifact is `b7aab2b68eeb55b923a72470780fff62b14a5e353ed55b7f8bc6bc66b4acf898`.
It is the same JAR as the current 471-probe strict catalog, crash comparisons and
command regressions. The release gate now also requires this report and its
validated archive to match that exact artifact and Paper build. All 53 evidence
mutation controls are rejected. Report r3 enumerates 240 inactive-registry rows
and six stale-binding rows; it observed one terminal callback for each of the
420 UUIDs and 126 native public despawn events, with no duplicates.

The original three-round observer is
[r2](../tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r2.json).
It is observation-only evidence. Observer r1 stopped before spawning: its probe
looked up an imported file by its output filename instead of `source-mob-id`.
That fixture error was corrected; r1 cannot establish compatibility.

MM can retain an activity entry through an unload/reload, then deactivate that
entry after the entity has already returned. The original UUID and saved marker
remain on the live entity, but the native active registry no longer supplies its
variables. A second, slower unload/reload restores activity and marker 73. In a
second pattern, the activity entry still points at the previous, invalid entity
object while the reloaded entity with the same UUID is valid.

| Observed state | Helstera | Native MM 5.7.2 |
| --- | --- | --- |
| NEVER/PERSISTENT rapid reload | Valid controller, marker 73, tree 91, eleven visuals | May lose activity after reload or briefly bind the old entity object |
| Retained entity after a second 40-tick unload/reload | Same UUID and values | Same UUID, active registry and marker recovered |
| NORMAL/CHUNK/NPC unload | Removed, one despawn callback | Cleanup is deferred; a very early reload can briefly expose the entity or its active entry |
| Real death after stable recovery | One death callback; no entity, registry, tree or visuals left | One death callback; no entity or active registry left |

The pinned native bytecode explains the competing operations: the entity-unload
listener schedules a five-tick validity check; retained `ActiveMob.setUnloaded`
saves its state and schedules UUID-based deactivation five ticks later. The mob
update path can also invoke `setUnloaded`. Meanwhile `scanChunk` returns when it
finds a UUID still active. A stale delayed deactivation can therefore remove an
entry even if an entity has since returned. The fixture uses public events,
identity-stable object tokens and live/PDC reads to measure this independently.
The bytecode inspection is explanatory, not a replacement for runtime evidence.

In observer r2, all twelve retained native entities in each 4/5/6/10-tick group
lost registration by the settled observation. The 1- and 11-tick groups varied;
they are scheduling boundaries, not universal safe thresholds. The 40-tick
control remained registered. Helstera preserved all live variables and trees.
The report records every inactive registry, stale binding and still-absent native
checkpoint as an explicit exception. These rows are never called matching native
and runtime behavior. An absent early checkpoint is permitted only while the
original native entity and registry objects remain active; stable recovery always
requires a saved marker.

The separate historical lifecycle r7 diagnostic observed repeated native despawn
commands. The rapid-reload observer did not reproduce those duplicates. That
historical failure remains unresolved; it is not erased by successful repetitions
and is not attributed to this registration race without further evidence.
Native public despawn events retain their originating stacks; any duplicate command
also records a stack and makes the comparison fail.

The fixture observes eight checkpoints per UUID: initial, immediate reload,
+1/+5/+10/+40 ticks, stable recovery, and final cleanup. Independent validation
requires all 3,360 observations and 420 unique native/runtime entity UUIDs,
actual unload/load/death events, source mappings, accepted and saved unloads,
exact requested tick offsets, callback counts, native public events, scalar PDC
payloads, controller/entity bindings, tree/visual state and empty final registries.
Transient native timing differences are listed explicitly instead of silently
counted as successful comparisons. The runner archives incomplete and rejected
runs as observation-only reports.

This does not prove delayed/asynchronous/visual callback equivalence, callback
child inheritance, all entity families, interrupted writes, client-driven chunk
loading, another Paper/MM build, or the complete Mob Options/SkillTree catalogs.
Helstera intentionally keeps the restored controller instead of copying native
registration loss. [Crash checkpoints](DESPAWN_CRASH_COMPATIBILITY.md),
[commands](MOB_COMMAND_COMPATIBILITY.md) and
[ordinary lifecycle](DESPAWN_LIFECYCLE_COMPATIBILITY.md) retain their own scopes.

Run serially with an unused revision and the pinned local jars; fixtures share
port 25731:

```bash
python3 tools/paper-integration/run-rapid-reload-fixture.py --revision r3 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-rapid-reload-evidence.py tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r3.json
```
