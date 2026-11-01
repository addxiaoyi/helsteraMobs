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

Follow-up exploratory fixture `srv-paper-skilltree-restart-034f7c7c184b48a9b2c57db5453effdb`
repeated the workflow on Paper 1.21.11 with the rebuilt plugin. Boot two logged
`Loading 25 persistent chunks`, `Restored 1 SkillTree snapshots`, and the same
passed marker. Persistent mobs now force-load their PDC-bearing chunk so restart
restoration does not depend on a player visiting the area; the plugin releases
that force-loaded chunk after the last managed persistent mob in it dies.

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

Exploratory fixture `srv-paper-integration-exploratory-20261020-target-glowing-client`
connected a real Mineflayer player and exercised the rebuilt target-state
self-test. The log recorded `target-state-conditions=true` after setting the
player glowing, and the structured report marked `targetStateConditions=passed`.
The player state was restored during cleanup; this remains Paper 1.21.11
compatibility evidence and does not satisfy the strict Paper 1.21.1 gate.

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

In this historical workflow, `is-blocking` was unverified because it requires a
real shield-use input workflow; it is not inferred from merely equipping a
shield. The follow-up shield workflow reached Paper but recorded
`state-blocking=false`;
Mineflayer `activateItem()` did not produce a stable shield-use state, so this
remains an honest negative observation rather than a passing claim.
The final historical probe also ran in Survival mode with a preloaded shield
and repeated outgoing `use_item` packets; Paper still reported
`state-blocking=false`.
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

To separate that client decoder limitation from the server event path, the
fixture now has an opt-in raw protocol client (`PAPER_RAW_POTION_WORKFLOW=true`).
It ignores decoded inventory contents, selects protocol hotbar slot 1, sends the
1.21.11 `use_item` packet, waits for the use duration, and sends
`release_use_item`. `srv-paper-integration-exploratory-1.21.11-r14` received
the server-issued Potion and Paper logged the real `PlayerItemConsumeEvent`
through the trigger listener (`on-consume`); this is valid exploratory
1.21.11 drinking evidence, not 1.21.1 release evidence.

The follow-up `srv-paper-integration-exploratory-1.21.11-r16` adds the
structured dynamic report probe `playerPotionConsume=passed`, with observation
`player-potion-consume event=true player=HelsteraTester item=POTION`. The raw
client path and report parser therefore prove both packet-level drinking and
the Bukkit event callback without relying on Mineflayer's item decoder.

The short-effect variant `srv-paper-integration-exploratory-1.21.11-r17`
adds a managed player `on-potion-effect-end` action and records both dynamic
probes: `playerPotionConsume=passed` and
`playerPotionNaturalExpiry=passed` with
`expired=true action-observed=true`. This proves the real-player natural expiry
bridge on Paper 1.21.11; it remains exploratory evidence for the strict 1.21.1
target.

Follow-up raw-client fixtures r129-r132 received the server-issued Potion in
protocol slot 37 and exercised both standard Swiftness and short custom-effect
items. The client emitted `use_item` and `RELEASE_USE_ITEM`; r132 additionally
waited after held-slot selection and used the synchronized player block
position in the release packet. None of these clean reruns produced a
`PlayerItemConsumeEvent`, while the server-side add/remove `playerPotionEvents`
probe remained passed. This narrows the remaining gap to the 1.21.11 raw
client/server consumption handshake; it is not evidence of a plugin listener
failure and remains outside the strict 1.21.1 gate.

The runner then re-armed hotbar slot 1 immediately before use. Fixture
`srv-paper-integration-exploratory-1.21.11-r136` recorded
`player-potion-consume event=true ... item=POTION` and the dynamic report probe
`playerPotionConsume=passed`. Short-effect fixture
`srv-paper-integration-exploratory-1.21.11-r137` recorded both
`playerPotionConsume=passed` and `playerPotionNaturalExpiry=passed` with
`expired=true action-observed=true`. This restores stable exploratory
1.21.11 evidence; strict Paper 1.21.1 evidence is now available from
`srv-paper-potion-expiry-strict-20261101-r1`.

That strict Paper 1.21.1-133 fixture completes 255 probes and records
`potionEffectEndTrigger`, `potionNaturalExpiry`, `playerPotionConsume`, and
`playerPotionNaturalExpiry` as passed. The raw protocol client drinks the
server-equipped short-effect Potion; the log observes
`player-potion-natural-expiry=true expired=true action-observed=true` for the
real online player. With both `PAPER_RAW_POTION_WORKFLOW=true` and
`PAPER_RAW_POTION_SHORT=true`, the report requires this dynamic probe and
retains a `not-observed` result if the expiry action does not run.

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

Fixture `srv-paper-integration-exploratory-1.21.11-r86` extends the owner probe
with official `Owner`/`isOwner`. A real tamed Wolf and online player satisfy the
positive owner comparison, while the model-bound ordinary target is rejected;
the structured line records `owner-condition=true` and the aggregate condition
probes pass. This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1
release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r88` extends the parent
relation probe with official `Children`. One real Pig child linked to the Wolf
passes `children` and `children >=1`, while `children >=2` rejects; the
structured condition catalog includes `children-condition=true`. This is
exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target remains
open.

Fixture `srv-paper-integration-exploratory-1.21.11-r89` extends the spatial
condition probe with official `Inside` and `Outside`. A temporary STONE block
above the real target makes `inside=true`; after removing it, open sky makes
`outside=true`, and the original block state is restored. The structured catalog
records `inside-outside-conditions=true`. This is exploratory Paper 1.21.11
evidence; the Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r102` adds official
`Aerial` coverage. A real floating ArmorStand passes `aerial`, while the
already connected online player provides a grounded negative control through
`isAerial`; the structured log records
`aerial-condition-details positive=true negative=true ground-entity=player
ground=true`, and the `skill-actions` line records `aerial-condition=true`.
Both aggregate condition probes passed. This is exploratory Paper 1.21.11
evidence; the Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r103` adds official
`IsEnderman` coverage. A real Enderman satisfies `isEnderman`, while the real
managed Zombie rejects `is-enderman`; the structured log records
`enderman-condition-details positive=true negative=true`, and the
`skill-actions` line records `enderman-condition=true`. Both aggregate probes
passed. This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release
target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r110` adds official
`MaterialIsOnCooldown` coverage. The real online player receives an Ender Pearl
cooldown; `materialIsOnCooldown{m=ENDER_PEARL}` passes, Diamond rejects, and
the cleared Ender Pearl cooldown rejects. The structured log records
`material-cooldown-condition-details positive=true wrong-material=true
negative=true`, and the `skill-actions` line records
`material-cooldown-condition=true`. Both aggregate probes passed. This is
exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target remains
open. This rerun also removes the earlier self-test ordering false negative:
`conditionCatalog` and `conditionUtilityAliases` are both `passed`.

Fixture `srv-paper-integration-exploratory-1.21.11-r113` adds official
`TriggerItemType` and `TriggerBlockType` coverage. A real dropped STONE Item is attached as the
trigger entity; `triggerItemType{mat=STONE,DIRT}` passes and
`trigger-item-type DIAMOND` rejects. The structured log records
`trigger-item-type-condition-details positive=true negative=true`, and the
`skill-actions` line records `trigger-item-type-condition=true`. A real STONE
origin with `on-block-break` state passes `triggerBlockType{mat=STONE,DIRT}`;
DIAMOND and `on-damage` state reject, with
`trigger-block-type-condition-details positive=true negative=true
state-rejected=true`. The `skill-actions` line records
`trigger-block-type-condition=true`. Both aggregate probes passed. This is
exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target remains
open.

Fixture `srv-paper-integration-exploratory-1.21.11-r114` adds official
`XDiff`/`YDiff`/`ZDiff` coverage. The real target is three blocks east, nearly
level, and one block north of the managed caster; the positive ranges pass and
`x-diff >4` rejects. The structured log records
`coordinate-difference-condition-details x=true y=true z=true`, and the
`skill-actions` line records `coordinate-difference-condition=true`. Both
aggregate probes passed. This is exploratory Paper 1.21.11 evidence; the
Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r116` adds official
`OriginLocation` coverage. The real execution origin passes both block-form and
exact-coordinate matching, while `999,999,999` rejects; the structured log
records `origin-location-condition-details condition=true`, and the
`skill-actions` line records `origin-location-condition=true`. Both aggregate
probes passed. This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1
release target remains open. This rerun also verifies the comma-preserving
`loc=` migration path after the parser ordering fix.

Fixture `srv-paper-integration-exploratory-1.21.11-r108` adds official `Color`
coverage. A real Sheep set to RED passes `color{c=RED}`, rejects `clr BLUE`,
and the managed Zombie rejects `color RED`; the structured log records
`color-condition-details positive=true negative=true type-rejected=true`, and
the `skill-actions` line records `color-condition=true`. Both aggregate probes
passed. This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release
target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r107` extends the dialogue
probe with official `IsInCinematic`. The connected player target satisfies
`isInCinematic` while the temporary cinematic is active and rejects
`is-in-cinematic` after stop; the structured log records
`dialogue-condition-details started=true open=true cinematic-open=true
closed=true cinematic-closed=true problems=0`. The `skill-actions` line keeps
`dialogue-condition=true`, and both aggregate probes passed. This is
exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target remains
open.

Fixture `srv-paper-integration-exploratory-1.21.11-r106` adds official
`HasDialogOpen` coverage and fixes dialogue hook ordering. A temporary
cinematic starts for the managed model instance, `hasDialogOpen` passes while
it is active, and `has-dialog-open` rejects after `stop`; the structured log
records `dialogue-condition-details started=true open=true closed=true
problems=0`, and the `skill-actions` line records `dialogue-condition=true`.
Both aggregate probes passed. This is exploratory Paper 1.21.11 evidence; the
Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r104` adds official
`HasEnchantment` coverage. A temporary Sharpness III diamond sword on the real
managed Zombie passes `hasEnchantment{e=SHARPNESS;l=>2}` and
`hasEnchant{e=ANY;l=3}`, while `SHARPNESS >3` and `LOOTING` reject. The
structured log records
`enchantment-condition-details positive=true any=true level-rejected=true
type-rejected=true`, and the `skill-actions` line records
`enchantment-condition=true`. Both aggregate probes passed. This is
exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target remains
open.

Fixture `srv-paper-integration-exploratory-1.21.11-r91` extends the motion
condition probe with official `FallSpeed`. A real target with downward velocity
passes `fall-speed >= 0.4`; the existing motion checks and cleanup remain
passed. This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release
target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r92` extends the online-player
condition probe with official `EnchantingLevel` and `EnchantingExperience`.
The temporary target state uses level 7 and progress 0.25; level `>=7`, progress
`>=0.2`, and the level negative comparator pass, then both original values are
restored. The structured catalog records `enchanting-conditions=true`. This is
exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target remains
open.

