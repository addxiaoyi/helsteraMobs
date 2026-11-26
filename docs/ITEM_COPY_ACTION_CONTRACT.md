# Equipment copying and item grants

M04a implements `equipCopy` and `giveitemfromslot` / `givefromslot` in independent
action components. The source-level contract below is based on MythicMobs 5.7.2
`MythicMobs-5.7.2-FTWvGuE5.jar`, SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
The actions and importer are wired. At the time of writing, central build and
real-player inventory/packet verification are still pending. Unit-test source
is not live server evidence.

## M04a direction and arguments

| Native name | Internal action | Direction | Arguments |
| --- | --- | --- | --- |
| `equipCopy` | `equip-copy` | Selected entity equipment → caster equipment | The pinned constructor reads `slots` / `s` but discards it. All six slots are copied regardless of that value. |
| `giveitemfromslot`, `givefromslot` | `give-item-from-slot` | Caster equipment slot → selected player inventory | `slot` / `s`, default `NONE`; `dofakelooting` / `fakelooting` / `fl`, default `false`. |

Both actions are entity-targeted. An explicit location target does not fall back
to the caster or a retained entity. Each selected entity gets its own invocation.
Source stacks are copied, never consumed. Neither mechanic reads an `amount`
argument: the complete stack and its Bukkit metadata are retained. The receiver
may be the caster itself.

Named arguments retain quotes and resolve aliases in the listed priority order.
Repeated occurrences of the same key use the final value. Slot names ignore
case, but quoted names are not unquoted. The fake-looting flag uses
`Boolean.parseBoolean`, so only unquoted `true`, ignoring case, enables it.
Unrecognized fields are ignored, matching the inspected mechanics.

| Slot | Accepted spellings |
| --- | --- |
| Head | `HEAD`, `4`, `HELMET`, `HELM`, `HAT` |
| Chest | `CHEST`, `3`, `CHESTPIECE`, `CHESTPLATE`, `BODY` |
| Legs | `LEGS`, `2`, `LEGGINGS`, `PANTS` |
| Feet | `FEET`, `1`, `BOOTS`, `SHOES` |
| Main hand | `HAND`, `0`, `WEAPON`, `MAINHAND` |
| Off hand | `OFFHAND`, `5`, `SHIELD` |

`NONE`, unknown names, `MAIN_HAND`, and `OFF_HAND` do not select an equipment
slot. `BODY` means chest armor, not the newer animal-body equipment slot.

## Item identity, empty slots, and overflow

`equip-copy` copies target equipment to the caster. A present AIR stack clears
the corresponding caster slot. A null source stack skips that slot. A nonliving
source or caster has no equipment to copy. Helstera visits the six distinct
slots once; native `EquipSlot.values()` exposes a map containing aliases and
therefore visits some slots repeatedly. Final equipment state is the supported
contract; duplicate setter/event counts are not claimed.

Native `equipCopy` also calls `ActiveMob.checkEquipment()` after copying. That
method refreshes Mythic's own equipment-stat sources. Helstera preserves item
metadata and Bukkit equipment behavior; it does not update another plugin's
private `ActiveMob` stat registry.

`give-item-from-slot` validates the caster's source slot before checking whether
the selected target is a player. A missing source produces `INVALID_CONFIG`;
a nonplayer recipient with a present source produces `INVALID_TARGET`.
These result values are exposed for direct tests; the shared action interface
continues to isolate individual mechanic failures.

Each inventory insertion receives an independent `ItemStack.clone()`. The
animation also receives an independent snapshot, because Bukkit's `addItem`
may modify the stack passed to it. Inventory overflow is dropped naturally at
the recipient's location, with full leftover metadata. This mechanic does not
set an owner, thrower, or mob-pickup restriction. It reports success even when
the complete stack overflows.

## Private pickup animation

`FakeItemLoot` is injectable. The production `PaperFakeItemLoot` adapter supports
the inspected Paper 1.21.1 release and uses Mojang-named methods through
reflection, without MythicMobs, ProtocolLib, or a paid plugin dependency.

It constructs an unregistered native item entity to allocate an entity ID and
serialize the copied stack, then sends spawn and metadata packets only through
the recipient's connection. It never adds the entity to a level and never
delivers inventory items itself. `give-item-from-slot` places this visual at
skill metadata's origin, which can differ from the caster's current location.

After five server ticks, it sends the native collection packet with count `1`.
It then explicitly removes the fake entity, an intentional cleanup extension
that prevents a visual remainder when the original stack held more than one
item. Shutdown and scheduling failures remove outstanding fake entities;
disconnect and dimension transitions cancel their pending collection tasks.
Callbacks guard against stale sessions/worlds. Packet failures do not replay
inventory delivery. On unsupported releases, actual item grants remain active
and the adapter logs a single diagnostic that the animation is unavailable.

## Required live verification

The fixture must compare direct and imported invocations with native casts,
including all slot aliases, invalid/quoted values, ignored amount/slots,
nonliving boundaries, AIR clearing, self-giving, multiple targets, source
mutation after copying, and serialized metadata equality. Partial and full
inventory overflow must retain item data while keeping the native ownership
defaults.

Two real clients must record the fake entity ID, spawn position, item metadata,
five-tick collection, count `1`, and cleanup; the observer must receive no fake
item packets. Server entity snapshots must show no corresponding item entity.
Reload, disconnect, and dimension transitions must leave no pending visuals.
Mocks and reflection-signature inspection do not replace that evidence.

## M04b remains unfinished

`giveitemfromtarget`, `givefromtarget`, `giveitemsfromtarget`, and
`itemgivefromtarget` are a separate mechanic family and are not implemented by
M04a. They generate configured drops for the caster player, while the selected
entity or location supplies only the animation origin. Their fake-looting
default is `true`, and inventory overflow belongs to the caster, with
owner/thrower set and mob pickup disabled.

That family still requires named-table and inline-drop generation, amount and
probability semantics, item/experience/command delivery, and independent live
verification. Existing `LootService` is not changed by this slice. External
custom drop providers must be listed separately instead of silently replacing
their drops with vanilla items.
