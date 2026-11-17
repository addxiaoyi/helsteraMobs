package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PercentHealthSpecTest {
    @Test
    void defaultsAndAliasesUseFractionsWithoutChangingLegacyPercentActions() {
        for (String name : List.of("healpercent", "percentheal", "hp", "heal-fraction")) {
            var spec = PercentHealthSpec.parse(name, List.of());
            assertTrue(spec.healing());
            assertEquals(0.1, spec.resolve(v -> v).orElseThrow().fraction());
            for (String alias : List.of("m", "multiplier", "amount", "a")) {
                assertEquals(0.25, PercentHealthSpec.parse(name, List.of(alias + "=0.25"))
                        .resolve(v -> v).orElseThrow().fraction());
            }
        }
        for (String name : List.of("damagepercent", "percentdamage", "damage-fraction")) {
            var spec = PercentHealthSpec.parse(name, List.of("p=0.25", "ch=true"));
            assertFalse(spec.healing());
            assertTrue(spec.currentHealth());
            assertEquals(0.25, spec.resolve(v -> v).orElseThrow().fraction());
        }
        assertFalse(PercentHealthSpec.supports("heal-percent"));
        assertFalse(PercentHealthSpec.supports("damage-percent-targets"));
    }

    @Test
    void validatesOverhealAndDynamicValues() {
        for (String key : List.of("maxoverheal", "maxabsorb", "maxshield", "mo", "ma", "ms")) {
            var spec = PercentHealthSpec.parse("hp", List.of("m=<skill.var.heal>", "oh=true", key + "=0.5"));
            assertTrue(spec.overheal());
            assertEquals(new PercentHealthSpec.Resolved(0.2, 0.5), spec.resolve(v -> "0.2").orElseThrow());
            assertTrue(spec.resolve(v -> "NaN").isEmpty());
            assertTrue(spec.resolve(v -> "<missing>").isEmpty());
        }
        for (List<String> args : List.of(List.of("m=0.1", "multiplier=0.2"), List.of("0.1", "a=0.2"),
                List.of("m="), List.of("m=Infinity"), List.of("oh=maybe"), List.of("unknown=1"),
                List.of("p=0.25"), List.of("m=<broken"), List.of("0.1", "0.2"))) {
            assertThrows(IllegalArgumentException.class, () -> PercentHealthSpec.parse("hp", args), args.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> PercentHealthSpec.parse("damagepercent", List.of("ch=<skill.var.flag>")));
        assertThrows(IllegalArgumentException.class, () -> PercentHealthSpec.parse("damagepercent", List.of("ignorearmor=true")));
    }
}