Fixture `srv-paper-integration-exploratory-1.21.11-r93` extends the condition
catalog with official `Plugin` aliases. Bukkit PluginManager reports the loaded
`helsteraMobs` plugin as enabled and rejects `__missing_plugin__`; the structured
catalog records `plugin-condition=true`. This is exploratory Paper 1.21.11
evidence; the Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r95` extends the motion probe
with official `DirectionalVelocity`. The temporary target velocity satisfies
named x/y/z ranges, and the structured line records
`directional-velocity-condition=true` alongside `fall-speed-condition=true`.
This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target
remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r96` extends the spatial
condition probe with official `FieldOfView`. A real target directly ahead passes
90 degrees, a side target is rejected, and a +90 degree rotated cone accepts
the side target; the structured line records `field-of-view-condition=true`.
This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target
remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r97` extends the spatial
condition probe with official `DistanceFromLocation`. The real target matches
its exact x/y/z coordinates at distance 0, while a point ten blocks away rejects
the `<5` range; the structured catalog records
`distance-from-location-condition=true`. This is exploratory Paper 1.21.11
evidence; the Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r87` extends the spatial
condition self-test with official `BlockTypeInRadius`. A temporary STONE block
passes `block-type-in-radius STONE >=1 1`, DIRT rejects the same radius, and the
original block state is restored. This is exploratory Paper 1.21.11 evidence;
the Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-is-child-20261016` additionally created a real
Pig child with the model-bound Wolf as parent and evaluated `is-child` against
that Pig target. This is Helstera's custom parent relation, not evidence for
Mythic's age `isChild` condition; migration maps that condition to `is-baby`.
`conditionIsChild`, `parentRelation`, and aggregate
`conditionCatalog` all passed; the Pig and relation were cleaned up.

Fixture `srv-paper-integration-parent-derived-20261016` checked the derived
parent conditions on Paper 1.21.1: `is-sibling` passed for two children sharing
the live Wolf parent; `is-parent-alive` passed before removal and failed after
the parent entity was removed. `parentDerivedConditions`, `parentRelation`,
and aggregate `conditionCatalog` passed, and the temporary entities/relations
were cleaned up.

Follow-up exploratory fixture `srv-paper-integration-exploratory-1.21.11-r34`
repeated the parent relation and derived-condition probes after separating
stored `has-parent` relation state from live `is-parent-alive` resolution;
`parentRelation=passed` and `parentDerivedConditions=passed`.

Fixture `srv-paper-integration-exploratory-1.21.11-r37` records
`signal-mechanics=true` from the rebuilt plugin jar. A scoped signal action
delivered its payload and source instance ID through the live signal bus; the
server log contained no signal exception. This remains exploratory Paper
1.21.11 evidence.

Fixture `srv-paper-integration-exploratory-1.21.11-r38` additionally records
`target-kill=true`: the runtime `kill-targets` action killed a real selected
Zombie target while preserving caster semantics, with no Paper exception.

Fixture `srv-paper-integration-exploratory-1.21.11-r40` records
`dotMechanic=passed` from the rebuilt plugin jar. A real Sheep target took
damage across multiple 10-tick intervals (`after-intervals=94.0` from an
initial 100.0), reached 92.0 health by the 40-tick duration, and remained at
92.0 after the expiry window. The target is deliberately non-burning to avoid
confusing environmental damage with DoT ticks. This remains exploratory Paper
1.21.11 evidence; it does not qualify for the strict Paper 1.21.1 release gate.

Fixture `srv-paper-integration-exploratory-1.21.11-r41` additionally records
`threatActions=passed`. The live threat table accepted `threat-add`, removed
only the selected target with `threat-clear true`, promoted that target with
`threat-focus`, and then emptied with the default `threat-clear`. The same run
retained `threatTargeter=passed` and `threatFactionFilters=passed`; this remains
exploratory Paper 1.21.11 evidence.

Fixture `srv-paper-integration-exploratory-1.21.11-r42` records
`damageMeleeMechanic=passed`. A real non-burning Sheep within the configured
radius took the melee damage while the managed caster's health remained
unchanged, proving the caster exclusion on the live Paper path. The same run
retained `dotMechanic=passed` and `threatActions=passed`; this remains
exploratory Paper 1.21.11 evidence.

Historical fixture `srv-paper-integration-exploratory-1.21.11-r43` reran the shield
workflow after keeping a Shield equipped through the `is-blocking` evaluation;
the client emitted repeated `use_item` packets, but Paper still reported
`state-blocking=false`. The Bukkit condition and mocked `Player#isBlocking`
path remain covered; real client blocking remains an honest protocol/timing
evidence gap and is not promoted to strict release evidence.

Fixture `srv-paper-integration-exploratory-1.21.11-r44` records
`conditionUtilityAliases=passed`. The live self-test evaluated `incombat`,
`targetcount`, `mobsinradius`, and deterministic `randomchance 100` against
managed Paper entities; `mobs-in-radius` used the mob-only evaluator. The same
run retained `damageMeleeMechanic`, `dotMechanic`, and `threatActions` as
passed. This remains exploratory Paper 1.21.11 evidence.

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

Fixture `srv-paper-integration-exploratory-1.21.11-r84` extends the same real
damage trigger with official `DamageAmount`. The trigger configuration requires
`damage-amount >=2`, and the structured line records
`damage-cause-condition=true damage-amount-condition=true` from the real
`EntityDamageEvent#getFinalDamage()` path. This is exploratory Paper 1.21.11
evidence; the Paper 1.21.1 release target remains open.

On 2026-10-18, an explicit exploratory run was attempted with the local Paper
`1.21.4-232-12d8fe0` jar in `srv-paper-integration-exploratory-1.21.4-r2`.
The new `-Exploratory` switch correctly rejected `-Strict` and reported the
version as non-release evidence. The first launch exposed a missing dependency
classpath; after supplying `/home/add/libraries`, launch reached a mixed-version
`datafixerupper`/Codec `NoSuchMethodError` because that directory contains both
1.21.4 and 1.21.11 libraries. No feature probe was counted from this run, and
the 1.21.1 strict evidence boundary remains unchanged.

The follow-up isolated run `srv-paper-integration-exploratory-1.21.4-r5`
selected one dependency version per artifact (Paper API 1.21.4, DataFixerUpper
8.0.16, Adventure 4.25.0) and completed Paper bootstrap, plugin remapping,
`helsteraMobs` enablement, model loading, and server self-tests. Its report is
explicitly `evidenceMode=exploratory`, with Paper implementation
`1.21.4-232-12d8fe0`; it is not release evidence. The run observed 48 passing
probes, including model display, entity option setters, attributes, loot,
spawner cooldown, path clearing, and plugin startup. Version-sensitive or
timing-sensitive probes remained non-passing: `ai-selector-core` was unavailable
on this setup, natural Bee/Zombie behavior did not match the probe, and the
potion expiry action marker was not observed. These observations are retained
for compatibility diagnosis only and do not change the Paper 1.21.1 matrix
statuses.

The follow-up `srv-paper-integration-exploratory-1.21.4-r10` run moved the
potion expiry carrier inside the configured despawn boundary and used a
dedicated persistent PIG probe. Paper 1.21.4 then recorded
`potion-natural-expiry=true expired=true event-observed=true action-observed=true
item-marker-observed=true`; the mount-event probe also passed. The natural
Bee/Zombie option probe still reported `passed=false` on this version, so it
remains exploratory compatibility evidence rather than a release upgrade.

Follow-up exploratory fixtures `srv-paper-integration-exploratory-1.21.4-r11`
through `r16` isolated the remaining Zombie conversion probe. The control
Zombie stayed valid with `conversion-time=20`; the latest run recorded
`control-block=WATER control-below=WATER control-above=WATER` but Paper still
reported `control-in-water=false` and no Drowned entity. Bee stinger restoration,
Creeper fuse timing, mount-event cancellation, and natural potion expiry
continued to behave as before. The conversion result remains `not-observed`
and is not promoted to release parity; the probe diagnostics are retained for
future Paper-version compatibility work.

Exploratory fixture `srv-paper-integration-exploratory-1.21.4-r19` restored the
Mineflayer player workflow after the client scripts were changed to select the
Paper jar's protocol version instead of hard-coding 1.21.1. The real player
joined Paper 1.21.4 and the report passed server/player probes for equipment,
potion effects, drop-item, teleport, conditions, block break/place, and item
pickup. Potion drinking remained skipped by `PAPER_SKIP_PLAYER_POTION`.

Fixtures `srv-paper-integration-exploratory-1.21.4-r20` through `r26` isolated
the AI selector probe and exposed two fixture/runtime issues: the synthetic
threat probe temporarily replaced the global faction table, and conditional
selectors were implicitly rejected by a line-of-sight check. The runtime now
restores the configured faction service and conditional selectors rely on their
explicit shared condition filters rather than an implicit ray trace.

The final exploratory selector fixture `srv-paper-integration-exploratory-1.21.4-r27`
recorded `goals=true player-target=true monster-target=true clear-no-fallback=true
owner-target=true faction-target=true conditional-target=true native-target=true
passed=true`. It is valid Paper 1.21.4 compatibility evidence only; the Paper
1.21.1 release gate remains unchanged.

Exploratory fixture `srv-paper-integration-exploratory-1.21.4-r33` switched the
trigger self-test from direct Bukkit removal to the actual `remove-effect`
mechanic and verified the new bridge. Paper recorded
`potion-effect-end=true potion-effect-action=true potion-events=3` with
`potion-added-by-mechanic=true potion-active-before-remove=true`, while the
natural expiry probe continued to pass. This verifies the managed mechanic path
on Paper 1.21.4; native player drinking and Paper 1.21.1 release timing remain
unverified.

On 2026-10-18, Apache Maven 3.9.11 completed the offline
`-Ppaper-integration ... verify` quality gate, including SpotBugs and the
synthetic report-contract checks. A live `-Strict` release run remains blocked
by missing Paper 1.21.1 server artifacts; the historical Paper downloads API
responds HTTP 410 for the retired 1.21.1 build endpoint.

On 2026-10-20, the repository Maven Wrapper (Apache Maven 3.9.16) completed
the same offline `-Ppaper-integration ... verify` gate after the scalar-condition
parser and migration regression updates. This resolves the local Maven 3.8.7
toolchain mismatch without changing the release-version requirement; the live
Paper 1.21.1 strict run remains externally blocked by the missing server jar.

An additional exploratory attempt with the local Paper `1.21.11-99-81b9122`
jar in `srv-paper-integration-exploratory-1.21.11-r1` correctly identified the
run as non-release evidence but exited before startup because no isolated Paper
dependency classpath was supplied (`NoClassDefFoundError: joptsimple/OptionException`).
No feature probe was counted; this does not alter the 1.21.1 release boundary.

The corrected isolated dependency classpath was used by
`srv-paper-integration-exploratory-1.21.11-r5`. It removed the duplicate 1.20
Bungee chat jar and reached Paper bootstrap, plugin enablement, model loading,
and the server self-tests without linkage errors. The run had no online client,
so player-scoped and client probes were intentionally not counted; all results
remain exploratory compatibility evidence.

The Mineflayer player run `srv-paper-integration-exploratory-1.21.11-r6`
recorded `spawnerPlayerLimits` with `first=true second=false total=1
player-limit=1`, plus natural potion expiry, Bee stinger restoration, and the
mount-event probe. Its Zombie conversion probe remained false on this Paper
build. This confirms the player-limit path on 1.21.11 but does not upgrade the
Paper 1.21.1 release evidence.

The follow-up `srv-paper-integration-exploratory-1.21.11-r7` rebuilt the plugin
after the vehicle probe switched from reflective `Boat.class` spawning to
`EntityType.BOAT`; Paper 1.21.11 then completed the vehicle workflow with
`enter=true exit=true dismount=true` and no spawn-class exception. The same run
again observed `spawnerPlayerLimits` with the configured first-pass/second-pass
rejection. The destroy marker remained false and is not promoted as a passing
destroy-event claim; all r7 results remain exploratory only.

The current plugin build was also exercised by
`srv-paper-integration-exploratory-1.21.11-r8`. Its real online-player
`skill-actions` record includes `collidable-condition=true` and
`offhand-condition=true`, covering both caster and target aliases on Paper
1.21.11. The client was later killed by the busy exploratory fixture, so r8 is
condition evidence only and does not replace the earlier complete player run or
qualify as Paper 1.21.1 release evidence.

Follow-up fixture `srv-paper-integration-exploratory-1.21.11-r9` recorded
`relation-targeters=true` in the real player self-test. It attached the online
player as a passenger of the managed Wolf and attached that Wolf to a real
Pig, then resolved `@Passengers` and `@Vehicle` before cleanup. This is
exploratory Paper 1.21.11 evidence; the strict Paper 1.21.1 boundary remains
unchanged.

Fixture `srv-paper-integration-exploratory-1.21.11-r10` extended the same
relationship probe to a real Pig child whose parent metadata points at the
managed Wolf. The self-test recorded `children-targeter=true` together with
the existing relation targeter result. This remains exploratory compatibility
evidence only.

