package dev.helstera.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 血量上限钳制测试。
 *
 * <p><b>这个测试来自一次真实的服务端告警</b>。服务器日志里有 6 次：
 * <pre>
 * Caused by: java.lang.IllegalArgumentException:
 *   Health value (12155.0625) must be between 0 and 2048.0.
 *   at CraftLivingEntity.setHealth(CraftLivingEntity.java:108)
 *   at HelsteraPlugin.spawnMobCore(HelsteraPlugin.java:850)
 *   at HelsteraCommand.mob(HelsteraCommand.java:204)
 * </pre>
 * 等级系统按 {@code growth^(level-1)} 指数缩放，算出 12155 血后
 * {@code setMaxHealth} 抛异常，异常冒泡到命令层 ——
 * {@code /helstera mob spawn} <b>整条命令失败</b>，
 * 而日志里除了一个数字没有任何指向配置的提示。</p>
 */
class HealthClampTest {

    @Test
    @DisplayName("超过 Bukkit 上限时夹到 2048，而不是抛异常")
    void clampsAboveBukkitLimit() {
        // 日志里的真实值
        assertEquals(HelsteraPlugin.BUKKIT_MAX_HEALTH,
                HelsteraPlugin.clampToBukkitRange(12155.0625),
                "等级缩放算出 12155 血：不夹紧会抛 IllegalArgumentException 并让命令整条失败");
    }

    @Test
    @DisplayName("区间内的血量原样通过，不被改动")
    void keepsValuesInRange() {
        assertEquals(20.0, HelsteraPlugin.clampToBukkitRange(20.0), 1e-9);
        assertEquals(1024.5, HelsteraPlugin.clampToBukkitRange(1024.5), 1e-9);
        assertEquals(HelsteraPlugin.BUKKIT_MAX_HEALTH,
                HelsteraPlugin.clampToBukkitRange(HelsteraPlugin.BUKKIT_MAX_HEALTH), 1e-9,
                "恰好等于上限应原样通过——夹紧会让人误以为上限不包含等号");
    }

    @Test
    @DisplayName("0 与负数返回 0，表示「不改动」")
    void nonPositiveMeansUnchanged() {
        assertEquals(0.0, HelsteraPlugin.clampToBukkitRange(0.0), 1e-9);
        assertEquals(0.0, HelsteraPlugin.clampToBukkitRange(-5.0), 1e-9);
    }

    @Test
    @DisplayName("NaN 返回 0 而不是原样传出")
    void nanDoesNotPropagate() {
        // entity.health 写成 "abc" 时 getDouble 会给 0，写成 ".nan" 则是 NaN。
        // NaN 若原样传给 setHealth，Paper 的 Preconditions 比较恒为 false，
        // 抛出的是一条看不懂的异常；夹成 0 让调用方走「不改动」分支。
        double r = HelsteraPlugin.clampToBukkitRange(Double.NaN);
        assertEquals(0.0, r, 1e-9, "NaN 不能传出——它会让 Paper 的范围校验抛出费解的异常");
        assertTrue(Double.isNaN(r) == false);
    }

    @Test
    @DisplayName("上限值与 Paper 实际限制一致")
    void limitMatchesPaper() {
        // Paper 用 Attribute.GENERIC_MAX_HEALTH 的 Range（上限 2048.0）做校验，
        // 日志里的报错信息原文即 "must be between 0 and 2048.0"。
        assertEquals(2048.0, HelsteraPlugin.BUKKIT_MAX_HEALTH, 1e-9,
                "上限必须与 Paper 的 GENERIC_MAX_HEALTH 实际限制一致；"
                        + "调大它会让防护形同虚设");
    }
}