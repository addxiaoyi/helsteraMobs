package dev.helstera.api.util;

import java.util.Locale;

/** A single placeholder token, including Mythic numeric rounding configuration. */
public record PlaceholderSyntax(String key, Integer rounding) {
    /** Parse the text inside angle brackets. Unknown keys remain valid for runtime resolution. */
    public static PlaceholderSyntax parse(String text) {
        if (text == null || text.isEmpty()) throw invalid();
        int open = text.indexOf('{');
        String key = text;
        Integer rounding = null;
        if (open >= 0) {
            if (open == 0 || text.charAt(open - 1) == '.') throw invalid();
            int close = text.indexOf('}', open);
            if (close < 0 || text.indexOf('{', open + 1) >= 0 || text.indexOf('}', close + 1) >= 0
                    || close + 1 < text.length() && text.charAt(close + 1) != '.') throw invalid();
            key = text.substring(0, open) + text.substring(close + 1);
            String body = text.substring(open + 1, close);
            rounding = 2; // EntityNumericPlaceholder's configured default.
            if (!body.isBlank()) {
                String[] options = body.split("[;,]", -1);
                if (options.length != 1) throw invalid();
                int equals = body.indexOf('=');
                if (equals < 1 || !java.util.Set.of("rounding", "round", "r")
                        .contains(body.substring(0, equals).trim().toLowerCase(Locale.ROOT))) throw invalid();
                String value = body.substring(equals + 1).trim();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                try { rounding = Integer.valueOf(value); }
                catch (NumberFormatException e) { throw invalid(); }
                // Greater scales add no useful double precision and can allocate unbounded memory.
                if (rounding > 340) throw invalid();
            }
        }
        if (!key.matches("[A-Za-z0-9_.:+-]+")) throw invalid();
        return new PlaceholderSyntax(key, rounding);
    }

    public static boolean isToken(String text) {
        if (text == null || !text.startsWith("<") || !text.endsWith(">")) return false;
        try { parse(text.substring(1, text.length() - 1)); return true; }
        catch (IllegalArgumentException e) { return false; }
    }

    /** Only a leading argument key owns '=', not inline placeholder options or comparisons. */
    public static int argumentEquals(String text) {
        int equals = text.indexOf('=');
        if (equals <= 0) return equals;
        return text.substring(0, equals).trim().matches("[\\p{L}_][\\p{L}\\p{N}_.:-]*") ? equals : -1;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid placeholder or numeric rounding configuration");
    }
}
