package dev.helstera.ai.skill;

import dev.helstera.ai.AiManager;
import dev.helstera.ai.AiProfile;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.BehaviorRegistry;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.MobStateChangedEvent;
import dev.helstera.api.event.ModelRemoveEvent;
import dev.helstera.api.event.ModelSpawnEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * 事件触发器分发：把 profile 的 triggers 配置接到真实事件上。
 *
 * <p>事件名：on-spawn / on-damage / on-death / on-remove / on-state。
 * 每个触发器先求值 require（全部为 true），再执行 do 中的动作。
 * 触发器异常一律吞掉并记录，不允许影响实体与事件链。</p>
 *
 * <p>线程约束：全部回调在主线程（Bukkit 事件与事件总线均由主线程派发）。</p>
 */
public final class SkillTriggers implements Listener {

    private final Plugin plugin;
    private final AiManager ai;
    private final BehaviorRegistry registry;
    private final HelsteraEventBus bus;
    private final Logger log;
    private final java.util.concurrent.atomic.AtomicLong fired =
            new java.util.concurrent.atomic.AtomicLong();
    /** 总线订阅句柄；持有引用以便 stop() 精确注销。 */
    private java.util.function.Consumer<ModelSpawnEvent> onSpawn;
    private java.util.function.Consumer<ModelRemoveEvent> onRemove;
    private java.util.function.Consumer<MobStateChangedEvent> onState;

    public SkillTriggers(Plugin plugin, AiManager ai, BehaviorRegistry registry,
                         HelsteraEventBus bus, Logger log) {
        this.plugin = plugin;
        this.ai = ai;
        this.registry = registry;
        this.bus = bus;
        this.log = log;
    }

    /**
     * 启动监听。三个总线订阅保存引用，以便 stop() 精确注销——
     * 匿名 lambda 若不持有引用，重载后会残留在总线上并持有已失效的 AiManager。
     */
    public void start() {
        if (onSpawn != null) return; // 防重复注册
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        onSpawn = e -> dispatch("on-spawn", e.instance(), null, e.instance().location());
        onRemove = e -> dispatch("on-remove", e.instance(), null, e.instance().location());
        onState = e -> {
            // on-state 只在进入 DEAD 时触发一次，避免每次状态抖动都播死亡动作
            if ("DEAD".equals(e.toState())) dispatch("on-state", e.instance(), null, e.instance().location());
        };
        bus.register(ModelSpawnEvent.class, onSpawn);
        bus.register(ModelRemoveEvent.class, onRemove);
        bus.register(MobStateChangedEvent.class, onState);
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        if (onSpawn != null) bus.unregister(ModelSpawnEvent.class, onSpawn);
        if (onRemove != null) bus.unregister(ModelRemoveEvent.class, onRemove);
        if (onState != null) bus.unregister(MobStateChangedEvent.class, onState);
        onSpawn = null;
        onRemove = null;
        onState = null;
    }

    /** 累计触发次数，供 /helstera debug 展示。 */
    public long firedCount() {
        return fired.get();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        var inst = find(e.getEntity());
        if (inst == null) return;
        Player attacker = e.getDamager() instanceof Player p ? p : null;
        dispatch("on-damage", inst, attacker, e.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        var inst = find(e.getEntity());
        if (inst == null) return;
        dispatch("on-death", inst, e.getEntity().getKiller(), e.getEntity().getLocation());
    }

    private dev.helstera.runtime.instance.ModelInstanceImpl find(Entity e) {
        for (var inst : ai.allInstances()) {
            if (e.getUniqueId().equals(inst.boundEntityId().orElse(null))) return inst;
        }
        return null;
    }

    /** 求值并执行触发器。context 缺失或实例已失效时安全返回。 */
    private void dispatch(String event, dev.helstera.api.instance.ModelInstance instance,
                          Player target, org.bukkit.Location loc) {
        if (instance == null || !instance.isValid()) return;
        AiProfile profile = ai.profileOf(instance.instanceId());
        if (profile == null) return;
        AiProfile.TriggerSpec spec = profile.triggers.get(event);
        if (spec == null || spec.actions.isEmpty()) return;

        double healthRatio = 1.0;
        double distance = -1;
        if (instance.baseEntity().orElse(null) instanceof LivingEntity le && le.getMaxHealth() > 0) {
            healthRatio = le.getHealth() / le.getMaxHealth();
        }
        if (target != null && loc != null && target.getWorld() != null
                && target.getWorld().equals(loc.getWorld())) {
            distance = target.getLocation().distance(loc);
        }
        BehaviorContext ctx = BehaviorContext.of(instance, target, healthRatio, distance,
                0, event);

        for (String cond : spec.require) {
            if (!registry.testCondition(cond, ctx)) return;
        }
        for (String act : spec.actions) {
            try {
                registry.runAction(act, ctx);
            } catch (Throwable t) {
                warn("触发器 " + event + " 动作 \"" + act + "\" 失败: " + t);
            }
        }
        fired.incrementAndGet();
    }

    private void warn(String msg) {
        if (log != null) log.warning("[技能] " + msg);
    }
}
