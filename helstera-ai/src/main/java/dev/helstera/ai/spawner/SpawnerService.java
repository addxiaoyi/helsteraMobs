package dev.helstera.ai.spawner;

import dev.helstera.ai.YamlNums;
import dev.helstera.api.util.ConditionListParser;
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
        /**
         * 成组生成的规模区间（Mythic 的 GroupSize / group-size）。
         *
         * <p>1..1 表示关闭。成组只影响「一次生成尝试生成几只」，不影响存活/累计上限：
         * 上限仍按实例逐只计入，所以 groupMin 超过 maxAlive 时会被上限自然截断，
         * 而不是绕过上限。</p>
         */
        private final int groupMin;
        private final int groupMax;
        /**
         * 间隔抖动（Mythic 的 Interval 区间 / 0%~50%）。
         *
         * <p>真实秒数落在 {@code interval * [1 - jitter, 1 + jitter]}，下一次生成重新掷。
         * 取 0 时退化成固定间隔，与旧行为逐 tick 一致。</p>
         */
        private final double intervalJitter;
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
            this(id, mobId, world, x, y, z, radius, intervalTicks, maxAlive, maxSpawns,
                    maxPerWorld, maxPerPlayer, minPlayers, playersRadius,
                    enabled, yRange, 1, 1, 0, aiProfile, randomPool, conditions);
        }

        Spawner(String id, String mobId, String world, double x, double y, double z,
                double radius, int intervalTicks, int maxAlive, int maxSpawns,
                int maxPerWorld, int maxPerPlayer, int minPlayers, double playersRadius,
                boolean enabled, double yRange, int groupMin, int groupMax, double intervalJitter,
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
            this.groupMin = Math.max(1, Math.min(groupMin, Math.max(1, groupMax)));
            this.groupMax = Math.max(1, groupMax);
            this.intervalJitter = Double.isFinite(intervalJitter) ? Math.max(0, Math.min(1, intervalJitter)) : 0;
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
        public int groupMin() { return groupMin; }
        public int groupMax() { return groupMax; }
        public double intervalJitter() { return intervalJitter; }
        /** 一次生成尝试的目标只数（含随机化）；未配置 group-size 时恒为 1。 */
        public int rollGroupSize(Random rnd) {
            if (groupMax <= groupMin) return groupMin;
            return groupMin + rnd.nextInt(groupMax - groupMin + 1);
        }
        /** 本次生成的等待 tick 数：interval 上下抖动 intervalJitter 比例，并保持 Spawner 的 20 tick 下限。 */
        public int rollIntervalTicks(Random rnd) {
            if (intervalJitter <= 0) return intervalTicks;
            double span = intervalTicks * intervalJitter;
            double rolled = intervalTicks + (rnd.nextDouble() * 2 - 1) * span;
            return Math.max(20, (int) Math.round(rolled));
        }
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
            mobSpawnCounts.clear();
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
        problems.clear();
        spawnWarningKeys.clear();
        runtimeDisabled.clear();
        minInterval = Integer.MAX_VALUE;
        if (root == null) {
            alive.clear();
            worldSpawnCounts.clear();
            playerSpawnCounts.clear();
            minInterval = 100;
            return;
        }
        ConfigurationSection source = root;
        ConfigurationSection nested = root.getConfigurationSection("spawners");
        if (nested != null && !root.contains("mob")) source = nested;
        // A full spawners.yml keeps named regions beside the `spawners` map;
        // a direct section load may keep them on the section itself. Resolve
        // both shapes before iterating only the actual spawner entries.
        ConfigurationSection regionSection = root.getConfigurationSection("regions");
        if (regionSection == null && source != root) {
            regionSection = source.getConfigurationSection("regions");
        }
        loadRegions(regionSection);
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
            // 抖动键名：优先 interval-jitter，其次 <interval 键名>-jitter（cooldown-jitter）。
            String jitterKey = s.contains("interval-jitter") ? "interval-jitter"
                    : s.contains("cooldown-jitter") ? "cooldown-jitter" : null;
            if (jitterKey != null && !jitterKey.equals("interval-jitter")) {
                problems.add(where + " " + jitterKey + " is accepted as the alias for interval-jitter.");
            }
            Object rawInterval = s.get(intervalKey);
            double intervalJitter = 0;
            if (rawInterval instanceof List<?> intervalList && intervalList.size() == 2) {
                double lo = scalarDouble(intervalList.get(0));
                double hi = scalarDouble(intervalList.get(1));
                if (Double.isFinite(lo) && Double.isFinite(hi) && lo > 0 && hi >= lo) {
                    interval = (int) Math.max(20, Math.round((lo + hi) / 2.0));
                    // 半宽相对均值：抖动比 = (hi - lo) / (hi + lo)，落在 [0, 1)
                    intervalJitter = Math.min(1.0, (hi - lo) / (lo + hi));
                    problems.add(where + " interval 写成区间 [" + fmt(lo) + ", " + fmt(hi)
                            + "]，已按均值 " + interval + " tick ±" + fmt(intervalJitter * 100) + "% 抖动执行。");
                    // 区间写法已经隐含了抖动；此时再写 interval-jitter 会被下面的
                    // 分支完全跳过——作者以为两处都生效，实际只有区间起作用。
                    // 这类「写了但静默不生效」正是 YamlNums 要消灭的病根，必须报出来。
                    if (jitterKey != null) {
                        problems.add(where + " 同时配置了 " + jitterKey + " 与区间写法的 interval，"
                                + jitterKey + " 已被忽略；如需自定义抖动请改用固定 interval 值。");
                    }
                } else {
                    problems.add(where + " interval 区间非法（需为正数且上界不小于下界），已按默认值处理。");
                }
            } else if (jitterKey != null) {
                // 支持两种写法：比例 0.3，或百分数 30%。后者不能交给
                // YamlNums.chance —— 它只认纯数字，"30%" 会被判成非法值而静默回落 0，
                // 表现就是「interval-jitter: 30% 写了完全不生效」。
                intervalJitter = readJitter(s, jitterKey, problems, where);
                if (intervalJitter <= 0) {
                    problems.add(where + " " + jitterKey + " 必须为正比例（如 0.3 或 30%），已忽略。");
                    intervalJitter = 0;
                }
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
            // 成组生成：Mythic 的 GroupSize: 3 / GroupSize: 2-5 / group-size: 3。
            // 非正数一律视为 1（= 关闭），而不是让整条刷怪点失效——作者写 0 的
            // 本意多半是「别成组」。整数超过 maxAlive 时由上限自然截断，见 trySpawn。
            int groupMin = 1;
            int groupMax = 1;
            Object rawGroup = s.contains("group-size") ? s.get("group-size")
                    : s.contains("groupsize") ? s.get("groupsize")
                    : s.contains("GroupSize") ? s.get("GroupSize")
                    : s.contains("Group-Size") ? s.get("Group-Size") : null;
            if (rawGroup != null) {
                int[] range = parseRange(rawGroup);
                if (range == null) {
                    // 只有「读不懂」才告警：group-size = 两只 这类作者显然写错了。
                    problems.add(where + " group-size = " + rawGroup
                            + " 无法解析（应为 3 或 2-5），已按不成组处理。");
                } else if (range[0] <= 0) {
                    // 0 / 负数属于「别成组」的合法写法，静默按 1 处理，不必告警。
                    groupMin = 1;
                    groupMax = 1;
                } else {
                    groupMin = range[0];
                    groupMax = range[1];
                }
            }
            if (groupMax > 1 && groupMax > maxAlive * 4 && maxAlive > 0) {
                problems.add(where + " group-size 上界 " + groupMax + " 远大于 max-alive " + maxAlive
                        + "，每次尝试只会生成到上限为止，多余的会被丢弃。");
            }
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
                    groupMin, groupMax, intervalJitter,
                    s.getString("ai-profile"), parseRandomPool(s, problems, where),
                    readConditions(s));
            spawners.put(id, sp);
            alive.putIfAbsent(id, Collections.synchronizedList(new ArrayList<>()));
            minInterval = Math.min(minInterval, sp.intervalTicks);
        }
        // A config reload must not forget living instances tracked by an
        // existing spawner, but removed spawner definitions must be pruned.
        for (String existing : new ArrayList<>(alive.keySet())) {
            if (!spawners.containsKey(existing)) alive.remove(existing);
        }
        for (String existing : new ArrayList<>(playerSpawnCounts.keySet())) {
            if (!spawners.containsKey(existing)) playerSpawnCounts.remove(existing);
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
            // A state file is a snapshot. Reloading it must replace prior
            // scoped counters instead of retaining entries removed from the
            // newer snapshot.
            worldSpawnCounts.clear();
            playerSpawnCounts.clear();
            for (Spawner sp : spawners.values()) sp.restoreStats(0, Map.of());
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

    /**
     * Returns the currently live instance IDs tracked by a spawner.
     *
     * <p>The snapshot is intentionally read-only and prunes invalid instances
     * through the same injected liveness check as {@link #aliveCount(String)}.
     * The plugin uses it for lifecycle diagnostics without exposing the
     * mutable tracking list.</p>
     */
    public List<Integer> trackedInstanceIds(String id) {
        if (id == null) return List.of();
        List<Integer> list = alive.get(id.toLowerCase(Locale.ROOT));
        if (list == null) return List.of();
        synchronized (list) {
            list.removeIf(i -> i == null || !isAlive(i));
            return List.copyOf(list);
        }
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
        cancelTask();
        if (spawners.isEmpty()) return;
        int period = Math.max(20, minInterval);
        if (plugin == null) throw new IllegalStateException("Plugin is required to start the spawner scheduler");
        // The shared task runs every `period` real ticks. Advance the logical
        // clock by that amount so interval/cooldown values are not multiplied
        // by the shared scheduler period.
        task = plugin.getServer().getScheduler().runTaskTimer(plugin,
                () -> tickOnce(period), period, period);
    }

    public void stop() {
        cancelTask();
        alive.clear();
        worldSpawnCounts.clear();
        playerSpawnCounts.clear();
    }

    private void cancelTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
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
        // 手动生成同样按 group-size 成组：否则「强制刷一群」会与定时生成行为不一致，
        // 排查时看到的数量对不上。上限仍是逐只判定。
        int attempts = sp.rollGroupSize(random);
        int spawned = 0;
        for (int i = 0; i < attempts; i++) {
            if (i > 0) {
                if (!withinSpawnLimits(sp)) break;
                if (!withinScopedLimits(sp, loc, owner)) break;
            }
            int instId = safeSpawn(mobId, loc, sp.aiProfile());
            if (instId <= 0) break;
            spawned++;
            alive.computeIfAbsent(sp.id(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(instId);
            sp.totalSpawned++;
            sp.recordSpawn(mobId);
            recordScopedSpawn(sp, loc, owner);
        }
        if (spawned > 0) {
            forceSpawnFailures.remove(key);
            return true;
        }
        return failForceSpawn(key, "spawn callback returned failure for " + mobId);
    }

    private boolean failForceSpawn(String id, String reason) {
        forceSpawnFailures.put(id, reason);
        return false;
    }

    public String forceSpawnFailure(String id) {
        return id == null ? null : forceSpawnFailures.get(id.toLowerCase(Locale.ROOT));
    }

    /** Advances one logical tick; exposed for deterministic tests and diagnostics. */
    public void tickOnce() {
        tickOnce(1);
    }

    /** Advances the logical clock by real server ticks for the shared scheduler. */
    public void tickOnce(int elapsedTicks) {
        tickCounter += Math.max(1, elapsedTicks);
        for (Spawner sp : spawners.values()) {
            try {
                if (!isActive(sp)) continue;
                if (tickCounter < sp.nextSpawnTick) continue;
                if (trySpawn(sp)) {
                    sp.nextSpawnTick = tickCounter + sp.rollIntervalTicks(random);
                }
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
                    || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                    || player.getGameMode() == org.bukkit.GameMode.CREATIVE) continue;
            Location playerLocation;
            try {
                playerLocation = player.getLocation();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (playerLocation == null) continue;
            double distance;
            try {
                distance = location.distanceSquared(playerLocation);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (distance <= nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /** Returns whether a player can own a player-scoped spawn at this location. */
    public boolean playerOwnerPresent(String id, Location location) {
        Spawner sp = get(id);
        return sp != null && nearestPlayer(location, sp.playersRadius()) != null;
    }

    /** Deterministic test/diagnostic hook for player gates without a Bukkit world. */
    public static boolean playerGate(int minPlayers, int nearbyPlayers, double radius) {
        if (minPlayers <= 0) return true;
        if (!Double.isFinite(radius) || radius <= 0) return false;
        return nearbyPlayers >= minPlayers;
    }

    private boolean trySpawn(Spawner sp) {
        if (!withinSpawnLimits(sp)) return false;
        Location loc = randomLocation(sp);
        if (loc == null) return false;
        if (!conditionsPass(sp, loc)) return false;
        if (!playerGateOpen(sp, loc)) return false;
        Player owner = nearestPlayer(loc, sp.playersRadius());
        if (!withinScopedLimits(sp, loc, owner)) return false;
        String mobId = sp.randomPool() == null ? sp.mobId() : sp.randomPool().choose(random);
        // 成组生成：一次节拍尝试生成 rollGroupSize 只，但每只都必须单独过存活上限与
        // world/player 作用域上限——否则 group-size 就成了绕过 max-alive 的后门。
        // 生成成功后逐只计数，第一只失败即停止本次尝试（剩下的大概率也会失败）。
        int attempts = sp.rollGroupSize(random);
        int spawned = 0;
        for (int i = 0; i < attempts; i++) {
            if (i > 0) {
                if (!withinSpawnLimits(sp)) break;
                if (!withinScopedLimits(sp, loc, owner)) break;
            }
            int instId = safeSpawn(mobId, loc, sp.aiProfile());
            if (instId <= 0) break;
            spawned++;
            alive.computeIfAbsent(sp.id(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(instId);
            sp.totalSpawned++;
            sp.recordSpawn(mobId);
            recordScopedSpawn(sp, loc, owner);
        }
        return spawned > 0;
    }

    private List<String> readConditions(ConfigurationSection section) {
        return ConditionListParser.parse(section.get("conditions"));
    }

    /**
     * 光照门控：light-level / sky-light-level / light-level-from-blocks，
     * 形如 {@code light-level-below=8}、{@code sky-light-level=15}、{@code light-level 0-7}。
     *
     * <p>与技能侧 SkillExtras 的同名条件语义保持一致（技能条件在主世界战斗里已经用这套
     * 命名），但**这里不 import 技能模块**：刷怪点装载不需要装载整套技能注册表。
     * 因此两边各有一份实现，改动时需同步。</p>
     *
     * <p>为什么值得补：刷怪点最常见的诉求就是「只在暗处刷」「只在地表白天刷」。
     * 之前这两种条件未支持，会被当成 unsupported 条件直接拒绝生成——刷怪点一个怪都不出，
     * 而配置看上去完全正常。返回 null 表示「不是光照条件」，交回原有判定链。</p>
     */
    public static Boolean lightCondition(String condition, Location location) {
        if (condition == null) return null;
        String text = condition.trim();
        String kind;
        String body;
        int sep = indexOfSeparator(text);
        if (sep < 0) {
            kind = text;
            body = "";
        } else {
            kind = text.substring(0, sep).trim();
            body = text.substring(sep).trim();
        }
        String normalizedKind = kind.toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        boolean sky = normalizedKind.equals("sky-light-level") || normalizedKind.equals("skylightlevel");
        boolean block = normalizedKind.equals("light-level-from-blocks")
                || normalizedKind.equals("block-light-level")
                || normalizedKind.equals("blocklightlevel");
        // Mythic 的 LightLevel 是含天空光的合成光照；这里把裸写形式也归入它。
        boolean combined = normalizedKind.equals("light-level") || normalizedKind.equals("lightlevel");
        if (!sky && !block && !combined) return null;
        if (location == null || location.getWorld() == null) return false;
        String rangeText = normalizeComparator(body);
        if (rangeText.isEmpty()) return false;
        try {
            World world = location.getWorld();
            int chunkX = location.getBlockX() >> 4;
            int chunkZ = location.getBlockZ() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) return false;
            int level = sky ? location.getBlock().getLightFromSky()
                    : block ? location.getBlock().getLightFromBlocks()
                    : location.getBlock().getLightLevel();
            return appliesTo(level, rangeText, sky || block);
        } catch (Throwable t) {
            // 区块未加载时 Bukkit 取光照可能抛异常：拒绝生成而不是放行，
            // 避免刷怪点在未加载区域"盲刷"到玩家脚下。
            return false;
        }
    }

    /**
     * 把光照参数归一成 "&lt;8" / "&gt;=10" / "0-7" / "15" 这类可判定形态。
     *
     * <p>覆盖三种写法：{@code 0-7}（区间）、{@code <=8}（符号）、
     * {@code below 5} / {@code above 5} / {@code 5-}（Mythic 的比较词）。
     * 注意先剥开头的 '-'：光强不会是负数，那是分隔符而不是负号。</p>
     */
    private static String normalizeComparator(String body) {
        String text = body == null ? "" : body.trim();
        // 只有当 '=' 本身就是分隔符（"=8"）时才剥掉；绝不能剥掉 "<=" / ">=" 的第二个字符。
        if (text.startsWith("=") && !text.startsWith("==")) text = text.substring(1).trim();
        else if (text.startsWith("-")) text = text.substring(1).trim();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : new String[]{"below", "above", "at-most", "at-least", "less-than", "greater-than"}) {
            if (lower.startsWith(word)) {
                String rest = text.substring(word.length()).trim();
                if (rest.startsWith("=")) rest = rest.substring(1).trim();
                String op = switch (word) {
                    case "below", "less-than" -> "<";
                    case "above", "greater-than" -> ">";
                    case "at-most" -> "<=";
                    default -> ">=";
                };
                return op + rest;
            }
        }
        return text;
    }

    /**
     * 找出 kind 与参数的分隔位置。
     *
     * <p>支持 {@code light-level<8}（比较符贴着名字）、{@code light-level=8}、
     * {@code light-level 8}（空格）与模板写的 {@code light-level-8}。
     */
    private static int indexOfSeparator(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int nameEnd = -1;
        for (String name : new String[]{"lightlevel", "light-level", "skylightlevel",
                "sky-light-level", "blocklightlevel", "block-light-level",
                "light-level-from-blocks"}) {
            int at = lower.indexOf(name);
            if (at >= 0 && at + name.length() > nameEnd) nameEnd = at + name.length();
        }
        // 比较词形态（light-level-above=2 / blocklightlevel below 20）：
        // 名字后面紧跟 -below/-above/-at-most/-at-least。
        // 必须排在符号判断**之前**：否则 "light-level-above=2" 会先按 '=' 切开，
        // kind 得到 "light-level-above"，匹配不上任何光照类别而返回 null，
        // 这条光照配置就被当成「不认识的条件」静默放行。
        if (nameEnd > 0) {
            String rest = lower.substring(nameEnd);
            for (String word : new String[]{"-below", "-above", "-at-most", "-at-least",
                    "-less-than", "-greater-than"}) {
                if (rest.startsWith(word)) return nameEnd;
            }
        }
        int best = -1;
        for (char symbol : new char[]{'=', '<', '>'}) {
            int at = text.indexOf(symbol);
            if (at <= 0) continue;
            if (nameEnd > 0 && at < nameEnd) continue;
            if (best < 0 || at < best) best = at;
        }
        if (best > 0) return best;
        int space = text.indexOf(' ');
        // 空格只有出现在名字之后才算分隔符，否则 "light-level below 5" 的 kind 会被切断。
        if (space > 0 && (nameEnd < 0 || space >= nameEnd)) return space;
        // 名字-数字形态（模板把 '=' 写成 '-'）：连字符必须在名字之后且其后是数字。
        int dash = text.lastIndexOf('-');
        if (dash > 0 && dash < text.length() - 1 && (nameEnd < 0 || dash >= nameEnd)) {
            char next = text.charAt(dash + 1);
            if (Character.isDigit(next) || next == '<' || next == '>' || next == '=') return dash;
        }
        return -1;
    }

    private static boolean appliesTo(int level, String rangeText, boolean exact) {
        String text = rangeText.trim();
        if (text.startsWith("<=")) return level <= number(text.substring(2), Double.NaN);
        if (text.startsWith(">=")) return level >= number(text.substring(2), Double.NaN);
        if (text.startsWith("!=")) return level != number(text.substring(2), Double.NaN);
        if (text.startsWith("<")) return level < number(text.substring(1), Double.NaN);
        if (text.startsWith(">")) return level > number(text.substring(1), Double.NaN);
        if (text.startsWith("=")) return level == number(text.substring(1), Double.NaN);
        int[] range = parseRange(text);
        if (range == null) return false;
        // 单值形态（sky-light-level=15）语义是"等于"，区间形态（0-7）是闭区间。
        // 拉平会让「只在满光地表刷」变成「任何 >=15 的光」，语义漂移且不告警。
        if (range[0] == range[1] && exact) return level == range[0];
        return level >= range[0] && level <= range[1];
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
            // 光照条件带参数（light-level-above=-1），规范化会把负号也吃掉，
            // 所以先按原样判定，命中就跳过下面的小写分支。
            Boolean light = lightCondition(condition, location);
            if (light != null) {
                if (negate ? light : !light) return false;
                continue;
            }
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

    /** 宽松标量解析：接受数字与 "30%" 形态；不可解析返回 NaN，交给调用方告警。 */
    private static double scalarDouble(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        if (raw == null) return Double.NaN;
        String text = String.valueOf(raw).trim();
        boolean percent = text.endsWith("%");
        if (percent) text = text.substring(0, text.length() - 1).trim();
        double value = number(text, Double.NaN);
        return percent ? value / 100.0 : value;
    }

    /**
     * 解析 "3"、"2-5"、"2~5"、"2,5" 这类数量区间，返回 {min, max}；无法解析返回 null。
     *
     * <p>只认整数：成组数量是只数，2.5 只没有意义。区间写成 "5-2" 时归一化，
     * 不静默返回 0 让作者以为配置生效了。</p>
     */
    static int[] parseRange(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number n) {
            int value = n.intValue();
            return value == n.doubleValue() ? new int[]{value, value} : null;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return null;
        String[] parts = text.split("\\s*[-~,]\\s*", -1);
        if (parts.length == 0 || parts.length > 2) return null;
        // 逗号分隔写法拆开后可能多出空段，逐段修剪。
        int lo = parseInt(parts[0].trim());
        int hi = parts.length == 2 ? parseInt(parts[1].trim()) : lo;
        if (lo == Integer.MIN_VALUE || hi == Integer.MIN_VALUE) return null;
        return lo <= hi ? new int[]{lo, hi} : new int[]{hi, lo};
    }

    private static int parseInt(String raw) {
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException ignored) { return Integer.MIN_VALUE; }
    }

    /**
     * 读抖动比例，认 {@code 0.3}（比例）与 {@code 30%}（百分数）两种写法。
     *
     * <p>百分数必须先转成比例再交给 {@link YamlNums#chance}：后者只做纯数字解析，
     * "30%" 会被判成非法值并静默回落默认值 0——表现是「写了 interval-jitter: 30%
     * 却完全不抖动」，且日志里只有一句「不是合法数值」，看不出该写百分数。
     * </p>
     */
    private static double readJitter(ConfigurationSection s, String key,
                                     List<String> problems, String where) {
        Object raw = s.get(key);
        if (raw instanceof String str) {
            String t = str.trim();
            if (t.endsWith("%")) {
                String num = t.substring(0, t.length() - 1).trim();
                try {
                    // 百分数语义是「百分之几」，% 号之外仍是 [0,100] 的区间。
                    return clampJitter(Double.parseDouble(num) / 100.0, problems, where, key);
                } catch (NumberFormatException ignored) {
                    // 落到下面统一走 YamlNums.chance 报错
                }
            }
        }
        return clampJitter(YamlNums.d(s, key, 0, problems, where), problems, where, key);
    }

    /** 百分数形态的抖动比例：超出 0..1 时钳制并留痕，与 {@link YamlNums#chance} 口径一致。 */
    private static double clampJitter(double ratio, List<String> problems, String where, String key) {
        if (!Double.isFinite(ratio)) {
            problems.add(where + " 的 " + key + " 必须为有限数值，已按 0 处理。");
            return 0;
        }
        if (ratio < 0 || ratio > 1) {
            double clamped = Math.max(0, Math.min(1, ratio));
            problems.add(where + " 的 " + key + " = " + fmt(ratio) + " 超出 0..1，已夹到 "
                    + fmt(clamped) + "。抖动比例写错会让刷新间隔与预期差很远");
            return clamped;
        }
        return ratio;
    }

    private static String fmt(double value) {
        return Math.abs(value - Math.rint(value)) < 1e-9
                ? String.valueOf((long) Math.rint(value))
                : String.format(Locale.ROOT, "%.3f", value);
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
                if (p == null || !p.isOnline() || p.isDead()
                        || p.getGameMode() == org.bukkit.GameMode.SPECTATOR
                        || p.getGameMode() == org.bukkit.GameMode.CREATIVE) {
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
