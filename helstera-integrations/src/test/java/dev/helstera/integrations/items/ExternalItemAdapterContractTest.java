package dev.helstera.integrations.items;

import dev.helstera.api.integration.Capability;
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
}
