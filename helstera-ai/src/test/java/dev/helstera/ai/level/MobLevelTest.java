package dev.helstera.ai.level;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 等级缩放决策层测试。
 *
 * <p>重点守三件「写错了服上只表现为数值不对」的事：
 * <b>未分配等级不缩放</b>、<b>增长幂次正确</b>、<b>属性名拼错不炸系统</b>。</p>
 */
class MobLevelTest {

    private static final MobLevel.ScalingConfig HEALTH =
            new MobLevel.ScalingConfig("health", 1000, 1.05);
    private static final MobLevel.ScalingConfig DAMAGE =
            new MobLevel.ScalingConfig("damage", 10, 1.10);
    private static final MobLevel.ScalingConfig SPEED =
            new MobLevel.ScalingConfig("speed", 0.3, 1.02);

    @Test
    @DisplayName("等级 <=0 时所有属性为 0（与「未分配等级」语义一致）")
    void zeroLevelIsZero() {
        var r = MobLevel.compute(0, HEALTH, DAMAGE, SPEED);
        assertEquals(0, r.health(), 1e-9);
        assertEquals(0, r.damage(), 1e-9);
        assertEquals(0, r.speed(), 1e-9);
    }

    @Test
    @DisplayName("等级 1 时所有属性等于 base")
    void levelOneIsBase() {
        var r = MobLevel.compute(1, HEALTH, DAMAGE, SPEED);
        assertEquals(1000, r.health(), 1e-9);
        assertEquals(10, r.damage(), 1e-9);
        assertEquals(0.3, r.speed(), 1e-9);
    }

    @Test
    @DisplayName("等级 10 时生命值约等于 base × 1.05^9")
    void healthScalesExponentially() {
        var r = MobLevel.compute(10, HEALTH);
        double expected = 1000 * Math.pow(1.05, 9);
        assertEquals(expected, r.health(), 1e-3,
                "等级 10 应该按 9 次幂增长，而非 10 次");
    }

    @Test
    @DisplayName("伤害与速度各自独立缩放，互不干扰")
    void independentScaling() {
        var r = MobLevel.compute(20, HEALTH, DAMAGE, SPEED);
        assertTrue(r.damage() > r.health() / 100, "20 级伤害应明显高于生命值除以 100");
        assertTrue(r.speed() > 0.3);
    }

    @Test
    @DisplayName("growthPerLevel=1.0 时属性值不随等级变化")
    void flatGrowthIsConstant() {
        var flat = new MobLevel.ScalingConfig("health", 1000, 1.0);
        var r1 = MobLevel.compute(1, flat);
        var r10 = MobLevel.compute(10, flat);
        assertEquals(r1.health(), r10.health(), 1e-9,
                "growth=1.0 时任何等级都不该有缩放变化");
    }

    @Test
    @DisplayName("未知属性名静默忽略，不影响其他属性")
    void unknownPropertyIgnored() {
        var bad = new MobLevel.ScalingConfig("hp", 1000, 1.10);
        var good = new MobLevel.ScalingConfig("damage", 10, 1.20);
        var r = MobLevel.compute(5, bad, good);
        assertEquals(0, r.health(), 1e-9,
                "hp 不是已知属性名，应被忽略，health 保持默认值 0");
        assertEquals(10 * Math.pow(1.20, 4), r.damage(), 1e-6,
                "damage 应正常生效");
    }

    @Test
    @DisplayName("base<=0 时属性值返回 0")
    void zeroBaseYieldsZero() {
        var cfg = new MobLevel.ScalingConfig("health", 0, 1.10);
        var r = MobLevel.compute(5, cfg);
        assertEquals(0, r.health(), 1e-9);
    }

    @Test
    @DisplayName("null configs 返回空结果（不抛 NPE）")
    void nullConfigsHandled() {
        var r = MobLevel.compute(5, (MobLevel.ScalingConfig[]) null);
        assertEquals(MobLevel.LevelResult.empty(), r);
    }

    @Test
    @DisplayName("配置构造期允许非法值，由计算路径自然处理")
    void constructorAllowsInvalid() {
        var bad = new MobLevel.ScalingConfig("health", -100, 0.5);
        assertEquals(-100, bad.base(), "base 负数应原样保留，由 compute 处理");
        assertEquals(0.5, bad.growthPerLevel(), "growth<1 应原样保留");
        var r = MobLevel.compute(5, bad);
        // base 为负时 scale 仍为正，结果仍为负——这是作者写错时的可预期行为
        assertTrue(r.health() < 0, "base 为负时结果应为负，不静默修正");
    }

    @Test
    @DisplayName("等级越高伤害值越大（单调递增）")
    void monotonicDamage() {
        var dmg = new MobLevel.ScalingConfig("damage", 10, 1.15);
        double l5 = MobLevel.compute(5, dmg).damage();
        double l10 = MobLevel.compute(10, dmg).damage();
        double l20 = MobLevel.compute(20, dmg).damage();
        assertTrue(l5 < l10 && l10 < l20, "等级 5 < 10 < 20 时伤害必须严格递增");
    }
}
