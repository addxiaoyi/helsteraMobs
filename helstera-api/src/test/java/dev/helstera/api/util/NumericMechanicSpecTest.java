package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NumericMechanicSpecTest {
    @Test
    void reordersNamedFieldsAndKeepsOmittedDefaults() {
        assertEquals(List.of("2", "0.5"), NumericMechanicSpec.parse("knockback",
                List.of("vertical=0.5", "power=2")).arguments());
        assertEquals(List.of("1", "0.5"), NumericMechanicSpec.parse("knockback", List.of("y=0.5")).arguments());
        assertEquals(List.of("2", "0.4"), NumericMechanicSpec.parse("knockback", List.of("power=2")).arguments());
        assertEquals(List.of("6", "2"), NumericMechanicSpec.parse("pull", List.of("power=2")).arguments());
        assertEquals(List.of("3", "4"), NumericMechanicSpec.parse("damage-melee", List.of("r=4")).arguments());
        assertEquals(List.of("1"), NumericMechanicSpec.parse("force-pull", List.of()).arguments());
        assertEquals(List.of("1", "0.2"), NumericMechanicSpec.parse("force-pull", List.of("yv=0.2")).arguments());
    }

    @Test
    void mixedArgumentsUseFixedPositionsAndRejectDuplicates() {
        assertEquals(List.of("2", "0.5"), NumericMechanicSpec.parse("knockback", List.of("2", "y=0.5")).arguments());
        for (List<String> args : List.of(List.of("power=2", "3"), List.of("y=1", "vertical=2"),
                List.of("1", "2", "3"), List.of("power=1", "power=2"))) {
            assertThrows(IllegalArgumentException.class, () -> NumericMechanicSpec.parse("knockback", args));
        }
    }

    @Test
    void rejectsUnknownEmptyNonFiniteAndMalformedValues() {
        for (String name : List.of("pull-targets", "damage-melee-targets", "heal-self-targets", "freeze-targets")) {
            assertFalse(NumericMechanicSpec.supports(name), name);
        }
        for (String argument : List.of("power=NaN", "power=Infinity", "power=", "", "=2", "power=<bad",
                "power=foo", "unknown=1", "power=1e400")) {
            assertThrows(IllegalArgumentException.class, () -> NumericMechanicSpec.parse("knockback", List.of(argument)), argument);
        }
    }

    @Test
    void resolvesDynamicValuesOnceBeforeAnyMutationAndFailsClosed() {
        var spec = NumericMechanicSpec.parse("knockback", List.of("power=<skill.var.power>", "y=0.2"));
        var calls = new AtomicInteger();
        assertEquals(List.of("2", "0.2"), spec.resolve(value -> { calls.incrementAndGet(); return "2"; }).orElseThrow());
        assertEquals(1, calls.get());
        for (String invalid : List.of("<missing>", "NaN", "Infinity", "")) {
            assertTrue(spec.resolve(value -> invalid).isEmpty(), invalid);
        }
    }

    @Test
    void damageTagsRemainNamedAndDoNotOccupyNumericSlots() {
        assertEquals(List.of("0", "tags=FIRE", "rawtags=ice"), NumericMechanicSpec.parse("damage",
                List.of("tags=FIRE", "rawtags=ice")).arguments());
        assertEquals(List.of("3", "tags=FIRE"), NumericMechanicSpec.parse("damage",
                List.of("tags=FIRE", "a=3")).arguments());
        assertThrows(IllegalArgumentException.class, () -> NumericMechanicSpec.parse("damage", List.of("tags=FIRE", "tag=ICE")));
    }

    @Test
    void documentsHistoricalImportDefaultsWithoutClaimingUpstreamParity() {
        assertEquals(List.of("0"), NumericMechanicSpec.parseImported("damage-percent", List.of()).arguments());
        assertEquals(List.of("10"), NumericMechanicSpec.parse("damage-percent", List.of()).arguments());
        assertEquals(List.of("0"), NumericMechanicSpec.parseImported("modify-speed", List.of()).arguments());
        assertEquals(List.of("1"), NumericMechanicSpec.parse("modify-speed", List.of()).arguments());
    }
}
