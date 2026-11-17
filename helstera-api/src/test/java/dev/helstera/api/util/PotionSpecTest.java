package dev.helstera.api.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class PotionSpecTest {
    @Test
    void officialDefaultsAndLegacyPositionalDuration() {
        assertEquals(new PotionSpec.Resolved("SLOWNESS", 100, 0, false, true, true, false),
                PotionSpec.parse(List.of()).resolve(UnaryOperator.identity()).orElseThrow());
        assertEquals("60", PotionSpec.parse(List.of("SPEED")).duration());
        assertEquals("100", PotionSpec.parse(List.of("type=SPEED")).duration());
    }

    @Test
    void aliasesAndFlagsKeepTheirMeaningRegardlessOfOrder() {
        for (String force : List.of("force", "overwrite", "ow", "override", "or")) {
            var potion = PotionSpec.parse(List.of("i=false", "p=off", "a=yes", force + "=1",
                    "lvl=2", "d=40", "t=FAST_DIGGING"));
            assertEquals(new PotionSpec.Resolved("HASTE", 40, 2, true, false, false, true),
                    potion.resolve(UnaryOperator.identity()).orElseThrow());
            assertEquals(potion, PotionSpec.parse(potion.positionalArguments()));
        }
        assertEquals("RESISTANCE", PotionSpec.parse(List.of("effect=minecraft:damage_resistance")).type());
        assertEquals("2", PotionSpec.parse(List.of("effect=SPEED", "amplifier=2",
                "show-particles=false", "show-icon=false")).level());
        assertEquals("SPEED", PotionSpec.parse(List.of("type=\"SPEED\"")).type());
    }

    @Test
    void mixedArgumentsUseFixedSlotsAndRejectConflicts() {
        var potion = PotionSpec.parse(List.of("SPEED", "duration=80", "level=2"));
        assertEquals(List.of("SPEED", "80", "2"), potion.positionalArguments());
        for (List<String> args : List.of(List.of("type=SPEED", "POISON"),
                List.of("SPEED", "40", "duration=80"), List.of("d=40", "duration=80"))) {
            assertThrows(IllegalArgumentException.class, () -> PotionSpec.parse(args), args.toString());
        }
    }

    @Test
    void invalidArgumentsAreNeverConvertedToDefaults() {
        for (String arg : List.of("type=NOT_A_POTION", "type=", "=SPEED", "unknown=true", "force=maybe",
                "duration=0", "duration=-1", "duration=NaN", "duration=Infinity", "duration=2.5",
                "duration=2147483648", "level=-1", "level=NaN", "level=0.5", "level=2147483648",
                "duration=<broken", "")) {
            assertThrows(IllegalArgumentException.class, () -> PotionSpec.parse(List.of(arg)), arg);
        }
        assertThrows(IllegalArgumentException.class,
                () -> PotionSpec.parse(List.of("SPEED", "20", "1", "false", "true", "true", "false", "extra")));
    }

    @Test
    void dynamicTypeDurationAndLevelResolveTogetherOrDoNothing() {
        var potion = PotionSpec.parse(List.of("type=<skill.var.type>", "d=<skill.var.ticks>", "l=<skill.var.level>"));
        Map<String, String> valid = Map.of("<skill.var.type>", "SLOW", "<skill.var.ticks>", "80.0",
                "<skill.var.level>", "2");
        assertEquals(new PotionSpec.Resolved("SLOWNESS", 80, 2, false, true, true, false),
                potion.resolve(value -> valid.getOrDefault(value, value)).orElseThrow());
        for (String value : List.of("NaN", "Infinity", "-1", "0.5", "2147483648", "<missing>")) {
            assertTrue(potion.resolve(token -> token.equals("<skill.var.level>") ? value : valid.get(token)).isEmpty());
            assertTrue(potion.resolve(token -> token.equals("<skill.var.ticks>") ? value : valid.get(token)).isEmpty());
        }
        assertTrue(potion.resolve(UnaryOperator.identity()).isEmpty());
        assertTrue(potion.resolve(token -> token.equals("<skill.var.type>") ? "UNKNOWN" : valid.get(token)).isEmpty());
    }
}
