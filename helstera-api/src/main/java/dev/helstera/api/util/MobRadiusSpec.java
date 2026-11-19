package dev.helstera.api.util;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** MobsInRadius arguments shared by migration and direct condition binding. */
public record MobRadiusSpec(String amount, String radius, String types) {
    private static final String NUMBER = "(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)";
    private static final Pattern RANGE = Pattern.compile(
            "(?:>=|<=|>|<)?" + NUMBER + "|" + NUMBER + "(?:to|-)" + NUMBER);
    private static final Pattern HEAD = Pattern.compile("(?i)^mobs[-_]?in[-_]?radius(?=\\s|\\{|$)");

    public static boolean supports(String source) {
        return source != null && HEAD.matcher(source.trim()).find();
    }

    public static MobRadiusSpec parse(String source) {
        String value = source.trim();
        var head = HEAD.matcher(value);
        if (!head.find()) throw invalid("unknown condition");
        String rest = value.substring(head.end()).trim();
        Map<String, String> args = new HashMap<>();
        String legacy = "0";
        if (rest.startsWith("{")) {
            List<String> entries = new ArrayList<>();
            int close = parameterBlock(rest, entries);
            if (!(entries.size() == 1 && entries.getFirst().isBlank())) {
                for (String entry : entries) {
                    int equals = entry.indexOf('=');
                    if (equals < 1) throw invalid("expected a named argument");
                    String key = switch (entry.substring(0, equals).trim().toLowerCase(Locale.ROOT)) {
                        case "amount", "a" -> "amount";
                        case "radius", "r" -> "radius";
                        case "types", "type", "t" -> "types";
                        default -> throw invalid("unknown argument");
                    };
                    String argument = entry.substring(equals + 1).trim();
                    if (key.equals("radius")) argument = unquote(argument);
                    if (argument.isEmpty() || args.putIfAbsent(key, argument) != null)
                        throw invalid("empty or duplicate argument");
                }
            }
            if (close + 1 < rest.length()) {
                if (!Character.isWhitespace(rest.charAt(close + 1))) throw invalid("unseparated suffix");
                legacy = rest.substring(close + 1).trim();
            }
        } else if (!rest.isEmpty()) {
            legacy = rest;
        }
        if (!validAmount(legacy)) throw invalid("invalid legacy value");
        // MM 5.7.2 feeds conditionVar to BOTH fields before their nominal defaults.
        String amount = args.getOrDefault("amount", legacy);
        String radius = args.getOrDefault("radius", legacy);
        String types = args.getOrDefault("types", "");
        if (!validAmount(amount) || !validRadius(radius)) throw invalid("invalid amount or radius");
        if (!types.isEmpty()) {
            for (String type : types.split(",", -1)) {
                if (type.isBlank() || type.chars().anyMatch(c -> Character.isWhitespace(c)
                        || Character.isISOControl(c) || "{};=\"".indexOf(c) >= 0))
                    throw invalid("invalid type list");
            }
        }
        return new MobRadiusSpec(amount, radius, types);
    }

    public String runtime() {
        String runtimeRadius = NumericExpression.dynamic(radius)
                ? "\"" + radius.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : radius;
        return "mobs-in-radius " + amount + " " + runtimeRadius + (types.isEmpty() ? "" : " " + types);
    }

    public static boolean validAmount(String raw) {
        if (raw == null || !RANGE.matcher(raw).matches()) return false;
        String value = raw.replaceFirst("^(>=|<=|>|<)", "");
        String[] bounds = value.split("to|-", -1);
        if (!finiteNonNegative(bounds[0])) return false;
        return bounds.length == 1 || finiteNonNegative(bounds[1])
                && Double.parseDouble(bounds[0]) <= Double.parseDouble(bounds[1]);
    }

    public static boolean validRadius(String raw) {
        try {
            // Native bare A-B/AtoB is a random interval, not subtraction. Preserve until supported.
            if (raw != null && raw.matches(NUMBER + "(?:to|-)" + NUMBER)) return false;
            NumericExpression.validate(raw);
            if (!NumericExpression.dynamic(raw)) requireRadius(Double.parseDouble(raw));
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Validate again after resolution: a valid expression may produce an invalid radius. */
    public static double requireRadius(double radius) {
        if (!Double.isFinite(radius) || radius < 0 || !Double.isFinite(radius * radius))
            throw invalid("radius must be nonnegative and have a finite square");
        return radius;
    }

    private static int parameterBlock(String rest, List<String> entries) {
        int depth = 1;
        int start = 1;
        char quote = 0;
        for (int i = 1; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (quote != 0) {
                if (c == '\\' && i + 1 < rest.length()) i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '{') depth++;
            else if (c == '}') {
                if (--depth == 0) {
                    entries.add(rest.substring(start, i));
                    return i;
                }
            } else if (c == ';' && depth == 1) {
                entries.add(rest.substring(start, i));
                start = i + 1;
            }
        }
        throw invalid("malformed parameter block");
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\").trim();
        }
        return value;
    }

    private static boolean finiteNonNegative(String raw) {
        try {
            double number = Double.parseDouble(raw);
            return Double.isFinite(number) && number >= 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("mobs-in-radius: " + reason);
    }
}
