package dev.helstera.ai.bossbar;

import java.util.ArrayList;
import java.util.List;

/**
 * Boss 血条的<b>决策层</b>：把「当前血量、阶段、读条」换算成一条血条该显示什么。
 *
 * <p>刻意与 Bukkit 的 {@code BossBar} 完全分离，理由与 {@code LootService.rollPlan}
 * 相同：分段阈值、阶段切换、读条进度是整个系统里最容易写错、又最难在真服上
 * 归因的部分——「血条颜色不对」在服上只表现为一个观感差异，几乎无法定位。
 * 抽成纯类才能让这部分被测。</p>
 *
 * <p>命名颜色为字符串而不用 {@code BarColor}：这样本类不引用 Bukkit，
 * 测试也不必加载材质与颜色注册表。</p>
 */
public final class BossBarState {

    private BossBarState() {
    }

    /** 分段配色的一档。 */
    public record Segment(double fromRatio, String color, String note) {

        /** {@code fromRatio} 为该档的<b>下界</b>（含），取值 [0,1]。 */
        public boolean covers(double ratio) {
            return ratio >= fromRatio && ratio < 1.0;
        }
    }

    /**
     * 一次血条的完整显示内容。
     *
     * @param title 主标题；已拼接阶段与读条
     * @param ratio 当前血量比例，恒在 [0,1]
     * @param color 命名颜色
     * @param visible 是否应显示（未配置时为 false）
     */
    public record Render(String title, double ratio, String color, boolean visible) {
    }

    /** 读条进度；null 表示无读条。 */
    public record Cast(String label, double progress) {

        public double clamped() {
            double p = progress;
            if (Double.isNaN(p)) return 0;
            return p < 0 ? 0 : (p > 1 ? 1 : p);
        }
    }

    /**
     * 选配色档。
     *
     * <p>按 {@code fromRatio} <b>降序</b>找第一个命中的档：升序找会让相邻档的
     * 边界归属反了——0.5 恰好落在哪一档决定了「半血变色」的观感是否与作者预期一致。
     * 边界用「含下界、不含上界」保证每个比例值只属于一档。</p>
     *
     * @param segments 档位；为空时返回 {@link #DEFAULT_COLOR}
     */
    public static String colorOf(List<Segment> segments, double ratio) {
        if (segments == null || segments.isEmpty()) return DEFAULT_COLOR;
        List<Segment> sorted = new ArrayList<>(segments);
        sorted.sort((a, b) -> Double.compare(b.fromRatio(), a.fromRatio()));
        double r = clamp01(ratio);
        for (Segment s : sorted) {
            if (s.color() != null && !s.color().isBlank() && s.covers(r)) return s.color();
        }
        // 落在所有档之下（作者把下界都写高了）：退回最低档而非默认色，
        // 否则「只剩一丝血」反而显示成满血色，观感上比报错更难察觉
        for (Segment s : sorted) {
            if (s.color() != null && !s.color().isBlank()) return s.color();
        }
        return DEFAULT_COLOR;
    }

    /** 缺省配色：高于半血绿、以下黄、濒死红。 */
    public static final String DEFAULT_COLOR = "GREEN";

    /**
     * 组装显示内容。
     *
     * <p>标题拼接顺序固定为「名称 [阶段] 读条」：技能读条是玩家最需要立刻读到的信息，
     * 放在末尾正好落在血条右侧视线末端。</p>
     *
     * @param enabled 档案是否启用了血条
     * @param name 档案名；为空时用 {@code fallbackName}
     * @param fallbackName 档案名为空时的兜底（通常是模型名）
     * @param health 当前血量
     * @param maxHealth 最大血量；<=0 时按满血处理
     * @param phase 当前阶段名；空表示无阶段
     * @param cast 读条；null 表示无
     */
    public static Render render(boolean enabled, String name, String fallbackName,
                                double health, double maxHealth, String phase, Cast cast) {
        if (!enabled) return new Render("", 0, DEFAULT_COLOR, false);

        double ratio = maxHealth > 0 ? clamp01(health / maxHealth) : 1.0;

        String base = (name == null || name.isBlank()) ? fallbackName : name;
        if (base == null || base.isBlank()) base = "Boss";

        StringBuilder sb = new StringBuilder(base);
        if (phase != null && !phase.isBlank()) sb.append(" [").append(phase).append(']');
        if (cast != null && cast.label() != null && !cast.label().isBlank()) {
            sb.append(' ').append(cast.label()).append(' ').append(Math.round(cast.clamped() * 100)).append('%');
        }
        return new Render(sb.toString(), ratio, DEFAULT_COLOR, true);
    }

    /**
     * 按配置里的分段重算配色。
     *
     * @param colors 各档命名颜色；长度需与 thresholds 对应
     * @param thresholds 各档下界，升序或降序均可
     */
    public static String colorByThresholds(List<Double> thresholds, List<String> colors, double ratio) {
        if (thresholds == null || colors == null
                || thresholds.isEmpty() || colors.isEmpty()
                || thresholds.size() != colors.size()) {
            return DEFAULT_COLOR;
        }
        List<Segment> segs = new ArrayList<>();
        for (int i = 0; i < thresholds.size(); i++) {
            segs.add(new Segment(clamp01(thresholds.get(i)), colors.get(i), null));
        }
        return colorOf(segs, ratio);
    }

    public static double clamp01(double v) {
        if (Double.isNaN(v)) return 0;
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}