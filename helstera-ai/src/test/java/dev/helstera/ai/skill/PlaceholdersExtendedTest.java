package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.GameMode;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MainHand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 新增占位符目录的测试。
 *
 * <p>仅覆盖本次扩展的键：实体状态、玩家状态、生物类别、位置偏移与世界向属性。
 * 原有键的降级语义由 {@link PlaceholdersTest} 守着，这里不重复。</p>
 */
class PlaceholdersExtendedTest {

    private static BehaviorContext ctx(double hp, double dist, String state) {
        return BehaviorContext.of(null, null, hp, dist, 0, state);
    }

    // ---------------- 实体状态类 ----------------

    @Test
    @DisplayName("燃烧与冻结状态反映 Bukkit 标记")
    void exposesBurningAndFrozen() {
        Monster monster = mock(Monster.class);
        when(monster.getFireTicks()).thenReturn(40);
        when(monster.isFrozen()).thenReturn(true);
        when(monster.isInvulnerable()).thenReturn(false);
        BehaviorContext c = BehaviorContext.of(null, monster, 1.0, -1, 0, "IDLE");

        assertEquals("true", Placeholders.lookup("target.is-burning", c));
        assertEquals("true", Placeholders.lookup("target.is-on-fire", c), "is-burning 与 is-on-fire 应同义");
        assertEquals("true", Placeholders.lookup("target.is-frozen", c));
        assertEquals("false", Placeholders.lookup("target.is-invulnerable", c));
    }

    @Test
    @DisplayName("is-invulnerable 取到 true 时输出 true")
    void exposesInvulnerable() {
        Monster monster = mock(Monster.class);
        when(monster.isInvulnerable()).thenReturn(true);
        BehaviorContext c = BehaviorContext.of(null, monster, 1.0, -1, 0, "IDLE");
        assertEquals("true", Placeholders.lookup("target.is-invulnerable", c));
    }

    @Test
    @DisplayName("载具与拴绳、乘客状态")
    void exposesVehicleAndLeash() {
        Monster monster = mock(Monster.class);
        when(monster.isInsideVehicle()).thenReturn(true);
        when(monster.isLeashed()).thenReturn(false);
        when(monster.getVehicle()).thenReturn(monster);
        BehaviorContext c = BehaviorContext.of(null, monster, 1.0, -1, 0, "IDLE");

        assertEquals("true", Placeholders.lookup("target.is-in-vehicle", c));
        assertEquals("false", Placeholders.lookup("target.is-leashed", c));
        assertEquals("true", Placeholders.lookup("target.is-passenger", c));
    }

    @Test
    @DisplayName("左手判定只对玩家成立，非玩家恒为 false")
    void exposesLeftHanded() {
        Player lefty = mock(Player.class);
        when(lefty.getMainHand()).thenReturn(MainHand.LEFT);
        assertEquals("true", Placeholders.lookup("target.is-left-handed",
                BehaviorContext.of(null, lefty, 1.0, -1, 0, "IDLE")));

        Player righty = mock(Player.class);
        when(righty.getMainHand()).thenReturn(MainHand.RIGHT);
        assertEquals("false", Placeholders.lookup("target.is-left-handed",
                BehaviorContext.of(null, righty, 1.0, -1, 0, "IDLE")));

        Monster monster = mock(Monster.class);
        assertEquals("false", Placeholders.lookup("target.is-left-handed",
                BehaviorContext.of(null, monster, 1.0, -1, 0, "IDLE")));
    }

    // ---------------- 生物类别类 ----------------

    @Test
    @DisplayName("生物类别与 SkillExtras 的 is-monster/is-villager 同义")
    void exposesEntityCategories() {
        Monster monster = mock(Monster.class);
        BehaviorContext c = BehaviorContext.of(null, monster, 1.0, -1, 0, "IDLE");
        assertEquals("true", Placeholders.lookup("target.is-monster", c));
        assertEquals("false", Placeholders.lookup("target.is-villager", c));
        assertEquals("false", Placeholders.lookup("target.is-animal", c));

        Villager villager = mock(Villager.class);
        BehaviorContext v = BehaviorContext.of(null, villager, 1.0, -1, 0, "IDLE");
        assertEquals("true", Placeholders.lookup("target.is-villager", v));
        assertEquals("false", Placeholders.lookup("target.is-monster", v));
        // Villager 实现了 Tameable 以外的接口，这里只断言不抛异常且为布尔文本
        assertTrue(Placeholders.lookup("target.is-tameable", v).matches("true|false"));
    }