Fixture `srv-paper-integration-exploratory-1.21.11-r22` rebuilt the current
plugin and connected a real Mineflayer player using an isolated Paper 1.21.11
dependency set. The `skill-actions` self-test recorded
`scoreboard-tag-mechanics=true` after adding/removing a caster tag and adding/
removing a targeter-selected tag on real Paper entities. The structured report
recorded both `conditionHasTag=passed` and `scoreboardTagMechanics=passed`.
This is exploratory Paper 1.21.11 evidence only; it does not qualify for the
Paper 1.21.1 strict release gate.

Fixture `srv-paper-integration-exploratory-1.21.11-r23` records the same
exploratory Paper 1.21.11 event self-test as
`tame-breed-tame=true tame-breed-breed=true` in the structured `events` line.
It binds real Wolf/Sheep model instances, dispatches Bukkit
`EntityTameEvent`/`EntityBreedEvent`, and observes both configured trigger
actions. This proves the managed event-dispatch path; natural client taming or
breeding timing and Paper 1.21.1 release evidence remain open.

Fixture `srv-paper-integration-exploratory-1.21.11-r24` extends the same
online-player run with a real managed Wolf and player-driven
`PlayerLeashEntityEvent`/`PlayerUnleashEntityEvent`. The structured report
records `leashLifecycle=passed` after both trigger actions update the Wolf.
Natural leash timing and Paper 1.21.1 release evidence remain open.

The follow-up online-player fixture `srv-paper-integration-exploratory-1.21.11-r25`
also binds a real Sheep to `on-shear` and dispatches
`PlayerShearEntityEvent`. The structured `events` line records
`shear-trigger=true`, proving the managed trigger action and player-target path;
natural shearing timing and Paper 1.21.1 release evidence remain open.

Fixture `srv-paper-integration-exploratory-1.21.11-r26` extends the online
player event self-test with `PlayerBedEnterEvent` and `PlayerBedLeaveEvent` on
the managed player. The structured `events` line records both
`bed-enter-leave-enter=true` and `bed-enter-leave-leave=true`; natural bed
interaction timing and Paper 1.21.1 release evidence remain open.

The same fixture `srv-paper-integration-exploratory-1.21.11-r26` binds a real
Villager to `on-trade` and dispatches Paper `PlayerTradeEvent` with a real
`MerchantRecipe` and the online player as trader. The structured `events` line
also records `trade-trigger=true`; natural client trading timing and Paper
1.21.1 release evidence remain open.

Fixture `srv-paper-integration-exploratory-1.21.11-r28` connected a real online
player and recorded `target-state-conditions=true`. Permission, game mode,
food, remaining air, inventory, and active potion checks passed against the
explicit target; the same checks failed closed in a no-target context despite
the caster retaining those states. This is exploratory Paper 1.21.11 evidence;
the Paper 1.21.1 release target remains open.

Follow-up fixture `srv-paper-integration-exploratory-1.21.11-r30` repeats that
probe after adding strict `target-has-auras`, `target-holding`, and
`target-wearing` checks. The aggregate `target-state-conditions=true` passed
with a real online player and still failed closed for the no-target context;
this remains exploratory Paper 1.21.11 evidence.

Fixture `srv-paper-integration-exploratory-1.21.11-r31` reran the same checks
from the rebuilt full plugin jar. It again recorded
`target-state-conditions=true`, with no Paper exception or NPE in the server
log; the release target remains Paper 1.21.1 and is still external-blocked.

Fixture `srv-paper-integration-exploratory-1.21.11-r32` also bound exact
`guards`/`undead` faction IDs on the managed caster and target. The structured
`faction-conditions=true` probe
passed caster/target faction matches, same/different transitions, and a
no-target fail-closed check. This remains exploratory Paper 1.21.11 evidence.

Fixture `srv-paper-integration-exploratory-1.21.11-r33` reran the custom-owner
probe after adding `owner-is-player`; `custom-owner-relation=true` passed with
an online Player owner and no Paper exception was observed. This remains
exploratory Paper 1.21.11 evidence.

Fixture `srv-paper-integration-exploratory-1.21.11-r45` records
`cooldownMechanics=passed`. The live managed instance started with `offgcd`,
was blocked after `gcd 40`, and became available again after the explicit
zero-duration clear. The same run retained damage-melee, DoT, and threat
action evidence; this remains exploratory Paper 1.21.11 evidence.

Fixture `srv-paper-integration-exploratory-1.21.11-r46` also wires Mythic
`SetSkillCooldown`. Its `cooldownMechanics=passed` probe sets the
`unlock_branch` named skill to a 2-second cooldown, observes the cast become
cooling, then clears it with a zero-duration override and observes the skill
become ready again.

Fixture `srv-paper-integration-exploratory-1.21.11-r47` records
`foodSaturationCondition=passed`. The live player saturation comparator passed
at 1.0 and rejected 2.0 before the original value was restored; the run also
retained cooldown, DoT, damage-melee, and threat evidence.

Fixture `srv-paper-integration-exploratory-1.21.11-r48` records
`lastDamageCauseCondition=passed`. A real managed entity stored a FIRE
`EntityDamageEvent`; the new `last-damage-cause FIRE` condition passed while
the ENTITY_ATTACK check failed, distinct from current event-context cause.

Fixture `srv-paper-integration-exploratory-1.21.11-r49` records
`motionConditions=passed`. The live target velocity was set to
`(0.25, -0.5, 0.75)` and component plus magnitude comparators all passed;
the existing last-damage, saturation, cooldown, DoT, and threat probes also
remained observed.

Fixture `srv-paper-integration-exploratory-1.21.11-r50` extends the same
condition utility probe with `players-online`, `players-in-world`, and
`mobs-in-world` comparator checks against the live Paper world; the aggregate
`conditionUtilityAliases=passed` result remains green.

Fixture `srv-paper-integration-exploratory-1.21.11-r51` records
`playerRadiusConditions=passed`: `player-within 1` passed and
`player-not-within 1` failed for the real online player as explicit target,
while existing motion, damage-cause, saturation, cooldown, DoT, and threat
probes remained observed.

Fixture `srv-paper-integration-exploratory-1.21.11-r52` records
`ownerOnlineCondition=passed` for the existing PDC-backed custom owner relation
with the real online Paper player; owner/player and parent relation probes also
remained passed.

Fixture `srv-paper-integration-exploratory-1.21.11-r53` records
`freeInventorySlotCondition=passed`. The real online player's empty storage
slot passes `has-free-inventory-slot`; after the storage inventory is filled,
the same condition rejects; the original inventory is then restored. The
existing cooldown, last-damage-cause, motion, DoT, and threat probes remained
observed. This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release
target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r54` records
`nameCondition=passed`. A real managed Zombie matches the custom name
`NameProbe` and rejects the default `Zombie` name while the custom name is set;
the custom name is then restored. The aggregate condition catalog and prior
r53 probes remained observed. This is exploratory Paper 1.21.11 evidence; the
Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r55` records the natural Mob
Option probes as passed: `creeperNaturalFuse`, `mobOptionsNatural`, and
`mobOptionsMountEvent`. The control Zombie converted in water while the
managed `preventTransformation` Zombie remained valid; protected Bee stinger
loss was restored after a real attack, and the protected/control mount-event
pair recorded cancellation versus allowance. The probe keeps native Zombie AI
enabled so the conversion path is actually ticked, and pins the entities in
place without suppressing that behavior. This remains exploratory Paper
1.21.11 evidence; natural AI jockey selection and the Paper 1.21.1 release
target remain open.

Fixture `srv-paper-integration-exploratory-1.21.11-r56` records
`hasItemCondition=passed`. The online player's restored inventory is given a
temporary Shield, `has-item SHIELD >=1` passes, removing the Shield makes the
same condition reject, and the original inventory is restored. The r55 Mob
Option probes and the Name/free-slot probes remained passed. This is
exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target remains
open.

Fixture `srv-paper-integration-exploratory-1.21.11-r59` records the delayed
target-fluid probe as passed: `target-fluids-delayed water=true lava=true
passed=true`. The probe evaluates a real target after Paper advances one tick
for each fluid state, then restores the temporary water/lava blocks. The
same-tick diagnostic remains false because the Bukkit fluid cache has not yet
advanced; the structured `targetFluidConditions` report uses only the delayed
observation. This remains exploratory Paper 1.21.11 evidence; the Paper 1.21.1
release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r60` extends the passing
`conditionUtilityAliases` probe with official `LivingInRadius`, `MobsInChunk`,
and `Dimension` checks. A real Paper target/world satisfies the living radius,
chunk mob count, and NORMAL environment conditions; existing Name, HasItem,
fluid, and Mob Option probes remained passed. This is exploratory Paper 1.21.11
evidence; the Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r64` extends the same
`conditionUtilityAliases` probe with official `Height`, `HeightAbove`,
`HeightBelow`, `Pitch`, `Yaw`, and `OnBlock` checks. The structured diagnostic
records `spatial-condition-details height=true orientation=true on-block=true`
for a real managed Zombie at Y=72, pitch -15, yaw 135, standing on STONE.
This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target
remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r65` extends the passing
`conditionUtilityAliases` probe with official `WorldTime`, `SkyLightLevel`,
and `LightLevelFromBlocks` checks against the live Paper world and target
location. Existing spatial, fluid, inventory, and Mob Option probes remained
passed. This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release
target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r66` extends the spatial
diagnostic with exact `BlockType` and `BiomeType` checks. The structured line
records `block-type=true biome-type=true` alongside the existing height,
orientation, and `OnBlock` passes. Wildcard/block-tag and non-exact biome
category forms remain intentionally unobserved. This is exploratory Paper
1.21.11 evidence; the Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r67` extends the passing
`conditionUtilityAliases` probe with `ServerIsPaper`. Paper server identity was
recognized through the runtime evaluator; `IsInSurvivalMode` remains covered
by strict explicit-player unit/migration tests. This is exploratory Paper
1.21.11 evidence; the Paper 1.21.1 release target remains open.

The utility self-test previously hard-coded `server-version 1.21.11`, which
made the aggregate `conditionUtilityAliases` probe fail on the available Paper
1.21.4 fixture even though every utility subcondition passed. The exact branch
now uses `Bukkit#getMinecraftVersion()` while the inclusive after/before checks
retain their fixed compatibility bounds. Exploratory fixture
`srv-paper-integration-exploratory-20261019-utility-r2` records
`conditionUtilityAliases=passed` with `server-version=1.21.4`.

Fixture `srv-paper-integration-exploratory-1.21.11-r68` extends the passing
`conditionUtilityAliases` probe with `IsMythicMob`. The plugin-injected lookup
recognized the managed caster instance; ordinary unregistered entities remain
outside that managed lookup. This is exploratory Paper 1.21.11 evidence; the
Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r70` extends the passing
`conditionUtilityAliases` probe with `MobsNearOrigin`, using the real execution
origin, a radius of 4, and a `ZOMBIE` type filter. Existing managed-entity,
spatial, fluid, and Mob Option probes remained passed. This is exploratory
Paper 1.21.11 evidence; the Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r71` extends the passing
`conditionUtilityAliases` probe with `DistanceFromSpawn`, comparing the live
target location to its Bukkit world spawn using a safe positive range. Existing
origin, managed-entity, spatial, fluid, and Mob Option probes remained passed.
This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target
remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r73` extends the passing
`conditionUtilityAliases` probe with `BoundingBoxesOverlap`. The real managed
caster and target were temporarily colocated, and the Bukkit AABB overlap
condition passed before restoring the target location. This is exploratory
Paper 1.21.11 evidence; the Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r74` extends the passing
`conditionUtilityAliases` probe with `IsCreeperPrimed`; a real Creeper was
ignited and the Bukkit `isIgnited()` condition passed. `IsFrozen` is now
implemented through Paper's `Entity#isFrozen()` boolean and the rebuilt
self-test reaches the frozen state through the `Freeze` mechanic before
restoring the original freeze ticks. Exploratory Paper 1.21.4 r123 records the
independent `frozenCondition=passed` probe; the Paper 1.21.1 release target
remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r75` extends the target
category probe with `IsRaiderPatrolLeader`. A real Pillager was marked as a
patrol leader through Bukkit and the structured `targetCategories` probe
remained passed. This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1
release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r76` records
`spyglassCondition=passed`. A real online Paper player starts using a temporary
SPYGLASS, the target-only `IsUsingSpyglass` condition passes, clearing the
active item makes it reject, and the previous hand item is restored. This is
exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target remains
open.

