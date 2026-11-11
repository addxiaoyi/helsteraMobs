package dev.helstera.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShowHealthMessageTest {
    @Test
    void triggersOnlyOnDownwardTenPercentCrossingsAndAllowsHealingToRearm() {
        assertFalse(ShowHealthMessage.crossedThreshold(100, 95, 100));
        assertTrue(ShowHealthMessage.crossedThreshold(95, 90, 100));
        assertFalse(ShowHealthMessage.crossedThreshold(90, 89, 100));
        assertTrue(ShowHealthMessage.crossedThreshold(89, 80, 100));
        assertFalse(ShowHealthMessage.crossedThreshold(80, 79, 100));
        assertTrue(ShowHealthMessage.crossedThreshold(79, 70, 100));
        assertFalse(ShowHealthMessage.crossedThreshold(89, 88, 100));
        assertTrue(ShowHealthMessage.crossedThreshold(88, 79, 100));
        assertTrue(ShowHealthMessage.crossedThreshold(85, 79, 100),
                "85% to 79% crosses the 80% boundary after healing");
        assertFalse(ShowHealthMessage.crossedThreshold(79, 85, 100));
        assertFalse(ShowHealthMessage.crossedThreshold(10, 9, 0));
        assertFalse(ShowHealthMessage.crossedThreshold(Double.NaN, 0, 20));
    }

    @Test
    void rendersLegacyAndModernPlaceholdersWithoutConsumingUnknownVariables() {
        assertEquals("§cWarden 89% (89/100) Warden 89/100 <unknown.value>",
                ShowHealthMessage.render("&c$mobname $percent% ($mobhp/$mobmaxhp) "
                                + "<mob.name> <mob.hp>/<mob.mhp> <unknown.value>",
                        "Warden", 89, 100));
        assertEquals("§cMob §7- §c0% §7(0/0)", ShowHealthMessage.render(null, null, 0, 0));
        assertTrue(ShowHealthMessage.DEFAULT_RADIUS > 0);
    }
}
