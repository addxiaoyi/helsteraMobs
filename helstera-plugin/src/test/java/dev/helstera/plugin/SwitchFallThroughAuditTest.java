package dev.helstera.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 箭头 {@code switch} 的 fall-through 审计。
 *
 * <p><b>这个测试存在的理由</b>：本项目在 {@code HelsteraCommand} 里出现过
 * <b>17 处</b> {@code case} 块未终止。其中四处不是显示瑕疵，而是功能失效：
 * <ul>
 *   <li>{@code /helstera web start} 会紧接着执行 {@code web stop}
 *       —— 刚启动就被自己停掉，命令完全无效；</li>
 *   <li>{@code /helstera animation stop} 会依次执行 pause、resume
 *       —— 停止后动画又动起来；</li>
 *   <li>{@code /helstera migrate apply} 会贯穿到 {@code rollback}
 *       —— 参数恰好够时真的执行一次回滚，把刚应用的改动撤销；</li>
 *   <li>{@code /helstera model validate} 会落到 {@code unload}
 *       —— 纯只读校验变成「卸载模型并销毁全部实例」。</li>
 * </ul>
 * 其余是「执行完 A 却打印 B 的用法提示」，其中 {@code list} 分支最多 ——
 * 而 list 恰是管理员日常敲得最多的诊断命令。</p>
 *
 * <p>为什么单测抓不到：这类缺陷<b>编译通过、全部测试通过</b>，
 * 只有真敲一次命令才看得见。</p>
 *
 * <p><b>为什么只用粗规则</b>：本测试曾尝试剥离字符串字面量与注释后做精确的
 * 花括号配对，调试三轮仍不稳定（文本块 {@code \"\"\"}、转义引号、
 * 参数列表里的右括号都会让它错判），最终选择「宁可漏报也不误报」的粗规则：
 * 只检查 case 块的最后一行是否以 {@code return} / {@code throw} 收尾。
 * 漏报的代价是新分支可能再犯；误报的代价是测试长期红着被人加豁免，
 * 后者更糟 —— 那会让整个审计变成摆设。</p>
 */
class SwitchFallThroughAuditTest {

    private static final Path CMD = Path.of("src", "main", "java", "dev", "helstera", "plugin",
            "HelsteraCommand.java");

    /**
     * 有意不终止的 case，格式「方法名:分支名」。
     *
     * <p>目前只有 {@code reload:all}：各 case 只做自己的动作，
     * 统一在 switch 之后输出「重载完成 + 耗时 + 模型数」。
     * 在 6 个 case 里各写一遍收尾更容易漏改，代价是 default 必须 return
     * （reload() 里已满足）。</p>
     */
    private static final Set<String> INTENTIONALLY_OPEN = new LinkedHashSet<>(List.of(
            "reload:all"
    ));

    private static List<String> lines() throws IOException {
        return Files.readAllLines(CMD, StandardCharsets.UTF_8);
    }

    /** 方法名哨兵：标记「当前处于应当整体跳过 onTabComplete 的区间」。 */
    private static final String SKIP_UNTIL_BRACE = "@@skip-tab-complete";

    @Test
    @DisplayName("每个 case 分支都以 return 收尾，不会贯穿到下一个分支")
    void everyCaseBranchTerminates() throws IOException {
        List<String> all = lines();
        // 当前方法名：遇到 "void name(" 就切换
        Pattern methodSig = Pattern.compile("\\bvoid\\s+(\\w+)\\s*\\(");
        Pattern skipSig = Pattern.compile("\\bonTabComplete\\s*\\(");
        Pattern caseLabel = Pattern.compile("\\bcase\\s+\"([^\"]*)\"\\s*->\\s*\\{");

        String method = null;
        String pendingCase = null;
        int blockStart = -1;
        int depth = 0;
        int audited = 0;
        List<String> problems = new ArrayList<>();

        for (int i = 0; i < all.size(); i++) {
            String line = all.get(i);

            if (skipSig.matcher(line).find() && line.contains("{")) {
                method = SKIP_UNTIL_BRACE;
            }
            if (SKIP_UNTIL_BRACE.equals(method)) continue;

            Matcher mm = methodSig.matcher(line);
            if (mm.find() && line.contains("{")) {
                method = mm.group(1);
                // onTabComplete 返回 List<String> 而非 void，它的 case 只往集合里 add，
                // 多个 case 依次执行正是它想要的行为。但它体内的 case 仍会被下面的
                // caseLabel 匹配到，必须显式跳过整段，否则会挂在上一个 void 方法名下，
                // 报出「deny:mob spawn」这种根本不属于该方法的问题。
                if ("onTabComplete".equals(method)) method = SKIP_UNTIL_BRACE;
            }
            if (SKIP_UNTIL_BRACE.equals(method)) continue;

            Matcher cm = caseLabel.matcher(line);
            if (cm.find()) {
                pendingCase = cm.group(1);
                blockStart = i;
                // 同行可能已有内容（极少见），下一行起算块体
                depth = 1 - countBraces(line.substring(cm.end()));
                if (depth <= 0) {
                    // 形如 case "x" -> { ... } 单行闭合：不算漏网
                    pendingCase = null;
                }
                continue;
            }

            if (pendingCase == null) continue;

            depth += countBraces(line);
            if (depth > 0) continue;

            // 块体结束。检查 blockStart+1 到 i-1 里最后一个有内容的非注释行。
            // 排除闭合行 i 本身：让 depth 归零的那一行可能同时是 default 的标签行
            // （末个 case 与 default 同行闭合），把它算进去就永远看到 default 的
            // 文案而不是本 case 的 return，规则就会把全部末位 case 都误报一遍。
            String last = lastMeaningfulLine(all, blockStart + 1, i - 1);
            audited++;
            String label = method + ":" + pendingCase;
            if (last != null && terminatesLine(last)) {
                pendingCase = null;
                continue;
            }
            if (INTENTIONALLY_OPEN.contains(label)) {
                pendingCase = null;
                continue;
            }
            problems.add(label + "（第 " + (blockStart + 1) + " 行起）末尾是: "
                    + (last == null ? "(空)" : last.strip()));
            pendingCase = null;
        }

        assertTrue(audited > 20,
                "只审计到 " + audited + " 个 case 分支，行解析多半没匹配上，测试本身可能已失效");
        assertTrue(problems.isEmpty(),
                "以下 case 分支未以 return/throw 收尾，会执行到下一个分支"
                        + "（web start / animation stop / migrate apply / model validate "
                        + "四处会让功能直接失效）：\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("粗规则本身有效：能识别已终止与未终止两种形态")
    void ruleDetectsBothShapes() {
        assertTrue(terminatesLine("                return;"), "裸 return 应判为已终止");
        assertTrue(terminatesLine("            }"), "闭合括号本身即终止");
        assertTrue(terminatesLine("                throw new IllegalStateException();"),
                "throw 应判为已终止");
        assertTrue(!terminatesLine("                s.sendMessage(\"x\");"),
                "裸 sendMessage 应判为未终止");
    }

    @Test
    @DisplayName("INTENTIONALLY_OPEN 里的 case 仍真实存在，避免豁免过期")
    void exemptionsAreNotStale() throws IOException {
        String src = Files.readString(CMD, StandardCharsets.UTF_8);
        List<String> stale = new ArrayList<>();
        for (String label : INTENTIONALLY_OPEN) {
            String m = label.substring(0, label.indexOf(':'));
            String action = label.substring(label.indexOf(':') + 1);
            boolean found = Pattern.compile("\\bvoid\\s+" + Pattern.quote(m) + "\\s*\\(").matcher(src).find()
                    && Pattern.compile("case\\s+\"" + Pattern.quote(action) + "\"\\s*->").matcher(src).find();
            if (!found) stale.add(label);
        }
        assertTrue(stale.isEmpty(),
                "INTENTIONALLY_OPEN 列的 case 已不存在，应删除以免豁免膨胀：" + stale);
    }

    /**
     * 一行里 { 与 } 的净数量，<b>忽略双引号内的括号</b>。
     *
     * <p>忽略字符串是必需的：本文件的文案里出现过 {@code "{}"} 这类内容，
     * 一旦计入就会让 depth 多 1，导致本 case 永远找不到闭合点，
     * 块体一路吞到下一个方法里去——表现为「A 方法的 case 被报成
     * B 方法的方法体结尾」，比不审计还误导人。</p>
     *
     * <p>不处理文本块与转义引号：它们在本文件里只出现在注释和 javadoc 中，
     * 而注释行以 {@code //} 或 {@code *} 开头会在下方被 countBraces 计入……
     * 故此方法同时跳过以注释符开头的行。</p>
     */
    private static int countBraces(String s) {
        String t = s.strip();
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return 0;
        int d = 0;
        boolean inStr = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && inStr) { i++; continue; }
            if (c == '"') { inStr = !inStr; continue; }
            if (inStr) continue;
            if (c == '{') d++;
            else if (c == '}') d--;
        }
        return d;
    }

    /** 取 [from, to] 区间内最后一个有内容的非注释行。 */
    private static String lastMeaningfulLine(List<String> lines, int from, int to) {
        for (int i = to; i >= from; i--) {
            String s = lines.get(i).strip();
            if (s.isEmpty()) continue;
            if (s.startsWith("//") || s.startsWith("*") || s.startsWith("/*")) continue;
            return s;
        }
        return null;
    }

    /** 该行是否构成终止（return / throw / 块的闭合括号）。 */
    private static boolean terminatesLine(String s) {
        String t = s.strip();
        if (t.isEmpty()) return true;
        if (t.equals("}")) return true;
        return t.startsWith("return") || t.startsWith("throw")
                || t.startsWith("continue") || t.startsWith("break");
    }
}