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

class EntityInteractionImportTest {
    @TempDir Path root;

    private YamlConfiguration convert(String sourceName, List<String> lines) throws Exception {
        Path file = root.resolve("Skills/" + sourceName + ".yml");
        Files.createDirectories(file.getParent());
        var input = new YamlConfiguration();
        input.set("Interactions.Skills", lines);
        Files.writeString(file, input.saveToString());
        List<Map<String, Object>> plans = new MythicMobsImporter().convert(file);
        var plan = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
    }

    @Test void aliasesNestedSelectorsAndExecutionAttributesSurviveImport() throws Exception {
        var output = convert("valid", List.of("stopusingitem @trigger", "releaseitem{delay=2} @self",
                "matchrotation{to=@origin} @trigger",
                "matchrot{l=@location{x=1;y=2;z=3;yaw=450;pitch=-120};repeat=2;repeatinterval=3} @trigger",
                "GoatRam @trigger"));
        assertEquals(List.of("stop-using-item @trigger", "stop-using-item{delay=2} @self",
                "match-rotation{target=\"@origin\"} @trigger",
                "match-rotation{target=\"@location{x=1;y=2;z=3;yaw=450;pitch=-120}\";repeat=2;repeatinterval=3} @trigger",
                "goat-ram @trigger"), output.getStringList("skills.interactions.on-decision"));
        assertTrue(output.getStringList("skills.interactions.unsupported").isEmpty());
    }

    @Test void malformedArgumentsAndUnknownSelectorsRemainUnsupported() throws Exception {
        var lines = List.of("releaseitem{amount=1} @trigger", "GoatRam{damage=5} @trigger",
                "matchrot @trigger", "matchrot{to=@unknown} @trigger",
                "matchrot{to=@origin;target=@trigger} @trigger", "releaseitem @unknown",
                "matchrot{to=@origin} @self > @unknown", "GoatRam{delay=bad} @trigger");
        var output = convert("invalid", lines);
        assertEquals(lines, output.getStringList("skills.interactions.unsupported"));
        assertTrue(output.getStringList("skills.interactions.on-decision").isEmpty());
    }
}
