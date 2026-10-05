package dev.helstera.ai.immunity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 免疫 / 伤害倍率判定测试。
 *
 * <p>直接构造 {@link ImmunityService.Config} 而非 YAML：被测的匹配优先级、
 * 倍率语义与告警生成都不该依赖 Bukkit 的配置解析器
 * （与 {@code FactionServiceTest} 同一思路）。</p>
 *
 * <p>重点守四件「写错了服务端不报错、只是让战斗变得莫名其妙」的事：
 * <b>未配置不修改</b>、<b>精确优先于类别</b>、<b>倍率不逐次衰减</b>、
 * <b>未知名必须告警</b>。</p>
 */
class ImmunityServiceTest {

    private static ImmunityService.Table table(ImmunityService.Row... rows) {
        return ImmunityService.compile(new ImmunityService.Config(List.of(rows)));
    }

    @Test
    @DisplayName("isHeal / healAmount：只有负倍率才是回血，且回血量为正数")
    void healSemantics() {
        var heal = table(ImmunityService.Row.of("entity-attack", -1.0)).evaluate("ENTITY_ATTACK", 4.0);
        assertTrue(heal.isHeal(), "负倍率应判定为回血");
        assertEquals(4.0, heal.healAmount(), 1e-9, "healAmount 必须是正数（加血量，不是负伤害值）");

        var immune = table(ImmunityService.Row.immune("MAGIC")).evaluate("MAGIC", 4.0);
        assertFalse(immune.isHeal(), "negate 是归零，不是回血");
        assertEquals(0.0, immune.healAmount(), 1e-9);

        var reduce = table(ImmunityService.Row.of("fire", 0.5)).evaluate("FIRE", 4.0);
        assertFalse(reduce.isHeal(), "正倍率是减伤，不是回血");
        assertEquals(0.0, reduce.healAmount(), 1e-9);

        var none = table().evaluate("FIRE", 4.0);
        assertFalse(none.isHeal(), "未命中时不是回血（matched 为 false，必须一并判掉）");
        assertEquals(0.0, none.healAmount(), 1e-9);
    }

    @Test
    @DisplayName("零伤害未命中时不会被误判成回血")
    void zeroDamageUnmatchedIsNotHeal() {
        // 边界：damage < 0 才是回血，恰好 0（negate）不是。
        // 若把判断写成 <= 0，所有免疫规则都会顺带触发一次加血
        var immune = table(ImmunityService.Row.immune("FIRE")).evaluate("FIRE", 0.0);
        assertEquals(0.0, immune.damage(), 1e-9);
        assertFalse(immune.isHeal(), "negate 造成的 0 伤害不得被判成回血");
    }

    @Test
    @DisplayName("负倍率在 0 原始伤害下不产生回血")
    void negativeOnZeroDealsNothing() {
        // 原始伤害为 0 时负倍率的乘积仍是 0：不该凭空回血
        var r = table(ImmunityService.Row.of("fire", -1.0)).evaluate("FIRE", 0.0);
        assertEquals(0.0, r.damage(), 1e-9);
        assertFalse(r.isHeal(), "0 × 任何倍率都是 0，不产生回血");
    }

    @Test
    @DisplayName("未配置 = 不免疫：原值原样返回且不标记命中")
    void unconfiguredDoesNotModify() {
        var t = table();
        assertTrue(t.isEmpty(), "空配置应编译出空表");
        var r = t.evaluate("FIRE", 10.0);
        assertFalse(r.matched(), "未配置时不该命中任何规则");
        assertEquals(10.0, r.damage(), 1e-9, "未配置时伤害必须原样不动");
        assertNull(r.rule());
    }

    @Test
    @DisplayName("未配置某 cause 时不修改，而已配置的 cause 照常生效")
    void onlyConfiguredCausesAreTouched() {
        var t = table(ImmunityService.Row.immune("LAVA"));
        assertFalse(t.evaluate("FIRE", 10.0).matched(), "只免疫岩浆时，火焰不该被改");
        assertTrue(t.evaluate("LAVA", 10.0).matched());
    }

