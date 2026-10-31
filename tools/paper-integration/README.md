# Isolated Paper Integration Server

This fixture starts a disposable Paper 1.21.1 server in the repository root at
`srv-paper-integration` (or the directory named by `PAPER_INTEGRATION_DIR`).
Each run resets only that fixture's plugin data and
enables the delayed model self-test; it does not use or modify existing
`srv-*` worlds.

Build the full plugin first, then run:

```powershell
.\mvnw.cmd -o -q -pl helstera-plugin -am -DskipTests package
npm ci --prefix tools/paper-integration
$env:PAPER_MENU_WORKFLOW = 'true'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 75
```

For the strict release gate, keep the two-player menu workflow enabled and use
the release assertion:

```powershell
$env:PAPER_MENU_WORKFLOW = 'true'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 150 -Strict
```

The latest Paper 1.21.1-133 strict fixture completes 250 observed probes: 239
positive observations and 11 expected negative-control observations. The
current release evidence is `srv-paper-targeter-limits-20261031-r1`; earlier
fixture counts in this document are historical checkpoints.

The follow-up fixture also verifies the root Mythic `Exp` path through
`exp-options result=true experience=13 event=true`.

The AI selector probe also dispatches the production `set-target` and
`clear-target` mechanics and records both target mutation and cleanup on the
managed Bukkit Mob.

It also records the complete managed vehicle lifecycle probe:
`vehicle-events enter=true exit=true dismount=true eject=true destroy=true`.

The strict fixture also verifies `mobs/despawn_probe.yml` through
`despawn-options result=true mode=PERSISTENT distance=0.0 persistent=true`.

It also verifies the visibility option fixture through
`visibility-options result=true visible-by-default=true invisible=true show-name=true`.

The Spawner catalog also verifies group-limit enforcement through
`spawner-group id=group_probe requested=true alive=2 total=2 max-alive=2 group-min=3 group-max=5 limit=true`.

The natural scheduler path is separately covered by
`spawner-natural-group id=natural_group_probe mode=live-scheduler before=2 after=4 alive=2 max-alive=2 group-min=3 group-max=5 limit=true`.

Natural instance recycling is covered by
`spawner-recycle id=recycle_probe before=2 after=3 released=true respawned=true alive=1 interval=40`.

Spawner counter persistence across a real plugin restart is verified separately:

```powershell
powershell -ExecutionPolicy Bypass -File tools/paper-integration/test-spawner-restart.ps1
```

The two boots share one plugin data directory. The first boot generates
`persistence_probe` and lets the normal plugin shutdown write
`spawner-state.yml`; the second boot verifies `before=1 after=1` after loading
the state, proving the lifetime counter is restored without duplicate loading.

Named region gating is covered by
`spawner-region id=region_probe requested=true region=integration_box failure=null`.

The default server jar path is `srv-1.21.1/paper.jar`. If the Paper jar is
stored elsewhere, set an explicit override before running the fixture:

```powershell
$env:PAPER_JAR_PATH = 'D:\paper\paper-1.21.1-133.jar'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 75
```

The Paper downloads API may retire old 1.21.1 builds; in that case obtain a
compatible Paper 1.21.1 jar through the project's approved artifact storage and
use `PAPER_JAR_PATH`. The integration script refuses to substitute another
Minecraft version silently.

For the archived official build used by the current evidence, fetch and verify
Paper 1.21.1-133 with:

```powershell
.\mvnw.cmd -o -q -pl helstera-plugin -am -DskipTests package
powershell -ExecutionPolicy Bypass -File tools/paper-integration/fetch-paper-1.21.1.ps1
```

The helper verifies `version.json` and SHA-256 before writing
`srv-1.21.1/paper.jar`; pass `-Force` only to replace an existing fixture.

If the Paper distribution keeps dependencies outside the server jar, also set
the directory containing its matching libraries:

```powershell
$env:PAPER_LIBRARIES_PATH = 'D:\paper\libraries'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 75
```

That directory must be version-isolated. The runner rejects a directory that
contains more than one `paper-api-*.jar`; do not point it at a shared cache
containing 1.21.4 and 1.21.11 libraries together, because classpath order can
produce misleading Codec/API linkage failures.

The script launches `org.bukkit.craftbukkit.Main` with the supplied jar and
library classpath; it does not mix dependencies from a different Minecraft
version.

