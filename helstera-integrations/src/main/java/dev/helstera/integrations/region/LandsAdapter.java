package dev.helstera.integrations.region;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
import dev.helstera.api.integration.IntegrationContract;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Set;

/** Reflection-only Lands claim provider. Installed-plugin behavior is not Paper-verified yet. */
public final class LandsAdapter implements IntegrationAdapter {
    private static final int MAX_INDEXED_QUERY_CHUNKS = 4096;
    private static final int MAX_FULL_SCAN_LANDS = 10_000;
    private static final int MAX_FULL_SCAN_CHUNKS = 100_000;

    private boolean connected;
    private String report;
    private Object integration;
    private Method getArea;
    private Method getLandByChunk;
    private Method getLands;
    private Method getChunks;
    private Method getChunkX;
    private Method getChunkZ;

    @Override public String pluginName() { return "Lands"; }

    @Override public String supportedVersions() {
        return "Lands 7.25.14 and 9.0.0 API shapes statically inspected; runtime behavior unverified";
    }

    static boolean isSupportedVersion(String version) {
        if (version == null) return false;
        return version.trim().matches("^(?:7\\.25\\.14|9\\.0\\.0)(?:[-+].*)?$");
    }

    @Override public IntegrationContract contract() {
        return new IntegrationContract(pluginName(), supportedVersions(),
                Set.of(Capability.CLAIM_QUERY),
                Set.of("enabled Lands 7.25.14 or 9.0.0 plugin",
                        "LandsIntegration public lookup API",
                        "Land chunk-coordinate API"), true, true);
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
                report = "Lands is not installed or enabled.";
                return false;
            }
            String version = plugin.getDescription().getVersion();
            if (!isSupportedVersion(version)) {
                report = "Lands " + version + " is outside the statically inspected 7.25.14/9.0.0 API shapes.";
                return false;
            }

            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> integrationClass = Class.forName(
                    "me.angeschossen.lands.api.LandsIntegration", false, loader);
            Class<?> landClass = Class.forName(
                    "me.angeschossen.lands.api.land.Land", false, loader);
            Class<?> chunkClass = Class.forName(
                    "me.angeschossen.lands.api.land.ChunkCoordinate", false, loader);

            Method of = integrationClass.getMethod("of", Plugin.class);
            integration = of.invoke(null, plugin);
            if (integration == null || !integrationClass.isInstance(integration)) {
                throw new IllegalStateException("LandsIntegration.of returned no integration");
            }
            getArea = integrationClass.getMethod("getArea", Location.class);
            getLandByChunk = integrationClass.getMethod("getLandByChunk", World.class, int.class, int.class);
            getLands = integrationClass.getMethod("getLands");
            getChunks = landClass.getMethod("getChunks", World.class);
            getChunkX = chunkClass.getMethod("getX");
            getChunkZ = chunkClass.getMethod("getZ");

