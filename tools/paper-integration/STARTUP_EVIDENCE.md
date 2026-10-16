# Paper Startup Evidence

Observed during an isolated run on 2026-10-14:

This file is a human-readable summary; the matching machine-readable run report
is `srv-paper-integration-report/integration-report.json`. A later dynamic
chance run report is under `srv-paper-integration-dynamic-chance`.

- Paper `1.21.1-133`, API `1.21.1-R0.1-SNAPSHOT`.
- Server bind: `127.0.0.1:25731`.
- World: `integration-world`.
- `helsteraMobs v1.0.0` enabled successfully.
- Model `example/emberling` loaded (`9` bones, `2` animations).
- `2` loot tables loaded.
- `3` spawners loaded.
- Resource pack build completed with `12` files.
- Self-test completed with `通过`: instance `#1`, model `example/emberling`,
  `9` bones, `12` Display entities, `实例有效=true`, animation `idle`.
- Paper entity-options self-test completed with `result=true` for Horse, Sheep,
  ArmorStand, Cat, and FallingBlock setters/read-back; all probe entities were
  removed before shutdown.
- Creeper, Bee, and Zombie state probes also completed with `result=true` for
  configured Creeper fuse/explosion/charged state, Bee nectar/stung state, and
  Zombie adult state.
- Item-effects self-test completed with `equipment=true`, `potion=true`, and
  `drop-item=true`; the Zombie and dropped Item entity were removed before
  shutdown.
- Event self-test creates Bukkit Creeper/Bee/Zombie entities, constructs the
  corresponding event objects, dispatches them through Paper's plugin event
  manager, and records cancellation for explosion, bee attack, and zombie
  transformation policy. This verifies listener policy on the event bus, not
  native game-event timing or a player's combat workflow.
- SkillService dispatch self-test completed with `equipment=true`,
  `potion=true`, and `drop-item=true` after fixing vanilla Material fallback
  when the custom-item resolver returns null.
- Loot self-test loaded `common` and logged `hits=1 materialized=1 entities=1
  amounts=true experience=0`; the generated ItemEntity was removed before
  shutdown.
- Death-loot self-test binds a model instance to a Zombie, sets a real loot
  table and fixed experience override, triggers entity death, and records the
  resulting item/experience entities before cleanup. Observed:
  `death-loot items=1 experience-orbs=1`.
- Spawner self-test invokes the loaded `SpawnerService.forceSpawn` path and
  records requested state, alive count, cooldown, direct spawn result, and
  structured failure reason. An initial `max-spawns: 1` run correctly reported
  `spawn limits reached`; after setting the integration fixture to unlimited
  spawns, the safe-override path reported `requested=true failure=null`.
  The alive count is sampled after cleanup/expiry and is not the success
  assertion.
- The fixture now includes a two-entry weighted `random-mobs` pool; the self-
  test requests 100 additional safe-location spawns and records expected and
  observed distributions plus total count. Initial samples exposed scheduler
  `max-alive` interference and a missing `emberling_boss` mob fixture; both
  were corrected. A 21-spawn run observed boss/emberling `0.5238/0.4762`; a
  100-spawn run observed `0.56/0.44` against expected 0.5/0.5, deviation
  `+/-0.06`.
- External adapters: `0/1` connected in the isolated minimal configuration.
- The fixture now also contains condition-pass and condition-fail spawners;
  their next run records accepted/rejected world and Y gates with failure
  reasons. Observed `condition_pass requested=true failure=null` and
  `condition_fail requested=false failure=conditions rejected location`.
- Spawner location/gate probes observed `natural_ground_pass requested=true`,
  `border_fail requested=false failure=no valid spawn location` (world-border
  radius clipped), and `player_gate_fail requested=false failure=player gate
  closed`.
- Composite checks now use origin-aware location conditions. On Paper,
  `condition_combo_pass` logged `requested=true failure=null`, while
  `condition_combo_fail` logged `requested=false failure=conditions rejected
  location`.
- The first composite probe exposed that `world-is` needed an origin-aware
  Skill context when no model instance exists. `selfLoc` now uses
  `BehaviorContext.origin()` before instance location, and the composite pass
  and rejection were both re-verified on Paper.
- Server was stopped cleanly after observation.

The event-policy rerun used the isolated fixture
`srv-paper-integration-events-850a0a4af7f9404e83f54ca4f79e4104`. Its structured
report contains exactly one `events` probe with status `passed`; JSON schema
validation also passed. The fixture process exited cleanly; other Java
processes visible on the machine belonged to unrelated tooling and were not
treated as evidence for this run.