When Mineflayer is installed, the fixture connects a disposable offline client,
opens the configured two-page menu, drops operator permission before clicking a
restricted button, pages forward, executes an allowed command, and verifies the
inventory closes, and observes the plugin's message/title/actionbar Audience
delivery. The structured report includes
`custom-menu-player-workflow` probe only when the client emits its assertion
records plus `audience-player-delivery`; otherwise each scenario remains
unobserved.

For exploratory runs, the runner derives `PAPER_MINEFLAYER_VERSION` from the
Paper jar manifest or `version.json` and passes it to all disposable clients.
Set the variable explicitly only when testing a protocol variant; the default
remains `1.21.1` when neither metadata source is parseable.

For AI probes that only need an online player, use the lighter client workflow:

```powershell
$env:PAPER_PLAYER_WORKFLOW = 'true'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 45
```

This keeps `HelsteraTester` online without opening a menu or asserting menu
behavior.

For a real potion-consumption probe without Mineflayer's potion-component
decoder, use the opt-in raw protocol client:

```powershell
$env:PAPER_PLAYER_WORKFLOW = 'true'
$env:PAPER_RAW_POTION_WORKFLOW = 'true'
$env:PAPER_SKIP_PLAYER_POTION = 'false'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 48 -Exploratory
```

This path is exploratory compatibility evidence and must not be promoted to
the Paper 1.21.1 strict release gate.

The event self-test also runs the server-owned `ai-selector-core` probe. It
does not require Mineflayer: it exercises player/monster target selection,
`clear` no-fallback behavior, owner/faction/conditional resolution, and Paper
native target synchronization, then logs one structured `passed=true`
observation. Exploratory Paper evidence is retained separately from the
Paper 1.21.1 release gate.

The skill self-test also runs `dotMechanic`: it applies a bounded DoT to a real
non-burning Sheep, checks damage after multiple intervals, and checks that
health remains unchanged after the configured duration. The probe is
exploratory evidence on non-1.21.1 servers and is not a strict release claim.

It also records `conditionUtilityAliases` for the runtime-backed Mythic
condition aliases `incombat`, radius counts, `mobsinradius`, `targetcount`,
`Moist`/`MoistureLevel`, and deterministic `randomchance` checks. The skill
self-test temporarily sets a real block to hydrated and dry `Farmland`, then
restores its original BlockState. It also sets a real Slime to sizes 4 and 2
to verify `MobSize` range evaluation before cleanup.

It also places a temporary STONE probe block and verifies `BlockTypeInRadius`
positive/negative material counts before restoring the original BlockState.

The same owner probe also requires `owner` to match the explicit online player
target and rejects the ordinary non-owner target path.

The parent-relation probe also requires `children`, `children >=1`, and rejects
`children >=2` for one loaded child.

The spatial condition probe also temporarily places a STONE head block for
`inside`, removes it for `outside`, and restores the original block state.

The damage trigger probe also requires `damage-amount >= 2` and reports the
real `EntityDamageEvent#getFinalDamage()` path alongside `damage-cause`.

The motion probe also evaluates named `DirectionalVelocity` x/y/z ranges on the
same temporary target velocity.

It also checks `FieldOfView` for a forward target, rejects a side target, then
rotates the view cone and verifies the rotated target before restoring locations.

The spatial probe also evaluates `DistanceFromLocation` at the exact target
coordinates and rejects a point ten blocks away under a five-block range.

The online-player condition probe temporarily sets level 7 and experience
progress 0.25 for `EnchantingLevel`/`EnchantingExperience`, then restores both
values.

The condition catalog probe also checks `plugin helsteraMobs` positively and
`plugin __missing_plugin__` negatively against Bukkit PluginManager state.

It also reads the real spawned entity's Bukkit `getEntitySpawnReason()`, checks
the matching `SpawnReason` through named-argument aliases, and rejects a
different enum value. Exploratory Paper 1.21.11 r98 records
`spawn-reason-condition=true`; this does not qualify as Paper 1.21.1 strict
release evidence.

The same catalog probe checks `IsWaterMob` on a real Cod and `IsZombie` on a
real Zombie, with cross-type rejection for each. Exploratory Paper 1.21.11 r99
records `water-mob-condition=true zombie-condition=true`; strict Paper 1.21.1
release evidence remains separate.

