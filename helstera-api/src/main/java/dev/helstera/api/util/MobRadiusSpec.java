package dev.helstera.api.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Constant MobsInRadius arguments shared by migration and direct condition binding. */
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
            int close = rest.indexOf('}');
            if (close < 0 || rest.indexOf('{', 1) >= 0 || rest.indexOf('}', close + 1) >= 0)
                throw invalid("malformed parameter block");
            String body = rest.substring(1, close).trim();
            if (!body.isEmpty()) {
                for (String entry : body.split(";", -1)) {
                    int equals = entry.indexOf('=');
                    if (equals < 1) throw invalid("expected a named argument");
                    String key = switch (entry.substring(0, equals).trim().toLowerCase(Locale.ROOT)) {
                        case "amount", "a" -> "amount";
                        case "radius", "r" -> "radius";
                        case "types", "type", "t" -> "types";
                        default -> throw invalid("unknown argument");
                    };
                    String argument = entry.substring(equals + 1).trim();
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
        return "mobs-in-radius " + amount + " " + radius + (types.isEmpty() ? "" : " " + types);
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
        return raw != null && raw.matches(NUMBER) && finiteNonNegative(raw)
                && Double.isFinite(Double.parseDouble(raw) * Double.parseDouble(raw));
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
