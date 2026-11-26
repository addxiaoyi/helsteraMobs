package dev.helstera.api.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** A condition-list outcome action, separate from the condition's own arguments. */
public record MythicConditionAction(String condition, Type type, String argument, boolean booleanAlias) {
    public enum Type { REQUIRED, CANCEL, CAST, CASTINSTEAD, ORELSECAST, POWER, LEVEL }

    /** Returns null when no recognized top-level suffix is present. */
    public static MythicConditionAction split(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        List<Integer> starts = tokenStarts(value);
        if (starts.size() < 2) return null;
        int last = starts.size() - 1;
        int actionIndex = last;
        Type type = type(value.substring(starts.get(last)));
        if (last > 1) {
            String previous = value.substring(starts.get(last - 1), starts.get(last)).trim();
            Type withArgument = type(previous);
            if (withArgument != null) {
                actionIndex = last - 1;
                type = withArgument;
            }
        }
        if (type == null) return null;
        int start = starts.get(actionIndex);
        String name = value.substring(start, actionIndex == last ? value.length() : starts.get(last)).trim();
        String argument = actionIndex == last ? "" : unquote(value.substring(starts.get(last)));
        return new MythicConditionAction(value.substring(0, start).trim(), type, argument,
                name.equalsIgnoreCase("true") || name.equalsIgnoreCase("false"));
    }

    public boolean valid() {
        return !condition.isBlank() && (type == Type.REQUIRED || type == Type.CANCEL || !argument.isBlank());
    }

    public boolean castsSkill() {
        return type == Type.CAST || type == Type.CASTINSTEAD || type == Type.ORELSECAST;
    }

    /** Encodes the leaf so positional booleans cannot be mistaken for suffix actions. */
    public String runtime() {
        return "condition-action " + type + " " + encode(condition) + " "
                + (argument.isEmpty() ? "-" : encode(argument));
    }

    public static MythicConditionAction decode(String raw) {
        String[] parts = raw.trim().split("\\s+", -1);
        if (parts.length != 4 || !parts[0].equalsIgnoreCase("condition-action")) {
            throw new IllegalArgumentException("Invalid condition action");
        }
        Type type = Type.valueOf(parts[1].toUpperCase(Locale.ROOT));
        var result = new MythicConditionAction(decodeValue(parts[2]), type,
                parts[3].equals("-") ? "" : decodeValue(parts[3]), false);
        if (!result.valid()) throw new IllegalArgumentException("Missing condition action argument");
        return result;
    }

    private static Type type(String raw) {
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "TRUE", "REQUIRED" -> Type.REQUIRED;
            case "FALSE", "CANCEL" -> Type.CANCEL;
            case "CAST" -> Type.CAST;
            case "CASTINSTEAD" -> Type.CASTINSTEAD;
            case "ORELSECAST" -> Type.ORELSECAST;
            case "POWER" -> Type.POWER;
            case "LEVEL" -> Type.LEVEL;
            default -> null;
        };
    }

    private static String encode(String raw) {
        return HexFormat.of().formatHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeValue(String raw) {
        return new String(HexFormat.of().parseHex(raw), StandardCharsets.UTF_8);
    }

    private static String unquote(String value) {
        if (value.length() > 1 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static List<Integer> tokenStarts(String value) {
        List<Integer> starts = new ArrayList<>();
        char quote = 0;
        boolean escaped = false;
        int depth = 0;
        boolean token = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (depth == 0 && Character.isWhitespace(c)) { token = false; continue; }
            if (!token) { starts.add(i); token = true; }
            if (c == '\'' || c == '"') quote = c;
            else if (c == '{' || c == '[' || c == '(') depth++;
            else if (c == '}' || c == ']' || c == ')') depth--;
            if (depth < 0) return List.of();
        }
        return quote == 0 && depth == 0 ? starts : List.of();
    }
}
