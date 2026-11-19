package dev.helstera.ai.spawner;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

class RandomSpawnServiceTest {
    private static final String CONFIG = """
            random-spawns:
              boss:
                action: REPLACE
                types:
                  mythic_wraith: 3
                  mythic_guard: 1
                chance: 1
                priority: 10
                cooldown: 2
                worlds: arena
                biomes: PLAINS
                reasons: NATURAL
            """;

    private static CreatureSpawnEvent event(World world, Biome biome,
                                               CreatureSpawnEvent.SpawnReason reason) {
        Location location = new Location(world, 12, 64, -4);
        LivingEntity entity = mock(LivingEntity.class);
        CreatureSpawnEvent event = mock(CreatureSpawnEvent.class);
        when(event.getLocation()).thenReturn(location);
        when(event.getEntity()).thenReturn(entity);
        when(event.getSpawnReason()).thenReturn(reason);
        when(world.getBiome(location)).thenReturn(biome);
        return event;
    }

    @Test
    @DisplayName("REPLACE filters natural spawns and cancels vanilla only after custom spawn succeeds")
    void replacesMatchingNaturalSpawn() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        List<String> spawned = new ArrayList<>();
        RandomSpawnService service = new RandomSpawnService(Logger.getLogger("RandomSpawnServiceTest"),
                (mob, location, ai) -> { spawned.add(mob); return 5; }, new java.util.Random(7), () -> 0L);
        service.load(YamlConfiguration.loadConfiguration(new StringReader(CONFIG)).getConfigurationSection("random-spawns"));
        CreatureSpawnEvent matching = event(world, Biome.PLAINS, CreatureSpawnEvent.SpawnReason.NATURAL);

        service.onCreatureSpawn(matching);

        assertEquals(1, spawned.size());
        assertTrue(List.of("mythic_wraith", "mythic_guard").contains(spawned.get(0)));
        verify(matching).setCancelled(true);

