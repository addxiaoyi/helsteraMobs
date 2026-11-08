package dev.helstera.ai;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Resolves the entity responsible for an entity-damage event.
 *
 * <p>Bukkit keeps the projectile as {@code EntityDamageByEntityEvent#getDamager()}
 * for arrows, tridents, fireballs and similar attacks. Mythic trigger and AI
 * semantics need the entity that launched that projectile when Bukkit exposes
 * one. A projectile with no entity-backed shooter remains the source entity so
 * callers can still distinguish it from environmental damage.</p>
 */
public final class DamageSourceResolver {

    private DamageSourceResolver() {
    }

    /**
     * Returns the direct attacker or an entity-backed projectile shooter.
     *
     * @param source Bukkit's damage source, possibly {@code null}
     * @return the resolved entity, the original projectile for non-entity
     *         shooters, or {@code null}
     */
    public static Entity resolve(Entity source) {
        if (!(source instanceof Projectile projectile)) return source;
        try {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Entity entity ? entity : source;
        } catch (RuntimeException ignored) {
            // An entity can be removed while a late damage callback is inspected.
            return source;
        }
    }
}
