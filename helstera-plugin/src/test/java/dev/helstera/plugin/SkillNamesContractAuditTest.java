package dev.helstera.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code WebBridgeAdapter.skillNames()} 的契约审计。
 *
 * <p><b>这个测试来自浏览器走查</b>：网页端「技能预览」的下拉框里
 * 出现的全是 {@code health-above:0.5}、{@code set-scale:1.1} 这类
 * 条件名与动作名，且同一条 {@code skill:smoke_test} 重复出现两次。</p>
 *
 * <p>根因：bridge 实现把 {@code BehaviorRegistry.conditionNames()} 与
 * {@code actionNames()} 拼在一起返回。两者都是「所有可注册的条件/动作」，
 * 与方法名和调用方预期完全不符——调用方（网页端预览下拉框、
 * {@code /api/skills} 的 names 字段）要的是<b>skills.yml 里定义的命名技能</b>。</p>
 *
 * <p>后果是下拉框里<b>每一项都预览失败</b>，因为没有一项叫
 * {@code health-above:0.5}。命令层的 {@code /helstera skill list} 用的是
 * {@code SkillService.skillNames()}，一直是对的，所以只有网页端这条路错了。</p>
 *
 * <p>为什么用源码审计而非行为测试：{@code WebBridgeAdapter} 是匿名内部类，
 * 需要装满 Bukkit 服务端才能实例化。审计源码虽不如行为测试直接，
 * 但它锁的是「这个方法只能从 skillService 取」这条不变量，
 * 改动实现方式时它仍会报警。</p>
 */
class SkillNamesContractAuditTest {

    private static final Path PLUGIN_SRC =
            Path.of("src", "main", "java", "dev", "helstera", "plugin", "HelsteraPlugin.java");

    private static String pluginSource() throws IOException {
        return Files.readString(PLUGIN_SRC, StandardCharsets.UTF_8);
    }

    /**
     * 截出 WebBridgeAdapter 里 skillNames() 方法体。
     *
     * <p>必须跳过注释：本方法的说明性注释里会提到 {@code conditionNames()}
     * 这类名字（解释「为什么不能用它」），若连注释一起扫描，测试就会因为
     * 注释里出现了被禁的名字而误报失败。</p>
     */
    private static String skillNamesBody(String src) throws IOException {
        int anchor = src.indexOf("public java.util.List<String> skillNames()");
        assertTrue(anchor > 0, "HelsteraPlugin.java 里找不到 WebBridgeAdapter.skillNames()");
        int start = src.indexOf('{', anchor);

        StringBuilder code = new StringBuilder();
        int depth = 0;
        boolean inLine = false, inBlock = false, inStr = false, inChar = false;
        for (int i = start; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';

            if (inLine) {
                if (c == '\n') inLine = false;
                continue;
            }
            if (inBlock) {
                if (c == '*' && n == '/') { inBlock = false; i++; }
                continue;
            }
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (inChar) {
                if (c == '\\') { i++; continue; }
                if (c == '\'') inChar = false;
                continue;
            }

            if (c == '/' && n == '/') { inLine = true; i++; continue; }
            if (c == '/' && n == '*') { inBlock = true; i++; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == '\'') { inChar = true; continue; }

            if (c == '{') {
                depth++;
                if (depth == 1) continue;   // 不收录最外层 {
            } else if (c == '}') {
                depth--;
                if (depth == 0) return code.toString();
            }
            code.append(c);
        }
        throw new IOException("skillNames() 的方法体括号不配对，无法提取");
    }

    @Test
    @DisplayName("skillNames() 取自 SkillService，不拼 BehaviorRegistry 的条件/动作名")
    void skillNamesComesFromSkillService() throws IOException {
        String body = skillNamesBody(pluginSource());

        assertFalse(body.contains("conditionNames()"),
                "WebBridgeAdapter.skillNames() 不得拼接 BehaviorRegistry.conditionNames()："
                        + "它返回的是所有可注册条件（health-above:0.5 等），"
                        + "不是命名技能。后果是网页端预览下拉框里每一项都预览失败。");
        assertFalse(body.contains("actionNames()"),
                "WebBridgeAdapter.skillNames() 不得拼接 BehaviorRegistry.actionNames()，同上。");
        assertTrue(body.contains("skillService"),
                "WebBridgeAdapter.skillNames() 应从 SkillService 取（skillService.skillNames()），"
                        + "它返回 skills.yml 里定义的技能名，天然去重。");
    }

    @Test
    @DisplayName("SkillService.skillNames() 返回去重后的命名技能")
    void skillServiceNamesAreDistinct() throws IOException {
        Path svc = Path.of("..", "helstera-ai", "src", "main", "java",
                "dev", "helstera", "ai", "skill", "SkillService.java");
        String src = Files.readString(svc, StandardCharsets.UTF_8);

        int anchor = src.indexOf("public List<String> skillNames()");
        assertTrue(anchor > 0, "SkillService 里找不到 skillNames()");
        int start = src.indexOf('{', anchor);

        // 复用同一套「跳过注释与字符串」的括号配对扫描：
        // 该方法的注释里出现过 List.copyOf(defs.keySet()) 的说明，
        // 朴素 indexOf('}') 会被注释里的括号带偏。
        StringBuilder code = new StringBuilder();
        int depth = 0;
        boolean inLine = false, inBlock = false, inStr = false, inChar = false;
        for (int i = start; i < src.length(); i++) {
            char c = src.charAt(i);
            char n = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            if (inLine) { if (c == '\n') inLine = false; continue; }
            if (inBlock) { if (c == '*' && n == '/') { inBlock = false; i++; } continue; }
            if (inStr) { if (c == '\\') { i++; continue; } if (c == '"') inStr = false; continue; }
            if (inChar) { if (c == '\\') { i++; continue; } if (c == '\'') inChar = false; continue; }
            if (c == '/' && n == '/') { inLine = true; i++; continue; }
            if (c == '/' && n == '*') { inBlock = true; i++; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == '\'') { inChar = true; continue; }
            if (c == '{') { depth++; if (depth == 1) continue; }
            else if (c == '}') { depth--; if (depth == 0) break; }
            code.append(c);
        }
        String body = code.toString();

        // defs 是 Map，keySet() 天然去重。若将来改成遍历 List 拼装，
        // 同一技能被 collect 两次的 bug 会立刻回来（网页端下拉框出现重复项）。
        assertTrue(body.contains("defs") && body.contains("keySet()"),
                "SkillService.skillNames() 应基于 defs 的 keySet()，它天然去重；"
                        + "改成遍历 List 拼装会让重复项回归。当前实现: " + body.trim());
    }
}