# Mob cleanup command compatibility

Helstera provides `/helstera mob kill` and `/helstera mob killall` for loaded,
definition-owned mobs. The reference is public MythicMobs 5.7.2 on
Paper 1.21.1-133. `helstera.mob` permission is required. Existing `mob remove`
continues to unbind a model; it is not a replacement for these cleanup commands.

| Command | Selection |
| --- | --- |
| `kill <mob-id>` | Case-sensitive substring of the runtime mob ID, all five Despawn modes |
| `kill -e <mob-id>` | Case-insensitive exact runtime mob ID, all five modes |
| `kill -f <name>` | Case-insensitive faction match **and** case-sensitive mob-ID substring |
| `killall` | All loaded NORMAL, CHUNK and NEVER mobs; exempts NPC and PERSISTENT |
| `killall -p` | All five modes |
| `killall [-p] <positive-integer-radius>` | Player's world and inclusive 3D radius, with the same mode policy |

`-e` and `-f` may be combined. Named `kill` accepts `-p` but already includes
protected modes, matching the native behavior. Imported `Faction` maps to
`mob.faction` and overrides the AI profile faction. Blank clears it; malformed
or ambiguous fields are preserved under `mob.unsupported.Faction` with warnings.
Commands operate on the **runtime ID**, normally `mythic_<normalized-source-id>`
for an imported mob. The comparison fixture deliberately saves generated files
under matching native/runtime IDs to isolate selection semantics. It does not
prove that original source IDs are accepted as command aliases.

These deliberate syntax differences are part of the contract:

- Native named `kill <name> <radius>` parses but ignores the radius. Helstera
  rejects it; use `killall <radius>` for a bounded cleanup.
- Native zero/negative `killall` radius becomes global. Helstera rejects
  nonpositive, noninteger and overflowing radii.
- Native console `killall <radius>` ignores the radius. Helstera rejects it
  because the console has no location.
- Native `-v` and legacy `LITERALLY` are not supported. Unknown options are
  rejected before any removal. Helstera uses its own permission node; native
  checks also allow `mythicmobs.admin`, which must be denied for a meaningful
  native permission-denial control.

Removal unregisters the instance before callbacks, dispatches `onDespawn` once
while the caster variables/profile are live, and then destroys the model and
base entity. It removes the base without killing it; the fixture checks that no death event
is emitted. Separate loot and XP-spawn interception is outside this fixture. Scoped entity variables,
SkillTree state, suspended snapshots and UUID/legacy numeric snapshot files are
removed. A shared PERSISTENT chunk remains forced until its last managed
PERSISTENT mob is removed. Players, entities with only a bound model, and pure
model instances are excluded both from selection and the removal API.

The focused runner performs actual native and Helstera command dispatch over
13 scenarios, five modes, direct/imported configurations and four positions:
near, exact radius, outside radius and another world. It records 1,560 state
rows and 1,040 restart absence rows, 1,040 configured callbacks across command
and final cleanup, model counts, variables, tree files and chunk flags. Each managed mob has
eleven rendered entities; unnamed bound controls have ten and the pure model
has eleven, for a fixed control baseline of 31. A real
Mineflayer player exercises permissions/radius and stays connected. Three
ownership controls are checked before/after/final cleanup in every scenario
(117 checks). The independent validator pins expected outcomes rather than
accepting equality alone. Restart uses the original 1,040 entity UUIDs.

The client records the existing `declare_recipes` PartialReadError limitation
of minecraft-protocol 1.21.1; only this declared packet issue is permitted.
Recipe rendering is outside the fixture. Observer r1 has incorrect permission
and direct callback setup; observer r2 exposes the missing imported Faction.
Their original reports remain diagnostics and cannot satisfy the release gate.

This is a loaded-mob command scope. Unloaded entities, unknown native flag
combinations, legacy aliases, callback-driven spawning/removal recursion,
external chunk-ticket ownership and failures deleting snapshot files need
further validation. No deletion of unloaded world data is claimed. The full
Mob Options/Mechanics catalogs, forced-crash recovery and other Paper/MythicMobs
versions remain incomplete.

Static follow-up for the unloaded target: in pinned 5.7.2, `ActiveMob.setUnloaded`
saves retained mobs and schedules `MobRegistry.setInactive` five ticks later.
`MobRegistry.values` exposes active entries only; global killall iterates these
entries, while radius killall additionally checks `isLoaded`. The five-tick
transition is therefore an explicit next test boundary. This bytecode audit is
not runtime evidence for unloaded behavior. Test both the transition and stable
unloaded state, then reload/restart and inspect UUID variables and saved mobs.

Current passing [report](../tools/paper-integration/reports/paper-1.21.1-mob-command-20261120-r4.json)
and [redacted archive](../tools/paper-integration/reports/paper-1.21.1-mob-command-20261120-r4-logs.zip)
use plugin SHA-256 `9797f89f37f9de80506570fded644ed16b06340dde0fd91f35cf767b1653f82b`. All 29 mutation controls pass.
Rejected r3 preserves the incorrect expectation of 33 control visuals; bound
controls actually have ten each and the pure model eleven (31 total), which r4
reads from the renderer as well as counting real world entities.

```bash
python3 tools/paper-integration/run-mob-command-fixture.py --revision r5 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-mob-command-evidence.py tools/paper-integration/reports/paper-1.21.1-mob-command-20261120-r4.json
```
