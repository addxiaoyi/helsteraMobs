package dev.helstera.ai.skill;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.Targeters;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class DeathCasterTargeterTest {
    @Test
    void deathCallbackCanSelectItsOwnCasterButOrdinaryExecutionCannot() {
        var instance = org.mockito.Mockito.mock(dev.helstera.api.instance.ModelInstance.class);
        var entity = org.mockito.Mockito.mock(LivingEntity.class);
        org.mockito.Mockito.when(instance.isValid()).thenReturn(true);
        org.mockito.Mockito.when(instance.baseEntity()).thenReturn(java.util.Optional.of(entity));
        org.mockito.Mockito.when(entity.isDead()).thenReturn(true);
        var death = BehaviorContext.of(instance, null, 0, -1, 0, "on-death");
        var normal = BehaviorContext.of(instance, null, 0, -1, 0, "on-timer");
        assertEquals(List.of(entity),Targeters.select("@self",death,List.of(),List.of()));
        assertEquals(List.of(entity),Targeters.select("@caster",death,List.of(),List.of()));
        assertTrue(Targeters.select("@self",normal,List.of(),List.of()).isEmpty());
        org.mockito.Mockito.when(entity.isDead()).thenReturn(false);
        var despawn = BehaviorContext.of(instance, null, 0, -1, 0, "on-despawn");
        assertEquals(List.of(entity),Targeters.select("@self",despawn,List.of(),List.of()));
        assertTrue(Targeters.select("@self",normal,List.of(),List.of()).isEmpty());
        org.mockito.Mockito.when(instance.isValid()).thenReturn(false);
        assertTrue(Targeters.select("@self",death,List.of(),List.of()).isEmpty());
    }
}
