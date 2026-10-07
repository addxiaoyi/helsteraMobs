package dev.helstera.ai.bossbar;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Boss 血条配置解析（{@code mobs/<档案>.yml} 的 {@code bossbar} 节）。
 *
 * <p>与 {@code DropTable.parse} 同构：非法条目跳过并记入 problems，
 * 而不是让整份档案加载失败——一张表里写错一行不该让怪物完全失去血条。</p>
 */
public final class BossBarConfig {

    private BossBarConfig() {
    }

    /** 解析后的血条配置。 */
    public record Parsed(boolean enabled, String title, double range,
                         List<BossBarState.Segment> segments,
                         boolean gradient,
                         List<String> problems) {

        public boolean hasSegments() {
            return segments != null && !segments.isEmpty();
        }
    }

    /**
     * 血条的默认配置：启用、无标题、无限距离、无配色。
     *
     * <p>供 {@code show-health-bar: true} 这类快捷开关使用——用户只想看到一条
     * 血条，不打算写 {@code bossbar} 节。没有它，快捷开关命中的实例会因
     * {@code bossBar == null} 被当成「未配置」而跳过，得到的是「开关没生效」的错觉。</p>
     */
    public static Parsed defaults() {
        return new Parsed(true, null, 0, List.of(), false, List.of());
    }

    /**
     * 从档案节解析血条配置。
     *
     * @param sec {@code bossbar} 节；null 表示未配置
     * @param problems 装载期告警收集器
     */
    public static Parsed parse(ConfigurationSection sec, List<String> problems) {
        if (sec == null) {
            return new Parsed(false, null, 0, List.of(), false, List.of());
        }
        boolean enabled = sec.getBoolean("enabled", false);
        String title = sec.getString("title");
        double range = Math.max(0, sec.getDouble("range", 0));

        List<BossBarState.Segment> segments = new ArrayList<>();
        // 分段配色：thresholds 与 colors 必须一一对应
        List<Double> thresholds = sec.getDoubleList("thresholds");
        List<String> colors = sec.getStringList("colors");
        if (!thresholds.isEmpty() || !colors.isEmpty()) {
            if (thresholds.size() != colors.size()) {
                problems.add("bossbar 的 thresholds 与 colors 数量不匹配，已忽略分段配色");
            } else {
                for (int i = 0; i < thresholds.size(); i++) {
                    segments.add(new BossBarState.Segment(
                            BossBarState.clamp01(thresholds.get(i)), colors.get(i), null));
                }
            }
        }
        boolean gradient = sec.getBoolean("gradient", false);
        if (gradient && segments.size() < 2) {
            // 单档无区间可插值，静默忽略比留一个永远等于该档的开关更诚实
            problems.add("bossbar 开了 gradient 但可用档位少于 2，已按分段处理");
            gradient = false;
        }
        return new Parsed(enabled, title, range, List.copyOf(segments), gradient, List.of());
    }
}