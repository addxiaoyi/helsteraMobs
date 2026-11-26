package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShieldBreakBindingTest {
    private final World world = mock(World.class);
    private <T extends Entity> T entity(Class<T> type, int height) {
        T entity = mock(type);
        when(entity.isValid()).thenReturn(true);
        when(entity.getLocation()).thenReturn(new Location(world, 1, height, 3));
        return entity;
    }
    private BehaviorContext context(Entity caster, Entity recipient) {
        var instance = mock(ModelInstance.class);
        var location = caster.getLocation();
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(location);
        return BehaviorContext.execution(instance, null, 1, 0, 0, "test", null, recipient, List.of(), location);
    }

    @Test void aliasesUseRegisteredServiceAndDurationExplicitTargetIsTheCaster() {
        var caster = entity(LivingEntity.class, 75);
        var recipient = entity(Player.class, 12);
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        var values = new ArrayList<Integer>();
        try (var owners = mockConstruction(ShieldBreakService.class, (mock, construction) ->
                when(mock.disable(eq(recipient), any())).thenAnswer(call -> {
                    values.add(((IntSupplier) call.getArgument(1)).getAsInt()); return ShieldBreakService.Result.SUCCESS;
                }))) {
            ShieldBreakActions.host(mock(Plugin.class));
            try {
                for (String alias : List.of("shieldbreak", "disableshield", "disable-shield")) {
                    var key = service.bindAction(alias + "{d=5;duration=<target.l.y>} @trigger");
                    assertNotNull(key); assertTrue(service.runAction(key, context(caster, recipient)));
                }
                assertEquals(List.of(75,75,75), values);
                verify(owners.constructed().getFirst(), times(3)).disable(eq(recipient), any());
            } finally { ShieldBreakActions.host(null); }
        }
    }

    @Test void nonplayersAndLocationsCannotReceiveShieldMutation() {
        var caster = entity(Player.class, 75);
        var recipient = entity(LivingEntity.class, 12);
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        try (var owners = mockConstruction(ShieldBreakService.class)) {
            ShieldBreakActions.host(mock(Plugin.class));
            try {
                for (String source : List.of("shieldbreak @trigger", "disableshield @origin")) {
                    String key = service.bindAction(source); assertNotNull(key);
                    assertTrue(service.runAction(key, context(caster, recipient)));
                }
                verify(owners.constructed().getFirst(), never()).disable(any(), any());
                assertNull(service.bindAction("shieldbreak @unknown"));
                assertNull(service.bindAction("shieldbreak 20 @trigger"));
            } finally { ShieldBreakActions.host(null); }
        }
    }
}
