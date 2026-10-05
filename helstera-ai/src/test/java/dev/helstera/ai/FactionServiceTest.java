package dev.helstera.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阵营表测试。
 *
 * <p>重点守两件「写错了不会报错、只会让战斗变得莫名其妙」的事：
 * <b>同盟的对称化</b>与<b>未归队的语义</b>。</p>
 *
 * <p>对称化尤其重要：作者只需在一侧写 allies。若不自动对称，
 * 「A 不打 B，但 B 还打 A」这种单向仇恨会长期存在，而单看任一方向
 * 都像配置正确，现场几乎无从定位。</p>
 *
 * <p>未归队语义：档案不写 faction 意味着「与所有阵营敌对」。若实现成
 * 「与所有阵营结盟」，会给全部存量配置套上一层没写过的免伤，
 * 表现为 Boss 打不动玩家且毫无日志。</p>
 *
 * <p>测试直接构造 {@link FactionService.Config} 而非 YAML 字符串：
 * 被测的同盟展开逻辑不该依赖 Bukkit 的配置解析器。</p>
 */
class FactionServiceTest {

    /** 便捷构造：名字 -> (显示名, 盟友列表)。 */
    private static Map<String, List<String>> allies(String... pairs) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], List.of(pairs[i + 1].split(",")));
        }
        return m;
    }

    private static Map<String, String> displays(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    private static FactionService load(String players, boolean bff,
                                       Map<String, String> displays,
                                       Map<String, List<String>> allies) {
        FactionService fs = new FactionService();
        fs.load(new FactionService.Config(players, bff, displays, allies));
        return fs;
    }

    @Test
    @DisplayName("同盟关系自动对称化：只在一侧声明 allies 即可")
    void allyRelationIsSymmetric() {
        FactionService fs = load(null, true,
                displays("guard", "王近卫", "kingdom", "王国", "bandit", "山贼"),
                allies("guard", "kingdom"));
        assertTrue(fs.alliesOf("guard").contains("kingdom"), "guard 应认 kingdom 为盟友");
        assertTrue(fs.alliesOf("kingdom").contains("guard"),
                "kingdom 应自动对称地认 guard，否则出现「A 不打 B 但 B 还打 A」");
        assertFalse(fs.alliesOf("guard").contains("bandit"), "未声明的应不同盟");
    }

    @Test
    @DisplayName("同盟闭包含自身，同名始终互相认识")
    void closureContainsSelf() {
        FactionService fs = load(null, true, displays("solo", "独行"), Map.of());
        assertEquals(Set.of("solo"), fs.alliesOf("solo"),
                "闭包应含自身，否则自己和自己都不算盟友");
    }

    @Test
    @DisplayName("未配置任何阵营时不产生告警")
    void emptyConfigNoWarnings() {
        FactionService fs = new FactionService();
        fs.load((FactionService.Config) null);
        assertTrue(fs.warnings().isEmpty());
        assertTrue(fs.names().isEmpty());
    }

    @Test
    @DisplayName("盟友指向未定义阵营时报错，且不把非法盟友塞进闭包")
    void danglingAllyWarnsAndIsIgnored() {
        FactionService fs = load(null, true, displays("guard", "近卫"),
                allies("guard", "nonexistent"));
        assertFalse(fs.warnings().isEmpty(), "应报出未定义盟友");
        assertEquals(Set.of("guard"), fs.alliesOf("guard"),
                "非法盟友不应进入闭包，否则凭空多出一个可打/免伤的阵营");
    }

    @Test
    @DisplayName("同盟链只展开一层，不做传递闭包")
    void allyChainIsNotTransitive() {
        // A<->B, B<->C：作者只声明了 A->B 与 B->C。
        // 若做传递闭包会让 A 与 C 也互为盟友——那是把「朋友的朋友」当自己人。
        FactionService fs = load(null, true,
                displays("a", "A", "b", "B", "c", "C"),
                allies("a", "b", "b", "c"));
        assertTrue(fs.alliesOf("b").contains("a"));
        assertTrue(fs.alliesOf("b").contains("c"));
        assertFalse(fs.alliesOf("a").contains("c"), "不应做传递闭包");
        assertFalse(fs.alliesOf("c").contains("a"), "不应做传递闭包");
    }

    @Test
    @DisplayName("players 指向未定义阵营时报错")
    void playerFactionDanglingWarns() {
        FactionService fs = load("nowhere", true, displays("real", "真"), Map.of());
        assertTrue(fs.warnings().stream().anyMatch(w -> w.contains("nowhere")),
                "玩家阵营应被校验，实际: " + fs.warnings());
    }

    @Test
    @DisplayName("players 为空白串时视为未归队，而不是一个名为空的阵营")
    void blankPlayerFactionIsUnaffiliated() {
        FactionService fs = load("   ", true, displays("real", "真"), Map.of());
        assertNull(fs.playerFaction(), "空白应归一为 null");
    }

    @Test
    @DisplayName("block-friendly-fire 默认为开，可显式关闭")
    void friendlyFireToggle() {
        assertTrue(load(null, true, Map.of(), Map.of()).blockFriendlyFire(),
                "显式 true 应生效");
        assertFalse(load(null, false, Map.of(), Map.of()).blockFriendlyFire(),
                "显式 false 应生效");
    }

    @Test
    @DisplayName("names 全部小写归一")
    void namesAreNormalized() {
        FactionService fs = load(null, true, displays("RoyalGuard", "近卫", "bandit", "山贼"), Map.of());
        assertTrue(fs.names().contains("royalguard"), "应归一为小写，实际: " + fs.names());
        assertTrue(fs.names().contains("bandit"));
    }

    @Test
    @DisplayName("display 为空时回退到阵营名，不返回 null")
    void displayFallsBackToName() {
        FactionService fs = load(null, true,
                displays("guard", "王近卫", "plain", " "), Map.of());
        assertEquals("王近卫", fs.displayOf("guard"));
        assertEquals("plain", fs.displayOf("plain"), "空白 display 应回退到阵营名");
    }

    @Test
    @DisplayName("未定义阵营的 displayOf 返回 null，供命令区分「不存在」")
    void displayOfUnknownIsNull() {
        assertNull(load(null, true, displays("guard", "近卫"), Map.of()).displayOf("nope"));
    }

    @Test
    @DisplayName("未登记的阵营名只与自己同盟，不会被当成空串一伙")
    void unregisteredFactionOnlyAlliesItself() {
        FactionService fs = load(null, true, displays("guard", "近卫"), Map.of());
        // 闭包里没有 bandit，判定应退化为「同名才同盟」，而非全体同伙
        assertEquals(Set.of("guard"), fs.alliesOf("guard"));
    }
}