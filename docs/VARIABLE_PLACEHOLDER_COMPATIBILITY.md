# Scoped variable placeholder compatibility

`<caster.var.NAME>`, `<target.var.NAME>`, `<trigger.var.NAME>`,
`<skill.var.NAME>`, `<world.var.NAME>` and `<global.var.NAME>` read the same
stores used by variable actions and conditions. Names are case-sensitive.
Caster, target and trigger are roles addressing one registry per entity, so a
write through target is visible when that entity later becomes the caster or
trigger. Other entities retain separate values. Reads do not allocate stores.

Terminal execution exception: after the live caster registry is detached, only
caster placeholders can read its retained snapshot. Target/trigger placeholders
addressing that same entity preserve their token, including any fallback; a
separate live entity remains readable. Initial removal windows still differ.
See [terminal read observations](TERMINAL_READ_OBSERVATIONS.md); this in-progress
change has not been promoted to the strict release gate.

Skill variables belong to the current skill tree. A new tree does not inherit
legacy instance variables; numeric instance variables remain a compatibility
fallback only when there is no tree. World variables use the caster's world,
then the trigger's world if there is no caster world. Helstera additionally
allows an origin-world fallback when neither entity supplies a world. Global
and world placeholders still require an execution context.

The pinned public MythicMobs 5.7.2 entity-variable constructors consume only
the first dot-separated argument. Consequently `<caster.var.Root.Part>` reads
`Root`, and `<caster.var.Absent|4.5>` uses fallback `4`. Skill, world and global
variables retain the complete dotted name and fallback `4.5`. The first pipe
fallback is used only when the value is absent. Empty or malformed fallbacks
remain unresolved. Valid outer rounding configuration is accepted but ignored,
matching the native nonnumeric variable placeholder classes.

Finite numbers and strings retain their stored representation. Locations
format as `world,x,y,z` with double coordinates and are not numeric values.
Replacing a scalar with a location, or a location with a scalar, clears the
old value; unset and entity cleanup remove the shared entry. Booleans are a
Helstera extension. Unsupported collections and locations without a world or
with nonfinite coordinates remain unresolved.

Native missing registered values produce `UNDEFINED` without a fallback;
native NaN variables produce `NaN`. Helstera preserves the original token and
rejects numeric use in both cases. An existing invalid value does not select
the absent-value fallback. Missing caster, target or trigger contexts also
preserve the token, without borrowing another entity. Native ordinary entities
without an ActiveMob/player registry leave variable placeholders unresolved;
Helstera permits an entity registry there and can resolve a default. These are
explicit compatibility boundaries, not claims of identical native text.

The Paper probe uses public native PlaceholderString evaluation on actual
ActiveMobs. Native INTEGER/FLOAT/DOUBLE/STRING writes and unsets execute public
variable mechanics. The equivalent Helstera actions run both directly and
through the production MythicMobs importer. For location formatting, the native
side uses public VariableRegistry.putLocation, while the runtime uses a typed
location action through both paths. This verifies location storage and output,
not native location-selector mechanic parity. Native trigger placeholders are
prepared during enable and evaluated after real scheduler ticks; no native
fields, scheduled callbacks or licensing checks are replaced.

The probe reuses compiled placeholders across changes, role swaps, self-targets,
unsets, new trees, another origin world, missing/item/removed triggers, selected
targets, metadata-only calls and contextless calls. The Distance suite separately
checks variable defaults and unresolved skill values through named/inline,
direct/imported, true/false condition dispatch. Scalar/location replacement,
tree isolation and shared entity cleanup also have unit coverage. Published
counts, source hashes, artifact IDs and logs are in
[Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md).

Variable expiry and skill-tree deadline snapshots have a separate
[lifecycle contract](VARIABLE_LIFECYCLE_COMPATIBILITY.md). Existing skill-tree
snapshots retain their persistence evidence. Entity/world/global disk/restart
persistence, world unload lifecycle, player registries on connected clients,
nested dynamic variable names, collections,
vectors and the complete variable mechanic catalog remain open. Broader
placeholder families and other Paper/MythicMobs versions are not established by
this fixture. Full Conditions, Mechanics/Skills and Context Targeters parity
remain incomplete.
