# Detached variable reads: next comparison target

Status: audited next-step plan, not a parity claim or a change to current read
behavior. Follow the verified model bridge milestone with a focused native
MM 5.7.2 / Paper 1.21.1-133 comparison.

## Current evidence boundary

The terminal-variable contract proves caster set/add/unset, expiry, and rejection
of detached `target.*` writes. It deliberately did not claim detached target or
trigger read parity. `EntityVariableContext.find` currently returns the retained
caster snapshot when a target/trigger has the same detached entity identity;
`SkillExtras.placeholderVariable` and `VariableLocations.get` read that snapshot.
`TerminalSkillContextTest` explicitly preserves `73:73:73` caster/target/trigger
reads as the pre-existing behavior, rather than native evidence for that choice.

Read-only inspection of pinned native JAR
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`
found distinct paths:

- `CasterVariablePlaceholder` resolves CASTER through PlaceholderMeta, whose
  retained ActiveMob can supply its variable registry.
- `TargetVariablePlaceholder` resolves TARGET through the target AbstractEntity
  and VariableManager's active-mob/player lookup.
- `TriggerPlaceholder` wraps a target/entity placeholder with `getTrigger()`;
  there is no separate TriggerVariablePlaceholder class in this artifact.
- `MobExecutor.isActiveMob` queries the active registry (plus the optional
  ModelEngine sub-hitbox parent fallback). It does not establish that a removed
  entity can access the retained caster object used by the first path.

This suggests a possible mismatch, but only real native execution can establish
observable placeholder fallback, exception handling and timing around removal.
Do not change reads based on the bytecode hypothesis alone.

## Comparison design

1. Seed distinct caster, live peer and trigger scalar markers and location
   variables. Record each entity UUID, native active membership, runtime live
   registry membership, retained execution count and expiration deadlines.
2. Exercise ordinary living controls, attached death callbacks, synchronous
   cancelled death, confirmed death, loaded command removal, CHUNK unload and
   removal during the NEVER unloaded window. Include delayed nested skills and
   repeated callbacks; keep native command dispatch's existing timing exception
   explicitly bounded.
3. Observe caster/target/trigger placeholders with and without fallbacks while
   each role names the detached caster, a separate live peer or no entity. Verify
   condition reads separately from string interpolation and location reads.
   Missing-registry exceptions must not silently disappear from evidence.
4. Mutate retained caster values between observations and cross real expiry
   deadlines. Verify whether detached role reads track the mutation, become
   unavailable, or differ by phase; never infer the answer from output formatting.
5. Confirm reads do not recreate a live registry, SkillTree state, active mob,
   unloaded chunk or model instance. Preserve separate live target/trigger access.
6. Implement only the behavior demonstrated by native/runtime/direct/imported
   comparisons. Add negative evidence controls for wrong UUIDs, resurrected
   registries, falsely preserved values, lost fallback, missing callbacks,
   wrong deadlines and artifacts. Refresh the exact-JAR gate before promotion.

## Unsupported action scopes

Native VariableScope contains SKILL, CASTER, TARGET, WORLD and GLOBAL; TRIGGER is
an entity read role, not a declared mutation scope. Helstera's `variableRef`
currently falls back to skill when scope normalization returns null, including
an explicit unknown prefix. Audit direct binding and importer handling for
`trigger.*`, arbitrary scopes, contradictory scope/prefix arguments and duplicates.
Distinguish omitted scope (documented default) from explicit invalid scope; do not
let a rejected scope accidentally create or overwrite a skill variable. Keep
read-only trigger placeholders separate from this mutation contract.

The importer needs an independent audit: `variableName` selects `var`,
`variable`, `name` or the first positional argument, while `variableSetAction`,
`variableSimpleAction`, `variableNumericAction` and `variableMathAction` rebuild
the action without carrying a separate `scope` argument. This is a code-path
finding, not proof that native MM accepts such an argument. Compare native
parsing before deciding which forms to preserve with a warning and which forms
to translate.

Minimum syntax controls should distinguish omitted scope, an explicit supported
prefix, `trigger.Marker`, `unknown.Marker`, `scope=unknown`, a supported prefix
combined with a conflicting scope argument, repeated `var`, and `var` plus its
`variable` alias. Record both parse diagnostics and all candidate registries
before/after dispatch; a no-op or swallowed exception alone is not sufficient
evidence. Cover scalar, numeric, unset and location actions, preserving ordinary
unscoped behavior only where the existing contract or native comparison proves it.
