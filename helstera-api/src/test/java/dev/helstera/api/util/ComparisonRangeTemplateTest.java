package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ComparisonRangeTemplateTest {
    @Test void comparisonPrefixAndNestedOptionsSurviveSubstitution() {
        assertEquals("<3", new ComparisonRangeTemplate("<<target.hp{r=0}>").resolve(token -> "3"));
        assertEquals("-4to4", new ComparisonRangeTemplate("-<caster.hp>to<target.hp>").resolve(token -> "4"));
        assertEquals("5to3", new ComparisonRangeTemplate("<skill.var.range>").resolve(token -> "5to3"));
        assertEquals("!=4e-2", new ComparisonRangeTemplate("!=<caster.hp>").resolve(token -> "4e-2"));
    }

    @Test void identicalRandomTokensAreEvaluatedOncePerResolution() {
        var count = new AtomicInteger();
        var template = new ComparisonRangeTemplate("<random.1to9>to<random.1to9>");
        assertEquals("1to1", template.resolve(token -> Integer.toString(count.incrementAndGet())));
        assertEquals("2to2", template.resolve(token -> Integer.toString(count.incrementAndGet())));
        assertEquals(2, count.get());
    }

    @Test void malformedTemplatesDoNotBecomeArithmeticOrDefaultRanges() {
        for (String raw : List.of("<caster.hp> + 1", "min(<caster.hp>, 5)", "<caster.hp>to", "<caster.hp>toNaN",
                "<caster.hp{r=1;r=2}>", "<caster.hp{unknown=1}>", "<caster.hp>to1e309", "<caster.hp", "<4to5"))
            assertThrows(IllegalArgumentException.class, () -> new ComparisonRangeTemplate(raw), raw);
    }

    @Test void unresolvedAndNonfiniteResultsAreRejected() {
        var template = new ComparisonRangeTemplate("<caster.hp>");
        for (String value : List.of("<caster.hp>", "", "NaN", "Infinity", "1e309", "4+1"))
            assertThrows(IllegalArgumentException.class, () -> template.resolve(token -> value), value);
        assertThrows(IllegalArgumentException.class, () -> template.resolve(token -> null));
        assertFalse(ComparisonRangeTemplate.hasPlaceholders("<4"));
    }
}
