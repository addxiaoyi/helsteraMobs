package dev.helstera.ai;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.ai.skill.SkillCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新增触发条件的行为测试。
 *
 * <p>重点是<b>无目标 / 无实例</b>这些边界：条件用于 require 时，
 * 一旦在缺数据的情况下返回 true，技能就会在不该触发的场合反复放行。</p>
 */
class SkillConditionsTest {

    private static BehaviorContext ctx() {
        // 实例与目标均为 null：模拟实体尚未绑定 / 无仇恨目标的场合
        return BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE");
    }

    private static SkillCatalog.ConditionFactory factory(String name) {
        var f = SkillCatalog.conditions().get(name);
        assertNotNull(f, "内置条件 " + name + " 不存在");
        return f;
    }

    // ---- has-line-of-sight ----

    @Test
    @DisplayName("has-line-of-sight：无目标时为 false，不得无条件放行")
    void lineOfSightNeedsTarget() {
        var p = factory("has-line-of-sight").create(List.of());
        assertFalse(p.test(ctx()), "缺数据时必须判否，否则技能会在无目标时反复触发");
    }

    @Test
    @DisplayName("has-line-of-sight：实例无效时为 false")
    void lineOfSightNeedsInstance() {
        var p = factory("has-line-of-sight").create(List.of());
        // ctx() 里 instance 为 null，instanceValid() 为 false
        assertFalse(p.test(ctx()));
    }

    // ---- target-is ----

    @Test
    @DisplayName("target-is：未知取值恒为 false，不因配置写错而放行")
    void targetIsUnknownKindIsFalse() {
        var p = factory("target-is").create(List.of("definitely-not-a-kind"));
        assertFalse(p.test(ctx()), "未知取值应判否，否则拼错的条件会变成永真");
    }

    @Test
    @DisplayName("target-is：无目标时为 false")
    void targetIsWithoutTarget() {
        var p = factory("target-is").create(List.of("player"));
        assertFalse(p.test(ctx()));
    }

    @Test
    @DisplayName("target-is：游戏模式取值被登记，供玩家上下文求值")
    void targetIsRegistersGameModes() {
        for (String mode : List.of("survival", "creative", "adventure", "spectator")) {
            assertNotNull(factory("target-is").create(List.of(mode)));
        }
    }

    // ---- cooldown-ready ----

    @Test
    @DisplayName("cooldown-ready：间隔为 0 时恒放行")
    void cooldownZeroAlwaysReady() {
        var p = factory("cooldown-ready").create(List.of("0"));
        assertTrue(p.test(ctx()));
    }

    @Test
    @DisplayName("cooldown-ready：负数间隔按 0 处理，不产生永久锁死")
    void cooldownNegativeTreatedAsZero() {
        var p = factory("cooldown-ready").create(List.of("-5"));
        assertTrue(p.test(ctx()), "非法参数应回退为无冷却，而非卡住不放");
    }

    @Test
    @DisplayName("cooldown-ready：实例无效时为 false")
    void cooldownNeedsInstance() {
        var p = factory("cooldown-ready").create(List.of("10"));
        assertFalse(p.test(ctx()), "无实例时无法记录冷却时间，应判否");
    }

    @Test
    @DisplayName("cooldown-ready：参数缺失回退为 0（无冷却）")
    void cooldownMissingArg() {
        var p = factory("cooldown-ready").create(List.of());
        assertTrue(p.test(ctx()));
    }

    @Test
    @DisplayName("cooldown-ready：非数字参数回退为 0，不抛异常")
    void cooldownNonNumericArg() {
        var p = factory("cooldown-ready").create(List.of("abc"));
        assertTrue(p.test(ctx()), "解析失败应回退默认值，而不是中断技能加载");
    }

    // ---- 回归：既有条件不受影响 ----

    @Test
    @DisplayName("既有条件仍在册，且距离类条件保持原语义")
    void existingConditionsIntact() {
        var all = SkillCatalog.conditions();
        for (String n : List.of("health-below", "health-above", "distance-below", "distance-above",
                "state-is", "targets-exist", "targets-in-range", "every-n-decisions")) {
            assertTrue(all.containsKey(n), "既有条件 " + n + " 不应丢失");
        }
        // 无目标时 distance-below 必须为 false
        var below = all.get("distance-below").create(List.of("10"));
        assertFalse(below.test(ctx()), "距离未知时不应判为近距离");
    }
}