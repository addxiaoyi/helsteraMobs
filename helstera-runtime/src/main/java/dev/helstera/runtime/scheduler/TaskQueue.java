package dev.helstera.runtime.scheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * 延时/重复任务队列。
 *
 * <p>刻意不依赖 Bukkit：真实调度在 {@link HelsteraScheduler} 里完成（推进 tick 计数后
 * 调用 {@link #drain}），队列本体只是纯数据结构，可以脱离服务端直接验证时序。
 * 之前把队列直接写在 HelsteraScheduler 里，导致单测必须反射构造该类而触发
 * Bukkit 依赖加载失败——这正是它该被拆出来的原因。</p>
 *
 * <p><b>线程模型</b>：仅主线程调用。</p>
 */
public final class TaskQueue {

    /** 取消句柄。 */
    public interface Handle {
        boolean cancel();

        boolean isCancelled();
    }

    private static final class Task {
        final Runnable body;
        long nextRun;
        final int period;      // <=0 表示单次
        int remaining;         // 剩余执行次数
        boolean cancelled;

        Task(Runnable body, long nextRun, int period, int remaining) {
            this.body = body;
            this.nextRun = nextRun;
            this.period = period;
            this.remaining = remaining;
        }
    }

    /** 每 tick 执行上限：队列异常膨胀时至少保住实例更新这条主路径。 */
    private static final int MAX_PER_TICK = 64;
    /** 队列容量上限，超出直接拒绝新任务，避免无限增长。 */
    private static final int MAX_SIZE = 4096;

    private final List<Task> tasks = new ArrayList<>();

    /** 延迟 {@code delay} tick 执行一次。 */
    public Handle runLater(long now, Runnable body, int delay) {
        return enqueue(now, body, Math.max(0, delay), 0, 1);
    }

    /** 延迟 {@code delay} tick 后，每 {@code period} tick 执行一次。 */
    public Handle runTimer(long now, Runnable body, int delay, int period) {
        if (period <= 0) return runLater(now, body, delay);
        return enqueue(now, body, Math.max(0, delay), Math.max(1, period), Integer.MAX_VALUE);
    }

    private Handle enqueue(long now, Runnable body, int delay, int period, int repeats) {
        if (body == null || tasks.size() >= MAX_SIZE) {
            return noop();
        }
        // nextRun = now + max(1, delay)：delay N 就是 N tick 之后。
        // 最早也排到下一 tick，避免任务在安排它的那一 tick 内就地重入执行。
        Task t = new Task(body, now + Math.max(1, delay), period, repeats);
        tasks.add(t);
        return new Handle() {
            @Override
            public boolean cancel() {
                if (t.cancelled) return false;
                t.cancelled = true;
                return true;
            }

            @Override
            public boolean isCancelled() {
                return t.cancelled;
            }
        };
    }

    private static Handle noop() {
        return new Handle() {
            @Override
            public boolean cancel() {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };
    }

    /**
     * 推进一个 tick，执行到期任务。
     *
     * <p>单个任务抛异常不中断本 tick 剩余任务：写坏的技能效果不该让同批其它
     * 模型的效果一起停摆。</p>
     */
    public void drain(long now) {
        if (tasks.isEmpty()) return;
        int ran = 0;
        for (int i = 0; i < tasks.size(); ) {
            if (ran >= MAX_PER_TICK) break;
            Task t = tasks.get(i);
            if (t.cancelled) {
                tasks.remove(i);
                continue;
            }
            if (now < t.nextRun) {
                i++;
                continue;
            }
            ran++;
            try {
                t.body.run();
            } catch (Throwable ignored) {
            }
            if (t.period <= 0 || --t.remaining <= 0 || t.cancelled) {
                tasks.remove(i);
            } else {
                t.nextRun = now + t.period;
                i++;
            }
        }
    }

    /** 待执行任务数。 */
    public int size() {
        return tasks.size();
    }

    /** 清空（停服或重载时调用）。 */
    public void clear() {
        tasks.clear();
    }
}