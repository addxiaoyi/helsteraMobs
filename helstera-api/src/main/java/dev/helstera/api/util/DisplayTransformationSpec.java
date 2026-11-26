package dev.helstera.api.util;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/** Native display transformation fields; finite dynamic numbers are a Helstera extension. */
public record DisplayTransformationSpec(Action action, Type type, List<String> values) {
    public enum Action { SET, ADD, MULTIPLY, DIVIDE }
    public enum Type { TRANSLATION, SCALE, RIGHT_ROTATION, LEFT_ROTATION }
    private static final Pattern RANGE = Pattern.compile("^-?[0-9]*(\\.\\d+)?(to|-)+[0-9]\\d*(\\.\\d+)?");

    public DisplayTransformationSpec { values = List.copyOf(values); }

    public static String canonical(String name) {
        if (name == null) return "";
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "displaytransformation", "settransformation", "transformation", "display-transformation" -> "display-transformation";
            default -> "";
        };
    }

    public static boolean supports(String name) { return !canonical(name).isEmpty(); }

    public static DisplayTransformationSpec parse(List<String> arguments) {
        var fields = new LinkedHashMap<String, String>();
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            int equals = argument == null ? -1 : PlaceholderSyntax.argumentEquals(argument);
            if (equals <= 0 || argument.substring(0, equals).isBlank())
                throw new IllegalArgumentException("Display transformation requires named arguments");
            fields.put(argument.substring(0, equals).trim().toLowerCase(Locale.ROOT), argument.substring(equals + 1).trim());
        }
        Action action = enumeration(Action.class, first(fields, "SET", "action", "a"), Action.SET);
        Type type = enumeration(Type.class, first(fields, "TRANSLATION", "transformationtype", "transformation", "type", "tt"), Type.TRANSLATION);
        // Native splits the raw string first; support a quoted vector as an explicit extension.
        String raw = MythicMessageText.prepare(first(fields, "0,0,0", "value", "val"));
        List<String> values = Arrays.asList(raw.split(","));
        int required = type == Type.TRANSLATION || type == Type.SCALE ? 3 : 4;
        if (values.size() < required) throw new IllegalArgumentException("Display transformation needs " + required + " components");
        for (String value : values) {
            try { finite(Float.parseFloat(value)); }
            catch (NumberFormatException ignored) { /* Dynamic/range/fallback resolved at invocation. */ }
        }
        return new DisplayTransformationSpec(action, type, values);
    }

    public float[] resolve(UnaryOperator<String> resolver, RandomGenerator random) {
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = number(values.get(i), resolver, random);
        return result;
    }

    private static float number(String text, UnaryOperator<String> resolver, RandomGenerator random) {
        if (RANGE.matcher(text).matches()) {
            String separator = text.contains("to") ? "to" : text.startsWith("-") ? null : "-";
            if (separator != null) {
                String[] pair = text.split(separator);
                try {
                    float minimum = finite(Float.parseFloat(pair[0])), maximum = finite(Float.parseFloat(pair[1]));
                    return finite(minimum + (float) random.nextDouble() * (maximum - minimum));
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException invalid) { return 1; }
            }
        }
        String value = resolver.apply(text);
        try { return finite(Float.parseFloat(value)); }
        catch (NumberFormatException invalid) {
            if (text.startsWith("=") || text.contains("(") && text.contains(")")) {
                double computed;
                try { computed = NumericExpression.resolve(text, resolver); }
                catch (IllegalArgumentException malformed) { return 1; }
                return finite((float) computed);
            }
            return 1;
        }
    }

    private static float finite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite display transformation");
        return value;
    }

    private static String first(Map<String, String> fields, String fallback, String... names) {
        for (String name : names) if (fields.containsKey(name)) return fields.get(name);
        return fallback;
    }

    private static <T extends Enum<T>> T enumeration(Class<T> type, String value, T fallback) {
        try { return Enum.valueOf(type, value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { return fallback; }
    }
}
