package dev.helstera.integrations.mythicmobs;

import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void activeEntityLookupBindsTheOptionalApiAndFailsClosedOnErrors() throws Exception {
        var adapter = new MythicMobsAdapter(null, null, null);
        Entity mythicMob = entityProxy();
        Entity ordinaryMob = entityProxy();

        adapter.bindMythicMobLookup(new FakeApiHelper(Set.of(mythicMob)));
        assertTrue(adapter.isMythicMob(mythicMob));
        assertFalse(adapter.isMythicMob(ordinaryMob));
        assertFalse(adapter.isMythicMob(null));

        assertThrows(NoSuchMethodException.class, () -> adapter.bindMythicMobLookup(new Object()));
        assertFalse(adapter.isMythicMob(mythicMob), "failed rebind must not retain a stale API helper");

        adapter.bindMythicMobLookup(new FailingApiHelper());
        assertFalse(adapter.isMythicMob(mythicMob), "API failures must fail closed");
    }

    private static Entity entityProxy() {
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "toString" -> "fixture-entity";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    public static class FakeApiHelper {
        private final Set<Entity> mythicMobs;

        public FakeApiHelper(Set<Entity> mythicMobs) {
            this.mythicMobs = mythicMobs;
        }

        public boolean isMythicMob(Entity entity) {
            return mythicMobs.contains(entity);
        }
    }

    public static class FailingApiHelper {
        public boolean isMythicMob(Entity entity) {
            throw new IllegalStateException("fixture API failure");
        }
    }
}
