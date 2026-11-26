package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuraLifecycleImportTest {
    @Test
    void lifecycleAliasesChargesAndTimerOptionsArePreserved(@TempDir Path root) throws Exception {
        var result = convert(root, "aura{auraName=shield;cogd=true;cotd=false;cod=true;cocd=false;cot=true;"
                + "cocw=true;coq=false;c=<skill.count>;bt=true;bartimertext=Shield;bartimercolor=custom;bartimerstyle=custom}");
        String mapped = result.getStringList("skills.test.on-decision").getFirst();
        for (String expected : List.of("cancelOnGiveDamage=\"true\"", "cancelOnTakeDamage=\"false\"",
                "cancelOnDeath=\"true\"", "cancelOnCasterDeath=\"false\"", "cancelOnTeleport=\"true\"",
                "cancelOnChangeWorld=\"true\"", "cancelOnQuit=\"false\"", "charges=\"<skill.count>\"",
                "showBarTimer=\"true\"", "barTimerDisplay=\"Shield\"", "barTimerColor=\"custom\"",
                "barTimerStyle=\"custom\"")) assertTrue(mapped.contains(expected), mapped);
        assertTrue(result.getStringList("skills.test.unsupported").isEmpty());
    }

    @Test
    void invalidFlagsAndUnsupportedSkillUseCancellationStayVisible(@TempDir Path root) throws Exception {
        for (String source : List.of("aura{cot=maybe}", "aura{cot=true;cancelonteleport=false}",
                "aura{charges=two}", "aura{cancelonskilluse=true}")) {
            var result = convert(root, source);
            assertEquals(List.of(source), result.getStringList("skills.test.unsupported"));
            assertTrue(result.getStringList("skills.test.on-decision").isEmpty());
        }
    }

    private static YamlConfiguration convert(Path root, String source) throws Exception {
        Path file = root.resolve("Skills/aura.yml");
        Files.createDirectories(file.getParent());
        var input = new YamlConfiguration();
        input.set("test.Skills", List.of(source));
        input.save(file.toFile());
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> "skills.yml".equals(entry.get("target-file"))).findFirst().orElseThrow();
        var output = new YamlConfiguration();
        output.loadFromString(plan.get("content").toString());
        return output;
    }
}
