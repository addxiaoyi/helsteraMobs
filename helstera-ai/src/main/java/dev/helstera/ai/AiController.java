package dev.helstera.ai;

import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.MobStateChangedEvent;
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
                        System::currentTimeMillis, id -> Bukkit.getPlayer(id))
                : null;
        this.home = inst.location() != null ? inst.location().clone() : null;
    }

    public State state() {
        return state;
    }

    public Player target() {
        return target;
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
        if (attacker instanceof Player p && profile.canChase) {
            if (threat != null) {
                Location loc = inst.location();
                threat.addThreat(p.getUniqueId(), damage, loc == null ? 0 : safeDistance(loc, p));
                target = p;
            } else {
                target = p;
            }
        }
        if (anim != null) anim.intent(EntityAnimationStateMachine.AnimState.HURT);
    }

    /** 供 {@code threat} 动作直接加仇恨（对应 MythicMobs 的 threat mechanic）。 */
    public void addThreat(Player player, double amount) {
        if (threat != null && player != null) threat.addThreat(player.getUniqueId(), amount);
    }

    /** 当前仇恨表；未启用时为 null。 */
    public ThreatTable threatTable() {
        return threat;
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
        Player sensed = sense(loc);

        decisions++;
        dev.helstera.api.behavior.BehaviorContext ctx = behaviors == null ? null
                : dev.helstera.api.behavior.BehaviorContext.of(
                        inst, target != null ? target : sensed, healthRatio,
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
    private void decide(Location loc, Player sensed, double healthRatio) {
        // 决策（优先级从高到低）
        if (state == State.DEAD) return;
        if (profile.canFlee && healthRatio <= profile.fleeHealthRatio) {
            transition(State.FLEE);
            flee(loc);
            return;
        }

        // 目标来源：启用仇恨表时由威胁值决定，否则退回「最近可见者」。
        // 两者不能混用——sense() 每 tick 都可能把仇恨最低的人重新吸成目标，
        // 那等于仇恨表白建。
        Player foe = threatTarget(loc) != null ? threatTarget(loc) : sensed;

        if (foe != null) {
            if (!foe.isOnline() || foe.isDead()) {
                // 主目标失效：尝试切到仇恨次高的目标，而不是退回最近者
                Player alt = threatTarget(loc);
                if (alt != null && alt != foe) {
                    foe = alt;
                } else {
                    foe = sensed;
                }
            }
        }

        if (foe != null) {
            double dist = safeDistance(loc, foe);
            if (profile.canAttack && dist >= 0 && dist <= profile.attackRadius) {
                transition(State.ATTACK);
                attack(foe);
                return;
            }
            if (profile.canChase) {
                transition(State.CHASE);
                target = foe;
                moveToward(loc, foe.getLocation());
                return;
            }
        }
        // 无目标：巡逻/待机
        if (profile.canPatrol) {
            transition(State.PATROL);
            patrol(loc);
        } else {
            transition(State.IDLE);
        }
    }

    /** 按仇恨选出当前目标；未启用仇恨表或无有效仇恨时返回 null。 */
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
    private Player sense(Location loc) {
        Player best = null;
        double bestD = profile.sightRadius;
        if (loc.getWorld() == null) return null;
        Entity self = inst.entity();
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || p.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
            if (dev.helstera.api.behavior.Factions.allied(self, p)) continue;
            Location pl = p.getLocation();
            if (!pl.getWorld().equals(loc.getWorld())) continue;
            double d = pl.distance(loc);
            if (d > bestD) continue;
            // 方块阻挡感知
            var ray = loc.getWorld().rayTraceBlocks(
                    loc.clone().add(0, 1.5, 0),
                    pl.clone().add(0, 1.2, 0).subtract(loc.clone().add(0, 1.5, 0)).toVector(),
                    bestD, org.bukkit.FluidCollisionMode.NEVER);
            if (ray != null && ray.getHitBlock() != null) continue;
            best = p;
            bestD = d;
        }
        return best;
    }

    private void attack(Player targetPlayer) {
        this.target = targetPlayer;
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
    public Player hitTarget() {
        if (target == null || !target.isOnline() || target.isDead()) return null;
        Location t = target.getLocation();
        Location me = inst.location();
        if (me == null || t.getWorld() == null || !t.getWorld().equals(me.getWorld())) return null;
        if (!inHitbox(t)) return null;
        target.damage(profile.attackDamage, inst.entity());
        return target;
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
        if (target != null && target.isOnline()) {
            Vector away = loc.toVector().subtract(target.getLocation().toVector()).normalize().multiply(profile.moveSpeed);
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
        if (target != null && target.getLocation() != null) {
            toTarget = target.getLocation().toVector().subtract(from.toVector());
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
        if (anim != null) anim.setMoveSpeed(profile.moveSpeed * 20);
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
