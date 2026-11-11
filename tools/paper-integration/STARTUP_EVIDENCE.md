# Paper Startup Evidence

Latest fixture `srv-paper-passthrough-damage-strict-20261111-r3` passed the full strict gate on Paper 1.21.1-133: 436 registered probes, 417 passed, 19 expected rejections, and zero failed or incomplete. The `passthroughDamageOptions` probe verifies imported `PassthroughDamage:true`, an automatically linked parent on imported summon, preserved `DamageSource` and cause, disabled/orphan fallback, cancelled-hit behavior, cycle safety, and invalid-value preservation. `executionAudienceMobSummon` verifies production mob spawning, parent UUID linkage before spawn events, inherited audience, and member/outsider visibility. Plugin SHA-256: `4358cee22c0ff46a9e009fffbf16c718adb6a267880d23369c69095627ab2ca4`. Report: [paper-1.21.1-passthrough-damage-strict-20261111-r3.json](reports/paper-1.21.1-passthrough-damage-strict-20261111-r3.json), SHA-256 `48e17247b2e41737f7523e5fbdd79377eeaed1c7e32fff6e2eedbb82af428141`. Compressed redacted server log: [paper-1.21.1-passthrough-damage-strict-20261111-r3.log.gz](reports/paper-1.21.1-passthrough-damage-strict-20261111-r3.log.gz), SHA-256 `c09d9d0ea96df16e61b1a8d57fc61971e1f6184ec75732aac331b164a43d46fd`. Client log archive: [paper-1.21.1-passthrough-damage-strict-20261111-r3-client-logs.tar.gz](reports/paper-1.21.1-passthrough-damage-strict-20261111-r3-client-logs.tar.gz), SHA-256 `8338ec4765d4566f3cd90f65e0654581367c6c977f03f36c4cd62c0efe8784be`. Archived logs redact token query values.

Previous fixture `srv-paper-villager-options-strict-20261111-r4` verified imported Villager `Type`, `Profession`, `Level`, and `HasTrades`; its report and hashes remain in `release-evidence.json` as historical evidence.

Previous fixture `srv-paper-villager-options-strict-20261111-r2` verified Villager `Type`, `Profession`, and `Level`; its report and hashes remain in `release-evidence.json`.

Previous fixture `srv-paper-parrot-flying-speed-strict-20261111-r2` passed the full strict gate on Paper 1.21.1-133:
434 registered probes, 415 passed, 19 expected rejections, and zero failed or
incomplete. Its `parrotFlyingSpeed` probe reads back Mythic `Options.FlyingSpeed: 0.8`
through production spawning; the `-1` sentinel preserves native speed, while
wrong-entity and invalid values remain preserved with warnings. Report, redacted
server/client logs, and hashes remain indexed in `release-evidence.json`.

Previous fixture `srv-paper-panda-options-strict-20261111-r1` passed the full strict gate on Paper 1.21.1-133:
433 registered probes, 414 passed, 19 expected rejections, and zero failed or
incomplete. The dedicated `pandaOptions` probe imports `MainGene: LAZY` and
`HiddenGene: WORRIED`, reads back both genes from a production-spawned Panda,
and verifies `NORMAL` controls. Wrong entity types and invalid genes are
preserved with warnings. Plugin SHA-256: `8741a5d35063e64995121457a09f4c05f476cdea550c1656542298a4ad1b3398`.
Report: [paper-1.21.1-panda-options-strict-20261111-r1.json](reports/paper-1.21.1-panda-options-strict-20261111-r1.json), SHA-256:
`bc1f819e540d62ab67adaa5281486806d134f5bc496dc60aca6e6d9aabcae08f`. Compressed redacted server log:
[paper-1.21.1-panda-options-strict-20261111-r1.log.gz](reports/paper-1.21.1-panda-options-strict-20261111-r1.log.gz), SHA-256:
`9b8aa4e520630ba2435a59c459e97917e5ef8a52e70027e1b323e9faea74652b`. Client log archive:
[paper-1.21.1-panda-options-strict-20261111-r1-client-logs.tar.gz](reports/paper-1.21.1-panda-options-strict-20261111-r1-client-logs.tar.gz), SHA-256:
`c4f87648dbc2924a2d5fac3d8877bdb6c3e7db36eb6d610c2e63ef66eef2b3d0`. Archived logs redact token query values.

Previous fixture `srv-paper-experience-orb-strict-20261111-r1` passed the full
432-probe strict gate (413 passed, 19 expected rejections, zero failed or
incomplete). Its `experienceOrbOptions` probe imports Mythic `Options.Experience`
only for `EXPERIENCE_ORB` and reads back 17 from a production-spawned orb;
wrong-entity and malformed values remain preserved. The report and artifact
hashes are indexed in `release-evidence.json`.

Previous fixture `srv-paper-tameable-families-strict-20261111-r3` passed the full strict gate on Paper 1.21.1-133:
431 registered probes, 412 passed, 19 expected rejections, and zero failed or
incomplete. The dedicated `tamedFamilyOptions` probe imports `Options.Tamed`
for Donkey, Mule, Llama, Trader Llama, Skeleton Horse, Zombie Horse, and Camel
through production spawning. Donkey, Mule, Llama, Trader Llama, Skeleton Horse,
and Zombie Horse each read back true/false control states. Paper 1.21.1 always
reports Camel as tamed, so imported `Tamed:false` is retained under
`entity.unsupported.Options.Tamed` with a warning; Camel true reads back as
tamed. Earlier strict fixtures cover Wolf, Cat, Horse, and Parrot. Plugin
SHA-256: `6a2811b446426eeb3bc4eb18946143f92a2a68ebc55fb1cc28e6d478ba9a8779`.
Report: [paper-1.21.1-tameable-families-strict-20261111-r3.json](reports/paper-1.21.1-tameable-families-strict-20261111-r3.json), SHA-256:
`d0b88acdb37fa8d6d16c7a15c026f4ba8ed1165f20f85b920754b72ca3c2c588`. Compressed
redacted server log: [paper-1.21.1-tameable-families-strict-20261111-r3.log.gz](reports/paper-1.21.1-tameable-families-strict-20261111-r3.log.gz), SHA-256:
`a7e54bf8ef26848ff8eded4fce628ee3d491e9ed5f465cfb9a929027c89279b2`. Client
log archive: [paper-1.21.1-tameable-families-strict-20261111-r3-client-logs.tar.gz](reports/paper-1.21.1-tameable-families-strict-20261111-r3-client-logs.tar.gz), SHA-256:
`9b77621c915f2d4532b3e83365b55383a4a4d055edbd071c32d5850d3ddb487c`. Archived
logs redact token query values.

Previous fixture `srv-paper-tameable-families-strict-20261111-r2` passed the full strict gate on Paper 1.21.1-133:
430 registered probes, 411 passed, 19 expected rejections, and zero failed or
incomplete. The `entityFlagOptions` probe imports `Options.Tamed` on Cat, Horse,
and Parrot through production spawning and reads back true/false states for all
three families alongside Wolf coverage. Plugin SHA-256:
`70f007403ff552aebac595542f52ed0b3028c14206fc56bdac56c6186045f821`. Report:
[paper-1.21.1-tameable-families-strict-20261111-r2.json](reports/paper-1.21.1-tameable-families-strict-20261111-r2.json), SHA-256:
`55b72d54b7c6ee8bd4e26bb2f3acf9282121a7f1b86944cabcec4631edc6e560`. Compressed redacted server log:
[paper-1.21.1-tameable-families-strict-20261111-r2.log.gz](reports/paper-1.21.1-tameable-families-strict-20261111-r2.log.gz), SHA-256:
`c5ca550e0724f71e54e7ca5b7199e5f6a89aff0bd6f8eb70879297c69df75a23`. Client log archive:
[paper-1.21.1-tameable-families-strict-20261111-r2-client-logs.tar.gz](reports/paper-1.21.1-tameable-families-strict-20261111-r2-client-logs.tar.gz), SHA-256:
`fe5862e38e12c2560eec32fc05e2a4116c593ba71654e6516eda7bdaf4aa4712`. Archived logs redact token query values.

Previous fixture `srv-paper-steerable-saddles-strict-20261111-r1` passed the full strict gate on Paper 1.21.1-133:
430 registered probes, 411 passed, 19 expected rejections, and zero failed or
incomplete. The `horseOptionsImported` probe imports Horse `Saddled` and
`HorseArmor`, Donkey `CarryingChest`, and `Saddled` on Pig and Strider. Through
production spawning it reads back Horse saddle/diamond armor, Donkey chest
true/false, and Pig/Strider saddle true/false controls. Horse color, style,
domestication, and jump-strength controls also pass. Plugin SHA-256: `e1bc4e9c44c5fc1e80dfbba1d0b72dd2e9cab8cbc92648e291e9fb9734e46198`. Report:
[paper-1.21.1-steerable-saddles-strict-20261111-r1.json](reports/paper-1.21.1-steerable-saddles-strict-20261111-r1.json), SHA-256: `4c69994a9e71990e8cedf290e5cb3198029d8863e0c792d89cfb2272fd40052c`. Compressed redacted server log:
[paper-1.21.1-steerable-saddles-strict-20261111-r1.log.gz](reports/paper-1.21.1-steerable-saddles-strict-20261111-r1.log.gz), SHA-256: `df6e7397ae008a211218c0b2a33051de851d8518bde22161bb67a24b16386585`. Client log archive:
[paper-1.21.1-steerable-saddles-strict-20261111-r1-client-logs.tar.gz](reports/paper-1.21.1-steerable-saddles-strict-20261111-r1-client-logs.tar.gz), SHA-256: `f31df7258b7c0c86c4ace8538d42b1422e457b3a50ef65037a28119682f8d92d`. Archived logs redact token values.

Previous fixture `srv-paper-horse-inventory-options-strict-20261111-r2` passed the full strict gate on Paper 1.21.1-133:
430 registered probes, 411 passed, 19 expected rejections, and zero failed or
incomplete. It verifies Horse saddle/armor and Donkey chest true/false states
with Horse variant/stat controls. Report, redacted server log, client log
archive, and plugin hash are indexed in `release-evidence.json`.

Previous fixture `srv-paper-visibility-options-strict-20261111-r1` passed the full strict gate on Paper 1.21.1-133:
430 registered probes, 411 passed, 19 expected rejections, and zero failed or
incomplete. The `visibilityOptions` probe imports Mythic `VisibleByDefault`,
`Invisible`, and `AlwaysShowName` and reads back all configured values from a
production-spawned Zombie alongside inverse controls. Plugin SHA-256: `24bf2350158faee75b207eef24dae2595a3cf1a5d49d1a2d3364e46eeedec28e`. Report:
[paper-1.21.1-visibility-options-strict-20261111-r1.json](reports/paper-1.21.1-visibility-options-strict-20261111-r1.json), SHA-256: `7c561d6c17a0286665f25b44847f96d3c11c39a8ecf4f44c6bab8ea951aafbb3`. Compressed server log:
[paper-1.21.1-visibility-options-strict-20261111-r1.log.gz](reports/paper-1.21.1-visibility-options-strict-20261111-r1.log.gz), SHA-256: `0cf72104920d7f2bda11429dffd44e9d65b0bb20bd184ec6507a892ba73a56f6`. Client log archive:
[paper-1.21.1-visibility-options-strict-20261111-r1-client-logs.tar.gz](reports/paper-1.21.1-visibility-options-strict-20261111-r1-client-logs.tar.gz), SHA-256: `83ec7374154df5b654b8d2050cee28362f6ff088a240a50f7a88381eab95954b`. Archived logs redact web-token query values.

Previous fixture `srv-paper-armor-stand-options-strict-20261110-r2` passed the full strict gate on Paper 1.21.1-133:
430 registered probes, 411 passed, 19 expected rejections, and zero failed or
incomplete. The `armorStand` probe verifies all six imported Armor Stand flags
and all six equipment slots (`ItemBody`, `ItemFeet`, `ItemHand`, `ItemOffhand`,
`ItemHead`, `ItemLegs`), default controls, and wrong-entity/malformed-value
preservation through production spawning. Plugin SHA-256: `ff6f2348a447f8c59a014eb72a383da7c67f3d903c06374565111502f89c3e54`. Report:
[paper-1.21.1-armor-stand-options-strict-20261110-r2.json](reports/paper-1.21.1-armor-stand-options-strict-20261110-r2.json), SHA-256:
`8b28de85386dc89cbfcb059fa8e503b7f0591599942663f59e983d59e0597cc4`. Compressed server log:
[paper-1.21.1-armor-stand-options-strict-20261110-r2.log.gz](reports/paper-1.21.1-armor-stand-options-strict-20261110-r2.log.gz), SHA-256:
`06253c4cccfbd35c21d9df7a2063fe5d14d16c46ed3790b774bbd5f4e099ce8c`. Client log archive:
[paper-1.21.1-armor-stand-options-strict-20261110-r2-client-logs.tar.gz](reports/paper-1.21.1-armor-stand-options-strict-20261110-r2-client-logs.tar.gz), SHA-256:
`c6d6285990378124e5475f6241a1adcd795f6fca52efc74e91362f7cf75e2eea`. Archived logs redact web-token query values.

Previous fixture `srv-paper-armor-stand-options-strict-20261110-r1` passed the full strict gate on Paper 1.21.1-133:
430 registered probes, 411 passed, 19 expected rejections, and zero failed or
incomplete. The `armorStand` probe verifies all six imported Armor Stand flags,
body and hand equipment, default controls, and wrong-entity/malformed-value
preservation through production spawning. Plugin SHA-256: `17551be8a9046434ee5e9d1c0a850ada8a36af58c6c528ac5ba7261ebd6a700f`. Report:
[paper-1.21.1-armor-stand-options-strict-20261110-r1.json](reports/paper-1.21.1-armor-stand-options-strict-20261110-r1.json), SHA-256:
`690e45a7844c794197f76d7b56377335393a27a070acc7eb23c1b49357608e27`. Compressed server log:
[paper-1.21.1-armor-stand-options-strict-20261110-r1.log.gz](reports/paper-1.21.1-armor-stand-options-strict-20261110-r1.log.gz), SHA-256:
`5a335753b578d09355ccd1cdf6304786522f3b989b6e2dd51d96a71e33d385c2`. Client log archive:
[paper-1.21.1-armor-stand-options-strict-20261110-r1-client-logs.tar.gz](reports/paper-1.21.1-armor-stand-options-strict-20261110-r1-client-logs.tar.gz), SHA-256:
`6736e2a80ea58931a49c659a45266fdabad7351a6432646431aea5cb0ec0cc83`. Archived logs redact web-token query values.

Previous fixture `srv-paper-gravity-scope-strict-20261110-r1` passed the full
strict gate on Paper 1.21.1-133: 430 registered probes, 411 passed, 19 expected
rejections, and zero failed or incomplete. `noGravityImported` verifies both
general `NoGravity` states, Armor Stand-only `HasGravity` true/false readback,
wrong-entity handling, and malformed-value preservation. Undocumented
`Gravity` remains unsupported-preserved. `playerCreatedImported`
verifies true/false states on real Iron Golems spawned through production
configuration, with wrong-entity and malformed values preserved. Plugin
SHA-256: `e18a30a8c72b6e79e23bb7ebbf5c25c547d276091222d60e420ecbe3b1a37c80`.
Full catalog report:
[paper-1.21.1-gravity-scope-strict-20261110-r1.json](reports/paper-1.21.1-gravity-scope-strict-20261110-r1.json), SHA-256:
`cfe39a77a327a0d33b8712328a66729dd94cffcbc908b32fadc317643146beff`.
Compressed server log:
[paper-1.21.1-gravity-scope-strict-20261110-r1.log.gz](reports/paper-1.21.1-gravity-scope-strict-20261110-r1.log.gz), SHA-256:
`f18ffc2066f7a55f0fada3a17a431531ccf947768d32273f30bda3f34e4afd62`. Client
log archive:
[paper-1.21.1-gravity-scope-strict-20261110-r1-client-logs.tar.gz](reports/paper-1.21.1-gravity-scope-strict-20261110-r1-client-logs.tar.gz), SHA-256:
`d2bb78f4922aab53dc77e9b87ac9cc0cace8821ebdb1f7b5e07179b142c9c96f`.
Archived logs redact web-token query values.

The earlier `srv-paper-gravity-aliases-strict-20261110-r1` report remains
archived as superseded; it treated undocumented `Gravity` as supported and
applied `HasGravity` beyond Armor Stands.

Previous fixture `srv-paper-no-gravity-strict-20261110-r2` also passed the
430-probe strict gate. Its report and hashes remain indexed in
`release-evidence.json`.

Previous fixture `srv-paper-default-mob-options-strict-20261110-r1` passed the
full strict gate on Paper 1.21.1-133: 428 registered probes, 409 passed, 19
expected rejections, and zero failed or incomplete. `importedMobDefaults`
verifies the three documented global defaults, explicit precedence, and
unsupported-key preservation through production mob spawning.
`tameableOptionsImported` confirms that unsupported global `Tameable` does not
override the built-in false policy. Plugin SHA-256:
`118f05ec5b5179e394f21dfc9d9c26df80cdae92fa6747b42e176332f024befa`. Full
catalog report:
[paper-1.21.1-default-mob-options-strict-20261110-r1.json](reports/paper-1.21.1-default-mob-options-strict-20261110-r1.json), SHA-256:
`1cc0ac63d3865f982ab1a9ab4f518bf66562cd81ab000f805813bd009fcae7c7`.
Compressed server log SHA-256:
`f0c79ee1fe5e9d614b890476fcb566dda1875f82b41bb66ee4425fd84383c6a5`.
Client log archive SHA-256:
`b39566c19812f32a3c226a848e9490a61588357fa07b15fede7c9d81527daeed`.
Archived logs redact web-token values.

Previous fixture `srv-paper-interaction-options-strict-20261110-r2` passed
the full strict gate on Paper 1.21.1-133: 426 probes, 407 passed, 19 expected
rejections, zero failed or incomplete. Its `interactionOptionsImported` probe
verifies configured dimensions and responsiveness, width fallback, Mythic
defaults, and wrong-type/invalid-value preservation. Plugin SHA-256:
`bfd459f340efed76aaf15f4ba60ccb0d80fb59391aa2453fe46791ff4cdb6fcd`. Report
and log hashes remain indexed in `release-evidence.json`.

Previous fixture `srv-paper-boat-options-strict-20261110-r1` passed 425
registered probes (406 passed, 19 expected rejections) and verified imported
Boat and ChestBoat variants with wrong-type, invalid, and conflict controls.

Focused exploratory fixture `paper-1.21.4-item-group-cooldown-exploratory-20261110`
ran plugin `helsteraMobs` on Paper 1.21.4-232 with a real Mineflayer 1.21.4
player. The production skill condition/action factories set and read the named
cooldown and then cleared it (`set-and-read=true clear=true result=true`). This
checks the Paper 1.21.4+ API path only; it is not a strict release fixture and
does not change the Paper 1.21.1 release baseline. Plugin SHA-256:
`eb335851a7d87f2af00f53740a8c4df13edaf41754410b066b5285cd2a3ec5b3`. Report:
[paper-1.21.4-item-group-cooldown-exploratory-20261110.json](reports/paper-1.21.4-item-group-cooldown-exploratory-20261110.json).
Report SHA-256: `9a55551b868786687c64fbf27e91e78bcfc3a029d28669c55112846eb21482e8`.
Sanitized focused log:
[paper-1.21.4-item-group-cooldown-exploratory-20261110.txt](reports/paper-1.21.4-item-group-cooldown-exploratory-20261110.txt).
Log SHA-256: `19e575d9adbbe883c2ee1058246256aa4523433f6cb130e429fa77ff81e1c145`.

Focused exploratory fallback fixture `paper-1.21.1-item-group-cooldown-legacy-api-exploratory-20261110`
ran the same plugin on Paper 1.21.1-133. After a real player joined, the
condition failed closed, the set action was skipped, and the server logged the
unsupported API warning; the plugin remained enabled. Mineflayer reported a
packet `PartialReadError` after spawn, so this record relies on the server-side
join and self-test logs. It does not change the strict release catalog. Report:
[paper-1.21.1-item-group-cooldown-legacy-api-exploratory-20261110.json](reports/paper-1.21.1-item-group-cooldown-legacy-api-exploratory-20261110.json).
Report SHA-256: `57d3e686d7ea76f4b200f6bcc748ed0e0678f9aa93407cb626bdc9d40380b331`.
Sanitized focused log:
[paper-1.21.1-item-group-cooldown-legacy-api-exploratory-20261110.txt](reports/paper-1.21.1-item-group-cooldown-legacy-api-exploratory-20261110.txt).
Log SHA-256: `acb173de2a0bb1cfdd22a39507d3fe24ee7ca5e9b3495155e0801ea61b9ec178`.

Previous fixture `srv-paper-target-fluids-import-strict-20261110-r2` passed the full strict
gate on Paper 1.21.1-133: 423 registered probes, 404 passed, 19 expected
rejections, and zero failed or incomplete. Its `targetFluidConditions` probe
verifies the imported `?targetInWater`/`?targetInLava` aliases against live
target fluids, malformed-argument preservation, and restoration of the touched
blocks. Plugin SHA-256:
`eb536ff2ed308e44893e0ecd2d13e730f39f8952aafc2b969b327add939ad0ea`. Full
catalog report:
[paper-1.21.1-target-fluids-import-strict-20261110-r2.json](reports/paper-1.21.1-target-fluids-import-strict-20261110-r2.json), SHA-256:
`1acc1bc8f127373babf65e8de4e5f1462caa94a81bd3d6ccbddad2979eb3ed1c`.
Compressed server log SHA-256:
`1a17ee57f7e748b53ac97cb989e8852672e805d4e27cde6672c1d332ce23edc5`.
Client log archive SHA-256:
`5af51dcd4c2ec750acf2cb964d2d2313fa8e0049e4a7df186ce9202fa22e192c`.
Archived logs replace web-token query values with `[REDACTED]`.

Previous fixture `srv-paper-invincible-strict-20261110-r3` passed the full strict
gate on Paper 1.21.1-133: 423 registered probes, 404 passed, 19 expected
rejections, and zero failed or incomplete. Its imported `invulnerableCondition`
and `targetInvulnerable` probes verify `IsInvulnerable`/`IsInvincible` aliases,
positive and negative states, target evaluation, invalid-input preservation,
and restoration. Plugin SHA-256:
`0ae19ad0e0da1637f5b466a7df8eb132f62da7ad627e7b98088fe631c344c254`. Full
catalog report:
[paper-1.21.1-invulnerable-condition-strict-20261110-r3.json](reports/paper-1.21.1-invulnerable-condition-strict-20261110-r3.json), SHA-256:
`36c6c1ba33c4cb32a572a08950b115f237fd8aeebb435d1f345275637511fe91`.
Compressed server log SHA-256:
`24ff14d79ab2d12124255eec3f6bd48bda1cae23354da82acd619d892e1ae6d9`.
Client log archive SHA-256:
`543fa484f9c9a82ed2926bf15cf950c4e9f36a69bfb5af1d24f0a024048e34df`.
Archived logs replace web-token query values with `[REDACTED]`.

Previous same-condition fixture `srv-paper-invincible-strict-20261110-r1`
passed the same full 423-probe catalog. Its archived report, redacted server
log, client log archive, and plugin hash remain in the release manifest.

