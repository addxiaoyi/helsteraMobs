package dev.helstera.plugin;

import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.api.behavior.ManagedMobIdentity;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Cached runtime view of imported mob-template lineage metadata. */
final class MobTemplateIndex implements SkillExtras.MobTemplateLookup {

    private static final int MAX_DEPTH = 32;
    private static final String MOB_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9_-]{0,63}";

    private final Function<String, ConfigurationSection> mobConfigLookup;
    private final Map<String, Optional<MobConfig>> configs = new ConcurrentHashMap<>();

    MobTemplateIndex(Function<String, ConfigurationSection> mobConfigLookup) {
        this.mobConfigLookup = mobConfigLookup;
    }

    @Override
    public Collection<String> templatesOf(Entity entity) {
        String mobId = ManagedMobIdentity.mobId(entity);
        if (!validMobId(mobId)) return List.of();

        LinkedHashSet<String> result = new LinkedHashSet<>();
        boolean valid = expand(mobId.toLowerCase(Locale.ROOT), result,
                new LinkedHashSet<>(), new LinkedHashSet<>(), 0, true);
        return valid ? List.copyOf(result) : List.of();
    }

    /** Drop cached files after a runtime reload so edited mob definitions are observed. */
    void invalidate() {
        configs.clear();
    }

    private boolean expand(String mobId, LinkedHashSet<String> result, LinkedHashSet<String> active,
                           LinkedHashSet<String> visited, int depth, boolean required) {
        if (depth > MAX_DEPTH || active.contains(mobId)) return false;
        if (visited.contains(mobId)) return true;

        MobConfig config = config(mobId).orElse(null);
        if (config == null) return !required;
        if (!config.valid()) return false;

        active.add(mobId);
        for (String template : config.templates()) {
            result.add(template);
            // A template may itself be represented by an imported mob definition.
            // If so, retain that ancestor chain; otherwise the direct reference is still usable.
            if (config(template).isPresent()
                    && !expand(template, result, active, visited, depth + 1, false)) {
                active.remove(mobId);
                return false;
            }
        }
        active.remove(mobId);
        visited.add(mobId);
        return true;
    }

    private Optional<MobConfig> config(String id) {
        if (!validMobId(id)) return Optional.empty();
        return configs.computeIfAbsent(id, key -> {
            ConfigurationSection section;
            try {
                section = mobConfigLookup.apply(key);
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
            if (section == null) return Optional.empty();
            Object raw = section.get("mob.templates");
            if (raw == null) return Optional.of(new MobConfig(true, List.of()));

            List<?> values = raw instanceof Collection<?> collection
                    ? new ArrayList<>(collection) : List.of(raw);
            List<String> templates = new ArrayList<>(values.size());
            for (Object value : values) {
                if (!(value instanceof String text)) return Optional.of(new MobConfig(false, List.of()));
                String normalized = normalizeTemplateId(text);
                if (normalized == null) return Optional.of(new MobConfig(false, List.of()));
                templates.add(normalized);
            }
            return Optional.of(new MobConfig(true, List.copyOf(new LinkedHashSet<>(templates))));
        });
    }

    private static boolean validMobId(String value) {
        return value != null && value.matches(MOB_ID_PATTERN);
    }

    private static String normalizeTemplateId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        int dot = value.indexOf('.');
        if (dot > 0) value = value.substring(dot + 1);
        value = value.replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_")
                .replaceAll("^_", "").replaceAll("_$", "");
        if (value.isBlank()) return null;
        return value.startsWith("mythic_") ? value : "mythic_" + value;
    }

    private record MobConfig(boolean valid, List<String> templates) { }
}
