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

class ScoreboardImportTest {
    @Test void everyScoreboardAliasPreservesNamedArgumentsAndScope(@TempDir Path root) throws Exception {
        var aliases = Map.of("sgs", "set-global-score", "sms", "set-mob-score", "sts", "set-target-score",
                "mgs", "modify-global-score", "mms", "modify-mob-score", "mts", "modify-target-score",
                "modifyScore", "modify-score");
        for (var alias : aliases.entrySet()) {
            String body = "{objective=Points;obj=Ignored;value=2;VALUE=3;action=MULTIPLY;delay=2}";
            String line = alias.getKey() + body + " @trigger";
            var output = convert(root, List.of(line));
            assertEquals(List.of(alias.getValue() + body + " @trigger"), output.getStringList("skills.write.on-decision"));
            assertFalse(output.contains("skills.unsupported"));
        }
    }

    @Test void rawQuotesEmptyFieldsAndDynamicExpressionsAreNotChanged(@TempDir Path root) throws Exception {
        var output = convert(root, List.of("setGlobalScore{objective=;value=\"2\"}",
                "modifyTargetScore{obj=<target.name>;v=<target.var.Score>} @trigger",
                "modifyScore{entry=;name=ignored;value=;v=8;action=UNSET}"));
        assertEquals(List.of("set-global-score{objective=;value=\"2\"}",
                "modify-target-score{obj=<target.name>;v=<target.var.Score>} @trigger",
                "modify-score{entry=;name=ignored;value=;v=8;action=UNSET}"), output.getStringList("skills.write.on-decision"));
    }

    @Test void legacyInstanceScoresRetainTheirSeparateActions(@TempDir Path root) throws Exception {
        var output = convert(root, List.of("setScore{objective=local;value=4}", "addScore{objective=local;value=2}"));
        assertEquals(List.of("score-set local 4", "score-add local 2"), output.getStringList("skills.write.on-decision"));
    }

    private YamlConfiguration convert(Path root, List<String> lines) throws Exception {
        Path file = root.resolve("Skills/scoreboard.yml"); Files.createDirectories(file.getParent());
        var input = new YamlConfiguration(); input.set("write.Skills", lines); input.save(file.toFile());
        var plans = new MythicMobsImporter().convert(file);
        Map<String, Object> plan = plans.stream().filter(entry -> "skills.yml".equals(entry.get("target-file"))).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
    }
}
