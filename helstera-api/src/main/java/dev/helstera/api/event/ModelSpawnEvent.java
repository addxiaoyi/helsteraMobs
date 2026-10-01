package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;

/** 模型实例生成事件。 */
public record ModelSpawnEvent(ModelInstance instance, Location location) {
}
