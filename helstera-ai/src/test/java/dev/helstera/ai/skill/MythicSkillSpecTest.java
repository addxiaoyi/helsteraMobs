package dev.helstera.ai.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    }

    @Test
    void supportsCommaValuesAndQuotedArguments() {
        MythicSkillSpec spec = MythicSkillSpec.parse("message{m=\"hello; world\";loc=1,2,3}");
        assertEquals("hello; world", spec.arguments().get("m"));
        assertEquals("1,2,3", spec.arguments().get("loc"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage{amount=5"));
        assertThrows(IllegalArgumentException.class, () -> MythicSkillSpec.parse("damage 101%"));
    }
}



