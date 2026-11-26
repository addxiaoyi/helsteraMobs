package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Real native cost-table comparison and imported persistent-mob restart coverage. */
public final class PathfindingMalusProbe extends JavaPlugin {
    private Plugin runtime;
    private Plugin mythic;
    private Class<?> nativePathType;
    private Class<?> adapter;
    private final List<Map<String, Object>> observations = new ArrayList<>();

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            String stage = getConfig().getString("stage", "write");
            try {
                runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
                mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
                require(runtime != null && mythic != null, "Missing required plugins");
                nativePathType = Class.forName("net.minecraft.world.level.pathfinder.PathType");
                adapter = Class.forName("dev.helstera.runtime.instance.PaperPathfindingMalus", true,
                        runtime.getClass().getClassLoader());
                if (stage.equals("write")) write();
                else if (stage.equals("verify")) verify();
                else throw new IllegalArgumentException("Unknown fixture stage " + stage);
                var output = new YamlConfiguration();
                output.set("stage", stage);
                output.set("observations", observations);
                output.save(getDataFolder().toPath().resolve(stage + "-observations.yml").toFile());
                getLogger().info("MALUS_SUMMARY|stage=" + stage + "|observations=" + observations.size() + "|passed=true");
            } catch (Throwable error) {
                getLogger().severe("MALUS_FAILED|stage=" + stage + "|error=" + error);
                error.printStackTrace();
            }
        }, 100L);
    }

    private void write() throws Exception {
        Location point = Bukkit.getWorlds().getFirst().getSpawnLocation().clone().add(0, 3, 0);
        point.getChunk().getEntities();
        point.getChunk().addPluginChunkTicket(this);
        Object nativeApi = mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
        Entity nativeMob = (Entity) nativeApi.getClass().getMethod("spawnMythicMob", String.class, Location.class)
                .invoke(nativeApi, "MalusNative", point.clone().add(3, 0, 0));
        Entity nativeMap = (Entity) nativeApi.getClass().getMethod("spawnMythicMob", String.class, Location.class)
                .invoke(nativeApi, "MalusMap", point.clone().add(6, 0, 0));
        ArmorStand stand = point.getWorld().spawn(point.clone().add(9, 0, 0), ArmorStand.class);
        try {
            Path source = mythic.getDataFolder().toPath().resolve("Mobs/pathfinding-malus.yml");
            var importerClass = Class.forName("dev.helstera.migration.importer.MythicMobsImporter", true,
                    runtime.getClass().getClassLoader());
            Object importer = importerClass.getConstructor().newInstance();
            @SuppressWarnings("unchecked") List<Map<String, Object>> plans = (List<Map<String, Object>>)
                    importerClass.getMethod("convert", Path.class).invoke(importer, source);
            String mainId = null;
            for (var plan : plans) {
                String target = String.valueOf(plan.get("target-file"));
                if (!target.startsWith("mobs/") || !plan.containsKey("content")) continue;
                var config = YamlConfiguration.loadConfiguration(new java.io.StringReader(plan.get("content").toString()));
                config.set("model", "example/emberling");
                Path output = runtime.getDataFolder().toPath().resolve(target);
                Files.createDirectories(output.getParent());
                config.save(output.toFile());
                if (config.contains("ai.pathfinding-malus")) {
                    mainId = output.getFileName().toString().replaceFirst("\\.yml$", "");
                } else {
                    require(config.contains("mob.unsupported.AIPathfindingMalus"), "Map source was not preserved");
                    record("map-preserved", true, true);
                }
            }
            require(mainId != null, "Native list did not import");
            int id = (int) runtime.getClass().getMethod("spawnMobAt", String.class, Location.class, String.class)
                    .invoke(runtime, mainId, point, null);
            Object manager = runtime.getClass().getMethod("instances").invoke(runtime);
            Object instance = manager.getClass().getMethod("impl", int.class).invoke(manager, id);
            require(instance != null, "Imported mob failed to spawn");
            Entity imported = (Entity) ((Optional<?>) instance.getClass().getMethod("baseEntity").invoke(instance)).orElseThrow();
            var sourceConfig = YamlConfiguration.loadConfiguration(source.toFile());
            Map<String, Float> expected = new LinkedHashMap<>();
            for (String line : sourceConfig.getStringList("MalusNative.AIPathfindingMalus")) {
                String[] parts = line.split(" ");
                expected.put(parts[0], Float.parseFloat(parts[1]));
            }
            require(expected.size() == 26, "Expected all pinned native PathTypes");
            for (var entry : expected.entrySet()) {
                record("native-" + entry.getKey(), entry.getValue(), cost(nativeMob, entry.getKey()));
                record("imported-" + entry.getKey(), entry.getValue(), cost(imported, entry.getKey()));
            }
            record("native-map-ignored", 8f, cost(nativeMap, "WATER"));
            record("unsupported-entity", false, success(apply(stand, Map.of("WATER", -1))));
            float before = cost(imported, "WATER");
            record("invalid-runtime-rejected", false,
                    success(apply(imported, Map.of("WATER", 8, "DANGER_FIRE", "NaN"))));
            record("invalid-runtime-no-partial-write", before, cost(imported, "WATER"));
            record("fire-rejected", false, success(apply(imported, Map.of("FIRE", 0))));
            record("version-gate", false,
                    adapter.getMethod("supportsVersion", String.class, String.class).invoke(null, "Paper", "1.21.4"));
            var identities = new YamlConfiguration();
            identities.set("runtime", imported.getUniqueId().toString());
            identities.set("costs", expected);
            identities.save(getDataFolder().toPath().resolve("identities.yml").toFile());
        } finally {
            if (nativeMob != null) nativeMob.remove();
            if (nativeMap != null) nativeMap.remove();
            stand.remove();
            point.getChunk().removePluginChunkTicket(this);
        }
    }

    private void verify() throws Exception {
        var identities = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("identities.yml").toFile());
        Entity imported = Bukkit.getEntity(UUID.fromString(identities.getString("runtime")));
        require(imported instanceof Mob, "Saved runtime mob did not restore");
        Object manager = runtime.getClass().getMethod("instances").invoke(runtime);
        @SuppressWarnings("unchecked") var instances = (java.util.Collection<Object>) manager.getClass().getMethod("allImpl").invoke(manager);
        boolean bound = false;
        for (Object instance : instances) {
            var entity = (Optional<?>) instance.getClass().getMethod("baseEntity").invoke(instance);
            if (entity.isPresent() && ((Entity) entity.get()).getUniqueId().equals(imported.getUniqueId())) bound = true;
        }
        record("saved-entity-rebound", true, bound);
        var costs = identities.getConfigurationSection("costs");
        require(costs != null && costs.getKeys(false).size() == 26, "Missing expected costs");
        for (String name : costs.getKeys(false)) record("restart-" + name, (float) costs.getDouble(name), cost(imported, name));
    }

    private Object apply(Entity entity, Object raw) throws Exception {
        return adapter.getMethod("apply", Entity.class, Object.class).invoke(null, entity, raw);
    }

    private boolean success(Object result) throws Exception {
        return (boolean) result.getClass().getMethod("success").invoke(result);
    }

    private float cost(Entity entity, String name) throws Exception {
        require(entity != null, "Native mob missing");
        Object type = null;
        for (Object candidate : nativePathType.getEnumConstants()) {
            if (((Enum<?>) candidate).name().equals(name)) type = candidate;
        }
        require(type != null, "Native PathType missing " + name);
        Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
        return (float) handle.getClass().getMethod("getPathfindingMalus", nativePathType).invoke(handle, type);
    }

    private void record(String id, Object expected, Object actual) {
        boolean matched = expected.equals(actual);
        observations.add(Map.of("id", id, "expected", expected, "actual", actual, "matched", matched));
        require(matched, id + " expected=" + expected + " actual=" + actual);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
