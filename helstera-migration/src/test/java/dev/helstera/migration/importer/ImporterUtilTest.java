package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入器公共路径的回归测试。
 *
 * <p>导入失败是静默的——只体现在报告里。字段一旦映射错了，用户不会收到任何提示，
 * 只能等发现「设置没生效」再回头查。这里锁住最容易出错的几处。</p>
 */
class ImporterUtilTest {

    private static Path write(Path dir, String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    @DisplayName("findYml 递归收集 yml，忽略其它扩展名")
    void findsYmlRecursively(@TempDir Path dir) throws Exception {
        write(dir, "a.yml", "k: 1");
        write(dir, "sub/b.yml", "k: 2");
        write(dir, "sub/deep/c.yml", "k: 3");
        Files.writeString(dir.resolve("readme.txt"), "x");

        List<Path> found = ImporterUtil.findYml(dir);
        assertEquals(3, found.size(), "应找到 3 个 yml");
        assertTrue(found.stream().allMatch(p -> p.toString().endsWith(".yml")));
    }

    @Test
    @DisplayName("findYml 遇到不存在的目录返回空表而非抛异常")
    void missingDirYieldsEmpty(@TempDir Path dir) {
        assertTrue(ImporterUtil.findYml(dir.resolve("nope")).isEmpty());
    }

    @Test
    @DisplayName("load 读取 UTF-8 中文内容")
    void loadsUtf8(@TempDir Path dir) throws Exception {
        Path f = write(dir, "x.yml", "名称: 远古巨龙\n血量: 100\n");
        YamlConfiguration c = ImporterUtil.load(f);
        assertEquals("远古巨龙", c.getString("名称"));
        assertEquals(100, c.getInt("血量"));
    }

    @Test
    @DisplayName("load 遇到损坏文件返回空配置，不抛异常")
    void corruptFileYieldsEmpty(@TempDir Path dir) throws Exception {
        // 语法错误：冒号后无值且换行截断
        Path f = write(dir, "bad.yml", "a: [1, 2\nb: :::\n");
        YamlConfiguration c = ImporterUtil.load(f);
        assertNotNull(c, "损坏文件应返回空配置而非 null");
    }

    @Test
    @DisplayName("load 遇到不存在的文件不抛异常")
    void missingFileYieldsEmpty(@TempDir Path dir) {
        assertNotNull(ImporterUtil.load(dir.resolve("ghost.yml")));
    }

    @Test
    @DisplayName("entry 六字段齐全，note 为 null 时归一成空串")
    void entryShape() {
        Map<String, Object> e = ImporterUtil.entry("src.yml", "a.b", "out.c", 42, "mapped", null);
        assertEquals("src.yml", e.get("source-file"));
        assertEquals("a.b", e.get("source-key"));
        assertEquals("out.c", e.get("target-key"));
        assertEquals("42", e.get("value"), "value 应统一转字符串便于展示");
        assertEquals("mapped", e.get("status"));
        assertEquals("", e.get("note"), "null note 应归一为空串，避免前端渲染 undefined");
    }

    @Test
    @DisplayName("copySection 把已知字段映射到目标路径")
    void mapsKnownFields(@TempDir Path dir) throws Exception {
        Path f = write(dir, "mob.yml", "MyMob:\n  Health: 250\n  Damage: 18\n  Display: \"&cBOSS\"\n");
        YamlConfiguration src = ImporterUtil.load(f);

        YamlConfiguration target = new YamlConfiguration();
        List<Map<String, Object>> entries = new ArrayList<>();
        ImporterUtil.copySection(src.getConfigurationSection("MyMob"), target, "mobs.MyMob", entries, "mob.yml");

        assertEquals(250, target.getInt("mobs.MyMob.health"));
        assertEquals(18, target.getInt("mobs.MyMob.attack-damage"));
        assertEquals("&cBOSS", target.getString("mobs.MyMob.display-name"));
        assertEquals(3, entries.size());
        assertTrue(entries.stream().allMatch(e -> "mapped".equals(e.get("status"))));
    }

    @Test
    @DisplayName("copySection 对未知字段保留原值，不静默丢弃")
    void preservesUnknownFields(@TempDir Path dir) throws Exception {
        Path f = write(dir, "mob.yml", "MyMob:\n  Health: 10\n  SomeVendorOnlyField: hello\n");
        YamlConfiguration src = ImporterUtil.load(f);

        YamlConfiguration target = new YamlConfiguration();
        List<Map<String, Object>> entries = new ArrayList<>();
        ImporterUtil.copySection(src.getConfigurationSection("MyMob"), target, "mobs.MyMob", entries, "mob.yml");

        assertEquals("hello", target.getString("mobs.MyMob.unsupported.SomeVendorOnlyField"),
                "未知字段必须落到 unsupported，不能消失");
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))));
        String note = entries.stream().filter(e -> "unsupported".equals(e.get("status")))
                .findFirst().orElseThrow().get("note").toString();
        assertFalse(note.isEmpty(), "unsupported 条目应带说明，告诉用户原值被保留在哪里");
    }

    @Test
    @DisplayName("配置节本身不入 unsupported，但其叶子值会被保留")
    void skipsNestedSections(@TempDir Path dir) throws Exception {
        Path f = write(dir, "mob.yml", "MyMob:\n  Health: 5\n  Options:\n    FollowRange: 30\n");
        YamlConfiguration src = ImporterUtil.load(f);

        YamlConfiguration target = new YamlConfiguration();
        List<Map<String, Object>> entries = new ArrayList<>();
        ImporterUtil.copySection(src.getConfigurationSection("MyMob"), target, "mobs.MyMob", entries, "mob.yml");

        // getKeys(true) 会同时给出 "Options" 与 "Options.FollowRange"：
        // 前者被 isConfigurationSection 拦下，后者是叶子值，走 unsupported 保留。
        assertFalse(entries.stream().anyMatch(e -> "mobs.MyMob.unsupported.Options".equals(e.get("target-key"))),
                "配置节本身不应作为独立字段被搬运");
        assertEquals(30, target.getInt("mobs.MyMob.unsupported.Options.FollowRange"),
                "子节里的叶子值仍须保留，不能因跳过节而丢失");
    }

    @Test
    @DisplayName("同一语义的多种拼写归一到同一目标键")
    void fieldAliases(@TempDir Path dir) throws Exception {
        // Type 与 MobType 都映射到 entity-type
        Path f = write(dir, "a.yml", "M:\n  Type: ZOMBIE\n  MobType: SKELETON\n");
        YamlConfiguration src = ImporterUtil.load(f);

        YamlConfiguration target = new YamlConfiguration();
        List<Map<String, Object>> entries = new ArrayList<>();
        ImporterUtil.copySection(src.getConfigurationSection("M"), target, "m", entries, "a.yml");

        // 两个别名各记一条报告，但都写到同一个目标键
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(e -> "m.entity-type".equals(e.get("target-key"))),
                "两种写法应归一到同一目标键");
        assertTrue(target.contains("m.entity-type"));
        // 同键多次写入时最终值取决于遍历顺序，这里只锁「一定有值」，
        // 不断言是哪一个——顺序依赖是既有行为，不在本次要改的范围内。
        assertTrue(Set.of("ZOMBIE", "SKELETON").contains(target.getString("m.entity-type")));
    }
}