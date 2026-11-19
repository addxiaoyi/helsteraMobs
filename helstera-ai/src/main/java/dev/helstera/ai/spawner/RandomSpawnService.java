package dev.helstera.ai.spawner;

import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/** Mythic RandomSpawns event actions and an opt-in, bounded ADD/CLUSTER subset. */
public final class RandomSpawnService implements Listener {
    private enum Action { ADD, REPLACE, DENY }
    private enum PositionType { LAND, SEA }

    private record Definition(String id, Action action, RandomSpawnPool types, double chance, int priority,
                              long cooldownNanos, Set<String> worlds, Set<String> biomes,
                              Set<CreatureSpawnEvent.SpawnReason> reasons,
                              List<String> conditions, PositionType positionType) { }

    /** Bounded generation controls; NONE preserves Mythic's 5.7.2 default. */
    public record AddSettings(String generator, int radiusMin, int radiusMax, int radiusY,
                              int maxSpawnsPerCycle, int maxMonstersPerWorld) {
        public AddSettings {
            generator = generator == null ? "NONE" : generator.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("NONE", "CLUSTER").contains(generator)) {
                throw new IllegalArgumentException("Only NONE and CLUSTER generators are supported");
            }
            if (radiusMin < 0 || radiusMax < 1 || radiusMin > radiusMax || radiusMax > 256
                    || radiusY < 0 || radiusY > 128 || maxSpawnsPerCycle < 1 || maxSpawnsPerCycle > 64
                    || maxMonstersPerWorld < 1 || maxMonstersPerWorld > 100_000) {
                throw new IllegalArgumentException("Random-spawn ADD limits are outside supported bounds");
            }
        }

