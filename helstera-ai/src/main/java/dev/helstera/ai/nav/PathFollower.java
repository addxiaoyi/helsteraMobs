package dev.helstera.ai.nav;

import java.util.List;

/**
 * 路径跟随器：决定「现在该往哪走」以及「什么时候该重算」。
 *
 * <p><b>与 A* 分离的原因</b>：A* 是无状态搜索，跟随是<b>有状态</b>的——
 * 需要记住上次算出的路径、当前走到第几个路点、上次重算的时间。两者混在一起会让
 * A* 无法被独立单测（要构造完整跟随状态才跑得起来），而搜索正确性恰恰是最该测的。</p>
 *
 * <p><b>三项工作策略，均为「宁可退化也不卡死主线程」</b>：
 * <ol>
 *   <li><b>重算节流 2 秒</b>：目标每帧都在动，逐帧重算是纯浪费。间隔过短则在
 *       多生物集群里把主线程吃满；过长则表现为「绕远路后长时间不纠正」。</li>
 *   <li><b>单次预算 256</b>：略低于 A* 默认值。跟随场景通常路径不长，
 *       256 足够；超限就放弃这次重算，沿用旧路径（若没有旧路径则直连目标）。</li>
 *   <li><b>卡死检测 2 秒</b>：连续 2 秒没有推进就强制重算并请求侧移。
 *       墙后目标、目标瞬移、被顶住都会走进这个状态。</li>
 * </ol></p>
 *
 * <p>时钟注入而非直接调 {@code System.currentTimeMillis()}：否则节流与卡死
 * 检测无法在单测里推进时间，只能靠 sleep 测——那会让测试既慢又不稳定。</p>
 */
public final class PathFollower {

    /** 重算节流：两次寻路之间的最小间隔（毫秒）。 */
    public static final long REPATH_INTERVAL_MS = 2000L;
    /** 卡死判定：多久没有推进就认为被卡住（毫秒）。 */
    public static final long STUCK_TIMEOUT_MS = 2000L;
    /** 到达路点的判定半径（格）。 */
    public static final double WAYPOINT_REACH = 0.6;
    /** 推进判定的最小位移（格）。小于它算原地打转。 */
    public static final double PROGRESS_EPS = 0.05;

    private final long repathInterval;
    private final long stuckTimeout;
    private final double waypointReach;

    private List<NavNode> path = List.of();
    private int index;
    private long lastRepath = Long.MIN_VALUE;
    private long lastProgressAt;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    /** 请求侧移脱困：置位后由调用方读取并清空。 */
    private boolean wantsSidestep;

    public PathFollower() {
        this(REPATH_INTERVAL_MS, STUCK_TIMEOUT_MS, WAYPOINT_REACH);
    }

    public PathFollower(long repathInterval, long stuckTimeout, double waypointReach) {
        this.repathInterval = repathInterval;
        this.stuckTimeout = stuckTimeout;
        this.waypointReach = waypointReach;
    }

    /**
     * 是否该重算路径。
     *
     * <p>首次调用一律返回 true（{@code lastRepath} 初值让节流恒不成立），
     * 否则第一次移动就得先空走两秒才找路。</p>
     */
    public boolean shouldRepath(long now) {
        return lastRepath == Long.MIN_VALUE || now - lastRepath >= repathInterval;
    }

    /** 标记一次寻路已发生，压住节流窗口。 */
    public void onRepathed(long now) {
        this.lastRepath = now;
        if (Double.isNaN(lastX)) {
            this.lastProgressAt = now;
        }
    }

    /** 是否卡住（连续超过阈值没有推进）。 */
    public boolean isStuck(long now) {
        if (Double.isNaN(lastX)) return false;
        return now - lastProgressAt >= stuckTimeout;
    }

    /**
     * 记录本帧位置，判断是否算「有推进」。
     *
     * @return 是否产生了推进
     */
    public boolean recordProgress(long now, double x, double z) {
        boolean moved = Double.isNaN(lastX)
                || Math.abs(x - lastX) >= PROGRESS_EPS
                || Math.abs(z - lastZ) >= PROGRESS_EPS;
        lastX = x;
        lastZ = z;
        if (moved) {
            lastProgressAt = now;
            return true;
        }
        return false;
    }

    /** 安装新路径，重置路点游标。 */
    public void setPath(List<NavNode> newPath) {
        this.path = newPath == null ? List.of() : List.copyOf(newPath);
        // 从 1 开始：第 0 个路点就是自己所在的格子，朝它走没有意义
        this.index = Math.min(1, this.path.size() - 1);
        if (this.index < 0) this.index = 0;
    }

    public List<NavNode> path() {
        return path;
    }

    public boolean hasPath() {
        return !path.isEmpty();
    }

    public void clearPath() {
        path = List.of();
        index = 0;
    }

    /**
     * 取下一个应前往的路点。
     *
     * @return null 表示路径已走完，调用方应直连目标
     */
    public NavNode nextWaypoint() {
        if (index >= path.size()) return null;
        return path.get(index);
    }

    /**
     * 抵达当前路点则推进游标。
     *
     * @return 是否发生了推进
     */
    public boolean advanceIfReached(double x, double z) {
        NavNode wp = nextWaypoint();
        if (wp == null) return false;
        if (distanceTo(wp, x, z) > waypointReach) return false;
        index++;
        return true;
    }

    /**
     * 侧移脱困请求。
     *
     * <p>卡死时仅重算路径往往无济于事——目标同样在墙后，重算出的路径依旧到不了。
     * 侧移让生物先挪出墙角再重算，才有实际区别。</p>
     */
    public void requestSidestep(long now) {
        wantsSidestep = true;
        lastProgressAt = now;
        clearPath();
        // 松开节流，否则侧移后仍要干等两秒才开始找路
        lastRepath = Long.MIN_VALUE;
    }

    /** 读取并清空侧移请求。 */
    public boolean consumeSidestep() {
        boolean v = wantsSidestep;
        wantsSidestep = false;
        return v;
    }

    /**
     * 距路点的水平距离。
     *
     * <p>用 {@link NavNode#heuristicTo} 的目标节点算不了（那是到某目标的估计），
     * 故约定路点必须有 {@code key()} 可解析的坐标。这里统一走 {@link #locate}，
     * 解析失败返回 {@link Double#NaN} 让调用方按「已抵达」处理，避免卡死在同一路点。</p>
     */
    private static double distanceTo(NavNode n, double x, double z) {
        double[] p = locate(n);
        if (p == null) return Double.NaN;
        return Math.hypot(p[0] - x, p[1] - z);
    }

    /**
     * 从 {@link NavNode#key()} 解析出 x/z。
     *
     * <p>约定 key 形如 {@code "x,y"}。解析不出返回 null——宁可直接放行，
     * 也不因为 key 格式特殊就把生物永久钉在原地。</p>
     */
    protected static double[] locate(NavNode n) {
        if (n == null) return null;
        String k = n.key();
        if (k == null) return null;
        int i = k.indexOf(',');
        if (i <= 0 || i >= k.length() - 1) return null;
        try {
            return new double[]{
                    Double.parseDouble(k.substring(0, i).trim()),
                    Double.parseDouble(k.substring(i + 1).trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}