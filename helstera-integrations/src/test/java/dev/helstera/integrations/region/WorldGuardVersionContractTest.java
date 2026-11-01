package dev.helstera.integrations.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGuardVersionContractTest {
    @Test
    void acceptsDeclaredSevenSeriesVersions() {
        assertTrue(WorldGuardAdapter.isSupportedVersion("7.0.13"));
        assertTrue(WorldGuardAdapter.isSupportedVersion("7.0.13-SNAPSHOT+build.2"));
    }

    @Test
    void rejectsVersionsOutsideDeclaredRange() {
        assertFalse(WorldGuardAdapter.isSupportedVersion("6.2.2"));
        assertFalse(WorldGuardAdapter.isSupportedVersion("8.0.0"));
        assertFalse(WorldGuardAdapter.isSupportedVersion(null));
    }
}
