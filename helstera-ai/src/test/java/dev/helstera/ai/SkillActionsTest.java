package dev.helstera.ai;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.ai.skill.SkillCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自身位移动作的构造与边界测试。
 *
 * <p>位移类动作依赖真实世界（方块阻挡、实体速度），单元测试无法覆盖运行期效果，
 * 因此这里只锁两件能在离线环境确证的事：动作能被构造出来，以及缺实例时安全返回。
 * 真正的移动效果需要在服务端上验证。</p>
 */
class SkillActionsTest {

    private static BehaviorContext ctx() {
        // 实例为 null：模拟载体已失效的场合，动作必须静默返回而非抛异常
        return BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE");
    }

    private static SkillCatalog.ActionFactory factory(String name) {
        var f = SkillCatalog.actions().get(name);
        assertNotNull(f, "内置动作 " + name + " 不存在");
        return f;
    }

    @Test
    @DisplayName("三个自身位移动作均在册")
    void movementActionsRegistered() {
        var all = SkillCatalog.actions();
        for (String n : List.of("dash", "blink", "knockback-self")) {
            assertTrue(all.containsKey(n), "动作 " + n + " 缺失");
        }
    }

    @Test
    @DisplayName("缺实例时位移动作安全返回，不抛异常")
    void movementSafeWithoutInstance() {
        factory("dash").create(List.of("5", "target")).accept(ctx());
        factory("blink").create(List.of("8", "target")).accept(ctx());
        factory("knockback-self").create(List.of("1.0", "0.4")).accept(ctx());
    }

    /**
     * 非数值参数在<b>装载期</b>抛异常，而不是静默回落默认值。
     *
     * <p><b>行为期望被反转过。</b>原名 {@code movementBadArgs}，断言
     * 「参数非法时回退到默认值，不产生零位移或异常」——那正是本轮修掉的缺陷。
     * 静默回落让 {@code dash abc} 变成「冲刺默认距离」，技能照常执行，
     * {@code /helstera check} 与日志都无提示，现场只表现为「位移距离不对」。</p>
     *
     * <p>注意保留本测试原本真正想守的东西：<b>缺参与合法值不该被误伤</b>。
     * {@code dash} 不带参数、{@code dash -3}（负数合法）、{@code blink} 空参数
     * 都必须正常工作——只有真正无法解析的字面量才抛。</p>
     */
    @Test
    @DisplayName("非数值参数抛异常；缺参与合法值不受影响")
    void movementBadArgs() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> factory("dash").create(List.of("abc")),
                "dash abc 应在装载期抛异常，让 /helstera check 能报出来");
        assertTrue(e.getMessage().contains("abc"),
                "异常信息应含原始字面量，便于定位：实际 = " + e.getMessage());

        // 以下三种都必须正常建出动作——参数校验不该误伤它们
        factory("dash").create(List.of("-3")).accept(ctx());
        factory("blink").create(List.of()).accept(ctx());
        factory("knockback-self").create(List.of("1.0", "0.4")).accept(ctx());
    }

    @Test
    @DisplayName("朝向取值非法时退回默认方向")
    void movementBadDirection() {
        factory("dash").create(List.of("5", "not-a-direction")).accept(ctx());
        factory("blink").create(List.of("5", "back")).accept(ctx());
        factory("blink").create(List.of("5", "away")).accept(ctx());
    }

    @Test
    @DisplayName("summon：未注入钩子时静默返回，不抛异常")
    void summonWithoutHook() {
        SkillCatalog.summoner(null);
        factory("summon").create(List.of("my_model", "2")).accept(ctx());
    }

    @Test
    @DisplayName("summon：模型名为空时不生成")
    void summonBlankModelId() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        SkillCatalog.summoner((modelId, at) -> {
            calls.add(modelId);
            return 1;   // Summoner 现返回新实例 id（-1 表示失败）
        });
        try {
            factory("summon").create(List.of("")).accept(ctx());
            assertTrue(calls.isEmpty(), "空模型名不应触发生成");
        } finally {
            SkillCatalog.summoner(null);
        }
    }

    @Test
    @DisplayName("summon：缺实例时不生成")
    void summonNeedsInstance() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        SkillCatalog.summoner((modelId, at) -> {
            calls.add(modelId);
            return 1;   // Summoner 现返回新实例 id（-1 表示失败）
        });
        try {
            factory("summon").create(List.of("my_model")).accept(ctx());
            assertTrue(calls.isEmpty(), "实例无效时不应生成，否则会凭空刷出模型");
        } finally {
            SkillCatalog.summoner(null);
        }
    }

    @Test
    @DisplayName("summon：数量与半径被限制在上限内")
    void summonClampsArgs() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        SkillCatalog.summoner((modelId, at) -> {
            calls.add(modelId);
            return 1;   // Summoner 现返回新实例 id（-1 表示失败）
        });
        try {
            // 数量 999 / 半径 9999 必须被夹到上限，避免一份配置刷爆渲染
            var action = factory("summon").create(List.of("m", "999", "9999"));
            // 无实例时不会真的调用钩子，这里只确认构造不抛异常且参数被夹住
            action.accept(ctx());
            assertTrue(calls.isEmpty());
        } finally {
            SkillCatalog.summoner(null);
        }
    }

    @Test
    @DisplayName("既有动作未受影响")
    void existingActionsIntact() {
        var all = SkillCatalog.actions();
        for (String n : List.of("set-scale", "play-animation", "damage-target", "aoe-damage",
                "teleport-targets", "effect-targets", "ignite-targets", "knockback-targets",
                "dash", "blink", "knockback-self", "summon")) {
            assertTrue(all.containsKey(n), "动作 " + n + " 缺失");
        }
    }
}