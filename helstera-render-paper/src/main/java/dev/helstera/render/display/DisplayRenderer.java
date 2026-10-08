package dev.helstera.render.display;

import dev.helstera.api.Vec3;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.core.model.ModelDefinitionImpl;
import dev.helstera.runtime.animation.BonePose;
import dev.helstera.runtime.instance.InstanceRenderer;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Display 实体渲染器（方案 1：资源包 + 显示实体）。
 * 每骨骼一个 ItemDisplay：位置=骨骼 pivot 世界坐标，变换矩阵=骨骼链线性部分。
 * 碰撞盒：Interaction 实体（点击/投射物命中）。
 * 降级外观：资源包未加载的玩家改为看到隐形载体的原版外观。
 */
public final class DisplayRenderer implements InstanceRenderer {

    /** 单实例的全部显示实体。 */
    static final class Visuals {
        ArmorStand carrier;
        final List<ItemDisplay> boneDisplays = new ArrayList<>();
        Interaction hitbox;
        TextDisplay nameTag;
        final List<UUID> allEntityIds = new ArrayList<>();
        /** 与骨骼顺序一一对应的世界矩阵（块单位，含 yaw）。 */
        final List<Matrix4f> worldMatrices = new ArrayList<>();
        final List<Location> bonePositions = new ArrayList<>();
    }

    private final Plugin plugin;
    private final BoneCommandMapping cmdMapping;
    private final Map<Integer, Visuals> visuals = new ConcurrentHashMap<>();
    /** 实体 UUID -> 实例 ID（事件路由）。 */
    private final Map<UUID, Integer> entityIndex = new ConcurrentHashMap<>();
    private int interpolationTicks = 1;

    public DisplayRenderer(Plugin plugin, BoneCommandMapping cmdMapping) {
        this.plugin = plugin;
        this.cmdMapping = cmdMapping;
    }

    public void setInterpolationTicks(int t) {
        this.interpolationTicks = Math.max(1, t);
    }

    public Integer instanceOf(Entity entity) {
        return entityIndex.get(entity.getUniqueId());
    }

    public Visuals visualsOf(ModelInstanceImpl inst) {
        return visuals.get(inst.instanceId());
    }

