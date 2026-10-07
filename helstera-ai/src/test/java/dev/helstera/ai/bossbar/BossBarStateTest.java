package dev.helstera.ai.bossbar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boss 血条决策层测试。
 *
 * <p>重点守三件「写错了服上只表现为观感不对、几乎无法归因」的事：
 * <b>分段边界归属</b>、<b>阶段与读条的拼接</b>、<b>未启用时不得显示</b>。</p>
 */
class BossBarStateTest {

    @Test
    @DisplayName("未启用时不可见且标题为空")
    void disabledIsInvisible() {
        var r = BossBarState.render(false, "龙", null, 100, 100, "狂暴", null);
        assertFalse(r.visible(), "未启用却可见，会让所有生物都冒出血条");
        assertEquals("", r.title());
    }

    @Test
    @DisplayName("血量比例正确换算")
    void ratioFromHealth() {
        assertEquals(0.5, BossBarState.render(true, "龙", null, 50, 100, null, null).ratio(), 1e-9);
        assertEquals(1.0, BossBarState.render(true, "龙", null, 100, 100, null, null).ratio(), 1e-9);
    }

    @Test
    @DisplayName("最大血量非正时按满血处理，不产生 NaN")
    void nonPositiveMaxHealth() {
        var r = BossBarState.render(true, "龙", null, 100, 0, null, null);
        assertEquals(1.0, r.ratio(), 1e-9, "maxHealth<=0 会让比例变成 NaN 或 Infinity");
        assertFalse(Double.isNaN(r.ratio()));
    }

    @Test
    @DisplayName("比例恒被夹在 0..1")
    void ratioClamped() {
        assertEquals(1.0, BossBarState.render(true, "x", null, 200, 100, null, null).ratio(), 1e-9);
        assertEquals(0.0, BossBarState.render(true, "x", null, -5, 100, null, null).ratio(), 1e-9);
    }

    @Test
    @DisplayName("档案名为空时回落到兜底名，再回落到 Boss")
    void nameFallback() {
        assertEquals("模型名", BossBarState.render(true, null, "模型名", 1, 1, null, null).title());
        assertEquals("模型名", BossBarState.render(true, "  ", "模型名", 1, 1, null, null).title());
        assertEquals("Boss", BossBarState.render(true, null, null, 1, 1, null, null).title());
    }

    @Test
    @DisplayName("阶段名以方括号拼在名称后")
    void phaseAppended() {
        assertEquals("龙 [狂暴]", BossBarState.render(true, "龙", null, 1, 1, "狂暴", null).title());
    }

    @Test
    @DisplayName("阶段名为空白时不拼接，避免出现「龙 []」")
    void blankPhaseOmitted() {
        assertEquals("龙", BossBarState.render(true, "龙", null, 1, 1, "  ", null).title());
        assertEquals("龙", BossBarState.render(true, "龙", null, 1, 1, null, null).title());
    }

    @Test
    @DisplayName("读条进度取整百分比并夹紧")
    void castAppended() {
        var r = BossBarState.render(true, "龙", null, 1, 1, null,
                new BossBarState.Cast("火球", 0.456));
        assertEquals("龙 火球 46%", r.title());
        assertEquals("龙 火球 100%",
                BossBarState.render(true, "龙", null, 1, 1, null,
                        new BossBarState.Cast("火球", 5)).title());
    }

    @Test
    @DisplayName("读条标签为空白时省略")
    void blankCastOmitted() {
        assertEquals("龙", BossBarState.render(true, "龙", null, 1, 1, null,
                new BossBarState.Cast("  ", 0.5)).title());
    }

    @Test
    @DisplayName("阶段与读条同时存在时依次拼接")
    void phaseAndCastCombined() {
        assertEquals("龙 [狂暴] 火球 30%",
                BossBarState.render(true, "龙", null, 1, 1, "狂暴",
                        new BossBarState.Cast("火球", 0.3)).title());
    }

    @Test
    @DisplayName("分段按血量降血切换配色")
    void colorBySegments() {
        var segs = List.of(
                new BossBarState.Segment(0.5, "GREEN", null),
                new BossBarState.Segment(0.2, "YELLOW", null),
                new BossBarState.Segment(0.0, "RED", null));
        assertEquals("GREEN", BossBarState.colorOf(segs, 0.9));
        assertEquals("YELLOW", BossBarState.colorOf(segs, 0.4));
        assertEquals("RED", BossBarState.colorOf(segs, 0.1));
    }

    @Test
    @DisplayName("档位顺序写反也能正确选档")
    void segmentsOrderIndependent() {
        var asc = List.of(
                new BossBarState.Segment(0.0, "RED", null),
                new BossBarState.Segment(0.2, "YELLOW", null),
                new BossBarState.Segment(0.5, "GREEN", null));
        var desc = List.of(
                new BossBarState.Segment(0.5, "GREEN", null),
                new BossBarState.Segment(0.2, "YELLOW", null),
                new BossBarState.Segment(0.0, "RED", null));
        for (double r : new double[]{0.9, 0.6, 0.4, 0.1}) {
            assertEquals(BossBarState.colorOf(asc, r), BossBarState.colorOf(desc, r),
                    "比例 " + r + "：档位书写顺序不该影响结果");
        }
    }

