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
$env:PAPER_WEB_RELOAD_PROBE = 'true'
$env:PAPER_RAW_POTION_WORKFLOW = 'true'
$env:PAPER_RAW_POTION_SHORT = 'true'
$env:PAPER_SPAWNING_DISTANCE_WALK_WORKFLOW = 'true'
$env:PAPER_INTERACTABLE_WORKFLOW = 'true'
$env:PAPER_BEE_STINGER_ONLY = 'false'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 150 -Strict
```

For an isolated imported Bee stinger probe, the runner starts one idle client to
activate nearby entity AI without changing player state:

```powershell
$env:PAPER_BEE_STINGER_ONLY = 'true'
$env:PAPER_INTEGRATION_DIR = 'srv-paper-bee-stinger-isolated'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 75
```

Latest strict fixture `srv-paper-full-strict-20261107-r4` passes all 391
probes (372 passed, 19 expected rejections, zero failed or incomplete) on
Paper 1.21.1-133. It includes the effective `CanJoinRaid` control, native-AI
Bee `PreventStingerLoss` evidence, and native Zombie `NATURAL` jockey
mount cancellation/allowance alongside the registered Mythic parity catalog.
Report: `reports/paper-1.21.1-full-strict-20261107-r4.json`;
server log: `reports/paper-1.21.1-full-strict-20261107-r4.log.gz`;
client logs:
`reports/paper-1.21.1-full-strict-20261107-r4-client-logs.tar.gz`.
Report SHA-256: `8567db2f97d44eb87141a2792e9faaa1b97e1c2abe689888ad6034d26b74d785`;
server log SHA-256: `227fc40ca768bb8ae5aaa3dd3ad7db6e6e2e5c295ab0816751f28430c660a4ac`;
client log SHA-256: `a2462ce0a600ec565a582cf80640f3784479ab394ac3d3b4a8a8ba92937cb0f1`;
plugin SHA-256: `ae36884bdde4163dc213d4fc2a9fd3850f8eb365b7c4af3b4326570765bb02cc`.

Previous strict fixture `srv-paper-mythic-teleport-strict-20261107-r1` passes all
391 probes (372 passed, 19 expected rejections, zero failed or incomplete) on
Paper 1.21.1-133. It verifies imported Mythic `teleport`/`tp` for entity and
location targets, safe landing, `unsafe` bypass, bounded spread, preserved and
overridden orientation, and invalid-input preservation with warnings. Report:
`reports/paper-1.21.1-mythic-teleport-strict-20261107-r1.json`;
server log: `reports/paper-1.21.1-mythic-teleport-strict-20261107-r1.log.gz`;
client logs:
`reports/paper-1.21.1-mythic-teleport-strict-20261107-r1-client-logs.tar.gz`.
Report SHA-256: `ceefa59fa0b73dce20cca9e1e41cc6cef3536bca4c695c9ffaadd704dacd6406`;
server log SHA-256: `6c1afb1f8d38418618c119268b26ee5bdb8861dadecef41317f612e82dfd3ee2`;
client log SHA-256: `20ebcf32379083b80384b2993c00a706f718f58d4176ed25b0b7fea9a2247c24`;
plugin SHA-256: `7cf45192c315391cb726ed3afb89e775d7fb899367d7ec05f4d1330c8f19250c`.

Previous strict fixture `srv-paper-audience-filter-negative-strict-20261107-r1`
passed all 389 probes (370 passed, 19 expected rejections, zero failed or
incomplete) on Paper 1.21.1-133. Its two-client workflow verifies that
`permission:!helstera.menu` excludes the permission-bearing tester and includes
the guest, while `faction:-undead` includes the tester and excludes the guest
bound to `undead`. Report:
`reports/paper-1.21.1-audience-filter-negative-strict-20261107-r1.json`;
server log: `reports/paper-1.21.1-audience-filter-negative-strict-20261107-r1.log.gz`;
client logs:
`reports/paper-1.21.1-audience-filter-negative-strict-20261107-r1-client-logs.tar.gz`.
Report SHA-256: `a16283dff27ac196d2a36199718c62686d8032ee6481dfe0916f57c438458738`;
server log SHA-256: `4b552b4b5df5f9b79c14ef73011cb5ad9eb74e2524b1a6729433f07d1b4b5030`;
client log SHA-256: `e6148a8360062ebb535ea6c9dec71ff5c659e6dfbc1f5e50d06d734994f7f8e2`;
plugin SHA-256: `f3e7d53ca06e9ef62d1c07425d66b0868f70d9b0ad81f02dae6b00b50477745b`.
The archived logs redact web token query values; the source fixture logs remain
unchanged.

Previous strict fixture `srv-paper-entity-flags-strict-20261107-r3` passed all
389 probes (370 passed, 19 expected rejections, zero failed or incomplete) on
Paper 1.21.1-133. It imports Wolf `Tamed`/`Angry`, Zombified Piglin `Angry`,
and Pillager `PatrolLeader`/`PatrolSpawnPoint` through production spawning and
checks each against inverse controls. Paper accepts the `CanJoinRaid` setter,
but has no getter for its effective value. Report:
`reports/paper-1.21.1-entity-flags-strict-20261107-r3.json`;
server log: `reports/paper-1.21.1-entity-flags-strict-20261107-r3.log.gz`;
client logs:
`reports/paper-1.21.1-entity-flags-strict-20261107-r3-client-logs.tar.gz`.
Report SHA-256: `4add60fd513d716fd8bfbb26cb6e141691ff5db580e8ef9c54b98a80d3424dc5`;
server log SHA-256: `04a523b80a82e52be7e39d7a128ac0daf90300aabf56aa3ddda20bce7a808eb2`;
client log SHA-256: `9c0e10957d681ca11040c8f614e493967c4a1da21b1a51ed6d1453352f70b073`;
plugin SHA-256: `dfa06ca836ad0d177962b3ee553197ad41c0989942c0637aafb4fe044015da28`.

Previous strict fixture `srv-paper-prevent-sunburn-strict-20261107-r4` passed
388 probes (369 passed, 19 expected rejections, zero failed or incomplete).
It verifies imported `Options.PreventSunburn` against an inverse control:
both native midday sunlight events on the protected Zombie were cancelled,
while the control event was uncancelled with 132 fire ticks. Report:
`reports/paper-1.21.1-prevent-sunburn-strict-20261107-r4.json`;
server log: `reports/paper-1.21.1-prevent-sunburn-strict-20261107-r4.log.gz`;
client logs:
`reports/paper-1.21.1-prevent-sunburn-strict-20261107-r4-client-logs.tar.gz`.
Report SHA-256: `7dcdb9a10f0a5a62388f9578db3fd9d88d4417cea8e7ffb44dcce675203e3bbc`;
server log SHA-256: `3ec6c62d4362ed2d95a6300b605e17990286b9bfa8249bea6e950cbe80f3c0db`;
client log SHA-256: `4c4baf391ffee276d4f635163402b5719b5cf5fc663825f55d9327ba05ca0e79`;
plugin SHA-256: `362e44995f813fbab99f4a8c09ca65b685183602922cf9d794b4927f7ddda69b`.

Previous strict fixture `srv-paper-bee-stinger-loss-strict-20261107-r6` passed all
387 probes (368 passed, 19 expected rejections, zero failed or incomplete) on
Paper 1.21.1-133. It imports Bee nectar/stung fields and `PreventStingerLoss`
through production spawning against inverse controls. Native Bee AI attacks a
stationary Zombie; the protected Bee restores its stinger while the control
remains stung, and both Bukkit damage events are uncancelled. Report:
`reports/paper-1.21.1-bee-stinger-loss-strict-20261107-r6.json`;
server log: `reports/paper-1.21.1-bee-stinger-loss-strict-20261107-r6.log.gz`;
client logs:
`reports/paper-1.21.1-bee-stinger-loss-strict-20261107-r6-client-logs.tar.gz`.
Report SHA-256: `a19476296ade4ac370e358a4b83d5f994d190f8cd871686f2bb9b68ab9fa1571`;
server log SHA-256: `af282d10b5a26eba921689358a9f095937989ff1716b33ef13055ebabc6049d3`;
client log SHA-256: `be17f8a8a1c9263ed98866d3f5eb64d06bd1c655a5972c8380190ec2b509a270`;
plugin SHA-256: `d1f34a62d8be828a525af0a9a98ff65d0c3478b47883c921b1fa7002bd20b7c5`.

Previous strict fixture `srv-paper-horse-options-strict-20261107-r1` passed all
385 probes (366 passed, 19 expected rejections, zero incomplete) on Paper
1.21.1-133. Imported Horse color, style, domestication, maximum domestication,
and jump strength are read back against a contrasting control. Report:
`reports/paper-1.21.1-horse-options-strict-20261107-r1.json`;
server log: `reports/paper-1.21.1-horse-options-strict-20261107-r1.log.gz`;
client logs:
`reports/paper-1.21.1-horse-options-strict-20261107-r1-client-logs.tar.gz`.
Report SHA-256: `6c4c3a516d999fa409817075491db62d5d479db5b33a139672f75e4673faae6a`;
server log SHA-256: `8dfa77dc66263d3fa6e4d53ed0b018b3dbb5458caefe6f1e029078b61d7d117f`;
client log SHA-256: `655667e0946236116aed9f46ec0090a2aca37b51ebd430afa389893ff0b5397d`;
plugin SHA-256: `740b24fb9990aaa7038665355e0ab153430ac26a46b37b492aa4c45523ff4016`.

Previous strict fixture `srv-paper-sheep-options-strict-20261107-r1` passed all
384 probes (365 passed, 19 expected rejections, zero incomplete) on Paper
1.21.1-133. Its imported Sheep probe applies `SheepColor: RED` and
`Sheared: true` through production mob spawning, then checks a white, unshorn
control. Report: `reports/paper-1.21.1-sheep-options-strict-20261107-r1.json`;
server log: `reports/paper-1.21.1-sheep-options-strict-20261107-r1.log.gz`;
client logs:
`reports/paper-1.21.1-sheep-options-strict-20261107-r1-client-logs.tar.gz`.
Report SHA-256: `eea8fd2e6462b636425882145048d0de6ed9887b43a416aa7517e6f6cb93ad14`;
server log SHA-256: `8fd0c7855beee03712338b5c482245157cff12b8dc848480e634f11836297afc`;
client log SHA-256: `279d6430528ce8315c8a094f97071a9b2ef57a597b62e8e1a78ee3faa053e1d2`;
plugin SHA-256: `b8174ab89cf0a585b063da12db46e9e8859da544698f51b61a9b596f7a8c7a77`.

Previous strict fixture `srv-paper-cat-options-strict-20261107-r3` passed the
same 384-probe catalog and verifies imported Cat `Type`, `Sitting`, and
`CollarColor` against a `TABBY`/not-sitting/`RED` control. Report:
`reports/paper-1.21.1-cat-options-strict-20261107-r3.json`;
plugin SHA-256: `5e08bb51d697fa77c885c1f5cd86eb3bca6b2a9d6da60dad7289c41f0358eddc`.

Previous strict fixture `srv-paper-creeper-options-strict-20261107-r1` passed
all 384 probes (365 passed, 19 expected rejections, zero incomplete) on Paper
1.21.1-133. It imports `ExplosionRadius: 5`, `FuseTicks: 60`,
`SuperCharged: true`, and `PreventSuicide: true`; Paper cancels only the
protected Creeper's explosion. Report:
`reports/paper-1.21.1-creeper-options-strict-20261107-r1.json`;
plugin SHA-256:
`cfa2888713144cb1fec54d5b341a8d3d6e696de21457d7338e02ab895326f7f6`.

Previous strict fixture `srv-paper-entity-size-range-strict-20261107-r1`
passed all 383 probes (364 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. It imports Mythic `Options.Size: 2to5` for Slime, MagmaCube,
and Phantom, then samples each real Paper entity 64 times; all observations
stayed within range and covered both endpoints. Report:
`reports/paper-1.21.1-entity-size-range-strict-20261107-r1.json`;
server log: `reports/paper-1.21.1-entity-size-range-strict-20261107-r1.log.gz`;
client logs:
`reports/paper-1.21.1-entity-size-range-strict-20261107-r1-client-logs.tar.gz`.
Plugin SHA-256:
`9c2246042a36ed83bcca6b3e2d13d09ff73dd62101ed44f3c17a24a4e638c3c1`.

Previous strict fixture `srv-paper-interactable-strict-20261107-r2` passed
all 383 probes (364 passed, 19 expected rejections, zero incomplete). Its
real-client `interactableClient` probe confirms Paper cancels interactions
with protected managed entities and allows interactions with controls. Report:
`reports/paper-1.21.1-interactable-strict-20261107-r2.json`.

Previous strict fixture `srv-paper-tracked-location-strict-20261107-r1`
passed all 382 probes (363 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. Its imported `TrackLocation`, `@TrackedLocation`,
`DistanceFromTrackedLocation`, and missing-position checks are in
`reports/paper-1.21.1-tracked-location-strict-20261107-r1.json`.
Plugin SHA-256:
`f360cbbd3b0102f406086f61c507cb994a32352c24fe1db090b29d9710802a1a`.

Previous strict fixture `srv-paper-mythic-spawner-biome-strict-20261107-r2`
passed all 382 probes (363 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. Its imported Mythic Spawner condition probe maps
`BiomeType{type=plains;exact=false}`, naturally spawns one managed mob in the
matching category, and rejects `desert` with zero spawns. It restores both
original biomes. The same fixture verifies matching and missing world
conditions, rain without thunder, weighted `RandomMobs` at 1:3 across 100
production spawns, `GroupSize: 3` capped by `MaxMobs: 2`, valid and fail-closed
invalid `Chance`, and variant-specific natural DropTables. The DropTable probe
spawns 32 weighted variants, verifies each Mob's imported `DropTable-1`
assignment, reloads each table before natural Bukkit damage, observes Bread only
for the common Mob and Diamond only for the rare Mob, and restores the original
loot service state. Report:
`reports/paper-1.21.1-mythic-spawner-biome-strict-20261107-r2.json`;
server log:
`reports/paper-1.21.1-mythic-spawner-biome-strict-20261107-r2.log.gz`;
client logs:
`reports/paper-1.21.1-mythic-spawner-biome-strict-20261107-r2-client-logs.tar.gz`.
Plugin SHA-256:
`fd72793b1e8a9aa8b62bc8d7a354dfaf1e36208f874e81fc37f10da1dadca9f5`.

Previous strict fixture `srv-paper-mythic-random-drops-strict-20261106-r4` passed
all 381 probes (362 passed, 19 expected rejections, zero incomplete) on Paper
1.21.1-133. `mythicSpawnerRandomPoolImported` imports Mythic `RandomMobs` with
weights 1:3 and observes 25:75 across 100 production spawns. The same release
run verifies imported `GroupSize: 3` capped by `MaxMobs: 2`, valid `Chance: 1`,
and fail-closed `Chance: 1.5`. `mythicSpawnerRandomPoolDropTables` then spawns
32 weighted variants, verifies each Mob's imported `DropTable-1` assignment,
reloads each table immediately before natural Bukkit damage, observes Bread
only for the common Mob and Diamond only for the rare Mob, and restores the
original loot service state. Report:
`reports/paper-1.21.1-mythic-random-pool-strict-20261106-r4.json`;
server log:
`reports/paper-1.21.1-mythic-random-pool-strict-20261106-r4.log.gz`;
client logs:
`reports/paper-1.21.1-mythic-random-pool-strict-20261106-r4-client-logs.tar.gz`.
Plugin SHA-256:
`773367e232f2c48f5b96876c3572ad486e3918e84c3cfbe7fe925866c353202a`.

Previous strict fixture `srv-paper-mythic-spawner-group-size-strict-20261106-r5`
records a real-client `SpawningDistance` walk: 14.36 blocks of player movement,
15.00 blocks between player and mob, and removal 2 ticks after crossing the
8-block radius, within the 10-tick AI decision period.

Previous strict fixture `srv-paper-execution-audience-mob-strict-20261106-r5`
passed all 377 probes (358 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. `executionAudienceMobSpawn` imports Mythic Mob
`Options.ExecutionAudience` and runs the production mob spawn path. It verifies
`@PlayersInRadius` selection, PDC persistence, and member-only visibility across
two clients. `executionAudienceMobMutation` imports `RemoveFromAudience` and
`AddToAudience` with `@EntitiesInRadius`; it verifies targeter selection,
persisted empty/restored audiences, and member/outsider visibility transitions.
`executionAudienceMobSummon` verifies the imported summon action inherits its
source audience and restricts summoned model visuals to members.
Report:
`reports/paper-1.21.1-execution-audience-mob-strict-20261106-r5.json`;
server log:
`reports/paper-1.21.1-execution-audience-mob-strict-20261106-r5.log.gz`;
client logs:
`reports/paper-1.21.1-execution-audience-mob-strict-20261106-r5-client-logs.tar.gz`.
Plugin SHA-256:
`5aa37479c888f3e77aaef920ee0920a32b97d16cf7df6f6cd047d59334dc9e0f`.

The two-boot Paper integration verifies
that a persistent managed mob retains its entity UUID and exact non-empty
execution audience in entity PDC, and that startup restores the same audience
to the runtime instance. This fixture has no clients, so member/outsider
visibility after restart is not covered. Run it with:

```powershell
powershell -ExecutionPolicy Bypass -File tools/paper-integration/test-execution-audience-restart.ps1
```

Report: `reports/paper-1.21.1-execution-audience-restart-20261106-r3.json`,
SHA-256 `705b216133dee0e40ec46d72c96886fa0803da193d866566f813527004137573`;
first-boot and second-boot logs are archived beside the report. The tested
plugin SHA-256 is
`6ba52a22a59492ec6d0fe82991d7d27d7298e152e91515974ff615a08091a750`.

Previous strict fixture `srv-paper-imported-mob-defaults-strict-20261106-r4`
passed all 373 probes (354 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. `importedMobDefaults` verifies Mythic `PreventLeashing`,
`PreventItemPickup`, `NoDamageTicks`, and `PreventTransformation` through real
Paper entities and transform events. The same run verifies natural Potion
consumption/expiry, movement beyond `SpawningDistance`, web reload, and dual-client
menu behavior. Report and logs remain in `release-evidence.json`.

Previous strict fixture `srv-paper-execution-audience-mob-strict-20261106-r3`
passed 376 probes (357 passed, 19 expected rejections, zero incomplete). It
verified imported mob audience spawn and targeter-based audience mutations;
summon inheritance was added to the catalog and verified in r5.

Previous strict fixture `srv-paper-execution-audience-strict-20261106-r1` passed
all 371 probes (352 passed, 19 expected rejections, zero incomplete) on Paper
1.21.1-133. It verifies imported `isInAudience` aliases, no-audience/member/
outsider/empty-audience and non-player behavior, `?@` target filtering, plus
imported block/entity `LookingAt` selectors with real ray-trace type and distance
controls. Report:
`reports/paper-1.21.1-execution-audience-strict-20261106-r1.json`;
server log: `reports/paper-1.21.1-execution-audience-strict-20261106-r1.log.gz`;
client logs:
`reports/paper-1.21.1-execution-audience-strict-20261106-r1-client-logs.tar.gz`.
The Mineflayer menu client emitted `PartialReadError` warnings while reading
menu protocol packets; server-side render/click assertions and the client
workflow report passed, and the later raw-potion client rejoined successfully.
Plugin SHA-256:
`abbc8f64d612eb4cd7cf548f5cde663821b6183687e2c9649affe3003febd3cf`.

Previous strict fixture `srv-paper-server-conditions-strict-20261106-r1`
passed all 370 probes (351 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. `importedServerConditions` verifies imported Paper identity
aliases, exact Minecraft-version matching, inclusive/exclusive `ServerAfter`
and `ServerBefore` boundaries, and malformed-input preservation. Its report,
server/client logs, and plugin hash remain in `release-evidence.json`.

Latest strict follow-up `srv-paper-looking-at-import-strict-20261106-r1` also
passed all 370 release probes (351 passed, 19 expected rejections, zero
incomplete) on Paper 1.21.1-133. `importedLookingAtCondition` verifies
`?lookingAt{b=STONE;d=6}`, the full-name block alias, and
`?lookingAt{e=COW;d=6}` through the importer and SkillService. Real Paper
ray traces cover matching and wrong block/entity types, distances shorter than
the hit, invalid distance preservation, and restoration of the block, player
location, and temporary Cow. Report:
`reports/paper-1.21.1-looking-at-import-strict-20261106-r1.json`;
server log: `reports/paper-1.21.1-looking-at-import-strict-20261106-r1.log.gz`;
client logs:
`reports/paper-1.21.1-looking-at-import-strict-20261106-r1-client-logs.tar.gz`.
Plugin SHA-256:
`cf2267186d343f2b80335b66f45bb215c4f155a3739d7e1df89a7b8b568b5273`.

Previous strict fixture `srv-paper-name-import-strict-20261106-r2` passed all
369 probes (350 passed, 19 expected rejections, zero incomplete) on Paper
1.21.1-133. `importedNameCondition` verifies the `Name`/`nameIs` mappings,
quoted custom-name matching, default-name rejection, entity-name fallback after
clearing the custom name, and invalid-argument preservation. Report:
`reports/paper-1.21.1-name-import-strict-20261106-r2.json`;
server log: `reports/paper-1.21.1-name-import-strict-20261106-r2.log.gz`;
client logs:
`reports/paper-1.21.1-name-import-strict-20261106-r2-client-logs.tar.gz`.
Plugin SHA-256:
`42760067a8c4c7ea88d9691da8886d5dc5db7f9fd50a369489ba8b25c45e3657`.

Previous strict fixture `srv-paper-free-inventory-slot-strict-20261106-r1`
passed all 368 probes (349 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. Its imported `HasFreeInventorySlot`/`HasFreeSlot` probe
checks empty/full inventory results on a real player and invalid-argument
preservation. Report and compressed logs remain in `release-evidence.json`.

Previous strict fixture `srv-paper-variable-conditions-strict-20261106-r1` passed
all 367 probes (348 passed, 19 expected rejections, zero incomplete) on Paper
1.21.1-133. `importedVariableConditions` verifies the imported
`VariableEquals`, `VariableInRange`, and `VariableIsSet` paths, aliases, five
scopes, inclusive/exclusive numeric boundaries, fail-closed missing and
wrong-type values, quoted semicolon values, dynamic placeholders, and invalid
range preservation. Report:
`reports/paper-1.21.1-variable-conditions-strict-20261106-r1.json`;
server log: `reports/paper-1.21.1-variable-conditions-strict-20261106-r1.log.gz`;
client logs:
`reports/paper-1.21.1-variable-conditions-strict-20261106-r1-client-logs.tar.gz`.
Plugin SHA-256:
`b37f06a021b904fd3fa61b2710f88cc4c533943c533e28a813e7bc1dd0eae8b6`.

Previous strict fixture `srv-paper-aura-target-strict-20261106-r1` passed all
366 probes (347 passed, 19 expected rejections, zero incomplete) on Paper
1.21.1-133. `mythicAuraRuntime` verifies imported target-only named Aura checks,
Aura application and conditions, partial/default/`ANY` removal, aliases, and
invalid-input rejection. Its report, logs, and exact plugin hash remain in
`release-evidence.json`.

Previous strict fixture `srv-paper-aura-remove-strict-20261106-r1` also passed
the full catalog and verifies partial/default/`ANY` Aura removal. Its exact
report, logs, and plugin hash remain in `release-evidence.json`.

Previous strict fixture `srv-paper-aura-strict-20261106-r1` passed all 366
probes (347 passed, 19 expected rejections, zero incomplete) on Paper 1.21.1-133.
It verifies Aura action dispatch, named state and stack merging, imported Aura
conditions, and malformed-condition rejection. Its report and logs are in
`reports/` and its exact tested JAR hash is in `release-evidence.json`.

Previous strict fixture `srv-paper-entity-type-traits-strict-20261106-r1`
passed all 365 probes (346 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. `importedEntityTypeTraitsCondition` verifies imported runtime
selectors against real Horse, Panda, Villager, Zombie Villager, Sheep, Shulker,
Parrot, Llama, Tropical Fish, and Iron Golem entities, and rejects traits used
with inapplicable entity types. Report:
`reports/paper-1.21.1-entity-type-traits-strict-20261106-r1.json`;
server log:
`reports/paper-1.21.1-entity-type-traits-strict-20261106-r1.log.gz`;
client logs:
`reports/paper-1.21.1-entity-type-traits-strict-20261106-r1-client-logs.tar.gz`.
Plugin SHA-256:
`327c83088c400d0e39f6045d1e58d8a226e999e8d67d9feaa20116a5faacd59d`.

Previous strict fixture `srv-paper-entity-type-variant-import-strict-20261106-r1`
passed all 364 probes (345 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. `importedEntityTypeVariantCondition` verifies the
`entityType`, `entityTypeIs`, and `mobtype` imports against a real Cat, including
TABBY/BLACK variant alternatives, RED and wrong-type rejection, and invalid
selector preservation. Report:
`reports/paper-1.21.1-entity-type-variant-import-strict-20261106-r1.json`;
server log:
`reports/paper-1.21.1-entity-type-variant-import-strict-20261106-r1.log.gz`;
client logs:
`reports/paper-1.21.1-entity-type-variant-import-strict-20261106-r1-client-logs.tar.gz`.
Plugin SHA-256:
`58f94be7da6216b62e09e7d3405bc6229b97c022e2a2fdd74e2450957721c369`.

Previous strict fixture `srv-paper-entity-category-import-strict-20261106-r2`
passed all 363 probes (344 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. Its `importedEntityCategoryCondition` probe verifies the
`?isWaterMob`/`?waterMob` and `?isZombie`/`?zombie` imports against real Cod and
Zombie targets, cross-type rejection, and preservation of invalid arguments.
Report: `reports/paper-1.21.1-entity-category-import-strict-20261106-r2.json`;
server log:
`reports/paper-1.21.1-entity-category-import-strict-20261106-r2.log.gz`;
client logs:
`reports/paper-1.21.1-entity-category-import-strict-20261106-r2-client-logs.tar.gz`.
Plugin SHA-256:
`94b1bfb7f5700e0fe0f69f505ade71fabc533599279c2961522ddad5c28ec1c8`.

Previous strict fixture `srv-paper-condition-action-suffix-strict-20261106-r1`
passed 362 probes (343 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. Its `importedBurningCondition` probe verifies imported
`burning true`, `isburning false`, `!burning false`, target priority, caster
fallback, and invalid-suffix preservation. Report:
`reports/paper-1.21.1-condition-action-suffix-strict-20261106-r1.json`; server
log: `reports/paper-1.21.1-condition-action-suffix-strict-20261106-r1.log.gz`;
client logs:
`reports/paper-1.21.1-condition-action-suffix-strict-20261106-r1-client-logs.tar.gz`.
Plugin SHA-256:
`eb83955509fb4741fd2ced98b57706a1881542442f0a0107dd25781cef1ad454`.

Previous strict fixture `srv-paper-is-spyglass-import-strict-20261106-r1`
passed 360 probes (341 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. `importedSpyglassCondition` verifies Mythic
`?isUsingSpyglass`/`?isScoping` through the importer and `SkillService` while a
real online Player actively uses a Spyglass; it also rejects the inactive
state, a Zombie target, and malformed arguments. Report:
`reports/paper-1.21.1-is-spyglass-import-strict-20261106-r1.json`; server log:
`reports/paper-1.21.1-is-spyglass-import-strict-20261106-r1.log.gz`; client
logs: `reports/paper-1.21.1-is-spyglass-import-strict-20261106-r1-client-logs.tar.gz`.
Plugin SHA-256:
`38e0e9769a086950b3e4f5a6923d8f58f44a16fdf3bfaebf512f6d49462a7a20`.

Previous strict fixture `srv-paper-is-frozen-import-strict-20261106-r2` passed
359 probes (340 passed, 19 expected rejections, zero incomplete) on Paper
1.21.1-133. `importedIsFrozenCondition` verifies Mythic
`?isFrozen`/`?frozen` through the importer and `SkillService` against a real
frozen Zombie, after thawing, and with an invalid argument. Report:
`reports/paper-1.21.1-is-frozen-import-strict-20261106-r2.json`; server log:
`reports/paper-1.21.1-is-frozen-import-strict-20261106-r2.log.gz`; client logs:
`reports/paper-1.21.1-is-frozen-import-strict-20261106-r2-client-logs.tar.gz`.
Plugin SHA-256:
`4af92b5462a6d1bb533fdb14df654c7e9e9cea066eaef8da10c568bdd4976335`.

Previous strict fixture `srv-paper-item-recharging-import-strict-20261106-r1`
passed 358 probes (339 passed, 19 expected rejections, zero incomplete) on
Paper 1.21.1-133. `importedItemRechargingCondition` verifies Mythic
`?itemRecharging` and `?recharging` through the importer and `SkillService`,
checks both after a real Bukkit attack lowers Player attack cooldown, and
rejects a Zombie target. Report:
`reports/paper-1.21.1-item-recharging-import-strict-20261106-r1.json`; server
log:
`reports/paper-1.21.1-item-recharging-import-strict-20261106-r1.log.gz`;
client logs:
`reports/paper-1.21.1-item-recharging-import-strict-20261106-r1-client-logs.tar.gz`.
Plugin SHA-256:
`7e4b194a8beb0645e457108ab19d349c9888c783b74724d2cdb78ed533fecc91`.

Previous strict fixture `srv-paper-origin-location-import-strict-20261105-r4`
passed 357 probes (338 passed, 19 expected rejections, zero incomplete).
`importedOriginLocationCondition` verifies Mythic primary and alias forms,
exact origin coordinates, mismatch rejection, and malformed input through the
importer and `SkillService`. Report:
`reports/paper-1.21.1-origin-location-import-strict-20261105-r4.json`.
Plugin SHA-256:
`42abd200b6cde48ca19471c180dbf2821e45d02eb2b12d993b8bd27b221c4bf0`.

Previous strict fixture `srv-paper-trigger-types-import-strict-20261105-r2`
passed 356 probes (337 passed, 19 expected rejections, zero incomplete). Its
imported TriggerItemType and TriggerBlockType probes verify primary/alias
mapping, material positives and negatives, and wrong-state rejection against a
real Bukkit Item and block in constructed `BehaviorContext` values; natural
Bukkit event delivery remains unverified. Report:
`reports/paper-1.21.1-trigger-types-import-strict-20261105-r2.json`.
Plugin SHA-256:
`41cf9e79c81657f05acb68a95a78ad5fdda073de60b7502d17b79e428538ec4a`.

Previous strict fixture `srv-paper-trigger-types-import-strict-20261105-r1`
also passed 356 probes with the same TriggerItemType/TriggerBlockType controls;
the r2 report supersedes it as the latest evidence for the current exact JAR.
Report:
`reports/paper-1.21.1-trigger-types-import-strict-20261105-r1.json`.
Plugin SHA-256:
`071ceebd2da8a904dfbc3aef8a2594fb5d22c04d83e23acf4e798f9943ebe65e`.

Earlier strict fixture `srv-paper-has-enchantment-import-strict-20261105-r1`
passed 354 probes (335 passed, 19 expected rejections, zero incomplete). It
verifies imported `?hasEnchantment`/`?hasEnchant`, Sharpness and `ANY`
positives, level/type negatives, and equipment restoration on a real managed
Zombie. Report:
`reports/paper-1.21.1-has-enchantment-import-strict-20261105-r1.json`; server
log: `reports/paper-1.21.1-has-enchantment-import-strict-20261105-r1.log.gz`;
client logs:
`reports/paper-1.21.1-has-enchantment-import-strict-20261105-r1-client-logs.tar.gz`.
Plugin SHA-256:
`81492669538c1c4358a4ac7f8537e76791a31fb08d55c1246137ad6afe149fbe`.

Previous strict fixture `srv-paper-condition-import-strict-20261105-r1` passed
353 probes (334 passed, 19 expected rejections, zero incomplete). It verifies
imported Aerial and Color aliases and `MaterialIsOnCooldown` against a real
player cooldown. Report:
`reports/paper-1.21.1-condition-import-strict-20261105-r1.json`; server log:
`reports/paper-1.21.1-condition-import-strict-20261105-r1.log.gz`; client logs:
`reports/paper-1.21.1-condition-import-strict-20261105-r1-client-logs.tar.gz`.
Plugin SHA-256:
`75b5bf2bde256454caa90aa1bea652ac67754b0f65b3e38633d3ddbf7933104a`.

Previous strict fixture `srv-paper-color-import-strict-20261105-r1` passed 352
probes (333 passed, 19 expected rejections, zero incomplete). It verifies
imported `?color{c=RED}`/`?clr{color=BLUE}` on a real red Sheep. Report:
`reports/paper-1.21.1-color-import-strict-20261105-r1.json`; server log:
`reports/paper-1.21.1-color-import-strict-20261105-r1.log.gz`; client logs:
`reports/paper-1.21.1-color-import-strict-20261105-r1-client-logs.tar.gz`.
Plugin SHA-256:
`6411d68946e2d04082086609dba9ff8f74e5d404c99df577ae93d6588bf8a51e`.

Previous strict fixture `srv-paper-aerial-import-strict-20261105-r1` passed 351
probes; it verifies both Aerial aliases against floating and grounded entities.

Previous strict fixture `srv-paper-is-child-import-strict-20261105-r1` passed 350
probes (331 passed, 19 expected rejections, zero incomplete).
`importedIsChildCondition` executes Mythic `?isChild` through the importer and
`SkillService`: an adult Pig related to its caster matches without being a baby,
while an unrelated baby Pig fails `isChild` and passes the `isBaby` control.
Report: `reports/paper-1.21.1-is-child-import-strict-20261105-r1.json`; server
log: `reports/paper-1.21.1-is-child-import-strict-20261105-r1.log.gz`; client
logs: `reports/paper-1.21.1-is-child-import-strict-20261105-r1-client-logs.tar.gz`.
Plugin SHA-256:
`e0c6fdaf31e96003a68bd589d1ca88e0786dabb0ecdf8333022474d1d52ddc7b`.

Previous strict fixture `srv-paper-age-options-strict-20261105-r3` passed 349
probes (330 passed, 19 expected rejections, zero incomplete). The required
`ageOptions` probe reads exact animal age/AgeLock and Adult/Baby from real
Sheep and verifies the Zombie-specific Baby setter on a real Zombie. Report:
`reports/paper-1.21.1-age-options-strict-20261105-r3.json`; server log:
`reports/paper-1.21.1-age-options-strict-20261105-r3.log.gz`; client logs:
`reports/paper-1.21.1-age-options-strict-20261105-r3-client-logs.tar.gz`.
Plugin SHA-256:
`62051171e7637e3c9d1d40bcc7d254bc6d565d1cca3428a6fff62d019380b50e`.

Previous strict fixture `srv-paper-aerial-condition-strict-20261105-r2` passed
349 probes (330 passed, 19 expected rejections, zero incomplete).
`aerialCondition` verifies a floating ArmorStand through both `aerial` and
`isAerial`, and rejects a grounded player with `ground-entity=player`. Report:
`reports/paper-1.21.1-aerial-condition-strict-20261105-r2.json`; server log:
`reports/paper-1.21.1-aerial-condition-strict-20261105-r2.log.gz`; client logs:
`reports/paper-1.21.1-aerial-condition-strict-20261105-r2-client-logs.tar.gz`.
Plugin SHA-256:
`97f61a7392ad0051acc0ee55cdcc0272c0512d2675347c138be3ce3f918eaa6a`.

Previous strict fixture `srv-paper-aerial-condition-strict-20261105-r1` passed
349 probes (330 passed, 19 expected rejections, zero incomplete).
`aerialCondition` verifies the floating ArmorStand and grounded-player states.
Report:
`reports/paper-1.21.1-aerial-condition-strict-20261105-r1.json`; server log:
`reports/paper-1.21.1-aerial-condition-strict-20261105-r1.log.gz`; client logs:
`reports/paper-1.21.1-aerial-condition-strict-20261105-r1-client-logs.tar.gz`.
Plugin SHA-256:
`0c35898c00b2b52ebebe8aa8b0ee53665682188c53e10a8149d2fce67df98159`.

Previous strict fixture `srv-paper-enderman-condition-strict-20261105-r1` passed
348 probes (329 passed, 19 expected rejections, zero incomplete).
`endermanCondition` verifies a real Enderman positive and managed Zombie
negative. Report:
`reports/paper-1.21.1-enderman-condition-strict-20261105-r1.json`; server log:
`reports/paper-1.21.1-enderman-condition-strict-20261105-r1.log.gz`; client logs:
`reports/paper-1.21.1-enderman-condition-strict-20261105-r1-client-logs.tar.gz`.
Plugin SHA-256:
`d575844f655ae2812475c18dd550d4e47aa7f235ed73dd974a8153ac23f51478`.

Previous strict fixture `srv-paper-cinematic-condition-20261105-r1` passed
347 probes (328 passed, 19 expected rejections, zero incomplete).
`isInCinematicCondition` verifies in-audience positive, out-of-range and
non-player rejection, and the stopped-cinematic state. Report:
`reports/paper-1.21.1-is-in-cinematic-condition-strict-20261105-r1.json`;
server log: `reports/paper-1.21.1-is-in-cinematic-condition-strict-20261105-r1.log.gz`;
client logs: `reports/paper-1.21.1-is-in-cinematic-condition-strict-20261105-r1-client-logs.tar.gz`.
Plugin SHA-256:
`d575844f655ae2812475c18dd550d4e47aa7f235ed73dd974a8153ac23f51478`.

Previous strict fixture `srv-paper-spawning-timing-20261105-r3`
passed 346 probes (327 passed, 19 expected rejections, zero incomplete).
`spawningDistanceClientWalk` records player displacement of 174.12 blocks,
entity distance of 176.84 blocks, and removal of both the managed instance and
Bukkit entity 2 ticks after the first outside-range sample; the configured AI
decision period is 10 ticks. `insideOutsideCondition` also verifies positive
and negative results and restores the original block state. Report:
`reports/paper-1.21.1-spawning-distance-timing-20261105-r3.json`; server log:
`reports/paper-1.21.1-spawning-distance-timing-20261105-r3.log.gz`; client logs:
`reports/paper-1.21.1-spawning-distance-timing-20261105-r3-client-logs.tar.gz`.
Plugin SHA-256:
`842a3dfd96d10e23c844652ed0fe05736ed3c10d2996d258d81bbd6c870ca4c9`.
The complete Conditions catalog remains open.

Previous full strict fixture `srv-paper-snowing-strict-20261105-r2`
passed 345 probes (326 passed, 19 expected rejections, zero incomplete).
`snowingCondition` verifies target-location isolation, both aliases, warm and
clear rejection, missing context, invalid arguments, and state restoration.
Report: `reports/paper-1.21.1-snowing-strict-20261105-r2.json`.
Plugin SHA-256:
`1b6fe0a823faadc268149f90df680f3550bab8a2059f41ac1477fa992fd31a44`.
The complete Conditions catalog remains open.

Earlier strict fixture `srv-paper-in-combat-strict-20261105-r3`
passed 344 probes (325 passed, 19 expected rejections, zero incomplete).
`inCombatCondition` independently verifies managed-caster and native-Mob
positive, no-target rejection, and target restoration; `conditionUtilityAliases`
retains aggregate coverage. The required `spawningDistanceClientWalk` probe
also verifies real-player displacement beyond range and managed/Bukkit entity
removal. Report:
`reports/paper-1.21.1-in-combat-strict-20261105-r3.json`; server log:
`reports/paper-1.21.1-in-combat-strict-20261105-r3.log.gz`; client logs:
`reports/paper-1.21.1-in-combat-strict-20261105-r3-client-logs.tar.gz`.
Plugin SHA-256:
`5a5df5c3a02a33f6b0f7fdbc9b4071f04df93613e3c05d1aba98bb290dc86548`.
The managed Paper path used Bukkit `Mob#setTarget`; controller-specific behavior
remains covered by unit tests. The complete Conditions catalog remains open.

