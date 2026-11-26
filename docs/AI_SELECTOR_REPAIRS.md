# Scoped AI selector repairs

The runtime keeps Helstera's existing controller and movement implementation.
These changes repair observable selector behavior; they do not replace vanilla
goal tables or establish native MythicMobs Pathfinder parity.

`clear` and `reset` discard earlier goal effects, including float/swim, door
opening/breaking, sun avoidance, look behavior, entity avoidance, temptation,
ranged attacks and leaps. Later goals take effect in their configured priority
order. Clearing target selectors also removes earlier attacker and faction
preferences. Unsupported climbing and door-closing selectors now appear in the
controller's diagnostic output and its deduplicated warnings.

Flee selectors restrict candidates by their declared kind:

| Selector family | Candidates |
| --- | --- |
| `fleeplayers` / `runfromplayers` | Online Survival/Adventure players |
| `fleegolems` / `runfromgolems` | Iron golems |
| `fleevillagers` / `runfromvillagers` | Villagers |
| `fleewolf`, `fleewolves`, `runfromwolves` | Wolves |
| `fleefaction` / `runfromfaction` | Entities whose Helstera faction matches a listed name |
| `avoidentity` | Living entities outside the caster's allied faction |

Snake case aliases remain accepted. The first rule with an eligible candidate
wins; the nearest eligible entity within that rule supplies the flee direction.
Creative/Spectator players and invalid/dead candidates are excluded. Low health
does not bypass a specific rule's species restriction.

Flee options use `distance`/`d`, `speed`/`s`, and `safespeed`/`ss`; faction rules
also require `faction`/`f` or a positional comma-separated faction list. Numbers
must be finite and positive. Unknown options, duplicate aliases and missing
faction names disable the affected rule with a diagnostic. Defaults are distance
16 (wolves: 6; generic avoidance: the profile's sight radius), speed 1.2 and safe
speed 1. Speed multiplies the existing Helstera profile movement speed; safe
speed is used at distances of at least 7 blocks. This remains Helstera movement,
not a reconstruction of native path selection or activation timing.

`randomlookaround` / `lookaround` chooses a random horizontal direction while
idle, refreshing every 20–40 server ticks when the controller next runs. It does
not scan for players. Look selectors use their configured priority order.

`breakdoors` uses Paper's public `Zombie.supportsBreakingDoors()` and
`Zombie.setCanBreakDoors(boolean)` methods. The controller records the previous
value and restores it when the goal is removed, the profile changes, the
controller is replaced or the instance detaches. A pre-existing true value stays
true after release. Other entity kinds, and zombies that do not support this
attribute, produce an explicit diagnostic. Door destruction still depends on
native AI, world rules and difficulty; the repair does not promise that an
AI-disabled zombie will break a door.

`SelectorBehaviorRepairTest` covers policy resets, species/faction selection,
bad arguments, player modes, random look and door-property ownership.
`ProfileRebindAuditTest` checks null/fresh profile updates against real controller
state, accumulated threat and the active combat target.

The independent live suite is `tools/paper-integration/run-selector-repairs-fixture.py`.
It creates real zombies, a cow, a wolf and an offline test client on pinned Paper;
21 observations cover attribute apply/rebind/detach, original false/true values,
opposite flee directions, player modes, clear/reset, and deterministic random
look. Its strict validator checks archived source profiles, exact identities,
client lifecycle, native Pathfinder flags, unsupported diagnostics and cleanup.
The report records `nativeGoalParity: false`. Its validator's synthetic self-test
rejects 263 altered observations/archives; a live run is required before those
runtime observations can be claimed as passing evidence.
