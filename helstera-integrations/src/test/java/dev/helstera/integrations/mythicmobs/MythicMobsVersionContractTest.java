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

    @Test
    void activeEntityTypeLookupMatchesInternalNamesAndFailsClosed() throws Exception {
        var adapter = new MythicMobsAdapter(null, null, null);
        Entity mythicMob = entityProxy();
        adapter.bindMythicMobLookup(new TypedFakeApiHelper(mythicMob),
                new FakeMobRegistry(Set.of("ForestGuardian")));

        assertTrue(adapter.isMythicMobType(mythicMob, "ForestGuardian"));
        assertTrue(adapter.isMythicMobType(mythicMob, "forestguardian"));
        assertFalse(adapter.isMythicMobType(mythicMob, "Guardian"),
                "exact matching remains the default");
        assertTrue(adapter.isMythicMobType(mythicMob, "Guardian", false));
        assertTrue(adapter.isMythicMobType(mythicMob, "guardian", false));
        assertFalse(adapter.isMythicMobType(mythicMob, "Cave", false));
        assertFalse(adapter.isMythicMobType(mythicMob, "CaveWraith"));
        assertTrue(adapter.hasMythicMobType("ForestGuardian"));
        assertTrue(adapter.hasMythicMobType("forestguardian"),
                "registry lookup accepts case-insensitive Mythic internal names");
        assertFalse(adapter.hasMythicMobType("MissingMob"));
        assertTrue(adapter.matchesMythicMobType(mythicMob, "forestguardian").orElseThrow());
        assertFalse(adapter.matchesMythicMobType(entityProxy(), "ForestGuardian").orElseThrow());
        assertFalse(adapter.isMythicMobType(entityProxy(), "ForestGuardian"));
        assertFalse(adapter.isMythicMobType(null, "ForestGuardian"));

        adapter.bindMythicMobLookup(new FakeApiHelper(Set.of(mythicMob)));
        assertFalse(adapter.isMythicMobType(mythicMob, "ForestGuardian"),
                "helpers without active mob type lookup must not retain stale bindings");
        assertFalse(adapter.hasMythicMobType("ForestGuardian"),
                "helpers without registry lookup must fail closed");
        assertTrue(adapter.matchesMythicMobType(mythicMob, "ForestGuardian").isEmpty(),
                "helpers without active-type lookup must report an unavailable query");
    }

    private static Entity entityProxy() {
        java.util.UUID id = java.util.UUID.randomUUID();
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "toString" -> "fixture-entity";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test void radiusLookupDoesNotResurrectPersistedEntitiesAndPreservesAliasValidity() throws Exception {
        var adapter = new MythicMobsAdapter(null, null, null);
        Entity entity = entityProxy();
        var registry = new ActiveRegistry();
        var helper = new ReloadingHelper(entity);
        adapter.bindMythicMobLookup(helper, registry);
        adapter.bindMythicEntityLookup(EntityTypes.class);
        assertTrue(adapter.hasRadiusType("BABY_ZOMBIE"));
        assertFalse(adapter.hasRadiusType("not-a-type"));
        assertFalse(adapter.hasMythicMobType("BABY_ZOMBIE"));
        assertFalse(adapter.matchesActiveRadiusType(entity, "ForestGuardian").orElseThrow());
        assertFalse(helper.instanceQueried, "unregistered persistent entity must not be reloaded");
        registry.active = java.util.Optional.of(new FakeActiveMob());
        assertTrue(adapter.matchesActiveRadiusType(entity, "forestguardian").orElseThrow());
        assertFalse(adapter.matchesActiveRadiusType(entity, "BABY_ZOMBIE").orElseThrow());
        registry.active = java.util.Optional.empty();
        assertFalse(adapter.matchesActiveRadiusType(entity, "ForestGuardian").orElseThrow());
        assertFalse(helper.instanceQueried);
        registry.fail = true;
        assertTrue(adapter.matchesActiveRadiusType(entity, "ForestGuardian").isEmpty());
        adapter.bindMythicMobLookup(helper); // Rebinding must discard both old registry and aliases.
        assertTrue(adapter.matchesActiveRadiusType(entity, "ForestGuardian").isEmpty());
        assertFalse(adapter.hasRadiusType("BABY_ZOMBIE"));
        assertThrows(IllegalStateException.class, () -> adapter.hasRadiusType("ForestGuardian"),
                "Missing active lookup must reject even before querying an empty scene");
        adapter.bindMythicEntityLookup(EntityTypes.class);
        assertThrows(IllegalStateException.class, () -> adapter.hasRadiusType("BABY_ZOMBIE"));
    }

    @Test void radiusAliasProviderFailuresAreDistinctFromUnknownTypes() throws Exception {
        var adapter = new MythicMobsAdapter(null, null, null);
        Entity entity = entityProxy();
        var registry = new ActiveRegistry();
        registry.active = java.util.Optional.of(new FakeActiveMob());
        adapter.bindMythicMobLookup(new TypedFakeApiHelper(entity), registry);
        adapter.bindMythicEntityLookup(FailingEntityTypes.class);
        assertThrows(IllegalStateException.class, () -> adapter.hasRadiusType("BABY_ZOMBIE"));
        assertThrows(NoSuchMethodException.class, () -> adapter.bindMythicEntityLookup(Object.class));
        assertFalse(adapter.hasRadiusType("BABY_ZOMBIE"));
        assertTrue(adapter.hasRadiusType("ForestGuardian"));
        assertTrue(adapter.matchesActiveRadiusType(entity, "ForestGuardian").orElseThrow());
    }

    public static class ActiveRegistry {
        java.util.Optional<FakeActiveMob> active = java.util.Optional.empty();
        boolean fail;
        public java.util.Optional<FakeActiveMob> getActiveMob(java.util.UUID id) {
            if (fail) throw new IllegalStateException("Registry unavailable");
            return active;
        }
    }

    public static class ReloadingHelper extends TypedFakeApiHelper {
        boolean instanceQueried;
        ReloadingHelper(Entity entity) { super(entity); }
        @Override public java.util.Optional<FakeActiveMob> getMythicMobInstance(Entity entity) {
            instanceQueried = true;
            return super.getMythicMobInstance(entity);
        }
    }

    public static class EntityTypes {
        public static Object getMythicEntity(String name) { return name.equals("BABY_ZOMBIE") ? new Object() : null; }
    }

    public static class FailingEntityTypes {
        public static Object getMythicEntity(String name) { throw new IllegalStateException("Unavailable"); }
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

    public static class TypedFakeApiHelper {
        private final Entity active;

        public TypedFakeApiHelper(Entity active) {
            this.active = active;
        }

        public boolean isMythicMob(Entity entity) {
            return entity == active;
        }

        public java.util.Optional<FakeActiveMob> getMythicMobInstance(Entity entity) {
            return entity == active ? java.util.Optional.of(new FakeActiveMob()) : java.util.Optional.empty();
        }

        public FakeMythicMobType getMythicMob(String name) {
            return "ForestGuardian".equals(name) ? new FakeMythicMobType() : null;
        }
    }

    public static class FakeMobRegistry {
        private final Set<String> names;

        public FakeMobRegistry(Set<String> names) {
            this.names = names;
        }

        public Set<String> getMobNames() {
            return names;
        }
    }

    public static class FakeActiveMob {
        public FakeMythicMobType getType() {
            return new FakeMythicMobType();
        }
    }

    public static class FakeMythicMobType {
        public String getInternalName() {
            return "ForestGuardian";
        }
    }
}
