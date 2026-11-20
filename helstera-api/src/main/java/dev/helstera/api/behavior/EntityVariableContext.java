package dev.helstera.api.behavior;

import org.bukkit.entity.Entity;
import java.util.Optional;

/** Execution-owned variables for an entity whose live registry has been removed. */
public interface EntityVariableContext {
    Optional<SkillTreeContext> variablesFor(Entity entity);

    /** A retained registry may remain addressable briefly after confirmed death. */
    default boolean registryAvailableFor(Entity entity) { return variablesFor(entity).isEmpty(); }

    /**
     * Target mutations require an attached registry; only caster mutations may use
     * retained terminal variables. Entity validity/death does not establish detachment.
     */
    static boolean isDetachedTarget(BehaviorContext context, String scope) {
        return scope != null && scope.trim().equalsIgnoreCase("target") && isRegistryUnavailable(context, scope);
    }

    /** True when a terminal target or trigger role refers to an expired entity registry. */
    static boolean isUnavailableEntityRole(BehaviorContext context, String scope) {
        return scope != null && (scope.trim().equalsIgnoreCase("target")
                || scope.trim().equalsIgnoreCase("trigger")) && isRegistryUnavailable(context, scope);
    }

    static boolean isRegistryUnavailable(BehaviorContext context, String scope) {
        if (context == null || !(context.instance() instanceof EntityVariableContext owner)) return false;
        Entity entity = entityFor(context, scope);
        return entity != null && !owner.registryAvailableFor(entity);
    }

    static Optional<SkillTreeContext> find(BehaviorContext context, String scope) {
        if (scope == null || context == null || !(context.instance() instanceof EntityVariableContext owner)) return Optional.empty();
        Entity entity = entityFor(context, scope);
        return entity == null ? Optional.empty() : owner.variablesFor(entity);
    }

    private static Entity entityFor(BehaviorContext context, String scope) {
        if (scope == null) return null;
        return switch (scope.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "caster" -> context.instance().baseEntity().orElse(null);
            case "target" -> context.targetEntity().orElse(null);
            case "trigger" -> context.trigger().orElse(null);
            default -> null;
        };
    }
}
