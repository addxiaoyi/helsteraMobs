package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.StringReader;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VariableDurationImportTest {
    @TempDir Path root;
    @Test void durationsReachActionsAndInvalidSourceIsPreserved() throws Exception {
        Path file = root.resolve("Skills/variables.yml"); Files.createDirectories(file.getParent());
        Files.writeString(file, """
                test:
                  Skills:
                    - setvariable{var=skill.one;value=7;duration=40}
                    - setvariable{var=caster.two;type=STRING;value=text;d=-1}
                    - variableadd{var=skill.one;amount=2;expire=80}
                    - setvariablelocation{var=skill.location;e=20}
                    - setvariable{var=skill.bad;type=INTEGER;value=7;duration=NaN}
                    - setvariable{var=skill.duplicate;type=INTEGER;value=7;d=1;expire=2}
                """);
        var plans = new MythicMobsImporter().convert(file);
        String content = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file")))
                .findFirst().orElseThrow().get("content").toString();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader(content));
        List<String> actions = yaml.getStringList("skills.test.on-decision");
        assertEquals(4, actions.size(), content);
        assertTrue(actions.contains("set-variable var=skill.one type=INTEGER value=\"7\" duration=40"), content);
        assertTrue(actions.contains("set-variable var=caster.two type=STRING value=\"text\" duration=-1"), content);
        assertTrue(actions.contains("variable-add var=skill.one value=2 duration=80"), content);
        assertTrue(actions.contains("variable-location-set var=skill.location duration=20"), content);
        assertTrue(plans.toString().contains("duration=NaN"));
        assertTrue(plans.toString().contains("d=1;expire=2"));
    }
    @Test void saveFlagsReachEveryNativeWriterAndMalformedFlagsRemainPreserved() throws Exception {
        Path file = root.resolve("Skills/retention.yml"); Files.createDirectories(file.getParent());
        Files.writeString(file, """
                test:
                  Skills:
                    - setvariable{var=world.saved;value=7;save=true}
                    - variableadd{var=caster.saved;amount=2;save=false}
                    - setvariablelocation{var=global.point;save=true;d=-1}
                    - setvariable{var=world.bad;value=7;save=maybe}
                    - setvariable{var=world.duplicate;value=7;save=true;SAVE=false}
                """);
        var plans = new MythicMobsImporter().convert(file);
        String content = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file")))
                .findFirst().orElseThrow().get("content").toString();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader(content));
        List<String> actions = yaml.getStringList("skills.test.on-decision");
        assertEquals(3, actions.size(), content);
        assertTrue(actions.stream().anyMatch(a -> a.startsWith("set-variable ") && a.endsWith("save=true")), content);
        assertTrue(actions.contains("variable-add var=caster.saved value=2 save=false"), content);
        assertTrue(actions.contains("variable-location-set var=global.point duration=-1 save=true"), content);
        assertTrue(plans.toString().contains("save=maybe"));
        assertTrue(plans.toString().contains("save=true;SAVE=false"));
    }

}
