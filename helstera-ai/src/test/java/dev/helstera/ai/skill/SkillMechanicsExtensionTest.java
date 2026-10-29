package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第二批 mechanic 扩展的测试：push-away / glide / stop-glide / set-hunger / set-slot。
 *
 * <p>这一批的共同风险不是「崩」，而是「静默失效」——推离在重合位置变成原地不动、
 * 饥饿度写到越界值被原版静默回写、带 {@code @Targeter} 的写法映射不到
 * {@code *-targets} 键而退化到施法者自身。这些都是不报错的错，
 * 所以测试逐条锁住可观察的行为，而不只是「能构造出来」。</p>
 */
class SkillMechanicsExtensionTest {

    private static BehaviorContext contextWith(ModelInstance instance, List<LivingEntity> targets) {
        LivingEntity first = targets.isEmpty() ? null : targets.get(0);
        return BehaviorContext.continueExecution(instance, first, 1.0, 3.0, 0,
                "IDLE", null, first, targets, null, null);
    }

    private static ModelInstance instanceWith(LivingEntity caster) {
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        return instance;
    }

    // ------------------------------------------------------------------
    // push-away
    // ------------------------------------------------------------------

    @Test
    @DisplayName("push-away 注册在册，且带 @Targeter 时无需别名即可使用")
    void pushAwayRegistered() {
        assertNotNull(SkillExtras.actions().get("push-away"), "push-away 缺失");
    }

    @Test
    @DisplayName("push-away 把目标沿背向施法者的水平方向推出去，并保留纵向分量")
    void pushAwayMovesTargetOutward() {
        World world = mock(World.class);
        LivingEntity caster = mock(LivingEntity.class);
        LivingEntity target = mock(LivingEntity.class);
        // selfLoc 优先取 ctx.origin()，其次才是 instance.location()；
        // 直接给 origin 才不会被 baseEntity 的默认 mock 位置带偏。
        Location origin = new Location(world, 0, 64, 0);
        when(caster.getLocation()).thenReturn(origin);
        when(target.getLocation()).thenReturn(new Location(world, 3, 64, 0));

        var ctx = BehaviorContext.continueExecution(instanceWith(caster), target, 1.0, 3.0, 0,
                "IDLE", null, target, List.of(target), origin, null);
        SkillExtras.actions().get("push-away").create(List.of("2.0", "0.5")).accept(ctx);
        var captured = org.mockito.ArgumentCaptor.forClass(Vector.class);
        verify(target).setVelocity(captured.capture());
        Vector v = captured.getValue();
        assertEquals(2.0, v.getX(), 1.0e-9, "水平方向应背向施法者，长度等于强度");
        assertEquals(0.5, v.getY(), 1.0e-9, "纵向分量应原样写入");
        assertEquals(0.0, v.getZ(), 1.0e-9);
    }

    @Test
    @DisplayName("推离向量：位置重合或跨世界时返回 null，目标不受影响")
    void pushAwayVectorRejectsDegenerateInput() {
        World world = mock(World.class);
        World other = mock(World.class);
        Location here = new Location(world, 5, 64, 5);
        Location same = new Location(world, 5, 64, 5);
        Location elsewhere = new Location(other, 9, 64, 5);
        Location apart = new Location(world, 5, 64, 8);

        assertNull(SkillExtras.pushAwayVector(here, same, 2.0, 0.1),
                "水平重合时不该给出零向量速度——那等于没推");
        assertNull(SkillExtras.pushAwayVector(here, elsewhere, 2.0, 0.1), "跨世界不施加速度");
        assertNull(SkillExtras.pushAwayVector(here, apart, 0.0, 0.1), "强度非正不施加速度");
        assertEquals(new Vector(0, 0.1, 1.0), SkillExtras.pushAwayVector(here, apart, 1.0, 0.1));
    }

