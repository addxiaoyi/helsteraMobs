package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PercentHealthActionTest {
    private final SkillService service = new SkillService(new BehaviorRegistryImpl(), null);
    private final LivingEntity caster = mock(LivingEntity.class);
    private final LivingEntity target = mock(LivingEntity.class);
    private final LivingEntity control = mock(LivingEntity.class);
    private final ModelInstance instance = mock(ModelInstance.class);
    private final Location origin = new Location(mock(World.class), 0, 64, 0);
    private final SkillTreeContext tree = new SkillTreeContext(target, List.of(target, control), origin);
    private final BehaviorContext context = BehaviorContext.continueExecution(instance, target, 1, 2, 0,
            "test", null, target, List.of(target, control), origin, tree);

    PercentHealthActionTest() {
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(origin);
        for (var entity : List.of(caster, target, control)) {
            when(entity.isValid()).thenReturn(true);
            when(entity.getWorld()).thenReturn(origin.getWorld());
            when(entity.getLocation()).thenReturn(origin);
            when(entity.getHealth()).thenReturn(10.0);
            when(entity.getMaxHealth()).thenReturn(20.0);
        }
    }

    private void run(String source) {
        String key = service.bindAction(source);
        assertNotNull(key, source + service.warnings());
        assertTrue(service.runAction(key, context));
    }

    @Test
    void fractionalDamageSupportsMaxCurrentAndDefaultsWhileLegacyStaysPercentPoints() {
        run("damagepercent{p=0.25} @Target");
        verify(target).damage(5, caster);
        run("percentdamage{percent=0.25;current=true} @Target");
        verify(target).damage(2.5, caster);
        run("damagepercent @Target");
        verify(target).damage(2, caster);
        run("damage-percent{p=25} @Target");
        verify(target, times(2)).damage(5, caster);
        verify(caster, never()).damage(anyDouble(), any(Entity.class));
        verify(control, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void healAliasesDefaultsAndSelfTargetAreObservable() {
        run("healpercent{m=0.25} @Target");
        run("percentheal{multiplier=0.25} @Target");
        run("hp{a=0.25} @Target");
        verify(target, times(3)).setHealth(15);
        run("healpercent @Target");
        verify(target).setHealth(12);
        run("healpercent{m=0.25} @Self");
        verify(caster).setHealth(15);
        run("heal-percent{p=25} @Target");
        verify(target, times(4)).setHealth(15);
        verify(control, never()).setHealth(anyDouble());
    }

    @Test
    void overhealMatchesStrictCapAndClampsNegativeHealth() {
        when(target.getHealth()).thenReturn(19.0);
        run("hp{m=0.2;oh=true;mo=0.5} @Target");
        verify(target).setAbsorptionAmount(3);
        verify(target).setHealth(20);
        clearInvocations(target);
        run("hp{m=0.2;oh=true;mo=0.15} @Target");
        verify(target, never()).setAbsorptionAmount(anyDouble());
        verify(target).setHealth(20);
        clearInvocations(target);
        run("hp{m=0.2;oh=true} @Target");
        verify(target, never()).setAbsorptionAmount(anyDouble());
        run("hp{m=-2} @Target");
        verify(target).setHealth(0);
    }

    @Test
    void multipleTargetsResolveTheirOwnHealthPlaceholders() {
        when(control.getHealth()).thenReturn(5.0);
        when(target.getAbsorptionAmount()).thenReturn(0.1);
        when(control.getAbsorptionAmount()).thenReturn(0.2);
        run("hp{m=<target.absorption>} @Targets");
        verify(target).setHealth(12);
        verify(control).setHealth(9);
        verify(caster, never()).setHealth(anyDouble());
    }

    @Test
    void locationTargetsDoNotFallBackToCasterHealing() {
        var locationContext = context.withLocationTarget(origin);
        SkillExtras.actions().get("heal-fraction").create(List.of()).accept(locationContext);
        SkillExtras.actions().get("damage-fraction").create(List.of()).accept(locationContext);
        verify(caster, never()).setHealth(anyDouble());
        verify(caster, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void invalidDynamicStaticAndDeadTargetsDoNotMutateHealth() {
        tree.setVariable("fraction", 0.2);
        run("hp{m=<skill.var.fraction>} @Target");
        verify(target).setHealth(14);
        clearInvocations(target);
        for (String value : List.of("NaN", "Infinity", "<missing>")) {
            tree.setVariable("fraction", value);
            run("hp{m=<skill.var.fraction>} @Target");
            run("damagepercent{p=<skill.var.fraction>} @Target");
        }
        run("hp{m=1e308;oh=true;mo=1e308} @Target");
        when(target.isDead()).thenReturn(true);
        SkillExtras.actions().get("heal-fraction").create(List.of()).accept(context);
        verify(target, never()).setHealth(anyDouble());
        verify(target, never()).damage(anyDouble(), any(Entity.class));
        for (String line : List.of("hp{m=0.1;m=0.2}", "hp{m=0.1;multiplier=0.2}",
                "hp{m=0.1;;oh=true}", "damagepercent{p=NaN}", "hp @Target 0.2")) {
            assertNull(service.bindAction(line), line);
        }
    }
}
