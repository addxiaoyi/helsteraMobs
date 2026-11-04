package dev.helstera.ai.skill;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PinTargeterTest {
    @Test
    void resolvesAllNamedPinLocationsByDefaultAndOneWhenRandom() {
        World world = mock(World.class);
        PinRegistry registry = new PinRegistry(name -> "world".equals(name) ? world : null);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pins.Route.locations", List.of("world,1,2,3", "world,4,5,6"));
        registry.load(yaml);

        assertEquals(2, PinTargeter.select("@Pin{name=route}", registry).size());
        assertEquals(2, PinTargeter.select("@Pin{p=route}", registry).size());
        assertEquals(1, PinTargeter.select("@Pin{key=route;r=true}", registry).size());
        assertEquals(2, PinTargeter.select("@Pin{route}", registry).size());
        assertEquals(1, PinTargeter.select("@Pin{route;random=true}", registry).size());
        assertEquals(List.of(), PinTargeter.select("@Pin{name=missing}", registry));
    }

    @Test
    void rejectsMalformedPinTargetersAndDoesNotClaimEntityTargeters() {
        World world = mock(World.class);
        PinRegistry registry = new PinRegistry(name -> "world".equals(name) ? world : null);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pins.Single", "world,1,2,3");
        registry.load(yaml);

        assertTrue(PinTargeter.recognizes("@pin"));
        assertFalse(PinTargeter.recognizes("@nearestPlayer"));
        assertEquals(List.of(), PinTargeter.select("@Pin{name=single;random=maybe}", registry));
        assertEquals(List.of(), PinTargeter.select("@Pin{name=single;unknown=x}", registry));
        assertNull(PinTargeter.select("@NearestPlayer", registry));
    }

    @Test
    void selectsEveryBlockInAnInclusiveSameWorldPinCuboid() {
        World world = mock(World.class);
        PinRegistry registry = new PinRegistry(name -> "world".equals(name) ? world : null);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pins.Min", "world,2,4,8");
        yaml.set("pins.Max", "world,3,5,9");
        yaml.set("pins.Far", "world,200,200,200");
        registry.load(yaml);

        List<Location> blocks = PinTargeter.select(
                "@BlocksInPinRegion{pin1=min;p2=max}", registry);
        assertEquals(8, blocks.size());
        assertTrue(blocks.contains(new Location(world, 2, 4, 8)));
        assertTrue(blocks.contains(new Location(world, 3, 5, 9)));
        assertEquals(List.of(), PinTargeter.select(
                "@BlocksInPinRegion{pin1=min;pin2=far}", registry));
    }

    @Test
    void rejectsRegionsAboveTheSynchronousBlockLimit() {
        World world = mock(World.class);
        PinRegistry registry = new PinRegistry(name -> "world".equals(name) ? world : null);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pins.Min", "world,0,64,0");
        yaml.set("pins.Max", "world,16384,64,0");
        registry.load(yaml);

        assertEquals(List.of(), PinTargeter.select(
                "@BlocksInPinRegion{pin1=min;pin2=max}", registry));
    }
}
