package dev.helstera.ai.nav;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 寻路指标测试。
 *
 * <p>指标存在的唯一目的是「让静默失效变成可见计数」，所以测试守的是
 * <b>分类是否正确</b>——若预算超限被误归到「不可达」，管理员就会去调地形
 * 而不是调预算，诊断反而把人带偏。</p>
 */
class NavMetricsTest {

    private static NavNode node() {
        return new NavNode() {
            @Override public String key() { return "0,0"; }
            @Override public boolean walkable() { return true; }
            @Override public List<NavNode> neighbors() { return List.of(); }
            @Override public double costTo(NavNode to) { return 1.0; }
            @Override public double heuristicTo(NavNode t) { return 0.0; }
        };
    }

    @Test
    @DisplayName("成功结果计入 OK 而非失败")
    void successCountsAsOk() {
        NavMetrics m = new NavMetrics();
        m.record(AStar.find(node(), node(), 64));
        assertEquals(1, m.get(NavMetrics.Kind.OK));
        assertEquals(0, m.failures());
    }

    @Test
    @DisplayName("按原因前缀正确分类各类失败")
    void failuresAreClassifiedByReason() {
        NavMetrics m = new NavMetrics();
        m.record(AStar.Result.fail(0, "超出展开预算 256"));
        m.record(AStar.Result.fail(0, "起点不可通行"));
        m.record(AStar.Result.fail(0, "终点不可通行"));
        m.record(AStar.Result.fail(0, "不可达"));
        assertEquals(1, m.get(NavMetrics.Kind.BUDGET_EXCEEDED));
        assertEquals(2, m.get(NavMetrics.Kind.ENDPOINT_BLOCKED));
        assertEquals(1, m.get(NavMetrics.Kind.UNREACHABLE));
        assertEquals(4, m.failures());
    }

    @Test
    @DisplayName("侧移不计入失败：它是一次成功恢复")
    void sidestepIsNotAFailure() {
        NavMetrics m = new NavMetrics();
        m.bump(NavMetrics.Kind.SIDESTEP);
        assertEquals(0, m.failures(),
                "侧移是恢复动作，算作失败会让失败率虚高、掩盖真问题");
        assertEquals(1, m.get(NavMetrics.Kind.SIDESTEP));
    }

    @Test
    @DisplayName("未跑过时失败率为 0 而非 NaN")
    void failureRateNeverNaN() {
        assertEquals(0.0, new NavMetrics().failureRate());
    }

    @Test
    @DisplayName("reset 清零全部计数")
    void resetClears() {
        NavMetrics m = new NavMetrics();
        m.bump(NavMetrics.Kind.UNREACHABLE);
        m.reset();
        assertEquals(0, m.total());
    }

    @Test
    @DisplayName("快照包含全部分类，便于命令稳定输出")
    void snapshotCoversAllKinds() {
        assertEquals(NavMetrics.Kind.values().length, new NavMetrics().snapshot().size());
    }

    @Test
    @DisplayName("null 结果安全，不炸")
    void nullRecordIsSafe() {
        NavMetrics m = new NavMetrics();
        m.record(null);
        assertFalse(m.total() == 0 && m.failures() == 0);
    }
}