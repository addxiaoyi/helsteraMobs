package dev.helstera.api.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MerchantTradeSpecTest {
    @Test void aliasesDoNotChangeTheDefaultOperation() {
        for (String name : List.of("addTrade", "setTrade", "removeTrade", "replaceTrade"))
            assertEquals("merchant-trade", MerchantTradeSpec.canonical(name));
        var spec = MerchantTradeSpec.parse(List.of());
        assertEquals("ADD", spec.action()); assertEquals(0, spec.slot());
        assertEquals("STONE", spec.ingredient()); assertEquals("STONE", spec.result());
        assertNull(spec.secondIngredient()); assertEquals(Integer.MAX_VALUE, spec.maxUses());
        assertFalse(spec.experienceReward()); assertEquals(0, spec.villagerExperience());
        assertEquals(0f, spec.priceMultiplier()); assertEquals(1, spec.demand());
        assertEquals(1, spec.specialPrice()); assertFalse(spec.ignoreDiscounts());
        assertTrue(MerchantTradeSpec.realTrade(List.of()));
        for (String name : List.of("opentrades", "opentrade", "trade"))
            assertEquals("open-trades", MerchantTradeSpec.canonical(name));
    }
    @Test void nativeAliasPriorityAndRawFallbacksArePreserved() {
        var spec = MerchantTradeSpec.parse(List.of("mode=remove", "action=replace", "ACTION=SET", "s=2", "slot=3",
                "item=APPLE", "ingredient=DIAMOND 4", "item2=EMERALD 2", "r=BREAD", "maxUses=\"2\"",
                "exp=true", "villExp=-4", "multiplier=0.25", "d=oops", "special=-2", "discounts=true"));
        assertEquals("SET", spec.action()); assertEquals(3, spec.slot());
        assertEquals("DIAMOND 4", spec.ingredient()); assertEquals("EMERALD 2", spec.secondIngredient());
        assertEquals("BREAD", spec.result()); assertEquals(Integer.MAX_VALUE, spec.maxUses());
        assertTrue(spec.experienceReward()); assertEquals(-4, spec.villagerExperience());
        assertEquals(.25f, spec.priceMultiplier()); assertEquals(1, spec.demand());
        assertEquals(-2, spec.specialPrice()); assertTrue(spec.ignoreDiscounts());
        assertFalse(MerchantTradeSpec.realTrade(List.of("real=true", "realTrade=\"true\"")));
        assertThrows(IllegalArgumentException.class, () -> MerchantTradeSpec.parse(List.of("multiplier=NaN")));
        assertThrows(IllegalArgumentException.class, () -> MerchantTradeSpec.parse(List.of("ADD")));
    }
}
