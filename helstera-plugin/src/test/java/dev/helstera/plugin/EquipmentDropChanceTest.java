package dev.helstera.plugin;

import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EquipmentDropChanceTest {

    @Test
    void writesValidatedChanceToRequestedPaperSlot() {
        var observedSlot = new AtomicReference<EquipmentSlot>();
        var observedChance = new AtomicReference<Float>();
        EntityEquipment equipment = (EntityEquipment) Proxy.newProxyInstance(
                EntityEquipment.class.getClassLoader(), new Class<?>[]{EntityEquipment.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setDropChance")) {
                        observedSlot.set((EquipmentSlot) args[0]);
                        observedChance.set((Float) args[1]);
                    }
                    return null;
                });

        assertTrue(HelsteraPlugin.applyEquipmentDropChance(
                equipment, EquipmentSlot.HAND, 0.35));
        assertEquals(EquipmentSlot.HAND, observedSlot.get());
        assertEquals(0.35f, observedChance.get());
    }

    @Test
    void rejectsOutOfRangeAndNonFiniteChancesWithoutWriting() {
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        EntityEquipment equipment = (EntityEquipment) Proxy.newProxyInstance(
                EntityEquipment.class.getClassLoader(), new Class<?>[]{EntityEquipment.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setDropChance")) writes.incrementAndGet();
                    return null;
                });

        assertFalse(HelsteraPlugin.applyEquipmentDropChance(equipment, EquipmentSlot.HEAD, -0.01));
        assertFalse(HelsteraPlugin.applyEquipmentDropChance(equipment, EquipmentSlot.HEAD, 1.01));
        assertFalse(HelsteraPlugin.applyEquipmentDropChance(equipment, EquipmentSlot.HEAD, Double.NaN));
        assertFalse(HelsteraPlugin.applyEquipmentDropChance(equipment, EquipmentSlot.HEAD,
                Double.POSITIVE_INFINITY));
        assertEquals(0, writes.get());
    }
}
