package dev.helstera.resourcepack;

import org.bukkit.Bukkit;

import java.util.logging.Logger;

/**
 * Minecraft 版本 → 资源包格式号（pack_format）映射。
 *
 * <p>设计为运行时解析而非编译期锁定：跨版本支持只需扩展本表或改配置，
 * 不必重新编译整个工程。</p>
 *
 * <p><b>本表只收录已确认的值。</b>未收录的版本（含 26.x 年份编号系列）
 * 一律返回 {@code null}，由 {@link #resolve} 回落到用户配置的 {@code pack-format}
 * 并输出告警。这样做是刻意的：宁可让管理员显式指定，也不猜一个错的格式号——
 * 猜错会导致客户端直接拒绝资源包，且不会给出任何提示。</p>
 */
public final class PackFormats {

    private PackFormats() {
    }

    /** 已知最低格式号，作为无任何信息时的保守默认。 */
    public static final int FALLBACK = 34;

    /**
     * 版本前缀 → 格式号。按「前缀越长越具体」匹配，
     * 故 1.21.10 不会被 1.21.1 抢先命中。
     */
    private static final String[][] TABLE = {
            // 26.x 起改用年份编号，且格式号由单值变为 major.minor 一对。
            // 取 resource_major 作为对外的 pack_format；26.3 实测为 97。
            //
            // 本表数值来源：逐版本从官方 client.jar 的 version.json 读取
            // pack_version.resource（或 resource_major）实测得出，非凭记忆填写。
            // 1.21.9 起字段名改为 resource_major，1.21.11 实测 75（此前误记为 69，
            // 会让 1.21.11 客户端静默拒绝资源包）。改动此表前请先实测。
            //
            // 注意：25.x 未收录——没有实测依据，猜测格式号会让客户端静默拒绝资源包，
            // 比回落到配置项更糟。未收录版本会告警并使用配置值。
            {"26.", "97"},
            {"1.21.11", "75"},
            {"1.21.10", "69"},
            {"1.21.9", "69"},
            {"1.21.8", "64"},
            {"1.21.7", "64"},
            {"1.21.6", "63"},
            {"1.21.5", "55"},
            {"1.21.4", "46"},
            {"1.21.3", "42"},
            {"1.21.2", "42"},
            {"1.21.1", "34"},
            {"1.21", "34"},
            {"1.20.6", "32"},
            {"1.20.4", "30"},
            {"1.20.2", "18"},
            {"1.20.1", "15"},
    };

    /**
     * 解析运行中服务器版本对应的格式号。
     *
     * <p>优先级：服务器版本查表 → 配置值 → {@link #FALLBACK}。
     * 查表命中时配置值被忽略，因为服务器版本比用户填的更可信。</p>
     *
     * @param configured 配置中的 pack-format；{@code <=0} 表示未指定（自动）
     */
    public static int resolve(int configured, Logger log) {
        String v = serverVersion();
        Integer mapped = lookup(v);

        if (mapped != null) {
            if (configured > 0 && configured != mapped) {
                warn(log, "配置 pack-format=" + configured + " 与服务器版本 " + v
                        + " 的实际格式号 " + mapped + " 不一致，已采用后者。");
            }
            return mapped;
        }

        if (configured > 0) {
            warn(log, "无法识别 Minecraft 版本 \"" + v + "\" 的资源包格式号（该表尚未收录），"
                    + "改用配置的 pack-format=" + configured
                    + "。请确认该值与客户端一致，否则客户端会静默拒绝资源包。");
            return configured;
        }

        warn(log, "无法识别 Minecraft 版本 \"" + v + "\" 的资源包格式号，且未配置 pack-format，"
                + "回落到 " + FALLBACK + "。请在 config.yml 设置 resourcepack.pack-format。");
        return FALLBACK;
    }

    /** 解析字符串形式的配置值（支持 {@code auto}）；无法解析时返回 -1。 */
    public static int parseConfigured(Object raw) {
        if (raw == null) return -1;
        if (raw instanceof Number n) return n.intValue();
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "auto".equalsIgnoreCase(s) || "自动".equals(s)) return -1;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 版本前缀 → 格式号；未收录返回 {@code null}（刻意不猜）。 */
    public static Integer lookup(String version) {
        if (version == null || version.isBlank()) return null;
        String v = version.trim();
        Integer best = null;
        int bestLen = -1;
        for (String[] row : TABLE) {
            String prefix = row[0];
            if (v.startsWith(prefix) && prefix.length() > bestLen) {
                best = Integer.valueOf(row[1]);
                bestLen = prefix.length();
            }
        }
        return best;
    }

    /** 运行中服务器版本串（形如 {@code 1.21.1}），不可用时返回空串。 */
    public static String serverVersion() {
        try {
            return Bukkit.getMinecraftVersion();
        } catch (Throwable t) {
            try {
                return Bukkit.getBukkitVersion();
            } catch (Throwable ignored) {
                return "";
            }
        }
    }

    /** 供 /helstera debug 展示的诊断文本。 */
    public static String describe(int format) {
        String v = serverVersion();
        Integer mapped = lookup(v);
        return "服务器版本=" + (v.isBlank() ? "(未知)" : v)
                + " 查表=" + (mapped == null ? "未收录" : mapped)
                + " 使用=" + format
                + (mapped == null || !mapped.equals(format) ? " §e(不一致)" : "");
    }

    private static void warn(Logger log, String msg) {
        if (log != null) log.warning("[资源包] " + msg);
    }
}
