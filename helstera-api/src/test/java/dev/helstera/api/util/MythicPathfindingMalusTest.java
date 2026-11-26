package dev.helstera.api.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MythicPathfindingMalusTest {
    @Test
    void parsesNativeListWithDistinctFireTypesAndNegativeCosts() {
        var parsed = MythicPathfindingMalus.parseMythic(
                List.of("WATER -1", "danger_fire 0", "DAMAGE_FIRE 12.5", " LAVA\t4 "));
        assertEquals(Map.of("WATER", -1f, "DANGER_FIRE", 0f, "DAMAGE_FIRE", 12.5f, "LAVA", 4f),
                parsed.values());
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void unknownTypesDoNotBecomeInventedAliases() {
        var parsed = MythicPathfindingMalus.parseMythic(
                List.of("WATER 2", "FIRE 0", "CACTUS 3", "DOOR 4"));
        assertEquals(Map.of("WATER", 2f), parsed.values());
        assertEquals(3, parsed.warnings().size());
        assertFalse(MythicPathfindingMalus.isSupportedPathType("FIRE"));
        assertTrue(MythicPathfindingMalus.isSupportedPathType("DANGER_TRAPDOOR"));
    }

    @Test
    void nativeMapAndScalarFormsRemainUnsupported() {
        assertTrue(MythicPathfindingMalus.parseMythic(Map.of("WATER", -1)).values().isEmpty());
        assertFalse(MythicPathfindingMalus.parseMythic(Map.of("WATER", -1)).warnings().isEmpty());
        assertFalse(MythicPathfindingMalus.parseMythic("WATER -1").warnings().isEmpty());
        assertFalse(MythicPathfindingMalus.parseMythic(null).warnings().isEmpty());
    }

    @Test
    void rejectsMalformedEntriesAndNonFiniteFloatConversions() {
        List<Object> entries = new ArrayList<>(List.of("WATER", "WATER 1 trailing", "WATER NaN",
                "WATER Infinity", "WATER -Infinity", "WATER 1e100", "WATER nope", "", 3));
        entries.add(null);
        var parsed = MythicPathfindingMalus.parseMythic(entries);
        assertTrue(parsed.values().isEmpty());
        assertEquals(entries.size(), parsed.warnings().size());
    }

    @Test
    void repeatedNativeEntriesUseTheLastValue() {
        var parsed = MythicPathfindingMalus.parseMythic(List.of("WATER 2", "WATER -1"));
        assertEquals(Map.of("WATER", -1f), parsed.values());
    }

    @Test
    void readsImportedYamlMapAndRetainsFloatPrecision() {
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                ai:
                  pathfinding-malus:
                    WATER: -1
                    DANGER_FIRE: 0.1
                """));
        var parsed = MythicPathfindingMalus.parseRuntime(yaml.get("ai.pathfinding-malus"));
        assertEquals(Map.of("WATER", -1f, "DANGER_FIRE", 0.1f), parsed.values());
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test
    void runtimeMapValidatesTypesAndFloatOverflowWithoutCoercingBooleans() {
        Map<Object, Object> raw = new LinkedHashMap<>();
        raw.put("WATER", true);
        raw.put("LAVA", Double.MAX_VALUE);
        raw.put("FIRE", 0);
        raw.put("RAIL", null);
        raw.put(42, 1);
        var parsed = MythicPathfindingMalus.parseRuntime(raw);
        assertTrue(parsed.values().isEmpty());
        assertEquals(raw.size(), parsed.warnings().size());
        assertFalse(MythicPathfindingMalus.parseRuntime(List.of("WATER -1")).warnings().isEmpty());
    }

    @Test
    void parsedResultsAreImmutableCopiesAndEmptyCollectionsAreValid() {
        Map<String, Float> values = new LinkedHashMap<>(Map.of("WATER", -1f));
        List<String> warnings = new ArrayList<>(List.of("warning"));
        var parsed = new MythicPathfindingMalus.Parsed(values, warnings);
        values.clear();
        warnings.clear();
        assertEquals(Map.of("WATER", -1f), parsed.values());
        assertEquals(List.of("warning"), parsed.warnings());
        assertThrows(UnsupportedOperationException.class, () -> parsed.values().put("LAVA", 1f));
        assertThrows(UnsupportedOperationException.class, () -> parsed.warnings().clear());
        assertTrue(MythicPathfindingMalus.parseMythic(List.of()).warnings().isEmpty());
        assertTrue(MythicPathfindingMalus.parseRuntime(Map.of()).warnings().isEmpty());
    }
}