    /**
     * 该实例当前持有的渲染实体总数（载体 + 骨骼 Display + 标签 + 交互体）。
     * 供启动自检等包外调用方做只读诊断。
     */
    public int renderedEntityCount(int instanceId) {
        Visuals v = visuals.get(instanceId);
        return v == null ? 0 : v.allEntityIds.size();
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------

    @Override
    public void createVisuals(ModelInstanceImpl inst, Location location, SpawnOptions options) {
        Visuals v = new Visuals();
        World world = location.getWorld();
        ModelDefinitionImpl model = inst.modelImpl();

        // 隐形载体（位置锚点 + 可攻击碰撞载体）
        // 仅在“纯展示”模型（未绑定真实实体）时创建；绑定实体时以该实体为锚点，避免多出冗余实体。
        if (inst.baseEntity().isEmpty()) {
            ArmorStand carrier = world.spawn(location, ArmorStand.class, as -> {
                as.setInvisible(true);
                as.setMarker(false); // 非 marker：保留可攻击的实体碰撞盒（整体碰撞盒方案）
                as.setSmall(true);
                as.setGravity(false);
                as.setPersistent(options.persistent());
                as.setCustomNameVisible(false);
                as.setCanPickupItems(false);
                as.getPersistentDataContainer().set(key(), PersistentDataType.INTEGER, inst.instanceId());
            });
            v.carrier = carrier;
            v.allEntityIds.add(carrier.getUniqueId());
            entityIndex.put(carrier.getUniqueId(), inst.instanceId());
        }

        // 每骨骼一个 ItemDisplay
        for (var bone : model.allBones()) {
            Location boneLoc = location.clone();
            int cmd = cmdMapping.commandData(model.id(), bone.name());
            ItemStack item = cmdMapping.itemFor(model.id(), bone.name(), cmd);
            ItemDisplay disp = world.spawn(boneLoc, ItemDisplay.class, d -> {
                d.setItemStack(item);
                d.setTransformation(new Transformation(
                        new org.joml.Vector3f(), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
                d.setInterpolationDelay(-1);
                d.setInterpolationDuration(interpolationTicks);
                d.setTeleportDuration(interpolationTicks);
                // 能力探测：setViewRange / setShadowRadius 在部分版本不存在，
                // 缺失时降级为默认值（范围 1f、无阴影），不影响骨骼显示。
                if (VersionAdapter.canSetViewRange()) {
                    d.setViewRange(1.0f);
                }
                if (VersionAdapter.canSetShadowRadius()) {
                    d.setShadowRadius(0f);
                }
                d.setPersistent(options.persistent());
                d.setGlowing(options.glowing());
                d.setBillboard(Display.Billboard.FIXED);
                d.getPersistentDataContainer().set(key(), PersistentDataType.INTEGER, inst.instanceId());
            });
            v.boneDisplays.add(disp);
            v.allEntityIds.add(disp.getUniqueId());
            entityIndex.put(disp.getUniqueId(), inst.instanceId());
        }

        // 碰撞盒（Interaction：点击/投射物）
        if (options.spawnHitbox()) {
            var hb = model.hitbox();
            double s = inst.getScale();
            Interaction interaction = world.spawn(location, Interaction.class, i -> {
                i.setInteractionWidth((float) Math.max(0.1, hb.width() * s));
                i.setInteractionHeight((float) Math.max(0.1, hb.height() * s));
                i.setResponsive(true);
                i.setPersistent(options.persistent());
                i.getPersistentDataContainer().set(key(), PersistentDataType.INTEGER, inst.instanceId());
            });
            v.hitbox = interaction;
            v.allEntityIds.add(interaction.getUniqueId());
            entityIndex.put(interaction.getUniqueId(), inst.instanceId());
        }

        // 名称牌
        if (options.showName() && options.displayName() != null) {
            TextDisplay tag = world.spawn(location, TextDisplay.class, t -> {
                t.text(LegacyText.parse(options.displayName()));
                t.setBillboard(Display.Billboard.CENTER);
                t.setPersistent(options.persistent());
                t.setShadowed(true);
            });
            v.nameTag = tag;
            v.allEntityIds.add(tag.getUniqueId());
        }

        visuals.put(inst.instanceId(), v);
    }

    private org.bukkit.NamespacedKey key() {
        return new org.bukkit.NamespacedKey(plugin, "instance_id");
    }

    // ------------------------------------------------------------------
    // 姿态应用
    // ------------------------------------------------------------------

    @Override
    public void updateTransforms(ModelInstanceImpl inst) {
        Visuals v = visuals.get(inst.instanceId());
        if (v == null || !inst.isValid()) return;
        Location base = inst.location();
        if (base == null || base.getWorld() == null) return;
        computeWorldMatrices(inst, base, v);

        List<ItemDisplay> displays = v.boneDisplays;
        boolean async = VersionAdapter.canTeleportAsync();
        boolean matrix = VersionAdapter.canSetTransformationMatrix();
        for (int i = 0; i < displays.size() && i < v.worldMatrices.size(); i++) {
            ItemDisplay d = displays.get(i);
            if (!d.isValid()) continue;
            Location p = v.bonePositions.get(i);
            // teleportAsync 在旧版本不存在；缺失时同步传送，行为一致但阻塞主线程更久。
            if (async) {
                d.teleportAsync(p);
            } else {
                d.teleport(p);
            }
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(interpolationTicks);
            if (matrix) {
                d.setTransformationMatrix(v.worldMatrices.get(i));
            }
        }

        // 碰撞盒跟随
        if (v.hitbox != null && v.hitbox.isValid()) {
            Location hbLoc = base.clone();
            hbLoc.setYaw(0);
            hbLoc.setPitch(0);
            v.hitbox.teleport(hbLoc);
        }
        // 名称牌跟随（头顶）
        if (v.nameTag != null && v.nameTag.isValid()) {
            var hb = inst.modelImpl().hitbox();
            Location tagLoc = base.clone().add(0, hb.height() * inst.getScale() + 0.3, 0);
            v.nameTag.teleport(tagLoc);
        }
    }

    /**
     * 计算每骨骼世界矩阵：
     * W_bone = R_yaw · W_parent · T(pivot)·R(anim)·S(anim)·T(-pivot)
     * 实体位置 = basePos + (1/16)·scale · (W_bone · pivot)
     * 实体矩阵 = (1/16)·scale · lin(W_bone)（相对实体位置的线性部分）
     */
    private void computeWorldMatrices(ModelInstanceImpl inst, Location base, Visuals v) {
        v.worldMatrices.clear();
        v.bonePositions.clear();
        double globalScale = inst.getScale() / 16.0;
        float yawRad = -base.getYaw() * ((float) Math.PI / 180f);
        Matrix4f baseRot = new Matrix4f().rotationY(yawRad);
        Map<String, BonePose> pose = inst.getPose();
        // 同步一份骨骼世界坐标到实例：命中判定在 AI 模块，而这里恰好是唯一算出
        // 骨骼世界坐标的地方。顺带写入而非让 AI 重算矩阵，避免两份实现漂移。
        Map<String, Location> boneWorld = new HashMap<>();

        // 迭代（骨骼已保证父在前）
        Map<String, Matrix4f> worldByBone = new HashMap<>();
        for (var bone : inst.modelImpl().allBones()) {
            Matrix4f w;
            if (bone.parent() == null) {
                w = new Matrix4f(baseRot);
            } else {
                Matrix4f parent = worldByBone.get(bone.parent());
                w = parent == null ? new Matrix4f(baseRot) : new Matrix4f(parent);
            }
            // 局部：T(p)·R·S·T(-p)
            Vec3 pivot = bone.pivot();
            BonePose bp = pose.getOrDefault(bone.name(), BonePose.ZERO);
            w.translate(new Vector3f((float) pivot.x(), (float) pivot.y(), (float) pivot.z()));
            w.rotateXYZ((float) Math.toRadians(bp.rotation().x()),
                    (float) Math.toRadians(bp.rotation().y()),
                    (float) Math.toRadians(bp.rotation().z()));
            float sx = 1f + (float) bp.scale().x();
            float sy = 1f + (float) bp.scale().y();
            float sz = 1f + (float) bp.scale().z();
            w.scale(new Vector3f(Math.max(sx, 0.01f), Math.max(sy, 0.01f), Math.max(sz, 0.01f)));
            w.translate(new Vector3f((float) -pivot.x(), (float) -pivot.y(), (float) -pivot.z()));
            worldByBone.put(bone.name(), w);

            // 实体位置：W · pivot
            Vector3f pv = new Vector3f((float) pivot.x(), (float) pivot.y(), (float) pivot.z());
            w.transformPosition(pv);
            Location bp2 = base.clone().add(pv.x() * globalScale, pv.y() * globalScale, pv.z() * globalScale);
            v.bonePositions.add(bp2);
            boneWorld.put(bone.name(), bp2);

            // 实体矩阵：lin(W) · (scale/16)
            Matrix4f m = new Matrix4f(w);
            m.m30(0);
            m.m31(0);
            m.m32(0); // 去平移，仅线性部分
            m.scale(new Vector3f((float) globalScale));
            v.worldMatrices.add(m);
        }
        inst.setBoneWorld(boneWorld);
    }

    // ------------------------------------------------------------------
    // 位置同步 / 挂接 / 销毁
    // ------------------------------------------------------------------

    @Override
    public void updateLocation(ModelInstanceImpl inst) {
        updateTransforms(inst);
    }

    @Override
    public void updateAttachments(ModelInstanceImpl inst) {
        // 挂接物品以额外 ItemDisplay 实现：attachToBone 时动态生成
        // 简化实现：挂接点物品显示在骨骼实体位置的挂接点偏移处
        Visuals v = visuals.get(inst.instanceId());
        if (v == null) return;
        // 清理旧挂接显示（重新生成）
        for (UUID id : new ArrayList<>(attachDisplays.getOrDefault(inst.instanceId(), List.of()))) {
            Entity e = org.bukkit.Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        attachDisplays.remove(inst.instanceId());
        if (inst.attachments.isEmpty()) return;

        List<UUID> created = new ArrayList<>();
        Location base = inst.location();
        if (base == null || base.getWorld() == null) return;
        for (var e : inst.attachments.entrySet()) {
            var bone = inst.modelImpl().bone(e.getKey());
            if (bone == null) continue;
            for (var p : e.getValue().entrySet()) {
                Vec3 offset = bone.attachPoints().getOrDefault(p.getKey(),
                        p.getKey().equals("default") ? Vec3.ZERO : Vec3.ZERO);
                double gs = inst.getScale() / 16.0;
                Location loc = base.clone().add(
                        offset.x() * gs, (offset.y() + bone.pivot().y()) * gs, offset.z() * gs);
                ItemDisplay d = base.getWorld().spawn(loc, ItemDisplay.class, dd -> {
                    dd.setItemStack(p.getValue());
                    dd.setTeleportDuration(interpolationTicks);
                    dd.setPersistent(false);
                });
                created.add(d.getUniqueId());
                entityIndex.put(d.getUniqueId(), inst.instanceId());
            }
        }
        attachDisplays.put(inst.instanceId(), created);
    }

    private final Map<Integer, List<UUID>> attachDisplays = new ConcurrentHashMap<>();

    @Override
    public void destroyVisuals(ModelInstanceImpl inst) {
        Visuals v = visuals.remove(inst.instanceId());
        if (v != null) {
            for (UUID id : v.allEntityIds) {
                Entity e = org.bukkit.Bukkit.getEntity(id);
                if (e != null) e.remove();
                entityIndex.remove(id);
            }
        }
        for (UUID id : attachDisplays.getOrDefault(inst.instanceId(), List.of())) {
            Entity e = org.bukkit.Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        attachDisplays.remove(inst.instanceId());
    }

    @Override
    public void applyVisibility(ModelInstanceImpl inst,
                                java.util.Collection<org.bukkit.entity.Player> visible,
                                java.util.Collection<org.bukkit.entity.Player> hidden) {
        Visuals v = visuals.get(inst.instanceId());
        if (v == null) return;
        for (org.bukkit.entity.Player p : visible) {
            for (ItemDisplay d : v.boneDisplays) {
                if (d.isValid()) p.showEntity(plugin, d);
            }
        }
        for (org.bukkit.entity.Player p : hidden) {
            for (ItemDisplay d : v.boneDisplays) {
                if (d.isValid()) p.hideEntity(plugin, d);
            }
        }
    }

    /** 全实例可见实体集合（可见性服务用）。 */
    public List<Entity> modelEntities(ModelInstanceImpl inst) {
        Visuals v = visuals.get(inst.instanceId());
        List<Entity> out = new ArrayList<>();
        if (v == null) return out;
        for (UUID id : v.allEntityIds) {
            Entity e = org.bukkit.Bukkit.getEntity(id);
            if (e != null) out.add(e);
        }
        return out;
    }

    public void shutdown() {
        for (Visuals v : visuals.values()) {
            for (UUID id : v.allEntityIds) {
                Entity e = org.bukkit.Bukkit.getEntity(id);
                if (e != null) e.remove();
            }
        }
        for (List<UUID> ids : attachDisplays.values()) {
            for (UUID id : ids) {
                Entity e = org.bukkit.Bukkit.getEntity(id);
                if (e != null) e.remove();
            }
        }
        visuals.clear();
        entityIndex.clear();
        attachDisplays.clear();
    }

    /** 名称/血条文本更新（伤害反馈时调用）。 */
    public void updateNameTag(ModelInstanceImpl inst, String text) {
        Visuals v = visuals.get(inst.instanceId());
        if (v != null && v.nameTag != null && v.nameTag.isValid()) {
            v.nameTag.text(LegacyText.parse(text));
        }
    }
}
