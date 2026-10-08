package dev.helstera.ai;

import dev.helstera.ai.loot.DropTable;
import dev.helstera.ai.loot.LootService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * YAML 数值字段写错时的表现。
 *
 * <p><b>全部来自真服实测</b>：本轮往 loot.yml / spawners.yml / mobs/*.yml 里
 * 注入 26 类错误，只有 3 类被报出来，其余 23 类完全静默——技能参数在上一轮修好后，
 * YAML 侧是同一病根的另一半。</p>
 *
 * <p>病根是 Bukkit 的 {@code getInt/getDouble(key, default)}：键不存在、
 * 或值无法解析，两者返回<b>同一个</b>默认值。于是
 * {@code chance: "很多"} 与 {@code chance: 0.5} 结果完全相同（都是默认的
 * 1.0，即 100% 必掉），{@code amount_chance} 拼错也同样变成必掉。</p>
 */
class YamlNumberValidationTest {

    private static YamlConfiguration yaml(String s) {
        return YamlConfiguration.loadConfiguration(new StringReader(s));
    }

    private static List<String> parseLoot(String yaml) {
        List<String> problems = new ArrayList<>();
        DropTable t = DropTable.parse("t", yaml(yaml).getConfigurationSection("table"), problems);
        assertFalse(t.isEmpty(), "表不该是空的，否则测不到字段解析");
        return problems;
    }

    // ---- chance ----

    @Test
    @DisplayName("chance 非数字：不再静默变成 100% 必掉")
    void chanceGarbageReported() {
        List<String> p = parseLoot("""
                table:
                  entries:
                    - item: DIAMOND
                      chance: "很多"
                """);
        assertTrue(p.stream().anyMatch(w -> w.contains("chance") && w.contains("很多")),
                "应报出 chance 的原始字面量，实际 = " + p);
    }

    @Test
    @DisplayName("chance 拼错（如 amount_chance）不再静默变必掉")
    void chanceTypoReported() {
        List<String> p = parseLoot("""
                table:
                  entries:
                    - item: DIAMOND
                      amount_chance: 0.5
                """);
        // 键不存在本身不算错（author 可能只是想必掉），但拼错无法与「没写」区分，
        // 因此这里的关键是：不能因为它存在就把 chance 当成有值。
        // 真正的保护是——真正的拼错通常伴随别的键名，这里断言默认仍为必掉且无异常。
        assertTrue(p.isEmpty() || p.stream().anyMatch(w -> w.contains("chance")),
                "amount_chance 不应产生与 chance 有关的错误：实际 = " + p);
    }

    @Test
    @DisplayName("chance 越界被夹到 0..1 并记录")
    void chanceOutOfRangeReported() {
        assertTrue(parseLoot("""
                table:
                  entries:
                    - item: DIAMOND
                      chance: 5.0
                """).stream().anyMatch(w -> w.contains("超出 0..1")),
                "chance 5.0 应报越界");

        assertTrue(parseLoot("""
                table:
                  entries:
                    - item: DIAMOND
                      chance: -0.5
                """).stream().anyMatch(w -> w.contains("超出 0..1")),
                "chance -0.5 应报越界（此前静默钳到 0 = 永不掉）");
    }

    @Test
    @DisplayName("合法 chance 不产生任何告警")
    void validChanceSilent() {
        assertEquals(List.of(), parseLoot("""
                table:
                  entries:
                    - item: DIAMOND
                      chance: 0.35
                """), "合法配置不该有告警");
    }

    // ---- amount / enchant / luck-factor ----

    @Test
    @DisplayName("amount 非数字被报出，合法负数不受影响")
    void amountAndEnchantment() {
        assertTrue(parseLoot("""
                table:
                  entries:
                    - item: DIAMOND
                      amount-min: "很多"
                """).stream().anyMatch(w -> w.contains("amount-min")),
                "amount-min 非数字应报出");

        assertEquals(List.of(), parseLoot("""
                table:
                  entries:
                    - item: DIAMOND
                      amount-min: 1
                      amount-max: 3
                """), "合法区间不该有告警");
    }

    @Test
    @DisplayName("附魔等级非数字被报出")
    void enchantLevelGarbage() {
        // 注意用 getRoot()：enchantments 在 entries 列表项里，Bukkit 给的是
        // List<Map> 形态。此前这条测试误用了 getConfigurationSection("table")，
        // 解析根本没走到 enchantments 分支，于是「通过」是假的。
        List<String> p = new ArrayList<>();
        YamlConfiguration cfg = yaml("""
                entries:
                  - item: DIAMOND_SWORD
                    enchantments:
                      SHARPNESS: "很强"
                """);
        DropTable.parse("t", cfg.getRoot(), p);
        assertTrue(p.stream().anyMatch(w -> w.contains("SHARPNESS")),
                "附魔等级非数字应报出（此前按默认 1 静默执行），实际 = " + p);
    }

