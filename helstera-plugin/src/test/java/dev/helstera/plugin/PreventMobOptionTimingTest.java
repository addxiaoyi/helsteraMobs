package dev.helstera.plugin;

import dev.helstera.api.instance.SpawnOptions;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreventMobOptionTimingTest {

    @Test
    void blockInfectionPolicyIsSilverfishAndInfestedBlockSpecific() {
        var silverfish = proxy(org.bukkit.entity.Silverfish.class);
        var pig = proxy(org.bukkit.entity.Pig.class);
        var enabled = SpawnOptions.defaults().preventBlockInfection(true);

        assertTrue(HelsteraPlugin.preventsSilverfishBlockInfection(
                silverfish, org.bukkit.Material.INFESTED_STONE, enabled));
        assertFalse(HelsteraPlugin.preventsSilverfishBlockInfection(
                silverfish, org.bukkit.Material.STONE, enabled));
        assertFalse(HelsteraPlugin.preventsSilverfishBlockInfection(
                proxy(org.bukkit.entity.Silverfish.class), org.bukkit.Material.INFESTED_STONE,
                SpawnOptions.defaults()));
        assertFalse(HelsteraPlugin.preventsSilverfishBlockInfection(
                pig, org.bukkit.Material.INFESTED_STONE, enabled));
    }

    @Test
    void snowFormationPolicyOnlyAppliesToEnabledSnowGolem() {
        var snowman = proxy(org.bukkit.entity.Snowman.class);
        var pig = proxy(org.bukkit.entity.Pig.class);
        assertTrue(HelsteraPlugin.preventsSnowFormationFor(snowman,
                SpawnOptions.defaults().preventSnowFormation(true)));
        assertFalse(HelsteraPlugin.preventsSnowFormationFor(proxy(org.bukkit.entity.Snowman.class),
                SpawnOptions.defaults()));
        assertFalse(HelsteraPlugin.preventsSnowFormationFor(pig,
                SpawnOptions.defaults().preventSnowFormation(true)));
    }

    @Test
    void jockeyGuardOnlyBlocksZombieAsPassenger() {
        assertFalse(HelsteraPlugin.preventsEntityMount(SpawnOptions.defaults(), EntityType.ZOMBIE));
        assertTrue(HelsteraPlugin.preventsEntityMount(
                SpawnOptions.defaults().preventJockeyMounts(true), EntityType.ZOMBIE));
        assertFalse(HelsteraPlugin.preventsEntityMount(
                SpawnOptions.defaults().preventJockeyMounts(true), EntityType.CHICKEN));
    }

    @Test
    void jockeyGuardOnlyBlocksZombieChickenPair() {
        var protectedOptions = SpawnOptions.defaults().preventJockeyMounts(true);
        var zombie = proxy(org.bukkit.entity.Zombie.class);
        var chicken = proxy(org.bukkit.entity.Chicken.class);
        var pig = proxy(org.bukkit.entity.Pig.class);

        assertTrue(HelsteraPlugin.preventsEntityMount(protectedOptions, zombie, chicken));
        assertFalse(HelsteraPlugin.preventsEntityMount(protectedOptions, zombie, pig));
        assertFalse(HelsteraPlugin.preventsEntityMount(protectedOptions, chicken, pig));
        assertTrue(HelsteraPlugin.preventsEntityMount(
                SpawnOptions.defaults().preventMount(true), chicken, pig));
    }

    @Test
    void preventMountBlocksEveryPassengerType() {
        assertTrue(HelsteraPlugin.preventsEntityMount(
                SpawnOptions.defaults().preventMount(true), EntityType.ZOMBIE));
        assertTrue(HelsteraPlugin.preventsEntityMount(
                SpawnOptions.defaults().preventMount(true), EntityType.CHICKEN));
    }

    @Test
    void dropPreventionPoliciesDoNotDependOnLootService() {
        var drops = new ArrayList<org.bukkit.inventory.ItemStack>();
        drops.add(null);
        var victim = (org.bukkit.entity.LivingEntity) Proxy.newProxyInstance(
                org.bukkit.entity.LivingEntity.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.LivingEntity.class}, (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == double.class) return 0.0;
                    return null;
                });
        var event = new org.bukkit.event.entity.EntityDeathEvent(victim, null, drops, 7);

        HelsteraPlugin.applyOtherDropPolicy(event, SpawnOptions.defaults().preventOtherDrops(true));
        org.junit.jupiter.api.Assertions.assertTrue(drops.isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(0, event.getDroppedExp());

        drops.add(null);
        HelsteraPlugin.applyMobKillDropPolicy(event, SpawnOptions.defaults().preventMobKillDrops(true));
        org.junit.jupiter.api.Assertions.assertTrue(drops.isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(0, event.getDroppedExp());
    }

    @Test
    void explicitExperienceOverridesVanillaPolicyAndRollsInclusiveRanges() {
        var drops = new ArrayList<org.bukkit.inventory.ItemStack>();
        var victim = proxy(org.bukkit.entity.LivingEntity.class);
        var event = new org.bukkit.event.entity.EntityDeathEvent(victim, null, drops, 7);

        HelsteraPlugin.applyOtherDropPolicy(event, SpawnOptions.defaults().preventOtherDrops(true));
        HelsteraPlugin.applyExperienceOverride(event, 13);
        org.junit.jupiter.api.Assertions.assertEquals(13, event.getDroppedExp());

        assertEquals(-1, HelsteraPlugin.rollExperienceOverride(-1, 4, new Random(1)));
        assertEquals(-1, HelsteraPlugin.rollExperienceOverride(8, 4, new Random(1)));
        assertEquals(4, HelsteraPlugin.rollExperienceOverride(4, 4, new Random(1)));
        assertEquals(Integer.MAX_VALUE,
                HelsteraPlugin.rollExperienceOverride(Integer.MAX_VALUE, Integer.MAX_VALUE, new Random(1)));
        for (int i = 0; i < 32; i++) {
            int value = HelsteraPlugin.rollExperienceOverride(4, 8, new Random(i));
            assertTrue(value >= 4 && value <= 8);
        }
    }

    @Test
    void dropLuckUsesEffectivePlayerLuckAttributeAndFailsClosedWhenUnavailable() {
        var attribute = (org.bukkit.attribute.AttributeInstance) Proxy.newProxyInstance(
                org.bukkit.attribute.AttributeInstance.class.getClassLoader(),
                new Class<?>[]{org.bukkit.attribute.AttributeInstance.class},
                (proxy, method, args) -> method.getName().equals("getValue") ? 4.25 : null);
        var killer = (org.bukkit.entity.Player) Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(), new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, args) -> method.getName().equals("getAttribute") ? attribute : null);
        var noLuckAttribute = (org.bukkit.entity.Player) Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(), new Class<?>[]{org.bukkit.entity.Player.class},
                (proxy, method, args) -> null);

        assertEquals(4.25, HelsteraPlugin.resolveDropLuck(killer));
        assertEquals(0.0, HelsteraPlugin.resolveDropLuck(noLuckAttribute));
        assertEquals(0.0, HelsteraPlugin.resolveDropLuck(null));
    }

    @Test
    void healOnReloadRestoresOnlyEnabledValidLivingEntities() {
        double[] health = {7.0};
        double[] maxHealth = {20.0};
        boolean[] valid = {true};
        boolean[] dead = {false};
        var entity = (org.bukkit.entity.LivingEntity) Proxy.newProxyInstance(
                org.bukkit.entity.LivingEntity.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.LivingEntity.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getHealth" -> health[0];
                        case "getMaxHealth" -> maxHealth[0];
                        case "setHealth" -> {
                            health[0] = ((Number) args[0]).doubleValue();
                            yield null;
                        }
                        case "isValid" -> valid[0];
                        case "isDead" -> dead[0];
                        default -> {
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) yield false;
                            if (type == int.class) yield 0;
                            if (type == long.class) yield 0L;
                            if (type == float.class) yield 0f;
                            if (type == double.class) yield 0d;
                            yield null;
                        }
                    };
                });

        assertTrue(HelsteraPlugin.applyHealOnReloadPolicy(
                entity, SpawnOptions.defaults().healOnReload(true)));
        assertEquals(20.0, health[0]);

        health[0] = 7.0;
        assertFalse(HelsteraPlugin.applyHealOnReloadPolicy(entity, SpawnOptions.defaults()));
        assertEquals(7.0, health[0]);

        valid[0] = false;
        assertFalse(HelsteraPlugin.applyHealOnReloadPolicy(
                entity, SpawnOptions.defaults().healOnReload(true)));
        valid[0] = true;
        maxHealth[0] = Double.NaN;
        assertFalse(HelsteraPlugin.applyHealOnReloadPolicy(
                entity, SpawnOptions.defaults().healOnReload(true)));
        assertFalse(HelsteraPlugin.applyHealOnReloadPolicy(null,
                SpawnOptions.defaults().healOnReload(true)));
    }

    @Test
    void preventSunburnOnlyClassifiesTheBaseCombustEventAsSunlight() {
        var entity = (org.bukkit.entity.Entity) Proxy.newProxyInstance(
                org.bukkit.entity.Entity.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Entity.class}, (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == double.class) return 0.0;
                    return null;
                });
        var direct = new org.bukkit.event.entity.EntityCombustEvent(entity, 8);
        assertTrue(HelsteraPlugin.isSunlightCombustEvent(direct));

        var block = new org.bukkit.event.entity.EntityCombustByBlockEvent(null, entity, 8);
        var attacker = new org.bukkit.event.entity.EntityCombustByEntityEvent(entity, entity, 8);
        assertFalse(HelsteraPlugin.isSunlightCombustEvent(block));
        assertFalse(HelsteraPlugin.isSunlightCombustEvent(attacker));
    }

    @Test
    void resolvesManagedMobAndProjectileShootersForDeathPolicies() {
        var damager = (org.bukkit.entity.Entity) Proxy.newProxyInstance(
                org.bukkit.entity.Entity.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Entity.class}, (proxy, method, args) -> null);
        assertTrue(HelsteraPlugin.resolveDamageSource(damager) == damager);

        var shooter = (org.bukkit.entity.Entity) Proxy.newProxyInstance(
                org.bukkit.entity.Entity.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Entity.class, org.bukkit.projectiles.ProjectileSource.class},
                (proxy, method, args) -> null);
        var projectile = (org.bukkit.entity.Projectile) Proxy.newProxyInstance(
                org.bukkit.entity.Projectile.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Projectile.class, org.bukkit.projectiles.ProjectileSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getShooter")) return shooter;
                    return null;
                });
        assertTrue(HelsteraPlugin.resolveDamageSource(projectile) == shooter);
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    Class<?> result = method.getReturnType();
                    if (result == boolean.class) return false;
                    if (result == int.class) return 0;
                    if (result == long.class) return 0L;
                    if (result == float.class) return 0f;
                    if (result == double.class) return 0d;
                    return null;
                }));
    }
}
