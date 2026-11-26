# Shield disabling: implementation and acceptance boundary

M18 (`shieldbreak`, `disableshield`) maps to `disable-shield` in the runtime
and importer. The following contract was inspected in public MythicMobs 5.7.2
`ShieldBreakMechanic`, SHA-256
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

## Runtime ownership and intentional extensions

`ShieldBreakService` owns each removed shield until it transfers that shield
back to the inventory or to the death-event drops. It retains separate entries
for repeated calls, clones shield metadata and rejects offline/dead players,
disabled hosts and off-thread execution before mutation. Scheduling failure
attempts immediate restoration. A failed inventory write retains ownership for
later cleanup; once the shield is written, a replacement-insertion failure
cannot cause a second shield restoration.

Restoration reserves the selected equipment slot for the shield before
reinserting a displaced stack. With storage space, the stack is added and any
leftovers are dropped. With no empty storage slot before restoration, the stack
is dropped as in native. Full-inventory drops have no owner or thrower. A hotbar
change moves HAND restoration to the newly selected hotbar slot.

This insertion order is an intentional conservation extension. Live native
observations confirm that `addItem` can merge a displaced emerald stack into
the hand slot that native subsequently clears, losing all three emeralds. Both
the free-storage and changed-hotbar cases demonstrate this loss. Helstera
retains the three emeralds. Do not describe these cases as native parity.

Host close and quit restore pending shields before inventory saving. A death
with `keepInventory` restores the shield; otherwise the detached shield joins
the death drops unless it has Curse of Vanishing. These are lifecycle extensions,
not behavior supplied by the inspected native callback. Unexpected failures
in downstream inventory/drop APIs, cancelled item spawning and process crashes
are not covered by an exception-proof or durable recovery claim.

`duration` takes priority over `d`; the last occurrence of an exact key wins.
The default is 100 ticks. Native integer ranges are inclusive. Invalid text
falls back to one. Paper rejects negative cooldowns before shield removal.
Helstera resolves quoted/dynamic integers itself, including caster-scoped
`<target.l.y>`; the public native build falls back to one for these forms.
Malformed positional arguments are preserved as unsupported by migration.
This bounded numeric parser is not a claim to match every malformed native
fallback or every Premium expression.

## Evidence and remaining acceptance

The fixture uses two real protocol clients on Paper 1.21.1. Nineteen cases run
through native, direct and imported paths. It captures actual use-item packets,
blocking state, serialized inventory metadata, immediate cooldown and tick
decay, one-tick restoration, recipient-only sounds, final client inventory,
replacement stacks, full inventories, changed hotbar and service host close.
The caster is at Y=180 and the recipient at Y=170, distinguishing duration
context. The independent auditor rejects incorrect state even after file hashes
are recomputed. This is protocol evidence, not human visual/audio acceptance.

Both-hand/off-hand blocking intentionally remains active: native removes the
main-hand shield while leaving the actively used off-hand shield in place.
Immediate service-close restoration can also preserve blocking, since the
shield returns before the entity's next tick. These effects are captured
separately from the cooldown and must not be replaced with `clearActiveItem`.

The first run is retained as rejected: the client inventory helper included
storage slots but omitted the off-hand slot. The corrected helper captures all
inventory slots, including protocol slot 45.

The [2026-11-26 r2 report](../tools/paper-integration/reports/paper-1.21.1-shield-break-20261126-r2.json)
passes all 57 observations. The independent audit passed with current source
and artifact checks, and rejected all 1,485 rehashed semantic mutations.
The same artifact passes 2,919 Maven tests and 11 SpotBugs checks. Its SHA-256 is
`c4aa0d30cc8ee5653a3f14f39bf13686b49e2fffdd5d804c6aa84887dfee10e3`.
Earlier focused reports retain their own artifact hashes; this run does not
recertify them against the new JAR.

Remaining live acceptance includes actual disconnect/reconnect and plugin
disable/reload, death/respawn with keepInventory and Curse of Vanishing,
repeated casts before restoration, the blocking warmup boundary, randomized
ranges, additional metadata, full native dispatcher scheduling, and the final
release gate. Unit tests cover normal shutdown/quit/death paths, scheduling and
inventory-write failure, repeated ownership and generic skill binding; they do
not substitute for those live lifecycle cases.
