package dev.helstera.ai;

import dev.helstera.ai.stats.MythicStatsService;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MythicStatsServiceTest {

    @Test
    void defaultStatInheritsBaseAndConfiguredStatsRespectValidity() {
        MythicStatsService service = new MythicStatsService("helstera", Logger.getAnonymousLogger());
        assertEquals(0d, service.resolve("loot_bias", null, null, null));

        var config = YamlConfiguration.loadConfiguration(new StringReader("""
                stats:
                  PLAYER_BIAS:
                    enabled: true
                    base-value: 12.5
                    min-value: -10
                    max-value: 20
                  DISABLED:
                    enabled: false
                    base-value: 4
                  INVALID:
                    enabled: true
                    base-value: nope
                """));
        service.load(config);

        assertEquals(12.5, service.resolve("PLAYER_BIAS", null, null, null));
        assertNull(service.resolve("DISABLED", null, null, null));
        assertNull(service.resolve("INVALID", null, null, null));
        assertNull(service.resolve("UNKNOWN", null, null, null));
        assertTrue(service.warnings().stream().anyMatch(value -> value.contains("INVALID")));
    }

    @Test
    void killerOverrideWinsAndDropperIsUsedOnlyWithoutKiller() {
        MythicStatsService service = new MythicStatsService("helstera", null);
        var config = YamlConfiguration.loadConfiguration(new StringReader("""
                stats:
                  LOOT_BIAS:
                    enabled: true
                    base-value: 3
                    min-value: -100
                    max-value: 1000
                """));
        service.load(config);

        Map<String, Double> killerValues = new HashMap<>();
        Map<String, Double> dropperValues = new HashMap<>();
        Player killer = entity(Player.class, killerValues);
        Entity dropper = entity(Entity.class, dropperValues);
        assertTrue(service.setEntityStat(killer, "loot_bias", 100));
        assertTrue(service.setEntityStat(dropper, "LOOT_BIAS", -100));

        assertEquals(100d, service.resolve("LOOT_BIAS", null, killer, dropper));
        assertEquals(-100d, service.resolve("LOOT_BIAS", null, null, dropper));
        assertTrue(service.clearEntityStat(killer, "LOOT_BIAS"));
        assertEquals(3d, service.resolve("LOOT_BIAS", null, killer, null));
    }

    @Test
    void overridesClampToConfiguredRangeAndCanBeCleared() {
        MythicStatsService service = new MythicStatsService("helstera", null);
        var config = YamlConfiguration.loadConfiguration(new StringReader("""
                stats:
                  SCORE:
                    enabled: true
                    base-value: 5
                    min-value: 0
                    max-value: 10
                """));
        service.load(config);

        Map<String, Double> values = new HashMap<>();
        Entity entity = entity(Entity.class, values);
        assertTrue(service.setEntityStat(entity, "SCORE", 99));
        assertEquals(10d, service.resolve("SCORE", null, null, entity));
        assertTrue(service.clearEntityStat(entity, "score"));
        assertEquals(5d, service.resolve("SCORE", null, null, entity));
        assertFalse(service.setEntityStat(entity, "UNKNOWN", 1));
        assertFalse(service.setEntityStat(entity, "SCORE", Double.NaN));
    }

    private static <T extends Entity> T entity(Class<T> type, Map<String, Double> values) {
        T entity = mock(type);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.DOUBLE)))
                .thenAnswer(invocation -> values.get(invocation.<NamespacedKey>getArgument(0).toString()));
        doAnswer(invocation -> {
            NamespacedKey key = invocation.getArgument(0);
            values.put(key.toString(), invocation.getArgument(2));
            return null;
        }).when(data).set(any(NamespacedKey.class), eq(PersistentDataType.DOUBLE), any(Double.class));
        doAnswer(invocation -> {
            values.remove(invocation.<NamespacedKey>getArgument(0).toString());
            return null;
        }).when(data).remove(any(NamespacedKey.class));
        return entity;
    }
}
