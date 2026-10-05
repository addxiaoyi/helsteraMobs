package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}