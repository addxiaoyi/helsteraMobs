# Percentage health compatibility

Audit date: 2026-11-17. The primary version oracle is MythicMobs 5.7.2,
Modrinth version `FTWvGuE5`, SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
The external JAR is not redistributed. Inspect the installed artifact with
`javap -c -p` for `io.lumine.mythic.core.skills.mechanics.HealPercentMechanic`
and `DamagePercentMechanic`, and the latter's `DamagingMechanic` superclass.

## Corrected source behavior

| Source | Canonical action | Unit/default | Additional fields |
| --- | --- | --- | --- |
| `healpercent`, `percentheal`, `hp` | `heal-fraction` | Fraction of maximum health; `0.1` = 10% | `multiplier/m`, `overheal/oh`, `maxoverheal/maxabsorb/maxshield/mo/ma/ms` |
| `damagepercent`, `percentdamage` | `damage-fraction` | Fraction of maximum health; `0.1` = 10% | `percent/p`, `currenthealth/current/ch/c` |
| Existing `heal-percent` and target variant | unchanged | Percentage points; `25` = 25% | Existing numeric argument contract |
| Existing `damage-percent` and target variant | unchanged | Percentage points; `25` = 25% | Existing numeric argument contract |

The earlier importer defaulted both Mythic mechanics to zero and routed them
into factories dividing the provided value by 100. This made the documented
`damagepercent{p=0.25}` produce only 0.25% damage. New imports and direct Mythic
names use the corrected fractional actions. Re-import original MM files to
obtain the corrected behavior; previously generated `heal-percent 25` and
`damage-percent 25` retain their existing percentage-point meaning.
Hand-written profiles using the unhyphenated Mythic aliases must now supply
fractions. No heuristic guesses the unit from a value's magnitude.

The [current official HealPercent page](https://wiki.mythiccraft.io/mythicmobs/skills/mechanics/healpercent)
also documents `amount/a`; those aliases are accepted as fractions. They are an
extension of the inspected 5.7.2 constructor, which reads `multiplier/m`.
Positional fractional arguments are a Helstera convenience, not an upstream
syntax claim. Unknown fields and unimplemented options are preserved with WARN
on import and rejected at direct binding, including advanced damage flags and
later healing tag fields. Duplicate aliases and malformed/non-finite values
are rejected. Boolean flags are static; numeric placeholders resolve for each
selected entity and fail closed on invalid values or arithmetic overflow.

## Overhealing and boundaries

MythicMobs 5.7.2 adds excess healing to Bukkit absorption, not maximum health.
The resulting absorption must be strictly below `maxHealth * maxOverheal`.
At or above the limit, the extra absorption is skipped, not clamped. The actual
constructor defaults `maxOverheal` to zero, despite a conflicting annotation
default of one. Without a positive cap, overhealing does not add absorption.
Paper's absorption capacity still applies; the action does not raise that
attribute. Healing clamps resulting health to `[0, maxHealth]`, and negative
fractions can reduce health. Damage uses maximum health unless current-health
mode is explicitly enabled. Inactive or dead entities are skipped. Location
targets are skipped without falling back to the caster.

The implementation preserves Bukkit damage event modification/cancellation and
Helstera's existing damage policy. It does not emit the external
`MythicHealMechanicEvent`, implement the complete Mythic damage modifier stack,
skill-power scaling, armor/effect/shield bypass flags, or later healing tags.
Those remain open parity work. Delayed/repeated execution, natural combat,
client visuals, every entity type, and other server/MM versions require their
own evidence; a fractional arithmetic result does not close the catalog.
The comparison uses `@Trigger` with the API recipient supplied explicitly.
Mythic 5.7.2 `TargetTargeter` selects an ActiveMob's threat/combat target or a
player's looked-at entity; it returns no target for a generic vanilla-mob caster.
Helstera's direct context `@Target` behavior is broader. The comparison does not
establish `@Target` parity, which remains part of the Context Targeters audit.
Entity numeric placeholders currently pass through the shared display formatter,
which rounds many fields to one decimal place; skill variables preserve their
string/number value. Full numeric-placeholder precision is a separate open
contract and is not established by the exact skill-variable cases here.

## Speed audit follow-up

The same pinned artifact registers `setspeed`, whose WALK/FLY defaults are
absolute base values `0.2 * multiplier` / `0.1 * multiplier`, with multiplier
default one. For players it uses walk/fly-speed APIs; for living mobs it sets
the corresponding attribute base. This is distinct from the existing Helstera
`modify-speed` and `set-tick-speed` actions, which multiply movement speed.
No `ModifySpeedMechanic` or `SetTickSpeedMechanic` class was found in this
artifact. That is evidence for a separate `setspeed` implementation/audit,
not evidence that legacy movement scaling matches official MM semantics.

## Validation

The full regression suite passes 2,220 tests with no failures/errors/skips.
Final artifact verification and SpotBugs pass. The [Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md)
records 27 assertions on Paper 1.21.1-133 with actual MM 5.7.2, including 14
identical-source native/imported comparisons. From 10/20 health, defaults heal
to 12 or damage to 8; quarter healing produces 15, quarter maximum-health damage
5, and quarter current-health damage 7.5. Overheal below its cap produces 20
health plus 2 absorption, while equality/default-zero caps add no absorption.
Bukkit damage-event modification and cancellation agree in both systems.
The shared fixture sets absorption capacity to 20 explicitly. Entity cleanup,
invalid-source preservation, dynamic no-ops, and legacy units also pass.
