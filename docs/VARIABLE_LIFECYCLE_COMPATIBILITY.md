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
trigger roles share a UUID registry, with a separate player namespace for player
entities. Expired values behave as
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
persisted skill-tree extension. Native registry persistence has the separate evidence below.

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

Native registry persistence now writes `scoped-variables.yml` before plugin shutdown
tears down instance state, then restores it before skills run. The document uses
UUID entity/world scopes, typed values, immutable location coordinates/world names,
and separate retention metadata. Atomic replacement preserves the last valid file
when a write fails. Invalid startup data disables disk writes for that session,
so shutdown cannot overwrite the unreadable file. Expired values are pruned without
resetting surviving deadlines. Unloaded worlds retain their location coordinates;
lookups resolve the original world when available, never a replacement world.

The pinned public MM 5.7.2 shutdown paths differ by scope:

| Value metadata | World after graceful restart | Global / persistent native entity after graceful restart |
| --- | --- | --- |
| Omitted duration/save, or zero duration with `save=false` | Absent | Retained |
| Zero/omitted duration with `save=true` | Retained | Retained |
| Negative duration, including `save=false` | Retained | Retained |
| Positive scalar duration, either save flag | Retained until original deadline | Retained until original deadline |
| Deadline elapsed during downtime | Absent | Absent |

Native `variableadd` preserves the existing retention marker unless a positive
new duration refreshes the deadline; its `save` flag alone does not change retention.
`setvariable` and `setvariablelocation` replace the metadata. Explicit save flags
are validated as true/false, with quoted/case-insensitive values accepted and
malformed or duplicate flags rejected/preserved by binding/import.

Focused fixture `srv-paper-variable-persistence-comparison-20261119-r3`
([report](../tools/paper-integration/reports/paper-1.21.1-variable-persistence-20261119-r3.json))
passes 432 observations across three real Paper 1.21.1-133 boots.
The runner covers 18 cases across caster, target, world and
global registries through direct and imported actions. Eight native persistent cows
retain their UUIDs; their saved chunks are explicitly loaded before resolving entities.
The second boot validates typed values, world/coordinates and unchanged absolute
deadlines, then removes every probe variable. The third boot verifies absence.
Server observations have explicit expected presence/value assertions in addition to
native/runtime equality. Three shutdown snapshots and redacted logs are hashed;
the evidence validator rejects missing/duplicate rows, lost identities, changed
deadlines, vacuous absence, wrong values and resaved deleted data.

Player registries use `player:<uuid>` and independent atomic
`player-variables/<uuid>.yml` files. Login restores values before join skills;
logout saves after Helstera quit skills, then releases scalar and location memory.
Shutdown saves players who remain online and retries previously failed logout
writes. Periodic saving also writes online registries and retries failed logout
writes, releasing the offline registry only after a successful retry. The shared
scoped snapshot excludes player registries. A failed write
preserves both the previous file and live state; reconnect cannot replace that
live state with stale disk data. A malformed or foreign-player file is rejected
and is never overwritten by the failed session. These file operations currently
run synchronously on the server thread.

`variables.player-autosave-seconds` defaults to 300, matching public MM 5.7.2's
`Configuration.Clock.Saving` default. Helstera schedules a save every configured
seconds multiplied by 20 server ticks; the first save waits one full interval.
The native saving clock uses its asynchronous scheduler and begins without an
initial delay. Timing under server lag and the native startup scheduling phase
are not claimed identical. Zero disables only Helstera periodic saving; logout
and shutdown still save. Malformed, negative, fractional or overflowing intervals
fall back to 300 with a warning. Interval changes require a plugin restart.
One player's failed write is logged without preventing other players from saving.

On first login after upgrading, a missing player file migrates the matching
legacy `entity:<uuid>` registry from the shared snapshot. The player file is
committed before the legacy in-memory registry is removed. An existing empty
player file is a tombstone and takes precedence over stale shared data, preventing
explicitly deleted values from returning. Migration failure leaves the legacy
registry available for retry. This changes the storage layout: older plugin
builds do not read the new per-player files.

