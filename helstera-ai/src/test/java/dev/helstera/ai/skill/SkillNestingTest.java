package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}