Previous strict fixture `srv-paper-in-combat-strict-20261105-r2` passed 342
probes (323 passed, 19 expected rejections, zero incomplete); it recorded the
same runtime paths in the aggregate condition utility probe.

Previous strict fixture `srv-paper-is-sheared-strict-20261105-r1`
passed 342 probes (323 passed, 19 expected rejections, zero incomplete).
`entityShearedStates` verifies self and target conditions for real Sheep,
Bogged, and Snow Golems in positive and negative states, plus unsupported
entities and missing context. Report:
`reports/paper-1.21.1-is-sheared-strict-20261105-r1.json`; server log:
`reports/paper-1.21.1-is-sheared-strict-20261105-r1.log.gz`; client logs:
`reports/paper-1.21.1-is-sheared-strict-20261105-r1-client-logs.tar.gz`.
The plugin SHA-256 is
`cef3fa45f41ff612dbcd89a9aa0fe8224a07702f4a71dd84aad40a5c323713d1`.

Previous full-catalog fixture `srv-paper-targeter-relations-strict-20261105-r3`
passed 340 probes (321 passed, 19 expected rejections, zero incomplete).
It verifies owner/faction target filters and precedence, managed identity,
`targetall` interaction, and fail-closed relation syntax. Exact same-faction
matching uses Helstera's faction service; external MythicMobs/Citizens identity
and premium filters remain open.

