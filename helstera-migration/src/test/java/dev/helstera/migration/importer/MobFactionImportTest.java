package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MobFactionImportTest {
    @TempDir Path root;

    private YamlConfiguration convert(String fields) throws Exception {
        Path file = Files.createDirectories(root.resolve("Mobs")).resolve("faction.yml");
        Files.writeString(file, "Guard:\n  Type: COW\n" + fields);
        var entries = new MythicMobsImporter().convert(file, root);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
    }

    @Test void factionSurvivesWithAiSelectorsAndMobTriggers() throws Exception {
        var yaml = convert("  Faction: ' Guards '\n  AIGoalSelectors: [clear]\n  Skills:\n    - 'message{m=removed} @self ~onDespawn'\n");
        assertEquals("Guards", yaml.getString("mob.faction"));
        assertTrue(yaml.contains("ai.goals"));
        assertTrue(yaml.contains("mob.triggers.on-despawn"));
    }

    @Test void ambiguousAndStructuredFactionsRemainVisible() throws Exception {
        for (String fields : List.of("  Faction: [guards]\n", "  Faction: 42\n", "  Faction: guards\n  faction: undead\n")) {
            var yaml = convert(fields);
            assertFalse(yaml.contains("mob.faction"));
            assertTrue(yaml.contains("mob.unsupported.Faction"));
        }
    }
}
