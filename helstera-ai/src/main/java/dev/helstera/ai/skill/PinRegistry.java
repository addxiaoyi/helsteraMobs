package dev.helstera.ai.skill;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Immutable snapshot of named world locations used by Mythic pin conditions and targeters. */
public final class PinRegistry {
    private final Function<String, World> worlds;
    private volatile Map<String, List<Location>> pins = Map.of();

    public PinRegistry() {
        this(Bukkit::getWorld);
    }

    public PinRegistry(Function<String, World> worlds) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
    }

    public synchronized List<String> load(ConfigurationSection root) {
        Map<String, List<Location>> loaded = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        ConfigurationSection section = findSection(root, "pins");
        if (section == null) {
            pins = Map.of();
            return List.of();
        }

        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            List<Location> locations = new ArrayList<>();
            if (value instanceof ConfigurationSection pinSection) {
                Object points = firstValue(pinSection, "locations", "points");
                if (points instanceof List<?> list) {
                    for (Object point : list) {
                        Location location = parsePoint(point);
                        if (location != null) locations.add(location);
                        else warnings.add("Pin '" + key + "' contains an invalid or unloaded location");
                    }
                } else {
                    Location location = parsePoint(pinSection);
                    if (location != null) locations.add(location);
                }
            } else if (value instanceof List<?> list) {
                for (Object point : list) {
                    Location location = parsePoint(point);
                    if (location != null) locations.add(location);
                    else warnings.add("Pin '" + key + "' contains an invalid or unloaded location");
                }
            } else {
                Location location = parsePoint(value);
                if (location != null) locations.add(location);
            }

            if (locations.isEmpty()) {
                warnings.add("Pin '" + key + "' has no valid loaded locations");
                continue;
            }
            String normalized = normalize(key);
            if (loaded.putIfAbsent(normalized, locations.stream().map(Location::clone).toList()) != null) {
                warnings.add("Pin '" + key + "' duplicates a case-insensitive pin name");
            }
        }
        pins = Map.copyOf(loaded);
        return List.copyOf(warnings);
    }

    public List<Location> locations(String name) {
        if (name == null || name.isBlank()) return List.of();
        return pins.getOrDefault(normalize(name), List.of()).stream().map(Location::clone).toList();
    }

    /** Moves a single-point pin. Multi-pins are immutable through this mechanic. */
    public synchronized boolean moveSingle(String name, Location destination) {
        if (name == null || name.isBlank() || destination == null || destination.getWorld() == null
                || !finite(destination.getX(), destination.getY(), destination.getZ(),
                destination.getYaw(), destination.getPitch())) return false;
        String key = normalize(name);
        List<Location> current = pins.get(key);
        if (current == null || current.size() != 1) return false;
        Map<String, List<Location>> updated = new LinkedHashMap<>(pins);
        updated.put(key, List.of(destination.clone()));
        pins = Map.copyOf(updated);
        return true;
    }

    public int size() {
        return pins.size();
    }

    private Location parsePoint(Object value) {
        if (value instanceof ConfigurationSection section) {
            String worldName = stringValue(section, "world");
            World world = worldName == null ? null : worlds.apply(worldName);
            Double x = number(firstValue(section, "x"));
            Double y = number(firstValue(section, "y"));
            Double z = number(firstValue(section, "z"));
            Float yaw = optionalFloat(firstValue(section, "yaw"));
            Float pitch = optionalFloat(firstValue(section, "pitch"));
            return world == null || x == null || y == null || z == null || yaw == null || pitch == null
                    ? null : finite(x, y, z, yaw, pitch) ? new Location(world, x, y, z, yaw, pitch) : null;
        }
        if (value instanceof Map<?, ?> point) {
            Object worldValue = mapValue(point, "world");
            Object xValue = mapValue(point, "x");
            Object yValue = mapValue(point, "y");
            Object zValue = mapValue(point, "z");
            World world = worldValue instanceof String name ? worlds.apply(name) : null;
            Double x = number(xValue);
            Double y = number(yValue);
            Double z = number(zValue);
            Float yaw = optionalFloat(mapValue(point, "yaw"));
            Float pitch = optionalFloat(mapValue(point, "pitch"));
            if (world == null || x == null || y == null || z == null || yaw == null || pitch == null) return null;
            return finite(x, y, z, yaw, pitch) ? new Location(world, x, y, z, yaw, pitch) : null;
        }
        if (!(value instanceof String raw)) return null;
        String[] fields = raw.split(",", -1);
        if (fields.length < 4 || fields.length > 6) return null;
        World world = worlds.apply(fields[0].trim());
        if (world == null) return null;
        try {
            double x = Double.parseDouble(fields[1].trim());
            double y = Double.parseDouble(fields[2].trim());
            double z = Double.parseDouble(fields[3].trim());
            float yaw = fields.length > 4 ? Float.parseFloat(fields[4].trim()) : 0;
            float pitch = fields.length > 5 ? Float.parseFloat(fields[5].trim()) : 0;
            return finite(x, y, z, yaw, pitch) ? new Location(world, x, y, z, yaw, pitch) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean finite(double x, double y, double z, float yaw, float pitch) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Float.isFinite(yaw) && Float.isFinite(pitch);
    }

    private static Double number(Object value) {
        if (!(value instanceof Number number)) return null;
        double parsed = number.doubleValue();
        return Double.isFinite(parsed) ? parsed : null;
    }

    private static Float optionalFloat(Object value) {
        if (value == null) return 0f;
        if (!(value instanceof Number number)) return null;
        float parsed = number.floatValue();
        return Float.isFinite(parsed) ? parsed : null;
    }

    private static ConfigurationSection findSection(ConfigurationSection root, String name) {
        if (root == null) return null;
        if (name.equalsIgnoreCase(root.getName())) return root;
        for (String key : root.getKeys(false)) {
            if (name.equalsIgnoreCase(key)) return root.getConfigurationSection(key);
        }
        return null;
    }

    private static Object firstValue(ConfigurationSection section, String... names) {
        for (String key : section.getKeys(false)) {
            for (String name : names) {
                if (name.equalsIgnoreCase(key)) return section.get(key);
            }
        }
        return null;
    }

    private static String stringValue(ConfigurationSection section, String name) {
        Object value = firstValue(section, name);
        return value instanceof String string ? string : null;
    }

    private static Object mapValue(Map<?, ?> map, String name) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && name.equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
