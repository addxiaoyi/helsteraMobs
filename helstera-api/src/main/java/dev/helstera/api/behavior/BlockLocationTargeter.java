package dev.helstera.api.behavior;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static dev.helstera.api.behavior.NativeTargeterOptions.*;

/** Public block-location selectors with bounded work and no implicit chunk generation. */
public final class BlockLocationTargeter {
    public static final Set<String> NAMES = Set.of("highestblock", "obstructingblock", "blocksinchunk", "bic",
            "blocksinradius", "bir", "blocksnearorigin", "bno", "blockvein", "vein", "bv");
    public static final int MAX_VISITS = 262_144;
    public static final int MAX_RESULTS = 131_072;

    private BlockLocationTargeter() { }

    private static String name(String spec) {
        if (spec == null) return "";
        String raw = spec.trim().replaceFirst("^@", "");
        int brace = raw.indexOf('{');
        return (brace < 0 ? raw : raw.substring(0, brace)).trim().toLowerCase(Locale.ROOT);
    }

    public static boolean recognizes(String spec) { return NAMES.contains(name(spec)); }

    static boolean valid(String spec) {
        try { conditions(options(spec)); return recognizes(spec); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    static List<Location> select(String spec, BehaviorContext context, UnaryOperator<String> resolver) {
        try {
            Map<String, String> raw = options(spec);
            Query query = new Query();
            switch (name(spec)) {
                case "highestblock" -> {
                    Location origin = context.origin().orElse(null);
                    if (loaded(origin)) query.add(NativeLocationTargetFilters.mutate(
                            origin.getWorld().getHighestBlockAt(origin).getLocation(), raw));
                }
                case "obstructingblock" -> obstruction(query, context);
                case "blockvein", "vein", "bv" -> vein(query, context.origin().orElse(null), raw, resolver);
                default -> {
                    boolean chunk = Set.of("blocksinchunk", "bic").contains(name(spec));
                    boolean origin = Set.of("blocksnearorigin", "bno").contains(name(spec))
                            || flag(first(raw, "nearorigin", "no"), false);
                    List<Location> centers = origin ? context.origin().map(List::of).orElseGet(List::of)
                            : !context.entityTargets().isEmpty()
                            ? context.entityTargets().stream().map(entity -> entity.getLocation()).toList()
                            : context.locationTargets();
                    for (Location center : new LinkedHashSet<>(centers)) {
                        if (chunk) chunk(query, center, raw);
                        else radius(query, center, raw);
                    }
                }
            }
            // Native chunk, obstruction and vein selectors do not invoke location mutation.
            // Radius selectors mutate before their AIR mask, highestblock before filtering.
            return NativeLocationTargetFilters.apply(query.points.stream().filter(BlockLocationTargeter::loaded).toList(),
                    raw, context, resolver, false);
        } catch (IllegalArgumentException | IllegalStateException invalid) { return List.of(); }
    }

    private static final class Query {
        private final Set<Location> points = new LinkedHashSet<>();
        private int visits;

        private void visit() {
            if (++visits > MAX_VISITS) throw new IllegalArgumentException("Block query visit cap exceeded");
        }

        private void reserve(long candidates) {
            if (candidates < 0 || candidates > MAX_VISITS - visits)
                throw new IllegalArgumentException("Block query visit cap exceeded");
            visits += (int) candidates;
        }

        private void add(Location point) {
            if (!NativeLocationTargetFilters.finite(point)) throw new IllegalArgumentException("Non-finite block location");
            points.add(point);
            if (points.size() > MAX_RESULTS) throw new IllegalArgumentException("Block query output cap exceeded");
        }
    }

    private static boolean loaded(Location point) {
        return point != null && point.getWorld() != null && NativeLocationTargetFilters.finite(point)
                && point.getX() >= Integer.MIN_VALUE && point.getX() < Integer.MAX_VALUE
                && point.getZ() >= Integer.MIN_VALUE && point.getZ() < Integer.MAX_VALUE
                && point.getWorld().isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4);
    }

    private static boolean mask(Location point, Map<String, String> raw) {
        if (!loaded(point)) return false;
        boolean onlyAir = flag(first(raw, "onlyair", "oa"), false);
        boolean noAir = flag(first(raw, "noair", "na"), true);
        if (!onlyAir && !noAir) return true;
        boolean air = point.getBlock().getType() == Material.AIR;
        return onlyAir ? air : !air;
    }

    private static void chunk(Query query, Location center, Map<String, String> raw) {
        if (!loaded(center)) return;
        int minimum = center.getWorld().getMinHeight(), maximum = center.getWorld().getMaxHeight();
        query.reserve(256L * ((long) maximum - minimum + 1));
        int x0 = (center.getBlockX() >> 4) << 4, z0 = (center.getBlockZ() >> 4) << 4;
        // The pinned loop includes maxHeight (VOID_AIR on Paper), not only buildable blocks.
        for (int x = 0; x < 16; x++) for (long y = minimum; y <= maximum; y++) for (int z = 0; z < 16; z++) {
            Location point = new Location(center.getWorld(), (double) x0 + x, y, (double) z0 + z);
            if (mask(point, raw)) query.add(point);
        }
    }

    private static void radius(Query query, Location center, Map<String, String> raw) {
        if (center == null || center.getWorld() == null || !NativeLocationTargetFilters.finite(center)) return;
        String radius = first(raw, "radius", "r");
        radius = radius == null ? "2" : radius;
        String vertical = first(raw, "radiusy", "ry", "yradius", "yr");
        double r = NativeLocationTargetFilters.publicDouble(radius);
        double ry = NativeLocationTargetFilters.publicDouble(vertical == null ? radius : vertical);
        if (!Double.isFinite(r) || !Double.isFinite(ry)) throw new IllegalArgumentException("Non-finite radius");
        if (r < 0 || ry < 0) return;
        double width = Math.floor(2 * r) + 1, height = Math.floor(2 * ry) + 1;
        if (width * width * height > MAX_VISITS) throw new IllegalArgumentException("Block radius cap exceeded");
        query.reserve((long) (width * width * height));
        String shape = first(raw, "shape", "s");
        boolean sphere = shape == null || shape.equalsIgnoreCase("SPHERE");
        double noise = number(first(raw, "noise", "n"), 0);
        if (!Double.isFinite(noise)) throw new IllegalArgumentException("Non-finite noise");
        double r2 = (int) Math.pow(r, 2), ry2 = (int) Math.pow(ry, 2);
        for (double x = -r; x <= r; x++) for (double y = -ry; y <= ry; y++) for (double z = -r; z <= r; z++) {
            if (noise > 0 && noise < Math.random()) continue;
            // Native dcmpl accepts NaN here: a zero squared axis does not exclude points.
            if (sphere && x * x / r2 + y * y / ry2 + z * z / r2 > 1) continue;
            Location point = NativeLocationTargetFilters.mutate(new Location(center.getWorld(),
                    center.getX() + x, center.getY() + y, center.getZ() + z), raw);
            if (mask(point, raw)) query.add(point);
        }
    }

    private static void obstruction(Query query, BehaviorContext context) {
        if (!context.instanceValid()) return;
        var caster = context.instance().baseEntity().orElse(null);
        if (caster == null) return;
        Location location = caster.getLocation();
        if (!loaded(location)) return;
        int quadrant = Math.round(location.getYaw() / 90f);
        BlockFace facing = switch (quadrant) {
            case -4, 0, 4 -> BlockFace.SOUTH;
            case -1, 3 -> BlockFace.EAST;
            case -3, 1 -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
        BlockFace left = switch (quadrant) {
            case -4, 0, 4 -> BlockFace.EAST;
            case -1, 3 -> BlockFace.NORTH;
            case -2, 2 -> BlockFace.WEST;
            case -3, 1 -> BlockFace.SOUTH;
            default -> null;
        };
        Location feet = new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        Location front = feet.clone().add(facing.getModX(), 0, facing.getModZ());
        List<Location> probes = new ArrayList<>(List.of(front, feet, front.clone().add(0, 1, 0), front.clone().add(0, 2, 0)));
        if (left != null) for (int sign : new int[]{1, -1}) for (int y = 0; y <= 2; y++)
            probes.add(front.clone().add(sign * left.getModX(), y, sign * left.getModZ()));
        for (Location probe : probes) {
            if (!loaded(probe)) return;
            if (!probe.getBlock().isPassable()) { query.add(probe); return; }
        }
    }

    private record VeinFrame(Location point, int remaining, java.util.Iterator<int[]> directions) { }

    private static java.util.Iterator<int[]> directions() {
        List<int[]> directions = new ArrayList<>();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++)
            if (x != 0 || y != 0 || z != 0) directions.add(new int[]{x, y, z});
        Collections.shuffle(directions);
        return directions.iterator();
    }

    private static void vein(Query query, Location origin, Map<String, String> raw, UnaryOperator<String> resolver) {
        if (!loaded(origin)) return;
        String types = first(raw, "blocktypes", "bt", "t", "material", "materials", "m", "blocks", "block", "b");
        types = resolver.apply(types == null ? "STONE" : types);
        String maximum = first(raw, "limit", "l", "max", "m");
        int limit = maximum == null ? 10 : integer(maximum);
        if (limit <= 0) return;
        if (flag(first(raw, "originmustmatch", "match"), true)
                && !NativeLocationTargetFilters.matches(origin.getBlock().getType(), types)) return;
        var stack = new ArrayDeque<VeinFrame>();
        stack.push(new VeinFrame(origin, limit, directions()));
        while (!stack.isEmpty()) {
            VeinFrame frame = stack.peek();
            if (!frame.directions().hasNext()) { stack.pop(); continue; }
            int[] step = frame.directions().next();
            query.visit();
            Location point = new Location(origin.getWorld(), frame.point().getX() + step[0],
                    frame.point().getY() + step[1], frame.point().getZ() + step[2]);
            if (query.points.contains(point) || !loaded(point)
                    || !NativeLocationTargetFilters.matches(point.getBlock().getType(), types)) continue;
            query.add(point);
            // Preserve the native entry-only size check and decreasing recursion budget.
            // The parent's remaining neighbor loop can exceed this budget; generic limit is separate.
            if (frame.remaining() > 1 && query.points.size() < frame.remaining() - 1)
                stack.push(new VeinFrame(point, frame.remaining() - 1, directions()));
        }
    }
}
