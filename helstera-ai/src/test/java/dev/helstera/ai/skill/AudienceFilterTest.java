package dev.helstera.ai.skill;

import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void malformedAudienceFiltersFailClosed() {
        assertFalse(SkillExtras.AudienceSpec.parse("permission:", 16).valid());
        assertFalse(SkillExtras.AudienceSpec.parse("radius{r=NaN}", 16).valid());
        assertFalse(SkillExtras.AudienceSpec.parse(
                "radius{permission=one;permission=two}", 16).valid());
        assertFalse(SkillExtras.AudienceSpec.parse("radius{r=12", 16).valid());
        assertFalse(SkillExtras.AudienceSpec.parse("unknown", 16).valid());
    }

    @Test
    void audienceTargetersUseTheSharedTargeterParser() {
        Player target = org.mockito.Mockito.mock(Player.class);
        var context = dev.helstera.api.behavior.BehaviorContext.of(
                null, target, 1.0, 4.0, 0, "AUDIENCE");

        assertEquals(List.of(target), SkillExtras.selectAudienceTargeter(
                "@target{limit=1}", context, List.of(target)));
        assertTrue(SkillExtras.selectAudienceTargeter(
                "@target{limit=0}", context, List.of(target)).isEmpty());
    }
}
