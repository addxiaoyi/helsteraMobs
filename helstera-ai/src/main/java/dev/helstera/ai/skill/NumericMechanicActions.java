package dev.helstera.ai.skill;

import dev.helstera.api.util.NumericMechanicSpec;
import java.util.LinkedHashMap;
import java.util.Map;

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
                // Resolve once before any mutation, including multi-argument mechanics.
                return context -> spec.resolve(value -> Placeholders.resolve(value, context))
                        .ifPresent(resolved -> factory.create(resolved).accept(context));
            });
        });
        return Map.copyOf(wrapped);
    }
}
