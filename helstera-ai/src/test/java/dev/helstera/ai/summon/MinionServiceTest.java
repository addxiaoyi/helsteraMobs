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

    /**
     * 实例 id 超出 Integer 缓存范围时，{@code forget} 仍要真正移除。
     *
     * <p><b>本测试的来历</b>：曾怀疑 {@code removeIf(k -> k == minionId)} 是装箱比较
     * 陷阱（{@code Integer} 与 {@code int} 用 {@code ==} 比引用），并打算改写成
     * 值比较。写了本测试后<b>没能复现</b>该问题——JDK 21 下无论 id 取 5000 还是
     * 900000、甚至从 {@code Integer.parseInt} 动态取值，{@code removeIf} 都能正确删除。</p>
     *
     * <p>因此本测试不锁定「曾存在的 bug」，而是作为<b>行为契约</b>存在：
     * 无论底层装箱如何实现，{@code forget} 对任意 id 都必须让计数正确回落。
     * 若将来有人把谓词改成真正会失效的形态（例如改成 {@code k == Integer.valueOf(id)}
     * 这种每次都新建对象的写法），本测试会立刻失败。</p>
     *
     * <p>此前已有的 {@code forgetRemovesFromParent} 用 id 10 和 11，
     * 落在 {@code Integer.valueOf} 的缓存内，覆盖不到这个区间。</p>
     */
    @Test
    @DisplayName("实例 id 超过 Integer 缓存范围（127）时 forget 仍生效")
    void forgetWorksBeyondIntegerCache() {
        MinionService s = new MinionService();
        s.limits(5, 10);
        int minionA = 5000;   // 远超 Integer.valueOf 的缓存上界
        int minionB = 5001;
        s.register(1, minionA, 1);
        s.register(1, minionB, 1);
        assertEquals(2, s.minionCount(1));

        s.forget(minionA);

        assertEquals(1, s.minionCount(1),
                "id 超出 Integer 缓存范围时 forget 必须仍然生效");
        assertEquals(0, s.depthOf(minionA), "已移除的召唤物深度归零");
        assertEquals(1, s.depthOf(minionB), "同一召唤主下的其它召唤物不受影响");
    }

    /**
     * 反复召唤/移除不应让名额单调增长。
     *
     * <p>比单次 forget 更贴近真实症状：缓存比较的问题在单次调用里不易察觉，
     * 但循环几十次后 {@code minionCount} 明显回不到 0。</p>
     */
    @Test
    @DisplayName("反复召唤移除后计数回到 0（覆盖缓存比较的累积效应）")
    void repeatedRegisterForgetReturnsToZero() {
        MinionService s = new MinionService();
        s.limits(5, 64);
        for (int i = 0; i < 40; i++) {
            int id = 3000 + i;   // 全部在缓存范围外
            s.register(1, id, 1);
        }
        assertEquals(40, s.minionCount(1));
        for (int i = 0; i < 40; i++) {
            s.forget(3000 + i);
        }
        assertEquals(0, s.minionCount(1),
                "40 个召唤物全部死亡后计数应归零；否则说明 forget 存在静默失效");
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