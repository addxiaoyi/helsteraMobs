package dev.helstera.plugin;

import dev.helstera.ai.skill.AuraRegistry;
import dev.helstera.ai.skill.SkillService;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuraSkillReloadTest {
    @TempDir Path data;

    @Test
    void reloadDiscardsAuraCallbacksAndClosesTimersBeforeReplacingSkillDefinitions() throws Exception {
        Files.writeString(data.resolve("skills.yml"), "skills:\n  after_reload:\n    on-decision: []\n");
        HelsteraPlugin plugin = mock(HelsteraPlugin.class, CALLS_REAL_METHODS);
        doReturn(data.toFile()).when(plugin).getDataFolder();
        doReturn(Logger.getAnonymousLogger()).when(plugin).getLogger();
        SkillService skills = mock(SkillService.class);
        AuraRegistry registry = new AuraRegistry(() -> 1, ignored -> true);
        UUID target = UUID.randomUUID();
        AtomicInteger ended = new AtomicInteger(), cleaned = new AtomicInteger();
        registry.applyTracked(target, null, "ward", "", 20, 1, true, true, false,
                false, false, 1, null, snapshot -> ended.incrementAndGet(), true, Set.of(), 0, cleaned::incrementAndGet);
        put(plugin, "skillService", skills);
        put(plugin, "auraRegistry", registry);
        doAnswer(invocation -> {
            assertFalse(registry.hasAura(target, "ward"));
            assertEquals(1, cleaned.get(), "the timer must close before loading replacement definitions");
            assertEquals(0, ended.get(), "reload must discard old end callbacks");
            return null;
        }).when(skills).loadSkills(any(ConfigurationSection.class));
        assertTrue(plugin.reloadSkills("test"));
        verify(skills).loadSkills(any(ConfigurationSection.class));
        assertFalse(registry.hasAura(target, "ward"));
        assertEquals(1, cleaned.get());
        assertEquals(0, ended.get());
    }

    private static void put(HelsteraPlugin plugin, String name, Object value) throws Exception {
        Field field = HelsteraPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
