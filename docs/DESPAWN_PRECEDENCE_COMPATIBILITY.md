# Despawn/Persistent precedence compatibility

Terminal-variable release refresh (2026-11-20): [despawn-precedence](../tools/paper-integration/reports/paper-1.21.1-despawn-precedence-20261120-r8.json) passed against plugin SHA-256 `0d9070b2764868a8de94888c140238d03dc3691ce3ac01355e3d766d444c3d75`, also verified by the 471-probe strict catalog. Earlier entries below describe their original artifacts; their documented behavior exceptions remain unchanged.

The reference is public MythicMobs 5.7.2 on Paper 1.21.1-133. Resolution follows
this order, independently of YAML key order:

1. `config-mobs.yml.Configuration.DefaultMobOptions.Despawn` supplies a **YAML
   boolean** default: true selects NORMAL, false selects NEVER. Omission or a
   non-boolean uses the built-in true. The native server reads this file from
   `plugins/MythicMobs/config/`; the importer reads the same `config/config-mobs.yml` layout and falls back to
   a root-level `config-mobs.yml` only when the modern path is absent. A malformed
   modern file warns and uses built-in defaults; it does not select a legacy file.
2. Top-level `Despawn` overrides the global mode. `Options.Despawn` overrides the
   top-level mode.
3. Top-level `Persistent` defaults to false. A YAML boolean `Options.Persistent`
   overrides it; a malformed value inherits the top-level boolean.
4. If the effective Persistent value is true, the final mode is PERSISTENT,
   including when either Despawn field explicitly selects another mode.
   Persistent=false does not override an explicit `Despawn: PERSISTENT`.

The supported mode names are NORMAL, CHUNK, NPC, NEVER and PERSISTENT, ignoring
case. TRUE/YES select NORMAL; FALSE/NO select NEVER. Numeric 0/1, quoted numeric
aliases, whitespace-padded names and unknown names fall back to NORMAL, as in
this native version. The importer retains unrecognized source values under
`mob.unsupported` and emits warnings, including invalid fields later overridden
by a valid field. Quoted `'true'`/`'false'` are valid *Despawn* aliases but are not
YAML booleans for *Persistent* or the global default. Unquoted YAML yes/no are
parsed by Bukkit as booleans.

This corrects earlier importer behavior that returned immediately after
Options.Despawn, ignored top-level fields, treated numeric mode aliases as native,
and allowed global mode names such as CHUNK. Historical `importedMobDefaults`
strict results used that incorrect global assumption; the current probe uses
`Despawn: false` and verifies NEVER. Broad case-insensitive key lookup remains
an importer convenience and is not a claim about native key casing.

The evidence runner `run-despawn-precedence-fixture.py` creates six isolated
servers with global default omitted, true, false, CHUNK, quoted false and numeric
zero. Each runs the same 72 source configurations through native spawning and
the production importer/spawner, then shuts down and restarts. It records native
and actual runtime modes, both Bukkit distance/save flags, entity and registry
presence, stable UUIDs and real post-restart deaths. Source YAML, generated YAML,
migration warnings, identities and redacted logs are archived. The validator
checks outcomes against explicit expected cases, not merely equality between two
implementations, and checks callbacks and boot ordering.

The existing `MM-5.7.2-shutdown-command-scheduling` exception applies: native
command-based onDespawn callbacks attempt to schedule after MythicMobs disables
and fail; runtime synchronous commands execute once. The validator requires the
native exception count and native scheduler stacks. This exception does not
excuse missing death callbacks after restart. See the
[lifecycle contract](DESPAWN_LIFECYCLE_COMPATIBILITY.md).

This scope does not establish kill-command immunity, forced crash recovery,
all template inheritance combinations, every mob family, other Paper/MM versions,
or every native unload ordering. Distance behavior has its own
[contract](DESPAWN_DISTANCE_COMPATIBILITY.md) and evidence. Broad matrix statuses
are not promoted by this interaction alone.

Current precedence evidence:
[report](../tools/paper-integration/reports/paper-1.21.1-despawn-precedence-20261120-r5.json)
and [redacted archive](../tools/paper-integration/reports/paper-1.21.1-despawn-precedence-20261120-r5-logs.zip).
There are 1,296 observations over twelve boots, 864 distinct tested entity UUIDs,
436 real deaths with 436 configured death callbacks, and 214 runtime shutdown
callbacks alongside 214 documented native scheduling exceptions. The modern
config path is used for both native and imported cases. Thirty evidence mutation
controls are rejected. Earlier r1 used a root-level migration export; r2
verified the modern path on the preceding artifact. Neither certifies the current JAR.

```bash
python3 tools/paper-integration/run-despawn-precedence-fixture.py --revision r6 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-despawn-precedence-evidence.py tools/paper-integration/reports/paper-1.21.1-despawn-precedence-20261120-r5.json
```

The current command milestone revalidated this suite against the same release
artifact; older archives retain their original hashes. Loaded cleanup command
behavior is covered separately by the [command contract](MOB_COMMAND_COMPATIBILITY.md).

The unloaded-command milestone revalidated this suite against the current release artifact.

The reentrant-command milestone revalidated this suite against the current release artifact.
