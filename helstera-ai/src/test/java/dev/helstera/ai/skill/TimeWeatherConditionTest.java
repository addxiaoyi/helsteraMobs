package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimeWeatherConditionTest {

    @Test
    void matchesNamedTimeWindowsAndWrappingRanges() {
        assertTrue(SkillExtras.matchesTime("dawn", 500));
        assertTrue(SkillExtras.matchesTime("night", 13_000));
        assertTrue(SkillExtras.matchesTime("23000-1000", 23_500));
        assertTrue(SkillExtras.matchesTime("23000-1000", 500));
        assertFalse(SkillExtras.matchesTime("23000-1000", 12_000));
    }

    @Test
    void matchesNumericTimeComparators() {
        assertTrue(SkillExtras.matchesTime(">=6000", 6_000));
        assertTrue(SkillExtras.matchesTime("<12000", 11_999));
        assertFalse(SkillExtras.matchesTime("<12000", 12_000));
    }

    @Test
    void acceptsMythicWeatherAliases() {
        assertTrue(SkillExtras.matchesWeather("clear", false, false));
        assertTrue(SkillExtras.matchesWeather("thunderstorm", true, true));
        assertTrue(SkillExtras.matchesWeather("rain", true, false));
        assertFalse(SkillExtras.matchesWeather("rain", true, true));
    }

    @Test
    void registersNamedTimeAndWeatherAliasesThroughTheConditionCatalog() {
        World world = mock(World.class);
        when(world.getTime()).thenReturn(6_000L);
        when(world.hasStorm()).thenReturn(true);
        when(world.isThundering()).thenReturn(false);
        Location location = new Location(world, 0, 64, 0);
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(location);
        BehaviorContext context = BehaviorContext.of(instance, null, 1.0, -1, 0, "IDLE");

        for (String name : List.of("day", "daytime", "dawn", "dusk", "sunset", "morning", "noon",
                "midnight", "nighttime", "sun", "sunny", "clear", "rain", "storm", "thunder",
                "thunderstorm")) {
            var factory = SkillExtras.conditions().get(name);
            assertTrue(factory != null, "missing condition alias: " + name);
            assertFalse(factory.create(List.of()).test(BehaviorContext.of(null, null,
                    1.0, -1, 0, "IDLE")), "missing context must fail closed: " + name);
        }

        assertTrue(SkillExtras.conditions().get("noon").create(List.of()).test(context));
        assertTrue(SkillExtras.conditions().get("rain").create(List.of()).test(context));
        assertFalse(SkillExtras.conditions().get("sunny").create(List.of()).test(context));
        assertFalse(SkillExtras.conditions().get("storm").create(List.of()).test(context));
    }

    @Test
    void snowingAliasesUseTheTargetLocationAndFailClosed() {
        World warmWorld = mock(World.class);
        when(warmWorld.hasStorm()).thenReturn(true);
        Block warmBlock = mock(Block.class);
        when(warmBlock.getTemperature()).thenReturn(0.2);
        when(warmWorld.getBlockAt(4, 70, 8)).thenReturn(warmBlock);
        Location casterLocation = new Location(warmWorld, 4, 70, 8);

        World coldWorld = mock(World.class);
        when(coldWorld.hasStorm()).thenReturn(true);
        Block coldBlock = mock(Block.class);
        when(coldBlock.getTemperature()).thenReturn(0.1);
        when(coldWorld.getBlockAt(12, 72, 16)).thenReturn(coldBlock);
        Location targetLocation = new Location(coldWorld, 12, 72, 16);

        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(casterLocation);
        LivingEntity target = mock(LivingEntity.class);
        when(target.getLocation()).thenReturn(targetLocation);
        BehaviorContext context = BehaviorContext.of(instance, target, 1.0, -1, 0, "IDLE");

        for (String alias : List.of("snowing", "is-snowing")) {
            var factory = SkillExtras.conditions().get(alias);
            assertTrue(factory != null, "missing condition alias: " + alias);
            assertTrue(factory.create(List.of()).test(context),
                    "target cold location should be snowing: " + alias);
            assertFalse(factory.create(List.of()).test(BehaviorContext.of(null, null,
                    1.0, -1, 0, "IDLE")), "missing context must fail closed: " + alias);
            assertThrows(IllegalArgumentException.class,
                    () -> factory.create(List.of("unexpected=true")), alias);
        }

        assertFalse(SkillExtras.isSnowingAt(casterLocation), "warm locations must reject snow");
        when(coldBlock.getTemperature()).thenReturn(0.15);
        assertFalse(SkillExtras.isSnowingAt(targetLocation), "the rain/snow cutoff is exclusive");
        when(coldBlock.getTemperature()).thenReturn(0.1);
        when(coldWorld.hasStorm()).thenReturn(false);
        assertFalse(SkillExtras.isSnowingAt(targetLocation), "clear weather must reject snow");
        assertFalse(SkillExtras.isSnowingAt(new Location(null, 0, 64, 0)),
                "locations without a world must fail closed");
    }
}