The isolated fixture `srv-paper-integration-menu-16ecf8cbc3b24914a30778f0952c7858`
also ran a Mineflayer 1.21.1 client against Paper. The client opened page 1 as
an operator, was de-opped before clicking the restricted button, confirmed the
denied action did not run and the menu stayed open, navigated to page 2, ran the
allowed command, and observed the configured inventory close. The generated
report contains one `custom-menu-player-workflow` probe with status `passed` and
passed JSON schema validation. Mineflayer emitted a registry-data
`PartialReadError` warning during login but completed the login and inventory
workflow; this remains a client compatibility risk for future protocol versions.

The isolated fixture `srv-paper-integration-audience-particle-da0e0f2e5ef7431aae0e785f183a9018`
also contains one `audience-player-delivery` probe with status `passed`. A real
online player received message, title, actionbar, and FLAME particle Audience
actions; the server logged all four dispatches and the Mineflayer client
observed all four payload markers. A later dual-client fixture
`srv-paper-integration-audience-filters-843fff2b1551480390723aee7b802c2b`
also passed `audience-filter-delivery`: both clients received the large-radius
message, only the tester received the 0.1-radius message, and only the operator
tester received the permission-filtered message. Faction filters remain
unverified in that earlier fixture. A final fixture
`srv-paper-integration-audience-faction-a5078f4c8b5b4288a555798a1a23c18e`
passed the same radius and permission checks plus explicit faction checks:
both configured `guards` players received `faction:guards`, while neither
received `faction:undead`. The server diagnostic recorded two candidates and
two recipients for `guards`, and two candidates and zero recipients for
`undead`.

This proves startup, resource loading, the listed feature probes, and the
narrow built-in model/Display self-test. It does not prove full Paper parity for
natural entity event timing, particle rendering, or the remaining matrix rows.

The first SkillService item-effects probe also exposed a real resolver bug:
when the plugin-level custom-item resolver returned null for a vanilla material,
`equip`, `set-item`, and `drop-item` did not fall back to `Material`. The
fallback was fixed in source and rebuilt; the isolated Paper probes above were
run against the rebuilt plugin artifact.

The dedicated fixture `srv-paper-skilltree-restart-ead0ef7e7b1d4f0aa480d91c064cb774`
passed a two-boot restart workflow. Boot one created `skilltree_probe`, wrote a
stable PDC mob ID and `saved-on-first-boot` marker. Boot two restored the
persistent entity, loaded one SkillTree snapshot, and logged
`skilltree-restart stage=verify result=passed ... marker=saved-on-first-boot`.

The isolated fixture `srv-paper-integration-conditions-65d39e2d2a224dba854fb15c2ce19582`
passed the `conditionCatalog` probe on Paper 1.21.1. A real target Zombie was
set to 10/20 health at a 3-block context distance; `health{h=<15}`,
`health-percent{p=<=50}`, and `distance{d=<4}` all evaluated successfully.
The same report passed equipment, potion flag, drop-item, teleport coordinate,
and JSON schema probes.

The Paper self-test fixture also loads `items.yml` and equips
`integration_test_item` through the plugin custom resolver. The resulting
real ItemStack was `GOLD_INGOT` with ItemMeta, recorded as
`custom-item=true` in the integration report.

The isolated fixture `srv-paper-integration-caster-permission` connected a real
online player, attached `integration.probe`, and evaluated
`caster-has-permission` through a trigger context without a model instance;
the report recorded `caster-permission=true` and passed schema validation.
The latest rerun `srv-paper-integration-caster-permission-2` also passed with
the full conditions self-test, including target categories and composition.
Fixture `srv-paper-integration-target-categories-player` additionally recorded
`target-categories=true` for a real Zombie condition target (`monster` and
`living` categories).
Fixture `srv-paper-integration-target-categories-expanded` extends that probe
to real Bukkit entities: Wolf=`tameable`, Pillager=`raider`, Sheep=`animal`,
Villager=`villager`; the combined `targetCategories` probe passed and all
temporary entities were removed during cleanup.
Fixture `srv-paper-integration-target-health` evaluated target raw health
`<15` and target health percent `>=50` against a real Zombie at 10/20 health;
the `targetHealthComparators` report probe passed.

The isolated fixture `srv-paper-integration-spawner-max-c585c72436bc4bc59180bf761962654b`
passed `spawnerMaxSpawns`: `max-spawns=1` accepted the first spawn, rejected
the second, and reported `total=1`; the generated integration report passed
JSON schema validation.

