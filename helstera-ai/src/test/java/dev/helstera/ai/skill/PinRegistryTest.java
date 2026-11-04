package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PinRegistryTest {
    @Test
    void loadsSingleAndMultipleLocationsAndReturnsDefensiveCopies() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        PinRegistry registry = new PinRegistry(name -> "world".equals(name) ? world : null);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pins.Gate", Map.of("world", "world", "x", 1, "y", 64, "z", 2));
        yaml.set("pins.Route.locations", List.of(
                Map.of("world", "world", "x", 3, "y", 64, "z", 4),
                "world,5,64,6,90,0"));

        assertTrue(registry.load(yaml).isEmpty());
        assertEquals(2, registry.size());
        assertEquals(1, registry.locations("GATE").size());
        assertEquals(2, registry.locations("route").size());
        Location returned = registry.locations("gate").get(0);
        returned.setX(99);
        assertEquals(1, registry.locations("GATE").get(0).getX());
    }

    @Test
    void skipsInvalidPointsAndRejectsCaseInsensitiveDuplicateNames() {
        World world = mock(World.class);
        PinRegistry registry = new PinRegistry(name -> "world".equals(name) ? world : null);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pins.A", List.of("missing,0,0,0", "world,NaN,0,0", "world,1,2,3"));
        yaml.set("pins.a", "world,4,5,6");

        List<String> warnings = registry.load(yaml);

        assertEquals(1, registry.size());
        assertEquals(1, registry.locations("a").size());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("invalid or unloaded")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("duplicates")));
    }

    @Test
    void absentPinsSectionDoesNotInterpretUnrelatedConfigAsPins() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("other", "world,1,2,3");
        PinRegistry registry = new PinRegistry(name -> mock(World.class));

        assertTrue(registry.load(yaml).isEmpty());
        assertEquals(0, registry.size());
    }

    @Test
    void acceptsMythicPackSectionAndFieldCasing() {
        World world = mock(World.class);
        PinRegistry registry = new PinRegistry(name -> "world".equals(name) ? world : null);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("Pins.Route.Points", List.of(
                Map.of("World", "world", "X", 1, "Y", 64, "Z", 2, "Yaw", 90),
                Map.of("world", "world", "x", 3, "y", 64, "z", 4)));

        assertTrue(registry.load(yaml).isEmpty());
        assertEquals(2, registry.locations("route").size());
        assertEquals(90f, registry.locations("route").get(0).getYaw());
    }

    @Test
    void moveSingleUpdatesOnlySinglePointPinsAndRejectsInvalidDestinations() {
        World world = mock(World.class);
        PinRegistry registry = new PinRegistry(name -> "world".equals(name) ? world : null);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pins.Single", "world,1,2,3");
        yaml.set("pins.Multi", List.of("world,1,2,3", "world,4,5,6"));
        registry.load(yaml);

        Location destination = new Location(world, 7, 8, 9, 30, 15);
        assertTrue(registry.moveSingle("single", destination));
        assertEquals(destination, registry.locations("SINGLE").get(0));
        assertFalse(registry.moveSingle("multi", destination));
        assertFalse(registry.moveSingle("missing", destination));
        assertFalse(registry.moveSingle("single", new Location(null, 0, 0, 0)));

        LivingEntity target = mock(LivingEntity.class);
        when(target.isValid()).thenReturn(true);
        when(target.getLocation()).thenReturn(destination);
        SkillExtras.pinRegistry(registry);
        try {
            BehaviorContext context = BehaviorContext.continueExecution(null, target, 1, 0, 0,
                    "test", null, target, List.of(target), new Location(world, 0, 0, 0), null);
            SkillExtras.actions().get("move-pin").create(List.of("pin=single")).accept(context);
            assertEquals(destination, registry.locations("single").get(0));
        } finally {
            SkillExtras.pinRegistry(null);
        }
    }

    @Test
    void pinConditionsUseNamedLocationsAndFailClosedWhenRegistryIsUnavailable() {
        World world = mock(World.class);
        LivingEntity target = mock(LivingEntity.class);
        Location targetLocation = new Location(world, 3, 64, 4);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(targetLocation);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pins.Center", Map.of("world", "world", "x", 0, "y", 64, "z", 0));
        yaml.set("pins.CornerA", Map.of("world", "world", "x", 0, "y", 60, "z", 0));
        yaml.set("pins.CornerB", Map.of("world", "world", "x", 10, "y", 70, "z", 10));
        PinRegistry registry = new PinRegistry(name -> "world".equals(name) ? world : null);
        registry.load(yaml);
        SkillExtras.pinRegistry(registry);
        BehaviorContext context = BehaviorContext.continueExecution(null, target, 1, 0, 0,
                "test", null, target, List.of(target), new Location(world, 20, 64, 20), null);

        try {
            var conditions = SkillExtras.conditions();
            assertTrue(conditions.get("distance-from-pin").create(
                    List.of("pin=center", "distance=<=5")).test(context));
            assertTrue(conditions.get("distance-from-pin").create(
                    List.of("pin=center", "distance=5to6")).test(context));
            assertTrue(conditions.get("distance-from-pin").create(
                    List.of("pin=center", "distance=!=4")).test(context));
            assertFalse(conditions.get("distance-from-pin").create(
                    List.of("pin=center", "distance=!=5")).test(context));
            assertFalse(conditions.get("distance-from-pin").create(
                    List.of("pin=center", "distance=6to10")).test(context));
            assertTrue(conditions.get("origin-distance-from-pin").create(
                    List.of("p=center", "d=>20")).test(context));
            assertTrue(conditions.get("in-pin-region").create(
                    List.of("pin1=CornerA", "pin2=CornerB")).test(context));
            assertFalse(conditions.get("distance-from-pin").create(
                    List.of("pin=unknown", "distance=<=100")).test(context));
            assertThrows(IllegalArgumentException.class, () -> conditions.get("distance-from-pin")
                    .create(List.of("pin=center", "distance=8to2")));
        } finally {
            SkillExtras.pinRegistry(null);
        }
        assertFalse(SkillExtras.conditions().get("distance-from-pin").create(
                List.of("pin=center", "distance=<=100")).test(context));
    }
}
