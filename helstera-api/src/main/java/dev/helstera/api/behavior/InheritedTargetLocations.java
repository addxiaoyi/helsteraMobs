package dev.helstera.api.behavior;

import dev.helstera.api.util.NumericExpression;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/** LocationsOfTargets and its pinned ILocationSelector mutation/filter contract. */
final class InheritedTargetLocations {
    private static final Set<String> NUMERIC = Set.of("x", "y", "z", "forward", "side", "length",
            "cx", "cy", "cz", "yaw", "pitch", "rotatex", "rotatey", "rotatez");
    private InheritedTargetLocations() { }

    static boolean valid(List<String> arguments) {
        try { options(arguments); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    static List<Location> select(List<String> arguments, BehaviorContext context, UnaryOperator<String> resolver) {
        try {
            Map<String, String> options = options(arguments);
            Map<String, Double> values = new LinkedHashMap<>();
            for (String key : NUMERIC) values.put(key, NumericExpression.resolve(options.getOrDefault(key, "0"), resolver));
            // Native prefers its inherited entity collection, including an empty collection,
            // to the location collection. A location continuation has no entity recipient.
            List<Location> inherited = new ArrayList<>();
            if (context.entityTargetsPresent()) {
                for (var target : context.entityTargets()) inherited.add(target.getLocation());
                if (context.targets().isEmpty() && context.targetLocation().isEmpty())
                    context.entityRecipient().ifPresent(entity -> inherited.add(entity.getLocation()));
            } else context.targetLocation().ifPresent(inherited::add);
            var unique = new LinkedHashSet<Location>();
            for (Location point : inherited) {
                if (point == null || point.getWorld() == null) continue;
                Location location = mutate(point, options, values);
                if (!finite(location)) return List.of();
                unique.add(location);
            }
            List<Location> selected = new ArrayList<>(unique);
            if (options.containsKey("ignore")) {
                List<String> blocks = blocks(resolver.apply(options.get("ignore")));
                selected.removeIf(location -> matches(location.getBlock().getType(), blocks));
            }
            if (options.containsKey("blocks")) {
                List<String> blocks = blocks(resolver.apply(options.get("blocks")));
                selected.removeIf(location -> !matches(location.getBlock().getType(), blocks));
            }
            int limit = Integer.parseInt(options.getOrDefault("limit", "0"));
            // Native sorts only when a positive limit is configured. Unordered native sets
            // have no stable order; retain inherited order for deterministic local execution.
            if (limit > 0) {
                String sort = options.getOrDefault("sort", "NONE").toUpperCase(Locale.ROOT);
                Location origin = context.origin().orElse(null);
                if (sort.equals("NEAREST") || sort.equals("FURTHEST")) {
                    Comparator<Location> comparator = Comparator.comparingDouble(location -> distance(origin, location));
                    selected.sort(sort.equals("FURTHEST") ? comparator.reversed() : comparator);
                } else if (sort.equals("RANDOM")) java.util.Collections.shuffle(selected);
                if (selected.size() > limit) selected = new ArrayList<>(selected.subList(0, limit));
            }
            return List.copyOf(selected);
        } catch (IllegalArgumentException invalid) {
            return List.of();
        }
    }

    private static Map<String, String> options(List<String> arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments) {
            int equals = argument.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("Location argument must be named");
            String key = canonical(argument.substring(0, equals).trim().toLowerCase(Locale.ROOT));
            String value = argument.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (key == null || value.isBlank() || values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Unknown, blank or duplicate location argument");
            }
            if (NUMERIC.contains(key)) NumericExpression.validate(value);
            else if (key.equals("limit")) Integer.parseInt(value);
            else if (key.equals("centered") || key.equals("faulty")) {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("Invalid location boolean");
                }
            } else if (key.equals("sort")) {
                if (!Set.of("NONE", "NEAREST", "FURTHEST", "RANDOM").contains(value.toUpperCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Invalid location sort");
                }
            } else if (!value.contains("<")) blocks(value);
        }
        return values;
    }

    private static String canonical(String key) {
        return switch (key) {
            case "xoffset", "xo", "x" -> "x";
            case "yoffset", "yo", "y" -> "y";
            case "zoffset", "zo", "z" -> "z";
            case "forwardoffset", "foffset", "fo" -> "forward";
            case "sideoffset", "soffset", "so" -> "side";
            case "length", "limit", "faulty" -> key;
            case "blockcentered", "centered" -> "centered";
            case "coordinatex", "cx" -> "cx";
            case "coordinatey", "cy" -> "cy";
            case "coordinatez", "cz" -> "cz";
            case "coordinateyaw", "cyaw" -> "yaw";
            case "coordinatepitch", "cpitch" -> "pitch";
            case "rotatex", "rotx" -> "rotatex";
            case "rotatey", "roty" -> "rotatey";
            case "rotatez", "rotz" -> "rotatez";
            case "blocktypes", "blocktype", "bt" -> "blocks";
            case "blockignores", "blockignore", "bi" -> "ignore";
            case "sort", "sortby" -> "sort";
            default -> null;
        };
    }

    private static Location mutate(Location source, Map<String, String> options, Map<String, Double> values) {
        Location location = source.clone().add(values.get("x"), values.get("y"), values.get("z"));
        double forward = values.get("forward"), side = values.get("side");
        if (forward != 0 || side != 0) {
            // The pinned native default intentionally uses MythicUtil.faultyMove; the false
            // variant uses its rotate helper. Both use float radians, retained here.
            double yaw = location.getYaw() * 0.017453292f;
            double pitch = location.getPitch() * 0.017453292f;
            Vector offset;
            if (Boolean.parseBoolean(options.getOrDefault("faulty", "true"))) {
                offset = new Vector(forward * Math.sin(yaw) - side * Math.cos(yaw) * Math.cos(pitch),
                        -side * Math.sin(pitch), -forward * Math.cos(yaw) - side * Math.sin(yaw) * Math.cos(pitch));
            } else {
                offset = new Vector(-side, 0, -forward).rotateAroundX(pitch).rotateAroundY(-yaw);
            }
            location.add(offset);
        }
        if (values.get("length") != 0) location.add(location.getDirection().multiply(values.get("length")));
        if (values.get("cx") != 0) location.setX(values.get("cx"));
        if (values.get("cy") != 0) location.setY(values.get("cy"));
        if (values.get("cz") != 0) location.setZ(values.get("cz"));
        if (values.get("yaw") != 0) location.setYaw(values.get("yaw").floatValue());
        if (values.get("pitch") != 0) location.setPitch(values.get("pitch").floatValue());
        // rotatex/y/z are read by native but never applied by its mutate implementation.
        if (Boolean.parseBoolean(options.getOrDefault("centered", "false"))) {
            location.setX(location.getBlockX() + .5);
            location.setZ(location.getBlockZ() + .5);
        }
        return location;
    }

    private static List<String> blocks(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing block filter");
        List<String> blocks = new ArrayList<>();
        for (String raw : value.split(",", -1)) {
            String block = raw.trim().toUpperCase(Locale.ROOT);
            if (block.isEmpty()) throw new IllegalArgumentException("Empty block filter");
            if (block.startsWith("#") || block.startsWith("@")) {
                if (block.length() == 1) throw new IllegalArgumentException("Empty block pattern");
            } else Material.valueOf(block);
            blocks.add(block);
        }
        return blocks;
    }

    private static boolean matches(Material material, List<String> blocks) {
        String name = material.name();
        return blocks.stream().anyMatch(block -> block.startsWith("#") ? name.contains(block.substring(1))
                : block.startsWith("@") ? name.startsWith(block.substring(1)) : name.equals(block));
    }

    private static boolean finite(Location location) {
        return Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ())
                && Float.isFinite(location.getYaw()) && Float.isFinite(location.getPitch());
    }

    private static double distance(Location origin, Location target) {
        return origin == null || origin.getWorld() == null || !origin.getWorld().equals(target.getWorld())
                ? Double.POSITIVE_INFINITY : origin.distanceSquared(target);
    }
}
