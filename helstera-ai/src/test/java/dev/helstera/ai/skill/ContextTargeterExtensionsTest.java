package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.behavior.Targeters;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextTargeterExtensionsTest {
    // Bukkit Location keeps a weak world reference; retain mock worlds for the whole test.
    private final List<World> worlds = new ArrayList<>();
    private final World world = world();
    private final Location origin = new Location(world, 100, 64, 0);

    private World world() {
        World value = mock(World.class);
        worlds.add(value);
        return value;
    }

    private <T extends Entity> T entity(Class<T> type, Location location) {
        T entity = mock(type);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.isValid()).thenReturn(true);
        when(entity.getWorld()).thenReturn(location.getWorld());
        when(entity.getLocation()).thenAnswer(ignored -> location.clone());
        if (entity instanceof LivingEntity living) {
            when(living.getMaxHealth()).thenReturn(20.0);
            when(living.getHealth()).thenReturn(20.0);
        }
        if (entity instanceof Player player) {
            when(player.isOnline()).thenReturn(true);
            when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        }
        return entity;
    }

    private BehaviorContext context(Entity caster, List<LivingEntity> targets) {
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(origin);
        SkillTreeContext tree = new SkillTreeContext(caster, targets, origin);
        return BehaviorContext.continueExecution(instance, targets.isEmpty() ? null : targets.getFirst(),
                1, 0, 4, "on-death", "payload", caster, targets, origin, tree, mock(Cancellable.class));
    }

    @Test
    void namedPlayerUsesNativeLookupAliasesDefaultAndCallerPlaceholderScope() {
        LivingEntity caster = entity(LivingEntity.class, origin);
        Player player = entity(Player.class, new Location(world(), 0, 64, 0));
        BehaviorContext context = context(caster, List.of());
        context.skillTree().orElseThrow().setVariable("PlayerName", "Chosen");
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        List<LivingEntity> recipients = new ArrayList<>();
        registry.registerAction("record-player", scoped -> recipients.addAll(scoped.targets()));
        try (var bukkit = mockStatic(Bukkit.class)) {
            for (String name : List.of("Chosen", "chosen", "CarsonJF")) {
                bukkit.when(() -> Bukkit.getPlayerExact(name)).thenReturn(player);
            }
            for (String selector : List.of("@PlayerByName{name=Chosen}", "@specificplayer{n=chosen}",
                    "@playerbyname", "@specificplayer{name=Chosen;n=missing}")) {
                assertEquals(List.of(player), Targeters.select(selector, context, List.of(), List.of()), selector);
            }
            assertTrue(skills.runAction(skills.bindAction("record-player @specificplayer{n=<skill.var.PlayerName>}"), context));
            assertEquals(List.of(player), recipients);
            assertEquals(List.of(), Targeters.select("@playerbyname{name=Cho}", context, List.of(player), List.of()));
            BehaviorContext playerCaster = context(player, List.of());
            assertEquals(List.of(), Targeters.select("@playerbyname{name=Chosen}", playerCaster, List.of(), List.of()));
            assertEquals(List.of(player), Targeters.select("@playerbyname{name=Chosen;targetSelf=true}", playerCaster, List.of(), List.of()));
            assertEquals(List.of(), Targeters.select("@playerbyname{name=missing}", context, List.of(player), List.of()));
            assertEquals(List.of(), Targeters.select("@playerbyname{name=Chosen;targetPlayers=false}", context, List.of(), List.of()));
            assertEquals(List.of(), Targeters.select("@playerbyname{name=Chosen;radius=2}", context, List.of(), List.of()));
            assertEquals(List.of(), Targeters.select("@playerbyname{name=Chosen;unknown=true}", context, List.of(), List.of()));
            assertEquals(List.of(), Targeters.select("@playerbyname{name=<skill.var.Missing>}", context, List.of(), List.of()));
            bukkit.verify(() -> Bukkit.getPlayer(anyString()), never());
        }
    }

    @Test
    void interactionHistoryBelongsToCasterAndRejectsMissingOrOfflineRecords() {
        Interaction caster = entity(Interaction.class, origin);
        Player attacker = entity(Player.class, new Location(world(), 1, 64, 0));
        Player interacting = entity(Player.class, new Location(world, 2, 64, 0));
        Interaction.PreviousInteraction attack = mock(Interaction.PreviousInteraction.class);
        Interaction.PreviousInteraction interact = mock(Interaction.PreviousInteraction.class);
        OfflinePlayer attackPlayer = mock(OfflinePlayer.class), interactPlayer = mock(OfflinePlayer.class);
        when(attack.getPlayer()).thenReturn(attackPlayer);
        when(attackPlayer.getPlayer()).thenReturn(attacker);
        when(interact.getPlayer()).thenReturn(interactPlayer);
        when(interactPlayer.getPlayer()).thenReturn(interacting);
        when(caster.getLastAttack()).thenReturn(attack);
        when(caster.getLastInteraction()).thenReturn(interact);
        BehaviorContext context = context(caster, List.of(interacting));
        for (String selector : List.of("@interactionLastAttacker", "@lastAttacker")) {
            assertEquals(List.of(attacker), Targeters.select(selector, context, List.of(interacting), List.of()));
        }
        for (String selector : List.of("@interactionLastInteract", "@lastInteract")) {
            assertEquals(List.of(interacting), Targeters.select(selector, context, List.of(attacker), List.of()));
        }
        assertEquals(List.of(), Targeters.select("@lastAttacker{targetPlayers=false}", context, List.of(), List.of()));
        assertEquals(List.of(), Targeters.select("@lastInteract{unknown=true}", context, List.of(), List.of()));
        when(attackPlayer.getPlayer()).thenReturn(null);
        assertEquals(List.of(), Targeters.select("@lastAttacker", context, List.of(interacting), List.of()));
        when(caster.getLastInteraction()).thenReturn(null);
        assertEquals(List.of(), Targeters.select("@lastInteract", context, List.of(interacting), List.of()));
        assertEquals(List.of(), Targeters.select("@lastInteract", context(interacting, List.of(interacting)), List.of(), List.of()));
    }

    @Test
    void inheritedLocationsSnapshotEveryTargetDeduplicateAndKeepTerminalEntities() {
        LivingEntity caster = entity(LivingEntity.class, origin);
        LivingEntity first = entity(LivingEntity.class, new Location(world, 2.25, 64, 1));
        LivingEntity second = entity(LivingEntity.class, new Location(world(), 4, 65, 2));
        when(second.isValid()).thenReturn(false);
        when(second.isDead()).thenReturn(true);
        BehaviorContext context = context(caster, List.of(first, second, first));
        for (String selector : List.of("@locationsOfTargets", "@locationOfTarget", "@LOT")) {
            assertTrue(Targeters.isLocationName(selector));
            assertTrue(Targeters.validLocationTargeterSpec(selector));
            List<Location> locations = Targeters.resolveLocations(selector, context);
            assertEquals(List.of(first.getLocation(), second.getLocation()), locations);
            locations.getFirst().setX(999);
            assertEquals(2.25, first.getLocation().getX());
        }
        assertEquals(List.of(), Targeters.resolveLocations("@LOT", context(caster, List.of())));
        var located = context.withLocationTarget(new Location(world, 7, 8, 9));
        assertEquals(List.of(), Targeters.resolveLocations("@LOT", located));
        assertTrue(located.entityTargetsPresent());
    }

    @Test
    void locationModifiersUseNativeOrderZeroCoordinatesAndPositiveLimitSorting() {
        LivingEntity caster = entity(LivingEntity.class, origin);
        LivingEntity near = entity(LivingEntity.class, new Location(world, 99.25, 64.75, 2.75, 0, 0));
        LivingEntity far = entity(LivingEntity.class, new Location(world, 10, 64, 0));
        BehaviorContext context = context(caster, List.of(far, near));
        assertEquals(List.of(new Location(world, 100.5, 66.75, 4.5, 90, 0)), Targeters.resolveLocations(
                "@LOT{x=1;y=2;length=2;cx=0;cyaw=90;centered=true;limit=1;sort=NEAREST}", context));
        assertEquals(List.of(far.getLocation(), near.getLocation()), Targeters.resolveLocations("@LOT{sort=NEAREST;limit=0}", context));
        assertEquals(List.of(far.getLocation()), Targeters.resolveLocations("@LOT{sortby=FURTHEST;limit=1}", context));
        assertEquals(List.of(near.getLocation().subtract(0, 0, 2)), Targeters.resolveLocations(
                "@LOT{fo=2;limit=1;sort=NEAREST}", context));
        assertEquals(List.of(), Targeters.resolveLocations("@LOT{x=Infinity}", context));
        assertFalse(Targeters.validLocationTargeterSpec("@LOT{x=1;xo=2}"));
        assertFalse(Targeters.validLocationTargeterSpec("@LOT{unknown=true}"));
        assertEquals(List.of(origin), Targeters.resolveLocations("@origin", context));
    }

    @Test
    void boundLotDispatchesEveryLocationButNestedLotRetainsEmptyEntityCollection() {
        LivingEntity caster = entity(LivingEntity.class, origin);
        LivingEntity first = entity(LivingEntity.class, new Location(world, 1, 64, 0));
        LivingEntity second = entity(LivingEntity.class, new Location(world, 2, 64, 0));
        BehaviorContext context = context(caster, List.of(first, second));
        context.skillTree().orElseThrow().setVariable("YOffset", 3);
        context.multiplySkillPower(2);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        List<Location> locations = new ArrayList<>();
        registry.registerAction("record-location", scoped -> {
            locations.add(scoped.targetLocation().orElseThrow());
            assertTrue(scoped.targets().isEmpty());
            assertTrue(scoped.targetEntity().isEmpty());
            assertEquals(origin, scoped.origin().orElseThrow());
            assertEquals(context.trigger(), scoped.trigger());
            assertEquals(context.cancellableEvent(), scoped.cancellableEvent());
            assertSame(context.skillTree().orElseThrow(), scoped.skillTree().orElseThrow());
            assertEquals(2, scoped.skillPower());
        });
        var definitions = new org.bukkit.configuration.file.YamlConfiguration();
        definitions.set("skills.nested.on-decision", List.of("record-location @LOT"));
        skills.loadSkills(definitions.getConfigurationSection("skills"));
        String direct = skills.bindAction("record-location @LOT{y=<skill.var.YOffset>}");
        assertTrue(skills.runAction(direct, context));
        assertEquals(List.of(new Location(world, 1, 67, 0), new Location(world, 2, 67, 0)), locations);
        locations.clear();
        String binding = skills.bindAction("skill{s=nested} @LOT{y=<skill.var.YOffset>}");
        assertNotNull(binding);
        assertTrue(skills.runAction(binding, context));
        assertEquals(List.of(), locations);
        assertEquals(List.of(first, second), context.targets());
    }

    @Test
    void absentEntityCollectionCanForwardLocationAcrossTreeRecreationAndMetadataCopies() {
        LivingEntity caster = entity(LivingEntity.class, origin);
        var base = context(caster, List.of());
        var location = new Location(world, 7, 8, 9);
        BehaviorContext absent = BehaviorContext.continueExecution(base.instance(), (LivingEntity) null,
                1, 0, 0, "test", null, caster, null, origin, null).withLocationTarget(location);
        absent.multiplySkillPower(3);
        assertFalse(absent.entityTargetsPresent());
        var copied = absent.copyForSkillMechanic().withoutCancellableEvent()
                .withConditionScope(BehaviorContext.ConditionScope.TARGET).withNarrowedExecutionAudience(List.of());
        assertFalse(copied.entityTargetsPresent());
        assertEquals(List.of(location), Targeters.resolveLocations("@LOT", copied));
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        List<Location> results = new ArrayList<>();
        registry.registerAction("record-inherited", ctx -> {
            assertFalse(ctx.entityTargetsPresent());
            assertEquals(3, ctx.skillPower());
            results.add(ctx.targetLocation().orElseThrow());
        });
        var definitions = new org.bukkit.configuration.file.YamlConfiguration();
        definitions.set("skills.forward.on-decision", List.of("record-inherited @LOT"));
        service.loadSkills(definitions.getConfigurationSection("skills"));
        assertTrue(service.runAction(service.bindAction("skill{s=forward}"), copied));
        assertEquals(List.of(location), results);
        assertTrue(copied.withTargetEntity(caster).entityTargetsPresent());
        assertTrue(Targeters.resolveLocations("@LOT", copied.withTargetEntity(caster)
                .withLocationTarget(location)).isEmpty());
    }
}
