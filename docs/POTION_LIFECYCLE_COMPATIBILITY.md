# Potion lifecycle compatibility

The lifecycle probe covers the Paper 1.21.1-133 server behavior of the supported
`potion`/`effect` mechanic. The oracle is MythicMobs 5.7.2, Modrinth `FTWvGuE5`.
Both backends execute the same [packaged Skills YAML](../helstera-plugin/src/main/resources/probes/potion-lifecycle-skills.yml)
with static arguments, a Cow recipient, a Zombie recipient, and isolated controls.
The API supplies the recipient as `@Trigger`; vanilla cows do not acquire a
combat target for MM's `@Target` selector. Target acquisition is a separate contract.

The production importer and skill binder load the Helstera copy. The native
probe checks that MM's loaded source file exactly matches the packaged source.
The runner pins both Paper and MM by SHA-256 and records the plugin and source
hashes. Exploratory native evidence remains separate from the strict release
catalog; exact reports and hashes are indexed in
[Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md).

## Supported behavior

| Scenario | Required server result |
| --- | --- |
| Haste, 8 ticks, ambient on, particles/icon off | Exact initial flags; effect expires with a non-cancelled `EXPIRATION` event. |
| Slowness level 0 for 40 ticks, then level 2 for 8 ticks | Stronger effect becomes visible; weaker effect returns with its duration already reduced; it eventually expires. |
| The same hidden chain, then `force=true`, level 1 for 6 ticks | Existing chain is removed through the plugin event path; the replacement expires without restoring either old effect. |
| Cancel `ADDED` | No effect is installed. |
| Cancel `CHANGED` | Original level and duration remain intact. |
| Cancel expiry, then allow it | Effect remains while expiry is cancelled and disappears after cancellation is released. |
| `HEAL`, duration 1, level 0 | Applied on an entity tick: Cow health 10 → 14, Zombie health 10 → 4. |
| `HARM`, duration 1, level 0 | Applied on an entity tick: Cow health 10 → 4, Zombie health 10 → 14. |
| Cancel instant-effect addition | Both recipients retain health 10 and have no instant effect. |
| Allow instant addition, cancel damage/regain events | Events occur; both recipients retain health 10; effects still expire. |

All health checks use maximum health 20, no absorption, and reset damage
immunity between cases. AI, gravity, and distance-based despawning are disabled;
Zombies cannot burn in daylight. No effect or health change may reach the caster or control entity.
The event trace is retained with the log so an aggregate success does not hide
which event paths occurred.

Temporary plugin chunk tickets keep the test entities loaded. The probe waits
for `Chunk.LoadLevel.ENTITY_TICKING` before applying any timed effects; loaded
chunks can still be waiting for neighbours to become ready. This readiness
check is bounded. Cleanup removes entities, instances, temporary source files,
listeners, and all tickets acquired by the session.

## Release and reproduction

`potionLifecycle` requires every one of its 47 assertions, including readiness
and cleanup. Report contract tests reject false or missing assertions, omitted
hidden-effect/cancellation evidence, a failed instant-effect check, missing
cleanup, and omission of the whole probe. The focused runner waits for every
required probe to finish and compares the complete observations with the release
catalog before archiving a pass.

```bash
./mvnw -B -pl helstera-plugin -am verify
pwsh -NoProfile -File tools/paper-integration/run-potion-mechanic-fixture.ps1 \
  -EvidenceRevision r19 \
  -MythicMobsJarPath /path/to/MythicMobs-5.7.2-FTWvGuE5.jar
```

Choose an unused revision. Omitting `-MythicMobsJarPath` runs the imported
runtime, timing, and lifecycle probes without MM. Providing the pinned artifact
also runs `potionLifecycleNative` with the identical assertion contract. This is
an exploratory comparison and does not replace the full strict server/client run.

## Restart persistence evidence

Focused fixture `srv-paper-potion-restart-strict-20261118-r6` runs three real
Paper 1.21.1-133 server boots with MythicMobs 5.7.2. The first boot applies
Haste and Invisibility to an online player through native MythicMobs skills and
to a Cow through the production importer. The second boot checks the same
player/Cow UUIDs, remaining effects and durations against saved player/entity
NBT and actual login packets; durations have advanced rather than reset. It
then removes both effects. The third boot confirms the cleared state stays
clear, with two-client packet checks on every stage. The fixture restores the
players' original game modes on successful completion.

The report and redacted logs, including artifact and source hashes, are indexed
in [Paper evidence](../tools/paper-integration/STARTUP_EVIDENCE.md). To rerun,
build the plugin and choose a fresh evidence revision:

```bash
./mvnw -B -pl helstera-plugin -am verify
npm ci --prefix tools/paper-integration
pwsh -NoProfile -File tools/paper-integration/run-potion-restart-fixture.ps1 \
  -EvidenceRevision r7 \
  -MythicMobsJarPath /path/to/MythicMobs-5.7.2-FTWvGuE5.jar
```

This is a focused persistence slice, not a full strict release run. It covers
only Haste/Invisibility on the pinned Paper/MM versions; other effects,
long-duration expiry, other plugin interactions, graphical rendering and other
server versions remain open. The full Mechanics and Skills catalogs remain
incomplete.

## Boundaries

Duration remains a positive exact integer and level a nonnegative exact integer,
bounded by Java's integer range. Infinite/zero/negative duration, fractional
levels, every effect type, client rendering of particles/icons, other Paper/MM
versions, Premium dynamic numeric execution, and arbitrary event-plugin
interactions are outside this evidence. See the
[numeric expression contract](NUMERIC_EXPRESSION_COMPATIBILITY.md) for formulas
and the [recipient contract](NUMERIC_PLACEHOLDER_COMPATIBILITY.md#recipient-context)
for invalid-input preservation and delay/repeat semantics.

Helstera deliberately stops a forced replacement if another plugin cancels
removal and the original effect remains. MM's adapter attempts the subsequent
addition regardless. The existing runtime probe verifies Helstera's preservation
contract; this cancellation difference is not claimed as native equivalence.
The full Mechanics and Skills importer catalogs remain incomplete. The separate
[client packet contract](POTION_PACKET_COMPATIBILITY.md) covers protocol flags,
duration/amplifier, ordered removal and cancellation on a real connection;
graphical rendering remains outside both probes.
