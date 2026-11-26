# Generated rewards from a selected origin

`giveitemfromtarget`, `givefromtarget`, `giveitemsfromtarget` and
`itemgivefromtarget` map to `give-item-from-target`. This is M04b, distinct from
equipment copying and slot grants. The inspected native contract is public
MythicMobs 5.7.2, SHA-256
`9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f`, on Paper 1.21.1.

## Recipients and argument lookup

The caster player receives the rewards. A selected entity or location supplies
only the private animation origin. Each selected origin generates a fresh bag;
an explicit location takes precedence over retained entity recipients. Missing
selection produces no rewards. A nonplayer caster still runs generation and
conditions but does not materialize or receive the resulting items, commands or
experience.

`items`, `item`, `i` resolve in that priority order, with the last occurrence of
the same key winning. Missing or empty values are rejected. The raw value is
looked up as a case-sensitive table identifier before any quote removal or
comma splitting. Imported tables retain their original `reward-source-id`.
Only a double-quoted inline list decodes native special characters before
splitting; its trailing empty field follows Java `String.split` behavior.

`dofakelooting`, `fakelooting`, `fl` resolve in that priority order and default
to `true`. Values use `Boolean.parseBoolean`; a quoted `"true"` is false.

The native constructor queues table lookup in `DropExecutor.queueSecondPass`.
Live comparisons must run the real second-pass initializer and allow delayed
placeholder initialization before invoking native mechanics.

## Reward generation

Inline and retained native table lines support vanilla materials, imported
custom items, nested tables, experience (`exp`, `experience`, `xp`) and
`command{...}`. Supported entries share one weighted pool. Native `reward-drops`
retain fractional values and command payloads; ordinary Helstera `entries`
retain their metadata, tiers, conditions and dynamic chance evaluation.

- Native amounts remain doubles until item/command delivery truncates them.
  Experience is summed across the bag before the final integer conversion.
  Native amount ranges are continuous and upper-exclusive. A lone scalar below
  one is probability shorthand with amount one.
- `TotalItems` or a positive minimum chooses with replacement. Count ranges
  use an exclusive upper endpoint. `MaxItems` without a minimum counts selected
  outer lines before nested expansion. Nested table amounts repeat generation.
- Table conditions see the caster as both dropper and trigger, regardless of
  the incoming skill trigger or animation origin. Generated metadata is fresh.
- Bonus counts use the runtime model level when available, otherwise one,
  and the caster's integer luck attribute. Ordinary Helstera entry amounts keep
  the existing inclusive integer/tier sampler.
- Generation is bounded by 10,000 operations and 100 table expansions. The
  operation bound is an explicit Helstera protection against excessive work.

Unsupported configured drop types and external provider namespaces reject the
raw table. Invalid or ambiguous declared tables cannot fall back to a material
with the same name. Imported unsupported-only string tables remain declared
for this rejection even when they have no natural-death item projection.
Unsupported condition forms continue to be preserved by migration rather than
silently removed from an active table.

## Delivery, commands and visuals

Items are resolved through the production item service and cloned into the
caster's inventory. Configured item keys and original Mythic item names identify
custom items; their underlying material is not an alias. For example, defining
`GrantGem` with material `DIAMOND` and model 4321 leaves a plain `DIAMOND` reward
unchanged while `GrantGem` resolves to the custom item.

Overflow spawns at the caster's location. Its owner and thrower are the caster,
and mob pickup is disabled before world insertion. This differs from the
unowned overflow of `giveitemfromslot`.

Command drops recognize `command/cmd/c`, `ascaster/caster/ac/sudo/asmob`,
`astrigger/trigger/at`, and `asop/op/operator`. Native player command execution
uses `performCommand` only when an actor flag and `asop` are both true; otherwise
it uses the console. Previous operator state is restored in a `finally` block.
`<drop.amount>` receives the truncated amount, and role placeholders resolve
against the fresh caster-based reward metadata.

Inventory and experience delivery precede fake visuals. Items are materialized
again for animation, matching native behavior for custom drop generation.
`PaperFakeItemLoot` sends private spawn/metadata and five-tick collect packets.
Explicit visual removal and stale-session/world cleanup are Helstera lifecycle
extensions described in [ITEM_COPY_ACTION_CONTRACT.md](ITEM_COPY_ACTION_CONTRACT.md).

## Evidence and completion boundary

The fixture compares native, direct and imported paths with two real clients.
It archives serialized server items, actual client inventories, private packets,
tick markers, overflow ownership, experience, command scores and imported
configuration. Source and artifact hashes pin each run. The independent auditor
checks the archive; altered-evidence controls recompute hashes before expecting
semantic rejection.

The [2026-11-26 r3 report](../tools/paper-integration/reports/paper-1.21.1-generated-loot-20261126-r3.json)
passes 90 observations (30 cases through native/direct/imported paths). It
covers aliases, actor direction, nonliving and explicit-location origins,
nonplayer delivery, fractional item/experience values, named and nested tables,
weighted experience, outer count limits, caster conditions, command context,
private animations, overflow and custom model data 4321. Both clients complete
all phases. The independent auditor passed with the run's source/JAR checks
before subsequent entity-interaction registration changes.
The artifact also passes 2,896 Maven tests and all 11 SpotBugs checks.
All 765 rehashed semantic mutations are rejected by the independent auditor.

The r1 run was rejected because the fixture overwrote previously imported table
sections. The r2 run was rejected because plain diamond rewards incorrectly
resolved to the configured custom diamond. Both failures remain archived and
led to separate fixture and production corrections. Successful subsequent
evidence is indexed in [STARTUP_EVIDENCE.md](../tools/paper-integration/STARTUP_EVIDENCE.md).

Remaining acceptance includes randomized native range/weight distributions,
repeated selection of the same native drop object, randomized custom metadata,
all command flag combinations, multiple origins, actual reload/disconnect,
the full native dispatcher, and the final release gate. Strict rejection of
malformed/dynamic numeric syntax is not a claim to reproduce every public
numeric-parser fallback. External reward providers and private Mythic item/stat
registries remain separate integrations. This scoped implementation does not
complete the full M04 family or establish whole-catalog native parity.
