# AIPathfindingMalus version contract

The native adapter targets **Paper 1.21.1** and the **MythicMobs 5.7.2** input
contract. It does not activate on Spigot, Folia, Paper forks reporting another
server name, or another Minecraft version. A failed capability or validation
check produces a warning without applying the requested costs.

## Source syntax

MythicMobs 5.7.2 `MobType` reads `AIPathfindingMalus` with `getStringList`, splits
each entry into a PathType and a float, and passes the entries to
`VolatileAIHandler.setPathfindingMalus`. Its `v1_21_R1` implementation uppercases
the name and uses `PathType.valueOf`. There is no `FIRE` alias.

```yaml
ExampleMob:
  Type: ZOMBIE
  AIPathfindingMalus:
    - WATER -1
    - DANGER_FIRE 0
    - DAMAGE_FIRE 12.5
```

The importer writes valid entries to `ai.pathfinding-malus`, normalizes names
to uppercase, and keeps the last repeated entry for a name. Invalid entries
produce migration warnings and preserve the complete source at
`mob.unsupported.AIPathfindingMalus`. Map-shaped source input remains preserved;
the native Mythic option expects a list. Negative finite costs are supported.
NaN, infinity, float overflow, missing values, extra fields, and unknown names
are rejected. Whitespace is normalized when importing the supported form.

The supported Minecraft 1.21.1 names are:

```text
BLOCKED OPEN WALKABLE WALKABLE_DOOR TRAPDOOR POWDER_SNOW DANGER_POWDER_SNOW
FENCE LAVA WATER WATER_BORDER RAIL UNPASSABLE_RAIL DANGER_FIRE DAMAGE_FIRE
DANGER_OTHER DAMAGE_OTHER DOOR_OPEN DOOR_WOOD_CLOSED DOOR_IRON_CLOSED BREACH
LEAVES STICKY_HONEY COCOA DAMAGE_CAUTIOUS DANGER_TRAPDOOR
```

## Native application

The adapter requires a Bukkit `Mob`, the server thread, and the native
`net.minecraft.world.entity.Mob` handle. It discovers the exact
`getPathfindingMalus(PathType): float` and
`setPathfindingMalus(PathType, float): void` methods reflectively. No private
field writes or access widening are used.

The entire runtime map is validated before native access. Every requested
enum constant and previous cost is resolved before the first write. If a
setter fails, the adapter attempts to restore every attempted entry, including
the entry whose setter failed, and reports whether restoration succeeded.
It does not report successful application after an error.

Application runs from the production mob spawn path before AI attachment and
spawn skill dispatch. Restoring a serialized managed entity uses the same
spawn path, so costs are reapplied after a server or chunk reload. An empty
map is a no-op. Removing a configured cost does not provide a live reset
mechanic for an already bound mob.

## Verification

Unit tests cover list and runtime-map parsing, exact names, finite float
conversion, immutable results, the version gate, reflection signatures,
complete prevalidation, and rollback after a setter mutates then fails.
Importer tests cover valid lists, mixed valid/invalid lists, and map
preservation.

The focused Paper fixture compares all 26 costs against a native Mythic mob,
checks the production imported spawn, rejects unsupported entities and
invalid runtime maps, and restarts the server to inspect the same saved
entity UUID and restored costs:

```sh
python3 tools/paper-integration/run-pathfinding-malus-fixture.py \
  --revision r1 --cache-directory /path/to/existing/paper-1.21.1-fixture
```

The runner builds the fixture plugin unless `--skip-build` is supplied. It
expects the current full Helstera JAR to have been built already. Successful
execution produces 59 initial and 27 restart observations, exact artifact
hashes, source hashes, and an archived evidence report. The report remains
observation evidence until the final artifact passes the required strict
release gates. Cost-table equality does not establish route-choice parity.

Native bytecode inspected for the implementation:

- MythicMobs 5.7.2 SHA-256:
  `9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
- Extracted Paper 1.21.1 server SHA-256:
  `020b6831758cfbaad2890d77eb05d664758319c12a1b190f6abf40323e96120e`.
- The fixture separately verifies the pinned Paper bootstrap SHA-256 from the
  existing runner contract before starting the server.

## Independent evidence audit

`audit-pathfinding-malus.py REPORT --check-current-sources` validates all 86
required observations independently of the producer's `matched` and `expected`
fields, including rejected runtime data without a partial WATER write, map/FIRE
rejection, server-version boundary and restored native costs. It also checks
both stage summaries and exit codes, native input types, imported/preserved
configuration, restart UUID, exact archive membership, file hashes and dependency
pins. `test-pathfinding-malus-audit.py` supplies 14 tampered-evidence controls.

The Paper invalid-input check proves prevalidation before mutation. Actual native
setter-failure rollback remains covered by `PaperPathfindingMalusTest` using a
setter that mutates and then throws; the Paper report does not claim that injected
failure occurred on a real entity.