The isolated fixture `srv-paper-integration-scoped-limits-3` passed
`spawnerScopedLimits`: a dedicated End-world probe with `max-per-world=1`
accepted the first request and rejected the second with
`failure=scoped spawn limits reached`; the generated report and schema
validation both passed.

The isolated fixture `srv-paper-integration-player-limits` passed
`spawnerPlayerLimits` with two real Mineflayer players online. The configured
`max-per-player=1` probe accepted the first request and rejected the second
for the same nearest player with `failure=scoped spawn limits reached`; the
generated report and schema validation both passed.

The isolated fixture `srv-paper-integration-cooldown` observed the live Paper
Spawner scheduler decrement `cooldown_probe` from `remaining-first=38` to
`remaining-second=37` across consecutive scheduler ticks, with no additional
spawn during the cooldown window. The generated report and schema validation
passed; exact natural spawn timing remains separate evidence.

The isolated fixture `srv-paper-integration-lunar` passed `spawnerLunar`:
the self-test temporarily set the real Paper world's full time to day 0,
submitted a spawner condition `lunar-phase-is=FULL`, observed
`requested=true phase=FULL failure=null`, and restored the original time.
The generated report and schema validation passed.

The isolated fixture `srv-paper-integration-tempt-native-target` passed the
AI `tempt` probe with a real online player: the live PIG controller selected
the player and Paper `Mob#getTarget()` returned the same player UUID
(`native-target=true`). The generated report and schema validation passed.

The Attribute self-test in the isolated Paper fixture
`srv-paper-integration-item-attributes-50ba050d42e7442db9a58584ae06270e` also verified the runtime path after
alias normalization: `ATTACK_DAMAGE` with `ADD`/`HAND` was applied twice,
Paper read back one stable modifier UUID with `ADD_SCALAR`, and the entity
attribute value changed from base `3.0` to `3.3`.
The same fixture passed `item-attribute-options` with one ItemMeta modifier.

The isolated fixture `srv-paper-integration-ai-priority` passed `aiPriority`:
with an online `HelsteraTester` and both probe PIGs at 50% health, the
`meleeattack`-before-`panic` configuration selected `ATTACK`, while
`panic`-before-`meleeattack` selected `FLEE`. The generated report records
`attack-first=true flee-first=true`. The same run's native Pathfinder clear
probe remained `not-observed` because Paper produced no reachable path.

The trigger probe in that fixture was later corrected to construct
`EntityDamageByEntityEvent` with Bukkit's `(damager, damagee, cause, damage)`
order. Fixture `srv-paper-integration-trigger-passed-dad263d48b3b476b8ec5f6b40e3dc88d`
then passed `skillTrigger`: the configured damage action and attacked action
both ran, with `fired-after=2`; the event was not cancelled and the target
entity remained valid.

The isolated fixture `srv-paper-integration-time-weather` passed the
`timeWeather` probe against the real Paper world: after temporarily setting
world time to `6000`, clear weather matched `day`, `noon`, and `>=6000`; after
switching to storm/thunder, `thunderstorm` matched. The original world time and
weather flags were restored in the self-test cleanup.

The isolated fixture `srv-paper-integration-world-biome` also passed the
`worldBiome` probe: the condition context pointed at the real spawned Zombie,
and dynamically generated `world-is <actual world>` plus
`biome-is <actual biome>` both evaluated true. The same run passed the
`timeWeather` probe and restored the modified world state before shutdown.
It also passed negative assertions for nonexistent world and biome names,
confirming that both conditions reject mismatches instead of defaulting true.

The isolated fixture `srv-paper-integration-events-final-5595adb29ae14e24b5b6485cc0507a56`
passed the corrected `events` probe on Paper 1.21.1. It verified
`creeper-explode-cancelled=true`, `bee-attack-cancelled=true`, and
`zombie-transform-cancelled=true`; the Bee event uses the Bee as the damager,
matching the `prevent-attack` option semantics.
The Creeper probe is bound specifically with `preventSuicide=true`; natural
fuse timing remains unverified.

The isolated fixture `srv-paper-integration-teleport-trigger` passed the
`skillTrigger` probe for the new `on-teleport` trigger. A real bound Zombie
received a Bukkit `EntityTeleportEvent`; the configured teleport action ran
(`teleport=true`, `teleport-before=false`) while the existing damage trigger
evidence remained valid.

The isolated fixture `srv-paper-integration-block-events-7` passed the real
player workflow for `on-block-break` and `on-block-place`. Mineflayer recorded
`PAPER_BLOCK_DIG` and `PAPER_BLOCK_PLACE`; Paper then dispatched both handlers
for the bound `HelsteraTester`, and the plugin probe recorded
`block-events break=true place=true`.

