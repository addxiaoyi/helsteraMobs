package dev.helstera.api.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Literal location selectors supported for native VariableSetLocation mutations. */
public record MythicVariableLocation(String selector) {
    private static final List<String> VALUES = List.of("value", "val", "v", "amount", "a");
    private static final Set<String> MECHANICS = Set.of(
            "variablesetlocation", "setvariablelocation", "setvarloc", "variablelocationset");

    /** Native names and the existing variablelocationset alias; canonical actions retain their defaults. */
    public static boolean supports(String mechanic) {
        return mechanic != null && MECHANICS.contains(mechanic.toLowerCase(Locale.ROOT));
    }

    public static boolean isValueArgument(String key) {
        return key != null && VALUES.contains(key.toLowerCase(Locale.ROOT));
    }

    /** Keeps native alias priority, duplicate last values and literal quote characters. */
    public static MythicVariableLocation parse(List<String> arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            int equals = PlaceholderSyntax.argumentEquals(argument);
            if (equals <= 0) continue;
            String key = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (isValueArgument(key)) values.put(key, argument.substring(equals + 1).trim());
        }
        String selector = "@self";
        for (String alias : VALUES) {
            if (values.containsKey(alias)) {
                selector = values.get(alias);
                break;
            }
        }
        String canonical = switch (selector.toLowerCase(Locale.ROOT)) {
            case "@self", "@caster", "@boss", "@mob", "@selflocation", "@casterlocation",
                    "@bosslocation", "@moblocation" -> "@self";
            case "@origin", "@source" -> "@origin";
            case "@trigger", "@triggerlocation" -> "@trigger";
            case "@target", "@t" -> "@target";
            case "@targetlocation", "@targetloc", "@tl" -> "@targetlocation";
            default -> throw new IllegalArgumentException(
                    "unsupported variable location value: " + selector
                            + "; expected a caster, origin, trigger or target selector without arguments");
        };
        return new MythicVariableLocation(canonical);
    }
}
