package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.util.ShieldBreakSpec;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

final class ShieldBreakActions {
    private static ShieldBreakService service;
    private ShieldBreakActions() { }

    static void host(Plugin plugin) {
        if (service != null) service.close();
        service = plugin == null ? null : new ShieldBreakService(plugin);
    }

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories) {
        var result = new LinkedHashMap<>(factories);
        result.put("disable-shield", arguments -> {
            var spec = ShieldBreakSpec.parse(arguments);
            return context -> execute(spec, context, service);
        });
        return Map.copyOf(result);
    }

    static void execute(ShieldBreakSpec spec, BehaviorContext context, ShieldBreakService owner) {
        if (owner == null || context == null || !context.instanceValid() || context.targetLocation().isPresent()) return;
        Entity caster = context.instance().baseEntity().orElse(null);
        if (caster == null) return;
        // Native passes the caster as PlaceholderInt's explicit entity argument.
        var durationContext = context.withEntityRecipient(caster);
        List<? extends Entity> recipients = !context.targets().isEmpty() ? List.copyOf(context.targets())
                : context.entityRecipient().map(List::of).orElseGet(List::of);
        for (Entity recipient : recipients) if (recipient instanceof Player player) {
            try {
                owner.disable(player, () -> spec.duration(text -> Placeholders.resolve(text, durationContext),
                        ThreadLocalRandom.current()));
            } catch (RuntimeException failure) { SkillFaults.swallow("ShieldBreakActions#disable-shield", failure); }
        }
    }
}
