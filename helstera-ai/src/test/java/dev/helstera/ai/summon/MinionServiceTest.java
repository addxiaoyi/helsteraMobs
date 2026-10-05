package dev.helstera.ai.summon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召唤关系测试。
 *
 * <p>本类守的三道闸门失效时<b>都不会报错</b>，只会表现为「点一下生成上千只怪、
 * 服务端卡死」：递归上限、数量上限、以及 {@code depthOf} 的环防护。
 * 环防护尤其关键——它用固定访问次数截断而非 while(true)，因此测试里也必须
 * 构造出真实成环的数据来验证它不会死循环。</p>
 */
class MinionServiceTest {

    @Test
    @DisplayName("第一层召唤可登记，深度正确")
    void registersFirstLevel() {
        MinionService s = new MinionService();
        assertNull(s.register(1, 10, 1));
        assertEquals(1, s.depthOf(10));
        assertEquals(0, s.depthOf(1), "召唤主自身深度为 0");
    }

    @Test
    @DisplayName("递归深度超限被拦截")
    void recursionDepthCapped() {
        MinionService s = new MinionService();
        s.limits(2, 10);
        assertNull(s.register(1, 10, 1));
        assertNull(s.register(1, 11, 2));
        // 10 的深度是 1，它召唤的召唤物深度 2，再召唤就该被拒
        assertNotNull(s.whyBlocked(11, 2));
        assertTrue(s.whyBlocked(11, 2).contains("递归深度"));
    }

    @Test
    @DisplayName("同一召唤主数量超限被拦截")
    void perOwnerCapEnforced() {
        MinionService s = new MinionService();
        s.limits(5, 3);
        for (int i = 0; i < 3; i++) assertNull(s.register(1, 10 + i, 1));
        assertEquals(3, s.minionCount(1));
        assertNotNull(s.register(1, 99, 1));
        assertTrue(s.whyBlocked(1, 0).contains("上限"));
    }

    @Test
    @DisplayName("非法上限回落默认值，不导致无限召唤")
    void invalidLimitsFallBack() {
        MinionService s = new MinionService();
        s.limits(0, -5);
        assertEquals(MinionService.DEFAULT_MAX_DEPTH, s.maxDepth());
        assertEquals(MinionService.DEFAULT_MAX_PER_OWNER, s.maxPerOwner());
    }

    @Test
    @DisplayName("召唤物移除后其父计数减少")
    void forgetRemovesFromParent() {
        MinionService s = new MinionService();
        s.limits(5, 3);
        s.register(1, 10, 1);
        s.register(1, 11, 1);
        assertEquals(2, s.minionCount(1));
        s.forget(10);
        assertEquals(1, s.minionCount(1));
        assertEquals(0, s.depthOf(10), "已移除的召唤物深度归零");
    }

    @Test
    @DisplayName("多级链条深度累加")
    void depthAccumulatesAlongChain() {
        MinionService s = new MinionService();
        s.limits(5, 10);
        s.register(1, 10, 1);
        s.register(10, 20, 2);
        assertEquals(2, s.depthOf(20));
    }

    @Test
    @DisplayName("关系成环时 depthOf 截断而非死循环")
    void cycleIsTruncatedNotInfinite() {
        // 程序缺陷可能造出环。depthOf 必须靠固定访问次数返回，不能卡死主线程。
        MinionService s = new MinionService();
        s.limits(9, 10);
        s.register(7, 8, 1);
        s.register(8, 9, 2);
        // 人为制造环：把 8 的父改成 9，9 的父是 8
        s.forceParentForTest(9, 8);
        int d = s.depthOf(9);
        assertTrue(d >= 0 && d <= 64, "环必须被截断，返回深度应受限，实际 " + d);
    }

    @Test
    @DisplayName("空输入与幂等清理")
    void emptyAndIdempotentClear() {
        MinionService s = new MinionService();
        assertEquals(0, s.totalMinions());
        assertEquals(0, s.minionCount(1));
        assertTrue(s.childrenOf(1).isEmpty());
        s.clear();
        s.clear();
        assertEquals(0, s.totalMinions());
    }
}