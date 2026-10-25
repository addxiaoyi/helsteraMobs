package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 占位符解析测试。
 *
 * <p>不依赖运行中的服务端：{@link Placeholders#render} 可注入假取值，
 * 因此扫描、转义与「未知键保留原文」这些最容易出错的规则都能脱离实体验证。</p>
 */
class PlaceholdersTest {

    private static BehaviorContext ctx(double hp, double dist, String state) {
        return BehaviorContext.of(null, null, hp, dist, 7, state);
    }

    @Test
    @DisplayName("未知键原样保留，而不是替换成空串")
    void keepsUnknownKeysVerbatim() {
        var c = ctx(1.0, -1, "IDLE");
        assertEquals("<caster.bogus>", Placeholders.resolve("<caster.bogus>", c));
        assertEquals("<完全不是占位符>", Placeholders.resolve("<完全不是占位符>", c));
    }

    @Test
    @DisplayName("血量占位符按一位小数输出")
    void formatsHealthWithOneDecimal() {
        var c = ctx(0.2299999, -1, "IDLE");
        assertEquals("23.0", Placeholders.resolve("<caster.hp>", c));
        assertEquals("23.0", Placeholders.resolve("<caster.hp.percent>", c));
    }

    @Test
    @DisplayName("无实例时实体类占位符退化为空而非抛异常")
    void degradesGracefullyWithoutInstance() {
        var c = ctx(0.5, -1, "IDLE");
        for (String key : Placeholders.knownKeys()) {
            // 只要求不抛异常；取值可能为 null（保留原文）或有效文本
            Placeholders.resolve("<" + key + ">", c);
        }
        assertNull(Placeholders.lookup("caster.loc.x", c), "无实例时坐标应为 null");
        assertNull(Placeholders.lookup("caster.world", c));
    }

    @Test
    @DisplayName("render 用注入的取值函数扫描替换")
    void renderUsesInjectedLookup() {
        String out = Placeholders.render("a<k1>b<k2>c", ctx(1, -1, "IDLE"),
                (key, c) -> key.equals("k1") ? "1" : null);
        assertEquals("a1b<k2>c", out, "取不到值的键应保留原文");
    }

    @Test
    @DisplayName("取值为 null 时保留原文，取到值才替换")
    void onlyReplacesNonNull() {
        String out = Placeholders.render("<x>|<y>", ctx(1, -1, "IDLE"), (k, c) -> "v");
        assertEquals("v|v", out);
    }

    @Test
    @DisplayName("取值函数抛异常时降级为保留原文，不吞掉整条文本")
    void survivesLookupException() {
        String out = Placeholders.render("前<bad>后", ctx(1, -1, "IDLE"), (k, c) -> {
            throw new IllegalStateException("炸了");
        });
        assertEquals("前<bad>后", out);
    }

    @Test
    @DisplayName("反斜杠转义输出字面尖括号")
    void honorsBackslashEscape() {
        String out = Placeholders.render("\\<literal>", ctx(1, -1, "IDLE"), (k, c) -> "v");
        assertEquals("<literal>", out, "\\< 应输出字面 <，且不再被当作占位符解析");
    }

    @Test
    @DisplayName("未闭合的 < 当普通字符处理")
    void toleratesUnclosedAngleBracket() {
        String out = Placeholders.render("5 < 10", ctx(1, -1, "IDLE"), (k, c) -> "v");
        assertEquals("5 < 10", out);
    }

    @Test
    @DisplayName("无尖括号的文本原样返回")
    void passesThroughPlainText() {
        assertEquals("hello", Placeholders.resolve("hello", ctx(1, -1, "IDLE")));
        assertEquals("", Placeholders.resolve("", ctx(1, -1, "IDLE")));
        assertNull(Placeholders.resolve(null, ctx(1, -1, "IDLE")));
    }

    @Test
    @DisplayName("random 区间落在 [lo, hi] 内")
    void randomRespectsBounds() {
        var c = ctx(1, -1, "IDLE");
        for (int i = 0; i < 200; i++) {
            double v = Placeholders.resolveNumber("<random.5to10>", c, -1);
            assertTrue(v >= 5.0 && v <= 10.0, "越界: " + v);
        }
    }

    @Test
    @DisplayName("random 上下界写反时自动纠正")
    void randomSwapsReversedBounds() {
        var c = ctx(1, -1, "IDLE");
        double v = Placeholders.resolveNumber("<random.10to5>", c, -1);
        assertTrue(v >= 5.0 && v <= 10.0, "应纠正为 5..10，实际: " + v);
    }

    @Test
    @DisplayName("上下界相等时返回该值本身")
    void randomWithEqualBoundsIsConstant() {
        assertEquals(7.0, Placeholders.resolveNumber("<random.7to7>", ctx(1, -1, "IDLE"), -1));
    }

    @Test
    @DisplayName("resolveNumber 无法解析时返回默认值，不返回 0")
    void resolveNumberFallsBackToDefault() {
        var c = ctx(1, -1, "IDLE");
        // 0 伤害是合法但完全错误的行为，因此这里必须回落到 def 而不是 0
        assertEquals(-1.0, Placeholders.resolveNumber("<caster.bogus>", c, -1));
        assertEquals(-1.0, Placeholders.resolveNumber("abc", c, -1));
        assertEquals(-1.0, Placeholders.resolveNumber("", c, -1));
        assertEquals(-1.0, Placeholders.resolveNumber(null, c, -1));
    }

    @Test
    @DisplayName("resolveNumber 能吃带占位符的文本")
    void resolveNumberExpandsPlaceholders() {
        var c = ctx(0.5, -1, "IDLE");
        // 0.5 血量比例 = 50%，一位小数输出
        assertEquals(50.0, Placeholders.resolveNumber("<caster.hp>", c, -1));
    }

    @Test
    @DisplayName("keysIn 列出全部占位符键")
    void listsKeysInText() {
        var keys = Placeholders.keysIn("a<caster.hp>b<target.name>c");
        assertTrue(keys.contains("caster.hp"));
        assertTrue(keys.contains("target.name"));
        assertEquals(2, keys.size());
    }

    @Test
    @DisplayName("keysIn 跳过转义的尖括号")
    void keysInIgnoresEscapedBrackets() {
        assertTrue(Placeholders.keysIn("\\<notaplaceholder>").isEmpty());
    }

    @Test
    @DisplayName("knownKeys 列出的键都能被 parseSkillRef 之外正常解析而不抛异常")
    void knownKeysAreResolvable() {
        var c = ctx(0.3, 5.0, "CHASE");
        for (String key : Placeholders.knownKeys()) {
            assertTrue(Placeholders.resolve("<" + key + ">", c) != null,
                    key + " 不应返回 null 文本");
        }
    }

    @Test
    @DisplayName("距离为 -1（无目标）时 distance 占位符不产出 0")
    void distanceAbsentWhenNoTarget() {
        assertNull(Placeholders.lookup("caster.distance", ctx(1, -1, "IDLE")),
                "无目标时应为 null 而非 0，否则 0 距离会让范围类条件误判");
    }

    @Test
    @DisplayName("有距离时 distance 输出实际值")
    void distancePresentWhenTargeted() {
        assertEquals("5.0", Placeholders.lookup("caster.distance", ctx(1, 5.0, "CHASE")));
    }

    @Test
    @DisplayName("mob / player 是 caster / target 的别名")
    void legacyScopeAliases() {
        var c = ctx(0.4, 3.0, "CHASE");
        assertEquals(Placeholders.lookup("caster.hp", c), Placeholders.lookup("mob.hp", c));
    }

    @Test
    @DisplayName("状态名可从 caster.state 取到，供调试技能使用")
    void exposesAiState() {
        assertEquals("CHASE", Placeholders.lookup("caster.state", ctx(1, -1, "CHASE")));
        assertEquals("7", Placeholders.lookup("caster.decisions", ctx(1, -1, "IDLE")));
    }

    @Test
    @DisplayName("target entity state placeholders expose Bukkit flags")
    void exposesTargetEntityState() {
        Player player = mock(Player.class);
        when(player.isSneaking()).thenReturn(true);
        when(player.isSprinting()).thenReturn(true);
        when(player.isFlying()).thenReturn(true);
        when(player.isBlocking()).thenReturn(true);
        when(player.isOp()).thenReturn(true);
        when(player.isOnGround()).thenReturn(true);
        when(player.isInWater()).thenReturn(false);
        when(player.isInvisible()).thenReturn(false);
        when(player.isGlowing()).thenReturn(true);
        when(player.getFireTicks()).thenReturn(20);
        when(player.hasPermission("my.node")).thenReturn(true);
        BehaviorContext context = BehaviorContext.of(null, player, 1.0, 3.0, 0, "CHASE");

        assertEquals("true", Placeholders.lookup("target.is-sneaking", context));
        assertEquals("true", Placeholders.lookup("target.is-sprinting", context));
        assertEquals("true", Placeholders.lookup("target.is-flying", context));
        assertEquals("true", Placeholders.lookup("target.is-blocking", context));
        assertEquals("true", Placeholders.lookup("target.is-op", context));
        assertEquals("true", Placeholders.lookup("target.is-on-ground", context));
        assertEquals("false", Placeholders.lookup("target.is-in-water", context));
        assertEquals("false", Placeholders.lookup("target.is-baby", context));
        assertEquals("false", Placeholders.lookup("target.is-tamed", context));
        assertEquals("false", Placeholders.lookup("target.is-invisible", context));
        assertEquals("true", Placeholders.lookup("target.is-glowing", context));
        assertEquals("true", Placeholders.lookup("target.is-on-fire", context));
        assertEquals("true", Placeholders.lookup("target.permission.my.node", context));
    }

    @Test
    @DisplayName("placeholder field aliases accept Mythic underscore spellings")
    void acceptsUnderscoreFieldAliases() {
        Player player = mock(Player.class);
        when(player.isSneaking()).thenReturn(true);
        when(player.getFireTicks()).thenReturn(20);
        BehaviorContext context = BehaviorContext.of(null, player, 0.5, -1, 0, "IDLE");

        assertEquals("50.0", Placeholders.lookup("target.hp_percent", context));
        assertEquals("true", Placeholders.lookup("target.is_sneaking", context));
        assertEquals("true", Placeholders.lookup("target.is_on_fire", context));
    }

    @Test
    @DisplayName("health, alive, and permission placeholder aliases follow Mythic spellings")
    void acceptsCommonMythicFieldAliases() {
        Player player = mock(Player.class);
        when(player.getHealth()).thenReturn(6.0);
        when(player.getMaxHealth()).thenReturn(20.0);
        when(player.isDead()).thenReturn(false);
        when(player.isValid()).thenReturn(true);
        when(player.hasPermission("my.node")).thenReturn(true);
        BehaviorContext context = BehaviorContext.of(null, player, 0.3, -1, 0, "IDLE");

        assertEquals("30.0", Placeholders.lookup("target.health.percent", context));
        assertEquals("6.0", Placeholders.lookup("target.health.raw", context));
        assertEquals("20.0", Placeholders.lookup("target.health.max", context));
        assertEquals("true", Placeholders.lookup("target.is_alive", context));
        assertEquals("true", Placeholders.lookup("target.has_permission.my.node", context));
        assertTrue(Placeholders.knownKeys().contains("target.is-alive"));
    }

    @Test
    @DisplayName("entity state placeholders expose age, AI, gravity and powered flags")
    void exposesEntityStateFlags() {
        org.bukkit.entity.Zombie zombie = mock(org.bukkit.entity.Zombie.class);
        when(zombie.isAdult()).thenReturn(true);
        when(zombie.hasAI()).thenReturn(true);
        when(zombie.hasGravity()).thenReturn(false);
        BehaviorContext context = BehaviorContext.of(null, zombie, 1.0, -1, 0, "IDLE");

        assertEquals("true", Placeholders.lookup("target.is-adult", context));
        assertEquals("false", Placeholders.lookup("target.is-baby", context));
        assertEquals("true", Placeholders.lookup("target.has-ai", context));
        assertEquals("false", Placeholders.lookup("target.has-gravity", context));

        org.bukkit.entity.Creeper creeper = mock(org.bukkit.entity.Creeper.class);
        when(creeper.isPowered()).thenReturn(true);
        BehaviorContext powered = BehaviorContext.of(null, creeper, 1.0, -1, 0, "IDLE");
        assertEquals("true", Placeholders.lookup("target.is-powered", powered));
    }

    @Test
    @DisplayName("placeholder entity state exposes collidable and offhand values")
    void exposesCollidableAndOffhandState() {
        Player player = mock(Player.class);
        org.bukkit.inventory.EntityEquipment equipment = mock(org.bukkit.inventory.EntityEquipment.class);
        org.bukkit.inventory.ItemStack shield = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.Material material = mock(org.bukkit.Material.class);
        when(player.isCollidable()).thenReturn(true);
        when(player.getEquipment()).thenReturn(equipment);
        when(equipment.getItemInOffHand()).thenReturn(shield);
        when(shield.getType()).thenReturn(material);
        when(material.isAir()).thenReturn(false);
        when(material.name()).thenReturn("SHIELD");
        BehaviorContext context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        assertEquals("true", Placeholders.lookup("target.is-collidable", context));
        assertEquals("true", Placeholders.lookup("target.has-offhand", context));
        assertEquals("shield", Placeholders.lookup("target.offhand.type", context));
    }

    @Test
    @DisplayName("placeholder exposes player gameplay and equipment values")
    void exposesGameplayValues() {
        Player player = mock(Player.class);
        org.bukkit.inventory.EntityEquipment equipment = mock(org.bukkit.inventory.EntityEquipment.class);
        org.bukkit.inventory.ItemStack sword = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.Material material = mock(org.bukkit.Material.class);
        org.bukkit.attribute.AttributeInstance luck = mock(org.bukkit.attribute.AttributeInstance.class);
        org.bukkit.attribute.AttributeInstance armor = mock(org.bukkit.attribute.AttributeInstance.class);
        when(player.getFoodLevel()).thenReturn(14);
        when(player.getSaturation()).thenReturn(3.5f);
        when(player.getExhaustion()).thenReturn(1.25f);
        when(player.getRemainingAir()).thenReturn(120);
        when(player.getMaximumAir()).thenReturn(300);
        when(player.getEquipment()).thenReturn(equipment);
        when(equipment.getItemInMainHand()).thenReturn(sword);
        when(sword.getType()).thenReturn(material);
        when(material.isAir()).thenReturn(false);
        when(material.name()).thenReturn("DIAMOND_SWORD");
        when(player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_LUCK)).thenReturn(luck);
        when(player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_ARMOR)).thenReturn(armor);
        when(luck.getValue()).thenReturn(2.0);
        when(armor.getValue()).thenReturn(8.0);
        BehaviorContext context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        assertEquals("14", Placeholders.lookup("target.food", context));
        assertEquals("14", Placeholders.lookup("target.food_level", context));
        assertEquals("3.5", Placeholders.lookup("target.saturation", context));
        assertEquals("1.3", Placeholders.lookup("target.exhaustion", context));
        assertEquals("120", Placeholders.lookup("target.air", context));
        assertEquals("300", Placeholders.lookup("target.air.max", context));
        assertEquals("2.0", Placeholders.lookup("target.luck", context));
        assertEquals("8.0", Placeholders.lookup("target.armor", context));
        assertEquals("diamond_sword", Placeholders.lookup("target.mainhand.type", context));
        assertEquals("diamond_sword", Placeholders.lookup("target.held.item", context));
    }
}
