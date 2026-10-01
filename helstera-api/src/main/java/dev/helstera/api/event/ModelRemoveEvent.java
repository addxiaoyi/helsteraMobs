package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;

/** 模型实例销毁事件。 */
public record ModelRemoveEvent(ModelInstance instance, String cause) {
}
