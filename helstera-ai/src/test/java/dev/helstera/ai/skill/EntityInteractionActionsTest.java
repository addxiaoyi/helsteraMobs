package dev.helstera.ai.skill;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityInteractionActionsTest {
    private static final PaperEntityInteractions.Result OK = new PaperEntityInteractions.Result(true, 1, "ok");
    private record Rotated(Entity entity, float yaw, float pitch) { }
    private record Rammed(Entity caster, Entity target) { }

    private static final class Operations {
        final List<Entity> releases = new ArrayList<>();
        final List<Rotated> rotations = new ArrayList<>();
        final List<Rammed> rams = new ArrayList<>();
        final Function<Entity, PaperEntityInteractions.Result> release = entity -> { releases.add(entity); return OK; };
        final EntityInteractionActions.Rotation rotation = (entity, yaw, pitch) -> {
            rotations.add(new Rotated(entity, yaw, pitch)); return OK;
        };
        final BiFunction<Entity, Entity, PaperEntityInteractions.Result> ram = (caster, target) -> {
            rams.add(new Rammed(caster, target)); return OK;
        };
        void apply(String mechanic, List<String> arguments, BehaviorContext context) {
            EntityInteractionActions.wrap(Map.of(), release, rotation, ram).get(mechanic)
                    .create(arguments).accept(context);
        }
    }

    private static <T extends Entity> T entity(Class<T> type, World world, float yaw, float pitch) {
        T entity = mock(type);
        when(entity.isValid()).thenReturn(true);
        when(entity.getLocation()).thenReturn(new Location(world, 1, 2, 3, yaw, pitch));
        return entity;
    }

    private static BehaviorContext context(Entity caster, Entity trigger, List<LivingEntity> targets) {
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.executionAudience()).thenReturn(Optional.empty());
        Location casterLocation = caster.getLocation();
        when(instance.location()).thenReturn(casterLocation);
        return BehaviorContext.execution(instance, null, 1, -1, 0, "test", null, trigger, targets, caster.getLocation());
    }

    @Test void releaseDispatchesEachLivingRecipientIncludingPlayersAndSkipsNonliving() {
        var world = mock(World.class);
        var caster = entity(LivingEntity.class, world, 10, 20);
        var player = entity(Player.class, world, 0, 0);
        var target = entity(LivingEntity.class, world, 0, 0);
        var display = entity(TextDisplay.class, world, 0, 0);
        var operations = new Operations();
        var context = context(caster, null, List.of(player, target));
        operations.apply("stop-using-item", List.of(), context);
        assertEquals(List.of(player, target), operations.releases);
        operations.apply("stop-using-item", List.of(), context.withEntityRecipient(display));
        operations.apply("stop-using-item", List.of(), context.withLocationTarget(caster.getLocation()));
        assertEquals(2, operations.releases.size());
        verify(player, never()).clearActiveItem();
        verify(player, never()).completeUsingActiveItem();
    }

    @Test void matchRotationUsesSourceAnglesForNonlivingRecipientAndNeverRotatesPlayers() {
        var world = mock(World.class);
        var caster = entity(LivingEntity.class, world, 450, -120);
        var display = entity(TextDisplay.class, world, 5, 6);
        var player = entity(Player.class, world, 7, 8);
        var operations = new Operations();
        var context = context(caster, null, List.of()).withEntityRecipient(display);
        operations.apply("match-rotation", List.of("to=@selflocation"), context);
        assertEquals(List.of(new Rotated(display, 450, -120)), operations.rotations);
        operations.apply("match-rotation", List.of("to=@selflocation"), context.withEntityRecipient(player));
        operations.apply("match-rotation", List.of("to=@selflocation"), context.withLocationTarget(caster.getLocation()));
        assertEquals(1, operations.rotations.size());
        verify(display, never()).setRotation(anyFloat(), anyFloat());
        verify(display, never()).teleport(any(Location.class));
        verify(display, never()).setVelocity(any());
    }

    @Test void sourceIsResolvedOnceBeforeAnyRecipientCanMutateIt() {
        var world = mock(World.class);
        var caster = entity(LivingEntity.class, world, 10, 20);
        var first = entity(LivingEntity.class, world, 0, 0);
        var second = entity(LivingEntity.class, world, 0, 0);
        var operations = new Operations();
        EntityInteractionActions.Rotation rotation = (entity, yaw, pitch) -> {
            operations.rotations.add(new Rotated(entity, yaw, pitch));
            when(caster.getLocation()).thenReturn(new Location(world, 1, 2, 3, 99, 88));
            return OK;
        };
        EntityInteractionActions.wrap(Map.of(), operations.release, rotation, operations.ram)
                .get("match-rotation").create(List.of("to=@self"))
                .accept(context(caster, null, List.of(first, second)));
        assertEquals(List.of(new Rotated(first, 10, 20), new Rotated(second, 10, 20)), operations.rotations);
    }

    @Test void emptyTrackedSourceUsesOriginButMissingTriggersDoNot() {
        var world = mock(World.class);
        var caster = entity(LivingEntity.class, world, 450, 120);
        var target = entity(LivingEntity.class, world, 1, 2);
        var context = context(caster, null, List.of(target));
        var operations = new Operations();
        operations.apply("match-rotation", List.of("to=@trackedlocation"), context);
        operations.apply("match-rotation", List.of("to=@trigger"), context);
        operations.apply("match-rotation", List.of("to=@triggerlocation"), context);
        assertEquals(List.of(new Rotated(target, 450, 120)), operations.rotations);
    }

    @Test void nonlivingTriggerAndStaticLocationAreValidRotationSources() {
        var world = mock(World.class);
        var caster = entity(LivingEntity.class, world, 1, 2);
        var trigger = entity(TextDisplay.class, world, -450, 120);
        var target = entity(LivingEntity.class, world, 3, 4);
        var operations = new Operations();
        var context = context(caster, trigger, List.of(target));
        operations.apply("match-rotation", List.of("to=@triggerlocation"), context);
        operations.apply("match-rotation", List.of("to=@location{x=1;y=2;z=3;yaw=720;pitch=-200}"), context);
        assertEquals(List.of(new Rotated(target, -450, 120), new Rotated(target, 720, -200)), operations.rotations);
    }

    @Test void goatRamRequiresGoatCasterAndLivingTargetAndDoesNotInventDamage() {
        var world = mock(World.class);
        var goat = entity(Goat.class, world, 0, 0);
        var target = entity(LivingEntity.class, world, 0, 0);
        var display = entity(TextDisplay.class, world, 0, 0);
        var operations = new Operations();
        operations.apply("goat-ram", List.of(), context(goat, null, List.of(target)));
        operations.apply("goat-ram", List.of(), context(target, null, List.of(goat)));
        operations.apply("goat-ram", List.of(), context(goat, null, List.of()).withEntityRecipient(display));
        assertEquals(List.of(new Rammed(goat, target)), operations.rams);
        verify(target, never()).damage(anyDouble());
        verify(target, never()).damage(anyDouble(), any(Entity.class));
        verify(target, never()).setVelocity(any());
    }

    @Test void adapterFailuresAreCountedAndDoNotPreventLaterRecipients() {
        var world = mock(World.class);
        var caster = entity(LivingEntity.class, world, 1, 2);
        var first = entity(LivingEntity.class, world, 0, 0);
        var second = entity(LivingEntity.class, world, 0, 0);
        var operations = new Operations();
        long before = SkillFaults.countOf("EntityInteractionActions#stop-using-item");
        Function<Entity, PaperEntityInteractions.Result> release = entity -> {
            operations.releases.add(entity);
            return entity == first ? new PaperEntityInteractions.Result(false, 0, "unavailable") : OK;
        };
        EntityInteractionActions.wrap(Map.of(), release, operations.rotation, operations.ram)
                .get("stop-using-item").create(List.of()).accept(context(caster, null, List.of(first, second)));
        assertEquals(List.of(first, second), operations.releases);
        assertEquals(before + 1, SkillFaults.countOf("EntityInteractionActions#stop-using-item"));
    }
}
