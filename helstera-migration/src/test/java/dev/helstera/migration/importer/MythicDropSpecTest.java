package dev.helstera.migration.importer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MythicDropSpecTest {
    @Test
    void parsesTextRangePercentAndExternalNamespace() {
        MythicDropSpec spec = MythicDropSpec.parse("MMOItems:SWORD 2-4 25%");
        assertEquals("MMOItems:SWORD", spec.item());
        assertEquals(2, spec.amountMin());
        assertEquals(4, spec.amountMax());
        assertEquals(0.25, spec.chance(), 1e-9);
        assertTrue(spec.external());
        assertTrue(spec.runtimeEntry().isEmpty());
        MythicDropSpec conditional = MythicDropSpec.parse("DIAMOND 1 50% ?world-is arena");
        assertEquals(List.of("world-is arena"), conditional.conditions());
        MythicDropSpec multiple = MythicDropSpec.parse(
                "DIAMOND 1 50% ?world-is arena ?permission loot.vip");
        assertEquals(List.of("world-is arena", "permission loot.vip"), multiple.conditions());
    }

    @Test
    void parsesMapTableConditionsAndRejectsInvalidRanges() {
        MythicDropSpec spec = MythicDropSpec.parse(Map.of(
                "table", "nested", "amount-min", 2, "amount-max", 3,
                "chance", 0.5, "chance-expression", "luck-based",
                "conditions", List.of("world-is arena")));
        assertTrue(spec.isTable());
        assertEquals(List.of("world-is arena"), spec.conditions());
        assertFalse(spec.external());
        assertEquals("nested", spec.runtimeEntry().get("table"));
        assertEquals(2, spec.runtimeEntry().get("amount-min"));
        assertEquals(0.5, spec.runtimeEntry().get("chance"));
        assertEquals("luck-based", spec.runtimeEntry().get("chance-expression"));
        assertNull(MythicDropSpec.parse("DIAMOND 4-2 1.0"));
    }

    @Test
    void runtimeProjectionPreservesConditionsAndDynamicChance() {
        MythicDropSpec spec = MythicDropSpec.parse(Map.of(
                "item", "DIAMOND",
                "amount", 2,
                "chance-expression", "luck-based",
                "conditions", List.of("permission loot.vip")));
        assertEquals("luck-based", spec.runtimeEntry().get("chance-expression"));
        assertEquals(List.of("permission loot.vip"), spec.runtimeEntry().get("conditions"));
        assertEquals(2, spec.runtimeEntry().get("amount-min"));
    }
}
