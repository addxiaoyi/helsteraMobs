package dev.helstera.ai;

import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.MobStateChangedEvent;
import dev.helstera.api.event.ModelPreTargetEvent;
import dev.helstera.api.event.ModelTargetChangedEvent;
import dev.helstera.ai.nav.NavNode;
import dev.helstera.ai.nav.PathFollower;
import dev.helstera.api.model.ModelHitbox;
import dev.helstera.runtime.animation.EntityAnimationStateMachine;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 有限状态机 AI 控制器（每实例一个，由 AiManager 统一节拍驱动，不建独立任务）。
 * 状态：IDLE / PATROL / CHASE / ATTACK / HURT / FLEE / DEAD。
 * 感知器：视线（rayTrace）、半径、伤害事件、血量。
 * 决策只产生动作意图（写动画状态机 + 移动/攻击动作）。
 */
public final class AiController {

    public enum State {IDLE, PATROL, CHASE, ATTACK, HURT, FLEE, DEAD}

    private final ModelInstanceImpl inst;
    /**
     * 行为档案。刻意<b>非 final</b>：{@code /helstera reload config} 会重建
     * {@code AiProfile} 对象，而本控制器在 attach 时捕获的是旧引用——
     * 写成 final 的话配置热改对存量 Boss 完全无效，且无任何报错。
     * 换绑入口见 {@link #rebindProfile(AiProfile)}，只由 AiManager 调用。
     */
    private AiProfile profile;
    private final EntityAnimationStateMachine anim;
    private final HelsteraEventBus bus;
    /** 自定义条件/动作来源；为 null 时决策链不执行任何扩展钩子。 */
    private final dev.helstera.api.behavior.BehaviorRegistry behaviors;

    private State state = State.IDLE;
    private Player target;
    private LivingEntity lastAttackerEntity;
    private LivingEntity combatTarget;
    private Player lastAttacker;
    /**
     * 仇恨表；档案未启用 {@code threat-enabled} 时为 null，
     * 此时退回「最近者」的单目标行为以保持旧配置表现不变。
     */
    private final ThreatTable threat;
    private long lastAttack;
    private long lastPatrol;
    private long decisions;
    private Location patrolTarget;
    private Location home;
    private final Random random = new Random();
    /** 寻路服务；未注入时全部走直线，保证旧配置行为不变。 */
    private volatile dev.helstera.ai.nav.NavService navService;
    /** 触发寻路的最小距离平方；低于它一律直连，避免近距离频繁重算。 */
    private static final double PROFILE_PATH_MIN_DIST_SQ = 4.0;

    /** 注入寻路服务；null 表示不启用，一切走直线。 */
    public void navService(dev.helstera.ai.nav.NavService s) {
        this.navService = s;
    }

    /**
     * 换绑到重载后的档案对象。
     *
     * <p>只换引用，不重置仇恨表与状态机：reload config 不该把 Boss 打成
     * 「忘了刚才是谁在打它」，那会让 reload 本身变成一次战斗状态清档。</p>
     *
     * @param fresh 新档案；null 会被忽略（保留旧引用优于退化成无档案）
     */
    public void rebindProfile(AiProfile fresh) {
        if (fresh != null) this.profile = fresh;
    }

    public AiController(ModelInstanceImpl inst, AiProfile profile,
                        EntityAnimationStateMachine anim, HelsteraEventBus bus) {
        this(inst, profile, anim, bus, null);
    }

    public AiController(ModelInstanceImpl inst, AiProfile profile,
                        EntityAnimationStateMachine anim, HelsteraEventBus bus,
                        dev.helstera.api.behavior.BehaviorRegistry behaviors) {
        this.inst = inst;
        this.profile = profile;
        this.anim = anim;
        this.bus = bus;
        this.behaviors = behaviors;
        this.threat = profile.threatEnabled
                ? new ThreatTable(profile.threatDecayPerSecond, profile.threatDistanceWeight,
                        System::currentTimeMillis, id -> Bukkit.getEntity(id))
                : null;
        this.home = inst.location() != null ? inst.location().clone() : null;
        applySelectorProfile(inst.entity(), inst.location());
    }

    public State state() {
        return state;
    }

    public LivingEntity targetEntity() {
        return combatTarget != null ? combatTarget : target;
    }

    /** Updates the combat target from a skill mechanic or an external integration. */
    public void setCombatTarget(LivingEntity entity) {
        LivingEntity previous = targetEntity();
        combatTarget = entity;
        target = entity instanceof Player player ? player : null;
        syncNativeTarget(entity);
        if (entity instanceof Player player && threat != null) threat.focus(player.getUniqueId());
        if (previous != entity) bus.post(new ModelTargetChangedEvent(inst, previous, entity));
    }

    public void clearCombatTarget() {
        LivingEntity previous = targetEntity();
        combatTarget = null;
        target = null;
        syncNativeTarget(null);
        if (previous != null) bus.post(new ModelTargetChangedEvent(inst, previous, null));
    }

    public Player target() {
        return target;
    }

    public String selectorDiagnostic() {
        return "mode=" + selectorTargetMode
                + " can-attack=" + selectorCanAttack
                + " can-chase=" + selectorCanChase
                + " can-flee=" + selectorCanFlee
                + " target-matches=" + selectorTargetMatches(combatTarget)
                + " combat-target=" + (combatTarget == null ? null : combatTarget.getUniqueId());
    }

