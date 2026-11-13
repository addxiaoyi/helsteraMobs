package dev.helstera.integrations;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
import dev.helstera.api.integration.IntegrationContract;
import org.bukkit.plugin.Plugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationRegistryContractTest {
    @Test
    void rejectsAdaptersWithMissingOrMismatchedStaticContracts() {
        var registry = new IntegrationRegistryImpl();

        assertThrows(IllegalArgumentException.class, () -> registry.register(null));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new FakeAdapter(null, "1.x", true, false, null)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new FakeAdapter("Vault", "1.x", true, false,
                        new IntegrationContract("Other", "1.x", Set.of(), Set.of(), true, true))));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new FakeAdapter("Vault", "1.x", true, false,
                        new IntegrationContract("Vault", "2.x", Set.of(), Set.of(), true, true))));
    }

    @Test
    void isolatesEnableFailureAndKeepsRegisteredAdaptersImmutable() {
        var registry = new IntegrationRegistryImpl();
        var healthyEnabled = new AtomicBoolean();
        registry.register(new FakeAdapter("broken", "1.x", false, true, null));
        registry.register(new FakeAdapter("healthy", "1.x", true, false, null,
                () -> healthyEnabled.set(true)));

        int enabled = registry.enableAll(plugin("helsteraMobs"));

        assertEquals(1, enabled);
        org.junit.jupiter.api.Assertions.assertTrue(healthyEnabled.get());
        assertThrows(UnsupportedOperationException.class,
                () -> registry.all().clear());
    }

    @Test
    void claimQueriesAggregateOnlyKnownProviderAnswers() {
        var registry = new IntegrationRegistryImpl();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return "world";
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    if (method.getReturnType() == double.class) return 0.0;
                    if (method.getReturnType() == float.class) return 0.0f;
                    return null;
                });
        Location location = new Location(world, 1, 64, 1);

        registry.register(new ClaimAdapter("one", false, false));
        assertFalse(registry.inClaim(location));
        assertFalse(registry.nearClaim(location, 16));

        registry.register(new ClaimAdapter("two", null, null));
        assertNull(registry.inClaim(location), "an unknown provider must not become a negative match");
        assertNull(registry.nearClaim(location, 16));

        registry.register(new ClaimAdapter("three", true, true));
        assertTrue(registry.inClaim(location), "one positive provider is sufficient");
        assertTrue(registry.nearClaim(location, 16));
        assertNull(registry.nearClaim(location, Double.NaN));
        assertNull(registry.nearClaim(location, -1));
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getLogger")) return Logger.getLogger(name);
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                });
    }

    private static final class FakeAdapter implements IntegrationAdapter {
        private final String pluginName;
        private final String versions;
        private final boolean enableResult;
        private final boolean throwOnEnable;
        private final IntegrationContract contract;
        private final Runnable onEnable;

        private FakeAdapter(String pluginName, String versions, boolean enableResult,
                            boolean throwOnEnable, IntegrationContract contract) {
            this(pluginName, versions, enableResult, throwOnEnable, contract, () -> { });
        }

        private FakeAdapter(String pluginName, String versions, boolean enableResult,
                            boolean throwOnEnable, IntegrationContract contract, Runnable onEnable) {
            this.pluginName = pluginName;
            this.versions = versions;
            this.enableResult = enableResult;
            this.throwOnEnable = throwOnEnable;
            this.contract = contract == null && pluginName != null
                    ? new IntegrationContract(pluginName, versions, Set.of(Capability.EVENTS),
                    Set.of("test hook"), true, true) : contract;
            this.onEnable = onEnable;
        }

        @Override public String pluginName() { return pluginName; }
        @Override public String supportedVersions() { return versions; }
        @Override public IntegrationContract contract() { return contract; }
        @Override public Set<Capability> capabilities() { return Set.of(); }
        @Override public boolean isConnected() { return false; }
        @Override public String statusReport() { return "test"; }

        @Override
        public boolean enable(Plugin host) {
            onEnable.run();
            if (throwOnEnable) throw new IllegalStateException("test failure");
            return enableResult;
        }

        @Override public void disable() { }
    }

    private static final class ClaimAdapter implements IntegrationAdapter {
        private final String name;
        private final Boolean inClaim;
        private final Boolean nearClaim;
        private final String versions = "test";

        private ClaimAdapter(String name, Boolean inClaim, Boolean nearClaim) {
            this.name = name;
            this.inClaim = inClaim;
            this.nearClaim = nearClaim;
        }

        @Override public String pluginName() { return name; }
        @Override public String supportedVersions() { return versions; }
        @Override public IntegrationContract contract() {
            return new IntegrationContract(name, versions, Set.of(Capability.CLAIM_QUERY),
                    Set.of("test provider"), true, true);
        }
        @Override public Set<Capability> capabilities() { return Set.of(Capability.CLAIM_QUERY); }
        @Override public boolean isConnected() { return true; }
        @Override public String statusReport() { return null; }
        @Override public boolean enable(Plugin host) { return true; }
        @Override public void disable() { }
        @Override public Boolean inClaim(Location location) { return inClaim; }
        @Override public Boolean nearClaim(Location location, double radius) { return nearClaim; }
    }
}