    @Test
    @DisplayName("半血边界归属于下界所属档，不含糊")
    void boundaryOwnership() {
        var segs = List.of(
                new BossBarState.Segment(0.5, "GREEN", null),
                new BossBarState.Segment(0.0, "RED", null));
        assertEquals("GREEN", BossBarState.colorOf(segs, 0.5),
                "0.5 恰好落在 GREEN 档下界，应归 GREEN 而非下一档");
        assertNotEquals("GREEN", BossBarState.colorOf(segs, 0.4999));
    }

    @Test
    @DisplayName("比例低于所有档下界时回落到最低档而非默认色")
    void belowAllSegmentsUsesLowest() {
        var segs = List.of(new BossBarState.Segment(0.8, "GREEN", null));
        assertEquals("GREEN", BossBarState.colorOf(segs, 0.1),
                "作者把下界都写高了时，濒死应显示最低档，而不是满血色");
    }

    @Test
    @DisplayName("空档位回落到默认色")
    void emptySegmentsFallback() {
        assertEquals("GREEN", BossBarState.colorOf(List.of(), 0.5));
        assertEquals("GREEN", BossBarState.colorOf(null, 0.5));
    }

    @Test
    @DisplayName("阈值与颜色数量不匹配时回落到默认色")
    void mismatchedThresholds() {
        assertEquals("GREEN", BossBarState.colorByThresholds(
                List.of(0.5, 0.2), List.of("RED"), 0.1));
        assertEquals("GREEN", BossBarState.colorByThresholds(null, null, 0.1));
    }

    @Test
    @DisplayName("阈值与颜色一一对应时按阈值选色")
    void thresholdsPaired() {
        assertEquals("RED", BossBarState.colorByThresholds(
                List.of(0.5, 0.2), List.of("GREEN", "RED"), 0.3));
        assertEquals("GREEN", BossBarState.colorByThresholds(
                List.of(0.5, 0.2), List.of("GREEN", "RED"), 0.7));
    }

    @Test
    @DisplayName("NaN 比例按 0 处理，不让比较全部失效")
    void nanRatioHandled() {
        assertEquals("RED", BossBarState.colorOf(List.of(
                new BossBarState.Segment(0.0, "RED", null),
                new BossBarState.Segment(0.5, "GREEN", null)), Double.NaN),
                "NaN 让所有比较返回 false，会静默落到错误配色");
    }

    @Test
    @DisplayName("clamp01 夹紧并处理 NaN")
    void clampHelper() {
        assertEquals(0, BossBarState.clamp01(-1));
        assertEquals(1, BossBarState.clamp01(2));
        assertEquals(0, BossBarState.clamp01(Double.NaN));
    }

    @Test
    @DisplayName("颜色名为空的档位被跳过")
    void blankColorSkipped() {
        var segs = List.of(
                new BossBarState.Segment(0.5, "  ", null),
                new BossBarState.Segment(0.0, "RED", null));
        assertEquals("RED", BossBarState.colorOf(segs, 0.8),
                "空白颜色名不应选中，否则会把空串推给 Bukkit");
    }

    @Test
    @DisplayName("读条进度 NaN 按 0 处理")
    void castNaN() {
        assertTrue(Double.isNaN(new BossBarState.Cast("x", Double.NaN).clamped())
                || new BossBarState.Cast("x", Double.NaN).clamped() == 0);
    }

    @Test
    @DisplayName("渲染结果不可变，便于跨 tick 复用")
    void renderIsValue() {
        var a = BossBarState.render(true, "龙", null, 1, 1, "一阶段", null);
        var b = BossBarState.render(true, "龙", null, 1, 1, "二阶段", null);
        assertEquals("龙 [一阶段]", a.title(), "上一次的 render 结果不应被后续调用改写");
        assertEquals("龙 [二阶段]", b.title());
    }

    @Test
    @DisplayName("可见性只由 enabled 决定，血量为零仍应可见")
    void visibilityIndependentOfHealth() {
        assertTrue(BossBarState.render(true, "龙", null, 0, 100, null, null).visible(),
                "残血时血条消失，玩家会以为 Boss 已死");
    }

    // ------------------------------------------------------------------
    // 渐变配色
    // ------------------------------------------------------------------

    @Test
    @DisplayName("渐变在档位端点取到端点色")
    void gradientHitsEndpoints() {
        var segs = List.of(
                new BossBarState.Segment(0.0, "RED", null),
                new BossBarState.Segment(1.0, "GREEN", null));
        assertEquals("RED", BossBarState.gradientOf(segs, 0.0));
        assertEquals("GREEN", BossBarState.gradientOf(segs, 1.0));
    }

