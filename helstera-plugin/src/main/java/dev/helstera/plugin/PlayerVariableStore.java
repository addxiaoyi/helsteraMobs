package dev.helstera.plugin;

import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.api.behavior.ScopedVariableSnapshotStore;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Main-thread player registry lifecycle; each UUID has an atomic file independent of other scopes. */
final class PlayerVariableStore {
    private static final ScopedVariableSnapshotStore.Registry EMPTY = new ScopedVariableSnapshotStore.Registry(Map.of(), Map.of(), Map.of(), Map.of());
    private final File directory;
    private final Set<UUID> loaded = new HashSet<>();
    PlayerVariableStore(File directory) { this.directory = directory; }

    void load(UUID id) throws IOException {
        // A failed quit write keeps its live state for retry; reconnect must not replace it with stale disk data.
        if (loaded.contains(id)) return;
        File file = file(id);
        String key = key(id), legacy = "entity:" + id;
        ScopedVariableSnapshotStore.Registry registry;
        if (file.exists()) {
            var saved = ScopedVariableSnapshotStore.load(file);
            if (saved.size() > 1 || !saved.isEmpty() && !saved.containsKey(key)) throw new IOException("player file contains a different scope");
            registry = saved.getOrDefault(key, EMPTY);
        } else {
            registry = SkillExtras.scopedVariableSnapshot(legacy);
            // Commit migration (including an empty tombstone) before removing the old shared snapshot.
            ScopedVariableSnapshotStore.save(file, Map.of(key, registry));
        }
        SkillExtras.restoreScopedVariables(key, registry);
        SkillExtras.restoreScopedVariables(legacy, EMPTY);
        loaded.add(id);
    }

    void save(UUID id, boolean release) throws IOException {
        // Failed loads must never overwrite the unreadable file on quit or shutdown.
        if (!loaded.contains(id)) return;
        String key = key(id);
        ScopedVariableSnapshotStore.save(file(id), Map.of(key, SkillExtras.scopedVariableSnapshot(key)));
        if (release) {
            SkillExtras.restoreScopedVariables(key, EMPTY);
            loaded.remove(id);
        }
    }

    Set<UUID> loadedPlayers() { return Set.copyOf(loaded); }
    File file(UUID id) { return new File(directory, id + ".yml"); }
    private static String key(UUID id) { return "player:" + id; }
}
