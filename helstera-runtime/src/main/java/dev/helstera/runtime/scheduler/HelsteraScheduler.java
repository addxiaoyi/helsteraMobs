package dev.helstera.runtime.scheduler;

import dev.helstera.api.instance.ModelInstance;
import dev.helstera.runtime.animation.AnimationControllerImpl;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * 统一 Tick 调度器（轻量化运行时核心）：
 * - 全引擎只有一个重复任务，禁止每实例创建定时器；
 * - 批量更新队列 + 每 Tick 最大更新数 + 每玩家实例上限检查；
 * - 距离 LOD：远距离玩家降低动画采样率；
 * - 无订阅者（视距外全员）的实例暂停更新。
 */
public final class HelsteraScheduler {

    private final Plugin plugin;
    private final InstanceManagerImpl instances;
    private final ToIntFunction<ModelInstance> visibleCount;
    private final ToDoubleFunction<ModelInstance> nearestViewerDistance;

    private BukkitTask task;
    private int updateRateTicks = 1;          // render.update-rate（多少 Tick 更新一次动画采样）
    private int maxUpdatesPerTick = 200;      // 每 Tick 最大实例更新数
    private double lodFarDistance = 32;       // 超过此距离隔 Tick 更新
    private double lodFartherDistance = 64;   // 超过此距离 1/4 频率更新
    private long tick = 0;

    // 可观测性计数
    private volatile long updatesLastTick;
    private volatile long totalUpdates;
    private volatile long skippedNoViewer;
    private volatile long skippedBudget;
    private volatile double lastSampleMillis;

    public HelsteraScheduler(Plugin plugin, InstanceManagerImpl instances,
                             ToIntFunction<ModelInstance> visibleCount,
                             ToDoubleFunction<ModelInstance> nearestViewerDistance) {
        this.plugin = plugin;
        this.instances = instances;
        this.visibleCount = visibleCount;
        this.nearestViewerDistance = nearestViewerDistance;
    }

    public void configure(int updateRate, int maxUpdatesPerTick, double lodFar, double lodFarther) {
        this.updateRateTicks = Math.max(1, updateRate);
        this.maxUpdatesPerTick = Math.max(8, maxUpdatesPerTick);
        this.lodFarDistance = lodFar;
        this.lodFartherDistance = Math.max(lodFar, lodFarther);
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 检测基础实体位置/朝向变化并同步显示实体（骑乘、传送、行走）。 */
    private void syncLocations() {
        var renderer = instances.renderer();
        if (renderer == null) return;
        for (ModelInstanceImpl inst : instances.allImpl()) {
            if (!inst.isValid()) continue;
            Entity e = inst.entity();
            Location last = inst.lastLocation();
            if (e == null || !e.isValid()) continue;
            Location cur = e.getLocation();
            if (last == null || last.getWorld() != cur.getWorld()
                    || last.getX() != cur.getX() || last.getY() != cur.getY() || last.getZ() != cur.getZ()
                    || last.getYaw() != cur.getYaw()) {
                inst.lastLocationSet(cur);
                inst.setRotation(cur.getYaw(), cur.getPitch());
                try {
                    renderer.updateLocation(inst);
                } catch (Throwable ignored) {
                }
            }
            inst.stateMachineGround(e.isOnGround());
        }
    }

    private void tickAll() {
        tick++;
        long start = System.nanoTime();
        List<ModelInstanceImpl> batch = new ArrayList<>(instances.allImpl());

        // 基础实体移动同步 & 失效清理每 Tick 执行（位置检测便宜）
        instances.cleanupInvalid();

        if (tick % updateRateTicks != 0) {
            updatesLastTick = 0;
            return;
        }

        float dt = updateRateTicks / 20.0f;
        int budget = maxUpdatesPerTick;
        long updates = 0;
        long skippedView = 0;
        long skippedBudgetLocal = 0;

        for (ModelInstanceImpl inst : batch) {
            if (!inst.isValid() || inst.dead) continue;
            int viewers = visibleCount.applyAsInt(inst);
            if (viewers == 0) {
                skippedView++;
                continue; // 没有订阅者：暂停采样（视距外）
            }
            // LOD：按最近观众距离决定本 Tick 是否采样
            double dist = nearestViewerDistance.applyAsDouble(inst);
            int interval = 1;
            if (dist > lodFartherDistance) interval = 4;
            else if (dist > lodFarDistance) interval = 2;
            if (tick % interval != 0) continue;

            if (budget-- <= 0) {
                skippedBudgetLocal++;
                continue; // 超出预算：本轮跳过（远距离优先降级）
            }

            // 1. 推进动画 & 采样姿态
            AnimationControllerImpl anim = inst.animationImpl();
            if (anim != null) {
                anim.tick(dt * interval);
                if (inst.stateMachine != null) inst.stateMachine.tick();
                inst.setPose(anim.samplePose());
            }
            // 2. 渲染层应用姿态
            try {
                dev.helstera.runtime.instance.InstanceRenderer renderer = instances.renderer();
                if (renderer != null) {
                    renderer.updateTransforms(inst);
                }
            } catch (Throwable t) {
                // 渲染异常不允许中断调度
            }
            updates++;
        }

        updatesLastTick = updates;
        totalUpdates += updates;
        skippedNoViewer = skippedView;
        skippedBudget = skippedBudgetLocal;
        lastSampleMillis = (System.nanoTime() - start) / 1_000_000.0;
    }

    // ---- 可观测性 ----
    public long updatesLastTick() { return updatesLastTick; }
    public long totalUpdates() { return totalUpdates; }
    public long skippedNoViewer() { return skippedNoViewer; }
    public long skippedBudget() { return skippedBudget; }
    public double lastSampleMillis() { return lastSampleMillis; }
}