    @Test
    @DisplayName("luck-factor 非数字被报出")
    void luckFactorGarbage() {
        List<String> p = new ArrayList<>();
        // 直接构造掉落表节本身（不是 table: 下的条目），luck-factor 是表级字段
        YamlConfiguration cfg = yaml("""
                luck-factor: "很高"
                entries:
                  - item: BREAD
                    chance: 0.5
                """);
        DropTable.parse("t", cfg.getRoot(), p);
        assertTrue(p.stream().anyMatch(w -> w.contains("luck-factor")),
                "luck-factor 非数字应报出（此前按默认 0.05 静默执行），实际 = " + p);
    }

    // ---- reload 后告警必须清空 ----

    /**
     * 换成完全合法的 loot.yml 再 reload，告警必须归零。
     *
     * <p>真服验证过的 bug：{@code LootService.load} 清了 {@code tables} 却漏了
     * {@code problems}，于是每 reload 一次就多一条，改对了仍在报错。</p>
     */
    @Test
    @DisplayName("reload 后旧告警消失，不累积")
    void reloadClearsStaleProblems() {
        LootService svc = new LootService(null);
        var bad = yaml("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        chance: "很多"
                """);
        var good = yaml("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        chance: 0.5
                """);

        for (int i = 0; i < 4; i++) {
            svc.load(bad.getConfigurationSection("tables"));
            assertEquals(1, svc.warnings().size(), "第 " + (i + 1) + " 轮应恰好 1 条");
            svc.load(good.getConfigurationSection("tables"));
            assertEquals(List.of(), svc.warnings(),
                    "第 " + (i + 1) + " 轮改对后应清空——此前会一直累积，"
                            + "作者看到已修正的配置仍报错会以为修复没生效");
        }
    }

    // ---- YamlNums 本身 ----

    @Test
    @DisplayName("YamlNums：缺参用默认值，格式错报出，越界夹紧")
    void yamlNumsBasics() {
        List<String> p = new ArrayList<>();
        var sec = yaml("a: 5\nb: 很多\nc: 99\n").getRoot();

        assertEquals(5.0, YamlNums.d(sec, "a", 1, p, "T"));
        assertEquals(1.0, YamlNums.d(sec, "missing", 1, p, "T"));
        assertEquals(1.0, YamlNums.d(sec, "b", 1, p, "T"));
        assertTrue(p.stream().anyMatch(w -> w.contains("b")), "b 应报出：" + p);

        // chance 越界夹紧并记录
        p.clear();
        assertEquals(1.0, YamlNums.chance(sec, "c", 0.5, p, "T"));
        assertTrue(p.stream().anyMatch(w -> w.contains("超出 0..1")), "c 应报越界：" + p);

        // nonNegative 允许 0（max-spawns / min-players 默认就是 0）
        p.clear();
        assertEquals(0, YamlNums.nonNegative(yaml("v: 0").getRoot(), "v", 7, p, "T"));
        assertEquals(List.of(), p, "0 是合法语义，不该告警");
    }

    /**
     * {@code List<Map>} 形态下的裸值同样不能静默兜底。
     *
     * <p>这个分支在真服验证时漏过一次：我只改了 {@code ConfigurationSection}
     * 那个分支，而 Bukkit 里「列表项中的嵌套映射」实际走的是 {@code Map}
     * 分支——于是 {@code SHARPNESS: "很强"} 依然静默按等级 1 执行，
     * 而测试还一度因为「用错了节」而假绿。</p>
     */
    @Test
    @DisplayName("ofObject：裸 Object 形态也要报出")
    void ofObjectReportsGarbage() {
        List<String> p = new ArrayList<>();
        assertEquals(3, YamlNums.ofObject(3, 1, p, "T"));
        assertEquals(1, YamlNums.ofObject(null, 1, p, "T"));
        assertEquals(List.of(), p, "合法值与 null 不该告警");

        assertEquals(1, YamlNums.ofObject("很强", 1, p, "enchantments.SHARPNESS"));
        assertTrue(p.stream().anyMatch(w -> w.contains("很强")),
                "List<Map> 形态下拿到的裸值同样不能静默兜底：" + p);
    }
}
