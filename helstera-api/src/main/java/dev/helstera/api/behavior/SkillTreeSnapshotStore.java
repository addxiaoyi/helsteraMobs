package dev.helstera.api.behavior;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** YAML persistence for SkillTreeContext snapshots; live entities are intentionally excluded. */
public final class SkillTreeSnapshotStore {
    private SkillTreeSnapshotStore() {
    }

    public static void save(File file, SkillTreeContext.Snapshot snapshot) throws IOException {
        if (file == null || snapshot == null) throw new IllegalArgumentException("file and snapshot are required");
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create snapshot directory: " + parent);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        write(yaml, snapshot);
        yaml.save(file);
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

    private static void write(ConfigurationSection section, SkillTreeContext.Snapshot snapshot) {
        ConfigurationSection variables = section.createSection("variables");
        snapshot.variables().forEach(variables::set);
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
        snapshot.parent().ifPresent(parent -> write(section.createSection("parent"), parent));
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
        return new SkillTreeContext.Snapshot(variables, locations, requirements, unlocked, parent);
    }
}
