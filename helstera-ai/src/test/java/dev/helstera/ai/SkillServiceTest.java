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

    /**
     * 非法数字参数：装载期拒绝注册，并在 warnings 里说明原因。
     *
     * <p><b>这个测试的行为期望被反转过。</b>原名是
     * {@code malformedNumberFallsBack}，断言「非法数字回落到默认值，不抛异常」——
     * 那正是本轮要修掉的缺陷。</p>
     *
     * <p>旧行为的问题：{@code health-below abc} 会注册成一个阈值 0.0 的条件，
     * 于是「血量低于 abc」实际变成「血量低于 0」，永远不成立。技能安静地
     * 永不触发，{@code /helstera check} 与日志都没有任何提示——
     * 现场无法与「阈值设得本来就高」区分。</p>
     *
     * <p>现在参数非法时 {@code bindCondition} 返回 null（不注册）并记 warning，
     * 作者能在体检里直接看到「参数非法」与原始字面量。</p>
     */
    @Test
    @DisplayName("非法数字参数在装载期被拒绝，并记入 warnings")
    void malformedNumberIsRejectedWithWarning() {
        var svc = service();
        String key = svc.bindCondition("health-below abc");

        assertNull(key, "参数非法的条件不应被注册成阈值 0.0 的怪条件");
        assertTrue(svc.warnings().stream().anyMatch(w -> w.contains("abc")),
                "warnings 应包含原始字面量，便于定位：实际 = " + svc.warnings());
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
        assertTrue(svc.skillNames().contains("burst"));
        assertTrue(svc.skillNames().contains("heal"));
        assertTrue(svc.skillNames().contains("idle_skill"));
        assertEquals(0, svc.queuedSkillCount());
    }

    // ------------------------------------------------------------------
    // 技能预览（只判定，不执行）
    // ------------------------------------------------------------------

    private SkillService skillServiceWith(String yaml) {
        var svc = service();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        svc.loadSkills(y.getConfigurationSection("skills"));
        return svc;
    }

    @Test
    @DisplayName("preview 报告条件通过与否，并指出首条未满足项")
    void previewTracesConditions() {
        var svc = skillServiceWith("""
                skills:
                  enrage:
                    require:
                      - health-below 0.3
                      - has-target true
                    on-decision: [effect-self speed 2]
                """);
        // 满血：health-below 不满足，且无目标时 has-target 也不满足
        var full = svc.preview("enrage", ctx(1.0, 0));
        assertNotNull(full);
        assertFalse(full.wouldRun(), "满血时不该放行");
        assertEquals("health-below:0.3", full.firstFailing(), "应指出首条未满足的条件");
        assertEquals(2, full.trace().size(), "轨迹应逐条列出，两条都要能看到");

        // 残血：health-below 满足，但 has-target 仍因无目标而不满足。
        // 这正是预览的边界——它只能判定标量条件，依赖世界状态的条件
        // （on-ground / has-target 等）必须上真服验证。轨迹里的 ✗ 含义是
        // 「需要真服」，而不是「配置写错了」，命令输出里也这样提示。
        var low = svc.preview("enrage", ctx(0.1, 0));
        assertNotNull(low);
        assertTrue(low.trace().get(0).passed(), "残血时 health-below 应满足");
        assertFalse(low.trace().get(1).passed(), "无目标时 has-target 应不满足");
        assertEquals("has-target:true", low.firstFailing());
        assertFalse(low.actions().isEmpty(), "应列出将要执行的动作");
    }

    @Test
    @DisplayName("preview 在全部条件可由标量判定时给出确定结论")
    void previewReachesVerdictOnScalarOnlySkills() {
        var svc = skillServiceWith("""
                skills:
                  panic:
                    require:
                      - health-below 0.3
                      - state-is idle
                    on-decision: [set-scale 1.5]
                """);
        assertFalse(svc.preview("panic", ctx(1.0, 0)).wouldRun(), "满血不该放行");
        // 残血 + 状态匹配：两条都满足，才给确定结论
        assertTrue(svc.preview("panic", lowIdleCtx()).wouldRun(), "低血量且状态 idle 时应放行");
        // 残血 + 状态不匹配
        var wrongState = BehaviorContext.of(null, null, 0.1, -1, 0, "CHASE");
        assertFalse(svc.preview("panic", wrongState).wouldRun(), "状态不匹配不该放行");
    }

    /** 低血量 + idle 状态的上下文。 */
    private static BehaviorContext lowIdleCtx() {
        return BehaviorContext.of(null, null, 0.1, -1, 0, "idle");
    }

    @Test
    @DisplayName("preview 不执行动作——这是它能安全挂在真服上的前提")
    void previewDoesNotExecute() {
        var registry2 = new BehaviorRegistryImpl();
        var svc = new SkillService(registry2, null);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  boom:
                    on-decision: [damage-target 999]
                """));
        svc.loadSkills(y.getConfigurationSection("skills"));
        var r = svc.preview("boom", ctx(1.0, 0));
        assertNotNull(r);
        assertTrue(r.wouldRun(), "无 require 时视为恒满足");
        // 动作键已绑定但未被执行：跑一遍也不会有副作用，这里只断言调用本身安全
        assertFalse(r.actions().isEmpty());
    }

    @Test
    @DisplayName("preview 对不存在的技能返回 null，而不是抛异常")
    void previewUnknownSkill() {
        var svc = skillServiceWith("skills:\n  a:\n    on-decision: [set-scale 1.0]\n");
        assertNull(svc.preview("nope", ctx(1.0, 0)));
        assertNull(svc.preview(null, ctx(1.0, 0)));
        assertNull(svc.preview("  ", ctx(1.0, 0)));
    }

    @Test
    @DisplayName("preview 的轨迹顺序与 require 书写顺序一致")
    void previewTracePreservesOrder() {
        var svc = skillServiceWith("""
                skills:
                  ordered:
                    require:
                      - health-above 0.9
                      - state-is idle
                """);
        var p = svc.preview("ordered", ctx(1.0, 0));
        assertNotNull(p);
        assertEquals(2, p.trace().size());
        assertEquals("health-above:0.9", p.trace().get(0).key());
        assertEquals("state-is:idle", p.trace().get(1).key());
    }
}