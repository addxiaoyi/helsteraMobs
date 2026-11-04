package dev.helstera.api.util;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses condition fields that may be written as a YAML list or a delimited scalar.
 *
 * <p>Mythic configurations commonly use commas, while condition arguments may also
 * contain commas (for example {@code time-between=0,12000}). Braces, quotes, and
 * known coordinate/range arguments are therefore kept intact instead of using a
 * plain {@code String.split(",")}.</p>
 */
public final class ConditionListParser {
    public static final String INVALID_CONDITION = "__helstera_invalid_condition__";

    private static final Set<String> CONDITION_STARTS = Set.of(
            "all", "any", "not", "day", "night", "raining", "thundering", "sunny",
            "dawn", "dusk", "world-is", "biome-is", "time-between", "lunar-phase-is",
            "moon-phase-is", "region", "entities-at-least", "mobs-at-least",
            "players-at-most", "players-at-least", "health", "health-above", "health-below",
            "health-percent", "target-health", "target-health-above", "target-health-below",
            "distance", "distance-above", "distance-below", "target-within",
            "target-not-within", "permission", "target-has-permission", "has-target",
            "target-has-target", "has-item", "target-has-item", "has-aura", "has-auras",
            "has-potion-effect", "damage-cause", "damage-amount", "faction", "targetfaction",
            "samefaction", "differentfaction", "incombat", "randomchance", "chance",
            "is-on-ground", "on-ground", "in-water", "in-lava", "in-air", "is-blocked",
            "stand-on", "daylight", "is-alive", "is-dead", "is-valid", "is-player",
            "is-living", "is-mob", "is-monster", "is-animal", "is-undead", "is-creature",
            "is-baby", "is-adult", "is-tamed", "is-sheared", "is-saddled", "is-powered",
            "is-collidable", "has-offhand", "variable", "variable-equals", "variable-contains",
            "score", "global-score", "plugin", "plugin-exists", "is-skill", "spawn-reason",
            "item-type", "entity-item-type", "block-type", "structure", "frozen", "is-frozen",
            "mob-size", "children", "has-owner", "owner-is-player", "owner-is-online",
            "target-is-alive", "target-is-valid", "target-is-undead", "target-is-creature",
            "target-is-baby", "target-is-adult", "target-is-tamed", "target-is-sheared",
            "target-is-saddled", "target-is-powered", "target-is-collidable",
            "target-has-offhand", "target-on-fire", "target-in-air", "target-in-water",
            "target-in-lava", "target-is-sneaking", "target-is-sprinting", "target-is-flying",
            "target-is-gliding", "target-is-swimming", "target-is-blocking"
    );

    private ConditionListParser() {
    }

    /** Returns a normalized, immutable condition list from a YAML-shaped value. */
    public static List<String> parse(Object raw) {
        if (raw == null) return List.of();
        List<String> out = new ArrayList<>();
        append(out, raw);
        return List.copyOf(out);
    }

    /** Preserves malformed entries as a marker that consumers must reject. */
    public static List<String> parseFailClosed(Object raw) {
        if (raw == null) return List.of();
        List<?> values = raw instanceof List<?> list ? list : List.of(raw);
        List<String> out = new ArrayList<>();
        for (Object value : values) {
            List<String> parsed = value instanceof String text ? splitScalar(text, true) : List.of();
            if (parsed.isEmpty()) out.add(INVALID_CONDITION);
            else out.addAll(parsed);
        }
        return List.copyOf(out);
    }

    private static void append(List<String> out, Object raw) {
        if (raw == null) return;
        if (raw instanceof Iterable<?> values) {
            for (Object value : values) append(out, value);
            return;
        }
        if (raw.getClass().isArray()) {
            int length = Array.getLength(raw);
            for (int i = 0; i < length; i++) append(out, Array.get(raw, i));
            return;
        }
        for (String value : splitScalar(String.valueOf(raw))) {
            if (!value.isBlank()) out.add(value.trim());
        }
    }

    private static List<String> splitScalar(String raw) {
        return splitScalar(raw, false);
    }

