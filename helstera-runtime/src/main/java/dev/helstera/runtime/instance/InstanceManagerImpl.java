package dev.helstera.runtime.instance;

import dev.helstera.api.animation.AnimationOptions;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.ModelRemoveEvent;
import dev.helstera.api.event.ModelSpawnEvent;
import dev.helstera.api.instance.InstanceManager;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.api.model.ModelDefinition;
import dev.helstera.api.model.ModelHitbox;
import dev.helstera.api.model.ModelRegistry;
import dev.helstera.core.model.ModelDefinitionImpl;
import dev.helstera.runtime.animation.AnimationControllerImpl;
import dev.helstera.runtime.animation.EntityAnimationStateMachine;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实例管理器实现。
 */
public final class InstanceManagerImpl implements InstanceManager {

    private final ModelRegistry registry;
    private final HelsteraEventBus bus;
    private final InstanceRenderer renderer;
    private final Map<Integer, ModelInstanceImpl> instances = new ConcurrentHashMap<>();
    private final AtomicInteger idGen = new AtomicInteger(1);
    private final ConfigurationSection animMappingCfg;

    public InstanceManagerImpl(ModelRegistry registry, HelsteraEventBus bus,
                               InstanceRenderer renderer, ConfigurationSection animMappingCfg) {
        this.registry = registry;
        this.bus = bus;
        this.renderer = renderer;
        this.animMappingCfg = animMappingCfg;
    }

    @Override
    public ModelInstance spawn(String modelId, Location location, SpawnOptions options) {
        ModelDefinition def = requireModel(modelId);
        ModelInstanceImpl inst = createInstance((ModelDefinitionImpl) def, null, options);
        Location loc = location.clone();
        inst.lastLocationSet(loc);
        renderer.createVisuals(inst, loc, options);
        renderer.updateTransforms(inst);
        // 不在这里 post ModelSpawnEvent：spawnMobCore 会在 ai().attach() 之后统一派发，
        // 确保 boundProfiles 已经绑定。此处只负责创建实例和视觉效果。
        return inst;
    }

    @Override
    public ModelInstance bind(String modelId, Entity entity, SpawnOptions options) {
        ModelDefinition def = requireModel(modelId);
        ModelInstanceImpl inst = createInstance((ModelDefinitionImpl) def, entity, options);
        inst.lastLocationSet(entity.getLocation());
        renderer.createVisuals(inst, entity.getLocation(), options);
        renderer.updateTransforms(inst);
        // 同上：不在这里 post ModelSpawnEvent
        return inst;
    }

    private ModelDefinition requireModel(String modelId) {
        return registry.get(modelId).orElseThrow(() ->
                new IllegalArgumentException("模型未加载: " + modelId + "（用 /helstera model list 查看已加载模型）"));
    }

    private ModelInstanceImpl createInstance(ModelDefinitionImpl def, Entity base, SpawnOptions options) {
        SpawnOptions opts = options == null ? SpawnOptions.defaults() : options;
        ModelInstanceImpl inst = new ModelInstanceImpl(idGen.getAndIncrement(), def, renderer, base, opts);
        AnimationControllerImpl controller = new AnimationControllerImpl(inst, def, bus);
        inst.initAnimation(controller);
        inst.stateMachine = new EntityAnimationStateMachine(def, controller, animMappingCfg);
        instances.put(inst.instanceId(), inst);
        // 默认播放待机动画
        if (def.defaultAnimation() != null && controller.hasAnimation(def.defaultAnimation())) {
            controller.play(def.defaultAnimation(), AnimationOptions.defaults().loop(true));
        } else if (controller.hasAnimation("idle")) {
            controller.play("idle", AnimationOptions.defaults().loop(true));
        }
        return inst;
    }

    @Override
    public Optional<ModelInstance> getInstance(int instanceId) {
        ModelInstanceImpl i = instances.get(instanceId);
        return i == null ? Optional.empty() : Optional.of(i);
    }

    @Override
    public Collection<ModelInstance> allInstances() {
        return java.util.Collections.unmodifiableCollection(new java.util.ArrayList<>(instances.values()));
    }

    public Collection<ModelInstanceImpl> allImpl() {
        return java.util.Collections.unmodifiableCollection(new java.util.ArrayList<>(instances.values()));
    }

    public ModelInstanceImpl impl(int id) {
        return instances.get(id);
    }

    public InstanceRenderer renderer() {
        return renderer;
    }

    @Override
    public int activeCount() {
        return instances.size();
    }

    @Override
    public boolean despawn(int instanceId) {
        ModelInstanceImpl inst = instances.remove(instanceId);
        if (inst == null) return false;
        inst.despawn();
        bus.post(new ModelRemoveEvent(inst, "manual"));
        return true;
    }

    @Override
    public int despawnAll(String modelId) {
        int n = 0;
        for (Iterator<Map.Entry<Integer, ModelInstanceImpl>> it = instances.entrySet().iterator(); it.hasNext(); ) {
            ModelInstanceImpl inst = it.next().getValue();
            if (inst.model().id().equalsIgnoreCase(modelId)) {
                it.remove();
                inst.despawn();
                bus.post(new ModelRemoveEvent(inst, "model_unload"));
                n++;
            }
        }
        return n;
    }

    @Override
    public void despawnWorld(World world) {
        for (Iterator<Map.Entry<Integer, ModelInstanceImpl>> it = instances.entrySet().iterator(); it.hasNext(); ) {
            ModelInstanceImpl inst = it.next().getValue();
            Location l = inst.location();
            if (l != null && world.equals(l.getWorld())) {
                it.remove();
                inst.despawn();
                bus.post(new ModelRemoveEvent(inst, "world_unload"));
            }
        }
    }

    @Override
    public ModelHitbox effectiveHitbox(String modelId, double scaleOverride) {
        ModelDefinition def = registry.get(modelId).orElseThrow(() ->
                new IllegalArgumentException("模型未加载: " + modelId));
        double s = scaleOverride > 0 ? scaleOverride : def.scale();
        return new ModelHitbox(def.hitbox().width() * s, def.hitbox().height() * s);
    }

    /** 清理无效实例（基础实体死亡/被移除）。由调度器调用。 */
    public void cleanupInvalid() {
        for (Iterator<Map.Entry<Integer, ModelInstanceImpl>> it = instances.entrySet().iterator(); it.hasNext(); ) {
            ModelInstanceImpl inst = it.next().getValue();
            boolean baseGone = inst.entity() != null && !inst.entity().isValid();
            if (!inst.isValid() || baseGone) {
                it.remove();
                if (inst.isValid()) inst.despawn();
                bus.post(new ModelRemoveEvent(inst, baseGone ? "base_entity_removed" : "invalid"));
            }
        }
    }
}
