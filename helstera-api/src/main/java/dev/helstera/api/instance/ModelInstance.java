package dev.helstera.api.instance;

import dev.helstera.api.animation.AnimationController;
import dev.helstera.api.model.ModelDefinition;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * 一个已生成的模型实例。生命周期方法均要求主线程。
 */
public interface ModelInstance {

    /** 运行时唯一 ID。 */
    int instanceId();

    ModelDefinition model();

    /** 绑定的基础实体（原版生物/盔甲架/显示实体），可能为空。 */
    Optional<Entity> baseEntity();

    /** 绑定实体 UUID（若有）。 */
    Optional<UUID> boundEntityId();

    /** 当前位置。 */
    Location location();

    void teleport(Location location);

    /** 全局缩放（会与模型定义 scale 相乘）。 */
    void setScale(double scale);

    double getScale();

    /** 设置基础朝向（yaw/pitch，度）。 */
    void setRotation(float yaw, float pitch);

    float getYaw();

    AnimationController animation();

    /**
     * 在骨骼挂接点放置物品（武器/装备/特效）。
     *
     * @param bone  骨骼名
     * @param point 挂接点名（可为 null 表示骨骼原点）
     * @param item  物品；传 null 移除
     */
    void attachItem(String bone, String point, ItemStack item);

    /** 销毁实例并清理所有实体。 */
    void despawn();

    /** 实例是否仍然有效（未销毁）。 */
    boolean isValid();
}