The isolated fixture `srv-paper-integration-item-pickup` additionally passed
the real player pickup workflow: the plugin dropped a Gold Nugget beside the
bound player, Mineflayer picked it up, and the trigger probe recorded
`item-pickup trigger=true`.
The follow-up fixture `srv-paper-integration-item-pickup-fixed` confirms the
same result after delaying item spawn by 10 ticks so the player model binding
has completed before `EntityPickupItemEvent` is delivered.

The isolated fixture `srv-paper-integration-vehicle-events` passed real Paper
vehicle interaction for the bound player: `Boat#addPassenger` and
`removePassenger` dispatched the configured enter and exit triggers, recording
`vehicle-events enter=true exit=true`.

The isolated fixture `srv-paper-integration-dismount` passed the runtime
`dismount` mechanic against a real Boat: the bound player entered, the skill
removed the passenger relation, and the probe recorded
`vehicle-events enter=true exit=true dismount=true`. Natural Boat destruction
remains unverified.

The isolated fixture `srv-paper-integration-dismount` additionally passed the
runtime `dismount` mechanic against a real Boat: the bound player entered,
the mechanic removed the passenger relation, and the probe recorded
`vehicle-events enter=true exit=true dismount=true`. Natural Boat destruction
remains unverified (`destroy=false`).

The fresh isolated fixture `srv-paper-integration-current-20261015` booted the
rebuilt full plugin on Paper `1.21.1-133` and produced a schema-valid report.
It reconfirmed the entity-option, condition, equipment, potion, drop-item,
teleport, loot/experience, spawner, attribute, and online Audience probes.
The real player workflow reached `VehicleDestroyEvent` and the listener
dispatched `on-vehicle-destroy`, but the configured skill marker remained
`destroy=false`; this is event-receipt evidence, not a passing mechanic-action
result. The same run recorded `environmental-damage trigger=false`, so natural
environmental damage remains unverified.

Fixture `srv-paper-integration-owner-20261015` verified the context targeter
against a real online player and a real Paper Wolf: the Wolf was tamed and
assigned to `HelsteraTester`, then `Targeters.select("@owner", ...)` returned
that player. The schema-valid report records
`owner-targeter=true`. The fixture's separate vehicle probe was not used as
evidence here because its delayed client workflow ended with `destroy=false`;
the earlier `srv-paper-integration-vehicle-fix-20261015` result remains the
authoritative vehicle-destroy evidence.

The same rebuilt fixture also dispatched a real `EntityDamageByEntityEvent`
through the plugin and recorded
`skill-trigger ... attacker-targeter=true ... bukkit-damage=true`. This
verifies `@attacker` returns the event damager in a real Paper event context;
the short rerun did not produce a new Audience or owner probe and does not
replace their dedicated evidence above.

Fixture `srv-paper-integration-threat-20261016` verified the threat targeter
on Paper with a real model-bound entity and two real Zombie targets. The
runtime controller received threat values `5.0` and `20.0`; `@threat` returned
the 20.0 target first and logged `skill-targeter threat-targeter=true`. The
report passed schema validation. This proves targeter ordering and provider
wiring, not the full natural combat threat accumulation lifecycle.

Follow-up fixture `srv-paper-integration-threat-damage-20261016` replaced the
manual threat injection with two real `EntityDamageByEntityEvent` instances
carrying damage values `5.0` and `20.0`. The AI Manager accumulated those
events and the probe logged
`skill-targeter threat-targeter=true source=damage-events`; its report also
passed schema validation. Faction/threat composition remains unverified.

Fixture `srv-paper-integration-environmental-event-20261015` added a real
Paper `LivingEntity#damage(1.0)` call without an attacker while the bound
entity was placed in a temporary lava block. It recorded
`environmental-damage trigger=true` and restored the lava block during cleanup.
This proves the no-attacker `EntityDamageEvent` dispatch path; it does not yet
prove that the server's autonomous lava tick reaches the same path for this
no-AI probe entity.

After fixing the self-test lifecycle so the bound player instance is not
despawned before the delayed Boat attack, fixture
`srv-paper-integration-vehicle-fix-20261015` recorded
`vehicle-events enter=true exit=true dismount=true destroy=true`. The report
was schema-valid and the server exited cleanly. This is real player-driven
Boat destruction evidence; natural environmental damage remains unverified.

Fixture `srv-paper-integration-target-event-fixed-20261016` removed the
duplicate `on-entity-target` YAML key from `tempt_probe.yml`. Its report now
reliably records `synthetic-entity-target=true synthetic-before=false`; the
additional real `Mob#setTarget` probe remains `entity-target-trigger=false`.
This confirms the fixture's synthetic listener path while preserving the
natural `EntityTargetEvent` gap.

