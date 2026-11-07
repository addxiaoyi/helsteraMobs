package dev.helstera.api.integration;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationContractTest {
    @Test
    void copiesAndExposesTheStaticBoundary() {
        var capabilities = new HashSet<>(Set.of(Capability.REGION_QUERY));
        var prerequisites = new HashSet<>(Set.of("WorldGuard 7.x"));
        var contract = new IntegrationContract("WorldGuard", "7.x", capabilities,
                prerequisites, true, true);

        capabilities.clear();
        prerequisites.clear();

        assertEquals(Set.of(Capability.REGION_QUERY), contract.declaredCapabilities());
        assertEquals(Set.of("WorldGuard 7.x"), contract.prerequisites());
        assertTrue(contract.declares(Capability.REGION_QUERY));
        assertThrows(UnsupportedOperationException.class,
                () -> contract.declaredCapabilities().add(Capability.EVENTS));
    }

    @Test
    void rejectsInvalidContractIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new IntegrationContract("", "7.x", Set.of(), Set.of(), true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new IntegrationContract("WorldGuard", "", Set.of(), Set.of(), true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new IntegrationContract("WorldGuard", "7.x", Set.of(),
                        Set.of(" "), true, true));
    }

    @Test
    void blockedFactoryIsExplicitlyFailClosed() {
        var contract = IntegrationContract.blocked("ModelEngine", "none verified", "runtime API bridge");

        assertTrue(contract.declaredCapabilities().isEmpty());
        assertEquals(Set.of("runtime API bridge"), contract.prerequisites());
        assertTrue(contract.failClosed());
        assertTrue(contract.preservesSourceValues());
    }
}
