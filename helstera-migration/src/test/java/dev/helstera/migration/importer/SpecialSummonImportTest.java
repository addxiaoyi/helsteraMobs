package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpecialSummonImportTest {
    @TempDir Path root;
    private YamlConfiguration convert(List<String> lines) throws Exception {
        Path file=root.resolve("Skills/summons.yml"); Files.createDirectories(file.getParent());
        var input=new YamlConfiguration(); input.set("Summons.Skills",lines); Files.writeString(file,input.saveToString());
        var plan=new MythicMobsImporter().convert(file).stream().filter(p->"skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
    }
    @Test void cloudAndBlockRetainRawFieldsAndScheduling() throws Exception {
        var result=convert(List.of("summonCloud{drou=-2;radius=3;delay=2} @origin",
                "summonAreaEffectCloud{p=reddust;c=\"<trigger.name>\"} @trigger",
                "summonFallingBlock{types=oak_log[axis=x];repeat=1} @self"));
        assertEquals(List.of("summon-cloud{drou=-2;radius=3;delay=2} @origin",
                "summon-cloud{p=reddust;c=\"<trigger.name>\"} @trigger",
                "summon-falling-block{types=oak_log[axis=x];repeat=1} @self"), result.getStringList("skills.summons.on-decision"));
    }
    @Test void passengerMobIdUsesAliasPriorityAndPreservesExecutionOptions() throws Exception {
        var result=convert(List.of("summonPassenger{t=Wrong;rider=Mob.Rider;p=BestRider;stack=true;delay=3} @trigger"));
        var lines=result.getStringList("skills.summons.on-decision"); assertEquals(1,lines.size());
        assertTrue(lines.getFirst().contains("passenger=mythic_bestrider"));
        assertTrue(lines.getFirst().contains("delay=3")); assertTrue(lines.getFirst().endsWith(" @trigger"));
    }
    @Test void unsupportedFieldsStayVisibleAndDynamicBlockIsPreservedAsNativeInert() throws Exception {
        var bad=List.of("summonPassenger Rider @self", "summonCloud{rrot=NaN} @self", "summonCloud @unknown");
        var result=convert(bad); assertEquals(bad,result.getStringList("skills.summons.unsupported"));
        result=convert(List.of("summonFallingBlock{type=<target.name>} @origin"));
        assertEquals(List.of("summon-falling-block{type=<target.name>} @origin"),result.getStringList("skills.summons.on-decision"));
    }
}
