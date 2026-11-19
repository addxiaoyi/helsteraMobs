package dev.helstera.api.behavior;

import org.bukkit.entity.Entity;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;

/** Per-trigger state shared by nested skills in one Mythic-style skill tree. */
public final class SkillTreeContext {
    private static final int MAX_VARIABLE_COLLECTION_SIZE = 4096;
    private static final int MAX_VARIABLE_VALUE_NODES = 16384;
    private static final int MAX_VARIABLE_VALUE_DEPTH = 16;
    /** Internal location key used by MythicMobs' TrackLocation mechanic and TrackedLocation targeter. */
    public static final String MYTHIC_TRACKED_LOCATION = "mythic.tracked-location";
    /** Internal variable key used by MythicMobs' MythicKeyId skill condition. */
    public static final String MYTHIC_KEY_ID_VARIABLE = "key-id";

    /** Immutable persistence form for skill-tree-local state. Entity references are restored by the caller. */
    public record Snapshot(Map<String, Object> variables,
                           Map<String, LocationSnapshot> locations,
                           Map<String, Set<String>> branchRequirements,
                           Set<String> unlockedBranches,
                           Set<String> damageTags,
                           Optional<Snapshot> parent,
                           Map<String, Long> variableExpirations, Map<String, Long> locationExpirations) {
        public Snapshot(Map<String, Object> variables, Map<String, LocationSnapshot> locations,
                        Map<String, Set<String>> branchRequirements, Set<String> unlockedBranches,
                        Set<String> damageTags, Optional<Snapshot> parent) {
            this(variables, locations, branchRequirements, unlockedBranches, damageTags, parent, Map.of(), Map.of());
        }
        public Snapshot {
            variableExpirations = Map.copyOf(variableExpirations == null ? Map.of() : variableExpirations);
            locationExpirations = Map.copyOf(locationExpirations == null ? Map.of() : locationExpirations);
            Map<String, Object> variableCopy = new java.util.LinkedHashMap<>();
            if (variables != null) {
                variables.forEach((name, value) -> variableCopy.put(name, copyVariableValue(value)));
            }
            variables = Map.copyOf(variableCopy);
            locations = Map.copyOf(locations == null ? Map.of() : locations);
            branchRequirements = copyRequirements(branchRequirements);
            unlockedBranches = Set.copyOf(unlockedBranches == null ? Set.of() : unlockedBranches);
            damageTags = Set.copyOf(damageTags == null ? Set.of() : damageTags);
            parent = parent == null ? Optional.empty() : parent;
        }

        /** Removes elapsed values without resolving worlds or changing surviving deadlines. */
        public Snapshot withoutExpiredValues(long now) {
            if (variableExpirations.isEmpty() && locationExpirations.isEmpty() && parent.isEmpty()) return this;
            Map<String, Object> liveVariables = new java.util.LinkedHashMap<>(variables);
            Map<String, LocationSnapshot> liveLocations = new java.util.LinkedHashMap<>(locations);
            Map<String, Long> liveVariableExpirations = new java.util.LinkedHashMap<>(variableExpirations);
            Map<String, Long> liveLocationExpirations = new java.util.LinkedHashMap<>(locationExpirations);
            removeExpired(liveVariables, liveVariableExpirations, now);
            removeExpired(liveLocations, liveLocationExpirations, now);
            Optional<Snapshot> liveParent = parent.map(value -> value.withoutExpiredValues(now));
            if (liveVariables.size() == variables.size() && liveLocations.size() == locations.size()
                    && liveVariableExpirations.size() == variableExpirations.size()
                    && liveLocationExpirations.size() == locationExpirations.size()
                    && liveParent.equals(parent)) return this;
            return new Snapshot(liveVariables, liveLocations, branchRequirements, unlockedBranches,
                    damageTags, liveParent, liveVariableExpirations, liveLocationExpirations);
        }

        private static void removeExpired(Map<String, ?> values, Map<String, Long> expirations, long now) {
            // Invalid metadata remains visible to the persistence validator.
            expirations.entrySet().removeIf(entry -> {
                if (!values.containsKey(entry.getKey()) || entry.getValue() <= 1 || entry.getValue() > now)
                    return false;
                values.remove(entry.getKey());
                return true;
            });
        }

        private static Map<String, Set<String>> copyRequirements(Map<String, Set<String>> value) {
            Map<String, Set<String>> copy = new java.util.LinkedHashMap<>();
            if (value != null) value.forEach((key, deps) -> copy.put(key, Set.copyOf(deps == null ? Set.of() : deps)));
            return Map.copyOf(copy);
        }

        private static Object copyVariableValue(Object value) {
            if (!isPersistentVariableValue(value, 0, new int[]{0})) return value;
            return immutableVariableCopy(value);
        }

        private static Object immutableVariableCopy(Object value) {
            if (isScalar(value)) return value;
            if (value instanceof List<?> list) return list.stream()
                    .map(Snapshot::immutableVariableCopy).toList();
            if (value instanceof Set<?> set) {
                Set<Object> copy = new java.util.LinkedHashSet<>();
                set.forEach(item -> copy.add(immutableVariableCopy(item)));
                return Set.copyOf(copy);
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new java.util.LinkedHashMap<>();
                map.forEach((key, entryValue) -> copy.put((String) key, immutableVariableCopy(entryValue)));
                return Map.copyOf(copy);
            }
            // Keep invalid explicit snapshots constructible so the persistence
            // boundary can report an IOException without replacing prior data.
            return value;
        }

        private static boolean isScalar(Object value) {
            if (value instanceof String || value instanceof Boolean) return true;
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return true;
            }
            if (value instanceof Float number) return Float.isFinite(number);
            return value instanceof Double number && Double.isFinite(number);
        }

