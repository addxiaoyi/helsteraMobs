package dev.helstera.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRuntimeLifecycleAuditTest {

    @Test
    @DisplayName("static skill hooks expose a complete reset path")
    void staticHooksHaveResetPath() throws Exception {
        String extras = Files.readString(Path.of(
                "src/main/java/dev/helstera/ai/skill/SkillExtras.java"));
        String catalog = Files.readString(Path.of(
                "src/main/java/dev/helstera/ai/skill/SkillCatalog.java"));

        assertTrue(extras.contains("public static void resetRuntimeHooks()"));
        for (String field : new String[]{"host = null", "despawner = null",
                "threatLookup = null", "damagePolicy = null", "damageCause = null",
                "itemResolver = null", "audienceDiagnostic = null",
                "potionEffectEndDispatcher = null",
                "streaks = null", "VARS.clear()", "SCOPED_VALUES.clear()",
                "SCORES.clear()", "STANCES.clear()", "GLOBAL_CD.clear()"}) {
            assertTrue(extras.contains(field), "reset must clear " + field);
        }

        assertTrue(catalog.contains("public static void resetRuntimeHooks()"));
        for (String field : new String[]{"summoner = null", "summonGate = null",
                "transformer = null", "dialogueService = null", "pluginRef = null",
                "taskScheduler = null", "skillServiceRef = null", "RUNTIME_WARNINGS.clear()"}) {
            assertTrue(catalog.contains(field), "catalog reset must clear " + field);
        }
    }
}
