# Typed summon mechanics

Baseline: Paper 1.21.1 / public MythicMobs 5.7.2, native JAR SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
The three mechanics have runtime, direct binding and importer implementations.
The ordinary managed-model `summon` action remains a different operation.

## Area effect clouds

`summonAreaEffectCloud/summonCloud` map to `summon-cloud`. An entity recipient
provides its current location; location selectors provide the spawn location
directly. The spawned object is a real Bukkit AreaEffectCloud.

Alias precedence runs left to right. A repeated exact key takes its last value.

| Field aliases | Default | Interpretation |
| --- | --- | --- |
| `particle/p` | reddust | Static native particle alias |
| `type/effect/t` | SLOW | Potion type string |
| `potionduration/pd` | 100 | Potion ticks |
| `level/lvl/l` | 1 | Amplifier directly; no subtraction |
| `duration/d/cloudduration` | 200 | Cloud ticks |
| `durationreudctiononuse/drou` | 0 | Signed duration change; long key has native typo |
| `radius/r` | 2 | Integer radius |
| `radiusreductiononuse/rrou` | 0 | Signed float radius change per use |
| `radiusreductionontick/rrot` | 0 | Signed float radius change per tick |

The reduction fields are forwarded without sign inversion. The correctly
spelled `durationreductiononuse` is not a native alias. Paper's wait and
reapplication defaults are both 20 ticks. The mechanic does not assign a
source/owner or install its own removal timer.

Execution spawns first, applies particle data, constructs and adds the potion,
then sets duration, duration-on-use, radius, radius-on-use and radius-per-tick.
Invalid potion/data input can leave a partly configured cloud. That entity is
not silently rolled back. Errors are isolated per recipient. Native entity
casts can return SUCCESS even when the forwarded location cast returns
INVALID_CONFIG; runtime dispatch success is recorded separately from mutation.

Particle data supports native item material, block material, color, dust color
and size, transition colors and size, and SHRIEK's zero delay. Colors accept
hex RGB/RGBA, integer RGB, comma RGB/alpha and Bukkit firework dye colors; byte
wrapping follows the native color container. ENTITY_EFFECT color is subsequently
recomputed by Paper when the custom potion is added. Paper's legacy particle
name conversion is explicit in the alias table: `dust` resolves to BLOCK,
while `reddust/redstone` resolve to DUST. Unknown names fall back to CLOUD;
removed known BARRIER/LIGHT names reject initialization. SCULK_CHARGE retains
the pinned native Double/Float data mismatch and its partial-cloud outcome.
VIBRATION has no native data builder in this mechanic.

Numeric placeholders use the incoming trigger as the default explicit entity.
The native public parser gates dynamic/quoted numbers behind Premium and
falls back to one. Helstera resolves these numbers itself; these are explicit
extensions. Supported native static ranges retain their native grammar;
a negative upper float range does not become an accepted native range.
Non-finite float results are rejected before passing them to Paper.

## Falling blocks

`summonFallingBlock` maps to `summon-falling-block` and uses the selected entity
location or an explicit location. `types/type/t/material/mat/m` defaults to
DIRT. Static block strings are lowercased and parsed by Bukkit, including
namespaces and properties such as `oak_log[axis=x]`.

The native constructor initializes its static block after reload. Its dynamic
block field never initializes; Helstera likewise leaves dynamic block requests
inert. Invalid materials never silently become DIRT. AIR spawns an air-valued
falling entity that the server removes on its first tick, without a drop. External MMOItems
and Crucible block resolution needs its separate installed adapter environment.

The action calls the real `spawnFallingBlock` API and retains its gravity,
velocity, item-drop and damage defaults. Landing, block placement, obstruction,
item drops and removal follow the server lifecycle.

## Passengers

`summonPassenger` maps to `summon-passenger`. `passenger/p/rider/r/type/t`
selects a custom mob ID; `stack/s` defaults to false. The ID is static, and a
missing mob is retried on later invocations. The importer resolves the chosen
ID into the existing `mythic_` namespace. Direct binding accepts an actual
local mob ID or resolves the imported native name. A visual-only model ID is
not a custom mob spawn.

The action executes once on the caster, including with inherited recipients or
an explicit targeter. It uses the caster's location and the production mob
pipeline, applying options, AI, level and spawn callbacks before mounting.
The current runtime stores integer mob levels. Levels 1 and 4 are exercised;
fractional native levels remain outside this implementation's level boundary.

Without stacking, native `Entity.setPassenger` replaces the caster's current
passenger. With stacking, the first passenger is selected, the caster calls
`setPassenger(existing)` again, then the existing passenger calls
`setPassenger(new)`. The code does not walk to the top of a passenger chain.
On pinned Paper the reboarding cooldown can leave the original first passenger
detached from the caster, while the new child rides it. Preserve that observed
API behavior; do not describe the option as unconditional tower extension.

Owner metadata is written after mounting, even when a mount event cancels the
attempt. No parent relationship is added. Native Wolf ownership also updates
its vanilla player owner; Cat and Parrot ownership is not implicitly changed.
Spawn and mount event observers therefore see the pre-owner state. The
production spawn event is Helstera's event; native SUMMON reason observations
refer to Mythic's separate spawn event API.

The existing summon limit and execution audience hooks apply. A depth limit of
32 also bounds synchronous spawn-callback recursion before children can be
registered with the ordinary summon gate. Hooks are cleared on shutdown.

## Evidence and remaining acceptance

Offline tests cover argument precedence, particle conversion/data, color
formats, entity/location binding, invalid blocks, passenger replacement order,
owner timing, cancellation return behavior, missing mobs, recursion, importer
mapping and rejected syntax. Full Maven verification passes 2,953 tests and
11 clean SpotBugs module checks for the verified build.

The Paper fixture records native/direct/imported entity state, spawn and mount
events, real tick progression and lifecycle outcomes. It initializes both native
second-pass and post-reload queues. An online protocol observer keeps the test
chunks ticking; that alone is not client rendering or packet parity evidence.
The [2026-11-26 r5 report](../tools/paper-integration/reports/paper-1.21.1-special-summon-20261126-r5.json)
passes all 129 observations (43 scenarios across the three paths), and the
independent audit passes with current source/JAR hashes checked. Plugin SHA-256:
`7ab88527145c36ae5fd1a85829951a9f3d7952ece186a885374b11dacf3bfc3d`.
The independent auditor rejects 8,292 rehashed semantic/evidence mutations.
The evidence index records earlier rejected runs and the r4 audit correction.

Still required: full native dispatcher scheduling; fractional levels; broader
selectors and nonliving conditions; randomized/non-degenerate numeric ranges;
additional particle data edge cases; player-caster taming; natural damage and
all drop cases; cancelled spawn/reentrant callback cleanup; reload, disconnect,
world unload and shutdown acceptance; client spawn/metadata/mount/removal
packets and human rendering; external custom blocks; and the strict release
gate on the final artifact. Earlier scoped reports do not certify a later JAR.
