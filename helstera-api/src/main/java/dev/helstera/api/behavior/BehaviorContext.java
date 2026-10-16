package dev.helstera.api.behavior;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.ArrayList;
import java.util.List;

import java.util.Optional;

/**
 * 行为决策上下文：传给自定义条件与动作的只读快照。
 *
 * <p>线程约束：仅在主线程决策节拍内构造与读取。实例在决策过程中可能销毁，
 * 访问 {@link #instance()} 后应先调用 {@link #instanceValid()} 判断。</p>
 *
 * <p>设计意图：让条件/动作能拿到具名语义字段，而不是无类型的 {@code Object}，
 * 从而在不修改决策内核的前提下扩展行为。</p>
 */
public final class BehaviorContext {

    private final ModelInstance instance;
    private final Player target;
    private final LivingEntity targetEntity;
    private final double healthRatio;
    private final double distanceToTarget;
    private final long decisionCount;
    private final String state;
    /**
     * 技能信号载荷；非信号来源的决策为 null。
     *
     * <p>放在最外层而非复用 {@code state}：state 已被 {@code state-is} 类条件占用，
     * 且语义不同——state 是 AI 状态机状态，payload 是技能间传递的数据。</p>
     */
    private final String payload;
    private final Entity trigger;
    private final List<LivingEntity> targets;
    private final Location origin;
    private final SkillTreeContext skillTree;
    private final Cancellable cancellableEvent;
    private final EntityDamageEvent.DamageCause damageCause;

    private BehaviorContext(ModelInstance instance, Player target, double healthRatio,
                            double distanceToTarget, long decisionCount, String state) {
        this(instance, target, healthRatio, distanceToTarget, decisionCount, state,
                null, null, target == null ? List.of() : List.of(target),
                instance == null ? null : instance.location(), null);
    }

    private BehaviorContext(ModelInstance instance, Player target, double healthRatio,
                            double distanceToTarget, long decisionCount, String state,
                            String payload) {
        this(instance, target, healthRatio, distanceToTarget, decisionCount, state,
                payload, null, target == null ? List.of() : List.of(target),
                instance == null ? null : instance.location(), null);
    }

    private BehaviorContext(ModelInstance instance, Player target, double healthRatio,
                            double distanceToTarget, long decisionCount, String state,
                            String payload, Entity trigger, List<? extends LivingEntity> targets,
                            Location origin, SkillTreeContext skillTree) {
        this(instance, target, target, healthRatio, distanceToTarget, decisionCount, state,
                payload, trigger, targets, origin, skillTree, null);
    }

    private BehaviorContext(ModelInstance instance, Player target, LivingEntity targetEntity,
                            double healthRatio, double distanceToTarget, long decisionCount,
                            String state, String payload, Entity trigger,
                            List<? extends LivingEntity> targets, Location origin,
                            SkillTreeContext skillTree) {
        this(instance, target, targetEntity, healthRatio, distanceToTarget, decisionCount, state,
                payload, trigger, targets, origin, skillTree, null);
    }

    private BehaviorContext(ModelInstance instance, Player target, LivingEntity targetEntity,
                            double healthRatio, double distanceToTarget, long decisionCount,
                            String state, String payload, Entity trigger,
                            List<? extends LivingEntity> targets, Location origin,
                            SkillTreeContext skillTree, Cancellable cancellableEvent) {
        this(instance, target, targetEntity, healthRatio, distanceToTarget, decisionCount, state,
                payload, trigger, targets, origin, skillTree, cancellableEvent, null);
    }

    private BehaviorContext(ModelInstance instance, Player target, LivingEntity targetEntity,
                            double healthRatio, double distanceToTarget, long decisionCount,
                            String state, String payload, Entity trigger,
                            List<? extends LivingEntity> targets, Location origin,
                            SkillTreeContext skillTree, Cancellable cancellableEvent,
                            EntityDamageEvent.DamageCause damageCause) {
        this.instance = instance;
        this.target = target;
        this.targetEntity = targetEntity;
        this.healthRatio = healthRatio;
        this.distanceToTarget = distanceToTarget;
        this.decisionCount = decisionCount;
        this.state = state;
        this.payload = payload;
        this.trigger = trigger;
        this.targets = List.copyOf(targets == null ? List.of() : new ArrayList<>(targets));
        this.origin = origin == null ? null : origin.clone();
        this.skillTree = skillTree;
        this.cancellableEvent = cancellableEvent;
        this.damageCause = damageCause;
    }

