package dev.helstera.plugin;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class MobRemovalCommandTest {
    private MobRemovalCommand.Selection parse(String command, boolean player) {
        return MobRemovalCommand.parse(("mob " + command).split(" "), player);
    }
    @Test void mobFactionOverlaysAiAndBlankClearsIt() {
        var profile = new dev.helstera.ai.AiProfile("test");
        profile.faction = "ai-default";
        var mob = new org.bukkit.configuration.file.YamlConfiguration();
        mob.set("faction", " Guards ");
        HelsteraPlugin.applyMobOptions(profile, mob);
        assertEquals("Guards", profile.faction);
        mob.set("faction", " ");
        HelsteraPlugin.applyMobOptions(profile, mob);
        assertNull(profile.faction);
    }
    @Test void bulkExemptsNpcAndPersistentButNotNever() {
        for (String mode : List.of("NORMAL", "CHUNK", "NPC", "NEVER", "PERSISTENT")) {
            assertEquals(!List.of("NPC", "PERSISTENT").contains(mode),
                    parse("killall", false).matches("boss", null, mode, null, null));
            assertTrue(parse("killall -p", false).matches("boss", null, mode, null, null));
            assertTrue(parse("kill -e BOSS", false).matches("boss", null, mode, null, null));
        }
    }
    @Test void nativeSubstringIsCaseSensitiveAndExactMatchIsNot() {
        assertTrue(parse("kill boss",false).matches("boss_extra",null,"NPC",null,null));
        assertFalse(parse("kill BOSS",false).matches("boss_extra",null,"NORMAL",null,null));
        assertTrue(parse("kill -e BOSS",false).matches("boss",null,"NPC",null,null));
        assertFalse(parse("kill -e boss",false).matches("boss_extra",null,"NORMAL",null,null));
        assertFalse(parse("killall -p",false).matches(null,null,"NORMAL",null,null));
    }
    @Test void factionAddsNativeNameIntersection() {
        var selection=parse("kill -f guards",false);
        assertTrue(selection.matches("guards_boss","GUARDS","NPC",null,null));
        assertFalse(selection.matches("guards_boss","other","NPC",null,null));
        assertFalse(selection.matches("boss","guards","NPC",null,null));
    }
    @Test void radiusIsInclusiveThreeDimensionalAndSameWorld() {
        World world=mock(World.class),other=mock(World.class);
        var selection=parse("killall -p 10",true);var origin=new Location(world,0,100,0);
        assertTrue(selection.matches("boss",null,"NPC",new Location(world,6,108,0),origin));
        assertFalse(selection.matches("boss",null,"NPC",new Location(world,6,108.01,0),origin));
        assertFalse(selection.matches("boss",null,"NPC",new Location(other,0,100,0),origin));
        assertFalse(selection.matches("boss",null,"NPC",origin,null));
    }
    @Test void malformedOrMisleadingSyntaxCannotBecomeUnboundedCleanup() {
        for (String command:List.of("killall 0","killall -1","killall NaN","killall 2147483648",
                "killall -x","killall 10 extra","kill","kill boss 10","kill -","kill -v boss"))
            assertThrows(IllegalArgumentException.class,()->parse(command,true),command);
        assertThrows(IllegalArgumentException.class,()->parse("killall 10",false));
    }
}
