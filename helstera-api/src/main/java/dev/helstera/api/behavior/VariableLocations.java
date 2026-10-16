package dev.helstera.api.behavior;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stores persistent location variables for scopes that outlive one skill tree. */
public final class VariableLocations {
    private static final Map<String, Map<String, Location>> VALUES = new ConcurrentHashMap<>();

    private VariableLocations() {
    }

    public static void set(String scope, String name, BehaviorContext context, Location location) {
        String key = scopeKey(scope, context);
        if (key == null || name == null || name.isBlank() || location == null) return;
        VALUES.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(name, location.clone());
    }

    public static Optional<Location> get(String scope, String name, BehaviorContext context) {
        String key = scopeKey(scope, context);
        if (key == null || name == null || name.isBlank()) return Optional.empty();
        Location location = VALUES.getOrDefault(key, Map.of()).get(name);
        return Optional.ofNullable(location == null ? null : location.clone());
    }

    public static Optional<Location> unset(String scope, String name, BehaviorContext context) {
        String key = scopeKey(scope, context);
        if (key == null || name == null || name.isBlank()) return Optional.empty();
        Map<String, Location> values = VALUES.get(key);
        if (values == null) return Optional.empty();
        Location removed = values.remove(name);
        if (values.isEmpty()) VALUES.remove(key, values);
        return Optional.ofNullable(removed == null ? null : removed.clone());
    }

    public static void clearEntity(Entity entity) {
        if (entity == null) return;
        String identity = entityIdentity(entity);
        VALUES.remove("caster:" + identity);
        VALUES.remove("target:" + identity);
    }

    public static void clearWorld(World world) {
        if (world == null) return;
        VALUES.remove("world:" + worldIdentity(world));
    }

    public static int scopeCount() {
        return VALUES.size();
    }

    public static String scopeKey(String rawScope, BehaviorContext context) {
        String scope = normalizeScope(rawScope);
        if (scope == null) return null;
        if (scope.equals("skill")) return null;
        if (scope.equals("global")) return "global";
        if (context == null) return null;
        if (scope.equals("caster")) {
            if (!context.instanceValid()) return null;
            String identity = context.instance().baseEntity()
                    .map(VariableLocations::entityIdentity).orElse(null);
            return identity == null ? null : "caster:" + identity;
        }
        if (scope.equals("target")) {
            return context.targetEntity().map(VariableLocations::entityIdentity)
                    .map(identity -> "target:" + identity).orElse(null);
        }
        if (scope.equals("world")) {
            Location origin = context.origin().orElse(null);
            World world = origin == null ? null : origin.getWorld();
            return world == null ? null : "world:" + worldIdentity(world);
        }
        return null;
    }

    public static String normalizeScope(String rawScope) {
        if (rawScope == null || rawScope.isBlank()) return "skill";
        return switch (rawScope.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "skill", "caster", "target", "world", "global" -> rawScope.trim().toLowerCase(java.util.Locale.ROOT);
            default -> null;
        };
    }

    private static String entityIdentity(Entity entity) {
        try {
            UUID uuid = entity.getUniqueId();
            if (uuid != null) return uuid.toString();
        } catch (RuntimeException ignored) {
            // Some test doubles do not implement Bukkit identity methods.
        }
        return Integer.toString(entity.getEntityId());
    }

    private static String worldIdentity(World world) {
        try {
            UUID uid = world.getUID();
            if (uid != null) return uid.toString();
        } catch (RuntimeException ignored) {
            // Fall back to the stable world name for lightweight test worlds.
        }
        return world.getName();
    }
}
