package dev.helstera.ai.skill;

import dev.helstera.ai.loot.GeneratedLoot;
import dev.helstera.ai.loot.LootService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.util.MythicGeneratedLootSpec;
import dev.helstera.api.util.MythicRewardEntry;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Caster rewards with a selected entity/location used only as the animation origin. */
final class GeneratedLootActions {
    private static volatile LootService loot;
    private GeneratedLootActions() { }

    static void loot(LootService service) { loot = service; }

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories) {
        var result = new LinkedHashMap<>(factories);
        result.put("give-item-from-target", arguments -> {
            var spec = MythicGeneratedLootSpec.parse(arguments);
            return context -> {
                var service = loot;
                if (service == null) { SkillFaults.swallow("GeneratedLootActions#service",
                        new IllegalStateException("Generated loot requires the loot service")); return; }
                execute(spec, context, new GeneratedLoot(service), ItemCopyActions::showLoot, new Random());
            };
        });
        return Map.copyOf(result);
    }

    static void execute(MythicGeneratedLootSpec spec, BehaviorContext context, GeneratedLoot generator,
                        FakeItemLoot animation, Random random) {
        if (context == null || !context.instanceValid()) return;
        Entity caster = context.instance().baseEntity().orElse(null);
        if (caster == null) return;
        if (context.targetLocation().isPresent()) {
            apply(spec, context, caster, context.targetLocation().orElseThrow(), generator, animation, random);
        } else {
            List<? extends Entity> selected = !context.targets().isEmpty() ? List.copyOf(context.targets())
                    : context.entityRecipient().map(List::of).orElseGet(List::of);
            for (Entity entity : selected)
                apply(spec, context, caster, entity.getLocation(), generator, animation, random);
        }
    }

    private static void apply(MythicGeneratedLootSpec spec, BehaviorContext context, Entity caster, Location origin,
                              GeneratedLoot generator, FakeItemLoot animation, Random random) {
        try {
            double level = context.instance() instanceof ModelInstanceImpl instance ? instance.level : 1;
            double luck = 0;
            if (caster instanceof LivingEntity living) {
                var attribute = living.getAttribute(Attribute.GENERIC_LUCK);
                if (attribute != null) luck = (int) attribute.getValue();
            }
            var bag = generator.generate(spec, caster, level, luck, random);
            if (!(caster instanceof Player player)) return;
            // Native creates new drop metadata: incoming trigger, selected target and skill variables do not leak.
            var metadata = BehaviorContext.execution(context.instance(), player, 1, 0, 0,
                    "generated-loot", null, caster, List.of(player), caster.getLocation());
            for (GeneratedLoot.Reward reward : bag.rewards()) {
                var command = reward.entry().reward().command();
                if (command != null) { command(command, reward.amount(), metadata, player); continue; }
                var item = generator.materialize(reward);
                if (item == null) continue;
                for (var leftover : player.getInventory().addItem(item.clone()).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover, dropped -> {
                        dropped.setCanMobPickup(false);
                        dropped.setOwner(player.getUniqueId());
                        dropped.setThrower(player.getUniqueId());
                    });
                }
            }
            if (bag.experience() != 0) player.giveExp((int) bag.experience());
            if (spec.fakeLooting()) for (GeneratedLoot.Reward reward : bag.rewards()) {
                var visual = generator.materialize(reward);
                if (visual != null) animation.show(player, visual, origin);
            }
        } catch (RuntimeException failure) {
            SkillFaults.swallow("GeneratedLootActions#give-item-from-target", failure);
        }
    }

    static void command(MythicRewardEntry.Command command, double amount, BehaviorContext metadata, Player player) {
        String rendered = Placeholders.resolve(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                command.text()).replace("<drop.amount>", Integer.toString((int) amount)), metadata);
        // Public CommandDrop only uses player.performCommand when asop is set with ascaster/astrigger.
        if ((command.asCaster() || command.asTrigger()) && command.asOperator()) {
            boolean previous = player.isOp();
            try { player.setOp(true); player.performCommand(rendered); }
            finally { player.setOp(previous); }
        } else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rendered);
    }
}
