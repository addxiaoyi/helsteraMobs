package dev.helstera.ai.skill;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 技能执行期故障的统一留痕入口。
 *
 * <p>此前 {@code SkillExtras} 里有 40 处 {@code catch (Throwable ignored) {}}，
 * 其中大量是完全空块。空块不是「无害的容错」，而是<b>静默失效</b>：动作失败后
 * 不抛异常、不记日志、不计数，配置作者在服务器上只会看到「技能没反应」，
 * 无从判断是没触发、还是触发了但失败、还是参数写错了。</p>
 *
 * <p>本类让失败可观测，同时<b>不改变控制流</b>：仍然吞掉异常，
 * 因为一个动作失败不应中断整个技能链。</p>
 *
 * <p><b>为何默认只计数不打印</b>：技能每 tick 可能执行多次，逐次打印会刷屏。
 * 计数是零成本的，正常运行零开销；只有 {@link #setDebug} 打开时才逐次记日志。
 * 计数本身有个作用——它让 {@code /helstera debug} 能显示「有 N 个动作在静默失败」，
 * 这是发现问题的唯一线索。</p>
 *
 * <p>线程安全：技能主要在主线程执行，但计数器用并发容器以防未来异步调用。</p>
 */
public final class SkillFaults {

    private static final Logger LOG = Logger.getLogger("helstera.skills");

    /** 「动作名」-> 累计失败次数。 */
    private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();

    private static volatile boolean debug;

    private SkillFaults() {
    }

    /** 打开/关闭逐次日志。默认关闭。 */
    public static void setDebug(boolean on) {
        debug = on;
    }

    public static boolean debugEnabled() {
        return debug;
    }

    /**
     * 记录一次被吞掉的失败。
     *
     * @param what 出错的动作/条件标识，用于定位与计数归类
     * @param t 捕获到的异常
     */
    public static void swallow(String what, Throwable t) {
        if (what == null) what = "?";
        COUNTS.computeIfAbsent(what, k -> new AtomicLong()).incrementAndGet();
        if (debug) {
            // 打印堆栈：静默失败最难查的就是「到底哪一行炸了」
            LOG.log(Level.WARNING, "[" + what + "] 执行失败", t);
        }
    }

    /**
     * 记录一次失败但不保留异常对象。
     *
     * <p>用于本来就没接住 Throwable 的路径；只计数，避免为了留痕而构造异常。</p>
     */
    public static void swallow(String what) {
        swallow(what, null);
    }

    /** 某动作的累计失败次数；无记录为 0。 */
    public static long countOf(String what) {
        AtomicLong c = COUNTS.get(what);
        return c == null ? 0 : c.get();
    }

    /** 失败种类数（不同 what 的个数）。 */
    public static int faultKinds() {
        return COUNTS.size();
    }

    /** 总失败次数。 */
    public static long totalFaults() {
        long n = 0;
        for (AtomicLong c : COUNTS.values()) n += c.get();
        return n;
    }

    /** 单条故障记录：出错的动作标识与累计次数。 */
    public record Fault(String what, long count) {
    }

    /**
     * 故障列表，按次数降序，供 /helstera debug 展示。
     *
     * <p>返回不可变副本：直接暴露内部 Map 会让命令层拿到活引用，
     * 遍历过程中被并发写入会抛 {@code ConcurrentModificationException}。</p>
     */
    public static java.util.List<Fault> faults() {
        java.util.List<Fault> out = new java.util.ArrayList<>();
        for (Map.Entry<String, AtomicLong> e : COUNTS.entrySet()) {
            long c = e.getValue().get();
            if (c > 0) out.add(new Fault(e.getKey(), c));
        }
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return java.util.List.copyOf(out);
    }

    /** 快照，供 /helstera debug 展示。 */
    public static Map<String, Long> snapshot() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        COUNTS.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        return out;
    }

    /** 清零计数（调试用）。 */
    public static void reset() {
        COUNTS.clear();
    }
}