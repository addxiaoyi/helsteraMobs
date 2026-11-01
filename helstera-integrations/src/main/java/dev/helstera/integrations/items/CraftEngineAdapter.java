package dev.helstera.integrations.items;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * CraftEngine availability probe. No CraftEngine item or resource pack bridge is implemented yet.
 */
public final class CraftEngineAdapter implements IntegrationAdapter {

    private boolean connected;
    private String report;

    @Override public String pluginName() { return "CraftEngine"; }

    @Override public String supportedVersions() { return "none verified; API bridge not implemented"; }

    @Override
    public Set<Capability> capabilities() {
        return Set.of();
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String statusReport() {
        return report;
    }

    @Override
    public boolean enable(Plugin host) {
        Plugin p = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (p == null || !p.isEnabled()) {
            report = "CraftEngine 未安装；CraftEngine 物品和资源包能力不可用。";
            return false;
        }
        connected = false;
        report = "已检测到 CraftEngine " + p.getDescription().getVersion()
                + "，但物品和资源包 API 适配尚未实现。";
        host.getLogger().info(report);
        return false;
    }

    @Override
    public void disable() {
        connected = false;
    }

    /** No placeholder item is returned because it would falsely claim parity. */
    public org.bukkit.inventory.ItemStack resolve(String itemId) {
        return null;
    }
}
