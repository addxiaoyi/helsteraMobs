package dev.helstera.integrations.region;

import dev.helstera.api.integration.Capability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GriefPreventionAdapterContractTest {
    @Test
    void declaresFailClosedClaimLookupContractWithoutLinkingGpClasses() {
        var adapter = new GriefPreventionAdapter();

        assertEquals("GriefPrevention", adapter.pluginName());
        assertTrue(adapter.contract().declares(Capability.CLAIM_QUERY));
        assertTrue(adapter.contract().failClosed());
        assertTrue(adapter.contract().preservesSourceValues());
        assertTrue(adapter.capabilities().isEmpty());
        assertFalse(adapter.isConnected());
    }

    @Test
    void acceptsOnlyPaperVerifiedGriefPreventionVersions() {
        assertTrue(GriefPreventionAdapter.isSupportedVersion("16.18.4"));
        assertTrue(GriefPreventionAdapter.isSupportedVersion("16.18.5"));
        assertTrue(GriefPreventionAdapter.isSupportedVersion("16.18.5-SNAPSHOT"));
        assertFalse(GriefPreventionAdapter.isSupportedVersion("16.18.3"));
        assertFalse(GriefPreventionAdapter.isSupportedVersion("17.0.0"));
        assertFalse(GriefPreventionAdapter.isSupportedVersion("not-a-version"));
        assertFalse(GriefPreventionAdapter.isSupportedVersion(null));
    }

    @Test
    void computesHorizontalDistanceToInclusiveClaimBlockBounds() {
        assertEquals(0.0, GriefPreventionAdapter.horizontalDistanceSquared(10, 10,
                0, 10, 0, 10));
        assertEquals(1.0, GriefPreventionAdapter.horizontalDistanceSquared(11, 10,
                0, 10, 0, 10));
        assertEquals(2.0, GriefPreventionAdapter.horizontalDistanceSquared(11, 11,
                0, 10, 0, 10));
        assertEquals(0.0, GriefPreventionAdapter.horizontalDistanceSquared(10, 10,
                10, 0, 10, 0));
    }
}
