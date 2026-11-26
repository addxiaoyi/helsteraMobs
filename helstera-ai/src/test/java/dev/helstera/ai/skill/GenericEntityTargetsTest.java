package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.Targeters;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenericEntityTargetsTest {
    private final World world = mock(World.class);
    private final Location origin = new Location(world, 0, 64, 0);

    private <T extends Entity> T entity(Class<T> type, int x) {
        T entity = mock(type);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.isValid()).thenReturn(true);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(ignored -> new Location(world, x, 64, 0));
        when(entity.getVelocity()).thenAnswer(ignored -> new Vector());
        return entity;
    }

    private BehaviorContext context(LivingEntity caster, Entity... targets) {
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(17);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(origin);
        return BehaviorContext.continueExecution(instance, (LivingEntity) null, 1, -1, 7, "event", "payload",
                caster, List.of(), origin, null, mock(Cancellable.class)).withEntityTargets(List.of(targets));
    }

    @Test
    void nestedSkillRecreatesTreeAndCarriesAllItemsThroughDelayedInheritedSelection() {
        LivingEntity caster = entity(LivingEntity.class, 0);
        Item first = entity(Item.class, 1), second = entity(Item.class, 2);
        var context = context(caster, first, second).withExecutionAudience(List.of());
        context.multiplySkillPower(3);
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        var observations = new ArrayList<BehaviorContext>();
        registry.registerAction("capture-items", observations::add);
        var definitions = new YamlConfiguration();
        definitions.set("skills.outer.on-decision", List.of("skill{s=inner} @targets"));
        definitions.set("skills.inner.on-decision", List.of("capture-items{delay=2} @targets"));
        service.loadSkills(definitions.getConfigurationSection("skills"));
        var scheduled = new ArrayDeque<Runnable>();
        SkillCatalog.taskScheduler((task, ticks) -> { scheduled.add(task); return true; });
        try {
            assertTrue(service.runAction(service.bindAction("skill{s=outer}"), context));
            assertEquals(1, scheduled.size());
            assertTrue(observations.isEmpty());
            scheduled.remove().run();
            assertEquals(1, observations.size());
            var observed = observations.getFirst();
            assertEquals(List.of(first, second), observed.entityTargets());
            assertEquals(List.of(first, second), observed.skillTree().orElseThrow().entityTargets());
            assertTrue(observed.targets().isEmpty());
            assertEquals(first.getUniqueId().toString(), Placeholders.resolve("<target.uuid>", observed));
            assertEquals(3, observed.skillPower());
            assertEquals(context.trigger(), observed.trigger());
            assertEquals(context.origin(), observed.origin());
            assertEquals(context.executionAudience(), observed.executionAudience());
            assertEquals(context.cancellableEvent(), observed.cancellableEvent());
        } finally {
            SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void inheritedSettersVisitEachNonlivingRecipientAndLocationsRetainEveryPosition() {
        LivingEntity caster = entity(LivingEntity.class, 0);
        Interaction first = entity(Interaction.class, 2), second = entity(Interaction.class, 4);
        var context = context(caster, first, second);
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        assertTrue(service.runAction(service.bindAction("interactionSize{h=<target.l.x>;w=3} @targets"), context));
        verify(first).setInteractionHeight(2);
        verify(second).setInteractionHeight(4);
        verify(first).setInteractionWidth(3);
        verify(second).setInteractionWidth(3);
        assertEquals(List.of(first.getLocation(), second.getLocation()), Targeters.resolveLocations("@LOT", context));
        assertTrue(Targeters.resolveLocations("@LOT", context.withLocationTarget(origin)).isEmpty());
    }

    @Test
    void itemActionsUseAllRecipientsAndLivingOnlyActionsDoNotMutateCaster() {
        LivingEntity caster = entity(LivingEntity.class, 0);
        Item first = entity(Item.class, 1), second = entity(Item.class, 2);
        var context = context(caster, first, second);
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        assertTrue(service.runAction(service.bindAction("velocity{x=1;y=2;z=3} @targets"), context));
        verify(first).setVelocity(new Vector(1, 2, 3));
        verify(second).setVelocity(new Vector(1, 2, 3));
        assertTrue(service.runAction(service.bindAction("addtag{t=selected} @targets"), context));
        verify(first).addScoreboardTag("selected");
        verify(second).addScoreboardTag("selected");
        assertTrue(service.runAction(service.bindAction("setname{n=<target.uuid>} @targets"), context));
        String firstName = first.getUniqueId().toString(), secondName = second.getUniqueId().toString();
        verify(first).setCustomName(firstName);
        verify(second).setCustomName(secondName);
        assertTrue(service.runAction(service.bindAction("teleport-targets @targets"), context));
        verify(first).teleport(origin);
        verify(second).teleport(origin);
        SkillExtras.actions().get("set-absorption").create(List.of("0")).accept(context);
        verify(caster, never()).setAbsorptionAmount(anyDouble());
        verify(caster, never()).setVelocity(any());
        verify(caster, never()).addScoreboardTag(anyString());
        var casterCondition = BehaviorContext.continueExecution(context.instance(), caster, 1, 0,
                7, "event", "payload", caster, context.entityTargets(), origin, null)
                .withConditionScope(BehaviorContext.ConditionScope.CASTER);
        SkillExtras.actions().get("set-absorption").create(List.of("0")).accept(casterCondition);
        verify(caster).setAbsorptionAmount(0);
    }

    @Test
    void livingSelectorCanSearchWorldFromAnItemContext() {
        LivingEntity caster = entity(LivingEntity.class, 0), nearby = entity(LivingEntity.class, 3);
        Item item = entity(Item.class, 1);
        when(world.getNearbyEntities(origin, 32, 32, 32)).thenReturn(List.of(caster, nearby));
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        var received = new ArrayList<Entity>();
        registry.registerAction("capture-nearest", scoped -> received.addAll(scoped.entityTargets()));
        assertTrue(service.runAction(service.bindAction("capture-nearest @nearest"), context(caster, item)));
        assertEquals(List.of(nearby), received);
    }

    @Test
    void inheritedSelectionRejectsRemovedItemsAndDoesNotExpandAnEmptyResultToNearbyMobs() {
        LivingEntity caster = entity(LivingEntity.class, 0);
        Item removed = entity(Item.class, 1), live = entity(Item.class, 2);
        var context = context(caster, removed, live);
        when(removed.isValid()).thenReturn(false);
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        var received = new ArrayList<Entity>();
        registry.registerAction("capture-items", scoped -> received.addAll(scoped.entityTargets()));
        String action = service.bindAction("capture-items @targets");
        assertTrue(service.runAction(action, context));
        assertEquals(List.of(live), received);
        received.clear();
        when(live.isDead()).thenReturn(true);
        assertTrue(service.runAction(action, context));
        assertTrue(received.isEmpty());
        verify(world, never()).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
    }
}
