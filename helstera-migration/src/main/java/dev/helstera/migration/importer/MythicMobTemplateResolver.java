package dev.helstera.migration.importer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Resolves MythicMobs mob templates before the ordinary field importer runs. */
final class MythicMobTemplateResolver {

    private static final Set<String> METADATA = Set.of("template", "exclude");
    private static final Set<String> KEYED_LISTS = Set.of("equipment", "equip", "damagemodifiers", "levelmodifiers");

    private final Map<String, List<Definition>> definitions;

    private MythicMobTemplateResolver(Map<String, List<Definition>> definitions) {
        this.definitions = definitions;
    }

    static MythicMobTemplateResolver load(Path mobsDirectory) {
        Map<String, List<Definition>> definitions = new LinkedHashMap<>();
        if (mobsDirectory == null || Files.notExists(mobsDirectory)) {
            return new MythicMobTemplateResolver(definitions);
        }
        try (Stream<Path> paths = Files.walk(mobsDirectory)) {
            paths.filter(MythicMobsImporter::isYamlFile)
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> readFile(path, definitions));
        } catch (IOException | java.io.UncheckedIOException ex) {
            throw new IllegalArgumentException("Cannot scan MythicMobs mob templates: " + ex.getMessage(), ex);
        }
        return new MythicMobTemplateResolver(definitions);
    }

    Resolution resolve(String mobId, ConfigurationSection mob) {
        String templateKey = keyIgnoreCase(mob, "Template");
        String excludeKey = keyIgnoreCase(mob, "Exclude");
        Object rawTemplate = templateKey == null ? null : mob.get(templateKey);
        Object rawExclude = excludeKey == null ? null : mob.get(excludeKey);
        if (templateKey == null && excludeKey == null) {
            return new Resolution(toMap(mob), null, null, null, List.of(), false);
        }
        try {
            Map<String, Object> resolved = resolveMap(mobId, toMap(mob), new ArrayDeque<>());
            List<String> directTemplates = templateKey == null ? List.of() : parseNames(rawTemplate, "Template");
            // Template is not inherited, but the importer still needs this mob's
            // direct lineage for TemplateType conditions.
            if (templateKey != null) resolved.put(templateKey, deepCopy(rawTemplate));
            return new Resolution(resolved, null, rawTemplate, rawExclude, directTemplates, true);
        } catch (ResolutionException ex) {
            return new Resolution(toMap(mob), ex.getMessage(), rawTemplate, rawExclude,
                    parseNamesOrEmpty(rawTemplate), true);
        }
    }

    private Map<String, Object> resolveMap(String mobId, Map<String, Object> own, Deque<String> stack) {
        String normalizedId = normalizeReference(mobId);
        if (stack.contains(normalizedId)) {
            throw new ResolutionException("template cycle detected: " + String.join(" -> ", stack) + " -> " + mobId);
        }
        stack.addLast(normalizedId);
        try {
            LinkedHashMap<String, Object> inherited = new LinkedHashMap<>();
            String templateKey = keyIgnoreCase(own, "Template");
            if (templateKey != null) {
                for (String reference : parseNames(own.get(templateKey), "Template")) {
                    Definition definition = find(reference);
                    Map<String, Object> resolvedTemplate = resolveMap(definition.id(), definition.fields(), stack);
                    mergeMap(inherited, resolvedTemplate, true, "");
                }
            }

            String excludeKey = keyIgnoreCase(own, "Exclude");
            if (excludeKey != null) {
                for (String excluded : parseNames(own.get(excludeKey), "Exclude")) {
                    removeIgnoreCase(inherited, excluded);
                }
            }

            mergeMap(inherited, own, true, "");
            return inherited;
        } finally {
            stack.removeLast();
        }
    }

    private Definition find(String reference) {
        List<Definition> matches = definitions.get(normalizeReference(reference));
        if ((matches == null || matches.isEmpty()) && reference.regionMatches(true, 0, "Template.", 0, 9)) {
            matches = definitions.get(normalizeReference(reference.substring(9)));
        }
        if (matches == null || matches.isEmpty()) {
            throw new ResolutionException("template reference does not resolve to a mob: " + reference);
        }
        if (matches.size() != 1) {
            String sources = matches.stream().map(definition -> definition.source().toString())
                    .distinct().sorted().collect(java.util.stream.Collectors.joining(", "));
            throw new ResolutionException("template mob ID is ambiguous across files: " + reference
                    + " (" + sources + ")");
        }
        return matches.get(0);
    }

    private static void mergeMap(LinkedHashMap<String, Object> target, Map<String, Object> source,
                                 boolean topLevel, String path) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (topLevel && METADATA.contains(normalizedKey)) continue;
            String existingKey = keyIgnoreCase(target, key);
            Object incoming = deepCopy(entry.getValue());
            if (existingKey == null) {
                target.put(key, incoming);
                continue;
            }
            Object existing = target.get(existingKey);
            if (existing instanceof Map<?, ?> existingMap && incoming instanceof Map<?, ?> incomingMap) {
                LinkedHashMap<String, Object> merged = stringMap(existingMap);
                mergeMap(merged, stringMap(incomingMap), false, join(path, key));
                target.put(existingKey, merged);
            } else if (existing instanceof List<?> existingList && incoming instanceof List<?> incomingList) {
                target.put(existingKey, mergeList(key, existingList, incomingList));
            } else {
                target.put(existingKey, incoming);
            }
        }
    }

    private static List<Object> mergeList(String field, List<?> existing, List<?> incoming) {
        String normalized = field.toLowerCase(Locale.ROOT);
        List<Object> result = new ArrayList<>();
        existing.forEach(value -> result.add(deepCopy(value)));
        if (normalized.equals("trades")) {
            for (int index = 0; index < incoming.size(); index++) {
                Object value = deepCopy(incoming.get(index));
                if (index < result.size()) result.set(index, value);
                else result.add(value);
            }
            return result;
        }
        if (!KEYED_LISTS.contains(normalized)) {
            incoming.forEach(value -> result.add(deepCopy(value)));
            return result;
        }
        for (Object value : incoming) {
            String identity = listIdentity(normalized, value);
            if (identity != null) {
                result.removeIf(previous -> identity.equalsIgnoreCase(listIdentity(normalized, previous)));
            }
            result.add(deepCopy(value));
        }
        return result;
    }

    private static String listIdentity(String field, Object value) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) return null;
            String[] parts = trimmed.split("\\s+");
            return field.equals("equipment") || field.equals("equip")
                    ? parts[parts.length - 1] : parts[0];
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = stringMap(map);
            String identity;
            if (field.equals("equipment") || field.equals("equip")) {
                identity = firstString(normalized, "slot", "position", "equipment-slot");
            } else {
                identity = firstString(normalized, "type", "modifier", "name", "id");
            }
            return identity == null || identity.isBlank() ? null : identity.trim();
        }
        return null;
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = getIgnoreCase(map, key);
            if (value instanceof String text) return text;
        }
        return null;
    }

    private static List<String> parseNames(Object raw, String field) {
        List<String> result = new ArrayList<>();
        if (raw instanceof String text) {
            appendNames(text, result, field);
        } else if (raw instanceof List<?> values) {
            for (Object value : values) {
                if (!(value instanceof String text)) {
                    throw new ResolutionException(field + " entries must be strings");
                }
                appendNames(text, result, field);
            }
        } else {
            throw new ResolutionException(field + " must be a string or a list of strings");
        }
        if (result.isEmpty()) throw new ResolutionException(field + " must contain at least one non-empty name");
        return List.copyOf(result);
    }

    private static void appendNames(String text, List<String> result, String field) {
        for (String token : text.split(",", -1)) {
            String value = token.trim();
            if (value.isEmpty()) throw new ResolutionException(field + " contains an empty name");
            result.add(value);
        }
    }

    private static List<String> parseNamesOrEmpty(Object raw) {
        try {
            return parseNames(raw, "Template");
        } catch (ResolutionException ignored) {
            return List.of();
        }
    }

    private static void readFile(Path path, Map<String, List<Definition>> definitions) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(path.toFile());
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException | SecurityException ex) {
            throw new java.io.UncheckedIOException(new IOException("Cannot read " + path + ": " + ex.getMessage(), ex));
        }
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) continue;
            ConfigurationSection nested = sectionIgnoreCase(section, "Type", "MobType", "EntityType", "Template");
            boolean wrapper = key.equalsIgnoreCase("mobs") && nested == null
                    && section.getKeys(false).stream().anyMatch(child -> section.getConfigurationSection(child) != null);
            if (wrapper) {
                for (String child : section.getKeys(false)) {
                    ConfigurationSection definition = section.getConfigurationSection(child);
                    if (definition != null) register(definitions, child, definition, path);
                }
            } else {
                register(definitions, key, section, path);
            }
        }
    }

    private static void register(Map<String, List<Definition>> definitions, String id,
                                 ConfigurationSection section, Path path) {
        String normalized = normalizeReference(id);
        definitions.computeIfAbsent(normalized, ignored -> new ArrayList<>())
                .add(new Definition(id, toMap(section), path));
    }

    private static Map<String, Object> toMap(ConfigurationSection section) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) result.put(key, deepCopy(section.get(key)));
        return result;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof ConfigurationSection section) return toMap(section);
        if (value instanceof Map<?, ?> map) return stringMap(map);
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(element -> copy.add(deepCopy(element)));
            return copy;
        }
        return value;
    }

    private static LinkedHashMap<String, Object> stringMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) result.put(key, deepCopy(entry.getValue()));
        }
        return result;
    }

    private static String keyIgnoreCase(ConfigurationSection section, String key) {
        for (String candidate : section.getKeys(false)) if (candidate.equalsIgnoreCase(key)) return candidate;
        return null;
    }

    private static String keyIgnoreCase(Map<String, ?> map, String key) {
        for (String candidate : map.keySet()) if (candidate.equalsIgnoreCase(key)) return candidate;
        return null;
    }

    private static Object getIgnoreCase(Map<String, ?> map, String key) {
        String found = keyIgnoreCase(map, key);
        return found == null ? null : map.get(found);
    }

    private static ConfigurationSection sectionIgnoreCase(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            String found = keyIgnoreCase(section, key);
            if (found != null) return section.getConfigurationSection(found);
        }
        return null;
    }

    private static void removeIgnoreCase(Map<String, ?> map, String key) {
        String found = keyIgnoreCase(map, key);
        if (found != null) map.remove(found);
    }

    private static String normalizeReference(String reference) {
        return reference.trim().toLowerCase(Locale.ROOT);
    }

    private static String join(String prefix, String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }

    record Resolution(Map<String, Object> effectiveFields, String error, Object rawTemplate,
                      Object rawExclude, List<String> directTemplates, boolean changed) {
        Resolution {
            // YAML permits explicit null values; preserve them in the source map.
            effectiveFields = Collections.unmodifiableMap(new LinkedHashMap<>(effectiveFields));
            directTemplates = List.copyOf(directTemplates);
        }
        boolean resolved() { return error == null; }
    }

    private record Definition(String id, Map<String, Object> fields, Path source) { }

    private static final class ResolutionException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private ResolutionException(String message) { super(message); }
    }
}
