package dev.helstera.ai.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Structured targeter syntax used by MythicMobs skill lines. */
public record MythicTargeterSpec(String name, Map<String, String> arguments, List<String> positional) {

    public MythicTargeterSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("targeter name is blank");
        name = name.toLowerCase(Locale.ROOT);
        arguments = Map.copyOf(arguments);
        positional = List.copyOf(positional);
    }

    public static MythicTargeterSpec parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("targeter is blank");
        String value = raw.trim();
        if (value.charAt(0) == '@') value = value.substring(1);
        if (value.isBlank()) throw new IllegalArgumentException("targeter name is blank");

        int brace = value.indexOf('{');
        if (brace < 0) return new MythicTargeterSpec(value, Map.of(), List.of());
        if (!value.endsWith("}")) throw new IllegalArgumentException("unterminated targeter arguments");
        String name = value.substring(0, brace).trim();
        String body = value.substring(brace + 1, value.length() - 1);
        Map<String, String> arguments = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (String part : split(body)) {
            String token = part.trim();
            if (token.isEmpty()) continue;
            int eq = token.indexOf('=');
            if (eq > 0) {
                arguments.put(token.substring(0, eq).trim().toLowerCase(Locale.ROOT),
                        unquote(token.substring(eq + 1).trim()));
            } else {
                positional.add(unquote(token));
            }
        }
        return new MythicTargeterSpec(name, arguments, positional);
    }

    private static List<String> split(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int nested = 0;
        for (char c : body.toCharArray()) {
            if (c == '"') quoted = !quoted;
            if (!quoted && (c == '[' || c == '(')) nested++;
            if (!quoted && (c == ']' || c == ')')) nested = Math.max(0, nested - 1);
            if (!quoted && nested == 0 && (c == ';' || c == ',')) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quoted || nested != 0) throw new IllegalArgumentException("malformed targeter arguments");
        parts.add(current.toString());
        return parts;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }
}
