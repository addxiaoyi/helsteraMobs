# Entity interaction mechanics

The project implements these native MythicMobs 5.7.2 mechanics on Paper 1.21.1:

| Native name / alias | Helstera action | Effect |
| --- | --- | --- |
| `stopusingitem`, `releaseitem` | `stop-using-item` | Runs the living recipient's native item release routine. |
| `matchrotation`, `matchrot` | `match-rotation` | Copies the source's raw yaw and pitch to each non-player recipient, including displays. |
| `GoatRam` | `goat-ram` | Activates a goat caster's RAM brain activity against a living recipient. |

All three actions require an entity recipient. An explicit location recipient never falls back to a retained target or the caster. Invalid entity types are skipped. Adapter failures are counted under `EntityInteractionActions#<action>` in `SkillFaults` and appear in debug logging when enabled. The adapter checks the server implementation, Minecraft version and primary thread before mutation.

The action factories, native/canonical aliases and production importer are now
connected to `SkillService`. A 2026-11-26 review found that the earlier adapter
and direct-action tests had not covered this registration step. Binding tests
now invoke the registered operations, including a nonliving rotation recipient,
nested source coordinates, explicit location rejection and separate goat
caster/target identities. Import tests retain execution options and reject
unsupported source/recipient selectors. These tests establish the connection;
the real-server/client acceptance listed below remains outstanding.

## Releasing active items

`stop-using-item` has no mechanic-specific arguments. Its Paper adapter invokes NMS `LivingEntity.releaseUsingItem()` exactly once. For an active player item, that routine emits `PlayerStopUsingItemEvent`, runs the item's release callback and then clears the use state. This allows normal bow/crossbow/shield behavior to remain in the server implementation.

`LivingEntity.clearActiveItem()` invokes only `stopUsingItem()` and omits the release event/callback. `completeUsingActiveItem()` finishes item consumption. Neither substitutes for this mechanic, including after a failure; retrying could duplicate events or release effects.

## Matching rotation

The required source argument accepts native aliases `target`, `to`, `t`, `location`, `loc`, `l`, `of`. Exactly one alias is allowed; ambiguous duplicate aliases and unknown arguments are rejected.

```yaml
- matchrotation{to=@triggerlocation} @self
- matchrot{target=@location{x=0;y=0;z=0;yaw=450;pitch=120}} @trigger
```

The bounded nested-selector contract supports `@origin` / `@source`, `@self` / `@caster`, `@trigger`, `@selflocation` and its caster/boss/mob aliases, `@selfeyelocation` and its existing aliases, `@triggerlocation`, `@trackedlocation`, and static `@location` / `@loc` / `@l` coordinates. An empty tracked-location selection uses the mechanic origin. A missing trigger produces no write for either `@trigger` or `@triggerlocation`; native `TriggerTargeter` returns a singleton containing its trigger, so a missing trigger is not an empty-collection origin fallback.

Sources that depend on inherited entity targets (`@targets`, `@targetlocation`, `@LOT`), plural selectors, nested conditions and selector chains are explicitly rejected. They require preserving the original metadata across the outer recipient dispatcher and native random location selection; they are not silently interpreted as one of the supported sources.

The adapter validates every native method signature and both finite angles before the first write. It then calls `setYRot(yaw)`, `setYHeadRot(yaw)`, `setYBodyRot(yaw)`, and `setXRot(pitch)` in that order. Angles such as yaw `450` and pitch `120` remain unnormalized. Historical `yRotO`/`xRotO`, position and velocity are untouched. Public Bukkit `setRotation` normalizes angles, changes historical rotations and does not supply the same body-yaw mutation, so it is not used.

Native `setpitch` remains outside this supported set: its Paper 1.21.1 volatile handler inherits a no-op default. It must not be represented as a successful implementation of a real pitch mutation.

## Goat ram

`goat-ram` has no damage, velocity or cooldown arguments. It requires a Goat caster and LivingEntity recipient and calls Paper's `Goat.ram(target)`. The server records the target position, removes RAM cooldown / breed / tempting-player memories, and activates RAM. Multiple recipients follow normal ordered invocation, so a later call can replace the brain target.

Dispatch does not guarantee a hit. Movement and damage occur later through normal goat AI and can be affected by terrain, target position and server events. The action does not synthesize damage, velocity, a cross-world check or a self-target exclusion.

## Evidence boundary

The implementation contracts were checked against `MythicMobs-5.7.2-FTWvGuE5.jar` (SHA-256 `9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`), its Paper-remapped volatile handler, Paper 1.21.1 CraftBukkit code and NMS bytecode. Parser/action tests cover argument rejection, type eligibility, source fallback, unnormalized angles, failure counting and absence of substitute mutations. Native-adapter tests verify exact reflection method selection, ordering and signature preflight using Java handles.

Real-server acceptance additionally requires comparing native/direct/imported paths: player release event plus active-item state, a charged bow/crossbow release callback, raw current and historical rotation fields on a living entity and display, and goat RAM memory/activity immediately after dispatch. Natural damage must be observed separately before claiming a successful hit.
