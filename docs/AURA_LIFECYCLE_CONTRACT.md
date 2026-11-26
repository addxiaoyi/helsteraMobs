# Aura lifecycle contract

The reference is public MythicMobs 5.7.2 on Paper 1.21.1, native JAR SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
The contract was checked against `Aura`, `Aura$AuraTracker`, `AuraMechanic`
and the native event subscription builder. This document does not certify a
full release or the event-specific Aura subclasses.

## Termination events

`cancelOnGiveDamage/cogd`, `cancelOnTakeDamage/cotd`,
`cancelOnDeath/cod`, `cancelOnCasterDeath/cocd`, `cancelOnTeleport/cot`,
`cancelOnChangeWorld/cocw` and `cancelOnQuit/coq` terminate matching trackers.
They never modify the Bukkit event's cancelled flag. Death and quit default
to true; the other flags default to false. Aliases are strictly validated;
duplicate aliases and non-boolean values are rejected before execution.

Damage matches the direct damager, including a projectile entity rather
than automatically replacing it with its shooter. Teleport and world-change
subscriptions apply to players, as in the pinned native tracker. Damage and
teleport subscriptions observe cancelled events. Death waits for MONITOR
and skips cancelled Paper death events, so a revived entity keeps its aura;
this is an intentional boundary from native target-death subscriptions at
NORMAL priority. Caster death applies across targets owned by that caster.

Event termination runs the end callback regardless of
`doEndSkillOnTerminate`. That flag governs removal through the aura registry
mechanic; natural expiry, overwrite and event termination run the end callback.
Trackers are removed before callbacks to prevent repeated termination and
reentrant double dispatch. Overwritten/expired trackers finish their end skills
before a replacement is registered; a reload/clear from an end skill invalidates
the pending application. Merges retain the original stack cap, refresh policy,
duration, type, cancellation policy and callbacks. Reload and shutdown discard
callbacks and close owned visuals.

## Charges and callback state

`charges/c` is a signed integer, with execution-time placeholder resolution.
Nonpositive values are unlimited. A plain native `Aura` does **not** consume
a charge every tick. Native event-specific subclasses call `consumeCharge`;
the registry now provides the equivalent tracker-ID API, which terminates
on the transition to zero and cannot consume a removed tracker again.
Refreshing a merged tracker restores its original initial charges.

Start, tick and end callback contexts expose `aura-name`, `aura-type`,
`aura-charges`, `aura-duration`, `aura-duration-millis` and `aura-stacks`
as skill variables. Callback snapshots identify individual trackers, so
separate same-name auras cannot borrow each other's final charge count. A queued
tick reads its snapshot immediately before dispatch, after earlier end/tick
callbacks may have refreshed or consumed the tracker.

Each started tracker copies the caller's skill-variable scope and expiration
deadlines. Its start, tick and end skills then share that tracker-owned scope;
changes do not escape into the caller or another target's tracker. Scalar skill
power is copied for each callback. The start origin is the original skill origin,
tick origin follows the affected entity, and end uses the most recent tick origin
(or the original origin when no tick ran). Synchronous cancellable events are
never retained in this state. The existing tick/end boundary still clears the
original trigger; this is separate from native variable-registry clone behavior.

## Timer ownership

`showBarTimer/barTimer/bt` creates a player-only Bukkit BossBar.
`barTimerDisplay/barTimerText` defaults to `<skill.var.aura-name>`;
`barTimerColor` and `barTimerStyle` default to `RED` and `SOLID`.
Invalid native enum names use those same defaults. Progress updates on the
tracker interval and is clamped to the public BossBar range after refresh.

Only the started tracker creates its bar. It is removed on expiry,
overwrite, cancellation, explicit removal even when end skills are disabled,
target disappearance, registry clear and plugin shutdown. Cleanup still runs
when an end callback throws.

## Remaining boundaries

The pinned base tracker reads `cancelOnSkillUse/cosu` but registers no skill
subscription for it. It is preserved as unsupported rather than inventing
an effect. Event-specific Aura mechanics (`onAttack`, `onDamaged`, etc.),
location-attached Aura ownership and additional visual attachment components
are separate contracts. No real Paper comparison or client rendering result
is claimed by this document until its dedicated fixture has run.
