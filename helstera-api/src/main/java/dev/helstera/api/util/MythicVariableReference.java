package dev.helstera.api.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Literal mutation references in MythicMobs 5.7.2, shared by import and direct binding. */
public record MythicVariableReference(String name, String scope, String sourceName, boolean explicitScope) {
    private static final List<String> NAMES = List.of("name", "n", "variable", "var", "key", "k");
    private static final List<String> SCOPES = List.of("scope", "s");

    /** Only native spellings: canonical Helstera actions already carry literal keys and scopes. */
    public static boolean supports(String mechanic) {
        return mechanic != null && Set.of("setvariable", "setvar", "variableadd", "varadd", "variablemath", "varmath",
                "variablesubtract", "variablesub", "subtractvariable", "subvar", "reducevariable",
                "variableunset", "unsetvariable", "unsetvar", "varunset", "setvariablelocation",
                "variablesetlocation", "variablelocationset", "setvarloc", "locationunset", "unsetlocation")
                .contains(mechanic.toLowerCase(Locale.ROOT));
    }

    public static boolean isReferenceArgument(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return NAMES.contains(normalized) || SCOPES.contains(normalized);
    }

    /** Arguments retain quotes: the native reference parser treats them as literal data. */
    public static MythicVariableReference parse(List<String> arguments, String positionalFallback) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments) {
            int equals = PlaceholderSyntax.argumentEquals(argument);
            if (equals <= 0) continue;
            String key = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (isReferenceArgument(key)) values.put(key, argument.substring(equals + 1).trim());
        }
        // Repeated keys are last-wins; alias selection follows the native declared order.
        String sourceName = first(values, NAMES);
        if (sourceName == null) sourceName = positionalFallback;
        if (sourceName == null || !sourceName.matches("[\\p{L}\\p{N}_.:-]+")) {
            throw new IllegalArgumentException("invalid or unsupported variable name");
        }
        String scope = first(values, SCOPES);
        boolean explicitScope = scope != null;
        String name = sourceName;
        int dot = sourceName.indexOf('.');
        if (dot >= 0) {
            if (!explicitScope) scope = sourceName.substring(0, dot);
            name = sourceName.substring(dot + 1);
        }
        if (name.isEmpty()) throw new IllegalArgumentException("variable name must not be empty");
        if (scope == null) scope = "skill";
        scope = scope.toLowerCase(Locale.ROOT);
        if (!Set.of("skill", "caster", "target", "world", "global").contains(scope)) {
            throw new IllegalArgumentException("invalid or unsupported variable mutation scope: " + scope);
        }
        return new MythicVariableReference(name, scope, sourceName, explicitScope);
    }

    private static String first(Map<String, String> values, List<String> aliases) {
        for (String alias : aliases) {
            if (values.containsKey(alias)) return values.get(alias);
        }
        return null;
    }
}
