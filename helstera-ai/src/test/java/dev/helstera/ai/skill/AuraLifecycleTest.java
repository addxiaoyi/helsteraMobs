package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuraLifecycleTest {
    private static void apply(AuraRegistry registry, UUID target, UUID caster, String name,
                              Set<AuraRegistry.CancelEvent> events, Runnable end) {
        assertTrue(registry.applyWithResult(target, caster, name, "", 100, 3,
                true, true, false, false, false, 1, null, ignored -> end.run(), false, events).applied());
    }

    @Test
    void cancellationHonorsTargetAndPolicyAndRunsEndExactlyOnceDespiteRemovalFlag() {
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        UUID target = UUID.randomUUID();
        AtomicInteger ended = new AtomicInteger();
        apply(registry, target, null, "ward", Set.of(AuraRegistry.CancelEvent.TAKE_DAMAGE), ended::incrementAndGet);
        assertEquals(0, registry.cancel(UUID.randomUUID(), AuraRegistry.CancelEvent.TAKE_DAMAGE));
        assertEquals(0, registry.cancel(target, AuraRegistry.CancelEvent.GIVE_DAMAGE));
        assertEquals(1, registry.cancel(target, AuraRegistry.CancelEvent.TAKE_DAMAGE));
        assertFalse(registry.hasAura(target, "ward"));
        assertEquals(0, registry.cancel(target, AuraRegistry.CancelEvent.TAKE_DAMAGE));
        assertEquals(1, ended.get());
    }

    @Test
    void casterDeathRemovesOnlyOwnedTrackersAcrossTargetsAndIsReentrantSafe() {
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        UUID caster = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        AtomicInteger ended = new AtomicInteger();
        Set<AuraRegistry.CancelEvent> events = Set.of(AuraRegistry.CancelEvent.CASTER_DEATH);
        apply(registry, first, caster, "owned", events, () -> {
            ended.incrementAndGet();
            registry.cancel(caster, AuraRegistry.CancelEvent.CASTER_DEATH);
        });
        apply(registry, second, caster, "owned", events, ended::incrementAndGet);
        apply(registry, second, UUID.randomUUID(), "other", events, ended::incrementAndGet);
        assertEquals(2, registry.cancel(caster, AuraRegistry.CancelEvent.CASTER_DEATH));
        assertEquals(2, ended.get());
        assertTrue(registry.hasAura(second, "other"));
    }

    @Test
    void mergingRetainsOriginalCancellationPolicy() {
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        UUID target = UUID.randomUUID();
        apply(registry, target, null, "ward", Set.of(AuraRegistry.CancelEvent.QUIT), () -> { });
        apply(registry, target, null, "ward", Set.of(AuraRegistry.CancelEvent.TAKE_DAMAGE), () -> { });
        assertEquals(0, registry.cancel(target, AuraRegistry.CancelEvent.TAKE_DAMAGE));
        assertEquals(1, registry.cancel(target, AuraRegistry.CancelEvent.QUIT));
    }

    @Test
    void oneDamageEventCannotCancelATrackerCreatedByItsOwnEndCallback() {
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        apply(registry, victim, null, "original", Set.of(AuraRegistry.CancelEvent.TAKE_DAMAGE), () ->
                apply(registry, attacker, null, "replacement", Set.of(AuraRegistry.CancelEvent.GIVE_DAMAGE), () -> { }));
        registry.cancel(java.util.Map.of(AuraRegistry.CancelEvent.TAKE_DAMAGE, victim,
                AuraRegistry.CancelEvent.GIVE_DAMAGE, attacker));
        assertTrue(registry.hasAura(attacker, "replacement"));
    }

    @Test
    void damageUsesDirectDamagerAndObservesCancelledEventsWithoutMutatingThem() {
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        AuraLifecycleListener listener = new AuraLifecycleListener(() -> registry);
        Entity victim = mock(Entity.class);
        Entity projectile = mock(Entity.class);
        UUID victimId = UUID.randomUUID();
        UUID projectileId = UUID.randomUUID();
        when(victim.getUniqueId()).thenReturn(victimId);
        when(projectile.getUniqueId()).thenReturn(projectileId);
        apply(registry, victimId, null, "take", Set.of(AuraRegistry.CancelEvent.TAKE_DAMAGE), () -> { });
        apply(registry, projectileId, null, "give", Set.of(AuraRegistry.CancelEvent.GIVE_DAMAGE), () -> { });
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn(projectile);
        when(event.isCancelled()).thenReturn(true);
        listener.onDamage(event);
        assertFalse(registry.hasAura(victimId, "take"));
        assertFalse(registry.hasAura(projectileId, "give"));
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    void worldChangeRequiresDifferentWorldAndListenerUsesReloadedRegistry() {
        AuraRegistry previous = new AuraRegistry(() -> 1, ignored -> true);
        AuraRegistry current = new AuraRegistry(() -> 1, ignored -> true);
        AtomicReference<AuraRegistry> reference = new AtomicReference<>(previous);
        AuraLifecycleListener listener = new AuraLifecycleListener(reference::get);
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        Set<AuraRegistry.CancelEvent> events = Set.of(AuraRegistry.CancelEvent.CHANGE_WORLD);
        apply(previous, playerId, null, "world", events, () -> { });
        apply(current, playerId, null, "world", events, () -> { });
        reference.set(current);
        World first = mock(World.class);
        World second = mock(World.class);
        PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(new Location(first, 0, 0, 0));
        when(event.getTo()).thenReturn(new Location(first, 1, 0, 0));
        listener.onTeleport(event);
        assertTrue(current.hasAura(playerId, "world"));
        when(event.getTo()).thenReturn(new Location(second, 1, 0, 0));
        listener.onTeleport(event);
        assertFalse(current.hasAura(playerId, "world"));
        assertTrue(previous.hasAura(playerId, "world"));
        reference.set(null);
        listener.onTeleport(event);
    }

    @Test
    void directAuraBindingValidatesCancellationAliasesAndDuplicates() {
        BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
        SkillService skills = new SkillService(registry, java.util.logging.Logger.getAnonymousLogger());
        assertNotNull(skills.bindAction("aura{aura=ward;cotd=true;cogd=false;cod=false;cocd=true;cot=true;cocw=true;coq=false}"));
        assertNull(skills.bindAction("aura{aura=ward;cotd=yes}"));
        assertNull(skills.bindAction("aura{aura=ward;cotd=true;cancelOnTakeDamage=false}"));
        assertNull(skills.bindAction("aura{aura=ward;cancelOnSkillUse=true}"));
    }
}
