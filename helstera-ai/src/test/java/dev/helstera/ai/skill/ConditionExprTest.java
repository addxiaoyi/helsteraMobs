package dev.helstera.ai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件表达式树测试。
 *
 * <p>重点验证三件容易做错的事：优先级、AND 短路、文法不匹配时的整体回退。
 * 最后一条尤其关键——静默把 {@code a || b} 截成只判 {@code a} 的实现，
 * 表现是条件「偶发失效」，几乎不可能靠读代码定位。</p>
 */
class ConditionExprTest {

    /** 求值器：只认已知条件名，返回集合内为真。 */
    private static ConditionExpr.Eval truthy(String... names) {
        Set<String> yes = Set.of(names);
        return spec -> {
            String head = spec.split("\\s+")[0];
            return yes.contains(head);
        };
    }

    @Test
    @DisplayName("单条件直通")
    void singleLeaf() {
        assertTrue(ConditionExpr.parse("health-below").test(truthy("health-below")));
        assertFalse(ConditionExpr.parse("health-below").test(truthy()));
    }

    @Test
    @DisplayName("空文本与 null 视为无条件成立")
    void emptyIsAlwaysTrue() {
        assertTrue(ConditionExpr.parse("").test(truthy()));
        assertTrue(ConditionExpr.parse("   ").test(truthy()));
        assertTrue(ConditionExpr.parse(null).test(truthy()));
    }

    @Test
    @DisplayName("&& 全满足才为真")
    void andRequiresAll() {
        var e = ConditionExpr.parse("a && b");
        assertTrue(e.test(truthy("a", "b")));
        assertFalse(e.test(truthy("a")));
        assertFalse(e.test(truthy("b")));
    }

    @Test
    @DisplayName("|| 任一满足即为真")
    void orRequiresAny() {
        var e = ConditionExpr.parse("a || b");
        assertTrue(e.test(truthy("a")));
        assertTrue(e.test(truthy("b")));
        assertFalse(e.test(truthy()));
    }

    @Test
    @DisplayName("优先级：&& 高于 ||，即 a || b && c 读作 a || (b && c)")
    void precedenceAndBindsTighter() {
        var e = ConditionExpr.parse("a || b && c");
        // a 成立即真，b/c 无关
        assertTrue(e.test(truthy("a")));
        // a 不成立时要求 b && c 同真
        assertTrue(e.test(truthy("b", "c")));
        assertFalse(e.test(truthy("b")), "只有 b 时 b && c 为假");
        assertFalse(e.test(truthy("c")), "只有 c 时 b && c 为假");
    }

    @Test
    @DisplayName("括号可改变结合")
    void parenthesesOverridePrecedence() {
        var e = ConditionExpr.parse("(a || b) && c");
        assertFalse(e.test(truthy("a")), "a 成立但 c 不成立，整体为假");
        assertFalse(e.test(truthy("b")));
        assertTrue(e.test(truthy("a", "c")));
        assertTrue(e.test(truthy("b", "c")));
    }

    @Test
    @DisplayName("AND 短路：左侧为假时不再评估右侧")
    void andShortCircuits() {
        var asked = new ArrayList<String>();
        var e = ConditionExpr.parse("a && b");
        e.test(spec -> {
            asked.add(spec);
            return false;          // a 为假，应短路
        });
        assertEquals(List.of("a"), asked, "a 为假后不应再问 b");
    }

    @Test
    @DisplayName("OR 短路：左侧为真时不再评估右侧")
    void orShortCircuits() {
        var asked = new ArrayList<String>();
        var e = ConditionExpr.parse("a || b");
        e.test(spec -> {
            asked.add(spec);
            return true;           // a 为真，应短路
        });
        assertEquals(List.of("a"), asked, "a 为真即短路，不该再问 b");
    }

    @Test
    @DisplayName("多词定义不被运算符切碎：health-below 0.3 是一个整体叶子")
    void multiWordLeafStaysIntact() {
        var seen = new ArrayList<String>();
        // 左侧返回 true 才能走到第二个叶子；AND 本身仍会在第一个为假时短路
        ConditionExpr.parse("health-below 0.3 && has-target")
                .test(spec -> {
                    seen.add(spec);
                    return true;
                });
        assertEquals(List.of("health-below 0.3", "has-target"), seen);
    }

    @Test
    @DisplayName("取反")
    void negation() {
        assertFalse(ConditionExpr.parse("!a").test(truthy("a")));
        assertTrue(ConditionExpr.parse("!a").test(truthy()));
        assertTrue(ConditionExpr.parse("!!a").test(truthy("a")), "双重取反等价于不取反");
        assertFalse(ConditionExpr.parse("!!a").test(truthy()), "双重取反后仍为假");
    }

