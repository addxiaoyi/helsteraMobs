package dev.helstera.api.behavior;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static dev.helstera.api.behavior.NativeTargeterOptions.*;

/** Distinct public targetBlock, targetedLocation and targetedTarget contracts. */
public final class AimingInheritedTargeter {
    public static final Set<String> NAMES = Set.of("targetblock", "targetedlocation", "targetedlocations",
            "targetedloc", "targetedtarget", "targeted");
    private static final Map<String, List<String>> NUMERIC = Map.ofEntries(
            Map.entry("x", List.of("xoffset", "xo", "x")), Map.entry("y", List.of("yoffset", "yo", "y")),
            Map.entry("z", List.of("zoffset", "zo", "z")), Map.entry("forward", List.of("forwardoffset", "foffset", "fo")),
            Map.entry("side", List.of("sideoffset", "soffset", "so")), Map.entry("length", List.of("length")),
            Map.entry("cx", List.of("coordinatex", "cx")), Map.entry("cy", List.of("coordinatey", "cy")),
            Map.entry("cz", List.of("coordinatez", "cz")), Map.entry("yaw", List.of("coordinateyaw", "cyaw")),
            Map.entry("pitch", List.of("coordinatepitch", "cpitch")));
    private static final class Transparency {
        private static final Set<Material> BLOCKS = Arrays.stream(Material.values())
                .filter(material -> material.isTransparent() || !material.isSolid() || !material.isOccluding())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** A nonnull state represents an ActiveMob, even when its saved target is absent. */
    public record CasterTarget(Entity target) { }
    @FunctionalInterface
    public interface CasterTargetProvider {
        CasterTarget target(ModelInstance source);
    }
    private static volatile CasterTargetProvider casterTargetProvider;

    private AimingInheritedTargeter() { }

    public static synchronized void casterTargetProvider(CasterTargetProvider provider) { casterTargetProvider = provider; }
    public static synchronized void clearCasterTargetProvider(CasterTargetProvider expected) {
        if (expected != null && casterTargetProvider == expected) casterTargetProvider = null;
    }

    private static String name(String spec) {
        if (spec == null) return "";
        String raw = spec.trim().replaceFirst("^@", "");
        int open = raw.indexOf('{');
        return (open < 0 ? raw : raw.substring(0, open)).trim().toLowerCase(Locale.ROOT);
    }

    public static boolean recognizes(String spec) { return NAMES.contains(name(spec)); }
    public static boolean isEntity(String spec) { return Set.of("targetedtarget", "targeted").contains(name(spec)); }
    public static boolean isLocation(String spec) { return recognizes(spec) && !isEntity(spec); }
    public static List<Targeters.ConditionalCondition> conditions(String spec) {
        return NativeTargeterOptions.conditions(options(spec));
    }
    static boolean valid(String spec) {
        try { conditions(spec); return recognizes(spec); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    static List<Entity> entities(String spec, BehaviorContext context, Targeters.ConditionProvider conditions,
                                 Targeters.EntityThreatProvider threat) {
        try {
            // Metadata presence/emptiness is authoritative: do not query nearby or combat targets.
            return NativeEntityTargetFilters.apply(context.entityTargets(), options(spec), context, conditions, threat);
        } catch (IllegalArgumentException invalid) { return List.of(); }
    }

    static List<Location> locations(String spec, BehaviorContext context, UnaryOperator<String> resolver) {
        try {
            Map<String, String> raw = options(spec);
            List<Location> inherited = name(spec).equals("targetblock") ? targetBlock(raw, context) : context.locationTargets();
            Map<String, String> mutation = Map.of("faulty", raw.getOrDefault("faulty", "true"),
                    "centered", Boolean.toString(flag(first(raw, "blockcentered", "centered"), false)));
            var unique = new LinkedHashSet<Location>();
            for (Location point : inherited) {
                if (point == null || point.getWorld() == null) continue;
                Location selected = InheritedTargetLocations.mutate(point, mutation, numericValues(raw));
                if (!finite(selected)) return List.of();
                unique.add(selected);
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
        } catch (IllegalArgumentException | IllegalStateException invalid) { return List.of(); }
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

    private static List<Location> targetBlock(Map<String, String> raw, BehaviorContext context) {
        if (!context.instanceValid()) return List.of();
        Entity caster = context.instance().baseEntity().orElse(null);
        if (caster == null) return List.of();
        CasterTargetProvider provider = casterTargetProvider;
        CasterTarget state = provider == null ? null : provider.target(context.instance());
        if (state != null) return state.target() == null ? List.of() : List.of(state.target().getLocation());
        if (ManagedMobIdentity.isManaged(caster)) {
            Entity target = caster instanceof Creature creature ? creature.getTarget() : null;
            return target == null ? List.of() : List.of(target.getLocation());
        }
        if (!(caster instanceof Player player)) return List.of();
        int maximum = staticInteger(first(raw, "maxdistance", "max", "distance", "d"), 64);
        Set<Material> transparent = flag(first(raw, "ignoretransparent", "it"), true)
                ? Transparency.BLOCKS : Set.of(Material.AIR);
        return List.of(player.getTargetBlock(transparent, maximum).getLocation());
    }

    private static int staticInteger(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    /** Public PlaceholderDouble accepts literals/ranges; other inputs fall back to one. */
    private static double publicDouble(String value) {
        if (value.matches("^-?[0-9]*(\\.\\d+)?(to|-)+[0-9]\\d*(\\.\\d+)?")) {
            if (value.contains("to") || !value.startsWith("-")) {
                String[] ends = value.split(value.contains("to") ? "to" : "-");
                double min = number(ends[0], 1), max = number(ends[1], 1);
                return min + Math.random() * (max - min);
            }
        }
        return number(value, 1);
    }

    private static boolean matches(Material material, String filter) {
        for (String value : filter.split(",")) {
            String pattern = value.toUpperCase(Locale.ROOT);
            if (pattern.startsWith("#") ? material.name().contains(pattern.substring(1))
                    : pattern.startsWith("@") ? material.name().startsWith(pattern.substring(1))
                    : material == Material.valueOf(pattern)) return true;
        }
        return false;
    }

    private static boolean finite(Location location) {
        return Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ())
                && Float.isFinite(location.getYaw()) && Float.isFinite(location.getPitch());
    }
}
