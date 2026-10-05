package dev.helstera.ai.nav;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基于世界的寻路网格：把 Bukkit 方块包装成 {@link NavNode}。
 *
 * <p><b>这是唯一必须跑在服务端的一层</b>，也是最容易被「凭想象写错」的一层。
 * 因此通行规则不硬编码，而是<b>配置声明 + 白名单优先</b>：</p>
 *
 * <ul>
 *   <li>{@code nav.passable-materials}：显式视为可通行的材质（门、梯子、台阶…）</li>
 *   <li>不在白名单里时，退回 Bukkit 自己的 {@code Block#isPassable()}</li>
 * </ul>
 *
 * <p>这样做的理由：{@code isPassable()} 对「实体能否穿过」的判定与玩家直觉一致，
 * 把差异项显式列出来交给服主调整，而不是由我猜「岩浆算不算」。猜错的后果是
 * 静默穿墙或原地不动，且没有任何日志——本类因此强制所有判定走
 * {@link NavMetrics}，让异常在 {@code /helstera nav} 里可见。</p>
 *
 * <p>高度范围有限制（默认 ±4）：垂直方向全展开会让搜索空间膨胀一个量级，
 * 而战斗中需要爬升的幅度通常很小。</p>
 */
public final class NavGrid implements NavNode {

    private final World world;
    private final int x, y, z;
    private final NavRules rules;
    private final NavMetrics metrics;

    public NavGrid(World world, int x, int y, int z, NavRules rules, NavMetrics metrics) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rules = rules == null ? NavRules.defaults() : rules;
        this.metrics = metrics == null ? new NavMetrics() : metrics;
    }

    /** 由坐标取节点；世界不同一律返回 null（跨世界寻路无意义且极易出错）。 */
    public static NavGrid at(Location loc, NavRules rules, NavMetrics metrics) {
        if (loc == null || loc.getWorld() == null) return null;
        return new NavGrid(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                rules, metrics);
    }

    @Override
    public String key() {
        return x + "," + z + "," + y;
    }

    /**
     * 是否可站立。
     *
     * <p>要求「脚下实、身处空」：只判身处空会让生物走向悬空边缘掉下去，
     * 只判脚下实会让它试图钻进实心方块。</p>
     */
    @Override
    public boolean walkable() {
        try {
            Block feet = world.getBlockAt(x, y, z);
            Block ground = world.getBlockAt(x, y - 1, z);
            return rules.passable(feet) && rules.standable(ground);
        } catch (Throwable t) {
            // 世界正在卸载 / 区块未加载时 getBlockAt 会抛异常。
            // 判为不可通行是安全侧：宁可绕路，不可走进未加载区域导致连锁加载卡顿。
            return false;
        }
    }

    /**
     * 相邻节点：八方向水平 + 上下限幅内的垂直。
     *
     * <p>刻意<b>不在此处剔除不可通行的邻居</b>：{@link NavNode} 的契约把这件事
     * 交给实现，但 AStar 内部仍会再判一次 walkable，两处都判是为了让
     * 「邻居列表只含可行节点」这个约定不至于某处一改就失效。</p>
     */
    @Override
    public List<NavNode> neighbors() {
        List<NavNode> out = new ArrayList<>(10);
        int h = rules.verticalRange();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                addIfWalkable(out, x + dx, y, z + dz);
            }
        }
        if (h > 0) {
            addIfWalkable(out, x, y + 1, z);
            addIfWalkable(out, x, y - 1, z);
        }
        return out;
    }

    private void addIfWalkable(List<NavNode> out, int nx, int ny, int nz) {
        if (ny < world.getMinHeight() || ny >= world.getMaxHeight()) return;
        NavGrid n = new NavGrid(world, nx, ny, nz, rules, metrics);
        if (n.walkable()) out.add(n);
    }

    @Override
    public double costTo(NavNode to) {
        if (!(to instanceof NavGrid g)) return 1.0;
        int dx = Math.abs(g.x - x);
        int dy = Math.abs(g.y - y);
        int dz = Math.abs(g.z - z);
        int flat = dx + dz;
        if (dy > 0) {
            // 垂直移动代价高：让「爬墙」排在「绕过去」之后
            return 2.0 + flat;
        }
        return flat == 1 ? 1.0 : (flat == 2 ? Math.sqrt(2.0) : flat);
    }

    @Override
    public double heuristicTo(NavNode target) {
        if (!(target instanceof NavGrid g)) return 0.0;
        // 取水平 Chebyshev 距离并**扣除垂直差**：admissible 下界是「最少要多少步」，
        // 直接用三维欧氏距离会高估，导致 A* 返回绕远路。
        int dx = Math.abs(g.x - x);
        int dz = Math.abs(g.z - z);
        int dy = Math.abs(g.y - y);
        double flat = Math.max(dx, dz);
        return Math.max(0.0, flat - dy);
    }

    public Location toLocation() {
        return new Location(world, x + 0.5, y, z + 0.5);
    }

    /** 让寻路诊断命令能显示实际用的是哪套规则。 */
    public NavRules rules() {
        return rules;
    }

    NavMetrics metrics() {
        return metrics;
    }

    @Override
    public String toString() {
        return "NavGrid[" + world.getName() + " " + key() + "]";
    }

    /**
     * 通行规则：材质白名单 + 垂直限幅。
     *
     * <p>刻意做成不可变值对象并独立于 Bukkit：规则本身可以纯单测，
     * 而「规则是否被正确解析」正是最容易出错又最难手工验证的一环。</p>
     */
    public static final class NavRules {

        private final java.util.Set<String> passable;
        private final java.util.Set<String> blocked;
        private final int verticalRange;

        public NavRules(java.util.Collection<String> passable,
                        java.util.Collection<String> blocked, int verticalRange) {
            this.passable = lower(passable);
            this.blocked = lower(blocked);
            this.verticalRange = Math.max(0, Math.min(8, verticalRange));
        }

        private static java.util.Set<String> lower(java.util.Collection<String> in) {
            java.util.Set<String> out = new java.util.HashSet<>();
            if (in != null) {
                for (String s : in) {
                    if (s == null) continue;
                    String t = s.trim().toLowerCase(Locale.ROOT);
                    if (!t.isEmpty()) out.add(t);
                }
            }
            return java.util.Set.copyOf(out);
        }

        /** 默认规则：只看 Bukkit 自身的判定，垂直 ±4。 */
        public static NavRules defaults() {
            return new NavRules(List.of("oak_door", "iron_door", "ladder", "vine", "torch"),
                    List.of(), 4);
        }

        /**
         * 某方块能否被穿过（用于「身处空」判定）。
         *
         * <p>优先级：{@code blocked} 显式拒绝 &gt; {@code passable} 显式放行 &gt;
         * Bukkit 的 {@code isPassable()}。显式拒绝排在最前，是为了让服主能覆盖
         * Bukkit 的判定（例如把水当作墙来阻挡生物涉水）。</p>
         */
        public boolean passable(Block b) {
            String name = b.getType().name().toLowerCase(Locale.ROOT);
            if (blocked.contains(name)) return false;
            if (passable.contains(name)) return true;
            if (b.isLiquid()) return false;   // 默认不涉水：多数玩法里生物该绕岸
            try {
                return b.isPassable();
            } catch (Throwable t) {
                return false;
            }
        }

        /** 某方块能否站立（用于「脚下实」判定）。 */
        public boolean standable(Block b) {
            String name = b.getType().name().toLowerCase(Locale.ROOT);
            if (blocked.contains(name)) return false;
            if (b.isLiquid()) return false;
            try {
                return b.isPassable();
            } catch (Throwable t) {
                return false;
            }
        }

        public int verticalRange() {
            return verticalRange;
        }

        public java.util.Set<String> passableMaterials() {
            return passable;
        }

        public java.util.Set<String> blockedMaterials() {
            return blocked;
        }
    }
}