# Potion observer and reconnect compatibility

The [packaged YAML](../helstera-plugin/src/main/resources/probes/potion-sync-skills.yml)
runs through native MythicMobs 5.7.2 (Modrinth `FTWvGuE5`) and the production
Helstera importer/binder on Paper 1.21.1-133. Both target a real survival player
and a Cow through `@Trigger`, with a separate caster and an unaffected observer.
Two actual Minecraft connections record packets; the same usernames reconnect
with their existing UUIDs and new entity IDs. No effect is reapplied on join.

This slice covers Haste and Invisibility. It verifies protocol state, ordinary
observer tracking, and Paper player-data persistence across player reconnects.
It does not establish graphical rendering, every effect, passenger effect
packets, long-disconnect behavior, other versions, or Premium numeric skill
execution. A separate three-boot fixture verifies the narrower server-restart
case for these two effects; see the [restart evidence](POTION_LIFECYCLE_COMPATIBILITY.md#restart-persistence-evidence).

Focused fixture `srv-paper-potion-sync-exploratory-20261117-r5` passes this
contract on plugin SHA-256
`f1d298440bdd9c5170fa5b88c242031b570bae02a63cd2839111b2c96854eb5e`.
The same JAR passes the 471-probe full strict catalog in
`srv-paper-potion-sync-strict-20261117-r3`. Native comparisons remain exploratory
and separate from that catalog. The [evidence index](../tools/paper-integration/STARTUP_EVIDENCE.md)
records the matching reports and redacted server/client log hashes.

## Required sequence

Each backend executes these stages, with both clients acknowledging every
checkpoint before the server advances:

| Stage | Required evidence |
| --- | --- |
| Live addition | Haste `(1200 ticks, level 2, ambient, particles, no icon)` and hidden-particle Invisibility reach the player and Cow. Owner receives exact effect packets; observer tracks particle metadata and invisibility. |
| Observer reconnect | Existing UUID reconnects with a new entity ID; current player/Cow metadata arrives without effect-duration packets for watched entities. |
| Owner reconnect with effects | Paper restores the remaining durations saved after its logout tick; login packets use those remaining values and do not restart at 1200. Watcher removes the old entity and tracks the new one. |
| Forced change | Haste becomes level 1, non-ambient, with particles/icon. Owner receives removal before replacement; observer's ambient metadata becomes false. |
| Hidden particles | Another forced Haste replacement disables particles/icon. Observers receive an empty effect-particle list while invisibility remains. |
| Explicit clearing | Both effects are removed; the owner's removal packets and observers' empty particle/invisibility state agree. |
| Owner reconnect after clearing | No effect returns from player data or from login packets; observer removes the old entity and tracks the clean replacement. |

There are 118 named server assertions and 28 client checkpoints: seven stages
for each backend, checked by both roles. Fourteen native/imported comparisons
and six planned reconnections are required. A missing acknowledgment, failed
cast/state/isolation check, unplanned disconnect, kick, or timeout cannot produce
a passing archive. Cleanup restores player modes, invulnerability and gravity,
teleports players to their original locations, unregisters listeners, and
removes both probe entities and the managed instance.

## Paper wire details

Ordinary observers receive entity metadata rather than duration/amplifier
effect packets for the entities they watch. In protocol 1.21.1, LivingEntity
metadata key 0 contains the invisibility flag (bit 32), key 10 contains the
effect-particle list, and key 11 carries ambient state. Haste uses an
`entity_effect` particle with RGB `0xd9c043`. The pinned `MobEffect` factory
sets alpha to `floor(38.25) = 38` for ambient effects (`0x26d9c043`) and 255
otherwise (`0xffd9c043`). The validator checks serializers,
particle data, tracking spawn/destruction, and the exact expected state.

On this pinned Paper version, ambient metadata considers only effects whose
particles are visible. With no visible effects it becomes true. Removing all
effects clears the particle list but retains the last ambient field; a newly
created clean player starts with the default false value. These are observable
Paper states, not plugin settings to normalize away. Graphical interpretation
of the fields remains outside this evidence.

Player login effect packets omit the blend flag used for new additions. The
client checks exact effect IDs, levels, flags and packet counts; reconnect
durations must be the remaining duration observed by the server, allowing one
tick around the ten-tick interval between join and checkpoint. The server also
compares the join duration with its quit observation minus exactly one tick:
the pinned `PlayerList.remove` calls `ServerPlayer.doTick` after `PlayerQuitEvent`
and before saving player data. Both backends' quit/join values are retained in
eight required duration traces for Haste and Invisibility; clean reconnects
must retain zero for both. Neither
backend's durations are compared by wall time, since network login latency may
differ. The unaffected Cow keeps ticking during player disconnects.

The focused runner verifies the full named server assertion set and replays
all recorded client events through the validator before archiving. A claimed
aggregate success cannot replace missing metadata, login packets, phase
boundaries, reconnections, or cleanup. Sixteen validator tests include negative
cases for wrong/lost metadata, incorrect particle colors/serializers, reset
durations, wrong login blending, absent removals/spawns/destruction, unexpected
observer effect packets, duplicate/missing phases, and incomplete log replay.
Unrelated Display metadata uses different serializers and is ignored.

## Reproduction and limits

```bash
./mvnw -B -pl helstera-plugin -am verify
npm ci --prefix tools/paper-integration
npm run test:potion-sync --prefix tools/paper-integration
pwsh -NoProfile -File tools/paper-integration/run-potion-sync-fixture.ps1 \
  -EvidenceRevision r6 \
  -MythicMobsJarPath /path/to/MythicMobs-5.7.2-FTWvGuE5.jar \
  -MojangCachePath /path/to/cache/mojang_1.21.1.jar
```

Choose an unused revision. The optional vanilla cache must match the SHA-256
declared by the pinned Paperclip jar. Paper and MM are pinned separately; the
report records plugin, YAML, client, contract, evidence-validator, and runner
hashes. Server/client logs are archived with token redaction. Existing evidence
is never overwritten. See [Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md)
for the actual artifacts, hashes, and applicable release JAR.

Native comparisons remain exploratory and do not replace the full strict
server/client catalog. Complete Mechanics and Skills catalogs, arbitrary other
plugins' event changes, and native equivalence when forced removal is cancelled
remain open. The [packet contract](POTION_PACKET_COMPATIBILITY.md) covers all
eight single-effect presentation flag combinations; the
[lifecycle contract](POTION_LIFECYCLE_COMPATIBILITY.md) covers hidden chains,
natural expiry, instant effects, and event cancellation.
