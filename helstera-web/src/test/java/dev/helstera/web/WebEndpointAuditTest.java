package dev.helstera.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网页端接口对账：前端调用的每个 {@code /api/*}，服务端必须真的提供。
 *
 * <p><b>这个测试存在的理由</b>：本模块此前<b>一个测试都没有</b>，而
 * 「前端调用的接口服务端没实现」是本项目最典型的静默缺陷——没有编译错误、
 * 没有运行时报错、其它模块全绿，只有点开那个页面的人会发现它 404，
 * 而那往往要等到发版之后。</p>
 *
 * <p>刻意只做「前端 → 服务端」单��检查：服务端多出的路由（无前端调用者）
 * 是正常的演进痕迹，而前端调用不存在的路由一律是缺陷。</p>
 */
class WebEndpointAuditTest {

    private static final Path HTML = Path.of("src", "main", "resources", "web", "index.html");
    private static final Path SERVER = Path.of("src", "main", "java", "dev", "helstera", "web",
            "WebServerService.java");

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    /** 前端里出现的 /api/... 字面量。 */
    private static Set<String> frontendEndpoints(String html) {
        // 只取引号内的路径，避免把文案里的斜杠误当端点
        Matcher m = Pattern.compile("['\"`](/api/[A-Za-z0-9_/-]+)").matcher(html);
        Set<String> out = new LinkedHashSet<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** 服务端 switch 里的路由。 */
    private static Set<String> serverRoutes(String src) {
        Matcher m = Pattern.compile("case\\s+\"(/api/[A-Za-z0-9_/-]*)\"\\s*->").matcher(src);
        Set<String> out = new LinkedHashSet<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    @DisplayName("前端调用的每个 /api 端点，服务端都真的实现了")
    void everyFrontendCallIsServed() throws IOException {
        String html = read(HTML);
        String src = read(SERVER);

        Set<String> called = frontendEndpoints(html);
        assertTrue(called.size() >= 30,
                "只从页面提取到 " + called.size() + " 个端点，正则多半没匹配上，测试本身可能已失效");

        Set<String> missing = new LinkedHashSet<>();
        for (String ep : called) {
            // 在整个服务端源码里找该字面量：路由可能由 switch 分发，也可能
            // 走 javalin 的其它注册方式，只查 switch 会误报
            if (!src.contains("\"" + ep + "\"")) missing.add(ep);
        }
        assertTrue(missing.isEmpty(),
                "前端调用了服务端未提供的端点（运行时静默 404）: " + missing);
    }

    @Test
    @DisplayName("本次新增的免疫诊断端点两端都在")
    void immunityEndpointIsWired() throws IOException {
        String html = read(HTML);
        String src = read(SERVER);
        assertTrue(src.contains("/api/immunity"),
                "服务端缺少 /api/immunity 路由");
        assertTrue(html.contains("/api/immunity"),
                "前端没有调用 /api/immunity：接口已实现却没有任何调用者，"
                        + "属于另一种「实现了但没接线」，同样不会报任何错");
    }

    /**
     * 反向对账：服务端提供的每个 {@code /api/*}，前端都应有调用者。
     *
     * <p>与 {@link #everyFrontendCallIsServed} 互补。前者查「前端调了但服务端没有」
     * （表现为 404），本测试查「服务端有但前端不调」——后者<b>不表现为任何报错</b>：
     * 接口安静地躺在那里，永远不会被触发，功能等于没做。
     * 本项目已因此出现过一次真实缺口：{@code /helstera spawner toggle} 做完命令后
     * 只接了命令行，网页端刷怪点面板上没有对应按钮，管理员只能回控制台敲命令。</p>
     *
     * <p>豁免是显式清单而非通配符：确有「给外部工具用的只读端点」时，
     * 应在 {@link #BRIDGE_ONLY} 写明理由，让例外可见。</p>
     */
    @Test
    @DisplayName("服务端新增的端点，前端也有调用者（否则功能等于没做）")
    void everyServerEndpointIsUsedByFrontend() throws IOException {
        String html = read(HTML);
        String src = read(SERVER);

        Set<String> routes = serverRoutes(src);
        assertTrue(routes.size() >= 30,
                "只从服务端提取到 " + routes.size() + " 条路由，正则多半没匹配上，测试本身可能已失效");

        Set<String> unused = new LinkedHashSet<>();
        for (String ep : routes) {
            if (BRIDGE_ONLY.contains(ep)) continue;
            // 子串匹配而非引号精确匹配：前端大量使用模板拼接路径，
            // 如 api(`/api/model?id=${id}`) 与 api(`/api/model/files`)。
            // 按引号精确匹配会把这些全部误报成「未接线」，而真实的缺口
            // （整个端点名都不出现在页面里）依然会被抓到。
            if (!html.contains(ep)) unused.add(ep);
        }
        assertTrue(unused.isEmpty(),
                "服务端实现了但前端没有任何调用者的端点（不报 404，因此更隐蔽——"
                        + "功能等于没做）：" + unused
                        + "。若确实是给外部工具用的只读端点，请加入 BRIDGE_ONLY 并写明理由。");
    }

    /**
     * 有意不做前端入口的端点，逐条附理由。
     *
     * <p>不是「豁免就完事」——每条都必须写清<b>为什么前端不需要它</b>。
     * 放宽规则最怕的就是豁免表悄悄长成垃圾桶：列进来的端点越多，
     * 规则越形同虚设。因此本表刻意保持极短，新增条目需要说服人。</p>
     */
    private static final Set<String> BRIDGE_ONLY = new LinkedHashSet<>(java.util.List.of(
            // 与聚合端点功能重复：/api/skills 与 /api/loot 各自已返回 content
            // （YAML 全文），前端编辑器直接用它填充文本框。file 端点是给
            // 「只想取文件不想取元数据」的外部脚本用的备用路径。
            //
            // 对照：/api/spawners/file **不可**豁免——/api/spawners 只发摘要
            // 列表，不含 YAML 文本，编辑器必须走 file 端点。
            // （这条曾被误列为豁免，理由写错后会让编辑器无从取文本。）
            "/api/skills/file",
            "/api/loot/file",
            // 表单元数据：/api/templates 已提供 mob 表单的字段定义与默认值。
            // /api/mob/schema 是同一份数据的另一份导出，供外部生成器消费。
            "/api/mob/schema"
    ));

    @Test
    @DisplayName("BRIDGE_ONLY 里列的端点确实存在于服务端，避免过期豁免")
    void bridgeOnlyEntriesAreReal() throws IOException {
        String src = read(SERVER);
        Set<String> stale = new LinkedHashSet<>();
        for (String ep : BRIDGE_ONLY) {
            if (!src.contains("\"" + ep + "\"")) stale.add(ep);
        }
        assertTrue(stale.isEmpty(),
                "BRIDGE_ONLY 列了服务端已不存在的端点，应删除以免豁免逐渐膨胀：" + stale);
    }

    @Test
    @DisplayName("免疫诊断前端必须处理 active=false，否则监听器未注册时静默失效")
    void immunityPanelHandlesInactive() throws IOException {
        String html = read(HTML);
        assertTrue(html.contains("d.active"),
                "前端未读取 active 标志位：监听器未注册时全部免疫规则静默失效，"
                        + "而页面若只显示「没有配置」，管理员会以为是自己没配");
        assertTrue(html.contains("imOut"), "缺少免疫诊断输出节点");
    }

    @Test
    @DisplayName("两份 index.html 副本保持同步")
    void frontendCopiesAreInSync() throws IOException {
        Path res = HTML;
        Path copy = Path.of("..", "frontend", "index.html");
        if (!Files.exists(copy)) return;   // 单模块构建时副本不在，跨仓校验跳过
        assertTrue(Files.readString(res, StandardCharsets.UTF_8)
                        .equals(Files.readString(copy, StandardCharsets.UTF_8)),
                "frontend/index.html 与 helstera-web 资源目录下的副本已漂移："
                        + "发布用的是后者，前者会让人以为改过了但实际没生效");
    }

    /**
     * sync-back.cjs 必须按修改时间判断同步方向。
     *
     * <p><b>为什么这条规则重要</b>：脚本的旧实现无条件用
     * {@code frontend/index.html} 覆盖正式源文件。而正式源才是插件
     * 打包时真正读进 jar 的那份。于是只要有人直接改了正式源
     * （这才是正确的改动位置），下次谁跑一次脚本，改动就被旧副本
     * 静默覆盖——构建照样通过，只是改动没了，且没有任何提示。</p>
     *
     * <p>这不是假想：本轮就踩过一次反向的坑（改了正式源忘了改副本，
     * 被上面的双副本校验抓到），顺手把这个脚本也加固了。</p>
     */
    @Test
    @DisplayName("sync-back.cjs 按时间戳判断方向，方向不明时拒绝执行")
    void syncScriptRefusesBlindOverwrite() throws IOException {
        Path sync = Path.of("..", "frontend", "sync-back.cjs");
        if (!Files.exists(sync)) return;   // 单模块构建时脚本不在，跳过

        String src = Files.readString(sync, StandardCharsets.UTF_8);

        // 1. 必须有方向判断的依据
        assertTrue(src.contains("mtimeMs"),
                "sync-back.cjs 未读取 mtime：无条件覆盖会静默丢失正式源的改动");
        assertTrue(src.contains("to-real") && src.contains("to-edit"),
                "sync-back.cjs 缺少 --to-real / --to-edit 强制方向开关，"
                        + "方向判断出错时无处可退");

        // 2. 方向不明时必须中止，而不是随便挑一个方向
        assertTrue(src.contains("process.exit(3)"),
                "sync-back.cjs 在时间戳接近（无法判断方向）时应以非 0 退出码中止，"
                        + "当前实现可能仍会选一个方向执行，从而覆盖掉另一份的改动");

        // 3. 写入必须在方向判断之后，不能是无条件覆盖。
        // 注意不能简单断言「不存在 writeFileSync(REAL, a)」——那正是
        // --to-real 分支里该有的写法；真正要禁的是「没有前置判断就写」。
        int guard = src.indexOf("if (dir === 'to-real')");
        int writeReal = src.indexOf("writeFileSync(REAL");
        int writeEdit = src.indexOf("writeFileSync(EDIT");
        assertTrue(guard >= 0,
                "sync-back.cjs 写入正式源前必须先判断同步方向（if (dir === 'to-real')）");
        assertTrue(writeReal > guard,
                "writeFileSync(REAL) 出现在方向判断之前，等同于无条件覆盖："
                        + "会静默丢掉正式源里的改动");
        assertTrue(writeEdit > guard,
                "writeFileSync(EDIT) 出现在方向判断之前，同上");
    }
}