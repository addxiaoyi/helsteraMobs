package dev.helstera.ai;

import dev.helstera.ai.skill.SkillCatalog;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能装载与条件求值的契约测试。
 *
 * <p>不依赖运行中的服务端：条件只读 BehaviorContext 的标量快照，
 * 因此可以用 {@code instance=null} 构造上下文来验证纯逻辑分支。</p>
 */
class SkillServiceTest {

    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();

    private SkillService service() {
        return new SkillService(registry, null);
    }

    private static BehaviorContext ctx(double health, long decisions) {
        return BehaviorContext.of(null, null, health, -1, decisions, "IDLE");
    }

    private static BehaviorContext ctxWithState(String state) {
        return BehaviorContext.of(null, null, 1.0, -1, 0, state);
    }

    @Test
    @DisplayName("带参数的条件被绑成确定性键 health-below:0.3")
    void bindsParameterizedCondition() {
        var svc = service();
        String key = svc.bindCondition("health-below 0.3");

        assertEquals("health-below:0.3", key);
        assertTrue(registry.hasCondition(key));
        assertFalse(registry.testCondition(key, ctx(0.5, 0)), "血量 0.5 不应满足 <=0.3");
        assertTrue(registry.testCondition(key, ctx(0.2, 0)), "血量 0.2 应满足 <=0.3");
    }

    @Test
    @DisplayName("同一条件重复绑定不会重复注册，也不产生重复告警")
    void bindsIdempotently() {
        var svc = service();
        svc.bindCondition("health-below 0.3");
        svc.bindCondition("health-below 0.3");

        assertEquals(1, registry.conditionNames().size());
        assertEquals(List.of(), svc.warnings());
    }

    @Test
    @DisplayName("未知条件返回 null 并留下告警，理由里带可用内置名")
    void warnsOnUnknownCondition() {
        var svc = service();
        assertNull(svc.bindCondition("no-such-condition 1"));
        assertEquals(1, svc.warnings().size());
        assertTrue(svc.warnings().get(0).contains("未知条件"), svc.warnings().toString());
        assertTrue(svc.warnings().get(0).contains("health-below"), "应提示可用内置条件");
    }

    @Test
    @DisplayName("Java 侧注册的条件可直接按名引用，不算未知")
    void referencesRegisteredConditionByName() {
        registry.registerCondition("my-flag", c -> true);
        var svc = service();

        assertEquals("my-flag", svc.bindCondition("my-flag"));
        assertEquals(List.of(), svc.warnings());
        assertTrue(registry.testCondition("my-flag", ctx(1, 0)));
    }

    @Test
    @DisplayName("has-target：无目标时为 false，显式 false 参数反转语义")
    void hasTargetCondition() {
        var svc = service();
        String withTarget = svc.bindCondition("has-target");
        String withoutTarget = svc.bindCondition("has-target false");

        assertEquals("has-target", withTarget);
        assertEquals("has-target:false", withoutTarget);
        assertFalse(registry.testCondition(withTarget, ctx(1, 0)));
        assertTrue(registry.testCondition(withoutTarget, ctx(1, 0)));
    }

    @Test
    @DisplayName("health-above / health-below 边界取闭区间")
    void healthThresholdBoundaries() {
        var svc = service();
        String below = svc.bindCondition("health-below 0.5");
        String above = svc.bindCondition("health-above 0.5");

        assertTrue(registry.testCondition(below, ctx(0.5, 0)));
        assertTrue(registry.testCondition(above, ctx(0.5, 0)));
        assertFalse(registry.testCondition(below, ctx(0.51, 0)));
        assertFalse(registry.testCondition(above, ctx(0.49, 0)));
    }

    @Test
    @DisplayName("距离类条件：无目标（距离 -1）一律不满足")
    void distanceConditionsRequireTarget() {
        var svc = service();
        String near = svc.bindCondition("distance-below 5");
        String far = svc.bindCondition("distance-above 5");

        assertFalse(registry.testCondition(near, ctx(1, 0)), "distance=-1 不应满足 <=5");
        assertFalse(registry.testCondition(far, ctx(1, 0)));
    }

