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
    void rejectsMalformedPercentSignsWithoutChangingDropChance() {
        for (String malformed : List.of("1%0", "%25", "25%%", "2%5%", "0x1p-1", "0.5d", "0.5f", "25d%")) {
            assertNull(MythicDropSpec.parse("DIAMOND 1 " + malformed), malformed);
            assertNull(MythicDropSpec.parse(Map.of("item", "DIAMOND", "chance", malformed)), malformed);
        }
        assertEquals(0.25, MythicDropSpec.parse("DIAMOND 1 25%").chance(), 1e-9);
        assertEquals(0.25, MythicDropSpec.parse(Map.of("item", "DIAMOND", "chance", "25%"))
                .chance(), 1e-9);
        assertEquals(0.25, MythicDropSpec.parse("DIAMOND 1 2.5e1%").chance(), 1e-9);
        assertEquals(0.5, MythicDropSpec.parse(Map.of("item", "DIAMOND", "chance", ".5"))
                .chance(), 1e-9);
    }
    @Test
    void parsesTextRangePercentAndExternalNamespace() {
        MythicDropSpec spec = MythicDropSpec.parse("MMOItems:SWORD 2-4 25%");
        assertEquals("MMOItems:SWORD", spec.item());
        assertEquals(2, spec.amountMin());
        assertEquals(3, spec.amountMax());
        assertEquals(0.25, spec.chance(), 1e-9);
        assertTrue(spec.external());
        assertTrue(spec.runtimeEntry().isEmpty());
        MythicDropSpec toRange = MythicDropSpec.parse("DIAMOND 2to4 1.0");
        assertEquals(2, toRange.amountMin());
        assertEquals(3, toRange.amountMax());
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
        assertTrue(spec.runtimeEntry().isEmpty());
        assertNull(MythicDropSpec.parse("DIAMOND 4-2 1.0"));
    }

    @Test
    void normalizesNestedTableReferencesLikeTableDefinitions() {
        MythicDropSpec spec = MythicDropSpec.parse(Map.of("table", "Boss-A"));
        assertEquals("Boss-A", spec.table());
        assertEquals("boss_a", spec.runtimeEntry().get("table"));
    }

    @Test
    void punctuationOnlyTableReferenceDoesNotResolveToUnnamed() {
        MythicDropSpec spec = MythicDropSpec.parse(Map.of("table", "---"));
        assertTrue(spec.isTable());
        assertTrue(spec.runtimeEntry().isEmpty());
        assertEquals("---", spec.table());
        assertTrue(MythicDropSpec.parse(Map.of("table", "prefix.---")).runtimeEntry().isEmpty());
        assertEquals("unnamed", MythicDropSpec.parse(Map.of("table", "unnamed")).runtimeEntry().get("table"));
    }

    @Test
    void runtimeProjectionPreservesConditionsAndDynamicChance() {
        MythicDropSpec spec = MythicDropSpec.parse(Map.of(
                "item", "DIAMOND",
                "amount", 2,
                "chance-expression", "luck-based",
                "conditions", List.of("permission loot.vip")));
        assertEquals("luck-based", spec.runtimeEntry().get("chance-expression"));
        assertEquals(List.of("has-permission loot.vip"), spec.runtimeEntry().get("conditions"));
        assertEquals(2, spec.runtimeEntry().get("amount-min"));
    }

    @Test
    void projectsExperienceDropLinesAsRuntimeExperienceEntries() {
        MythicDropSpec spec = MythicDropSpec.parse("exp 2-5 50% ?world-is arena");

        assertEquals("exp", spec.runtimeEntry().get("item"));
        assertEquals(2, spec.runtimeEntry().get("amount-min"));
        assertEquals(4, spec.runtimeEntry().get("amount-max"));
        assertEquals(0.5, spec.runtimeEntry().get("chance"));
        assertEquals(List.of("world-is arena"), spec.runtimeEntry().get("conditions"));
    }

    @Test
    void runtimeProjectionTranslatesMythicConditionPrefixes() {
        MythicDropSpec spec = MythicDropSpec.parse(Map.of(
                "item", "DIAMOND",
                "conditions", List.of("?isLiving", "?healthPercent{p=>=50%}")));

        assertEquals(List.of("is-living", "health-percent >=50"),
                spec.runtimeEntry().get("conditions"));
    }

    @Test
    void dropIfAddsPositiveConditions() {
        MythicDropSpec spec = MythicDropSpec.parse(Map.of(
                "item", "DIAMOND",
                "drop-if", List.of("?isLiving", "?permission loot.vip")));

        assertEquals(List.of("isLiving", "permission loot.vip"), spec.conditions());
        assertEquals(List.of("is-living", "has-permission loot.vip"),
                spec.runtimeEntry().get("conditions"));
    }

    @Test
    void dropIfMergesAfterExistingConditions() {
        MythicDropSpec spec = MythicDropSpec.parse(Map.of(
                "item", "DIAMOND",
                "conditions", List.of("night"),
                "DropIf", "raining"));

        assertEquals(List.of("night", "raining"), spec.conditions());
    }

    @Test
    void noDropIfIsStoredAsNegatedCondition() {
        MythicDropSpec spec = MythicDropSpec.parse(Map.of(
                "item", "DIAMOND",
                "no-drop-if", List.of("?raining", "?night")));

        assertEquals(List.of("!raining", "!night"), spec.conditions());
    }

    @Test
    void noDropIfCancelsDoubleNegation() {
        MythicDropSpec spec = MythicDropSpec.parse(Map.of(
                "item", "DIAMOND",
                "NoDropIf", "!raining"));

        // no-drop-if: "!raining" 等于 drop-if: "raining"，不能留下 "!!raining"
        assertEquals(List.of("raining"), spec.conditions());
    }

    @Test
    void noDropIfSurvivesTheRuntimeProjection() {
        MythicDropSpec spec = MythicDropSpec.parse(Map.of(
                "item", "DIAMOND",
                "no-drop-if", "raining"));

        assertEquals(List.of("!raining"), spec.runtimeEntry().get("conditions"));
    }

    @Test
    void combinesAllConditionAliasesWithoutLosingRestrictions() {
        MythicDropSpec spec = MythicDropSpec.parse(Map.of(
                "item", "DIAMOND", "Conditions", "night", "drop-if", "raining",
                "DropIf", "world-is arena", "no-drop-if", "day", "NoDropIf", "thundering"));

        assertEquals(List.of("night", "raining", "world-is arena", "!time-is day", "!thundering"),
                spec.runtimeEntry().get("conditions"));
    }

    @Test
    void rejectsUnrecognizedEntryOptionsAndTrailingTokens() {
        assertNull(MythicDropSpec.parse("DIAMOND 1 1 unsupported-option"));
        assertNull(MythicDropSpec.parse(Map.of("item", "DIAMOND", "future-condition", "required")));
    }

    @Test
    void rejectsConditionValuesThatParseToNoRestriction() {
        for (String key : List.of("conditions", "Conditions", "DropIf", "NoDropIf")) {
            for (Object invalid : List.of(";", "|", ";|", ";?", "night;", "night;;raining", List.of("night", "|"),
                    List.of("night", ";?"))) {
                assertNull(MythicDropSpec.parse(Map.of("item", "DIAMOND", key, invalid)), key + ": " + invalid);
            }
            assertTrue(MythicDropSpec.parse(Map.of("item", "DIAMOND", key, List.of())).conditions().isEmpty());
        }
        assertEquals(List.of("night", "raining"), MythicDropSpec.parse(
                Map.of("item", "DIAMOND", "DropIf", "night;raining")).conditions());
    }

    @Test
    void rejectsAmbiguousOrBlankScalarFields() {
        assertNull(MythicDropSpec.parse(Map.of("item", "DIAMOND", "table", "other")));
        assertNull(MythicDropSpec.parse(Map.of("item", "DIAMOND", "Item", "EMERALD")));
        assertNull(MythicDropSpec.parse(Map.of("item", "DIAMOND", "chance", 1, "Chance", 0)));
        assertNull(MythicDropSpec.parse(Map.of("item", "DIAMOND", "amount", 1, "amount-min", 2)));
        assertNull(MythicDropSpec.parse(Map.of("item", "DIAMOND", "chance", "")));
        assertNull(MythicDropSpec.parse(Map.of("item", "DIAMOND", "amount", true)));
        var nullChance = new java.util.HashMap<String, Object>();
        nullChance.put("item", "DIAMOND");
        nullChance.put("chance", null);
        assertNull(MythicDropSpec.parse(nullChance));
        assertEquals(0.25, MythicDropSpec.parse(Map.of("item", "DIAMOND", "chance", "25%"))
                .chance(), 1e-9);
    }
}
