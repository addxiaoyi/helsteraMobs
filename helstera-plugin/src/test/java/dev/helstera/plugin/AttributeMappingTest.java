package dev.helstera.plugin;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttributeMappingTest {

    @Test
    void resolvesMythicAttributeAliasesAndPaperSlotAliases() {
        assertEquals(Attribute.GENERIC_ATTACK_DAMAGE,
                HelsteraPlugin.resolveAttribute("Damage"));
        assertEquals(Attribute.GENERIC_MOVEMENT_SPEED,
                HelsteraPlugin.resolveAttribute("minecraft:movement-speed"));
        assertEquals(Attribute.GENERIC_MAX_HEALTH,
                HelsteraPlugin.resolveAttribute("MaxHealth"));
        assertEquals(EquipmentSlotGroup.MAINHAND,
                HelsteraPlugin.resolveEquipmentSlotGroup("main-hand"));
        assertEquals(EquipmentSlotGroup.HAND,
                HelsteraPlugin.resolveEquipmentSlotGroup("weapon"));
        assertEquals(EquipmentSlotGroup.ARMOR,
                HelsteraPlugin.resolveEquipmentSlotGroup("armor"));
    }

    @Test
    void normalizesMythicOperationsAndPercentAmountsWithoutAcceptingNonFiniteValues() {
        assertEquals("ADD_NUMBER", HelsteraPlugin.normalizeAttributeOperation("ADD"));
        assertEquals("ADD_SCALAR", HelsteraPlugin.normalizeAttributeOperation("MULTIPLY_BASE"));
        assertEquals("MULTIPLY_SCALAR_1", HelsteraPlugin.normalizeAttributeOperation("MULTIPLY_TOTAL"));
        assertEquals("ADD_NUMBER", HelsteraPlugin.normalizeAttributeOperation("0"));
        assertEquals("MULTIPLY_SCALAR_1", HelsteraPlugin.normalizeAttributeOperation("2"));
        assertEquals("UNKNOWN", HelsteraPlugin.normalizeAttributeOperation("UNKNOWN"));

        assertEquals(0.1, HelsteraPlugin.parseAttributeAmount("10%"), 1.0e-9);
        assertEquals(2.5, HelsteraPlugin.parseAttributeAmount("2.5"), 1.0e-9);
        assertFalse(Double.isFinite(HelsteraPlugin.parseAttributeAmount("NaN")));
        assertFalse(Double.isFinite(HelsteraPlugin.parseAttributeAmount("Infinity")));
        assertTrue(Double.isFinite(HelsteraPlugin.parseAttributeAmount("-1")));
    }

    @Test
    void paperOperationNamesRemainTheRuntimeContract() {
        assertEquals(AttributeModifier.Operation.ADD_NUMBER,
                AttributeModifier.Operation.valueOf(HelsteraPlugin.normalizeAttributeOperation("ADD")));
        assertEquals(AttributeModifier.Operation.ADD_SCALAR,
                AttributeModifier.Operation.valueOf(HelsteraPlugin.normalizeAttributeOperation("ADD_SCALAR")));
        assertEquals(AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                AttributeModifier.Operation.valueOf(HelsteraPlugin.normalizeAttributeOperation("MULTIPLY")));
    }
}
