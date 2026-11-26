package dev.helstera.api.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;

/** Main-scoreboard mechanics; unrelated to Helstera's per-instance score map. */
public record MythicScoreboardSpec(String mechanic, Scope scope, String objective, String entry,
                                   String value, Operation operation, ValueKind valueKind, IntegerRange range) {
    public enum Scope { GLOBAL, CASTER, TARGET, ENTRY }
    public enum Operation { SET, ADD, SUBTRACT, MULTIPLY, DIVIDE, MOD, UNSET }
    public enum ValueKind { STATIC_INTEGER, PLACEHOLDER_INTEGER, STRING_INTEGER }

    public record IntegerRange(int minimum, int maximum) {
        int sample(IntUnaryOperator boundedRandom) {
            // Preserve native int bound overflow and nextInt's invalid-bound rejection.
            return minimum + boundedRandom.applyAsInt(maximum - minimum + 1);
        }
    }

    public static String canonical(String name) {
        if (name == null) return "";
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "setglobalscore", "sgs", "set-global-score" -> "set-global-score";
            case "setmobscore", "sms", "set-mob-score" -> "set-mob-score";
            case "settargetscore", "sts", "set-target-score" -> "set-target-score";
            case "modifyglobalscore", "mgs", "modify-global-score" -> "modify-global-score";
            case "modifymobscore", "mms", "modify-mob-score" -> "modify-mob-score";
            case "modifytargetscore", "mts", "modify-target-score" -> "modify-target-score";
            case "modifyscore", "modify-score" -> "modify-score";
            default -> "";
        };
    }

    public static boolean supports(String name) { return !canonical(name).isEmpty(); }
    public static Set<String> names() {
        return Set.of("set-global-score", "set-mob-score", "set-target-score", "modify-global-score",
                "modify-mob-score", "modify-target-score", "modify-score");
    }

    public static MythicScoreboardSpec parse(String name, List<String> arguments) {
        String mechanic = canonical(name);
        if (mechanic.isEmpty()) throw new IllegalArgumentException("Unknown scoreboard mechanic: " + name);
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            int equal = PlaceholderSyntax.argumentEquals(argument);
            if (equal <= 0) throw new IllegalArgumentException("Scoreboard mechanics require named arguments");
            values.put(argument.substring(0, equal).trim().toLowerCase(Locale.ROOT),
                    argument.substring(equal + 1).trim());
        }
        Scope scope = mechanic.equals("modify-score") ? Scope.ENTRY : mechanic.contains("-global-")
                ? Scope.GLOBAL : mechanic.contains("-mob-") ? Scope.CASTER : Scope.TARGET;
        boolean setting = mechanic.startsWith("set-");
        Operation fallback = setting || scope == Scope.ENTRY ? Operation.SET : Operation.ADD;
        Operation operation = fallback;
        if (!setting) {
            try { operation = Operation.valueOf(first(values, fallback.name(), "action", "a").toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { }
            if (scope != Scope.ENTRY && operation == Operation.UNSET) operation = fallback;
        }
        ValueKind kind = scope == Scope.CASTER || scope == Scope.GLOBAL ? ValueKind.STATIC_INTEGER
                : setting ? ValueKind.PLACEHOLDER_INTEGER : ValueKind.STRING_INTEGER;
        String value = first(values, mechanic.equals("modify-target-score") ? null : "0", "value", "v");
        IntegerRange range = null;
        if (kind == ValueKind.STATIC_INTEGER) {
            // Native getInteger is a load-time parse, including malformed/quoted fallback to zero.
            int integer = 0;
            try { integer = Integer.parseInt(value); } catch (NumberFormatException ignored) { }
            value = Integer.toString(integer);
        } else if (kind == ValueKind.PLACEHOLDER_INTEGER && value.matches("^-?[0-9]*(to|-)?[0-9]\\d*")) {
            // Native RandomInt recognition runs on raw text before quote decoding or math.
            try {
                String separator = value.contains("to") ? "to" : !value.startsWith("-") && value.contains("-") ? "-" : null;
                if (separator != null) {
                    String[] bounds = value.split(separator);
                    range = new IntegerRange(Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1]));
                } else value = Integer.toString(Integer.parseInt(value));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException invalidRange) {
                // A rejected RandomInt constructor makes PlaceholderInt.of return StaticInt(1).
                value = "1";
            }
        } else value = decode(value);
        return new MythicScoreboardSpec(mechanic, scope, decode(first(values, "", "objective", "obj", "o")),
                decode(first(values, "dummy", "entry", "e", "name", "n")), value, operation, kind, range);
    }

    public int operand(UnaryOperator<String> resolver) {
        if (valueKind == ValueKind.STATIC_INTEGER) return Integer.parseInt(value);
        if (value == null) throw new IllegalArgumentException("Missing scoreboard value");
        if (valueKind == ValueKind.STRING_INTEGER) return Integer.parseInt(resolver.apply(value));
        if (range != null) return range.sample(ThreadLocalRandom.current()::nextInt);
        // Unlike the gated public native build, Helstera implements dynamic PlaceholderInt itself.
        // Math truncates toward zero; plain decimal text floors; malformed values fall back to one.
        try {
            if (value.startsWith("=") || value.startsWith("eval ") || value.contains("(") && value.contains(")")
                    || value.chars().anyMatch(character -> "+-*/^".indexOf(character) >= 0))
                return (int) NumericExpression.resolve(value, resolver);
            String resolved = resolver.apply(value);
            if (!resolved.contains(".")) return Integer.parseInt(resolved);
            double number = Double.parseDouble(resolved);
            int integer = (int) number;
            return integer == number ? integer : integer - (int) (Double.doubleToRawLongBits(number) >>> 63);
        } catch (IllegalArgumentException ignored) { return 1; }
    }

    /** Java int overflow, signed remainder and truncating division are the native operations. */
    public int apply(int current, int operand) {
        return switch (operation) {
            case SET -> operand;
            case ADD -> current + operand;
            case SUBTRACT -> current - operand;
            case MULTIPLY -> current * operand;
            case DIVIDE -> current / operand;
            case MOD -> current % operand;
            case UNSET -> current;
        };
    }

    private static String first(Map<String, String> values, String fallback, String... aliases) {
        for (String alias : aliases) if (values.containsKey(alias)) return values.get(alias);
        return fallback;
    }

    private static String decode(String value) {
        if (value == null) return null;
        for (String quote : List.of("\"", "'")) {
            if (value.length() >= 2 && value.startsWith(quote) && value.endsWith(quote))
                value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
