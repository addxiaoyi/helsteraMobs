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
    private static final Map<String, ExpiringVariables<StoredLocation>> VALUES = new ConcurrentHashMap<>();

    private VariableLocations() {
    }

    public static void set(String scope, String name, BehaviorContext context, Location location) {
        set(scope, name, context, location, 0);
    }

    public static void set(String scope, String name, BehaviorContext context, Location location, long expiresAt) {
        if (EntityVariableContext.isDetachedTarget(context, scope)) return;
        var detached = EntityVariableContext.find(context, scope);
        if (detached.isPresent()) {
            if (name != null && !name.isBlank() && location != null) detached.get().setLocation(name, location, expiresAt);
            return;
        }
        String key = scopeKey(scope, context);
        if (key == null || name == null || name.isBlank() || location == null) return;
        VALUES.computeIfAbsent(key, ignored -> new ExpiringVariables<>()).put(name, new StoredLocation(location), expiresAt);
    }

    public static Optional<Location> get(String scope, String name, BehaviorContext context) {
        var detached = EntityVariableContext.find(context, scope);
        if (detached.isPresent()) return detached.get().location(name);
        return getByKey(scopeKey(scope, context), name);
    }

    /** Read the same entity registry when it is acting as a nonliving trigger. */
    public static Optional<Location> get(Entity entity, String name) {
        return getByKey(entityScopeKey(entity), name);
    }

    private static Optional<Location> getByKey(String key, String name) {
        if (key == null || name == null || name.isBlank()) return Optional.empty();
        ExpiringVariables<StoredLocation> values = VALUES.get(key);
        StoredLocation location = values == null ? null : values.get(name);
        return Optional.ofNullable(location == null ? null : location.resolve());
    }

    public static long expirationTime(String scope, String name, BehaviorContext context) {
        var detached = EntityVariableContext.find(context, scope);
        if (detached.isPresent()) return detached.get().locationExpiration(name);
        String key = scopeKey(scope, context);
        ExpiringVariables<StoredLocation> values = key == null ? null : VALUES.get(key);
        return values == null ? 0 : values.expirationTime(name);
    }

    public static Optional<Location> unset(String scope, String name, BehaviorContext context) {
        if (EntityVariableContext.isDetachedTarget(context, scope)) return Optional.empty();
        var detached = EntityVariableContext.find(context, scope);
        if (detached.isPresent()) return detached.get().unsetLocation(name);
        String key = scopeKey(scope, context);
        if (key == null || name == null || name.isBlank()) return Optional.empty();
        Map<String, StoredLocation> values = VALUES.get(key);
        if (values == null) return Optional.empty();
        StoredLocation removed = values.remove(name);
        if (values.isEmpty()) VALUES.remove(key, values);
        return Optional.ofNullable(removed == null ? null : removed.resolve());
    }

    public static void clearEntity(Entity entity) {
        if (entity == null) return;
        VALUES.remove(entityScopeKey(entity));
    }

    public static void clearWorld(World world) {
        if (world == null) return;
        VALUES.remove("world:" + worldIdentity(world));
    }

    /** Coordinates survive unloaded worlds; runtime resolution never substitutes another world. */
    private static final class StoredLocation {
        private final SkillTreeContext.LocationSnapshot snapshot;
        private final java.lang.ref.WeakReference<World> fallback;
        StoredLocation(Location location) {
            World world = location.getWorld();
            snapshot = new SkillTreeContext.LocationSnapshot(world == null ? null : world.getName(),
                    location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            fallback = new java.lang.ref.WeakReference<>(world);
        }
        StoredLocation(SkillTreeContext.LocationSnapshot snapshot) {
            this.snapshot = snapshot; fallback = new java.lang.ref.WeakReference<>(null);
        }
        Location resolve() {
            World world = snapshot.world() == null ? null : org.bukkit.Bukkit.getServer() == null
                    ? fallback.get() : org.bukkit.Bukkit.getWorld(snapshot.world());
            if (snapshot.world() != null && world == null) return null;
            return new Location(world, snapshot.x(), snapshot.y(), snapshot.z(), snapshot.yaw(), snapshot.pitch());
        }
    }

    public static Map<String, ExpiringVariables.State<SkillTreeContext.LocationSnapshot>> snapshots() {
        Map<String, ExpiringVariables.State<SkillTreeContext.LocationSnapshot>> result = new java.util.LinkedHashMap<>();
        VALUES.forEach((key, registry) -> result.put(key, snapshotScope(key)));
        return Map.copyOf(result);
    }

    public static ExpiringVariables.State<SkillTreeContext.LocationSnapshot> snapshotScope(String key) {
        var registry = VALUES.get(key);
        if (registry == null) return new ExpiringVariables.State<>(Map.of(), Map.of());
        var state = registry.snapshotWithRetention();
        Map<String, SkillTreeContext.LocationSnapshot> points = new java.util.LinkedHashMap<>();
        state.values().forEach((name, value) -> points.put(name, value.snapshot));
        return new ExpiringVariables.State<>(Map.copyOf(points), state.expirations());
    }

    public static void restore(Map<String, ScopedVariableSnapshotStore.Registry> registries) {
        Map<String, ExpiringVariables<StoredLocation>> restored = new java.util.LinkedHashMap<>();
        registries.forEach((key, registry) -> {
            var values = restoredLocations(registry);
            if (!values.isEmpty()) restored.put(key, values);
        });
        VALUES.clear(); VALUES.putAll(restored);
    }

    public static void restoreScope(String key, ScopedVariableSnapshotStore.Registry registry) {
        var values = restoredLocations(registry);
        if (values.isEmpty()) VALUES.remove(key); else VALUES.put(key, values);
    }

    private static ExpiringVariables<StoredLocation> restoredLocations(ScopedVariableSnapshotStore.Registry registry) {
        var values = new ExpiringVariables<StoredLocation>();
        registry.locations().forEach((name, location) -> values.put(name, new StoredLocation(location),
                registry.locationRetention().getOrDefault(name, 0L)));
        return values;
    }

    public static void clear() { VALUES.clear(); }

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
        return entity == null ? null : (entity instanceof org.bukkit.entity.Player ? "player:" : "entity:") + entityIdentity(entity);
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
