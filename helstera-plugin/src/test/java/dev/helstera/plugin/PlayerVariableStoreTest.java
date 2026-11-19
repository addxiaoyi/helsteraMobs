package dev.helstera.plugin;

import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.api.behavior.ScopedVariableSnapshotStore;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.behavior.VariableLocations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerVariableStoreTest {
    @TempDir Path directory;
    @BeforeEach @AfterEach void reset() { SkillExtras.resetRuntimeHooks(); }
    private static String key(UUID id) { return "player:" + id; }
    private static ScopedVariableSnapshotStore.Registry values(Object value) {
        return new ScopedVariableSnapshotStore.Registry(Map.of("Value", value), Map.of(), Map.of("Value", 1L), Map.of());
    }
    @Test void logoutWritesBeforeShutdownAndReleasesOnlyThatPlayersLiveRegistry() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(); var store = new PlayerVariableStore(directory.toFile());
        store.load(a); store.load(b);
        long deadline = System.currentTimeMillis() + 60000;
        var point = new SkillTreeContext.LocationSnapshot("late-world", 1, 2, 3, 0, 0);
        var state = new ScopedVariableSnapshotStore.Registry(Map.of("Rate", 3.25F, "Transient", 7), Map.of("point", point),
                Map.of("Rate", deadline, "Transient", 1L), Map.of("point", -50L));
        SkillExtras.restoreScopedVariables(key(a), state); SkillExtras.restoreScopedVariables(key(b), values("second"));
        SkillExtras.restoreScopedVariables("global", values("global"));
        store.save(a, true);
        assertTrue(SkillExtras.scopedVariableSnapshot(key(a)).variables().isEmpty());
        assertTrue(VariableLocations.snapshotScope(key(a)).values().isEmpty());
        assertEquals("second", SkillExtras.scopedVariableSnapshot(key(b)).variables().get("Value"));
        assertEquals("global", SkillExtras.scopedVariableSnapshot("global").variables().get("Value"));
        assertEquals(state, ScopedVariableSnapshotStore.load(store.file(a)).get(key(a)));
        store.load(a);
        assertEquals(state, SkillExtras.scopedVariableSnapshot(key(a)));
        assertEquals(Set.of(a, b), store.loadedPlayers());
    }
    @Test void failedQuitWriteKeepsLiveStateAndReconnectDoesNotRestoreStaleDisk() throws Exception {
        UUID id = UUID.randomUUID(); var store = new PlayerVariableStore(directory.toFile()); store.load(id);
        SkillExtras.restoreScopedVariables(key(id), values("old")); store.save(id, false);
        byte[] old = Files.readAllBytes(store.file(id).toPath());
        Object unsupported = new Object(); SkillExtras.restoreScopedVariables(key(id), values(unsupported));
        assertThrows(java.io.IOException.class, () -> store.save(id, true));
        assertArrayEquals(old, Files.readAllBytes(store.file(id).toPath()));
        store.load(id);
        assertSame(unsupported, SkillExtras.scopedVariableSnapshot(key(id)).variables().get("Value"));
        SkillExtras.restoreScopedVariables(key(id), values("fixed")); store.save(id, true); store.load(id);
        assertEquals("fixed", SkillExtras.scopedVariableSnapshot(key(id)).variables().get("Value"));
    }
    @Test void corruptOrForeignPlayerFileCannotOverwriteDiskOrContaminateAnotherScope() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(); var store = new PlayerVariableStore(directory.toFile());
        store.load(b); SkillExtras.restoreScopedVariables(key(b), values("other"));
        for (boolean malformed : List.of(true, false)) {
            if (malformed) Files.writeString(store.file(a).toPath(), "registry-format: [invalid]\n");
            else ScopedVariableSnapshotStore.save(store.file(a), Map.of(key(b), values("foreign")));
            byte[] original = Files.readAllBytes(store.file(a).toPath());
            assertThrows(java.io.IOException.class, () -> store.load(a));
            SkillExtras.restoreScopedVariables(key(a), values("new runtime data")); store.save(a, true);
            assertArrayEquals(original, Files.readAllBytes(store.file(a).toPath()));
            assertEquals("other", SkillExtras.scopedVariableSnapshot(key(b)).variables().get("Value"));
        }
    }
    @Test void legacyMigrationIsCommittedBeforeRemovalAndEmptyPlayerFilePreventsResurrection() throws Exception {
        UUID id = UUID.randomUUID(); String legacy = "entity:" + id; var store = new PlayerVariableStore(directory.toFile());
        SkillExtras.restoreScopedVariables(legacy, values("legacy")); store.load(id);
        assertEquals("legacy", ScopedVariableSnapshotStore.load(store.file(id)).get(key(id)).variables().get("Value"));
        assertTrue(SkillExtras.scopedVariableSnapshot(legacy).variables().isEmpty());
        SkillExtras.restoreScopedVariables(key(id), new ScopedVariableSnapshotStore.Registry(Map.of(), Map.of(), Map.of(), Map.of()));
        store.save(id, true); SkillExtras.restoreScopedVariables(legacy, values("stale main file")); store.load(id);
        assertTrue(SkillExtras.scopedVariableSnapshot(key(id)).variables().isEmpty());
        assertTrue(SkillExtras.scopedVariableSnapshot(legacy).variables().isEmpty());
    }
    @Test void failedMigrationDoesNotRemoveLegacyValues() throws Exception {
        UUID id = UUID.randomUUID(); Path invalid = directory.resolve("not-a-directory"); Files.writeString(invalid, "keep");
        var store = new PlayerVariableStore(invalid.toFile()); SkillExtras.restoreScopedVariables("entity:" + id, values("legacy"));
        assertThrows(java.io.IOException.class, () -> store.load(id));
        assertEquals("legacy", SkillExtras.scopedVariableSnapshot("entity:" + id).variables().get("Value"));
        assertTrue(store.loadedPlayers().isEmpty());
    }
    @Test void shutdownSaveKeepsOnlineValuesAndSharedSnapshotDoesNotDuplicatePlayerData() throws Exception {
        UUID id = UUID.randomUUID(); var store = new PlayerVariableStore(directory.resolve("players").toFile()); store.load(id);
        SkillExtras.restoreScopedVariables(key(id), values(7)); SkillExtras.restoreScopedVariables("global", values(9));
        store.save(id, false);
        assertEquals(7, SkillExtras.scopedVariableSnapshot(key(id)).variables().get("Value"));
        var shared = directory.resolve("shared.yml").toFile(); SkillExtras.saveScopedVariables(shared);
        assertEquals(Set.of("global"), ScopedVariableSnapshotStore.load(shared).keySet());
        SkillExtras.resetRuntimeHooks(); new PlayerVariableStore(directory.resolve("players").toFile()).load(id);
        assertEquals(7, SkillExtras.scopedVariableSnapshot(key(id)).variables().get("Value"));
    }
    @Test void playerRolesUseAStablePlayerKeyAndKeepEntityCleanupWorking() {
        UUID id = UUID.randomUUID(); var player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        org.mockito.Mockito.when(player.getUniqueId()).thenReturn(id);
        assertEquals(key(id), VariableLocations.entityScopeKey(player));
        SkillExtras.restoreScopedVariables(key(id), values(7)); SkillExtras.forgetEntity(player);
        assertTrue(SkillExtras.scopedVariableSnapshot(key(id)).variables().isEmpty());
    }
}
