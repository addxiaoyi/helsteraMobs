package dev.helstera.ai.spawner;

import dev.helstera.ai.YamlNums;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 刷怪点服务（spawners.yml）。
 *
 * <p>一个刷怪点 = 固定位置 + 随机半径 + 间隔 + 存活上限 + 世界绑定。调度用
 * <b>单一共享任务</b>而不是每个刷怪点一个任务：100 个刷怪点会变成 100 个
 * BukkitTask，调度器本身就成了负担。这里按最小间隔合并成一个节拍，每个刷怪点
 * 自己记 nextSpawnTick，到点才真正生成。</p>
 *
 * <p>数量上限按"该刷怪点已生成的实例"计数，而不是全局实例数——后者会让
 * 多刷怪点互相抢名额，一个满了其余全部停摆。</p>
 *
 * <p>线程约束：装载与 tick 均在主线程。生成动作通过 {@link MobSpawner} 回调
 * 交给插件层实现，避免 ai 模块反向依赖插件主模块。</p>
 */
public final class SpawnerService {

    private static final int MAX_GROUND_SEARCH_BLOCKS = 512;

    private record RegionBounds(String world, double x1, double y1, double z1,
                                double x2, double y2, double z2) {
        boolean contains(Location location) {
            if (location == null || location.getWorld() == null) return false;
            return (world == null || world.isBlank() || world.equalsIgnoreCase(location.getWorld().getName()))
                    && location.getX() >= Math.min(x1, x2) && location.getX() <= Math.max(x1, x2)
                    && location.getY() >= Math.min(y1, y2) && location.getY() <= Math.max(y1, y2)
                    && location.getZ() >= Math.min(z1, z2) && location.getZ() <= Math.max(z1, z2);
        }
    }

    /** 插件层提供的生成能力：按 mobs 配置生成，返回实例 ID（-1 表示失败）。
     * {@code aiProfile} 非 null 时覆盖 mob 档案的 AI 配置。 */
    @FunctionalInterface
    public interface MobSpawner {
        int spawn(String mobId, Location loc, String aiProfile);
    }

    @FunctionalInterface
    public interface ConditionTester {
        Boolean test(String condition, Location location);
    }

    @FunctionalInterface
    public interface RegionTester {
        Boolean contains(String region, Location location);
    }

    /** 单个刷怪点定义。字段名与 spawners.yml 一一对应。 */
    public static final class Spawner {
        private final String id;
        private final String mobId;
        private final String world;
        private final double x, y, z;
        private final double radius;
        private final int intervalTicks;
        private final int maxAlive;
        private final int maxSpawns;
        private final int maxPerWorld;
        private final int maxPerPlayer;
        private final int minPlayers;
        private final double playersRadius;
        private final boolean enabled;
        private final double yRange;
        /** 覆盖 mob 档案的 AI 配置；null 表示不覆盖，沿用 mob 自身档案。 */
        private final String aiProfile;
        private final RandomSpawnPool randomPool;
        private final List<String> conditions;
        private long nextSpawnTick;
        private int totalSpawned;
        private final Map<String, Integer> mobSpawnCounts = new ConcurrentHashMap<>();

        Spawner(String id, String mobId, String world, double x, double y, double z,
                double radius, int intervalTicks, int maxAlive, int maxSpawns,
                int maxPerWorld, int maxPerPlayer, int minPlayers, double playersRadius,
                boolean enabled, double yRange,
                 String aiProfile, RandomSpawnPool randomPool, List<String> conditions) {
            this.id = id;
            this.mobId = mobId;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.intervalTicks = Math.max(20, intervalTicks);
            this.maxAlive = Math.max(1, maxAlive);
            this.maxSpawns = maxSpawns;
            this.maxPerWorld = maxPerWorld;
            this.maxPerPlayer = maxPerPlayer;
            this.minPlayers = Math.max(0, minPlayers);
            this.playersRadius = Math.max(0, playersRadius);
            this.enabled = enabled;
            this.yRange = Math.max(0, yRange);
            this.aiProfile = aiProfile == null || aiProfile.isBlank() ? null : aiProfile.trim();
            this.randomPool = randomPool;
            this.conditions = List.copyOf(conditions == null ? List.of() : conditions);
            this.nextSpawnTick = 0;
        }

        public String id() { return id; }
        public String mobId() { return mobId; }
        public String world() { return world; }
        public double x() { return x; }
        public double y() { return y; }
        public double z() { return z; }
        public double radius() { return radius; }
        public int intervalTicks() { return intervalTicks; }
        public int maxAlive() { return maxAlive; }
        public int maxSpawns() { return maxSpawns; }
        public int maxPerWorld() { return maxPerWorld; }
        public int maxPerPlayer() { return maxPerPlayer; }
        public int minPlayers() { return minPlayers; }
        public double playersRadius() { return playersRadius; }
        public double yRange() { return yRange; }
        public boolean enabled() { return enabled; }
        public int totalSpawned() { return totalSpawned; }
        /** 返回覆盖用的 AI 档案名；null 表示不覆盖。 */
        public String aiProfile() { return aiProfile; }
        public RandomSpawnPool randomPool() { return randomPool; }
        public Map<String, Integer> mobSpawnCounts() { return Map.copyOf(mobSpawnCounts); }
        public Map<String, Double> observedDistribution() {
            int total = mobSpawnCounts.values().stream().mapToInt(Integer::intValue).sum();
            if (total <= 0) return Map.of();
            Map<String, Double> result = new java.util.LinkedHashMap<>();
            mobSpawnCounts.forEach((mob, count) -> result.put(mob, count / (double) total));
            return Map.copyOf(result);
        }
        public Map<String, Double> expectedDistribution() {
            if (randomPool == null) return Map.of(mobId, 1.0);
            double total = randomPool.entries().stream().mapToDouble(RandomSpawnPool.Entry::weight).sum();
            Map<String, Double> result = new java.util.LinkedHashMap<>();
            for (var entry : randomPool.entries()) result.put(entry.mobId(), entry.weight() / total);
            return Map.copyOf(result);
        }
        public Map<String, Double> distributionDeviation() {
            Map<String, Double> expected = expectedDistribution();
            Map<String, Double> observed = observedDistribution();
            Map<String, Double> result = new java.util.LinkedHashMap<>();
            expected.forEach((mob, probability) -> result.put(mob, observed.getOrDefault(mob, 0.0) - probability));
            return Map.copyOf(result);
        }
        void recordSpawn(String mobId) {
            if (mobId != null) mobSpawnCounts.merge(mobId, 1, Integer::sum);
        }
        void restoreStats(int total, Map<String, Integer> counts) {
            totalSpawned = Math.max(0, total);
            if (counts != null) {
                counts.forEach((mob, count) -> {
                    if (mob != null && count != null && count > 0) mobSpawnCounts.put(mob, count);
                });
            }
        }
        void restoreNextSpawn(int remainingTicks, int currentTick) {
            nextSpawnTick = currentTick + Math.max(0, remainingTicks);
        }
        int remainingToSpawn(int currentTick) {
            return (int) Math.max(0, nextSpawnTick - (long) currentTick);
        }
        public List<String> conditions() { return conditions; }

