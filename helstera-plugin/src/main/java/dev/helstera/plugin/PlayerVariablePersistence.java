package dev.helstera.plugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

/** Restore before join skills, save after quit skills, and save online players on plugin disable. */
final class PlayerVariablePersistence implements Listener {
    private final JavaPlugin plugin;
    private final PlayerVariableStore store;
    private BukkitTask autosaveTask;
    PlayerVariablePersistence(JavaPlugin plugin) {
        this.plugin = plugin;
        store = new PlayerVariableStore(new File(plugin.getDataFolder(), "player-variables"));
    }
    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getOnlinePlayers().forEach(player -> load(player.getUniqueId()));
        Object configured = plugin.getConfig().get("variables.player-autosave-seconds", 300);
        long seconds;
        if ((configured instanceof Integer || configured instanceof Long) && ((Number) configured).longValue() >= 0
                && ((Number) configured).longValue() <= Long.MAX_VALUE / 20) {
            seconds = ((Number) configured).longValue();
        } else {
            plugin.getLogger().warning("Invalid variables.player-autosave-seconds; using 300 seconds");
            seconds = 300;
        }
        if (seconds > 0) autosaveTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::autosave, seconds * 20, seconds * 20);
    }
    @EventHandler(priority = EventPriority.LOWEST) public void onJoin(PlayerJoinEvent event) { load(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent event) { save(event.getPlayer().getUniqueId(), true); }
    void stop() {
        if (autosaveTask != null) { autosaveTask.cancel(); autosaveTask = null; }
        org.bukkit.event.HandlerList.unregisterAll(this);
        for (UUID id : store.loadedPlayers()) save(id, false);
    }
    private void autosave() {
        for (UUID id : store.loadedPlayers()) {
            var player = plugin.getServer().getPlayer(id);
            // Retry failed logout writes too, releasing their memory only after success.
            save(id, player == null || !player.isOnline());
        }
    }
    private void load(UUID id) {
        try { store.load(id); }
        catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Could not restore player variables for " + id + "; file will not be overwritten", e); }
    }
    private void save(UUID id, boolean release) {
        try { store.save(id, release); }
        catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Could not save player variables for " + id + "; previous file and live state retained", e); }
    }
}
