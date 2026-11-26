package dev.helstera.api.behavior;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Raw public Mythic targeter arguments; alias priority belongs to each consumer. */
final class NativeTargeterOptions {
    private NativeTargeterOptions() { }

    static double number(String value, double fallback) {
        try { return value == null ? fallback : Double.parseDouble(value); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    static boolean flag(String value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    /** Public-build PlaceholderInt falls back to 1 for non-integer/non-range input. */
    static int integer(String value) {
        try {
            if (value.matches("-?\\d+")) return Integer.parseInt(value);
            if (!value.matches("-?[0-9]*(to|-)[0-9]\\d*")) return 1;
            String[] range = value.contains("to") ? value.split("to") : value.split("-");
            int min = Integer.parseInt(range[0]), max = Integer.parseInt(range[1]);
            return min == max ? min : (int) ThreadLocalRandom.current().nextLong(min, (long) max + 1);
        } catch (IllegalArgumentException invalid) { return 1; }
    }

    static String first(Map<String, String> values, String... keys) {
        for (String key : keys) if (values.containsKey(key)) return values.get(key);
        return null;
    }

    static List<Targeters.ConditionalCondition> conditions(Map<String, String> options) {
        String value = first(options, "targetconditions", "conditions", "cond", "c");
        if (value == null) return List.of();
        if (!value.startsWith("[") || !value.endsWith("]"))
            throw new IllegalArgumentException("Target conditions require a bracketed list");
        String inner = value.substring(1, value.length() - 1).trim();
        if (inner.isEmpty()) return List.of();
        List<Targeters.ConditionalCondition> parsed = Targeters.parseConditionalConditions(inner.startsWith("-") ? inner : "- " + inner);
        if (parsed.isEmpty()) throw new IllegalArgumentException("Malformed target conditions");
        return parsed;
    }

    static Map<String, String> options(String spec) {
        if (spec == null || spec.isBlank()) throw new IllegalArgumentException("Missing targeter");
        String raw = spec.trim().replaceFirst("^@", "");
        int open = raw.indexOf('{');
        Map<String, String> values = new LinkedHashMap<>();
        values.put("@name", (open < 0 ? raw : raw.substring(0, open)).trim().toLowerCase(Locale.ROOT));
        if (open < 0) return values;
        if (!raw.endsWith("}")) throw new IllegalArgumentException("Unclosed targeter");
        String body = raw.substring(open + 1, raw.length() - 1);
        if (body.isBlank()) return values;
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i <= body.length(); i++) {
            char c = i == body.length() ? ';' : body.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '[' || c == '{' || c == '(') depth++;
            if (c == ']' || c == '}' || c == ')') depth--;
            if (depth < 0) throw new IllegalArgumentException("Unbalanced targeter");
            if (c == ';' && depth == 0) {
                String argument = body.substring(start, i).trim();
                int equals = argument.indexOf('=');
                if (equals <= 0) throw new IllegalArgumentException("Targeter arguments must be named");
                // Retain raw quoted values and alias keys: native scalar parsing does not
                // unquote values, and alias precedence differs between the two queries.
                values.put(argument.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                        argument.substring(equals + 1).replace("<&csp>", " ").replace("<&da>", "-").trim());
                start = i + 1;
            }
        }
        if (depth != 0 || quote != 0) throw new IllegalArgumentException("Unbalanced targeter");
        return values;
    }
}
