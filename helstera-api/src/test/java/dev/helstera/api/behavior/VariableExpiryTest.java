package dev.helstera.api.behavior;

import dev.helstera.api.util.VariableDuration;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class VariableExpiryTest {
    @TempDir Path directory;

    @Test void deadlinesExpireAtBoundaryWithoutRemovingReplacements() {
        var now = new AtomicLong(1000);
        var variables = new ExpiringVariables<String>(now::get);
        variables.put("short", "value", 1050);
        variables.put("replaced", "old", 1050);
        variables.put("replaced", "new");
        now.set(1049);
        assertEquals("value", variables.get("short"));
        var snapshot = variables.snapshot();
        assertEquals(1050L, snapshot.expirations().get("short"));
        now.set(1050);
        assertNull(variables.get("short"));
        assertFalse(variables.containsKey("short"));
        assertEquals(Map.of("replaced", "new"), Map.copyOf(variables));
        assertNull(variables.remove("short"));
        variables.remove("replaced");
        assertTrue(variables.isEmpty());
        assertEquals("value", snapshot.values().get("short"));
        assertEquals(1050L, snapshot.expirations().get("short"));
    }

    @Test void aliasesRejectMalformedAndOverflowingDurations() {
        for (String alias : List.of("duration", "d", "expire", "e")) {
            var duration = VariableDuration.parse(List.of(alias + "=40"));
            assertEquals(40, duration.ticks());
            long before = System.currentTimeMillis();
            assertTrue(duration.expirationTime() >= before + 2000);
        }
        for (String bad : List.of("", "NaN", "Infinity", "0.5", "<skill.var.Time>", "9223372036854775807"))
            assertThrows(IllegalArgumentException.class, () -> VariableDuration.parse(List.of("duration=" + bad)));
        assertThrows(IllegalArgumentException.class, () -> VariableDuration.parse(List.of("d=1", "expire=2")));
        assertEquals(0, VariableDuration.parse(List.of("d=-1")).expirationTime());
        assertEquals(0, VariableDuration.parse(List.of("d=0")).expirationTime());
    }

    @Test void snapshotsRetainDeadlinesAndDoNotResurrectExpiredValues() throws Exception {
        var tree = new SkillTreeContext(null, List.of());
        long future = System.currentTimeMillis() + 60000;
        tree.setVariable("timed.name", 7, future);
        tree.setVariable("past", 1, 2);
        tree.setLocation("timed.location", new Location(null, 1, 2, 3), future);
        tree.setLocation("past", new Location(null, 1, 2, 3), 2);
        var path = directory.resolve("snapshot.yml");
        SkillTreeSnapshotStore.save(path.toFile(), tree.snapshot());
        var loaded = SkillTreeSnapshotStore.load(path.toFile());
        assertEquals(Map.of("timed.name", future), loaded.variableExpirations());
        assertEquals(Map.of("timed.location", future), loaded.locationExpirations());
        assertFalse(loaded.variables().containsKey("past"));
        assertFalse(loaded.locations().containsKey("past"));
        var restored = SkillTreeContext.restore(loaded, null, List.of(), null);
        assertEquals(7, restored.variable("timed.name").orElseThrow());
        assertEquals(future, restored.variableExpiration("timed.name"));
        assertEquals(future, restored.locationExpiration("timed.location"));
        var expired = new SkillTreeContext.Snapshot(loaded.variables(), loaded.locations(), Map.of(),
                java.util.Set.of(), java.util.Set.of(), java.util.Optional.empty(),
                Map.of("timed.name", 2L), Map.of("timed.location", 2L));
        restored = SkillTreeContext.restore(expired, null, List.of(), null);
        assertTrue(restored.variables().isEmpty());
        assertTrue(restored.locations().isEmpty());
    }

    @Test void malformedDeadlineCannotReplaceExistingSnapshot() throws Exception {
        var tree = new SkillTreeContext(null, List.of());
        tree.setVariable("keep", 7);
        var path = directory.resolve("snapshot.yml");
        SkillTreeSnapshotStore.save(path.toFile(), tree.snapshot());
        byte[] original = java.nio.file.Files.readAllBytes(path);
        var bad = new SkillTreeContext.Snapshot(Map.of("keep", 9), Map.of(), Map.of(),
                java.util.Set.of(), java.util.Set.of(), java.util.Optional.empty(), Map.of("missing", 100L), Map.of());
        assertThrows(java.io.IOException.class, () -> SkillTreeSnapshotStore.save(path.toFile(), bad));
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(path));
        String yaml = java.nio.file.Files.readString(path).replace("name: keep", "name: keep\n      expires-at: 0.5");
        java.nio.file.Files.writeString(path, yaml);
        assertThrows(java.io.IOException.class, () -> SkillTreeSnapshotStore.load(path.toFile()));
    }
}
