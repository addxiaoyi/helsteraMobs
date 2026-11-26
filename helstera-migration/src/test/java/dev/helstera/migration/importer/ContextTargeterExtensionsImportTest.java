package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextTargeterExtensionsImportTest {
    @TempDir Path root;

    @Test
    void importsRandomLocationAliasesWithoutChangingRawNumericArguments() throws Exception {
        List<String> aliases=List.of("randomLocationsNearOrigin","RLO","randomLocationsOrigin","RLNO",
                "randomLocationsNearTargets","RLNT","randomLocationsNearCaster","RLNC","randomLocations");
        List<String> expected=List.of("randomlocationsnearorigin","randomlocationsnearorigin","randomlocationsnearorigin","randomlocationsnearorigin",
                "randomlocationsneartargets","randomlocationsneartargets","randomlocationsnearcaster","randomlocationsnearcaster","randomlocationsnearcaster");
        var input=new YamlConfiguration();
        String args="{a=2to2;amount=3;r=\"2\";radius=4;minr=1;s=2}";
        input.set("random.Skills",aliases.stream().map(alias->"setblock{type=DIAMOND_BLOCK} @"+alias+args).toList());
        Path file=root.resolve("Skills/random.yml");Files.createDirectories(file.getParent());Files.writeString(file,input.saveToString());
        var plans=new MythicMobsImporter().convert(file);
        var plan=plans.stream().filter(item->"skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var result=YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
        assertEquals(expected.stream().map(name->"set-block DIAMOND_BLOCK @"+name+args).toList(),result.getStringList("skills.random.on-decision"));
        assertTrue(plans.stream().noneMatch(item->"WARN".equals(item.get("severity"))));
    }

    @Test
    void importsNearestStructureWithStaticArgumentsAndPreservedUnknownType() throws Exception {
        var input = new YamlConfiguration();
        input.set("structures.Skills", List.of("setblock{type=DIAMOND_BLOCK} @nearestStructure{type=bad;t=VILLAGE;radius=\"2\";r=4;unexplored=false;u=true}"));
        Path file = root.resolve("Skills/structures.yml");
        Files.createDirectories(file.getParent()); Files.writeString(file, input.saveToString());
        var plans = new MythicMobsImporter().convert(file);
        var plan = plans.stream().filter(item -> "skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var result = YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
        assertEquals(List.of("set-block DIAMOND_BLOCK @neareststructure{type=bad;t=VILLAGE;radius=\"2\";r=4;unexplored=false;u=true}"),
                result.getStringList("skills.structures.on-decision"));
        assertTrue(plans.stream().noneMatch(item -> "WARN".equals(item.get("severity"))));
    }

    @Test
    void importsBlockQueriesWithNativeRawArgumentsAndAllAliases() throws Exception {
        List<String> selectors = List.of("highestblock", "obstructingBlock", "blocksInChunk", "BIC",
                "blocksInRadius", "BIR", "blocksNearOrigin", "BNO", "blockVein", "vein", "bv");
        List<String> expected = List.of("highestblock", "obstructingblock", "blocksinchunk", "blocksinchunk",
                "blocksinradius", "blocksinradius", "blocksnearorigin", "blocksnearorigin", "blockvein", "blockvein", "blockvein");
        var input = new YamlConfiguration();
        input.set("blocks.Skills", selectors.stream().map(selector ->
                "setblock{type=DIAMOND_BLOCK} @" + selector + "{r=2;radius=\"1\";nearorigin=true;limit=4}").toList());
        Path file = root.resolve("Skills/blocks.yml");
        Files.createDirectories(file.getParent()); Files.writeString(file, input.saveToString());
        var plans = new MythicMobsImporter().convert(file);
        var plan = plans.stream().filter(item -> "skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var result = YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
        assertEquals(expected.stream().map(selector -> "set-block DIAMOND_BLOCK @" + selector
                + "{r=2;radius=\"1\";nearorigin=true;limit=4}").toList(), result.getStringList("skills.blocks.on-decision"));
        assertTrue(plans.stream().noneMatch(item -> "WARN".equals(item.get("severity"))));
    }

    @Test
    void keepsAimingAndInheritedTargetsDistinctAndPreservesRawOptions() throws Exception {
        var input = new YamlConfiguration();
        input.set("inherited.Skills", List.of(
                "addtag{t=chosen} @targeted{sort=NEAREST;limit=2}",
                "addtag{t=chosen} @targetedTarget",
                "setchunkforceloaded{loaded=true} @targetedLocation{x=2;xoffset=3}",
                "setchunkforceloaded{loaded=true} @targetedLocations",
                "setchunkforceloaded{loaded=true} @targetedLoc{y=\"2\"}",
                "setchunkforceloaded{loaded=true} @targetBlock{d=8;maxdistance=5;it=false}"));
        Path file = root.resolve("Skills/inherited.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, input.saveToString());
        var plans = new MythicMobsImporter().convert(file);
        var plan = plans.stream().filter(item -> item.containsKey("content")).findFirst().orElseThrow();
        var result = YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
        List<String> actions = result.getStringList("skills.inherited.on-decision");
        assertEquals(6, actions.size());
        assertTrue(actions.get(0).endsWith("@targetedtarget{sort=NEAREST;limit=2}"));
        assertTrue(actions.get(1).endsWith("@targetedtarget"));
        assertTrue(actions.get(2).endsWith("@targetedlocation{x=2;xoffset=3}"));
        assertTrue(actions.get(3).endsWith("@targetedlocation"));
        assertTrue(actions.get(4).endsWith("@targetedlocation{y=\"2\"}"));
        assertTrue(actions.get(5).endsWith("@targetblock{d=8;maxdistance=5;it=false}"));
        assertTrue(plans.stream().noneMatch(item -> "WARN".equals(item.get("severity"))));
    }

    @Test
    void preservesItemRadiusAliasPriorityRawValuesAndInlineConditions() throws Exception {
        var input = new YamlConfiguration();
        input.set("items.Skills", List.of(
                "addtag{t=chosen} @IIR{r=2;radius=5}",
                "addtag{t=chosen} @INO{radius=5;r=2}",
                "addtag{t=chosen} @itemsInRadius{r=\"2\";limit=1;sort=NEAREST}",
                "addtag{t=chosen} @itemsNearOrigin{conditions=[ - hastag{t=eligible} true ]}"));
        Path file = root.resolve("Skills/items.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, input.saveToString());
        var plans = new MythicMobsImporter().convert(file);
        var plan = plans.stream().filter(item -> item.containsKey("content")).findFirst().orElseThrow();
        var result = YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
        List<String> actions = result.getStringList("skills.items.on-decision");
        assertEquals(4, actions.size());
        assertTrue(actions.get(0).endsWith("@itemsinradius{r=2;radius=5}"));
        assertTrue(actions.get(1).endsWith("@itemsnearorigin{radius=5;r=2}"));
        assertTrue(actions.get(2).endsWith("@itemsinradius{r=\"2\";limit=1;sort=NEAREST}"));
        assertTrue(actions.get(3).endsWith("@itemsnearorigin{conditions=[ - hastag{t=eligible} true ]}"));
        assertTrue(plans.stream().noneMatch(item -> "WARN".equals(item.get("severity"))));
    }

    @Test
    void canonicalizesNewAliasesWhileRetainingNamePlaceholdersLocationOptionsAndGuards() throws Exception {
        var input = new YamlConfiguration();
        input.set("context_targeters.Skills", List.of(
                "damage{amount=1} @specificplayer{n=<skill.var.Player>}",
                "damage{amount=1} @lastAttacker{targetPlayers=false}",
                "damage{amount=1} @lastInteract",
                "setchunkforceloaded{loaded=true} @LOT{y=3;limit=1;sort=NEAREST}",
                "setchunkforceloaded{loaded=true} @locationOfTarget{conditions=[ - outside true ]}"));
        Path file = root.resolve("Skills/context-targeters.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, input.saveToString());
        var plans = new MythicMobsImporter().convert(file);
        var plan = plans.stream().filter(item -> item.containsKey("content")).findFirst().orElseThrow();
        var result = YamlConfiguration.loadConfiguration(new StringReader(plan.get("content").toString()));
        List<String> actions = result.getStringList("skills.context_targeters.on-decision");
        assertEquals(5, actions.size(), result.saveToString());
        assertTrue(actions.get(0).endsWith("@playerbyname{n=<skill.var.Player>}"));
        assertTrue(actions.get(1).endsWith("@interactionlastattacker{targetPlayers=false}"));
        assertTrue(actions.get(2).endsWith("@interactionlastinteract"));
        assertTrue(actions.get(3).endsWith("@locationsoftargets{y=3;limit=1;sort=NEAREST}"));
        assertTrue(actions.get(4).contains("@locationsoftargets{conditions="));
        assertTrue(result.getStringList("skills.context_targeters.unsupported").isEmpty());
        assertTrue(plans.stream().noneMatch(item -> "WARN".equals(item.get("severity"))));
    }
}