Public MM 5.7.2 player saves retain the transient marker as well as saved,
negative-duration and unexpired timed values, matching the entity/global column
above. Deadlines remain absolute through time offline. Native player profiles
are saved asynchronously on logout and when still online during graceful shutdown;
the comparison uses those ordinary lifecycle paths without modifying native
profiles, clocks or save callbacks.

The real-player fixture runs two local offline-auth Mineflayer clients through
three Paper boots and five observation phases. Its 740 observations include
720 direct/imported variable comparisons and 20 quit-hook controls. It verifies
stable UUIDs, typed scalar/location values, unchanged deadlines, offline expiry,
explicit unsets, trigger aliases, condition agreement and per-player isolation.
Nine actual logout events release both native and runtime registries. Snapshots
taken before and after each shutdown verify real disk writes; one player remains
online with a new value until shutdown to prove the separate online save path.
The independent validator also accepts the archived report/log ZIP and rejects
31 missing/altered evidence controls, including stale shutdown files and tampered
archive members. See the indexed report and hashes in
[Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md).

The autosave comparison uses the ordinary native saving clock and Helstera's
production scheduler, each configured to five seconds. It never invokes a save
API or modifies the native clock. Two real clients stay online across two
observed checkpoints; the second contains changed generation values and deleted
variables. Only after both native/runtime files match those values does the
runner send SIGKILL to its own disposable Paper process. After saved short
deadlines elapse while the process is stopped, a second boot verifies restored
types, locations, retained long deadlines and offline expiry. It then clears
the values, waits for another periodic save and kills the process again. A third
boot verifies cleared data stays absent and the latest generation survives.
The 480 direct/imported observations, three online disk checkpoints and two
post-kill file copies are independently validated. Twenty-nine evidence mutation
controls reject missing observations, graceful-save contamination, stale files,
wrong identities/types/deadlines and resurrected data. Reports and source/artifact
hashes are indexed in [Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md).

The death/respawn fixture uses two real local clients and actual Bukkit player
death/respawn events. Each player remains dead for four seconds so two-second
values expire before respawn. Existing scalar/location variables survive with
unchanged types and deadlines; fixture listeners write a value of one during
death and two during respawn through both native and runtime actions. Both
callback values are checked immediately and the final value survives reconnects.
These listeners exercise action binding/importing inside actual events; they do
not establish complete configured skill-trigger dispatch or arbitrary on-death
skill ordering. The runtime context uses the harness ModelInstance adapter.

After respawn, each client makes four consecutive reconnects, calling connect
inside the previous socket's end callback without a timer or a disk-save wait.
The native repository performs its ordinary asynchronous quit save and join load;
observations wait only for the joined native profile to become available. Five
successive generation values detect stale restoration, while clearing all other
variables before the last reconnect checks deletion persistence. Ten actual quits
release both registries. After the final quit, the runner waits for ordinary native
save completion and checks exact disk contents: four generation entries and two
event-hook entries per player. No save method or native callback is replaced.

All 700 observations (672 variable rows and 28 retained event-hook rows), actual
client/server event sequences, eight callback writes and four final disk files
pass independent validation. Forty-one mutation controls reject missing/duplicate
observations, wrong identities/types/values, reset deadlines, lost events, stale
files and archive tampering. In this fixture, server quit-to-join intervals are
20–53 ms and client reconnect initiation takes 0.018–0.050 ms. These are measured
local sessions, not a proof that every possible native async save/load interleaving
is race-free. Native and runtime autosave are configured to 300 seconds; the client
scenario completes in 5.38 seconds. See the report, source hashes and redacted logs
in [Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md).

