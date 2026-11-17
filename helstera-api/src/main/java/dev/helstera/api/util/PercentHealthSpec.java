package dev.helstera.api.util;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Mythic percentage-health syntax uses fractions (0.1 = 10%), unlike legacy Helstera actions. */
public record PercentHealthSpec(boolean healing, String fraction, boolean currentHealth,
                                boolean overheal, String maxOverheal) {
    public static String canonical(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "healpercent", "percentheal", "hp", "heal-fraction" -> "heal-fraction";
            case "damagepercent", "percentdamage", "damage-fraction" -> "damage-fraction";
            default -> name.toLowerCase(Locale.ROOT);
        };
    }

    public static boolean supports(String name) {
        return List.of("heal-fraction", "damage-fraction").contains(canonical(name));
    }

    public static PercentHealthSpec parse(String name, List<String> args) {
        if (!supports(name)) throw new IllegalArgumentException("Unknown percent-health mechanic: " + name);
        boolean healing = canonical(name).equals("heal-fraction");
        Map<String, String> values = new HashMap<>();
        int positional = 0;
        for (String raw : args == null ? List.<String>of() : args) {
            if (raw == null || raw.isBlank()) throw invalid("empty argument");
            int equals = raw.indexOf('=');
            String key;
            String value;
            if (equals < 0) {
                if (++positional > 1) throw invalid("too many positional arguments");
                key = "fraction";
                value = raw.trim();
            } else {
                key = raw.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                key = switch (key) {
                    case "multiplier", "m", "amount", "a" -> healing ? "fraction" : key;
                    case "percent", "p" -> healing ? key : "fraction";
                    case "currenthealth", "current", "ch", "c" -> healing ? key : "currenthealth";
                    case "overheal", "oh" -> healing ? "overheal" : key;
                    case "maxoverheal", "maxabsorb", "maxshield", "mo", "ma", "ms" -> healing ? "maxoverheal" : key;
                    default -> key;
                };
                if (!(key.equals("fraction") || healing && List.of("overheal", "maxoverheal").contains(key)
                        || !healing && key.equals("currenthealth"))) throw invalid("unsupported argument " + key);
                value = raw.substring(equals + 1).trim();
            }
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (value.isEmpty() || values.putIfAbsent(key, value) != null) throw invalid("empty or duplicate " + key);
        }
        String fraction = values.getOrDefault("fraction", "0.1");
        String maximum = values.getOrDefault("maxoverheal", "0");
        if (!dynamic(fraction)) finite(fraction);
        if (!dynamic(maximum)) finite(maximum);
        return new PercentHealthSpec(healing, fraction, bool(values.getOrDefault("currenthealth", "false")),
                bool(values.getOrDefault("overheal", "false")), maximum);
    }

    public List<String> arguments() {
        return healing ? List.of("multiplier=" + fraction, "overheal=" + overheal, "maxoverheal=" + maxOverheal)
                : List.of("percent=" + fraction, "currenthealth=" + currentHealth);
    }

    public Optional<Resolved> resolve(UnaryOperator<String> resolver) {
        try {
            double amount = finite(dynamic(fraction) ? resolver.apply(fraction) : fraction);
            double maximum = finite(dynamic(maxOverheal) ? resolver.apply(maxOverheal) : maxOverheal);
            return Optional.of(new Resolved(amount, maximum));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    public record Resolved(double fraction, double maxOverheal) { }

    private static boolean dynamic(String value) { return value.matches("<[a-zA-Z0-9_.:-]+>"); }

    private static double finite(String value) {
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number)) throw invalid("non-finite value");
        return number;
    }

    private static boolean bool(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid("invalid boolean " + value);
        };
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("percent-health: " + reason);
    }
}
