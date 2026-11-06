package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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

        AuraRegistry auraRegistry = new AuraRegistry(() -> 1, ignored -> true);
        BehaviorRegistryImpl behaviors = new BehaviorRegistryImpl();
        SkillService skills = new SkillService(behaviors, null);
        SkillExtras.auraRegistry(auraRegistry);
        try {
            BehaviorContext context = BehaviorContext.continueExecution(null, target, 1, 0, 0,
                    "test", null, null, List.of(target), target.getLocation(), null);
            String action = skills.bindAction("Aura{auraName=\"Burning Fury\";auratype=HELPFUL;"
                    + "duration=40;maxStacks=2;mergeSameCaster=true}");
            assertTrue(skills.runAction(action, context));
            assertTrue(skills.runAction(action, context));

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
            assertNull(skills.bindCondition("mythic-has-aura aura=FURY unknown=true"));
            assertTrue(skills.warnings().stream().anyMatch(w -> w.contains("unknown")));
        } finally {
            SkillExtras.auraRegistry(null);
        }
    }
}