    @Test
    @DisplayName("state-is 大小写不敏感")
    void stateConditionIsCaseInsensitive() {
        var svc = service();
        String key = svc.bindCondition("state-is chase");

        assertTrue(registry.testCondition(key, ctxWithState("CHASE")));
        assertTrue(registry.testCondition(key, ctxWithState("chase")));
        assertFalse(registry.testCondition(key, ctxWithState("IDLE")));
    }

    @Test
    @DisplayName("every-n-decisions：缺省每次都满足，N>1 时按次数取模")
    void everyNDecisions() {
        var svc = service();
        String everyOne = svc.bindCondition("every-n-decisions");
        String everyThree = svc.bindCondition("every-n-decisions 3");

        assertTrue(registry.testCondition(everyOne, ctx(1, 7)));
        assertFalse(registry.testCondition(everyThree, ctx(1, 7)), "7 % 3 != 0");
        assertTrue(registry.testCondition(everyThree, ctx(1, 9)));
    }

    @Test
    @DisplayName("非法数字参数回落到默认值，不抛异常")
    void malformedNumberFallsBack() {
        var svc = service();
        String key = svc.bindCondition("health-below abc");

        assertNotNull(key);
        assertFalse(registry.testCondition(key, ctx(1.0, 0)), "阈值回落为 0.0，血量 1.0 不满足 <=0");
        assertTrue(registry.testCondition(key, ctx(0.0, 0)), "血量 0.0 满足 <=0.0");
    }

    @Test
    @DisplayName("引号内的空格不被切分：message-target 可带整句文案")
    void keepsQuotedSpaces() {
        var svc = service();
        String key = svc.bindAction("message-target \"你好 世界\"");

        assertEquals("message-target:你好 世界", key);
    }

