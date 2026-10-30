package dev.helstera.ai;

import dev.helstera.ai.spawner.SpawnerService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * 刷怪点配置解析的测试。
 *
 * <p>只测 {@code load} 的解析与校验部分。生成逻辑（随机取点、地面吸附、
 * 上限门控）需要 Bukkit 的 World/BukkitTask，装不上去测不了，
 * 那些路径依赖运行时验证而不是单测。</p>
 */
class SpawnerServiceTest {

    /** 无需 Bukkit 调度器的裸服务：plugin 传 null，任何真正调度/取世界的调用都会 NPE。 */
    private static SpawnerService service(String yaml) {
        List<String> spawned = new ArrayList<>();
        SpawnerService s = new SpawnerService(null, (mob, loc, aiProfile) -> {
            spawned.add(mob);
            return spawned.size();
        });
        s.load(YamlConfiguration.loadConfiguration(new StringReader(yaml)).getConfigurationSection("spawners"));
        return s;
    }

    @Test
    @DisplayName("spawner diagnostics expose the remaining automatic cooldown")
    void exposesNextSpawnCooldown() {
        SpawnerService service = service("""
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    z: 0
                """);
        assertEquals(0, service.nextSpawnInTicks("arena"));
        service.tickOnce();
        assertEquals(200, service.nextSpawnInTicks("arena"));
        service.tickOnce();
        assertEquals(199, service.nextSpawnInTicks("arena"));
        assertEquals(-1, service.nextSpawnInTicks("missing"));
    }

    @Test
    @DisplayName("shared scheduler advances cooldown by real elapsed ticks")
    void sharedSchedulerUsesElapsedTicks() {
        SpawnerService service = service("""
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    z: 0
                    interval: 200
                """);

        service.tickOnce(40);
        assertEquals(200, service.nextSpawnInTicks("arena"));
        service.tickOnce(40);
        assertEquals(160, service.nextSpawnInTicks("arena"));
    }

    @Test
    @DisplayName("config reload keeps alive instance tracking")
    void reloadKeepsAliveInstanceTracking() {
        String yaml = """
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    z: 0
                    max-alive: 3
                """;
        SpawnerService service = service(yaml);
        service.setAliveCheck(id -> true);
        assertTrue(service.forceSpawn("arena", new Location(null, 0, 64, 0)));
        assertEquals(1, service.aliveCount("arena"));

        service.load(YamlConfiguration.loadConfiguration(new StringReader(yaml))
                .getConfigurationSection("spawners"));

        assertEquals(1, service.aliveCount("arena"));
    }

    @Test
    @DisplayName("解析基础字段，间隔低于 20 被抬到 20")
    void parsesBasicFields() {
        SpawnerService s = service("""
                spawners:
                  cave:
                    mob: crystal_boss
                    world: world
                    x: 100.5
                    y: 64
                    z: -20.5
                    radius: 8.5
                    interval: 5
                    max-alive: 4
                """);
        assertEquals(1, s.size());
        var sp = s.get("cave");
        assertNotNull(sp);
        assertEquals("crystal_boss", sp.mobId());
        assertEquals("world", sp.world());
        assertEquals(8.5, sp.radius(), 1e-9);
        assertEquals(20, sp.intervalTicks(), "interval<20 应抬到 20");
        assertEquals(4, sp.maxAlive());
        assertTrue(sp.enabled());
    }

    @Test
    @DisplayName("缺少 mob 的刷怪点被跳过并告警")
    void skipsSpawnerWithoutMob() {
        SpawnerService s = service("""
                spawners:
                  bad:
                    x: 0
                    z: 0
                  good:
                    mob: a
                    x: 0
                    z: 0
                """);
        assertEquals(1, s.size());
        assertNotNull(s.get("good"));
        assertNull(s.get("bad"));
        assertEquals(1, s.warnings().size());
        assertTrue(s.warnings().get(0).contains("缺少 mob"));
    }

    @Test
    @DisplayName("缺少 x/z 的刷怪点被跳过并告警")
    void skipsSpawnerWithoutCoordinates() {
        SpawnerService s = service("""
                spawners:
                  bad:
                    mob: a
                  good:
                    mob: a
                    x: 1
                    z: 2
                """);
        assertEquals(1, s.size());
        assertNull(s.get("bad"));
        assertTrue(s.warnings().get(0).contains("坐标"));
    }

    @Test
    @DisplayName("max-alive 至少为 1，避免配成 0 导致永远不刷")
    void clampsMaxAliveToAtLeastOne() {
        SpawnerService s = service("""
                spawners:
                  t:
                    mob: a
                    x: 0
                    z: 0
                    max-alive: 0
                """);
        assertEquals(1, s.get("t").maxAlive());
    }

    @Test
    @DisplayName("min-players 负值被归零")
    void clampsMinPlayersToZero() {
        SpawnerService s = service("""
                spawners:
                  t:
                    mob: a
                    x: 0
                    z: 0
                    min-players: -5
                """);
        assertEquals(0, s.get("t").minPlayers());
    }

