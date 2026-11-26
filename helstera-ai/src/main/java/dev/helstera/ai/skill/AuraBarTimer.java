package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

/** A player-only aura timer, owned and closed by its registry tracker. */
final class AuraBarTimer {
    private final Player player;
    private final String display;
    private final BarColor color;
    private final BarStyle style;
    private BossBar bar;
    private boolean closed;

    AuraBarTimer(Player player, String display, BarColor color, BarStyle style) {
        this.player = player;
        this.display = display;
        this.color = color;
        this.style = style;
    }

    void update(AuraRegistry.Snapshot snapshot, BehaviorContext context) {
        if (closed || player == null || !player.isOnline()) return;
        String title = Placeholders.resolveDisplay(display, context);
        if (bar == null) {
            bar = Bukkit.createBossBar(title, color, style);
            bar.addPlayer(player);
        } else bar.setTitle(title);
        bar.setProgress(Math.clamp((double) snapshot.remainingTicks() / snapshot.durationTicks(), 0, 1));
    }

    void close() {
        closed = true;
        if (bar != null) {
            bar.removeAll();
            bar = null;
        }
    }
}
