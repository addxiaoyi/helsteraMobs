package dev.helstera.plugin;

import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReinforcementsChanceTest {

    @Test
    void appliesOnlyFiniteZombieReinforcementChancesFromZeroThroughOne() {
        AtomicReference<Double> baseValue = new AtomicReference<>(0.0);
        AttributeInstance attribute = (AttributeInstance) Proxy.newProxyInstance(
                AttributeInstance.class.getClassLoader(), new Class<?>[]{AttributeInstance.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setBaseValue")) {
                        baseValue.set((double) args[0]);
                        return null;
                    }
                    if (method.getName().equals("getBaseValue")) return baseValue.get();
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == double.class) return 0.0;
                    return null;
                });
        Zombie zombie = (Zombie) Proxy.newProxyInstance(Zombie.class.getClassLoader(),
                new Class<?>[]{Zombie.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAttribute")
                            && args[0] == org.bukkit.attribute.Attribute.ZOMBIE_SPAWN_REINFORCEMENTS) {
                        return attribute;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == double.class) return 0.0;
                    return null;
                });

        assertTrue(HelsteraPlugin.applyZombieReinforcementsChance(zombie, 0.38));
        assertEquals(0.38, baseValue.get(), 1.0e-9);
        assertTrue(HelsteraPlugin.applyZombieReinforcementsChance(zombie, 0.0));
        assertTrue(HelsteraPlugin.applyZombieReinforcementsChance(zombie, 1.0));
        assertFalse(HelsteraPlugin.applyZombieReinforcementsChance(zombie, -0.01));
        assertFalse(HelsteraPlugin.applyZombieReinforcementsChance(zombie, 1.01));
        assertFalse(HelsteraPlugin.applyZombieReinforcementsChance(zombie, Double.NaN));
        assertFalse(HelsteraPlugin.applyZombieReinforcementsChance(zombie, Double.POSITIVE_INFINITY));

        Skeleton skeleton = (Skeleton) Proxy.newProxyInstance(Skeleton.class.getClassLoader(),
                new Class<?>[]{Skeleton.class}, (proxy, method, args) -> null);
        assertFalse(HelsteraPlugin.applyZombieReinforcementsChance(skeleton, 0.5));
    }
}
