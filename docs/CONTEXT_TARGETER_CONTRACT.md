# Named players, Interaction history and inherited locations

The current slice targets public MythicMobs 5.7.2 on Paper 1.21.1. It adds
native spelling and aliases to direct skill binding and the importer.

| Selector | Behavior |
| --- | --- |
| `@PlayerByName` / `@specificplayer` | `name/n`, default `CarsonJF`; resolves placeholders and uses `Bukkit.getPlayerExact`. Case handling follows Bukkit, prefixes do not match, and world does not limit lookup. The default excludes the caster; `targetSelf=true` permits it. |
| `@InteractionLastAttacker` / `@lastAttacker` | Reads the caster Interaction's last attack record and resolves its currently online player. Missing history, offline players or a different caster type produce no targets. |
| `@InteractionLastInteract` / `@lastInteract` | Reads the caster Interaction's last interaction record with the same lookup rules. |
| `@LocationsOfTargets` / `@locationOfTarget` / `@LOT` | Projects inherited entity targets to a deduplicated location collection, or uses inherited location metadata when the entity collection is absent. |

An empty entity collection and an absent entity collection have different native
meanings. Selecting a location clears an existing entity collection to empty;
it does not make that collection absent. Consequently, an entity selection
followed by a location selection and a nested `@LOT` returns no locations.
`BehaviorContext` retains this distinction through mechanic copies, audiences,
condition scopes, skill-tree creation and queued tree restoration. A location
context constructed with an absent entity collection can forward its location.

The location projection implements offsets, forward/side movement, direction
length, nonzero coordinate overrides, block centering, block include/ignore
filters, and positive-limit sorting. Native `rotateX/Y/Z` parameters are read
but not applied by its mutation method. Native HashSet order is unspecified;
Helstera preserves inherited order when no explicit sorting is requested.
Random order and tied distances do not promise the same arbitrary selection.

The focused Paper fixture requires an actual online client and checks selection
alongside real tag/chunk mutations, through both direct and imported actions.
Interaction history is prepared with server data commands and then read through
the public API. This validates history lookup, not client input packet handling.
Its independent audit fixes the complete case list and expected outcomes;
synthetic audit tests are rejection controls, not server evidence.

The new fixture and final artifact verification remain pending. These additions
do not by themselves implement all entity targeters, global native targeter
configuration, or multi-location metadata collections throughout every existing
skill primitive.