    @Test
    @DisplayName("is-raider 对非袭击者输出 false")
    void exposesRaiderFlag() {
        Monster monster = mock(Monster.class);
        BehaviorContext c = BehaviorContext.of(null, monster, 1.0, -1, 0, "IDLE");
        assertEquals("false", Placeholders.lookup("target.is-raider", c));
    }

    // ---------------- 玩家状态类 ----------------

    @Test
    @DisplayName("游戏模式细分为 is-creative/is-survival/is-spectator/is-adventure")
    void exposesGameModeSlices() {
        Player player = mock(Player.class);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        BehaviorContext c = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        assertEquals("true", Placeholders.lookup("target.is-creative", c));
        assertEquals("false", Placeholders.lookup("target.is-survival", c));
        assertEquals("false", Placeholders.lookup("target.is-spectator", c));
        assertEquals("false", Placeholders.lookup("target.is-adventure", c));
    }

    @Test
    @DisplayName("非玩家的游戏模式细分键恒为 false，不产出一串 null")
    void gameModeSlicesAreFalseForNonPlayers() {
        Monster monster = mock(Monster.class);
        BehaviorContext c = BehaviorContext.of(null, monster, 1.0, -1, 0, "IDLE");
        for (String key : new String[]{"is-creative", "is-survival", "is-spectator", "is-adventure"}) {
            assertEquals("false", Placeholders.lookup("target." + key, c), key);
        }
    }

    @Test
    @DisplayName("经验等级与进度")
    void exposesLevelAndExp() {
        Player player = mock(Player.class);
        when(player.getLevel()).thenReturn(37);
        when(player.getExp()).thenReturn(0.25f);
        BehaviorContext c = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        assertEquals("37", Placeholders.lookup("target.level", c));
        assertEquals("25", Placeholders.lookup("target.exp", c), "exp 按百分比取整");
    }

    @Test
    @DisplayName("level/exp 对非玩家返回 null 以保留原文")
    void levelAndExpAbsentForNonPlayers() {
        Monster monster = mock(Monster.class);
        BehaviorContext c = BehaviorContext.of(null, monster, 1.0, -1, 0, "IDLE");
        assertNull(Placeholders.lookup("target.level", c));
        assertNull(Placeholders.lookup("target.exp", c));
    }

    // ---------------- 无实例时的降级 ----------------

    @Test
    @DisplayName("新增键在没有实例/目标时一律返回 null（保留原文），不退化成 0 或 false")
    void newKeysDegradeToNullWithoutContext() {
        BehaviorContext c = ctx(0.5, -1, "IDLE");
        for (String key : Placeholders.knownKeys()) {
            // 只要求不抛异常；已知键的取值可能仍是 null
            Placeholders.resolve("<" + key + ">", c);
        }
        assertNull(Placeholders.lookup("caster.dx", c), "无目标时偏移量不应为 0");
        assertNull(Placeholders.lookup("caster.dz", c));
        assertNull(Placeholders.lookup("caster.biome", c));
        assertNull(Placeholders.lookup("caster.time", c));
        assertNull(Placeholders.lookup("caster.weather", c));
        assertNull(Placeholders.lookup("caster.moonphase", c));
        assertNull(Placeholders.lookup("caster.is-day", c));
    }

    @Test
    @DisplayName("未知键仍原样保留，扩展没有改变失败语义")
    void unknownKeysStillKeepVerbatim() {
        BehaviorContext c = ctx(0.5, -1, "IDLE");
        assertEquals("<caster.not-a-key>", Placeholders.resolve("<caster.not-a-key>", c));
        assertEquals("<caster.is-flying-to-mars>", Placeholders.resolve("<caster.is-flying-to-mars>", c));
    }

