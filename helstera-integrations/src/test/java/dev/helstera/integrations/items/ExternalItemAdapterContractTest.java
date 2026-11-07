package dev.helstera.integrations.items;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationContract;
import dev.helstera.integrations.mythicmobs.MythicMobsAdapter;
import dev.helstera.integrations.region.WorldGuardAdapter;
import dev.helstera.integrations.economy.VaultEconomyAdapter;
import dev.helstera.integrations.BlockedExternalAdapter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalItemAdapterContractTest {
    @Test
    void itemAdderDoesNotClaimUnimplementedItemResolution() {
        var adapter = new ItemAdderAdapter();

        assertFalse(adapter.isConnected());
        assertTrue(adapter.capabilities().isEmpty());
        assertTrue(adapter.contract().declares(Capability.CUSTOM_ITEM_RESOLVE));
        assertTrue(adapter.contract().failClosed());
        assertTrue(adapter.contract().preservesSourceValues());
        assertFalse(adapter.hasCapability(Capability.CUSTOM_ITEM_RESOLVE));
        assertTrue(adapter.supportedVersions().contains("none verified"));
        assertNull(adapter.resolve("itemsadder:example_sword"));
    }

    @Test
    void craftEngineDoesNotClaimUnimplementedItemOrPackResolution() {
        var adapter = new CraftEngineAdapter();

        assertFalse(adapter.isConnected());
        assertTrue(adapter.capabilities().isEmpty());
        assertTrue(adapter.contract().declares(Capability.CUSTOM_ITEM_RESOLVE));
        assertTrue(adapter.contract().declares(Capability.RESOURCEPACK_MERGE));
        assertEquals(3, adapter.contract().prerequisites().size());
        assertFalse(adapter.hasCapability(Capability.CUSTOM_ITEM_RESOLVE));
        assertFalse(adapter.hasCapability(Capability.RESOURCEPACK_MERGE));
        assertTrue(adapter.supportedVersions().contains("none verified"));
        assertNull(adapter.resolve("ce:example_sword"));
    }

    @Test
    void mythicMobsCapabilitiesRequireAConnectedHook() {
        var adapter = new MythicMobsAdapter(null, null, new YamlConfiguration());

        assertTrue(adapter.capabilities().isEmpty());
        assertTrue(adapter.contract().declares(Capability.MOB_MODEL_BINDING));
        assertTrue(adapter.contract().declares(Capability.SKILLS));
        assertTrue(adapter.contract().failClosed());
        assertTrue(adapter.supportedVersions().contains("not CI-verified"));
        adapter.disable();
        assertTrue(adapter.capabilities().isEmpty());
    }

    @Test
    void worldGuardRegionCapabilityRequiresAConnectedProvider() {
        var adapter = new WorldGuardAdapter();

        assertTrue(adapter.capabilities().isEmpty());
        assertTrue(adapter.contract().declares(Capability.REGION_QUERY));
        assertTrue(adapter.contract().prerequisites().contains("RegionManager API"));
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
        assertTrue(adapter.contract().declares(Capability.ECONOMY_BALANCE));
        assertTrue(adapter.contract().prerequisites().contains("registered Economy service"));
        assertFalse(adapter.hasCapability(Capability.ECONOMY_BALANCE));
        assertTrue(adapter.supportedVersions().contains("not CI-verified"));
        assertTrue(adapter.balance(null).isEmpty());
    }

    @Test
    void blockedExternalAdapterNeverClaimsAResolvedCapability() {
        var adapter = new BlockedExternalAdapter("MMOItems", "none verified");

        assertEquals("MMOItems", adapter.pluginName());
        assertEquals("none verified", adapter.supportedVersions());
        assertFalse(adapter.isConnected());
        assertTrue(adapter.capabilities().isEmpty());
        IntegrationContract contract = adapter.contract();
        assertTrue(contract.declaredCapabilities().isEmpty());
        assertTrue(contract.failClosed());
        assertTrue(contract.preservesSourceValues());
        assertFalse(adapter.hasCapability(Capability.CUSTOM_ITEM_RESOLVE));
        adapter.disable();
        assertFalse(adapter.isConnected());
        assertTrue(adapter.capabilities().isEmpty());
    }

}
