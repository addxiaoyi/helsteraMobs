package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;

/** 动画事件标记触发（attack_hit / sound / particle / skill 等）。 */
public record AnimationMarkerEvent(ModelInstance instance, String animation, String marker, String data) {
}
