# Shield disabling: native contract and remaining implementation

M18 (`shieldbreak`, `disableshield`) remains unimplemented. The following
contract was inspected in public MythicMobs 5.7.2 `ShieldBreakMechanic`, SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
It requires the server thread and uses Bukkit inventory/scheduler APIs.

## Native execution

1. Reject a nonplayer recipient with `INVALID_TARGET`.
2. Require `Player.isBlocking()`, otherwise return `CONDITION_FAILED`.
3. Inspect the main hand first. If its material is not SHIELD, inspect the off
   hand. If neither contains SHIELD, return `CONDITION_FAILED`. When both hold
   shields, the main hand wins even if the player is using the off hand.
4. Resolve `duration/d`, default `100`, using `PlaceholderInt` with the caster
   as its entity argument. Set the recipient's SHIELD material cooldown.
5. Play `minecraft:item.shield.block` and `minecraft:item.shield.break` to that
   player at its current location, each with volume and pitch one.
6. Save the chosen shield and set that equipment slot to null.
7. Schedule restoration one tick later and report `SUCCESS` immediately.

During restoration, inspect the same equipment slot. If another non-AIR stack
is there, native checks `firstEmpty()`: with no empty slot it drops that stack
using `World.dropItem` at the player's current location; otherwise it calls
`addItem`. It clears the chosen equipment slot and puts the original shield
back. The native callback does not explicitly handle plugin shutdown, player
death, disconnect, changed held slot or leftovers from `addItem`.

The mechanic does not directly call `clearActiveItem`, `releaseUsingItem`, the
native shield-disable damage routine, or an item-damage operation. A generic
material cooldown therefore does not implement this contract.

## Required implementation and acceptance

Use a dedicated owner for pending shield restoration so plugin shutdown or
scheduling failure cannot lose a removed item. Define and document the handling
of disconnect/death, hotbar changes, occupied slots, repeated calls and overflow;
these must not be disguised as verified native behavior. Preserve item metadata
and separate cooldown from the one-tick inventory mutation.

Native/direct/imported comparisons need real main-hand/off-hand client input,
the blocking warmup boundary, both-hand priority, nonblocking/nonplayer
rejection, default/alias durations, zero/negative/malformed values, and caster
versus recipient placeholder context. The public integer parser's fallback
behavior must be recorded separately from any Helstera expression extension.
Capture cooldown, use state, inventory and serialized shield data immediately
and after restoration, plus recipient/observer sound and inventory packets.
Exercise replacement stacks, full inventories, hotbar changes and lifecycle
cleanup explicitly. Do not infer safe item conservation from a cooldown-only
test or from an empty-slot restoration case.
