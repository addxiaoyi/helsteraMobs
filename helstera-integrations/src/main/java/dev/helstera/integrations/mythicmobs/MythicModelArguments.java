package dev.helstera.integrations.mythicmobs;

import java.util.*;

/** Immutable, literal bridge arguments. Native timing/targeting arguments remain native-owned. */
record MythicModelArguments(String model, String animation, double scale, boolean loop, int priority) {
    private static final Set<String> FRAMEWORK = Set.of("cooldown", "cd", "delay", "targetinterval", "targeti",
            "repeat", "repeatinterval", "repeati", "power", "powersplitbetweentargets", "powersplit", "splitpower",
            "forcesync", "sync", "targetisorigin", "sourceisorigin", "castfromorigin", "fromorigin", "fo",
            "faulty", "origin", "targetcreative", "chance");

    static MythicModelArguments parse(String name, String line, boolean condition) {
        if (line == null) throw new IllegalArgumentException("Missing source line");
        String source = line.trim();
        int endName = 0;
        while (endName < source.length() && source.charAt(endName) != '{'
                && !Character.isWhitespace(source.charAt(endName))) endName++;
        if (!source.substring(0, endName).equalsIgnoreCase(name))
            throw new IllegalArgumentException("Source name does not match load event");
        String tail = source.substring(endName).trim();
        Map<String, String> values = new LinkedHashMap<>();
        if (tail.startsWith("{")) {
            int end = closingBrace(tail);
            for (String pair : split(tail.substring(1, end))) {
                if (pair.isBlank()) continue;
                int equal = pair.indexOf('=');
                if (equal <= 0) throw new IllegalArgumentException("Expected named brace argument");
                String key = pair.substring(0, equal).trim().toLowerCase(Locale.ROOT);
                String canonical = switch (key) {
                    case "m" -> switch (name) {
                        case "modelspawn" -> "model";
                        case "modelplay", "modelstop", "modelplaying" -> "animation";
                        case "modelscale" -> "scale";
                        default -> "m";
                    };
                    default -> key;
                };
                String value = pair.substring(equal + 1).trim();
                if (values.putIfAbsent(canonical, unquote(value)) != null)
                    throw new IllegalArgumentException("Duplicate or conflicting argument: " + canonical);
            }
            tail = tail.substring(end + 1).trim();
        }
        if (condition && !tail.isEmpty() && !tail.equalsIgnoreCase("true") && !tail.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("Only true/false condition suffixes are supported");
        if (!condition && !tail.isEmpty() && !tail.matches("[@~?=<>0-9].*"))
            throw new IllegalArgumentException("Positional model arguments are unsupported");
        Set<String> allowed = switch (name) {
            case "modelspawn" -> Set.of("model", "scale", "animation", "loop");
            case "modelplay" -> Set.of("animation", "loop", "priority");
            case "modelstop", "modelplaying" -> Set.of("animation");
            case "modelscale" -> Set.of("scale");
            case "modelremove", "modelspawned", "modelremoved" -> Set.of();
            default -> throw new IllegalArgumentException("Unsupported model operation");
        };
        for (String key : values.keySet()) {
            if (!allowed.contains(key) && (condition || !FRAMEWORK.contains(key)))
                throw new IllegalArgumentException("Unsupported argument: " + key);
        }
        String model = identifier(values.get("model"), name.equals("modelspawn"));
        String animation = identifier(values.get("animation"), name.equals("modelplay"));
        double scale = positiveScale(values.getOrDefault("scale", name.equals("modelscale") ? "" : "1"));
        String loopText = values.getOrDefault("loop", "false");
        if (!loopText.equalsIgnoreCase("true") && !loopText.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("loop must be true or false");
        int priority = Integer.parseInt(values.getOrDefault("priority", "7"));
        return new MythicModelArguments(model, animation, scale, Boolean.parseBoolean(loopText), priority);
    }

    static double positiveScale(String text) {
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("scale must be finite and positive");
        return value;
    }

    private static String identifier(String value, boolean required) {
        if (value == null && !required) return null;
        if (value == null || !value.matches("[A-Za-z0-9_./:-]+"))
            throw new IllegalArgumentException("Missing or invalid literal model/animation identifier");
        return value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.charAt(0) == value.charAt(value.length() - 1)) return value.substring(1, value.length() - 1);
        return value;
    }

    private static int closingBrace(String text) {
        char quote = 0; int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') { i++; continue; }
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return i;
        }
        throw new IllegalArgumentException("Unclosed argument block");
    }

    private static List<String> split(String text) {
        List<String> parts = new ArrayList<>(); char quote = 0; int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') { i++; continue; }
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '{') depth++;
            if (c == '}') depth--;
            if (c == ';' && depth == 0) { parts.add(text.substring(start, i)); start = i + 1; }
        }
        if (quote != 0 || depth != 0) throw new IllegalArgumentException("Unbalanced arguments");
        parts.add(text.substring(start)); return parts;
    }
}
