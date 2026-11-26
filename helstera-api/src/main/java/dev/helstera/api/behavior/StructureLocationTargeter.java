package dev.helstera.api.behavior;

import org.bukkit.Location;
import org.bukkit.StructureType;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static dev.helstera.api.behavior.NativeTargeterOptions.*;

/** Native legacy structure lookup, including Paper events and generation semantics. */
@SuppressWarnings("deprecation")
public final class StructureLocationTargeter {
    /** Preserve the public native default while rejecting larger explicit searches. */
    public static final int MAX_SEARCH_RADIUS = 5000;

    private StructureLocationTargeter() { }

    public static boolean recognizes(String spec) {
        if (spec == null) return false;
        String name = spec.trim();
        if (name.startsWith("@")) name = name.substring(1);
        int brace = name.indexOf('{');
        return (brace < 0 ? name : name.substring(0, brace)).trim().equalsIgnoreCase("neareststructure");
    }

    static boolean valid(String spec) {
        try {
            Map<String, String> args = options(spec);
            conditions(args);
            return recognizes(spec) && radius(args) <= MAX_SEARCH_RADIUS;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static int radius(Map<String, String> args) {
        return NativeLocationTargetFilters.staticInteger(first(args, "radius", "r"), MAX_SEARCH_RADIUS);
    }

    static List<Location> select(String spec, BehaviorContext context, UnaryOperator<String> resolver) {
        if (!valid(spec) || !context.instanceValid()) return List.of();
        var caster = context.instance().baseEntity().orElse(null);
        if (caster == null || !caster.isValid()) return List.of();
        Location center = caster.getLocation();
        if (center == null || center.getWorld() == null || !NativeLocationTargetFilters.finite(center)
                || center.getX() < Integer.MIN_VALUE || center.getX() >= Integer.MAX_VALUE
                || center.getZ() < Integer.MIN_VALUE || center.getZ() >= Integer.MAX_VALUE) return List.of();
        try {
            Map<String, String> args = options(spec);
            String requested = first(args, "type", "t");
            StructureType type = StructureType.STRONGHOLD;
            if (requested != null) for (StructureType candidate : StructureType.getStructureTypes().values()) {
                if (candidate.getName().equalsIgnoreCase(requested) || candidate.toString().equalsIgnoreCase(requested)) {
                    type = candidate;
                    break;
                }
            }
            // Do not cache, restrict to loaded chunks, mutate coordinates or resolve static placeholders.
            // Paper owns generation, StructuresLocateEvent and unexplored-reference side effects.
            Location found = center.getWorld().locateNearestStructure(center, type, radius(args),
                    flag(first(args, "unexplored", "u"), false));
            if (found == null || found.getWorld() == null || !NativeLocationTargetFilters.finite(found)) return List.of();
            return NativeLocationTargetFilters.apply(List.of(found), args, context, resolver, false);
        } catch (IllegalArgumentException | IllegalStateException invalid) { return List.of(); }
    }
}
