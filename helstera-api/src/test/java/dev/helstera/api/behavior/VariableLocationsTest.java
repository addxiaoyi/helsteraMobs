package dev.helstera.api.behavior;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VariableLocationsTest {
    @Test
    void rejectsSkillScopeFromPersistentStore() {
        var context = BehaviorContext.execution(null, null, 1, -1, 0,
                "test", null, null, java.util.List.of(), null);
        VariableLocations.set("skill", "mark", context, new Location(null, 1, 2, 3));
        assertEquals(0, VariableLocations.scopeCount());
    }

    @Test
    void clearingUnknownEntityAndWorldIsSafe() {
        VariableLocations.clearEntity(null);
        VariableLocations.clearWorld(null);
        assertEquals(0, VariableLocations.scopeCount());
    }}
