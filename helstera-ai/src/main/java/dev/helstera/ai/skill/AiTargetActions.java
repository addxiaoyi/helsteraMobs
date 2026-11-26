package dev.helstera.ai.skill;

import dev.helstera.ai.AiController;
import dev.helstera.ai.NativeTargetSelectors;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.BehaviorRegistry;
import dev.helstera.api.util.AiTargetSelectorSpec;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import dev.helstera.runtime.instance.PaperTargetGoals;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

final class AiTargetActions implements Listener {
    private AiTargetActions() { }
    private static final Map<UUID, NativeTargetSelectors> EXTERNAL = new HashMap<>();
    private static AiTargetActions listener;

    static void host(Plugin plugin) {
        if (listener != null) HandlerList.unregisterAll(listener);
        listener = null;
        if (plugin != null) {
            listener = new AiTargetActions();
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        } else shutdown();
    }

    @EventHandler public void removed(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent event) {
        forget(event.getEntity());
    }

    static SkillCatalog.ActionFactory factory(BehaviorRegistry registry, Function<String, String> binder,
            Function<Entity, dev.helstera.api.instance.ModelInstance> instances) {
        return arguments -> {
            var spec = AiTargetSelectorSpec.parse(arguments);
            // Preserve malformed static rules as inert mechanics, matching native handler failures.
            java.util.List<AiTargetSelectorSpec.Rule> parsed;
            try { parsed = spec.rules(); }
            catch (IllegalArgumentException invalid) { parsed = java.util.List.of(); }
            var rules = parsed;
            return context -> {
                if (context == null || !context.instanceValid() || rules.isEmpty()
                        || rules.stream().allMatch(rule -> rule.kind() == AiTargetSelectorSpec.Kind.UNKNOWN)) return;
                Entity caster = context.instance().baseEntity().orElse(null);
                if (caster == null) return;
                if (context.instance() instanceof ModelInstanceImpl instance
                        && instance.aiController instanceof AiController controller) {
                    controller.runTargetSelectors(rules);
                    return;
                }
                NativeTargetSelectors selectors = EXTERNAL.get(caster.getUniqueId());
                if (selectors == null) {
                    var opened = PaperTargetGoals.open(caster);
                    if (opened.session() == null) return;
                    BehaviorContext owned = context.copyForSkillMechanic();
                    selectors = new NativeTargetSelectors(opened.session(),
                            NativeTargetSelectors.predicates(() -> owned, registry, binder, entity -> {
                                var instance = instances.apply(entity);
                                return instance != null && instance.isValid()
                                        && instance.baseEntity().filter(base -> base == entity).isPresent();
                            }));
                    EXTERNAL.put(caster.getUniqueId(), selectors);
                }
                var result = selectors.mutate(rules);
                if (!result.success()) SkillFaults.swallow("AiTargetActions", new IllegalStateException(result.detail()));
            };
        };
    }
    static void forget(Entity entity) {
        var selectors = EXTERNAL.remove(entity.getUniqueId());
        if (selectors != null) selectors.close();
    }
    static void shutdown() {
        for (var selectors : EXTERNAL.values()) {
            try { selectors.close(); }
            catch (RuntimeException failure) { SkillFaults.swallow("AiTargetActions#close", failure); }
        }
        EXTERNAL.clear();
    }
}
