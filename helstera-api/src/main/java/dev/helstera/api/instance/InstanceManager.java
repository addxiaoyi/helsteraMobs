package dev.helstera.api.instance;

import dev.helstera.api.model.ModelHitbox;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Collection;
import java.util.Optional;

/**
 * 实例管理器：生成/绑定/查询/销毁模型实例。全部方法要求主线程。
 */
public interface InstanceManager {

    /** 在指定位置生成独立模型实体（隐形载体 + 模型显示）。 */
    ModelInstance spawn(String modelId, Location location, SpawnOptions options);

    /** 将模型绑定到已有实体（原版生物、盔甲架、显示实体均可）。 */
    ModelInstance bind(String modelId, Entity entity, SpawnOptions options);

    Optional<ModelInstance> getInstance(int instanceId);

    Collection<ModelInstance> allInstances();

    int activeCount();

    /** 按 ID 销毁实例。 */
    boolean despawn(int instanceId);

    /** 销毁某模型的所有实例。 */
    int despawnAll(String modelId);

    /** 世界卸载/重载时清理某世界的全部实例。 */
    void despawnWorld(org.bukkit.World world);

    ModelHitbox effectiveHitbox(String modelId, double scaleOverride);
}
