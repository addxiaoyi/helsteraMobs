package dev.helstera.ai.loot;

import dev.helstera.api.util.MythicRewardEntry;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/** Reward-table view; retained native drop lines preserve commands and fractional amounts. */
public record GeneratedLootTable(DropTable options, List<Entry> entries) {
    public record Entry(MythicRewardEntry reward, DropTable.Entry itemTemplate) { }

    public GeneratedLootTable { entries = List.copyOf(entries); }

    static GeneratedLootTable parse(DropTable options, ConfigurationSection section) {
        List<Entry> entries = new ArrayList<>();
        if (section.contains("reward-drops")) {
            if (!(section.get("reward-drops") instanceof List<?> raw))
                throw new IllegalArgumentException("reward-drops must be a list");
            for (Object value : raw) {
                if (!(value instanceof String text)) throw new IllegalArgumentException("reward-drops must contain strings");
                entries.add(new Entry(MythicRewardEntry.parse(text), null));
            }
        } else {
            for (DropTable.Entry entry : options.entries()) {
                // The attached template delegates amounts/tiers to the inclusive Helstera configuration sampler.
                entries.add(new Entry(new MythicRewardEntry(entry.itemId(), entry.amountMin(), entry.amountMax(),
                        entry.chance(), null), entry));
            }
        }
        return new GeneratedLootTable(options, entries);
    }
}
