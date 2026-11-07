package dev.helstera.integrations;

import dev.helstera.api.integration.IntegrationAdapter;
import dev.helstera.api.integration.IntegrationContract;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 集成注册表实现：适配器错误隔离——单个适配器 enable 失败不影响其他与核心启动。
 */
public final class IntegrationRegistryImpl implements dev.helstera.api.integration.IntegrationRegistry {

    private final Map<String, IntegrationAdapter> adapters = new LinkedHashMap<>();

    @Override
    public void register(IntegrationAdapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter must not be null");
        String pluginName = adapter.pluginName();
        if (pluginName == null || pluginName.isBlank()) {
            throw new IllegalArgumentException("adapter pluginName must not be blank");
        }
        String supportedVersions = adapter.supportedVersions();
        if (supportedVersions == null || supportedVersions.isBlank()) {
            throw new IllegalArgumentException("adapter supportedVersions must not be blank");
        }
        IntegrationContract contract = adapter.contract();
        if (contract == null
                || !pluginName.equals(contract.pluginName())
                || !supportedVersions.equals(contract.supportedVersions())) {
            throw new IllegalArgumentException("adapter contract must match pluginName and supportedVersions");
        }
        adapters.put(pluginName, adapter);
    }

    @Override
    public Optional<IntegrationAdapter> get(String pluginName) {
        return Optional.ofNullable(adapters.get(pluginName));
    }

    @Override
    public Collection<IntegrationAdapter> all() {
        return java.util.List.copyOf(adapters.values());
    }

    @Override
    public int enableAll(Plugin host) {
        int ok = 0;
        for (IntegrationAdapter a : adapters.values()) {
            try {
                if (a.enable(host)) ok++;
            } catch (Throwable t) {
                host.getLogger().warning("适配器 " + a.pluginName() + " 启用失败（已隔离）: " + t.getMessage());
            }
        }
        return ok;
    }

    @Override
    public void disableAll() {
        for (IntegrationAdapter a : adapters.values()) {
            try {
                a.disable();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public String dependencyReport() {
        StringBuilder sb = new StringBuilder();
        for (IntegrationAdapter a : adapters.values()) {
            Plugin p = Bukkit.getPluginManager().getPlugin(a.pluginName());
            String ver = p == null ? "未安装" : p.getDescription().getVersion();
            IntegrationContract contract = a.contract();
            sb.append(String.format("· %s: %s（支持 %s）能力=%s%n",
                    a.pluginName(), a.isConnected() ? "已连接" : "未连接", ver,
                    a.capabilities()));
            sb.append("  └ 契约能力=").append(contract.declaredCapabilities())
                    .append(" 前置=").append(contract.prerequisites())
                    .append(" fail-closed=").append(contract.failClosed())
                    .append(" 保留源值=").append(contract.preservesSourceValues())
                    .append('\n');
            if (!a.isConnected() && a.statusReport() != null) {
                sb.append("  └ ").append(a.statusReport()).append('\n');
            }
        }
        if (adapters.isEmpty()) sb.append("（未注册任何适配器）");
        return sb.toString();
    }
}
