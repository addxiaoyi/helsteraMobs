package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AiTargetSelectorSpecTest {
    @Test void aliasesAreStaticAndGoalIsNotAnAlias() {
        assertEquals("clear", AiTargetSelectorSpec.parse(List.of("s=players", "target=monsters", "aitargetselector=clear")).selector());
        assertTrue(AiTargetSelectorSpec.parse(List.of("goal=players")).rules().isEmpty());
        assertEquals(AiTargetSelectorSpec.Kind.UNKNOWN,
                AiTargetSelectorSpec.parse(List.of("target=<caster.var.rule>")).rules().getFirst().kind());
    }
    @Test void prioritiesSpacingAndQuotesFollowNativeHandler() {
        assertEquals(7,AiTargetSelectorSpec.rule("players",7).priority());
        assertEquals(0,AiTargetSelectorSpec.rule("0 players",7).priority());
        assertEquals(AiTargetSelectorSpec.Kind.UNKNOWN,AiTargetSelectorSpec.rule("1  players",1).kind());
        assertEquals(AiTargetSelectorSpec.Kind.UNKNOWN,AiTargetSelectorSpec.parse(List.of("target=\"1 players\"")).rules().getFirst().kind());
        assertThrows(IllegalArgumentException.class,()->AiTargetSelectorSpec.rule(" 1 players",1));
        assertThrows(IllegalArgumentException.class,()->AiTargetSelectorSpec.rule("999999999999 players",1));
        assertThrows(IllegalArgumentException.class,()->AiTargetSelectorSpec.rule("1",1));
    }
    @Test void nestedConditionSpacesSurviveWithoutBecomingSecondRule() {
        var rule=AiTargetSelectorSpec.parse(List.of("s=3 nearestIf{c=[ - isplayer true ];ms=false;mr=true;i=8}")).rules().getFirst();
        assertEquals(3,rule.priority()); assertEquals(AiTargetSelectorSpec.Kind.CONDITIONAL,rule.kind());
        assertEquals("[ - isplayer true ]",rule.conditions()); assertEquals(8,rule.interval());
        assertFalse(rule.mustSee()); assertTrue(rule.mustReach());
        var defaults=AiTargetSelectorSpec.rule("nearestIf",1);
        assertEquals(0,defaults.interval()); assertTrue(defaults.mustSee()); assertFalse(defaults.mustReach());
    }
    @Test void factionFallbackAndCollidingOwnerAliasMatchLoadedCatalog() {
        assertEquals("Blue,RED",AiTargetSelectorSpec.rule("2 specificFaction Blue,RED",1).factions());
        assertEquals("Green",AiTargetSelectorSpec.rule("specificFaction{f=Green} Blue",1).factions());
        assertEquals(AiTargetSelectorSpec.Kind.OWNER_TARGET,AiTargetSelectorSpec.rule("ownerHurt",1).kind());
        assertEquals(AiTargetSelectorSpec.Kind.OWNER_HURT_BY,AiTargetSelectorSpec.rule("ownerAttacker",1).kind());
    }
    @Test void quotedSpecialCharactersRemainEncodedForTheNativeFieldParser() {
        var rule = AiTargetSelectorSpec.parse(List.of("s=specificFaction{f=\"Blue,Red/[]{}=; A-B\"}")).rules().getFirst();
        assertEquals("\"Blue<&cm>Red<&fs><&lb><&rb><&lc><&rc><&eq><&sc><&sp>A-B\"",rule.factions());
        assertEquals("players{",AiTargetSelectorSpec.protectBlock("players{"));
        assertEquals(AiTargetSelectorSpec.Kind.UNKNOWN,AiTargetSelectorSpec.rule("players{",1).kind());
        assertEquals("\"players\"",AiTargetSelectorSpec.protectBlock("\"players"));
    }
}