    @Test
    @DisplayName("世界向字段在无世界时返回 null，保留原文而非猜测默认值")
    void worldFieldsAbsentWithoutWorld() {
        BehaviorContext c = ctx(0.5, -1, "IDLE");
        assertNull(Placeholders.lookup("target.weather", c));
        assertNull(Placeholders.lookup("target.time", c));
        assertNull(Placeholders.lookup("target.moonphase", c));
        assertNull(Placeholders.lookup("target.biome", c));
        assertEquals("<caster.weather>", Placeholders.resolve("<caster.weather>", c));
    }

    @Test
    @DisplayName("Mythic scoped entity and location placeholders preserve their documented semantics")
    void exposesScopedEntityAndMythicLocationPlaceholders() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("paper_world");
        Monster caster = mock(Monster.class);
        Monster target = mock(Monster.class);
        Location casterLocation = new Location(world, -12.25, 64.125, 8.75, 12.5f, -5.25f);
        Location targetLocation = new Location(world, 2.75, 68.125, 12.75, -90.0f, 30.0f);
        when(caster.getLocation()).thenReturn(casterLocation);
        when(target.getLocation()).thenReturn(targetLocation);
        when(caster.getCustomName()).thenReturn("Caster display");
        when(target.getCustomName()).thenReturn("Target display");
        when(caster.isValid()).thenReturn(true);
        when(target.isValid()).thenReturn(true);
        when(caster.getType()).thenReturn(EntityType.ZOMBIE);
        when(target.getType()).thenReturn(EntityType.SKELETON);
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(instance.location()).thenReturn(casterLocation);
        BehaviorContext context = BehaviorContext.of(instance, target, 1.0, -1, 0, "IDLE");

        assertEquals("Caster display", Placeholders.lookup("caster.display", context));
        assertEquals("Target display", Placeholders.lookup("target.display", context));
        assertEquals("zombie", Placeholders.lookup("caster.entity_type", context));
        assertEquals("skeleton", Placeholders.lookup("target.entity_type", context));
        assertEquals("0.0", Placeholders.lookup("caster.distancesquared", context));
        assertEquals("257.0", Placeholders.lookup("target.distancesquared", context));
        assertEquals("-12.5", Placeholders.lookup("caster.l.x", context));
        assertEquals("64.5", Placeholders.lookup("caster.l.y", context));
        assertEquals("8.5", Placeholders.lookup("caster.l.z", context));
        assertEquals("-12.25", Placeholders.lookup("caster.l.x.double", context));
        assertEquals("64.125", Placeholders.lookup("caster.l.y.double", context));
        assertEquals("8.75", Placeholders.lookup("caster.l.z.double", context));
        assertEquals("12.5", Placeholders.lookup("caster.l.yaw", context));
        assertEquals("-5.25", Placeholders.lookup("caster.l.pitch", context));
        assertEquals("paper_world", Placeholders.lookup("caster.l.w", context));
        assertEquals("2.75", Placeholders.lookup("target.l.x.double", context));
        assertEquals("68.125", Placeholders.lookup("target.l.y.double", context));
        assertEquals("12.75", Placeholders.lookup("target.l.z.double", context));
        assertTrue(Placeholders.knownKeys().contains("target.distancesquared"));
    }

    @Test
    @DisplayName("scoped squared distance fails closed without a live same-world target")
    void squaredDistanceRequiresSameWorldTarget() {
        World casterWorld = mock(World.class);
        World targetWorld = mock(World.class);
        Monster caster = mock(Monster.class);
        Monster target = mock(Monster.class);
        Location casterLocation = new Location(casterWorld, 0, 64, 0);
        when(caster.getLocation()).thenReturn(casterLocation);
        when(target.getLocation()).thenReturn(new Location(targetWorld, 3, 64, 4));
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(casterLocation);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        assertNull(Placeholders.lookup("target.distancesquared",
                BehaviorContext.of(instance, target, 1.0, 5.0, 0, "IDLE")));
        assertNull(Placeholders.lookup("target.distancesquared",
                BehaviorContext.of(instance, (org.bukkit.entity.LivingEntity) null,
                        1.0, -1, 0, "IDLE")));
    }
}
