package dev.helstera.ai;

import dev.helstera.api.behavior.BehaviorRegistry;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.MobStateChangedEvent;
import dev.helstera.api.event.ModelPreTargetEvent;
import dev.helstera.api.event.ModelTargetChangedEvent;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiThreatTargetSelectionTest {
    @Test
    void decisionCommitsBeforeCallbacksAndKeepsExistingCombatSelectionPolicy() {
        var fixture = new Fixture(null);
        var controller = fixture.controller;
        var threat = controller.threatTable();
        var states = new ArrayList<UUID>();
        var attacks = new ArrayList<UUID>();
        fixture.bus.register(MobStateChangedEvent.class, event -> states.add(threat.currentTarget()));
        fixture.bus.register(ModelPreTargetEvent.class, event -> attacks.add(threat.currentTarget()));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getEntity(fixture.firstId)).thenReturn(fixture.first);
            bukkit.when(() -> Bukkit.getEntity(fixture.secondId)).thenReturn(fixture.second);
            threat.addThreat(fixture.firstId, 100);
            threat.addThreat(fixture.secondId, 10);
            assertNull(threat.currentTarget());
            controller.tick();
            assertEquals(fixture.firstId, threat.currentTarget());
            assertSame(fixture.first, controller.targetEntity());
            assertEquals(List.of(fixture.firstId), states);
            assertEquals(List.of(fixture.firstId), attacks);

            threat.addThreat(fixture.secondId, 1000);
            assertEquals(fixture.secondId, threat.top(null));
            assertEquals(fixture.firstId, threat.currentTarget());
            controller.tick();
            assertSame(fixture.first, controller.targetEntity(), "Existing valid combat targets remain sticky");
            assertEquals(fixture.firstId, threat.currentTarget());

            threat.remove(fixture.firstId);
            assertNull(threat.currentTarget());
            controller.tick();
            assertSame(fixture.second, controller.targetEntity());
            assertEquals(fixture.secondId, threat.currentTarget());
            assertEquals(fixture.secondId, attacks.getLast());
        }
    }

    @Test
    void blockedDecisionDoesNotCommitTheSensedThreatCandidate() {
        var behaviors = mock(BehaviorRegistry.class);
        var fixture = new Fixture(behaviors);
        fixture.profile.require.add("blocked");
        var threat = fixture.controller.threatTable();
        threat.addThreat(fixture.firstId, 10);
        threat.addThreat(fixture.secondId, 100);
        threat.selectTarget(fixture.firstId);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getEntity(fixture.firstId)).thenReturn(fixture.first);
            bukkit.when(() -> Bukkit.getEntity(fixture.secondId)).thenReturn(fixture.second);
            fixture.controller.tick();
        }
        verify(behaviors).testCondition(eq("blocked"), any());
        assertEquals(fixture.firstId, threat.currentTarget());
        assertNull(fixture.controller.targetEntity());
    }

    @Test
    void damageAndExplicitTargetChangesCommitOnlyRecordedThreatMembers() {
        var fixture = new Fixture(null);
        var controller = fixture.controller;
        var threat = controller.threatTable();
        var changes = new ArrayList<UUID>();
        var nativeTargetCallbacks = new ArrayList<UUID>();
        doAnswer(invocation -> {
            nativeTargetCallbacks.add(threat.currentTarget());
            return null;
        }).when(fixture.mob).setTarget(any());
        fixture.bus.register(ModelTargetChangedEvent.class, event -> changes.add(threat.currentTarget()));
        controller.onDamaged(fixture.first, 5);
        assertEquals(fixture.firstId, threat.currentTarget());
        assertSame(fixture.first, controller.targetEntity());
        controller.setCombatTarget(fixture.second);
        assertNull(threat.currentTarget());
        assertEquals(1, changes.size());
        assertNull(changes.getFirst());

        threat.addThreat(fixture.secondId, 10);
        controller.setCombatTarget(fixture.second);
        assertEquals(fixture.secondId, threat.currentTarget());
        assertEquals(fixture.secondId, nativeTargetCallbacks.getLast());
        controller.clearCombatTarget();
        assertNull(threat.currentTarget());
        assertNull(changes.getLast());
        assertNull(nativeTargetCallbacks.getLast());

        var player = fixture.entity(Player.class, 3);
        UUID playerId = player.getUniqueId();
        controller.setCombatTarget(player);
        assertEquals(playerId, threat.currentTarget(), "Existing setCombatTarget player focus commits immediately");
        assertEquals(playerId, changes.getLast());
        assertEquals(playerId, nativeTargetCallbacks.getLast());
    }

    @Test
    void profileClearingAndDeathClearTheCommittedTargetBeforeLaterCallbacks() {
        var fixture = new Fixture(null);
        var controller = fixture.controller;
        var threat = controller.threatTable();
        threat.addThreat(fixture.firstId, 10);
        controller.setCombatTarget(fixture.first);
        fixture.profile.targets.clear();
        fixture.profile.targets.add("0 clear");
        controller.tick();
        assertNull(threat.currentTarget());
        assertNull(controller.targetEntity());

        controller.setCombatTarget(fixture.first);
        var deathSelections = new ArrayList<UUID>();
        fixture.bus.register(MobStateChangedEvent.class, event -> deathSelections.add(threat.currentTarget()));
        controller.markDead();
        assertNull(threat.currentTarget());
        assertEquals(1, deathSelections.size());
        assertNull(deathSelections.getFirst());
    }

    private static final class Fixture {
        private final World world = mock(World.class);
        private final Location origin = new Location(world, 0, 70, 0);
        private final Mob mob = entity(Mob.class, 0);
        private final LivingEntity first = entity(LivingEntity.class, 1);
        private final LivingEntity second = entity(LivingEntity.class, 2);
        private final UUID firstId = first.getUniqueId();
        private final UUID secondId = second.getUniqueId();
        private final ModelInstanceImpl instance = mock(ModelInstanceImpl.class);
        private final HelsteraEventBus bus = new HelsteraEventBus();
        private final AiProfile profile = new AiProfile("stored-threat-target");
        private final AiController controller;

        private Fixture(BehaviorRegistry behaviors) {
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(instance.entity()).thenReturn(mob);
            when(instance.location()).thenReturn(origin);
            when(instance.isValid()).thenReturn(true);
            profile.threatEnabled = true;
            profile.threatDecayPerSecond = 0;
            profile.threatDistanceWeight = 0;
            profile.canPatrol = false;
            profile.attackRadius = 4;
            profile.targets.add("0 threat");
            controller = new AiController(instance, profile, null, bus, behaviors);
        }

        private <T extends LivingEntity> T entity(Class<T> type, double x) {
            T entity = mock(type);
            UUID identity = UUID.randomUUID();
            when(entity.getUniqueId()).thenReturn(identity);
            when(entity.isValid()).thenReturn(true);
            when(entity.getLocation()).thenReturn(new Location(world, x, 70, 0));
            when(entity.getWorld()).thenReturn(world);
            return entity;
        }
    }
}
