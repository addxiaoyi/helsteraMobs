package dev.helstera.ai;

import dev.helstera.ai.loot.LootService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 掉落系统的纯逻辑测试。
 *
 * <p>只测 {@link LootService#rollPlan}：概率判定、数量区间、幸运值影响、配置容错。
 * 这部分刻意做成不依赖 Bukkit 类型的纯函数，因此可以在没有运行中服务端的
 * 环境下验证。物品实体化（ItemStack/材质注册表）不在单测覆盖范围内。</p>
 */
class LootServiceTest {

    private static LootService service(String yaml) {
        LootService s = new LootService(null);
        s.load(YamlConfiguration.loadConfiguration(new StringReader(yaml)).getConfigurationSection("tables"));
        return s;
    }

    @Test
    @DisplayName("chance=1.0 必定命中，数量落在区间内")
    void alwaysHitsWhenChanceIsOne() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        amount-min: 2
                        amount-max: 4
                    """);
        for (int i = 0; i < 50; i++) {
            var hits = s.rollPlan("t", 0);
            assertEquals(1, hits.size());
            assertEquals("DIAMOND", hits.get(0).entry().itemId());
            assertTrue(hits.get(0).amount() >= 2 && hits.get(0).amount() <= 4,
                    "数量越界: " + hits.get(0).amount());
        }
    }

    @Test
    @DisplayName("chance=0 永不命中")
    void neverHitsWhenChanceIsZero() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        chance: 0
                    """);
        for (int i = 0; i < 100; i++) {
            assertTrue(s.rollPlan("t", 0).isEmpty(), "chance=0 不应命中");
        }
    }

    @Test
    @DisplayName("luck-scaling 随幸运值提高命中概率，且不会超过 1.0")
    void luckRaisesChanceWithinBounds() {
        LootService s = service("""
                tables:
                  t:
                    luck-factor: 0.1
                    entries:
                      - item: DIAMOND
                        chance: 0.5
                        luck-scaling: true
                    """);
        // 固定随机源 + 大量采样，统计幸运值对命中率的影响
        int low = hits(s, 0, 0L, 20000);
        int high = hits(s, 10, 0L, 20000);
        assertTrue(high > low, "幸运值应提高命中率: low=" + low + " high=" + high);
        assertTrue(high <= 20000);
    }

    @Test
    @DisplayName("luck-scaling=false 时幸运值不影响概率")
    void fixedChanceIgnoresLuck() {
        LootService s = service("""
                tables:
                  t:
                    luck-factor: 0.5
                    entries:
                      - item: DIAMOND
                        chance: 0.5
                        luck-scaling: false
                    """);
        assertEquals(hits(s, 0, 0L, 20000), hits(s, 20, 0L, 20000));
    }

    /**
     * 用固定种子的随机源连续取样。
     *
     * <p>刻意复用同一个 Random 实例而不是每次 {@code new Random(i)}：Java 的 Random
     * 对连续小种子的首个输出高度相关，用它做抽样会得到「全部同中或全部同失」的假象。</p>
     */
    private int hits(LootService s, double luck, long seed, int n) {
        Random rnd = new Random(seed);
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (!s.rollPlan(s.table("t"), luck, rnd).isEmpty()) c++;
        }
        return c;
    }

    @Test
    @DisplayName("多条独立投掷：各自判定，不是整表一起中或一起不中")
    void entriesRollIndependently() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: BREAD
                        chance: 0.5
                      - item: IRON_INGOT
                        chance: 0.5
                    """);
        Random rnd = new Random(12345);
        boolean sawOne = false, sawTwo = false;
        for (int i = 0; i < 200; i++) {
            int n = s.rollPlan(s.table("t"), 0, rnd).size();
            if (n == 1) sawOne = true;
            if (n == 2) sawTwo = true;
            if (sawOne && sawTwo) break;
        }
        assertTrue(sawOne && sawTwo, "应观察到只中一条与两条的情况");
    }

    @Test
    @DisplayName("amount-min > amount-max 时自动交换，不会产出负数或异常")
    void swapsInvertedAmountRange() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        amount-min: 5
                        amount-max: 2
                    """);
        var t = s.table("t");
        var e = t.entries().get(0);
        assertTrue(e.amountMin() <= e.amountMax(), "区间应被修正");
        for (int i = 0; i < 20; i++) {
            assertTrue(s.rollPlan(s.table("t"), 0, new Random(i)).get(0).amount() >= 2);
        }
    }

    @Test
    @DisplayName("缺少 item 的条目被跳过并告警，其余条目仍生效")
    void skipsEntryWithoutItem() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - chance: 1.0
                      - item: DIAMOND
                        chance: 1.0
                    """);
        assertEquals(1, s.table("t").entries().size());
        assertEquals(1, s.warnings().size());
        assertTrue(s.warnings().get(0).contains("缺少 item"));
    }

    @Test
    @DisplayName("空表与未知表名安全返回空，不抛异常")
    void toleratesEmptyAndUnknownTables() {
        LootService s = service("""
                tables:
                  empty:
                    entries: []
                """);
        assertTrue(s.rollPlan("empty", 0).isEmpty());
        assertTrue(s.rollPlan("nope", 0).isEmpty());
        assertTrue(s.rollPlan(null, 0).isEmpty());
    }

    @Test
    @DisplayName("null 节安全返回，不抛异常")
    void toleratesNullSection() {
        LootService s = new LootService(null);
        s.load(null);
        assertEquals(0, s.size());
        assertTrue(s.warnings().isEmpty());
    }

    @Test
    @DisplayName("表名大小写不敏感")
    void tableNamesAreCaseInsensitive() {
        LootService s = service("""
                tables:
                  BossLoot:
                    entries:
                      - item: DIAMOND
                """);
        assertNotNull(s.table("bossloot"));
        assertNotNull(s.table("BOSSLOOT"));
        assertEquals("bossloot", s.table("BossLoot").name());
    }

    @Test
    @DisplayName("附魔/名称/自定义模型数据被解析出来")
    void parsesEntryMetadata() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND_SWORD
                        name: "§b测试剑"
                        custom-model-data: 1001
                        glow: true
                        enchantments:
                          SHARPNESS: 3
                          looting: 2
                """);
        var e = s.table("t").entries().get(0);
        assertEquals("§b测试剑", e.displayName());
        assertEquals(1001, e.customModelData());
        assertTrue(e.glow());
        assertEquals(3, e.enchantments().get("SHARPNESS"));
        assertEquals(2, e.enchantments().get("LOOTING"), "附魔名应统一大写");
    }

    @Test
    @DisplayName("luck 负值被当作 0，不降低概率")
    void negativeLuckIsClamped() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        chance: 1.0
                """);
        assertFalse(s.rollPlan("t", -5.0).isEmpty());
    }

    @Test
    @DisplayName("单次堆叠不会超过原版上限逻辑：数量由配置决定")
    void respectsConfiguredAmount() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        amount-min: 64
                        amount-max: 64
                """);
        for (int i = 0; i < 10; i++) {
            assertEquals(64, s.rollPlan(s.table("t"), 0, new Random(i)).get(0).amount());
        }
    }

    @Test
    @DisplayName("tableNames 反映已装载的表")
    void listsLoadedTables() {
        LootService s = service("""
                tables:
                  a:
                    entries:
                      - item: BREAD
                  b:
                    entries:
                      - item: STONE
                """);
        assertEquals(2, s.size());
        assertTrue(s.tableNames().containsAll(List.of("a", "b")));
    }
}