    /**
     * 受到伤害。
     *
     * <p>{@code damage} 是事件结算后的实际扣血量（已过护甲与抗性）。
     * 威胁按真实扣血累计；若按技能声明的理论伤害累计，高护甲目标会
     * 凭空拿到成倍仇恨，坦克配置完全失效。</p>
     */
    public void onDamaged(org.bukkit.entity.Entity attacker, double damage) {
        transition(State.HURT);
        if (attacker instanceof LivingEntity living) lastAttackerEntity = living;
        if (attacker instanceof Player player) lastAttacker = player;
        if (attacker instanceof LivingEntity living && profile.canChase) {
            if (threat != null) {
                Location loc = inst.location();
                threat.addThreat(living.getUniqueId(), damage,
                        loc == null ? 0 : safeDistance(loc, living.getLocation()));
            }
            if (living instanceof Player p) target = p;
            combatTarget = living;
        }
        if (anim != null) anim.intent(EntityAnimationStateMachine.AnimState.HURT);
    }

    /** 供 {@code threat} 动作直接加仇恨（对应 MythicMobs 的 threat mechanic）。 */
    public void addThreat(Player player, double amount) {
        if (threat != null && player != null) threat.addThreat(player.getUniqueId(), amount);
    }

    public void addThreat(LivingEntity entity, double amount) {
        if (threat != null && entity != null) threat.addThreat(entity.getUniqueId(), amount);
    }

    /** 当前仇恨表；未启用时为 null。 */
    public ThreatTable threatTable() {
        return threat;
    }

    private double safeDistance(Location from, Location targetLocation) {
        try {
            if (from == null || targetLocation == null || from.getWorld() == null
                    || !from.getWorld().equals(targetLocation.getWorld())) return -1;
            return from.distance(targetLocation);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private double safeDistance(Location from, Player p) {
        try {
            Location pl = p.getLocation();
            if (pl.getWorld() == null || from.getWorld() == null
                    || !pl.getWorld().equals(from.getWorld())) return 0;
            return pl.distance(from);
        } catch (Throwable t) {
            return 0;
        }
    }

    public void markDead() {
        transition(State.DEAD);
        inst.dead = true;
        if (anim != null) anim.intent(EntityAnimationStateMachine.AnimState.DEATH);
    }

    /** AI 决策节拍（建议每 10 Tick 调一次，主线程）。 */
    public void tick() {
        if (inst.dead || !inst.isValid()) return;
        Entity base = inst.entity();
        if (base == null || !base.isValid()) return;
        Location loc = base.getLocation();

        // 血量感知（载体盔甲架无血量；若绑定的是 LivingEntity 用其血量）
        double healthRatio = 1.0;
        if (base instanceof LivingEntity le && le.getMaxHealth() > 0) {
            healthRatio = le.getHealth() / le.getMaxHealth();
        }

        // 感知：最近可视线玩家
        applySelectorProfile(base, loc);
        LivingEntity sensed = sense(loc);
        decisions++;
        dev.helstera.api.behavior.BehaviorContext ctx = behaviors == null ? null
                : dev.helstera.api.behavior.BehaviorContext.of(
                         inst, combatTarget != null ? combatTarget : sensed, healthRatio,
                         sensed == null ? -1 : sensed.getLocation().distance(loc), decisions, state.name());

        // 扩展点一：自定义前置条件，任一不通过则本节拍不做内置决策
        if (behaviors != null && ctx != null) {
            for (String c : profile.require) {
                if (!behaviors.testCondition(c, ctx)) return;
            }
        }

        decide(loc, sensed, healthRatio);

        // 扩展点二：每次决策后执行自定义动作（无论本次走了哪个分支）
        if (behaviors != null && ctx != null) {
            for (String a : profile.onDecision) behaviors.runAction(a, ctx);
        }
    }

    /** 内置状态机决策（优先级从高到低）。 */
    private final Set<String> reportedSelectorWarnings = ConcurrentHashMap.newKeySet();
    private boolean selectorCanChase = true;
    private boolean selectorCanAttack = true;
    private boolean selectorCanPatrol = true;
    private boolean selectorCanFlee;
    private boolean selectorRangedAttack;
    private boolean selectorLeapAtTarget;
    private boolean selectorAllowsPlayerTargets = true;
    private AiProfile.TargetMode selectorTargetMode = AiProfile.TargetMode.PLAYERS;
    private boolean selectorLooksAtPlayers;
    private boolean selectorAvoidsEntities;
    private boolean selectorAttackerFirst;
    private boolean selectorHurtByFirst;
    private boolean selectorThreatPriority;
    private String selectorTargetFaction;
    private boolean selectorFactionHostile;
    private boolean selectorFactionAllied;
    private String selectorTemptItem;
    private double selectorTemptRange;
    private boolean selectorCanSwim = true;
    private boolean selectorCanOpenDoors;
    private boolean selectorAvoidSun;
    private boolean selectorHasExplicitGoalPriority;
    private int selectorFleePriority = Integer.MAX_VALUE;
    private int selectorAttackPriority = Integer.MAX_VALUE;
    private int nativePathStopCalls;

    public int nativePathStopCalls() {
        return nativePathStopCalls;
    }

    private void applySelectorProfile(Entity base, Location loc) {
        AiProfile.SelectorPolicy policy = profile.selectorPolicy();
        selectorCanChase = policy.chase();
        selectorCanAttack = policy.attack();
        selectorCanPatrol = policy.patrol();
        selectorCanFlee = policy.flee();
        selectorRangedAttack = policy.rangedAttack() || profile.rangedAttack;
        selectorLeapAtTarget = policy.leapAtTarget() || profile.leapAtTarget;
        selectorCanSwim = policy.swim();
        selectorCanOpenDoors = policy.openDoors();
        selectorAvoidSun = policy.avoidSun();
        selectorAllowsPlayerTargets = policy.playerTargets();
        selectorTargetMode = policy.targetMode();
        selectorLooksAtPlayers = policy.lookAtPlayers();
        selectorAvoidsEntities = policy.avoidEntities();
        selectorAttackerFirst = policy.attackerFirst();
        selectorHurtByFirst = policy.hurtByTargetFirst();
        selectorThreatPriority = policy.targetMode() == AiProfile.TargetMode.THREAT;
        selectorTargetFaction = policy.targetFaction();
        selectorFactionHostile = policy.factionHostile();
        selectorTemptItem = policy.temptItem();
        if (selectorTemptItem == null) selectorTemptItem = temptItemFromGoals(profile.goals);
        selectorTemptRange = policy.temptRange();
        selectorHasExplicitGoalPriority = !profile.goals.isEmpty();
        selectorFleePriority = policy.goalPriority(profile.goals, "flee");
        selectorAttackPriority = policy.goalPriority(profile.goals, "attack");
        applySelectorAdapters(base, loc);
        for (String unsupported : policy.unsupported()) {
            int separator = unsupported.indexOf(':');
            warnSelector(separator < 0 ? "selector" : unsupported.substring(0, separator),
                    separator < 0 ? unsupported : unsupported.substring(separator + 1));
        }
        if (selectorTargetMode != AiProfile.TargetMode.PLAYERS) target = null;
        if (!selectorTargetMatches(combatTarget)) combatTarget = null;
    }

    private void applySelectorAdapters(Entity base, Location loc) {
        try {
            if (base instanceof org.bukkit.entity.Mob mob) {
                com.destroystokyo.paper.entity.Pathfinder pathfinder = mob.getPathfinder();
                if (pathfinder != null) {
                    pathfinder.setCanFloat(selectorCanSwim);
                    pathfinder.setCanOpenDoors(selectorCanOpenDoors);
                    pathfinder.setCanPassDoors(selectorCanOpenDoors);
                    if (selectorTargetMode == AiProfile.TargetMode.NONE && !selectorCanPatrol) {
                        pathfinder.stopPathfinding();
                    }
                }
            }
            if (base instanceof LivingEntity living) living.setSwimming(selectorCanSwim && base.isInWater());
            if (selectorAvoidSun && base instanceof org.bukkit.entity.Mob mob && mob.isInDaylight()) moveToShade(mob, loc);
            if (selectorLooksAtPlayers) { Player player = nearestVisiblePlayer(loc); if (player != null) faceToward(player.getLocation()); }
        } catch (RuntimeException ex) {
            warnSelector("adapter", ex.getClass().getSimpleName());
        }
    }

    private void moveToShade(org.bukkit.entity.Mob mob, Location origin) {
        if (origin == null || origin.getWorld() == null) return;
        com.destroystokyo.paper.entity.Pathfinder pathfinder = mob.getPathfinder();
        if (pathfinder == null) return;
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                if (dx * dx + dz * dz < 4) continue;
                Location candidate = origin.clone().add(dx, 0, dz);
                if (candidate.getBlock().getLightFromSky() >= 15) continue;
                double distance = candidate.distanceSquared(origin);
                if (distance < bestDistance) { best = candidate; bestDistance = distance; }
            }
        }
        if (best != null) pathfinder.moveTo(best, Math.max(0.05, profile.moveSpeed));
    }
    private void warnSelector(String kind, String token) {
        if (reportedSelectorWarnings.add(kind + ":" + token)) {
            org.bukkit.Bukkit.getLogger().warning("Unsupported AI " + kind + " selector token: " + token);
        }
    }

