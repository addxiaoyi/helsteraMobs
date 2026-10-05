package dev.helstera.ai.skill;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 派生触发器状态机：由「当前目标 + 血量」单次采样推导战斗/目标类边沿。
 *
 * <p>服务于 {@code on-enter-combat} / {@code on-leave-combat} /
 * {@code on-target-change} / {@code on-lost-target} / {@code on-lower-health}，
 * 以及 {@code on-enter-water} / {@code on-leave-water}。
 * 这些触发器没有独立事件来源——它们是 AI 状态与实体环境的自变量，
 * 因此只能在采样节拍里靠「记住上次值 + 求差分」得到。</p>
 *
 * <p><b>为何不做去抖</b>：与 {@link ConditionLatch} 不同，这里的量是
 * 「是否有目标」与「血量是否低于阈值」——前者只在 AI 状态迁移时变化，
 * 后者按玩家每次受击自然单调。只有一个例外需要注意：血量在阈值附近会抖动，
 * 因此 {@code on-lower-health} 内部带了一个「确认下方」的滞后量
 * （低于阈值 {@link #HYSTERESIS} 比例才认定），避免擦边反复触发。</p>
 *
 * <p><b>为何独立成类</b>：边沿推导涉及基线、滞后、上次目标三种记忆，
 * 是极易写错的逻辑；深埋在事件分发里时无法单测。抽出后不引用 Bukkit。</p>
 *
 * <p>非线程安全：设计上由主线程单线程驱动。</p>
 */
public final class DerivedTriggerLatch {

    /** 血量阈值下方的滞后比例：低于 threshold*(1-HYSTERESIS) 才认定越线。 */
    private static final double HYSTERESIS = 0.02;

    private static final class Cell {
        /** 是否已完成首次采样。未完成前不产生任何边沿。 */
        boolean armed;
        /** 上次是否有目标。 */
        boolean inCombat;
        /** 上次的血量比（0..1）。 */
        double healthRatio = -1;
        /** 上次的目标，用于识别「换人」而非「丢失」。 */
        UUID lastTarget;
        /** 上次是否处于水中；null 表示尚未采样过（与 armed 同步语义）。 */
        Boolean inWater;
        /** 上次是否已成年；null 表示载体非 Ageable，不参与成年边沿。 */
        Boolean adult;
        /** 上次是否在档案区域内；null 表示未配置区域。 */
        Boolean inRegion;
    }

    private final Map<String, Cell> cells = new HashMap<>();

    /**
     * 推进一次采样（不含水状态）。
     *
     * <p>保留此重载是为了让只关心战斗/血量的调用方与既有测试不必关心水：
     * 等价于 {@code inWater=false}。</p>
     */
    public Set<SkillTrigger> sample(String key, UUID target, double healthRatio,
                                     double lowerThreshold) {
        return sample(key, target, healthRatio, lowerThreshold, false);
    }

    /**
     * 推进一次采样，返回本次应派发的触发器。
     *
     * @param key 观测键（实例 + 档案 + 阈值）
     * @param target 当前仇恨目标；null 表示无目标
     * @param healthRatio 当前血量比 0..1
     * @param lowerThreshold on-lower-health 的阈值（血量百分比 0..100）
     * @param inWater 载体实体当前是否在水中
     * @return 本次产生边沿的触发器集合；无则为空集
     */
    public Set<SkillTrigger> sample(String key, UUID target, double healthRatio,
                                     double lowerThreshold, boolean inWater) {
        return sample(key, target, healthRatio, lowerThreshold, inWater, null, null);
    }

    /**
     * 推进一次采样（含成年状态）。
     *
     * @param adult 载体是否已成年；null 表示载体不是 {@code Ageable}，不参与该边沿。
     *              用 null 而非 false：两者都代表「不触发 AGE」，但 false 会让
     *              非 Ageable 载体在第一次采样时把基线记成「幼年」，
     *              随后它变成 Ageable 时会误报一次 AGE。
     */
    public Set<SkillTrigger> sample(String key, UUID target, double healthRatio,
                                     double lowerThreshold, boolean inWater, Boolean adult) {
        return sample(key, target, healthRatio, lowerThreshold, inWater, adult, null);
    }

    /**
     * 推进一次采样（含成年与区域状态）。
     *
     * @param inRegion 载体当前是否在档案区域内；null 表示档案未配置区域，不参与边沿。
     */
    public Set<SkillTrigger> sample(String key, UUID target, double healthRatio,
                                     double lowerThreshold, boolean inWater, Boolean adult,
                                     Boolean inRegion) {
        Cell c = cells.computeIfAbsent(key, k -> new Cell());
        boolean hasTarget = target != null;

        // 首次采样只建基线：无法区分「刚进入战斗」与「一直在战斗」
        if (!c.armed) {
            c.armed = true;
            c.inCombat = hasTarget;
            c.healthRatio = healthRatio;
            c.lastTarget = target;
            c.inWater = inWater;
            c.adult = adult;
            c.inRegion = inRegion;
            return EnumSet.noneOf(SkillTrigger.class);
        }

        Set<SkillTrigger> edges = EnumSet.noneOf(SkillTrigger.class);

        // ---- 战斗进出 ----
        if (hasTarget && !c.inCombat) {
            edges.add(SkillTrigger.ENTER_COMBAT);
        } else if (!hasTarget && c.inCombat) {
            // 丢失目标与脱离战斗同时发生：MythicMobs 里二者语义相近但可分别挂技能，
            // 故同时产出，档案没配的那个自然不会触发
            edges.add(SkillTrigger.LOST_TARGET);
            edges.add(SkillTrigger.LEAVE_COMBAT);
        }

        // ---- 目标切换 ----
        // 必须是「从有到有且不同」：从无到有属于进入战斗，不是换人
        if (hasTarget && c.inCombat && c.lastTarget != null && !c.lastTarget.equals(target)) {
            edges.add(SkillTrigger.TARGET_CHANGE);
        }

        // ---- 血量下穿 ----
        // 用滞后量而非单次比较：血量在阈值附近抖动时，单次比较会反复触发
        double th = Math.max(0, Math.min(100, lowerThreshold));
        boolean belowNow = healthRatio * 100.0 <= th - th * HYSTERESIS;
        boolean belowPrev = c.healthRatio * 100.0 <= th - th * HYSTERESIS;
        if (belowNow && !belowPrev) {
            edges.add(SkillTrigger.LOWER_HEALTH);
        }

        // ---- 进出水 ----
        // Bukkit 没有「实体入水/出���」事件，只能靠 isInWater 求差分。
        // 玩家踩水坑会连续多 tick 为 true，但求差分只在翻转的那一次产出边沿，
        // 因此这里不需要像血量那样加滞后量。
        Boolean wasInWater = c.inWater;
        if (wasInWater != null) {
            if (inWater && !wasInWater) edges.add(SkillTrigger.ENTER_WATER);
            else if (!inWater && wasInWater) edges.add(SkillTrigger.LEAVE_WATER);
        }

        // ---- 成年 ----
        // Paper 没有「生物成年」事件（TransformReason 里没有 AGED），
        // 只能对 Ageable 求 isAdult() 差分。与进出水同理，翻转那一次产出边沿。
        Boolean wasAdult = c.adult;
        if (adult != null && wasAdult != null && adult && !wasAdult) {
            edges.add(SkillTrigger.AGE);
        }

        // ---- 区域进出 ----
        // 无对应 Bukkit 事件，只能对档案配置的 AABB 求差分。
        // 与进出水同理：只有翻转那一次产出边沿，因此不需要滞后量。
        Boolean wasInRegion = c.inRegion;
        if (inRegion != null && wasInRegion != null) {
            if (inRegion && !wasInRegion) edges.add(SkillTrigger.ENTER_REGION);
            else if (!inRegion && wasInRegion) edges.add(SkillTrigger.LEAVE_REGION);
        }

        c.inCombat = hasTarget;
        c.lastTarget = target;
        c.healthRatio = healthRatio;
        c.inWater = inWater;
        c.adult = adult;
        c.inRegion = inRegion;
        return edges;
    }

    /** 已建立基线的观测数。 */
    public int size() {
        return (int) cells.values().stream().filter(c -> c.armed).count();
    }

    /** 遗忘不属于 {@code liveKeys} 的观测键，避免 map 随实例生成/销毁无限增长。 */
    public void retainAll(Set<String> liveKeys) {
        cells.keySet().removeIf(k -> !liveKeys.contains(k));
    }

    public void clear() {
        cells.clear();
    }
}