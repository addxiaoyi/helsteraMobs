package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuraReentrancyTest {
    private static AuraRegistry.ApplyResult apply(AuraRegistry registry, UUID target, String name,
                                                   long duration, boolean overwrite,
                                                   Consumer<AuraRegistry.Snapshot> tick,
                                                   Consumer<AuraRegistry.Snapshot> end, int charges) {
        return registry.applyTracked(target, null, name, "original", duration, 3,
                true, !overwrite, false, false, overwrite, 1, tick, end,
                true, Set.of(), charges, null);
    }

    @Test
    void oldEndRemovalCannotEndTheReplacementBeforeItsStartSkill() {
        for (boolean expiry : List.of(false, true)) {
            AtomicLong clock = new AtomicLong(1);
            AuraRegistry registry = new AuraRegistry(clock::get, ignored -> true);
            LivingEntity target = mock(LivingEntity.class);
            UUID id = UUID.randomUUID();
            when(target.getUniqueId()).thenReturn(id);
            when(target.isValid()).thenReturn(true);
            when(target.getLocation()).thenReturn(new Location(mock(World.class), 0, 64, 0));
            BehaviorContext context = BehaviorContext.continueExecution(null, target, 1, 0, 0,
                    "test", null, null, List.of(target), target.getLocation(),
                    new SkillTreeContext(target, List.of(target), target.getLocation()));
            SkillService skills = new SkillService(new BehaviorRegistryImpl(), null);
            List<String> callbacks = new ArrayList<>();
            SkillExtras.auraRegistry(registry);
            SkillExtras.auraStartSkillDispatcher((name, scoped) -> {
                callbacks.add(name);
                assertTrue(registry.hasAura(id, "ward"));
                return true;
            });
            SkillExtras.auraEndSkillDispatcher((name, scoped) -> {
                callbacks.add(name);
                if (name.equals("old_end")) registry.remove(id, "ward", Integer.MAX_VALUE);
                return true;
            });
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(target);
                assertTrue(skills.runAction(skills.bindAction("aura{aura=ward;duration=1;onEnd=old_end}"), context));
                if (expiry) clock.set(2);
                assertTrue(skills.runAction(skills.bindAction("aura{aura=ward;duration=20;overwriteAll=true;"
                        + "onStart=new_start;onEnd=new_end}"), context));
                assertEquals(List.of("old_end", "new_start"), callbacks);
                assertTrue(registry.hasAura(id, "ward"));
                registry.remove(id, "ward", Integer.MAX_VALUE);
                assertEquals(List.of("old_end", "new_start", "new_end"), callbacks);
            } finally {
                registry.clear();
                SkillExtras.auraRegistry(null);
                SkillExtras.auraStartSkillDispatcher(null);
                SkillExtras.auraEndSkillDispatcher(null);
            }
        }
    }

    @Test
    void oldEndMayRenewTheSameAuraWithoutRecursivelyTerminatingThePendingReplacement() {
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        UUID target = UUID.randomUUID();
        AtomicInteger ends = new AtomicInteger();
        AtomicReference<Consumer<AuraRegistry.Snapshot>> renew = new AtomicReference<>();
        renew.set(snapshot -> {
            // Bound a broken implementation's recursion so the assertion reports the error.
            if (ends.incrementAndGet() < 10) apply(registry, target, "ward", 20, true, null, renew.get(), 0);
        });
        apply(registry, target, "ward", 20, false, null, renew.get(), 0);
        var replacement = apply(registry, target, "ward", 20, true, null, renew.get(), 0);
        assertTrue(replacement.started());
        assertEquals(1, ends.get());
        assertTrue(registry.hasAura(target, "ward"));
        registry.clear();
        assertEquals(1, ends.get());
    }

    @Test
    void clearDuringOldEndInvalidatesThePendingApplication() {
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        UUID target = UUID.randomUUID();
        AtomicInteger cleaned = new AtomicInteger();
        registry.applyTracked(target, null, "ward", "", 20, 1, true, true, false,
                false, false, 1, null, snapshot -> registry.clear(), true, Set.of(), 0, cleaned::incrementAndGet);
        var replacement = apply(registry, target, "ward", 20, true, null, null, 0);
        assertFalse(replacement.applied());
        assertFalse(replacement.started());
        assertNull(replacement.snapshot());
        assertFalse(registry.hasAura(target, "ward"));
        assertEquals(1, cleaned.get());
    }

    @Test
    void clearDuringFirstEndDiscardsOtherQueuedEndsButAlwaysCleansTheirResources() {
        for (boolean expire : List.of(false, true)) {
            AtomicLong clock = new AtomicLong(1);
            AuraRegistry registry = new AuraRegistry(clock::get, ignored -> true);
            UUID target = UUID.randomUUID();
            List<String> calls = new ArrayList<>();
            AtomicInteger cleaned = new AtomicInteger();
            for (String name : List.of("first", "second")) {
                registry.applyTracked(target, null, name, "", 1, 1, true, true, false,
                        false, false, 1, null, snapshot -> {
                            calls.add(name);
                            if (name.equals("first")) registry.clear();
                        }, true, Set.of(AuraRegistry.CancelEvent.QUIT), 0, cleaned::incrementAndGet);
            }
            if (expire) {
                clock.set(2);
                registry.tick();
            } else {
                registry.cancel(target, AuraRegistry.CancelEvent.QUIT);
            }
            assertEquals(List.of("first"), calls, "reload must discard callbacks already queued by the old generation");
            assertEquals(2, cleaned.get(), "discarding a callback must still close its visual resources");
            registry.clear();
            assertEquals(2, cleaned.get());
        }
    }

    @Test
    void clearDiscardsQueuedTickEvenWhenSameNameAndCallbackAreRecreated() {
        for (boolean clearFromEnd : List.of(false, true)) {
            AtomicLong clock = new AtomicLong(1);
            AuraRegistry registry = new AuraRegistry(clock::get, ignored -> true);
            UUID target = UUID.randomUUID();
            AtomicInteger ticks = new AtomicInteger();
            Consumer<AuraRegistry.Snapshot> tick = snapshot -> ticks.incrementAndGet();
            Consumer<AuraRegistry.Snapshot> clearAndReapply = snapshot -> {
                registry.clear();
                apply(registry, target, "pending", 20, false, tick, null, 0);
            };
            apply(registry, target, "first", clearFromEnd ? 1 : 20, false,
                    clearFromEnd ? null : clearAndReapply, clearFromEnd ? clearAndReapply : null, 0);
            apply(registry, target, "pending", 20, false, tick, null, 0);
            clock.set(2);
            registry.tick();
            assertEquals(0, ticks.get(), "clear must discard a tick already queued for the removed tracker");
            assertTrue(registry.hasAura(target, "pending"));
            clock.set(3);
            registry.tick();
            assertEquals(1, ticks.get(), "the replacement must start ticking on its own schedule");
        }
    }

    @Test
    void clearDuringFirstTargetStartStopsTheOldCastBeforeItsOtherTargets() {
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        World world = mock(World.class);
        List<LivingEntity> targets = List.of(mock(LivingEntity.class), mock(LivingEntity.class));
        for (LivingEntity target : targets) {
            when(target.getUniqueId()).thenReturn(UUID.randomUUID());
            when(target.isValid()).thenReturn(true);
            when(target.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        }
        BehaviorContext context = BehaviorContext.continueExecution(null, targets.getFirst(), 1, 0, 0,
                "cast", null, null, targets, targets.getFirst().getLocation(),
                new SkillTreeContext(targets.getFirst(), targets, targets.getFirst().getLocation()));
        SkillService skills = new SkillService(new BehaviorRegistryImpl(), null);
        AtomicInteger starts = new AtomicInteger();
        SkillExtras.auraRegistry(registry);
        SkillExtras.auraStartSkillDispatcher((name, scoped) -> {
            starts.incrementAndGet();
            registry.clear();
            return true;
        });
        try {
            assertTrue(skills.runAction(skills.bindAction("aura{aura=old;duration=20;onStart=reload}"), context));
            assertEquals(1, starts.get(), "the first callback's reload must stop the remaining old targets");
            for (LivingEntity target : targets) assertFalse(registry.hasAura(target.getUniqueId(), "old"));
        } finally {
            registry.clear();
            SkillExtras.auraRegistry(null);
            SkillExtras.auraStartSkillDispatcher(null);
        }
    }

    @Test
    void clearDuringOldEndAllowsANewNestedCastButInvalidatesAllPendingOldTargets() {
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        World world = mock(World.class);
        List<LivingEntity> targets = List.of(mock(LivingEntity.class), mock(LivingEntity.class));
        for (LivingEntity target : targets) {
            when(target.getUniqueId()).thenReturn(UUID.randomUUID());
            when(target.isValid()).thenReturn(true);
            when(target.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        }
        BehaviorContext context = BehaviorContext.continueExecution(null, targets.getFirst(), 1, 0, 0,
                "cast", null, null, targets, targets.getFirst().getLocation(),
                new SkillTreeContext(targets.getFirst(), targets, targets.getFirst().getLocation()));
        SkillService skills = new SkillService(new BehaviorRegistryImpl(), null);
        String freshCast = skills.bindAction("aura{aura=fresh;duration=20;onStart=fresh_start}");
        List<String> starts = new ArrayList<>();
        SkillExtras.auraRegistry(registry);
        SkillExtras.auraStartSkillDispatcher((name, scoped) -> {
            starts.add(name);
            return true;
        });
        apply(registry, targets.getFirst().getUniqueId(), "old", 20, false, null, snapshot -> {
            registry.clear();
            assertTrue(skills.runAction(freshCast, context));
        }, 0);
        try {
            assertTrue(skills.runAction(skills.bindAction(
                    "aura{aura=old;duration=20;overwriteAll=true;onStart=old_start}"), context));
            assertEquals(List.of("fresh_start", "fresh_start"), starts);
            for (LivingEntity target : targets) {
                assertTrue(registry.hasAura(target.getUniqueId(), "fresh"));
                assertFalse(registry.hasAura(target.getUniqueId(), "old"));
            }
        } finally {
            registry.clear();
            SkillExtras.auraRegistry(null);
            SkillExtras.auraStartSkillDispatcher(null);
        }
    }

    @Test
    void mergeCannotReplaceTheOriginalNonRefreshingPolicyOrReduceItsStackCap() {
        AtomicLong clock = new AtomicLong(1);
        AuraRegistry registry = new AuraRegistry(clock::get, ignored -> true);
        UUID target = UUID.randomUUID();
        var initial = registry.applyTracked(target, null, "ward", "original", 20, 2,
                false, true, false, false, false, 1, null, null, true, Set.of(), 2, null);
        registry.consumeCharge(initial.snapshot().id());
        clock.set(11);
        var merged = registry.applyTracked(target, null, "ward", "replacement", 200, 100,
                true, true, false, false, false, 99, null, null, false, Set.of(), 99, null);
        assertEquals(initial.snapshot().id(), merged.snapshot().id());
        assertEquals(2, merged.snapshot().stacks());
        assertEquals(1, merged.snapshot().charges());
        assertEquals(10, merged.snapshot().remainingTicks());
        assertEquals(20, merged.snapshot().durationTicks());
        assertEquals("original", merged.snapshot().type());
        var third = registry.applyTracked(target, null, "ward", "other", 200, 1,
                true, true, false, false, false, 99, null, null, true, Set.of(), 99, null);
        assertEquals(2, third.snapshot().stacks());
        assertFalse(registry.hasAuraType(target, "replacement"));
        clock.set(21);
        assertFalse(registry.hasAura(target, "ward"));
    }

    @Test
    void refreshingMergeUsesOriginalDurationChargesTypeAndStackCap() {
        AtomicLong clock = new AtomicLong(1);
        AuraRegistry registry = new AuraRegistry(clock::get, ignored -> true);
        UUID target = UUID.randomUUID();
        var initial = registry.applyTracked(target, null, "ward", "original", 20, 1,
                true, true, false, false, false, 1, null, null, true, Set.of(), 2, null);
        registry.consumeCharge(initial.snapshot().id());
        clock.set(11);
        var merged = registry.applyTracked(target, null, "ward", "replacement", 200, 100,
                false, true, false, false, false, 99, null, null, false, Set.of(), 99, null);
        assertEquals(initial.snapshot().id(), merged.snapshot().id());
        assertEquals(1, merged.snapshot().stacks());
        assertEquals(2, merged.snapshot().charges());
        assertEquals(20, merged.snapshot().remainingTicks());
        assertEquals(20, merged.snapshot().durationTicks());
        assertEquals("original", merged.snapshot().type());
        clock.set(30);
        assertTrue(registry.hasAura(target, "ward"));
        clock.set(31);
        assertFalse(registry.hasAura(target, "ward"));
    }

    @Test
    void dueTickReadsStateRefreshedByAnEarlierExpiryCallback() {
        AtomicLong clock = new AtomicLong(1);
        AuraRegistry registry = new AuraRegistry(clock::get, ignored -> true);
        UUID target = UUID.randomUUID();
        List<AuraRegistry.Snapshot> ticks = new ArrayList<>();
        var live = apply(registry, target, "live", 20, false, ticks::add, null, 2);
        registry.consumeCharge(live.snapshot().id());
        apply(registry, target, "expiring", 1, false, null,
                snapshot -> apply(registry, target, "live", 200, false, null, null, 7), 0);
        clock.set(2);
        registry.tick();
        assertEquals(1, ticks.size());
        var observed = ticks.getFirst();
        assertEquals(live.snapshot().id(), observed.id());
        assertEquals(2, observed.stacks());
        assertEquals(2, observed.charges());
        assertEquals(20, observed.remainingTicks());
    }

    @Test
    void dueTickReadsChargesConsumedByAnEarlierTickCallback() {
        AtomicLong clock = new AtomicLong(1);
        AuraRegistry registry = new AuraRegistry(clock::get, ignored -> true);
        UUID target = UUID.randomUUID();
        AtomicReference<UUID> consumed = new AtomicReference<>();
        apply(registry, target, "first", 20, false, snapshot -> registry.consumeCharge(consumed.get()), null, 0);
        List<AuraRegistry.Snapshot> ticks = new ArrayList<>();
        var second = apply(registry, target, "second", 20, false, ticks::add, null, 2);
        consumed.set(second.snapshot().id());
        clock.set(2);
        registry.tick();
        assertEquals(1, ticks.size());
        assertEquals(1, ticks.getFirst().charges());
        clock.set(3);
        registry.tick();
        assertEquals(1, ticks.size(), "the exhausted tracker must not execute its already queued tick");
    }
}
