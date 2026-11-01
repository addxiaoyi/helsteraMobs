package dev.helstera.integrations.items;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * ItemAdder availability probe. No ItemAdder item API bridge is implemented yet.
 */
public final class ItemAdderAdapter implements IntegrationAdapter {

    private boolean connected;
    private String report;

    @Override public String pluginName() { return "ItemAdder"; }

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
        Plugin p = Bukkit.getPluginManager().getPlugin("ItemAdder");
        if (p == null || !p.isEnabled()) {
            report = "ItemAdder 未安装；ItemAdder 物品解析不可用。";
            return false;
        }
        connected = false;
        report = "已检测到 ItemAdder " + p.getDescription().getVersion()
                + "，但真实物品 API 适配尚未实现；相关物品保持未解析。";
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