Previous strict fixture `srv-paper-target-all-filter-strict-20261105-r1`
passed 339 probes (320 passed, 19 expected rejections, zero incomplete). Its
`targeterArgumentValidation` probe verifies `targetall=true` overrides
conflicting `target`, `ignore`, and specific category filters within the real
candidate set. It retains animal, creature, villager, ArmorStand,
invulnerability, player-mode, ground, water, and flying coverage against real
entities, including a flying Phantom. Invalid and duplicate options still fail
closed. Report:
`reports/paper-1.21.1-target-all-filter-strict-20261105-r1.json`; server log:
`reports/paper-1.21.1-target-all-filter-strict-20261105-r1.log.gz`; client logs:
`reports/paper-1.21.1-target-all-filter-strict-20261105-r1-client-logs.tar.gz`.
The plugin SHA-256 is
`1a30a22c1274f045782daff576ebeb1ed4089cd963879d5a831602ef04cf1bf4`.
`targetall` preserves the targeter's radius and world scope. Non-living
display/interaction entities, NPC identity, external MythicMobs identity, and
premium filters remain open.

Previous strict fixture `srv-paper-target-armorstands-strict-20261105-r1`
verified ArmorStand aliases, inclusion/exclusion, and invulnerability and
remains archived in `release-evidence.json`.

