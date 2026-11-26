package dev.helstera.api.behavior;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/** Uses the same Bukkit online-player lookup as the pinned native server adapter. */
final class NamedPlayerTargeter {
    private NamedPlayerTargeter() { }

    static List<LivingEntity> select(List<String> arguments, UnaryOperator<String> resolver) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String argument : arguments) {
            int equals = argument.indexOf('=');
            if (equals <= 0) return List.of();
            String key = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (!key.equals("name") && !key.equals("n")) return List.of();
            String value = argument.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            options.put(key, value);
        }
        String name = resolver.apply(options.getOrDefault("name", options.getOrDefault("n", "CarsonJF")));
        if (name == null || name.isBlank() || name.indexOf('<') >= 0 || name.indexOf('>') >= 0) return List.of();
        Player player = Bukkit.getPlayerExact(name);
        return player == null ? List.of() : List.of(player);
    }
}