    /**
     * 构造带信号载荷的上下文（供 on-signal 使用）。
     *
     * <p>独立重载而非给现有工厂加参数：已有五处调用点都要传 null，
     * 加参数会让每个调用点多出一个无意义的 null。</p>
     */
    public static BehaviorContext withPayload(ModelInstance instance, Player target,
                                              double healthRatio, double distanceToTarget,
                                              long decisionCount, String state, String payload) {
        return new BehaviorContext(instance, target, healthRatio, distanceToTarget,
                decisionCount, state, payload);
    }

    /** 构造决策上下文；target 为 null 时视为无目标，distanceToTarget 记为 -1。 */
    public static BehaviorContext of(ModelInstance instance, Player target, double healthRatio,
                                     double distanceToTarget, long decisionCount) {
        return new BehaviorContext(instance, target, healthRatio, distanceToTarget, decisionCount, "IDLE");
    }

    /** 构造决策上下文并记录当前 AI 状态（供 state-is 类条件使用）。 */
    public static BehaviorContext of(ModelInstance instance, Player target, double healthRatio,
                                     double distanceToTarget, long decisionCount, String state) {
        return new BehaviorContext(instance, target, healthRatio, distanceToTarget, decisionCount, state);
    }

    /** Creates an AI context whose active target may be any LivingEntity. */
    public static BehaviorContext of(ModelInstance instance, LivingEntity target, double healthRatio,
                                     double distanceToTarget, long decisionCount, String state) {
        Player player = target instanceof Player p ? p : null;
        return new BehaviorContext(instance, player, target, healthRatio, distanceToTarget,
                decisionCount, state, null, null,
                target == null ? List.of() : List.of(target),
                instance == null ? null : instance.location(), null);
    }

    /** Creates a skill context with explicit trigger, inherited targets, and origin. */
    public static BehaviorContext execution(ModelInstance instance, Player target,
                                            double healthRatio, double distanceToTarget,
                                            long decisionCount, String state, String payload,
                                            Entity trigger, List<? extends LivingEntity> targets,
                                            Location origin) {
        return new BehaviorContext(instance, target, healthRatio, distanceToTarget,
                decisionCount, state, payload, trigger, targets, origin,
                new SkillTreeContext(trigger, targets, origin));
    }

    /** 承载模型的实例（决策发起方）。 */
    /** Creates a context that continues an existing skill tree. */
    public static BehaviorContext continueExecution(ModelInstance instance, Player target,
                                                    double healthRatio, double distanceToTarget,
                                                    long decisionCount, String state, String payload,
                                                    Entity trigger, List<? extends LivingEntity> targets,
                                                    Location origin, SkillTreeContext skillTree) {
        return new BehaviorContext(instance, target, healthRatio, distanceToTarget,
                decisionCount, state, payload, trigger, targets, origin, skillTree);
    }

    /** Continues execution while retaining the Bukkit event that may be cancelled. */
    public static BehaviorContext continueExecution(ModelInstance instance, Player target,
                                                    double healthRatio, double distanceToTarget,
                                                    long decisionCount, String state, String payload,
                                                    Entity trigger, List<? extends LivingEntity> targets,
                                                    Location origin, SkillTreeContext skillTree,
                                                    Cancellable cancellableEvent) {
        return continueExecution(instance, target, healthRatio, distanceToTarget, decisionCount,
                state, payload, trigger, targets, origin, skillTree, cancellableEvent, null);
    }

    /** Player-target continuation retaining the original damage cause. */
    public static BehaviorContext continueExecution(ModelInstance instance, Player target,
                                                    double healthRatio, double distanceToTarget,
                                                    long decisionCount, String state, String payload,
                                                    Entity trigger, List<? extends LivingEntity> targets,
                                                    Location origin, SkillTreeContext skillTree,
                                                    Cancellable cancellableEvent,
                                                    EntityDamageEvent.DamageCause damageCause) {
        return new BehaviorContext(instance, target, target, healthRatio, distanceToTarget,
                decisionCount, state, payload, trigger, targets, origin, skillTree,
                cancellableEvent, damageCause);
    }

