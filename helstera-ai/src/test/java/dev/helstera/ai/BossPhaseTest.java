package dev.helstera.ai;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 血量阶段的区间语义测试。
 *
 * <p>重点是<b>分界值</b>：相邻段在边界上必须恰好命中一段，既不重叠也不留空。
 * 这是左闭右开设计存在的原因，测试的意义就在于此。</p>
 */
class BossPhaseTest {

    private static BossPhase p(String id, double lo, double hi) {
        return new BossPhase(id, lo, hi, "", List.of(), List.of());
    }

    /** 常见的四段 Boss 配置：100-75 / 75-50 / 50-25 / 25-0 */
    private static List<BossPhase> fourSegments() {
        return List.of(
                p("p4", 0, 25),
                p("p3", 25, 50),
                p("p2", 50, 75),
                p("p1", 75, 100));
    }

    @Test
    @DisplayName("分界血量恰好命中一段，不重叠也不留空")
    void boundariesHitExactlyOne() {
        List<BossPhase> phases = fourSegments();
        for (double hp : new double[]{100, 75, 50, 25, 0}) {
            int hits = 0;
            for (BossPhase ph : phases) {
                if (ph.matches(hp)) hits++;
            }
            assertEquals(1, hits, "血量 " + hp + " 应恰好命中一段，实际 " + hits);
        }
    }

    @Test
    @DisplayName("满血命中首段，不落空")
    void fullHealthHitsFirstSegment() {
        BossPhase first = BossPhase.resolve(fourSegments(), 100.0);
        assertNotNull(first, "满血时不能无阶段");
        assertEquals("p1", first.id());
    }

    @Test
    @DisplayName("血量下降逐段推进")
    void walksDownSegments() {
        List<BossPhase> phases = fourSegments();
        assertEquals("p1", BossPhase.resolve(phases, 90).id());
        assertEquals("p2", BossPhase.resolve(phases, 60).id());
        assertEquals("p3", BossPhase.resolve(phases, 30).id());
        assertEquals("p4", BossPhase.resolve(phases, 10).id());
    }

    @Test
    @DisplayName("区间上下颠倒自动交换，不丢弃该段")
    void swappedRangeStillUsable() {
        BossPhase ph = p("x", 75, 25);
        assertEquals(25, ph.minPercent());
        assertEquals(75, ph.maxPercent());
        assertTrue(ph.matches(50));
    }

    @Test
    @DisplayName("越界血量被夹到 0~100")
    void outOfRangeClamped() {
        BossPhase ph = p("x", 0, 100);
        assertTrue(ph.matches(-50), "负血量应夹到 0");
        assertTrue(ph.matches(500));
        assertFalse(ph.matches(Double.NaN), "NaN 不应命中任何段");
    }

    @Test
    @DisplayName("未配置阶段时 resolve 返回 null")
    void noPhasesReturnsNull() {
        assertNull(BossPhase.resolve(List.of(), 50));
        assertNull(BossPhase.resolve(null, 50));
    }

    @Test
    @DisplayName("区间有空隙时不命中任何段")
    void gapHitsNothing() {
        // 故意留 40~60 空隙
        List<BossPhase> phases = List.of(p("a", 0, 40), p("b", 60, 100));
        assertNull(BossPhase.resolve(phases, 50), "配置留空隙时不该硬套某一段");
    }

    // ---- 解析 ----

    @Test
    @DisplayName("从 YAML 解析四阶段配置")
    void parsesFromYaml() {
        String yml = """
                phases:
                  - id: enraged
                    min: 0
                    max: 25
                    announce: "&cBoss 狂暴了！"
                    on-enter: [set-scale 1.2]
                  - id: phase2
                    min: 25
                    max: 75
                  - id: phase1
                    min: 75
                    max: 100
                """;
        var sec = YamlConfiguration.loadConfiguration(new StringReader(yml));
        List<String> problems = new ArrayList<>();
        List<BossPhase> phases = BossPhase.parseList(sec, problems);

        assertEquals(3, phases.size());
        assertTrue(problems.isEmpty(), "不应有解析问题: " + problems);
        // 按 id 排序，输出顺序稳定
        assertEquals("enraged", phases.get(0).id());
        assertEquals("&cBoss 狂暴了！", phases.get(0).announce());
        assertEquals(List.of("set-scale 1.2"), phases.get(0).onEnter());
        assertEquals("enraged", BossPhase.resolve(phases, 10).id());
    }

    @Test
    @DisplayName("只写一段（映射而非列表）也能解析")
    void parsesSinglePhaseAsMap() {
        var sec = YamlConfiguration.loadConfiguration(
                new StringReader("phases: {id: only, min: 0, max: 100}"));
        List<BossPhase> phases = BossPhase.parseList(sec, new ArrayList<>());
        assertEquals(1, phases.size(), "单段写成映射时不应被静默丢弃");
    }

    @Test
    @DisplayName("缺少 id 的阶段被跳过并留痕")
    void skipsPhaseWithoutId() {
        var sec = YamlConfiguration.loadConfiguration(
                new StringReader("phases:\n  - min: 0\n    max: 50\n"));
        List<String> problems = new ArrayList<>();
        List<BossPhase> phases = BossPhase.parseList(sec, problems);
        assertEquals(0, phases.size());
        assertTrue(problems.stream().anyMatch(p -> p.contains("id")), "应说明缺什么");
    }

    @Test
    @DisplayName("档案解析时装载阶段，复制构造一并复制")
    void profileCarriesPhases() {
        var sec = YamlConfiguration.loadConfiguration(new StringReader("""
                phases:
                  - id: a
                    min: 0
                    max: 100
                """));
        AiProfile p = AiProfile.fromSection("boss", sec);
        assertEquals(1, p.phases.size());

        AiProfile copy = new AiProfile(p);
        assertEquals(1, copy.phases.size(), "复制构造必须带上阶段，否则派生档案会丢 Boss 逻辑");
    }
}