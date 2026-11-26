# Main-scoreboard actions

M03 adds the server's **main Bukkit scoreboard**. It does not reuse or migrate
Helstera's per-instance `score-set` / `score-add` map.

| Native names | Internal action | Score holder |
| --- | --- | --- |
| `setGlobalScore`, `sgs` | `set-global-score` | `__GLOBAL__` |
| `setMobScore`, `sms` | `set-mob-score` | Caster UUID, including a player caster |
| `setTargetScore`, `sts` | `set-target-score` | Selected player's name or other entity UUID |
| `modifyGlobalScore`, `mgs` | `modify-global-score` | `__GLOBAL__` |
| `modifyMobScore`, `mms` | `modify-mob-score` | Caster UUID |
| `modifyTargetScore`, `mts` | `modify-target-score` | Selected player's name or other entity UUID |
| `modifyScore` | `modify-score` | `entry` / `e` / `name` / `n`, default `dummy` |

The reference is MythicMobs 5.7.2, SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`.
The contract was inspected in the seven mechanic classes, `LineConfig`,
`PlaceholderParser`, `PlaceholderIntImpl`, and `Numbers`.

## Arguments and operations

`objective` / `obj` / `o` select the objective, defaulting to the empty string.
A missing objective is registered with `dummy` criteria. An existing objective
keeps its criteria and other holders. Aliases are tried in the listed order,
regardless of argument order. Same-key duplicates are case-insensitive and the
last one wins. Explicit empty values remain selected. Unknown options are
ignored by these native mechanics.

`value` / `v` supplies the operand. Global and mob variants parse a static Java
integer when the skill loads; malformed, quoted, fractional, out-of-range and
placeholder input falls back to zero. `setTargetScore` defaults to zero and
uses integer placeholders. `modifyTargetScore` requires a value and resolves a
string in the selected entity's context before a strict integer parse.
`modifyScore` uses the same strict string parse but defaults to zero. Invalid
strict operands do not create an objective or change a score.

`action` / `a` accepts `SET`, `ADD`, `SUBTRACT`, `MULTIPLY`, `DIVIDE`, and `MOD`,
case-insensitively. Modify global/mob/target default to `ADD`, including invalid
or quoted action names. `modifyScore` defaults to `SET`. Set variants ignore
this option. Arithmetic uses Java signed 32-bit integers, including overflow,
truncation toward zero for division and signed remainder. Division or remainder
by zero preserves the score, with runtime diagnostics.

`modifyScore` also recognizes `UNSET`. The pinned native implementation validates
the value and creates the objective, then performs no score operation. Helstera
preserves this observed implementation contract; it does not silently interpret
UNSET as deleting every score for the holder.

Only the two target variants iterate selected entities, including nonliving
entities. The other five variants execute once per mechanic, independently of
how many recipients an outer selector finds. Native `PlaceholderString.get(metadata)`
passes a null explicit entity target: `<target.uuid>` and other unavailable
target placeholders remain literal, even if the mechanic inherited selected
entities. Explicit `<trigger.uuid>` and `<caster.uuid>` still resolve against
their own metadata roles. This applies to objective, custom entry and value.

## Independently implemented dynamic integers

The pinned public native artifact substitutes one for non-static
`PlaceholderInt` values; this is a gated parser, not an external dependency
required by Helstera. Helstera implements dynamic `setTargetScore` values itself,
including entity placeholders and expressions. Literal decimal parsing and
integer-expression conversion follow the native parser's flooring/truncation
rules; invalid input falls back to one. Raw integer ranges (`3to7`, `3-7`,
`-3to7`) use inclusive endpoints before quote or expression parsing, as in native
`RandomInt`. Thus `3-7` is a range, `(3-7)` is subtraction, and the native
constructor's malformed `-3-7` falls back to one. Reversed or overflowing range
bounds reject the write. This is an explicit behavioral difference from that
public artifact, not evidence of complete native parity. External PlaceholderAPI
expansions are not claimed by this slice.

## Verification

`MythicScoreboardSpecTest`, `ScoreboardActionsTest`, and `ScoreboardImportTest`
cover parsing, holder selection, operations, mutation boundaries, aliases,
nonliving recipients, once-only global dispatch and separation from instance
scores. The standalone fixture is:

```sh
python3 tools/paper-integration/run-scoreboard-actions-fixture.py --revision r1 --cache-directory <completed-fixture>
python3 tools/paper-integration/audit-scoreboard-actions.py <report.json> --check-current
python3 tools/paper-integration/test-scoreboard-actions-audit.py <report.json>
```

It uses a real player, actual scoreboard snapshots, native constructors plus
`cast` / `castAtEntity`, and direct/imported runtime binding. It checks every
seeded holder and an unrelated objective after each call. Full native dispatcher
scheduling and the project's release gate remain outside this fixture's scope.
Rejected runs remain archived and cannot be promoted by the semantic auditor.

The 2026-11-26 r2 [Paper report](../tools/paper-integration/reports/paper-1.21.1-scoreboard-actions-20261126-r2.json)
passes 212 observations, including eight explicit public-build numeric-parser
differences. Its audit rejects 13,629 altered-evidence controls. The native
mechanics are prepared and retained at least six scheduler ticks before use:
Mythic's TriggerPlaceholder delegate initializes through queueAfterLoad. The
auditor requires preparation logs and per-row tick evidence. Earlier failed
reports remain historical failures; their uninitialized native placeholders
are not the runtime entry-holder contract.
