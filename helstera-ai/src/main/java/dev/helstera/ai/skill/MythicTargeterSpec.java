package dev.helstera.ai.skill;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
        char quote = 0;
        boolean escaped = false;
        Deque<Character> nested = new ArrayDeque<>();
        for (char c : body.toCharArray()) {
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '(' || c == '{') {
                nested.push(c);
            } else if (c == ']' || c == ')' || c == '}') {
                if (nested.isEmpty() || !matches(nested.pop(), c)) {
                    throw new IllegalArgumentException("malformed targeter arguments");
                }
            }
            if (quote == 0 && nested.isEmpty() && (c == ';' || c == ',')) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quote != 0 || escaped || !nested.isEmpty()) {
            throw new IllegalArgumentException("malformed targeter arguments");
        }
        parts.add(current.toString());
        return parts;
    }

    private static boolean matches(char opening, char closing) {
        return opening == '[' && closing == ']'
                || opening == '(' && closing == ')'
                || opening == '{' && closing == '}';
    }

    private static String unquote(String value) {
        if (value.length() < 2) return value;
        char quote = value.charAt(0);
        if ((quote != '"' && quote != '\'') || value.charAt(value.length() - 1) != quote) {
            return value;
        }
        StringBuilder unquoted = new StringBuilder(value.length() - 2);
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() - 1) {
                char next = value.charAt(i + 1);
                if (next == quote || next == '\\') {
                    unquoted.append(next);
                    i++;
                    continue;
                }
            }
            unquoted.append(c);
        }
        return unquoted.toString();
    }
}
