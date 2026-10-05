package dev.helstera.ai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连杀计数器测试。
 *
 * <p>时钟全部用注入的纳秒值，不读系统时钟——窗口逻辑是这里唯一容易写错的
 * 部分（「超时归零」写反会表现为连杀永不重置，而那看起来像配置问题）。</p>
 */
class KillStreakTest {

    private static final long SEC = 1_000_000_000L;
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    @DisplayName("窗口内连续击杀累加")
    void consecutiveKillsAccumulate() {
        var k = new KillStreak(30 * SEC);
        assertEquals(1, k.record(A, 0L));
        assertEquals(2, k.record(A, 5 * SEC));
        assertEquals(3, k.record(A, 29 * SEC));
        assertEquals(3, k.current(A, 29 * SEC), "未超窗时读取应等于累计值");
    }

    @Test
    @DisplayName("超窗后归零重算，且不是接着往上加")
    void expiredWindowRestartsAtOne() {
        var k = new KillStreak(30 * SEC);
        k.record(A, 0L);
        k.record(A, 1 * SEC);
        k.record(A, 2 * SEC);
        // 边界语义是 <=：32s - 2s == 30s，仍算窗口内，连杀继续累加
        assertEquals(4, k.record(A, 32 * SEC), "恰好等于窗口长度仍属窗口内（<= 语义）");
        // 明显超窗：60s - 32s == 28s ... 仍在窗口内；改用 63s，间隔 31s 才真正超窗
        assertEquals(1, k.record(A, 63 * SEC), "间隔超过窗口必须从 1 重新起算，而不是接着加");
        assertEquals(1, k.current(A, 63 * SEC));
    }

    @Test
    @DisplayName("超窗后读取返回 0，而不是残留的上次值")
    void expiredReadIsZero() {
        var k = new KillStreak(10 * SEC);
        k.record(A, 0L);
        assertEquals(1, k.current(A, 5 * SEC));
        assertEquals(0, k.current(A, 11 * SEC),
                "超窗后仍返回旧值会让「连杀」在玩家停手后一直挂着");
        assertFalse(k.active(A, 11 * SEC));
    }

    @Test
    @DisplayName("不同玩家互不影响")
    void streaksArePerPlayer() {
        var k = new KillStreak(30 * SEC);
        k.record(A, 0L);
        k.record(A, 1 * SEC);
        assertEquals(1, k.record(B, 2 * SEC), "B 的第一次击杀应为 1");
        assertEquals(2, k.current(A, 2 * SEC), "A 的连杀不该被 B 影响");
        assertEquals(1, k.current(B, 2 * SEC));
    }

    @Test
    @DisplayName("按实例反查击杀者，且不同实例互不串号")
    void lastKillerPerInstance() {
        var k = new KillStreak(30 * SEC);
        k.record(7, A, 0L);
        k.record(7, A, 1 * SEC);
        assertEquals(A, k.lastKillerOf(7));
        assertEquals(2, k.streakOfInstance(7, 1 * SEC));

        k.record(9, B, 2 * SEC);
        assertEquals(B, k.lastKillerOf(9));
        assertEquals(1, k.streakOfInstance(9, 2 * SEC));
        assertEquals(2, k.streakOfInstance(7, 2 * SEC), "实例 7 的连杀不该被实例 9 影响");
    }

    @Test
    @DisplayName("从未被玩家击杀的实例返回 null / 0")
    void neverKilledInstance() {
        var k = new KillStreak(30 * SEC);
        assertNull(k.lastKillerOf(42));
        assertEquals(0, k.streakOfInstance(42, 0L),
                "没被击杀过就返回 0；返回 1 会让「首次见面即触发连杀奖励」");
    }

    @Test
    @DisplayName("reset 清零后读取为 0")
    void resetClears() {
        var k = new KillStreak(30 * SEC);
        k.record(A, 0L);
        k.record(A, 1 * SEC);
        assertEquals(2, k.current(A, 1 * SEC));
        k.reset(A);
        assertEquals(0, k.current(A, 1 * SEC));
        assertEquals(1, k.record(A, 2 * SEC), "清零后下一次击杀应从 1 起算");
    }

