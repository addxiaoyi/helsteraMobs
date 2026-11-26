package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.Targeters;
import dev.helstera.api.util.EntitySetterSpec;
import dev.helstera.api.util.NumericExpression;
import dev.helstera.runtime.instance.PaperPathfindingMalus;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Raider;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Public Bukkit mutations with native argument aliases and explicit entity recipients. */
final class EntitySetterActions {
    private EntitySetterActions() { }

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories,
                                                       Function<String, ItemStack> items) {
        var result = new LinkedHashMap<>(factories);
        result.put("display-transformation", arguments -> {
            var spec = dev.helstera.api.util.DisplayTransformationSpec.parse(arguments);
            return context -> DisplayTransformationActions.apply(spec, context);
        });
        for (String name : EntitySetterSpec.names()) {
            result.put(name, args -> {
                var spec = EntitySetterSpec.parse(name, args);
                return context -> apply(spec, context, items);
            });
        }
        return Map.copyOf(result);
    }

    private static void apply(EntitySetterSpec spec, BehaviorContext context, Function<String, ItemStack> items) {
        if (context == null) return;
        if (spec.mechanic().equals("set-chunk-force-loaded")) {
            if (context.targetLocation().isPresent()) {
                forceChunk(context.targetLocation().orElseThrow(), spec.flag("loaded"));
            } else for (Entity entity : recipients(context)) forceChunk(entity.getLocation(), spec.flag("loaded"));
            return;
        }
        // An explicit location selection must never fall back to a retained entity or caster.
        if (context.targetLocation().isPresent()) return;
        List<Entity> recipients = recipients(context);
        if (recipients.isEmpty() && spec.mechanic().equals("set-interaction-size") && context.instanceValid()) {
            recipients = context.instance().baseEntity().map(List::of).orElseGet(List::of);
        }
        Location patrol = spec.mechanic().equals("set-raider-patrol-block")
                ? patrolLocation(spec.value("location"), context) : null;
        for (Entity recipient : recipients) {
            if (recipient == null || !recipient.isValid() || recipient.isDead()) continue;
            try {
                var scoped = context.withEntityRecipient(recipient);
                switch (spec.mechanic()) {
                    case "set-pathfinding-malus" -> PaperPathfindingMalus.apply(recipient,
                            Map.of(spec.value("malus"), EntitySetterSpec.finiteFloat(spec.value("value"))));
                    case "set-leash-holder" -> {
                        Entity caster = context.instanceValid() ? context.instance().baseEntity().orElse(null) : null;
                        if (caster instanceof LivingEntity living)
                            living.setLeashHolder(spec.flag("remove") ? null : recipient);
                    }
                    case "set-text-display" -> {
                        if (recipient instanceof TextDisplay display) display.text(MiniMessage.miniMessage()
                                .deserializeOrNull(Placeholders.resolve(spec.value("text"), scoped)));
                    }
                    case "set-display-entity-item" -> {
                        if (recipient instanceof ItemDisplay display) {
                            ItemStack item = spec.value("item").equalsIgnoreCase("AIR")
                                    ? new ItemStack(Material.AIR) : items.apply(spec.value("item"));
                            if (item != null) display.setItemStack(item.clone());
                        }
                    }
                    case "set-interaction-size" -> {
                        if (recipient instanceof Interaction interaction) {
                            float height = resolveFloat(spec.value("height"), scoped);
                            float width = resolveFloat(spec.value("width"), scoped);
                            // Resolve and validate both dimensions before either write.
                            interaction.setInteractionHeight(height);
                            interaction.setInteractionWidth(width);
                        }
                    }
                    case "set-raider-can-join-raid" -> {
                        if (recipient instanceof Raider raider) raider.setCanJoinRaid(spec.flag("bool"));
                    }
                    case "set-raider-patrol-leader" -> {
                        if (recipient instanceof Raider raider) raider.setPatrolLeader(spec.flag("bool"));
                    }
                    case "set-raider-patrol-block" -> {
                        if (recipient instanceof Raider raider && patrol != null && patrol.getWorld() != null)
                            raider.setPatrolTarget(patrol.getBlock());
                    }
                    default -> throw new IllegalStateException("Unregistered setter " + spec.mechanic());
                }
            } catch (RuntimeException failure) {
                SkillFaults.swallow("EntitySetterActions#" + spec.mechanic(), failure);
            }
        }
    }

    private static Location patrolLocation(String selector, BehaviorContext context) {
        var selected = Targeters.resolveLocation(selector, context);
        if (selected.isPresent()) return selected.orElseThrow();
        String name = selector.trim().replaceFirst("^@", "").split("\\{", 2)[0]
                .toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "");
        // These native location selectors return an empty collection when no target is
        // available, and SkillExecutor.getLocationTarget falls back to metadata.origin.
        // Missing trigger/invalid world failures are not empty selections.
        if (List.of("targetlocation", "targetloc", "tl", "trackedlocation").contains(name))
            return context.origin().orElse(null);
        return null;
    }

    private static float resolveFloat(String source, BehaviorContext context) {
        double value = NumericExpression.resolve(source, token -> Placeholders.resolve(token, context));
        return EntitySetterSpec.finiteFloat(Double.toString(value));
    }

    private static List<Entity> recipients(BehaviorContext context) {
        if (!context.targets().isEmpty()) return List.copyOf(context.targets());
        return context.entityRecipient().map(List::of).orElseGet(List::of);
    }

    private static void forceChunk(Location location, boolean loaded) {
        if (location == null || location.getWorld() == null) return;
        var chunk = location.getChunk();
        chunk.load();
        chunk.setForceLoaded(loaded);
    }
}
