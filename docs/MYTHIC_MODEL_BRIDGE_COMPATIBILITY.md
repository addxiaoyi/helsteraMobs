# Native MythicMobs model bridge contract

Verified baseline release: [srv-paper-model-bridge-20261120-r6](../tools/paper-integration/reports/paper-1.21.1-model-bridge-20261120-r6.json)
([raw archive](../tools/paper-integration/reports/paper-1.21.1-model-bridge-20261120-r6-logs.zip)):
93 observations and 510 rejected evidence mutations. Production SHA-256:
`d283a6850d0d587c80e7a81ab41c71b4f5a97186cac84fe7f30356eeb23bd051`. All existing same-artifact release suites and the
471-probe strict catalog pass; 2,474 Maven tests and 11 SpotBugs reports are clean.

This is the optional native MythicMobs integration, separate from importing Mythic
skills into Helstera's own runtime. The bounded extension API is MM 5.7.2 on
Paper 1.21.1-133. It does not establish the complete MythicMobs catalog, client
rendering, or compatibility across the previously declared 5.3.x–5.7.x lookup range.

## Execution and arguments

| Native extension | Behavior |
| --- | --- |
| `modelspawn{model=example/emberling;scale=1.2;animation=idle;loop=true} @self` | Binds the selected entity; repeating the same model does not create or reconfigure a second binding. Different-model replacement is rejected. Model and optional animation are checked before binding. |
| `modelremove{} @self` | Removes the model instance and visuals, preserving the external entity. Repeated removal is harmless. |
| `modelplay{animation=walk;loop=true;priority=7} @self` | Plays a known animation subject to existing animation priority. |
| `modelstop{animation=walk} @self` / `modelstop{} @self` | Stops the named animation or all animation layers. |
| `modelscale{scale=1.5} @self` | Sets the instance scale multiplier. |
| `modelmount{} @target` | Makes the native skill caster ride the selected bound model entity. Repeated mounting is harmless. An existing different vehicle, self-mount, cross-world target or passenger cycle is rejected before mutation. |
| `modelunmount{} @target` | Removes only the native caster from the selected bound model entity. Other passengers and a caster riding a different entity are preserved. Repeated unmounting is harmless. |
| `modelheal{amount=5} @target` | Heals the selected bound Bukkit `Damageable` entity, up to its current maximum health. Defaults to 1; zero is harmless. Dead or zero-health entities are rejected. |
| `modelspawned true` / `modelremoved true` | Tests binding presence/absence on a valid entity. |
| `modelplaying{animation=walk} true` | Tests active playback; omit animation for any active animation. Paused playback does not pass. |

`m` aliases model for spawn, animation for play/stop/playing, and scale for scale.
Duplicate keys and conflicting aliases are rejected, even when values agree.
Scale must be finite and positive; loop is a boolean; priority is an integer.
Model/animation IDs are literal and case-sensitive. Unknown keys and invalid
values do not mutate model state. For a bound model, an unknown animation
condition fails evaluation, including with a `false` suffix. An unbound entity
has no playing animation.

`modelmount`, `modelunmount` and `modelheal` are Helstera-defined extensions; MM
5.7.2 has no native mechanics with those names. They operate on the model's bound
base entity, with no bone seats or separate model health pool. Mount and unmount
read `SkillMetadata.getCaster().getEntity()` through the exact pinned native ABI;
they require a valid, living caster and target in the same world. Bukkit passenger
operations remain cancellable and a refused operation reports an error. Healing
accepts a literal finite nonnegative `amount` (alias `a`), writes Bukkit health
directly, and does not implement native heal events, expressions or absorption.
All three require an existing valid binding; they do not create model instances.
They inherit the same malformed-input, thread and lifecycle guards as the original
bridge mechanics.

