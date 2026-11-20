# Cancelled death and terminal callbacks

Model-bridge release refresh (2026-11-20): [cancelled-death](../tools/paper-integration/reports/paper-1.21.1-cancelled-death-20261120-r5.json) passed against plugin SHA-256 `d283a6850d0d587c80e7a81ab41c71b4f5a97186cac84fe7f30356eeb23bd051`, also verified by the 471-probe strict catalog. Earlier entries retain their original artifact scope.

Terminal-variable release refresh (2026-11-20): [cancelled-death](../tools/paper-integration/reports/paper-1.21.1-cancelled-death-20261120-r4.json) passed against plugin SHA-256 `0d9070b2764868a8de94888c140238d03dc3691ce3ac01355e3d766d444c3d75`, also verified by the 471-probe strict catalog. Earlier entries below describe their original artifacts; their documented behavior exceptions remain unchanged.

Scope: Paper 1.21.1-133, public MythicMobs 5.7.2, COW/ZOMBIE parents,
direct/imported configurations, health and revive health 20, Despawn NEVER.
The [r3 report](../tools/paper-integration/reports/paper-1.21.1-cancelled-death-20261120-r3.json)
and [redacted raw archive](../tools/paper-integration/reports/paper-1.21.1-cancelled-death-20261120-r3-logs.zip)
use plugin SHA-256
`d16e14c17d07e5de7413e25cd882a46627b24d1c1269ed34c92473cf71b4ccd8`,
the same artifact as that milestone’s 471-probe strict catalog. They contain 96 parent
observations, 32 parent UUIDs, 64 child UUIDs, 276 command callbacks and 128
child-state observations. This is a scoped comparison with explicit differences;
it is not a full parity match or complete Triggers/Mechanics/SkillTree evidence.

Each of four scenarios runs with both entity types and both configuration paths.
Real deaths are initiated with `setHealth(0)`. The probe records the final Paper
death event at MONITOR, including its cancellation flag and revive health.
The living control starts the same skill ten ticks after spawning.

| Scenario | Helstera | Native MM 5.7.2 |
| --- | --- | --- |
| Living control | Managed parent survives; ordinary delayed work runs | Same outcome |
| Synchronous skill cancellation | Death is cancelled; original parent, marker and controller survive; pending work runs | Same outcome |
| External late HIGHEST cancellation | Death is cancelled; managed parent and marker survive; pending work runs; cleanup selects parent | Entity revives, but its ActiveMob entry is lost, pending work is cancelled and native command cleanup misses the parent |
| Confirmed death | Parent removed; ordinary pending work cancelled; terminal sequence completes | Same outcome, allowing documented deferred command dispatch |

Skill cancellation uses `cancelevent{sync=true} ~onDeath`, without a targeter,
after the terminal metaskill trigger. In this pinned native version, ordinary
death mechanics can be dispatched asynchronously; the explicit synchronous
cancellation runs before the death listener checks its result. The importer
maps this action to synchronous `cancel-event`, while the preceding named skill
retains its own production mapping. This proves this cancellation action only;
it does not establish general `sync` support on arbitrary skill calls.

The external cancellation listener is registered at HIGHEST after native MM.
All eight native/runtime external events are cancelled and revive health is 20.
The four native parents nevertheless lose their active registry before the
late listener cancels death. At settlement their original entities remain alive,
but their markers are unavailable through the active registry. Native
`mythicmobs mobs killall -p` removes their children but cannot select those four
parents. Helstera retains its live controller and removes it through its ordinary
cleanup command. These four differences are individually listed in
`nativeCancellationExceptions` under
`MM-5.7.2-late-cancelled-death-orphan`. Helstera intentionally retains management
of the revived entity. The fixture observes the orphan after command cleanup,
then logs a separate raw-entity teardown before saving and unloading its world.
No raw teardown is counted as successful native command cleanup.

Every terminal sequence checks an immediate command, one- and ten-tick delays,
three repetitions five ticks apart, and a delayed nested skill. The nested skill
summons one NEVER and one PERSISTENT child and records callbacks before and
after summoning. Every command preserves the parent's original UUID and marker
73 and executes on the main thread. Native command dispatch has the same bounded
timing exception as the [terminal continuation comparison](TERMINAL_CALLBACK_COMPATIBILITY.md).
An ordinary spawn command delayed eighty ticks runs only for managed living
parents; confirmed death and the four native late-cancellation cases cancel it.

Validation requires 24 actual death events, including 16 cancellations, eight
external cancellation records, all 64 child origins and both later child states.
It checks parent health/identity/registration, eleven visuals per live runtime
mob, final variable and SkillTree absence, and successful world seals. Runtime
terminal-execution counts are zero at every checkpoint; runtime summon ownership
has one owner/two minions at settlement and zero records after cleanup. Those
runtime service counters are global, also sampled on the native observation row;
they are not native MM registry introspection. All 32 parent teardowns must follow
the command-cleanup observations, leaving no orphan behind.

The validator rejects 73 semantic evidence mutations, including false survival,
missing or duplicate callbacks, changed cancellation flags/revive health, lost
pending work, changed source/imported actions, hidden native differences and
incomplete cleanup. The release gate pins report/archive and artifact/server
hashes, requires schema 1 with `comparison-with-exception` and
`scoped-comparison`, and revalidates the raw evidence. It rejects observations,
incomplete results and reports that relabel this difference as a parity match.

Historical observers remain unchanged:

- [r1](../tools/paper-integration/reports/paper-1.21.1-cancelled-death-20261120-r1.json)
  used `cancelevent @self ~onDeath` without explicit synchronous dispatch. Its
  native internal-cancellation control died, so it cannot prove successful
  native cancellation. It also exposed the late external-cancellation orphan.
- [r2](../tools/paper-integration/reports/paper-1.21.1-cancelled-death-20261120-r2.json)
  moved no-target `cancelevent` into a named metaskill, but left the outer death
  trigger asynchronous. All four native internal controls still died and lost
  their pending commands. The validator rejected the run; it remains observation
  evidence. The new r3 stimuli explicitly synchronize the cancellation action.

Open scopes include other cancellation priorities and listener orderings,
pre-cancelled/uncancelled events, killer-side callbacks and drops during
cancellation, variable mutation/expiry during terminal work, reload/shutdown
mid-callback, cast queues, cross-trigger cooldowns, asynchronous or visual
mechanics, and child inheritance/restart/crash behavior. Other Paper/MM builds
remain unverified. The five broad Mechanics, Trigger, Mob Options mapping,
Skills importer and SkillTree matrix rows remain `mapped-unverified`.

Run serially with an unused revision and the pinned local dependencies; fixtures
share port 25731. The archived r3 can be validated without starting Paper.

```bash
python3 tools/paper-integration/run-cancelled-death-fixture.py --revision r4 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-cancelled-death-evidence.py tools/paper-integration/reports/paper-1.21.1-cancelled-death-20261120-r3.json
```
