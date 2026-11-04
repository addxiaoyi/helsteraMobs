package dev.helstera.ai.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the outer MythicMobs skill line without throwing away execution context. */
public record MythicSkillSpec(
        String mechanic,
        Map<String, String> arguments,
        List<String> positional,
        String targeter,
        String trigger,
        Double chance,
        List<String> inlineConditions) {

    private static final Pattern CHANCE = Pattern.compile(
            "^[+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?%$");

    public MythicSkillSpec {
        mechanic = Objects.requireNonNull(mechanic, "mechanic").toLowerCase(Locale.ROOT);
        // Preserve Mythic's keyed argument order when binding positional
        // factories. Map.copyOf is intentionally free to reorder entries.
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        positional = List.copyOf(positional);
        inlineConditions = List.copyOf(inlineConditions);
        if (chance != null && (!Double.isFinite(chance) || chance < 0 || chance > 100)) {
            throw new IllegalArgumentException("chance must be between 0 and 100");
        }
    }

    public static MythicSkillSpec parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("skill line is blank");
        List<String> tokens = tokenize(raw.trim());
        if (tokens.isEmpty()) throw new IllegalArgumentException("skill line is blank");

        String head = tokens.remove(0);
        int brace = head.indexOf('{');
        String mechanic = brace < 0 ? head : head.substring(0, brace);
        int closingBrace = brace < 0 ? -1 : matchingBrace(head, brace);
        if (brace >= 0 && closingBrace != head.length() - 1) {
            throw new IllegalArgumentException("unexpected text after mechanic arguments");
        }
        String body = brace < 0 ? null : head.substring(brace + 1, closingBrace);
        List<String> positional = new ArrayList<>();
        java.util.LinkedHashMap<String, String> args = new java.util.LinkedHashMap<>();
        if (body != null) parseBody(body, args, positional);

        String targeter = null;
        String trigger = null;
        Double chance = null;
        List<String> conditions = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("@") || token.equals("?")) {
                throw new IllegalArgumentException("incomplete targeter or condition");
            }
            if (token.startsWith("@") && token.length() > 1) {
                if (targeter != null) throw new IllegalArgumentException("multiple targeters without a chain");
                targeter = token.substring(1);
                while (i + 1 < tokens.size() && tokens.get(i + 1).equals(">")) {
                    if (i + 2 >= tokens.size() || !tokens.get(i + 2).startsWith("@")
                            || tokens.get(i + 2).length() == 1) {
                        throw new IllegalArgumentException("incomplete targeter chain");
                    }
                    targeter += " > " + tokens.get(i + 2);
                    i += 2;
                }
            }
            else if (token.equals(">")) throw new IllegalArgumentException("incomplete targeter chain");
            else if (token.startsWith("~")) {
                if (token.length() <= 3 || !token.regionMatches(true, 0, "~on", 0, 3)) {
                    throw new IllegalArgumentException("invalid event trigger: " + token);
                }
                if (trigger != null) throw new IllegalArgumentException("multiple event triggers");
                trigger = token.substring(3);
            }
            else if (token.startsWith("?") && token.length() > 1) conditions.add(token.substring(1));
            else {
                Matcher match = CHANCE.matcher(token);
                if (match.matches()) {
                    if (chance != null) throw new IllegalArgumentException("multiple chance percentages");
                    chance = Double.parseDouble(token.substring(0, token.length() - 1));
                    if (!Double.isFinite(chance) || chance < 0 || chance > 100) {
                        throw new IllegalArgumentException("chance must be between 0 and 100");
                    }
                } else if (token.endsWith("%")) {
                    throw new IllegalArgumentException("invalid chance percentage: " + token);
                } else {
                    positional.add(unquote(token));
                }
            }
        }
        return new MythicSkillSpec(mechanic, args, positional, targeter, trigger, chance, conditions);
    }

    private static void parseBody(String body, Map<String, String> args, List<String> positional) {
        for (String part : splitBody(body)) {
            String value = part.trim();
            if (value.isEmpty()) continue;
            int eq = value.indexOf('=');
            if (eq > 0) {
                String key = value.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                if ((key.equals("delay") || key.equals("repeat") || key.equals("repeatinterval")
                        || key.equals("repeat-interval"))
                        && (args.containsKey(key)
                        || key.equals("repeatinterval") && args.containsKey("repeat-interval")
                        || key.equals("repeat-interval") && args.containsKey("repeatinterval"))) {
                    throw new IllegalArgumentException("duplicate execution option: " + key);
                }
                args.put(key, unquote(value.substring(eq + 1).trim()));
            } else positional.add(unquote(value));
        }
    }

    private static int matchingBrace(String value, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted && c == '\\' && i + 1 < value.length()) { i++; continue; }
            if (c == '"') quoted = !quoted;
            if (quoted) continue;
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return i;
        }
        throw new IllegalArgumentException("unterminated mechanic arguments");
    }

    private static List<String> tokenize(String value) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int braces = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted && c == '\\' && i + 1 < value.length()) {
                current.append(c).append(value.charAt(++i));
                continue;
            }
            if (c == '"') quoted = !quoted;
            if (!quoted && c == '{') braces++;
            if (!quoted && c == '}') braces--;
            boolean targeterSeparator = !quoted && braces == 0 && c == '>'
                    && (current.length() > 0 && current.charAt(0) == '@'
                    || current.length() == 0 && !out.isEmpty()
                    && out.get(out.size() - 1).startsWith("@"));
            if (targeterSeparator) {
                if (current.length() > 0) { out.add(current.toString()); current.setLength(0); }
                out.add(">");
                continue;
            }
            if (!quoted && braces == 0 && Character.isWhitespace(c)) {
                if (current.length() > 0) { out.add(current.toString()); current.setLength(0); }
            } else current.append(c);
        }
        if (quoted || braces != 0) throw new IllegalArgumentException("malformed skill line");
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    private static List<String> splitBody(String value) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        int bracket = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted && c == '\\' && i + 1 < value.length()) {
                current.append(c).append(value.charAt(++i));
                continue;
            }
            if (c == '"') quoted = !quoted;
            if (!quoted) {
                if (c == '[' || c == '(' || c == '{') bracket++;
                else if (c == ']' || c == ')' || c == '}') bracket = Math.max(0, bracket - 1);
                else if (bracket == 0 && (c == ';' || (c == ',' && keyFollows(value, i)))) {
                    out.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        out.add(current.toString());
        return out;
    }

    private static boolean keyFollows(String value, int comma) {
        for (int i = comma + 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ';') return false;
            if (c == '=') return true;
        }
        return false;
    }
    private static List<String> split(String value, char... separators) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : value.toCharArray()) {
            if (c == '"') quoted = !quoted;
            boolean separator = false;
            for (char candidate : separators) separator |= c == candidate;
            if (!quoted && separator) { out.add(current.toString()); current.setLength(0); }
            else current.append(c);
        }
        out.add(current.toString());
        return out;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            StringBuilder unescaped = new StringBuilder();
            for (int i = 1; i < value.length() - 1; i++) {
                char c = value.charAt(i);
                if (c == '\\' && i + 1 < value.length() - 1
                        && (value.charAt(i + 1) == '"' || value.charAt(i + 1) == '\\')) {
                    c = value.charAt(++i);
                }
                unescaped.append(c);
            }
            return unescaped.toString();
        }
        return value;
    }
}