Previous strict fixture `srv-paper-game-mode-target-filter-strict-20261104-r1`
verified generic Creative and Spectator `target=`/`ignore=` filters. Its report
and logs remain archived in `release-evidence.json`.

Earlier strict fixture `srv-paper-self-eye-location-strict-20261104-r1` passed
339 probes (320 passed, 19 expected rejections). `locationTargeterDispatch`
uses real Paper entities and the production `set-block` action to verify
`@SelfLocation` aliases and all five `@SelfEyeLocation` aliases,
`@CasterSpawnLocation`/`@casterSpawn`,
`@SpawnLocation`, `@TargetLocation` aliases, `@TriggerLocation`, `@Forward`,
`@ProjectileForward` with a real Arrow, and `@TargetPredictedLocation` with a
moving target. Unknown arguments, non-living and missing caster contexts fail
closed in API tests; an invalid coordinate remains rejected on Paper. Report:
`reports/paper-1.21.1-self-eye-location-strict-20261104-r1.json`; server log:
`reports/paper-1.21.1-self-eye-location-strict-20261104-r1.log.gz`; client logs:
`reports/paper-1.21.1-self-eye-location-strict-20261104-r1-client-logs.tar.gz`.
The plugin SHA-256 is `b949b2278b3f6fce54fe9c1efdd571858b53c4e44436bee4e6f259cd357c8fca`.

Earlier strict fixture `srv-paper-targeter-syntax-20261104-r2` passed 339 probes
(320 passed, 19 expected rejections). It checks a delayed nested skill before
execution, after its first execution and after its repeat on a real entity;
two invalid timing options are preserved, warned and not executed. Duplicate
delay, repeat and interval aliases are rejected during binding with diagnostics.
Three invalid imported heal mechanics are preserved with warnings and do not execute.
Imported positional heal mechanics without targeters obey 0% and 100% chances.
Passing and failing target conditions and a braced targeter are also checked.
Ambiguous suffixes are preserved, warned and rejected; placeholder and valid
chain mappings remain accepted. Invalid chances `101%`, `NaN%`, and `1e999%`
are preserved, warned and rejected without affecting the entity.
Its `ai-selector-core` Paper observation rejects `@targets{limit=1;;}` and
confirms `@targets{limit=1}` selects the real online player. Unmatched quotes
and other delimiter errors remain covered by API tests.
Unrouted and duplicate event suffixes are rejected during binding with warnings.
Case variants, unknown and incomplete suffixes are rejected, while quoted
event-like text binds as a message. This does not test client delivery.
Standalone `?` and `@` are preserved with warnings and rejected without healing
a real test entity. Throwing conditions under nested negation block healing;
a normal false-condition control heals. Named skill requirements that throw or
are unknown also block healing under negation, while a normal false requirement
allows the negated control to heal. Report:
`reports/paper-1.21.1-targeter-syntax-20261104-r2.json`. A named skill reload
self-test checks a real entity: deletion blocks its old action and negated
condition, and reintroducing the definition restores the action. A separate
self-test verifies mixed-case forward references, missing references and
cyclic conditions against a real entity. Native DropTable file loading rejects
invalid tier ranges with warnings, retains valid sibling tiers, and preserves
the base-amount fallback when an indexed tier range is null. The current JAR
contains strict targeter argument parsing; valid targeter runtime paths passed
the full catalog run.

