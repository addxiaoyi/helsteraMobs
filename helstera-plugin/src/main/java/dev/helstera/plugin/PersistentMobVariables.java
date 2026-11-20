package dev.helstera.plugin;

import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.api.behavior.ScopedVariableSnapshotStore;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** Variable checkpoints share the entity save boundary, including an explicitly empty registry. */
final class PersistentMobVariables {
    static final NamespacedKey KEY = new NamespacedKey("helsteramobs", "persistent_mob_variables");
    private static final ScopedVariableSnapshotStore.Registry EMPTY =
            new ScopedVariableSnapshotStore.Registry(Map.of(), Map.of(), Map.of(), Map.of());

    private PersistentMobVariables() { }

    static void save(Entity entity) throws IOException {
        String scope = "entity:" + entity.getUniqueId();
        String text = ScopedVariableSnapshotStore.serialize(Map.of(scope, SkillExtras.scopedVariableSnapshot(scope)));
        // Serialize and validate completely before replacing the last good checkpoint.
        entity.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, text);
    }

    static void restore(Entity entity) throws IOException {
        var data = entity.getPersistentDataContainer();
        if (!data.has(KEY)) return; // Migrate older entities from the existing shutdown file.
        String text = data.get(KEY, PersistentDataType.STRING);
        if (text == null) throw new IOException("invalid entity variable checkpoint type");
        String scope = "entity:" + entity.getUniqueId();
        var saved = ScopedVariableSnapshotStore.deserialize(text);
        if (!Set.of(scope).containsAll(saved.keySet())) throw new IOException("foreign entity variable scope");
        // An empty checkpoint must override stale data in the legacy shutdown file.
        SkillExtras.restoreScopedVariables(scope, saved.getOrDefault(scope, EMPTY));
    }
}
