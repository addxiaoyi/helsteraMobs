package dev.helstera.integrations.region;

import dev.helstera.api.integration.Capability;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandsAdapterContractTest {
    @Test
    void declaresFailClosedClaimLookupWithoutLinkingLandsClasses() {
        var adapter = new LandsAdapter();

        assertEquals("Lands", adapter.pluginName());
        assertTrue(adapter.contract().declares(Capability.CLAIM_QUERY));
        assertTrue(adapter.contract().failClosed());
        assertTrue(adapter.contract().preservesSourceValues());
        assertTrue(adapter.capabilities().isEmpty());
        assertFalse(adapter.isConnected());
    }

    @Test
    void acceptsOnlyStaticallyInspectedApiVersions() {
        assertTrue(LandsAdapter.isSupportedVersion("7.25.14"));
        assertTrue(LandsAdapter.isSupportedVersion("7.25.14-SNAPSHOT"));
        assertTrue(LandsAdapter.isSupportedVersion("9.0.0"));
        assertFalse(LandsAdapter.isSupportedVersion("7.25.13"));
        assertFalse(LandsAdapter.isSupportedVersion("8.0.0"));
        assertFalse(LandsAdapter.isSupportedVersion("9.0.1"));
        assertFalse(LandsAdapter.isSupportedVersion("not-a-version"));
        assertFalse(LandsAdapter.isSupportedVersion(null));
    }

    @Test
    void computesChunkQueryBoundsWithoutOverflow() {
        assertEquals(1, LandsAdapter.chunkQueryCount(0, 0, 0, 0));
        assertEquals(9, LandsAdapter.chunkQueryCount(-1, 1, -1, 1));
        assertEquals(2, LandsAdapter.chunkQueryCount(-2, -1, 4, 4));
        assertEquals(-1, LandsAdapter.chunkQueryCount(Long.MIN_VALUE, Long.MAX_VALUE, 0, 0));
    }

    @Test
    void measuresDistanceToInclusiveChunkBounds() {
        assertEquals(0.0, LandsAdapter.chunkDistanceSquared(0, 0, 0, 0));
        assertEquals(1.0, LandsAdapter.chunkDistanceSquared(16, 0, 0, 0));
        assertEquals(2.0, LandsAdapter.chunkDistanceSquared(16, 16, 0, 0));
        assertEquals(0.0, LandsAdapter.chunkDistanceSquared(-1, -1, -1, -1));
    }

    @Test
    void resolvesMembershipThroughAreaLookupAndFailsClosedOnProviderError() throws Exception {
        FakeProvider provider = new FakeProvider();
        LandsAdapter adapter = connectedAdapter(provider);
        Location location = location(3, 64, 5);

        provider.areaPresent = true;
        assertEquals(Boolean.TRUE, adapter.inClaim(location));
        provider.areaPresent = false;
        assertEquals(Boolean.FALSE, adapter.inClaim(location));
        provider.failArea = true;
        assertNull(adapter.inClaim(location));
        assertNull(adapter.inClaim(null));
    }

    @Test
    void indexedNearClaimUsesExactInclusiveBlockDistance() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.claimedChunks.add("0,0");
        LandsAdapter adapter = connectedAdapter(provider);
        Location outsideClaim = location(16, 200, 0);

        assertEquals(Boolean.FALSE, adapter.nearClaim(outsideClaim, 0));
        assertEquals(Boolean.FALSE, adapter.nearClaim(outsideClaim, 0.99));
        assertEquals(Boolean.TRUE, adapter.nearClaim(outsideClaim, 1));
        assertTrue(provider.indexedQueries > 0);
        assertEquals(0, provider.fullScans);
    }

    @Test
    void indexedProviderFailureReturnsUnknownWithoutClaimingNoMatch() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.failIndexedLookup = true;
        LandsAdapter adapter = connectedAdapter(provider);

        assertNull(adapter.nearClaim(location(16, 64, 0), 1));
        assertTrue(provider.indexedQueries > 0);
        assertEquals(0, provider.fullScans);
    }

    @Test
    void largeRadiusUsesBoundedAllLandChunkFallback() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.lands = List.of(new FakeLand(List.of(new FakeChunk(64, 0))));
        LandsAdapter adapter = connectedAdapter(provider);
        Location origin = location(0, 64, 0);

        assertEquals(Boolean.TRUE, adapter.nearClaim(origin, 1024));
        assertEquals(Boolean.FALSE, adapter.nearClaim(origin, 1023.99));
        assertEquals(0, provider.indexedQueries);
        assertEquals(2, provider.fullScans);
    }

    @Test
    void oversizedLandScanReturnsUnknownInsteadOfAFalseNegative() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.lands = Collections.nCopies(10_001, null);
        LandsAdapter adapter = connectedAdapter(provider);

        assertNull(adapter.nearClaim(location(0, 64, 0), 1024));
        assertEquals(1, provider.fullScans);
    }

    @Test
    void oversizedChunkScanReturnsUnknownInsteadOfAFalseNegative() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.lands = List.of(new FakeLand(
                Collections.nCopies(100_001, new FakeChunk(10_000, 0))));
        LandsAdapter adapter = connectedAdapter(provider);

        assertNull(adapter.nearClaim(location(0, 64, 0), 1024));
        assertEquals(1, provider.fullScans);
    }

    @Test
    void landScanProviderExceptionReturnsUnknown() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.failLands = true;
        LandsAdapter adapter = connectedAdapter(provider);

        assertNull(adapter.nearClaim(location(0, 64, 0), 1024));
        assertEquals(1, provider.fullScans);
    }

    private static LandsAdapter connectedAdapter(FakeProvider provider) throws Exception {
        LandsAdapter adapter = new LandsAdapter();
        setField(adapter, "connected", true);
        setField(adapter, "integration", provider);
        setField(adapter, "getArea", accessibleMethod(FakeProvider.class, "getArea", Location.class));
        setField(adapter, "getLandByChunk", accessibleMethod(
                FakeProvider.class, "getLandByChunk", World.class, int.class, int.class));
        setField(adapter, "getLands", accessibleMethod(FakeProvider.class, "getLands"));
        setField(adapter, "getChunks", accessibleMethod(FakeLand.class, "getChunks", World.class));
        setField(adapter, "getChunkX", accessibleMethod(FakeChunk.class, "getX"));
        setField(adapter, "getChunkZ", accessibleMethod(FakeChunk.class, "getZ"));
        return adapter;
    }

    private static Method accessibleMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws Exception {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = LandsAdapter.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Location location(double x, double y, double z) {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "test";
                    case "getUID" -> new java.util.UUID(0, 1);
                    case "toString" -> "test-world";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        return new Location(world, x, y, z);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    public static final class FakeProvider {
        private boolean areaPresent;
        private boolean failArea;
        private boolean failIndexedLookup;
        private boolean failLands;
        private final Set<String> claimedChunks = new java.util.HashSet<>();
        private Iterable<?> lands = List.of();
        private int indexedQueries;
        private int fullScans;

        public Object getArea(Location location) {
            if (failArea) throw new IllegalStateException("simulated Lands API failure");
            return areaPresent ? this : null;
        }

        public Object getLandByChunk(World world, int chunkX, int chunkZ) {
            indexedQueries++;
            if (failIndexedLookup) throw new IllegalStateException("unexpected indexed lookup");
            return claimedChunks.contains(chunkX + "," + chunkZ) ? this : null;
        }

        public Iterable<?> getLands() {
            fullScans++;
            if (failLands) throw new IllegalStateException("simulated Lands scan failure");
            return lands;
        }
    }

    public static final class FakeLand {
        private final Iterable<?> chunks;

        private FakeLand(Iterable<?> chunks) { this.chunks = chunks; }

        public Iterable<?> getChunks(World world) { return chunks; }
    }

    public static final class FakeChunk {
        private final int x;
        private final int z;

        private FakeChunk(int x, int z) { this.x = x; this.z = z; }

        public int getX() { return x; }
        public int getZ() { return z; }
    }
}
