package dev.helstera.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置消费点审计：断言每个配置字段都被生产代码真正读到。
 *
 * <p>存在的理由：本项目反复出现同一类缺陷——配置键被解析进数据类、暴露成
 * getter，然后<b>没有任何生产代码读它</b>。已确认多处：{@code phases}、
 * {@code phases[].announce}、{@code min-tier-level}、{@code bone.hitbox}、
 * 生物级 {@code phases}/{@code require}、{@code SpawnOptions.showHealthBar}、
 * {@code SpawnOptions.aiProfile}。服务端表现统一为「配置写得越详细越没效果」，
 * 且日志里没有任何痕迹。</p>
 *
 * <p><b>判定规则</b>：字段名必须以<b>实例访问</b>形态（{@code x.field}）
 * 出现在生产源码中。刻意不用「字段名出现次数」——getter 定义与建造器赋值
 * 都会让字段名出现，却不代表有人读它；先剔除 {@code this.field = v} 形式的写入。</p>
 *
 * <p>类内裸访问（如 {@code hp >= minPercent}）无法用单一正则可靠区分，
 * 与其堆正则调优，不如把这些字段显式记入 {@link #EXEMPT} 并附理由——
 * 豁免表因此成为「已知例外」的显式清单，而非藏起来的死角。</p>
 *
 * <p>刻意不扫描测试源码：单测为覆盖率读 getter 是正常的，但那不代表运行期有人用。
 * 把测试排除后，本测试才能反映真实的运行期接线。</p>
 */
class ConfigConsumptionAuditTest {

    /** 审计覆盖的数据类：类名 -> 源码相对路径。 */
    private static final Map<String, String> AUDITED = new LinkedHashMap<>();

    static {
        AUDITED.put("AiProfile", "helstera-ai/src/main/java/dev/helstera/ai/AiProfile.java");
        AUDITED.put("BossPhase", "helstera-ai/src/main/java/dev/helstera/ai/BossPhase.java");
        AUDITED.put("DropTable", "helstera-ai/src/main/java/dev/helstera/ai/loot/DropTable.java");
        AUDITED.put("SpawnerService", "helstera-ai/src/main/java/dev/helstera/ai/spawner/SpawnerService.java");
        AUDITED.put("SpawnOptions", "helstera-api/src/main/java/dev/helstera/api/instance/SpawnOptions.java");
    }

    /** 审计豁免：无法用规则表达或已确认无需消费点，逐项附理由。 */
    private static final Map<String, String> EXEMPT = new LinkedHashMap<>();

    static {
        // ---- 类内裸访问：规则表达不了，记入显式清单 ----
        EXEMPT.put("BossPhase.minPercent", "类内裸访问，见 matches()");
        EXEMPT.put("BossPhase.maxPercent", "类内裸访问，见 matches() / coversFullHealth()");

        // ---- 运行期状态字段，非配置项 ----
        EXEMPT.put("SpawnerService.random", "Random 实例，非配置");
        EXEMPT.put("SpawnerService.task", "BukkitTask 句柄，非配置");
        EXEMPT.put("SpawnerService.tickCounter", "共享节拍计数器，非配置");
        EXEMPT.put("SpawnerService.minInterval", "装载期派生值，非配置键");
        EXEMPT.put("SpawnerService.aliveCheck", "插件层注入的回调，非配置键");
        EXEMPT.put("SpawnerService.problems", "装载期告警列表，供命令与网页开发器");
        EXEMPT.put("SpawnerService.alive", "实例追踪表，非配置");
        EXEMPT.put("SpawnerService.spawners", "装载表本身，非配置");

        // ---- 已知死配置：待接线，记此以免遗忘 ----
        // 这两项确实无运行期消费点。审计表在此显式挂账，
        // 接线完成后请删除对应行——删除后审计会重新接管。
        EXEMPT.put("SpawnOptions.showHealthBar", "已知死配置：项目内无 BossBar 机制，待接线");
        EXEMPT.put("SpawnOptions.aiProfile", "已知死配置：mobs/*.yml 走 ai 节独立路径，此项未被读取");
    }

    @Test
    @DisplayName("每个配置字段都被生产代码消费，无未挂账的死配置")
    void noDeadConfigFields() throws IOException {
        Path root = repoRoot();
        String corpus = productionCorpus(root);
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, String> e : AUDITED.entrySet()) {
            String cls = e.getKey();
            Path file = root.resolve(e.getValue());
            assertTrue(Files.exists(file), "找不到源文件: " + e.getValue() + "（仓库根判定错误？）");
            for (String field : fieldNames(Files.readString(file, StandardCharsets.UTF_8))) {
                String qualified = cls + "." + field;
                if (EXEMPT.containsKey(qualified)) continue;
                if (!isConsumed(corpus, field)) dead.add(qualified + "  <- " + e.getValue());
            }
        }
        assertTrue(dead.isEmpty(),
                "以下配置字段被解析但没有任何生产代码消费（配置写了不生效，且无日志痕迹）：\n  "
                        + String.join("\n  ", dead)
                        + "\n\n若确实无需消费，请在 EXEMPT 中挂账并写明理由。");
    }

    @Test
    @DisplayName("审计本身有效：能发现已知死配置，而不是恒通过")
    void auditDetectsKnownDeadField() throws IOException {
        // 若此断言失败，说明审计规则太松，上面的测试形同虚设。
        String corpus = productionCorpus(repoRoot());
        assertFalse(isConsumed(corpus, "showHealthBar"),
                "审计应能识别出 showHealthBar 无消费点");
        assertFalse(isConsumed(corpus, "aiProfile"),
                "审计应能识别出 aiProfile 无消费点");
        assertTrue(fieldNames(Files.readString(
                        repoRoot().resolve(AUDITED.get("SpawnOptions")), StandardCharsets.UTF_8))
                        .contains("showHealthBar"),
                "showHealthBar 应被识别为配置字段");
    }

    @Test
    @DisplayName("已知接线的字段不会被误报为死配置")
    void auditAcceptsLiveFields() throws IOException {
        String corpus = productionCorpus(repoRoot());
        // phases / announce 上轮刚接通，在 SkillTriggers 内消费。
        assertTrue(isConsumed(corpus, "phases"), "phases 已接通，不应被判为死配置");
        assertTrue(isConsumed(corpus, "announce"), "announce 已接通");
        // Spawner 的内部类字段在同文件以 sp.xxx() 形式消费——
        // 这正是不能用「跳过声明文件」规则的原因。
        assertTrue(isConsumed(corpus, "minPlayers"), "内部类字段在同文件被消费，不应误报");
        assertTrue(isConsumed(corpus, "yRange"), "内部类字段在同文件被消费，不应误报");
        // 掉落表字段由 LootService 跨文件消费。
        assertTrue(isConsumed(corpus, "luckScaling"), "掉落表字段跨文件被消费");
    }

    @Test
    @DisplayName("建造器写入不算消费，只有实例读取才算")
    void builderWriteIsNotConsumption() {
        // this.x = v 形式的赋值必须被剔除：否则每个建造器字段都会被自己的 setter
        // 判成已消费，整张审计表就失去意义。
        assertFalse(isConsumed("public SpawnOptions glowing(boolean v) { this.glowing = v; return this; }",
                "glowing"), "建造器赋值不构成消费");
        assertTrue(isConsumed("opts.glowing()", "glowing"), "读取才算消费");
        // getter 定义本身也不构成消费
        assertFalse(isConsumed("public boolean glowing() { return glowing; }", "glowing"),
                "getter 定义不构成消费");
    }

    @Test
    @DisplayName("字段名提取不含方法声明与局部变量")
    void fieldNamesAreFields() {
        String src = """
                private boolean glowing = false;
                private String displayName = null;
                public boolean showName() { return showName; }
                public SpawnOptions scale(double v) { this.scale = v; return this; }
                """;
        // 只提取字段。showName/scale 后面跟的是 '(' 与参数，属方法声明，不算字段。
        assertEquals(Set.of("glowing", "displayName"), fieldNames(src), "只应提取字段，方法名不算");
    }

    @Test
    @DisplayName("豁免项都有理由，不留空挂账")
    void everyExemptionHasAReason() {
        for (Map.Entry<String, String> e : EXEMPT.entrySet()) {
            assertFalse(e.getValue() == null || e.getValue().isBlank(),
                    "豁免项 " + e.getKey() + " 必须写明理由");
        }
    }

    // ------------------------------------------------------------------
    // 实现
    // ------------------------------------------------------------------

    /**
     * 字段是否被生产代码读取：要求出现实例访问 {@code x.field}。
     *
     * <p>先剔除 {@code this.field = v} 形态的写入，再匹配 {@code .field}。</p>
     */
    private static boolean isConsumed(String corpus, String field) {
        String scrubbed = Pattern.compile("\\bthis\\s*\\.\\s*" + Pattern.quote(field) + "\\b")
                .matcher(corpus).replaceAll("this");
        return Pattern.compile("\\.\\s*" + Pattern.quote(field) + "\\b").matcher(scrubbed).find();
    }

    /** 全部生产源码拼成的单一文本（排除测试）。 */
    private static String productionCorpus(Path root) throws IOException {
        List<Path> sources = mainSources(root);
        assertFalse(sources.isEmpty(), "未找到任何生产源码，仓库根判定可能错误");
        StringBuilder sb = new StringBuilder();
        for (Path java : sources) {
            sb.append(Files.readString(java, StandardCharsets.UTF_8)).append('\n');
        }
        return sb.toString();
    }

    /** 仓库内所有生产源码（排除测试与 target）。 */
    private static List<Path> mainSources(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path module : moduleDirs(root)) {
            Path src = module.resolve("src/main/java");
            if (!Files.isDirectory(src)) continue;
            try (var walk = Files.walk(src)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    private static List<Path> moduleDirs(Path root) throws IOException {
        try (var s = Files.list(root)) {
            return s.filter(p -> Files.isDirectory(p)
                            && p.getFileName().toString().startsWith("helstera-"))
                    .collect(Collectors.toList());
        }
    }

    /** 提取字段声明名：其后必须紧跟 {@code =} 或 {@code ;}，以排除方法声明。 */
    private static Set<String> fieldNames(String src) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile(
                        "^\\s*(?:private|protected|public)\\s+(?:static\\s+)?(?:final\\s+)?"
                                + "[A-Za-z_][\\w.<>,\\[\\]?]*\\s+([a-z_]\\w*)\\s*(?==|;)",
                        Pattern.MULTILINE)
                .matcher(src);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** 从当前工作目录向上找到仓库根。 */
    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++) {
            if (Files.exists(p.resolve("pom.xml")) && Files.isDirectory(p.resolve("helstera-ai"))) {
                return p;
            }
            p = p.getParent();
        }
        throw new IllegalStateException("未能定位仓库根（当前目录 "
                + Path.of("").toAbsolutePath().toString().toLowerCase(Locale.ROOT) + "）");
    }
}