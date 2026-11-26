package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextTargeterExtensionsImportTest {
    @TempDir Path root;

    @Test
    void canonicalizesNewAliasesWhileRetainingNamePlaceholdersLocationOptionsAndGuards() throws Exception {
        var input = new YamlConfiguration();
        input.set("context_targeters.Skills", List.of(
                "damage{amount=1} @specificplayer{n=<skill.var.Player>}",
                "damage{amount=1} @lastAttacker{targetPlayers=false}",
                "damage{amount=1} @lastInteract",
                "setchunkforceloaded{loaded=true} @LOT{y=3;limit=1;sort=NEAREST}",
                "setchunkforceloaded{loaded=true} @locationOfTarget{conditions=[ - outside true ]}"));
        Path file = root.resolve("Skills/context-targeters.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, input.saveToString());
        var plans = new MythicMobsImporter().convert(file);
        var plan = plans.stream().filter(item -> item.containsKey("content")).findFirst().orElseThrow();
        var result = YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
        List<String> actions = result.getStringList("skills.context_targeters.on-decision");
        assertEquals(5, actions.size(), result.saveToString());
        assertTrue(actions.get(0).endsWith("@playerbyname{n=<skill.var.Player>}"));
        assertTrue(actions.get(1).endsWith("@interactionlastattacker{targetPlayers=false}"));
        assertTrue(actions.get(2).endsWith("@interactionlastinteract"));
        assertTrue(actions.get(3).endsWith("@locationsoftargets{y=3;limit=1;sort=NEAREST}"));
        assertTrue(actions.get(4).contains("@locationsoftargets{conditions="));
        assertTrue(result.getStringList("skills.context_targeters.unsupported").isEmpty());
        assertTrue(plans.stream().noneMatch(item -> "WARN".equals(item.get("severity"))));
    }
}
