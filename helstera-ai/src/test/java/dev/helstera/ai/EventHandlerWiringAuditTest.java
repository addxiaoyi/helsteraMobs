package dev.helstera.ai;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件处理器接线审计：断言每个 {@code @EventHandler} 都落在合法的方法声明上。
 *
 * <p><b>这个测试存在的理由</b>：本项目出现过一次真实故障——
 * {@code SkillTriggers} 里 {@code @EventHandler} 注解被错放到
 * {@code minions()} 访问器与 {@code onPotionEffect()} 之间。后果有两层：
 * <ol>
 *   <li>Bukkit 启动时拒绝注册那个非法签名，只在日志留一行 ERROR；</li>
 *   <li>{@code onPotionEffect} 失去注解，{@code on-buff} 与
 *       {@code on-potion-effect-end} 两个触发器<b>从未真正接线</b>，
 *       表现为「配置写了永不触发且无任何提示」。</li>
 * </ol>
 * 更麻烦的是它<b>编译通过、全部单测通过</b>：既有的
 * {@link TriggerWiringAuditTest} 只验证枚举的 {@code wired} 标记自洽，
 * 而标记与真实派发点是否对得上，它并不检查。</p>
 *
 * <p>判定方式：先把源码里的注释整体替换成等长空白，再在<b>无注释</b>的文本上
 * 检查每个 {@code @EventHandler} 后面紧跟的声明。这样既不会被 javadoc 干扰，
 * 也与 Bukkit 基于反射/字节码的扫描语义一致。</p>
 */
class EventHandlerWiringAuditTest {

    /** 实现 Listener 的类——只有这些类会被 Bukkit 扫描事件处理器。 */
    private static final Pattern LISTENER_CLASS =
            Pattern.compile("\\bclass\\s+(\\w+)[^{;]*\\bimplements\\s+Listener\\b");

    /**
     * 合法的事件处理器方法头：{@code public void name(Event e)}。
     *
     * <p>要求 {@code public} 与<b>恰好一个</b>参数：Bukkit 对非 public 方法
     * 直接拒绝注册，多参数方法会被判非法签名。两者都不会报错在编译期。</p>
     */
    private static final Pattern VALID_HANDLER =
            Pattern.compile("\\bpublic\\s+void\\s+(\\w+)\\s*\\(\\s*[\\w.]+\\s+\\w+\\s*\\)");

    /** 注释起始序列，按出现顺序尝试。 */
    private static final String[][] COMMENT_OPENERS = {
            {"/**"}, {"/*"}, {"//"},
    };

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.exists(p.resolve("pom.xml")) && Files.isDirectory(p.resolve("helstera-ai"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未能定位仓库根（当前目录 "
                + Path.of("").toAbsolutePath().toString().toLowerCase(java.util.Locale.ROOT) + "）");
    }

    private static List<Path> aiSources() throws IOException {
        Path main = repoRoot().resolve("helstera-ai/src/main/java");
        List<Path> out = new ArrayList<>();
        try (var walk = Files.walk(main)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
        }
        return out;
    }

    /**
     * 把所有注释替换成等长空白，保留字符偏移量与换行。
     *
     * <p>保留长度是关键：替换后所有位置与原文一一对应，报错才能指出源码位置。
     * 字符串字面量里的 {@code //} 会被误判为注释起点，但本项目的 Listener 类
     * 里没有这种写法，且即便误判也只是让那一行不参与审计，不影响正确性。</p>
     */
    private static String blankOutComments(String src) {
        char[] out = src.toCharArray();
        int i = 0;
        int n = src.length();
        while (i < n) {
            String opener = null;
            for (String[] cand : COMMENT_OPENERS) {
                if (src.startsWith(cand[0], i)) {
                    opener = cand[0];
                    break;
                }
            }
            if (opener == null) {
                i++;
                continue;
            }
            int end;
            if ("//".equals(opener)) {
                end = src.indexOf('\n', i);
                if (end < 0) end = n;
            } else {
                int close = src.indexOf("*/", i + 2);
                end = close < 0 ? n : close + 2;
            }
            for (int k = i; k < end && k < n; k++) {
                // 换行保留，避免行号信息丢失
                if (out[k] != '\n') out[k] = ' ';
            }
            i = end;
        }
        return new String(out);
    }

