package dev.helstera.ai;

import dev.helstera.ai.spawner.SpawnerService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

}
