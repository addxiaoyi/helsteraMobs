package dev.helstera.plugin;

import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.behavior.SkillTreeSnapshotStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;

final class PersistentMobSkillTreeStore {
    private final File directory;

    PersistentMobSkillTreeStore(File directory) {
        this.directory = directory;
    }

    File entityFile(UUID entityId) {
        if (entityId == null) throw new IllegalArgumentException("entityId is required");
        return new File(directory, "entity-" + entityId + ".yml");
    }

    File legacyMobFile(String mobId) {
        String safe = mobId == null ? "" : mobId.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new File(directory, "mob-" + safe + ".yml");
    }

    Optional<SkillTreeContext.Snapshot> load(UUID entityId, String mobId) throws IOException {
        File entitySnapshot = entityFile(entityId);
        if (entitySnapshot.isFile()) return Optional.of(SkillTreeSnapshotStore.load(entitySnapshot));
        File legacySnapshot = legacyMobFile(mobId);
        return legacySnapshot.isFile()
                ? Optional.of(SkillTreeSnapshotStore.load(legacySnapshot))
                : Optional.empty();
    }

    void save(UUID entityId, SkillTreeContext.Snapshot snapshot) throws IOException {
        SkillTreeSnapshotStore.save(entityFile(entityId), snapshot);
    }

    boolean delete(UUID entityId) throws IOException {
        return Files.deleteIfExists(entityFile(entityId).toPath());
    }

    boolean deleteInstance(int instanceId) throws IOException {
        return Files.deleteIfExists(new File(directory, instanceId + ".yml").toPath());
    }
}