Use named brace arguments and an explicit entity target. Native timing/targeting
options remain owned by MM; empty native target selection does not fall back to
an arbitrary caster. MM 5.7.2 supplies a compact mechanic configuration to load
events and its mechanic `getConfigLine()` returns null. It can discard positional
trailing text before the extension sees it. Such text is unsupported and cannot
be reliably diagnosed by this bridge; a valid named argument can still execute.
Conditions retain their full source line and support only true/false suffixes;
action suffixes such as `cast` are rejected rather than reinterpreted.

Mechanics advertise `SYNC_ONLY`; native dispatch moves asynchronous execution to
the main thread. Direct off-thread calls return an error without accessing the
entity. Conditions reject off-thread access before reading entity/model state.
Inactive, malformed and unavailable condition handlers throw an evaluation
exception: native MM catches it before applying boolean inversion. Returning
ordinary false here would incorrectly permit a `false` condition.

## Registration and lifecycle

Reflection resolves exact interfaces, signatures and enum constants through the
installed native plugin classloader. Unknown API shapes fail closed. Mechanic and
condition load listeners are independent. Listener readiness is diagnostic state;
SKILLS and CONDITIONS capabilities require separately confirmed, valid native
holders. Readback occurs after event dispatch, detecting overwritten/ignored
registrations. Existing registrations from another listener are preserved.
Diagnostics remain visible even when the basic integration is connected.

Disable unregisters listeners, cancels owned confirmation/auto-bind tasks, clears
capabilities and releases runtime references. Native parsed definitions can retain
old proxies; those proxies stay inactive. Re-enable alone cannot revive them:
`/mm reload` must parse new definitions. Native reload also invalidates the prior
generation without requiring adapter disable. Repeated enable does not accumulate
listeners. Removing the native plugin makes capabilities unavailable.

Automatic binding uses `getMobType().getInternalName()` and waits one tick to
observe final cancellation. It checks entity validity, existing binding, model,
animation and scale before mutation. A later MONITOR listener cancelling the
spawn cannot leave a model binding. The pinned native `spawnMythicMob` convenience
API throws a NullPointerException after cancellation; the fixture records this
native behavior separately and verifies the entity and binding are absent.

## Evidence and limits

`run-model-bridge-fixture.py` installs native YAML before cold boot. The probe
executes native skills against distinct caster and target UUIDs, records actual
command callbacks for both caster and target conditions, inspects model/animation
state, and checks automatic binding, cancellation, repeat events, async execution,
disable, repeated enable, two native reloads, stale proxies and final cleanup.
`validate-model-bridge.py` verifies the raw archive, source YAML, artifact hashes,
load events, callback counts, exact identities, state and lifecycle ordering.
`test-model-bridge-evidence.py` mutates observations and archives after rehashing.

Rejected development runs remain observation-only; they cannot satisfy the
release gate. Broader bridge/version compatibility remains mapped-unverified.
Release evidence is promoted only after the scoped fixture and all existing
same-artifact release checks pass.

The baseline artifact above predates the three mount/heal extensions. Their
behavior is covered by argument and mutation test cases for target/caster identity,
passenger preservation, invalid targets, health caps, rejected arguments and stale
or asynchronous execution. This is not native same-name mechanic parity evidence;
the new artifact still requires its own Paper fixture before release promotion.

The independent `run-model-actions-fixture.py` suite covers these custom extensions
without changing the baseline model-bridge evidence schema. Its native YAML casts
at distinct caster/target entities and checks passenger direction, other-passenger
preservation, existing vehicles, self/cyclic mounts, cancellable Bukkit mount and
dismount events, heal defaults/zero/caps, malformed arguments, dead/non-damageable
targets, asynchronous dispatch, disable/re-enable and two reload generations.
`validate-model-actions.py` checks 53 state/direct-call observations, exact entity
identities, definition loads, cancellation callbacks and cleanup;
`test-model-actions-evidence.py` rejects rehashed semantic and archive mutations.
The report explicitly records `extensionContract: helstera-defined` and
`nativeParity: false`. A validator self-test passed 942 rejection cases, but
synthetic validator checks do not substitute for the pending live Paper run.
