package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariableMathImportTest {
    @TempDir Path root;

    @Test
    void importsNativeEquationAliasesScopesAndCanonicalLiteralKeys() throws Exception {
        var lines = List.of(
                "variablemath{var=skill.Counter;equation=x+1}",
                "varmath{key=ignored.Counter.Part;s=target;eq=x/2}",
                "variablemath{var=Counter;equation=\"max(x + 1, 5)\"}",
                "variablemath{var=Counter;equation='x * 2'}",
                "variable-math var=skill.Literal.Part scope=target equation=\"x + 1\"");
        assertEquals(List.of(
                "variable-math var=skill.Counter equation=\"x+1\"",
                "variable-math var=Counter.Part scope=target equation=\"x/2\"",
                "variable-math var=Counter equation=\"max(x + 1, 5)\"",
                "variable-math var=Counter equation=\"x * 2\"",
                "variable-math var=skill.Literal.Part scope=target equation=\"x + 1\""), actions(lines));
    }

    @Test
    void aliasPriorityDuplicatesAndDefaultEquationUseNativeSelection() throws Exception {
        assertEquals(List.of(
                "variable-math var=skill.Counter equation=\"x+1\" duration=0",
                "variable-math var=skill.Counter equation=\"x+2\" duration=0",
                "variable-math var=skill.Counter equation=\"x*2\"",
                "variable-math var=skill.Counter equation=\"x\"",
                "variable-math var=skill.Counter equation=\"x\"",
                "var-math var=skill.Counter op=* value=3"), actions(List.of(
                "variablemath{var=skill.Counter;e=x+100;eq=x+10;equation=x+1}",
                "variablemath{var=skill.Counter;eq=x+2;e=x+100}",
                "variablemath{var=skill.Counter;equation=unknown;EQUATION=x*2}",
                "variablemath{var=skill.Counter}",
                "variablemath{var=skill.Counter;op=*;value=3}",
                "var-math var=skill.Counter op=* value=3")));
    }

    @Test
    void equationAndDurationShareEWhileDurationAliasesUseNativeFallback() throws Exception {
        assertEquals(List.of(
                "variable-math var=skill.Counter equation=\"40\" duration=40",
                "variable-math var=skill.Counter equation=\"40\" duration=0",
                "variable-math var=skill.Counter equation=\"x+1\" duration=80",
                "variable-math var=skill.Counter equation=\"x+1\" duration=0",
                "variable-math var=skill.Counter equation=\"x+1\" duration=0",
                "variable-math var=skill.Counter equation=\"x+1\" duration=-1",
                "variable-math var=skill.Counter equation=\"x+1\" duration=1200",
                "variable-math var=skill.Counter equation=\"x+1\""), actions(List.of(
                "variablemath{var=skill.Counter;e=40}",
                "variablemath{var=skill.Counter;e=\"40\"}",
                "variablemath{var=skill.Counter;eq=x+1;e=80}",
                "variablemath{var=skill.Counter;equation=x+1;duration=bad;d=40;e=20}",
                "variablemath{var=skill.Counter;equation=x+1;duration=2s}",
                "variablemath{var=skill.Counter;equation=x+1;expire=-1;save=true}",
                "variablemath{var=skill.Counter;equation=x+1;duration=bad;DURATION=1200;d=40}",
                "variablemath{var=skill.Counter;equation=x+1;save=false}")));
    }

    @Test
    void allFiveScopesKeepPlaceholdersAndRemainingDottedKeySegments() throws Exception {
        var lines = new ArrayList<String>();
        var expected = new ArrayList<String>();
        for (String scope : List.of("skill", "caster", "target", "world", "global")) {
            lines.add("variablemath{name=ignored.Counter.Part;scope=" + scope
                    + ";equation=x+<target.var.Step>}");
            expected.add("variable-math var=Counter.Part scope=" + scope + " equation=\"x+<target.var.Step>\"");
        }
        assertEquals(expected, actions(lines));
    }

    @Test
    void malformedEquationsRemainPreservedWithWarningsBeforeMobEventProjection() throws Exception {
        for (boolean mob : List.of(false, true)) {
            var lines = new ArrayList<String>();
            for (String arguments : List.of("equation=", "equation=x+", "equation=unknown", "eq=max(x)",
                    "equation=eval(x+1)", "equation=NaN", "equation=Infinity", "equation=x+1;EQUATION=",
                    "eq=x+1;equation=", "e=x+1;eq=")) {
                lines.add("variablemath{var=skill.Counter;" + arguments + "}" + (mob ? " @self ~onSpawn" : ""));
            }
            var plans = convert(lines, mob);
            var yaml = content(plans);
            assertEquals(lines, yaml.getStringList(mob ? "mob.unsupported.Skills" : "skills.test.unsupported"));
            assertTrue(yaml.getStringList(mob ? "mob.on-spawn" : "skills.test.on-decision").isEmpty());
            assertEquals(lines.size(), plans.stream().filter(plan -> "WARN".equals(plan.get("severity"))
                    && "unsupported".equals(plan.get("status"))).count());
        }
    }

    private List<String> actions(List<String> lines) throws Exception {
        var yaml = content(convert(lines, false));
        assertTrue(yaml.getStringList("skills.test.unsupported").isEmpty(), yaml.saveToString());
        return yaml.getStringList("skills.test.on-decision");
    }

    private List<Map<String, Object>> convert(List<String> lines, boolean mob) throws Exception {
        var source = new YamlConfiguration();
        source.set("test.Skills", lines);
        if (mob) source.set("test.Type", "COW");
        Path file = root.resolve(mob ? "Mobs/math.yml" : "Skills/math.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source.saveToString());
        return new MythicMobsImporter().convert(file);
    }

    private static YamlConfiguration content(List<Map<String, Object>> plans) {
        var plan = plans.stream().filter(item -> item.containsKey("content")).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
    }
}
