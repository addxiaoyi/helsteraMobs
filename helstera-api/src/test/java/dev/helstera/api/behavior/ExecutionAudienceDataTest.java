package dev.helstera.api.behavior;

import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionAudienceDataTest {

    @Test
    void persistedAudienceDistinguishesUnrestrictedFromExplicitlyEmpty() {
        Map<String, Object> values = new HashMap<>();
        Entity entity = entity(data(values));
        assertTrue(ExecutionAudienceData.read(entity).isEmpty());

        ExecutionAudienceData.write(entity, Set.of());
        assertEquals(Optional.of(Set.of()), ExecutionAudienceData.read(entity));

        Set<UUID> players = Set.of(UUID.randomUUID(), UUID.randomUUID());
        ExecutionAudienceData.write(entity, players);
        assertEquals(Optional.of(players), ExecutionAudienceData.read(entity));

        ExecutionAudienceData.write(entity, null);
        assertTrue(ExecutionAudienceData.read(entity).isEmpty());
    }

    @Test
    void malformedStoredIdsAreIgnoredWithoutWideningAudience() {
        Map<String, Object> values = new HashMap<>();
        values.put("helstera:execution_audience", java.util.List.of("not-a-uuid"));
        Entity entity = entity(data(values));

        assertEquals(Optional.of(Set.of()), ExecutionAudienceData.read(entity));
    }

    private static Entity entity(PersistentDataContainer data) {
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
                (proxy, method, args) -> method.getName().equals("getPersistentDataContainer")
                        ? data : defaultValue(method.getReturnType()));
    }

    private static PersistentDataContainer data(Map<String, Object> values) {
        return (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(),
                new Class<?>[]{PersistentDataContainer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("set")) {
                        values.put(args[0].toString(), args[2]);
                        return null;
                    }
                    if (method.getName().equals("remove")) {
                        values.remove(args[0].toString());
                        return null;
                    }
                    if (method.getName().equals("get")) return values.get(args[0].toString());
                    if (method.getName().equals("has")) return values.containsKey(args[0].toString());
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
