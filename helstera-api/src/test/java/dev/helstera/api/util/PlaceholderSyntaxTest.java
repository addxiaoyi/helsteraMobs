package dev.helstera.api.util;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaceholderSyntaxTest {
    @Test
    void parsesRoundingAliasesDefaultAndSegmentConfiguration() {
        assertNull(PlaceholderSyntax.parse("caster.hp").rounding());
        assertEquals(2, PlaceholderSyntax.parse("caster.hp{}").rounding());
        for (String option : List.of("rounding", "round", "r", "R")) {
            var syntax = PlaceholderSyntax.parse("random.float{" + option + "='3'}.-1e-3to+1e-3");
            assertEquals("random.float.-1e-3to+1e-3", syntax.key());
            assertEquals(3, syntax.rounding());
        }
        assertEquals(-2147483648, PlaceholderSyntax.parse("c.h{r=-2147483648}").rounding());
        assertEquals(340, PlaceholderSyntax.parse("c.h{r=340}").rounding());
    }

    @Test
    void rejectsAmbiguousMalformedAndUnboundedConfigurations() {
        for (String invalid : List.of("", "{r=2}caster.hp", "caster.{r=2}hp", "caster.hp{r=2}tail",
                "caster.hp{r=2}{r=3}", "caster.hp{r=2", "caster.hp}", "caster.hp{r=}",
                "caster.hp{rounding=NaN}", "caster.hp{r=2.5}", "caster.hp{r=341}",
                "caster.hp{r=2147483648}", "caster.hp{foo=2}", "caster.hp{r=2;round=3}",
                "caster.hp{r=2,}", "caster.hp{r=2=3}", "caster.hp{r='2\"}")) {
            assertThrows(IllegalArgumentException.class, () -> PlaceholderSyntax.parse(invalid), invalid);
            assertFalse(PlaceholderSyntax.isToken("<" + invalid + ">"), invalid);
        }
        assertFalse(PlaceholderSyntax.isToken(null));
        assertFalse(PlaceholderSyntax.isToken("<caster.hp> extra"));
        assertTrue(PlaceholderSyntax.isToken("<caster.hp{r=2}>"));
        assertEquals(-1, PlaceholderSyntax.argumentEquals(" <caster.hp{r=2}>"));
        assertEquals(1, PlaceholderSyntax.argumentEquals("a=<caster.hp{r=2}>"));
    }

    @Test
    void potionAndHealthContractsPreserveInlineOptionsAndValidateResolvedValues() {
        var potion = PotionSpec.parse(List.of("SPEED", "<caster.mhp{r=0}>", "<random.1to1>"));
        assertEquals("<caster.mhp{r=0}>", potion.duration());
        assertEquals(20, potion.resolve(raw -> switch (raw) {
            case "<caster.mhp{r=0}>" -> "20";
            case "<random.1to1>" -> "1";
            default -> raw;
        }).orElseThrow().duration());
        assertTrue(potion.resolve(raw -> raw.startsWith("<") ? "NaN" : raw).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> PotionSpec.parse(List.of("SPEED", "<caster.hp{r=2;foo=3}>")));
        assertThrows(IllegalArgumentException.class,
                () -> NumericMechanicSpec.parse("heal", List.of("<caster.hp{r=999}>")));
        assertThrows(IllegalArgumentException.class,
                () -> PercentHealthSpec.parse("healpercent", List.of("<caster.hp{r=2;foo=3}>")));
    }
}
