package dev.helstera.integrations;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
import dev.helstera.api.integration.IntegrationContract;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
