package dev.helstera.ai.stats;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Small runtime implementation of the MythicMobs custom-stat contract used by
 * native Helstera systems.
 *
 * <p>Stats are configured in {@code stats.yml}. A player or dropper inherits a
 * stat's configured base value unless an entity-local override is present in
 * its PDC. The resolver deliberately chooses the killer first and only uses
 * the dropper when no killer exists, matching the context available to a
 * death-loot evaluation.</p>
 */
public final class MythicStatsService {

    public static final String DEFAULT_STAT = "LOOT_BIAS";
    private static final double DEFAULT_MIN = -100d;
    private static final double DEFAULT_MAX = 1000d;

    /** Immutable definition after configuration validation. */
    public record StatDefinition(String name, boolean enabled, double baseValue,
                                 double minValue, double maxValue) {
    }

    private final String namespace;
    private final Logger log;
    private final Map<String, StatDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, NamespacedKey> keys = new ConcurrentHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public MythicStatsService(String namespace, Logger log) {
        this.namespace = normalizeNamespace(namespace);
        this.log = log;
        definitions.put(DEFAULT_STAT,
                new StatDefinition(DEFAULT_STAT, true, 0d, DEFAULT_MIN, DEFAULT_MAX));
    }

    /** Loads a stats file. A malformed file leaves only the safe default stat. */
    public void loadFile(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try {
            String yaml = Files.readString(path);
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(yaml);
            load(config);
        } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
            resetToDefault();
            throw new IOException("Unable to load stats configuration " + path, ex);
        }
    }

    /**
     * Loads a root configuration. Both {@code stats: {...}} and a direct stat
     * mapping are accepted so imported or embedded configurations remain easy
     * to test.
     */
    public void load(ConfigurationSection root) {
        resetToDefault();
        if (root == null) return;

        ConfigurationSection stats = childSection(root, "stats");
        if (stats == null) stats = root;
        for (String rawName : stats.getKeys(false)) {
            ConfigurationSection section = stats.getConfigurationSection(rawName);
            if (section == null) continue;
            String name = normalizeStatName(rawName);
            if (name == null) {
                warn("忽略无效 stat 名称 " + rawName);
                continue;
            }
            StatDefinition definition = parseDefinition(name, section);
            if (definition != null) definitions.put(name, definition);
        }
    }

    public List<String> warnings() {
        synchronized (warnings) {
            return List.copyOf(warnings);
        }
    }

    public Map<String, StatDefinition> definitions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
    }

    public StatDefinition definition(String stat) {
        String name = normalizeStatName(stat);
        return name == null ? null : definitions.get(name);
    }

    /**
     * Resolves a stat for loot context. Unknown, disabled, malformed, or
     * non-finite values return {@code null}, allowing LootService to fail closed
     * to its documented zero-bias behavior.
     */
    public Double resolve(String stat, org.bukkit.Location location, Player killer, Entity dropper) {
        String name = normalizeStatName(stat);
        StatDefinition definition = name == null ? null : definitions.get(name);
        if (definition == null || !definition.enabled()) return null;

        Entity holder = killer != null ? killer : dropper;
        if (holder == null) return bounded(definition.baseValue(), definition);
        try {
            PersistentDataContainer data = holder.getPersistentDataContainer();
            NamespacedKey key = keyFor(name);
            Double override = data.get(key, PersistentDataType.DOUBLE);
            return bounded(override == null ? definition.baseValue() : override, definition);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Sets an entity-local override, clamping it to the configured stat range. */
    public boolean setEntityStat(Entity entity, String stat, double value) {
        if (entity == null || !Double.isFinite(value)) return false;
        String name = normalizeStatName(stat);
        StatDefinition definition = name == null ? null : definitions.get(name);
        if (definition == null || !definition.enabled()) return false;
        try {
            entity.getPersistentDataContainer().set(keyFor(name), PersistentDataType.DOUBLE,
                    bounded(value, definition));
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Returns the raw entity override, or {@code null} when the entity inherits the base value. */
    public Double entityStatOverride(Entity entity, String stat) {
        if (entity == null) return null;
        String name = normalizeStatName(stat);
        if (name == null || !definitions.containsKey(name)) return null;
        try {
            return entity.getPersistentDataContainer().get(keyFor(name), PersistentDataType.DOUBLE);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public boolean clearEntityStat(Entity entity, String stat) {
        if (entity == null) return false;
        String name = normalizeStatName(stat);
        if (name == null || !definitions.containsKey(name)) return false;
        try {
            entity.getPersistentDataContainer().remove(keyFor(name));
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private StatDefinition parseDefinition(String name, ConfigurationSection section) {
        Object enabledRaw = value(section, "enabled");
        boolean enabled = enabledRaw == null ? true : parseBoolean(enabledRaw);
        if (enabledRaw != null && !(enabledRaw instanceof Boolean)
                && !(enabledRaw instanceof String value
                && ("true".equalsIgnoreCase(value.trim()) || "false".equalsIgnoreCase(value.trim())))) {
            warn("stat " + name + " 的 enabled 无效");
            return null;
        }

        Double base = number(value(section, "base-value", "basevalue", "base"));
        Double min = number(value(section, "min-value", "minvalue", "min"));
        Double max = number(value(section, "max-value", "maxvalue", "max"));
        if (base == null || !Double.isFinite(base)
                || (min != null && !Double.isFinite(min))
                || (max != null && !Double.isFinite(max))) {
            warn("stat " + name + " 的 base/min/max 必须为有限数字");
            return null;
        }
        double lower = min == null ? Double.NEGATIVE_INFINITY : min;
        double upper = max == null ? Double.POSITIVE_INFINITY : max;
        if (lower > upper || base < lower || base > upper) {
            warn("stat " + name + " 的 base/min/max 范围无效");
            return null;
        }
        return new StatDefinition(name, enabled, base, lower, upper);
    }

    private void resetToDefault() {
        definitions.clear();
        synchronized (warnings) {
            warnings.clear();
        }
        definitions.put(DEFAULT_STAT,
                new StatDefinition(DEFAULT_STAT, true, 0d, DEFAULT_MIN, DEFAULT_MAX));
    }

    private void warn(String message) {
        synchronized (warnings) {
            warnings.add(message);
        }
        if (log != null) log.warning("[Stats] " + message);
    }

    private NamespacedKey keyFor(String name) {
        return keys.computeIfAbsent(name, value ->
                new NamespacedKey(namespace, "mythic_stat_" + value.toLowerCase(Locale.ROOT)));
    }

    private static Double bounded(double value, StatDefinition definition) {
        if (!Double.isFinite(value)) return null;
        return Math.max(definition.minValue(), Math.min(definition.maxValue(), value));
    }

    private static ConfigurationSection childSection(ConfigurationSection root, String wanted) {
        for (String key : root.getKeys(false)) {
            if (key.equalsIgnoreCase(wanted)) return root.getConfigurationSection(key);
        }
        return null;
    }

    private static Object value(ConfigurationSection section, String... names) {
        for (String key : section.getKeys(false)) {
            for (String name : names) {
                if (propertyKey(key).equals(propertyKey(name))) return section.get(key);
            }
        }
        return null;
    }

    private static Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean parseBoolean(Object value) {
        return value instanceof Boolean bool
                ? bool
                : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static String normalizeStatName(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z0-9._-]+") ? normalized : null;
    }

    private static String normalizeNamespace(String value) {
        String normalized = value == null ? "helstera" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9._-]+")) return "helstera";
        return normalized;
    }

    private static String propertyKey(String value) {
        return value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "")
                .replace(".", "").replace(" ", "");
    }
}
