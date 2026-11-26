package dev.helstera.api.util;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Native subtract's float operand, alias precedence, numeric types and duration. */
public record MythicVariableArithmetic(String amount, VariableDuration duration) {
    private static final List<String> AMOUNTS = List.of("value", "val", "v", "amount", "a");
    private static final List<String> DURATIONS = List.of("duration", "d", "expire", "e");

    public static MythicVariableArithmetic parse(List<String> arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            int equals = PlaceholderSyntax.argumentEquals(argument);
            if (equals <= 0) continue;
            String key = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (AMOUNTS.contains(key) || DURATIONS.contains(key)) {
                values.put(key, argument.substring(equals + 1).trim());
            }
        }
        String amount = first(values, AMOUNTS);
        if (amount == null) amount = "0";
        if (amount.length() >= 2 && (amount.startsWith("\"") && amount.endsWith("\"")
                || amount.startsWith("'") && amount.endsWith("'"))) amount = amount.substring(1, amount.length() - 1);
        NumericExpression.validate(amount);
        String rawDuration = first(values, DURATIONS);
        long ticks = 0;
        if (rawDuration != null) {
            try { ticks = Long.parseLong(rawDuration); }
            catch (NumberFormatException ignored) { }
        }
        if (ticks > (Long.MAX_VALUE - System.currentTimeMillis()) / 50 || ticks < Long.MIN_VALUE / 50) {
            throw new IllegalArgumentException("variable duration overflows millisecond deadline");
        }
        return new MythicVariableArithmetic(amount, new VariableDuration(ticks, rawDuration != null));
    }

    /** Returns null for unsupported variable types or non-finite arithmetic. */
    public static Object subtract(Object current, float amount) {
        if (!Float.isFinite(amount)) return null;
        if (current instanceof Integer integer) return integer - (int) amount;
        if (!(current instanceof Float value) || !Float.isFinite(value)) return null;
        float result = BigDecimal.valueOf(value).subtract(BigDecimal.valueOf(amount)).floatValue();
        return Float.isFinite(result) ? result : null;
    }

    private static String first(Map<String, String> values, List<String> aliases) {
        for (String alias : aliases) if (values.containsKey(alias)) return values.get(alias);
        return null;
    }
}
