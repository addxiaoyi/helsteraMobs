package dev.helstera.ai.skill;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连杀计数器（对标 MythicMobs 的 killstreak）。
 *
 * <p><b>按玩家 UUID 记，不按实例</b>：连杀是「谁杀得多」的性质，与哪只怪被死无关。
 * 复用现成的 {@code SCORES} 表是错的——那张表按实例 id 分桶，
 * 于是「同一个玩家杀了 10 只不同的怪」会记成 10 个各为 1 的实例分数。</p>
 *
 * <p><b>窗口期采用惰性求值</b>，而不是后台定时器：读取时拿当前时间戳与上次击杀
 * 时间比较，超时即视为 0。这样既不需要任何调度任务，也不会出现「定时器漏跑导致
 * 连杀不重置」——后者是那种只在服务器卡顿时偶发、且完全无法排查的静默错误。</p>
 *
 * <p><b>时钟可注入</b>（{@code nowNanos}），窗口逻辑因此能脱离服务端单测；
 * 用 {@code System.nanoTime} 而非 {@code currentTimeMillis}，避免系统时间被
 * 调整时连杀时长算错甚至永不重置。</p>
 *
 * <p>线程安全：{@link ConcurrentHashMap} + 不可变值对象，避免读改写竞态。</p>
 */
public final class KillStreak {

    /** 默认窗口：30 秒内连续击杀算同一段连杀。 */
    public static final long DEFAULT_WINDOW_NANOS = 30L * 1_000_000_000L;

    /**
     * 全局实例。
     *
     * <p>与 {@link SkillSignals} 同理：{@code SkillExtras} 的条件/动作表是静态的，
     * 没法接收注入实例，而 {@code kill-streak-at-least} 需要读同一张表。
     * 让动作表变成有状态会波及所有已注册动作，不值得。</p>
     */
    private static volatile KillStreak global = new KillStreak();

    public static KillStreak global() {
        return global;
    }

    /** 测试钩子：替换全局实例，避免用例之间互相污染。 */
    public static void resetGlobal(KillStreak s) {
        global = s == null ? new KillStreak() : s;
    }

    /** 单个玩家的连杀状态（不可变，读改写时整体替换）。 */
    private record Entry(int count, long lastKillNanos) {
    }

    private final Map<UUID, Entry> table = new ConcurrentHashMap<>();
    /**
     * 实例 -> 最近击杀它的玩家。
     *
     * <p>需要它的原因：{@code on-kill-player} 派发时 ctx 里的 target 是<b>死者</b>，
     * 而连杀属于击杀者。改派发语义会让 message-targets 之类既有动作打错人，
     * 因此单独记一份指针，条件据此反查连杀。</p>
     */
    private final Map<Integer, UUID> lastKiller = new ConcurrentHashMap<>();
    private final long windowNanos;

    public KillStreak() {
        this(DEFAULT_WINDOW_NANOS);
    }

    public KillStreak(long windowNanos) {
        // 窗口必须为正：0 或负会让每次击杀都立刻过期，连杀恒为 1，
        // 而配置能写 0，且没有任何报错
        this.windowNanos = windowNanos > 0 ? windowNanos : DEFAULT_WINDOW_NANOS;
    }

    /** 窗口长度（纳秒）。 */
    public long windowNanos() {
        return windowNanos;
    }

    /**
     * 记一次击杀，返回本次之后（含）的连杀数。
     *
     * <p>超窗时从 1 重新起算而不是从 0 加 1——结果同为 1，但显式写出来
     * 免得日后有人误改成「过期仍累加」，那会让断连后继续显示高连杀。</p>
     */
    public int record(UUID player, long nowNanos) {
        if (player == null) return 0;
        // 必须用 compute 做「读-改-写」：先 get 再 put 是竞态，
        // 8 线程并发下 1600 次击杀只记进 684（实测），而丢计数的表现是
        // 「连杀偶尔不涨」，没有报错也没有堆栈，极难归因。
        final int[] out = new int[1];
        table.compute(player, (k, prev) -> {
            out[0] = (prev != null && nowNanos - prev.lastKillNanos() <= windowNanos)
                    ? prev.count() + 1
                    : 1;
            return new Entry(out[0], nowNanos);
        });
        return out[0];
    }

    /**
     * 当前连杀数；已超窗返回 0。
     *
     * <p>超窗不删除条目：删除会让「读一次就清一次」在多线程下互相踩，
     * 且本次读取不该改变别人即将读到的值。条目改由 {@link #prune} 统一回收。</p>
     */
    public int current(UUID player, long nowNanos) {
        if (player == null) return 0;
        Entry e = table.get(player);
        if (e == null) return 0;
        return nowNanos - e.lastKillNanos() <= windowNanos ? e.count() : 0;
    }

    /** 距上次击杀是否仍在窗口内。 */
    public boolean active(UUID player, long nowNanos) {
        return current(player, nowNanos) > 0;
    }

    /** 手动清零（动作 kill-streak-reset / 玩家退出）。 */
    public void reset(UUID player) {
        if (player != null) table.remove(player);
    }

    /**
     * 记一次击杀并登记「实例 → 击杀者」。
     *
     * @return 本次之后的连杀数
     */
    public int record(int instanceId, UUID killer, long nowNanos) {
        if (killer == null) return 0;
        lastKiller.put(instanceId, killer);
        return record(killer, nowNanos);
    }

    /** 该实例最近的击杀者；从未被玩家击杀过返回 null。 */
    public UUID lastKillerOf(int instanceId) {
        return lastKiller.get(instanceId);
    }

    /** 该实例的击杀者当前连杀数；无击杀者或已超窗返回 0。 */
    public int streakOfInstance(int instanceId, long nowNanos) {
        return current(lastKiller.get(instanceId), nowNanos);
    }

    /** 实例销毁时清掉它的击杀者指针，否则随累计生成量无界增长。 */
    public void forgetInstance(int instanceId) {
        lastKiller.remove(instanceId);
    }

    /**
     * 回收已超窗的条目。
     *
     * <p>不回收的话表随玩家进出无界增长，是「跑一夜后内存缓慢上涨」的典型来源。
     * 由定时调用，但判定仍用惰性窗口，不依赖定时器是否准时。</p>
     *
     * @return 被清除的条目数
     */
    public int prune(long nowNanos) {
        int before = table.size();
        table.entrySet().removeIf(e -> nowNanos - e.getValue().lastKillNanos() > windowNanos);
        return before - table.size();
    }

    /** 全部清空（reload / 测试隔离）。 */
    public void clear() {
        table.clear();
        lastKiller.clear();
    }

    /** 当前条目数，供 /helstera debug 与测试断言。 */
    public int size() {
        return table.size();
    }

    /** 当前最高连杀数；无记录返回 0。供诊断展示。 */
    public int peak(UUID player, long nowNanos) {
        return current(player, nowNanos);
    }
}