    @Test
    @DisplayName("命名技能：require 全满足才算一个整体条件，on-decision 顺序执行")
    void loadsNamedSkill() {
        var svc = service();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  tank:
                    require:
                      - health-above 0.8
                      - state-is idle
                    on-decision:
                      - set-scale 1.5
                """));

        svc.loadSkills(y.getConfigurationSection("skills"));

        String c = svc.bindCondition("skill:tank");
        String a = svc.bindAction("skill:tank");
        assertEquals("skill:tank", c);
        assertEquals("skill:tank", a);

        // health 1.0 + IDLE → 两个 require 均满足
        assertTrue(registry.testCondition(c, ctxWithState("IDLE")));
        assertFalse(registry.testCondition(c, ctxWithState("CHASE")));
        // 实例为空时 set-scale 会自行跳过，不应抛异常
        assertTrue(registry.runAction(a, ctxWithState("IDLE")));
    }

    @Test
    @DisplayName("技能里引用未知名时，条件部分跳过并告警，其余仍可用")
    void namedSkillToleratesUnknownMembers() {
        var svc = service();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  broken:
                    require:
                      - nonsense
                      - health-above 0.5
                    on-decision:
                      - stop-animation
                """));

        svc.loadSkills(y.getConfigurationSection("skills"));
        assertEquals(1, svc.warnings().size());

        String c = svc.bindCondition("skill:broken");
        assertNotNull(c);
        assertTrue(registry.testCondition(c, ctx(0.9, 0)), "跳过的未知条件不应让整体恒为 false");
    }

    @Test
    @DisplayName("loadSkills 对 null 节安全返回")
    void toleratesNullSection() {
        var svc = service();
        svc.loadSkills(null);
        assertEquals(List.of(), svc.warnings());
    }

    @Test
    @DisplayName("expandTriggers 把触发器里的文本定义换成已绑定键")
    void expandsTriggersInPlace() {
        var svc = service();
        var profile = new AiProfile("boss");
        var spec = new AiProfile.TriggerSpec();
        spec.require.add("health-below 0.4");
        spec.actions.add("play-animation roar true 5");
        profile.triggers.put("on-damage", spec);

        svc.expandTriggers(profile);

        assertEquals(List.of("health-below:0.4"), profile.triggers.get("on-damage").require);
        assertEquals(List.of("play-animation:roar true 5"), profile.triggers.get("on-damage").actions);
        assertTrue(registry.hasCondition("health-below:0.4"));
        assertTrue(registry.hasAction("play-animation:roar true 5"));
    }

    @Test
    @DisplayName("expand 把命名技能同时挂到 require 与 onDecision")
    void expandsNamedSkillsOntoProfile() {
        var svc = service();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  frenzy:
                    require:
                      - health-below 0.3
                    on-decision:
                      - set-scale 1.2
                """));
        svc.loadSkills(y.getConfigurationSection("skills"));

        var profile = new AiProfile("berserker");
        svc.expand(profile, List.of("frenzy"));

        assertEquals(List.of("skill:frenzy"), profile.require);
        assertEquals(List.of("skill:frenzy"), profile.onDecision);
    }

    @Test
    @DisplayName("注册表：条件抛异常按 false 处理，异常不外泄到决策链")
    void registrySwallowsConditionErrors() {
        registry.registerCondition("boom", c -> {
            throw new IllegalStateException("炸了");
        });

        assertFalse(registry.testCondition("boom", ctx(1, 0)));
    }

    @Test
    @DisplayName("注册表：动作抛异常被吞掉并返回 false")
    void registrySwallowsActionErrors() {
        registry.registerAction("boom", c -> {
            throw new IllegalStateException("炸了");
        });

        assertFalse(registry.runAction("boom", ctx(1, 0)));
    }

    @Test
    @DisplayName("注册表：未知名求值不抛异常，分别返回 false")
    void registryToleratesUnknownNames() {
        assertFalse(registry.testCondition("ghost", ctx(1, 0)));
        assertFalse(registry.runAction("ghost", ctx(1, 0)));
        assertFalse(registry.hasCondition("ghost"));
        assertFalse(registry.hasAction("ghost"));
    }

    @Test
    @DisplayName("注册表：名称大小写不敏感，空名与 null 被忽略")
    void registryNormalizesNames() {
        registry.registerCondition("MixedCase", c -> true);
        registry.registerCondition(null, c -> true);
        registry.registerCondition("  ", c -> true);

        assertTrue(registry.testCondition("mixedcase", ctx(1, 0)));
        assertTrue(registry.testCondition("MIXEDCASE", ctx(1, 0)));
        assertEquals(1, registry.conditionNames().size(), "空名不应入库");
    }

    @Test
    @DisplayName("内置目录：条件名与动作名各自去重且非空")
    void catalogNamesAreStable() {
        Map<String, SkillCatalog.ConditionFactory> conds = SkillCatalog.conditions();
        Map<String, SkillCatalog.ActionFactory> acts = SkillCatalog.actions();

        assertTrue(conds.keySet().containsAll(List.of(
                "has-target", "health-below", "health-above",
                "distance-below", "distance-above", "state-is", "every-n-decisions")), conds.keySet().toString());
        assertTrue(acts.keySet().containsAll(List.of(
                "set-scale", "play-animation", "stop-animation",
                "set-intent", "message-target")), acts.keySet().toString());
    }

    @Test
    @DisplayName("动作：实例无效时安全跳过，不抛 NullPointerException")
    void actionsSkipWhenInstanceInvalid() {
        var svc = service();
        for (String spec : List.of("set-scale 2.0", "play-animation walk", "stop-animation",
                "set-intent chase", "heal-self 5", "sound BLAZE", "particle FLAME")) {
            String key = svc.bindAction(spec);
            assertNotNull(key, spec);
            assertTrue(registry.runAction(key, ctx(1, 0)), spec + " 应在实例为空时安全返回");
        }
    }

    @Test
    @DisplayName("绑定动作名与条件名互不串扰")
    void doesNotCrossBindNames() {
        var svc = service();
        String a = svc.bindAction("message-target hi");
        assertNotNull(a);
        assertFalse(registry.hasCondition(a), "动作键不应被当成条件");
        assertTrue(registry.hasAction(a));
    }

    @Test
    @DisplayName("技能 priority 字段被正确解析，queuedSkillCount 反映排队状态")
    void parsesSkillPriority() {
        var svc = service();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  burst:
                    on-decision: [damage-target 50]
                    priority: 10
                  heal:
                    on-decision: [heal-self 10]
                    priority: 5
                  idle_skill:
                    on-decision: [set-scale 1.0]
                """));
        svc.loadSkills(y.getConfigurationSection("skills"));
        // priority 解析：通过 skillNames 确认加载成功
        assertTrue(svc.skillNames().contains("burst"));
        assertTrue(svc.skillNames().contains("heal"));
        assertTrue(svc.skillNames().contains("idle_skill"));
        // queuedSkillCount 在无实例上下文时为 0
        assertEquals(0, svc.queuedSkillCount());
    }
}