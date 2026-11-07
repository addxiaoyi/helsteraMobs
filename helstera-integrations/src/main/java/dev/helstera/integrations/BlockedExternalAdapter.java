package dev.helstera.integrations;

import dev.helstera.api.integration.IntegrationAdapter;
import dev.helstera.api.integration.IntegrationContract;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * Explicit status adapter for an external plugin whose runtime API is not
 * implemented yet. Registering the boundary makes the blocked state visible
 * in diagnostics without pretending that preserved identifiers are resolvable.
 */
public final class BlockedExternalAdapter implements IntegrationAdapter {
    private final String pluginName;
    private final String supportedVersions;
    private boolean connected;
    private String report;

    public BlockedExternalAdapter(String pluginName, String supportedVersions) {
        if (pluginName == null || pluginName.isBlank()) {
            throw new IllegalArgumentException("pluginName must not be blank");
        }
        this.pluginName = pluginName;
        this.supportedVersions = supportedVersions == null || supportedVersions.isBlank()
                ? "none verified; API bridge not implemented" : supportedVersions;
    }

    @Override public String pluginName() { return pluginName; }

    @Override public String supportedVersions() { return supportedVersions; }

    @Override
    public IntegrationContract contract() {
        return IntegrationContract.blocked(pluginName, supportedVersions,
                "external runtime API bridge");
    }

    @Override public Set<dev.helstera.api.integration.Capability> capabilities() {
        return Set.of();
    }

    @Override public boolean isConnected() { return connected; }

    @Override public String statusReport() { return report; }

    @Override
    public boolean enable(Plugin host) {
        disable();
        Plugin dependency = Bukkit.getPluginManager().getPlugin(pluginName);
        if (dependency == null || !dependency.isEnabled()) {
            report = pluginName + " 未安装或未启用；相关外部能力保持 blocked-by-external-plugin。";
        } else {
            report = "已检测到 " + pluginName + " " + dependency.getDescription().getVersion()
                    + "，但运行时 API 适配尚未实现；相关能力保持 blocked-by-external-plugin。";
        }
        if (host != null) host.getLogger().info(report);
        return false;
    }

    @Override public void disable() {
        connected = false;
        report = null;
    }
}
