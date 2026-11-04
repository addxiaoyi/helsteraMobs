package dev.helstera.migration.importer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MythicSpecTest {
    @Test
    void commasOnlySeparateImmediatelyFollowingKeyValuePairs() {
        assertEquals(List.of("items=STONE 2,DIAMOND 3", "n = false"),
                MythicSpec.keyedArguments("dropitem{items=STONE 2,DIAMOND 3, n = false}"));
        assertEquals("dropitem STONE 2,DIAMOND 3 false",
                MythicSpec.normalize("dropitem{items=STONE 2,DIAMOND 3, n = false}"));
        assertEquals(List.of("loc=1,2,3", "n=false", "amount=5"),
                MythicSpec.keyedArguments("x{loc=1,2,3,n=false,amount=5}"));
        assertEquals(List.of("value=one,bad key=x", "valid-key_2 =yes"),
                MythicSpec.keyedArguments("x{value=one,bad key=x,valid-key_2 =yes}"));
        assertEquals(List.of("value=[a,b=c]", "text=a,b=c", "flag=true"),
                MythicSpec.keyedArguments("x{value=[a,b=c],text=\"a,b=c\",flag=true}"));
    }

    @Test
    void keyedValuesRespectListsQuotesAndNestedDelimiters() {
        assertEquals("Hello, world", MythicSpec.keyOf("message{m=Hello, world}", "m"));
        assertEquals("STONE 2,DIAMOND 3", MythicSpec.keyOf("dropitem{items=STONE 2,DIAMOND 3,n=false}", "items"));
        assertEquals("false", MythicSpec.keyOf("dropitem{items=STONE 2,DIAMOND 3,n=false}", "n"));
        assertEquals("Hello, radius=10; world", MythicSpec.keyOf("message{m=\"Hello, radius=10; world\"}", "m"));
        assertNull(MythicSpec.keyOf("message{m=\"Hello, radius=10; world\"}", "radius"));
        assertNull(MythicSpec.keyOf("x{value=[a,radius=10]}", "radius"));
    }

    @Test
    void escapedQuotesAndBackslashesDoNotEndQuotedValues() {
        String spec = "message{m=\"Say \\\"hi; radius=10\\\" at C:\\\\home } now\";radius=12} @target";
        assertEquals("Say \"hi; radius=10\" at C:\\home } now", MythicSpec.keyOf(spec, "m"));
        assertEquals("12", MythicSpec.keyOf(spec, "radius"));
        assertEquals(2, MythicSpec.keyedArguments(spec).size());
    }
}
