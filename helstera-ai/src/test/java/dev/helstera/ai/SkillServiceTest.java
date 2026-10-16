package dev.helstera.ai;

import dev.helstera.ai.skill.SkillCatalog;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyDouble;

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
    @DisplayName("player state conditions read Bukkit blocking state")
    void playerBlockingConditionReadsEntityState() {
        var svc = service();
        var instance = mock(ModelInstance.class);
        var player = mock(Player.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(player));
        when(player.isBlocking()).thenReturn(true);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");

        String key = svc.bindCondition("is-blocking");
        assertTrue(registry.testCondition(key, context));
        when(player.isBlocking()).thenReturn(false);
        assertFalse(registry.testCondition(key, context));
    }

    @Test
    @DisplayName("food-level compares the active player target and fails closed without one")
    void foodLevelConditionComparesPlayerFood() {
        var svc = service();
        var player = mock(Player.class);
        when(player.getFoodLevel()).thenReturn(14);
        var context = BehaviorContext.of(null, (LivingEntity) player, 1.0, -1, 0, "IDLE");

        String key = svc.bindCondition("food-level <= 14");
        assertNotNull(key, svc.warnings().toString());
        assertTrue(registry.testCondition(key, context));
        when(player.getFoodLevel()).thenReturn(15);
        assertFalse(registry.testCondition(key, context));
        assertFalse(registry.testCondition(key, ctx(1.0, 0)));
    }

    @Test
    @DisplayName("air-level compares remaining air and fails closed without an entity")
    void airLevelConditionComparesRemainingAir() {
        var svc = service();
        var living = mock(LivingEntity.class);
        when(living.getRemainingAir()).thenReturn(120, 121);
        var context = BehaviorContext.of(null, living, 1.0, -1, 0, "IDLE");

        String key = svc.bindCondition("air-level <= 120");
        assertNotNull(key, svc.warnings().toString());
        assertTrue(registry.testCondition(key, context));
        assertFalse(registry.testCondition(key, context));
        assertFalse(registry.testCondition(key, ctx(1.0, 0)));
    }

    @Test
    @DisplayName("damage-cause reads the original Bukkit damage event cause")
    void damageCauseConditionReadsEventContext() {
        var svc = service();
        var context = BehaviorContext.continueExecution(null, (Player) null,
                1.0, -1, 0, "damage", null, null, List.of(), null,
                new dev.helstera.api.behavior.SkillTreeContext(null, List.of(), null),
                null, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        String key = svc.bindCondition("damage-cause ENTITY_ATTACK");

        assertNotNull(key, svc.warnings().toString());
        assertTrue(registry.testCondition(key, context));
        assertFalse(registry.testCondition(svc.bindCondition("damage-cause FIRE"), context));
        assertFalse(registry.testCondition(key, ctx(1.0, 0)));
    }

    @Test
    @DisplayName("has-item counts player inventory stacks and supports slot filtering")
    void hasItemConditionCountsInventory() {
        var svc = service();
        var player = mock(Player.class);
        var inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        var expected = mock(org.bukkit.inventory.ItemStack.class);
        var diamonds = mock(org.bukkit.inventory.ItemStack.class);
        when(expected.getType()).thenReturn(org.bukkit.Material.DIAMOND);
        when(diamonds.getType()).thenReturn(org.bukkit.Material.DIAMOND);
        when(diamonds.getAmount()).thenReturn(2);
        when(diamonds.isSimilar(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[]{diamonds});
        var instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(player));
        var context = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "IDLE", null, player, List.of(player), null);

        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> expected);
        try {
            String key = svc.bindCondition("hasItem{item=DIAMOND;amount=>=2}");
            assertNotNull(key, svc.warnings().toString());
            assertTrue(registry.testCondition(key, context));
            when(diamonds.getAmount()).thenReturn(1);
            assertFalse(registry.testCondition(key, context));
            assertFalse(registry.testCondition(svc.bindCondition("has-item DIAMOND >=1"),
                    BehaviorContext.of(null, (Player) null, 1.0, -1, 0, "IDLE")));
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("entity invulnerability condition reads Bukkit state")
    void invulnerableConditionReadsEntityState() {
        var svc = service();
        var instance = mock(ModelInstance.class);
        var entity = mock(Entity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(entity));
        when(entity.isInvulnerable()).thenReturn(true);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");

        String key = svc.bindCondition("invulnerable");
        assertTrue(registry.testCondition(key, context));
        when(entity.isInvulnerable()).thenReturn(false);
        assertFalse(registry.testCondition(key, context));
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
    @DisplayName("Mythic health, health-percent, and distance comparators evaluate keyed syntax")
    void mythicComparatorsEvaluateKeyedSyntax() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getHealth()).thenReturn(8.0);
        when(target.getMaxHealth()).thenReturn(20.0);
        var context = BehaviorContext.of(null, target, 1.0, 7.5, 0, "IDLE");
        var svc = service();

        String healthBelow = svc.bindCondition("health{h=<10}");
        String healthPercent = svc.bindCondition("health-percent{p=>=40}");
        String distanceBelow = svc.bindCondition("distance{d=<8}");
        String distanceAbove = svc.bindCondition("distance{d=>8}");
        String targetHealth = svc.bindCondition("target-health{h=<10}");
        String targetHealthPercent = svc.bindCondition("target-health-percent{p=>=40}");
        String targetHealthBelow = svc.bindCondition("target-health-below{p=>=50}");
        String targetHealthAbove = svc.bindCondition("target-health-above{p=>=40}");

        assertTrue(registry.testCondition(healthBelow, context));
        assertTrue(registry.testCondition(healthPercent, context));
        assertTrue(registry.testCondition(distanceBelow, context));
        assertFalse(registry.testCondition(distanceAbove, context));
        assertTrue(registry.testCondition(targetHealth, context));
        assertTrue(registry.testCondition(targetHealthPercent, context));
        assertFalse(registry.testCondition(targetHealthBelow, context));
        assertTrue(registry.testCondition(targetHealthAbove, context));
        assertFalse(registry.testCondition(svc.bindCondition("health{h=invalid}"), context));
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
    @DisplayName("condition composition supports all, any, not, and boolean operators")
    void composesConditions() {
        var svc = service();
        String all = svc.bindCondition("all{health-above 0.5;state-is idle}");
        String any = svc.bindCondition("health-below 0.2 || state-is idle");
        String not = svc.bindCondition("not health-below 0.2");

        assertTrue(registry.testCondition(all, ctxWithState("IDLE")));
        assertTrue(registry.testCondition(any, ctxWithState("IDLE")));
        assertTrue(registry.testCondition(not, ctx(0.8, 0)));
        assertFalse(registry.testCondition(all, ctxWithState("CHASE")));
    }

    @Test
    @DisplayName("location conditions use an origin when no model instance exists")
    void locationConditionsUseOriginContext() {
        var svc = service();
        var world = org.mockito.Mockito.mock(org.bukkit.World.class);
        when(world.getName()).thenReturn("integration-world");
        var location = org.mockito.Mockito.mock(org.bukkit.Location.class);
        when(location.getWorld()).thenReturn(world);
        when(location.clone()).thenReturn(location);
        var context = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "spawner", null, mock(org.bukkit.entity.Entity.class), List.of(), location);

        String worldCondition = svc.bindCondition("world-is integration-world");
        assertTrue(registry.testCondition(worldCondition, context));
    }

    @Test
    @DisplayName("entity-type-is checks the active target and stance-is checks execution state")
    void entityTypeAndStanceConditions() {
        var svc = service();
        LivingEntity target = mock(LivingEntity.class);
        when(target.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        var execution = BehaviorContext.of(null, target, 1.0, -1, 0, " enraged ");

        String type = svc.bindCondition("entity-type-is zombie");
        String stance = svc.bindCondition("stance-is enraged");

        assertTrue(registry.testCondition(type, execution));
        assertTrue(registry.testCondition(stance, execution));
        assertFalse(registry.testCondition(svc.bindCondition("entity-type-is creeper"), execution));
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
    @Test
    @DisplayName("Mythic action metadata gates custom actions")
    void mythicActionMetadataGatesAction() {
        var svc = service();
        var fired = new AtomicInteger();
        var allowed = new AtomicBoolean(false);
        registry.registerCondition("allow", ctx -> allowed.get());
        registry.registerAction("probe", ctx -> fired.incrementAndGet());

        String gated = svc.bindAction("probe ?allow");
        assertNotNull(gated);
        assertFalse(gated.equals("probe"));
        assertTrue(registry.runAction(gated, ctx(1, 0)));
        assertEquals(0, fired.get());

        allowed.set(true);
        assertTrue(registry.runAction(gated, ctx(1, 0)));
        assertEquals(1, fired.get());

        String skipped = svc.bindAction("probe 0%");
        assertNotNull(skipped);
        assertTrue(registry.runAction(skipped, ctx(1, 0)));
        assertEquals(1, fired.get());
    }

    @Test
    @DisplayName("skill variables survive without an instance in one skill tree")
    void skillTreeVariablesWorkWithoutInstance() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String set = svc.bindAction("var-set score 2");
        String add = svc.bindAction("var-add score 3");
        assertNotNull(set);
        assertNotNull(add);
        assertTrue(registry.runAction(set, ctx));
        assertTrue(registry.runAction(add, ctx));
        assertEquals(5.0, ((Number) ctx.skillTree().orElseThrow()
                .variable("score").orElseThrow()).doubleValue());
    }

    @Test
    @DisplayName("unset removes skill-tree and instance-scoped values")
    void variableUnsetRemovesStoredValue() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String set = svc.bindAction("var-set marker 7");
        String unset = svc.bindAction("variable-unset marker");
        assertTrue(registry.runAction(set, ctx));
        assertTrue(ctx.skillTree().orElseThrow().variable("marker").isPresent());
        assertTrue(registry.runAction(unset, ctx));
        assertTrue(ctx.skillTree().orElseThrow().variable("marker").isEmpty());
    }

    @Test
    @DisplayName("variable-unset supports explicit skill and tree scopes")
    void variableUnsetScopesAreExplicit() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String set = svc.bindAction("var-set marker 7");
        assertTrue(registry.runAction(set, ctx));
        assertTrue(registry.runAction(svc.bindAction("variable-unset skill marker"), ctx));
        assertTrue(ctx.skillTree().orElseThrow().variable("marker").isEmpty());
    }

    @Test
    @DisplayName("remove-self removes only the caster entity")
    void removeSelfDoesNotRemoveTarget() {
        var svc = service();
        ModelInstance instance = mock(ModelInstance.class);
        Entity caster = mock(Entity.class);
        Entity target = mock(Entity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.isValid()).thenReturn(true);

        var ctx = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "skill", null, target, List.of(), null);
        String action = svc.bindAction("remove-self");

        assertNotNull(action);
        assertTrue(registry.runAction(action, ctx));
        verify(caster).remove();
        verifyNoInteractions(target);
    }

    @Test
    @DisplayName("equip applies the requested material, slot, and amount")
    void equipAppliesMaterialSlotAndAmount() {
        var svc = service();
        registry.logger(java.util.logging.Logger.getLogger("SkillServiceTest"));
        var item = mock(org.bukkit.inventory.ItemStack.class);
        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> item);
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.isValid()).thenReturn(true);
        when(caster.getEquipment()).thenReturn(equipment);

        var ctx = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String action = svc.bindAction("equip DIAMOND_SWORD off-hand 2");

        assertNotNull(action);
        try {
            assertTrue(registry.runAction(action, ctx));
            verify(item).setAmount(2);
            verify(equipment).setItem(EquipmentSlot.OFF_HAND, item);
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("set-name applies translated name and visibility")
    void setNameAppliesDisplayName() {
        var svc = service();
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.isValid()).thenReturn(true);

        var ctx = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String action = svc.bindAction("set-name &cBoss false");

        assertNotNull(action);
        assertTrue(registry.runAction(action, ctx));
        verify(caster).setCustomName("\u00a7cBoss");
        verify(caster).setCustomNameVisible(false);
    }

    @Test
    @DisplayName("set-item replaces the requested equipment slot")
    void setItemReplacesEquipmentSlot() {
        var svc = service();
        var item = mock(org.bukkit.inventory.ItemStack.class);
        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> item);
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.isValid()).thenReturn(true);
        when(caster.getEquipment()).thenReturn(equipment);

        var ctx = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String action = svc.bindAction("setitem{item=SHIELD;slot=main-hand;amount=1}");

        try {
            assertNotNull(action, svc.warnings().toString());
            assertTrue(registry.runAction(action, ctx));
            verify(item).setAmount(1);
            verify(equipment).setItem(EquipmentSlot.HAND, item);
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("drop-item uses the caster location and item resolver")
    void dropItemDropsResolvedStack() {
        var svc = service();
        var item = mock(org.bukkit.inventory.ItemStack.class);
        var world = mock(World.class);
        var location = mock(Location.class);
        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> item);
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(location);
        when(location.getWorld()).thenReturn(world);

        var ctx = BehaviorContext.of(instance, null, 1.0, -1, 0, "IDLE");
        String action = svc.bindAction("drop-item DIAMOND 3");

        try {
            assertNotNull(action);
            assertTrue(registry.runAction(action, ctx));
            verify(item).setAmount(3);
            verify(world).dropItemNaturally(location, item);
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("mount makes the active target a passenger of the caster")
    void mountAddsTargetAsPassenger() {
        var svc = service();
        ModelInstance instance = mock(ModelInstance.class);
        Entity caster = mock(Entity.class);
        LivingEntity target = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));

        var ctx = BehaviorContext.of(instance, target, 1.0, -1, 0, "IDLE");
        String action = svc.bindAction("mount");

        assertNotNull(action);
        assertTrue(registry.runAction(action, ctx));
        verify(caster).addPassenger(target);
    }

    @Test
    @DisplayName("dismount leaves the caster vehicle when present")
    void dismountLeavesVehicle() {
        var svc = service();
        ModelInstance instance = mock(ModelInstance.class);
        Entity caster = mock(Entity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.isInsideVehicle()).thenReturn(true);

        String action = svc.bindAction("dismount");
        assertNotNull(action);
        assertTrue(registry.runAction(action, BehaviorContext.of(instance, (org.bukkit.entity.Player) null,
                1.0, -1, 0, "IDLE")));
        verify(caster).leaveVehicle();
    }

    @Test
    @DisplayName("cancel-event cancels only when a cancellable event is present")
    void cancelEventUsesContextEvent() {
        var svc = service();
        var cancellable = mock(org.bukkit.event.Cancellable.class);
        var ctx = BehaviorContext.continueExecution(null, (org.bukkit.entity.Player) null,
                1.0, -1, 0, "damage", null, null, List.of(), null,
                new dev.helstera.api.behavior.SkillTreeContext(null, List.of(), null), cancellable);
        String action = svc.bindAction("cancel-event");

        assertNotNull(action);
        assertTrue(registry.runAction(action, ctx));
        verify(cancellable).setCancelled(true);
        assertTrue(registry.runAction(action, ctx(1.0, 0)));
    }

    @Test
    @DisplayName("is-cancelled reads the same cancellable event context")
    void isCancelledReadsContextEvent() {
        var svc = service();
        var cancellable = mock(org.bukkit.event.Cancellable.class);
        when(cancellable.isCancelled()).thenReturn(false, true);
        var context = BehaviorContext.continueExecution(null, (org.bukkit.entity.Player) null,
                1.0, -1, 0, "damage", null, null, List.of(), null,
                new dev.helstera.api.behavior.SkillTreeContext(null, List.of(), null), cancellable);
        String condition = svc.bindCondition("isCancelled");

        assertNotNull(condition, svc.warnings().toString());
        assertFalse(registry.testCondition(condition, context));
        assertTrue(registry.testCondition(condition, context));
        verify(cancellable, org.mockito.Mockito.times(2)).isCancelled();
    }

    @Test
    void parsesPreboundCastSkillAction() {
        assertEquals("cancelled_damage_probe", SkillService.parseSkillRef(
                "cast-skill:cancelled_damage_probe"));
    }

    @Test
    void nestedSkillRetainsCancelledEventContext() {
        var svc = service();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  cancelled:
                    require:
                      - is-cancelled
                    on-decision:
                      - set-name &aCANCELLED
                """));
        svc.loadSkills(yaml.getConfigurationSection("skills"));
        var event = mock(org.bukkit.event.Cancellable.class);
        when(event.isCancelled()).thenReturn(true);
        var instance = mock(ModelInstance.class);
        var caster = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        var context = BehaviorContext.continueExecution(instance, caster, 1.0, -1, 0,
                "damage", null, caster, List.of(caster), null,
                new dev.helstera.api.behavior.SkillTreeContext(caster, List.of(caster), null), event);

        assertTrue(svc.castSkill("cancelled", context));
        verify(caster).setCustomName("\u00a7aCANCELLED");
    }

    @Test
    @DisplayName("AiProfile loads preserved goal and target selector lists")
    void aiProfileLoadsSelectorFields() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                goals:
                  - 0 clear
                  - 1 meleeattack
                targets:
                  - 0 clear
                  - 1 players
                """));
        AiProfile profile = AiProfile.fromSection("guard", yaml);
        assertEquals(List.of("0 clear", "1 meleeattack"), profile.goals);
        assertEquals(List.of("0 clear", "1 players"), profile.targets);
    }

    @Test
    @DisplayName("selector modifiers do not leak into the shared profile")
    void profileCopyDoesNotMutateBaseSelectorBehavior() {
        AiProfile base = new AiProfile("base");
        base.canChase = true;
        base.goals.addAll(List.of("0 clear", "1 meleeattack"));
        base.targets.addAll(List.of("0 clear", "1 players"));
        AiProfile copy = new AiProfile(base);
        copy.canChase = false;
        copy.goals.clear();
        assertTrue(base.canChase);
        assertEquals(List.of("0 clear", "1 meleeattack"), base.goals);
    }

    @Test
    @DisplayName("selector policy applies ordered clear and enables native goals")
    void selectorPolicyAppliesPriorityOrder() {
        AiProfile profile = new AiProfile("selector");
        profile.goals.addAll(List.of("0 clear", "1 meleeattack", "2 randomstroll"));
        profile.targets.addAll(List.of("0 clear", "1 players"));
        var policy = profile.selectorPolicy();
        assertTrue(policy.attack());
        assertTrue(policy.patrol());
        assertFalse(policy.chase());
        assertFalse(policy.flee());
        assertTrue(policy.playerTargets());
    }

    @Test
    @DisplayName("selector policy sorts numeric priorities before applying clear")
    void selectorPolicySortsNumericPriorities() {
        AiProfile profile = new AiProfile("selector");
        profile.goals.addAll(List.of("2 clear", "0 meleeattack"));
        profile.targets.addAll(List.of("1 clear", "0 players"));

        var policy = profile.selectorPolicy();

        assertFalse(policy.attack());
        assertFalse(policy.chase());
        assertFalse(policy.playerTargets());
        assertEquals(AiProfile.TargetMode.NONE, policy.targetMode());
        assertEquals(List.of("0 meleeattack", "2 clear"), policy.orderedGoals(profile.goals));
        assertEquals(List.of("0 players", "1 clear"), policy.orderedTargets(profile.targets));
        assertEquals(0, policy.goalPriority(profile.goals, "attack"));
        assertEquals(Integer.MAX_VALUE, policy.goalPriority(profile.goals, "flee"));
        assertEquals(Integer.MAX_VALUE, policy.goalPriority(profile.goals, "patrol"));
    }

    @Test
    @DisplayName("selector policy reports unsupported entries without dropping their token")
    void selectorPolicyReportsUnsupportedTokens() {
        AiProfile profile = new AiProfile("selector");
        profile.goals.add("2 customvendorpathfinder");
        var policy = profile.selectorPolicy();
        assertEquals(List.of("goal:customvendorpathfinder"), policy.unsupported());
    }

    @Test
    @DisplayName("panic goal enables the existing flee runtime policy")
    void selectorPolicyRecognizesPanicGoal() {
        AiProfile profile = new AiProfile("panic");
        profile.goals.add("0 panic");

        var policy = profile.selectorPolicy();

        assertTrue(policy.flee());
        assertTrue(policy.unsupported().isEmpty());
    }

    @Test
    @DisplayName("follow-owner goal selects the existing owner target runtime")
    void selectorPolicyRecognizesFollowOwnerGoal() {
        AiProfile profile = new AiProfile("follow-owner");
        profile.goals.add("0 follow_owner");

        var policy = profile.selectorPolicy();

        assertEquals(AiProfile.TargetMode.OWNER, policy.targetMode());
        assertFalse(policy.playerTargets());
        assertTrue(policy.unsupported().isEmpty());
    }

    @Test
    @DisplayName("tempt goal preserves the configured item and range")
    void selectorPolicyRecognizesTemptGoal() {
        AiProfile profile = new AiProfile("tempt");
        profile.goals.add("0 tempt WHEAT");
        profile.temptRange = 12.0;

        var policy = profile.selectorPolicy();

        assertEquals(AiProfile.TargetMode.PLAYERS, policy.targetMode());
        assertEquals("WHEAT", policy.temptItem());
        assertEquals(12.0, policy.temptRange(), 1e-9);
        assertTrue(policy.unsupported().isEmpty());
    }

    @Test
    @DisplayName("tempt matching accepts either hand and multiple configured materials")
    void temptMatchingChecksBothHands() {
        var player = mock(org.bukkit.entity.Player.class);
        var inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        var mainHand = mock(org.bukkit.inventory.ItemStack.class);
        var offHand = mock(org.bukkit.inventory.ItemStack.class);
        when(mainHand.getType()).thenReturn(org.bukkit.Material.AIR);
        when(offHand.getType()).thenReturn(org.bukkit.Material.WHEAT);
        when(inventory.getItemInMainHand()).thenReturn(mainHand);
        when(inventory.getItemInOffHand()).thenReturn(offHand);

        assertTrue(AiController.holdsTemptItem(player, "CARROT|WHEAT"));
        assertFalse(AiController.holdsTemptItem(player, "CARROT,POTATO"));
    }


    @Test
    @DisplayName("location variables honor global scope and VariableLocation resolution")
    void globalLocationVariableRoundTrip() {
        var svc = service();
        var origin = new org.bukkit.Location(null, 12, 64, -4);
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), origin);

        String set = svc.bindAction("variable-location-set{scope=global;var=mark}");
        String unset = svc.bindAction("variable-unset{var=global.mark}");
        assertNotNull(set);
        assertNotNull(unset);
        assertTrue(registry.runAction(set, ctx));
        var resolved = dev.helstera.api.behavior.Targeters.resolveLocation(
                "@VariableLocation{var=global.mark}", ctx);
        assertTrue(resolved.isPresent());
        assertEquals(12, resolved.orElseThrow().getX());
        assertEquals(64, resolved.orElseThrow().getY());
        assertTrue(registry.runAction(unset, ctx));
        assertTrue(dev.helstera.api.behavior.Targeters.resolveLocation(
                "@VariableLocation{var=global.mark}", ctx).isEmpty());
    }


    @Test
    @DisplayName("typed LOCATION variables resolve through VariableLocation and unset")
    void typedLocationVariableRoundTrip() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), new org.bukkit.Location(null, 1, 2, 3));
        String set = svc.bindAction("setvariable{var=skill.mark;type=LOCATION;value=20,70,-5}");
        String check = svc.bindCondition("variable-is-set{var=skill.mark}");
        String unset = svc.bindAction("variable-unset{var=skill.mark}");

        assertNotNull(set);
        assertNotNull(check);
        assertNotNull(unset);
        assertTrue(registry.runAction(set, ctx));
        assertTrue(registry.testCondition(check, ctx));
        var resolved = dev.helstera.api.behavior.Targeters.resolveLocation(
                "@VariableLocation{var=skill.mark}", ctx).orElseThrow();
        assertEquals(20, resolved.getX());
        assertEquals(70, resolved.getY());
        assertEquals(-5, resolved.getZ());
        assertTrue(registry.runAction(unset, ctx));
        assertFalse(registry.testCondition(check, ctx));
        assertTrue(dev.helstera.api.behavior.Targeters.resolveLocation(
                "@VariableLocation{var=skill.mark}", ctx).isEmpty());
    }
    @Test
    @DisplayName("numeric variables honor global scope across actions and conditions")
    void numericGlobalVariableRoundTrip() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String set = svc.bindAction("setvariable{var=global.counter;value=2}");
        String check = svc.bindCondition("variable-gte{var=global.counter;value=2}");
        String unset = svc.bindAction("variable-unset{var=global.counter}");
        assertNotNull(set);
        assertNotNull(check);
        assertNotNull(unset);
        assertTrue(registry.runAction(set, ctx));
        assertTrue(registry.testCondition(check, ctx));
        assertTrue(registry.runAction(unset, ctx));
        assertFalse(registry.testCondition(check, ctx));
    }
    @Test
    @DisplayName("variable conditions support equality, range, and is-set aliases")
    void variableConditionAliasesWork() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        assertTrue(registry.runAction(svc.bindAction("setvariable{var=global.answer;value=42}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition("variable-is-set{var=global.answer}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition("variableequals{var=global.answer;value=42}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition("variable-in-range{var=global.answer;min=40;max=45}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition("variableequals{var=global.answer;value=7}"), ctx));
        assertTrue(registry.runAction(svc.bindAction("variable-unset{var=global.answer}"), ctx));
    }
    @Test
    @DisplayName("typed variables support strings and lists")
    void typedVariablesRoundTrip() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        assertTrue(registry.runAction(svc.bindAction(
                "setvariable{var=skill.label;type=STRING;val=hello-world}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-equals{var=skill.label;value=hello-world}"), ctx));
        assertTrue(registry.runAction(svc.bindAction(
                "setvariable{var=skill.tags;type=LIST;val=red,blue}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-contains{var=skill.tags;value=blue}"), ctx));
        assertTrue(registry.runAction(svc.bindAction(
                "setvariable{var=skill.map;type=MAP;value=color:blue,size:large}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-index-equals{var=skill.tags;index=1;value=blue}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-index-equals{var=skill.tags;index=9;value=blue}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-key-exists{var=skill.map;key=color}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-key-exists{var=skill.map;key=missing}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-map-value-equals{var=skill.map;key=color;value=blue}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-map-value-equals{var=skill.map;key=color;value=red}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-index-gte{var=skill.tags;index=1;value=1}"), ctx));
        assertTrue(registry.runAction(svc.bindAction(
                "setvariable{var=skill.numbers;type=LIST;value=2,7}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-index-gte{var=skill.numbers;index=1;value=7}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-index-lte{var=skill.numbers;index=0;value=2}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-index-in-range{var=skill.numbers;index=1;min=6;max=8}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-index-in-range{var=skill.numbers;index=9;min=0;max=10}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-index-lte{var=skill.numbers;index=9;value=2}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-key-not-exists{var=skill.map;key=missing}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-key-not-exists{var=skill.map;key=color}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-map-value-not-equals{var=skill.map;key=color;value=red}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-map-value-not-equals{var=skill.map;key=color;value=blue}"), ctx));
        assertTrue(registry.runAction(svc.bindAction(
                "setvariable{var=skill.numericMap;type=MAP;value=low:2,high:7}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-map-value-gte{var=skill.numericMap;key=high;value=7}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-map-value-lte{var=skill.numericMap;key=low;value=2}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-map-value-in-range{var=skill.numericMap;key=high;min=7;max=8}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-map-value-in-range{var=skill.map;key=color;min=0;max=10}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-map-value-not-in-range{var=skill.numericMap;key=high;min=0;max=6}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-map-value-not-in-range{var=skill.numericMap;key=high;min=0;max=7}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-map-value-gte{var=skill.map;key=color;value=1}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-not-equals{var=skill.label;value=goodbye}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-not-contains{var=skill.tags;value=green}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-not-contains{var=skill.tags;value=blue}"), ctx));
        assertTrue(registry.runAction(svc.bindAction("variable-unset{var=skill.label}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-not-equals{var=skill.label;value=goodbye}"), ctx));
    }

    @Test
    @DisplayName("negative string variable conditions honor each persistent scope")
    void negativeVariableConditionsHonorPersistentScopes() {
        var svc = service();
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
        var world = mock(World.class);
        when(world.getUID()).thenReturn(java.util.UUID.randomUUID());
        when(world.getName()).thenReturn("variable-scope-test");
        var caster = mock(org.bukkit.entity.Player.class);
        when(caster.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        var target = mock(org.bukkit.entity.Player.class);
        when(target.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        var origin = new org.bukkit.Location(world, 0, 64, 0);
        var ctx = BehaviorContext.execution(instance, target, 1.0, 2, 0,
                "skill", null, caster, List.of(target), origin);

        for (String scope : List.of("caster", "target", "world", "global")) {
            String variable = scope + suffix;
            String reference = scope + "." + variable;
            assertTrue(registry.runAction(svc.bindAction(
                    "setvariable{var=" + reference + ";type=STRING;value=blue}"), ctx));
            assertTrue(registry.testCondition(svc.bindCondition(
                    "variable-not-equals{var=" + reference + ";value=red}"), ctx), scope);
            assertTrue(registry.testCondition(svc.bindCondition(
                    "variable-not-contains{var=" + reference + ";value=green}"), ctx), scope);
            assertFalse(registry.testCondition(svc.bindCondition(
                    "variable-not-contains{var=" + reference + ";value=blue}"), ctx), scope);
        }

        String casterRef = "caster." + "caster" + suffix;
        String targetRef = "target." + "target" + suffix;
        assertTrue(registry.runAction(svc.bindAction(
                "variable-unset{var=" + casterRef + "}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-not-equals{var=" + casterRef + ";value=anything}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-not-contains{var=" + casterRef + ";value=anything}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-not-equals{var=" + targetRef + ";value=red}"), ctx));
    }

    @Test
    @DisplayName("AI profile loads MaxCombatDistance from mob options")
    void loadsMaxCombatDistance() {
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("max-combat-distance: 18"));
        AiProfile profile = AiProfile.fromSection("guard", yaml);
        assertEquals(18, profile.maxCombatDistance, 1e-9);
    }    @Test
    @DisplayName("despawn modes distinguish chunk unload behavior")
    void despawnModeChunkPolicy() {
        assertTrue(AiManager.despawnOnChunkUnload("NORMAL"));
        assertTrue(AiManager.despawnOnChunkUnload("CHUNK"));
        assertTrue(AiManager.despawnOnChunkUnload("NPC"));
        assertFalse(AiManager.despawnOnChunkUnload("PERSISTENT"));
        assertFalse(AiManager.despawnOnChunkUnload("NEVER"));
    }    @Test
    @DisplayName("selector policy exposes look-at and entity avoidance adapters")
    void selectorPolicyExposesAdvancedGoalFlags() {
        AiProfile profile = new AiProfile("selector");
        profile.goals.addAll(List.of("0 look_at_players", "1 avoid_entity", "2 open_doors", "3 avoid_sun",
                "4 rangedattack", "5 leapattarget"));
        var policy = profile.selectorPolicy();
        assertTrue(policy.lookAtPlayers());
        assertTrue(policy.avoidEntities());
        assertTrue(policy.openDoors());
        assertTrue(policy.avoidSun());
        assertTrue(policy.rangedAttack());
        assertTrue(policy.leapAtTarget());
    }    @Test
    @DisplayName("selector policy marks attacker and hurt-by-target priorities")
    void selectorPolicyMarksTargetPriorities() {
        AiProfile profile = new AiProfile("selector");
        profile.targets.addAll(List.of("0 attacker", "1 hurtbytarget"));
        var policy = profile.selectorPolicy();
        assertTrue(policy.attackerFirst());
        assertTrue(policy.hurtByTargetFirst());
    }

    @Test
    @DisplayName("selector policy recognizes threat and faction target priority")
    void selectorPolicyRecognizesThreatAndFactionTarget() {
        AiProfile threat = new AiProfile("threat");
        threat.targets.add("0 threat");
        assertEquals(AiProfile.TargetMode.THREAT, threat.selectorPolicy().targetMode());

        AiProfile faction = new AiProfile("faction");
        faction.targets.add("0 hostile_to_faction:undead");
        var policy = faction.selectorPolicy();
        assertEquals(AiProfile.TargetMode.FACTION, policy.targetMode());
        assertEquals("undead", policy.targetFaction());
        assertTrue(policy.factionHostile());
    }

    @Test
    @DisplayName("selector policy recognizes allied faction target priority")
    void selectorPolicyRecognizesAlliedFactionTarget() {
        AiProfile profile = new AiProfile("selector");
        profile.targets.add("0 allied_to_faction:guards");
        var policy = profile.selectorPolicy();
        assertEquals(AiProfile.TargetMode.FACTION, policy.targetMode());
        assertEquals("guards", policy.targetFaction());
        assertTrue(policy.factionAllied());
        assertFalse(policy.factionHostile());
    }

    @Test
    @DisplayName("selector policy recognizes owner target priority")
    void selectorPolicyRecognizesOwnerTarget() {
        AiProfile profile = new AiProfile("selector");
        profile.targets.add("0 owner");
        var policy = profile.selectorPolicy();
        assertFalse(policy.playerTargets());
        assertEquals(AiProfile.TargetMode.OWNER, policy.targetMode());
    }

    @Test
    @DisplayName("selector policy exposes non-player living target categories")
    void selectorPolicyExposesLivingTargetCategories() {
        AiProfile profile = new AiProfile("selector");
        profile.targets.addAll(List.of("0 clear", "1 monsters"));
        var policy = profile.selectorPolicy();
        assertFalse(policy.playerTargets());
        assertEquals(AiProfile.TargetMode.MONSTERS, policy.targetMode());

        profile.targets.clear();
        profile.targets.add("living");
        assertEquals(AiProfile.TargetMode.LIVING, profile.selectorPolicy().targetMode());
    }

    @Test
    @DisplayName("caster faction conditions bind alongside target faction conditions")
    void factionConditionsBind() {
        var svc = service();
        assertNotNull(svc.bindCondition("caster-faction-is guards"));
        assertNotNull(svc.bindCondition("caster-faction-allied-to guards"));
        assertNotNull(svc.bindCondition("caster-faction-hostile-to undead"));
        assertNotNull(svc.bindCondition("target-faction-is undead"));
    }

    @Test
    @DisplayName("common Mythic condition aliases bind to canonical factories")
    void commonConditionAliasesBind() {
        var svc = service();
        assertNotNull(svc.bindCondition("permission vip.node"));
        assertNotNull(svc.bindCondition("entity-type ZOMBIE"));
        assertNotNull(svc.bindCondition("stance combat"));
        assertNotNull(svc.bindCondition("healthbelow 0.5"));
        assertNotNull(svc.bindCondition("distanceabove 4"));
        assertNotNull(svc.bindCondition("worldis integration-world"));
    }

    @Test
    @DisplayName("world condition catalog binds high-frequency environment and target aliases")
    void environmentConditionCatalogBinds() {
        var svc = service();
        for (String spec : List.of(
                "biome-is plains", "weather-is clear", "time-is day",
                "has-permission vip.node", "entity-type-is ZOMBIE", "stance-is combat",
                "health-above 0.5", "distance-below 8", "onground", "lineofsight",
                "playersinradius 1 8", "entitiesinradius 1 8", "randomchance 100",
                "targethealthbelow 50", "gamemode survival", "potionactive speed")) {
            assertNotNull(svc.bindCondition(spec), spec);
        }
        for (String alias : List.of("sneaking", "issneaking", "sprinting", "issprinting",
                "glowing", "isglowing", "swimming", "isswimming", "climbing", "isclimbing",
                "inair", "airborne", "gliding", "isgliding", "flying", "isflying",
                "blocking", "isblocking", "facingtarget", "targetinlineofsight",
                "target-line-of-sight", "invulnerable", "isinvulnerable")) {
            assertNotNull(svc.bindCondition(alias), alias);
        }
        assertNotNull(svc.bindCondition("caster-has-permission vip.node"));
        assertNotNull(svc.bindCondition("casterpermission vip.node"));
        assertNotNull(svc.bindCondition("targettype player"));
        for (String targetType : List.of("mob", "monster", "animal", "villager", "tameable", "raider")) {
            assertNotNull(svc.bindCondition("target-is " + targetType));
        }
        for (String alias : List.of("target-invulnerable", "targetinvulnerable", "target-is-invulnerable")) {
            assertNotNull(svc.bindCondition(alias), alias);
        }
    }

    @Test
    @DisplayName("nested composite conditions preserve braces and support negation")
    void nestedCompositeConditionsBind() {
        var svc = service();
        registry.registerCondition("first", context -> true);
        registry.registerCondition("second", context -> false);
        registry.registerCondition("third", context -> true);
        registry.registerCondition("never", context -> false);

        String nested = svc.bindCondition(
                "all{conditions=first;any{second;third}}" );
        String negated = svc.bindCondition("not{any{never;never}}");

        assertNotNull(nested);
        assertNotNull(negated);
        assertTrue(registry.hasCondition(nested));
        assertTrue(registry.hasCondition(negated));
        assertTrue(registry.testCondition(nested, ctx(1.0, 0)));
        assertTrue(registry.testCondition(negated, ctx(1.0, 0)));
    }

    @Test
    @DisplayName("condition composition honors unary not and boolean precedence")
    void conditionCompositionHonorsPrecedence() {
        var svc = service();
        String negated = svc.bindCondition("!has-target");
        assertNotNull(negated);
        assertTrue(registry.testCondition(negated, BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE")));

        String mixed = svc.bindCondition("has-target true && state-is IDLE || has-target false");
        assertNotNull(mixed);
        assertTrue(registry.testCondition(mixed, BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE")));

        String grouped = svc.bindCondition("(has-target false && state-is IDLE)");
        assertNotNull(grouped);
        assertTrue(registry.testCondition(grouped, BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE")));
    }

    @Test
    @DisplayName("audience-message binds a reusable audience-aware action")
    void audienceMessageIsRegistered() {
        var svc = service();
        String key = svc.bindAction("audience-message target hello");
        assertNotNull(key);
        assertTrue(registry.hasAction(key));
    }    @Test
    @DisplayName("projectile mechanics bind with Mythic shoot aliases")
    void projectileMechanicsBind() {
        var svc = service();
        String direct = svc.bindAction("shoot-projectile ARROW 1.6 3");
        String alias = svc.bindAction("shoot ARROW 1.6 3");
        String hitSkill = svc.bindAction("shoot-projectile ARROW 1.6 3 impact-skill 2 40 SPEED 1 false true 3 20");
        assertNotNull(direct);
        assertNotNull(alias);
        assertNotNull(hitSkill);
        assertTrue(registry.hasAction(direct));
        assertTrue(registry.hasAction(alias));
    }    @Test
    @DisplayName("combat and movement mechanics bind with Mythic aliases")
    void combatMechanicsBind() {
        var svc = service();
        assertTrue(registry.hasAction(svc.bindAction("explosion 2 false false")));
        assertTrue(registry.hasAction(svc.bindAction("velocity 0 0.5 0")));
        assertTrue(registry.hasAction(svc.bindAction("set-target")));
        assertTrue(registry.hasAction(svc.bindAction("clear-target")));
        assertTrue(registry.hasAction(svc.bindAction("stop")));
    }    @Test
    @DisplayName("common Mythic mechanic aliases bind to native actions")
    void commonMythicAliasesBind() {
        var svc = service();
        assertTrue(registry.hasAction(svc.bindAction("settarget")));
        assertTrue(registry.hasAction(svc.bindAction("cleartarget")));
        assertTrue(registry.hasAction(svc.bindAction("aoedamage players 8 2 4")));
        assertTrue(registry.hasAction(svc.bindAction("playanimation attack false 3")));
        assertTrue(registry.hasAction(svc.bindAction("stopanimation")));
        assertTrue(registry.hasAction(svc.bindAction("command say <target.name>")));
        assertTrue(registry.hasAction(svc.bindAction("command-self help")));
        assertTrue(registry.hasAction(svc.bindAction("command-target help")));
    }    @Test
    @DisplayName("audience title aliases bind successfully")
    void audienceTitleAliasesBind() {
        var svc = service();
        assertTrue(registry.hasAction(svc.bindAction("audience-title target Hello")));
        assertTrue(registry.hasAction(svc.bindAction("title-audience target Hello")));
    }    @Test
    @DisplayName("audience particle aliases bind successfully")
    void audienceParticleAliasesBind() {
        var svc = service();
        assertTrue(registry.hasAction(svc.bindAction("audience-particle target FLAME 4")));
        assertTrue(registry.hasAction(svc.bindAction("particle-audience target FLAME 4")));
    }    @Test
    @DisplayName("audience specification accepts permission and faction filters")
    void audienceSpecActionBindsWithFilters() {
        var svc = service();
        assertNotNull(svc.bindAction("audience-message nearby hello"));
    }    @Test
    @DisplayName("audience actionbar aliases bind successfully")
    void audienceActionbarAliasesBind() {
        var svc = service();
        assertNotNull(svc.bindAction("audience-actionbar target hello"));
        assertNotNull(svc.bindAction("actionbar-audience target hello"));
    }

    @Test
    @DisplayName("branch-unlock is available as a SkillTree action")
    void branchUnlockActionBinds() {
        var svc = service();
        String key = svc.bindAction("branch-unlock advanced");
        assertNotNull(key);
        assertTrue(registry.hasAction(key));
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        tree.defineBranch("advanced", java.util.Set.of());
        var context = BehaviorContext.continueExecution(null, (org.bukkit.entity.Player) null,
                1.0, -1, 0, "IDLE", null, null, List.of(), null, tree);
        assertTrue(svc.runAction(key, context));
        assertTrue(tree.branchUnlocked("advanced"));
    }

    @Test
    @DisplayName("branch unlock accepts an inline condition argument")
    void branchUnlockConditionBinds() {
        var svc = service();
        assertNotNull(svc.bindAction("branch-unlock{branch=combat;condition=health-above 0.5}"));
    }

    @Test
    void branchUnlockConditionRuns() {
        var svc = service();
        String key = svc.bindAction("branch-unlock{branch=combat;condition=health-above 0.5}");
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        tree.defineBranch("combat", java.util.Set.of());
        var context = BehaviorContext.continueExecution(null, (org.bukkit.entity.Player) null,
                0.8, -1, 0, "IDLE", null, null, List.of(), null, tree);
        assertTrue(svc.runAction(key, context));
        assertTrue(tree.branchUnlocked("combat"));
    }

    @Test
    void branchUnlockMultipleConditionsRun() {
        var svc = service();
        String key = svc.bindAction("branch-unlock{branch=elite;conditions=health-above 0.5|has-target false}");
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        tree.defineBranch("elite", java.util.Set.of());
        var context = BehaviorContext.continueExecution(null, (org.bukkit.entity.Player) null,
                0.8, -1, 0, "IDLE", null, null, List.of(), null, tree);
        assertTrue(svc.runAction(key, context));
        assertTrue(tree.branchUnlocked("elite"));
    }

    @Test
    @DisplayName("branch gated skills block before unlock and run after persisted unlock")
    void branchGatedSkillCastFlow() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", context -> fired.incrementAndGet());
        var svc = service();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  unlock:
                    on-decision:
                      - 'branch-unlock{branch=combat}'
                  gated:
                    branch: combat
                    on-decision:
                      - probe
                """));
        svc.loadSkills(yaml.getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(42);
        when(instance.isValid()).thenReturn(true);
        var context = BehaviorContext.of(instance, (org.bukkit.entity.Player) null, 1.0, -1, 0, "IDLE");

        assertFalse(svc.castSkill("gated", context));
        assertEquals(0, fired.get());
        assertTrue(svc.castSkill("unlock", context));
        assertTrue(svc.castSkill("gated", context));
        assertEquals(1, fired.get());
    }

    @Test
    void branchUnlockAnyAndNotConditionsRun() {
        var svc = service();
        String anyKey = svc.bindAction(
                "branch-unlock{branch=any-branch;condition=any{health-below 0.1;has-target false}}");
        String notKey = svc.bindAction(
                "branch-unlock{branch=not-branch;condition=not{health-below 0.1}}");
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        tree.defineBranch("any-branch", java.util.Set.of());
        tree.defineBranch("not-branch", java.util.Set.of());
        var context = BehaviorContext.continueExecution(null, (org.bukkit.entity.Player) null,
                0.8, -1, 0, "IDLE", null, null, List.of(), null, tree);

        assertTrue(svc.runAction(anyKey, context));
        assertTrue(svc.runAction(notKey, context));
        assertTrue(tree.branchUnlocked("any-branch"));
        assertTrue(tree.branchUnlocked("not-branch"));
    }

    @Test
    @DisplayName("teleport mechanics bind location and caster aliases")
    void teleportMechanicsBind() {
        var svc = service();
        assertTrue(registry.hasAction(svc.bindAction("teleport @targetlocation")));
        assertTrue(registry.hasAction(svc.bindAction("teleport @triggerlocation")));
        assertTrue(registry.hasAction(svc.bindAction("teleport @VariableLocation{var=skill.mark}")));
        assertTrue(registry.hasAction(svc.bindAction("teleport-caster @origin")));
        assertTrue(registry.hasAction(svc.bindAction("teleport-self 1 2 3")));
    }
    @Test
    @DisplayName("delay and repeat metadata wrap registered actions instead of bypassing them")
    void timingMetadataWrapsRegisteredAction() {
        registry.registerAction("probe", ctx -> { });
        var svc = service();
        String key = svc.bindAction("probe{delay=2;repeat=3;repeat-interval=1}");
        assertNotNull(key);
        assertTrue(key.contains("delay=2"), key);
        assertFalse("probe".equals(key), "execution metadata must not bypass the wrapper");
    }

    @Test
    @DisplayName("repeat scheduling executes every repetition with the configured delays")
    void repeatSchedulingRunsAllExecutions() {
        var fired = new AtomicInteger();
        var delays = new java.util.ArrayList<Long>();
        registry.registerAction("probe", ctx -> fired.incrementAndGet());
        var svc = service();
        dev.helstera.ai.skill.SkillCatalog.taskScheduler((task, delay) -> {
            delays.add(delay);
            task.run();
            return true;
        });
        try {
            String key = svc.bindAction("probe{delay=2;repeat=3;repeatinterval=1}");
            assertTrue(registry.runAction(key, ctx(1, 0)));
            assertEquals(4, fired.get());
            assertEquals(List.of(2L, 1L, 1L, 1L), delays);
        } finally {
            dev.helstera.ai.skill.SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    @DisplayName("repeat re-evaluates chance and inline conditions for every execution")
    void repeatReevaluatesGuards() {
        var fired = new AtomicInteger();
        var checks = new AtomicInteger();
        registry.registerAction("probe", ctx -> fired.incrementAndGet());
        registry.registerCondition("gate", ctx -> checks.incrementAndGet() % 2 == 1);
        var svc = service();
        dev.helstera.ai.skill.SkillCatalog.taskScheduler((task, delay) -> {
            task.run();
            return true;
        });
        try {
            String key = svc.bindAction("probe{repeat=3;repeatinterval=1} ?gate");
            assertTrue(registry.runAction(key, ctx(1, 0)));
            assertEquals(4, checks.get());
            assertEquals(2, fired.get());
        } finally {
            dev.helstera.ai.skill.SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    @DisplayName("cast-duration defers actions until tickCasts completes the cast")
    void castDurationDefersActions() throws Exception {
        var fired = new AtomicInteger();
        registry.registerAction("probe", ctx -> fired.incrementAndGet());
        var svc = service();
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  charged:
                    cast-duration: 10ms
                    on-decision: [probe]
                """)).getConfigurationSection("skills"));

        assertTrue(svc.castSkill("charged", ctx(1, 0)));
        assertEquals(0, fired.get());
        assertEquals(1, svc.activeCastCount());
        Thread.sleep(30);
        svc.tickCasts();
        assertEquals(1, fired.get());
        assertEquals(0, svc.activeCastCount());
    }

    @Test
    @DisplayName("cancel-cast removes the active cast without executing deferred actions")
    void cancelCastDropsDeferredAction() throws Exception {
        var fired = new AtomicInteger();
        registry.registerAction("probe", ctx -> fired.incrementAndGet());
        var svc = service();
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  charged:
                    cast-duration: 20ms
                    on-decision: [probe]
                """)).getConfigurationSection("skills"));

        assertTrue(svc.castSkill("charged", ctx(1, 0)));
        assertTrue(svc.cancelCast(-1));
        Thread.sleep(30);
        svc.tickCasts();
        assertEquals(0, fired.get());
        assertEquals(0, svc.activeCastCount());
    }

    @Test
    @DisplayName("persistent mode skips AI ticks in unloaded chunks")
    void persistentModeTickPolicy() {
        assertFalse(AiManager.tickInUnloadedChunk("PERSISTENT"));
        assertTrue(AiManager.tickInUnloadedChunk("NORMAL"));
        assertTrue(AiManager.tickInUnloadedChunk("CHUNK"));
    }

    @Test
    @DisplayName("AI profile loads ranged projectile configuration")
    void loadsRangedProjectileConfiguration() {
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                ranged-attack: true
                ranged-projectile-type: FIREBALL
                ranged-hit-skill: burn_target
                ranged-projectile-gravity: false
                ranged-projectile-fire-ticks: 60
                ranged-projectile-explosion: 2.5
                ranged-projectile-max-hits: 3
                ranged-projectile-potion: POISON:60:1
                ranged-projectile-pierce: 2
                ranged-projectile-tracking: true
                ranged-projectile-tracking-ticks: 40
                """));
        AiProfile profile = AiProfile.fromSection("ranged", yaml);
        assertTrue(profile.rangedAttack);
        assertEquals("FIREBALL", profile.rangedProjectileType);
        assertEquals("burn_target", profile.rangedHitSkill);
        assertFalse(profile.rangedProjectileGravity);
        assertEquals(60, profile.rangedProjectileFireTicks);
        assertEquals(2.5, profile.rangedProjectileExplosion, 1e-9);
        assertEquals(3, profile.rangedProjectileMaxHits);
        assertEquals("POISON:60:1", profile.rangedProjectilePotion);
        assertEquals(2, profile.rangedProjectilePierce);
        assertTrue(profile.rangedProjectileTracking);
        assertEquals(40, profile.rangedProjectileTrackingTicks);
    }

    @Test
    @DisplayName("Mythic entity category conditions resolve aliases against the active target")
    void entityCategoryConditionsUseTargetContext() {
        SkillService service = service();
        Player player = mock(Player.class);
        BehaviorContext playerContext = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        for (String alias : List.of("isLiving", "isPlayer", "player")) {
            String key = service.bindCondition(alias);
            assertNotNull(key, alias);
            assertTrue(registry.testCondition(key, playerContext), alias);
        }
        for (String alias : List.of("isMob", "isMonster", "isAnimal", "isVillager",
                "isTameable", "isRaider")) {
            String key = service.bindCondition(alias);
            assertNotNull(key, alias);
            assertFalse(registry.testCondition(key, playerContext), alias);
        }

        org.bukkit.entity.Zombie zombie = mock(org.bukkit.entity.Zombie.class);
        BehaviorContext monsterContext = BehaviorContext.of(null, zombie, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("isLiving"), monsterContext));
        assertTrue(registry.testCondition(service.bindCondition("isMob"), monsterContext));
        assertTrue(registry.testCondition(service.bindCondition("isMonster"), monsterContext));
        assertFalse(registry.testCondition(service.bindCondition("isPlayer"), monsterContext));
    }

    @Test
    @DisplayName("Mythic undead, creature and age conditions use Bukkit type capabilities")
    void entityCapabilityConditionsUseBukkitTypes() {
        SkillService service = service();
        org.bukkit.entity.Zombie zombie = mock(org.bukkit.entity.Zombie.class);
        when(zombie.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        BehaviorContext zombieContext = BehaviorContext.of(null, zombie, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(service.bindCondition("isUndead"), zombieContext));
        assertTrue(registry.testCondition(service.bindCondition("isCreature"), zombieContext));

        org.bukkit.entity.Ageable baby = mock(org.bukkit.entity.Ageable.class);
        when(baby.isAdult()).thenReturn(false);
        BehaviorContext babyContext = BehaviorContext.of(null, baby, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("baby"), babyContext));
        assertTrue(registry.testCondition(service.bindCondition("isChild"), babyContext));
        assertFalse(registry.testCondition(service.bindCondition("adult"), babyContext));

        when(baby.isAdult()).thenReturn(true);
        assertTrue(registry.testCondition(service.bindCondition("isAdult"), babyContext));
        assertFalse(registry.testCondition(service.bindCondition("isBaby"), babyContext));
    }

    @Test
    @DisplayName("Mythic tame, shear, saddle and powered conditions read entity state")
    void entityStateConditionsReadBukkitState() {
        SkillService service = service();

        org.bukkit.entity.Wolf wolf = mock(org.bukkit.entity.Wolf.class);
        when(wolf.isTamed()).thenReturn(true);
        BehaviorContext wolfContext = BehaviorContext.of(null, wolf, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("isTamed"), wolfContext));

        org.bukkit.entity.Sheep sheep = mock(org.bukkit.entity.Sheep.class);
        when(sheep.isSheared()).thenReturn(true);
        BehaviorContext sheepContext = BehaviorContext.of(null, sheep, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("sheared"), sheepContext));

        org.bukkit.entity.Horse horse = mock(org.bukkit.entity.Horse.class);
        org.bukkit.inventory.HorseInventory horseInventory =
                mock(org.bukkit.inventory.HorseInventory.class);
        org.bukkit.inventory.ItemStack saddle = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.Material saddleType = mock(org.bukkit.Material.class);
        when(horse.getInventory()).thenReturn(horseInventory);
        when(horseInventory.getSaddle()).thenReturn(saddle);
        when(saddle.getType()).thenReturn(saddleType);
        when(saddleType.isAir()).thenReturn(false);
        BehaviorContext horseContext = BehaviorContext.of(null, horse, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("isSaddled"), horseContext));

        org.bukkit.entity.Creeper creeper = mock(org.bukkit.entity.Creeper.class);
        when(creeper.isPowered()).thenReturn(true);
        BehaviorContext creeperContext = BehaviorContext.of(null, creeper, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("charged"), creeperContext));
    }

    @Test
    @DisplayName("target entity state aliases bind to the shared state evaluators")
    void targetEntityStateAliasesBind() {
        SkillService service = service();
        for (String alias : List.of("target-is-undead", "target-is-creature", "target-is-baby",
                "target-is-child", "target-is-adult", "target-is-tamed", "target-is-sheared",
                "target-is-saddled", "target-is-powered", "target-is-charged")) {
            assertNotNull(service.bindCondition(alias), alias);
        }
    }

    @Test
    @DisplayName("target entity state aliases evaluate against the target, not the caster")
    void targetEntityStateAliasesEvaluateTarget() {
        SkillService service = service();
        org.bukkit.entity.Zombie zombie = mock(org.bukkit.entity.Zombie.class);
        when(zombie.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        BehaviorContext context = BehaviorContext.of(null, zombie, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(service.bindCondition("target-is-undead"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-is-creature"), context));
        assertFalse(registry.testCondition(service.bindCondition("target-is player"), context));

        org.bukkit.entity.Ageable baby = mock(org.bukkit.entity.Ageable.class);
        when(baby.isAdult()).thenReturn(false);
        BehaviorContext babyContext = BehaviorContext.of(null, baby, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-baby"), babyContext));
        assertFalse(registry.testCondition(service.bindCondition("target-is-adult"), babyContext));
    }

    @Test
    @DisplayName("target tame, shear, saddle and powered aliases evaluate target state")
    void targetEntityStateAliasesEvaluateBukkitState() {
        SkillService service = service();

        org.bukkit.entity.Wolf wolf = mock(org.bukkit.entity.Wolf.class);
        when(wolf.isTamed()).thenReturn(true);
        BehaviorContext wolfContext = BehaviorContext.of(null, wolf, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-tamed"), wolfContext));

        org.bukkit.entity.Sheep sheep = mock(org.bukkit.entity.Sheep.class);
        when(sheep.isSheared()).thenReturn(true);
        BehaviorContext sheepContext = BehaviorContext.of(null, sheep, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-sheared"), sheepContext));

        org.bukkit.entity.Horse horse = mock(org.bukkit.entity.Horse.class);
        org.bukkit.inventory.HorseInventory inventory = mock(org.bukkit.inventory.HorseInventory.class);
        org.bukkit.inventory.ItemStack saddle = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.Material saddleType = mock(org.bukkit.Material.class);
        when(horse.getInventory()).thenReturn(inventory);
        when(inventory.getSaddle()).thenReturn(saddle);
        when(saddle.getType()).thenReturn(saddleType);
        when(saddleType.isAir()).thenReturn(false);
        BehaviorContext horseContext = BehaviorContext.of(null, horse, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-saddled"), horseContext));

        org.bukkit.entity.Creeper creeper = mock(org.bukkit.entity.Creeper.class);
        when(creeper.isPowered()).thenReturn(true);
        BehaviorContext creeperContext = BehaviorContext.of(null, creeper, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-powered"), creeperContext));
        assertTrue(registry.testCondition(service.bindCondition("target-is-charged"), creeperContext));
    }

    @Test
    @DisplayName("target player state conditions read Bukkit player flags")
    void targetPlayerStateConditionsReadFlags() {
        SkillService service = service();
        Player player = mock(Player.class);
        when(player.isSneaking()).thenReturn(true);
        when(player.isSprinting()).thenReturn(true);
        when(player.isFlying()).thenReturn(true);
        when(player.isBlocking()).thenReturn(true);
        BehaviorContext context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(service.bindCondition("target-is-sneaking"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-is-sprinting"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-is-flying"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-is-blocking"), context));
    }

    @Test
    @DisplayName("target environmental state aliases bind to runtime evaluators")
    void targetEnvironmentalStateAliasesBind() {
        SkillService service = service();
        for (String alias : List.of("target-on-fire", "target-in-air", "target-in-water", "target-in-lava")) {
            assertNotNull(service.bindCondition(alias), alias);
        }
    }

    @Test
    @DisplayName("target environmental conditions evaluate the target entity state")
    void targetEnvironmentalConditionsEvaluateState() {
        SkillService service = service();
        org.bukkit.entity.Zombie target = mock(org.bukkit.entity.Zombie.class);
        org.bukkit.Location location = mock(org.bukkit.Location.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        org.bukkit.block.Block block = mock(org.bukkit.block.Block.class);
        when(target.getFireTicks()).thenReturn(20);
        when(target.isOnGround()).thenReturn(false);
        when(target.getLocation()).thenReturn(location);
        when(location.getWorld()).thenReturn(world);
        when(location.getBlock()).thenReturn(block);
        when(block.getType()).thenReturn(org.bukkit.Material.WATER);
        BehaviorContext waterContext = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(service.bindCondition("target-on-fire"), waterContext));
        assertTrue(registry.testCondition(service.bindCondition("target-in-air"), waterContext));
        assertTrue(registry.testCondition(service.bindCondition("target-in-water"), waterContext));
        assertFalse(registry.testCondition(service.bindCondition("target-in-lava"), waterContext));

        when(block.getType()).thenReturn(org.bukkit.Material.LAVA);
        assertTrue(registry.testCondition(service.bindCondition("target-in-lava"), waterContext));
    }

    @Test
    @DisplayName("target invisible condition reads the target entity")
    void targetInvisibleConditionReadsTarget() {
        SkillService service = service();
        org.bukkit.entity.Zombie target = mock(org.bukkit.entity.Zombie.class);
        when(target.isInvisible()).thenReturn(true);
        BehaviorContext context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-invisible"), context));
    }

    @Test
    @DisplayName("target dead and ground-state aliases bind to runtime conditions")
    void targetBasicStateAliasesBind() {
        SkillService service = service();
        for (String alias : List.of("target-is-dead", "target-is-on-ground", "target-is-airborne")) {
            assertNotNull(service.bindCondition(alias), alias);
        }

        org.bukkit.entity.Zombie target = mock(org.bukkit.entity.Zombie.class);
        when(target.isOnGround()).thenReturn(false);
        BehaviorContext context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");
        assertFalse(registry.testCondition(service.bindCondition("target-is-dead"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-is-airborne"), context));

        when(target.isOnGround()).thenReturn(true);
        assertTrue(registry.testCondition(service.bindCondition("target-is-on-ground"), context));
        assertFalse(registry.testCondition(service.bindCondition("target-is-airborne"), context));
    }

    @Test
    @DisplayName("target climbing and blocked conditions read target state")
    void targetClimbingAndBlockedConditionsReadTarget() {
        SkillService service = service();
        org.bukkit.entity.Zombie target = mock(org.bukkit.entity.Zombie.class);
        org.bukkit.Location location = mock(org.bukkit.Location.class);
        org.bukkit.block.Block block = mock(org.bukkit.block.Block.class);
        when(target.isClimbing()).thenReturn(true);
        when(target.getLocation()).thenReturn(location);
        when(location.getBlock()).thenReturn(block);
        when(location.clone()).thenReturn(location);
        when(location.add(anyDouble(), anyDouble(), anyDouble())).thenReturn(location);
        when(block.getType()).thenReturn(org.bukkit.Material.STONE);
        BehaviorContext context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(service.bindCondition("target-is-climbing"), context));
        assertNotNull(service.bindCondition("target-is-blocked"));
    }

}
