# Rapid chunk reload and native registration

Terminal-variable release refresh (2026-11-20): [rapid-reload](../tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r7.json) passed against plugin SHA-256 `0d9070b2764868a8de94888c140238d03dc3691ce3ac01355e3d766d444c3d75`, also verified by the 471-probe strict catalog. Earlier entries below describe their original artifacts; their documented behavior exceptions remain unchanged.

Scope: public Paper 1.21.1-133 and MythicMobs 5.7.2, COW mobs, all five Despawn
modes, direct/imported Helstera configuration, separate native/runtime chunks.
The fixture requests ordinary chunk unloads and reloads them 1, 4, 5, 6, 10, 11
or 40 server ticks after the real ChunkUnloadEvent. Every combination runs three
times. PERSISTENT force-loading is explicitly released to permit the test.
No native clocks, handlers, private registries or bytecode are modified.

Current comparison: [r5 report](../tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r5.json)
and [raw archive](../tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r5-logs.zip)
use plugin `d16e14c17d07e5de7413e25cd882a46627b24d1c1269ed34c92473cf71b4ccd8`,
the same artifact as that milestone’s 471-probe strict catalog. All 3,360 observations
and 420 UUIDs validate, including 224 inactive-registry rows, six stale-binding
rows and three explicitly paired native duplicate-callback cases. The duplicates
occur in `r2_d5_npc_direct`, `r2_d5_npc_imported` and
`r2_d10_chunk_imported`, each two ticks after its single unload. All Helstera
callbacks execute once. All 55 current evidence mutations and twelve historical
duplicate-diagnostic mutations are rejected. Report/archive and exact artifact
hashes are pinned by the release gate.

Historical verified comparison: [r3 report](../tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r3.json)
and [redacted raw archive](../tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r3-logs.zip).
That artifact is `b7aab2b68eeb55b923a72470780fff62b14a5e353ed55b7f8bc6bc66b4acf898`.
It is the same JAR as the historical crash-strict catalog and its focused
command regressions. At that milestone its report/archive were pinned to the same artifact and Paper
build. All 53 original evidence
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

The [r4 capture](../tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r4.json)
reproduces duplicate native callbacks on the current artifact. It remains a
rejected observation under its original schema; the report and archive are not
relabeled. Cases `r1_d40_chunk_imported` and `r1_d40_npc_imported` each record
one entity unload, two public native despawn events for the same ActiveMob object,
and two command callbacks. Both events and both commands occur three ticks after
the unload, before any reload. Runtime counterparts execute once and all final
entity, registry, variable, tree and visual cleanup checks hold.

Native public event stacks pass through `BukkitBootstrap.handleMobDespawnEvent`;
the repeated commands pass through `CommandMechanic.castAtEntity` on the main
scheduler. This establishes duplicate native emission rather than a duplicated
report row. Inspection of the pinned artifact shows unload processing can reach
both `setDespawned` and `setUnloaded`, with the latter emitting its trigger before
marking the mob dead in `remove`. The exact competing invocation that produced
this capture is not established by deferred stacks and remains open.

Schema 2 accounts for this as `MM-5.7.2-duplicate-native-unload-callback`, never
as native/runtime agreement. A duplicate is allowed only for native CHUNK/NPC:
exactly two commands paired with two public events, the original UUID and
ActiveMob object, consecutive counts 1/2, one dispatch tick within five ticks of
the original unload, and a matching duplicate command stack. Runtime, retained
mob, death, unpaired, unbounded and later duplicate callbacks remain rejected.
Every accepted duplicate must be listed in `nativeDuplicateCallbacks`.
Schema 1 retains its original single-callback requirement. Regression tests replay
r4 in memory to exercise the new accounting and twelve corruptions; its stored
observation report is unchanged and still cannot pass the release gate.

NORMAL duplicate boundary (2026-11-20): the frozen rejected
[r6 capture](../tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r6.json)
records `r2_d10_normal_imported` with one actual entity unload at tick 2412,
two native public events and two native commands at tick 2415, and one runtime
command. The two native events share UUID and ActiveMob identity and their
stacks pass through the same native dispatch paths above. A CHUNK case in that
capture exhibits the same paired pattern. Its original report remains
observation-only with `rejected: missing/duplicate callback`.

Schema 3 adds an explicit NORMAL duplicate exception under the same exact
pairing, timing, identity, stack and final-cleanup constraints. Schema 1/2 keep
their old scopes; schema 2 cannot accept the NORMAL observation. The schema
change does not permit duplicates from runtime or retained modes, a third
callback, unpaired events, changed identities or later dispatch. Frozen replay
controls run only in memory and never alter or promote the rejected capture.
The 31 NORMAL diagnostic mutations reject schema-2 relabeling, hidden metadata,
extra/late/runtime/retained callbacks, unpaired events and wrong stack origins.
The underlying competing native invocation remains unverified.

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
python3 tools/paper-integration/run-rapid-reload-fixture.py --revision r5 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-rapid-reload-evidence.py tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r5.json tools/paper-integration/reports/paper-1.21.1-rapid-reload-20261120-r4.json
```
