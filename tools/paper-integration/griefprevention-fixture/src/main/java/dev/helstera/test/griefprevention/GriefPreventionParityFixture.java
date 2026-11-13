package dev.helstera.test.griefprevention;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Real-plugin Paper probe for imported Mythic claim conditions and the GP adapter. */
public final class GriefPreventionParityFixture extends JavaPlugin {
    private final Map<String, Boolean> checks = new LinkedHashMap<>();
    private String griefPreventionVersion = "missing";
    private Entity probeEntity;

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTask(this, this::runProbe);
    }

    private void runProbe() {
        Plugin griefPrevention = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        Plugin host = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        if (griefPrevention != null) griefPreventionVersion = griefPrevention.getDescription().getVersion();
        try {
            requireEnabled(griefPrevention, "GriefPrevention");
            requireEnabled(host, "helsteraMobs");

            World world = Bukkit.getWorlds().getFirst();
            Location spawn = world.getSpawnLocation();
            int baseX = spawn.getBlockX() + 96;
            int baseZ = spawn.getBlockZ() + 96;
            int y = Math.max(world.getMinHeight() + 8, Math.min(world.getMaxHeight() - 8, spawn.getBlockY()));
            Object mainClaim = createClaim(griefPrevention, world,
                    baseX, baseX + 10, 0, 255, baseZ, baseZ + 10, null);
            createClaim(griefPrevention, world,
                    baseX + 100, baseX + 110, 0, 255, baseZ, baseZ + 10, null);
            createClaim(griefPrevention, world,
                    baseX + 2, baseX + 4, 0, 255, baseZ + 2, baseZ + 4, mainClaim);

            Object registry = call(host, "integrationRegistry");
            Object adapterOptional = call(registry, "get", new Class<?>[]{String.class}, "GriefPrevention");
            Object adapter = ((Optional<?>) adapterOptional).orElseThrow(
                    () -> new IllegalStateException("GriefPrevention adapter is not registered"));
            boolean connected = (boolean) call(adapter, "isConnected");
            Object capabilities = call(adapter, "capabilities");
            checks.put("provider_connected", connected);
            checks.put("claim_query_capability", capabilities.toString().contains("CLAIM_QUERY"));

            Location inside = new Location(world, baseX + 5, y, baseZ + 5);
            Location subclaim = new Location(world, baseX + 3, y, baseZ + 3);
            Location adjacent = new Location(world, baseX + 11, y, baseZ + 5);
            Location outside = new Location(world, baseX + 50, y, baseZ + 50);
            checks.put("in_claim_inside", query(registry, "inClaim", inside));
            checks.put("in_claim_subclaim", query(registry, "inClaim", subclaim));
            checks.put("in_claim_outside", !query(registry, "inClaim", outside));
            checks.put("near_claim_inside", near(registry, inside, 0));
            checks.put("near_claim_exact_block_boundary", near(registry, adjacent, 1));
            checks.put("near_claim_below_boundary", !near(registry, adjacent, 0.99));
            checks.put("near_claim_outside", !near(registry, outside, 16));
            checks.put("near_claim_large_radius_fallback", near(registry, outside, 1024));

            probeEntity = world.spawnEntity(inside, EntityType.ZOMBIE);
            if (!(probeEntity instanceof LivingEntity target)) {
                throw new IllegalStateException("Could not spawn a living condition target");
            }
            checks.put("imported_in_claim_inside", importedCondition(host,
                    "?InClaim", target, inside));
            checks.put("imported_in_claim_outside", !importedCondition(host,
                    "?InClaim", target, outside));
            checks.put("imported_near_claim_default", importedCondition(host,
                    "?NearClaim", target, new Location(world, baseX + 26, y, baseZ + 5)));
            checks.put("imported_near_claim_outside", !importedCondition(host,
                    "?NearClaim", target, new Location(world, baseX + 27, y, baseZ + 5)));
            checks.put("imported_near_claim_explicit", importedCondition(host,
                    "?NearClaim{radius=1}", target, adjacent));
            checks.put("imported_near_claims_alias", importedCondition(host,
                    "?nearClaims{r=20}", target, new Location(world, baseX + 30, y, baseZ + 5)));
            checks.put("malformed_near_claim_preserved", !translated(host,
                    "?NearClaim{radius=NaN}"));
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            getLogger().severe("GriefPrevention parity probe failed: " + root.getClass().getSimpleName()
                    + ": " + root.getMessage());
            checks.put("fixture_exception", false);
        } finally {
            if (probeEntity != null && probeEntity.isValid()) probeEntity.remove();
            logResult();
            Bukkit.shutdown();
        }
    }

    private Object createClaim(Plugin griefPrevention, World world,
                               int x1, int x2, int y1, int y2, int z1, int z2, Object parent)
            throws Exception {
        ClassLoader loader = griefPrevention.getClass().getClassLoader();
        Class<?> claimType = load(loader, "me.ryanhamshire.GriefPrevention.Claim");
        Class<?> dataStoreType = load(loader, "me.ryanhamshire.GriefPrevention.DataStore");
        Field dataStoreField = griefPrevention.getClass().getField("dataStore");
        Object dataStore = dataStoreField.get(griefPrevention);
        Method create = dataStoreType.getMethod("createClaim", World.class,
                int.class, int.class, int.class, int.class, int.class, int.class,
                UUID.class, claimType, Long.class, org.bukkit.entity.Player.class);
        Object result = create.invoke(dataStore, world, x1, x2, y1, y2, z1, z2,
                null, parent, null, null);
        Field succeeded = result.getClass().getField("succeeded");
        if (!(boolean) succeeded.get(result)) throw new IllegalStateException("GriefPrevention refused probe claim");
        Object claim = result.getClass().getField("claim").get(result);
        if (claim == null) throw new IllegalStateException("GriefPrevention returned an empty probe claim");
        return claim;
    }

    private boolean importedCondition(Plugin host, String source, LivingEntity target,
                                      Location location) throws Exception {
        if (!location.getChunk().isLoaded()) location.getChunk().load();
        Entity spawned = location.getWorld().spawnEntity(location, EntityType.ZOMBIE);
        if (!(spawned instanceof LivingEntity subject)) {
            spawned.remove();
            throw new IllegalStateException("Could not spawn condition target at " + location);
        }
        try {
            return evaluateImported(host, source, subject, location);
        } finally {
            if (subject.isValid()) subject.remove();
        }
    }

    private boolean evaluateImported(Plugin host, String source, LivingEntity target,
                                     Location location) throws Exception {
        ClassLoader loader = host.getClass().getClassLoader();
        Class<?> importer = load(loader, "dev.helstera.migration.importer.MmConditions");
        Object mapped = importer.getMethod("translate", String.class).invoke(null, source);
        if (!(boolean) call(mapped, "translated")) {
            throw new IllegalStateException("Mythic condition was not imported: " + source);
        }
        return evaluate(host, (String) call(mapped, "condition"), target, location);
    }

    private boolean translated(Plugin host, String source) throws Exception {
        ClassLoader loader = host.getClass().getClassLoader();
        Object mapped = load(loader, "dev.helstera.migration.importer.MmConditions")
                .getMethod("translate", String.class).invoke(null, source);
        return (boolean) call(mapped, "translated");
    }

    private boolean evaluate(Plugin host, String spec, LivingEntity target, Location location) throws Exception {
        Object skills = call(host, "skillService");
        String key = (String) call(skills, "bindCondition", new Class<?>[]{String.class}, spec);
        if (key == null) throw new IllegalStateException("Imported condition did not bind: " + spec);

        ClassLoader loader = host.getClass().getClassLoader();
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
                "griefprevention-fixture", null, target, List.of(target), location, tree, null);
        Object registry = call(host, "behaviorRegistry");
        Class<?> registryType = load(loader, "dev.helstera.api.behavior.BehaviorRegistry");
        return (boolean) registryType.getMethod("testCondition", String.class, contextType)
                .invoke(registry, key, context);
    }

    private boolean query(Object registry, String method, Location location) throws Exception {
        return Boolean.TRUE.equals(registry.getClass().getMethod(method, Location.class)
                .invoke(registry, location));
    }

    private boolean near(Object registry, Location location, double radius) throws Exception {
        return Boolean.TRUE.equals(registry.getClass().getMethod("nearClaim", Location.class, double.class)
                .invoke(registry, location, radius));
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
        StringBuilder result = new StringBuilder("GRIEFPREVENTION_PARITY_PROBE result=")
                .append(passed ? "passed" : "failed")
                .append(" griefprevention=").append(griefPreventionVersion);
        checks.forEach((name, value) -> result.append(' ').append(name).append('=').append(value));
        getLogger().info(result.toString());
    }
}
