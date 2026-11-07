package dev.helstera.plugin;

import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.behavior.SkillTreeSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PersistentMobSkillTreeStoreTest {
    @Test
    void isolatesSnapshotsForEntitiesWithTheSameMobId(@TempDir Path directory) throws Exception {
        var store = new PersistentMobSkillTreeStore(directory.toFile());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.save(first, snapshot("first"));
        store.save(second, snapshot("second"));

        assertEquals("first", marker(store.load(first, "probe").orElseThrow()));
        assertEquals("second", marker(store.load(second, "probe").orElseThrow()));
        assertTrue(store.entityFile(first).isFile());
        assertTrue(store.entityFile(second).isFile());
    }

    @Test
    void prefersEntitySnapshotAndFallsBackToLegacyMobSnapshot(@TempDir Path directory) throws Exception {
        var store = new PersistentMobSkillTreeStore(directory.toFile());
        UUID entity = UUID.randomUUID();
        SkillTreeSnapshotStore.save(store.legacyMobFile("probe"), snapshot("legacy"));

        assertEquals("legacy", marker(store.load(entity, "probe").orElseThrow()));
        store.save(entity, snapshot("entity"));
        assertEquals("entity", marker(store.load(entity, "probe").orElseThrow()));
    }

    @Test
    void deletingOneEntityLeavesOtherSnapshotsAndLegacyFallbackIntact(@TempDir Path directory)
            throws Exception {
        var store = new PersistentMobSkillTreeStore(directory.toFile());
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.save(first, snapshot("first"));
        store.save(second, snapshot("second"));
        SkillTreeSnapshotStore.save(store.legacyMobFile("probe"), snapshot("legacy"));

        assertTrue(store.delete(first));
        assertFalse(store.entityFile(first).exists());
        assertEquals("second", marker(store.load(second, "probe").orElseThrow()));
        assertEquals("legacy", marker(store.load(UUID.randomUUID(), "probe").orElseThrow()));
        assertFalse(store.delete(first));
    }

    private static SkillTreeContext.Snapshot snapshot(String marker) {
        var tree = new SkillTreeContext(null, null);
        tree.setVariable("marker", marker);
        return tree.snapshot();
    }

    private static String marker(SkillTreeContext.Snapshot snapshot) {
        return String.valueOf(snapshot.variables().get("marker"));
    }
}
