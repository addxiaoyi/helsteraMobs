package dev.helstera.plugin;

import dev.helstera.api.integration.Capability;
import dev.helstera.api.integration.IntegrationAdapter;
import dev.helstera.api.integration.IntegrationRegistry;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.HorseInventory;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class EquipmentItemResolutionTest {
    public static final class ItemAdapter implements IntegrationAdapter {
        private final ItemStack item;
        ItemAdapter(ItemStack item) { this.item = item; }
        public ItemStack resolve(String id) { return "stone:2".equals(id) ? item : null; }
        public String pluginName() { return "test"; }
        public String supportedVersions() { return "test"; }
        public Set<Capability> capabilities() { return Set.of(Capability.CUSTOM_ITEM_RESOLVE); }
        public boolean isConnected() { return true; }
        public String statusReport() { return ""; }
        public boolean enable(org.bukkit.plugin.Plugin host) { return true; }
        public void disable() { }
    }

    @Test
    void equipmentOptionsPreferResolvedCustomItemsAndRejectUnknownIds() throws Exception {
        HelsteraPlugin plugin = mock(HelsteraPlugin.class, CALLS_REAL_METHODS);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        ItemStack custom = mock(ItemStack.class);
        when(custom.getType()).thenReturn(Material.DIAMOND);
        IntegrationRegistry registry = mock(IntegrationRegistry.class);
        when(registry.all()).thenReturn(List.of(new ItemAdapter(custom)));
        Field integrations = HelsteraPlugin.class.getDeclaredField("integrations");
        integrations.setAccessible(true);
        integrations.set(plugin, registry);

        LivingEntity entity = mock(LivingEntity.class);
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(entity.getEquipment()).thenReturn(equipment);
        Method apply = HelsteraPlugin.class.getDeclaredMethod("applyEntityEquipment",
                LivingEntity.class, ConfigurationSection.class);
        apply.setAccessible(true);
        apply.invoke(plugin, entity, yaml("items: {main-hand: 'stone:2', off-hand: 'stone:unknown'}"));
        verify(equipment).setItem(EquipmentSlot.HAND, custom);
        verify(equipment, never()).setItem(eq(EquipmentSlot.OFF_HAND), any());

        try (var vanilla = mockConstruction(ItemStack.class,
                (stack, context) -> when(stack.getType()).thenReturn(Material.STONE))) {
            apply.invoke(plugin, entity, yaml("items: {helmet: 'minecraft:stone:3'}"));
            verify(equipment).setItem(EquipmentSlot.HEAD, vanilla.constructed().get(0));
            org.junit.jupiter.api.Assertions.assertEquals(1, vanilla.constructed().size());
        }

        Horse horse = mock(Horse.class);
        HorseInventory horseInventory = mock(HorseInventory.class);
        when(horse.getInventory()).thenReturn(horseInventory);
        apply.invoke(plugin, horse, yaml("horse-armor: 'stone:2'"));
        verify(horseInventory).setArmor(custom);

        ArmorStand stand = mock(ArmorStand.class);
        EntityEquipment standEquipment = mock(EntityEquipment.class);
        when(stand.getEquipment()).thenReturn(standEquipment);
        apply.invoke(plugin, stand, yaml("armor-stand-item-head: 'stone:2'"));
        verify(standEquipment).setItem(EquipmentSlot.HEAD, custom);
    }

    @Test
    void invalidConfiguredMaterialNameBlocksAdapterAndEquipmentFallback() throws Exception {
        HelsteraPlugin plugin = mock(HelsteraPlugin.class, CALLS_REAL_METHODS);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        Field items = HelsteraPlugin.class.getDeclaredField("customItems");
        items.setAccessible(true);
        items.set(plugin, yaml("stone: {template: missing, material: DIAMOND}"));
        IntegrationRegistry registry = mock(IntegrationRegistry.class);
        when(registry.all()).thenReturn(List.of(new ItemAdapter(mock(ItemStack.class))));
        Field integrations = HelsteraPlugin.class.getDeclaredField("integrations");
        integrations.setAccessible(true);
        integrations.set(plugin, registry);
        LivingEntity entity = mock(LivingEntity.class);
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(entity.getEquipment()).thenReturn(equipment);
        Method apply = HelsteraPlugin.class.getDeclaredMethod("applyEntityEquipment",
                LivingEntity.class, ConfigurationSection.class);
        apply.setAccessible(true);
        try (var construction = mockConstruction(ItemStack.class)) {
            apply.invoke(plugin, entity, yaml("items: {main-hand: STONE}"));
            verify(equipment, never()).setItem(any(), any());
            verify(registry, never()).all();
            assertTrue(construction.constructed().isEmpty());
        }
    }

    private static YamlConfiguration yaml(String source) {
        return YamlConfiguration.loadConfiguration(new StringReader(source));
    }

    @Test
    void configuredItemTemplatesFailClosedAndValidTemplatesInherit() throws Exception {
        HelsteraPlugin plugin = mock(HelsteraPlugin.class, CALLS_REAL_METHODS);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        Field items = HelsteraPlugin.class.getDeclaredField("customItems");
        items.setAccessible(true);
        items.set(plugin, yaml("""
                base: {material: DIAMOND}
                valid: {template: base, amount: 2}
                missing: {template: absent, material: STONE}
                blank: {template: ' ', material: STONE}
                numeric: {template: 42, material: STONE}
                preserved: {unsupported: {template: 42}, material: STONE}
                cycle_a: {template: cycle_b, material: STONE}
                cycle_b: {template: cycle_a, material: STONE}
                """));
        Method resolve = HelsteraPlugin.class.getDeclaredMethod("resolveConfiguredItem", String.class);
        resolve.setAccessible(true);
        try (var constructed = mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.clone()).thenReturn(stack);
            when(stack.getAmount()).thenReturn(1);
        })) {
            assertNull(resolve.invoke(plugin, "missing"));
            assertNull(resolve.invoke(plugin, "blank"));
            assertNull(resolve.invoke(plugin, "numeric"));
            assertNull(resolve.invoke(plugin, "preserved"));
            assertNull(resolve.invoke(plugin, "cycle_a"));
            assertTrue(constructed.constructed().isEmpty());
            ItemStack inherited = (ItemStack) resolve.invoke(plugin, "valid");
            assertNotNull(inherited);
            assertEquals(1, constructed.constructed().size());
            verify(inherited).setAmount(2);
        }
    }

    @Test
    void importedInvalidTemplateCannotProduceBaseItem(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Items")).resolve("items.yml");
        Files.writeString(source, "broken:\n  Id: DIAMOND\n  Template: 42\n");
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        var output = entries.stream().filter(e -> "items.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        var imported = yaml(String.valueOf(output.get("content")));
        assertEquals(42, imported.getInt("items.broken.unsupported.template"));
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))));
        HelsteraPlugin plugin = mock(HelsteraPlugin.class, CALLS_REAL_METHODS);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        Field items = HelsteraPlugin.class.getDeclaredField("customItems");
        items.setAccessible(true);
        items.set(plugin, imported.getConfigurationSection("items"));
        Method resolve = HelsteraPlugin.class.getDeclaredMethod("resolveConfiguredItem", String.class);
        resolve.setAccessible(true);
        try (var constructed = mockConstruction(ItemStack.class)) {
            assertNull(resolve.invoke(plugin, "broken"));
            assertTrue(constructed.constructed().isEmpty());
        }
    }
}
