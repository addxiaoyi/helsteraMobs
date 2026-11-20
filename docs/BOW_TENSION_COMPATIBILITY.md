# BowTension compatibility

This contract covers the `BowTension` condition on the pinned public MythicMobs
5.7.2 artifact and Paper 1.21.1-133. It is a focused condition comparison, not
a claim that MythicMobs dispatches a native player bow-shoot trigger.

## Runtime behavior

Helstera evaluates `bow-tension` only when the active `BehaviorContext` retains
an `EntityShootBowEvent`; otherwise it returns false. MythicMobs 5.7.2's
`BukkitTriggerMetadata.apply(metadata, event)` stores the event's force in its
`bow-tension` variable, and the native `BowTensionCondition` compares that value
with its configured range. The focused fixture uses a real Mineflayer player,
Paper-generated bow events, the production Helstera registry and the pinned
native condition implementation.

| Check | Helstera condition | MythicMobs native condition | Result |
| --- | --- | --- | --- |
| Force greater than 0.5 | `bow-tension >0.5` | `BowTension{force=>0.5}` | Match |
| Force less than 0.5 | `bow-tension <0.5` | `BowTension{force=<0.5}` | Match |
| Equality comparator at threshold 0.5 | `bow-tension =0.5` | `BowTension{force=0.5}` | Same outcome for all observed forces |
| No event/force metadata | Fail closed | Fail closed | Match |

Strict evidence `srv-paper-bow-tension-20261120-r10` records six real shots with
forces from 0.3225 through 0.96. All native/runtime results agree for every
observation and all missing-context controls pass. The report and redacted logs
are available in the [report](../tools/paper-integration/reports/paper-1.21.1-bow-tension-strict-20261120-r10.json)
and [redacted log archive](../tools/paper-integration/reports/paper-1.21.1-bow-tension-strict-20261120-r10-logs.zip).

## Scope limits

The fixture directly evaluates MythicMobs' condition after applying its Bukkit
event adapter. It does not invoke or verify MythicMobs' native player shoot
trigger listener, skill dispatch, imported YAML parsing on Paper, other MythicMobs
versions, or other Paper versions. `>=` and `<=` are Helstera extensions; this
comparison intentionally uses the operators accepted by the pinned native
condition. The broader Conditions catalog remains `mapped-unverified`.

Run with the cached pinned Paper dependencies:

```sh
python3 tools/paper-integration/run-bow-tension-fixture.py \
  --revision r11 --cache-directory srv-paper-player-life-20261120-r1
python3 tools/paper-integration/test-bow-tension-evidence.py
```

Choose the next unused `rN` revision if `r11` is already present; the runner
refuses to overwrite an existing fixture or report.
