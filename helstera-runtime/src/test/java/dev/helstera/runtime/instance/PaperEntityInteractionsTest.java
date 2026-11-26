package dev.helstera.runtime.instance;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PaperEntityInteractionsTest {
    public static class NativeLiving {
        int released, stopped, completed;
        boolean fail;
        public void releaseUsingItem() {
            released++;
            if (fail) throw new IllegalStateException("item callback failed");
        }
        public void stopUsingItem() { stopped++; }
        public void completeUsingItem() { completed++; }
    }

    public static class NativeEntity {
        final List<String> calls = new ArrayList<>();
        float yaw = 10, headYaw = 20, bodyYaw = 30, pitch = 40;
        float yRotO = 11, xRotO = 41;
        boolean failBody;
        public void setYRot(float value) { yaw = value; calls.add("yaw"); }
        public void setYHeadRot(float value) { headYaw = value; calls.add("head"); }
        public void setYBodyRot(float value) {
            calls.add("body");
            if (failBody) throw new IllegalStateException("body unavailable");
            bodyYaw = value;
        }
        public void setXRot(float value) { pitch = value; calls.add("pitch"); }
    }

    public static class MissingPitch {
        int writes;
        public void setYRot(float value) { writes++; }
        public void setYHeadRot(float value) { writes++; }
        public void setYBodyRot(float value) { writes++; }
    }

    public static class ChangedPitch extends MissingPitch {
        public boolean setXRot(float value) { return true; }
    }

    public static class StaticRelease {
        static int releases;
        public static void releaseUsingItem() { releases++; }
    }

    public static class ChangedRelease {
        public boolean releaseUsingItem() { return true; }
    }

    @Test void versionGateIsExplicit() {
        assertTrue(PaperEntityInteractions.supportsVersion("Paper", "1.21.1"));
        assertFalse(PaperEntityInteractions.supportsVersion("Paper", "1.21.4"));
        assertFalse(PaperEntityInteractions.supportsVersion("Folia", "1.21.1"));
        assertFalse(PaperEntityInteractions.supportsVersion("Spigot", "1.21.1"));
        assertFalse(PaperEntityInteractions.supportsVersion(null, "1.21.1"));
    }

    @Test void releaseUsesTheNativeReleaseCallbackExactlyOnce() {
        var handle = new NativeLiving();
        var result = PaperEntityInteractions.releaseNative(handle);
        assertTrue(result.success(), result.detail());
        assertEquals(1, result.applied());
        assertEquals(1, handle.released);
        assertEquals(0, handle.stopped);
        assertEquals(0, handle.completed);
        handle.fail = true;
        assertFalse(PaperEntityInteractions.releaseNative(handle).success());
        assertEquals(2, handle.released, "a failed callback must not be retried");
        assertEquals(0, handle.stopped, "failure must not substitute the public clear operation");
    }

    @Test void rotationPreservesUnnormalizedAnglesAndHistoricalFields() {
        var handle = new NativeEntity();
        var result = PaperEntityInteractions.rotateNative(handle, 450, -120);
        assertTrue(result.success(), result.detail());
        assertEquals(4, result.applied());
        assertEquals(List.of("yaw", "head", "body", "pitch"), handle.calls);
        assertEquals(450f, handle.yaw);
        assertEquals(450f, handle.headYaw);
        assertEquals(450f, handle.bodyYaw);
        assertEquals(-120f, handle.pitch);
        assertEquals(11f, handle.yRotO);
        assertEquals(41f, handle.xRotO);
    }

    @Test void invalidAnglesAndMissingOrChangedMethodsCannotPartiallyWrite() {
        var handle = new NativeEntity();
        assertFalse(PaperEntityInteractions.rotateNative(handle, Float.NaN, 2).success());
        assertFalse(PaperEntityInteractions.rotateNative(handle, 1, Float.POSITIVE_INFINITY).success());
        assertTrue(handle.calls.isEmpty());
        var missing = new MissingPitch();
        assertFalse(PaperEntityInteractions.rotateNative(missing, 1, 2).success());
        assertEquals(0, missing.writes);
        var changed = new ChangedPitch();
        assertFalse(PaperEntityInteractions.rotateNative(changed, 1, 2).success());
        assertEquals(0, changed.writes);
        assertFalse(PaperEntityInteractions.rotateNative(null, 1, 2).success());
    }

    @Test void failedNativeRotationReportsCompletedWritesAndStopsTheSequence() {
        var handle = new NativeEntity();
        handle.failBody = true;
        var result = PaperEntityInteractions.rotateNative(handle, 450, 120);
        assertFalse(result.success());
        assertEquals(2, result.applied());
        assertTrue(result.detail().contains("after 2 completed setters"));
        assertEquals(List.of("yaw", "head", "body"), handle.calls);
        assertEquals(40f, handle.pitch);
    }

    @Test void invalidReleaseContractsAndMissingRecipientsReject() {
        StaticRelease.releases = 0;
        assertFalse(PaperEntityInteractions.releaseNative(new StaticRelease()).success());
        assertEquals(0, StaticRelease.releases);
        assertFalse(PaperEntityInteractions.releaseNative(new ChangedRelease()).success());
        assertFalse(PaperEntityInteractions.releaseNative(new Object()).success());
        assertFalse(PaperEntityInteractions.releaseNative(null).success());
        assertFalse(PaperEntityInteractions.releaseUsingItem(null).success());
        assertFalse(PaperEntityInteractions.matchRotation(null, 0, 0).success());
        assertFalse(PaperEntityInteractions.ram(null, null).success());
    }
}
