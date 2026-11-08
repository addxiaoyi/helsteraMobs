package dev.helstera.ai;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DamageSourceResolverTest {

    @Test
    @DisplayName("direct entity damage keeps the original source")
    void keepsDirectEntity() {
        Entity attacker = mock(Entity.class);

        assertSame(attacker, DamageSourceResolver.resolve(attacker));
    }

    @Test
    @DisplayName("projectile damage resolves an entity-backed shooter")
    void resolvesEntityShooter() {
        Projectile projectile = mock(Projectile.class);
        Player shooter = mock(Player.class);
        when(projectile.getShooter()).thenReturn(shooter);

        assertSame(shooter, DamageSourceResolver.resolve(projectile));
    }

    @Test
    @DisplayName("projectile with a non-entity shooter remains a projectile source")
    void keepsProjectileForNonEntityShooter() {
        Projectile projectile = mock(Projectile.class);
        ProjectileSource source = mock(ProjectileSource.class);
        when(projectile.getShooter()).thenReturn(source);

        assertSame(projectile, DamageSourceResolver.resolve(projectile));
    }

    @Test
    @DisplayName("projectile without a shooter remains a projectile source")
    void keepsProjectileWithoutShooter() {
        Projectile projectile = mock(Projectile.class);
        when(projectile.getShooter()).thenReturn(null);

        assertSame(projectile, DamageSourceResolver.resolve(projectile));
    }

    @Test
    @DisplayName("resolver is null-safe")
    void handlesNull() {
        assertNull(DamageSourceResolver.resolve(null));
    }

    @Test
    @DisplayName("removed projectile callbacks retain their original source")
    void keepsSourceWhenShooterLookupFails() {
        Projectile projectile = mock(Projectile.class);
        when(projectile.getShooter()).thenThrow(new IllegalStateException("removed"));

        assertSame(projectile, DamageSourceResolver.resolve(projectile));
    }
}
