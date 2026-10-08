package dev.helstera.web;

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
 * 路径安全审计：任何来自 HTTP 输入的路径片段都必须先过白名单。
 *
 * <p><b>这个测试存在的理由</b>：网页开发器直接处理不可信输入，而「把用户
 * 提供的字符串直接 {@code resolve()} 进文件系统」是这条链上最典型的漏洞。
 * 本项目出现过一次真实缺口：{@code mobVersions} 的 {@code name} 参数只经过
 * {@code stripYml}（去后缀）就拿去 {@code resolve}，
 * {@code ?name=../../..} 能列出服务器上任意目录的内容——只读，
 * 但足以泄露目录结构，且没有任何报错。</p>
 *
 * <p>本测试刻意<b>不做语义分析</b>，只做一件事：把「参数直接拼进路径」的行
 * 全找出来，逐行要求它出现在本文件末尾的 {@link #REVIEWED} 清单里。
 * 清单条目必须写明「这个参数已经被哪个白名单函数校验过」，
 * 这样漏掉时能立刻看出该补哪个校验。</p>
 *
 * <p>为什么用「清单」而不是自动判定是否安全：自动判定需要判断
 * {@code safeMobPath} 之类的函数是否真的做了白名单，而这需要解析 Java，
 * 代价高且不稳定（本项目已在 {@code SwitchFallThroughAuditTest} 上吃过亏）。
 * 清单把判断交给人，但用「漏了就红」的方式保证它不会腐化。</p>
 */
class PathTraversalAuditTest {

    private static final Path SERVER = Path.of("src", "main", "java", "dev", "helstera", "web",
            "WebServerService.java");

    private static String read() throws IOException {
        return Files.readString(SERVER, StandardCharsets.UTF_8);
    }

    /**
     * 已复核为安全的「参数拼路径」行，按行内出现的校验函数名索引。
     *
     * <p>key 是校验方式，value 是该方式覆盖的行特征片段。测试只要求
     * 每一处可疑行都能在某个 value 里找到对应片段，不要求反过来成立。</p>
     */
    private static final String[][] REVIEWED = {
            // safeMobPath：内部做 [A-Za-z0-9_-]+ 白名单 + normalize + startsWith
            {"safeMobPath", "safeMobPath(name)"},
            {"safeMobPath 校验后的文件名", "mobFile.getFileName().toString()"},
            {"fileStem 来自 safeMobPath 结果", "fileStem(mobFile.getFileName().toString())"},
            // 源路径用裸 stripYml，但外层有 vFile.startsWith(versionsDir) 兜底。
            // 单独记一条，避免后来者以为这里的 stripYml 是漏网之鱼而重复加固。
            {"startsWith(versionsDir) 兜底", "vFile.startsWith(versionsDir)"},
            // safeModelDir：白名单 + 拒绝 .. + normalize + startsWith
            {"safeModelDir", "safeModelDir(rel)"},
            // dir 来自 safeModelDir，n 来自 MODEL_FILES 常量循环
            {"dir 来自 safeModelDir，n 来自 MODEL_FILES 常量循环", "dir.resolve(n)"},
            {"modelSave：name 先过 MODEL_FILE_SET 白名单，dir 来自 safeModelDir", "dir.resolve(name)"},
            // 模型导入解压：normalize + startsWith(target) 防 zip slip。
            // 两条（zip 的 e.getName() 与 JSON 的 rel）走同一套防护。
            {"zip slip 防护", "dest.startsWith(target)"},
            {"zip slip 防护（zip 分支）", "target.resolve(e.getName().replace('\\\\', '/'))"},
            {"zip slip 防护（JSON files 分支）", "target.resolve(rel.replace('\\\\', '/'))"},
            // 固定字面量或内部常量，不是用户输入
            {"固定字面量", "resolve(\"mobs\")"},
            {"固定字面量", "resolve(\"textures\")"},
            {"固定字面量", "dataFolder.resolve("},
            {"固定字面量", "resolve(kind)"},
            {"固定字面量", "resolve(\"skills.yml\")"},
            {"固定字面量", "resolve(\"spawners.yml\")"},
            {"固定字面量", "resolve(\"loot.yml\")"},
            {"固定字面量", "resolve(\"web/versions\")"},
            {"固定字面量", "resolve(\"web/audit.log\")"},
            // safeMobPath / safeModelDir 内部：n、r 已过白名单正则
            {"白名单后的名字", "mobsDir.resolve(n + \".yml\")"},
            {"白名单后的相对路径", "modelsRoot.resolve(r)"},
            // backup()：kind 是内部常量，label 由调用方传入且已替换过分隔符，
            // version 是时间戳字符串
            {"backup 内部，参数均非用户输入", "vDir.resolve(version + \"_\" + file.getFileName())"},
    };

    /**
     * 疑似把输入直接拼进路径的行。
     *
     * <p>识别依据：出现 {@code X.resolve(Y)} 且 Y 是标识符（不是字符串字面量、
     * 不是已知白名单函数调用）。刻意的漏网：直接字符串拼接（如
     * {@code resolve(kind + "/" + x)}）在多行里会漏检，但那些路径
     * 都在 {@code backup()} 等只接受内部常量的方法里。</p>
     */
    private static final Pattern SUSPECT = Pattern.compile(
            "\\.resolve\\(\\s*([A-Za-z_][A-Za-z0-9_]*)");

    @Test
    @DisplayName("把输入拼进路径的行，都已复核过防护方式")
    void everyResolvedIdentifierIsReviewed() throws IOException {
        String src = read();
        List<String> lines = Files.readAllLines(SERVER, StandardCharsets.UTF_8);

        List<String> suspects = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = SUSPECT.matcher(line);
            while (m.find()) {
                String arg = m.group(1);
                // 白名单函数本身就是校验入口，不算可疑
                if (arg.startsWith("safe") || arg.equals("stripYml") || arg.equals("fileStem")) {
                    continue;
                }
                suspects.add((i + 1) + "行: " + line.strip());
            }
        }

        List<String> unreviewed = new ArrayList<>();
        for (String s : suspects) {
            boolean covered = false;
            for (String[] rule : REVIEWED) {
                if (s.contains(rule[1])) {
                    covered = true;
                    break;
                }
            }
            if (!covered) unreviewed.add(s);
        }

        assertTrue(unreviewed.isEmpty(),
                "以下行把标识符直接 resolve 进路径，且未在 REVIEWED 里登记防护方式：\n  "
                        + String.join("\n  ", unreviewed)
                        + "\n\n请确认该标识符是否来自用户输入：若是，补白名单校验；"
                        + "若不是，在 REVIEWED 里登记并写明理由。");
    }

    @Test
    @DisplayName("REVIEWED 里引用的代码片段仍然存在，避免清单腐化")
    void reviewedSnippetsStillExist() throws IOException {
        String src = read();
        List<String> stale = new ArrayList<>();
        for (String[] rule : REVIEWED) {
            if (!src.contains(rule[1])) {
                stale.add(rule[0] + " → " + rule[1]);
            }
        }
        assertTrue(stale.isEmpty(),
                "REVIEWED 登记的片段在源码里已不存在，应删除对应条目：\n  "
                        + String.join("\n  ", stale));
    }

    /**
     * 路径工具函数不能被改成空壳。
     *
     * <p>这是清单机制最关键的一道防线：若有人把 {@code safeMobPath} 简化成
     * 「直接拼接返回」，上面两个测试仍会全绿——因为它们只关心「哪些行被登记过」，
     * 不关心登记理由是否还成立。没有这条断言，清单就会在某次重构后变成
     * 一堆指向空壳的合法声明，安全防护消失而测试毫无反应。</p>
     */
    @Test
    @DisplayName("路径白名单函数确实在做校验，不是被登记的空壳")
    void pathHelpersActuallyValidate() throws IOException {
        String src = read();
        assertTrue(src.contains("matches(\"[A-Za-z0-9_\\\\-]+\")"),
                "safeMobPath 的白名单正则消失了——请确认防护是否被移除");
        assertTrue(src.contains("r.contains(\"..\")"),
                "safeModelDir 不再拒绝 .. ——目录穿越防护已失效");
        assertTrue(src.contains("startsWith(mobsDir)"),
                "safeMobPath 的 normalize + startsWith 兜底消失了");
        assertTrue(src.contains("startsWith(modelsRoot)"),
                "safeModelDir 的 normalize + startsWith 兜底消失了");
        assertTrue(src.contains("MODEL_FILE_SET.contains(name)"),
                "modelSave 的文件名白名单消失了——任意文件可被覆写");
    }
}