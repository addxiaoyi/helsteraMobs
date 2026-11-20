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
