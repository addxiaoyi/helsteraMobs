package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class MythicGeneratedLootSpecTest {
    @Test void aliasesRawPriorityLastKeyAndNativeBooleanDefaults() {
        for (String name : List.of("giveitemfromtarget", "givefromtarget", "giveitemsfromtarget", "itemgivefromtarget"))
            assertEquals("give-item-from-target", MythicGeneratedLootSpec.canonical(name.toUpperCase()));
        var spec = MythicGeneratedLootSpec.parse(List.of("i=APPLE", "items=DIAMOND", "ITEMS=RewardTable",
                "fl=false", "dofakelooting=true"));
        assertEquals("RewardTable", spec.items());
        assertTrue(spec.fakeLooting());
        assertTrue(MythicGeneratedLootSpec.parse(List.of("items=STONE")).fakeLooting());
        assertFalse(MythicGeneratedLootSpec.parse(List.of("items=STONE", "fl=\"true\"")).fakeLooting());
    }

    @Test void onlyDoubleQuotedItemsDecodeSpecialCharactersBeforeCommaSplitting() {
        var spec = MythicGeneratedLootSpec.parse(List.of("i=\"DIAMOND 2<&cm>exp 3\""));
        assertEquals("\"DIAMOND 2<&cm>exp 3\"", spec.items());
        assertEquals(List.of("DIAMOND 2", "exp 3"), spec.inlineDrops());
        assertEquals(List.of("'DIAMOND<&cm>exp'"), MythicGeneratedLootSpec.parse(List.of("i='DIAMOND<&cm>exp'")).inlineDrops());
        assertEquals(List.of("DIAMOND<&cm>exp"), MythicGeneratedLootSpec.parse(List.of("i=DIAMOND<&cm>exp")).inlineDrops());
    }

    @Test void missingAndEmptyPrimaryFieldNeverFallBackToAnotherAlias() {
        for (List<String> fields : List.of(List.<String>of(), List.of("items=", "i=DIAMOND"), List.of("DIAMOND")))
            assertThrows(IllegalArgumentException.class, () -> MythicGeneratedLootSpec.parse(fields));
    }

    @Test void fractionalAmountsAreRetainedAndRangesAreUpperExclusive() {
        var fractional = MythicRewardEntry.parse("DIAMOND 2.75 100%");
        assertEquals(2.75, fractional.minimum());
        assertEquals(1, fractional.chance());
        var range = MythicRewardEntry.parse("exp 2to4 0.5");
        var random = new Random(123);
        for (int i = 0; i < 100; i++) { double value = range.amount(random); assertTrue(value >= 2 && value < 4); }
        assertEquals(range, MythicRewardEntry.parse("exp 2-4 50%"));
        assertEquals(.25, MythicRewardEntry.parse("DIAMOND .25").chance());
        assertEquals(1, MythicRewardEntry.parse("DIAMOND .25").minimum());
        assertEquals(.25, MythicRewardEntry.parse("exp .25 1").minimum());
    }

    @Test void commandAliasesAndLiteralPayloadSurviveDropParsing() {
        var entry = MythicRewardEntry.parse("command{c='scoreboard<&sp>players<&sp>add<&sp><caster.name><&sp>rewards<&sp><drop.amount>';ac=true;op=true} 4 1");
        assertEquals("scoreboard players add <caster.name> rewards <drop.amount>", entry.command().text());
        assertTrue(entry.command().asCaster());
        assertTrue(entry.command().asOperator());
        assertFalse(entry.command().asTrigger());
        assertEquals(4, entry.minimum());
    }

    @Test void invalidAmountsProvidersAndMalformedCommandsAreRejected() {
        for (String raw : List.of("DIAMOND NaN", "DIAMOND 4-2", "DIAMOND -2", "DIAMOND 1 NaN",
                "DIAMOND 1 -0.2", "mmoi:SWORD", "command{}", "command{c='broken}", "DIAMOND 1 1 extra"))
            assertThrows(IllegalArgumentException.class, () -> MythicRewardEntry.parse(raw), raw);
    }
}