    private void decide(Location loc, LivingEntity sensed, double healthRatio) {
        // 决策（优先级从高到低）
        if (state == State.DEAD) return;
        boolean fleeHasPriority = !selectorHasExplicitGoalPriority
                || selectorFleePriority <= selectorAttackPriority;
        if (selectorCanFlee && fleeHasPriority && healthRatio <= profile.fleeHealthRatio) {
            transition(State.FLEE);
            flee(loc);
            return;
        }
        if (selectorAvoidsEntities && fleeHasPriority) {
            LivingEntity danger = nearestDanger(loc);
            if (danger != null) {
                target = danger instanceof Player player ? player : null;
                combatTarget = danger;
                syncNativeTarget(danger);
                transition(State.FLEE);
                moveAwayFrom(loc, danger.getLocation());
                return;
            }
        }
        // 目标来源：启用仇恨表时由威胁值决定，否则退回「最近可见者」。
        // 两者不能混用——sense() 每 tick 都可能把仇恨最低的人重新吸成目标，
        // 那等于仇恨表白建。
        LivingEntity foe = preferredCombatTarget(loc, sensed);
        if (foe != null && (!foe.isValid() || foe.isDead())) foe = null;
        syncNativeTarget(foe);
        if (foe != null) {
            double dist = safeDistance(loc, foe.getLocation());
            if (selectorCanAttack && selectorRangedAttack
                    && dist > profile.attackRadius && dist <= profile.rangedAttackRange) {
                transition(State.ATTACK);
                rangedAttack(foe);
                return;
            }
            if (selectorCanAttack && selectorLeapAtTarget
                    && dist > profile.attackRadius && dist <= profile.leapRange) {
                transition(State.ATTACK);
                leapAtTarget(foe, loc);
                return;
            }
            if (selectorCanAttack && dist >= 0 && dist <= profile.attackRadius) {
                transition(State.ATTACK);
                attackEntity(foe);
                return;
            }
            if (selectorCanChase) {
                transition(State.CHASE);
                combatTarget = foe;
                target = foe instanceof Player player ? player : null;
                moveToward(loc, foe.getLocation());
                return;
            }
        }
        if (selectorCanPatrol) {
            transition(State.PATROL);
            patrol(loc);
        } else {
            syncNativeTarget(null);
            stopNativePathfinding();
            transition(State.IDLE);
        }
    }

