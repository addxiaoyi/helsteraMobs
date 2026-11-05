package dev.helstera.ai;

import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiControllerTest {

    @Test
    void idleClearProfileStopsPaperPathfinder() {
        ModelInstanceImpl instance = mock(ModelInstanceImpl.class);
        Mob mob = mock(Mob.class);
        Pathfinder pathfinder = mock(Pathfinder.class);
        Location location = mock(Location.class);
        AiProfile profile = new AiProfile("clear-probe");
        profile.canChase = false;
        profile.canAttack = false;
        profile.canPatrol = false;
        profile.goals.add("0 clear");
        profile.targets.add("0 clear");

        when(instance.entity()).thenReturn(mob);
        when(instance.location()).thenReturn(location);
        when(instance.isValid()).thenReturn(true);
        when(mob.isValid()).thenReturn(true);
        when(mob.getLocation()).thenReturn(location);
        when(mob.getPathfinder()).thenReturn(pathfinder);

        AiController controller = new AiController(instance, profile, null,
                mock(HelsteraEventBus.class));
        controller.tick();

        verify(pathfinder, atLeastOnce()).stopPathfinding();
        assertTrue(controller.nativePathStopCalls() >= 1);
    }

    @Test
    void coreMeleeSelectorEnablesChaseWithoutLegacyCanChaseFlag() {
        ModelInstanceImpl instance = mock(ModelInstanceImpl.class);
        Mob mob = mock(Mob.class);
        Pathfinder pathfinder = mock(Pathfinder.class);
        Location location = mock(Location.class);
        AiProfile profile = new AiProfile("core-selector");
        profile.canChase = false;
        profile.canAttack = true;
        profile.canPatrol = false;
        profile.goals.add("0 meleeattack");
        profile.targets.add("0 clear");

        when(instance.entity()).thenReturn(mob);
        when(instance.location()).thenReturn(location);
        when(instance.isValid()).thenReturn(true);
        when(mob.isValid()).thenReturn(true);
        when(mob.getLocation()).thenReturn(location);
        when(mob.getPathfinder()).thenReturn(pathfinder);

        AiController controller = new AiController(instance, profile, null,
                mock(HelsteraEventBus.class));
        controller.tick();

        assertTrue(controller.selectorDiagnostic().contains("can-chase=true"));
    }

    @Test
    void combatStateRequiresALiveAssignedTarget() {
        ModelInstanceImpl instance = mock(ModelInstanceImpl.class);
        Mob mob = mock(Mob.class);
        Pathfinder pathfinder = mock(Pathfinder.class);
        Location location = mock(Location.class);
        AiProfile profile = new AiProfile("combat-state");
        profile.canChase = false;
        profile.canAttack = false;
        profile.canPatrol = false;
        profile.goals.add("0 clear");
        profile.targets.add("0 clear");

        when(instance.entity()).thenReturn(mob);
        when(instance.location()).thenReturn(location);
        when(instance.isValid()).thenReturn(true);
        when(mob.isValid()).thenReturn(true);
        when(mob.getLocation()).thenReturn(location);
        when(mob.getPathfinder()).thenReturn(pathfinder);

        AiController controller = new AiController(instance, profile, null,
                mock(HelsteraEventBus.class));
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(target.isValid()).thenReturn(true);
        when(target.isDead()).thenReturn(false);

        assertFalse(controller.isInCombat());
        controller.setCombatTarget(target);
        assertTrue(controller.isInCombat());
        controller.clearCombatTarget();
        assertFalse(controller.isInCombat());

        controller.setCombatTarget(target);
        when(target.isDead()).thenReturn(true);
        assertFalse(controller.isInCombat());
    }
}