Fixture `srv-paper-integration-spatial-targeters-20261016` verified spatial
selection against real Paper Zombie entities and a real model origin. The
probe recorded `sphere=true ring=true cone=true lineofsight=true line=true`. The
line-of-sight implementation now uses a collision-box-center tolerance before
the Paper block ray trace, avoiding rejection of visible ground targets due
only to their vertical center offset. The same run placed a temporary Stone
block between source and target and recorded `lineofsight-blocked=true`, then
restored the original block data.
The same probe recorded `sphere-boundary=true ring-boundary=true
cone-boundary=true` for targets outside the configured radius, ring, and cone
angle, respectively.
It also verified `line-named=true line-width-boundary=true
line-length-boundary=true` for the `@livinginline{length=...;width=...}`
parameters and their negative boundaries.

Fixture `srv-paper-integration-condition-states-20261016` verified real
online-player state conditions after binding the player to a model instance.
The self-test temporarily enabled flight, flying, and sneaking, then recorded
`state-conditions=true state-flying=true state-sneaking=true
state-gliding=true`; the original player flags were restored during cleanup.
The report passed schema validation.
The same real Zombie/model context also set `Entity#setInvulnerable(true)`
and recorded `invulnerable-condition=true`, then restored the flag.

`is-blocking` remains unverified because it requires a real shield-use input
workflow; it is not inferred from merely equipping a shield.
The follow-up shield workflow reached Paper but recorded `state-blocking=false`;
Mineflayer `activateItem()` did not produce a stable shield-use state, so this
remains an honest negative observation rather than a passing claim.
The final probe also ran in Survival mode with a preloaded shield and repeated
outgoing `use_item` packets; Paper still reported `state-blocking=false`.
The same run verified targeter control semantics with
`targeter-controls=true`: chained filtering, `count=1`, `limit=1`, mob
filtering, and farthest sorting. Negative controls also passed with
`targeter-limit-zero=true` and `targeter-negative-filter=true`.

Fixture `srv-paper-integration-threat-faction-20261016` extended the real
damage-event probe with a live faction table. It recorded
`skill-targeter threat-targeter=true source=damage-events faction-filters=true`:
same-faction targets retained threat ordering, while `hostile:guards` excluded
the configured guards targets. The report passed schema validation.

Fixture `srv-paper-integration-entity-states-20261016` ran the updated full
plugin with a real online Paper player and real Wolf, Sheep, Horse, and Creeper
entities. The self-test set tame, sheared, saddle, and powered state through
Paper APIs, evaluated `is-tamed`, `is-sheared`, `is-saddled`, and `is-powered`,
and recorded `entity-state-conditions=true`. Its structured report marked
`entityStateConditions`, `skillEquipment`, `skillPotion`, `skillDropItem`, and
`skillTeleport` as `passed`. The fixture was stopped and cleaned by the runner.

Fixture `srv-paper-integration-potion-trigger-20261016-r4` loaded a real
`on-potion-effect-end` trigger profile and attempted to add and remove a real
Potion Effect from the managed Zombie. Paper did not emit an observed trigger
event in this synchronous self-test: the diagnostic recorded
`potion-events=0`, `potion-effect-end=false`, and
`potion-effect-action=false`. This proves the current failure is before the
listener's managed-entity lookup, not a missing dispatch after an observed
callback. The probe remains explicitly unverified and is retained for a future
real-player or natural-expiry workflow.

Fixture `srv-paper-integration-player-potion-20261016-r2` restored the
Mineflayer dependency and connected a real online Paper player. The self-test
added and removed a Potion Effect on that player and recorded
`player-potion-events=true`; the report marked `playerPotionEvents` and the
existing caster-permission/state probes as `passed`. This is valid Paper API
event evidence, but it does not yet prove a player drinking a potion or a
natural expiry event.

The follow-up fixture `srv-paper-integration-potion-survival-20261016` showed
that the current Mineflayer protocol decoder cannot parse the Potion item
component reliably (`PartialReadError`); switching to Survival produced an
empty client inventory. That workaround was reverted so the standard player
workflow remains stable. Real client-side potion consumption therefore stays
unverified and is tracked as a client-tool compatibility gap.

The inventory diagnostic in `srv-paper-integration-potion-inventory-20261016`
recorded only `wheat:1`, `stone:8`, and `gold_nugget:1` on the client after the
server logged `Gave 1 [Potion of Swiftness]`. This isolates the remaining issue
to the Mineflayer/Paper item-component synchronization path.

