package dev.helstera.ai.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudienceSpecTest {

    @Test
    void parsesPermissionAudienceAsAllPlayers() {
        var spec = SkillExtras.AudienceSpec.parse("permission:helstera.menu", 32);

        assertEquals("all", spec.kind());
        assertEquals("helstera.menu", spec.permission());
        assertEquals(32, spec.radius());
    }

    @Test
    void parsesRadiusAndFactionOptions() {
        var spec = SkillExtras.AudienceSpec.parse("radius{r=12;faction=guards}", 32);

        assertEquals("radius", spec.kind());
        assertEquals(12, spec.radius());
        assertEquals("guards", spec.faction());
    }
}