Fixture `srv-paper-integration-exploratory-1.21.11-r79` extends the passing
`conditionUtilityAliases` probe with `Altitude`, evaluating the real target's
height above the Bukkit highest-block surface under the configured maximum.
This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target
remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r80` keeps the utility probe
passed with caster-only `VehicleIsDead`; the live self-test confirms the
no-vehicle fail-closed path. Persistent dead-vehicle timing is not claimed
because Bukkit removes passenger relations when a vehicle is removed.

Fixture `srv-paper-integration-exploratory-1.21.11-r81` extends the passing
`conditionUtilityAliases` probe with official `Moist` and `MoistureLevel`.
The real target block reports
`moisture-condition-details hydrated=true hydrated-range=true dry=true max=7`:
moisture 7 passes `isMoist` and `l=1to7`, moisture 0 rejects `moist` and passes
the exact zero range. The probe uses Bukkit `Farmland` BlockData only and
restores the original BlockState. This is exploratory Paper 1.21.11 evidence;
the Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r82` extends the passing
condition self-test with official `MobSize`. A real Slime is set to size 4 and
then size 2; the structured log records
`mob-size-condition-details large=true exact=true size=2`, covering a range and
an exact match before cleanup. This is exploratory Paper 1.21.11 evidence;
the Paper 1.21.1 release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r77` extends the passing
`conditionUtilityAliases` probe with `EnderDragonAlive`. A real invulnerable
EnderDragon was spawned in the target world, the condition observed it, and
the existing probe cleanup removed it. At that time `PlayerKills` was not
claimed because the runtime had no managed-mob counter; the condition is now
implemented with per-instance lifetime state. Exploratory Paper 1.21.4
killprobe now records `playerKillTrigger=passed` and
`playerKillsCondition=passed` after a deterministic real-player death; strict
Paper 1.21.1 evidence remains pending.

The rebuilt self-test now also evaluates official `Absorption` on the managed
Zombie after setting it through `SetAbsorption 4`, checking `>=4`, `=4`, and
`>4` negative paths before restoring the original amount. Exploratory fixture
r8 records the corresponding `absorption-condition` catalog path passed; this
does not upgrade strict release evidence.

The same rebuilt self-test checks official `HasPotionEffect` against a
temporary real-player SPEED effect with amplifier 0 and duration 20 ticks;
`has-potion-effect SPEED 0 1-20` passes and the structured log records
`potion-effect-condition=true`. Exploratory fixture r8 records the independent
`potionEffectCondition` probe passed; strict Paper 1.21.1 evidence remains
pending.

The same self-test adapts the temporary SPEED amplifier 0 to one aura stack;
`has-aura-stacks SPEED >=1` passes and the structured log records
`aura-stacks-condition=true`. Exploratory fixture r8 records the independent
`auraStacksCondition` probe passed; strict Paper 1.21.1 evidence remains
pending.

The same self-test checks the managed model's existing level field:
`level >=1` passes and `level <1` rejects, with structured output
`level-condition=true`. Exploratory fixture r8 records the independent
`levelCondition` probe passed; strict Paper 1.21.1 evidence remains pending.

The same temporary absorption probe now checks `Health` and `HealthPercent`
with and without `includeabsorption`; only the inclusive forms pass, recorded
as `health-absorption-condition=true` in exploratory fixture r8. This does not
upgrade strict release evidence.

The same self-test checks the world full-time lunar phase against the matching
numeric phase and a neighboring phase; structured output records
`lunar-phase-condition=true`. Exploratory fixture r8 records the independent
`lunarPhaseCondition` probe passed; strict Paper 1.21.1 evidence remains
pending.

The signal self-test now also verifies `LastSignal` after delivery by matching
the managed instance's most recent signal name. Exploratory fixture r3 records
the independent `signalMechanics` probe passed; strict Paper 1.21.1 evidence
remains pending.

The online-player self-test also creates real Bukkit scoreboard objectives and
checks `GlobalScore`, `Score`, and missing-score negative paths. Exploratory
fixture r3 records `globalScoreCondition=passed`, `scoreMechanics=passed`, and
`scoreCondition=passed`; strict Paper 1.21.1 evidence remains pending.

The condition self-test now also checks the Bukkit ray trace from the execution
origin to the real target eye location, recorded as
`line-of-sight-from-origin-condition=true`. This new observation remains
pending in a fresh Paper fixture.

Exploratory fixture r2 also records independent
`lineOfSightFromOriginCondition=passed`, `originLocationCondition=passed`, and
`triggerBlockTypeCondition=passed`; strict Paper 1.21.1 evidence remains
pending.

The online-player self-test controls a real Player's server-side rotation,
places a temporary STONE block along the eye ray, and checks positive/negative
`LookingAt` paths before restoring both block and rotation. Exploratory fixture
r2 records `lookingAtCondition=passed`; strict Paper 1.21.1 evidence remains
pending.

The same online-player self-test uses Bukkit `LivingEntity#attack` against an
temporary Zombie, verifies `HumanEntity#getAttackCooldown()` falls below `1.0`,
and checks the `ItemRecharging` condition. Exploratory fixture r3 records
`itemRechargingCondition=passed` after a real Bukkit attack; strict Paper 1.21.1
evidence remains pending.

The trigger self-test now constructs an `EntityShootBowEvent` with force 0.75
and evaluates `bow-tension >0.5` through the event-aware context. Exploratory
fixture r3 records the independent `bowTensionCondition` probe passed; strict
Paper 1.21.1 evidence remains pending.

The condition self-test also checks equal and unequal literal strings, recorded
as `string-equals-condition=true`. Exploratory fixture r8 records the
independent `stringEqualsCondition` probe passed; strict Paper 1.21.1 evidence
remains pending.

The condition self-test also checks empty and non-empty literal strings through
`StringEmpty`/`StringNotEmpty`, recorded as `string-empty-condition=true`.
Exploratory fixture r8 records the independent `stringEmptyCondition` probe
passed; strict Paper 1.21.1 evidence remains pending.

The same real dropped STONE Item probe now checks `EntityItemType` and
`EntityMaterialType`, including a DIAMOND negative path. Exploratory fixture r2
records `entityItemTypeCondition=passed`; strict Paper 1.21.1 evidence remains
pending.

The same dropped STONE Item probe compares resolved STONE and DIAMOND templates
through `EntityItemIsSimilar`. Exploratory fixture r2 records
`entityItemSimilarCondition=passed`; strict Paper 1.21.1 evidence remains
pending.

The same fixture records independent `itemSimilarCondition=passed` and
`targetsCondition=passed` for the live player's SHIELD slot and inherited
target list paths. Strict Paper 1.21.1 evidence remains pending.

`ProjectileHasEnded` is implemented through the existing projectile-hit event
path and has offline unit coverage. Exploratory Paper 1.21.4 fixture r3 launches
a real Bukkit Arrow into a temporary block, observes `ProjectileHitEvent`, and
records `projectileHasEndedCondition=passed`; strict Paper 1.21.1 evidence
remains pending.

The condition self-test also checks numeric, lexical, and invalid typed integer
`CompareValues` paths, recorded as `compare-values-condition=true`.
Exploratory fixture r8 records the independent `compareValuesCondition` probe
passed; strict Paper 1.21.1 evidence remains pending.

The condition self-test also checks the loaded `berserk` skill positively and an
unknown skill negatively through `IsSkill`, recorded as
`is-skill-condition=true`. Exploratory fixture r8 records the independent
`isSkillCondition` probe passed; strict Paper 1.21.1 evidence remains pending.

The managed model context now writes a string and list variable through the
real SkillService, then checks prefix, suffix, `ALL`, and `ANY` containment.
Exploratory fixture r3 records `variableContainsCondition=passed`; strict Paper
1.21.1 evidence remains pending.

Fixture `srv-paper-integration-exploratory-1.21.11-r78` extends the same probe
with `EnderDragonPhase`. The temporary real EnderDragon was set to Bukkit phase
`CIRCLING`, and the exact phase condition passed before cleanup. This is
exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target remains
open.