        CreatureSpawnEvent wrongWorld = event(world, Biome.PLAINS, CreatureSpawnEvent.SpawnReason.NATURAL);
        when(world.getName()).thenReturn("other");
        service.onCreatureSpawn(wrongWorld);
        verify(wrongWorld, org.mockito.Mockito.never()).setCancelled(true);
        assertEquals(1, spawned.size());
    }

    @Test
    @DisplayName("spawn reason and biome filters leave non-matching vanilla spawns untouched")
    void rejectsWrongBiomeAndReason() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        RandomSpawnService service = new RandomSpawnService(Logger.getLogger("RandomSpawnServiceTest"),
                (mob, location, ai) -> 1, new java.util.Random(1), () -> 0L);
        service.load(YamlConfiguration.loadConfiguration(new StringReader(CONFIG)).getConfigurationSection("random-spawns"));

        CreatureSpawnEvent wrongBiome = event(world, Biome.DESERT, CreatureSpawnEvent.SpawnReason.NATURAL);
        service.onCreatureSpawn(wrongBiome);
        verify(wrongBiome, org.mockito.Mockito.never()).setCancelled(true);

        CreatureSpawnEvent wrongReason = event(world, Biome.PLAINS, CreatureSpawnEvent.SpawnReason.SPAWNER);
        service.onCreatureSpawn(wrongReason);
        verify(wrongReason, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    @DisplayName("cooldown is consumed only by a successful replacement")
    void cooldownAndFailedSpawn() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        AtomicLong clock = new AtomicLong();
        int[] calls = {0};
        SpawnerService.MobSpawner spawner = (mob, location, ai) -> ++calls[0] == 1 ? -1 : 10;
        RandomSpawnService service = new RandomSpawnService(Logger.getLogger("RandomSpawnServiceTest"),
                spawner, new java.util.Random(2), clock::get);
        service.load(YamlConfiguration.loadConfiguration(new StringReader(CONFIG)).getConfigurationSection("random-spawns"));

        CreatureSpawnEvent failed = event(world, Biome.PLAINS, CreatureSpawnEvent.SpawnReason.NATURAL);
        service.onCreatureSpawn(failed);
        verify(failed, org.mockito.Mockito.never()).setCancelled(true);

        CreatureSpawnEvent succeeded = event(world, Biome.PLAINS, CreatureSpawnEvent.SpawnReason.NATURAL);
        service.onCreatureSpawn(succeeded);
        verify(succeeded).setCancelled(true);
        CreatureSpawnEvent cooledDown = event(world, Biome.PLAINS, CreatureSpawnEvent.SpawnReason.NATURAL);
        service.onCreatureSpawn(cooledDown);
        verify(cooledDown, org.mockito.Mockito.never()).setCancelled(true);
        assertEquals(2, calls[0]);

        clock.set(2_000_000_001L);
        CreatureSpawnEvent ready = event(world, Biome.PLAINS, CreatureSpawnEvent.SpawnReason.NATURAL);
        service.onCreatureSpawn(ready);
        verify(ready).setCancelled(true);
    }

    @Test
    @DisplayName("DENY cancels matching spawn events without creating a replacement")
    void deniesMatchingSpawnEvents() {
        String config = """
                random-spawns:
                  deny-zombies:
                    action: DENY
                    chance: 1
                    priority: 10
                    worlds: arena
                    biomes: PLAINS
                    reasons: NATURAL
                    conditions: "entity-type-is ZOMBIE"
                """;
        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        List<String> spawned = new ArrayList<>();
        RandomSpawnService service = new RandomSpawnService(Logger.getLogger("RandomSpawnServiceTest"),
                (mob, location, ai) -> { spawned.add(mob); return 1; }, new java.util.Random(1), () -> 0L);
        service.load(YamlConfiguration.loadConfiguration(new StringReader(config)).getConfigurationSection("random-spawns"));
        service.setConditionTester((condition, spawnEvent) ->
                "entity-type-is ZOMBIE".equals(condition)
                        && spawnEvent.getEntity().getType() == org.bukkit.entity.EntityType.ZOMBIE);

        CreatureSpawnEvent matching = event(world, Biome.PLAINS, CreatureSpawnEvent.SpawnReason.NATURAL);
        when(matching.getEntity().getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        service.onCreatureSpawn(matching);
        verify(matching).setCancelled(true);
        assertTrue(spawned.isEmpty());

        CreatureSpawnEvent wrongCondition = event(world, Biome.PLAINS, CreatureSpawnEvent.SpawnReason.NATURAL);
        when(wrongCondition.getEntity().getType()).thenReturn(org.bukkit.entity.EntityType.CREEPER);
        service.onCreatureSpawn(wrongCondition);
        verify(wrongCondition, org.mockito.Mockito.never()).setCancelled(true);

        CreatureSpawnEvent wrongReason = event(world, Biome.PLAINS, CreatureSpawnEvent.SpawnReason.SPAWNER);
        when(wrongReason.getEntity().getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        service.onCreatureSpawn(wrongReason);
        verify(wrongReason, org.mockito.Mockito.never()).setCancelled(true);
        assertEquals(1, service.size());
    }

    @Test
    @DisplayName("ADD is loaded as an opt-in rule while unknown actions and invalid pools fail closed")
    void addIsLoadedButOptIn() {
        String config = """
                random-spawns:
                  add:
                    action: ADD
                    types: {mythic_wraith: 1}
                  scale:
                    action: SCALE
                    types: {mythic_wraith: 1}
                  invalid:
                    action: REPLACE
                    types: {mythic_wraith: 0}
                """;
        RandomSpawnService service = new RandomSpawnService(Logger.getLogger("RandomSpawnServiceTest"),
                (mob, location, ai) -> 1, new java.util.Random(1), () -> 0L);
        service.load(YamlConfiguration.loadConfiguration(new StringReader(config)).getConfigurationSection("random-spawns"));
        assertEquals(1, service.size());
        assertEquals(1, service.addSize());
        assertEquals(3, service.warnings().size());
        assertEquals(0, service.tickAdd(List.of()));
    }

    @Test
    @DisplayName("CLUSTER ADD spawns a configured LAND mob in a loaded chunk near an eligible player")
    void clusterAddSpawnsLandMob() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(320);
        WorldBorder border = mock(WorldBorder.class);
        when(border.isInside(org.mockito.ArgumentMatchers.any(Location.class))).thenReturn(true);
        when(world.getWorldBorder()).thenReturn(border);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBiome(org.mockito.ArgumentMatchers.any(Location.class))).thenReturn(Biome.PLAINS);
        when(world.getEntities()).thenReturn(List.of());
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int y = invocation.getArgument(1);
            Material material = y <= 63 ? Material.STONE : Material.AIR;
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(material);
            when(block.isPassable()).thenReturn(material == Material.AIR);
            when(block.isLiquid()).thenReturn(false);
            return block;
        });
        Player player = player(world, GameMode.SURVIVAL);
        List<Location> spawnedAt = new ArrayList<>();
        List<String> spawnedTypes = new ArrayList<>();
        RandomSpawnService service = new RandomSpawnService(Logger.getLogger("RandomSpawnAddTest"),
                (mob, location, ai) -> { spawnedTypes.add(mob); spawnedAt.add(location); return 7; },
                new java.util.Random(5), () -> 0L);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                random-spawns:
                  add:
                    action: ADD
                    types: {mythic_wraith: 1}
                    chance: 1
                    worlds: arena
                    biomes: PLAINS
                    position-type: LAND
                    conditions: world-is arena
                """)).getConfigurationSection("random-spawns"));
        service.configureAdd(new RandomSpawnService.AddSettings("CLUSTER", 0, 8, 0, 1, 70));

        assertEquals(0, service.tickAdd(List.of(player)), "conditions fail closed without a candidate tester");
        List<String> testedConditions = new ArrayList<>();
        service.setAddConditionTester((condition, location) -> {
            testedConditions.add(condition);
            return "world-is arena".equals(condition) && location.getWorld() == world;
        });
        assertEquals(1, service.tickAdd(List.of(player)));
        assertEquals(1, spawnedAt.size());
        assertEquals(List.of("mythic_wraith"), spawnedTypes);
        assertEquals(List.of("world-is arena"), testedConditions);
        assertEquals(64.0, spawnedAt.getFirst().getY());
    }

    @Test
    @DisplayName("CLUSTER ADD never samples terrain beyond the world border")
    void clusterAddRejectsWorldBorderCandidates() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        WorldBorder border = mock(WorldBorder.class);
        when(border.isInside(org.mockito.ArgumentMatchers.any(Location.class))).thenReturn(false);
        when(world.getWorldBorder()).thenReturn(border);
        Player player = player(world, GameMode.SURVIVAL);
        int[] spawnAttempts = {0};
        RandomSpawnService service = new RandomSpawnService(Logger.getLogger("RandomSpawnBorderTest"),
                (mob, location, ai) -> { spawnAttempts[0]++; return 1; }, new java.util.Random(5), () -> 0L);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                random-spawns:
                  add:
                    action: ADD
                    types: {mythic_wraith: 1}
                    worlds: arena
                """)).getConfigurationSection("random-spawns"));
        service.configureAdd(new RandomSpawnService.AddSettings("CLUSTER", 2, 8, 0, 1, 70));

        assertEquals(0, service.tickAdd(List.of(player)));
        assertEquals(0, spawnAttempts[0]);
        verify(world, org.mockito.Mockito.never()).isChunkLoaded(anyInt(), anyInt());
    }

    @Test
    @DisplayName("ADD stays disabled for the default NONE generator and ignores spectator players")
    void addHonorsGeneratorAndPlayerMode() {
        World world = mock(World.class);
        Player player = player(world, GameMode.SPECTATOR);
        int[] attempts = {0};
        RandomSpawnService service = new RandomSpawnService(Logger.getLogger("RandomSpawnAddTest"),
                (mob, location, ai) -> { attempts[0]++; return 1; }, new java.util.Random(5), () -> 0L);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                random-spawns:
                  add:
                    action: ADD
                    types: {mythic_wraith: 1}
                """)).getConfigurationSection("random-spawns"));

        assertEquals(0, service.tickAdd(List.of(player)));
        service.configureAdd(new RandomSpawnService.AddSettings("CLUSTER", 0, 8, 0, 1, 70));
        assertEquals(0, service.tickAdd(List.of(player)));
        assertEquals(0, attempts[0]);
    }

    private static Player player(World world, GameMode mode) {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getGameMode()).thenReturn(mode);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        return player;
    }
}
