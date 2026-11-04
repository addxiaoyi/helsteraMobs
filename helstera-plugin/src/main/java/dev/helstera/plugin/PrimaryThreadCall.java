package dev.helstera.plugin;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class PrimaryThreadCall {
    private PrimaryThreadCall() {}

    static boolean run(Plugin plugin, Callable<Boolean> action) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return Boolean.TRUE.equals(action.call());
            } catch (Exception ex) {
                plugin.getLogger().warning("Main-thread reload failed: " + ex.getMessage());
                return false;
            }
        }
        Future<Boolean> future = null;
        try {
            future = plugin.getServer().getScheduler().callSyncMethod(plugin, action);
            return Boolean.TRUE.equals(future.get(10, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (future != null) future.cancel(false);
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException ex) {
            if (future != null) future.cancel(false);
            plugin.getLogger().warning("Main-thread reload failed: " + ex.getMessage());
            return false;
        }
    }
}