The condition catalog also checks official `Aerial`: a real floating ArmorStand
passes while the already connected player's grounded state rejects the same
condition. Exploratory Paper 1.21.11 r102 records `aerial-condition=true`;
strict Paper 1.21.1 release evidence remains separate.

The catalog also checks official `IsEnderman` on a real Enderman and rejects
the same condition for the managed Zombie. Exploratory Paper 1.21.11 r103
records `enderman-condition=true`; strict Paper 1.21.1 release evidence
remains separate.

The catalog also checks official `HasEnchantment` against a temporary Sharpness
III diamond sword on the real managed Zombie. Sharpness `>2` and `ANY=3` pass;
Sharpness `>3` and Looting reject. Exploratory Paper 1.21.11 r104 records
`enchantment-condition=true`; strict Paper 1.21.1 release evidence remains
separate.

The catalog also checks official `Color` on a real red Sheep: RED passes,
BLUE rejects, and the Zombie type rejects the color condition. Exploratory
Paper 1.21.11 r108 records `color-condition=true`; strict Paper 1.21.1 release
evidence remains separate.

The condition catalog also checks official `MaterialIsOnCooldown` on the real
online player: Ender Pearl cooldown passes, Diamond rejects, and clearing the
cooldown rejects. Exploratory Paper 1.21.11 r110 records
`material-cooldown-condition=true` with both aggregate condition probes
passing; strict Paper 1.21.1 release evidence remains separate.

The condition catalog also checks official `TriggerItemType` using a real
dropped Bukkit Item as `BehaviorContext.trigger()`: STONE/DIRT matches and
DIAMOND rejects. Exploratory Paper 1.21.11 r113 records
`trigger-item-type-condition=true`; strict Paper 1.21.1 release evidence
remains separate.

It also checks `TriggerBlockType` against a real STONE origin in the
`on-block-break` state: STONE/DIRT matches, DIAMOND rejects, and a non-block
trigger state rejects. The same r113 exploratory run records
`trigger-block-type-condition=true`.

The catalog also checks official `XDiff`/`YDiff`/`ZDiff` against the real
managed caster and target locations. Positive coordinate ranges pass and an
out-of-range X difference rejects. Exploratory Paper 1.21.11 r114 records
`coordinate-difference-condition=true`; strict Paper 1.21.1 release evidence
remains separate.

The catalog also checks official `OriginLocation` against a real execution
origin: block-coordinate and exact-coordinate matches pass, while a distant
origin rejects. Exploratory Paper 1.21.11 r116 records
`origin-location-condition=true`; strict Paper 1.21.1 release evidence
remains separate.

The condition catalog also checks `PlayerKills` on a managed killer. A real
online player is killed through a deterministic Bukkit health-zero path,
`on-kill-player` observes the action, `player-kills >=1` passes, and
`player-kills >1` rejects. Exploratory Paper 1.21.4 killprobe records
`playerKillTrigger=passed` and `playerKillsCondition=passed`; strict Paper 1.21.1
evidence remains pending.

The condition catalog also checks official `Absorption` by temporarily setting
the managed Zombie's Bukkit absorption amount to 4 through `SetAbsorption`:
`>=4` and `=4` pass while `>4` rejects, then the original value is restored.
Exploratory fixture r8 records `absorption-condition=true`; strict Paper 1.21.1
evidence remains pending.

The same catalog checks `HasPotionEffect` against a temporary real-player
SPEED effect: type `SPEED`, amplifier `0`, and duration `1-20` must match.
The structured log records `potion-effect-condition=true`; a fresh Paper
fixture r8 records the independent `potionEffectCondition` probe passed; strict
Paper 1.21.1 evidence remains pending.

The same temporary SPEED effect is checked through `HasAuraStacks`; Bukkit
amplifier 0 must satisfy the one-stack range and the structured log records
`aura-stacks-condition=true`. Exploratory fixture r8 records the independent
`auraStacksCondition` probe passed; strict Paper 1.21.1 evidence remains
pending.

The managed model's existing level path is also checked by the condition
catalog: `level >=1` passes and `level <1` rejects, recorded as
`level-condition=true`. Exploratory fixture r8 records the independent
`levelCondition` probe passed; strict Paper 1.21.1 evidence remains pending.

The condition catalog also sets temporary absorption on the managed Zombie and
verifies raw `Health` and percentage `HealthPercent` only pass when
`includeabsorption=true`; the structured log records
`health-absorption-condition=true` in exploratory fixture r8. Strict Paper
1.21.1 evidence remains pending.

