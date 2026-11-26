# Base Aura lifecycle fixture

Run after building the production plugin, with no other fixture using port 25731:

```sh
python3 tools/paper-integration/run-aura-lifecycle-fixture.py --revision r1 --cache-directory srv-1.21.1
python3 tools/paper-integration/test-aura-lifecycle-evidence.py tools/paper-integration/reports/paper-1.21.1-aura-lifecycle-YYYYMMDD-r1.json
```

The runner pins Paper 1.21.1-133 and public MythicMobs 5.7.2 by SHA-256,
creates a fresh server, launches a real Mineflayer player, and retains an
immutable report plus raw-log archive. A rejected run is retained as
`evidenceMode=observation`, never as successful compatibility evidence.

The probe uses the installed production Aura registry and listener. It
wraps all three existing callback dispatchers, captures the callback skill
variables, and delegates to the installed production skill service. It
executes mechanics both directly and through the actual Mythic importer.
The probe uses reflection only for classloader isolation, observing private
tracker IDs/state, and wrapping/restoring the callback observers.

There are 38 state observations, two paths through each of 19 cases:
normal/cancelled incoming damage, outgoing damage, projectile versus shooter
identity, normal/cancelled target death, caster death, event termination with
a suppressed remove callback, explicit suppressed removal, retained merge
policy, overwrite, independent same-name final snapshots, cancelled player
teleport/world-change, unchanged tick charges, refresh and explicit charge
consumption, timer expiry/removal/registry-clear, and a real player quit.

Damage, death and teleport events are dispatched through Bukkit's actual
plugin manager with real entities. They are controlled event stimuli, not
claims that physical combat or portal navigation was simulated. Player quit
and BossBar packets use an actual protocol connection. The client requires
six distinct bars with RED/SOLID fallback, decreasing progress, exactly one
removal each, and no surviving bar before quit.

Native comparisons are scoped to the documented base Aura contract. The
cancelled-death case explicitly expects the native target aura to end at
NORMAL while Helstera retains it at MONITOR with `ignoreCancelled=true`.
The report declares `fullAuraParity=false`. It does not certify event-specific
Aura subclasses, full native AI goals, every timer cleanup path, reload, or
a complete plugin release. Unit coverage of unavailable targets, throwing
callbacks and registry lifecycle remains separate.

The validator binds observations to raw logs, artifact/source hashes, exact
entity relationships and callback snapshots. The tamper test alters each
record/field, client lifecycle, scope declaration and archive membership,
including attacks with recomputed checksums. Synthetic validator self-tests
are implementation checks only and are never stored as Paper evidence.
