package dev.helstera.api.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MythicVariableLocationTest {
    private static final List<String> ALIASES = List.of("value", "val", "v", "amount", "a");

    @Test
    void omittedValueUsesCasterWithoutReadingTheMutationReference() {
        assertEquals("@self", MythicVariableLocation.parse(null).selector());
        assertEquals("@self", MythicVariableLocation.parse(List.of()).selector());
        assertEquals("@self", MythicVariableLocation.parse(
                List.of("var=target.Point", "scope=target", "duration=40", "save=true")).selector());
    }

    @Test
    void canonicalizesOnlySupportedStaticSelectorAliases() {
        Map<String, List<String>> selectors = Map.of(
                "@self", List.of("@self", "@caster", "@boss", "@mob", "@selflocation",
                        "@casterlocation", "@bosslocation", "@moblocation"),
                "@origin", List.of("@origin", "@source"),
                "@trigger", List.of("@trigger", "@triggerlocation"),
                "@target", List.of("@target", "@T"),
                "@targetlocation", List.of("@targetlocation", "@targetLoc", "@TL"));
        selectors.forEach((expected, spellings) -> spellings.forEach(selector -> {
            assertEquals(expected, MythicVariableLocation.parse(List.of("value=" + selector)).selector());
            assertEquals(expected, MythicVariableLocation.parse(
                    List.of("VALUE= " + selector.toUpperCase(java.util.Locale.ROOT) + " ")).selector());
        }));
    }

    @Test
    void valueAliasesUseNativePriorityRegardlessOfSourceOrder() {
        for (int first = 0; first < ALIASES.size(); first++) {
            var arguments = new ArrayList<String>();
            for (int index = ALIASES.size() - 1; index >= first; index--) {
                arguments.add(ALIASES.get(index) + "=" + (index == first ? "@origin" : "@trigger"));
            }
            assertEquals("@origin", MythicVariableLocation.parse(arguments).selector());
        }
        assertEquals("@origin", MythicVariableLocation.parse(
                List.of("value=@origin", "val=@trigger", "a=@self")).selector());
    }

    @Test
    void repeatedKeysUseTheLastValueIncludingEmptyAndUnsupportedValues() {
        assertEquals("@origin", MythicVariableLocation.parse(
                List.of("value=unknown", "VALUE=@source")).selector());
        assertEquals("@trigger", MythicVariableLocation.parse(
                List.of("a=", "A=@triggerlocation")).selector());
        for (List<String> arguments : List.of(
                List.of("value=@self", "VALUE="),
                List.of("value=@self", "VALUE=@unknown"),
                List.of("value=", "val=@origin"),
                List.of("value=\"@self\"", "v=@origin"))) {
            assertThrows(IllegalArgumentException.class, () -> MythicVariableLocation.parse(arguments));
        }
    }

    @Test
    void refusesQuotesDynamicValuesCoordinatesAndSelectorOptions() {
        for (String selector : List.of("", "self", "@targets", "@target{}", "@target{limit=1}",
                "@targetlocation{maxdistance=32}", "@TL{ignoretransparent=false}",
                "@self{}", "@self{limit=1}", "@origin{y=1}", "@trigger > @self",
                "\"@self\"", "'@origin'", "<skill.var.Point>", "1,2,3",
                "@location{x=1;y=2;z=3}", "@variablelocation{var=skill.Point}")) {
            assertThrows(IllegalArgumentException.class,
                    () -> MythicVariableLocation.parse(List.of("value=" + selector)), selector);
        }
    }

    @Test
    void recognizesNativeMechanicsWithoutChangingTheCanonicalExtension() {
        for (String name : List.of("variablesetlocation", "setvariablelocation", "setvarloc", "variablelocationset")) {
            assertTrue(MythicVariableLocation.supports(name));
            assertTrue(MythicVariableLocation.supports(name.toUpperCase(java.util.Locale.ROOT)));
        }
        for (String name : List.of("variable-location-set", "location-set", "setvariable", "locationunset")) {
            assertFalse(MythicVariableLocation.supports(name));
        }
        assertFalse(MythicVariableLocation.supports(null));
        for (String alias : ALIASES) {
            assertTrue(MythicVariableLocation.isValueArgument(alias));
            assertTrue(MythicVariableLocation.isValueArgument(alias.toUpperCase(java.util.Locale.ROOT)));
        }
        assertFalse(MythicVariableLocation.isValueArgument("var"));
        assertFalse(MythicVariableLocation.isValueArgument(null));
    }
}