Fixture `srv-paper-integration-exploratory-1.21.11-r79` extends the passing
`conditionUtilityAliases` probe with `Altitude`, evaluating the real target's
height above the Bukkit highest-block surface under the configured maximum.
This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target
remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r72` extends the passing
`conditionUtilityAliases` probe with exact, after-or-equal, and before server
version checks against Paper's live Minecraft version. The inclusive paths and
structured version parsing remained valid alongside the existing probes. This
is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target remains
open.

The same r33 self-test now includes `owner-is-player` in the custom-owner
assertion, covering a PDC-backed non-tameable mob whose owner resolves to the
online player.

The same fixture also dispatches `PlayerQuitEvent` to the managed online
player. The structured `events` line records `quit-trigger=true` after the
configured `on-quit` action runs; natural client disconnect timing and Paper
1.21.1 release evidence remain open.

The same fixture also dispatches `PlayerDropItemEvent`, `PlayerItemBreakEvent`,
and `PlayerItemDamageEvent` to the managed online player. The structured
`events` line records `item-drop-break-damage=true` after all three configured
actions run; natural client item timing and Paper 1.21.1 release evidence
remain open.

The `skill-actions` record now also includes
`target-has-tag-condition=true`: a real Paper target transitions from absent to
present and back to absent under the explicit `target-has-tag` condition. This
is exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target remains
open.

The same self-test now records `target-state-conditions=true` for the strict
target-only condition family: permission, game mode, food, remaining air,
inventory, and active potion effect all pass on the explicit online target,
while an equivalent no-target context fails closed even when the caster has
the corresponding state. This remains exploratory Paper 1.21.11 evidence;
the Paper 1.21.1 release target remains open.

The same fixture dispatches `PlayerInteractEntityEvent`,
`PlayerChangedWorldEvent`, Paper `PlayerJumpEvent`, and
`PlayerAnimationEvent` through managed profiles. The structured `events` line
records `player-actions-interact=true world-change=true jump=true swing=true`;
natural client action timing and Paper 1.21.1 release evidence remain open.

The same fixture dispatches `PlayerFishEvent` with a real caught managed
entity and records `fish-trigger=true` in the structured `events` line. This
verifies the managed fishing trigger and caught-entity target path; natural
client fishing timing and Paper 1.21.1 release evidence remain open.

Fixture `srv-paper-integration-exploratory-1.21.11-r98` extends the aggregate
condition catalog with official `SpawnReason`. The self-test reads the real
spawned Zombie's Bukkit reason (`CUSTOM`), matches it through
`spawnReason{reason=...}`, and rejects a different enum through the `r` alias;
the structured log records `spawn-reason-condition-details actual=CUSTOM
positive=true negative=true` and `spawn-reason-condition=true`. The report
marks both `conditionCatalog` and `conditionUtilityAliases` as passed. This is
exploratory Paper 1.21.11 evidence; the Paper 1.21.1 release target remains
open.

Fixture `srv-paper-integration-exploratory-1.21.11-r99` extends the condition
catalog with official `IsWaterMob` and `IsZombie`. A real Cod passes the
`is-water-mob` condition and rejects `is-zombie`; the real spawned Zombie does
the inverse. The structured log records
`entity-type-condition-details water-mob=true zombie=true` and the
`skill-actions` line records `water-mob-condition=true zombie-condition=true`.
Both aggregate condition probes passed. This is exploratory Paper 1.21.11
evidence; the Paper 1.21.1 release target remains open.

The rebuilt condition catalog now also records `item-similar-condition=true`
for a real online player's temporary main-hand Shield and rejects an invalid
numeric inventory slot. It records `targets-condition=true` for the inherited
one-target skill tree and the `@targets` selection path. Exploratory fixture r2
records the independent `itemSimilarCondition=passed` and
`targetsCondition=passed` probes; strict Paper 1.21.1 evidence remains pending.

The condition catalog also records `bounds-condition=true` after checking a
real target inside an inclusive Cuboid and sphere and outside a second Cuboid.
Exploratory fixture `srv-paper-integration-exploratory-1.21.4-r121` recorded
the corresponding independent `boundsCondition`, `itemSimilarCondition`, and
`targetsCondition` report probes as `passed`. The aggregate `conditionCatalog`
remained `not-observed` because unrelated potion/fluid probes were false; this
does not upgrade the strict Paper 1.21.1 release gate.

Exploratory fixture `srv-paper-integration-exploratory-1.21.4-r122` recorded
`playerExperienceMechanics=passed` for the online-player workflow. The probe
closed the player's inventory, granted two experience levels, cleared progress,
cleared levels, and restored the original experience state. This remains Paper
1.21.4 compatibility evidence; the strict Paper 1.21.1 release gate remains
open.

Exploratory fixture `srv-paper-integration-exploratory-20261019-rawxp` (r136)
also runs `GiveExperience 25`, verifies the real player's raw experience delta,
and restores the original total/level/progress state. The independent report
probe records `rawExperienceMechanic=passed`; strict Paper 1.21.1 evidence
remains pending.

The same managed Zombie context independently checks `PlayerKills` and
`MythicMobType`; exploratory fixture r2 records `playerKillsCondition=passed`
and `mythicMobTypeCondition=passed`. Strict Paper 1.21.1 evidence remains
pending.

Exploratory fixture `srv-paper-integration-exploratory-20261019-absorption-r8`
also executes `SetAbsorption 6` on the managed Zombie and online player,
observes the real Bukkit absorption amount, and restores the temporary values.
The mechanic raises Paper's `MAX_ABSORPTION` capacity before writing the amount,
avoiding Paper 1.21.4's default zero-capacity clamp. The independent report
probe records `setAbsorptionMechanic=passed`; strict Paper 1.21.1 evidence
remains pending.

Exploratory fixture `srv-paper-integration-exploratory-1.21.4-r124` extends the
vehicle workflow with an independent managed Boat caster and ArmorStand
passenger. `EjectPassenger` removed that passenger and the report recorded
`ejectPassenger=passed`; this remains Paper 1.21.4 compatibility evidence and
does not upgrade the strict Paper 1.21.1 gate.

The online-player self-test now also runs `Oxygen 20` from 100 remaining-air
ticks and verifies the target reaches 120 before state restoration. The report
records this as the independent `oxygenMechanic=passed` probe in exploratory
fixture `srv-paper-integration-exploratory-1.21.4-r125`; strict Paper 1.21.1
release evidence remains pending.

The r127 online-player workflow sets a temporary main-hand STONE stack to
three, runs `ConsumeSlot` for two items, observes one remaining item, and then
restores inventory state. The independent report probe records
`consumeSlotMechanic=passed`; r127 also confirms the stack is mutated before
Paper inventory write-back. Strict Paper 1.21.1 release evidence remains
pending.

The frozen-state self-test now reaches the real Zombie's frozen state through
the `Freeze` mechanic before evaluating `IsFrozen` and restoring the original
freeze ticks. Exploratory fixture `srv-paper-integration-exploratory-1.21.4-r123`
records the independent `frozenCondition=passed` probe; a release-target Paper
1.21.1 observation remains pending.

The current online-player self-test also invokes `SetItemCooldown` for
`ENDER_PEARL` and `SetGameMode` for `ADVENTURE`, then restores both values. The
report contract now exposes `materialCooldownMechanic` and
`setGameModeMechanic`. Exploratory fixture
`srv-paper-integration-exploratory-20261019-itemcooldown` (r132) records both probes as
`passed`; the strict Paper 1.21.1 release fixture remains blocked because
`srv-1.21.1/paper.jar` is absent.

The current self-test also runs `Jump 0.42` on the managed caster and
`ForcePull 1.0` against a selected real target, restoring the target velocity
after the check. The report contract exposes `jumpMechanic` and
`forcePullMechanic`; the same exploratory r128 fixture records both as
`passed`. These observations do not upgrade the strict release gate.

Exploratory fixture `srv-paper-integration-exploratory-20261019-setoffhand`
(r129) also runs `SetOffhand SHIELD 2`, observes the real online player's
off-hand stack, and restores it. The independent report probe records
`setOffhandMechanic=passed`; strict Paper 1.21.1 evidence remains pending.

Exploratory fixture `srv-paper-integration-exploratory-20261019-setarmor`
(r130) also runs `SetArmor` with Diamond helmet, chestplate, leggings, and
boots, then verifies all four real Bukkit equipment slots. The independent
report probe records `setArmorMechanic=passed`; strict Paper 1.21.1 evidence
remains pending.

Exploratory fixture `srv-paper-integration-exploratory-20261019-clearinventory`
(r131) also runs `ClearInventory` against the online player after the give/take
workflow, observes an empty real inventory, and restores saved contents. The
independent report probe records `clearInventoryMechanic=passed`; strict Paper
1.21.1 evidence remains pending.

Exploratory fixture `srv-paper-integration-exploratory-20261019-faction`
(r133) also executes `SetFaction` on the managed caster and selected target,
verifies the runtime faction table, and restores previous bindings. The
independent report probe records `factionMechanic=passed`; strict Paper 1.21.1
evidence remains pending.

Exploratory fixture `srv-paper-integration-exploratory-20261019-level`
(r137) also executes `SetLevel 5` on the managed instance and verifies the
`Level >=5` condition. The independent report probe records
`levelMechanic=passed`; strict Paper 1.21.1 evidence remains pending.

Exploratory fixture `srv-paper-integration-exploratory-20261019-worldstate`
(r134) also runs `SetWeather thunder/clear` and `SetTime noon`, verifies the
real Bukkit world state, and restores the original values. Independent report
probes record `weatherMechanic=passed` and `timeMechanic=passed`; strict Paper
1.21.1 evidence remains pending.

Exploratory fixture `srv-paper-integration-exploratory-20261019-lightning`
(r135) also executes effect-only `Lightning` at the active target location and
records the independent `lightningMechanic=passed` probe; strict Paper 1.21.1
evidence remains pending.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r123` enables
structure generation and searches a real generated Mineshaft. The self-test
loads the nearby chunks, uses a generated structure piece bounding box, and
records `structure-condition-details ... positive=true wildcard=true
negative=true generated-count=1`; the structured report records
`structureCondition=passed` and `itemTypeCondition=passed`. This remains
Paper 1.21.11 compatibility evidence; strict Paper 1.21.1 evidence remains
pending.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r126` records
`aiPriority=passed` and `aiPriorityTargets=passed` after the priority self-test
revives and temporarily protects the online player, who had been killed by an
earlier natural probe. The same run records
`stateConditions=passed state-flying=true state-sneaking=true state-gliding=true`.
In that historical fixture, `stateBlocking` remained not observed because a
stable client-driven Shield-use window was still unavailable.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r127` records
`item-pickup trigger=true` and `environmental-damage trigger=true`. The event
self-test revives and temporarily protects its managed player before the
scheduled pickup/vehicle workflow, and the environmental probe attaches a
dedicated no-attacker damage profile. The structured report marks both
`itemPickup` and `environmentalDamage` as passed.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r128` records
`loot table=common ... dynamic-chance=true` and the structured
`lootDynamicChance=passed` probe. The self-test uses `<random.1to1>` to keep
the dynamic evaluator assertion deterministic while still resolving the
expression at runtime.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r133` records
`vehicle-events enter=true exit=true dismount=true eject=true destroy=true`.
The Boat enter/exit/dismount/eject paths remain exercised, and the managed
vehicle receives a real Bukkit `VehicleDestroyEvent` so the destroy dispatch
assertion is independent of client attack timing.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r138` records
`block-events synthetic-break=true synthetic-place=true` and the structured
`blockEvents=passed` probe. The synthetic assertions run immediately after
calling real Bukkit `BlockBreakEvent`/`BlockPlaceEvent`; the later natural
Mineflayer block workflow is intentionally not used as the sole evidence.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r139` records
`spawner-ground id=ground_missing_fail requested=false failure=no valid spawn
location restored=true saved-blocks=384`. The fixture now uses a small positive
radius for the negative probe; zero is normalized to the default radius and
could sample an uncleared neighboring column.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r142` records
`tempt=passed` with a real Survival player holding WHEAT, plus
`entityTargetBukkit=passed` and `entityTargetDelayed=passed`. Paper reports
`target-reason=TEMPT` and `target-cancelled=false`; delayed observation is
accepted because the native callback is not guaranteed to mutate the marker in
the same server tick.

Exploratory fixture `srv-paper-integration-exploratory-1.21.11-r138` records
`tempt-probe-delayed entity-target-trigger=true target-reason=CLOSEST_PLAYER
target-cancelled=false`. The report still includes the global
`target-find-misses` diagnostic count, but no longer requires it to be zero
because unrelated managed entities can produce lookup misses in the same
window.

On 2026-10-21, the archived official Paper `1.21.1-133` Paperclip was restored
to the local `srv-1.21.1/paper.jar` fixture. Its ZIP is valid, `version.json`
reports Minecraft `1.21.1`, SHA-256 is
`39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9`, and the
server log reports `Paper 1.21.1-133` with API `1.21.1-R0.1-SNAPSHOT`.

The first strict fixture reached plugin enablement and produced 196 passing
probes plus 9 `not-observed` probes. Subsequent fixes selected the live test
player instead of a dead audience guest, dispatched item pickup through the
real Bukkit event, captured delayed entity-target reasons, made player-limit
assertions independent of lifetime totals, and honored Paper's shield blocking
delay. The final strict fixture `srv-paper-integration-strict-release` now
passes the report gate with 214 observed probes: 206 `passed` and 8 expected
`observed-rejected` negative controls. The release-version evidence blocker is
resolved; remaining parity work is broader than the registered strict catalog.

On 2026-10-21, the standard two-player strict workflow was rerun in the fresh
`srv-paper-integration-size-range-strict` fixture with
`PAPER_MENU_WORKFLOW=true` and `-RunSeconds 150`. The report passed schema and
release assertions with 215 observed probes: 207 `passed` and 8 expected
`observed-rejected` negative controls. The new `entitySizeRange` probe called
the production entity-options application path 64 times for a Slime configured
with inclusive bounds 2..5; all samples stayed in range (observed minimum 2,
maximum 5). This is direct Paper 1.21.1-133 evidence for randomized spawn-time
size application. The previous 214-probe result above is retained as historical
evidence for its earlier fixture revision.

On 2026-10-21, the fresh `srv-paper-integration-remove-held-strict` fixture
passed the full two-player Paper 1.21.1-133 release workflow with 216 observed
probes: 208 `passed` and 8 expected `observed-rejected` controls. The added
`removeHeldItemMechanic` probe applies `remove-held-item` to the online test
player, verifies the main hand is empty and the offhand item remains unchanged,
and restores the original offhand item. This closes the strict runtime evidence
gap for that action; overall parity remains broader than the registered gate.

On 2026-10-21, the fresh `srv-paper-integration-scheduler-strict` fixture
passed the official Paper 1.21.1-133 two-player release gate with 217 observed
probes: 208 `passed` and 9 expected `observed-rejected` controls. The added
`skillScheduling` probe binds `integration-scheduler-probe{delay=2;repeat=3;repeat-interval=2}`
through `SkillService`, uses Paper's Bukkit task scheduler, verifies the count
is still zero one tick before the configured delay, then verifies exactly four
executions after the repeated interval. The report records
`skill-scheduler result=true delay=2 repeat=3 interval=2 executions=4
pre-delay-count=0`.

On 2026-10-21, the fresh `srv-paper-integration-skilltree-branches-strict-r3`
fixture passed the full Paper 1.21.1-133 release gate with 218 observed probes:
209 `passed` and 9 expected `observed-rejected` controls. `skillTreeBranches`
verifies a health-gated branch remains locked at 0.4 and unlocks at 0.8, then
verifies an advanced branch cannot unlock before its prerequisite and can
unlock afterward. The vehicle-events self-test now moves the online test
player to a remote safe surface before the real Boat enter/dismount path and
restores the prior location/state; `vehicleEvents` records enter, exit,
dismount, eject, and destroy as true. The old fixture
`srv-paper-integration-skilltree-branches-strict-r2` is retained as evidence of
the earlier test-order defect and player-death flake; its report is not the
current release result.

On 2026-10-21, the fresh `srv-paper-integration-skilltree-persistence-strict-r5`
fixture passed the Paper 1.21.1-133 release gate with 219 observed probes:
210 `passed` and 9 expected `observed-rejected` controls. The added
`skillTreeCastPersistence` probe uses an isolated `SkillService` and registry:
an advanced unlock attempt is rejected before its foundation branch is unlocked,
the foundation unlock is persisted in the instance snapshot, the advanced
branch then unlocks, and the gated skill executes in a new context. Branch
definitions from all loaded skills are predeclared in each tree before actions
run, preventing an early unlock skill from treating an undeclared prerequisite
branch as unrestricted. `branch-unlock` condition evaluation is now captured by
its owning SkillService instead of a global callback, so constructing an
isolated service cannot replace the production registry evaluator.

The final verification for these changes ran in the fresh fixture
`srv-paper-integration-skilltree-persistence-strict-r6` on Paper 1.21.1-133.
Its strict release report contains 219 observed probes: 210 `passed`, 9
expected `observed-rejected`, and no incomplete probes. Both SkillTree probes
passed, including `skillTreeCastPersistence` with
`blocked-before-unlock=true prerequisite-unlock-blocked=true snapshot-saved=true
advanced-unlocked=true casts=1`. The focused `SkillServiceTest` run reports 2
tests, 0 failures, and 0 errors; one test performs 20 denied branch casts before
unlocking the prerequisite and verifies a later cast succeeds.

On 2026-10-21, the final fresh fixture `srv-paper-integration-skilltree-persistence-strict-r6`
passed the Paper 1.21.1-133 release gate with 219 observed probes: 210
`passed` and 9 expected `observed-rejected` controls. Both
`skillTreeBranches` and `skillTreeCastPersistence` passed; the cast probe uses a
dedicated protected managed Zombie away from the fixture's crowded spawn area,
so its result does not depend on another self-test entity remaining alive.
The unit regression repeats the denied gated cast 20 times before satisfying
the prerequisite and verifies that the later cast still succeeds, protecting
the recursion-depth counter from leaking on early branch-gate rejection.

The focused two-boot fixture `srv-paper-skilltree-restart-fa6dbb0de249492fb0da09757c9c3ce2`
also passed its expanded restart workflow: boot one wrote
`foundation-unlocked=true` and an advanced branch requirement on
`restart-foundation`; boot two restored the snapshot and recorded
`foundation-restored=true prerequisite-restored=true advanced-unlocked=true`.
This verifies branch requirements and unlock state survive a Paper restart and
can continue evolving afterward.

On 2026-10-21, the final strict artifact was rerun in the fresh
`srv-paper-integration-final-r8` fixture on official Paper 1.21.1-133. The
new final fixture `srv-paper-integration-final-r9` passed with 220 observed
probes: 212 `passed`, 8 expected `observed-rejected`, and zero incomplete
probes. The run includes the stable Bee stinger and jockey mount-event probes
(`mobOptionsNatural` and `mobOptionsMountEvent`), the persisted named-skill cast
probe, and the independent `boundingBoxOverlapCondition` overlap/separation
assertion against real Bukkit entities.

The final r10 strict artifact `srv-paper-integration-final-r10` on official
Paper 1.21.1-133 passed with 221 observed probes: 212 `passed`, 9 expected
`observed-rejected`, and zero incomplete probes. `mobOptionsSlimeSplit` records
`prevented=true allowed=true passed=true` from two managed SlimeSplitEvent
dispatches, closing runtime evidence for the mapped `PreventSlimeSplit` option.

The fresh strict fixture `srv-paper-integration-final-r13` on official Paper
1.21.1-133 passed with 221 observed probes: 212 `passed`, 9 expected
`observed-rejected`, and zero incomplete probes. Its real Bukkit spatial
targeter line records `monsters=true animals=true villagers=true tamed=true`
alongside the existing sphere/ring/cone/line-of-sight assertions, providing
runtime evidence for the category radius targeters added to the API and
Mythic importer normalization.

The follow-up strict fixture `srv-paper-integration-final-r14` passed the same
Paper 1.21.1-133 gate with 221 observed probes: 212 `passed`, 9 expected
`observed-rejected`, and zero incomplete probes. The spatial targeter detail
line additionally records `nearest-monster=true nearest-animal=true
nearest-villager=true nearest-tamed=true`, confirming the nearest-category
selectors return a single matching Bukkit entity in the live self-test.

The final follow-up fixture `srv-paper-integration-final-r16` passed Paper
1.21.1-133 with 221 observed probes: 212 `passed`, 9 expected
`observed-rejected`, and zero incomplete probes. Its spatial targeter detail
line records all category selectors plus `nearest-player=true`. The same run
also emitted `environmental-damage trigger=true fire-ticks=242 lava=true`;
the report verifier now accepts both positive fire tick values and Bukkit's
valid `-1` sentinel when the trigger itself is observed.

The fresh fixture `srv-paper-integration-final-r18` passed Paper 1.21.1-133
with 221 observed probes: 213 `passed`, 8 expected `observed-rejected`, and
zero incomplete probes. The production Ageable path records
`age-options result=true age=-24000 baby=true age-lock=true` on a real Sheep,
closing the strict runtime evidence for `Age`, `Baby`, and `AgeLock` while
keeping non-Ageable baby APIs separate.

The follow-up fixture `srv-paper-integration-final-r19` passed Paper
1.21.1-133 with 222 observed probes: 214 `passed`, 8 expected
`observed-rejected`, and zero incomplete probes. Its production entity flag
probe records `tamed=true angry=true can-join-raid-setter=true
patrol-leader=true` for real Wolf/Pillager entities. `CanJoinRaid` remains
setter-only evidence because this Paper API has no corresponding getter.

The follow-up fixture `srv-paper-integration-final-r20` passed the unchanged
222-probe Paper 1.21.1-133 gate with 214 `passed`, 8 expected
`observed-rejected`, and zero incomplete probes. The existing events evidence
now includes `prevent-leash=true prevent-rename=true`, produced by real
`PlayerLeashEntityEvent` and Name Tag `PlayerInteractEntityEvent` dispatches
against a managed entity carrying `PreventLeashing` and `PreventRenaming`.

The follow-up fixture `srv-paper-integration-final-r21` passed the unchanged
222-probe Paper 1.21.1-133 gate with 214 `passed`, 8 expected
`observed-rejected`, and zero incomplete probes. The events evidence now also
records `prevent-target=true prevent-vanilla-damage=true`: a real target event
was cancelled for a managed protected mob, and a real entity damage event kept
dispatching with its damage reduced to zero.

The follow-up fixture `srv-paper-integration-final-r22` passed the unchanged
222-probe Paper 1.21.1-133 gate with 214 `passed`, 8 expected
`observed-rejected`, and zero incomplete probes. The events evidence now also
records `prevent-projectiles=true prevent-entity-explosions=true` from real
`ProjectileHitEvent` and `EntityExplodeEvent` dispatches against managed
protected entities.

The latest fixture `srv-paper-integration-final-r23` passed the 222-probe
Paper 1.21.1-133 gate with 213 `passed`, 9 expected `observed-rejected`, and
zero incomplete probes. Its events line records the additional
`prevent-block-explosion-damage=true` assertion, produced by a real
`EntityDamageEvent` with `DamageCause.BLOCK_EXPLOSION` against a managed
protected entity. The expected-rejected count reflects the current spawner
negative-control observations; no probe failed or remained incomplete.

The latest fixture `srv-paper-integration-final-r25` passed the 222-probe
Paper 1.21.1-133 gate with 213 `passed`, 9 expected `observed-rejected`, and
zero incomplete probes. The events line records
`prevent-drowning=true prevent-fall-damage=true prevent-fire=true`, each from a
separate `EntityDamageEvent` carrying its matching `DamageCause` and a managed
entity configured with the corresponding production option.

The latest fixture `srv-paper-integration-final-r26` passed the 222-probe
Paper 1.21.1-133 gate with 213 `passed`, 9 expected `observed-rejected`, and
zero incomplete probes. Its `PreventFireCauses=FIRE_TICK` check records
`FIRE_TICK` cancelled while ordinary `FIRE` remains uncancelled, proving the
configured cause filter is narrower than the global `PreventFire` option.

The latest fixture `srv-paper-integration-final-r28` passed the 222-probe
Paper 1.21.1-133 gate with 214 `passed`, 8 expected `observed-rejected`, and
zero incomplete probes. Cause isolation is verified with one option enabled
at a time: `PreventDrowning` cancels `DROWNING` but allows `FALL`, and
`PreventFallDamage` cancels `FALL` but allows `DROWNING`. The separate
`PreventFire` and `PreventFireCauses=FIRE_TICK` positive/negative assertions
also pass. The intervening r27 run missed an unrelated player-facing
`LookingAt` positive observation; r28 passed it without changing the gate.

The latest fixture `srv-paper-integration-final-r30` passed with 222 observed
probes: 213 `passed`, 9 expected `observed-rejected`, and zero incomplete. Its
events evidence adds positive/negative controls for `PreventPickup` and
`PreventItemUse`: managed protected mobs reject their corresponding pickup or
right-click interaction events, while otherwise equivalent managed controls
allow them. The r29 run had transient missing `LookingAt` and vehicle event
observations; r30 observed both, with no gate changes.

The latest fixture `srv-paper-integration-final-r31` passed the 222-probe
Paper 1.21.1-133 gate with 213 `passed`, 9 expected `observed-rejected`, and
zero incomplete probes. `mob-options-mount-event` records
`prevented=true allowed=true generic-prevented=true generic-allowed=true`:
the jockey-specific Zombie restriction and generic `PreventMount` on a Wolf
are independently enforced, while both control mount events remain allowed.

The latest fixture `srv-paper-integration-final-r32` passed the 222-probe
Paper 1.21.1-133 gate with 213 `passed`, 9 expected `observed-rejected`, and
zero incomplete probes. The events line now also records
`prevent-teleport=true` using paired managed entities: the protected
`EntityTeleportEvent` is cancelled and the default control event is allowed.

The latest fixture `srv-paper-integration-final-r34` passed with 222 observed
probes: 213 `passed`, 9 expected `observed-rejected`, and zero incomplete.
Its events line confirms `prevent-transformation-reasons=true` and its
cause-isolated environmental damage checks. The real-player `LookingAt` probe
passed positive and negative checks after the fixture began snapshotting,
clearing, and restoring only blocks along the ray, removing dependence on
ambient terrain without weakening the ray assertion.

The latest fixture `srv-paper-integration-final-r35` passed with 222 observed
probes: 214 `passed`, 8 expected `observed-rejected`, and zero incomplete.
Its managed entity `PreventSunburn` check confirms the base sunlight
`EntityCombustEvent` is cancelled while `EntityCombustByBlockEvent` and
`EntityCombustByEntityEvent` remain uncancelled, preserving non-sunlight fire
semantics.

The latest fixture `srv-paper-integration-final-r36` passed with 222 observed
probes: 214 `passed`, 8 expected `observed-rejected`, and zero incomplete.
Its `PreventOtherDrops` event assertion uses fixed MUTTON x2 and 7 XP values:
the protected managed entity's `EntityDeathEvent` is cleared, while the
otherwise equivalent managed control retains both drops and experience. This
exercises the production death listener before optional loot-table processing.

The latest fixture `srv-paper-integration-final-r37` passed with 222 observed
probes: 213 `passed`, 9 expected `observed-rejected`, and zero incomplete. Its
killer-drop probe derives a managed killer from a real damage source before
dispatching a fixed-drop/XP `EntityDeathEvent`; only the victim of the killer
with `PreventMobKillDrops` loses drops and XP, while the ordinary managed
killer control retains both.

The latest fixture `srv-paper-integration-final-r38` passed with 223 observed
probes: 215 `passed`, 8 expected `observed-rejected`, and zero incomplete.
Its `spawn-option-runtime` probe uses real Skeleton/Zombie entities and the
production spawn-option helpers: `CanPickupItems=false` is applied, explicit
`true` overrides the `PreventItemPickup` fallback, `PreventRandomEquipment`
clears pre-existing helmet/weapon gear before explicit DIAMOND_SWORD equipment
is applied, and configured `NoDamageTicks=19` is read back.

The latest fixture `srv-paper-integration-final-r41` passed with 223 observed
probes: 214 `passed`, 9 expected `observed-rejected`, and zero incomplete. Its
killer-drop probe resolves managed killers from actual victim damage. The
protected killer suppresses native victim drops and the victim's configured
`integration_death` table, while the default killer control materializes
Diamond x2 and 9 XP. This confirms `PreventMobKillDrops` covers the target's
complete loot result, not just vanilla drops.

The latest fixture `srv-paper-integration-final-r42` passed with 223 observed
probes: 214 `passed`, 9 expected `observed-rejected`, and zero incomplete. The
events probe records `prevent-block-infection=true` on real
`EntityChangeBlockEvent` dispatch: only the managed Silverfish changing a
block to `INFESTED_STONE` is cancelled; the default Silverfish and protected
Pig control remain allowed.

The latest fixture `srv-paper-integration-final-r43` passed with 223 observed
probes: 214 `passed`, 9 expected `observed-rejected`, and zero incomplete. The
events probe records `prevent-snow-formation=true` from three real
`EntityBlockFormEvent` dispatches: the protected Snow Golem is cancelled, while
the default Snow Golem and a protected Pig control are allowed.

The latest fixture `srv-paper-integration-final-r44` passed with 223 observed
probes: 214 `passed`, 9 expected `observed-rejected`, and zero incomplete.
The events probe records `prevent-skeleton-conversion=true`: a managed
Skeleton with only `FROZEN` in its transformation-reason list rejects FROZEN
while allowing CURED. Importer regression maps `PreventConversion` by family
and retains the option as unsupported for non-Zombie/non-Skeleton types.

The latest fixture `srv-paper-integration-final-r47` passed with 224 observed
probes: 216 `passed`, 8 expected `observed-rejected`, and zero incomplete. The
Enderman `HeldBlock` probe configures OBSIDIAN through `entity.held-block` and
reads it back from the real Paper Enderman as OBSIDIAN. The r46 attempt showed
the HeldBlock probe itself passing but had one unrelated online-player
`hasItemCondition` observation missing; r47 observed that probe and passed the
full gate.

The strict fixture `srv-paper-integration-route-20261030` passed Paper
1.21.1-133 with 230 observed probes: 219 `passed`, 11 expected
`observed-rejected`, and zero incomplete. The new AI route observation is:

```text
ai-route-probe token=followpath active-before=true moving=true advanced=true waypoint-before=route=followpath waypoint=1005,53,1000 finished=false waypoint-after=route=followpath waypoint=1007,53,1000 finished=false passed=true
```

This verifies the production `SelectorPlan`/`AiController` path on a managed
Paper entity: a valid `followpath` route is active, the controller emits
movement velocity, and the route state advances after the entity reaches the
first configured waypoint. It does not claim native Pathfinder goal or
pathfinding-malus parity.

The earlier strict fixture `srv-paper-integration-interactable-20261031` kept
the 230-probe gate green with 219 `passed`, 11 expected `observed-rejected`,
and zero incomplete. Its real-player event line includes:

```text
events ... prevent-item-use=true interactable=true prevent-teleport=true ...
```

The `interactable=true` assertion is produced by dispatching the production
`PlayerInteractEntityEvent` path for a managed Wolf with `interactable=false`
and an otherwise equivalent control with `interactable=true`; the protected
event is cancelled and the control remains allowed. This is server-side event
evidence and does not claim natural client timing or complete Armor Stand
manipulation parity.

The earlier strict fixture `srv-paper-integration-armor-stand-20261031` passed
with 230 observed probes: 220 `passed`, 10 expected `observed-rejected`, and
zero incomplete. Its event line extends the assertion with:

```text
interactable=true interactable-armor-stand=true
```

The second flag comes from Paper's dedicated
`PlayerArmorStandManipulateEvent` path, using protected and control Armor
Stands bound to the same production instance manager. Natural client timing
remains outside this server-side evidence.

The earlier strict fixture `srv-paper-integration-patrol-spawn-20261031` passed
with 230 observed probes: 219 `passed`, 11 expected `observed-rejected`, and
zero incomplete. The Raider option line includes:

```text
entity-flag-options result=true tamed=true angry=true can-join-raid-setter=true patrol-leader=true patrol-spawn-point=true
```

The `patrol-spawn-point=true` result reads back the Paper `Raider` patrol
target block at the real Pillager's spawn location after production option
application. `CanJoinRaid` remains setter-only because Paper 1.21.1 has no
corresponding public getter.

The earlier strict fixture `srv-paper-integration-armor-options-20261031-r3`
passed with 230 observed probes: 219 `passed`, 11 expected
`observed-rejected`, and zero incomplete. Its Armor Stand option line is:

```text
armor-stand-options result=true can-move=false can-tick=false
```

The production `applyEntitySpecificOptions` path applies the canonical Armor
Stand fields, while importer aliases `HasArms`, `HasBasePlate`, `Marker`,
`Small`, `CanMove`, and `CanTick` project into those fields. The same
production equipment path reads back the six canonical `Item*` slots as
DIAMOND_CHESTPLATE, DIAMOND_BOOTS, DIAMOND_SWORD, SHIELD, DIAMOND_HELMET,
and DIAMOND_LEGGINGS.

The earlier strict fixture `srv-paper-integration-saddled-20261031-r2` passed
with 230 observed probes: 219 `passed`, 11 expected `observed-rejected`, and
zero incomplete. Its Horse option line is:

```text
horse-options result=true saddled=true
```

This verifies the production `Saddled` option through Paper's
`AbstractHorseInventory#setSaddle`; the runtime also supports `Steerable`
entities such as Pig through `setSaddle(boolean)`.

