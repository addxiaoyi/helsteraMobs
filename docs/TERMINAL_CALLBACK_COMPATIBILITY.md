# Terminal callback continuations

Helstera now lets delayed, repeated and nested `onDespawn`/`onDeath` actions
finish after their caster is removed. Ordinary work pending before removal is
still cancelled. A terminal execution retains the caster's original UUID,
origin and scoped variables, then releases its state after the last action or
cast. It does not recreate the removed parent's controller, visuals or persisted
SkillTree. Reload and shutdown cancel terminal executions.

Pinned scope: Paper 1.21.1-133 and public MythicMobs 5.7.2. The current artifact
SHA-256 is `d16e14c17d07e5de7413e25cd882a46627b24d1c1269ed34c92473cf71b4ccd8`. The [r7 comparison](../tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r7.json)
and [redacted raw archive](../tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r7-logs.zip)
contain 120 parent observations, 40 parent UUIDs, 80 child UUIDs, 328 command
callbacks and 160 child-state observations. The evidence is a scoped match,
with native deferred command dispatch documented as a timing exception. It is
not full Mechanics, Triggers, Mob Options, Skills importer or SkillTree parity;
those broad matrix rows remain `mapped-unverified`.

All twenty combinations use real Paper entities: COW/ZOMBIE, direct/imported
parent configurations, and five scenarios. Named skills use the production
importer; runtime child definitions use its mapped `mythic_` identifiers.

| Scenario | Native MM and current Helstera |
| --- | --- |
| Living control, skill starts ten ticks after spawn | Complete sequence, two children, ordinary pending command |
| Loaded command removal | Complete terminal sequence; two children survive removal |
| Actual entity death | Complete terminal sequence; two children survive |
| CHUNK entity unload | Deferred terminal sequence; two children survive |
| NEVER command removal one tick after paired entity unload | Complete terminal sequence; two children survive |

Each sequence has an immediate command, commands delayed one and ten ticks,
`repeat=2;repeatInterval=5` (three executions), and a named skill delayed ten
ticks. The nested skill issues a command, uses real `summon` mechanics for one
NEVER and one PERSISTENT child, then issues another command. All callbacks run
on the main thread and preserve marker 73 and the original caster UUID. Native
command dispatch can be deferred relative to skill scheduling; small tick
offsets are checked explicitly. Runtime child callbacks are synchronous with
the nested summon. This does not establish arbitrary asynchronous mechanics.

The ordinary spawn command delayed eighty ticks executes only for living
controls on both implementations. Settled/final observations verify parent and
child identity, registries, eleven visuals per runtime mob and actual death/
unload/command events. Runtime terminal executions are zero at each checkpoint;
summon ownership has one owner and two children at settlement, then zero owners
and children after cleanup. Final variable registries and SkillTree files are
empty. Each child is observed again after forced cleanup.
All 59 semantic evidence mutations are rejected, including lost callbacks,
changed timing, leaked entities, orphan terminal state and false parity labels.
The release gate requires schema 3 with ownership cleanup, pins report/archive
and plugin/server hashes, validates the raw evidence, and rejects historical
diagnostics as proof of this fix.

Execution scheduling uses separate terminal IDs while actions retain the
original caster identity. Before live entity variables are removed, overlapping
terminal callbacks share a detached variable/location snapshot with absolute
expiry deadlines. Those snapshots are never written into the global registry
or the removed parent's persisted SkillTree. Model mutations require a live
source model. The production spawn path loads the target chunk's entity data
before creating a new mob so its first `onSpawn @self` sees a valid entity.

Twelve focused terminal tests and a 100-cycle summon-owner regression cover
ordinary-versus-terminal cancellation, nested
variable mutation, scoped caster/target/trigger reads, expiry/location retention,
reload during dispatch, explicit cancellation, surviving/cancelled-death caster
state, overlapping contexts, model mutation guards, absent schedulers/action
errors, terminal aliases and cast-duration cleanup. The artifact passes all
2,442 Maven tests and SpotBugs. These unit controls do not substitute for native
Paper comparisons of cancelled deaths, reload during callbacks, variable
mutation/expiry, cast queues, cross-trigger cooldowns, summon depth/onSummon
dispatch, visual mechanics or child SkillTree inheritance and restart/crash
persistence. Those scopes remain open,
as does historical duplicate native callback investigation.

Historical evidence is preserved without relabeling:

- [r1](../tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r1.json)
  used unprefixed runtime child names; its summon control was invalid and it
  remains observation-only.
- [r2](../tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r2.json)
  was rejected because the initial validator disallowed a native child callback
  arriving one tick later. The corrected validator permits only zero/one tick.
- [r3](../tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r3.json)
  is verified `diagnostic-known-gap` evidence for the previous artifact
  `b7aab2b68eeb55b923a72470780fff62b14a5e353ed55b7f8bc6bc66b4acf898`:
  all sixteen removal cases cancelled continuations; four controls passed.
  Its 56 mutation controls remain valid.
- [r4](../tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r4.json)
  restored the terminal sequences but exposed missing first-child spawn callbacks
  in all eight chunk/suspended cases. Terrain had loaded before entity data;
  the first new child was invalid during `onSpawn @self`. It remains rejected
  observation evidence. The production load-order fix is independently rerun in r7.
- [r5](../tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r5.json)
  restored all children but failed exact runtime repeat timing: the first
  zero-delay repetition ran one tick late during CHUNK removal, moving the
  later repetitions as well. It remains rejected observation evidence. The
  scheduler now runs the first repetition immediately while retaining generation
  tracking so reentrant cancellation still stops subsequent repetitions.

The [r6 comparison](../tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r6.json)
passed the original schema-2 scope but did not count summon-owner records.
Code review found that delayed summons after owner removal could retain empty
owner lists. The registry now removes empty owner entries when their last child
leaves, with registration and removal serialized per owner. Schema 3 adds live
ownership and final zero-record checks; r6 is retained under its original scope.

Run serially with a fresh revision because Paper fixtures share port 25731:

```bash
python3 tools/paper-integration/run-terminal-callback-fixture.py --revision r7 --cache-directory srv-paper-player-life-20261120-r1 --expect-parity
python3 tools/paper-integration/test-terminal-callback-evidence.py tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r7.json
```