    @Test
    @DisplayName("精确 cause 命中并归零")
    void exactCauseMatches() {
        var t = table(ImmunityService.Row.immune("FIRE"));
        var r = t.evaluate("FIRE", 10.0);
        assertTrue(r.matched());
        assertEquals(0.0, r.damage(), 1e-9, "免疫应把伤害打到 0");
        assertEquals("FIRE", r.matchedKey());
    }

    @Test
    @DisplayName("cause 名大小写与分隔符不敏感")
    void causeNameNormalization() {
        var t = table(ImmunityService.Row.immune("fire_tick"));
        for (String spelling : List.of("FIRE_TICK", "fire_tick", "fire-tick", "FireTick", "firetick")) {
            assertTrue(t.evaluate(spelling, 8.0).matched(), "写法 " + spelling + " 应命中同一条规则");
        }
    }

    @Test
    @DisplayName("类别命中其全部 cause，且不越界到别的类别")
    void categoryMatchesAllItsCauses() {
        var t = table(ImmunityService.Row.of("fire", 0.5));
        // 类别 fire 覆盖的 cause 都应减半
        for (String cause : List.of("FIRE", "FIRE_TICK", "LAVA", "MELTING", "HOT_FLOOR", "CAMPFIRE")) {
            var r = t.evaluate(cause, 10.0);
            assertTrue(r.matched(), cause + " 应被 fire 类别命中");
            assertEquals(5.0, r.damage(), 1e-9, cause + " 应减半");
        }
        // 不属于 fire 的 cause 不该被改
        for (String cause : List.of("FALL", "DROWNING", "ENTITY_ATTACK", "POISON")) {
            assertFalse(t.evaluate(cause, 10.0).matched(), cause + " 不属于 fire 类别，不该被改");
        }
    }

    @Test
    @DisplayName("精确 cause 优先于类别：两者都命中时精确规则生效")
    void exactCauseBeatsCategory() {
        // 环境类别 + 精确 FIRE 规则：任务书给的正是这个场景
        var t = table(
                ImmunityService.Row.of("environment", 0.1),
                ImmunityService.Row.of("FIRE", 0.5));
        var r = t.evaluate("FIRE", 10.0);
        assertTrue(r.matched());
        assertEquals(5.0, r.damage(), 1e-9,
                "FIRE 同时命中 environment 与精确 FIRE，必须走精确规则（×0.5）而非 ×0.1");
        // 未写精确规则的 cause 仍走类别
        assertEquals(1.0, t.evaluate("FALL", 10.0).damage(), 1e-9,
                "没写精确规则的 cause 应落到类别倍率");
    }

    @Test
    @DisplayName("精确 cause 优先与书写顺序无关")
    void exactPriorityIgnoresDeclarationOrder() {
        var before = table(
                ImmunityService.Row.of("FIRE", 0.5),
                ImmunityService.Row.of("environment", 0.1));
        var after = table(
                ImmunityService.Row.of("environment", 0.1),
                ImmunityService.Row.of("FIRE", 0.5));
        assertEquals(5.0, before.evaluate("FIRE", 10.0).damage(), 1e-9);
        assertEquals(5.0, after.evaluate("FIRE", 10.0).damage(), 1e-9,
                "把精确规则写到类别之后也必须优先，否则「改配置顺序就换一条规则生效」");
    }

    @Test
    @DisplayName("多条类别同时命中时先写的那条生效（书写顺序有意义）")
    void firstDeclaredCategoryWins() {
        var t = table(
                ImmunityService.Row.of("fire", 0.5),
                ImmunityService.Row.of("environment", 0.1));
        assertEquals(5.0, t.evaluate("LAVA", 10.0).damage(), 1e-9,
                "fire 写在 environment 前，LAVA 应走 fire");
        var reversed = table(
                ImmunityService.Row.of("environment", 0.1),
                ImmunityService.Row.of("fire", 0.5));
        assertEquals(1.0, reversed.evaluate("LAVA", 10.0).damage(), 1e-9,
                "顺序反过来应换成 environment，顺序必须真的起作用");
    }

