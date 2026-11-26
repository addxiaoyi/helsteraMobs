package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.runtime.instance.PaperEntityInteractions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Goat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityInteractionBindingTest {
    private final World world = mock(World.class);
    private final SkillService service = new SkillService(new BehaviorRegistryImpl(), null);
    private static final PaperEntityInteractions.Result OK = new PaperEntityInteractions.Result(true, 1, "ok");

    private <T extends Entity> T entity(Class<T> type) {
        T value = mock(type);
        when(value.isValid()).thenReturn(true);
        when(value.getLocation()).thenReturn(new Location(world, 1, 60, 3));
        return value;
    }

    private BehaviorContext context(Entity caster, Entity target) {
        var model = mock(ModelInstance.class);
        Location casterLocation = caster.getLocation();
        when(model.isValid()).thenReturn(true);
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        when(model.location()).thenReturn(casterLocation);
        return BehaviorContext.execution(model, null, 1, 0, 0, "test", null, target, List.of(),
                new Location(world, 5, 70, 8, 450, -120));
    }

    private void execute(String source, BehaviorContext context) {
        String key = service.bindAction(source);
        assertNotNull(key, source);
        assertTrue(service.runAction(key, context), source);
    }

    @Test void releaseAliasesReachTheRegisteredNativeOperation() {
        var caster = entity(LivingEntity.class);
        var recipient = entity(Player.class);
        try (var nativeCalls = mockStatic(PaperEntityInteractions.class)) {
            nativeCalls.when(() -> PaperEntityInteractions.releaseUsingItem(recipient)).thenReturn(OK);
            for (String alias : List.of("stopusingitem", "releaseitem", "stop-using-item"))
                execute(alias + " @trigger", context(caster, recipient));
            nativeCalls.verify(() -> PaperEntityInteractions.releaseUsingItem(recipient), times(3));
            nativeCalls.verifyNoMoreInteractions();
        }
    }

    @Test void rotationDispatchRetainsNonlivingRecipientAndNestedSourceArguments() {
        var caster = entity(LivingEntity.class);
        var recipient = entity(TextDisplay.class);
        try (var nativeCalls = mockStatic(PaperEntityInteractions.class)) {
            nativeCalls.when(() -> PaperEntityInteractions.matchRotation(recipient, 450, -120)).thenReturn(OK);
            execute("matchrotation{to=@origin} @trigger", context(caster, recipient));
            execute("matchrot{l=@location{x=0;y=2;z=3;yaw=450;pitch=-120}} @trigger", context(caster, recipient));
            execute("match-rotation{target=\"@origin\"} @trigger", context(caster, recipient));
            nativeCalls.verify(() -> PaperEntityInteractions.matchRotation(recipient, 450, -120), times(3));
            nativeCalls.verifyNoMoreInteractions();
        }
    }

    @Test void goatAliasesReachTheOperationWithDistinctCasterAndTarget() {
        var caster = entity(Goat.class);
        var recipient = entity(LivingEntity.class);
        try (var nativeCalls = mockStatic(PaperEntityInteractions.class)) {
            nativeCalls.when(() -> PaperEntityInteractions.ram(caster, recipient)).thenReturn(OK);
            execute("GoatRam @trigger", context(caster, recipient));
            execute("goat-ram @trigger", context(caster, recipient));
            nativeCalls.verify(() -> PaperEntityInteractions.ram(caster, recipient), times(2));
            nativeCalls.verifyNoMoreInteractions();
        }
    }

    @Test void locationTargetsDoNotBecomeEntityRecipientsAndInvalidSourcesFailBinding() {
        var caster = entity(Goat.class);
        var recipient = entity(Player.class);
        try (var nativeCalls = mockStatic(PaperEntityInteractions.class)) {
            for (String line : List.of("releaseitem @origin", "GoatRam @origin", "matchrot{to=@origin} @origin"))
                execute(line, context(caster, recipient));
            nativeCalls.verifyNoInteractions();
        }
        for (String source : List.of("matchrot", "matchrot{to=@unknown} @trigger",
                "matchrot{to=@origin;target=@trigger} @trigger", "GoatRam{damage=5} @trigger",
                "releaseitem @unknown", "matchrot{to=@origin} @self > @unknown"))
            assertNull(service.bindAction(source), source);
    }
}
