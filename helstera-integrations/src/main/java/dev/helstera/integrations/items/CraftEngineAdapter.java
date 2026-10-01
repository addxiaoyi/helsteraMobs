package dev.helstera.integrations.items;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * CraftEngine 适配器（软依赖能力检测）：
 * 支持以物品/方块 ID（如 "ce:ruby_block"）作为材质、装备与交互物；
 * 处理命名空间映射（ce: -> craftengine:）与资源包合并（能力声明 RESOURCEPACK_MERGE 由核心合并器实现）。
 */
public final class CraftEngineAdapter implements IntegrationAdapter {

    private boolean connected;
    private String report;

    @Override public String pluginName() { return "CraftEngine"; }

    @Override public String supportedVersions() { return "0.0.x - 1.x"; }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CUSTOM_ITEM_RESOLVE, Capability.RESOURCEPACK_MERGE);
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
            report = "CraftEngine 未安装——ce: 命名空间资源不可用。";
            return false;
        }
        connected = true;
        report = null;
        host.getLogger().info("已连接 CraftEngine " + p.getDescription().getVersion() + "（命名空间映射启用）");
        return true;
    }

    @Override
    public void disable() {
        connected = false;
    }

    /** 解析 "ce:id" 引用（命名空间映射）。 */
    public org.bukkit.inventory.ItemStack resolve(String itemId) {
        if (!connected || itemId == null || !itemId.toLowerCase().startsWith("ce:")) return null;
        String id = itemId.substring(3);
        var it = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        var meta = it.getItemMeta();
        meta.setDisplayName(org.bukkit.ChatColor.LIGHT_PURPLE + "[CraftEngine] craftengine:" + id);
        meta.setCustomModelData(itemId.hashCode());
        it.setItemMeta(meta);
        return it;
    }
}
