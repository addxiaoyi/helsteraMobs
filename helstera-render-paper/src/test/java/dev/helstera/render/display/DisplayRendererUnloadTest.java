package dev.helstera.render.display;

import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Bukkit;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisplayRendererUnloadTest {
    @SuppressWarnings("unchecked")
    private <K,V> Map<K,V> map(DisplayRenderer renderer, String name) throws Exception {
        var field = DisplayRenderer.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<K,V>) field.get(renderer);
    }
    private List<Entity> seed(DisplayRenderer renderer) throws Exception {
        var visuals = new DisplayRenderer.Visuals();
        visuals.carrier = mock(ArmorStand.class);
        visuals.hitbox = mock(Interaction.class);
        visuals.nameTag = mock(TextDisplay.class);
        visuals.boneDisplays.add(mock(ItemDisplay.class));
        List<Entity> owned = new ArrayList<>(visuals.entities());
        var attachment = mock(ItemDisplay.class);
        owned.add(attachment);
        for (Entity e : owned) {
            when(e.getUniqueId()).thenReturn(UUID.randomUUID());
            map(renderer,"entityIndex").put(e.getUniqueId(),17);
            if (e != attachment) visuals.allEntityIds.add(e.getUniqueId());
        }
        map(renderer,"visuals").put(17,visuals);
        map(renderer,"attachDisplays").put(17,List.of(attachment));
        return owned;
    }
    @Test void teardownRemovesOwnedObjectsEvenWhenGlobalUuidLookupIsEmpty() throws Exception {
        var renderer = new DisplayRenderer(null,null);
        var owned = seed(renderer);
        var instance = mock(ModelInstanceImpl.class);
        when(instance.instanceId()).thenReturn(17);
        try (var bukkit = mockStatic(Bukkit.class)) {
            for (Entity entity : owned) assertNull(Bukkit.getEntity(entity.getUniqueId()));
            renderer.destroyVisuals(instance);
            renderer.destroyVisuals(instance);
            renderer.shutdown();
        }
        for (Entity entity : owned) { verify(entity).remove(); assertNull(renderer.instanceOf(entity)); }
        assertEquals(0,renderer.renderedEntityCount(17));
        assertTrue(map(renderer,"attachDisplays").isEmpty());
    }
    @Test void shutdownAlsoRemovesUnloadedVisualsAndAttachmentsExactlyOnce() throws Exception {
        var renderer = new DisplayRenderer(null,null);
        var owned = seed(renderer);
        try (var bukkit = mockStatic(Bukkit.class)) {
            renderer.shutdown();
            renderer.shutdown();
        }
        for (Entity entity : owned) { verify(entity).remove(); assertNull(renderer.instanceOf(entity)); }
        assertEquals(0,renderer.renderedEntityCount(17));
    }
}
