package dev.helstera.migration;

import dev.helstera.migration.importer.MythicDropTableProjection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MigrationServiceImplTest {
    @Test
    void resolvesNegatedRegionReferencesAndPreservesUnknownRegions() {
        var region = Map.<String, Object>of("status", "mapped", "source-regions", Map.of("arena_a", "Arena-A"));
        var spawner = new java.util.LinkedHashMap<String, Object>();
        spawner.put("status", "mapped");
        spawner.put("target-file", "spawners.yml");
        spawner.put("content", "spawners:\n  gate:\n    conditions: ['!region=Arena-A', 'region=external-region']\n");
        MigrationServiceImpl.resolveSpawnerRegionReferences(List.of(region, spawner));
        var yaml = YamlConfiguration.loadConfiguration(new StringReader((String) spawner.get("content")));
        assertEquals(List.of("!region=arena_a", "region=external-region"),
                yaml.getStringList("spawners.gate.conditions"));
    }

    @Test
    void reportsSpawnerPoolCollisionAfterResolvingMobId() {
        var mob = Map.<String, Object>of("status", "mapped", "source-mob-id", "Boss-A",
                "target-mob-id", "mythic_boss_a");
        var spawner = new java.util.LinkedHashMap<String, Object>();
        spawner.put("status", "mapped");
        spawner.put("target-file", "spawners.yml");
        spawner.put("content", "spawners:\n  arena:\n    mob: Boss-A\n"
                + "    random-mobs: {Boss-A: 1, mythic_boss_a: 2}\n");
        var diagnostics = MigrationServiceImpl.resolveSpawnerMobReferences(List.of(mob, spawner));
        assertEquals(true, diagnostics.stream().anyMatch(e -> "duplicate-spawner-pool-id".equals(e.get("conflict-type"))));
    }

    @Test
    void replacingSpawnerAndRegionKeepsUnrelatedDefinitions() {
        var target = YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  BOSS: {mob: old, radius: 20}
                  other: {mob: keep}
                  regions:
                    ARENA: {world: old, x1: 5}
                    other: {world: keep}
                """));
        var incoming = YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  boss: {mob: new}
                  regions:
                    arena: {world: new}
                """));
        MigrationServiceImpl.clearDefinitions(incoming, target, "spawners.regions", null);
        MigrationServiceImpl.clearDefinitions(incoming, target, "spawners", "regions");
        MigrationServiceImpl.mergeYaml(incoming, target, "");
        assertFalse(target.contains("spawners.BOSS"));
        assertFalse(target.contains("spawners.regions.ARENA"));
        assertEquals("new", target.getString("spawners.boss.mob"));
        assertEquals("new", target.getString("spawners.regions.arena.world"));
        assertEquals("keep", target.getString("spawners.other.mob"));
        assertEquals("keep", target.getString("spawners.regions.other.world"));
    }

    @Test
    void detectsDuplicateImportedLootTablesAcrossPlansIgnoringCase() {
        var entries = List.<Map<String, Object>>of(
                Map.of("status", "mapped", "target-file", "loot.yml", "file", "mobs/a.yml",
                        "content", "tables:\n  boss_drops: {entries: []}\n"),
                Map.of("status", "mapped", "target-file", "loot.yml", "file", "drops/b.yml",
                        "content", "tables:\n  BOSS_DROPS: {entries: []}\n"),
                Map.of("status", "mapped", "target-file", "loot.yml", "file", "drops/b.yml",
                        "content", "tables:\n  boss_drops: {entries: []}\n"));

        var conflicts = MigrationServiceImpl.lootTableConflicts(entries);
        assertEquals(2, conflicts.size());
        assertEquals("duplicate-loot-table", conflicts.get(0).get("conflict-type"));
        assertEquals("mobs/a.yml", conflicts.get(0).get("previous-file"));
        assertEquals("drops/b.yml", conflicts.get(0).get("file"));
    }

    @Test
    void ignoresDistinctTablesAndDiagnosticEntries() {
        var entries = List.<Map<String, Object>>of(
                Map.of("status", "mapped", "target-file", "loot.yml", "file", "a.yml",
                        "content", "tables:\n  first: {entries: []}\n"),
                Map.of("status", "mapped", "target-file", "loot.yml", "file", "b.yml",
                        "content", "tables:\n  second: {entries: []}\n"),
                Map.of("status", "mapped", "target-file", "loot.yml", "note", "diagnostic"),
                Map.of("status", "unsupported", "target-file", "loot.yml",
                        "content", "tables:\n  first: {entries: []}\n"));

        assertEquals(List.of(), MigrationServiceImpl.lootTableConflicts(entries));
    }

    @Test
    void mergesMultipleLootTablesWithoutDroppingEarlierEntries() {
        var target = YamlConfiguration.loadConfiguration(new StringReader("""
                tables:
                  existing:
                    entries:
                      - item: BREAD
                """));
        var first = YamlConfiguration.loadConfiguration(new StringReader("""
                tables:
                  first:
                    entries:
                      - item: DIAMOND
                        amount-min: 2
                """));
        var second = YamlConfiguration.loadConfiguration(new StringReader("""
                tables:
                  second:
                    entries:
                      - item: EMERALD
                        amount-max: 3
                """));

        MigrationServiceImpl.mergeYaml(first, target, "");
        MigrationServiceImpl.mergeYaml(second, target, "");

        assertEquals("BREAD", target.getMapList("tables.existing.entries").get(0).get("item"));
        assertEquals("DIAMOND", target.getMapList("tables.first.entries").get(0).get("item"));
        assertEquals(2, target.getMapList("tables.first.entries").get(0).get("amount-min"));
        assertEquals("EMERALD", target.getMapList("tables.second.entries").get(0).get("item"));
        assertEquals(3, target.getMapList("tables.second.entries").get(0).get("amount-max"));
    }

    @Test
    void generatedDropProjectionsMergeIntoOneRuntimeLootDocument() {
        var loot = new YamlConfiguration();
        var first = MythicDropTableProjection.project("alpha_drops",
                java.util.List.of("DIAMOND 2-3 25% ?world-is arena"));
        var second = MythicDropTableProjection.project("beta_drops",
                java.util.List.of(java.util.Map.of("table", "nested", "amount", 1,
                        "chance-expression", "luck", "conditions", java.util.List.of("has-target"))));

        MigrationServiceImpl.mergeYaml(first, loot, "");
        MigrationServiceImpl.mergeYaml(second, loot, "");

        assertEquals("DIAMOND", loot.getString("tables.alpha_drops.entries.0.item"));
        assertEquals(0.25, loot.getDouble("tables.alpha_drops.entries.0.chance"), 1e-9);
        assertEquals("world-is arena",
                loot.getStringList("tables.alpha_drops.entries.0.conditions").get(0));
        assertEquals("nested", loot.getString("tables.beta_drops.entries.0.table"));
        assertEquals("luck", loot.getString("tables.beta_drops.entries.0.chance-expression"));
    }

    @Test
    void replacingLootTableRemovesStaleConditionsAndEntries() {
        var loot = YamlConfiguration.loadConfiguration(new StringReader("""
                tables:
                  boss:
                    conditions: [old-condition]
                    entries:
                      '0': {item: BREAD}
                      '1': {item: EMERALD}
                  other:
                    entries: [{item: GOLD_INGOT}]
                """));
        var incoming = MythicDropTableProjection.project("boss", java.util.List.of("DIAMOND 1 1"));
        MigrationServiceImpl.mergeLootYaml(incoming, loot);
        assertEquals("DIAMOND", loot.getString("tables.boss.entries.0.item"));
        assertFalse(loot.contains("tables.boss.entries.1"));
        assertFalse(loot.contains("tables.boss.conditions"));
        assertEquals("GOLD_INGOT", loot.getMapList("tables.other.entries").get(0).get("item"));
    }

    @Test
    void replacingLootTableRemovesExistingCaseVariants() {
        var loot = YamlConfiguration.loadConfiguration(new StringReader("""
                tables:
                  Boss: {entries: [{item: BREAD}]}
                  BOSS: {entries: [{item: EMERALD}]}
                  other: {entries: [{item: GOLD_INGOT}]}
                """));
        var incoming = MythicDropTableProjection.project("boss", List.of("DIAMOND 1 1"));
        MigrationServiceImpl.mergeLootYaml(incoming, loot);
        assertFalse(loot.contains("tables.Boss"));
        assertFalse(loot.contains("tables.BOSS"));
        assertEquals("DIAMOND", loot.getString("tables.boss.entries.0.item"));
        assertEquals("GOLD_INGOT", loot.getMapList("tables.other.entries").get(0).get("item"));
    }

    @Test
    void restoreWrittenFileRestoresBackupWhenTargetPreviouslyExisted() throws Exception {
        Path dataRoot = Files.createTempDirectory("migration-data-");
        Path backupRoot = Files.createTempDirectory("migration-backups-");
        Path target = dataRoot.resolve("loot.yml");
        Path backup = backupRoot.resolve("mythicmobs/backup-1/loot.yml");
        Files.writeString(target, "new-value");
        Files.createDirectories(backup.getParent());
        Files.writeString(backup, "original-value");

        assertEquals(true, MigrationServiceImpl.restoreWrittenFile(
                target, dataRoot, backupRoot, "mythicmobs", "backup-1"));
        assertEquals("original-value", Files.readString(target));
    }
}