    @Test
    @DisplayName("倍率作用于事件原始值，不逐次衰减")
    void multiplierAppliesToOriginalNotCurrent() {
        var t = table(ImmunityService.Row.of("fire", 0.5));
        // 每次判定都传入「本次事件的原始伤害 10」，结果必须恒为 5
        for (int i = 0; i < 5; i++) {
            assertEquals(5.0, t.evaluate("FIRE", 10.0).damage(), 1e-9,
                    "第 " + (i + 1) + " 次判定：倍率必须乘在原始值上，"
                            + "若乘在已修正值上会衰减成 0.25、0.125");
        }
    }

    @Test
    @DisplayName("倍率与原值线性：两次判定不共享状态")
    void repeatedEvaluationIsStateless() {
        var t = table(ImmunityService.Row.of("entity-attack", 0.25));
        assertEquals(2.5, t.evaluate("ENTITY_ATTACK", 10.0).damage(), 1e-9);
        assertEquals(2.5, t.evaluate("ENTITY_ATTACK", 10.0).damage(), 1e-9);
        assertEquals(5.0, t.evaluate("ENTITY_ATTACK", 20.0).damage(), 1e-9);
    }

    @Test
    @DisplayName("负倍率 = 回血（得到负伤害）")
    void negativeMultiplierHeals() {
        var t = table(ImmunityService.Row.of("entity-attack", -1.0));
        var r = t.evaluate("ENTITY_ATTACK", 4.0);
        assertTrue(r.matched());
        assertEquals(-4.0, r.damage(), 1e-9,
                "负倍率必须产生负伤害（Bukkit 对负伤害走治疗分支），不另造回血机制");
    }

    @Test
    @DisplayName("negate 与负倍率走同一条链路：negate 归零、负倍率回血")
    void negateAndNegativeShareOneMechanism() {
        var immune = table(ImmunityService.Row.immune("MAGIC"));
        var heal = table(ImmunityService.Row.of("MAGIC", -0.5));
        assertEquals(0.0, immune.evaluate("MAGIC", 10.0).damage(), 1e-9);
        assertEquals(-5.0, heal.evaluate("MAGIC", 10.0).damage(), 1e-9);
        // 两条都必须走同一个 evaluate 入口，不存在「免疫」与「回血」两套机制
        assertNotNull(immune.ruleFor("MAGIC"));
        assertNotNull(heal.ruleFor("MAGIC"));
    }

    @Test
    @DisplayName("0 倍率等同免疫")
    void zeroMultiplierIsImmunity() {
        var t = table(ImmunityService.Row.of("POISON", 0.0));
        assertEquals(0.0, t.evaluate("POISON", 6.0).damage(), 1e-9);
    }

    @Test
    @DisplayName("未知名在装载期告警，运行期静默跳过")
    void unknownNameWarnsAtLoadTime() {
        var t = table(ImmunityService.Row.immune("FIRE_JKD"));
        assertTrue(t.isEmpty(), "未知名不该产生规则");
        assertTrue(t.warnings().stream().anyMatch(w -> w.contains("FIRE_JKD")),
                "未知名必须告警，否则规则永不匹配且现场与「没配」完全一致。实际: " + t.warnings());
        assertFalse(t.evaluate("FIRE_JKD", 10.0).matched());
    }

    @Test
    @DisplayName("条件成立时规则生效，不成立时退回下一条而非冻结伤害")
    void conditionsGateTheRule() {
        var t = table(ImmunityService.Row.immune("FIRE", List.of("is-boss")));
        // 条件成立 -> 生效
        var on = t.evaluate("FIRE", 10.0, spec -> "is-boss".equals(spec));
        assertTrue(on.matched());
        assertEquals(0.0, on.damage(), 1e-9);
        // 条件不成立 -> 该条视为不存在，原值不动
        var off = t.evaluate("FIRE", 10.0, spec -> false);
        assertFalse(off.matched(), "条件不成立时规则不应生效");
        assertEquals(10.0, off.damage(), 1e-9);
    }

