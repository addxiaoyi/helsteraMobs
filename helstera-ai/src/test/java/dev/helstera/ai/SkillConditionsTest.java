package dev.helstera.ai;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.ai.skill.SkillCatalog;
import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.ai.skill.PlayerKillCount;
import dev.helstera.api.instance.ModelInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.Location;

import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 新增触发条件的行为测试。
 *
 * <p>重点是<b>无目标 / 无实例</b>这些边界：条件用于 require 时，
 * 一旦在缺数据的情况下返回 true，技能就会在不该触发的场合反复放行。</p>
 */
class SkillConditionsTest {

    private static <T extends org.bukkit.Keyed> T keyedVariant(Class<T> type, String name) {
        var key = new org.bukkit.NamespacedKey("minecraft", name.toLowerCase(java.util.Locale.ROOT));
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getKey" -> key;
                    case "name", "toString" -> name.toUpperCase(java.util.Locale.ROOT);
                    case "ordinal", "compareTo" -> 0;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }

    private static BehaviorContext ctx() {
        // 实例与目标均为 null：模拟实体尚未绑定 / 无仇恨目标的场合
        return BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE");
    }

    private static SkillCatalog.ConditionFactory factory(String name) {
        var f = SkillCatalog.conditions().get(name);
        assertNotNull(f, "内置条件 " + name + " 不存在");
        return f;
    }

    private static SkillCatalog.ConditionFactory extraFactory(String name) {
        var f = SkillExtras.conditions().get(name);
        assertNotNull(f, "扩展条件 " + name + " 不存在");
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

    @Test
    @DisplayName("has-line-of-sight：读取施法者和目标的真实 Bukkit 视线状态")
    void lineOfSightReadsCasterAndTargetState() {
        var instance = mock(ModelInstance.class);
        var caster = mock(LivingEntity.class);
        var target = mock(LivingEntity.class);
        var world = mock(World.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.getWorld()).thenReturn(world);
        when(target.getWorld()).thenReturn(world);
        when(caster.hasLineOfSight(target)).thenReturn(true, false);

        var condition = factory("has-line-of-sight").create(List.of());
        var context = BehaviorContext.of(instance, target, 1.0, 3.0, 0, "LOS");
        assertTrue(condition.test(context));
        assertFalse(condition.test(context));
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

    /**
     * cooldown-ready 的非数字参数在装载期抛异常。
     *
     * <p><b>行为期望被反转过。</b>原名 {@code cooldownNonNumericArg}，断言
     * 「非数字参数回退为 0，不抛异常」，理由是「不应该中断技能加载」。</p>
     *
     * <p>这个理由当时成立，但它同时带来一个更糟的后果：
     * {@code cooldown-ready abc} 被静默当成「无冷却」，于是技能<b>每次都能放</b>，
     * 而作者以为设了冷却。冷却失效在实战里表现为「Boss 技能无缝连放」，
     * 现场没有任何提示。</p>
     *
     * <p>「不中断加载」这个目标其实一直达成——抛出的异常由
     * {@code SkillService.bindCondition} 的 try-catch 接住，转成一条 warning，
     * 加载照常完成，体检里能看到原因。既不中断加载，又能看见问题。</p>
     */
    @Test
    @DisplayName("cooldown-ready：非数字参数抛异常，由 bindCondition 转成告警")
    void cooldownNonNumericArg() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> factory("cooldown-ready").create(List.of("abc")));
        assertTrue(e.getMessage().contains("abc"),
                "异常信息应含原始字面量：实际 = " + e.getMessage());
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

    @Test
    @DisplayName("Targets 匹配 skill tree 继承目标数量并支持 targets targeter")
    void officialTargetsConditionCountsInheritedTargets() {
        LivingEntity first = mock(LivingEntity.class);
        LivingEntity second = mock(LivingEntity.class);
        var context = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "IDLE", null, null, List.of(first, second), null);

        assertTrue(extraFactory("targets").create(List.of(">=2")).test(context));
        assertTrue(extraFactory("targets").create(List.of("0to2")).test(context));
        assertFalse(extraFactory("targets").create(List.of(">2")).test(context));
        assertTrue(extraFactory("targets").create(List.of("amount=>=2", "targeter=@targets"))
                .test(context));
        assertFalse(extraFactory("targets").create(List.of(">0")).test(ctx()));
    }

    @Test
    @DisplayName("vehicle/passenger 条件区分载具和乘客状态")
    void vehicleAndPassengerConditions() {
        LivingEntity vehicle = org.mockito.Mockito.mock(LivingEntity.class);
        Entity passenger = org.mockito.Mockito.mock(Entity.class);
        org.mockito.Mockito.when(vehicle.getPassengers()).thenReturn(List.of(passenger));
        BehaviorContext context = BehaviorContext.of(null, vehicle, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("is-vehicle").create(List.of()).test(context));
        assertTrue(extraFactory("has-passenger").create(List.of()).test(context));
        assertTrue(extraFactory("target-is-vehicle").create(List.of()).test(context));
        assertTrue(extraFactory("target-has-passenger").create(List.of()).test(context));
    }

    @Test
    @DisplayName("name-is：优先匹配自定义名，无目标或空参数时失败关闭")
    void nameConditionMatchesCustomAndEntityNames() {
        LivingEntity named = mock(LivingEntity.class);
        when(named.getCustomName()).thenReturn("Emberling");
        when(named.getName()).thenReturn("Zombie");
        BehaviorContext context = BehaviorContext.of(null, named, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("name-is").create(List.of("Emberling")).test(context));
        assertFalse(extraFactory("name-is").create(List.of("Zombie")).test(context));
        assertFalse(extraFactory("name-is").create(List.of()).test(ctx()));

        when(named.getCustomName()).thenReturn(null);
        assertTrue(extraFactory("name-is").create(List.of("Zombie")).test(context));
    }

    @Test
    @DisplayName("official population and dimension conditions fail closed without a context")
    void extendedOfficialConditionsAreRegistered() {
        assertFalse(extraFactory("living-in-radius").create(List.of(">=1", "5")).test(ctx()));
        assertFalse(extraFactory("mobs-in-chunk").create(List.of(">=1")).test(ctx()));
        assertFalse(extraFactory("dimension-is").create(List.of("NORMAL")).test(ctx()));
    }

