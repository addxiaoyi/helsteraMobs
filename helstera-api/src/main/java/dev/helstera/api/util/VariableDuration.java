package dev.helstera.api.util;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Native variable duration is a static tick count converted to wall-clock milliseconds. */
public record VariableDuration(long ticks, boolean specified) {
    public static VariableDuration parse(List<String> arguments) {
        String duration = null;
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            int equals = PlaceholderSyntax.argumentEquals(argument);
            if (equals < 0 || !Set.of("duration", "d", "expire", "e").contains(
                    argument.substring(0, equals).trim().toLowerCase(Locale.ROOT))) continue;
            if (duration != null) throw new IllegalArgumentException("duplicate variable duration");
            duration = argument.substring(equals + 1).trim();
            if (duration.length() >= 2 && (duration.startsWith("\"") && duration.endsWith("\"")
                    || duration.startsWith("'") && duration.endsWith("'"))) duration = duration.substring(1, duration.length() - 1);
        }
        try {
            long ticks = duration == null ? 0 : Long.parseLong(duration);
            // Reserve enough headroom for the epoch offset as well as the conversion.
            if (ticks > (Long.MAX_VALUE - System.currentTimeMillis()) / 50
                    || ticks < Long.MIN_VALUE / 50) throw new ArithmeticException("overflow");
            return new VariableDuration(ticks, duration != null);
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IllegalArgumentException("variable duration must be an integral tick count without overflow", error);
        }
    }
    public long expirationTime() {
        return ticks > 0 ? Math.addExact(System.currentTimeMillis(), Math.multiplyExact(ticks, 50)) : 0;
    }
    public String argument() { return specified ? " duration=" + ticks : ""; }
}
