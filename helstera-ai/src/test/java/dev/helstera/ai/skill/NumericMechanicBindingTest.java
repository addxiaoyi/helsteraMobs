package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.util.NumericMechanicSpec;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NumericMechanicBindingTest {
    private final SkillService service = new SkillService(new BehaviorRegistryImpl(), null);
    private final World world = mock(World.class);
    private final LivingEntity caster = mock(LivingEntity.class);
    private final LivingEntity target = mock(LivingEntity.class);
    private final LivingEntity control = mock(LivingEntity.class);
    private final ModelInstance instance = mock(ModelInstance.class);
    private final Location origin = new Location(world, 0, 64, 0);
    private final SkillTreeContext tree = new SkillTreeContext(target, List.of(target, control), origin);

    NumericMechanicBindingTest() {
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(origin);
        for (var entity : List.of(caster, target, control)) {
            when(entity.isValid()).thenReturn(true);
            when(entity.getWorld()).thenReturn(world);
            when(entity.getHealth()).thenReturn(5.0);
            when(entity.getMaxHealth()).thenReturn(20.0);
            when(entity.getLocation()).thenReturn(new Location(world, entity == caster ? 0 : 2, 64, 0));
            when(entity.getVelocity()).thenAnswer(invocation -> new Vector(0.1, 0, 0.2));
        }
        when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(caster, target));
    }

    private BehaviorContext context() {
        return BehaviorContext.continueExecution(instance, target, 1, 2, 0, "test", null,
                target, List.of(target, control), origin, tree);
    }

    private void run(String line) {
        String action = service.bindAction(line);
        assertNotNull(action, line + ": " + service.warnings());
        assertTrue(service.runAction(action, context()));
    }

    @Test
    void reorderedAndOmittedKnockbackArgumentsPreserveMeaningAndTargetIsolation() {
        for (var test : Map.of("knockback{vertical=0.5;power=2} @Target", new Vector(2, 0.5, 0),
                "knockback{vertical=0.5} @Target", new Vector(1, 0.5, 0),
                "knockback-targets{power=2} @Target", new Vector(2, 0.4, 0),
                "knockback{delay=0;repeat=0} @Target", new Vector(1, 0.4, 0)).entrySet()) {
            clearInvocations(target, caster, control);
            run(test.getKey());
            verify(target).setVelocity(test.getValue());
            verify(caster, never()).setVelocity(any());
            verify(control, never()).setVelocity(any());
        }
    }

    @Test
    void directFactoriesUseTheSameOmittedDefaults() {
        SkillCatalog.actions().get("knockback-targets").create(List.of("y=0.2")).accept(context());
        verify(target).setVelocity(new Vector(1, 0.2, 0));
        SkillExtras.actions().get("pull").create(List.of("power=2")).accept(context());
        verify(world).getNearbyEntities(origin, 6.0, 6.0, 6.0);
        verify(target).setVelocity(new Vector(-2, 0, 0));
        SkillExtras.actions().get("damage-melee").create(List.of("r=4")).accept(context());
        verify(target).damage(3, caster);
    }

    @Test
    void explicitTargetVariantsAndCasterHealthRemainSeparate() {
        run("heal{health=3}");
        verify(caster).setHealth(8);
        run("heal-targets{value=2} @Target");
        verify(target).setHealth(7);
        run("set-health-targets{health=9} @Target");
        verify(target).setHealth(9);
        run("heal-percent-targets{p=25} @Target");
        verify(target).setHealth(10);
        run("damage-percent-targets{p=25} @Target");
        verify(target).damage(5, caster);
        verify(control, never()).setHealth(anyDouble());
        verify(control, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void dynamicValuesUseTheSelectedEntityAndUnresolvedArgumentsDoNothing() {
        run("leap{velocity=<target.hp.raw>} @Target");
        verify(target).setVelocity(new Vector(0.1, 5, 0.2));
        tree.setVariable("power", 2);
        run("knockback{y=0.5;power=<skill.var.power>} @Target");
        verify(target).setVelocity(new Vector(2, 0.5, 0));
        clearInvocations(target, caster, control, world);
        for (String line : List.of("sethealth{a=<missing.value>} @Target",
                "knockback{power=2;y=<missing.value>} @Target", "pull{power=<missing.value>}",
                "healpercent{p=<missing.value>} @Target", "setfire{t=<missing.value>} @Target")) run(line);
        verify(target, never()).setVelocity(any());
        verify(target, never()).setHealth(anyDouble());
        verify(target, never()).setFireTicks(anyInt());
        verify(world, never()).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void rejectsEveryCoveredFactorysUnknownAndDuplicateArgumentsAtBothBoundaries() {
        var factories = new java.util.LinkedHashMap<>(SkillCatalog.actions());
        factories.putAll(SkillExtras.actions());
        int checked = 0;
        for (var entry : factories.entrySet()) {
            if (!NumericMechanicSpec.supports(entry.getKey())) continue;
            checked++;
            assertNull(service.bindAction(entry.getKey() + "{unknown=1}"), entry.getKey());
            assertThrows(IllegalArgumentException.class, () -> entry.getValue().create(List.of("unknown=1")));
            assertNull(service.bindAction(entry.getKey() + "{1;NaN}"), entry.getKey());
        }
        assertTrue(checked >= 30, "covered=" + checked);
        for (String line : List.of("knockback{power=1;power=2}", "pull{power=1;strength=2}",
                "damage{amount=1;a=2}", "sethealth{health=}", "knockback{power=2;;y=1}",
                "heal @Target 2", "forcepull{velocity=1;yv=Infinity}")) assertNull(service.bindAction(line), line);
    }
}
