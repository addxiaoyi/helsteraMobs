package dev.helstera.ai;

import dev.helstera.ai.spawner.SpawnerService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        SpawnerService s = new SpawnerService(null, null, (mob, loc, aiProfile) -> {
            spawned.add(mob);
            return spawned.size();
        });
        s.load(YamlConfiguration.loadConfiguration(new StringReader(yaml)).getConfigurationSection("spawners"));
        return s;
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
                    players-radius: -8
                """);
        assertEquals(40.0, s.get("custom").playersRadius(), 1e-9);
        assertEquals(0.0, s.get("bad").playersRadius(), 1e-9,
                "负半径应归零；此时人数门控恒不满足，而不是抛异常");
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
    @DisplayName("null 节与空节安全返回，不抛异常")
    void toleratesNullAndEmpty() {
        SpawnerService s = new SpawnerService(null, null, (m, l, a) -> -1);
        s.load(null);
        assertEquals(0, s.size());
        s.load(YamlConfiguration.loadConfiguration(new StringReader("")).getConfigurationSection("spawners"));
        assertEquals(0, s.size());
        assertTrue(s.warnings().isEmpty());
    }

    @Test
    @DisplayName("重复 load 会清空旧定义，不残留已删除的刷怪点")
    void reloadClearsPreviousSpawners() {
        SpawnerService s = new SpawnerService(null, null, (m, l, a) -> -1);
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
}