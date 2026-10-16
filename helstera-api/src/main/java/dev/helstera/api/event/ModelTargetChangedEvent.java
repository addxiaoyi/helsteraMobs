package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.entity.LivingEntity;

/** Fired when a skill or integration explicitly changes a model's combat target. */
public record ModelTargetChangedEvent(ModelInstance instance,
                                      LivingEntity previousTarget,
                                      LivingEntity target) {
}
