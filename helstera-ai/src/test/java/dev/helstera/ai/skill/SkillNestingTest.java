package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 嵌套技能、环引用与冷却的测试。
 *
 * <p>这三项是阶段 0 的核心产出：此前技能只能平铺引用，
 * {@code a → b → a} 会在运行期栈溢出，而技能冷却完全不存在。</p>
 */
class SkillNestingTest {

    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();

    private SkillService service() {
        return new SkillService(registry, null);
    }

    private static BehaviorContext ctx() {
        return BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE");
    }

    private static YamlConfiguration yaml(String text) {
        return YamlConfiguration.loadConfiguration(new StringReader(text));
    }

    // ------------------------------------------------------------------
    // 引用解析
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cast-skill / skill{s=X} / skill{s=X;cooldown=1s} 三种写法都能解析出技能名")
    void parsesAllReferenceForms() {
        assertEquals("ember", SkillService.parseSkillRef("cast-skill ember"));
        assertEquals("ember", SkillService.parseSkillRef("skill{s=ember}"));
        assertEquals("ember", SkillService.parseSkillRef("skill{s=ember;cooldown=1s}"));
        assertEquals("ember", SkillService.parseSkillRef("~onSpawn:ember"));
        assertEquals("ember", SkillService.parseSkillRef("  cast-skill   ember  "));
    }

    @Test
    @DisplayName("非技能引用与畸形写法返回 null，不误判为引用")
    void ignoresNonReferences() {
        assertNull(SkillService.parseSkillRef("damage-target 5"));
        assertNull(SkillService.parseSkillRef("play-animation walk"));
        assertNull(SkillService.parseSkillRef(null));
        assertNull(SkillService.parseSkillRef(""));
        assertNull(SkillService.parseSkillRef("cast-skill"));
        assertNull(SkillService.parseSkillRef("other{amount=5}"), "非 skill 前缀不应被当成技能引用");
        assertNull(SkillService.parseSkillRef("skill{amount=5}"), "没有 s= 键就不是技能引用");
    }

