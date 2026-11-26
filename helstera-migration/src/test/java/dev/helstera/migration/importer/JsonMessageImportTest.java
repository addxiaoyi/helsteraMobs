package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JsonMessageImportTest {
    @Test void aliasesPreserveJsonAndSchedulingLiterally(@TempDir Path root) throws Exception {
        String body = "{m='[{\"text\":\"<target.name>: a;b=x, c=y\\nC:\\\\path\","
                + "\"clickEvent\":{\"action\":\"suggest_command\",\"value\":\"/help\"}}]';"
                + "delay=2;repeat=3;repeatinterval=4}";
        for (String alias : List.of("messagejson", "jsonmessage", "jmsg", "jm", "message-json")) {
            var output = convert(root, alias + body + " @trigger");
            assertEquals(List.of("message-json" + body + " @trigger"), output.getStringList("skills.json.on-decision"));
            assertFalse(output.contains("skills.json.unsupported"));
        }
    }

    @Test void invalidSchedulingPreservesOriginal(@TempDir Path root) throws Exception {
        String original = "jm{m='{\"text\":\"hi\"}';delay=invalid} @trigger";
        var output = convert(root, original);
        assertTrue(output.getStringList("skills.json.on-decision").isEmpty());
        assertEquals(List.of(original), output.getStringList("skills.json.unsupported"));
    }

    private YamlConfiguration convert(Path root, String line) throws Exception {
        Path file = root.resolve("Skills/json-message.yml");
        Files.createDirectories(file.getParent());
        var input = new YamlConfiguration();
        input.set("json.Skills", List.of(line));
        input.save(file.toFile());
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> "skills.yml".equals(entry.get("target-file"))).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
    }
}