    @Test
    @DisplayName("条件不成立时继续匹配下一条，不把后续规则一起废掉")
    void unmetConditionFallsThrough() {
        // 用 FIRE_TICK 做精确规则：它只是 cause，不与任何类别同名，
        // 这样两条规则才能共存（fire 同时是 cause 与类别，会互相顶掉）
        var t = table(
                ImmunityService.Row.of("FIRE_TICK", 0.5, List.of("enraged")),
                ImmunityService.Row.of("fire", 0.1));
        assertEquals(5.0, t.evaluate("FIRE_TICK", 10.0, spec -> true).damage(), 1e-9,
                "条件成立时精确规则优先");
        assertEquals(1.0, t.evaluate("FIRE_TICK", 10.0, spec -> false).damage(), 1e-9,
                "精确规则条件不成立时必须退回 fire 类别（×0.1），而非不生效");
    }

    @Test
    @DisplayName("无条件求值器时，带条件规则一律不成立（无法判断不等于成立）")
    void missingEvaluatorMeansUnconditionalRulesFail() {
        var t = table(
                ImmunityService.Row.of("fire", 0.1),
                ImmunityService.Row.immune("FREEZE", List.of("enraged")));
        var r = t.evaluate("FREEZE", 10.0);
        assertFalse(r.matched(), "没有求值器时不能假定条件成立，否则漏接线会直接变成免疫全场");
        assertEquals(10.0, r.damage(), 1e-9);
        // 同表里的无条件规则不受影响
        assertEquals(1.0, t.evaluate("MELTING", 10.0).damage(), 1e-9);
    }

    @Test
    @DisplayName("单条条件抛异常按不成立处理，不让整条免疫链崩掉")
    void throwingConditionFailsClosed() {
        var t = table(ImmunityService.Row.immune("FIRE", List.of("boom")));
        var r = t.evaluate("FIRE", 10.0, spec -> {
            throw new IllegalStateException("条件注册坏了");
        });
        assertFalse(r.matched(), "条件出错应按不成立处理");
        assertEquals(10.0, r.damage(), 1e-9);
    }

    @Test
    @DisplayName("hasConditions 只在真有条件时为 true")
    void hasConditionsFlag() {
        assertFalse(table(ImmunityService.Row.of("fire", 0.5)).hasConditions(),
                "无条件档案不该让监听器去造 BehaviorContext");
        assertTrue(table(ImmunityService.Row.immune("FIRE", List.of("x"))).hasConditions());
    }

    @Test
    @DisplayName("条件进入 describe 展示，否则诊断命令看不出规则受什么限制")
    void describeShowsConditions() {
        var r = table(ImmunityService.Row.immune("FREEZE", List.of("enraged", "has-target")))
                .ruleFor("FREEZE", spec -> true);
        assertNotNull(r, "带求值器时规则应可被找到（无条件 ruleFor 会因缺求值器而返回 null）");
        assertTrue(r.describe().contains("enraged"), "实际: " + r.describe());
        assertTrue(r.describe().contains("has-target"), "实际: " + r.describe());
        assertFalse(r.unconditional());
    }

    @Test
    @DisplayName("回血夹到 maxHealth，绝不产生越界 setHealth")
    void healIsClampedToMax() {
        // setHealth 越界会被服务端夹到 0，也就是把生物打死。
        // 「Boss 莫名其妙暴毙」比「不回血」难解释得多，而它就发生在这行算术里。
        assertEquals(20.0, ImmunityService.clampHeal(10.0, 20.0, 100.0), 1e-9,
                "回血量远超剩余血量时必须夹到 maxHealth");
        assertEquals(20.0, ImmunityService.clampHeal(19.0, 20.0, 5.0), 1e-9);
        assertEquals(15.0, ImmunityService.clampHeal(10.0, 20.0, 5.0), 1e-9, "未满血时应正常加血");
    }

