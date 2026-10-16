package dev.helstera.migration;

import dev.helstera.migration.importer.MythicDropTableProjection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MigrationServiceImplTest {
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
