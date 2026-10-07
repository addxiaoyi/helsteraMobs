package dev.helstera.ai.spawner;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

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

    /** 插件层提供的生成能力：按 mobs 配置生成，返回实例 ID（-1 表示失败）。
     * {@code aiProfile} 非 null 时覆盖 mob 档案的 AI 配置。 */
    @FunctionalInterface
    public interface MobSpawner {
        int spawn(String mobId, Location loc, String aiProfile);
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
        private final int minPlayers;
        private final double playersRadius;
        private final boolean enabled;
        private final double yRange;
        /** 覆盖 mob 档案的 AI 配置；null 表示不覆盖，沿用 mob 自身档案。 */
        private final String aiProfile;
        private long nextSpawnTick;
        private int totalSpawned;

        Spawner(String id, String mobId, String world, double x, double y, double z,
                double radius, int intervalTicks, int maxAlive, int maxSpawns,
                int minPlayers, double playersRadius, boolean enabled, double yRange,
                String aiProfile) {
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
            this.minPlayers = Math.max(0, minPlayers);
            this.playersRadius = Math.max(0, playersRadius);
            this.enabled = enabled;
            this.yRange = Math.max(0, yRange);
            this.aiProfile = aiProfile == null || aiProfile.isBlank() ? null : aiProfile.trim();
            this.nextSpawnTick = 0;
        }

        public String id() { return id; }
        public String mobId() { return mobId; }
        public String world() { return world; }
        public double radius() { return radius; }
        public int intervalTicks() { return intervalTicks; }
        public int maxAlive() { return maxAlive; }
        public int maxSpawns() { return maxSpawns; }
        public int minPlayers() { return minPlayers; }
        public double playersRadius() { return playersRadius; }
        public double yRange() { return yRange; }
        public boolean enabled() { return enabled; }
        public int totalSpawned() { return totalSpawned; }
        /** 返回覆盖用的 AI 档案名；null 表示不覆盖。 */
        public String aiProfile() { return aiProfile; }

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
    private final Random random = new Random();
    private final Map<String, Spawner> spawners = new ConcurrentHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final Map<String, List<Integer>> alive = new ConcurrentHashMap<>();
    private BukkitTask task;
    private int tickCounter;
    private int minInterval = 100;

    public SpawnerService(Plugin plugin, Logger log, MobSpawner spawner) {
        this.plugin = plugin;
        this.log = log;
        this.spawner = spawner;
    }

    public void load(ConfigurationSection root) {
        spawners.clear();
        alive.clear();
        problems.clear();
        runtimeDisabled.clear();
        minInterval = Integer.MAX_VALUE;
        if (root == null) {
            minInterval = 100;
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
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
            Spawner sp = new Spawner(
                    id, mobId,
                    s.getString("world", ""),
                    s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                    s.getDouble("radius", 6.0),
                    s.getInt("interval", 200),
                    s.getInt("max-alive", 3),
                    s.getInt("max-spawns", 0),
                    s.getInt("min-players", 0),
                    s.getDouble("players-radius", 24.0),
                    s.getBoolean("enabled", true),
                    s.getDouble("y-range", 0),
                    s.getString("ai-profile"));
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

    public Collection<String> ids() {
        return Collections.unmodifiableSet(spawners.keySet());
    }

    public Spawner get(String id) {
        return id == null ? null : spawners.get(id.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return spawners.size();
    }

    public List<String> warnings() {
        return List.copyOf(problems);
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
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        alive.clear();
    }

    /** 手动触发一次生成（/helstera spawner force <id>），忽略间隔与上限。 */
    public boolean forceSpawn(String id, Location override) {
        Spawner sp = get(id);
        if (sp == null) return false;
        Location loc = override != null ? override : randomLocation(sp);
        if (loc == null) return false;
        int instId = safeSpawn(sp.mobId(), loc, sp.aiProfile());
        if (instId > 0) {
            alive.computeIfAbsent(sp.id(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(instId);
            sp.totalSpawned++;
        }
        return instId > 0;
    }

    /** 共享节拍：只处理到点的刷怪点。 */
    private void tick() {
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

    private void trySpawn(Spawner sp) {
        if (sp.maxSpawns() > 0 && sp.totalSpawned >= sp.maxSpawns()) return;
        if (aliveCount(sp.id()) >= sp.maxAlive()) return;
        Location loc = randomLocation(sp);
        if (loc == null) return;
        if (sp.minPlayers() > 0 && countPlayersNear(loc, sp.minPlayers(), sp.playersRadius()) < sp.minPlayers()) return;
        int instId = safeSpawn(sp.mobId(), loc, sp.aiProfile());
        if (instId > 0) {
            alive.computeIfAbsent(sp.id(), k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(instId);
            sp.totalSpawned++;
        }
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
        World w = resolveWorld(sp.world());
        if (w == null) return null;
        Location base = sp.base(w);
        if (base == null) return null;
        double r = sp.radius();
        if (r <= 0) {
            if (sp.yRange() > 0) base.setY(base.getY() + (random.nextDouble() - 0.5) * sp.yRange());
            return base;
        }
        // sqrt 保证圆内均匀分布：直接用半径随机会向圆心聚集
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = Math.sqrt(random.nextDouble()) * r;
        double dx = Math.cos(angle) * dist;
        double dz = Math.sin(angle) * dist;
        double dy = sp.yRange() > 0 ? (random.nextDouble() - 0.5) * sp.yRange() : 0;
        Location loc = base.clone().add(dx, dy, dz);
        // 落到地面：随机点常常悬空/埋地，直接找最近的安全落脚点
        return groundAt(w, loc);
    }

    private Location groundAt(World w, Location loc) {
        try {
            int x = loc.getBlockX(), z = loc.getBlockZ();
            int startY = Math.max(w.getMinHeight() + 1, loc.getBlockY() + 8);
            int floor = w.getMinHeight();
            for (int y = startY; y >= w.getMinHeight() + 1; y--) {
                if (w.getBlockAt(x, y - 1, z).getType().isSolid()) {
                    floor = y;
                    break;
                }
            }
            return new Location(w, loc.getX(), floor, loc.getZ());
        } catch (Throwable t) {
            return loc;
        }
    }

    private World resolveWorld(String name) {
        try {
            if (name != null && !name.isBlank()) {
                World w = plugin.getServer().getWorld(name);
                if (w != null) return w;
                if (log != null) log.warning("[刷怪] 世界 " + name + " 不存在，已跳过该刷怪点");
                return null;
            }
            return plugin.getServer().getWorlds().isEmpty() ? null : plugin.getServer().getWorlds().get(0);
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