    @Test
    @DisplayName("缺右操作数时安全退化，不抛异常")
    void toleratesMalformedInput() {
        for (String bad : List.of("a &&", "a ||", "&& b", "(a", "a)", "!", "a && && b")) {
            // 不要求正确，只要求不抛
            ConditionExpr.parse(bad).test(truthy("a", "b"));
        }
    }

    @Test
    @DisplayName("五段 AND 是合法语法，按左结合归约为独立叶子")
    void longAndIsNotAMismatch() {
        var seen = new ArrayList<String>();
        ConditionExpr.parse("a && b && c && d && e").test(spec -> {
            seen.add(spec);
            return true;
        });
        assertEquals(List.of("a", "b", "c", "d", "e"), seen);
    }

    @Test
    @DisplayName("无法归约的畸形文法整段下传给上层，而不是静默截断")
    void fallsBackToWholeLeafOnMismatch() {
        var seen = new ArrayList<String>();
        // 孤立的右括号让文法无法归约：应把整段当作一个叶子交给上层
        ConditionExpr.parse("a && ) b").test(spec -> {
            seen.add(spec);
            return false;
        });
        assertEquals(1, seen.size(), "应只下一个整体叶子: " + seen);
        assertTrue(seen.get(0).contains("a") && seen.get(0).contains("b"),
                "叶子应含整段原文: " + seen);
    }

    @Test
    @DisplayName("parseAll：多行默认 AND")
    void parseAllAndSemantics() {
        var e = ConditionExpr.parseAll(List.of("a", "b"));
        assertTrue(e.test(truthy("a", "b")));
        assertFalse(e.test(truthy("a")));
    }

    @Test
    @DisplayName("parseAll 跳过空行与 null")
    void parseAllSkipsBlanks() {
        // 用 Arrays.asList 而非 List.of：后者拒绝 null 元素，构造不出待测输入
        var e = ConditionExpr.parseAll(java.util.Arrays.asList("a", "", "  ", null, "b"));
        assertTrue(e.test(truthy("a", "b")));
        assertFalse(e.test(truthy("a")));
    }

    @Test
    @DisplayName("parseAll 空列表恒真（没写条件即不加限制）")
    void parseAllEmptyIsTrue() {
        assertTrue(ConditionExpr.parseAll(List.of()).test(truthy()));
        assertTrue(ConditionExpr.parseAll(null).test(truthy()));
    }

    @Test
    @DisplayName("and 空列表恒真，or 空列表恒假")
    void emptySemantics() {
        assertTrue(ConditionExpr.and(List.of()).test(truthy()));
        assertFalse(ConditionExpr.or(List.of()).test(truthy()));
    }

    @Test
    @DisplayName("MythicMobs 的 ? 前缀被剥掉")
    void stripsQuestionMarkPrefix() {
        var seen = new ArrayList<String>();
        ConditionExpr.parse("?health-below").test(spec -> {
            seen.add(spec);
            return false;
        });
        assertEquals(List.of("health-below"), seen);
    }

    @Test
    @DisplayName("~onSpawn:SkillName 转成 cast-skill 调用")
    void convertsTildeTriggerSyntax() {
        assertEquals("cast-skill Ember",
                ConditionExpr.normalizeLeaf("~onSpawn:Ember"));
        assertEquals("cast-skill Ember",
                ConditionExpr.normalizeLeaf("~on-spawn:Ember"));
    }

    @Test
    @DisplayName("非触发器冒号写法不被误转")
    void leavesOtherColonSyntaxAlone() {
        assertEquals("some:thing", ConditionExpr.normalizeLeaf("some:thing"));
    }

    @Test
    @DisplayName("嵌套组合：括号套括号与混合运算符")
    void deeplyNested() {
        var e = ConditionExpr.parse("(a || b) && !(c && d)");
        assertTrue(e.test(truthy("a")));
        assertTrue(e.test(truthy("b")));
        assertFalse(e.test(truthy("a", "c", "d")), "c&&d 为真时 !(c&&d) 为假，整体为假");
        assertTrue(e.test(truthy("a", "c")), "c&&d 为假时取反为真，且 a 成立，整体为真");
    }

    @Test
    @DisplayName("求值器抛异常时按 false 处理，不外泄")
    void swallowsEvalException() {
        var e = ConditionExpr.parse("a");
        assertFalse(e.test(spec -> {
            throw new IllegalStateException("炸了");
        }));
    }

    @Test
    @DisplayName("求值器为 null 时返回 false")
    void nullEvalIsFalse() {
        assertFalse(ConditionExpr.parse("a").test((ConditionExpr.Eval) null));
    }

    @Test
    @DisplayName("tokenize 保留括号与运算符，引号整体成词")
    void tokenizeKeepsOperators() {
        assertEquals(List.of("(", "a", "||", "b", ")"),
                ConditionExpr.tokenize("(a || b)"));
        assertEquals(List.of("health-below 0.3"), ConditionExpr.tokenize("\"health-below 0.3\""));
    }
}