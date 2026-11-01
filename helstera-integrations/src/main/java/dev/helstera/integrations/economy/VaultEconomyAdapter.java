package dev.helstera.integrations.economy;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.OptionalDouble;
import java.util.Set;

/** Optional Vault Economy balance lookup without a hard Vault API linkage. */
public final class VaultEconomyAdapter implements IntegrationAdapter {
    private static final String ECONOMY_TYPE = "net.milkbowl.vault.economy.Economy";

    private Object economy;
    private Method getBalance;
    private boolean offlinePlayerArgument;
    private String report;

    @Override public String pluginName() { return "Vault"; }
    @Override public String supportedVersions() {
        return "Vault Economy service contract; installed Vault/provider versions not CI-verified";
    }
    @Override public Set<Capability> capabilities() {
        return isConnected() ? Set.of(Capability.ECONOMY_BALANCE) : Set.of();
    }
    @Override public boolean isConnected() { return economy != null && getBalance != null; }
    @Override public String statusReport() { return report; }

    @Override
    public boolean enable(Plugin host) {
        economy = null;
        getBalance = null;
        offlinePlayerArgument = false;
        report = null;
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            report = "Vault 未安装；HasCurrency 条件将 fail-closed。";
            return false;
        }
        try {
            Class<?> economyType = Class.forName(ECONOMY_TYPE, true, vault.getClass().getClassLoader());
            org.bukkit.plugin.RegisteredServiceProvider<?> registration =
                    Bukkit.getServicesManager().getRegistration(economyType);
            if (registration == null) {
                report = "Vault 已安装，但没有注册 Economy provider；HasCurrency 条件将 fail-closed。";
                return false;
            }
            economy = registration.getProvider();
            if (economy == null) {
                report = "Vault Economy registration 未提供服务；HasCurrency 条件将 fail-closed。";
                return false;
            }
            try {
                getBalance = economyType.getMethod("getBalance", OfflinePlayer.class);
                offlinePlayerArgument = true;
            } catch (NoSuchMethodException ignored) {
                getBalance = economyType.getMethod("getBalance", String.class);
                offlinePlayerArgument = false;
            }
            report = "已连接 Vault Economy provider " + economy.getClass().getName() + ".";
            host.getLogger().info(report);
            return true;
        } catch (ReflectiveOperationException | LinkageError exception) {
            economy = null;
            getBalance = null;
            report = "Vault Economy API 不兼容；HasCurrency 条件将 fail-closed: "
                    + exception.getClass().getSimpleName();
            host.getLogger().warning(report);
            return false;
        }
    }

    public OptionalDouble balance(Player player) {
        if (!isConnected() || player == null) return OptionalDouble.empty();
        try {
            Object value = getBalance.invoke(economy, offlinePlayerArgument ? player : player.getName());
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(number.doubleValue());
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return OptionalDouble.empty();
        }
    }

    @Override
    public void disable() {
        economy = null;
        getBalance = null;
        report = null;
    }
}
