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

class ItemCopyImportTest {
    @Test void copyAndSlotAliasesPreserveRawFieldsAndExecutionOptions(@TempDir Path root) throws Exception {
        for (var alias : Map.of("equipCopy", "equip-copy", "giveitemfromslot", "give-item-from-slot",
                "givefromslot", "give-item-from-slot").entrySet()) {
            String body = "{slots=HEAD;slot=HAND;s=HEAD;SLOT=SHIELD;amount=0;fl=true;"
                    + "dofakelooting=\"true\";delay=2;repeat=3;repeatinterval=4}";
            var output = convert(root, List.of(alias.getKey() + body + " @trigger"));
            assertEquals(List.of(alias.getValue() + body + " @trigger"), output.getStringList("skills.copy.on-decision"));
            assertFalse(output.contains("skills.copy.unsupported"));
        }
    }

    @Test void quotesEmptySlotsIgnoredAmountsAndLocationSelectionRemainLiteral(@TempDir Path root) throws Exception {
        var output = convert(root, List.of("giveFromSlot{slot=\"HAND\";s=HEAD;fl='true'} @trigger",
                "giveItemFromSlot{slot=;s=SHIELD;amount=<caster.var.amount>} @selflocation",
                "equipCopy{slots=NONE;amount=-9} @targets", "equipCopy @self"));
        assertEquals(List.of("give-item-from-slot{slot=\"HAND\";s=HEAD;fl='true'} @trigger",
                "give-item-from-slot{slot=;s=SHIELD;amount=<caster.var.amount>} @selflocation",
                "equip-copy{slots=NONE;amount=-9} @targets", "equip-copy @self"),
                output.getStringList("skills.copy.on-decision"));
    }

    @Test void generatedLootFamilyRemainsExplicitlyUnsupported(@TempDir Path root) throws Exception {
        List<String> unfinished = List.of("giveitemfromtarget{items=DIAMOND}", "givefromtarget{items=DIAMOND}",
                "giveitemsfromtarget{items=DIAMOND}", "itemgivefromtarget{items=DIAMOND}");
        var output = convert(root, unfinished);
        assertTrue(output.getStringList("skills.copy.on-decision").isEmpty());
        assertEquals(unfinished, output.getStringList("skills.copy.unsupported"));
    }

    @Test void invalidSchedulingPreservesOriginalInsteadOfLosingExecutionSemantics(@TempDir Path root) throws Exception {
        String original = "giveFromSlot{slot=HAND;delay=bad} @trigger";
        var output = convert(root, List.of(original));
        assertTrue(output.getStringList("skills.copy.on-decision").isEmpty());
        assertEquals(List.of(original), output.getStringList("skills.copy.unsupported"));
    }

    private YamlConfiguration convert(Path root, List<String> lines) throws Exception {
        Path file = root.resolve("Skills/item-copy.yml");
        Files.createDirectories(file.getParent());
        var input = new YamlConfiguration();
        input.set("copy.Skills", lines);
        input.save(file.toFile());
        var plans = new MythicMobsImporter().convert(file);
        Map<String, Object> plan = plans.stream().filter(entry -> "skills.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
    }
}
