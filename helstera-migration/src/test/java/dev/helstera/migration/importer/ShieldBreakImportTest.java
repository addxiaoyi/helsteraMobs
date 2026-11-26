package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShieldBreakImportTest {
    @TempDir Path root;
    private YamlConfiguration convert(List<String> lines) throws Exception {
        Path file = root.resolve("Skills/shield.yml"); Files.createDirectories(file.getParent());
        var input = new YamlConfiguration(); input.set("Shield.Skills", lines); Files.writeString(file, input.saveToString());
        var plans = new MythicMobsImporter().convert(file);
        var plan = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
    }
    @Test void rawPriorityAndSchedulingArePreserved() throws Exception {
        var output = convert(List.of("shieldbreak @trigger",
                "disableshield{d=5;duration=20;DURATION=30;delay=2;repeat=1} @trigger",
                "shieldbreak{duration=\"<target.l.y>\"} @trigger"));
        assertEquals(List.of("disable-shield @trigger",
                "disable-shield{d=5;duration=20;DURATION=30;delay=2;repeat=1} @trigger",
                "disable-shield{duration=\"<target.l.y>\"} @trigger"), output.getStringList("skills.shield.on-decision"));
    }
    @Test void invalidSelectorsAndExecutionOptionsStayUnsupported() throws Exception {
        var lines = List.of("shieldbreak @unknown", "disableshield @self > @unknown",
                "shieldbreak{delay=bad} @trigger", "shieldbreak 20 @trigger",
                "shieldbreak{d=20} extra @trigger", "shieldbreak duration=20 @trigger",
                "shieldbreak{d=20} @trigger extra");
        var output = convert(lines);
        assertEquals(lines, output.getStringList("skills.shield.unsupported"));
        assertTrue(output.getStringList("skills.shield.on-decision").isEmpty());
    }
}
