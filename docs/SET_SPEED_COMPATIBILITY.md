# SetSpeed compatibility

Audit date: 2026-11-17. The version oracle is MythicMobs 5.7.2,
Modrinth `FTWvGuE5`, SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
Inspect `io.lumine.mythic.core.skills.mechanics.SetSpeedMechanic` with
`javap -c -p` against the pinned artifact. Its annotation registers `setspeed`.
The external JAR is not redistributed. The
[official artifact](https://cdn.modrinth.com/data/NLuc7Jjh/versions/FTWvGuE5/MythicMobs-5.7.2.jar)
is also used by the reproducible Paper comparison.

## Source and runtime contract

Mythic `setspeed` and the Helstera spelling `set-speed` bind to `set-speed`.
The multiplier defaults to one and accepts `speed/s/amount/a/value/v/multiplier/m`.
The type defaults to WALK and accepts `type/t`. Like the inspected constructor,
type text containing `FLY` (ignoring case) selects flying; all other nonempty
type text selects walking. Thus `FLYING` and `dragon_fly` select flying, while
`WALKING` and `unknown` select walking. This fallback is deliberate.

| Recipient | WALK | FLY |
| --- | --- | --- |
| Player | `setWalkSpeed(0.2f * multiplier)` | `setFlySpeed(0.1f * multiplier)` |
| Other living entity | Movement-speed attribute base = `0.2f * multiplier` | Flying-speed attribute base = `0.1f * multiplier` |

Values use Java float parsing/multiplication, matching the pinned constructor.
An invocation sets an absolute base/API value; repeated calls do not multiply
the existing speed. Existing attribute modifiers remain attached, and Bukkit
still computes the effective value using the attribute's own limits/modifiers.
Player walk/fly speed does not write the movement/flying attribute base.
Negative player speeds in Bukkit's `[-1,1]` range are supported.

For example, `setspeed{m=2} @Trigger` sets a mob's walking base or a player's
walking API speed to `0.4f`, regardless of its previous value. With `t=FLY`,
the result is `0.2f`. `setspeed` with no multiplier resets the selected channel
to `0.2f` for WALK or `0.1f` for FLY. Changing one channel leaves the other alone.

Existing `modify-speed` / `modifyspeed` and `set-tick-speed` / `settickspeed`
keep their historical movement-attribute multiplication behavior. Existing
`set-walk-speed` / `setwalkspeed` accepts an absolute player API speed, whereas
`setspeed` accepts a multiplier. These are separate actions; existing generated
profiles are not silently rewritten. Re-import original `setspeed` lines to
obtain the new canonical representation.

## Invalid and unsupported cases

Unknown fields, duplicate aliases, empty/excess arguments, malformed numbers,
non-finite floats, and float overflow fail binding. Migration preserves those
source lines with WARN for both mob and standalone skill files. The 5.7.2
constructor reads a static float, so speed/type placeholders remain unsupported
and preserved; they are not resolved to a guessed value. A single positional
multiplier is a Helstera convenience, not an upstream syntax claim. Valid
delay/repeat/targeter metadata survives import, with timing evidence still open.

Location targets, inactive/dead entities, and absent mob attributes are skipped.
Player results outside `[-1,1]` skip that recipient. These are explicit
fail-closed boundaries: upstream directly calls Bukkit and can throw for an
invalid player range or a missing flying attribute. Valid recipients of the
same multi-target action still run. No flying permission, flight state,
attribute modifier, or temporary restoration timer is added by the action.

## Validation and remaining work

The full build passes 2,229 tests, zero failures/errors/skips, and SpotBugs.
API, AI, importer, and plugin contracts cover default/alias behavior, absolute
repetition, player APIs, target isolation, invalid-source preservation, and
location/range/missing-attribute boundaries. The tracked fixture uses the same
[skill YAML](../tools/paper-integration/set-speed-skills.yml) for native MM and
the production importer. Runtime/client results and artifact hashes are indexed
in [Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md).
Fixture `srv-paper-set-speed-exploratory-20261117-r2` passes 41 server assertions
on Paper 1.21.1-133 with actual MM 5.7.2, including 16 native/imported mob and
16 player comparisons, idempotence, target isolation, boundary no-ops, and cleanup.
The real client verifies 32 native/imported ability-packet phases (16 paired
comparisons). Bukkit encodes ability walk/fly fields at half its public API
values; both systems agree on those packets, including zero and negative speed.

The focused oracle uses explicit `@Trigger` recipients. It does not establish
the different MM `@Target` selection contract or creative/spectator filtering.
MM's shared entity selector filters creative/spectator players by default;
Helstera's direct context targeting currently has broader eligibility. The
comparison uses a survival player and restores the original game mode/speeds.
Those global selector differences remain open Targeters work. Natural movement/
flight physics, client movement response, every entity type, delayed/repeated
scheduling, other versions, and the complete Mechanics/Skills catalogs remain
unverified. Full strict release
integration and version-matrix validation remain required.
