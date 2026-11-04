package dev.helstera.migration.importer;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Source definitions used to distinguish bare table names from item names. */
final class MythicDropCatalog {
    private final Map<String, Integer> tables = new HashMap<>();
    private final Map<String, Integer> normalizedTables = new HashMap<>();
    private final Map<String, Integer> items = new HashMap<>();
    private final Map<String, Integer> normalizedItems = new HashMap<>();
    private final Set<String> unprojectableTables = new java.util.HashSet<>();
    private final Map<String, ConfigurationSection> tableDefinitions = new HashMap<>();
    private final List<String> issues = new ArrayList<>();
    private boolean tableCatalogIncomplete;
    private boolean itemCatalogIncomplete;

    static MythicDropCatalog read(Path sourceFile, Path category) {
        MythicDropCatalog catalog = new MythicDropCatalog();
        Path directory = category;
        if (directory == null) directory = sourceFile.getParent();
        Path root = directory == null ? null : directory.getParent();
        if (root == null) return catalog;
        catalog.collect(root.resolve("DropTables"), true);
        catalog.collect(root.resolve("Items"), false);
        catalog.rejectEmptyProjections();
        return catalog;
    }

    private void collect(Path directory, boolean table) {
        if (Files.notExists(directory)) return;
        try (var files = Files.walk(directory)) {
            files.filter(Files::isRegularFile).filter(MythicMobsImporter::isYamlFile)
                    .forEach(file -> collectFile(file, table));
        } catch (IOException | java.io.UncheckedIOException | SecurityException ex) {
            issues.add(directory + ": " + ex.getClass().getSimpleName());
            if (table) tableCatalogIncomplete = true;
            else itemCatalogIncomplete = true;
        }
    }

    private void collectFile(Path file, boolean table) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            if (table) MythicMobsImporter.preserveNullDropTableConditions(yaml, file);
            ConfigurationSection definitions = !table && yaml.isConfigurationSection("items")
                    ? yaml.getConfigurationSection("items") : yaml;
            for (String key : definitions.getKeys(false)) {
                if (!table && !definitions.isConfigurationSection(key)) continue;
                String name = key.toLowerCase(Locale.ROOT);
                if (table) {
                    tables.merge(name, 1, Integer::sum);
                    normalizedTables.merge(MythicMobsImporter.normalizeId(key), 1, Integer::sum);
                    ConfigurationSection definition = definitions.getConfigurationSection(key);
                    if (definition == null) {
                        unprojectableTables.add(MythicMobsImporter.normalizeId(key));
                        continue;
                    }
                    tableDefinitions.put(MythicMobsImporter.normalizeId(key), definition);
                    if (!MythicDropTableProjection.validConditions(definition)) {
                        unprojectableTables.add(MythicMobsImporter.normalizeId(key));
                    }
                }
                else {
                    items.merge(name, 1, Integer::sum);
                    normalizedItems.merge(MythicMobsImporter.normalizeId(key), 1, Integer::sum);
                }
            }
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException | SecurityException | IllegalArgumentException ex) {
            issues.add(file + ": " + ex.getClass().getSimpleName());
            if (table) tableCatalogIncomplete = true;
            else itemCatalogIncomplete = true;
        }
    }

    List<String> issues() {
        return List.copyOf(issues);
    }

    private void rejectEmptyProjections() {
        boolean changed;
        do {
            changed = false;
            for (var entry : tableDefinitions.entrySet()) {
                if (unprojectableTables.contains(entry.getKey())) continue;
                Object drops = MythicMobsImporter.ciGet(entry.getValue(), "Drops");
                List<?> lines = drops instanceof List<?> list ? list : drops == null ? List.of() : List.of(drops);
                boolean hasEntry = lines.stream().map(this::parse)
                        .anyMatch(spec -> spec != null && !spec.runtimeEntry().isEmpty());
                if (!hasEntry) changed |= unprojectableTables.add(entry.getKey());
            }
        } while (changed);
    }

    boolean safeTableReference(String name) {
        String id = MythicMobsImporter.normalizeId(name);
        return !tableCatalogIncomplete && !unprojectableTables.contains(id)
                && normalizedTables.getOrDefault(id, 0) <= 1;
    }

    boolean ambiguousTable(String name) {
        return normalizedTables.getOrDefault(MythicMobsImporter.normalizeId(name), 0) > 1;
    }

    MythicDropSpec parse(Object raw) {
        MythicDropSpec spec = MythicDropSpec.parse(raw);
        if (spec == null || spec.external()) return spec;
        if (spec.isTable()) {
            return safeTableReference(spec.table()) ? spec : null;
        }
        if (spec.item() == null) return spec;
        boolean bare = raw instanceof String;
        if (itemCatalogIncomplete || (bare && tableCatalogIncomplete)) return null;
        String name = spec.item().toLowerCase(Locale.ROOT);
        Integer count = tables.get(name);
        Integer itemCount = items.get(name);
        boolean vanilla = Set.of("exp", "xp", "experience").contains(name)
                || Material.matchMaterial(spec.item()) != null;
        if (bare && count != null) {
            if (count != 1 || itemCount != null || vanilla ||
                    !safeTableReference(spec.item()) ||
                    normalizedTables.getOrDefault(MythicMobsImporter.normalizeId(spec.item()), 0) != 1) return null;
            return new MythicDropSpec(null, spec.item(), spec.amountMin(), spec.amountMax(), spec.chance(),
                    spec.chanceExpression(), spec.conditions(), spec.source(), false);
        }
        if (itemCount == null) return spec;
        String normalized = MythicMobsImporter.normalizeId(spec.item());
        if (itemCount != 1 || normalizedItems.getOrDefault(normalized, 0) != 1 || vanilla) return null;
        return new MythicDropSpec(normalized, null, spec.amountMin(), spec.amountMax(), spec.chance(),
                spec.chanceExpression(), spec.conditions(), spec.source(), false);
    }
}