The condition catalog reads the current world full-time lunar phase, checks the
matching numeric phase, and rejects the next phase; the structured log records
`lunar-phase-condition=true`. Exploratory fixture r8 records the independent
`lunarPhaseCondition` probe passed; strict Paper 1.21.1 evidence remains
pending.

The existing signal self-test now also records `LastSignal`: after delivering
the scoped signal, the managed instance's `last-signal` condition must match
that signal name. Exploratory fixture r3 records the independent
`signalMechanics` probe passed; strict Paper 1.21.1 evidence remains pending.

The online-player self-test also creates real Bukkit scoreboard objectives and
checks `GlobalScore`, `Score`, and their missing-score negative paths. Exploratory
fixture r3 records `globalScoreCondition=passed`, `scoreMechanics=passed`, and
`scoreCondition=passed`; strict Paper 1.21.1 evidence remains pending.

The condition catalog also ray-traces from the execution origin to the real
target eye location and records `line-of-sight-from-origin-condition=true`.
A fresh Paper fixture run is still pending.

The trigger self-test now constructs an `EntityShootBowEvent` with force 0.75
and evaluates `bow-tension >0.5` through the event-aware context. Exploratory
fixture r3 records the independent `bowTensionCondition` probe passed; strict
Paper 1.21.1 evidence remains pending.

The condition catalog also checks literal `string-equals yes yes` and rejects
`string-equals yes no`; the structured log records
`string-equals-condition=true`. Exploratory fixture r8 records the independent
`stringEqualsCondition` probe passed; strict Paper 1.21.1 evidence remains
pending.

The condition catalog also checks an empty literal and rejects the non-empty
counterpart through `StringEmpty`/`StringNotEmpty`; structured output records
`string-empty-condition=true`. Exploratory fixture r8 records the independent
`stringEmptyCondition` probe passed; strict Paper 1.21.1 evidence remains
pending.

The existing real dropped STONE Item probe now checks both
`EntityItemType` and `EntityMaterialType`, including a DIAMOND negative path;
exploratory fixture r2 records the independent `entityItemTypeCondition` probe
passed; strict Paper 1.21.1 evidence remains pending.

Exploratory fixture r126 isolates the online player before the AI priority and
state checks. It records `aiPriority=passed`, `aiPriorityTargets=passed`, and
`stateConditions=passed`; strict Paper 1.21.1-133 fixture
`srv-paper-integration-exp-20261031-r27` now records `stateBlocking=passed`
from a real Mineflayer Shield-use window.

Exploratory fixture r127 extends that isolation through the scheduled event
workflow and records `itemPickup=passed` and `environmentalDamage=passed`.
The environmental probe uses a dedicated no-attacker `on-damage` profile, so
its result is independent of the ENTITY_ATTACK trigger fixture.

The condition catalog also toggles the managed Zombie's Paper freeze state and
checks `is-frozen` before and after thawing; the structured log records
`frozen-condition=true`. Exploratory Paper 1.21.4 fixture
`srv-paper-integration-exploratory-1.21.4-r123` records the independent
`frozenCondition=passed` probe; strict Paper 1.21.1 evidence remains pending.

The same dropped STONE Item probe also compares resolved `STONE` and `DIAMOND`
templates through `EntityItemIsSimilar`; exploratory fixture r2 records the
independent `entityItemSimilarCondition` probe passed; strict Paper 1.21.1
evidence remains pending.

The live-player condition catalog also checks `ItemIsSimilar` against the
temporary main-hand `SHIELD` and rejects an invalid numeric slot, while
`Targets` checks the inherited target list and `@targets` selection. The
structured catalog records `item-similar-condition=true` and
`targets-condition=true`; exploratory fixture r2 records independent
`itemSimilarCondition=passed` and `targetsCondition=passed`; strict Paper 1.21.1
evidence remains pending.

The same real target now exercises inclusive `Cuboid` and `InBounds` sphere
paths plus an outside negative control; the structured catalog records
`bounds-condition=true`. Exploratory Paper 1.21.4 fixture
`srv-paper-integration-exploratory-1.21.4-r121` observes this probe; strict
Paper 1.21.1 release evidence remains pending.

The online-player workflow also exercises `CloseInventory`, experience
progress clearing, experience-level clearing, and level granting. Exploratory
fixture `srv-paper-integration-exploratory-1.21.4-r122` records the independent
`playerExperienceMechanics=passed` probe; strict Paper 1.21.1 release evidence
remains pending.

