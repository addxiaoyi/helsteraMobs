package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

/** Minimal synthetic API shape used only to test reflective service discovery. */
@FunctionalInterface
public interface Economy {
    double getBalance(OfflinePlayer player);
}
