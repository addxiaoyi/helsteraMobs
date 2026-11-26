package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.AimingInheritedTargeter;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.behavior.Targeters;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.Cancellable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AimingInheritedTargeterTest {
    private final World world = mock(World.class);
    private final Location origin = new Location(world, 0, 64, 0);
    private final Location near = new Location(world, 2.25, 64, 0), far = new Location(world, 8.75, 64, 0);

    private <T extends Entity> T entity(Class<T> type, Location location) {
        T entity = mock(type);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.isValid()).thenReturn(true);
        when(entity.getWorld()).thenReturn(location.getWorld());
        when(entity.getLocation()).thenAnswer(ignored -> location.clone());
        return entity;
    }

    private BehaviorContext context(Entity caster, Entity... entities) {
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(51);
        when(instance.location()).thenReturn(origin);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        return BehaviorContext.continueExecution(instance, (LivingEntity) null, 1, -1, 4, "event", "payload",
                caster, List.of(entities), origin, null, mock(Cancellable.class));
    }

    private List<Entity> select(String spec, BehaviorContext context) {
        return Targeters.selectEntities(spec, context, List.of(), List.of(), UnaryOperator.identity());
    }

    @ParameterizedTest @ValueSource(strings = {"@targetedTarget", "@targeted"})
    void entitySelectionRetainsGenericRecipientsAndDoesNotSubstituteCombatOrNearbyTargets(String selector) {
        Creature caster = entity(Creature.class, origin);
        Item item = entity(Item.class, near);
        Interaction display = entity(Interaction.class, far);
        LivingEntity combat = entity(LivingEntity.class, far);
        when(caster.getTarget()).thenReturn(combat);
        var context = context(caster, caster, item, display, item);
        assertEquals(List.of(item, display), select(selector, context));
        assertTrue(select(selector, context.withEntityTargets(List.of())).isEmpty());
        when(item.isValid()).thenReturn(false);
        assertEquals(List.of(item, display), select(selector, context));
        verify(caster, never()).getTarget();
        verify(world, never()).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void inheritedEntityFiltersAndLimitsUseWholeMixedCollection() {
        Creature caster = entity(Creature.class, origin);
        Item item = entity(Item.class, near);
        Cow cow = entity(Cow.class, far);
        ArmorStand stand = entity(ArmorStand.class, near.clone().add(0, 0, 1));
        Player player = entity(Player.class, near.clone().add(0, 0, 2));
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(cow.getHealth()).thenReturn(10.0);
        var ctx = context(caster, item, cow, stand, player);
        assertEquals(List.of(item, cow, player), select("@targeted", ctx));
        assertEquals(List.of(item), select("@targeted{sort=NEAREST;limit=1}", ctx));
        assertEquals(List.of(cow), select("@targeted{sort=HIGHEST_HEALTH;limit=1}", ctx));
        assertEquals(List.of(item), select("@targeted{target=players;ignore=creative}", ctx));
        assertEquals(List.of(item, stand, player), select("@targeted{ignore=animals;targetarmorstands=true}", ctx));
        assertTrue(select("@targeted{target=water}", ctx).isEmpty());
        assertEquals(List.of(item, cow, player), select("@targeted{ignoretype=COW;limit=0}", ctx));
        assertEquals(List.of(item), select("@targeted{sort=NEAREST;sortby=FURTHEST;limit=\"2\"}", ctx));
    }

    @ParameterizedTest @ValueSource(strings = {"@targetedLocation", "@targetedLocations", "@targetedLoc"})
    void locationsAreIndependentOfEntitiesAndDeduplicateAfterMutation(String selector) {
        var ctx = context(entity(Creature.class, origin), entity(Item.class, far));
        assertTrue(Targeters.resolveLocations(selector, ctx).isEmpty());
        ctx = ctx.withLocationTargets(List.of(near, far, near.clone()));
        assertEquals(List.of(near, far), Targeters.resolveLocations(selector, ctx));
        assertEquals(List.of(new Location(world, 5, 64, 0)),
                Targeters.resolveLocations(selector + "{coordinatex=5}", ctx));
        assertEquals(List.of(far.clone().add(0, 2, 0)),
                Targeters.resolveLocations(selector + "{y=2;limit=1;sort=FURTHEST}", ctx));
        assertTrue(Targeters.resolveLocations(selector, ctx.withLocationTargets(List.of())).isEmpty());
    }

    @Test
    void locationOptionsRetainRawAliasPriorityAndNativeNumericFallbacks() {
        var ctx = context(entity(Creature.class, origin)).withLocationTargets(List.of(near));
        assertEquals(List.of(near.clone().add(3, 0, 0)),
                Targeters.resolveLocations("@targetedLoc{x=2;xoffset=3}", ctx));
        assertEquals(List.of(near.clone().add(1, 0, 0)),
                Targeters.resolveLocations("@targetedLoc{xoffset=\"2\";x=5}", ctx));
        assertEquals(List.of(near.clone().add(2, 0, 0)),
                Targeters.resolveLocations("@targetedLoc{x=2to2;cy=0;rotatey=90}", ctx));
        assertEquals(List.of(near), Targeters.resolveLocations("@targetedLoc{limit=bad;sort=bad}", ctx));
        assertTrue(Targeters.resolveLocations("@targetedLoc{x=NaN}", ctx).isEmpty());
        assertTrue(Targeters.resolveLocations("@targetedLoc{x=Infinity}", ctx).isEmpty());
    }

    @Test
    void locationCollectionsAreDefensiveAndSurviveCopiesAndTreeRecreation() {
        var ctx = context(entity(Creature.class, origin));
        ctx.multiplySkillPower(3);
        var supplied = new ArrayList<>(List.of(near.clone(), far.clone()));
        var selected = ctx.withLocationTargets(supplied).withExecutionAudience(List.of());
        supplied.getFirst().setX(99); supplied.clear();
        var copy = selected.withSkillTree(new SkillTreeContext(null, List.of())).copyForSkillMechanic()
                .withConditionScope(BehaviorContext.ConditionScope.TARGET).withoutCancellableEvent();
        copy.locationTargets().getFirst().setY(-9);
        assertEquals(List.of(near, far), copy.locationTargets());
        assertEquals(3, copy.skillPower());
        assertEquals("payload", copy.payload().orElseThrow());
        assertEquals(ctx.trigger(), copy.trigger());
        assertTrue(copy.executionAudience().orElseThrow().isEmpty());
        assertTrue(copy.entityTargets().isEmpty());
        assertEquals(List.of(near, far), copy.withLocationRecipient(far).locationTargets());
        assertEquals(far, copy.withLocationRecipient(far).targetLocation().orElseThrow());
        assertTrue(copy.withEntityTargets(List.of()).locationTargets().isEmpty());
    }

    @Test
    void nestedLocationSkillRunsOnceAndRetainsPluralSelectionThroughDelay() {
        var ctx = context(entity(Creature.class, origin)).withLocationTargets(List.of(near, far));
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        var received = new ArrayList<BehaviorContext>();
        registry.registerAction("capture-location", received::add);
        var definitions = new YamlConfiguration();
        definitions.set("skills.outer.on-decision", List.of("skill{s=inner} @targetedLocations"));
        definitions.set("skills.inner.on-decision", List.of("capture-location{delay=2} @targetedLoc{y=1}"));
        service.loadSkills(definitions.getConfigurationSection("skills"));
        var scheduled = new ArrayDeque<Runnable>();
        SkillCatalog.taskScheduler((task, ticks) -> { scheduled.add(task); return true; });
        try {
            assertTrue(service.runAction(service.bindAction("skill{s=outer}"), ctx));
            assertEquals(1, scheduled.size(), "metaskill must receive the set once");
            assertTrue(received.isEmpty());
            scheduled.remove().run();
            assertEquals(List.of(near.clone().add(0, 1, 0), far.clone().add(0, 1, 0)),
                    received.stream().map(c -> c.targetLocation().orElseThrow()).toList());
            assertTrue(received.stream().allMatch(c -> c.locationTargets().size() == 2));
        } finally { SkillCatalog.taskScheduler(null); }
    }

    @Test
    void genericSetterVisitsAllInheritedEntitiesAndGuardFiltersBeforeLimit() {
        Interaction first = entity(Interaction.class, near), second = entity(Interaction.class, far);
        var context = context(entity(Creature.class, origin), first, second);
        when(second.getScoreboardTags()).thenReturn(Set.of("eligible"));
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        assertTrue(service.runAction(service.bindAction("interactionSize{h=2;w=3} @targeted"), context));
        verify(first).setInteractionHeight(2); verify(second).setInteractionHeight(2);
        assertTrue(service.runAction(service.bindAction(
                "interactionSize{h=4;w=5} @targeted{conditions=[ - hastag{t=eligible} true ];limit=1;sort=NEAREST}"), context));
        verify(first, never()).setInteractionHeight(4); verify(second).setInteractionHeight(4);
    }

    @ParameterizedTest @ValueSource(strings = {"target-conditions", "TargetConditions"})
    void metaskillConditionsFilterEachLocationAndRetainTheSelectionInCasterAndTriggerScopes(String field) {
        var caster = entity(Creature.class, origin);
        var ctx = context(caster).withLocationTargets(List.of(near, far)).withExecutionAudience(List.of());
        ctx.multiplySkillPower(3);
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        var inspected = new ArrayList<BehaviorContext>();
        var received = new ArrayList<BehaviorContext>();
        registry.registerCondition("far-location", c -> c.targetLocation().orElseThrow().getX() > 5);
        registry.registerCondition("observe-selection", c -> { inspected.add(c); return true; });
        registry.registerAction("capture-location", received::add);
        var definitions = new YamlConfiguration();
        definitions.set("skills.inner." + field, List.of("far-location", "observe-selection"));
        definitions.set("skills.inner.require", List.of("observe-selection"));
        definitions.set("skills.inner.TriggerConditions", List.of("observe-selection"));
        definitions.set("skills.inner.on-decision", List.of("capture-location @targetedLoc"));
        service.loadSkills(definitions.getConfigurationSection("skills"));
        assertTrue(service.runAction(service.bindAction("skill{s=inner} @targetedLoc"), ctx));
        assertEquals(List.of(far), received.stream().map(c -> c.targetLocation().orElseThrow()).toList());
        assertEquals(List.of(BehaviorContext.ConditionScope.TARGET, BehaviorContext.ConditionScope.TRIGGER,
                BehaviorContext.ConditionScope.CASTER), inspected.stream().map(BehaviorContext::conditionScope).toList());
        for (var condition : inspected) {
            assertEquals(List.of(far), condition.locationTargets());
            assertEquals(3, condition.skillPower());
            assertTrue(condition.executionAudience().orElseThrow().isEmpty());
            assertEquals(ctx.trigger(), condition.trigger());
        }
    }

    @Test
    void pinMetaskillsRetainTheirPerPointContractWithoutMultiplyingInheritedLocations() {
        var pins = new PinRegistry(name -> name.equals("world") ? world : null);
        var config = new YamlConfiguration();
        config.set("pins.route", List.of("world,2.25,64,0", "world,8.75,64,0"));
        assertTrue(pins.load(config).isEmpty());
        SkillExtras.pinRegistry(pins);
        try {
            var registry = new BehaviorRegistryImpl();
            var service = new SkillService(registry, null);
            var received = new ArrayList<BehaviorContext>();
            registry.registerAction("capture-location", received::add);
            var definitions = new YamlConfiguration();
            definitions.set("skills.inner.on-decision", List.of("capture-location @targetedLoc"));
            service.loadSkills(definitions.getConfigurationSection("skills"));
            assertTrue(service.runAction(service.bindAction("skill{s=inner} @Pin{name=route}"),
                    context(entity(Creature.class, origin))));
            assertEquals(List.of(near, far), received.stream().map(c -> c.targetLocation().orElseThrow()).toList());
            assertTrue(received.stream().allMatch(c -> c.locationTargets().size() == 1));
        } finally { SkillExtras.pinRegistry(null); }
    }

    @Test
    void targetBlockUsesBukkitBlockCoordinatesAndStaticAliasPriority() {
        Player player = entity(Player.class, origin);
        Block block = mock(Block.class);
        Location coordinate = new Location(world, 3, 65, 0);
        when(block.getLocation()).thenReturn(coordinate);
        when(player.getTargetBlock(anySet(), anyInt())).thenReturn(block);
        var ctx = context(player);
        assertEquals(List.of(coordinate), Targeters.resolveLocations("@targetBlock{it=false}", ctx));
        verify(player).getTargetBlock(Set.of(Material.AIR), 64);
        assertEquals(List.of(coordinate.clone().add(0, 2, 0)),
                Targeters.resolveLocations("@targetBlock{d=8;maxdistance=5;it=true;ignoreTransparent=false;y=2}", ctx));
        verify(player).getTargetBlock(Set.of(Material.AIR), 5);
        Targeters.resolveLocations("@targetBlock{maxdistance=bad;d=8;it=false}", ctx);
        verify(player, times(2)).getTargetBlock(Set.of(Material.AIR), 64);
        verify(world, never()).rayTraceBlocks(any(), any(), anyDouble(), any(), anyBoolean());
    }

    @Test
    void activeMobSavedSelectionTakesPrecedenceOverPlayerAimingIncludingAnEmptyThreatTable() {
        Player player = entity(Player.class, origin);
        Item saved = entity(Item.class, far);
        var ctx = context(player);
        AimingInheritedTargeter.CasterTargetProvider provider = source -> new AimingInheritedTargeter.CasterTarget(saved);
        AimingInheritedTargeter.casterTargetProvider(provider);
        try {
            assertEquals(List.of(far), Targeters.resolveLocations("@targetBlock", ctx));
            AimingInheritedTargeter.casterTargetProvider(source -> new AimingInheritedTargeter.CasterTarget(null));
            assertTrue(Targeters.resolveLocations("@targetBlock", ctx).isEmpty());
            verify(player, never()).getTargetBlock(anySet(), anyInt());
        } finally { AimingInheritedTargeter.casterTargetProvider(null); }
        assertTrue(Targeters.resolveLocations("@targetBlock", context(entity(Creature.class, origin))).isEmpty());
    }
}
