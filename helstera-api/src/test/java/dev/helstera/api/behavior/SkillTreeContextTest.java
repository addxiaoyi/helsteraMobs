package dev.helstera.api.behavior;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;

class SkillTreeContextTest {
    @Test
    void locationValuesAreCopiedAndCanBeUnset() {
        Location origin = new Location(null, 1, 2, 3);
        SkillTreeContext tree = new SkillTreeContext(null, null, origin);
        origin.setX(99);
        assertEquals(1, tree.location("origin").orElseThrow().getX());

        Location stored = new Location(null, 4, 5, 6);
        tree.setLocation("marker", stored);
        stored.setX(42);
        Location read = tree.location("marker").orElseThrow();
        assertEquals(4, read.getX());
        read.setX(100);
        assertEquals(4, tree.location("marker").orElseThrow().getX());
        assertTrue(tree.unsetLocation("marker").isPresent());
        assertTrue(tree.location("marker").isEmpty());
    }
    @Test
    void nestedTreeReadsParentButWritesLocally() {
        SkillTreeContext parent = new SkillTreeContext(null, null);
        parent.setVariable("score", 7);
        parent.setLocation("marker", new Location(null, 8, 9, 10));
        SkillTreeContext child = new SkillTreeContext(null, null, null, parent);

        assertEquals(7, child.variable("score").orElseThrow());
        assertEquals(8, child.location("marker").orElseThrow().getX());
        child.setVariable("score", 11);
        child.setLocation("marker", new Location(null, 12, 13, 14));
        assertEquals(11, child.variable("score").orElseThrow());
        assertEquals(7, parent.variable("score").orElseThrow());
        assertEquals(12, child.location("marker").orElseThrow().getX());
        assertEquals(8, parent.location("marker").orElseThrow().getX());
        assertTrue(child.unsetVariable("score").isPresent());
        assertEquals(7, child.variable("score").orElseThrow());
    }    @Test
    void nestedTreeInheritsTargetsUntilExplicitlyReplaced() {
        SkillTreeContext parent = new SkillTreeContext(null, java.util.List.of());
        SkillTreeContext child = new SkillTreeContext(null, null, null, parent);
        assertTrue(child.targets().isEmpty());
        child.targets(java.util.List.of());
        assertTrue(child.targets().isEmpty());
    }

    @Test
    void snapshotRestoresNestedVariablesAndLocations() {
        SkillTreeContext parent = new SkillTreeContext(null, null);
        parent.setVariable("score", 7);
        parent.setLocation("origin", new Location(null, 1, 2, 3, 4, 5));
        SkillTreeContext child = new SkillTreeContext(null, null, null, parent);
        child.setVariable("branch", "left");
        child.setLocation("marker", new Location(null, 8, 9, 10, 11, 12));

        SkillTreeContext restored = SkillTreeContext.restore(child.snapshot(), null, null, name -> null);

        assertEquals(7, restored.variable("score").orElseThrow());
        assertEquals("left", restored.variable("branch").orElseThrow());
        assertEquals(1, restored.location("origin").orElseThrow().getX());
        assertEquals(8, restored.location("marker").orElseThrow().getX());
    }

    @Test
    void snapshotRejectsNonPersistentVariableValues() {
        SkillTreeContext tree = new SkillTreeContext(null, null);
        tree.setVariable("object", new Object());

        assertThrows(IllegalStateException.class, tree::snapshot);
    }

    @Test
    void snapshotStoreRoundTripsYaml() throws Exception {
        SkillTreeContext tree = new SkillTreeContext(null, null);
        tree.setVariable("score", 12);
        tree.setLocation("marker", new Location(null, 4, 5, 6, 7, 8));
        var file = Files.createTempFile("skill-tree", ".yml").toFile();
        try {
            SkillTreeSnapshotStore.save(file, tree.snapshot());
            var loaded = SkillTreeSnapshotStore.load(file);
            assertEquals(12, ((Number) loaded.variables().get("score")).intValue());
            assertEquals(4, loaded.locations().get("marker").x());
            assertTrue(loaded.parent().isEmpty());
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    void branchesRequirePrerequisitesAndSurviveSnapshotRestore() {
        SkillTreeContext tree = new SkillTreeContext(null, null);
        tree.defineBranch("root", java.util.Set.of());
        tree.defineBranch("advanced", java.util.Set.of("root"));

        assertFalse(tree.unlockBranch("advanced"));
        assertTrue(tree.unlockBranch("root"));
        assertTrue(tree.unlockBranch("advanced"));
        assertTrue(tree.branchUnlocked("advanced"));

        SkillTreeContext restored = SkillTreeContext.restore(tree.snapshot(), null, null, name -> null);
        assertTrue(restored.branchUnlocked("root"));
        assertTrue(restored.branchUnlocked("advanced"));
    }
}
