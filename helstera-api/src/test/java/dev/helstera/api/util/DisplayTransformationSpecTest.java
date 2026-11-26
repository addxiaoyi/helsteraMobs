package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class DisplayTransformationSpecTest {
    @Test void nativeDefaultsAliasPriorityAndLastExactKeyAreRetained() {
        var defaults = DisplayTransformationSpec.parse(List.of());
        assertEquals(DisplayTransformationSpec.Action.SET, defaults.action());
        assertEquals(DisplayTransformationSpec.Type.TRANSLATION, defaults.type());
        assertEquals(List.of("0", "0", "0"), defaults.values());
        var chosen = DisplayTransformationSpec.parse(List.of("a=DIVIDE", "action=ADD", "ACTION=MULTIPLY",
                "tt=SCALE", "type=RIGHT_ROTATION", "transformation=LEFT_ROTATION", "transformationtype=SCALE",
                "val=9,9,9", "value=2,3,4"));
        assertEquals(DisplayTransformationSpec.Action.MULTIPLY, chosen.action());
        assertEquals(DisplayTransformationSpec.Type.SCALE, chosen.type());
        assertEquals(List.of("2", "3", "4"), chosen.values());
        assertEquals(defaults, DisplayTransformationSpec.parse(List.of("a=invalid", "type=invalid")));
    }

    @Test void rangesDynamicValuesAndMalformedNumbersFollowTheDocumentedBoundary() {
        var spec = DisplayTransformationSpec.parse(List.of("value=\"2to2,<target.l.y>,oops\""));
        assertArrayEquals(new float[]{2, 14, 1}, spec.resolve(s -> s.replace("<target.l.y>", "14"), new Random(1)));
        var ranged = DisplayTransformationSpec.parse(List.of("val=-2to2,3-5,2,9"));
        for (int i = 0; i < 20; i++) {
            float[] result = ranged.resolve(s -> s, new Random(i));
            assertTrue(result[0] >= -2 && result[0] < 2);
            assertTrue(result[1] >= 3 && result[1] < 5);
            assertEquals(4, result.length); // Native resolves extras but uses the required prefix.
        }
    }

    @Test void insufficientOrNonfiniteComponentsRejectBeforeMutation() {
        assertThrows(IllegalArgumentException.class, () -> DisplayTransformationSpec.parse(List.of("type=LEFT_ROTATION")));
        assertThrows(IllegalArgumentException.class, () -> DisplayTransformationSpec.parse(List.of("value=1,2")));
        assertThrows(IllegalArgumentException.class, () -> DisplayTransformationSpec.parse(List.of("1,2,3")));
        assertThrows(IllegalArgumentException.class, () -> DisplayTransformationSpec.parse(List.of("value=1,NaN,3")));
        var spec = DisplayTransformationSpec.parse(List.of("value=1,<target.l.y>,3"));
        assertThrows(IllegalArgumentException.class, () -> spec.resolve(s -> s.replace("<target.l.y>", "Infinity"), new Random(1)));
    }
}
