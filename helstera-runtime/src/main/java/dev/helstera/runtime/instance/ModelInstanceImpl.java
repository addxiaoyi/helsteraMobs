package dev.helstera.runtime.instance;

import dev.helstera.api.Vec3;
import dev.helstera.api.animation.AnimationController;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.api.model.ModelDefinition;
import dev.helstera.core.model.ModelDefinitionImpl;
import dev.helstera.runtime.animation.AnimationControllerImpl;
import dev.helstera.runtime.animation.BonePose;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型实例实现。所有方法主线程；姿态数据由调度器每 Tick 采样。
 */
public final class ModelInstanceImpl implements ModelInstance {

    private final int instanceId;
    private final ModelDefinitionImpl model;
    private final InstanceRenderer renderer;
    private Entity baseEntity;
    private double scale;
    private float yaw;
    private float pitch;
    private AnimationControllerImpl animation;
    private final SpawnOptions options;
    private volatile boolean valid = true;
    private Location lastLocation;

    /** 当前采样姿态（boneName -> pose），由调度线程写、渲染线程读。 */
    private volatile Map<String, BonePose> pose = Map.of();

    /** 骨骼挂接物品：bone -> (point -> item)。 */
    public final Map<String, Map<String, ItemStack>> attachments = new ConcurrentHashMap<>();

    /** AI 行为控制器（helstera-ai 注入），可为 null。 */
    public volatile Object aiController;

    /** 死亡时投掷的掉落表名（mobs/*.yml 的 drops.table），无掉落时为 null。 */
    public volatile String lootTable;

/** 掉落是否计入击杀者的幸运值（mobs/*.yml 的 drops.luck），默认 true。 */
    public volatile boolean lootUsesLuck = true;

    /**
     * 当前等级；0 表示「未指定」，取 profile.level 的默认值。
     *
     * <p>由 HelsteraPlugin 在生成时从配置写入：mobs/*.yml 的 level 节，
     * 或 SpawnOptions 里的 level()。</p>
     */
    public volatile int level = 0;

    /** 实体动画状态机（AI 意图 -> 动画选择）。 */
    public volatile dev.helstera.runtime.animation.EntityAnimationStateMachine stateMachine;

    /** 供管理器初始化位置缓存。 */
    public void lastLocationSet(Location loc) {
        this.lastLocation = loc.clone();
    }

    public Location lastLocation() {
        return lastLocation;
    }

    /** 调度器同步落地状态到动画状态机。 */
    public void stateMachineGround(boolean onGround) {
        if (stateMachine != null) stateMachine.setOnGround(onGround);
    }

    /** 一次性状态标记：死亡后不再驱动动画。 */
    public volatile boolean dead;

    public ModelInstanceImpl(int instanceId, ModelDefinitionImpl model, InstanceRenderer renderer,
                             Entity baseEntity, SpawnOptions options) {
        this.instanceId = instanceId;
        this.model = model;
        this.renderer = renderer;
        this.baseEntity = baseEntity;
        this.options = options;
        this.scale = (options != null ? options.scale() : 1.0) * model.scale();
    }

    public void initAnimation(AnimationControllerImpl controller) {
        this.animation = controller;
    }

    @Override
    public int instanceId() {
        return instanceId;
    }

    @Override
    public ModelDefinition model() {
        return model;
    }

    public ModelDefinitionImpl modelImpl() {
        return model;
    }

    @Override
    public Optional<Entity> baseEntity() {
        return Optional.ofNullable(baseEntity);
    }

    @Override
    public Optional<UUID> boundEntityId() {
        return baseEntity == null ? Optional.empty() : Optional.of(baseEntity.getUniqueId());
    }

    public Entity entity() {
        return baseEntity;
    }

    public void setBaseEntity(Entity e) {
        this.baseEntity = e;
    }

    public SpawnOptions options() {
        return options;
    }

    @Override
    public Location location() {
        if (baseEntity != null && baseEntity.isValid()) {
            return baseEntity.getLocation();
        }
        return lastLocation;
    }

    @Override
    public void teleport(Location location) {
        if (baseEntity != null && baseEntity.isValid()) {
            baseEntity.teleport(location);
        }
        this.lastLocation = location.clone();
        renderer.updateLocation(this);
    }

    @Override
    public void setScale(double scale) {
        this.scale = scale * model.scale();
        renderer.updateTransforms(this);
    }

    @Override
    public double getScale() {
        return scale;
    }

    @Override
    public void setRotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    @Override
    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    @Override
    public AnimationController animation() {
        return animation;
    }

    public AnimationControllerImpl animationImpl() {
        return animation;
    }

    @Override
    public void attachItem(String bone, String point, ItemStack item) {
        Map<String, ItemStack> m = attachments.computeIfAbsent(bone, k -> new ConcurrentHashMap<>());
        String key = point == null ? "default" : point;
        if (item == null) m.remove(key);
        else m.put(key, item);
        renderer.updateAttachments(this);
    }

    @Override
    public void despawn() {
        if (!valid) return;
        valid = false;
        renderer.destroyVisuals(this);
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    public void setPose(Map<String, BonePose> pose) {
        this.pose = pose;
    }

    public Map<String, BonePose> getPose() {
        return pose;
    }

    /**
     * 骨骼名 -> 当前世界坐标（块单位）。
     *
     * <p>由渲染层在计算世界矩阵时顺带写入，AI 等非渲染模块读取。此前骨骼世界坐标
     * 只存在渲染器私有的 Visuals 里，模型作者在 JSON 里写的 {@code bone.hitbox}
     * 因而在运行期无人消费——命中判定退回成以模型原点为心的球。</p>
     *
     * <p>空 Map 表示渲染层尚未写入过（例如实例未启用渲染）：此时命中判定应退回
     * 球形近似，而不是判为「全部命中」。</p>
     */
    private volatile Map<String, Location> boneWorld = Map.of();

    /**
     * 写入骨骼世界坐标。调用方须交出该 Map 的所有权（之后不再修改）。
     *
     * <p>刻意不做 {@code Map.copyOf}：这是每 Tick 每实例都会走一次的路径，
     * 多一次全量拷贝在几十个实例同时活动时是可观的垃圾，而渲染层本来就在
     * 每 Tick 新建这个 Map，交出所有权不会带来别名问题。</p>
     */
    public void setBoneWorld(Map<String, Location> boneWorld) {
        this.boneWorld = boneWorld == null ? Map.of() : boneWorld;
    }

    public Map<String, Location> getBoneWorld() {
        return boneWorld;
    }

    /** 骨骼 pivot 查询（渲染层计算世界变换用）。 */
    public Vec3 bonePivot(String boneName) {
        var b = model.bone(boneName);
        return b == null ? Vec3.ZERO : b.pivot();
    }
}
