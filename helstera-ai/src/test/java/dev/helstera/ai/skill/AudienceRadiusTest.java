package dev.helstera.ai.skill;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AudienceRadiusTest {
    @Test
    void radiusUsesWorldNameAndSquaredCoordinates() {
        World originWorld = mock(World.class);
        World targetWorld = mock(World.class);
        when(originWorld.getName()).thenReturn("world");
        when(targetWorld.getName()).thenReturn("world");

        assertTrue(SkillExtras.withinRadius(new Location(originWorld, 0, 0, 0),
                new Location(targetWorld, 3, 4, 0), 5));
        assertFalse(SkillExtras.withinRadius(new Location(originWorld, 0, 0, 0),
                new Location(targetWorld, 3, 4, 0), 4.9));
    }
}
