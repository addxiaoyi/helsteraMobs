package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        assertEquals(2, found.size(), "只应扫描 Mobs 目录");
        assertTrue(found.stream().allMatch(p -> p.toString().contains("Mobs")));
    }

    @Test
    void scansDeepSourcesAndResolvesDeepDropAndItemReferences(@TempDir Path root) throws Exception {
        Path mob = write(root, "Mobs/a/b/c/boss.yml", "Boss: {Type: ZOMBIE}\n");
        Path item = write(root, "Items/a/b/c/gear.yml", "Boss-Sword: {Id: DIAMOND_SWORD}\n");
        Path child = write(root, "DropTables/a/b/c/child.yml", "reward: {Drops: [Boss-Sword 1 1]}\n");
        Path parent = write(root, "DropTables/a/b/c/parent.yml", "parent: {Drops: [reward 1 1]}\n");
        var importer = new MythicMobsImporter();
        var scanned = importer.scan(root);
        assertTrue(scanned.containsAll(List.of(mob, item, child, parent)));
        var childPlan = importer.convert(child).stream().filter(e -> "loot.yml".equals(e.get("target-file"))
                && "mapped".equals(e.get("status"))).findFirst().orElseThrow();
        var childYaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(childPlan.get("content"))));
        assertEquals("boss_sword", childYaml.getString("tables.reward.entries.0.item"));
        var parentPlan = importer.convert(parent).stream().filter(e -> "loot.yml".equals(e.get("target-file"))
                && "mapped".equals(e.get("status"))).findFirst().orElseThrow();
        var parentYaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(parentPlan.get("content"))));
        assertEquals("reward", parentYaml.getString("tables.parent.entries.0.table"));
    }

    @Test
    void mythicScansYamlExtensionsAndResolvesTheirDropTables(@TempDir Path root) throws Exception {
        Path child = write(root, "DropTables/child.YAML", "reward:\n  Drops: [BREAD 1 1]\n");
        Path parent = write(root, "DropTables/parent.YML", "parent:\n  Drops: [reward 1 1]\n");
        Files.createDirectories(root.resolve("DropTables/not-a-file.yml"));
        var importer = new MythicMobsImporter();
        var scanned = importer.scan(root);
        assertTrue(scanned.contains(child));
        assertTrue(scanned.contains(parent));
        assertEquals(2, scanned.size());
        var plan = importer.convert(parent).stream().filter(entry -> "loot.yml".equals(entry.get("target-file"))
                && "mapped".equals(entry.get("status"))).findFirst().orElseThrow();
        var loot = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
        assertEquals("reward", loot.getString("tables.parent.entries.0.table"));
    }

    @Test
    void distinguishesNamedMobFromWrapperWithReservedChildName(@TempDir Path root) throws Exception {
        Path wrapped = write(root, "Mobs/wrapped.yml", "mobs:\n  Skills: {Type: ZOMBIE}\n");
        Path named = write(root, "Mobs/named.yml", "mobs: {Type: SKELETON, Health: 12}\n");
        var importer = new MythicMobsImporter();
        assertTrue(importer.convert(wrapped).stream().anyMatch(e -> "mobs/mythic_skills.yml".equals(e.get("target-file"))));
        assertTrue(importer.convert(named).stream().anyMatch(e -> "mobs/mythic_mobs.yml".equals(e.get("target-file"))));
    }

    @Test
    void preservesInvalidMobEntriesAlongsideValidDefinitions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/mixed.yml", """
                invalid: [zombie, 42]
                mobs:
                  broken: invalid-mob
                  Guard: {Type: SKELETON}
                """);
        var entries = new MythicMobsImporter().convert(file);
        assertTrue(entries.stream().anyMatch(e -> "mobs/mythic_guard.yml".equals(e.get("target-file"))));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "invalid".equals(e.get("source-key")) && String.valueOf(e.get("value")).contains("42")));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "mobs.broken".equals(e.get("source-key")) && "invalid-mob".equals(e.get("value"))));
    }

    @Test
    void mapsMobTypeAliasesAndPreservesAmbiguousTypes(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/types.yml", """
                First: {MobType: SKELETON}
                Second: {EntityType: CREEPER}
                Spaced: {Type: ' skeleton '}
                Lowercase: {MobType: creeper}
                Conflict: {Type: ZOMBIE, MobType: SKELETON}
                Invalid: {Type: NOT_A_MOB}
                Numeric: {Type: 12}
                """);
        var entries = new MythicMobsImporter().convert(file);
        for (var pair : Map.of("first", "SKELETON", "second", "CREEPER",
                "spaced", "SKELETON", "lowercase", "CREEPER").entrySet()) {
            var plan = entries.stream().filter(e -> ("mobs/mythic_" + pair.getKey() + ".yml").equals(e.get("target-file")))
                    .findFirst().orElseThrow();
            var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
            assertEquals(pair.getValue(), yaml.getString("mob.entity-type"));
            assertEquals(org.bukkit.entity.EntityType.valueOf(pair.getValue()),
                    org.bukkit.entity.EntityType.valueOf(yaml.getString("mob.entity-type").toUpperCase(java.util.Locale.ROOT)));
        }
        assertTrue(entries.stream().anyMatch(e -> "Type".equals(e.get("source-key"))
                && "mob.entity-type".equals(e.get("target-key")) && "SKELETON".equals(e.get("value"))));
        assertTrue(entries.stream().noneMatch(e -> "mobs/mythic_conflict.yml".equals(e.get("target-file"))));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "Conflict".equals(e.get("source-key")) && String.valueOf(e.get("value")).contains("SKELETON")));
        for (String name : List.of("Invalid", "Numeric")) {
            assertTrue(entries.stream().noneMatch(e -> ("mobs/mythic_" + name.toLowerCase() + ".yml").equals(e.get("target-file"))));
            assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status")) && name.equals(e.get("source-key"))));
        }
    }

    @Test
    @DisplayName("MythicMobs: mob option defaults honor config-mobs precedence except PreventTransformation")
    void mapsMobOptionDefaultsWithExplicitAndGlobalPrecedence(@TempDir Path root) throws Exception {
        write(root, "config-mobs.yml", """
                Configuration:
                  DefaultMobOptions:
                    PreventItemPickup: false
                    NoDamageTicks: 19
                    PreventLeashing: false
                    PreventTransformation: false
                """);
        Path file = write(root, "Mobs/defaults.yml", """
                GlobalDefaults:
                  Type: ZOMBIE
                ExplicitValues:
                  Type: ZOMBIE
                  Options:
                    PreventItemPickup: true
                    NoDamageTicks: 27
                    PreventLeashing: true
                    PreventTransformation: true
                    CanPickupItems: true
                """);

        var importer = new MythicMobsImporter();
        var entries = importer.convert(file, root);
        var global = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(entries.stream()
                .filter(e -> "mobs/mythic_globaldefaults.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow().get("content"))));
        var explicit = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(entries.stream()
                .filter(e -> "mobs/mythic_explicitvalues.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow().get("content"))));

        assertFalse(global.getBoolean("entity.prevent-item-pickup"));
        assertEquals(19, global.getInt("entity.no-damage-ticks"));
        assertFalse(global.getBoolean("mob.prevent-leashing"));
        assertTrue(global.getBoolean("mob.prevent-transformation"));
        assertTrue(entries.stream().anyMatch(e -> "defaulted".equals(e.get("status"))
                && "MythicMobs.Options.PreventTransformation.default".equals(e.get("source-key"))
                && "true".equals(e.get("value"))));
        assertTrue(entries.stream().noneMatch(e -> "defaulted".equals(e.get("status"))
                && String.valueOf(e.get("source-key")).contains("DefaultMobOptions.PreventTransformation")));
        assertTrue(explicit.getBoolean("entity.prevent-item-pickup"));
        assertEquals(27, explicit.getInt("entity.no-damage-ticks"));
        assertTrue(explicit.getBoolean("mob.prevent-leashing"));
        assertTrue(explicit.getBoolean("mob.prevent-transformation"));
        assertTrue(explicit.getBoolean("entity.can-pickup-items"));
        assertTrue(entries.stream().anyMatch(e -> "defaulted".equals(e.get("status"))
                && String.valueOf(e.get("source-key")).contains("DefaultMobOptions.PreventItemPickup")));
        assertTrue(entries.stream().anyMatch(e -> "mapped".equals(e.get("status"))
                && "Options.NoDamageTicks".equals(e.get("source-key"))
                && "27".equals(e.get("value"))));
    }

    @Test
    void preservesInvalidGlobalMobOptionAndUsesBuiltInDefault(@TempDir Path root) throws Exception {
        write(root, "config-mobs.yml", """
                Configuration:
                  DefaultMobOptions:
                    PreventItemPickup: maybe
                    NoDamageTicks: -4
                """);
        Path file = write(root, "Mobs/invalid-defaults.yml", """
                Probe:
                  Type: ZOMBIE
                """);

        var entries = new MythicMobsImporter().convert(file, root);
        var plan = entries.stream()
                .filter(e -> "mobs/mythic_probe.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
        assertTrue(yaml.getBoolean("entity.prevent-item-pickup"));
        assertEquals(10, yaml.getInt("entity.no-damage-ticks"));
        assertTrue(yaml.getBoolean("mob.prevent-leashing"));
        assertTrue(yaml.getBoolean("mob.prevent-transformation"));
        assertTrue(yaml.contains("mob.unsupported.DefaultMobOptions.PreventItemPickup"));
        assertTrue(yaml.contains("mob.unsupported.DefaultMobOptions.NoDamageTicks"));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && String.valueOf(e.get("source-key")).contains("DefaultMobOptions")));
    }

    @Test
    void mapsHealOnReloadBooleanAndPreservesMalformedValues(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/heal-options.yml", """
                mobs:
                  Protected:
                    Type: ZOMBIE
                    Options:
                      HealOnReload: yes
                  Control:
                    Type: ZOMBIE
                    Options:
                      HealOnReload: false
                  Invalid:
                    Type: ZOMBIE
                    Options:
                      HealOnReload: maybe
                  Missing:
                    Type: ZOMBIE
                """);
        var entries = new MythicMobsImporter().convert(file);
        var protectedMob = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(entries.stream()
                .filter(e -> "mobs/mythic_protected.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow().get("content"))));
        var controlMob = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(entries.stream()
                .filter(e -> "mobs/mythic_control.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow().get("content"))));
        var invalidMob = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(entries.stream()
                .filter(e -> "mobs/mythic_invalid.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow().get("content"))));
        var missingMob = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(entries.stream()
                .filter(e -> "mobs/mythic_missing.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow().get("content"))));

        assertTrue(protectedMob.getBoolean("mob.heal-on-reload"));
        assertFalse(controlMob.getBoolean("mob.heal-on-reload"));
        assertEquals("maybe", invalidMob.getString("mob.unsupported.Options.HealOnReload"));
        assertFalse(invalidMob.contains("mob.heal-on-reload"));
        assertFalse(missingMob.getBoolean("mob.heal-on-reload", false));
        assertFalse(missingMob.contains("mob.heal-on-reload"));
        assertTrue(entries.stream().anyMatch(e -> "mapped".equals(e.get("status"))
                && "Options.HealOnReload".equals(e.get("source-key"))
                && "true".equals(String.valueOf(e.get("value")))));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "Options.HealOnReload".equals(e.get("source-key"))
                && "WARN".equals(e.get("severity"))));
    }

    @Test
    void convertsContentInScannedSubdirectories(@TempDir Path root) throws Exception {
        var importer = new MythicMobsImporter();
        Path item = write(root, "Items/area/items.yml", "blade:\n  Id: DIAMOND_SWORD\n");
        Path skill = write(root, "Skills/area/skills.yml", "skills:\n  blast:\n    Skills: ['damage{amount=2}']\n");
        Path spawner = write(root, "Spawners/area/spawner.yml", "gate:\n  Mob: boss\n  X: 0\n  Z: 0\n");
        assertTrue(importer.convert(item).stream().anyMatch(e -> "items.yml".equals(e.get("target-file"))));
        assertTrue(importer.convert(skill).stream().anyMatch(e -> "skills.yml".equals(e.get("target-file"))));
        assertTrue(importer.convert(spawner).stream().anyMatch(e -> "spawners.yml".equals(e.get("target-file"))));
    }

    @Test
    @DisplayName("MythicMobs: imports standalone DropTables and preserves unsupported table conditions")
    void mythicImportsStandaloneDropTables(@TempDir Path root) throws Exception {
        Path file = write(root, "DropTables/boss.yml", """
                boss_reward:
                  TotalItems: 2
                  BonusLevelItems: 0.5
                  BiasStat: LOOT_BIAS
                  Conditions:
                    - is-monster
                  TriggerConditions:
                    - is-player
                  Drops:
                    - DIAMOND 1 0.25
                    - exp 5 100%
                    - MMOItems:SWORD 1 1
                conditional_reward:
                  Conditions:
                    - future-condition{value=x}
                  Drops:
                    - EMERALD 1 1
                trigger_reward:
                  TriggerConditions: [future-trigger]
                  Drops: [BREAD 1 1]
                """);
        MythicMobsImporter importer = new MythicMobsImporter();
        assertTrue(importer.scan(root).contains(file));

        List<Map<String, Object>> converted = importer.convert(file);
        Map<String, Object> plan = converted.stream()
                .filter(entry -> "mapped".equals(entry.get("status")) && "loot.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration loot = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(2, loot.getInt("tables.boss_reward.total-items"));
        assertEquals(0.5, loot.getDouble("tables.boss_reward.bonus-level-items"), 1e-9);
        assertEquals("LOOT_BIAS", loot.getString("tables.boss_reward.bias-stat"));
        assertTrue(converted.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "boss_reward.BiasStat".equals(entry.get("source-key"))));
        assertTrue(converted.stream().noneMatch(entry -> "unsupported".equals(entry.get("status"))
                && "boss_reward.BiasStat".equals(entry.get("source-key"))));
        assertEquals(List.of("is-monster"), loot.getStringList("tables.boss_reward.conditions"));
        assertEquals("is-player", loot.getStringList("tables.boss_reward.trigger-conditions").get(0));
        assertEquals("DIAMOND", loot.getString("tables.boss_reward.entries.0.item"));
        assertEquals("exp", loot.getString("tables.boss_reward.entries.1.item"));
        assertEquals(5, loot.getInt("tables.boss_reward.entries.1.amount-min"));
        assertFalse(loot.contains("tables.conditional_reward"));
        assertTrue(converted.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "conditional_reward".equals(entry.get("source-key"))));
        assertTrue(converted.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && String.valueOf(entry.get("value")).contains("MMOItems:SWORD")));
        var triggerWarning = converted.stream().filter(entry -> "trigger_reward.TriggerConditions".equals(entry.get("source-key"))
                && "unsupported".equals(entry.get("status"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(triggerWarning.get("value")).contains("future-trigger"));
        assertTrue(String.valueOf(triggerWarning.get("value")).contains("BREAD 1 1"));
    }

    @Test
    void invalidDropTableConditionEntriesPreventProjection(@TempDir Path root) throws Exception {
        Path file = write(root, "DropTables/invalid.yml", """
                null_dropper:
                  Conditions: [null]
                  Drops: [DIAMOND 1 1]
                numeric_trigger:
                  TriggerConditions: [42]
                  Drops: [EMERALD 1 1]
                blank_dropper:
                  Conditions: ['']
                  Drops: [BREAD 1 1]
                valid:
                  Conditions: [is-monster]
                  Drops: [GOLD_INGOT 1 1]
                """);
        var converted = new MythicMobsImporter().convert(file);
        var projected = converted.stream().filter(e -> "loot.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(projected.get("content"))));
        assertTrue(yaml.contains("tables.valid"));
        for (String name : List.of("null_dropper", "numeric_trigger", "blank_dropper")) {
            assertFalse(yaml.contains("tables." + name), name);
            assertTrue(converted.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                    && String.valueOf(e.get("source-key")).startsWith(name)), name);
        }
    }

    @Test
    void mergedNullDropConditionsRejectTablesAndParentReferences(@TempDir Path root) throws Exception {
        Path file = write(root, "DropTables/merged.yml", """
                defaults: &defaults
                  Conditions: null
                  TriggerConditions: null
                allowed: &allowed
                  Conditions: [is-monster]
                  TriggerConditions: [is-player]
                unsafe:
                  <<: *defaults
                  Drops: [DIAMOND 1 1]
                unsafe_first:
                  <<: [*defaults, *allowed]
                  Drops: [IRON_INGOT 1 1]
                safe_first:
                  <<: [*allowed, *defaults]
                  Drops: [GOLD_INGOT 1 1]
                safe:
                  <<: *defaults
                  Conditions: [is-monster]
                  TriggerConditions: [is-player]
                  Drops: [BREAD 1 1]
                parent:
                  Drops: [unsafe 1 1, BREAD 1 1]
                intermediate:
                  Drops: [unsafe 1 1]
                grandparent:
                  Drops: [intermediate 1 1, BREAD 1 1]
                """);
        var converted = new MythicMobsImporter().convert(file);
        var plans = converted.stream().filter(e -> "loot.yml".equals(e.get("target-file"))
                && "mapped".equals(e.get("status")) && e.containsKey("content"))
                .map(e -> YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(e.get("content"))))).toList();
        assertTrue(plans.stream().noneMatch(yaml -> yaml.contains("tables.unsafe")));
        assertTrue(plans.stream().noneMatch(yaml -> yaml.contains("tables.unsafe_first")));
        assertTrue(plans.stream().anyMatch(yaml -> yaml.contains("tables.safe")));
        assertTrue(plans.stream().anyMatch(yaml -> yaml.contains("tables.safe_first")));
        assertTrue(converted.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && String.valueOf(e.get("source-key")).startsWith("unsafe")));
        assertTrue(converted.stream().anyMatch(e -> "parent.Drops".equals(e.get("source-key"))
                && "unsupported".equals(e.get("status")) && String.valueOf(e.get("value")).contains("unsafe")));
        assertTrue(converted.stream().anyMatch(e -> "grandparent.Drops".equals(e.get("source-key"))
                && "unsupported".equals(e.get("status")) && String.valueOf(e.get("value")).contains("intermediate")));
    }

    @Test
    void explicitNullDropTableConditionsPreventProjection(@TempDir Path root) throws Exception {
        Path file = write(root, "DropTables/null-conditions.yml", """
                null_dropper:
                  Conditions: null
                  Drops: [DIAMOND 1 1]
                null_trigger:
                  TriggerConditions: ~
                  Drops: [EMERALD 1 1]
                mixed_case:
                  cOnDiTiOnS:
                  Drops: [IRON_INGOT 1 1]
                null_element:
                  Conditions: [null]
                  Drops: [COAL 1 1]
                conflicting_case:
                  Conditions: [is-monster]
                  conditions: null
                  Drops: [REDSTONE 1 1]
                unconditional:
                  Drops: [BREAD 1 1]
                conditional:
                  Conditions: [is-monster]
                  Drops: [GOLD_INGOT 1 1]
                """);
        var converted = new MythicMobsImporter().convert(file);
        var plans = converted.stream().filter(e -> "loot.yml".equals(e.get("target-file"))
                && "mapped".equals(e.get("status")) && e.containsKey("content"))
                .map(e -> YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(e.get("content")))))
                .toList();
        for (String name : List.of("null_dropper", "null_trigger", "mixed_case", "null_element", "conflicting_case")) {
            assertTrue(plans.stream().noneMatch(yaml -> yaml.contains("tables." + name)), name);
            assertTrue(converted.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                    && "WARN".equals(e.get("severity"))
                    && String.valueOf(e.get("source-key")).startsWith(name)), name);
        }
        for (String name : List.of("unconditional", "conditional")) {
            assertTrue(plans.stream().anyMatch(yaml -> yaml.contains("tables." + name)), name);
        }
    }

    @Test
    void standaloneDropTableReportsOnlyUnsafeRawEntries(@TempDir Path root) throws Exception {
        Path file = write(root, "DropTables/mixed.yml", """
                mixed:
                  Drops:
                    - {item: DIAMOND, chance: 1}
                    - {item: EMERALD, DropIf: ""}
                    - {item: BREAD, future-condition: required}
                    - GOLD_INGOT 1 1 unexpected-option
                    - {item: IRON_INGOT, Chance: ""}
                    - {item: COAL, table: another}
                    - {item: LAPIS_LAZULI, DropIf: ";"}
                    - {item: REDSTONE, NoDropIf: "|"}
                invalid_table:
                  Conditions: [""]
                  Drops:
                    - DIAMOND 1 1
                """);
        List<Map<String, Object>> converted = new MythicMobsImporter().convert(file);
        var plan = converted.stream().filter(entry -> "mapped".equals(entry.get("status"))
                && "loot.yml".equals(entry.get("target-file"))).findFirst().orElseThrow();
        var loot = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals("DIAMOND", loot.getString("tables.mixed.entries.0.item"));
        assertEquals(1, loot.getConfigurationSection("tables.mixed.entries").getKeys(false).size());
        var warning = converted.stream().filter(entry -> "mixed.Drops".equals(entry.get("source-key"))
                && "unsupported".equals(entry.get("status"))).findFirst().orElseThrow();
        String rejected = String.valueOf(warning.get("value"));
        assertTrue(rejected.contains("EMERALD"));
        assertTrue(rejected.contains("BREAD"));
        assertTrue(rejected.contains("GOLD_INGOT"));
        assertTrue(rejected.contains("IRON_INGOT"));
        assertTrue(rejected.contains("COAL"));
        assertTrue(rejected.contains("LAPIS_LAZULI"));
        assertTrue(rejected.contains("REDSTONE"));
        assertFalse(rejected.contains("DIAMOND"));
        assertTrue(converted.stream().anyMatch(entry -> "invalid_table".equals(entry.get("source-key"))
                && "unsupported".equals(entry.get("status"))));
    }

    @Test
    void malformedDropChancesRemainInMigrationWarningWithSafeSibling(@TempDir Path root) throws Exception {
        Path file = write(root, "DropTables/chances.yml", """
                rewards:
                  Drops:
                    - DIAMOND 1 1%0
                    - {item: EMERALD, chance: '25%%'}
                    - COAL 1 0x1p-1
                    - {item: IRON_INGOT, chance: '0.5d'}
                    - GOLD_INGOT 1 25%
                """);
        var converted = new MythicMobsImporter().convert(file);
        var plan = converted.stream().filter(entry -> "loot.yml".equals(entry.get("target-file"))
                && "mapped".equals(entry.get("status")) && entry.containsKey("content"))
                .findFirst().orElseThrow();
        var loot = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        var entries = loot.getConfigurationSection("tables.rewards.entries");
        assertEquals(1, entries.getKeys(false).size());
        assertEquals("GOLD_INGOT", entries.getString("0.item"));
        assertEquals(0.25, entries.getDouble("0.chance"), 1e-9);
        var warning = converted.stream().filter(entry -> "rewards.Drops".equals(entry.get("source-key"))
                && "unsupported".equals(entry.get("status")) && "WARN".equals(entry.get("severity")))
                .findFirst().orElseThrow();
        String preserved = String.valueOf(warning.get("value"));
        assertTrue(preserved.contains("DIAMOND 1 1%0"), preserved);
        assertTrue(preserved.contains("25%%"), preserved);
        assertTrue(preserved.contains("0x1p-1"), preserved);
        assertTrue(preserved.contains("0.5d"), preserved);
        assertFalse(preserved.contains("GOLD_INGOT"), preserved);
    }

    @Test
    void standalonePreservesNestedAmountsForBareAndStructuredReferences(@TempDir Path root) throws Exception {
        Path file = write(root, "DropTables/nested.yml", """
                child:
                  Drops: [BREAD 1 1]
                parent:
                  Drops:
                    - child 2 1
                    - {table: child, amount-min: 1, amount-max: 3}
                    - {table: child, amount: 1}
                    - {item: DIAMOND}
                """);
        List<Map<String, Object>> converted = new MythicMobsImporter().convert(file);
        var plan = converted.stream().filter(entry -> "loot.yml".equals(entry.get("target-file"))
                && String.valueOf(entry.get("content")).contains("parent:"))
                .findFirst().orElseThrow();
        var loot = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        var entries = loot.getConfigurationSection("tables.parent.entries");
        assertEquals(2, entries.getKeys(false).size());
        assertEquals("child", entries.getString("0.table"));
        assertEquals("DIAMOND", entries.getString("1.item"));
        var warning = converted.stream().filter(entry -> "parent.Drops".equals(entry.get("source-key"))
                && "unsupported".equals(entry.get("status")) && "WARN".equals(entry.get("severity")))
                .findFirst().orElseThrow();
        String preserved = String.valueOf(warning.get("value"));
        assertTrue(preserved.contains("child 2 1"), preserved);
        assertTrue(preserved.contains("amount-max=3"), preserved);
        assertFalse(preserved.contains("DIAMOND"), preserved);
    }

    @Test
    void normalizedTableCollisionsPreserveBareAndStructuredReferences(@TempDir Path root) throws Exception {
        write(root, "DropTables/children.yml", """
                Boss-A:
                  Drops: [BREAD 1 1]
                Boss_A:
                  Drops: [GOLD_INGOT 1 1]
                """);
        Path file = write(root, "DropTables/parent.yml", """
                parent:
                  Drops:
                    - Boss-A 1 1
                    - {table: Boss_A}
                    - {item: DIAMOND}
                """);
        List<Map<String, Object>> converted = new MythicMobsImporter().convert(file);
        var plan = converted.stream().filter(entry -> "loot.yml".equals(entry.get("target-file"))
                && "mapped".equals(entry.get("status"))).findFirst().orElseThrow();
        var loot = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        var entries = loot.getConfigurationSection("tables.parent.entries");
        assertEquals(1, entries.getKeys(false).size());
        assertEquals("DIAMOND", entries.getString("0.item"));
        var warning = converted.stream().filter(entry -> "parent.Drops".equals(entry.get("source-key"))
                && "unsupported".equals(entry.get("status")) && "WARN".equals(entry.get("severity")))
                .findFirst().orElseThrow();
        String preserved = String.valueOf(warning.get("value"));
        assertTrue(preserved.contains("Boss-A 1 1"), preserved);
        assertTrue(preserved.contains("Boss_A"), preserved);
    }

    @Test
    void collidingTableDefinitionsAreNeverProjectedIndividually(@TempDir Path root) throws Exception {
        Path first = write(root, "DropTables/one.yml", "Boss-A:\n  Drops: [DIAMOND 1 1]\n");
        Path second = write(root, "DropTables/two.yml", "Boss_A: null\n");
        var importer = new MythicMobsImporter();
        for (Path source : List.of(first, second)) {
            var converted = importer.convert(source);
            assertTrue(converted.stream().noneMatch(e -> "mapped".equals(e.get("status"))
                    && "loot.yml".equals(e.get("target-file")) && e.containsKey("content")));
            assertTrue(converted.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                    && "WARN".equals(e.get("severity"))
                    && String.valueOf(e.get("note")).contains("same ID")));
        }
    }

    @Test
    void ambiguousBareDropTableNamesArePreserved(@TempDir Path root) throws Exception {
        write(root, "Items/items.yml", "rewards:\n  Id: DIAMOND\n");
        write(root, "DropTables/duplicate.yml", "repeated:\n  Drops: [BREAD 1 1]\n");
        Path file = write(root, "DropTables/tables.yml", """
                rewards:
                  Drops: [DIAMOND 1 1]
                DIAMOND:
                  Drops: [BREAD 1 1]
                repeated:
                  Drops: [BREAD 1 1]
                parent:
                  Drops: [rewards 1 1, DIAMOND 1 1, repeated 1 1, GOLD_INGOT 1 1]
                """);
        var converted = new MythicMobsImporter().convert(file);
        var warning = converted.stream().filter(e -> "parent.Drops".equals(e.get("source-key"))
                && "unsupported".equals(e.get("status"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(warning.get("value")).contains("rewards 1 1"));
        assertTrue(String.valueOf(warning.get("value")).contains("DIAMOND 1 1"));
        assertTrue(String.valueOf(warning.get("value")).contains("repeated 1 1"));
        var plan = converted.stream().filter(e -> "loot.yml".equals(e.get("target-file"))
                && String.valueOf(e.get("content")).contains("parent:")).findFirst().orElseThrow();
        var loot = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals("GOLD_INGOT", loot.getString("tables.parent.entries.0.item"));
        assertEquals(1, loot.getConfigurationSection("tables.parent.entries").getKeys(false).size());
    }

    @Test
    void unreadableCatalogPreservesBareDropsAndReportsSource(@TempDir Path root) throws Exception {
        write(root, "Items/broken.yml", "item: [unterminated\n");
        Path file = write(root, "DropTables/tables.yml", """
                rewards:
                  Drops: [{item: DIAMOND}]
                parent:
                  Drops: [rewards 1 1, DIAMOND 1 1, {item: GOLD_INGOT}, {table: rewards}]
                """);
        var converted = new MythicMobsImporter().convert(file);
        var catalogWarning = converted.stream().filter(e -> "drop-catalog".equals(e.get("source-key")))
                .findFirst().orElseThrow();
        assertTrue(String.valueOf(catalogWarning.get("value")).contains("broken.yml"));
        var warning = converted.stream().filter(e -> "parent".equals(e.get("source-key"))
                && "unsupported".equals(e.get("status"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(warning.get("value")).contains("rewards 1 1"));
        assertTrue(String.valueOf(warning.get("value")).contains("DIAMOND 1 1"));
        assertTrue(converted.stream().noneMatch(e -> "mapped".equals(e.get("status"))
                && String.valueOf(e.get("content")).contains("parent:")));
        assertTrue(String.valueOf(warning.get("value")).contains("GOLD_INGOT"));
        assertTrue(String.valueOf(warning.get("value")).contains("rewards"));
    }

    @Test
    void unreadableTableCatalogPreservesStructuredTableReference(@TempDir Path root) throws Exception {
        write(root, "DropTables/broken.yml", "broken: [unterminated\n");
        write(root, "Items/gear.yml", "Boss-Sword: {Id: DIAMOND_SWORD}\n");
        Path file = write(root, "DropTables/tables.yml", """
                parent:
                  Drops:
                    - {table: child}
                    - {item: DIAMOND}
                    - {item: Boss-Sword}
                """);
        var converted = new MythicMobsImporter().convert(file);
        assertTrue(converted.stream().anyMatch(e -> "drop-catalog".equals(e.get("source-key"))
                && String.valueOf(e.get("value")).contains("broken.yml")));
        var warning = converted.stream().filter(e -> "parent.Drops".equals(e.get("source-key"))
                && "unsupported".equals(e.get("status"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(warning.get("value")).contains("child"));
        var plan = converted.stream().filter(e -> "loot.yml".equals(e.get("target-file"))
                && "mapped".equals(e.get("status"))).findFirst().orElseThrow();
        var loot = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
        assertEquals("DIAMOND", loot.getString("tables.parent.entries.0.item"));
        assertEquals("boss_sword", loot.getString("tables.parent.entries.1.item"));
        assertEquals(2, loot.getConfigurationSection("tables.parent.entries").getKeys(false).size());
    }

    @Test
    void normalizesKnownCustomDropItemsAndPreservesCollidingNames(@TempDir Path root) throws Exception {
        write(root, "Items/gear.yml", """
                Boss-Sword: {Id: DIAMOND_SWORD}
                Clash-Item: {Id: DIAMOND}
                Clash_Item: {Id: EMERALD}
                """);
        Path file = write(root, "DropTables/rewards.yml", """
                rewards:
                  Drops:
                    - Boss-Sword 1 1
                    - {item: Boss-Sword, chance: 1}
                    - Clash-Item 1 1
                """);
        var converted = new MythicMobsImporter().convert(file);
        var plan = converted.stream().filter(e -> "loot.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        var loot = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
        assertEquals("boss_sword", loot.getString("tables.rewards.entries.0.item"));
        assertEquals("boss_sword", loot.getString("tables.rewards.entries.1.item"));
        assertEquals(2, loot.getConfigurationSection("tables.rewards.entries").getKeys(false).size());
        var warning = converted.stream().filter(e -> "rewards.Drops".equals(e.get("source-key"))
                && "unsupported".equals(e.get("status"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(warning.get("value")).contains("Clash-Item"));
    }

    @Test
    void standaloneTableWithCaseVariantConditionsIsPreserved(@TempDir Path root) throws Exception {
        Path file = write(root, "DropTables/ambiguous.yml", """
                rewards:
                  Conditions: [is-player]
                  conditions: [is-monster]
                  Drops: [DIAMOND 1 1]
                """);
        var converted = new MythicMobsImporter().convert(file);
        assertTrue(converted.stream().noneMatch(e -> "mapped".equals(e.get("status"))
                && "loot.yml".equals(e.get("target-file"))));
        var warning = converted.stream().filter(e -> "unsupported".equals(e.get("status"))
                && "rewards".equals(e.get("source-key"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(warning.get("value")).contains("is-player"));
        assertTrue(String.valueOf(warning.get("value")).contains("is-monster"));
        assertTrue(String.valueOf(warning.get("value")).contains("DIAMOND"));
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
        assertEquals(List.of("Thunderbolt"), target.getStringList("mob.skills"));
    }

    @Test
    void eventSkillsAreRoutedOrPreserved(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/events.yml", """
                mobs:
                  Boss:
                    Skills:
                      - damage{amount=4} @Target ~onSpawn
                      - damage{amount=5} @Target ~ONSPAWN
                      - damage{amount=6} @Target ~onAttack
                      - damage{amount=7} @Trigger ~onKill
                      - ~onKill:KillReward
                      - ~onKillPlayer:PlayerKillReward
                      - skill{s=Child;delay=2} @Target ~onDeath
                      - damage{amount=3} @Trigger ~onDamaged
                      - damage{amount=2} @Trigger ~onHurt
                      - damage{amount=<skill.var.damage-amount>} @Trigger ~onDamaged
                      - damage{amount=1} ~onTimer:20
                      - damage{amount=1} ~unknown
                      - damage{amount=2} ~onSpawn ~onDeath
                      - teleport @Target ~onSpawn
                      - ~onSpawn:Child
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(4, target.getStringList("mob.triggers.on-spawn.do").size());
        assertEquals(List.of("skill{s=Child;delay=2} @Target"), target.getStringList("mob.triggers.on-death.do"));
        assertEquals(List.of("damage-target 6 @target"), target.getStringList("mob.triggers.on-attack.do"));
        assertEquals(List.of("damage-target 7 @trigger", "cast-skill KillReward"),
                target.getStringList("mob.triggers.on-kill.do"));
        assertEquals(List.of("cast-skill PlayerKillReward"),
                target.getStringList("mob.triggers.on-kill-player.do"));
        assertEquals(List.of("damage-target 3 @trigger", "damage-target 2 @trigger"),
                target.getStringList("mob.triggers.on-damage.do"));
        assertTrue(target.getStringList("mob.on-decision").isEmpty());
        assertEquals(List.of("damage{amount=<skill.var.damage-amount>} @Trigger ~onDamaged",
                        "damage{amount=1} ~unknown", "damage{amount=2} ~onSpawn ~onDeath"),
                target.getStringList("mob.unsupported.Skills"));
        assertEquals(20, target.getInt("mob.triggers.on-timer.interval"));
        assertEquals(true, target.getBoolean("mob.triggers.on-timer.global-clock"));
        assertEquals(List.of("damage-target 1"), target.getStringList("mob.triggers.on-timer.do"));
        assertTrue(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))));
    }

    @Test
    @DisplayName("Mythic onTimer：按全局周期映射，多个周期独立保留，非法周期原样告警")
    void mythicTimerTriggersKeepIndependentIntervals(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/timers.yml", """
                mobs:
                  Clockwork:
                    Skills:
                      - skill{s=FastA} ~onTimer:20
                      - skill{s=FastB} ~onTimer:20
                      - skill{s=Slow} ~onTimer:40
                      - skill{s=Zero} ~onTimer:0
                      - skill{s=Negative} ~onTimer:-1
                      - skill{s=Overflow} ~onTimer:2147483648
                      - skill{s=Malformed} ~onTimer:nope
                """);

        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        List<Map<?, ?>> timers = target.getMapList("mob.triggers.on-timer");

        assertEquals(2, timers.size(), "不同 interval 必须保留为独立分支");
        assertEquals(20, ((Number) timers.get(0).get("interval")).intValue());
        assertEquals(true, timers.get(0).get("global-clock"));
        assertEquals(List.of("skill{s=FastA}", "skill{s=FastB}"), timers.get(0).get("do"));
        assertEquals(40, ((Number) timers.get(1).get("interval")).intValue());
        assertEquals(List.of("skill{s=Slow}"), timers.get(1).get("do"));
        assertEquals(List.of("skill{s=Zero} ~onTimer:0", "skill{s=Negative} ~onTimer:-1",
                        "skill{s=Overflow} ~onTimer:2147483648", "skill{s=Malformed} ~onTimer:nope"),
                target.getStringList("mob.unsupported.Skills"));
        assertTrue(entries.stream().anyMatch(entry -> "WARN".equals(entry.get("severity"))));
    }

    @Test
    @DisplayName("MythicMobs：onSignal 过滤器映射为独立信号分支")
    void mythicSignalTriggersKeepSelectorsAndGenericBranch(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/signals.yml", """
                mobs:
                  Receiver:
                    Skills:
                      - message{m=any signal} ~onSignal
                      - message{m=phase two} ~onSignal:phase2
                      - skill{s=PhaseThree} ~onSignal:phase3
                      - 'message{m=bad} ~onSignal:phase-2'
                      - 'message{m=empty} ~onSignal:'
                      - '~onSignal:SignalReceiver'
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        List<?> variants = target.getList("mob.triggers.on-signal");
        assertNotNull(variants, "multiple signal selectors must remain separate branches");
        assertEquals(3, variants.size());
        assertEquals(null, ((Map<?, ?>) variants.get(0)).get("signal"));
        assertEquals("phase2", ((Map<?, ?>) variants.get(1)).get("signal"));
        assertEquals("phase3", ((Map<?, ?>) variants.get(2)).get("signal"));
        assertEquals(List.of("message-target \"any signal\""),
                ((Map<?, ?>) variants.get(0)).get("do"));
        assertEquals(List.of("message-target \"phase two\""),
                ((Map<?, ?>) variants.get(1)).get("do"));
        assertEquals(List.of("skill{s=PhaseThree}"),
                ((Map<?, ?>) variants.get(2)).get("do"));
        assertEquals(List.of("message{m=bad} ~onSignal:phase-2",
                        "message{m=empty} ~onSignal:", "~onSignal:SignalReceiver"),
                target.getStringList("mob.unsupported.Skills"));
    }

    @Test
    void invalidReviveHealthIsPreserved(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/revive.yml", "mobs:\n  Boss:\n    Options:\n      ReviveHealth: 0\n");
        var entries = new MythicMobsImporter().convert(f);
        var target = YamlConfiguration.loadConfiguration(new java.io.StringReader(
                String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertFalse(target.contains("mob.revive-health"));
        assertEquals(0, target.getInt("mob.unsupported.Options.ReviveHealth"));
        assertTrue(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))));
    }

    @Test
    void standaloneEventSkillsArePreserved(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/events.yml", """
                skills:
                  Parent:
                    Skills:
                      - damage{amount=4} ~onSpawn
                      - ~onDeath:Child
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(List.of("damage{amount=4} ~onSpawn", "~onDeath:Child"),
                target.getStringList("skills.parent.unsupported"));
        assertFalse(target.contains("skills.parent.triggers"));
        assertFalse(target.contains("skills.parent.on-decision"));
    }

    @Test
    @DisplayName("MythicMobs common named mechanics convert to native action syntax")
    void mythicMapsNamedSkillMechanics(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/combat.yml", """
                skills:
                  blast:
                    Skills:
                      - damage{amount=12}
                      - ignite{ticks=40}
                      - knockback{power=1.2;vertical=0.6}
                      - explosion{power=2;fire=true;breakblocks=false}
                      - giveitem{item=DIAMOND;amount=2}
                      - consume{item=DIAMOND;amount=1}
                      - closeinventory
                      - clearexp
                      - clearexplevels
                      - giveexplevels{a=2}
                      - ejectpassenger
                      - oxygen{a=20}
                      - consumeslot{slot=0;amount=1}
                      - clearinventory
                      - givexp 25
                      - setabsorption 6
                      - giveitem DIAMOND 3
                      - removeitem DIAMOND 1
                      - oxygen 20
                      - consumeslot HAND 2
                      - setblock STONE
                      - setsilent false
                      - disarm
                      - removehelditem
                      - setmaterialcooldown{material=ENDER_PEARL;ticks=20}
                      - setgamemode{mode=ADVENTURE}
                      - jump{velocity=0.42}
                      - forcepull{v=1.5;y=0.2}
                      - setmaterialcooldown ENDER_PEARL 30
                      - setitemcooldown{material=ENDER_PEARL;ticks=25}
                      - setgamemode CREATIVE
                      - setoffhand{item=SHIELD;amount=2}
                      - setarmor{helmet=IRON_HELMET;chestplate=IRON_CHESTPLATE;leggings=IRON_LEGGINGS;boots=IRON_BOOTS}
                      - setfaction{faction=guards}
                      - setlevel 5
                      - setweather{weather=rain;duration=20}
                      - movepin{pin=arena.spawn}
                      - movepin arena.exit
                      - settime noon
                      - lightning{effectonly=true}
                      - jump 0.5
                      - forcepull 2 0.4
                      - feed{amount=4}
                      - swing{hand=OFF_HAND}
                      - swing-main-hand
                      - setblock{material=STONE}
                      - breakblock
                      - setai{ai=false}
                      - setnogravity
                      - setsilent{silent=true}
                      - setcollidable{collidable=false}
                      - setsneaking{sneaking=true}
                      - setgliding{gliding=true}
                      - setswimming{swimming=true}
                      - setsprinting{sprinting=true}
                      - setflying{flying=true}
                      - setnodamageticks{ticks=10}
                      - setmaxnodamageticks{ticks=30}
                      - setwalkspeed{speed=0.4}
                      - setmaxhealth{amount=40}
                      - setfire{ticks=40}
                      - extinguish
                      - freeze{duration=80}
                      - lookat
                      - setrotation{yaw=90;pitch=15}
                      - setstance{stance=enraged}
                      - ~onKill:KillReward
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.blast.on-decision");
        assertTrue(actions.contains("damage-target 12"));
        assertTrue(actions.contains("ignite-targets 40"));
        assertTrue(actions.contains("knockback-targets 1.2 0.6"));
        assertTrue(actions.contains("explode 2 true false"));
        assertTrue(actions.contains("give-item DIAMOND 2"));
        assertTrue(actions.contains("take-item DIAMOND 1"));
        assertTrue(actions.contains("close-inventory"));
        assertTrue(actions.contains("clear-experience"));
        assertTrue(actions.contains("clear-experience-levels"));
        assertTrue(actions.contains("give-experience-levels 2"));
        assertTrue(actions.contains("eject-passenger"));
        assertTrue(actions.contains("oxygen 20"));
        assertTrue(actions.contains("consume-slot 0 1"));
        assertTrue(actions.contains("clear-inventory"));
        assertTrue(actions.contains("give-experience 25"));
        assertTrue(actions.contains("set-absorption 6"));
        assertTrue(actions.contains("give-item DIAMOND 3"));
        assertTrue(actions.contains("take-item DIAMOND 1"));
        assertTrue(actions.contains("oxygen 20"));
        assertTrue(actions.contains("consume-slot HAND 2"));
        assertTrue(actions.contains("set-block STONE"));
        assertTrue(actions.contains("set-silent false"));
        assertTrue(actions.contains("disarm"));
        assertTrue(actions.contains("set-material-cooldown ENDER_PEARL 20"));
        assertTrue(actions.contains("set-game-mode ADVENTURE"));
        assertTrue(actions.contains("jump 0.42"));
        assertTrue(actions.contains("force-pull 1.5 0.2"));
        assertTrue(actions.contains("set-material-cooldown ENDER_PEARL 30"));
        assertTrue(actions.contains("set-material-cooldown ENDER_PEARL 25"));
        assertTrue(actions.contains("set-game-mode CREATIVE"));
        assertTrue(actions.contains("set-offhand SHIELD 2"));
        assertTrue(actions.contains("set-armor IRON_HELMET IRON_CHESTPLATE IRON_LEGGINGS IRON_BOOTS"));
        assertTrue(actions.contains("set-faction guards"));
        assertTrue(actions.contains("set-level 5"));
        assertTrue(actions.contains("set-weather rain 20"));
        assertTrue(actions.contains("move-pin arena.spawn"));
        assertTrue(actions.contains("move-pin arena.exit"));
        assertTrue(actions.contains("set-time noon"));
        assertTrue(actions.contains("lightning true"));
        assertTrue(actions.contains("jump 0.5"));
        assertTrue(actions.contains("force-pull 2 0.4"));
        assertTrue(actions.contains("feed 4"));
        assertTrue(actions.contains("swing OFF_HAND"));
        assertTrue(actions.contains("swing MAIN_HAND"));
        assertTrue(actions.contains("set-block STONE"));
        assertTrue(actions.contains("break-block"));
        assertTrue(actions.contains("set-ai false"));
        assertTrue(actions.contains("set-gravity false"));
        assertTrue(actions.contains("set-silent true"));
        assertTrue(actions.contains("set-collidable false"));
        assertTrue(actions.contains("set-sneaking true"));
        assertTrue(actions.contains("set-gliding true"));
        assertTrue(actions.contains("set-swimming true"));
        assertTrue(actions.contains("set-sprinting true"));
        assertTrue(actions.contains("set-flying true"));
        assertTrue(actions.contains("set-no-damage-ticks 10"));
        assertTrue(actions.contains("set-maximum-no-damage-ticks 30"));
        assertTrue(actions.contains("set-walk-speed 0.4"));
        assertTrue(actions.contains("set-max-health 40"));
        assertTrue(actions.contains("set-fire 40"));
        assertTrue(actions.contains("clear-fire"));
        assertTrue(actions.contains("freeze 80"));
        assertTrue(actions.contains("look-at"));
        assertTrue(actions.contains("set-rotation 90 15"));
        assertTrue(actions.contains("set-stance enraged"));
        assertEquals(List.of("~onKill:KillReward"), target.getStringList("skills.blast.unsupported"));
    }

    @Test
    @DisplayName("Mythic mechanic targeter, chance, and inline condition survive conversion")
    void mythicPreservesMechanicExecutionContext(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/context.yml", """
                skills:
                  blast:
                    Skills:
                      - damage{amount=10} @PIR{r=8} 50% ?onGround
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        String action = target.getStringList("skills.blast.on-decision").get(0);
        assertEquals("damage-target 10 @playersinradius{r=8} 50% ?onGround", action);
    }

    @Test
    void positionalMechanicKeepsChanceAndConditionsWithoutTargeter(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/positional-context.yml", """
                skills:
                  blast:
                    Skills:
                      - 'damage 4 0%'
                      - 'damage 5 ?onGround'
                      - 'damage 6 50% ?onGround'
                      - 'damage 7 ?health{amount=10}'
                      - 'damage 8 @Target{limit=1}'
                """);
        var target = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(
                plan(new MythicMobsImporter().convert(file)).orElseThrow().get("content"))));
        assertEquals(List.of("damage-target 4 0%", "damage-target 5 ?onGround",
                "damage-target 6 50% ?onGround", "damage-target 7 ?health{amount=10}",
                "damage-target 8 @target{limit=1}"), target.getStringList("skills.blast.on-decision"));
    }

    @Test
    void ambiguousExecutionSuffixesArePreserved(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/ambiguous.yml", """
                skills:
                  test:
                    Skills:
                      - 'damage 4 0% 100%'
                      - 'damage 4 @self @target'
                      - 'damage 4 @self > > @target'
                      - 'damage 4 @self >'
                      - 'damage 4 101%'
                      - 'damage 4 NaN%'
                      - 'damage 4 1e999%'
                      - 'damage 4 ?'
                      - 'damage 4 @'
                      - 'damage 4 @self > @target'
                      - 'message{m="@self 0% 100%"} @target'
                      - 'message <target.name>'
                      - 'damage 4 ?health{h=>5}'
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(
                plan(entries).orElseThrow().get("content"))));
        assertEquals(List.of("damage 4 0% 100%", "damage 4 @self @target",
                        "damage 4 @self > > @target", "damage 4 @self >",
                        "damage 4 101%", "damage 4 NaN%", "damage 4 1e999%", "damage 4 ?", "damage 4 @"),
                target.getStringList("skills.test.unsupported"));
        assertEquals(4, target.getStringList("skills.test.on-decision").size());
        assertTrue(target.getStringList("skills.test.on-decision").contains("message-target \"<target.name>\""));
        assertEquals(9, entries.stream().filter(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("note")).contains("Invalid or ambiguous targeter, condition or chance suffix")).count());
    }

    @Test
    @DisplayName("Mythic passenger and vehicle targeters survive mechanic migration")
    void mythicPreservesPassengerAndVehicleTargeters(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/relations.yml", """
                skills:
                  relations:
                    Skills:
                      - message{m=Passenger} @Passengers
                      - message{m=Vehicle} @Vehicle
                      - message{m=Child} @Children
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.relations.on-decision");
        assertTrue(actions.contains("message-target \"Passenger\" @passengers"), actions.toString());
        assertTrue(actions.contains("message-target \"Vehicle\" @vehicle"), actions.toString());
        assertTrue(actions.contains("message-target \"Child\" @children"), actions.toString());
    }

    @Test
    @DisplayName("Mythic category radius targeter aliases normalize before runtime dispatch")
    void mythicNormalizesCategoryRadiusTargeters(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/categories.yml", """
                skills:
                  categories:
                    Skills:
                      - message{m=Monster} @Monsters_In_Radius{r=8}
                      - message{m=Animal} @Animals-In-Radius{r=8}
                      - message{m=Villager} @VillagersInRadius{r=8}
                      - message{m=Tamed} @TamedInRadius{r=8}
                      - message{m=Blocks} @BlocksInPinRegion{pin1=arena.min;pin2=arena.max}
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.categories.on-decision");
        assertTrue(actions.contains("message-target \"Monster\" @monstersinradius{r=8}"), actions.toString());
        assertTrue(actions.contains("message-target \"Animal\" @animalsinradius{r=8}"), actions.toString());
        assertTrue(actions.contains("message-target \"Villager\" @villagersinradius{r=8}"), actions.toString());
        assertTrue(actions.contains("message-target \"Tamed\" @tamedinradius{r=8}"), actions.toString());
        assertTrue(actions.contains("message-target \"Blocks\" @blocksinpinregion{pin1=arena.min;pin2=arena.max}"),
                actions.toString());
    }

    @Test
    @DisplayName("Mythic nearest category targeters normalize their canonical names")
    void mythicNormalizesNearestCategoryTargeters(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/nearest.yml", """
                skills:
                  nearest:
                    Skills:
                      - message{m=Player} @Nearest_Player
                      - message{m=Monster} @Nearest-Monster
                      - message{m=Animal} @NearestAnimal
                      - message{m=Villager} @NearestVillager
                      - message{m=Tamed} @NearestTamed
                      - message{m=Mob} @Nearest_Mob
                      - message{m=Living} @Nearest-Living-Entity
                      - message{m=World} @Players_In_World{limit=2}
                      - message{m=Skipped} @Players_In_World{stuti=1;limit=1}
                      - message{m=Server} @PlayersOnServer{targetself=true}
                      - message{m=Everyone} @everyone
                      - message{m=Chain} @Players_In_World{limit=2} > @Nearest_Mob
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.nearest.on-decision");
        assertTrue(actions.contains("message-target \"Player\" @nearestplayer"), actions.toString());
        assertTrue(actions.contains("message-target \"Monster\" @nearestmonster"), actions.toString());
        assertTrue(actions.contains("message-target \"Animal\" @nearestanimal"), actions.toString());
        assertTrue(actions.contains("message-target \"Villager\" @nearestvillager"), actions.toString());
        assertTrue(actions.contains("message-target \"Tamed\" @nearesttamed"), actions.toString());
        assertTrue(actions.contains("message-target \"Mob\" @nearestmob"), actions.toString());
        assertTrue(actions.contains("message-target \"Living\" @nearestlivingentity"), actions.toString());
        assertTrue(actions.contains("message-target \"World\" @playersinworld{limit=2}"), actions.toString());
        assertTrue(actions.contains("message-target \"Skipped\" @playersinworld{stuti=1;limit=1}"), actions.toString());
        assertTrue(actions.contains("message-target \"Server\" @playersonserver{targetself=true}"), actions.toString());
        assertTrue(actions.contains("message-target \"Everyone\" @playersonserver"), actions.toString());
        assertTrue(actions.contains("message-target \"Chain\" @playersinworld{limit=2} > @nearestmob"), actions.toString());
    }

    @Test
    @DisplayName("Mythic LastDamager targeter normalizes its separator aliases")
    void mythicNormalizesLastDamagerTargeter(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/last-damager.yml", """
                skills:
                  last:
                    Skills:
                      - message{m=canonical} @LastDamager
                      - message{m=separator} @Last-Damager
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.last.on-decision");
        assertTrue(actions.contains("message-target \"canonical\" @lastdamager"), actions.toString());
        assertTrue(actions.contains("message-target \"separator\" @lastdamager"), actions.toString());
    }

    @Test
    @DisplayName("Mythic TargetOfTarget targeter normalizes separator aliases")
    void mythicNormalizesTargetOfTargetTargeter(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/target-of-target.yml", """
                skills:
                  target:
                    Skills:
                      - message{m=canonical} @TargetOfTarget
                      - message{m=separator} @Target-Of-Target
                      - message{m=underscore} @Target_Of_Target
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.target.on-decision");
        assertTrue(actions.contains("message-target \"canonical\" @targetoftarget"), actions.toString());
        assertTrue(actions.contains("message-target \"separator\" @targetoftarget"), actions.toString());
        assertTrue(actions.contains("message-target \"underscore\" @targetoftarget"), actions.toString());
    }

    @Test
    @DisplayName("Mythic utility mechanics map to native actions")
    void mythicMapsUtilityMechanics(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/utility.yml", """
                skills:
                  utility:
                    Skills:
                      - setscale{scale=1.2}
                      - playanimation{animation=attack;loop=false;priority=5}
                      - aoedamage{radius=8;amount=4}
                      - removeeffect{effect=SPEED}
                      - removealleffects
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.utility.on-decision");
        assertTrue(actions.contains("set-scale 1.2"));
        assertTrue(actions.contains("play-animation attack false 5"));
        assertTrue(actions.contains("aoe-damage players 8 1 4"));
        assertTrue(actions.contains("remove-effect SPEED"));
        assertTrue(actions.contains("remove-all-effects"));
    }

    @Test
    @DisplayName("独立 Skills 文件不会丢失 skill{s=...} 嵌套引用")
    void standaloneSkillReferencesBecomeCastActions(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/Chain.yml", """
                skills:
                  parent:
                    Skills:
                      - message{m=before}
                      - skill{s=Child}
                      - message{m=after}
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        assertEquals(List.of(
                        "message-target \"before\"",
                        "cast-skill Child",
                        "message-target \"after\""),
                target.getStringList("skills.parent.on-decision"));
    }

    @Test
    void skillReferenceStopsAtMechanicClosingBrace() {
        assertEquals("child", MythicSpec.skillRef("skill{s=child} @Target{limit=1}"));
        assertEquals("child", MythicSpec.skillRef("skill{delay=2;skill=child} ?health{value=>1}"));
        assertNull(MythicSpec.skillRef("skill{s=child"));
        assertNull(MythicSpec.skillRef("skill{note=\"text;s=child\"}"));
        assertNull(MythicSpec.skillRef("skill{s=child;skill=other}"));
        assertNull(MythicSpec.skillRef("skill{s=child;repeatinterval=2;repeat-interval=3}"));
        assertNull(MythicSpec.skillRef("skill{s=child;unknown=true}"));
        assertNull(MythicSpec.skillRef("skill{s=\"child other\"}"));
        assertEquals("child", MythicSpec.skillRef("skill{s=\"child\",delay=2}"));
    }

    @Test
    void skillReferenceExecutionOptionsAreValidatedBeforeMigration(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/invalid-timing.yml", """
                skills:
                  parent:
                    Skills:
                      - 'skill{s=child;delay=-1}'
                      - 'skill{s=child;delay=NaN}'
                      - 'skill{s=child;repeat=1.5}'
                      - 'skill{s=child;repeat=2147483647;repeatinterval=1}'
                      - 'skill{s=child;repeatinterval=1e100s}'
                      - 'skill{s=child;delay=50ms;repeat=1;repeatinterval=0.25s}'
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(new StringReader(
                String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(List.of("skill{s=child;delay=50ms;repeat=1;repeatinterval=0.25s}"),
                target.getStringList("skills.parent.on-decision"));
        assertEquals(5, target.getStringList("skills.parent.unsupported").size());
        assertEquals(5, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid skill execution options")).count());
        Path mob = write(root, "Mobs/invalid-timing.yml", """
                mobs:
                  boss:
                    Skills:
                      - 'skill{s=child;repeat=-1} ~onSpawn'
                """);
        var mobEntries = new MythicMobsImporter().convert(mob);
        var mobTarget = YamlConfiguration.loadConfiguration(new StringReader(
                String.valueOf(plan(mobEntries).orElseThrow().get("content"))));
        assertEquals(List.of("skill{s=child;repeat=-1} ~onSpawn"), mobTarget.getStringList("mob.unsupported.Skills"));
        assertTrue(mobTarget.getStringList("mob.triggers.on-spawn.do").isEmpty());
    }

    @Test
    void unsupportedSkillReferencesArePreservedWithoutScheduling(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/references.yml", """
                skills:
                  parent:
                    Skills:
                      - 'skill{note="text;s=child"}'
                      - 'skill{s=child;skill=other}'
                      - 'skill{s=child;unknown=true}'
                      - 'skill{s=child}'
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(new StringReader(
                String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(List.of("cast-skill child"), target.getStringList("skills.parent.on-decision"));
        assertEquals(List.of("skill{note=\"text;s=child\"}", "skill{s=child;skill=other}",
                "skill{s=child;unknown=true}"), target.getStringList("skills.parent.unsupported"));
        assertEquals(3, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid skill reference or unsupported skill arguments")).count());
    }

    @Test
    void mythicNestedSkillRetainsExecutionMetadata(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/nested-timing.yml", """
                skills:
                  parent:
                    Skills:
                      - skill{s=Child;delay=2;repeat=1} @Target 50%
                      - skill{s=Child} @Target{limit=1}
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(
                plan(new MythicMobsImporter().convert(f)).orElseThrow().get("content"))));
        assertEquals(List.of("skill{s=Child;delay=2;repeat=1} @Target 50%",
                        "skill{s=Child} @Target{limit=1}"),
                target.getStringList("skills.parent.on-decision"));
    }

    @Test
    @DisplayName("Mythic execution metadata survives mechanic conversion")
    void mythicPreservesDelayAndRepeatMetadata(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/timing.yml", """
                skills:
                  timed:
                    Skills:
                      - damage{amount=4;delay=5;repeat=2;repeatinterval=3} @Target
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        String action = target.getStringList("skills.timed.on-decision").get(0);
        assertTrue(action.startsWith("damage-target{delay=5;repeat=2;repeatinterval=3} 4"), action);
        assertTrue(action.contains("@target"), action);
    }

    @Test
    void invalidMechanicTimingIsPreservedForSkillsAndMobEvents(@TempDir Path root) throws Exception {
        Path skills = write(root, "Skills/mechanic-timing.yml", """
                skills:
                  timed:
                    Skills:
                      - 'damage{amount=4;delay=NaN} @Target'
                      - 'heal{amount=2;repeat=-1} @Target'
                      - 'damage{amount=4;repeatinterval=1;repeat-interval=2} @Target'
                      - 'damage{amount=4;delay=2;repeat=1;repeatinterval=3} @Target'
                """);
        var entries = new MythicMobsImporter().convert(skills);
        var target = YamlConfiguration.loadConfiguration(new StringReader(
                String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(3, target.getStringList("skills.timed.unsupported").size());
        assertEquals(1, target.getStringList("skills.timed.on-decision").size());
        assertEquals(3, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid mechanic execution options")).count());

        Path mob = write(root, "Mobs/mechanic-timing.yml", """
                mobs:
                  boss:
                    Skills:
                      - 'damage{amount=4;delay=-1} @Target ~onSpawn'
                """);
        var mobEntries = new MythicMobsImporter().convert(mob);
        var mobTarget = YamlConfiguration.loadConfiguration(new StringReader(
                String.valueOf(plan(mobEntries).orElseThrow().get("content"))));
        assertEquals(List.of("damage{amount=4;delay=-1} @Target ~onSpawn"),
                mobTarget.getStringList("mob.unsupported.Skills"));
        assertTrue(mobTarget.getStringList("mob.triggers.on-spawn.do").isEmpty());
    }

    @Test
    void mythicMessagesPreserveCommasAndDoNotReadQuotedTextAsArguments(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/messages.yml", """
                skills:
                  messages:
                    Skills:
                      - 'message{m=Hello, world} @self'
                      - 'messageall{m="Hello, radius=10; world"} @self'
                      - 'messageall{m=Hello, world,radius=12} @self'
                      - 'message{m="Say \\"hi; radius=10\\" at } now"} @target'
                """);
        var target = YamlConfiguration.loadConfiguration(new java.io.StringReader(
                String.valueOf(plan(new MythicMobsImporter().convert(file)).orElseThrow().get("content"))));
        assertEquals(List.of("message-target \"Hello, world\" @self",
                        "message-all \"Hello, radius=10; world\" 32 @self",
                        "message-all \"Hello, world\" 12 @self",
                        "message-target \"Say \\\"hi; radius=10\\\" at } now\" @target"),
                target.getStringList("skills.messages.on-decision"));
    }

    @Test
    @DisplayName("Mythic utility and summon mechanics map when runtime actions exist")
    void mythicMapsExtendedRuntimeMechanics(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/extended.yml", """
                skills:
                  utility:
                    Skills:
                      - sound{sound=ENTITY_PLAYER_LEVELUP}
                      - particle{particle=FLAME}
                      - actionbar{m=Hello Target}
                      - title{title=Hello;subtitle=World;fadein=5;stay=40;fadeout=5}
                      - randomskill{skills=child}
                      - summon{type=example/minion;amount=2;radius=4}
                      - transform{type=ZOMBIE}
                      - dash{distance=6;mode=forward}
                      - blink{distance=8;mode=target}
                      - knockbackself{power=1.2;vertical=0.5}
                      - healpercent{amount=10}
                      - sethealth{amount=20}
                      - leap{velocity=1.2}
                      - settickspeed{factor=1.1}
                      - pull{radius=6;power=1.5}
                      - modifyhealth{amount=-2}
                      - modifyspeed{amount=0.1}
                      - invisible{state=true}
                      - glowing{state=true}
                      - setinvulnerable{state=true}
                      - command{c=say Hello world}
                      - commandself{c=me waves}
                      - commandtarget{c=msg @s hello}
                      - setvariable{var=skill.counter;type=INTEGER;value=2}
                      - variableadd{var=skill.counter;amount=3}
                      - variablemath{var=skill.counter;op=*;value=2}
                      - variableunset{var=skill.counter}
                      - setvariablelocation{var=skill.mark}
                      - locationunset{var=skill.mark}
                      - signal{signal=phase2;payload=ready;global=true}
                      - suicide
                      - kill
                      - setnameplate{name=&cElite}
                      - broadcast{message=Server alert}
                      - globalcooldown{ticks=40}
                      - setskillcooldown{skill=child;seconds=10}
                      - dot{damage=2;duration=100;interval=20} @Target
                      - ~onSignal:SignalReceiver
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.utility.on-decision");

        assertTrue(actions.contains("sound ENTITY_PLAYER_LEVELUP"));
        assertTrue(actions.contains("particle FLAME"));
        assertTrue(actions.contains("actionbar \"Hello Target\""), actions.toString());
        assertTrue(actions.contains("title \"Hello\" \"World\" 5 40 5"), actions.toString());
        assertTrue(actions.contains("random-skill child"), actions.toString());
        assertTrue(actions.contains("summon example/minion 2 4"));
        assertTrue(actions.contains("transform ZOMBIE"));
        assertTrue(actions.contains("dash 6 forward"));
        assertTrue(actions.contains("blink 8 target"));
        assertTrue(actions.contains("knockback-self 1.2 0.5"));
        assertTrue(actions.contains("heal-percent 10"));
        assertTrue(actions.contains("set-health 20"));
        assertTrue(actions.contains("leap 1.2"));
        assertTrue(actions.contains("set-tick-speed 1.1"));
        assertTrue(actions.contains("pull 6 1.5"));
        assertTrue(actions.contains("modify-health -2"));
        assertTrue(actions.contains("modify-speed 0.1"));
        assertTrue(actions.contains("invisible true"));
        assertTrue(actions.contains("glowing true"));
        assertTrue(actions.contains("set-invulnerable true"));
        assertTrue(actions.contains("command \"say Hello world\""), actions.toString());
        assertTrue(actions.contains("command-self \"me waves\""), actions.toString());
        assertTrue(actions.contains("command-target \"msg @s hello\""), actions.toString());
        assertTrue(actions.contains("set-variable var=skill.counter type=INTEGER value=\"2\""), actions.toString());
        assertTrue(actions.contains("var-add var=skill.counter value=3"), actions.toString());
        assertTrue(actions.contains("var-math var=skill.counter op=* value=2"), actions.toString());
        assertTrue(actions.contains("variable-unset var=skill.counter"), actions.toString());
        assertTrue(actions.contains("variable-location-set var=skill.mark"), actions.toString());
        assertTrue(actions.contains("location-unset var=skill.mark"), actions.toString());
        assertTrue(actions.contains("signal \"phase2\" \"ready\" global"), actions.toString());
        assertTrue(actions.contains("suicide"), actions.toString());
        assertTrue(actions.contains("kill-targets"), actions.toString());
        assertTrue(actions.contains("set-nameplate &cElite"), actions.toString());
        assertTrue(actions.contains("broadcast \"Server alert\""), actions.toString());
        assertTrue(actions.contains("set-global-cooldown __mythic_gcd 40t"), actions.toString());
        assertTrue(actions.contains("set-skill-cooldown child 10"), actions.toString());
        assertTrue(actions.contains("dot damage=2 duration=100 interval=20 @target"), actions.toString());
        assertEquals(List.of("~onSignal:SignalReceiver"), target.getStringList("skills.utility.unsupported"));
    }

    @Test
    @DisplayName("Mythic threat, melee, and message mechanics map to native actions")
    void mythicMapsThreatAndMessageMechanics(@TempDir Path root) throws Exception {
        Path f = write(root, "Skills/utility.yml", """
                skills:
                  utility:
                    Skills:
                      - damagemelee{amount=4;radius=3}
                      - addthreat{amount=5}
                      - clearthreat{target=true}
                      - threatfocus
                      - messageall{m=Hello all;radius=16}
                      - messageself{m=Hello self}
                      - randommessage{messages=One|Two}
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(f)).get().get("content"))));
        List<String> actions = target.getStringList("skills.utility.on-decision");

        assertTrue(actions.contains("damage-melee 4 3"), actions.toString());
        assertTrue(actions.contains("threat-add 5"), actions.toString());
        assertTrue(actions.contains("threat-clear true"), actions.toString());
        assertTrue(actions.contains("threat-focus"), actions.toString());
        assertTrue(actions.contains("message-all \"Hello all\" 16"), actions.toString());
        assertTrue(actions.contains("message-self \"Hello self\""), actions.toString());
        assertTrue(actions.contains("random-message \"One|Two\""), actions.toString());
        assertFalse(target.contains("skills.utility.unsupported"), actions.toString());
    }

    @Test
    @DisplayName("Mythic remove mechanic removes the caster through an explicit runtime action")
    void mythicMapsRemoveMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/remove.yml", """
                skills:
                  despawn:
                    Skills:
                      - remove
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("remove-self"), target.getStringList("skills.despawn.on-decision"));
        assertFalse(target.contains("skills.despawn.unsupported"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "remove-self".equals(entry.get("value"))));
    }

    @Test
    @DisplayName("Mythic equip mechanic maps item, slot, and amount")
    void mythicMapsEquipMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/equip.yml", """
                skills:
                  arm:
                    Skills:
                      - equip{item=DIAMOND_SWORD;slot=offhand;amount=2}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("equip DIAMOND_SWORD offhand 2"),
                target.getStringList("skills.arm.on-decision"));
        assertFalse(target.contains("skills.arm.unsupported"));
    }

    @Test
    @DisplayName("Mythic setname mechanic maps name and visibility")
    void mythicMapsSetNameMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/name.yml", """
                skills:
                  title:
                    Skills:
                      - setname{name=&cBoss;visible=false}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("set-name &cBoss false"),
                target.getStringList("skills.title.on-decision"));
        assertFalse(target.contains("skills.title.unsupported"));
    }

    @Test
    void mythicLightningEffectAliasRemainsVisualOnly(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/lightning.yml", """
                skills:
                  storm:
                    Skills:
                      - strike-lightning-effect @Target
                      - strike-lightning
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(new StringReader(
                String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(List.of("lightning true @target", "lightning false"),
                target.getStringList("skills.storm.on-decision"));
    }

    @Test
    @DisplayName("Mythic setitem mechanic maps item, slot, and amount")
    void mythicMapsSetItemMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/item.yml", """
                skills:
                  replace:
                    Skills:
                      - setitem{item=SHIELD;slot=main-hand;amount=1}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("set-item SHIELD main-hand 1"),
                target.getStringList("skills.replace.on-decision"));
        assertFalse(target.contains("skills.replace.unsupported"));
    }

    @Test
    @DisplayName("Mythic dropitem mechanic maps item and amount")
    void mythicMapsDropItemMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/drop.yml", """
                skills:
                  reward:
                    Skills:
                      - dropitem{item=DIAMOND;amount=3}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("drop-item DIAMOND 3"),
                target.getStringList("skills.reward.on-decision"));
        assertFalse(target.contains("skills.reward.unsupported"));
    }

    @Test
    void mythicDropItemOwnerMapsAndInvalidOwnersArePreserved(@TempDir Path root) throws Exception {
        String owner = "123e4567-e89b-12d3-a456-426614174000";
        Path file = write(root, "Skills/drop-owner.yml", """
                skills:
                  reward:
                    Skills:
                      - dropitem{item=DIAMOND;owner=%s}
                      - dropitem{items=DIAMOND 2,STONE 3;n=false;owner=<target.uuid>}
                      - dropitem{item=STONE;owner=not-a-uuid}
                      - dropitem{item=STONE;owner=}
                      - dropitem{item=STONE;owner=%s;OWNER=%s}
                """.formatted(owner, owner, owner));
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("drop-item DIAMOND 1 true " + owner,
                        "drop-items false DIAMOND 2 STONE 3 <target.uuid>"),
                target.getStringList("skills.reward.on-decision"));
        assertEquals(3, target.getStringList("skills.reward.unsupported").size());
        assertEquals(3, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid dropitem owner")).count());
    }

    @Test
    void invalidDropItemCountsArePreservedForSkillsAndMobEvents(@TempDir Path root) throws Exception {
        Path skill = write(root, "Skills/drop-invalid.yml", """
                skills:
                  reward:
                    Skills:
                      - dropitem{item=DIAMOND;amount=0}
                      - dropitem{item=DIAMOND;a=-2}
                      - dropitem{item=DIAMOND;amount=1.5}
                      - dropitem{item=DIAMOND;amount=2147483648}
                      - dropitem{item=DIAMOND;amount=NaN}
                      - dropitem{item=DIAMOND;amount=}
                      - dropitem{item=DIAMOND;amount=3;a=2}
                      - dropitem{item=DIAMOND;amount=3;AMOUNT=4}
                      - dropitem{item=DIAMOND;amount=<skill.var.count>}
                """);
        var entries = new MythicMobsImporter().convert(skill);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(8, target.getStringList("skills.reward.unsupported").size());
        assertEquals(List.of("drop-item DIAMOND <skill.var.count>"),
                target.getStringList("skills.reward.on-decision"));
        assertEquals(8, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid dropitem amount")).count());

        Path mob = write(root, "Mobs/drop-invalid.yml", """
                mobs:
                  Boss:
                    Skills:
                      - dropitem{item=DIAMOND;amount=0} @self ~onSpawn
                      - dropitem{item=DIAMOND} @self ~onSpawn
                """);
        var mobEntries = new MythicMobsImporter().convert(mob);
        var mobTarget = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(mobEntries).orElseThrow().get("content"))));
        assertEquals(List.of("dropitem{item=DIAMOND;amount=0} @self ~onSpawn"),
                mobTarget.getStringList("mob.unsupported.Skills"));
        assertEquals(List.of("drop-item DIAMOND 1 @self"), mobTarget.getStringList("mob.triggers.on-spawn.do"));
        assertTrue(mobEntries.stream().anyMatch(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid dropitem amount")));
    }

    @Test
    void invalidDropItemIdentifiersArePreserved(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/drop-items-invalid.yml", """
                skills:
                  reward:
                    Skills:
                      - dropitem{amount=2} @self
                      - dropitem{item=;amount=2}
                      - dropitem{item=STONE;material=DIRT}
                      - dropitem{item=STONE;ITEM=DIRT}
                      - dropitem{material=STONE;amount=2}
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(4, target.getStringList("skills.reward.unsupported").size());
        assertEquals(List.of("drop-item STONE 2"), target.getStringList("skills.reward.on-decision"));
        assertEquals(4, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid dropitem item")).count());
    }

    @Test
    void unsupportedDropItemArgumentsArePreservedForSkillsAndMobEvents(@TempDir Path root) throws Exception {
        Path skill = write(root, "Skills/drop-options.yml", """
                skills:
                  reward:
                    Skills:
                      - dropitem{item=STONE;unknown=true}
                      - dropitem{item=STONE;unmapped}
                      - dropitem{item=STONE;amount=2;delay=5;repeat=1;repeatinterval=2}
                """);
        var entries = new MythicMobsImporter().convert(skill);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(2, target.getStringList("skills.reward.unsupported").size());
        assertEquals(List.of("drop-item{delay=5;repeat=1;repeatinterval=2} STONE 2"),
                target.getStringList("skills.reward.on-decision"));
        assertEquals(2, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Unsupported dropitem argument")).count());

        Path mob = write(root, "Mobs/drop-options.yml", """
                mobs:
                  Boss:
                    Skills:
                      - dropitem{item=STONE;unknown=true} @self ~onSpawn
                """);
        var mobEntries = new MythicMobsImporter().convert(mob);
        var mobTarget = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(mobEntries).orElseThrow().get("content"))));
        assertEquals(List.of("dropitem{item=STONE;unknown=true} @self ~onSpawn"),
                mobTarget.getStringList("mob.unsupported.Skills"));
        assertTrue(mobEntries.stream().anyMatch(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Unsupported dropitem argument")));
    }

    @Test
    void invalidDropItemExecutionOptionsArePreserved(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/drop-timing.yml", """
                skills:
                  reward:
                    Skills:
                      - dropitem{item=STONE;delay=}
                      - dropitem{item=STONE;delay=-1}
                      - dropitem{item=STONE;repeat=1.5}
                      - dropitem{item=STONE;repeat=2147483647;repeatinterval=1}
                      - dropitem{item=STONE;repeatinterval=2;repeat-interval=3}
                      - dropitem{item=STONE;delay=50ms;repeat=2;repeatinterval=0.5s}
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(5, target.getStringList("skills.reward.unsupported").size());
        assertEquals(List.of("drop-item{delay=50ms;repeat=2;repeatinterval=0.5s} STONE 1"),
                target.getStringList("skills.reward.on-decision"));
        assertEquals(5, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid dropitem execution options")).count());
    }

    @Test
    @DisplayName("Mythic potion mechanic preserves effect flags")
    void mythicMapsPotionFlags(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/potion.yml", """
                skills:
                  aura:
                    Skills:
                      - potion{effect=SPEED;duration=100;amplifier=2;ambient=true;particles=false;icon=false}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("effect-targets SPEED 100 2 true false false"),
                target.getStringList("skills.aura.on-decision"));
        assertFalse(target.contains("skills.aura.unsupported"));
    }

    @Test
    @DisplayName("Mythic Aura basic state options map while components and invalid values stay visible")
    void mythicMapsBasicAuraStateAndPreservesUnsupportedFeatures(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/aura.yml", """
                skills:
                  fury:
                    Skills:
                      - aura{auraName=FURY;maxStacks=3}
                  overwrite:
                    Skills:
                      - buff{b=\"Burning Fury\";d=40;overwriteAll=true}
                  options:
                    Skills:
                      - aura{type=HARMFUL;refreshDuration=false}
                  components:
                    Skills:
                      - aura{auraName=WARD;duration=100;components=[onattack]}
                  invalid_duration:
                    Skills:
                      - aura{auraName=SHORT;duration=0}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("mythic-aura{auraName=\"FURY\";maxStacks=\"3\"}"),
                target.getStringList("skills.fury.on-decision"));
        assertEquals(List.of("mythic-aura{auraName=\"Burning Fury\";duration=\"40\";overwriteAll=\"true\"}"),
                target.getStringList("skills.overwrite.on-decision"));
        assertEquals(List.of("mythic-aura{auraType=\"HARMFUL\";refreshDuration=\"false\"}"),
                target.getStringList("skills.options.on-decision"));
        assertTrue(target.getStringList("skills.components.unsupported")
                .contains("aura{auraName=WARD;duration=100;components=[onattack]}"));
        assertTrue(target.getStringList("skills.invalid_duration.unsupported")
                .contains("aura{auraName=SHORT;duration=0}"));
        assertTrue(entries.stream().anyMatch(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Aura components")));
        assertTrue(entries.stream().anyMatch(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("source-key")).contains("invalid_duration")));
    }

    @Test
    @DisplayName("Mythic AuraRemove aliases map while invalid arguments stay visible")
    void mythicMapsAuraRemoveAliasesAndPreservesInvalidArguments(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/aura-remove.yml", """
                skills:
                  defaults:
                    Skills:
                      - AuraRemove
                  partial:
                    Skills:
                      - removeaura{aura=FURY;stacks=2}
                  named:
                    Skills:
                      - removebuff{buff=WARD}
                  wildcard:
                    Skills:
                      - removedebuff{aura=ANY}
                  invalid_count:
                    Skills:
                      - removeaura{aura=FURY;stacks=0}
                  invalid_argument:
                    Skills:
                      - removeaura{aura=FURY;unknown=true}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("mythic-aura-remove"),
                target.getStringList("skills.defaults.on-decision"));
        assertEquals(List.of("mythic-aura-remove{auraName=\"FURY\";stacks=\"2\"}"),
                target.getStringList("skills.partial.on-decision"));
        assertEquals(List.of("mythic-aura-remove{auraName=\"WARD\"}"),
                target.getStringList("skills.named.on-decision"));
        assertEquals(List.of("mythic-aura-remove{auraName=\"ANY\"}"),
                target.getStringList("skills.wildcard.on-decision"));
        assertEquals(List.of("removeaura{aura=FURY;stacks=0}"),
                target.getStringList("skills.invalid_count.unsupported"));
        assertEquals(List.of("removeaura{aura=FURY;unknown=true}"),
                target.getStringList("skills.invalid_argument.unsupported"));
        assertEquals(2, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("source-key")).contains("invalid_")).count());
    }

    @Test
    @DisplayName("Mythic mount mechanic maps to the native mount action")
    void mythicMapsMountMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/mount.yml", """
                skills:
                  ride:
                    Skills:
                      - mount
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("mount"), target.getStringList("skills.ride.on-decision"));
        assertFalse(target.contains("skills.ride.unsupported"));
    }

    @Test
    @DisplayName("Mythic dismount mechanic maps to the native dismount action")
    void mythicMapsDismountMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/dismount.yml", """
                skills:
                  leave:
                    Skills:
                      - dismount
                      - unmount
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("dismount", "dismount"),
                target.getStringList("skills.leave.on-decision"));
        assertFalse(target.contains("skills.leave.unsupported"));
    }

    @Test
    @DisplayName("Mythic owner mechanics map to persistent owner relation actions")
    void mythicMapsOwnerMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/owner.yml", """
                skills:
                  ownership:
                    Skills:
                      - setowner
                      - removeowner
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("set-owner", "remove-owner"),
                target.getStringList("skills.ownership.on-decision"));
        assertFalse(target.contains("skills.ownership.unsupported"));
    }

    @Test
    @DisplayName("Mythic parent mechanics map to persistent parent relation actions")
    void mythicMapsParentMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/parent.yml", """
                skills:
                  family:
                    Skills:
                      - setparent
                      - removeparent
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("set-parent", "remove-parent"),
                target.getStringList("skills.family.on-decision"));
        assertFalse(target.contains("skills.family.unsupported"));
    }

    @Test
    @DisplayName("Mythic scoreboard tag mechanics map to caster and target actions")
    void mythicMapsScoreboardTagMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/tags.yml", """
                skills:
                  flags:
                    Skills:
                      - addtag{tag=Marked}
                      - removetag Marked
                      - add-scoreboard-tag{tag=Targeted} @Targets
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("add-tag Marked", "remove-tag Marked", "add-tag Targeted @targets"),
                target.getStringList("skills.flags.on-decision"), target.saveToString());
        assertFalse(target.contains("skills.flags.unsupported"));
    }

    @Test
    @DisplayName("Mythic execution audience mechanics preserve named options and nested targeters")
    void mythicMapsExecutionAudienceMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/audience.yml", """
                skills:
                  audience:
                    Skills:
                      - AddToAudience{a=target;mobs=@EntitiesInRadius{r=30};ignoreSkillAudience=true}
                      - RemoveFromAudience{audience=target}
                      - UnsetAudience
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of(
                        "add-to-audience{a=target;mobs=@EntitiesInRadius{r=30};ignoreSkillAudience=true}",
                        "remove-from-audience{audience=target}",
                        "unset-audience"),
                target.getStringList("skills.audience.on-decision"), target.saveToString());
        assertFalse(target.contains("skills.audience.unsupported"));
    }

    @Test
    @DisplayName("Mythic Mob and skill ExecutionAudience configuration imports")
    void mythicMapsExecutionAudienceConfiguration(@TempDir Path root) throws Exception {
        Path mobFile = write(root, "Mobs/audience.yml", """
                Guard:
                  Type: ZOMBIE
                  Options:
                    ExecutionAudience: '@PlayersInRadius{r=12}'
                """);
        List<Map<String, Object>> mobEntries = new MythicMobsImporter().convert(mobFile);
        YamlConfiguration mob = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(
                mobEntries.stream().filter(entry -> "mobs/mythic_guard.yml".equals(entry.get("target-file")))
                        .findFirst().orElseThrow().get("content"))));
        assertEquals("@PlayersInRadius{r=12}", mob.getString("mob.execution-audience"));

        Path skillFile = write(root, "Skills/audience-config.yml", """
                skills:
                  private:
                    ExecutionAudience: NONE
                    Skills:
                      - message{m=private}
                """);
        List<Map<String, Object>> skillEntries = new MythicMobsImporter().convert(skillFile);
        YamlConfiguration skills = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(
                plan(skillEntries).orElseThrow().get("content"))));
        assertEquals("NONE", skills.getString("skills.private.execution-audience"));
    }

    @Test
    @DisplayName("Mythic score mechanics map to the existing instance score actions")
    void mythicMapsScoreMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/scores.yml", """
                skills:
                  points:
                    Skills:
                      - setscore{objective=points;value=2}
                      - addscore points 3
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("score-set points 2", "score-add points 3"),
                target.getStringList("skills.points.on-decision"));
        assertFalse(target.contains("skills.points.unsupported"));
    }

    @Test
    @DisplayName("Mythic shoot and set-intent aliases map to runtime factories")
    void mythicMapsShootAndIntentMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/projectile.yml", """
                skills:
                  combat:
                    Skills:
                      - shoot{type=ARROW;velocity=1.6;damage=3}
                      - setintent{intent=ATTACK}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("shoot-projectile ARROW 1.6 3", "set-intent ATTACK"),
                target.getStringList("skills.combat.on-decision"));
        assertFalse(target.contains("skills.combat.unsupported"));
    }

    @Test
    @DisplayName("Mythic cancel-event mechanic maps to the cancellable-event action")
    void mythicMapsCancelEventMechanic(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/cancel.yml", """
                skills:
                  prevent:
                    Skills:
                      - cancelEvent
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("cancel-event"),
                target.getStringList("skills.prevent.on-decision"));
        assertFalse(target.contains("skills.prevent.unsupported"));
    }

    @Test
    @DisplayName("Mythic teleport variants preserve self and caster destinations")
    void mythicMapsTeleportVariants(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/teleport.yml", """
                skills:
                  move:
                    Skills:
                      - teleportself{x=1;y=2;z=3}
                      - teleportcaster{target=@origin}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("teleport-self 1 2 3", "teleport-caster @origin"),
                target.getStringList("skills.move.on-decision"));
        assertFalse(target.contains("skills.move.unsupported"));
    }

    @Test
    void mythicDropItemNaturallyMapsAndInvalidValuesArePreserved(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/drop-natural.yml", """
                skills:
                  reward:
                    Skills:
                      - dropitem{item=STONE;naturally=false} @self
                      - dropitem{items=DIAMOND;natural=true} @self
                      - dropitem{i=STONE;n=FALSE} @self
                      - dropitem{item=STONE;n=maybe} @self
                      - dropitem{item=STONE;n=} @self
                      - dropitem{item=STONE;n=false;natural=true} @self
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(List.of("drop-item STONE 1 false @self", "drop-item DIAMOND 1 true @self",
                "drop-item STONE 1 false @self"), target.getStringList("skills.reward.on-decision"));
        assertEquals(3, target.getStringList("skills.reward.unsupported").size());
        assertEquals(3, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid dropitem naturally flag")).count());
    }

    @Test
    void mythicDropItemAliasesMapSingleItemsAndPreserveLists(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/drop-aliases.yml", """
                skills:
                  reward:
                    Skills:
                      - dropitem{items=DIAMOND;amount=2} @self
                      - dropitem{i=STONE} @self
                      - dropitem{items=STONE,DIAMOND} @self
                      - dropitem{items=STONE 2,DIAMOND 3;n=false} @self
                      - dropitem{items=STONE 2,DIAMOND 3,n=false} @self
                      - dropitem{items=STONE,DIAMOND,n=maybe} @self
                      - dropitem{items=STONE 2,DIAMOND 0} @self
                      - dropitem{items=STONE,,DIAMOND} @self
                      - dropitem{items=STONE,DIAMOND;amount=2} @self
                      - dropitem{items=STONE 3} @self
                      - dropitem{i=DIAMOND <skill.var.count>;n=false} @self
                      - dropitem{items=STONE 0} @self
                      - dropitem{items=STONE 3;amount=3} @self
                      - dropitem{items=STONE 2 extra} @self
                      - dropitem{item=STONE;i=DIAMOND} @self
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(List.of("drop-item DIAMOND 2 @self", "drop-item STONE 1 @self",
                        "drop-items true STONE 1 DIAMOND 1 @self", "drop-items false STONE 2 DIAMOND 3 @self",
                        "drop-items false STONE 2 DIAMOND 3 @self", "drop-item STONE 3 @self",
                        "drop-item DIAMOND <skill.var.count> false @self"),
                target.getStringList("skills.reward.on-decision"));
        assertEquals(List.of("dropitem{items=STONE,DIAMOND,n=maybe} @self", "dropitem{items=STONE 2,DIAMOND 0} @self", "dropitem{items=STONE,,DIAMOND} @self",
                        "dropitem{items=STONE,DIAMOND;amount=2} @self", "dropitem{items=STONE 0} @self",
                        "dropitem{items=STONE 3;amount=3} @self", "dropitem{items=STONE 2 extra} @self",
                        "dropitem{item=STONE;i=DIAMOND} @self"),
                target.getStringList("skills.reward.unsupported"));
        assertEquals(7, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid dropitem item")).count());
        assertTrue(entries.stream().anyMatch(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid dropitem naturally flag")));
        Path mob = write(root, "Mobs/drop-inline.yml", """
                mobs:
                  Boss:
                    Skills:
                      - dropitem{items=DIAMOND 4;n=false} @self ~onSpawn
                      - dropitem{items=STONE 0} @self ~onSpawn
                """);
        var mobEntries = new MythicMobsImporter().convert(mob);
        var mobTarget = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(mobEntries).orElseThrow().get("content"))));
        assertEquals(List.of("drop-item DIAMOND 4 false @self"),
                mobTarget.getStringList("mob.triggers.on-spawn.do"));
        assertEquals(List.of("dropitem{items=STONE 0} @self ~onSpawn"),
                mobTarget.getStringList("mob.unsupported.Skills"));
    }

    @Test
    void mythicTeleportMapsStrictOptionsAndPreservesInvalidLines(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/teleport-unsupported.yml", """
                skills:
                  move:
                    Skills:
                      - teleport @target
                      - tp{spreadh=5;spreadv=2;unsafe=true;yaw=90;pitch=-15} @target
                      - tp{spreadh=-1} @target
                      - tp{unsafe=maybe} @target
                      - tp{unknown=1} @target
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(List.of("teleport-mythic 0 0 false preserve preserve @target",
                        "teleport-mythic 5 2 true 90 -15 @target"),
                target.getStringList("skills.move.on-decision"));
        assertEquals(List.of("tp{spreadh=-1} @target", "tp{unsafe=maybe} @target",
                        "tp{unknown=1} @target"),
                target.getStringList("skills.move.unsupported"));
        assertEquals(3, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid teleport arguments")).count());
    }

    @Test
    void mythicBlockMechanicPreservesLocationTargeter(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/location.yml", """
                skills:
                  place:
                    Skills:
                      - setblock{material=STONE} @location{x=4;y=5;z=6}
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(List.of("set-block STONE @location{x=4;y=5;z=6}"),
                target.getStringList("skills.place.on-decision"));
        assertTrue(target.getStringList("skills.place.unsupported").isEmpty());
    }

    @Test
    void officialLocationTargetersAreValidatedAndPreservedDuringMigration(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/location-targeters.yml", """
                skills:
                  place:
                    Skills:
                      - setblock{material=STONE} @Forward{f=3;uel=true}
                      - setblock{material=STONE} @SelfEyeLocation
                      - setblock{material=STONE} @eyeDirection
                      - setblock{material=STONE} @casterEyeLocation
                      - setblock{material=STONE} @bossEyeLocation
                      - setblock{material=STONE} @mobEyeLocation
                      - setblock{material=STONE} @targetLoc
                      - setblock{material=STONE} @TargetPredictedLoc{ticks=10;max=4}
                      - setblock{material=STONE} @ProjectileForward{amount=2}
                      - setblock{material=STONE} @SpawnLocation
                      - setblock{material=STONE} @CasterSpawn
                      - setblock{material=DIRT} @Forward{f=3;unknown=true}
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of(
                "set-block STONE @forward{f=3;uel=true}",
                "set-block STONE @selfeyelocation",
                "set-block STONE @eyedirection",
                "set-block STONE @castereyelocation",
                "set-block STONE @bosseyelocation",
                "set-block STONE @mobeyelocation",
                "set-block STONE @targetloc",
                "set-block STONE @targetpredictedloc{ticks=10;max=4}",
                "set-block STONE @projectileforward{amount=2}",
                "set-block STONE @spawnlocation",
                "set-block STONE @casterspawn"), target.getStringList("skills.place.on-decision"));
        assertEquals(List.of("setblock{material=DIRT} @Forward{f=3;unknown=true}"),
                target.getStringList("skills.place.unsupported"));
        assertTrue(entries.stream().anyMatch(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid Location targeter")));
    }

    @Test
    void mythicTrackLocationMechanicAliasesImportWithValidatedTargeters(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/tracked-location.yml", """
                skills:
                  track:
                    Skills:
                      - tracklocation @targetlocation
                      - settrackedlocation @TrackedLocation
                      - stl @selflocation
                      - tracklocation{unknown=true} @targetlocation
                      - tracklocation unexpected @targetlocation
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals(List.of("track-location @targetlocation", "track-location @trackedlocation",
                "track-location @selflocation"), target.getStringList("skills.track.on-decision"));
        assertEquals(List.of("tracklocation{unknown=true} @targetlocation",
                        "tracklocation unexpected @targetlocation"),
                target.getStringList("skills.track.unsupported"));
    }

    @Test
    void invalidVariableLocationOnMobEventIsPreserved(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/location.yml", """
                mobs:
                  Boss:
                    Skills:
                      - setblock{material=STONE} @VariableLocation{var=skill.mark} ~onSpawn
                      - setblock{material=DIRT} @VariableLocation{var=skill.mark;scope=bad} ~onSpawn
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(1, target.getStringList("mob.triggers.on-spawn.do").size());
        assertEquals(List.of("setblock{material=DIRT} @VariableLocation{var=skill.mark;scope=bad} ~onSpawn"),
                target.getStringList("mob.unsupported.Skills"));
        assertTrue(entries.stream().anyMatch(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid VariableLocation")));
    }

    @Test
    void invalidExplicitLocationsArePreservedWithWarnings(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/invalid-location.yml", """
                skills:
                  place:
                    Skills:
                      - setblock{material=STONE} @loc{l=1,2,3,0,0}
                      - setblock{material=DIRT} @location{x=NaN;y=2;z=3}
                      - setblock{material=DIRT} @l{x=1;x=2}
                      - setblock{material=DIRT} @loc{l=1,2,3,0,0;y=1}
                      - setblock{material=DIRT} @location{world={other}}
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(1, target.getStringList("skills.place.on-decision").size());
        assertEquals(4, target.getStringList("skills.place.unsupported").size());
        assertEquals(4, entries.stream().filter(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid Location targeter")).count());
    }

    @Test
    void invalidExplicitLocationOnMobEventIsPreserved(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/invalid-location.yml", """
                mobs:
                  Boss:
                    Skills:
                      - setblock{material=STONE} @loc{x=1;y=2;z=3} ~onSpawn
                      - setblock{material=DIRT} @location{x=Infinity} ~onSpawn
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals(1, target.getStringList("mob.triggers.on-spawn.do").size());
        assertEquals(List.of("setblock{material=DIRT} @location{x=Infinity} ~onSpawn"),
                target.getStringList("mob.unsupported.Skills"));
        assertTrue(entries.stream().anyMatch(entry -> "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("Invalid Location targeter")));
    }

    @Test
    @DisplayName("Mythic mob drop experience maps beside the drop table reference")
    void mythicMapsDropExperience(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/experience.yml", """
                mobs:
                  Boss:
                    DropTable-1: boss_loot
                    ExperienceMin: 4
                    ExperienceMax: 8
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals("boss_loot", target.getString("mob.drops.table"));
        assertEquals(4, target.getInt("mob.drops.experience-min"));
        assertEquals(8, target.getInt("mob.drops.experience-max"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "mob.drops.experience-min".equals(entry.get("target-key"))));
    }

    @Test
    void conflictingMobDropSourcesArePreservedWithoutProjection(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/conflicting-drops.yml", """
                mobs:
                  Boss:
                    DropTable-1: first_table
                    DropTable1: second_table
                    Drops:
                      - DIAMOND 1 1
                      - MMOItems:SWORD 1 1
                """);
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertFalse(target.contains("mob.drops.table"));
        var preserved = target.getConfigurationSection("mob.unsupported.Drops.conflicting-sources");
        assertNotNull(preserved);
        assertEquals("first_table", preserved.getString("DropTable-1"));
        assertEquals("second_table", preserved.getString("DropTable1"));
        assertEquals(List.of("DIAMOND 1 1", "MMOItems:SWORD 1 1"), preserved.getStringList("Drops"));
        assertTrue(entries.stream().noneMatch(e -> "loot.yml".equals(e.get("target-file"))));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "mob.unsupported.Drops.conflicting-sources".equals(e.get("target-key"))
                && "WARN".equals(e.get("severity"))));
        assertTrue(entries.stream().anyMatch(e -> "external-plugin".equals(e.get("dependency"))
                && String.valueOf(e.get("value")).contains("MMOItems:SWORD")));
    }

    @Test
    void malformedInlineDropChancesArePreservedAndNeverProjected(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/chances.yml", """
                mobs:
                  Boss:
                    Drops:
                      - DIAMOND 1 1%0
                      - {item: EMERALD, chance: '25%%'}
                      - GOLD_INGOT 1 25%
                  Broken:
                    Drops:
                      - DIAMOND 1 1%0
                """);
        var entries = new MythicMobsImporter().convert(file);
        var bossPlan = entries.stream().filter(e -> "mobs/mythic_boss.yml".equals(e.get("target-file"))
                && e.containsKey("content")).findFirst().orElseThrow();
        var boss = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(bossPlan.get("content"))));
        assertEquals("boss_drops", boss.getString("mob.drops.table"));
        String preserved = String.valueOf(boss.get("mob.unsupported.Drops.entries"));
        assertTrue(preserved.contains("DIAMOND 1 1%0"), preserved);
        assertTrue(preserved.contains("25%%"), preserved);
        assertFalse(preserved.contains("GOLD_INGOT"), preserved);
        var lootPlan = entries.stream().filter(e -> "loot.yml".equals(e.get("target-file"))
                && e.containsKey("content")).findFirst().orElseThrow();
        var loot = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(lootPlan.get("content"))));
        var projected = loot.getConfigurationSection("tables.boss_drops.entries");
        assertEquals(1, projected.getKeys(false).size());
        assertEquals("GOLD_INGOT", projected.getString("0.item"));
        assertEquals(0.25, projected.getDouble("0.chance"), 1e-9);
        var brokenPlan = entries.stream().filter(e -> "mobs/mythic_broken.yml".equals(e.get("target-file"))
                && e.containsKey("content")).findFirst().orElseThrow();
        var broken = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(brokenPlan.get("content"))));
        assertFalse(broken.contains("mob.drops.table"));
        assertEquals(List.of("DIAMOND 1 1%0"), broken.getStringList("mob.unsupported.Drops.entries"));
        for (String mob : List.of("Boss", "Broken")) {
            assertTrue(entries.stream().anyMatch(e -> (mob + ".Drops").equals(e.get("source-key"))
                    && "WARN".equals(e.get("severity")) && "unsupported".equals(e.get("status"))), mob);
        }
    }

    @Test
    @DisplayName("third-party drop types are preserved and reported as unsupported")
    void mythicPreservesExternalDropTypes(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/external-drops.yml", """
                mobs:
                  Boss:
                    Drops:
                      - DIAMOND 1 0.5
                      - MMOItems:SWORD_OF_DAWN 1 1.0
                      - itemadder:ancient_sword 1 1.0
                      - craftengine:rare_gem 1 1.0
                      - MythicCrucible:relic 1 1.0
                      - minecraft:emerald 2 1.0
                      - type: ItemAdder:ancient_ingot
                        amount: 2
                        chance: 0.25
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        Map<String, Object> mobPlan = entries.stream()
                .filter(e -> String.valueOf(e.get("target-file")).startsWith("mobs/"))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(mobPlan.get("content"))));
        String preservedDrops = String.valueOf(target.get("mob.unsupported.Drops.entries"));
        assertTrue(preservedDrops.contains("MMOItems:SWORD_OF_DAWN 1 1.0"), preservedDrops);
        assertTrue(preservedDrops.contains("itemadder:ancient_sword 1 1.0"));
        assertTrue(preservedDrops.contains("craftengine:rare_gem 1 1.0"));
        assertTrue(preservedDrops.contains("MythicCrucible:relic 1 1.0"));
        assertTrue(preservedDrops.contains("ItemAdder:ancient_ingot"));
        assertFalse(preservedDrops.isBlank());
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && "external-plugin".equals(entry.get("dependency"))
                && "Boss.Drops".equals(entry.get("source-key"))
                && "mob.unsupported.Drops.external-types".equals(entry.get("target-key"))
                && String.valueOf(entry.get("value")).contains("MMOItems:SWORD_OF_DAWN")));
    }

    @Test
    @DisplayName("safe structured drops generate a merged runtime loot table")
    void mythicProjectsSafeStructuredDrops(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/simple-drops.yml", """
                mobs:
                  Boss:
                    Drops:
                      - DIAMOND 2-4 25%
                      - minecraft:EMERALD 1 1.0
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(f);
        Map<String, Object> mob = entries.stream()
                .filter(e -> String.valueOf(e.get("target-file")).startsWith("mobs/"))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(mob.get("content"))));
        assertEquals("boss_drops", target.getString("mob.drops.table"));
        Map<String, Object> loot = entries.stream()
                .filter(e -> "loot.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        String content = String.valueOf(loot.get("content"));
        assertTrue(content.contains("amount-min: 2"));
        assertTrue(content.contains("chance: 0.25"));
    }

    @Test
    @DisplayName("unmapped structured drop conditions remain unsupported instead of becoming unconditional")
    void mythicPreservesStructuredDropsWithUnmappedConditions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/conditional-drops.yml", """
                mobs:
                  Boss:
                    Drops:
                      - item: DIAMOND
                        chance: 1.0
                        conditions:
                          - world-is arena
                          - future-condition{value=x}
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        Map<String, Object> mob = entries.stream()
                .filter(e -> String.valueOf(e.get("target-file")).startsWith("mobs/"))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(mob.get("content"))));
        String preserved = String.valueOf(target.get("mob.unsupported.Drops.entries"));

        assertTrue(preserved.contains("future-condition"), preserved);
        assertTrue(preserved.contains("world-is arena"), preserved);
        assertFalse(entries.stream().filter(e -> "loot.yml".equals(e.get("target-file")))
                .map(e -> String.valueOf(e.get("content")))
                .anyMatch(content -> content.contains("item: DIAMOND")));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "Boss.Drops".equals(e.get("source-key"))
                && "WARN".equals(e.get("severity"))));
    }

    @Test
    void mythicPreservesUnsupportedNestedTableAmount(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/nested-amount.yml", """
                mobs:
                  Boss:
                    Drops:
                      - table: nested
                        amount: 2
                      - item: DIAMOND
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        Map<String, Object> mob = entries.stream()
                .filter(e -> String.valueOf(e.get("target-file")).startsWith("mobs/"))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(mob.get("content"))));
        assertTrue(String.valueOf(target.get("mob.unsupported.Drops.entries")).contains("nested"));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "Boss.Drops".equals(e.get("source-key")) && "WARN".equals(e.get("severity"))));
        assertTrue(entries.stream().filter(e -> "loot.yml".equals(e.get("target-file")))
                .map(e -> String.valueOf(e.get("content")))
                .anyMatch(content -> content.contains("DIAMOND") && !content.contains("nested")));
    }


    @Test
    @DisplayName("Mythic entity disguise is preserved as unsupported external behavior")
    void preservesExternalDisguiseMechanics(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/disguise.yml", """
                skills:
                  disguise:
                    Skills:
                      - disguise{type=ZOMBIE}
                      - removedisguise
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertTrue(target.getStringList("skills.disguise.unsupported").contains("disguise{type=ZOMBIE}"));
        assertTrue(target.getStringList("skills.disguise.unsupported").contains("removedisguise"));
        assertFalse(target.contains("skills.disguise.on-decision"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("LibsDisguises")));
    }

    @Test
    @DisplayName("Mythic item-group cooldown imports to the version-gated native runtime")
    void mapsItemGroupCooldownAndPreservesMalformedGroups(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/item-group-cooldown.yml", """
                skills:
                  item_group_gate:
                    TargetConditions:
                      - '?ItemGroupOnCooldown{group=minecraft:teleportingitems}'
                    Skills:
                      - setItemGroupCooldown{group=minecraft:teleportingitems;ticks=20} @target
                      - setItemGroupCooldown{k=TELEPORTINGITEMS;t=30} @target
                      - setItemGroupCooldown{key=teleportingitems;ticks=25;delay=5}
                      - setItemGroupCooldown @target
                      - setItemGroupCooldown{ticks=10}
                  malformed_item_group_gate:
                    TargetConditions:
                      - '?ItemGroupOnCooldown{group=minecraft:bad:key}'
                    Skills:
                      - setItemGroupCooldown{group=minecraft:bad:key;ticks=20}
                      - setItemGroupCooldown{key=minecraft:valid;ticks=1.5}
                      - setItemGroupCooldown{group=minecraft:valid;key=minecraft:other;ticks=20}
                      - setItemGroupCooldown{group=minecraft:valid;ticks=-1}
                      - setItemGroupCooldown{group=minecraft:valid;ticks=2147483648}
                      - setItemGroupCooldown{group=minecraft:valid;ticks=20;unknown=true}
                      - setItemGroupCooldown group=minecraft:valid
                      - setItemGroupCooldown minecraft:valid 20
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        Map<String, Object> plan = entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals(List.of("item-group-on-cooldown group=minecraft:teleportingitems"),
                target.getStringList("skills.item_group_gate.target-conditions"));
        assertEquals(List.of(
                        "set-item-group-cooldown group=minecraft:teleportingitems ticks=20 @target",
                        "set-item-group-cooldown group=minecraft:teleportingitems ticks=30 @target",
                        "set-item-group-cooldown{delay=5} group=minecraft:teleportingitems ticks=25",
                        "set-item-group-cooldown group=minecraft:item_group ticks=20 @target",
                        "set-item-group-cooldown group=minecraft:item_group ticks=10"),
                target.getStringList("skills.item_group_gate.on-decision"));
        assertFalse(target.contains("skills.item_group_gate.unsupported"));
        assertEquals(List.of("?ItemGroupOnCooldown{group=minecraft:bad:key}"),
                target.getStringList("skills.malformed_item_group_gate.target-conditions"));
        assertEquals(List.of(
                        "setItemGroupCooldown{group=minecraft:bad:key;ticks=20}",
                        "setItemGroupCooldown{key=minecraft:valid;ticks=1.5}",
                        "setItemGroupCooldown{group=minecraft:valid;key=minecraft:other;ticks=20}",
                        "setItemGroupCooldown{group=minecraft:valid;ticks=-1}",
                        "setItemGroupCooldown{group=minecraft:valid;ticks=2147483648}",
                        "setItemGroupCooldown{group=minecraft:valid;ticks=20;unknown=true}",
                        "setItemGroupCooldown group=minecraft:valid",
                        "setItemGroupCooldown minecraft:valid 20"),
                target.getStringList("skills.malformed_item_group_gate.unsupported"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("source-key")).contains("malformed_item_group_gate.TargetConditions.unmapped")));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("value")).contains("setItemGroupCooldown")));
    }

    @Test
    @DisplayName("Mythic FallingBlock options map to non-living entity fields")
    void mythicMapsFallingBlockOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/falling.yml", """
                mobs:
                  Falling:
                    Type: FALLING_BLOCK
                    Options:
                      BlockData: minecraft:sand
                      FallingBlockDropItem: false
                      FallingBlockHurtEntities: true
                      FallingBlockCancelDrop: true
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan(entries).orElseThrow().get("content"))));

        assertEquals("minecraft:sand", target.getString("entity.falling-block-data"));
        assertFalse(target.getBoolean("entity.falling-block-drop-item"));
        assertTrue(target.getBoolean("entity.falling-block-hurt-entities"));
        assertTrue(target.getBoolean("entity.falling-block-cancel-drop"));
        assertTrue(entries.stream().filter(entry -> "mapped".equals(entry.get("status")))
                .anyMatch(entry -> "entity.falling-block-data".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("MythicMobs：无法识别的顶层节保留在报告中")
    void mythicWithoutMobs(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml", "something-else:\n  x: 1\n");
        var entries = new MythicMobsImporter().convert(f);
        assertTrue(entries.stream().noneMatch(e -> e.containsKey("target-file")));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "something-else".equals(e.get("source-key")) && String.valueOf(e.get("value")).contains("x=1")));
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
    @DisplayName("MythicMobs delimited Immunities become separate runtime entries")
    void mythicMapsDelimitedImmunities(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml", "mobs:\n  Boss:\n    Immunities: FIRE,LAVA|DROWNING\n");
        assertEquals(List.of("FIRE", "LAVA", "DROWNING"),
                converted(f).getStringList("mob.immunities"));
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
    @DisplayName("MythicMobs scalar modifier conditions preserve range commas")
    void mythicMapsScalarConditionalModifier(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml", """
                mobs:
                  Boss:
                    DamageModifiers:
                      FIRE:
                        multiplier: 0.25
                        conditions: "health-above 0.3,time-between=0,12000"
                """);
        YamlConfiguration t = converted(f);
        assertEquals(List.of("health-above 0.3", "time-between=0,12000"),
                t.getStringList("mob.damage-modifiers.FIRE.conditions"));
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
    void mythicPreservesUnrecognizedModifierValues(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml", """
                mobs:
                  Boss:
                    DamageModifiers:
                      FIRE: invalid
                      PROJECTILE: []
                      MAGIC: [unexpected]
                      FALL: {nonsense: true}
                      LAVA: 0.5
                """);
        var entries = new MythicMobsImporter().convert(f);
        var target = converted(f);
        for (String key : List.of("FIRE", "PROJECTILE", "MAGIC", "FALL")) {
            assertTrue(target.contains("mob.unsupported.DamageModifiers." + key), key);
            assertTrue(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))
                    && String.valueOf(e.get("source-key")).endsWith(".DamageModifiers." + key)), key);
        }
        assertEquals(0.5, target.getDouble("mob.damage-modifiers.LAVA"), 1e-9);
    }

    @Test
    void mythicPreservesUnsupportedImmunityAndModifierSectionsAsYaml(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml", """
                mobs:
                  Boss:
                    Immunities:
                      FIRE:
                        conditions: ['isLiving']
                        custom:
                          levels: [1, 2]
                      FALL: false
                    DamageModifiers:
                      PROJECTILE:
                        multiplier: invalid
                        custom:
                          levels: [3, 4]
                      MAGIC:
                        multiplier: 0.5
                        custom:
                          enabled: true
                """);
        var entries = new MythicMobsImporter().convert(f);
        var target = converted(f);
        assertEquals(List.of(1, 2), target.getIntegerList("mob.unsupported.Immunities.FIRE.custom.levels"));
        assertEquals(false, target.get("mob.unsupported.Immunities.FALL"));
        assertEquals("invalid", target.getString("mob.unsupported.DamageModifiers.PROJECTILE.multiplier"));
        assertEquals(List.of(3, 4), target.getIntegerList("mob.unsupported.DamageModifiers.PROJECTILE.custom.levels"));
        assertTrue(target.getBoolean("mob.unsupported.DamageModifiers.MAGIC.custom.enabled"));
        assertTrue(entries.stream().filter(e -> "WARN".equals(e.get("severity"))).count() >= 4);
    }

    @Test
    void mythicPreservesNestedNullFieldsAsInvalidMarkers(@TempDir Path root) throws Exception {
        Path f = write(root, "Mobs/b.yml", """
                mobs:
                  Boss:
                    Immunities:
                      FIRE:
                        custom:
                          nested:
                            missing: null
                    DamageModifiers:
                      PROJECTILE:
                        multiplier: 0.5
                        custom:
                          nested:
                            missing: ~
                """);
        var entries = new MythicMobsImporter().convert(f);
        var target = converted(f);
        String marker = dev.helstera.api.util.ConditionListParser.INVALID_CONDITION;
        assertEquals(marker, target.getString("mob.unsupported.Immunities.FIRE.custom.nested.missing"));
        assertEquals(marker, target.getString("mob.unsupported.DamageModifiers.PROJECTILE.custom.nested.missing"));
        assertTrue(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).endsWith(".Immunities.FIRE")));
        assertTrue(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).endsWith(".DamageModifiers.PROJECTILE")));
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
    @Test
    @DisplayName("MythicMobs metaskill target and trigger conditions are translated into Helstera schema")
    void mythicMapsMetaskillContextConditions(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/Blast.yml", """
                Blast:
                  TargetConditions:
                    - isLiving
                  TriggerConditions:
                    - isPlayer
                  Skills:
                    - message{m=hello}
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("is-living"), target.getStringList("skills.blast.target-conditions"));
        assertEquals(List.of("is-player"), target.getStringList("skills.blast.trigger-conditions"));
        assertTrue(entries.stream().anyMatch(e -> "mapped".equals(e.get("status"))
                && String.valueOf(e.get("source-key")).endsWith(".TargetConditions")));
    }

    @Test
    @DisplayName("unmapped metaskill conditions stay visible and produce a warning")
    void mythicPreservesUnmappedMetaskillConditions(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/UnknownCondition.yml", """
                UnknownCondition:
                  TargetConditions:
                    - isLiving
                    - '?hasAura{aura=FURY}'
                    - '?hasBuff{buff=FURY}'
                    - '?hasDebuff{debuff=FURY}'
                    - '?hasAuraStacks{n=SPEED;s=>2}'
                    - '?hasAuraType{type=DEBUFF}'
                    - '?hasBuffType{group=HELPFUL}'
                    - '?hasDebuffType{auratype=HARMFUL}'
                    - '?hasBuffStacks{n=FURY;s=>2}'
                    - '?hasDebuffStacks{n=FURY;s=1}'
                    - '?auraStacks{aura=FURY;stacks=2}'
                    - '?buffStacks{aura=FURY;stacks=2}'
                    - '?debuffStacks{aura=FURY;stacks=2}'
                  TriggerConditions:
                    - '?targetHasAuras{aura=FURY}'
                  Skills:
                    - message{m=hello}
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals(List.of(
                        "is-living",
                        "mythic-has-aura aura=FURY",
                        "mythic-has-aura aura=FURY",
                        "mythic-has-aura aura=FURY",
                        "mythic-has-aura-stacks aura=SPEED stacks=>2",
                        "mythic-has-aura-type type=DEBUFF",
                        "mythic-has-aura-type type=HELPFUL",
                        "mythic-has-aura-type type=HARMFUL",
                        "mythic-has-aura-stacks aura=FURY stacks=>2",
                        "mythic-has-aura-stacks aura=FURY stacks=1",
                        "mythic-has-aura-stacks aura=FURY stacks=2",
                        "mythic-has-aura-stacks aura=FURY stacks=2",
                        "mythic-has-aura-stacks aura=FURY stacks=2"),
                target.getStringList("skills.unknowncondition.target-conditions"));
        assertEquals(List.of("mythic-target-has-aura aura=FURY"),
                target.getStringList("skills.unknowncondition.trigger-conditions"));
        assertFalse(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).contains("TargetConditions.unmapped")));
        assertFalse(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).contains("TriggerConditions.unmapped")));
    }

    @Test
    @DisplayName("execution audience condition aliases import to the runtime evaluator")
    void mythicMapsExecutionAudienceConditions(@TempDir Path root) throws Exception {
        Path file = write(root, "Skills/AudienceGate.yml", """
                AudienceGate:
                  TargetConditions:
                    - '?isInAudience'
                    - '?inaudience'
                    - '?audienceContains{}'
                  Skills:
                    - message{m=hello}
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("is-in-audience", "is-in-audience", "is-in-audience"),
                target.getStringList("skills.audiencegate.target-conditions"));
        assertFalse(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && String.valueOf(e.get("source-key")).endsWith("TargetConditions.unmapped")
                && String.valueOf(e.get("target-key")).equals("skills.audiencegate.target-conditions")));
    }

    @Test
    @DisplayName("Mythic ranged AI options map to the runtime projectile profile")
    void mythicMapsRangedAiOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/ranged.yml", """
                mobs:
                  Archer:
                    Options:
                      RangedAttack: true
                      RangedAttackRange: 24
                      ProjectileType: FIREBALL
                      ProjectileSpeed: 1.8
                      ProjectileDamage: 7
                      RangedHitSkill: burn_target
                      ProjectileGravity: false
                      ProjectileFireTicks: 60
                      ProjectileExplosion: 2.5
                      ProjectileMaxHits: 3
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(file)).get().get("content"))));
        assertTrue(target.getBoolean("ai.ranged-attack"));
        assertEquals(24, target.getInt("ai.ranged-attack-range"));
        assertEquals("FIREBALL", target.getString("ai.ranged-projectile-type"));
        assertEquals(1.8, target.getDouble("ai.ranged-projectile-speed"), 1e-9);
        assertEquals(7, target.getInt("ai.ranged-projectile-damage"));
        assertEquals("burn_target", target.getString("ai.ranged-hit-skill"));
        assertFalse(target.getBoolean("ai.ranged-projectile-gravity", true));
        assertEquals(60, target.getInt("ai.ranged-projectile-fire-ticks"));
        assertEquals(2.5, target.getDouble("ai.ranged-projectile-explosion"), 1e-9);
        assertEquals(3, target.getInt("ai.ranged-projectile-max-hits"));
    }

    @Test
    @DisplayName("MythicMobs Options and Equipment map to runtime entity fields")
    void mythicMapsRuntimeOptionsAndEquipment(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/options.yml", """
                mobs:
                  Boss:
                    Type: HUSK
                    Armor: 8
                    Stats:
                      - ARMOR_TOUGHNESS 4
                    Exp: 13
                    Options:
                      FollowRange: 40
                      MovementSpeed: 0.4
                      KnockbackResistance: 0.75
                      Damage: 12
                      Silent: true
                      NoAI: true
                      Invincible: true
                      Gravity: true
                      NoGravity: true
                      Collidable: false
                      CanPickupItems: false
                    Equipment:
                      - DIAMOND_SWORD HAND
                      - DIAMOND_HELMET HEAD
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertTrue(target.getBoolean("entity.silent"));
        assertEquals(8, target.getDouble("mob.armor"), 1e-9);
        assertEquals(4, target.getDouble("mob.armor-toughness"), 1e-9);
        assertEquals(13, target.getDouble("mob.exp"), 1e-9);
        assertEquals(40, target.getDouble("mob.sight-radius"), 1e-9);
        assertEquals(0.4, target.getDouble("mob.move-speed"), 1e-9);
        assertEquals(0.75, target.getDouble("mob.knockback-resistance"), 1e-9);
        assertEquals(12, target.getDouble("mob.attack-damage"), 1e-9);
        assertTrue(target.getBoolean("entity.no-ai"));
        assertTrue(target.getBoolean("entity.invulnerable"));
        assertFalse(target.getBoolean("entity.gravity", true));
        assertFalse(target.getBoolean("entity.collidable", true));
        assertFalse(target.getBoolean("entity.can-pickup-items", true));
        assertFalse(target.getConfigurationSection("mob.unsupported.Options") != null
                && target.getConfigurationSection("mob.unsupported.Options").contains("CanPickupItems"));
        assertEquals("DIAMOND_SWORD", target.getString("entity.items.main-hand"));
        assertEquals("DIAMOND_HELMET", target.getString("entity.items.helmet"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "mob.armor-toughness".equals(entry.get("target-key"))
                && "Boss.Stats[0]".equals(entry.get("source-key"))));
    }

    @Test
    @DisplayName("Mythic native scalar stats map while unsupported forms remain preserved")
    void mythicStatsMapNativeScalarsAndPreserveUnsupportedForms(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/stats.yml", """
                mobs:
                  Native:
                    Type: ZOMBIE
                    Armor: 2
                    Options:
                      Damage: 3
                      MovementSpeed: 0.2
                    Stats:
                      - ARMOR_TOUGHNESS 4
                      - ATTACK_DAMAGE 12
                      - MOVEMENT_SPEED 0.4
                      - ARMOR 8
                      - KNOCKBACK_RESISTANCE 0.25
                      - FOLLOW_RANGE 36
                      - ATTACK_SPEED 3
                      - CUSTOM_DEFENSE 6
                  Modifier:
                    Type: ZOMBIE
                    Stats:
                      - ARMOR_TOUGHNESS 8 ADDITIVE
                  DuplicateAlias:
                    Type: ZOMBIE
                    Stats:
                      - ATTACK_DAMAGE 2
                      - ATTACKDAMAGE 3
                  Malformed:
                    Type: ZOMBIE
                    Stats:
                      - MOVEMENT_SPEED NaN
                  LegacyRoot:
                    Type: ZOMBIE
                    ArmorToughness: 9
                """);

        var entries = new MythicMobsImporter().convert(file);
        var plans = entries.stream().filter(entry -> entry.containsKey("target-file")
                && String.valueOf(entry.get("target-file")).startsWith("mobs/"))
                .collect(java.util.stream.Collectors.toMap(
                        entry -> String.valueOf(entry.get("source-mob-id")),
                        entry -> YamlConfiguration.loadConfiguration(new StringReader(
                                String.valueOf(entry.get("content"))))));

        var nativeStats = plans.get("Native");
        assertEquals(4, nativeStats.getDouble("mob.armor-toughness"), 1e-9);
        assertEquals(12, nativeStats.getDouble("mob.attack-damage"), 1e-9);
        assertEquals(0.4, nativeStats.getDouble("mob.move-speed"), 1e-9);
        assertEquals(8, nativeStats.getDouble("mob.armor"), 1e-9);
        assertEquals(0.25, nativeStats.getDouble("mob.knockback-resistance"), 1e-9);
        assertEquals(36, nativeStats.getDouble("mob.sight-radius"), 1e-9);
        assertEquals(List.of("ATTACK_SPEED 3", "CUSTOM_DEFENSE 6"),
                nativeStats.getStringList("mob.unsupported.Stats"));
        assertFalse(plans.get("Modifier").contains("mob.armor-toughness"));
        assertEquals(List.of("ARMOR_TOUGHNESS 8 ADDITIVE"),
                plans.get("Modifier").getStringList("mob.unsupported.Stats"));
        assertFalse(plans.get("DuplicateAlias").contains("mob.attack-damage"));
        assertEquals(List.of("ATTACK_DAMAGE 2", "ATTACKDAMAGE 3"),
                plans.get("DuplicateAlias").getStringList("mob.unsupported.Stats"));
        assertFalse(plans.get("Malformed").contains("mob.move-speed"));
        assertEquals(List.of("MOVEMENT_SPEED NaN"),
                plans.get("Malformed").getStringList("mob.unsupported.Stats"));
        assertFalse(plans.get("LegacyRoot").contains("mob.armor-toughness"));
        assertEquals(9, plans.get("LegacyRoot").getInt("mob.unsupported.ArmorToughness"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "LegacyRoot.ArmorToughness".equals(entry.get("source-key"))));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "mob.move-speed".equals(entry.get("target-key"))
                && "Native.Stats[2]".equals(entry.get("source-key"))));
    }


    @Test
    @DisplayName("Equipment enchantment metadata is migrated")
    void mythicMapsEquipmentEnchantment(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/enchant.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - DIAMOND_SWORD HAND enchant SHARPNESS 5
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(5, target.getInt("entity.item-enchantments.main-hand.SHARPNESS"));
    }

    @Test
    @DisplayName("MythicMobs equipment amount and drop chance map to runtime fields")
    void mythicMapsEquipmentDropChance(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/equipment-chance.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - DIAMOND_SWORD HAND 2 0.35
                      - leather_helmet{name=Guard;amount=1} HEAD 1 25%
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals(2, target.getInt("entity.items.main-hand.amount"));
        assertEquals(0.35, target.getDouble("entity.equipment-drop-chances.main-hand"), 1e-9);
        assertEquals("leather_helmet", target.getString("entity.items.helmet.item"));
        assertEquals("Guard", target.getString("entity.items.helmet.display-name"));
        assertEquals(0.25, target.getDouble("entity.equipment-drop-chances.helmet"), 1e-9);
        assertFalse(target.contains("entity.unsupported.Equipment"));
        assertTrue(entries.stream().anyMatch(e -> "mapped".equals(e.get("status"))
                && "entity.equipment-drop-chances.main-hand".equals(e.get("target-key"))));
    }

    @Test
    @DisplayName("MythicMobs inline Equipment aliases map to item metadata")
    void mythicMapsInlineEquipmentAliases(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/inline-aliases.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - DIAMOND_SWORD{n=Blade;l=Sharp|Heavy;a=1;e=SHARPNESS:5} HAND
                      - PLAYER_HEAD{skullOwner=Builder;skullTexture=https://textures.minecraft.net/texture/example} HEAD
                      - POTION{pe=HEAL 60 1|SPEED 100 0;pc=255,0,0} OFFHAND
                """);
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("Blade", target.getString("entity.items.main-hand.display-name"));
        assertEquals(List.of("Sharp", "Heavy"), target.getStringList("entity.items.main-hand.lore"));
        assertEquals(1, target.getInt("entity.items.main-hand.amount"));
        assertEquals(5, target.getInt("entity.item-enchantments.main-hand.SHARPNESS"));
        assertEquals("Builder", target.getString("entity.items.helmet.skull-owner"));
        assertEquals("https://textures.minecraft.net/texture/example",
                target.getString("entity.items.helmet.skull-texture"));
        assertEquals(List.of("HEAL 60 1", "SPEED 100 0"),
                target.getStringList("entity.items.off-hand.potion-effects"));
        assertEquals("255,0,0", target.getString("entity.items.off-hand.color"));
        assertFalse(target.contains("entity.unsupported.Equipment"));
    }

    @Test
    @DisplayName("legacy skull data converts to modern skull materials and unknown data is retained")
    void mythicConvertsLegacySkullData(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/legacy-skulls.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - SKULL_ITEM{data=0} HEAD
                      - 397{data=3;skullOwner=Builder} OFFHAND
                      - 397{data=5} FEET
                      - DIAMOND_SWORD{data=2} HAND
                """);
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("SKELETON_SKULL", target.getString("entity.items.helmet.item"));
        assertEquals("PLAYER_HEAD", target.getString("entity.items.off-hand.item"));
        assertEquals("Builder", target.getString("entity.items.off-hand.skull-owner"));
        assertEquals("DRAGON_HEAD", target.getString("entity.items.boots.item"));
        assertEquals("2", target.getString("entity.items.main-hand.unsupported.data"));
        assertTrue(target.contains("entity.items.main-hand.unsupported.data"));
    }

    @Test
    @DisplayName("common legacy equipment IDs and tool damage convert to modern item fields")
    void mythicConvertsLegacyEquipmentIds(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/legacy-equipment.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - 276{data=42} HAND
                      - 397{data=3;skullOwner=Builder} HEAD
                      - 35{data=14} CHEST
                      - 44{data=8} FEET
                      - 53{data=5} OFFHAND
                """);
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("DIAMOND_SWORD", target.getString("entity.items.main-hand.item"));
        assertEquals(42, target.getInt("entity.items.main-hand.durability"));
        assertEquals("PLAYER_HEAD", target.getString("entity.items.helmet.item"));
        assertEquals("Builder", target.getString("entity.items.helmet.skull-owner"));
        assertEquals("RED_WOOL", target.getString("entity.items.chestplate.item"));
        assertEquals("STONE_SLAB", target.getString("entity.items.boots.item"));
        assertEquals("[type=top]", target.getString("entity.items.boots.block-states"));
        assertEquals("OAK_STAIRS", target.getString("entity.items.off-hand.item"));
        assertEquals("[facing=west,half=top]", target.getString("entity.items.off-hand.block-states"));
        assertFalse(target.contains("entity.items.main-hand.unsupported.data"));
    }

    @Test
    @DisplayName("legacy numeric Equipment id:data shorthand maps variants and durability")
    void mythicConvertsLegacyEquipmentIdDataShorthand(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/legacy-equipment-shorthand.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - '35:14 CHEST'
                      - 'minecraft:346:12 HAND'
                """);
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("RED_WOOL", target.getString("entity.items.chestplate.item"));
        assertEquals("FISHING_ROD", target.getString("entity.items.main-hand.item"));
        assertEquals(12, target.getInt("entity.items.main-hand.durability"));
        assertFalse(target.contains("entity.items.chestplate.unsupported.legacy-id"));
    }

    @Test
    @DisplayName("legacy durability rejects negative, malformed, and over-limit damage in items and equipment")
    void mythicRejectsInvalidLegacyDurability(@TempDir Path root) throws Exception {
        Path items = write(root, "Items/legacy-durability-bounds.yml", """
                items:
                  valid: {Id: 276, Data: 1560}
                  shorthand: {Id: "276:37"}
                  negative: {Id: 276, Data: -1}
                  over_limit: {Id: 276, Data: 1561}
                  malformed: {Id: 276, Data: not-a-number}
                  shield_over_limit: {Id: 442, Data: 336}
                """);
        var itemEntries = new MythicMobsImporter().convert(items);
        YamlConfiguration itemTarget = YamlConfiguration.loadConfiguration(new StringReader(
                String.valueOf(plan(itemEntries).orElseThrow().get("content"))));

        assertEquals("DIAMOND_SWORD", itemTarget.getString("items.valid.id"));
        assertEquals(1560, itemTarget.getInt("items.valid.durability"));
        assertEquals(37, itemTarget.getInt("items.shorthand.durability"));
        assertEquals("-1", itemTarget.getString("items.negative.unsupported.legacy-data"));
        assertEquals("1561", itemTarget.getString("items.over_limit.unsupported.legacy-data"));
        assertEquals("not-a-number", itemTarget.getString("items.malformed.unsupported.legacy-data"));
        assertEquals("336", itemTarget.getString("items.shield_over_limit.unsupported.legacy-data"));
        assertTrue(itemEntries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && String.valueOf(entry.get("note")).contains("modern Material durability range")));

        Path mobs = write(root, "Mobs/legacy-durability-bounds.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - '276{data=1560} HAND'
                      - '276{data=1561} OFFHAND'
                      - '276{data=-1} HEAD'
                      - '276{data=not-a-number} CHEST'
                      - '276:37 FEET'
                """);
        var equipmentEntries = new MythicMobsImporter().convert(mobs);
        YamlConfiguration equipmentTarget = YamlConfiguration.loadConfiguration(new StringReader(
                String.valueOf(plan(equipmentEntries).orElseThrow().get("content"))));

        assertEquals(1560, equipmentTarget.getInt("entity.items.main-hand.durability"));
        assertEquals("1561", equipmentTarget.getString("entity.items.off-hand.unsupported.data"));
        assertEquals("-1", equipmentTarget.getString("entity.items.helmet.unsupported.data"));
        assertEquals("not-a-number", equipmentTarget.getString("entity.items.chestplate.unsupported.data"));
        assertEquals(37, equipmentTarget.getInt("entity.items.boots.durability"));
        assertFalse(equipmentTarget.contains("entity.items.off-hand.durability"));
        assertFalse(equipmentTarget.contains("entity.items.helmet.durability"));
        assertFalse(equipmentTarget.contains("entity.items.chestplate.durability"));
    }

    @Test
    @DisplayName("out-of-range legacy numeric IDs and data remain visible")
    void mythicPreservesOutOfRangeLegacyNumbers(@TempDir Path root) throws Exception {
        Path items = write(root, "Items/overflow.yml", """
                items:
                  huge_id: {Id: '99999999999999999999'}
                  huge_data: {Id: '35:99999999999999999999'}
                  huge_both: {Id: '99999999999999999999:12'}
                """);
        var entries = new MythicMobsImporter().convert(items);
        var target = YamlConfiguration.loadConfiguration(new StringReader(
                String.valueOf(plan(entries).orElseThrow().get("content"))));
        assertEquals("99999999999999999999", target.getString("items.huge_id.unsupported.legacy-id"));
        assertEquals("99999999999999999999", target.getString("items.huge_data.unsupported.legacy-data"));
        assertEquals("99999999999999999999:12", target.getString("items.huge_both.unsupported.legacy-id"));
        assertEquals("12", target.getString("items.huge_both.unsupported.legacy-data"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "items.huge_id.unsupported.legacy-id".equals(entry.get("target-key"))));

        Path mobs = write(root, "Mobs/overflow.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - '99999999999999999999 HAND'
                      - '35:99999999999999999999 CHEST'
                """);
        var equipment = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(
                plan(new MythicMobsImporter().convert(mobs)).orElseThrow().get("content"))));
        assertEquals("99999999999999999999", equipment.getString("entity.items.main-hand.item"));
        assertEquals("99999999999999999999", equipment.getString("entity.items.main-hand.unsupported.legacy-id"));
        assertEquals("35:99999999999999999999", equipment.getString("entity.items.chestplate.item"));
        assertEquals("99999999999999999999", equipment.getString("entity.items.chestplate.unsupported.data"));
    }

    @Test
    @DisplayName("Mythic item files convert known numeric IDs and report unknown IDs/data")
    void mythicConvertsLegacyItemFileIds(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/legacy-ids.yml", """
                items:
                  sword:
                    Id: 276
                    Data: 37
                  player_head:
                    Id: 397
                    Data: 3
                  unknown:
                    Id: 9999
                    Data: 12
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("DIAMOND_SWORD", target.getString("items.sword.id"));
        assertEquals(37, target.getInt("items.sword.durability"));
        assertEquals("PLAYER_HEAD", target.getString("items.player_head.id"));
        assertEquals("9999", target.getString("items.unknown.unsupported.legacy-id"));
        assertEquals("12", target.getString("items.unknown.unsupported.legacy-data"));
        assertTrue(entries.stream().filter(entry -> "unsupported".equals(entry.get("status")))
                .count() >= 2);
    }

    @Test
    @DisplayName("common block variants, dyes, and invalid legacy data map or remain explicit")
    void mythicConvertsLegacyItemVariants(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/legacy-variants.yml", """
                items:
                  red_wool:
                    Id: 35
                    Data: 14
                  red_wool_colon:
                    Id: "35:14"
                  minecraft_red_wool_colon:
                    Id: "minecraft:35:14"
                  namespaced_diamond:
                    Id: "minecraft:diamond"
                  cyan_glass:
                    Id: 95
                    Data: 9
                  dark_oak_planks:
                    Id: 5
                    Data: 5
                  charcoal:
                    Id: 263
                    Data: 1
                  yellow_dye:
                    Id: 351
                    Data: 11
                  dark_oak_sapling:
                    Id: 6
                    Data: 5
                  acacia_log:
                    Id: 162
                    Data: 0
                  birch_leaves_decay_bit:
                    Id: 18
                    Data: 6
                  birch_leaves:
                    Id: 18
                    Data: 2
                  chiseled_sandstone:
                    Id: 24
                    Data: 1
                  chiseled_bricks:
                    Id: 98
                    Data: 3
                  blue_orchid:
                    Id: 38
                    Data: 1
                  dandelion:
                    Id: 37
                    Data: 0
                  top_stone_slab:
                    Id: 44
                    Data: 8
                  double_stone_slab:
                    Id: 43
                  top_dark_oak_slab:
                    Id: 126
                    Data: 13
                  double_jungle_slab:
                    Id: 125
                    Data: 3
                  oak_log_x:
                    Id: 17
                    Data: 4
                  oak_wood:
                    Id: 17
                    Data: 12
                  oak_stairs:
                    Id: 53
                    Data: 5
                  red_sandstone_stairs:
                    Id: 180
                    Data: 3
                  invalid_wool:
                    Id: 35
                    Data: 16
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("RED_WOOL", target.getString("items.red_wool.id"));
        assertEquals("RED_WOOL", target.getString("items.red_wool_colon.id"));
        assertEquals("RED_WOOL", target.getString("items.minecraft_red_wool_colon.id"));
        assertEquals("DIAMOND", target.getString("items.namespaced_diamond.id"));
        assertEquals("CYAN_STAINED_GLASS", target.getString("items.cyan_glass.id"));
        assertEquals("DARK_OAK_PLANKS", target.getString("items.dark_oak_planks.id"));
        assertEquals("CHARCOAL", target.getString("items.charcoal.id"));
        assertEquals("YELLOW_DYE", target.getString("items.yellow_dye.id"));
        assertEquals("DARK_OAK_SAPLING", target.getString("items.dark_oak_sapling.id"));
        assertEquals("ACACIA_LOG", target.getString("items.acacia_log.id"));
        assertEquals("BIRCH_LEAVES", target.getString("items.birch_leaves_decay_bit.id"));
        assertEquals("BIRCH_LEAVES", target.getString("items.birch_leaves.id"));
        assertEquals("CHISELED_SANDSTONE", target.getString("items.chiseled_sandstone.id"));
        assertEquals("CHISELED_STONE_BRICKS", target.getString("items.chiseled_bricks.id"));
        assertEquals("BLUE_ORCHID", target.getString("items.blue_orchid.id"));
        assertEquals("DANDELION", target.getString("items.dandelion.id"));
        assertEquals("STONE_SLAB", target.getString("items.top_stone_slab.id"));
        assertEquals("[type=top]", target.getString("items.top_stone_slab.block-states"));
        assertEquals("[type=double]", target.getString("items.double_stone_slab.block-states"));
        assertEquals("DARK_OAK_SLAB", target.getString("items.top_dark_oak_slab.id"));
        assertEquals("[type=top]", target.getString("items.top_dark_oak_slab.block-states"));
        assertEquals("JUNGLE_SLAB", target.getString("items.double_jungle_slab.id"));
        assertEquals("[type=double]", target.getString("items.double_jungle_slab.block-states"));
        assertEquals("OAK_LOG", target.getString("items.oak_log_x.id"));
        assertEquals("[axis=x]", target.getString("items.oak_log_x.block-states"));
        assertEquals("OAK_WOOD", target.getString("items.oak_wood.id"));
        assertEquals("OAK_STAIRS", target.getString("items.oak_stairs.id"));
        assertEquals("[facing=west,half=top]", target.getString("items.oak_stairs.block-states"));
        assertEquals("RED_SANDSTONE_STAIRS", target.getString("items.red_sandstone_stairs.id"));
        assertEquals("[facing=north,half=bottom]", target.getString("items.red_sandstone_stairs.block-states"));
        assertEquals("16", target.getString("items.invalid_wool.unsupported.legacy-data"));
        assertFalse(target.contains("items.invalid_wool.unsupported.legacy-id"));
        for (String id : List.of("red_wool", "cyan_glass", "dark_oak_planks", "charcoal", "yellow_dye",
                "dark_oak_sapling", "acacia_log", "birch_leaves_decay_bit", "birch_leaves",
                "chiseled_sandstone", "chiseled_bricks",
                "blue_orchid", "dandelion", "top_stone_slab", "double_stone_slab",
                "top_dark_oak_slab", "double_jungle_slab", "oak_log_x", "oak_wood", "oak_stairs",
                "red_sandstone_stairs")) {
            assertNotNull(org.bukkit.Material.getMaterial(target.getString("items." + id + ".id")), id);
        }
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "items.invalid_wool.unsupported.legacy-data".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("additional legacy block IDs and variants map without guessing unsupported data")
    void mythicConvertsAdditionalLegacyBlockIds(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/legacy-blocks.yml", """
                items:
                  grass_block:
                    Id: 2
                  air:
                    Id: 0
                  crafting_table:
                    Id: 58
                  dirt_path:
                    Id: 208
                  flowing_water:
                    Id: 8
                  still_water:
                    Id: 9
                  flowing_lava:
                    Id: 10
                  still_lava:
                    Id: 11
                  piston_head:
                    Id: 34
                  moving_piston:
                    Id: 36
                  white_shulker:
                    Id: 219
                  red_shulker:
                    Id: 233
                  white_glazed:
                    Id: 235
                  black_glazed:
                    Id: 250
                    Data: 1
                  purpur_stairs:
                    Id: 203
                  purpur_slab_top:
                    Id: 205
                    Data: 8
                  spruce_gate:
                    Id: 183
                  spruce_fence:
                    Id: 188
                  spruce_door:
                    Id: 193
                  end_gateway:
                    Id: 209
                  chain_command_block:
                    Id: 211
                  structure_block:
                    Id: 255
                  infested_mossy:
                    Id: 97
                    Data: 3
                  quartz_pillar:
                    Id: 155
                    Data: 2
                  prismarine_bricks:
                    Id: 168
                    Data: 1
                  peony:
                    Id: 175
                    Data: 5
                  fern:
                    Id: 31
                    Data: 2
                  invalid_cobblestone_data:
                    Id: 4
                    Data: 1
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("GRASS_BLOCK", target.getString("items.grass_block.id"));
        assertEquals("AIR", target.getString("items.air.id"));
        assertEquals("CRAFTING_TABLE", target.getString("items.crafting_table.id"));
        assertEquals("DIRT_PATH", target.getString("items.dirt_path.id"));
        assertEquals("WATER", target.getString("items.flowing_water.id"));
        assertEquals("WATER", target.getString("items.still_water.id"));
        assertEquals("LAVA", target.getString("items.flowing_lava.id"));
        assertEquals("LAVA", target.getString("items.still_lava.id"));
        assertEquals("PISTON_HEAD", target.getString("items.piston_head.id"));
        assertEquals("MOVING_PISTON", target.getString("items.moving_piston.id"));
        assertEquals("WHITE_SHULKER_BOX", target.getString("items.white_shulker.id"));
        assertEquals("RED_SHULKER_BOX", target.getString("items.red_shulker.id"));
        assertEquals("WHITE_GLAZED_TERRACOTTA", target.getString("items.white_glazed.id"));
        assertEquals("250", target.getString("items.black_glazed.id"));
        assertEquals("1", target.getString("items.black_glazed.unsupported.legacy-data"));
        assertEquals("PURPUR_STAIRS", target.getString("items.purpur_stairs.id"));
        assertEquals("PURPUR_SLAB", target.getString("items.purpur_slab_top.id"));
        assertEquals("[type=top]", target.getString("items.purpur_slab_top.block-states"));
        assertEquals("SPRUCE_FENCE_GATE", target.getString("items.spruce_gate.id"));
        assertEquals("SPRUCE_FENCE", target.getString("items.spruce_fence.id"));
        assertEquals("SPRUCE_DOOR", target.getString("items.spruce_door.id"));
        assertEquals("END_GATEWAY", target.getString("items.end_gateway.id"));
        assertEquals("CHAIN_COMMAND_BLOCK", target.getString("items.chain_command_block.id"));
        assertEquals("STRUCTURE_BLOCK", target.getString("items.structure_block.id"));
        assertEquals("INFESTED_MOSSY_STONE_BRICKS", target.getString("items.infested_mossy.id"));
        assertEquals("QUARTZ_PILLAR", target.getString("items.quartz_pillar.id"));
        assertEquals("PRISMARINE_BRICKS", target.getString("items.prismarine_bricks.id"));
        assertEquals("PEONY", target.getString("items.peony.id"));
        assertEquals("FERN", target.getString("items.fern.id"));
        assertEquals("4", target.getString("items.invalid_cobblestone_data.id"));
        assertEquals("1", target.getString("items.invalid_cobblestone_data.unsupported.legacy-data"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "items.invalid_cobblestone_data.unsupported.legacy-data".equals(entry.get("target-key"))));

        for (String id : List.of("AIR", "GRASS_BLOCK", "CRAFTING_TABLE", "DIRT_PATH", "WATER", "LAVA",
                "PISTON_HEAD", "MOVING_PISTON", "WHITE_SHULKER_BOX",
                "RED_SHULKER_BOX", "WHITE_GLAZED_TERRACOTTA", "PURPUR_STAIRS", "PURPUR_SLAB",
                "SPRUCE_FENCE_GATE", "SPRUCE_FENCE", "SPRUCE_DOOR", "END_GATEWAY", "CHAIN_COMMAND_BLOCK",
                "STRUCTURE_BLOCK", "INFESTED_MOSSY_STONE_BRICKS", "QUARTZ_PILLAR", "PRISMARINE_BRICKS",
                "PEONY", "FERN")) {
            assertNotNull(org.bukkit.Material.getMaterial(id), id);
        }
    }

    @Test
    @DisplayName("legacy item IDs 318-449 map to Paper materials and keep data-bearing boundaries explicit")
    void mythicConvertsLegacyItemIdsAndData(@TempDir Path root) throws Exception {
        StringBuilder yaml = new StringBuilder("items:\n");
        for (int id = 318; id <= 449; id++) {
            yaml.append("  item_").append(id).append(":\n    Id: ").append(id).append('\n');
        }
        yaml.append("""
                  item_450:
                    Id: 450
                  item_451:
                    Id: 451
                  item_452:
                    Id: 452
                  item_453:
                    Id: 453
                  white_bed:
                    Id: 355
                    Data: 0
                  red_bed:
                    Id: 355
                    Data: 14
                  zombie_spawn_egg:
                    Id: 383
                    Data: 54
                  unknown_spawn_egg:
                    Id: 383
                    Data: 999
                  record_13:
                    Id: 2256
                  record_wait:
                    Id: 2267
                  salmon:
                    Id: 349
                    Data: 1
                  red_banner:
                    Id: 425
                    Data: 14
                  potion_with_data:
                    Id: 373
                    Data: 1
                  potion_regeneration:
                    Id: 373
                    Data: 8193
                  potion_long_swiftness:
                    Id: 373
                    Data: 8258
                  potion_strong_healing:
                    Id: 373
                    Data: 8229
                  splash_poison:
                    Id: 438
                    Data: 24580
                  named_potion:
                    Material: POTION
                    Data: 8193
                  fishing_rod_damage:
                    Id: 346
                    Data: 12
                  shears_damage:
                    Id: 359
                    Data: 7
                  carrot_stick_damage:
                    Id: 398
                    Data: 3
                  shield_damage:
                    Id: 442
                    Data: 4
                  elytra_damage:
                    Id: 443
                    Data: 5
                  cobblestone_zero_data:
                    Id: 4
                    Data: 0
                """);
        Path file = write(root, "Items/legacy-items.yml", yaml.toString());
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        for (int id = 318; id <= 449; id++) {
            if (id == 383) continue;
            String name = target.getString("items.item_" + id + ".id");
            assertNotNull(name, "legacy item ID " + id + " should have been converted");
            assertNotNull(org.bukkit.Material.getMaterial(name),
                    "legacy item ID " + id + " converted to unknown Paper Material " + name);
        }
        assertEquals("383", target.getString("items.item_383.id"));
        assertTrue(target.contains("items.item_383.unsupported.legacy-id"));
        assertEquals("ZOMBIE_SPAWN_EGG", target.getString("items.zombie_spawn_egg.id"));
        assertFalse(target.contains("items.zombie_spawn_egg.unsupported.legacy-data"));
        assertEquals("383", target.getString("items.unknown_spawn_egg.id"));
        assertEquals("999", target.getString("items.unknown_spawn_egg.unsupported.legacy-data"));
        assertEquals("SALMON", target.getString("items.salmon.id"));
        assertEquals("RED_BANNER", target.getString("items.red_banner.id"));
        assertEquals("373", target.getString("items.potion_with_data.id"));
        assertEquals("1", target.getString("items.potion_with_data.unsupported.legacy-data"));
        assertEquals("POTION", target.getString("items.potion_regeneration.id"));
        assertEquals("REGENERATION", target.getString("items.potion_regeneration.base-potion-type"));
        assertEquals("LONG_SWIFTNESS", target.getString("items.potion_long_swiftness.base-potion-type"));
        assertEquals("STRONG_HEALING", target.getString("items.potion_strong_healing.base-potion-type"));
        assertEquals("SPLASH_POTION", target.getString("items.splash_poison.id"));
        assertEquals("POISON", target.getString("items.splash_poison.base-potion-type"));
        assertEquals("POTION", target.getString("items.named_potion.id"));
        assertEquals("REGENERATION", target.getString("items.named_potion.base-potion-type"));
        assertFalse(target.contains("items.potion_regeneration.unsupported.legacy-data"));
        assertFalse(target.contains("items.splash_poison.unsupported.legacy-data"));
        assertEquals(12, target.getInt("items.fishing_rod_damage.durability"));
        assertEquals(7, target.getInt("items.shears_damage.durability"));
        assertEquals(3, target.getInt("items.carrot_stick_damage.durability"));
        assertEquals(4, target.getInt("items.shield_damage.durability"));
        assertEquals(5, target.getInt("items.elytra_damage.durability"));
        assertEquals("COBBLESTONE", target.getString("items.cobblestone_zero_data.id"));
        assertFalse(target.contains("items.cobblestone_zero_data.unsupported.legacy-data"));
        assertEquals("SHULKER_SHELL", target.getString("items.item_450.id"));
        assertEquals("IRON_NUGGET", target.getString("items.item_451.id"));
        assertEquals("KNOWLEDGE_BOOK", target.getString("items.item_452.id"));
        assertEquals("453", target.getString("items.item_453.id"));
        assertTrue(target.contains("items.item_453.unsupported.legacy-id"));
        assertEquals("WHITE_BED", target.getString("items.white_bed.id"));
        assertEquals("RED_BED", target.getString("items.red_bed.id"));
        assertEquals("MUSIC_DISC_13", target.getString("items.record_13.id"));
        assertEquals("MUSIC_DISC_WAIT", target.getString("items.record_wait.id"));
        for (String id : List.of("SHULKER_SHELL", "IRON_NUGGET", "KNOWLEDGE_BOOK", "ZOMBIE_SPAWN_EGG", "MUSIC_DISC_13",
                "MUSIC_DISC_WAIT")) {
            assertNotNull(org.bukkit.Material.getMaterial(id), id);
        }
    }

    @Test
    @DisplayName("legacy sugar cane and book IDs preserve their distinct materials")
    void mythicLegacySugarCaneAndBookIds(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/legacy-distinct.yml", """
                items:
                  cane: {Id: 338}
                  sugar: {Id: 353}
                  writable: {Id: 386}
                  written: {Id: 387}
                """);
        var result = plan(new MythicMobsImporter().convert(file)).orElseThrow();
        var target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(result.get("content"))));
        assertEquals("SUGAR_CANE", target.getString("items.cane.id"));
        assertEquals("SUGAR", target.getString("items.sugar.id"));
        assertEquals("WRITABLE_BOOK", target.getString("items.writable.id"));
        assertEquals("WRITTEN_BOOK", target.getString("items.written.id"));
    }

    @Test
    @DisplayName("all converted legacy equipment IDs resolve to Paper Materials")
    void mythicLegacyEquipmentMaterialNamesExistInPaper(@TempDir Path root) throws Exception {
        StringBuilder yaml = new StringBuilder("items:\n");
        for (int id = 256; id <= 317; id++) {
            yaml.append("  item_").append(id).append(":\n    Id: ").append(id).append('\n');
        }
        Path file = write(root, "Items/equipment-id-range.yml", yaml.toString());
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        for (int id = 256; id <= 317; id++) {
            String name = target.getString("items.item_" + id + ".id");
            assertNotNull(name, "legacy ID " + id + " should have been converted");
            assertNotNull(org.bukkit.Material.getMaterial(name),
                    "legacy ID " + id + " converted to unknown Paper Material " + name);
        }
    }

    @Test
    @DisplayName("MythicMobs Attributes are copied to entity attribute modifier schema")
    void mythicMapsAttributes(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/attributes.yml", """
                mobs:
                  Boss:
                    Attributes:
                      attack:
                        attribute: GENERIC_ATTACK_DAMAGE
                        amount: 5
                        operation: ADD_NUMBER
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals("GENERIC_ATTACK_DAMAGE", target.getString("entity.attributes.main-hand.attack.attribute"));
        assertEquals(5.0, target.getDouble("entity.attributes.main-hand.attack.amount"), 0.001);
    }

    @Test
    @DisplayName("MythicMobs percentage attributes are preserved for runtime conversion")
    void mythicPreservesPercentageAttributeAmount(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/percentage-attributes.yml", """
                mobs:
                  Boss:
                    Attributes:
                      attack:
                        attribute: ATTACK_DAMAGE
                        amount: 10%
                        operation: ADD
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals("10%", target.getString("entity.attributes.main-hand.attack.amount"));
        assertEquals("ADD", target.getString("entity.attributes.main-hand.attack.operation"));
    }

    @Test
    @DisplayName("MythicMobs scalar attributes remain runtime-consumable")
    void mythicMapsScalarAttributes(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/scalar-attributes.yml", """
                mobs:
                  Boss:
                    Attributes:
                      GENERIC_ATTACK_DAMAGE: 12
                      GENERIC_MOVEMENT_SPEED: 0.35
                """);
        YamlConfiguration target = converted(file);

        assertEquals(12.0, target.getDouble("entity.attributes.GENERIC_ATTACK_DAMAGE"), 1e-9);
        assertEquals(0.35, target.getDouble("entity.attributes.GENERIC_MOVEMENT_SPEED"), 1e-9);
    }

    @Test
    @DisplayName("Mythic standard attribute groups become Paper modifiers and invalid values stay visible")
    void mythicMapsStandardAttributeGroupsAndPreservesInvalidValues(@TempDir Path root) throws Exception {
        Path mobFile = write(root, "Mobs/standard-attributes.yml", """
                mobs:
                  Boss:
                    Attributes:
                      GENERIC_MAX_HEALTH: 40
                      MainHand:
                        Damage: 4
                        MovementSpeed: 0.2
                        Broken: not-a-number
                """);
        YamlConfiguration mob = converted(mobFile);
        assertEquals(40.0, mob.getDouble("entity.attributes.GENERIC_MAX_HEALTH"), 1e-9);
        assertEquals("GENERIC_ATTACK_DAMAGE",
                mob.getString("entity.attributes.main-hand.damage.attribute"));
        assertEquals(4.0, mob.getDouble("entity.attributes.main-hand.damage.amount"), 1e-9);
        assertEquals("GENERIC_MOVEMENT_SPEED",
                mob.getString("entity.attributes.main-hand.movementspeed.attribute"));
        assertEquals("not-a-number",
                mob.getString("entity.unsupported.Attributes.main-hand.broken"));

        Path itemFile = write(root, "Items/standard-attributes.yml", """
                items:
                  sword:
                    Id: DIAMOND_SWORD
                    Attributes:
                      MainHand:
                        Damage: 3
                        AttackSpeed:
                          Amount: 10%
                          Operation: ADD
                          Slot: HAND
                      OffHand:
                        Broken: nope
                """);
        YamlConfiguration item = converted(itemFile);
        assertEquals("GENERIC_ATTACK_DAMAGE",
                item.getString("items.sword.attributes.mainhand.damage.attribute"));
        assertEquals(3.0, item.getDouble("items.sword.attributes.mainhand.damage.amount"), 1e-9);
        assertEquals("GENERIC_ATTACK_SPEED",
                item.getString("items.sword.attributes.hand.attackspeed.attribute"));
        assertEquals("10%", item.getString("items.sword.attributes.hand.attackspeed.amount"));
        assertEquals("ADD", item.getString("items.sword.attributes.hand.attackspeed.operation"));
        assertEquals("nope", item.getString("items.sword.unsupported.attributes.offhand.broken"));
    }

    @Test
    @DisplayName("Unknown item attribute operations and slots are preserved instead of projected")
    void mythicPreservesInvalidAttributeModifiers(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/invalid-attributes.yml", """
                items:
                  invalid:
                    Id: DIAMOND_SWORD
                    Attributes:
                      MainHand:
                        attack:
                          Attribute: ATTACK_DAMAGE
                          Amount: 2
                          Operation: NO_SUCH_OPERATION
                        speed:
                          Attribute: ATTACK_SPEED
                          Amount: 1
                          Slot: NO_SUCH_SLOT
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);
        assertFalse(target.contains("items.invalid.attributes.mainhand.attack"));
        assertFalse(target.contains("items.invalid.attributes.mainhand.speed"));
        assertEquals("NO_SUCH_OPERATION",
                target.getString("items.invalid.unsupported.attributes.mainhand.attack.Operation"));
        assertEquals("NO_SUCH_SLOT",
                target.getString("items.invalid.unsupported.attributes.mainhand.speed.Slot"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && String.valueOf(entry.get("note")).contains("attribute modifier")));
    }

    @Test
    @DisplayName("MythicMobs Options.AttackSpeed is preserved when Paper entities lack that attribute")
    void mythicPreservesAttackSpeedOption(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/attack-speed.yml", """
                mobs:
                  Swift:
                    Type: ZOMBIE
                    Options:
                      AttackSpeed: 1.75
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertEquals(1.75, target.getDouble("mob.unsupported.Options.AttackSpeed"), 1e-9);
        assertFalse(target.contains("entity.attack-speed"));
        assertTrue(entries.stream().anyMatch(entry ->
                "unsupported".equals(entry.get("status"))
                        && "mob.unsupported.Options".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("MythicMobs ReinforcementsChance maps only valid Zombie-family values")
    void mythicMapsZombieReinforcementsChanceAndPreservesInvalidValues(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/reinforcements.yml", """
                mobs:
                  ZombieChance:
                    Type: ZOMBIE
                    Options:
                      ReinforcementsChance: 0.38
                  HuskChance:
                    Type: HUSK
                    Options:
                      ReinforcementsChance: "1"
                  NonZombieChance:
                    Type: SKELETON
                    Options:
                      ReinforcementsChance: 0.5
                  OutOfRangeChance:
                    Type: ZOMBIE
                    Options:
                      ReinforcementsChance: 1.01
                  MalformedChance:
                    Type: ZOMBIE
                    Options:
                      ReinforcementsChance: not-a-number
                """);
        var entries = new MythicMobsImporter().convert(file);

        var zombie = convertedMob(entries, "zombiechance");
        assertEquals(0.38, zombie.getDouble("entity.zombie-reinforcements-chance"), 1e-9);
        assertFalse(zombie.contains("mob.unsupported.Options.ReinforcementsChance"));
        var husk = convertedMob(entries, "huskchance");
        assertEquals(1.0, husk.getDouble("entity.zombie-reinforcements-chance"), 1e-9);
        var nonZombie = convertedMob(entries, "nonzombiechance");
        assertEquals(0.5, nonZombie.getDouble("mob.unsupported.Options.ReinforcementsChance"), 1e-9);
        assertFalse(nonZombie.contains("entity.zombie-reinforcements-chance"));
        var outOfRange = convertedMob(entries, "outofrangechance");
        assertEquals(1.01, outOfRange.getDouble("mob.unsupported.Options.ReinforcementsChance"), 1e-9);
        assertFalse(outOfRange.contains("entity.zombie-reinforcements-chance"));
        var malformed = convertedMob(entries, "malformedchance");
        assertEquals("not-a-number", malformed.getString("mob.unsupported.Options.ReinforcementsChance"));
        assertFalse(malformed.contains("entity.zombie-reinforcements-chance"));
        assertTrue(entries.stream().anyMatch(entry ->
                "mapped".equals(entry.get("status"))
                        && "entity.zombie-reinforcements-chance".equals(entry.get("target-key"))));
        assertTrue(entries.stream().filter(entry ->
                        "unsupported".equals(entry.get("status"))
                                && "Options.ReinforcementsChance".equals(entry.get("source-key")))
                .count() >= 3);
    }

    private static YamlConfiguration convertedMob(List<Map<String, Object>> entries, String mobId) {
        var plan = entries.stream().filter(entry ->
                        ("mobs/mythic_" + mobId + ".yml").equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
    }

    @Test
    @DisplayName("MythicMobs Options.MaxCombatDistance maps to the mob distance guard")
    void mythicMapsMaxCombatDistanceOption(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/combat-distance.yml", """
                mobs:
                  Guarded:
                    Type: ZOMBIE
                    Options:
                      MaxCombatDistance: 8
                """);
        YamlConfiguration target = converted(file);

        assertEquals(8.0, target.getDouble("mob.max-combat-distance"), 1e-9);
        assertFalse(target.contains("mob.unsupported.Options.MaxCombatDistance"));
    }

    @Test
    @DisplayName("MythicMobs Options.SpawningDistance maps to the legacy mob despawn radius")
    void mythicMapsSpawningDistanceOption(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/spawning-distance.yml", """
                mobs:
                  Wandering:
                    Type: ZOMBIE
                    Options:
                      SpawningDistance: 8
                """);
        YamlConfiguration target = converted(file);

        assertEquals(8.0, target.getDouble("mob.spawning-distance"), 1e-9);
        assertFalse(target.contains("mob.unsupported.Options.SpawningDistance"));
    }

    @Test
    @DisplayName("MythicMobs option aliases and ThreatTable module use runtime paths")
    void mythicMapsOptionAliasesAndModules(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/aliases.yml", """
                mobs:
                  Boss:
                    Options:
                      UseThreatTable: true
                      Scale: 1.25
                      ShowHealth: true
                      VisibleByDefault: false
                    Modules:
                      ThreatTable: false
                      CustomVendorModule: true
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertTrue(target.getBoolean("show-health-bar"));
        assertEquals(1.25, target.getDouble("scale"), 1e-9);
        assertTrue(target.getBoolean("ai.threat-enabled"),
                "Options.UseThreatTable is an explicit runtime override");
        assertTrue(target.getBoolean("mob.unsupported.Modules.CustomVendorModule"));
        assertFalse(target.getBoolean("entity.visible-by-default", true));
        assertFalse(target.contains("mob.unsupported.Options.VisibleByDefault"));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && String.valueOf(e.get("source-key")).contains("Modules")));
    }

    @Test
    @DisplayName("MythicMobs interaction guard options map to runtime mob fields")
    void mythicMapsInteractionGuards(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/guards.yml", """
                mobs:
                  Boss:
                    Type: HUSK
                    Options:
                      PreventTransformation: true
                      PreventMount: true
                      PreventJockeyMounts: true
                      PreventTarget: true
                      PreventAttack: true
                      PreventVanillaDamage: true
                      PreventSlimeSplit: true
                      PreventBlockInfection: true
                      PreventSnowFormation: true
                      CanPickupItems: false
                      Age: -1
                      AgeLock: true
                      Adult: true
                      Baby: true
                      Saddled: true
                      HorseArmor: DIAMOND_HORSE_ARMOR
                      CarryingChest: true
                      CanJoinRaid: false
                      PatrolLeader: true
                      PatrolSpawnPoint: true
                      Tamed: true
                      PreventTeleport: true
                      PreventPickup: true
                      PreventItemUse: true
                      PreventDrowning: true
                      PreventFallDamage: true
                      PreventFire: true
                      PreventProjectiles: true
                      PreventExplosions: true
                      PreventBlockExplosions: true
                      PreventEntityExplosions: true
                """);
        var entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertEquals("HUSK", target.getString("mob.entity-type"));
        assertTrue(target.getBoolean("mob.prevent-transformation"));
        assertTrue(target.getBoolean("mob.prevent-mount"));
        assertTrue(target.getBoolean("mob.prevent-jockey-mounts"));
        assertTrue(target.getBoolean("mob.prevent-target"));
        assertTrue(target.getBoolean("mob.prevent-attack"));
        assertTrue(target.getBoolean("mob.prevent-vanilla-damage"));
        assertFalse(target.contains("mob.prevent-slime-split"));
        assertFalse(target.contains("mob.prevent-block-infection"));
        assertFalse(target.contains("mob.prevent-snow-formation"));
        assertTrue(target.getBoolean("entity.unsupported.Options.PreventSlimeSplit"));
        assertTrue(target.getBoolean("entity.unsupported.Options.PreventBlockInfection"));
        assertTrue(target.getBoolean("entity.unsupported.Options.PreventSnowFormation"));
        assertFalse(target.getBoolean("entity.can-pickup-items", true));
        assertFalse(target.contains("entity.age"));
        assertFalse(target.contains("entity.age-lock"));
        assertEquals(-1, target.getInt("entity.unsupported.Options.Age"));
        assertTrue(target.getBoolean("entity.unsupported.Options.AgeLock"));
        assertTrue(target.getBoolean("entity.adult"));
        assertTrue(target.getBoolean("entity.baby"));
        assertFalse(target.contains("entity.saddled"));
        assertEquals("DIAMOND_HORSE_ARMOR",
                target.getString("entity.unsupported.Options.HorseArmor"));
        assertTrue(target.getBoolean("entity.unsupported.Options.Saddled"));
        assertTrue(target.getBoolean("entity.unsupported.Options.CarryingChest"));
        assertFalse(target.contains("entity.can-join-raid"));
        assertFalse(target.contains("entity.patrol-leader"));
        assertFalse(target.contains("entity.patrol-spawn-point"));
        assertFalse(target.contains("entity.tamed"));
        assertFalse(target.getBoolean("entity.unsupported.Options.CanJoinRaid", true));
        assertTrue(target.getBoolean("entity.unsupported.Options.PatrolLeader"));
        assertTrue(target.getBoolean("entity.unsupported.Options.PatrolSpawnPoint"));
        assertTrue(target.getBoolean("entity.unsupported.Options.Tamed"));
        assertTrue(target.getBoolean("mob.prevent-teleport"));
        assertTrue(target.getBoolean("mob.prevent-pickup"));
        assertTrue(target.getBoolean("mob.prevent-item-use"));
        assertTrue(target.getBoolean("mob.prevent-drowning"));
        assertTrue(target.getBoolean("mob.prevent-fall-damage"));
        assertTrue(target.getBoolean("mob.prevent-fire"));
        assertTrue(target.getBoolean("mob.prevent-projectiles"));
        assertTrue(target.getBoolean("mob.prevent-explosions"));
        assertTrue(target.getBoolean("mob.prevent-block-explosions"));
        assertTrue(target.getBoolean("mob.prevent-entity-explosions"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && "Options.AgeLock".equals(entry.get("source-key"))
                && String.valueOf(entry.get("note")).contains("animal-only AgeLock")));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && "Options.Age".equals(entry.get("source-key"))
                && String.valueOf(entry.get("note")).contains("exact Age ticks")));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && "Options.PatrolLeader".equals(entry.get("source-key"))
                && String.valueOf(entry.get("note")).contains("does not implement Raider")));
    }

    @Test
    @DisplayName("interaction guard options require their runtime entity types and boolean values")
    void mythicPreservesInteractionGuardOptionBoundaries(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/interaction-guard-boundaries.yml", """
                mobs:
                  slime:
                    Type: SLIME
                    Options:
                      PreventSlimeSplit: true
                  silverfish:
                    Type: SILVERFISH
                    Options:
                      PreventBlockInfection: true
                  snow_golem:
                    Type: SNOW_GOLEM
                    Options:
                      PreventSnowFormation: true
                  zombie:
                    Type: ZOMBIE
                    Options:
                      PreventJockeyMounts: true
                      CanPickupItems: false
                  malformed_slime:
                    Type: SLIME
                    Options:
                      PreventSlimeSplit: maybe
                  malformed_silverfish:
                    Type: SILVERFISH
                    Options:
                      PreventBlockInfection: maybe
                  malformed_snow_golem:
                    Type: SNOW_GOLEM
                    Options:
                      PreventSnowFormation: maybe
                  malformed_zombie:
                    Type: ZOMBIE
                    Options:
                      PreventJockeyMounts: maybe
                      CanPickupItems: maybe
                  wrong_type:
                    Type: ARMOR_STAND
                    Options:
                      PreventSlimeSplit: true
                      PreventBlockInfection: true
                      PreventSnowFormation: true
                      PreventJockeyMounts: true
                      CanPickupItems: true
                """);
        var entries = new MythicMobsImporter().convert(file);

        var slime = convertedMob(entries, "slime");
        assertTrue(slime.getBoolean("mob.prevent-slime-split"));
        assertFalse(slime.contains("entity.unsupported.Options.PreventSlimeSplit"));

        var silverfish = convertedMob(entries, "silverfish");
        assertTrue(silverfish.getBoolean("mob.prevent-block-infection"));
        assertFalse(silverfish.contains("entity.unsupported.Options.PreventBlockInfection"));

        var snowGolem = convertedMob(entries, "snow_golem");
        assertTrue(snowGolem.getBoolean("mob.prevent-snow-formation"));
        assertFalse(snowGolem.contains("entity.unsupported.Options.PreventSnowFormation"));

        var zombie = convertedMob(entries, "zombie");
        assertTrue(zombie.getBoolean("mob.prevent-jockey-mounts"));
        assertFalse(zombie.getBoolean("entity.can-pickup-items", true));
        assertFalse(zombie.contains("entity.unsupported.Options.PreventJockeyMounts"));
        assertFalse(zombie.contains("entity.unsupported.Options.CanPickupItems"));

        var malformedSlime = convertedMob(entries, "malformed_slime");
        assertEquals("maybe", malformedSlime.getString(
                "entity.unsupported.Options.PreventSlimeSplit"));
        assertFalse(malformedSlime.contains("mob.prevent-slime-split"));
        var malformedSilverfish = convertedMob(entries, "malformed_silverfish");
        assertEquals("maybe", malformedSilverfish.getString(
                "entity.unsupported.Options.PreventBlockInfection"));
        assertFalse(malformedSilverfish.contains("mob.prevent-block-infection"));
        var malformedSnowGolem = convertedMob(entries, "malformed_snow_golem");
        assertEquals("maybe", malformedSnowGolem.getString(
                "entity.unsupported.Options.PreventSnowFormation"));
        assertFalse(malformedSnowGolem.contains("mob.prevent-snow-formation"));
        var malformedZombie = convertedMob(entries, "malformed_zombie");
        assertEquals("maybe", malformedZombie.getString(
                "entity.unsupported.Options.PreventJockeyMounts"));
        assertEquals("maybe", malformedZombie.getString(
                "entity.unsupported.Options.CanPickupItems"));
        assertFalse(malformedZombie.contains("mob.prevent-jockey-mounts"));
        assertFalse(malformedZombie.contains("entity.can-pickup-items"));

        var wrongType = convertedMob(entries, "wrong_type");
        for (String option : List.of("PreventSlimeSplit", "PreventBlockInfection",
                "PreventSnowFormation", "PreventJockeyMounts", "CanPickupItems")) {
            assertTrue(wrongType.contains("entity.unsupported.Options." + option), option);
        }
        assertFalse(wrongType.contains("mob.prevent-slime-split"));
        assertFalse(wrongType.contains("mob.prevent-block-infection"));
        assertFalse(wrongType.contains("mob.prevent-snow-formation"));
        assertFalse(wrongType.contains("mob.prevent-jockey-mounts"));
        assertFalse(wrongType.contains("entity.can-pickup-items"));
        assertEquals(10, entries.stream().filter(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("target-key")).startsWith("entity.unsupported.Options."))
                .filter(entry -> List.of("Options.PreventSlimeSplit", "Options.PreventBlockInfection",
                        "Options.PreventSnowFormation", "Options.PreventJockeyMounts",
                        "Options.CanPickupItems").contains(entry.get("source-key")))
                .count());
    }

    @Test
    @DisplayName("age options are preserved with a warning for non-Ageable entity types")
    void mythicPreservesAgeOptionsForNonAgeableEntities(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/non-ageable.yml", """
                mobs:
                  Allay:
                    Type: ALLAY
                    Options:
                      Age: -24000
                      AgeLock: true
                      Adult: false
                      Baby: true
                """);

        var entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertFalse(target.contains("entity.age"));
        assertFalse(target.contains("entity.age-lock"));
        assertFalse(target.contains("entity.adult"));
        assertFalse(target.contains("entity.baby"));
        assertEquals(-24000, target.getInt("entity.unsupported.Options.Age"));
        assertTrue(target.getBoolean("entity.unsupported.Options.AgeLock"));
        assertFalse(target.getBoolean("entity.unsupported.Options.Adult"));
        assertTrue(target.getBoolean("entity.unsupported.Options.Baby"));
        assertEquals(4, entries.stream().filter(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && String.valueOf(entry.get("note")).contains("does not implement Bukkit Ageable")
                && String.valueOf(entry.get("target-key")).startsWith("entity.unsupported.Options."))
                .count());
    }

    @Test
    @DisplayName("numeric age and AgeLock are preserved for ageable non-animal mobs")
    void mythicPreservesNumericAgeAndAgeLockForAgeableNonAnimals(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/zombie-age-lock.yml", """
                mobs:
                  BabyHusk:
                    Type: HUSK
                    Options:
                      Baby: true
                      Age: -24000
                      AgeLock: true
                """);

        var entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertTrue(target.getBoolean("entity.baby"));
        assertFalse(target.contains("entity.age"));
        assertFalse(target.contains("entity.age-lock"));
        assertEquals(-24000, target.getInt("entity.unsupported.Options.Age"));
        assertTrue(target.getBoolean("entity.unsupported.Options.AgeLock"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && "entity.unsupported.Options.AgeLock".equals(entry.get("target-key"))
                && String.valueOf(entry.get("note")).contains("animal-only AgeLock")));
    }

    @Test
    @DisplayName("PreventConversion merges only zombie conversion reasons")
    void mythicMapsPreventConversionNarrowly(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/prevent-conversion.yml", """
                mobs:
                  Zombie:
                    Type: ZOMBIE
                    Options:
                      PreventConversion: true
                      PreventTransformationReasons:
                        - SHEARED
                """);
        var entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertTrue(target.getBoolean("mob.prevent-transformation"));
        assertEquals(List.of("SHEARED", "DROWNED", "CURED", "INFECTION"),
                target.getStringList("mob.prevent-transformation-reasons"));
        for (String reason : target.getStringList("mob.prevent-transformation-reasons")) {
            org.bukkit.event.entity.EntityTransformEvent.TransformReason.valueOf(reason);
        }
        assertFalse(target.contains("mob.unsupported.Options"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "mob.prevent-transformation-reasons".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("Enderman HeldBlock option maps to runtime BlockData configuration")
    void mythicMapsEndermanHeldBlock(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/enderman-held-block.yml", """
                mobs:
                  ObsidianCarrier:
                    Type: ENDERMAN
                    Options:
                      HeldBlock: minecraft:obsidian
                """);
        YamlConfiguration target = converted(file);

        assertEquals("minecraft:obsidian", target.getString("entity.held-block"));
        assertFalse(target.contains("mob.unsupported.Options"));
    }

    @Test
    @DisplayName("official Armor Stand option names map to runtime fields")
    void mythicMapsOfficialArmorStandOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/armor-stand-options.yml", """
                mobs:
                  Dummy:
                    Type: ARMOR_STAND
                    Options:
                      HasArms: true
                      HasBasePlate: false
                      Marker: true
                      Small: true
                      CanMove: false
                      CanTick: false
                      HasGravity: false
                      ItemBody: DIAMOND_CHESTPLATE
                      ItemFeet: DIAMOND_BOOTS
                      ItemHand: DIAMOND_SWORD
                      ItemOffhand: SHIELD
                      ItemHead: DIAMOND_HELMET
                      ItemLegs: DIAMOND_LEGGINGS
                """);
        YamlConfiguration target = converted(file);

        assertTrue(target.getBoolean("entity.armor-stand-arms"));
        assertFalse(target.getBoolean("entity.armor-stand-baseplate", true));
        assertTrue(target.getBoolean("entity.armor-stand-marker"));
        assertTrue(target.getBoolean("entity.armor-stand-small"));
        assertFalse(target.getBoolean("entity.armor-stand-can-move", true));
        assertFalse(target.getBoolean("entity.armor-stand-can-tick", true));
        assertFalse(target.getBoolean("entity.gravity", true));
        assertEquals("DIAMOND_CHESTPLATE", target.getString("entity.armor-stand-item-body"));
        assertEquals("DIAMOND_BOOTS", target.getString("entity.armor-stand-item-feet"));
        assertEquals("DIAMOND_SWORD", target.getString("entity.armor-stand-item-hand"));
        assertEquals("SHIELD", target.getString("entity.armor-stand-item-offhand"));
        assertEquals("DIAMOND_HELMET", target.getString("entity.armor-stand-item-head"));
        assertEquals("DIAMOND_LEGGINGS", target.getString("entity.armor-stand-item-legs"));
        assertFalse(target.contains("mob.unsupported.Options"));
    }

    @Test
    @DisplayName("Mythic Interactable option maps explicitly and applies its documented default")
    void mythicMapsInteractableAndDefault(@TempDir Path root) throws Exception {
        Path explicitFile = write(root, "Mobs/interactable-explicit.yml", """
                mobs:
                  Clickable:
                    Type: ZOMBIE
                    Options:
                      Interactable: true
                """);
        YamlConfiguration explicit = converted(explicitFile);
        assertTrue(explicit.getBoolean("mob.interactable"));
        assertFalse(explicit.contains("mob.unsupported.Options"));

        Path defaultFile = write(root, "Mobs/interactable-default.yml", """
                mobs:
                  MythicDefault:
                    Type: ZOMBIE
                    Options: {}
                """);
        YamlConfiguration withDefault = converted(defaultFile);
        assertFalse(withDefault.getBoolean("mob.interactable", true));
    }

    @Test
    @DisplayName("PreventConversion maps by entity family and preserves unsupported families")
    void mythicMapsSkeletonPreventConversionAndPreservesOtherFamilies(@TempDir Path root) throws Exception {
        Path skeletonFile = write(root, "Mobs/skeleton-conversion.yml", """
                mobs:
                  FrozenSkeleton:
                    Type: SKELETON
                    Options:
                      PreventConversion: true
                """);
        YamlConfiguration skeleton = converted(skeletonFile);
        assertEquals(List.of("FROZEN"), skeleton.getStringList("mob.prevent-transformation-reasons"));
        assertFalse(skeleton.contains("mob.unsupported.Options"));

        Path pigFile = write(root, "Mobs/other-conversion.yml", """
                mobs:
                  StablePig:
                    Type: PIG
                    Options:
                      PreventConversion: true
                """);
        YamlConfiguration pig = converted(pigFile);
        assertTrue(pig.getBoolean("mob.unsupported.Options.PreventConversion"));
        assertTrue(new MythicMobsImporter().convert(pigFile).stream().anyMatch(entry ->
                "unsupported".equals(entry.get("status"))
                        && "mob.unsupported.Options".equals(entry.get("target-key"))));

        Path invalidFile = write(root, "Mobs/invalid-conversion.yml", """
                mobs:
                  MaybeFrozen:
                    Type: SKELETON
                    Options:
                      PreventConversion: maybe
                """);
        var invalidEntries = new MythicMobsImporter().convert(invalidFile);
        YamlConfiguration invalid = converted(invalidFile);
        assertEquals("maybe", invalid.getString("mob.unsupported.Options.PreventConversion"));
        assertFalse(invalid.contains("mob.prevent-transformation-reasons"));
        assertTrue(invalidEntries.stream().anyMatch(entry ->
                "unsupported".equals(entry.get("status"))
                        && "WARN".equals(entry.get("severity"))
                        && "mob.unsupported.Options.PreventConversion".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("fixed and random Mythic Size values map to Paper entity options")
    void mythicMapsFixedEntitySize(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/sizes.yml", """
                mobs:
                  fixed:
                    Type: SLIME
                    Options:
                      Size: 6
                  magma:
                    Type: MAGMA_CUBE
                    Options:
                      Size: 2to5
                  ranged:
                    Type: PHANTOM
                    Options:
                      Size: 1to8
                  invalid:
                    Type: SLIME
                    Options:
                      Size: 0-4
                """);
        var converted = new MythicMobsImporter().convert(file);
        var plans = converted.stream().filter(entry -> entry.containsKey("target-file")).toList();
        var fixed = plans.stream().map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .filter(config -> "mythic_fixed".equals(config.getString("id"))).findFirst().orElseThrow();
        var magma = plans.stream().map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .filter(config -> "mythic_magma".equals(config.getString("id"))).findFirst().orElseThrow();
        var ranged = plans.stream().map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .filter(config -> "mythic_ranged".equals(config.getString("id"))).findFirst().orElseThrow();
        var invalid = plans.stream().map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .filter(config -> "mythic_invalid".equals(config.getString("id"))).findFirst().orElseThrow();

        assertEquals(6, fixed.getInt("entity.size"));
        assertFalse(fixed.contains("mob.unsupported.Options.Size"));
        assertEquals("MAGMA_CUBE", magma.getString("mob.entity-type"));
        assertEquals(2, magma.getInt("entity.size-min"));
        assertEquals(5, magma.getInt("entity.size-max"));
        assertFalse(magma.contains("entity.unsupported.Options.Size"));
        assertEquals(1, ranged.getInt("entity.size-min"));
        assertEquals(8, ranged.getInt("entity.size-max"));
        assertFalse(ranged.contains("entity.unsupported.Options.Size"));
        assertEquals("0-4", invalid.getString("entity.unsupported.Options.Size"));
        assertTrue(converted.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "entity.unsupported.Options.Size".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("Neutral entity Angry option maps to an entity-specific runtime setting")
    void mythicMapsAngry(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/angry.yml", """
                mobs:
                  wolf:
                    Type: WOLF
                    Options:
                      Angry: true
                  piglin:
                    Type: ZOMBIFIED_PIGLIN
                    Options:
                      Angry: false
                  zombie:
                    Type: ZOMBIE
                    Options:
                      Angry: true
                """);
        var plans = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file")).toList();
        var wolf = plans.stream().map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .filter(config -> "mythic_wolf".equals(config.getString("id"))).findFirst().orElseThrow();
        var piglin = plans.stream().map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .filter(config -> "mythic_piglin".equals(config.getString("id"))).findFirst().orElseThrow();
        var zombie = plans.stream().map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .filter(config -> "mythic_zombie".equals(config.getString("id"))).findFirst().orElseThrow();

        assertTrue(wolf.getBoolean("entity.angry"));
        assertFalse(piglin.getBoolean("entity.angry"));
        assertTrue(zombie.getBoolean("entity.unsupported.Options.Angry"));
        assertFalse(zombie.contains("entity.angry"));
        assertFalse(wolf.contains("mob.unsupported.Options"));
        assertFalse(piglin.contains("mob.unsupported.Options"));
    }

    @Test
    @DisplayName("entity-scoped flags map only to compatible Mythic entity types")
    void mythicMapsEntityScopedFlags(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/entity-flags.yml", """
                mobs:
                  wolf:
                    Type: WOLF
                    Options:
                      Tamed: true
                  raider:
                    Type: PILLAGER
                    Options:
                      CanJoinRaid: false
                      PatrolLeader: true
                      PatrolSpawnPoint: true
                  husk:
                    Type: HUSK
                    Options:
                      CanJoinRaid: false
                      PatrolLeader: false
                      PatrolSpawnPoint: true
                      Tamed: true
                """);
        var configs = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> entry.containsKey("target-file"))
                .map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .collect(java.util.stream.Collectors.toMap(config -> config.getString("id"),
                        java.util.function.Function.identity()));

        var wolf = configs.get("mythic_wolf");
        var raider = configs.get("mythic_raider");
        var husk = configs.get("mythic_husk");
        assertTrue(wolf.getBoolean("entity.tamed"));
        assertFalse(wolf.contains("entity.unsupported.Options.Tamed"));
        assertFalse(raider.getBoolean("entity.can-join-raid", true));
        assertTrue(raider.getBoolean("entity.patrol-leader"));
        assertTrue(raider.getBoolean("entity.patrol-spawn-point"));
        assertFalse(raider.contains("entity.unsupported.Options"));
        assertFalse(husk.contains("entity.can-join-raid"));
        assertFalse(husk.contains("entity.patrol-leader"));
        assertFalse(husk.contains("entity.patrol-spawn-point"));
        assertFalse(husk.contains("entity.tamed"));
        assertTrue(husk.contains("entity.unsupported.Options.CanJoinRaid"));
        assertFalse(husk.getBoolean("entity.unsupported.Options.CanJoinRaid", true));
        assertFalse(husk.getBoolean("entity.unsupported.Options.PatrolLeader", true));
        assertTrue(husk.getBoolean("entity.unsupported.Options.PatrolSpawnPoint"));
        assertTrue(husk.getBoolean("entity.unsupported.Options.Tamed"));
    }

    @Test
    @DisplayName("Mythic Bee anger maps as ticks and unsupported values remain visible")
    void mythicMapsBeeAnger(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/bee-anger.yml", """
                mobs:
                  bee:
                    Type: BEE
                    Options:
                      Anger: 1200
                  calm_bee:
                    Type: BEE
                    Options:
                      Anger: 0
                  wrong_entity:
                    Type: ZOMBIE
                    Options:
                      Anger: 2400
                  invalid_bee:
                    Type: BEE
                    Options:
                      Anger: -1
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plans = entries.stream().filter(entry -> entry.containsKey("target-file"))
                .map(entry -> YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(String.valueOf(entry.get("content")))))
                .collect(java.util.stream.Collectors.toMap(config -> config.getString("id"),
                        java.util.function.Function.identity()));
        var bee = plans.get("mythic_bee");
        var calmBee = plans.get("mythic_calm_bee");
        var wrongEntity = plans.get("mythic_wrong_entity");
        var invalidBee = plans.get("mythic_invalid_bee");

        assertEquals(1200, bee.getInt("entity.bee-anger"));
        assertEquals(0, calmBee.getInt("entity.bee-anger", -1));
        assertEquals(2400, wrongEntity.getInt("entity.unsupported.Options.Anger"));
        assertEquals(-1, invalidBee.getInt("entity.unsupported.Options.Anger"));
        assertFalse(wrongEntity.contains("entity.bee-anger"));
        assertFalse(invalidBee.contains("entity.bee-anger"));
        assertFalse(wrongEntity.contains("mob.unsupported.Options.Anger"));
        assertEquals(2, entries.stream().filter(entry -> "unsupported".equals(entry.get("status")))
                .filter(entry -> "Options.Anger".equals(entry.get("source-key"))).count());
    }

    @Test
    @DisplayName("Creeper-specific options map including PreventSuicide")
    void mythicMapsCreeperOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/creeper-options.yml", """
                mobs:
                  creeper:
                    Type: CREEPER
                    Options:
                      ExplosionRadius: 5
                      FuseTicks: 60
                      SuperCharged: true
                      PreventSuicide: true
                """);
        var entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertEquals("CREEPER", target.getString("mob.entity-type"));
        assertEquals(5, target.getInt("entity.creeper-explosion-radius"));
        assertEquals(60, target.getInt("entity.creeper-fuse-ticks"));
        assertTrue(target.getBoolean("entity.creeper-super-charged"));
        assertTrue(target.getBoolean("mob.prevent-suicide"));
        assertFalse(target.contains("mob.unsupported.Options.PreventSuicide"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "mob.prevent-suicide".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("entity-specific options preserve incompatible types and malformed values")
    void mythicPreservesEntityScopedOptionBoundaries(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/entity-option-boundaries.yml", """
                mobs:
                  horse:
                    Type: HORSE
                    Options:
                      HorseColor: GRAY
                      HorseStyle: WHITE_DOTS
                      Domestication: 5
                      MaxDomestication: 20
                      JumpStrength: 0.8
                      Saddled: true
                      HorseArmor: DIAMOND_HORSE_ARMOR
                  wrong:
                    Type: ZOMBIE
                    Options:
                      HeldBlock: minecraft:obsidian
                      Size: 5
                      ExplosionRadius: 5
                      HasNectar: true
                      HorseColor: WHITE
                      CatType: SIAMESE
                      SheepColor: RED
                      ArmorStandArms: true
                      BlockData: minecraft:sand
                  invalid_creeper:
                    Type: CREEPER
                    Options:
                      ExplosionRadius: not-a-number
                      FuseTicks: -1
                      SuperCharged: maybe
                  invalid_horse:
                    Type: HORSE
                    Options:
                      HorseColor: PAISLEY
                      JumpStrength: 3.5
                      Domestication: -1
                  invalid_cat:
                    Type: CAT
                    Options:
                      CatType: NOT_A_CAT
                  invalid_sheep:
                    Type: SHEEP
                    Options:
                      SheepColor: NOT_A_COLOR
                  invalid_falling:
                    Type: FALLING_BLOCK
                    Options:
                      BlockData: []
                  invalid_falling_name:
                    Type: FALLING_BLOCK
                    Options:
                      FallingBlockData: NOT_A_BLOCK
                  invalid_stand:
                    Type: ARMOR_STAND
                    Options:
                      HasArms: maybe
                  invalid_raider:
                    Type: PILLAGER
                    Options:
                      CanJoinRaid: maybe
                """);
        var entries = new MythicMobsImporter().convert(file);

        var horse = convertedMob(entries, "horse");
        assertEquals("GRAY", horse.getString("entity.horse-color"));
        assertEquals("WHITE_DOTS", horse.getString("entity.horse-style"));
        assertEquals(5, horse.getInt("entity.horse-domestication"));
        assertEquals(20, horse.getInt("entity.horse-max-domestication"));
        assertEquals(0.8, horse.getDouble("entity.horse-jump-strength"), 1e-9);
        assertTrue(horse.getBoolean("entity.saddled"));
        assertEquals("DIAMOND_HORSE_ARMOR", horse.getString("entity.horse-armor"));

        var wrong = convertedMob(entries, "wrong");
        for (String mapped : List.of("entity.held-block", "entity.size",
                "entity.creeper-explosion-radius", "entity.has-nectar", "entity.horse-color",
                "entity.cat-type", "entity.sheep-color", "entity.armor-stand-arms",
                "entity.falling-block-data")) {
            assertFalse(wrong.contains(mapped), mapped);
        }
        assertEquals("minecraft:obsidian", wrong.getString("entity.unsupported.Options.HeldBlock"));
        assertEquals(5, wrong.getInt("entity.unsupported.Options.Size"));
        assertEquals(5, wrong.getInt("entity.unsupported.Options.ExplosionRadius"));
        assertTrue(wrong.getBoolean("entity.unsupported.Options.HasNectar"));
        assertEquals("WHITE", wrong.getString("entity.unsupported.Options.HorseColor"));
        assertEquals("SIAMESE", wrong.getString("entity.unsupported.Options.CatType"));
        assertEquals("RED", wrong.getString("entity.unsupported.Options.SheepColor"));
        assertTrue(wrong.getBoolean("entity.unsupported.Options.ArmorStandArms"));
        assertEquals("minecraft:sand", wrong.getString("entity.unsupported.Options.BlockData"));

        var invalidCreeper = convertedMob(entries, "invalid_creeper");
        assertEquals("not-a-number", invalidCreeper.getString(
                "entity.unsupported.Options.ExplosionRadius"));
        assertEquals(-1, invalidCreeper.getInt("entity.unsupported.Options.FuseTicks"));
        assertEquals("maybe", invalidCreeper.getString("entity.unsupported.Options.SuperCharged"));
        assertFalse(invalidCreeper.contains("entity.creeper-explosion-radius"));
        assertFalse(invalidCreeper.contains("entity.creeper-fuse-ticks"));
        assertFalse(invalidCreeper.contains("entity.creeper-super-charged"));

        var invalidHorse = convertedMob(entries, "invalid_horse");
        assertEquals("PAISLEY", invalidHorse.getString("entity.unsupported.Options.HorseColor"));
        assertEquals(3.5, invalidHorse.getDouble("entity.unsupported.Options.JumpStrength"), 1e-9);
        assertEquals(-1, invalidHorse.getInt("entity.unsupported.Options.Domestication"));
        assertFalse(invalidHorse.contains("entity.horse-color"));
        assertFalse(invalidHorse.contains("entity.horse-jump-strength"));
        assertFalse(invalidHorse.contains("entity.horse-domestication"));

        assertEquals("NOT_A_CAT", convertedMob(entries, "invalid_cat")
                .getString("entity.unsupported.Options.CatType"));
        assertEquals("NOT_A_COLOR", convertedMob(entries, "invalid_sheep")
                .getString("entity.unsupported.Options.SheepColor"));
        assertTrue(convertedMob(entries, "invalid_falling")
                .contains("entity.unsupported.Options.BlockData"));
        var invalidFallingName = convertedMob(entries, "invalid_falling_name");
        assertEquals("NOT_A_BLOCK", invalidFallingName.getString(
                "entity.unsupported.Options.FallingBlockData"));
        assertFalse(invalidFallingName.contains("entity.falling-block-data"));
        assertEquals("maybe", convertedMob(entries, "invalid_stand")
                .getString("entity.unsupported.Options.HasArms"));
        assertEquals("maybe", convertedMob(entries, "invalid_raider")
                .getString("entity.unsupported.Options.CanJoinRaid"));
        assertTrue(entries.stream().filter(entry -> "unsupported".equals(entry.get("status")))
                .anyMatch(entry -> "WARN".equals(entry.get("severity"))
                        && "Options.HorseColor".equals(entry.get("source-key"))));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "Options.FallingBlockData".equals(entry.get("source-key"))
                && "WARN".equals(entry.get("severity"))));
    }

    @Test
    @DisplayName("Mythic Cat options map type, sitting, and collar color")
    void mythicMapsCatOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/cat-options.yml", """
                mobs:
                  cat:
                    Type: CAT
                    Options:
                      CatType: SIAMESE
                      Sitting: true
                      CollarColor: BLUE
                """);
        var entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertEquals("CAT", target.getString("mob.entity-type"));
        assertEquals("SIAMESE", target.getString("entity.cat-type"));
        assertTrue(target.getBoolean("entity.cat-sitting"));
        assertEquals("BLUE", target.getString("entity.cat-collar-color"));
        assertFalse(target.contains("mob.unsupported.Options"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "entity.cat-type".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("Mythic RabbitType maps only to rabbits and preserves invalid values")
    void mythicMapsRabbitTypeWithEntityBoundary(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/rabbit-options.yml", """
                mobs:
                  rabbit:
                    Type: RABBIT
                    Options:
                      RabbitType: SALT_AND_PEPPER
                  wrong:
                    Type: ZOMBIE
                    Options:
                      RabbitType: BLACK
                  invalid:
                    Type: RABBIT
                    Options:
                      RabbitType: NOT_A_RABBIT
                """);
        var entries = new MythicMobsImporter().convert(file);

        var rabbit = convertedMob(entries, "rabbit");
        var wrong = convertedMob(entries, "wrong");
        var invalid = convertedMob(entries, "invalid");

        assertEquals("SALT_AND_PEPPER", rabbit.getString("entity.rabbit-type"));
        assertFalse(rabbit.contains("entity.unsupported.Options.RabbitType"));

        assertEquals("BLACK", wrong.getString("entity.unsupported.Options.RabbitType"));
        assertFalse(wrong.contains("entity.rabbit-type"));

        assertEquals("NOT_A_RABBIT", invalid.getString("entity.unsupported.Options.RabbitType"));
        assertFalse(invalid.contains("entity.rabbit-type"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && "Options.RabbitType".equals(entry.get("source-key"))
                && "entity.unsupported.Options.RabbitType".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("Mythic IsKillerBunny and Rabbit Angry alias map safely")
    void mythicMapsKillerBunnyAliasesAndPreservesAmbiguity(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/killer-bunny-options.yml", """
                mobs:
                  explicit:
                    Type: RABBIT
                    Options:
                      IsKillerBunny: true
                  angry_alias:
                    Type: RABBIT
                    Options:
                      Angry: true
                  regular:
                    Type: RABBIT
                    Options:
                      RabbitType: SALT_AND_PEPPER
                      IsKillerBunny: false
                  wolf:
                    Type: WOLF
                    Options:
                      Angry: true
                  wrong_type:
                    Type: ZOMBIE
                    Options:
                      IsKillerBunny: true
                  invalid:
                    Type: RABBIT
                    Options:
                      IsKillerBunny: maybe
                  ambiguous:
                    Type: RABBIT
                    Options:
                      Angry: true
                      IsKillerBunny: false
                """);
        var entries = new MythicMobsImporter().convert(file);

        assertTrue(convertedMob(entries, "explicit").getBoolean("entity.rabbit-killer-bunny"));
        assertTrue(convertedMob(entries, "angry_alias").getBoolean("entity.rabbit-killer-bunny"));
        var regular = convertedMob(entries, "regular");
        assertFalse(regular.getBoolean("entity.rabbit-killer-bunny"));
        assertEquals("SALT_AND_PEPPER", regular.getString("entity.rabbit-type"));
        assertTrue(convertedMob(entries, "wolf").getBoolean("entity.angry"));

        var wrongType = convertedMob(entries, "wrong_type");
        assertTrue(wrongType.getBoolean("entity.unsupported.Options.IsKillerBunny"));
        assertFalse(wrongType.contains("entity.rabbit-killer-bunny"));
        var invalid = convertedMob(entries, "invalid");
        assertEquals("maybe", invalid.getString("entity.unsupported.Options.IsKillerBunny"));
        assertFalse(invalid.contains("entity.rabbit-killer-bunny"));
        var ambiguous = convertedMob(entries, "ambiguous");
        assertTrue(ambiguous.getBoolean("entity.unsupported.Options.Angry"));
        assertFalse(ambiguous.getBoolean("entity.unsupported.Options.IsKillerBunny"));
        assertFalse(ambiguous.contains("entity.rabbit-killer-bunny"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "Options.Angry".equals(entry.get("source-key"))
                && "entity.unsupported.Options.Angry".equals(entry.get("target-key"))
                && "true".equals(entry.get("value"))));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "Options.IsKillerBunny".equals(entry.get("source-key"))
                && "entity.unsupported.Options.IsKillerBunny".equals(entry.get("target-key"))
                && "false".equals(entry.get("value"))));
    }

    @Test
    @DisplayName("Mythic FoxType maps only to foxes and preserves invalid values")
    void mythicMapsFoxTypeWithEntityBoundary(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/fox-options.yml", """
                mobs:
                  fox:
                    Type: FOX
                    Options:
                      FoxType: SNOW
                  wrong:
                    Type: ZOMBIE
                    Options:
                      FoxType: RED
                  invalid:
                    Type: FOX
                    Options:
                      FoxType: NOT_A_FOX
                """);
        var entries = new MythicMobsImporter().convert(file);

        var fox = convertedMob(entries, "fox");
        var wrong = convertedMob(entries, "wrong");
        var invalid = convertedMob(entries, "invalid");

        assertEquals("SNOW", fox.getString("entity.fox-type"));
        assertFalse(fox.contains("entity.unsupported.Options.FoxType"));

        assertEquals("RED", wrong.getString("entity.unsupported.Options.FoxType"));
        assertFalse(wrong.contains("entity.fox-type"));

        assertEquals("NOT_A_FOX", invalid.getString("entity.unsupported.Options.FoxType"));
        assertFalse(invalid.contains("entity.fox-type"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))
                && "Options.FoxType".equals(entry.get("source-key"))
                && "entity.unsupported.Options.FoxType".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("Mythic Sheep options map color and sheared state")
    void mythicMapsSheepOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/sheep-options.yml", """
                mobs:
                  sheep:
                    Type: SHEEP
                    Options:
                      SheepColor: RED
                      Sheared: true
                  control:
                    Type: SHEEP
                    Options:
                      SheepColor: WHITE
                      Sheared: false
                """);
        var entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertEquals("SHEEP", target.getString("mob.entity-type"));
        assertEquals("RED", target.getString("entity.sheep-color"));
        assertTrue(target.getBoolean("entity.sheep-sheared"));
        assertFalse(target.contains("mob.unsupported.Options"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "entity.sheep-color".equals(entry.get("target-key"))));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "entity.sheep-sheared".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("Bee state and stinger options map to entity and mob runtime fields")
    void mythicMapsBeeOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/bee-options.yml", """
                mobs:
                  bee:
                    Type: BEE
                    Options:
                      HasNectar: true
                      HasStung: false
                      PreventStingerLoss: true
                """);
        var entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertTrue(target.getBoolean("entity.has-nectar"));
        assertFalse(target.getBoolean("entity.has-stung"));
        assertTrue(target.getBoolean("mob.prevent-stinger-loss"));
        assertFalse(target.contains("mob.unsupported.Options"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "entity.has-nectar".equals(entry.get("target-key"))));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "entity.has-stung".equals(entry.get("target-key"))));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "mob.prevent-stinger-loss".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("MythicMobs Persistent option enables restart-safe despawn mode")
    void mythicMapsPersistentOption(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/persistent.yml", """
                mobs:
                  Boss:
                    Options:
                      Persistent: true
                """);
        YamlConfiguration target = converted(file);

        assertTrue(target.getBoolean("persistent"));
        assertEquals("PERSISTENT", target.getString("mob.despawn"));
    }

    @Test
    @DisplayName("MythicMobs inline equipment metadata becomes structured item data")
    void mythicMapsInlineEquipment(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/equipment-inline.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - 'DIAMOND_SWORD{name="&aBlade";lore="&7Sharp|&8Heavy";model=42;enchants=SHARPNESS:5} HAND'
                """);
        YamlConfiguration target = converted(file);

        assertEquals("DIAMOND_SWORD", target.getString("entity.items.main-hand.item"));
        assertEquals("&aBlade", target.getString("entity.items.main-hand.display-name"));
        assertEquals(List.of("&7Sharp", "&8Heavy"),
                target.getStringList("entity.items.main-hand.lore"));
        assertEquals(42, target.getInt("entity.items.main-hand.custom-model-data"));
        assertEquals(5, target.getInt("entity.item-enchantments.main-hand.SHARPNESS"));
    }

    @Test
    @DisplayName("unsupported equipment entries are retained beside mapped entries")
    void mythicPreservesUnsupportedEquipment(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/equipment-mixed.yml", """
                mobs:
                  Boss:
                    Equipment:
                      - DIAMOND_SWORD HAND
                      - DIAMOND_SWORD BACK
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertEquals("DIAMOND_SWORD", target.getString("entity.items.main-hand"));
        assertEquals(List.of("DIAMOND_SWORD BACK"), target.getStringList("entity.unsupported.Equipment"));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "entity.unsupported.Equipment".equals(e.get("target-key"))));
    }

    @Test
    @DisplayName("AI goal and target selectors are mapped into profile-consumable fields")
    void mythicMapsAiSelectors(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/ai.yml", """
                mobs:
                  Guard:
                    AIGoalSelectors:
                      - 0 clear
                      - 1 meleeattack
                    AIGoalTargets:
                      - 0 clear
                      - 1 players
                    AITargetSelectors:
                      - 0 clear
                      - 1 players
                      - 2 nearestConditionalTarget{conditions=[isPlayer;health-above 0.5]}
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("0 clear", "1 meleeattack"), target.getStringList("ai.goals"));
        assertEquals(List.of("0 clear", "1 players",
                "2 nearestConditionalTarget{conditions=[isPlayer;health-above 0.5]}"),
                target.getStringList("ai.targets"));
        assertFalse(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && (String.valueOf(e.get("source-key")).contains("AIGoalSelectors")
                || String.valueOf(e.get("source-key")).contains("AIGoalTargets")
                || String.valueOf(e.get("source-key")).contains("AITargetSelectors"))));
    }

    @Test
    @DisplayName("AIPathfindingMalus is preserved with an explicit public-API warning")
    void mythicPreservesAiPathfindingMalus(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/ai-malus.yml", """
                mobs:
                  DuneStalker:
                    AIPathfindingMalus:
                      WATER: -1
                      FIRE: 0
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals(-1, target.getInt("mob.unsupported.AIPathfindingMalus.WATER"));
        assertEquals(0, target.getInt("mob.unsupported.AIPathfindingMalus.FIRE"));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "mob.unsupported.AIPathfindingMalus".equals(e.get("target-key"))
                && "WARN".equals(e.get("severity"))));
    }





    @Test
    @DisplayName("MythicMobs spawners convert to spawners.yml")
    void mythicSpawnersConvert(@TempDir Path root) throws Exception {
        Path file = write(root, "Spawners/arena.yml", """
                spawners:
                  arena:
                    Mob: crystal_boss
                    World: world
                    X: 10
                    Y: 64
                    Z: -4
                    Radius: 8
                    Interval: 200
                    MaxMobs: 2
                    Enabled: true
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        Map<String, Object> plan = entries.stream()
                .filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals("crystal_boss", target.getString("spawners.arena.mob"));
        assertEquals("world", target.getString("spawners.arena.world"));
        assertEquals(2, target.getInt("spawners.arena.max-alive"));
    }

    @Test
    @DisplayName("delimited Mob Option reason values become runtime lists")
    void mythicMapsDelimitedOptionReasons(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/delimited-reasons.yml", """
                mobs:
                  Boss:
                    Options:
                      PreventTransformationReasons: DROWNED, CURED|INFECTION
                      PreventFireCauses: FIRE, FIRE_TICK
                """);
        YamlConfiguration target = converted(file);
        assertEquals(List.of("DROWNED", "CURED", "INFECTION"),
                target.getStringList("mob.prevent-transformation-reasons"));
        assertEquals(List.of("FIRE", "FIRE_TICK"),
                target.getStringList("mob.prevent-fire-causes"));
    }


    @Test
    @DisplayName("MythicMobs spawner random pool is preserved")
    void mythicSpawnerRandomPoolConverts(@TempDir Path root) throws Exception {
        Path file = write(root, "Spawners/random.yml", """
                spawners:
                  arena:
                    Mob: fallback
                    X: 0
                    Z: 0
                    RandomMobs:
                      zombie: 1
                      skeleton: 3
                """);
        Map<String, Object> plan = new MythicMobsImporter().convert(file).stream()
                .filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(1, target.getInt("spawners.arena.random-mobs.zombie"));
        assertEquals(3, target.getInt("spawners.arena.random-mobs.skeleton"));
    }

    @Test
    @DisplayName("Mythic Spawner references resolve to imported Mob IDs")
    void mythicSpawnerReferencesResolveToImportedMobIds(@TempDir Path root) throws Exception {
        write(root, "Mobs/arena.yml", """
                mobs:
                  Guard:
                    Type: ZOMBIE
                  Boss:
                    Type: SKELETON
                """);
        Path file = write(root, "Spawners/arena.yml", """
                spawners:
                  arena:
                    Mob: GUARD
                    RandomMobs:
                      guard: 1
                      Boss: 3
                      external_mob: 2
                """);

        var converted = new MythicMobsImporter().convert(file, root);
        var plan = converted.stream().filter(entry -> "spawners.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("mythic_guard", target.getString("spawners.arena.mob"));
        assertEquals(1, target.getInt("spawners.arena.random-mobs.mythic_guard"));
        assertEquals(3, target.getInt("spawners.arena.random-mobs.mythic_boss"));
        assertEquals(2, target.getInt("spawners.arena.random-mobs.external_mob"));
        assertTrue(converted.stream().anyMatch(entry ->
                "unsupported".equals(entry.get("status"))
                        && "Spawners.arena.RandomMobs.external_mob".equals(entry.get("source-key"))));
    }

    @Test
    @DisplayName("MythicMobs spawner lifecycle and AI fields are preserved")
    void mythicSpawnerExtendedFieldsConvert(@TempDir Path root) throws Exception {
        Path file = write(root, "Spawners/extended.yml", """
                spawners:
                  arena:
                    Mob: boss
                    X: 0
                    Z: 0
                    Chance: 0.25
                    GroupSize: 3-5
                    MaxMobs: 2
                    MaxSpawns: 10
                    YRange: 8
                    AIProfile: elite
                    Conditions:
                      - night
                      - "!raining"
                """);
        Map<String, Object> plan = new MythicMobsImporter().convert(file).stream()
                .filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(10, target.getInt("spawners.arena.max-spawns"));
        assertEquals(0.25, target.getDouble("spawners.arena.chance"), 1e-9);
        assertEquals("3-5", target.getString("spawners.arena.group-size"));
        assertEquals(2, target.getInt("spawners.arena.max-alive"));
        assertEquals(8, target.getInt("spawners.arena.y-range"));
        assertEquals("elite", target.getString("spawners.arena.ai-profile"));
        assertEquals(List.of("night", "!raining"), target.getStringList("spawners.arena.conditions"));
    }

    @Test
    void mythicSpawnerNullConditionsFailClosedThroughMerges(@TempDir Path root) throws Exception {
        for (String prefix : List.of("spawners:\n", "")) {
            String indent = prefix.isEmpty() ? "" : "  ";
            Path file = write(root, "Spawners/nulls.yml", prefix + ("""
                    base: &base {Mob: boss, Conditions: null}
                    merged: {<<: *base}
                    override: {<<: *base, Conditions: []}
                    alias: {Mob: boss, spawn-conditions: null}
                    absent: {Mob: boss}
                    """).lines().map(line -> indent + line + "\n").collect(java.util.stream.Collectors.joining()));
            var entries = new MythicMobsImporter().convert(file);
            var plan = entries.stream().filter(e -> "spawners.yml".equals(e.get("target-file")))
                    .findFirst().orElseThrow();
            var target = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
            for (String name : List.of("base", "merged", "alias")) {
                assertEquals(List.of(""), target.getStringList("spawners." + name + ".conditions"), name);
            }
            assertFalse(target.contains("spawners.override.conditions"));
            assertFalse(target.contains("spawners.absent.conditions"));
            assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))));
        }
    }

    @Test
    @DisplayName("MythicMobs spawner conditions translate Mythic leaves and preserve native predicates")
    void mythicSpawnerConditionsTranslate(@TempDir Path root) throws Exception {
        Path file = write(root, "Spawners/conditions.yml", """
                spawners:
                  arena:
                    Mob: boss
                    Conditions:
                      - isRaining
                      - "!isThundering"
                      - "?health{<50}"
                      - lunar-phase-is=FULL
                """);
        Map<String, Object> plan = new MythicMobsImporter().convert(file).stream()
                .filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals(List.of("raining", "!thundering", "health <50", "lunar-phase-is=FULL"),
                target.getStringList("spawners.arena.conditions"));
    }

    @Test
    @DisplayName("MythicMobs scalar spawner conditions preserve comma arguments")
    void mythicScalarSpawnerConditionsTranslate(@TempDir Path root) throws Exception {
        Path file = write(root, "Spawners/scalar-conditions.yml", """
                spawners:
                  arena:
                    Mob: boss
                    Conditions: "isRaining, ?health{<50}, time-between=0,12000"
                """);
        Map<String, Object> plan = new MythicMobsImporter().convert(file).stream()
                .filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("raining", "health <50", "time-between=0,12000"),
                target.getStringList("spawners.arena.conditions"));
    }


    @Test
    @DisplayName("MythicMobs spawner regions are nested under spawners")
    void mythicSpawnerRegionsUseRuntimeInputShape(@TempDir Path root) throws Exception {
        Path file = write(root, "Spawners/regions.yml", """
                regions:
                  arena:
                    World: world
                    X1: -10
                    Y1: 0
                    Z1: -20
                    X2: 10
                    Y2: 100
                    Z2: 20
                spawners:
                  arena:
                    Mob: crystal_boss
                    Region: arena
                """);
        Map<String, Object> plan = new MythicMobsImporter().convert(file).stream()
                .filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertEquals("world", target.getString("spawners.regions.arena.world"));
        assertEquals(-10, target.getInt("spawners.regions.arena.x1"));
        assertEquals(20, target.getInt("spawners.regions.arena.z2"));
        assertEquals("crystal_boss", target.getString("spawners.arena.mob"));
    }

    @Test
    @DisplayName("MythicMobs item files are converted into structured item data")
    void mythicMapsItems(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/example.yml", """
                example_item:
                  Id: DIAMOND_SWORD
                  Display: "&aBlade"
                  Lore:
                    - "&7Sharp"
                  CustomModelData: 42
                  Amount: 2
                  Options:
                    Unbreakable: true
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals("DIAMOND_SWORD", target.getString("items.example_item.id"));
        assertEquals("&aBlade", target.getString("items.example_item.display-name"));
        assertEquals(42, target.getInt("items.example_item.custom-model-data"));
        assertTrue(target.getBoolean("items.example_item.unbreakable"));
    }

    @Test
    @DisplayName("Mythic item names and skull metadata map separately and invalid model data is preserved")
    void mythicMapsItemNameAndSkullMetadata(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/metadata.yml", """
                named_head:
                  Id: PLAYER_HEAD
                  Display: "&aDisplay"
                  ItemName: "&bExact name"
                  SkullOwner: Notch
                  CustomModelData: 42
                invalid_model:
                  Id: DIAMOND
                  CustomModelData: not-a-number
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        YamlConfiguration target = converted(file);

        assertEquals("&aDisplay", target.getString("items.named_head.display-name"));
        assertEquals("&bExact name", target.getString("items.named_head.item-name"));
        assertEquals("Notch", target.getString("items.named_head.skull-owner"));
        assertEquals(42, target.getInt("items.named_head.custom-model-data"));
        assertEquals("not-a-number", target.getString("items.invalid_model.unsupported.custom-model-data"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "items.invalid_model.unsupported.custom-model-data".equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("MythicMobs item options preserve durability and adventure restrictions")
    void mythicMapsItemOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/options.yml", """
                example_item:
                  Id: OAK_SLAB
                  Durability: 3
                  MaxDurability: 80
                  CanPlaceOn:
                    - stone
                  CanBreak:
                    - dirt
                  BlockStates: "waterlogged=true"
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(3, target.getInt("items.example_item.durability"));
        assertEquals(80, target.getInt("items.example_item.max-durability"));
        assertEquals(List.of("stone"), target.getStringList("items.example_item.can-place-on"));
        assertEquals("waterlogged=true", target.getString("items.example_item.block-states"));
     }

    @Test
    @DisplayName("MythicMobs item visual metadata is mapped from top-level and Options aliases")
    void mythicMapsItemVisualMetadataAliases(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/visual.yml", """
                visual:
                  Id: FIREWORK_ROCKET
                  FireworkEffects:
                    - type: STAR
                      flicker: true
                  Options:
                    Unbreakable: true
                    BannerPatterns:
                      - RED STRIPE
                    PotionEffects:
                      - effect: SPEED
                        duration: 40
                        amplifier: 1
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        new MythicMobsImporter().convert(file).stream()
                                .filter(e -> e.containsKey("target-file")).findFirst().orElseThrow()
                                .get("content"))));

        assertTrue(target.getBoolean("items.visual.unbreakable"));
        assertTrue(target.contains("items.visual.firework-effects"));
        assertTrue(target.contains("items.visual.banner-layers"));
        assertTrue(target.contains("items.visual.potion-effects"));
    }

    @Test
    @DisplayName("MythicMobs universal item options map to runtime metadata and preserve unsupported options")
    void mythicMapsUniversalItemOptions(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/universal-options.yml", """
                items:
                  durable:
                    Id: DIAMOND_SWORD
                    RepairCost: 7
                    Options:
                      Repairable: false
                      Glint: true
                      PreventStacking: true
                      StackSize: 16
                      GenerateUUID: true
                      GenerateTimestamp: true
                      FireResistant: true
                      ItemModel: example:relic
                      DamageType: spear
                      HideFlags: true
                """);
        var entries = new MythicMobsImporter().convert(file);
        var plan = entries.stream().filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));

        assertFalse(target.getBoolean("items.durable.repairable", true));
        assertEquals(7, target.getInt("items.durable.repair-cost"));
        assertTrue(target.getBoolean("items.durable.glint"));
        assertTrue(target.getBoolean("items.durable.prevent-stacking"));
        assertEquals(16, target.getInt("items.durable.stack-size"));
        assertTrue(target.getBoolean("items.durable.generate-uuid"));
        assertTrue(target.getBoolean("items.durable.generate-timestamp"));
        assertTrue(target.getBoolean("items.durable.fire-resistant"));
        assertTrue(target.getStringList("items.durable.item-flags").contains("HIDE_ENCHANTS"));
        assertTrue(target.getStringList("items.durable.item-flags").contains("HIDE_ITEM_SPECIFICS"));
        assertEquals("example:relic", target.getString("items.durable.unsupported.options.ItemModel"));
        assertEquals("spear", target.getString("items.durable.unsupported.options.DamageType"));
        assertFalse(target.contains("items.durable.unsupported.options.HideFlags"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "items.durable.unsupported.options".equals(entry.get("target-key"))));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && String.valueOf(entry.get("note")).contains("version-gated adapter")));
    }

    @Test
    @DisplayName("Mythic item metadata clear/remove aliases are preserved")
    void mythicMapsItemMetadataClearAliases(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/clear.yml", """
                clear_item:
                  Id: DIAMOND_SWORD
                  ClearPotionEffects: true
                  RemovePotionEffects:
                    - SPEED
                  PotionEffectsMode: append
                  ClearBannerLayers: true
                  BannerLayersMode: append
                  ClearFireworkEffects: true
                  FireworkEffectsMode: append
                  ClearPersistentData: true
                  RemovePersistentData:
                    - old_key
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        plan(new MythicMobsImporter().convert(file)).get().get("content"))));
        assertTrue(target.getBoolean("items.clear_item.clear-potion-effects"));
        assertEquals(List.of("SPEED"), target.getStringList("items.clear_item.remove-potion-effects"));
        assertEquals("append", target.getString("items.clear_item.potion-effects-mode"));
        assertTrue(target.getBoolean("items.clear_item.clear-banner-layers"));
        assertEquals("append", target.getString("items.clear_item.banner-layers-mode"));
        assertTrue(target.getBoolean("items.clear_item.clear-firework-effects"));
        assertEquals("append", target.getString("items.clear_item.firework-effects-mode"));
        assertTrue(target.getBoolean("items.clear_item.clear-persistent-data"));
        assertEquals(List.of("old_key"), target.getStringList("items.clear_item.remove-persistent-data"));
    }

    @Test
    @DisplayName("MythicMobs item templates and attribute modifier aliases are preserved")
    void mythicMapsItemTemplateAliases(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/templates.yml", """
                items:
                  base:
                    Id: DIAMOND_SWORD
                  enchanted:
                    Template: base
                    AttributeModifiers:
                      mainhand:
                        attack:
                          attribute: ATTACK_DAMAGE
                          amount: 4
                """);
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(
                        new MythicMobsImporter().convert(file).stream()
                                .filter(e -> e.containsKey("target-file")).findFirst().orElseThrow()
                                .get("content"))));

        assertEquals("base", target.getString("items.enchanted.template"));
        assertEquals("ATTACK_DAMAGE",
                target.getString("items.enchanted.attributes.mainhand.attack.attribute"));
    }

    @Test
    void invalidItemTemplateIsPreservedWithWarning(@TempDir Path root) throws Exception {
        Path file = write(root, "Items/invalid-template.yml", "broken:\n  Id: DIAMOND\n  Template: 42\n");
        var entries = new MythicMobsImporter().convert(file);
        var target = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(
                entries.stream().filter(e -> "items.yml".equals(e.get("target-file")))
                        .findFirst().orElseThrow().get("content"))));
        assertFalse(target.contains("items.broken.template"));
        assertEquals(42, target.getInt("items.broken.unsupported.template"));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "Items.broken.Template".equals(e.get("source-key"))));
    }

    @Test
    @DisplayName("Mythic mob templates retain direct lineage and report unresolved inheritance")
    void mythicMobTemplateLineageIsRetained(@TempDir Path root) throws Exception {
        Path file = write(root, "Mobs/templates.yml", """
                mobs:
                  armored:
                    Type: ZOMBIE
                    Template: [Undead, Template.Armored]
                  invalid:
                    Type: SKELETON
                    Template: [42]
                """);
        List<Map<String, Object>> entries = new MythicMobsImporter().convert(file);
        Map<String, Object> plan = entries.stream()
                .filter(entry -> "mobs/mythic_armored.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new StringReader(String.valueOf(plan.get("content"))));

        assertEquals(List.of("mythic_undead", "mythic_armored"), target.getStringList("mob.templates"));
        assertTrue(target.contains("mob.unsupported.TemplateInheritance.Template"));
        assertTrue(target.getString("mob.unsupported.TemplateInheritance.error").contains("does not resolve"));
        Map<String, Object> invalidPlan = entries.stream()
                .filter(entry -> "mobs/mythic_invalid.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration invalid = YamlConfiguration.loadConfiguration(
                new StringReader(String.valueOf(invalidPlan.get("content"))));
        assertEquals(List.of(42), invalid.getIntegerList("mob.unsupported.Template"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "mob.templates".equals(entry.get("target-key"))));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "mob.unsupported.TemplateInheritance"
                .equals(entry.get("target-key"))));
    }

    @Test
    @DisplayName("Mythic mob template fields and Exclude apply before ordinary import mapping")
    void mythicMobTemplateFieldsAreInheritedAndExcluded(@TempDir Path root) throws Exception {
        write(root, "Mobs/templates/base.yml", """
                Base:
                  Type: ZOMBIE
                  Health: 20
                  Damage: 3
                  Display: '&aBase'
                  Options:
                    PreventOtherDrops: true
                    NoAI: true
                """);
        write(root, "Mobs/templates/mid.yml", """
                Mid:
                  Template: Base
                  Health: 24
                  Options:
                    PreventSunburn: true
                """);
        Path child = write(root, "Mobs/children/child.yml", """
                Child:
                  Type: SKELETON
                  Template: Mid
                  Exclude: [Display, Options]
                  Damage: 9
                  Options:
                    NoAI: false
                """);

        List<Map<String, Object>> entries = new MythicMobsImporter().convert(child);
        Map<String, Object> plan = entries.stream()
                .filter(entry -> "mobs/mythic_child.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        YamlConfiguration target = YamlConfiguration.loadConfiguration(
                new StringReader(String.valueOf(plan.get("content"))));

        assertEquals("SKELETON", target.getString("mob.entity-type"));
        assertEquals(24, target.getInt("mob.health"));
        assertEquals(9, target.getInt("mob.attack-damage"));
        assertFalse(target.contains("mob.display-name"));
        assertFalse(target.contains("entity.prevent-other-drops"));
        assertFalse(target.contains("entity.prevent-sunburn"));
        assertFalse(target.getBoolean("entity.no-ai", true));
        assertEquals(List.of("mythic_mid"), target.getStringList("mob.templates"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "mob.template-inheritance".equals(entry.get("target-key"))));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "mob.template-exclude".equals(entry.get("target-key"))));
    }
}
