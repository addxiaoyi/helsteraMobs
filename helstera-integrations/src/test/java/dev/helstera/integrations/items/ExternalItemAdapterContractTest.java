package dev.helstera.integrations.items;

import dev.helstera.api.integration.Capability;
import dev.helstera.integrations.mythicmobs.MythicMobsAdapter;
import dev.helstera.integrations.region.WorldGuardAdapter;
import dev.helstera.integrations.economy.VaultEconomyAdapter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalItemAdapterContractTest {
    @Test
    void itemAdderDoesNotClaimUnimplementedItemResolution() {
        var adapter = new ItemAdderAdapter();

        assertFalse(adapter.isConnected());
        assertTrue(adapter.capabilities().isEmpty());
        assertFalse(adapter.hasCapability(Capability.CUSTOM_ITEM_RESOLVE));
        assertTrue(adapter.supportedVersions().contains("none verified"));
        assertNull(adapter.resolve("itemsadder:example_sword"));
    }

    @Test
    void craftEngineDoesNotClaimUnimplementedItemOrPackResolution() {
        var adapter = new CraftEngineAdapter();

        assertFalse(adapter.isConnected());
        assertTrue(adapter.capabilities().isEmpty());
        assertFalse(adapter.hasCapability(Capability.CUSTOM_ITEM_RESOLVE));
        assertFalse(adapter.hasCapability(Capability.RESOURCEPACK_MERGE));
        assertTrue(adapter.supportedVersions().contains("none verified"));
        assertNull(adapter.resolve("ce:example_sword"));
    }

    @Test
    void mythicMobsCapabilitiesRequireAConnectedHook() {
        var adapter = new MythicMobsAdapter(null, null, new YamlConfiguration());

        assertTrue(adapter.capabilities().isEmpty());
        assertTrue(adapter.supportedVersions().contains("not CI-verified"));
        adapter.disable();
        assertTrue(adapter.capabilities().isEmpty());
    }

    @Test
    void worldGuardRegionCapabilityRequiresAConnectedProvider() {
        var adapter = new WorldGuardAdapter();

        assertTrue(adapter.capabilities().isEmpty());
        assertTrue(adapter.supportedVersions().contains("not CI-verified"));
        assertNull(adapter.containsRegion("spawn", null));
        adapter.disable();
        assertTrue(adapter.capabilities().isEmpty());
    }

    @Test
    void vaultDoesNotClaimBalanceCapabilityUntilAnEconomyProviderConnects() {
        var adapter = new VaultEconomyAdapter();

        assertFalse(adapter.isConnected());
        assertTrue(adapter.capabilities().isEmpty());
        assertFalse(adapter.hasCapability(Capability.ECONOMY_BALANCE));
        assertTrue(adapter.supportedVersions().contains("not CI-verified"));
        assertTrue(adapter.balance(null).isEmpty());
    }

}
