package dev.helstera.ai;

import dev.helstera.api.behavior.Factions;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SelectorBehaviorRepairTest {
    @Test void clearAndResetRemoveEveryEarlierGoalEffectAndPreserveLaterRules() {
        for (String reset : List.of("clear", "reset")) {
            var profile = new AiProfile("reset-all");
            profile.goals.addAll(List.of("0 float", "1 opendoors", "2 avoidsun", "3 lookatplayers", "4 avoidentity",
                    "5 tempt WHEAT", "6 breakdoors", "7 rangedattack", "8 leapattarget", "9 followowner", "10 " + reset));
            var policy = profile.selectorPolicy();
            assertFalse(policy.chase()); assertFalse(policy.attack()); assertFalse(policy.patrol()); assertFalse(policy.flee());
            assertFalse(policy.swim()); assertFalse(policy.openDoors()); assertFalse(policy.avoidSun());
            assertFalse(policy.canFloat()); assertFalse(policy.canBreakDoors()); assertFalse(policy.lookAtPlayers());
            assertFalse(policy.avoidEntities()); assertFalse(policy.rangedAttack()); assertFalse(policy.leapAtTarget());
            assertNull(policy.temptItem()); assertEquals(AiProfile.TargetMode.PLAYERS, policy.targetMode());
            assertTrue(SelectorPlan.compile(profile).goals().isEmpty());
            profile.goals.add("11 float"); profile.goals.add("12 lookatplayers");
            assertTrue(profile.selectorPolicy().canFloat()); assertTrue(profile.selectorPolicy().lookAtPlayers());
            assertFalse(profile.selectorPolicy().canBreakDoors());
        }
    }

    @Test void targetResetRemovesEarlierAttackerAndFactionPolicy() {
        for (String reset : List.of("clear", "reset")) {
            var profile = new AiProfile("reset-target");
            profile.targets.addAll(List.of("0 attacker", "1 hurtbytarget", "2 allied_to_faction:guards", "3 " + reset));
            var policy = profile.selectorPolicy();
            assertEquals(AiProfile.TargetMode.NONE, policy.targetMode());
            assertFalse(policy.attackerFirst()); assertFalse(policy.hurtByTargetFirst());
            assertNull(policy.targetFaction()); assertFalse(policy.factionAllied()); assertFalse(policy.factionHostile());
        }
    }

    @Test void unavailableClimbingAndDoorClosingProduceControllerDiagnostics() {
        var fixture = new Fixture();
        fixture.profile.goals.addAll(List.of("0 clear", "1 spider", "2 closedoor"));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getLogger).thenReturn(Logger.getAnonymousLogger());
            var controller = fixture.controller();
            assertTrue(controller.selectorDiagnostic().contains("native climbing has no public API"));
            assertTrue(controller.selectorDiagnostic().contains("door-closing toggle"));
        }
    }

    @Test void fleeSpeciesRestrictionsIgnoreNearerUnrelatedEntitiesAndHonorPriority() {
        for (var entry : List.of(new Species("fleeplayers", Player.class), new Species("flee_golems", IronGolem.class),
                new Species("runfromvillagers", Villager.class), new Species("flee_wolves", Wolf.class))) {
            var fixture = new Fixture();
            LivingEntity unrelated = fixture.entity(Cow.class, 1);
            LivingEntity selected = fixture.entity(entry.type(), 4);
            fixture.nearby.addAll(List.of(unrelated, selected));
            fixture.profile.goals.addAll(List.of("0 clear", "1 " + entry.token()));
            var controller = fixture.controller(); controller.tick();
            assertSame(selected, controller.targetEntity(), entry.token());
            assertEquals(AiController.State.FLEE, controller.state());
            verify(fixture.mob).setVelocity(argThat(value -> value.getX() < 0));
        }
        var fixture = new Fixture();
        var wolf = fixture.entity(Wolf.class, 1); var golem = fixture.entity(IronGolem.class, 5);
        fixture.nearby.addAll(List.of(wolf, golem));
        fixture.profile.goals.addAll(List.of("0 clear", "1 fleegolems", "2 fleewolf"));
        var controller = fixture.controller(); controller.tick();
        assertSame(golem, controller.targetEntity(), "first usable configured goal wins over closer lower-priority species");
    }

    @Test void factionFleeSelectsOnlyConfiguredFactionsEvenWhenUnrelatedEntityIsCloser() {
        var previous = Factions.table();
        var factions = new FactionService();
        try {
            Factions.install(factions);
            var fixture = new Fixture();
            var other = fixture.entity(Cow.class, 1); var matching = fixture.entity(Cow.class, 3);
            factions.bindInstance(other.getUniqueId(), "neutral");
            factions.bindInstance(matching.getUniqueId(), "Guards");
            fixture.nearby.addAll(List.of(other, matching));
            fixture.profile.goals.addAll(List.of("0 clear", "1 fleefaction{f=guards,raiders;d=8;s=1.5;ss=1}"));
            var controller = fixture.controller(); controller.tick();
            assertSame(matching, controller.targetEntity());
            verify(fixture.mob).setVelocity(argThat(value -> Math.abs(value.length() - fixture.profile.moveSpeed * 1.5) < 0.000001));
        } finally { Factions.install(previous); }
    }

    @Test void invalidOrOutOfRangeFleeRulesNeverBroadenTheirCandidates() {
        for (String selector : List.of("fleefaction", "fleefaction{f=guards;f=raiders}",
                "fleeplayers{unknown=true}", "fleeplayers{d=NaN}", "fleeplayers{d=1}", "fleegolems")) {
            var fixture = new Fixture();
            fixture.nearby.add(fixture.entity(Player.class, 2));
            fixture.profile.goals.addAll(List.of("0 clear", "1 " + selector));
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getLogger).thenReturn(Logger.getAnonymousLogger());
                var controller = fixture.controller(); controller.tick();
                assertEquals(AiController.State.IDLE, controller.state(), selector);
                assertNull(controller.targetEntity(), selector);
                verify(fixture.mob, never()).setVelocity(any());
            }
        }
    }

    @Test void creativeSpectatorAndLowHealthDoNotBypassSpecificFleeSelection() {
        var fixture = new Fixture();
        var player = fixture.entity(Player.class, 2);
        when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
        fixture.nearby.addAll(List.of(player, fixture.entity(Cow.class, 1)));
        when(fixture.mob.getHealth()).thenReturn(1.0);
        fixture.profile.goals.addAll(List.of("0 clear", "1 fleeplayers"));
        var controller = fixture.controller(); controller.tick();
        assertEquals(AiController.State.IDLE, controller.state());
        when(player.getGameMode()).thenReturn(GameMode.SPECTATOR); controller.tick();
        assertEquals(AiController.State.IDLE, controller.state());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL); controller.tick();
        assertEquals(AiController.State.FLEE, controller.state()); assertSame(player, controller.targetEntity());
    }

    @Test void randomLookChangesFacingWithoutScanningPlayersAndRespectsClear() {
        var fixture = new Fixture(); fixture.profile.goals.addAll(List.of("0 clear", "1 randomlookaround"));
        assertFalse(fixture.profile.selectorPolicy().lookAtPlayers());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getCurrentTick).thenReturn(100);
            var controller = fixture.controller();
            verify(fixture.mob).teleport(argThat((Location location) -> location.getX() == 0 && location.getY() == 70));
            verify(fixture.world, never()).getPlayers();
            controller.tick();
            verify(fixture.mob, times(1)).teleport(any(Location.class));
            fixture.profile.goals.add("2 clear"); bukkit.when(Bukkit::getCurrentTick).thenReturn(200); controller.tick();
            verify(fixture.mob, times(1)).teleport(any(Location.class));
            verify(fixture.world, never()).getPlayers();
        }
    }

    @Test void zombieDoorAttributeRestoresOnReloadCloseAndRepeatedOwnership() {
        var zombie = mock(Zombie.class);
        when(zombie.supportsBreakingDoors()).thenReturn(true);
        var current = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(zombie.canBreakDoors()).thenAnswer(invocation -> current.get());
        doAnswer(invocation -> { current.set(invocation.getArgument(0)); return null; }).when(zombie).setCanBreakDoors(anyBoolean());
        var fixture = new Fixture(zombie); fixture.profile.goals.add("0 breakdoors");
        var controller = fixture.controller(); assertTrue(current.get());
        controller.tick(); verify(zombie, times(1)).setCanBreakDoors(true);
        var cleared = new AiProfile("clear"); cleared.goals.add("0 reset"); cleared.targets.add("0 clear");
        controller.rebindProfile(cleared); assertFalse(current.get());
        controller.rebindProfile(fixture.profile); assertTrue(current.get());
        controller.close(); controller.close(); assertFalse(current.get());
        verify(zombie, times(2)).setCanBreakDoors(false);
        current.set(true);
        var next = fixture.controller(); next.close(); assertTrue(current.get(), "pre-existing door support is restored exactly");
        controller.tick(); verify(zombie, times(4)).setCanBreakDoors(true);
    }

    @Test void unsupportedDoorSpeciesAreDiagnosedWithoutTouchingOtherNativeState() {
        for (Mob mob : List.of(mock(Cow.class), mock(Zombie.class))) {
            var fixture = new Fixture(mob); fixture.profile.goals.add("0 breakdoors");
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(Bukkit::getLogger).thenReturn(Logger.getAnonymousLogger());
                assertTrue(fixture.controller().selectorDiagnostic().contains("requires a Zombie supporting door breaking"));
                if (mob instanceof Zombie zombie) verify(zombie, never()).setCanBreakDoors(anyBoolean());
            }
        }
    }

    private record Species(String token, Class<? extends LivingEntity> type) {}

    private static final class Fixture {
        final World world = mock(World.class);
        final Mob mob;
        final ModelInstanceImpl instance = mock(ModelInstanceImpl.class);
        final AiProfile profile = new AiProfile("selectors");
        final List<Entity> nearby = new ArrayList<>();
        Fixture() { this(mock(Mob.class)); }
        Fixture(Mob mob) {
            this.mob = mob; prepare(mob, 0);
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble())).thenReturn(nearby);
            when(instance.entity()).thenReturn(mob);
            when(instance.location()).thenReturn(new Location(world, 0, 70, 0));
            when(instance.isValid()).thenReturn(true);
            profile.targets.add("0 clear"); profile.canPatrol = false;
        }
        AiController controller() { return new AiController(instance, profile, null, new HelsteraEventBus()); }
        <T extends LivingEntity> T entity(Class<T> type, double x) {
            T entity = mock(type); prepare(entity, x); return entity;
        }
        private void prepare(LivingEntity entity, double x) {
            when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
            when(entity.isValid()).thenReturn(true);
            when(entity.getLocation()).thenAnswer(invocation -> new Location(world, x, 70, 0));
            when(entity.getWorld()).thenReturn(world);
            when(entity.getHealth()).thenReturn(20.0); when(entity.getMaxHealth()).thenReturn(20.0);
            if (entity instanceof Player player) {
                when(player.isOnline()).thenReturn(true); when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
            }
        }
    }
}
