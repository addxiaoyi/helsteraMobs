package dev.helstera.plugin;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

/** Cross-module contract for translated conditions and mechanics used by migration tests. */
class MigrationRuntimeConditionContractTest {

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
