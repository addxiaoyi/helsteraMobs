package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.Targeters;
import dev.helstera.api.util.EntityInteractionSpec;
import dev.helstera.runtime.instance.PaperEntityInteractions;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Goat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Entity-only native interactions with explicit type eligibility and observable adapter failures. */
final class EntityInteractionActions {
    private EntityInteractionActions() { }

    @FunctionalInterface
    interface Rotation {
        PaperEntityInteractions.Result apply(Entity recipient, float yaw, float pitch);
    }

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories) {
        return wrap(factories, PaperEntityInteractions::releaseUsingItem,
                PaperEntityInteractions::matchRotation, PaperEntityInteractions::ram);
    }

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories,
            Function<Entity, PaperEntityInteractions.Result> release,
            Rotation rotation, BiFunction<Entity, Entity, PaperEntityInteractions.Result> ram) {
        var result = new LinkedHashMap<>(factories);
        for (String name : EntityInteractionSpec.names()) result.put(name, arguments -> {
            var spec = EntityInteractionSpec.parse(name, arguments);
            return context -> apply(spec, context, release, rotation, ram);
        });
        return Map.copyOf(result);
    }

    private static void apply(EntityInteractionSpec spec, BehaviorContext context,
            Function<Entity, PaperEntityInteractions.Result> release,
            Rotation rotation, BiFunction<Entity, Entity, PaperEntityInteractions.Result> ram) {
        if (context == null || context.targetLocation().isPresent()) return;
        Entity caster = context.instanceValid() ? context.instance().baseEntity().orElse(null) : null;
        if (spec.mechanic().equals("goat-ram") && !(caster instanceof Goat)) return;
        List<Entity> recipients = context.targets().isEmpty()
                ? context.entityRecipient().map(List::of).orElseGet(List::of) : List.copyOf(context.targets());
        // Resolve against the calling metadata once, before narrowing it to an individual recipient.
        Location source;
        try {
            source = spec.mechanic().equals("match-rotation") ? source(spec.source(), context) : null;
        } catch (RuntimeException failure) {
            SkillFaults.swallow("EntityInteractionActions#" + spec.mechanic(), failure);
            return;
        }
        for (Entity recipient : recipients) {
            if (recipient == null || !recipient.isValid() || recipient.isDead()) continue;
            try {
                PaperEntityInteractions.Result result;
                switch (spec.mechanic()) {
                    case "stop-using-item" -> {
                        if (!(recipient instanceof LivingEntity)) continue;
                        result = release.apply(recipient);
                    }
                    case "match-rotation" -> {
                        if (recipient instanceof Player || source == null) continue;
                        result = rotation.apply(recipient, source.getYaw(), source.getPitch());
                    }
                    case "goat-ram" -> {
                        if (!(recipient instanceof LivingEntity)) continue;
                        result = ram.apply(caster, recipient);
                    }
                    default -> throw new IllegalStateException("Unregistered interaction " + spec.mechanic());
                }
                if (result == null || !result.success()) throw new IllegalStateException(
                        result == null ? "Interaction adapter returned no result" : result.detail());
            } catch (RuntimeException failure) {
                SkillFaults.swallow("EntityInteractionActions#" + spec.mechanic(), failure);
            }
        }
    }

    private static Location source(String selector, BehaviorContext context) {
        String kind = EntityInteractionSpec.sourceKind(selector);
        if (kind.equals("self") || kind.equals("caster"))
            return context.instanceValid() ? context.instance().baseEntity().map(Entity::getLocation)
                    .orElse(null) : null;
        // Native TriggerTargeter returns a singleton containing the trigger even when it
        // is null; a missing trigger is a failed source, never an empty-collection fallback.
        if (kind.equals("trigger")) return context.trigger().map(Entity::getLocation).orElse(null);
        var selected = Targeters.resolveLocation(selector, context);
        if (selected.isPresent()) return selected.orElseThrow();
        if (kind.equals("triggerlocation") || List.of("location", "loc", "l").contains(kind)) return null;
        return context.origin().orElse(null);
    }
}
