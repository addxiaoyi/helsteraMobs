package dev.helstera.ai.lever;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拉杆服务测试。
 *
 * <p>重点守两件「按下去没反应且不报错」的事：<b>坐标写反</b>会退化成空区域，
 * <b>动作写漏</b>会变成按了没反应。两者都必须有告警，否则服主只能靠猜。</p>
 *
 * <p>时钟注入而非 sleep：冷却逻辑要验证「还剩几秒」，靠真实时间测必然不稳定。</p>
 */
class LeverServiceTest {

    private static LeverService.Lever lever(String id, LeverRegion r, int cooldown) {
        return new LeverService.Lever(id, r, List.of("message-action"), cooldown, null);
    }

    @Test
    @DisplayName("pos1 大于 pos2 时仍能命中：区域自动规整为 min/max")
    void regionNormalizesCorners() {
        // 作者常把 pos1 写在 pos2 之后；不规整就会得到空区域，拉杆完全不触发
        LeverRegion r = LeverRegion.of("world", 10, 10, 10, 0, 0, 0);
        assertTrue(r.contains("world", 5, 5, 5));
        assertTrue(r.contains("world", 0, 0, 0));
        assertTrue(r.contains("world", 10, 10, 10));
        assertFalse(r.contains("world", 11, 0, 0));
    }

    @Test
    @DisplayName("不同世界不互相命中")
    void regionsAreWorldScoped() {
        LeverRegion r = LeverRegion.block("nether", 0, 64, 0);
        assertTrue(r.contains("nether", 0, 64, 0));
        assertFalse(r.contains("world", 0, 64, 0), "同坐标不同世界不应命中");
        assertFalse(r.contains(null, 0, 64, 0));
    }

    @Test
    @DisplayName("区域体积可用于识别坐标写错")
    void volumeIsComputable() {
        assertEquals(8, LeverRegion.of("w", 0, 0, 0, 1, 1, 1).volume());
        assertEquals(1, LeverRegion.block("w", 5, 5, 5).volume());
    }

    @Test
    @DisplayName("命中后进入冷却，时间未到被拒")
    void cooldownBlocksRepeatTriggers() {
        AtomicLong now = new AtomicLong(0);
        LeverService s = new LeverService(now::get);
        s.load(List.of(lever("gate", LeverRegion.block("w", 0, 64, 0), 40))); // 40 tick = 2s

        assertTrue(s.trigger("p1", "w", 0, 64, 0, null).ok());
        var second = s.trigger("p1", "w", 0, 64, 0, null);
        assertFalse(second.ok(), "冷却内不该重复触发");
        assertTrue(second.reason().contains("冷却"));

        now.addAndGet(2100); // 超过 2 秒
        assertTrue(s.trigger("p1", "w", 0, 64, 0, null).ok(), "冷却结束后应恢复");
    }

    @Test
    @DisplayName("冷却按玩家隔离：一个玩家触发不影响另一个")
    void cooldownIsPerPlayer() {
        AtomicLong now = new AtomicLong(0);
        LeverService s = new LeverService(now::get);
        s.load(List.of(lever("gate", LeverRegion.block("w", 0, 64, 0), 40)));

        assertTrue(s.trigger("p1", "w", 0, 64, 0, null).ok());
        assertFalse(s.trigger("p1", "w", 0, 64, 0, null).ok());
        assertTrue(s.trigger("p2", "w", 0, 64, 0, null).ok(), "换人不该被冷却拦住");
    }

    @Test
    @DisplayName("权限先于命中判定，避免把「没权限」误报成「没拉杆」")
    void permissionCheckedBeforeHit() {
        LeverService s = new LeverService(() -> 0L);
        s.load(List.of(new LeverService.Lever("admin_gate",
                LeverRegion.block("w", 0, 64, 0), List.of("x"), 0, "helstera.admin")));

        var denied = s.trigger("p1", "w", 0, 64, 0, perm -> false);
        assertFalse(denied.ok());
        assertEquals("权限不足", denied.reason());

        assertTrue(s.trigger("p1", "w", 0, 64, 0, perm -> true).ok());
    }

    @Test
    @DisplayName("没配置动作的拉杆必须告警：按下去没反应是最难查的故障")
    void emptyTriggersWarn() {
        LeverService s = new LeverService(() -> 0L);
        s.load(List.of(new LeverService.Lever("silent", LeverRegion.block("w", 0, 0, 0),
                List.of(), 0, null)));
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("没有配置任何动作")),
                "空动作拉杆应告警，实际: " + s.warnings());
    }

    @Test
    @DisplayName("重复 id 与超大区域都告警，但不阻断装载")
    void duplicateAndOversizedWarn() {
        LeverService s = new LeverService(() -> 0L);
        s.load(List.of(
                lever("a", LeverRegion.block("w", 0, 0, 0), 0),
                lever("a", LeverRegion.block("w", 1, 0, 0), 0),
                lever("big", LeverRegion.of("w", 0, 0, 0, 40, 40, 40), 0)));
        assertEquals(2, s.size());
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("重复声明")));
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("体积")));
    }

    @Test
    @DisplayName("缺 id 或缺区域的拉杆被跳过并告警")
    void invalidDefinitionsSkipped() {
        LeverService s = new LeverService(() -> 0L);
        s.load(List.of(
                new LeverService.Lever(null, LeverRegion.block("w", 0, 0, 0), List.of("x"), 0, null),
                new LeverService.Lever("noRegion", null, List.of("x"), 0, null)));
        assertEquals(0, s.size());
        assertEquals(2, s.warnings().size());
    }

    @Test
    @DisplayName("未命中位置给出可读原因")
    void missIsReadable() {
        LeverService s = new LeverService(() -> 0L);
        s.load(List.of(lever("gate", LeverRegion.block("w", 0, 64, 0), 0)));
        var r = s.trigger("p1", "w", 500, 64, 500, null);
        assertFalse(r.ok());
        assertNotNull(r.reason());
        assertNull(r.lever());
    }

    @Test
    @DisplayName("reload 清空冷却，避免旧冷却卡住新配置")
    void reloadClearsCooldowns() {
        AtomicLong now = new AtomicLong(0);
        LeverService s = new LeverService(now::get);
        s.load(List.of(lever("gate", LeverRegion.block("w", 0, 64, 0), 4000)));
        assertTrue(s.trigger("p1", "w", 0, 64, 0, null).ok());
        assertFalse(s.trigger("p1", "w", 0, 64, 0, null).ok());

        s.load(List.of(lever("gate", LeverRegion.block("w", 0, 64, 0), 4000)));
        assertTrue(s.trigger("p1", "w", 0, 64, 0, null).ok(), "重载后应立即可用");
    }
}