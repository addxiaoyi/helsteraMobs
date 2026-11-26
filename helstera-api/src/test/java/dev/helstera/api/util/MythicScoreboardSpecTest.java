package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.function.UnaryOperator;
import static org.junit.jupiter.api.Assertions.*;

class MythicScoreboardSpecTest {
    private MythicScoreboardSpec parse(String name, String... arguments) {
        return MythicScoreboardSpec.parse(name, List.of(arguments));
    }

    @Test void aliasesHaveDistinctScoreboardScopesAndDoNotCaptureInstanceScores() {
        for (var pair : List.of(List.of("sgs", "set-global-score"), List.of("sms", "set-mob-score"),
                List.of("sts", "set-target-score"), List.of("mgs", "modify-global-score"),
                List.of("mms", "modify-mob-score"), List.of("mts", "modify-target-score"),
                List.of("modifyScore", "modify-score"))) {
            assertEquals(pair.get(1), MythicScoreboardSpec.canonical(pair.getFirst().toUpperCase()));
            assertTrue(MythicScoreboardSpec.names().contains(pair.get(1)));
        }
        assertFalse(MythicScoreboardSpec.supports("setscore"));
        assertFalse(MythicScoreboardSpec.supports("score-add"));
        assertEquals(MythicScoreboardSpec.Scope.CASTER, parse("sms").scope());
        assertEquals(MythicScoreboardSpec.Scope.TARGET, parse("sts").scope());
        assertEquals(MythicScoreboardSpec.Scope.ENTRY, parse("modifyScore").scope());
    }

    @Test void nativeAliasOrderLastDuplicateAndEmptyValuesSurvive() {
        var spec = parse("mgs", "o=third", "obj=second", "objective=first", "OBJECTIVE=last",
                "value=bad", "v=8", "VALUE=2", "a=SET", "action=MULTIPLY", "unused=true");
        assertEquals("last", spec.objective());
        assertEquals(14, spec.apply(7, spec.operand(UnaryOperator.identity())));
        assertEquals("", parse("modifyScore", "entry=", "name=other", "objective=").entry());
        assertEquals("", parse("sgs").objective());
        assertEquals("dummy", parse("modifyScore").entry());
    }

    @Test void staticIntegersNeverEvaluateExpressionsOrPlaceholdersAndMalformedFallsBackToZero() {
        for (String name : List.of("sgs", "sms", "mgs", "mms")) {
            for (String value : List.of("bad", "1.5", "2147483648", "", "\"2\"", "<target.health>", "(1+2)"))
                assertEquals(0, parse(name, "value=" + value, "v=9").operand(ignored -> "6"));
            assertEquals(-7, parse(name, "v=-7").operand(ignored -> "6"));
        }
    }

    @Test void modificationDefaultsAndUnknownActionsMatchEachNativeFamily() {
        for (String name : List.of("mgs", "mms", "mts")) {
            for (String action : List.of("bad", "UNSET", "+", "\"SET\"", ""))
                assertEquals(9, parse(name, "value=2", "action=" + action).apply(7, 2));
            assertEquals(9, parse(name, "value=2").apply(7, 2));
        }
        assertEquals(2, parse("modifyScore", "value=2", "action=bad").apply(7, 2));
        assertEquals(7, parse("modifyScore", "action=unset").apply(7, 2));
        assertEquals(2, parse("sgs", "action=ADD").apply(7, 2));
    }

    @Test void targetSetIntegersSupportImplementedPlaceholdersMathAndFallbacks() {
        assertEquals(3, parse("sts", "value=<target.health>").operand(ignored -> "3.9"));
        assertEquals(-4, parse("sts", "value=<target.health>").operand(ignored -> "-3.9"));
        assertEquals(-3, parse("sts", "value=-3.9").operand(UnaryOperator.identity()));
        assertEquals(7, parse("sts", "value=(3+4)").operand(UnaryOperator.identity()));
        assertEquals(6, parse("sts", "value=\"6\"").operand(UnaryOperator.identity()));
        assertEquals(1, parse("sts", "value=bad").operand(UnaryOperator.identity()));
        assertEquals(0, parse("sts").operand(UnaryOperator.identity()));
    }

