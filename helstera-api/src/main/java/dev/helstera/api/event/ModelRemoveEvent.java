package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;

/** 模型实例移除事件；主动移除在实例失效前派发，以便执行最后的技能。 */
public record ModelRemoveEvent(ModelInstance instance, String cause) {
    /** Native despawn is distinct from death and temporary suspension. */
    public boolean despawning() {
        // Paper can already report isDead during DESPAWN; its cause is authoritative.
        if ("natural_despawn".equals(cause)) return true;
        // Death-animation cleanup can use the manual removal path after the death callback.
        if (instance != null && instance.baseEntity().map(org.bukkit.entity.Entity::isDead).orElse(false)) return false;
        return "manual".equals(cause) || "model_unload".equals(cause)
                || "chunk_unload".equals(cause) || "world_unload".equals(cause)
                || "plugin_shutdown".equals(cause);
    }

    /** A persistent entity left the loaded world but still exists on disk. */
    public boolean unloading() {
        return "persistent_chunk_unload".equals(cause) || "persistent_world_unload".equals(cause);
    }
}
