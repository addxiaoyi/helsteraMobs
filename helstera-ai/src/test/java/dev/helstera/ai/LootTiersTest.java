package dev.helstera.ai;

import dev.helstera.ai.loot.DropTable;
import dev.helstera.ai.loot.LootService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分层权重与击杀者档位门槛的测试。
 *
 * <p>用固定种子的 {@link Random} 保证可重复：概率逻辑出错时必须能复现，
 * 否则这类测试会变成随机通过、随机失败的噪音。</p>
 */
class LootTiersTest {

    private static DropTable parse(String yml, List<String> problems) {
        return DropTable.parse("t", YamlConfiguration.loadConfiguration(new StringReader(yml)), problems);
    }

    // 插件参数为 null：rollPlan 是纯函数，不触达插件
    private static LootService service() {
        return new LootService(null);
    }

    // ---- 解析 ----

    @Test
    @DisplayName("解析分层权重，非法档位被跳过并记录")
    void parsesTiers() {
        List<String> problems = new ArrayList<>();
        DropTable t = parse("""
                entries:
                  - item: DIAMOND
                    tiers:
                      - {weight: 70, amount-min: 1, amount-max: 1}
                      - {weight: 25, amount-min: 2, amount-max: 3}
                      - {weight: 5,  amount-min: 5, amount-max: 5}
                      - {weight: 0,  amount-min: 9, amount-max: 9}
                """, problems);

        assertEquals(1, t.entries().size());
        DropTable.Entry e = t.entries().get(0);
        assertTrue(e.hasTiers());
        assertEquals(3, e.tiers().size(), "weight<=0 的档位应被剔除");
        assertTrue(problems.stream().anyMatch(p -> p.contains("weight<=0")),
                "剔除非法档位必须留痕，否则配置作者不知道为什么少了一档");
    }

    @Test
    @DisplayName("只写一个档位时（写成映射而非列表）也能解析")
    void singleTierAsMap() {
        DropTable t = parse("""
                entries:
                  - item: DIAMOND
                    tiers: {weight: 1, amount-min: 4, amount-max: 4}
                """, new ArrayList<>());
        assertTrue(t.entries().get(0).hasTiers(), "单档写成映射时不应被静默丢弃");
        assertEquals(1, t.entries().get(0).tiers().size());
    }

    @Test
    @DisplayName("min-tier-level 非正数时忽略门槛并留痕")
    void negativeTierIgnored() {
        List<String> problems = new ArrayList<>();
        DropTable t = parse("""
                entries:
                  - item: DIAMOND
                    min-tier-level: -3
                """, problems);
        assertNull(t.entries().get(0).minTierLevel(), "非法门槛应视为未设置");
        assertTrue(problems.stream().anyMatch(p -> p.contains("min-tier-level")));
    }

    @Test
    @DisplayName("未配置 tiers 时保持旧构造语义")
    void noTiersKeepsLegacy() {
        DropTable t = parse("""
                entries:
                  - item: DIAMOND
                    amount-min: 2
                    amount-max: 4
                """, new ArrayList<>());
        DropTable.Entry e = t.entries().get(0);
        assertFalse(e.hasTiers());
        assertEquals(2, e.amountMin());
        assertEquals(4, e.amountMax());
        assertNull(e.minTierLevel());
    }

    // ---- 权重抽取 ----

    @Test
    @DisplayName("权重 70/25/5 时高档位很少出现")
    void weightedDistribution() {
        DropTable t = parse("""
                entries:
                  - item: DIAMOND
                    tiers:
                      - {weight: 70, amount-min: 1, amount-max: 1}
                      - {weight: 25, amount-min: 2, amount-max: 3}
                      - {weight: 5,  amount-min: 5, amount-max: 5}
                """, new ArrayList<>());

        Map<Integer, Integer> hist = new HashMap<>();
        Random rnd = new Random(20260903L);
        for (int i = 0; i < 20000; i++) {
            List<LootService.Hit> hits = service().rollPlan(t, 0, rnd);
            assertEquals(1, hits.size());
            hist.merge(hits.get(0).amount(), 1, Integer::sum);
        }

        int one = hist.getOrDefault(1, 0);
        int five = hist.getOrDefault(5, 0);
        assertTrue(one > 12000, "1 个档应占约七成，实际 " + one);
        assertTrue(five < 1600, "5 个档应占约 5%，实际 " + five);
        assertTrue(one > five * 5, "低档与高档应有数量级差异");
    }

    @Test
    @DisplayName("负权重档位被剔除，回退到原有均匀区间而非丢失掉落")
    void invalidWeightFallsBackToLegacyRange() {
        DropTable t = parse("""
                entries:
                  - item: DIAMOND
                    amount-min: 2
                    amount-max: 2
                    tiers:
                      - {weight: -5, amount-min: 9, amount-max: 9}
                """, new ArrayList<>());

        DropTable.Entry e = t.entries().get(0);
        assertFalse(e.hasTiers(), "负权重档位应被剔除");
        // 关键：剔除后仍能按 amount 区间掉落，而不是整条失效
        assertEquals(2, service().rollPlan(t, 0, new Random(1)).get(0).amount());
    }

    @Test
    @DisplayName("空节不抛异常，按空表处理")
    void nullSectionTolerated() {
        DropTable t = DropTable.parse("t", null, new ArrayList<>());
        assertTrue(t.isEmpty(), "null 节应得到空表而不是抛异常");
        assertTrue(service().rollPlan(t, 0, new Random(1)).isEmpty());
    }

