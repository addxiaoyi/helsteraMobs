package dev.helstera.render.display;

import org.bukkit.Bukkit;

import java.util.logging.Logger;

/**
 * 运行时 Minecraft 版本探测与 Display 相关能力判定。
 *
 * <p>1.21.1 → 26.x 跨越多代渲染变更，且 Bukkit 没有官方多版本机制。
 * 与其按版本号硬编码分支，不如按<b>实际可用能力</b>探测——能力探测对
 * 未来版本同样有效，不依赖版本号猜测。</p>
 *
 * <p>探测策略（按可靠性排序，先命中先用）：</p>
 * <ol>
 *   <li>客户端 jar 的 {@code version.json} 无法在服务端读取，故不用；</li>
 *   <li>Paper 服务端内部暴露的资源包格式号，取真实运行值；</li>
 *   <li>关键 API 方法是否存在于当前 {@code Display} 类；</li>
 *   <li>版本号前缀兜底。</li>
 * </ol>
 *
 * <p>所有方法只读、无副作用，主线程启动期调用一次即可。</p>
 */
public final class VersionAdapter {

    private VersionAdapter() {
    }

    private static volatile boolean initialized;
    private static volatile String version = "";
    private static volatile int mcMajor;
    private static volatile int mcMinor;
    private static volatile int packFormat = -1;
    private static volatile boolean teleportAsync;
    private static volatile boolean transformationMatrix;
    private static volatile boolean shadowRadius;
    private static volatile boolean viewRange;
    private static final Logger LOG = Logger.getLogger("helstera.render");

    /** 启动时探测一次。重复调用无副作用。 */
    public static synchronized void init(Logger log) {
        if (initialized) return;
        version = rawVersion();
        parse(version);
        teleportAsync = hasMethod("teleportAsync", org.bukkit.Location.class);
        transformationMatrix = hasMethod("setTransformationMatrix", org.joml.Matrix4f.class);
        shadowRadius = hasMethod("setShadowRadius", float.class);
        viewRange = hasMethod("setViewRange", float.class);
        initialized = true;
        if (log != null) {
            log.info("[渲染] 版本自适应: MC=" + (version.isBlank() ? "(未知)" : version)
                    + " teleportAsync=" + teleportAsync
                    + " transformationMatrix=" + transformationMatrix
                    + " shadowRadius=" + shadowRadius
                    + " viewRange=" + viewRange);
        }
    }

    /** 探测结果是否可用；不可用时渲染层应走降级路径。 */
    public static boolean ready() {
        return initialized;
    }

    public static String version() {
        return version;
    }

    public static int major() {
        return mcMajor;
    }

    public static int minor() {
        return mcMinor;
    }

    /** 是否为 26.x 年份编号系列。 */
    public static boolean yearNumbered() {
        return mcMajor >= 26;
    }

    /** 1.21.x 系列（含 1.21.1）。 */
    public static boolean is121x() {
        return mcMajor == 1 && mcMinor == 21;
    }

    // 刻意不提供 serverPackFormat()：它需要反射 Paper 内部版本类，而那套 API
    // 跨版本改名频繁，实测在 1.21.11 上恒定失败。日志里一个恒为 -1 的值会被
    // 误读成「资源包格式号没解析出来」。真实解析由 PackFormats 按服务器版本查表完成。

    public static boolean canTeleportAsync() {
        return teleportAsync;
    }

    public static boolean canSetTransformationMatrix() {
        return transformationMatrix;
    }

    public static boolean canSetShadowRadius() {
        return shadowRadius;
    }

    public static boolean canSetViewRange() {
        return viewRange;
    }

    /** 供 /helstera debug 展示。 */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("MC=").append(version.isBlank() ? "(未知)" : version);
        sb.append(" 年份编号=").append(yearNumbered());
        sb.append(" | 能力: async=").append(teleportAsync)
                .append(" matrix=").append(transformationMatrix)
                .append(" shadow=").append(shadowRadius)
                .append(" viewRange=").append(viewRange);
        return sb.toString();
    }

    private static String rawVersion() {
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

    private static void parse(String v) {
        if (v == null || v.isBlank()) return;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)").matcher(v.trim());
        if (m.find()) {
            try {
                mcMajor = Integer.parseInt(m.group(1));
                mcMinor = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static boolean hasMethod(String name, Class<?>... params) {
        try {
            Class<?> display = Class.forName("org.bukkit.entity.Display");
            display.getMethod(name, params);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 记录一次能力缺失，供调试定位。不在热路径调用。 */
    public static void warnMissing(String capability, Logger log) {
        if (log != null) {
            log.warning("[渲染] 当前 MC " + version + " 不支持 " + capability
                    + "，已按降级路径处理。");
        }
    }

    static {
        // 未显式 init 时先做一次尽力探测，保证单元/离线场景不 NPE。
        version = rawVersion();
        parse(version);
    }
}
