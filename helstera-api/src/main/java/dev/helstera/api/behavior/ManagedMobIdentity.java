package dev.helstera.api.behavior;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

/** Persistent identity for entities spawned from a Helstera mob definition. */
public final class ManagedMobIdentity {

    private static final NamespacedKey MOB_ID_KEY = new NamespacedKey("helstera", "managed_mob_id");

    private ManagedMobIdentity() {
    }

    public static void mark(Entity entity, String mobId) {
        if (entity == null || mobId == null || mobId.isBlank()) return;
        var data = entity.getPersistentDataContainer();
        if (data != null) data.set(MOB_ID_KEY, PersistentDataType.STRING, mobId.trim());
    }

    public static String mobId(Entity entity) {
        if (entity == null) return null;
        var data = entity.getPersistentDataContainer();
        if (data == null) return null;
        try {
            String value = data.get(MOB_ID_KEY, PersistentDataType.STRING);
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static boolean isManaged(Entity entity) {
        return mobId(entity) != null;
    }
}
