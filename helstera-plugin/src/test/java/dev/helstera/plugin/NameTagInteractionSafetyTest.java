package dev.helstera.plugin;

import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class NameTagInteractionSafetyTest {

    @Test
    void missingPlayerOrHandFailsClosed() {
        assertFalse(HelsteraPlugin.isNameTagUse(null, EquipmentSlot.HAND));
        assertFalse(HelsteraPlugin.isNameTagUse(null, null));
    }
}
