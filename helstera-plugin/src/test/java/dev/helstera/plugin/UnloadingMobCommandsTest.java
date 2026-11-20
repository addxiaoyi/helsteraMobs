package dev.helstera.plugin;

import dev.helstera.ai.AiProfile;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnloadingMobCommandsTest {
    private ModelInstanceImpl instance(Entity entity, UUID id, String mobId) {
        var data = mock(PersistentDataContainer.class);
        when(entity.getUniqueId()).thenReturn(id);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        when(data.get(new NamespacedKey("helstera", "managed_mob_id"), PersistentDataType.STRING)).thenReturn(mobId);
        var instance = mock(ModelInstanceImpl.class);
        when(instance.entity()).thenReturn(entity);
        return instance;
    }
    @Test void oldExpiryCannotRemoveAReattachedGenerationAndRemovalIsOnceOnly() {
        var queue = new UnloadingMobCommands();
        UUID uuid = UUID.randomUUID();
        var first = queue.remember(instance(mock(Entity.class), uuid, "guard"), new AiProfile("old"), "guards");
        var second = queue.remember(instance(mock(Entity.class), uuid, "guard"), new AiProfile("new"), "guards");
        assertFalse(queue.take(first));
        assertEquals(java.util.List.of(second), queue.snapshot());
        assertTrue(queue.take(second));
        assertFalse(queue.take(second));
        assertTrue(queue.snapshot().isEmpty());
    }
    @Test void snapshotsRetainFactionAndProfileButExcludePlayersAndUnownedModels() {
        var queue = new UnloadingMobCommands();
        assertNull(queue.remember(instance(mock(Player.class), UUID.randomUUID(), "player"), null, null));
        assertNull(queue.remember(instance(mock(Entity.class), UUID.randomUUID(), null), null, null));
        var profile = new AiProfile("mob");profile.faction = "guards";
        UUID uuid = UUID.randomUUID();
        var entry = queue.remember(instance(mock(Entity.class), uuid, "guard"), profile, "guards");
        profile.faction = "changed";
        assertEquals("guards", entry.profile().faction);
        assertEquals("guards", entry.faction());
        queue.restored(uuid);
        assertFalse(queue.take(entry));
    }
}
