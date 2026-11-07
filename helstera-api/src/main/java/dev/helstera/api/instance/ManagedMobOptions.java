package dev.helstera.api.instance;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

/** Entity metadata needed by Bukkit events that can arrive after instance cleanup. */
public final class ManagedMobOptions {

    private static final NamespacedKey PREVENT_SLIME_SPLIT_KEY =
            new NamespacedKey("helstera", "prevent_slime_split");

    private ManagedMobOptions() {
    }

    public static void apply(Entity entity, SpawnOptions options) {
        if (entity == null) return;
        var data = entity.getPersistentDataContainer();
        if (options != null && options.preventSlimeSplit()) {
            data.set(PREVENT_SLIME_SPLIT_KEY, PersistentDataType.BYTE, (byte) 1);
        } else {
            data.remove(PREVENT_SLIME_SPLIT_KEY);
        }
    }

    public static boolean preventsSlimeSplit(Entity entity) {
        if (entity == null) return false;
        try {
            Byte value = entity.getPersistentDataContainer().get(
                    PREVENT_SLIME_SPLIT_KEY, PersistentDataType.BYTE);
            return value != null && value == (byte) 1;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static void clear(Entity entity) {
        if (entity != null) entity.getPersistentDataContainer().remove(PREVENT_SLIME_SPLIT_KEY);
    }
}
