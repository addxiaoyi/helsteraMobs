# Terminal role read observations

This work remains observation-only on Paper 1.21.1-133 / MythicMobs 5.7.2.
It does not satisfy the strict release gate or establish complete variable,
SkillTree, Trigger or Mechanics parity.

Current [r3 observation](../tools/paper-integration/reports/paper-1.21.1-terminal-read-20261120-r3.json)
and [raw archive](../tools/paper-integration/reports/paper-1.21.1-terminal-read-20261120-r3-logs.zip)
audit 32 cases, 64 identities, 192 lifecycle rows and 592 callbacks on plugin
SHA-256 `dff057a8406bce021dafcd2fcdf4168988b55eeeff8a4b7160f5750debebd17c`.
All stable detached-read invariants pass, and 143 semantic/archive negative
controls are rejected. All role UUIDs agree after per-backend identity mapping.
There are still 232 field differences: 200 in the initial/seeded lifecycle
windows and 32 native `UNDEFINED` versus preserved raw tokens. The audit keeps
every difference visible; none is converted into a passing parity exception.
The build passes 2,477 Maven tests and 11 clean SpotBugs reports. The evidence
gate's contract tests pass, and the release gate rejects this JAR against the
previous build's full-catalog evidence as expected.

The public placeholder renderer now keeps `<target.var.Marker|absent>` and
`<trigger.var.Marker|absent>` unresolved when those roles address the detached
caster. The caster continues to read and mutate its execution-owned values.
The distinction is a missing registry, not a missing variable: selecting the
default `absent` would also disagree with native behavior. A separate live
trigger continues to read its own value. onDespawn now retains its removed
entity as trigger, including the command window while its chunk is unloaded.

The fixture exercises eight scenarios for COW and ZOMBIE, both directly and
through the production importer: living controls, self-inflicted death,
peer-inflicted death, unattributed death, synchronous cancelled death, loaded
command removal, CHUNK unload and removal during NEVER suspension. A peer has
marker 29; the caster starts with 73. The native and runtime paths record plain
UUID placeholders, actual attackers, active membership, live variable counts,
absolute expiry, nested delayed mutations, pending spawn work and cleanup.

Historical observations are retained without relabeling:

- [r1](../tools/paper-integration/reports/paper-1.21.1-terminal-read-20261120-r1.json)
  and its [archive](../tools/paper-integration/reports/paper-1.21.1-terminal-read-20261120-r1-logs.zip)
  capture the original target snapshot leakage in 24 scenarios. The fallback
  suffix on UUID placeholders is unsupported; use the separate ID and command
  UUID fields for that observer, not the unresolved UUID placeholder text.
- [r2](../tools/paper-integration/reports/paper-1.21.1-terminal-read-20261120-r2.json)
  and its [archive](../tools/paper-integration/reports/paper-1.21.1-terminal-read-20261120-r2-logs.zip)
  use plain UUID placeholders in 32 scenarios, with 64 parent identities,
  192 lifecycle rows and 592 callbacks. They confirm the target fix and expose
  trigger snapshot leakage and absent onDespawn trigger identity. Both reports
  retain their original source and artifact hashes.

Remaining differences are deliberately visible:

1. Initial command and suspended-removal callbacks run while Helstera still
   has its live variable data, although its model has left the active registry.
   Native target lookups already fail. Snapshot detachment alone does not model
   this synchronous window.
2. Following confirmed death and CHUNK unload, native active membership persists
   briefly. In these observations it survives the seed callback at +3 ticks and
   is absent by +13 ticks; runtime detachment occurs earlier. Inspection of the
   pinned JAR's `ActiveMob.unregister(boolean)` explains the distinction:
   `true` calls `unregisterActiveMob` immediately, while `false` schedules it
   five ticks later; `setDead()` takes the latter path. The next fixture needs
   observations and target writes at ticks 0–7 to confirm ordering at the exact
   boundary, including nested/repeated callbacks. Implement that lifecycle
   without resurrecting a model or using entity validity as a registry proxy.
3. Missing registered values without a default render `UNDEFINED` in native MM;
   Helstera preserves the token. This pre-existing boundary is documented in
   [variable placeholder compatibility](VARIABLE_PLACEHOLDER_COMPATIBILITY.md).
4. Direct condition reads and location-selector/variable reads still use the
   retained lookup API. Separate native evidence is required before changing
   their behavior. Unsupported action scopes, reload/cast behavior and other
   server/plugin versions remain open.

`audit-terminal-reads.py` checks archive/member hashes, parent/role identities,
callback completeness and delays, expiry crossings, live peer reads and final
empty stores. It emits every native/runtime field difference. Its optional
`--require-detached-reads` checks only the stable detached-read invariant, after
the native registry is gone; it does not suppress earlier differences, relabel
the report or approve a release. No observer is added to `release-evidence.json`.

```sh
./mvnw -B -ntp verify
python3 -B tools/paper-integration/run-terminal-read-fixture.py \
  --revision r4 --cache-directory srv-paper-player-life-20261120-r1 --observe-only
python3 -B tools/paper-integration/audit-terminal-reads.py \
  tools/paper-integration/reports/paper-1.21.1-terminal-read-20261120-r4.json \
  --require-detached-reads --plugin-jar helstera-plugin/target/helsteraMobs-1.0.0-full.jar
python3 -B tools/paper-integration/test-terminal-read-audit.py \
  tools/paper-integration/reports/paper-1.21.1-terminal-read-20261120-r4.json
```

Use an unused revision and the actual UTC date. The authorized native JAR and
pinned Paper JAR must already be cached. The observer disables the web UI and
redacts archived server logs. Its current observation mode intentionally cannot
be requested as a comparison run.
