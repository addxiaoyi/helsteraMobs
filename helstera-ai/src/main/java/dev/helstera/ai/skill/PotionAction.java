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
        if (context == null || context.targetLocation().isPresent()) return;
        for (LivingEntity target : targets) {
            try {
                if (target == null || !target.isValid() || target.isDead()) continue;
                var scoped = context.withTargetEntity(target);
                var resolved = spec.resolve(value -> Placeholders.resolve(value, scoped));
                if (resolved.isEmpty()) continue;
                var args = resolved.get();
                var type = PotionEffectType.getByName(args.type());
                if (type == null) continue; // Type unavailable on this server version.
                var effect = new PotionEffect(type, args.duration(), args.level(),
                        args.ambient(), args.particles(), args.icon());
                if (args.force() && target.hasPotionEffect(type)) {
                    target.removePotionEffect(type);
                    // Respect plugins cancelling removal; do not update the retained effect.
                    if (target.hasPotionEffect(type)) continue;
                }
                // Event callbacks during forced removal can invalidate the recipient.
                if (target.isValid() && !target.isDead()) target.addPotionEffect(effect);
            } catch (RuntimeException failure) {
                SkillFaults.swallow("PotionAction#recipient", failure);
            }
        }
    }
}
