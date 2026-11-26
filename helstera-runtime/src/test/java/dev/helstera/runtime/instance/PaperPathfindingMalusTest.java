package dev.helstera.runtime.instance;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PaperPathfindingMalusTest {
    public enum NativePathType { WATER, DANGER_FIRE, DAMAGE_FIRE }

    public static class NativeMob {
        final Map<NativePathType, Float> costs = new EnumMap<>(NativePathType.class);
        int writes;
        boolean failSecondWrite;
        boolean failRead;

        public NativeMob() {
            costs.put(NativePathType.WATER, 8f);
            costs.put(NativePathType.DANGER_FIRE, 16f);
            costs.put(NativePathType.DAMAGE_FIRE, 32f);
        }

        public float getPathfindingMalus(NativePathType type) {
            if (failRead) throw new IllegalStateException("unavailable");
            return costs.get(type);
        }

        public void setPathfindingMalus(NativePathType type, float cost) {
            writes++;
            costs.put(type, cost);
            if (failSecondWrite && writes == 2) throw new IllegalStateException("failed after mutation");
        }
    }

    public static class WrongSignatureMob {
        public double getPathfindingMalus(NativePathType type) { return 8; }
        public boolean setPathfindingMalus(NativePathType type, float value) { return true; }
    }

    @Test
    void versionGateExcludesOtherMinecraftVersionsAndServerImplementations() {
        assertTrue(PaperPathfindingMalus.supportsVersion("Paper", "1.21.1"));
        assertFalse(PaperPathfindingMalus.supportsVersion("Paper", "1.21"));
        assertFalse(PaperPathfindingMalus.supportsVersion("Paper", "1.21.4"));
        assertFalse(PaperPathfindingMalus.supportsVersion("Folia", "1.21.1"));
        assertFalse(PaperPathfindingMalus.supportsVersion("Spigot", "1.21.1"));
        assertFalse(PaperPathfindingMalus.supportsVersion(null, "1.21.1"));
    }

    @Test
    void appliesRealFloatCostsThroughGetterAndSetterSignatures() {
        var mob = new NativeMob();
        var result = PaperPathfindingMalus.applyNative(mob, NativePathType.class,
                Map.of("WATER", -1f, "DANGER_FIRE", 0f));
        assertTrue(result.success(), result.detail());
        assertEquals(2, result.applied());
        assertEquals(-1f, mob.costs.get(NativePathType.WATER));
        assertEquals(0f, mob.costs.get(NativePathType.DANGER_FIRE));
        assertEquals(32f, mob.costs.get(NativePathType.DAMAGE_FIRE));
    }

    @Test
    void validatesEveryNativeTypeBeforeFirstWrite() {
        var mob = new NativeMob();
        Map<String, Float> values = new LinkedHashMap<>();
        values.put("WATER", -1f);
        values.put("LAVA", 0f);
        var result = PaperPathfindingMalus.applyNative(mob, NativePathType.class, values);
        assertFalse(result.success());
        assertTrue(result.detail().contains("LAVA"));
        assertEquals(0, mob.writes);
        assertEquals(8f, mob.costs.get(NativePathType.WATER));
    }

    @Test
    void rejectsNonFiniteAndUnknownValuesWithoutNativeAccess() {
        var mob = new NativeMob();
        assertFalse(PaperPathfindingMalus.applyNative(mob, NativePathType.class,
                Map.of("WATER", Float.NaN)).success());
        assertFalse(PaperPathfindingMalus.applyNative(mob, NativePathType.class,
                Map.of("WATER", Float.POSITIVE_INFINITY)).success());
        assertFalse(PaperPathfindingMalus.applyNative(mob, NativePathType.class,
                Map.of("FIRE", 0f)).success());
        assertEquals(0, mob.writes);
    }

    @Test
    void failedSnapshotDoesNotMutateEarlierCosts() {
        var mob = new NativeMob();
        mob.failRead = true;
        var result = PaperPathfindingMalus.applyNative(mob, NativePathType.class, Map.of("WATER", -1f));
        assertFalse(result.success());
        assertEquals(0, mob.writes);
    }

    @Test
    void failedSetterRestoresBothEarlierAndFailingMutations() {
        var mob = new NativeMob();
        mob.failSecondWrite = true;
        var before = new EnumMap<>(mob.costs);
        Map<String, Float> values = new LinkedHashMap<>();
        values.put("WATER", -1f);
        values.put("DANGER_FIRE", 0f);
        var result = PaperPathfindingMalus.applyNative(mob, NativePathType.class, values);
        assertFalse(result.success());
        assertEquals(0, result.applied());
        assertTrue(result.detail().contains("previous costs restored"));
        assertEquals(before, mob.costs);
    }

    @Test
    void rejectsMissingOrChangedNativeContracts() {
        assertFalse(PaperPathfindingMalus.applyNative(new Object(), NativePathType.class,
                Map.of("WATER", -1f)).success());
        assertFalse(PaperPathfindingMalus.applyNative(new NativeMob(), String.class,
                Map.of("WATER", -1f)).success());
        assertFalse(PaperPathfindingMalus.applyNative(new WrongSignatureMob(), NativePathType.class,
                Map.of("WATER", -1f)).success());
        assertFalse(PaperPathfindingMalus.applyNative(null, NativePathType.class,
                Map.of("WATER", -1f)).success());
    }

    @Test
    void publicEntryPointRefusesMalformedConfigAndMissingServer() {
        assertFalse(PaperPathfindingMalus.apply(null, Map.of("WATER", "NaN")).success());
        assertFalse(PaperPathfindingMalus.apply(null, Map.of("WATER", -1f)).success());
        assertTrue(PaperPathfindingMalus.apply(null, Map.of()).success());
    }
}