Fixture `srv-paper-integration-native-target-20261016-r2` used the corrected
non-`no-ai` trigger probe. Paper selected the online player as the native mob
target (`native-target=true`), but the managed `on-entity-target` skill still
recorded `entity-target-trigger=false`; the natural Bukkit-to-skill dispatch
chain therefore remains unverified.

The follow-up fixture `srv-paper-integration-target-delayed-20261016` added a
two-tick observation window. It recorded `target-events=1`,
`target-find-misses=0`, and delayed `entity-target-trigger=true`, proving the
natural Paper target event reaches the managed skill after the scheduled
continuation runs.

Fixture `srv-paper-integration-target-reason-clean-20261016` repeated the
native-target probe with `PAPER_SKIP_PLAYER_POTION=true` to remove client item
component noise. It recorded delayed `entity-target-trigger=true`,
`target-events=1`, `target-find-misses=0`, `target-reason=CLOSEST_PLAYER`, and
`target-cancelled=false`.

Fixture `srv-paper-integration-path-stop-20261016` ran without a player client
and produced a valid Paper plugin boot. Its path probe recorded
`accepted=false`, `cleared=false`, and `stop-calls=1`. This proves the native
stop request is issued in the idle/clear branch, while a real accepted path
and post-clear state remain unverified.

Fixture `srv-paper-integration-path-native-compare-20261016` added an
unbound native Zombie on the same temporary flat plane. Both the native mob
and the model-bound probe returned `findPath=null`; this rules out the model
binding as the sole cause, while Paper path creation remains unverified.

The later diagnostic `srv-paper-integration-path-diagnostic-20261016` recorded
`has-ai=true`, `start-block=AIR`, `below-block=STONE`, and
`on-ground=false` for the native probe. The path query currently runs in the
same tick as entity creation, before physics settles the entity onto the test
floor; this explains the `findPath=null` result and identifies the next fix as
a delayed path query rather than another terrain change.

Fixture `srv-paper-integration-target-env-20261016` ran the real-player Paper
self-test with `PAPER_SKIP_PLAYER_POTION=true` and recorded
`target-environment=true`; the structured report marked
`targetEnvironmentalConditions` as `passed` for target fire and visibility
state without relying on Mineflayer Potion item parsing.

Fixture `srv-paper-integration-target-fluids-20261016` extended the same real
Zombie target probe with temporary water and lava block states. The structured
report marked `targetFluidConditions=passed` and recorded
`target-fluids=true`; original block data was restored during cleanup.

Fixture `srv-paper-integration-target-blocked-20261016` temporarily placed a
solid block at the target's head location, evaluated `target-is-blocked`, and
restored the original block state. The structured report marked
`targetBlockedCondition=passed` and recorded `target-blocked=true`.

Fixture `srv-paper-integration-path-delayed-20261016` delayed the managed
path probe by five ticks, but Paper still reported `on-ground=false`, rejected
all candidate paths, and left `pathfinderClear`/`pathfinderStopCalls` as
`not-observed`. The follow-up 20-tick run recorded a valid mob at `-35.5,71.0,32.5`
with `SAND` below it, but still `on-ground=false`, `found=false`,
`accepted=false`, and `passed=false`. This remains unresolved until a server run
demonstrates an accepted path and a cleared active path.

Fixture `srv-paper-integration-followup-20261016` ran Paper 1.21.1-133 with a
real online Mineflayer player and skipped the potion-item action because the
client cannot decode the 1.21.1 potion component. The report recorded 69 passed
probes and 10 `not-observed` probes. SkillService equipment, potion effect,
drop-item, teleport, and configured custom-item resolution passed, as did the
configured condition, state, targeter, block-event, pickup, delayed
entity-target, and Audience-dispatch probes. Player block break/place were
performed and observed. Vehicle enter/exit and dismount passed, but destroy
remained false. `state-blocking` and `on-potion-effect-end` remain unverified;
strict report validation rejected the run because those and eight other
registered probes were not observed.

Fixture `srv-paper-integration-menu-followup-20261016-r7` used two real online
Mineflayer clients. The structured report marked `custom-menu-player-workflow`,
`audience-player-delivery`, and `audience-filter-delivery` as passed. The tester
confirmed the denied permission button kept the menu open, page navigation
worked, the allowed action closed the inventory and ran its command, and both
clients observed message/title/actionbar/particle delivery plus radius,
permission, and faction inclusion/exclusion. This fixture also fixed the test
harness race by handshaking permission revoke/restore and making its runner
reject client failures or failed report statuses.