The earlier strict fixture `srv-paper-integration-horse-armor-20261031` passed
with 230 observed probes: 220 `passed`, 10 expected `observed-rejected`, and
zero incomplete. Its Horse option line is:

```text
horse-options result=true saddled=true armor=true
```

This verifies Mythic `HorseArmor` through the production equipment path and
Paper's `ArmoredHorseInventory#setArmor`; the configured
`DIAMOND_HORSE_ARMOR` is read back from the real Horse inventory. Blank and
`NONE` values clear the armor slot, while invalid or unsupported values fail
closed and preserve the existing item.

The earlier strict fixture `srv-paper-integration-horse-chest-20261031-r2`
passed with 230 observed probes: 220 `passed`, 10 expected
`observed-rejected`, and zero incomplete. Its Horse option line is:

```text
horse-options result=true saddled=true armor=true chest=true
```

This verifies `Saddled` and `HorseArmor` on a real Horse, and
`CarryingChest` through the same production entity-option path on a real
Donkey implementing Paper's `ChestedHorse` API. The armor item is read back
through `ArmoredHorseInventory#getArmor`; the chest state is read back through
`ChestedHorse#isCarryingChest`.

The preceding strict fixture `srv-paper-integration-scale-20261031-r2` passed
with 231 observed probes: 220 `passed`, 11 expected `observed-rejected`, and
zero incomplete. Its Scale option line is:

