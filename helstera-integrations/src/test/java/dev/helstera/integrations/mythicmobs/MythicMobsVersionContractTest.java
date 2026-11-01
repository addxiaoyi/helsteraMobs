package dev.helstera.integrations.mythicmobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MythicMobsVersionContractTest {
    @Test
    void acceptsDeclaredVersionsIncludingSnapshots() {
        assertTrue(MythicMobsAdapter.isSupportedVersion("5.3.0"));
        assertTrue(MythicMobsAdapter.isSupportedVersion("5.7.2-SNAPSHOT"));
        assertTrue(MythicMobsAdapter.isSupportedVersion("5.5.1"));
    }

    @Test
    void rejectsVersionsOutsideDeclaredRange() {
        assertFalse(MythicMobsAdapter.isSupportedVersion("5.2.9"));
        assertFalse(MythicMobsAdapter.isSupportedVersion("5.8.0"));
        assertFalse(MythicMobsAdapter.isSupportedVersion("6.0.0"));
        assertFalse(MythicMobsAdapter.isSupportedVersion(null));
    }
}
