package dev.helstera.ai.nav;

import org.bukkit.Location;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 寻路服务：持有规则与全局指标，为每个实例各配一个 {@link PathFollower}。
 *
 * <p><b>为何每实例一个跟随器</b>：路径与路点游标都是实例私有状态。若共用一个，
 * 多生物会互相抢游标，表现为「一群怪挤在一起走同一条路」，且单测无法构造。</p>
 *
 * <p><b>为何指标是全局的</b>：单个实例的计数没有诊断价值（一只怪卡住说明不了问题），
 * 而「全服有 N 次预算超限」才是能据此调参的信号。</p>
 */
public final class NavService {

    private final NavGrid.NavRules rules;
    private final NavMetrics metrics = new NavMetrics();
    private final ConcurrentHashMap<Integer, PathFollower> followers = new ConcurrentHashMap<>();
    /** 累计展开的节点数，用于估算 A* 的实际开销。 */
    private final AtomicLong expandedTotal = new AtomicLong();

    public NavService(NavGrid.NavRules rules) {
        this.rules = rules == null ? NavGrid.NavRules.defaults() : rules;
    }

    public NavGrid.NavRules rules() {
        return rules;
    }

    public NavMetrics metrics() {
        return metrics;
    }

    public long expandedTotal() {
        return expandedTotal.get();
    }

    /** 取（必要时创建）某实例的跟随器。 */
    public PathFollower follower(int instanceId) {
        return followers.computeIfAbsent(instanceId, k -> new PathFollower());
    }

    /** 实例销毁时清理，防止 map 随实例生成无限增长。 */
    public void forget(int instanceId) {
        followers.remove(instanceId);
    }

    public int trackedInstances() {
        return followers.size();
    }

    /**
     * 重算路径并装入跟随器。
     *
     * <p>失败时<b>清空路径</b>：保留一条早已失效的旧路径会让生物继续撞墙，
     * 而直连目标至少会顶到墙根停下——后者是更容易被玩家察觉、也更可诊断的表现。</p>
     *
     * @return A* 结果，便于调用方记指标
     */
    public AStar.Result repath(int instanceId, Location from, Location to, long now, int budget) {
        PathFollower f = follower(instanceId);
        if (!f.shouldRepath(now)) return new AStar.Result(List.of(), 0, "节流中");
        NavGrid start = NavGrid.at(from, rules, metrics);
        NavGrid goal = NavGrid.at(to, rules, metrics);
        if (start == null || goal == null) {
            metrics.bump(NavMetrics.Kind.ENDPOINT_BLOCKED);
            f.clearPath();
            return AStar.Result.fail(0, "位置缺失");
        }
        AStar.Result r = AStar.find(start, goal, budget);
        expandedTotal.addAndGet(r.expanded());
        metrics.record(r);
        if (r.found()) f.setPath(r.path());
        else f.clearPath();
        f.onRepathed(now);
        return r;
    }

    /** 记录一次卡死侧移。 */
    public void recordSidestep(int instanceId, long now) {
        follower(instanceId).requestSidestep(now);
        metrics.bump(NavMetrics.Kind.SIDESTEP);
    }

    /** 供命令展示的一行摘要。 */
    public String summary() {
        return String.format(java.util.Locale.ROOT,
                "寻路 %d 次 / 展开 %d 节点 / 失败率 %.1f%% / 跟踪 %d 实例",
                metrics.total(), expandedTotal.get(), metrics.failureRate() * 100,
                trackedInstances());
    }
}