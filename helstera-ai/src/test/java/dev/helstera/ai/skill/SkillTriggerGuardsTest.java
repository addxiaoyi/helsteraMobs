package dev.helstera.ai.skill;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触发器守卫与键构造的测试。
 *
 * <p>覆盖两处「写错不报错」的逻辑：{@code on-kill-player} 的死者类型守卫，
 * 以及条件观测键的拼接口径。</p>
 *
 * <p>后者尤其关键：{@code pollConditions} 与清理阶段若用不同规则拼键，
 * 清理会把仍在用的键当死键删掉，表现为「条件触发器跑一段时间后永久失效」，
 * 编译器查不出、日志无异常，只有字符串断言能挡住。</p>
 */
class SkillTriggerGuardsTest {

    /**
     * 造一个只满足类型检查的空壳 Player。
     *
     * <p>用动态代理而非 Mockito：测试栈是纯离线的，且此处只需要
     * {@code instanceof} 判定，不调用任何方法。代理不实现任何逻辑，
     * 任何方法调用都会抛 {@code UnsupportedOperationException}——
     * 这恰好能在守卫误调方法时立刻暴露。</p>
     */
    private static Player fakePlayer() {
        return (Player) Proxy.newProxyInstance(
                SkillTriggerGuardsTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    if ("isDead".equals(method.getName())) return false;
                    if ("getName".equals(method.getName())) return "Tester";
                    throw new UnsupportedOperationException("测试不应调用 " + method.getName());
                });
    }

    private static Entity fakeNonPlayer() {
        return (Entity) Proxy.newProxyInstance(
                SkillTriggerGuardsTest.class.getClassLoader(),
                new Class<?>[]{Entity.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException("测试不应调用 " + method.getName());
                });
    }

    // ---------- on-kill-player 守卫 ----------

    @Test
    @DisplayName("死者是玩家且有击杀者时才派发")
    void firesWhenPlayerKilledBySomeone() {
        assertTrue(SkillTriggers.shouldFireKillPlayer(fakePlayer(), fakePlayer()));
    }

    @Test
    @DisplayName("死者是玩家但无击杀者（摔死/岩浆）不派发")
    void doesNotFireOnNaturalPlayerDeath() {
        // 这是本守卫存在的主要理由：玩家在野外摔死会走 EntityDeathEvent，
        // 不判击杀者就会触发 Boss 的处决播报
        assertFalse(SkillTriggers.shouldFireKillPlayer(fakePlayer(), null));
    }

    @Test
    @DisplayName("死者不是玩家不派发")
    void doesNotFireOnMobDeath() {
        assertFalse(SkillTriggers.shouldFireKillPlayer(fakeNonPlayer(), fakePlayer()));
    }

    @Test
    @DisplayName("两侧为 null 时安全返回 false")
    void toleratesNulls() {
        assertFalse(SkillTriggers.shouldFireKillPlayer(null, null));
        assertFalse(SkillTriggers.shouldFireKillPlayer(null, fakePlayer()));
        assertFalse(SkillTriggers.shouldFireKillPlayer(fakeNonPlayer(), null));
    }

    @Test
    @DisplayName("怪物被玩家击杀也不触发 on-kill-player")
    void mobKilledByPlayerDoesNotCount() {
        // on-kill-player 语义是「本生物杀了玩家」，不是「本生物杀了某个东西」
        assertFalse(SkillTriggers.shouldFireKillPlayer(fakeNonPlayer(), fakePlayer()));
    }

    // ---------- 条件观测键 ----------

    @Test
    @DisplayName("键格式为 实例id|档案名|条件")
    void keyFormat() {
        assertEquals("42|fire-lord|health-below 0.3",
                SkillTriggers.conditionKey(42, "fire-lord", "health-below 0.3"));
    }

    @Test
    @DisplayName("键可反解出实例 id")
    void keyRoundTrip() {
        assertEquals(42, SkillTriggers.instanceIdOfConditionKey(
                SkillTriggers.conditionKey(42, "boss", "has-target")));
    }

    @Test
    @DisplayName("条件含分隔符也不影响反解（只取第一段）")
    void roundTripSurvivesPipesInCondition() {
        // 条件本身可能含 | 之外的特殊字符，反解只依赖首个分隔符
        String key = SkillTriggers.conditionKey(7, "p", "a && b");
        assertEquals(7, SkillTriggers.instanceIdOfConditionKey(key));
    }

    @Test
    @DisplayName("非法键返回 -1 而不抛异常")
    void malformedKeysReturnMinusOne() {
        assertEquals(-1, SkillTriggers.instanceIdOfConditionKey(null));
        assertEquals(-1, SkillTriggers.instanceIdOfConditionKey(""));
        assertEquals(-1, SkillTriggers.instanceIdOfConditionKey("|no-id|cond"));
        assertEquals(-1, SkillTriggers.instanceIdOfConditionKey("notanumber|p|c"));
    }

    @Test
    @DisplayName("不同实例/档案/条件产生互不相同的键")
    void keysAreDistinct() {
        assertFalse(SkillTriggers.conditionKey(1, "p", "c")
                .equals(SkillTriggers.conditionKey(2, "p", "c")));
        assertFalse(SkillTriggers.conditionKey(1, "p", "c")
                .equals(SkillTriggers.conditionKey(1, "q", "c")));
        assertFalse(SkillTriggers.conditionKey(1, "p", "c")
                .equals(SkillTriggers.conditionKey(1, "p", "d")));
    }

    @Test
    @DisplayName("键可被 retainAll 精确保留，死实例键被清掉")
    void latchRetainsOnlyLiveKeys() {
        var latch = new ConditionLatch(1);
        latch.rising(SkillTriggers.conditionKey(1, "boss", "health-below"), true);
        latch.rising(SkillTriggers.conditionKey(2, "boss", "health-below"), true);
        assertEquals(2, latch.size());

        // 实例 2 已被移除，只保留实例 1 的键
        latch.retainAll(java.util.Set.of(SkillTriggers.conditionKey(1, "boss", "health-below")));
        assertEquals(1, latch.size());
        assertTrue(latch.initialized(SkillTriggers.conditionKey(1, "boss", "health-below")));
    }

    @Test
    @DisplayName("清理后被误删的键会重建立基线而非沿用旧状态")
    void clearedKeyRebuildsBaseline() {
        var latch = new ConditionLatch(1);
        String key = SkillTriggers.conditionKey(9, "boss", "c");
        latch.rising(key, true);
        latch.retainAll(java.util.Set.of());      // 模拟实例死亡后被清理
        assertEquals(0, latch.size());
        // 重新出现时应重新建立基线，不应因「记得旧值」而误报边沿
        assertFalse(latch.rising(key, true));
    }
}