    private static int lineOf(String src, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < src.length(); i++) {
            if (src.charAt(i) == '\n') line++;
        }
        return line;
    }

    @Test
    @DisplayName("每个 @EventHandler 都紧跟一个合法的 public void 方法(单参数)")
    void everyAnnotationSitsOnValidHandler() throws IOException {
        List<String> problems = new ArrayList<>();
        int audited = 0;

        for (Path file : aiSources()) {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            if (!LISTENER_CLASS.matcher(blankOutComments(original)).find()) {
                continue;   // 非 Listener，无需审计
            }
            String clean = blankOutComments(original);
            int from = 0;
            while (true) {
                int at = clean.indexOf("@EventHandler", from);
                if (at < 0) break;
                from = at + 1;
                audited++;

                // 跳过其余注解行，再看紧邻的声明。
                int i = skipAnnotations(clean, at + "@EventHandler".length());
                if (i < 0) {
                    problems.add(rel(file) + ":" + lineOf(clean, at)
                            + ": @EventHandler 之后没有任何声明");
                    continue;
                }
                int declEnd = declarationEnd(clean, i);
                String decl = clean.substring(i, declEnd);
                if (!VALID_HANDLER.matcher(decl).find()) {
                    problems.add(rel(file) + ":" + lineOf(clean, at)
                            + ": @EventHandler 之后不是合法的单参方法 —— "
                            + decl.replaceAll("\\s+", " ").trim()
                            + "（Bukkit 会拒绝注册并在启动日志留一行 ERROR；"
                            + "被它「隔开」的方法会静默失去事件接线）");
                }
            }
        }

        assertTrue(audited > 0,
                "未审计到任何 @EventHandler，审计规则本身失效（源码路径或匹配规则有问题）");
        assertTrue(problems.isEmpty(),
                "以下 @EventHandler 没落在合法方法上：\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("审计能识别注解与合法方法之间插入成员声明的写法")
    void auditDetectsMisplacedAnnotation() {
        // 复现真实故障的形态：注解后面先出现了另一个成员（访问器），
        // 真正想注册的方法被隔到后面去了。
        String broken = """
                @EventHandler(priority = EventPriority.MONITOR)
                private MinionService minions() { return null; }
                public void onPotionEffect(EntityPotionEffectEvent e) { }
                """;
        assertTrue(judge(broken) != null, "插入了访问器时应判为非法");

        // 对照组：注解与 javadoc 紧贴合法方法时应通过
        String good = """
                /** 说明。 */
                @EventHandler(priority = EventPriority.MONITOR)
                public void onDamage(EntityDamageEvent e) { }
                """;
        assertTrue(judge(good) == null, "javadoc 不应干扰判定");

        // 对照组：多参数是非法签名
        assertTrue(judge("@EventHandler\npublic void bad(EntityDamageEvent e, int x) { }") != null,
                "多参数方法不是合法事件处理器");

        // 对照组：非 public 是非法签名
        assertTrue(judge("@EventHandler\nprivate void hidden(EntityDamageEvent e) { }") != null,
                "非 public 方法不会被 Bukkit 注册");

        // 对照组：没有类型修饰符的裸参数也应被接受
        assertTrue(judge("@EventHandler\npublic void ok(Event e) { }") == null,
                "标准单参方法应通过");
    }

    /** 对一段源码片段做判定；合法返回 {@code null}，非法返回声明片段。 */
    private static String judge(String snippet) {
        String clean = blankOutComments(snippet);
        int at = clean.indexOf("@EventHandler");
        if (at < 0) return "no-annotation";
        int i = skipAnnotations(clean, at + "@EventHandler".length());
        if (i < 0) return "no-declaration";
        int end = declarationEnd(clean, i);
        String decl = clean.substring(i, end);
        return VALID_HANDLER.matcher(decl).find() ? null : decl;
    }

    /**
     * 从给定位置起跳过连续的注解（及其所在行的参数）。
     *
     * @return 首个非注解字符的下标；其后只有空白时返回 -1
     */
    private static int skipAnnotations(String clean, int from) {
        int i = from;
        int n = clean.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(clean.charAt(i))) i++;
            if (i >= n || clean.charAt(i) != '@') return i < n ? i : -1;
            // 注解参数里可能带括号，扫到该行末尾即可（注解不会跨行书写）
            int eol = clean.indexOf('\n', i);
            if (eol < 0) return -1;
            i = eol + 1;
        }
        return -1;
    }

    /** 从声明起点取到第一个 {@code ;} 或 {@code {} 起始的 {@code {}。 */
    private static int declarationEnd(String clean, int from) {
        int j = from;
        int n = clean.length();
        while (j < n) {
            char c = clean.charAt(j);
            if (c == ';' || c == '{') return j + 1;
            j++;
        }
        return n;
    }

    private static String rel(Path file) {
        return repoRoot().relativize(file).toString().replace('\\', '/');
    }
}