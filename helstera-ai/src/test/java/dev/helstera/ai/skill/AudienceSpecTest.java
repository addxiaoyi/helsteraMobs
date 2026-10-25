package dev.helstera.ai.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void parsesCombinedPermissionAndFactionExclusions() {
        var spec = SkillExtras.AudienceSpec.parse(
                "radius{r=12;permission=!helstera.menu;faction=-undead}", 32);

        assertEquals("radius", spec.kind());
        assertEquals(12, spec.radius());
        assertEquals("helstera.menu", spec.permission());
        assertEquals("undead", spec.faction());
        assertTrue(spec.permissionExcluded());
        assertTrue(spec.factionExcluded());
    }
}
