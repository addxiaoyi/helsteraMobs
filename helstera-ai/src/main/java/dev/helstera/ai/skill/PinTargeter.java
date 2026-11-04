package dev.helstera.ai.skill;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves Mythic's position-valued {@code @Pin} targeter. */
public final class PinTargeter {
    private static final long MAX_REGION_BLOCKS = 16_384;

    private PinTargeter() { }

    public static boolean recognizes(String spec) {
        if (spec == null || spec.isBlank()) return false;
        String value = spec.trim();
        if (value.charAt(0) == '@') value = value.substring(1);
        int brace = value.indexOf('{');
        String name = (brace < 0 ? value : value.substring(0, brace))
                .replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return name.equals("pin") || name.equals("blocksinpinregion");
    }

    /** Invalid recognized targeters resolve to an empty list; non-Pin targeters return null. */
    public static List<Location> select(String spec, PinRegistry registry) {
        if (!recognizes(spec)) return null;
        if (registry == null) return List.of();
        try {
            MythicTargeterSpec parsed = MythicTargeterSpec.parse(spec);
            Map<String, String> args = parsed.arguments();
            String targeterName = parsed.name().replace("_", "").replace("-", "");
            if (targeterName.equals("blocksinpinregion")) {
                return blocksInPinRegion(args, parsed.positional(), registry);
            }
            if (!targeterName.equals("pin")) return List.of();
            String name = first(args, "name", "n", "pin", "p", "key", "k");
            boolean keyedName = name != null;
            if (name == null && parsed.positional().size() == 1) name = parsed.positional().get(0);
            if (name == null || name.isBlank() || parsed.positional().size() > 1
                    || keyedName && !parsed.positional().isEmpty()) {
                return List.of();
            }
            for (String key : args.keySet()) {
                if (!List.of("name", "n", "pin", "p", "key", "k", "random", "r")
                        .contains(key)) return List.of();
            }
            String randomValue = first(args, "random", "r");
            boolean random = false;
            if (randomValue != null) {
                if (randomValue.equalsIgnoreCase("true")) random = true;
                else if (!randomValue.equalsIgnoreCase("false")) return List.of();
            }
            List<Location> locations = registry.locations(name);
            if (!random || locations.size() <= 1) return locations;
            return List.of(locations.get(java.util.concurrent.ThreadLocalRandom.current()
                    .nextInt(locations.size())));
        } catch (RuntimeException malformed) {
            return List.of();
        }
    }

    private static List<Location> blocksInPinRegion(Map<String, String> args,
                                                     List<String> positional,
                                                     PinRegistry registry) {
        if (!positional.isEmpty()) return List.of();
        for (String key : args.keySet()) {
            if (!List.of("pin1", "p1", "pin2", "p2").contains(key)) return List.of();
        }
        String firstName = first(args, "pin1", "p1");
        String secondName = first(args, "pin2", "p2");
        if (firstName == null || firstName.isBlank() || secondName == null || secondName.isBlank()) {
            return List.of();
        }
        Map<BlockCoordinate, Location> blocks = new LinkedHashMap<>();
        for (Location first : registry.locations(firstName)) {
            for (Location second : registry.locations(secondName)) {
                if (first.getWorld() == null || first.getWorld() != second.getWorld()) continue;
                int minX = Math.min(first.getBlockX(), second.getBlockX());
                int maxX = Math.max(first.getBlockX(), second.getBlockX());
                int minY = Math.min(first.getBlockY(), second.getBlockY());
                int maxY = Math.max(first.getBlockY(), second.getBlockY());
                int minZ = Math.min(first.getBlockZ(), second.getBlockZ());
                int maxZ = Math.max(first.getBlockZ(), second.getBlockZ());
                long sizeX = (long) maxX - minX + 1;
                long sizeY = (long) maxY - minY + 1;
                long sizeZ = (long) maxZ - minZ + 1;
                if (sizeX > MAX_REGION_BLOCKS || sizeY > MAX_REGION_BLOCKS
                        || sizeZ > MAX_REGION_BLOCKS || sizeX * sizeY > MAX_REGION_BLOCKS
                        || sizeX * sizeY * sizeZ > MAX_REGION_BLOCKS) return List.of();
                long volume = sizeX * sizeY * sizeZ;
                if (volume > MAX_REGION_BLOCKS || (long) blocks.size() + volume > MAX_REGION_BLOCKS) {
                    return List.of();
                }
                World world = first.getWorld();
                for (long x = minX; x <= maxX; x++) {
                    for (long y = minY; y <= maxY; y++) {
                        for (long z = minZ; z <= maxZ; z++) {
                            int blockX = (int) x;
                            int blockY = (int) y;
                            int blockZ = (int) z;
                            BlockCoordinate key = new BlockCoordinate(world, blockX, blockY, blockZ);
                            blocks.putIfAbsent(key, new Location(world, blockX, blockY, blockZ));
                        }
                    }
                }
            }
        }
        return List.copyOf(new ArrayList<>(blocks.values()));
    }

    private record BlockCoordinate(World world, int x, int y, int z) { }

    private static String first(Map<String, String> args, String... keys) {
        for (String key : keys) {
            String value = args.get(key);
            if (value != null) return value;
        }
        return null;
    }
}
