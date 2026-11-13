package dev.helstera.integrations.region;

import dev.helstera.api.integration.Capability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandsAdapterContractTest {
    @Test
    void declaresFailClosedClaimLookupWithoutLinkingLandsClasses() {
        var adapter = new LandsAdapter();

        assertEquals("Lands", adapter.pluginName());
        assertTrue(adapter.contract().declares(Capability.CLAIM_QUERY));
        assertTrue(adapter.contract().failClosed());
        assertTrue(adapter.contract().preservesSourceValues());
        assertTrue(adapter.capabilities().isEmpty());
        assertFalse(adapter.isConnected());
    }

    @Test
    void acceptsOnlyStaticallyInspectedApiVersions() {
        assertTrue(LandsAdapter.isSupportedVersion("7.25.14"));
        assertTrue(LandsAdapter.isSupportedVersion("7.25.14-SNAPSHOT"));
        assertTrue(LandsAdapter.isSupportedVersion("9.0.0"));
        assertFalse(LandsAdapter.isSupportedVersion("7.25.13"));
        assertFalse(LandsAdapter.isSupportedVersion("8.0.0"));
        assertFalse(LandsAdapter.isSupportedVersion("9.0.1"));
        assertFalse(LandsAdapter.isSupportedVersion("not-a-version"));
        assertFalse(LandsAdapter.isSupportedVersion(null));
    }

    @Test
    void computesChunkQueryBoundsWithoutOverflow() {
        assertEquals(1, LandsAdapter.chunkQueryCount(0, 0, 0, 0));
        assertEquals(9, LandsAdapter.chunkQueryCount(-1, 1, -1, 1));
        assertEquals(2, LandsAdapter.chunkQueryCount(-2, -1, 4, 4));
        assertEquals(-1, LandsAdapter.chunkQueryCount(Long.MIN_VALUE, Long.MAX_VALUE, 0, 0));
    }

    @Test
    void measuresDistanceToInclusiveChunkBounds() {
        assertEquals(0.0, LandsAdapter.chunkDistanceSquared(0, 0, 0, 0));
        assertEquals(1.0, LandsAdapter.chunkDistanceSquared(16, 0, 0, 0));
        assertEquals(2.0, LandsAdapter.chunkDistanceSquared(16, 16, 0, 0));
        assertEquals(0.0, LandsAdapter.chunkDistanceSquared(-1, -1, -1, -1));
    }
}