    @Test
    @DisplayName("prune 只回收超窗条目，不动窗口内的")
    void pruneOnlyRemovesExpired() {
        var k = new KillStreak(10 * SEC);
        k.record(A, 0L);          // 旧的，应被回收
        k.record(B, 9 * SEC);     // 新的，应保留
        assertEquals(2, k.size());
        // prune 边界同样按 > window 判定：11s - 0s > 10 回收 A，
        // 而 11s - 9s = 2s 保留 B。写成 10s 恰好等于窗口，A 不会被回收
        int removed = k.prune(11 * SEC);
        assertEquals(1, removed, "只应回收 A");
        assertEquals(1, k.size());
        assertEquals(0, k.current(A, 11 * SEC));
        assertEquals(1, k.current(B, 11 * SEC));
    }

    @Test
    @DisplayName("实例销毁时清掉击杀者指针")
    void forgetInstanceClearsPointer() {
        var k = new KillStreak(30 * SEC);
        k.record(5, A, 0L);
        k.forgetInstance(5);
        assertNull(k.lastKillerOf(5), "指针不回收会随累计生成量无界增长");
        assertEquals(1, k.current(A, 0L), "清指针不应连带清掉玩家自己的连杀");
    }

    @Test
    @DisplayName("窗口为 0 或负时回退到默认值，不让连杀恒为 1")
    void nonPositiveWindowFallsBack() {
        // 0 窗口会让每次击杀都立刻过期，症状是「连杀永远是 1」且无任何报错
        assertEquals(KillStreak.DEFAULT_WINDOW_NANOS, new KillStreak(0L).windowNanos());
        assertEquals(KillStreak.DEFAULT_WINDOW_NANOS, new KillStreak(-5L).windowNanos());
        var k = new KillStreak(0L);
        assertEquals(1, k.record(A, 0L));
        assertEquals(2, k.record(A, 1L), "回退到默认窗口后应能正常累加");
    }

    @Test
    @DisplayName("null 击杀者安全返回 0，不写表")
    void nullKillerIsSafe() {
        var k = new KillStreak(30 * SEC);
        assertEquals(0, k.record((UUID) null, 0L));
        assertEquals(0, k.record(1, null, 0L));
        assertEquals(0, k.current(null, 0L));
        assertEquals(0, k.size(), "null 击杀者不应留下空条目");
    }

    @Test
    @DisplayName("clear 清空玩家表与实例指针")
    void clearEmptiesEverything() {
        var k = new KillStreak(30 * SEC);
        k.record(3, A, 0L);
        k.record(A, 0L);
        assertEquals(1, k.size());
        k.clear();
        assertEquals(0, k.size());
        assertNull(k.lastKillerOf(3));
    }

    @Test
    @DisplayName("并发记录不会丢计数")
    void concurrentRecordDoesNotLoseCounts() throws Exception {
        var k = new KillStreak(600 * SEC);
        int threads = 8, per = 200;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        // 用 CountDownLatch(1) + 先全部 submit 再 countDown，而不是
        // CountDownLatch(threads) 做栅栏：后者会死锁——newFixedThreadPool
        // 按需建线程，第 8 个任务要等前一个空闲才起线程，而 8 个任务
        // 全卡在 await() 上永远等不到第 8 个线程。
        // 症状是整个构建挂死在单个测试上，既不报错也不超时。
        var gate = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    gate.await();
                    for (int i = 0; i < per; i++) k.record(A, 1L);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        gate.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS),
                "并发用例超时：说明线程没被正常调度");
        pool.shutdownNow();
        assertEquals(threads * per, k.current(A, 1L),
                "读改写竞态会丢计数，而丢计数表现为「连杀偶尔不涨」，极难复现");
    }
}