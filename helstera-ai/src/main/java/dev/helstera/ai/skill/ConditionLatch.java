package dev.helstera.ai.skill;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 条件状态锁存器：对布尔条件做去抖，只在状态<b>确认翻转</b>时给出边沿事件。
 *
 * <p>服务于 {@code on-condition-met} / {@code on-condition-lost}。这两个触发器
 * 的语义是「条件状态变化时触发一次」，而采样是每 tick 一次。若直接用单次采样
 * 求差分，血量类条件（{@code health-below 0.3}）在阈值附近的微小波动就会
 * 产生每秒数十次的边沿事件——把 Boss 打成筛子。</p>
 *
 * <p><b>确认策略</b>：状态首次观测只记录基线、不产生边沿；之后每观察到一次
 * 与当前基线不同的取值就累加计数，连续达到 {@code debounce} 次才确认翻转并
 * 产生一次边沿。确认后基线翻转、计数归零。</p>
 *
 * <p><b>为何独立成类</b>：这段状态机是整套触发逻辑里最容易写错的部分
 * （基线语义、计数重置、边沿方向），却深埋在 {@code SkillTriggers} 里依赖
 * {@code AiManager} 与 Bukkit 实体，单测无法触达。抽成纯类后可直接覆盖，
 * 且不引用 Bukkit。</p>
 *
 * <p>非线程安全：设计上由主线程单线程驱动。</p>
 */
public final class ConditionLatch {

    /** 单个键的锁存状态。 */
    private static final class Cell {
        /** 已确认的基线值。首次采样前为 null（表示尚未建立基线）。 */
        Boolean baseline;
        /** 与基线不一致的连续观测计数。 */
        int pending;
    }

    private final Map<String, Cell> cells = new HashMap<>();
    private final int debounce;

    /**
     * @param debounce 确认翻转所需的连续观测次数；小于 1 时按 1 处理
     *                 （取 1 即退化为「无去抖」，仍保持基线语义）
     */
    public ConditionLatch(int debounce) {
        this.debounce = Math.max(1, debounce);
    }

    /**
     * 喂入一次观测，返回是否产生「条件转为满足」的边沿。
     *
     * @param key 观测键（实例与档案的组合）
     * @param value 本次采样结果
     * @return true 表示本次确认了 false -> true 的翻转
     */
    public boolean rising(String key, boolean value) {
        return update(key, value, true);
    }

    /**
     * 喂入一次观测，返回是否产生「条件转为不满足」的边沿。
     *
     * @return true 表示本次确认了 true -> false 的翻转
     */
    public boolean falling(String key, boolean value) {
        return update(key, value, false);
    }

    /**
     * 推进一次采样。
     *
     * @param wantValue 关注的目标值：true 关注 false->true，false 关注 true->false
     */
    private boolean update(String key, boolean value, boolean wantValue) {
        Cell c = cells.computeIfAbsent(key, k -> new Cell());

        // 首次观测只建立基线：无法判断是「刚变」还是「一直是这个值」
        if (c.baseline == null) {
            c.baseline = value;
            c.pending = 0;
            return false;
        }

        boolean base = c.baseline;
        if (value == base) {
            // 与基线一致，抵消掉之前积累的反向待确认次数。
            // 若不清零，condition 在阈值附近抖动时会互相抵消导致永远不确认翻转。
            c.pending = 0;
            return false;
        }

        c.pending++;
        if (c.pending < debounce) return false;

        // 连续足够多次观测到反值 -> 确认翻转，翻转基线并重置计数
        c.baseline = value;
        c.pending = 0;
        return value == wantValue;
    }

    /** 该键是否已建立基线。 */
    public boolean initialized(String key) {
        Cell c = cells.get(key);
        return c != null && c.baseline != null;
    }

    /** 该键当前已确认的基线值；未初始化返回 null。 */
    public Boolean baseline(String key) {
        Cell c = cells.get(key);
        return c == null ? null : c.baseline;
    }

    /** 当前待确认的连续观测次数（调试用）。 */
    public int pending(String key) {
        Cell c = cells.get(key);
        return c == null ? 0 : c.pending;
    }

    /** 已建立基线的键数量。 */
    public int size() {
        int n = 0;
        for (Cell c : cells.values()) if (c.baseline != null) n++;
        return n;
    }

    /** 遗忘不属于 {@code liveKeys} 的观测键，避免 map 随实例生成/销毁无限增长。 */
    public void retainAll(Set<String> liveKeys) {
        cells.keySet().removeIf(k -> !liveKeys.contains(k));
    }

    /** 清空全部状态（实例死亡、重载）。 */
    public void clear() {
        cells.clear();
    }
}
