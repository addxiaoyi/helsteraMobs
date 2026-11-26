package dev.helstera.api.behavior;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

import static dev.helstera.api.behavior.NativeTargeterOptions.*;

/** Shared public native location mutations and post-query filters. */
final class NativeLocationTargetFilters {
    private NativeLocationTargetFilters() { }
    private static final Map<String, List<String>> NUMERIC = Map.ofEntries(
            Map.entry("x", List.of("xoffset", "xo", "x")), Map.entry("y", List.of("yoffset", "yo", "y")),
            Map.entry("z", List.of("zoffset", "zo", "z")), Map.entry("forward", List.of("forwardoffset", "foffset", "fo")),
            Map.entry("side", List.of("sideoffset", "soffset", "so")), Map.entry("length", List.of("length")),
            Map.entry("cx", List.of("coordinatex", "cx")), Map.entry("cy", List.of("coordinatey", "cy")),
            Map.entry("cz", List.of("coordinatez", "cz")), Map.entry("yaw", List.of("coordinateyaw", "cyaw")),
            Map.entry("pitch", List.of("coordinatepitch", "cpitch")));

    static Location mutate(Location point, Map<String, String> raw) {
        Map<String, String> mutation = Map.of("faulty", raw.getOrDefault("faulty", "true"),
                "centered", Boolean.toString(flag(first(raw, "blockcentered", "centered"), false)));
        Location result = InheritedTargetLocations.mutate(point, mutation, numericValues(raw));
        if (!finite(result)) throw new IllegalArgumentException("Non-finite location");
        return result;
    }

    static List<Location> apply(List<Location> inherited, Map<String, String> raw,
                                BehaviorContext context, UnaryOperator<String> resolver, boolean mutate) {
        return apply(inherited, raw, context, resolver, mutate, true);
    }

    static List<Location> apply(List<Location> inherited, Map<String, String> raw,
                                BehaviorContext context, UnaryOperator<String> resolver, boolean mutate, boolean deduplicate) {
        java.util.Collection<Location> unique = deduplicate ? new LinkedHashSet<>() : new ArrayList<>();
        for (Location point : inherited) {
            if (point == null || point.getWorld() == null) continue;
            unique.add(mutate ? mutate(point, raw) : point.clone());
        }
        List<Location> selected = new ArrayList<>(unique);
        String ignored = first(raw, "blockignores", "blockignore", "bi");
        String allowed = first(raw, "blocktypes", "blocktype", "bt");
        if (ignored != null) {
            String filter = resolver.apply(ignored);
            selected.removeIf(location -> matches(location.getBlock().getType(), filter));
        }
        if (allowed != null) {
            String filter = resolver.apply(allowed);
            selected.removeIf(location -> !matches(location.getBlock().getType(), filter));
        }
        int limit = staticInteger(raw.get("limit"), 0);
        if (limit > 0) {
            String sort = first(raw, "sort", "sortby");
            if ("NEAREST".equalsIgnoreCase(sort) || "FURTHEST".equalsIgnoreCase(sort)) {
                Location origin = context.origin().orElse(null);
                Comparator<Location> comparator = Comparator.comparingDouble(location ->
                        origin == null || !location.getWorld().equals(origin.getWorld())
                                ? Double.POSITIVE_INFINITY : origin.distanceSquared(location));
                selected.sort("FURTHEST".equalsIgnoreCase(sort) ? comparator.reversed() : comparator);
            } else if ("RANDOM".equalsIgnoreCase(sort)) java.util.Collections.shuffle(selected);
            if (selected.size() > limit) selected = new ArrayList<>(selected.subList(0, limit));
        }
        return List.copyOf(selected);
    }

    private static Map<String, Double> numericValues(Map<String, String> raw) {
        Map<String, Double> values = new LinkedHashMap<>();
        NUMERIC.forEach((key, aliases) -> {
            String value = first(raw, aliases.toArray(String[]::new));
            double number = value == null ? 0 : publicDouble(value);
            values.put(key, key.equals("yaw") || key.equals("pitch") ? (double) (float) number : number);
        });
        return values;
    }

    static int staticInteger(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    /** Public PlaceholderDouble accepts literals/ranges; other inputs fall back to one. */
    static double publicDouble(String value) {
        if (value.matches("^-?[0-9]*(\\.\\d+)?(to|-)+[0-9]\\d*(\\.\\d+)?")) {
            if (value.contains("to") || !value.startsWith("-")) {
                String[] ends = value.split(value.contains("to") ? "to" : "-");
                double min = number(ends[0], 1), max = number(ends[1], 1);
                return min + Math.random() * (max - min);
            }
        }
        return number(value, 1);
    }

    static boolean matches(Material material, String filter) {
        for (String value : filter.split(",")) {
            String pattern = value.toUpperCase(Locale.ROOT);
            if (pattern.startsWith("#") ? material.name().contains(pattern.substring(1))
                    : pattern.startsWith("@") ? material.name().startsWith(pattern.substring(1))
                    : material == Material.valueOf(pattern)) return true;
        }
        return false;
    }

    static boolean finite(Location location) {
        return Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ())
                && Float.isFinite(location.getYaw()) && Float.isFinite(location.getPitch());
    }
}
