package dev.helstera.integrations.region;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
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
    private Method adaptLocation;

    @Override public String pluginName() { return "WorldGuard"; }
    @Override public String supportedVersions() { return "7.x"; }
    @Override public Set<Capability> capabilities() { return Set.of(Capability.REGION_QUERY); }
    @Override public boolean isConnected() { return connected; }
    @Override public String statusReport() { return report; }

    @Override
    public boolean enable(Plugin host) {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
            if (plugin == null || !plugin.isEnabled()) {
                report = "WorldGuard is not installed or enabled.";
                return false;
            }
            Class<?> wgClass = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object wg = wgClass.getMethod("getInstance").invoke(null);
            Object platform = wgClass.getMethod("getPlatform").invoke(wg);
            regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            getRegionManager = regionContainer.getClass().getMethod("get", Class.forName("com.sk89q.worldedit.world.World"));
            Class<?> wgAdapter = Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter");
            adaptLocation = wgAdapter.getMethod("adapt", Location.class);
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

    @Override public void disable() { connected = false; regionContainer = null; }

    @Override
    public Boolean containsRegion(String region, Location location) {
        if (!connected || region == null || region.isBlank() || location == null || location.getWorld() == null) return null;
        try {
            Object adaptedWorld = Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter")
                    .getMethod("adapt", org.bukkit.World.class).invoke(null, location.getWorld());
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
