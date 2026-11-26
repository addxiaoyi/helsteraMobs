package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.util.MythicItemCopySpec;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copies real equipment stacks without reconstructing or consuming their metadata. */
final class ItemCopyActions {
    enum Result { SUCCESS, INVALID_CONFIG, INVALID_TARGET }

    private static final List<EquipmentSlot> COPIED_SLOTS = List.of(EquipmentSlot.HEAD,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.HAND, EquipmentSlot.OFF_HAND);
    private static volatile PaperFakeItemLoot fakeLoot;

    private ItemCopyActions() { }

    /** The host owns pending cosmetic packets and must close them before plugin shutdown. */
    static void host(Plugin plugin) {
        var previous = fakeLoot;
        fakeLoot = null;
        if (previous != null) previous.close();
        if (plugin != null) fakeLoot = new PaperFakeItemLoot(plugin);
    }

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories) {
        return wrap(factories, ItemCopyActions::showLoot);
    }

    static void showLoot(Player recipient, ItemStack item, Location origin) {
        var adapter = fakeLoot;
        if (adapter == null) throw new IllegalStateException("Fake item looting requires an installed plugin host");
        adapter.show(recipient, item, origin);
    }

    static Map<String, SkillCatalog.ActionFactory> wrap(Map<String, SkillCatalog.ActionFactory> factories,
                                                       FakeItemLoot animation) {
        var result = new LinkedHashMap<>(factories);
        for (String name : MythicItemCopySpec.names()) result.put(name, arguments -> {
            var spec = MythicItemCopySpec.parse(name, arguments);
            return context -> execute(spec, context, animation);
        });
        return Map.copyOf(result);
    }

    private static void execute(MythicItemCopySpec spec, BehaviorContext context, FakeItemLoot animation) {
        if (context == null || context.targetLocation().isPresent()) return;
        List<? extends Entity> recipients = !context.targets().isEmpty() ? List.copyOf(context.targets())
                : context.entityRecipient().map(List::of).orElseGet(List::of);
        for (Entity target : recipients) {
            try {
                Entity caster = context.instanceValid() ? context.instance().baseEntity().orElse(null) : null;
                apply(spec, caster, target, context.origin().orElse(null), animation);
            } catch (RuntimeException failure) {
                SkillFaults.swallow("ItemCopyActions#" + spec.mechanic(), failure);
            }
        }
    }

    static Result apply(MythicItemCopySpec spec, Entity caster, Entity target, Location origin,
                        FakeItemLoot animation) {
        if (spec.mechanic().equals("equip-copy")) {
            if (!(caster instanceof LivingEntity destination) || !(target instanceof LivingEntity source))
                return Result.SUCCESS;
            EntityEquipment equipment = destination.getEquipment();
            EntityEquipment original = source.getEquipment();
            if (equipment == null || original == null) return Result.SUCCESS;
            // Native visits duplicate aliases of these six slots. State is copied once per slot here.
            for (EquipmentSlot slot : COPIED_SLOTS) {
                ItemStack item = original.getItem(slot);
                // AIR is present and clears the destination; only absent/null equipment is skipped.
                if (item != null) equipment.setItem(slot, item.clone());
            }
            return Result.SUCCESS;
        }

        EquipmentSlot slot = equipmentSlot(spec.slot());
        EntityEquipment equipment = caster instanceof LivingEntity source ? source.getEquipment() : null;
        ItemStack original = slot == null || equipment == null ? null : equipment.getItem(slot);
        // Native checks the configured source slot before it checks the recipient's entity type.
        if (original == null) return Result.INVALID_CONFIG;
        if (!(target instanceof Player player)) return Result.INVALID_TARGET;

        ItemStack snapshot = original.clone();
        // addItem may mutate the submitted stack. The source and animation retain the full stack.
        var leftovers = player.getInventory().addItem(snapshot.clone());
        for (ItemStack leftover : leftovers.values())
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        if (spec.fakeLooting()) animation.show(player, snapshot, origin);
        return Result.SUCCESS;
    }

    private static EquipmentSlot equipmentSlot(MythicItemCopySpec.Slot slot) {
        return switch (slot) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
            case HAND -> EquipmentSlot.HAND;
            case OFFHAND -> EquipmentSlot.OFF_HAND;
            case NONE -> null;
        };
    }
}
