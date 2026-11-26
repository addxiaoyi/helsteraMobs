package dev.helstera.ai.skill;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Owns shields removed for the one-tick native disable sequence. Server thread only. */
public final class ShieldBreakService implements Listener, AutoCloseable {
    public enum Result { SUCCESS, CONDITION_FAILED, UNAVAILABLE }
    private static final class Pending {
        final Player player;
        final EquipmentSlot slot;
        final ItemStack shield;
        BukkitTask task;
        Pending(Player player, EquipmentSlot slot, ItemStack shield) {
            this.player = player; this.slot = slot; this.shield = shield;
        }
    }

    private final Plugin plugin;
    private final Map<Long, Pending> pending = new LinkedHashMap<>();
    private long sequence;
    private boolean closed;

    public ShieldBreakService(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public Result disable(Player player, java.util.function.IntSupplier duration) {
        if (closed || !plugin.isEnabled() || !plugin.getServer().isPrimaryThread()
                || player == null || !player.isOnline() || player.isDead()) return Result.UNAVAILABLE;
        if (!player.isBlocking()) return Result.CONDITION_FAILED;
        var inventory = player.getInventory();
        var item = inventory.getItemInMainHand();
        EquipmentSlot slot = EquipmentSlot.HAND;
        if (item == null || item.getType() != Material.SHIELD) {
            item = inventory.getItemInOffHand(); slot = EquipmentSlot.OFF_HAND;
        }
        if (item == null || item.getType() != Material.SHIELD) return Result.CONDITION_FAILED;
        ItemStack shield = item.clone();
        int ticks = duration.getAsInt();
        if (ticks < 0) throw new IllegalArgumentException("Cannot have negative cooldown");
        player.setCooldown(Material.SHIELD, ticks);
        player.playSound(player.getLocation(), "minecraft:item.shield.block", 1, 1);
        player.playSound(player.getLocation(), "minecraft:item.shield.break", 1, 1);
        inventory.setItem(slot, null);
        long id = ++sequence;
        var entry = new Pending(player, slot, shield);
        pending.put(id, entry);
        try {
            entry.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> restore(id), 1);
        } catch (RuntimeException failure) {
            restore(id);
            throw failure;
        }
        return Result.SUCCESS;
    }

    private Pending take(long id) {
        Pending entry = pending.remove(id);
        if (entry != null && entry.task != null) entry.task.cancel();
        return entry;
    }

    private void restore(long id) {
        Pending entry = pending.get(id);
        if (entry != null) {
            if (entry.task != null) entry.task.cancel();
            restore(entry, () -> pending.remove(id));
        }
    }

    private static void restore(Pending entry, Runnable transferred) {
        var inventory = entry.player.getInventory();
        ItemStack occupied = inventory.getItem(entry.slot);
        ItemStack displaced = occupied == null || occupied.getType() == Material.AIR ? null : occupied.clone();
        boolean full = displaced != null && inventory.firstEmpty() == -1;
        // Reserve the hand with the shield before reinserting a replacement stack.
        // Native addItem-before-clear can merge into the very slot it then clears.
        inventory.setItem(entry.slot, entry.shield.clone());
        // Release ownership only after the shield is back in the inventory.
        // If a later replacement insertion fails, retrying the shield would duplicate it.
        transferred.run();
        if (displaced == null) return;
        if (full) entry.player.getWorld().dropItem(entry.player.getLocation(), displaced);
        else for (ItemStack leftover : inventory.addItem(displaced).values())
            entry.player.getWorld().dropItem(entry.player.getLocation(), leftover);
    }

    private void restorePlayer(Player player) {
        for (long id : new ArrayList<>(pending.keySet())) {
            Pending entry = pending.get(id);
            if (entry != null && entry.player.getUniqueId().equals(player.getUniqueId())) restore(id);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) { restorePlayer(event.getPlayer()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        for (long id : new ArrayList<>(pending.keySet())) {
            Pending entry = pending.get(id);
            if (entry == null || !entry.player.getUniqueId().equals(event.getEntity().getUniqueId())) continue;
            if (event.getKeepInventory()) restore(id);
            else {
                if (entry.shield.getEnchantments().keySet().stream()
                        .noneMatch(enchantment -> enchantment.getKey().equals(NamespacedKey.minecraft("vanishing_curse"))))
                    event.getDrops().add(entry.shield.clone());
                take(id);
            }
        }
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) { if (event.getPlugin() == plugin) close(); }

    @Override public void close() {
        if (closed && pending.isEmpty()) return;
        closed = true;
        for (long id : new ArrayList<>(pending.keySet())) {
            try { restore(id); }
            catch (RuntimeException failure) { SkillFaults.swallow("ShieldBreakService#restore", failure); }
        }
        HandlerList.unregisterAll(this);
    }

    int pendingCount() { return pending.size(); }
}
