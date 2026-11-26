package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MythicJsonMessageSpecTest {
    @Test void aliasesAndArgumentPriorityMatchNativeWithoutCapturingPlainMessages() {
        for (String alias : List.of("messagejson", "JSONMESSAGE", "jmsg", "jm", "message-json"))
            assertEquals("message-json", MythicJsonMessageSpec.canonical(alias));
        assertFalse(MythicJsonMessageSpec.supports("message"));
        assertFalse(MythicJsonMessageSpec.supports(null));
        assertEquals("last", MythicJsonMessageSpec.parse(List.of("m=short", "msg=middle",
                "message=first", "MESSAGE=last")).message());
        assertEquals("", MythicJsonMessageSpec.parse(List.of("message=", "m=fallback")).message());
        assertThrows(IllegalArgumentException.class, () -> MythicJsonMessageSpec.parse(List.of("text={}")));
        assertThrows(IllegalArgumentException.class, () -> MythicJsonMessageSpec.parse(List.of("{}")));
    }

    @Test void outerQuotesAreRemovedWhileJsonEscapesRemainIntact() {
        String json = "{\"text\":\"line\\nquoted \\\"word\\\" C:\\\\path\"}";
        assertEquals(json, MythicJsonMessageSpec.parse(List.of("m='" + json + "'")).message());
        assertEquals(json, MythicJsonMessageSpec.parse(List.of("msg=\"'" + json + "'\"")).message());
    }

    @Test void nativeSpecialCharactersAndLegacyRolesArePreparedBeforeRuntimeResolution() {
        String encoded = "<&lc><&dq>text<&dq><&co><&dq><player.name><&sp><killer.name><&heart><&dq><&rc>";
        assertEquals("{\"text\":\"<caster.name> <trigger.name>❤\"}",
                MythicJsonMessageSpec.parse(List.of("m=" + encoded)).message());
    }
}
