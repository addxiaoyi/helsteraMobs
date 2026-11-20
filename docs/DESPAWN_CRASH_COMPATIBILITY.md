# Despawn crash checkpoints

Verified evidence: [r3 report](../tools/paper-integration/reports/paper-1.21.1-despawn-crash-20261120-r3.json)
and [redacted logs, snapshots and raw NBT](../tools/paper-integration/reports/paper-1.21.1-despawn-crash-20261120-r3-logs.zip).
All 43 mutation controls are rejected. The plugin artifact is
`b7aab2b68eeb55b923a72470780fff62b14a5e353ed55b7f8bc6bc66b4acf898` and passes 2,429 Maven tests plus SpotBugs.
The same artifact also passes the [471-probe strict suite](../tools/paper-integration/reports/paper-1.21.1-despawn-crash-strict-20261120-r1.json)
and [clean three-boot restart](../tools/paper-integration/reports/paper-1.21.1-despawn-restart-20261120-r8.json)
with 50 observations and 36 rejected evidence mutations. The release gate requires
this crash archive alongside matching distance, precedence, loaded-command,
unloaded-command and reentrant-command evidence.

The tested boundary is a completed entity save followed by a forced process exit
(SIGKILL), on Paper 1.21.1-133 and public MythicMobs 5.7.2. A normal `save-all flush`
does not create a fresh native mob-variable checkpoint. The checkpoint is written
when the retained mob unloads. Changes after that checkpoint are not crash durable.

| State before the crash | Native and Helstera variables after restart | Helstera SkillTree |
| --- | --- | --- |
| NEVER/PERSISTENT, never unloaded | No variable checkpoint | No tree checkpoint |
| NEVER/PERSISTENT, unloaded and saved | Values at that unload, same entity UUID | Tree at that unload |
| NEVER/PERSISTENT, saved, loaded and changed again | Values from the previous unload | Tree from the previous unload |
| NORMAL/CHUNK/NPC | Entities absent after restart | No restored tree |

Helstera now writes a typed variable checkpoint into the retained entity's PDC
before releasing its controller. Scalar values, locations and retention metadata
therefore travel with the entity region data. Entity `save=false` values follow
the observed native entity contract and survive that checkpoint; player and world
scopes have their separately documented filtering rules. Same-process suspension
continues using live state. First restoration after a process restart prefers the
entity checkpoint over the legacy shared shutdown file. A deliberately empty
checkpoint replaces stale legacy values; entities without the new field keep the
legacy migration path. Invalid types, malformed snapshots and foreign UUID scopes
are rejected before replacing live state. Graceful shutdown updates loaded
retained entities' checkpoints before final world saving.

Checkpoint creation in this fixture uses actual world unloads. The chunk-only
crash path remains outside this focused proof.

The fixture creates 30 native/runtime pairs over direct and imported configurations,
five Despawn modes and three independent worlds. It makes two actual SIGKILL exits
and one final graceful shutdown. The second crash follows real deaths of the direct
mobs and unsets/replacement of the imported mobs' variables plus SkillTree clearing.
Final restart must preserve the replacement and keep deleted data absent; final
cleanup must leave no tested entities, visual entities, registries or tree files.
NEVER chunks are checked before loading, and retain their lazy loading policy.

The independent validator checks 190 live observations, all 60 entity UUIDs,
import mappings, actual load/death events, 48 terminal commands, both process exit
codes and shutdown ordering. It reparses archived raw `.mca` entity files and
compares their contents with the captured JSON and native/runtime PDC, including
coordinates and retention markers. Disk snapshots immediately before and after
SIGKILL must agree. The in-memory representation while unloaded remains an
explicit exception (`MM-5.7.2-inactive-entity-registry`): MM drops its active
registry, while Helstera retains UUID-scoped values until terminal cleanup.

Observer r2 completed both crashes but was rejected by the initial disk validator,
which assumed the tree had no origin location. Its cow death drops also made it
unsuitable for an all-entity-absence assertion. The fixture now suppresses only
the tested cows’ death drops, and validates the exact tree origin. Paper can leave
the final sector of an open region unpadded; the raw reader checks allocated
sectors and payload bounds without requiring padding bytes.

Observer r1 is diagnostic evidence of Helstera losing saved entity variables while
MM recovered them. It is not passing evidence. A successful checkpoint comparison
does not prove recovery during an unfinished region write, a partially written
SkillTree file, operating-system failure, disk failure or power loss. Native
periodic saving, uncheckpointed changes, delayed callbacks, broader catalogs and
other server versions remain outside this test.

Run this SIGKILL fixture on a POSIX host (Linux/macOS). Archived evidence can be
validated on any host with the Python dependencies. Reproduction requires Java 21,
the pinned local Paper/MM jars and Python packages in `tools/paper-integration/requirements-despawn-crash.txt`. Use a new revision and
a disposable fixture cache:

```bash
python3 tools/paper-integration/run-despawn-crash-fixture.py --revision r3 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-despawn-crash-evidence.py tools/paper-integration/reports/paper-1.21.1-despawn-crash-20261120-r3.json
```

Only the fresh child Java process started by this runner receives SIGKILL.
