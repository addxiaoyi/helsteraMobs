# Despawn lifecycle compatibility

This contract covers actual chunk/world unload, reload and subsequent death on
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

Evidence: [final report](../tools/paper-integration/reports/paper-1.21.1-despawn-lifecycle-20261120-r9.json)
and its [redacted logs and snapshots](../tools/paper-integration/reports/paper-1.21.1-despawn-lifecycle-20261120-r9-logs.zip).
The final artifact SHA-256 is
`3e5ab03e1d3d342084f02df8db86dd08b1f943efed36a576fe265b10ed86c9dc`.
There are 90 observations: 80 matching native/runtime outcomes and ten explicit
cancellation exceptions. The independent validator checks the full Cartesian
coverage, UUID identities, actual unload/load/death events, mapping results,
46 configured command callbacks, nine visual checkpoints, eight disk trees and
shutdown registry membership. Location observations compare the complete world
and coordinates; death observations wait beyond the delayed cleanup task. Visual counts are 110 initially, 44 after accepted
reload and zero after death. Thirty-three mutation controls reject missing, duplicate
or altered evidence. The earlier
[baseline diagnostic](../tools/paper-integration/reports/paper-1.21.1-despawn-lifecycle-20261120-r3.json)
contains observed failures on the previous artifact and is not passing evidence.

A separate [rejected diagnostic](../tools/paper-integration/reports/paper-1.21.1-despawn-lifecycle-20261120-r7.json)
recorded native chunk `onDespawn` commands executing 2–4 times, with runtime
commands remaining exactly once. The validator rejected all 12 affected rows.
The cause is unresolved; do not infer an always-once native callback guarantee.
Subsequent instrumented runs r8 and r9 each passed and recorded 18 native public
MythicMobDespawnEvents, with one event and one command per actual native despawn.
Those passes do not erase the failing observation. The final probe retains public
event identity logs and duplicate-call stacks for further diagnosis. No native
scheduler, clock or plugin bytecode is patched by the fixture.

Reproduce serially (fixtures share port 25731):

```bash
./mvnw verify
python3 tools/paper-integration/run-despawn-lifecycle-fixture.py --revision r10 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-despawn-lifecycle-evidence.py tools/paper-integration/reports/paper-1.21.1-despawn-lifecycle-20261120-r10.json
```

Open work: intermittent native chunk callback replay, native player-distance behavior and entity-family defaults, kill-command
immunity differences between NPC/PERSISTENT and other modes, mode-specific process
restart and crash recovery, external removal paths, late event cancellation,
non-living casters and other Paper/MM versions. Existing timed PERSISTENT restart
and variable-lifetime suites provide separate evidence, not a NEVER restart claim.
