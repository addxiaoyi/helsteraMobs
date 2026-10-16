package dev.helstera.ai.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudienceFilterTest {
    @Test
    void factionFilterMatchesRecipientFaction() {
        assertTrue(SkillExtras.audienceFactionAllowed("guards", "guards"));
        assertFalse(SkillExtras.audienceFactionAllowed("guards", "undead"));
        assertTrue(SkillExtras.audienceFactionAllowed("", null));
    }

    @Test
    void audienceAliasesNormalizeToExpectedSelectorKinds() {
        var permission = SkillExtras.AudienceSpec.parse("permission:helstera.menu", 16);
        var players = SkillExtras.AudienceSpec.parse("players", 16);
        var radius = SkillExtras.AudienceSpec.parse("playersinradius", 12);

        assertTrue(permission.kind().equals("all") && permission.permission().equals("helstera.menu"));
        assertTrue(players.kind().equals("world"));
        assertTrue(radius.kind().equals("nearby") && radius.radius() == 12);
    }
}
