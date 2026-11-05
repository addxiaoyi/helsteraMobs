package dev.helstera.ai.dialog;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DialogueServiceAudienceTest {

    @Test
    void audienceMatchesOnlinePlayersWithinSixteenBlocksInTheSameWorld() {
        World world = mock(World.class);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 16, 0, 0));

        assertTrue(DialogueService.inAudience(new Location(world, 0, 0, 0), player));
        when(player.getLocation()).thenReturn(new Location(world, 16.01, 0, 0));
        assertFalse(DialogueService.inAudience(new Location(world, 0, 0, 0), player));
        World otherWorld = mock(World.class);
        when(player.getWorld()).thenReturn(otherWorld);
        assertFalse(DialogueService.inAudience(new Location(world, 0, 0, 0), player));
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(false);
        assertFalse(DialogueService.inAudience(new Location(world, 0, 0, 0), player));
    }
}
