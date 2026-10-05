package dev.helstera.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToDoubleFunction;

/**
 * 单个生物实例的仇恨表：记录各目标造成的威胁值，选出当前首要目标。
 *
 * <p>此前 AI 只有「一个 target 字段」，且每次感知到更近的玩家就直接覆盖。
 * 后果是典型的群战混乱：三个人同时打 Boss，Boss 被最近的人吸走，坦克拉不住仇恨。
 * MythicMobs 用 ThreatTable 解决这件事。</p>
 *
 * <p><b>威胁值构成</b>：{@code 伤害量 × 距离权重}。权重随距离下降但保留下限，
 * 让「远程输出拉仇恨、近战坦克拉住」成为可配置策略而非巧合。</p>
 *
 * <p><b>衰减</b>：按 {@code decayPerSecond} 线性衰减。完全不衰减的话，
 * 玩家打一下跑掉再回来会永久保持高仇恨，而路过碰一下的路人再也拉不走。
 * 衰减让仇恨能随时间自然洗掉。</p>
 *
 * <p><b>可测性设计</b>：本类<b>完全不引用 Bukkit</b>。内核按 UUID 运算，
 * 玩家解析与时钟都是注入的 {@link Function}/{@link LongSupplier}。
 * 这不是洁癖——早期版本在排序里直接调 {@code Bukkit.getPlayer(id)}，
 * 单测环境恒返回 null，导致「并列时是否确定性排序」这个最容易出错的规则
 * 完全无法被测试覆盖。</p>
 */
public final class ThreatTable {

    /** 威胁低于此值视为清零，避免残留微量仇恨干扰排序。 */
    private static final double EPSILON = 0.01;

    private static final class Entry {
        double threat;
        long lastSeen;
    }

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final double decayPerSecond;
    private final double distanceWeightFalloff;
    private final LongSupplier clock;
    /** 由 AiManager 注入：UUID -> 玩家。仅在需要返回 Player 时使用。 */
    private final Function<UUID, ?> playerResolver;

    public ThreatTable(double decayPerSecond, double distanceWeightFalloff,
                       LongSupplier clock, Function<UUID, ?> playerResolver) {
        this.decayPerSecond = Math.max(0, decayPerSecond);
        this.distanceWeightFalloff = Math.max(0, distanceWeightFalloff);
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.playerResolver = playerResolver;
    }

    /** 记录一次伤害威胁。 */
    public void addThreat(UUID id, double rawDamage, double distance) {
        if (id == null || rawDamage <= 0) return;
        double weight = 1.0;
        if (distanceWeightFalloff > 0 && distance > 0) {
            // 远处权重下降但保底 0.5，避免远程输出完全拉不到仇恨
            weight = Math.max(0.5, 1.0 - (distance / (distanceWeightFalloff * 4)) * 0.5);
        }
        Entry e = entries.computeIfAbsent(id, k -> new Entry());
        e.threat += rawDamage * weight;
        e.lastSeen = clock.getAsLong();
    }

    /**
     * 记录一次「无伤害的仇恨获取」。
     *
     * <p>对应 MythicMobs 的 {@code threat} mechanic：手动加威胁，与是否造成伤害无关。</p>
     */
    public void addThreat(UUID id, double amount) {
        if (id == null || amount <= 0) return;
        Entry e = entries.computeIfAbsent(id, k -> new Entry());
        e.threat += amount;
        e.lastSeen = clock.getAsLong();
    }

    /**
     * 选出当前首要目标的 UUID。
     *
     * <p>按威胁降序；并列时取最近者；仍并列则按 UUID 序，
     * 保证<b>确定性</b>——否则每 tick 因 map 遍历顺序微变而在等威胁目标间
     * 反复横跳，表现为模型周期性抽搐转向。</p>
     *
     * @param distanceFn 目标到本实例的距离；可为 null（视为 0）
     * @return 首要目标 UUID；无有效目标返回 null
     */
    public UUID top(ToDoubleFunction<UUID> distanceFn) {
        long now = clock.getAsLong();
        decayTo(now);

        List<Map.Entry<UUID, Entry>> list = new ArrayList<>(entries.entrySet());
        list.sort(comparator(distanceFn));

        for (Map.Entry<UUID, Entry> en : list) {
            if (en.getValue().threat > 0) return en.getKey();
        }
        return null;
    }

