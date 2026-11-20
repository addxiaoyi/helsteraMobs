# Mob cleanup command compatibility

Model-bridge release refresh (2026-11-20): [mob-command](../tools/paper-integration/reports/paper-1.21.1-mob-command-20261120-r10.json), [unloaded-command](../tools/paper-integration/reports/paper-1.21.1-unloaded-command-20261120-r11.json), [reentrant-command](../tools/paper-integration/reports/paper-1.21.1-reentrant-command-20261120-r8.json) passed against plugin SHA-256 `d283a6850d0d587c80e7a81ab41c71b4f5a97186cac84fe7f30356eeb23bd051`, also verified by the 471-probe strict catalog. Earlier entries retain their original artifact scope.

Terminal-variable release refresh (2026-11-20): [mob-command](../tools/paper-integration/reports/paper-1.21.1-mob-command-20261120-r9.json), [unloaded-command](../tools/paper-integration/reports/paper-1.21.1-unloaded-command-20261120-r10.json), [reentrant-command](../tools/paper-integration/reports/paper-1.21.1-reentrant-command-20261120-r7.json) passed against plugin SHA-256 `0d9070b2764868a8de94888c140238d03dc3691ce3ac01355e3d766d444c3d75`, also verified by the 471-probe strict catalog. Earlier entries below describe their original artifacts; their documented behavior exceptions remain unchanged.

Helstera provides `/helstera mob kill` and `/helstera mob killall` for definition-owned
mobs that are loaded or in the brief unload transition. The reference is public
MythicMobs 5.7.2 on
Paper 1.21.1-133. `helstera.mob` permission is required. Existing `mob remove`
continues to unbind a model; it is not a replacement for these cleanup commands.

| Command | Selection |
| --- | --- |
| `kill <mob-id>` | Case-sensitive substring of the runtime mob ID, all five Despawn modes |
| `kill -e <mob-id>` | Case-insensitive exact runtime mob ID, all five modes |
| `kill -f <name>` | Case-insensitive faction match **and** case-sensitive mob-ID substring |
| `killall` | All command-visible NORMAL, CHUNK and NEVER mobs; exempts NPC and PERSISTENT |
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

The unload contract uses real Paper `EntitiesUnloadEvent` boundaries. In the
pinned build, native retained entries remain selectable at samples 1, 4 and 5
ticks after unload and disappear by sample 6. MM observes unloading on the next
tick and then waits five ticks before unregistering. Helstera retains a separate
short-lived command context after destroying visuals/controllers; it does not
keep an active AI instance in an unloaded chunk.

Named kill and forced killall remove NEVER/PERSISTENT during this transition.
Default killall removes NEVER and preserves PERSISTENT. A matching player-radius
command loads the selected chunk: native `isLoaded()` calls `Location.getChunk()`.
Targets outside the radius or in another world do not load it. Stable unloaded
entries are no longer selected by any of these commands; saved survivors retain
the same UUID, variables and SkillTree when explicitly reloaded and restarted.
This does not implement a global scan or deletion of saved world entities.

Native `onDespawn` commands run after scheduling; Helstera dispatches the terminal
callback synchronously before clearing caster variables. A scoped callback view
makes `@self` and variable placeholders available for the suspended caster, then
invalidates itself and cancels newly queued skills. It cannot mutate or recreate
the destroyed model. The verified callback scope is synchronous command actions;
delayed/asynchronous actions, visual mutations, target conditions and callback
SkillTree inheritance need separate evidence. While inactive, native variables
are absent from the active registry; Helstera keeps a retained scoped snapshot.
Survivors must restore the same saved values after load/restart.

The unload fixture uses separate native/runtime chunks in the same world so that
native radius loading cannot restore runtime instances before the runtime command.
It samples 23 scenarios, five modes and direct/imported paths over three boots:
unload/command/reload, restart/cleanup, and a final restart. Real unload events,
command ticks, independent load events, callbacks, visuals and snapshot files are
recorded. A second cleanup verifies that survivors are removable and cannot return.

Unknown native flag combinations, legacy aliases, delayed/visual callbacks,
external chunk-ticket ownership, snapshot I/O failures, crash recovery,
the broader catalogs and other Paper/MythicMobs versions remain incomplete.

