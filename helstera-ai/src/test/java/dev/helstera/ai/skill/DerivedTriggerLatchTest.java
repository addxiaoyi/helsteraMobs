package dev.helstera.ai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派生触发器状态机测试。
 *
 * <p>覆盖五个由「目标 + 血量」推导的触发器：enter-combat / leave-combat /
 * target-change / lost-target / lower-health。</p>
 *
 * <p>重点是<b>边沿语义</b>而非状态值：这些触发器若在状态稳定时反复触发，
 * 表现为 Boss 每 tick 施放一次技能，而不是报错。</p>
 */
class DerivedTriggerLatchTest {

    private final DerivedTriggerLatch l = new DerivedTriggerLatch();
    private static final String K = "1|boss|0";
    private static UUID p(String n) {
        return UUID.nameUUIDFromBytes(n.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("首次采样只建基线，不产生任何边沿")
    void firstSampleIsBaselineOnly() {
        // 两个独立键各自只采一次：首次若有目标，无法区分「刚进入战斗」
        // 与「一直是战斗态」，故不产出边沿
        assertTrue(l.sample("k-with-target", p("a"), 1.0, 50).isEmpty());
        assertTrue(l.sample("k-no-target", null, 1.0, 50).isEmpty());
    }

    @Test
    @DisplayName("首次采样即便血量已低于阈值也不触发 lower-health")
    void firstSampleBelowThresholdIsBaseline() {
        // 同样无法区分「刚被打到阈值下」与「生成时就低于阈值」
        assertTrue(l.sample("k", p("a"), 0.1, 50).isEmpty());
        assertTrue(l.sample("k2", p("a"), 0.1, 50).isEmpty());
    }

    @Test
    @DisplayName("无目标 -> 有目标：进入战斗")
    void enterCombat() {
        l.sample(K, null, 1.0, 50);
        assertEquals(Set.of(SkillTrigger.ENTER_COMBAT), l.sample(K, p("a"), 1.0, 50));
    }

    @Test
    @DisplayName("有目标 -> 无目标：同时产出丢失目标与脱离战斗")
    void leaveCombatAndLostTarget() {
        l.sample(K, p("a"), 1.0, 50);
        Set<SkillTrigger> edges = l.sample(K, null, 1.0, 50);
        assertTrue(edges.contains(SkillTrigger.LEAVE_COMBAT));
        assertTrue(edges.contains(SkillTrigger.LOST_TARGET));
        assertFalse(edges.contains(SkillTrigger.ENTER_COMBAT));
    }

    @Test
    @DisplayName("状态稳定时不重复触发")
    void stableStateDoesNotRetrigger() {
        l.sample(K, null, 1.0, 50);
        l.sample(K, p("a"), 1.0, 50);        // 进入战斗
        for (int i = 0; i < 20; i++) {
            assertTrue(l.sample(K, p("a"), 1.0, 50).isEmpty(),
                    "持续有目标时不应重复触发，进入战斗后第 " + i + " 次采样");
        }
    }

    @Test
    @DisplayName("目标 A -> 目标 B：目标切换")
    void targetChange() {
        l.sample(K, p("a"), 1.0, 50);
        assertEquals(Set.of(SkillTrigger.TARGET_CHANGE), l.sample(K, p("b"), 1.0, 50));
    }

    @Test
    @DisplayName("无目标 -> 有目标不是换人，而是进入战斗")
    void firstTargetIsNotAChange() {
        l.sample(K, null, 1.0, 50);
        Set<SkillTrigger> edges = l.sample(K, p("a"), 1.0, 50);
        assertFalse(edges.contains(SkillTrigger.TARGET_CHANGE),
                "上次无目标时无从比较，不能判为切换");
        assertTrue(edges.contains(SkillTrigger.ENTER_COMBAT));
    }

    @Test
    @DisplayName("血量下穿阈值触发，且只在穿越瞬间一次")
    void lowerHealthOnCrossing() {
        l.sample(K, p("a"), 1.0, 50);
        assertTrue(l.sample(K, p("a"), 0.6, 50).isEmpty(), "60% 未低于 50%");
        assertEquals(Set.of(SkillTrigger.LOWER_HEALTH), l.sample(K, p("a"), 0.4, 50));
        assertTrue(l.sample(K, p("a"), 0.3, 50).isEmpty(), "持续低于阈值不应重复触发");
    }

    @Test
    @DisplayName("血量在阈值附近抖动不反复触发")
    void lowerHealthDebounced() {
        l.sample(K, p("a"), 1.0, 50);
        l.sample(K, p("a"), 0.40, 50);        // 确认为下方
        int fires = 0;
        // 在 50% 上下反复，每 tick 采样一次
        for (int i = 0; i < 100; i++) {
            double ratio = (i % 2 == 0) ? 0.505 : 0.495;
            if (l.sample(K, p("a"), ratio, 50).contains(SkillTrigger.LOWER_HEALTH)) fires++;
        }
        assertTrue(fires <= 2, "擦边抖动应被压制，实际触发 " + fires + " 次");
    }

    @Test
    @DisplayName("阈值不同互不干扰")
    void thresholdsAreIndependent() {
        String k30 = "1|boss|30", k80 = "1|boss|80";
        l.sample(k30, p("a"), 1.0, 30);
        l.sample(k80, p("a"), 1.0, 80);
        assertTrue(l.sample(k30, p("a"), 0.5, 30).isEmpty());
        assertEquals(Set.of(SkillTrigger.LOWER_HEALTH), l.sample(k80, p("a"), 0.5, 80));
    }

    @Test
    @DisplayName("不同观测键互不干扰")
    void keysAreIndependent() {
        l.sample("1|a|c", p("x"), 1.0, 50);
        assertTrue(l.sample("2|a|c", p("y"), 1.0, 50).isEmpty());
    }

    @Test
    @DisplayName("阈值越界被夹到合法范围，不抛异常")
    void clampsThreshold() {
        l.sample(K, p("a"), 1.0, -50);
        l.sample(K, p("a"), 1.0, 1000);
    }

    @Test
    @DisplayName("retainAll 清理失效键并重置基线")
    void retainAllResetsState() {
        l.sample(K, null, 1.0, 50);
        assertEquals(1, l.size());
        l.retainAll(Set.of());
        assertEquals(0, l.size());
        // 键被清理后应重新建基线，不沿用旧记忆误报边沿
        assertTrue(l.sample(K, p("a"), 1.0, 50).isEmpty());
    }

    @Test
    @DisplayName("clear 清空全部状态")
    void clearResets() {
        l.sample(K, p("a"), 1.0, 50);
        l.clear();
        assertEquals(0, l.size());
    }

    // ---- 进出水 ----

    @Test
    @DisplayName("入水/离水各自只在翻转那一次产出边沿")
    void waterEdgesOnTransition() {
        String k = "w1|boss|0";
        assertTrue(l.sample(k, null, 1.0, 50, false).isEmpty(), "首次采样只建基线");
        assertEquals(Set.of(SkillTrigger.ENTER_WATER), l.sample(k, null, 1.0, 50, true));
        // 持续泡在水里必须静默：否则玩家站水里会每 tick 触发一次
        for (int i = 0; i < 20; i++) {
            assertTrue(l.sample(k, null, 1.0, 50, true).isEmpty(), "持续在水中不应重复触发");
        }
        assertEquals(Set.of(SkillTrigger.LEAVE_WATER), l.sample(k, null, 1.0, 50, false));
    }

    @Test
    @DisplayName("首次采样即便已在水中也不触发 enter-water")
    void firstSampleInWaterIsBaseline() {
        assertTrue(l.sample("w2", null, 1.0, 50, true).isEmpty(),
                "生成时就在水里无法区分「刚入水」与「一直在水里」");
    }

    @Test
    @DisplayName("进出水与战斗边沿互不干扰，同一次采样可同时产出")
    void waterAndCombatAreIndependent() {
        String k = "w3|boss|0";
        l.sample(k, null, 1.0, 50, false);
        Set<SkillTrigger> edges = l.sample(k, p("a"), 1.0, 50, true);
        assertTrue(edges.contains(SkillTrigger.ENTER_WATER));
        assertTrue(edges.contains(SkillTrigger.ENTER_COMBAT));
    }

    @Test
    @DisplayName("不传水状态的重载等价于 inWater=false")
    void shortOverloadMatchesDrySample() {
        var a = new DerivedTriggerLatch();
        var b = new DerivedTriggerLatch();
        String k = "w4|boss|0";
        assertEquals(a.sample(k, p("a"), 1.0, 50), b.sample(k, p("a"), 1.0, 50, false));
        assertEquals(a.sample(k, null, 0.2, 50), b.sample(k, null, 0.2, 50, false));
    }
}