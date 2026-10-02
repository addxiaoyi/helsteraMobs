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
    /** 自定义条件/动作来源，透传给每个控制器；可为 null。 */
    private dev.helstera.api.behavior.BehaviorRegistry behaviors;
    /** 技能装载器：把 skills.yml 与 profile 中的文本定义绑定为可执行键；可为 null。 */
    private dev.helstera.ai.skill.SkillService skills;
    /** 实例 -> 实际绑定的档案（含 mobs/*.yml 局部覆盖）；事件触发器据此取档案。 */
    private final Map<Integer, AiProfile> boundProfiles = new ConcurrentHashMap<>();
    /** 受控实例，避免事件触发器反查时反复遍历全量实例。 */
    private final Map<Integer, ModelInstanceImpl> controlled = new ConcurrentHashMap<>();
    private final Map<String, AiProfile> profiles = new ConcurrentHashMap<>();
    private final Map<Integer, AiController> controllers = new ConcurrentHashMap<>();
    private BukkitTask task;

    public AiManager(Plugin plugin, InstanceManagerImpl instances, HelsteraEventBus bus) {
        this.plugin = plugin;
        this.instances = instances;
        this.bus = bus;
    }

    /** 注入自定义条件/动作注册表，使 profile 的 require / on-decision 生效。 */
    public void setBehaviors(dev.helstera.api.behavior.BehaviorRegistry behaviors) {
        this.behaviors = behaviors;
    }

    /** 注入技能装载器，用于把 profile 的 skills 列表展开为已绑定的条件/动作。 */
    public void setSkills(dev.helstera.ai.skill.SkillService skills) {
        this.skills = skills;
    }

    public void loadProfiles(org.bukkit.configuration.ConfigurationSection root) {
        profiles.clear();
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            var sec = root.getConfigurationSection(key);
            AiProfile p = AiProfile.fromSection(key, sec);
            // 先把 skills 引用展开，再把 require/on-decision 的文本定义绑定成可执行键。
            // 顺序重要：技能内部的条件/动作必须先完成绑定，展开时才能按名引用。
            if (skills != null && sec != null) {
                skills.expand(p, sec.getStringList("skills"));
            }
            p.require.replaceAll(s -> {
                String bound = skills == null ? null : skills.bindCondition(s);
                return bound == null ? s : bound;
            });
            p.onDecision.replaceAll(s -> {
                String bound = skills == null ? null : skills.bindAction(s);
                return bound == null ? s : bound;
            });
            if (skills != null) skills.expandTriggers(p);
            profiles.put(key, p);
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
        AiProfile p = profile == null ? profile("default") : profile;
        AiController c = new AiController(inst, p, anim, bus, behaviors);
        inst.aiController = c;
        controllers.put(inst.instanceId(), c);
        boundProfiles.put(inst.instanceId(), p);
        controlled.put(inst.instanceId(), inst);
    }

    public AiController controllerOf(ModelInstance inst) {
        return controllers.get(inst.instanceId());
    }

    /** 取某实例实际绑定的档案（含 mobs/*.yml 局部覆盖）；未启用 AI 时返回 null。 */
    public AiProfile profileOf(int instanceId) {
        return boundProfiles.get(instanceId);
    }

    /** 全部受控实例，供事件触发器按实体反查。O(n) 拷贝，无嵌套遍历。 */
    public java.util.Collection<ModelInstanceImpl> allInstances() {
        return java.util.List.copyOf(controlled.values());
    }

    public void detach(int instanceId) {
        controllers.remove(instanceId);
        boundProfiles.remove(instanceId);
        controlled.remove(instanceId);
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
        boundProfiles.clear();
        controlled.clear();
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
