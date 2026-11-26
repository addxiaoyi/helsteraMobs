package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EntitySetterSpecTest {
    @Test void nativeDefaultsAndAliases() {
        assertEquals("OPEN", EntitySetterSpec.parse("malus", List.of()).value("malus"));
        assertEquals("0.0", EntitySetterSpec.parse("setMalus", List.of()).value("value"));
        assertFalse(EntitySetterSpec.parse("setleasher", List.of()).flag("remove"));
        assertTrue(EntitySetterSpec.parse("setchunkforceloaded", List.of()).flag("loaded"));
        assertNull(EntitySetterSpec.parse("setText", List.of()).value("text"));
        assertEquals("AIR", EntitySetterSpec.parse("setDisplayEntityItem", List.of()).value("item"));
        assertEquals("1", EntitySetterSpec.parse("interactionSize", List.of()).value("width"));
        assertTrue(EntitySetterSpec.parse("setCanJoinRaid", List.of()).flag("bool"));
        assertTrue(EntitySetterSpec.parse("setRaiderLeader", List.of()).flag("bool"));
    }

    @Test void malformedInputsNeverBroadenOrDefault() {
        for (List<String> args : List.of(List.of("type=FIRE"), List.of("value=NaN"),
                List.of("value=1e100"), List.of("value=<caster.level>"),
                List.of("malus=WATER", "type=LAVA"), List.of("v=1")))
            assertThrows(IllegalArgumentException.class, () -> EntitySetterSpec.parse("malus", args));
        assertThrows(IllegalArgumentException.class, () -> EntitySetterSpec.parse("setleasher", List.of("b=maybe")));
        assertThrows(IllegalArgumentException.class, () -> EntitySetterSpec.parse("setText", List.of("text=a", "t=b")));
        assertThrows(IllegalArgumentException.class, () -> EntitySetterSpec.parse("setDisplayEntityItem", List.of("i=<target.name>")));
    }

    @Test void interactionDimensionsAreFiniteFloatsWithRecipientPlaceholders() {
        assertEquals("<target.l.y>", EntitySetterSpec.parse("interactionSize",
                List.of("h=<target.l.y>", "w=-2")).value("height"));
        assertEquals("0", EntitySetterSpec.parse("interactionSize", List.of("w=0")).value("width"));
        assertThrows(IllegalArgumentException.class, () -> EntitySetterSpec.parse("interactionSize", List.of("w=Infinity")));
        assertThrows(IllegalArgumentException.class, () -> EntitySetterSpec.parse("interactionSize", List.of("w=1e100")));
    }

    @Test void outerSelectorNameGuardCoversEveryChainSegment() {
        for (String selector : List.of("@self", "@trigger", "@targets", "@origin", "@playersinradius{r=4} > @nearest"))
            assertDoesNotThrow(() -> EntitySetterSpec.requireKnownTargeter(selector));
        for (String selector : List.of("@unknown", "@self > @unknown", "@", "@self >", "@origin{unknown=1}"))
            assertThrows(IllegalArgumentException.class, () -> EntitySetterSpec.requireKnownTargeter(selector));
    }

    @Test void patrolRequiresAValidatedExplicitNestedLocation() {
        assertEquals("@origin", EntitySetterSpec.parse("setRaiderBlock", List.of("l=@origin")).value("location"));
        for (List<String> args : List.of(List.<String>of(), List.of("l=origin"), List.of("l=@imaginary"),
                List.of("l=@origin{unknown=1}"), List.of("l=@origin{conditions=[- true]}")))
            assertThrows(IllegalArgumentException.class, () -> EntitySetterSpec.parse("setRaiderBlock", args));
    }
}
