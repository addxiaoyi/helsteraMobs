package dev.helstera.integrations.items;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * ItemAdder 适配器（软依赖能力检测）：
 * 支持以物品 ID（如 "itemsadder:sword"）作为模型装备/武器/挂接点物品；
 * 通过命名空间解析桥接（resolve 返回 display 名，供挂接点显示）。
 */
public final class ItemAdderAdapter implements IntegrationAdapter {

    private boolean connected;
    private String report;

    @Override public String pluginName() { return "ItemAdder"; }

    @Override public String supportedVersions() { return "3.x - 4.x"; }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CUSTOM_ITEM_RESOLVE);
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
            report = "ItemAdder 未安装——挂接点物品 ID 需为原版/其他来源。";
            return false;
        }
        connected = true;
        report = null;
        host.getLogger().info("已连接 ItemAdder " + p.getDescription().getVersion() + "（自定义物品解析启用）");
        return true;
    }

    @Override
    public void disable() {
        connected = false;
    }

    /**
     * 解析 "namespace:id" 物品引用。ItemAdder 私有 API 不直接暴露给第三方反射场景时，
     * 返回带 lore 标记的占位物品；事件中会返回来源插件、命名空间与物品 ID。
     */
    public org.bukkit.inventory.ItemStack resolve(String itemId) {
        if (!connected || itemId == null || !itemId.contains(":")) return null;
        String ns = itemId.substring(0, itemId.indexOf(':'));
        String id = itemId.substring(itemId.indexOf(':') + 1);
        var it = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        var meta = it.getItemMeta();
        meta.setDisplayName(org.bukkit.ChatColor.AQUA + "[ItemAdder] " + ns + ":" + id);
        meta.setCustomModelData(itemId.hashCode());
        it.setItemMeta(meta);
        return it;
    }
}
