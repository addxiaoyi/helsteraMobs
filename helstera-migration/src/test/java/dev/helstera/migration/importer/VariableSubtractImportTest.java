package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VariableSubtractImportTest {
    @Test
    void nativeAliasesAndAllFiveScopesImportWithoutLosingAmounts(@TempDir Path root) throws Exception {
        var sources = List.of("variableSubtract{var=skill.Count;amount=2}",
                "variableSub{var=caster.Count;value=3}", "subtractVariable{var=target.Count;v=4}",
                "subVar{var=world.Count;val=5}", "reduceVariable{var=global.Count;a=6}");
        var result = convert(root, sources);
        assertEquals(List.of("variable-subtract var=skill.Count value=\"2\"",
                "variable-subtract var=caster.Count value=\"3\"", "variable-subtract var=target.Count value=\"4\"",
                "variable-subtract var=world.Count value=\"5\"", "variable-subtract var=global.Count value=\"6\""),
                result.getStringList("skills.test.on-decision"));
        assertTrue(result.getStringList("skills.test.unsupported").isEmpty());
    }

    @Test
    void defaultAliasPriorityAndExplicitScopeOverrideMatchNativeParsing(@TempDir Path root) throws Exception {
        var result = convert(root, List.of("variableSubtract{var=Count}",
                "variableSubtract{var=ignored.Count.Part;scope=target;a=8;amount=7;val=6;value=1;VALUE=2;duration=bad;d=40}",
                "variableSubtract{var=skill.Count;value=<caster.var.Step>;duration=bad;DURATION=1200}",
                "variable-subtract var=Count scope=skill value=3"));
        assertEquals(List.of("variable-subtract var=Count value=\"0\"",
                "variable-subtract var=Count.Part scope=target value=\"2\" duration=0",
                "variable-subtract var=skill.Count value=\"<caster.var.Step>\" duration=1200",
                "variable-subtract var=Count scope=skill value=3"), result.getStringList("skills.test.on-decision"));
    }

    @Test
    void invalidScopeAndOperandArePreserved(@TempDir Path root) throws Exception {
        var sources = List.of("variableSubtract{var=trigger.Count;value=2}",
                "variableSubtract{var=skill.Count;scope=unknown;value=2}",
                "variableSubtract{var=skill.Count;value=not_a_number}");
        var result = convert(root, sources);
        assertEquals(sources, result.getStringList("skills.test.unsupported"));
        assertTrue(result.getStringList("skills.test.on-decision").isEmpty());
    }

    @Test
    void quotedNativeDurationDoesNotBecomeAValidDeadlineDuringImport(@TempDir Path root) throws Exception {
        var result = convert(root, List.of("variableSubtract{var=skill.Count;value=2;duration=\"1200\"}",
                "reduceVariable{var=skill.Count;value=2;d='1200'}"));
        assertEquals(List.of("variable-subtract var=skill.Count value=\"2\" duration=0",
                "variable-subtract var=skill.Count value=\"2\" duration=0"), result.getStringList("skills.test.on-decision"));
    }

    @Test
    void quotedAmountsPreserveTheInternallySupportedOperand(@TempDir Path root) throws Exception {
        var result = convert(root, List.of("variableSubtract{var=skill.Count;value=\"2.5\"}",
                "reduceVariable{var=skill.Count;value='<target.health>'}"));
        assertEquals(List.of("variable-subtract var=skill.Count value=\"2.5\"",
                "variable-subtract var=skill.Count value=\"<target.health>\""), result.getStringList("skills.test.on-decision"));
        assertTrue(result.getStringList("skills.test.unsupported").isEmpty());
    }

    private static YamlConfiguration convert(Path root, List<String> sources) throws Exception {
        Path file = root.resolve("Skills/variables.yml");
        Files.createDirectories(file.getParent());
        var input = new YamlConfiguration();
        input.set("test.Skills", sources);
        input.save(file.toFile());
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(entry -> "skills.yml".equals(entry.get("target-file"))).findFirst().orElseThrow();
        var output = new YamlConfiguration();
        output.loadFromString(plan.get("content").toString());
        return output;
    }
}
