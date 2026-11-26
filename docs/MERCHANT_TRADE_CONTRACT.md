# Merchant trade mechanics

Baseline: Paper 1.21.1 / public MythicMobs 5.7.2. This contract concerns
`addTrade/setTrade/removeTrade/replaceTrade` and `opentrades/opentrade/trade`.
It does not implement the mob-level `Trades` or `DynamicTrades` configuration.

## Recipe mutation

All four mutation aliases map to `merchant-trade`. Their default action is
**ADD**, including `removeTrade` and `replaceTrade`. Only `action/mode/m`
selects ADD, REPLACE or REMOVE; other values, including SET, are inert.
The selected entity must implement Bukkit `Merchant`; Villager and
WanderingTrader are supported. ADD inserts at the specified zero-based slot,
REPLACE changes one recipe, and REMOVE removes one recipe.

A selected Villager with profession NONE becomes NITWIT before action/index
handling. On the pinned Paper version this change clears its existing recipes.
The profession change and cleared recipes survive an invalid mode or index.
An existing non-NONE profession is retained. Errors in one recipient are
reported through the existing skill fault handler and do not abort other
recipients.

Fields are static except the item amount suffix. Alias priority is left to
right below; repeated occurrences of the same key use the last value.

| Field aliases | Default |
| --- | --- |
| `action/mode/m` | ADD |
| `slot/s/index` | 0 |
| `ingredient/item/ingredient1/item1/i/i1` | STONE |
| `ingredient2/item2/i2` | absent |
| `result/r` | STONE |
| `maxUses/uses/u` | 2147483647 |
| `experienceReward/expReward/exp/dropExp` | false |
| `villagerExp/villExp/vexp` | 0 |
| `priceMultiplier/multiplier` | 0 |
| `demand/d` | 1 |
| `specialPrice/special` | 1 |
| `ignoreDiscounts/discounts` | false |

`discounts` is not inverted. New recipes start with zero uses. Malformed
static integers/floats use their native defaults; booleans use
`Boolean.parseBoolean`. Quoted static numeric fields are not unquoted.
Non-finite price multipliers are rejected by Helstera rather than sent into
the merchant state. Positional arguments and trailing garbage are rejected
by the binding/importer.

An item value splits at its first literal space: `DIAMOND 3` means an item
source and an amount of three. The source first resolves a case-sensitive
DropTable name, then the existing inline/generated-item path. Generation uses
the **selected merchant** as both dropper and trigger. XP and command drops
are skipped when choosing the first item; commands are never executed.
Native item drops with generated amounts below one are filtered out before
selection. The first remaining item receives the mechanic amount. Custom item metadata is retained. Tables
without an item produce AIR, which cannot become a merchant recipe result.

The amount suffix uses native integer/range parsing. The public native build
falls back to one for Premium-gated dynamic/quoted operands. Helstera supports
those operands itself; their placeholder recipient is the incoming trigger,
not the selected merchant. Those differences are explicit extensions, not
matching-native observations. Existing ordinary reward delivery retains its
amount filtering.

## Opening a trade window

The caster must be a **Villager** and the selected recipient a **Player**.
A WanderingTrader caster and the reversed player/villager direction are
inert. `realTrade/real` defaults to true and uses the native boolean parser.

With `real=true`, the player opens the caster's merchant using `force=true`.
An existing trader is closed first by Paper. With `real=false`, a synthetic
merchant receives the villager's custom name and recipes and is opened with
`force=true`; it does not take over the actual villager's trader. Both modes
use Paper's merchant inventory lifecycle, including normal client closure.

The copy path deliberately calls the same `setRecipes(getRecipes())` API
sequence as native. This is not a claim that all recipe object mutations or
completed transactions are isolated; those require explicit observations.

## Validation scope

Offline parsing, action, production binding, importer and reward-filter
regressions are present. The complete Maven verification passes 2,938 tests
with no failures, errors or skips and all eleven SpotBugs module checks.

The [2026-11-26 r3 report](../tools/paper-integration/reports/paper-1.21.1-merchant-trade-20261126-r3.json)
passes 129 observations: 43 scenarios through native casts, direct runtime
binding and imported configuration on real merchants. Its plugin SHA-256 is
`424911672851df593ec79fe1ee93f4bc6f126e5335128009fba4ae3766f13a6d`.
An independent audit verifies recipe fields and unchanged controls, actor and
trigger direction, packet trade lists, UI identity/takeover and server closure.
Its negative controls reject 3,376 semantic mutations after recomputing file
hashes, including recipe fields, actor identity and window packet removal.
Native casts bypass dispatcher scheduling; direct/imported casts exercise the
inherited `@targets` path. Two connected protocol clients record window
and trade-list packets and send real close requests. A successful focused
report still does not prove completed exchanges, trade-event cancellation,
restocking, gossip/discount recomputation, human rendering, randomized custom
metadata, reload/disconnect handling, every selector or the final release gate.
