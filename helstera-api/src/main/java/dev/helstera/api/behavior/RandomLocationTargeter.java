package dev.helstera.api.behavior;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.UnaryOperator;

import static dev.helstera.api.behavior.NativeTargeterOptions.*;

/** Public native random location sampling, with per-invocation work limits. */
public final class RandomLocationTargeter {
    public static final Set<String> NAMES = Set.of("randomlocationsnearorigin", "rlo", "randomlocationsorigin", "rlno",
            "randomlocationsneartargets", "rlnt", "randomlocationsnearcaster", "rlnc", "randomlocations");
    public static final int MAX_ATTEMPTS = 262_144;
    public static final int MAX_BLOCK_READS = 262_144;
    public static final int MAX_DISTANCE_CHECKS = 262_144;
    public static final int MAX_RESULTS = 4096;

    private RandomLocationTargeter() { }

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
        return select(spec, context, resolver, () -> ThreadLocalRandom.current().nextDouble());
    }

    // A scoped sampler makes collision retries and rejection budgets reproducible in tests.
    static List<Location> select(String spec, BehaviorContext context, UnaryOperator<String> resolver, DoubleSupplier random) {
        try {
            if (!valid(spec)) return List.of();
            Map<String, String> raw = options(spec);
            boolean targets = Set.of("randomlocationsneartargets", "rlnt").contains(name(spec));
            boolean origin = Set.of("randomlocationsnearorigin", "rlo", "randomlocationsorigin", "rlno").contains(name(spec));
            List<Location> centers;
            if (targets) centers = !context.entityTargets().isEmpty()
                    ? context.entityTargets().stream().map(entity -> entity.getLocation()).toList() : context.locationTargets();
            else if (origin) centers = context.origin().map(List::of).orElseGet(List::of);
            else {
                if (!context.instanceValid()) return List.of();
                centers = context.instance().baseEntity().map(entity -> entity.getLocation())
                        .or(() -> java.util.Optional.ofNullable(context.instance().location())).map(List::of).orElseGet(List::of);
            }
            String amountValue = first(raw, "amount", "a");
            int amount = amountValue == null ? 5 : integer(amountValue);
            if (amount <= 0 || centers.isEmpty()) return List.of();
            long attempts = (long) amount * amount;
            if (attempts > MAX_ATTEMPTS || attempts * centers.size() > MAX_ATTEMPTS)
                throw new IllegalArgumentException("Random location attempt cap exceeded");
            String maximumValue = first(raw, "radius", "r", "maxradius", "maxr");
            String minimumValue = first(raw, "minradius", "minr");
            int maximum = targets && maximumValue != null ? integer(maximumValue)
                    : NativeLocationTargetFilters.staticInteger(maximumValue, 5);
            int minimum = targets && minimumValue != null ? integer(minimumValue)
                    : NativeLocationTargetFilters.staticInteger(minimumValue, 0);
            int spacing = NativeLocationTargetFilters.staticInteger(first(raw, "spacing", "s"), 0);
            // Native origin/caster and spacing use int multiplication; targets use Math.pow.
            double minimumSquared = targets ? Math.pow(minimum, 2) : minimum * minimum;
            int spacingSquared = spacing * spacing;
            Collection<Location> points = targets ? new ArrayList<>() : new LinkedHashSet<>();
            Budget budget = new Budget();
            for (Location center : centers) {
                if (!usable(center)) continue;
                int accepted = 0;
                for (int attempt = 0; attempt < attempts && accepted < amount; attempt++) {
                    Location candidate = sample(center, maximum, !targets && !origin, random, budget);
                    if (minimum > 0 && candidate.distanceSquared(center) < minimumSquared) continue;
                    boolean tooClose = false;
                    if (spacing > 0) for (Location previous : points) {
                        if (++budget.distances > MAX_DISTANCE_CHECKS)
                            throw new IllegalArgumentException("Random location distance cap exceeded");
                        if (candidate.distanceSquared(previous) < spacingSquared) { tooClose = true; break; }
                    }
                    if (tooClose) continue;
                    Location point = NativeLocationTargetFilters.mutate(candidate, raw);
                    if (!usable(point)) throw new IllegalArgumentException("Invalid random location");
                    points.add(point);
                    if (points.size() > MAX_RESULTS) throw new IllegalArgumentException("Random location output cap exceeded");
                    // Duplicates consume acceptance slots, even for the native HashSet variants.
                    accepted++;
                }
            }
            return NativeLocationTargetFilters.apply(new ArrayList<>(points), raw, context, resolver, false, false);
        } catch (IllegalArgumentException | IllegalStateException invalid) { return List.of(); }
    }

    private static final class Budget { private int blocks; private int distances; }

    private static boolean usable(Location point) {
        return point != null && point.getWorld() != null && NativeLocationTargetFilters.finite(point)
                && point.getX() >= Integer.MIN_VALUE && point.getX() < Integer.MAX_VALUE
                && point.getY() >= Integer.MIN_VALUE && point.getY() < Integer.MAX_VALUE
                && point.getZ() >= Integer.MIN_VALUE && point.getZ() < Integer.MAX_VALUE;
    }

    private static boolean solid(Location point, Budget budget) {
        if (!usable(point)) throw new IllegalArgumentException("Invalid random sample");
        if (++budget.blocks > MAX_BLOCK_READS) throw new IllegalArgumentException("Random location block cap exceeded");
        return point.getBlock().getType().isSolid();
    }

    private static Location sample(Location center, int radius, boolean vertical, DoubleSupplier random, Budget budget) {
        Location candidate = draw(center, radius, vertical, random);
        int retries = 10;
        while (solid(candidate, budget)) {
            candidate = draw(center, radius, vertical, random);
            // The tenth retry is discarded without testing it, including when it would be air.
            if (--retries == 0) return new Location(center.getWorld(), center.getX(), center.getY() + 1, center.getZ());
        }
        return candidate;
    }

    private static Location draw(Location center, int radius, boolean vertical, DoubleSupplier random) {
        double r = radius <= 0 ? 1 : radius;
        double x = center.getX() - r + random.getAsDouble() * (r * 2);
        double z = center.getZ() - r + random.getAsDouble() * (r * 2);
        double y = vertical ? center.getY() - 1 + random.getAsDouble() * 2 : center.getY();
        return new Location(center.getWorld(), x, y, z);
    }
}
