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
    /** Immutable persistence form for skill-tree-local state. Entity references are restored by the caller. */
    public record Snapshot(Map<String, Object> variables,
                           Map<String, LocationSnapshot> locations,
                           Map<String, Set<String>> branchRequirements,
                           Set<String> unlockedBranches,
                           Optional<Snapshot> parent) {
        public Snapshot {
            variables = Map.copyOf(variables == null ? Map.of() : variables);
            locations = Map.copyOf(locations == null ? Map.of() : locations);
            branchRequirements = copyRequirements(branchRequirements);
            unlockedBranches = Set.copyOf(unlockedBranches == null ? Set.of() : unlockedBranches);
            parent = parent == null ? Optional.empty() : parent;
        }

        private static Map<String, Set<String>> copyRequirements(Map<String, Set<String>> value) {
            Map<String, Set<String>> copy = new java.util.LinkedHashMap<>();
            if (value != null) value.forEach((key, deps) -> copy.put(key, Set.copyOf(deps == null ? Set.of() : deps)));
            return Map.copyOf(copy);
        }
    }

    /** Location data that does not retain a live Bukkit world reference. */
    public record LocationSnapshot(String world, double x, double y, double z,
                                   float yaw, float pitch) {
    }
    private final Entity trigger;
    private final SkillTreeContext parent;
    private final ConcurrentHashMap<String, Object> variables = new ConcurrentHashMap<>();
    private volatile List<LivingEntity> targets;
    private volatile boolean targetsSet;
    private final ConcurrentHashMap<String, Location> locations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> branchRequirements = new ConcurrentHashMap<>();
    private final java.util.Set<String> unlockedBranches = ConcurrentHashMap.newKeySet();

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
        Map<String, Object> savedVariables = new java.util.LinkedHashMap<>();
        variables.forEach((name, value) -> {
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalStateException("unsupported skill-tree variable type for '" + name + "'");
            }
            savedVariables.put(name, value);
        });
        Map<String, LocationSnapshot> savedLocations = new java.util.LinkedHashMap<>();
        locations.forEach((name, location) -> savedLocations.put(name, toSnapshot(location)));
        return new Snapshot(savedVariables, savedLocations, branchRequirements, unlockedBranches,
                parent == null ? Optional.empty() : Optional.of(parent.snapshot()));
    }

    /** Restores state while allowing the caller to resolve current trigger and target entities. */
    public static SkillTreeContext restore(Snapshot snapshot, Entity trigger,
                                           List<? extends LivingEntity> targets,
                                           Function<String, org.bukkit.World> worldResolver) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        SkillTreeContext parent = snapshot.parent()
                .map(value -> restore(value, trigger, targets, worldResolver)).orElse(null);
        SkillTreeContext restored = new SkillTreeContext(trigger, targets, null, parent);
        restored.variables.putAll(snapshot.variables());
        snapshot.branchRequirements().forEach(restored.branchRequirements::put);
        restored.unlockedBranches.addAll(snapshot.unlockedBranches());
        snapshot.locations().forEach((name, location) ->
                restored.locations.put(name, fromSnapshot(location, worldResolver)));
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

