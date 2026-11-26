package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableScopeImportTest {
    @TempDir Path root;

    @Test void explicitScopesOverrideDottedPrefixesAcrossVariableActions() throws Exception {
        Path file = writeSkill("scopes.yml", """
                test:
                  Skills:
                    - setvariable{var=skill.scalar;scope=target;type=INTEGER;value=11}
                    - variableadd{variable=global.counter;s=caster;amount=2}
                    - variablemath{var=world.math;s=global;equation=x*3}
                    - variableunset{var=skill.old;scope=world}
                    - setvariablelocation{var=caster.point;scope=global}
                    - locationunset{var=target.point;scope=skill}
                """);

        List<String> actions = skillActions(file, "test");
        assertEquals(List.of(
                "set-variable var=scalar scope=target type=INTEGER value=\"11\"",
                "variable-add var=counter scope=caster value=2",
                "variable-math var=math scope=global equation=\"x*3\"",
                "variable-unset var=old scope=world",
                "variable-location-set var=point scope=global value=@self",
                "location-unset var=point scope=skill"), actions);
    }

    @Test void variableAndScopeAliasesMatchMythicConfigPrecedence() throws Exception {
        Path file = writeSkill("aliases.yml", """
                test:
                  Skills:
                    - setvariable{var=skill.first;variable=global.alias;value=1}
                    - variableadd{var=skill.first;var=world.last;amount=2}
                    - variableunset{var=skill.scope;scope=target;s=global}
                    - locationunset{var=world.scope;scope=caster;scope=global}
                    - setvariable{key=target.key;type=INTEGER;value=5}
                """);

        List<String> actions = skillActions(file, "test");
        assertEquals(List.of(
                "set-variable var=global.alias type=INTEGER value=\"1\"",
                "variable-add var=world.last value=2",
                "variable-unset var=scope scope=target",
                "location-unset var=scope scope=global",
                "set-variable var=target.key type=INTEGER value=\"5\""), actions);
    }

    @Test void invalidAndUnsupportedMutationScopesRemainUnmapped() throws Exception {
        Path file = writeSkill("invalid.yml", """
                test:
                  Skills:
                    - setvariable{var=bogus.name;value=1}
                    - variableadd{var=trigger.name;value=1}
                    - variablemath{var=skill.good;scope=trigger;op=+;value=1}
                    - variableunset{var=skill.empty;scope=}
                    - setvariablelocation{var=skill.repeated;scope=target;scope=bad}
                """);

        YamlConfiguration yaml = skillYaml(file);
        assertEquals(List.of(), yaml.getStringList("skills.test.on-decision"));
        assertEquals(List.of(
                "setvariable{var=bogus.name;value=1}",
                "variableadd{var=trigger.name;value=1}",
                "variablemath{var=skill.good;scope=trigger;op=+;value=1}",
                "variableunset{var=skill.empty;scope=}",
                "setvariablelocation{var=skill.repeated;scope=target;scope=bad}"),
                yaml.getStringList("skills.test.unsupported"));
    }

    @Test void allNativeNameAliasesAndScopesRetainLiteralDottedKeys() throws Exception {
        var lines = new ArrayList<String>();
        var expected = new ArrayList<String>();
        for (String name : List.of("name", "n", "variable", "var", "key", "k")) {
            for (String scope : List.of("skill", "caster", "target", "world", "global")) {
                lines.add("setvariable{" + name + "=ignored.Counter.Part;s=" + scope.toUpperCase()
                        + ";value=7;duration=40;save=true}");
                expected.add("set-variable var=Counter.Part scope=" + scope
                        + " type=INTEGER value=\"7\" duration=40 save=true");
            }
        }
        var source = new YamlConfiguration();
        source.set("test.Skills", lines);
        assertEquals(expected, skillActions(writeSkill("all-scopes.yml", source.saveToString()), "test"));
    }

    @Test void unscopedAndPrefixedControlsKeepTheirExistingFormat() throws Exception {
        assertEquals(List.of(
                "set-variable var=Counter type=INTEGER value=\"7\"",
                "variable-add var=SKILL.Counter value=2",
                "variable-unset var=global.Counter.Part",
                "variable-location-set var=world.坐标 value=@self",
                "set-variable var=Counter type=INTEGER value=\"7\""),
                skillActions(writeSkill("controls.yml", """
                        test:
                          Skills:
                            - setvariable{var=Counter;value=7}
                            - variableadd{var=SKILL.Counter;amount=2}
                            - unsetvariable{var=global.Counter.Part}
                            - variablelocationset{var=world.坐标}
                            - setvariable Counter 7
                        """), "test"));
    }

    @Test void nativeAliasOrderAndLastDuplicateIncludeEmptyAndInvalidValues() throws Exception {
        var source = new YamlConfiguration();
        var rejected = List.of(
                "setvariable{var=skill.Counter;NAME=;value=1}",
                "setvariable{var=skill.Counter;VAR=trigger.Counter;value=1}",
                "setvariable{var=Counter;scope=;s=caster;value=1}",
                "setvariable{var=Counter;s=caster;scope=unknown;value=1}");
        source.set("test.Skills", rejected);
        var yaml = skillYaml(writeSkill("empty-aliases.yml", source.saveToString()));
        assertTrue(yaml.getStringList("skills.test.on-decision").isEmpty());
        assertEquals(rejected, yaml.getStringList("skills.test.unsupported"));
    }

    @Test void unsafeReferencesArePreservedWithWarningsForSkillsAndMobEvents() throws Exception {
        for (boolean mob : List.of(false, true)) {
            var lines = new ArrayList<String>();
            for (String action : List.of("setvariable", "variableadd", "variablemath", "variableunset",
                    "unsetvariable", "setvariablelocation", "variablelocationset", "locationunset", "unsetlocation")) {
                for (String argument : List.of("var=trigger.Counter", "var=unknown.Counter", "var=Counter;scope=bad",
                        "var=Counter;scope=", "var=Counter;scope=\"target\"", "var=\"Counter\"",
                        "var=skill.<caster.name>", "var=skill.", "var=", "var=\"Counter scope=global\"")) {
                    lines.add(action + "{" + argument + ";value=2}" + (mob ? " @self ~onSpawn" : ""));
                }
            }
            lines.add("variableunset{} @self" + (mob ? " ~onSpawn" : ""));
            var source = new YamlConfiguration();
            source.set("test.Skills", lines);
            if (mob) source.set("test.Type", "COW");
            Path file = root.resolve(mob ? "Mobs/bad.yml" : "Skills/bad.yml");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.saveToString());
            var plans = new MythicMobsImporter().convert(file);
            var plan = plans.stream().filter(item -> item.containsKey("content")).findFirst().orElseThrow();
            var yaml = YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
            assertEquals(lines, yaml.getStringList(mob ? "mob.unsupported.Skills" : "skills.test.unsupported"));
            assertTrue(yaml.getStringList(mob ? "mob.on-spawn" : "skills.test.on-decision").isEmpty());
            assertEquals(lines.size(), plans.stream().filter(item -> "unsupported".equals(item.get("status"))
                    && "WARN".equals(item.get("severity"))
                    && String.valueOf(item.get("note")).contains("variable name or mutation scope")).count());
        }
    }

    private Path writeSkill(String name, String content) throws Exception {
        Path file = root.resolve("Skills").resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private List<String> skillActions(Path file, String skill) throws Exception {
        return skillYaml(file).getStringList("skills." + skill + ".on-decision");
    }

    private YamlConfiguration skillYaml(Path file) throws Exception {
        var plan = new MythicMobsImporter().convert(file).stream()
                .filter(item -> "skills.yml".equals(item.get("target-file")))
                .findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
    }
}
