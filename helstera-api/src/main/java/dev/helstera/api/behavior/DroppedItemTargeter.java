package dev.helstera.api.behavior;

import static dev.helstera.api.behavior.NativeTargeterOptions.*;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Item-valued queries and the public 5.7.2 entity-selector filters. */
public final class DroppedItemTargeter {
    public static final Set<String> NAMES = Set.of("itemsinradius", "iir", "itemsnearorigin", "ino");

    private DroppedItemTargeter() { }

    public static boolean recognizes(String spec) {
        if (spec == null) return false;
        String name = spec.trim().replaceFirst("^@", "");
        int open = name.indexOf('{');
        return NAMES.contains((open < 0 ? name : name.substring(0, open)).trim().toLowerCase(Locale.ROOT));
    }

    /** Validate syntax without resolving placeholders or touching the world. */
    public static List<Targeters.ConditionalCondition> conditions(String spec) {
        return NativeTargeterOptions.conditions(options(spec));
    }

    static List<Entity> select(String spec, BehaviorContext context, Targeters.ConditionProvider conditions,
                               Targeters.EntityThreatProvider threatProvider) {
        if (context == null || !context.instanceValid()) return List.of();
        try {
            Map<String, String> options = options(spec);
            List<Targeters.ConditionalCondition> guards = NativeTargeterOptions.conditions(options);
            if (!guards.isEmpty() && conditions == null) return List.of();
            boolean nearOrigin = options.get("@name").equals("ino") || options.get("@name").equals("itemsnearorigin");
            double radius = nearOrigin ? number(options.get("r"), number(options.get("radius"), 5))
                    : number(first(options, "radius", "r"), 5);
            if (!Double.isFinite(radius)) return List.of();
            Entity caster = context.instance().baseEntity().orElse(null);
            Location origin = context.origin().orElse(context.instance().location());
            Location center = nearOrigin ? origin : context.instance().location();
            Location distanceCenter = nearOrigin ? origin : caster == null ? null : caster.getLocation();
            if (!finite(center) || !finite(distanceCenter) || !center.getWorld().equals(distanceCenter.getWorld()))
                return List.of();
            boolean boundingBox = flag(first(options, "bb", "useboundingbox"), false);
            double minX = Math.floor((center.getX() - radius) / 16), maxX = Math.floor((center.getX() + radius) / 16);
            double minZ = Math.floor((center.getZ() - radius) / 16), maxZ = Math.floor((center.getZ() + radius) / 16);
            double squaredRadius = Math.pow(radius, 2);
            Set<Entity> unique = new LinkedHashSet<>();
            // Iterate loaded chunks rather than the potentially enormous radius rectangle.
            // A nearby-entity AABB query would incorrectly omit points in the same chunk,
            // especially for negative radii and bounding-box distance at chunk boundaries.
            for (Chunk chunk : center.getWorld().getLoadedChunks()) {
                if (chunk.getX() < minX || chunk.getX() > maxX || chunk.getZ() < minZ || chunk.getZ() > maxZ) continue;
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Item && distanceSquared(distanceCenter, entity, boundingBox) < squaredRadius)
                        unique.add(entity);
                }
            }
            return NativeEntityTargetFilters.apply(new ArrayList<>(unique), options, context, conditions, threatProvider);
        } catch (IllegalArgumentException invalid) {
            return List.of();
        }
    }

    private static double distanceSquared(Location point, Entity entity, boolean boundingBox) {
        if (!finite(point) || !point.getWorld().equals(entity.getWorld())) return Double.POSITIVE_INFINITY;
        if (!boundingBox) return point.distanceSquared(entity.getLocation());
        var box = entity.getBoundingBox();
        double x = Math.max(Math.abs(point.getX() - box.getCenterX()) - box.getWidthX() / 2, 0);
        double y = Math.max(Math.abs(point.getY() - box.getCenterY()) - box.getHeight() / 2, 0);
        double z = Math.max(Math.abs(point.getZ() - box.getCenterZ()) - box.getWidthZ() / 2, 0);
        return x * x + y * y + z * z;
    }

    private static boolean finite(Location point) {
        return point != null && point.getWorld() != null && Double.isFinite(point.getX())
                && Double.isFinite(point.getY()) && Double.isFinite(point.getZ());
    }

}
