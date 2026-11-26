# Native AI target selectors

Baseline: Paper 1.21.1 / public MythicMobs 5.7.2, native JAR SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
M22 has runtime, direct skill binding and importer implementations. The
2026-11-26 r4 fixture passes 144 scoped native/direct/imported observations;
earlier rejected reports are retained.
This slice implements the target scheduler, not native movement Goal composition
or the full threat-table switching policy.

## Mechanic and static syntax

`runaitargetselector/aitarget` maps to `run-ai-target-selector`. It executes
once on the caster, including when the execution contains inherited recipients
or an explicit targeter. Field precedence is `aitargetselector`,
`targetselector`, `target`, `string`, `s`. `goal` is not a field alias.

The selected string is static. Placeholders do not select a different rule.
Quoting the whole rule retains the quotes and encodes its internal spaces;
`s="1 players"` therefore does not add a player Goal. Missing and unknown
selectors are inert. Native handler errors on player/ArmorStand casters can
still return SUCCESS; runtime execution success likewise does not prove mutation.

Rules use a literal-space split. A leading decimal integer specifies priority;
otherwise the list index, starting at one, supplies it. A single mechanic uses
default priority one. Clear and unknown entries still advance the list index.
Leading spaces, overflowing priorities and a priority without a following token
abort the remaining profile list. Two spaces after a priority produce an empty,
unknown token. Constructor failures skip the individual Goal and continue the
list. The importer preserves target-list whitespace and source order.

Mechanic nested blocks protect spaces and hyphens before the rule split. Quoted
segments encode native message special characters without unquoting the value.
The same protection is not implicitly applied to raw profile-list entries.

## Native Goal catalog

| Rules and aliases | Actual native Goal |
| --- | --- |
| `players/player` | NearestAttackableTargetGoal, Player, mustSee=true |
| `monsters/monster` | NearestAttackableTargetGoal, Monster, mustSee=true |
| `villagers/villager` | NearestAttackableTargetGoal, Villager, mustSee=false |
| `irongolems/irongolem/iron_golems/iron_golem` | NearestAttackableTargetGoal, IronGolem, mustSee=false |
| `hurtbytarget/attacker/damager` | HurtByTargetGoal |
| `ownertarget/ownerattack/ownerhurt` | OwnerHurtTargetGoal |
| `ownerhurtby/ownerhurtbytarget/ownerdamager/ownerattacker` | OwnerHurtByTargetGoal |
| `nearestconditionaltarget/nearestconditional/nearestif` | Conditional NearestAttackableTargetGoal |
| `nearestotherfaction/otherfaction` | Faction predicate over LivingEntity |
| `nearestotherfactionmonsters/otherfactionmonsters` | Faction predicate over Monster |
| `nearestspecificfaction/specificfaction` | Listed factions over LivingEntity |
| `nearestspecificfactionmonsters/specificfactionmonsters` | Listed factions over Monster |
| `clear/reset` | Clear the native target selector and target slot |

The pinned loaded catalog resolves the colliding `ownerHurt` alias to the
owner's attack target. General nearest, hurt and faction rules require a Bukkit
Creature; owner rules require a native TamableAnimal. These are real native
Goal instances, registered through Paper MobGoals and ticked by Paper.

Conditional arguments are `targetconditions/conditions/cond/c`,
`interval/int/i` (default 0), `mustsee/ms` (true), `mustreach/mr` (false).
Paper reduces the native interval for its Goal tick cadence: the ordinary
nearest constructor's interval 10 is stored as 5; configured 8 becomes 4.
Conditions bind through the existing condition registry. An unresolved custom
condition rejects candidates, including with a false outcome. Missing or
non-list conditions are accepted by the native predicate's exception fallback.
Only the condition forms exercised by the fixture establish condition parity;
arbitrary composite/custom condition syntax needs its own acceptance.

Faction arguments are `faction/f`, falling back to the token after the rule.
Specific faction matching requires a live managed caster and compares listed
factions without case sensitivity. Other-faction comparisons use original
nonplayer faction spelling; player comparisons ignore case. External faction
providers and multiple simultaneous player factions remain integration work.

## Priority and ownership

Lower numeric priority preempts an interruptible running Goal with conflicting
GoalTypes. Equal priority does not preempt. Duplicate rules create distinct
Goal instances. Native clear/reset directly empties locked flags and available
goals, then clears the mob target; it does not call the running Goals' stop
callbacks. Ordinary removal through Paper does call stop.

Imported target profiles enable `ai.native-targets`. Handwritten profiles can
opt in explicitly. A mutating mechanic activates native target selection for
its caster. The custom controller observes Paper's selected target and avoids
overwriting it on ordinary decisions or damage bookkeeping; explicit target
skills still write the native slot.

Each caster owns its added Goals and dynamic rule history. Profile rebind
restores displaced original Goals, applies the new list in source order and
replays that caster's dynamic rules. Dynamic clear discards earlier dynamic
history. Shared AiProfile lists are not modified.

Close removes owned Goals and restores the original displaced Goals at their
prior priorities. Restoration uses the original native target selector, even
for Goals whose Paper type is UNKNOWN_BEHAVIOR. It does not wrap arbitrary
third-party Goals through Paper's class heuristics. External-caster sessions
are released on entity removal and service shutdown. Rebind, ownership and
restoration are Helstera lifecycle extensions, not native persistence claims.

## Validation boundary

Offline tests cover static syntax, binding direction, importer preservation,
per-caster mutation history, configuration failure handling, managed-caster
checks and damage bookkeeping. The independent fixture captures actual native
Goal inventory, priority, flags, running state, target events and stop counts
through native/direct/imported paths. It also tests rebind and close.

[Paper r4](../tools/paper-integration/reports/paper-1.21.1-ai-target-selector-20261126-r4.json)
passes 48 cases on each of the three paths. The independent auditor passes
with `--check-current` and rejects all 10,108 rehashed scalar mutations of the
checked observations. Full offline `./mvnw -B -o verify` passes 2,969 tests
with no failures/errors/skips and 11 clean SpotBugs checks. The accepted JAR
SHA-256 is `d1ad6c0b1ff48dc851c68f6857697173b808591d5a666dffccb85c89857176f3`.

The observer client keeps the scene ticking. Each fixture caster receives its
own seeded native RandomSource; Paper's shared source ignores setSeed. Native
constructors and direct casts do not establish the full native event-dispatch
pipeline. Restart persistence, real reload/disable/removal events, additional
plugins, broad condition catalogs, multiple worlds and other Paper versions
remain separate acceptance work. Historical reports certify only their listed
artifact hashes, and no focused report replaces the strict release gate.
