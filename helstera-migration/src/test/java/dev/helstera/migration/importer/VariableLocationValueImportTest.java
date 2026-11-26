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

class VariableLocationValueImportTest {
    @TempDir Path root;

    @Test
    void nativeNamesEmitAnExplicitSelfDefaultAndCanonicalActionsRetainTheirExistingMeaning() throws Exception {
        var lines = new ArrayList<String>();
        var expected = new ArrayList<String>();
        for (String mechanic : List.of("variablesetlocation", "setvariablelocation", "setvarloc", "variablelocationset")) {
            lines.add(mechanic + "{var=target.Point}");
            expected.add("variable-location-set var=target.Point value=@self");
        }
        lines.add("variable-location-set var=Point scope=target");
        expected.add("variable-location-set var=Point scope=target");
        lines.add("setvariablelocation Point");
        expected.add("variable-location-set var=Point");
        assertEquals(expected, actions(lines));
    }

    @Test
    void staticSelectorAliasesNormalizeWithoutChangingTheVariableRegistryOrDuration() throws Exception {
        var lines = new ArrayList<String>();
        var expected = new ArrayList<String>();
        for (String selector : List.of("self", "caster", "boss", "mob", "selflocation", "casterlocation",
                "bosslocation", "moblocation", "origin", "source", "trigger", "triggerlocation",
                "target", "T", "targetlocation", "targetLoc", "TL")) {
            String value = List.of("origin", "source").contains(selector) ? "origin"
                    : selector.startsWith("trigger") ? "trigger"
                    : List.of("target", "T").contains(selector) ? "target"
                    : List.of("targetlocation", "targetLoc", "TL").contains(selector) ? "targetlocation" : "self";
            lines.add("setvariablelocation{var=ignored.Point.Part;s=world;value=@"
                    + selector.toUpperCase(java.util.Locale.ROOT) + ";duration=-1;save=true}");
            expected.add("variable-location-set var=Point.Part scope=world value=@" + value + " duration=-1 save=true");
        }
        assertEquals(expected, actions(lines));
    }

    @Test
    void valueAliasPriorityAndLastDuplicateAreIndependentOfSourceOrder() throws Exception {
        var lines = new ArrayList<String>();
        for (String arguments : List.of("value=@origin", "val=@origin", "v=@origin", "amount=@origin", "a=@origin",
                "a=@trigger;amount=@trigger;v=@trigger;val=@trigger;value=@origin",
                "value=@origin;val=@trigger", "val=@origin;v=@trigger", "v=@origin;amount=@trigger",
                "amount=@origin;a=@trigger", "value=;VALUE=@origin", "val=@unknown;VAL=@origin")) {
            lines.add("setvariablelocation{var=skill.Point;" + arguments + "}");
        }
        assertEquals(java.util.Collections.nCopies(lines.size(), "variable-location-set var=skill.Point value=@origin"),
                actions(lines));
    }

    @Test
    void allFiveScopesStripOnlyTheNativePrefixAndRetainTheRemainingDottedKey() throws Exception {
        var lines = new ArrayList<String>();
        var expected = new ArrayList<String>();
        for (String scope : List.of("skill", "caster", "target", "world", "global")) {
            lines.add("setvarloc{name=ignored.Point.Part;scope=" + scope + ";value=@triggerlocation}");
            expected.add("variable-location-set var=Point.Part scope=" + scope + " value=@trigger");
        }
        assertEquals(expected, actions(lines));
    }

    @Test
    void invalidAndUnsupportedSelectorsPreserveSourceWithWarningsBeforeMobEventProjection() throws Exception {
        for (boolean mob : List.of(false, true)) {
            var lines = new ArrayList<String>();
            for (String mechanic : List.of("variablesetlocation", "setvariablelocation", "setvarloc", "variablelocationset")) {
                for (String arguments : List.of("value=", "value=\"@self\"", "value='@origin'", "value=@target{limit=1}",
                        "value=@targetlocation{maxdistance=32}", "value=@unknown", "value=1,2,3", "value=world,1,2,3",
                        "value=<skill.var.Selector>", "value=@self{y=2}", "value=@origin{offset=2}",
                        "value=@self;VALUE=", "value=;val=@origin", "val=;v=@origin")) {
                    lines.add(mechanic + "{var=target.Point;" + arguments + "}" + (mob ? " @self ~onSpawn" : ""));
                }
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
        Path file = root.resolve(mob ? "Mobs/location-values.yml" : "Skills/location-values.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source.saveToString());
        return new MythicMobsImporter().convert(file);
    }

    private static YamlConfiguration content(List<Map<String, Object>> plans) {
        var plan = plans.stream().filter(item -> item.containsKey("content")).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
    }
}