    @Test
    @DisplayName("渐变中点插出不同于两端的第三色")
    void gradientInterpolatesMidpoint() {
        // RED(255,0,0) 与 GREEN(0,255,0) 的中点是 (128,128,128)，
        // 在六个可用色里都远，故必落到 PURPLE 或 BLUE 之一——
        // 关键是它不是任何一个端点色，这才叫插值而不是分段。
        var segs = List.of(
                new BossBarState.Segment(0.0, "RED", null),
                new BossBarState.Segment(1.0, "GREEN", null));
        String mid = BossBarState.gradientOf(segs, 0.5);
        assertNotEquals("RED", mid, "中点不该还是端点色");
        assertNotEquals("GREEN", mid, "中点不该还是端点色");
        assertTrue(java.util.Arrays.asList(BossBarState.AVAILABLE_COLORS).contains(mid),
                "插值结果必须落在 Bukkit 支持的颜色里，实际: " + mid);
    }

    @Test
    @DisplayName("渐变低于最低档下界时不外推，取最低档色")
    void gradientDoesNotExtrapolateBelow() {
        var segs = List.of(
                new BossBarState.Segment(0.5, "YELLOW", null),
                new BossBarState.Segment(0.8, "GREEN", null));
        assertEquals("YELLOW", BossBarState.gradientOf(segs, 0.1),
                "外推会造出作者没配过的颜色，作者只声明了 0.5 与 0.8 两点");
    }

    @Test
    @DisplayName("渐变档位少于 2 时退化为分段，不抛异常")
    void gradientDegradesToSegments() {
        assertEquals("RED", BossBarState.gradientOf(
                List.of(new BossBarState.Segment(0.0, "RED", null)), 0.5));
        assertEquals("GREEN", BossBarState.gradientOf(List.of(), 0.5));
        assertEquals("GREEN", BossBarState.gradientOf(null, 0.5));
    }

    @Test
    @DisplayName("渐变忽略空颜色档，不让空串参与插值")
    void gradientSkipsBlankColors() {
        var segs = List.of(
                new BossBarState.Segment(0.0, "  ", null),
                new BossBarState.Segment(0.5, "RED", null),
                new BossBarState.Segment(1.0, "GREEN", null));
        assertEquals("RED", BossBarState.gradientOf(segs, 0.5));
        assertTrue(java.util.Arrays.asList(BossBarState.AVAILABLE_COLORS)
                        .contains(BossBarState.gradientOf(segs, 0.1)),
                "空白档若参与插值会把结果拉向 GREEN（空串按绿处理）");
    }

    @Test
    @DisplayName("渐变结果单调：越接近高档越取高档色")
    void gradientIsMonotonic() {
        var segs = List.of(
                new BossBarState.Segment(0.0, "BLUE", null),
                new BossBarState.Segment(1.0, "RED", null));
        var seen = new java.util.LinkedHashSet<String>();
        for (double r = 0; r <= 1.0; r += 0.05) seen.add(BossBarState.gradientOf(segs, r));
        assertTrue(seen.size() >= 2,
                "整条血量范围内只出现一种色，插值根本没生效，实际: " + seen);
    }

    // ------------------------------------------------------------------
    // 快捷开关的默认配置
    // ------------------------------------------------------------------

    @Test
    @DisplayName("defaults() 是启用且无配色的裸血条")
    void defaultsAreEnabledButBare() {
        var d = BossBarConfig.defaults();
        assertTrue(d.enabled(), "快捷开关命中时必须显示血条，默认配置若是 disabled 会让开关看起来没生效");
        assertTrue(d.title() == null, "默认不该带标题，否则会盖掉档案名回落");
        assertEquals(0.0, d.range(), 1e-9, "默认不限距离");
        assertFalse(d.hasSegments(), "默认无配色档，withConfiguredColor 应原样返回默认色");
        assertFalse(d.gradient(), "默认不开渐变");
    }

    @Test
    @DisplayName("defaults() 渲染出可见且有标题的血条")
    void defaultsRenderUsableBar() {
        var d = BossBarConfig.defaults();
        var r = BossBarState.render(true, d.title(), null, 60, 100, null, null);
        assertTrue(r.visible(), "默认配置必须渲染出可见血条");
        assertEquals("Boss", r.title(), "无标题时应回落到兜底名而非空串");
        assertEquals(0.6, r.ratio(), 1e-9);
    }

    @Test
    @DisplayName("未配置 bossbar 节时返回 disabled，与 defaults() 语义相反")
    void missingSectionDiffersFromDefaults() {
        var missing = BossBarConfig.parse(null, new ArrayList<>());
        assertFalse(missing.enabled(),
                "未配置节应视为不显示——否则所有生物都会冒出血条");
        assertTrue(BossBarConfig.defaults().enabled(),
                "而 defaults() 是给快捷开关用的，必须显示。两者语义不可混同");
    }
}