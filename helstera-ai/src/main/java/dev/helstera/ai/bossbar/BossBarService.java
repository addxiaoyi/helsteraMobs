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
    private volatile int showCount;
    private volatile int updateCount;
    private volatile int hideCount;

    /**
     * 生成时挂条。
     *
     * @param entityUuid 实体 UUID
     * @param render 决策层输出
     * @param range 可见距离；0 表示不限
     */
    public void show(UUID entityUuid, BossBarState.Render render, double range) {
        if (!render.visible()) return;
        showCount++;
        BossBar bar = bars.computeIfAbsent(entityUuid,
                k -> Bukkit.createBossBar(render.title(), toBarColor(render.color()), BarStyle.SOLID));
        bar.setTitle(render.title());
        bar.setProgress(render.ratio());
        bar.setColor(toBarColor(render.color()));
        bar.removeAll();

        // 实体位置只取一次：原实现在玩家循环里反复调 Bukkit.getEntity()，
        // 人数为 N 就查 N 次。更糟的是实体已卸载时 getEntity 返回 null，
        // 范围判断直接 NPE——而这恰恰发生在「Boss 刚死/刚被卸载」的瞬间，
        // 也就是最可能发生的时候。NPE 会冒泡出去，onModelSpawn 整个失败。
        org.bukkit.Location anchor = locOf(entityUuid);
        int visible = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            // anchor 为 null（实体已卸载）时不做距离过滤：宁可让所有人看到，
            // 也不要因为取不到实体位置而抛 NPE。条目会在 update/hide 时被清理。
            if (range > 0 && anchor != null && p.getLocation().distance(anchor) > range) continue;
            bar.addPlayer(p);
            visible++;
        }
        // 日志：生成时至少打一条，方便真服调试
        org.bukkit.plugin.java.JavaPlugin plugin = null;
        try {
            plugin = (org.bukkit.plugin.java.JavaPlugin) Bukkit.getPluginManager().getPlugin("helsteraMobs");
        } catch (Throwable ignored) {
        }
        if (plugin != null) {
            // 字段名刻意用「实体」而非「实例」：这里拿到的是 Bukkit 实体 UUID，
            // 不是 instanceId。项目其它日志一律写「实例 #N」，
            // 这里若也写「实例」会让管理员按 instanceId 检索时完全找不到对应记录。
            plugin.getLogger().info("[BossBar] show 实体=" + entityUuid +
                    " 标题=" + render.title() +
                    " 血量比例=" + String.format("%.2f", render.ratio()) +
                    " 可见玩家=" + visible);
        }
    }

    /** 实体位置；实体已卸载或查询失败时返回 null，绝不抛异常。 */
    private static org.bukkit.Location locOf(UUID entityUuid) {
        try {
            org.bukkit.entity.Entity e = Bukkit.getEntity(entityUuid);
            return e == null ? null : e.getLocation();
        } catch (Throwable ignored) {
            return null;
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
        updateCount++;
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
        hideCount++;
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

    /** 本次运行累计调用次数，供诊断命令使用。 */
    public int showCount() { return showCount; }
    public int updateCount() { return updateCount; }
    public int hideCount() { return hideCount; }
    public int barCount() { return bars.size(); }
}