    private void stopNativePathfinding() {
        Entity base = inst.entity();
        if (!(base instanceof org.bukkit.entity.Mob mob)) return;
        try {
            com.destroystokyo.paper.entity.Pathfinder pathfinder = mob.getPathfinder();
            if (pathfinder != null) {
                nativePathStopCalls++;
                pathfinder.stopPathfinding();
            }
        } catch (RuntimeException ex) {
            warnSelector("pathfinder", ex.getClass().getSimpleName());
        }
    }

    /** Mirrors the custom selector result into Bukkit's native Mob target slot. */
    private void syncNativeTarget(LivingEntity entity) {
        Entity base = inst.entity();
        if (!(base instanceof org.bukkit.entity.Mob mob)) return;
        try {
            mob.setTarget(entity != null && entity.isValid() && !entity.isDead() ? entity : null);
        } catch (RuntimeException ex) {
            warnSelector("native-target", ex.getClass().getSimpleName());
        }
    }

    /** 按仇恨选出当前目标；未启用仇恨表或无有效仇恨时返回 null。 */
    private LivingEntity nearestDanger(Location origin) {
        if (origin == null || origin.getWorld() == null) return null;
        Entity self = inst.entity();
        LivingEntity nearest = null;
        double best = profile.sightRadius * profile.sightRadius;
        for (Entity entity : origin.getWorld().getNearbyEntities(origin, profile.sightRadius, profile.sightRadius, profile.sightRadius)) {
            if (!(entity instanceof LivingEntity living) || living == self || !living.isValid() || living.isDead()) continue;
            if (dev.helstera.api.behavior.Factions.allied(self, living)) continue;
            double distance = living.getLocation().distanceSquared(origin);
            if (distance < best) { nearest = living; best = distance; }
        }
        return nearest;
    }

    private void moveAwayFrom(Location origin, Location danger) {
        if (origin == null || danger == null || origin.getWorld() == null || !origin.getWorld().equals(danger.getWorld())) return;
        Vector away = origin.toVector().subtract(danger.toVector());
        away.setY(0);
        if (away.lengthSquared() < 0.001) away = new Vector(1, 0, 0);
        moveBy(away.normalize().multiply(profile.moveSpeed), origin);
        if (anim != null) anim.enterLocomotion();
    }

    private LivingEntity preferredCombatTarget(Location loc, LivingEntity sensed) {
        if ((selectorAttackerFirst || selectorHurtByFirst) && lastAttackerEntity != null
                && lastAttackerEntity.isValid() && !lastAttackerEntity.isDead()) return lastAttackerEntity;
        if (selectorTargetMatches(combatTarget)) return combatTarget;
        return preferredTarget(loc, sensed);
    }

    private LivingEntity preferredTarget(Location loc, LivingEntity sensed) {
        if (selectorTargetMode == AiProfile.TargetMode.OWNER) return ownerTarget(loc);
        if (selectorThreatPriority) return threatTargetEntity(loc);
        if (selectorTargetMode == AiProfile.TargetMode.FACTION) return factionTarget(loc);
        if ((selectorAttackerFirst || selectorHurtByFirst) && validTarget(lastAttackerEntity)) {
            return lastAttackerEntity;
        }
        if (selectorTargetMode == AiProfile.TargetMode.PLAYERS) {
            Player threatTarget = threatTarget(loc);
            return threatTarget != null ? threatTarget : sensed;
        }
        return targetMatches(sensed, selectorTargetMode) ? sensed : null;
    }

    private static boolean validTarget(Player player) {
        return player != null && player.isOnline() && !player.isDead();
    }

    private static boolean validTarget(LivingEntity entity) {
        return entity != null && entity.isValid() && !entity.isDead();
    }