Earlier strict fixture `srv-paper-drop-inline-20261104-r2` passed 326 probes
(307 passed, 19 expected rejections). It imports inline fixed and skill-variable
counts and checks missing variables and invalid inline-count preservation.
Report: `reports/paper-1.21.1-drop-inline-20261104-r2.json`.

Earlier strict fixture `srv-paper-drop-natural-20261104-r1` passed 325 probes
(306 passed, 19 expected rejections). Imported `i=DIAMOND;n=false` produces an
item at the exact target location and an invalid natural flag is preserved with
a warning. Report: `reports/paper-1.21.1-drop-natural-20261104-r1.json`.

Earlier strict fixture `srv-paper-drop-import-20261104-r1` passed 324 probes
(305 passed, 19 expected rejections). The importer-to-runtime probe executes
valid Mythic dropitem YAML, checks a missing variable and verifies preservation
and warnings for zero amount and an unknown argument. Report:
`reports/paper-1.21.1-drop-import-20261104-r1.json`.

Earlier strict fixture `srv-paper-drop-location-20261104-r2` passed 323 probes
(304 passed, 19 expected rejections). A real item spawn confirms the requested
location, material and count, while zero count and a missing location variable
produce no item. Report: `reports/paper-1.21.1-drop-location-20261104-r2.json`.
The imported action was not exercised by this earlier Paper probe.

Earlier strict fixture `srv-paper-explosion-location-20261104-r3` passed 322
probes (303 passed, 19 expected rejections). Its report was regenerated from
the original server and client logs after adding the explosion location probe
to the report contract. The real Paper block explosion event confirms the
target coordinates and the absence of an event for a missing location variable;
the event is cancelled by the probe. Report:
`reports/paper-1.21.1-explosion-location-20261104-r3.json`.
Earlier strict fixture `srv-paper-variable-location-strict-20261104-r1` passed 321
probes (302 passed, 19 expected rejections), including variable location
dispatch, invalid-argument rejection, and migration preservation. Its report is
`reports/paper-1.21.1-variable-location-strict-20261104-r1.json`.
Earlier fixture `srv-paper-location-dispatch-strict-20261104-r2` passed 320
probes (301 passed, 19 expected rejections), including location dispatch and
invalid-coordinate rejection. The archived report is
`reports/paper-1.21.1-location-dispatch-strict-20261104-r2.json`.
Earlier fixture `srv-paper-targeter-arguments-strict-20261104-r1` passed 319
probes (300 passed, 19 expected rejections). `healthImportedCondition` and
`healthRangeImportedCondition` exercise translated health comparisons, range
boundaries, percentages, absorption and invalid-input preservation on Paper 1.21.1-133.
`comparisonBoundaryImportedCondition` checks strict and inclusive distance and
light boundaries and invalid-input preservation on real entities and blocks.
`targetHealthImportedCondition` checks target health multipliers, percentages,
strict boundaries, missing targets and invalid-input preservation.
`environmentImportedCondition` checks real world, time and weather conditions
and rejects malformed, unknown, duplicate and negated invalid arguments.
`dropChanceImported` checks that malformed drop probabilities, including Java
hexadecimal and numeric type suffix forms, are warned and
excluded while a valid sibling rolls. It does not exercise natural death.
`dropNativeDynamic` checks invalid expressions, inherited nulls and valid overrides.
`dropNativeTier` checks invalid tier restrictions and valid known-tier controls.
`targeterArgumentValidation` checks ambiguous and malformed arguments, a chain and a positive control.
Report: `reports/paper-1.21.1-targeter-arguments-strict-20261104-r1.json`; plugin SHA-256:
`fa456690d3e3609721ff76c734402822ba52d1ea572fed629a167aa504f0d8ab`.
Numeric overflow is covered by unit tests.

Earlier strict fixture `srv-paper-pin-casing-strict-20261103-r1` passed all 310
required probes (291 passed, 19 expected rejections). It verifies that Pin
configuration accepts both `Pins` and `pins` sections and mixed-case map fields
(`World`/`X`/`Y`/`Z`) while retaining invalid-coordinate and missing-world
rejection behavior. The `pinTargeters` and `pinConditions` probes passed. The
report is tied to plugin SHA-256
`d0650a0078e6e91be741a58b4af3cea8186e296ff64cfc931de825123b57aa65` and is
archived at `reports/paper-1.21.1-pin-casing-strict-20261103-r1.json`.

Strict fixture `srv-paper-external-boundary-strict-20261103-r2` passed all 310
required probes (291 passed, 19 expected rejections) after the external-boundary
adapter registration. This historical run confirms that
the blocked adapters do not alter the core strict catalog; it does not claim
installed-plugin parity for MMOItems, MythicCrucible, or ModelEngine. The report
is tied to plugin SHA-256
`8b9e5a3d25e07e373b1fa96a872323ab5fa5ec02ae6b4926facee6f8e6cd9287` and is
archived at `reports/paper-1.21.1-external-boundary-strict-20261103-r2.json`.

The two-boot spawner restart script also accepts `-CorruptPrimary` to replace
the primary state file with invalid YAML between boots and assert backup
recovery, unchanged backup contents after shutdown, and the restored primary
counter. Focused fixture `srv-paper-spawner-backup-save-20261103-r1` passed;
this workflow does not run the full strict release gate.
Use `-MissingPrimary` for the equivalent missing-file recovery check; focused
fixture `srv-paper-state-missing-primary-20261103-r1` passed.
Use `-CorruptBoth` to check the unrecoverable-state force command and file
preservation; fixture `srv-paper-state-corrupt-both-20261103-r1` passed.
Use `-RepairCorrupt` to restore a valid state and verify forced spawning resumes;
fixture `srv-paper-state-repair-20261103-r2` passed after loading its target chunk.
Use `-NaturalRepair` for a real scheduled-spawn pause and recovery check;
fixture `srv-paper-state-natural-repair-20261103-r2` passed.
Set `PAPER_WEB_RELOAD_PROBE=true` to enable the local web server on port 18765
and exercise malformed and repaired spawner reloads over HTTP. Use an isolated
fixture directory; this focused workflow does not establish strict evidence.

Exploratory run `srv-paper-revive-20261102-r4` on Paper 1.21.1-133 observed
the `revive_probe` zombie alive and still attached at 8 health, 60 ticks after
lethal Bukkit damage. The probe uses native configuration and is now required
by the strict report contract. Strict run `srv-paper-revive-strict-20261102-r2`
observed this probe passing, but failed the full gate because `expNaturalDeath`
observed 10 rather than 13 experience. Earlier exploratory runs r1-r3 used
normal distance despawn without a player and cannot establish survival.
Strict run `srv-paper-exp-revive-strict-20261102-r1` observed both natural
experience (13 XP) and revive passing after the experience probe began counting
changes to existing nearby orbs. Its full gate failed because the
`spawner:player_gate_fail` negative control unexpectedly spawned. The prior
experience discrepancy's precise cause remains unconfirmed.

Current-worktree strict run `srv-paper-current-items-strict-20261102-r1` also
failed the release gate on `expNaturalDeath`: the synthetic death event reported
13 XP, while the natural death probe observed 10 XP in nearby orbs. Its report
passed schema validation; the failure has not been diagnosed as either an orb
observation issue or a runtime drop discrepancy. This run is not release
evidence and does not validate external item integrations.

Exploratory run `srv-paper-exp-diagnostic-20261102-r1` added a MONITOR
observation of the real death event and recorded 13 event XP and three new
orbs valued 7, 3, and 3. This run did not reproduce the discrepancy and did
not include the client workflows; its late revive probe failed, so it is not
a strict release result. The probe now logs event XP and individual orb values
to help diagnose any recurrence without relaxing the 13 XP assertion.
The failed late-revive entity in that run was removed while invalid but not
dead, consistent with its distant chunk unloading without a player. The probe
now temporarily force-loads its chunk and restores its prior setting. In the
no-client exploratory run `srv-paper-revive-chunk-diagnostic-20261102-r1`, the
late-revive check passed after 60 ticks and the experience check observed 13 XP;
this does not replace a full strict run or prove chunk-unload recovery.
Full strict run `srv-paper-current-diagnostic-strict-20261102-r1` subsequently
passed all 291 required probes (274 passed, 17 expected rejections); its real
death event and orbs both recorded 13 XP, and the late-revive probe passed.
One successful run does not establish the cause of the earlier 10 XP result.
Strict run `srv-paper-menu-resolution-strict-20261102-r1` passed all 291 probes
on the subsequent build; its real-client menu permission and pagination workflow
passed. It does not specifically exercise custom item name collisions.
Strict run `srv-paper-menu-collision-strict-20261102-r1` passed all 291 probes
(274 passed and 17 expected rejections). Paper rendered the custom `SPONGE`
menu button as `GOLD_INGOT`; the report gate rejects a vanilla `SPONGE` render.
This covers a valid menu item collision, not invalid items or external plugins.
Strict run `srv-paper-exp-tier-strict-20261102-r1` passed 292 probes (275 passed,
17 expected rejections). Real Bukkit-damage deaths with no player killer produced
2 XP at mob level 2 and 7 XP at level 3 in the observed death events. The new
probe is required by the current release catalog; player tier and orb pickup
are not covered by this probe. Older reports remain historical evidence and are
not checked against the expanded current catalog.
The current catalog also requires the exact tier and Bread observations for
`expTierDeath`; a passed status without those observations fails the gate.
Follow-up `srv-paper-fixed-amount-strict-20261102-r1` observed fixed `amount`
values for Bread and XP in real death events, but failed the full strict gate:
the separate delayed `deathLootExperience` probe counted 8 XP in nearby new
orbs instead of 14-16. The reason is not established; this run is not release
evidence.
Follow-up strict fixture `srv-paper-orb-detail-strict-20261102-r1` passed 292
probes, including fixed amounts. Its separate death-loot diagnostic recorded
15 event XP and the same 7+7+1 orbs immediately and at the delayed check. It
did not reproduce or explain the prior 8 XP observation. A later source-only
cross-world guard was compiled after that run.

Earlier fixture `srv-paper-state-validation-strict-20261103-r2` passed all 310 required
probes (291 passed, 19 expected rejections). The unrecoverable-state pause is
not exercised by this fixture. The report is tied to plugin SHA-256
`cccd4197127a0759da3cf849e01dbc3de1a43724e5f2e7ec74d61fd1214c2836` and is
archived at `reports/paper-1.21.1-state-validation-strict-20261103-r2.json`.

Previous fixture `srv-paper-state-guard-strict-20261103-r2` passed all 310 required
probes (291 passed, 19 expected rejections). State-load failure protection is
unit tested but its negative paths are not exercised by this fixture. Report:
`reports/paper-1.21.1-state-guard-strict-20261103-r2.json`.

Previous fixture `srv-paper-backup-save-strict-20261103-r2` passed all 310 required
probes (291 passed, 19 expected rejections) on the build with backup-save
protection. It does not specifically test the recovery/save sequence. Report:
`reports/paper-1.21.1-backup-save-strict-20261103-r2.json`.

Previous fixture `srv-paper-web-strict-20261103-r1` passed all 310 required
probes (291 passed, 19 expected rejections), including HTTP spawner reload
failure and recovery on the server thread. Browser rendering and the loot
failure path are not covered. Report:
`reports/paper-1.21.1-web-strict-20261103-r1.json`.

Previous fixture `srv-paper-spawner-null-20261103-r2` passed all 309 required
probes (290 passed, 19 expected rejections). Forced spawns verify native direct
and merged null conditions are rejected and a valid empty-list override passes;
natural scheduling and imported spawners are not covered by these probes. Report:
`reports/paper-1.21.1-spawner-null-20261103-r2.json`.

