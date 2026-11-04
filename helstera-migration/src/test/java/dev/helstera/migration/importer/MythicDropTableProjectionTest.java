package dev.helstera.migration.importer;

import org.junit.jupiter.api.Test;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MythicDropTableProjectionTest {
    @Test
    void projectsSafeEntriesAndSkipsExternalEntries() {
        var table = MythicDropTableProjection.project("boss_drops", List.of(
                "DIAMOND 2-4 25% ?world-is arena",
                Map.of("table", "nested", "amount-min", 1, "amount-max", 1,
                        "chance-expression", "luck-based", "conditions", List.of("permission loot.vip")),
                "MMOItems:SWORD 1 1.0"));

        assertEquals("DIAMOND", table.getString("tables.boss_drops.entries.0.item"));
        assertEquals(2, table.getInt("tables.boss_drops.entries.0.amount-min"));
        assertEquals(3, table.getInt("tables.boss_drops.entries.0.amount-max"));
        assertEquals(0.25, table.getDouble("tables.boss_drops.entries.0.chance"), 1e-9);
        assertEquals("world-is arena",
                table.getStringList("tables.boss_drops.entries.0.conditions").get(0));
        assertEquals("nested", table.getString("tables.boss_drops.entries.1.table"));
        assertEquals("luck-based", table.getString("tables.boss_drops.entries.1.chance-expression"));
        assertEquals(2, table.getConfigurationSection("tables.boss_drops.entries").getKeys(false).size());
        assertTrue(table.getString("tables.boss_drops.entries.0.item").equals("DIAMOND"));
    }

    @Test
    void malformedChanceDoesNotProjectOrSuppressSafeSibling() {
        var table = MythicDropTableProjection.project("rewards", List.of(
                "DIAMOND 1 1%0", Map.of("item", "EMERALD", "chance", "25%%"),
                "GOLD_INGOT 1 25%"));
        var entries = table.getConfigurationSection("tables.rewards.entries");
        assertEquals(1, entries.getKeys(false).size());
        assertEquals("GOLD_INGOT", entries.getString("0.item"));
        assertEquals(0.25, entries.getDouble("0.chance"), 1e-9);
    }

    @Test
    void doesNotProjectUnconsumedNestedTableAmounts() {
        var table = MythicDropTableProjection.project("boss_drops", List.of(
                Map.of("table", "nested", "amount", 2),
                Map.of("table", "nested", "amount-min", 1, "amount-max", 2),
                Map.of("table", "nested", "amount", 1)));
        var entries = table.getConfigurationSection("tables.boss_drops.entries");
        assertEquals(1, entries.getKeys(false).size());
        assertEquals("nested", entries.getString("0.table"));
    }

    @Test
    void doesNotProjectEntriesWhenAnyDropConditionIsUnmapped() {
        var table = MythicDropTableProjection.project("boss_drops", List.of(
                Map.of("item", "DIAMOND", "chance", 1.0,
                        "conditions", List.of("world-is arena", "future-condition{value=x}"))));

        assertTrue(table.getConfigurationSection("tables.boss_drops.entries").getKeys(false).isEmpty());
    }

    @Test
    void omitsUnsafeEntriesWithoutDiscardingSafeEntries() {
        var table = MythicDropTableProjection.project("boss_drops", List.of(
                "DIAMOND 1 1 unknown-option", Map.of("item", "EMERALD", "future-option", true),
                "GOLD_INGOT 1 1"));
        var entries = table.getConfigurationSection("tables.boss_drops.entries");
        assertEquals(1, entries.getKeys(false).size());
        assertEquals("GOLD_INGOT", entries.getString("0.item"));
    }

    @Test
    void parsesExclusiveDashAndToAmountRangesAndFixedAmounts() {
        var table = MythicDropTableProjection.project("ranges", List.of(
                "DIAMOND 1-3 1",
                "EMERALD 2to5 1",
                "GOLD_INGOT 4 1"));

        assertEquals(1, table.getInt("tables.ranges.entries.0.amount-min"));
        assertEquals(2, table.getInt("tables.ranges.entries.0.amount-max"));
        assertEquals(2, table.getInt("tables.ranges.entries.1.amount-min"));
        assertEquals(4, table.getInt("tables.ranges.entries.1.amount-max"));
        assertEquals(4, table.getInt("tables.ranges.entries.2.amount-min"));
        assertEquals(4, table.getInt("tables.ranges.entries.2.amount-max"));
    }

    @Test
    void rejectsEmptyExclusiveRangesSoImporterPreservesThemInsteadOfChangingMeaning() {
        assertNull(MythicDropSpec.parse("DIAMOND 3-3 1"));
        assertNull(MythicDropSpec.parse("DIAMOND 4to2 1"));
    }

    @Test
    void rejectsWholeStandaloneTableWhenAnyTableConditionIsUnmapped() {
        YamlConfiguration source = new YamlConfiguration();
        source.set("Drops", List.of("DIAMOND 1 1"));
        source.set("Conditions", List.of("world-is arena", "future-condition{value=x}"));

        var projected = MythicDropTableProjection.project("boss_drops", source);

        assertTrue(projected.getKeys(true).isEmpty());
    }

    @Test
    void rejectsWholeStandaloneTableWhenAnyTriggerConditionIsUnmapped() {
        YamlConfiguration source = new YamlConfiguration();
        source.set("Drops", List.of("DIAMOND 1 1"));
        source.set("TriggerConditions", List.of("player-kills >=1", "future-trigger"));

        var projected = MythicDropTableProjection.project("boss_drops", source);

        assertTrue(projected.getKeys(true).isEmpty());
    }

    @Test
    void rejectsCaseInsensitiveDuplicateTableFields() {
        for (String key : List.of("Conditions", "TriggerConditions", "Drops", "TotalItems")) {
            YamlConfiguration source = new YamlConfiguration();
            source.set("Drops", List.of("DIAMOND 1 1"));
            source.set(key, List.of("is-player"));
            source.set(key.toLowerCase(java.util.Locale.ROOT), List.of("is-monster"));
            assertTrue(MythicDropTableProjection.project("rewards", source).getKeys(true).isEmpty(), key);
        }
    }

    @Test
    void rejectsBlankAndNonStringTableConditions() {
        for (String key : List.of("Conditions", "TriggerConditions")) {
            for (Object invalid : List.of("", " ", ";", "|", 42)) {
                YamlConfiguration source = new YamlConfiguration();
                source.set("Drops", List.of("DIAMOND 1 1"));
                source.set(key, List.of(invalid));
                assertTrue(MythicDropTableProjection.project("boss_drops", source).getKeys(true).isEmpty());
            }
            YamlConfiguration source = new YamlConfiguration();
            source.set("Drops", List.of("DIAMOND 1 1"));
            source.set(key, java.util.Arrays.asList((Object) null));
            assertTrue(MythicDropTableProjection.project("boss_drops", source).getKeys(true).isEmpty());
        }
    }

    @Test
    void rejectsMalformedEntryConditionsInsteadOfDroppingRestrictions() {
        assertNull(MythicDropSpec.parse("DIAMOND 1 1 ?"));
        for (String key : List.of("conditions", "DropIf", "NoDropIf")) {
            for (Object invalid : List.of("", "?", 42, List.of(""), java.util.Arrays.asList((Object) null))) {
                var entry = new java.util.HashMap<String, Object>();
                entry.put("item", "DIAMOND");
                entry.put(key, invalid);
                var projected = MythicDropTableProjection.project("boss_drops", List.of(entry));
                assertTrue(projected.getConfigurationSection("tables.boss_drops.entries").getKeys(false).isEmpty());
            }
        }
    }
}
