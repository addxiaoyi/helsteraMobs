package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Raider;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntitySetterActionsTest {
    private static <T extends Entity> T entity(Class<T> type) {
        T entity = mock(type);
        when(entity.isValid()).thenReturn(true);
        when(entity.getLocation()).thenReturn(new Location(mock(World.class), 4, 8, 12));
        return entity;
    }

    private static BehaviorContext context(Entity caster, Entity trigger) {
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.executionAudience()).thenReturn(Optional.empty());
        Location casterLocation = caster.getLocation();
        when(instance.location()).thenReturn(casterLocation);
        return BehaviorContext.execution(instance, null, 1, -1, 0, "test", null, trigger, List.of(), caster.getLocation());
    }

    private static void action(String name, List<String> args, BehaviorContext context) {
        EntitySetterActions.wrap(Map.of(), ignored -> null).get(name).create(args).accept(context);
    }

    @Test void dimensionsResolveAgainstNonlivingRecipientAndRejectPartialInvalidWrite() {
        Interaction caster = entity(Interaction.class);
        Interaction target = entity(Interaction.class);
        var context = context(caster, target).withEntityRecipient(target);
        action("set-interaction-size", List.of("h=<target.l.y>", "w=0"), context);
        verify(target).setInteractionHeight(8.0f);
        verify(target).setInteractionWidth(0);
        verify(caster, never()).setInteractionHeight(anyFloat());
        clearInvocations(target);
        action("set-interaction-size", List.of("h=2", "w=<target.var.missing>"), context);
        verify(target, never()).setInteractionHeight(anyFloat());
        verify(target, never()).setInteractionWidth(anyFloat());
    }

    @Test void noTargetInteractionUsesCasterButLocationSelectionDoesNot() {
        Interaction caster = entity(Interaction.class);
        var context = context(caster, null);
        action("set-interaction-size", List.of(), context);
        verify(caster).setInteractionHeight(1);
        clearInvocations(caster);
        action("set-interaction-size", List.of(), context.withLocationTarget(caster.getLocation()));
        verify(caster, never()).setInteractionHeight(anyFloat());
    }

    @Test void leashMutatesCasterUsingSelectedHolderAndCanRemoveIt() {
        LivingEntity caster = entity(LivingEntity.class);
        LivingEntity holder = entity(LivingEntity.class);
        var context = context(caster, null).withEntityRecipient(holder);
        action("set-leash-holder", List.of(), context);
        verify(caster).setLeashHolder(holder);
        verify(holder, never()).setLeashHolder(any());
        action("set-leash-holder", List.of("r=true"), context);
        verify(caster).setLeashHolder(null);
    }

    @Test void miniMessageAndTargetPlaceholderRemainIntact() {
        TextDisplay display = entity(TextDisplay.class);
        action("set-text-display", List.of("t=<red>Y=<target.l.y></red>"), context(display, null).withEntityRecipient(display));
        verify(display).text(Component.text("Y=8.0", NamedTextColor.RED));
        action("set-text-display", List.of(), context(display, null).withEntityRecipient(display));
        verify(display).text((Component) null);
    }

    @Test void customDisplayItemsAreResolvedAndCloned() {
        ItemDisplay display = entity(ItemDisplay.class);
        ItemStack original = mock(ItemStack.class);
        ItemStack cloned = mock(ItemStack.class);
        when(original.clone()).thenReturn(cloned);
        var actions = EntitySetterActions.wrap(Map.of(), name -> name.equals("custom_sword") ? original : null);
        actions.get("set-display-entity-item").create(List.of("i=custom_sword"))
                .accept(context(display, null).withEntityRecipient(display));
        verify(display).setItemStack(cloned);
    }

    @Test void forceLoadedFalseStillLoadsTheSelectedChunkFirst() {
        Entity caster = entity(Entity.class);
        Location location = mock(Location.class);
        World world = mock(World.class);
        Chunk chunk = mock(Chunk.class);
        when(location.getWorld()).thenReturn(world);
        when(location.clone()).thenReturn(location);
        when(location.getChunk()).thenReturn(chunk);
        action("set-chunk-force-loaded", List.of("l=false"), context(caster, null).withLocationTarget(location));
        var order = inOrder(chunk);
        order.verify(chunk).load();
        order.verify(chunk).setForceLoaded(false);
    }

    @Test void raiderFlagsAffectSelectedRaiderOnly() {
        Raider caster = entity(Raider.class);
        Raider target = entity(Raider.class);
        var context = context(caster, null).withEntityRecipient(target);
        action("set-raider-can-join-raid", List.of("c=false"), context);
        action("set-raider-patrol-leader", List.of(), context);
        verify(target).setCanJoinRaid(false);
        verify(target).setPatrolLeader(true);
        verify(caster, never()).setPatrolLeader(anyBoolean());
    }

    @Test void genericRecipientReplacesInheritedLivingTargetsAndSurvivesCopies() {
        LivingEntity caster = entity(LivingEntity.class);
        LivingEntity previous = entity(LivingEntity.class);
        Interaction selected = entity(Interaction.class);
        var inherited = context(caster, selected).withTargetEntity(previous);
        inherited.multiplySkillPower(3);
        var scoped = inherited.withEntityRecipient(selected).withNarrowedExecutionAudience(List.of())
                .copyForSkillMechanic().withConditionScope(BehaviorContext.ConditionScope.TARGET)
                .withoutCancellableEvent();
        assertSame(selected, scoped.entityRecipient().orElseThrow());
        assertTrue(scoped.targetEntity().isEmpty());
        assertTrue(scoped.targets().isEmpty());
        assertEquals(3.0f, scoped.skillPower());
        assertTrue(scoped.executionAudience().orElseThrow().isEmpty());
        action("set-interaction-size", List.of(), scoped);
        verify(selected).setInteractionHeight(1);
        assertTrue(scoped.withLocationTarget(selected.getLocation()).entityRecipient().isEmpty());
    }

    @Test void targetSelectorUsesMobCombatTargetInsteadOfInheritedRecipient() {
        org.bukkit.entity.Mob caster = entity(org.bukkit.entity.Mob.class);
        Raider combatTarget = entity(Raider.class);
        Raider inheritedTarget = entity(Raider.class);
        when(caster.getTarget()).thenReturn(combatTarget);
        var context = context(caster, inheritedTarget).withTargetEntity(inheritedTarget);
        SkillService service = new SkillService(new BehaviorRegistryImpl(), null);
        String key = service.bindAction("setRaiderLeader @target");
        assertNotNull(key);
        assertTrue(service.runAction(key, context));
        verify(combatTarget).setPatrolLeader(true);
        verify(inheritedTarget, never()).setPatrolLeader(anyBoolean());
        clearInvocations(combatTarget);
        when(caster.getTarget()).thenReturn(null);
        assertTrue(service.runAction(key, context));
        verify(combatTarget, never()).setPatrolLeader(anyBoolean());
        verify(inheritedTarget, never()).setPatrolLeader(anyBoolean());
    }

    @Test void explicitNonlivingTargetRecipientRemainsAddressable() {
        org.bukkit.entity.Mob caster = entity(org.bukkit.entity.Mob.class);
        Interaction selected = entity(Interaction.class);
        var context = context(caster, null).withEntityRecipient(selected);
        SkillService service = new SkillService(new BehaviorRegistryImpl(), null);
        String key = service.bindAction("interactionSize{h=2;w=3} @target");
        assertNotNull(key);
        assertTrue(service.runAction(key, context));
        verify(selected).setInteractionHeight(2);
        verify(selected).setInteractionWidth(3);
    }

    @Test void emptyTrackedPatrolLocationFallsBackToOriginButMissingTriggerDoesNot() {
        LivingEntity caster = entity(LivingEntity.class);
        Raider selected = entity(Raider.class);
        Location origin = caster.getLocation();
        org.bukkit.block.Block block = mock(org.bukkit.block.Block.class);
        when(origin.getWorld().getBlockAt(any(Location.class))).thenReturn(block);
        var context = context(caster, null).withEntityRecipient(selected);
        action("set-raider-patrol-block", List.of("l=@trackedlocation"), context);
        verify(selected).setPatrolTarget(block);
        clearInvocations(selected);
        action("set-raider-patrol-block", List.of("l=@triggerlocation"), context);
        verify(selected, never()).setPatrolTarget(any());
    }

    @Test void unknownOuterSelectorsRejectBindingIncludingChains() {
        SkillService service = new SkillService(new BehaviorRegistryImpl(), null);
        assertNull(service.bindAction("setRaiderLeader @imaginary"));
        assertNull(service.bindAction("setRaiderLeader @self > @imaginary"));
        assertNotNull(service.bindAction("setRaiderLeader @self"));
        assertNotNull(service.bindAction("setRaiderLeader @playersinradius{r=8}"));
        assertNotNull(service.bindAction("setchunkforceloaded @origin"));
    }

    @Test void directSkillBindingReachesNonlivingTrigger() {
        LivingEntity caster = entity(LivingEntity.class);
        Interaction target = entity(Interaction.class);
        SkillService service = new SkillService(new BehaviorRegistryImpl(), null);
        String key = service.bindAction("setInteractionSize{h=<target.l.y>;w=3} @trigger");
        assertNotNull(key);
        assertTrue(service.runAction(key, context(caster, target)));
        verify(target).setInteractionHeight(8.0f);
        verify(target).setInteractionWidth(3);
        assertNull(service.bindAction("setInteractionSize{h=2;h=3} @trigger"));
        assertNull(service.bindAction("setInteractionSize{w=2;width=3} @trigger"));
    }
}
