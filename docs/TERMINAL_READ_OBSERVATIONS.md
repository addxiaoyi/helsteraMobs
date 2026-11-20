# Terminal variable-registry boundary observations

This is scoped observation evidence on Paper 1.21.1-133 and MythicMobs 5.7.2.
It is not release-gate evidence and does not establish complete variable,
SkillTree, Trigger or Mechanics parity. The Parity Matrix is intentionally not
promoted by these fixtures.

The original terminal role-read series is retained at [r3](../tools/paper-integration/reports/paper-1.21.1-terminal-read-20261120-r3.json)
with its [raw archive](../tools/paper-integration/reports/paper-1.21.1-terminal-read-20261120-r3-logs.zip).
It covers 32 cases, 64 identities, 192 lifecycle rows and 592 callbacks on
plugin SHA-256 `dff057a8406bce021dafcd2fcdf4168988b55eeeff8a4b7160f5750debebd17c`.
That artifact confirms detached target/trigger placeholders do not borrow the
caster snapshot, and a separate live peer trigger stays readable. `onDespawn`
also retains its removed entity as trigger. The older audit's 232 field
differences remain historical: 200 early lifecycle reads and 32 native
`UNDEFINED` versus preserved raw tokens.

The audited [tick-boundary r6 report](../tools/paper-integration/reports/paper-1.21.1-terminal-boundary-20261120-r6.json)
and [redacted archive](../tools/paper-integration/reports/paper-1.21.1-terminal-boundary-20261120-r6-logs.zip)
observe native and runtime target writes before, at and after registry removal.
They cover eight scenarios across COW/ZOMBIE and direct/imported configurations:
living and cancelled-death controls, self- and peer-inflicted death,
unattributed death, immediate loaded command removal, CHUNK unload and command
removal during NEVER suspension. A caster begins at marker 73; an independent
peer trigger has marker 29. Each run records 0–8 tick nested actions, repeated
siblings, late siblings, role placeholders, actual native evaluation ticks,
active registration and final cleanup.

The implementation now uses the observed lifecycle rather than entity
validity: death and natural despawn retain the entity registry through the
five-tick native window; CHUNK unload uses a six-tick runtime offset because
the runtime removal callback begins one tick earlier than the native unload
skill. Immediate command and suspension removals detach before dispatch.
When `variableadd` addresses a target/trigger whose entity registry has expired,
it aborts the current nested skill action chain. Independent scheduled and
repeated siblings still run, and retained caster mutations remain isolated
from the live entity store. The independent auditor passes all 32 cases (64
identities, 192 lifecycle rows, 1,476 callbacks); its 128 negative controls
also reject corrupted evidence. Twenty initial native/runtime evaluation-order
differences remain recorded. Despite the successful scoped audit, this report
remains observation-only and does not promote any Parity Matrix status.

The first zero-delay native command can evaluate placeholders after the sibling
write within the same tick; that ordering difference is recorded separately
from the registry boundary and is not suppressed. Native missing-value
`UNDEFINED` versus Helstera's preserved placeholder token also remains an
explicit difference. Condition and location-variable lookup, other variable
mechanics, reload/cast interactions and additional Paper versions remain open.

Full `./mvnw -B -ntp verify` passes 2,484 tests and 11 clean SpotBugs reports.
The release gate still requires evidence for the exact release artifact and
all other catalog probes; this focused observation cannot satisfy it.

Historical runs preserve their original status and artifact hashes:

- [Role-read r1](../tools/paper-integration/reports/paper-1.21.1-terminal-read-20261120-r1.json)
  records the initial detached target snapshot leakage.
- [Role-read r2](../tools/paper-integration/reports/paper-1.21.1-terminal-read-20261120-r2.json)
  confirms the target fix while exposing trigger snapshot leakage and missing
  `onDespawn` trigger identity.
- Boundary [r1](../tools/paper-integration/reports/paper-1.21.1-terminal-boundary-20261120-r1.json)
  and [r2](../tools/paper-integration/reports/paper-1.21.1-terminal-boundary-20261120-r2.json)
  are retained as observations of earlier implementation artifacts. They are
  not substituted for the final audited boundary report.
- Boundary [r3](../tools/paper-integration/reports/paper-1.21.1-terminal-boundary-20261120-r3.json),
  [r4](../tools/paper-integration/reports/paper-1.21.1-terminal-boundary-20261120-r4.json)
  and [r5](../tools/paper-integration/reports/paper-1.21.1-terminal-boundary-20261120-r5.json)
  are retained for provenance; the auditor and negative-control suite were
  refined afterward. Use r6 for the current result.

Reproduce the current boundary observer serially (Paper fixtures share a port):

```sh
./mvnw -B -ntp verify
python3 -B tools/paper-integration/run-terminal-read-fixture.py \
  --revision r7 --cache-directory srv-paper-player-life-20261120-r1 \
  --observe-only --boundary
python3 -B tools/paper-integration/audit-terminal-boundary.py \
  tools/paper-integration/reports/paper-1.21.1-terminal-boundary-20261120-r7.json
python3 -B tools/paper-integration/test-terminal-boundary-audit.py \
  tools/paper-integration/reports/paper-1.21.1-terminal-boundary-20261120-r7.json
```

Use an unused revision and the report's actual UTC date. The runner refuses to
reuse a fixture or overwrite evidence. It pins Paper/MM, the production JAR,
probe and observer sources, disables the web UI and redacts archived server
logs. Neither the observer nor its auditor is included in
`release-evidence.json`.