    @Test
    @DisplayName("已满血或非正回血量时不改血量")
    void healIsNoopWhenFullOrNonPositive() {
        assertEquals(20.0, ImmunityService.clampHeal(20.0, 20.0, 5.0), 1e-9,
                "已满血时应原样返回，避免一次无意义的写入");
        assertEquals(10.0, ImmunityService.clampHeal(10.0, 20.0, 0.0), 1e-9);
        assertEquals(10.0, ImmunityService.clampHeal(10.0, 20.0, -5.0), 1e-9,
                "负回血量不应把生物打伤");
        assertEquals(10.0, ImmunityService.clampHeal(10.0, 0.0, 5.0), 1e-9,
                "maxHealth 非法时原样返回，不能算出 NaN 写进 setHealth");
    }

    @Test
    @DisplayName("非法倍率（NaN / 无穷）在装载期告警并跳过，不抛异常")
    void illegalMultiplierWarnsAndSkips() {
        var t = table(
                new ImmunityService.Row("FIRE", Double.NaN, null, java.util.List.of()),
                new ImmunityService.Row("LAVA", Double.POSITIVE_INFINITY, null, java.util.List.of()),
                ImmunityService.Row.of("POISON", 0.5));
        assertEquals(1, t.size(), "两个非法项都该被跳过，只剩合法的一条");
        assertEquals(2, t.warnings().size(), "两个非法项都该告警，实际: " + t.warnings());
        assertEquals(5.0, t.evaluate("POISON", 10.0).damage(), 1e-9);
    }

    @Test
    @DisplayName("既无倍率又无 immune 的条目告警且不产生规则")
    void emptyRowWarns() {
        var t = table(new ImmunityService.Row("FIRE", null, Boolean.FALSE, java.util.List.of()));
        assertTrue(t.isEmpty());
        assertTrue(t.warnings().stream().anyMatch(w -> w.contains("FIRE")));
    }

    @Test
    @DisplayName("重复声明以后者为准并告警")
    void duplicateKeyWarnsAndLastWins() {
        var t = table(ImmunityService.Row.of("fire", 0.5), ImmunityService.Row.of("fire", 0.25));
        assertEquals(1, t.size(), "重复声明不该留下两条规则（否则两条都会生效）");
        assertEquals(2.5, t.evaluate("FIRE", 10.0).damage(), 1e-9, "以后者为准");
        assertTrue(t.warnings().stream().anyMatch(w -> w.contains("重复")), "实际: " + t.warnings());
    }

    @Test
    @DisplayName("规则顺序按服主书写顺序保留，不被归类打乱")
    void ruleOrderFollowsDeclaration() {
        var t = table(
                ImmunityService.Row.of("drowning", 0.5),
                ImmunityService.Row.immune("fire"),
                ImmunityService.Row.of("fall", 0.25));
        assertEquals(List.of("drowning", "fire", "fall"),
                t.rules().stream().map(ImmunityService.Rule::key).toList(),
                "顺序必须原样保留：类别优先级依赖书写顺序，"
                        + "用 EnumMap 之类按 ordinal 排的容器会把顺序打乱");
    }

    @Test
    @DisplayName("environment 类别覆盖全部环境伤害，但不含实体攻击与法术")
    void environmentScopeIsDeliberate() {
        var t = table(ImmunityService.Row.immune("environment"));
        for (String cause : List.of("FIRE", "FIRE_TICK", "LAVA", "FALL", "DROWNING",
                "SUFFOCATION", "POISON", "WITHER", "VOID", "STARVATION", "LIGHTNING",
                "BLOCK_EXPLOSION", "ENTITY_EXPLOSION", "FREEZE", "WORLD_BORDER")) {
            assertTrue(t.evaluate(cause, 10.0).matched(), cause + " 属环境伤害，应被 environment 覆盖");
        }
        // 把实体攻击/法术算进「环境」会让「免疫环境伤害」的 Boss 同时免疫玩家攻击
        for (String cause : List.of("ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK", "PROJECTILE", "MAGIC")) {
            assertFalse(t.evaluate(cause, 10.0).matched(),
                    cause + " 不是环境伤害，environment 不该覆盖它");
        }
    }

