package dev.helstera.ai;

import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.MobStateChangedEvent;
import dev.helstera.runtime.animation.EntityAnimationStateMachine;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * 有限状态机 AI 控制器（每实例一个，由 AiManager 统一节拍驱动，不建独立任务）。
 * 状态：IDLE / PATROL / CHASE / ATTACK / HURT / FLEE / DEAD。
 * 感知器：视线（rayTrace）、半径、伤害事件、血量。
 * 决策只产生动作意图（写动画状态机 + 移动/攻击动作）。
 */
public final class AiController {

    public enum State {IDLE, PATROL, CHASE, ATTACK, HURT, FLEE, DEAD}

    private final ModelInstanceImpl inst;
    private final AiProfile profile;
    private final EntityAnimationStateMachine anim;
    private final HelsteraEventBus bus;

    private State state = State.IDLE;
    private Player target;
    private long lastAttack;
    private long lastPatrol;
    private Location patrolTarget;
    private Location home;
    private final Random random = new Random();

    public AiController(ModelInstanceImpl inst, AiProfile profile,
                        EntityAnimationStateMachine anim, HelsteraEventBus bus) {
        this.inst = inst;
        this.profile = profile;
        this.anim = anim;
        this.bus = bus;
        this.home = inst.location() != null ? inst.location().clone() : null;
    }

    public State state() {
        return state;
    }

    public Player target() {
        return target;
    }

    public void onDamaged(org.bukkit.entity.Entity attacker) {
        transition(State.HURT);
        if (attacker instanceof Player p && profile.canChase) {
            target = p;
        }
        if (anim != null) anim.intent(EntityAnimationStateMachine.AnimState.HURT);
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

        // 决策（优先级从高到低）
        if (state == State.DEAD) return;
        if (profile.canFlee && healthRatio <= profile.fleeHealthRatio) {
            transition(State.FLEE);
            flee(loc);
            return;
        }
        if (sensed != null) {
            double dist = sensed.getLocation().distance(loc);
            if (profile.canAttack && dist <= profile.attackRadius) {
                transition(State.ATTACK);
                attack(sensed);
                return;
            }
            if (profile.canChase) {
                transition(State.CHASE);
                target = sensed;
                moveToward(loc, sensed.getLocation());
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

    /** 视线感知器：半径内最近玩家，且方块射线不被阻挡。 */
    private Player sense(Location loc) {
        Player best = null;
        double bestD = profile.sightRadius;
        if (loc.getWorld() == null) return null;
        for (Player p : loc.getWorld().getPlayers()) {
            if (p.isDead() || p.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || p.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
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

    /** 由 AnimationMarkerEvent(attack_hit) 桥接调用。 */
    public void hitTarget() {
        if (target != null && target.isOnline() && !target.isDead()) {
            Location t = target.getLocation();
            Location me = inst.location();
            if (me != null && t.getWorld() == me.getWorld() && t.distance(me) <= profile.attackRadius + 1.0) {
                target.damage(profile.attackDamage, inst.entity());
            }
        }
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

    private void moveToward(Location from, Location to) {
        Vector dir = to.toVector().subtract(from.toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 0.001) return;
        dir.normalize().multiply(profile.moveSpeed);
        moveBy(dir, from);
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