    private static List<String> splitScalar(String raw, boolean failClosed) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        int braces = 0;
        int brackets = 0;
        int parentheses = 0;
        boolean invalidStructure = false;
        java.util.ArrayDeque<Character> delimiters = new java.util.ArrayDeque<>();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '{' || c == '[' || c == '(') delimiters.push(c);
            else if (c == '}' || c == ']' || c == ')') {
                char expected = c == '}' ? '{' : c == ']' ? '[' : '(';
                if (delimiters.isEmpty() || delimiters.pop() != expected) invalidStructure = true;
            }
            if (c == '{') braces++;
            else if (c == '}') { if (braces > 0) braces--; else invalidStructure = true; }
            else if (c == '[') brackets++;
            else if (c == ']') { if (brackets > 0) brackets--; else invalidStructure = true; }
            else if (c == '(') parentheses++;
            else if (c == ')') { if (parentheses > 0) parentheses--; else invalidStructure = true; }

            boolean topLevel = braces == 0 && brackets == 0 && parentheses == 0;
            if (topLevel && c == '|' && i + 1 < raw.length() && raw.charAt(i + 1) == '|') {
                current.append("||");
                i++;
                continue;
            }
            if (topLevel && (c == ';' || c == '|')) {
                addPart(out, current, failClosed);
                continue;
            }
            if (topLevel && c == ',' && commaSeparates(raw, i, current.toString())) {
                addPart(out, current, failClosed);
                continue;
            }
            current.append(c);
        }
        addPart(out, current, failClosed);
        if (failClosed && (invalidStructure || quote != 0 || braces != 0 || brackets != 0 || parentheses != 0)) {
            out.add(INVALID_CONDITION);
        }
        return out;
    }

    private static void addPart(List<String> out, StringBuilder current, boolean failClosed) {
        String value = current.toString().trim();
        if (!value.isBlank()) out.add(value);
        else if (failClosed) out.add(INVALID_CONDITION);
        current.setLength(0);
    }

    private static boolean commaSeparates(String raw, int comma, String current) {
        String left = current.trim();
        if (left.isBlank()) return true;
        String after = raw.substring(comma + 1).trim();
        if (after.isBlank()) return false;

        String name = conditionName(left);
        int required = requiredCommaValues(name, left, after);
        if (required > 0) {
            return valueCount(left) >= required;
        }
        return startsCondition(after);
    }

    /** Returns the condition's leading name, ignoring Mythic negation markers. */
    private static String conditionName(String value) {
        int start = 0;
        while (start < value.length() && (value.charAt(start) == '!' || value.charAt(start) == '?')) {
            start++;
        }
        int end = start;
        while (end < value.length()) {
            char c = value.charAt(end);
            if (Character.isWhitespace(c) || c == '=' || c == '{' || c == '(') break;
            end++;
        }
        return value.substring(start, end).toLowerCase(Locale.ROOT);
    }

    /** Returns the number of top-level comma-separated values after the first '='. */
    private static int valueCount(String value) {
        int equals = value.indexOf('=');
        if (equals < 0) return 0;
        int count = 1;
        boolean quote = false;
        for (int i = equals + 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\'' || c == '"') quote = !quote;
            else if (c == ',' && !quote) count++;
        }
        return count;
    }

    private static int requiredCommaValues(String name, String value, String after) {
        if (name.equals("time-between")) return 2;
        if (name.equals("region")) {
            String raw = valueAfterEquals(value);
            String[] parts = raw.split(",", -1);
            if (parts.length > 1 && isNumber(parts[1])) {
                return isNumber(parts[0]) ? 6 : 7;
            }
            String next = after.split(",", 2)[0].trim();
            if (parts.length == 1 && isNumber(next)) return isNumber(parts[0]) ? 6 : 7;
        }
        if (Set.of("origin-location", "originlocation", "location", "coordinates", "coordinate")
                .contains(name)) {
            String raw = valueAfterEquals(value);
            String[] parts = raw.split(",", -1);
            if (parts.length > 1 && isNumber(parts[1])) {
                return isNumber(parts[0]) ? 3 : 4;
            }
            String next = after.split(",", 2)[0].trim();
            if (parts.length == 1 && isNumber(next)) return isNumber(parts[0]) ? 3 : 4;
        }
        return 0;
    }

    private static String valueAfterEquals(String value) {
        int equals = value.indexOf('=');
        return equals < 0 ? "" : value.substring(equals + 1).trim();
    }

    private static boolean isNumber(String value) {
        try {
            return Double.isFinite(Double.parseDouble(value.trim()));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean startsCondition(String value) {
        String normalized = value.trim();
        while (normalized.startsWith("!") || normalized.startsWith("?")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.isBlank()) return false;
        int end = 0;
        while (end < normalized.length()) {
            char c = normalized.charAt(end);
            if (Character.isWhitespace(c) || c == '=' || c == '{' || c == '(') break;
            end++;
        }
        String token = normalized.substring(0, end).toLowerCase(Locale.ROOT);
        if (CONDITION_STARTS.contains(token)) return true;
        // Unknown conditions with explicit arguments are still safe separators.
        return normalized.contains("=") || normalized.contains("{") || normalized.contains("(");
    }
}
