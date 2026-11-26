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

class GeneratedLootImportTest {
    @TempDir Path root;

    private List<Map<String, Object>> convert(String path, String yaml) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent()); Files.writeString(file, yaml);
        return new MythicMobsImporter().convert(file);
    }

    private YamlConfiguration content(List<Map<String, Object>> plans, String target) {
        var plan = plans.stream().filter(p -> target.equals(p.get("target-file"))).findFirst().orElseThrow();
        return YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
    }

    @Test void rawDropPayloadAliasesAndExecutionScopeArePreserved() throws Exception {
        String line = "givefromtarget{items=\"DIAMOND 2,command{c='reward<&sp><drop.amount>'} 3\";fl=false;delay=2;repeat=3} @trigger";
        var yaml = new YamlConfiguration(); yaml.set("Rewards.Skills", List.of(line));
        var output = content(convert("Skills/reward.yml", yaml.saveToString()), "skills.yml");
        assertEquals(List.of(line.replace("givefromtarget", "give-item-from-target")),
                output.getStringList("skills.rewards.on-decision"));
    }

    @Test void invalidItemsAndSchedulingRetainUnsupportedSource() throws Exception {
        var source = List.of("givefromtarget{fl=false}", "givefromtarget{items=;i=DIAMOND}",
                "givefromtarget{items=DIAMOND;delay=bad}");
        var yaml = new YamlConfiguration(); yaml.set("Rewards.Skills", source);
        var output = content(convert("Skills/reward.yml", yaml.saveToString()), "skills.yml");
        assertTrue(output.getStringList("skills.rewards.on-decision").isEmpty());
        assertEquals(source, output.getStringList("skills.rewards.unsupported"));
    }

    @Test void mixedTablesKeepOriginalIdentifierFractionalAmountsAndCommands() throws Exception {
        var drops = List.of("DIAMOND 2", "exp 1.75", "command{c='reward<&sp><drop.amount>'} 3");
        var yaml = new YamlConfiguration();
        yaml.set("MixedRewards.Drops", drops); yaml.set("MixedRewards.TotalItems", 1);
        var plans = convert("DropTables/rewards.yml", yaml.saveToString());
        var output = content(plans, "loot.yml");
        assertEquals("MixedRewards", output.getString("tables.mixedrewards.reward-source-id"));
        assertEquals(drops, output.getStringList("tables.mixedrewards.reward-drops"));
        assertEquals(1, output.getInt("tables.mixedrewards.total-items"));
        assertTrue(plans.stream().anyMatch(p -> String.valueOf(p.get("note")).contains("natural-death")));
    }

    @Test void commandOnlyTableHasARewardProjectionWithoutClaimingNaturalDeathCommands() throws Exception {
        var output = content(convert("DropTables/rewards.yml", """
                Commands:
                  Drops:
                  - "command{c='reward'} 1"
                """), "loot.yml");
        assertEquals(List.of("command{c='reward'} 1"), output.getStringList("tables.commands.reward-drops"));
        assertTrue(output.getConfigurationSection("tables.commands.entries").getKeys(false).isEmpty());
    }

    @Test void customItemOriginalIdentifierIsAvailableToRawRewardLines() throws Exception {
        var output=content(convert("Items/items.yml", """
                GrantGem:
                  Id: DIAMOND
                  Display: Reward gem
                """), "items.yml");
        assertEquals("GrantGem",output.getString("items.grantgem.mythic-source-id"));
    }

    @Test void unsupportedRewardProviderCannotSilentlyDisappearFromGeneratedTable() throws Exception {
        var drops=List.of("DIAMOND 2","mmoi:SWORD 1");
        var input=new YamlConfiguration();input.set("Mixed.Drops",drops);
        var output=content(convert("DropTables/rewards.yml",input.saveToString()),"loot.yml");
        assertEquals(drops,output.getStringList("tables.mixed.reward-drops"));
    }

    @Test void unsupportedOnlyTableCannotBecomeAVanillaItemWithTheSameName() throws Exception {
        var plans = convert("DropTables/rewards.yml", """
                DIAMOND:
                  Drops: [mmoi:SWORD 1]
                """);
        var output = content(plans, "loot.yml");
        assertEquals("DIAMOND", output.getString("tables.diamond.reward-source-id"));
        assertEquals(List.of("mmoi:SWORD 1"), output.getStringList("tables.diamond.reward-drops"));
        assertTrue(plans.stream().anyMatch(p -> "unsupported".equals(p.get("status"))
                && String.valueOf(p.get("note")).contains("falling back to an item")));
    }
}
