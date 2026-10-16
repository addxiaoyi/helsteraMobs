package dev.helstera.ai;

import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.entity.Mob;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
