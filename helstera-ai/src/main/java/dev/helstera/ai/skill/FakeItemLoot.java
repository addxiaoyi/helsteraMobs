package dev.helstera.ai.skill;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** A private cosmetic pickup; implementations must never add the item to the world or inventory. */
@FunctionalInterface
public interface FakeItemLoot {
    /** Called on the server thread after inventory delivery, with an independent item snapshot. */
    void show(Player recipient, ItemStack item, Location origin);
}
