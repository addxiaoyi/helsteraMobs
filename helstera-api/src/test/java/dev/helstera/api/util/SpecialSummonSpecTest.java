package dev.helstera.api.util;

import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class SpecialSummonSpecTest {
    @Test void aliasesHaveNativePriorityIncludingTheMisspelledDurationKey() {
        var spec = SpecialSummonSpec.parse("summonCloud", List.of("p=cloud", "drou=-3",
                "durationreductiononuse=99", "durationreudctiononuse=-7", "r=9", "radius=4"));
        assertEquals("summon-cloud", spec.mechanic());
        assertEquals(-7, spec.integer("0", s -> s, new Random(1), "durationreudctiononuse", "drou"));
        assertEquals(4, spec.integer("2", s -> s, new Random(1), "radius", "r"));
        var fall = SpecialSummonSpec.parse("summonFallingBlock", List.of("type=stone", "types=oak_log[axis=x]", "m=sand"));
        assertEquals("oak_log[axis=x]", fall.block());
        var passenger = SpecialSummonSpec.parse("summonPassenger", List.of("t=One", "rider=Two", "p=Three", "passenger=Four", "s=true"));
        assertEquals("Four", passenger.passenger()); assertTrue(passenger.stack());
        assertEquals("DIRT", SpecialSummonSpec.parse("summonFallingBlock", List.of()).block());
    }
    @Test void particleAliasCollisionsAndUnknownFallbackRemainDistinct() {
        assertEquals(Particle.DUST, MythicParticleSpec.parse(Map.of()).particle());
        assertEquals(Particle.DUST, MythicParticleSpec.parse(Map.of("particle", "red_dust")).particle());
        assertEquals(Particle.ENTITY_EFFECT, MythicParticleSpec.parse(Map.of("p", "spellmobambient")).particle());
        assertEquals(Particle.POOF, MythicParticleSpec.parse(Map.of("p", "explode")).particle());
        assertEquals(Particle.EXPLOSION, MythicParticleSpec.parse(Map.of("p", "explosion")).particle());
        assertEquals(Particle.CLOUD, MythicParticleSpec.parse(Map.of("p", "unknown_particle")).particle());
        assertEquals(Particle.BLOCK, MythicParticleSpec.parse(Map.of("p", "dust")).particle());
        assertEquals(Particle.BLOCK, MythicParticleSpec.parse(Map.of("p", "block")).particle());
        assertEquals(Particle.UNDERWATER, MythicParticleSpec.parse(Map.of("p", "underwater")).particle());
        for (String collision : List.of("barrier", "light"))
            assertThrows(IllegalArgumentException.class, () -> MythicParticleSpec.parse(Map.of("p", collision)));
    }
    @Test void colorsPreserveRgbaWrappingAndFireworkDyeValues() {
        assertEquals(Color.fromARGB(0x78,0x12,0x34,0x56), MythicParticleSpec.color("#12345678"));
        assertEquals(Color.fromRGB(0x123456), MythicParticleSpec.color("1193046"));
        assertEquals(Color.fromARGB(127,255,0,1), MythicParticleSpec.color("-1,256,257,0.5"));
        assertEquals(DyeColor.BLUE.getFireworkColor(), MythicParticleSpec.color("blue"));
        assertThrows(IllegalArgumentException.class, () -> MythicParticleSpec.color("not_a_color"));
    }
    @Test void particleDataRetainsBothTransitionColorsAndNativeSculkTypeMismatch() {
        var spec = MythicParticleSpec.parse(Map.of("p", "dustcolor", "fc", "#112233", "tc", "#445566", "size", "2"));
        var data = (Particle.DustTransition) spec.data(s -> s, new Random(1));
        assertEquals(Color.fromRGB(0x112233), data.getColor());
        assertEquals(Color.fromRGB(0x445566), data.getToColor()); assertEquals(2, data.getSize());
        var sculk = MythicParticleSpec.parse(Map.of("p", "sculk_charge", "r", "180"));
        assertEquals(Math.PI, sculk.data(s -> s, new Random(1)));
        assertEquals(0, MythicParticleSpec.parse(Map.of("p", "shriek")).data(s -> s, new Random(1)));
    }
    @Test void dynamicIntegersAreExplicitExtensionsWhileNonfiniteRadiusChangesReject() {
        var spec = SpecialSummonSpec.parse("summoncloud", List.of("radius=\"<target.l.y>\"", "rrou=-0.5"));
        assertEquals(14, spec.integer("2", s -> s.replace("<target.l.y>", "14"), new Random(1), "radius"));
        assertEquals(1f, SpecialSummonSpec.number("-0.5to-0.5", s -> s, new Random(1)));
        assertEquals(-.5f, spec.floating("0", s -> s, new Random(1), "rrou"));
        assertThrows(IllegalArgumentException.class, () -> SpecialSummonSpec.parse("summoncloud", List.of("rrou=NaN")));
        assertThrows(IllegalArgumentException.class, () -> SpecialSummonSpec.parse("summoncloud", List.of("STONE")));
    }
}
