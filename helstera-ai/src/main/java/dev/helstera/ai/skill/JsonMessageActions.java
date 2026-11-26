package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.util.MythicJsonMessageSpec;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sends structured components to each selected player using its own target context. */
final class JsonMessageActions {
    private JsonMessageActions() { }

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories) {
        var result = new LinkedHashMap<>(factories);
        result.put("message-json", arguments -> {
            String message = ChatColor.translateAlternateColorCodes('&', MythicJsonMessageSpec.parse(arguments).message());
            return context -> execute(message, context);
        });
        return Map.copyOf(result);
    }

    private static void execute(String message, BehaviorContext context) {
        if (context == null || context.targetLocation().isPresent()) return;
        List<? extends Entity> recipients = !context.targets().isEmpty() ? List.copyOf(context.targets())
                : context.entityRecipient().map(List::of).orElseGet(List::of);
        for (Entity target : recipients) {
            if (!(target instanceof Player player) || !ExecutionAudiences.allows(context, player)) continue;
            try {
                String resolved = Placeholders.resolve(message, context.withTargetEntity(player));
                var components = ComponentSerializer.parse(resolved);
                if (components == null || java.util.Arrays.stream(components).anyMatch(java.util.Objects::isNull))
                    throw new IllegalArgumentException("JSON message contains a null component");
                player.spigot().sendMessage(components);
            } catch (RuntimeException failure) {
                SkillFaults.swallow("JsonMessageActions#message-json", failure);
            }
        }
    }
}
