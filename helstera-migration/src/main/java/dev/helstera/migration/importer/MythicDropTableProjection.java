package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
}