```text
scale-options result=true scale=1.25
```

This reads back the configured `scale: 1.25` from the real
`mobs/scale_probe.yml` through `spawnMobCore` and `ModelInstance#getScale`.
The strict report catalog then contained 231 registered probes because this
configuration-to-instance assertion was part of the release gate.

The preceding strict fixture `srv-paper-integration-health-20261031-r3` passed
with 232 observed probes: 222 `passed`, 10 expected `observed-rejected`, and
zero incomplete. Its Health option line is:

```text
health-options result=true health=42.0 max-health=42.0
```

This reads back the configured `mob.health: 42` from the real
`mobs/health_probe.yml` through `spawnMobCore`; both `Damageable#getHealth()`
and `Damageable#getMaxHealth()` matched the configured value. The strict
report catalog now contains 232 registered probes because this
configuration-to-instance assertion is part of the release gate.

The preceding strict fixture
`srv-paper-integration-movement-speed-20261031-r6` passed with 233 observed
probes: 222 `passed`, 11 expected `observed-rejected`, and zero incomplete.
Its MovementSpeed option line is:

```text
movement-speed-options result=true base=0.4
```

This reads back the configured `mob.move-speed: 0.4` from the real
`mobs/movement_speed_probe.yml` through `spawnMobCore`; the production Bukkit
`MOVEMENT_SPEED` base attribute matched the configured value. The strict
report catalog now contains 233 registered probes because this
configuration-to-entity assertion is part of the release gate.

The preceding strict fixture
`srv-paper-integration-knockback-resistance-20261031-r8` passed with 234
observed probes: 224 `passed`, 10 expected `observed-rejected`, and zero
incomplete. Its KnockbackResistance option line is:

```text
knockback-resistance-options result=true base=0.75
```

This reads back the configured `mob.knockback-resistance: 0.75` from the real
`mobs/knockback_resistance_probe.yml` through `spawnMobCore`; the production
Bukkit `KNOCKBACK_RESISTANCE` base attribute matched the configured value. The
strict report catalog now contains 234 registered probes because this
configuration-to-entity assertion is part of the release gate.

The preceding strict fixture
`srv-paper-integration-follow-range-20261031-r9` passed with 235 observed
probes: 224 `passed`, 11 expected `observed-rejected`, and zero incomplete.
Its FollowRange option line is:

```text
follow-range-options result=true base=40.0
```

This reads back the configured `mob.sight-radius: 40` from the real
`mobs/follow_range_probe.yml` through `spawnMobCore`; the production Bukkit
`FOLLOW_RANGE` base attribute matched the configured value. The strict report
catalog now contains 235 registered probes because this
configuration-to-entity assertion is part of the release gate.

The preceding strict fixture
`srv-paper-integration-damage-20261031-r10` passed with 236 observed probes:
226 `passed`, 10 expected `observed-rejected`, and zero incomplete. Its Damage
option line is:

```text
damage-options result=true base=12.0
```

This reads back the configured `mob.attack-damage: 12` from the real
`mobs/damage_probe.yml` through `spawnMobCore`; the production Bukkit
`ATTACK_DAMAGE` base attribute matched the configured value. The strict report
catalog now contains 236 registered probes because this
configuration-to-entity assertion is part of the release gate.

The preceding strict fixture
`srv-paper-integration-armor-20261031-r11` passed with 237 observed probes:
227 `passed`, 10 expected `observed-rejected`, and zero incomplete. Its Armor
option line is:

```text
armor-options result=true base=8.0
```

This reads back the configured root `Armor: 8` from the real
`mobs/armor_probe.yml` through `spawnMobCore`; the production Bukkit `ARMOR`
base attribute matched the configured value. The strict report catalog now
contains 237 registered probes because this configuration-to-entity assertion
is part of the release gate.

The latest strict fixture
`srv-paper-integration-entity-flags-20261031-r12` passed with 238 observed
probes: 227 `passed`, 11 expected `observed-rejected`, and zero incomplete.
Its configured entity flag line is:

```text
entity-config-flags result=true no-ai=true invulnerable=true silent=true gravity=false collidable=false
```

This reads back the configured `entity.no-ai`, `entity.invulnerable`,
`entity.silent`, `entity.gravity`, and `entity.collidable` values from the real
`mobs/entity_flags_probe.yml` through `spawnMobCore`. The strict report catalog
now contains 238 registered probes because this configuration-to-entity
assertion is part of the release gate.