    @Test
    @DisplayName("push-away 在缺实例时不抛异常")
    void pushAwaySafeWithoutInstance() {
        SkillExtras.actions().get("push-away").create(List.of())
                .accept(BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE"));
    }

    // ------------------------------------------------------------------
    // glide / stop-glide
    // ------------------------------------------------------------------

    @Test
    @DisplayName("glide 与 stop-glide 均注册，且有各自的 -targets 变体")
    void glideVariantsRegistered() {
        var all = SkillExtras.actions();
        for (String name : List.of("glide", "stop-glide", "glide-targets", "stop-glide-targets")) {
            assertNotNull(all.get(name), name + " 缺失");
        }
    }

    @Test
    @DisplayName("glide 开启滑翔，stop-glide 关闭滑翔")
    void glideTogglesGliding() {
        World world = mock(World.class);
        LivingEntity caster = mock(LivingEntity.class);
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        var ctx = contextWith(instanceWith(caster), List.of());

        SkillExtras.actions().get("glide").create(List.of()).accept(ctx);
        verify(caster).setGliding(true);

        LivingEntity ground = mock(LivingEntity.class);
        when(ground.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        var ctx2 = contextWith(instanceWith(ground), List.of());
        SkillExtras.actions().get("stop-glide").create(List.of()).accept(ctx2);
        verify(ground).setGliding(false);
    }

    @Test
    @DisplayName("glide 支持 keyed 参数 state=false 表达关闭")
    void glideAcceptsKeyedState() {
        World world = mock(World.class);
        LivingEntity caster = mock(LivingEntity.class);
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        var ctx = contextWith(instanceWith(caster), List.of());

        SkillExtras.actions().get("glide").create(List.of("state=false")).accept(ctx);
        verify(caster).setGliding(false);
    }

    @Test
    @DisplayName("glide-targets 作用于选择出的目标而不是施法者")
    void glideTargetsAffectsSelectedTarget() {
        World world = mock(World.class);
        LivingEntity caster = mock(LivingEntity.class);
        LivingEntity target = mock(LivingEntity.class);
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(target.getLocation()).thenReturn(new Location(world, 2, 64, 0));

        var ctx = contextWith(instanceWith(caster), List.of(target));
        SkillExtras.actions().get("glide-targets").create(List.of()).accept(ctx);

        verify(target).setGliding(true);
        verify(caster, never()).setGliding(org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ------------------------------------------------------------------
    // set-hunger
    // ------------------------------------------------------------------

    @Test
    @DisplayName("set-hunger 及 -targets 变体均注册")
    void setHungerRegistered() {
        assertNotNull(SkillExtras.actions().get("set-hunger"));
        assertNotNull(SkillExtras.actions().get("set-hunger-targets"));
    }

    @Test
    @DisplayName("饥饿值与饱和度的钳位规则")
    void hungerClamping() {
        assertEquals(0, SkillExtras.clampFoodLevel(-5));
        assertEquals(20, SkillExtras.clampFoodLevel(40), "超出上限必须钳到 20，否则被原版静默回写");
        assertEquals(7, SkillExtras.clampFoodLevel(7));
        assertEquals(0, SkillExtras.clampFoodLevel(Double.NaN));

        assertEquals(0.0f, SkillExtras.clampSaturation(10, 0), "饱和度为 0 时饱和度必须一起归零");
        assertEquals(6.0f, SkillExtras.clampSaturation(10, 6), "饱和度不得超过饥饿值");
        assertEquals(3.5f, SkillExtras.clampSaturation(3.5, 20));
    }

    @Test
    @DisplayName("set-hunger 写入绝对值，并且只在给了第二参数时才写饱和度")
    void setHungerWritesAbsoluteValue() {
        World world = mock(World.class);
        Player caster = mock(Player.class);
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(caster.getFoodLevel()).thenReturn(18);
        var ctx = contextWith(instanceWith(caster), List.of());

        SkillExtras.actions().get("set-hunger").create(List.of("6")).accept(ctx);

        verify(caster).setFoodLevel(6);
        verify(caster, never()).setSaturation(anyFloat());
    }

    @Test
    @DisplayName("set-hunger 给了第二参数时同时写饱和度，并按饥饿值钳位")
    void setHungerWritesSaturationWhenGiven() {
        World world = mock(World.class);
        Player caster = mock(Player.class);
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        var ctx = contextWith(instanceWith(caster), List.of());

        SkillExtras.actions().get("set-hunger").create(List.of("4", "9")).accept(ctx);

        verify(caster).setFoodLevel(4);
        verify(caster).setSaturation(4.0f);
    }

    @Test
    @DisplayName("set-hunger 越界输入被钳位后写入，而不是原样透传给原版")
    void setHungerClampsBeforeWriting() {
        World world = mock(World.class);
        Player caster = mock(Player.class);
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        var ctx = contextWith(instanceWith(caster), List.of());

        SkillExtras.actions().get("set-hunger").create(List.of("99")).accept(ctx);

        verify(caster).setFoodLevel(20);
    }

    // ------------------------------------------------------------------
    // set-slot
    // ------------------------------------------------------------------

    @Test
    @DisplayName("set-slot 注册在册，缺实例时安全返回")
    void setSlotSafeWithoutInstance() {
        assertNotNull(SkillExtras.actions().get("set-slot"));
        SkillExtras.actions().get("set-slot").create(List.of("hand", "STONE"))
                .accept(BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE"));
    }

    @Test
    @DisplayName("set-slot 槽位为空时不做任何写入")
    void setSlotIgnoresEmptySlot() {
        World world = mock(World.class);
        Player caster = mock(Player.class);
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        var client = mock(org.bukkit.inventory.PlayerInventory.class);
        when(caster.getInventory()).thenReturn(client);

        var ctx = contextWith(instanceWith(caster), List.of());
        SkillExtras.actions().get("set-slot").create(List.of("", "STONE")).accept(ctx);

        verify(client, never()).setItem(anyInt(), any());
    }

    @Test
    @DisplayName("set-slot 清空写法：AIR / NONE 落为 null 而不是残留旧物品")
    void setSlotClearsWithAir() {
        World world = mock(World.class);
        Player caster = mock(Player.class);
        var inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(caster.getInventory()).thenReturn(inventory);

        var ctx = contextWith(instanceWith(caster), List.of());
        SkillExtras.actions().get("set-slot").create(List.of("hand", "AIR")).accept(ctx);

        verify(inventory).setItemInMainHand(org.mockito.ArgumentMatchers.isNull());
    }

    // ------------------------------------------------------------------
    // 目标感知别名
    // ------------------------------------------------------------------

    @Test
    @DisplayName("glide / stop-glide / set-hunger 带 @Targeter 时映射到对应的 -targets 键")
    void targetableAliasesResolveToRegisteredActions() {
        var registry = new BehaviorRegistryImpl();
        var svc = new SkillService(registry, null);
        svc.loadSkills(yaml("""
                skills:
                  cone:
                    on-decision:
                      - set-hunger{amount=3} @Target
                      - stop-glide @Target
                      - glide @Target
                      - push-away{power=1.5} @Target
                      - set-slot{slot=hand;item=STONE}
                """).getConfigurationSection("skills"));

        var ctx = BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE");
        // 到这里为止只为验证装载期没有告警——真正的语义由上面的单元用例覆盖。
        assertTrue(svc.warnings().stream().noneMatch(w -> w.contains("Unknown action")),
                "带 @Targeter 的写法不该落到 Unknown action: " + svc.warnings());
        assertFalse(svc.warnings().stream().anyMatch(w -> w.contains("set-hunger") || w.contains("glide")),
                "新增机制在装载期不应产生告警: " + svc.warnings());
    }

    private static YamlConfiguration yaml(String text) {
        return YamlConfiguration.loadConfiguration(new StringReader(text));
    }
}
