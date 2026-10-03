package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四个外部插件导入器的解析回归测试。
 *
 * <p>关注点各不相同：MythicMobs 要保证生物 ID 合法且已知字段能落地；
 * ItemAdder 要保证命名空间拼接正确；CraftEngine 只统计叶子值；
 * ModelEngine 无法转换模型、必须明确告知用户导出路径——它靠一条
 * "unsupported" 报告承载引导，错了用户就只能手动猜。</p>
 */
class ImportersTest {

    private static Path write(Path dir, String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    /** 取写入计划条目（含 target-file），与逐字段映射条目区分开。 */
    private static Optional<Map<String, Object>> plan(List<Map<String, Object>> entries) {
        return entries.stream().filter(e -> e.containsKey("target-file")).findFirst();
    }

    // ---------- MythicMobs ----------

    @Test
    @DisplayName("MythicMobs：扫描 Mobs 目录下的 yml")
    void mythicScans(@TempDir Path root) throws Exception {
        write(root, "Mobs/ bosses.yml", "mobs:\n  A:\n    Health: 1\n");
        write(root, "Mobs/extra/b.yml", "mobs:\n  B:\n    Health: 1\n");
        write(root, "other/c.yml", "x: 1\n");
        List<Path> found = new MythicMobsImporter().scan(root);
        assertEquals(2, found.size(), "只应扫 Mobs 目录，且限深 3 层");
        assertTrue(found.stream().allMatch(p -> p.toString().contains("Mobs")));
    }

    @Test
    @DisplayName("MythicMobs：生物 ID 归一为合法标识符")
    void mythicNormalisesId(@TempDir Path root) throws Exception {
        // 含空格与横线的名称必须被替换，否则会生成非法的 mobs 文件名
        Path f = write(root, "Mobs/b.yml", "mobs:\n  \"Fire Lord-01\":\n    Health: 100\n");
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        Optional<Map<String, Object>> p = plan(entries);
        assertTrue(p.isPresent(), "应产生写入计划");
        assertEquals("mobs/mythic_fire_lord_01.yml", p.get().get("target-file"));
        assertTrue(p.get().get("target-file").toString().matches("mobs/mythic_[a-z0-9_]+\\.yml"),
                "生物 ID 只能含小写字母、数字与下划线");
    }

    @Test
    @DisplayName("MythicMobs：已知字段落地，Skills 进入 unsupported")
    void mythicMapsFields(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    Health: 500\n    Damage: 25\n    Display: \"&cBOSS\"\n");
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new MythicMobsImporter().convert(f)).get().get("content"))));

        assertEquals(1, target.getInt("schema-version"));
        assertEquals("mythic_boss", target.getString("id"));
        assertEquals(500, target.getInt("mob.health"));
        assertEquals(25, target.getInt("mob.attack-damage"));
        assertEquals("&cBOSS", target.getString("mob.display-name"));
        assertEquals("default", target.getString("mob.ai-profile"), "应给一个可用的 AI 档位，而不是留空");
    }

    @Test
    @DisplayName("MythicMobs：Skills 保留原值而非丢弃")
    void mythicPreservesSkills(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    Skills:\n      - skill{s=Thunderbolt} 0.5\n");
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).get().get("content"))));

        String skills = target.getString("mob.unsupported.Skills");
        assertTrue(skills != null && skills.contains("Thunderbolt"),
                "Skills 是 MythicMobs 特有语法，应保留原值供人工重建，不能丢");
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))));
    }

    @Test
    @DisplayName("MythicMobs：无 mobs 节时不产生写入计划")
    void mythicWithoutMobs(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml", "something-else:\n  x: 1\n");
        assertTrue(new MythicMobsImporter().convert(f).isEmpty());
    }

    // ---------- ItemAdder ----------

    @Test
    @DisplayName("ItemAdder：命名空间取自父目录名")
    void itemAdderNamespace(@TempDir Path root) throws Exception {
        Path f = write(root, "contents/my_pack/items/swords.yml",
                "items:\n  ruby_sword:\n    display_name: \"&cRuby Sword\"\n  ruby_axe:\n    material: DIAMOND\n");
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new ItemAdderImporter().convert(f)).get().get("content"))));

        assertEquals("itemadder", target.getString("items.my_pack_ruby_sword.source"));
        assertEquals("my_pack:ruby_sword", target.getString("items.my_pack_ruby_sword.id"),
                "物品 ID 应是 命名空间:名称");
        assertEquals("&cRuby Sword", target.getString("items.my_pack_ruby_sword.display-name"));
        assertTrue(target.contains("items.my_pack_ruby_axe.id"));
    }

    @Test
    @DisplayName("ItemAdder：优先读物品自带的 id，点号不被 YAML 解析拆掉")
    void itemAdderUsesDeclaredId(@TempDir Path root) throws Exception {
        // ItemAdder 的标准写法：键名不带点，真实命名空间 ID 写在 id 字段里
        Path f = write(root, "contents/my_pack/items/swords.yml",
                "items:\n  ruby_sword:\n    id: my_pack:swords.ruby_sword\n    material: DIAMOND\n");
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new ItemAdderImporter().convert(f)).get().get("content"))));

        String key = "items.my_pack_swords_ruby_sword";
        assertEquals("itemadder", target.getString(key + ".source"));
        assertEquals("my_pack:swords.ruby_sword", target.getString(key + ".id"),
                "应保留 ItemAdder 真实 ID，点号与冒号不能被改写");
    }

    @Test
    @DisplayName("ItemAdder：无 id 字段时回退到 命名空间:键名")
    void itemAdderFallsBackToKey(@TempDir Path root) throws Exception {
        Path f = write(root, "contents/my_pack/items.yml",
                "items:\n  plain_item:\n    material: STONE\n");
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new ItemAdderImporter().convert(f)).get().get("content"))));
        assertEquals("my_pack:plain_item", target.getString("items.my_pack_plain_item.id"));
    }

    @Test
    @DisplayName("ItemAdder：无 items 节时不产生写入计划")
    void itemAdderWithoutItems(@TempDir Path root) throws Exception {
        Path f = write(root, "contents/pack/other.yml", "blocks:\n  x:\n    material: STONE\n");
        assertTrue(new ItemAdderImporter().convert(f).isEmpty());
    }

    @Test
    @DisplayName("ItemAdder：扫描 contents 目录")
    void itemAdderScans(@TempDir Path root) throws Exception {
        write(root, "contents/pack/items.yml", "items:\n  x:\n    material: STONE\n");
        assertEquals(1, new ItemAdderImporter().scan(root).size());
        assertTrue(new ItemAdderImporter().scan(root.resolve("nonexistent")).isEmpty());
    }

    // ---------- CraftEngine ----------

    @Test
    @DisplayName("CraftEngine：只统计叶子值，不把配置节计入")
    void craftEngineCountsLeaves(@TempDir Path root) throws Exception {
        // 3 个叶子：items.sword.model / items.sword.id / config.id
        Path f = write(root, "resources/ce/items.yml",
                "items:\n  sword:\n    model: m/x\n    id: ce:sword\nconfig:\n  id: ce\n");
        List<Map<String, Object>> entries = new CraftEngineImporter().convert(f);
        Map<String, Object> e = entries.get(0);
        assertEquals("3 项", e.get("value"), "统计的是叶子值数量，配置节本身不计");
        assertTrue(e.get("target-key").toString().endsWith("craftengine.ce"));
        assertTrue(e.get("note").toString().contains("ce: -> craftengine:"));
    }

    @Test
    @DisplayName("CraftEngine：空文件不产生条目")
    void craftEngineEmptyFile(@TempDir Path root) throws Exception {
        assertTrue(new CraftEngineImporter().convert(write(root, "resources/ce/e.yml", "")).isEmpty());
    }

    @Test
    @DisplayName("CraftEngine：扫描 resources 目录")
    void craftEngineScans(@TempDir Path root) throws Exception {
        write(root, "resources/ce/a.yml", "x: 1\n");
        assertEquals(1, new CraftEngineImporter().scan(root).size());
    }

    // ---------- ModelEngine ----------

    @Test
    @DisplayName("ModelEngine：只扫 bbmodel 蓝图，不碰 yml")
    void modelEngineScansBlueprints(@TempDir Path root) throws Exception {
        write(root, "models/a.bbmodel", "{}");
        write(root, "models/b.yml", "x: 1\n");
        List<Path> found = new ModelEngineImporter().scan(root);
        assertEquals(1, found.size());
        assertTrue(found.get(0).toString().endsWith(".bbmodel"));
    }

    @Test
    @DisplayName("ModelEngine：蓝图无法转换，须给出导出指引")
    void modelEngineExplainsManualExport(@TempDir Path root) throws Exception {
        Path f = write(root, "models/dragon.bbmodel", "{}");
        Map<String, Object> e = new ModelEngineImporter().convert(f).get(0);

        assertEquals("unsupported", e.get("status"), "bbmodel 无法无损转换，必须如实标注");
        assertEquals("models/me_dragon/", e.get("target-key"));
        String note = e.get("note").toString();
        // 这条 note 是用户唯一的迁移指引，缺一项用户就只能自己猜目录结构
        assertTrue(note.contains("Blockbench"), "应说明用什么工具导出");
        assertTrue(note.contains("helstera-v1"), "应说明导出成什么格式");
        assertTrue(note.contains("plugins/helsteraMobs/models/me_dragon/"), "应给出确切的目标路径");
    }

    @Test
    @DisplayName("ModelEngine：蓝图名含非法字符时归一")
    void modelEngineNormalisesName(@TempDir Path root) throws Exception {
        Path f = write(root, "models/Fire-Dragon 2.bbmodel", "{}");
        Map<String, Object> e = new ModelEngineImporter().convert(f).get(0);
        assertTrue(e.get("target-key").toString().matches("models/me_[a-z0-9_]+/"));
    }
}