Fixture `srv-paper-integration-random-distribution-20261016-r2` ran Paper
1.21.1-133 with the dedicated `random_distribution_probe` pool. The self-test
accepted all 100 forced spawns and recorded expected weights 0.5/0.5, observed
frequencies 0.52/0.48, and deviations +0.02/-0.02. The report parser classified
the sample as `observed` with `sampleCount=100`; the integration report still
contains unrelated unobserved probes and does not pass the all-probe strict
gate.

Fixture `srv-paper-integration-random-tolerance-20261016` ran the current
plugin with `-RandomSpawnTolerance 0.15`. Paper accepted 100 spawns from the
dedicated pool; expected and observed distributions were both 0.50/0.50,
`maxDeviation=0`, and the structured probe status was `passed`. The report
stores both `maxDeviation` and `tolerance`; strict validation still rejected
the complete report because 44 unrelated probes were not observed.

Fixture `srv-paper-integration-condition-mobility-20261016-r2` ran the current
plugin on Paper 1.21.1-133 with a real online player. It created a Zombie target
and Pig passenger, verified `mounted` false before mounting and true after,
verified `has-passenger` before/after passenger attachment, and set non-zero
velocity for `moving`. The structured `conditionMobility` probe passed. The
fixture is focused evidence for these three conditions, not full catalog
coverage.

Fixture `srv-paper-integration-leashed-condition-20261016-r2` verified
`is-leashed` on a real Paper Wolf by temporarily attaching a leash to the
online player, checking the condition, and restoring the previous leash state.
The `conditionLeashed` and combined `conditionCatalog` report probes passed.

Fixture `srv-paper-integration-has-owner-20261016` verified `has-owner` against
a real tamed Wolf whose Bukkit owner was the online test player. The structured
`conditionHasOwner` and aggregate `conditionCatalog` probes passed. This is
Bukkit tameable-owner evidence only; Mythic's custom non-tameable `SetOwner`
metadata is not implemented by this adapter.

Fixture `srv-paper-integration-owner-relations-20261016` verified the custom
non-tameable MythicMob owner path on Paper 1.21.1-133. The self-test used
`set-owner` on the model-bound Zombie targeting the online player, confirmed
`has-owner`, confirmed `@Owner` selected that player, then ran `remove-owner`
and verified both the condition and targeter were cleared. The
`customOwnerRelation`, `conditionHasOwner`, and `conditionCatalog` probes
passed. The owner UUID is stored in the mob PDC; entity target resolution
requires the owner to be loaded. Parent relations remain outside this fixture.

Fixture `srv-paper-integration-parent-relations-20261016` verified the parent
relationship lifecycle on Paper 1.21.1-133 with a real online player: set the
model-bound mob's parent, pass `has-parent` and `is-parent`, resolve `@Parent`,
simulate player quit and confirm the session parent is cleared, then set and
explicitly remove it. `parentRelation` and aggregate `conditionCatalog` passed.
Player parents are session scoped; non-player parents use child PDC UUID
metadata and resolve only while loaded.

Fixture `srv-paper-integration-is-child-20261016` additionally created a real
Pig child with the model-bound Wolf as parent and evaluated `is-child` against
that Pig target. `conditionIsChild`, `parentRelation`, and aggregate
`conditionCatalog` all passed; the Pig and relation were cleaned up.

Fixture `srv-paper-integration-parent-derived-20261016` checked the derived
parent conditions on Paper 1.21.1: `is-sibling` passed for two children sharing
the live Wolf parent; `is-parent-alive` passed before removal and failed after
the parent entity was removed. `parentDerivedConditions`, `parentRelation`,
and aggregate `conditionCatalog` passed, and the temporary entities/relations
were cleaned up.

Fixture `srv-paper-integration-spawner-composite-20261016-r2` corrected the
positive composite gate to use registered condition names (`world-is
integration-world` and `y-level-above 0`). Paper reported the positive `all{}`
spawner as requested with no failure and retained the negative control as
rejected. Report-contract tests assert both outcomes.

Fixture `srv-paper-integration-spawner-environment-20261016` performed four
real Spawner requests while changing Paper world state and restoring it after
each probe. Rain accepted under `hasStorm=true` and rejected under clear
weather. The 2000-10000 time window accepted at time 6000; the 13000-23000
window rejected at the same time. The report records positive requests as
`passed` and negative controls as `observed-rejected`.

Fixture `srv-paper-integration-spawner-biome-20261016` temporarily changed the
probe block biome to DESERT and restored the original value after the requests.
The `biome-is=DESERT` spawner passed and the `biome-is=PLAINS` control was
rejected, proving the actual SpawnerService biome gate on Paper.

