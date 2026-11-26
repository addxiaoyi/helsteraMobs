package dev.helstera.ai.skill;

import dev.helstera.ai.loot.GeneratedLoot;
import dev.helstera.ai.loot.LootService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.util.MerchantTradeSpec;
import dev.helstera.api.util.MythicGeneratedLootSpec;
import dev.helstera.api.util.ShieldBreakSpec;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Merchant recipes use the selected merchant as dropper; UI uses caster villager/selected player. */
final class MerchantTradeActions {
    private static volatile LootService loot;
    private MerchantTradeActions() { }
    static void loot(LootService service) { loot = service; }

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories) {
        var result = new LinkedHashMap<>(factories);
        result.put("merchant-trade", arguments -> {
            var spec = MerchantTradeSpec.parse(arguments);
            return context -> {
                LootService service = loot;
                mutate(spec, context, service == null ? null : new GeneratedLoot(service), new Random());
            };
        });
        result.put("open-trades", arguments -> {
            boolean real = MerchantTradeSpec.realTrade(arguments);
            return context -> open(real, context);
        });
        return Map.copyOf(result);
    }

    static void mutate(MerchantTradeSpec spec, BehaviorContext context, GeneratedLoot generator, Random random) {
        if (context == null || !context.instanceValid() || context.targetLocation().isPresent()) return;
        for (Entity selected : recipients(context)) {
            if (!(selected instanceof Merchant merchant) || !selected.isValid() || selected.isDead()) continue;
            try {
                if (selected instanceof Villager villager && villager.getProfession() == Villager.Profession.NONE)
                    villager.setProfession(Villager.Profession.NITWIT);
                if (spec.action().equals("REMOVE")) {
                    var recipes = new ArrayList<>(merchant.getRecipes()); recipes.remove(spec.slot()); merchant.setRecipes(recipes);
                } else if (spec.action().equals("ADD") || spec.action().equals("REPLACE")) {
                    if (generator == null) throw new IllegalStateException("Trade items require the loot service");
                    MerchantRecipe recipe = recipe(spec, selected, context, generator, random);
                    if (spec.action().equals("REPLACE")) merchant.setRecipe(spec.slot(), recipe);
                    else {
                        var recipes = new ArrayList<>(merchant.getRecipes()); recipes.add(spec.slot(), recipe); merchant.setRecipes(recipes);
                    }
                }
            } catch (RuntimeException failure) { SkillFaults.swallow("MerchantTradeActions#mutate", failure); }
        }
    }

    private static MerchantRecipe recipe(MerchantTradeSpec spec, Entity selected, BehaviorContext context,
                                        GeneratedLoot generator, Random random) {
        var output = item(spec.result(), selected, context, generator, random);
        var first = item(spec.ingredient(), selected, context, generator, random);
        var second = spec.secondIngredient() == null ? null : item(spec.secondIngredient(), selected, context, generator, random);
        var recipe = new MerchantRecipe(output, 0, spec.maxUses(), spec.experienceReward(), spec.villagerExperience(),
                spec.priceMultiplier(), spec.demand(), spec.specialPrice(), spec.ignoreDiscounts());
        recipe.addIngredient(first);
        if (second != null) recipe.addIngredient(second);
        return recipe;
    }

    private static ItemStack item(String source, Entity merchant, BehaviorContext context, GeneratedLoot generator, Random random) {
        // Native splits at the first literal space; a quoted identifier is not a quoted item/amount pair.
        String[] pieces = source.split(" ", 2);
        int amount = 1;
        if (pieces.length == 2) {
            // Both mechanics use native PlaceholderInt; get(metadata) defaults its explicit entity to trigger.
            var amountContext = context.trigger().map(context::withEntityRecipient).orElse(context);
            amount = ShieldBreakSpec.parse(List.of("duration="+pieces[1]))
                    .duration(text -> Placeholders.resolve(text, amountContext), random);
        }
        double luck = 0;
        if (merchant instanceof LivingEntity living) {
            var attribute = living.getAttribute(Attribute.GENERIC_LUCK);
            if (attribute != null) luck = (int) attribute.getValue();
        }
        var bag = generator.generate(new MythicGeneratedLootSpec(pieces[0], false), merchant, 1, luck, random);
        ItemStack result = null;
        for (var reward : bag.rewards()) {
            if (reward.entry().reward().command() != null) continue;
            result = generator.materialize(new GeneratedLoot.Reward(reward.entry(), 1));
            if (result == null) throw new IllegalArgumentException("Unresolved merchant item: "+reward.entry().reward().item());
            break;
        }
        if (result == null) result = new ItemStack(Material.AIR);
        else result = result.clone();
        result.setAmount(amount);
        return result;
    }

    static void open(boolean real, BehaviorContext context) {
        if (context == null || !context.instanceValid() || context.targetLocation().isPresent()
                || !(context.instance().baseEntity().orElse(null) instanceof Villager villager)) return;
        for (Entity recipient : recipients(context)) if (recipient instanceof Player player && player.isOnline() && !player.isDead()) {
            try {
                if (real) player.openMerchant(villager, true);
                else {
                    Merchant copy = Bukkit.createMerchant(villager.customName());
                    copy.setRecipes(villager.getRecipes());
                    player.openMerchant(copy, true);
                }
            } catch (RuntimeException failure) { SkillFaults.swallow("MerchantTradeActions#open", failure); }
        }
    }

    private static List<? extends Entity> recipients(BehaviorContext context) {
        return !context.targets().isEmpty() ? List.copyOf(context.targets())
                : context.entityRecipient().map(List::of).orElseGet(List::of);
    }
}
