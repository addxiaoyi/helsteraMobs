package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuraCallbackStateTest {
    private static LivingEntity entity(World world, AtomicReference<Location> point) {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.isValid()).thenReturn(true);
        when(entity.getLocation()).thenAnswer(ignored -> point.get().clone());
        when(entity.getMaxHealth()).thenReturn(20.0);
        when(entity.getHealth()).thenReturn(20.0);
        return entity;
    }

    @Test
    void multiTargetAuraSharesVariablesOnlyWithinEachTrackerAndFollowsItsTargetOrigin() {
        World world = mock(World.class);
        AtomicReference<Location> firstPoint = new AtomicReference<>(new Location(world, 1, 64, 0));
        AtomicReference<Location> secondPoint = new AtomicReference<>(new Location(world, 2, 64, 0));
        LivingEntity first = entity(world, firstPoint), second = entity(world, secondPoint);
        Location castOrigin = new Location(world, 100, 64, 0);
        SkillTreeContext parent = new SkillTreeContext(first, List.of(first, second), castOrigin);
        parent.setVariable("inherited", "before");
        SkillTreeContext caller = new SkillTreeContext(first, List.of(first, second), castOrigin, parent);
        caller.setVariable("seed", "before");
        caller.setVariable("aura-name", "caller-owned");
        long deadline = System.currentTimeMillis() + 60_000;
        caller.setVariable("expires", 7, deadline);
        caller.setLocation("saved", castOrigin, deadline);
        caller.defineBranch("gate", Set.of());
        BehaviorContext context = BehaviorContext.continueExecution(null, first, 1, 0, 0,
                "event", "payload", first, List.of(first, second), castOrigin, caller, mock(Cancellable.class));
        context.multiplySkillPower(2);
        AtomicLong clock = new AtomicLong(1);
        AuraRegistry registry = new AuraRegistry(clock::get, ignored -> true);
        SkillService skills = new SkillService(new BehaviorRegistryImpl(), null);
        Map<UUID, SkillTreeContext> trees = new HashMap<>();
        Map<UUID, Location> lastOrigins = new HashMap<>();
        AtomicInteger starts = new AtomicInteger(), ticks = new AtomicInteger(), ends = new AtomicInteger();
        SkillExtras.auraRegistry(registry);
        SkillExtras.auraStartSkillDispatcher((name, scoped) -> {
            LivingEntity target = scoped.targetEntity().orElseThrow();
            SkillTreeContext tree = scoped.skillTree().orElseThrow();
            assertNotSame(caller, tree);
            assertEquals("before", tree.variable("seed").orElseThrow());
            assertEquals("before", tree.variable("inherited").orElseThrow());
            assertEquals(deadline, tree.variableExpiration("expires"));
            assertEquals(deadline, tree.locationExpiration("saved"));
            assertEquals(castOrigin, scoped.origin().orElseThrow());
            assertTrue(scoped.cancellableEvent().isEmpty());
            assertEquals(2.0f, scoped.skillPower());
            scoped.multiplySkillPower(3);
            tree.setVariable("tracker-owner", target.getUniqueId().toString());
            tree.setVariable("counter", 0);
            tree.unlockBranch("gate");
            trees.put(target.getUniqueId(), tree);
            starts.incrementAndGet();
            return true;
        });
        SkillExtras.auraTickSkillDispatcher((name, scoped) -> {
            LivingEntity target = scoped.targetEntity().orElseThrow();
            SkillTreeContext tree = scoped.skillTree().orElseThrow();
            assertSame(trees.get(target.getUniqueId()), tree);
            assertEquals(target.getUniqueId().toString(), tree.variable("tracker-owner").orElseThrow());
            assertEquals("before", tree.variable("seed").orElseThrow());
            assertEquals("before", tree.variable("inherited").orElseThrow());
            assertTrue(tree.variable("late").isEmpty());
            assertTrue(tree.branchUnlocked("gate"));
            assertEquals(2.0f, scoped.skillPower());
            scoped.multiplySkillPower(4);
            assertTrue(scoped.cancellableEvent().isEmpty());
            assertTrue(scoped.trigger().isEmpty());
            assertEquals(target.getLocation(), scoped.origin().orElseThrow());
            assertEquals(target.getLocation(), tree.location("origin").orElseThrow());
            tree.setVariable("counter", ((Number) tree.variable("counter").orElseThrow()).intValue() + 1);
            lastOrigins.put(target.getUniqueId(), scoped.origin().orElseThrow());
            ticks.incrementAndGet();
            return true;
        });
        SkillExtras.auraEndSkillDispatcher((name, scoped) -> {
            LivingEntity target = scoped.targetEntity().orElseThrow();
            SkillTreeContext tree = scoped.skillTree().orElseThrow();
            assertSame(trees.get(target.getUniqueId()), tree);
            assertEquals(2, tree.variable("counter").orElseThrow());
            assertEquals(lastOrigins.get(target.getUniqueId()), scoped.origin().orElseThrow());
            assertEquals(lastOrigins.get(target.getUniqueId()), tree.location("origin").orElseThrow());
            assertEquals(2.0f, scoped.skillPower());
            ends.incrementAndGet();
            return true;
        });
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getEntity(first.getUniqueId())).thenReturn(first);
            bukkit.when(() -> Bukkit.getEntity(second.getUniqueId())).thenReturn(second);
            assertTrue(skills.runAction(skills.bindAction("aura{aura=ward;duration=100;interval=1;"
                    + "onStart=start;onTick=tick;onEnd=end}"), context));
            assertEquals(2, starts.get());
            assertNotSame(trees.get(first.getUniqueId()), trees.get(second.getUniqueId()));
            assertFalse(caller.branchUnlocked("gate"));
            assertTrue(caller.variable("counter").isEmpty());
            assertEquals("caller-owned", caller.variable("aura-name").orElseThrow());
            assertEquals(2.0f, context.skillPower());
            caller.setVariable("seed", "after"); caller.setVariable("late", true);
            parent.setVariable("inherited", "after"); context.multiplySkillPower(5);
            firstPoint.set(new Location(world, 5, 64, 0)); secondPoint.set(new Location(world, 8, 64, 0));
            clock.set(2); registry.tick();
            firstPoint.set(new Location(world, 6, 64, 0)); secondPoint.set(new Location(world, 9, 64, 0));
            clock.set(3); registry.tick();
            firstPoint.set(new Location(world, 10, 64, 0)); secondPoint.set(new Location(world, 11, 64, 0));
            registry.remove(first.getUniqueId(), "ward", Integer.MAX_VALUE);
            registry.remove(second.getUniqueId(), "ward", Integer.MAX_VALUE);
            assertEquals(4, ticks.get()); assertEquals(2, ends.get());
            assertEquals(10.0f, context.skillPower());
            assertEquals(castOrigin, caller.location("origin").orElseThrow());
        } finally {
            registry.clear(); SkillExtras.auraRegistry(null);
            SkillExtras.auraStartSkillDispatcher(null); SkillExtras.auraTickSkillDispatcher(null); SkillExtras.auraEndSkillDispatcher(null);
        }
    }

    @Test
    void endBeforeFirstTickUsesCastOriginAndStartVariables() {
        World world = mock(World.class);
        AtomicReference<Location> point = new AtomicReference<>(new Location(world, 1, 64, 0));
        LivingEntity target = entity(world, point);
        Location castOrigin = new Location(world, 30, 64, 0);
        BehaviorContext caller = BehaviorContext.continueExecution(null, target, 1, 0, 0,
                "cast", null, null, List.of(target), castOrigin,
                new SkillTreeContext(target, List.of(target), castOrigin));
        AuraCallbackState state = new AuraCallbackState(caller, target);
        BehaviorContext start = state.start(target);
        start.skillTree().orElseThrow().setVariable("from-start", "retained");
        point.set(new Location(world, 10, 64, 0));
        BehaviorContext end = state.end(target);
        assertEquals(castOrigin, end.origin().orElseThrow());
        assertEquals("retained", end.skillTree().orElseThrow().variable("from-start").orElseThrow());
        assertTrue(caller.skillTree().orElseThrow().variable("from-start").isEmpty());
    }

    @Test
    void endOnlyAuraUsesItsLatestScheduledOriginWithoutATickSkillOrTimer() {
        for (boolean runInterval : List.of(false, true)) {
            World world = mock(World.class);
            AtomicReference<Location> point = new AtomicReference<>(new Location(world, 1, 64, 0));
            LivingEntity target = entity(world, point);
            Location castOrigin = new Location(world, 100, 64, 0);
            BehaviorContext caller = BehaviorContext.continueExecution(null, target, 1, 0, 0,
                    "cast", null, null, List.of(target), castOrigin,
                    new SkillTreeContext(target, List.of(target), castOrigin));
            AtomicLong clock = new AtomicLong(1);
            AuraRegistry registry = new AuraRegistry(clock::get, ignored -> true);
            SkillService skills = new SkillService(new BehaviorRegistryImpl(), null);
            AtomicReference<BehaviorContext> end = new AtomicReference<>();
            SkillExtras.auraRegistry(registry);
            SkillExtras.auraEndSkillDispatcher((name, scoped) -> {
                end.set(scoped);
                return true;
            });
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getEntity(target.getUniqueId())).thenReturn(target);
                assertTrue(skills.runAction(skills.bindAction(
                        "aura{aura=ward;duration=100;interval=3;onEnd=end}"), caller));
                Location intervalOrigin = new Location(world, 5, 64, 0);
                point.set(intervalOrigin);
                clock.set(runInterval ? 4 : 3);
                registry.tick();
                point.set(new Location(world, 10, 64, 0));
                registry.remove(target.getUniqueId(), "ward", Integer.MAX_VALUE);
                assertNotNull(end.get());
                Location expected = runInterval ? intervalOrigin : castOrigin;
                assertEquals(expected, end.get().origin().orElseThrow());
                assertEquals(expected, end.get().skillTree().orElseThrow().location("origin").orElseThrow());
                assertEquals(castOrigin, caller.skillTree().orElseThrow().location("origin").orElseThrow());
            } finally {
                registry.clear();
                SkillExtras.auraRegistry(null);
                SkillExtras.auraEndSkillDispatcher(null);
            }
        }
    }

    @Test
    void separateSameNameTrackersKeepIndependentCallerSnapshots() {
        World world = mock(World.class);
        LivingEntity target = entity(world, new AtomicReference<>(new Location(world, 0, 64, 0)));
        BehaviorContext caller = BehaviorContext.continueExecution(null, target, 1, 0, 0,
                "cast", null, null, List.of(target), target.getLocation(),
                new SkillTreeContext(target, List.of(target), target.getLocation()));
        SkillTreeContext tree = caller.skillTree().orElseThrow();
        tree.setVariable("seed", 1);
        AuraCallbackState first = new AuraCallbackState(caller, target);
        tree.setVariable("seed", 2);
        AuraCallbackState second = new AuraCallbackState(caller, target);
        first.start(target).skillTree().orElseThrow().setVariable("seed", 11);
        tree.setVariable("seed", 3);
        assertEquals(11, first.tick(target).skillTree().orElseThrow().variable("seed").orElseThrow());
        assertEquals(2, second.tick(target).skillTree().orElseThrow().variable("seed").orElseThrow());
        first.tick(target).skillTree().orElseThrow().unsetVariable("seed");
        assertTrue(first.end(target).skillTree().orElseThrow().variable("seed").isEmpty());
        assertEquals(2, second.end(target).skillTree().orElseThrow().variable("seed").orElseThrow());
        assertEquals(3, tree.variable("seed").orElseThrow());
    }
    @Test
    void chargesAndDurationPlaceholdersAreResolvedForEachAffectedTarget() {
        World world = mock(World.class);
        LivingEntity first = entity(world, new AtomicReference<>(new Location(world, 0, 64, 0)));
        LivingEntity second = entity(world, new AtomicReference<>(new Location(world, 1, 64, 0)));
        when(first.getHealth()).thenReturn(4.0);
        when(second.getHealth()).thenReturn(9.0);
        when(first.getRemainingAir()).thenReturn(4);
        when(second.getRemainingAir()).thenReturn(9);
        BehaviorContext caller = BehaviorContext.continueExecution(null, first, 1, 0, 0,
                "cast", null, null, List.of(first, second), first.getLocation(),
                new SkillTreeContext(first, List.of(first, second), first.getLocation()));
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        SkillService skills = new SkillService(new BehaviorRegistryImpl(), null);
        Map<UUID, List<Long>> states = new HashMap<>();
        SkillExtras.auraRegistry(registry);
        SkillExtras.auraStartSkillDispatcher((name, scoped) -> {
            var tree = scoped.skillTree().orElseThrow();
            states.put(scoped.targetEntity().orElseThrow().getUniqueId(), List.of(
                    ((Number) tree.variable("aura-duration").orElseThrow()).longValue(),
                    ((Number) tree.variable("aura-charges").orElseThrow()).longValue()));
            return true;
        });
        try {
            String action = skills.bindAction("aura{aura=target_values;duration=<target.health>;charges=<target.air>;onStart=probe}");
            assertNotNull(action);
            assertTrue(skills.runAction(action, caller));
            assertEquals(Map.of(first.getUniqueId(), List.of(4L, 4L), second.getUniqueId(), List.of(9L, 9L)), states);
        } finally {
            registry.clear(); SkillExtras.auraRegistry(null); SkillExtras.auraStartSkillDispatcher(null);
        }
    }

}
