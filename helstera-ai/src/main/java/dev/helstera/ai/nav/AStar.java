package dev.helstera.ai.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 预算化 A* 寻路内核。
 *
 * <p><b>为何必须有预算</b>：A* 在「不可达」场景下会退化成全图搜索。把这套逻辑挂在
 * 主线程的决策节拍上，无界搜索就等价于「TNT 世界加载时全服卡死」。因此本实现
 * 要求调用方给定展开节点上限，超限即放弃并如实返回——放弃是可恢复的，卡死不是。</p>
 *
 * <p><b>预算选多少</b>：普通 8×8 地板到目标，展开数十个节点即够；预算设太大等于
 * 没设。默认值 512 足以覆盖常规战斗距离，又能保证最坏情况下开销有界。</p>
 *
 * <p>线程约束：纯计算，不触碰 Bukkit，可在任意线程调用。</p>
 */
public final class AStar {

    /** 默认展开预算。超过即放弃本次寻路。 */
    public static final int DEFAULT_BUDGET = 512;

    /**
     * 寻路结果。
     *
     * @param path 找到时为从起点到终点的完整节点序列（含首尾），否则为空
     * @param expanded 实际展开的节点数——用于观测预算是否偏紧
     * @param reason 未找到时的原因，便于命令层与调试区分「不可达」和「超预算」
     */
    public record Result(List<NavNode> path, int expanded, String reason) {
        public boolean found() {
            return !path.isEmpty();
        }

        static Result fail(int expanded, String reason) {
            return new Result(List.of(), expanded, reason);
        }
    }

    private AStar() {
    }

    /**
     * 带预算的 A*。
     *
     * @param start 起点；不可通行直接返回失败，不做「从不可通行处出发」的容错——
     *               那会掩盖「路径根本不通」的事实
     * @param goal 终点
     * @param budget 允许展开的节点数上限，<=0 视为 {@link #DEFAULT_BUDGET}
     */
    public static Result find(NavNode start, NavNode goal, int budget) {
        if (start == null || goal == null) return Result.fail(0, "起点或终点缺失");
        if (!start.walkable()) return Result.fail(0, "起点不可通行");
        if (!goal.walkable()) return Result.fail(0, "终点不可通行");
        int maxExpanded = budget > 0 ? budget : DEFAULT_BUDGET;
        if (start.key().equals(goal.key())) {
            return new Result(List.of(start), 0, null);
        }

        // gScore: 从起点到该节点的最优已知代价。fScore = g + h（估代价）。
        Map<String, Double> g = new HashMap<>();
        Map<String, NavNode> prev = new HashMap<>();
        // open: 待展开节点，按 fScore 升序出队。用「惰性删除」而非 decrease-key：
        // 重复入队条目在出队时按 g 表跳过，避免维护额外的有序结构。
        PriorityQueue<Entry> open = new PriorityQueue<>((a, b) ->
                Double.compare(a.f, b.f));

        g.put(start.key(), 0.0);
        open.add(new Entry(start, 0.0, start.heuristicTo(goal)));

        int expanded = 0;
        while (!open.isEmpty()) {
            if (expanded >= maxExpanded) {
                // 预算耗尽：如实报告，让调用方决定是放宽预算还是放弃。
                // 静默返回一个空路径会让人误判为「不可达」，从而永久放弃寻路。
                return Result.fail(expanded, "超出展开预算 " + maxExpanded);
            }
            Entry cur = open.poll();
            String ck = cur.node.key();
            if (ck.equals(goal.key())) return new Result(rebuild(prev, start, goal), expanded, null);
            // 惰性删除：出队时若该条目已被更优路径取代，跳过
            if (cur.g > g.getOrDefault(ck, Double.MAX_VALUE)) continue;

            expanded++;
            for (NavNode nb : cur.node.neighbors()) {
                if (nb == null || !nb.walkable()) continue;
                double tentative = cur.g + cur.node.costTo(nb);
                double known = g.getOrDefault(nb.key(), Double.MAX_VALUE);
                if (tentative < known) {
                    g.put(nb.key(), tentative);
                    prev.put(nb.key(), cur.node);
                    open.add(new Entry(nb, tentative, tentative + nb.heuristicTo(goal)));
                }
            }
        }
        return Result.fail(expanded, "不可达");
    }

    private record Entry(NavNode node, double g, double f) {
    }

    /** 由 prev 回溯出 start..goal 的完整序列。 */
    private static List<NavNode> rebuild(Map<String, NavNode> prev, NavNode start, NavNode goal) {
        List<NavNode> path = new ArrayList<>();
        NavNode cur = goal;
        // 以 key 而非引用比较：实现方可能每次调用 neighbors 都新建节点对象
        while (cur != null && !cur.key().equals(start.key())) {
            path.add(cur);
            cur = prev.get(cur.key());
        }
        path.add(start);
        Collections.reverse(path);
        return List.copyOf(path);
    }
}