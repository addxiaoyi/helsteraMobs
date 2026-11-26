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

class EntitySetterImportTest {
    @Test void aliasesDefaultsAndExecutionMetadataSurviveImport(@TempDir Path root) throws Exception {
        List<String> lines = List.of(
                "setMalus{type=WATER;amount=-1;delay=2} @self",
                "setLeasher{r=true} @trigger",
                "setChunkForceLoaded{l=false} @origin",
                "setText{t=\"<red>Hello <target.name></red>\"} @trigger",
                "setDisplayEntityItem @trigger",
                "interactionSize{h=<target.l.y>;w=0} @trigger",
                "setCanJoinRaid @target",
                "setRaiderLeader{leader=false} @self",
                "setRaiderBlock{block=@origin} @self");
        var target = target(convert(root, lines));
        List<String> actions = target.getStringList("mob.triggers.on-spawn.do");
        assertEquals(lines.size(), actions.size(), target.saveToString());
        assertTrue(actions.stream().anyMatch(line -> line.startsWith("set-pathfinding-malus{")
                && line.contains("malus=\"WATER\"") && line.contains("delay=2") && line.endsWith("@self")));
        assertTrue(actions.stream().anyMatch(line -> line.contains("text=\"<red>Hello <target.name></red>\"")));
        assertTrue(actions.stream().anyMatch(line -> line.startsWith("set-display-entity-item{") && line.contains("AIR")));
        assertFalse(target.contains("mob.unsupported.Skills"));
    }

    @Test void invalidSetterArgumentsPreserveOriginalLines(@TempDir Path root) throws Exception {
        List<String> invalid = List.of("setMalus{type=FIRE} @self", "setMalus{value=<caster.level>} @self",
                "setLeasher{r=perhaps} @trigger", "setText{t=a;text=b} @trigger",
                "interactionSize{w=Infinity} @trigger", "setRaiderBlock @self",
                "setRaiderBlock{l=@imaginary} @self", "setCanJoinRaid{unknown=true} @self",
                "setRaiderLeader @imaginary", "setRaiderLeader @self > @imaginary");
        var entries = convert(root, invalid);
        var target = target(entries);
        assertEquals(invalid.stream().map(line -> line + " ~onSpawn").toList(), target.getStringList("mob.unsupported.Skills"));
        assertTrue(target.getStringList("mob.triggers.on-spawn.do").isEmpty());
        assertEquals(invalid.size(), entries.stream().filter(entry -> "unsupported".equals(entry.get("status"))
                && String.valueOf(entry.get("note")).contains("entity setter")).count());
    }

    private List<Map<String, Object>> convert(Path root, List<String> lines) throws Exception {
        Path file = root.resolve("Mobs/setters.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "SetterMob:\n  Type: ZOMBIE\n  Skills:\n" + lines.stream()
                .map(line -> "    - '" + line.replace("'", "''") + " ~onSpawn'\n")
                .collect(java.util.stream.Collectors.joining()));
        return new MythicMobsImporter().convert(file);
    }

    private YamlConfiguration target(List<Map<String, Object>> entries) {
        var plan = entries.stream().filter(entry -> entry.containsKey("target-file")).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
    }
}
