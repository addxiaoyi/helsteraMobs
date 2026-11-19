package dev.helstera.api.behavior;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stores persistent location variables for scopes that outlive one skill tree. */
public final class VariableLocations {
    private static final Map<String, ExpiringVariables<Location>> VALUES = new ConcurrentHashMap<>();

    private VariableLocations() {
    }

    public static void set(String scope, String name, BehaviorContext context, Location location) {
        set(scope, name, context, location, 0);
    }

    public static void set(String scope, String name, BehaviorContext context, Location location, long expiresAt) {
        String key = scopeKey(scope, context);
        if (key == null || name == null || name.isBlank() || location == null) return;
        VALUES.computeIfAbsent(key, ignored -> new ExpiringVariables<>()).put(name, location.clone(), expiresAt);
    }

    public static Optional<Location> get(String scope, String name, BehaviorContext context) {
        return getByKey(scopeKey(scope, context), name);
    }

    /** Read the same entity registry when it is acting as a nonliving trigger. */
    public static Optional<Location> get(Entity entity, String name) {
        return getByKey(entityScopeKey(entity), name);
    }

    private static Optional<Location> getByKey(String key, String name) {
        if (key == null || name == null || name.isBlank()) return Optional.empty();
        ExpiringVariables<Location> values = VALUES.get(key);
        Location location = values == null ? null : values.get(name);
        return Optional.ofNullable(location == null ? null : location.clone());
    }

    public static long expirationTime(String scope, String name, BehaviorContext context) {
        String key = scopeKey(scope, context);
        ExpiringVariables<Location> values = key == null ? null : VALUES.get(key);
        return values == null ? 0 : values.expirationTime(name);
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
        VALUES.remove(entityScopeKey(entity));
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
            return context.instance().baseEntity().map(VariableLocations::entityScopeKey).orElse(null);
        }
        if (scope.equals("target")) {
            return context.targetEntity().map(VariableLocations::entityScopeKey).orElse(null);
        }
        if (scope.equals("world")) {
            World world = context.instanceValid()
                    ? context.instance().baseEntity().map(Entity::getWorld).orElse(null) : null;
            if (world == null) world = context.trigger().map(Entity::getWorld).orElse(null);
            // Contexts without entities retain the location-origin extension.
            if (world == null) world = context.origin().map(Location::getWorld).orElse(null);
            return world == null ? null : "world:" + worldIdentity(world);
        }
        return null;
    }

    /** Caster and target are roles; both address the same entity registry. */
    public static String entityScopeKey(Entity entity) {
        return entity == null ? null : "entity:" + entityIdentity(entity);
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
