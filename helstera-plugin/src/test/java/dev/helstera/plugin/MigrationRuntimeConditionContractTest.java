package dev.helstera.plugin;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/** Cross-module contract for translated conditions and mechanics used by migration tests. */
class MigrationRuntimeConditionContractTest {

    @Test
    @DisplayName("migrated nearest mob targeter executes against the nearest non-player")
    void migratedNearestMobSelectsTarget(@TempDir Path root) throws Exception {
        Path source = root.resolve("Skills/target.yml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                skills:
                  target:
                    Skills:
                      - damage{amount=5} @Nearest_Mob
                      - damage{amount=7} @living{filter=players} > @Nearest_Mob
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow().get("content"));
        var actions = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml))
                .getStringList("skills.target.on-decision");
        String action = actions.get(0);

        var world = mock(org.bukkit.World.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var player = mock(org.bukkit.entity.Player.class);
        var near = mock(org.bukkit.entity.LivingEntity.class);
        var far = mock(org.bukkit.entity.LivingEntity.class);
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var origin = new org.bukkit.Location(world, 0, 64, 0);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(origin);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(player.getLocation()).thenReturn(new org.bukkit.Location(world, 1, 64, 0));
        when(near.getLocation()).thenReturn(new org.bukkit.Location(world, 2, 64, 0));
        when(far.getLocation()).thenReturn(new org.bukkit.Location(world, 5, 64, 0));
        when(near.getWorld()).thenReturn(world);
        when(near.getMaxHealth()).thenReturn(20.0);
        when(near.getHealth()).thenReturn(20.0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, null, 1, -1, 0, "test", null, null,
                List.of(player, far, near), origin, null);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        String key = skills.bindAction(action);
        assertNotNull(key, action);
        assertTrue(registry.runAction(key, context));
        verify(near).damage(5.0, caster);
        verify(far, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
        verify(player, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
        String chained = skills.bindAction(actions.get(1));
        assertNotNull(chained, actions.get(1));
        assertTrue(registry.runAction(chained, context));
        verify(near, never()).damage(eq(7.0), any(org.bukkit.entity.Entity.class));
        var playerContext = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, player, 1, 1, 0, "test", null, null, List.of(player), origin, null);
        String filteredContext = skills.bindAction("damage{amount=8} @Target{} > @living{filter=mob}");
        assertNotNull(filteredContext);
        assertTrue(registry.runAction(filteredContext, playerContext));
        verify(player, never()).damage(eq(8.0), any(org.bukkit.entity.Entity.class));
        for (String separator : List.of(">", " >", "> ")) {
            String compactChain = skills.bindAction(
                    "damage{amount=9} @Target{}" + separator + "@living{filter=mob}");
            assertNotNull(compactChain);
            assertTrue(registry.runAction(compactChain, playerContext));
        }
        verify(player, never()).damage(eq(9.0), any(org.bukkit.entity.Entity.class));
    }

    private static final Pattern EXPECTED_TRANSLATION = Pattern.compile(
            "assertEquals\\(\\\"([^\\\"]+)\\\"\\s*,\\s*t\\(");
    private static final Pattern EXPECTED_ACTION = Pattern.compile(
            "actions\\.contains\\(\\\"([^\\\"]+)\\\"\\)|"
                    + "assertEquals\\(List\\.of\\(\\\"([^\\\"]+)\\\"");
    private static final Pattern JAVA_STRING = Pattern.compile("\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && current != null; i++) {
            if (Files.isDirectory(current.resolve("helstera-migration"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static Set<String> translatedConditions() throws IOException {
        Path source = repoRoot().resolve(
                "helstera-migration/src/test/java/dev/helstera/migration/importer/MmConditionsTest.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        var matcher = EXPECTED_TRANSLATION.matcher(text);
        Set<String> conditions = new LinkedHashSet<>();
        while (matcher.find()) conditions.add(matcher.group(1));
        return conditions;
    }

    private static Set<String> translatedActions() throws IOException {
        Path source = repoRoot().resolve(
                "helstera-migration/src/test/java/dev/helstera/migration/importer/ImportersTest.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        var matcher = EXPECTED_ACTION.matcher(text);
        Set<String> actions = new LinkedHashSet<>();
        while (matcher.find()) actions.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        for (String body : actionListBodies(text)) {
            var strings = JAVA_STRING.matcher(body);
            while (strings.find()) {
                actions.add(strings.group(1).replace("\\\\", "\\").replace("\\\"", "\""));
            }
        }
        return actions;
    }

    /** Extracts List.of bodies with a small scanner so escaped quotes cannot trigger regex backtracking. */
    private static List<String> actionListBodies(String source) {
        List<String> bodies = new ArrayList<>();
        String marker = "assertEquals(List.of(";
        int from = 0;
        while ((from = source.indexOf(marker, from)) >= 0) {
            int bodyStart = from + marker.length();
            boolean quoted = false;
            boolean escaped = false;
            int close = -1;
            for (int i = bodyStart; i < source.length(); i++) {
                char c = source.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (quoted && c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    quoted = !quoted;
                    continue;
                }
                if (!quoted && c == ')' && source.startsWith(",", i + 1)) {
                    close = i;
                    break;
                }
            }
            if (close >= 0) {
                String tail = source.substring(close, Math.min(source.length(), close + 220));
                if (tail.contains("target.getStringList(\"skills.")
                        && (tail.contains(".on-decision") || tail.contains(".triggers."))) {
                    bodies.add(source.substring(bodyStart, close));
                }
                from = close + 2;
            } else {
                break;
            }
        }
        return bodies;
    }

    @Test
    @DisplayName("all canonical migration conditions bind through the runtime SkillService")
    void everyTranslatedConditionBindsAtRuntime() throws IOException {
        Set<String> conditions = translatedConditions();
        assertFalse(conditions.isEmpty(), "migration condition source audit found no expected translations");

        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        List<String> failures = new ArrayList<>();
        for (String condition : conditions) {
            String key = service.bindCondition(condition);
            if (key == null || !registry.hasCondition(key)) {
                failures.add(condition + " -> " + key);
            }
        }

        assertTrue(failures.isEmpty(),
                "migration produced canonical conditions that runtime cannot bind: " + failures);
        assertNotNull(service.bindCondition("target-has-line-of-sight"));
    }

    @Test
    @DisplayName("all canonical migration mechanics bind through the runtime SkillService")
    void everyTranslatedActionBindsAtRuntime() throws IOException {
        Set<String> actions = translatedActions();
        assertFalse(actions.isEmpty(), "migration action source audit found no expected actions");

        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        YamlConfiguration definitions = new YamlConfiguration();
        ConfigurationSection skills = definitions.createSection("skills");
        for (String action : actions) {
            if (!action.startsWith("random-skill ")) continue;
            for (String candidate : action.substring("random-skill ".length()).split("[,;|]")) {
                String name = candidate.trim();
                if (!name.isBlank()) skills.createSection(name);
            }
        }
        service.loadSkills(skills);
        List<String> failures = new ArrayList<>();
        for (String action : actions) {
            String key = service.bindAction(action);
            if (key == null || !registry.hasAction(key)) failures.add(action + " -> " + key);
        }

        assertTrue(failures.isEmpty(),
                "migration produced mechanics that runtime cannot bind: " + failures);
    }
}
