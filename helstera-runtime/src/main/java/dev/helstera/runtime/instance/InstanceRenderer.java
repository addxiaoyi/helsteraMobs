package dev.helstera.runtime.instance;

import dev.helstera.api.instance.SpawnOptions;
import org.bukkit.Location;

/**
 * 渲染层 SPI（由 helstera-render-paper 实现）。
 * runtime 模块不依赖 Bukkit 实体创建细节。
 */
public interface InstanceRenderer {

    /** 生成显示实体（骨骼 ItemDisplay 层级 + 碰撞盒 + 名称牌）。 */
    void createVisuals(ModelInstanceImpl inst, Location location, SpawnOptions options);

    /** 应用当前姿态到显示实体（变换矩阵 + 插值）。 */
    void updateTransforms(ModelInstanceImpl inst);

    /** 跟随基础实体移动/转向（传送同步）。 */
    void updateLocation(ModelInstanceImpl inst);

    /** 更新挂接物品。 */
    void updateAttachments(ModelInstanceImpl inst);

    /** 销毁显示实体并清理。 */
    void destroyVisuals(ModelInstanceImpl inst);

    /** 玩家订阅变化后同步 hide/show（可见性服务回调）。 */
    void applyVisibility(ModelInstanceImpl inst, java.util.Collection<org.bukkit.entity.Player> visible,
                         java.util.Collection<org.bukkit.entity.Player> hidden);
}