        private static boolean isPersistentVariableValue(Object value, int depth, int[] nodes) {
            if (depth > MAX_VARIABLE_VALUE_DEPTH || ++nodes[0] > MAX_VARIABLE_VALUE_NODES) return false;
            if (isScalar(value)) return true;
            if (value instanceof List<?> list && list.size() <= MAX_VARIABLE_COLLECTION_SIZE) {
                return list.stream().allMatch(item -> isPersistentVariableValue(item, depth + 1, nodes));
            }
            if (value instanceof Set<?> set && set.size() <= MAX_VARIABLE_COLLECTION_SIZE) {
                return set.stream().allMatch(item -> isPersistentVariableValue(item, depth + 1, nodes));
            }
            if (value instanceof Map<?, ?> map && map.size() <= MAX_VARIABLE_COLLECTION_SIZE) {
                return map.entrySet().stream().allMatch(entry -> entry.getKey() instanceof String
                        && isPersistentVariableValue(entry.getValue(), depth + 1, nodes));
            }
            return false;
        }
    }

    /** Location data that does not retain a live Bukkit world reference. */
    public record LocationSnapshot(String world, double x, double y, double z,
                                   float yaw, float pitch) {
    }
    private final Entity trigger;
    private final SkillTreeContext parent;
    private final ExpiringVariables<Object> variables = new ExpiringVariables<>();
    private volatile List<LivingEntity> targets;
    private volatile boolean targetsSet;
    private final ExpiringVariables<Location> locations = new ExpiringVariables<>();
    private final ConcurrentHashMap<String, Set<String>> branchRequirements = new ConcurrentHashMap<>();
    private final java.util.Set<String> unlockedBranches = ConcurrentHashMap.newKeySet();
    private volatile Set<String> damageTags = Set.of();

    public SkillTreeContext(Entity trigger, List<? extends LivingEntity> targets) {
        this(trigger, targets, null, null);
    }

    public SkillTreeContext(Entity trigger, List<? extends LivingEntity> targets, Location origin) {
        this(trigger, targets, origin, null);
    }

    public SkillTreeContext(Entity trigger, List<? extends LivingEntity> targets,
                            Location origin, SkillTreeContext parent) {
        this.trigger = trigger;
        this.parent = parent;
        this.targets = List.copyOf(targets == null ? List.of() : targets);
        this.targetsSet = targets != null && !targets.isEmpty();
        if (origin != null) locations.put("origin", origin.clone());
    }

    public Optional<SkillTreeContext> parent() {
        return Optional.ofNullable(parent);
    }

    public Set<String> damageTags() {
        return damageTags.isEmpty() && parent != null ? parent.damageTags() : damageTags;
    }

    public void damageTags(Set<String> tags) {
        damageTags = Set.copyOf(tags == null ? Set.of() : tags);
    }

    public Optional<Entity> trigger() {
        return Optional.ofNullable(trigger);
    }

    public List<LivingEntity> targets() {
        if (targetsSet || parent == null) return targets;
        return parent.targets();
    }

    public void targets(List<? extends LivingEntity> next) {
        targets = List.copyOf(next == null ? List.of() : next);
        targetsSet = true;
    }

    public Optional<Object> variable(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        Object value = variables.get(name);
        if (value != null) return Optional.of(value);
        return parent == null ? Optional.empty() : parent.variable(name);
    }

    public void setVariable(String name, Object value) {
        if (name == null || name.isBlank() || value == null) return;
        variables.put(name, value);
    }

    public void setVariable(String name, Object value, long expiresAt) {
        if (name == null || name.isBlank() || value == null) return;
        variables.put(name, value, expiresAt);
    }

    public long variableExpiration(String name) {
        return variables.containsKey(name) ? variables.expirationTime(name)
                : parent == null ? 0 : parent.variableExpiration(name);
    }

    public Optional<Object> unsetVariable(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(variables.remove(name));
    }

    public Map<String, Object> variables() {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        if (parent != null) merged.putAll(parent.variables());
        merged.putAll(variables);
        return Map.copyOf(merged);
    }

    public Optional<Location> location(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        Location location = locations.get(name);
        if (location != null) return Optional.of(location.clone());
        return parent == null ? Optional.empty() : parent.location(name);
    }

    public void setLocation(String name, Location location) {
        if (name == null || name.isBlank() || location == null) return;
        locations.put(name, location.clone());
    }

    public void setLocation(String name, Location location, long expiresAt) {
        if (name == null || name.isBlank() || location == null) return;
        locations.put(name, location.clone(), expiresAt);
    }

    public long locationExpiration(String name) {
        return locations.containsKey(name) ? locations.expirationTime(name)
                : parent == null ? 0 : parent.locationExpiration(name);
    }

    public Map<String, Location> locations() {
        Map<String, Location> merged = new java.util.LinkedHashMap<>();
        if (parent != null) merged.putAll(parent.locations());
        locations.forEach((key, value) -> merged.put(key, value.clone()));
        return Map.copyOf(merged);
    }

    public Optional<Location> unsetLocation(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        Location location = locations.remove(name);
        return Optional.ofNullable(location == null ? null : location.clone());
    }

    /** Declares a branch and its prerequisite branches in this tree scope. */
    public void defineBranch(String name, Set<String> prerequisites) {
        if (name == null || name.isBlank()) return;
        branchRequirements.put(name, Set.copyOf(prerequisites == null ? Set.of() : prerequisites));
    }

    /** Unlocks a branch only when every declared prerequisite is already unlocked. */
    public boolean unlockBranch(String name) {
        if (name == null || name.isBlank()) return false;
        Set<String> prerequisites = branchRequirements.get(name);
        if (prerequisites == null && parent != null) return parent.unlockBranch(name);
        if (prerequisites == null) {
            prerequisites = Set.of();
            Set<String> existing = branchRequirements.putIfAbsent(name, prerequisites);
            if (existing != null) prerequisites = existing;
        }
        if (!branchesUnlocked(prerequisites)) return false;
        unlockedBranches.add(name);
        return true;
    }

    public boolean branchUnlocked(String name) {
        if (name == null || name.isBlank()) return false;
        return unlockedBranches.contains(name) || (parent != null && parent.branchUnlocked(name));
    }

    public Set<String> unlockedBranches() {
        Set<String> result = new java.util.LinkedHashSet<>();
        if (parent != null) result.addAll(parent.unlockedBranches());
        result.addAll(unlockedBranches);
        return Set.copyOf(result);
    }

    private boolean branchesUnlocked(Set<String> names) {
        return names.stream().allMatch(this::branchUnlocked);
    }

    /** Captures local state and its parent chain without serializing live entities. */
    public Snapshot snapshot() {
        var variableState = variables.snapshot();
        var locationState = locations.snapshot();
        Map<String, Object> savedVariables = new java.util.LinkedHashMap<>();
        variableState.values().forEach((name, value) -> {
            if (!Snapshot.isPersistentVariableValue(value, 0, new int[]{0})) {
                throw new IllegalStateException("unsupported skill-tree variable type for '" + name + "'");
            }
            savedVariables.put(name, Snapshot.copyVariableValue(value));
        });
        Map<String, LocationSnapshot> savedLocations = new java.util.LinkedHashMap<>();
        locationState.values().forEach((name, location) -> savedLocations.put(name, toSnapshot(location)));
        return new Snapshot(savedVariables, savedLocations, branchRequirements, unlockedBranches, damageTags,
                parent == null ? Optional.empty() : Optional.of(parent.snapshot()),
                variableState.expirations(), locationState.expirations());
    }

    /** Restores state while allowing the caller to resolve current trigger and target entities. */
    public static SkillTreeContext restore(Snapshot snapshot, Entity trigger,
                                           List<? extends LivingEntity> targets,
                                           Function<String, org.bukkit.World> worldResolver) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        SkillTreeContext parent = snapshot.parent()
                .map(value -> restore(value, trigger, targets, worldResolver)).orElse(null);
        SkillTreeContext restored = new SkillTreeContext(trigger, targets, null, parent);
        snapshot.variables().forEach((name, value) -> restored.variables.put(name, value,
                snapshot.variableExpirations().getOrDefault(name, 0L)));
        snapshot.branchRequirements().forEach(restored.branchRequirements::put);
        restored.unlockedBranches.addAll(snapshot.unlockedBranches());
        restored.damageTags = snapshot.damageTags();
        snapshot.locations().forEach((name, location) ->
                restored.locations.put(name, fromSnapshot(location, worldResolver),
                        snapshot.locationExpirations().getOrDefault(name, 0L)));
        return restored;
    }

    private static LocationSnapshot toSnapshot(Location location) {
        org.bukkit.World world = location.getWorld();
        return new LocationSnapshot(world == null ? null : world.getName(), location.getX(),
                location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    private static Location fromSnapshot(LocationSnapshot snapshot,
                                         Function<String, org.bukkit.World> worldResolver) {
        org.bukkit.World world = snapshot.world() == null || worldResolver == null
                ? null : worldResolver.apply(snapshot.world());
        return new Location(world, snapshot.x(), snapshot.y(), snapshot.z(),
                snapshot.yaw(), snapshot.pitch());
    }
}

