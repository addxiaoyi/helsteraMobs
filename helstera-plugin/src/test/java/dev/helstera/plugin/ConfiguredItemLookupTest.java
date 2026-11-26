package dev.helstera.plugin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfiguredItemLookupTest {
    @Test void materialDoesNotBecomeAnAliasForCustomItemsUsingThatMaterial() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                items:
                  grantgem:
                    mythic-source-id: GrantGem
                    id: DIAMOND
                    custom-model-data: 4321
                  secondgem:
                    id: DIAMOND
                    custom-model-data: 5432
                """);
        var items = yaml.getConfigurationSection("items");
        assertNull(HelsteraPlugin.configuredItemSection(items, "DIAMOND"));
        assertNull(HelsteraPlugin.configuredItemSection(items, "minecraft:diamond"));
        assertSame(items.getConfigurationSection("grantgem"),
                HelsteraPlugin.configuredItemSection(items, "GrantGem"));
        assertSame(items.getConfigurationSection("secondgem"),
                HelsteraPlugin.configuredItemSection(items, "SECONDGEM"));
    }

    @Test void sourceIdentifiersAndNormalizedKeysRemainAvailable() {
        var items = new YamlConfiguration();
        var item = items.createSection("imported_gem");
        item.set("mythic-source-id", "Gem-Reward");
        item.set("id", "DIAMOND");
        assertSame(item, HelsteraPlugin.configuredItemSection(items, "Gem-Reward"));
        assertSame(item, HelsteraPlugin.configuredItemSection(items, "imported:gem"));
        assertSame(item, HelsteraPlugin.configuredItemSection(items, "imported.gem"));
        assertNull(HelsteraPlugin.configuredItemSection(items, "gem-reward"));
        assertNull(HelsteraPlugin.configuredItemSection(items, null));
    }
}
