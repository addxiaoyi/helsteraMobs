package dev.helstera.ai.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MythicTargeterSpecTest {
    @Test
    void parsesNamedAndPositionalArguments() {
        MythicTargeterSpec spec = MythicTargeterSpec.parse("@PlayersInRadius{r=8;limit=3;sort=NEAREST}");
        assertEquals("playersinradius", spec.name());
        assertEquals("8", spec.arguments().get("r"));
        assertEquals("3", spec.arguments().get("limit"));
        assertEquals("NEAREST", spec.arguments().get("sort"));
    }

    @Test
    void keepsCommaCoordinatesAndPositionalValues() {
        MythicTargeterSpec spec = MythicTargeterSpec.parse("@Location{1,2,3}");
        assertEquals(List.of("1", "2", "3"), spec.positional());
    }

    @Test
    void preservesSeparatorsInsideQuotedAndNestedValues() {
        MythicTargeterSpec spec = MythicTargeterSpec.parse(
                "@NearestConditionalTarget{conditions=[is-player;health{h=>5}],label='a;b',quoted=\"x\\\";y\"}");
        assertEquals("[is-player;health{h=>5}]", spec.arguments().get("conditions"));
        assertEquals("a;b", spec.arguments().get("label"));
        assertEquals("x\";y", spec.arguments().get("quoted"));
    }

    @Test
    void rejectsMalformedTargeters() {
        assertThrows(IllegalArgumentException.class, () -> MythicTargeterSpec.parse("@PlayersInRadius{r=8"));
        assertThrows(IllegalArgumentException.class,
                () -> MythicTargeterSpec.parse("@Target{filter=[players)}"));
        assertThrows(IllegalArgumentException.class,
                () -> MythicTargeterSpec.parse("@Target{filter='players}"));
    }
}