Previous fixture `srv-paper-merged-null-20261103-r1` passed all 306 required
probes (289 passed, 17 expected rejections). Its server-side importer and loot
service probe verifies cross-file inherited null rejection, parent warnings,
stale-table isolation and a valid sibling. It does not test invalid merged
conditions through natural death. Report:
`reports/paper-1.21.1-merged-null-20261103-r1.json`.

Previous fixture `srv-paper-native-numeric-20261103-r1` passed all 305 required
probes (288 passed, 17 expected rejections). Its native file-loading and loot
service probe verifies null chance rejection, tier weight fallback, valid
overrides, and warnings. It does not test invalid values through a natural death.
Report: `reports/paper-1.21.1-native-numeric-20261103-r1.json`.

Previous fixture `srv-paper-native-null-20261103-r1` passed all 304 required probes
(287 passed, 17 expected rejections). Its server-side native file-loading and
loot service probe verifies inherited null-condition rejection for items and
experience, warnings, and a valid override. It does not test invalid conditions
through a natural death. Report:
`reports/paper-1.21.1-native-null-20261103-r1.json`.

Previous fixture `srv-paper-unsafe-drop-references-20261103-r1` passed all 303
required probes (286 passed, 17 expected rejections). The server-side importer
and runtime probe checks warnings, indirect unsafe references, stale table
collisions, and a valid sibling. Report:
`reports/paper-1.21.1-unsafe-drop-references-20261103-r1.json`.

Previous fixture `srv-paper-null-drop-conditions-20261103-r1` passed all 302 required
probes (285 passed, 17 expected rejections). Its server-side importer and loot
service probe verifies null-condition rejection, warnings, and a working valid
sibling. Report: `reports/paper-1.21.1-null-drop-conditions-20261103-r1.json`.

Previous fixture `srv-paper-visibility-arguments-20261103-r1` passed all 301 required probes
(284 passed, 17 expected rejections) on Paper 1.21.1-133. Its report is
`reports/paper-1.21.1-visibility-arguments-20261103-r1.json`, with plugin SHA-256
`6078768cc112024d97caf360ddd8f184999e7d98dba1e98c761d4f85c23feb8d`.
The probe verifies a glowing, invisible and frozen Zombie target and rejects
six invalid conditions, checking each source and migration warning separately.
It does not establish positive climbing, falling or charged state behavior.

Previous-build fixture `srv-paper-time-weather-arguments-20261103-r1` passed all 300 required probes
(283 passed, 17 expected rejections) on Paper 1.21.1-133. Its report is
`reports/paper-1.21.1-time-weather-arguments-20261103-r1.json`, with plugin SHA-256
`cb69127cc60c6214b0f673be81811268604e62331e258983e3923c9103385256`.
Using the current world state, the probe checks morning and clear and their
opposites, rejects invalid morning, rain and negated thunder conditions, and
checks preserved source and migration warnings without changing the world.

Previous-build fixture `srv-paper-sneaking-mob-20261103-r1` passed all 299 required probes
(282 passed, 17 expected rejections) on Paper 1.21.1-133. Its report is
`reports/paper-1.21.1-sneaking-mob-20261103-r1.json`, with plugin SHA-256
`b1e3a460b895b1071c3d9018a772644dd12e9f75385cfcfcda17050ce013e13c`.
The imported target sneaking condition rejects a non-sneaking Zombie and runs
its name-changing action when that Zombie is sneaking.

Previous-build fixture `srv-paper-condition-state-20261103-r2` passed all 298 required probes
(281 passed, 17 expected rejections) on Paper 1.21.1-133. Its report is
`reports/paper-1.21.1-condition-state-20261103-r2.json`, with plugin SHA-256
`4238c4b92b9bd0374cfd1789ec9b632cb147a41824ea5378c1fe4f10d6a401b9`.
The new probe exercises target mob, alive and gravity conditions on a Zombie,
and rejects invalid target sneaking, mob, alive and negated mob arguments after
migration. It does not provide a player sneaking positive control.

Previous-build fixture `srv-paper-xp-count-20261103-r1` passed all 297 required probes
(280 passed, 17 expected rejections) on Paper 1.21.1-133. Its report is
`reports/paper-1.21.1-xp-count-20261103-r1.json`, with plugin SHA-256
`d18e058c936f277aef2c083530e490abfde146956650c66d454bc7d53dfcb7ed`.
Natural death, death-loot, and imported drop-table XP probes use orb counts;
the latter two also restore the value and count of pre-existing orbs.

Previous-build fixture `srv-paper-immunity-preservation-20261103-r2` passed all 297 required
probes (280 passed, 17 expected rejections) on Paper 1.21.1-133. Its report is
`reports/paper-1.21.1-immunity-preservation-20261103-r2.json`, with plugin SHA-256
`977bb1dfc2d370b29f10936a964e60012ba29c5143e67056b4735a6e2eb1028d`.
The preservation probe verifies nested null markers and list-form null conditions
survive migration, with warnings and real damage events rejecting both rules.
The preceding `srv-paper-immunity-preservation-20261103-r1` failed the strict
gate: the death event reported 13 XP but nearby orbs totaled 10. Its report and
server log are archived as `paper-1.21.1-immunity-preservation-20261103-r1-failed`;
that historical run did not record orb counts.

Non-strict fixture `srv-paper-xp-spawn-diagnostic-20261103-r1` recorded 13 event
XP and three uncancelled orbs of 7, 3 and 3 at the death location. Each orb
generated duplicate spawn notifications, so notifications must be deduplicated
by entity ID. The archived diagnostic report and log are
`reports/paper-1.21.1-xp-spawn-diagnostic-20261103-r1.json` and
`reports/paper-1.21.1-xp-spawn-diagnostic-20261103-r1.log.gz`. This later JAR
is not a strict release build. Follow-up non-strict fixture
`srv-paper-xp-repeat-20261103-r3` observed eight `7x1,3x2` stacks in 400 deaths;
face values total 10, but all 400 count-weighted totals equal 13. The updated
natural-death probe uses `ExperienceOrb.getCount()` and restores prior counts.
The earlier failed run did not record counts, so its precise cause is unproven.
Reports and logs for this run and the pre-fix 40-death run are archived as
`paper-1.21.1-xp-repeat-20261103-r3` and `paper-1.21.1-xp-repeat-20261103-r1`.
The latest strict build includes count-aware death-loot and imported-table probes.

Previous-build fixture `srv-paper-immunity-fields-20261103-r1` passed all 296 required probes
(279 passed, 17 expected rejections) on Paper 1.21.1-133. Its report is
`reports/paper-1.21.1-immunity-fields-20261103-r1.json`, with plugin SHA-256
`67d195d735334008331e082eda90589eb4b22d668e46266ee4d5f7a14e85b393`.
Real damage events verify rejection with warnings for a null unknown immunity
field, null and true unknown modifier fields, and invalid and null `immune` values.
Structured preservation of unsupported sections has no dedicated Paper probe.
The subsequent nested-mapping null restoration fix has importer/runtime tests
but is not included in this fixture's JAR.

Previous-build fixture `srv-paper-condition-arguments-20261103-r1` passed all 295 required probes
(278 passed, 17 expected rejections) on Paper 1.21.1-133. Its report is
`reports/paper-1.21.1-condition-arguments-20261103-r1.json`, with plugin SHA-256
`b5ce469ea90bd0ab2d4544b38809f24e9ad6f01aad130c4539937ba76646b2f6`.
The real-entity probe checks positive `isLiving`, `isBaby`, and `isFrozen`
conditions and rejects their unknown arguments after import.

Previous-build fixture `srv-paper-immunity-native-20261103-r1` passed all 294 required probes
(277 passed, 17 expected rejections) on Paper 1.21.1-133. Its report is
`reports/paper-1.21.1-immunity-native-20261103-r1.json`, with plugin SHA-256
`5f84140f98205e54d2fe0170bce72ee3ac3c7dff7962578cd0cd2a34470326c4`.
Real damage events verify that false, null and unknown-field native immunity
entries and null scalar modifiers are rejected with warnings.

Previous-build fixture `srv-paper-immunity-20261103-r1` passed all 293 required probes
(276 passed, 17 expected rejections) on Paper 1.21.1-133. Its archived report is
`reports/paper-1.21.1-immunity-20261103-r1.json`, with plugin SHA-256
`0d8ad01e34616f7123228c4262f332d96add56e1ba1b7d35f98d892a544f12e9`.
It covers imported conditional damage reduction, rejected conditions, and an
invalid native multiplier in real damage events. Explicit null multipliers,
multiple same-type rules, profile reload, and `lastSignal` argument validation
still lack dedicated Paper probes.
Subsequent explicit-null modifier and invalid native immunity entry fixes are
not included in this JAR.

Previous-build fixture `srv-paper-current-20261103-r4` passed all 292 required
probes (275 passed, 17 expected rejections) on Paper 1.21.1-133. The archived
report is `reports/paper-1.21.1-current-20261103-r4.json`, with plugin SHA-256
`03e417dfaf7918ba04b3a27e05d3eb446ef2c0f1bd59fcb66d11e378ff102f76`.
Recent immunity conditions, profile reload, and `lastSignal` argument validation
do not have dedicated Paper probes.
Subsequent modifier migration preservation and validation changes are not in that JAR.

Previous-build fixture `srv-paper-current-20261103-r3` passed all 292 required
probes (275 passed, 17 expected rejections) on Paper 1.21.1-133. The archived
report is `reports/paper-1.21.1-current-20261103-r3.json`; migration backup and
failure handling do not have dedicated Paper probes.
Later `lastSignal` argument validation is not included in the tested JAR.

Previous-build fixture `srv-paper-current-strict-20261103-r2` passed the full
Paper 1.21.1-133 strict catalog: 275 passed and 17 expected rejections. Its
archived report is `reports/paper-1.21.1-current-strict-20261103-r2.json`, tied to
plugin SHA-256 `f9ed76327e1f36688b34c2fd7eaba0ce5ef0bcea0046920a9996ea3c3616f6e2`.
This is a regression run of the existing catalog; recent malformed drop input
and large dynamic weight boundaries do not have dedicated Paper probes.

Paper 1.21.1-133 strict baseline `srv-paper-world-target-validity-20261101-r2`
completed 250 probes: 238 positive observations and 12 expected negative
controls. Later strict follow-ups below supersede that baseline.

Earlier full strict fixture `srv-paper-nested-import-strict-20261102-r1` completes
291 probes (274 passed and 17 expected rejections). It verifies a bare table
reference from a nested source directory, cross-file import, and natural death
output. Its report is `reports/paper-1.21.1-nested-import-20261102.json`.
Earlier full strict fixture `srv-paper-skill-lifecycle-20261102-r1` completes
291 probes (274 passed and 17 expected rejections). An isolated service on a
managed Zombie checks in-flight reload and delayed cancellation after `forget`.
Its archived report is `reports/paper-1.21.1-skill-lifecycle-20261102.json`.
Earlier full strict fixture `srv-paper-imported-drop-contract-strict-20261102-r1`
completes 284 probes and verifies imported DropTable conditions, conflicting
table preservation, and natural managed-Zombie death output of one Diamond,
one Gold Ingot from the exclusive `1-3` range, and 5 XP. Its archived report is
`reports/paper-1.21.1-imported-drop-contract-20261102.json`.
Earlier strict fixture `srv-paper-named-conditions-strict-20261102-r1`
completes 284 probes and verifies valid negated world selection plus rejection
of invalid world, moon-phase, and biome arguments. Its archived report is
`reports/paper-1.21.1-named-conditions-20261102.json`.
Earlier strict fixture `srv-paper-negated-conditions-strict-20261102-r1`
completes 280 probes (266 passed, 14 expected negative controls), including
valid negated height and invalid negated height and light spawner conditions.
The archived report is `reports/paper-1.21.1-negated-conditions-20261102.json`.

