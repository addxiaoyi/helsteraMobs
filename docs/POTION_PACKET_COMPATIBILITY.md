# Potion client packet compatibility

The focused probe compares the `potion` mechanic from MythicMobs 5.7.2
(Modrinth `FTWvGuE5`) with the production Helstera importer and skill binder.
Both execute the same [packaged YAML](../helstera-plugin/src/main/resources/probes/potion-packet-skills.yml)
against one dedicated survival player on Paper 1.21.1-133. The API supplies the
recipient as `@Trigger`; this does not audit MM's combat target acquisition.

The client receives and decodes actual Minecraft 1.21.1 `entity_effect` and
`remove_entity_effect` packets. It checks the recipient entity, Haste registry
ID, amplifier, duration in ticks, full flags byte, packet order, and every
native/imported pair. These checks establish protocol delivery. Rendering of
particles or HUD icons in a graphical client remains unverified.

## Wire contract

The pinned Paper packet class `ClientboundUpdateMobEffectPacket` defines flags
1 for ambient, 2 for particles, 4 for the icon, and 8 for blending. Its
`ServerPlayer.onEffectAdded` path enables blending on newly added effects.
The protocol registry gives Haste ID 2. These expectations are independent of
the packets observed by the probe; the client does not infer them from MM's
output and then accept the same output as proof.

| Case, for each backend | Required packets |
| --- | --- |
| Default flags; Haste level 2, duration 80 | Add with flags 14, then explicit removal. |
| All eight ambient/particles/icon combinations | Add with the requested three bits plus blend; exact level 2 and duration 80, then removal. |
| Natural expiry; level 0, duration 8, ambient/icon on | Add with flags 13, followed by removal without an explicit server removal call. |
| Force level 2 → level 1 | Add `(80,2,14)`, remove, add `(60,1,9)`, remove; the first removal must occur before replacement. |
| Cancel `ADDED` | Server observes cancellation; the client receives no effect or removal packet during the phase. |

There are 12 cases per backend, 24 phases, and 48 packets. The server verifies
84 individual assertions, including identical source, clean/survival recipient,
every cast and Bukkit effect, removals/cancellation, caster isolation, and
cleanup. Phase markers surround each case on the same ordered connection.
The client rejects missing, duplicated, reordered, or interrupted phases and
fails on timeout, kick, early disconnection, or a server failure marker.

The runner requires the complete, named server assertion set and the client's
successful exit before writing a passing report. It archives redacted server
and client logs and records Paper, MM, plugin, YAML, client, validator, and
runner SHA-256 hashes. Existing revisions are never overwritten. Native
comparisons are exploratory evidence, separate from the full strict release
catalog and its existing real potion-consumption workflow.

## Reproduction

```bash
./mvnw -B -pl helstera-plugin -am verify
npm ci --prefix tools/paper-integration
npm run test:potion-packets --prefix tools/paper-integration
pwsh -NoProfile -File tools/paper-integration/run-potion-packet-fixture.ps1 \
  -EvidenceRevision r3 \
  -MythicMobsJarPath /path/to/MythicMobs-5.7.2-FTWvGuE5.jar
```

Use an unused revision. To avoid downloading vanilla 1.21.1 again, optionally
pass `-MojangCachePath /path/to/cache/mojang_1.21.1.jar`; the runner checks its
SHA-256 against the pinned Paperclip download identity before copying it.
The runner also executes the validator's 16 contract
tests before starting Paper. Negative fixtures reject missing add/removal,
wrong duration/level/flags/blending/registry ID/recipient, unexpected packets
after cancellation, incorrect force order, duplicate packets/phases, incomplete
phases, and aggregate success without all observations.

See [Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md) for recorded
runs and hashes, and the [lifecycle contract](POTION_LIFECYCLE_COMPATIBILITY.md)
for hidden effects, instant effects, expiry cancellation, and health events.
The separate [observer/reconnect contract](POTION_SYNC_COMPATIBILITY.md) verifies
Haste/Invisibility metadata, observer rejoining, and effect-owner persistence
on the same pinned Paper/MM versions. This packet slice does not establish
all other effects, other versions, Premium
numeric execution, reconnect/resynchronization, other event-plugin interactions,
or native equivalence when a plugin cancels forced removal. Complete Mechanics
and Skills catalogs remain open.
