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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** YAML persistence for SkillTreeContext snapshots; live entities are intentionally excluded. */
public final class SkillTreeSnapshotStore {
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
        ConfigurationSection variables = section.createSection("variables");
        for (var entry : snapshot.variables().entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IOException("unsupported skill-tree variable type for '" + entry.getKey() + "'");
            }
            variables.set(entry.getKey(), value);
        }
        ConfigurationSection locations = section.createSection("locations");
        snapshot.locations().forEach((name, value) -> {
            ConfigurationSection location = locations.createSection(name);
            location.set("world", value.world());
            location.set("x", value.x());
            location.set("y", value.y());
            location.set("z", value.z());
            location.set("yaw", value.yaw());
            location.set("pitch", value.pitch());
        });
        ConfigurationSection branches = section.createSection("branches");
        snapshot.branchRequirements().forEach((name, requirements) -> {
            branches.set(name + ".requires", new java.util.ArrayList<>(requirements));
        });
        branches.set("unlocked", new java.util.ArrayList<>(snapshot.unlockedBranches()));
        section.set("damage-tags", new java.util.ArrayList<>(snapshot.damageTags()));
        if (snapshot.parent().isPresent()) {
            write(section.createSection("parent"), snapshot.parent().orElseThrow());
        }
    }

    private static SkillTreeContext.Snapshot read(ConfigurationSection section) {
        Map<String, Object> variables = new LinkedHashMap<>();
        ConfigurationSection variableSection = section.getConfigurationSection("variables");
        if (variableSection != null) variables.putAll(variableSection.getValues(false));

        Map<String, SkillTreeContext.LocationSnapshot> locations = new LinkedHashMap<>();
        ConfigurationSection locationSection = section.getConfigurationSection("locations");
        if (locationSection != null) {
            for (String name : locationSection.getKeys(false)) {
                ConfigurationSection value = locationSection.getConfigurationSection(name);
                if (value == null) continue;
                locations.put(name, new SkillTreeContext.LocationSnapshot(
                        value.getString("world"), value.getDouble("x"), value.getDouble("y"),
                        value.getDouble("z"), (float) value.getDouble("yaw"),
                        (float) value.getDouble("pitch")));
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
            for (String name : branchSection.getKeys(false)) {
                if (name.equals("unlocked")) continue;
                ConfigurationSection branch = branchSection.getConfigurationSection(name);
                if (branch != null) requirements.put(name, new java.util.LinkedHashSet<>(branch.getStringList("requires")));
            }
        }
        return new SkillTreeContext.Snapshot(variables, locations, requirements, unlocked,
                new java.util.LinkedHashSet<>(section.getStringList("damage-tags")), parent);
    }
}
