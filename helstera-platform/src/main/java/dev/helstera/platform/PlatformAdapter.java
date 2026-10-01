package dev.helstera.platform;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * 平台适配：Paper/Purpur 直接支持；Folia 检测后降级警告（Folia 支持为后续版本目标）。
 * 版本适配集中在此模块，其他模块不得直接触碰 NMS。
 */
public final class PlatformAdapter {

    private PlatformAdapter() {
    }

    public enum Platform {PAPER, PURPUR, FOLIA, SPIGOT, UNKNOWN}

    public static Platform detect(Plugin plugin) {
        try {
            Class.forName("io.papermc.paper.PaperBootstrap");
            try {
                Class.forName("org.purpurmc.purpur.PurpurConfig");
                return Platform.PURPUR;
            } catch (ClassNotFoundException e) {
                return Platform.PAPER;
            }
        } catch (ClassNotFoundException ignored) {
        }
        try {
            if (Bukkit.getVersion().toLowerCase().contains("folia")) {
                return Platform.FOLIA;
            }
        } catch (Throwable ignored) {
        }
        if (Bukkit.getVersion().toLowerCase().contains("spigot")) {
            return Platform.SPIGOT;
        }
        return Platform.UNKNOWN;
    }

    /** 启动时平台检查；Folia 返回 false 表示本版不支持。 */
    public static boolean verify(Plugin plugin, StringBuilder summary) {
        Platform p = detect(plugin);
        summary.append("平台: ").append(p).append(" / ").append(Bukkit.getVersion()).append('\n');
        if (p == Platform.FOLIA) {
            plugin.getLogger().warning("检测到 Folia：当前版本未适配 Folia 线程模型，插件可能运行异常（后续版本支持）。");
            return false;
        }
        if (p == Platform.SPIGOT || p == Platform.UNKNOWN) {
            plugin.getLogger().warning("检测到非 Paper/Purpur 服务端：Display 实体行为可能受限，推荐使用 Paper 1.21.1+。");
        }
        return true;
    }
}