    /** 并列时的次级目标（用于「主目标不可达时切副目标」）。 */
    public List<UUID> ordered(ToDoubleFunction<UUID> distanceFn) {
        long now = clock.getAsLong();
        decayTo(now);
        List<Map.Entry<UUID, Entry>> list = new ArrayList<>(entries.entrySet());
        list.sort(comparator(distanceFn));
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> en : list) {
            if (en.getValue().threat > 0) out.add(en.getKey());
        }
        return out;
    }

    private Comparator<Map.Entry<UUID, Entry>> comparator(ToDoubleFunction<UUID> distanceFn) {
        return (a, b) -> {
            int t = Double.compare(b.getValue().threat, a.getValue().threat);
            if (t != 0) return t;
            double da = distanceFn == null ? 0 : distanceFn.applyAsDouble(a.getKey());
            double db = distanceFn == null ? 0 : distanceFn.applyAsDouble(b.getKey());
            int d = Double.compare(da, db);
            return d != 0 ? d : a.getKey().compareTo(b.getKey());
        };
    }

    /** 衰减并清理归零条目。 */
    private void decayTo(long now) {
        for (Entry e : entries.values()) {
            if (decayPerSecond > 0 && e.lastSeen > 0) {
                double seconds = Math.max(0, (now - e.lastSeen) / 1000.0);
                // 指数衰减而非线性：线性式 threat -= threat*rate*seconds 在
                // seconds > 1/rate 时会变负并被夹到 0，等于「过了 1/rate 秒就
                // 全清」——10000 点仇恨与 10 点仇恨的绝对衰减量相同，坦克攒的
                // 仇恨毫无意义，正是仇恨表要解决的问题本身。
                e.threat *= Math.exp(-decayPerSecond * seconds);
            }
            if (e.threat < EPSILON) e.threat = 0;
        }
        // 清掉已衰减为零的条目：否则 map 会随玩家进出持续增长
        entries.entrySet().removeIf(en -> en.getValue().threat <= 0);
    }

    /** 当前全部有效目标（UUID -> 威胁值），按降序。供 /helstera debug 展示。 */
    public Map<UUID, Double> snapshot() {
        decayTo(clock.getAsLong());
        Map<UUID, Double> out = new LinkedHashMap<>();
        entries.entrySet().stream()
                .filter(en -> en.getValue().threat > 0)
                .sorted((a, b) -> Double.compare(b.getValue().threat, a.getValue().threat))
                .forEach(en -> out.put(en.getKey(), en.getValue().threat));
        return out;
    }

    /**
     * 某目标的当前威胁值；无记录为 0。
 *
     * <p>先推进衰减再读：否则返回的是「最后一次被记录时的值」而非「此刻的威胁」。
     * 同一份数据在 {@code top()} 与 {@code threatOf()} 给出不同答案，
     * 会让按仇恨配平机制的作者完全无法调试。</p>
     */
    public double threatOf(UUID id) {
        if (id == null) return 0;
        decayTo(clock.getAsLong());
        Entry e = entries.get(id);
        return e == null ? 0 : e.threat;
    }

    public boolean contains(UUID id) {
        return id != null && entries.containsKey(id);
    }

    public void clear() {
        entries.clear();
    }

    public void remove(UUID id) {
        if (id != null) entries.remove(id);
    }

    /** 在场目标数（威胁 > 0）。 */
    public int size() {
        return (int) entries.values().stream().filter(e -> e.threat > 0).count();
    }

    /** 解析 UUID 为玩家；未注入解析器时返回 null。 */
    public <P> P resolve(UUID id) {
        if (playerResolver == null) return null;
        return (P) playerResolver.apply(id);
    }
}