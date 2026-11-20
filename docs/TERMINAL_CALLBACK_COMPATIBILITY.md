# Terminal callback scheduling: known compatibility gap

The current Helstera artifact does **not** match MythicMobs 5.7.2 when an
`onDespawn` or `onDeath` skill schedules delayed, repeated or nested actions.
The immediate command runs, but the continuations are cancelled by entity
teardown. Native MM continues those terminal skills with the original caster
UUID and marker, and a delayed nested skill can summon new mobs.

This is an implementation gap, not an intentional native-behavior exception.
The diagnostic is recorded as `diagnostic-known-gap`, `parityStatus: incomplete`.
It must not be cited as successful terminal-skill parity or used to promote the
Mechanics, Trigger, Skills importer, Mob Options or Full SkillTree catalog rows.
The strict release gate retains this diagnostic for the exact artifact so the
known limitation cannot disappear behind the existing passing strict catalog.

Pinned scope: Paper 1.21.1-133, public MM 5.7.2, production plugin SHA-256
`b7aab2b68eeb55b923a72470780fff62b14a5e353ed55b7f8bc6bc66b4acf898`.
The [r3 diagnostic](../tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r3.json)
and [redacted raw archive](../tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r3-logs.zip)
contain 120 parent observations, 40 parent UUIDs, 48 child UUIDs, 216 command
observations and 96 child-state observations. All twenty combinations use real
Paper entities: COW/ZOMBIE, direct/imported parent configurations, and five
scenarios. The named skills are imported through the production importer;
runtime child definitions use the mapped `mythic_` identifiers. All 56 semantic
evidence mutations are rejected, including false parity claims, hidden gap cases,
lost or leaked entities, broken positive controls and altered execution timing.

| Scenario | Native MM | Current Helstera |
| --- | --- | --- |
| Living control, skill starts ten ticks after spawn | Immediate command, delayed commands, three repeats, nested skill, two children, ordinary pending command | Same observed results |
| Loaded command removal | Terminal continuations execute; two children survive the original removal | Immediate command only |
| Actual entity death | Terminal continuations execute; two children survive | Immediate command only |
| CHUNK entity unload | Deferred terminal continuations execute; two children survive | Immediate command only |
| NEVER command removal one tick after paired entity unload | Terminal continuations execute; two children survive | Immediate command only |

Each terminal skill has a synchronous command, commands delayed by one and ten
ticks, `repeat=2;repeatInterval=5` (three executions), and a named skill delayed
ten ticks. The nested skill issues a command, uses real `summon` mechanics for
one NEVER and one PERSISTENT child, then issues another command. All observed
commands and child spawn callbacks run on the main thread. Native command
dispatch can be deferred relative to skill scheduling; small native tick offsets
are checked explicitly. This does not establish arbitrary asynchronous mechanics.

The ordinary spawn-triggered command delayed eighty ticks executes only for
living controls, on both implementations. It does not survive removal. That
negative control prevents a proposed fix from merely disabling cancellation for
all work owned by the removed caster. Every observed callback retains marker 73.
Settled and final observations verify registry/entity identity, live children,
eleven runtime visuals per managed entity, actual death/unload/command events,
final empty variable registries and no remaining SkillTree files. Every child
is observed again after forced cleanup. The test does not infer restart or crash
retention from the names NEVER and PERSISTENT.

Observer [r1](../tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r1.json)
also observed the lost continuations, but its runtime summon positive control
used unprefixed child filenames while imported Summon used `mythic_` names.
That fixture setup error prevented runtime control children from spawning.
It remains observation-only evidence; r3 corrects the namespace and requires
both runtime control children and all 33 expected control visuals.

[r2](../tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r2.json)
completed the corrected fixture but was rejected by the initial validator: one
native PERSISTENT child's onSpawn command arrived one tick after its summon.
The native command dispatch boundary now permits zero or one tick for that child
callback, while runtime callbacks must be synchronous with the nested summon.
The archived r2 report is unchanged and remains observation-only. The fresh r3
run uses the corrected validator; failures are never relabeled after the fact.

The cancellation has several cooperating paths: `SkillTriggers` clears casts
and queues after dispatching removal callbacks; `AiManager.detach` forgets
scheduled work and entity variables; and the suspended-removal context closes
at the end of synchronous dispatch. Moving a single cleanup call cannot safely
preserve terminal continuations, caster variables and nested summon context.

The next implementation must give terminal continuations an execution lifetime
separate from ordinary pending work. It must retain only the callback context
needed after removal, preserve variable expiry, prevent dead caster visuals or
controllers from being recreated, clean the callback state after completion or
reload, and support delayed child creation without orphan SkillTree state.
Regression acceptance requires all sixteen currently failing removal cases to
execute the native terminal sequence, all four living controls to remain valid,
all ordinary pending commands to remain cancelled after removal, and all final
cleanup checks to pass. Cancelled deaths, reload during a terminal callback,
variable mutation/expiry, cast-duration queues, visual actions, child SkillTree
inheritance and child restart/crash persistence need additional controls before
broader claims. Historical duplicate native callbacks remain a separate open
investigation.

Run serially with a fresh revision because Paper fixtures share port 25731:

```bash
python3 tools/paper-integration/run-terminal-callback-fixture.py --revision r3 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-terminal-callback-evidence.py tools/paper-integration/reports/paper-1.21.1-terminal-callback-20261120-r3.json
```
