package dev.helstera.api.behavior;

import dev.helstera.api.util.VariableLifetime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScopedVariableSnapshotStoreTest {
    @TempDir Path directory;
    private static final String WORLD = "world:" + UUID.randomUUID(), ENTITY = "entity:" + UUID.randomUUID();

    @Test void nativeSaveMarkersAreStrictAndOnlyPositiveScalarDurationsUseAnEpochDeadline() {
        assertEquals(1, VariableLifetime.parse(List.of()).expirationTime());
        assertEquals(0, VariableLifetime.parse(List.of("save='TrUe'")).expirationTime());
        assertEquals(1, VariableLifetime.parse(List.of("save=false", "duration=0")).expirationTime());
        assertEquals(-50, VariableLifetime.parse(List.of("save=false", "d=-1")).expirationTime());
        assertEquals(1000, VariableLifetime.parse(List.of("save=true", "duration=20")).locationExpirationTime());
        assertTrue(VariableLifetime.parse(List.of("save=false", "duration=20")).expirationTime() > System.currentTimeMillis());
        for (var arguments : List.of(List.of("save=maybe"), List.of("save="), List.of("save=true", "SAVE=false")))
            assertThrows(IllegalArgumentException.class, () -> VariableLifetime.parse(arguments));
    }

    @Test void typedValuesRetentionAndUnloadedWorldLocationsRoundTripWithoutResettingDeadlines() throws Exception {
        long future = System.currentTimeMillis() + 60000;
        var location = new SkillTreeContext.LocationSnapshot("unloaded", 1.25, 2.5, -3, 4, 5);
        var registry = new ScopedVariableSnapshotStore.Registry(Map.of("Transient", 7, "negative", "text", "expired", 9,
                "timed", 3.25F, "double", 3.25, "dotted.值", List.of(1, "two")), Map.of("point", location),
                Map.of("Transient", 1L, "negative", -50L, "expired", 2L, "timed", future), Map.of("point", future));
        var file = directory.resolve("registry.yml").toFile();
        ScopedVariableSnapshotStore.save(file, Map.of("global", registry, ENTITY, registry, WORLD, registry));
        var loaded = ScopedVariableSnapshotStore.load(file);
        for (String scope : List.of("global", ENTITY, WORLD)) {
            var actual = loaded.get(scope);
            assertEquals(!scope.equals(WORLD), actual.variables().containsKey("Transient"));
            assertFalse(actual.variables().containsKey("expired"));
            assertEquals(3.25F, actual.variables().get("timed"));
            assertEquals(3.25, actual.variables().get("double"));
            assertEquals(List.of(1, "two"), actual.variables().get("dotted.值"));
            assertEquals(-50L, actual.variableRetention().get("negative"));
            assertEquals(future, actual.variableRetention().get("timed"));
            assertEquals(location, actual.locations().get("point"));
            assertEquals(future, actual.locationRetention().get("point"));
        }
        VariableLocations.restore(loaded);
        try {
            assertTrue(VariableLocations.get("global", "point", null).isEmpty());
            assertEquals(location, VariableLocations.snapshots().get("global").values().get("point"));
        } finally { VariableLocations.clear(); }
    }

    @Test void elapsedValuesAndWorldTransientLocationsArePrunedWithoutChangingTheSource() {
        var point = new SkillTreeContext.LocationSnapshot("late", 0, 1, 2, 0, 0);
        var original = new ScopedVariableSnapshotStore.Registry(Map.of("expired", 1, "future", 2), Map.of("transient", point),
                Map.of("expired", 100L, "future", 101L), Map.of("transient", 1L));
        var pruned = original.retained(100, true);
        assertEquals(Map.of("future", 2), pruned.variables()); assertTrue(pruned.locations().isEmpty());
        assertEquals(Map.of("future", 101L), pruned.variableRetention()); assertTrue(pruned.locationRetention().isEmpty());
        assertEquals(2, original.variables().size()); assertEquals(1, original.locations().size());
    }

    @Test void invalidWritePreservesPreviousFileAndLeavesNoTemporaryFiles() throws Exception {
        var file = directory.resolve("registry.yml").toFile();
        ScopedVariableSnapshotStore.save(file, Map.of("global", new ScopedVariableSnapshotStore.Registry(Map.of("keep", 7), Map.of(), Map.of(), Map.of())));
        byte[] original = Files.readAllBytes(file.toPath());
        for (var registry : List.of(
                new ScopedVariableSnapshotStore.Registry(Map.of("bad", new Object()), Map.of(), Map.of(), Map.of()),
                new ScopedVariableSnapshotStore.Registry(Map.of("keep", 9), Map.of(), Map.of("missing", 1L), Map.of()))) {
            assertThrows(java.io.IOException.class, () -> ScopedVariableSnapshotStore.save(file, Map.of("global", registry)));
            assertArrayEquals(original, Files.readAllBytes(file.toPath()));
        }
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void corruptRetentionAndDuplicateScopesAreRejectedInsteadOfPartiallyRestored() throws Exception {
        var file = directory.resolve("registry.yml").toFile();
        ScopedVariableSnapshotStore.save(file, Map.of("global", new ScopedVariableSnapshotStore.Registry(Map.of("keep", 7), Map.of(), Map.of("keep", 1L), Map.of())));
        String original = Files.readString(file.toPath());
        for (String corrupted : List.of(original.replace("expires-at: 1", "expires-at: 0.5"),
                original.replace("scope: global", "scope: entity:bad"), original.replace("name: keep\n        expires-at", "name: orphan\n        expires-at"),
                original.replace("registry-format: 1", "registry-format: 2"))) {
            Files.writeString(file.toPath(), corrupted);
            assertThrows(java.io.IOException.class, () -> ScopedVariableSnapshotStore.load(file), corrupted);
        }
        var yaml = new org.bukkit.configuration.file.YamlConfiguration(); yaml.loadFromString(original);
        yaml.set("registries.r1", yaml.getConfigurationSection("registries.r0")); yaml.save(file);
        assertThrows(java.io.IOException.class, () -> ScopedVariableSnapshotStore.load(file));
    }
    @Test void incompleteAndDuplicateLocationsDoNotBecomeOriginCoordinates() throws Exception {
        var file = directory.resolve("registry.yml").toFile();
        var point = new SkillTreeContext.LocationSnapshot("late", 1, 2, 3, 0, 0);
        ScopedVariableSnapshotStore.save(file, Map.of("global", new ScopedVariableSnapshotStore.Registry(
                Map.of(), Map.of("point", point), Map.of(), Map.of())));
        String original = Files.readString(file.toPath());
        for (String replacement : List.of("x: bad", "other: 1.0")) {
            Files.writeString(file.toPath(), original.replace("x: 1.0", replacement));
            assertThrows(java.io.IOException.class, () -> ScopedVariableSnapshotStore.load(file));
        }
        var yaml = new org.bukkit.configuration.file.YamlConfiguration(); yaml.loadFromString(original);
        var entries = yaml.getConfigurationSection("registries.r0.snapshot.locations.entries");
        entries.set("duplicate", entries.getConfigurationSection(entries.getKeys(false).iterator().next()));
        yaml.save(file);
        assertThrows(java.io.IOException.class, () -> ScopedVariableSnapshotStore.load(file));
    }

}
