package dev.helstera.api.behavior;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

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

    /** Native condition dispatch scope; ordinary action contexts use EXECUTION. */
    public enum ConditionScope { EXECUTION, CASTER, TARGET, TRIGGER }
    private final ConditionScope conditionScope;

    /** Scalar metadata copied at a mechanic boundary, shared by condition outcome casts. */
    private static final class SkillPower {
        private float value;
        private SkillPower(float value) { this.value = value; }
    }
    private final SkillPower skillPower;

    private static final class ExecutionAudienceScope {
        private volatile Set<UUID> players;

        private ExecutionAudienceScope(Collection<UUID> players) {
            set(players);
        }

        private Set<UUID> get() {
            return players;
        }

        private void set(Collection<UUID> players) {
            this.players = players == null ? null : Set.copyOf(players);
        }
    }

    private final ModelInstance instance;
    private final Player target;
    private final LivingEntity targetEntity;
    private final Entity entityRecipient;
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
    private final List<Entity> entityTargets;
    /** Native metadata distinguishes an absent entity collection from an explicitly empty one. */
    private final boolean entityTargetsPresent;
    private final Location origin;
    private final Location targetLocation;
    private final SkillTreeContext skillTree;
    private final Cancellable cancellableEvent;
    private final EntityDamageEvent.DamageCause damageCause;
    private final ExecutionAudienceScope executionAudience;

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
                            String payload, Entity trigger, List<? extends Entity> targets,
                            Location origin, SkillTreeContext skillTree) {
        this(instance, target, target, healthRatio, distanceToTarget, decisionCount, state,
                payload, trigger, targets, origin, skillTree, null);
    }

    private BehaviorContext(ModelInstance instance, Player target, LivingEntity targetEntity,
                            double healthRatio, double distanceToTarget, long decisionCount,
                            String state, String payload, Entity trigger,
                            List<? extends Entity> targets, Location origin,
                            SkillTreeContext skillTree) {
        this(instance, target, targetEntity, healthRatio, distanceToTarget, decisionCount, state,
                payload, trigger, targets, origin, skillTree, null);
    }

    private BehaviorContext(ModelInstance instance, Player target, LivingEntity targetEntity,
                            double healthRatio, double distanceToTarget, long decisionCount,
                            String state, String payload, Entity trigger,
                            List<? extends Entity> targets, Location origin,
                            SkillTreeContext skillTree, Cancellable cancellableEvent) {
        this(instance, target, targetEntity, healthRatio, distanceToTarget, decisionCount, state,
                payload, trigger, targets, origin, skillTree, cancellableEvent, null);
    }

    private BehaviorContext(ModelInstance instance, Player target, LivingEntity targetEntity,
                            double healthRatio, double distanceToTarget, long decisionCount,
                            String state, String payload, Entity trigger,
                            List<? extends Entity> targets, Location origin,
                            SkillTreeContext skillTree, Cancellable cancellableEvent,
                            EntityDamageEvent.DamageCause damageCause) {
        this(instance, target, targetEntity, healthRatio, distanceToTarget, decisionCount, state,
                payload, trigger, targets, origin, skillTree, cancellableEvent, damageCause, null);
    }

    private BehaviorContext(ModelInstance instance, Player target, LivingEntity targetEntity,
                            double healthRatio, double distanceToTarget, long decisionCount,
                            String state, String payload, Entity trigger,
                            List<? extends Entity> targets, Location origin,
                            SkillTreeContext skillTree, Cancellable cancellableEvent,
                            EntityDamageEvent.DamageCause damageCause, Location targetLocation) {
        this(instance, target, targetEntity, healthRatio, distanceToTarget, decisionCount, state,
                payload, trigger, targets, origin, skillTree, cancellableEvent, damageCause, targetLocation, null);
    }

    private BehaviorContext(ModelInstance instance, Player target, LivingEntity targetEntity,
                            double healthRatio, double distanceToTarget, long decisionCount,
                            String state, String payload, Entity trigger,
                            List<? extends Entity> targets, Location origin,
                            SkillTreeContext skillTree, Cancellable cancellableEvent,
                            EntityDamageEvent.DamageCause damageCause, Location targetLocation,
                            Entity entityRecipient) {
        this.entityRecipient = entityRecipient == null ? targetEntity : entityRecipient;
        this.conditionScope = ConditionScope.EXECUTION;
        this.skillPower = new SkillPower(1.0f);
        this.instance = instance;
        this.target = target;
        this.targetEntity = targetEntity;
        this.healthRatio = healthRatio;
        this.distanceToTarget = distanceToTarget;
        this.decisionCount = decisionCount;
        this.state = state;
        this.payload = payload;
        this.trigger = trigger;
        this.entityTargetsPresent = targets != null || entityRecipient != null;
        this.entityTargets = List.copyOf(targets == null ? List.of() : targets);
        this.targets = entityTargets.stream().filter(LivingEntity.class::isInstance)
                .map(LivingEntity.class::cast).toList();
        this.origin = origin == null ? null : origin.clone();
        this.targetLocation = targetLocation == null ? null : targetLocation.clone();
        this.skillTree = skillTree;
        this.cancellableEvent = cancellableEvent;
        this.damageCause = damageCause;
        Optional<Set<UUID>> instanceAudience = instance == null ? Optional.empty()
                : instance.executionAudience();
        this.executionAudience = new ExecutionAudienceScope(instanceAudience == null
                ? null : instanceAudience.orElse(null));
    }

    private BehaviorContext(BehaviorContext source, ExecutionAudienceScope executionAudience) {
        this(source, executionAudience, source.conditionScope);
    }

    private BehaviorContext(BehaviorContext source, ExecutionAudienceScope executionAudience,
                            ConditionScope conditionScope) {
        this(source, executionAudience, conditionScope, source.skillPower);
    }

    private BehaviorContext(BehaviorContext source, ExecutionAudienceScope executionAudience,
                            ConditionScope conditionScope, SkillPower skillPower) {
        this.conditionScope = java.util.Objects.requireNonNull(conditionScope);
        this.skillPower = skillPower;
        this.instance = source.instance;
        this.target = source.target;
        this.targetEntity = source.targetEntity;
        this.entityRecipient = source.entityRecipient;
        this.healthRatio = source.healthRatio;
        this.distanceToTarget = source.distanceToTarget;
        this.decisionCount = source.decisionCount;
        this.state = source.state;
        this.payload = source.payload;
        this.trigger = source.trigger;
        this.targets = source.targets;
        this.entityTargets = source.entityTargets;
        this.entityTargetsPresent = source.entityTargetsPresent;
        this.origin = source.origin == null ? null : source.origin.clone();
        this.targetLocation = source.targetLocation == null ? null : source.targetLocation.clone();
        this.skillTree = source.skillTree;
        this.cancellableEvent = source.cancellableEvent;
        this.damageCause = source.damageCause;
        this.executionAudience = executionAudience;
    }

    private BehaviorContext(BehaviorContext source, boolean clearCancellableEvent) {
        this.conditionScope = source.conditionScope;
        this.skillPower = source.skillPower;
        this.instance = source.instance;
        this.target = source.target;
        this.targetEntity = source.targetEntity;
        this.entityRecipient = source.entityRecipient;
        this.healthRatio = source.healthRatio;
        this.distanceToTarget = source.distanceToTarget;
        this.decisionCount = source.decisionCount;
        this.state = source.state;
        this.payload = source.payload;
        this.trigger = source.trigger;
        this.targets = source.targets;
        this.entityTargets = source.entityTargets;
        this.entityTargetsPresent = source.entityTargetsPresent;
        this.origin = source.origin == null ? null : source.origin.clone();
        this.targetLocation = source.targetLocation == null ? null : source.targetLocation.clone();
        this.skillTree = source.skillTree;
        this.cancellableEvent = clearCancellableEvent ? null : source.cancellableEvent;
        this.damageCause = source.damageCause;
        this.executionAudience = source.executionAudience;
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
                                            Entity trigger, List<? extends Entity> targets,
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
                                                    Entity trigger, List<? extends Entity> targets,
                                                    Location origin, SkillTreeContext skillTree) {
        return new BehaviorContext(instance, target, healthRatio, distanceToTarget,
                decisionCount, state, payload, trigger, targets, origin, skillTree);
    }

    /** Continues execution while retaining the Bukkit event that may be cancelled. */
    public static BehaviorContext continueExecution(ModelInstance instance, Player target,
                                                    double healthRatio, double distanceToTarget,
                                                    long decisionCount, String state, String payload,
                                                    Entity trigger, List<? extends Entity> targets,
                                                    Location origin, SkillTreeContext skillTree,
                                                    Cancellable cancellableEvent) {
        return continueExecution(instance, target, healthRatio, distanceToTarget, decisionCount,
                state, payload, trigger, targets, origin, skillTree, cancellableEvent, null);
    }

    /** Player-target continuation retaining the original damage cause. */
    public static BehaviorContext continueExecution(ModelInstance instance, Player target,
                                                    double healthRatio, double distanceToTarget,
                                                    long decisionCount, String state, String payload,
                                                    Entity trigger, List<? extends Entity> targets,
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
                                                    Entity trigger, List<? extends Entity> targets,
                                                    Location origin, SkillTreeContext skillTree,
                                                    Cancellable cancellableEvent) {
        return continueExecution(instance, target, healthRatio, distanceToTarget, decisionCount,
                state, payload, trigger, targets, origin, skillTree, cancellableEvent, null);
    }

    /** Event-aware continuation retaining both cancellation state and damage cause. */
    public static BehaviorContext continueExecution(ModelInstance instance, LivingEntity target,
                                                    double healthRatio, double distanceToTarget,
                                                    long decisionCount, String state, String payload,
                                                    Entity trigger, List<? extends Entity> targets,
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
                                                    Entity trigger, List<? extends Entity> targets,
                                                    Location origin, SkillTreeContext skillTree) {
        Player player = target instanceof Player p ? p : null;
        return new BehaviorContext(instance, player, target, healthRatio, distanceToTarget,
                decisionCount, state, payload, trigger, targets, origin, skillTree);
    }

    /** Scope used only while evaluating a condition, without changing its execution metadata. */
    public ConditionScope conditionScope() { return conditionScope; }

    public BehaviorContext withConditionScope(ConditionScope scope) {
        return new BehaviorContext(this, executionAudience, scope);
    }

    public float skillPower() { return skillPower.value; }

    /** Native POWER actions use float multiplication; reject non-finite mutation. */
    public void multiplySkillPower(float multiplier) {
        float next = skillPower.value * multiplier;
        if (!Float.isFinite(multiplier) || !Float.isFinite(next)) {
            throw new IllegalArgumentException("Skill power must remain finite");
        }
        skillPower.value = next;
    }

    /** Copies scalar skill metadata for a mechanic while retaining the shared skill variables. */
    public BehaviorContext copyForSkillMechanic() {
        return new BehaviorContext(this, executionAudience, conditionScope, new SkillPower(skillPower.value));
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

    /** Active entity recipient, including display and interaction entities. */
    public Optional<Entity> entityRecipient() {
        return Optional.ofNullable(entityRecipient);
    }

    /** All selected entities; {@link #targets()} remains the living-only projection. */
    public List<Entity> entityTargets() {
        return entityTargets;
    }

    /** Replaces the complete selection, including an explicitly empty selection. */
    public BehaviorContext withEntityTargets(List<? extends Entity> entities) {
        List<Entity> selected = List.copyOf(entities);
        Entity first = selected.isEmpty() ? null : selected.getFirst();
        LivingEntity living = first instanceof LivingEntity entity ? entity : null;
        double maximum = living == null ? 0 : living.getMaxHealth();
        double ratio = maximum > 0 ? living.getHealth() / maximum : 0;
        double distance = -1;
        Location location = first == null ? null : first.getLocation();
        if (origin != null && origin.getWorld() != null && location != null
                && origin.getWorld().equals(location.getWorld())) distance = origin.distance(location);
        return new BehaviorContext(instance, first instanceof Player player ? player : null, living, ratio, distance,
                decisionCount, state, payload, trigger, selected, origin, skillTree,
                cancellableEvent, damageCause, null, first)
                .withExecutionAudienceFrom(this).withConditionScope(conditionScope);
    }

    public boolean entityTargetsPresent() { return entityTargetsPresent; }

    /** Replaces tree-local state without selecting targets or losing native collection presence. */
    public BehaviorContext withSkillTree(SkillTreeContext tree) {
        return new BehaviorContext(instance, target, targetEntity, healthRatio, distanceToTarget,
                decisionCount, state, payload, trigger, entityTargetsPresent ? entityTargets : null,
                origin, tree, cancellableEvent, damageCause, targetLocation, entityRecipient)
                .withExecutionAudienceFrom(this).withConditionScope(conditionScope);
    }

    /** Selects one generic entity without presenting a nonliving entity as a living target. */
    public BehaviorContext withEntityRecipient(Entity entity) {
        if (entity == null) throw new IllegalArgumentException("entity recipient is required");
        if (entity instanceof LivingEntity living) return withTargetEntity(living);
        double distance = -1;
        Location location = entity.getLocation();
        if (origin != null && origin.getWorld() != null && location != null
                && origin.getWorld().equals(location.getWorld())) distance = origin.distance(location);
        return new BehaviorContext(instance, null, null, 0, distance, decisionCount, state, payload,
                trigger, List.of(entity), origin, skillTree, cancellableEvent, damageCause, null, entity)
                .withExecutionAudienceFrom(this);
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

    /** Optional execution audience; a present empty set explicitly allows no players. */
    public Optional<Set<UUID>> executionAudience() {
        return Optional.ofNullable(executionAudience.get());
    }

    /** Returns a context whose audience is the supplied set of players, including an explicit empty set. */
    public BehaviorContext withExecutionAudience(Collection<? extends Player> players) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (players != null) {
            for (Player player : players) {
                if (player != null && player.getUniqueId() != null) ids.add(player.getUniqueId());
            }
        }
        return new BehaviorContext(this, new ExecutionAudienceScope(ids));
    }

    /** Returns a context restricted to the intersection of this and the supplied player IDs. */
    public BehaviorContext withNarrowedExecutionAudience(Collection<UUID> players) {
        Set<UUID> narrowed = new LinkedHashSet<>();
        if (players != null) narrowed.addAll(players);
        Set<UUID> active = executionAudience.get();
        if (active != null) narrowed.retainAll(active);
        return new BehaviorContext(this, new ExecutionAudienceScope(narrowed));
    }

    /** Replaces the active skill audience without changing the mob's persisted audience. */
    public void setExecutionAudience(Collection<UUID> players) {
        executionAudience.set(players);
    }

    /** Carries execution metadata across a scoped continuation, including any explicit audience. */
    public BehaviorContext withExecutionAudienceFrom(BehaviorContext source) {
        if (source == null) return this;
        return new BehaviorContext(this, source.executionAudience.get() == null
                ? executionAudience : source.executionAudience, conditionScope, source.skillPower);
    }

    /** Origin used by location targeters and mechanics. */
    public Optional<Location> origin() {
        return Optional.ofNullable(origin == null ? null : origin.clone());
    }

    /** Current positional target, independent of entity targets such as {@code @Pin}. */
    public Optional<Location> targetLocation() {
        return Optional.ofNullable(targetLocation == null ? null : targetLocation.clone());
    }

    /** Returns a continuation scoped to a location target while retaining execution metadata. */
    public BehaviorContext withLocationTarget(Location location) {
        if (location == null || location.getWorld() == null) {
            throw new IllegalArgumentException("location target must have a world");
        }
        return new BehaviorContext(instance, null, null, 0, -1, decisionCount, state, payload,
                trigger, entityTargetsPresent ? List.of() : null, origin, skillTree,
                cancellableEvent, damageCause, location)
                .withExecutionAudienceFrom(this);
    }

    /** Returns a continuation scoped to one entity in the current target set. */
    public BehaviorContext withTargetEntity(LivingEntity entity) {
        if (entity == null) throw new IllegalArgumentException("entity target is required");
        double scopedHealthRatio = healthRatio;
        double maximum = entity.getMaxHealth();
        if (Double.isFinite(maximum) && maximum > 0) {
            double health = entity.getHealth();
            if (Double.isFinite(health)) scopedHealthRatio = health / maximum;
        }
        double scopedDistance = distanceToTarget;
        Location entityLocation = entity.getLocation();
        if (origin != null && origin.getWorld() != null && entityLocation != null
                && origin.getWorld().equals(entityLocation.getWorld())) {
            scopedDistance = origin.distance(entityLocation);
        }
        Player player = entity instanceof Player p ? p : null;
        return new BehaviorContext(instance, player, entity, scopedHealthRatio, scopedDistance,
                decisionCount, state, payload, trigger, List.of(entity), origin, skillTree,
                cancellableEvent, damageCause)
                .withExecutionAudienceFrom(this);
    }

    /** Returns this context without retaining a synchronous event beyond its trigger. */
    public BehaviorContext withoutCancellableEvent() {
        return cancellableEvent == null ? this : new BehaviorContext(this, true);
    }

    /** Snapshots the calling skill tree and scalar metadata for one independent Aura tracker. */
    public BehaviorContext copyForAuraTracker(LivingEntity entity) {
        if (entity == null) throw new IllegalArgumentException("Aura target is required");
        SkillTreeContext trackerTree = skillTree == null
                ? new SkillTreeContext(entity, List.of(entity), origin)
                : skillTree.copyForAuraTracker(entity, List.of(entity));
        BehaviorContext scoped = withTargetEntity(entity);
        return new BehaviorContext(instance, scoped.target, entity, scoped.healthRatio, scoped.distanceToTarget,
                decisionCount, state, payload, trigger, List.of(entity), origin, trackerTree,
                null, null, null).withExecutionAudienceFrom(this).copyForSkillMechanic();
    }

    /** Creates a tick callback sharing its tracker's variables and following the affected entity. */
    public BehaviorContext periodicTarget(LivingEntity entity) {
        if (entity == null) throw new IllegalArgumentException("Aura callback target is required");
        return auraCallbackTarget(entity, "aura-tick", entity.getLocation());
    }

    /** Creates an end callback using the tracker's most recent origin and shared variables. */
    public BehaviorContext auraEndTarget(LivingEntity entity) {
        return auraCallbackTarget(entity, "aura-end", origin);
    }

    private BehaviorContext auraCallbackTarget(LivingEntity entity, String callbackState, Location callbackOrigin) {
        if (entity == null) throw new IllegalArgumentException("Aura callback target is required");
        double scopedHealthRatio = healthRatio;
        double maximum = entity.getMaxHealth();
        if (Double.isFinite(maximum) && maximum > 0) {
            double health = entity.getHealth();
            if (Double.isFinite(health)) scopedHealthRatio = health / maximum;
        }
        Location scopedOrigin = callbackOrigin == null ? null : callbackOrigin.clone();
        Location entityLocation = entity.getLocation();
        double scopedDistance = -1;
        if (scopedOrigin != null && scopedOrigin.getWorld() != null && entityLocation != null
                && scopedOrigin.getWorld().equals(entityLocation.getWorld())) {
            scopedDistance = scopedOrigin.distance(entityLocation);
        }
        Player player = entity instanceof Player p ? p : null;
        SkillTreeContext trackerTree = skillTree == null
                ? new SkillTreeContext(entity, List.of(entity), scopedOrigin) : skillTree;
        trackerTree.targets(List.of(entity));
        if (scopedOrigin != null) trackerTree.setLocation("origin", scopedOrigin);
        return new BehaviorContext(instance, player, entity, scopedHealthRatio, scopedDistance,
                decisionCount, callbackState, null, null, List.of(entity), scopedOrigin,
                trackerTree, null, null, null).withExecutionAudienceFrom(this).copyForSkillMechanic();
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