    @Test
    @DisplayName("未配置 tiers 时数量仍落在原区间内")
    void legacyAmountRange() {
        DropTable t = parse("""
                entries:
                  - item: DIAMOND
                    amount-min: 3
                    amount-max: 5
                """, new ArrayList<>());
        Random rnd = new Random(7);
        for (int i = 0; i < 500; i++) {
            int amt = service().rollPlan(t, 0, rnd).get(0).amount();
            assertTrue(amt >= 3 && amt <= 5, "实际 " + amt);
        }
    }

    // ---- 击杀者门槛 ----

    @Test
    @DisplayName("档位低于门槛时该条不掉落")
    void tierGateBlocks() {
        DropTable t = parse("""
                entries:
                  - item: NETHERITE_INGOT
                    min-tier-level: 5
                """, new ArrayList<>());
        Random rnd = new Random(3);
        assertTrue(service().rollPlan(t, 0, rnd, 1).isEmpty(), "档位 1 应被挡下");
        assertEquals(1, service().rollPlan(t, 0, rnd, 5).size(), "档位 5 应放行");
        assertEquals(1, service().rollPlan(t, 0, rnd, 9).size());
    }

    @Test
    @DisplayName("击杀者未知时不套用门槛")
    void unknownKillerIgnoresGate() {
        DropTable t = parse("""
                entries:
                  - item: NETHERITE_INGOT
                    min-tier-level: 99
                """, new ArrayList<>());
        // killerTier=-1 表示拿不到档位，不应把掉落全吞掉
        assertEquals(1, service().rollPlan(t, 0, new Random(3), -1).size(),
                "拿不到击杀者档位时不应误伤门槛掉落");
    }

    @Test
    @DisplayName("门槛只作用于对应条目，同表其它条目不受影响")
    void gateIsPerEntry() {
        DropTable t = parse("""
                entries:
                  - item: DIAMOND
                  - item: NETHERITE_INGOT
                    min-tier-level: 10
                """, new ArrayList<>());
        List<LootService.Hit> hits = service().rollPlan(t, 0, new Random(11), 1);
        assertEquals(1, hits.size());
        assertEquals("DIAMOND", hits.get(0).entry().itemId());
    }

    @Test
    @DisplayName("旧三参重载不套门槛，向后兼容")
    void legacyOverloadCompatible() {
        DropTable t = parse("""
                entries:
                  - item: DIAMOND
                    min-tier-level: 50
                """, new ArrayList<>());
        assertEquals(1, service().rollPlan(t, 0, new Random(5)).size(),
                "既有调用方走三参重载，行为不应改变");
    }

    // ---- 五参重载：合并玩家档位与 mob 等级 ----

    @Test
    @DisplayName("玩家档位与 mob 等级取最大值作为有效档位")
    void fiveArgUsesMaxTier() {
        DropTable t = parse("""
                entries:
                  - item: DIAMOND
                    min-tier-level: 3
                  - item: NETHERITE_INGOT
                    min-tier-level: 7
                """, new ArrayList<>());
        Random rnd = new Random(42);
        // player=3, mob=5 → effective=5: DIAMOND(门槛3)放行, NETHERITE(门槛7)拦截
        List<LootService.Hit> hits = service().rollPlan(t, 0, rnd, 5);
        assertTrue(hits.stream().anyMatch(h -> h.entry().itemId().equals("DIAMOND")));
        assertFalse(hits.stream().anyMatch(h -> h.entry().itemId().equals("NETHERITE_INGOT")));
    }

    @Test
    @DisplayName("mob 等级为 0 时只看玩家档位（旧行为不变）")
    void mobZeroFallsBackToPlayerTier() {
        DropTable t = parse("""
                entries:
                  - item: GOLD_INGOT
                    min-tier-level: 2
                """, new ArrayList<>());
        // player=1, mob=0 → effective=1: 门槛 2 不满足
        assertEquals(0, service().rollPlan(t, 0, new Random(1), 1).size());
        // player=3, mob=0 → effective=3: 门槛 2 满足
        assertEquals(1, service().rollPlan(t, 0, new Random(1), 3).size());
    }

    @Test
    @DisplayName("两者都未知（-1, 0）时有效档位为 -1，门槛全部放行")
    void bothUnknownAllowsAll() {
        DropTable t = parse("""
                entries:
                  - item: DIAMOND
                    min-tier-level: 99
                """, new ArrayList<>());
        // playerTier=-1, mobLevel=0 → effective=-1: 门槛跳过
        assertEquals(1, service().rollPlan(t, 0, new Random(3), -1).size());
    }

    @Test
    @DisplayName("玩家档位未知但 mob 有等级时用 mob 等级")
    void mobLevelWinsWhenPlayerUnknown() {
        DropTable t = parse("""
                entries:
                  - item: DIAMOND
                    min-tier-level: 5
                """, new ArrayList<>());
        // playerTier=-1, mobLevel=5 → effective=5: 门槛满足
        assertEquals(1, service().rollPlan(t, 0, new Random(3), 5).size());
        // playerTier=-1, mobLevel=3 → effective=3: 门槛不满足
        assertEquals(0, service().rollPlan(t, 0, new Random(3), 3).size());
    }
}