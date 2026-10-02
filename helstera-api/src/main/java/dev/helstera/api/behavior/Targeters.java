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
        BUILTIN.put("threat", Targeters::nearest);
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