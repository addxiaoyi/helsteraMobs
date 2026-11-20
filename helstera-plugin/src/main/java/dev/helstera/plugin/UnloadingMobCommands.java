package dev.helstera.plugin;

import dev.helstera.ai.AiProfile;
import dev.helstera.api.behavior.ManagedMobIdentity;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Brief command visibility between chunk unload and the native five-tick deregistration. */
final class UnloadingMobCommands {
    record Entry(ModelInstanceImpl instance, AiProfile profile, String mobId, String faction) {}
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    Entry remember(ModelInstanceImpl instance, AiProfile profile, String faction) {
        var entity = instance.entity();
        String mobId = ManagedMobIdentity.mobId(entity);
        if (mobId == null || entity instanceof org.bukkit.entity.Player) return null;
        var entry = new Entry(instance, profile == null ? null : new AiProfile(profile), mobId, faction);
        entries.put(entity.getUniqueId(), entry);
        return entry;
    }
    List<Entry> snapshot() { return List.copyOf(entries.values()); }
    boolean take(Entry entry) { return entries.remove(entry.instance().entity().getUniqueId(), entry); }
    void restored(UUID id) { entries.remove(id); }
    void clear() { entries.clear(); }
}
