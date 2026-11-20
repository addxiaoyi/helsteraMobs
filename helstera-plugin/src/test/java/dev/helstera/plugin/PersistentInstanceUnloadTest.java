package dev.helstera.plugin;

import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.ModelRemoveEvent;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import dev.helstera.runtime.instance.InstanceRenderer;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersistentInstanceUnloadTest {
    @Test @SuppressWarnings("unchecked")
    void pendingRetainedSpawnsSaveBeforeTeardownWithoutLoadingTheChunkOrFiringDespawn() throws Exception {
        for (String mode : List.of("NEVER", "PERSISTENT")) {
            var bus = new HelsteraEventBus();
            var manager = new InstanceManagerImpl(null, bus, mock(InstanceRenderer.class), null);
            var field = InstanceManagerImpl.class.getDeclaredField("instances"); field.setAccessible(true);
            var instances = (Map<Integer, ModelInstanceImpl>) field.get(manager);
            var instance = mock(ModelInstanceImpl.class); var entity = mock(LivingEntity.class);
            var world = mock(org.bukkit.World.class);
            var data = mock(org.bukkit.persistence.PersistentDataContainer.class);
            when(entity.getPersistentDataContainer()).thenReturn(data);
            when(data.get(any(org.bukkit.NamespacedKey.class), eq(org.bukkit.persistence.PersistentDataType.STRING))).thenReturn("child");
            when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
            when(entity.getLocation()).thenReturn(new org.bukkit.Location(world, -17, 100, 33));
            when(instance.entity()).thenReturn(entity); when(instance.instanceId()).thenReturn(7);
            when(instance.isValid()).thenReturn(true); when(instance.options()).thenReturn(new SpawnOptions().despawnMode(mode));
            instances.put(7, instance);
            var order = new ArrayList<String>();
            manager.setBeforePersistentUnload((selected, cause) -> {
                assertSame(instance, selected); assertEquals("chunk_unload", cause);
                assertNull(manager.impl(7)); assertFalse(manager.unloadPersistent(7, cause));
                verify(instance, never()).despawn(); order.add("save");
            });
            doAnswer(i -> {order.add("teardown"); return null;}).when(instance).despawn();
            bus.register(ModelRemoveEvent.class, event -> {
                assertTrue(event.unloading()); assertFalse(event.despawning()); order.add("detach");
            });
            manager.cleanupInvalid(); manager.cleanupInvalid();
            assertEquals(List.of("save", "teardown", "detach"), order);
            verify(world).isChunkLoaded(-2, 2); verify(world, never()).getChunkAt(anyInt(), anyInt());
            verify(entity, never()).remove(); verify(instance).despawn();
        }
    }

    @Test void retainedChunkDetectionRejectsDeadLoadedUnmanagedDisposableAndPlayerEntities() {
        for (String reason : List.of("dead", "loaded", "unmanaged", "disposable", "player")) {
            var instance = mock(ModelInstanceImpl.class);
            LivingEntity entity = reason.equals("player") ? mock(org.bukkit.entity.Player.class) : mock(LivingEntity.class);
            var world = mock(org.bukkit.World.class);
            var data = mock(org.bukkit.persistence.PersistentDataContainer.class);
            when(instance.entity()).thenReturn(entity);
            when(instance.options()).thenReturn(new SpawnOptions().despawnMode(reason.equals("disposable") ? "NORMAL" : "NEVER"));
            when(entity.isDead()).thenReturn(reason.equals("dead"));
            when(entity.getPersistentDataContainer()).thenReturn(data);
            if (!reason.equals("unmanaged")) when(data.get(any(org.bukkit.NamespacedKey.class), eq(org.bukkit.persistence.PersistentDataType.STRING))).thenReturn("child");
            when(entity.getLocation()).thenReturn(new org.bukkit.Location(world, 16, 100, 16));
            when(world.isChunkLoaded(1, 1)).thenReturn(reason.equals("loaded"));
            assertFalse(InstanceManagerImpl.awaitingRetainedChunk(instance), reason);
            verify(world, never()).getChunkAt(anyInt(), anyInt());
        }
    }

    @Test @SuppressWarnings("unchecked")
    void shutdownRemovesDisposableMobsOnceBeforeSerializationButKeepsRetainedAndStandaloneInstances() throws Exception {
        var bus = new HelsteraEventBus();
        var manager = new InstanceManagerImpl(null, bus, mock(InstanceRenderer.class), null);
        var field = InstanceManagerImpl.class.getDeclaredField("instances"); field.setAccessible(true);
        var instances = (Map<Integer, ModelInstanceImpl>) field.get(manager);
        var removed = new ArrayList<Integer>();
        bus.register(ModelRemoveEvent.class, event -> {
            assertEquals("plugin_shutdown", event.cause()); assertTrue(event.despawning());
            assertNull(manager.impl(event.instance().instanceId()));
            removed.add(event.instance().instanceId());
        });
        var modes = List.of("NORMAL", "CHUNK", "NPC", "NEVER", "PERSISTENT", "NORMAL");
        var all = new ArrayList<ModelInstanceImpl>();
        for (int i = 0; i < modes.size(); i++) {
            var instance = mock(ModelInstanceImpl.class); all.add(instance);
            when(instance.instanceId()).thenReturn(i + 1);
            when(instance.options()).thenReturn(new SpawnOptions().despawnMode(modes.get(i)));
            if (i < 5) {
                var entity = mock(LivingEntity.class);
                when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
                when(entity.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
                when(instance.entity()).thenReturn(entity); when(instance.baseEntity()).thenReturn(Optional.of(entity));
            }
            instances.put(i + 1, instance);
        }
        manager.despawnDisposableOnShutdown(); manager.despawnDisposableOnShutdown();
        assertEquals(Set.of(1, 2, 3), new HashSet<>(removed)); assertEquals(3, removed.size());
        assertEquals(Set.of(4, 5, 6), instances.keySet());
        for (int i = 0; i < all.size(); i++) {
            verify(all.get(i), times(i < 3 ? 1 : 0)).despawn();
            if (i < 5) verify(all.get(i).entity(), times(i < 3 ? 1 : 0)).remove();
        }
    }

    @Test @SuppressWarnings("unchecked")
    void naturalDespawnDispatchesBeforeTeardownEvenWhenPaperAlreadyMarksTheEntityDead() throws Exception {
        var bus = new HelsteraEventBus();
        var manager = new InstanceManagerImpl(null, bus, mock(InstanceRenderer.class), null);
        var field = InstanceManagerImpl.class.getDeclaredField("instances"); field.setAccessible(true);
        var instances = (Map<Integer, ModelInstanceImpl>) field.get(manager);
        var instance = mock(ModelInstanceImpl.class);
        var entity = mock(LivingEntity.class);
        when(instance.instanceId()).thenReturn(7); when(instance.entity()).thenReturn(entity);
        when(instance.baseEntity()).thenReturn(Optional.of(entity)); when(entity.isDead()).thenReturn(true);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
        var order = new ArrayList<String>();
        doAnswer(i -> {order.add("teardown"); return null;}).when(instance).despawn();
        bus.register(ModelRemoveEvent.class, event -> {
            assertTrue(event.despawning()); assertFalse(event.unloading());
            assertNull(manager.impl(7)); assertFalse(manager.despawnNaturally(7));
            order.add("callback");
        });
        instances.put(7, instance);
        assertTrue(manager.despawnNaturally(7)); assertFalse(manager.despawnNaturally(7));
        manager.cleanupInvalid();
        assertEquals(List.of("callback", "teardown"), order);
        verify(entity, never()).remove(); verify(instance).despawn();
    }

    @Test void bindingPlayerDoesNotApplyMobDistanceOrSavingFlags() {
        var player = mock(org.bukkit.entity.Player.class);
        when(player.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
        for (String mode : List.of("NORMAL", "CHUNK", "NPC", "NEVER", "PERSISTENT")) {
            dev.helstera.api.instance.ManagedMobOptions.apply(player, new SpawnOptions().despawnMode(mode));
        }
        verify(player, never()).setPersistent(anyBoolean());
        verify(player, never()).setRemoveWhenFarAway(anyBoolean());
    }

    @Test void distanceFlagsFollowModeAndDoNotInheritTheEntityFamilyDefault() {
        for (String mode : List.of("NORMAL", "CHUNK", "NPC", "NEVER", "PERSISTENT")) {
            var entity = mock(LivingEntity.class);
            when(entity.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
            var options = new SpawnOptions().despawnMode(mode);
            dev.helstera.api.instance.ManagedMobOptions.apply(entity, options);
            verify(entity).setRemoveWhenFarAway(mode.equals("NORMAL"));
            verify(entity).setPersistent(Set.of("NEVER", "PERSISTENT").contains(mode));
            assertEquals(0, options.despawnDistance());
        }
    }

    @Test void persistentModesPreserveBase() throws Exception {
        for (String mode : List.of("NEVER", "PERSISTENT")) persistentUnloadPreservesBaseEntityAndPostsExactlyOneUnload(mode);
    }
    @SuppressWarnings("unchecked")
    private void persistentUnloadPreservesBaseEntityAndPostsExactlyOneUnload(String mode) throws Exception {
        var bus = new HelsteraEventBus();
        var manager = new InstanceManagerImpl(null,bus,mock(InstanceRenderer.class),null);
        var field = InstanceManagerImpl.class.getDeclaredField("instances");field.setAccessible(true);
        var instances = (Map<Integer,ModelInstanceImpl>) field.get(manager);
        var persistent = mock(ModelInstanceImpl.class); var ordinary = mock(ModelInstanceImpl.class);
        var entity = mock(LivingEntity.class); when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
        when(persistent.options()).thenReturn(new SpawnOptions().despawnMode(mode));
        when(persistent.entity()).thenReturn(entity);when(persistent.instanceId()).thenReturn(1);
        when(ordinary.options()).thenReturn(SpawnOptions.defaults());
        instances.put(1,persistent);instances.put(2,ordinary);
        var events = new ArrayList<ModelRemoveEvent>();bus.register(ModelRemoveEvent.class,events::add);
        assertFalse(manager.unloadPersistent(2,"chunk_unload"));
        assertThrows(IllegalArgumentException.class,()->manager.unloadPersistent(1,"death"));
        assertTrue(manager.unloadPersistent(1,"chunk_unload"));
        assertFalse(manager.unloadPersistent(1,"chunk_unload"));
        assertNull(manager.impl(1));assertSame(ordinary,manager.impl(2));
        verify(persistent).despawn();verify(entity,never()).remove();
        assertEquals(1,events.size());assertTrue(events.getFirst().unloading());
        assertFalse(new ModelRemoveEvent(persistent,"manual").unloading());
        assertFalse(new ModelRemoveEvent(ordinary,"world_unload").unloading());
    }

    @Test void disposableModesRemoveBaseAfterCallback() throws Exception {
        for (String mode : List.of("NORMAL", "CHUNK", "NPC")) unloadRemovesBaseAfterCallbackAndCannotBeRepeated(mode);
    }
    @SuppressWarnings("unchecked")
    private void unloadRemovesBaseAfterCallbackAndCannotBeRepeated(String mode) throws Exception {
        var bus = new HelsteraEventBus();
        var manager = new InstanceManagerImpl(null,bus,mock(InstanceRenderer.class),null);
        var field = InstanceManagerImpl.class.getDeclaredField("instances"); field.setAccessible(true);
        var instances = (Map<Integer,ModelInstanceImpl>)field.get(manager);
        var inst = mock(ModelInstanceImpl.class); var entity = mock(LivingEntity.class);
        when(inst.options()).thenReturn(new SpawnOptions().despawnMode(mode));
        when(inst.entity()).thenReturn(entity); when(inst.instanceId()).thenReturn(5);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
        var order = new ArrayList<String>();
        doAnswer(i->{order.add("visuals");return null;}).when(inst).despawn();
        doAnswer(i->{order.add("base");return null;}).when(entity).remove();
        bus.register(ModelRemoveEvent.class,e->{
            order.add(e.cause());
            order.add("recursive="+manager.despawnOnUnload(5,"chunk_unload"));
        });
        instances.put(5,inst);
        assertThrows(IllegalArgumentException.class,()->manager.despawnOnUnload(5,"death"));
        assertTrue(manager.despawnOnUnload(5,"chunk_unload"));
        assertFalse(manager.despawnOnUnload(5,"chunk_unload"));
        assertEquals(List.of("chunk_unload","recursive=false","visuals","base"),order);
        verify(inst,times(1)).despawn(); verify(entity,times(1)).remove();
        assertNull(manager.impl(5));
    }
}
