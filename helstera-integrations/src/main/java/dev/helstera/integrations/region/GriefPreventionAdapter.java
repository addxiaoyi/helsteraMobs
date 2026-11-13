package dev.helstera.integrations.region;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
import dev.helstera.api.integration.IntegrationContract;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Reflection-only GriefPrevention claim provider. */
public final class GriefPreventionAdapter implements IntegrationAdapter {
    private static final int MAX_NEAR_QUERY_CHUNKS = 4096;

    private boolean connected;
    private String report;
    private Object dataStore;
    private Method getClaimAt;
    private Method getClaimsInChunk;
    private Method getAllClaims;
    private Method lesserBoundary;
    private Method greaterBoundary;

    @Override public String pluginName() { return "GriefPrevention"; }

    @Override public String supportedVersions() {
        return "GriefPrevention 16.18.4-16.18.5 API shape; reflected DataStore and Claim methods";
    }

    static boolean isSupportedVersion(String version) {
        if (version == null) return false;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matcher(version.trim());
        if (!matcher.matches()) return false;
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = Integer.parseInt(matcher.group(3));
            return major == 16 && minor == 18 && patch >= 4 && patch <= 5;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    @Override public IntegrationContract contract() {
        return new IntegrationContract(pluginName(), supportedVersions(),
                Set.of(Capability.CLAIM_QUERY),
                Set.of("enabled GriefPrevention plugin", "DataStore claim lookup API",
                        "Claim lesser/greater boundary API"), true, true);
    }

    @Override public Set<Capability> capabilities() {
        return connected ? Set.of(Capability.CLAIM_QUERY) : Set.of();
    }

    @Override public boolean isConnected() { return connected; }

    @Override public String statusReport() { return report; }

    @Override
    public boolean enable(Plugin host) {
        disable();
        report = null;
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName());
            if (plugin == null || !plugin.isEnabled()) {
                report = "GriefPrevention is not installed or enabled.";
                return false;
            }
            String version = plugin.getDescription().getVersion();
            if (!isSupportedVersion(version)) {
                report = "GriefPrevention " + version + " is outside the verified 16.18.4-16.18.5 API range.";
                return false;
            }

            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> pluginClass = Class.forName(
                    "me.ryanhamshire.GriefPrevention.GriefPrevention", false, loader);
            Class<?> dataStoreClass = Class.forName(
                    "me.ryanhamshire.GriefPrevention.DataStore", false, loader);
            Class<?> claimClass = Class.forName(
                    "me.ryanhamshire.GriefPrevention.Claim", false, loader);

            Field dataStoreField = pluginClass.getField("dataStore");
            dataStore = dataStoreField.get(plugin);
            if (dataStore == null || !dataStoreClass.isInstance(dataStore)) {
                throw new IllegalStateException("GriefPrevention dataStore is not initialized");
            }
            getClaimAt = dataStoreClass.getMethod(
                    "getClaimAt", Location.class, boolean.class, claimClass);
            getClaimsInChunk = dataStoreClass.getMethod("getClaims", int.class, int.class);
            getAllClaims = dataStoreClass.getMethod("getClaims");
            lesserBoundary = claimClass.getMethod("getLesserBoundaryCorner");
            greaterBoundary = claimClass.getMethod("getGreaterBoundaryCorner");

            connected = true;
            if (host != null) host.getLogger().info("GriefPrevention claim provider enabled.");
            return true;
        } catch (Throwable ex) {
            disable();
            report = "GriefPrevention claim API unavailable: " + ex.getClass().getSimpleName();
            return false;
        }
    }

    @Override
    public void disable() {
        connected = false;
        dataStore = null;
        getClaimAt = null;
        getClaimsInChunk = null;
        getAllClaims = null;
        lesserBoundary = null;
        greaterBoundary = null;
    }

    @Override
    public Boolean inClaim(Location location) {
        if (!connected || !validLocation(location)) return null;
        try {
            return getClaimAt.invoke(dataStore, location, true, null) != null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public Boolean nearClaim(Location location, double radius) {
        if (!connected || !validLocation(location) || !Double.isFinite(radius) || radius < 0) return null;

        Boolean inside = inClaim(location);
        if (Boolean.TRUE.equals(inside)) return true;
        if (radius == 0) return inside;

        long minBlockX = floorToLong(location.getX() - radius);
        long maxBlockX = floorToLong(location.getX() + radius);
        long minBlockZ = floorToLong(location.getZ() - radius);
        long maxBlockZ = floorToLong(location.getZ() + radius);
        if (!validBlockRange(minBlockX, maxBlockX) || !validBlockRange(minBlockZ, maxBlockZ)) {
            return checkAllClaims(location, radius * radius);
        }

        long minChunkX = Math.floorDiv(minBlockX, 16L);
        long maxChunkX = Math.floorDiv(maxBlockX, 16L);
        long minChunkZ = Math.floorDiv(minBlockZ, 16L);
        long maxChunkZ = Math.floorDiv(maxBlockZ, 16L);
        long queryCount = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        double radiusSquared = radius * radius;
        try {
            if (queryCount <= 0) return null;
            if (queryCount > MAX_NEAR_QUERY_CHUNKS) {
                return checkAllClaims(location, radiusSquared);
            }
            for (long chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (long chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    Object result = getClaimsInChunk.invoke(dataStore, (int) chunkX, (int) chunkZ);
                    if (!(result instanceof Iterable<?> claims)) return null;
                    for (Object claim : claims) {
                        if (claim == null || !visited.add(claim)) continue;
                        if (claimWithinRadius(claim, location, radiusSquared)) return true;
                    }
                }
            }
            return false;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Boolean checkAllClaims(Location location, double radiusSquared) {
        try {
            Object result = getAllClaims.invoke(dataStore);
            if (!(result instanceof Iterable<?> claims)) return null;
            for (Object claim : claims) {
                if (claim != null && claimWithinRadius(claim, location, radiusSquared)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static double horizontalDistanceSquared(int x, int z,
                                            int minX, int maxX, int minZ, int maxZ) {
        int normalizedMinX = Math.min(minX, maxX);
        int normalizedMaxX = Math.max(minX, maxX);
        int normalizedMinZ = Math.min(minZ, maxZ);
        int normalizedMaxZ = Math.max(minZ, maxZ);
        long dx = x < normalizedMinX ? (long) normalizedMinX - x
                : x > normalizedMaxX ? (long) x - normalizedMaxX : 0;
        long dz = z < normalizedMinZ ? (long) normalizedMinZ - z
                : z > normalizedMaxZ ? (long) z - normalizedMaxZ : 0;
        return (double) dx * dx + (double) dz * dz;
    }

    private boolean claimWithinRadius(Object claim, Location location, double radiusSquared) throws Exception {
        Object lesserResult = lesserBoundary.invoke(claim);
        Object greaterResult = greaterBoundary.invoke(claim);
        if (!(lesserResult instanceof Location lesser)
                || !(greaterResult instanceof Location greater)) throw new IllegalStateException("invalid claim bounds");
        if (!location.getWorld().equals(lesser.getWorld())
                || !location.getWorld().equals(greater.getWorld())) return false;

        int minX = Math.min(lesser.getBlockX(), greater.getBlockX());
        int maxX = Math.max(lesser.getBlockX(), greater.getBlockX());
        int minZ = Math.min(lesser.getBlockZ(), greater.getBlockZ());
        int maxZ = Math.max(lesser.getBlockZ(), greater.getBlockZ());
        return horizontalDistanceSquared(location.getBlockX(), location.getBlockZ(),
                minX, maxX, minZ, maxZ) <= radiusSquared;
    }

    private static boolean validLocation(Location location) {
        return location != null && location.getWorld() != null
                && Double.isFinite(location.getX()) && Double.isFinite(location.getZ());
    }

    private static long floorToLong(double value) {
        if (!Double.isFinite(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return Long.MIN_VALUE;
        }
        return (long) Math.floor(value);
    }

    private static boolean validBlockRange(long min, long max) {
        return min != Long.MIN_VALUE && max != Long.MIN_VALUE
                && min <= max && min >= Integer.MIN_VALUE && max <= Integer.MAX_VALUE;
    }
}
