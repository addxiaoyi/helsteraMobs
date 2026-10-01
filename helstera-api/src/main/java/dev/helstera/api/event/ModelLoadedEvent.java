package dev.helstera.api.event;

import dev.helstera.api.model.ModelDefinition;

/** 模型加载/卸载事件。 */
public record ModelLoadedEvent(ModelDefinition model, boolean loaded, String error) {
}
