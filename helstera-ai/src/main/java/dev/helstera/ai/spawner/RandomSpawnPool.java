package dev.helstera.ai.spawner;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Weighted mob selection for random-spawn integrations. */
public final class RandomSpawnPool {
    public record Entry(String mobId, double weight) {
        public Entry {
            if (mobId == null || mobId.isBlank()) throw new IllegalArgumentException("mobId is blank");
            if (!Double.isFinite(weight) || weight <= 0) throw new IllegalArgumentException("weight must be positive");
            if (weight > 1_000_000) throw new IllegalArgumentException("weight is too large");
            mobId = mobId.trim();
        }
    }

    private final List<Entry> entries;
    private final double totalWeight;

    public RandomSpawnPool(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("pool is empty");
        this.entries = List.copyOf(new ArrayList<>(entries));
        totalWeight = this.entries.stream().mapToDouble(Entry::weight).sum();
    }

    public List<Entry> entries() { return entries; }

    public String choose(Random random) {
        if (random == null) throw new IllegalArgumentException("random is null");
        double cursor = random.nextDouble() * totalWeight;
        for (Entry entry : entries) {
            cursor -= entry.weight();
            if (cursor < 0) return entry.mobId();
        }
        return entries.get(entries.size() - 1).mobId();
    }
}