    private LivingEntity threatTargetEntity(Location loc) {
        if (threat == null) return null;
        for (UUID id : threat.ordered(uuid -> {
            Entity entity = Bukkit.getEntity(uuid);
            return entity instanceof LivingEntity living ? safeDistance(loc, living.getLocation()) : Double.MAX_VALUE;
        })) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity living && validTarget(living)
                    && !dev.helstera.api.behavior.Factions.allied(inst.entity(), living)) return living;
        }
        return null;
    }

    private LivingEntity factionTarget(Location loc) {
        if (loc == null || loc.getWorld() == null || selectorTargetFaction == null) return null;
        LivingEntity best = null;
        double bestDistance = profile.sightRadius;
        for (Entity entity : loc.getWorld().getNearbyEntities(loc, profile.sightRadius,
                profile.sightRadius, profile.sightRadius)) {
            if (!(entity instanceof LivingEntity living) || !validTarget(living)
                    || entity == inst.entity()) continue;
            String faction = dev.helstera.api.behavior.Factions.factionOf(entity);
            boolean match = selectorFactionAllied
                    ? dev.helstera.api.behavior.Factions.table() != null
                    && dev.helstera.api.behavior.Factions.table().alliedFactions(faction, selectorTargetFaction)
                    : selectorFactionHostile
                    ? faction == null || !faction.equalsIgnoreCase(selectorTargetFaction)
                    : faction != null && faction.equalsIgnoreCase(selectorTargetFaction);
            if (!match || (!selectorFactionHostile
                    && dev.helstera.api.behavior.Factions.allied(inst.entity(), living))) continue;
            double distance = safeDistance(loc, living.getLocation());
            if (distance >= 0 && distance <= bestDistance) {
                best = living;
                bestDistance = distance;
            }
        }
        return best;
    }

    private Player threatTarget(Location loc) {
        if (threat == null) return null;
        UUID id = threat.top(uuid -> {
            Player p = Bukkit.getPlayer(uuid);
            return p == null ? Double.MAX_VALUE : safeDistance(loc, p);
        });
        if (id == null) return null;
        return Bukkit.getPlayer(id);
    }

    /**
     * 视线感知器：半径内最近玩家，且方块射线不被阻挡。
     *
     * <p>跳过与自身同阵营的玩家：否则守卫会把同为守卫的玩家当作敌人追击，
     * 而这一行为在配置上完全看不出来（档案只写了 {@code faction}，
     * 没人预期它会改变感知范围）。</p>
     */
    private Player nearestVisiblePlayer(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        Player nearest = null;
        double distance = profile.sightRadius;
        for (Player player : loc.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double candidateDistance = safeDistance(loc, player);
            if (candidateDistance <= distance) { nearest = player; distance = candidateDistance; }
        }
        return nearest;
    }
    private LivingEntity sense(Location loc) {
        if (selectorTargetMode == AiProfile.TargetMode.OWNER) return ownerTarget(loc);
        if (selectorThreatPriority || selectorTargetMode == AiProfile.TargetMode.FACTION) return preferredTarget(loc, null);
        LivingEntity best = null;
        double bestD = selectorTemptItem == null ? profile.sightRadius
                : Math.min(profile.sightRadius, selectorTemptRange);
        if (selectorTargetMode == AiProfile.TargetMode.NONE || loc.getWorld() == null) return null;
        Entity self = inst.entity();
        if (selectorTemptItem != null) {
            for (Player player : loc.getWorld().getPlayers()) {
                double distance = safeDistance(loc, player.getLocation());
                boolean valid = validTarget(player);
                boolean playable = player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                        && player.getGameMode() != org.bukkit.GameMode.CREATIVE;
                boolean holds = holdsTemptItem(player, selectorTemptItem);
                boolean allied = dev.helstera.api.behavior.Factions.allied(self, player);
                if (!valid || !playable || !holds || allied) continue;
                if (distance >= 0 && distance <= bestD) {
                    best = player;
                    bestD = distance;
                }
            }
            if (best != null) return best;
        }
        for (Entity candidate : loc.getWorld().getNearbyEntities(
                loc, profile.sightRadius, profile.sightRadius, profile.sightRadius)) {
            if (!(candidate instanceof LivingEntity living) || !targetMatches(living, selectorTargetMode)) continue;
            if (living == self || !living.isValid() || living.isDead()) continue;
            if (living instanceof Player p && (p.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || p.getGameMode() == org.bukkit.GameMode.CREATIVE)) continue;
            if (selectorTemptItem != null && living instanceof Player player
                    && !holdsTemptItem(player, selectorTemptItem)) continue;
            if (dev.helstera.api.behavior.Factions.allied(self, living)) continue;
            Location pl = living.getLocation();
            if (pl.getWorld() == null || !pl.getWorld().equals(loc.getWorld())) continue;
            double d = safeDistance(loc, pl);
            if (d < 0 || d > bestD) continue;
            // 方块阻挡感知
            var ray = loc.getWorld().rayTraceBlocks(
                    loc.clone().add(0, 1.5, 0),
                    pl.clone().add(0, 1.2, 0).subtract(loc.clone().add(0, 1.5, 0)).toVector(),
                    d, org.bukkit.FluidCollisionMode.NEVER);
            if (ray != null && ray.getHitBlock() != null) continue;
            best = living;
            bestD = d;
        }
        return best;
    }

    static boolean holdsTemptItem(Player player, String configured) {
        if (player == null || configured == null || configured.isBlank()) return false;
        for (String raw : configured.split("[,|]")) {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(raw.trim());
            if (material == null) continue;
            var inventory = player.getInventory();
            if (inventory.getItemInMainHand().getType() == material
                    || inventory.getItemInOffHand().getType() == material) return true;
        }
        return false;
    }

    private static String temptItemFromGoals(List<String> goals) {
        if (goals == null) return null;
        for (String raw : goals) {
            if (raw == null) continue;
            String[] parts = raw.trim().split("\\s+");
            int index = parts.length > 0 && parts[0].matches("\\d+") ? 1 : 0;
            if (index < parts.length && parts[index].equalsIgnoreCase("tempt")
                    && index + 1 < parts.length && !parts[index + 1].isBlank()) {
                return parts[index + 1].trim();
            }
        }
        return null;
    }

    private LivingEntity ownerTarget(Location origin) {
        Entity base = inst.entity();
        if (!(base instanceof org.bukkit.entity.Tameable tameable) || !tameable.isTamed()) return null;
        org.bukkit.entity.AnimalTamer owner = tameable.getOwner();
        if (!(owner instanceof LivingEntity living) || !validTarget(living)) return null;
        Location at = living.getLocation();
        if (origin == null || at == null || origin.getWorld() == null
                || !origin.getWorld().equals(at.getWorld())) return null;
        return safeDistance(origin, at) <= profile.sightRadius ? living : null;
    }

    private boolean selectorTargetMatches(LivingEntity entity) {
        if (entity == null || !validTarget(entity)) return false;
        if (selectorTargetMode == AiProfile.TargetMode.THREAT) return threat != null && threat.contains(entity.getUniqueId());
        if (selectorTargetMode == AiProfile.TargetMode.FACTION) {
            String faction = dev.helstera.api.behavior.Factions.factionOf(entity);
            if (selectorFactionAllied) {
                return dev.helstera.api.behavior.Factions.table() != null
                        && dev.helstera.api.behavior.Factions.table().alliedFactions(faction, selectorTargetFaction);
            }
            return selectorFactionHostile
                    ? faction == null || selectorTargetFaction == null
                    || !faction.equalsIgnoreCase(selectorTargetFaction)
                    : faction != null && selectorTargetFaction != null
                    && faction.equalsIgnoreCase(selectorTargetFaction);
        }
        return targetMatches(entity, selectorTargetMode);
    }

    private static boolean targetMatches(LivingEntity entity, AiProfile.TargetMode mode) {
        if (entity == null || mode == null) return false;
        return switch (mode) {
            case NONE -> false;
            case PLAYERS -> entity instanceof Player;
            case LIVING -> true;
            case MOBS -> entity instanceof org.bukkit.entity.Mob;
            case MONSTERS -> entity instanceof org.bukkit.entity.Monster;
            case ANIMALS -> entity instanceof org.bukkit.entity.Animals;
            case VILLAGERS -> entity instanceof org.bukkit.entity.Villager;
            case OWNER, THREAT, FACTION -> false;
        };
    }

    private void attackEntity(LivingEntity entity) {
        combatTarget = entity;
        bus.post(new ModelPreTargetEvent(inst, entity));
        if (entity instanceof Player player) { attack(player); return; }
        long now = System.currentTimeMillis();
        if (now - lastAttack < profile.attackCooldown * 1000L) { faceToward(entity.getLocation()); return; }
        lastAttack = now;
        if (anim != null) anim.intent(EntityAnimationStateMachine.AnimState.ATTACK);
        faceToward(entity.getLocation());
    }

    private void rangedAttack(LivingEntity entity) {
        if (!validTarget(entity)) return;
        long now = System.currentTimeMillis();
        if (now - lastAttack < profile.attackCooldown * 1000L) {
            faceToward(entity.getLocation());
            return;
        }
        Entity base = inst.entity();
        if (!(base instanceof org.bukkit.projectiles.ProjectileSource source)) {
            attackEntity(entity);
            return;
        }
        Location origin = base.getLocation().clone().add(0, 1.5, 0);
        Vector direction = entity.getLocation().clone().add(0, 1.0, 0)
                .subtract(origin).toVector();
        if (direction.lengthSquared() < 0.001) return;
        lastAttack = now;
        combatTarget = entity;
        target = entity instanceof Player player ? player : null;
        bus.post(new ModelPreTargetEvent(inst, entity));

        org.bukkit.entity.Projectile projectile = launchConfiguredProjectile(source, profile.rangedProjectileType,
                direction.normalize().multiply(profile.rangedProjectileSpeed));
        if (projectile == null) {
            attackEntity(entity);
            return;
        }
        projectile.setGravity(profile.rangedProjectileGravity);
        if (projectile instanceof org.bukkit.entity.AbstractArrow arrow) {
            arrow.setDamage(profile.rangedProjectileDamage);
            arrow.setPierceLevel(profile.rangedProjectilePierce);
            arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
        }
        org.bukkit.NamespacedKey key;
        if (profile.rangedHitSkill != null
                && (key = dev.helstera.ai.skill.SkillTriggers.projectileKey("projectile_skill")) != null) {
            projectile.getPersistentDataContainer().set(key,
                    org.bukkit.persistence.PersistentDataType.STRING, profile.rangedHitSkill);
        }
        if (profile.rangedProjectileFireTicks > 0
                && (key = dev.helstera.ai.skill.SkillTriggers.projectileKey("projectile_fire")) != null) {
            projectile.getPersistentDataContainer().set(key,
                    org.bukkit.persistence.PersistentDataType.INTEGER, profile.rangedProjectileFireTicks);
        }
        if (profile.rangedProjectileExplosion > 0
                && (key = dev.helstera.ai.skill.SkillTriggers.projectileKey("projectile_explosion")) != null) {
            projectile.getPersistentDataContainer().set(key,
                    org.bukkit.persistence.PersistentDataType.DOUBLE, profile.rangedProjectileExplosion);
        }
        if (profile.rangedProjectileMaxHits > 0
                && (key = dev.helstera.ai.skill.SkillTriggers.projectileKey("projectile_max_hits")) != null) {
            projectile.getPersistentDataContainer().set(key,
                    org.bukkit.persistence.PersistentDataType.INTEGER, profile.rangedProjectileMaxHits);
        }
        if (profile.rangedProjectilePotion != null
                && (key = dev.helstera.ai.skill.SkillTriggers.projectileKey("projectile_potion")) != null) {
            projectile.getPersistentDataContainer().set(key,
                    org.bukkit.persistence.PersistentDataType.STRING, profile.rangedProjectilePotion);
        }
        if (profile.rangedProjectileTracking && profile.rangedProjectileTrackingTicks > 0) {
            dev.helstera.ai.skill.SkillCatalog.trackProjectile(projectile, entity,
                    profile.rangedProjectileSpeed, profile.rangedProjectileTrackingTicks);
        }
        if (anim != null) anim.intent(EntityAnimationStateMachine.AnimState.ATTACK);
        faceToward(entity.getLocation());
    }

    private static org.bukkit.entity.Projectile launchConfiguredProjectile(
            org.bukkit.projectiles.ProjectileSource source, String rawType, Vector velocity) {
        String type = rawType == null ? "ARROW" : rawType.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return switch (type) {
                case "SNOWBALL" -> source.launchProjectile(org.bukkit.entity.Snowball.class, velocity);
                case "EGG" -> source.launchProjectile(org.bukkit.entity.Egg.class, velocity);
                case "FIREBALL" -> source.launchProjectile(org.bukkit.entity.Fireball.class, velocity);
                case "SMALL_FIREBALL", "SMALLFIREBALL" -> source.launchProjectile(org.bukkit.entity.SmallFireball.class, velocity);
                case "WITHER_SKULL", "WITHERSKULL" -> source.launchProjectile(org.bukkit.entity.WitherSkull.class, velocity);
                case "DRAGON_FIREBALL", "DRAGONFIREBALL" -> source.launchProjectile(org.bukkit.entity.DragonFireball.class, velocity);
                case "TRIDENT" -> source.launchProjectile(org.bukkit.entity.Trident.class, velocity);
                default -> source.launchProjectile(org.bukkit.entity.Arrow.class, velocity);
            };
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return null;
        }
    }

    private void leapAtTarget(LivingEntity entity, Location origin) {
        if (!validTarget(entity) || origin == null) return;
        long now = System.currentTimeMillis();
        if (now - lastAttack < profile.attackCooldown * 1000L) return;
        Vector jump = entity.getLocation().toVector().subtract(origin.toVector());
        jump.setY(Math.max(0.35, profile.leapStrength));
        if (jump.lengthSquared() < 0.001) return;
        lastAttack = now;
        combatTarget = entity;
        target = entity instanceof Player player ? player : null;
        bus.post(new ModelPreTargetEvent(inst, entity));
        moveBy(jump.normalize().multiply(Math.max(profile.moveSpeed, profile.leapStrength)), origin);
        if (anim != null) anim.intent(EntityAnimationStateMachine.AnimState.ATTACK);
        faceToward(entity.getLocation());
    }

    private void attack(Player targetPlayer) {
        // 在设定目标前派发 on-pre-target：允许技能在此之前修改仇恨或施加效果
        this.target = targetPlayer;
        this.combatTarget = targetPlayer;
        long now = System.currentTimeMillis();
        if (now - lastAttack < profile.attackCooldown * 1000L) {
            faceToward(targetPlayer.getLocation());
            return;
        }
        lastAttack = now;
        if (anim != null) anim.intent(EntityAnimationStateMachine.AnimState.ATTACK);
        faceToward(targetPlayer.getLocation());
        // 实际伤害在 attack_hit 动画标记时结算（插件桥接调用 hitTarget()）
    }

    /**
     * 由 AnimationMarkerEvent(attack_hit) 桥接调用。
     *
     * @return 本次真正命中的玩家；目标无效、跨世界或命中盒没扫到时返回 null
     */
    public LivingEntity hitTarget() {
        LivingEntity victim = combatTarget != null ? combatTarget : target;
        if (victim == null || !victim.isValid() || victim.isDead()) return null;
        Location location = victim.getLocation();
        Location me = inst.location();
        if (me == null || location.getWorld() == null || !location.getWorld().equals(me.getWorld())) return null;
        if (!inHitbox(location)) return null;
        double damage = profile.attackDamage * scaledDamageFactor();
        victim.damage(damage, inst.entity());
        return victim;
    }

    private double scaledDamageFactor() {
        int level = inst.level > 0 ? inst.level : profile.level;
        return dev.helstera.ai.level.MobLevel.compute(level,
                profile.levels.toArray(new dev.helstera.ai.level.MobLevel.ScalingConfig[0]))
                .damage();
    }

    /**
     * 当前等级对应的移速缩放倍数；未配置等级系统时返回 1.0。
     */
    private double scaledSpeedFactor() {
        int level = inst.level > 0 ? inst.level : profile.level;
        return dev.helstera.ai.level.MobLevel.compute(level,
                profile.levels.toArray(new dev.helstera.ai.level.MobLevel.ScalingConfig[0]))
                .speed();
    }

    /**
     * 目标是否被当前姿态的骨骼命中盒扫到。
     *
     * <p>模型没配任何骨骼 {@code hitbox} 时退回以模型原点为心的球
     * （{@code attackRadius + 1}），这是旧行为：绝大多数模型不写骨骼盒，
     * 直接判否会让它们再也打不中人。</p>
     *
     * <p>骨骼世界坐标为空也退回球形：渲染层尚未写入过（未启用渲染）时
     * 拿不到骨骼位置，此时判否等于让整个模型失去攻击能力，
     * 比判定粗糙得多地失败要糟。</p>
     */
    private boolean inHitbox(Location t) {
        List<BoneHitbox.Box> boxes = hitBoxes();
        if (!boxes.isEmpty()) {
            return BoneHitbox.anyContains(boxes, t.getX(), BoneHitbox.bodyPoint(t), t.getZ());
        }
        Location me = inst.location();
        return me != null && t.distance(me) <= profile.attackRadius + 1.0;
    }

    /**
     * 当前姿态下的骨骼命中盒列表；模型未配骨骼盒时返回空列表。
     *
     * <p>每 Tick 至多调用一次（命中判定落在动画标记上，频率远低于决策节拍），
     * 因此这里直接构造而不缓存——缓存就得处理骨骼被增删与姿态失效，
     * 收益抵不过复杂度。</p>
     */
    private List<BoneHitbox.Box> hitBoxes() {
        Map<String, Location> world = inst.getBoneWorld();
        if (world == null || world.isEmpty()) return List.of();
        double scale = inst.getScale();
        List<BoneHitbox.Box> out = new ArrayList<>();
        for (var bone : inst.modelImpl().allBones()) {
            ModelHitbox hb = bone.boneHitbox();
            if (hb == null) continue;
            Location bp = world.get(bone.name());
            if (bp == null) continue;
            // 盒尺寸以模型单位给出，需按实例缩放换算到世界单位，
            // 与渲染层 Interaction 碰撞盒用同一套换算（见 DisplayRenderer#createVisuals）
            out.add(new BoneHitbox.Box(bone.name(), bp.getX(), bp.getY(), bp.getZ(),
                    hb.width() * scale, hb.height() * scale));
        }
        return out;
    }

    private void flee(Location loc) {
        LivingEntity fleeTarget = combatTarget != null ? combatTarget : target;
        if (validTarget(fleeTarget)) {
            Vector away = loc.toVector().subtract(fleeTarget.getLocation().toVector()).normalize().multiply(profile.moveSpeed);
            moveBy(away, loc);
        }
        if (anim != null) anim.enterLocomotion();
    }

    private void patrol(Location loc) {
        long now = System.currentTimeMillis();
        if (patrolTarget == null || now - lastPatrol > profile.patrolInterval * 1000L
                || (patrolTarget.distance(loc) < 0.8)) {
            lastPatrol = now;
            double ang = random.nextDouble() * Math.PI * 2;
            double r = random.nextDouble() * profile.patrolRadius;
            patrolTarget = home.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
        }
        moveToward(loc, patrolTarget);
    }

    /**
     * 朝目标移动：近距或无寻路服务时直连，远距时沿 A* 路径走。
     *
     * <p>直连作为兜底是刻意的：寻路失败（超预算、不可达、位置缺失）时若不直连，
     * 生物会<b>完全不动</b>，看起来像插件卡死。顶到墙根停下更符合直觉，
     * 也更容易被诊断——{@code /helstera nav} 会显示对应的失败计数。</p>
     */
    private void moveToward(Location from, Location to) {
        if (from == null || to == null) return;
        if (from.getWorld() == null || to.getWorld() == null) return;
        Location goal = followPath(from, to);
        Vector dir = goal.toVector().subtract(from.toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 0.001) return;
        dir.normalize().multiply(profile.moveSpeed);
        moveBy(dir, from);
    }

    /**
     * 求「下一个应前往的点」：有可用路点就用路点，否则回落到目标本身。
     *
     * <p>把「寻路 + 节流 + 卡死 + 侧移」全部收在这里，是为了让 {@link #moveToward}
     * 保持直线时代的形状——所有新行为在未启用寻路时完全不改变。</p>
     */
    private Location followPath(Location from, Location to) {
        var nav = navService;
        if (nav == null || !profile.canPathfind) return to;
        long now = System.currentTimeMillis();

        // 卡死检测先于寻路：卡住时先脱困，否则会反复算出一条同样到不了的路径
        PathFollower f = nav.follower(inst.instanceId());
        f.recordProgress(now, from.getX(), from.getZ());
        if (f.isStuck(now)) {
            nav.recordSidestep(inst.instanceId(), now);
            Location away = sidestepFrom(from);
            if (away != null) return away;
        }
        if (f.consumeSidestep()) {
            Location away = sidestepFrom(from);
            if (away != null) return away;
        }

        if (f.hasPath()) {
            f.advanceIfReached(from.getX(), from.getZ());
            NavNode wp = f.nextWaypoint();
            if (wp != null) {
                double[] p = pathPoint(wp);
                if (p != null) {
                    return new Location(from.getWorld(), p[0] + 0.5, from.getY(), p[1] + 0.5);
                }
            }
        }
        if (from.distanceSquared(to) > PROFILE_PATH_MIN_DIST_SQ) {
            nav.repath(inst.instanceId(), from, to, now, profile.pathBudget);
        }
        return to;
    }

    /** 路点 key 形如 "x,z[,y]"，取世界坐标。解析不出返回 null。 */
    private static double[] pathPoint(NavNode n) {
        String k = n.key();
        if (k == null) return null;
        String[] parts = k.split(",");
        if (parts.length < 2) return null;
        try {
            double x = Double.parseDouble(parts[0].trim());
            double z = Double.parseDouble(parts[1].trim());
            return new double[]{x, z};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 侧移：朝垂直于目标方向的随机侧前方挪一格，用来脱离墙角。 */
    private Location sidestepFrom(Location from) {
        Vector toTarget = null;
        LivingEntity currentTarget = combatTarget != null ? combatTarget : target;
        if (currentTarget != null && currentTarget.getLocation() != null) {
            toTarget = currentTarget.getLocation().toVector().subtract(from.toVector());
            toTarget.setY(0);
        }
        double base = toTarget == null || toTarget.lengthSquared() < 0.001
                ? random.nextDouble() * Math.PI * 2
                : Math.atan2(toTarget.getZ(), toTarget.getX());
        double ang = base + (random.nextBoolean() ? Math.PI / 2 : -Math.PI / 2);
        Location at = from.clone().add(Math.cos(ang) * 1.5, 0, Math.sin(ang) * 1.5);
        return at.getBlockY() < from.getWorld().getMinHeight() ? null : at;
    }

    /** 移动：非 Living 基座（盔甲架）用小幅传送，Living 用速度。 */
    private void moveBy(Vector velocity, Location from) {
        Entity base = inst.entity();
        if (base == null) return;
        if (base instanceof LivingEntity) {
            base.setVelocity(velocity);
        } else {
            Location next = from.clone().add(velocity);
            next.setYaw((float) (Math.atan2(-velocity.getX(), velocity.getZ()) * 180 / Math.PI));
            base.teleport(next);
        }
        if (anim != null) anim.setMoveSpeed(profile.moveSpeed * scaledSpeedFactor() * 20);
    }

    private void faceToward(Location to) {
        Entity base = inst.entity();
        if (base == null) return;
        Location l = base.getLocation().clone();
        Vector d = to.toVector().subtract(l.toVector());
        l.setYaw((float) (Math.atan2(-d.getX(), d.getZ()) * 180 / Math.PI));
        l.setPitch(0);
        base.teleport(l);
    }

    private void transition(State s) {
        if (state == s) return;
        State from = state;
        state = s;
        bus.post(new MobStateChangedEvent(inst, from.name(), s.name()));
    }
}