    @Test
    @DisplayName("all 类别覆盖一切伤害")
    void allCategoryCoversEverything() {
        var t = table(ImmunityService.Row.immune("all"));
        for (String cause : DamageCategory.KNOWN_CAUSES) {
            assertTrue(t.evaluate(cause, 10.0).matched(), cause + " 应被 all 覆盖");
        }
    }

    @Test
    @DisplayName("没有攻击者的伤害（火焰/溺水/窒息/饥饿）同样能匹配")
    void causesWithoutAttackerAreMatchable() {
        // 这些 cause 走 EntityDamageEvent 而非 EntityDamageByEntityEvent，
        // 若监听器选错事件类型，这四条规则就永远不会生效
        var t = table(ImmunityService.Row.immune("FIRE"),
                ImmunityService.Row.immune("DROWNING"),
                ImmunityService.Row.immune("SUFFOCATION"),
                ImmunityService.Row.immune("STARVATION"));
        for (String cause : List.of("FIRE", "FIRE_TICK", "DROWNING", "SUFFOCATION", "CRAMMING",
                "STARVATION")) {
            assertTrue(t.evaluate(cause, 10.0).matched(), cause + " 无攻击者，必须能被免疫规则覆盖");
        }
    }

    @Test
    @DisplayName("空 cause 名返回未命中而不是抛异常")
    void emptyCauseIsSafe() {
        var t = table(ImmunityService.Row.of("fire", 0.5));
        assertFalse(t.evaluate(null, 10.0).matched());
        assertFalse(t.evaluate("", 10.0).matched());
        assertFalse(t.evaluate("   ", 10.0).matched());
    }

    @Test
    @DisplayName("null 配置与 null 行安全处理")
    void nullConfigTolerated() {
        ImmunityService.Table t = ImmunityService.compile(null);
        assertTrue(t.isEmpty());
        assertFalse(t.evaluate("FIRE", 10.0).matched());
        ImmunityService.Config c = new ImmunityService.Config(null);
        assertTrue(c.rows().isEmpty());
        var withNulls = ImmunityService.compile(new ImmunityService.Config(java.util.Arrays.asList(
                (ImmunityService.Row) null, ImmunityService.Row.immune("FIRE"))));
        assertEquals(1, withNulls.size());
    }

    @Test
    @DisplayName("affects 供诊断命令判断某 cause 是否会被改动")
    void affectsMatchesEvaluate() {
        var t = table(ImmunityService.Row.of("fire", 0.5));
        for (String cause : DamageCategory.KNOWN_CAUSES) {
            assertEquals(t.evaluate(cause, 10.0).matched(), t.affects(cause),
                    "affects 与 evaluate 必须一致，否则 /helstera immunity 的试算会骗人：" + cause);
        }
    }

    @Test
    @DisplayName("规则表不可变：外部改不动已编译结果")
    void tableIsImmutable() {
        var t = table(ImmunityService.Row.of("fire", 0.5));
        assertThrowsUnsupported(() -> t.rules().clear());
        assertThrowsUnsupported(() -> t.warnings().clear());
        // 再次判定仍应正常，证明上面的修改确实没生效
        assertEquals(5.0, t.evaluate("FIRE", 10.0).damage(), 1e-9);
    }

    private static void assertThrowsUnsupported(Runnable r) {
        try {
            r.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("规则表应不可变：调用方能改掉它就等于能改掉已生效的免疫");
    }
}