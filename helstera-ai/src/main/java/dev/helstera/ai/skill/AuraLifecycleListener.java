package dev.helstera.ai.skill;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** One listener for all aura trackers, resolving the current registry after reload. */
public final class AuraLifecycleListener implements Listener {
    private final Supplier<AuraRegistry> registry;

    public AuraLifecycleListener(Supplier<AuraRegistry> registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        Map<AuraRegistry.CancelEvent, UUID> cancellations = new java.util.EnumMap<>(AuraRegistry.CancelEvent.class);
        cancellations.put(AuraRegistry.CancelEvent.TAKE_DAMAGE, event.getEntity().getUniqueId());
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            // Match Mythic's direct damager identity; projectile shooters are not substituted.
            cancellations.put(AuraRegistry.CancelEvent.GIVE_DAMAGE, byEntity.getDamager().getUniqueId());
        }
        cancel(cancellations);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        cancelTeleport(event);
    }

    @EventHandler
    public void onPortal(PlayerPortalEvent event) {
        cancelTeleport(event);
    }

    private void cancelTeleport(PlayerTeleportEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        Map<AuraRegistry.CancelEvent, UUID> cancellations = new java.util.EnumMap<>(AuraRegistry.CancelEvent.class);
        cancellations.put(AuraRegistry.CancelEvent.TELEPORT, player);
        if (event.getTo() != null && !Objects.equals(event.getFrom().getWorld(), event.getTo().getWorld())) {
            cancellations.put(AuraRegistry.CancelEvent.CHANGE_WORLD, player);
        }
        cancel(cancellations);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        UUID entity = event.getEntity().getUniqueId();
        cancel(Map.of(AuraRegistry.CancelEvent.DEATH, entity, AuraRegistry.CancelEvent.CASTER_DEATH, entity));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), AuraRegistry.CancelEvent.QUIT);
    }

    private void cancel(UUID entity, AuraRegistry.CancelEvent event) {
        cancel(Map.of(event, entity));
    }

    private void cancel(Map<AuraRegistry.CancelEvent, UUID> cancellations) {
        AuraRegistry current = registry.get();
        if (current != null) current.cancel(cancellations);
    }
}
