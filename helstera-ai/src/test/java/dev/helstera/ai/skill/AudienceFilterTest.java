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
        assertTrue(SkillExtras.audienceFactionAllowed("undead", "guards", true));
        assertFalse(SkillExtras.audienceFactionAllowed("undead", "undead", true));
    }

    @Test
    void audienceAliasesNormalizeToExpectedSelectorKinds() {
        var permission = SkillExtras.AudienceSpec.parse("permission:helstera.menu", 16);
        var players = SkillExtras.AudienceSpec.parse("players", 16);
        var radius = SkillExtras.AudienceSpec.parse("playersinradius", 12);

        assertTrue(permission.kind().equals("all") && permission.permission().equals("helstera.menu"));
        assertTrue(players.kind().equals("world"));
        assertTrue(radius.kind().equals("nearby") && radius.radius() == 12);

        var excludedPermission = SkillExtras.AudienceSpec.parse("permission:!helstera.menu", 16);
        var excludedFaction = SkillExtras.AudienceSpec.parse("faction:-undead", 16);
        assertTrue(excludedPermission.permissionExcluded());
        assertTrue(excludedPermission.permission().equals("helstera.menu"));
        assertTrue(excludedFaction.factionExcluded());
        assertTrue(excludedFaction.faction().equals("undead"));
    }
}
