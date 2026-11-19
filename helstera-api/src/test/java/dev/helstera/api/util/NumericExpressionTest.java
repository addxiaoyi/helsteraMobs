package dev.helstera.api.util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NumericExpressionTest {
    @Test
    void evaluatesNativePrecedenceFunctionsAndImplicitMultiplication() {
        var cases = Map.ofEntries(Map.entry("2+3*4", 14.0), Map.entry("2^3^2", 512.0),
                Map.entry("-2^2", -4.0), Map.entry("(-2)^2", 4.0), Map.entry("2(3+4)", 14.0),
                Map.entry("max(2,min(8,3))", 3.0), Map.entry("atan2(0,-1)", Math.PI),
                Map.entry("sin(pi/2)+sqrt(9)", 4.0), Map.entry("(7%4)", 3.0),
                Map.entry("1e-3+2E-3", 0.003), Map.entry("(pi<e>0)", 0.0));
        cases.forEach((source, expected) -> {
            NumericExpression.validate(source);
            assertEquals(expected, NumericExpression.resolve(source, token -> fail(token)), 1e-12, source);
        });
    }

    @Test
    void comparisonsReturnNumericBooleansAndShareNativePrecedence() {
        for (String source : List.of("(2<3)", "(2<=2)", "(3>2)", "(2>=2)", "(2==2)", "(1+2==3)",
                "(2>1<2)", "(1==1)*5==5")) {
            assertEquals(1, NumericExpression.resolve(source, token -> fail(token)), source);
        }
        assertEquals(0, NumericExpression.resolve("(3<2)", token -> fail(token)));
    }

    @Test
    void preservesLiteralAndSinglePlaceholderTextAndResolvesRepeatedTokensOnce() {
        assertEquals("2", NumericExpression.resolveText("2", token -> fail(token)));
        assertEquals("3.125", NumericExpression.resolveText("<target.hp>", token -> "3.125"));
        var calls = new AtomicInteger();
        assertEquals(12.52, NumericExpression.resolve("2*(<target.hp{r=2}>+<target.hp{r=2}>)", token -> {
            assertEquals("<target.hp{r=2}>", token);
            calls.incrementAndGet();
            return "3.13";
        }));
        assertEquals(1, calls.get());
        assertEquals(-4, NumericExpression.resolve("<skill.var.value>^2", token -> "-2"));
        assertEquals(4, NumericExpression.resolve("(<skill.var.value>)^2", token -> "-2"));
        assertEquals(7, NumericExpression.resolve("<skill.var.value>+1", token -> "2*3"));
    }

    @Test
    void distinguishesSyntaxRejectionFromRuntimeDomainErrors() {
        for (String source : List.of("foo", "1+", "(1+2", "max(1)", "min(1,2,3)", "2;3", "1==1",
                "7%4", "=1+2", "eval(1+2)", "<bad", "NaN", "Infinity", "1e999")) {
            assertThrows(IllegalArgumentException.class, () -> NumericExpression.validate(source), source);
        }
        for (String source : List.of("1/0", "sqrt(-1)", "exp(10000)")) {
            NumericExpression.validate(source);
            assertThrows(RuntimeException.class, () -> NumericExpression.resolve(source, token -> fail(token)), source);
        }
        for (String value : List.of("<missing>", "NaN", "Infinity", "", "1/0")) {
            assertThrows(RuntimeException.class, () -> NumericExpression.resolve("<skill.var.value>", token -> value));
        }
    }

    @Test
    void sharedMechanicSpecsValidateExpressionsAndFailClosedBeforeAnyMutation() {
        var numeric = NumericMechanicSpec.parse("knockback", List.of("power=(<target.hp>+1)/2", "y=(1/0)"));
        assertTrue(numeric.dynamic());
        assertTrue(numeric.resolve(token -> "3").isEmpty());
        var percent = PercentHealthSpec.parse("healpercent", List.of("m=(<target.hp>/20)"));
        assertEquals(0.25, percent.resolve(token -> "5").orElseThrow().fraction());
        assertTrue(percent.resolve(token -> "NaN").isEmpty());
        var potion = PotionSpec.parse(List.of("d=(20+<target.hp>)", "l=(<target.hp>/2)"));
        assertEquals(24, potion.resolve(token -> token.startsWith("<") ? "4" : token).orElseThrow().duration());
        assertTrue(potion.resolve(token -> token.startsWith("<") ? "3" : token).isEmpty());
    }

    @Test
    void randomUsesNativeContinuousFormulaWithinBounds() {
        assertEquals(0.125, NumericExpression.resolve("random(0.125,0.125)", token -> fail(token)));
        for (int i = 0; i < 64; i++) {
            double value = NumericExpression.resolve("random(-2,3)", token -> fail(token));
            assertTrue(value >= -2 && value < 3);
        }
    }
}
