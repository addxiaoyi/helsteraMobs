package dev.helstera.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SpotBugs 排除清单的防腐化审计。
 *
 * <p><b>为什么需要这个</b>：排除清单是所有静态分析工具配置里最危险的东西。
 * 加一个条目很容易，写错一个理由也很容易，而两者累积起来的结果是
 * 「工具显示全绿、实际什么都没查」——比没有工具更危险，因为它给人虚假的安全感。
 *
 * <p>本测试从三个角度守住它：</p>
 * <ol>
 *   <li><b>条目有理由</b>：每条 {@code <Match>} 上方必须有注释说明为什么。</li>
 *   <li><b>规模不膨胀</b>：条目数不超过阈值。超过说明有人在「顺手排除」
 *       而不是修问题。</li>
 *   <li><b>不整体屏蔽</b>：禁止出现 {@code <Match/>}（匹配全部）这种写法。</li>
 * </ol>
 *
 * <p>设计上刻意<b>不</b>校验具体排除了哪些 bug pattern——那需要枚举
 * SpotBugs 的全部类型名并跟踪其版本变化，脆弱且无意义。真正要守住的是
 * 「排除是有理由的、且没有把工具整个关掉」这两条。</p>
 */
class SpotbugsExcludeAuditTest {

    private static final Path FILTER = Path.of("..", "tools", "spotbugs-exclude.xml");

    /** 条目数上限。当前实际 7 条；留出余量但不允许无限增长。 */
    private static final int MAX_ENTRIES = 12;

    private static String read() throws IOException {
        Path p = FILTER.normalize();
        if (!Files.isRegularFile(p)) {
            throw new IOException("找不到排除清单: " + p.toAbsolutePath());
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 每条 <Match> 开标签。 */
    private static final Pattern MATCH = Pattern.compile("<Match>");

    @Test
    @DisplayName("排除清单存在且能被解析")
    void filterFileExists() throws IOException {
        String xml = read();
        assertTrue(xml.contains("<FindBugsFilter"), "缺少 FindBugsFilter 根元素");
        assertTrue(xml.contains("</FindBugsFilter>"), "FindBugsFilter 未闭合");
    }

    @Test
    @DisplayName("每条排除都有注释说明理由")
    void everyEntryHasAReason() throws IOException {
        String[] lines = read().split("\n");
        List<String> missing = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            if (!MATCH.matcher(lines[i]).find()) continue;
            // 从 <Match> 往上找一段紧邻的 XML 注释。
            // 紧邻上一行若是 "-->"，说明它属于一个以多行 <!-- ... --> 写成��
            // 注释块；据此判定「有理由」。若中间夹了别的 <Match>，则没写理由。
            boolean hasReason = false;
            for (int j = i - 1; j >= 0; j--) {
                String t = lines[j].trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("<Match>")) break;      // 上一个条目，中间没写理由
                if (t.endsWith("-->")) {
                    int k = j;
                    while (k >= 0 && !lines[k].trim().contains("<!--")) k--;
                    if (k >= 0) hasReason = true;
                }
                break;
            }
            if (!hasReason) missing.add("第 " + (i + 1) + " 行的 Match 缺少理由注释");
        }
        assertTrue(missing.isEmpty(),
                "以下 Match 条目上方没有理由注释：\n  " + String.join("\n  ", missing));
    }

    @Test
    @DisplayName("条目数不超过上限，防止清单无声膨胀")
    void entryCountStaysSmall() throws IOException {
        int n = countEntries();
        assertTrue(n <= MAX_ENTRIES,
                "排除条目已增至 " + n + " 条（上限 " + MAX_ENTRIES + "）。"
                        + "加排除很容易、修问题很难——若确实需要新排除，"
                        + "先问自己这条是真误报还是该修的 bug。");
    }

    @Test
    @DisplayName("没有把工具整体屏蔽掉")
    void notDisabledEntirely() throws IOException {
        String xml = read();
        // <Match/> 无子元素即匹配全部，会让 SpotBugs 一个 bug 都不报
        assertFalse(xml.matches("(?s).*<Match\\s*/>.*"),
                "存在 <Match/>（匹配全部）——这等于把 SpotBugs 关掉");
        assertFalse(xml.contains("<Bug pattern=\"*\""),
                "存在通配 bug pattern，等同于屏蔽全部检查");
    }

    @Test
    @DisplayName("被真正修掉的问题不再出现在排除清单里")
    void fixedBugsAreNotExcluded() throws IOException {
        String xml = read();
        // 这几类是本轮真实修复的：NPE 与静默吞异常。
        // 若哪天它们又被排除了，说明修复被回退了。
        assertFalse(xml.contains("DE_MIGHT_IGNORE"),
                "DE_MIGHT_IGNORE 曾暴露出真实的静默吞异常（缓存写失败、网页关闭失败），"
                        + "现已改为记录日志。若要重新排除，请先确认那些日志是有意去掉的");
        assertFalse(xml.contains("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE"),
                "该类曾暴露两个真实 NPE（levels 配置缺 property），已修复，不应再排除");
    }

    private static int countEntries() throws IOException {
        int n = 0;
        for (String line : read().split("\n")) {
            if (MATCH.matcher(line).find()) n++;
        }
        return n;
    }
}