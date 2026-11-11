# External Plugin Integration Contract

**Contract version:** 1
**Applies to:** `IntegrationAdapter`, `IntegrationContract`, `IntegrationRegistry`, and every optional plugin bridge.

This contract defines when Helstera may claim an external capability, how an
adapter behaves when a dependency is missing or incompatible, and what evidence
is required before the parity matrix can call that capability verified.

## Capability states

`IntegrationContract.declaredCapabilities()` is the adapter's static design
boundary. `IntegrationAdapter.capabilities()` is the set that is currently safe
to call. A declared capability is not evidence that the integration is active.

| State | Required behavior |
| --- | --- |
| Dependency absent or disabled | Keep active capabilities empty. Preserve source identifiers and report that the capability is unavailable. |
| Dependency version outside the adapter's tested range | Keep active capabilities empty. Do not attempt best-effort calls against an unknown API shape. |
| API or service not discovered, or hook registration incomplete | Keep the affected capability inactive. A different independently working capability may remain active. |
| Hook ready and its prerequisites satisfied | Expose only that capability through `capabilities()` and `hasCapability()`. |
| Call receives invalid input, an unknown identifier, or an API error | Return the API's unavailable value (`null`, empty optional, or a documented rejected result); never fabricate a successful result. |

`isConnected()` means at least one adapter hook is active; it does not imply
that every capability declared by the contract is active. After an enable
attempt, `statusReport()` must explain absent dependencies, incompatible
versions, missing services, or partial registration when the adapter is not
fully active. It may be `null` before the first enable attempt or after
`disable()` resets the adapter.

## Adapter lifecycle

Adapters are soft dependencies. The host plugin must start and operate when an
external plugin is absent, disabled, incompatible, or throws during discovery.

1. `contract()` is stable before and after enablement. Its plugin name and
   supported-version description must match `pluginName()` and
   `supportedVersions()` exactly.
2. `enable(host)` clears stale runtime state before probing. It must be safe to
   call more than once, return `true` only when at least one hook is usable, and
   leave every failed capability inactive. Expected reflection, linkage,
   service-discovery, and registration errors become a status report rather
   than an exception escaping into host startup.
3. Capability activation is granular. A failed optional mechanic registration
   must not deactivate an unrelated event or lookup hook that passed its own
   checks.
4. `disable()` unregisters listeners and releases provider/API references. It
   leaves `isConnected()` false and `capabilities()` empty; calling it more
   than once is safe.
5. The registry isolates each adapter. One adapter's enable or disable failure
   cannot prevent another adapter from starting or stopping.

The registry validates contract identity but does not parse or enforce version
ranges. Each adapter is responsible for checking the installed plugin version
and API shape. `supportedVersions()` is currently descriptive text; therefore
the adapter's version check and a version-specific fixture are the authoritative
compatibility evidence.

## Public API and result semantics

- Keep external plugin classes out of `helstera-api` signatures and core
  compile-time dependencies. Use Bukkit types, Helstera API types, and Java
  values at the boundary. Reflection or an isolated optional bridge belongs in
  `helstera-integrations`.
- Check `hasCapability()` before invoking a capability. A capability is active
  only after dependency discovery, version/API validation, and required hook or
  service registration succeed.
- Do not collapse “unknown/unavailable” into a valid negative or numeric zero.
  `containsRegion` returns `true` for a known inside result, `false` for a known
  outside or absent-region result, and `null` when the provider cannot answer.
  Economy lookup uses `OptionalDouble.empty()` for a missing player, invalid or
  non-finite balance, unavailable provider, or provider failure; it never means
  a balance of zero.
- Custom item lookup returns no item for an unresolved identifier. It must not
  substitute a vanilla item, air, or another plugin's item. Resource-pack
  merging must report conflicts and must not claim a merge that was skipped.
- Preserve the original source configuration and emit a migration warning when
  a required external capability is unavailable. Do not silently drop or
  rewrite a custom identifier into a value that appears supported.
- Call Bukkit or provider APIs on the primary server thread unless the
  capability's public contract and provider explicitly guarantee thread safety.

## Required evidence before claiming support

Every capability marked `implemented-runtime` must have evidence at the scope
of the claim:

1. Unit or contract tests for static declarations, version/API rejection,
   activation state, result semantics, cleanup, and error isolation.
2. A positive fixture with the dependency installed at an identified version,
   proving discovery, real API/service use, and the capability's observable
   result.
3. Negative controls for absent/incompatible dependency, missing service or
   API, unknown identifiers, and provider exceptions where applicable.
4. An evidence record naming dependency versions, Paper build, fixture/report,
   plugin artifact hash, and any client-side observation needed by the claim.
5. Matrix and README wording limited to the exact tested versions and
   capability. A unit test using a fake API does not prove an installed-plugin
   compatibility range.

## Current adapter boundaries

| Adapter | Declared capability | Current claim and remaining proof |
| --- | --- | --- |
| Vault Economy | `ECONOMY_BALANCE` | Reflectively discovers the registered Economy service and accepts `getBalance(OfflinePlayer)` or the legacy `getBalance(String)` shape. Unit tests cover both signatures and invalid balances. No installed Vault/provider fixture establishes service discovery or a supported provider/version range, so this remains blocked for release parity. |
| WorldGuard | `REGION_QUERY` | Reflection bridge declares WorldGuard 7.x and requires WorldEdit's Bukkit adapter plus a `RegionManager`. Strict Paper evidence covers WorldGuard 7.0.12 with WorldEdit 7.3.8 for the tested region-condition path. Other versions and region behaviors require separate evidence. |
| MythicMobs | `MOB_MODEL_BINDING`, `EVENTS`, `SKILLS`, `CONDITIONS` | Declares MythicMobs 5.3.x–5.7.x and activates capabilities only after event/API hooks succeed. The adapter range is not established by an installed MythicMobs compatibility matrix; keep that limitation explicit. |
| ItemAdder | `CUSTOM_ITEM_RESOLVE` | Availability is reported, but no item API bridge is implemented. Active capabilities remain empty and identifiers remain unresolved/preserved. |
| CraftEngine | `CUSTOM_ITEM_RESOLVE`, `RESOURCEPACK_MERGE` | Availability is reported, but neither item nor resource-pack API bridge is implemented. Active capabilities remain empty. |
| MMOItems, MythicCrucible, ModelEngine | None active | Explicit blocked adapters may report plugin presence, but must not resolve items, execute mechanics, or bind models until a public API/version contract and installed-plugin fixture exist. |

The corresponding matrix rows remain `blocked-by-external-plugin` or
`mapped-unverified` until their capability-specific evidence is complete. This
document defines the integration boundary; it does not itself claim that a
blocked adapter works.
