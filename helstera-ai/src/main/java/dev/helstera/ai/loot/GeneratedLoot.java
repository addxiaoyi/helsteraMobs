package dev.helstera.ai.loot;

import dev.helstera.api.util.MythicGeneratedLootSpec;
import dev.helstera.api.util.MythicRewardEntry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Generates one mixed reward bag before delivery. Caster is both dropper and trigger. */
public final class GeneratedLoot {
    public record Reward(GeneratedLootTable.Entry entry, double amount) { }
    public record Bag(List<Reward> rewards, double experience) {
        public Bag { rewards = List.copyOf(rewards); }
    }
    private final LootService loot;

    public GeneratedLoot(LootService loot) { this.loot = java.util.Objects.requireNonNull(loot); }

    public Bag generate(MythicGeneratedLootSpec spec, Entity caster, double level, double luck, Random random) {
        GeneratedLootTable table = loot.rewardTable(spec.items());
        if (table == null) {
            List<GeneratedLootTable.Entry> entries = new ArrayList<>();
            for (String line : spec.inlineDrops()) entries.add(new GeneratedLootTable.Entry(MythicRewardEntry.parse(line), null));
            table = new GeneratedLootTable(DropTable.parse("<inline-reward>", new YamlConfiguration(), new ArrayList<>()), entries);
        }
        Accumulator result = new Accumulator();
        generate(table, caster, level, luck, random, result);
        return new Bag(result.rewards, result.experience);
    }

    private static final class Accumulator {
        final List<Reward> rewards = new ArrayList<>();
        double experience;
        int tables;
        int operations;
        void operation() {
            if (++operations > 10_000) throw new IllegalArgumentException("Generated loot exceeds 10000 operations");
        }
    }

    private void generate(GeneratedLootTable table, Entity caster, double level, double luck,
                          Random random, Accumulator result) {
        result.operation();
        DropTable options = table.options();
        if (!loot.rewardConditionsPass(options, caster)) return;
        if (++result.tables > 100) return; // Native metadata limits nested multi-drop expansions.
        if (options.experienceMax() > 0) result.experience += options.experienceMin()
                + (options.experienceMax() > options.experienceMin()
                ? random.nextInt(options.experienceMax() - options.experienceMin() + 1) : 0);
        int bonus = (int) (level * sample(options.bonusLevelItemsMin(), options.bonusLevelItemsMax(), random))
                + (int) (luck * sample(options.bonusLuckItemsMin(), options.bonusLuckItemsMax(), random));
        int minimum = options.totalItems() > 0 ? options.totalItems() : options.minItems();
        int maximum = options.totalItems() > 0 ? options.totalItems() : options.maxItems();
        var eligible = table.entries().stream().filter(entry -> entry.itemTemplate() == null
                || loot.rewardEntryPass(entry.itemTemplate(), caster)).toList();
        if (minimum > 0) {
            int upper = maximum > 0 ? maximum : table.entries().size();
            long count = (long) minimum + (upper > minimum ? random.nextInt(upper - minimum) : 0) + bonus;
            if (count > 10_000) throw new IllegalArgumentException("Generated loot count exceeds 10000");
            var weights = eligible.stream().mapToDouble(e -> chance(e, options, caster, luck)).toArray();
            double scale = java.util.Arrays.stream(weights).max().orElse(0);
            if (!(scale > 0)) return;
            double total = java.util.Arrays.stream(weights).map(weight -> weight / scale).sum();
            for (long i = 0; i < count; i++) {
                double roll = random.nextDouble() * total;
                for (int j = 0; j < eligible.size(); j++) {
                    roll -= weights[j] / scale;
                    if (roll < 0) { add(eligible.get(j), caster, level, luck, random, result); break; }
                }
            }
        } else {
            long generated = -(long) bonus;
            for (GeneratedLootTable.Entry entry : eligible) {
                double chance = chance(entry, options, caster, luck);
                if (chance >= 1 || random.nextDouble() < chance) {
                    generated++;
                    add(entry, caster, level, luck, random, result);
                }
                if (maximum > 0 && generated >= maximum) break;
            }
        }
    }

    private double chance(GeneratedLootTable.Entry entry, DropTable options, Entity caster, double luck) {
        return entry.itemTemplate() == null ? entry.reward().chance()
                : loot.rewardChance(entry.itemTemplate(), options, caster, luck);
    }

    private void add(GeneratedLootTable.Entry entry, Entity caster, double level, double luck,
                     Random random, Accumulator result) {
        result.operation();
        double amount = entry.itemTemplate() == null ? entry.reward().amount(random)
                : loot.rewardAmount(entry.itemTemplate(), random);
        String item = entry.reward().item();
        String tableName = item.regionMatches(true, 0, "table:", 0, 6) ? item.substring(6) : item;
        GeneratedLootTable nested = loot.rewardTable(tableName);
        if (nested != null && entry.reward().command() == null && !entry.reward().experience()) {
            for (int i = 0; i < amount && result.tables <= 100; i++) generate(nested, caster, level, luck, random, result);
        } else if (entry.reward().experience()) {
            result.experience += amount;
        } else if (entry.reward().command() != null || amount >= 1) {
            result.rewards.add(new Reward(entry, amount));
        }
    }

    /** Materializes independently for delivery and animation, as native custom drops do. */
    public ItemStack materialize(Reward reward) {
        if (reward.entry().reward().command() != null) return null;
        DropTable.Entry template = reward.entry().itemTemplate();
        if (template == null) template = new DropTable.Entry(reward.entry().reward().item(), 1, 1, 1,
                false, java.util.Map.of(), null, null, false, null);
        var items = loot.materialize(List.of(new LootService.Hit(template, (int) reward.amount())));
        return items.isEmpty() ? null : items.getFirst();
    }

    private static double sample(double min, double max, Random random) {
        return min == max ? min : min + random.nextDouble() * (max - min);
    }
}
