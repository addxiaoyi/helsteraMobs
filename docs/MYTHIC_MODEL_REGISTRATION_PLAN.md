# MythicMobs 5.7.2 model bridge: implementation plan and audit

Status: bounded 5.7.2 implementation completed and verified; see
[the execution contract](MYTHIC_MODEL_BRIDGE_COMPATIBILITY.md). The original
audit and acceptance plan follow for traceability. Broader version/client scope
remains open. This work follows the terminal-variable release milestone.
The pinned public native JAR is
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f` on
Paper 1.21.1-133. Other declared Mythic versions need their own verification.

## Original audit findings (resolved by the scoped bridge)

The previous `MythicMobsModelMechanics.put` recorded advertised entries before
registration. `MythicMobsAdapter.capabilities` used the shared nonempty collection
to advertise both SKILLS and CONDITIONS, even after registrar discovery failed.
The spawn listener attempted `getMobName()`, which is not the pinned event API.
`modelremoved` required an invalid entity rather than a valid entity without a
model binding. The scoped bridge replaces these paths.

## Verified native interfaces

| Concern | Pinned API |
| --- | --- |
| Mechanic registration | MythicMechanicLoadEvent: getMechanicName, getConfig, register(ISkillMechanic), getMechanic |
| Entity execution | ITargetedEntitySkill.castAtEntity(SkillMetadata, AbstractEntity), returning SkillResult |
| Condition registration | MythicConditionLoadEvent: getConditionName, getConfig, getArgument, register(ISkillCondition), getCondition |
| Entity predicate | IEntityCondition.check(AbstractEntity) |
| Bukkit identity | AbstractEntity.getBukkitEntity() |
| Mechanic threading | ISkillMechanic.getThreadSafetyLevel(): SYNC_ONLY |
| Spawn identity | MythicMobSpawnEvent.getMobType().getInternalName(), getEntity() |

Native CustomMechanic/CustomCondition defer load events by one tick, then retain
the registered objects. SkillExecutor.registerSkill registers a named skill; it
is not a mechanic registrar. Bind exact method signatures through the installed
plugin classloader and fail closed when this API shape differs. A Java interface
proxy plus native load-event listeners is a bounded implementation path.

## Implementation scope and acceptance

1. Register `modelspawn`, `modelremove`, `modelplay`, `modelstop`, `modelscale`,
   plus `modelspawned`, `modelremoved`, `modelplaying`. Bind immutable arguments
   for each load event. Validate identifiers, finite positive scales and boolean/
   integer options. Inspect the native compact mechanic config or full condition
   holder line for duplicate or conflicting aliases
   because native entrySet has already collapsed duplicate keys. Keep framework
   execution arguments distinct. Require explicit entity targeting in examples.
2. Track mechanic and condition success separately. Distinguish listener readiness
   from definitions accepted by native holders; verify registration by readback.
   Preserve an existing registration owned by another listener and report collisions.
   Expose partial failures even when basic integration connectivity succeeds.
3. Install/uninstall listeners without duplicates. Disabling invalidates retained
   proxies and cancels owned tasks; it cannot erase objects already retained by
   native definitions. Re-enable requires fresh parsing. Old proxies remain inactive.
4. Use the verified spawn-event identity path, respect cancellation and prevent
   duplicate model binding. Removing a model must preserve the external mob UUID.
5. Keep Bukkit/model mutation on the main thread. Reject off-thread predicates
   before reading Bukkit state. Test unavailable/disabled conditions with both
   true and false suffixes: ordinary false is insufficient when inversion would
   permit execution. Validate an explicit failure path against native dispatch.

Production ownership should stay within the two Mythic adapter files, optional
package-private API/argument helpers and the integration registry's partial-failure
diagnostics. Extend the existing version/capability tests and integration examples.
Defer modelmount/modelunmount/modelheal until their semantics have separate evidence.

## Paper evidence required

A dedicated disposable fixture must include native YAML before cold startup and
verify deferred registration without an initial reload. Execute real native skills
against distinct caster and target UUIDs; inspect binding, scale and animation state,
not just returned SkillResult (native dispatch can ignore it). Cover repeated binds,
condition true/false gates, unknown identifiers, invalid arguments, automatic binding,
cancelled spawns, native asynchronous dispatch, reload with changed parameters,
disable with cached proxies, re-enable plus reload, and final entity/visual cleanup.

Archive exact plugin/Paper/native/probe hashes and raw evidence. Mutation controls
must reject missing or duplicate events, wrong targets, false successes, stale
capabilities, orphan bindings and wrong artifacts. Add the scoped comparison to
the strict release gate only after this evidence passes. Client rendering and
other native versions remain unverified. The broad model-bridge matrix row stays
mapped-unverified until its declared scope is fully explained and evidenced.