Earlier full strict fixture `srv-paper-integration` completes all 271 registered
probes: 259 passed, 12 expected negative controls, and zero incomplete. The
archived report is
`tools/paper-integration/reports/paper-1.21.1-full-strict-20261102.json`.
It records the Survival condition's positive/negative branches and restored
player mode, the Ender Dragon alive condition's accepted/removed-negative
lifecycle, and the natural Lava probe's real
`DamageCause.LAVA`, confirms the configured damage action ran, and restores
the temporary block and entity. The same run also requires the `pinTargeters`
probe, which executes
multi-point and random `@Pin` dispatch through `SkillService`, verifies that
`MovePin` updates a single-point pin and rejects a multi-point pin, then sets
and breaks all four real blocks selected by an inclusive
`@BlocksInPinRegion` cuboid and restores their original states. It also rejects
a 16,385-block region and verifies it dispatches zero downstream actions. The
fixture also verifies the closed player gate, natural Potion consumption/expiry,
and menu/Audience workflows. The real menu client performs denied and allowed
clicks, paginates, observes the action feedback, and confirms the close. Menu
button materials and click outcomes are verified from Paper's inventory/render
and click logs because Mineflayer does not decode the 1.21.1 menu item contents
reliably. The earlier dedicated strict client-walk fixture remains archived;
the current full strict release also requires that workflow and verifies real
player SpawningDistance movement and entity removal.

Strict targeter follow-up `srv-paper-targeter-players-strict-20261102-r1`
completes all 272 registered probes (260 passed, 12 expected negative
controls, zero incomplete). Its production `ai-selector-core` probe verifies
that `@PlayersOnServer` selects the current server player set and `@everyone`
returns the same targets. Unit coverage separately verifies world isolation,
online/valid/dead filtering and `targetself=true`. The archived report is
`tools/paper-integration/reports/paper-1.21.1-targeter-world-players-20261102.json`.

Strict Conditions follow-up `srv-paper-distance-from-spawn-strict-20261102-r1`
also completes all 265 probes with zero incomplete. Its production condition
probe measures a real entity's distance from the Paper world's spawn location;
the exact `<= distance` boundary passes and `> distance` is rejected. The
report is archived at
`tools/paper-integration/reports/paper-1.21.1-distance-from-spawn-20261102.json`.

Previous full strict fixture `srv-paper-menu-client-strict-20261102-r2`
completes all 264 registered probes: 252 passed, 12 expected negative
controls, and zero incomplete.

Strict Potion-consumption follow-up `srv-paper-strict-potion-20261101-r5`
completes 252 probes: 241 passed and 11 expected negative controls rejected.
It records `playerPotionConsume=passed` from a raw 1.21.1 protocol client after
the menu workflow disconnects and the same player reconnects for a real drink.
The older 250-probe fixture remains the baseline evidence for its original
catalog; r5 is additive and specifically closes strict player Potion consumption.

Strict Potion natural-expiry fixture `srv-paper-potion-expiry-strict-20261101-r1`
completes 255 probes on Paper 1.21.1-133. It records both
`playerPotionConsume=passed` and `playerPotionNaturalExpiry=passed`; the real
player's short effect expires and executes the configured action. When
`PAPER_RAW_POTION_SHORT=true` is requested with the raw Potion workflow, the
report requires the natural-expiry probe and emits `not-observed` if its event
is absent.

Strict Mob Options follow-up `srv-paper-mob-max-combat-distance-20261101-r5`
completes 253 probes: 241 passed, 12 expected negative controls rejected, and
zero incomplete. It verifies `Options.MaxCombatDistance=8` through production
spawn and Bukkit damage dispatch, allowing the 7-block control and cancelling
the 9-block hit; menu/Audience and real player Potion consumption also pass.

Strict legacy Mob Options follow-up `srv-paper-mob-spawning-distance-20261101-r2`
also completes 253 probes: 241 passed, 12 expected negative controls rejected,
and zero incomplete. It reads `SpawningDistance=8` through the normal mob
loader as `mode=NORMAL distance=8.0`. The separate strict fixture
`srv-paper-spawning-distance-runtime-20261101-r5` verifies nearby retention and
production removal of both the managed instance and Bukkit entity beyond the
configured radius. Its strict release report observes 254 probes with zero
incomplete; the `spawningDistanceRuntime` probe passes.

Strict block-selector follow-up `srv-paper-block-selector-strict-20261101-r2`
completes 253 probes: 242 passed, 11 expected negative controls rejected, and
zero incomplete. Its `conditionUtilityAliases` probe independently checks
exact materials, material globs, and Paper block tags through `BlockType`,
`OnBlock`, and `BlockTypeInRadius`, alongside the real-player
`spawningDistanceRuntime` nearby-retention and out-of-range entity-removal
assertions.

The follow-up fixture also verifies the root Mythic `Exp` path through
`exp-options result=true experience=13 event=true`.

Strict fixture `srv-paper-exp-natural-death-20261101-r3` adds a distinct
`exp-natural-death` probe. It applies Bukkit damage to a second managed Zombie,
asserts the entity died, totals only newly spawned nearby experience orbs, and
cleans up those orbs. The report records 255 probes (243 passed, 12 expected
negative controls, zero incomplete). The synthetic death-event probe remains
separate so dispatch coverage is not confused with the real damage/death path.

Strict fixture `srv-paper-drops-natural-death-20261101-r4` strengthens the
existing `death-loot` probe. It records pre-existing Item/ExperienceOrb UUIDs,
applies Bukkit damage to a bound Zombie using `integration_death`, and only
counts and removes entities generated by that death. The report requires
`natural=true dead=true cause=BukkitDamage`; it observes one condition-matched
DIAMOND stack, no rejected BREAD entry, and 9 experience points within the
table's 9–11 range. All 255 probes complete (243 passed, 12 expected negative
controls, zero incomplete).

Strict fixture `srv-paper-drops-trigger-conditions-20261101-r7` checks table
conditions through natural player-caused deaths. The accepted parent requires
`is-player` and contains a nested table gated by `loot.vip`; an OP player passes
and a non-OP player is rejected. The rejected parent also requires a missing
world. The report requires the nested Diamond to appear for the OP killer, the
non-OP nested Diamond to stay absent while the accepted parent still grants 4
XP, and the rejected parent to produce neither the Diamond nor its configured
5 XP. This strict run records 258 observations: 246 passed, 12 expected
negative controls, and zero incomplete.

Strict fixture `srv-paper-dropper-conditions-strict-20261102-r1` completes 268
release probes. It kills two managed Zombies through real Bukkit damage: the
`is-monster` table condition permits its Emerald and 3 configured XP, while
the `is-animal` condition rejects its BREAD and 7 configured XP. The report is
archived at
`tools/paper-integration/reports/paper-1.21.1-dropper-conditions-20261102.json`.

Strict fixture `srv-paper-exp-lines-strict-20261102-r2` completes 268 probes
(256 passed, 12 expected negative controls, zero incomplete). Its natural
managed-Zombie death probe observes 16 XP: the table's 9-11 base plus one
guaranteed 5 XP `exp` drop entry. The archived report is
`tools/paper-integration/reports/paper-1.21.1-exp-drop-lines-20261102.json`.

The DropTable generated-line probe requires 128 total items and broad bounds
around the expected 32 Diamond / 96 Emerald split from a real player-caused
managed Zombie death. Strict fixture
`srv-paper-drops-total-items-strict-20261102-r1` observed 33 DIAMOND and 95
EMERALD by amount, with all 266 registered probes complete and zero
incomplete. The archived report is
`tools/paper-integration/reports/paper-1.21.1-drops-total-items-20261102.json`.

Strict fixture `srv-paper-drops-bonus-semantics-strict-20261102-r1` exercises
natural managed-Zombie deaths and reports 4 level-only items, 3 Luck-only
items, 5 combined items, a 2-item zero-bonus baseline, and a single-bound
weighted selection (`weighted-max=4 weighted-item-count=true`). The report
report contains all 273 registered probes (262 passed, 11 expected negative
controls, zero incomplete) and is archived at
`tools/paper-integration/reports/paper-1.21.1-drops-bonus-semantics-20261102.json`.
The report parser requires this weighted-selection evidence; the earlier r4
report only established line counts and does not prove weighted selection.
Strict fixture `srv-paper-fractional-bonus-strict-20261102-r4` records 63 extra
lines across 128 natural managed-Zombie deaths with a level-one 0.5 bonus. Its
273-probe report has 261 passed and 12 expected negative controls; raw Potion
consumption and natural expiry were not requested in this run. `BiasStat`, custom
Mythic Luck enchantment/curses inputs, and the broader official DropTable
catalog remain unverified. The official DropTables catalog does not define a
separate `Looting` table option.

Strict fixture `srv-paper-raider-patrol-condition-strict-20261101-r1` adds a
dedicated `raiderPatrolLeaderCondition` probe. It reads a real Pillager's
`Raider#isPatrolLeader()` state through the production condition service, checks
both boolean states, restores the leader flag, and records 259 observations
(247 passed, 12 expected negative controls, zero incomplete).

Strict fixture `srv-paper-creeper-primed-condition-strict-20261101-r1` adds a
dedicated `creeperPrimedCondition` probe. It evaluates Mythic
`IsCreeperPrimed` against a real Creeper through `SkillService`, checks ignited
and non-ignited states, restores the ignited state, and passes the 259-probe
strict report (247 passed, 12 expected negative controls, zero incomplete).

Strict fixture `srv-paper-damage-tag-strict-20261102-r3` completes 261 probes
on Paper 1.21.1-133: 249 passed, 12 expected negative controls, zero
incomplete. Its required `damageTagCondition` probe first dispatches an
untagged event and confirms that it does not run the configured `on-damage`
actions. It then runs `damage-target 3 tags=FIRE` against the managed probe
through Bukkit, confirms the `damage-tag`, `damage-cause`, and final
`damage-amount` conditions, and observes the action cancel the event while
preserving target health. The same observation supplies the
`skillTrigger`, `damageCauseCondition`, and `cancelledCondition` probes.

Strict Conditions follow-up `srv-paper-drowning-strict-20261102-r2` completes
262 probes on Paper 1.21.1-133: 250 passed, 12 expected negative controls, and
zero incomplete. It extends the required `conditionCatalog` log contract with
`drowning-condition=true`, checking the production caster remaining-air
comparator on both sides of the threshold. The schema validator and strict
completeness gate passed.

Strict biome-category fixture `srv-paper-biome-type-category-strict-20261101-r1`
completes 254 probes: 243 passed, 11 expected negative controls rejected, and
zero incomplete. The `biomeCondition` release probe verifies `BiomeType` with
`exact=false` against a real `DESERT` biome (`desert` matches and `plains`
rejects), comma-separated category alternatives, exact mode, and biome
restoration. It also verifies that the category extension retains the existing
exact `BiomeType` behavior.

Run `pwsh -NoProfile -File tools/paper-integration/audit-evidence.ps1` to inspect
the evidence inventory. It scans the root README, parity matrix, Paper README,
and startup evidence document and reports documented `srv-paper-*` fixture
directories that are absent from the workspace, including each source document
and line. Versioned Paper names are treated as single references. Maven
`verify` reads `release-evidence.json` and requires every listed report to be
archived under `tools/paper-integration/reports/`, match its release fixture
or an explicit `reportFixtureDirectory` source mapping, target Paper 1.21.1,
and contain every expected probe without incomplete results. A declared
`reportSha256` is checked against the archived report. At least one manifest
entry must set the boolean `fullReleaseCatalog`
to `true`; that report must satisfy the current release probe catalog. Other
absent references stay visible as historical inventory and do
not count as release evidence. Add a fixture to the manifest only after its
report is archived and validated. Run
`pwsh -NoProfile -File tools/paper-integration/test-audit-evidence.ps1` to
exercise version parsing, source locations, and required-presence failure.