Exploratory fixture r136 additionally runs `GiveExperience 25`, observes the
real player's raw experience increase, and restores the saved experience state.
The report records `rawExperienceMechanic=passed`; strict Paper 1.21.1 evidence
remains pending.

Exploratory fixture r8 also runs `SetAbsorption 6` on the managed Zombie and
online player, checks the real Bukkit absorption amount, and restores the
temporary values. The mechanic raises Paper's `MAX_ABSORPTION` capacity before
writing the amount, avoiding Paper 1.21.4's default zero-capacity clamp. The
report records `setAbsorptionMechanic=passed`; strict Paper 1.21.1 evidence
remains pending.

The vehicle workflow also creates an independent managed Boat caster with an
ArmorStand passenger and runs `EjectPassenger`; exploratory r124 records the
independent `ejectPassenger=passed` probe. Strict Paper 1.21.1 evidence remains
pending.

The player workflow also runs `Oxygen 20` against a real online player with
100 remaining-air ticks and restores the original value; the structured report
records `oxygenMechanic=passed` in exploratory fixture r125; strict Paper 1.21.1
release evidence remains pending.

The same player workflow runs `ConsumeSlot HAND 2` against a temporary three-item
STONE stack, verifies one item remains, and restores the inventory; r127 records
`consumeSlotMechanic=passed`. The runtime mutates the stack before writing it
back because Paper may copy ItemStacks during inventory updates.

The same online-player workflow now exercises `SetItemCooldown ENDER_PEARL 20`
and `SetGameMode ADVENTURE`, restoring the original cooldown and game mode
after each check. The report exposes these as independent
`materialCooldownMechanic` and `setGameModeMechanic` probes. Exploratory Paper
fixture r132 records both probes as `passed`; neither probe can promote
the strict Paper 1.21.1 release gate.

The same self-test now checks `Jump 0.42` on the managed caster and
`ForcePull 1.0` against a real selected target, restoring the target velocity
afterward. The report exposes `jumpMechanic` and `forcePullMechanic`; exploratory
fixture r128 records both as `passed`; strict Paper 1.21.1 evidence remains
pending.

Exploratory fixture r129 also runs `SetOffhand SHIELD 2` on the online player,
checks the real off-hand stack, and restores the original item. The report
records `setOffhandMechanic=passed`; strict Paper 1.21.1 evidence remains
pending.

Exploratory fixture r130 also runs `SetArmor` with four Diamond armor pieces,
checks the real managed Zombie's helmet/chest/legs/feet slots, and records
`setArmorMechanic=passed`. Strict Paper 1.21.1 evidence remains pending.

Exploratory fixture r131 also runs `ClearInventory` after temporarily placing
items in the online player's inventory, verifies the real inventory is empty,
and restores the saved contents. The report records
`clearInventoryMechanic=passed`; strict Paper 1.21.1 evidence remains pending.

Exploratory fixture r133 also runs `SetFaction guards` on the managed caster
and `SetFaction undead` through the selected target path, then restores the
original faction bindings. The report records `factionMechanic=passed`; strict
Paper 1.21.1 evidence remains pending.

Exploratory fixture r137 also runs `SetLevel 5` on the managed instance and
checks the existing `Level >=5` condition path. The report records
`levelMechanic=passed`; generation-time health scaling is not recomputed by the
runtime action, and strict Paper 1.21.1 evidence remains pending.

Exploratory fixture r137 also runs `SetLevel 5` on the managed instance and
checks the existing `Level >=5` condition path. The report records
`levelMechanic=passed`; generation-time health scaling is not recomputed by the
runtime action, and strict Paper 1.21.1 evidence remains pending.

Exploratory fixture r134 also runs `SetWeather thunder/clear` and
`SetTime noon`, verifies the real world state, and restores the original time
and weather. The report records `weatherMechanic=passed` and
`timeMechanic=passed`; strict Paper 1.21.1 evidence remains pending.

Exploratory fixture r135 also runs effect-only `Lightning` against the managed
target location and records `lightningMechanic=passed`; strict Paper 1.21.1
evidence remains pending.

`ItemType` shares the existing dropped-Item material path with
`TriggerItemType`; migration and unit coverage are present, and the structured
self-test/report contract now includes the independent `itemTypeCondition`
probe. Exploratory Paper 1.21.11 r123 records `itemTypeCondition=passed`;
strict Paper 1.21.1 evidence remains pending.

