package dev.helstera.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可达性审计：代码里检查的每个权限与每个子命令，都必须在 {@code plugin.yml} 里
 * 有对应声明。
 *
 * <p><b>这个测试存在的理由</b>：本项目反复出现「实现了但没接线」的缺陷——
 * 命令分支写了却没进 {@code SUBS}、权限检查写了却没在 {@code plugin.yml} 声明。
 * 这类缺陷<b>没有编译错误、没有运行时报错、其它测试全绿</b>，只有玩家会发现
 * 「命令没反应」或「管理员想授权却无处可授」。靠人读代码发现不了。</p>
 *
 * <p>因此把「可达性」变成断言：新增权限或子命令却忘了同步声明时，构建直接失败。</p>
 */
class CommandReachableAuditTest {

    private static final Path PLUGIN_DIR = Path.of("src", "main", "java", "dev", "helstera", "plugin");
    private static final Path PLUGIN_YML = Path.of("src", "main", "resources", "plugin.yml");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("代码中检查的每个 helstera.* 权限都已在 plugin.yml 声明")
    void everyCheckedPermissionIsDeclared() throws IOException {
        String yml = read(PLUGIN_YML);
        // 源码里出现的所有权限字面量
        Pattern perm = Pattern.compile("\"(helstera\\.[a-z]+)\"");
        var declared = new TreeSet<String>();
        Matcher dm = Pattern.compile("^\\s{2,6}(helstera\\.[a-z]+):", Pattern.MULTILINE).matcher(yml);
        while (dm.find()) declared.add(dm.group(1));

        var checked = new TreeSet<String>();
        try (var files = Files.list(PLUGIN_DIR)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = perm.matcher(read(f));
                while (m.find()) checked.add(m.group(1));
            }
        }
        assertTrue(!checked.isEmpty(), "未从源码提取到任何权限字面量，测试本身可能已失效");

        var missing = new TreeSet<String>();
        for (String c : checked) if (!declared.contains(c)) missing.add(c);
        assertTrue(missing.isEmpty(),
                "以下权限被代码检查却未在 plugin.yml 声明，"
                        + "非 OP 玩家会被静默拒绝且管理员无处授权: " + missing);
    }

    @Test
    @DisplayName("SUBS 与命令分派分支一致：无遗漏、无未实现")
    void subsMatchDispatch() throws IOException {
        String src = read(PLUGIN_DIR.resolve("HelsteraCommand.java"));
        // 只取顶层分派 switch：migrate/pack 等方法内部还有嵌套 switch，
        // 用全文正则会把 all/apply/build 这些嵌套子命令误当成顶层子命令
        int start = src.indexOf("case \"");
        int end = src.indexOf("default ->", start);
        assertTrue(start >= 0 && end > start, "未定位到顶层命令分派 switch");
        String topLevel = src.substring(start, end);
        var dispatched = new TreeSet<String>();
        Matcher m = Pattern.compile("case \"([a-z]+)\" ->").matcher(topLevel);
        while (m.find()) dispatched.add(m.group(1));

        // 必须锚定 SUBS 声明本身：文件里有多处 List.of(...)，用宽松正则会匹配到
        // 迁移/资源包等子命令列表，报出一堆无关的词
        Matcher sub = Pattern.compile("SUBS\\s*=\\s*List\\.of\\((.*?)\\);",
                Pattern.DOTALL).matcher(src);
        assertTrue(sub.find(), "未找到 SUBS 列表");
        var listed = new TreeSet<String>();
        Matcher q = Pattern.compile("\"([a-z]+)\"").matcher(sub.group(1));
        while (q.find()) listed.add(q.group(1));

        var missingInSubs = new TreeSet<>(dispatched);
        missingInSubs.removeAll(listed);
        assertTrue(missingInSubs.isEmpty(),
                "已实现分派却未列入 SUBS，Tab 补全看不到且 help 不会提示: " + missingInSubs);

        var missingBranch = new TreeSet<>(listed);
        missingBranch.removeAll(dispatched);
        // help 不需要独立分支（它就是 default）
        missingBranch.remove("help");
        assertTrue(missingBranch.isEmpty(),
                "SUBS 中列出但没有对应分派分支，调用会落到 default 走 help: " + missingBranch);
    }

    @Test
    @DisplayName("check() 必须接入免疫告警，否则免疫写错只能靠冷门诊断命令才能发现")
    void checkSurfacesImmunityProblems() throws IOException {
        String src = read(PLUGIN_DIR.resolve("HelsteraCommand.java"));
        int start = src.indexOf("private void check(");
        assertTrue(start > 0, "未定位到 check() 方法");
        // 截到下一个方法声明为止：免疫告警同样出现在 /helstera immunity 里，
        // 用全文搜索会把那个方法的调用算成 check() 已接入，测不出真实缺口
        int end = src.indexOf("\n    private void ", start + 10);
        assertTrue(end > start, "未定位到 check() 方法结尾");
        String body = src.substring(start, end);

        assertTrue(body.contains("immunityWarnings()"),
                "check() 未读取免疫装载期告警：免疫写错在服务端毫无症状"
                        + "（规则静默永不匹配，现场与「没配」无法区分），"
                        + "而管理员通常不会去跑 /helstera immunity 那条冷门命令");
        assertTrue(body.contains("immunityListener()"),
                "check() 未检查免疫监听器是否注册：该状态会让全部免疫与倍率静默失效，"
                        + "比任何配置错误都严重，此前完全没有暴露途径");
    }

    @Test
    @DisplayName("helstera.admin 覆盖全部业务权限，避免新增节点忘记授权")
    void adminChildrenCoverPermissions() throws IOException {
        String yml = read(PLUGIN_YML);
        var children = new TreeSet<String>();
        Matcher m = Pattern.compile("^\\s{6}(helstera\\.[a-z]+): true",
                Pattern.MULTILINE).matcher(yml);
        while (m.find()) children.add(m.group(1));

        var declared = new TreeSet<String>();
        Matcher dm = Pattern.compile("^\\s{2,6}(helstera\\.[a-z]+):", Pattern.MULTILINE).matcher(yml);
        while (dm.find()) declared.add(dm.group(1));
        // admin / view 是根节点，不应作为子项出现
        declared.remove("helstera.admin");
        declared.remove("helstera.view");

        var orphan = new TreeSet<String>(declared);
        orphan.removeAll(children);
        assertTrue(orphan.isEmpty(),
                "已声明但未挂到 helstera.admin 下，OP 之外无人可用: " + orphan);
    }

    @Test
    @DisplayName("plugin.yml 中每个权限都至少有 description")
    void permissionsAreDocumented() throws IOException {
        List<String> lines = Files.readAllLines(PLUGIN_YML, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.matches("^  helstera\\.[a-z]+:")) continue;
            String next = i + 1 < lines.size() ? lines.get(i + 1) : "";
            if (!next.contains("description:")) {
                throw new AssertionError("权限 " + line.trim() + " 缺少 description");
            }
        }
    }
}