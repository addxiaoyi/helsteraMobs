package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PathfindingMalusImportTest {
    @Test
    void mapsNativeListToRuntimeCosts(@TempDir Path root) throws Exception {
        var entries = convert(root, """
                - WATER -1
                - DANGER_FIRE 0
                - DAMAGE_FIRE 12.5
                - WATER 4
                """);
        var target = target(entries);
        assertEquals(4, target.getDouble("ai.pathfinding-malus.WATER"));
        assertEquals(0, target.getDouble("ai.pathfinding-malus.DANGER_FIRE"));
        assertEquals(12.5, target.getDouble("ai.pathfinding-malus.DAMAGE_FIRE"));
        assertFalse(target.contains("mob.unsupported.AIPathfindingMalus"));
        assertTrue(entries.stream().anyMatch(entry -> "mapped".equals(entry.get("status"))
                && "ai.pathfinding-malus".equals(entry.get("target-key"))));
    }

    @Test
    void preservesInvalidListEntriesWhileMappingValidOnes(@TempDir Path root) throws Exception {
        var entries = convert(root, """
                - WATER -1
                - FIRE 0
                - LAVA NaN
                - RAIL 1e100
                - DANGER_FIRE invalid
                """);
        var target = target(entries);
        assertEquals(-1, target.getDouble("ai.pathfinding-malus.WATER"));
        assertEquals(1, target.getConfigurationSection("ai.pathfinding-malus").getKeys(false).size());
        assertEquals(List.of("WATER -1", "FIRE 0", "LAVA NaN", "RAIL 1e100", "DANGER_FIRE invalid"),
                target.getStringList("mob.unsupported.AIPathfindingMalus"));
        assertTrue(entries.stream().anyMatch(entry -> "unsupported".equals(entry.get("status"))
                && "WARN".equals(entry.get("severity"))));
    }

    @Test
    void preservesUnsupportedMapWithoutGuessingFireAlias(@TempDir Path root) throws Exception {
        var target = target(convert(root, """
                WATER: -1
                FIRE: 0
                """));
        assertFalse(target.contains("ai.pathfinding-malus"));
        assertEquals(-1, target.getInt("mob.unsupported.AIPathfindingMalus.WATER"));
        assertEquals(0, target.getInt("mob.unsupported.AIPathfindingMalus.FIRE"));
    }

    private List<Map<String, Object>> convert(Path root, String malus) throws Exception {
        Path file = root.resolve("Mobs/malus.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "MalusMob:\n  Type: ZOMBIE\n  AIPathfindingMalus:\n" + malus.indent(4));
        return new MythicMobsImporter().convert(file);
    }

    private YamlConfiguration target(List<Map<String, Object>> entries) {
        var plan = entries.stream().filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
    }
}
