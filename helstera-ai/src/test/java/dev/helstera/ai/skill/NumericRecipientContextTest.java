package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.instance.ModelInstance;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NumericRecipientContextTest {
    private final LivingEntity caster = mock(LivingEntity.class);
    private final LivingEntity first = mock(LivingEntity.class);
    private final LivingEntity second = mock(LivingEntity.class);
    private final ModelInstance instance = mock(ModelInstance.class);
    // Bukkit Location retains its world weakly; keep the mock alive for the test.
    private final World world = mock(World.class);
    private final Location origin = new Location(world, 0, 64, 0);
    private final SkillTreeContext tree = new SkillTreeContext(first, List.of(first, second), origin);
    private final EntityDamageEvent event = mock(EntityDamageEvent.class);
    private final BehaviorContext context = BehaviorContext.continueExecution(instance, first, 0.99, -1, 7,
            "recipient", "payload", caster, List.of(first, second), origin, tree, event,
            EntityDamageEvent.DamageCause.ENTITY_ATTACK);
    private final SkillService service = new SkillService(new BehaviorRegistryImpl(), null);

    NumericRecipientContextTest() {
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(origin);
        for (var entity : List.of(caster, first, second)) {
            when(entity.isValid()).thenReturn(true);
            when(entity.getWorld()).thenReturn(world);
            when(entity.getLocation()).thenReturn(origin.clone().add(entity == second ? 4 : 2, 0, 0));
            when(entity.getMaxHealth()).thenReturn(entity == second ? 30.0 : 20.0);
        }
        when(first.getHealth()).thenReturn(3.125);
        when(second.getHealth()).thenReturn(5.25);
        when(first.getAbsorptionAmount()).thenReturn(0.125);
        when(second.getAbsorptionAmount()).thenReturn(0.25);
    }

    private void run(String source) {
        String key = service.bindAction(source);
        assertNotNull(key, source + service.warnings());
        assertTrue(service.runAction(key, context));
    }

    @Test
    void directAndBoundFactoriesUseEachRecipientsHealthAndRounding() {
        SkillCatalog.actions().get("heal-targets").create(List.of("<target.hp{r=2}>")).accept(context);
        verify(first).setHealth(6.255);
        verify(second).setHealth(10.5);
        run("damage{a=<target.hp>} @Targets");
        verify(first).damage(3.125, caster);
        verify(second).damage(5.25, caster);
        verify(caster, never()).setHealth(anyDouble());
        verify(caster, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void eachRecipientValidatesAllArgumentsAndAnInvalidFirstDoesNotBlockTheSecond() {
        when(first.getAbsorptionAmount()).thenReturn(Double.NaN);
        run("knockback{power=<target.hp>;y=<target.absorption>} @Targets");
        verify(first, never()).setVelocity(any());
        verify(second).setVelocity(new Vector(5.25, 0.25, 0));
        clearInvocations(first, second);
        when(first.getAbsorptionAmount()).thenReturn(0.125);
        when(second.getHealth()).thenReturn(Double.NaN);
        run("knockback{power=<target.hp>;y=<target.absorption>} @Targets");
        verify(first).setVelocity(new Vector(3.125, 0.125, 0));
        verify(second, never()).setVelocity(any());
    }

    @Test
    void statusSpeedAndMaximumHealthActionsUseRecipientNumbers() {
        run("setfire{t=<target.mhp{r=0}>} @Targets");
        verify(first).setFireTicks(20);
        verify(second).setFireTicks(30);
        var firstSpeed = mock(AttributeInstance.class);
        var secondSpeed = mock(AttributeInstance.class);
        when(first.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED)).thenReturn(firstSpeed);
        when(second.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED)).thenReturn(secondSpeed);
        when(firstSpeed.getBaseValue()).thenReturn(0.1);
        when(secondSpeed.getBaseValue()).thenReturn(0.2);
        run("modifyspeed{factor=<target.hp>} @Targets");
        verify(firstSpeed).setBaseValue(0.3125);
        verify(secondSpeed).setBaseValue(1.05);
        var firstMax = mock(AttributeInstance.class);
        var secondMax = mock(AttributeInstance.class);
        when(first.getAttribute(Attribute.GENERIC_MAX_HEALTH)).thenReturn(firstMax);
        when(second.getAttribute(Attribute.GENERIC_MAX_HEALTH)).thenReturn(secondMax);
        run("setmaxhealth{a=<target.hp>} @Targets");
        verify(firstMax).setBaseValue(3.125);
        verify(secondMax).setBaseValue(5.25);
    }

    @Test
    void directFactoriesSkipInactiveRecipientsAndContinueAfterScopeFailures() {
        var action = SkillCatalog.actions().get("heal-targets").create(List.of("<target.hp>"));
        when(first.isValid()).thenReturn(false);
        action.accept(context);
        verify(first, never()).setHealth(anyDouble());
        verify(second).setHealth(10.5);
        clearInvocations(second);
        when(first.isValid()).thenReturn(true);
        when(first.getMaxHealth()).thenThrow(new IllegalStateException("removed during callback"));
        action.accept(context);
        verify(second).setHealth(10.5);
        clearInvocations(second);
        when(second.isDead()).thenReturn(true);
        action.accept(context);
        verify(second, never()).setHealth(anyDouble());
    }

    @Test
    void recipientScopesPreserveExecutionMetadataWithoutReplacingInheritedTargets() {
        var audience = java.util.UUID.randomUUID();
        context.setExecutionAudience(List.of(audience));
        tree.setVariable("delta", 0.125);
        var seen = new ArrayList<BehaviorContext>();
        SkillCatalog.ActionFactory capture = args -> seen::add;
        var action = NumericMechanicActions.wrap(Map.of("heal-targets", capture)).get("heal-targets")
                .create(List.of("<skill.var.delta>"));
        action.accept(context);
        assertEquals(2, seen.size());
        for (int i = 0; i < seen.size(); i++) {
            var scoped = seen.get(i);
            var recipient = i == 0 ? first : second;
            assertSame(recipient, scoped.targetEntity().orElseThrow());
            assertEquals(List.of(recipient), scoped.targets());
            assertSame(tree, scoped.skillTree().orElseThrow());
            assertSame(event, scoped.cancellableEvent().orElseThrow());
            assertSame(caster, scoped.trigger().orElseThrow());
            assertEquals(EntityDamageEvent.DamageCause.ENTITY_ATTACK, scoped.damageCause().orElseThrow());
            assertEquals("payload", scoped.payload().orElseThrow());
            assertEquals(7, scoped.decisionCount());
            assertEquals(origin, scoped.origin().orElseThrow());
            assertEquals(i == 0 ? 2 : 4, scoped.distanceToTarget());
            assertEquals(java.util.Set.of(audience), scoped.executionAudience().orElseThrow());
        }
        assertSame(first, context.targetEntity().orElseThrow());
        assertEquals(List.of(first, second), context.targets());
        assertEquals(List.of(first, second), tree.targets());
    }

    @Test
    void delayedRepeatsResolveEachRecipientsLatestHealthAtExecutionTime() {
        var pending = new java.util.ArrayDeque<Runnable>();
        var delays = new ArrayList<Long>();
        SkillCatalog.taskScheduler((task, delay) -> {
            pending.add(task);
            delays.add(delay);
            return true;
        });
        try {
            run("heal{a=<target.hp>;delay=1;repeat=1;repeatinterval=2} @Targets");
            verify(first, never()).setHealth(anyDouble());
            verify(second, never()).setHealth(anyDouble());
            pending.remove().run();
            verify(first).setHealth(6.25);
            verify(second).setHealth(10.5);
            when(first.getHealth()).thenReturn(4.0);
            when(second.getHealth()).thenReturn(6.0);
            pending.remove().run();
            verify(first).setHealth(8);
            verify(second).setHealth(12);
            assertTrue(pending.isEmpty());
            assertEquals(List.of(1L, 2L), delays);
            assertEquals(List.of(first, second), tree.targets());
        } finally {
            SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void locationTargetsAndCasterOrRadialActionsRetainTheirRecipientContracts() {
        SkillCatalog.actions().get("heal-targets").create(List.of("<caster.hp>"))
                .accept(context.withLocationTarget(origin));
        verify(first, never()).setHealth(anyDouble());
        verify(second, never()).setHealth(anyDouble());
        when(caster.getHealth()).thenReturn(1.0);
        run("heal{a=<target.hp>}");
        verify(caster).setHealth(4.125);
        // A radial action must run its spatial query once, not once per inherited entity.
        when(origin.getWorld().getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(first, second));
        run("pull{power=<target.hp>}");
        verify(origin.getWorld()).getNearbyEntities(origin, 6.0, 6.0, 6.0);
        verify(first).setVelocity(new Vector(-3.125, 0, 0));
        verify(second).setVelocity(new Vector(-3.125, 0, 0));
    }
}
