package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EntityInteractionSpecTest {
    @Test void nativeNamesAndArgumentsHaveCanonicalRepresentations() {
        assertEquals("stop-using-item", EntityInteractionSpec.parse("releaseitem", List.of()).mechanic());
        assertEquals("stop-using-item", EntityInteractionSpec.canonical("StopUsingItem"));
        assertEquals("goat-ram", EntityInteractionSpec.parse("GoatRam", List.of()).mechanic());
        for (String alias : List.of("target", "to", "t", "location", "loc", "l", "of")) {
            var spec = EntityInteractionSpec.parse("matchrot", List.of(alias + "=\"@origin\""));
            assertEquals("match-rotation", spec.mechanic());
            assertEquals(List.of("target=@origin"), spec.arguments());
        }
    }

    @Test void validatesOnlyExplicitlySupportedSingleSourceSelectors() {
        for (String selector : List.of("@origin", "@source", "@self", "@caster", "@trigger", "@selflocation",
                "@casterlocation", "@selfeyelocation", "@triggerlocation", "@trackedlocation",
                "@location{x=1;y=2;z=3;yaw=450;pitch=120}", "@loc{l=1,2,3,-450,-120}")) {
            assertTrue(EntityInteractionSpec.validSource(selector), selector);
            assertEquals(selector, EntityInteractionSpec.parse("matchrotation", List.of("to=" + selector)).source());
        }
        for (String selector : List.of("origin", "@unknown", "@self{}", "@self{limit=1}", "@origin{typo=1}",
                "@targets", "@targetlocation", "@lot", "@locationsoftargets", "@self > @trigger",
                "@origin{conditions=[- true]}", "@location{yaw=NaN}", "@location{pitch=1e100}")) {
            assertFalse(EntityInteractionSpec.validSource(selector), selector);
            assertThrows(IllegalArgumentException.class,
                    () -> EntityInteractionSpec.parse("matchrot", List.of("to=" + selector)), selector);
        }
    }

    @Test void incompleteAndAmbiguousConfigurationRejectsInsteadOfDefaulting() {
        for (List<String> arguments : List.of(List.<String>of(), List.of("to="), List.of("@self"),
                List.of("to=@self", "target=@origin"), List.of("typo=@self"))) {
            assertThrows(IllegalArgumentException.class, () -> EntityInteractionSpec.parse("matchrot", arguments));
        }
        assertThrows(IllegalArgumentException.class, () -> EntityInteractionSpec.parse("stopusingitem", List.of("amount=1")));
        assertThrows(IllegalArgumentException.class, () -> EntityInteractionSpec.parse("goatram", List.of("damage=10")));
        // Native v1_21_R1 setpitch is a no-op. It must not silently become an actual rotation mutation.
        assertFalse(EntityInteractionSpec.supports("setpitch"));
        assertFalse(EntityInteractionSpec.supports("set-pitch"));
    }
}
