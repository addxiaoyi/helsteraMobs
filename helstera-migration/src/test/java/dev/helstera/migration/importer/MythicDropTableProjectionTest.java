package dev.helstera.migration.importer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MythicDropTableProjectionTest {
    @Test
    void projectsSafeEntriesAndSkipsExternalEntries() {
        var table = MythicDropTableProjection.project("boss_drops", List.of(
                "DIAMOND 2-4 25% ?world-is arena",
                Map.of("table", "nested", "amount-min", 1, "amount-max", 2,
                        "chance-expression", "luck-based", "conditions", List.of("permission loot.vip")),
                "MMOItems:SWORD 1 1.0"));

        assertEquals("DIAMOND", table.getString("tables.boss_drops.entries.0.item"));
        assertEquals(2, table.getInt("tables.boss_drops.entries.0.amount-min"));
        assertEquals(4, table.getInt("tables.boss_drops.entries.0.amount-max"));
        assertEquals(0.25, table.getDouble("tables.boss_drops.entries.0.chance"), 1e-9);
        assertEquals("world-is arena",
                table.getStringList("tables.boss_drops.entries.0.conditions").get(0));
        assertEquals("nested", table.getString("tables.boss_drops.entries.1.table"));
        assertEquals("luck-based", table.getString("tables.boss_drops.entries.1.chance-expression"));
        assertEquals(2, table.getConfigurationSection("tables.boss_drops.entries").getKeys(false).size());
        assertTrue(table.getString("tables.boss_drops.entries.0.item").equals("DIAMOND"));
    }
}
