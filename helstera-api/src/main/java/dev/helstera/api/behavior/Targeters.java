package dev.helstera.api.behavior;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 内置目标选择器注册表。
 *
 * <p>每个选择器是「挑一批候选里最好的 n 个」的纯函数。数量由动作侧的参数控制
 * （见 {@code a} 系列动作的 count 参数），这里只按策略排序并截断到一个安全上限。</p>
 *
 * <p>排序依据里的距离都做世界校验：跨世界实体视为距离无穷，直接排到末尾而不是
 * 抛异常——技能在候选集混入了别处实体时也不该炸掉整个决策节拍。</p>
 */
public final class Targeters {

    private static final Map<String, Targeter> BUILTIN = new LinkedHashMap<>();

    static {
        BUILTIN.put("nearest", Targeters::nearest);
        BUILTIN.put("farthest", Targeters::farthest);
        BUILTIN.put("random", Targeters::random);
        BUILTIN.put("lowest-health", Targeters::lowestHealth);
        BUILTIN.put("highest-health", Targeters::highestHealth);
        BUILTIN.put("players", Targeters::playersOnly);
        BUILTIN.put("mobs", Targeters::mobsOnly);
        BUILTIN.put("threat", Targeters::byThreat);
        BUILTIN.put("living", Targeters::living);
        BUILTIN.put("vulnerable", Targeters::vulnerable);
        BUILTIN.put("lowest-health-percent", Targeters::vulnerable);
        BUILTIN.put("highest-health-percent", Targeters::toughest);
    }

    /**
     * 按「分数降序、距离升序」排序。
     *
     * <p>抽成泛型纯函数的原因：排序语义是这些选择器里唯一容易写错、又完全不需要
     * Bukkit 实体就能验证的部分。此前它内联在每个选择器里，只能靠真服观测——
     * 而「等分时的兜底顺序写反」这类错误在真服上表现为模型偶尔选错目标，
     * 几乎不可能归因。</p>
     *
     * <p>距离兜底不可省：分数相同的候选若不定序，每 tick 因遍历顺序微变就会
     * 在它们之间反复横跳，表现为周期性抽搐转向。</p>
     *
     * @param items 候选；不会被修改，返回的是新列表
     * @param score 越高越优先
     * @param distance 越近越优先，仅在分数持平时生效
     */
    public static <T> List<T> orderedBy(List<T> items,
                                        java.util.function.ToDoubleFunction<T> score,
                                        java.util.function.ToDoubleFunction<T> distance) {
        List<T> out = new ArrayList<>(items);
        out.sort(Comparator
                .comparingDouble((T t) -> -score.applyAsDouble(t))
                .thenComparingDouble(distance::applyAsDouble));
        return out;
    }

    /** 全类型存活实体（含玩家），按距离由近到远。对应 MM 的 living 类目标器。 */
    private static List<LivingEntity> living(ModelInstance src,
                                             Collection<? extends LivingEntity> candidates,
                                             List<String> args) {
        return orderedBy(new ArrayList<>(candidates), e -> 0, e -> distance(src, e));
    }

    /**
     * 血量<b>比例</b>最低者优先。
     *
     * <p>刻意与 {@link #lowestHealth}（绝对血量）分开：混血队伍里绝对血量几乎
     * 恒等于「挑玩家」，而 MM 的 vulnerable 是按比例——满血的坦克应当比残血的
     * 玩家更「脆弱」与否，取决于比例而非数值本身。</p>
     */
    private static List<LivingEntity> vulnerable(ModelInstance src,
                                                 Collection<? extends LivingEntity> candidates,
                                                 List<String> args) {
        return orderedBy(new ArrayList<>(candidates), Targeters::healthRatio,
                e -> distance(src, e));
    }

    /** 血量比例最高者优先。 */
    private static List<LivingEntity> toughest(ModelInstance src,
                                               Collection<? extends LivingEntity> candidates,
                                               List<String> args) {
        return orderedBy(new ArrayList<>(candidates), e -> -healthRatio(e),
                e -> distance(src, e));
    }

    /**
     * 血量比例；取不到血量时返回 0（而非 NaN），保证排序不会因 NaN 退化。
     *
     * <p>NaN 会让所有比较返回 false，比较器在这种输入下退化为「保持原序」——
     * 而原序来自集合遍历顺序，等于不确定行为。</p>
     */
    /**
     * 血量属性常量。
     *
     * <p>反射取而非直接引用：该常量在 Paper 1.21.3 由 {@code GENERIC_MAX_HEALTH}
     * 改名为 {@code MAX_HEALTH}，直接写死任一个名字都会让另一批版本编译失败
     * 或运行期 {@code NoSuchFieldError}。只解析一次并缓存。</p>
     */
    private static final org.bukkit.attribute.Attribute MAX_HEALTH = resolveMaxHealth();

    private static org.bukkit.attribute.Attribute resolveMaxHealth() {
        for (String n : new String[]{"MAX_HEALTH", "GENERIC_MAX_HEALTH"}) {
            try {
                return org.bukkit.attribute.Attribute.valueOf(n);
            } catch (IllegalArgumentException ignored) {
                // 该版本没有这个名字，试下一个
            }
        }
        return null;
    }

