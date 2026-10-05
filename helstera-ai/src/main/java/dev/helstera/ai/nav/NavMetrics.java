package dev.helstera.ai.nav;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 寻路运行期指标。
 *
 * <p><b>存在的理由</b>：寻路失效的表现几乎全是<b>静默</b>的——生物贴着墙走、
 * 绕远路、或干脆不动，而服务端没有任何报错。加了指标之后，这些情况会在
 * {@code /helstera nav} 里表现为「预算超限 N 次」「不可达 N 次」「卡死 N 次」，
 * 于是问题被定位到具体一类，而不必靠观察现象猜。</p>
 *
 * <p>换句话说：本类不是锦上添花的监控，而是把「看不见的失败」变成「看得见的数字」，
 * 这也是单元测试覆盖不到的那部分唯一可行的兜底。</p>
 *
 * <p>线程约束：全部为原子计数，可任意线程累加。</p>
 */
public final class NavMetrics {

    /** 失败分类。用枚举而非字符串，便于 {@code /helstera nav} 稳定排序输出。 */
    public enum Kind {
        /** 找到路径。 */
        OK,
        /** 起点或终点不可通行——通常是生物卡在方块里，或目标站在岩浆里。 */
        ENDPOINT_BLOCKED,
        /** 图上确实不通。 */
        UNREACHABLE,
        /** 展开节点超预算。反复出现说明预算偏小或路径过长。 */
        BUDGET_EXCEEDED,
        /** 连续无位移触发的侧移。反复出现多半是生物被顶住或地形狭窄。 */
        SIDESTEP,
        /** 未注入寻路服务，仍走直线。 */
        NAV_DISABLED
    }

    private final Map<Kind, AtomicLong> counts = new EnumMap<>(Kind.class);

    public NavMetrics() {
        for (Kind k : Kind.values()) counts.put(k, new AtomicLong());
    }

    /** 累加一次寻路结果。 */
    public void record(AStar.Result r) {
        if (r == null || r.found()) {
            bump(Kind.OK);
            return;
        }
        // 原因字符串是实现细节，按前缀归类而非直接存字符串：
        // 这样改文案不会让历史计数散成两行
        String reason = r.reason() == null ? "" : r.reason();
        if (reason.contains("预算")) bump(Kind.BUDGET_EXCEEDED);
        else if (reason.contains("不可通行")) bump(Kind.ENDPOINT_BLOCKED);
        else if (reason.contains("缺失")) bump(Kind.ENDPOINT_BLOCKED);
        else bump(Kind.UNREACHABLE);
    }

    public void bump(Kind k) {
        counts.get(k).incrementAndGet();
    }

    public long get(Kind k) {
        return counts.get(k).get();
    }

    /** 寻路总次数（含成功）。 */
    public long total() {
        long n = 0;
        for (AtomicLong v : counts.values()) n += v.get();
        return n;
    }

    /**
     * 失败总数。
     *
     * <p>{@code SIDESTEP} 不计入失败：它表示「检测到卡住并已侧移」，是一次成功的
     * 恢复，把它算作失败会让失败率虚高，掩盖真正的问题。</p>
     */
    public long failures() {
        long n = 0;
        for (Kind k : Kind.values()) {
            if (k != Kind.OK && k != Kind.SIDESTEP) n += counts.get(k).get();
        }
        return n;
    }

    public void reset() {
        for (AtomicLong v : counts.values()) v.set(0);
    }

    /** 快照：枚举 -> 计数。 */
    public Map<Kind, Long> snapshot() {
        Map<Kind, Long> out = new EnumMap<>(Kind.class);
        for (Kind k : Kind.values()) out.put(k, counts.get(k).get());
        return out;
    }

    /**
     * 失败率（0..1），分母为寻路总次数。
     *
     * <p>一次都没跑过时返回 0 而非 NaN——直接进格式化会把命令输出变成 {@code NaN%}。</p>
     */
    public double failureRate() {
        long t = total();
        return t == 0 ? 0.0 : (double) failures() / t;
    }
}