package dev.helstera.api.behavior;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent Mythic-style owner metadata stored on the mob entity. */
public final class MobRelations {

    private static final NamespacedKey OWNER_KEY = new NamespacedKey("helstera", "mythic_owner_uuid");
    private static final NamespacedKey PARENT_KEY = new NamespacedKey("helstera", "mythic_parent_uuid");
    private static final ConcurrentHashMap<UUID, UUID> PLAYER_PARENTS = new ConcurrentHashMap<>();

    private MobRelations() {
    }

    public static void removeOwner(Entity mob) {
        if (mob == null) return;
        var data = mob.getPersistentDataContainer();
        if (data != null) data.remove(OWNER_KEY);
    }

    public static UUID ownerId(Entity mob) {
        if (mob == null) return null;
        var data = mob.getPersistentDataContainer();
        if (data == null) return null;
        String value = data.get(OWNER_KEY, PersistentDataType.STRING);
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static boolean hasOwner(Entity mob) {
        return ownerId(mob) != null;
    }

    public static Entity owner(Entity mob) {
        UUID id = ownerId(mob);
        return id == null ? null : Bukkit.getEntity(id);
    }

    public static boolean setOwner(Entity mob, Entity owner) {
        if (mob == null || owner == null || mob.equals(owner)) return false;
        var data = mob.getPersistentDataContainer();
        if (data == null) return false;
        data.set(OWNER_KEY, PersistentDataType.STRING, owner.getUniqueId().toString());
        if (mob instanceof org.bukkit.entity.Tameable tameable
                && owner instanceof org.bukkit.entity.Player player
                && (mob instanceof org.bukkit.entity.Wolf
                || mob instanceof org.bukkit.entity.Cat
                || mob instanceof org.bukkit.entity.Parrot)) {
            tameable.setOwner(player);
        }
        return true;
    }

    public static void clearOwner(Entity mob) {
        if (mob == null) return;
        removeOwner(mob);
        if (mob instanceof org.bukkit.entity.Tameable tameable
                && (mob instanceof org.bukkit.entity.Wolf
                || mob instanceof org.bukkit.entity.Cat
                || mob instanceof org.bukkit.entity.Parrot)) {
            tameable.setTamed(false);
        }
    }

    public static boolean setParent(Entity child, Entity parent) {
        if (child == null || parent == null || child.equals(parent)) return false;
        clearParent(child);
        if (parent instanceof org.bukkit.entity.Player) {
            PLAYER_PARENTS.put(child.getUniqueId(), parent.getUniqueId());
            return true;
        }
        var data = child.getPersistentDataContainer();
        if (data == null) return false;
        data.set(PARENT_KEY, PersistentDataType.STRING, parent.getUniqueId().toString());
        return true;
    }

    public static void clearParent(Entity child) {
        if (child == null) return;
        PLAYER_PARENTS.remove(child.getUniqueId());
        var data = child.getPersistentDataContainer();
        if (data != null) data.remove(PARENT_KEY);
    }

    public static void clearPlayerParents(UUID playerId) {
        if (playerId != null) PLAYER_PARENTS.entrySet().removeIf(entry -> entry.getValue().equals(playerId));
    }

    public static UUID parentId(Entity child) {
        if (child == null) return null;
        UUID playerParent = PLAYER_PARENTS.get(child.getUniqueId());
        if (playerParent != null) return playerParent;
        var data = child.getPersistentDataContainer();
        if (data == null) return null;
        String value = data.get(PARENT_KEY, PersistentDataType.STRING);
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static Entity parent(Entity child) {
        UUID id = parentId(child);
        return id == null ? null : Bukkit.getEntity(id);
    }

    public static boolean hasParent(Entity child) {
        Entity parent = parent(child);
        return parent != null && parent.isValid() && !parent.isDead();
    }

    public static boolean isChild(Entity parent, Entity candidate) {
        if (parent == null || candidate == null || !parent.isValid() || parent.isDead()
                || !candidate.isValid() || candidate.isDead()) return false;
        return parent.getUniqueId().equals(parentId(candidate));
    }

    public static boolean areSiblings(Entity first, Entity second) {
        if (first == null || second == null || !first.isValid() || first.isDead()
                || !second.isValid() || second.isDead()) return false;
        UUID firstParent = parentId(first);
        UUID secondParent = parentId(second);
        if (firstParent == null || !firstParent.equals(secondParent)) return false;
        Entity parent = Bukkit.getEntity(firstParent);
        return parent != null && parent.isValid() && !parent.isDead();
    }
}
