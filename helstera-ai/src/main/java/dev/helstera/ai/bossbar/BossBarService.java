package dev.helstera.ai.bossbar;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Boss 血条的 Bukkit 推送层：生成时挂条、受伤时更新、死亡时隐藏。
 *
 * <p>与 {@code BossBarState} 的分工：后者是纯决策层（分段配色、阶段、读条），
 * 本类只负责把决策结果推给 Bukkit 的 {@code BossBar} API。</p>
 *
 * <p><b>可见性过滤</b>：按距离与队伍过滤可见玩家。距离过滤避免全服玩家都看到
 * 远处 Boss 的血条；队伍过滤让友方 Boss 的血条不对敌方显示。</p>
 */
public final class BossBarService {

    private final Map<UUID, BossBar> bars = new HashMap<>();

    /**
     * 生成时挂条。
     *
     * @param entityUuid 实体 UUID
     * @param render 决策层输出
     * @param range 可见距离；0 表示不限
     */
    public void show(UUID entityUuid, BossBarState.Render render, double range) {
        if (!render.visible()) return;
        BossBar bar = bars.computeIfAbsent(entityUuid,
                k -> Bukkit.createBossBar(render.title(), toBarColor(render.color()), BarStyle.SOLID));
        bar.setTitle(render.title());
        bar.setProgress(render.ratio());
        bar.setColor(toBarColor(render.color()));
        bar.removeAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (range > 0 && p.getLocation().distance(
                    Bukkit.getEntity(entityUuid).getLocation()) > range) continue;
            bar.addPlayer(p);
        }
    }

    /**
     * 受伤时更新数值。
     *
     * @param entityUuid 实体 UUID
     * @param render 决策层输出
     */
    public void update(UUID entityUuid, BossBarState.Render render) {
        BossBar bar = bars.get(entityUuid);
        if (bar == null) return;
        bar.setTitle(render.title());
        bar.setProgress(render.ratio());
        bar.setColor(toBarColor(render.color()));
    }

    /**
     * 死亡时隐藏并清理。
     *
     * @param entityUuid 实体 UUID
     */
    public void hide(UUID entityUuid) {
        BossBar bar = bars.remove(entityUuid);
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
    }

    /** 命名颜色转 Bukkit BarColor；未知颜色回落到 GREEN。 */
    private static BarColor toBarColor(String named) {
        if (named == null || named.isBlank()) return BarColor.GREEN;
        try {
            return BarColor.valueOf(named.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BarColor.GREEN;
        }
    }
}