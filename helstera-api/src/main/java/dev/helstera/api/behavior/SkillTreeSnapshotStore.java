package dev.helstera.api.behavior;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** YAML persistence for SkillTreeContext snapshots; live entities are intentionally excluded. */
public final class SkillTreeSnapshotStore {
    private static final int SNAPSHOT_FORMAT_VERSION = 4;
    private static final int MAX_VARIABLE_COLLECTION_SIZE = 4096;
    private static final int MAX_VARIABLE_VALUE_NODES = 16384;
    private static final int MAX_VARIABLE_VALUE_DEPTH = 16;

    private SkillTreeSnapshotStore() {
    }

    public static void save(File file, SkillTreeContext.Snapshot snapshot) throws IOException {
        if (file == null || snapshot == null) throw new IllegalArgumentException("file and snapshot are required");
        Path target = file.toPath().toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null || target.getFileName() == null) {
            throw new IOException("snapshot path must name a file: " + file);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent,
                "skilltree-" + target.getFileName() + "-", ".tmp");
        boolean committed = false;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            write(yaml, snapshot);
            yaml.save(temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            committed = true;
        } finally {
            if (!committed) Files.deleteIfExists(temporary);
        }
    }

    public static SkillTreeContext.Snapshot load(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("snapshot file does not exist: " + file);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
            throw new IOException("invalid skill-tree snapshot: " + file, ex);
        }
        return read(yaml);
    }

    private static void write(ConfigurationSection section, SkillTreeContext.Snapshot snapshot)
            throws IOException {
        validateExpirations(snapshot.variableExpirations(), snapshot.variables());
        validateExpirations(snapshot.locationExpirations(), snapshot.locations());
        section.set("snapshot-format", SNAPSHOT_FORMAT_VERSION);
        ConfigurationSection variables = section.createSection("variables");
        ConfigurationSection variableEntries = variables.createSection("entries");
        int[] valueNodes = {0};
        for (var entry : snapshot.variables().entrySet()) {
            ConfigurationSection variable = variableEntries.createSection(encodedKey(entry.getKey()));
            variable.set("name", entry.getKey());
            if (snapshot.variableExpirations().containsKey(entry.getKey()))
                variable.set("expires-at", snapshot.variableExpirations().get(entry.getKey()));
            writeVariableValue(variable.createSection("value"), entry.getValue(), 0, valueNodes);
        }
        ConfigurationSection locations = section.createSection("locations");
        ConfigurationSection locationEntries = locations.createSection("entries");
        snapshot.locations().forEach((name, value) -> {
            ConfigurationSection location = locationEntries.createSection(encodedKey(name));
            location.set("name", name);
            if (snapshot.locationExpirations().containsKey(name))
                location.set("expires-at", snapshot.locationExpirations().get(name));
            location.set("world", value.world());
            location.set("x", value.x());
            location.set("y", value.y());
            location.set("z", value.z());
            location.set("yaw", value.yaw());
            location.set("pitch", value.pitch());
        });
        ConfigurationSection branches = section.createSection("branches");
        ConfigurationSection branchEntries = branches.createSection("entries");
        snapshot.branchRequirements().forEach((name, requirements) -> {
            ConfigurationSection branch = branchEntries.createSection(encodedKey(name));
            branch.set("name", name);
            branch.set("requires", new java.util.ArrayList<>(requirements));
        });
        branches.set("unlocked", new java.util.ArrayList<>(snapshot.unlockedBranches()));
        section.set("damage-tags", new java.util.ArrayList<>(snapshot.damageTags()));
        if (snapshot.parent().isPresent()) {
            write(section.createSection("parent"), snapshot.parent().orElseThrow());
        }
    }

    private static SkillTreeContext.Snapshot read(ConfigurationSection section) throws IOException {
        int format = section.getInt("snapshot-format", 1);
        if (format < 1 || format > SNAPSHOT_FORMAT_VERSION) {
            throw new IOException("unsupported skill-tree snapshot format: " + format);
        }
        Map<String, Long> variableExpirations = new LinkedHashMap<>(), locationExpirations = new LinkedHashMap<>();
        Map<String, Object> variables = new LinkedHashMap<>();
        int[] valueNodes = {0};
        ConfigurationSection variableSection = section.getConfigurationSection("variables");
        if (variableSection != null) {
            if (format == 1) {
                variables.putAll(variableSection.getValues(false));
            } else {
                ConfigurationSection entries = variableSection.getConfigurationSection("entries");
                if (entries != null) {
                    for (String key : entries.getKeys(false)) {
                        ConfigurationSection variable = entries.getConfigurationSection(key);
                        if (variable == null || !variable.contains("name") || !variable.contains("value")) {
                            throw new IOException("invalid skill-tree variable entry: " + key);
                        }
                        String name = variable.getString("name");
                        readExpiration(variable, name, format, variableExpirations);
                        Object value = format == 2 ? variable.get("value")
                                : readVariableValue(variable.getConfigurationSection("value"), 0, valueNodes);
                        if (name == null || name.isBlank()
                                || format < 3 && !isPersistentScalar(value)
                                || variables.putIfAbsent(name, value) != null) {
                            throw new IOException("invalid or duplicate skill-tree variable name: " + name);
                        }
                    }
                }
            }
        }

        Map<String, SkillTreeContext.LocationSnapshot> locations = new LinkedHashMap<>();
        ConfigurationSection locationSection = section.getConfigurationSection("locations");
        if (locationSection != null) {
            ConfigurationSection entries = format == 1 ? locationSection
                    : locationSection.getConfigurationSection("entries");
            if (entries != null) {
                for (String key : entries.getKeys(false)) {
                    ConfigurationSection value = entries.getConfigurationSection(key);
                    if (value == null) continue;
                    String name = format == 1 ? key : value.getString("name");
                    if (name == null || name.isBlank()) {
                        throw new IOException("invalid skill-tree location name: " + key);
                    }
                    readExpiration(value, name, format, locationExpirations);
                    locations.put(name, new SkillTreeContext.LocationSnapshot(
                            value.getString("world"), value.getDouble("x"), value.getDouble("y"),
                            value.getDouble("z"), (float) value.getDouble("yaw"),
                            (float) value.getDouble("pitch")));
                }
            }
        }
        ConfigurationSection parentSection = section.getConfigurationSection("parent");
        Optional<SkillTreeContext.Snapshot> parent = parentSection == null
                ? Optional.empty() : Optional.of(read(parentSection));
        Map<String, java.util.Set<String>> requirements = new LinkedHashMap<>();
        java.util.Set<String> unlocked = new java.util.LinkedHashSet<>();
        ConfigurationSection branchSection = section.getConfigurationSection("branches");
        if (branchSection != null) {
            unlocked.addAll(branchSection.getStringList("unlocked"));
            ConfigurationSection entries = format == 1 ? branchSection
                    : branchSection.getConfigurationSection("entries");
            if (entries != null) {
                for (String key : entries.getKeys(false)) {
                    if (format == 1 && key.equals("unlocked")) continue;
                    ConfigurationSection branch = entries.getConfigurationSection(key);
                    if (branch == null) continue;
                    String name = format == 1 ? key : branch.getString("name");
                    if (name == null || name.isBlank() || requirements.containsKey(name)) {
                        throw new IOException("invalid or duplicate skill-tree branch name: " + name);
                    }
                    requirements.put(name, new java.util.LinkedHashSet<>(branch.getStringList("requires")));
                }
            }
        }
        return new SkillTreeContext.Snapshot(variables, locations, requirements, unlocked,
                new java.util.LinkedHashSet<>(section.getStringList("damage-tags")), parent,
                variableExpirations, locationExpirations);
    }

    private static void validateExpirations(Map<String, Long> expirations, Map<String, ?> values) throws IOException {
        for (var entry : expirations.entrySet()) {
            if (!values.containsKey(entry.getKey()) || entry.getValue() <= 1)
                throw new IOException("invalid variable expiration: " + entry.getKey());
        }
    }

    private static void readExpiration(ConfigurationSection section, String name, int format,
                                       Map<String, Long> expirations) throws IOException {
        if (!section.contains("expires-at")) return;
        Object value = section.get("expires-at");
        if (format < 4 || !(value instanceof Long || value instanceof Integer)
                || ((Number) value).longValue() <= 1 || name == null)
            throw new IOException("invalid variable expiration: " + name);
        expirations.put(name, ((Number) value).longValue());
    }

    private static String encodedKey(String value) {
        return "k" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeVariableValue(ConfigurationSection section, Object value,
                                           int depth, int[] nodes) throws IOException {
        countVariableNode(depth, nodes);
        if (value instanceof String text) {
            section.set("type", "STRING");
            section.set("value", text);
        } else if (value instanceof Boolean bool) {
            section.set("type", "BOOLEAN");
            section.set("value", bool);
        } else if (value instanceof Byte number) {
            section.set("type", "BYTE");
            section.set("value", number.intValue());
        } else if (value instanceof Short number) {
            section.set("type", "SHORT");
            section.set("value", number.intValue());
        } else if (value instanceof Integer number) {
            section.set("type", "INTEGER");
            section.set("value", number);
        } else if (value instanceof Long number) {
            section.set("type", "LONG");
            section.set("value", number);
        } else if (value instanceof Float number && Float.isFinite(number)) {
            section.set("type", "FLOAT");
            section.set("value", number.doubleValue());
        } else if (value instanceof Double number && Double.isFinite(number)) {
            section.set("type", "DOUBLE");
            section.set("value", number);
        } else if (value instanceof List<?> list) {
            writeCollection(section, "LIST", list, depth, nodes);
        } else if (value instanceof java.util.Set<?> set) {
            writeCollection(section, "SET", new java.util.ArrayList<>(set), depth, nodes);
        } else if (value instanceof Map<?, ?> map) {
            if (map.size() > MAX_VARIABLE_COLLECTION_SIZE) {
                throw new IOException("skill-tree variable map exceeds " + MAX_VARIABLE_COLLECTION_SIZE + " entries");
            }
            section.set("type", "MAP");
            ConfigurationSection entries = section.createSection("entries");
            int index = 0;
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IOException("skill-tree variable map keys must be strings");
                }
                ConfigurationSection item = entries.createSection(indexKey(index++));
                item.set("key", key);
                writeVariableValue(item.createSection("value"), entry.getValue(), depth + 1, nodes);
            }
        } else {
            throw new IOException("unsupported skill-tree variable value type: "
                    + (value == null ? "null" : value.getClass().getName()));
        }
    }

    private static void writeCollection(ConfigurationSection section, String type, List<?> values,
                                        int depth, int[] nodes) throws IOException {
        if (values.size() > MAX_VARIABLE_COLLECTION_SIZE) {
            throw new IOException("skill-tree variable collection exceeds "
                    + MAX_VARIABLE_COLLECTION_SIZE + " entries");
        }
        section.set("type", type);
        ConfigurationSection items = section.createSection("items");
        for (int index = 0; index < values.size(); index++) {
            writeVariableValue(items.createSection(indexKey(index)), values.get(index), depth + 1, nodes);
        }
    }

    private static Object readVariableValue(ConfigurationSection section, int depth, int[] nodes)
            throws IOException {
        if (section == null) throw new IOException("missing skill-tree variable value");
        countVariableNode(depth, nodes);
        String type = section.getString("type", "");
        Object raw = section.get("value");
        return switch (type) {
            case "STRING" -> {
                if (!(raw instanceof String)) throw new IOException("invalid string skill-tree variable");
                yield raw;
            }
            case "BOOLEAN" -> {
                if (!(raw instanceof Boolean)) throw new IOException("invalid boolean skill-tree variable");
                yield raw;
            }
            case "BYTE" -> (byte) numericValue(section, raw).intValue();
            case "SHORT" -> (short) numericValue(section, raw).intValue();
            case "INTEGER" -> numericValue(section, raw).intValue();
            case "LONG" -> numericValue(section, raw).longValue();
            case "FLOAT" -> finiteFloat(numericValue(section, raw).floatValue());
            case "DOUBLE" -> finiteDouble(numericValue(section, raw).doubleValue());
            case "LIST" -> readCollection(section.getConfigurationSection("items"), false, depth, nodes);
            case "SET" -> readCollection(section.getConfigurationSection("items"), true, depth, nodes);
            case "MAP" -> readMap(section.getConfigurationSection("entries"), depth, nodes);
            default -> throw new IOException("unsupported skill-tree variable value type: " + type);
        };
    }

    private static Number numericValue(ConfigurationSection section, Object raw) throws IOException {
        if (raw instanceof Number number && Double.isFinite(number.doubleValue())) return number;
        throw new IOException("invalid numeric skill-tree variable of type " + section.getString("type"));
    }

    private static Float finiteFloat(float value) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("non-finite skill-tree float variable");
        return value;
    }

    private static Double finiteDouble(double value) throws IOException {
        if (!Double.isFinite(value)) throw new IOException("non-finite skill-tree double variable");
        return value;
    }

    private static Object readCollection(ConfigurationSection items, boolean asSet,
                                         int depth, int[] nodes) throws IOException {
        if (items == null) throw new IOException("missing skill-tree variable collection");
        var keys = orderedEntryKeys(items);
        if (keys.size() > MAX_VARIABLE_COLLECTION_SIZE) {
            throw new IOException("skill-tree variable collection exceeds "
                    + MAX_VARIABLE_COLLECTION_SIZE + " entries");
        }
        List<Object> values = new java.util.ArrayList<>(keys.size());
        for (String key : keys) {
            values.add(readVariableValue(items.getConfigurationSection(key), depth + 1, nodes));
        }
        if (!asSet) return List.copyOf(values);
        java.util.Set<Object> set = new java.util.LinkedHashSet<>(values);
        if (set.size() != values.size()) throw new IOException("duplicate skill-tree set value");
        return java.util.Set.copyOf(set);
    }

    private static Map<String, Object> readMap(ConfigurationSection entries, int depth, int[] nodes)
            throws IOException {
        if (entries == null) throw new IOException("missing skill-tree variable map");
        var keys = orderedEntryKeys(entries);
        if (keys.size() > MAX_VARIABLE_COLLECTION_SIZE) {
            throw new IOException("skill-tree variable map exceeds " + MAX_VARIABLE_COLLECTION_SIZE + " entries");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : keys) {
            ConfigurationSection entry = entries.getConfigurationSection(key);
            String mapKey = entry == null ? null : entry.getString("key");
            if (mapKey == null || values.containsKey(mapKey)) {
                throw new IOException("invalid or duplicate skill-tree map key: " + mapKey);
            }
            values.put(mapKey, readVariableValue(entry.getConfigurationSection("value"), depth + 1, nodes));
        }
        return Map.copyOf(values);
    }

    private static List<String> orderedEntryKeys(ConfigurationSection section) {
        return section.getKeys(false).stream().sorted().toList();
    }

    private static String indexKey(int index) {
        return String.format(java.util.Locale.ROOT, "%08d", index);
    }

    private static void countVariableNode(int depth, int[] nodes) throws IOException {
        if (depth > MAX_VARIABLE_VALUE_DEPTH || ++nodes[0] > MAX_VARIABLE_VALUE_NODES) {
            throw new IOException("skill-tree variable structure exceeds persistence limits");
        }
    }

    private static boolean isPersistentScalar(Object value) {
        if (value instanceof String || value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) return true;
        if (value instanceof Float number) return Float.isFinite(number);
        if (value instanceof Double number) return Double.isFinite(number);
        return false;
    }
}