Fixture `srv-paper-integration-maxspawns-20261016` now disables automatic
scheduler spawning on `max_spawn_probe`; the self-test alone consumes its
single allowed spawn. Paper recorded `first=true second=false total=1`, and the
structured `spawnerMaxSpawns` probe passed. This removes the earlier race where
the scheduled spawner could exhaust the limit before the direct test.

Fixture `srv-paper-integration-has-tag-20261016` verified Mythic `HasTag` on a
real Paper Zombie target. The tag was absent, added with the Bukkit scoreboard
tag API, observed as present, then removed and observed absent again.
`conditionHasTag` and aggregate `conditionCatalog` passed.

Fixture `srv-paper-integration-spawner-ground-20261016-r2` verified natural
heightmap placement and no-ground rejection. The heightmap probe spawned
successfully above SAND at Y=64. The negative probe cleared one vertical column
across the Paper world height, observed `no valid spawn location`, and confirmed
all 384 saved block states were restored. Both structured probes passed.

Fixture `srv-paper-integration-spawner-border-20261016-r2` set a temporary
20-block world border centered on the inside probe and restored the original
border afterward. The inside random-location spawn succeeded after configured
radius 16 was clipped to 10; the outside probe was rejected. Both reports
confirmed border restoration.

Fixture `srv-paper-integration-ai-priority-20261016-r3` verified the repaired
AI priority path on Paper 1.21.1 with an online player. The detail probe
confirmed an assigned valid target and attack/flee priorities 0/1; the final
probe recorded `attack-first=true flee-first=true attack-state=ATTACK
flee-state=FLEE`. The fix preserves explicit combat targets before re-sensing.

Fixture `srv-paper-integration-ai-priority-diagnostic-20261016` confirmed the
current build's selector state before arbitration: both probes had PLAYERS mode,
attack/chase/flee enabled, a valid assigned online combat target, and expected
inverse priorities. The final report recorded
`attack-first=true flee-first=true attack-state=ATTACK flee-state=FLEE`.

Fixture `srv-paper-integration-path-normal-20261016` completed the native
Pathfinder probe after changing isolated Paper difficulty from PEACEFUL to
NORMAL; natural monster spawning remained disabled. The deterministic platform
and temporary entity target produced `found=true reachable=true accepted=true
cleared=true on-ground=true stop-calls=4 passed=true`. Temporary platform and
entities were cleaned up.

Fixture `srv-paper-integration-cancelled-20261016-r5` verified the event-aware
condition chain on Paper 1.21.1-133. A real Bukkit damage event was dispatched
to the managed probe; its trigger ran `cancel-event`, then a nested skill whose
`require` was `is-cancelled`. The nested skill marked the real entity with a
GOLD_INGOT in its off-hand. The structured report recorded
`cancelled-condition=true cancelled-before=false cancelled-after=true`, and
the `cancelledCondition` probe passed. The fixture was stopped and its world
was isolated under the run-specific directory.

Fixture `srv-paper-integration-food-level-20261016` verified the new
`food-level` condition against a real online Paper player. The self-test set
the player's food level to 14 and observed `food-level <= 14`, then set it to
15 and observed the same condition reject; the original food level was
restored. The structured `foodLevelCondition` probe passed. The run also
encountered an existing Mineflayer packet `PartialReadError`, so this evidence
only upgrades the server-side condition probe, not the whole player workflow.

Fixture `srv-paper-integration-has-item-20261016-r2` verified `has-item` on a
real online Paper player. The self-test temporarily equipped a Shield, observed
`has-item SHIELD >= 1`, removed it and observed the negative result, then
restored the original main-hand ItemStack. The structured skill-actions record
reported `has-item=true`; the Mineflayer packet decoder remains an independent
workflow limitation.

Fixture `srv-paper-integration-air-level-20261016` verified `air-level` against
a real online Paper player. The self-test set remaining air to 100 and observed
`air-level <= 100`, then set it to 101 and observed the negative result before
restoring the original remaining-air value. The structured skill-actions record
reported `air-level=true`.

Fixture `srv-paper-integration-damage-cause-20261016` verified the original
Bukkit damage cause propagation. The trigger probe dispatched a real
`EntityDamageByEntityEvent` with `ENTITY_ATTACK`; its `on-damage` require used
`damage-cause ENTITY_ATTACK`, and the existing equipment action only ran when
that condition passed. The structured log recorded
`damage-cause-condition=true`, alongside the real damage trigger result.