    @Test
    @DisplayName("official height, orientation and block conditions fail closed without a context")
    void officialSpatialConditionsAreRegistered() {
        assertFalse(extraFactory("height").create(List.of("<50")).test(ctx()));
        assertFalse(extraFactory("height-above").create(List.of("50")).test(ctx()));
        assertFalse(extraFactory("height-below").create(List.of("50")).test(ctx()));
        assertFalse(extraFactory("pitch").create(List.of("-45to45")).test(ctx()));
        assertFalse(extraFactory("yaw").create(List.of("90to180", "true")).test(ctx()));
        assertFalse(extraFactory("on-block").create(List.of("STONE")).test(ctx()));
        assertFalse(extraFactory("line-of-sight-from-origin").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("BowTension 只读取 EntityShootBowEvent 的力度")
    void officialBowTensionConditionReadsShootEventForce() {
        var event = mock(org.bukkit.event.entity.EntityShootBowEvent.class);
        when(event.getForce()).thenReturn(0.75f);
        var context = BehaviorContext.continueExecution(null, (LivingEntity) null,
                1.0, -1, 0, "on-entity-shoot", null, null, List.of(), null, null, event);

        assertTrue(extraFactory("bow-tension").create(List.of(">0.5")).test(context));
        assertFalse(extraFactory("bow-tension").create(List.of(">0.8")).test(context));
        assertFalse(extraFactory("bow-tension").create(List.of(">0.5")).test(ctx()));
    }

    @Test
    @DisplayName("StringEquals 支持字面量和执行期 placeholder")
    void officialStringEqualsConditionMatchesValues() {
        assertTrue(extraFactory("string-equals").create(List.of("yes", "yes")).test(ctx()));
        assertFalse(extraFactory("string-equals").create(List.of("yes", "no")).test(ctx()));
    }

    @Test
    @DisplayName("CompareValues 支持数字、字符串和操作符别名")
    void officialCompareValuesConditionComparesTypedValues() {
        assertTrue(extraFactory("compare-values").create(List.of("2", "10", "<")).test(ctx()));
        assertTrue(extraFactory("compare-values").create(List.of("foo", "bar", "GREATER_THAN", "STRING"))
                .test(ctx()));
        assertTrue(extraFactory("compare-values").create(List.of("2", "2", "EQUALS", "INTEGER"))
                .test(ctx()));
        assertTrue(extraFactory("compare-values").create(List.of("2", "2", "GREATER_THAN_OR_EQUAL", "INTEGER"))
                .test(ctx()));
        assertTrue(extraFactory("compare-values").create(List.of("2", "2", "LESS_THAN_OR_EQUAL", "INTEGER"))
                .test(ctx()));
        assertTrue(extraFactory("compare-values").create(List.of("2", "3", "NOT_EQUAL", "INTEGER"))
                .test(ctx()));
        assertFalse(extraFactory("compare-values").create(List.of("2", "two", ">", "INTEGER"))
                .test(ctx()));
    }

    @Test
    @DisplayName("CompareValues 对 NaN 和 Infinity 失败关闭")
    void officialCompareValuesRejectsNonFiniteNumbers() {
        var factory = extraFactory("compare-values");

        assertFalse(factory.create(List.of("NaN", "1", ">", "DOUBLE")).test(ctx()));
        assertFalse(factory.create(List.of("1", "Infinity", "<", "DOUBLE")).test(ctx()));
        assertFalse(factory.create(List.of("-Infinity", "1", "<", "DOUBLE")).test(ctx()));
    }

    @Test
    @DisplayName("VariableContains 支持字符串前后缀和集合 ALL/ANY")
    void officialVariableContainsSupportsCompareTypes() {
        var instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(321);
        var context = BehaviorContext.execution(instance, null, 1.0, -1, 0, "IDLE", null,
                null, List.of(), null);
        try {
            SkillExtras.actions().get("set-variable").create(List.of("text", "dungeon_alpha",
                    "type=STRING")).accept(context);
            SkillExtras.actions().get("set-variable").create(List.of("items", "red,blue",
                    "type=LIST")).accept(context);
            assertTrue(extraFactory("variable-contains").create(List.of(
                    "text", "dungeon_", "STARTS_WITH")).test(context));
            assertTrue(extraFactory("variable-contains").create(List.of(
                    "text", "alpha", "ENDS_WITH")).test(context));
            assertTrue(extraFactory("variable-contains").create(List.of(
                    "items", "red,blue", "ALL")).test(context));
            assertTrue(extraFactory("variable-contains").create(List.of(
                    "items", "red,green", "ANY")).test(context));
        } finally {
            SkillExtras.forget(321);
        }
    }

    @Test
    @DisplayName("StringEmpty 和 StringNotEmpty 检查解析后的文本")
    void officialStringEmptyConditionsMatchText() {
        assertTrue(extraFactory("string-empty").create(List.of("")).test(ctx()));
        assertFalse(extraFactory("string-empty").create(List.of("value")).test(ctx()));
        assertTrue(extraFactory("string-not-empty").create(List.of("value")).test(ctx()));
        assertFalse(extraFactory("string-not-empty").create(List.of("")).test(ctx()));
    }

    @Test
    @DisplayName("IsSkill 读取注入的命名技能表并对未知技能 fail-closed")
    void officialIsSkillConditionUsesSkillLookup() {
        try {
            SkillExtras.skillLookup(name -> "known-skill".equalsIgnoreCase(name));
            assertTrue(extraFactory("is-skill").create(List.of("known-skill")).test(ctx()));
            assertFalse(extraFactory("is-skill").create(List.of("missing-skill")).test(ctx()));
        } finally {
            SkillExtras.skillLookup(null);
        }
    }

    @Test
    @DisplayName("GlobalScore 读取主 scoreboard fake entry")
    void officialGlobalScoreConditionUsesLookup() {
        try {
            SkillExtras.globalScoreLookup((objective, entry) ->
                    "KillCount".equals(objective) && "GLOBAL".equals(entry) ? 5 : null);
            assertTrue(extraFactory("global-score").create(List.of("KillCount", "5")).test(ctx()));
            assertTrue(extraFactory("global-score").create(List.of("KillCount", ">=5")).test(ctx()));
            assertFalse(extraFactory("global-score").create(List.of("Missing", "5")).test(ctx()));
            SkillExtras.globalScoreLookup((objective, entry) ->
                    "KillCount".equals(objective) && "__GLOBAL__".equals(entry) ? 5 : null);
            assertTrue(extraFactory("global-score").create(List.of("KillCount", "5")).test(ctx()));
        } finally {
            SkillExtras.globalScoreLookup(null);
        }
    }

    @Test
    @DisplayName("Score 读取目标实体 objective 分数")
    void officialScoreConditionUsesEntityLookup() {
        LivingEntity target = mock(LivingEntity.class);
        try {
            SkillExtras.scoreLookup((objective, entity, entry) ->
                    "Kills".equals(objective) && entity == target && entry == null ? 7 : null);
            var context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");
            assertTrue(extraFactory("score").create(List.of("Kills", "", ">=7")).test(context));
            assertTrue(extraFactory("score").create(List.of("Kills", ">=7")).test(context));
            assertFalse(extraFactory("score").create(List.of("Missing", "", "7")).test(context));
        } finally {
            SkillExtras.scoreLookup(null);
        }
    }

    @Test
    @DisplayName("LookingAt 仅允许 Player 目标并读取 ray trace 结果")
    void officialLookingAtConditionUsesPlayerRayTrace() {
        Player player = mock(Player.class);
        var ray = mock(org.bukkit.util.RayTraceResult.class);
        var block = mock(Block.class);
        when(player.rayTraceBlocks(5.0, org.bukkit.FluidCollisionMode.NEVER)).thenReturn(ray);
        when(ray.getHitBlock()).thenReturn(block);
        when(block.getType()).thenReturn(org.bukkit.Material.STONE);
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("looking-at").create(List.of("STONE", "5")).test(context));
        assertFalse(extraFactory("looking-at").create(List.of("DIRT", "5")).test(context));
        assertFalse(extraFactory("looking-at").create(List.of("STONE", "5")).test(ctx()));
    }

    @Test
    @DisplayName("EntityItemType 和 EntityMaterialType 读取 Item trigger")
    void officialEntityItemConditionsReadItemTrigger() {
        var item = mock(org.bukkit.entity.Item.class);
        var stack = mock(org.bukkit.inventory.ItemStack.class);
        when(item.getItemStack()).thenReturn(stack);
        when(stack.getType()).thenReturn(org.bukkit.Material.STONE);
        var context = BehaviorContext.continueExecution(null, (LivingEntity) null,
                1.0, -1, 0, "on-item-pickup", null, item, List.of(), null, null);

        assertTrue(extraFactory("entity-item-type").create(List.of("STONE")).test(context));
        assertTrue(extraFactory("entity-material-type").create(List.of("STONE,DIRT")).test(context));
        assertFalse(extraFactory("entity-item-type").create(List.of("DIAMOND")).test(context));
        assertFalse(extraFactory("entity-item-type").create(List.of("STONE")).test(ctx()));
    }

    @Test
    @DisplayName("EntityItemIsSimilar 使用 custom item resolver 和 ItemStack 相似性")
    void officialEntityItemIsSimilarConditionMatchesResolvedStack() {
        var item = mock(org.bukkit.entity.Item.class);
        var actual = mock(org.bukkit.inventory.ItemStack.class);
        var expected = mock(org.bukkit.inventory.ItemStack.class);
        when(item.getItemStack()).thenReturn(actual);
        when(actual.isSimilar(any(org.bukkit.inventory.ItemStack.class))).thenReturn(true);
        try {
            SkillExtras.itemResolver(name -> "integration_item".equals(name) ? expected : null);
            var context = BehaviorContext.continueExecution(null, (LivingEntity) null,
                    1.0, -1, 0, "on-item-pickup", null, item, List.of(), null, null);
            assertTrue(extraFactory("entity-item-is-similar").create(List.of("integration_item")).test(context));
            assertFalse(extraFactory("entity-item-is-similar").create(List.of("missing_item")).test(ctx()));
        } finally {
            SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("ItemIsSimilar 只读取目标玩家指定背包槽位")
    void officialItemIsSimilarConditionMatchesPlayerSlot() {
        var player = mock(Player.class);
        var inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        var actual = mock(org.bukkit.inventory.ItemStack.class);
        var expected = mock(org.bukkit.inventory.ItemStack.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItem(0)).thenReturn(actual);
        when(actual.getType()).thenReturn(org.bukkit.Material.STONE);
        when(actual.isSimilar(expected)).thenReturn(true);
        try {
            SkillExtras.itemResolver(name -> "integration_item".equals(name) ? expected : null);
            var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");
            assertTrue(extraFactory("item-is-similar").create(List.of("integration_item", "0")).test(context));
            assertFalse(extraFactory("item-is-similar").create(List.of("integration_item", "1")).test(context));
            assertFalse(extraFactory("item-is-similar").create(List.of("integration_item", "99")).test(context));
            assertFalse(extraFactory("item-is-similar").create(List.of("integration_item", "0")).test(ctx()));
        } finally {
            SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("ItemRecharging 读取 HumanEntity 攻击冷却")
    void officialItemRechargingConditionUsesAttackCooldown() {
        Player player = mock(Player.class);
        when(player.getAttackCooldown()).thenReturn(0.75f);
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("item-recharging").create(List.of()).test(context));
        when(player.getAttackCooldown()).thenReturn(1.0f);
        assertFalse(extraFactory("item-recharging").create(List.of()).test(context));
        assertFalse(extraFactory("item-recharging").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("ItemType 复用 Item trigger 材质语义")
    void officialItemTypeConditionUsesItemTrigger() {
        var item = mock(org.bukkit.entity.Item.class);
        var stack = mock(org.bukkit.inventory.ItemStack.class);
        when(item.getItemStack()).thenReturn(stack);
        when(stack.getType()).thenReturn(org.bukkit.Material.STONE);
        var context = BehaviorContext.continueExecution(null, (LivingEntity) null,
                1.0, -1, 0, "on-item-pickup", null, item, List.of(), null, null);
        assertTrue(extraFactory("trigger-item-type").create(List.of("STONE")).test(context));
        assertTrue(extraFactory("trigger-item-type").create(List.of("STONE")).test(context));
        assertFalse(extraFactory("trigger-item-type").create(List.of("DIRT")).test(context));
    }

    @Test
    @DisplayName("Moist/MoistureLevel 只读取目标方块的 Farmland moisture")
    void officialMoistureConditionsUseFarmlandData() {
        World world = mock(World.class);
        Block block = mock(Block.class);
        Farmland farmland = mock(Farmland.class);
        when(world.getBlockAt(0, 0, 0)).thenReturn(block);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(block.getBlockData()).thenReturn(farmland);
        when(farmland.getMoisture()).thenReturn(7);
        var location = new Location(world, 0.5, 0, 0.5);
        var context = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "IDLE", null, null, List.of(), location);

        assertTrue(extraFactory("moist").create(List.of()).test(context));
        assertTrue(extraFactory("moisture-level").create(List.of("1to7")).test(context));
        assertFalse(extraFactory("moisture-level").create(List.of("0")).test(context));

        when(farmland.getMoisture()).thenReturn(0);
        assertFalse(extraFactory("moist").create(List.of()).test(context));
        assertTrue(extraFactory("moisture-level").create(List.of("0")).test(context));

        when(block.getBlockData()).thenReturn(mock(BlockData.class));
        assertFalse(extraFactory("moist").create(List.of()).test(context));
        assertFalse(extraFactory("moisture-level").create(List.of(">=0")).test(context));
    }

    @Test
    @DisplayName("MobSize 只匹配可调整大小的 Slime、MagmaCube 或 Phantom")
    void officialMobSizeConditionUsesSupportedEntities() {
        var slime = mock(org.bukkit.entity.Slime.class);
        when(slime.getSize()).thenReturn(4);
        var context = BehaviorContext.of(null, slime, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("mob-size").create(List.of(">3")).test(context));
        assertTrue(extraFactory("mob-size").create(List.of("4to5")).test(context));
        assertFalse(extraFactory("mob-size").create(List.of("5")).test(context));

        var phantom = mock(org.bukkit.entity.Phantom.class);
        when(phantom.getSize()).thenReturn(2);
        var phantomContext = BehaviorContext.of(null, phantom, 1.0, -1, 0, "IDLE");
        assertTrue(extraFactory("mob-size").create(List.of("2")).test(phantomContext));
        assertFalse(extraFactory("mob-size").create(List.of("1")).test(ctx()));
    }

    @Test
    @DisplayName("IsWaterMob/IsZombie 使用 Bukkit 类型并失败关闭")
    void officialEntityTypeConditionsUseBukkitTypes() {
        var waterMob = mock(org.bukkit.entity.WaterMob.class);
        var zombie = mock(org.bukkit.entity.Zombie.class);
        var waterContext = BehaviorContext.of(null, waterMob, 1.0, -1, 0, "IDLE");
        var zombieContext = BehaviorContext.of(null, zombie, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("is-water-mob").create(List.of()).test(waterContext));
        assertFalse(extraFactory("is-water-mob").create(List.of()).test(zombieContext));
        assertTrue(extraFactory("is-zombie").create(List.of()).test(zombieContext));
        assertFalse(extraFactory("is-zombie").create(List.of()).test(waterContext));
        assertFalse(extraFactory("is-water-mob").create(List.of()).test(ctx()));
        assertFalse(extraFactory("is-zombie").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("EntityType 的 variant 约束匹配 Bukkit 实体变体")
    void entityTypeVariantConstraintMatchesPaperEntityVariant() {
        var cat = mock(org.bukkit.entity.Cat.class);
        when(cat.getType()).thenReturn(org.bukkit.entity.EntityType.CAT);
        var tabbyType = keyedVariant(org.bukkit.entity.Cat.Type.class, "tabby");
        var blackType = keyedVariant(org.bukkit.entity.Cat.Type.class, "black");
        when(cat.getCatType()).thenReturn(tabbyType);
        var catContext = BehaviorContext.of(null, cat, 1.0, -1, 0, "IDLE");
        var tabby = extraFactory("entity-type-is").create(List.of("CAT[variant=TABBY,BLACK]"));

        assertTrue(tabby.test(catContext));
        when(cat.getCatType()).thenReturn(blackType);
        assertTrue(tabby.test(catContext));
        var redType = keyedVariant(org.bukkit.entity.Cat.Type.class, "red");
        when(cat.getCatType()).thenReturn(redType);
        assertFalse(tabby.test(catContext));

        var wolf = mock(org.bukkit.entity.Wolf.class);
        when(wolf.getType()).thenReturn(org.bukkit.entity.EntityType.WOLF);
        when(wolf.getVariant()).thenReturn(keyedVariant(org.bukkit.entity.Wolf.Variant.class, "snowy"));
        var combined = extraFactory("entity-type-is").create(
                List.of("CAT[variant=TABBY]", "WOLF[variant=SNOWY]"));
        assertTrue(combined.test(BehaviorContext.of(null, wolf, 1.0, -1, 0, "IDLE")));
        assertFalse(combined.test(ctx()));
        var zombie = mock(org.bukkit.entity.Zombie.class);
        when(zombie.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        var inapplicableVariant = extraFactory("entity-type-is").create(List.of("ZOMBIE[variant=TABBY]"));
        assertFalse(inapplicableVariant.test(BehaviorContext.of(null, zombie, 1.0, -1, 0, "IDLE")));
    }

    @Test
    @DisplayName("EntityType 无 variant 时仍按 Bukkit 类型匹配")
    void entityTypeWithoutVariantMatchesBukkitType() {
        var cat = mock(org.bukkit.entity.Cat.class);
        when(cat.getType()).thenReturn(org.bukkit.entity.EntityType.CAT);
        assertTrue(extraFactory("entity-type-is").create(List.of("CAT"))
                .test(BehaviorContext.of(null, cat, 1.0, -1, 0, "IDLE")));
        assertFalse(extraFactory("entity-type-is").create(List.of("WOLF"))
                .test(BehaviorContext.of(null, cat, 1.0, -1, 0, "IDLE")));
    }

    @Test
    @DisplayName("Aerial 匹配目标实体的离地状态")
    void officialAerialConditionUsesTargetGroundState() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.isOnGround()).thenReturn(false);
        var context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("aerial").create(List.of()).test(context));
        when(target.isOnGround()).thenReturn(true);
        assertFalse(extraFactory("aerial").create(List.of()).test(context));
        assertFalse(extraFactory("aerial").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("IsEnderman 使用 Bukkit Enderman 类型并失败关闭")
    void officialEndermanConditionUsesBukkitType() {
        var enderman = mock(org.bukkit.entity.Enderman.class);
        var zombie = mock(org.bukkit.entity.Zombie.class);
        assertTrue(extraFactory("is-enderman").create(List.of()).test(
                BehaviorContext.of(null, enderman, 1.0, -1, 0, "IDLE")));
        assertFalse(extraFactory("is-enderman").create(List.of()).test(
                BehaviorContext.of(null, zombie, 1.0, -1, 0, "IDLE")));
        assertFalse(extraFactory("is-enderman").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("HasEnchantment 匹配装备槽附魔与等级范围")
    void officialHasEnchantmentConditionMatchesEquipment() {
        LivingEntity target = mock(LivingEntity.class);
        org.bukkit.inventory.EntityEquipment equipment = mock(org.bukkit.inventory.EntityEquipment.class);
        when(target.getEquipment()).thenReturn(equipment);
        when(equipment.getItemInMainHand()).thenReturn(null);
        when(equipment.getItemInOffHand()).thenReturn(null);
        when(equipment.getHelmet()).thenReturn(null);
        when(equipment.getChestplate()).thenReturn(null);
        when(equipment.getLeggings()).thenReturn(null);
        when(equipment.getBoots()).thenReturn(null);
        var context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        assertFalse(extraFactory("has-enchantment").create(List.of("SHARPNESS", ">2")).test(context));
        assertFalse(extraFactory("has-enchantment").create(List.of("ANY", "3")).test(context));
        assertFalse(extraFactory("has-enchantment").create(List.of("LOOTING", ">0")).test(context));
        assertFalse(extraFactory("has-enchantment").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("HasDialogOpen 读取宿主对话运行状态并失败关闭")
    void officialHasDialogOpenConditionUsesDialogueLookup() {
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(42);
        BehaviorContext context = BehaviorContext.of(instance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");
        Player cinematicPlayer = mock(Player.class);
        try {
            SkillExtras.dialogueLookup(id -> id == 42);
            assertTrue(extraFactory("has-dialog-open").create(List.of()).test(context));
            BehaviorContext playerContext = BehaviorContext.of(instance, cinematicPlayer,
                    1.0, -1, 0, "IDLE");
            var cinematic = extraFactory("is-in-cinematic").create(List.of());
            assertFalse(cinematic.test(playerContext));
            SkillExtras.cinematicLookup((id, player) -> id == 42 && player == cinematicPlayer);
            assertTrue(cinematic.test(playerContext));
            assertFalse(cinematic.test(BehaviorContext.of(instance, mock(Player.class),
                    1.0, -1, 0, "IDLE")));
            assertFalse(cinematic.test(BehaviorContext.of(instance, mock(LivingEntity.class),
                    1.0, -1, 0, "IDLE")));
            SkillExtras.dialogueLookup(id -> false);
            SkillExtras.cinematicLookup((id, player) -> false);
            assertFalse(extraFactory("has-dialog-open").create(List.of()).test(context));
            assertFalse(cinematic.test(playerContext));
            assertFalse(extraFactory("has-dialog-open").create(List.of()).test(ctx()));
        } finally {
            SkillExtras.dialogueLookup(null);
            SkillExtras.cinematicLookup(null);
        }
    }

    @Test
    @DisplayName("Color 匹配 Bukkit 颜色与实体变体")
    void officialColorConditionMatchesSupportedEntities() {
        var sheep = mock(org.bukkit.entity.Sheep.class);
        when(sheep.getColor()).thenReturn(org.bukkit.DyeColor.RED);
        var sheepContext = BehaviorContext.of(null, sheep, 1.0, -1, 0, "IDLE");
        assertTrue(extraFactory("color").create(List.of("RED")).test(sheepContext));
        assertFalse(extraFactory("color").create(List.of("BLUE")).test(sheepContext));

        var cat = mock(org.bukkit.entity.Cat.class);
        when(cat.getCatType()).thenReturn(null);
        var catContext = BehaviorContext.of(null, cat, 1.0, -1, 0, "IDLE");
        assertFalse(extraFactory("color").create(List.of("TABBY")).test(catContext));
        assertFalse(extraFactory("color").create(List.of("RED")).test(ctx()));
    }

    @Test
    @DisplayName("MaterialIsOnCooldown 读取目标玩家 Bukkit 冷却状态")
    void officialMaterialCooldownConditionReadsPlayerCooldown() {
        Player player = mock(Player.class);
        when(player.hasCooldown(org.bukkit.Material.ENDER_PEARL)).thenReturn(true);
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");
        assertTrue(extraFactory("material-on-cooldown").create(List.of()).test(context));
        assertTrue(extraFactory("material-on-cooldown").create(List.of("ENDER_PEARL")).test(context));
        assertFalse(extraFactory("material-on-cooldown").create(List.of("DIAMOND")).test(context));
        assertFalse(extraFactory("material-on-cooldown").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("PlayerKills 读取注入的实例累计玩家击杀数")
    void officialPlayerKillsConditionUsesInstanceCounter() {
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(7);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");
        var counter = new PlayerKillCount();
        try {
            SkillExtras.playerKillCount(counter);
            assertTrue(extraFactory("player-kills").create(List.of("0")).test(context));
            counter.record(7);
            counter.record(7);
            assertTrue(extraFactory("player-kills").create(List.of(">=2")).test(context));
            assertFalse(extraFactory("player-kills").create(List.of(">2")).test(context));
            assertFalse(extraFactory("player-kills").create(List.of(">=1")).test(ctx()));
        } finally {
            SkillExtras.playerKillCount(null);
        }
    }

    @Test
    @DisplayName("Absorption 读取 Bukkit 吸收生命值并支持比较符")
    void officialAbsorptionConditionReadsBukkitAmount() {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getAbsorptionAmount()).thenReturn(4.0);
        var context = BehaviorContext.of(null, entity, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("absorption").create(List.of(">=4")).test(context));
        assertTrue(extraFactory("absorption").create(List.of("=4")).test(context));
        assertFalse(extraFactory("absorption").create(List.of(">4")).test(context));
        assertFalse(extraFactory("absorption").create(List.of(">0")).test(ctx()));
    }

    @Test
    @DisplayName("Level 无管理实例时 fail-closed")
    void officialLevelConditionRequiresManagedInstance() {
        assertFalse(extraFactory("level").create(List.of(">=1")).test(ctx()));
        assertFalse(extraFactory("level").create(List.of("1to3")).test(ctx()));
    }

    @Test
    @DisplayName("IsFrozen 读取 Paper Entity.isFrozen")
    void officialIsFrozenConditionReadsEntityState() {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.isFrozen()).thenReturn(true);
        var context = BehaviorContext.of(null, entity, 1.0, -1, 0, "IDLE");
        assertTrue(extraFactory("is-frozen").create(List.of()).test(context));
        when(entity.isFrozen()).thenReturn(false);
        assertFalse(extraFactory("is-frozen").create(List.of()).test(context));
    }

    @Test
    @DisplayName("TargetIsFrozen 读取目标实体状态并在缺少目标时拒绝")
    void officialTargetIsFrozenConditionReadsTargetState() {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.isFrozen()).thenReturn(true);
        var context = BehaviorContext.of(null, entity, 1.0, -1, 0, "IDLE");
        assertTrue(extraFactory("target-is-frozen").create(List.of()).test(context));
        when(entity.isFrozen()).thenReturn(false);
        assertFalse(extraFactory("target-is-frozen").create(List.of()).test(context));
        assertFalse(extraFactory("target-is-frozen").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("angry and pollinated inspect only the documented entity states")
    void angryAndPollinatedConditionsCheckSupportedEntities() {
        var bee = mock(org.bukkit.entity.Bee.class);
        var wolf = mock(org.bukkit.entity.Wolf.class);
        var vex = mock(org.bukkit.entity.Vex.class);
        var piglin = mock(org.bukkit.entity.PigZombie.class);
        when(bee.getAnger()).thenReturn(40, 0);
        when(bee.hasNectar()).thenReturn(true, false);
        when(wolf.isAngry()).thenReturn(true, false);
        when(vex.isCharging()).thenReturn(true, false);
        var angry = extraFactory("angry").create(List.of());
        var pollinated = extraFactory("pollinated").create(List.of());

        assertTrue(angry.test(entityConditionContext(bee)));
        assertFalse(angry.test(entityConditionContext(bee)));
        assertTrue(angry.test(entityConditionContext(wolf)));
        assertFalse(angry.test(entityConditionContext(wolf)));
        assertTrue(angry.test(entityConditionContext(vex)));
        assertFalse(angry.test(entityConditionContext(vex)));
        assertFalse(angry.test(entityConditionContext(piglin)),
                "the Mythic condition excludes other anger-capable entities");
        assertFalse(angry.test(ctx()));

        assertTrue(pollinated.test(entityConditionContext(bee)));
        assertFalse(pollinated.test(entityConditionContext(bee)));
        assertFalse(pollinated.test(entityConditionContext(wolf)));
        assertFalse(pollinated.test(ctx()));
        assertThrows(IllegalArgumentException.class, () -> extraFactory("angry").create(List.of("true")));
        assertThrows(IllegalArgumentException.class, () -> extraFactory("pollinated").create(List.of("false")));
    }

    @Test
    @DisplayName("is-sheared supports Sheep, Bogged and Snow Golems for caster and target")
    void shearedConditionsSupportAllDocumentedEntities() {
        var sheep = mock(org.bukkit.entity.Sheep.class);
        var bogged = mock(org.bukkit.entity.Bogged.class);
        var snowman = mock(org.bukkit.entity.Snowman.class);
        var unsupported = mock(org.bukkit.entity.Zombie.class);
        var self = extraFactory("is-sheared").create(List.of());
        var target = extraFactory("target-is-sheared").create(List.of());

        when(sheep.isSheared()).thenReturn(true);
        assertTrue(self.test(entityConditionContext(sheep)));
        assertTrue(target.test(entityConditionContext(sheep)));
        when(sheep.isSheared()).thenReturn(false);
        assertFalse(self.test(entityConditionContext(sheep)));
        assertFalse(target.test(entityConditionContext(sheep)));

        when(bogged.isSheared()).thenReturn(true);
        assertTrue(self.test(entityConditionContext(bogged)));
        assertTrue(target.test(entityConditionContext(bogged)));
        when(bogged.isSheared()).thenReturn(false);
        assertFalse(self.test(entityConditionContext(bogged)));
        assertFalse(target.test(entityConditionContext(bogged)));

        when(snowman.isDerp()).thenReturn(true);
        assertTrue(self.test(entityConditionContext(snowman)));
        assertTrue(target.test(entityConditionContext(snowman)));
        when(snowman.isDerp()).thenReturn(false);
        assertFalse(self.test(entityConditionContext(snowman)));
        assertFalse(target.test(entityConditionContext(snowman)));

        assertFalse(self.test(entityConditionContext(unsupported)));
        assertFalse(target.test(entityConditionContext(unsupported)));
        assertFalse(self.test(ctx()));
        assertFalse(target.test(ctx()));
    }

    @Test
    @DisplayName("in-combat requires a managed or Bukkit mob with a live target")
    void inCombatRequiresAnActiveTarget() {
        var condition = extraFactory("in-combat").create(List.of());
        var mob = mock(org.bukkit.entity.Mob.class);
        var target = mock(LivingEntity.class);
        when(target.isValid()).thenReturn(true);
        when(target.isDead()).thenReturn(false);
        when(mob.getTarget()).thenReturn(target);
        assertTrue(condition.test(entityConditionContext(mob)));

        when(mob.getTarget()).thenReturn(null);
        assertFalse(condition.test(entityConditionContext(mob)));
        when(mob.getTarget()).thenReturn(target);
        when(target.isDead()).thenReturn(true);
        assertFalse(condition.test(entityConditionContext(mob)));
        when(target.isDead()).thenReturn(false);
        when(target.isValid()).thenReturn(false);
        assertFalse(condition.test(entityConditionContext(mob)));

        var player = mock(Player.class);
        assertFalse(condition.test(entityConditionContext(player)));
        assertFalse(condition.test(ctx()));
    }

    @Test
    @DisplayName("in-combat reads the managed AI controller for its own mob")
    void inCombatUsesManagedControllerState() {
        var instance = mock(dev.helstera.runtime.instance.ModelInstanceImpl.class);
        var mob = mock(org.bukkit.entity.Mob.class);
        var pathfinder = mock(com.destroystokyo.paper.entity.Pathfinder.class);
        var location = mock(Location.class);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(mob));
        when(instance.entity()).thenReturn(mob);
        when(instance.location()).thenReturn(location);
        when(instance.isValid()).thenReturn(true);
        when(mob.isValid()).thenReturn(true);
        when(mob.getLocation()).thenReturn(location);
        when(mob.getPathfinder()).thenReturn(pathfinder);
        var profile = new dev.helstera.ai.AiProfile("in-combat-condition");
        profile.canChase = false;
        profile.canAttack = false;
        profile.canPatrol = false;
        profile.goals.add("0 clear");
        profile.targets.add("0 clear");
        var controller = new dev.helstera.ai.AiController(instance, profile, null,
                mock(dev.helstera.api.event.HelsteraEventBus.class));
        instance.aiController = controller;
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");
        var target = mock(LivingEntity.class);
        when(target.isValid()).thenReturn(true);
        when(target.isDead()).thenReturn(false);

        var condition = extraFactory("in-combat").create(List.of());
        assertFalse(condition.test(context));
        controller.setCombatTarget(target);
        assertTrue(condition.test(context));
        controller.clearCombatTarget();
        assertFalse(condition.test(context));
    }

    private static BehaviorContext entityConditionContext(LivingEntity entity) {
        return BehaviorContext.continueExecution(null, entity, 1.0, -1, 0, "IDLE",
                null, null, List.of(entity), null, null);
    }

    @Test
    @DisplayName("MythicMobType 无管理实例时 fail-closed")
    void officialMythicMobTypeConditionRequiresManagedInstance() {
        assertFalse(extraFactory("mythic-mob-type").create(List.of("example/emberling")).test(ctx()));
    }

    @Test
    @DisplayName("LunarPhase 使用世界 fullTime 的八日周期")
    void officialLunarPhaseConditionUsesWorldFullTime() {
        World world = mock(World.class);
        when(world.getFullTime()).thenReturn(9L * 24_000L);
        Location location = new Location(world, 0, 64, 0);
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(location);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("lunar-phase").create(List.of("1")).test(context));
        assertTrue(extraFactory("lunar-phase").create(List.of("0,1,2")).test(context));
        assertFalse(extraFactory("lunar-phase").create(List.of("2")).test(context));
        assertFalse(extraFactory("lunar-phase").create(List.of("1,invalid")).test(context));
        assertFalse(extraFactory("lunar-phase").create(List.of("1,")).test(context));
        assertFalse(extraFactory("lunar-phase").create(List.of("8")).test(context));
        assertFalse(extraFactory("lunar-phase").create(List.of("")).test(context));
    }

    @Test
    @DisplayName("Chance 仅接受有限的 0..100 百分数")
    void randomChanceRejectsInvalidPercentAtBindTime() {
        var factory = extraFactory("random-chance");
        assertFalse(factory.create(List.of("0")).test(ctx()));
        assertTrue(factory.create(List.of("100")).test(ctx()));
        for (String invalid : List.of("NaN", "Infinity", "-Infinity", "-1", "101", "invalid")) {
            assertThrows(IllegalArgumentException.class, () -> factory.create(List.of(invalid)), invalid);
        }
    }

    @Test
    @DisplayName("LastSignal 读取实例最近收到的信号")
    void officialLastSignalConditionReadsManagedInstanceState() {
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(91);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");
        try {
            SkillExtras.rememberSignal(91, "phase2");
            assertTrue(extraFactory("last-signal").create(List.of("phase2")).test(context));
            assertFalse(extraFactory("last-signal").create(List.of("phase1")).test(context));
            assertFalse(extraFactory("last-signal").create(List.of("phase2")).test(ctx()));
        } finally {
            SkillExtras.forget(91);
        }
    }

    @Test
    @DisplayName("Region/NotInRegion 使用外部查询并在未知时 fail-closed")
    void officialRegionConditionsUseExternalLookup() {
        World world = mock(World.class);
        Location casterLocation = new Location(world, 1, 64, 1);
        Location targetLocation = new Location(world, 8, 64, 8);
        LivingEntity caster = mock(LivingEntity.class);
        LivingEntity target = mock(LivingEntity.class);
        when(caster.getLocation()).thenReturn(casterLocation);
        when(target.getLocation()).thenReturn(targetLocation);
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(casterLocation);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));

        var region = extraFactory("region").create(List.of("arena"));
        var outside = extraFactory("not-in-region").create(List.of("arena"));
        try {
            SkillExtras.regionLookup((name, location) -> name.equals("arena")
                    && location.equals(casterLocation));
            assertTrue(region.test(BehaviorContext.of(instance, null, 1.0, -1, 0, "IDLE")));
            assertFalse(outside.test(BehaviorContext.of(instance, null, 1.0, -1, 0, "IDLE")));

            SkillExtras.regionLookup((name, location) -> location == targetLocation);
            var targetContext = BehaviorContext.of(instance, target, 1.0, -1, 0, "IDLE");
            assertTrue(region.test(targetContext));
            assertFalse(outside.test(targetContext));

            SkillExtras.regionLookup((name, location) -> false);
            assertFalse(region.test(targetContext));
            assertTrue(outside.test(targetContext));

            SkillExtras.regionLookup((name, location) -> null);
            assertFalse(region.test(targetContext));
            assertFalse(outside.test(targetContext));
            assertThrows(IllegalArgumentException.class,
                    () -> extraFactory("region").create(List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> extraFactory("not-in-region").create(List.of("arena", "other")));
        } finally {
            SkillExtras.regionLookup(null);
        }
    }

    @Test
    @DisplayName("ProjectileHasEnded 读取命中事件记录")
    void officialProjectileHasEndedConditionReadsHitState() {
        var projectile = mock(org.bukkit.entity.Projectile.class);
        when(projectile.isValid()).thenReturn(true);
        when(projectile.isDead()).thenReturn(false);
        when(projectile.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        var context = BehaviorContext.continueExecution(null, (LivingEntity) null,
                1.0, -1, 0, "on-projectile-hit", null, projectile, List.of(), null, null);
        try {
            assertFalse(extraFactory("projectile-has-ended").create(List.of()).test(context));
            SkillExtras.rememberProjectileEnded(projectile);
            assertTrue(extraFactory("projectile-has-ended").create(List.of()).test(context));
        } finally {
            SkillExtras.resetRuntimeHooks();
        }
    }

    @Test
    @DisplayName("HasPotionEffect 匹配 Bukkit 效果、等级和持续时间范围")
    void officialHasPotionEffectConditionMatchesDetails() {
        LivingEntity entity = mock(LivingEntity.class);
        var effect = mock(org.bukkit.potion.PotionEffect.class);
        when(effect.getType()).thenReturn(null);
        when(effect.getAmplifier()).thenReturn(2);
        when(effect.getDuration()).thenReturn(120);
        when(entity.getActivePotionEffects()).thenReturn(List.of(effect));
        var context = BehaviorContext.of(null, entity, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("has-potion-effect").create(List.of("ANY", ">=2"))
                .test(context));
        assertTrue(extraFactory("has-potion-effect").create(List.of("ANY", "2", "100to130"))
                .test(context));
        assertFalse(extraFactory("has-potion-effect").create(List.of("SPEED", ">3"))
                .test(context));
    }

    @Test
    @DisplayName("内部 PotionEffect 栈条件将 amplifier 转为 1-based 范围")
    void internalPotionEffectStacksMatchOneBasedAmplifier() {
        LivingEntity entity = mock(LivingEntity.class);
        var effect = mock(org.bukkit.potion.PotionEffect.class);
        when(effect.getType()).thenReturn(null);
        when(effect.getAmplifier()).thenReturn(2);
        when(entity.getActivePotionEffects()).thenReturn(List.of(effect));
        var context = BehaviorContext.of(null, entity, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("has-aura-stacks").create(List.of("ANY", ">=3")).test(context));
        assertTrue(extraFactory("has-aura-stacks").create(List.of("ANY", "3")).test(context));
        assertFalse(extraFactory("has-aura-stacks").create(List.of("ANY", ">3")).test(context));
    }

    @Test
    @DisplayName("TriggerItemType 匹配 trigger Item 的 Bukkit 材质")
    void officialTriggerItemTypeConditionReadsTriggerItem() {
        var item = mock(org.bukkit.entity.Item.class);
        var stack = mock(org.bukkit.inventory.ItemStack.class);
        when(item.getItemStack()).thenReturn(stack);
        when(stack.getType()).thenReturn(org.bukkit.Material.STONE);
        var context = BehaviorContext.continueExecution(null, (LivingEntity) null,
                1.0, -1, 0, "on-item-pickup", null, item, List.of(), null, null);
        assertTrue(extraFactory("trigger-item-type").create(List.of("STONE,DIRT")).test(context));
        assertFalse(extraFactory("trigger-item-type").create(List.of("DIAMOND")).test(context));
        assertFalse(extraFactory("trigger-item-type").create(List.of("STONE")).test(ctx()));
    }

    @Test
    @DisplayName("TriggerBlockType 匹配 block trigger 的 origin 方块")
    void officialTriggerBlockTypeConditionReadsOriginBlock() {
        World world = mock(World.class);
        Block block = mock(Block.class);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(block.getType()).thenReturn(org.bukkit.Material.STONE);
        Location origin = new Location(world, 0, 0, 0);
        LivingEntity target = mock(LivingEntity.class);
        var context = BehaviorContext.continueExecution(null, target, 1.0, -1, 0,
                "on-block-break", null, target, List.of(target), origin, null);
        assertTrue(extraFactory("trigger-block-type").create(List.of("STONE,DIRT")).test(context));
        assertFalse(extraFactory("trigger-block-type").create(List.of("DIAMOND")).test(context));
        var wrongState = BehaviorContext.continueExecution(null, target, 1.0, -1, 0,
                "on-damage", null, target, List.of(target), origin, null);
        assertFalse(extraFactory("trigger-block-type").create(List.of("STONE")).test(wrongState));
    }

    @Test
    @DisplayName("DamageAmount 读取 damage event 的 final damage，并在无事件时失败关闭")
    void officialDamageAmountConditionUsesDamageEvent() {
        LivingEntity target = mock(LivingEntity.class);
        var event = mock(org.bukkit.event.entity.EntityDamageEvent.class);
        when(event.getFinalDamage()).thenReturn(5.0);
        var context = BehaviorContext.continueExecution(null, target, 1.0, -1, 0,
                "on-damage", null, target, List.of(target), null, null, event);

        assertTrue(extraFactory("damage-amount").create(List.of(">=5")).test(context));
        assertFalse(extraFactory("damage-amount").create(List.of(">5")).test(context));
        assertTrue(extraFactory("damage-amount").create(List.of()).test(context));
        assertFalse(extraFactory("damage-amount").create(List.of(">0")).test(ctx()));
    }

    @Test
    @DisplayName("Owner 匹配 caster 的 Bukkit 或 Helstera owner，不回退到 target")
    void officialOwnerConditionMatchesCasterOwner() {
        var instance = mock(ModelInstance.class);
        var caster = mock(org.bukkit.entity.Tameable.class);
        var owner = mock(Player.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        var id = java.util.UUID.randomUUID();
        when(owner.getUniqueId()).thenReturn(id);
        when(caster.getOwner()).thenReturn(owner);
        when(caster.getOwnerUniqueId()).thenReturn(id);
        var context = BehaviorContext.of(instance, owner, 1.0, 0, 0, "IDLE");

        assertTrue(extraFactory("is-owner").create(List.of()).test(context));
        assertFalse(extraFactory("is-owner").create(List.of()).test(
                BehaviorContext.of(instance, mock(Player.class), 1.0, 0, 0, "IDLE")));
    }

    @Test
    @DisplayName("BlockTypeInRadius 统计球形范围内的精确 Bukkit 材质")
    void officialBlockTypeInRadiusConditionCountsBlocks() {
        World world = mock(World.class);
        Block block = mock(Block.class);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(1);
        when(world.getBlockAt(0, 0, 0)).thenReturn(block);
        when(block.getType()).thenReturn(org.bukkit.Material.STONE);
        var location = new Location(world, 0.5, 0.5, 0.5);
        var context = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "IDLE", null, null, List.of(), location);

        assertTrue(extraFactory("block-type-in-radius").create(
                List.of("STONE", ">0", "0")).test(context));
        assertTrue(extraFactory("block-type-in-radius").create(
                List.of("ST*", ">0", "0")).test(context));
        assertFalse(extraFactory("block-type-in-radius").create(
                List.of("DIRT", ">0", "0")).test(context));
        assertFalse(extraFactory("block-type-in-radius").create(
                List.of("#minecraft:missing_tag", ">0", "1")).test(context));
    }

    @Test
    @DisplayName("BlockType 与 OnBlock 支持 Bukkit block tags 并拒绝未知 tag")
    void officialBlockConditionsResolveTagsAndWildcards() throws ReflectiveOperationException {
        World world = mock(World.class);
        Block block = mock(Block.class);
        Location location = new Location(world, 0, 64, 0);
        when(world.getBlockAt(location)).thenReturn(block);
        when(world.getBlockAt(new Location(world, 0, 63, 0))).thenReturn(block);
        when(block.getType()).thenReturn(org.bukkit.Material.STONE);
        LivingEntity target = mock(LivingEntity.class);
        when(target.getLocation()).thenReturn(location);
        var context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("block-type-is").create(List.of("ST*")).test(context));
        assertTrue(extraFactory("on-block").create(List.of("ST*")).test(context));

        var serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object originalServer = serverField.get(null);
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        serverField.set(null, server);
        try {
            org.bukkit.Tag<org.bukkit.Material> tag = new org.bukkit.Tag<>() {
                @Override public org.bukkit.NamespacedKey getKey() {
                    return org.bukkit.NamespacedKey.minecraft("logs");
                }
                @Override public boolean isTagged(org.bukkit.Material material) {
                    return material == org.bukkit.Material.STONE;
                }
                @Override public Set<org.bukkit.Material> getValues() {
                    return Set.of(org.bukkit.Material.STONE);
                }
            };
            when(server.getTag(org.bukkit.Tag.REGISTRY_BLOCKS,
                    org.bukkit.NamespacedKey.minecraft("logs"), org.bukkit.Material.class)).thenReturn(tag);
            assertTrue(extraFactory("block-type-is").create(
                    List.of("#minecraft:logs")).test(context));
            assertTrue(extraFactory("on-block").create(
                    List.of("#minecraft:logs")).test(context));
            assertFalse(extraFactory("block-type-is").create(
                    List.of("#minecraft:missing_tag")).test(context));
        } finally {
            serverField.set(null, originalServer);
        }
    }

    @Test
    @DisplayName("Children 无 caster 时失败关闭并支持默认/范围工厂")
    void officialChildrenConditionRequiresCaster() {
        assertFalse(extraFactory("children").create(List.of()).test(ctx()));
        assertFalse(extraFactory("children").create(List.of(">=1")).test(ctx()));
        assertNotNull(extraFactory("children").create(List.of("1")));
    }

    @Test
    @DisplayName("Inside/Outside 无位置上下文时失败关闭")
    void officialInsideOutsideConditionsFailClosed() {
        assertFalse(extraFactory("inside").create(List.of()).test(ctx()));
        assertFalse(extraFactory("outside").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("FallSpeed 匹配向下速度正值，不把上升速度当下落")
    void officialFallSpeedConditionUsesDownwardVelocity() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getVelocity()).thenReturn(new org.bukkit.util.Vector(0, -0.5, 0));
        var context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");
        assertTrue(extraFactory("fall-speed").create(List.of("0.4to0.6")).test(context));
        when(target.getVelocity()).thenReturn(new org.bukkit.util.Vector(0, 0.5, 0));
        assertTrue(extraFactory("fall-speed").create(List.of("0")).test(context));
        assertFalse(extraFactory("fall-speed").create(List.of(">0.1")).test(context));
    }

    @Test
    @DisplayName("EnchantingLevel/Experience 只读取显式目标玩家")
    void officialEnchantingConditionsUsePlayerTarget() {
        Player player = mock(Player.class);
        when(player.getLevel()).thenReturn(7);
        when(player.getExp()).thenReturn(0.25f);
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("enchanting-level").create(List.of(">=7")).test(context));
        assertTrue(extraFactory("enchanting-experience").create(List.of(">=0.2")).test(context));
        assertFalse(extraFactory("enchanting-level").create(List.of(">8")).test(context));
        assertFalse(extraFactory("enchanting-experience").create(List.of(">0.3")).test(context));
        assertFalse(extraFactory("enchanting-level").create(List.of(">=0")).test(ctx()));
    }

    @Test
    @DisplayName("DirectionalVelocity 支持具名轴、绝对值和可选分量")
    void officialDirectionalVelocityConditionUsesNamedAxes() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getVelocity()).thenReturn(new org.bukkit.util.Vector(0.25, -0.5, 0.75));
        var location = mock(org.bukkit.Location.class);
        when(location.getDirection()).thenReturn(new org.bukkit.util.Vector(0, 0, 1));
        when(target.getLocation()).thenReturn(location);
        var context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("directional-velocity").create(
                List.of("x=>=0.2", "y=<0", "z=>=0.7")).test(context));
        assertTrue(extraFactory("directional-velocity").create(
                List.of("y=>=0.4", "absy=true")).test(context));
        assertFalse(extraFactory("directional-velocity").create(
                List.of("x=>=0.3")).test(context));
    }

    @Test
    @DisplayName("FieldOfView 使用 caster 朝向、总角度和 rotation")
    void officialFieldOfViewConditionUsesViewCone() {
        var instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        LivingEntity target = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.getLocation()).thenReturn(new org.bukkit.Location(null, 0, 0, 0, 0, 0));
        when(target.getLocation()).thenReturn(new org.bukkit.Location(null, 0, 0, 10));
        var context = BehaviorContext.of(instance, target, 1.0, 10, 0, "IDLE");

        assertTrue(extraFactory("field-of-view").create(List.of("90", "0")).test(context));
        when(target.getLocation()).thenReturn(new org.bukkit.Location(null, 10, 0, 0));
        assertFalse(extraFactory("field-of-view").create(List.of("90", "0")).test(context));
        assertTrue(extraFactory("field-of-view").create(List.of("90", "90")).test(context));
    }

    @Test
    @DisplayName("DistanceFromLocation 只读取显式 target 的位置")
    void officialDistanceFromLocationConditionUsesTargetPosition() {
        var world = mock(org.bukkit.World.class);
        LivingEntity target = mock(LivingEntity.class);
        when(target.getLocation()).thenReturn(new org.bukkit.Location(world, 3, 4, 0));
        var context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("distance-from-location").create(
                List.of("x=0", "y=0", "z=0", "distance=5")).test(context));
        assertTrue(extraFactory("distance-from-location").create(
                List.of("x=0", "y=0", "z=0", "distance=<6")).test(context));
        assertFalse(extraFactory("distance-from-location").create(
                List.of("x=0", "y=0", "z=0", "distance=<4")).test(context));
        assertFalse(extraFactory("distance-from-location").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("XDiff/YDiff/ZDiff 匹配目标与 caster 的坐标差")
    void officialCoordinateDifferenceConditionsUseCasterAndTargetLocations() {
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity target = mock(LivingEntity.class);
        World world = mock(World.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(new Location(world, 10, 20, 30));
        when(target.getLocation()).thenReturn(new Location(world, 13, 18, 31));
        var context = BehaviorContext.of(instance, target, 1.0, 3, 0, "IDLE");
        assertTrue(extraFactory("x-diff").create(List.of("2to4")).test(context));
        assertTrue(extraFactory("y-diff").create(List.of("-3to-1")).test(context));
        assertTrue(extraFactory("z-diff").create(List.of("1")).test(context));
        assertFalse(extraFactory("x-diff").create(List.of(">4")).test(context));
        assertFalse(extraFactory("x-diff").create(List.of("3")).test(ctx()));
    }

    @Test
    @DisplayName("OriginLocation 匹配上下文 origin 的方块或精确坐标")
    void officialOriginLocationConditionUsesContextOrigin() {
        World world = mock(World.class);
        Location origin = new Location(world, 10.25, 20.5, 30.75);
        var context = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "IDLE", null, null, List.of(), origin);
        assertTrue(extraFactory("origin-location").create(List.of("x=10", "y=20", "z=30")).test(context));
        assertTrue(extraFactory("origin-location").create(List.of("loc=10.25,20.5,30.75", "exact=true"))
                .test(context));
        assertFalse(extraFactory("origin-location").create(List.of("loc=11,20,30")).test(context));
        assertFalse(extraFactory("origin-location").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("Cuboid/InBounds 使用目标位置、相对坐标和球/柱体几何")
    void officialBoundsConditionsUseGeometry() {
        World world = mock(World.class);
        Location origin = new Location(world, 0, 64, 0);
        LivingEntity target = mock(LivingEntity.class);
        when(target.getLocation()).thenReturn(new Location(world, 2, 65, 2));
        var context = BehaviorContext.continueExecution(null, target, 1.0, 1.0, 0,
                "IDLE", null, null, List.of(target), origin, null);

        assertTrue(extraFactory("cuboid").create(List.of("0,64,0", "4,70,4")).test(context));
        assertTrue(extraFactory("cuboid").create(List.of("-2,0,-2", "2,2,2", "true")).test(context));
        assertFalse(extraFactory("cuboid").create(List.of("10,64,10", "12,70,12")).test(context));
        assertTrue(extraFactory("in-bounds").create(List.of(
                "shape=sphere", "location=0,64,0", "radius=5")).test(context));
        assertTrue(extraFactory("in-bounds").create(List.of(
                "shape=cylinder", "location=0,64,0", "radius=3", "height=2")).test(context));
        assertFalse(extraFactory("in-bounds").create(List.of(
                "shape=unknown", "location=0,64,0", "radius=5")).test(context));
    }

    @Test
    @DisplayName("SpawnReason 匹配目标实体的 Bukkit 生成原因并失败关闭")
    void officialSpawnReasonConditionUsesEntitySpawnReason() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getEntitySpawnReason())
                .thenReturn(org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.RAID);
        var context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("spawn-reason").create(List.of("RAID")).test(context));
        assertTrue(extraFactory("spawn-reason").create(List.of("r=raid")).test(context));
        assertFalse(extraFactory("spawn-reason").create(List.of("NATURAL")).test(context));
        assertFalse(extraFactory("spawn-reason").create(List.of()).test(context));
        assertFalse(extraFactory("spawn-reason").create(List.of("not-a-reason")).test(context));
        assertFalse(extraFactory("spawn-reason").create(List.of("RAID")).test(ctx()));
    }

    @Test
    @DisplayName("official world-time and light-source conditions fail closed without a context")
    void officialWorldTimeAndLightConditionsAreRegistered() {
        assertFalse(extraFactory("world-time").create(List.of("0to6000")).test(ctx()));
        assertFalse(extraFactory("sky-light-level").create(List.of(">=0")).test(ctx()));
        assertFalse(extraFactory("light-level-from-blocks").create(List.of(">=0")).test(ctx()));
    }

    @Test
    @DisplayName("official block and biome type conditions fail closed without a context")
    void officialBlockAndBiomeConditionsAreRegistered() {
        assertFalse(extraFactory("block-type-is").create(List.of("STONE")).test(ctx()));
        assertFalse(extraFactory("biome-type-is").create(List.of("plains", "true")).test(ctx()));
        assertFalse(extraFactory("biome-type-is").create(List.of("plains", "false")).test(ctx()));
        assertFalse(extraFactory("biome-is").create(List.of("plains", "true")).test(ctx()));
    }

    @Test
    @DisplayName("HasCurrency checks the online target balance and fails closed without Vault")
    void hasCurrencyUsesOptionalEconomyBalance() {
        Player player = mock(Player.class);
        BehaviorContext context = BehaviorContext.continueExecution(null, player, 1.0, -1, 0,
                "IDLE", null, null, List.of(player), null, null);
        var condition = extraFactory("has-currency").create(List.of(">=1000"));

        SkillExtras.currencyBalance(null);
        assertFalse(condition.test(context));
        SkillExtras.currencyBalance(ignored -> OptionalDouble.of(1500));
        assertTrue(condition.test(context));
        assertFalse(extraFactory("has-currency").create(List.of("<1000")).test(context));
        SkillExtras.currencyBalance(ignored -> OptionalDouble.empty());
        assertFalse(condition.test(context));
        SkillExtras.currencyBalance(null);
    }

    @Test
    @DisplayName("BiomeType non-exact mode matches documented biome categories")
    void biomeTypeConditionMatchesCategoriesAndAlternatives() {
        World world = mock(World.class);
        Block block = mock(Block.class);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(block.getBiome()).thenReturn(org.bukkit.block.Biome.SUNFLOWER_PLAINS);
        Location location = new Location(world, 0, 64, 0);
        BehaviorContext context = BehaviorContext.continueExecution(null, null, 1.0, 1.0, 0,
                "IDLE", null, null, List.of(), location, null);

        assertTrue(extraFactory("biome-type-is").create(List.of("plains", "false")).test(context));
        assertTrue(extraFactory("biome-type-is").create(List.of("forest,plains", "false")).test(context));
        assertFalse(extraFactory("biome-type-is").create(List.of("forest", "false")).test(context));
        assertTrue(extraFactory("biome-type-is").create(List.of("sunflower_plains", "true"))
                .test(context));
        assertFalse(extraFactory("biome-type-is").create(List.of("plains", "false")).test(ctx()));
    }

    @Test
    @DisplayName("Biome honors namespaced exact matches, prefix matches, and comma-separated alternatives")
    void biomeConditionUsesNamespacedKeysAndExactFlag() {
        World world = mock(World.class);
        Block block = mock(Block.class);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(block.getBiome()).thenReturn(org.bukkit.block.Biome.SUNFLOWER_PLAINS);
        Location location = new Location(world, 0, 64, 0);
        BehaviorContext context = BehaviorContext.continueExecution(null, null, 1.0, 1.0, 0,
                "IDLE", null, null, List.of(), location, null);

        assertTrue(extraFactory("biome-is").create(
                List.of("minecraft:sunflower_plains", "true")).test(context));
        assertTrue(extraFactory("biome-is").create(List.of("plains", "false")).test(context));
        assertTrue(extraFactory("biome-is").create(
                List.of("minecraft:desert,sunflower_plains", "true")).test(context));
        assertFalse(extraFactory("biome-is").create(List.of("plains", "true")).test(context));
        assertFalse(extraFactory("biome-is").create(List.of("example:plains", "false")).test(context));
    }

    @Test
    @DisplayName("official server and survival conditions are registered and fail closed without a target")
    void officialServerAndSurvivalConditionsAreRegistered() {
        assertFalse(extraFactory("is-in-survival-mode").create(List.of()).test(ctx()));
        assertNotNull(extraFactory("server-is-paper").create(List.of()));
        assertFalse(extraFactory("plugin").create(List.of("__missing_plugin__")).test(ctx()));
    }

    @Test
    @DisplayName("survival-mode condition accepts Survival and rejects other player modes")
    void survivalModeConditionMatchesExplicitPlayerMode() {
        Player player = mock(Player.class);
        BehaviorContext context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");
        var condition = extraFactory("is-in-survival-mode").create(List.of());

        when(player.getGameMode()).thenReturn(org.bukkit.GameMode.ADVENTURE);
        assertFalse(condition.test(context));
        when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        assertTrue(condition.test(context));
        when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SPECTATOR);
        assertFalse(condition.test(context));
    }

    @Test
    @DisplayName("spyglass condition reads a target player's active item")
    void officialSpyglassConditionReadsActiveItem() {
        Player player = mock(Player.class);
        org.bukkit.inventory.ItemStack item = mock(org.bukkit.inventory.ItemStack.class);
        when(player.hasActiveItem()).thenReturn(true);
        when(player.getActiveItem()).thenReturn(item);
        when(item.getType()).thenReturn(org.bukkit.Material.SPYGLASS);
        BehaviorContext context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");
        assertTrue(extraFactory("is-using-spyglass").create(List.of()).test(context));
        when(item.getType()).thenReturn(org.bukkit.Material.SHIELD);
        assertFalse(extraFactory("is-using-spyglass").create(List.of()).test(context));
    }

    @Test
    @DisplayName("execution audience defaults to every player and honors an explicit player set")
    void executionAudienceConditionUsesExplicitAudienceWhenPresent() {
        Player member = mock(Player.class);
        Player outsider = mock(Player.class);
        LivingEntity zombie = mock(org.bukkit.entity.Zombie.class);
        when(member.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(outsider.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        var condition = extraFactory("is-in-audience").create(List.of());

        assertTrue(condition.test(BehaviorContext.of(null, outsider, 1.0, -1, 0, "IDLE")));
        var audience = BehaviorContext.of(null, member, 1.0, -1, 0, "IDLE")
                .withExecutionAudience(List.of(member));
        assertTrue(condition.test(audience));
        assertFalse(condition.test(BehaviorContext.of(null, outsider, 1.0, -1, 0, "IDLE")
                .withExecutionAudience(List.of(member))));
        assertFalse(condition.test(BehaviorContext.of(null, member, 1.0, -1, 0, "IDLE")
                .withExecutionAudience(List.of())));
        assertFalse(condition.test(BehaviorContext.of(null, zombie, 1.0, -1, 0, "IDLE")));
        assertThrows(IllegalArgumentException.class,
                () -> extraFactory("is-in-audience").create(List.of("unexpected")));
    }

    @Test
    @DisplayName("is-mythic-mob recognizes the managed caster and rejects an empty context")
    void officialMythicMobConditionUsesManagedInstance() {
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        BehaviorContext managed = BehaviorContext.of(instance, caster, 1.0, -1, 0, "IDLE");

        assertTrue(extraFactory("is-mythic-mob").create(List.of()).test(managed));
        assertFalse(extraFactory("is-mythic-mob").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("mobs-near-origin fails closed without an execution origin")
    void officialMobsNearOriginConditionIsRegistered() {
        assertFalse(extraFactory("mobs-near-origin").create(List.of(">=1", "5", "ZOMBIE")).test(ctx()));
    }

    @Test
    @DisplayName("official server version conditions are registered")
    void officialServerVersionConditionsAreRegistered() {
        assertNotNull(extraFactory("server-version").create(List.of("1.21.11")));
        assertNotNull(extraFactory("server-version-after-or-equal").create(List.of("1.21.1", "true")));
        assertNotNull(extraFactory("server-version-before").create(List.of("1.22", "true")));
    }

    @Test
    @DisplayName("official NMS version condition maps supported Minecraft versions and fails closed")
    void officialServerNmsVersionConditionMatchesKnownVersions() {
        var server = mock(org.bukkit.Server.class);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getServer).thenReturn(server);
            bukkit.when(org.bukkit.Bukkit::getMinecraftVersion).thenReturn("1.21.1");
            assertTrue(extraFactory("server-nms-version").create(List.of("v1_21_R1")).test(ctx()));
            assertTrue(extraFactory("server-nms-version").create(List.of("1_21_R1")).test(ctx()));
            assertFalse(extraFactory("server-nms-version").create(List.of("v1_21_R3")).test(ctx()));

            bukkit.when(org.bukkit.Bukkit::getMinecraftVersion).thenReturn("1.21.4");
            assertTrue(extraFactory("server-nms-version").create(List.of("v1_21_R3")).test(ctx()));

            bukkit.when(org.bukkit.Bukkit::getMinecraftVersion).thenReturn("1.21.11");
            assertTrue(extraFactory("server-nms-version").create(List.of("v1_21_R7")).test(ctx()));

            bukkit.when(org.bukkit.Bukkit::getMinecraftVersion).thenReturn("99.99");
            assertFalse(extraFactory("server-nms-version").create(List.of("v1_21_R7")).test(ctx()));
        }
        assertNotNull(extraFactory("server-nms-version").create(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> extraFactory("server-nms-version").create(List.of("1.21.1")));
    }

    @Test
    @DisplayName("official LocalDifficulty condition compares the target or caster location and fails closed")
    void officialLocalDifficultyConditionUsesLocationAndBoundaries() {
        World world = mock(World.class);
        Location targetLocation = new Location(world, 10, 64, 10);
        LivingEntity target = mock(LivingEntity.class);
        when(target.getLocation()).thenReturn(targetLocation);
        Location casterLocation = new Location(world, 0, 64, 0);
        var targetContext = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");
        var casterContext = BehaviorContext.continueExecution(null, (LivingEntity) null,
                1.0, -1, 0, "IDLE", null, null, List.of(), casterLocation, null);

        try {
            SkillExtras.localDifficultyLookup(location -> location == targetLocation ? 2.0 : 1.0);
            assertTrue(extraFactory("local-difficulty").create(List.of(">=2")).test(targetContext));
            assertFalse(extraFactory("local-difficulty").create(List.of(">2")).test(targetContext));
            assertTrue(extraFactory("local-difficulty").create(List.of("1to2")).test(targetContext));
            assertFalse(extraFactory("local-difficulty").create(List.of("3to4")).test(targetContext));
            assertTrue(extraFactory("local-difficulty").create(List.of("d=>=2")).test(targetContext));
            assertTrue(extraFactory("local-difficulty").create(List.of("diff=>0")).test(casterContext));
            assertFalse(extraFactory("local-difficulty").create(List.of(">0")).test(ctx()));

            assertThrows(IllegalArgumentException.class,
                    () -> extraFactory("local-difficulty").create(List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> extraFactory("local-difficulty").create(List.of("NaN")));
            assertThrows(IllegalArgumentException.class,
                    () -> extraFactory("local-difficulty").create(List.of("2to1")));
            assertThrows(IllegalArgumentException.class,
                    () -> extraFactory("local-difficulty").create(List.of("unknown=>0")));
            assertThrows(IllegalArgumentException.class,
                    () -> extraFactory("local-difficulty").create(List.of("d=>0", "diff=>1")));
        } finally {
            SkillExtras.resetRuntimeHooks();
        }
    }

    @Test
    @DisplayName("raider patrol leader condition matches the live Bukkit flag and fails closed without a raider")
    void officialRaiderPatrolLeaderConditionIsRegistered() {
        var condition = extraFactory("is-raider-patrol-leader").create(List.of());
        assertFalse(condition.test(ctx()));

        var raider = mock(org.bukkit.entity.Raider.class);
        var context = BehaviorContext.of(null, raider, 1.0, -1, 0, "IDLE");
        when(raider.isPatrolLeader()).thenReturn(true, false);
        assertTrue(condition.test(context));
        assertFalse(condition.test(context));
    }

    @Test
    @DisplayName("DamageTag checks scoped damage tags and fails closed without a tag context")
    void damageTagRequiresMatchingDamageContext() {
        var hasTag = extraFactory("damage-tag").create(List.of("tag=FIRE"));
        var lacksTag = extraFactory("damage-tag").create(List.of("tag=FIRE", "value=false"));
        assertFalse(hasTag.test(ctx()));
        assertFalse(lacksTag.test(ctx()));
        assertThrows(IllegalArgumentException.class,
                () -> extraFactory("damage-tag").create(List.of("tag=FIRE", "value=maybe")));

        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        var context = BehaviorContext.continueExecution(null, (LivingEntity) null,
                1.0, -1, 0, "damage", null, null, List.of(), null, tree);
        assertFalse(hasTag.test(context));
        tree.damageTags(Set.of("FIRE"));
        assertTrue(hasTag.test(context));
        assertFalse(lacksTag.test(context));
        assertTrue(extraFactory("damage-tag").create(List.of("tag=ICE", "value=false")).test(context));
    }

    @Test
    @DisplayName("bounding-box overlap is registered and fails closed without an instance")
    void officialBoundingBoxConditionIsRegistered() {
        assertFalse(extraFactory("bounding-boxes-overlap").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("creeper primed condition matches the live Bukkit state and fails closed without a creeper")
    void officialCreeperPrimedConditionIsRegistered() {
        var condition = extraFactory("is-creeper-primed").create(List.of());
        assertFalse(condition.test(ctx()));

        var creeper = mock(org.bukkit.entity.Creeper.class);
        var context = BehaviorContext.of(null, creeper, 1.0, -1, 0, "IDLE");
        when(creeper.isIgnited()).thenReturn(true, false);
        assertTrue(condition.test(context));
        assertFalse(condition.test(context));
    }

    @Test
    @DisplayName("distance-from-spawn measures from world spawn and fails closed without a location")
    void officialDistanceFromSpawnConditionIsRegistered() {
        var factory = extraFactory("distance-from-spawn");
        assertFalse(factory.create(List.of("<100")).test(ctx()));

        var world = mock(World.class);
        var instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(new Location(world, 3, 64, 4));
        when(world.getSpawnLocation()).thenReturn(new Location(world, 0, 64, 0));
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");

        assertTrue(factory.create(List.of("5to5")).test(context));
        assertTrue(factory.create(List.of(">=5")).test(context));
        assertFalse(factory.create(List.of(">5")).test(context));
        assertFalse(factory.create(List.of("<5")).test(context));
        assertFalse(factory.create(List.of("NaN")).test(context));
    }

    @Test
    @DisplayName("ender dragon alive condition fails closed without a world context")
    void officialEnderDragonAliveConditionIsRegistered() {
        assertFalse(extraFactory("ender-dragon-alive").create(List.of()).test(ctx()));
    }

    @Test
    @DisplayName("ender dragon phase condition matches phase lists and rejects invalid values")
    void officialEnderDragonPhaseConditionIsRegistered() {
        var factory = extraFactory("ender-dragon-phase");
        var exact = factory.create(List.of("circling"));
        var list = factory.create(List.of("FLY_TO_PORTAL,CIRCLING"));
        assertFalse(exact.test(ctx()));

        var dragon = mock(org.bukkit.entity.EnderDragon.class);
        var context = BehaviorContext.of(null, dragon, 1.0, -1, 0, "IDLE");
        when(dragon.getPhase()).thenReturn(org.bukkit.entity.EnderDragon.Phase.CIRCLING);
        assertTrue(exact.test(context));
        assertTrue(list.test(context));
        when(dragon.getPhase()).thenReturn(org.bukkit.entity.EnderDragon.Phase.HOVER);
        assertFalse(exact.test(context));
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(List.of("CIRCLING,INVALID")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(List.of("CIRCLING,")));
        assertThrows(IllegalArgumentException.class, () -> factory.create(List.of("")));
    }

    @Test
    @DisplayName("altitude condition fails closed without a world location")
    void officialAltitudeConditionIsRegistered() {
        assertFalse(extraFactory("altitude").create(List.of(">=0", "30")).test(ctx()));
    }

    @Test
    @DisplayName("vehicle-is-dead fails closed without a mounted caster")
    void officialVehicleIsDeadConditionIsRegistered() {
        assertFalse(extraFactory("vehicle-is-dead").create(List.of()).test(ctx()));
    }
}
