package dev.helstera.migration.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DespawnPrecedenceImportTest {
    @TempDir Path root;

    private List<Map<String, Object>> convert(String fields, String global) throws Exception {
        Files.createDirectories(root.resolve("Mobs"));
        Files.writeString(root.resolve("config-mobs.yml"),
                "Configuration:\n  DefaultMobOptions:\n    Despawn: " + global + "\n");
        Path file = root.resolve("Mobs/probe.yml");
        Files.writeString(file, "Probe:\n  Type: COW\n" + fields.indent(2));
        return new MythicMobsImporter().convert(file, root);
    }

    private YamlConfiguration mob(List<Map<String, Object>> entries) {
        return YamlConfiguration.loadConfiguration(new StringReader(entries.stream()
                .filter(e -> "mobs/mythic_probe.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow().get("content").toString()));
    }

    @Test void persistentOverridesEveryExplicitMode() throws Exception {
        for (String mode : List.of("NORMAL", "CHUNK", "NEVER", "PERSISTENT", "NPC")) {
            for (String prefix : List.of("", "Options:\n  ")) {
                String fields = prefix + "Despawn: " + mode + "\n"
                        + (prefix.isEmpty() ? "" : "  ") + "Persistent: true\n";
                var cfg = mob(convert(fields, "false"));
                assertEquals("PERSISTENT", cfg.getString("mob.despawn"), fields);
                assertTrue(cfg.getBoolean("persistent"));
            }
        }
    }

    @Test void optionsFalseOverridesRootTrueWithoutOverridingDespawn() throws Exception {
        for (String mode : List.of("NORMAL", "CHUNK", "NEVER", "PERSISTENT", "NPC")) {
            var cfg = mob(convert("Persistent: true\nDespawn: NPC\nOptions:\n  Persistent: false\n  Despawn: " + mode, "false"));
            assertEquals(mode, cfg.getString("mob.despawn"));
            assertFalse(cfg.getBoolean("persistent"));
        }
    }

    @Test void malformedPersistentInheritsRootBooleanAndIsPreserved() throws Exception {
        for (String value : List.of("maybe", "'true'", "'false'", "1", "0", "[true]", "{value: true}")) {
            var entries = convert("Persistent: true\nOptions:\n  Despawn: CHUNK\n  Persistent: " + value, "true");
            var cfg = mob(entries);
            assertEquals("PERSISTENT", cfg.getString("mob.despawn"), value);
            assertTrue(cfg.getBoolean("persistent"));
            assertTrue(cfg.contains("mob.unsupported.Options.Persistent"));
            assertTrue(entries.stream().anyMatch(e -> "Options.Persistent".equals(e.get("source-key"))
                    && "unsupported".equals(e.get("status")) && "WARN".equals(e.get("severity"))));
        }
    }

    @Test void quotedBooleansAndModeNamesAreInvalidGlobalDefaults() throws Exception {
        for (String value : List.of("CHUNK", "NEVER", "PERSISTENT", "NPC", "'false'", "'true'", "0", "1", "[false]")) {
            var cfg = mob(convert("", value));
            assertEquals("NORMAL", cfg.getString("mob.despawn"), value);
            assertTrue(cfg.contains("mob.unsupported.DefaultMobOptions.Despawn"));
        }
        assertEquals("NEVER", mob(convert("", "false")).getString("mob.despawn"));
        assertEquals("NORMAL", mob(convert("", "true")).getString("mob.despawn"));
    }

    @Test void nativeDespawnAliasesDoNotIncludeNumbersOrTrimmedText() throws Exception {
        for (String value : List.of("0", "1", "'0'", "'1'", "' CHUNK '", "unknown", "[NEVER]")) {
            var cfg = mob(convert("Despawn: NEVER\nOptions:\n  Despawn: " + value, "false"));
            assertEquals("NORMAL", cfg.getString("mob.despawn"), value);
            assertTrue(cfg.contains("mob.unsupported.Options.Despawn"));
        }
        assertEquals("NEVER", mob(convert("Despawn: 'no'", "true")).getString("mob.despawn"));
        assertEquals("NORMAL", mob(convert("Despawn: 'yes'", "false")).getString("mob.despawn"));
    }

    @Test void invalidOverriddenValuesAreStillRetainedWithWarnings() throws Exception {
        var entries = convert("Despawn: bad\nPersistent: 'true'\nOptions:\n  Despawn: CHUNK\n  Persistent: true", "CHUNK");
        var cfg = mob(entries);
        assertEquals("PERSISTENT", cfg.getString("mob.despawn"));
        for (String key : List.of("Despawn", "Persistent", "DefaultMobOptions.Despawn")) {
            assertTrue(cfg.contains("mob.unsupported." + key), key);
        }
        assertEquals(3, entries.stream().filter(e -> "unsupported".equals(e.get("status"))
                && "WARN".equals(e.get("severity"))).count());
    }

    @Test void nativeConfigDirectoryOverridesLegacyRootFile() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/config-mobs.yml"),
                "Configuration:\n  DefaultMobOptions:\n    Despawn: false\n    PreventOtherDrops: true\n");
        var cfg = mob(convert("", "true"));
        assertEquals("NEVER", cfg.getString("mob.despawn"));
        assertTrue(cfg.getBoolean("entity.prevent-other-drops"));
    }

    @Test void malformedNativeConfigDoesNotSilentlyUseLegacyFile() throws Exception {
        Files.createDirectories(root.resolve("config"));
        Files.writeString(root.resolve("config/config-mobs.yml"), "Configuration: [invalid");
        var entries = convert("", "false");
        assertEquals("NORMAL", mob(entries).getString("mob.despawn"));
        assertTrue(entries.stream().anyMatch(e -> "Configuration.DefaultMobOptions".equals(e.get("source-key"))
                && "unsupported".equals(e.get("status")) && "WARN".equals(e.get("severity"))));
    }
}
