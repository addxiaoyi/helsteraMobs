package dev.helstera.plugin;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MythicItemOptionsTest {

    @Test
    void appliesPaperMetaFieldsAndPerItemPersistentMarkers() {
        var setters = new LinkedHashMap<String, Object>();
        var meta = metaProxy(setters);
        var firstTags = new LinkedHashMap<String, Object>();
        var secondTags = new LinkedHashMap<String, Object>();
        var config = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                repairable: false
                repair-cost: 7
                glint: true
                stack-size: 16
                fire-resistant: true
                generate-uuid: true
                prevent-stacking: true
                generate-timestamp: true
                """));

        HelsteraPlugin.applyMythicItemOptions(meta, config, pdcProxy(firstTags), ignored -> { });
        HelsteraPlugin.applyMythicItemOptions(meta, config, pdcProxy(secondTags), ignored -> { });

        assertEquals(Integer.MAX_VALUE, setters.get("setRepairCost"),
                "Repairable=false must override the configured RepairCost");
        assertEquals(Boolean.TRUE, setters.get("setEnchantmentGlintOverride"));
        assertEquals(16, setters.get("setMaxStackSize"));
        assertEquals(Boolean.TRUE, setters.get("setFireResistant"));
        assertNotEquals(firstTags.get("helstera:mythic_generated_uuid"),
                secondTags.get("helstera:mythic_generated_uuid"));
        assertNotEquals(firstTags.get("helstera:mythic_prevent_stacking"),
                secondTags.get("helstera:mythic_prevent_stacking"));
        assertTrue(firstTags.get("helstera:mythic_generated_uuid") instanceof String);
        assertTrue(firstTags.get("helstera:mythic_generated_timestamp") instanceof Long);
    }

    @Test
    void appliesRepairCostWhenRepairIsEnabledAndRejectsInvalidStackSize() {
        var setters = new LinkedHashMap<String, Object>();
        var warnings = new java.util.ArrayList<String>();
        var config = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                repairable: true
                repair-cost: 7
                stack-size: 100
                """));

        HelsteraPlugin.applyMythicItemOptions(metaProxy(setters), config, null, warnings::add);

        assertEquals(7, setters.get("setRepairCost"));
        assertFalse(setters.containsKey("setMaxStackSize"));
        assertTrue(warnings.stream().anyMatch(message -> message.contains("stack-size")));
    }

    private static Repairable metaProxy(Map<String, Object> setters) {
        return (Repairable) Proxy.newProxyInstance(Repairable.class.getClassLoader(),
                new Class<?>[]{Repairable.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("set") && args != null && args.length == 1) {
                        setters.put(method.getName(), args[0]);
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    return null;
                });
    }

    private static PersistentDataContainer pdcProxy(Map<String, Object> values) {
        return (PersistentDataContainer) Proxy.newProxyInstance(PersistentDataContainer.class.getClassLoader(),
                new Class<?>[]{PersistentDataContainer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("set") && args != null && args.length == 3) {
                        values.put(((NamespacedKey) args[0]).toString(), args[2]);
                    }
                    return null;
                });
    }
}
