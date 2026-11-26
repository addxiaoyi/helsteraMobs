package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;
import static org.junit.jupiter.api.Assertions.*;

class ShieldBreakSpecTest {
    private int duration(String... arguments) {
        return ShieldBreakSpec.parse(List.of(arguments)).duration(UnaryOperator.identity(), new Random(1));
    }
    @Test void aliasesDefaultPriorityAndLastExactKeyMatchNativeLookup() {
        assertEquals("disable-shield", ShieldBreakSpec.canonical("ShieldBreak"));
        assertEquals("disable-shield", ShieldBreakSpec.canonical("DisableShield"));
        assertEquals(100, duration());
        assertEquals(20, duration("d=20"));
        assertEquals(30, duration("d=20", "duration=25", "DURATION=30"));
        assertEquals(100, duration("ignored=50"));
    }
    @Test void inclusiveRangesAndInvalidRangeFailuresAreNotClamped() {
        var range = ShieldBreakSpec.parse(List.of("d=1to2"));
        var values = new java.util.HashSet<Integer>();
        var random = new Random(3);
        for (int i=0;i<30;i++) values.add(range.duration(UnaryOperator.identity(), random));
        assertEquals(java.util.Set.of(1,2), values);
        assertEquals(7, duration("d=7-7"));
        assertEquals(-1, duration("d=-1"));
        assertThrows(IllegalArgumentException.class, () -> duration("d=3-1"));
        assertEquals(1, duration("d=9999999999999999"));
    }
    @Test void dynamicExtensionAndMalformedFallbackAreExplicit() {
        assertEquals(30, duration("d=\"30\""));
        assertEquals(2, duration("d=2.9"));
        assertEquals(1, duration("d="));
        assertEquals(1, duration("d=invalid"));
        assertEquals(1, duration("d=NaN"));
        var spec = ShieldBreakSpec.parse(List.of("duration=<target.l.y>"));
        assertEquals(75, spec.duration(text -> "75", new Random(1)));
        assertThrows(IllegalArgumentException.class, () -> ShieldBreakSpec.parse(List.of("30")));
    }
}
