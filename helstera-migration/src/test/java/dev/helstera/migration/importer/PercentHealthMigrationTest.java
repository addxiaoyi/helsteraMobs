package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PercentHealthMigrationTest {
    @TempDir Path root;

    @Test
    void officialFieldsAndExecutionMetadataSurviveMigration() throws Exception {
        Path file = Files.createDirectories(root.resolve("Skills")).resolve("fraction.yml");
        Files.writeString(file, """
                Test:
                  Skills:
                    - 'percentheal{m=0.25;oh=true;ma=0.5;delay=2} @Target'
                    - 'hp{m=<skill.var.fraction>} @Self'
                    - 'percentdamage{c=true;p=0.25;repeat=2;repeatinterval=1} @Target'
                    - 'heal-percent{p=25} @Target'
                    - 'damage-percent{p=25} @Target'
                """);
        var entries = new MythicMobsImporter().convert(file, root);
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("heal-fraction{delay=2} multiplier=0.25 overheal=true maxoverheal=0.5 @target",
                "heal-fraction multiplier=<skill.var.fraction> overheal=false maxoverheal=0 @self",
                "damage-fraction{repeat=2;repeatinterval=1} percent=0.25 currenthealth=true @target",
                "heal-percent 25 @target", "damage-percent 25 @target"), yaml.getStringList("skills.test.on-decision"));
        assertFalse(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))), entries.toString());
    }

    @Test
    void malformedOrUnimplementedFieldsArePreservedForSkillsAndMobs() throws Exception {
        var sources = List.of("hp{m=0.1;multiplier=0.2}", "hp{m=}", "damagepercent{p=NaN}",
                "damagepercent{ch=maybe}", "hp{oh=true;mo=Infinity}", "hp{unknown=1}",
                "damagepercent{ignorearmor=true}", "healpercent{tags=NEWER_VERSION}",
                "hp{m=0.1;;oh=true}", "damagepercent @Target 0.25");
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