    @Test
    @DisplayName("y-range 负值被归零")
    void clampsYRangeToZero() {
        SpawnerService s = service("""
                spawners:
                  t:
                    mob: a
                    x: 0
                    z: 0
                    y-range: -3
                """);
        assertEquals(0.0, s.get("t").yRange(), 1e-9);
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("y-range")));
    }

    @Test
    @DisplayName("players-radius 有独立默认值，不随 min-players 变化")
    void playersRadiusIndependentOfMinPlayers() {
        SpawnerService s = service("""
                spawners:
                  three:
                    mob: a
                    x: 0
                    z: 0
                    min-players: 3
                  eight:
                    mob: a
                    x: 0
                    z: 0
                    min-players: 8
                """);
        // 半径是「在多大范围内数人」，与「需要几名玩家」是两个维度。
        // 早前把它写成 min*min*16，等于门槛越严判定范围越大。
        assertEquals(24.0, s.get("three").playersRadius(), 1e-9);
        assertEquals(24.0, s.get("eight").playersRadius(), 1e-9,
                "两名刷怪点的判定半径不应因 min-players 不同而变化");
    }

    @Test
    @DisplayName("players-radius 可显式配置，负值被归零")
    void readsAndClampsPlayersRadius() {
        SpawnerService s = service("""
                spawners:
                  custom:
                    mob: a
                    x: 0
                    z: 0
                    players-radius: 40
                  bad:
                    mob: a
                    x: 0
                    z: 0
                    min-players: 0
                    players-radius: -8
                """);
        assertEquals(40.0, s.get("custom").playersRadius(), 1e-9);
        assertEquals(0.0, s.get("bad").playersRadius(), 1e-9,
                "负半径应归零；此时人数门控恒不满足，而不是抛异常");

        SpawnerService gated = service("""
                spawners:
                  gated:
                    mob: a
                    x: 0
                    z: 0
                    min-players: 1
                    players-radius: 0
                """);
        assertEquals(24.0, gated.get("gated").playersRadius(), 1e-9);
        assertTrue(gated.warnings().stream().anyMatch(w -> w.contains("players-radius")));
    }

    @Test
    @DisplayName("Mythic player-distance aliases the runtime player gate radius")
    void readsMythicPlayerDistanceAlias() {
        SpawnerService s = service("""
                spawners:
                  mythic:
                    mob: a
                    x: 0
                    z: 0
                    min-players: 1
                    player-distance: 32
                """);

        assertEquals(32.0, s.get("mythic").playersRadius(), 1e-9);
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("player-distance")));
    }

    @Test
    @DisplayName("Mythic cooldown aliases the automatic interval")
    void readsMythicCooldownAlias() {
        SpawnerService s = service("""
                spawners:
                  mythic:
                    mob: a
                    x: 0
                    z: 0
                    cooldown: 40
                """);

        assertEquals(40, s.get("mythic").intervalTicks());
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("cooldown")));
    }

    @Test
    @DisplayName("scoped world limits apply across spawners in the same world")
    void scopedWorldLimitAppliesAcrossSpawners() {
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> 1);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  first:
                    mob: a
                    x: 0
                    z: 0
                    max-per-world: 1
                  second:
                    mob: b
                    x: 10
                    z: 0
                    max-per-world: 1
                """)).getConfigurationSection("spawners"));
        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        Location first = new Location(world, 0, 64, 0);
        Location second = new Location(world, 10, 64, 0);

        assertTrue(service.forceSpawn("first", first));
        assertFalse(service.forceSpawn("second", second));
        assertEquals("scoped spawn limits reached", service.forceSpawnFailure("second"));
        assertEquals(1, service.get("first").maxPerWorld());
    }

    @Test
    @DisplayName("scoped limit aliases are preserved in the loaded model")
    void scopedLimitAliasesLoad() {
        SpawnerService service = service("""
                spawners:
                  arena:
                    mob: a
                    x: 0
                    z: 0
                    per-world-limit: 3
                    per-player-limit: 2
                """);
        assertEquals(3, service.get("arena").maxPerWorld());
        assertEquals(2, service.get("arena").maxPerPlayer());
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("per-world-limit")));
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("per-player-limit")));
    }

    @Test
    @DisplayName("player-scoped probes require an online owner in range")
    void playerOwnerPresenceUsesConfiguredRadius() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("arena");
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.isDead()).thenReturn(false);
        when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        when(player.getLocation()).thenReturn(new Location(world, 2, 64, 0));
        when(world.getPlayers()).thenReturn(java.util.List.of(player));

        SpawnerService service = service("""
                spawners:
                  arena:
                    mob: a
                    x: 0
                    z: 0
                    max-per-player: 1
                    players-radius: 4
                """);
        assertTrue(service.playerOwnerPresent("arena", new Location(world, 0, 64, 0)));
        when(player.getLocation()).thenReturn(new Location(world, 8, 64, 0));
        assertFalse(service.playerOwnerPresent("arena", new Location(world, 0, 64, 0)));
    }

    @Test
    @DisplayName("scoped spawn counters survive state round trip")
    void scopedCountersSurviveStateRoundTrip() throws Exception {
        String config = """
                spawners:
                  arena:
                    mob: a
                    x: 0
                    z: 0
                    max-per-world: 1
                """;
        World world = mock(World.class);
        when(world.getName()).thenReturn("arena-world");
        Location point = new Location(world, 0, 64, 0);
        SpawnerService first = service(config);
        assertTrue(first.forceSpawn("arena", point));
        File state = Files.createTempFile("scoped-spawner", ".yml").toFile();
        first.saveState(state);

        YamlConfiguration saved = YamlConfiguration.loadConfiguration(state);
        assertEquals(1, saved.getInt("world-spawn-counts.arena-world"));

        SpawnerService restored = service(config);
        restored.loadState(state);
        assertFalse(restored.forceSpawn("arena", point));
        assertEquals("scoped spawn limits reached", restored.forceSpawnFailure("arena"));
        Files.deleteIfExists(state.toPath());
        Files.deleteIfExists(new File(state.getPath() + ".bak").toPath());
    }

    @Test
    @DisplayName("lunar phase conditions follow the vanilla eight-day cycle")
    void lunarPhaseConditionUsesFullTime() {
        World world = mock(World.class);
        when(world.getFullTime()).thenReturn(0L);
        SpawnerService fullMoon = service("""
                spawners:
                  lunar:
                    mob: a
                    x: 0
                    z: 0
                    conditions: [lunar-phase-is=FULL]
                """);
        assertTrue(fullMoon.forceSpawn("lunar", new Location(world, 0, 64, 0)));

        when(world.getFullTime()).thenReturn(4L * 24_000L);
        SpawnerService newMoon = service("""
                spawners:
                  lunar:
                    mob: a
                    x: 0
                    z: 0
                    conditions: [moon-phase-is=NEW]
                """);
        assertTrue(newMoon.forceSpawn("lunar", new Location(world, 0, 64, 0)));
        assertEquals("WAXING_GIBBOUS", SpawnerService.lunarPhase(7L * 24_000L));
    }

    @Test
    @DisplayName("enabled=false 被正确读取")
    void readsEnabledFlag() {
        // 注意：键名不能用 on/off——YAML 1.1 会把它们解析成布尔值，键名变成 "true"/"false"
        SpawnerService s = service("""
                spawners:
                  alpha:
                    mob: a
                    x: 0
                    z: 0
                  beta:
                    mob: a
                    x: 0
                    z: 0
                    enabled: false
                """);
        assertTrue(s.get("alpha").enabled());
        assertFalse(s.get("beta").enabled());
    }

    @Test
    @DisplayName("max-spawns 默认为 0（不限）")
    void maxSpawnsDefaultsToUnlimited() {
        SpawnerService s = service("""
                spawners:
                  t:
                    mob: a
                    x: 0
                    z: 0
                """);
        assertEquals(0, s.get("t").maxSpawns());
    }

    @Test
    @DisplayName("刷怪点 ID 大小写不敏感")
    void idsAreCaseInsensitive() {
        SpawnerService s = service("""
                spawners:
                  Cave_Spawn:
                    mob: a
                    x: 0
                    z: 0
                """);
        assertNotNull(s.get("cave_spawn"));
        assertNotNull(s.get("CAVE_SPAWN"));
    }

    @Test
    @DisplayName("full spawners document keeps named regions and spawner entries together")
    void acceptsFullSpawnerDocument() {
        SpawnerService service = new SpawnerService(null, (mob, location, aiProfile) -> 1);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader("""
                regions:
                  arena:
                    world: world
                    x1: -10
                    y1: 0
                    z1: -10
                    x2: 10
                    y2: 100
                    z2: 10
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    y: 64
                    z: 0
                    conditions:
                      - region=arena
                """));

        service.load(config);

        assertEquals(1, service.size());
        assertNotNull(service.get("arena"));
        assertTrue(service.warnings().stream().noneMatch(w -> w.contains("spawners missing mob")));
    }

    @Test
    @DisplayName("null 节与空节安全返回，不抛异常")
    void toleratesNullAndEmpty() {
        SpawnerService s = new SpawnerService(null, (m, l, a) -> -1);
        s.load(null);
        assertEquals(0, s.size());
        s.load(YamlConfiguration.loadConfiguration(new StringReader("")).getConfigurationSection("spawners"));
        assertEquals(0, s.size());
        assertTrue(s.warnings().isEmpty());
    }

    @Test
    @DisplayName("重复 load 会清空旧定义，不残留已删除的刷怪点")
    void reloadClearsPreviousSpawners() {
        SpawnerService s = new SpawnerService(null, (m, l, a) -> -1);
        s.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  old:
                    mob: a
                    x: 0
                    z: 0
                """)).getConfigurationSection("spawners"));
        assertNotNull(s.get("old"));

        s.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  fresh:
                    mob: b
                    x: 0
                    z: 0
                """)).getConfigurationSection("spawners"));
        assertNull(s.get("old"), "旧刷怪点应被清掉");
        assertNotNull(s.get("fresh"));
    }

    @Test
    @DisplayName("ids 反映已装载的刷怪点")
    void listsSpawnerIds() {
        SpawnerService s = service("""
                spawners:
                  a:
                    mob: m1
                    x: 0
                    z: 0
                  b:
                    mob: m2
                    x: 0
                    z: 0
                """);
        assertEquals(2, s.ids().size());
        assertTrue(s.ids().containsAll(List.of("a", "b")));
    }

    @Test
    @DisplayName("未知刷怪点返回 null")
    void unknownSpawnerIsNull() {
        SpawnerService s = service("""
                spawners:
                  a:
                    mob: m
                    x: 0
                    z: 0
                """);
        assertNull(s.get("nope"));
        assertNull(s.get(null));
    }

    // ------------------------------------------------------------------
    // 运行期 toggle
    // ------------------------------------------------------------------

    /**
     * 键名刻意避开 {@code on}/{@code off}/{@code yes}/{@code no}：
     * YAML 1.1 把这些当布尔字面量，{@code on:} 会被解析成键 {@code "true"}，
     * 于是 {@code get("on")} 返回 null——一个只在测试里出现、却极难归因的坑。
     */
    private static SpawnerService toggleService() {
        return service("""
                spawners:
                  alpha:
                    mob: m
                    x: 0
                    z: 0
                    enabled: true
                  beta:
                    mob: m
                    x: 0
                    z: 0
                    enabled: false
                """);
    }

    @Test
    @DisplayName("toggle 翻转运行期状态，两次回到原位")
    void toggleFlipsAndRestores() {
        var s = toggleService();
        var alpha = s.get("alpha");
        assertTrue(s.isActive(alpha), "YAML enabled: true 应视为启用");

        assertEquals(Boolean.FALSE, s.toggle("alpha"), "第一次 toggle 应关闭");
        assertFalse(s.isActive(alpha));

        assertEquals(Boolean.TRUE, s.toggle("alpha"), "第二次 toggle 应恢复");
        assertTrue(s.isActive(alpha));
    }

    @Test
    @DisplayName("toggle 不改写 YAML 的 enabled，reload 后回到配置值")
    void toggleDoesNotMutateYamlIntent() {
        var s = toggleService();
        var alpha = s.get("alpha");
        s.toggle("alpha");
        assertFalse(s.isActive(alpha), "运行期应停用");
        assertTrue(alpha.enabled(), "但 Spawner.enabled() 必须仍反映 YAML 的作者意图");

        // reload 会清空运行期覆盖集
        s.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  alpha:
                    mob: m
                    x: 0
                    z: 0
                    enabled: true
                """)).getConfigurationSection("spawners"));
        assertTrue(s.isActive(s.get("alpha")), "reload 后应回到 YAML 的 enabled: true");
    }

    @Test
    @DisplayName("YAML 里本就停用的点，toggle 回来仍是停用")
    void yamlDisabledStaysDisabledAfterToggle() {
        var s = toggleService();
        var beta = s.get("beta");
        assertFalse(s.isActive(beta), "enabled: false 本就不该产出");

        // toggle 返回的是「翻转后是否启用」，而非「运行期覆盖是否被解除」。
        // 对 enabled:false 的点，解除覆盖也仍然不产出——返回值必须如实为 false，
        // 否则命令会骗管理员说「已启用」。
        assertEquals(Boolean.FALSE, s.toggle("beta"));
        assertFalse(s.isActive(beta), "toggle 只能叠加在 YAML 之上，不能覆盖它");

        assertEquals(Boolean.FALSE, s.toggle("beta"));
        assertFalse(s.isActive(beta));
    }

    @Test
    @DisplayName("toggle 对大小写不敏感的 id 同样生效")
    void toggleNormalizesIdCase() {
        var s = toggleService();
        assertEquals(Boolean.FALSE, s.toggle("ALPHA"), "id 应按与 get() 相同的方式归一化");
        assertFalse(s.isActive(s.get("alpha")));
        assertEquals(Boolean.TRUE, s.toggle("Alpha"));
        assertTrue(s.isActive(s.get("alpha")));
    }

    @Test
    @DisplayName("toggle 不存在的刷怪点返回 null，不误报成功")
    void toggleUnknownReturnsNull() {
        var s = toggleService();
        assertNull(s.toggle("nope"));
        assertNull(s.toggle(null));
    }

    @Test
    @DisplayName("forceSpawn tracks successful instances and passes ai profile")
    void forceSpawnTracksInstance() {
        List<String> calls = new ArrayList<>();
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> {
            calls.add(mob + ":" + profile);
            return 42;
        });
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    z: 0
                    ai-profile: aggressive
                """)).getConfigurationSection("spawners"));
        service.setAliveCheck(id -> true);
        assertTrue(service.forceSpawn("arena", new org.bukkit.Location(null, 0, 64, 0)));
        assertEquals(List.of("boss:aggressive"), calls);
        assertEquals(1, service.aliveCount("arena"));
        assertEquals(1, service.get("arena").totalSpawned());
    }

    @Test
    @DisplayName("forceSpawn rejects unknown spawners and failed callbacks")
    void forceSpawnRejectsUnknownAndFailure() {
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> -1);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    z: 0
                """)).getConfigurationSection("spawners"));
        assertFalse(service.forceSpawn("missing"));
        assertFalse(service.forceSpawn("arena"));
        assertEquals(0, service.get("arena").totalSpawned());
    }


    @Test
    @DisplayName("forceSpawn respects max-alive and max-spawns")
    void forceSpawnRespectsLimits() {
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> 7);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    z: 0
                    max-alive: 1
                    max-spawns: 1
                """)).getConfigurationSection("spawners"));
        service.setAliveCheck(id -> true);
        org.bukkit.Location point = new org.bukkit.Location(null, 0, 64, 0);
        assertTrue(service.forceSpawn("arena", point));
        assertFalse(service.forceSpawn("arena", point));
        assertEquals(1, service.get("arena").totalSpawned());
    }


    @Test
    @DisplayName("player gate is deterministic for min players and radius")
    void playerGateRules() {
        assertTrue(SpawnerService.playerGate(0, 0, 0));
        assertTrue(SpawnerService.playerGate(2, 2, 16));
        assertFalse(SpawnerService.playerGate(2, 1, 16));
        assertFalse(SpawnerService.playerGate(1, 3, 0));
        assertFalse(SpawnerService.playerGate(1, 3, Double.NaN));
    }

    @Test
    @DisplayName("forceSpawn enforces min-players gate")
    void forceSpawnRequiresPlayersWhenConfigured() {
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> 7);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    z: 0
                    min-players: 1
                    players-radius: 16
                """)).getConfigurationSection("spawners"));
        assertFalse(service.forceSpawn("arena", new org.bukkit.Location(null, 0, 64, 0)));
        assertEquals(0, service.get("arena").totalSpawned());
    }


    @Test
    @DisplayName("random-mobs pool is loaded and used by forceSpawn")
    void randomMobPoolIsUsed() {
        List<String> mobs = new ArrayList<>();
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> {
            mobs.add(mob);
            return 1;
        });
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: fallback
                    x: 0
                    z: 0
                    random-mobs:
                      zombie: 1
                      skeleton: 1
                """)).getConfigurationSection("spawners"));
        service.setAliveCheck(id -> false);
        assertTrue(service.forceSpawn("arena", new org.bukkit.Location(null, 0, 64, 0)));
        assertTrue(List.of("zombie", "skeleton").contains(mobs.get(0)));
        assertNotNull(service.get("arena").randomPool());
        assertEquals(1, service.get("arena").mobSpawnCounts().get(mobs.get(0)));
        assertEquals(1.0, service.get("arena").observedDistribution().get(mobs.get(0)), 1e-9);
        assertEquals(0.5, service.get("arena").expectedDistribution().get("zombie"), 1e-9);
        assertEquals(0.5, Math.abs(service.get("arena").distributionDeviation().get("zombie")), 1e-9);
    }

    @Test
    @DisplayName("random spawn distribution deviation is surfaced after enough samples")
    void randomDistributionDeviationWarning() throws Exception {
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> 1);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: fallback
                    x: 0
                    z: 0
                    random-mobs:
                      zombie: 1
                      skeleton: 1
                """)).getConfigurationSection("spawners"));
        var spawner = service.get("arena");
        var restore = spawner.getClass().getDeclaredMethod("restoreStats", int.class, Map.class);
        restore.setAccessible(true);
        restore.invoke(spawner, 20, Map.of("zombie", 20));

        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("distribution")));
    }

    @Test
    @DisplayName("random spawn statistics survive state save and reload")
    void randomSpawnStateRoundTrip() throws Exception {
        SpawnerService first = new SpawnerService(null, (mob, location, profile) -> 1);
        first.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: fallback
                    x: 0
                    z: 0
                    random-mobs:
                      zombie: 1
                """)).getConfigurationSection("spawners"));
        first.setAliveCheck(id -> false);
        assertTrue(first.forceSpawn("arena", new Location(null, 0, 64, 0)));
        var state = Files.createTempFile("spawner-state", ".yml").toFile();
        first.saveState(state);
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(state);
        assertTrue(saved.contains("spawners.arena.next-spawn-in"));
        saved.set("spawners.arena.next-spawn-in", 12);
        saved.save(state);

        SpawnerService second = new SpawnerService(null, (mob, location, profile) -> 1);
        second.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: fallback
                    x: 0
                    z: 0
                """)).getConfigurationSection("spawners"));
        second.loadState(state);
        assertEquals(1, second.get("arena").totalSpawned());
        assertEquals(1, second.get("arena").mobSpawnCounts().get("zombie"));
        var restoredState = Files.createTempFile("spawner-state-restored", ".yml").toFile();
        second.saveState(restoredState);
        assertEquals(12, YamlConfiguration.loadConfiguration(restoredState)
                .getInt("spawners.arena.next-spawn-in"));
        Files.deleteIfExists(state.toPath());
        Files.deleteIfExists(restoredState.toPath());
    }

    @Test
    @DisplayName("corrupt spawner state falls back to the backup file")
    void corruptStateUsesBackup() throws Exception {
        SpawnerService first = service("""
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    z: 0
                """);
        first.setAliveCheck(id -> false);
        assertTrue(first.forceSpawn("arena", new Location(null, 0, 64, 0)));
        var state = Files.createTempFile("spawner-state", ".yml").toFile();
        first.saveState(state);
        Files.copy(state.toPath(), new java.io.File(state.getPath() + ".bak").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(state.toPath(), "spawners: [broken");

        SpawnerService restored = service("""
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    z: 0
                """);
        restored.loadState(state);
        assertEquals(1, restored.get("arena").totalSpawned());
        Files.deleteIfExists(state.toPath());
        Files.deleteIfExists(new java.io.File(state.getPath() + ".bak").toPath());
    }

    @Test
    @DisplayName("spawner conditions are loaded for runtime gates")
    void loadsSpawnConditions() {
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> 1);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    z: 0
                    conditions:
                      - night
                      - "!raining"
                """)).getConfigurationSection("spawners"));
        assertEquals(List.of("night", "!raining"), service.get("arena").conditions());
    }

    @Test
    @DisplayName("scalar spawner conditions split without breaking range arguments")
    void loadsDelimitedScalarConditions() {
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> 1);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    z: 0
                    conditions: "day, !raining, time-between=0,12000"
                """)).getConfigurationSection("spawners"));
        assertEquals(List.of("day", "!raining", "time-between=0,12000"),
                service.get("arena").conditions());
    }


    @Test
    @DisplayName("spawner condition tester blocks a forced spawn")
    void conditionTesterBlocksForceSpawn() {
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> 1);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: boss
                    x: 0
                    z: 0
                    conditions: [blocked]
                """)).getConfigurationSection("spawners"));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        service.setConditionTester((condition, location) -> {
            calls.incrementAndGet();
            return !condition.equals("blocked");
        });
        assertFalse(service.forceSpawn("arena", new Location(mock(World.class), 0, 64, 0)));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("empty random-mobs pool falls back and reports warning")
    void emptyRandomPoolFallsBack() {
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> 1);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: fallback
                    x: 0
                    z: 0
                    random-mobs: {}
                """)).getConfigurationSection("spawners"));
        assertNull(service.get("arena").randomPool());
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("random-mobs")));
    }


    @Test
    @DisplayName("invalid random-mobs weights are warned and excluded")
    void invalidRandomWeightsAreExcluded() {
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> 1);
        service.load(YamlConfiguration.loadConfiguration(new StringReader("""
                spawners:
                  arena:
                    mob: fallback
                    x: 0
                    z: 0
                    random-mobs:
                      zombie: 0
                      skeleton: -2
                      valid: 2
                """)).getConfigurationSection("spawners"));
        assertNotNull(service.get("arena").randomPool());
        assertEquals(List.of("valid"), service.get("arena").randomPool().entries().stream()
                .map(dev.helstera.ai.spawner.RandomSpawnPool.Entry::mobId).toList());
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("random-mobs")));
    }


    @Test
    @DisplayName("non-finite player radius and y-range are normalized with warnings")
    void normalizesNonFiniteSpatialValues() {
        SpawnerService service = service("""
                spawners:
                  weird:
                    mob: mob
                    x: 0
                    z: 0
                    min-players: 1
                    players-radius: .NaN
                    y-range: .NaN
                """);
        assertEquals(24.0, service.get("weird").playersRadius(), 1e-9);
        assertEquals(0.0, service.get("weird").yRange(), 1e-9);
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("players-radius")));
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("y-range")));
    }


    @Test
    @DisplayName("radius Infinity is bounded before random location math")
    void radiusInfinityIsReported() {
        SpawnerService service = service("""
                spawners:
                  huge:
                    mob: mob
                    x: 0
                    z: 0
                    radius: .Inf
                """);
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("radius")),
                "non-finite radius is normalized with a warning");
        assertEquals(6.0, service.get("huge").radius(), 1e-9);
    }

    @Test
    @DisplayName("non-finite and oversized coordinates are normalized with warnings")
    void normalizesSpawnerCoordinates() {
        SpawnerService service = service("""
                spawners:
                  invalid:
                    mob: mob
                    x: .Inf
                    y: 0
                    z: 40000000
                    radius: 8
                """);
        assertEquals(0.0, service.get("invalid").x(), 1e-9);
        assertEquals(30_000_000.0, service.get("invalid").z(), 1e-6);
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("x")));
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("z")));
    }

    @Test
    @DisplayName("oversized finite spawner radius is clamped and warned")
    void clampsOversizedRadius() {
        SpawnerService service = service("""
                spawners:
                  huge:
                    mob: mob
                    x: 0
                    z: 0
                    radius: 1.0e100
                """);
        assertEquals(30_000_000.0, service.get("huge").radius(), 1e-6);
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("radius")));
    }

    @Test
    @DisplayName("players-radius is clamped at the coordinate safety bound")
    void clampsOversizedPlayersRadius() {
        SpawnerService service = service("""
                spawners:
                  huge:
                    mob: mob
                    x: 0
                    z: 0
                    players-radius: 1.0e100
                """);
        assertEquals(30_000_000.0, service.get("huge").playersRadius(), 1e-6);
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("players-radius")));
    }


    @Test
    @DisplayName("radius stays within remaining world-border clearance")
    void clipsRadiusToWorldBorderClearance() {
        assertEquals(8.0, SpawnerService.safeRadiusWithinBorder(10, 40, 0, 0, 0, 96), 1e-9);
        assertEquals(0.0, SpawnerService.safeRadiusWithinBorder(10, 50, 0, 0, 0, 96), 1e-9);
        assertEquals(10.0, SpawnerService.safeRadiusWithinBorder(10, 0, 0, 0, 0, 96), 1e-9);
        assertEquals(0.0, SpawnerService.safeRadiusWithinBorder(Double.NaN, 0, 0, 0, 0, 96), 1e-9);
    }











    @Test
    @DisplayName("vertical spawn range must intersect world height")
    void verticalRangeIntersectsWorldBounds() {
        assertTrue(SpawnerService.worldHeightRangeIntersects(-64, 320, 319, 4));
        assertTrue(SpawnerService.worldHeightRangeIntersects(-64, 320, -65, 4));
        assertFalse(SpawnerService.worldHeightRangeIntersects(-64, 320, 400, 8));
        assertFalse(SpawnerService.worldHeightRangeIntersects(-64, 320, -200, 10));
        assertFalse(SpawnerService.worldHeightRangeIntersects(-64, 320, Double.NaN, 1));
        assertFalse(SpawnerService.worldHeightRangeIntersects(-64, 320, 64, Double.POSITIVE_INFINITY));
    }


    @Test
    @DisplayName("floor location conversion rejects absent or invalid heightmap results")
    void floorLocationConversionHandlesHeightBounds() {
        assertEquals(64, SpawnerService.floorLocationY(-64, 320, 64, y -> y == 63));
        assertEquals(319, SpawnerService.floorLocationY(-64, 320, 319, y -> y == 318));
        assertEquals(Integer.MIN_VALUE, SpawnerService.floorLocationY(-64, 320, -65, y -> false));
        assertEquals(Integer.MIN_VALUE, SpawnerService.floorLocationY(-64, 320, 320, y -> true));
        assertEquals(Integer.MIN_VALUE, SpawnerService.floorLocationY(20, 20, 20, y -> true));
        assertEquals(64, SpawnerService.floorLocationY(-64, 320, 70, y -> y == 63),
                "heightmap skips non-solid fluid cells to find the solid ocean floor");
        assertEquals(Integer.MIN_VALUE, SpawnerService.floorLocationY(-64, 320, 70, y -> false));
    }


    @Test
    @DisplayName("heightmap suggestion falls downward at most the configured search budget")
    void floorResolutionIsBoundedForWaterAndCaves() {
        assertEquals(91, SpawnerService.floorLocationY(-64, 320, 96, y -> y == 90));
        assertEquals(Integer.MIN_VALUE,
                SpawnerService.floorLocationY(-64, 320, 700, y -> y == 100),
                "a deep cavern beyond the bounded fallback scan safely fails closed");
    }


    @Test
    @DisplayName("unloaded candidate chunk skips heightmap lookup")
    void unloadedChunkSkipsGroundLookup() {
        World world = mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> -1);
        Location candidate = new Location(world, 3.5, 80, -2.5);

        Location result = service.groundAt(world, candidate, false,
                (ignored, x, z) -> fail("heightmap must not be read for an unloaded chunk"));

        assertNull(result);
        verifyNoInteractions(world);
    }

    @Test
    @DisplayName("ground lookup rejects heightmap columns without solid support")
    void missingGroundIsRejectedAndWarningIsDeduplicated() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("test-world");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            Block block = mock(Block.class);
            when(block.isSolid()).thenReturn(false);
            return block;
        });
        SpawnerService service = new SpawnerService(null, (mob, location, profile) -> -1);
        Location candidate = new Location(world, 3.5, 80, -2.5);
        SpawnerService.HeightMapReader heightmap = (ignored, x, z) -> 80;

        assertNull(service.groundAt(world, candidate, true, heightmap));
        assertNull(service.groundAt(world, candidate, true, heightmap));
        assertEquals(1, service.warnings().stream().filter(w -> w.contains("no solid floor")).count());
    }
    @Test
    @DisplayName("spawner reload clears runtime warning de-duplication")
    void reloadClearsWarningDeduplication() {
        SpawnerService service = service("spawners: {}");
        assertTrue(service.firstSpawnWarning("unloaded chunk"));
        assertFalse(service.firstSpawnWarning("unloaded chunk"));
        service.load(YamlConfiguration.loadConfiguration(new StringReader("spawners: {}"))
                .getConfigurationSection("spawners"));
        assertTrue(service.firstSpawnWarning("unloaded chunk"));
    }

    // ---- 成组生成 / 间隔抖动 / 光照门控（Mythic 补齐语义） ----

    @Test
    @DisplayName("group-size accepts scalar, range and tilde forms")
    void readsGroupSize() {
        SpawnerService service = service("""
                spawners:
                  single:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: 4
                  ranged:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: 2-5
                  tilted:
                    mob: zombie
                    x: 0
                    z: 0
                    GroupSize: 3~6
                """);
        assertEquals(4, service.get("single").groupMin());
        assertEquals(4, service.get("single").groupMax());
        assertEquals(2, service.get("ranged").groupMin());
        assertEquals(5, service.get("ranged").groupMax());
        assertEquals(3, service.get("tilted").groupMin());
        assertEquals(6, service.get("tilted").groupMax());
    }

    @Test
    @DisplayName("group-size defaults to one and rejects nonsense with a warning")
    void groupSizeDefaultsAndRejectsInvalid() {
        SpawnerService service = service("""
                spawners:
                  plain:
                    mob: zombie
                    x: 0
                    z: 0
                  zero:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: 0
                  messy:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: 两只
                """);
        assertEquals(1, service.get("plain").groupMax());
        assertEquals(1, service.get("zero").groupMax());
        assertEquals(1, service.get("messy").groupMax());
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("group-size = 两只")));
        assertFalse(service.warnings().stream().anyMatch(w -> w.contains("group-size = 0")));
    }

    @Test
    @DisplayName("rolled group size always lands inside the configured range")
    void rollGroupSizeStaysInRange() {
        SpawnerService service = service("""
                spawners:
                  pack:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: 2-5
                """);
        var sp = service.get("pack");
        var rnd = new java.util.Random(7);
        boolean sawMin = false;
        boolean sawMax = false;
        for (int i = 0; i < 500; i++) {
            int rolled = sp.rollGroupSize(rnd);
            assertTrue(rolled >= 2 && rolled <= 5, "rolled " + rolled);
            sawMin |= rolled == 2;
            sawMax |= rolled == 5;
        }
        assertTrue(sawMin && sawMax, "range should cover both ends");
    }

    @Test
    @DisplayName("interval written as a range becomes a jittered schedule")
    void intervalRangeBecomesJitter() {
        SpawnerService service = service("""
                spawners:
                  ranged:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: [100, 300]
                """);
        var sp = service.get("ranged");
        assertEquals(200, sp.intervalTicks());
        assertTrue(sp.intervalJitter() > 0, "range form must introduce jitter");
        var rnd = new java.util.Random(3);
        for (int i = 0; i < 200; i++) {
            int ticks = sp.rollIntervalTicks(rnd);
            assertTrue(ticks >= 100 && ticks <= 300, "rolled " + ticks);
        }
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("interval 写成区间")));
    }

    @Test
    @DisplayName("group-size accepts integers, normalizes reverse ranges, and rejects malformed endpoints")
    void parseRangeRejectsFractionalAndMalformedValues() {
        SpawnerService service = service("""
                spawners:
                  integer:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: 3
                  reverse:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: 5-2
                  empty:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: 2-
                  invalid:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: two
                  fractional:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: 2.5
                """);
        assertEquals(3, service.get("integer").groupMax());
        assertEquals(2, service.get("reverse").groupMin());
        assertEquals(5, service.get("reverse").groupMax());
        assertEquals(1, service.get("empty").groupMax());
        assertEquals(1, service.get("invalid").groupMax());
        assertEquals(1, service.get("fractional").groupMax());
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("group-size = 2-")));
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("group-size = two")));
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("group-size = 2.5")));
    }

    @Test
    @DisplayName("interval jitter rejects non-finite percent values and records a problem")
    void intervalJitterRejectsNonFinitePercent() {
        SpawnerService service = service("""
                spawners:
                  broken:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: 100
                    interval-jitter: NaN%
                """);
        assertEquals(0.0, service.get("broken").intervalJitter());
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("interval-jitter")));
    }

    @Test
    @DisplayName("explicit interval-jitter accepts ratio, percent and cooldown alias")
    void explicitIntervalJitter() {
        SpawnerService service = service("""
                spawners:
                  ratio:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: 100
                    interval-jitter: 0.5
                  percent:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: 100
                    interval-jitter: 20%
                  alias:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: 100
                    cooldown-jitter: 0.1
                """);
        assertEquals(0.5, service.get("ratio").intervalJitter(), 1e-9);
        assertEquals(0.2, service.get("percent").intervalJitter(), 1e-9);
        assertEquals(0.1, service.get("alias").intervalJitter(), 1e-9);
        var rnd = new java.util.Random(11);
        for (int i = 0; i < 200; i++) {
            assertTrue(service.get("ratio").rollIntervalTicks(rnd) >= 20);
        }
    }

    @Test
    @DisplayName("zero jitter keeps the fixed interval unchanged")
    void zeroJitterKeepsFixedInterval() {
        SpawnerService service = service("""
                spawners:
                  fixed:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: 120
                """);
        var sp = service.get("fixed");
        assertEquals(0.0, sp.intervalJitter());
        assertEquals(120, sp.rollIntervalTicks(new java.util.Random(1)));
    }

    @Test
    @DisplayName("light gate parses comparators, ranges and named variants")
    void lightConditionParsesForms() {
        Location location = litLocation("test", 3, 15, 12);

        assertEquals(Boolean.TRUE, SpawnerService.lightCondition("light-level<8", location));
        assertEquals(Boolean.FALSE, SpawnerService.lightCondition("light-level<3", location));
        assertEquals(Boolean.TRUE, SpawnerService.lightCondition("light-level<=3", location));
        assertEquals(Boolean.TRUE, SpawnerService.lightCondition("light-level-above=2", location));
        assertEquals(Boolean.FALSE, SpawnerService.lightCondition("light-level-above=5", location));
        assertEquals(Boolean.TRUE, SpawnerService.lightCondition("light-level 0-5", location));
        assertEquals(Boolean.FALSE, SpawnerService.lightCondition("light-level 10-15", location));
        assertEquals(Boolean.TRUE, SpawnerService.lightCondition("light-level below 5", location));
        assertEquals(Boolean.FALSE, SpawnerService.lightCondition("light-level above 5", location));
        // 天空光与方块光各取各的，不互相冒充
        assertEquals(Boolean.TRUE, SpawnerService.lightCondition("sky-light-level=15", location));
        assertEquals(Boolean.FALSE, SpawnerService.lightCondition("sky-light-level=12", location));
        assertEquals(Boolean.TRUE, SpawnerService.lightCondition("light-level-from-blocks=12", location));
        assertEquals(Boolean.TRUE, SpawnerService.lightCondition("blocklightlevel below 20", location));
        // 非光照条件返回 null，交回原有判定链
        assertNull(SpawnerService.lightCondition("night", location));
        assertNull(SpawnerService.lightCondition("biome-is=plains", location));
    }

    @Test
    @DisplayName("malformed light gate fails closed instead of passing")
    void lightConditionFailsClosed() {
        Location location = litLocation("test", 3, 15, 12);
        assertEquals(Boolean.FALSE, SpawnerService.lightCondition("light-level", location));
        assertEquals(Boolean.FALSE, SpawnerService.lightCondition("light-level", null));
        // 取光照抛异常（区块未加载）时拒绝，而不是放行
        Location broken = mock(Location.class);
        when(broken.getWorld()).thenReturn(mock(World.class));
        when(broken.getBlock()).thenThrow(new IllegalStateException("chunk not loaded"));
        assertEquals(Boolean.FALSE, SpawnerService.lightCondition("light-level<8", broken));
    }

    @Test
    @DisplayName("interval jitter rejects naked non-finite values with zero and a warning")
    void intervalJitterRejectsNonFiniteValues() {
        SpawnerService service = service("""
                spawners:
                  nan:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: 100
                    interval-jitter: NaN
                  infinity:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: 100
                    interval-jitter: Infinity
                  negative-infinity:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: 100
                    interval-jitter: -Infinity
                """);
        assertEquals(0, service.get("nan").intervalJitter(), 1e-9);
        assertEquals(0, service.get("infinity").intervalJitter(), 1e-9);
        assertEquals(0, service.get("negative-infinity").intervalJitter(), 1e-9);
        assertTrue(service.warnings().stream().filter(w -> w.contains("interval-jitter")).count() >= 3);
    }

    @Test
    @DisplayName("interval jitter clamps out-of-range ratios and percentages with warnings")
    void intervalJitterClampsOutOfRangeValues() {
        SpawnerService service = service("""
                spawners:
                  ratio-high:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: 100
                    interval-jitter: 1.5
                  ratio-low:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: 100
                    interval-jitter: -0.5
                  percent-high:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: 100
                    interval-jitter: 150%
                  percent-low:
                    mob: zombie
                    x: 0
                    z: 0
                    interval: 100
                    interval-jitter: -20%
                """);
        assertEquals(1, service.get("ratio-high").intervalJitter(), 1e-9);
        assertEquals(0, service.get("ratio-low").intervalJitter(), 1e-9);
        assertEquals(1, service.get("percent-high").intervalJitter(), 1e-9);
        assertEquals(0, service.get("percent-low").intervalJitter(), 1e-9);
        assertTrue(service.warnings().stream().filter(w -> w.contains("超出 0..1")).count() >= 4);
    }

    @Test
    @DisplayName("parseRange rejects missing left and right endpoints, empty, reverse, invalid and decimal forms")
    void parseRangeRejectsMalformedBoundaries() {
        SpawnerService service = service("""
                spawners:
                  left:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: "-2"
                  empty:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: ""
                  right:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: "2-"
                  reverse:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: "5-2"
                  invalid:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: nonsense
                  decimal:
                    mob: zombie
                    x: 0
                    z: 0
                    group-size: "2.5"
                """);
        assertEquals(1, service.get("left").groupMax());
        assertTrue(service.warnings().stream().anyMatch(w -> w.contains("group-size = -2")));
        assertEquals(1, service.get("empty").groupMax());
        assertEquals(1, service.get("right").groupMax());
        assertEquals(2, service.get("reverse").groupMin());
        assertEquals(5, service.get("reverse").groupMax());
        assertEquals(1, service.get("invalid").groupMax());
        assertEquals(1, service.get("decimal").groupMax());
    }

    /** 伪造一个光照固定的方块位置（方块光/天空光以 int 存，Bukkit 返回 byte）。 */
    private static Location litLocation(String worldName, int combined, int sky, int blockLight) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(worldName);
        Block block = mock(Block.class);
        when(block.getLightLevel()).thenReturn((byte) combined);
        when(block.getLightFromSky()).thenReturn((byte) sky);
        when(block.getLightFromBlocks()).thenReturn((byte) blockLight);
        Location location = mock(Location.class);
        when(location.getWorld()).thenReturn(world);
        when(location.getBlock()).thenReturn(block);
        return location;
    }

}
