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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ---------- MythicMobs：免疫 / 伤害倍率 ----------

    private static YamlConfiguration converted(Path f) throws Exception {
        Optional<Map<String, Object>> p = plan(new MythicMobsImporter().convert(f));
        assertTrue(p.isPresent(), "应产生写入计划");
        return YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(p.get().get("content"))));
    }

    @Test
    @DisplayName("MythicMobs：Immunities 落地为 mob.immunities，不再报 BLOCKER")
    void mythicMapsImmunities(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    Immunities:\n      - FIRE\n      - LAVA\n");
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        YamlConfiguration t = converted(f);
        assertEquals(List.of("FIRE", "LAVA"), t.getStringList("mob.immunities"),
                "MM 免疫清单应直接落地，否则用户得手工重建本可自动迁移的配置");
        assertFalse(entries.stream().anyMatch(e ->
                        "unsupported".equals(e.get("status"))
                                && String.valueOf(e.get("source-key")).contains("Immunities")),
                "已支持的 Immunities 不得再报 unsupported");
        assertTrue(t.get("mob.unsupported.Immunities") == null,
                "已迁移的节不应同时留在 unsupported 里");
    }

    @Test
    @DisplayName("MythicMobs：Immunities 写单条时退化成字符串也要生效")
    void mythicMapsSingleImmunity(@TempDir Path root) throws Exception {
        // YAML 里只写一条会退化成字符串；只判 isList 会让这一条静默消失
        Path f = write(root, "Mobs/b.yml", "mobs:\n  Boss:\n    Immunities: FIRE\n");
        assertEquals(List.of("FIRE"), converted(f).getStringList("mob.immunities"));
    }

    @Test
    @DisplayName("MythicMobs：DamageModifiers 裸倍率落地")
    void mythicMapsBareModifiers(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    DamageModifiers:\n      fire: 0.5\n      entity-attack: 2.0\n");
        YamlConfiguration t = converted(f);
        assertEquals(0.5, t.getDouble("mob.damage-modifiers.fire"), 1e-9);
        assertEquals(2.0, t.getDouble("mob.damage-modifiers.entity-attack"), 1e-9);
    }

    @Test
    @DisplayName("MythicMobs：带 multiplier 与 conditions 的 modifier 落地为条件规则")
    void mythicMapsConditionalModifier(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    DamageModifiers:\n      PROJECTILE:\n"
                        + "        multiplier: 0.25\n        conditions:\n          - enraged\n");
        YamlConfiguration t = converted(f);
        assertEquals(0.25, t.getDouble("mob.damage-modifiers.PROJECTILE.multiplier"), 1e-9);
        assertEquals(List.of("enraged"), t.getStringList("mob.damage-modifiers.PROJECTILE.conditions"),
                "条件必须一起迁移：丢掉它会让规则无条件生效，强度直接翻倍");
    }

    @Test
    @DisplayName("MythicMobs：只有 conditions 时按免疫迁移")
    void mythicMapsConditionsOnlyAsImmune(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    DamageModifiers:\n      FIRE:\n        conditions:\n          - invulnerable\n");
        YamlConfiguration t = converted(f);
        assertTrue(t.getBoolean("mob.damage-modifiers.FIRE.immune"),
                "只有 conditions 无 multiplier 时应迁为免疫，否则这条规则会被丢弃");
        assertEquals(List.of("invulnerable"), t.getStringList("mob.damage-modifiers.FIRE.conditions"));
    }

    @Test
    @DisplayName("MythicMobs：多条目写法只迁第一条并提示需手工合并")
    void mythicFlagsMultipleModifiers(@TempDir Path root) throws Exception {
        // helstera 同一键只保留一条；MM 可写多条。静默只取第一条会让人以为全部迁完
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    DamageModifiers:\n      FIRE:\n"
                        + "        - multiplier: 0.5\n        - multiplier: 0.2\n");
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        assertEquals(0.5, converted(f).getDouble("mob.damage-modifiers.FIRE.multiplier"), 1e-9);
        assertTrue(entries.stream().anyMatch(e ->
                        String.valueOf(e.get("note")).contains("需手工合并")),
                "只迁第一条时必须提示其余条目需手工合并");
    }

    @Test
    @DisplayName("MythicMobs：无法识别的 modifier 条目报 WARN 而非静默丢弃")
    void mythicWarnsOnUnknownModifier(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml",
                "mobs:\n  Boss:\n    DamageModifiers:\n      FIRE:\n        nonsense: true\n");
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        assertTrue(entries.stream().anyMatch(e ->
                        "unsupported".equals(e.get("status")) && "WARN".equals(e.get("severity"))),
                "识别不出的条目必须告警，静默丢弃正是本项目最想消灭的失败模式");
    }

    @Test
    @DisplayName("MythicMobs：未配置免疫时不产生空节")
    void mythicNoEmptyImmunitySections(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml", "mobs:\n  Boss:\n    Health: 10\n");
        YamlConfiguration t = converted(f);
        assertNull(t.get("mob.immunities"), "未配置时不该写空列表");
        assertNull(t.get("mob.damage-modifiers"), "未配置时不该写空节");
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