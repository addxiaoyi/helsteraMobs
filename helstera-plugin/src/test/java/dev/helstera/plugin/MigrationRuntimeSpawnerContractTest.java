package dev.helstera.plugin;

import dev.helstera.ai.spawner.SpawnerService;
import dev.helstera.ai.spawner.RandomSpawnService;
import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.migration.importer.MythicMobsImporter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.GameMode;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.entity.LivingEntity;
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
    @DisplayName("imported Mythic RandomSpawn Types become equally selectable runtime alternatives")
    void importedRandomSpawnTypesFlowThroughRuntime(@TempDir Path root) throws Exception {
        Path mobs = root.resolve("Mobs/alternatives.yml");
        Path source = root.resolve("RandomSpawns/alternatives.yml");
        Files.createDirectories(mobs.getParent());
        Files.createDirectories(source.getParent());
        Files.writeString(mobs, "first:\n  Type: ZOMBIE\nsecond:\n  Type: SKELETON\n");
        Files.writeString(source, """
                Alternatives:
                  Action: REPLACE
                  Types: first, second
                  Reason: NATURAL
                """);

        var plan = new MythicMobsImporter().convert(source, root).stream()
                .filter(entry -> "random-spawns.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        Path converted = root.resolve("generated/random-spawns.yml");
        Files.createDirectories(converted.getParent());
        Files.writeString(converted, String.valueOf(plan.get("content")));
        var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(converted.toFile());
        var alternatives = config.getConfigurationSection("random-spawns.alternatives.types");
        assertEquals(java.util.Map.of("mythic_first", 1.0, "mythic_second", 1.0), alternatives.getValues(false));

        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        var selected = new java.util.HashSet<String>();
        RandomSpawnService service = new RandomSpawnService(Logger.getLogger("RandomSpawnTypesContractTest"),
                (mobId, at, aiProfile) -> { selected.add(mobId); return 42; });
        service.loadFile(converted);
        for (int i = 0; i < 64; i++) {
            service.onCreatureSpawn(randomSpawnEvent(world, CreatureSpawnEvent.SpawnReason.NATURAL));
        }
        assertEquals(java.util.Set.of("mythic_first", "mythic_second"), selected);
    }

    private static CreatureSpawnEvent randomSpawnEvent(World world, CreatureSpawnEvent.SpawnReason reason) {
        Location location = new Location(world, 0, 64, 0);
        when(world.getBiome(location)).thenReturn(org.bukkit.block.Biome.PLAINS);
        var entity = mock(LivingEntity.class);
        var event = mock(CreatureSpawnEvent.class);
        when(event.getLocation()).thenReturn(location);
        when(event.getEntity()).thenReturn(entity);
        when(event.getSpawnReason()).thenReturn(reason);
        return event;
    }

    @Test
    @DisplayName("imported Mythic RandomSpawn ADD runs only through the opt-in bounded CLUSTER generator")
    void importedRandomSpawnAddFlowsThroughRuntime(@TempDir Path root) throws Exception {
        Path mob = root.resolve("Mobs/aquatic.yml");
        Path source = root.resolve("RandomSpawns/aquatic.yml");
        Files.createDirectories(mob.getParent());
        Files.createDirectories(source.getParent());
        Files.writeString(mob, "aquatic_boss:\n  Type: DROWNED\naquatic_guard:\n  Type: GUARDIAN\n");
        Files.writeString(source, """
                SeaBoss:
                  Action: ADD
                  Types: aquatic_boss, aquatic_guard
                  PositionType: SEA
                  Chance: 1
                  Worlds: arena
                  Biomes: OCEAN
                  Conditions:
                    - "?world{world=arena}"
                    - "?time{time=night}"
                    - "?weather{weather=clear}"
                    - "?playersinradius{radius=64;amount=>0}"
                    - "?playerwithin{d=64}"
                    - "?playernotwithin{d=1}"
                """);
        var plan = new MythicMobsImporter().convert(source, root).stream()
                .filter(entry -> "random-spawns.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        var importedYaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(java.util.Map.of("mythic_aquatic_boss", 1.0, "mythic_aquatic_guard", 1.0),
                importedYaml.getConfigurationSection("random-spawns.seaboss.types").getValues(false));
        Path converted = root.resolve("generated/random-spawns.yml");
        Files.createDirectories(converted.getParent());
        Files.writeString(converted, String.valueOf(plan.get("content")));

        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        when(world.getTime()).thenReturn(18_000L);
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(320);
        var border = mock(org.bukkit.WorldBorder.class);
        when(border.isInside(org.mockito.ArgumentMatchers.any(Location.class))).thenReturn(true);
        when(world.getWorldBorder()).thenReturn(border);
        when(world.isChunkLoaded(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(true);
        when(world.getBiome(org.mockito.ArgumentMatchers.any(Location.class))).thenReturn(Biome.OCEAN);
        when(world.getEntities()).thenReturn(java.util.List.of());
        Block water = mock(Block.class);
        when(water.getType()).thenReturn(Material.WATER);
        when(world.getBlockAt(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(water);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(world.getPlayers()).thenReturn(java.util.List.of(player));
        when(world.getNearbyEntities(org.mockito.ArgumentMatchers.any(Location.class),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble())).thenReturn(java.util.List.of(player));
        var spawned = new java.util.ArrayList<String>();
        var testedConditions = new java.util.ArrayList<String>();
        RandomSpawnService service = new RandomSpawnService(Logger.getLogger("MigrationRandomSpawnAddContractTest"),
                (mobId, at, aiProfile) -> { spawned.add(mobId); return 42; });
        service.loadFile(converted);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        service.setAddConditionTester((condition, at) -> {
            testedConditions.add(condition);
            String key = skills.bindCondition(condition);
            return key != null && registry.testCondition(key,
                    dev.helstera.api.behavior.BehaviorContext.execution(null, null, 1, -1, 0,
                            "random-spawn-add", null, null, java.util.List.of(), at));
        });

        assertEquals(1, service.addSize());
        assertEquals(0, service.tickAdd(java.util.List.of(player)));
        service.configureAdd(new RandomSpawnService.AddSettings("CLUSTER", 2, 8, 0, 1, 70));
        assertEquals(1, service.tickAdd(java.util.List.of(player)));
        assertEquals(1, spawned.size());
        assertTrue(java.util.Set.of("mythic_aquatic_boss", "mythic_aquatic_guard").contains(spawned.getFirst()));
        assertEquals(java.util.List.of("world-is arena", "time-is night", "weather-is clear",
                "players-in-radius >0 64 true", "player-within 64", "player-not-within 1"), testedConditions);

        // Keep the same eligible anchor, but remove the query result to exercise
        // the actual condition rather than the generator's eligibility filter.
        when(world.getNearbyEntities(org.mockito.ArgumentMatchers.any(Location.class),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble())).thenReturn(java.util.List.of());
        assertEquals(0, service.tickAdd(java.util.List.of(player)));
        when(world.getNearbyEntities(org.mockito.ArgumentMatchers.any(Location.class),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble())).thenThrow(new IllegalStateException("query unavailable"));
        assertEquals(0, service.tickAdd(java.util.List.of(player)));
        assertEquals(1, spawned.size(), "failed and empty queries must never reach the spawn adapter");
    }

    @Test
    @DisplayName("imported Mythic RandomSpawn REPLACE reaches the event-driven runtime")
    void importedRandomSpawnReplaceFlowsThroughRuntime(@TempDir Path root) throws Exception {
        Path mob = root.resolve("Mobs/boss.yml");
        Path source = root.resolve("RandomSpawns/boss.yml");
        Files.createDirectories(mob.getParent());
        Files.createDirectories(source.getParent());
        Files.writeString(mob, "boss:\n  Type: ZOMBIE\n");
        Files.writeString(source, """
                NightBoss:
                  Action: REPLACE
                  Type: boss
                  Chance: 1
                  Worlds: arena
                  Biomes: PLAINS
                  Reason: NATURAL
                  Conditions:
                    - "entitytype{t=SKELETON}"
                """);
        var plan = new MythicMobsImporter().convert(source, root).stream()
                .filter(entry -> "random-spawns.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        Path converted = root.resolve("generated/random-spawns.yml");
        Files.createDirectories(converted.getParent());
        Files.writeString(converted, String.valueOf(plan.get("content")));

        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        Location location = new Location(world, 0, 64, 0);
        when(world.getBiome(location)).thenReturn(Biome.PLAINS);
        LivingEntity vanilla = mock(LivingEntity.class);
        when(vanilla.getType()).thenReturn(org.bukkit.entity.EntityType.SKELETON);
        var event = mock(CreatureSpawnEvent.class);
        when(event.getEntity()).thenReturn(vanilla);
        when(event.getLocation()).thenReturn(location);
        when(event.getSpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.NATURAL);

        var spawned = new java.util.ArrayList<String>();
        RandomSpawnService service = new RandomSpawnService(Logger.getLogger("MigrationRandomSpawnContractTest"),
                (mobId, at, aiProfile) -> { spawned.add(mobId); return 42; });
        service.loadFile(converted);
        service.setConditionTester((condition, spawnEvent) ->
                "entity-type-is SKELETON".equals(condition)
                        && spawnEvent.getEntity().getType() == org.bukkit.entity.EntityType.SKELETON);
        service.onCreatureSpawn(event);

        assertEquals(1, service.size());
        assertEquals(java.util.List.of("mythic_boss"), spawned);
        org.mockito.Mockito.verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("imported Mythic RandomSpawn DENY cancels only matching creature events")
    void importedRandomSpawnDenyFlowsThroughRuntime(@TempDir Path root) throws Exception {
        Path source = root.resolve("RandomSpawns/deny.yml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                DenyZombies:
                  Action: DENY
                  Chance: 1
                  Worlds: arena
                  Biomes: PLAINS
                  Reason: NATURAL
                  Conditions:
                    - "entitytype{t=ZOMBIE}"
                """);
        var plan = new MythicMobsImporter().convert(source, root).stream()
                .filter(entry -> "random-spawns.yml".equals(entry.get("target-file")))
                .findFirst().orElseThrow();
        Path converted = root.resolve("generated/random-spawns.yml");
        Files.createDirectories(converted.getParent());
        Files.writeString(converted, String.valueOf(plan.get("content")));

        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        Location location = new Location(world, 0, 64, 0);
        when(world.getBiome(location)).thenReturn(Biome.PLAINS);
        var spawned = new java.util.concurrent.atomic.AtomicInteger();
        RandomSpawnService service = new RandomSpawnService(Logger.getLogger("MigrationRandomSpawnDenyContractTest"),
                (mobId, at, aiProfile) -> spawned.incrementAndGet());
        service.loadFile(converted);
        service.setConditionTester((condition, spawnEvent) ->
                "entity-type-is ZOMBIE".equals(condition)
                        && spawnEvent.getEntity().getType() == org.bukkit.entity.EntityType.ZOMBIE);

        var matchingEntity = mock(LivingEntity.class);
        when(matchingEntity.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        var matching = mock(CreatureSpawnEvent.class);
        when(matching.getEntity()).thenReturn(matchingEntity);
        when(matching.getLocation()).thenReturn(location);
        when(matching.getSpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.NATURAL);
        service.onCreatureSpawn(matching);
        org.mockito.Mockito.verify(matching).setCancelled(true);
        assertEquals(0, spawned.get());

        var wrongConditionEntity = mock(LivingEntity.class);
        when(wrongConditionEntity.getType()).thenReturn(org.bukkit.entity.EntityType.CREEPER);
        var wrongCondition = mock(CreatureSpawnEvent.class);
        when(wrongCondition.getEntity()).thenReturn(wrongConditionEntity);
        when(wrongCondition.getLocation()).thenReturn(location);
        when(wrongCondition.getSpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.NATURAL);
        service.onCreatureSpawn(wrongCondition);
        org.mockito.Mockito.verify(wrongCondition, org.mockito.Mockito.never()).setCancelled(true);

        var wrongReason = mock(CreatureSpawnEvent.class);
        when(wrongReason.getEntity()).thenReturn(matchingEntity);
        when(wrongReason.getLocation()).thenReturn(location);
        when(wrongReason.getSpawnReason()).thenReturn(CreatureSpawnEvent.SpawnReason.SPAWNER);
        service.onCreatureSpawn(wrongReason);
        org.mockito.Mockito.verify(wrongReason, org.mockito.Mockito.never()).setCancelled(true);
        assertEquals(1, service.size());
    }

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
