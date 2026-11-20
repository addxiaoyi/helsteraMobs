# Distance despawn compatibility

Paper 1.21.1-133 and public MythicMobs 5.7.2 delegate NORMAL distance removal to
Paper's entity-family and world policy. Helstera now does the same. It no longer
inventories all NORMAL mobs against an implicit 32-block radius. The Bukkit
`removeWhenFarAway` flag is true for NORMAL (including omitted Despawn) and false
for CHUNK/NPC/NEVER/PERSISTENT. The separate save-to-disk `persistent` flag is false
for NORMAL/CHUNK/NPC and true for NEVER/PERSISTENT. Applying both flags explicitly
prevents retained hostile mobs from disappearing through their vanilla defaults.

| Scenario, ticketed chunks | NORMAL COW/VILLAGER | NORMAL ZOMBIE/SLIME | CHUNK/NPC/NEVER/PERSISTENT |
| --- | --- | --- | --- |
| Creative player at 16 blocks, 80 ticks | Retained | Retained | Retained |
| Player at 64 blocks, another 80 ticks | Retained | Retained | Retained |
| Player at 192 blocks, another 80 ticks | Retained | Despawned | Retained |
| No player in this world, 80 ticks | Retained | Retained | Retained |
| Only spectator at 192 blocks, 80 ticks | Retained | Retained | Retained |

These are observations with the pinned world settings, not a universal hard-coded
128-block guarantee. Paper controls category distance ranges, soft removal timers,
randomness and entity-specific exceptions. Long-lived soft-distance removal,
other families/world settings and boundary distances remain unverified.

A natural `EntityRemoveEvent.Cause.DESPAWN` removes the managed registry entry,
dispatches `onDespawn` while its profile is available, then releases rendering and
the AI controller. It does not invoke `onDeath`. Recursive/delayed cleanup cannot
repeat this callback. The event cause is authoritative even if Bukkit already
reports the entity as dead. Other removal causes are not reclassified as natural
despawn. Unload and shutdown callbacks retain their separately tested paths.

MM 5.7.2 does not interpret `Options.SpawningDistance` as a distance radius.
The importer now preserves that source under
`mob.unsupported.Options.SpawningDistance` with a warning, and sets no radius.
Earlier claims and fixtures that treated the import as native support are
withdrawn. Explicit Helstera `mob.despawn-distance` and legacy direct
`mob.spawning-distance` remain documented extensions. A positive explicit radius
still removes NORMAL mobs if no eligible player is in range, including empty and
spectator-only worlds. Zero disables that extension and leaves native Paper policy
active; it does not disable vanilla distance removal.

[Comparison report](../tools/paper-integration/reports/paper-1.21.1-despawn-distance-20261120-r8.json)
and [redacted logs](../tools/paper-integration/reports/paper-1.21.1-despawn-distance-20261120-r8-logs.zip)
contain 448 observations across 168 native/runtime pairs: 416 native parity rows
and 32 explicit-radius extension controls. Both direct configuration and production
importer paths are exercised. The plugin artifact is
`9797f89f37f9de80506570fded644ed16b06340dde0fd91f35cf767b1653f82b`.
The independent validator checks every identity and phase, actual server/client
positions, removal causes, live registries/controllers, persistence flags and all
34 configured despawn command executions. Schema 2 also binds the real player
in all five modes with both saving flags and verifies ten unchanged player states
and complete model cleanup. Real players are excluded from mob flag application.
Forty-four mutation controls reject
missing or corrupted evidence, including both implementations returning the same
wrong result. Chunks stay ticketed, a stone floor prevents falling, and collision
is disabled. Actual XYZ/distance measurements reject void or displacement errors.

The client has one documented tooling limitation: minecraft-protocol's 1.21.1
`declare_recipes` parser emits a PartialReadError on the server's recipe list. The
fixture records its packet identity before the library drops it; the validator
allows only this known recipe packet error, and requires all four matching client
and server position acknowledgements. Movement/chat packet errors, unrecorded
parser errors, missing acknowledgements and client disconnects fail validation.
This suite does not verify recipe rendering.

Diagnostics r1/r2/r3 are observations, not passing evidence. r1 used a void world
and cannot establish distance thresholds. r2 fixes the floor and coordinates and
records the old artifact's incorrect 32-block/no-player cleanup and retained-hostile
flags. r3 observes corrected production behavior but predates controller and final
validator evidence. None is substituted for the passing r7 archive.

Run with a fresh revision, an existing pinned Paper cache and PyYAML 6.x from
`tools/paper-integration/requirements-player-variables.txt` (port 25731 is shared):

```bash
python3 tools/paper-integration/run-despawn-distance-fixture.py --revision r9 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-despawn-distance-evidence.py tools/paper-integration/reports/paper-1.21.1-despawn-distance-20261120-r8.json
```

Persistent/Despawn precedence and global defaults have a separate
[contract](DESPAWN_PRECEDENCE_COMPATIBILITY.md). Open interactions include
kill-command immunity, external removal, chunk callback replay, non-living bases
and other Paper/MM versions. This scoped evidence does not close the universal
Mob Options catalog or the full parity goal.

A second full strict diagnostic (r2) was rejected with two missing Potion probes:
the returning survival client was killed by a leftover combat-test mob before
consumption completed. The raw-client stage now grants Resistance V for this
disposable fixture. It still requires real `PlayerItemConsumeEvent`, SPEED
application, native expiry and the configured callback; the production plugin
and release assertions were not changed for this fixture isolation.

The first full strict regression was rejected because its skeleton conversion
probe used default NORMAL bindings after manually disabling vanilla distance
removal. Binding now correctly applies the mode flags, so the distant skeletons
despawned before conversion. The probe now explicitly uses NEVER for both controls;
its real FROZEN event/cancellation assertions remain intact. Full strict distance r4 and
distance r6 use an earlier artifact. Precedence strict r1 and distance r7
verify the subsequent precedence milestone; command strict r1 and distance r8
verify the current artifact. The earlier passing distance r4
and unload r13 retain their original artifact hashes.

The current command milestone revalidated this suite against the same release
artifact; older archives retain their original hashes. Loaded cleanup command
behavior is covered separately by the [command contract](MOB_COMMAND_COMPATIBILITY.md).
