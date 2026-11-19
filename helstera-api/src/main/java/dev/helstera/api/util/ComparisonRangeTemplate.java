package dev.helstera.api.util;

import java.util.LinkedHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PlaceholderString substitution followed by numeric comparison parsing, without arithmetic. */
public record ComparisonRangeTemplate(String source) {
    private static final Pattern TOKEN = Pattern.compile("<[A-Za-z_][^<>]*>");
    private static final String NUMBER = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?";
    private static final Pattern RANGE = Pattern.compile(
            "(?:(>=|<=|>|<|==|!=|=)\\s*)?(" + NUMBER + ")(?:\\s*(?:to|-)\\s*(" + NUMBER + "))?",
            Pattern.CASE_INSENSITIVE);

    public ComparisonRangeTemplate {
        if (source == null || source.isBlank()) throw invalid();
        validateLiteral(substitute(source, ignored -> "1"));
    }

    public static boolean hasPlaceholders(String source) {
        return source != null && TOKEN.matcher(source).find();
    }

    /** Repeated identical tokens resolve once, as native string replacement does. */
    public String resolve(UnaryOperator<String> resolver) {
        String result = substitute(source, resolver);
        validateLiteral(result);
        return result.trim();
    }

    private static String substitute(String source, UnaryOperator<String> resolver) {
        var tokens = TOKEN.matcher(source);
        var values = new LinkedHashMap<String, String>();
        var result = new StringBuilder();
        while (tokens.find()) {
            String token = tokens.group();
            if (!PlaceholderSyntax.isToken(token)) throw invalid();
            String value = values.computeIfAbsent(token, resolver);
            if (value == null) throw invalid();
            tokens.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        tokens.appendTail(result);
        return result.toString();
    }

    private static void validateLiteral(String source) {
        var match = RANGE.matcher(source.trim());
        if (!match.matches() || match.group(1) != null && match.group(3) != null) throw invalid();
        if (!Double.isFinite(Double.parseDouble(match.group(2)))
                || match.group(3) != null && !Double.isFinite(Double.parseDouble(match.group(3)))) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid or unresolved numeric comparison template");
    }
}
