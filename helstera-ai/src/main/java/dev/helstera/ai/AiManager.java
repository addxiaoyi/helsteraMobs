package dev.helstera.ai;

import dev.helstera.api.event.AnimationMarkerEvent;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 管理器：统一节拍驱动全部 AiController（无独立每实例任务），
 * 桥接伤害事件（HURT 意图 / 死亡处理 / attack_hit 结算）。
 */
public final class AiManager implements Listener {

    private final Plugin plugin;
    private final InstanceManagerImpl instances;
    private final HelsteraEventBus bus;
    private final Map<String, AiProfile> profiles = new ConcurrentHashMap<>();
    private final Map<Integer, AiController> controllers = new ConcurrentHashMap<>();
    private BukkitTask task;

    public AiManager(Plugin plugin, InstanceManagerImpl instances, HelsteraEventBus bus) {
        this.plugin = plugin;
        this.instances = instances;
        this.bus = bus;
    }

    public void loadProfiles(org.bukkit.configuration.ConfigurationSection root) {
        profiles.clear();
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            profiles.put(key, AiProfile.fromSection(key, root.getConfigurationSection(key)));
        }
    }

    public AiProfile profile(String name) {
        return profiles.computeIfAbsent(name == null ? "default" : name, k -> new AiProfile(k));
    }

    /** 实例启用 AI。 */
    public void attach(ModelInstanceImpl inst, String profileName) {
        attach(inst, profile(profileName));
    }

    /** 用指定档案（可来自 mobs/*.yml 的 ai 节覆盖）启用 AI。 */
    public void attach(ModelInstanceImpl inst, AiProfile profile) {
        var anim = inst.stateMachine;
        if (anim == null) return;
        AiController c = new AiController(inst, profile == null ? profile("default") : profile, anim, bus);
        inst.aiController = c;
        controllers.put(inst.instanceId(), c);
    }

    public AiController controllerOf(ModelInstance inst) {
        return controllers.get(inst.instanceId());
    }

    public void detach(int instanceId) {
        controllers.remove(instanceId);
    }

    public void start(int decisionTicks) {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (AiController c : controllers.values()) {
                try {
                    c.tick();
                } catch (Throwable ignored) {
                }
            }
        }, decisionTicks, Math.max(2, decisionTicks));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // attack_hit 动画标记 -> 结算伤害
        bus.register(AnimationMarkerEvent.class, e -> {
            if (!"attack_hit".equals(e.marker())) return;
            AiController c = controllers.get(e.instance().instanceId());
            if (c != null) c.hitTarget();
        });
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        HandlerList.unregisterAll(this);
        controllers.clear();
    }

    public int activeCount() {
        return controllers.size();
    }

    // ---- 伤害桥接 ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        ModelInstanceImpl inst = findByEntity(e.getEntity());
        if (inst != null) {
            AiController c = controllers.get(inst.instanceId());
            if (c != null) c.onDamaged(e.getDamager());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        ModelInstanceImpl inst = findByEntity(e.getEntity());
        if (inst == null) return;
        AiController c = controllers.get(inst.instanceId());
        if (c != null && c.state() != AiController.State.DEAD) {
            c.markDead();
            // 死亡动画后清理（延迟 2.5s）
            Bukkit.getScheduler().runTaskLater(plugin, () -> instances.despawn(inst.instanceId()), 50L);
        }
    }

    private ModelInstanceImpl findByEntity(Entity e) {
        for (ModelInstanceImpl inst : instances.allImpl()) {
            if (e.getUniqueId().equals(inst.boundEntityId().orElse(null))) return inst;
        }
        return null;
    }
}
