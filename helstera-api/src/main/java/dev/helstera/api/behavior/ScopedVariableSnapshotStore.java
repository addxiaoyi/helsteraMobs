package dev.helstera.api.behavior;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Atomic, typed snapshots of UUID-scoped registries. Retention markers are separate from deadlines. */
public final class ScopedVariableSnapshotStore {
    private ScopedVariableSnapshotStore() { }

    public record Registry(Map<String, Object> variables, Map<String, SkillTreeContext.LocationSnapshot> locations,
                           Map<String, Long> variableRetention, Map<String, Long> locationRetention) {
        public Registry {
            variables = Map.copyOf(variables); locations = Map.copyOf(locations);
            variableRetention = Map.copyOf(variableRetention); locationRetention = Map.copyOf(locationRetention);
        }
        public Registry retained(long now, boolean discardTransient) {
            Map<String, Object> values = new LinkedHashMap<>(variables);
            Map<String, SkillTreeContext.LocationSnapshot> points = new LinkedHashMap<>(locations);
            Map<String, Long> valueTimes = new LinkedHashMap<>(variableRetention), pointTimes = new LinkedHashMap<>(locationRetention);
            prune(values, valueTimes, now, discardTransient); prune(points, pointTimes, now, discardTransient);
            return new Registry(values, points, valueTimes, pointTimes);
        }
        private static void prune(Map<String, ?> values, Map<String, Long> times, long now, boolean discardTransient) {
            times.entrySet().removeIf(entry -> {
                long time = entry.getValue();
                if (values.containsKey(entry.getKey()) && (time > 1 && time <= now || discardTransient && time == 1)) {
                    values.remove(entry.getKey()); return true;
                }
                return false;
            });
        }
    }

    public static void save(File file, Map<String, Registry> registries) throws IOException {
        // Validate every scope before pruning or touching the previous file.
        YamlConfiguration yaml = new YamlConfiguration(); yaml.set("registry-format", 1);
        ConfigurationSection entries = yaml.createSection("registries");
        long now = System.currentTimeMillis();
        int index = 0;
        for (var entry : new TreeMap<>(registries).entrySet()) {
            validateScope(entry.getKey()); validate(entry.getValue());
            Registry registry = entry.getValue().retained(now, entry.getKey().startsWith("world:"));
            if (registry.variables().isEmpty() && registry.locations().isEmpty()) continue;
            ConfigurationSection section = entries.createSection("r" + index++);
            section.set("scope", entry.getKey());
            SkillTreeSnapshotStore.write(section.createSection("snapshot"), new SkillTreeContext.Snapshot(
                    registry.variables(), registry.locations(), Map.of(), Set.of(), Set.of(), Optional.empty()));
            writeRetention(section.createSection("variable-retention"), registry.variableRetention());
            writeRetention(section.createSection("location-retention"), registry.locationRetention());
        }
        Path target = file.toPath().toAbsolutePath(), parent = target.getParent();
        if (parent == null || target.getFileName() == null) throw new IOException("registry path must name a file");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "scoped-variables-", ".tmp");
        try {
            yaml.save(temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public static Map<String, Registry> load(File file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file); }
        catch (org.bukkit.configuration.InvalidConfigurationException e) { throw new IOException("invalid scoped variables", e); }
        if (!Integer.valueOf(1).equals(yaml.get("registry-format"))) throw new IOException("unsupported scoped-variable format");
        ConfigurationSection entries = required(yaml, "registries");
        Map<String, Registry> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String key : entries.getKeys(false)) {
            ConfigurationSection section = required(entries, key);
            String scope = section.getString("scope"); validateScope(scope);
            ConfigurationSection content = required(section, "snapshot");
            if (!Integer.valueOf(4).equals(content.get("snapshot-format"))) throw new IOException("invalid registry snapshot format");
            required(required(content, "variables"), "entries"); required(required(content, "locations"), "entries");
            validateLocationEntries(required(required(content, "locations"), "entries"));
            var snapshot = SkillTreeSnapshotStore.read(content);
            if (snapshot.parent().isPresent() || !snapshot.branchRequirements().isEmpty() || !snapshot.unlockedBranches().isEmpty()
                    || !snapshot.damageTags().isEmpty() || !snapshot.variableExpirations().isEmpty() || !snapshot.locationExpirations().isEmpty())
                throw new IOException("unexpected skill-tree metadata in scoped registry");
            Registry registry = new Registry(snapshot.variables(), snapshot.locations(),
                    readRetention(required(section, "variable-retention")), readRetention(required(section, "location-retention")));
            validate(registry);
            if (result.putIfAbsent(scope, registry.retained(now, false)) != null) throw new IOException("duplicate variable scope: " + scope);
        }
        return Map.copyOf(result);
    }

    private static ConfigurationSection required(ConfigurationSection parent, String name) throws IOException {
        ConfigurationSection section = parent.getConfigurationSection(name);
        if (section == null) throw new IOException("missing registry section: " + name);
        return section;
    }
    private static void validateLocationEntries(ConfigurationSection entries) throws IOException {
        Set<String> names = new HashSet<>();
        for (String key : entries.getKeys(false)) {
            var entry = required(entries, key);
            if (!(entry.get("name") instanceof String name) || name.isBlank() || !names.add(name)
                    || entry.contains("world") && !(entry.get("world") instanceof String))
                throw new IOException("invalid or duplicate registry location");
            for (String axis : List.of("x", "y", "z", "yaw", "pitch")) {
                if (!(entry.get(axis) instanceof Number value) || !Double.isFinite(value.doubleValue()))
                    throw new IOException("invalid registry coordinate: " + axis);
            }
        }
    }
    private static void validateScope(String scope) throws IOException {
        if ("global".equals(scope)) return;
        if (scope == null || !(scope.startsWith("entity:") || scope.startsWith("world:"))) throw new IOException("invalid variable scope: " + scope);
        String id = scope.substring(scope.indexOf(':') + 1);
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { throw new IOException("invalid scope UUID", e); }
    }
    private static void validate(Registry registry) throws IOException {
        for (String key : registry.variables().keySet()) {
            if (key.isBlank() || registry.locations().containsKey(key)) throw new IOException("blank or overlapping variable name");
        }
        for (var entry : registry.locations().entrySet()) {
            var point = entry.getValue();
            if (entry.getKey().isBlank() || !Double.isFinite(point.x()) || !Double.isFinite(point.y()) || !Double.isFinite(point.z())
                    || !Float.isFinite(point.yaw()) || !Float.isFinite(point.pitch())) throw new IOException("invalid variable location");
        }
        if (!registry.variables().keySet().containsAll(registry.variableRetention().keySet())
                || !registry.locations().keySet().containsAll(registry.locationRetention().keySet())) throw new IOException("orphan variable retention metadata");
    }
    private static void writeRetention(ConfigurationSection section, Map<String, Long> times) {
        int index = 0;
        for (var entry : new TreeMap<>(times).entrySet()) {
            var item = section.createSection("r" + index++);
            item.set("name", entry.getKey()); item.set("expires-at", entry.getValue());
        }
    }
    private static Map<String, Long> readRetention(ConfigurationSection section) throws IOException {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            var item = required(section, key); Object name = item.get("name"), time = item.get("expires-at");
            if (!(name instanceof String text) || text.isBlank() || !(time instanceof Integer || time instanceof Long)
                    || result.putIfAbsent(text, ((Number) time).longValue()) != null) throw new IOException("invalid variable retention entry");
        }
        return result;
    }
}
