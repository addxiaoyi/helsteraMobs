package dev.helstera.ai;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.ai.skill.SkillCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    @DisplayName("距离参数非法时回退到默认值，不产生零位移或异常")
    void movementBadArgs() {
        factory("dash").create(List.of("abc")).accept(ctx());
        factory("dash").create(List.of("-3")).accept(ctx());
        factory("blink").create(List.of()).accept(ctx());
        factory("knockback-self").create(List.of("x", "y")).accept(ctx());
    }

    @Test
    @DisplayName("朝向取值非法时退回默认方向")
    void movementBadDirection() {
        factory("dash").create(List.of("5", "not-a-direction")).accept(ctx());
        factory("blink").create(List.of("5", "back")).accept(ctx());
        factory("blink").create(List.of("5", "away")).accept(ctx());
    }

    @Test
    @DisplayName("既有动作未受影响")
    void existingActionsIntact() {
        var all = SkillCatalog.actions();
        for (String n : List.of("set-scale", "play-animation", "damage-target", "aoe-damage",
                "teleport-targets", "effect-targets", "ignite-targets", "knockback-targets")) {
            assertTrue(all.containsKey(n), "既有动作 " + n + " 不应丢失");
        }
    }
}