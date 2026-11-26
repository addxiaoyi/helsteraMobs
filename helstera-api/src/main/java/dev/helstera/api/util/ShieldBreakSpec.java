package dev.helstera.api.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;

/** Native ShieldBreak argument lookup and PlaceholderInt sampling. */
public record ShieldBreakSpec(String value, Integer minimum, Integer maximum) {
    public static String canonical(String name) {
        if (name == null) return "";
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "shieldbreak", "disableshield", "disable-shield" -> "disable-shield";
            default -> "";
        };
    }

    public static boolean supports(String name) { return !canonical(name).isEmpty(); }

    public static ShieldBreakSpec parse(List<String> arguments) {
        var fields = new LinkedHashMap<String, String>();
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            int equals = argument == null ? -1 : PlaceholderSyntax.argumentEquals(argument);
            if (equals <= 0 || argument.substring(0, equals).isBlank())
                throw new IllegalArgumentException("Shield disabling requires named arguments");
            fields.put(argument.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                    argument.substring(equals + 1).trim());
        }
        String value = fields.getOrDefault("duration", fields.getOrDefault("d", "100"));
        if (value.matches("^-?[0-9]*(to|-)?[0-9]\\d*")) {
            try {
                String separator = value.contains("to") ? "to" : !value.startsWith("-") && value.contains("-") ? "-" : null;
                if (separator != null) {
                    String[] bounds = value.split(separator);
                    return new ShieldBreakSpec(value, Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1]));
                }
                return new ShieldBreakSpec(Integer.toString(Integer.parseInt(value)), null, null);
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException invalid) {
                return new ShieldBreakSpec("1", null, null);
            }
        }
        return new ShieldBreakSpec(MythicMessageText.prepare(value), null, null);
    }

    public int duration(UnaryOperator<String> resolver, RandomGenerator random) {
        if (minimum != null) return minimum + random.nextInt(maximum - minimum + 1);
        // Helstera supports dynamic integers itself; the pinned public native build
        // gates these behind Premium and falls back to one. Keep that difference explicit.
        try {
            if (value.startsWith("=") || value.startsWith("eval ") || value.contains("(") && value.contains(")")
                    || value.chars().anyMatch(character -> "+-*/^".indexOf(character) >= 0))
                return (int) NumericExpression.resolve(value, resolver);
            String resolved = resolver.apply(value);
            if (!resolved.contains(".")) return Integer.parseInt(resolved);
            double number = Double.parseDouble(resolved);
            if (!Double.isFinite(number)) return 1;
            int integer = (int) number;
            return integer == number ? integer : integer - (int) (Double.doubleToRawLongBits(number) >>> 63);
        } catch (IllegalArgumentException invalid) { return 1; }
    }
}
