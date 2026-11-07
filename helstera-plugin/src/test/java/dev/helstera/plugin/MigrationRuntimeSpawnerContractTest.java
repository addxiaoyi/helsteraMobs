package dev.helstera.plugin;

import dev.helstera.ai.spawner.SpawnerService;
import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.migration.importer.MythicMobsImporter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MigrationRuntimeSpawnerContractTest {

    @Test
    @DisplayName("imported Mythic Spawner Chance and GroupSize reach runtime")
    void importedChanceFlowsThroughRuntime(@TempDir Path root) throws Exception {
        Path source = root.resolve("Spawners/chance.yml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                spawners:
                  valid:
                    Mob: boss
                    X: 0
                    Z: 0
                    Chance: 1.0
                    GroupSize: 3
                    MaxMobs: 2
                  invalid:
                    Mob: boss
                    X: 0
                    Z: 0
                    Chance: 1.5
                    GroupSize: 2-4
                """);

        var plan = new MythicMobsImporter().convert(source).stream()
                .filter(entry -> "spawners.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        assertEquals("mapped", plan.get("status"));
        Path converted = root.resolve("generated/spawners.yml");
        Files.createDirectories(converted.getParent());
        Files.writeString(converted, String.valueOf(plan.get("content")));

        var service = new SpawnerService(Logger.getLogger("SpawnerContractTest"),
                (mob, location, aiProfile) -> 1, world -> null);
        service.loadFile(converted);

        assertEquals(1.0, service.get("valid").chance());
        assertEquals(3, service.get("valid").groupMin());
        assertEquals(3, service.get("valid").groupMax());
        assertEquals(2, service.get("valid").maxAlive());
        assertEquals(0.0, service.get("invalid").chance());
        assertEquals(2, service.get("invalid").groupMin());
        assertEquals(4, service.get("invalid").groupMax());
        assertTrue(service.warnings().stream().anyMatch(message ->
                message.contains("invalid") && message.toLowerCase(java.util.Locale.ROOT).contains("chance")));
    }

    @Test
    @DisplayName("imported Mythic weather and world conditions reach SpawnerService")
    void importedWeatherConditionsFlowThroughRuntime(@TempDir Path root) throws Exception {
        Path source = root.resolve("Spawners/weather.yml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                spawners:
                  rain:
                    Mob: boss
                    X: 0
                    Z: 0
                    Conditions:
                      - isRaining
                      - "!isThundering"
                      - "World{w=arena}"
                  thunder:
                    Mob: boss
                    X: 0
                    Z: 0
                    Conditions:
                      - isThundering
                  wrong_world:
                    Mob: boss
                    X: 0
                    Z: 0
                    Conditions:
                      - "World{w=missing-world}"
                  plains:
                    Mob: boss
                    X: 0
                    Z: 0
                    Conditions:
                      - "BiomeType{type=plains;exact=false}"
                  desert:
                    Mob: boss
                    X: 0
                    Z: 0
                    Conditions:
                      - "BiomeType{type=desert;exact=false}"
                """);

        var plan = new MythicMobsImporter().convert(source).stream()
                .filter(entry -> "spawners.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        assertEquals("mapped", plan.get("status"));
        Path converted = root.resolve("generated/spawners.yml");
        Files.createDirectories(converted.getParent());
        Files.writeString(converted, String.valueOf(plan.get("content")));

        var spawned = new java.util.concurrent.atomic.AtomicInteger();
        var service = new SpawnerService(Logger.getLogger("SpawnerWeatherContractTest"),
                (mob, location, aiProfile) -> spawned.incrementAndGet(), world -> null);
        service.loadFile(converted);
        service.setAliveCheck(id -> false);
        var behaviorRegistry = new BehaviorRegistryImpl();
        var skillService = new SkillService(behaviorRegistry, null);
        service.setConditionTester((condition, location) -> {
            String key = skillService.bindCondition(condition);
            if (key == null) return false;
            var context = dev.helstera.api.behavior.BehaviorContext.execution(
                    null, null, 1.0, -1, 0, "spawner", null, null, java.util.List.of(), location);
            return behaviorRegistry.testCondition(key, context);
        });

        assertEquals(java.util.List.of("raining", "!thundering", "world-is arena"),
                service.get("rain").conditions());
        assertEquals(java.util.List.of("thundering"), service.get("thunder").conditions());
        assertEquals(java.util.List.of("world-is missing-world"), service.get("wrong_world").conditions());
        assertEquals(java.util.List.of("biome-type-is plains false"), service.get("plains").conditions());
        assertEquals(java.util.List.of("biome-type-is desert false"), service.get("desert").conditions());

        World world = mock(World.class);
        Block block = mock(Block.class);
        when(world.getName()).thenReturn("arena");
        when(world.hasStorm()).thenReturn(true);
        when(world.isThundering()).thenReturn(false);
        when(world.getBlockAt(org.mockito.ArgumentMatchers.any(Location.class))).thenReturn(block);
        when(world.getBlockAt(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(block);
        when(block.getBiome()).thenReturn(Biome.SUNFLOWER_PLAINS);
        Location at = new Location(world, 0, 64, 0);
        String plainsCondition = skillService.bindCondition("biome-type-is plains false");
        assertTrue(behaviorRegistry.testCondition(plainsCondition,
                dev.helstera.api.behavior.BehaviorContext.execution(
                        null, null, 1.0, -1, 0, "spawner", null, null, java.util.List.of(), at)));
        assertTrue(service.forceSpawn("rain", at));
        assertFalse(service.forceSpawn("thunder", at));
        assertFalse(service.forceSpawn("wrong_world", at));
        assertTrue(service.forceSpawn("plains", at), service.forceSpawnFailure("plains"));
        assertFalse(service.forceSpawn("desert", at));
        assertEquals(2, spawned.get());
        assertEquals(1, service.get("rain").totalSpawned());
        assertEquals(0, service.get("thunder").totalSpawned());
        assertEquals(0, service.get("wrong_world").totalSpawned());
        assertEquals(1, service.get("plains").totalSpawned());
        assertEquals(0, service.get("desert").totalSpawned());
    }

    @Test
    @DisplayName("imported Mythic RandomMobs weights reach SpawnerService forceSpawn")
    void importedRandomMobPoolFlowsThroughRuntime(@TempDir Path root) throws Exception {
        Path source = root.resolve("Spawners/random.yml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                spawners:
                  arena:
                    Mob: fallback
                    X: 0
                    Z: 0
                    RandomMobs:
                      zombie: 1
                      skeleton: 3
                """);

        var plan = new MythicMobsImporter().convert(source).stream()
                .filter(entry -> "spawners.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        assertEquals("mapped", plan.get("status"));
        Path converted = root.resolve("generated/spawners.yml");
        Files.createDirectories(converted.getParent());
        Files.writeString(converted, String.valueOf(plan.get("content")));

        var spawnedMobs = new java.util.ArrayList<String>();
        var service = new SpawnerService(Logger.getLogger("SpawnerRandomPoolContractTest"),
                (mob, location, aiProfile) -> {
                    spawnedMobs.add(mob);
                    return 1;
                }, world -> null);
        service.loadFile(converted);
        service.setAliveCheck(id -> false);

        var spawner = service.get("arena");
        assertTrue(spawner != null && spawner.randomPool() != null);
        assertEquals(0.25, spawner.expectedDistribution().get("zombie"), 1e-9);
        assertEquals(0.75, spawner.expectedDistribution().get("skeleton"), 1e-9);
        for (int i = 0; i < 12; i++) {
            assertTrue(service.forceSpawn("arena", new org.bukkit.Location(null, 0, 64, 0)));
        }
        assertEquals(12, spawner.totalSpawned());
        assertEquals(12, spawnedMobs.size());
        assertTrue(spawnedMobs.stream().allMatch(mob ->
                "zombie".equals(mob) || "skeleton".equals(mob)));
        assertEquals(12, spawner.mobSpawnCounts().values().stream()
                .mapToInt(Integer::intValue).sum());
    }

    @Test
    @DisplayName("imported RandomMobs selects the generated IDs for source Mob definitions")
    void importedRandomMobPoolUsesResolvedMobIds(@TempDir Path root) throws Exception {
        Path mobs = root.resolve("Mobs/arena.yml");
        Path source = root.resolve("Spawners/random.yml");
        Files.createDirectories(mobs.getParent());
        Files.createDirectories(source.getParent());
        Files.writeString(mobs, """
                mobs:
                  Emberling:
                    Type: ZOMBIE
                  EmberlingBoss:
                    Type: SKELETON
                """);
        Files.writeString(source, """
                spawners:
                  arena:
                    Mob: Emberling
                    X: 0
                    Z: 0
                    RandomMobs:
                      Emberling: 1
                      EmberlingBoss: 3
                """);

        var plan = new MythicMobsImporter().convert(source, root).stream()
                .filter(entry -> "spawners.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        Path converted = root.resolve("generated/spawners.yml");
        Files.createDirectories(converted.getParent());
        Files.writeString(converted, String.valueOf(plan.get("content")));

        var spawnedMobs = new java.util.ArrayList<String>();
        var service = new SpawnerService(Logger.getLogger("SpawnerResolvedMobContractTest"),
                (mob, location, aiProfile) -> {
                    spawnedMobs.add(mob);
                    return 1;
                }, world -> null);
        service.loadFile(converted);
        service.setAliveCheck(id -> false);

        assertEquals("mythic_emberling", service.get("arena").mobId());
        assertEquals(0.25, service.get("arena").expectedDistribution().get("mythic_emberling"), 1e-9);
        assertEquals(0.75, service.get("arena").expectedDistribution().get("mythic_emberlingboss"), 1e-9);
        for (int i = 0; i < 12; i++) {
            assertTrue(service.forceSpawn("arena", new org.bukkit.Location(null, 0, 64, 0)));
        }
        assertEquals(12, spawnedMobs.size());
        assertTrue(spawnedMobs.stream().allMatch(mob ->
                "mythic_emberling".equals(mob) || "mythic_emberlingboss".equals(mob)));
    }
}