        public static AddSettings disabled() { return new AddSettings("NONE", 12, 64, 16, 1, 70); }
        public boolean enabled() { return "CLUSTER".equals(generator); }
    }

    private final Logger logger;
    private final SpawnerService.MobSpawner mobSpawner;
    private final java.util.Random random;
    private final LongSupplier clock;
    private final List<Definition> definitions = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final ThreadLocal<Boolean> replacing = ThreadLocal.withInitial(() -> false);
    private volatile BiPredicate<String, CreatureSpawnEvent> conditionTester = (condition, event) -> false;
    private volatile BiPredicate<String, Location> addConditionTester;
    private final Set<String> addConditionWarnings = new java.util.HashSet<>();
    private volatile AddSettings addSettings = AddSettings.disabled();
    private int addCycle;

    public RandomSpawnService(Logger logger, SpawnerService.MobSpawner mobSpawner) {
        this(logger, mobSpawner, new java.util.Random(), System::nanoTime);
    }

    RandomSpawnService(Logger logger, SpawnerService.MobSpawner mobSpawner,
                       java.util.Random random, LongSupplier clock) {
        this.logger = logger;
        this.mobSpawner = java.util.Objects.requireNonNull(mobSpawner, "mobSpawner");
        this.random = java.util.Objects.requireNonNull(random, "random");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public void setConditionTester(BiPredicate<String, CreatureSpawnEvent> tester) {
        conditionTester = tester == null ? (condition, event) -> false : tester;
    }

    /** Tests ADD Conditions against a candidate point with no spawned entity context. */
    public void setAddConditionTester(BiPredicate<String, Location> tester) {
        addConditionTester = tester;
    }

    public void configureAdd(AddSettings settings) {
        addSettings = Objects.requireNonNull(settings, "settings");
        addCycle = 0;
    }

    public void loadFile(Path file) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file.toFile());
        } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
            definitions.clear();
            warnings.clear();
            throw new IOException("Invalid RandomSpawns configuration: " + file, ex);
        }
        load(config.getConfigurationSection("random-spawns"));
    }

    public void load(ConfigurationSection root) {
        definitions.clear();
        warnings.clear();
        cooldowns.clear();
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                warn("RandomSpawn " + id + " must be a section; ignored.");
                continue;
            }
            Action action;
            String actionName = section.getString("action", "");
            try {
                action = Action.valueOf((actionName == null ? "" : actionName).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                warn("RandomSpawn " + id + " action is unsupported; ignored.");
                continue;
            }
            if (section.getKeys(false).stream().anyMatch(key -> !Set.of("action", "types", "chance",
                    "priority", "cooldown", "worlds", "biomes", "reasons", "conditions", "position-type")
                    .contains(key.toLowerCase(Locale.ROOT)))) {
                warn("RandomSpawn " + id + " contains unsupported options; ignored fail-closed.");
                continue;
            }
            if (action == Action.DENY && section.contains("types")) {
                warn("RandomSpawn " + id + " DENY action cannot declare replacement types; ignored fail-closed.");
                continue;
            }
            RandomSpawnPool pool = null;
            if (action != Action.DENY) {
                ConfigurationSection typesSection = section.getConfigurationSection("types");
                List<RandomSpawnPool.Entry> types = new ArrayList<>();
                boolean invalidType = false;
                if (typesSection != null) {
                    for (String mob : typesSection.getKeys(false)) {
                        double weight = finiteDouble(typesSection, mob, Double.NaN);
                        try {
                            types.add(new RandomSpawnPool.Entry(mob, weight));
                        } catch (IllegalArgumentException ex) {
                            invalidType = true;
                            warn("RandomSpawn " + id + " type " + mob + " has an invalid weight; ignored.");
                        }
                    }
                }
                if (invalidType) {
                    warn("RandomSpawn " + id + " contains invalid type weights; definition ignored fail-closed.");
                    continue;
                }
                if (types.isEmpty()) {
                    warn("RandomSpawn " + id + " has no usable custom mob types; ignored.");
                    continue;
                }
                pool = new RandomSpawnPool(types);
            }
            double chance = finiteDouble(section, "chance", 1);
            if (!Double.isFinite(chance) || chance < 0 || chance > 1) {
                warn("RandomSpawn " + id + " chance is outside [0,1]; ignored.");
                continue;
            }
            double parsedPriority = finiteDouble(section, "priority", 1);
            if (!Double.isFinite(parsedPriority) || parsedPriority != Math.rint(parsedPriority)
                    || parsedPriority < Integer.MIN_VALUE || parsedPriority > Integer.MAX_VALUE) {
                warn("RandomSpawn " + id + " priority must be an integer; ignored.");
                continue;
            }
            int priority = (int) parsedPriority;
            double cooldown = finiteDouble(section, "cooldown", 0);
            if (!Double.isFinite(cooldown) || cooldown < 0 || cooldown > 31_536_000) {
                warn("RandomSpawn " + id + " cooldown is invalid; ignored.");
                continue;
            }
            if (action == Action.DENY && cooldown != 0) {
                warn("RandomSpawn " + id + " DENY action cannot use replacement cooldown; ignored fail-closed.");
                continue;
            }
            Object configuredWorlds = section.get("worlds");
            Object configuredBiomes = section.get("biomes");
            Object configuredReasons = section.get("reasons");
            Object configuredConditions = section.get("conditions");
            List<String> worldValues = values(configuredWorlds);
            List<String> biomeValues = values(configuredBiomes);
            List<String> rawReasons = values(configuredReasons);
            List<String> conditions = values(configuredConditions);
            if ((configuredWorlds != null && worldValues == null)
                    || (configuredBiomes != null && biomeValues == null)
                    || (configuredReasons != null && rawReasons == null)
                    || (configuredConditions != null && conditions == null)) {
                warn("RandomSpawn " + id + " contains malformed filters/conditions; ignored fail-closed.");
                continue;
            }
            EnumSet<CreatureSpawnEvent.SpawnReason> reasons = EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
            if (rawReasons.isEmpty()) {
                reasons.add(CreatureSpawnEvent.SpawnReason.NATURAL);
                if (action != Action.ADD) reasons.add(CreatureSpawnEvent.SpawnReason.CHUNK_GEN);
            } else {
                boolean invalid = false;
                for (String raw : rawReasons) {
                    try {
                        reasons.add(CreatureSpawnEvent.SpawnReason.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ex) {
                        invalid = true;
                        warn("RandomSpawn " + id + " has unknown spawn reason " + raw + "; ignored.");
                    }
                }
                if (invalid || reasons.isEmpty()) continue;
            }
            if (action == Action.ADD && (reasons.size() != 1
                    || !reasons.contains(CreatureSpawnEvent.SpawnReason.NATURAL))) {
                warn("RandomSpawn " + id + " ADD only supports the Mythic 5.7.2 NATURAL point reason; ignored fail-closed.");
                continue;
            }
            PositionType positionType = PositionType.LAND;
            if (action == Action.ADD) {
                String configuredPosition = section.getString("position-type", "LAND");
                try {
                    positionType = PositionType.valueOf(configuredPosition == null
                            ? "LAND" : configuredPosition.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    warn("RandomSpawn " + id + " position-type must be LAND or SEA; ignored fail-closed.");
                    continue;
                }
            } else if (section.contains("position-type")) {
                warn("RandomSpawn " + id + " position-type only applies to ADD; ignored fail-closed.");
                continue;
            }
            definitions.add(new Definition(id, action, pool, chance, priority,
                    TimeUnit.MILLISECONDS.toNanos((long) (cooldown * 1000)),
                    lower(worldValues == null ? List.of() : worldValues),
                    upper(biomeValues == null ? List.of() : biomeValues),
                    Set.copyOf(reasons), List.copyOf(conditions == null ? List.of() : conditions), positionType));
        }
        definitions.sort(java.util.Comparator.comparingInt(Definition::priority).reversed()
                .thenComparing(Definition::id, String.CASE_INSENSITIVE_ORDER));
    }

    public List<String> warnings() { return List.copyOf(warnings); }
    public int size() { return definitions.size(); }
    public int addSize() { return (int) definitions.stream().filter(definition -> definition.action() == Action.ADD).count(); }

    /** Runs one bounded ADD generation cycle. Call from the server's main thread. */
    public int tickAdd() {
        return tickAdd(org.bukkit.Bukkit.getOnlinePlayers());
    }

    /** Collection overload keeps the generator deterministic and independently testable. */
    public int tickAdd(Collection<? extends Player> onlinePlayers) {
        AddSettings settings = addSettings;
        if (!settings.enabled() || onlinePlayers == null || onlinePlayers.isEmpty() || addSize() == 0) return 0;
        List<? extends Player> eligiblePlayers = onlinePlayers.stream()
                .filter(Objects::nonNull)
                .filter(Player::isOnline)
                .filter(player -> player.getGameMode() == GameMode.SURVIVAL
                        || player.getGameMode() == GameMode.ADVENTURE)
                .toList();
        if (eligiblePlayers.isEmpty()) return 0;

        Map<World, Integer> worldMonsterCounts = new HashMap<>();
        int spawnedCount = 0;
        for (Player player : eligiblePlayers) {
            if (spawnedCount >= settings.maxSpawnsPerCycle()) break;
            World world = player.getWorld();
            if (world == null || worldMonsterCounts.computeIfAbsent(world, RandomSpawnService::monsterCount)
                    >= settings.maxMonstersPerWorld()) continue;
            Location candidate = randomPoint(player, eligiblePlayers, settings);
            if (candidate == null) continue;
            String biome;
            try { biome = world.getBiome(candidate).name().toUpperCase(Locale.ROOT); }
            catch (RuntimeException ex) { continue; }
            PositionType pointType = isWater(candidate) ? PositionType.SEA : PositionType.LAND;

            for (Definition definition : definitions) {
                if (definition.action() != Action.ADD || definition.positionType() != pointType) continue;
                if (!definition.worlds().isEmpty()
                        && !definition.worlds().contains(world.getName().toLowerCase(Locale.ROOT))) continue;
                if (!definition.biomes().isEmpty() && !definition.biomes().contains(biome)) continue;
                if (!addConditionsPass(definition, candidate)) continue;
                long now = clock.getAsLong();
                if (now < cooldowns.getOrDefault(definition.id(), Long.MIN_VALUE)
                        || random.nextDouble() >= definition.chance()) continue;
                String mobId = definition.types().choose(random);
                boolean spawned = false;
                try { spawned = mobSpawner.spawn(mobId, candidate.clone(), null) > 0; }
                catch (Throwable ex) {
                    warn("RandomSpawn " + definition.id() + " ADD failed: " + ex.getClass().getSimpleName());
                }
                if (spawned) {
                    spawnedCount++;
                    worldMonsterCounts.computeIfPresent(world, (ignored, count) -> count + 1);
                    if (definition.cooldownNanos() > 0) {
                        cooldowns.put(definition.id(), now + definition.cooldownNanos());
                    }
                    break;
                }
            }
        }
        return spawnedCount;
    }

    private boolean addConditionsPass(Definition definition, Location candidate) {
        if (definition.conditions().isEmpty()) return true;
        BiPredicate<String, Location> tester = addConditionTester;
        if (tester == null) {
            warnAddConditionOnce(definition.id(), "has ADD Conditions but no location condition tester; ignored fail-closed.");
            return false;
        }
        for (String condition : definition.conditions()) {
            try {
                if (!tester.test(condition, candidate.clone())) return false;
            } catch (Throwable ex) {
                warnAddConditionOnce(definition.id(), "ADD condition evaluation failed; ignored fail-closed.");
                return false;
            }
        }
        return true;
    }

    private void warnAddConditionOnce(String id, String message) {
        if (addConditionWarnings.add(id)) warn("RandomSpawn " + id + " " + message);
    }

    private Location randomPoint(Player player, Collection<? extends Player> players, AddSettings settings) {
        World world = player.getWorld();
        Location playerLocation = player.getLocation();
        if (world == null || playerLocation == null
                || !Double.isFinite(playerLocation.getX())
                || !Double.isFinite(playerLocation.getY())
                || !Double.isFinite(playerLocation.getZ())) return null;
        double minSquared = (double) settings.radiusMin() * settings.radiusMin();
        double maxSquared = (double) settings.radiusMax() * settings.radiusMax();
        for (int attempt = 0; attempt < 12; attempt++) {
            double radius = Math.sqrt(minSquared + random.nextDouble() * (maxSquared - minSquared));
            double angle = random.nextDouble() * Math.PI * 2;
            int x = (int) Math.floor(playerLocation.getX() + Math.cos(angle) * radius);
            int z = (int) Math.floor(playerLocation.getZ() + Math.sin(angle) * radius);
            Location borderProbe = new Location(world, x + 0.5, playerLocation.getY(), z + 0.5);
            if (!insideWorldBorder(world, borderProbe)) {
                continue;
            }
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
            int verticalRange = settings.radiusY();
            int sampledY = verticalRange == 0 ? playerLocation.getBlockY()
                    : playerLocation.getBlockY() - verticalRange
                    + random.nextInt(verticalRange * 2 + 1);
            sampledY = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 2, sampledY));
            Block sampled = world.getBlockAt(x, sampledY, z);
            Location point = null;
            if (sampled.getType() == Material.WATER) {
                point = new Location(world, x + 0.5, sampledY + 0.5, z + 0.5);
            } else if (!sampled.isPassable() && !sampled.isLiquid()) {
                Block feet = world.getBlockAt(x, sampledY + 1, z);
                Block head = world.getBlockAt(x, sampledY + 2, z);
                if (feet.isPassable() && head.isPassable()) {
                    point = new Location(world, x + 0.5, sampledY + 1.0, z + 0.5);
                }
            } else if (sampled.isPassable()) {
                int minimumY = Math.max(world.getMinHeight(), sampledY - Math.max(2, verticalRange * 2));
                for (int y = sampledY - 1; y >= minimumY; y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.WATER) {
                        point = new Location(world, x + 0.5, y + 0.5, z + 0.5);
                        break;
                    }
                    if (!block.isPassable() && !block.isLiquid()) {
                        Block feet = world.getBlockAt(x, y + 1, z);
                        Block head = world.getBlockAt(x, y + 2, z);
                        if (feet.isPassable() && head.isPassable()) {
                            point = new Location(world, x + 0.5, y + 1.0, z + 0.5);
                        }
                        break;
                    }
                }
            }
            if (point == null || !farEnoughFromPlayers(point, players, settings.radiusMin())) continue;
            return point;
        }
        return null;
    }

    private static boolean insideWorldBorder(World world, Location candidate) {
        try {
            WorldBorder border = world.getWorldBorder();
            return border != null && border.isInside(candidate);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean isWater(Location location) {
        World world = location == null ? null : location.getWorld();
        return world != null && world.getBlockAt(location.getBlockX(), location.getBlockY(),
                location.getBlockZ()).getType() == Material.WATER;
    }

    private static boolean farEnoughFromPlayers(Location point, Collection<? extends Player> players, int minRadius) {
        double minSquared = (double) minRadius * minRadius;
        for (Player other : players) {
            if (other == null || !other.isOnline() || other.getWorld() != point.getWorld()) continue;
            Location location = other.getLocation();
            if (location != null && location.distanceSquared(point) < minSquared) return false;
        }
        return true;
    }

    private static int monsterCount(World world) {
        try {
            int count = 0;
            for (Entity entity : world.getEntities()) {
                if (entity.getSpawnCategory() == org.bukkit.entity.SpawnCategory.MONSTER) count++;
            }
            return count;
        } catch (RuntimeException ex) {
            return Integer.MAX_VALUE;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (Boolean.TRUE.equals(replacing.get()) || event == null || event.getEntity() == null) return;
        Definition selected = null;
        Location location = event.getLocation();
        World world = location == null ? null : location.getWorld();
        if (world == null) return;
        String biome;
        try {
            biome = world.getBiome(location).name().toUpperCase(Locale.ROOT);
        } catch (RuntimeException ex) {
            return;
        }
        for (Definition candidate : definitions) {
            if (candidate.action() == Action.ADD) continue;
            if (!candidate.reasons().contains(event.getSpawnReason())) continue;
            if (!candidate.worlds().isEmpty() && !candidate.worlds().contains(world.getName().toLowerCase(Locale.ROOT))) continue;
            if (!candidate.biomes().isEmpty() && !candidate.biomes().contains(biome)) continue;
            if (candidate.conditions().stream().anyMatch(condition -> {
                try { return !conditionTester.test(condition, event); }
                catch (Throwable ignored) { return true; }
            })) continue;
            selected = candidate;
            break;
        }
        if (selected == null || random.nextDouble() >= selected.chance()) return;
        if (selected.action() == Action.DENY) {
            event.setCancelled(true);
        } else {
            replace(event, selected);
        }
    }

    private void replace(CreatureSpawnEvent event, Definition definition) {
        long now = clock.getAsLong();
        long next = cooldowns.getOrDefault(definition.id(), Long.MIN_VALUE);
        if (now < next) return;
        String mobId = definition.types().choose(random);
        boolean spawned = false;
        replacing.set(true);
        try {
            spawned = mobSpawner.spawn(mobId, event.getLocation().clone(), null) > 0;
        } catch (Throwable ex) {
            warn("RandomSpawn " + definition.id() + " replacement failed: " + ex.getClass().getSimpleName());
        } finally {
            replacing.remove();
        }
        if (spawned) {
            event.setCancelled(true);
            if (definition.cooldownNanos() > 0) cooldowns.put(definition.id(), now + definition.cooldownNanos());
        }
    }

    private final Map<String, Long> cooldowns = new java.util.concurrent.ConcurrentHashMap<>();

    private void warn(String message) {
        warnings.add(message);
        if (logger != null) logger.warning("[RandomSpawn] " + message);
    }

    private static double finiteDouble(ConfigurationSection section, String key, double fallback) {
        Object raw = section.get(key);
        if (raw instanceof Number number) return number.doubleValue();
        try { return raw == null ? fallback : Double.parseDouble(String.valueOf(raw).trim()); }
        catch (NumberFormatException ex) { return Double.NaN; }
    }

    private static List<String> values(Object raw) {
        if (raw == null) return List.of();
        List<?> source = raw instanceof List<?> list ? list : List.of(raw);
        List<String> result = new ArrayList<>();
        for (Object value : source) {
            if (!(value instanceof String text)) return null;
            for (String part : text.split(",")) {
                if (part.isBlank()) return null;
                result.add(part.trim());
            }
        }
        if (result.isEmpty()) return null;
        return result;
    }

    private static Set<String> lower(List<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<String> upper(List<String> values) {
        return values.stream().map(value -> value.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
