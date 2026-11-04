package dev.helstera.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginRuntimeHookCleanupAuditTest {

    @Test
    @DisplayName("plugin disable clears all static skill runtime hooks")
    void disableClearsStaticSkillHooks() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/helstera/plugin/HelsteraPlugin.java"));
        int start = source.indexOf("public void onDisable()");
        assertTrue(start >= 0, "onDisable method is missing");
        String body = source.substring(start);
        assertTrue(body.contains("SkillExtras.resetRuntimeHooks()"));
        assertTrue(body.contains("SkillCatalog.resetRuntimeHooks()"));
        assertTrue(body.contains("SkillTriggers.configureProjectilePlugin(null)"));
        assertTrue(body.contains("Factions.install(null)"));
    }

    @Test
    @DisplayName("persistent mobs retain their chunk for restart restoration")
    void persistentMobRetentionKeepsChunkLoaded() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/helstera/plugin/HelsteraPlugin.java"));
        int start = source.indexOf("private void markPersistentMob(");
        assertTrue(start >= 0, "persistent mob marker method is missing");
        int end = source.indexOf("\n    private org.bukkit.NamespacedKey persistentMobIdKey", start);
        assertTrue(end > start, "persistent mob marker method boundary is missing");
        String body = source.substring(start, end);
        assertTrue(body.contains("PERSISTENT"), "retention must be limited to persistent mobs");
        assertTrue(body.contains("setForceLoaded(true)"),
                "restart restoration needs the PDC-bearing chunk to remain loaded");
        assertTrue(body.contains("persistentMobIdKey()"),
                "chunk retention must remain paired with the stable mob marker");
    }

    @Test
    @DisplayName("persistent mob death releases retained chunk ownership")
    void persistentMobDeathReleasesRetainedChunk() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/helstera/plugin/HelsteraPlugin.java"));
        int death = source.indexOf("public void onConfirmedEntityDeath(");
        assertTrue(death >= 0, "death handler is missing");
        int bodyEnd = source.indexOf("\n    /**", death);
        assertTrue(bodyEnd > death, "death handler boundary is missing");
        String body = source.substring(death, bodyEnd);
        assertTrue(body.contains("releasePersistentMobChunk(e.getEntity())"),
                "confirmed death must release the force-loaded chunk");

        int release = source.indexOf("private void releasePersistentMobChunk(");
        assertTrue(release >= 0, "persistent chunk release helper is missing");
        int releaseEnd = source.indexOf("\n    private org.bukkit.NamespacedKey persistentMobIdKey", release);
        assertTrue(releaseEnd > release, "persistent chunk release helper boundary is missing");
        String releaseBody = source.substring(release, releaseEnd);
        assertTrue(releaseBody.contains("setForceLoaded(false)"),
                "release helper must clear force loading");
        assertTrue(releaseBody.contains("managedPersistent")
                        && releaseBody.contains("if (!managedPersistent) return"),
                "ordinary entity deaths must not release another plugin's force-loaded chunk");
        assertTrue(releaseBody.contains("PERSISTENT"),
                "release helper must retain a chunk while another persistent mob uses it");
    }
}
