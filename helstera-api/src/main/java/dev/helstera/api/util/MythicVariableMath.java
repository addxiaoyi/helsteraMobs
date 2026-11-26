package dev.helstera.api.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native VariableMath equation and duration options, shared by import and direct binding. */
public record MythicVariableMath(String equation, VariableDuration duration) {
    private static final List<String> EQUATIONS = List.of("equation", "eq", "e");
    private static final List<String> DURATIONS = List.of("duration", "d", "expire", "e");

    public static boolean isEquationArgument(String key) {
        return key != null && EQUATIONS.contains(key.toLowerCase(Locale.ROOT));
    }

    /** Selects raw aliases after case-insensitive, last-wins duplicates, then decodes equation quotes. */
    public static MythicVariableMath parse(List<String> arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            int equals = PlaceholderSyntax.argumentEquals(argument);
            if (equals <= 0) continue;
            String key = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (EQUATIONS.contains(key) || DURATIONS.contains(key)) {
                values.put(key, argument.substring(equals + 1).trim());
            }
        }
        String equation = first(values, EQUATIONS);
        if (equation == null) equation = "x";
        if (equation.length() >= 2 && (equation.startsWith("\"") && equation.endsWith("\"")
                || equation.startsWith("'") && equation.endsWith("'"))) {
            equation = equation.substring(1, equation.length() - 1);
        }
        NumericExpression.validateEquation(equation);

        // Native e belongs to both alias lists. A non-integral selected duration becomes zero.
        String rawDuration = first(values, DURATIONS);
        long ticks = 0;
        if (rawDuration != null) {
            try { ticks = Long.parseLong(rawDuration); }
            catch (NumberFormatException ignored) { }
        }
        // Reject values whose tick conversion or positive epoch deadline cannot be represented.
        if (ticks > (Long.MAX_VALUE - System.currentTimeMillis()) / 50
                || ticks < Long.MIN_VALUE / 50) {
            throw new IllegalArgumentException("variable duration overflows millisecond deadline");
        }
        return new MythicVariableMath(equation, new VariableDuration(ticks, rawDuration != null));
    }

    private static String first(Map<String, String> values, List<String> aliases) {
        for (String alias : aliases) {
            if (values.containsKey(alias)) return values.get(alias);
        }
        return null;
    }
}
