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

    /**
     * 渐变配色：在相邻两档之间按比例线性插值，返回最接近的可用色名。
     *
     * <p><b>插值到离散色而非真 RGB</b>：Bukkit 的 {@code BossBar} 只接受
     * {@code PINK/BLUE/RED/GREEN/YELLOW/PURPLE} 六档，没有任意颜色通道。
     * 声称支持真渐变会让配置作者以为能写出细腻的橙→红过渡，实际只在六个采样点
     * 上跳变。这里显式按 RGB 距离取最近档，让「渐变」在能力范围内尽可能接近字面意思，
     * 且插值本身可测。</p>
     *
     * @param segments 档位；为空时返回 {@link #DEFAULT_COLOR}
     * @param ratio 当前血量比例
     */
    public static String gradientOf(List<Segment> segments, double ratio) {
        if (segments == null || segments.size() < 2) return colorOf(segments, ratio);
        List<Segment> usable = new ArrayList<>();
        for (Segment s : segments) {
            if (s.color() != null && !s.color().isBlank()) usable.add(s);
        }
        if (usable.size() < 2) return colorOf(segments, ratio);
        usable.sort((a, b) -> Double.compare(a.fromRatio(), b.fromRatio()));
        double r = clamp01(ratio);

        // 落在最下档之下：取最下档色，不外推——外推会造出作者没配置过的颜色
        if (r <= usable.get(0).fromRatio()) return nearest(usable.get(0).color());
        Segment top = usable.get(usable.size() - 1);
        if (r >= top.fromRatio()) return nearest(top.color());

        // 定位所在区间并插值
        for (int i = 1; i < usable.size(); i++) {
            Segment lo = usable.get(i - 1);
            Segment hi = usable.get(i);
            if (r >= hi.fromRatio()) continue;
            double span = hi.fromRatio() - lo.fromRatio();
            double t = span <= 1e-9 ? 0 : (r - lo.fromRatio()) / span;
            return nearest(mix(lo.color(), hi.color(), t));
        }
        return nearest(top.color());
    }

    /** 两个命名色按 {@code t} 线性插值，返回 RGB 三元组。 */
    private static int[] mix(String a, String b, double t) {
        int[] ca = rgb(a);
        int[] cb = rgb(b);
        double k = clamp01(t);
        return new int[]{
                (int) Math.round(ca[0] + (cb[0] - ca[0]) * k),
                (int) Math.round(ca[1] + (cb[1] - ca[1]) * k),
                (int) Math.round(ca[2] + (cb[2] - ca[2]) * k)};
    }

    /** 把 RGB 距离最近的 {@link #AVAILABLE_COLORS} 名返回。 */
    private static String nearest(int[] rgb) {
        String best = AVAILABLE_COLORS[0];
        long bestDist = Long.MAX_VALUE;
        for (String name : AVAILABLE_COLORS) {
            int[] c = rgb(name);
            long d = dr2(rgb[0], c[0]) + dr2(rgb[1], c[1]) + dr2(rgb[2], c[2]);
            if (d < bestDist) {
                bestDist = d;
                best = name;
            }
        }
        return best;
    }

    private static String nearest(String named) {
        return nearest(rgb(named));
    }

    private static long dr2(int a, int b) {
        long d = (long) a - b;
        return d * d;
    }

    /** Bukkit BossBar 支持的全部颜色名；渐变的采样目标集。 */
    public static final String[] AVAILABLE_COLORS =
            {"PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE"};

    /** 命名色 → RGB。未知名按绿色处理，与运行期回落到 GREEN 保持一致。 */
    private static int[] rgb(String named) {
        if (named == null) return new int[]{0, 255, 0};
        return switch (named.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "PINK" -> new int[]{255, 0, 170};
            case "BLUE" -> new int[]{0, 0, 255};
            case "RED" -> new int[]{255, 0, 0};
            case "YELLOW" -> new int[]{255, 255, 0};
            case "PURPLE" -> new int[]{170, 0, 255};
            default -> new int[]{0, 255, 0};
        };
    }

    /** 缺省配色：高于半血绿、以下黄、濒死红。 */
    public static final String DEFAULT_COLOR = "GREEN";

    /**
     * 组装显示内容。
     *
     * <p>标题拼接顺序固定为「Lv.N 名称 [阶段] 读条」：等级放最前面，
     * 让玩家一眼就能分辨不同等级的 Boss。</p>
     *
     * @param enabled 档案是否启用了血条
     * @param name 档案名；为空时用 {@code fallbackName}
     * @param fallbackName 档案名为空时的兜底（通常是模型名）
     * @param health 当前血量
     * @param maxHealth 最大血量；<=0 时按满血处理
     * @param phase 当前阶段名；空表示无阶段
     * @param cast 读条；null 表示无
     * @param level 当前等级；<=0 时不显示等级前缀
     */
    public static Render render(boolean enabled, String name, String fallbackName,
                                double health, double maxHealth, String phase, Cast cast,
                                int level) {
        if (!enabled) return new Render("", 0, DEFAULT_COLOR, false);

        double ratio = maxHealth > 0 ? clamp01(health / maxHealth) : 1.0;

        StringBuilder sb = new StringBuilder();
        if (level > 0) sb.append("Lv.").append(level).append(' ');

        String base = (name == null || name.isBlank()) ? fallbackName : name;
        if (base == null || base.isBlank()) base = "Boss";
        sb.append(base);

        if (phase != null && !phase.isBlank()) sb.append(" [").append(phase).append(']');
        if (cast != null && cast.label() != null && !cast.label().isBlank()) {
            sb.append(' ').append(cast.label()).append(' ').append(Math.round(cast.clamped() * 100)).append('%');
        }
        return new Render(sb.toString(), ratio, DEFAULT_COLOR, true);
    }

    /**
     * 无等级信息的旧版渲染入口（兼容）。
     */
    public static Render render(boolean enabled, String name, String fallbackName,
                                double health, double maxHealth, String phase, Cast cast) {
        return render(enabled, name, fallbackName, health, maxHealth, phase, cast, 0);
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