Previous-artifact loaded-command [report](../tools/paper-integration/reports/paper-1.21.1-mob-command-20261120-r4.json)
and [redacted archive](../tools/paper-integration/reports/paper-1.21.1-mob-command-20261120-r4-logs.zip)
use plugin SHA-256 `9797f89f37f9de80506570fded644ed16b06340dde0fd91f35cf767b1653f82b`. All 29 mutation controls pass.
Rejected r3 preserves the incorrect expectation of 33 control visuals; bound
controls actually have ten each and the pure model eleven (31 total), which r4
reads from the renderer as well as counting real world entities.

```bash
python3 tools/paper-integration/run-mob-command-fixture.py --revision r7 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-mob-command-evidence.py tools/paper-integration/reports/paper-1.21.1-mob-command-20261120-r5.json
```

Previous-artifact SHA-256: `0f68cdb23aa16ad8d8af6661488482838dee2b641879b45baa4251ce1bee203e`. Unloaded comparison
[report](../tools/paper-integration/reports/paper-1.21.1-unloaded-command-20261120-r6.json) and
[archive](../tools/paper-integration/reports/paper-1.21.1-unloaded-command-20261120-r6-logs.zip) pass 3,910
observations and 42 mutations. Loaded command regression
[report](../tools/paper-integration/reports/paper-1.21.1-mob-command-20261120-r5.json) passes 2,600
observations and 29 mutations on this same artifact. Observer r2 used a shared
chunk and could mask radius-loading failures; r3 isolated the chunks and exposed
the original implementation gap. Observer r4 retains missing suspended callbacks
and early tick-5 expiry. Rejected r5 retains orphan visuals after rapid radius
loading; the renderer fix removes owned objects directly. None of those failed
reports satisfies the gate.

```bash
python3 tools/paper-integration/test-unloaded-command-evidence.py tools/paper-integration/reports/paper-1.21.1-unloaded-command-20261120-r6.json
python3 tools/paper-integration/run-unloaded-command-fixture.py --revision r8 --cache-directory srv-paper-player-life-20261120-r1
```

Original milestone artifact SHA-256: `59dc5e9b21762a5306768f13f5ba6a1b1561e6e1c3f03d2a58b7f3421fe04675`. The reentrant command
[report](../tools/paper-integration/reports/paper-1.21.1-reentrant-command-20261120-r3.json)
and [archive](../tools/paper-integration/reports/paper-1.21.1-reentrant-command-20261120-r3-logs.zip)
cover 60 cases and 1,920 observations over three boots. The 15 callback actions
combine named self/peer removal, forced global/radius cleanup, child spawning,
and explicit chunk loads, across loaded/unloading and direct/imported paths.
The original command uses a selection snapshot. A new NEVER child survives
unless a subsequent nested command selects it. The caster cannot be restored
while its terminal callback is still running. An original peer restored by a
callback chunk load remains selected by the outer command.

Paper can create a retained child in a cached unloaded chunk without activating
it. Invalid cleanup now saves its SkillTree and preserves entity variables as
an unload; it does not treat this living entity as permanently removed. Named
and global commands can select this pending child. A matching radius command
loads its chunk before removal. Live callbacks read marker 73 from the actual
caster UUID; child state 73/91 survives reload and restart when retained. Final
cleanup removes all recorded UUIDs, scoped variables, tree files and visuals.
All 46 mutations are rejected. The full strict report and all four prior focused
suites were rerun on this same JAR; the release gate requires all five comparisons.

Observer r2 is an incomplete 916-row run: the old five-minute client deadline
expired before the first boot completed. Its redacted logs and failure metadata
are archived but cannot satisfy the gate. The client now accepts a validated
timeout argument, with fifteen minutes supplied by this runner.

Observer r1 retains the pre-fix failures. It also reloaded the native radius-spawn
child one tick after a second unload: native's pending unregister removed the
active entry after reload, while the entity and saved marker survived restart.
The accepted fixture samples stable state after 40 ticks, then explicitly loads
and waits 30 ticks. A separate [rapid-reload contract](RAPID_RELOAD_COMPATIBILITY.md)
now isolates native delayed deactivation using real events and seven reload
offsets; the historical callback-created-child path still needs its own timing
coverage. No broad claim of identical timing is made. Helstera's synchronous callback and native's
deferred callback are an explicit exception, checked through command depth.
Delayed/asynchronous/visual callbacks, child SkillTree inheritance, PERSISTENT
children created by callbacks, pending-child command expiry boundaries, failure
during snapshot I/O and crash recovery are outside this verified scope.

```bash
python3 tools/paper-integration/run-reentrant-command-fixture.py --revision r4 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-reentrant-command-evidence.py tools/paper-integration/reports/paper-1.21.1-reentrant-command-20261120-r3.json
```
