package dev.helstera.ai.skill;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AudienceSelectionTest {
    @Test
    void permissionFilterAllowsAndRejectsPlayers() {
        Player allowed = mock(Player.class);
        Player denied = mock(Player.class);
        when(allowed.hasPermission("helstera.menu")).thenReturn(true);
        when(denied.hasPermission("helstera.menu")).thenReturn(false);

        assertTrue(SkillExtras.audiencePermissionAllowed(allowed, "helstera.menu"));
        assertFalse(SkillExtras.audiencePermissionAllowed(denied, "helstera.menu"));
    }
}
