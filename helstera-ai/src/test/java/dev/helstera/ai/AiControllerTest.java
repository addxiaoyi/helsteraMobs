package dev.helstera.ai;

import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Bukkit;
import org.bukkit.entity.Mob;
import org.bukkit.entity.LivingEntity;
import com.destroystokyo.paper.entity.Pathfinder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiControllerTest {

    @Test
    void conditionalIntervalUsesNativeOneInNAttemptSemantics() {
        assertTrue(AiController.conditionalIntervalPasses(0, 0, 0.99));
        assertTrue(AiController.conditionalIntervalPasses(7, 1, 0.0));
        assertFalse(AiController.conditionalIntervalPasses(7, 1, 0.9));
        assertTrue(AiController.conditionalIntervalPasses(7, 20, 0.9));
        assertFalse(AiController.conditionalIntervalPasses(-1, 1, 0.0));
    }

    @Test
    void conditionalMustReachRequiresACompletePaperPath() {
        World world = mock(World.class);
        Location origin = mock(Location.class);
        Location destination = mock(Location.class);
        when(origin.getWorld()).thenReturn(world);
        when(destination.getWorld()).thenReturn(world);
        Mob mob = mock(Mob.class);
        Pathfinder pathfinder = mock(Pathfinder.class);
        LivingEntity target = mock(LivingEntity.class);
        Pathfinder.PathResult path = mock(Pathfinder.PathResult.class);
        AiController controller = controller(mob, origin, pathfinder);
        when(target.isValid()).thenReturn(true);
        when(target.isDead()).thenReturn(false);
        when(target.getLocation()).thenReturn(destination);
        when(pathfinder.findPath(target)).thenReturn(path);
        when(path.canReachFinalPoint()).thenReturn(true);

        assertTrue(controller.canReach(target));

        when(path.canReachFinalPoint()).thenReturn(false);
        assertFalse(controller.canReach(target));
    }

    @Test
    void conditionalMustReachFailsClosedForMissingPathsCrossWorldAndPathfinderErrors() {
        World originWorld = mock(World.class);
        World otherWorld = mock(World.class);
        Location origin = mock(Location.class);
        Location destination = mock(Location.class);
        when(origin.getWorld()).thenReturn(originWorld);
        when(destination.getWorld()).thenReturn(otherWorld);
        Mob mob = mock(Mob.class);
        Pathfinder pathfinder = mock(Pathfinder.class);
        LivingEntity target = mock(LivingEntity.class);
        AiController controller = controller(mob, origin, pathfinder);
        when(target.isValid()).thenReturn(true);
        when(target.isDead()).thenReturn(false);
        when(target.getLocation()).thenReturn(destination);

        assertFalse(controller.canReach(target));
        verify(pathfinder, never()).findPath(target);

        when(destination.getWorld()).thenReturn(originWorld);
        when(pathfinder.findPath(target)).thenReturn(null);
        assertFalse(controller.canReach(target));

        when(pathfinder.findPath(target)).thenThrow(new IllegalStateException("path query failed"));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(java.util.logging.Logger.getLogger("test"));
            assertFalse(controller.canReach(target));
        }
    }

    private static AiController controller(Mob mob, Location origin, Pathfinder pathfinder) {
        ModelInstanceImpl instance = mock(ModelInstanceImpl.class);
        AiProfile profile = new AiProfile("path-reach-probe");
        profile.canChase = false;
        profile.canAttack = false;
        profile.canPatrol = false;
        profile.goals.add("0 clear");
        profile.targets.add("0 clear");
        when(instance.entity()).thenReturn(mob);
        when(instance.location()).thenReturn(origin);
        when(mob.getLocation()).thenReturn(origin);
        when(mob.getPathfinder()).thenReturn(pathfinder);
        return new AiController(instance, profile, null, mock(HelsteraEventBus.class));
    }

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
