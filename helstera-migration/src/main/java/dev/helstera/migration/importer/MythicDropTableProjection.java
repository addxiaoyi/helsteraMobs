package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Converts safe Mythic drop entries into the runtime loot.yml table shape. */
public final class MythicDropTableProjection {
    private MythicDropTableProjection() {
    }

    public static YamlConfiguration project(String tableName, List<?> rawEntries) {
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("table name is blank");
        YamlConfiguration out = new YamlConfiguration();
        var entries = out.createSection("tables." + tableName + ".entries");
        int index = 0;
        for (Object raw : rawEntries == null ? List.of() : rawEntries) {
            MythicDropSpec spec = MythicDropSpec.parse(raw);
            if (spec == null) continue;
            Map<String, Object> projection = spec.runtimeEntry();
            if (projection.isEmpty()) continue;
            var entry = entries.createSection(Integer.toString(index++));
            projection.forEach(entry::set);
        }
        return out;
    }

    public static YamlConfiguration project(String tableName, ConfigurationSection source) {
        Object drops = valueIgnoreCase(source, "Drops");
        List<?> rawEntries = drops instanceof List<?> list ? list
                : drops == null ? List.of() : List.of(drops);
        return project(tableName, source, rawEntries);
    }

    static YamlConfiguration project(String tableName, ConfigurationSection source, List<?> rawEntries) {
        if (tableName == null || tableName.isBlank()) throw new IllegalArgumentException("table name is blank");
        if (!validConditions(source)) return new YamlConfiguration();
        List<String> rawTriggers = list(valueIgnoreCase(source, "TriggerConditions"));
        List<String> rawConditions = list(valueIgnoreCase(source, "Conditions"));
        List<String> translatedTriggers = translateConditions(rawTriggers);
        List<String> translatedConditions = translateConditions(rawConditions);

        YamlConfiguration out = new YamlConfiguration();
        String root = "tables." + tableName;
        YamlConfiguration entries = project(tableName, rawEntries);
        ConfigurationSection projected = entries.getConfigurationSection(root);
        if (projected != null) projected.getValues(true).forEach((key, value) -> out.set(root + "." + key, value));
        copy(source, out, root, "TotalItems", "total-items");
        copy(source, out, root, "MinItems", "min-items");
        copy(source, out, root, "MaxItems", "max-items");
        copy(source, out, root, "BonusLevelItems", "bonus-level-items");
        copy(source, out, root, "BonusLuckItems", "bonus-luck-items");
        copyString(source, out, root, "BiasStat", "bias-stat");
        if (!translatedTriggers.isEmpty()) out.set(root + ".trigger-conditions", translatedTriggers);
        if (!translatedConditions.isEmpty()) out.set(root + ".conditions", translatedConditions);
        return out;
    }

    static boolean validConditions(ConfigurationSection source) {
        if (source != null) {
            Set<String> seen = new HashSet<>();
            for (String key : source.getKeys(false)) {
                if (!seen.add(key.toLowerCase(Locale.ROOT))) return false;
            }
        }
        List<String> rawTriggers = list(valueIgnoreCase(source, "TriggerConditions"));
        List<String> rawConditions = list(valueIgnoreCase(source, "Conditions"));
        return rawTriggers != null && rawConditions != null
                && translateConditions(rawTriggers) != null && translateConditions(rawConditions) != null;
    }

    private static List<String> translateConditions(List<String> rawConditions) {
        List<String> translated = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        MmConditions.translateAll(rawConditions, translated, unmapped);
        return unmapped.isEmpty() ? translated : null;
    }

    private static List<String> list(Object value) {
        if (value == null) return List.of();
        List<?> values = value instanceof List<?> list ? list : List.of(value);
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) return null;
            result.add(text);
        }
        return result;
    }

    private static void copy(ConfigurationSection source, YamlConfiguration target,
                             String root, String sourceKey, String targetKey) {
        Object value = valueIgnoreCase(source, sourceKey);
        if (value != null) target.set(root + "." + targetKey, value);
    }

    private static void copyString(ConfigurationSection source, YamlConfiguration target,
                                   String root, String sourceKey, String targetKey) {
        Object value = valueIgnoreCase(source, sourceKey);
        if (value instanceof String text && !text.isBlank()) {
            target.set(root + "." + targetKey, text.trim());
        }
    }

    private static Object valueIgnoreCase(ConfigurationSection section, String key) {
        if (section == null) return null;
        for (String candidate : section.getKeys(false)) {
            if (candidate.equalsIgnoreCase(key)) return section.get(candidate);
        }
        return null;
    }
}
