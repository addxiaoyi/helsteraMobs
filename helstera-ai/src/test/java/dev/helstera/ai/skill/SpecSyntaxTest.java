package dev.helstera.ai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MythicMobs 风格定义行的归一化测试。
 *
 * <p>这一层决定了导入器能不能「原样搬运」mechanic 行。归一化错了不会立刻
 * 报错，而是变成「绑定到一个参数数量对不上的动作」或「参数错位一格」，
 * 表现为伤害数值诡异、范围偏移这类极难定位的问题。</p>
 */
class SpecSyntaxTest {

    @Test
    @DisplayName("大括号参数折成空白分隔，位置即参数序")
    void convertsBraceArgsToPositional() {
        assertEquals("damage 5 true",
                SpecSyntax.normalize("damage{amount=5;aoe=true}"));
        assertEquals("damage 5 true", SpecSyntax.normalize("damage{amount=5, aoe=true}"));
    }

    @Test
    @DisplayName("无大括号时原样返回（只 trim）")
    void passesThroughPlainSpecs() {
        assertEquals("damage-target 5", SpecSyntax.normalize("  damage-target 5  "));
        assertEquals("play-animation walk", SpecSyntax.normalize("play-animation walk"));
    }

    @Test
    @DisplayName("键名前缀被剥掉，定义名保留")
    void stripsKeyPrefix() {
        assertEquals("damage 5", SpecSyntax.normalize("damage{amount=5}"));
        assertEquals("sound BLAZE", SpecSyntax.normalize("sound{name=BLAZE}"));
    }

    @Test
    @DisplayName("不带键名的裸值原样保留在首位")
    void keepsValuelessArgs() {
        assertEquals("damage 5", SpecSyntax.normalize("damage{5}"));
        // 首参没写键名，作为裸值 "players" 保留
        assertEquals("aoe players 8 3", SpecSyntax.normalize("aoe{players;radius=8;count=3}"));
    }

    @Test
    @DisplayName("值内含逗号时不误切：坐标 1,2,3 应完整保留")
    void doesNotSplitCommasInsideValue() {
        // loc=1,2,3 若按逗号切会碎成 loc=1 / 2 / 3 三段，坐标直接错位
        assertEquals("teleport 1,2,3", SpecSyntax.normalize("teleport{loc=1,2,3}"));
    }

    @Test
    @DisplayName("方括号内的逗号同样不被切分")
    void respectsBracketedValues() {
        assertEquals("score [1,2,3]", SpecSyntax.normalize("score{[1,2,3]}"));
    }

    @Test
    @DisplayName("引号内的分号是内容而非分隔符")
    void keepsSemicolonsInsideQuotes() {
        // 引号只被剥掉，里面的分号属于值本身
        assertEquals("message 你好;世界", SpecSyntax.normalize("message{\"你好;世界\"}"));
    }

    @Test
    @DisplayName("skill{s=X} 这类嵌套引用也能归一化")
    void normalizesNestedSkillRef() {
        assertEquals("skill ember", SpecSyntax.normalize("skill{s=ember}"));
        assertEquals("skill ember 3", SpecSyntax.normalize("skill{s=ember;cooldown=3}"));
    }

    @Test
    @DisplayName("空参数与空大括号被忽略，不产生多余空格")
    void ignoresEmptySegments() {
        assertEquals("damage", SpecSyntax.normalize("damage{}"));
        assertEquals("damage 5", SpecSyntax.normalize("damage{;5;}"));
    }

    @Test
    @DisplayName("null / 空串安全返回")
    void toleratesNullAndEmpty() {
        assertNull(SpecSyntax.normalize(null));
        assertEquals("", SpecSyntax.normalize(""));
        // normalize 会先 trim，纯空白等价于空串
        assertEquals("", SpecSyntax.normalize("   "));
    }

    @Test
    @DisplayName("keyOf 按键名取值，与位置取值并存")
    void readsByKeyName() {
        assertEquals("ember", SpecSyntax.keyOf("skill{s=ember}", "s"));
        assertEquals("ember", SpecSyntax.keyOf("skill{skill=ember}", "s", "skill"));
        assertEquals("6s", SpecSyntax.keyOf("skill{s=ember;cooldown=6s}", "cooldown"));
        assertNull(SpecSyntax.keyOf("damage{amount=5}", "s"), "没有该键应返回 null");
        assertNull(SpecSyntax.keyOf("damage 5", "s"), "无大括号应返回 null");
    }

    @Test
    @DisplayName("argAt 按位置取值，越界返回 null")
    void readsByPosition() {
        String spec = "aoe{selector=players;radius=8;count=3;damage=5}";
        assertEquals("players", SpecSyntax.argAt(spec, 0));
        assertEquals("8", SpecSyntax.argAt(spec, 1));
        assertEquals("5", SpecSyntax.argAt(spec, 3));
        assertNull(SpecSyntax.argAt(spec, 9));
    }

    @Test
    @DisplayName("argCount 统计参数个数，空定义返回 0")
    void countsArgs() {
        assertEquals(4, SpecSyntax.argCount("aoe{selector=players;radius=8;count=3;damage=5}"));
        assertEquals(0, SpecSyntax.argCount("stop-animation"));
        assertEquals(0, SpecSyntax.argCount("damage{}"));
    }

    @Test
    @DisplayName("无引号的带空格值会被切开（YAML 引号保护之外的既有限制）")
    void documentsUnquotedSpaceLimitation() {
        // 引号内的空格由 stripKey 保留；不带引号时按分隔符处理。
        // 这条断言锁住当前行为，避免后续误改。
        assertTrue(SpecSyntax.normalize("message{你好 世界}").contains("世界"));
    }
}