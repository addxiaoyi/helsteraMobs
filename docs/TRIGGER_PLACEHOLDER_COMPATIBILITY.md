# Trigger placeholder compatibility

The trigger entity is independent of the caster and selected action target.
`trigger.*` reads that entity even for items, projectiles, removed entities or a
trigger in another world. It never borrows data from the selected target.

Supported native names are `uuid`, `name`, `entity_type`, `health`/`hp`/`thp`,
`maxhealth`/`mhp`, and `healthpercent`/`percenthealth`/`php`. Names use a player's
account name, otherwise the custom name (including an empty name) or Bukkit
entity name. Entity types retain the native uppercase spelling.

Locations accept `trigger.location.x/y/z`, their `.double` variants, `yaw`,
`pitch`, and `world`; short forms use `trigger.l.*`, with `l.w` for the world.
Plain axes return floored block coordinates, including negative positions.
Double axes preserve position precision. Yaw and pitch widen the source float
to a double before formatting, matching the native numeric formatter.

The pinned public MythicMobs 5.7.2 TriggerPlaceholder constructs its delegated
placeholder five ticks later and does not forward the outer configuration.
Consequently `<trigger.hp{r=0}>` returns the unrounded health value. Helstera
accepts valid rounding syntax and reproduces this delegation behavior. Invalid
configuration stays unresolved under the existing strict syntax contract.

Nonliving entities have zero health and max health in the native adapter.
Native health percentage is NaN for these entities. Helstera preserves that
percentage token and rejects its use as a number. Missing triggers also retain
the token and reject numeric use instead of falling back to another entity.
In the native public string path, a missing trigger produces `Unknown` for name
and preserves the other tested tokens; nonliving percentage produces `NaN`.
These are explicit boundaries, not claims of identical error text. With no
metadata, trigger placeholders remain unresolved in both implementations.

The focused Paper fixture constructs public native PlaceholderString and
DistanceCondition instances during enable, waits for real scheduler ticks,
and then compares them with production Helstera placeholder and skill execution.
No native field or scheduled callback is replaced. It checks changed health,
name and position on the same compiled placeholders; living, item, arrow,
removed, cross-world, missing and self triggers; metadata with an unrelated
selected target; and contextless reads. Probe source and artifact hashes are
recorded with the report. See [Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md)
for the published counts and artifact IDs.

Scoped variables, stats, equipment, threat, velocity/raytrace families and
player names on a real connected client remain outside this focused evidence.
Player name priority has a unit test. Full placeholder, Conditions and Context
Targeters parity is still incomplete.