    /** Event-aware continuation for non-player living targets. */
    public static BehaviorContext continueExecution(ModelInstance instance, LivingEntity target,
                                                    double healthRatio, double distanceToTarget,
                                                    long decisionCount, String state, String payload,
                                                    Entity trigger, List<? extends LivingEntity> targets,
                                                    Location origin, SkillTreeContext skillTree,
                                                    Cancellable cancellableEvent) {
        return continueExecution(instance, target, healthRatio, distanceToTarget, decisionCount,
                state, payload, trigger, targets, origin, skillTree, cancellableEvent, null);
    }

    /** Event-aware continuation retaining both cancellation state and damage cause. */
    public static BehaviorContext continueExecution(ModelInstance instance, LivingEntity target,
                                                    double healthRatio, double distanceToTarget,
                                                    long decisionCount, String state, String payload,
                                                    Entity trigger, List<? extends LivingEntity> targets,
                                                    Location origin, SkillTreeContext skillTree,
                                                    Cancellable cancellableEvent,
                                                    EntityDamageEvent.DamageCause damageCause) {
        Player player = target instanceof Player p ? p : null;
        return new BehaviorContext(instance, player, target, healthRatio, distanceToTarget,
                decisionCount, state, payload, trigger, targets, origin, skillTree,
                cancellableEvent, damageCause);
    }

    /** Continues execution with any LivingEntity as the active target, not only players. */
    public static BehaviorContext continueExecution(ModelInstance instance, LivingEntity target,
                                                    double healthRatio, double distanceToTarget,
                                                    long decisionCount, String state, String payload,
                                                    Entity trigger, List<? extends LivingEntity> targets,
                                                    Location origin, SkillTreeContext skillTree) {
        Player player = target instanceof Player p ? p : null;
        return new BehaviorContext(instance, player, target, healthRatio, distanceToTarget,
                decisionCount, state, payload, trigger, targets, origin, skillTree);
    }

    public ModelInstance instance() {
        return instance;
    }

    /** 决策发起方是否仍然有效；条件里访问 instance() 前应先判断。 */
    public boolean instanceValid() {
        return instance != null && instance.isValid();
    }

    /** 当前仇恨目标，无目标时 empty。 */
    public Optional<Player> target() {
        return Optional.ofNullable(target);
    }

    /** Current entity target when it is not necessarily a Player. */
    public Optional<LivingEntity> targetEntity() {
        return Optional.ofNullable(targetEntity);
    }

    /**
     * 技能信号载荷；非信号来源或未带载荷时 empty。
     *
     * <p>供 {@code on-signal} 下的条件/动作读取 {@code signal} 动作传来的内容，
     * 例如 {@code <ctx.payload>}。不使用信号时恒为 empty，不会影响其它条件。</p>
     */
    public Optional<String> payload() {
        return Optional.ofNullable(payload).filter(s -> !s.isEmpty());
    }

    /** Entity that caused the current skill trigger, when one exists. */
    public Optional<Entity> trigger() {
        return Optional.ofNullable(trigger);
    }

    /** Bukkit event associated with the current trigger, when cancellation is supported. */
    public Optional<Cancellable> cancellableEvent() {
        return Optional.ofNullable(cancellableEvent);
    }

    /** Original Bukkit damage cause for damage-triggered skills, when present. */
    public Optional<EntityDamageEvent.DamageCause> damageCause() {
        return Optional.ofNullable(damageCause);
    }

    /** Targets inherited by the current skill tree, in stable selection order. */
    public List<LivingEntity> targets() {
        return targets;
    }

    /** Origin used by location targeters and mechanics. */
    public Optional<Location> origin() {
        return Optional.ofNullable(origin == null ? null : origin.clone());
    }

    /** State shared by nested skills triggered from this context. */
    public Optional<SkillTreeContext> skillTree() {
        return Optional.ofNullable(skillTree);
    }

    /** 当前目标血量比例 0..1；载体无血量时为 1。 */
    public double healthRatio() {
        return healthRatio;
    }

    /** 与目标的距离；无目标时为 -1。 */
    public double distanceToTarget() {
        return distanceToTarget;
    }

    /** 本实例累计决策次数，可用于实现"每 N 次决策一次"的节流条件。 */
    public long decisionCount() {
        return decisionCount;
    }

    /** 本次决策开始时的 AI 状态名（IDLE/PATROL/CHASE/ATTACK/HURT/FLEE/DEAD）。 */
    public String state() {
        return state;
    }
}
