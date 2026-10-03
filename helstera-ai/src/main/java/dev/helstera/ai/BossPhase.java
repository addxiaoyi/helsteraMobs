package dev.helstera.ai;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Boss 血量阶段（{@code profiles.<name>.phases}）。
 *
 * <p>血量区间采用<b>左闭右开</b> {@code [min, max)}。这不是随意选择：
 * 若两端都闭合，血量恰好落在分界（如 75）时会被相邻两段同时命中，表现为
 * 两个阶段的效果叠加触发；若两端都开，则该血量不属于任何一段，Boss 卡在
 * 无阶段状态。左闭右开让相邻段既无缝衔接又互斥。</p>
 *
 * <p>刻意不压缩满血值：参考实现会把 100 压成 99.999 来规避 {@code [_,100)}
 * 抓不到满血的问题，那是补丁而非建模。这里由 {@link #resolve} 显式处理，
 * 满血即命中第一段。</p>
 *
 * <p>纯数据与纯函数，不依赖运行期组件，因此可在单元测试中离线验证。</p>
 */
public final class BossPhase {

    private final String id;
    private final double minPercent;
    private final double maxPercent;
    private final String announce;
    private final List<String> onEnter;
    private final List<String> onExit;

    public BossPhase(String id, double minPercent, double maxPercent,
                     String announce, List<String> onEnter, List<String> onExit) {
        this.id = id == null ? "" : id.trim();
        double lo = clamp(minPercent);
        double hi = clamp(maxPercent);
        // 配置写反了也要能用：交换而不是丢弃，阶段数量是配置作者的心血
        if (lo > hi) {
            double t = lo;
            lo = hi;
            hi = t;
        }
        this.minPercent = lo;
        this.maxPercent = hi;
        this.announce = announce == null ? "" : announce;
        this.onEnter = onEnter == null ? List.of() : List.copyOf(onEnter);
        this.onExit = onExit == null ? List.of() : List.copyOf(onExit);
    }

    public String id() {
        return id;
    }

    public double minPercent() {
        return minPercent;
    }

    public double maxPercent() {
        return maxPercent;
    }

    public String announce() {
        return announce;
    }

    public List<String> onEnter() {
        return onEnter;
    }

    public List<String> onExit() {
        return onExit;
    }

    /** 是否为「盖住 100% 血量」的首段（上限恰为 100）。 */
    public boolean coversFullHealth() {
        return maxPercent >= 100.0;
    }

    /**
     * 血量百分比是否落在本段（左闭右开）。
     *
     * <p>满血且本段上限为 100 时按命中处理——见类注释。</p>
     */
    public boolean matches(double hpPercent) {
        if (Double.isNaN(hpPercent)) return false;
        double hp = clamp(hpPercent);
        if (hp >= 100.0 && maxPercent >= 100.0) return minPercent <= hp;
        return hp >= minPercent && hp < maxPercent;
    }

    /**
     * 求血量落入的阶段。
     *
     * <p>配置区间重叠时取第一个匹配项：重叠属于配置错误，已在解析阶段留痕，
     * 运行时不该再抛异常。</p>
     *
     * @param hpPercent 血量百分比 0~100
     * @return 命中的阶段；无匹配返回 null
     */
    public static BossPhase resolve(List<BossPhase> phases, double hpPercent) {
        if (phases == null || phases.isEmpty()) return null;
        for (BossPhase p : phases) {
            if (p.matches(hpPercent)) return p;
        }
        return null;
    }

    static double clamp(double v) {
        if (Double.isNaN(v)) return 0;
        return Math.max(0, Math.min(100, v));
    }

    /**
     * 解析阶段的 {@code phases} 列表。
     *
     * <p>与 {@code entries} 同理，YAML 列表项可能是映射而非节，两种形态都要接。
     * 非法条目（缺少 id、区间完全为负）跳过并记入 problems，而不是让整份档案失败。</p>
     */
    @SuppressWarnings("unchecked")
    public static List<BossPhase> parseList(ConfigurationSection sec, List<String> problems) {
        List<BossPhase> out = new ArrayList<>();
        if (sec == null) return out;
        Object raw = sec.get("phases");
        List<?> items;
        if (raw instanceof List<?> l) {
            items = l;
        } else if (raw != null) {
            // 只写一段时 Bukkit 会退化成映射
            items = List.of(raw);
        } else {
            return out;
        }
        int idx = 0;
        for (Object o : items) {
            idx++;
            ConfigurationSection ps;
            if (o instanceof ConfigurationSection cs) {
                ps = cs;
            } else if (o instanceof java.util.Map<?, ?> map) {
                org.bukkit.configuration.MemoryConfiguration mc = new org.bukkit.configuration.MemoryConfiguration();
                ps = mc.createSection("phase");
                for (var e : map.entrySet()) {
                    if (e.getKey() != null) ps.set(String.valueOf(e.getKey()), e.getValue());
                }
            } else {
                problems.add("阶段第 " + idx + " 条不是映射（应写成 - id: ...），已跳过");
                continue;
            }
            String id = ps.getString("id");
            if (id == null || id.isBlank()) {
                problems.add("阶段第 " + idx + " 条缺少 id，已跳过");
                continue;
            }
            double lo = ps.getDouble("min", ps.getDouble("min-percent", 0));
            double hi = ps.getDouble("max", ps.getDouble("max-percent", 100));
            if (lo < 0 || hi > 100) {
                problems.add("阶段 " + id + " 的区间超出 0~100，已夹紧");
            }
            out.add(new BossPhase(id, lo, hi,
                    ps.getString("announce"),
                    ps.getStringList("on-enter"),
                    ps.getStringList("on-exit")));
        }
        // 覆盖顺序无关：resolve 会挑血量真正落入的那一段，
        // 但按 id 排序让日志与调试输出稳定。
        out.sort(java.util.Comparator.comparing(BossPhase::id));
        return Collections.unmodifiableList(out);
    }
}