package dev.helstera.api.util;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MythicVariableMathTest {
    @Test
    void defaultsToXAndIgnoresExtensionOptionsAndSave() {
        var parsed = MythicVariableMath.parse(List.of("var=skill.Counter", "op=*", "value=7", "save=false"));
        assertEquals("x", parsed.equation());
        assertEquals(new VariableDuration(0, false), parsed.duration());
        assertEquals(parsed, MythicVariableMath.parse(null));
        for (String alias : List.of("equation", "eq", "e", "EqUaTiOn")) {
            assertTrue(MythicVariableMath.isEquationArgument(alias));
        }
        assertFalse(MythicVariableMath.isEquationArgument("duration"));
        assertFalse(MythicVariableMath.isEquationArgument(null));
    }

    @Test
    void selectsEquationAliasesInNativeOrderAndDuplicatesUseTheirLastValue() {
        assertEquals("x+1", MythicVariableMath.parse(List.of("e=x+3", "eq=x+2", "equation=x+1")).equation());
        assertEquals("x+1", MythicVariableMath.parse(List.of("equation=x+1", "eq=x+2", "e=x+3")).equation());
        assertEquals("x+2", MythicVariableMath.parse(List.of("e=x+3", "eq=x+2")).equation());
        assertEquals("x+3", MythicVariableMath.parse(List.of("e=x+3")).equation());
        assertEquals("x+4", MythicVariableMath.parse(List.of("equation=", "EQUATION=x+4")).equation());
        assertEquals("x+4", MythicVariableMath.parse(List.of("eq=bad", "EQ=x+4")).equation());
    }

    @Test
    void preservesInvalidOrEmptySelectedEquationsInsteadOfFallingBack() {
        for (List<String> arguments : List.of(List.of("equation="), List.of("eq= "),
                List.of("equation=x+1", "EQUATION="), List.of("eq=x+2", "equation="),
                List.of("eq=x+2", "equation=bad"), List.of("equation=\"x+1'"),
                List.of("e='x+1\""), List.of("equation=x+"), List.of("equation=\"\""),
                List.of("equation=''"))) {
            assertThrows(IllegalArgumentException.class, () -> MythicVariableMath.parse(arguments), arguments.toString());
        }
    }

    @Test
    void stripsOneMatchingQuotePairFromEquationButRetainsRawDurationText() {
        assertEquals(new MythicVariableMath("x + 1", new VariableDuration(0, false)),
                MythicVariableMath.parse(List.of("equation=\"x + 1\"")));
        assertEquals(new MythicVariableMath("x + 1", new VariableDuration(0, false)),
                MythicVariableMath.parse(List.of("eq='x + 1'")));
        assertEquals(new MythicVariableMath("40", new VariableDuration(0, true)),
                MythicVariableMath.parse(List.of("e=\"40\"")));
        assertEquals(new MythicVariableMath("40", new VariableDuration(0, true)),
                MythicVariableMath.parse(List.of("e='40'")));
        assertThrows(IllegalArgumentException.class,
                () -> MythicVariableMath.parse(List.of("equation=\"'x + 1'\"")));
    }

    @Test
    void eCanSupplyBothEquationAndDurationIndependently() {
        assertEquals(new MythicVariableMath("x+1", new VariableDuration(0, true)),
                MythicVariableMath.parse(List.of("e=x+1")));
        assertEquals(new MythicVariableMath("40", new VariableDuration(40, true)),
                MythicVariableMath.parse(List.of("e=40")));
        assertEquals(new MythicVariableMath("x+2", new VariableDuration(40, true)),
                MythicVariableMath.parse(List.of("eq=x+2", "e=40")));
        assertEquals(new MythicVariableMath("x+2", new VariableDuration(5, true)),
                MythicVariableMath.parse(List.of("eq=x+2", "e=40", "duration=5")));
    }

    @Test
    void durationUsesAliasPriorityAndLastWinsWithNativeMalformedFallback() {
        assertEquals(new VariableDuration(4, true), MythicVariableMath.parse(
                List.of("equation=x", "e=1", "expire=2", "d=3", "duration=4")).duration());
        assertEquals(new VariableDuration(7, true), MythicVariableMath.parse(
                List.of("equation=x", "duration=4", "DURATION=7", "d=9")).duration());
        assertEquals(new VariableDuration(-1, true), MythicVariableMath.parse(
                List.of("equation=x", "expire=-1")).duration());
        for (String duration : List.of("", "bad", "1.5", "20t", "\"40\"", "9223372036854775808")) {
            assertEquals(new VariableDuration(0, true), MythicVariableMath.parse(
                    List.of("equation=x", "duration=" + duration, "d=40")).duration(), duration);
        }
        assertEquals(new VariableDuration(0, true), MythicVariableMath.parse(
                List.of("equation=x", "duration=4", "DURATION=")).duration());
    }

    @Test
    void rejectsRepresentableTickCountsThatOverflowMillisecondConversion() {
        for (long ticks : List.of(Long.MAX_VALUE, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException.class,
                    () -> MythicVariableMath.parse(List.of("equation=x", "duration=" + ticks)));
        }
    }
}
