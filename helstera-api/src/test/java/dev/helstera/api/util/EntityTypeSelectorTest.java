package dev.helstera.api.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTypeSelectorTest {
    @Test
    void parsesEntityTypesAndVariantAlternatives() {
        assertTrue(EntityTypeSelector.parse("CAT{variant=TABBY}").isPresent(), "CAT/TABBY");
        assertTrue(EntityTypeSelector.parse("RABBIT{variant=BLACK_AND_WHITE}").isPresent(),
                "RABBIT/BLACK_AND_WHITE");
        assertTrue(EntityTypeSelector.parse("WOLF{variant=SNOWY}").isPresent(), "WOLF/SNOWY");
        var selector = EntityTypeSelector.parse(
                "minecraft:cat{v=tabby, black},wolf[variant=snowy]").orElseThrow();

        assertEquals("CAT[variant=TABBY,BLACK],WOLF[variant=SNOWY]", selector.canonical());
    }

    @Test
    void rejectsMalformedAndUnsupportedConstraints() {
        for (String source : new String[]{
                "CAT{variant=UNKNOWN}",
                "ZOMBIE{variant=TABBY}",
                "CAT{variant=TABBY;v=BLACK}",
                "CAT{color=BLACK}",
                "CAT{variant=TABBY",
                "CAT,,WOLF",
                "NOT_A_REAL_ENTITY"}) {
            assertFalse(EntityTypeSelector.parse(source).isPresent(), source);
        }
    }
}
