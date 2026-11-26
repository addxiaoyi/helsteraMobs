package dev.helstera.api.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import net.objecthunter.exp4j.Expression;
import net.objecthunter.exp4j.ExpressionBuilder;
import net.objecthunter.exp4j.function.Function;
import net.objecthunter.exp4j.operator.Operator;

/** Finite numeric arguments with the exp4j grammar used by MythicMobs 5.7.2. */
public final class NumericExpression {
    private static final Pattern TOKEN = Pattern.compile("<([A-Za-z_][A-Za-z0-9_.:+-]*(?:\\{[^<>]*})?(?:\\.[A-Za-z0-9_.:+-]+)?)>");
    private static final Function[] FUNCTIONS = {
            binary("min", Math::min), binary("max", Math::max), binary("atan2", Math::atan2),
            binary("random", (minimum, maximum) -> minimum + Math.random() * (maximum - minimum))
    };
    private static final Operator[] OPERATORS = {
            comparison("<", (left, right) -> left < right ? 1 : 0),
            comparison("<=", (left, right) -> left <= right ? 1 : 0),
            comparison(">", (left, right) -> left > right ? 1 : 0),
            comparison(">=", (left, right) -> left >= right ? 1 : 0),
            comparison("==", (left, right) -> left == right ? 1 : 0)
    };

    private NumericExpression() { }

    /** True for placeholder or expression inputs; finite literal parsing remains eager. */
    public static boolean dynamic(String value) {
        if (value == null) return false;
        try { Double.parseDouble(value); return false; }
        catch (NumberFormatException ignored) { return true; }
    }

    public static void validate(String value) {
        requireText(value);
        if (!dynamic(value)) { finite(value); return; }
        String skeleton = substitute(value, ignored -> "1");
        if (mathEnabled(value)) expression(skeleton);
        else finite(skeleton);
    }

    /** Resolve a token once per distinct spelling, then evaluate its textual substitution. */
    public static double resolve(String source, UnaryOperator<String> resolver) {
        return finite(resolveText(source, resolver));
    }

    /** VariableMath always evaluates an equation, with x bound to the current variable value. */
    public static void validateEquation(String source) {
        requireText(source);
        equation(substitute(source, ignored -> "1"), 1);
    }

    /** Resolve each distinct placeholder once and reject non-finite equation results. */
    public static double resolveEquation(String source, double x, UnaryOperator<String> resolver) {
        requireText(source);
        double value = equation(substitute(source, resolver), x).evaluate();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite equation result");
        return value;
    }

    public static String resolveText(String source, UnaryOperator<String> resolver) {
        requireText(source);
        if (!dynamic(source)) { finite(source); return source; }
        String resolved = substitute(source, resolver);
        if (mathEnabled(source)) {
            double value = expression(resolved).evaluate();
            if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite expression result");
            return Double.toString(value);
        }
        finite(resolved);
        return resolved;
    }

    private static String substitute(String source, UnaryOperator<String> resolver) {
        var matcher = TOKEN.matcher(source);
        var out = new StringBuilder(source.length());
        Map<String, String> values = new LinkedHashMap<>();
        while (matcher.find()) {
            String token = matcher.group();
            // In (pi<e>0), < and > are comparisons, not an entity placeholder.
            if (token.indexOf('.') < 0 && token.indexOf('{') < 0 && matcher.start() > 0) {
                int previous = matcher.start() - 1;
                while (previous >= 0 && Character.isWhitespace(source.charAt(previous))) previous--;
                if (previous >= 0 && (Character.isLetterOrDigit(source.charAt(previous))
                        || source.charAt(previous) == ')')) continue;
            }
            if (!PlaceholderSyntax.isToken(token)) throw new IllegalArgumentException("invalid numeric placeholder");
            String value = values.computeIfAbsent(token, key -> {
                String result = resolver.apply(key);
                if (result == null) throw new IllegalArgumentException("unresolved numeric placeholder");
                return result;
            });
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean mathEnabled(String source) {
        return source.startsWith("=") || source.startsWith("eval")
                || source.contains("(") && source.contains(")")
                || source.chars().anyMatch(value -> "+-*/^".indexOf(value) >= 0);
    }

    private static Expression expression(String source) {
        var expression = new ExpressionBuilder(source).operator(OPERATORS).functions(FUNCTIONS).build();
        if (!expression.validate().isValid()) throw new IllegalArgumentException("invalid numeric expression");
        return expression;
    }

    private static Expression equation(String source, double x) {
        var expression = new ExpressionBuilder(source).operator(OPERATORS).functions(FUNCTIONS)
                .variable("x").build().setVariable("x", x);
        if (!expression.validate().isValid()) throw new IllegalArgumentException("invalid variable equation");
        return expression;
    }

    private static double finite(String value) {
        if (value == null) throw new IllegalArgumentException("unresolved numeric placeholder");
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number)) throw new IllegalArgumentException("non-finite numeric value");
        return number;
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("empty numeric expression");
    }

    private static Function binary(String name, DoubleBinaryOperator operation) {
        return new Function(name, 2) {
            @Override public double apply(double... values) { return operation.applyAsDouble(values[0], values[1]); }
        };
    }

    private static Operator comparison(String symbol, DoubleBinaryOperator operation) {
        // All five native comparisons are left associative, below addition/subtraction (500).
        return new Operator(symbol, 2, true, 499) {
            @Override public double apply(double... values) { return operation.applyAsDouble(values[0], values[1]); }
        };
    }
}
