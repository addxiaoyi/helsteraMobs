package dev.helstera.api.behavior;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
        return conditions(options(spec));
    }

    static List<Entity> select(String spec, BehaviorContext context, Targeters.ConditionProvider conditions,
                               Targeters.EntityThreatProvider threatProvider) {
        if (context == null || !context.instanceValid()) return List.of();
        try {
            Map<String, String> options = options(spec);
            List<Targeters.ConditionalCondition> guards = conditions(options);
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
            List<Entity> selected = new ArrayList<>(unique);
            // Native evaluates each condition against the whole set before ordinary filters/limit.
            for (var guard : guards) {
                String expression = guard.expected() ? guard.expression() : "!(" + guard.expression() + ")";
                selected.removeIf(entity -> !entity.isValid() || entity.isDead()
                        || !conditions.test(expression, context.withEntityRecipient(entity)
                        .withConditionScope(BehaviorContext.ConditionScope.TARGET)));
            }
            String target = options.getOrDefault("target", "").toLowerCase(Locale.ROOT);
            String ignore = options.getOrDefault("ignore", "").toLowerCase(Locale.ROOT);
            boolean self = flag(options.get("targetself"), target.contains("self") || target.contains("caster"));
            boolean vanilla = flag(first(options, "targetvanilla", "targetnonmythic"), !ignore.contains("vanilla"));
            boolean sameFaction = flag(options.get("targetsamefaction"), !ignore.contains("faction"));
            boolean owner = flag(options.get("targetowner"), !ignore.contains("owner"));
            String casterFaction = faction(caster, true);
            UUID ownerId = ManagedMobIdentity.isManaged(caster) ? MobRelations.ownerId(caster) : null;
            selected.removeIf(entity -> target.equals("water") || target.equals("flying")
                    || !self && caster != null && entity.getUniqueId().equals(caster.getUniqueId())
                    || !vanilla && !ManagedMobIdentity.isManaged(entity)
                    || !sameFaction && casterFaction.equals(faction(entity, false))
                    || !owner && entity.getUniqueId().equals(ownerId));
            int limit = integer(options.getOrDefault("limit", "0"));
            if (limit > 0) {
                String sort = first(options, "sort", "sortby");
                sort = sort == null ? "NONE" : sort.toUpperCase(Locale.ROOT);
                switch (sort) {
                    case "NEAREST", "FURTHEST" -> {
                        Comparator<Entity> comparator = Comparator.comparingDouble(entity -> distanceSquared(origin, entity, false));
                        selected.sort(sort.equals("FURTHEST") ? comparator.reversed() : comparator);
                    }
                    case "RANDOM" -> Collections.shuffle(selected);
                    case "HIGHEST_THREAT", "LOWEST_THREAT" -> {
                        // No live table cannot be interpreted as all-zero threat.
                        Map<UUID, Double> threat = threatProvider == null ? null : threatProvider.snapshot(context.instance());
                        if (threat == null || threat.values().stream().anyMatch(value -> value == null || !Double.isFinite(value)))
                            return List.of();
                        Comparator<Entity> comparator = Comparator.comparingDouble(entity -> threat.getOrDefault(entity.getUniqueId(), 0.0));
                        selected.sort(sort.equals("HIGHEST_THREAT") ? comparator.reversed() : comparator);
                    }
                    // Native nonliving health is zero; health ties and unknown sort names
                    // have no guaranteed order. Retain discovery order locally.
                    default -> { }
                }
                if (selected.size() > limit) selected = new ArrayList<>(selected.subList(0, limit));
            }
            return List.copyOf(selected);
        } catch (IllegalArgumentException invalid) {
            return List.of();
        }
    }

    private static String faction(Entity entity, boolean caster) {
        if (entity == null) return "";
        if (ManagedMobIdentity.isManaged(entity) || entity instanceof Player) {
            FactionTable table = Factions.table();
            String value = table == null ? null : table.comparisonFactionOf(entity);
            return value == null ? "" : value;
        }
        if (!caster && entity.hasMetadata("Faction")) {
            var values = entity.getMetadata("Faction");
            if (!values.isEmpty()) return values.getFirst().asString();
        }
        return "";
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

    private static double number(String value, double fallback) {
        try { return value == null ? fallback : Double.parseDouble(value); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    private static boolean flag(String value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    /** Public-build PlaceholderInt falls back to 1 for non-integer/non-range input. */
    private static int integer(String value) {
        try {
            if (value.matches("-?\\d+")) return Integer.parseInt(value);
            if (!value.matches("-?[0-9]*(to|-)[0-9]\\d*")) return 1;
            String[] range = value.contains("to") ? value.split("to") : value.split("-");
            int min = Integer.parseInt(range[0]), max = Integer.parseInt(range[1]);
            return min == max ? min : (int) ThreadLocalRandom.current().nextLong(min, (long) max + 1);
        } catch (IllegalArgumentException invalid) { return 1; }
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) if (values.containsKey(key)) return values.get(key);
        return null;
    }

    private static List<Targeters.ConditionalCondition> conditions(Map<String, String> options) {
        String value = first(options, "targetconditions", "conditions", "cond", "c");
        if (value == null) return List.of();
        if (!value.startsWith("[") || !value.endsWith("]"))
            throw new IllegalArgumentException("Item target conditions require a bracketed list");
        String inner = value.substring(1, value.length() - 1).trim();
        if (inner.isEmpty()) return List.of();
        List<Targeters.ConditionalCondition> parsed = Targeters.parseConditionalConditions(inner.startsWith("-") ? inner : "- " + inner);
        if (parsed.isEmpty()) throw new IllegalArgumentException("Malformed item target conditions");
        return parsed;
    }

    private static Map<String, String> options(String spec) {
        if (!recognizes(spec)) throw new IllegalArgumentException("Not an item targeter");
        String raw = spec.trim().replaceFirst("^@", "");
        int open = raw.indexOf('{');
        Map<String, String> values = new LinkedHashMap<>();
        values.put("@name", (open < 0 ? raw : raw.substring(0, open)).trim().toLowerCase(Locale.ROOT));
        if (open < 0) return values;
        if (!raw.endsWith("}")) throw new IllegalArgumentException("Unclosed item targeter");
        String body = raw.substring(open + 1, raw.length() - 1);
        if (body.isBlank()) return values;
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i <= body.length(); i++) {
            char c = i == body.length() ? ';' : body.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '[' || c == '{' || c == '(') depth++;
            if (c == ']' || c == '}' || c == ')') depth--;
            if (depth < 0) throw new IllegalArgumentException("Unbalanced item targeter");
            if (c == ';' && depth == 0) {
                String argument = body.substring(start, i).trim();
                int equals = argument.indexOf('=');
                if (equals <= 0) throw new IllegalArgumentException("Item targeter arguments must be named");
                // Retain raw quoted values and alias keys: native scalar parsing does not
                // unquote values, and alias precedence differs between the two queries.
                values.put(argument.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                        argument.substring(equals + 1).replace("<&csp>", " ").replace("<&da>", "-").trim());
                start = i + 1;
            }
        }
        if (depth != 0 || quote != 0) throw new IllegalArgumentException("Unbalanced item targeter");
        return values;
    }
}
