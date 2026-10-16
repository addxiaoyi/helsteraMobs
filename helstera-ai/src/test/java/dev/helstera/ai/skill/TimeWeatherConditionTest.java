package dev.helstera.ai.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeWeatherConditionTest {

    @Test
    void matchesNamedTimeWindowsAndWrappingRanges() {
        assertTrue(SkillExtras.matchesTime("dawn", 500));
        assertTrue(SkillExtras.matchesTime("night", 13_000));
        assertTrue(SkillExtras.matchesTime("23000-1000", 23_500));
        assertTrue(SkillExtras.matchesTime("23000-1000", 500));
        assertFalse(SkillExtras.matchesTime("23000-1000", 12_000));
    }

    @Test
    void matchesNumericTimeComparators() {
        assertTrue(SkillExtras.matchesTime(">=6000", 6_000));
        assertTrue(SkillExtras.matchesTime("<12000", 11_999));
        assertFalse(SkillExtras.matchesTime("<12000", 12_000));
    }

    @Test
    void acceptsMythicWeatherAliases() {
        assertTrue(SkillExtras.matchesWeather("clear", false, false));
        assertTrue(SkillExtras.matchesWeather("thunderstorm", true, true));
        assertTrue(SkillExtras.matchesWeather("rain", true, false));
        assertFalse(SkillExtras.matchesWeather("rain", true, true));
    }
}
