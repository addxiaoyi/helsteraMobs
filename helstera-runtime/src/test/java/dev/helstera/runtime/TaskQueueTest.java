package dev.helstera.runtime;

import dev.helstera.runtime.scheduler.TaskQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务队列的时序测试。
 *
 * <p>队列本体不依赖 Bukkit，时间由测试显式传入，因此能脱离服务端精确验证时序。</p>
 */
class TaskQueueTest {

    /** 推进 n 个 tick 并执行到期任务。 */
    private static long advance(TaskQueue q, long now, int ticks) {
        for (int i = 0; i < ticks; i++) {
            now++;
            q.drain(now);
        }
        return now;
    }

    @Test
    @DisplayName("延时任务到点才执行")
    void delayedTask() {
        TaskQueue q = new TaskQueue();
        AtomicInteger ran = new AtomicInteger();
        q.runLater(0, ran::incrementAndGet, 3);

        long now = advance(q, 0, 1);
        assertEquals(0, ran.get(), "延迟未到不应执行");
        now = advance(q, now, 1);
        assertEquals(0, ran.get());
        advance(q, now, 1);
        assertEquals(1, ran.get());
    }

    @Test
    @DisplayName("单次任务执行后自动出队")
    void singleTaskRemoved() {
        TaskQueue q = new TaskQueue();
        AtomicInteger ran = new AtomicInteger();
        q.runLater(0, ran::incrementAndGet, 0);
        long now = advance(q, 0, 6);
        assertEquals(1, ran.get(), "单次任务只能执行一次");
        assertEquals(0, q.size(), "执行完应自动出队，否则队列只增不减最终耗尽内存");
        advance(q, now, 3);
    }

    @Test
    @DisplayName("重复任务按周期触发")
    void timerRepeats() {
        TaskQueue q = new TaskQueue();
        AtomicInteger ran = new AtomicInteger();
        q.runTimer(0, ran::incrementAndGet, 0, 2);
        long now = advance(q, 0, 7);
        assertEquals(4, ran.get(), "每 2 tick 一次，7 tick 内应执行 4 次");
        assertEquals(1, q.size(), "重复任务不应出队");
        advance(q, now, 1);
    }

    @Test
    @DisplayName("取消后不再执行，并从队列移除")
    void cancelStopsTask() {
        TaskQueue q = new TaskQueue();
        AtomicInteger ran = new AtomicInteger();
        TaskQueue.Handle h = q.runTimer(0, ran::incrementAndGet, 0, 1);
        long now = advance(q, 0, 3);
        assertTrue(ran.get() > 0);

        assertTrue(h.cancel());
        assertTrue(h.isCancelled());
        assertFalse(h.cancel(), "重复取消应返回 false");

        int after = ran.get();
        advance(q, now, 5);
        assertEquals(after, ran.get(), "取消后不应继续执行");
    }

    @Test
    @DisplayName("任务抛异常不影响同批其它任务")
    void exceptionIsolated() {
        TaskQueue q = new TaskQueue();
        AtomicInteger ok = new AtomicInteger();
        q.runLater(0, () -> {
            throw new IllegalStateException("boom");
        }, 0);
        q.runLater(0, ok::incrementAndGet, 0);
        long now = advance(q, 0, 2);
        assertEquals(1, ok.get(), "一个任务抛异常不应拖垮同批其它任务");
        advance(q, now, 1);
    }

    @Test
    @DisplayName("null 任务被忽略，不进队列")
    void nullTaskIgnored() {
        TaskQueue q = new TaskQueue();
        TaskQueue.Handle h = q.runLater(0, null, 0);
        assertEquals(0, q.size());
        assertTrue(h.isCancelled(), "空任务返回的句柄应报已取消，避免调用方误以为排队成功");
        assertFalse(h.cancel());
        advance(q, 0, 3);
    }

    @Test
    @DisplayName("clear 清空队列")
    void clearEmpties() {
        TaskQueue q = new TaskQueue();
        AtomicInteger ran = new AtomicInteger();
        q.runLater(0, ran::incrementAndGet, 2);
        q.clear();
        assertEquals(0, q.size());
        long now = advance(q, 0, 5);
        assertEquals(0, ran.get(), "清空后不应再执行");
        advance(q, now, 1);
    }

    @Test
    @DisplayName("非正周期退化为单次任务")
    void nonPositivePeriodBecomesSingle() {
        TaskQueue q = new TaskQueue();
        AtomicInteger ran = new AtomicInteger();
        q.runTimer(0, ran::incrementAndGet, 0, 0);
        long now = advance(q, 0, 5);
        assertEquals(1, ran.get(), "period<=0 应按单次任务处理");
        assertEquals(0, q.size());
        advance(q, now, 1);
    }
}