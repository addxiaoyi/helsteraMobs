package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;

/** 生物 AI 状态变化事件。 */
public record MobStateChangedEvent(ModelInstance instance, String fromState, String toState) {
}
