package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.util.PercentHealthSpec;
import org.bukkit.entity.LivingEntity;

/** Fractional health mechanics audited against MythicMobs 5.7.2. */
final class PercentHealthAction {
    private PercentHealthAction() { }

    static void apply(PercentHealthSpec spec, BehaviorContext context, Iterable<LivingEntity> targets) {
        if (context == null || context.targetLocation().isPresent()) return;
        for (var target : targets) {
            if (target == null || !target.isValid() || target.isDead() || target.getHealth() <= 0) continue;
            var targetContext = context.withTargetEntity(target);
            var resolved = spec.resolve(value -> Placeholders.resolve(value, targetContext));
            if (resolved.isEmpty()) continue;
            var args = resolved.get();
            double maximum = target.getMaxHealth();
            double amount = (spec.currentHealth() ? target.getHealth() : maximum) * args.fraction();
            if (!Double.isFinite(amount)) continue;
            if (!spec.healing()) {
                SkillExtras.applyDamage(targetContext, target, amount, "ENTITY_ATTACK");
                continue;
            }
            double health = target.getHealth() + amount;
            double limit = maximum * args.maxOverheal();
            if (!Double.isFinite(health) || !Double.isFinite(limit)) continue;
            // MM 5.7.2 only adds overflow if the resulting absorption is strictly BELOW
            // maxHealth * maxOverheal; it neither clamps to that limit nor raises max health.
            if (health >= maximum && spec.overheal()) {
                double absorption = target.getAbsorptionAmount() + health - maximum;
                if (Double.isFinite(absorption) && absorption >= 0 && absorption < limit) {
                    target.setAbsorptionAmount(absorption);
                }
            }
            target.setHealth(Math.max(0, Math.min(maximum, health)));
        }
    }
}