`ItemType` shares the existing dropped-Item material path with
`TriggerItemType`; migration and unit coverage are present, and the structured
self-test/report contract now includes the independent `itemTypeCondition`
probe. Exploratory Paper 1.21.11 r123 records `itemTypeCondition=passed`;
strict Paper 1.21.1 evidence remains pending.

The condition catalog also checks numeric and lexical `CompareValues` paths,
including an invalid typed integer negative path; structured output records
`compare-values-condition=true`. Exploratory fixture r8 records the independent
`compareValuesCondition` probe passed; strict Paper 1.21.1 evidence remains
pending.

The condition catalog checks the loaded `berserk` skill positively and an
unknown skill negatively through `IsSkill`; structured output records
`is-skill-condition=true`. Exploratory fixture r8 records the independent
`isSkillCondition` probe passed; strict Paper 1.21.1 evidence remains pending.

The managed model context now writes a string and list variable through the
real SkillService, then checks prefix, suffix, `ALL`, and `ANY` containment.
Exploratory fixture r3 records `variableContainsCondition=passed`; strict Paper
1.21.1 evidence remains pending.

The online-player self-test now controls a real Player's server-side rotation,
places a temporary STONE block along the eye ray, and checks positive/negative
`LookingAt` paths before restoring both block and rotation. Exploratory fixture
r2 records `lookingAtCondition=passed`; strict Paper 1.21.1 evidence remains
pending.

The same online-player self-test uses Bukkit `LivingEntity#attack` against an
temporary Zombie, verifies `HumanEntity#getAttackCooldown()` falls below `1.0`,
and checks the `ItemRecharging` condition. Exploratory fixture r3 records
`itemRechargingCondition=passed` after a real Bukkit attack; strict Paper 1.21.1
evidence remains pending.

The same managed Zombie context independently checks `PlayerKills` and
`MythicMobType`; exploratory fixture r2 records `playerKillsCondition=passed`
and `mythicMobTypeCondition=passed`. Strict Paper 1.21.1 evidence remains
pending.

`Structure` is implemented against Paper's structure registry and
`World#hasStructureAt`. The isolated fixture enables structure generation and
the self-test searches for a real generated structure before checking exact,
wildcard, and missing-key paths; `structureCondition` is independently
reported when a generated structure is observed. Exploratory Paper 1.21.11
r123 records `structureCondition=passed` against a real Mineshaft after using
its generated piece bounding box. Strict Paper 1.21.1 evidence remains pending.

`ProjectileHasEnded` is implemented through the existing projectile-hit path
and unit-tested. Exploratory Paper 1.21.4 fixture r3 launches a real Bukkit
Arrow into a temporary block, observes `ProjectileHitEvent`, and records the
independent `projectileHasEndedCondition` probe passed; strict Paper 1.21.1
evidence remains pending.

The catalog also checks official `HasDialogOpen` through a temporary loaded
cinematic: starting it makes the condition pass, stopping it makes the
condition reject. Exploratory Paper 1.21.11 r106 records
`dialogue-condition=true`; strict Paper 1.21.1 release evidence remains
separate.

The same probe checks official `IsInCinematic` against the connected player
target: it passes while the temporary cinematic is active and rejects after
stop. Exploratory Paper 1.21.11 r107 records the combined dialogue condition
probe as passed; strict Paper 1.21.1 release evidence remains separate.

Target water/lava checks use a dedicated delayed probe: Paper updates an
entity's cached fluid state on the tick after block placement, so the probe
places water, evaluates on the next tick, switches to lava, evaluates again,
and restores both blocks. Same-tick checks remain diagnostic only.

The same event self-test runs `mob-options-natural`, which applies the runtime
`creeper-fuse-ticks` option, ignites a real Creeper, runs protected/control Bees
through attack ticks, and runs protected/control Zombie conversion. It also
emits a separate `mob-options-mount-event` probe for protected/control jockey
mount event cancellation; that probe uses the Bukkit mount event path, not a
claim of natural AI jockey selection. Exploratory r55 observes all four
server-side probes as passed; timeout or missing behavior remains
`not-observed`.