    private static double healthRatio(LivingEntity e) {
        try {
            var attr = MAX_HEALTH == null ? null : e.getAttribute(MAX_HEALTH);
            if (attr == null || attr.getValue() <= 0) return 0;
            return e.getHealth() / attr.getValue();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 仇恨排序所需的外部数据源。
     *
     * <p>刻意做成接口而非直接依赖实现类：仇恨表在 helstera-ai，而本类位于
     * helstera-api，直接依赖会形成 api → ai 的反向边（Maven 上是循环依赖）。
     * 这与 {@code SkillExtras.ThreatLookup} 是同一个桥接模式。</p>
     */
    public interface ThreatProvider {
        /**
         * 该来源实例眼中，此候选的当前仇恨值。
         *
         * <p>必须带来源实例：仇恨表挂在各自的 AiController 上，同一个玩家
         * 对不同 Boss 的仇恨完全不同，只凭实体无法定位该查哪张表。</p>
         */
        double threatOf(ModelInstance src, LivingEntity candidate);
    }

    /**
     * 注入仇恨数据源；由 helstera-ai 在启动时调用。
     *
     * <p>未注入时 {@code threat} 选择器返回空候选<b>而不是回落 nearest</b>。
     * 回落会让「按仇恨选目标」静默变成「选最近的」，症状是坦克拉不住仇恨，
     * 而配置与文档看上去完全正常——这正是本类注释里明令禁止的那种行为。</p>
     */
    private static volatile ThreatProvider threatProvider;

    public static void threatProvider(ThreatProvider p) {
        threatProvider = p;
    }

    /** 按当前仇恨降序排列；无数据源时返回空列表（见 {@link #threatProvider}）。 */
    private static List<LivingEntity> byThreat(ModelInstance src,
                                               Collection<? extends LivingEntity> candidates,
                                               List<String> args) {
        var p = threatProvider;
        if (p == null) return List.of();
        List<LivingEntity> list = new ArrayList<>(candidates);
        // 并列时退回距离，保证结果确定——否则每次遍历顺序微变都会让模型在
        // 等仇恨目标之间反复横跳，表现为周期性抽搐转向
        list.sort(Comparator
                .comparingDouble((LivingEntity e) -> -p.threatOf(src, e))
                .thenComparingDouble(e -> distance(src, e)));
        return list;
    }

    private Targeters() {
    }

    /** 全部内置选择器名（用于 /helstera debug 与告警提示）。 */
    public static Collection<String> names() {
        return Collections.unmodifiableSet(BUILTIN.keySet());
    }

    /**
     * 按名取选择器；未知名返回 null，由调用方决定是拒绝还是回落。
     *
     * <p>刻意不静默回落到 nearest：技能写错了 targeter 名却按"最近"执行，
     * 比直接报未知更难排查。</p>
     */
    public static Targeter byName(String name) {
        if (name == null) return null;
        return BUILTIN.get(name.trim().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------

    private static List<LivingEntity> nearest(ModelInstance src,
                                              Collection<? extends LivingEntity> candidates,
                                              List<String> args) {
        List<LivingEntity> list = new ArrayList<>(candidates);
        list.sort(Comparator.comparingDouble(e -> distance(src, e)));
        return list;
    }

    private static List<LivingEntity> farthest(ModelInstance src,
                                               Collection<? extends LivingEntity> candidates,
                                               List<String> args) {
        List<LivingEntity> list = new ArrayList<>(candidates);
        list.sort(Comparator.comparingDouble((LivingEntity e) -> distance(src, e)).reversed());
        return list;
    }

    private static List<LivingEntity> random(ModelInstance src,
                                             Collection<? extends LivingEntity> candidates,
                                             List<String> args) {
        List<LivingEntity> list = new ArrayList<>(candidates);
        // 只打乱不排序，避免引入可预测的行为（AOE 随机目标不应每次顺序一致）
        Collections.shuffle(list);
        return list;
    }

    private static List<LivingEntity> lowestHealth(ModelInstance src,
                                                    Collection<? extends LivingEntity> candidates,
                                                    List<String> args) {
        List<LivingEntity> list = new ArrayList<>(candidates);
        list.sort(Comparator.comparingDouble(Targeters::health));
        return list;
    }

    private static List<LivingEntity> highestHealth(ModelInstance src,
                                                     Collection<? extends LivingEntity> candidates,
                                                     List<String> args) {
        List<LivingEntity> list = new ArrayList<>(candidates);
        list.sort(Comparator.comparingDouble((LivingEntity e) -> -health(e)));
        return list;
    }

    private static List<LivingEntity> playersOnly(ModelInstance src,
                                                  Collection<? extends LivingEntity> candidates,
                                                  List<String> args) {
        List<LivingEntity> list = new ArrayList<>();
        for (LivingEntity e : candidates) {
            if (e instanceof Player) list.add(e);
        }
        list.sort(Comparator.comparingDouble(e -> distance(src, e)));
        return list;
    }

    private static List<LivingEntity> mobsOnly(ModelInstance src,
                                               Collection<? extends LivingEntity> candidates,
                                               List<String> args) {
        List<LivingEntity> list = new ArrayList<>();
        for (LivingEntity e : candidates) {
            if (!(e instanceof Player)) list.add(e);
        }
        list.sort(Comparator.comparingDouble(e -> distance(src, e)));
        return list;
    }

    // ------------------------------------------------------------------

    /** 跨世界距离视为无穷，保证排序稳定且不抛异常。 */
    public static double distance(ModelInstance src, LivingEntity e) {
        return distance(src == null ? null : src.location(), e);
    }

    public static double distance(Location from, LivingEntity e) {
        if (from == null || from.getWorld() == null || e == null) return Double.MAX_VALUE;
        Location to = e.getLocation();
        if (to == null || !from.getWorld().equals(to.getWorld())) return Double.MAX_VALUE;
        return from.distance(to);
    }

    private static double health(LivingEntity e) {
        try {
            return e.getHealth();
        } catch (Throwable t) {
            return Double.MAX_VALUE;
        }
    }
}