New server runs record the SHA-256 of the copied plugin JAR in `pluginSha256`.
The runner verifies that the JAR is unchanged at shutdown, and strict mode
requires the report hash to match. This identifies the input JAR, not its source
commit or Paper's internally remapped artifact. Historical reports without the
field remain valid for their original scope.
Before releasing a built JAR, run
`pwsh -NoProfile -File tools/paper-integration/audit-evidence.ps1 -ReleaseGate -ReleasePluginJar <path-to-jar>`.
This additionally requires the newest full-catalog report to match that exact
JAR's SHA-256; a normal Maven evidence audit checks archived reports but does
not establish that the current build was tested on Paper.
`-ReleaseGate` requires a JAR argument and enables required-presence checks.
The required-presence audit also requires the latest full-catalog report to
declare archived server and client logs with matching SHA-256 digests.

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

Add `-NaturalScheduling` to verify the restart cooldown through production
natural scheduling. The isolated fixture observes a scheduled spawn, saves its
remaining cooldown on shutdown, then checks that the next boot waits for the
restored cooldown before its next natural spawn. It does not issue a force
command. It also checks that `Chance: 0` and a non-finite explicit Chance both
remain at zero natural spawns across both boots:

```powershell
powershell -ExecutionPolicy Bypass -File tools/paper-integration/test-spawner-restart.ps1 -NaturalScheduling
```

The archived Paper 1.21.1-133 runs are
`reports/paper-1.21.1-spawner-natural-restart-20261106-r1.json`,
`-r2.json`, `-r4.json`, and `-r5.json`. The latest run verifies invalid
non-finite Chance rejection alongside the one-scheduler-period cooldown bound.

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

For a standalone real potion-consumption probe without Mineflayer's
potion-component decoder, use the opt-in exploratory raw protocol client:

```powershell
$env:PAPER_PLAYER_WORKFLOW = 'true'
$env:PAPER_RAW_POTION_WORKFLOW = 'true'
$env:PAPER_SKIP_PLAYER_POTION = 'false'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 48 -Exploratory
```

For the strict Paper 1.21.1 gate, enable the menu and short raw Potion workflows.
The full release check independently requires passing `playerPotionConsume`
and `playerPotionNaturalExpiry` probes, including when a report omits them from
its own catalog. After the menu client completes, the runner
waits for it to disconnect, reconnects the same player with the raw protocol
client, equips a server-issued potion, and requires the real consume event. A
requested but unobserved event remains in the report as `not-observed`, so the
strict gate fails instead of silently dropping this probe.

```powershell
$env:PAPER_MENU_WORKFLOW = 'true'
$env:PAPER_RAW_POTION_WORKFLOW = 'true'
$env:PAPER_RAW_POTION_SHORT = 'true'
$env:PAPER_SKIP_PLAYER_POTION = 'false'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 150 -Strict
```

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

The native catalog check evaluates `IsWaterMob` on a real Cod and `IsZombie` on
a real Zombie, with cross-type rejection for each. Strict Paper 1.21.1-133
fixture `srv-paper-entity-category-import-strict-20261106-r2` additionally
routes both primary names and aliases through the Mythic importer and runtime,
and verifies malformed-argument preservation. Exploratory Paper 1.21.11 r99
records the earlier native-only paths.

The condition catalog also checks official `Aerial`: a real floating ArmorStand
passes both `aerial` and `isAerial`, while a grounded target rejects the
condition. Exploratory Paper 1.21.11 r102 records `aerial-condition=true`;
strict Paper 1.21.1-133 fixture `srv-paper-aerial-condition-strict-20261105-r2`
records the independent `aerialCondition` probe and confirms the grounded
negative control was a player.

The catalog also checks official `IsEnderman` on a real Enderman and rejects
the same condition for the managed Zombie. Exploratory Paper 1.21.11 r103
records `enderman-condition=true`; strict Paper 1.21.1 release evidence
remains separate.

The catalog also checks official `HasEnchantment` against a temporary Sharpness
III diamond sword on the real managed Zombie. Sharpness `>2` and `ANY=3` pass;
Sharpness `>3` and Looting reject. Exploratory Paper 1.21.11 r104 records
`enchantment-condition=true`. Strict Paper 1.21.1-133 fixture
`srv-paper-has-enchantment-import-strict-20261105-r1` additionally verifies
Mythic import mapping and SkillService evaluation for `?hasEnchantment` and
`?hasEnchant`, with positive, level/type negative, and equipment-restoration
observations in `importedHasEnchantmentCondition`.

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

The same temporary SPEED effect is checked through the internal
`has-aura-stacks` evaluator; Bukkit amplifier 0 must satisfy the one-stack
range and the structured log records `aura-stacks-condition=true`. Exploratory
fixture r8 records the independent `auraStacksCondition` probe passed; this
exercises Bukkit PotionEffect data only and does not verify Mythic Aura parity.

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
`frozenCondition=passed` probe. Strict Paper 1.21.1-133 fixture
`srv-paper-runtime-alias-20261031-r2` also records
`frozenCondition=passed`.

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

The online-player self-test controls a real Player's server-side rotation,
places a temporary STONE block along the eye ray, and checks positive/negative
`LookingAt` paths before restoring the block and player state. The strict
imported block/entity paths are covered by
`srv-paper-looking-at-import-strict-20261106-r1`; natural client-originated
view direction remains unverified.

The same online-player self-test uses Bukkit `LivingEntity#attack` against an
temporary Zombie, verifies `HumanEntity#getAttackCooldown()` falls below `1.0`,
and checks the `ItemRecharging` condition. Exploratory fixture r3 records
`itemRechargingCondition=passed` after a real Bukkit attack. Strict fixture
`srv-paper-item-recharging-import-strict-20261106-r1` separately records the
imported primary and alias forms as passed, plus the non-HumanEntity rejection.

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
`dialogue-condition=true`. The later strict fixture
`srv-paper-cinematic-condition-20261105-r1` records the same lifecycle in its
aggregate condition observations.

The same probe checks official `IsInCinematic` against the connected player
target: it passes while the temporary cinematic is active and rejects after
stop. Exploratory Paper 1.21.11 r107 records the combined dialogue condition
probe as passed. The later strict fixture
`srv-paper-cinematic-condition-20261105-r1` additionally verifies audience
range, non-player rejection, and stopped state.

Target water/lava checks use a dedicated delayed probe: Paper updates an
entity's cached fluid state on the tick after block placement, so the probe
places water, evaluates on the next tick, switches to lava, evaluates again,
and restores both blocks. Same-tick checks remain diagnostic only.

The combined Mob Option self-test runs `mob-options-natural`, which applies the
runtime `creeper-fuse-ticks` option, ignites a real Creeper, explicitly sets
protected/control Bees to stung and dispatches synthetic Bukkit attack events,
and runs protected/control Zombie conversion. `bee-event=synthetic` marks this
path in the report; it does not establish a natural Bee AI attack. The test
also emits a separate `mob-options-mount-event` probe for protected/control
jockey mount event cancellation. The explicit control remains on the Bukkit
mount event path, while strict r4 additionally invokes Paper's native Zombie
`NATURAL` spawn finalization and observes the protected cancellation/control
allowance pair. Timeout or missing behavior remains `not-observed`.

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
$env:PAPER_MENU_WORKFLOW = 'true'
$env:PAPER_RAW_POTION_WORKFLOW = 'true'
$env:PAPER_RAW_POTION_SHORT = 'true'
$env:PAPER_INTERACTABLE_WORKFLOW = 'true'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 `
  -RunSeconds 150 -Strict
```

Strict mode requires Python with `jsonschema` 4.x in addition to the workflows
needed by the registered probes (including the environment variables above and
the fixture's Mineflayer dependencies for player/menu/Audience evidence). The
Interactable workflow uses the real client's offhand entity-interact packet and
requires both the client report and Paper's final cancellation observations. It
also rejects reports that
do not pass the JSON Schema, prove plugin enablement, or prove Paper
implementation/API version 1.21.1, or include every registered probe listed in the
report's expected probe catalog. The independent `release-probe-catalog.json`
also requires each baseline release probe and its expected `passed` or
`observed-rejected` status, even if a report omits it from its own catalog.
Negative Spawner controls must also report the specific rejection reason in the
catalog; a different failure cannot establish the condition being tested.
Update this contract deliberately when release coverage changes; older archived
reports retain their historical scope. It also rejects duplicate or unregistered
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

It uses two `PIG` persistent mobs with the same mob ID, runs two Paper
boots against the same isolated world, and verifies their UUID-keyed SkillTree
markers and branches restore independently. The second boot kills one mob and
checks that shutdown removes only its snapshot. For a non-release Paper fixture, pass
`-Exploratory -PaperJarPath <path-to-paper.jar>` and set
`PAPER_LIBRARIES_PATH` to the matching Paper runtime libraries directory; the
run is evidence only and does not satisfy the strict Paper 1.21.1 gate.

Strict follow-up `srv-paper-ender-dragon-phase-strict-20261101-r3`
passes all 260 registered probes on Paper 1.21.1-133: 248 passed, 12 expected
negative controls, and zero incomplete. It includes real-dragon
`EnderDragonPhase` positive/list/negative checks, fail-closed rejection of a
mixed valid/invalid phase list, border-clipped natural spawning, and both
branches of the real-player `LookingAt` condition. The schema validator and
release completeness gate pass; the report is retained at
`srv-paper-ender-dragon-phase-strict-20261101-r3/integration-report.json`.

Latest strict follow-up `srv-paper-mobs-in-chunk-boundary-strict-20261102-r1`
passes all 263 registered probes on Paper 1.21.1-133: 252 passed, 11 expected
negative controls, and zero incomplete. Its required `mobsInChunkCondition`
probe checks zero count, a real Mob in the adjacent occupied chunk, active chunk
counting, adjacent chunk exclusion, and rejection of an unreachable high count
threshold. The report schema and release completeness gate pass; the report is
retained at
`srv-paper-mobs-in-chunk-boundary-strict-20261102-r1/integration-report.json`.
This same report also requires and passes `damageTagCondition` and the
`conditionCatalog` Drowning comparator evidence.

Latest strict follow-up `srv-paper-spawning-distance-client-walk-strict-20261102-r3`
passes all 264 registered probes on Paper 1.21.1-133: 253 passed, 11 expected
negative controls, and zero incomplete. A real Mineflayer client receives the
server readiness message, flies 14.01 blocks, and records that movement in its
client log. The production server probe independently measures 8.47 blocks of
player displacement, then confirms the SpawningDistance managed instance and
Bukkit entity are both removed. The report schema and release completeness
gate pass; the report is retained at
`srv-paper-spawning-distance-client-walk-strict-20261102-r3/integration-report.json`.

Strict follow-up `srv-paper-condition-action-suffix-strict-20261106-r1` passes
all 362 required probes on Paper 1.21.1-133: 343 passed, 19 expected negative
controls, and zero incomplete. Its `importedBurningCondition` probe verifies
the imported `true`/`false` suffix actions, composition with `!`, active-target
priority over the caster, no-target caster fallback, and preservation of an
invalid suffix. The report and compressed server/client logs are archived under
`tools/paper-integration/reports/` and listed in `release-evidence.json`. The
full Conditions catalog remains incomplete.
