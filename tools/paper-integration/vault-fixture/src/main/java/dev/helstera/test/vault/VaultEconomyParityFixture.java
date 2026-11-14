package dev.helstera.test.vault;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Paper probe for the Vault Economy discovery contract.
 *
 * <p>This plugin deliberately implements only the documented service shape and
 * is not the Vault distribution or a compatibility claim for a real provider.</p>
 */
public final class VaultEconomyParityFixture extends JavaPlugin {
    private final Map<String, Boolean> checks = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        Economy provider = player -> {
            String name = player == null ? null : player.getName();
            if ("vault-throws".equals(name)) throw new IllegalStateException("synthetic provider failure");
            if ("vault-infinite".equals(name)) return Double.POSITIVE_INFINITY;
            if ("vault-rich".equals(name)) return 73.5;
            if ("vault-poor".equals(name)) return 5.25;
            return 0.0;
        };
        Bukkit.getServicesManager().register(Economy.class, provider, this, ServicePriority.Normal);
        Bukkit.getScheduler().runTaskLater(this, this::runProbe, 1L);
    }

    private void runProbe() {
        String version = "missing";
        try {
            var vault = Bukkit.getPluginManager().getPlugin("Vault");
            var host = Bukkit.getPluginManager().getPlugin("helsteraMobs");
            if (vault != null) version = vault.getDescription().getVersion();
            checks.put("vault_enabled", vault != null && vault.isEnabled());
            checks.put("host_enabled", host != null && host.isEnabled());
            checks.put("softdepend_order", vault != null && host != null
                    && vault.isEnabled() && host.isEnabled());
            if (host == null || !host.isEnabled()) throw new IllegalStateException("helsteraMobs is not enabled");

            Object integrationRegistry = host.getClass().getMethod("integrationRegistry").invoke(host);
            Object optional = integrationRegistry.getClass().getMethod("get", String.class)
                    .invoke(integrationRegistry, "Vault");
            Object adapter = ((Optional<?>) optional).orElseThrow(
                    () -> new IllegalStateException("Vault adapter was not registered"));
            boolean connected = (boolean) adapter.getClass().getMethod("isConnected").invoke(adapter);
            String capabilities = String.valueOf(adapter.getClass().getMethod("capabilities").invoke(adapter));
            checks.put("adapter_connected", connected);
            checks.put("economy_capability", capabilities.contains("ECONOMY_BALANCE"));

            Method balance = adapter.getClass().getMethod("balance", Player.class);
            OptionalDouble rich = readBalance(balance, adapter, "vault-rich");
            OptionalDouble poor = readBalance(balance, adapter, "vault-poor");
            OptionalDouble failed = readBalance(balance, adapter, "vault-throws");
            OptionalDouble infinite = readBalance(balance, adapter, "vault-infinite");
            checks.put("rich_balance", rich.isPresent() && Math.abs(rich.getAsDouble() - 73.5) < 1.0e-9);
            checks.put("poor_balance", poor.isPresent() && Math.abs(poor.getAsDouble() - 5.25) < 1.0e-9);
            checks.put("provider_exception_empty", failed.isEmpty());
            checks.put("non_finite_empty", infinite.isEmpty());
            checks.put("null_player_empty", ((OptionalDouble) balance.invoke(adapter, new Object[]{null})).isEmpty());

            String richCondition = bindCondition(host, "has-currency >=50");
            String poorCondition = bindCondition(host, "has-currency >=50");
            String underThresholdCondition = bindCondition(host, "has-currency <50");
            Object richContext = playerContext("vault-rich");
            Object poorContext = playerContext("vault-poor");
            Object behaviorRegistry = host.getClass().getMethod("behaviorRegistry").invoke(host);
            Method testCondition = Arrays.stream(behaviorRegistry.getClass().getMethods())
                    .filter(method -> method.getName().equals("testCondition")
                            && method.getParameterCount() == 2
                            && method.getParameterTypes()[0] == String.class)
                    .findFirst().orElseThrow(() -> new NoSuchMethodException("testCondition"));
            checks.put("condition_rich_pass", (boolean) testCondition.invoke(
                    behaviorRegistry, richCondition, richContext));
            checks.put("condition_poor_reject", !(boolean) testCondition.invoke(
                    behaviorRegistry, poorCondition, poorContext));
            checks.put("condition_below_reject", !(boolean) testCondition.invoke(
                    behaviorRegistry, underThresholdCondition, richContext));
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            getLogger().severe("Vault contract probe failed: " + root.getClass().getSimpleName()
                    + ": " + root.getMessage());
        } finally {
            StringBuilder line = new StringBuilder("VAULT_ECONOMY_PARITY_PROBE fixture=synthetic-contract")
                    .append(" vault-version=").append(version)
                    .append(" checks=").append(checks.size());
            checks.forEach((name, passed) -> line.append(' ').append(name).append('=').append(passed));
            boolean passed = !checks.isEmpty() && checks.values().stream().allMatch(Boolean::booleanValue);
            line.append(" result=").append(passed ? "passed" : "failed");
            getLogger().info(line.toString());
            Bukkit.shutdown();
        }
    }

    private OptionalDouble readBalance(Method balance, Object adapter, String name) throws Exception {
        return (OptionalDouble) balance.invoke(adapter, player(name));
    }

    private String bindCondition(org.bukkit.plugin.Plugin host, String source) throws Exception {
        Object skills = host.getClass().getMethod("skillService").invoke(host);
        Object key = skills.getClass().getMethod("bindCondition", String.class).invoke(skills, source);
        if (!(key instanceof String condition) || condition.isBlank()) {
            throw new IllegalStateException("Could not bind " + source);
        }
        return condition;
    }

    private Object playerContext(String name) throws Exception {
        ClassLoader loader = getServer().getPluginManager().getPlugin("helsteraMobs")
                .getClass().getClassLoader();
        Class<?> contextType = Class.forName("dev.helstera.api.behavior.BehaviorContext", true, loader);
        Method factory = Arrays.stream(contextType.getMethods())
                .filter(method -> method.getName().equals("of") && method.getParameterCount() == 6
                        && method.getParameterTypes()[1].isAssignableFrom(Player.class))
                .findFirst().orElseThrow(() -> new NoSuchMethodException("BehaviorContext.of"));
        return factory.invoke(null, null, player(name), 1.0, -1.0, 0L, "IDLE");
    }

    private static Player player(String name) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getUniqueId" -> UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
            case "isValid" -> true;
            case "isDead" -> false;
            case "toString" -> "SyntheticPlayer[" + name + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            default -> defaultValue(method.getReturnType());
        };
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        return null;
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
    }
}
