package dev.helstera.ai;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.BehaviorRegistry;
import dev.helstera.api.behavior.Targeters;
import dev.helstera.api.event.HelsteraEventBus;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiManagerConditionalLifecycleTest {
    @Test
    void threatProviderSurvivesRestartAndOlderManagerStop() {
        Plugin plugin = mock(Plugin.class);
        var bus = mock(HelsteraEventBus.class);
        var older = new AiManager(plugin, null, bus);
        var newer = new AiManager(plugin, null, bus);
        var candidate = mock(LivingEntity.class);
        when(candidate.isValid()).thenReturn(true);
        var candidates = List.of(candidate);
        var source = (dev.helstera.api.instance.ModelInstance) null;
        try (var bukkit = mockStatic(Bukkit.class)) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                    .thenReturn(mock(BukkitTask.class));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(mock(PluginManager.class));
            older.stop();
            assertEquals(candidates, Targeters.select("@living{sort=highest-threat}", source, candidates, List.of()));
            for (int i = 0; i < 2; i++) {
                newer.start(5);
                assertEquals(candidates, Targeters.select("@living{sort=highest-threat}", source, candidates, List.of()));
            }
            newer.stop();
            assertEquals(List.of(), Targeters.select("@living{sort=highest-threat}", source, candidates, List.of()));
        } finally {
            older.stop();
            newer.stop();
        }
    }

    @Test
    void stoppingOlderManagerDoesNotRemoveNewerProvider() {
        var bus = mock(HelsteraEventBus.class);
        var older = new AiManager(mock(Plugin.class), null, bus);
        var newer = new AiManager(mock(Plugin.class), null, bus);
        var registry = mock(BehaviorRegistry.class);
        when(registry.testCondition(eq("eligible"), any())).thenReturn(true);
        var candidate = mock(LivingEntity.class);
        when(candidate.isValid()).thenReturn(true);
        when(candidate.getMaxHealth()).thenReturn(20.0);
        var context = BehaviorContext.continueExecution(null, candidate, 1, 0, 0, "test",
                null, null, List.of(candidate), null, null);
        older.setBehaviors(registry);
        newer.setBehaviors(registry);
        newer.setConditionBinder(spec -> "eligible");
        try {
            older.stop();
            assertEquals(List.of(candidate), Targeters.select(
                    "@nearestConditionalTarget{conditions=[eligible]}", context, List.of(candidate), List.of()));
        } finally {
            newer.stop();
        }
    }

    @Test
    void conditionProviderSurvivesStartAndRestartAndStopsCleanly() {
        Plugin plugin = mock(Plugin.class);
        HelsteraEventBus bus = mock(HelsteraEventBus.class);
        BehaviorRegistry registry = mock(BehaviorRegistry.class);
        LivingEntity candidate = mock(LivingEntity.class);
        when(candidate.isValid()).thenReturn(true);
        when(candidate.getHealth()).thenReturn(16.0);
        when(candidate.getMaxHealth()).thenReturn(20.0);
        when(registry.testCondition(eq("eligible"), any())).thenReturn(true);
        var context = BehaviorContext.continueExecution(null, candidate, 1, 0, 0, "test",
                null, null, List.of(candidate), null, null);
        AiManager manager = new AiManager(plugin, null, bus);
        manager.setBehaviors(registry);
        manager.setConditionBinder(spec -> "eligible");
        try (var bukkit = mockStatic(Bukkit.class)) {
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            PluginManager plugins = mock(PluginManager.class);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                    .thenReturn(mock(BukkitTask.class));
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            for (int i = 0; i < 2; i++) {
                manager.start(5);
                assertEquals(List.of(candidate), Targeters.select(
                        "@nearestConditionalTarget{conditions=[eligible]}", context, List.of(candidate), List.of()));
            }
            manager.stop();
            assertEquals(List.of(), Targeters.select(
                    "@nearestConditionalTarget{conditions=[eligible]}", context, List.of(candidate), List.of()));
        }
    }
}
