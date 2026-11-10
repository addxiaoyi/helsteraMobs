package dev.helstera.migration.importer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MythicMobTemplateResolverTest {

    private static Path write(Path directory, String name, String yaml) throws Exception {
        Path path = directory.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, yaml);
        return path;
    }

    private static ConfigurationSection section(String yaml, String id) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config.getConfigurationSection(id);
    }

    @Test
    void mergesTemplateChainsListsNestedMapsEquipmentModifiersAndTrades(@TempDir Path root) throws Exception {
        Path mobs = root.resolve("Mobs");
        write(mobs, "base.yml", """
                Base:
                  Type: ZOMBIE
                  Health: 10
                  Options:
                    NoAI: true
                    PreventOtherDrops: true
                  Skills: [base]
                  Equipment:
                    - IRON_SWORD HAND
                    - LEATHER_HELMET HEAD
                  DamageModifiers:
                    - FIRE 0.5
                    - FALL 2
                  Trades: [first, second]
                Chain:
                  Template: Base
                  Health: 20
                  Skills: [chain]
                """);
        write(mobs, "later.yml", """
                Later:
                  Type: SKELETON
                  Health: 30
                  Options:
                    NoAI: false
                  Skills: [later]
                  Equipment:
                    - DIAMOND_SWORD HAND
                  DamageModifiers:
                    - FIRE 0.7
                  Trades: [replacement]
                """);
        MythicMobTemplateResolver resolver = MythicMobTemplateResolver.load(mobs);
        ConfigurationSection child = section("""
                Child:
                  Template: [Chain, Later]
                  Health: 50
                  Options:
                    PreventOtherDrops: false
                  Skills: [child]
                """, "Child");

        MythicMobTemplateResolver.Resolution resolution = resolver.resolve("Child", child);
        Map<String, Object> fields = resolution.effectiveFields();

        assertTrue(resolution.resolved());
        assertEquals(50, fields.get("Health"));
        assertEquals("SKELETON", fields.get("Type"));
        assertEquals(List.of("base", "chain", "later", "child"), fields.get("Skills"));
        Map<?, ?> options = (Map<?, ?>) fields.get("Options");
        assertEquals(false, options.get("NoAI"));
        assertEquals(false, options.get("PreventOtherDrops"));
        assertEquals(List.of("LEATHER_HELMET HEAD", "DIAMOND_SWORD HAND"), fields.get("Equipment"));
        assertEquals(List.of("FALL 2", "FIRE 0.7"), fields.get("DamageModifiers"));
        assertEquals(List.of("replacement", "second"), fields.get("Trades"));
        assertEquals(List.of("Chain", "Later"), resolution.directTemplates());
    }

    @Test
    void excludeRemovesInheritedFieldsButKeepsOwnDeclarations(@TempDir Path root) throws Exception {
        Path mobs = root.resolve("Mobs");
        write(mobs, "base.yml", """
                Base:
                  Health: 20
                  Options:
                    NoAI: true
                    PreventOtherDrops: true
                """);
        MythicMobTemplateResolver resolver = MythicMobTemplateResolver.load(mobs);
        ConfigurationSection child = section("""
                Child:
                  Template: Base
                  Exclude: [Health, Options]
                  Health: 8
                  Options:
                    NoAI: false
                """, "Child");

        MythicMobTemplateResolver.Resolution resolution = resolver.resolve("Child", child);

        assertTrue(resolution.resolved());
        assertEquals(8, resolution.effectiveFields().get("Health"));
        assertEquals(Map.of("NoAI", false), resolution.effectiveFields().get("Options"));
        assertFalse(((Map<?, ?>) resolution.effectiveFields().get("Options")).containsKey("PreventOtherDrops"));
    }

    @Test
    void missingAmbiguousAndCyclicReferencesFailWithoutPartialInheritance(@TempDir Path root) throws Exception {
        Path mobs = root.resolve("Mobs");
        write(mobs, "templates.yml", """
                Base:
                  Health: 20
                CycleA:
                  Template: CycleB
                CycleB:
                  Template: CycleA
                """);
        write(mobs, "duplicates/one.yml", "Duplicate:\n  Health: 1\n");
        write(mobs, "duplicates/two.yml", "Duplicate:\n  Health: 2\n");
        MythicMobTemplateResolver resolver = MythicMobTemplateResolver.load(mobs);

        var missing = resolver.resolve("MissingChild", section("""
                MissingChild:
                  Template: [Base, Absent]
                  Damage: 5
                """, "MissingChild"));
        var ambiguous = resolver.resolve("AmbiguousChild", section("""
                AmbiguousChild:
                  Template: Duplicate
                """, "AmbiguousChild"));
        var cycle = resolver.resolve("CycleA", section("""
                CycleA:
                  Template: CycleB
                """, "CycleA"));

        assertFalse(missing.resolved());
        assertTrue(missing.error().contains("Absent"));
        assertEquals(5, missing.effectiveFields().get("Damage"));
        assertFalse(missing.effectiveFields().containsKey("Health"));
        assertFalse(ambiguous.resolved());
        assertTrue(ambiguous.error().contains("ambiguous"));
        assertFalse(ambiguous.effectiveFields().containsKey("Health"));
        assertFalse(cycle.resolved());
        assertTrue(cycle.error().contains("cycle"));
    }

    @Test
    void rejectsMalformedTemplateAndExcludeSyntax(@TempDir Path root) throws Exception {
        MythicMobTemplateResolver resolver = MythicMobTemplateResolver.load(root.resolve("Mobs"));
        var invalidTemplate = resolver.resolve("InvalidTemplate", section("""
                InvalidTemplate:
                  Template: 42
                  Health: 7
                """, "InvalidTemplate"));
        var emptyExclude = resolver.resolve("EmptyExclude", section("""
                EmptyExclude:
                  Exclude: []
                """, "EmptyExclude"));

        assertFalse(invalidTemplate.resolved());
        assertNotNull(invalidTemplate.effectiveFields());
        assertTrue(invalidTemplate.effectiveFields().containsKey("Template"));
        assertFalse(emptyExclude.resolved());
        assertTrue(emptyExclude.error().contains("at least one"));
    }

    @Test
    void rejectsMalformedYamlWhileBuildingCrossFileIndex(@TempDir Path root) throws Exception {
        Path mobs = root.resolve("Mobs");
        write(mobs, "broken.yml", "Broken:\n  Template: [unterminated\n");

        assertThrows(IllegalArgumentException.class, () -> MythicMobTemplateResolver.load(mobs));
    }
}
