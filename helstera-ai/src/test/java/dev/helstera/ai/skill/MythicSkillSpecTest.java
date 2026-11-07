package dev.helstera.ai.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MythicSkillSpecTest {
    @Test
    void preservesExecutionContext() {
        MythicSkillSpec spec = MythicSkillSpec.parse(
                "damage{amount=5;ignorearmor=true} @PlayersInRadius{r=8} ~onAttack 25% ?health{h=0.5}");

        assertEquals("damage", spec.mechanic());
        assertEquals("5", spec.arguments().get("amount"));
        assertEquals("true", spec.arguments().get("ignorearmor"));
        assertEquals("PlayersInRadius{r=8}", spec.targeter());
        assertEquals("Attack", spec.trigger());
        assertEquals(25d, spec.chance());
        assertEquals(List.of("health{h=0.5}"), spec.inlineConditions());
        assertEquals(List.of(), spec.targetConditions());
    }

    @Test
    void supportsCommaValuesAndQuotedArguments() {
        MythicSkillSpec spec = MythicSkillSpec.parse("message{m=\"hello; world\";loc=1,2,3}");
        assertEquals("hello; world", spec.arguments().get("m"));
        assertEquals("1,2,3", spec.arguments().get("loc"));
    }

    @Test
    void escapedQuotedValuesPreserveDelimitersAndTargeter() {
        var spec = MythicSkillSpec.parse("message{m=\"Say \\\"hello; x=1\\\" } C:\\\\home \\q\";delay=2} @trigger");
        assertEquals("Say \"hello; x=1\" } C:\\home \\q", spec.arguments().get("m"));
        assertEquals("2", spec.arguments().get("delay"));
        assertEquals("trigger", spec.targeter());
        var positional = MythicSkillSpec.parse("message-target \"Say \\\"hello\\\" C:\\\\home\" @trigger");
        assertEquals(List.of("Say \"hello\" C:\\home"), positional.positional());
    }

    @Test
    void preservesKeyedArgumentOrderForPositionalFactories() {
        MythicSkillSpec spec = MythicSkillSpec.parse(
                "setmaterialcooldown{material=ENDER_PEARL;ticks=20}");
        assertEquals(List.of("material", "ticks"), List.copyOf(spec.arguments().keySet()));
    }

    @Test
    void preservesTargeterChainAndExecutionMetadata() {
        var spec = MythicSkillSpec.parse(
                "damage{amount=5} @PlayersInWorld{limit=2} > @NearestMob ~onAttack 25% ?health{h=1}");
        assertEquals("PlayersInWorld{limit=2} > @NearestMob", spec.targeter());
        assertEquals(List.of(), spec.positional());
        assertEquals("Attack", spec.trigger());
        assertEquals(25d, spec.chance());
        assertEquals(List.of("health{h=1}"), spec.inlineConditions());
        assertEquals(List.of(), spec.targetConditions());
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage @target >"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage @target > missing"));
        for (String separator : List.of(">", " >", "> ", " > ")) {
            var chained = MythicSkillSpec.parse("damage @living{filter=players}" + separator + "@nearestmob");
            assertEquals("living{filter=players} > @nearestmob", chained.targeter());
            assertEquals(List.of(), chained.positional());
        }
        var comparison = MythicSkillSpec.parse("damage @nearestConditionalTarget{conditions=[health{h=>5}]} > @nearest");
        assertEquals("nearestConditionalTarget{conditions=[health{h=>5}]} > @nearest", comparison.targeter());
        var targetCondition = MythicSkillSpec.parse("damage @PlayersInRadius{r=10} ?health{h=>10} ?@isInAudience");
        assertEquals(List.of("health{h=>10}"), targetCondition.inlineConditions());
        assertEquals(List.of("isInAudience"), targetCondition.targetConditions());
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage{amount=5"));
        assertThrows(IllegalArgumentException.class,
                () -> MythicSkillSpec.parse("damage{amount=5}garbage"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage 101%"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage -1%"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage NaN%"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage @self @target"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage 0% 100%"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage ~onAttack ~onDeath"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage ~on"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage ~unknown"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage 4 ?"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage 4 ?@"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage 4 @"));
        assertEquals("Attack", MythicSkillSpec.parse("damage ~ONAttack").trigger());
    }

    @Test
    void acceptsFiniteDecimalAndScientificChancePercentages() {
        assertEquals(0.5d, MythicSkillSpec.parse("damage .5%").chance());
        assertEquals(10d, MythicSkillSpec.parse("damage 1e1%").chance());
        assertEquals(100d, MythicSkillSpec.parse("damage +100%").chance());
    }

    @Test
    void constructorEnforcesFiniteChanceBounds() {
        for (double chance : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1d, 101d)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new MythicSkillSpec("damage", Map.of(), List.of(), null, null, chance, List.of(), List.of()));
        }
        for (double chance : List.of(0d, 50d, 100d)) {
            assertEquals(chance,
                    new MythicSkillSpec("damage", Map.of(), List.of(), null, null, chance, List.of(), List.of()).chance());
        }
    }
}



