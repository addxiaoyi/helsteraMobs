package dev.helstera.ai.skill;

import dev.helstera.api.util.NumericMechanicSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.LivingEntity;

/** Makes direct factories and SkillService share argument validation and fail-closed resolution. */
final class NumericMechanicActions {
    private NumericMechanicActions() { }

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories) {
        var wrapped = new LinkedHashMap<>(factories);
        factories.forEach((name, factory) -> {
            if (!NumericMechanicSpec.supports(name)) return;
            wrapped.put(name, args -> {
                var spec = NumericMechanicSpec.parse(name, args);
                var action = factory.create(spec.arguments());
                if (!spec.dynamic()) return action;
                String canonical = NumericMechanicSpec.canonical(name);
                if (canonical.endsWith("-targets") || List.of("damage-target", "force-pull", "freeze").contains(canonical)) {
                    return context -> {
                        if (context == null || context.targetLocation().isPresent()) return;
                        List<LivingEntity> targets = context.targets().isEmpty()
                                ? context.targetEntity().map(List::of).orElseGet(List::of) : context.targets();
                        // Freeze retains its existing explicit entity-or-caster fallback.
                        if (targets.isEmpty() && canonical.equals("freeze") && context.instanceValid()) {
                            targets = context.instance().baseEntity().filter(LivingEntity.class::isInstance)
                                    .map(LivingEntity.class::cast).map(List::of).orElseGet(List::of);
                        }
                        for (var target : targets) {
                            try {
                                if (target == null || !target.isValid() || target.isDead()) continue;
                                var scoped = context.withTargetEntity(target);
                                // Validate every numeric field for this recipient before any mutation.
                                spec.resolve(value -> Placeholders.resolve(value, scoped))
                                        .ifPresent(resolved -> factory.create(resolved).accept(scoped));
                            } catch (RuntimeException failure) {
                                SkillFaults.swallow("NumericMechanicActions#recipient", failure);
                            }
                        }
                    };
                }
                // Caster-only and radial actions retain their single invocation context.
                return context -> spec.resolve(value -> Placeholders.resolve(value, context))
                        .ifPresent(resolved -> factory.create(resolved).accept(context));
            });
        });
        return Map.copyOf(wrapped);
    }
}
