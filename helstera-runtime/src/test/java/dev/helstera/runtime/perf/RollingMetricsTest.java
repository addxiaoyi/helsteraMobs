package dev.helstera.runtime.perf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 滚动窗口统计的正确性验证。
 *
 * <p>重点覆盖环形覆盖行为与百分位插值——这两处最容易在后续改动中悄悄退化。</p>
 */
class RollingMetricsTest {

    @Test
    @DisplayName("空窗口不抛异常，读数全为 0")
    void emptyWindow() {
        RollingMetrics m = new RollingMetrics(10);
        assertEquals(0, m.count());
        assertEquals(0, m.max());
        assertEquals(0, m.min());
        assertEquals(0.0, m.mean());
        assertEquals(0.0, m.percentile(0.5));
        assertFalse(m.isFull());
        assertEquals("（无样本）", m.snapshot().describe("ms"));
    }

    @Test
    @DisplayName("容量必须为正")
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new RollingMetrics(0));
        assertThrows(IllegalArgumentException.class, () -> new RollingMetrics(-1));
    }

    @Test
    @DisplayName("未满窗口时统计仅覆盖已写入的样本")
    void partialWindow() {
        RollingMetrics m = new RollingMetrics(10);
        m.record(10);
        m.record(30);
        m.record(20);
        assertEquals(3, m.count());
        assertEquals(10, m.min());
        assertEquals(30, m.max());
        assertEquals(20.0, m.mean());
        assertFalse(m.isFull());
    }

    @Test
    @DisplayName("写满容量后只保留最新样本，旧值被逐出")
    void evictsOldestOnOverflow() {
        RollingMetrics m = new RollingMetrics(4);
        // 写入 1..8，容量 4，最终应只剩 5,6,7,8
        for (int i = 1; i <= 8; i++) m.record(i);
        assertEquals(4, m.count());
        assertEquals(5, m.min());
        assertEquals(8, m.max());
        assertEquals(6.5, m.mean());
        assertTrue(m.isFull());
    }

    @Test
    @DisplayName("环形回绕后顺序打乱不影响统计结果")
    void handlesWrapAround() {
        // 容量 3 反复回绕，覆盖满且乱序多次
        RollingMetrics m = new RollingMetrics(3);
        for (int i = 10; i <= 40; i++) m.record(i);
        assertEquals(3, m.count());
        assertEquals(38, m.min());
        assertEquals(40, m.max());
    }

    @Test
    @DisplayName("百分位在边界参数下退化为 min/max")
    void percentileBounds() {
        RollingMetrics m = new RollingMetrics(10);
        for (int i = 1; i <= 10; i++) m.record(i);
        assertEquals(1.0, m.percentile(0));
        assertEquals(10.0, m.percentile(1));
        assertEquals(5.5, m.percentile(0.5), 1e-9);
        assertTrue(m.percentile(0.5) <= m.percentile(0.95));
        assertTrue(m.percentile(0.95) <= m.percentile(0.99));
    }

    @Test
    @DisplayName("单样本时所有百分位相同")
    void singleSample() {
        RollingMetrics m = new RollingMetrics(5);
        m.record(42);
        assertEquals(42.0, m.percentile(0.5));
        assertEquals(42.0, m.percentile(0.99));
        assertEquals(42.0, m.mean());
    }

    @Test
    @DisplayName("全等样本时各百分位一致")
    void allEqualSamples() {
        RollingMetrics m = new RollingMetrics(8);
        for (int i = 0; i < 8; i++) m.record(7);
        assertEquals(7.0, m.mean());
        assertEquals(7.0, m.percentile(0.99));
        assertEquals(7, m.min());
        assertEquals(7, m.max());
    }

    @Test
    @DisplayName("尾部尖峰不拉高 p50，且 p99 已能反映抬升")
    void spikeOnlyHitsP99() {
        // 这正是引入百分位的意义：平均值会失真（被尖峰拉到 10.9），
        // 而 p50 保持稳定、p99 相对基线抬升约 10 倍，两者一对比就能看出卡顿。
        RollingMetrics m = new RollingMetrics(100);
        for (int i = 0; i < 99; i++) m.record(1);
        m.record(1000);

        assertEquals(1.0, m.percentile(0.5), 1e-9, "p50 不应被单个尖峰拉高");
        // rank = 0.99 * (100-1) = 98.01，在第 98 与 99 个样本间插值：
        // 1 + (1000-1) * 0.01 = 10.99
        assertEquals(10.99, m.percentile(0.99), 1e-9);
        assertEquals(10.99, m.mean(), 1e-9, "平均值确实被尖峰抬高（99+1000)/100，故单看均值不可靠");
        assertEquals(1000, m.max());
        assertTrue(m.percentile(0.99) > m.percentile(0.5) * 5, "p99 应显著高于 p50");
    }

    @Test
    @DisplayName("reset 清空窗口，不残留旧数据")
    void resetClears() {
        RollingMetrics m = new RollingMetrics(4);
        for (int i = 1; i <= 4; i++) m.record(i);
        m.reset();
        assertEquals(0, m.count());
        assertEquals(0.0, m.mean());
        assertFalse(m.isFull());
        m.record(99);
        assertEquals(1, m.count());
        assertEquals(99, m.max());
    }

    @Test
    @DisplayName("快照的 reliability 反映样本充足度")
    void snapshotReliability() {
        RollingMetrics m = new RollingMetrics(10);
        for (int i = 0; i < 4; i++) m.record(1);
        assertFalse(m.snapshot().reliable(), "样本不足半数时不可用于判断趋势");
        for (int i = 0; i < 6; i++) m.record(1);
        assertTrue(m.snapshot().reliable());
    }

    @Test
    @DisplayName("describe 输出含关键字段")
    void describeContainsKeyFields() {
        RollingMetrics m = new RollingMetrics(4);
        for (int i = 1; i <= 4; i++) m.record(i * 10);
        String s = m.snapshot().describe("ms");
        assertTrue(s.contains("n=4/4"));
        assertTrue(s.contains("p95"));
        assertTrue(s.contains("p99"));
        assertTrue(s.contains("ms"));
    }
}