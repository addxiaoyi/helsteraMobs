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

class NumericMechanicMigrationTest {
    @TempDir Path root;

    private List<Map<String, Object>> convert(String folder, List<String> skills) throws Exception {
        Path file = Files.createDirectories(root.resolve(folder)).resolve("numeric.yml");
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
    void reorderedOmittedMixedAndDynamicArgumentsKeepFieldIdentity() throws Exception {
        var entries = convert("Skills", List.of("knockback{vertical=0.5;power=2} @Target",
                "knockback{vertical=0.5;delay=0} @Target", "pull{power=2}", "damagemelee{r=4}",
                "forcepull{yv=0.2}", "forcepull", "knockback{2;y=0.5} @Target",
                "damage{tags=FIRE;a=<skill.var.amount>;repeat=2;repeatinterval=1} @Target",
                "heal{health=3} @Target", "healpercent", "damagepercent", "modifyspeed",
                "setfire{time=60} @Target", "setmaxhealth{health=30} @Target",
                "damage{a=2;rawtags=\"custom tag\"} @Target"));
        assertEquals(List.of("knockback-targets 2 0.5 @target",
                "knockback-targets{delay=0} 1 0.5 @target", "pull 6 2", "damage-melee 3 4",
                "force-pull 1 0.2", "force-pull 1", "knockback-targets 2 0.5 @target",
                "damage-target{repeat=2;repeatinterval=1} <skill.var.amount> tags=FIRE @target",
                "heal-targets 3 @target", "heal-percent 0", "damage-percent 0", "modify-speed 0",
                "set-fire 60 @target", "set-max-health 30 @target",
                "damage-target 2 \"rawtags=custom tag\" @target"),
                output(entries).getStringList("skills.probe.on-decision"));
        assertFalse(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))), entries.toString());
    }

    @Test
    void invalidNumericSourcesArePreservedWithWarningsForSkillsAndMobs() throws Exception {
        var invalid = List.of("knockback{power=1;power=2}", "knockback{power=1;strength=2}",
                "pull{power=NaN}", "pull{r=Infinity}", "damage{amount=1e400}", "heal{a=}",
                "sethealth{unknown=2}", "forcepull{velocity=2;yv=bad}", "knockback{power=2;;y=1}",
                "damage{tags=FIRE;tag=ICE}", "pull 1 2 3", "pull @Target 2",
                "damage{amount=1} @Target garbage", "knockback{power=2;1}");
        for (String folder : List.of("Skills", "Mobs")) {
            var entries = convert(folder, invalid);
            var yaml = output(entries);
            String prefix = folder.equals("Skills") ? "skills.probe" : "mob";
            assertEquals(invalid, yaml.getStringList(prefix +
                    (folder.equals("Skills") ? ".unsupported" : ".unsupported.Skills")));
            assertTrue(yaml.getStringList(prefix + ".on-decision").isEmpty());
            assertEquals(invalid.size(), entries.stream().filter(e -> "WARN".equals(e.get("severity"))).count());
        }
    }
}