Persistent managed mobs now suspend their runtime instances before an accepted
chunk/world unload. Controllers and queued casts are released, but UUID scalar and
location registries survive. Temporary unload does not dispatch the removal skill.
Event-bus consumers can identify this through `ModelRemoveEvent.unloading()`;
the causes are `persistent_chunk_unload` and `persistent_world_unload`.
Entity-load handling reattaches the same base UUID even when Paper loads entity
data after ChunkLoadEvent. SkillTree state is captured to the UUID snapshot before
suspension; an in-memory copy takes precedence on reattachment, including an empty
tree after explicit clearing. A failed disk write leaves that copy available and
shutdown retries it. Ordinary permanent removal still discards entity variables.
Cancelled world unload events are ignored by the production handlers. Paper 1.21.1
ChunkUnloadEvent is not cancellable; a force-loaded chunk provides the retained
negative control.

Visual entities belonging to any bound mob are no longer saved with the
chunk; reattachment recreates them. This prevents a saved set of visuals and a new
set accumulating together. It does not clean up orphan visuals created by older
builds. Standalone display models retain their configured persistence.

The focused world fixture uses actual runtime managed instances and separate native
ActiveMobs in a disposable world. Four UUIDs and the world UUID survive one actual
chunk unload and two world unload/reloads. Eight cases across caster/target/world/
global scopes, direct/imported actions and four phases produce 256 comparisons.
Timed values keep deadlines; short values expire while unloaded. Native world
transient values survive this temporary lifecycle, unlike their documented graceful
shutdown filtering. Eight tree checks and six disk copies verify restoration and
that cleared trees stay empty. The visual entity count stays 22 across all phases. Four
actual deaths remove native/runtime registries, runtime instances and snapshots;
final shutdown writes no deleted scoped data. Native location origin is deliberately
aligned with the runtime selected target; arbitrary location selectors remain open.
Twenty-eight mutation controls reject lost/duplicate observations, wrong identities,
values/types/deadlines, missed events, stale trees, duplicate visuals and stale disk
state. Source/artifact hashes and redacted logs are indexed in
[Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md).

The separate autosave fixture proves recovery of completed periodic saves after
process termination.
Writes after the last completed save can be lost. Killing during a file write,
host/power failure, large-player save performance, asynchronous save/load races,
configured player death/respawn skill dispatch, native distance and kill-command
immunity, external removal paths and mode-specific crash recovery,
nested dynamic variable names,
SetVariable expression/default-value semantics, the full arithmetic/location
mechanic catalog, broader save combinations and other server/MythicMobs versions
remain open. The shutdown snapshot is not a crash-durable per-mutation journal.
The generic arithmetic and typed-location extensions retain their documented
boundaries. Full MythicMobs parity is still incomplete.

NEVER now shares the entity/SkillTree suspension and reattachment policy with
PERSISTENT. NORMAL/CHUNK/NPC permanently clean up during accepted chunk/world
unload, including their base and visual entities. The separate
[Despawn lifecycle contract](DESPAWN_LIFECYCLE_COMPATIBILITY.md) covers all five
modes, configured death/despawn callbacks and the explicit MM 5.7.2 cancelled-world
exception. Clean restart now additionally covers all five modes across three boots,
including lazy NEVER reattachment, disposable shutdown cleanup, saved-value updates,
eight real deaths and empty UUID tree tombstones. MM shutdown commands have the
separate disabled-plugin scheduling exception documented in that contract. Native
broader distance boundaries/families, kill-command immunity, external removal and
crash recovery remain open. Four-family distance behavior now has scoped evidence
in the [distance contract](DESPAWN_DISTANCE_COMPATIBILITY.md).

Entity checkpoint persistence now has a separate [crash contract](DESPAWN_CRASH_COMPATIBILITY.md). Retained mobs carry typed scalar/location variables and retention metadata in their entity PDC at unload and clean shutdown. Three real boots and two forced exits verify completed checkpoints, previous-checkpoint recovery, replacement values and terminal cleanup. This does not establish recovery during unfinished writes or real-time persistence after every variable change.
