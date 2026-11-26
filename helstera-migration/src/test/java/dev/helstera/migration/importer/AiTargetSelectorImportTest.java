package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AiTargetSelectorImportTest {
    @TempDir Path root;
    @Test void nestedRuleAndStaticQuotesSurviveMigrationWithExecutionSuffix() throws Exception {
        var source=root.resolve("Skills/targets.yml"); Files.createDirectories(source.getParent());
        var yaml=new YamlConfiguration(); yaml.set("Targets.Skills",List.of(
                "runaitargetselector{target=2 nearestIf{c=[ - isplayer true ];i=0};delay=2} @trigger",
                "aitarget{s=\"3 players\"}","aitarget{goal=players}"));
        Files.writeString(source,yaml.saveToString());
        var plan=new MythicMobsImporter().convert(source).stream().filter(p->"skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        var result=YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
        assertEquals(List.of("run-ai-target-selector{target=2 nearestIf{c=[ - isplayer true ];i=0};delay=2} @trigger",
                "run-ai-target-selector{s=\"3 players\"}","run-ai-target-selector{goal=players}"),result.getStringList("skills.targets.on-decision"));
    }
    @Test void importedMobTargetListUsesNativeSchedulerAndPreservesSourceOrder() throws Exception {
        var source=root.resolve("Mobs/targets.yml"); Files.createDirectories(source.getParent());
        Files.writeString(source,"Guard:\n  Type: ZOMBIE\n  AITargetSelectors:\n  - 5 players\n  - 0 clear\n  - villagers\n  - ' 1 monsters'\n  - ''\n");
        var plans=new MythicMobsImporter().convert(source);
        var plan=plans.stream().filter(p->String.valueOf(p.get("target-file")).startsWith("mobs/")).findFirst().orElseThrow();
        var result=YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
        assertTrue(result.getBoolean("ai.native-targets"));
        assertEquals(List.of("5 players","0 clear","villagers"," 1 monsters",""),result.getStringList("ai.targets"));
    }
}
