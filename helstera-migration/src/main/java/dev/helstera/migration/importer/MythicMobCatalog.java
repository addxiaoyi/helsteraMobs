package dev.helstera.migration.importer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Source Mob IDs and their collision-safe target IDs for cross-file references. */
final class MythicMobCatalog {
    record Resolution(String targetId, String problem) {
        boolean resolved() {
            return targetId != null;
        }
    }

    private static final String[] MOB_FIELDS = {
            "Type", "MobType", "EntityType", "Health", "Damage", "Display", "Options",
            "Skills", "Drops", "Equipment", "Disguise", "Faction", "LevelModifiers"
    };

    private final Map<String, Integer> ids = new HashMap<>();
    private boolean incomplete;

    static MythicMobCatalog read(Path pluginDataFolder) {
        MythicMobCatalog catalog = new MythicMobCatalog();
        if (pluginDataFolder == null) return catalog;
        Path directory = pluginDataFolder.resolve("Mobs");
        if (Files.notExists(directory)) return catalog;
        try (var files = Files.walk(directory)) {
            files.filter(Files::isRegularFile).filter(MythicMobsImporter::isYamlFile)
                    .forEach(catalog::collectFile);
        } catch (IOException | java.io.UncheckedIOException | SecurityException ex) {
            catalog.incomplete = true;
        }
        return catalog;
    }

    private void collectFile(Path file) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            for (String key : yaml.getKeys(false)) {
                ConfigurationSection definition = yaml.getConfigurationSection(key);
                if (definition == null) continue;
                boolean nestedMobDefinitions = definition.getKeys(false).stream().anyMatch(nestedKey -> {
                    ConfigurationSection nested = definition.getConfigurationSection(nestedKey);
                    return nested != null && MythicMobsImporter.ciGet(nested,
                            "Type", "MobType", "EntityType") != null;
                });
                Object mobType = MythicMobsImporter.ciGet(definition, "Type", "MobType", "EntityType");
                if ("mobs".equalsIgnoreCase(key)
                        && ((nestedMobDefinitions && (mobType == null || mobType instanceof ConfigurationSection))
                        || MythicMobsImporter.ciGet(definition, MOB_FIELDS) == null)) {
                    for (String nestedKey : definition.getKeys(false)) {
                        if (definition.getConfigurationSection(nestedKey) != null) add(nestedKey);
                    }
                } else if (MythicMobsImporter.ciGet(definition, MOB_FIELDS) != null) {
                    add(key);
                }
            }
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException
                 | RuntimeException ex) {
            incomplete = true;
        }
    }

    private void add(String sourceId) {
        ids.merge(MythicMobsImporter.normalizeId(sourceId), 1, Integer::sum);
    }

    Resolution resolve(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) return new Resolution(null, "Mob reference is empty.");
        String normalized = MythicMobsImporter.normalizeId(sourceId);
        if (incomplete) return new Resolution(null, "The source Mobs catalog could not be read completely.");
        int matches = ids.getOrDefault(normalized, 0);
        if (matches > 1) return new Resolution(null,
                "Multiple source Mobs definitions normalize to " + normalized + ".");
        if (matches == 0) return new Resolution(null,
                "No imported source Mob definition matches " + sourceId + ".");
        return new Resolution("mythic_" + normalized, null);
    }
}
