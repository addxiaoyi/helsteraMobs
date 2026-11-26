package dev.helstera.api.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MythicVariableArithmeticTest {
    @Test
    void nativeAliasPriorityAndLastRepeatedKeyWin() {
        var options = MythicVariableArithmetic.parse(List.of("a=10", "amount=9", "v=8", "val=7", "value=1", "VALUE=2",
                "expire=80", "d=40", "duration=bad", "DURATION=1200"));
        assertEquals("2", options.amount());
        assertEquals(1200, options.duration().ticks());
        assertEquals("0", MythicVariableArithmetic.parse(List.of()).amount());
        assertEquals(0, MythicVariableArithmetic.parse(List.of("duration=bad", "d=40")).duration().ticks());
    }

    @Test
    void integerTruncationSaturationAndOverflowMatchNativeSubtraction() {
        assertEquals(4, MythicVariableArithmetic.subtract(7, 3.9f));
        assertEquals(10, MythicVariableArithmetic.subtract(7, -3.9f));
        assertEquals(-2147483640, MythicVariableArithmetic.subtract(7, Float.MAX_VALUE));
        assertEquals(-2147483641, MythicVariableArithmetic.subtract(7, -Float.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, MythicVariableArithmetic.subtract(Integer.MIN_VALUE, 1));
    }

    @Test
    void internallySupportedQuotedOperandsDoNotFallBackToThePublicNativePremiumGate() {
        assertEquals("2.5", MythicVariableArithmetic.parse(List.of("value=\"2.5\"")).amount());
        assertEquals("<target.health>", MythicVariableArithmetic.parse(List.of("value='<target.health>'")).amount());
    }

    @Test
    void floatingPointDifferencePreservesTypeAndRejectsNonFiniteOrNonNativeTypes() {
        assertEquals(0.75f, MythicVariableArithmetic.subtract(1.25f, 0.5f));
        assertNull(MythicVariableArithmetic.subtract(1.25, 0.5f));
        assertNull(MythicVariableArithmetic.subtract("1.25", 0.5f));
        assertNull(MythicVariableArithmetic.subtract(null, 0.5f));
        assertNull(MythicVariableArithmetic.subtract(7, Float.NaN));
        assertNull(MythicVariableArithmetic.subtract(Float.MAX_VALUE, -Float.MAX_VALUE));
    }
}
