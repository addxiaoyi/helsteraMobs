package dev.helstera.api.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConditionListParserTest {
    @Test
    void preservesBooleanOrWhileSplittingSinglePipeLists() {
        assertEquals(List.of("day || night", "raining", "(day || night) && raining"),
                ConditionListParser.parse("day || night | raining; (day || night) && raining"));
        assertEquals(List.of("day ||"), ConditionListParser.parseFailClosed("day ||"));
    }

    @Test
    void failClosedParsingPreservesEmptyListElements() {
        for (String input : List.of("day;", ";day", "day;;night", "day | | night")) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    ConditionListParser.parseFailClosed(input).contains(ConditionListParser.INVALID_CONDITION), input);
        }
        assertEquals(List.of("day", "night"), ConditionListParser.parse("day;;night"));
    }

    @Test
    void failClosedParsingRejectsUnbalancedStructure() {
        for (String input : List.of("night{", "night}", "night(", "night]", "name \"unfinished",
                "check{[}]", "check{(])}")) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    ConditionListParser.parseFailClosed(input).contains(ConditionListParser.INVALID_CONDITION), input);
        }
        assertEquals(List.of("name \"valid\""), ConditionListParser.parseFailClosed("name \"valid\""));
        assertEquals(List.of("check{value=[(ok)]}", "night"),
                ConditionListParser.parseFailClosed("check{value=[(ok)]};night"));
    }

    @Test
    @DisplayName("scalar conditions accept separators without losing range commas")
    void scalarConditionsPreserveRangeArguments() {
        assertEquals(List.of("day", "!raining", "time-between=0,12000", "night"),
                ConditionListParser.parse("day, !raining | time-between=0,12000,night"));
    }

    @Test
    @DisplayName("coordinate region arguments remain one condition")
    void coordinateRegionRemainsIntact() {
        assertEquals(List.of("region=world,0,64,0,10,80,10", "night"),
                ConditionListParser.parse("region=world,0,64,0,10,80,10,night"));
    }

    @Test
    @DisplayName("nested condition arguments ignore internal separators")
    void nestedArgumentsRemainIntact() {
        assertEquals(List.of("health{h=<50%,mode=raw}", "distance-above 4"),
                ConditionListParser.parse("health{h=<50%,mode=raw};distance-above 4"));
    }

    @Test
    @DisplayName("YAML lists and arrays are flattened consistently")
    void listShapesAreFlattened() {
        assertEquals(List.of("day", "night", "raining"),
                ConditionListParser.parse(List.of("day,night", new String[]{"raining"})));
    }
}