The server binds to `127.0.0.1:25731` with RCON disabled. The server runs
offline mode and must only be used locally. Console and Paper logs stay under
the isolated server directory. The script sends `stop` after `RunSeconds` and
cleans up its child process before returning. It then writes
`integration-report.json`; missing probes are `not-observed`, and rejected
Spawner probes are recorded as `observed-rejected`, not as passing.
Exploratory runs can retain partial evidence. Use `-Strict` for a release-gate
run; it exits non-zero if any registered probe is `not-observed` or
`insufficient-sample` and prints the exact incomplete probe IDs:

```powershell
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 `
  -RunSeconds 75 -Strict
```

Strict mode requires Python with `jsonschema` 4.x in addition to the workflows
needed by the registered probes (for
example, set `PAPER_MENU_WORKFLOW=true` and install the fixture's Mineflayer
dependencies for player/menu/Audience evidence). It also rejects reports that
do not pass the JSON Schema, prove plugin enablement, or prove Paper
implementation/API version 1.21.1, or include every registered probe listed in the
report's expected probe catalog. It also rejects duplicate or unregistered
probe IDs, so one log line cannot silently satisfy two catalog entries. Before startup it also requires the server jar
manifest to contain `Implementation-Version` for the same version check. A green Maven report-contract test validates
parsing and these version boundaries; it does not substitute for this live
Paper run.
Reports carry an `evidenceMode` field; strict validation accepts only
`release`, while exploratory reports are explicitly rejected by the release
gate.
The report parser can also be rerun against an existing fixture log:

```powershell
powershell -ExecutionPolicy Bypass -File tools/paper-integration/write-report.ps1 `
  -ServerDirectory srv-paper-integration-random100
```

Random distribution samples require at least 100 accepted spawns. The report
preserves expected and observed weights, per-entry deviation, maximum absolute
deviation, configured tolerance, and accepted sample count. Samples within the
tolerance are `passed`; samples outside it are `failed`; under-sampled runs are
`insufficient-sample`. The default maximum absolute deviation is `0.15` and can
be changed per run:

```powershell
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 `
  -RunSeconds 12 -RandomSpawnTolerance 0.10
```

The strict release gate rejects `failed` and `insufficient-sample` random
distribution results in addition to unobserved probes.

If a different Paper version is available for exploratory compatibility work,
it may be used only with the explicit `-Exploratory` switch and a separate
fixture directory. This mode refuses `-Strict` and its report must not be used
as Paper 1.21.1 release evidence:

```powershell
$env:PAPER_JAR_PATH = 'D:\paper\paper-1.21.4.jar'
$env:PAPER_INTEGRATION_DIR = 'srv-paper-integration-exploratory-1.21.4'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 `
  -RunSeconds 60 -Exploratory
```

The default and strict paths continue to require Paper 1.21.1.
Validate the report schema and status mapping with the synthetic test log:

```powershell
powershell -ExecutionPolicy Bypass -File tools/paper-integration/test-report.ps1
```

The JSON contract is `integration-report.schema.json` (schema version 1).
The report test uses Python `jsonschema` Draft 2020-12 validation in addition
to the PowerShell semantic assertions. On Windows it is also bound to Maven's
`verify` phase with `-Ppaper-integration`:

```powershell
.\mvnw.cmd -Ppaper-integration -pl helstera-plugin -am verify
```

That profile requires PowerShell and Python with `jsonschema` 4.x installed;
the default Maven lifecycle does not activate it.

The Maven profile selects `pwsh` on Unix-like systems and Windows PowerShell
(`powershell`) on Windows. Direct command examples below use Windows syntax;
on Linux/macOS, replace `powershell` with `pwsh`.

Passing startup proves only that the plugin loads on Paper. Feature evidence
must include a test setup, executed command/event, observed entity or inventory
state, and log output; it does not by itself upgrade parity-matrix statuses.

Persistent SkillTree restart verification is opt-in:

```powershell
powershell -ExecutionPolicy Bypass -File tools/paper-integration/test-skilltree-restart.ps1
```

It uses a dedicated `ARMOR_STAND` persistent mob, runs two Paper boots against
the same isolated world, and verifies the stable PDC mob ID and SkillTree marker
after the second boot. For a non-release Paper fixture, pass
`-Exploratory -PaperJarPath <path-to-paper.jar>` and set
`PAPER_LIBRARIES_PATH` to the matching Paper runtime libraries directory; the
run is evidence only and does not satisfy the strict Paper 1.21.1 gate.
