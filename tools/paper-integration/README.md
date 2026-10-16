# Isolated Paper Integration Server

This fixture starts a disposable Paper 1.21.1 server in the repository root at
`srv-paper-integration` (or the directory named by `PAPER_INTEGRATION_DIR`).
Each run resets only that fixture's plugin data and
enables the delayed model self-test; it does not use or modify existing
`srv-*` worlds.

Build the full plugin first, then run:

```powershell
mvn -o -q -pl helstera-plugin -am -DskipTests package
npm ci --prefix tools/paper-integration
$env:PAPER_MENU_WORKFLOW = 'true'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 75
```

When Mineflayer is installed, the fixture connects a disposable offline client,
opens the configured two-page menu, drops operator permission before clicking a
restricted button, pages forward, executes an allowed command, and verifies the
inventory closes, and observes the plugin's message/title/actionbar Audience
delivery. The structured report includes
`custom-menu-player-workflow` probe only when the client emits its assertion
records plus `audience-player-delivery`; otherwise each scenario remains
unobserved.

For AI probes that only need an online player, use the lighter client workflow:

```powershell
$env:PAPER_PLAYER_WORKFLOW = 'true'
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 -RunSeconds 45
```

This keeps `HelsteraTester` online without opening a menu or asserting menu
behavior.

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
powershell -ExecutionPolicy Bypass -File tools/paper-integration/run-server.ps1 `
  -RunSeconds 75 -Strict
```

Strict mode requires the workflows needed by the registered probes (for
example, set `PAPER_MENU_WORKFLOW=true` and install the fixture's Mineflayer
dependencies for player/menu/Audience evidence). A green Maven report-contract
test validates parsing only; it does not substitute for this live Paper run.
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
Validate the report schema and status mapping with the synthetic test log:

```powershell
powershell -ExecutionPolicy Bypass -File tools/paper-integration/test-report.ps1
```

The JSON contract is `integration-report.schema.json` (schema version 1).
The report test uses Python `jsonschema` Draft 2020-12 validation in addition
to the PowerShell semantic assertions. On Windows it is also bound to Maven's
`verify` phase with `-Ppaper-integration`:

```powershell
mvn -Ppaper-integration -pl helstera-plugin -am verify
```

That profile requires PowerShell and Python with `jsonschema` 4.x installed;
the default Maven lifecycle does not activate it.

Passing startup proves only that the plugin loads on Paper. Feature evidence
must include a test setup, executed command/event, observed entity or inventory
state, and log output; it does not by itself upgrade parity-matrix statuses.

Persistent SkillTree restart verification is opt-in:

```powershell
powershell -ExecutionPolicy Bypass -File tools/paper-integration/test-skilltree-restart.ps1
```

It uses a dedicated `ARMOR_STAND` persistent mob, runs two Paper boots against
the same isolated world, and verifies the stable PDC mob ID and SkillTree marker
after the second boot.