Previous fixture `srv-paper-grounded-strict-20261110-r3` passed the full strict
gate on Paper 1.21.1-133: 423 registered probes, 404 passed, 19 expected
rejections, and zero failed or incomplete. Its imported `groundedCondition`
probe verifies both `onGround` and `grounded` aliases, positive grounded and
negative airborne states, invalid-input preservation, and restoration. Plugin
SHA-256:
`fa622b01450cb109dbc29edae6a30e0a5f6f06519a2fd4305860b5d0869b54ee`. Full
catalog report:
[paper-1.21.1-grounded-condition-strict-20261110-r3.json](reports/paper-1.21.1-grounded-condition-strict-20261110-r3.json), SHA-256:
`084df9b5e22e339aa3aea70109d8a0ae78b7018acdf59e623b0a2a41df5c7807`.
Compressed server log SHA-256:
`aef0a37c0eb04a295ce45cf7afad2bd0458721d8334244117c4fd3f51b6f7946`.
Client log archive SHA-256:
`c28e37035b017b80b95eca43a6d659871245dfee6f9362a79a050bbdd89d35b9`.
Archived logs replace web-token query values with `[REDACTED]`.

Previous strict fixture `srv-paper-worldguard-strict-20261110-r2` passed the
full strict gate on Paper 1.21.1-133: 422 registered probes, 403 passed, 19
expected rejections, and zero failed or incomplete. Plugin SHA-256:
`48d797baf23689b41367f3efdc30a105418f8d578177cebc2d8b21277e9e9974`. Report:
[paper-1.21.1-worldguard-strict-20261110-r2.json](reports/paper-1.21.1-worldguard-strict-20261110-r2.json), SHA-256:
`840f9dca50dad598ab079fc99961466f68c3d3acb5a17b3a91f0d016259b0b2b`.
Compressed server log SHA-256:
`02d11d43a04b45c22a8b03ddb669765e92fc79964e174a30c6d331a53180db0d`.
Client log archive SHA-256:
`40b3b5409d356d1b58b3a2a7da9f7a1e77b484fb1552f8d51bc006b478efc5cb`.

Focused installed-WorldGuard fixture `paper-1.21.1-worldguard-region-parity-20261110-r1`
passes 13 checks on Paper 1.21.1-133 with WorldGuard 7.0.12 and WorldEdit
7.3.8. It verifies provider discovery, active region-query capability, simple,
union, and intersection queries, missing-region handling, and imported
`Region`/`NotInRegion` inside/outside behavior. Report:
[worldguard-region-paper-1.21.1-20261110-r1.json](reports/worldguard-region-paper-1.21.1-20261110-r1.json), SHA-256:
`e9d5e8f043307eb13303179ea83ea6c4cff9bbfd7969d5d35356d52004e85a09`.
Compressed server log SHA-256:
`ecba4083af2aee50ca4b252bd1ad91fcc42647d5f7081d0f24936129a153d840`.

Previous strict fixture `srv-paper-skilltree-atomic-strict-20261110-r1` passed
the full strict gate: 422 registered probes, 403 passed, 19 expected
rejections, and zero failed or incomplete. Its API module passes all 97 tests,
including a failed-serialization check that preserves the prior snapshot and
removes the temporary file. A clean focused two-boot fixture
`srv-paper-skilltree-atomic-restart-20261110-r2` verifies two same-ID mobs
restore independent UUID branch state and that death removes only the victim
snapshot. Focused evidence:
[report](reports/paper-1.21.1-skilltree-atomic-restart-20261110-r2-evidence.json)
(SHA-256 `ee6d3e2204ac5bc42ae87da954a73577c363541957555a0d63e3fd284af79945`),
[redacted boot logs](reports/paper-1.21.1-skilltree-atomic-restart-20261110-r2-logs.tar.gz)
(SHA-256 `52dfeb3890b214a9749f2af8995e4ac27412068a8fe6a0000372f9b078b859ee`).
Full-catalog report:
[paper-1.21.1-skilltree-atomic-snapshot-strict-20261110-r1.json](reports/paper-1.21.1-skilltree-atomic-snapshot-strict-20261110-r1.json), SHA-256:
`a55999e80d9ce52c33d80087890f2d02e69f524f3bff4a29e7793caddfbf1e30`.
Compressed server log SHA-256:
`4f51b57ce1f686e3c171e1ea98e3d8728c34c85af9193d49ea733af739b34028`.
Client log archive SHA-256:
`455b078ee0a04e2802db91207d98649e6b6cdc26cc3fb1daa6c74caa68fcbb20`.
Archived logs redact web-token query values. Complete Mythic SkillTree
semantics remain open.

