package dev.helstera.api.behavior;

import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobRelationsTest {

    @Test
    void parentRelationCanExistWithoutResolvingToALiveEntity() {
        Map<String, Object> values = new HashMap<>();
        PersistentDataContainer data = data(values);
        Entity child = entity(data, UUID.randomUUID());
        Entity parent = entity(data(), UUID.randomUUID());

        assertTrue(MobRelations.setParent(child, parent));
        assertTrue(MobRelations.hasParentRelation(child));
        assertFalse(MobRelations.isParentAlive(child));
        assertFalse(MobRelations.hasParent(child));

        MobRelations.clearParent(child);
        assertFalse(MobRelations.hasParentRelation(child));
    }

    @Test
    void missingEntityUuidFailsClosedAndCanStillBeCleared() {
        Entity child = entity(data(), null);
        Entity parent = entity(data(), UUID.randomUUID());

        assertFalse(MobRelations.setParent(child, parent));
        assertDoesNotThrow(() -> MobRelations.clearParent(child));
        assertNull(MobRelations.parentId(child));
        assertFalse(MobRelations.hasParentRelation(child));
    }

    private static Entity entity(PersistentDataContainer data, UUID id) {
        return (Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(),
                new Class<?>[]{Entity.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getPersistentDataContainer" -> data;
                    case "getUniqueId" -> id;
                    case "isValid" -> true;
                    case "isDead" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static PersistentDataContainer data() {
        return data(new HashMap<>());
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
