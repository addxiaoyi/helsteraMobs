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

class PotionMigrationTest {
    @TempDir Path root;

    private List<Map<String, Object>> convert(String folder, List<String> skills) throws Exception {
        Path file = Files.createDirectories(root.resolve(folder)).resolve("potion.yml");
        String text = folder.equals("Skills") ? "skills:\n  Probe:\n    Skills:\n"
                : "mobs:\n  Probe:\n    Type: ZOMBIE\n    Skills:\n";
        for (String skill : skills) text += "      - '" + skill.replace("'", "''") + "'\n";
        Files.writeString(file, text);
        return new MythicMobsImporter().convert(file, root);
    }

    private YamlConfiguration output(List<Map<String, Object>> entries) {
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
    }

    @Test
    void defaultsAliasesFlagsPlaceholdersAndSuffixesSurviveMigration() throws Exception {
        var entries = convert("Skills", List.of("potion", "potion{delay=0}", "potion SPEED",
                "effect{t=SLOW;d=40;lvl=2;or=true;a=true;p=false;i=false} @Target",
                "potion{type=<skill.var.effect>;d=<skill.var.ticks>;l=<skill.var.level>;repeat=2;repeatinterval=1} @Target",
                "potion{type=\"FAST_DIGGING\";amplifier=2;show-particles=false;show-icon=false}"));
        assertEquals(List.of("effect-targets SLOWNESS 100 0", "effect-targets{delay=0} SLOWNESS 100 0",
                "effect-targets SPEED 60 0", "effect-targets SLOWNESS 40 2 true false false true @target",
                "effect-targets{repeat=2;repeatinterval=1} <skill.var.effect> <skill.var.ticks> <skill.var.level> @target",
                "effect-targets HASTE 100 2 false false false"), output(entries).getStringList("skills.probe.on-decision"));
        assertFalse(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))));
    }

    @Test
    void invalidSourcesStayPreservedAndWarnForSkillsAndMobs() throws Exception {
        List<String> invalid = List.of("potion{type=UNKNOWN}", "potion{type=}", "potion{duration=NaN}",
                "potion{duration=0}", "potion{level=-1}", "potion{force=maybe}",
                "potion{type=SPEED;type=POISON}", "potion{type=SPEED;t=POISON}",
                "potion{duration=20;d=40}", "potion{unknown=true}", "potion{type=SPEED} garbage",
                "potion{type=SPEED;;duration=20}", "potion SPEED 20 1 false true true false extra",
                "potion @Target SPEED", "potion{type=SPEED} @Target garbage", "potion SPEED @Target 20");
        for (String folder : List.of("Skills", "Mobs")) {
            var entries = convert(folder, invalid);
            var yaml = output(entries);
            String prefix = folder.equals("Skills") ? "skills.probe" : "mob";
            assertEquals(invalid, yaml.getStringList(prefix + (folder.equals("Skills") ? ".unsupported" : ".unsupported.Skills")));
            assertTrue(yaml.getStringList(prefix + ".on-decision").isEmpty());
            assertEquals(invalid.size(), entries.stream().filter(e -> "WARN".equals(e.get("severity"))).count());
        }
    }
}
