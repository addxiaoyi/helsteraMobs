# Variable lifetime compatibility

Variable writes accept `duration`, `d`, `expire` and `e` as aliases for a static
integral tick count. Positive counts become an absolute wall-clock deadline at
write time, using 50 milliseconds per configured tick, as in public MythicMobs
5.7.2. This is elapsed real time, not a count of server scheduler ticks. Zero,
negative and omitted counts do not expire. Malformed, fractional, dynamic,
duplicate and overflowing durations reject binding or preserve the source on
import; they never silently become permanent values.

Entity, world, global and skill-tree scalar/location stores expire values on
lookup, presence checks, enumeration and snapshot reads. Caster, target and
trigger roles continue to share an entity registry. Expired values behave as
absent, so placeholder defaults and variable conditions agree. A new set replaces
the old deadline; setting without a positive duration makes that value permanent.
Unset and cleanup remove the value with its deadline. Legacy instance variables
also use the expiry-aware store.

`setvariable` defaults to INTEGER, so an omitted type remains usable by native
addition; use an explicit FLOAT or DOUBLE for fractional storage.

Native `variableadd` imports now bind to `variable-add`; direct `varadd` is also
an alias for that action. It requires an
existing INTEGER or FLOAT value, preserves that type, truncates an integer
increment before adding, and uses the native decimal-addition/float conversion
for FLOAT. It preserves the old deadline unless given a positive new duration.
Missing, expired and unsupported types are unchanged; addition cannot recreate
an expired variable. Invalid/nonfinite increments skip mutation under Helstera's
finite-number boundary. Generic Helstera `var-add` and `var-math` retain their
existing numeric extension behavior, with deadline retention or refresh; they
are not the native arithmetic catalog. Previously generated configurations using
`var-add` retain that extension; re-import native source to get `variable-add`.

The pinned native `SetVariableLocation` mechanic passes its raw converted duration
directly to LocationVariable as an absolute timestamp. A normal positive duration
therefore expires immediately. `variable-location-set` reproduces that behavior;
zero/negative/omitted durations retain locations. The separate typed
`set-variable type=LOCATION` extension uses a future deadline like scalar set.
The focused probe chooses native `@origin` with origin equal to the runtime
selected target location. Arbitrary location value selectors and the complete
location mechanic contract remain unverified.

Skill-tree snapshot format 4 stores `expires-at` alongside each timed scalar or
location. Values and deadlines are captured together; elapsed time is not reset
by a file round trip. Restoring a past deadline leaves the value absent, and
malformed deadline metadata is rejected. Snapshot writes remain atomic and a
failed write preserves the previous file. Formats 1–3 remain readable, and the
previous six-argument Snapshot constructor remains available. New format-4 files
require the current reader; older plugin builds cannot load them. Existing skill-tree
collection/branch snapshots retain their own evidence.

Retained per-instance snapshots are also filtered on load, read and shutdown save,
including parent snapshots. Expired values and their deadlines are removed together;
surviving deadlines remain unchanged. This does not resolve or load Bukkit worlds.
The original immutable snapshot remains a record of the moment it was captured.
Invalid expiry metadata remains subject to the persistence validator.

The persistent-mob restart runner now uses three actual Paper processes and two
UUID-keyed mobs with the same mob ID. It saves 90-second and 180-second deadlines,
stops before each deadline, and waits for real elapsed time before the next boot.
Seven scalar/collection types, locations, parent state, permanent replacements and
explicit unsets are checked on each boot. The second boot confirms that the first
set expired during downtime while the second set retains its exact deadline, then
kills one mob. The third boot confirms the survivor restores without expired values
or the dead mob's snapshot returning. Copies of all four shutdown snapshots are
checked and archived alongside all three boot logs. This validates Helstera's
persisted skill-tree extension; it does not implement native registry `save=true`.

Validation uses real native variable mechanics and production runtime actions
through both direct and imported paths. Three actual-time phases cover short,
zero and negative duration, replacement, refresh, retained deadlines, unset,
location expiry, expired/missing addition, integer truncation, FLOAT and unsupported
DOUBLE arithmetic. Native placeholders and registry presence are checked against
runtime placeholders and conditions. Separate Paper file-round-trip observations
check that a restored skill tree expires at its original deadline. No native clock,
field, callback or licensing behavior is patched. Unit tests additionally cover
exact deadline boundaries, invalid input, replacement, past-deadline restoration
and atomic preservation. See [Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md)
for artifact hashes and counts.

This milestone does not implement native `save=true` persistence for entity,
player, world or global registries. World unload lifecycle, player logout/login,
nested dynamic variable names, native
SetVariable expression/default-value semantics, the full arithmetic/location
mechanic catalog, all save combinations and other server or
MythicMobs versions remain open. Full MythicMobs parity is still incomplete.
