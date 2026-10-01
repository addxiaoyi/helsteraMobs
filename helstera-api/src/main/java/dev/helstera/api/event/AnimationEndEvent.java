package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;

/** 动画自然结束事件（非循环动画播完）。 */
public record AnimationEndEvent(ModelInstance instance, String animation) {
}
