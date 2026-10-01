package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;

/** 动画开始播放事件。 */
public record AnimationStartEvent(ModelInstance instance, String animation, boolean loop) {
}
