# Player radius condition compatibility

This contract targets MythicMobs **5.7.2** on Paper **1.21.1-133**. It does not
establish behavior for other MythicMobs versions or the complete Conditions and
RandomSpawns catalogs.

## Imported syntax

| Mythic source | Generated Helstera condition | Meaning |
| --- | --- | --- |
| `playersInRadius` | `players-in-radius 0 32 true` | Exactly zero eligible players within 32 blocks |
| `playersInRadius{r=4}` | `players-in-radius 0 4 true` | Exactly zero eligible players within four blocks |
| `playersInRadius 2` | `players-in-radius 2 32 true` | Exactly two eligible players, using the default radius |
| `playersInRadius{r=4} >0` | `players-in-radius >0 4 true` | At least one eligible player within four blocks |
| `playersInRadius{amount=1to3;r=4}` | `players-in-radius 1to3 4 true` | One through three eligible players |
| `playerWithin` | `player-within 0` | An eligible player at the queried location |
| `playerNotWithin{}` | `player-not-within 0` | No player at the queried location |
| `playerWithin{} 4` | `player-within 4` | An eligible player within four blocks |

The effective default amount in 5.7.2 is **0**, although the
`PlayersInRadiusCondition` constructor contains a `>0` fallback. Its inherited
`conditionVar` starts as `"0"`, and `LineConfig.getString` selects that value
before the fallback. Use explicit `amount=>0` when a rule requires a player.
Re-import source configurations to obtain this corrected default; previously
generated runtime YAML is not automatically rewritten.

`amount`/`a` accepts exact nonnegative integers, `>`/`<`, and inclusive `to` or
hyphen ranges. Fractional counts, overflow, invalid ranges, invalid radii,
unknown keys, empty values, and duplicate aliases remain unconverted with a
migration warning. Supplying both a named value and a trailing positional value
also remains unconverted; this avoids silently choosing between conflicting
inputs even though native MM may prioritize a named key.
Trailing positional values require a separating space; `playerWithin{}4`
is preserved rather than interpreted as a four-block distance.

## Player eligibility and extensions

`PlayersInRadius` and `PlayerWithin` exclude Creative and Spectator players.
`PlayerNotWithin` includes every game mode. In 5.7.2, the parsed
`ignoreSpectator` flag does not change the `PlayersInRadius` predicate.
Distances are spherical and inclusive at the boundary. Failed world queries or
missing origins fail closed, including through a negated condition.

Helstera retains its existing `>=`/`<=` count extensions. The pinned native
5.7.2 `RangedInt` parser rejects them; they are not claimed as native syntax.
The `radius`/`r` aliases for within/not-within are also project extensions;
the inspected native constructors use `distance`/`d`.
Direct Helstera runtime syntax remains a separate API: its parameterless
`players-in-radius` still means `>0`; imported source always emits an explicit
amount and radius.

Unit and cross-module tests exercise the importer, production condition binding,
and evaluation. The isolated RandomSpawns runner also loads the pinned native
MM plugin, compares supported source forms in all four game modes at offsets
0, 1, and 40 blocks, and separately records the inclusive-comparator boundary.
The import source directory is separate from MM's live RandomSpawns directory.
See the [Paper evidence index](../tools/paper-integration/STARTUP_EVIDENCE.md)
for archived runs and artifact hashes.
