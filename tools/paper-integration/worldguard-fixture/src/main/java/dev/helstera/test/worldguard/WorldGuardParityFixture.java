package dev.helstera.test.worldguard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Real-plugin Paper probe for the optional WorldGuard region bridge. */
public final class WorldGuardParityFixture extends JavaPlugin {
    private final Map<String, Boolean> checks = new LinkedHashMap<>();
    private String worldGuardVersion = "missing";
    private String worldEditVersion = "missing";
    private Entity probeEntity;

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTask(this, this::runProbe);
    }

    private void runProbe() {
        Plugin worldGuard = Bukkit.getPluginManager().getPlugin("WorldGuard");
        Plugin worldEdit = Bukkit.getPluginManager().getPlugin("WorldEdit");
        Plugin host = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        if (worldGuard != null) worldGuardVersion = worldGuard.getDescription().getVersion();
        if (worldEdit != null) worldEditVersion = worldEdit.getDescription().getVersion();

        try {
            requireEnabled(worldGuard, "WorldGuard");
            requireEnabled(worldEdit, "WorldEdit");
            requireEnabled(host, "helsteraMobs");

            World world = Bukkit.getWorlds().getFirst();
            int baseX = world.getSpawnLocation().getBlockX() + 64;
            int baseZ = world.getSpawnLocation().getBlockZ() + 64;
            int y = Math.max(8, Math.min(240, world.getSpawnLocation().getBlockY()));
            addRegion(worldGuard, world, "helstera_probe_a", baseX, 0, baseZ,
                    baseX + 10, 255, baseZ + 10);
            addRegion(worldGuard, world, "helstera_probe_b", baseX + 5, 0, baseZ,
                    baseX + 15, 255, baseZ + 10);

            Object registry = call(host, "integrationRegistry");
            Object adapterOptional = call(registry, "get", new Class<?>[]{String.class}, "WorldGuard");
            Object adapter = ((Optional<?>) adapterOptional).orElseThrow(
                    () -> new IllegalStateException("WorldGuard adapter is not registered"));
            boolean connected = (boolean) call(adapter, "isConnected");
            Object capabilities = call(adapter, "capabilities");
            checks.put("provider_connected", connected);
            checks.put("region_query_capability", capabilities.toString().contains("REGION_QUERY"));

            Location aOnly = new Location(world, baseX + 2.5, y, baseZ + 2.5);
            Location bOnly = new Location(world, baseX + 13.5, y, baseZ + 2.5);
            Location overlap = new Location(world, baseX + 7.5, y, baseZ + 2.5);
            Location outside = new Location(world, baseX + 30.5, y, baseZ + 30.5);
            checks.put("simple_inside", query(registry, "helstera_probe_a", aOnly));
            checks.put("simple_outside", !query(registry, "helstera_probe_a", outside));
            checks.put("union_inside_right", query(registry,
                    "helstera_probe_a|helstera_probe_b", bOnly));
            checks.put("union_outside", !query(registry,
                    "helstera_probe_a|helstera_probe_b", outside));
            checks.put("intersection_inside_overlap", query(registry,
                    "helstera_probe_a+helstera_probe_b", overlap));
            checks.put("intersection_outside_overlap", !query(registry,
                    "helstera_probe_a+helstera_probe_b", aOnly));
            checks.put("missing_region_false", !query(registry,
                    "helstera_probe_missing", aOnly));

            probeEntity = world.spawnEntity(aOnly, EntityType.ZOMBIE);
            if (!(probeEntity instanceof LivingEntity target)) {
                throw new IllegalStateException("Could not spawn a living condition target");
            }
            checks.put("imported_region_inside", importedCondition(host,
                    "?Region{r=helstera_probe_a}", target, aOnly));
            checks.put("imported_region_outside", !importedCondition(host,
                    "?Region{r=helstera_probe_a}", target, outside));
            checks.put("imported_not_region_inside", !importedCondition(host,
                    "?NotInRegion{region=helstera_probe_a}", target, aOnly));
            checks.put("imported_not_region_outside", importedCondition(host,
                    "?NotInRegion{region=helstera_probe_a}", target, outside));
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            getLogger().severe("WorldGuard parity probe failed: " + root.getClass().getSimpleName()
                    + ": " + root.getMessage());
        } finally {
            if (probeEntity != null && probeEntity.isValid()) probeEntity.remove();
            logResult();
            Bukkit.shutdown();
        }
    }

    private void addRegion(Plugin worldGuard, World world, String id,
                          int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
            throws Exception {
        ClassLoader loader = worldGuard.getClass().getClassLoader();
        Class<?> worldGuardClass = load(loader, "com.sk89q.worldguard.WorldGuard");
        Object instance = worldGuardClass.getMethod("getInstance").invoke(null);
        Object platform = worldGuardClass.getMethod("getPlatform").invoke(instance);
        Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
        Class<?> worldEditWorld = load(loader, "com.sk89q.worldedit.world.World");
        Object adaptedWorld = load(loader, "com.sk89q.worldedit.bukkit.BukkitAdapter")
                .getMethod("adapt", World.class).invoke(null, world);
        Class<?> containerType = load(loader, "com.sk89q.worldguard.protection.regions.RegionContainer");
        Object manager = containerType.getMethod("get", worldEditWorld).invoke(container, adaptedWorld);
        if (manager == null) throw new IllegalStateException("WorldGuard has no RegionManager for " + world.getName());

        Class<?> vector = load(loader, "com.sk89q.worldedit.math.BlockVector3");
        Method at = vector.getMethod("at", int.class, int.class, int.class);
        Object min = at.invoke(null, minX, minY, minZ);
        Object max = at.invoke(null, maxX, maxY, maxZ);
        Class<?> cuboid = load(loader, "com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion");
        Object region = cuboid.getConstructor(String.class, vector, vector).newInstance(id, min, max);
        Class<?> regionType = load(loader, "com.sk89q.worldguard.protection.regions.ProtectedRegion");
        load(loader, "com.sk89q.worldguard.protection.managers.RegionManager")
                .getMethod("addRegion", regionType).invoke(manager, region);
    }

    private boolean importedCondition(Plugin host, String source, LivingEntity target,
                                      Location location) throws Exception {
        target.teleport(location);
        ClassLoader loader = host.getClass().getClassLoader();
        Class<?> importer = load(loader, "dev.helstera.migration.importer.MmConditions");
        Object mapped = importer.getMethod("translate", String.class).invoke(null, source);
        if (!(boolean) call(mapped, "translated")) {
            throw new IllegalStateException("Mythic condition was not imported: " + source);
        }
        String conditionSpec = (String) call(mapped, "condition");
        Object skills = call(host, "skillService");
        String key = (String) call(skills, "bindCondition", new Class<?>[]{String.class}, conditionSpec);
        if (key == null) throw new IllegalStateException("Imported condition did not bind: " + conditionSpec);

        Class<?> contextType = load(loader, "dev.helstera.api.behavior.BehaviorContext");
        Class<?> treeType = load(loader, "dev.helstera.api.behavior.SkillTreeContext");
        Object tree = treeType.getConstructor(Entity.class, List.class, Location.class)
                .newInstance(target, List.of(target), location);
        Class<?> instanceType = load(loader, "dev.helstera.api.instance.ModelInstance");
        Method contextFactory = contextType.getMethod("continueExecution", instanceType,
                LivingEntity.class, double.class, double.class, long.class, String.class,
                String.class, Entity.class, List.class, Location.class, treeType,
                org.bukkit.event.Cancellable.class);
        Object context = contextFactory.invoke(null, null, target, 1.0, -1.0, 0L,
                "worldguard-fixture", null, target, List.of(target), location, tree, null);
        Object registry = call(host, "behaviorRegistry");
        Class<?> registryType = load(loader, "dev.helstera.api.behavior.BehaviorRegistry");
        return (boolean) registryType.getMethod("testCondition", String.class, contextType)
                .invoke(registry, key, context);
    }

    private boolean query(Object registry, String region, Location location) throws Exception {
        Object value = registry.getClass().getMethod("containsRegion", String.class, Location.class)
                .invoke(registry, region, location);
        return Boolean.TRUE.equals(value);
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    private static Object call(Object target, String method) throws Exception {
        return call(target, method, new Class<?>[0]);
    }

    private static Object call(Object target, String method, Class<?>[] parameterTypes,
                               Object... arguments) throws Exception {
        return target.getClass().getMethod(method, parameterTypes).invoke(target, arguments);
    }

    private static void requireEnabled(Plugin plugin, String name) {
        if (plugin == null || !plugin.isEnabled()) throw new IllegalStateException(name + " is not enabled");
    }

    private void logResult() {
        boolean passed = !checks.isEmpty() && checks.values().stream().allMatch(Boolean::booleanValue);
        StringBuilder result = new StringBuilder("WORLDGUARD_PARITY_PROBE result=")
                .append(passed ? "passed" : "failed")
                .append(" worldguard=").append(worldGuardVersion)
                .append(" worldedit=").append(worldEditVersion);
        checks.forEach((name, value) -> result.append(' ').append(name).append('=').append(value));
        getLogger().info(result.toString());
    }
}