Previous strict fixture `srv-paper-on-timer-import-strict-20261110-r1`
verified imported 2- and 5-tick Timer branches aligned to the Paper global
clock, independent dispatch, source context, and invalid zero-interval
preservation in the full 422-probe catalog. The official [onTimer trigger
reference](https://wiki.mythiccraft.io/mythicmobs/Skills/Triggers/onTimer)
specifies global-clock scheduling and no `@trigger`, and lists MythicCrucible
as its implementation; this evidence covers Helstera's imported mob-trigger
support, not the complete Mythic Trigger catalog.

Latest fixture `srv-paper-mythic-native-stats-release-strict-20261110-r1`
passed the full strict gate on Paper 1.21.1-133: 421 registered probes, 402
passed, 19 expected rejections, and zero failed or incomplete. Its imported
Mythic Stats probe reads `ATTACK_DAMAGE=12.0`, `MOVEMENT_SPEED=0.4`,
`ARMOR=8.0`, `ARMOR_TOUGHNESS=4.0`, `KNOCKBACK_RESISTANCE=0.25`, and
`FOLLOW_RANGE=36.0` from a production-spawned Zombie and confirms
Stats-over-Options precedence, preservation, and default-valued controls.
Report:
[paper-1.21.1-mythic-native-stats-imported-strict-20261110-r1.json](reports/paper-1.21.1-mythic-native-stats-imported-strict-20261110-r1.json), SHA-256:
`adb22fdbb2d5a76e2459da0a2b3e2b6a2519b70900b97246b4c583376845b426`.
Compressed server log SHA-256:
`21d854a7b4962ffe31b1b97cf0fbf3748ba4cc30791f47c27d4fc8423a140238`.
Client log archive SHA-256:
`95c1ce7cbcd5d9939e50db95b04ef28b0d5f6b75bf6d1068d9ad5fe357af5747`.
Plugin SHA-256:
`31b9f1a360e8d31b0ed2b0ee74fb400085bf4e470c1bbce79bd93ead1b800905`.
Other Stats and modifier/duplicate/invalid entries remain preserved; the full
Stats catalog remains open. Archived logs redact web-token query values.

Previous fixture `srv-paper-attack-cooldown-placeholder-strict-20261110-r1`
passed the full strict gate on Paper 1.21.1-133: 421 registered probes, 402
passed, 19 expected rejections, and zero failed or incomplete. Its
`gameplayPlaceholders` observation reads `0.1` from live Player caster/target
scopes after an attack, verifies dash and underscore spellings, checks the
`0..1` range, and preserves the placeholder for a non-player target. Report:
[paper-1.21.1-attack-cooldown-placeholder-strict-20261110-r1.json](reports/paper-1.21.1-attack-cooldown-placeholder-strict-20261110-r1.json), SHA-256:
`2c8fe8fdd97659e81969db61a516347e04adf1e9b9ee2a9396f328f2924dd45d`.
Compressed server log SHA-256:
`94412a8bf3228050969b0c310d2e264839528fd5e9d9871c817878fba06defb5`.
Client log archive SHA-256:
`aa964b4a5fd07abb9d845179d277deca380a8d92b1af41c0f7440ba9bbcdd858`.
Plugin SHA-256:
`874a7cc9a977916b0ddd4c2cd212a0b1073cac919ab8913722acfcb5f92d4702`.
This verifies the listed placeholder behavior; the complete Mythic placeholder
catalog remains `mapped-unverified`. Archived logs redact web-token query
values.

Previous fixture `srv-paper-health-boundaries-strict-20261110-r2` passed the
full strict gate on Paper 1.21.1-133 with 421 probes (402 passed, 19 expected
rejections, and zero failed or incomplete). Its `healthImportedCondition`
probe verifies absolute and percentage comparisons, positional arguments with
absorption, duplicate-argument rejection, and preservation of overflowing or
invalid inputs. Its report and hashes remain in `release-evidence.json`.

Previous fixture `srv-paper-armor-toughness-stats-strict-20261110-r1` passed the
full strict gate on Paper 1.21.1-133: 421 registered probes, 402 passed, 19
expected rejections, and zero failed or incomplete. Its
`armorToughnessStatsImported` probe runs the documented Mythic
`Stats: - ARMOR_TOUGHNESS 4` through the production importer and generated mob
configuration, then reads `4.0` from a real Zombie's Bukkit
`ARMOR_TOUGHNESS` attribute. Modifier syntax and the undocumented root field
are preserved; both controls read `0.0`. Report:
[paper-1.21.1-armor-toughness-stats-imported-strict-20261110-r1.json](reports/paper-1.21.1-armor-toughness-stats-imported-strict-20261110-r1.json), SHA-256:
`c5b90d9f0d0bfb9296ecad2c5ee21753c07fb052e46ebe6709dff4d865f2c3e1`.
Compressed server log SHA-256:
`a516596c72afb53659bd3454fbe78e9f277d86e3926bd2570c6edab68bf88a0b`.
Client log archive SHA-256:
`e8064ae881928a126f030c0f270fd0c4c046388497a2376dea9b08a7b8a60931`.
Plugin SHA-256:
`571e41dadf77c0105a2b12d616774ab21ea96b8533bd3d87d3dc4a9461e70d24`.
The earlier `srv-paper-armor-toughness-strict-20261110-r1` fixture tested
only an already-converted Helstera mob configuration, so it is historical
runtime evidence rather than Mythic import evidence. Archived logs redact web
token query values; the source fixture retains the original run logs.

Previous strict fixture `srv-paper-target-of-target-strict-20261110-r1` passes all
420 probes (401 passed, 19 expected rejections, zero failed or incomplete) on
Paper 1.21.1-133. Its `targetOfTargetTargeter` probe verifies the active Mob
target's living target, separator aliases, non-Mob rejection, missing-target
rejection, and removed-target rejection. Report:
`reports/paper-1.21.1-target-of-target-strict-20261110-r1.json`;
server log:
`reports/paper-1.21.1-target-of-target-strict-20261110-r1.log.gz`;
client logs:
`reports/paper-1.21.1-target-of-target-strict-20261110-r1-client-logs.tar.gz`.
Report SHA-256: `5c1f7a26e05ae6f7916edbc32dbec3578fe8421151ec4ed0ce1a2b127de08931`;
server log SHA-256: `c46f1ea02166a76bcad00e24c0e6938a115fe02da57611b4c5f913aded03e272`;
client log SHA-256: `d21860769abb9c604830033440f597eb86f59850d6177791eb075e4f65538799`;
plugin SHA-256: `bfd1679b795176965f0d44b8fe9e1a9c38e3a561c042bee4c03817aba0e2641d`.

Previous fixture `srv-paper-last-damager-strict-20261110-r1` passed the full
strict gate with 419 registered probes and remains archived below and in
`release-evidence.json`.
Previous fixture `srv-paper-moving-condition-strict-20261110-r1` passed the full
strict gate on Paper 1.21.1-133: 418 registered probes, 399 passed, 19
expected rejections, and zero failed or incomplete. Its `conditionMobility`
probe verifies `moving`/`isMoving`, exact and approximate coordinate semantics,
fractional movement, `exact`/`e` argument aliases, malformed-input rejection,
imported mapping, and entity-snapshot restoration. Report:
[paper-1.21.1-moving-condition-strict-20261110-r1.json](reports/paper-1.21.1-moving-condition-strict-20261110-r1.json), SHA-256:
`db1e07b1e29aea7a2d19d12f7717a4c29af85d60a24a1dfb8251f090bd41ec33`.
Compressed server log SHA-256:
`660693f4c3eba9473f63d262eb26e00a3182e9c8ad2b43e97b5184ff8d03a759`.
Client log archive SHA-256:
`114a55e80e5c6932c4697115557ded8ee31f8e52b4de9f4f643108fd0ca526fa`.
Plugin SHA-256:
`236f4647ad243eb96721814b92ebb24772b463fc6090fd6282db792f9a3c7a02`.
Archived logs redact web token query values; the source fixture retains the
original run logs.

Previous fixture `srv-paper-spawn-reason-strict-20261110-r1` passed the full
strict gate on Paper 1.21.1-133: 418 registered probes, 399 passed, 19
expected rejections, and zero failed or incomplete. Its imported
`SpawnReason`/`spawnreason` aliases, `reason`/`r` arguments, live `CUSTOM`
evaluation, and malformed-input preservation remain archived below and in
`release-evidence.json`.

Previous fixture `srv-paper-local-difficulty-strict-20261110-r1` passed the full
strict gate on Paper 1.21.1-133: 416 registered probes, 397 passed, 19
expected rejections, and zero failed or incomplete. Its `localDifficultyCondition`
probe verifies imported `LocalDifficulty` aliases (`difficulty`, `diff`, and
`d`), comparator and range evaluation, target-to-caster location fallback,
missing-context fail-closed behavior, and malformed-input preservation. Report:
[paper-1.21.1-local-difficulty-strict-20261110-r1.json](reports/paper-1.21.1-local-difficulty-strict-20261110-r1.json), SHA-256:
`b9f67d908b00da2c3bb9528d43a62001fe17223b35c317d531f108af7cbc4ccd`.
Compressed server log SHA-256:
`62cb3f5ed6f05bd847960d07c55bb7f7de39c86a7e6017b33101482ee3dad219`.
Client log archive SHA-256:
`86d9d29ccb72f3c2ed9b44affc34588003120b86b5810fb3206926bc7849a051`.
Plugin SHA-256:
`122fcee8f9eb9584d2ed0a7814d6122453b84fba050e52d006c389f4917b3473`.
Archived logs redact web token query values; the source fixture retains the
original run logs.

Previous fixture `srv-paper-imported-signal-strict-20261109-r3` passed the full strict gate on Paper 1.21.1-133:
415 registered probes, 396 passed, 19 expected rejections, and zero failed or
incomplete. Its imported `~onSignal` probe records wildcard and named selector
routing, payload propagation, sender and `@trigger` context, payload
placeholders, `last-signal`, and invalid-selector preservation. Report:
[paper-1.21.1-imported-signal-strict-20261109-r3.json](reports/paper-1.21.1-imported-signal-strict-20261109-r3.json), SHA-256:
`3345c7db2a19eef0c3248978dda4b6788740f07f616e3dea9f1073d38a7b260b`.
Compressed server log SHA-256:
`522013481ff241b2778b45956ef018c0d5f64daa69b55c41ba1face873957aff`.
Client log archive SHA-256:
`0d931326df2927909fee1a53bf43b2d758262c70708543e763f3e2fde612edc6`.
Plugin SHA-256:
`0ec52dbcd0622ac7f6460b3d70c66e6c83fd22edc3a8f545a5de3b34b94b30cb`.
Archived logs redact web token query values; the source fixture retains the
original run logs.

Previous fixture `srv-paper-on-kill-import-strict-20261109-r4` passed the full
strict gate on Paper 1.21.1-133: 414 registered probes, 395 passed, 19
expected rejections, and zero failed or incomplete. Its imported `~onKill` and
`~onKillPlayer` probes record `imported=true`, `mapped=true`, real killer-side
dispatch, player-victim filtering, and `PlayerKills` positive/negative checks.
Report:
[paper-1.21.1-on-kill-import-strict-20261109-r4.json](reports/paper-1.21.1-on-kill-import-strict-20261109-r4.json), SHA-256:
`c1062a32c3c1c8516e126318c0e9b4b27dda5726b2444a69e95ce6f8195edd98`.
Compressed server log SHA-256:
`d6304548db012578e683271af0fbbbb47eca2cbac73e3d91b589899133301c95`.
Client log archive SHA-256:
`61ab0439730df8d99b7950b06de14e5c84c4ae4feba765d57debbea8e44b7c1b`.
Plugin SHA-256:
`c005602083b0cbe6db4bf513893995e8eb954adf81ffbe3204ffa80f1d42362e`.
Archived logs redact web token query values; the source fixture retains the
original run logs.

Previous fixture `srv-paper-conversion-boundaries-strict-20261109-r2` passed the
same full strict catalog and remains archived with native Zombie `DROWNED`,
Zombie Villager `CURED`, Villager `INFECTION`, Skeleton `FROZEN`, and Slime
split evidence. Its report is
[paper-1.21.1-conversion-boundaries-strict-20261109-r2.json](reports/paper-1.21.1-conversion-boundaries-strict-20261109-r2.json).

Previous fixture `srv-paper-skeleton-conversion-strict-20261109-r3` passed the full
strict gate on Paper 1.21.1-133: 411 registered probes, 392 passed, 19
expected rejections, and zero failed or incomplete. The
`mobOptionsSkeletonConversion` observation proves an imported Skeleton with
`PreventConversion: true` cancels native `FROZEN` conversion while an inverse
control becomes a Stray. Report:
[paper-1.21.1-skeleton-conversion-strict-20261109-r3.json](reports/paper-1.21.1-skeleton-conversion-strict-20261109-r3.json), SHA-256:
`e05d3572b7e677920c6a96ae2205980110966f3135e0f887e11a4bc965202481`.
Compressed server log SHA-256:
`99268a4cefaf8ce85d657978d39a8aa7f6e9436c70dd6f006f254c1fa49b8aa9`.
Client log archive SHA-256:
`d776bea38518b3b622511dbcbdae0ef2bd59d6f354f582c55d1b11aa44d7aac5`.
Plugin SHA-256:
`ed3b871cad80efe4d6ff75747d11f4672f4d05cc4bdbda6abca2570a49b31737`.
Archived logs redact web token query values; the source fixture retains the
original run logs.

Previous fixture `srv-paper-heal-on-reload-strict-20261109-r1` passed the full
strict gate with 410 registered probes; its report and hashes remain archived
below.

Previous fixture `srv-paper-route-patrol-strict-20261109-r2` passed the full
strict gate on Paper 1.21.1-133: 409 registered probes, 390 passed, 19 expected
rejections, and zero failed or incomplete. The `aiRoute` observation proves
followpath movement, dynamic patrol mutation, configured speed and tolerance,
endpoint reversal, and `finished=false` after the patrol loop. Report:
[paper-1.21.1-route-patrol-strict-20261109-r2.json](reports/paper-1.21.1-route-patrol-strict-20261109-r2.json), SHA-256:
`f90115c90dad9d8883a2b6a592008488c9a2c27693f09ec3adbd04da3d8c7f5a`.
Compressed server log SHA-256:
`0915ca648b08a2ff918c7eda2b419b8c417c0a7ee3398d1cc3faee29e683cf18`.
Client log archive SHA-256:
`f5b1fb7b78b13ec58b4bc4a83f509c64943cfe5bbceedba6915727dcdb56f3c1`.
Plugin SHA-256:
`7c999206379c7363639e92a8031d057cabd762bcc698b2df224dc26fd36e983e`.
Native Pathfinder goal equivalence remains an explicit open boundary. Archived
logs redact web token query values; the source fixture retains the original run
logs.

Previous fixture `srv-paper-jockey-import-strict-20261109-r1` passed the
full strict gate on Paper 1.21.1-133: 409 registered probes, 390 passed, 19
expected rejections, and zero failed or incomplete. It verifies imported
Zombie `Options.PreventJockeyMounts: true` cancels a Chicken
`EntityMountEvent`, while the global-default Zombie allows it through
production mob configuration. The earlier native Zombie `NATURAL` spawn
fixture separately verifies actual protected/control jockey mount outcomes.
Report:
[paper-1.21.1-jockey-import-strict-20261109-r1.json](reports/paper-1.21.1-jockey-import-strict-20261109-r1.json), SHA-256:
`7c7a72ed0ff4388cc3b7bcd72e3f400d99d2daa8f1861c9d8c2f68edb57f5686`.
Compressed server log SHA-256:
`07602eedd66936e65ccde4d4350dec1b0e28f0983b647da13f825bd3b58b0a36`.
Client log archive SHA-256:
`1149e4c748f8b4cbe43b2866d8c6de1fc41000f9970b8b778ce2dd4350375f48`.
Plugin SHA-256:
`e848974e273020001199a559e675dcb54a631a4ca909841738676351521e06af`.
Archived logs redact web token query values; the source fixture retains the
original run logs.

Previous fixture `srv-paper-can-pickup-import-strict-20261109-r2` passed the
full strict gate on Paper 1.21.1-133: 409 registered probes, 390 passed, 19
expected rejections, and zero failed or incomplete. It verifies imported
`CanPickupItems: true` and `false` override the global fallback, while
wrong-entity and malformed values remain preserved through production mob
configuration. Report:
[paper-1.21.1-can-pickup-import-strict-20261109-r2.json](reports/paper-1.21.1-can-pickup-import-strict-20261109-r2.json), SHA-256:
`2a46d23de86aeed5ba958bbdf846bc0933d6f1e3a294e60c364d2e300552ece6`.
Compressed server log SHA-256:
`b85e3cea086191813496ec18623240b9855dff3144a28c3b7893f79f3b5b1753`.
Client log archive SHA-256:
`05495f7b0553102158c18f188024888ce3025fe15cc350516f83dda929258dad`.
Plugin SHA-256:
`7bede98dce74336f9b5a9df6707273a7793180a3558ffa5fbdd086cab3cd41ee`.
Archived logs redact web token query values; the source fixture retains the
original run logs.

Previous strict fixture `srv-paper-rabbit-killer-bunny-strict-20261109-r1`
passed the full strict gate on Paper 1.21.1-133: 409 registered probes, 390
passed, 19 expected rejections, and zero failed or incomplete. It verifies
imported Rabbit `IsKillerBunny`/`Angry` aliases, false and RabbitType controls,
and preservation of invalid, wrong-entity, and conflicting alias inputs through
production spawning. Report:
[paper-1.21.1-rabbit-killer-bunny-strict-20261109-r1.json](reports/paper-1.21.1-rabbit-killer-bunny-strict-20261109-r1.json), SHA-256:
`346f7d364eb108394e69f195a47f0b801895b6699c126de7409d2572a8c7ebec`.
Compressed server log SHA-256:
`6d2aeb1ae10c4cf3231e5a4d9983e84368274968f3af0f0a145a71a37b0c87b9`.
Client log archive SHA-256:
`5281e2ed2243c2b5bcff39c5126ec49485052b3eabac6d42e5e83d4df6a1aa24`.
Plugin SHA-256:
`99262abb3521e19b57b160e10715641e3ca43c84c6e6d9ee9ca2ca5f8c33f008`.
Archived logs redact web token query values; the source fixture retains the
original run logs.

Previous strict fixture `srv-paper-dropitem-owner-strict-20261109-r2` verified
fixed and `<target.uuid>` imported owners on real dropped items and remains
archived.

Previous strict fixture `srv-paper-dropitem-owner-strict-20261109-r1` verified
the fixed imported owner UUID and invalid-input preservation in the same 409
probe catalog. Its report and logs remain archived.

Previous strict fixture `srv-paper-mythic-stats-strict-20261109-r2` passed the
full strict gate on Paper 1.21.1-133: 409 registered probes, 390 passed, 19
expected rejections, and zero failed or incomplete. It verifies the native
`stats.yml` provider and weighted `BiasStat` loot path, including default and
configured base values, killer priority, dropper fallback, entity overrides,
unknown/disabled stat rejection, positive and negative weighting,
fail-closed fallback, reload, and state restoration. Report:
[paper-1.21.1-mythic-stats-strict-20261109-r2.json](reports/paper-1.21.1-mythic-stats-strict-20261109-r2.json), SHA-256:
`fdf9057a4b8740e89791fc182b4c0c00b75833c9dbbed90de02e01c7f53f45ab`.
Compressed server log SHA-256:
`6f0b3e142bd955b0999b0cd0bead600a983f3f91abcd3aaf7119e287dec511b5`.
Client log archive SHA-256:
`3fa1cd8199cbb00946a2c5e5fd830734c129300d597053686cb05630fd65516a`.
Plugin SHA-256:
`2c7a6c37935cf048b462ea2acad5cdc27383716f5900049f5589493444fe0d19`.
This is the native Helstera stats provider; MythicMobs itself was not installed
for this fixture.

Previous strict fixture `srv-paper-attributes-composite-strict-20261109-r1`
passed the full strict gate with 408 registered probes, 389 passed, 19
expected rejections, and zero failed or incomplete. It verifies imported
entity/item attribute combinations, aliases, percentage amounts,
`ADD_NUMBER`/`ADD_SCALAR`/`MULTIPLY_SCALAR_1`, slot groups, modifier
deduplication, and invalid-input rejection.
Report:
[paper-1.21.1-attributes-composite-strict-20261109-r1.json](reports/paper-1.21.1-attributes-composite-strict-20261109-r1.json), SHA-256:
`0067e5b18a36b60805879a8146b1d61f12965263d9e223e24b566df1c280d16e`.
Compressed server log SHA-256:
`a6b7696df5c650452a4a5a9691bed3797f5a3f4b22385f3b22ccd71aa4799c25`.
Client log archive SHA-256:
`adf9d205b30aaeadedbbfd85c71219d3fbbfbddcf98079684712652ca1458927`.
Plugin SHA-256:
`a7dd486ce46dfcc442cf33e3645a3f5812a7dda0d3ad1b7326532e7e89b16ef0`.

Previous strict fixture `srv-paper-item-similarity-import-strict-20261109-r2`
verifies imported `EntityItemIsSimilar` and `ItemIsSimilar` aliases,
full-inventory rejection, and invalid-argument preservation; its archived
report remains historical evidence.

Previous strict fixture `srv-paper-item-metadata-strict-20261108-r1` verifies
imported ItemStack metadata and legacy numeric item data and remains archived as
historical evidence.

Previous strict fixture `srv-paper-legacy-numeric-items-strict-20261108-r1`
isolates the legacy numeric Item and Equipment paths and remains archived as
historical evidence.

Previous fixture `srv-paper-population-conditions-strict-20261108-r2` passed
the full strict gate on Paper 1.21.1-133: 403 registered probes, 384 passed,
19 expected rejections, and zero failed or incomplete. It verifies imported
Mythic `PlayersOnline`, `onlinePlayerCount`, `PlayersInWorld`, and
`MobsInWorld` through the production importer and condition runtime, including
positive live counts, large-threshold negative controls, and malformed/unknown
argument preservation. The aggregate `conditionUtilityAliases` probe also
passes. Report:
[paper-1.21.1-population-conditions-strict-20261108-r2.json](reports/paper-1.21.1-population-conditions-strict-20261108-r2.json), SHA-256:
`67d7b23a3e96802fcf990e3a5e5c8315ef62429ea78d5cb4fffb20339f93b976`.
Compressed server log SHA-256:
`dc10faaba09fefe535083a34db7b189e77e0a28670a75005ddd7d3e763f62cf1`.
Client log archive SHA-256:
`bb3fcd1c276b4c4bb8b956baf2d47dd1f7dbdbd8069048c5756d879b1a6bf86e`.
Plugin SHA-256:
`9accae95e38c76fcf0a80af20ec1ad074c12a7ab33ead134d7c1685f405f7826`.
The importer rejects non-finite numeric values and unknown population
arguments instead of translating them into a misleading runtime condition.

Previous fixture `srv-paper-coordinate-difference-strict-20261108-r1` passed
the full strict gate on Paper 1.21.1-133: 402 registered probes, 383 passed,
19 expected rejections, and zero failed or incomplete. It verifies imported
Mythic `XDiff`, `YDiff`, and `ZDiff` aliases through the production importer
and condition runtime, including positive axis ranges, out-of-range rejection,
and malformed-input preservation. The same catalog verifies imported owner,
online-owner, leash, cleanup rejection, and missing-target fail-closed target
relationship paths. Report:
[paper-1.21.1-coordinate-difference-strict-20261108-r1.json](reports/paper-1.21.1-coordinate-difference-strict-20261108-r1.json), SHA-256:
`feb7799cbbcb65ef558060b07e677545aae4e7e7c64c1be54a95604146c85996`.
Compressed server log SHA-256:
`affaeaf4b94b359a05e6f49aac3814eb62f02d4480f82e1f36e148eae2663326`.
Client log archive SHA-256:
`5a9b63011d17e95f4ced0b8f7907cb1103f2856d33553202d4e53829afdc3aa3`.
Plugin SHA-256:
`d824761cdb575b50c5061706c7fde362b80f521aa9d4c46d68c296b7c64c343f`.
That strict report has 409 probes, zero incomplete. Archived logs redact web
token query values; the source fixture retains the original run logs.

Previous fixture `srv-paper-context-relations-strict-20261108-r2` passed
the full strict gate on Paper 1.21.1-133: 401 registered probes, 382 passed,
19 expected rejections, and zero failed or incomplete. It verifies context-bound
`@Passenger`, `@Passengers`, `@Mount`, `@Vehicle`, `@OwnerLocation`, and
`@ParentLocation` resolution, active-entity filtering, and malformed-argument
rejection through the production targeter path. Report:
[paper-1.21.1-context-targeter-relations-strict-20261108-r2.json](reports/paper-1.21.1-context-targeter-relations-strict-20261108-r2.json), SHA-256:
`d72a3fe89dbb17623219ae53b36fb07acde0a5a9bc257fe3ac06b598aca35800`.
Compressed server log SHA-256:
`13c5a6a019385370375a516dd5123655bc56dd3b7eea1f66a9cd6d1d80cb8940`.
Client log archive SHA-256:
`9a3f6dd620f6f671c1030e91a55df989b1df576117fc1991d51eb493f7c4624b`.
Plugin SHA-256:
`043139059115bd84f7e543d76c71486f06303163dc13b09d8aa186981609b7b6`.
That strict report has 401 probes, zero incomplete. Archived logs redact web
token query values; the source fixture retains the original run logs.

Previous fixture `srv-paper-player-kills-import-strict-20261108-r1` passed
the full strict gate on Paper 1.21.1-133: 400 registered probes, 381 passed,
19 expected rejections, and zero failed or incomplete. It verifies imported
`PlayerKills` primary, range, negative, and malformed-input preservation paths
through a real Player death workflow while retaining the complete prior
release catalog. Report:
[paper-1.21.1-player-kills-import-strict-20261108-r1.json](reports/paper-1.21.1-player-kills-import-strict-20261108-r1.json), SHA-256:
`cddad86f19b04c40ca23b364e5a0e223b056c65d358e05ed5f67d5f3e6c38ee1`.
Compressed server log SHA-256:
`590891ca6d4c597aba4d17e7c8db45d228006bee94764974676539ed71edd382`.
Client log archive SHA-256:
`0d079eb950937c4b8e1f935e490c5d9eddbdc3dfaadc04c0c06952d8b7f539d1`.
Plugin SHA-256:
`e699b8a21826b630af7e07d506ca2ed2e1fc828cf01d9c886f97d68ea3386dca`.
That strict report has 400 probes, zero incomplete. Archived logs redact web
token query values; the source fixture retains the original run logs.

Previous fixture `srv-paper-radius-conditions-strict-20261108-r2` passed
the full strict gate on Paper 1.21.1-133: 399 registered probes, 380 passed,
19 expected rejections, and zero failed or incomplete. It verifies Player
absorption comparisons, six imported radius conditions with positive and
negative real-entity controls, and the native-AI Bee stinger workflow. Report:
[paper-1.21.1-radius-conditions-strict-20261108-r2.json](reports/paper-1.21.1-radius-conditions-strict-20261108-r2.json), SHA-256:
`dfcec3d4becd5b3f89f9001448f48122e607a85ec289c7bca6ab85cb8ecd9195`.
Compressed server log SHA-256:
`2143ab88fbe2cb801f985236f62f2637206df76fbc011ec63c610ccc27b8adee`.
Client log archive SHA-256:
`bd28e895cc1c7a8988b7d9272992e47fffa7efcab23f032a72fa70722b4cf57d`.
Plugin SHA-256:
`9659402a08f17640a0fcc255b1693d6988eab69cdb78694ed4f040b051a5084d`.

Previous fixture `srv-paper-condition-mobility-states-strict-20261108-r7`
passed the full strict gate with 396 registered probes, 377 passed, 19
expected rejections, and zero failed or incomplete. It verifies positive and
negative falling, climbing, and charged states, imported aliases, cleanup
restoration, and direct and AI-target line-of-sight conditions.

Previous fixture `srv-paper-rabbit-options-strict-20261107-r2` passed the
full strict gate with 392 registered probes, 373 passed, 19 expected
rejections, and zero failed or incomplete. It verifies imported Rabbit
`Options.RabbitType`, Rabbit-only application, and preservation of
wrong-entity and malformed values under `entity.unsupported.Options`.

Previous full strict fixture `srv-paper-full-strict-20261107-r4` passed the
391-probe baseline with 372 passed, 19 expected rejections, and zero failed
or incomplete. It remains the pre-RabbitType full-catalog evidence.

Previous fixture `srv-paper-mythic-teleport-strict-20261107-r1` passed the full
strict gate on Paper 1.21.1-133: 391 registered probes, 372 passed, 19
expected rejections, and zero failed or incomplete. It verifies imported
Mythic `teleport`/`tp` for entity and location targets, safe landing,
`unsafe` bypass, bounded spread, preserved and overridden orientation, and
invalid-input preservation with warnings. Report:
[paper-1.21.1-mythic-teleport-strict-20261107-r1.json](reports/paper-1.21.1-mythic-teleport-strict-20261107-r1.json), SHA-256:
`ceefa59fa0b73dce20cca9e1e41cc6cef3536bca4c695c9ffaadd704dacd6406`.
Compressed server log SHA-256:
`6c1afb1f8d38418618c119268b26ee5bdb8861dadecef41317f612e82dfd3ee2`.
Client log archive SHA-256:
`20ebcf32379083b80384b2993c00a706f718f58d4176ed25b0b7fea9a2247c24`.
Plugin SHA-256:
`7cf45192c315391cb726ed3afb89e775d7fb899367d7ec05f4d1330c8f19250c`.

Previous fixture `srv-paper-slime-natural-strict-20261107-r10` passed the full
strict gate on Paper 1.21.1-133: 390 registered probes, 371 passed, 19
expected rejections, and zero failed or incomplete. The imported protected
Slime was killed through Bukkit's real damage path; its native
`SlimeSplitEvent` was observed and cancelled, while an inverse control emitted
an uncancelled event and created real child Slimes. Temporary mob-spawning
flags and the game rule were restored before cleanup. Report:
[paper-1.21.1-slime-natural-strict-20261107-r1.json](reports/paper-1.21.1-slime-natural-strict-20261107-r1.json), SHA-256:
`2b267ee50d929f69a93f8a8257f11c089c855e1b240e6c635fd4dc8d0ee6ae1c`.
Compressed server log SHA-256:
`7cfed098c088ee17ffd9d724c3f9fcd52123d23e7aa1b9ddf18a302553f78109`.
Client log archive SHA-256:
`6d74829591cf38ae24c50b5132c3d5f4e3bac18b15437f69e5709c02e86bfea3`.
Plugin SHA-256:
`a966568b9fcfb4de99c2268eccdce150be49f1fbf47fca20bd433a7e28b2edab`.

Previous fixture `srv-paper-audience-filter-negative-strict-20261107-r1` passed
the full strict gate on Paper 1.21.1-133: 389 registered probes, 370 passed,
19 expected rejections, and zero failed or incomplete. A two-client workflow
verified permission exclusion (`permission:!helstera.menu` omits the
permission-bearing tester and includes the guest) and faction exclusion
(`faction:-undead` includes the tester and omits the guest assigned to
`undead`), alongside positive permission/faction and radius controls. Report:
[paper-1.21.1-audience-filter-negative-strict-20261107-r1.json](reports/paper-1.21.1-audience-filter-negative-strict-20261107-r1.json), SHA-256:
`a16283dff27ac196d2a36199718c62686d8032ee6481dfe0916f57c438458738`.
Compressed server log SHA-256:
`4b552b4b5df5f9b79c14ef73011cb5ad9eb74e2524b1a6729433f07d1b4b5030`.
Client log archive SHA-256:
`e6148a8360062ebb535ea6c9dec71ff5c659e6dfbc1f5e50d06d734994f7f8e2`.
Plugin SHA-256:
`f3e7d53ca06e9ef62d1c07425d66b0868f70d9b0ad81f02dae6b00b50477745b`.
Archived logs replace web token query values with `[REDACTED]`; original
fixture logs are unchanged.

Previous fixture `srv-paper-entity-flags-strict-20261107-r3` passed the full
strict gate on Paper 1.21.1-133: 389 registered probes, 370 passed, 19
expected rejections, and zero failed or incomplete. Production import and
spawning verified `Tamed`/`Angry` on Wolf, `Angry` on Zombified Piglin, and
`PatrolLeader`/`PatrolSpawnPoint` on Pillager against inverse controls.
Paper accepted the imported `CanJoinRaid` setter call, but exposes no getter
for effective-state verification. Report:
[paper-1.21.1-entity-flags-strict-20261107-r3.json](reports/paper-1.21.1-entity-flags-strict-20261107-r3.json), SHA-256:
`4add60fd513d716fd8bfbb26cb6e141691ff5db580e8ef9c54b98a80d3424dc5`.
Compressed server log SHA-256:
`04a523b80a82e52be7e39d7a128ac0daf90300aabf56aa3ddda20bce7a808eb2`.
Client log archive SHA-256:
`9c0e10957d681ca11040c8f614e493967c4a1da21b1a51ed6d1453352f70b073`.
Plugin SHA-256:
`dfa06ca836ad0d177962b3ee553197ad41c0989942c0637aafb4fe044015da28`.

Previous fixture `srv-paper-prevent-sunburn-strict-20261107-r4` passed the
full strict gate with 388 probes (369 passed, 19 expected rejections, zero
failed or incomplete). It verified imported `Options.PreventSunburn` against
an inverse control: both native midday sunlight events on the protected
Zombie were cancelled, while the control event was uncancelled and retained
132 fire ticks. Report:
[paper-1.21.1-prevent-sunburn-strict-20261107-r4.json](reports/paper-1.21.1-prevent-sunburn-strict-20261107-r4.json), SHA-256:
`7dcdb9a10f0a5a62388f9578db3fd9d88d4417cea8e7ffb44dcce675203e3bbc`.
Compressed server log SHA-256:
`3ec6c62d4362ed2d95a6300b605e17990286b9bfa8249bea6e950cbe80f3c0db`.
Client log archive SHA-256:
`4c4baf391ffee276d4f635163402b5719b5cf5fc663825f55d9327ba05ca0e79`.
Plugin SHA-256:
`362e44995f813fbab99f4a8c09ca65b685183602922cf9d794b4927f7ddda69b`.

Previous full strict fixture `srv-paper-bee-stinger-loss-strict-20261107-r6`
passed 387 probes (368 passed, 19 expected rejections, zero failed or
incomplete). Imported Bee nectar/stung fields and `PreventStingerLoss` are
verified against inverse controls through production spawning. The stinger
probe observes one uncancelled native AI attack from each Bee against a
stationary Zombie; the protected Bee restores its stinger and the control
remains stung. Report:
[paper-1.21.1-bee-stinger-loss-strict-20261107-r6.json](reports/paper-1.21.1-bee-stinger-loss-strict-20261107-r6.json), SHA-256:
`a19476296ade4ac370e358a4b83d5f994d190f8cd871686f2bb9b68ab9fa1571`.
Compressed server log SHA-256:
`af282d10b5a26eba921689358a9f095937989ff1716b33ef13055ebabc6049d3`.
Client log archive SHA-256:
`be17f8a8a1c9263ed98866d3f5eb64d06bd1c655a5972c8380190ec2b509a270`.
Plugin SHA-256:
`d1f34a62d8be828a525af0a9a98ff65d0c3478b47883c921b1fa7002bd20b7c5`.

Previous fixture `srv-paper-horse-options-strict-20261107-r1` passed the full
strict gate on Paper 1.21.1-133: 385 registered probes, 366 passed, 19
expected rejections, and zero incomplete. Imported Horse color, style,
domestication, maximum domestication, and jump strength are read back against
a contrasting control. Report:
[paper-1.21.1-horse-options-strict-20261107-r1.json](reports/paper-1.21.1-horse-options-strict-20261107-r1.json), SHA-256:
`6c4c3a516d999fa409817075491db62d5d479db5b33a139672f75e4673faae6a`.
Compressed server log SHA-256:
`8dfa77dc66263d3fa6e4d53ed0b018b3dbb5458caefe6f1e029078b61d7d117f`.
Client log archive SHA-256:
`655667e0946236116aed9f46ec0090a2aca37b51ebd430afa389893ff0b5397d`.
Plugin SHA-256:
`740b24fb9990aaa7038665355e0ab153430ac26a46b37b492aa4c45523ff4016`.

Previous fixture `srv-paper-sheep-options-strict-20261107-r1` passed the full
strict gate on Paper 1.21.1-133: 384 registered probes, 365 passed, 19
expected rejections, and zero incomplete. The imported Sheep probe applies
`SheepColor: RED` and `Sheared: true` through production mob spawning and
checks a white, unshorn control. Report:
[paper-1.21.1-sheep-options-strict-20261107-r1.json](reports/paper-1.21.1-sheep-options-strict-20261107-r1.json), SHA-256:
`eea8fd2e6462b636425882145048d0de6ed9887b43a416aa7517e6f6cb93ad14`.
Compressed server log SHA-256:
`8fd0c7855beee03712338b5c482245157cff12b8dc848480e634f11836297afc`.
Client log archive SHA-256:
`279d6430528ce8315c8a094f97071a9b2ef57a597b62e8e1a78ee3faa053e1d2`.
Plugin SHA-256:
`b8174ab89cf0a585b063da12db46e9e8859da544698f51b61a9b596f7a8c7a77`.

Previous fixture `srv-paper-cat-options-strict-20261107-r3` passed the same
full strict gate and verifies imported Cat `Type`, `Sitting`, and
`CollarColor` through production spawning against a `TABBY`/not-sitting/`RED`
control. Report: [paper-1.21.1-cat-options-strict-20261107-r3.json](reports/paper-1.21.1-cat-options-strict-20261107-r3.json), SHA-256:
`a461280efb0d311b64da172ca3fb45250c56038f2c25449ff8c7faf7b7bec096`.

Previous fixture `srv-paper-creeper-options-strict-20261107-r1` passed the
same full strict gate: 384 probes, 365 passed, 19 expected rejections, and
zero incomplete. Its imported Creeper probe verifies radius, fuse, powered
state, and protected/control explosion behavior. Report:
[paper-1.21.1-creeper-options-strict-20261107-r1.json](reports/paper-1.21.1-creeper-options-strict-20261107-r1.json), SHA-256:
`4088b4c42abe7521ee5f16e1323fa94198dd40c7d354906a738c9883ce1ce6e3`.
Compressed server log SHA-256:
`1657512ec9a2a00219e90dd07c594446aa899180c75196e10979efd5db21f554`.
Client log archive SHA-256:
`204ec5f413a0f6cc2f1de0b20f837733c09d78571963968988d4360bc1116986`.
Plugin SHA-256:
`cfa2888713144cb1fec54d5b341a8d3d6e696de21457d7338e02ab895326f7f6`.

Previous fixture `srv-paper-entity-size-range-strict-20261107-r1` passed the
full strict gate on Paper 1.21.1-133: 383 registered probes, 364 passed, 19
expected rejections, and zero incomplete. Its `entitySizeRange` probe imports
Mythic `Options.Size: 2to5` for Slime, MagmaCube, and Phantom, then samples each
Paper entity 64 times; all values stayed inside the configured range and each
family reached both endpoints. Report:
[paper-1.21.1-entity-size-range-strict-20261107-r1.json](reports/paper-1.21.1-entity-size-range-strict-20261107-r1.json), SHA-256:
`83d12f6314ce4a4a8138f514003d8c91ada0dcad3269de2612aabc8d289db8b9`.
Compressed server log SHA-256:
`9478995075cab22112f61c76ffff80559f35fb77d09cde6205b0fdb148434867`.
Client log archive SHA-256:
`40072bdb7eb2d1b78bf0f9492af57941a7a989bf54c23d802980fdddf4a71dd2`.
Plugin SHA-256:
`9c2246042a36ed83bcca6b3e2d13d09ff73dd62101ed44f3c17a24a4e638c3c1`.

Previous fixture `srv-paper-interactable-strict-20261107-r2` passed the full
strict gate on Paper 1.21.1-133: 383 probes, 364 passed, 19 expected
rejections, and zero incomplete. Its real Mineflayer `use_entity` packets
confirm `Interactable=false` is cancelled while `Interactable=true` is allowed.
Report: [paper-1.21.1-interactable-strict-20261107-r2.json](reports/paper-1.21.1-interactable-strict-20261107-r2.json), SHA-256:
`97b8088a4acfa2c5d161c5c41730f1c7c796b60cac046fa6f1ba9077ea1a1f4e`.

Previous fixture `srv-paper-tracked-location-strict-20261107-r1` passed the
full strict gate on Paper 1.21.1-133: 382 registered probes, 363 passed, 19
expected rejections, and zero incomplete. Its `locationTargeterDispatch` probe
verifies imported `TrackLocation`, `@TrackedLocation`,
`DistanceFromTrackedLocation`, and missing-position rejection. Report:
[paper-1.21.1-tracked-location-strict-20261107-r1.json](reports/paper-1.21.1-tracked-location-strict-20261107-r1.json), SHA-256:
`b86a69aeeb3dde890b3845785fda47137faafc758484e697b68c3e066df4b41d`.
Compressed server log SHA-256:
`664d751529a197ff6c365a6f9d27bfa56d4c978a671a86988d6607107df97480`.
Client log archive SHA-256:
`fa1c49ec2656490216b16349494e25cafe08ffabf4acddcb90836bbf3e2f8d74`.
Plugin SHA-256:
`f360cbbd3b0102f406086f61c507cb994a32352c24fe1db090b29d9710802a1a`.

Previous fixture `srv-paper-mythic-spawner-biome-strict-20261107-r2` passed the
full strict gate on Paper 1.21.1-133: 382 registered probes, 363 passed, 19
expected rejections, and zero incomplete. Its imported Spawner condition probe
maps `BiomeType{type=plains;exact=false}`, naturally generates one managed mob
in the matching category, rejects `desert` with zero spawns, and restores both
original biomes. It also verifies matching-world, rain, and non-thundering
conditions, weighted `RandomMobs`, imported group-size capping, `Chance`
validation, and per-variant DropTables through natural Bukkit death. Report:
[paper-1.21.1-mythic-spawner-biome-strict-20261107-r2.json](reports/paper-1.21.1-mythic-spawner-biome-strict-20261107-r2.json), SHA-256:
`db1364e2628752d8264afb3ed777d14ecf30cd2a10743456445ffc112657e205`.
Compressed server log SHA-256:
`fe9417b24cff08f4e16966e57514946fdc5e69e5ca8c1b69e13aa05bc1eb05f0`.
Client log archive SHA-256:
`2f7b7c4e3df3f74e8fe0dca73949ee2f474d96672ab840cdeeb7885634bd830a`.
Plugin SHA-256:
`fd72793b1e8a9aa8b62bc8d7a354dfaf1e36208f874e81fc37f10da1dadca9f5`.

Previous fixture `srv-paper-mythic-random-drops-strict-20261106-r4` passed
the full strict gate on Paper 1.21.1-133: 381 registered probes, 362 passed,
19 expected rejections, and zero incomplete. `mythicSpawnerRandomPoolImported`
converts Mythic `RandomMobs` weights 1:3 and observes 0.25/0.75 across 100
production spawns. The report also verifies imported `GroupSize: 3` capped by
`MaxMobs: 2`, valid `Chance: 1.0`, and rejection of out-of-range `Chance: 1.5`.
`mythicSpawnerRandomPoolDropTables` spawns 32 weighted variants and confirms
each imported Mob retains its own `DropTable-1`; natural Bukkit damage produces
one Bread for the common variant and one Diamond for the rare variant, with no
cross-table drops. Each temporary table is reloaded before its kill and the
original loot state is restored.
Report SHA-256:
`5e6dcf481074465073601a50236253d1d7d7a4d3c931eb8f3d155ddd4c8726fe`.
Compressed server log SHA-256:
`db4ce71ad238b96ad67aabea3625aecf90db9623d9381acea9f58c8ff6dbc973`.
Client log archive SHA-256:
`cf77597a648d6a584aa0d6362b5938c64ac21aef3d0e81a73892414eceaf80ef`.
Plugin SHA-256: `773367e232f2c48f5b96876c3572ad486e3918e84c3cfbe7fe925866c353202a`.

Previous full strict fixture `srv-paper-mythic-random-pool-strict-20261106-r1`
passed 380 probes (361 passed, 19 expected rejections, zero incomplete) and
sampled the imported weighted pool at 0.27/0.73. Its report and hashes remain
in `release-evidence.json`.

Previous full strict fixture `srv-paper-mythic-spawner-group-size-strict-20261106-r5`
records the real-client `SpawningDistance` movement and removal timing: 14.36
blocks of player movement, 15.00 blocks between player and mob, and removal 2
ticks after crossing the configured 8-block radius, within the 10-tick AI
decision period.

Previous full strict fixture `srv-paper-execution-audience-mob-strict-20261106-r5` passed
the full strict gate on Paper 1.21.1-133: 377 registered probes, 358 passed, 19
expected rejections, and zero incomplete. The imported Mob
`Options.ExecutionAudience` path verifies production spawn, radius target
selection, entity PDC persistence, and member-only visibility across two clients.
Imported `RemoveFromAudience`/`AddToAudience` actions verify `@EntitiesInRadius`
selection, persisted empty/restored audiences, and visibility transitions. The
new `executionAudienceMobSummon` probe imports and dispatches `summon`, confirms
the new mob inherits its source audience, and checks that its model visuals stay
visible only to the member client.
Report SHA-256:
`a9da1ae78d324b01ceafdeb93c0e31b08783763020d3f6f0d3964c25a308824e`.
Compressed server log SHA-256:
`32b7ae5170e2d58e3832a2d381f742810e5850543cc7e89fd5791f1b34a7c9ee`.
Client log archive SHA-256:
`beee7dbf361bf8c3acc4907ac3f9fa3925c2da34c47c2b8ff574646510686db2`.
Plugin SHA-256: `5aa37479c888f3e77aaef920ee0920a32b97d16cf7df6f6cd047d59334dc9e0f`.

The two-boot Paper integration passed on Paper 1.21.1-133. It confirms a
persistent managed mob keeps the same entity
UUID and exact non-empty execution audience across server shutdown/startup; the
startup restore path rebinds that audience to its runtime instance. This run
had no clients, so post-restart member/outsider visibility is not claimed.
Report SHA-256:
`705b216133dee0e40ec46d72c96886fa0803da193d866566f813527004137573`.
First-boot compressed log SHA-256:
`3f59b604d540b624419a59017a4f61dd08577e60f4cb3174609a17bb31e86f82`.
Second-boot compressed log SHA-256:
`9684c51f8add8a429ccb013daa44593df7f26f62476f92ff54364e0c69e2105c`.
Plugin SHA-256:
`6ba52a22a59492ec6d0fe82991d7d27d7298e152e91515974ff615a08091a750`.

Previous fixture `srv-paper-execution-audience-mob-strict-20261106-r3` passed
376 probes (357 passed, 19 expected rejections, zero incomplete). It verifies
the imported mob audience spawn and targeter-based audience mutation paths;
summon inheritance was added to the release catalog and proven in r5.

Historical fixture `srv-paper-imported-mob-defaults-strict-20261106-r4` passed
373 probes. Its `DefaultMobOptions` assertions predate the official source
audit and incorrectly applied unsupported global keys; the latest fixture above
is the valid evidence for those semantics. The run also recorded Potion
consumption/expiry, movement beyond `SpawningDistance`, web reload, and
dual-client menu actions. Its original report remains historical evidence.

Previous fixture `srv-paper-imported-mob-defaults-strict-20261106-r2` passed
the full strict gate on Paper 1.21.1-133: 372 registered probes, 353 passed,
19 expected rejections, and zero incomplete. Its original evidence remains in
`release-evidence.json`.

Previous fixture `srv-paper-execution-audience-strict-20261106-r1` passed the full
strict gate on Paper 1.21.1-133: 371 registered probes, 352 passed, 19 expected
rejections, and zero incomplete. It verifies imported `isInAudience` aliases,
absent/member/outsider/empty audience outcomes, non-player rejection, and `?@`
target filtering. It also verifies imported block/entity `LookingAt` selectors
through real Paper ray traces, type/distance negative controls, invalid-input
preservation, and state cleanup. The Mineflayer menu client logged
`PartialReadError` warnings while parsing menu packets; the server-side render
and click checks and client workflow report passed, then the raw-potion client
rejoined and completed its workflow.

Archived report: [paper-1.21.1-execution-audience-strict-20261106-r1.json](reports/paper-1.21.1-execution-audience-strict-20261106-r1.json), SHA-256:
`2d73ffe76e8bd39bd1e4eb2a595aada8dbfe040f7ce76777e4332fe37e1e32d7`.
Compressed server log: [paper-1.21.1-execution-audience-strict-20261106-r1.log.gz](reports/paper-1.21.1-execution-audience-strict-20261106-r1.log.gz), SHA-256:
`5cbe16f660bc246472a1d378477194eb7ee2c62e6f15997a73e67119e6e8672a`.
Client logs: [paper-1.21.1-execution-audience-strict-20261106-r1-client-logs.tar.gz](reports/paper-1.21.1-execution-audience-strict-20261106-r1-client-logs.tar.gz), SHA-256:
`fc822abf800c714cef5722b7996e8ecd2fa2281d551697a4b7cf3f0b52f69218`.
Plugin SHA-256: `abbc8f64d612eb4cd7cf548f5cde663821b6183687e2c9649affe3003febd3cf`.

Previous fixture `srv-paper-name-import-strict-20261106-r2` passed the full
strict gate on Paper 1.21.1-133: 369 registered probes, 350 passed, 19 expected
rejections, and zero incomplete. `importedNameCondition` runs `Name` and
`nameIs` through the Mythic importer and `SkillService`; a real managed Zombie
matches a quoted custom name, rejects its default name while the custom name is
set, then matches its Bukkit entity name after the custom name is cleared.
Unknown, duplicate, and empty arguments remain preserved.

Archived report: [paper-1.21.1-name-import-strict-20261106-r2.json](reports/paper-1.21.1-name-import-strict-20261106-r2.json), SHA-256:
`53bf9b3683bba8abec21886dcc7300d3b4fe169a0395f59a691b93e251cb4af9`.
Compressed server log: [paper-1.21.1-name-import-strict-20261106-r2.log.gz](reports/paper-1.21.1-name-import-strict-20261106-r2.log.gz), SHA-256:
`429ea8ae60881efd11ab25151657b514b32ea68c93b3a1f13c016d0a1e2e0553`.
Client logs: [paper-1.21.1-name-import-strict-20261106-r2-client-logs.tar.gz](reports/paper-1.21.1-name-import-strict-20261106-r2-client-logs.tar.gz), SHA-256:
`524073a7389f8f3cb1805aedd231500ed8234065c2d192fde6d44b7c6c1905c9`.
Plugin SHA-256: `42760067a8c4c7ea88d9691da8886d5dc5db7f9fd50a369489ba8b25c45e3657`.

Previous fixture `srv-paper-free-inventory-slot-strict-20261106-r1` passed the
full strict gate on Paper 1.21.1-133: 368 registered probes, 349 passed, 19
expected rejections, and zero incomplete. `importedFreeInventorySlotCondition`
runs `HasFreeInventorySlot` and `HasFreeSlot` through the Mythic importer and
`SkillService`; it verifies empty/full inventory results on a real player and
preserves invalid arguments. The latest strict item-similarity fixture reruns
both aliases through the current binding path. Its report and compressed logs
remain listed in `release-evidence.json`.

Previous fixture `srv-paper-variable-conditions-strict-20261106-r1` passed the
full strict gate on Paper 1.21.1-133: 367 registered probes, 348 passed, 19
expected rejections, and zero incomplete. `importedVariableConditions` runs
`VariableEquals`, `VariableInRange`, and `VariableIsSet` through the Mythic
importer and `SkillService`; it checks aliases across skill/caster/target/world/
global scopes, numeric range boundaries, missing/wrong-type rejection, quoted
semicolon values, dynamic variable placeholders, and invalid-range preservation.

Archived report: [paper-1.21.1-variable-conditions-strict-20261106-r1.json](reports/paper-1.21.1-variable-conditions-strict-20261106-r1.json), SHA-256:
`c5375c3202f5c42a88bcb91ec839f9fe4afd09572c8e3bb614883ce61bc4cae5`.
Compressed server log: [paper-1.21.1-variable-conditions-strict-20261106-r1.log.gz](reports/paper-1.21.1-variable-conditions-strict-20261106-r1.log.gz), SHA-256:
`72341181511f48f949cf9e869cbd0b02ea57a429a33c2a334e7d221b780f68dc`.
Client logs: [paper-1.21.1-variable-conditions-strict-20261106-r1-client-logs.tar.gz](reports/paper-1.21.1-variable-conditions-strict-20261106-r1-client-logs.tar.gz), SHA-256:
`30a54ae26df932ba72229d77e80ebec94ba5615c4200954081536d64e715af4b`.
Plugin SHA-256: `b37f06a021b904fd3fa61b2710f88cc4c533943c533e28a813e7bc1dd0eae8b6`.

Previous fixture `srv-paper-aura-target-strict-20261106-r1` passed the full
strict gate on Paper 1.21.1-133: 366 registered probes, 347 passed, 19 expected
rejections, and zero incomplete. `mythicAuraRuntime` verifies imported
target-only named Aura checks, Aura application, partial/default/`ANY` removal,
runtime aliases, missing Aura rejection, and malformed AuraRemove rejection.
The probe uses real Paper entities; importer tests also cover accepted and
rejected target Aura arguments.

Previous fixture `srv-paper-aura-remove-strict-20261106-r1` also passed the
full strict catalog; it verifies removal aliases and partial/default/`ANY`
removal without the imported target Aura check.

Previous fixture `srv-paper-aura-strict-20261106-r1` passed the full strict
gate on Paper 1.21.1-133: 366 registered probes, 347 passed, 19 expected
rejections, and zero incomplete. It verifies Aura application, named presence,
stack merging and conditions, imported Aura type checks, and malformed
condition rejection. Its report, logs, and tested plugin hash remain in
`release-evidence.json`.

Previous fixture `srv-paper-entity-type-traits-strict-20261106-r1` passed the full
strict gate on Paper 1.21.1-133: 365 probes, 346 passed, 19 expected rejections,
and zero incomplete. `importedEntityTypeTraitsCondition` verifies imported
runtime selectors on real Horse, Panda, Villager, Zombie Villager, Sheep,
Shulker, Parrot, Llama, Tropical Fish, and Iron Golem entities, including
inapplicable-trait rejection. Report SHA-256:
`59ef6eeffea0c5cf6c24cbfe1d7c956a8a8215f3f8586a59f36cbcdb0d264c23`; compressed
server log SHA-256:
`db7d2518f777c2651848149f734b36409722525ee37c79b72a3836782f902cb3`; client log
archive SHA-256:
`0c3a91d398ccf4e0d4ef85c812760dec02d9ae408e184ff75888271e75fe457a`; plugin
SHA-256: `327c83088c400d0e39f6045d1e58d8a226e999e8d67d9feaa20116a5faacd59d`.

Previous fixture `srv-paper-entity-type-variant-import-strict-20261106-r1`
passed the full strict gate on Paper 1.21.1-133: 364 probes, 345 passed, 19
expected rejections, and zero incomplete. `importedEntityTypeVariantCondition`
verifies `entityType`, `entityTypeIs`, and `mobtype` through the importer and
runtime condition registry on a real Cat. TABBY and BLACK alternatives pass;
RED, a wrong entity type, invalid variants, unsupported nested traits, and
malformed type constraints are rejected or preserved as expected. Report SHA-256:
`9a887d003b8e4fd7ac2b31a9da067ade1d036316cca6ddf18dd6e943205c2c27`; compressed
server log SHA-256:
`581aeeba15500a92c974a2a05c8d829073742e58d7a548c80aabc70c25e29ad5`; client log
archive SHA-256:
`2e9f8e665fbd3eb38b9b22ee8b08d19d371f2a9a4864474d80b3300364de761f`; plugin
SHA-256: `58f94be7da6216b62e09e7d3405bc6229b97c022e2a2fdd74e2450957721c369`.

Previous fixture `srv-paper-is-survival-mode-import-strict-20261106-r1` passed the full
strict gate on Paper 1.21.1-133: 361 probes, 342 passed, 19 expected rejections,
and zero incomplete. `importedSurvivalModeCondition` verifies Mythic
`?isInSurvivalMode` and `?inSurvivalMode` through the importer and `SkillService`
for a real online Player in Survival, rejects Adventure, a Zombie target, and
malformed arguments, and restores the original game mode. Report and compressed
server/client logs are listed in `release-evidence.json`; report SHA-256:
`4793cc5ed1604e1affb24244896c2c678b8c2eb8fead64aec03398023fb3e331`; plugin
SHA-256: `47dbd266c9437da0ed5cee53fb9c88618d682bb0af43c1eccc07ce42e1cf50bf`.

Previous fixture `srv-paper-is-spyglass-import-strict-20261106-r1` passed the
full strict gate on Paper 1.21.1-133: 360 probes, 341 passed, 19 expected
rejections, and zero incomplete. `importedSpyglassCondition` verifies Mythic
`?isUsingSpyglass` and `?isScoping` through the importer and `SkillService` for
a real online Player actively using a Spyglass, and rejects the inactive state,
a Zombie target, and malformed arguments. Report and compressed server/client
logs are listed in `release-evidence.json`; report SHA-256:
`31524b55da5be947af54b973066d52b8f71b7d96feb647d5ba6fa88d6ae7c6d7`; plugin
SHA-256: `38e0e9769a086950b3e4f5a6923d8f58f44a16fdf3bfaebf512f6d49462a7a20`.

Previous fixture `srv-paper-is-frozen-import-strict-20261106-r2` passed the full
strict gate on Paper 1.21.1-133: 359 probes, 340 passed, 19 expected rejections,
and zero incomplete. `importedIsFrozenCondition` verifies Mythic `?isFrozen`
and `?frozen` through the importer and `SkillService` against a real frozen
Zombie, after thawing, and with an invalid argument. Report and compressed
server/client logs are listed in `release-evidence.json`; plugin SHA-256:
`4af92b5462a6d1bb533fdb14df654c7e9e9cea066eaef8da10c568bdd4976335`.

Previous fixture `srv-paper-item-recharging-import-strict-20261106-r1` passed
the full strict gate on Paper 1.21.1-133: 358 probes, 339 passed, 19 expected
rejections, and zero incomplete. `importedItemRechargingCondition` verifies
Mythic `?itemRecharging` and `?recharging` through the importer and
`SkillService`, checks both aliases after a real Bukkit attack lowers Player
attack cooldown, and rejects a Zombie target. Report and compressed
server/client logs are listed in `release-evidence.json`; plugin SHA-256:
`7e4b194a8beb0645e457108ab19d349c9888c783b74724d2cdb78ed533fecc91`.

Previous fixture `srv-paper-origin-location-import-strict-20261105-r4` passed
the full strict gate on Paper 1.21.1-133: 357 probes, 338 passed, 19 expected
rejections, and zero incomplete. `importedOriginLocationCondition` verifies
Mythic primary and alias forms through the importer and `SkillService`, block
and exact `BehaviorContext.origin()` coordinates, mismatch rejection, and
malformed number/boolean rejection. Report and compressed server/client logs
are listed in `release-evidence.json`; plugin SHA-256:
`42abd200b6cde48ca19471c180dbf2821e45d02eb2b12d993b8bd27b221c4bf0`.

Previous fixture `srv-paper-trigger-types-import-strict-20261105-r2` passed the
full strict gate on Paper 1.21.1-133: 356 probes, 337 passed, 19 expected
rejections, and zero incomplete. Its TriggerItemType and TriggerBlockType
checks use explicitly constructed `BehaviorContext` values and do not claim
natural Bukkit event delivery. Report and compressed server/client logs are
listed in `release-evidence.json`; plugin SHA-256:
`41cf9e79c81657f05acb68a95a78ad5fdda073de60b7502d17b79e428538ec4a`.

Previous fixture `srv-paper-trigger-types-import-strict-20261105-r1` also passed
356 probes (337 passed, 19 expected rejections, zero incomplete) against the
previous probe JAR. The r2 fixture reruns these controls against the current
exact JAR. Report and compressed server/client logs are listed in
`release-evidence.json`; plugin SHA-256:
`071ceebd2da8a904dfbc3aef8a2594fb5d22c04d83e23acf4e798f9943ebe65e`.

Previous fixture `srv-paper-has-enchantment-import-strict-20261105-r1` passed the full
strict gate on Paper 1.21.1-133: 354 probes, 335 passed, 19 expected
rejections, and zero incomplete. `importedHasEnchantmentCondition` verifies
Mythic `?hasEnchantment` and `?hasEnchant` on a real managed Zombie, including
positive Sharpness/`ANY`, level/type negatives, and equipment restoration.
Report and compressed server/client logs are listed in `release-evidence.json`;
plugin SHA-256:
`81492669538c1c4358a4ac7f8537e76791a31fb08d55c1246137ad6afe149fbe`.

Previous fixture `srv-paper-condition-import-strict-20261105-r1` passed the
full strict gate on Paper 1.21.1-133: 353 probes, 334 passed, 19 expected
rejections, and zero incomplete. It verifies imported Aerial and Color aliases
plus `MaterialIsOnCooldown` against a real player cooldown. Report and
compressed server/client logs are listed in `release-evidence.json`; plugin
SHA-256:
`75b5bf2bde256454caa90aa1bea652ac67754b0f65b3e38633d3ddbf7933104a`.

Fixture `srv-paper-color-import-strict-20261105-r1` passed the full strict
gate on Paper 1.21.1-133: 352 probes, 333 passed, 19 expected rejections, and
zero incomplete. `importedColorCondition` verifies `?color{c=RED}` and
`?clr{color=BLUE}` against a real red Sheep; the same fixture retains both
imported Aerial aliases and their floating/grounded controls. Report and
compressed server/client logs are listed in `release-evidence.json`; plugin
SHA-256:
`6411d68946e2d04082086609dba9ff8f74e5d404c99df577ae93d6588bf8a51e`.

Fixture `srv-paper-aerial-import-strict-20261105-r1` passed the full strict
gate on Paper 1.21.1-133: 351 probes, 332 passed, 19 expected rejections, and
zero incomplete. `importedAerialCondition` verifies that `?aerial` and
`?isAerial` both map to runtime `aerial`, pass on a floating ArmorStand, and
reject a grounded entity. Report and compressed server/client logs are listed
in `release-evidence.json`; plugin SHA-256:
`30315bb483240a5386d7d47ab6d4ca88ab9f0bff2d83c203924a7d1a20903722`.

Fixture `srv-paper-is-child-import-strict-20261105-r1` passed the full strict
gate on Paper 1.21.1-133: 350 probes, 331 passed, 19 expected rejections, and
zero incomplete. `importedIsChildCondition` executes `?isChild` through
`MmConditions.translate` and `SkillService`: an adult Pig related to its caster
matches, the same entity does not match `isBaby`, an unrelated baby Pig does
not match `isChild`, and the baby control matches `isBaby`. Report and
compressed server/client logs are listed in `release-evidence.json`; plugin
SHA-256:
`e0c6fdaf31e96003a68bd589d1ca88e0786dabb0ecdf8333022474d1d52ddc7b`.

Fixture `srv-paper-age-options-strict-20261105-r3` passed the full strict
gate on Paper 1.21.1-133: 349 probes, 330 passed and 19 expected rejections,
with zero incomplete. Its required `ageOptions` observation is
`age-options result=true age=-24000 baby=true adult=true age-lock=true
zombie-baby=true`: exact animal age/AgeLock and both age controls were read
from real Sheep, while the Zombie-specific Baby setter was read from a real
Zombie. The importer preserves numeric `Age` and `AgeLock` with warnings for
ageable non-animal mobs because Bukkit does not retain exact tick ages for
those types. Report and compressed logs are listed in `release-evidence.json`;
plugin SHA-256:
`62051171e7637e3c9d1d40bcc7d254bc6d565d1cca3428a6fff62d019380b50e`.

Fixture `srv-paper-aerial-condition-strict-20261105-r2` passed the complete
strict gate on Paper 1.21.1-133: 349 probes, 330 passed and 19 expected
rejections, with zero incomplete. `aerialCondition` verifies a floating
ArmorStand through both `aerial` and `isAerial`, then rejects a grounded
player and records `ground-entity=player`. The full report also includes the
two-client menu/Audience, raw Potion, and player-movement workflows. Report and
compressed logs are listed in `release-evidence.json`; plugin SHA-256:
`97f61a7392ad0051acc0ee55cdcc0272c0512d2675347c138be3ce3f918eaa6a`.

Fixture `srv-paper-aerial-condition-strict-20261105-r1` passed the complete
strict gate on Paper 1.21.1-133: 349 probes, 330 passed and 19 expected
rejections, with zero incomplete. `aerialCondition` verifies a floating
ArmorStand through both `aerial` and `isAerial`, then rejects a grounded
player. The full report also includes the two-client menu/Audience, raw Potion,
and player-movement workflows. Report and compressed logs are listed in
`release-evidence.json`; plugin SHA-256:
`0c35898c00b2b52ebebe8aa8b0ee53665682188c53e10a8149d2fce67df98159`.

Fixture `srv-paper-enderman-condition-strict-20261105-r1` passed the complete
strict gate on Paper 1.21.1-133: 348 probes, 329 passed and 19 expected
rejections, with zero incomplete. `endermanCondition` accepts a real Enderman
and rejects a managed Zombie through the official `isEnderman` condition.
The full strict report also includes the two-client menu/Audience, raw Potion,
and player-movement workflows. Report and compressed logs are listed in
`release-evidence.json`; plugin SHA-256:
`d575844f655ae2812475c18dd550d4e47aa7f235ed73dd974a8153ac23f51478`.

Fixture `srv-paper-cinematic-condition-20261105-r1` passed the complete strict
gate on Paper 1.21.1-133: 347 probes, 328 passed and 19 expected rejections,
with zero incomplete. `isInCinematicCondition` verifies an active cinematic
for a player inside its 16-block audience, rejects an out-of-range player and
a non-player, and clears when the cinematic stops. The full strict report also
includes the two-client menu/Audience and raw Potion client workflows. Report
and compressed logs are listed in `release-evidence.json`; plugin SHA-256:
`d575844f655ae2812475c18dd550d4e47aa7f235ed73dd974a8153ac23f51478`.

Fixture `srv-paper-spawning-timing-20261105-r3` passed the complete strict gate
on Paper 1.21.1-133: 346 probes, 327 passed and 19 expected rejections, with
zero incomplete. `spawningDistanceClientWalk` records 174.12 blocks of player
displacement, 176.84 blocks between player and mob, and removal of both the
managed instance and Bukkit entity 2 ticks after the first outside-range
sample; the configured AI decision period is 10 ticks. `insideOutsideCondition`
also verifies positive and negative `inside`/`outside` results and confirms the
original block type and BlockData are restored. The broader Conditions catalog
remains open. Report and compressed server/client logs are listed in
`release-evidence.json`; plugin SHA-256:
`842a3dfd96d10e23c844652ed0fe05736ed3c10d2996d258d81bbd6c870ca4c9`.

Previous full strict fixture `srv-paper-snowing-strict-20261105-r2` passed
345 probes (326 passed, 19 expected rejections, zero incomplete).
`snowingCondition` verifies target-location isolation, both aliases, warm and
clear rejection, missing context, invalid arguments, and state restoration.
Report and compressed logs are listed in `release-evidence.json`; plugin
SHA-256: `1b6fe0a823faadc268149f90df680f3550bab8a2059f41ac1477fa992fd31a44`.
The complete Conditions catalog remains open.

Earlier fixture `srv-paper-in-combat-strict-20261105-r3` passed the complete strict
gate on Paper 1.21.1-133: 344 probes, 325 passed and 19 expected rejections,
with zero incomplete. The independent `inCombatCondition` probe verifies the
`in-combat` condition against the managed caster and a native Mob: positive
target, no-target rejection, and target restoration all passed on both paths.
`conditionUtilityAliases` retains aggregate condition coverage. The Paper
self-test used the managed caster's Bukkit `Mob#setTarget` fallback;
controller-specific state is covered by unit tests. The same full strict report
requires `spawningDistanceClientWalk`, which observes real-player movement
beyond range and removal of both the managed instance and Bukkit entity. Report
and compressed server/client logs are listed in `release-evidence.json`;
plugin SHA-256:
`5a5df5c3a02a33f6b0f7fdbc9b4071f04df93613e3c05d1aba98bb290dc86548`.

Previous fixture `srv-paper-in-combat-strict-20261105-r2` passed 342 probes
(323 passed, 19 expected rejections, zero incomplete) with these runtime paths
included in the aggregate `conditionUtilityAliases` probe.

Fixture `srv-paper-is-sheared-strict-20261105-r1` passed the complete strict
gate on Paper 1.21.1-133: 342 probes, 323 passed and 19 expected rejections,
with zero incomplete. `entityShearedStates` verifies `is-sheared` and
`target-is-sheared` against real Sheep, Bogged, and Snow Golems in positive and
negative states. Unsupported entities and missing context fail closed. The
report and compressed server/client logs are listed in `release-evidence.json`;
plugin SHA-256: `cef3fa45f41ff612dbcd89a9aa0fe8224a07702f4a71dd84aad40a5c323713d1`.

Fixture `srv-paper-angry-pollination-strict-20261105-r1` passed the complete
strict gate on Paper 1.21.1-133: 341 probes, 322 passed and 19 expected
rejections, with zero incomplete. `entityAngryPollination` verifies `angry`
for Bees, Wolves and Vexes, `pollinated` for Bees, the documented aliases, and
fail-closed behavior for unsupported entities, invalid arguments and missing
context. The report and compressed logs are listed in `release-evidence.json`;
plugin SHA-256: `1ccbd718c489383e4099daba6ad06446ff7b51c2e3181e3a8df2c4895b47df15`.

Previous fixture `srv-paper-targeter-relations-strict-20261105-r3` passed the complete
strict gate on Paper 1.21.1-133: 340 probes, 321 passed and 19 expected
rejections, with zero incomplete. `targeterIdentityFilter` verifies owner and
same-faction inclusion/exclusion, `ignore=owner|faction`, relation precedence
over generic target categories, `targetall` interaction, and fail-closed
rejection of unsupported relation syntax. It also retains managed identity,
`targetvanilla`, and `targetnonmythic` coverage. Exact same-faction matching
uses Helstera's faction service; managed identity only applies to mobs spawned
from Helstera definitions. External MythicMobs/Citizens identity and premium
filters remain unsupported. Report and compressed server/client logs are
listed in `release-evidence.json`; plugin SHA-256:
`686cc99d64b3edbcfe6d2653d0672b4b188ae275ab15b4cec4c021f6111f5f71`.

Fixture `srv-paper-target-all-filter-strict-20261105-r1` passed the complete
strict gate on Paper 1.21.1-133: 339 probes, 320 passed and 19 expected
rejections, with zero incomplete. `targeterArgumentValidation` verifies
`targetall=true` overrides conflicting `target`, `ignore`, and specific
category filters within the existing candidate set. It retains real-entity
coverage for animals, creatures, villagers, ArmorStands, invulnerability,
player-mode switches, ground, water, flying, and a flying Phantom. API tests
cover malformed and duplicate options, unknown filters, and `targetall=false`.
The override does not widen a targeter's radius or world scope. Non-living
display/interaction entities, NPC, Mythic identity, and premium filters remain
outside this implementation. Report and compressed server/client logs are
listed in `release-evidence.json`; plugin SHA-256:
`1a30a22c1274f045782daff576ebeb1ed4089cd963879d5a831602ef04cf1bf4`.

Previous fixture `srv-paper-creature-target-filter-strict-20261105-r1` verifies
the same strict controls before `targetall` was added and remains archived.

Previous fixture `srv-paper-target-armorstands-strict-20261105-r1` verifies
ArmorStand inclusion/exclusion, aliases, and invulnerability and remains
archived.

Previous fixture `srv-paper-game-mode-target-filter-strict-20261104-r1` verifies
the earlier generic game-mode filters and remains archived.

Previous strict fixture `srv-paper-flight-target-filter-strict-20261104-r1`
passed the same 339-probe gate and established real ground, water, and flying
inclusion/exclusion, including a flying Phantom. Its report and logs remain in
`release-evidence.json` as historical evidence.

Fixture `srv-paper-self-eye-location-strict-20261104-r1` passed the complete
strict gate on Paper 1.21.1-133: 339 probes, 320 passed and 19 expected
rejections. `locationTargeterDispatch` verifies real block-action dispatch for
SelfLocation and all five SelfEyeLocation aliases, caster and world spawn
locations, explicit target and trigger locations, `@Forward`, `@ProjectileForward`
on an Arrow, and `@TargetPredictedLocation` using target velocity. Invalid
coordinates are rejected. Report and logs are listed in `release-evidence.json`.

Fixture `srv-paper-tier-range-20261104-r1` passed the complete strict gate on
Paper 1.21.1-133: 339 probes, 320 passed and 19 expected rejections.
`dropNativeTierRange` checks invalid list and indexed tier bounds are reported,
valid tier siblings remain usable, and null indexed bounds use the documented
base-amount fallback. Report and logs are listed in `release-evidence.json`.

Fixture `srv-paper-named-reference-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 338 probes, 319 passed and 19 expected rejections.
`namedSkillReference` verifies a mixed-case forward reference heals a real
entity, an unknown reference fails binding and a cyclic guard blocks healing.
Report and logs are listed in `release-evidence.json`.

Fixture `srv-paper-named-reload-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 337 probes, 318 passed and 19 expected rejections.
`namedSkillReload` checks direct skill action and negated condition behavior
against a real entity after deletion and reintroduction of the skill definition.
Report and logs are listed in `release-evidence.json`.

Fixture `srv-paper-named-condition-20261104-r1` passed the complete strict
gate on Paper 1.21.1-133: 336 probes, 317 passed and 19 expected rejections.
`namedConditionFailureClosed` checks throwing and unknown named skill
requirements block healing under negation on a real entity, while a normal
false requirement allows the negated control to heal. Report and logs are
listed in `release-evidence.json`.

Fixture `srv-paper-condition-failure-20261104-r1` passed the complete strict
gate on Paper 1.21.1-133: 335 probes, 316 passed and 19 expected rejections.
`conditionFailureClosed` checks throwing registered conditions under nested
negation block healing of a real entity, while a normal false-condition control
heals it. Report and logs are listed in `release-evidence.json`.

Fixture `srv-paper-orphan-suffix-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 334 probes, 315 passed and 19 expected rejections. The
`orphanSuffix` probe verifies standalone `?` and `@` are preserved with
warnings, rejected at binding and do not heal a real test entity. Report and
logs are listed in `release-evidence.json`.

Fixture `srv-paper-event-variants-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 333 probes, 314 passed and 19 expected rejections. The
`eventSuffixVariants` probe checks rejected uppercase, unknown and incomplete
event suffixes with diagnostics, and successful binding of quoted message text.
It does not test client delivery. Report and logs are in `release-evidence.json`.

Fixture `srv-paper-event-suffix-20261104-r1` passed the complete strict gate on
Paper 1.21.1-133: 332 probes, 313 passed and 19 expected rejections. The
`unroutedEventSuffix` probe checks direct binding rejects single unrouted and
duplicate event suffixes with diagnostics; it does not test natural events.
Report and logs are listed in `release-evidence.json`.

Fixture `srv-paper-invalid-chance-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 331 probes, 312 passed and 19 expected rejections. Invalid
chance suffixes `101%`, `NaN%`, and `1e999%` were preserved, warned and
rejected without affecting a real entity. Report and logs are listed in
`release-evidence.json`.

Fixture `srv-paper-suffix-grammar-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 331 probes, 312 passed and 19 expected rejections. Three
ambiguous suffixes were preserved, warned and rejected without affecting a real
entity; a positional placeholder and valid targeter chain remained mapped.
Report and logs are listed in `release-evidence.json`.

Fixture `srv-paper-positional-condition-20261104-r1` passed the complete strict
gate on Paper 1.21.1-133: 331 probes, 312 passed and 19 expected rejections.
Imported positional heal lines correctly evaluate true and false target
conditions, including a braced condition, and a braced targeter retains the
heal amount on a real entity. Report and logs are in `release-evidence.json`.

Fixture `srv-paper-positional-chance-20261104-r1` passed the complete strict
gate on Paper 1.21.1-133: 331 probes, 312 passed and 19 expected rejections.
Imported positional heal mechanics without targeters retained their chances:
0% did not heal a real entity and 100% healed it. Report and logs are listed
in `release-evidence.json`.

Fixture `srv-paper-mechanic-timing-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 331 probes, 312 passed and 19 expected rejections. Three
imported heal lines with invalid or duplicate timing options were preserved,
warned and did not affect a real entity. Report and logs are listed in
`release-evidence.json`.

Fixture `srv-paper-skill-duplicate-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 331 probes, 312 passed and 19 expected rejections. It
checks runtime rejection and diagnostics for duplicate delay, repeat and
interval options alongside the delayed nested-skill observations. Report and
logs are listed in `release-evidence.json`.

Fixture `srv-paper-skill-timing-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 331 probes, 312 passed and 19 expected rejections. An
imported nested skill was observed before its delay, after its first execution
and after its repeat. Two invalid timing options were preserved with warnings
and did not execute. Report and logs are listed in `release-evidence.json`.

Fixture `srv-paper-skill-reference-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 330 probes, 311 passed and 19 expected rejections. A
valid imported nested skill changed a real entity; three invalid references
were preserved with warnings and did not change the entity. Report and logs
are listed in `release-evidence.json`.

Fixture `srv-paper-message-escape-20261104-r2` passed the complete strict gate
on Paper 1.21.1-133: 329 probes, 310 passed and 19 expected rejections. A real
client received an imported message with escaped quotes, a backslash, embedded
delimiters and a `@trigger` targeter exactly. Report and logs are listed in
`release-evidence.json`.

Fixture `srv-paper-message-comma-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 328 probes, 309 passed and 19 expected rejections. A real
client received an imported message containing commas, a key-like expression,
and a semicolon exactly. The report and logs are listed in `release-evidence.json`.

Fixture `srv-paper-drop-list-comma-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 327 probes, 308 passed and 19 expected rejections. It
imports a two-item list followed by `,n=false`, observes both exact-location
drops, and preserves an invalid list flag. The report and logs are listed in
`release-evidence.json`.

Fixture `srv-paper-drop-list-20261104-r1` passed the complete strict gate on
Paper 1.21.1-133: 327 probes, 308 passed and 19 expected rejections. It imports a
two-item list, observes both item types and counts at the selected location, and
checks invalid-list preservation. The report and logs are in `release-evidence.json`.

Fixture `srv-paper-drop-inline-20261104-r2` passed the complete strict gate on
Paper 1.21.1-133: 326 probes, 307 passed and 19 expected rejections. It imports
inline fixed and skill-variable item counts, observes both drops, and verifies
missing-variable rejection and invalid inline-count preservation. Its report
and logs are listed in `release-evidence.json`.

Fixture `srv-paper-drop-natural-20261104-r1` passed the complete strict gate on
Paper 1.21.1-133: 325 probes, 306 passed and 19 expected rejections. It imports
`i=DIAMOND;n=false`, observes an exact-location item spawn, and checks invalid
natural flag preservation and warning. Its report and logs are in
`release-evidence.json`.

Fixture `srv-paper-drop-import-20261104-r1` passed the complete strict gate
on Paper 1.21.1-133: 324 probes, 305 passed and 19 expected rejections. Its
imported Mythic skill produced a real item at the requested location, rejected a
missing variable and preserved invalid amount and unknown arguments with warnings.
The report and compressed logs are listed in `release-evidence.json`.

Fixture `srv-paper-drop-location-20261104-r2` passed the complete strict gate
on Paper 1.21.1-133: 323 probes, 304 passed and 19 expected rejections.
Its item spawn listener confirmed the selected location, material and count,
and no spawn for a zero count or missing location variable. The report and
compressed logs are listed in `release-evidence.json`. Import-to-runtime
execution was not covered by this earlier Paper probe.

Fixture `srv-paper-explosion-location-20261104-r3` completed the existing
strict run on Paper 1.21.1-133. Its report was regenerated from the original
logs after registering `explosionLocationDispatch`: 322 probes, 303 passed,
19 expected rejections. The server event listener observed the requested
coordinates and no explosion for a missing variable, and cancelled the event.
The report and compressed server and client logs are listed in
`release-evidence.json`; imported explosion execution is unit-tested only.

Spawner-state counter type and nested structure validation was included in the
state-validation fixture below. Unit tests cover backup fallback and unrecoverable invalid
counters; those failure cases do not have Paper evidence yet.

Strict attempt `srv-paper-state-guard-strict-20261103-r1` failed because
`spawningDistanceRuntime` observed no online player when its probe started;
all other report entries reached their expected status. The client connected
later. The probe now waits up to 1,200 server ticks for a suitable player; a timeout
still fails the report. This attempt is not release evidence.

Source changes prevent state saves after an unrecoverable state load
or unsupported schema version until a valid state is loaded. Unit tests cover
these cases; the state-validation strict fixture below includes this change but does not exercise the
failure cases. Natural and forced spawning pause until valid state is loaded;
focused Paper probes below cover both paths.

Focused two-boot fixture `srv-paper-state-natural-repair-20261103-r2` used an
enabled independent spawner capped at one spawn. With both state files corrupt,
the real scheduler left its count at zero; after repair and console reload it
reached one and persisted one at shutdown. Logs, state files and configuration:
`reports/paper-1.21.1-state-natural-repair-20261103-r2-logs.tar.gz` (SHA-256
`62815f3307d3feb21bf9cbcb94c2f41ff60d42561a021dad4526b1ae9b74fdde`).
Report: `reports/paper-1.21.1-state-natural-repair-20261103-r2.json` (SHA-256
`862a927d15fd5d7b4539965295379c57f971a8909d85f199d9ccfa8501a30cf7`).
The earlier `srv-paper-state-natural-repair-20261103-r1` observed paused count
zero but failed because asynchronous chat markers appeared out of order; it did
not run the repair phase. These focused runs are not full strict fixtures.

Focused two-boot fixture `srv-paper-state-corrupt-both-20261103-r1` corrupted
both files after the first shutdown. The second boot logged the pause, rejected
the console force command with zero spawns, and skipped state saving; both file
hashes were unchanged after shutdown. Logs and state files:
`reports/paper-1.21.1-state-corrupt-both-20261103-r1-logs.tar.gz`
(SHA-256 `f7e54048e02e5fd0511bd242402e80b5b449a759f7f79b02436f0af395f2031a`).
Second-boot report: `reports/paper-1.21.1-state-corrupt-both-20261103-r1.json`
(SHA-256 `fc7b96fb6a02bcbfd982aaa91b5b6ba5bb1ce473636a33268818a254cef784bb`).
This focused run does not itself establish natural scheduling or repair behavior.

Focused fixture `srv-paper-state-repair-20261103-r2` corrupted both state files,
observed the forced spawn rejected, restored the first boot's valid state and
reloaded it. After loading the target chunk, the server restored count 1, accepted
one forced spawn, and saved count 2 at shutdown. Its two boot logs and state
files are archived in `reports/paper-1.21.1-state-repair-20261103-r2-logs.tar.gz`
(SHA-256 `4ac0e91b1c0b8b6f5cd452d38c402d184376935deef082dfae2538dbf6896d9b`);
the second-boot report is `reports/paper-1.21.1-state-repair-20261103-r2.json`
(SHA-256 `52e2f8cb1700c6815d00236c26c668495217063c5ad7fa4a9c001a4ccffff048`).
The earlier `srv-paper-state-repair-20261103-r1` restored count 1 but its forced
spawn failed before the target chunk was explicitly loaded. The precise failure
reason was not recorded. Neither
run proves natural scheduling during the pause or after recovery.

Historical strict fixture `srv-paper-state-validation-strict-20261103-r2` passed all 310
probes (291 passed, 19 expected rejections). Report:
`reports/paper-1.21.1-state-validation-strict-20261103-r2.json` (SHA-256
`7613be1618a5048cb52dc7f6b451201b09dd9edc23fe91c70e67c59c4a7cf485`). The
report uses plugin SHA-256
`cccd4197127a0759da3cf849e01dbc3de1a43724e5f2e7ec74d61fd1214c2836` and
includes the persisted-state structure validation available at that time.

Focused two-boot fixture `srv-paper-state-missing-primary-20261103-r1` removed
the primary state after the first shutdown. The second boot restored counter 1
from the backup; shutdown wrote it to the primary while preserving backup
SHA-256 `3ce9dbd1259f2eda6aa95ac702e58d1def045cb617fa15d37b9c9f9b224abc02`.
Both logs and state files are archived in
`reports/paper-1.21.1-state-missing-primary-20261103-r1-logs.tar.gz` (SHA-256
`3f94274cd01c10abf8e06fb3e376e2ffdd1410881a967e191ab3e14ec0dc9667`).
The second-boot report is `reports/paper-1.21.1-state-missing-primary-20261103-r1.json`
(SHA-256 `68a3f66aef3affe75c4d5178194a660efe0f75f04b982fcdf20632620aa70442`).
This focused run does not exercise the unrecoverable-state pause.

Previous strict fixture `srv-paper-state-guard-strict-20261103-r2` passed 310
probes (291 passed, 19 expected rejections). The distance probe observed
`inside-retained=true outside-removed=true`. Report:
`reports/paper-1.21.1-state-guard-strict-20261103-r2.json` (SHA-256
`71fa9426722b2950e41e0d211d9a5c4e30fdc978267cfb9d272dc87584968b72`).

Previous strict fixture `srv-paper-backup-save-strict-20261103-r2` passed 310
probes (291 passed, 19 expected rejections) on the build that preserves a valid
spawner-state backup after corrupt-primary recovery. Unit tests cover recovery
and subsequent backup rotation. Report: `reports/paper-1.21.1-backup-save-strict-20261103-r2.json`
(SHA-256 `a29384dbc10535092114f0edfb7ee52ab757145731f957976bd9b5006e0afe46`).

Focused fixture `srv-paper-spawner-backup-save-20261103-r1` performed two real
Paper 1.21.1-133 boots using the same plugin SHA-256 as the preceding
`srv-paper-backup-save-strict-20261103-r2` run.
After corrupting the primary state, the second boot restored the counter to 1;
shutdown wrote that counter back to the primary while preserving the backup
SHA-256 `8e0989e8a96f5c65df11c518e00713af3683dece713a9af6aa386f78135a92ac`.
Both boot logs and resulting state files are archived in
`reports/paper-1.21.1-spawner-backup-save-20261103-r1-logs.tar.gz` (SHA-256
`8c08f611a86ca665d244a0e85d86aae377dbf065689d2da13e0970ee75aae9d2`).
The second-boot report is `reports/paper-1.21.1-spawner-backup-save-20261103-r1.json`
(SHA-256 `e6c7d91474527c7bc34a5d45e1492da7ade136e4083781bd246ca5f8a07d9dd1`).
This focused run is not a full strict release fixture; interruption during the
write and subsequent backup rotation remain outside its scope.

Previous strict fixture `srv-paper-web-strict-20261103-r1` passed 310 probes
(291 passed, 19 expected rejections) on Paper 1.21.1-133. The HTTP spawner
reload probe rejected invalid YAML and recovered after repair on the server
thread. Browser rendering and the loot failure path were not tested. The
report is `reports/paper-1.21.1-web-strict-20261103-r1.json` (SHA-256
`2cbc29b66617b0eeb36d6be13a196661be6b1b144d1b72a1ef4d491c113413fe`);
the server and client logs and their hashes are in `release-evidence.json`.

Follow-up focused fixture `srv-paper-web-reload-20261103-r3` repeated the HTTP
failure and repair checks and recorded `serverThread=true`; the archived log
also passes the tightened same-line server-thread assertion. Its result is
`reports/paper-1.21.1-web-reload-20261103-r3.json` (SHA-256
`f7e4d74e9c9cf99f3683b17dc92ed103486772377b02666c911fd3d9a1a6f8d5`),
and its log is `reports/paper-1.21.1-web-reload-20261103-r3.log.gz` (SHA-256
`64634ac5c781a93cab05206a98646aa4f8939ac9163b45d8f302a4b86df2d319`).
This focused fixture is not part of the strict release catalog.

Independent HTTP fixture `srv-paper-web-reload-20261103-r2` on Paper 1.21.1-133
observed `invalidOk=false`, then `restoredOk=true` and 43 loaded spawners after
repairing the YAML. Both server log entries identify `Server thread`. It does
not test browser rendering or the loot failure path and is not a strict run.
The plugin SHA-256 was `82b509e07d06497e7169647b9b3185423b534c93fae976ea3eb58c408a27e735`.
Archived observations: `reports/paper-1.21.1-web-reload-20261103-r2.json`
(SHA-256 `3cb08b5d97349de6e869a58dd5734b099137b9f799c9bc6e23edfbb5c4921774`),
server log: `reports/paper-1.21.1-web-reload-20261103-r2.log.gz`
(SHA-256 `9b10978eabd1e805a2c755b0df75ce35d6e2920de53743415c091a4eda0af89a`).

Independent two-boot fixture `srv-paper-spawner-corrupt-restart-20261103-r1`
on Paper 1.21.1-133 generated one spawn, replaced the primary state with invalid
YAML, logged `state load failed; trying backup`, and restored the count with
`before=1 after=1 restored=true result=true`. This is not a full strict run.
The plugin SHA-256 was `cef7cc0bfc7779baa2eab9e3789ab4ff5714dd4867adbe80c9352b16a97be066`;
both boot logs are archived in
`reports/paper-1.21.1-spawner-corrupt-restart-20261103-r1-logs.tar.gz`
(SHA-256 `f409cfc44318b60faae6fc8cc9180fd2846cff856866ecd6a7b86f3746af37eb`).

Historical strict release fixture `srv-paper-spawner-null-20261103-r2` uses Paper
`1.21.1-133` and completes 309 probes (290 passed, 19 expected rejections).
Forced spawns reject direct and inherited null spawner conditions and accept an
empty-list override. Natural scheduling and imported spawners are not covered by
these probes. Report: `reports/paper-1.21.1-spawner-null-20261103-r2.json`.

Earlier strict release fixture `srv-paper-merged-null-20261103-r1` uses Paper
`1.21.1-133` and completes 306 probes (289 passed, 17 expected rejections).
Its `dropMergedNull` server-side importer and runtime probe verifies a cross-file
inherited null condition is rejected, the parent warns, a stale same-name table
is not rolled, and a valid sibling survives. It is not a natural-death test of
invalid merged conditions. Report: `reports/paper-1.21.1-merged-null-20261103-r1.json`.

Earlier strict release fixture `srv-paper-native-numeric-20261103-r1` uses Paper
`1.21.1-133` and completes 305 probes (288 passed, 17 expected rejections).
Its `dropNativeNullNumeric` service probe checks native YAML null chance for
items and XP, null tier weight fallback, explicit overrides and warnings. It is
not a natural-death test for invalid values. Report:
`reports/paper-1.21.1-native-numeric-20261103-r1.json`.

Earlier strict release fixture `srv-paper-native-null-20261103-r1` uses Paper
`1.21.1-133` and completes 304 probes (287 passed, 17 expected rejections).
Its `dropNativeNullConditions` server-side service probe loads a native YAML file,
rejects inherited null conditions for items and experience, and rolls a valid
override. It is not a natural-death test for invalid conditions. Report:
`reports/paper-1.21.1-native-null-20261103-r1.json`.

Earlier strict release fixture `srv-paper-unsafe-drop-references-20261103-r1` uses
Paper `1.21.1-133` and completes 303 probes (286 passed, 17 expected rejections).
Its server-side `dropUnsafeReferences` probe verifies warnings and rejects stale
same-name tables for null, unknown and indirect empty source references while a
valid sibling rolls. It is not a natural-death test for rejected references.
Report: `reports/paper-1.21.1-unsafe-drop-references-20261103-r1.json`.

Earlier strict release fixture `srv-paper-null-drop-conditions-20261103-r1` uses
Paper `1.21.1-133` and completes 302 probes (285 passed, 17 expected rejections).
Its `dropNullConditions` probe checks importer warnings, rejects two tables with
explicit null conditions, and rolls a valid sibling through the runtime service.
It does not perform a natural death with an invalid table. Report:
`reports/paper-1.21.1-null-drop-conditions-20261103-r1.json`.

Earlier strict release fixture `srv-paper-nested-import-strict-20261102-r1` uses
Paper `1.21.1-133` and completes 291 probes (274 passed, 17 expected rejections).
The imported parent table is in a subdirectory and references a separate child
by bare name. Natural Zombie death produces one Diamond, one Gold Ingot from
exclusive `1-3`, and 5 XP. Report: `reports/paper-1.21.1-nested-import-20261102.json`.

Earlier strict release fixture `srv-paper-skill-lifecycle-20261102-r1` uses Paper
`1.21.1-133` and completes 291 probes (274 passed, 17 expected rejections).
Its `skillLifecycleCleanup` probe uses an isolated skill service and a managed
Zombie to check reload and delayed cancellation by directly calling `forget`.
Its report is `reports/paper-1.21.1-skill-lifecycle-20261102.json`.
A HIGH-priority self-test listener cancels real lethal damage; 60 ticks later the
mob remains attached and alive at 8 health with no diamond drop observed. Its
report for the earlier run is `reports/paper-1.21.1-damaged-import-20261102.json`. The listener is part
of the self-test, so actual third-party plugin interoperability remains unverified.
The imported damage probe converts both aliases and checks their actions on a
real entity attacker; a separate no-attacker damage call reduces victim health
without selecting the victim as `@Trigger`. Both calls are initiated by the
self-test and do not establish natural combat AI or projectile semantics.

Earlier strict release fixture `srv-paper-imported-drop-contract-strict-20261102-r1`
uses Paper `1.21.1-133` and completes 284 probes (267 passed, 17 expected
negative controls, zero incomplete). The imported DropTable contract verifies
condition mapping, conflicting table preservation, and natural managed-Zombie
death output of one Diamond, one Gold Ingot from exclusive `1-3`, and 5 XP.
The input JAR SHA-256 is recorded in
`reports/paper-1.21.1-imported-drop-contract-20261102.json`.

Earlier strict release fixture `srv-paper-named-conditions-strict-20261102-r1`
uses Paper `1.21.1-133` and completes 284 probes (267 passed, 17 expected
negative controls, zero incomplete). Safe-location spawner probes accept a
valid negated world name and reject blank world, invalid moon-phase, and invalid
biome arguments with `conditions rejected location`. The input JAR SHA-256 is
recorded in `reports/paper-1.21.1-named-conditions-20261102.json`.

Earlier strict release fixture `srv-paper-negated-conditions-strict-20261102-r1`
uses Paper `1.21.1-133` and completes 280 probes (266 passed, 14 expected
negative controls, zero incomplete). Safe-location spawner probes accept
`!y-above=300` and reject `!y-above=NaN` and `!light-level!=NaN` as
`conditions rejected location`. The report and tested plugin SHA-256 are in
`tools/paper-integration/reports/paper-1.21.1-negated-conditions-20261102.json`.

Earlier strict release fixture `srv-paper-plugin-hash-strict-20261102-r1`
uses Paper `1.21.1-133` and completes 277 probes (265 passed, 12 expected
negative controls, zero incomplete). The tested input JAR SHA-256 is recorded in
`tools/paper-integration/reports/paper-1.21.1-plugin-hash-20261102.json` and
the release manifest. It does not identify the source commit or remapped JAR.

Earlier strict release fixture `srv-paper-threat-provider-strict-20261102-r1`
uses Paper `1.21.1-133` and completes 277 probes (265 passed, 12 expected
negative controls, zero incomplete). Its threat probes use synthetic damage
events with real entities and controllers to verify sorting, faction filters,
and threat actions after startup. Restart and overlapping managers are covered
by unit tests, not this fixture. The report is
`tools/paper-integration/reports/paper-1.21.1-threat-provider-20261102.json`.

Earlier strict release fixture `srv-paper-context-chain-strict-20261102-r7`
uses Paper `1.21.1-133` and completes 277 probes (265 passed, 12 expected
negative controls, zero incomplete). Its `contextTargeterChain` probe checks
target intersection, trigger inclusion/exclusion, and the production condition
provider on real Zombie entities. The report is
`tools/paper-integration/reports/paper-1.21.1-context-chain-20261102.json`.

Earlier strict release fixture `srv-paper-context-targeters-strict-20261102-r1`
uses Paper `1.21.1-133` and completes 276 probes (264 passed, 12 expected
negative controls, zero incomplete). Its `contextTargeterControls` probe uses
real Zombie entities to check ordering, radius, and invalid parameter rejection.
The report is `tools/paper-integration/reports/paper-1.21.1-context-targeters-20261102.json`.

Earlier strict release fixture `srv-paper-full-catalog-strict-20261102-r2`
uses Paper `1.21.1-133` and completes 275 probes (263 passed, 12 expected
negative controls, zero incomplete). Real player Potion consumption and
natural expiry both passed, including the expiry event and skill action. The
fractional Bonus sample recorded 69 extra lines in 128 natural deaths. The
report is `tools/paper-integration/reports/paper-1.21.1-full-catalog-20261102.json`.

Earlier archived strict follow-up: `srv-paper-fractional-bonus-strict-20261102-r4`
uses Paper `1.21.1-133` and completes 273 registered probes (261 passed,
12 expected negative controls). Its natural-death Bonus probe records 63
extra lines in 128 level-one samples with a 0.5 multiplier. This run did not
request the raw Potion client; the earlier full Potion evidence is separate.
The report is `tools/paper-integration/reports/paper-1.21.1-fractional-bonus-20261102.json`.

Previous archived strict release report: fixture
`srv-paper-drops-imported-range-strict-20261102-r5` uses Paper
`1.21.1-133` / API `1.21.1-R0.1-SNAPSHOT` and completes all 273 registered
probes (262 passed, 11 expected negative controls, zero incomplete). The
`deathLootImportedDropTable` probe verifies standalone `DropTables/` import,
runtime loading, and a real player-caused managed-Zombie death producing one
Diamond, two Gold Ingots from the imported exclusive `1-3` range, and 5 XP.
The report is archived at
`tools/paper-integration/reports/paper-1.21.1-drops-imported-range-20261102.json`.

Latest strict targeter follow-up: `srv-paper-targeter-sorting-strict-20261102-r1`
uses Paper `1.21.1-133` / API `1.21.1-R0.1-SNAPSHOT` and completes all 272
registered probes (260 passed, 12 expected negative controls, zero
incomplete). Its `targeterSorts` probe verifies highest-health and oldest-first
ordering against live Bukkit Zombies, and rejects an unknown sort mode. API
tests additionally cover lowest-health, newest, threat ordering, missing threat
provider, non-finite threat data, and conditional-target skip/limit ordering.
The archived report passes schema and
strict completeness gates:
`tools/paper-integration/reports/paper-1.21.1-targeter-sorting-20261102.json`.

Latest strict follow-up: `srv-paper-targeter-skip-index-strict-20261102-r3`
uses Paper `1.21.1-133` / API `1.21.1-R0.1-SNAPSHOT` and completes all 271
registered probes (259 passed, 12 expected negative controls, zero
incomplete). Its targeter observation verifies nearest-order skipping via
`skipTargetsUpToIndex` and alias `stuti`, with skip applied after ordering and
before limit. The archived report passes schema and strict completeness gates:
`tools/paper-integration/reports/paper-1.21.1-targeter-skip-index-20261102.json`.

Previous strict targeter follow-up: `srv-paper-targeter-players-strict-20261102-r1`
uses the same Paper/API versions and completes all 272 registered probes (260
passed, 12 expected negative controls, zero incomplete). Its `ai-selector-core`
observation verifies production `@PlayersOnServer` selection and `@everyone`
alias equivalence. Its archived report is
`tools/paper-integration/reports/paper-1.21.1-targeter-world-players-20261102.json`.

Previous full strict baseline run: `srv-paper-pin-conditions-strict-20261102-r1`
uses Paper `1.21.1-133` / API `1.21.1-R0.1-SNAPSHOT` and completes all 270
registered probes (258 passed, 12 expected negative controls, zero incomplete).
Its required `pinConditions` probe verifies target and origin distance
comparators inside and outside their thresholds, rejection of a missing pin,
and inclusive region membership. `pinTargeters` verifies multi-point and
random `@Pin` dispatch, single-point `MovePin` updates, multi-point movement
rejection, and real inclusive-cuboid block set/break with state restoration.
It rejects a 16,385-block region before downstream action dispatch. The report also
includes `player_gate_fail` rejected with
`failure=player gate closed`, real-player Potion consumption and natural
expiry, and the menu/Audience workflow. The Mineflayer client directly observed
the REDSTONE and EMERALD buttons, denied and allowed clicks, pagination,
command feedback, and inventory close. The strict report and schema gates
passed.

Archived machine-readable report:
`tools/paper-integration/reports/paper-1.21.1-pin-conditions-20261102.json`.

Previous full strict run: `srv-paper-pin-targeters-strict-20261102-r1`
completed 265 probes (253 passed, 12 expected negative controls, zero
incomplete) before the oversized-region negative assertion was added.

Earlier full strict run: `srv-paper-menu-client-strict-20261102-r2`
completed 264 probes (252 passed, 12 expected negative controls, zero
incomplete) before Pin evidence became a required report entry.

The earlier dedicated strict client-walk fixture
`srv-paper-spawning-distance-client-walk-strict-20261102-r3` remains archived;
the current full strict release also requires `spawningDistanceClientWalk` for
real player movement beyond the configured SpawningDistance and managed/Bukkit
entity removal.

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

Focused Paper 1.21.1-133 fixture `srv-paper-skilltree-uuid-restart-20261107-r4`
passes two boots with two persistent PIGs sharing `skilltree_probe`. Boot one
writes separate `entity-<uuid>.yml` snapshots. Boot two restores both entity-
specific markers, the `restart-foundation` unlock and the `restart-advanced`
prerequisite, then unlocks the dependent branch independently for each entity.
The probe kills one restored PIG through Bukkit damage and confirms its UUID
snapshot is deleted while the other remains. Shutdown saves only the survivor;
the fixture verifies the dead entity's snapshot stays absent and exactly one
entity snapshot remains. The [captured server log](reports/paper-1.21.1-skilltree-uuid-restart-20261107-r4.log.gz)
has SHA-256 `634bad1a1d844c98f890631df39dcd2875ff5af76bee9f95a4a3cb49b3c98970`.
This is focused lifecycle evidence, not a complete SkillTree parity result.

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
exploratory Paper 1.21.11 evidence. The later strict Paper 1.21.1 fixture
`srv-paper-cinematic-condition-20261105-r1` independently verifies audience
range, non-player rejection, and the stopped-cinematic state.

Fixture `srv-paper-integration-exploratory-1.21.11-r106` adds official
`HasDialogOpen` coverage and fixes dialogue hook ordering. A temporary
cinematic starts for the managed model instance, `hasDialogOpen` passes while
it is active, and `has-dialog-open` rejects after `stop`; the structured log
records `dialogue-condition-details started=true open=true closed=true
problems=0`, and the `skill-actions` line records `dialogue-condition=true`.
Both aggregate probes passed. This is exploratory Paper 1.21.11 evidence; the
later strict Paper 1.21.1 fixture
`srv-paper-cinematic-condition-20261105-r1` also records the dialogue
start/open/stop/closed transitions.

Fixture `srv-paper-integration-exploratory-1.21.11-r104` adds official
`HasEnchantment` coverage. A temporary Sharpness III diamond sword on the real
managed Zombie passes `hasEnchantment{e=SHARPNESS;l=>2}` and
`hasEnchant{e=ANY;l=3}`, while `SHARPNESS >3` and `LOOTING` reject. The
structured log records
`enchantment-condition-details positive=true any=true level-rejected=true
type-rejected=true`, and the `skill-actions` line records
`enchantment-condition=true`. Both aggregate probes passed. This is
exploratory Paper 1.21.11 evidence.

Strict Paper 1.21.1-133 fixture
`srv-paper-has-enchantment-import-strict-20261105-r1` adds importer-to-runtime
coverage. The required `importedHasEnchantmentCondition` probe runs
`?hasEnchantment{e=SHARPNESS;l=>2}` and the `?hasEnchant{e=ANY;l=3}` alias on
the real managed Zombie, rejects Sharpness above level 3 and missing Looting,
and confirms the original main-hand item is restored.

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
that Pig target. It verified the runtime relation evaluator only. The later
strict fixture `srv-paper-is-child-import-strict-20261105-r1` verifies the
corrected `?isChild` importer mapping through `SkillService` on real Paper
entities.

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

This exploratory result is superseded by strict Paper fixture
`srv-paper-integration-exp-20261031-r27`, which keeps a real Mineflayer client
inside an active Shield-use window and records `stateBlocking=passed` on Paper
1.21.1-133. The strict report includes all registered probes and no incomplete
evidence.

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
reported `control-in-water=false` and no Drowned entity. Synthetic Bee attack
event stinger restoration, Creeper fuse timing, mount-event cancellation, and
natural potion expiry continued to behave as before. The conversion result remains `not-observed`
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
player-limit=1`, plus natural potion expiry, synthetic Bee attack-event stinger restoration, and the
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
probe after adding internal PotionEffect-backed `target-has-auras`,
`target-holding`, and `target-wearing` checks. The aggregate
`target-state-conditions=true` passed with a real online player and still
failed closed for the no-target context; this remains exploratory Paper
1.21.11 evidence and does not verify Mythic Aura parity.

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
managed `preventTransformation` Zombie remained valid; the probe manually set
both Bees to stung and dispatched synthetic Bukkit attack events, after which
the protected Bee's stinger state was restored. This does not establish natural
Bee AI attack behavior. The protected/control mount-event
pair recorded cancellation versus allowance. The probe keeps native Zombie AI
enabled so the conversion path is actually ticked, and pins the entities in
place without suppressing that behavior. This remains exploratory Paper
1.21.11 evidence; the later strict Paper 1.21.1-133 r4 fixture supplies the
native AI jockey evidence for that release target.

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

The strict Paper 1.21.1-133 fixture
`srv-paper-drowning-strict-20261102-r2` also requires and passes
`conditionUtilityAliases`. Its production self-test evaluates
`mobs-in-chunk >=1` against the active chunk containing a valid Bukkit Mob.
This establishes the positive current-chunk count path; zero-count and
cross-chunk boundary outcomes are not part of this probe.

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
restoring the original freeze ticks. Strict Paper 1.21.1-133 fixture
`srv-paper-runtime-alias-20261031-r2` records `frozenCondition=passed` in its
integration report. Exploratory Paper 1.21.4 r123 is additional historical
evidence for the same condition.

Fixture `srv-paper-integration-exploratory-1.21.11-r75` extends the target
category probe with `IsRaiderPatrolLeader`. A real Pillager was marked as a
patrol leader through Bukkit and the structured `targetCategories` probe
remained passed. This is exploratory Paper 1.21.11 evidence; the Paper 1.21.1
release target remains open.

Fixture `srv-paper-integration-exploratory-1.21.11-r76` records an earlier
exploratory `spyglassCondition=passed`. The current strict Paper 1.21.1-133
fixture `srv-paper-is-spyglass-import-strict-20261106-r1` verifies imported
`IsUsingSpyglass` and `IsScoping` against a real online Player actively using a
temporary SPYGLASS, then rejects the inactive state, a Zombie target, and
malformed arguments. The strict report and its logs are listed in
`release-evidence.json`.

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

Strict Paper 1.21.1-133 fixture
`srv-paper-ender-dragon-alive-strict-20261102-r1` records the dedicated
`enderDragonAliveCondition` as passed: a real dragon is accepted in the target
world, then removed and rejected, with cleanup confirmed. Its release report
contains 263 probes (252 passed, 11 expected negative controls, zero
incomplete).

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
the internal Bukkit PotionEffect evaluator `has-aura-stacks SPEED >=1` passes
and the structured log records `aura-stacks-condition=true`. Exploratory
fixture r8 records the independent `auraStacksCondition` probe passed; this
does not verify Mythic Aura parity.

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
`itemRechargingCondition=passed` after a real Bukkit attack. Strict fixture
`srv-paper-item-recharging-import-strict-20261106-r1` separately records the
imported primary and alias forms as passed, plus the non-HumanEntity rejection.

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
`entityItemSimilarCondition=passed`; strict fixture
`srv-paper-item-similarity-import-strict-20261109-r2` records the imported
primary and alias mappings, positive/negative behavior, and invalid-argument
preservation in `importedItemSimilarityConditions`.

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

Strict fixture `srv-paper-ender-dragon-phase-strict-20261101-r1` verifies the
production `EnderDragonPhase` condition on Paper 1.21.1-133. The dedicated
report probe passed a real `CIRCLING` match, a phase list, a negative match,
fail-closed rejection of `CIRCLING,INVALID`, and restoration of the dragon's
original phase. The aggregate report has one unrelated incomplete probe:
`spawnerBorderClipped=not-observed`; therefore this run is condition evidence,
not a complete strict release-gate pass.

Strict follow-up `srv-paper-ender-dragon-phase-strict-20261101-r3` passes the
Paper 1.21.1-133 schema and completeness gates with all 260 probes observed:
248 passed, 12 expected negative controls, and zero incomplete. The required
`enderDragonPhaseCondition` probe records a real `CIRCLING` match, list match,
negative match, rejection of `CIRCLING,INVALID`, and phase restoration. The
world-border clipping positive and outside-negative probes both pass; the
positive natural-location sample records its bounded attempt count. The
`lookingAtCondition` probe also passes both real-player ray-trace branches.
The final report is retained at
`srv-paper-ender-dragon-phase-strict-20261101-r3/integration-report.json`.

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
`targetsCondition=passed` probes; strict fixture
`srv-paper-item-similarity-import-strict-20261109-r2` records the imported
`ItemIsSimilar` aliases and invalid-argument preservation.

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

The frozen-state self-test reaches the real Zombie's frozen state through the
`Freeze` mechanic before evaluating `IsFrozen` and restoring the original
freeze ticks. Strict Paper 1.21.1-133 fixture
`srv-paper-runtime-alias-20261031-r2` records the independent
`frozenCondition=passed` probe; exploratory fixture
`srv-paper-integration-exploratory-1.21.4-r123` provides additional evidence.

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
probes. The run includes the synthetic Bee stinger event and jockey mount-event probes
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

Historical fixture `srv-paper-integration-final-r23` passed the 222-probe
Paper 1.21.1-133 gate with 213 `passed`, 9 expected `observed-rejected`, and
zero incomplete probes. Its events line records the additional
`prevent-block-explosion-damage=true` assertion, produced by a real
`EntityDamageEvent` with `DamageCause.BLOCK_EXPLOSION` against a managed
protected entity. The expected-rejected count reflects the current spawner
negative-control observations; no probe failed or remained incomplete.

Historical fixture `srv-paper-integration-final-r25` passed the 222-probe
Paper 1.21.1-133 gate with 213 `passed`, 9 expected `observed-rejected`, and
zero incomplete probes. The events line records
`prevent-drowning=true prevent-fall-damage=true prevent-fire=true`, each from a
separate `EntityDamageEvent` carrying its matching `DamageCause` and a managed
entity configured with the corresponding production option.

Historical fixture `srv-paper-integration-final-r26` passed the 222-probe
Paper 1.21.1-133 gate with 213 `passed`, 9 expected `observed-rejected`, and
zero incomplete probes. Its `PreventFireCauses=FIRE_TICK` check records
`FIRE_TICK` cancelled while ordinary `FIRE` remains uncancelled, proving the
configured cause filter is narrower than the global `PreventFire` option.

Historical fixture `srv-paper-integration-final-r28` passed the 222-probe
Paper 1.21.1-133 gate with 214 `passed`, 8 expected `observed-rejected`, and
zero incomplete probes. Cause isolation is verified with one option enabled
at a time: `PreventDrowning` cancels `DROWNING` but allows `FALL`, and
`PreventFallDamage` cancels `FALL` but allows `DROWNING`. The separate
`PreventFire` and `PreventFireCauses=FIRE_TICK` positive/negative assertions
also pass. The intervening r27 run missed an unrelated player-facing
`LookingAt` positive observation; r28 passed it without changing the gate.

Historical fixture `srv-paper-integration-final-r30` passed with 222 observed
probes: 213 `passed`, 9 expected `observed-rejected`, and zero incomplete. Its
events evidence adds positive/negative controls for `PreventPickup` and
`PreventItemUse`: managed protected mobs reject their corresponding pickup or
right-click interaction events, while otherwise equivalent managed controls
allow them. The r29 run had transient missing `LookingAt` and vehicle event
observations; r30 observed both, with no gate changes.

Historical fixture `srv-paper-integration-final-r31` passed the 222-probe
Paper 1.21.1-133 gate with 213 `passed`, 9 expected `observed-rejected`, and
zero incomplete probes. `mob-options-mount-event` records
`prevented=true allowed=true generic-prevented=true generic-allowed=true`:
the jockey-specific Zombie restriction and generic `PreventMount` on a Wolf
are independently enforced, while both control mount events remain allowed.

Historical fixture `srv-paper-integration-final-r32` passed the 222-probe
Paper 1.21.1-133 gate with 213 `passed`, 9 expected `observed-rejected`, and
zero incomplete probes. The events line now also records
`prevent-teleport=true` using paired managed entities: the protected
`EntityTeleportEvent` is cancelled and the default control event is allowed.

Historical fixture `srv-paper-integration-final-r34` passed with 222 observed
probes: 213 `passed`, 9 expected `observed-rejected`, and zero incomplete.
Its events line confirms `prevent-transformation-reasons=true` and its
cause-isolated environmental damage checks. The real-player `LookingAt` probe
passed positive and negative checks after the fixture began snapshotting,
clearing, and restoring only blocks along the ray, removing dependence on
ambient terrain without weakening the ray assertion.

Historical fixture `srv-paper-integration-final-r35` passed with 222 observed
probes: 214 `passed`, 8 expected `observed-rejected`, and zero incomplete.
Its managed entity `PreventSunburn` check confirms the base sunlight
`EntityCombustEvent` is cancelled while `EntityCombustByBlockEvent` and
`EntityCombustByEntityEvent` remain uncancelled, preserving non-sunlight fire
semantics.

Strict Paper 1.21.1-133 fixture `srv-paper-prevent-sunburn-strict-20261107-r4`
passed all 388 release probes (369 passed, 19 expected rejections, zero failed
or incomplete). The imported protected Zombie received two native sunlight
combustion events, both cancelled with no fire (`getFireTicks() == -1`); its
inverse-control Zombie received one uncancelled event and retained 132 fire
ticks. Both entities advanced 40 ticks within 14 blocks of an online player.
The probe restored world time/weather and removed its temporary Mob files and
entities. Report and matching artifact hashes are recorded in
`tools/paper-integration/release-evidence.json`.

Historical fixture `srv-paper-integration-final-r36` passed with 222 observed
probes: 214 `passed`, 8 expected `observed-rejected`, and zero incomplete.
Its `PreventOtherDrops` event assertion uses fixed MUTTON x2 and 7 XP values:
the protected managed entity's `EntityDeathEvent` is cleared, while the
otherwise equivalent managed control retains both drops and experience. This
exercises the production death listener before optional loot-table processing.

Historical fixture `srv-paper-integration-final-r37` passed with 222 observed
probes: 213 `passed`, 9 expected `observed-rejected`, and zero incomplete. Its
killer-drop probe derives a managed killer from a real damage source before
dispatching a fixed-drop/XP `EntityDeathEvent`; only the victim of the killer
with `PreventMobKillDrops` loses drops and XP, while the ordinary managed
killer control retains both.

Historical fixture `srv-paper-integration-final-r38` passed with 223 observed
probes: 215 `passed`, 8 expected `observed-rejected`, and zero incomplete.
Its `spawn-option-runtime` probe uses real Skeleton/Zombie entities and the
production spawn-option helpers: `CanPickupItems=false` is applied, explicit
`true` overrides the `PreventItemPickup` fallback, `PreventRandomEquipment`
clears pre-existing helmet/weapon gear before explicit DIAMOND_SWORD equipment
is applied, and configured `NoDamageTicks=19` is read back.

Historical fixture `srv-paper-integration-final-r41` passed with 223 observed
probes: 214 `passed`, 9 expected `observed-rejected`, and zero incomplete. Its
killer-drop probe resolves managed killers from actual victim damage. The
protected killer suppresses native victim drops and the victim's configured
`integration_death` table, while the default killer control materializes
Diamond x2 and 9 XP. This confirms `PreventMobKillDrops` covers the target's
complete loot result, not just vanilla drops.

Historical fixture `srv-paper-integration-final-r42` passed with 223 observed
probes: 214 `passed`, 9 expected `observed-rejected`, and zero incomplete. The
events probe records `prevent-block-infection=true` on real
`EntityChangeBlockEvent` dispatch: only the managed Silverfish changing a
block to `INFESTED_STONE` is cancelled; the default Silverfish and protected
Pig control remain allowed.

Historical fixture `srv-paper-integration-final-r43` passed with 223 observed
probes: 214 `passed`, 9 expected `observed-rejected`, and zero incomplete. The
events probe records `prevent-snow-formation=true` from three real
`EntityBlockFormEvent` dispatches: the protected Snow Golem is cancelled, while
the default Snow Golem and a protected Pig control are allowed.

Historical fixture `srv-paper-integration-final-r44` passed with 223 observed
probes: 214 `passed`, 9 expected `observed-rejected`, and zero incomplete.
The events probe records `prevent-skeleton-conversion=true`: a managed
Skeleton with only `FROZEN` in its transformation-reason list rejects FROZEN
while allowing CURED. Importer regression maps `PreventConversion` by family
and retains the option as unsupported for non-Zombie/non-Skeleton types.

Historical fixture `srv-paper-integration-final-r47` passed with 224 observed
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

Historical strict fixture
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
and Bukkit `EntityDeathEvent` reads back the configured experience override.
The fixture uses `mobs/exp_probe.yml`; fixed and ranged `drops.experience*`
precedence and missing-table fallback are covered by unit tests. This root
`Exp` probe only establishes the mob-level override path; the separate natural
DropTable path is recorded below.

Historical strict fixture `srv-paper-integration-exp-20261031-r16` passed
Paper 1.21.1-133 with 240 observed probes: 230 `passed`, 10 expected
`observed-rejected`, and zero incomplete. Its current-code vehicle line is:

```text
vehicle-events enter=true exit=true dismount=true eject=true destroy=true player=HelsteraTester
```

The vehicle workflow now uses an independent managed ArmorStand passenger, so
the online player's unrelated death/respawn probes cannot remove the binding
before the delayed Boat event sequence runs.

Strict fixture `srv-paper-exp-natural-death-20261101-r3` passed on Paper
1.21.1-133 with 255 observed probes: 243 passed, 12 expected negative
controls, and zero incomplete. It retains the synthetic event check and adds
a separate real-damage death check:

```text
exp-options result=true experience=13 event=true
exp-natural-death result=true dead=true experience=13 cause=BukkitDamage
```

The natural-death probe spawns a second configured managed Zombie, records
existing experience-orb UUIDs, calls Bukkit `LivingEntity#damage`, confirms
the entity died, and totals only newly spawned orbs within four blocks. It
removes those probe orbs afterward. This verifies root `Exp` through the
production death listener and Paper's normal experience-orb spawn path.

Strict fixture `srv-paper-drops-natural-death-20261101-r4` passed on Paper
1.21.1-133 with 255 observed probes: 243 passed, 12 expected negative
controls, and zero incomplete. Its `death-loot` probe now requires and records
the natural death path explicitly:

```text
death-loot natural=true dead=true cause=BukkitDamage items=1 conditional-drop=true rejected-drop-absent=true dynamic-chance-drop=true experience=9
```

The self-test binds a production model instance to a Zombie, assigns the
`integration_death` table, snapshots existing Item and ExperienceOrb UUIDs,
and applies Bukkit damage. It counts only new entities within six blocks,
observes the condition-matched Diamond stack, confirms the rejected Bread
entry is absent, and reads 9 XP from the configured 9–11 range. Cleanup removes
only entities created during this probe. This proves this case of natural
DropTable death; looting interaction and broader Mythic drop placeholders
remain unverified.

Strict fixture `srv-paper-drops-trigger-conditions-20261101-r5` passed on
Paper 1.21.1-133 with 258 observed probes: 247 passed, 11 expected negative
controls, and zero incomplete. It adds natural player-caused deaths for
DropTable trigger conditions, including nested-table and experience behavior:

```text
death-loot-trigger natural=true killer-player=true nested-allowed-drop=true nested-rejected-drop-absent=true nested-rejected-experience=4 parent-rejected-drop-absent=true parent-rejected-experience=0
```

The first table passes its player condition and a nested world condition, so
its Diamond appears. The second table passes the parent player condition but
fails the nested world condition; its Diamond is absent while its parent's 4
XP remains. The third table fails its top-level world condition; both its
Diamond and configured 5 XP are suppressed. These are real Bukkit damage and
death-hook paths with player killer context, not synthetic event dispatch.
The same report retains passing menu/Audience and player Potion consume/natural
expiry probes. This r5 run predates the real permission-context assertion below.

Strict follow-up fixture `srv-paper-drops-trigger-conditions-20261101-r7`
passed Paper 1.21.1-133 with all 258 probes observed: 246 `passed`, 12
expected `observed-rejected`, and zero incomplete. The permission probe runs
after the menu client's temporary permission-denial check restores the test
player's operator status. It records the real allow and deny cases:

```text
death-loot-trigger natural=true killer-player=true permission-allowed=true permission-denied=true nested-allowed-drop=true nested-rejected-drop-absent=true nested-rejected-experience=4 parent-rejected-drop-absent=true parent-rejected-experience=0
```

The accepted nested table uses `has-permission loot.vip`. The OP killer
produces its Diamond; the non-OP killer produces none. The accepted parent
still grants 4 XP when its child rejects the killer. A separate top-level
world rejection suppresses both its Diamond and 5 configured XP. The schema
validator and strict report-completeness gate passed.

Historical strict fixture `srv-paper-integration-exp-20261031-r17` passed
Paper 1.21.1-133 with 240 observed probes: 229 `passed`, 11 expected
`observed-rejected`, and zero incomplete. It adds the configuration-to-instance
Mob Option line:

```text
despawn-options result=true mode=PERSISTENT distance=0.0 persistent=true
```

This reads the normalized `SpawnOptions` mode/distance and the real Bukkit
entity persistence flag from `mobs/despawn_probe.yml`.

Historical strict fixture `srv-paper-integration-exp-20261031-r19` passed
Paper 1.21.1-133 with 241 observed probes: 231 `passed`, 10 expected
`observed-rejected`, and zero incomplete. It adds the visibility option line:

```text
visibility-options result=true visible-by-default=true invisible=true show-name=true
```

This reads `VisibleByDefault`, `Invisible`, and `AlwaysShowName` from a real
configured Zombie through the production spawn path.

Historical strict fixture `srv-paper-integration-exp-20261031-r20` passed
Paper 1.21.1-133 with 244 observed probes: 233 `passed`, 11 expected
`observed-rejected`, and zero incomplete. It adds the Spawner group-limit line:

```text
spawner-group id=group_probe requested=true alive=2 total=2 max-alive=2 group-min=3 group-max=5 limit=true
```

This proves the production group spawn path stops at `max-alive` instead of
materializing the full requested group.

Historical strict fixture `srv-paper-integration-exp-20261031-r21` passed
Paper 1.21.1-133 with 245 observed probes: 235 `passed`, 10 expected
`observed-rejected`, and zero incomplete. Its live scheduler group line is:

```text
spawner-natural-group id=natural_group_probe mode=live-scheduler before=2 after=4 alive=2 max-alive=2 group-min=3 group-max=5 limit=true
```

This is separate from the manual `forceSpawn` group probe and proves the
interval-driven scheduler path applies the same per-attempt alive cap.

Historical strict fixture `srv-paper-integration-exp-20261031-r23` passed
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

Historical strict fixture `srv-paper-integration-exp-20261031-r27` passed
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
condition evidence and r28 was the overall release fixture at that time.

Historical strict fixture `srv-paper-integration-exp-20261031-r28` passed
Paper 1.21.1-133 with 250 observed probes: 239 `passed`, 11 expected
`observed-rejected`, and zero incomplete. It adds the natural Spawner recycle
probe:

```text
spawner-recycle id=recycle_probe before=2 after=3 released=true respawned=true alive=1 interval=40 passed=true
```

The probe removes a real tracked instance, observes the runtime cleanup release
the `max-alive=1` slot, and then observes the shared scheduler create the next
instance before disabling and cleaning the fixture probe.

Historical strict fixture `srv-paper-integration-targeters-20261031-r3`
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

Follow-up two-boot fixture `srv-paper-spawner-natural-restart-20261106-r1`
passed on Paper 1.21.1-133 using the same production scheduler on both boots.
Boot one naturally generated one `persistence_probe`, then saved 580 of its
600 cooldown ticks during normal shutdown. Boot two restored exactly 580 ticks
and naturally generated the second instance after 579 observed server ticks;
the fixture sent no manual force command. The final state recorded
`total-spawned: 2` and a fresh 600-tick cooldown.

Report: [paper-1.21.1-spawner-natural-restart-20261106-r1.json](reports/paper-1.21.1-spawner-natural-restart-20261106-r1.json), SHA-256:
`4af78ee39e598fe73ca2cb142059eae40bae2692bf5ff895da458cb087c55990`.
First-boot log SHA-256:
`17701090b6fed65fe22999350eec2d411d0395bd3c3573bbb71d234bf37e8da9`.
Second-boot log SHA-256:
`aab6ed9b45025dc2fe649b7632d4d279af34e4884d4e689fcb585942e7784914`.

Independent follow-up fixture `srv-paper-spawner-natural-restart-20261106-r2`
passed on Paper 1.21.1-133 after tightening the timing assertion to one
scheduler period. It again saved and restored 580 of 600 cooldown ticks and
naturally spawned after 579 observed ticks, without issuing a force command.
Report: [paper-1.21.1-spawner-natural-restart-20261106-r2.json](reports/paper-1.21.1-spawner-natural-restart-20261106-r2.json), SHA-256:
`4d6a1b3618b1ec27061d14f5b19d91807db36508c5f56baac2c6e6242431ab78`.
First-boot log SHA-256:
`0bd93e70ada1c0bcc7fec28d1549db88fcbeb8d4ef71bbb363b49175a734e913`.
Second-boot log SHA-256:
`c2ec0cd7428eb990bbac9759be362cf0897ec6b6e0af212423435c303ac5cd81`.
Plugin SHA-256: `1a720c987563702610542daf9cefbe8372c6793ba3718227edadf6ef3ae56026`.

Independent follow-up fixture `srv-paper-spawner-natural-restart-20261106-r4`
passed on Paper 1.21.1-133. It observed `Chance: 1` naturally spawn on both
boots and `Chance: 0` remain at zero. Shutdown saved 580 of 600 cooldown ticks;
the second boot restored 580 ticks and naturally spawned after 579 observed
ticks, with no manual force command.

Report: [paper-1.21.1-spawner-natural-restart-20261106-r4.json](reports/paper-1.21.1-spawner-natural-restart-20261106-r4.json), SHA-256:
`afc78251cdb4bcf9fc5ceba5e3985d57978a46234adcf8e88190b4072beff700`.
First-boot log SHA-256:
`f72b5ccdf878950cdee1972992f0d1e46a6c92309129716932f13b3282147e95`.
Second-boot log SHA-256:
`c7a7b32bb3cc5655459b170d2bcc882a2f91684255c4d6ee86da03b94619991b`.
Plugin SHA-256: `9b567ff5096e7b0bade71d782dee75e57d0f74566dd87cc5a9ea2169f65adbfa`.

Latest natural Spawner fixture `srv-paper-spawner-natural-restart-20261106-r5`
passed on Paper 1.21.1-133 using the current built plugin JAR on both boots.
It observes natural generation for `Chance: 1`, zero generations for
`Chance: 0`, and a warning plus zero generations for the non-finite explicit
value `Chance: "Infinity"`. Shutdown saved 580 of 600 cooldown ticks; boot two
restored 580 and naturally spawned after 579 observed ticks without a force
command.

Report: [paper-1.21.1-spawner-natural-restart-20261106-r5.json](reports/paper-1.21.1-spawner-natural-restart-20261106-r5.json), SHA-256:
`9ba540ddb755c89ea8df6381d1193a7310ce45157e63f481d55c19615e099691`.
First-boot log SHA-256:
`cf8845122711b2d8bfe96a221b33c7875e4fbeba76411ffc86e034145f83d2c8`.
Second-boot log SHA-256:
`5aa56229823dea6fd60dda724e9752042b431d2f81e04498d376e18a5a474336`.
Plugin SHA-256: `f588b8e2126e2439192337517a4e5e6ba85e8abf95ee4cb5622df9c1c9be5f4e`.

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

Historical strict fixture `srv-paper-runtime-alias-20261031-r2` passed Paper
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

Strict fixtures `srv-paper-spawning-distance-runtime-20261101-r4` and
`srv-paper-spawning-distance-runtime-20261101-r5` passed Paper 1.21.1-133 with
254 observed probes (242 passed, 12 expected rejected, zero incomplete). The
latest r5 `spawningDistanceRuntime` probe uses a real online player:
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

Strict fixture `srv-paper-biome-condition-strict-20261101-r1` passed Paper
1.21.1-133 with 254 observed probes (242 passed, 12 expected rejected, zero
incomplete). Its dedicated `biomeCondition` probe checks the actual target
biome through the production `SkillService`: the namespaced exact key and a
comma-separated alternative list match; a strict substring does not match in
exact mode; the same substring matches with `exact=false`; and a key with a
deliberate suffix mismatch is rejected. The structured server log records
`biome-exact=true biome-partial=true biome-reject=true`, and both
`biomeCondition` and `conditionUtilityAliases` pass in the report at
`srv-paper-biome-condition-strict-20261101-r1/integration-report.json`.

Strict fixture `srv-paper-biome-type-category-strict-20261101-r1` passed Paper
1.21.1-133 with 254 observed probes (243 passed, 11 expected negative
controls rejected, zero incomplete). Its `biomeCondition` release probe checks
non-exact `BiomeType` category matching against a real `DESERT` biome:
`desert` matches, `plains` rejects, and comma-separated alternatives match
when one category matches. The probe also checks exact biome matching,
negative exact/category cases, missing location context, and restores the
original biome. The report requires
`spatial-condition-details .*biome-type-category=true`; both `biomeCondition`
and `conditionUtilityAliases` pass in
`srv-paper-biome-type-category-strict-20261101-r1/integration-report.json`.

Strict fixture `srv-paper-raider-patrol-condition-strict-20261101-r1` passed
Paper 1.21.1-133 with all 259 probes observed: 247 `passed`, 12 expected
`observed-rejected`, and zero incomplete. Its required
`raiderPatrolLeaderCondition` probe runs the official `IsRaiderPatrolLeader`
condition through the production `SkillService` against a real Pillager. It
records both the leader-positive and non-leader-negative paths, restores the
Pillager's leader flag, and logs:

```text
raider-patrol-leader-condition positive=true negative=true entity=PILLAGER restored=true
```

The report schema and strict completeness gate passed. The report is retained
at `srv-paper-raider-patrol-condition-strict-20261101-r1/integration-report.json`.

Strict fixture `srv-paper-creeper-primed-condition-strict-20261101-r1` passed
Paper 1.21.1-133 with 259 observed probes: 247 `passed`, 12 expected
`observed-rejected`, and zero incomplete. Its required
`creeperPrimedCondition` probe evaluates official `IsCreeperPrimed` through the
production `SkillService` against a real Creeper, checks ignited and
non-ignited states, then restores the ignited state. The server records:

```text
creeper-primed-condition positive=true negative=true restored=true
```

The report schema and strict completeness gate passed. The report is retained
at `srv-paper-creeper-primed-condition-strict-20261101-r1/integration-report.json`.

Strict fixture `srv-paper-chance-condition-strict-20261101-r1` passed Paper
1.21.1-133 with all 259 probes observed: 247 passed, 12 expected
`observed-rejected` negative controls, and zero incomplete. Its required
`chanceCondition` probe uses the production `SkillService` and verifies
`Chance:100` passes, `Chance:0` rejects, and malformed `Chance:101` rejects
fail-closed with a diagnostic. The structured log records:

```text
condition-utility-details ... chance-100=true chance-0-rejected=true chance-invalid-rejected=true
```

The same release report also passes the natural DropTable trigger-condition
probe, including real OP/non-OP permission outcomes for a nested table. The
report schema and strict completeness gate passed and the report is retained
at `srv-paper-chance-condition-strict-20261101-r1/integration-report.json`.

Strict fixture `srv-paper-damage-tag-strict-20261102-r3` passed Paper
1.21.1-133 with 261 observed probes: 249 passed, 12 expected
`observed-rejected` negative controls, and zero incomplete. Its
`damageTagCondition` probe sends an untagged event first and confirms the
configured `on-damage` action does not run. It then invokes the production
`damage-target 3 tags=FIRE` action against the managed trigger probe. The real
Bukkit event carries `FIRE`, passes the cause and final-damage conditions,
executes the configured action, is cancelled, and leaves the victim health at
20.0. The structured self-test line records:

```text
skill-trigger damage=true ... bukkit-damage=false damage-cause-condition=true damage-amount-condition=true damage-tag-condition=true tagged-damage-action=true tagged-damage-events=1 tagged-damage-cancelled=true tagged-damage-health=20.0>20.0 ... cancelled-condition=true ... cancelled-after=true
```

The run also exposed and fixed event routing when multiple managed instances
share a Bukkit entity: damage and attacked dispatch now independently prefer a
binding whose profile declares that trigger. The report schema and strict
completeness gate passed; the report is retained at
`srv-paper-damage-tag-strict-20261102-r3/integration-report.json`.

Strict Conditions follow-up `srv-paper-drowning-strict-20261102-r2` passed on
Paper 1.21.1-133 with 262 observed probes: 250 passed, 12 expected
`observed-rejected` negative controls, and zero incomplete. The required
`conditionCatalog` observation includes `drowning-condition=true`; the
production self-test sets caster remaining air to 80, accepts `drowning <=100`,
rejects `drowning >100`, and restores the original value. Its report is retained
at `srv-paper-drowning-strict-20261102-r2/integration-report.json`.
The same report requires and passes `lastDamageCauseCondition`; its observation
is `last-damage-cause=true`, backed by the registered Paper self-test that
accepts the stored FIRE cause and rejects ENTITY_ATTACK.
It also requires and passes `foodSaturationCondition`; the production player
probe sets saturation to 1.0 and 2.0 to verify both comparator outcomes, then
restores the original value.

Strict follow-up `srv-paper-mobs-in-chunk-boundary-strict-20261102-r1` passed
on Paper 1.21.1-133 with 263 observed probes: 252 passed, 11 expected
`observed-rejected` negative controls, and zero incomplete. Its required
`mobsInChunkCondition` probe uses two loaded adjacent chunks, confirms both
start without valid Bukkit Mobs, spawns a controlled Mob only in one chunk, and
checks zero count in the other, positive count in the occupied chunk, and
exclusion of that adjacent Mob. It also rejects `mobs-in-chunk >=999999`.
The observation records `mobs-chunk-zero=true`,
`mobs-chunk-occupied=true`, and `mobs-chunk-adjacent-excluded=true`. The report
schema and release completeness gate passed; the report is retained at
`srv-paper-mobs-in-chunk-boundary-strict-20261102-r1/integration-report.json`.
The same strict report also passes the required `damageTagCondition` and
`conditionCatalog` entries, including the Drowning remaining-air comparator.

Strict Paper 1.21.1-133 fixture
`srv-paper-spawning-distance-client-walk-strict-20261102-r3` completes all 264
registered probes: 253 passed, 11 expected negative controls, and zero
incomplete. The client log records `PAPER_SPAWNING_DISTANCE_WALK
displacement=14.01`; the server independently records
`spawning-distance-client-walk result=true player-displacement=8.4687...`
and confirms `instance-removed=true entity-removed=true` after the configured
eight-block radius is crossed. The JSON schema validator and strict release
completeness gate pass. This standalone movement fixture is retained at
`srv-paper-spawning-distance-client-walk-strict-20261102-r3/integration-report.json`.
That aggregate report also passes `killTrigger`, `playerKillTrigger`, and
`playerKillsCondition`. The current strict imported-kill fixture supersedes
its trigger evidence: `killTrigger` uses an imported managed mob's real Bukkit
attack and victim death path. `playerKillTrigger` sets a controlled
`EntityDamageByEntityEvent` as the online player's `lastDamageCause`, then
sets health to zero so Bukkit emits the real player death events; the action
and per-instance `PlayerKills` positive/negative comparisons pass. This proves
death-event dispatch with controlled attribution, not a natural PvP damage
packet.

Strict Paper 1.21.1-133 fixture
`srv-paper-environmental-natural-strict-20261102-r1` completed all 269
registered probes: 257 passed, 12 expected negative controls, zero
incomplete. Its `environmentalDamage` probe places a production managed Zombie
in a temporary Lava block, clears prior fire and damage state, and waits for
Paper's own damage tick. The observation is:

```text
environmental-damage natural=true source=LAVA action-observed=true elapsed-ticks=16 lava=true passed=true
```

The action check reads back the configured main-hand DIAMOND_SWORD after the
managed `on-damage` skill runs. Cleanup restores the original block data and
despawns the probe instance. The report passed JSON schema validation and the
strict release completeness gate; the archived report is
`tools/paper-integration/reports/paper-1.21.1-environmental-natural-20261102.json`.

Strict Conditions follow-up `srv-paper-distance-from-spawn-strict-20261102-r1`
passed on Paper 1.21.1-133 with all 265 registered probes complete. Its
`distanceFromSpawnCondition` probe evaluates the production `SkillService`
against a real entity location and the world's spawn location. The inclusive
exact-distance comparison passes while the exclusive `> distance` comparison
rejects. The structured observation records
`spawn-distance-exact=true spawn-distance-over-rejected=true`; schema and strict
completeness checks pass. The archived report is
`tools/paper-integration/reports/paper-1.21.1-distance-from-spawn-20261102.json`.

Strict DropTable follow-up `srv-paper-drops-range-strict-20261102-r4` passed
on Paper 1.21.1-133 with all 265 registered probes complete and zero
incomplete. Its natural Bukkit death observation records
`exclusive-range-drop=true range-amount=2` for the fixture projection of the
Mythic inline entry `GOLD_INGOT 1-3 1`, whose exclusive upper bound yields
runtime amounts 1-2. The same run records the independent root experience
probe at 13 XP. The report is archived at
`tools/paper-integration/reports/paper-1.21.1-drops-exclusive-range-20261102.json`.
This verifies the tested amount-range path only; weighted line counts and Luck
and level bonuses have separate evidence, while strict Paper `BiasStat`
behavior and the full official DropTable catalog remain open.

Strict Paper 1.21.1-133 fixture
`srv-paper-drops-luck-attribute-strict-20261102-r2` passed all 266 registered
probes: 255 passed, 11 expected negative controls, zero incomplete. For the
natural player-kill DropTable probe, the self-test sets the killer's base
`GENERIC_LUCK` to 1.0, captures the effective value, applies Bukkit damage to
the managed Zombie, then restores the original attribute. The observation
records `effective-luck=1.0 luck-scaled-drop=true`; that IRON_INGOT entry has
base chance 0 and only passes the current scaler after the effective Luck
value is read. The JSON report is archived at
`tools/paper-integration/reports/paper-1.21.1-drops-luck-attribute-20261102.json`.
This verifies the current Luck input path. Separate strict fixture
`srv-paper-drops-bonus-semantics-strict-20261102-r1` verifies `BonusLuckItems`
with Bukkit Luck; neither fixture covers Mythic custom Luck enchantment/curses
inputs or strict Paper `BiasStat` behavior. The native parser, migration
projection, and injected runtime resolver are covered by unit and importer
tests.

Strict Paper 1.21.1-133 fixture
`srv-paper-drops-total-items-strict-20261102-r1` passed all 266 registered
probes: 254 passed, 12 expected negative controls, zero incomplete. Its
required `deathLootGeneratedLines` probe applies Bukkit damage to a managed
Zombie with `integration_total_items`, then measures only newly spawned Item
entities from that death. It observes exactly 128 items: 33 DIAMOND and 95
EMERALD by stack amount, within the configured 1:3 relative-weight bounds.
The schema validator and strict release completeness gate pass. The report is
archived at
`tools/paper-integration/reports/paper-1.21.1-drops-total-items-20261102.json`.
This establishes the tested `TotalItems` weighted path only. Separate strict
fixture `srv-paper-drops-bonus-semantics-strict-20261102-r1` verifies level/luck
line bonuses and single-bound weighted selection. Standalone Mythic DropTable
natural-death evidence is in fixture
`srv-paper-drops-imported-range-strict-20261102-r5`, which observes one Diamond,
two Gold Ingots from the imported exclusive range, and 5 XP. Its report is
archived at
`tools/paper-integration/reports/paper-1.21.1-drops-imported-range-20261102.json`.
`BiasStat` parsing, migration projection, and the injected weighted-table
resolver are implemented; strict Paper behavior, Mythic custom Luck
enchantment/curses inputs, and broader drop catalog semantics remain open. The
official DropTables catalog does not define a separate `Looting` table option.

Strict Paper 1.21.1-133 fixture
`srv-paper-drops-bonus-semantics-strict-20261102-r1` exercises natural
managed-Zombie deaths and reports `level-only=4`, `luck-only=3`, `combined=5`,
`zero=2`, and single-bound weighted selection via
`weighted-max=4 weighted-item-count=true`. Its archived report has 273 probes
(262 passed, 11 expected negative controls, zero incomplete). The earlier r4
report only verifies bonus line counts and is not evidence of weighted
selection. The report contract now requires the weighted fields. Fractional
bonus probability has focused unit coverage. Follow-up
`srv-paper-fractional-bonus-strict-20261102-r4` records 63 extra lines across
128 natural managed-Zombie deaths using a level-one 0.5 bonus. Its archived
273-probe report has 261 passed and 12 expected negative controls; raw Potion
consumption and natural expiry were not requested in this run. Imported
range/placeholder combinations, strict Paper `BiasStat` behavior, Mythic
custom Luck enchantment/curses inputs, and the broader official drop catalog
remain open. Native `BiasStat` parsing, migration projection, and the injected
runtime resolver are covered separately. The
importer-to-runtime parser contract is covered separately. The official
DropTables catalog does not define a separate `Looting` table option.
The bonus report is archived at
`tools/paper-integration/reports/paper-1.21.1-drops-bonus-semantics-20261102.json`.

Strict Paper 1.21.1-133 fixture
`srv-paper-dropper-conditions-strict-20261102-r1` completes 268 release probes
with zero incomplete. Its natural-death `deathLootDropperConditions` probe
binds two real Bukkit Zombies to separate DropTables and damages both through
Paper. The `is-monster` condition allows an Emerald and exactly 3 table XP;
`is-animal` rejects both the configured BREAD and its 7 table XP. Both deaths
retain the same real player killer, separating dropper context from killer
`TriggerConditions`. The report is archived at
`tools/paper-integration/reports/paper-1.21.1-dropper-conditions-20261102.json`.

Strict Paper 1.21.1-133 fixture
`srv-paper-exp-lines-strict-20261102-r2` completes 268 probes (256 passed,
12 expected negative controls, zero incomplete). Its natural `deathLootExperience`
probe configures an `exp` entry for 5 XP alongside table experience 9-11 and
observes 16 total XP from a real managed-Zombie Bukkit death. This confirms the
separate experience row is consumed by the production death hook. The archived
report is
`tools/paper-integration/reports/paper-1.21.1-exp-drop-lines-20261102.json`.

Strict Paper 1.21.1-133 fixture
`srv-paper-drops-imported-range-strict-20261102-r5` passes all 273 registered
probes: 262 passed, 11 expected negative controls, zero incomplete. The
`deathLootImportedDropTable` probe imports a standalone `DropTables/` file
containing killer `TriggerConditions`, dropper `Conditions`, a fixed Diamond,
an exclusive `GOLD_INGOT 1-3` range, and an `exp` line. Import projection and
runtime parsing both pass; a real player-caused managed-Zombie death observes
1 Diamond, 2 Gold Ingots (within the projected 1-2 range), and 5 XP, then
restores the prior runtime table set. The two-client menu workflow also ran in
this strict fixture. The report is archived at
`tools/paper-integration/reports/paper-1.21.1-drops-imported-range-20261102.json`.

Full strict Paper 1.21.1-133 fixture `srv-paper-integration` passed all 271
required probes: 259 passed, 12 expected negative controls, zero incomplete.
The required `survivalModeCondition` probe accepts Survival, rejects Adventure,
and records restoration of the player's original mode. The required
`enderDragonAliveCondition` probe accepts a real Ender Dragon, removes it, then
rejects the absent entity. The two-player menu client completed permission
denial, pagination, allowed action feedback, and close; Paper render/click logs
asserted the button materials and server outcomes because Mineflayer did not
decode the menu item contents reliably. The report passed schema and strict
completeness validation and is archived at
`tools/paper-integration/reports/paper-1.21.1-full-strict-20261102.json`.

Historical full strict run `srv-paper-current-20261103-r3` passed on Paper 1.21.1-133
with 275 passed probes and 17 expected rejections. The report and server/client
logs are archived in `tools/paper-integration/reports/` and recorded in the
release evidence manifest. Its plugin SHA-256 is
`c332a7a782a7e366d40b9033f49edb06b2793b6a5a708e5e9c484279173289a4`.
The catalog does not independently probe recent migration backup or failure handling.

Strict follow-up `srv-paper-pin-casing-strict-20261103-r1` passed all 310
required probes on Paper 1.21.1-133: 291 passed and 19 expected rejections.
The runtime self-check confirmed Pin targeters and conditions through mixed-case
`Pins`/`pins` sections and `World`/`X`/`Y`/`Z` fields, while invalid coordinates
and missing worlds remained rejected. The report is archived at
`tools/paper-integration/reports/paper-1.21.1-pin-casing-strict-20261103-r1.json`;
the plugin SHA-256 is
`d0650a0078e6e91be741a58b4af3cea8186e296ff64cfc931de825123b57aa65`.

Strict follow-up `srv-paper-location-targeters-strict-20261104-r1` passed 339
probes (320 passed, 19 expected rejections). `locationTargeterDispatch` verifies
real Paper block-action dispatch for SelfLocation aliases, caster and world
spawn locations, explicit target and trigger locations, `@Forward`,
`@ProjectileForward` on an Arrow, and `@TargetPredictedLocation` using target
velocity. Invalid coordinate input is rejected. Report:
`tools/paper-integration/reports/paper-1.21.1-location-targeters-strict-20261104-r1.json`;
server log SHA-256 `f17e7774605218e08521fec749e21a0da5953ab5d363c091645a310f2ab9f520`;
client log archive SHA-256
`efb184f20e405c6557cc650fd7535ef46bc4686bf37ee10b70cef9a086b7e9bf`. Plugin
SHA-256: `b23a9e5acd11f33281a25570a7918d1129b19041118088f23f8a9b09150c2cd9`.

Strict follow-up `srv-paper-variable-location-strict-20261104-r1` passed 321 probes
(302 passed, 19 expected rejections). `variableLocationDispatch` checks block
dispatch, duplicate-variable rejection and migration preservation with a warning.
Report: `tools/paper-integration/reports/paper-1.21.1-variable-location-strict-20261104-r1.json`.

Strict follow-up `srv-paper-location-dispatch-strict-20261104-r2` passed 320 probes
(301 passed, 19 expected rejections). `locationTargeterDispatch` verifies that
the real Paper block action receives an explicit location and that a duplicate
non-finite coordinate does not change the block. Report:
`tools/paper-integration/reports/paper-1.21.1-location-dispatch-strict-20261104-r2.json`.

Strict follow-up `srv-paper-targeter-arguments-strict-20261104-r1` passed 319 probes
(300 passed, 19 expected rejections). `targeterArgumentValidation` checks
malformed and ambiguous targeter controls, chain rejection and a valid control
using real Paper entities.
Report: `tools/paper-integration/reports/paper-1.21.1-targeter-arguments-strict-20261104-r1.json`.
Plugin SHA-256: `fa456690d3e3609721ff76c734402822ba52d1ea572fed629a167aa504f0d8ab`.

Strict follow-up `srv-paper-drop-tier-strict-20261104-r1` passed 318 probes
(299 passed, 19 expected rejections). `dropNativeTier` verifies invalid native
tier restrictions, inherited nulls and valid known-tier controls through file
loading and rolling; the probe does not exercise natural death.
Report: `tools/paper-integration/reports/paper-1.21.1-drop-tier-strict-20261104-r1.json`.
Plugin SHA-256: `18106d8b37195dd97091374b135d00fd76462740fc1203e1f5cfff73ac673969`.

Strict follow-up `srv-paper-drop-dynamic-strict-20261104-r1` passed 317 probes
(298 passed, 19 expected rejections). `dropNativeDynamic` verifies invalid native
dynamic chance expressions fail closed through file loading and runtime rolling,
including inherited nulls and explicit valid overrides; natural death is not tested.
Report: `tools/paper-integration/reports/paper-1.21.1-drop-dynamic-strict-20261104-r1.json`.
Plugin SHA-256: `880ec114287d3cc10ec1124770509002452c042e386bee74280c5fb0d59cd306`.

Strict follow-up `srv-paper-drop-numeric-strict-20261104-r1` passed 316 probes
(297 passed, 19 expected rejections). The imported drop chance probe also rejects
`0x1p-1` and `0.5d`, preserves their source in warnings and rolls the valid sibling.
Report: `tools/paper-integration/reports/paper-1.21.1-drop-numeric-strict-20261104-r1.json`.
Plugin SHA-256: `10a17a26162674114a2f6f0de22e3f4540843debf0294cb73cce6665865f1eff`.

Strict follow-up `srv-paper-dropper-orb-strict-20261104-r1` passed 316 probes
(297 passed, 19 expected rejections). The natural dropper-condition probe
observed event experience and immediate/delayed orb deltas of 3 for the
allowed branch and 0 for the rejected branch. The probe requires correct event
values and actual orbs observed at either sampling time; the earlier missed
observation's cause is still unconfirmed.
Report: `tools/paper-integration/reports/paper-1.21.1-dropper-orb-strict-20261104-r1.json`.
Plugin SHA-256: `7261cdfdebc9009019eda7e076fbc7b9f8ead09e70994460772f85ee8ef91fc7`.

Strict follow-up `srv-paper-drop-chance-strict-20261104-r1` passed 316 probes
(297 passed, 19 expected rejections). `dropChanceImported` verifies malformed
percentages are preserved in warnings and excluded from runtime rolling while
a valid sibling remains. Natural death is not covered by this probe.
Report: `tools/paper-integration/reports/paper-1.21.1-drop-chance-strict-20261104-r1.json`.
Plugin SHA-256: `5d4f0548381420a855a40e47519adb152256f09b9bb63ff31d3eca03cab55086`.
An earlier attempt observed zero instead of three experience points in the
unrelated natural dropper-condition probe; the cause remains unconfirmed.

Strict follow-up `srv-paper-environment-validation-strict-20261104-r1` passed 315 probes
(296 passed, 19 expected rejections), including mapped world, time and weather
conditions and rejection of invalid and negated invalid argument blocks.
Report: `tools/paper-integration/reports/paper-1.21.1-environment-validation-strict-20261104-r1.json`.
Plugin SHA-256: `a6b6e366cab29900970be7ad18bbb10a8bb4ba87a9de961b9d63696b67eaa6d8`.

Strict follow-up `srv-paper-target-health-strict-20261104-r1` passed 314 probes
(295 passed, 19 expected rejections), including target health percentage
multipliers, strict boundaries, absent target and invalid input preservation.
Report: `tools/paper-integration/reports/paper-1.21.1-target-health-strict-20261104-r1.json`.
Plugin SHA-256: `bbea66135c9eef740e3aaa49df6d4d08450321b4d592e980a5ca788cf9092dba`.

Strict follow-up `srv-paper-comparison-boundary-strict-20261104-r1` passed 313 probes
(294 passed, 19 expected rejections), including imported distance and light
strict/inclusive comparisons and invalid input preservation on real entities.
Report: `tools/paper-integration/reports/paper-1.21.1-comparison-boundary-strict-20261104-r1.json`.
Plugin SHA-256: `8a4804e2455c5b03b665014b3aed867d8868e2367c83578ff570cbb32f11af1f`.

Strict follow-up `srv-paper-health-range-strict-20261104-r1` passed 312 probes
(293 passed, 19 expected rejections), including imported health range endpoints,
absorption, percentages and invalid range preservation on a real Zombie.
Report: `tools/paper-integration/reports/paper-1.21.1-health-range-strict-20261104-r1.json`.
Plugin SHA-256: `6f5b825e45332f851ea008e701da0bed21b7a195e7f6d3006aa6545772bfce60`.

Strict follow-up `srv-paper-health-validation-strict-20261104-r1` passed 311 probes
(292 passed, 19 expected rejections) on the build with health argument validation.
Report: `tools/paper-integration/reports/paper-1.21.1-health-validation-strict-20261104-r1.json`.
The plugin SHA-256 is `a4b1b01227b8c4e193f4d13998f025a81d0ecb583581e0fb56cff8cb7b3dcbd2`.
Duplicate-argument and overflow rejection have unit tests but no dedicated Paper probe.

Strict follow-up `srv-paper-health-import-strict-20261104-r1` passed 311 probes
(292 passed, 19 expected rejections), including `healthImportedCondition` on a
real Zombie. Report: `tools/paper-integration/reports/paper-1.21.1-health-import-strict-20261104-r1.json`.
The plugin SHA-256 is `db9e56a4e5df62fe2a259d0630163f20734a59aee7825823f6176d1b7fbd4dc5`.

Strict follow-up `srv-paper-external-boundary-strict-20261103-r2` passed all
310 required probes on Paper 1.21.1-133: 291 passed and 19 expected
rejections. The current build registers explicit blocked adapters for MMOItems,
MythicCrucible, and ModelEngine; the strict catalog remained complete and no
core behavior was claimed for those uninstalled external plugins. The report
is archived at
`tools/paper-integration/reports/paper-1.21.1-external-boundary-strict-20261103-r2.json`;
the plugin SHA-256 is
`8b9e5a3d25e07e373b1fa96a872323ab5fa5ec02ae6b4926facee6f8e6cd9287`.

Previous full strict run `srv-paper-current-strict-20261103-r2` passed on Paper
1.21.1-133 with 275 passed probes and 17 expected rejections. The archived
report is `tools/paper-integration/reports/paper-1.21.1-current-strict-20261103-r2.json`;
its plugin SHA-256 is
`f9ed76327e1f36688b34c2fd7eaba0ce5ef0bcea0046920a9996ea3c3616f6e2`.
This reruns the existing release catalog and does not establish parity for
unregistered features or the recent migration failure-handling boundaries.
The original server log is archived as
`tools/paper-integration/reports/paper-1.21.1-current-strict-20261103-r2.log.gz`;
the release evidence manifest records its compressed-file SHA-256.
The corresponding client outputs are archived in
`tools/paper-integration/reports/paper-1.21.1-current-strict-20261103-r2-client-logs.tar.gz`
and checked by the same manifest audit. They do not constitute a complete
startup configuration snapshot; the server generated a token during startup.

Previous strict follow-up `srv-paper-condition-action-suffix-strict-20261106-r1` passed
all 362 required probes on Paper 1.21.1-133: 343 passed, 19 expected
rejections, and zero incomplete. The dedicated `importedBurningCondition`
probe used real managed and target Zombies. It verified translation of
`burning true`, `isburning false`, and `!burning false`; the target-negative
condition passed while only the caster was burning, positive and negative
target states behaved correctly, composed negation passed for a burning
target, and the caster was used when no target existed. An invalid
`burning maybe` suffix remained untranslated and warned. The report is
`tools/paper-integration/reports/paper-1.21.1-condition-action-suffix-strict-20261106-r1.json`;
the compressed server and client logs and their SHA-256 values are listed in
`tools/paper-integration/release-evidence.json`. The plugin SHA-256 is
`eb83955509fb4741fd2ced98b57706a1881542442f0a0107dd25781cef1ad454`.

Previous strict follow-up `srv-paper-entity-category-import-strict-20261106-r2`
passed all 363 required probes on Paper 1.21.1-133: 344 passed, 19 expected
rejections, and zero incomplete. Its `importedEntityCategoryCondition` probe
verified the primary and alias imports for `IsWaterMob` and `IsZombie` on a real
Cod/Zombie pair, both cross-type negatives, and preservation of malformed
arguments. The report is
`tools/paper-integration/reports/paper-1.21.1-entity-category-import-strict-20261106-r2.json`;
the compressed server and client logs and their SHA-256 values are listed in
`tools/paper-integration/release-evidence.json`. The plugin SHA-256 is
`94b1bfb7f5700e0fe0f69f505ade71fabc533599279c2961522ddad5c28ec1c8`.

