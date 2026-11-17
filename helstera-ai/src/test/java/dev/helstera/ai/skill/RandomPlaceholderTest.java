package dev.helstera.ai.skill;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RandomPlaceholderTest {
    private String resolve(String key) { return Placeholders.resolve("<random." + key + ">", null); }

    @Test
    void integersUseInclusiveSignedBoundsAndRetainIntegerText() {
        assertEquals("-5", resolve("-5"));
        assertEquals("-5", resolve("-5to-5"));
        assertEquals("7", resolve("7-7"));
        assertEquals("2147483647", resolve("2147483647"));
        assertEquals("-2147483648", resolve("-2147483648"));
        for (String range : List.of("-2to2", "2to-2", "0-2", "-2147483648to2147483647")) {
            for (int i = 0; i < 100; i++) {
                String value = resolve(range);
                assertTrue(value.matches("-?\\d+"), value);
                int integer = Integer.parseInt(value);
                if (!range.contains("2147483647")) assertTrue(integer >= -2 && integer <= 2);
            }
        }
        // A singleton is also an inclusive endpoint; no probabilistic endpoint assertions.
        assertEquals("2", resolve("2to2"));
    }

    @Test
    void floatFamiliesMatchFloatArithmeticAndDistinctRoundingModes() {
        for (var entry : Map.of("float.0.0125to0.0125", Double.toString((double) 0.0125f),
                "float.-0.125to-0.125", "-0.125", "float{r=2}.-0.125to-0.125", "-0.13",
                "float.rounded.-0.125to-0.125", "-0.12", "float.rounded.1to1", "1.00",
                "float{r=0}.2.5to2.5", "3", "float{}.0.125to0.125", "0.13",
                "float.-1e-3to-1e-3", Double.toString((double) -1e-3f)).entrySet()) {
            assertEquals(entry.getValue(), resolve(entry.getKey()), entry.getKey());
        }
        for (String range : List.of("float.-1to1", "float.1to-1", "float.0-1")) {
            for (int i = 0; i < 100; i++) {
                double value = Double.parseDouble(resolve(range));
                assertTrue(value >= -1 && value <= 1, range + "=" + value);
                assertEquals((double) (float) value, value);
            }
        }
    }

    @Test
    void invalidRangesNeverLoseSignsOrFallBackToOne() {
        for (String key : List.of("-5-2", "1--2", "0.1to0.2", "float.-5-2", "float.bad", "float.NaN",
                "float.-3e38to3e38", "float.1e999to2", "2147483648", "1to", "percent{r=2}",
                "float.rounded{r=2}.1to1", "float{r=2;round=3}.1to2")) {
            assertEquals("<random." + key + ">", resolve(key), key);
        }
        for (String key : List.of("percent", "chance")) {
            double value = Double.parseDouble(resolve(key));
            assertTrue(value >= 0 && value < 1);
        }
    }
}