        public Location base(World w) {
            return w == null ? null : new Location(w, x, y, z);
        }
    }

    /**
     * 运行期开关覆盖集：只含被手动关掉的刷怪点 id。
     *
     * <p>刻意与 YAML 的 {@code enabled} 分开存而非直接改字段：配置文件里的值是
     * 「作者意图」，运行期 toggle 是「临时处置」。合并后 reload 会把玩家的临时停用
     * 悄悄冲掉，且 {@code enabled()} 的语义会随调用时机漂移。</p>
     */
    private final java.util.Set<String> runtimeDisabled =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<>());

    private final Plugin plugin;
    private final Logger log;
    private final MobSpawner spawner;
    private final java.util.function.Function<String, World> worldResolver;
    private final Random random = new Random();
    private final Map<String, Spawner> spawners = new ConcurrentHashMap<>();
    private final Map<String, RegionBounds> regions = new ConcurrentHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final java.util.Set<String> spawnWarningKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, List<Integer>> alive = new ConcurrentHashMap<>();
    private final Map<String, Integer> worldSpawnCounts = new ConcurrentHashMap<>();
    private final Map<String, Map<java.util.UUID, Integer>> playerSpawnCounts = new ConcurrentHashMap<>();
    private final Map<String, String> forceSpawnFailures = new ConcurrentHashMap<>();
    private volatile ConditionTester conditionTester;
    private volatile RegionTester regionTester;
    private BukkitTask task;
    private int tickCounter;
    private int minInterval = 100;

    public SpawnerService(Plugin plugin, Logger log, MobSpawner spawner) {
        this(plugin, log, spawner, name -> plugin == null
                ? org.bukkit.Bukkit.getWorld(name) : plugin.getServer().getWorld(name));
    }

    public SpawnerService(Logger log, MobSpawner spawner) {
        this(null, log, spawner);
    }

    public SpawnerService(Logger log, MobSpawner spawner,
                          java.util.function.Function<String, World> worldResolver) {
        this(null, log, spawner, worldResolver);
    }

    public SpawnerService(Plugin plugin, Logger log, MobSpawner spawner,
                          java.util.function.Function<String, World> worldResolver) {
        this.plugin = plugin;
        this.log = log;
        this.spawner = spawner;
        this.worldResolver = java.util.Objects.requireNonNull(worldResolver, "worldResolver");
    }

    public void setConditionTester(ConditionTester tester) {
        this.conditionTester = tester;
    }

    public void setRegionTester(RegionTester tester) {
        this.regionTester = tester;
    }

    public void load(ConfigurationSection root) {
        spawners.clear();
        regions.clear();
        alive.clear();
        worldSpawnCounts.clear();
        playerSpawnCounts.clear();
        problems.clear();
        spawnWarningKeys.clear();
        runtimeDisabled.clear();
        minInterval = Integer.MAX_VALUE;
        if (root == null) {
            minInterval = 100;
            return;
        }
        ConfigurationSection source = root;
        ConfigurationSection nested = root.getConfigurationSection("spawners");
        if (nested != null && !root.contains("mob")) source = nested;
        loadRegions(source.getConfigurationSection("regions"));
        for (String key : source.getKeys(false)) {
            if (key.equalsIgnoreCase("regions")) continue;
            ConfigurationSection s = source.getConfigurationSection(key);
            if (s == null) continue;
            String id = key.toLowerCase(Locale.ROOT);
            String mobId = s.getString("mob");
            if (mobId == null || mobId.isBlank()) {
                problems.add("刷怪点 " + id + " 缺少 mob，已跳过");
                continue;
            }
            if (!s.contains("x") || !s.contains("z")) {
                problems.add("刷怪点 " + id + " 缺少坐标 x/z，已跳过");
                continue;
            }
            // 世界存在性在**装载期**查，而不是等到每次 spawn 才试。
            // resolveWorld 一直会告警，但那是运行期的：刷怪点能正常装载、
            // /helstera check 全绿，作者直到玩家踩过去才发现那个点从不出怪。
            //
            // 只校验 world，不在这里校验 mob/model——SpawnerService 拿到的只是
            // org.bukkit.plugin.Plugin，没有模型注册表；硬要查就得把 AI 模块耦合到
            // 模型层。模型侧的校验放在 HelsteraPlugin.reloadLootAndSpawners 里，
            // 那里才有 mobs/ 与 registry。
            String worldName = s.getString("world", "");
            if (worldName != null && !worldName.isBlank()) {
                try {
                    if (worldResolver.apply(worldName) == null) {
                        problems.add("刷怪点 " + id + " 的 world \"" + worldName
                                + "\" 不存在，该点将永远不出怪。可用世界："
                                + org.bukkit.Bukkit.getWorlds().stream()
                                        .map(w -> w.getName()).toList());
                    }
                } catch (Throwable ignored) {
                    // 服务端未就绪时不必拦——加载期拿不到世界列表是正常的
                }
            }
            // 以下原先全是裸 getDouble/getInt：Bukkit 对「值无法解析」与「键不存在」
            // 返回同一个默认值，于是 interval: "很快" 与不写 interval 完全同效、
            // x: "中间" 会静默变成坐标 0。真服实测这类写法零告警。
            String where = "刷怪点 " + id;
            String intervalKey = s.contains("interval") ? "interval"
                    : s.contains("cooldown") ? "cooldown" : "interval";
            int interval = YamlNums.positive(s, intervalKey, 200, problems, where);
            if (intervalKey.equals("cooldown")) {
                problems.add(where + " cooldown is accepted as the Mythic alias for interval.");
            }
            int minPlayers = YamlNums.nonNegative(s, "min-players", 0, problems, where);
            double configuredX = coordinate(s, "x", 0, problems, where);
            double configuredY = coordinate(s, "y", 0, problems, where);
            double configuredZ = coordinate(s, "z", 0, problems, where);
            String playerRadiusKey = s.contains("players-radius") ? "players-radius"
                    : s.contains("player-distance") ? "player-distance" : "players-radius";
            double playersRadius = YamlNums.d(s, playerRadiusKey, 24.0, problems, where);
            if (playerRadiusKey.equals("player-distance")) {
                problems.add(where + " player-distance is accepted as the Mythic alias for players-radius.");
            }
            if (!Double.isFinite(playersRadius)) {
                problems.add(where + " players-radius must be finite; defaulting to 24.");
                playersRadius = 24.0;
            } else if (playersRadius > 30_000_000.0) {
                problems.add(where + " players-radius exceeds coordinate bounds; clamped to 30000000.");
                playersRadius = 30_000_000.0;
            } else if (playersRadius < 0 && minPlayers > 0) {
                problems.add(where + " players-radius must be non-negative when min-players is enabled; defaulting to 24.");
                playersRadius = 24.0;
            } else if (playersRadius < 0) {
                problems.add(where + " players-radius is negative; clamped to 0.");
                playersRadius = 0.0;
            }
            double radius = YamlNums.d(s, "radius", 6.0, problems, where);
            if (!Double.isFinite(radius) || radius <= 0) {
                problems.add(where + " radius must be finite and positive; defaulting to 6.");
                radius = 6.0;
            } else if (radius > 30_000_000.0) {
                problems.add(where + " radius exceeds world coordinate bounds; clamped to 30000000.");
                radius = 30_000_000.0;
            }
            double yRange = YamlNums.d(s, "y-range", 0, problems, where);
            if (!Double.isFinite(yRange)) {
                problems.add(where + " y-range is non-finite; clamped to 0.");
                yRange = 0;
            } else if (yRange < 0) {
                problems.add(where + " y-range is negative; clamped to 0.");
                yRange = 0;
            }
            if (minPlayers > 0 && playersRadius <= 0) {
                problems.add(where + " has min-players > 0 and players-radius <= 0; defaulting to 24.");
                playersRadius = 24.0;
            }
            // max-alive <= 0 会让刷怪点永远刷不出（上限 0）。钳到 1 而不是回落到
            // 默认值：作者写 0 本意多半是「别限制」，钳到 1 既让它活着又给出告警，
            // 比悄悄给 3 只更贴近本意。SpawnerServiceTest.clampsMaxAliveToAtLeastOne
            // 守的就是这条。
            int maxAlive = YamlNums.i(s, "max-alive", 3, problems, where);
            if (maxAlive < 1) {
                problems.add(where + " 的 max-alive = " + maxAlive
                        + " 不是正数，已夹到 1（写 0 会让该点永远刷不出）。"
                        + "想表示「不限」可以省略该字段");
                maxAlive = 1;
            }
            if (maxAlive > 1000) {
                problems.add(where + " 的 max-alive = " + maxAlive + " 大得离谱（>1000），"
                        + "通常是少写了一位。同一刷怪点会同时存在这么多只，建议确认后主动降低");
            }
            int maxPerWorld = nonNegativeLimit(s, "max-per-world", "per-world-limit", problems, where);
            int maxPerPlayer = nonNegativeLimit(s, "max-per-player", "per-player-limit", problems, where);
            Spawner sp = new Spawner(
                    id, mobId,
                    s.getString("world", ""),
                    configuredX,
                    configuredY,
                    configuredZ,
                    // radius 为 0 会让「附近玩家」判定恒不成立；负数更是无意义
                    radius,
                    interval,
                    maxAlive,
                    // 这两个默认 0 是合法语义（不限 / 不需要人），故用非负版
                    YamlNums.nonNegative(s, "max-spawns", 0, problems, where),
                    maxPerWorld,
                    maxPerPlayer,
                    minPlayers,
                    playersRadius,
                    s.getBoolean("enabled", true),
                    yRange,
                    s.getString("ai-profile"), parseRandomPool(s, problems, where),
                    readConditions(s));
            spawners.put(id, sp);
            alive.put(id, Collections.synchronizedList(new ArrayList<>()));
            minInterval = Math.min(minInterval, sp.intervalTicks);
        }
        if (minInterval == Integer.MAX_VALUE) minInterval = 100;
        if (log != null) {
            log.info("已装载刷怪点 " + spawners.size() + " 个（共享节拍 " + minInterval + " tick）");
            for (String p : problems) log.warning("[刷怪] " + p);
        }
    }

    /** Restores lifetime spawn counters after config reload or plugin restart. */
    public void loadState(File file) {
        loadState(file, true);
    }

    private void loadState(File file, boolean allowBackup) {
        if (file == null || !file.isFile()) return;
        try {
            YamlConfiguration state = YamlConfiguration.loadConfiguration(file);
            int version = state.getInt("schema-version", 1);
            if (version != 1) {
                if (log != null) log.warning("[Spawner] unsupported state schema version " + version + "; ignoring state.");
                return;
            }
            ConfigurationSection root = state.getConfigurationSection("spawners");
            if (root == null) return;
            ConfigurationSection worldCounts = state.getConfigurationSection("world-spawn-counts");
            if (worldCounts != null) for (String world : worldCounts.getKeys(false)) {
                worldSpawnCounts.put(world.toLowerCase(Locale.ROOT), Math.max(0, worldCounts.getInt(world, 0)));
            }
            for (String id : root.getKeys(false)) {
                Spawner sp = get(id);
                ConfigurationSection section = root.getConfigurationSection(id);
                if (sp == null || section == null) continue;
                Map<String, Integer> counts = new java.util.LinkedHashMap<>();
                ConfigurationSection byMob = section.getConfigurationSection("mob-counts");
                if (byMob != null) for (String mob : byMob.getKeys(false)) {
                    int count = byMob.getInt(mob, 0);
                    if (count >= 0) counts.put(mob, count);
                }
                sp.restoreStats(Math.max(0, section.getInt("total-spawned", 0)), counts);
                sp.restoreNextSpawn(section.getInt("next-spawn-in", 0), tickCounter);
                ConfigurationSection players = section.getConfigurationSection("player-spawn-counts");
                if (players != null) {
                    Map<java.util.UUID, Integer> restored = playerSpawnCounts
                            .computeIfAbsent(sp.id(), ignored -> new ConcurrentHashMap<>());
                    for (String rawUuid : players.getKeys(false)) {
                        try {
                            int count = players.getInt(rawUuid, 0);
                            if (count > 0) restored.put(java.util.UUID.fromString(rawUuid), count);
                        } catch (IllegalArgumentException ignored) {
                            if (log != null) log.warning("[Spawner] invalid player UUID in state: " + rawUuid);
                        }
                    }
                }
            }
        } catch (RuntimeException ex) {
            File backup = new File(file.getPath() + ".bak");
            if (allowBackup && backup.isFile()) {
                if (log != null) log.warning("[Spawner] state load failed; trying backup: " + ex.getMessage());
                loadState(backup, false);
            } else if (log != null) {
                log.warning("[Spawner] state load failed; ignoring corrupt state: " + ex.getMessage());
            }
        }
    }

    /** Persists lifetime spawn counters without serializing live instance ids. */
    public void saveState(File file) {
        if (file == null) return;
        YamlConfiguration state = new YamlConfiguration();
        state.set("schema-version", 1);
        for (var entry : worldSpawnCounts.entrySet()) {
            if (!entry.getKey().isBlank()) {
                state.set("world-spawn-counts." + entry.getKey(), entry.getValue());
            }
        }
        for (Spawner sp : spawners.values()) {
            String path = "spawners." + sp.id();
            state.set(path + ".total-spawned", sp.totalSpawned());
            state.set(path + ".next-spawn-in", sp.remainingToSpawn(tickCounter));
            for (var entry : sp.mobSpawnCounts().entrySet()) {
                state.set(path + ".mob-counts." + entry.getKey(), entry.getValue());
            }
            for (var entry : playerSpawnCounts.getOrDefault(sp.id(), Map.of()).entrySet()) {
                state.set(path + ".player-spawn-counts." + entry.getKey(), entry.getValue());
            }
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            File temp = new File(file.getPath() + ".tmp");
            state.save(temp);
            if (file.isFile()) {
                Files.copy(file.toPath(), new File(file.getPath() + ".bak").toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            if (log != null) log.warning("[Spawner] state save failed: " + ex.getMessage());
        }
    }

    private void loadRegions(ConfigurationSection section) {
        if (section == null) return;
        for (String name : section.getKeys(false)) {
            ConfigurationSection region = section.getConfigurationSection(name);
            if (region == null) continue;
            try {
                regions.put(name.toLowerCase(Locale.ROOT), new RegionBounds(
                        region.getString("world", ""), region.getDouble("x1"), region.getDouble("y1"),
                        region.getDouble("z1"), region.getDouble("x2"), region.getDouble("y2"),
                        region.getDouble("z2")));
            } catch (RuntimeException ex) {
                problems.add("Spawner region " + name + " is invalid.");
            }
        }
    }

    public Collection<String> ids() {
        return Collections.unmodifiableSet(spawners.keySet());
    }

    public Spawner get(String id) {
        return id == null ? null : spawners.get(id.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return spawners.size();
    }

    /** Remaining automatic spawn cooldown in ticks; zero means ready or not scheduled. */
    public int nextSpawnInTicks(String id) {
        Spawner sp = get(id);
        return sp == null ? -1 : sp.remainingToSpawn(tickCounter);
    }

    /** Returns a manual spawn result without exposing internal tracking collections. */
    public boolean forceSpawn(String id) {
        return forceSpawn(id, null);
    }

    public List<String> warnings() {
        List<String> out = new ArrayList<>(problems);
        for (Spawner sp : spawners.values()) {
            if (sp.randomPool() == null || sp.totalSpawned() < 20) continue;
            sp.distributionDeviation().forEach((mob, deviation) -> {
                if (Math.abs(deviation) >= 0.25) {
                    out.add("Spawner " + sp.id() + " random-mobs distribution for " + mob
                            + " deviates by " + String.format(Locale.ROOT, "%.3f", deviation)
                            + " after " + sp.totalSpawned() + " spawns.");
                }
            });
        }
        return List.copyOf(out);
    }

    /** 某刷怪点当前追踪的存活实例数（已剔除失效的）。 */
    public int aliveCount(String id) {
        List<Integer> list = alive.get(id.toLowerCase(Locale.ROOT));
        if (list == null) return 0;
        synchronized (list) {
            list.removeIf(i -> i == null || !isAlive(i));
        }
        return list.size();
    }

    /** 实例是否仍存活；由插件层注入实例存活判定，避免 ai 模块依赖实例实现。 */
    private java.util.function.IntPredicate aliveCheck = id -> false;

    public void setAliveCheck(java.util.function.IntPredicate check) {
        if (check != null) this.aliveCheck = check;
    }

    private boolean isAlive(int instanceId) {
        try {
            return aliveCheck.test(instanceId);
        } catch (Throwable t) {
            return false;
        }
    }

    public void start() {
        stop();
        if (spawners.isEmpty()) return;
        int period = Math.max(20, minInterval);
        if (plugin == null) throw new IllegalStateException("Plugin is required to start the spawner scheduler");
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickOnce, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        alive.clear();
        worldSpawnCounts.clear();
        playerSpawnCounts.clear();
    }

    /** 手动触发一次生成（/helstera spawner force <id>），忽略间隔与上限。 */
    public boolean forceSpawn(String id, Location override) {
        Spawner sp = get(id);
        if (sp == null) return false;
        String key = sp.id();
        forceSpawnFailures.remove(key);
        if (!withinSpawnLimits(sp)) return failForceSpawn(key, "spawn limits reached");
        Location loc = override != null ? override : randomLocation(sp);
        if (loc == null) return failForceSpawn(key, "no valid spawn location");
        if (!conditionsPass(sp, loc)) return failForceSpawn(key, "conditions rejected location");
        if (!playerGateOpen(sp, loc)) return failForceSpawn(key, "player gate closed");
        Player owner = nearestPlayer(loc, sp.playersRadius());
        if (!withinScopedLimits(sp, loc, owner)) return failForceSpawn(key, "scoped spawn limits reached");
        String mobId = sp.randomPool() == null ? sp.mobId() : sp.randomPool().choose(random);
        if (mobId == null || mobId.isBlank()) return failForceSpawn(key, "mob id is blank");
        int instId = safeSpawn(mobId, loc, sp.aiProfile());
        if (instId > 0) {
            alive.computeIfAbsent(sp.id(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(instId);
            sp.totalSpawned++;
            sp.recordSpawn(mobId);
            recordScopedSpawn(sp, loc, owner);
            forceSpawnFailures.remove(key);
        } else {
            return failForceSpawn(key, "spawn callback returned " + instId + " for " + mobId);
        }
        return instId > 0;
    }

    private boolean failForceSpawn(String id, String reason) {
        forceSpawnFailures.put(id, reason);
        return false;
    }

    public String forceSpawnFailure(String id) {
        return id == null ? null : forceSpawnFailures.get(id.toLowerCase(Locale.ROOT));
    }

    /** Shared scheduler beat; exposed for deterministic integration tests and diagnostics. */
    public void tickOnce() {
        tickCounter++;
        for (Spawner sp : spawners.values()) {
            try {
                if (!isActive(sp)) continue;
                if (tickCounter < sp.nextSpawnTick) continue;
                sp.nextSpawnTick = tickCounter + sp.intervalTicks;
                trySpawn(sp);
            } catch (Throwable t) {
                if (log != null) log.warning("[刷怪] " + sp.id() + " 生成异常: " + t);
            }
        }
    }

    /**
     * 刷怪点当前是否应当产出：YAML 的 enabled 且未被运行期停用。
     *
     * <p>两个来源都算，因此 {@code toggle} 关掉一个 YAML 里 enabled: false 的点，
     * 再 toggle 回来仍然是关的——它本来就该关着。</p>
     */
    public boolean isActive(Spawner sp) {
        return sp.enabled && !runtimeDisabled.contains(sp.id);
    }

    /**
     * 翻转运行期开关。
     *
     * @return 翻转后<b>是否启用</b>；刷怪点不存在返回 {@code null}。
     *         注意这与「YAML 的 enabled」不同——后者为 false 时本方法恒返回 false，
     *         因为 toggle 只能在 YAML 之上叠加，不能覆盖作者意图。
     */
    public Boolean toggle(String id) {
        if (id == null) return null;
        Spawner sp = get(id);
        if (sp == null) return null;
        // 必须用 sp.id()（已小写化）而非传入的原始 id：配置里写 Alpha 而命令里打
        // alpha 时，原始 id 永远匹配不上覆盖集，表现为「toggle 提示成功但没效果」。
        String key = sp.id();
        if (runtimeDisabled.remove(key)) {
            // 之前被手动关过，现在解除
        } else {
            runtimeDisabled.add(key);
        }
        return isActive(sp);
    }


    private static double coordinate(ConfigurationSection section, String key, double fallback,
                                     List<String> warnings, String where) {
        double value = YamlNums.d(section, key, fallback, warnings, where);
        if (!Double.isFinite(value)) {
            warnings.add(where + " " + key + " must be finite; defaulting to " + fallback + ".");
            return fallback;
        }
        double bound = 30_000_000.0;
        if (Math.abs(value) > bound) {
            warnings.add(where + " " + key + " exceeds world coordinate bounds; clamped.");
            return Math.copySign(bound, value);
        }
        return value;
    }

    private RandomSpawnPool parseRandomPool(ConfigurationSection section, List<String> problems, String where) {
        ConfigurationSection pool = section.getConfigurationSection("random-mobs");
        if (pool == null) return null;
        if (pool.getKeys(false).isEmpty()) {
            problems.add(where + " random-mobs is empty; fallback mob will be used.");
            return null;
        }
        List<RandomSpawnPool.Entry> entries = new ArrayList<>();
        for (String key : pool.getKeys(false)) {
            double rawWeight = YamlNums.d(pool, key, Double.NaN, problems, where + " random-mobs");
            if (!Double.isFinite(rawWeight) || rawWeight <= 0) {
                problems.add(where + " random-mobs " + key + " has non-positive weight and was ignored.");
                continue;
            }
            double weight = rawWeight;
            try { entries.add(new RandomSpawnPool.Entry(key, weight)); }
            catch (IllegalArgumentException e) { problems.add(where + " random-mobs " + key + ": " + e.getMessage()); }
        }
        return entries.isEmpty() ? null : new RandomSpawnPool(entries);
    }

    private boolean withinSpawnLimits(Spawner sp) {
        if (sp.maxSpawns() > 0 && sp.totalSpawned >= sp.maxSpawns()) return false;
        return aliveCount(sp.id()) < sp.maxAlive();
    }

    private boolean withinScopedLimits(Spawner sp, Location location, Player owner) {
        if (sp.maxPerWorld() > 0 && worldSpawnCounts.getOrDefault(worldKey(location), 0) >= sp.maxPerWorld()) return false;
        if (sp.maxPerPlayer() > 0 && owner != null
                && playerSpawnCounts.getOrDefault(sp.id(), Map.of()).getOrDefault(owner.getUniqueId(), 0) >= sp.maxPerPlayer()) {
            return false;
        }
        return true;
    }

    private void recordScopedSpawn(Spawner sp, Location location, Player owner) {
        String world = worldKey(location);
        if (!world.isBlank()) worldSpawnCounts.merge(world, 1, Integer::sum);
        if (owner != null) {
            playerSpawnCounts.computeIfAbsent(sp.id(), ignored -> new ConcurrentHashMap<>())
                    .merge(owner.getUniqueId(), 1, Integer::sum);
        }
    }

    private static String worldKey(Location location) {
        if (location == null || location.getWorld() == null || location.getWorld().getName() == null) return "";
        return location.getWorld().getName().toLowerCase(Locale.ROOT);
    }

    private static int nonNegativeLimit(ConfigurationSection section, String primary, String alias,
                                       List<String> warnings, String where) {
        String key = section.contains(primary) ? primary : section.contains(alias) ? alias : primary;
        int value = YamlNums.nonNegative(section, key, 0, warnings, where);
        if (section.contains(alias) && !section.contains(primary)) {
            warnings.add(where + " " + alias + " is accepted as the alias for " + primary + ".");
        }
        return value;
    }

    /** Checks the player gate at an explicit location so manual and automatic spawns share the same rule. */
    boolean playerGateOpen(Spawner sp, Location location) {
        return sp.minPlayers() <= 0
                || countPlayersNear(location, sp.minPlayers(), sp.playersRadius()) >= sp.minPlayers();
    }

    private static Player nearestPlayer(Location location, double radius) {
        if (location == null || location.getWorld() == null || !Double.isFinite(radius) || radius <= 0) return null;
        Player nearest = null;
        double nearestDistance = radius * radius;
        for (Player player : location.getWorld().getPlayers()) {
            if (player == null || !player.isOnline() || player.isDead()
                    || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            double distance = location.distanceSquared(player.getLocation());
            if (distance <= nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /** Deterministic test/diagnostic hook for player gates without a Bukkit world. */
    public static boolean playerGate(int minPlayers, int nearbyPlayers, double radius) {
        if (minPlayers <= 0) return true;
        if (!Double.isFinite(radius) || radius <= 0) return false;
        return nearbyPlayers >= minPlayers;
    }

    private void trySpawn(Spawner sp) {
        if (!withinSpawnLimits(sp)) return;
        Location loc = randomLocation(sp);
        if (loc == null) return;
        if (!conditionsPass(sp, loc)) return;
        if (!playerGateOpen(sp, loc)) return;
        Player owner = nearestPlayer(loc, sp.playersRadius());
        if (!withinScopedLimits(sp, loc, owner)) return;
        String mobId = sp.randomPool() == null ? sp.mobId() : sp.randomPool().choose(random);
        int instId = safeSpawn(mobId, loc, sp.aiProfile());
        if (instId > 0) {
            alive.computeIfAbsent(sp.id(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(instId);
            sp.totalSpawned++;
            sp.recordSpawn(mobId);
            recordScopedSpawn(sp, loc, owner);
        }
    }

    private List<String> readConditions(ConfigurationSection section) {
        List<String> conditions = new ArrayList<>(section.getStringList("conditions"));
        if (conditions.isEmpty() && section.isString("conditions")) {
            String value = section.getString("conditions", "").trim();
            if (!value.isBlank()) conditions.add(value);
        }
        return conditions;
    }

    private boolean conditionsPass(Spawner sp, Location location) {
        if (sp.conditions().isEmpty()) return true;
        World world = location == null ? null : location.getWorld();
        if (world == null) return false;
        for (String raw : sp.conditions()) {
            if (raw == null || raw.isBlank()) continue;
            String condition = raw.trim();
            boolean negate = condition.startsWith("!");
            if (negate) condition = condition.substring(1).trim();
            String normalized = condition.toLowerCase(Locale.ROOT);
            boolean passed;
            if (normalized.equals("day")) passed = world.getTime() < 12000;
            else if (normalized.equals("night")) passed = world.getTime() >= 12000;
            else if (normalized.equals("raining")) passed = world.hasStorm();
            else if (normalized.equals("thundering")) passed = world.isThundering();
            else if (normalized.startsWith("lunar-phase-is=")) {
                passed = lunarPhase(world.getFullTime()).equalsIgnoreCase(condition.substring(15).trim());
            } else if (normalized.startsWith("moon-phase-is=")) {
                passed = lunarPhase(world.getFullTime()).equalsIgnoreCase(condition.substring(14).trim());
            }
            else if (normalized.startsWith("player-faction-is=")) {
                String faction = condition.substring(18).trim();
                passed = !faction.isBlank() && playerFactionNear(location, sp.playersRadius(), faction);
            }
            else if (normalized.startsWith("player-faction-allied-to=")) {
                String faction = condition.substring(26).trim();
                passed = !faction.isBlank() && playerFactionRelationNear(location, sp.playersRadius(), faction, true);
            } else if (normalized.startsWith("player-faction-hostile-to=")) {
                String faction = condition.substring(27).trim();
                passed = !faction.isBlank() && playerFactionRelationNear(location, sp.playersRadius(), faction, false);
            }
            else if (normalized.startsWith("region=")) {
                String regionName = condition.substring(7).trim();
                Boolean external = regionTester == null ? null : regionTester.contains(regionName, location);
                RegionBounds named = regions.get(regionName.toLowerCase(Locale.ROOT));
                passed = external != null ? external : named != null ? named.contains(location) : insideRegion(location, regionName);
            }
            else if (normalized.startsWith("biome-is=")) {
                passed = location.getBlock().getBiome().name().equalsIgnoreCase(condition.substring(9).trim());
            } else if (normalized.startsWith("time-between=")) {
                passed = timeBetween(world.getTime(), condition.substring(13));
            } else if (normalized.startsWith("entities-at-least=")) {
                int min = (int) number(condition.substring(18), -1);
                passed = min >= 0 && livingCount(location, sp.playersRadius(), false) >= min;
            } else if (normalized.startsWith("mobs-at-least=")) {
                int min = (int) number(condition.substring(14), -1);
                passed = min >= 0 && livingCount(location, sp.playersRadius(), true) >= min;
            } else if (normalized.startsWith("players-at-most=")) {
                int max = (int) number(condition.substring(16), -1);
                passed = max >= 0 && countPlayersNear(location, max + 1, sp.playersRadius()) <= max;
            }
            else if (normalized.startsWith("world-is=")) passed = world.getName().equalsIgnoreCase(condition.substring(9).trim());
            else if (normalized.startsWith("world-is ")) passed = world.getName().equalsIgnoreCase(condition.substring(9).trim());
            else if (normalized.startsWith("y-below=")) {
                double limit = number(condition.substring(8), Double.NaN);
                passed = Double.isFinite(limit) && location.getY() < limit;
            } else if (normalized.startsWith("y-above=")) {
                double limit = number(condition.substring(8), Double.NaN);
                passed = Double.isFinite(limit) && location.getY() > limit;
            }
            else if (normalized.startsWith("players-at-least=")) {
                int min = (int) number(condition.substring(17), -1);
                passed = min >= 0 && countPlayersNear(location, min, sp.playersRadius()) >= min;
            } else {
                ConditionTester tester = conditionTester;
                if (tester != null) {
                    try {
                        Boolean result = tester.test(raw, location);
                        if (result != null) {
                            if (!result) return false;
                            continue;
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
                String warning = "Spawner " + sp.id() + " has unsupported condition: " + raw;
                if (firstSpawnWarning(warning)) problems.add(warning);
                return false;
            }
            if (negate ? passed : !passed) return false;
        }
        return true;
    }

    private static double number(String raw, double fallback) {
        try { return Double.parseDouble(raw.trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean timeBetween(long rawTime, String rawRange) {
        String[] parts = rawRange.split(",", 2);
        if (parts.length != 2) return false;
        double start = number(parts[0], Double.NaN);
        double end = number(parts[1], Double.NaN);
        if (!Double.isFinite(start) || !Double.isFinite(end)) return false;
        long time = Math.floorMod(rawTime, 24_000L);
        start = Math.max(0, Math.min(24_000, start));
        end = Math.max(0, Math.min(24_000, end));
        return start <= end ? time >= start && time <= end : time >= start || time <= end;
    }

    /** Vanilla advances through eight moon phases, one phase per Minecraft day. */
    public static String lunarPhase(long fullTime) {
        String[] phases = {"FULL", "WANING_GIBBOUS", "THIRD_QUARTER", "WANING_CRESCENT",
                "NEW", "WAXING_CRESCENT", "FIRST_QUARTER", "WAXING_GIBBOUS"};
        long day = Math.floorDiv(fullTime, 24_000L);
        return phases[(int) Math.floorMod(day, phases.length)];
    }

    private static boolean insideRegion(Location location, String rawBounds) {
        if (location == null) return false;
        String[] parts = rawBounds.split(",");
        if (parts.length == 7) {
            if (location.getWorld() == null || !location.getWorld().getName().equalsIgnoreCase(parts[0].trim())) return false;
            parts = java.util.Arrays.copyOfRange(parts, 1, parts.length);
        }
        if (parts.length != 6) return false;
        double x1 = number(parts[0], Double.NaN);
        double y1 = number(parts[1], Double.NaN);
        double z1 = number(parts[2], Double.NaN);
        double x2 = number(parts[3], Double.NaN);
        double y2 = number(parts[4], Double.NaN);
        double z2 = number(parts[5], Double.NaN);
        if (!(Double.isFinite(x1) && Double.isFinite(y1) && Double.isFinite(z1)
                && Double.isFinite(x2) && Double.isFinite(y2) && Double.isFinite(z2))) return false;
        return location.getX() >= Math.min(x1, x2) && location.getX() <= Math.max(x1, x2)
                && location.getY() >= Math.min(y1, y2) && location.getY() <= Math.max(y1, y2)
                && location.getZ() >= Math.min(z1, z2) && location.getZ() <= Math.max(z1, z2);
    }

    private Boolean externalRegion(String name, Location location) {
        return null;
    }

    private static int livingCount(Location location, double radius, boolean mobsOnly) {
        if (location == null || location.getWorld() == null || radius <= 0) return 0;
        int count = 0;
        for (org.bukkit.entity.Entity entity : location.getWorld()
                .getNearbyEntities(location, radius, radius, radius)) {
            if (!(entity instanceof org.bukkit.entity.LivingEntity living)
                    || living.isDead() || !living.isValid()) continue;
            if (mobsOnly && !(living instanceof org.bukkit.entity.Mob)) continue;
            count++;
        }
        return count;
    }

    private static boolean playerFactionNear(Location location, double radius, String faction) {
        if (location == null || location.getWorld() == null || radius <= 0) return false;
        var table = dev.helstera.api.behavior.Factions.table();
        if (table == null) return false;
        double radiusSquared = radius * radius;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || player.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
            if (location.distanceSquared(player.getLocation()) > radiusSquared) continue;
            String actual = table.factionOf(player);
            if (actual != null && actual.equalsIgnoreCase(faction)) return true;
        }
        return false;
    }

    private static boolean playerFactionRelationNear(Location location, double radius,
                                                     String faction, boolean allied) {
        if (location == null || location.getWorld() == null || radius <= 0) return false;
        var table = dev.helstera.api.behavior.Factions.table();
        if (table == null) return false;
        double radiusSquared = radius * radius;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || player.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
            if (location.distanceSquared(player.getLocation()) > radiusSquared) continue;
            String actual = table.factionOf(player);
            if (actual == null) continue;
            boolean matches = table.alliedFactions(actual, faction);
            if (matches == allied) return true;
        }
        return false;
    }

    private int safeSpawn(String mobId, Location loc, String aiProfile) {
        try {
            return spawner.spawn(mobId, loc, aiProfile);
        } catch (Throwable t) {
            if (log != null) log.warning("[刷怪] 生成 " + mobId + " 失败: " + t);
            return -1;
        }
    }

    /** 在圆内随机取点；y 按 y-range 微幅抖动以避免所有生物严格同高。 */
    private Location randomLocation(Spawner sp) {
        World world = resolveWorld(sp.world());
        if (world == null) return null;
        Location base = sp.base(world);
        if (base == null || !withinWorldHeight(world, base.getY(), sp.yRange())) {
            warnUnsafeLocation(sp, "base Y/range is outside world height");
            return null;
        }
        double configuredRadius = sp.radius();
        if (!Double.isFinite(configuredRadius) || configuredRadius < 0) {
            warnUnsafeLocation(sp, "radius is invalid");
            return null;
        }

        WorldBorder border = world.getWorldBorder();
        double radius = safeRadiusWithinBorder(configuredRadius, base.getX(), base.getZ(),
                border.getCenter().getX(), border.getCenter().getZ(), border.getSize());
        if (radius < configuredRadius) warnUnsafeLocation(sp, "spawn radius clipped to world-border clearance");
        if (radius <= 0) {
            if (!border.isInside(base)) return null;
            if (sp.yRange() > 0) {
                base.setY(clampWorldY(world, base.getY() + (random.nextDouble() - 0.5) * sp.yRange()));
            }
            return groundAt(world, base);
        }

        double angle = random.nextDouble() * Math.PI * 2;
        double distance = Math.sqrt(random.nextDouble()) * radius;
        double dx = Math.cos(angle) * distance;
        double dz = Math.sin(angle) * distance;
        double dy = sp.yRange() > 0 ? (random.nextDouble() - 0.5) * sp.yRange() : 0;
        Location candidate = base.clone().add(dx, dy, dz);
        candidate.setY(clampWorldY(world, candidate.getY()));
        if (!border.isInside(candidate)) return null;
        return groundAt(world, candidate);
    }

    public boolean firstSpawnWarning(String key) {
        return spawnWarningKeys.add(key);
    }

    private void warnUnsafeLocation(Spawner sp, String reason) {
        String message = "Spawner " + sp.id() + " skipped spawn because " + reason + ".";
        if (firstSpawnWarning(message)) {
            if (log != null) log.warning(message);
            problems.add(message);
        }
    }

    public static double safeRadiusWithinBorder(double configuredRadius, double x, double z,
                                         double centerX, double centerZ, double borderSize) {
        if (!Double.isFinite(configuredRadius) || configuredRadius < 0
                || !Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(centerX) || !Double.isFinite(centerZ)
                || !Double.isFinite(borderSize) || borderSize <= 0) return 0;
        double half = borderSize / 2.0;
        double remainingX = half - Math.abs(x - centerX);
        double remainingZ = half - Math.abs(z - centerZ);
        return Math.max(0, Math.min(configuredRadius, Math.min(remainingX, remainingZ)));
    }

    public static boolean worldHeightRangeIntersects(int minHeight, int maxHeight, double centerY, double range) {
        return Double.isFinite(centerY) && Double.isFinite(range) && range >= 0
                && centerY + range / 2 >= minHeight
                && centerY - range / 2 <= maxHeight;
    }

    private static boolean withinWorldHeight(World world, double y, double range) {
        return worldHeightRangeIntersects(world.getMinHeight(), world.getMaxHeight(), y, range);
    }

    private static double clampWorldY(World world, double y) {
        return Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight(), y));
    }

    private Location groundAt(World world, Location location) {
        if (world == null || location == null) return null;
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        boolean loaded = world.isChunkLoaded(chunkX, chunkZ);
        return groundAt(world, location, loaded, this::highestSolidYAt);
    }

    @FunctionalInterface
    public interface HeightMapReader {
        int highestY(World world, int blockX, int blockZ);
    }

    public Location groundAt(World world, Location location, boolean chunkLoaded, HeightMapReader heightMapReader) {
        if (world == null || location == null || heightMapReader == null || !chunkLoaded) return null;
        try {
            int blockX = location.getBlockX();
            int blockZ = location.getBlockZ();
            int heightMapY = heightMapReader.highestY(world, blockX, blockZ);
            int floorY = floorLocationY(world.getMinHeight(), world.getMaxHeight(), heightMapY,
                    y -> world.getBlockAt(blockX, y, blockZ).isSolid());
            if (floorY == Integer.MIN_VALUE) {
                warnUnsafeLocationForWorld(world, "no solid floor in candidate column");
                return null;
            }
            return new Location(world, location.getX(), floorY, location.getZ());
        } catch (RuntimeException ex) {
            warnUnsafeLocationForWorld(world, "ground lookup failed: " + ex.getMessage());
            return null;
        }
    }

    private int highestSolidYAt(World world, int blockX, int blockZ) {
        return world.getHighestBlockYAt(blockX, blockZ, org.bukkit.HeightMap.OCEAN_FLOOR);
    }

    public static int floorLocationY(int minHeight, int maxHeight, int heightMapY,
                                     java.util.function.IntPredicate isSolid) {
        if (maxHeight <= minHeight || heightMapY < minHeight || heightMapY >= maxHeight || isSolid == null) {
            return Integer.MIN_VALUE;
        }
        int lowestY = Math.max(minHeight, heightMapY - MAX_GROUND_SEARCH_BLOCKS);
        for (int solidY = heightMapY; solidY >= lowestY; solidY--) {
            if (isSolid.test(solidY)) return Math.min(solidY + 1, maxHeight - 1);
        }
        return Integer.MIN_VALUE;
    }

    private void warnUnsafeLocationForWorld(World world, String reason) {
        String message = "Spawner location in " + world.getName() + " skipped: " + reason;
        if (firstSpawnWarning(message)) {
            if (log != null) log.warning(message);
            problems.add(message);
        }
    }

    private World resolveWorld(String name) {
        try {
            if (name != null && !name.isBlank()) {
                World w = worldResolver.apply(name);
                if (w != null) return w;
                if (log != null) log.warning("[刷怪] 世界 " + name + " 不存在，已跳过该刷怪点");
                return null;
            }
            return org.bukkit.Bukkit.getWorlds().isEmpty() ? null : org.bukkit.Bukkit.getWorlds().get(0);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 半径内玩家数；用于 min-players 门控。
     *
     * <p>半径由 {@code players-radius} 独立给出，不再从 {@code min-players} 推导：
     * 两者是「需要几名玩家」与「在多大范围内数人」，语义不同。
     * 早先把半径写成 {@code min * min * 16.0}，等于把人数当格数——
     * 配 3 人时判定范围 12 格，配 8 人时 32 格，门槛越严反而数到的人越远。</p>
     */
    private int countPlayersNear(Location loc, int min, double radius) {
        if (radius <= 0) return 0;
        int count = 0;
        double r2 = radius * radius;
        try {
            for (Player p : loc.getWorld().getPlayers()) {
                if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.getGameMode() == org.bukkit.GameMode.CREATIVE) {
                    continue;
                }
                if (loc.getWorld().equals(p.getWorld()) && loc.distanceSquared(p.getLocation()) <= r2) {
                    count++;
                    if (count >= min) break;
                }
            }
        } catch (Throwable ignored) {
        }
        return count;
    }
}