            connected = true;
            if (host != null) host.getLogger().info("Lands claim provider enabled (Paper behavior not yet verified).");
            return true;
        } catch (Throwable ex) {
            disable();
            report = "Lands claim API unavailable: " + ex.getClass().getSimpleName();
            return false;
        }
    }

    @Override public void disable() {
        connected = false;
        integration = null;
        getArea = null;
        getLandByChunk = null;
        getLands = null;
        getChunks = null;
        getChunkX = null;
        getChunkZ = null;
    }

    @Override
    public Boolean inClaim(Location location) {
        if (!connected || !validLocation(location)) return null;
        try {
            return getArea.invoke(integration, location) != null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public Boolean nearClaim(Location location, double radius) {
        if (!connected || !validLocation(location) || !Double.isFinite(radius) || radius < 0) return null;

        Boolean inside = inClaim(location);
        if (inside == null || inside) return inside;
        if (radius == 0) return false;

        double radiusSquared = radius * radius;
        if (!Double.isFinite(radiusSquared)) return scanAllLands(location, radiusSquared);

        long minBlockX = floorToLong(location.getX() - radius);
        long maxBlockX = floorToLong(location.getX() + radius);
        long minBlockZ = floorToLong(location.getZ() - radius);
        long maxBlockZ = floorToLong(location.getZ() + radius);
        if (!validBlockRange(minBlockX, maxBlockX) || !validBlockRange(minBlockZ, maxBlockZ)) {
            return scanAllLands(location, radiusSquared);
        }

        long minChunkX = Math.floorDiv(minBlockX, 16L);
        long maxChunkX = Math.floorDiv(maxBlockX, 16L);
        long minChunkZ = Math.floorDiv(minBlockZ, 16L);
        long maxChunkZ = Math.floorDiv(maxBlockZ, 16L);
        long queryCount = chunkQueryCount(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        if (queryCount <= 0) return null;
        if (queryCount > MAX_INDEXED_QUERY_CHUNKS) return scanAllLands(location, radiusSquared);

        World world = location.getWorld();
        int blockX = location.getBlockX();
        int blockZ = location.getBlockZ();
        try {
            for (long chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (long chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    Object land = getLandByChunk.invoke(integration, world, (int) chunkX, (int) chunkZ);
                    if (land != null && chunkDistanceSquared(blockX, blockZ, (int) chunkX, (int) chunkZ)
                            <= radiusSquared) return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Boolean scanAllLands(Location location, double radiusSquared) {
        try {
            Object result = getLands.invoke(integration);
            if (!(result instanceof Iterable<?> lands)) return null;
            int landCount = 0;
            int chunkCount = 0;
            int blockX = location.getBlockX();
            int blockZ = location.getBlockZ();
            World world = location.getWorld();
            for (Object land : lands) {
                if (++landCount > MAX_FULL_SCAN_LANDS) return null;
                if (land == null) continue;
                Object chunksResult = getChunks.invoke(land, world);
                if (!(chunksResult instanceof Iterable<?> chunks)) return null;
                for (Object chunk : chunks) {
                    if (++chunkCount > MAX_FULL_SCAN_CHUNKS) return null;
                    if (chunk == null) return null;
                    int chunkX = ((Number) getChunkX.invoke(chunk)).intValue();
                    int chunkZ = ((Number) getChunkZ.invoke(chunk)).intValue();
                    if (chunkDistanceSquared(blockX, blockZ, chunkX, chunkZ) <= radiusSquared) return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static long chunkQueryCount(long minChunkX, long maxChunkX, long minChunkZ, long maxChunkZ) {
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) return -1;
        try {
            long xCount = Math.addExact(Math.subtractExact(maxChunkX, minChunkX), 1);
            long zCount = Math.addExact(Math.subtractExact(maxChunkZ, minChunkZ), 1);
            return Math.multiplyExact(xCount, zCount);
        } catch (ArithmeticException ignored) {
            return -1;
        }
    }

    static double chunkDistanceSquared(int blockX, int blockZ, int chunkX, int chunkZ) {
        long minX = (long) chunkX * 16L;
        long maxX = minX + 15L;
        long minZ = (long) chunkZ * 16L;
        long maxZ = minZ + 15L;
        long dx = blockX < minX ? minX - blockX : blockX > maxX ? blockX - maxX : 0;
        long dz = blockZ < minZ ? minZ - blockZ : blockZ > maxZ ? blockZ - maxZ : 0;
        return (double) dx * dx + (double) dz * dz;
    }

    private static boolean validLocation(Location location) {
        return location != null && location.getWorld() != null
                && Double.isFinite(location.getX()) && Double.isFinite(location.getZ())
                && location.getX() >= Integer.MIN_VALUE && location.getX() <= Integer.MAX_VALUE
                && location.getZ() >= Integer.MIN_VALUE && location.getZ() <= Integer.MAX_VALUE;
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
