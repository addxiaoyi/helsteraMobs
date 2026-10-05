package dev.helstera.ai.lever;

/**
 * 拉杆作用范围：一段立方体区域。
 *
 * <p><b>刻意做成纯数学</b>：区域判定是最该被密集单测的部分，而带上 Bukkit 的
 * {@code Location}/{@code World} 就等于单测必须起服务端。坐标用 {@code int} 格点
 * 表示，世界名单列——这既是坐标的天然粒度，也让「不同世界的区域」不会误命中。</p>
 *
 * <p>选取正反面是关键：拉杆往往只配一个坐标，若按「单点 → 退化立方体」处理，
 * 相邻方块上的同类拉杆会互相命中，表现为「按一个拉杆触发两个事件」。</p>
 */
public record LeverRegion(String world, int x1, int y1, int z1, int x2, int y2, int z2) {

    /**
     * 构造区域，自动把两个角点规整为 min/max。
     *
     * <p>不要求配置里 pos1 小于 pos2——作者常把 pos1 写在 pos2 之后，
     * 若不规整就会得到一个「空区域」，表现为拉杆完全不触发且没有任何报错。</p>
     */
    public static LeverRegion of(String world, int x1, int y1, int z1,
                                 int x2, int y2, int z2) {
        return new LeverRegion(world,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** 单点区域：上下各延伸 1 格，覆盖拉杆与其正反面附着方块。 */
    public static LeverRegion block(String world, int x, int y, int z) {
        return of(world, x, y, z, x, y, z);
    }

    /** 是否覆盖某格点。 */
    public boolean contains(String w, int x, int y, int z) {
        // 世界必须完全相等：Bukkit 的 World 对象跨服同名，字符串比较反而更安全，
        // 且避免为了比较而持有 World 引用
        if (world == null || !world.equals(w)) return false;
        return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }

    /** 区域体积（格数），用于告警：体积异常大通常是坐标写错。 */
    public long volume() {
        return (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
    }

    @Override
    public String toString() {
        return world + " " + x1 + "," + y1 + "," + z1 + " -> " + x2 + "," + y2 + "," + z2;
    }
}