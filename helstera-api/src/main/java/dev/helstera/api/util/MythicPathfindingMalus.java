package dev.helstera.api.util;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** MythicMobs 5.7.2 path malus syntax and the Minecraft 1.21.1 PathType names. */
public final class MythicPathfindingMalus {
    private static final Set<String> PATH_TYPES = Set.of(
            "BLOCKED", "OPEN", "WALKABLE", "WALKABLE_DOOR", "TRAPDOOR",
            "POWDER_SNOW", "DANGER_POWDER_SNOW", "FENCE", "LAVA", "WATER",
            "WATER_BORDER", "RAIL", "UNPASSABLE_RAIL", "DANGER_FIRE", "DAMAGE_FIRE",
            "DANGER_OTHER", "DAMAGE_OTHER", "DOOR_OPEN", "DOOR_WOOD_CLOSED",
            "DOOR_IRON_CLOSED", "BREACH", "LEAVES", "STICKY_HONEY", "COCOA",
            "DAMAGE_CAUTIOUS", "DANGER_TRAPDOOR");

    private MythicPathfindingMalus() {
    }

    public record Parsed(Map<String, Float> values, List<String> warnings) {
        public Parsed {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            warnings = List.copyOf(warnings);
        }
    }

    /** Native Mythic reads a list such as {@code ["WATER -1", "DANGER_FIRE 0"]}. */
    public static Parsed parseMythic(Object raw) {
        Map<String, Float> values = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (!(raw instanceof List<?> entries)) {
            return new Parsed(values, List.of("AIPathfindingMalus requires a list of 'PATH_TYPE value' strings."));
        }
        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);
            if (!(entry instanceof String line)) {
                warnings.add("Entry " + (i + 1) + " requires a 'PATH_TYPE value' string.");
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 2) {
                warnings.add("Entry " + (i + 1) + " requires exactly 'PATH_TYPE value'.");
                continue;
            }
            add(values, warnings, parts[0], parts[1]);
        }
        return new Parsed(values, warnings);
    }

    /** Validates the imported {@code ai.pathfinding-malus} map before any native mutation. */
    public static Parsed parseRuntime(Object raw) {
        if (raw instanceof ConfigurationSection section) raw = section.getValues(false);
        Map<String, Float> values = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (!(raw instanceof Map<?, ?> entries)) {
            return new Parsed(values, List.of("ai.pathfinding-malus requires a map of PathType names to finite floats."));
        }
        for (var entry : entries.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                warnings.add("PathType keys must be strings.");
                continue;
            }
            add(values, warnings, name, entry.getValue());
        }
        return new Parsed(values, warnings);
    }

    public static boolean isSupportedPathType(String name) {
        return name != null && PATH_TYPES.contains(name.trim().toUpperCase(Locale.ROOT));
    }

    private static void add(Map<String, Float> values, List<String> warnings, String name, Object raw) {
        String type = name.trim().toUpperCase(Locale.ROOT);
        if (!PATH_TYPES.contains(type)) {
            warnings.add("Unknown Minecraft 1.21.1 PathType '" + name + "'; FIRE has no native alias.");
            return;
        }
        if (!(raw instanceof Number) && !(raw instanceof String)) {
            warnings.add(type + " requires a finite float.");
            return;
        }
        try {
            float value = Float.parseFloat(String.valueOf(raw).trim());
            if (!Float.isFinite(value)) {
                warnings.add(type + " requires a finite float.");
                return;
            }
            values.put(type, value);
        } catch (NumberFormatException ignored) {
            warnings.add(type + " requires a finite float.");
        }
    }
}