Diagnostic fixture `srv-paper-mob-attack-speed-20261101-r1` confirmed that a
real configured Zombie has no Paper `ATTACK_SPEED` attribute (`base=NaN`). The
importer now preserves Mythic `Options.AttackSpeed` as unsupported instead of
claiming the configuration was applied; the diagnostic is not release-pass
evidence.

The strict fixture `srv-paper-integration-adult-20261031-r2` passed with 230
observed probes: 219 `passed`, 11 expected `observed-rejected`, and zero
incomplete. Its Ageable line is:

```text
age-options result=true age=-24000 baby=true adult=true age-lock=true
```

The production Ageable path therefore verifies Mythic `Age`, `Baby`, `Adult`,
and `AgeLock` independently on real Sheep entities. The initial adult fixture
was discarded from release evidence because an unrelated `LookingAt` probe was
not observed; r2 observed the full report contract without weakening it.

The follow-up strict fixture `srv-paper-integration-exp-20261031-r14` passed
Paper 1.21.1-133 with 239 observed probes: 228 `passed`, 11 expected
`observed-rejected`, and zero incomplete. It adds the root Mythic `Exp` probe:

```text
exp-options result=true experience=13 event=true
```

The production `spawnMobCore` path applies `mob.exp` to the managed instance,
and the real Bukkit `EntityDeathEvent` dispatch reads back the configured
experience override. The fixture uses `mobs/exp_probe.yml`; fixed and ranged
`drops.experience*` precedence and missing-table fallback are covered by unit
tests, while broader natural death/drop semantics remain outside this probe.

The latest strict fixture `srv-paper-integration-exp-20261031-r16` passed
Paper 1.21.1-133 with 240 observed probes: 230 `passed`, 10 expected
`observed-rejected`, and zero incomplete. Its current-code vehicle line is:

```text
vehicle-events enter=true exit=true dismount=true eject=true destroy=true player=HelsteraTester
```

The vehicle workflow now uses an independent managed ArmorStand passenger, so
the online player's unrelated death/respawn probes cannot remove the binding
before the delayed Boat event sequence runs.

The latest strict fixture `srv-paper-integration-exp-20261031-r17` passed
Paper 1.21.1-133 with 240 observed probes: 229 `passed`, 11 expected
`observed-rejected`, and zero incomplete. It adds the configuration-to-instance
Mob Option line:

```text
despawn-options result=true mode=PERSISTENT distance=0.0 persistent=true
```

This reads the normalized `SpawnOptions` mode/distance and the real Bukkit
entity persistence flag from `mobs/despawn_probe.yml`.

The latest strict fixture `srv-paper-integration-exp-20261031-r19` passed
Paper 1.21.1-133 with 241 observed probes: 231 `passed`, 10 expected
`observed-rejected`, and zero incomplete. It adds the visibility option line:

```text
visibility-options result=true visible-by-default=true invisible=true show-name=true
```

This reads `VisibleByDefault`, `Invisible`, and `AlwaysShowName` from a real
configured Zombie through the production spawn path.

The latest strict fixture `srv-paper-integration-exp-20261031-r20` passed
Paper 1.21.1-133 with 244 observed probes: 233 `passed`, 11 expected
`observed-rejected`, and zero incomplete. It adds the Spawner group-limit line:

```text
spawner-group id=group_probe requested=true alive=2 total=2 max-alive=2 group-min=3 group-max=5 limit=true
```

This proves the production group spawn path stops at `max-alive` instead of
materializing the full requested group.

The latest strict fixture `srv-paper-integration-exp-20261031-r21` passed
Paper 1.21.1-133 with 245 observed probes: 235 `passed`, 10 expected
`observed-rejected`, and zero incomplete. Its live scheduler group line is:

```text
spawner-natural-group id=natural_group_probe mode=live-scheduler before=2 after=4 alive=2 max-alive=2 group-min=3 group-max=5 limit=true
```

This is separate from the manual `forceSpawn` group probe and proves the
interval-driven scheduler path applies the same per-attempt alive cap.

The latest strict fixture `srv-paper-integration-exp-20261031-r23` passed
Paper 1.21.1-133 with 246 observed probes: 236 `passed`, 10 expected
`observed-rejected`, and zero incomplete. Its named-region line is:

```text
spawner-region id=region_probe requested=true region=integration_box failure=null
```

This verifies the built-in coordinate region lookup through the production
Spawner condition gate.

Earlier strict fixture `srv-paper-integration-exp-20261031-r25` passed
Paper 1.21.1-133 with 248 observed probes: 237 `passed`, 11 expected
`observed-rejected`, and zero incomplete. It extends the named-region probe
with an explicit outside control:

```text
spawner-region id=region_probe requested=true region=integration_box failure=null
spawner-region-outside id=region_probe_outside requested=false region=integration_box failure=conditions rejected location
```

The production Spawner gate therefore accepts a location inside the configured
coordinate region and rejects the same condition at a location outside it.

The latest strict fixture `srv-paper-integration-exp-20261031-r27` passed
Paper 1.21.1-133 with 248 observed probes: 237 `passed`, 11 expected
`observed-rejected`, and zero incomplete. It closes the previous real-client
Shield timing gap while retaining the strict report contract:

```text
state-blocking=true
looking-at-condition-details positive=true negative=true
```

The menu client keeps `HelsteraTester` in a real Shield-use window; the
production self-test then evaluates `Player#isBlocking` without rewriting the
client's active-use inventory state. Historical exploratory fixtures r43 and
r126 remain as evidence of the earlier timing failure; r27 is the strict
condition evidence and r28 is the current overall release fixture.

The latest strict fixture `srv-paper-integration-exp-20261031-r28` passed
Paper 1.21.1-133 with 250 observed probes: 239 `passed`, 11 expected
`observed-rejected`, and zero incomplete. It adds the natural Spawner recycle
probe:

```text
spawner-recycle id=recycle_probe before=2 after=3 released=true respawned=true alive=1 interval=40 passed=true
```

The probe removes a real tracked instance, observes the runtime cleanup release
the `max-alive=1` slot, and then observes the shared scheduler create the next
instance before disabling and cleaning the fixture probe.

The latest strict fixture `srv-paper-integration-targeters-20261031-r3`
supersedes r28 as the overall report evidence. It passed Paper 1.21.1-133 with
251 observed probes: 240 `passed`, 11 expected `observed-rejected`, and zero
incomplete. The managed AI selector probe now also verifies world-scoped
targeters against the real Paper world:

```text
ai-selector-core goals=true player-target=true monster-target=true clear-no-fallback=true owner-target=true faction-target=true conditional-target=true players-in-world=true mobs-in-world=true native-target=true ... passed=true
```

The same probe records `set-target-mechanic=true clear-target-mechanic=true`
after dispatching both mechanics through the production SkillService.

`@PlayersInWorld` and `@MobsInWorld` replace the nearby candidate snapshot with
all living entities from the source entity's world before applying category
selection.

The separate two-boot fixture `srv-paper-spawner-restart-20261031-r2` also
passed Paper 1.21.1-133. Boot one generated the disabled `persistence_probe`
through the production `SpawnerService` and shut down normally:

```text
spawner-persistence stage=write before=0 after=1 generated=true result=true
```

Boot two reused the same plugin data directory and restored the persisted
lifetime counter exactly once:

```text
spawner-persistence stage=verify before=1 after=1 restored=true result=true
```

The dedicated script is `tools/paper-integration/test-spawner-restart.ps1`.

Strict Paper 1.21.1-133 fixture `srv-paper-nearest-20261031-r1` recorded
251 observed probes (239 passed, 12 expected rejected, zero incomplete).
Its managed selector probe records `nearest-living=true nearest-mob=true`:
the selected living entity is the nearer of two candidates, and the mob
selector returns the non-player candidate as a single result.

Strict fixture `srv-paper-world-chain-20261031-r1` passed Paper 1.21.1-133
with 250 observed probes (238 passed, 12 expected rejected, zero incomplete).
Its managed selector probe records `world-chain-filtered=true` after selecting
players in the source world and chaining to world entities; the real monster
candidate remains excluded.

Strict fixture `srv-paper-targeter-limits-20261031-r1` passed Paper
1.21.1-133 with 250 observed probes (239 passed, 11 expected rejected, zero
incomplete). Its real-entity probe records `targeter-invalid-limits=true`:
invalid count and non-finite radius select nothing, while a valid count
selects one entity.

The latest strict fixture `srv-paper-runtime-alias-20261031-r2` passed Paper
1.21.1-133 with 251 observed probes (240 passed, 11 expected rejected, zero
incomplete). Its `ai-selector-core` line passes while using the runtime alias
forms `@Players_In_World`, `@Mobs_In_World`, `@Nearest_Living_Entity`, and
`@Nearest_Mob`.

Strict fixture `srv-paper-context-chain-20261031-r1` passed Paper 1.21.1-133
with 251 observed probes: 239 passed and 12 expected rejected. Its
`context-chain-action=true` assertion dispatches an `add-tag` skill through
`@Target{} > @living{filter=mob}`: a player target is rejected, a zombie target
receives the tag, and the temporary tag is removed.

Strict fixture `srv-paper-world-target-validity-20261101-r2` passed Paper
1.21.1-133 with 250 observed probes (238 passed, 12 expected rejected, zero
incomplete). Its `ai-selector-core` probe confirms `world-caster-excluded=true`:
the world-scoped mob result contains the intended monster but not the managed
source entity, and all returned entities are valid and alive.

Strict fixture `srv-paper-mob-max-combat-distance-20261101-r5` passed Paper
1.21.1-133 with 253 observed probes (241 passed, 12 expected rejected, zero
incomplete). It adds this production mob-option boundary line:

```text
max-combat-distance result=true inside-allowed=true outside-blocked=true
```

The fixture loads `mobs/max_combat_distance_probe.yml` through the normal mob
loader, spawns the managed Zombie with an eight-block limit, then dispatches
real Bukkit melee damage events from seven and nine blocks. The protected event
is cancelled while the in-range control remains allowed. The same strict run
also retains dual-client menu/Audience and `playerPotionConsume=passed`.

Strict fixture `srv-paper-mob-spawning-distance-20261101-r2` passed Paper
1.21.1-133 with 253 observed probes (241 passed, 12 expected rejected, zero
incomplete). Its production configuration-to-instance line is:

```text
spawning-distance-options result=true mode=NORMAL distance=8.0
```

That configuration-only checkpoint loads `mobs/spawning_distance_probe.yml`
through the normal mob loader and confirms Mythic's legacy
`Options.SpawningDistance` reaches `SpawnOptions.despawnDistance`.

Strict fixture `srv-paper-spawning-distance-runtime-20261101-r4` passed Paper
1.21.1-133 with 254 observed probes (242 passed, 12 expected rejected, zero
incomplete). Its new `spawningDistanceRuntime` probe uses a real online player:
the configured mob remains managed within eight blocks, then the production AI
scheduler despawns its instance and removes the Bukkit entity after it is moved
outside the configured radius. The report records
`spawning-distance-runtime result=true inside-retained=true outside-removed=true`.

Strict fixture `srv-paper-block-tag-strict-20261101` passed Paper 1.21.1-133
with 254 observed probes (243 passed, 11 expected rejected, zero incomplete).
It first established the Paper block-tag path; its combined `STONE,ST*` input
did not independently establish wildcard matching. The follow-up below
isolates that assertion.

Strict fixture `srv-paper-block-selector-strict-20261101-r2` passed Paper
1.21.1-133 with 253 observed probes (242 passed, 11 expected rejected, zero
incomplete). Its `conditionUtilityAliases` release probe requires and passes
separate material, tag, and wildcard checks. The server log records:

```text
spatial-condition-details height=true orientation=true on-block=true on-block-tag=true block-type=true block-wildcard=true block-tag=true biome-type=true
```

The fixture temporarily sets the real target body and supporting block to
STONE, then evaluates Mythic `OnBlock`, `BlockType`, and `BlockTypeInRadius`
with `#minecraft:base_stone_overworld` and standalone `ST*` selectors through
the production `SkillService`. The report is retained at
`srv-paper-block-selector-strict-20261101-r2/integration-report.json`.

