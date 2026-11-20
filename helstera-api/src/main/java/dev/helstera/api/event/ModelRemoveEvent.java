package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;

/** 模型实例销毁事件。 */
public record ModelRemoveEvent(ModelInstance instance, String cause) {
    /** A persistent entity left the loaded world but still exists on disk. */
    public boolean unloading() {
        return "persistent_chunk_unload".equals(cause) || "persistent_world_unload".equals(cause);
    }
}
