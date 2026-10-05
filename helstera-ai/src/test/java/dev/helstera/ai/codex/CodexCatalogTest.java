package dev.helstera.ai.codex;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图鉴目录检索测试。
 *
 * <p>关注两点：<b>顺序稳定</b>（模型扫描顺序依赖文件系统，而图鉴是给人看的列表，
 * 顺序不稳定会让每次打开长得都不一样）与<b>空值不炸</b>（配置字段缺失很常见，
 * 图鉴崩掉会连带整条命令不可用）。</p>
 */
class CodexCatalogTest {

    private static CodexEntry entry(String id, String name, String author) {
        return new CodexEntry(id, name, author, "1.0", id.split("/")[0],
                10, 2, List.of("idle"), 1.0, 1.0, 2.0, "helstera-v1");
    }

    private static CodexCatalog catalog() {
        return new CodexCatalog(List.of(
                entry("zebra/Zebra", "斑马", "alice"),
                entry("apple/Apple", "苹果", "bob"),
                entry("kingdom/King", "王座", "alice")));
    }

    @Test
    @DisplayName("按 id 排序，与加入顺序无关")
    void sortedByIdRegardlessOfInputOrder() {
        var forward = new CodexCatalog(List.of(
                entry("apple/A", "A", "x"), entry("zebra/Z", "Z", "x")));
        var backward = new CodexCatalog(List.of(
                entry("zebra/Z", "Z", "x"), entry("apple/A", "A", "x")));
        assertEquals(forward.all().toString(), backward.all().toString(),
                "同样集合、不同加入顺序，图鉴列表必须一致");
    }

    @Test
    @DisplayName("检索命中 id、名称、作者任一即可，且大小写不敏感")
    void searchMatchesAnyField() {
        var c = catalog();
        assertEquals(1, c.search("zebra").size(), "应命中 id");
        assertEquals(1, c.search("苹果").size(), "应命中名称");
        assertEquals(2, c.search("alice").size(), "作者应命中两条");
        assertEquals(1, c.search("ZEBRA").size());
        assertTrue(c.search("不存在").isEmpty());
    }

    @Test
    @DisplayName("空查询返回全部而非空")
    void blankQueryReturnsAll() {
        var c = catalog();
        assertEquals(c.size(), c.search(null).size());
        assertEquals(c.size(), c.search("   ").size());
    }

    @Test
    @DisplayName("find 对不存在与 null 均安全")
    void findIsSafe() {
        var c = catalog();
        assertNotNull(c.find("zebra/Zebra"));
        assertNull(c.find("nope"));
        assertNull(c.find(null));
    }

    @Test
    @DisplayName("按资源包分组")
    void groupedByPack() {
        var groups = catalog().byPack();
        assertEquals(3, groups.size());
        assertTrue(groups.containsKey("zebra"));
    }

    @Test
    @DisplayName("贫乏条目被单独挑出")
    void sparseEntriesListed() {
        var rich = entry("pack/rich", "正常", "x");
        var poor = new CodexEntry("pack/poor", "空壳", "x", "1", "pack",
                0, 0, List.of(), 1.0, 1.0, 1.0, "helstera-v1");
        assertEquals(List.of(poor), new CodexCatalog(List.of(rich, poor)).sparse());
    }

    @Test
    @DisplayName("空输入不炸")
    void emptyAndNullSourceSafe() {
        assertEquals(0, new CodexCatalog(List.of()).size());
        assertEquals(0, new CodexCatalog(null).size());
    }

    @Test
    @DisplayName("条目内空白字段归一为占位符")
    void entryNormalizesNulls() {
        var e = new CodexEntry("pack/x", null, null, null, "pack",
                1, 0, List.of(), 1.0, 1.0, 1.0, null);
        assertEquals("[未填]", e.name());
        assertEquals("[未填]", e.author());
        assertEquals("[未填]", e.version());
        assertEquals("[未填]", e.sourceFormat());
    }

    @Test
    @DisplayName("缩放与碰撞盒格式固定小数位，避免图鉴里混排")
    void numericFormattingStable() {
        var e = new CodexEntry("pack/x", "n", "a", "v", "pack",
                1, 1, List.of(), 1.0, 3.14159, 2.0, "f");
        assertEquals("1.00", e.scaleText());
        assertEquals("3.1×2.0", e.hitboxText());
    }
}