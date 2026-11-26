package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DisplayTransformationImportTest {
    @TempDir Path root;
    private YamlConfiguration convert(List<String> lines) throws Exception {
        Path file = root.resolve("Skills/display.yml"); Files.createDirectories(file.getParent());
        var input = new YamlConfiguration(); input.set("Display.Skills", lines); Files.writeString(file, input.saveToString());
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
    }
    @Test void aliasesRawVectorsPriorityAndSchedulingArePreserved() throws Exception {
        var output = convert(List.of("displaytransformation @trigger", "settransformation{val=1,2,3;value=4,5,6;delay=2} @self",
                "transformation{type=LEFT_ROTATION;value=\"<target.l.y>,2,3,4\";repeat=1} @trigger"));
        assertEquals(List.of("display-transformation @trigger", "display-transformation{val=1,2,3;value=4,5,6;delay=2} @self",
                "display-transformation{type=LEFT_ROTATION;value=\"<target.l.y>,2,3,4\";repeat=1} @trigger"),
                output.getStringList("skills.display.on-decision"));
    }
    @Test void invalidInputsArePreservedAsUnsupported() throws Exception {
        var lines = List.of("transformation @unknown", "transformation{value=1,2} @trigger",
                "transformation{type=LEFT_ROTATION} @trigger", "transformation 1,2,3 @trigger",
                "transformation{value=1,NaN,3} @trigger", "transformation{value=1,2,3} @trigger extra");
        var output = convert(lines);
        assertEquals(lines, output.getStringList("skills.display.unsupported"));
        assertTrue(output.getStringList("skills.display.on-decision").isEmpty());
    }
}