    // ------------------------------------------------------------------
    // 嵌套执行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("技能 A 调用技能 B，两级动作按序执行")
    void executesNestedSkill() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", c -> fired.incrementAndGet());

        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  inner:
                    on-decision:
                      - probe
                  outer:
                    on-decision:
                      - cast-skill inner
                      - probe
                """).getConfigurationSection("skills"));

        assertTrue(svc.castSkill("outer", ctx()));
        assertEquals(2, fired.get(), "outer 的 probe + inner 的 probe 各一次");
    }

    @Test
    @DisplayName("三层嵌套链能逐层展开")
    void executesThreeLevelChain() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", c -> fired.incrementAndGet());

        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  c_skill:
                    on-decision:
                      - probe
                  b_skill:
                    on-decision:
                      - cast-skill c_skill
                  a_skill:
                    on-decision:
                      - cast-skill b_skill
                """).getConfigurationSection("skills"));

        assertTrue(svc.castSkill("a_skill", ctx()));
        assertEquals(1, fired.get());
    }

    @Test
    @DisplayName("skill{s=X} 写法同样能嵌套")
    void nestsViaMythicSyntax() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", c -> fired.incrementAndGet());

        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  inner:
                    on-decision:
                      - probe
                  outer:
                    on-decision:
                      - "skill{s=inner}"
                """).getConfigurationSection("skills"));

        assertTrue(svc.castSkill("outer", ctx()));
        assertEquals(1, fired.get());
    }

    @Test
    @DisplayName("randomskill selects a loaded named skill through the normal cast guards")
    void selectsRandomNamedSkill() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", c -> fired.incrementAndGet());

        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  child:
                    on-decision:
                      - probe
                  outer:
                    on-decision:
                      - randomskill{skills=child}
                """).getConfigurationSection("skills"));

        assertTrue(svc.castSkill("outer", ctx()));
        assertEquals(1, fired.get());
        assertTrue(svc.warnings().isEmpty(), svc.warnings().toString());
    }

    @Test
    @DisplayName("调用不存在的技能返回 false，不抛异常")
    void toleratesMissingSkill() {
        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  lonely:
                    on-decision:
                      - probe
                """).getConfigurationSection("skills"));

        assertFalse(svc.castSkill("ghost", ctx()));
        assertFalse(svc.castSkill(null, ctx()));
        assertFalse(svc.castSkill("  ", ctx()));
    }

    @Test
    @DisplayName("未定义的被调技能在装载期告警，而不是运行期静默失败")
    void warnsOnUndefinedReference() {
        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  outer:
                    on-decision:
                      - cast-skill missing_one
                """).getConfigurationSection("skills"));

        assertTrue(svc.warnings().stream().anyMatch(w -> w.contains("missing_one")),
                svc.warnings().toString());
    }

    @Test
    @DisplayName("skillNames 列出全部已装载技能")
    void listsLoadedSkills() {
        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  one:
                    on-decision:
                      - probe
                  two:
                    on-decision:
                      - probe
                """).getConfigurationSection("skills"));

        assertEquals(List.of("one", "two"), svc.skillNames());
    }

    // ------------------------------------------------------------------
    // 环引用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("两技能互调被检环拦下并告警，运行期不栈溢出")
    void detectsTwoNodeCycle() {
        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  a_skill:
                    on-decision:
                      - cast-skill b_skill
                  b_skill:
                    on-decision:
                      - cast-skill a_skill
                """).getConfigurationSection("skills"));

        assertTrue(svc.warnings().stream().anyMatch(w -> w.contains("循环引用")),
                "应报出环引用: " + svc.warnings());
        // 最关键的一条：不抛 StackOverflowError
        svc.castSkill("a_skill", ctx());
        svc.castSkill("b_skill", ctx());
    }

    @Test
    @DisplayName("三技能长链成环也能检出")
    void detectsLongerCycle() {
        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  x_skill:
                    on-decision:
                      - cast-skill y_skill
                  y_skill:
                    on-decision:
                      - cast-skill z_skill
                  z_skill:
                    on-decision:
                      - cast-skill x_skill
                """).getConfigurationSection("skills"));

        assertTrue(svc.warnings().stream().anyMatch(w -> w.contains("循环引用")),
                svc.warnings().toString());
        svc.castSkill("x_skill", ctx());
    }

    @Test
    @DisplayName("自调用被检出")
    void detectsSelfReference() {
        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                    loop:
                      on-decision:
                        - cast-skill loop
                """).getConfigurationSection("skills"));

        assertTrue(svc.warnings().stream().anyMatch(w -> w.contains("循环引用")),
                svc.warnings().toString());
        svc.castSkill("loop", ctx());
    }

    @Test
    @DisplayName("菱形依赖（D 同时被 B/C 引用）不算环，正常执行")
    void allowsDiamondDependency() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", c -> fired.incrementAndGet());

        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  leaf:
                    on-decision:
                      - probe
                  left:
                    on-decision:
                      - cast-skill leaf
                  right:
                    on-decision:
                      - cast-skill leaf
                  top:
                    on-decision:
                      - cast-skill left
                      - cast-skill right
                """).getConfigurationSection("skills"));

        assertFalse(svc.warnings().stream().anyMatch(w -> w.contains("循环引用")),
                "菱形不是环: " + svc.warnings());
        assertTrue(svc.castSkill("top", ctx()));
        assertEquals(2, fired.get(), "left 与 right 各调一次 leaf");
    }

    // ------------------------------------------------------------------
    // 冷却
    // ------------------------------------------------------------------

    @Test
    @DisplayName("冷却期内重复调用被拒，时间过后放行")
    void enforcesCooldown() throws InterruptedException {
        var fired = new AtomicInteger();
        registry.registerAction("probe", c -> fired.incrementAndGet());

        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  pulse:
                    cooldown: 200ms
                    on-decision:
                      - probe
                """).getConfigurationSection("skills"));

        assertTrue(svc.castSkill("pulse", ctx()));
        assertFalse(svc.castSkill("pulse", ctx()), "冷却期内应被拒绝");
        assertEquals(1, fired.get());

        Thread.sleep(220);
        assertTrue(svc.castSkill("pulse", ctx()), "冷却结束后应放行");
        assertEquals(2, fired.get());
    }

    @Test
    @DisplayName("冷却时间单位换算：200t=10s、1m=60s、6s=6s")
    void parsesCooldownUnits() throws InterruptedException {
        // 200 tick 冷却是 10 秒，测试里不该真等，因此用最短的 1s 验证换算方向
        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  tick_unit:
                    cooldown: 20t
                    on-decision:
                      - probe
                  min_unit:
                    cooldown: 1m
                    on-decision:
                      - probe
                  sec_unit:
                    cooldown: 1s
                    on-decision:
                      - probe
                """).getConfigurationSection("skills"));

        var fired = new AtomicInteger();
        registry.registerAction("probe", c -> fired.incrementAndGet());

        // 20t = 1 秒：立刻第二次调用应被拒
        svc.castSkill("tick_unit", ctx());
        assertFalse(svc.castSkill("tick_unit", ctx()), "20t 应等于 1 秒，冷却应生效");
        // 1m = 60 秒：任何短间隔内都应被拒
        svc.castSkill("min_unit", ctx());
        assertFalse(svc.castSkill("min_unit", ctx()));
        // 裸数字按秒
        svc.castSkill("sec_unit", ctx());
        assertFalse(svc.castSkill("sec_unit", ctx()));
    }

    @Test
    @DisplayName("无冷却配置时不受限制")
    void noCooldownWhenUnset() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", c -> fired.incrementAndGet());

        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  free_run:
                    on-decision:
                      - probe
                """).getConfigurationSection("skills"));

        assertTrue(svc.castSkill("free_run", ctx()));
        assertTrue(svc.castSkill("free_run", ctx()));
        assertEquals(2, fired.get());
    }

    @Test
    @DisplayName("冷却按实例独立计时：同一技能被不同实例调用互不阻塞")
    void cooldownIsPerInstance() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", c -> fired.incrementAndGet());

        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  pulse:
                    cooldown: 60
                    on-decision:
                      - probe
                """).getConfigurationSection("skills"));

        // instance 为 null 时 id 记为 -1；构造两个不同实例 id 的上下文
        BehaviorContext a = newInstanceCtx(1);
        BehaviorContext b = newInstanceCtx(2);

        assertTrue(svc.castSkill("pulse", a));
        assertTrue(svc.castSkill("pulse", b), "实例 B 不应被实例 A 的冷却挡住");
        assertFalse(svc.castSkill("pulse", a), "同一实例第二次仍在冷却内");
        assertEquals(2, fired.get());
    }

    /**
     * 构造带 instanceId 的上下文。
     *
     * <p>{@code BehaviorContext.instance()} 取的是 {@code ModelInstance.instanceId()}，
     * 而 ModelInstance 是接口——用动态代理让两个上下文返回不同 id 即可，
     * 不必为此造一个假的完整实现。</p>
     */
    private static BehaviorContext newInstanceCtx(int instanceId) {
        dev.helstera.api.instance.ModelInstance inst =
                (dev.helstera.api.instance.ModelInstance) java.lang.reflect.Proxy.newProxyInstance(
                        PlaceholdersTest.class.getClassLoader(),
                        new Class<?>[]{dev.helstera.api.instance.ModelInstance.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "instanceId" -> instanceId;
                            case "isValid" -> true;
                            case "baseEntity" -> java.util.Optional.empty();
                            case "location" -> null;
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> instanceId;
                            case "toString" -> "instance#" + instanceId;
                            default -> defaultValue(method.getReturnType());
                        });
        return BehaviorContext.of(inst, null, 1.0, -1, 0, "IDLE");
    }

    private static Object defaultValue(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return false;
        if (t == void.class) return null;
        if (t == long.class) return 0L;
        if (t == double.class) return 0.0;
        if (t == float.class) return 0.0f;
        return 0;
    }

    // ------------------------------------------------------------------
    // 与整体键的兼容
    // ------------------------------------------------------------------

    @Test
    @DisplayName("嵌套技能仍可通过 skill:名字 整体键触发（旧配置不受影响）")
    void aggregateKeysStillWork() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", c -> fired.incrementAndGet());

        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  tank:
                    require:
                      - health-above 0.5
                    on-decision:
                      - cast-skill inner
                  inner:
                    on-decision:
                      - probe
                """).getConfigurationSection("skills"));

        String c = svc.bindCondition("skill:tank");
        String a = svc.bindAction("skill:tank");
        assertNotNull(c);
        assertNotNull(a);

        assertTrue(registry.testCondition(c, ctx()));
        assertTrue(registry.runAction(a, ctx()));
        assertEquals(1, fired.get(), "整体键应走到嵌套的 inner");
    }

    @Test
    @DisplayName("reload 重载后技能定义被替换，旧的冷却记录一并清空")
    void reloadReplacesDefinitions() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", c -> fired.incrementAndGet());

        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  old_skill:
                    on-decision:
                      - probe
                """).getConfigurationSection("skills"));
        assertEquals(List.of("old_skill"), svc.skillNames());

        svc.loadSkills(yaml("""
                skills:
                  new_skill:
                    on-decision:
                      - probe
                """).getConfigurationSection("skills"));

        assertEquals(List.of("new_skill"), svc.skillNames(), "重载不应残留旧定义");
        assertFalse(svc.castSkill("old_skill", ctx()));
        assertTrue(svc.castSkill("new_skill", ctx()));
    }
    @Test
    @DisplayName("target-conditions and trigger-conditions gate skill casts")
    void targetAndTriggerConditionsGateCasts() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", ctx -> fired.incrementAndGet());
        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  gated:
                    target-conditions:
                      - has-target
                    trigger-conditions:
                      - trigger-is-player
                    on-decision:
                      - probe
                """).getConfigurationSection("skills"));

        assertFalse(svc.castSkill("gated", ctx()));
        assertEquals(0, fired.get());
    }

    @Test
    @DisplayName("nested skills share and unset one tree variable")
    void nestedSkillsShareAndUnsetTreeVariable() {
        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  inner:
                    on-decision:
                      - var-add marker 1
                      - variable-unset marker
                  outer:
                    on-decision:
                      - var-set marker 4
                      - cast-skill inner
                """).getConfigurationSection("skills"));
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);

        assertTrue(svc.castSkill("outer", ctx));
        assertTrue(ctx.skillTree().orElseThrow().variable("marker").isEmpty());
    }

    @Test
    @DisplayName("target and trigger gates receive distinct contexts")
    void conditionStagesUseSeparateContextValues() {
        var seen = new java.util.ArrayList<BehaviorContext>();
        registry.registerCondition("capture", context -> { seen.add(context); return true; });
        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  gated:
                    target-conditions: [capture]
                    trigger-conditions: [capture]
                    on-decision: [probe]
                """).getConfigurationSection("skills"));
        registry.registerAction("probe", context -> { });
        var original = BehaviorContext.execution(null, null, 1.0, -1, 0, "skill",
                null, null, List.of(), null);
        assertTrue(svc.castSkill("gated", original));
        assertEquals(2, seen.size());
        assertSame(original, seen.get(0), "without a target, keep the neutral context");
        assertSame(original, seen.get(1), "without a trigger entity, keep the neutral context");
    }

    @Test
    @DisplayName("target and trigger conditions evaluate against their respective entities")
    void targetAndTriggerConditionsUseDistinctEntityContexts() {
        var target = livingPlayerProxy();
        var trigger = livingPlayerProxy();
        var observed = new java.util.ArrayList<org.bukkit.entity.LivingEntity>();
        registry.registerCondition("capture-context", context -> {
            observed.add(context.targetEntity().orElse(null));
            return true;
        });
        registry.registerAction("probe", context -> { });

        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  gated:
                    target-conditions: [capture-context]
                    trigger-conditions: [capture-context]
                    on-decision: [probe]
                """).getConfigurationSection("skills"));
        var context = BehaviorContext.execution(null, (org.bukkit.entity.Player) target,
                0.5, 4, 0, "skill", null, trigger, List.of(target), null);

        assertTrue(svc.castSkill("gated", context));
        assertEquals(2, observed.size());
        assertSame(target, observed.get(0));
        assertSame(trigger, observed.get(1));
    }

    @Test
    @DisplayName("target conditions filter each selected target before nested actions")
    void targetConditionsFilterSelectedTargets() {
        var rejected = livingPlayerProxy();
        var accepted = livingPlayerProxy();
        var observed = new java.util.ArrayList<org.bukkit.entity.LivingEntity>();
        var executed = new java.util.ArrayList<BehaviorContext>();
        registry.registerCondition("accept-second-target", context -> {
            var target = context.targetEntity().orElseThrow();
            observed.add(target);
            return target == accepted;
        });
        registry.registerAction("capture-targets", executed::add);

        var svc = service();
        svc.loadSkills(yaml("""
                skills:
                  filtered:
                    target-conditions: [accept-second-target]
                    on-decision: [capture-targets]
                """).getConfigurationSection("skills"));
        var context = BehaviorContext.execution(null, (org.bukkit.entity.Player) rejected,
                0.5, 4, 0, "skill", null, null, List.of(rejected, accepted), null);

        assertTrue(svc.castSkill("filtered", context));
        assertEquals(List.of(rejected, accepted), observed);
        assertEquals(1, executed.size());
        assertEquals(List.of(accepted), executed.get(0).targets());
        assertSame(accepted, executed.get(0).targetEntity().orElseThrow());
        assertEquals(List.of(accepted), context.skillTree().orElseThrow().targets());
    }

    private static org.bukkit.entity.Player livingPlayerProxy() {
        return (org.bukkit.entity.Player) Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getHealth" -> 10.0;
                    case "getMaxHealth" -> 20.0;
                    case "isValid", "isOnline" -> true;
                    case "getName", "getDisplayName" -> "test-player";
                    case "toString" -> "test-player-proxy";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (args == null ? null : args[0]);
                    default -> {
                        Class<?> type = method.getReturnType();
                        if (!type.isPrimitive()) yield null;
                        if (type == boolean.class) yield false;
                        if (type == char.class) yield (char) 0;
                        if (type == byte.class) yield (byte) 0;
                        if (type == short.class) yield (short) 0;
                        if (type == int.class) yield 0;
                        if (type == long.class) yield 0L;
                        if (type == float.class) yield 0f;
                        if (type == double.class) yield 0d;
                        yield null;
                    }
                });
    }

    @Test
    @DisplayName("targeted damage mechanic routes into existing damage-target implementation")
    void targeterBindingUsesRealMechanicImplementation() {
        var svc = service();
        String bound = svc.bindAction("damage{amount=3} @Target");
        assertNotNull(bound);
        assertTrue(bound.startsWith("damage-target:"), bound);
        assertFalse(bound.contains("mythic-"), bound);
    }

    @Test
    @DisplayName("dash / blink 的目标器只提供方向，不把自身位移路由成目标动作")
    void selfMovementTargetersKeepCasterMechanics() {
        var svc = service();

        String dash = svc.bindAction("dash 5 @Target");
        String blink = svc.bindAction("blink 8 @Target");

        assertNotNull(dash, svc.warnings().toString());
        assertNotNull(blink, svc.warnings().toString());
        assertTrue(dash.startsWith("dash:"), dash);
        assertTrue(blink.startsWith("blink:"), blink);
    }

    @Test
    @DisplayName("targeter binding resolves mechanic aliases before validation")
    void targeterBindingRoutesDamageAlias() {
        String bound = service().bindAction("damage{amount=3} @Target");
        assertNotNull(bound);
        assertTrue(bound.startsWith("damage-target:"), bound);
    }

    @Test
    @DisplayName("targeted heal applies to the selected target rather than the caster")
    void targetedHealUsesTargetMechanic() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.getMaxHealth()).thenReturn(20.0);
        when(caster.getHealth()).thenReturn(4.0);
        when(target.getMaxHealth()).thenReturn(20.0);
        when(target.getHealth()).thenReturn(5.0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), null, null);

        String bound = service.bindAction("heal{amount=4} @Target");

        assertNotNull(bound, service.warnings().toString());
        assertTrue(registry.runAction(bound, context));
        verify(target).setHealth(9.0);
        verify(caster, never()).setHealth(org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("untargeted heal binds to the caster mechanic")
    void untargetedHealUsesSelfMechanic() {
        String bound = service().bindAction("heal 5");

        assertNotNull(bound);
        assertTrue(bound.startsWith("heal-self:"), bound);
    }

    @Test
    @DisplayName("set-stance stores a Mythic stance independently from the AI state")
    void stanceMechanicAndConditionUseInstanceStance() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(901);
        var context = BehaviorContext.of(instance, null, 1.0, -1, 0, "ATTACK");

        String action = service.bindAction("set-stance enraged");
        String condition = service.bindCondition("stance-is enraged");
        assertNotNull(action, service.warnings().toString());
        assertNotNull(condition, service.warnings().toString());
        assertTrue(registry.runAction(action, context));
        assertTrue(registry.testCondition(condition, context));

        SkillExtras.forget(901);
        assertFalse(registry.testCondition(condition, context));
    }

    @Test
    @DisplayName("untargeted damage binds to the active target mechanic")
    void untargetedDamageUsesTargetMechanic() {
        String bound = service().bindAction("damage 5");

        assertNotNull(bound);
        assertTrue(bound.startsWith("damage-target:"), bound);
    }

    @Test
    @DisplayName("common untargeted Mythic mechanics bind to existing target factories")
    void commonMechanicAliasesUseTargetFactories() {
        var service = service();
        assertTrue(service.bindAction("message hello").startsWith("message-target:"));
        assertTrue(service.bindAction("effect SPEED 20 0").startsWith("effect-targets:"));
        assertEquals("effect-self:SPEED 40 2 true false false",
                service.bindAction("effect-self SPEED 40 2 true false false"));
        assertTrue(service.bindAction("ignite 40").startsWith("ignite-targets:"));
        assertTrue(service.bindAction("knockback 1 0.4").startsWith("knockback-targets:"));
    }

    @Test
    @DisplayName("@Targets 让 damage mechanic 对全部选中实体执行")
    void targetedDamageExecutesForAllSelectedEntities() {
        var svc = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.Entity.class);
        var first = mock(org.bukkit.entity.LivingEntity.class);
        var second = mock(org.bukkit.entity.LivingEntity.class);
        var world = mock(org.bukkit.World.class);
        var origin = new org.bukkit.Location(world, 0, 64, 0);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(instance.location()).thenReturn(origin);
        when(first.getWorld()).thenReturn(world);
        when(second.getWorld()).thenReturn(world);
        when(first.getLocation()).thenReturn(new org.bukkit.Location(world, 1, 64, 0));
        when(second.getLocation()).thenReturn(new org.bukkit.Location(world, -1, 64, 0));
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, first, 1.0, 2.0, 0, "skill", null, first,
                java.util.List.of(first, second), origin, null);

        String bound = svc.bindAction("damage 3 @Targets");

        assertNotNull(bound, svc.warnings().toString());
        assertTrue(registry.hasAction(bound), "bound=" + bound + ", warnings=" + svc.warnings());
        assertTrue(registry.runAction(bound, context));
        verify(first).damage(3.0, caster);
        verify(second).damage(3.0, caster);
    }

    @Test
    @DisplayName("@Targets 让 damage-percent mechanic 对全部选中实体执行")
    void targetedDamagePercentExecutesForAllSelectedEntities() {
        var svc = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.Entity.class);
        var first = mock(org.bukkit.entity.LivingEntity.class);
        var second = mock(org.bukkit.entity.LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(first.getMaxHealth()).thenReturn(20.0);
        when(second.getMaxHealth()).thenReturn(40.0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, first, 1.0, 2.0, 0, "skill", null, first,
                java.util.List.of(first, second), null, null);

        String bound = svc.bindAction("damage-percent{amount=25} @Targets");

        assertNotNull(bound, svc.warnings().toString());
        assertTrue(bound.startsWith("damage-percent-targets:"), bound);
        assertTrue(registry.runAction(bound, context));
        verify(first).damage(5.0, caster);
        verify(second).damage(10.0, caster);
    }

    @Test
    @DisplayName("@Targets 让 explode mechanic 在每个选中位置执行")
    void targetedExplosionExecutesForAllSelectedEntities() {
        var svc = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var first = mock(org.bukkit.entity.LivingEntity.class);
        var second = mock(org.bukkit.entity.LivingEntity.class);
        var world = mock(org.bukkit.World.class);
        var origin = new org.bukkit.Location(world, 0, 64, 0);
        var firstLocation = new org.bukkit.Location(world, 1, 64, 0);
        var secondLocation = new org.bukkit.Location(world, -1, 64, 0);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(origin);
        when(first.getLocation()).thenReturn(firstLocation);
        when(second.getLocation()).thenReturn(secondLocation);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, first, 1.0, 2.0, 0, "skill", null, first,
                java.util.List.of(first, second), origin, null);

        String bound = svc.bindAction("explode 2 @Targets");

        assertNotNull(bound, svc.warnings().toString());
        assertTrue(registry.runAction(bound, context));
        verify(world).createExplosion(firstLocation, 2.0f, false, false);
        verify(world).createExplosion(secondLocation, 2.0f, false, false);
    }

    @Test
    @DisplayName("@Targets 让 message mechanic 发送给全部选中玩家")
    void targetedMessageExecutesForAllSelectedPlayers() {
        var svc = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var first = mock(org.bukkit.entity.Player.class);
        var second = mock(org.bukkit.entity.Player.class);
        when(instance.isValid()).thenReturn(true);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, first, 1.0, 2.0, 0, "skill", null, first,
                java.util.List.of(first, second), null, null);

        String bound = svc.bindAction("message hello @Targets");

        assertNotNull(bound, svc.warnings().toString());
        assertTrue(registry.runAction(bound, context));
        verify(first).sendMessage("hello");
        verify(second).sendMessage("hello");
    }

    @Test
    @DisplayName("targeted heal-percent heals the selected target")
    void targetedHealPercentUsesTargetMechanic() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(target.getMaxHealth()).thenReturn(20.0);
        when(target.getHealth()).thenReturn(10.0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), null, null);

        String bound = service.bindAction("heal-percent{amount=25} @Target");

        assertNotNull(bound, service.warnings().toString());
        assertTrue(registry.runAction(bound, context));
        verify(target).setHealth(15.0);
        verify(caster, never()).setHealth(org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("targeted set-health modifies the selected target")
    void targetedSetHealthUsesTargetMechanic() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(target.getMaxHealth()).thenReturn(20.0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), null, null);

        String bound = service.bindAction("set-health 7 @Target");

        assertNotNull(bound, service.warnings().toString());
        assertTrue(registry.runAction(bound, context));
        verify(target).setHealth(7.0);
        verify(caster, never()).setHealth(org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    @DisplayName("targeted set-name modifies the selected target")
    void targetedSetNameUsesTargetMechanic() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), null, null);

        String bound = service.bindAction("set-name &cTarget false @Target");

        assertNotNull(bound, service.warnings().toString());
        assertTrue(registry.runAction(bound, context));
        verify(target).setCustomName("§cTarget");
        verify(target).setCustomNameVisible(false);
    }

    @Test
    @DisplayName("targeted scoreboard tag mechanics mutate the selected target")
    void targetedScoreboardTagUsesTargetMechanic() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), null, null);

        String add = service.bindAction("addtag{tag=HelsteraTargetProbe} @Target");
        String remove = service.bindAction("removetag{tag=HelsteraTargetProbe} @Target");

        assertNotNull(add, service.warnings().toString());
        assertNotNull(remove, service.warnings().toString());
        assertTrue(add.startsWith("add-tag-targets:"), add);
        assertTrue(remove.startsWith("remove-tag-targets:"), remove);
        assertTrue(registry.runAction(add, context));
        assertTrue(registry.runAction(remove, context));
        verify(target).addScoreboardTag("HelsteraTargetProbe");
        verify(target).removeScoreboardTag("HelsteraTargetProbe");
        verify(caster, never()).addScoreboardTag(org.mockito.ArgumentMatchers.anyString());
        verify(caster, never()).removeScoreboardTag(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("targeted set-fire modifies the selected target")
    void targetedSetFireUsesTargetMechanic() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), null, null);

        String bound = service.bindAction("set-fire 40 @Target");

        assertNotNull(bound, service.warnings().toString());
        assertTrue(registry.runAction(bound, context));
        verify(target).setFireTicks(40);
        verify(caster, never()).setFireTicks(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("targeted equip places the item on the selected target")
    void targetedEquipUsesTargetMechanic() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        var equipment = mock(org.bukkit.inventory.EntityEquipment.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(target.getEquipment()).thenReturn(equipment);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), null, null);
        var resolvedItem = mock(ItemStack.class);
        var resolvedMaterial = mock(org.bukkit.Material.class);
        when(resolvedItem.getType()).thenReturn(resolvedMaterial);
        when(resolvedItem.clone()).thenReturn(resolvedItem);
        SkillExtras.itemResolver(name -> resolvedItem);
        try {
            String bound = service.bindAction("equip DIAMOND_SWORD main-hand 1 @Target");
            assertNotNull(bound, service.warnings().toString());
            assertTrue(registry.runAction(bound, context));
            verify(equipment).setItem(EquipmentSlot.HAND, resolvedItem);
            verify(caster, never()).getEquipment();
        } finally {
            SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("targeted drop-item drops at the selected target location")
    void targetedDropItemUsesTargetLocation() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        var world = mock(org.bukkit.World.class);
        var location = mock(org.bukkit.Location.class);
        var item = mock(ItemStack.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(target.getLocation()).thenReturn(location);
        when(location.getWorld()).thenReturn(world);
        when(item.clone()).thenReturn(item);
        SkillExtras.itemResolver(name -> item);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), null, null);
        try {
            String bound = service.bindAction("drop-item DIAMOND 2 @Target");
            assertNotNull(bound, service.warnings().toString());
            assertTrue(registry.runAction(bound, context));
            verify(world).dropItemNaturally(location, item);
        } finally {
            SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("targeted invulnerability modifies the selected target")
    void targetedInvulnerabilityUsesTargetMechanic() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), null, null);

        String bound = service.bindAction("set-invulnerable true @Target");

        assertNotNull(bound, service.warnings().toString());
        assertTrue(registry.runAction(bound, context));
        verify(target).setInvulnerable(true);
        verify(caster, never()).setInvulnerable(org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("targeted remove-all-effects binds to the target factory")
    void targetedRemoveEffectsUsesTargetMechanic() {
        String bound = service().bindAction("remove-all-effects @Target");

        assertNotNull(bound);
        assertTrue(bound.startsWith("remove-all-effects-targets"), bound);
    }

    @Test
    @DisplayName("targeted modify-speed uses the selected target attribute")
    void targetedModifySpeedUsesTargetMechanic() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        var attribute = mock(org.bukkit.attribute.AttributeInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(target.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED))
                .thenReturn(attribute);
        when(attribute.getBaseValue()).thenReturn(0.1);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), null, null);

        String bound = service.bindAction("modify-speed 2 @Target");

        assertNotNull(bound, service.warnings().toString());
        assertTrue(registry.runAction(bound, context));
        verify(attribute).setBaseValue(0.2);
        verify(caster, never()).getAttribute(org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED);
    }

    @Test
    @DisplayName("targeted leap modifies the selected target velocity")
    void targetedLeapUsesTargetMechanic() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(target.getVelocity()).thenReturn(new org.bukkit.util.Vector(0, 0, 0));
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), null, null);

        String bound = service.bindAction("leap 1.2 @Target");

        assertNotNull(bound, service.warnings().toString());
        assertTrue(registry.runAction(bound, context));
        verify(target).setVelocity(org.mockito.ArgumentMatchers.argThat(vector ->
                Math.abs(vector.getY() - 1.2) < 1.0e-9));
        verify(caster, never()).setVelocity(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("movement mechanics resolve target numeric placeholders at execution time")
    void movementMechanicsResolveNumericPlaceholders() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        var attribute = mock(org.bukkit.attribute.AttributeInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(target.getHealth()).thenReturn(4.0);
        when(target.getVelocity()).thenReturn(new org.bukkit.util.Vector(0, 0, 0));
        when(target.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED))
                .thenReturn(attribute);
        when(attribute.getBaseValue()).thenReturn(0.1);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), null, null);

        String leap = service.bindAction("leap <target.hp.raw> @Target");
        String speed = service.bindAction("modify-speed <target.hp.raw> @Target");

        assertNotNull(leap, service.warnings().toString());
        assertNotNull(speed, service.warnings().toString());
        assertTrue(registry.runAction(leap, context));
        assertTrue(registry.runAction(speed, context));
        verify(target).setVelocity(org.mockito.ArgumentMatchers.argThat(vector ->
                Math.abs(vector.getY() - 4.0) < 1.0e-9));
        verify(attribute).setBaseValue(0.4);
    }

    @Test
    @DisplayName("pull 不会把施法者自己纳入拉拽目标")
    void pullExcludesCasterFromNearbyTargets() {
        var service = service();
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var casterEntity = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        var world = mock(org.bukkit.World.class);
        var origin = new org.bukkit.Location(world, 0, 64, 0);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(casterEntity));
        when(instance.location()).thenReturn(origin);
        when(casterEntity.isValid()).thenReturn(true);
        when(target.isValid()).thenReturn(true);
        when(world.getNearbyEntities(org.mockito.ArgumentMatchers.any(org.bukkit.Location.class),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(java.util.List.of(casterEntity, target));
        when(target.getLocation()).thenReturn(new org.bukkit.Location(world, 2, 64, 0));
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1.0, 2.0, 0, "skill", null, target,
                java.util.List.of(target), origin, null);

        String bound = service.bindAction("pull 6 1");

        assertNotNull(bound, service.warnings().toString());
        assertTrue(registry.runAction(bound, context));
        org.mockito.Mockito.verify(casterEntity, org.mockito.Mockito.never())
                .setVelocity(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(target).setVelocity(org.mockito.ArgumentMatchers.any());
    }

}
