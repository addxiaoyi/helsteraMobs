package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Wolf;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntityStateConditionTest {

    private static Predicate<BehaviorContext> condition(String name) {
        return SkillExtras.conditions().get(name).create(List.of());
    }

    private static BehaviorContext context(LivingEntity target) {
        BehaviorContext context = mock(BehaviorContext.class);
        when(context.targetEntity()).thenReturn(Optional.ofNullable(target));
        return context;
    }

    @Test
    void mountedChecksWhetherTheCurrentTargetRidesAVehicle() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.isInsideVehicle()).thenReturn(true);

        assertTrue(condition("mounted").test(context(target)));
        when(target.isInsideVehicle()).thenReturn(false);
        assertFalse(condition("mounted").test(context(target)));
        assertFalse(condition("mounted").test(context(null)));
    }

    @Test
    void movingRequiresFiniteNonzeroVelocity() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getVelocity()).thenReturn(new Vector(0.1, 0.0, 0.0));
        assertTrue(condition("moving").test(context(target)));

        when(target.getVelocity()).thenReturn(new Vector());
        assertFalse(condition("moving").test(context(target)));

        when(target.getVelocity()).thenReturn(new Vector(Double.NaN, 0.0, 0.0));
        assertFalse(condition("moving").test(context(target)));
    }

    @Test
    void hasPassengerChecksTheCurrentTarget() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getPassengers()).thenReturn(List.of(mock(Entity.class)));
        assertTrue(condition("has-passenger").test(context(target)));

        when(target.getPassengers()).thenReturn(List.of());
        assertFalse(condition("has-passenger").test(context(target)));
        assertFalse(condition("has-passenger").test(context(null)));
    }

    @Test
    void isLeashedChecksTheCurrentTarget() {
        Wolf wolf = mock(Wolf.class);
        when(wolf.isLeashed()).thenReturn(true);
        assertTrue(condition("is-leashed").test(context(wolf)));

        when(wolf.isLeashed()).thenReturn(false);
        assertFalse(condition("is-leashed").test(context(wolf)));
        assertFalse(condition("is-leashed").test(context(null)));
    }

    @Test
    void hasOwnerChecksVanillaTameableOwnership() {
        Wolf wolf = mock(Wolf.class);
        when(wolf.getOwner()).thenReturn(mock(org.bukkit.entity.AnimalTamer.class));
        assertTrue(condition("has-owner").test(context(wolf)));

        when(wolf.getOwner()).thenReturn(null);
        assertFalse(condition("has-owner").test(context(wolf)));
        assertFalse(condition("has-owner").test(context(null)));
    }

    @Test
    void hasTagChecksExactScoreboardTagOnCurrentTarget() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getScoreboardTags()).thenReturn(java.util.Set.of("BossDefeated"));
        assertTrue(SkillExtras.conditions().get("has-tag").create(List.of("tag=BossDefeated"))
                .test(context(target)));
        assertFalse(SkillExtras.conditions().get("has-tag").create(List.of("t=Other"))
                .test(context(target)));
        assertFalse(SkillExtras.conditions().get("has-tag").create(List.of(""))
                .test(context(target)));
        assertFalse(SkillExtras.conditions().get("has-tag").create(List.of("BossDefeated"))
                .test(context(null)));
    }
}