    @Test void stringIntegerModifiersRejectMalformedBeforeAnyWriteAndDoNotCalculateMath() {
        for (String name : List.of("mts", "modifyScore")) {
            for (String value : List.of("", "2.5", "(1+2)", "2147483648"))
                assertThrows(IllegalArgumentException.class,
                        () -> parse(name, "value=" + value).operand(UnaryOperator.identity()));
            assertEquals(8, parse(name, "value=<target.var.count>").operand(ignored -> "8"));
            assertEquals(6, parse(name, "value=\"6\"").operand(UnaryOperator.identity()));
        }
        assertThrows(IllegalArgumentException.class, () -> parse("mts").operand(UnaryOperator.identity()));
        assertEquals(0, parse("modifyScore").operand(UnaryOperator.identity()));
    }

    @Test void operationsUseNativeIntOverflowDivisionAndRemainder() {
        assertEquals(Integer.MIN_VALUE, parse("mgs").apply(Integer.MAX_VALUE, 1));
        assertEquals(Integer.MAX_VALUE, parse("mgs", "a=SUBTRACT").apply(Integer.MIN_VALUE, 1));
        assertEquals(-2, parse("mgs", "a=MULTIPLY").apply(Integer.MAX_VALUE, 2));
        assertEquals(-2, parse("mgs", "a=DIVIDE").apply(-7, 3));
        assertEquals(-1, parse("mgs", "a=MOD").apply(-7, 3));
        assertThrows(ArithmeticException.class, () -> parse("mgs", "a=DIVIDE").apply(7, 0));
        assertThrows(ArithmeticException.class, () -> parse("mgs", "a=MOD").apply(7, 0));
    }

    @Test void rawIntegerRangesTakePriorityOverSubtractionAndIncludeBothEndpoints() {
        for (String raw : List.of("3to7", "3-7")) {
            var spec = parse("sts", "v=" + raw);
            assertEquals(new MythicScoreboardSpec.IntegerRange(3, 7), spec.range());
            assertEquals(3, spec.range().sample(bound -> { assertEquals(5, bound); return 0; }));
            assertEquals(7, spec.range().sample(bound -> bound - 1));
        }
        var negative = parse("sts", "v=-3to7");
        assertEquals(-3, negative.range().sample(bound -> { assertEquals(11, bound); return 0; }));
        assertEquals(7, negative.range().sample(bound -> bound - 1));
        assertEquals(3, parse("sts", "v=3to3").operand(UnaryOperator.identity()));
        assertEquals(3, parse("sts", "v=3-3").operand(UnaryOperator.identity()));
        // A leading minus disables the hyphen-range constructor branch, then integer parsing fails.
        assertEquals(1, parse("sts", "v=-3-7").operand(UnaryOperator.identity()));
        assertNull(parse("sts", "v=\"3-7\"").range());
        assertEquals(-4, parse("sts", "v=\"3-7\"").operand(UnaryOperator.identity()));
        assertEquals(-4, parse("sts", "v=(3-7)").operand(UnaryOperator.identity()));
        assertNull(parse("sts", "v=<target.name>").range());
        assertEquals(1, parse("sts", "v=<target.name>").operand(ignored -> "3-7"));
    }

    @Test void rangeBoundsPreserveNativeOverflowAndMalformedConstructorBehavior() {
        assertEquals(1, parse("sts", "v=2147483648to2147483649").operand(UnaryOperator.identity()));
        assertThrows(IllegalArgumentException.class, () -> parse("sts", "v=7to3").operand(UnaryOperator.identity()));
        assertThrows(IllegalArgumentException.class, () -> parse("sts", "v=0to2147483647").operand(UnaryOperator.identity()));
        assertEquals(0, parse("sgs", "v=3to7").operand(UnaryOperator.identity()));
        assertThrows(IllegalArgumentException.class, () -> parse("mts", "v=3to7").operand(UnaryOperator.identity()));
    }
}
