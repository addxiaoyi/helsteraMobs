package dev.helstera.api.behavior;

import org.bukkit.entity.Entity;
import java.util.Optional;

/** Execution-owned variables for an entity whose live registry has been removed. */
public interface EntityVariableContext {
    Optional<SkillTreeContext> variablesFor(Entity entity);

    static Optional<SkillTreeContext> find(BehaviorContext context, String scope) {
        if (scope == null || context == null || !(context.instance() instanceof EntityVariableContext owner)) return Optional.empty();
        Entity entity = switch (scope.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "caster" -> context.instance().baseEntity().orElse(null);
            case "target" -> context.targetEntity().orElse(null);
            case "trigger" -> context.trigger().orElse(null);
            default -> null;
        };
        return entity == null ? Optional.empty() : owner.variablesFor(entity);
    }
}
