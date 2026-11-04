package dev.helstera.plugin;

import org.bukkit.entity.ExperienceOrb;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExperienceOrbAccountingTest {
    @Test
    void countsNewAndExistingStackedOrbs() {
        var orb = mock(ExperienceOrb.class);
        var id = UUID.randomUUID();
        when(orb.getUniqueId()).thenReturn(id);
        when(orb.getExperience()).thenReturn(3);
        when(orb.getCount()).thenReturn(2);
        assertEquals(6, HelsteraPlugin.orbDelta(orb, Map.of()));
        assertEquals(3, HelsteraPlugin.orbDelta(orb, Map.of(id, new HelsteraPlugin.OrbSnapshot(3, 1))));
        assertEquals(2, HelsteraPlugin.orbDelta(orb, Map.of(id, new HelsteraPlugin.OrbSnapshot(4, 1))));
        assertEquals(0, HelsteraPlugin.orbDelta(orb, Map.of(id, new HelsteraPlugin.OrbSnapshot(3, 2))));
    }
}
