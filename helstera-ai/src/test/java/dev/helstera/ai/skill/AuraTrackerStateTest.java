package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuraTrackerStateTest {
    @Test
    void ticksDoNotConsumeChargesAndExplicitConsumptionEndsWithFinalStateExactlyOnce() {
        AtomicLong clock = new AtomicLong(1);
        AuraRegistry registry = new AuraRegistry(clock::get, ignored -> true);
        UUID target = UUID.randomUUID();
        List<AuraRegistry.Snapshot> ticks = new ArrayList<>();
        List<AuraRegistry.Snapshot> ends = new ArrayList<>();
        AtomicInteger cleanup = new AtomicInteger();
        var result = registry.applyTracked(target, null, "charged", "", 20, 1,
                true, true, false, false, false, 1, ticks::add, ends::add,
                false, Set.of(), 2, cleanup::incrementAndGet);
        clock.set(4);
        registry.tick();
        assertEquals(2, ticks.getFirst().charges());
        assertEquals(17, ticks.getFirst().remainingTicks());
        assertTrue(registry.consumeCharge(result.snapshot().id()));
        clock.set(5);
        registry.tick();
        assertEquals(1, ticks.getLast().charges());
        assertTrue(registry.consumeCharge(result.snapshot().id()));
        assertFalse(registry.hasAura(target, "charged"));
        assertEquals(0, ends.getFirst().charges());
        assertEquals(1, cleanup.get());
        assertFalse(registry.consumeCharge(result.snapshot().id()));
        registry.clear();
        assertEquals(1, ends.size());
        assertEquals(1, cleanup.get());
    }

    @Test
    void nonpositiveChargesAreUnlimitedAndMergesRetainTrackerAndRestoreInitialCharges() {
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        UUID target = UUID.randomUUID();
        for (int charges : new int[] {0, -1}) {
            var unlimited = registry.applyTracked(target, null, "unlimited", "", 20, 1,
                    true, true, false, false, false, 1, null, null, true, Set.of(), charges, null);
            assertFalse(registry.consumeCharge(unlimited.snapshot().id()));
            registry.clear();
        }
        var first = registry.applyTracked(target, null, "charged", "", 20, 3,
                true, true, false, false, false, 1, null, null, true, Set.of(), 2, null);
        registry.consumeCharge(first.snapshot().id());
        var merged = registry.applyTracked(target, null, "charged", "", 20, 3,
                true, true, false, false, false, 1, null, null, true, Set.of(), 7, null);
        assertFalse(merged.started());
        assertEquals(first.snapshot().id(), merged.snapshot().id());
        assertEquals(2, merged.snapshot().charges());
        assertEquals(2, merged.snapshot().stacks());
    }

    @Test
    void cleanupRunsWhenEndIsSuppressedAndUnavailableTargetsOrClearDiscardTrackers() {
        AtomicInteger cleaned = new AtomicInteger();
        AtomicInteger ended = new AtomicInteger();
        Set<UUID> available = new java.util.HashSet<>();
        AuraRegistry registry = new AuraRegistry(() -> 1, available::contains);
        for (int mode = 0; mode < 3; mode++) {
            UUID target = UUID.randomUUID();
            available.add(target);
            registry.applyTracked(target, null, "ward", "", 20, 1,
                    true, true, false, false, false, 1, null, ignored -> ended.incrementAndGet(),
                    false, Set.of(), 0, cleaned::incrementAndGet);
            if (mode == 0) registry.remove(target, "ward", 1);
            if (mode == 1) { available.clear(); registry.tick(); }
            if (mode == 2) registry.clear();
        }
        assertEquals(0, ended.get());
        assertEquals(3, cleaned.get());
    }

    @Test
    void failedEndCallbackStillCleansResources() {
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        AtomicInteger cleaned = new AtomicInteger();
        UUID target = UUID.randomUUID();
        registry.applyTracked(target, null, "ward", "", 20, 1,
                true, true, false, false, false, 1, null, ignored -> { throw new IllegalStateException(); },
                true, Set.of(AuraRegistry.CancelEvent.QUIT), 0, cleaned::incrementAndGet);
        registry.cancel(target, AuraRegistry.CancelEvent.QUIT);
        assertEquals(1, cleaned.get());
    }

    @Test
    void bossBarReflectsRemainingDurationAndCannotReappearAfterCleanup() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        BossBar bar = mock(BossBar.class);
        BehaviorContext context = BehaviorContext.execution(null, null, 1, -1, 0,
                "test", null, null, List.of(), null);
        context.skillTree().orElseThrow().setVariable("aura-name", "Ward");
        var snapshot = new AuraRegistry.Snapshot(UUID.randomUUID(), UUID.randomUUID(), "ward", "",
                1, 2, 5, 20);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createBossBar("Ward", BarColor.RED, BarStyle.SOLID)).thenReturn(bar);
            var timer = new AuraBarTimer(player, "<skill.var.aura-name>", BarColor.RED, BarStyle.SOLID);
            timer.update(snapshot, context);
            verify(bar).addPlayer(player);
            verify(bar).setProgress(0.25);
            timer.close();
            timer.close();
            timer.update(snapshot, context);
            verify(bar, times(1)).removeAll();
            bukkit.verify(() -> Bukkit.createBossBar("Ward", BarColor.RED, BarStyle.SOLID), times(1));
        }
    }
}
