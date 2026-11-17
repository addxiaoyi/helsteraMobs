package dev.helstera.ai.skill;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Mythic 5.7.2 integer and float placeholder families, with strict invalid-input handling. */
final class RandomPlaceholder {
    private static final String NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?";
    private static final Pattern TO = Pattern.compile("^(" + NUMBER + ")to(" + NUMBER + ")$");
    private static final Pattern DASH = Pattern.compile("^(" + NUMBER + ")-(" + NUMBER + ")$");
    private RandomPlaceholder() { }

    static String resolve(String field) {
        if (field.equals("percent") || field.equals("chance")) {
            return Double.toString(ThreadLocalRandom.current().nextDouble()); // Helstera extension.
        }
        boolean rounded = field.startsWith("float.rounded.");
        boolean floating = rounded || field.startsWith("float.");
        String range = rounded ? field.substring(14) : floating ? field.substring(6) : field;
        String[] bounds = bounds(range);
        if (bounds == null) return null;
        try {
            if (!floating) {
                int a = Integer.parseInt(bounds[0]);
                int b = Integer.parseInt(bounds[1]);
                long low = Math.min(a, b), high = Math.max(a, b);
                return Long.toString(low == high ? low : ThreadLocalRandom.current().nextLong(low, high + 1));
            }
            float a = Float.parseFloat(bounds[0]);
            float b = Float.parseFloat(bounds[1]);
            float spread = b - a;
            if (!Float.isFinite(a) || !Float.isFinite(b) || !Float.isFinite(spread)) return null;
            // Float casts and arithmetic match the pinned RandomFloat implementation.
            float value = a + (float) ThreadLocalRandom.current().nextDouble() * spread;
            if (!Float.isFinite(value)) return null;
            if (!rounded) return Double.toString((double) value);
            return new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String[] bounds(String range) {
        if (range == null || range.isEmpty()) return null;
        var to = TO.matcher(range);
        if (to.matches()) return new String[]{to.group(1), to.group(2)};
        // Native dash ranges require a nonnegative first endpoint; signed ranges use 'to'.
        var dash = DASH.matcher(range);
        if (!range.startsWith("-") && dash.matches() && !dash.group(2).startsWith("-")) {
            return new String[]{dash.group(1), dash.group(2)};
        }
        return range.matches(NUMBER) ? new String[]{range, range} : null;
    }
}
