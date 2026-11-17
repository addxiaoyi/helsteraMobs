package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.util.SetSpeedSpec;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/** Absolute WALK/FLY speed, independent of the recipient's current base/modifiers. */
final class SetSpeedAction {
    private SetSpeedAction() { }

    static void apply(SetSpeedSpec spec, BehaviorContext context, Iterable<LivingEntity> targets) {
        if (context == null || context.targetLocation().isPresent()) return;
        float speed = spec.speed();
        for (var target : targets) {
            if (target == null || !target.isValid() || target.isDead()) continue;
            if (target instanceof Player player) {
                // Bukkit rejects values outside [-1,1]. Skip that recipient without
                // preventing valid non-player recipients from receiving the same action.
                if (speed < -1f || speed > 1f) continue;
                if (spec.flying()) player.setFlySpeed(speed);
                else player.setWalkSpeed(speed);
            } else {
                var attribute = target.getAttribute(spec.flying()
                        ? Attribute.GENERIC_FLYING_SPEED : Attribute.GENERIC_MOVEMENT_SPEED);
                if (attribute != null) attribute.setBaseValue(speed);
            }
        }
    }
}
