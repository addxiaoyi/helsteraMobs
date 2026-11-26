package dev.helstera.api.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MythicConditionActionTest {
    @Test
    void onlyTopLevelTokensAreActions() {
        assertNull(MythicConditionAction.split("stringequals{value='x CAST foo';compare='x'}"));
        var parsed = MythicConditionAction.split("(health < 10 || burning) CAST Emergency");
        assertNotNull(parsed);
        assertEquals("(health < 10 || burning)", parsed.condition());
        assertEquals("Emergency", parsed.argument());
        assertEquals(parsed, MythicConditionAction.decode(parsed.runtime()));
    }

    @Test
    void optionalMessagesCanContainWhitespaceAndRuntimeKeysSurviveLowercasing() {
        var parsed = MythicConditionAction.split("burning CANCEL 'Too Hot Here'");
        assertEquals("Too Hot Here", parsed.argument());
        var decoded = MythicConditionAction.decode(parsed.runtime().toLowerCase(java.util.Locale.ROOT));
        assertEquals(parsed, decoded);
    }

    @Test
    void malformedOrMissingActionsFailClosed() {
        assertFalse(MythicConditionAction.split("burning CAST").valid());
        assertNull(MythicConditionAction.split("burning{value='unterminated CAST foo"));
        assertThrows(IllegalArgumentException.class, () -> MythicConditionAction.decode("condition-action CAST 00 -"));
        assertThrows(IllegalArgumentException.class, () -> MythicConditionAction.decode("condition-action CAST zz 01"));
    }
}
