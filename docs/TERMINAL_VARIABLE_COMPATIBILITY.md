# Terminal variable mutation and expiry

Model-bridge release refresh (2026-11-20): [terminal-variable](../tools/paper-integration/reports/paper-1.21.1-terminal-variable-20261120-r5.json) passed against plugin SHA-256 `d283a6850d0d587c80e7a81ab41c71b4f5a97186cac84fe7f30356eeb23bd051`, also verified by the 471-probe strict catalog. Earlier entries retain their original artifact scope.

The imported `set-variable ... value="..." @self` syntax now decodes the keyed
value before binding the action. Numeric values keep their declared type, and
quoted text keeps its spaces, escapes and literal targeter/condition characters.
A complete quoted command remains a single positional argument.

After a terminal caster's live variable registry is detached, `caster.*` can
still mutate the execution-owned snapshot. `target.* @self` set/add/unset
operations cannot mutate that snapshot or recreate a live registry. This boundary
uses actual registry detachment: a dead entity whose callback still has an
attached registry, or a revived entity after cancelled death, retains normal
target writes. Other live targets remain writable.

The [final comparison](../tools/paper-integration/reports/paper-1.21.1-terminal-variable-20261120-r3.json) and
[raw archive](../tools/paper-integration/reports/paper-1.21.1-terminal-variable-20261120-r3-logs.zip)
contain 144 observations, 48 identities and 448 callbacks. All 520 evidence
mutations are rejected. Production SHA-256: `0d9070b2764868a8de94888c140238d03dc3691ce3ac01355e3d766d444c3d75`.
The artifact passes 2,459 Maven tests and 11 clean SpotBugs reports.

Pinned comparison scope is Paper 1.21.1-133 with public MythicMobs 5.7.2. Twenty-four
COW/ZOMBIE direct/imported cases exercise a living control, loaded command
removal, actual death, synchronous cancellation, CHUNK unload, and command
removal during the NEVER unload window. The direct path uses original mechanic
spelling, and the imported path uses the production importer's named-skill output.

Each terminal chain verifies INTEGER set/add/unset, shared skill variables,
absolute expiry retained from before removal, short expiry, addition retaining
its deadline, addition extending its deadline, replacement removing expiry,
and addition to an expired variable leaving it absent. Target set/add/unset
have living positive controls and removed-entity negative controls. Pending spawn
work runs only for surviving entities. Native deferred command dispatch remains
an explicit timing exception with bounded tick checks.

The comparison checks actual action/death/cancellation/unload events, UUIDs,
callback ordering and timing, variable values, surviving entities, visual counts,
terminal execution counts and final empty live/disk state. Reports pin the
production JAR, Paper, MythicMobs, probe, sources and raw archive. The strict
release gate requires the independently validated comparison for the exact
release artifact and Paper hash; observation-only reports cannot satisfy it.

Historical observers are retained without relabeling: r1 records the quoted
import-value failure on the previous production artifact; r2 confirms that fix
and exposes detached-target mutation on all sixteen removed-entity cases. The
final comparison additionally tests target unset.

This establishes the listed scalar mutation and expiry slice. Unit tests also
cover detached scalar/location replacement and retained expiry without registry
recreation. Native terminal location mechanics and target/trigger missing-registry
placeholder rendering need separate Paper evidence. The runtime's retained
caster/target/trigger placeholder reads are not a full parity claim; native
manager lookup differs for removed targets. Unsupported `trigger` action scope,
reload during callbacks, cast queues, cross-trigger cooldowns, asynchronous
mechanics, child inheritance and additional Paper versions remain open. Broader
Mechanics, Skills importer, Triggers and Full SkillTree rows remain
`mapped-unverified`.

Reproduce the comparison with Java 21, Maven, Python/PyYAML, the pinned Paperclip
and authorized native MM JAR already present in the fixture cache:

```sh
./mvnw -B -ntp verify
python3 tools/paper-integration/run-terminal-variable-fixture.py \
  --revision r4 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-terminal-variable-evidence.py \
  tools/paper-integration/reports/paper-1.21.1-terminal-variable-20261120-r4.json
pwsh -NoProfile -File tools/paper-integration/test-audit-evidence.ps1
pwsh -NoProfile -File tools/paper-integration/test-version-matrix.ps1
pwsh -NoProfile -File tools/paper-integration/audit-evidence.ps1 \
  -ReleaseGate -ReleasePluginJar helstera-plugin/target/helsteraMobs-1.0.0-full.jar
```

Use an unused revision and the actual UTC report date. The runner refuses to
reuse a fixture or overwrite archived evidence. `--observe-only` explicitly
records an unassessed experiment; it never satisfies the release gate. The
mutation test's `--diagnostic-observer` option uses a synthetic in-memory control
for validator development and cannot promote or overwrite its original evidence.
The final release command requires every other artifact-matched comparison in
`release-evidence.json`, not just this focused suite.
