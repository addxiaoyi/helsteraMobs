package dev.helstera.ai.codex;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图鉴排版测试。
 *
 * <p>重点是两件「客户端静默出错、服务端零报错」的事：<b>溢出截断</b>与
 * <b>全角宽度</b>。书本正文超出可用宽度会被客户端截掉，图鉴表现为「后半段全白」；
 * 而按 {@code String.length()} 算宽度会让中文行的实际宽度翻倍，于是每一行都被截。</p>
 */
class CodexBookLayoutTest {

    @Test
    @DisplayName("半角字符按 1 格计")
    void asciiWidth() {
        assertEquals(0, CodexBook.displayWidth(null));
        assertEquals(0, CodexBook.displayWidth(""));
        assertEquals(5, CodexBook.displayWidth("hello"));
    }

    @Test
    @DisplayName("中文与全角标点按 2 格计")
    void wideCharsCountDouble() {
        assertEquals(4, CodexBook.displayWidth("中文"));
        assertEquals(2, CodexBook.displayWidth("。"));
    }

    @Test
    @DisplayName("颜色码不占显示宽度")
    void colorCodesAreZeroWidth() {
        // 中文 = 2 个全角字 = 4 格；§a 不占格，故带色与不带色的结果必须相同。
        // （原断言写成 2 是算错了：把「全角」当成了 1 格。）
        assertEquals(CodexBook.displayWidth("中文"), CodexBook.displayWidth("§a中文"),
                "§a 不占格，否则带色标题会被误判超宽而提前换行");
        assertEquals(4, CodexBook.displayWidth("§a中文"));
    }

    @Test
    @DisplayName("折行后每行都不超出可用宽度")
    void wrappedLinesFitWidth() {
        String longLine = "这是一个非常长的中文标题用来测试折行逻辑是否正确处理全角字符的宽度计算";
        for (String l : CodexBook.wrap(longLine)) {
            assertTrue(CodexBook.displayWidth(l) <= CodexBook.COLUMNS,
                    "折行后仍超宽: [" + l + "] = " + CodexBook.displayWidth(l));
        }
    }

    @Test
    @DisplayName("颜色码不会被折行切断")
    void wrapKeepsColorCodesIntact() {
        for (String l : CodexBook.wrap("§b" + "中".repeat(40))) {
            for (int i = 0; i < l.length(); i++) {
                if (l.charAt(i) == '§') {
                    assertTrue(i + 1 < l.length(), "颜色码被折断: [" + l + "]");
                }
            }
        }
    }

    @Test
    @DisplayName("短行不被折开；空输入产出单行空串")
    void shortLineStaysOneLine() {
        assertEquals(1, CodexBook.wrap("短行").size());
        assertEquals(List.of(""), CodexBook.wrap(""));
        assertEquals(List.of(""), CodexBook.wrap(null));
    }

    @Test
    @DisplayName("分页不丢行，且每页不超行数上限")
    void paginationKeepsAllLines() {
        List<String> input = new ArrayList<>();
        for (int i = 0; i < 40; i++) input.add("行" + i);
        var pages = CodexBook.paginate(input);
        int total = 0;
        for (var p : pages) {
            assertTrue(p.size() <= CodexBook.LINES_PER_PAGE, "单页行数超限: " + p.size());
            total += p.size();
        }
        assertEquals(40, total, "分页不得丢行");
    }

    @Test
    @DisplayName("空内容仍产出一页，避免生成空白书")
    void emptyStillOnePage() {
        assertEquals(1, CodexBook.paginate(List.of()).size());
        assertEquals(1, CodexBook.paginate(null).size());
    }

    @Test
    @DisplayName("§n 换行标记被展开为独立行")
    void newlineMarkerExpands() {
        assertEquals(List.of("第一段", "第二段", "第三段"),
                CodexBook.paginate(List.of("第一段§n第二段§n第三段")).get(0));
    }

    @Test
    @DisplayName("条目渲染不产生超宽行")
    void renderEntryProducesFittingLines() {
        CodexEntry e = new CodexEntry("pack/dragon", "远古巨龙", "作者", "1.0",
                "pack", 24, 3, List.of("idle", "fly", "death"),
                1.25, 3.0, 4.0, "helstera-v1");
        for (var page : CodexBook.paginate(CodexBook.renderEntry(e))) {
            for (String l : page) {
                assertTrue(CodexBook.displayWidth(l) <= CodexBook.COLUMNS,
                        "条目渲染产生超宽行: [" + l + "]");
            }
        }
    }

    @Test
    @DisplayName("贫乏条目在页面上明确告警")
    void sparseEntryIsFlagged() {
        CodexEntry sparse = new CodexEntry("p/x", "空壳", "a", "1", "p",
                0, 0, List.of(), 1.0, 1.0, 1.0, "helstera-v1");
        assertTrue(sparse.isSparse());
        assertTrue(String.join("", CodexBook.renderEntry(sparse)).contains("⚠"));

        CodexEntry full = new CodexEntry("p/y", "正常", "a", "1", "p",
                10, 2, List.of("idle"), 1.0, 1.0, 1.0, "helstera-v1");
        assertFalse(full.isSparse());
        assertFalse(String.join("", CodexBook.renderEntry(full)).contains("⚠"));
    }

    @Test
    @DisplayName("未填写字段显示占位符而不是空白")
    void unsetFieldsShowPlaceholder() {
        CodexEntry e = new CodexEntry("p/z", null, "  ", null, "p",
                1, 0, List.of(), 1.0, 1.0, 1.0, null);
        assertTrue(String.join("", CodexBook.renderEntry(e)).contains("[未填]"));
    }
}