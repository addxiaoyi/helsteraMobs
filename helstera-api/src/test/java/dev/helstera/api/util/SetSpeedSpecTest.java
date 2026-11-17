package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SetSpeedSpecTest {
    @Test
    void officialAliasesAndDefaultsUseStaticFloatMultipliers() {
        assertEquals(0.2f, SetSpeedSpec.parse(List.of()).speed());
        for (String key : List.of("speed", "s", "amount", "a", "value", "v", "multiplier", "m")) {
            var spec = SetSpeedSpec.parse(List.of("t=FLY", key + "=2"));
            assertEquals(0.2f, spec.speed());
            assertEquals(List.of("type=FLY", "speed=2.0"), spec.arguments());
            assertEquals(spec, SetSpeedSpec.parse(spec.arguments()));
        }
        assertEquals(0.4f, SetSpeedSpec.parse(List.of("2")).speed());
    }

    @Test
    void typeMatchesPinnedContainsFlyRuleAndKeepsNegativePlayerSpeeds() {
        for (String type : List.of("FLY", "flying", "dragon_fly")) {
            assertTrue(SetSpeedSpec.parse(List.of("type=" + type)).flying());
        }
        for (String type : List.of("WALK", "walking", "unknown")) {
            assertFalse(SetSpeedSpec.parse(List.of("type=" + type)).flying());
        }
        assertEquals(-0.2f, SetSpeedSpec.parse(List.of("m=-1")).speed());
    }

    @Test
    void malformedUnsupportedAndDynamicArgumentsFailClosed() {
        for (List<String> args : List.of(List.of("m=1", "speed=2"), List.of("t=FLY", "type=WALK"),
                List.of("1", "2"), List.of("m="), List.of("type="), List.of("unknown=1"),
                List.of("s=NaN"), List.of("m=Infinity"), List.of("m=1e100"),
                List.of("m=<skill.var.speed>"), List.of("type=<skill.var.type>"))) {
            assertThrows(IllegalArgumentException.class, () -> SetSpeedSpec.parse(args), args.toString());
        }
    }
}
