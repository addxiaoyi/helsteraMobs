package dev.helstera.ai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件锁存器去抖测试。
 *
 * <p>这组用例锁的是 {@code on-condition-met} / {@code on-condition-lost} 的核心行为：
 * <b>只在状态确认翻转时触发一次边沿</b>。若去抖失效，采样每 tick 一次而血量在阈值
 * 附近抖动，会导致触发器每秒执行数十次——这种故障不报错、无异常日志，
 * 只能靠这层测试挡住。</p>
 */
class ConditionLatchTest {

    @Test
    @DisplayName("首次采样只建立基线，不产生边沿")
    void firstSampleOnlySetsBaseline() {
        var l = new ConditionLatch(3);
        assertFalse(l.rising("k", true), "首次即无法判断是刚变还是一直是 true");
        assertFalse(l.falling("k", true));
        assertTrue(l.initialized("k"));
        assertEquals(Boolean.TRUE, l.baseline("k"));
    }

    @Test
    @DisplayName("连续足够次观测到反值才确认翻转")
    void confirmsAfterDebounce() {
        var l = new ConditionLatch(3);
        l.rising("k", false);                    // 基线 false
        assertFalse(l.rising("k", true));         // 1 次，不够
        assertFalse(l.rising("k", true));         // 2 次，不够
        assertTrue(l.rising("k", true));          // 3 次，确认上升沿
    }

    @Test
    @DisplayName("debounce=1 时退化为无去抖，但仍保持基线语义")
    void debounceOneDegeneratesCleanly() {
        var l = new ConditionLatch(1);
        assertFalse(l.rising("k", false));        // 仍是基线
        assertTrue(l.rising("k", true));          // 立刻确认
        assertFalse(l.rising("k", false), "反向需重新确认，不应同一次采样出双沿");
        // 上一次已把基线翻转为 false；再喂 true 与基线相反，debounce=1 下立即确认为上升沿
        assertTrue(l.rising("k", true));
        // 此时基线已是 true，再喂 true 属稳定态，不产生边沿
        assertFalse(l.rising("k", true), "基线已是 true，不应重复触发上升沿");
    }

    @Test
    @DisplayName("debounce=1 时下降沿同样立即确认")
    void debounceOneFallingImmediately() {
        var l = new ConditionLatch(1);
        l.falling("k", true);          // 基线 true
        assertTrue(l.falling("k", false), "debounce=1 应在下一次立即确认下降沿");
    }

    @Test
    @DisplayName("反向翻转需重新积累，不沿用上一次的计数")
    void reverseFlipNeedsFreshStreak() {
        var l = new ConditionLatch(3);
        l.rising("k", false);
        l.rising("k", true);
        l.rising("k", true);
        assertTrue(l.rising("k", true));      // 确认上升沿，基线=true
        assertEquals(0, l.pending("k"), "确认后计数归零");

        // 反向：必须重新累计 3 次，不能沿用上升沿时的进度
        assertFalse(l.rising("k", false));
        assertFalse(l.rising("k", false));
        assertTrue(l.falling("k", false), "第三次确认下降沿");
    }

    @Test
    @DisplayName("debounce 小于 1 被夹到 1，不出现零确认")
    void clampsNonPositiveDebounce() {
        var l = new ConditionLatch(0);
        l.rising("k", false);
        assertTrue(l.rising("k", true), "debounce=0 若被当 0 会导致基线期即确认翻转");
    }

    @Test
    @DisplayName("抖动样本不会触发，且不积累计数")
    void jitterDoesNotFireAndDoesNotAccumulate() {
        var l = new ConditionLatch(3);
        l.rising("k", false);
        // 交替抖动：每次回到基线都会清零 pending
        for (int i = 0; i < 50; i++) {
            l.rising("k", true);
            l.rising("k", false);
        }
        assertEquals(Boolean.FALSE, l.baseline("k"), "基线应仍为 false");
        assertEquals(0, l.pending("k"), "抖动不应累积待确认计数");
        assertFalse(l.falling("k", false));
    }

