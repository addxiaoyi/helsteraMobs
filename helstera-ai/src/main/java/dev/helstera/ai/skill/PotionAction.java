package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.util.PotionSpec;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Applies the shared Potion contract without relying on Paper's ignored force overload. */
final class PotionAction {
    private PotionAction() { }

    static void apply(PotionSpec spec, BehaviorContext context, Iterable<LivingEntity> targets) {
        if (!targets.iterator().hasNext()) return;
        var resolved = spec.resolve(value -> Placeholders.resolve(value, context));
        if (resolved.isEmpty()) return;
        var args = resolved.get();
        var type = PotionEffectType.getByName(args.type());
        if (type == null) return; // Type unavailable on this server version.
        var effect = new PotionEffect(type, args.duration(), args.level(),
                args.ambient(), args.particles(), args.icon());
        for (LivingEntity target : targets) {
            if (args.force() && target.hasPotionEffect(type)) {
                target.removePotionEffect(type);
                // Respect plugins cancelling removal; do not update the retained effect.
                if (target.hasPotionEffect(type)) continue;
            }
            target.addPotionEffect(effect);
        }
    }
}
