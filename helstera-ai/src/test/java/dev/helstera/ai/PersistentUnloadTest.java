package dev.helstera.ai;

import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.ScopedVariableSnapshotStore;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.ModelRemoveEvent;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersistentUnloadTest {
    @AfterEach void reset() { SkillExtras.resetRuntimeHooks(); }

    @ParameterizedTest
    @ValueSource(strings={"persistent_chunk_unload", "persistent_world_unload", "manual", "world_unload", "base_entity_removed"})
    @SuppressWarnings("unchecked")
    void unloadReleasesControllerButOnlyPermanentRemovalDiscardsEntityVariables(String cause) throws Exception {
        var bus = new HelsteraEventBus();
        Plugin plugin = mock(Plugin.class);
        var manager = new AiManager(plugin, null, bus);
        var skills = mock(SkillService.class); manager.setSkills(skills);
        var entity = mock(LivingEntity.class); UUID id = UUID.randomUUID();
        when(entity.getUniqueId()).thenReturn(id);
        var instance = mock(ModelInstanceImpl.class);
        when(instance.instanceId()).thenReturn(17);
        when(instance.options()).thenReturn(new SpawnOptions().despawnMode("PERSISTENT"));
        when(instance.boundEntityId()).thenReturn(Optional.of(id));
        when(instance.baseEntity()).thenReturn(Optional.of(entity));
        var point = new SkillTreeContext.LocationSnapshot("unloaded-world", 1, 2, 3, 0, 0);
        var registry = new ScopedVariableSnapshotStore.Registry(Map.of("value", 7), Map.of("point", point), Map.of("value", 1L), Map.of());
        try (var bukkit = mockStatic(Bukkit.class)) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong())).thenReturn(mock(BukkitTask.class));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            manager.start(5);
            var field = AiManager.class.getDeclaredField("controlled"); field.setAccessible(true);
            var controlled = (Map<Integer,ModelInstanceImpl>) field.get(manager); controlled.put(17, instance);
            SkillExtras.restoreScopedVariables("entity:"+id, registry);
            bus.post(new ModelRemoveEvent(instance, cause));
            assertFalse(controlled.containsKey(17), "unloaded controller must stop");
            verify(skills).forget(17);
            var actual = SkillExtras.scopedVariableSnapshot("entity:"+id);
            if (cause.startsWith("persistent_")) assertEquals(registry, actual);
            else { assertTrue(actual.variables().isEmpty()); assertTrue(actual.locations().isEmpty()); }
            manager.stop();
        }
    }
}
