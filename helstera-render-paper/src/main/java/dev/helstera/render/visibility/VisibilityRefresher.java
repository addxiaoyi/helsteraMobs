package dev.helstera.render.visibility;

import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.ModelRegionEnterEvent;
import dev.helstera.api.event.ModelRegionLeaveEvent;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.model.ModelHitbox;
import dev.helstera.render.display.DisplayRenderer;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 可见性刷新器：
 * - 周期 diff 目标可见玩家 vs 当前订阅，渲染层 show/hide；
 * - 玩家进入/离开模型碰撞盒区域时发布事件；
 * - 供调度器查询可见人数与最近观众距离。
 */
public final class VisibilityRefresher {

    private final Plugin plugin;
    private final InstanceManagerImpl instances;
    private final PlayerVisibilityServiceImpl visibility;
    private final DisplayRenderer renderer;
    private final HelsteraEventBus bus;
    private BukkitTask task;
    /** 实例 -> 玩家 -> 在区域内。 */
    private final Map<Integer, Set<UUID>> insideRegion = new HashMap<>();

    public VisibilityRefresher(Plugin plugin, InstanceManagerImpl instances,
                               PlayerVisibilityServiceImpl visibility, DisplayRenderer renderer,
                               HelsteraEventBus bus) {
        this.plugin = plugin;
        this.instances = instances;
        this.visibility = visibility;
        this.renderer = renderer;
        this.bus = bus;
    }

    public void start(int refreshTicks) {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, Math.max(5, refreshTicks));
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void refresh() {
        for (ModelInstanceImpl inst : instances.allImpl()) {
            if (!inst.isValid()) continue;
            List<Player> target = visibility.targetViewers(inst);
            List<UUID> current = visibility.subscribersOf(inst);
            Set<UUID> targetIds = new HashSet<>();
            for (Player p : target) targetIds.add(p.getUniqueId());

            // 新增可见
            List<Player> toShow = new ArrayList<>();
            for (Player p : target) {
                if (!current.contains(p.getUniqueId())) toShow.add(p);
            }
            // 移除可见
            List<Player> toHide = new ArrayList<>();
            for (UUID id : current) {
                if (!targetIds.contains(id)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) toHide.add(p);
                }
            }
            renderer.applyVisibility(inst, toShow, toHide);
            visibility.setSubscribers(inst, target);

            checkRegion(inst, target);
        }
    }

    /** 区域进入/离开检测。 */
    private void checkRegion(ModelInstanceImpl inst, List<Player> viewers) {
        Location base = inst.location();
        if (base == null || base.getWorld() == null) return;
        ModelHitbox hb = inst.modelImpl().hitbox();
        double s = inst.getScale();
        double halfW = hb.width() * s / 2 + 0.2;
        double height = hb.height() * s + 0.2;

        Set<UUID> inside = insideRegion.computeIfAbsent(inst.instanceId(), k -> new HashSet<>());
        Set<UUID> nowInside = new HashSet<>();
        for (Player p : viewers) {
            Location pl = p.getLocation();
            double dx = Math.abs(pl.getX() - base.getX());
            double dz = Math.abs(pl.getZ() - base.getZ());
            double dy = pl.getY() - base.getY();
            if (dx <= halfW && dz <= halfW && dy >= -0.5 && dy <= height) {
                nowInside.add(p.getUniqueId());
                if (!inside.contains(p.getUniqueId())) {
                    bus.post(new ModelRegionEnterEvent(inst, p));
                }
            }
        }
        for (UUID id : inside) {
            if (!nowInside.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) bus.post(new ModelRegionLeaveEvent(inst, p));
            }
        }
        inside.clear();
        inside.addAll(nowInside);
    }

    // ---- 调度器查询 ----

    public int visibleCount(ModelInstance inst) {
        return visibility.subscribersOf(inst).size();
    }

    public double nearestViewerDistance(ModelInstance inst) {
        List<UUID> subs = visibility.subscribersOf(inst);
        Location base = inst.location();
        if (subs.isEmpty() || base == null || base.getWorld() == null) return Double.MAX_VALUE;
        double best = Double.MAX_VALUE;
        for (UUID id : subs) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.getWorld() != base.getWorld()) continue;
            double d = p.getLocation().distance(base);
            if (d < best) best = d;
        }
        return best;
    }
}
