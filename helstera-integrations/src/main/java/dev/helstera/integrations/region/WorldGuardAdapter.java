package dev.helstera.integrations.region;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
import dev.helstera.api.integration.IntegrationContract;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Set;

/** Optional reflection-only WorldGuard region provider. */
public final class WorldGuardAdapter implements IntegrationAdapter {
    private boolean connected;
    private String report;
    private Object regionContainer;
    private Method getRegionManager;
    private Method getRegion;
    private Method contains;
    private Method adaptWorld;

    @Override public String pluginName() { return "WorldGuard"; }

    static boolean isSupportedVersion(String version) {
        return version != null && version.matches("^7(?:\\.|-|$).*");
    }

    @Override public String supportedVersions() {
        return "7.x API shape probed by reflection; installed-plugin runtime compatibility is not CI-verified";
    }
    @Override public IntegrationContract contract() {
        return new IntegrationContract(pluginName(), supportedVersions(),
                Set.of(Capability.REGION_QUERY),
                Set.of("WorldGuard 7.x", "WorldEdit BukkitAdapter", "RegionManager API"),
                true, true);
    }
    @Override public Set<Capability> capabilities() {
        return connected ? Set.of(Capability.REGION_QUERY) : Set.of();
    }
    @Override public boolean isConnected() { return connected; }
    @Override public String statusReport() { return report; }

    @Override
    public boolean enable(Plugin host) {
        disable();
        report = null;
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
            if (plugin == null || !plugin.isEnabled()) {
                report = "WorldGuard is not installed or enabled.";
                return false;
            }
            String version = plugin.getDescription().getVersion();
            if (!isSupportedVersion(version)) {
                report = "WorldGuard " + version + " is outside the declared 7.x adapter range.";
                return false;
            }
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg = wgClass.getMethod("getInstance").invoke(null);
            Object platform = wgClass.getMethod("getPlatform").invoke(wg);
            regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            getRegionManager = regionContainer.getClass().getMethod("get", Class.forName("com.sk89q.worldedit.world.World"));
            Class<?> worldEditAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            adaptWorld = worldEditAdapter.getMethod("adapt", org.bukkit.World.class);
            Class<?> managerClass = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager");
            getRegion = managerClass.getMethod("getRegion", String.class);
            Class<?> regionClass = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion");
            contains = regionClass.getMethod("contains", int.class, int.class, int.class);
            connected = true;
            report = null;
            host.getLogger().info("WorldGuard region provider enabled.");
            return true;
        } catch (Throwable ex) {
            connected = false;
            report = "WorldGuard region API unavailable: " + ex.getClass().getSimpleName();
            return false;
        }
    }

    @Override public void disable() {
        connected = false;
        regionContainer = null;
        getRegionManager = null;
        getRegion = null;
        contains = null;
        adaptWorld = null;
    }

    @Override
    public Boolean containsRegion(String region, Location location) {
        if (!connected || region == null || region.isBlank() || location == null || location.getWorld() == null) return null;
        try {
            Object adaptedWorld = adaptWorld.invoke(null, location.getWorld());
            Object manager = getRegionManager.invoke(regionContainer, adaptedWorld);
            if (manager == null) return false;
            String raw = region.trim();
            Object protectedRegion = getRegion.invoke(manager, raw);
            if (protectedRegion != null) return (Boolean) contains.invoke(protectedRegion,
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
            if (raw.contains("|")) return anyRegion(manager, raw.split("\\|"), location);
            if (raw.contains("+")) return allRegions(manager, raw.split("\\+"), location);
            return false;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean anyRegion(Object manager, String[] names, Location location) throws Exception {
        for (String name : names) {
            Object candidate = getRegion.invoke(manager, name.trim());
            if (candidate != null && (Boolean) contains.invoke(candidate,
                    location.getBlockX(), location.getBlockY(), location.getBlockZ())) return true;
        }
        return false;
    }

    private boolean allRegions(Object manager, String[] names, Location location) throws Exception {
        boolean found = false;
        for (String name : names) {
            Object candidate = getRegion.invoke(manager, name.trim());
            if (candidate == null) return false;
            found = true;
            if (!((Boolean) contains.invoke(candidate, location.getBlockX(), location.getBlockY(), location.getBlockZ()))) return false;
        }
        return found;
    }
}
