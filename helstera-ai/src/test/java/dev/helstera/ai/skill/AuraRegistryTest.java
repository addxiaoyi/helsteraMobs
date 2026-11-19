package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuraRegistryTest {

    @Test
    void expiresEntriesAtTheirTickAndCleanupDropsUnavailableTargets() {
        AtomicLong tick = new AtomicLong(10);
        UUID target = UUID.randomUUID();
        Set<UUID> available = new java.util.HashSet<>(Set.of(target));
        AuraRegistry registry = new AuraRegistry(tick::get, available::contains);

        assertTrue(registry.apply(target, null, "FURY", "HARMFUL", 10, 1,
                true, false, false, false, false));
        tick.set(19);
        assertTrue(registry.hasAura(target, "fury"));
        tick.set(20);
        assertFalse(registry.hasAura(target, "FURY"));

        registry.apply(target, null, "FURY", "HARMFUL", 10, 1,
                true, false, false, false, false);
        available.clear();
        registry.cleanupExpired();
        assertFalse(registry.hasAura(target, "FURY"));
    }

    @Test
    void mergesByCasterOrGloballyAndHonorsStackCapsAndRefreshPolicy() {
        AtomicLong tick = new AtomicLong(100);
        UUID target = UUID.randomUUID();
        UUID firstCaster = UUID.randomUUID();
        UUID secondCaster = UUID.randomUUID();
        AuraRegistry registry = new AuraRegistry(tick::get, ignored -> true);

        registry.apply(target, firstCaster, "FURY", "HARMFUL", 20, 2,
                false, true, false, false, false);
        tick.set(110);
        registry.apply(target, firstCaster, "FURY", "HARMFUL", 100, 2,
                false, true, false, false, false);
        assertTrue(registry.hasAuraStacks(target, "FURY", stacks -> stacks == 2));
        assertTrue(registry.hasAuraType(target, "harmful"));
        tick.set(120);
        assertFalse(registry.hasAura(target, "FURY"), "refreshDuration=false must retain the first expiry");

        registry.apply(target, firstCaster, "FURY", "HARMFUL", 100, 3,
                true, true, false, false, false);
        registry.apply(target, secondCaster, "FURY", "HARMFUL", 100, 3,
                true, true, false, false, false);
        assertFalse(registry.hasAuraStacks(target, "FURY", stacks -> stacks == 2),
                "same-caster merge keeps separate stacks for a different caster");

        registry.apply(target, secondCaster, "GLOBAL", "HELPFUL", 100, 3,
                true, false, true, false, false);
        registry.apply(target, firstCaster, "GLOBAL", "HELPFUL", 100, 3,
                true, false, true, false, false);
        assertTrue(registry.hasAuraStacks(target, "GLOBAL", stacks -> stacks == 2));
        assertTrue(registry.hasAuraType(target, "HELPFUL"));
    }

    @Test
    void overwriteOptionsReplaceTheSpecifiedCasterScopeAndRejectConflicts() {
        UUID target = UUID.randomUUID();
        UUID firstCaster = UUID.randomUUID();
        UUID secondCaster = UUID.randomUUID();
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);

        registry.apply(target, firstCaster, "WARD", "HELPFUL", 100, 4,
                true, true, false, false, false);
        registry.apply(target, firstCaster, "WARD", "HELPFUL", 100, 4,
                true, true, false, false, false);
        registry.apply(target, secondCaster, "WARD", "HELPFUL", 100, 4,
                true, true, false, false, false);
        registry.apply(target, secondCaster, "WARD", "HELPFUL", 100, 4,
                true, true, false, false, false);
        registry.apply(target, firstCaster, "WARD", "HELPFUL", 100, 4,
                true, false, false, true, false);
        assertTrue(registry.hasAuraStacks(target, "WARD", stacks -> stacks == 2),
                "overwriteSameCaster replaces only the first caster's stack");

        registry.apply(target, firstCaster, "WARD", "HELPFUL", 100, 4,
                true, false, false, false, true);
        assertTrue(registry.hasAuraStacks(target, "WARD", stacks -> stacks == 1));
        assertFalse(registry.hasAuraStacks(target, "WARD", stacks -> stacks == 2),
                "overwriteAll clears every previous caster's aura");
        assertFalse(registry.apply(target, firstCaster, "WARD", "HELPFUL", 100, 4,
                true, false, true, false, true));
    }

    @Test
    void applyResultDistinguishesFirstStartFromAStackMerge() {
        UUID target = UUID.randomUUID();
        UUID caster = UUID.randomUUID();
        AuraRegistry registry = new AuraRegistry(() -> 10, ignored -> true);

        AuraRegistry.ApplyResult first = registry.applyWithResult(target, caster, "FURY", "HARMFUL",
                100, 3, true, true, false, false, false);
        AuraRegistry.ApplyResult merged = registry.applyWithResult(target, caster, "FURY", "HARMFUL",
                100, 3, true, true, false, false, false);

        assertTrue(first.applied());
        assertTrue(first.started());
        assertTrue(merged.applied());
        assertFalse(merged.started());
    }

    @Test
    void periodicCallbacksHonorIntervalsAndMergesKeepTheOriginalSchedule() {
        AtomicLong tick = new AtomicLong(10);
        UUID target = UUID.randomUUID();
        AtomicInteger callbacks = new AtomicInteger();
        AuraRegistry registry = new AuraRegistry(tick::get, ignored -> true);
        java.util.function.Consumer<UUID> callback = ignored -> callbacks.incrementAndGet();

        registry.applyWithResult(target, null, "PULSE", "", 20, 3, true,
                true, false, false, false, 3, callback);
        tick.set(11);
        registry.applyWithResult(target, null, "PULSE", "", 20, 3, true,
                true, false, false, false, 8, ignored -> callbacks.addAndGet(100));
        registry.tick();
        assertEquals(0, callbacks.get());

        tick.set(13);
        registry.tick();
        assertEquals(1, callbacks.get());
        tick.set(14);
        registry.tick();
        assertEquals(1, callbacks.get());
        tick.set(16);
        registry.tick();
        assertEquals(2, callbacks.get(), "a stack merge must not reset or replace the active schedule");
        tick.set(31);
        registry.tick();
        assertFalse(registry.hasAura(target, "PULSE"));
        assertEquals(2, callbacks.get(), "expired auras must not keep ticking");
    }

    @Test
    void removingAnotherDueAuraCancelsItsQueuedCallback() {
        AtomicLong tick = new AtomicLong();
        UUID target = UUID.randomUUID();
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger removedCalls = new AtomicInteger();
        AuraRegistry registry = new AuraRegistry(tick::get, ignored -> true);
        registry.applyWithResult(target, null, "FIRST", "", 20, 1, true,
                false, false, false, false, 1, ignored -> {
                    firstCalls.incrementAndGet();
                    registry.remove(target, "SECOND", 1);
                });
        registry.applyWithResult(target, null, "SECOND", "", 20, 1, true,
                false, false, false, false, 1, ignored -> removedCalls.incrementAndGet());

        tick.set(1);
        registry.tick();

        assertEquals(1, firstCalls.get());
        assertEquals(0, removedCalls.get(), "a callback removed earlier in the same tick must not run");
    }

    @Test
    void endCallbacksRunOnExpiryAndOverwriteButRespectTerminateOption() {
        AtomicLong tick = new AtomicLong();
        UUID target = UUID.randomUUID();
        AtomicInteger callbacks = new AtomicInteger();
        AuraRegistry registry = new AuraRegistry(tick::get, ignored -> true);
        java.util.function.Consumer<UUID> callback = ignored -> callbacks.incrementAndGet();

        registry.applyWithResult(target, null, "EXPIRES", "", 3, 1, true,
                false, false, false, false, 1, null, callback, true);
        tick.set(3);
        registry.tick();
        registry.tick();
        assertEquals(1, callbacks.get(), "expiry must dispatch exactly one end callback");

        registry.applyWithResult(target, null, "REMOVED", "", 10, 1, true,
                false, false, false, false, 1, null, callback, false);
        assertEquals(1, registry.remove(target, "REMOVED", 1));
        assertEquals(1, callbacks.get(), "DoEndSkillOnTerminate=false suppresses explicit removal");

        registry.applyWithResult(target, null, "REPLACED", "", 10, 1, true,
                false, false, false, false, 1, null, callback, false);
        registry.applyWithResult(target, null, "REPLACED", "", 10, 1, true,
                false, false, false, true, 1, null, null, true);
        assertEquals(2, callbacks.get(), "overwrite must end the replaced aura regardless of terminate option");
    }

    @Test
    void endCallbackWaitsUntilTheFinalStackIsRemoved() {
        UUID target = UUID.randomUUID();
        AtomicInteger callbacks = new AtomicInteger();
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        java.util.function.Consumer<UUID> callback = ignored -> callbacks.incrementAndGet();

        registry.applyWithResult(target, null, "STACKED", "", 100, 3, true,
                true, false, false, false, 1, null, callback, true);
        registry.applyWithResult(target, null, "STACKED", "", 100, 3, true,
                true, false, false, false, 1, null, null, true);
        assertEquals(1, registry.remove(target, "STACKED", 1));
        assertEquals(0, callbacks.get());
        assertEquals(1, registry.remove(target, "STACKED", 1));
        assertEquals(1, callbacks.get());
    }

    @Test
    void removesRequestedStacksAcrossCastersAndAnyRemovesAllNames() {
        UUID target = UUID.randomUUID();
        UUID firstCaster = UUID.randomUUID();
        UUID secondCaster = UUID.randomUUID();
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);

        registry.apply(target, firstCaster, "FURY", "HARMFUL", 100, 4,
                true, true, false, false, false);
        registry.apply(target, firstCaster, "FURY", "HARMFUL", 100, 4,
                true, true, false, false, false);
        registry.apply(target, secondCaster, "FURY", "HARMFUL", 100, 4,
                true, true, false, false, false);
        registry.apply(target, firstCaster, "WARD", "HELPFUL", 100, 4,
                true, true, false, false, false);

        assertEquals(2, registry.remove(target, "fury", 2));
        assertTrue(registry.hasAuraStacks(target, "FURY", stacks -> stacks == 1));
        assertEquals(1, registry.remove(target, "FURY", Integer.MAX_VALUE));
        assertFalse(registry.hasAura(target, "FURY"));
        assertEquals(1, registry.remove(target, "ANY", Integer.MAX_VALUE));
        assertFalse(registry.hasAura(target, "WARD"));
        assertEquals(0, registry.remove(target, "ANY", 1));
    }

    @Test
    void mythicAuraBindingRetainsNamedOptionsAndDrivesAuraConditions() {
        LivingEntity target = mock(LivingEntity.class);
        UUID targetId = UUID.randomUUID();
        World world = mock(World.class);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.isValid()).thenReturn(true);
        when(target.isDead()).thenReturn(false);
        when(target.getLocation()).thenReturn(new Location(world, 0, 64, 0));

        AtomicLong tick = new AtomicLong(1);
        AuraRegistry auraRegistry = new AuraRegistry(tick::get, ignored -> true);
        BehaviorRegistryImpl behaviors = new BehaviorRegistryImpl();
        SkillService skills = new SkillService(behaviors, null);
        AtomicInteger startCalls = new AtomicInteger();
        behaviors.registerAction("aura-start-probe", scoped -> {
            assertEquals(target, scoped.targetEntity().orElse(null));
            startCalls.incrementAndGet();
        });
        AtomicInteger tickCalls = new AtomicInteger();
        behaviors.registerAction("aura-tick-probe", scoped -> {
            assertEquals(target, scoped.targetEntity().orElse(null));
            assertTrue(scoped.cancellableEvent().isEmpty(), "periodic skills must not retain the old event");
            tickCalls.incrementAndGet();
        });
        YamlConfiguration definitions = new YamlConfiguration();
        definitions.set("skills.aura_start_probe.on-decision", List.of("aura-start-probe"));
        definitions.set("skills.aura_tick_probe.on-decision", List.of("aura-tick-probe"));
        skills.loadSkills(definitions.getConfigurationSection("skills"));
        SkillExtras.auraRegistry(auraRegistry);
        SkillExtras.auraStartSkillDispatcher(skills::castSkill);
        SkillExtras.auraTickSkillDispatcher(skills::castSkill);
        try {
            BehaviorContext context = BehaviorContext.continueExecution(null, target, 1, 0, 0,
                    "test", null, null, List.of(target), target.getLocation(), null);
            String action = skills.bindAction("Aura{auraName=\"Burning Fury\";auratype=HELPFUL;"
                    + "duration=40;maxStacks=2;mergeSameCaster=true}");
            assertTrue(skills.runAction(action, context));
            assertTrue(skills.runAction(action, context));

            String auraWithStart = skills.bindAction(
                    "Aura{aura=START;duration=40;onStartSkill=aura_start_probe}");
            assertTrue(skills.runAction(auraWithStart, context));
            assertTrue(skills.runAction(auraWithStart, context));
            assertEquals(1, startCalls.get(), "merging another stack must not rerun the start skill");

            var event = mock(org.bukkit.event.Cancellable.class);
            var tickTree = new dev.helstera.api.behavior.SkillTreeContext(
                    target, List.of(target), target.getLocation());
            BehaviorContext eventContext = BehaviorContext.continueExecution(null, target, 1, 0, 0,
                    "event", null, target, List.of(target), target.getLocation(), tickTree, event);
            String auraWithTick = skills.bindAction(
                    "Aura{aura=TICK;duration=12;onTickSkill=aura_tick_probe;interval=3}");
            assertTrue(skills.runAction(auraWithTick, eventContext));
            assertTrue(skills.runAction(auraWithTick, eventContext));
            try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getEntity(targetId)).thenReturn(target);
                tick.set(4);
                auraRegistry.tick();
                assertEquals(1, tickCalls.get());
                tick.set(5);
                auraRegistry.tick();
                assertEquals(1, tickCalls.get());
                tick.set(7);
                auraRegistry.tick();
                assertEquals(2, tickCalls.get());
            }

            String hasAura = skills.bindCondition("mythic-has-aura aura=\"Burning Fury\"");
            String stacks = skills.bindCondition("mythic-has-aura-stacks aura=\"Burning Fury\" stacks=>=2");
            String type = skills.bindCondition("mythic-has-aura-type type=HELPFUL");
            assertTrue(behaviors.testCondition(hasAura, context));
            assertTrue(behaviors.testCondition(stacks, context));
            assertTrue(behaviors.testCondition(type, context));
            assertFalse(behaviors.testCondition(
                    skills.bindCondition("mythic-has-aura aura=MISSING"), context));
            assertFalse(behaviors.testCondition(
                    skills.bindCondition("mythic-has-aura-type type=HARMFUL"), context));

            String removeOne = skills.bindAction("RemoveAura{aura=\"Burning Fury\";stacks=1}");
            assertTrue(skills.runAction(removeOne, context));
            assertTrue(behaviors.testCondition(
                    skills.bindCondition("mythic-has-aura-stacks aura=\"Burning Fury\" stacks=1"), context));
            String removeRemaining = skills.bindAction("RemoveBuff{buff=\"Burning Fury\"}");
            assertTrue(skills.runAction(removeRemaining, context));
            assertFalse(behaviors.testCondition(hasAura, context));

            String defaultAura = skills.bindAction("Aura{duration=40}");
            String defaultRemove = skills.bindAction("AuraRemove");
            assertTrue(skills.runAction(defaultAura, context));
            assertTrue(behaviors.testCondition(skills.bindCondition("mythic-has-aura aura=default"), context));
            assertTrue(skills.runAction(defaultRemove, context));
            assertFalse(behaviors.testCondition(skills.bindCondition("mythic-has-aura aura=default"), context));

            String wildcardAura = skills.bindAction("Aura{aura=FURY;duration=40}");
            String otherAura = skills.bindAction("Aura{aura=WARD;duration=40}");
            assertTrue(skills.runAction(wildcardAura, context));
            assertTrue(skills.runAction(otherAura, context));
            assertTrue(skills.runAction(skills.bindAction("RemoveDebuff{aura=ANY}"), context));
            assertFalse(behaviors.testCondition(skills.bindCondition("mythic-has-aura aura=FURY"), context));
            assertFalse(behaviors.testCondition(skills.bindCondition("mythic-has-aura aura=WARD"), context));

            for (String alias : List.of("AuraRemove", "RemoveAura", "RemoveBuff", "RemoveDebuff")) {
                assertNotNull(skills.bindAction(alias), alias);
            }
            assertNull(skills.bindAction("AuraRemove{aura=FURY;unknown=true}"));
            assertNull(skills.bindAction("AuraRemove{aura=FURY;stacks=0}"));
            assertNull(skills.bindAction("Aura{aura=TICK;onTick=tick_probe;interval=0}"));
            assertNull(skills.bindCondition("mythic-has-aura aura=FURY unknown=true"));
            assertTrue(skills.warnings().stream().anyMatch(w -> w.contains("unknown")));
        } finally {
            SkillExtras.auraRegistry(null);
            SkillExtras.auraStartSkillDispatcher(null);
            SkillExtras.auraTickSkillDispatcher(null);
        }
    }

    @Test
    void mythicAuraEndSkillUsesFreshTargetContextAndTerminateOption() {
        LivingEntity target = mock(LivingEntity.class);
        UUID targetId = UUID.randomUUID();
        World world = mock(World.class);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.isValid()).thenReturn(true);
        when(target.isDead()).thenReturn(false);
        when(target.getLocation()).thenReturn(new Location(world, 0, 64, 0));

        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        BehaviorRegistryImpl behaviors = new BehaviorRegistryImpl();
        SkillService skills = new SkillService(behaviors, null);
        AtomicInteger endCalls = new AtomicInteger();
        behaviors.registerAction("aura-end-probe", scoped -> {
            assertEquals(target, scoped.targetEntity().orElse(null));
            assertEquals(List.of(target), scoped.targets());
            assertEquals("aura-end", scoped.state());
            assertTrue(scoped.cancellableEvent().isEmpty());
            assertTrue(scoped.trigger().isEmpty());
            endCalls.incrementAndGet();
        });
        YamlConfiguration definitions = new YamlConfiguration();
        definitions.set("skills.aura_end_probe.on-decision", List.of("aura-end-probe"));
        skills.loadSkills(definitions.getConfigurationSection("skills"));
        SkillExtras.auraRegistry(registry);
        SkillExtras.auraEndSkillDispatcher(skills::castSkill);
        try {
            BehaviorContext context = BehaviorContext.continueExecution(null, target, 1, 0, 0,
                    "event", null, target, List.of(target), target.getLocation(),
                    new dev.helstera.api.behavior.SkillTreeContext(target, List.of(target), target.getLocation()),
                    mock(org.bukkit.event.Cancellable.class));
            String aura = skills.bindAction("Aura{aura=END;duration=20;onEndSkill=aura_end_probe}");
            String suppressed = skills.bindAction(
                    "Aura{aura=SUPPRESSED;duration=20;onEnd=aura_end_probe;doEndSkillOnTerminate=false}");
            String removeEnd = skills.bindAction("AuraRemove{aura=END}");
            String removeSuppressed = skills.bindAction("AuraRemove{aura=SUPPRESSED}");
            try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getEntity(targetId)).thenReturn(target);
                assertTrue(skills.runAction(aura, context));
                assertTrue(skills.runAction(removeEnd, context));
                assertEquals(1, endCalls.get());
                assertTrue(skills.runAction(suppressed, context));
                assertTrue(skills.runAction(removeSuppressed, context));
                assertEquals(1, endCalls.get(), "DoEndSkillOnTerminate=false suppresses AuraRemove callback");
            }
        } finally {
            SkillExtras.auraRegistry(null);
            SkillExtras.auraEndSkillDispatcher(null);
        }
    }
}
