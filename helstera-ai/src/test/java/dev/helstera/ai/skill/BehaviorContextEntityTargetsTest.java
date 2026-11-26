package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BehaviorContextEntityTargetsTest {
    @Test
    void mixedSelectionSurvivesMetadataCopiesAndExplicitRetargetingClearsIt() {
        World world = mock(World.class);
        Location origin = new Location(world, 0, 64, 0);
        Item item = mock(Item.class);
        when(item.getLocation()).thenReturn(new Location(world, 3, 64, 0));
        LivingEntity living = mock(LivingEntity.class);
        when(living.getLocation()).thenReturn(new Location(world, 4, 64, 0));
        when(living.getMaxHealth()).thenReturn(20.0);
        when(living.getHealth()).thenReturn(10.0);
        Cancellable event = mock(Cancellable.class);
        var context = BehaviorContext.continueExecution(null, living, 1, 0, 7, "event", "payload",
                living, List.of(living), origin, null, event, EntityDamageEvent.DamageCause.FIRE);
        context.multiplySkillPower(2);
        var input = new ArrayList<org.bukkit.entity.Entity>(List.of(item, living));
        var selected = context.withEntityTargets(input).withExecutionAudience(List.of());
        input.clear();
        var tree = new SkillTreeContext(living, selected.entityTargets(), origin);
        var copied = selected.withSkillTree(tree).withConditionScope(BehaviorContext.ConditionScope.TARGET)
                .copyForSkillMechanic();
        assertEquals(List.of(item, living), copied.entityTargets());
        assertEquals(List.of(living), copied.targets());
        assertSame(item, copied.entityRecipient().orElseThrow());
        assertTrue(copied.targetEntity().isEmpty());
        assertEquals(3, copied.distanceToTarget());
        assertEquals(2, copied.skillPower());
        assertEquals("payload", copied.payload().orElseThrow());
        assertEquals(context.trigger(), copied.trigger());
        assertSame(event, copied.cancellableEvent().orElseThrow());
        assertEquals(context.damageCause(), copied.damageCause());
        assertEquals(BehaviorContext.ConditionScope.TARGET, copied.conditionScope());
        assertTrue(copied.executionAudience().orElseThrow().isEmpty());
        assertEquals(List.of(item, living), copied.withoutCancellableEvent().entityTargets());
        assertTrue(copied.withoutCancellableEvent().cancellableEvent().isEmpty());
        assertEquals(List.of(item), copied.withEntityRecipient(item).entityTargets());
        assertEquals(List.of(living), copied.withTargetEntity(living).entityTargets());
        assertEquals(0.5, copied.withTargetEntity(living).healthRatio());
        var empty = copied.withEntityTargets(List.of());
        assertTrue(empty.entityTargetsPresent());
        assertTrue(empty.entityTargets().isEmpty());
        assertTrue(empty.entityRecipient().isEmpty());
        assertTrue(empty.targetEntity().isEmpty());
        var located = copied.withLocationTarget(origin);
        assertTrue(located.entityTargets().isEmpty());
        assertTrue(located.entityRecipient().isEmpty());
        assertEquals(List.of(living), context.entityTargets());
    }

    @Test
    void skillTreeInheritanceExplicitEmptyAuraCopyAndRestoreKeepGenericEntities() {
        Item item = mock(Item.class);
        LivingEntity living = mock(LivingEntity.class);
        SkillTreeContext parent = new SkillTreeContext(living, List.of(item, living));
        parent.setVariable("kept", 7);
        SkillTreeContext child = new SkillTreeContext(living, List.of(), null, parent);
        assertEquals(List.of(item, living), child.entityTargets());
        assertEquals(List.of(living), child.targets());
        var copy = child.copyForAuraTracker(living, child.entityTargets());
        assertEquals(List.of(item, living), copy.entityTargets());
        assertEquals(7, copy.variable("kept").orElseThrow());
        var restored = SkillTreeContext.restore(child.snapshot(), living, List.of(item), ignored -> null);
        assertEquals(List.of(item), restored.entityTargets());
        assertTrue(restored.targets().isEmpty());
        child.entityTargets(List.of());
        assertTrue(child.entityTargets().isEmpty());
        assertTrue(child.targets().isEmpty());
        assertEquals(List.of(item, living), parent.entityTargets());
        child.targets(List.of(living));
        assertEquals(List.of(living), child.entityTargets());
    }
}
