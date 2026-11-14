package dev.helstera.test.vault;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/** Real Vault and EssentialsX provider integration probe. */
public final class VaultEssentialsProviderParityFixture extends JavaPlugin implements Listener {
    private static final String EXPECTED_PROVIDER =
            "com.earth2me.essentials.economy.vault.VaultEconomyProvider";
    private final Map<String, Boolean> checks = new LinkedHashMap<>();
    private boolean completed;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        if (Bukkit.getOnlinePlayers().size() == 1) {
            Bukkit.getScheduler().runTask(this, () -> runProbe(Bukkit.getOnlinePlayers().iterator().next()));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> runProbe(event.getPlayer()), 2L);
    }

    private void runProbe(Player player) {
        if (completed) return;
        completed = true;
        String vaultVersion = "missing";
        String essentialsVersion = "missing";
        String providerClass = "missing";
        double balanceBefore = Double.NaN;
        double expectedBalance = Double.NaN;
        double observedBalance = Double.NaN;
        try {
            var vault = Bukkit.getPluginManager().getPlugin("Vault");
            var essentials = Bukkit.getPluginManager().getPlugin("Essentials");
            var host = Bukkit.getPluginManager().getPlugin("helsteraMobs");
            if (vault != null) vaultVersion = vault.getDescription().getVersion();
            if (essentials != null) essentialsVersion = essentials.getDescription().getVersion();
            checks.put("vault_enabled", vault != null && vault.isEnabled());
            checks.put("essentials_enabled", essentials != null && essentials.isEnabled());
            checks.put("host_enabled", host != null && host.isEnabled());
            checks.put("real_player_online", player.isOnline());
            if (vault == null || essentials == null || host == null
                    || !vault.isEnabled() || !essentials.isEnabled() || !host.isEnabled()) {
                throw new IllegalStateException("Vault, EssentialsX, or Helstera is not enabled");
            }

            Class<?> economyType = Class.forName("net.milkbowl.vault.economy.Economy", true,
                    vault.getClass().getClassLoader());
            @SuppressWarnings({"rawtypes", "unchecked"})
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager()
                    .getRegistration((Class) economyType);
            Object provider = registration == null ? null : registration.getProvider();
            providerClass = provider == null ? "missing" : provider.getClass().getName();
            checks.put("provider_registered", provider != null);
            checks.put("essentials_provider", EXPECTED_PROVIDER.equals(providerClass));
            if (provider == null) throw new IllegalStateException("Vault Economy provider was not registered");

            Object registry = host.getClass().getMethod("integrationRegistry").invoke(host);
            Object optional = registry.getClass().getMethod("get", String.class).invoke(registry, "Vault");
            Object adapter = ((Optional<?>) optional).orElseThrow(
                    () -> new IllegalStateException("Helstera Vault adapter was not registered"));
            boolean connected = (boolean) adapter.getClass().getMethod("isConnected").invoke(adapter);
            String capabilities = String.valueOf(adapter.getClass().getMethod("capabilities").invoke(adapter));
            checks.put("adapter_connected", connected);
            checks.put("economy_capability", capabilities.contains("ECONOMY_BALANCE"));

            Method deposit = Arrays.stream(economyType.getMethods())
                    .filter(method -> method.getName().equals("depositPlayer")
                            && method.getParameterCount() == 2
                            && method.getParameterTypes()[0].isAssignableFrom(player.getClass())
                            && method.getParameterTypes()[1] == double.class)
                    .findFirst().orElseThrow(() -> new NoSuchMethodException("depositPlayer(OfflinePlayer, double)"));
            balanceBefore = (double) economyType.getMethod("getBalance", org.bukkit.OfflinePlayer.class)
                    .invoke(provider, player);
            Object depositResponse = deposit.invoke(provider, player, 73.5d);
            String responseType = String.valueOf(depositResponse.getClass().getField("type")
                    .get(depositResponse));
            expectedBalance = balanceBefore + 73.5d;

            Method balance = adapter.getClass().getMethod("balance", Player.class);
            OptionalDouble observed = (OptionalDouble) balance.invoke(adapter, player);
            if (observed.isPresent()) observedBalance = observed.getAsDouble();
            checks.put("deposit_success", "SUCCESS".equals(responseType));
            checks.put("real_balance_updated", observed.isPresent()
                    && Math.abs(observed.getAsDouble() - expectedBalance) < 1.0e-7);

            String positive = bindCondition(host, "has-currency >=" + format(expectedBalance));
            String negative = bindCondition(host, "has-currency >" + format(expectedBalance));
            Object context = playerContext(host, player);
            Object behaviorRegistry = host.getClass().getMethod("behaviorRegistry").invoke(host);
            Method testCondition = Arrays.stream(behaviorRegistry.getClass().getMethods())
                    .filter(method -> method.getName().equals("testCondition")
                            && method.getParameterCount() == 2
                            && method.getParameterTypes()[0] == String.class)
                    .findFirst().orElseThrow(() -> new NoSuchMethodException("testCondition"));
            checks.put("condition_positive_pass", (boolean) testCondition.invoke(
                    behaviorRegistry, positive, context));
            checks.put("condition_negative_reject", !(boolean) testCondition.invoke(
                    behaviorRegistry, negative, context));
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            getLogger().severe("Vault provider probe failed: " + root.getClass().getSimpleName()
                    + ": " + root.getMessage());
        } finally {
            StringBuilder line = new StringBuilder("VAULT_ESSENTIALS_PROVIDER_PROBE")
                    .append(" fixture=installed-provider")
                    .append(" vault-version=").append(vaultVersion)
                    .append(" essentials-version=").append(essentialsVersion)
                    .append(" provider-class=").append(providerClass)
                    .append(" balance-before=").append(format(balanceBefore))
                    .append(" balance-expected=").append(format(expectedBalance))
                    .append(" balance-observed=").append(format(observedBalance))
                    .append(" checks=").append(checks.size());
            checks.forEach((name, passed) -> line.append(' ').append(name).append('=').append(passed));
            boolean passed = !checks.isEmpty() && checks.values().stream().allMatch(Boolean::booleanValue);
            line.append(" result=").append(passed ? "passed" : "failed");
            getLogger().info(line.toString());
            Bukkit.shutdown();
        }
    }

    private String bindCondition(org.bukkit.plugin.Plugin host, String source) throws Exception {
        Object skills = host.getClass().getMethod("skillService").invoke(host);
        Object key = skills.getClass().getMethod("bindCondition", String.class).invoke(skills, source);
        if (!(key instanceof String condition) || condition.isBlank()) {
            throw new IllegalStateException("Could not bind " + source);
        }
        return condition;
    }

    private Object playerContext(org.bukkit.plugin.Plugin host, Player player) throws Exception {
        ClassLoader loader = host.getClass().getClassLoader();
        Class<?> contextType = Class.forName("dev.helstera.api.behavior.BehaviorContext", true, loader);
        Method factory = Arrays.stream(contextType.getMethods())
                .filter(method -> method.getName().equals("of") && method.getParameterCount() == 6
                        && method.getParameterTypes()[1].isAssignableFrom(Player.class))
                .findFirst().orElseThrow(() -> new NoSuchMethodException("BehaviorContext.of"));
        return factory.invoke(null, null, player, 1.0, -1.0, 0L, "IDLE");
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) return "unavailable";
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
