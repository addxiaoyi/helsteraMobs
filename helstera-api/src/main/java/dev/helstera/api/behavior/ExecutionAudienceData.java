package dev.helstera.api.behavior;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence helpers for an entity's optional execution audience. */
public final class ExecutionAudienceData {

    private static final NamespacedKey KEY = new NamespacedKey("helstera", "execution_audience");

    private ExecutionAudienceData() {
    }

    /** Presence distinguishes an unrestricted entity from an explicitly empty audience. */
    public static Optional<Set<UUID>> read(Entity entity) {
        if (entity == null) return Optional.empty();
        var data = entity.getPersistentDataContainer();
        if (data == null || !data.has(KEY, PersistentDataType.LIST.strings())) return Optional.empty();
        Set<UUID> audience = new LinkedHashSet<>();
        for (String value : data.get(KEY, PersistentDataType.LIST.strings())) {
            try {
                audience.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
                // Invalid persisted entries fail closed and are omitted.
            }
        }
        return Optional.of(Set.copyOf(audience));
    }

    /** A null audience removes the restriction; an empty collection restricts it to nobody. */
    public static void write(Entity entity, Collection<UUID> audience) {
        if (entity == null) return;
        var data = entity.getPersistentDataContainer();
        if (data == null) return;
        if (audience == null) {
            data.remove(KEY);
            return;
        }
        data.set(KEY, PersistentDataType.LIST.strings(), audience.stream()
                .filter(java.util.Objects::nonNull)
                .map(UUID::toString)
                .distinct()
                .sorted()
                .toList());
    }
}
