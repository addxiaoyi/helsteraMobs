package dev.helstera.api.util;

import dev.helstera.api.behavior.Targeters;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The bounded native interaction contracts verified against MythicMobs 5.7.2. */
public record EntityInteractionSpec(String mechanic, String source) {
    private static final Set<String> SOURCE_KEYS = Set.of("target", "to", "t", "location", "loc", "l", "of");
    private static final Set<String> LOCATION_SOURCES = Set.of("origin", "source", "selflocation",
            "casterlocation", "bosslocation", "moblocation", "selfeyelocation", "eyedirection",
            "castereyelocation", "bosseyelocation", "mobeyelocation", "triggerlocation", "trackedlocation",
            "location", "loc", "l");

    public static String canonical(String name) {
        if (name == null) return "";
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "stopusingitem", "releaseitem", "stop-using-item" -> "stop-using-item";
            case "matchrotation", "matchrot", "match-rotation" -> "match-rotation";
            case "goatram", "goat-ram" -> "goat-ram";
            default -> "";
        };
    }

    public static boolean supports(String name) { return !canonical(name).isEmpty(); }

    public static Set<String> names() { return Set.of("stop-using-item", "match-rotation", "goat-ram"); }

    public static EntityInteractionSpec parse(String name, List<String> arguments) {
        String mechanic = canonical(name);
        if (mechanic.isEmpty()) throw new IllegalArgumentException("Unknown entity interaction: " + name);
        String source = null;
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            if (!mechanic.equals("match-rotation"))
                throw new IllegalArgumentException(mechanic + " has no mechanic arguments");
            int equals = argument == null ? -1 : PlaceholderSyntax.argumentEquals(argument);
            if (equals <= 0 || !SOURCE_KEYS.contains(argument.substring(0, equals).trim().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("match-rotation requires a named source selector");
            if (source != null) throw new IllegalArgumentException("Duplicate match-rotation source selector");
            source = argument.substring(equals + 1).trim();
            if (source.length() >= 2 && source.startsWith("\"") && source.endsWith("\""))
                source = source.substring(1, source.length() - 1);
        }
        if (mechanic.equals("match-rotation") && !validSource(source))
            throw new IllegalArgumentException("Unsupported match-rotation source selector: " + source);
        return new EntityInteractionSpec(mechanic, source);
    }

    public List<String> arguments() { return source == null ? List.of() : List.of("target=" + source); }

    /** Sources are single-location selectors independent of per-recipient target rebinding. */
    public static boolean validSource(String source) {
        if (source == null || !source.startsWith("@")) return false;
        String kind = sourceKind(source);
        if (Set.of("self", "caster", "trigger").contains(kind))
            return source.substring(1).equalsIgnoreCase(kind);
        if (!LOCATION_SOURCES.contains(kind)) return false;
        try {
            return Targeters.validLocationTargeterSpec(source)
                    && Targeters.positionTargeterSpec(source).conditions().isEmpty();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static String sourceKind(String source) {
        if (source == null) return "";
        return source.trim().replaceFirst("^@", "").split("\\{", 2)[0].trim()
                .toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
