package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SetSpeedMigrationTest {
    @TempDir Path root;

    @Test
    void officialFieldsDefaultsAndExecutionMetadataSurviveImport() throws Exception {
        Path file = Files.createDirectories(root.resolve("Skills")).resolve("speed.yml");
        Files.writeString(file, """
                Test:
                  Skills:
                    - 'setspeed @Trigger'
                    - 'setspeed{m=2;t=FLYING;delay=2;repeat=1;repeatinterval=3} @Target'
                    - 'set-speed{value=-1;type=unknown} @Self'
                """);
        var entries = new MythicMobsImporter().convert(file, root);
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("set-speed type=WALK speed=1.0 @trigger",
                "set-speed{delay=2;repeat=1;repeatinterval=3} type=FLY speed=2.0 @target",
                "set-speed type=WALK speed=-1.0 @self"), yaml.getStringList("skills.test.on-decision"));
        assertFalse(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))), entries.toString());
    }

    @Test
    void invalidFieldsAndUnsupportedPlaceholdersArePreservedForMobsAndSkills() throws Exception {
        var sources = List.of("setspeed{m=1;speed=2}", "setspeed{m=}", "setspeed{m=NaN}",
                "setspeed{type=FLY;t=WALK}", "setspeed{m=<skill.var.speed>}",
                "setspeed{duration=20}", "setspeed{m=1;;t=FLY}", "setspeed @Target 2");
        for (String folder : List.of("Skills", "Mobs")) {
            Path file = Files.createDirectories(root.resolve(folder)).resolve("invalid.yml");
            String text = "Test:\n" + (folder.equals("Mobs") ? "  Type: COW\n" : "") + "  Skills:\n";
            for (String source : sources) text += "    - '" + source + "'\n";
            Files.writeString(file, text);
            var entries = new MythicMobsImporter().convert(file, root);
            var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
            var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
            assertEquals(sources, yaml.getStringList(folder.equals("Mobs") ? "mob.unsupported.Skills" : "skills.test.unsupported"));
            assertEquals(sources.size(), entries.stream().filter(e -> "WARN".equals(e.get("severity"))).count());
        }
    }
}
