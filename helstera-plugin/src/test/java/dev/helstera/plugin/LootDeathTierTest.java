package dev.helstera.plugin;

import dev.helstera.ai.loot.LootService;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDeathEvent;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class LootDeathTierTest {
    @Test
    void deathEventPassesMobLevelToExperienceDrop() throws Exception {
        var plugin = mock(HelsteraPlugin.class, CALLS_REAL_METHODS);
        var manager = mock(InstanceManagerImpl.class);
        var instance = mock(ModelInstanceImpl.class);
        var entity = mock(LivingEntity.class);
        UUID id = UUID.randomUUID();
        when(entity.getUniqueId()).thenReturn(id);
        when(instance.boundEntityId()).thenReturn(Optional.of(id));
        when(instance.options()).thenReturn(SpawnOptions.defaults());
        when(manager.allImpl()).thenReturn(List.of(instance));
        doReturn(new YamlConfiguration()).when(plugin).getConfig();
        instance.lootTable = "tiered";
        instance.lootExperienceMin = -1;
        instance.lootExperienceMax = -1;

        var loot = new LootService(null);
        loot.load(YamlConfiguration.loadConfiguration(new StringReader("""
                tables:
                  tiered:
                    experience: 2
                    entries:
                      - item: xp
                        amount-min: 5
                        amount-max: 5
                        min-tier-level: 3
                """)).getConfigurationSection("tables"));
        var instancesField = HelsteraPlugin.class.getDeclaredField("instances");
        instancesField.setAccessible(true);
        instancesField.set(plugin, manager);
        var lootField = HelsteraPlugin.class.getDeclaredField("lootService");
        lootField.setAccessible(true);
        lootField.set(plugin, loot);

        instance.level = 2;
        var low = new EntityDeathEvent(entity, null, new ArrayList<>(), 0);
        plugin.onEntityDeathForLoot(low);
        assertEquals(2, low.getDroppedExp());

        instance.level = 3;
        var qualified = new EntityDeathEvent(entity, null, new ArrayList<>(), 0);
        plugin.onEntityDeathForLoot(qualified);
        assertEquals(7, qualified.getDroppedExp());
    }
}