    @Test
    @DisplayName("抖动后再稳定，确认仍能正确触发")
    void recoversAfterJitter() {
        var l = new ConditionLatch(3);
        l.rising("k", false);
        l.rising("k", true);
        l.rising("k", false);
        // 现在稳定为 true
        assertFalse(l.rising("k", true));
        assertFalse(l.rising("k", true));
        assertTrue(l.rising("k", true), "抖动恢复后应能正常确认");
    }

    @Test
    @DisplayName("上升沿与下降沿互斥，不会同次双触发")
    void edgesAreMutuallyExclusive() {
        var l = new ConditionLatch(2);
        l.rising("k", false);
        l.rising("k", true);                 // 计数 1
        boolean up = l.rising("k", true);    // 计数 2 -> 确认
        boolean down = l.falling("k", true); // 同一时刻不应也报下降沿
        assertTrue(up);
        assertFalse(down);
    }

    @Test
    @DisplayName("下降沿确认")
    void confirmsFalling() {
        var l = new ConditionLatch(2);
        l.falling("k", true);
        assertFalse(l.falling("k", false));
        assertTrue(l.falling("k", false));
    }

    @Test
    @DisplayName("确认后基线翻转，下一轮需重新积累")
    void baselineFlipsAndResets() {
        var l = new ConditionLatch(2);
        l.rising("k", false);
        l.rising("k", true);
        assertTrue(l.rising("k", true));
        assertEquals(Boolean.TRUE, l.baseline("k"));
        assertEquals(0, l.pending("k"), "确认后计数必须归零");
        assertFalse(l.rising("k", true), "基线已是 true，不应重复触发上升沿");
    }

    @Test
    @DisplayName("不同键互不干扰")
    void keysAreIndependent() {
        var l = new ConditionLatch(2);
        l.rising("a", false);
        l.rising("b", false);
        l.rising("a", true);
        assertFalse(l.rising("b", true), "b 只观测到 1 次");
        assertTrue(l.rising("a", true));
    }

    @Test
    @DisplayName("retainAll 遗忘失效键，size 随之下降")
    void retainAllPrunesDeadKeys() {
        var l = new ConditionLatch(1);
        l.rising("1|prof|cond", true);
        l.rising("2|prof|cond", true);
        assertEquals(2, l.size());
        l.retainAll(Set.of("1|prof|cond"));
        assertEquals(1, l.size());
        assertEquals(Boolean.TRUE, l.baseline("1|prof|cond"));
    }

    @Test
    @DisplayName("未初始化的键：baseline 为 null，size 不计")
    void uninitialisedKey() {
        var l = new ConditionLatch(1);
        assertFalse(l.initialized("nope"));
        assertNull(l.baseline("nope"));
        assertEquals(0, l.pending("nope"));
        assertEquals(0, l.size());
    }

    @Test
    @DisplayName("clear 清空全部状态")
    void clearResets() {
        var l = new ConditionLatch(1);
        l.rising("k", true);
        l.clear();
        assertEquals(0, l.size());
        assertFalse(l.initialized("k"));
    }

    @Test
    @DisplayName("长时间高频抖动下状态机不失控（回归护栏）")
    void staysStableUnderNoise() {
        var l = new ConditionLatch(3);
        l.rising("k", false);
        int fires = 0;
        // 模拟 1000 tick 抖动：偶尔 3 次连续 true 后又回落
        for (int i = 0; i < 1000; i++) {
            boolean sample = (i % 97) < 3;      // 稀疏的 true 窗口
            if (l.rising("k", sample)) fires++;
        }
        // 每次 true 窗口仅 3 tick，恰好达到确认阈值 -> 约 10 次边沿，
        // 关键是远小于 1000（无去抖时会是几百次）
        assertTrue(fires <= 15, "抖动导致的误触发应被压制，实际 " + fires);
    }
}