package dev.helstera.plugin;

import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.api.behavior.ScopedVariableSnapshotStore;
import dev.helstera.api.behavior.SkillTreeContext;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersistentMobVariablesTest {
    private final UUID id = UUID.randomUUID();
    private final String scope = "entity:" + id;
    private final Entity entity = mock(Entity.class);
    private final PersistentDataContainer data = mock(PersistentDataContainer.class);
    private final AtomicReference<String> checkpoint = new AtomicReference<>();

    @BeforeEach void setup() {
        SkillExtras.resetRuntimeHooks();
        when(entity.getUniqueId()).thenReturn(id);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        when(data.has(PersistentMobVariables.KEY)).thenAnswer(call -> checkpoint.get() != null);
        when(data.get(PersistentMobVariables.KEY, PersistentDataType.STRING)).thenAnswer(call -> checkpoint.get());
        doAnswer(call -> { checkpoint.set(call.getArgument(2)); return null; }).when(data)
                .set(eq(PersistentMobVariables.KEY), eq(PersistentDataType.STRING), anyString());
    }
    @AfterEach void reset() { SkillExtras.resetRuntimeHooks(); }

    private static ScopedVariableSnapshotStore.Registry value(Object value) {
        return new ScopedVariableSnapshotStore.Registry(Map.of("Saved", value), Map.of(), Map.of(), Map.of());
    }
    @Test void entityCheckpointSurvivesProcessLossWithTypesLocationsAndRetention() throws Exception {
        long deadline = System.currentTimeMillis() + 60000;
        var point = new SkillTreeContext.LocationSnapshot("unloaded", 1.25, 70, -3.5, 10, 20);
        var original = new ScopedVariableSnapshotStore.Registry(Map.of("Saved", 3.25F, "Transient", 99,
                "dotted.name", java.util.List.of(1, true, "text")), Map.of("Point", point),
                Map.of("Transient", 1L, "Saved", deadline), Map.of("Point", -50L));
        SkillExtras.restoreScopedVariables(scope, original); PersistentMobVariables.save(entity);
        SkillExtras.resetRuntimeHooks(); PersistentMobVariables.restore(entity);
        assertEquals(original, SkillExtras.scopedVariableSnapshot(scope));
    }
    @Test void reloadedChangesRecoverOnlyTheLastEntityCheckpoint() throws Exception {
        SkillExtras.restoreScopedVariables(scope, value(7)); PersistentMobVariables.save(entity);
        SkillExtras.restoreScopedVariables(scope, value(107));
        SkillExtras.resetRuntimeHooks(); PersistentMobVariables.restore(entity);
        assertEquals(7, SkillExtras.scopedVariableSnapshot(scope).variables().get("Saved"));
        SkillExtras.restoreScopedVariables(scope, value(207)); PersistentMobVariables.save(entity);
        SkillExtras.resetRuntimeHooks(); PersistentMobVariables.restore(entity);
        assertEquals(207, SkillExtras.scopedVariableSnapshot(scope).variables().get("Saved"));
    }
    @Test void emptyCheckpointOverridesLegacyValuesAndAbsentCheckpointMigratesThem() throws Exception {
        SkillExtras.restoreScopedVariables(scope, value("legacy")); PersistentMobVariables.restore(entity);
        assertEquals("legacy", SkillExtras.scopedVariableSnapshot(scope).variables().get("Saved"));
        SkillExtras.resetRuntimeHooks(); PersistentMobVariables.save(entity);
        SkillExtras.restoreScopedVariables(scope, value("stale legacy")); PersistentMobVariables.restore(entity);
        assertTrue(SkillExtras.scopedVariableSnapshot(scope).variables().isEmpty());
    }
    @Test void corruptForeignAndWrongTypeCheckpointsDoNotReplaceLiveRegistries() throws Exception {
        String other = "entity:" + UUID.randomUUID();
        SkillExtras.restoreScopedVariables(scope, value("live"));
        SkillExtras.restoreScopedVariables(other, value("other"));
        for (String invalid : java.util.List.of("registry-format: [broken]\n",
                ScopedVariableSnapshotStore.serialize(Map.of(other, value("foreign"))),
                ScopedVariableSnapshotStore.serialize(Map.of("global", value("global"))))) {
            checkpoint.set(invalid);
            assertThrows(IOException.class, () -> PersistentMobVariables.restore(entity));
            assertEquals("live", SkillExtras.scopedVariableSnapshot(scope).variables().get("Saved"));
            assertEquals("other", SkillExtras.scopedVariableSnapshot(other).variables().get("Saved"));
        }
        checkpoint.set(null); when(data.has(PersistentMobVariables.KEY)).thenReturn(true);
        assertThrows(IOException.class, () -> PersistentMobVariables.restore(entity));
    }
    @Test void invalidValueKeepsLastGoodCheckpoint() throws Exception {
        SkillExtras.restoreScopedVariables(scope, value(7)); PersistentMobVariables.save(entity);
        String previous = checkpoint.get();
        SkillExtras.restoreScopedVariables(scope, value(new Object()));
        assertThrows(IOException.class, () -> PersistentMobVariables.save(entity));
        assertEquals(previous, checkpoint.get());
    }
    @Test void expiredCheckpointDoesNotReviveAValueFromTheLegacyFile() throws Exception {
        String yaml = ScopedVariableSnapshotStore.serialize(Map.of(scope, value(7)));
        checkpoint.set(yaml.replace("variable-retention: {}", "variable-retention:\n      r0:\n        name: Saved\n        expires-at: 2"));
        SkillExtras.restoreScopedVariables(scope, value(99)); PersistentMobVariables.restore(entity);
        assertTrue(SkillExtras.scopedVariableSnapshot(scope).variables().isEmpty());
    }
}
