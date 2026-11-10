package dev.helstera.plugin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class MobTemplateIndexTest {

    @Test
    void resolvesDirectAndTransitiveLineageAndKeepsUnknownTemplateNames() {
        YamlConfiguration child = config("mythic_parent", "UnregisteredTemplate");
        YamlConfiguration parent = config("mythic_ancestor");
        MobTemplateIndex index = new MobTemplateIndex(Map.of(
                "mythic_child", child,
                "mythic_parent", parent)::get);

        assertEquals(java.util.Set.of("mythic_parent", "mythic_unregisteredtemplate", "mythic_ancestor"),
                java.util.Set.copyOf(index.templatesOf(entity("mythic_child"))));
    }

    @Test
    void missingMobConfigurationAndCyclesFailClosed() {
        MobTemplateIndex missing = new MobTemplateIndex(ignored -> null);
        assertEquals(java.util.List.of(), missing.templatesOf(entity("mythic_missing")));

        YamlConfiguration child = config("mythic_parent");
        YamlConfiguration parent = config("mythic_child");
        MobTemplateIndex cyclic = new MobTemplateIndex(Map.of(
                "mythic_child", child,
                "mythic_parent", parent)::get);
        assertEquals(java.util.List.of(), cyclic.templatesOf(entity("mythic_child")));
    }

    @Test
    void invalidateReloadsChangedConfiguration() {
        YamlConfiguration child = config("mythic_before");
        MobTemplateIndex index = new MobTemplateIndex(Map.of("mythic_child", child)::get);
        Entity entity = entity("mythic_child");

        assertEquals(java.util.Set.of("mythic_before"),
                java.util.Set.copyOf(index.templatesOf(entity)));
        child.set("mob.templates", java.util.List.of("mythic_after"));
        assertEquals(java.util.Set.of("mythic_before"),
                java.util.Set.copyOf(index.templatesOf(entity)), "cached values change only after reload");
        index.invalidate();
        assertEquals(java.util.Set.of("mythic_after"),
                java.util.Set.copyOf(index.templatesOf(entity)));
    }

    private static YamlConfiguration config(String... templates) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("mob.templates", java.util.List.of(templates));
        return config;
    }

    private static Entity entity(String mobId) {
        PersistentDataContainer data = (PersistentDataContainer) java.lang.reflect.Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(), new Class<?>[]{PersistentDataContainer.class},
                (proxy, method, arguments) -> method.getName().equals("get") ? mobId : null);
        return (Entity) java.lang.reflect.Proxy.newProxyInstance(Entity.class.getClassLoader(),
                new Class<?>[]{Entity.class}, (proxy, method, arguments) ->
                        method.getName().equals("getPersistentDataContainer") ? data : null);
    }
}
