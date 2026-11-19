package dev.helstera.api.util;

import java.util.List;

/** Native retention metadata: 1 means transient, 0/negative permanent, >1 an epoch deadline. */
public record VariableLifetime(VariableDuration duration, boolean save, boolean saveSpecified) {
    public static VariableLifetime parse(List<String> arguments) {
        Boolean save = null;
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            int equals = PlaceholderSyntax.argumentEquals(argument);
            if (equals < 0 || !argument.substring(0, equals).trim().equalsIgnoreCase("save")) continue;
            if (save != null) throw new IllegalArgumentException("duplicate variable save flag");
            String value = argument.substring(equals + 1).trim();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) value = value.substring(1, value.length() - 1);
            if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
                throw new IllegalArgumentException("variable save must be true or false");
            save = Boolean.valueOf(value);
        }
        return new VariableLifetime(VariableDuration.parse(arguments), Boolean.TRUE.equals(save), save != null);
    }
    public long expirationTime() {
        return duration.ticks() > 0 ? duration.expirationTime() : locationExpirationTime();
    }
    public long locationExpirationTime() {
        return duration.ticks() == 0 ? (save ? 0 : 1) : Math.multiplyExact(duration.ticks(), 50);
    }
    public String argument() { return duration.argument() + (saveSpecified ? " save=" + save : ""); }
}
