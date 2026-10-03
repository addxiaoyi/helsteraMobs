package dev.helstera.runtime.perf;

/**
 * 固定容量滚动窗口的数值统计。
 *
 * <p>为什么不用瞬时值：调度器原本只保留「上一个 tick 的耗时」，这个数字
 * 既可能是抖动，也可能是性能恶化，单点读数无法区分。判断性能要看分布——
 * 平均值会被单次尖峰拉高，p99 才能暴露真正的卡顿。</p>
 *
 * <p>刻意不依赖 Bukkit，因此可脱离服务端直接单测。</p>
 *
 * <p><b>线程模型</b>：仅主线程调用（写入与读取都在同一 tick 内），
 * 因此用普通数组即可，无需同步。若将来跨线程读取快照，需要加 volatile
 * 或改用环形缓冲。</p>
 */
public final class RollingMetrics {

    private final long[] samples;
    private int cursor;
    private int filled;

    public RollingMetrics(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity 必须为正: " + capacity);
        this.samples = new long[capacity];
    }

    /** 记录一个样本（单位由调用方决定，调度器传纳秒）。 */
    public void record(long value) {
        samples[cursor] = value;
        cursor = (cursor + 1) % samples.length;
        if (filled < samples.length) filled++;
    }

    /** 已积累的样本数，上限为窗口容量。 */
    public int count() {
        return filled;
    }

    /** 窗口容量。 */
    public int capacity() {
        return samples.length;
    }

    /** 窗口是否已满——未满时 p99 样本量不足，读数仅供参考。 */
    public boolean isFull() {
        return filled == samples.length;
    }

    public long max() {
        if (filled == 0) return 0;
        long m = Long.MIN_VALUE;
        for (int i = 0; i < filled; i++) m = Math.max(m, samples[i]);
        return m;
    }

    public long min() {
        if (filled == 0) return 0;
        long m = Long.MAX_VALUE;
        for (int i = 0; i < filled; i++) m = Math.min(m, samples[i]);
        return m;
    }

    public double mean() {
        if (filled == 0) return 0;
        double sum = 0;
        for (int i = 0; i < filled; i++) sum += samples[i];
        return sum / filled;
    }

    /**
     * 百分位数（线性插值）。
     *
     * <p>窗口满载时先拷贝再排序，避免打乱环形顺序。</p>
     */
    public double percentile(double p) {
        if (filled == 0) return 0;
        if (p <= 0) return min();
        if (p >= 1) return max();
        long[] copy = new long[filled];
        System.arraycopy(samples, 0, copy, 0, filled);
        java.util.Arrays.sort(copy);
        double rank = p * (filled - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return copy[lo];
        double frac = rank - lo;
        return copy[lo] + (copy[hi] - copy[lo]) * frac;
    }

    /** 一次性取全部关键读数，避免调用方反复排序。 */
    public Snapshot snapshot() {
        return new Snapshot(count(), capacity(), min(), max(), mean(), percentile(0.5), percentile(0.95), percentile(0.99));
    }

    /** 清空窗口（重载/停服时调用，避免旧数据污染新一轮观测）。 */
    public void reset() {
        cursor = 0;
        filled = 0;
        java.util.Arrays.fill(samples, 0L);
    }

    /** 不可变的统计快照。 */
    public record Snapshot(int count, int capacity, long min, long max,
                           double mean, double p50, double p95, double p99) {

        /** 样本是否足够支撑百分位判断。 */
        public boolean reliable() {
            return count >= capacity / 2;
        }

        /** 转成一行可读文本，用于日志与命令输出。 */
        public String describe(String unit) {
            if (count == 0) return "（无样本）";
            return String.format("n=%d/%d  min=%d  mean=%.2f  p95=%.2f  p99=%.2f  max=%d %s",
                    count, capacity, min, p50, p95, p99, max, unit);
        }
    }
}