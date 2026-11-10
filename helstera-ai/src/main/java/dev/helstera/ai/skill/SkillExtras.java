package dev.helstera.ai.skill;

import dev.helstera.api.util.EntityTypeSelector;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.behavior.VariableLocations;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.type.Farmland;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 扩展 mechanic 目录：条件与动作的第二批实现。
 *
 * <p>与 {@link SkillCatalog} 分开成两个文件，是因为后者已 500 余行，
 * 再往里堆会让「内置基础动作」与「新增 gameplay 动作」无法区分。
 * 两份目录在 {@code SkillService} 构造时合并，工厂签名与扩展点完全不变。</p>
 *
 * <p>约束同 {@code SkillCatalog}：参数在加载期解析并闭包捕获，运行期零解析；
 * 实体访问全部包 try/catch，单条动作失败不影响决策链。</p>
 */
public final class SkillExtras {

    private SkillExtras() {
    }

    // ------------------------------------------------------------------
    // 运行期依赖注入
    // ------------------------------------------------------------------

    /**
     * 宿主插件实例。
     *
     * <p>凡是需要 {@code BukkitScheduler} 的动作都必须绑定到插件实例——
     * 传 null 会直接抛 IllegalArgumentException，而堆栈里看不出是哪个动作写错的。
     * 按名猜测插件（写死 "helsteraMobs"）同样不可靠：改名或改名插件后
     * 所有定时类动作会集体静默失效。由插件层在 enable 时注入是唯一稳定做法。</p>
     */
    private static volatile Plugin host;

    /** 实例销毁钩子，供 despawn 动作使用。 */
    public interface Despawner {
        boolean despawn(int instanceId);
    }

    private static volatile Despawner despawner;

    /**
     * 仇恨表访问器。
     *
     * <p>与 {@link #host} / {@link #streaks} 同一理由：静态工厂表拿不到
     * {@code AiController}，而仇恨条件/动作必须读同一实例的那张表。
     * 另建一份表会让条件按自己记的仇恨判断，与实际选目标用的表不是同一份，
     * 症状是「条件永远不成立」。</p>
     */
    public interface ThreatLookup {
        /** 取该实例的仇恨表；档案未启用 threat 或实例无控制器时返回 null。 */
        dev.helstera.ai.ThreatTable of(int instanceId);
    }

    private static volatile ThreatLookup threatLookup;
    private static volatile PinRegistry pinRegistry;
    private static volatile java.util.function.BiFunction<String, Location, Boolean> regionLookup;
    @FunctionalInterface
    public interface LocalDifficultyLookup {
        Double difficulty(Location location);
    }

    private static volatile LocalDifficultyLookup localDifficultyLookup;
    private static final Map<String, String> KNOWN_NMS_VERSIONS = Map.ofEntries(
            Map.entry("1.20.1", "v1_20_R1"),
            Map.entry("1.20.2", "v1_20_R2"),
            Map.entry("1.20.3", "v1_20_R3"),
            Map.entry("1.20.4", "v1_20_R3"),
            Map.entry("1.20.5", "v1_20_R4"),
            Map.entry("1.20.6", "v1_20_R4"),
            Map.entry("1.21", "v1_21_R1"),
            Map.entry("1.21.1", "v1_21_R1"),
            Map.entry("1.21.2", "v1_21_R2"),
            Map.entry("1.21.3", "v1_21_R2"),
            Map.entry("1.21.4", "v1_21_R3"),
            Map.entry("1.21.5", "v1_21_R4"),
            Map.entry("1.21.6", "v1_21_R5"),
            Map.entry("1.21.7", "v1_21_R5"),
            Map.entry("1.21.8", "v1_21_R6"),
            Map.entry("1.21.9", "v1_21_R7"),
            Map.entry("1.21.10", "v1_21_R7"),
            Map.entry("1.21.11", "v1_21_R7"));
    private static final Pattern NMS_VERSION_PATTERN =
            Pattern.compile("(?i)^v?\\d+_\\d+_r\\d+(?:_\\d+)?$");
    private static final Pattern CRAFTBUKKIT_VERSION_SEGMENT =
            Pattern.compile("(?i)^v\\d+_\\d+_r\\d+(?:_\\d+)?$");

    public static void pinRegistry(PinRegistry registry) {
        pinRegistry = registry;
    }

    /** Connects region conditions to an optional external region provider. */
    public static void regionLookup(
            java.util.function.BiFunction<String, Location, Boolean> lookup) {
        regionLookup = lookup;
    }

    /** Overrides Paper's local-difficulty lookup for integrations and unit tests. */
    public static void localDifficultyLookup(LocalDifficultyLookup lookup) {
        localDifficultyLookup = lookup;
    }

    static PinRegistry pinRegistry() {
        return pinRegistry;
    }

    @FunctionalInterface
    public interface DamagePolicy {
        double apply(BehaviorContext context, LivingEntity target, double amount, String cause);
    }

    private static volatile DamagePolicy damagePolicy;
    private static volatile java.util.function.BiFunction<BehaviorContext, LivingEntity, String> damageCause;
    private static volatile java.util.function.Function<String, ItemStack> itemResolver;
    private static volatile java.util.function.Predicate<String> skillLookup;
    private static volatile java.util.function.Function<Player, java.util.OptionalDouble> currencyBalance;
    @FunctionalInterface
    public interface FactionSetter {
        void set(Entity entity, String faction);
    }
    private static volatile FactionSetter factionSetter;
    @FunctionalInterface
    public interface GlobalScoreLookup { Integer score(String objective, String entry); }
    private static volatile GlobalScoreLookup globalScoreLookup;
    @FunctionalInterface
    public interface ScoreLookup {
        Integer score(String objective, Entity target, String entry);
    }
    private static volatile ScoreLookup scoreLookup;
    private static volatile java.util.function.IntPredicate dialogueLookup;
    @FunctionalInterface
    public interface CinematicLookup {
        boolean isActiveForPlayer(int instanceId, Player player);
    }
    private static volatile CinematicLookup cinematicLookup;
    @FunctionalInterface
    public interface ManagedEntityLookup {
        boolean isManaged(Entity entity);
    }

    private static volatile ManagedEntityLookup managedEntityLookup;
    private static volatile AuraRegistry auraRegistry;
    private static final Map<String, String> AURA_NAME_ARGUMENTS = Map.ofEntries(
            Map.entry("aura", "aura"), Map.entry("auraname", "aura"),
            Map.entry("b", "aura"), Map.entry("buff", "aura"),
            Map.entry("buffname", "aura"), Map.entry("debuff", "aura"),
            Map.entry("debuffname", "aura"), Map.entry("n", "aura"),
            Map.entry("name", "aura"));
    private static final Map<String, String> AURA_STACKS_ARGUMENTS = Map.ofEntries(
            Map.entry("aura", "aura"), Map.entry("auraname", "aura"),
            Map.entry("b", "aura"), Map.entry("buff", "aura"),
            Map.entry("buffname", "aura"), Map.entry("debuff", "aura"),
            Map.entry("debuffname", "aura"), Map.entry("n", "aura"),
            Map.entry("name", "aura"), Map.entry("stacks", "stacks"),
            Map.entry("s", "stacks"));
    private static final Map<String, String> AURA_TYPE_ARGUMENTS = Map.of(
            "auragroup", "type", "auratype", "type", "group", "type",
            "type", "type", "g", "type", "t", "type");
    private static final Map<String, String> AURA_ACTION_ARGUMENTS = Map.ofEntries(
            Map.entry("auraname", "name"), Map.entry("aura", "name"),
            Map.entry("b", "name"), Map.entry("buff", "name"),
            Map.entry("buffname", "name"), Map.entry("debuff", "name"),
            Map.entry("debuffname", "name"), Map.entry("n", "name"),
            Map.entry("name", "name"), Map.entry("auratype", "type"),
            Map.entry("auragroup", "type"), Map.entry("group", "type"),
            Map.entry("type", "type"), Map.entry("g", "type"),
            Map.entry("duration", "duration"), Map.entry("ticks", "duration"),
            Map.entry("d", "duration"), Map.entry("time", "duration"),
            Map.entry("t", "duration"), Map.entry("maxstacks", "maxstacks"),
            Map.entry("ms", "maxstacks"), Map.entry("refreshduration", "refreshduration"),
            Map.entry("rd", "refreshduration"), Map.entry("mergesamecaster", "mergesamecaster"),
            Map.entry("msc", "mergesamecaster"), Map.entry("mc", "mergesamecaster"),
            Map.entry("mergeall", "mergeall"), Map.entry("ma", "mergeall"),
            Map.entry("overwritesamecaster", "overwritesamecaster"),
            Map.entry("osc", "overwritesamecaster"), Map.entry("oc", "overwritesamecaster"),
            Map.entry("overwriteall", "overwriteall"), Map.entry("overwrite", "overwriteall"),
            Map.entry("ow", "overwriteall"));
    private static final Map<String, String> AURA_REMOVE_ARGUMENTS = Map.ofEntries(
            Map.entry("aura", "name"), Map.entry("auraname", "name"),
            Map.entry("b", "name"), Map.entry("buff", "name"),
            Map.entry("buffname", "name"), Map.entry("debuff", "name"),
            Map.entry("debuffname", "name"), Map.entry("n", "name"),
            Map.entry("name", "name"), Map.entry("stacks", "stacks"),
            Map.entry("s", "stacks"));
    private static volatile java.util.function.Consumer<String> audienceDiagnostic;

    @FunctionalInterface
    public interface AudienceMobSelector {
        Collection<? extends ModelInstance> select(String targeter, BehaviorContext context);
    }

    @FunctionalInterface
    public interface ExecutionAudienceWriter {
        void write(ModelInstance instance, Collection<UUID> players);
    }

    private static volatile AudienceMobSelector audienceMobSelector;
    private static volatile ExecutionAudienceWriter executionAudienceWriter;
    @FunctionalInterface
    public interface SkillCooldownSetter {
        boolean set(String skillName, BehaviorContext context, long durationMillis);
    }

    private static volatile SkillCooldownSetter skillCooldownSetter;

    @FunctionalInterface
    public interface PotionEffectEndDispatcher {
        void dispatch(BehaviorContext context, LivingEntity affected, PotionEffectType type);
    }

    private static volatile PotionEffectEndDispatcher potionEffectEndDispatcher;

    /** Bridges mechanic-driven removals to the managed trigger dispatcher. */
    public static void potionEffectEndDispatcher(PotionEffectEndDispatcher dispatcher) {
        potionEffectEndDispatcher = dispatcher;
    }

    private static void notifyPotionEffectEnd(BehaviorContext context, LivingEntity affected,
                                              PotionEffectType type) {
        PotionEffectEndDispatcher dispatcher = potionEffectEndDispatcher;
        if (dispatcher != null && affected != null && type != null) {
            dispatcher.dispatch(context, affected, type);
        }
    }

    /** Optional integration-only recipient diagnostics; null in normal runtime. */
    public static void audienceDiagnostic(java.util.function.Consumer<String> diagnostic) {
        audienceDiagnostic = diagnostic;
    }

    public static void audienceMobSelector(AudienceMobSelector selector) {
        audienceMobSelector = selector;
    }

    public static void executionAudienceWriter(ExecutionAudienceWriter writer) {
        executionAudienceWriter = writer;
    }

    public static void updateExecutionAudience(ModelInstance instance, Collection<UUID> players) {
        if (instance == null) return;
        ExecutionAudienceWriter writer = executionAudienceWriter;
        if (writer == null) instance.setExecutionAudience(players);
        else writer.write(instance, players);
    }

    public static void skillCooldownSetter(SkillCooldownSetter setter) {
        skillCooldownSetter = setter;
    }

    public static void damagePolicy(DamagePolicy policy) {
        damagePolicy = policy;
    }

    public static void damageCause(java.util.function.BiFunction<BehaviorContext, LivingEntity, String> resolver) {
        damageCause = resolver;
    }

    /** Overrides item lookup for integrations and Paper-free unit tests. */
    public static void itemResolver(java.util.function.Function<String, ItemStack> resolver) {
        itemResolver = resolver;
    }

    private static volatile java.util.function.Predicate<String> claimedItem = id -> false;

    public static void claimedItemPredicate(java.util.function.Predicate<String> predicate) {
        claimedItem = predicate == null ? id -> false : predicate;
    }

    /** Bridges IsSkill to the host's loaded named-skill registry. */
    public static void skillLookup(java.util.function.Predicate<String> lookup) {
        skillLookup = lookup;
    }

    /** Connects HasCurrency to an optional economy provider; empty means unavailable. */
    public static void currencyBalance(
            java.util.function.Function<Player, java.util.OptionalDouble> lookup) {
        currencyBalance = lookup;
    }

    public static void factionSetter(FactionSetter setter) {
        factionSetter = setter;
    }

    public static void globalScoreLookup(GlobalScoreLookup lookup) {
        globalScoreLookup = lookup;
    }

    public static void scoreLookup(ScoreLookup lookup) {
        scoreLookup = lookup;
    }

    /** Bridges HasDialogOpen to the host dialogue runner without retaining the service. */
    public static void dialogueLookup(java.util.function.IntPredicate lookup) {
        dialogueLookup = lookup;
    }

    public static void cinematicLookup(CinematicLookup lookup) {
        cinematicLookup = lookup;
    }

    public static void managedEntityLookup(ManagedEntityLookup lookup) {
        managedEntityLookup = lookup;
    }

    public static void auraRegistry(AuraRegistry registry) {
        auraRegistry = registry;
    }

    public static void threatLookup(ThreatLookup l) {
        threatLookup = l;
    }

    /** 供条件/动作取本实例的仇恨表；未接线时返回 null，调用方自行判空。 */
    static dev.helstera.ai.ThreatTable threatOf(BehaviorContext ctx) {
        var l = threatLookup;
        if (l == null || !hasInstance(ctx)) return null;
        return l.of(id(ctx));
    }

    /**
     * 当前目标在本实例仇恨表中的威胁值。
     *
     * <p>返回 {@link Double#NaN} 而非 0 表示「查不到」：NaN 与任何阈值比较
     * 都返回 false，天然满足「无表 / 无目标时判 false」，而返回 0 会让
     * {@code threat-below 100} 在无表时错误成立。</p>
     */
    private static double targetThreat(BehaviorContext ctx) {
        var t = threatOf(ctx);
        var tgt = ctx.targetEntity();
        if (t == null || tgt.isEmpty()) return Double.NaN;
        return t.threatOf(tgt.get().getUniqueId());
    }

    /**
     * 连杀计数表。
     *
     * <p>条件/动作工厂是静态表，拿不到注入实例，因此由 {@code SkillTriggers}
     * 在构造时把自己的 {@code KillStreak} 注入到这里。与 {@link #host} 同一理由：
     * 未注入时条件一律判 false，绝不抛异常——配置里写了却静默不放行，
     * 表现与「没写这条条件」完全一致。</p>
     */
    private static volatile KillStreak streaks;
    private static volatile PlayerKillCount playerKillCount;

    public static void killStreak(KillStreak s) {
        streaks = s;
    }

    public static void playerKillCount(PlayerKillCount counter) {
        playerKillCount = counter;
    }

    /** 连杀计数表；未注入时返回 null，调用方必须自行判空。 */
    static KillStreak streaks() {
        return streaks;
    }

    /**
     * 本实例击杀者的当前连杀数；表未注入、无击杀者或已超窗均返回 0。
     *
     * <p>未注入返回 0 而不是 NPE：条件在决策链里求值，抛异常会打断整轮决策，
     * 症状是「这个 Boss 的技能全都不动了」，比条件不成立严重得多。</p>
     */
    private static int streak(BehaviorContext ctx) {
        var t = streaks();
        if (t == null || !hasInstance(ctx)) return 0;
        return t.streakOfInstance(id(ctx), System.nanoTime());
    }

    public static void host(Plugin plugin) {
        host = plugin;
    }

    public static void despawner(Despawner d) {
        despawner = d;
    }

    /**
     * Clears plugin-owned hooks and static runtime state during plugin shutdown.
     *
     * <p>These hooks are intentionally static because catalog factories are
     * shared, but leaving them installed across reloads retains the old plugin
     * classloader and can route a later action into stale services.</p>
     */
    public static void resetRuntimeHooks() {
        host = null;
        despawner = null;
        threatLookup = null;
        pinRegistry = null;
        regionLookup = null;
        localDifficultyLookup = null;
        damagePolicy = null;
        damageCause = null;
        itemResolver = null;
        skillLookup = null;
        currencyBalance = null;
        factionSetter = null;
        globalScoreLookup = null;
        scoreLookup = null;
        dialogueLookup = null;
        cinematicLookup = null;
        managedEntityLookup = null;
        audienceMobSelector = null;
        executionAudienceWriter = null;
        auraRegistry = null;
        audienceDiagnostic = null;
        skillCooldownSetter = null;
        potionEffectEndDispatcher = null;
        streaks = null;
        playerKillCount = null;
        LAST_SIGNALS.clear();
        ENDED_PROJECTILES.clear();
        VARS.clear();
        SCOPED_VALUES.clear();
        SCORES.clear();
        STANCES.clear();
        GLOBAL_CD.clear();
    }

    /** 供定时类动作使用；宿主未注入时返回 null，调用方必须自行判空。 */
    static Plugin host() {
        return host;
    }

    // ------------------------------------------------------------------
    // 实例级状态（变量 / 分数 / 全局冷却）
    // ------------------------------------------------------------------

    /**
     * 实例级变量表，供 var-set / var-math / has-var / variable-gte 使用。
     *
     * <p>按实例 id 分桶，销毁时经 {@link #forget(int)} 回收——不回收的话，
     * 变量表随累计生成量无界增长，是那种跑一夜后内存缓慢上涨的典型来源。</p>
     */
    private static final Map<Integer, Map<String, Double>> VARS = new ConcurrentHashMap<>();

    /** Numeric variables stored in caster, target, world, or global scope. */
    private static final Map<String, Map<String, Object>> SCOPED_VALUES = new ConcurrentHashMap<>();

    private static final Map<Integer, Map<String, Integer>> SCORES = new ConcurrentHashMap<>();
    /** Most recent signal delivered to each managed instance. */
    private static final Map<Integer, String> LAST_SIGNALS = new ConcurrentHashMap<>();
    /** Projectiles that have reached a Bukkit ProjectileHitEvent. */
    private static final Map<java.util.UUID, Long> ENDED_PROJECTILES = new ConcurrentHashMap<>();
    /** Mythic-style stance per managed instance; separate from the AI FSM state. */
    private static final Map<Integer, String> STANCES = new ConcurrentHashMap<>();

    /** 全局冷却：名称 -> 解除冷却的时刻。 */
    private static final Map<String, Long> GLOBAL_CD = new ConcurrentHashMap<>();

    /** 实例销毁时回收其变量与分数。 */
    public static void forget(int instanceId) {
        VARS.remove(instanceId);
        SCORES.remove(instanceId);
        STANCES.remove(instanceId);
        LAST_SIGNALS.remove(instanceId);
        String prefix = instanceId + ":";
        GLOBAL_CD.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** Records a signal after the bus delivers it to a managed instance. */
    public static void rememberSignal(int instanceId, String signal) {
        if (signal == null || signal.isBlank()) return;
        LAST_SIGNALS.put(instanceId, signal.trim());
    }

    private static String lastSignal(BehaviorContext ctx) {
        return hasInstance(ctx) ? LAST_SIGNALS.get(id(ctx)) : null;
    }

    public static void rememberProjectileEnded(Entity projectile) {
        if (projectile != null) ENDED_PROJECTILES.put(projectile.getUniqueId(), System.nanoTime());
    }

    private static boolean projectileEnded(BehaviorContext ctx) {
        Entity trigger = ctx == null ? null : ctx.trigger().orElse(null);
        if (!(trigger instanceof org.bukkit.entity.Projectile projectile)) return false;
        return !projectile.isValid() || projectile.isDead()
                || ENDED_PROJECTILES.containsKey(projectile.getUniqueId());
    }

    /** Clears persistent scoped values associated with an entity identity. */
    public static void forgetEntity(org.bukkit.entity.Entity entity) {
        if (entity == null) return;
        String identity;
        try { identity = entity.getUniqueId().toString(); }
        catch (RuntimeException ignored) { identity = Integer.toString(entity.getEntityId()); }
        SCOPED_VALUES.remove("caster:" + identity);
        SCOPED_VALUES.remove("target:" + identity);
    }

    /** 变量表快照，供 /helstera debug 查看。 */
    public static Map<Integer, Map<String, Double>> variableSnapshot() {
        return Map.copyOf(VARS);
    }

    // ------------------------------------------------------------------
    // 参数解析辅助（与 SkillCatalog 私有同名方法语义一致）
    // ------------------------------------------------------------------

    /**
     * 解析数值参数。
     *
     * <p>参数存在但不是合法数值时<b>抛异常</b>，由 {@code bindAction} 在装载期
     * 接住并记入 warnings，最终显示在 {@code /helstera check} 里。
     *
     * <p>原先静默返回默认值：作者写 {@code set-scale abc} 时技能照常执行但用
     * 默认值，日志与体检全无提示。</p>
     */
    private static double num(List<String> a, int i, double def) {
        return SpecArgs.num(a, i, def);
    }

    private static double nonNegativeRadius(List<String> a, int i, double def) {
        double value = num(a, i, def);
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("radius must be finite and non-negative: " + value);
        }
        return value;
    }

    private static int nonNegativeCount(List<String> a, int i, int def) {
        double value = num(a, i, def);
        if (!Double.isFinite(value) || value < 0 || value != Math.rint(value)
                || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("count must be a non-negative integer: " + value);
        }
        return (int) value;
    }

    /** Resolves placeholder-backed numeric arguments when the action executes. */
    private static java.util.function.ToDoubleFunction<BehaviorContext> dynamicNum(
            List<String> args, int index, double def) {
        if (args == null || args.size() <= index || args.get(index) == null
                || args.get(index).isBlank()) return ctx -> def;
        String raw = args.get(index).trim();
        if (raw.indexOf('<') >= 0) {
            return ctx -> Placeholders.resolveNumber(raw, ctx, def);
        }
        double parsed = num(args, index, def);
        return ctx -> parsed;
    }

    private static java.util.function.ToDoubleFunction<BehaviorContext> dynamicKeyedNum(
            List<String> args, int index, double def, String... names) {
        String keyed = keyedArg(args, names);
        return keyed == null ? dynamicNum(args, index, def) : dynamicNum(List.of(keyed), 0, def);
    }

    private static String str(List<String> a, int i, String def) {
        return a != null && a.size() > i && !a.get(i).isBlank() ? a.get(i).trim() : def;
    }

    /**
     * 解析布尔参数。
     *
     * <p><b>修掉两个既有问题</b>：</p>
     * <ol>
     *   <li>原先写作
     *       {@code a != null && a.size() > i && Boolean.parseBoolean(a.get(i).trim())}，
     *       参数缺失时短路返回 {@code false}，<b>无视传入的 def</b>。
     *       而本文件里有 3 处声明默认值 {@code true}（invisible / glowing 等）——
     *       作者写 {@code invisible} 不带参数，本意是「隐身」，实际却让它
     *       <b>显形</b>了。同场景在 {@code SkillCatalog} 里行为还相反
     *       （那边正确返回 def=true），两个目录类语义不一致。</li>
     *   <li>{@link Boolean#parseBoolean} 对除 "true" 外的任何输入都返回
     *       false，于是 {@code loop yes} / {@code loop 1} / {@code loop on}
     *       全被当成「没开」，毫无提示。</li>
     * </ol>
     */
    private static boolean bool(List<String> a, int i, boolean def) {
        return SpecArgs.bool(a, i, def);
    }

    private static Comparison comparison(List<String> args, int index, String defaultOperator) {
        String raw = str(args, index, "");
        if (raw.equals(">") || raw.equals(">=") || raw.equals("<")
                || raw.equals("<=") || raw.equals("=") || raw.equals("==")
                || raw.equals("!=")) {
            raw += str(args, index + 1, "");
        }
        String operator = defaultOperator;
        for (String candidate : List.of(">=", "<=", "!=", "==", ">", "<", "=")) {
            if (raw.startsWith(candidate)) {
                operator = candidate;
                raw = raw.substring(candidate.length()).trim();
                break;
            }
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return new Comparison(operator, value);
        } catch (NumberFormatException ignored) {
            return new Comparison(operator, Double.NaN);
        }
    }

    private static boolean compare(double actual, Comparison comparison) {
        if (comparison == null || !Double.isFinite(actual) || !Double.isFinite(comparison.value())) {
            return false;
        }
        return switch (comparison.operator()) {
            case ">" -> actual > comparison.value();
            case ">=" -> actual >= comparison.value();
            case "<" -> actual < comparison.value();
            case "!=" -> Math.abs(actual - comparison.value()) > 1.0e-9;
            case "=", "==" -> Math.abs(actual - comparison.value()) <= 1.0e-9;
            default -> actual <= comparison.value();
        };
    }

    private record NumberRange(double min, double max,
                               boolean minInclusive, boolean maxInclusive) {
        boolean contains(double value) {
            if (!Double.isFinite(value)) return false;
            boolean above = minInclusive ? value >= min : value > min;
            boolean below = maxInclusive ? value <= max : value < max;
            return above && below;
        }
    }

    private static NumberRange numberRange(List<String> args, int index) {
        String raw = str(args, index, "");
        if (raw.isBlank()) return null;
        if ((raw.equals(">") || raw.equals(">=") || raw.equals("<") || raw.equals("<="))
                && args != null && args.size() > index + 1) {
            raw += str(args, index + 1, "");
        }
        try {
            if (raw.startsWith(">=")) {
                return new NumberRange(Double.parseDouble(raw.substring(2).trim()),
                        Double.POSITIVE_INFINITY, true, false);
            }
            if (raw.startsWith(">")) {
                return new NumberRange(Double.parseDouble(raw.substring(1).trim()),
                        Double.POSITIVE_INFINITY, false, false);
            }
            if (raw.startsWith("<=")) {
                return new NumberRange(Double.NEGATIVE_INFINITY,
                        Double.parseDouble(raw.substring(2).trim()), false, true);
            }
            if (raw.startsWith("<")) {
                return new NumberRange(Double.NEGATIVE_INFINITY,
                        Double.parseDouble(raw.substring(1).trim()), false, false);
            }
            int to = raw.toLowerCase(Locale.ROOT).indexOf("to");
            if (to > 0) {
                return new NumberRange(Double.parseDouble(raw.substring(0, to).trim()),
                        Double.parseDouble(raw.substring(to + 2).trim()), true, true);
            }
            int separator = raw.indexOf('-', 1);
            if (separator > 0) {
                return new NumberRange(Double.parseDouble(raw.substring(0, separator).trim()),
                        Double.parseDouble(raw.substring(separator + 1).trim()), true, true);
            }
            double exact = Double.parseDouble(raw);
            return new NumberRange(exact, exact, true, true);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static LivingEntity conditionEntity(BehaviorContext ctx) {
        if (ctx == null) return null;
        LivingEntity target = ctx.targetEntity().orElse(null);
        if (target != null) return target;
        if (!ctx.instanceValid()) return null;
        Entity entity = ctx.instance().baseEntity().orElse(null);
        return entity instanceof LivingEntity living ? living : null;
    }

    private static boolean inCombat(BehaviorContext ctx) {
        LivingEntity entity = conditionEntity(ctx);
        if (entity == null) return false;
        if (ctx.instanceValid() && ctx.instance().baseEntity().orElse(null) == entity
                && ctx.instance() instanceof dev.helstera.runtime.instance.ModelInstanceImpl instance
                && instance.aiController instanceof dev.helstera.ai.AiController controller) {
            return controller.isInCombat();
        }
        if (!(entity instanceof org.bukkit.entity.Mob mob)) return false;
        try {
            LivingEntity target = mob.getTarget();
            return target != null && target.isValid() && !target.isDead();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static LivingEntity targetOnlyEntity(BehaviorContext ctx) {
        return ctx == null ? null : ctx.targetEntity().orElse(null);
    }

    private static Entity casterEntity(BehaviorContext ctx) {
        if (ctx == null || !ctx.instanceValid()) return null;
        return ctx.instance().baseEntity().orElse(null);
    }

    private static boolean entityNameMatches(LivingEntity entity, String expected) {
        if (entity == null || expected == null || expected.isBlank()) return false;
        String customName = entity.getCustomName();
        if (customName != null) return customName.equals(expected);
        String name = entity.getName();
        return name != null && name.equals(expected);
    }

    private static boolean entityColorMatches(LivingEntity entity, String expected) {
        if (entity == null || expected == null || expected.isBlank()) return false;
        String wanted = expected.trim().toUpperCase(Locale.ROOT);
        try {
            if (entity instanceof org.bukkit.material.Colorable colorable) {
                return colorable.getColor() != null
                        && colorable.getColor().name().equalsIgnoreCase(wanted);
            }
            if (entity instanceof org.bukkit.entity.Cat cat) {
                return cat.getCatType() != null && cat.getCatType().name().equalsIgnoreCase(wanted);
            }
            if (entity instanceof org.bukkit.entity.Parrot parrot) {
                return parrot.getVariant() != null && parrot.getVariant().name().equalsIgnoreCase(wanted);
            }
            if (entity instanceof org.bukkit.entity.Horse horse) {
                return horse.getColor() != null && horse.getColor().name().equalsIgnoreCase(wanted);
            }
            if (entity instanceof org.bukkit.entity.Llama llama) {
                return llama.getColor() != null && llama.getColor().name().equalsIgnoreCase(wanted);
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private static boolean triggerItemMatches(BehaviorContext ctx, String wanted) {
        if (ctx == null || wanted == null || wanted.isBlank()) return false;
        Entity trigger = ctx.trigger().orElse(null);
        if (!(trigger instanceof org.bukkit.entity.Item item)) return false;
        ItemStack stack = item.getItemStack();
        if (stack == null || stack.getType() == null) return false;
        String actual = stack.getType().name();
        for (String raw : wanted.split(",")) {
            String expected = raw.trim().toUpperCase(Locale.ROOT);
            if (expected.startsWith("MINECRAFT:")) expected = expected.substring("MINECRAFT:".length());
            if (actual.equals(expected)) return true;
        }
        return false;
    }

    private static boolean entityItemMatches(BehaviorContext ctx, String wanted) {
        if (ctx == null || wanted == null || wanted.isBlank()) return false;
        Entity source = ctx.trigger().orElse(null);
        if (!(source instanceof org.bukkit.entity.Item item)) return false;
        ItemStack stack = item.getItemStack();
        if (stack == null || stack.getType() == null) return false;
        String actual = stack.getType().name();
        for (String raw : wanted.split(",")) {
            String expected = raw.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
            if (actual.equals(expected)) return true;
        }
        return false;
    }

    private static boolean entityItemIsSimilar(BehaviorContext ctx, String itemName) {
        if (ctx == null || itemName == null || itemName.isBlank()) return false;
        Entity source = ctx.trigger().orElse(null);
        if (!(source instanceof org.bukkit.entity.Item item)) return false;
        ItemStack actual = item.getItemStack();
        ItemStack expected = resolveItem(itemName, 1);
        if (actual == null || expected == null) return false;
        try {
            return actual.isSimilar(expected);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Reads Mythic's GLOBAL fake-player score, retaining the old adapter key as a fallback. */
    private static Integer globalScore(String objective) {
        GlobalScoreLookup lookup = globalScoreLookup;
        if (lookup != null) {
            Integer actual = lookup.score(objective, "GLOBAL");
            return actual != null ? actual : lookup.score(objective, "__GLOBAL__");
        }
        try {
            var manager = org.bukkit.Bukkit.getScoreboardManager();
            var board = manager == null ? null : manager.getMainScoreboard();
            var objectiveEntry = board == null ? null : board.getObjective(objective);
            if (objectiveEntry == null) return null;
            var standard = objectiveEntry.getScore("GLOBAL");
            if (standard.isScoreSet()) return standard.getScore();
            var legacy = objectiveEntry.getScore("__GLOBAL__");
            return legacy.isScoreSet() ? legacy.getScore() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Checks a target player's explicit inventory slot against an item template. */
    private static boolean itemIsSimilar(BehaviorContext ctx, String itemName, String slotName) {
        LivingEntity entity = conditionEntity(ctx);
        if (!(entity instanceof Player player) || itemName == null || itemName.isBlank()) return false;
        ItemStack expected = resolveItem(itemName, 1);
        ItemStack actual = inventoryItem(player, slotName);
        if (expected == null || actual == null) return false;
        try {
            return actual.isSimilar(expected);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static ItemStack inventoryItem(Player player, String slotName) {
        if (player == null) return null;
        String slot = slotName == null || slotName.isBlank()
                ? "HAND" : slotName.trim().toUpperCase(Locale.ROOT);
        try {
            if (slot.matches("\\d+")) {
                int index = Integer.parseInt(slot);
                return index >= 0 && index <= 35 ? player.getInventory().getItem(index) : null;
            }
            return switch (slot.replace('-', '_')) {
                case "HAND", "MAIN_HAND", "MAINHAND" -> player.getInventory().getItemInMainHand();
                case "OFF_HAND", "OFFHAND" -> player.getInventory().getItemInOffHand();
                case "HEAD", "HELMET" -> player.getInventory().getHelmet();
                case "CHEST", "CHESTPLATE" -> player.getInventory().getChestplate();
                case "LEGS", "LEGGINGS" -> player.getInventory().getLeggings();
                case "FEET", "BOOTS" -> player.getInventory().getBoots();
                default -> null;
            };
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void setInventoryItem(Player player, String slotName, ItemStack item) {
        if (player == null) return;
        String slot = slotName == null || slotName.isBlank()
                ? "HAND" : slotName.trim().toUpperCase(Locale.ROOT);
        try {
            if (slot.matches("\\d+")) {
                int index = Integer.parseInt(slot);
                if (index >= 0 && index <= 35) player.getInventory().setItem(index, item);
                return;
            }
            switch (slot.replace('-', '_')) {
                case "HAND", "MAIN_HAND", "MAINHAND" -> player.getInventory().setItemInMainHand(item);
                case "OFF_HAND", "OFFHAND" -> player.getInventory().setItemInOffHand(item);
                case "HEAD", "HELMET" -> player.getInventory().setHelmet(item);
                case "CHEST", "CHESTPLATE" -> player.getInventory().setChestplate(item);
                case "LEGS", "LEGGINGS" -> player.getInventory().setLeggings(item);
                case "FEET", "BOOTS" -> player.getInventory().setBoots(item);
                default -> { }
            }
        } catch (RuntimeException ignored) {
            SkillFaults.swallow("SkillExtras#setInventoryItem");
        }
    }

    private static boolean triggerBlockMatches(BehaviorContext ctx, String wanted) {
        if (ctx == null || wanted == null || wanted.isBlank()) return false;
        String state = ctx.state();
        if (!"on-block-break".equalsIgnoreCase(state)
                && !"on-block-place".equalsIgnoreCase(state)) return false;
        Location origin = ctx.origin().orElse(null);
        if (origin == null || origin.getWorld() == null) return false;
        String actual = origin.getBlock().getType().name();
        for (String raw : wanted.split(",")) {
            String expected = raw.trim().toUpperCase(Locale.ROOT);
            if (expected.startsWith("MINECRAFT:")) expected = expected.substring("MINECRAFT:".length());
            if (actual.equals(expected)) return true;
        }
        return false;
    }

    private static boolean lineOfSightFromOrigin(BehaviorContext ctx) {
        LivingEntity target = targetOnlyEntity(ctx);
        Location origin = ctx == null ? null : ctx.origin().orElse(null);
        if (target == null || origin == null || origin.getWorld() == null) return false;
        Location eye = target.getEyeLocation();
        if (eye == null || eye.getWorld() == null || !origin.getWorld().equals(eye.getWorld())) return false;
        org.bukkit.util.Vector delta = eye.toVector().subtract(origin.toVector());
        double distance = delta.length();
        if (!Double.isFinite(distance)) return false;
        if (distance <= 1.0e-6) return true;
        try {
            return origin.getWorld().rayTraceBlocks(origin, delta.normalize(), distance,
                    org.bukkit.FluidCollisionMode.NEVER, true) == null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean structureMatches(BehaviorContext ctx, String wanted) {
        Location location = conditionLocation(ctx);
        if (location == null || location.getWorld() == null || wanted == null || wanted.isBlank()) return false;
        try {
            for (String raw : wanted.split(",")) {
                String pattern = raw.trim().toLowerCase(Locale.ROOT);
                if (pattern.isBlank()) continue;
                if (!pattern.contains(":")) pattern = "minecraft:" + pattern;
                final String matcher = pattern;
                for (org.bukkit.generator.structure.Structure structure
                        : org.bukkit.Registry.STRUCTURE) {
                    String key = structure.getKey().toString().toLowerCase(Locale.ROOT);
                    boolean matches;
                    if (matcher.indexOf('*') >= 0) {
                        String regex = java.util.Arrays.stream(matcher.split("\\*", -1))
                                .map(java.util.regex.Pattern::quote)
                                .collect(java.util.stream.Collectors.joining(".*"));
                        matches = key.matches(regex);
                    } else {
                        matches = key.equals(matcher);
                    }
                    if (matches && location.getWorld().hasStructureAt(location, structure)) return true;
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private static boolean lookingAt(BehaviorContext ctx, String wanted, double distance, boolean blockMode) {
        if (!(targetOnlyEntity(ctx) instanceof Player player)
                || wanted == null || wanted.isBlank() || !Double.isFinite(distance) || distance <= 0) {
            return false;
        }
        try {
            if (blockMode) {
                Material expected = Material.matchMaterial(wanted);
                var hit = player.rayTraceBlocks(distance, org.bukkit.FluidCollisionMode.NEVER);
                return expected != null && hit != null && hit.getHitBlock() != null
                        && hit.getHitBlock().getType() == expected;
            }
            org.bukkit.entity.EntityType expected = org.bukkit.entity.EntityType.valueOf(
                    wanted.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""));
            var hit = player.rayTraceEntities((int) Math.ceil(distance), true);
            return hit != null && hit.getHitEntity() != null
                    && hit.getHitEntity().getType() == expected;
        } catch (IllegalArgumentException ignored) {
            return false;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isManagedEntity(BehaviorContext ctx) {
        LivingEntity entity = conditionEntity(ctx);
        if (entity == null) return false;
        ManagedEntityLookup lookup = managedEntityLookup;
        if (lookup != null) return lookup.isManaged(entity);
        return ctx != null && ctx.instanceValid()
                && ctx.instance().baseEntity().map(base -> base == entity).orElse(false);
    }

    private static boolean pluginRunning(String wanted) {
        if (wanted == null || wanted.isBlank()) return false;
        try {
            for (org.bukkit.plugin.Plugin plugin : org.bukkit.Bukkit.getPluginManager().getPlugins()) {
                if (plugin.getName().equalsIgnoreCase(wanted.trim())) return plugin.isEnabled();
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#pluginRunning");
        }
        return false;
    }

    private static org.bukkit.enchantments.Enchantment resolveEnchantment(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        String key = value.toLowerCase(Locale.ROOT);
        int separator = key.indexOf(':');
        if (separator >= 0) key = key.substring(separator + 1);
        org.bukkit.enchantments.Enchantment enchantment =
                org.bukkit.enchantments.Enchantment.getByName(value.toUpperCase(Locale.ROOT));
        if (enchantment != null) return enchantment;
        try {
            return org.bukkit.enchantments.Enchantment.getByKey(
                    org.bukkit.NamespacedKey.minecraft(key));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static List<ItemStack> equippedItems(EntityEquipment equipment) {
        return java.util.Arrays.asList(equipment.getItemInMainHand(), equipment.getItemInOffHand(),
                equipment.getHelmet(), equipment.getChestplate(), equipment.getLeggings(),
                equipment.getBoots());
    }

    private static NumberRange rangeValue(String raw) {
        return raw == null || raw.isBlank() ? null : numberRange(List.of(raw), 0);
    }

    private static String localDifficultyExpression(List<String> args) {
        if (args == null || args.isEmpty()) return null;
        String value = null;
        boolean named = false;
        for (int i = 0; i < args.size(); i++) {
            String raw = args.get(i);
            if (raw == null || raw.isBlank()) return null;
            raw = raw.trim();
            if (i == 0 && isComparisonToken(raw) && args.size() > 1) {
                String next = args.get(++i);
                if (next == null || next.isBlank() || next.indexOf('=') >= 0) return null;
                raw += next.trim();
            }
            int equals = raw.startsWith(">") || raw.startsWith("<") ? -1 : raw.indexOf('=');
            if (equals >= 0) {
                if (equals == 0) return null;
                String key = raw.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                if (!Set.of("difficulty", "diff", "d").contains(key) || named || value != null) {
                    return null;
                }
                value = raw.substring(equals + 1).trim();
                if (value.isBlank()) return null;
                named = true;
            } else {
                if (named || value != null) return null;
                value = raw;
            }
        }
        return value;
    }

    private static boolean isComparisonToken(String raw) {
        return raw.equals(">") || raw.equals(">=") || raw.equals("<") || raw.equals("<=");
    }

    private static boolean validLocalDifficultyExpression(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String value = raw.trim();
        try {
            if (value.startsWith(">=") || value.startsWith("<=")
                    || value.startsWith(">") || value.startsWith("<")) {
                String number = value.replaceFirst("^(>=|<=|>|<)", "").trim();
                return finite(number);
            }
            int to = value.toLowerCase(Locale.ROOT).indexOf("to");
            if (to > 0) {
                double lower = Double.parseDouble(value.substring(0, to).trim());
                double upper = Double.parseDouble(value.substring(to + 2).trim());
                return Double.isFinite(lower) && Double.isFinite(upper) && lower <= upper;
            }
            int separator = value.indexOf('-', 1);
            if (separator > 0) {
                double lower = Double.parseDouble(value.substring(0, separator).trim());
                double upper = Double.parseDouble(value.substring(separator + 1).trim());
                return Double.isFinite(lower) && Double.isFinite(upper) && lower <= upper;
            }
            return finite(value);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean finite(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            return Double.isFinite(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static NumberRange trackedLocationRange(List<String> args) {
        String named = null;
        String positional = null;
        for (String raw : args == null ? List.<String>of() : args) {
            int equals = raw == null ? -1 : raw.indexOf('=');
            if (equals > 0) {
                String key = raw.substring(0, equals).trim();
                if ((!key.equalsIgnoreCase("distance") && !key.equalsIgnoreCase("d")) || named != null) {
                    throw new IllegalArgumentException("distance-from-tracked-location accepts one distance range");
                }
                named = raw.substring(equals + 1).trim();
            } else if (positional == null) {
                positional = raw;
            } else {
                throw new IllegalArgumentException("distance-from-tracked-location accepts one distance range");
            }
        }
        if (named != null && positional != null) {
            throw new IllegalArgumentException("distance-from-tracked-location accepts one distance range");
        }
        String expression = named == null ? positional : named;
        NumberRange range = rangeValue(expression);
        if (range == null || Double.isNaN(range.min()) || Double.isNaN(range.max())
                || range.min() == Double.POSITIVE_INFINITY || range.max() == Double.NEGATIVE_INFINITY
                || range.min() > range.max()
                || range.max() < 0
                || range.min() < 0 && range.min() != Double.NEGATIVE_INFINITY
                || range.min() == Double.NEGATIVE_INFINITY && range.max() == Double.POSITIVE_INFINITY) {
            throw new IllegalArgumentException("distance-from-tracked-location requires a finite distance range");
        }
        return range;
    }

    private static boolean keyedBoolean(List<String> args, int index, boolean fallback,
                                        String... names) {
        String raw = keyedArg(args, names);
        return raw == null ? bool(args, index, fallback) : bool(List.of(raw), 0, fallback);
    }

    private static java.util.UUID ownerId(Entity entity) {
        if (entity == null) return null;
        java.util.UUID id = dev.helstera.api.behavior.MobRelations.ownerId(entity);
        if (id != null) return id;
        return entity instanceof org.bukkit.entity.Tameable tameable
                ? tameable.getOwnerUniqueId() : null;
    }

    private static Entity resolvedOwner(Entity entity) {
        if (entity == null) return null;
        Entity owner = dev.helstera.api.behavior.MobRelations.owner(entity);
        if (owner != null) return owner;
        if (entity instanceof org.bukkit.entity.Tameable tameable
                && tameable.getOwner() instanceof Player player) return player;
        return null;
    }

    private static boolean boundingBoxesOverlap(BehaviorContext ctx, double forwardOffset) {
        if (ctx == null || !ctx.instanceValid() || !Double.isFinite(forwardOffset)) return false;
        Entity caster = ctx.instance().baseEntity().orElse(null);
        Entity target = targetOnlyEntity(ctx);
        if (caster == null || target == null || caster.getWorld() != target.getWorld()) return false;
        try {
            org.bukkit.util.BoundingBox casterBox = caster.getBoundingBox().clone();
            if (forwardOffset != 0.0) {
                org.bukkit.util.Vector direction = caster.getLocation().getDirection();
                direction.setY(0);
                if (direction.lengthSquared() > 1.0e-9) {
                    casterBox.shift(direction.normalize().multiply(forwardOffset));
                }
            }
            return casterBox.overlaps(target.getBoundingBox());
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#boundingBoxesOverlap");
            return false;
        }
    }

    private static boolean fieldOfView(BehaviorContext ctx, double angle, double rotation) {
        if (ctx == null || !ctx.instanceValid() || !Double.isFinite(angle)
                || !Double.isFinite(rotation)) return false;
        Entity caster = ctx.instance().baseEntity().orElse(null);
        Entity target = targetOnlyEntity(ctx);
        if (caster == null || target == null) return false;
        try {
            Location from = caster.getLocation();
            Location to = target.getLocation();
            if (from == null || to == null) return false;
            if (from.getWorld() != null && to.getWorld() != null
                    && !from.getWorld().equals(to.getWorld())) return false;
            org.bukkit.util.Vector facing = from.getDirection();
            org.bukkit.util.Vector delta = to.toVector().subtract(from.toVector());
            if (facing.lengthSquared() < 1.0e-9 || delta.lengthSquared() < 1.0e-9) return false;
            facing.normalize().rotateAroundY(Math.toRadians(rotation));
            double cone = Math.max(0, Math.min(180, angle));
            return facing.dot(delta.normalize()) >= Math.cos(Math.toRadians(cone / 2.0));
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#fieldOfView");
            return false;
        }
    }

    private static boolean coordinateDifference(BehaviorContext ctx, char axis, NumberRange range) {
        if (ctx == null || range == null || !ctx.instanceValid()) return false;
        LivingEntity target = targetOnlyEntity(ctx);
        Location caster = selfLoc(ctx);
        if (target == null || caster == null || caster.getWorld() == null) return false;
        Location targetLocation = target.getLocation();
        if (targetLocation == null || targetLocation.getWorld() == null
                || !caster.getWorld().equals(targetLocation.getWorld())) return false;
        double difference = switch (axis) {
            case 'x' -> targetLocation.getX() - caster.getX();
            case 'y' -> targetLocation.getY() - caster.getY();
            case 'z' -> targetLocation.getZ() - caster.getZ();
            default -> Double.NaN;
        };
        return range.contains(difference);
    }

    private static double coordinateArg(List<String> args, int index, double fallback, String... names) {
        String raw = keyedArg(args, names);
        if (raw == null && args != null && args.size() > index && !args.get(index).contains("=")) {
            raw = args.get(index);
        }
        try { return raw == null ? fallback : Double.parseDouble(raw.trim()); }
        catch (NumberFormatException ignored) { return Double.NaN; }
    }

    private static boolean onBlockMatches(LivingEntity entity, String wanted) {
        if (entity == null || wanted == null || wanted.isBlank()) return false;
        Location location = entity.getLocation();
        if (location.getWorld() == null) return false;
        Location below = location.clone().add(0, -1, 0);
        return matchesBlockType(location.getWorld().getBlockAt(below).getType(), wanted);
    }

    private static Location conditionLocation(BehaviorContext ctx) {
        LivingEntity target = targetOnlyEntity(ctx);
        return target == null ? selfLoc(ctx) : target.getLocation();
    }

    private static double localDifficultyAt(Location location) {
        if (location == null || location.getWorld() == null) return Double.NaN;
        LocalDifficultyLookup lookup = localDifficultyLookup;
        if (lookup != null) {
            try {
                Double value = lookup.difficulty(location);
                return value != null && Double.isFinite(value) ? value : Double.NaN;
            } catch (Throwable ignored) {
                return Double.NaN;
            }
        }
        try {
            Object worldHandle = location.getWorld().getClass().getMethod("getHandle")
                    .invoke(location.getWorld());
            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            Object blockPos = blockPosClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            Object difficulty = worldHandle.getClass()
                    .getMethod("getCurrentDifficultyAt", blockPosClass)
                    .invoke(worldHandle, blockPos);
            Class<?> difficultyClass = Class.forName("net.minecraft.world.DifficultyInstance");
            Object effective = difficultyClass.getMethod("getEffectiveDifficulty").invoke(difficulty);
            if (!(effective instanceof Number number)) return Double.NaN;
            double value = number.doubleValue();
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    private static boolean boundsMatch(BehaviorContext ctx, String shape,
                                       String firstRaw, String secondRaw, String centerRaw,
                                       String radiusRaw, String heightRaw, boolean relative) {
        Location subject = conditionLocation(ctx);
        if (subject == null || subject.getWorld() == null || shape == null) return false;
        String normalized = shape.trim().toLowerCase(Locale.ROOT);
        try {
            if (normalized.equals("cuboid")) {
                Location first = boundsLocation(firstRaw, ctx, relative);
                Location second = boundsLocation(secondRaw, ctx, relative);
                if (!sameWorld(subject, first) || !sameWorld(subject, second)) return false;
                double minX = Math.min(first.getX(), second.getX());
                double maxX = Math.max(first.getX(), second.getX());
                double minY = Math.min(first.getY(), second.getY());
                double maxY = Math.max(first.getY(), second.getY());
                double minZ = Math.min(first.getZ(), second.getZ());
                double maxZ = Math.max(first.getZ(), second.getZ());
                return subject.getX() >= minX && subject.getX() <= maxX
                        && subject.getY() >= minY && subject.getY() <= maxY
                        && subject.getZ() >= minZ && subject.getZ() <= maxZ;
            }
            Location center = boundsLocation(centerRaw, ctx, false);
            if (!sameWorld(subject, center)) return false;
            double radius = Double.parseDouble(radiusRaw == null ? "NaN" : radiusRaw.trim());
            if (!Double.isFinite(radius) || radius < 0) return false;
            double dx = subject.getX() - center.getX();
            double dz = subject.getZ() - center.getZ();
            if (normalized.equals("sphere")) {
                double dy = subject.getY() - center.getY();
                return dx * dx + dy * dy + dz * dz <= radius * radius;
            }
            if (!normalized.equals("cylinder")) return false;
            double height = Double.parseDouble(heightRaw == null ? "NaN" : heightRaw.trim());
            return Double.isFinite(height) && height >= 0
                    && dx * dx + dz * dz <= radius * radius
                    && subject.getY() >= center.getY()
                    && subject.getY() <= center.getY() + height;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static Location boundsLocation(String raw, BehaviorContext ctx, boolean relative) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        if (value.startsWith("@")) {
            return dev.helstera.api.behavior.Targeters.resolveLocation(value, ctx).orElse(null);
        }
        String[] parts = value.split("\\s*,\\s*");
        int offset = parts.length == 4 ? 1 : 0;
        if (parts.length != 3 && parts.length != 4) return null;
        double x = Double.parseDouble(parts[offset]);
        double y = Double.parseDouble(parts[offset + 1]);
        double z = Double.parseDouble(parts[offset + 2]);
        Location base = ctx == null ? null : ctx.origin().orElse(null);
        org.bukkit.World world = base == null ? null : base.getWorld();
        if (parts.length == 4) {
            org.bukkit.World named = org.bukkit.Bukkit.getWorld(parts[0].trim());
            if (named == null && (world == null || !world.getName().equalsIgnoreCase(parts[0].trim()))) {
                return null;
            }
            world = named == null ? world : named;
        }
        if (world == null) return null;
        if (relative) {
            Location caster = ctx == null ? null : selfLoc(ctx);
            if (caster == null || caster.getWorld() == null || caster.getWorld() != world) return null;
            x += caster.getX();
            y += caster.getY();
            z += caster.getZ();
        }
        return new Location(world, x, y, z);
    }

    private static boolean sameWorld(Location first, Location second) {
        return first != null && second != null && first.getWorld() != null
                && second.getWorld() != null && first.getWorld().equals(second.getWorld());
    }

    private static boolean blockTypeMatches(Location location, String wanted) {
        if (location == null || location.getWorld() == null || wanted == null || wanted.isBlank()) {
            return false;
        }
        return matchesBlockType(location.getBlock().getType(), wanted);
    }

    private static boolean matchesBlockType(Material actual, String wanted) {
        Set<Material> materials = resolveBlockTypes(wanted);
        return actual != null && materials != null && materials.contains(actual);
    }

    private static Set<Material> resolveBlockTypes(String wanted) {
        if (wanted == null || wanted.isBlank()) return null;
        Set<Material> materials = new java.util.HashSet<>();
        for (String raw : wanted.split(",", -1)) {
            String selector = raw.trim().toUpperCase(Locale.ROOT);
            if (selector.isBlank()) return null;
            if (selector.startsWith("#")) {
                try {
                    org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(
                            selector.substring(1).toLowerCase(Locale.ROOT));
                    if (key == null) return null;
                    org.bukkit.Tag<Material> tag = org.bukkit.Bukkit.getTag(
                            org.bukkit.Tag.REGISTRY_BLOCKS, key, Material.class);
                    if (tag == null) return null;
                    materials.addAll(tag.getValues());
                } catch (Throwable ignored) {
                    SkillFaults.swallow("SkillExtras#resolveBlockTypes");
                    return null;
                }
                continue;
            }
            if (selector.startsWith("MINECRAFT:")) selector = selector.substring("MINECRAFT:".length());
            else if (selector.contains(":")) return null;
            if (selector.indexOf('*') >= 0) {
                for (Material material : Material.values()) {
                    if (matchesGlob(material.name(), selector)) materials.add(material);
                }
                continue;
            }
            try {
                materials.add(Material.valueOf(selector));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return materials;
    }

    private static boolean matchesGlob(String value, String pattern) {
        int valueIndex = 0;
        int patternIndex = 0;
        int starIndex = -1;
        int retryIndex = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == value.charAt(valueIndex)) {
                valueIndex++;
                patternIndex++;
            } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                starIndex = patternIndex++;
                retryIndex = valueIndex;
            } else if (starIndex >= 0) {
                patternIndex = starIndex + 1;
                valueIndex = ++retryIndex;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') patternIndex++;
        return patternIndex == pattern.length();
    }

    /** Counts selected Bukkit materials inside a spherical block radius. */
    private static int countBlocksInRadius(Location center, double radius, String wanted) {
        if (center == null || center.getWorld() == null || wanted == null || wanted.isBlank()
                || !Double.isFinite(radius) || radius < 0 || radius > 64) return -1;
        Set<Material> materials = resolveBlockTypes(wanted);
        if (materials == null) return -1;
        int minX = (int) Math.floor(center.getX() - radius);
        int maxX = (int) Math.floor(center.getX() + radius);
        int minY = Math.max(center.getWorld().getMinHeight(),
                (int) Math.floor(center.getY() - radius));
        int maxY = Math.min(center.getWorld().getMaxHeight() - 1,
                (int) Math.floor(center.getY() + radius));
        int minZ = (int) Math.floor(center.getZ() - radius);
        int maxZ = (int) Math.floor(center.getZ() + radius);
        double radiusSquared = radius * radius + 1.0e-9;
        int count = 0;
        try {
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        double dx = x + 0.5 - center.getX();
                        double dy = y + 0.5 - center.getY();
                        double dz = z + 0.5 - center.getZ();
                        if (dx * dx + dy * dy + dz * dz > radiusSquared) continue;
                        if (materials.contains(center.getWorld().getBlockAt(x, y, z).getType())) count++;
                    }
                }
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#countBlocksInRadius");
            return -1;
        }
        return count;
    }

    /** Returns the target block's farmland data, never treating water as farmland. */
    private static Farmland farmlandAt(Location location) {
        if (location == null || location.getWorld() == null) return null;
        try {
            return location.getBlock().getBlockData() instanceof Farmland farmland ? farmland : null;
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#farmlandAt");
            return null;
        }
    }

    private static boolean biomeTypeMatches(Location location, String wanted, boolean exact) {
        if (location == null || location.getWorld() == null || wanted == null || wanted.isBlank()) return false;
        try {
            String actual = location.getBlock().getBiome().getKey().getKey().toLowerCase(Locale.ROOT);
            for (String raw : wanted.split(",")) {
                String candidate = raw.trim().toLowerCase(Locale.ROOT);
                if (candidate.isEmpty()) continue;
                if (exact ? actual.equals(candidate) : matchesBiomeCategory(actual, candidate)) return true;
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#biomeTypeMatches");
        }
        return false;
    }

    private static boolean matchesBiomeCategory(String biome, String category) {
        return switch (category) {
            case "none" -> false;
            case "taiga" -> biome.contains("taiga");
            case "extreme_hills" -> biome.contains("hills") || biome.equals("extreme_hills");
            case "jungle" -> biome.contains("jungle");
            case "mesa" -> biome.contains("badlands") || biome.contains("mesa");
            case "plains" -> biome.contains("plains");
            case "savanna" -> biome.contains("savanna");
            case "icy" -> biome.contains("snow") || biome.contains("frozen") || biome.contains("ice_spikes");
            case "the_end" -> biome.equals("the_end") || biome.startsWith("end_");
            case "beach" -> biome.contains("beach");
            case "forest" -> biome.contains("forest") || biome.equals("pale_garden");
            case "ocean" -> biome.contains("ocean");
            case "desert" -> biome.contains("desert");
            case "river" -> biome.contains("river");
            case "swamp" -> biome.contains("swamp");
            case "mushroom" -> biome.contains("mushroom");
            case "nether" -> biome.contains("nether") || biome.equals("soul_sand_valley")
                    || biome.equals("crimson_forest") || biome.equals("warped_forest")
                    || biome.equals("basalt_deltas");
            case "underground" -> biome.equals("deep_dark") || biome.equals("dripstone_caves")
                    || biome.equals("lush_caves");
            case "mountain" -> biome.contains("hills") || biome.contains("peaks")
                    || biome.equals("meadow") || biome.equals("grove") || biome.equals("snowy_slopes");
            default -> false;
        };
    }

    private static boolean biomeMatches(Location location, String wanted, boolean exact) {
        if (location == null || location.getWorld() == null || wanted == null || wanted.isBlank()) return false;
        try {
            org.bukkit.NamespacedKey actual = location.getBlock().getBiome().getKey();
            if (actual == null) return false;
            String actualNamespace = actual.getNamespace();
            String actualKey = actual.getKey();
            for (String raw : wanted.split(",")) {
                String candidate = raw.trim().toLowerCase(Locale.ROOT);
                if (candidate.isEmpty()) continue;
                int separator = candidate.indexOf(':');
                String namespace = separator < 0 ? "minecraft" : candidate.substring(0, separator);
                String key = separator < 0 ? candidate : candidate.substring(separator + 1);
                if (namespace.isBlank() || key.isBlank()
                        || (separator >= 0 && candidate.indexOf(':', separator + 1) >= 0)) continue;
                if (!actualNamespace.equals(namespace)) continue;
                if (exact ? actualKey.equals(key) : actualKey.contains(key)) return true;
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#biomeMatches");
        }
        return false;
    }

    private static boolean isUndeadEntity(LivingEntity entity) {
        if (entity == null || entity.getType() == null) return false;
        return Set.of("ZOMBIE", "HUSK", "DROWNED", "ZOMBIFIED_PIGLIN",
                "SKELETON", "STRAY", "WITHER_SKELETON", "WITHER",
                "PHANTOM", "ZOGLIN", "PIGLIN", "PIGLIN_BRUTE",
                "ZOMBIE_VILLAGER", "GIANT").contains(entity.getType().name());
    }

    private static boolean isSaddledEntity(LivingEntity entity) {
        if (!(entity instanceof org.bukkit.entity.AbstractHorse horse)
                || horse.getInventory() == null) return false;
        ItemStack saddle = horse.getInventory().getSaddle();
        return saddle != null && !saddle.getType().isAir();
    }

    private static boolean isShearedEntity(LivingEntity entity) {
        if (entity instanceof org.bukkit.entity.Sheep sheep) return sheep.isSheared();
        if (entity instanceof org.bukkit.entity.Bogged bogged) return bogged.isSheared();
        return entity instanceof org.bukkit.entity.Snowman snowman && snowman.isDerp();
    }

    private static int mobSize(LivingEntity entity) {
        if (entity instanceof org.bukkit.entity.Slime slime) return slime.getSize();
        if (entity instanceof org.bukkit.entity.Phantom phantom) return phantom.getSize();
        return -1;
    }

    private static boolean hasOffhand(LivingEntity entity, String wanted) {
        if (entity == null || entity.getEquipment() == null) return false;
        ItemStack item = entity.getEquipment().getItemInOffHand();
        if (item == null || item.getType() == null || item.getType().isAir()) return false;
        return wanted == null || wanted.isBlank()
                || item.getType().name().equalsIgnoreCase(wanted.trim());
    }

    private static boolean compareValues(BehaviorContext ctx, String leftRaw, String rightRaw,
                                         String operatorRaw, String typeRaw) {
        String left = Placeholders.resolve(leftRaw, ctx);
        String right = Placeholders.resolve(rightRaw, ctx);
        if (left == null || right == null) return false;
        String operator = normalizeValueOperator(operatorRaw);
        if (operator == null) return false;
        String type = typeRaw == null ? "" : typeRaw.trim().toUpperCase(Locale.ROOT);
        try {
            int ordering;
            if (type.isBlank() || type.equals("INTEGER") || type.equals("INT")) {
                try {
                    ordering = Long.compare(Long.parseLong(left.trim()), Long.parseLong(right.trim()));
                    return compareOrdering(ordering, operator);
                } catch (NumberFormatException ignored) {
                    if (!type.isBlank()) return false;
                }
            }
            if (type.isBlank() || type.equals("DOUBLE") || type.equals("FLOAT")
                    || type.equals("NUMBER")) {
                try {
                    double leftNumber = Double.parseDouble(left.trim());
                    double rightNumber = Double.parseDouble(right.trim());
                    if (!Double.isFinite(leftNumber) || !Double.isFinite(rightNumber)) return false;
                    ordering = Double.compare(leftNumber, rightNumber);
                    return compareOrdering(ordering, operator);
                } catch (NumberFormatException ignored) {
                    if (!type.isBlank()) return false;
                }
            }
            if (type.equals("BOOLEAN")) {
                if (!left.equalsIgnoreCase("true") && !left.equalsIgnoreCase("false")) return false;
                if (!right.equalsIgnoreCase("true") && !right.equalsIgnoreCase("false")) return false;
                ordering = Boolean.compare(Boolean.parseBoolean(left), Boolean.parseBoolean(right));
                return compareOrdering(ordering, operator);
            }
            if (type.isBlank() || type.equals("STRING")) {
                ordering = left.compareTo(right);
                return compareOrdering(ordering, operator);
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private static String normalizeValueOperator(String raw) {
        if (raw == null) return "==";
        String value = raw.trim().toUpperCase(Locale.ROOT).replace('_', ' ');
        return switch (value) {
            case "=", "==", "EQUALS", "EQUAL", "EQ", "EQL" -> "==";
            case "!=", "NOT EQUAL", "NOT EQUALS", "NOTEQUALS", "NOTEQUAL", "NE", "NEQ", "NEQL" -> "!=";
            case ">", "GREATERTHAN", "GREATER THAN", "GTR", "GT" -> ">";
            case "<", "LESSTHAN", "LESS THAN", "LTR", "LT" -> "<";
            case ">=", "GREATERTHANOREQUAL", "GREATERTHANOREQUALS",
                    "GREATER THAN OR EQUAL", "GREATER THAN OR EQUALS", "GTEO", "GTE" -> ">=";
            case "<=", "LESSTHANOREQUAL", "LESSTHANOREQUALS",
                    "LESS THAN OR EQUAL", "LESS THAN OR EQUALS", "LTEO", "LTE" -> "<=";
            default -> null;
        };
    }

    private static boolean compareOrdering(int ordering, String operator) {
        return switch (operator) {
            case "==" -> ordering == 0;
            case "!=" -> ordering != 0;
            case ">" -> ordering > 0;
            case "<" -> ordering < 0;
            case ">=" -> ordering >= 0;
            case "<=" -> ordering <= 0;
            default -> false;
        };
    }

    private record Comparison(String operator, double value) {
    }

    // ------------------------------------------------------------------
    // 条件
    // ------------------------------------------------------------------

    public static Map<String, SkillCatalog.ConditionFactory> conditions() {
        return Map.ofEntries(
                // ---- 环境 / 姿态 ----

                Map.entry("health", (SkillCatalog.ConditionFactory) a -> {
                    String raw = keyedArg(a, "health", "h", "amount", "a");
                    Comparison comparison = raw == null
                            ? comparison(a, 0, "<=")
                            : comparison(List.of(raw), 0, "<=");
                    if (!Double.isFinite(comparison.value())) {
                        throw new IllegalArgumentException("health comparator is not numeric");
                    }
                    boolean includeAbsorption = keyedBoolean(a, 1, false,
                            "includeabsorption", "include-absorption", "ia");
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        if (entity == null) return false;
                        double health = entity.getHealth()
                                + (includeAbsorption ? entity.getAbsorptionAmount() : 0.0);
                        return compare(health, comparison);
                    };
                }),
                Map.entry("health-percent", (SkillCatalog.ConditionFactory) a -> {
                    String raw = keyedArg(a, "percent", "p", "healthpercent", "hp");
                    Comparison comparison = raw == null
                            ? comparison(a, 0, "<=")
                            : comparison(List.of(raw), 0, "<=");
                    if (!Double.isFinite(comparison.value())) {
                        throw new IllegalArgumentException("health-percent comparator is not numeric");
                    }
                    boolean includeAbsorption = keyedBoolean(a, 1, false,
                            "includeabsorption", "include-absorption", "ia");
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        if (entity == null || entity.getMaxHealth() <= 0) return false;
                        double health = entity.getHealth()
                                + (includeAbsorption ? entity.getAbsorptionAmount() : 0.0);
                        double percent = health / entity.getMaxHealth() * 100.0;
                        return compare(percent, comparison);
                    };
                }),
                Map.entry("health-below", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<=");
                    if (!Double.isFinite(comparison.value())) {
                        throw new IllegalArgumentException("health-below comparator is not numeric");
                    }
                    boolean includeAbsorption = keyedBoolean(a, 1, false,
                            "includeabsorption", "include-absorption", "ia");
                    return ctx -> {
                        if (!includeAbsorption) return ctx != null && compare(ctx.healthRatio(), comparison);
                        LivingEntity entity = conditionEntity(ctx);
                        if (entity == null || entity.getMaxHealth() <= 0) return false;
                        double health = entity.getHealth()
                                + (includeAbsorption ? entity.getAbsorptionAmount() : 0.0);
                        return compare(health / entity.getMaxHealth(), comparison);
                    };
                }),
                Map.entry("health-above", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    if (!Double.isFinite(comparison.value())) {
                        throw new IllegalArgumentException("health-above comparator is not numeric");
                    }
                    boolean includeAbsorption = keyedBoolean(a, 1, false,
                            "includeabsorption", "include-absorption", "ia");
                    return ctx -> {
                        if (!includeAbsorption) return ctx != null && compare(ctx.healthRatio(), comparison);
                        LivingEntity entity = conditionEntity(ctx);
                        if (entity == null || entity.getMaxHealth() <= 0) return false;
                        double health = entity.getHealth()
                                + (includeAbsorption ? entity.getAbsorptionAmount() : 0.0);
                        return compare(health / entity.getMaxHealth(), comparison);
                    };
                }),
                Map.entry("level", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> {
                        if (range == null || ctx == null || !ctx.instanceValid()) return false;
                        if (!(ctx.instance() instanceof dev.helstera.runtime.instance.ModelInstanceImpl instance)) {
                            return false;
                        }
                        int level = instance.level > 0 ? instance.level : 1;
                        return range.contains(level);
                    };
                }),
                Map.entry("is-frozen", (SkillCatalog.ConditionFactory) a -> {
                    if (a != null && !a.isEmpty()) throw new IllegalArgumentException("is-frozen takes no arguments");
                    return ctx -> conditionEntity(ctx) != null && conditionEntity(ctx).isFrozen();
                }),
                Map.entry("last-signal", (SkillCatalog.ConditionFactory) a -> {
                    if (a != null && (a.size() > 1 || (a.size() == 1 && a.get(0).isBlank()))) {
                        throw new IllegalArgumentException("last-signal expects one nonblank signal");
                    }
                    String expected = keyedArg(a, "signal", "s");
                    if (expected == null) expected = str(a, 0, "DEFAULT");
                    final String wanted = expected;
                    return ctx -> {
                        String actual = lastSignal(ctx);
                        return actual != null && actual.equals(wanted);
                    };
                }),
                Map.entry("projectile-has-ended", (SkillCatalog.ConditionFactory) a -> {
                    if (a != null && !a.isEmpty()) throw new IllegalArgumentException("projectile-has-ended takes no arguments");
                    return SkillExtras::projectileEnded;
                }),
                Map.entry("lunar-phase", (SkillCatalog.ConditionFactory) a -> {
                    java.util.Set<Integer> phases = new java.util.HashSet<>();
                    String raw = a != null && !a.isEmpty() ? a.get(0).trim() : "0";
                    boolean valid = raw != null && !raw.isBlank();
                    for (String token : raw == null ? new String[0] : raw.split(",", -1)) {
                        if (token.isBlank()) {
                            valid = false;
                            continue;
                        }
                        try {
                            int phase = Integer.parseInt(token.trim());
                            if (phase < 0 || phase > 7) {
                                valid = false;
                                continue;
                            }
                            phases.add(phase);
                        } catch (NumberFormatException ignored) {
                            valid = false;
                        }
                    }
                    java.util.Set<Integer> expected = java.util.Set.copyOf(phases);
                    boolean validExpected = valid && !expected.isEmpty();
                    return ctx -> {
                        Location location = conditionLocation(ctx);
                        if (location == null || location.getWorld() == null || !validExpected) return false;
                        long day = Math.floorDiv(location.getWorld().getFullTime(), 24_000L);
                        int phase = (int) Math.floorMod(day, 8);
                        return expected.contains(phase);
                    };
                }),
                Map.entry("absorption", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">0");
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        return entity != null && compare(entity.getAbsorptionAmount(), comparison);
                    };
                }),
                Map.entry("distance", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<=");
                    return ctx -> ctx != null && ctx.distanceToTarget() >= 0
                            && compare(ctx.distanceToTarget(), comparison);
                }),

                Map.entry("on-ground", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    LivingEntity living = selfLiving(ctx).orElse(null);
                    if (living != null) return living.isOnGround();
                    Location l = selfLoc(ctx);
                    if (l == null) return false;
                    try {
                        return l.getBlock().getRelative(0, -1, 0).getType().isSolid();
                    } catch (Throwable t) {
                        return false;
                    }
                }),
                Map.entry("in-water", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    if (ctx != null && ctx.instanceValid()) {
                        Entity base = ctx.instance().baseEntity().orElse(null);
                        if (base != null) return base.isInWater();
                    }
                    Location l = selfLoc(ctx);
                    if (l == null) return false;
                    try {
                        Material t = l.getBlock().getType();
                        return t == Material.WATER || t == Material.BUBBLE_COLUMN;
                    } catch (Throwable t) {
                        return false;
                    }
                }),
                Map.entry("in-lava", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    if (ctx != null && ctx.instanceValid()) {
                        Entity base = ctx.instance().baseEntity().orElse(null);
                        if (base != null) return base.isInLava();
                    }
                    Location l = selfLoc(ctx);
                    if (l == null) return false;
                    try {
                        return l.getBlock().getType() == Material.LAVA;
                    } catch (Throwable t) {
                        return false;
                    }
                }),
                Map.entry("standing", (SkillCatalog.ConditionFactory) a -> {
                    double tol = num(a, 0, 0.1);
                    return ctx -> selfLoc(ctx) != null
                            && Math.abs(selfLoc(ctx).getY() % 1.0) < tol;
                }),
                Map.entry("light-level-below", (SkillCatalog.ConditionFactory) a -> {
                    int level = (int) num(a, 0, 8);
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        if (l == null) return false;
                        try {
                            return l.getBlock().getLightLevel() < level;
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("light-level-above", (SkillCatalog.ConditionFactory) a -> {
                    int level = (int) num(a, 0, 8);
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        if (l == null) return false;
                        try {
                            return l.getBlock().getLightLevel() > level;
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("light-level", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "=");
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        if (l == null) return false;
                        try {
                            return compare(l.getBlock().getLightLevel(), comparison);
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("sky-light-level", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> {
                        Location location = selfLoc(ctx);
                        return range != null && location != null
                                && range.contains(location.getBlock().getLightFromSky());
                    };
                }),
                Map.entry("light-level-from-blocks", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> {
                        Location location = selfLoc(ctx);
                        return range != null && location != null
                                && range.contains(location.getBlock().getLightFromBlocks());
                    };
                }),
                Map.entry("world-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "");
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        return l != null && l.getWorld() != null
                                && want.equalsIgnoreCase(l.getWorld().getName());
                    };
                }),
                Map.entry("biome-is", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "minecraft:plains");
                    boolean exact = bool(a, 1, true);
                    return ctx -> biomeMatches(conditionLocation(ctx), wanted, exact);
                }),
                Map.entry("time-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "day");
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        if (l == null) return false;
                        try {
                            return matchesTime(want, l.getWorld().getTime());
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("world-time", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> {
                        Location location = selfLoc(ctx);
                        return range != null && location != null && location.getWorld() != null
                                && range.contains(Math.floorMod(location.getWorld().getTime(), 24_000L));
                    };
                }),
                Map.entry("weather-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "clear");
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        if (l == null) return false;
                        try {
                            return matchesWeather(want, l.getWorld().hasStorm(), l.getWorld().isThundering());
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("local-difficulty", (SkillCatalog.ConditionFactory) a -> {
                    String expression = localDifficultyExpression(a);
                    if (!validLocalDifficultyExpression(expression)) {
                        throw new IllegalArgumentException(
                                "local-difficulty requires a finite comparator or ordered range");
                    }
                    NumberRange range = numberRange(List.of(expression), 0);
                    if (range == null) {
                        throw new IllegalArgumentException("local-difficulty range is invalid: " + expression);
                    }
                    return ctx -> range.contains(localDifficultyAt(conditionLocation(ctx)));
                }),
                // Common Mythic aliases with direct Bukkit semantics.  Keep
                // thunder separate from ordinary rain so a `raining` gate does
                // not unexpectedly reject a storm.
                Map.entry("night", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    Location location = selfLoc(ctx);
                    if (location == null || location.getWorld() == null) return false;
                    return matchesTime("night", location.getWorld().getTime());
                }),
                Map.entry("raining", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    Location location = selfLoc(ctx);
                    return location != null && location.getWorld() != null
                            && location.getWorld().hasStorm();
                }),
                Map.entry("thundering", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    Location location = selfLoc(ctx);
                    return location != null && location.getWorld() != null
                            && location.getWorld().isThundering();
                }),
                Map.entry("snowing", snowingCondition()),
                Map.entry("is-snowing", snowingCondition()),
                Map.entry("day", timeAlias("day")),
                Map.entry("daytime", timeAlias("daytime")),
                Map.entry("dawn", timeAlias("dawn")),
                Map.entry("dusk", timeAlias("dusk")),
                Map.entry("sunset", timeAlias("sunset")),
                Map.entry("morning", timeAlias("morning")),
                Map.entry("noon", timeAlias("noon")),
                Map.entry("midnight", timeAlias("midnight")),
                Map.entry("nighttime", timeAlias("nighttime")),
                Map.entry("sun", weatherAlias("sun")),
                Map.entry("sunny", weatherAlias("sunny")),
                Map.entry("clear", weatherAlias("clear")),
                Map.entry("rain", weatherAlias("rain")),
                Map.entry("storm", weatherAlias("storm")),
                Map.entry("thunder", weatherAlias("thunder")),
                Map.entry("thunderstorm", weatherAlias("thunderstorm")),
                Map.entry("falling", (SkillCatalog.ConditionFactory) a -> ctx ->
                        selfLiving(ctx).map(entity -> !entity.isOnGround()
                                && entity.getVelocity().getY() < -0.05).orElse(false)),
                Map.entry("drowning", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = a == null || a.isEmpty()
                            ? new Comparison("<=", 0)
                            : comparison(a, 0, "<=");
                    if (!Double.isFinite(comparison.value())) {
                        throw new IllegalArgumentException("drowning comparator is not numeric");
                    }
                    return ctx -> selfLiving(ctx)
                            .map(entity -> compare(entity.getRemainingAir(), comparison))
                            .orElse(false);
                }),
                Map.entry("has-auras", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    String wanted = str(a, 0, "");
                    if (wanted.startsWith("effect=")) wanted = wanted.substring("effect=".length()).trim();
                    if (wanted.startsWith("type=")) wanted = wanted.substring("type=".length()).trim();
                    String expected = wanted;
                    LivingEntity entity = conditionEntity(ctx);
                    return entity != null && entity.getActivePotionEffects().stream().anyMatch(effect ->
                            expected.isBlank() || effect.getType().getKey().getKey()
                                    .equalsIgnoreCase(expected));
                }),

                // ---- 目标状态 ----

                Map.entry("is-cancelled", (SkillCatalog.ConditionFactory) a -> ctx ->
                        ctx != null && ctx.cancellableEvent()
                                .map(org.bukkit.event.Cancellable::isCancelled)
                                .orElse(false)),
                Map.entry("target-dead", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx.targetEntity().map(LivingEntity::isDead).orElse(false)),
                Map.entry("target-on-ground", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx.targetEntity().map(LivingEntity::isOnGround).orElse(false)),
                Map.entry("target-airborne", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx.targetEntity().map(e -> !e.isOnGround()).orElse(false)),
                Map.entry("target-invulnerable", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx.targetEntity().map(Entity::isInvulnerable).orElse(false)),
                Map.entry("target-health-below", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<=");
                    return ctx -> ctx.targetEntity()
                            .map(e -> e.getMaxHealth() > 0
                                    && compare(e.getHealth() / e.getMaxHealth() * 100.0, comparison))
                            .orElse(false);
                }),
                Map.entry("target-health-above", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> ctx.targetEntity()
                            .map(e -> e.getMaxHealth() > 0
                                    && compare(e.getHealth() / e.getMaxHealth() * 100.0, comparison))
                            .orElse(false);
                }),
                Map.entry("target-health", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<=");
                    return ctx -> ctx.targetEntity().map(e -> compare(e.getHealth(), comparison)).orElse(false);
                }),
                Map.entry("target-health-percent", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<=");
                    return ctx -> ctx.targetEntity()
                            .map(e -> e.getMaxHealth() > 0
                                    && compare(e.getHealth() / e.getMaxHealth() * 100.0, comparison))
                            .orElse(false);
                }),
                Map.entry("entity-type-is", (SkillCatalog.ConditionFactory) a -> {
                    EntityTypeSelector selector = EntityTypeSelector.parse(String.join(",", a))
                            .orElseThrow(() -> new IllegalArgumentException("invalid entity type selector"));
                    return ctx -> {
                        Entity entity = ctx == null ? null : ctx.targetEntity().orElse(null);
                        if (entity == null && ctx != null && ctx.instanceValid()) {
                            entity = ctx.instance().baseEntity().orElse(null);
                        }
                        return selector.matches(entity);
                    };
                }),
                Map.entry("height", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> {
                        Location location = selfLoc(ctx);
                        return range != null && location != null && range.contains(location.getY());
                    };
                }),
                Map.entry("altitude", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    double maxHeight = num(a, 1, 30);
                    return ctx -> {
                        Location location = conditionLocation(ctx);
                        if (range == null || location == null || location.getWorld() == null
                                || !Double.isFinite(maxHeight) || maxHeight < 0) return false;
                        try {
                            int surfaceY = location.getWorld().getHighestBlockAt(
                                    location.getBlockX(), location.getBlockZ()).getY();
                            double altitude = location.getY() - (surfaceY + 1.0);
                            return altitude <= maxHeight && range.contains(altitude);
                        } catch (Throwable ignored) {
                            return false;
                        }
                    };
                }),
                Map.entry("height-above", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">") ;
                    return ctx -> {
                        Location location = selfLoc(ctx);
                        return location != null && compare(location.getY(), comparison);
                    };
                }),
                Map.entry("height-below", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<");
                    return ctx -> {
                        Location location = selfLoc(ctx);
                        return location != null && compare(location.getY(), comparison);
                    };
                }),
                Map.entry("pitch", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        return range != null && entity != null
                                && range.contains(entity.getLocation().getPitch());
                    };
                }),
                Map.entry("yaw", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    boolean clamp = bool(a, 1, true);
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        if (range == null || entity == null) return false;
                        double yaw = entity.getLocation().getYaw();
                        if (clamp) yaw = ((yaw % 360.0) + 360.0) % 360.0;
                        return range.contains(yaw);
                    };
                }),
                Map.entry("on-block", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "STONE");
                    return ctx -> onBlockMatches(conditionEntity(ctx), wanted);
                }),
                Map.entry("moist", (SkillCatalog.ConditionFactory) a ->
                        ctx -> {
                            Farmland farmland = farmlandAt(conditionLocation(ctx));
                            return farmland != null && farmland.getMoisture() > 0;
                        }),
                Map.entry("moisture-level", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> {
                        Farmland farmland = farmlandAt(conditionLocation(ctx));
                        return range != null && farmland != null
                                && range.contains(farmland.getMoisture());
                    };
                }),
                Map.entry("block-type-is", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "DIRT");
                    return ctx -> blockTypeMatches(conditionLocation(ctx), wanted);
                }),
                Map.entry("block-type-in-radius", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "DIRT");
                    Comparison amount = a == null || a.size() <= 1
                            ? new Comparison(">", 0)
                            : comparison(a, 1, ">0");
                    double radius = num(a, 2, 8);
                    return ctx -> compare(countBlocksInRadius(conditionLocation(ctx), radius, wanted), amount);
                }),
                Map.entry("biome-type-is", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "ocean");
                    boolean exact = bool(a, 1, true);
                    return ctx -> biomeTypeMatches(conditionLocation(ctx), wanted, exact);
                }),
                Map.entry("name-is", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = keyedArg(a, "name", "n", "value", "v");
                    if (wanted == null) wanted = str(a, 0, "");
                    final String expected = wanted;
                    return ctx -> entityNameMatches(conditionEntity(ctx), expected);
                }),
                Map.entry("color", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = keyedArg(a, "color", "clr", "c");
                    if (wanted == null) wanted = str(a, 0, "WHITE");
                    final String expected = wanted;
                    return ctx -> entityColorMatches(conditionEntity(ctx), expected);
                }),
                Map.entry("spawn-reason", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = keyedArg(a, "reason", "r");
                    if (wanted == null) wanted = str(a, 0, "");
                    final org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason expected;
                    try {
                        expected = wanted.isBlank()
                                ? null
                                : org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.valueOf(
                                        wanted.trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {
                        return ctx -> false;
                    }
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        if (entity == null || expected == null) return false;
                        try {
                            return entity.getEntitySpawnReason() == expected;
                        } catch (RuntimeException ignored) {
                            return false;
                        }
                    };
                }),
                Map.entry("is-living", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) != null),
                Map.entry("is-player", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof Player),
                Map.entry("is-mob", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Mob),
                Map.entry("is-monster", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Monster),
                Map.entry("is-animal", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Animals),
                Map.entry("is-villager", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Villager),
                Map.entry("is-tameable", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Tameable),
                Map.entry("is-water-mob", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.WaterMob),
                Map.entry("is-zombie", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Zombie),
                Map.entry("aerial", (SkillCatalog.ConditionFactory) a ->
                        ctx -> {
                            LivingEntity entity = conditionEntity(ctx);
                            return entity != null && !entity.isOnGround();
                        }),
                Map.entry("is-enderman", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Enderman),
                Map.entry("is-raider", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Raider),
                Map.entry("is-raider-patrol-leader", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Raider raider
                                && raider.isPatrolLeader()),
                Map.entry("is-undead", (SkillCatalog.ConditionFactory) a ->
                        ctx -> isUndeadEntity(conditionEntity(ctx))),
                Map.entry("is-creature", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Creature),
                Map.entry("is-baby", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Ageable age
                                && !age.isAdult()),
                Map.entry("is-adult", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Ageable age
                                && age.isAdult()),
                Map.entry("angry", (SkillCatalog.ConditionFactory) a -> {
                    if (a != null && !a.isEmpty()) throw new IllegalArgumentException("angry takes no arguments");
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        if (entity instanceof org.bukkit.entity.Bee bee) return bee.getAnger() > 0;
                        if (entity instanceof org.bukkit.entity.Wolf wolf) return wolf.isAngry();
                        return entity instanceof org.bukkit.entity.Vex vex && vex.isCharging();
                    };
                }),
                Map.entry("pollinated", (SkillCatalog.ConditionFactory) a -> {
                    if (a != null && !a.isEmpty()) throw new IllegalArgumentException("pollinated takes no arguments");
                    return ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Bee bee && bee.hasNectar();
                }),
                Map.entry("mob-size", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> range != null && range.contains(mobSize(conditionEntity(ctx)));
                }),
                Map.entry("is-tamed", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Tameable tameable
                                && tameable.isTamed()),
                Map.entry("is-sheared", (SkillCatalog.ConditionFactory) a ->
                        ctx -> isShearedEntity(conditionEntity(ctx))),
                Map.entry("is-saddled", (SkillCatalog.ConditionFactory) a ->
                        ctx -> isSaddledEntity(conditionEntity(ctx))),
                Map.entry("is-powered", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Creeper creeper
                                && creeper.isPowered()),
                Map.entry("is-collidable", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) != null && conditionEntity(ctx).isCollidable()),
                Map.entry("has-offhand", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> hasOffhand(conditionEntity(ctx), wanted);
                }),
                Map.entry("is-op", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof Player player && player.isOp()),
                Map.entry("target-is-player", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof Player),
                Map.entry("is-caster", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return false;
                    Entity caster = ctx.instance().baseEntity().orElse(null);
                    Entity target = targetOnlyEntity(ctx);
                    return caster != null && target != null && caster.getUniqueId().equals(target.getUniqueId());
                }),
                Map.entry("target-is-op", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof Player player && player.isOp()),
                Map.entry("target-is-living", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) != null),
                Map.entry("target-is-mob", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof org.bukkit.entity.Mob),
                Map.entry("target-is-monster", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof org.bukkit.entity.Monster),
                Map.entry("target-is-animal", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof org.bukkit.entity.Animals),
                Map.entry("target-is-villager", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof org.bukkit.entity.Villager),
                Map.entry("target-is-tameable", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof org.bukkit.entity.Tameable),
                Map.entry("target-is-raider", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof org.bukkit.entity.Raider),
                Map.entry("target-is-undead", (SkillCatalog.ConditionFactory) a ->
                        ctx -> isUndeadEntity(targetOnlyEntity(ctx))),
                Map.entry("target-is-creature", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof org.bukkit.entity.Creature),
                Map.entry("target-is-baby", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof org.bukkit.entity.Ageable age && !age.isAdult()),
                Map.entry("target-is-adult", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof org.bukkit.entity.Ageable age && age.isAdult()),
                Map.entry("target-is-tamed", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof org.bukkit.entity.Tameable tameable
                                && tameable.isTamed()),
                Map.entry("target-is-sheared", (SkillCatalog.ConditionFactory) a ->
                        ctx -> isShearedEntity(targetOnlyEntity(ctx))),
                Map.entry("target-is-saddled", (SkillCatalog.ConditionFactory) a ->
                        ctx -> isSaddledEntity(targetOnlyEntity(ctx))),
                Map.entry("target-is-powered", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof org.bukkit.entity.Creeper creeper
                                && creeper.isPowered()),
                Map.entry("target-is-collidable", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) != null && targetOnlyEntity(ctx).isCollidable()),
                Map.entry("target-has-offhand", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> hasOffhand(targetOnlyEntity(ctx), wanted);
                }),
                Map.entry("has-owner", (SkillCatalog.ConditionFactory) a ->
                        ctx -> {
                            LivingEntity entity = conditionEntity(ctx);
                            return entity != null && (dev.helstera.api.behavior.MobRelations.hasOwner(entity)
                                    || entity instanceof org.bukkit.entity.Tameable tameable
                                    && tameable.getOwner() != null);
                        }),
                Map.entry("target-has-owner", (SkillCatalog.ConditionFactory) a ->
                        ctx -> {
                            Entity entity = targetOnlyEntity(ctx);
                            return entity != null && (dev.helstera.api.behavior.MobRelations.hasOwner(entity)
                                    || entity instanceof org.bukkit.entity.Tameable tameable
                                    && tameable.getOwner() != null);
                        }),
                Map.entry("owner-is-player", (SkillCatalog.ConditionFactory) a ->
                        ctx -> {
                            LivingEntity entity = conditionEntity(ctx);
                            if (entity == null) return false;
                            Entity owner = resolvedOwner(entity);
                            return owner instanceof Player;
                        }),
                Map.entry("owner-is-online", (SkillCatalog.ConditionFactory) a ->
                        ctx -> {
                            LivingEntity entity = conditionEntity(ctx);
                            if (entity == null) return false;
                            java.util.UUID id = ownerId(entity);
                            return id != null && org.bukkit.Bukkit.getPlayer(id) != null;
                        }),
                Map.entry("target-owner-is-player", (SkillCatalog.ConditionFactory) a ->
                        ctx -> {
                            Entity entity = targetOnlyEntity(ctx);
                            return entity != null && resolvedOwner(entity) instanceof Player;
                        }),
                Map.entry("target-owner-is-online", (SkillCatalog.ConditionFactory) a ->
                        ctx -> {
                            Entity entity = targetOnlyEntity(ctx);
                            if (entity == null) return false;
                            java.util.UUID id = ownerId(entity);
                            return id != null && org.bukkit.Bukkit.getPlayer(id) != null;
                        }),
                Map.entry("is-owner", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return false;
                    Entity caster = ctx.instance().baseEntity().orElse(null);
                    Entity target = targetOnlyEntity(ctx);
                    java.util.UUID owner = ownerId(caster);
                    return owner != null && target != null
                            && owner.equals(target.getUniqueId());
                }),
                Map.entry("has-tag", (SkillCatalog.ConditionFactory) a -> {
                    String tag = str(a, 0, "");
                    if (tag.startsWith("tag=")) tag = tag.substring(4);
                    else if (tag.startsWith("t=")) tag = tag.substring(2);
                    String expected = tag.trim();
                    return ctx -> !expected.isBlank() && conditionEntity(ctx) != null
                            && conditionEntity(ctx).getScoreboardTags().contains(expected);
                }),
                Map.entry("target-has-tag", (SkillCatalog.ConditionFactory) a -> {
                    String expected = scoreboardTag(a);
                    return ctx -> !expected.isBlank() && targetOnlyEntity(ctx) != null
                            && targetOnlyEntity(ctx).getScoreboardTags().contains(expected);
                }),
                Map.entry("has-parent", (SkillCatalog.ConditionFactory) a ->
                        ctx -> dev.helstera.api.behavior.MobRelations.hasParentRelation(conditionEntity(ctx))),
                Map.entry("is-parent", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return false;
                    Entity parent = ctx.targetEntity().orElse(null);
                    Entity caster = ctx.instance().baseEntity().orElse(null);
                    return parent != null && caster != null
                            && parent.getUniqueId().equals(dev.helstera.api.behavior.MobRelations.parentId(caster))
                            && parent.isValid() && !parent.isDead();
                }),
                Map.entry("is-child", (SkillCatalog.ConditionFactory) a -> {
                    return ctx -> {
                        if (ctx == null || !ctx.instanceValid()) return false;
                        Entity parent = ctx.instance().baseEntity().orElse(null);
                        Entity child = ctx.targetEntity().orElse(null);
                        return dev.helstera.api.behavior.MobRelations.isChild(parent, child);
                    };
                }),
                Map.entry("is-parent-alive", (SkillCatalog.ConditionFactory) a ->
                        ctx -> dev.helstera.api.behavior.MobRelations.isParentAlive(conditionEntity(ctx))),
                Map.entry("children", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = a == null || a.isEmpty()
                            ? new NumberRange(1, 1, true, true)
                            : numberRange(a, 0);
                    return ctx -> range != null && range.contains(countChildren(ctx));
                }),
                Map.entry("is-sibling", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return false;
                    Entity caster = ctx.instance().baseEntity().orElse(null);
                    Entity target = ctx.targetEntity().orElse(null);
                    return dev.helstera.api.behavior.MobRelations.areSiblings(caster, target);
                }),
                Map.entry("target-is-sneaking", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) != null && targetOnlyEntity(ctx).isSneaking()),
                Map.entry("target-has-ai", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx != null && ctx.targetEntity()
                                .map(entity -> entity instanceof org.bukkit.entity.Mob mob && mob.hasAI())
                                .orElse(false)),
                Map.entry("target-has-gravity", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx != null && ctx.targetEntity().map(Entity::hasGravity).orElse(false)),
                Map.entry("target-is-silent", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx != null && ctx.targetEntity().map(Entity::isSilent).orElse(false)),
                Map.entry("target-is-valid", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx != null && ctx.targetEntity().map(Entity::isValid).orElse(false)),
                Map.entry("target-is-alive", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx != null && ctx.targetEntity()
                                .map(entity -> entity.isValid() && !entity.isDead()).orElse(false)),
                Map.entry("mounted", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living && living.isInsideVehicle()),
                Map.entry("vehicle-is-dead", (SkillCatalog.ConditionFactory) a -> {
                    return ctx -> {
                        Entity caster = casterEntity(ctx);
                        return caster != null && caster.getVehicle() != null && caster.getVehicle().isDead();
                    };
                }),
                Map.entry("is-vehicle", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living
                                && !living.getPassengers().isEmpty()),
                Map.entry("target-within", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<=");
                    return ctx -> {
                        var distance = targetAiDistance(ctx);
                        return distance.isPresent() && compare(distance.getAsDouble(), comparison);
                    };
                }),
                Map.entry("target-not-within", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<=");
                    return ctx -> {
                        var distance = targetAiDistance(ctx);
                        return distance.isPresent() && !compare(distance.getAsDouble(), comparison);
                    };
                }),
                Map.entry("target-has-line-of-sight", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetAiLineOfSight(ctx).orElse(false)),
                Map.entry("target-not-in-line-of-sight", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetAiLineOfSight(ctx).map(found -> !found).orElse(false)),
                Map.entry("moving", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living
                                && Double.isFinite(living.getVelocity().lengthSquared())
                                && living.getVelocity().lengthSquared() > 0.0),
                Map.entry("has-passenger", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living
                                && !living.getPassengers().isEmpty()),
                Map.entry("target-is-vehicle", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) != null
                                && !targetOnlyEntity(ctx).getPassengers().isEmpty()),
                Map.entry("target-has-passenger", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) != null
                                && !targetOnlyEntity(ctx).getPassengers().isEmpty()),
                Map.entry("is-leashed", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living && living.isLeashed()),
                Map.entry("target-is-leashed", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof LivingEntity living && living.isLeashed()),
                Map.entry("target-is-sprinting", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof Player player && player.isSprinting()),
                Map.entry("target-is-flying", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof Player player && player.isFlying()),
                Map.entry("target-is-blocking", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof Player player && player.isBlocking()),
                Map.entry("target-is-gliding", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof LivingEntity living && living.isGliding()),
                Map.entry("target-is-swimming", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof LivingEntity living && living.isSwimming()),
                Map.entry("target-on-fire", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) != null && targetOnlyEntity(ctx).getFireTicks() > 0),
                Map.entry("target-in-air", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof LivingEntity living && !living.isOnGround()),
                Map.entry("target-in-water", (SkillCatalog.ConditionFactory) a -> {
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        if (target == null) return false;
                        try {
                            return target.isInWater();
                        } catch (RuntimeException ignored) {
                            return false;
                        }
                    };
                }),
                Map.entry("target-in-lava", (SkillCatalog.ConditionFactory) a -> {
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        if (target == null) return false;
                        try {
                            return target.isInLava();
                        } catch (RuntimeException ignored) {
                            return false;
                        }
                    };
                }),
                Map.entry("target-is-glowing", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) != null && targetOnlyEntity(ctx).isGlowing()),
                Map.entry("target-is-invisible", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) != null && targetOnlyEntity(ctx).isInvisible()),
                Map.entry("target-is-climbing", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof LivingEntity living && living.isClimbing()),
                Map.entry("target-is-frozen", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) != null && targetOnlyEntity(ctx).isFrozen()),
                Map.entry("target-is-blocked", (SkillCatalog.ConditionFactory) a -> {
                    double above = num(a, 0, 1.0);
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        if (target == null) return false;
                        try {
                            org.bukkit.Location location = target.getLocation();
                            if (location == null || location.getWorld() == null) return false;
                            org.bukkit.Location head = location.clone().add(0, above, 0);
                            return head.getWorld().getBlockAt(head).getType() != org.bukkit.Material.AIR;
                        } catch (RuntimeException ignored) {
                            return false;
                        }
                    };
                }),
                Map.entry("inside", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    Location location = conditionLocation(ctx);
                    if (location == null || location.getWorld() == null) return false;
                    try {
                        return !location.clone().add(0, 1, 0).getBlock().getType().isAir();
                    } catch (Throwable ignored) {
                        return false;
                    }
                }),
                Map.entry("outside", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    Location location = conditionLocation(ctx);
                    if (location == null || location.getWorld() == null) return false;
                    try {
                        return location.getWorld().getHighestBlockYAt(
                                location.getBlockX(), location.getBlockZ()) <= location.getBlockY();
                    } catch (Throwable ignored) {
                        return false;
                    }
                }),
                Map.entry("stance-is", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> ctx != null && !wanted.isBlank() && hasInstance(ctx)
                            && wanted.equalsIgnoreCase(STANCES.getOrDefault(id(ctx), ""));
                }),
                Map.entry("in-combat", (SkillCatalog.ConditionFactory) a ->
                        ctx -> inCombat(ctx)),
                Map.entry("is-in-audience", (SkillCatalog.ConditionFactory) a -> {
                    if (a != null && !a.isEmpty()) {
                        throw new IllegalArgumentException("is-in-audience takes no arguments");
                    }
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        if (!(entity instanceof Player player)) return false;
                        var audience = ctx.executionAudience().orElse(null);
                        return audience == null
                                || audience.contains(player.getUniqueId());
                    };
                }),
                Map.entry("target-faction-is", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> {
                        var entity = ctx.targetEntity().orElse(null);
                        var table = dev.helstera.api.behavior.Factions.table();
                        return entity != null && table != null && !wanted.isBlank()
                                && wanted.equalsIgnoreCase(table.factionOf(entity));
                    };
                }),
                Map.entry("target-faction-allied-to", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> {
                        var entity = ctx.targetEntity().orElse(null);
                        var table = dev.helstera.api.behavior.Factions.table();
                        if (entity == null || table == null || wanted.isBlank()) return false;
                        return table.alliedFactions(table.factionOf(entity), wanted);
                    };
                }),
                Map.entry("target-faction-hostile-to", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> {
                        var entity = ctx.targetEntity().orElse(null);
                        var table = dev.helstera.api.behavior.Factions.table();
                        if (entity == null || table == null || wanted.isBlank()) return false;
                        String actual = table.factionOf(entity);
                        return actual != null && !table.alliedFactions(actual, wanted);
                    };
                }),
                Map.entry("same-faction", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return false;
                    Entity caster = ctx.instance().baseEntity().orElse(null);
                    Entity target = targetOnlyEntity(ctx);
                    String casterFaction = dev.helstera.api.behavior.Factions.factionOf(caster);
                    String targetFaction = dev.helstera.api.behavior.Factions.factionOf(target);
                    return casterFaction != null && targetFaction != null
                            && casterFaction.equalsIgnoreCase(targetFaction);
                }),
                Map.entry("different-faction", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return false;
                    Entity caster = ctx.instance().baseEntity().orElse(null);
                    Entity target = targetOnlyEntity(ctx);
                    String casterFaction = dev.helstera.api.behavior.Factions.factionOf(caster);
                    String targetFaction = dev.helstera.api.behavior.Factions.factionOf(target);
                    return casterFaction != null && targetFaction != null
                            && !casterFaction.equalsIgnoreCase(targetFaction);
                }),
                Map.entry("caster-faction-is", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> {
                        var caster = ctx == null || !ctx.instanceValid()
                                ? null : ctx.instance().baseEntity().orElse(null);
                        String actual = dev.helstera.api.behavior.Factions.factionOf(caster);
                        return actual != null && !wanted.isBlank() && actual.equalsIgnoreCase(wanted);
                    };
                }),
                Map.entry("caster-faction-allied-to", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> {
                        var caster = ctx == null || !ctx.instanceValid()
                                ? null : ctx.instance().baseEntity().orElse(null);
                        var table = dev.helstera.api.behavior.Factions.table();
                        return caster != null && table != null && !wanted.isBlank()
                                && table.alliedFactions(table.factionOf(caster), wanted);
                    };
                }),
                Map.entry("caster-faction-hostile-to", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> {
                        var caster = ctx == null || !ctx.instanceValid()
                                ? null : ctx.instance().baseEntity().orElse(null);
                        var table = dev.helstera.api.behavior.Factions.table();
                        if (caster == null || table == null || wanted.isBlank()) return false;
                        String actual = table.factionOf(caster);
                        return actual != null && !table.alliedFactions(actual, wanted);
                    };
                }),
                Map.entry("target-in-region", (SkillCatalog.ConditionFactory) a -> {
                    RegionBox region = regionArgs(a);
                    return ctx -> {
                        var target = ctx.targetEntity().orElse(null);
                        return target != null && region != null && region.contains(
                                target.getWorld() == null ? null : target.getWorld().getName(),
                                target.getLocation().getX(), target.getLocation().getY(), target.getLocation().getZ());
                    };
                }),
                Map.entry("caster-in-region", (SkillCatalog.ConditionFactory) a -> {
                    RegionBox region = regionArgs(a);
                    return ctx -> {
                        Location location = selfLoc(ctx);
                        return location != null && region != null && region.contains(
                                location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
                    };
                }),
                Map.entry("region", (SkillCatalog.ConditionFactory) a -> {
                    String region = regionArgument(a);
                    if (region.isBlank()) throw new IllegalArgumentException("region requires a region name");
                    return ctx -> Boolean.TRUE.equals(externalRegion(region, conditionLocation(ctx)));
                }),
                Map.entry("not-in-region", (SkillCatalog.ConditionFactory) a -> {
                    String region = regionArgument(a);
                    if (region.isBlank()) {
                        throw new IllegalArgumentException("not-in-region requires a region name");
                    }
                    return ctx -> Boolean.FALSE.equals(externalRegion(region, conditionLocation(ctx)));
                }),

                // ---- 权限 / 持握 / 状态 ----

                Map.entry("has-permission", (SkillCatalog.ConditionFactory) a -> {
                    String perm = str(a, 0, "");
                    return ctx -> ctx.target().map(p -> p.hasPermission(perm)).orElse(false);
                }),
                Map.entry("target-has-permission", (SkillCatalog.ConditionFactory) a -> {
                    String perm = str(a, 0, "");
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        return target instanceof Player player && !perm.isBlank()
                                && player.hasPermission(perm);
                    };
                }),
                Map.entry("caster-has-permission", (SkillCatalog.ConditionFactory) a -> {
                    String perm = str(a, 0, "");
                    return ctx -> {
                        if (ctx == null || perm.isBlank()) return false;
                        Entity caster = ctx.instanceValid()
                                ? ctx.instance().baseEntity().orElse(null)
                                : ctx.trigger().orElse(null);
                        return caster instanceof Player player && player.hasPermission(perm);
                    };
                }),
                Map.entry("holding", (SkillCatalog.ConditionFactory) a -> {
                    String mat = str(a, 0, "");
                    return ctx -> {
                        LivingEntity target = ctx.targetEntity().orElse(null);
                        if (target == null || mat.isEmpty()) return false;
                        try {
                            return target.getEquipment() != null
                                    && target.getEquipment().getItemInMainHand() != null
                                    && target.getEquipment().getItemInMainHand().getType().name().equalsIgnoreCase(mat);
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("target-holding", (SkillCatalog.ConditionFactory) a -> {
                    String mat = str(a, 0, "");
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        if (target == null || mat.isEmpty()) return false;
                        try {
                            return target.getEquipment() != null
                                    && target.getEquipment().getItemInMainHand() != null
                                    && target.getEquipment().getItemInMainHand().getType().name()
                                    .equalsIgnoreCase(mat);
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("has-item", (SkillCatalog.ConditionFactory) a -> {
                    String itemName = str(a, 0, "");
                    Comparison amount = comparison(a, 1, ">=");
                    String slot = str(a, 2, "all");
                    return ctx -> {
                        // Resolve at execution time: integrations may inject their
                        // custom-item resolver after skills.yml has been bound.
                        ItemStack expected = resolveItem(itemName, 1);
                        LivingEntity target = ctx == null ? null : ctx.targetEntity().orElse(null);
                        if (target == null && ctx != null) target = ctx.target().orElse(null);
                        if (target == null) target = conditionEntity(ctx);
                        if (target == null || expected == null) return false;
                        return compare(countMatchingItems(target, expected, slot, vanillaItemName(itemName)), amount);
                    };
                }),
                Map.entry("has-free-inventory-slot", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof Player player
                                && player.getInventory().firstEmpty() >= 0),
                Map.entry("has-currency", (SkillCatalog.ConditionFactory) a -> {
                    String rawAmount = keyedArg(a, "amount", "a", "value", "v");
                    if (rawAmount == null) rawAmount = str(a, 0, "");
                    Comparison required = comparison(List.of(rawAmount), 0, ">=");
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        if (!(target instanceof Player player) || currencyBalance == null
                                || !Double.isFinite(required.value())) {
                            return false;
                        }
                        try {
                            java.util.OptionalDouble balance = currencyBalance.apply(player);
                            return balance != null && balance.isPresent()
                                    && compare(balance.getAsDouble(), required);
                        } catch (Throwable ignored) {
                            return false;
                        }
                    };
                }),
                Map.entry("target-has-item", (SkillCatalog.ConditionFactory) a -> {
                    String itemName = str(a, 0, "");
                    Comparison amount = comparison(a, 1, ">=");
                    String slot = str(a, 2, "all");
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        ItemStack expected = resolveItem(itemName, 1);
                        return target != null && expected != null
                                && compare(countMatchingItems(target, expected, slot, vanillaItemName(itemName)), amount);
                    };
                }),
                Map.entry("wearing", (SkillCatalog.ConditionFactory) a -> {
                    String mat = str(a, 0, "");
                    return ctx -> {
                        LivingEntity target = ctx.targetEntity().orElse(null);
                        if (target == null || mat.isEmpty()) return false;
                        try {
                            var helm = target.getEquipment() == null ? null : target.getEquipment().getHelmet();
                            return helm != null && helm.getType().name().equalsIgnoreCase(mat);
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("target-wearing", (SkillCatalog.ConditionFactory) a -> {
                    String mat = str(a, 0, "");
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        if (target == null || mat.isEmpty()) return false;
                        try {
                            var helm = target.getEquipment() == null ? null : target.getEquipment().getHelmet();
                            return helm != null && helm.getType().name().equalsIgnoreCase(mat);
                        } catch (Throwable t) {
                            return false;
                        }
                    };
                }),
                Map.entry("has-enchantment", (SkillCatalog.ConditionFactory) a -> {
                    String enchantmentName = keyedArg(a, "enchantment", "type", "ench", "e", "t");
                    if (enchantmentName == null) enchantmentName = str(a, 0, "ANY");
                    String levelRaw = keyedArg(a, "level", "lvl", "l");
                    if (levelRaw == null) levelRaw = str(a, 1, ">0");
                    final boolean any = enchantmentName.isBlank()
                            || enchantmentName.equalsIgnoreCase("ANY");
                    final String requested = enchantmentName;
                    final NumberRange level = numberRange(List.of(levelRaw), 0);
                    return ctx -> {
                        try {
                            LivingEntity target = conditionEntity(ctx);
                            if (target == null || level == null) return false;
                            org.bukkit.enchantments.Enchantment expected = any
                                    ? null : resolveEnchantment(requested);
                            if (!any && expected == null) return false;
                            EntityEquipment equipment = target.getEquipment();
                            if (equipment == null) return false;
                            for (ItemStack item : equippedItems(equipment)) {
                                if (item == null) continue;
                                for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry
                                        : item.getEnchantments().entrySet()) {
                                    if ((any || entry.getKey().equals(expected))
                                            && level.contains(entry.getValue())) return true;
                                }
                            }
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#hasEnchantment");
                        }
                        return false;
                    };
                }),
                Map.entry("has-dialog-open", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    if (!hasInstance(ctx)) return false;
                    java.util.function.IntPredicate lookup = dialogueLookup;
                    return lookup != null && lookup.test(id(ctx));
                }),
                Map.entry("is-in-cinematic", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    if (!hasInstance(ctx) || !(conditionEntity(ctx) instanceof Player player)) return false;
                    CinematicLookup lookup = cinematicLookup;
                    return lookup != null && lookup.isActiveForPlayer(id(ctx), player);
                }),
                Map.entry("material-on-cooldown", (SkillCatalog.ConditionFactory) a -> {
                    String materialName = keyedArg(a, "material", "mat", "m");
                    if (materialName == null) materialName = str(a, 0, "ENDER_PEARL");
                    final Material material = Material.matchMaterial(materialName);
                    return ctx -> targetOnlyEntity(ctx) instanceof Player player
                            && material != null && player.hasCooldown(material);
                }),
                Map.entry("trigger-item-type", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = keyedArg(a, "types", "type", "t", "material", "mat", "m",
                            "items", "item", "i");
                    if (wanted == null) wanted = str(a, 0, "DIRT");
                    final String expected = wanted;
                    return ctx -> triggerItemMatches(ctx, expected);
                }),
                Map.entry("entity-item-type", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = keyedArg(a, "types", "type", "t", "material", "mat", "m",
                            "items", "item", "i");
                    if (wanted == null) wanted = str(a, 0, "DIRT");
                    final String expected = wanted;
                    return ctx -> entityItemMatches(ctx, expected);
                }),
                Map.entry("entity-material-type", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = keyedArg(a, "material", "mat", "m", "type", "types", "t");
                    if (wanted == null) wanted = str(a, 0, "DIRT");
                    final String expected = wanted;
                    return ctx -> entityItemMatches(ctx, expected);
                }),
                Map.entry("entity-item-is-similar", (SkillCatalog.ConditionFactory) a -> {
                    String itemName = keyedArg(a, "item", "i", "material", "m", "mm", "mythicitem");
                    if (itemName == null) itemName = str(a, 0, "DIRT");
                    final String expected = itemName;
                    return ctx -> entityItemIsSimilar(ctx, expected);
                }),
                Map.entry("item-is-similar", (SkillCatalog.ConditionFactory) a -> {
                    String itemName = keyedArg(a, "item", "i", "material", "m", "mm", "mythicitem");
                    if (itemName == null) itemName = str(a, 0, "DIRT");
                    String slot = keyedArg(a, "slot", "s");
                    if (slot == null) slot = str(a, 1, "HAND");
                    final String expected = itemName;
                    final String expectedSlot = slot;
                    return ctx -> itemIsSimilar(ctx, expected, expectedSlot);
                }),
                Map.entry("item-recharging", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.HumanEntity human
                                && human.getAttackCooldown() < 1.0f),
                Map.entry("trigger-block-type", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = keyedArg(a, "types", "type", "t", "material", "mat", "m",
                            "block", "b");
                    if (wanted == null) wanted = str(a, 0, "DIRT");
                    final String expected = wanted;
                    return ctx -> triggerBlockMatches(ctx, expected);
                }),
                Map.entry("line-of-sight-from-origin", (SkillCatalog.ConditionFactory) a ->
                        SkillExtras::lineOfSightFromOrigin),
                Map.entry("structure", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = keyedArg(a, "structure", "structures", "s");
                    if (wanted == null) wanted = str(a, 0, "village");
                    final String expected = wanted;
                    return ctx -> structureMatches(ctx, expected);
                }),
                Map.entry("string-equals", (SkillCatalog.ConditionFactory) a -> {
                    String left = keyedArg(a, "value1", "val1", "v1", "string", "s");
                    if (left == null) left = str(a, 0, "");
                    String right = keyedArg(a, "value2", "val2", "v2", "value", "val", "v",
                            "equals", "eq", "e");
                    if (right == null) right = str(a, 1, "");
                    final String expectedLeft = left;
                    final String expectedRight = right;
                    return ctx -> Placeholders.resolve(expectedLeft, ctx)
                            .equals(Placeholders.resolve(expectedRight, ctx));
                }),
                Map.entry("compare-values", (SkillCatalog.ConditionFactory) a -> {
                    String left = keyedArg(a, "value1", "val1", "v1");
                    if (left == null) left = str(a, 0, "");
                    String right = keyedArg(a, "value2", "val2", "v2");
                    if (right == null) right = str(a, 1, "");
                    String operator = keyedArg(a, "operator", "op");
                    if (operator == null) operator = str(a, 2, "==");
                    String type = keyedArg(a, "type", "t");
                    if (type == null) type = str(a, 3, "");
                    final String value1 = left;
                    final String value2 = right;
                    final String op = operator;
                    final String valueType = type;
                    return ctx -> compareValues(ctx, value1, value2, op, valueType);
                }),
                Map.entry("string-empty", (SkillCatalog.ConditionFactory) a -> {
                    String value = keyedArg(a, "value", "val", "v", "string", "s");
                    if (value == null) value = str(a, 0, "");
                    final String raw = value;
                    return ctx -> Placeholders.resolve(raw, ctx).isEmpty();
                }),
                Map.entry("string-not-empty", (SkillCatalog.ConditionFactory) a -> {
                    String value = keyedArg(a, "value", "val", "v", "string", "s");
                    if (value == null) value = str(a, 0, "");
                    final String raw = value;
                    return ctx -> !Placeholders.resolve(raw, ctx).isEmpty();
                }),
                Map.entry("is-skill", (SkillCatalog.ConditionFactory) a -> {
                    String name = keyedArg(a, "name", "n", "skill", "s");
                    if (name == null) name = str(a, 0, "");
                    final String expected = name;
                    return ctx -> skillLookup != null && !expected.isBlank() && skillLookup.test(expected);
                }),
                Map.entry("global-score", (SkillCatalog.ConditionFactory) a -> {
                    String objective = keyedArg(a, "objective", "obj", "o");
                    if (objective == null) objective = str(a, 0, "");
                    String value = keyedArg(a, "value", "val", "v");
                    if (value == null) value = str(a, 1, "0");
                    final String scoreObjective = objective;
                    final NumberRange range = numberRange(List.of(value), 0);
                    return ctx -> {
                        if (scoreObjective.isBlank() || range == null) return false;
                        Integer actual = globalScore(scoreObjective);
                        return actual != null && range.contains(actual);
                    };
                }),
                Map.entry("score", (SkillCatalog.ConditionFactory) a -> {
                    String objective = keyedArg(a, "objective", "obj", "o");
                    if (objective == null) objective = str(a, 0, "");
                    String entry = keyedArg(a, "entry", "ent", "e");
                    String value = keyedArg(a, "value", "val", "v");
                    if (entry == null && value == null) {
                        if (a != null && a.size() > 2 && a.get(1).isBlank()) {
                            value = str(a, 2, "0");
                        } else if (a != null && a.size() > 2) {
                            entry = str(a, 1, "");
                            value = str(a, 2, "0");
                        } else {
                            value = str(a, 1, "0");
                        }
                    }
                    if (value == null) value = str(a, 2, "0");
                    final String scoreObjective = objective;
                    final String scoreEntry = entry;
                    final NumberRange range = numberRange(List.of(value), 0);
                    return ctx -> {
                        Entity target = conditionEntity(ctx);
                        if (target == null || scoreObjective.isBlank() || range == null) return false;
                        Integer actual;
                        ScoreLookup lookup = scoreLookup;
                        if (lookup != null) {
                            actual = lookup.score(scoreObjective, target,
                                    scoreEntry == null || scoreEntry.isBlank() ? null : scoreEntry);
                        } else {
                            try {
                                var manager = org.bukkit.Bukkit.getScoreboardManager();
                                var board = manager == null ? null : manager.getMainScoreboard();
                                var objectiveEntry = board == null ? null : board.getObjective(scoreObjective);
                                if (objectiveEntry == null) return false;
                                var score = scoreEntry == null || scoreEntry.isBlank()
                                        ? objectiveEntry.getScoreFor(target)
                                        : objectiveEntry.getScore(scoreEntry);
                                actual = score.isScoreSet() ? score.getScore() : null;
                            } catch (RuntimeException ignored) {
                                return false;
                            }
                        }
                        return actual != null && range.contains(actual);
                    };
                }),
                Map.entry("looking-at", (SkillCatalog.ConditionFactory) a -> {
                    String configuredMode = str(a, 0, "").toLowerCase(Locale.ROOT);
                    boolean explicitMode = Set.of("block", "entity", "type").contains(configuredMode);
                    String key = keyedArg(a, "type", "t", "block", "b", "entity", "e");
                    String wanted = explicitMode ? str(a, 1, "")
                            : key == null ? str(a, 0, "") : key;
                    String blockKey = keyedArg(a, "block", "b");
                    String entityKey = keyedArg(a, "entity", "e");
                    String distanceRaw = keyedArg(a, "distance", "d");
                    if (distanceRaw == null) distanceRaw = str(a, explicitMode ? 2 : 1, "5");
                    boolean blockMode = "block".equals(configuredMode)
                            || !"entity".equals(configuredMode) && (blockKey != null
                            || entityKey == null && Material.matchMaterial(wanted) != null);
                    double distance;
                    try { distance = Double.parseDouble(distanceRaw); }
                    catch (NumberFormatException ignored) { distance = Double.NaN; }
                    final String expected = wanted;
                    final double maxDistance = distance;
                    final boolean blocks = blockMode;
                    return ctx -> lookingAt(ctx, expected, maxDistance, blocks);
                }),
                Map.entry("bow-tension", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">0");
                    return ctx -> ctx != null && ctx.cancellableEvent().orElse(null)
                            instanceof org.bukkit.event.entity.EntityShootBowEvent shoot
                            && compare(shoot.getForce(), comparison);
                }),
                Map.entry("food-level", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> ctx.target().map(player ->
                            compare(player.getFoodLevel(), comparison)).orElse(false);
                }),
                Map.entry("food-saturation", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> ctx.target().map(player ->
                            compare(player.getSaturation(), comparison)).orElse(false);
                }),
                Map.entry("target-food-level", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        return target instanceof Player player && compare(player.getFoodLevel(), comparison);
                    };
                }),
                Map.entry("target-food-saturation", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> targetOnlyEntity(ctx) instanceof Player player
                            && compare(player.getSaturation(), comparison);
                }),
                Map.entry("enchanting-level", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = a == null || a.isEmpty()
                            ? new NumberRange(0, 0, true, true)
                            : numberRange(a, 0);
                    return ctx -> targetOnlyEntity(ctx) instanceof Player player
                            && range != null && range.contains(player.getLevel());
                }),
                Map.entry("enchanting-experience", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = a == null || a.isEmpty()
                            ? new NumberRange(0, 0, true, true)
                            : numberRange(a, 0);
                    return ctx -> targetOnlyEntity(ctx) instanceof Player player
                            && range != null && range.contains(player.getExp());
                }),
                Map.entry("air-level", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<=");
                    return ctx -> {
                        LivingEntity target = conditionEntity(ctx);
                        return target != null && compare(target.getRemainingAir(), comparison);
                    };
                }),
                Map.entry("target-air-level", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<=");
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        return target != null && compare(target.getRemainingAir(), comparison);
                    };
                }),
                Map.entry("damage-cause", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> ctx != null && !wanted.isBlank()
                            && ctx.damageCause().map(cause -> cause.name().equalsIgnoreCase(wanted)).orElse(false);
                }),
                Map.entry("damage-tag", (SkillCatalog.ConditionFactory) a -> {
                    String tag = keyedArg(a, "tag", "t");
                    if (tag == null) tag = str(a, 0, "");
                    String expected = keyedArg(a, "value", "val", "v", "b", "bool", "boolean");
                    if (expected == null && a != null && a.size() > 1) expected = a.get(1);
                    boolean wanted = expected == null || switch (expected.trim().toLowerCase(Locale.ROOT)) {
                        case "true" -> true;
                        case "false" -> false;
                        default -> throw new IllegalArgumentException("damage-tag value must be true or false");
                    };
                    String expectedTag = tag;
                    return ctx -> expectedTag != null && !expectedTag.isBlank() && ctx != null
                            && ctx.skillTree().map(tree ->
                            tree.damageTags().contains(expectedTag) == wanted).orElse(false);
                }),
                Map.entry("damage-amount", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = a == null || a.isEmpty()
                            ? new NumberRange(0, Double.POSITIVE_INFINITY, false, false)
                            : numberRange(a, 0);
                    return ctx -> {
                        if (range == null || ctx == null) return false;
                        var event = ctx.cancellableEvent().orElse(null);
                        return event instanceof org.bukkit.event.entity.EntityDamageEvent damage
                                && range.contains(damage.getFinalDamage());
                    };
                }),
                Map.entry("last-damage-cause", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "ENTITY_ATTACK");
                    return ctx -> {
                        Entity entity = conditionEntity(ctx);
                        if (entity == null || wanted.isBlank()) return false;
                        var last = entity.getLastDamageCause();
                        return last != null && last.getCause().name().equalsIgnoreCase(wanted);
                    };
                }),
                Map.entry("potion-effect-active", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    return ctx -> {
                        LivingEntity target = ctx.targetEntity().orElse(null);
                        if (target == null || name.isEmpty()) return false;
                        return target.getActivePotionEffects().stream()
                                .anyMatch(e -> e.getType().getKey().getKey()
                                        .equalsIgnoreCase(name.toLowerCase(Locale.ROOT)));
                    };
                }),
                Map.entry("has-potion-effect", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "ANY");
                    NumberRange level = a == null || a.size() <= 1 || a.get(1).isBlank()
                            ? null : numberRange(a, 1);
                    NumberRange duration = a == null || a.size() <= 2 || a.get(2).isBlank()
                            ? null : numberRange(a, 2);
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        if (entity == null) return false;
                        return entity.getActivePotionEffects().stream().anyMatch(effect -> {
                            String actualType = effect.getType() == null ? ""
                                    : effect.getType().getKey().getKey();
                            boolean typeMatches = wanted.isBlank() || "ANY".equalsIgnoreCase(wanted)
                                    || actualType.equalsIgnoreCase(wanted);
                            return typeMatches
                                    && (level == null || level.contains(effect.getAmplifier()))
                                    && (duration == null || duration.contains(effect.getDuration()));
                        });
                    };
                }),
                Map.entry("has-aura-stacks", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = keyedArg(a, "aura", "auraname", "type", "t", "name", "n");
                    if (wanted == null) wanted = str(a, 0, "ANY");
                    String stacksRaw = keyedArg(a, "stacks", "s", "amount", "a");
                    if (stacksRaw == null) stacksRaw = str(a, 1, ">=1");
                    final String expectedAura = wanted;
                    final NumberRange stackRange = numberRange(List.of(stacksRaw), 0);
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        if (entity == null || stackRange == null) return false;
                        return entity.getActivePotionEffects().stream().anyMatch(effect -> {
                            String actualType = effect.getType() == null ? ""
                                    : effect.getType().getKey().getKey();
                            boolean typeMatches = expectedAura.isBlank()
                                    || "ANY".equalsIgnoreCase(expectedAura)
                                    || actualType.equalsIgnoreCase(expectedAura);
                            return typeMatches && stackRange.contains(effect.getAmplifier() + 1.0);
                        });
                    };
                }),
                Map.entry("mythic-has-aura", (SkillCatalog.ConditionFactory) a -> {
                    validateAuraArguments(a, AURA_NAME_ARGUMENTS, "aura");
                    String wanted = keyedArg(a, "aura", "auraname", "b", "buff", "buffname",
                            "debuff", "debuffname", "n", "name");
                    if (wanted == null) wanted = str(a, 0, "");
                    if (wanted.isBlank()) throw new IllegalArgumentException("aura name is required");
                    String expected = wanted;
                    return ctx -> {
                        AuraRegistry store = auraRegistry;
                        LivingEntity entity = conditionEntity(ctx);
                        String name = Placeholders.resolve(expected, ctx).trim();
                        return store != null && entity != null && entity.isValid() && !entity.isDead()
                                && !(expected.contains("<") && name.equals(expected))
                                && store.hasAura(entity.getUniqueId(), name);
                    };
                }),
                Map.entry("mythic-has-aura-stacks", (SkillCatalog.ConditionFactory) a -> {
                    validateAuraArguments(a, AURA_STACKS_ARGUMENTS, "aura", "stacks");
                    String wanted = keyedArg(a, "aura", "auraname", "b", "buff", "buffname",
                            "debuff", "debuffname", "n", "name");
                    if (wanted == null) wanted = str(a, 0, "");
                    String stacksRaw = keyedArg(a, "stacks", "s");
                    if (stacksRaw == null) stacksRaw = str(a, 1, "1");
                    if (wanted.isBlank()) throw new IllegalArgumentException("aura name is required");
                    NumberRange stackRange = numberRange(List.of(stacksRaw), 0);
                    if (stackRange == null) throw new IllegalArgumentException("invalid aura stack range");
                    String expected = wanted;
                    return ctx -> {
                        AuraRegistry store = auraRegistry;
                        LivingEntity entity = conditionEntity(ctx);
                        String name = Placeholders.resolve(expected, ctx).trim();
                        return store != null && entity != null && entity.isValid() && !entity.isDead()
                                && !(expected.contains("<") && name.equals(expected))
                                && store.hasAuraStacks(entity.getUniqueId(), name,
                                stacks -> stackRange.contains(stacks));
                    };
                }),
                Map.entry("mythic-has-aura-type", (SkillCatalog.ConditionFactory) a -> {
                    validateAuraArguments(a, AURA_TYPE_ARGUMENTS, "type");
                    String wanted = keyedArg(a, "auratype", "auragroup", "group", "type", "g", "t");
                    if (wanted == null) wanted = str(a, 0, "");
                    if (wanted.isBlank()) throw new IllegalArgumentException("aura type is required");
                    String expected = wanted;
                    return ctx -> {
                        AuraRegistry store = auraRegistry;
                        LivingEntity entity = conditionEntity(ctx);
                        String type = Placeholders.resolve(expected, ctx).trim();
                        return store != null && entity != null && entity.isValid() && !entity.isDead()
                                && !(expected.contains("<") && type.equals(expected))
                                && store.hasAuraType(entity.getUniqueId(), type);
                    };
                }),
                Map.entry("mythic-target-has-aura", (SkillCatalog.ConditionFactory) a -> {
                    validateAuraArguments(a, AURA_NAME_ARGUMENTS, "aura");
                    String wanted = keyedArg(a, "aura", "auraname", "b", "buff", "buffname",
                            "debuff", "debuffname", "n", "name");
                    if (wanted == null) wanted = str(a, 0, "");
                    if (wanted.isBlank()) throw new IllegalArgumentException("aura name is required");
                    String expected = wanted;
                    return ctx -> {
                        AuraRegistry store = auraRegistry;
                        LivingEntity target = targetOnlyEntity(ctx);
                        String name = Placeholders.resolve(expected, ctx).trim();
                        return store != null && target != null && target.isValid() && !target.isDead()
                                && !(expected.contains("<") && name.equals(expected))
                                && store.hasAura(target.getUniqueId(), name);
                    };
                }),
                Map.entry("target-potion-effect-active", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        if (target == null || name.isEmpty()) return false;
                        return target.getActivePotionEffects().stream()
                                .anyMatch(e -> e.getType().getKey().getKey()
                                        .equalsIgnoreCase(name.toLowerCase(Locale.ROOT)));
                    };
                }),
                Map.entry("target-has-auras", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    if (wanted.startsWith("effect=")) wanted = wanted.substring("effect=".length()).trim();
                    if (wanted.startsWith("type=")) wanted = wanted.substring("type=".length()).trim();
                    String expected = wanted;
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        return target != null && target.getActivePotionEffects().stream().anyMatch(effect ->
                                expected.isBlank() || effect.getType().getKey().getKey()
                                        .equalsIgnoreCase(expected));
                    };
                }),
                Map.entry("game-mode-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "survival");
                    return ctx -> ctx.target()
                            .map(p -> p.getGameMode().name().equalsIgnoreCase(want)).orElse(false);
                }),
                Map.entry("server-is-paper", (SkillCatalog.ConditionFactory) a ->
                        ctx -> isPaperServer()),
                Map.entry("server-nms-version", (SkillCatalog.ConditionFactory) a -> {
                    String expected = keyedArg(a, "version", "sv", "v");
                    if (expected == null) expected = str(a, 0, "v1_19_R1_2");
                    String normalized = normalizeNmsVersion(expected);
                    if (normalized == null) {
                        throw new IllegalArgumentException(
                                "server-nms-version requires a value such as v1_21_R1");
                    }
                    return ctx -> serverNmsVersionMatches(normalized);
                }),
                Map.entry("plugin", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "MythicMobs");
                    return ctx -> pluginRunning(wanted);
                }),
                Map.entry("server-version", (SkillCatalog.ConditionFactory) a -> {
                    String expected = str(a, 0, "");
                    return ctx -> serverVersionMatches(expected, VersionCheck.EXACT);
                }),
                Map.entry("server-version-after-or-equal", (SkillCatalog.ConditionFactory) a -> {
                    String expected = str(a, 0, "");
                    boolean inclusive = bool(a, 1, true);
                    return ctx -> serverVersionMatches(expected, VersionCheck.AFTER_OR_EQUAL)
                            && (inclusive || !serverVersionMatches(expected, VersionCheck.EXACT));
                }),
                Map.entry("server-version-before", (SkillCatalog.ConditionFactory) a -> {
                    String expected = str(a, 0, "");
                    boolean inclusive = bool(a, 1, true);
                    return ctx -> {
                        boolean exact = serverVersionMatches(expected, VersionCheck.EXACT);
                        boolean afterOrEqual = serverVersionMatches(expected, VersionCheck.AFTER_OR_EQUAL);
                        return inclusive ? !afterOrEqual || exact : !afterOrEqual && !exact;
                    };
                }),
                Map.entry("is-mythic-mob", (SkillCatalog.ConditionFactory) a ->
                        SkillExtras::isManagedEntity),
                Map.entry("mythic-mob-type", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = keyedArg(a, "type", "types", "t");
                    if (wanted == null) wanted = str(a, 0, "");
                    boolean exact = keyedBoolean(a, 1, true, "exactmatch", "em");
                    final String expected = wanted;
                    final boolean exactMatch = exact;
                    return ctx -> {
                        if (ctx == null || !ctx.instanceValid() || expected.isBlank()) return false;
                        String modelId = ctx.instance().model().id();
                        for (String raw : expected.split(",")) {
                            String value = raw.trim();
                            if (value.isBlank()) continue;
                            if (exactMatch ? modelId.equalsIgnoreCase(value)
                                    : modelId.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT))) {
                                return true;
                            }
                        }
                        return false;
                    };
                }),
                Map.entry("is-creeper-primed", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Creeper creeper
                                && creeper.isIgnited()),
                Map.entry("bounding-boxes-overlap", (SkillCatalog.ConditionFactory) a -> {
                    double offset = num(a, 0, 0.0);
                    return ctx -> boundingBoxesOverlap(ctx, offset);
                }),
                Map.entry("field-of-view", (SkillCatalog.ConditionFactory) a -> {
                    double angle = num(a, 0, 90.0);
                    double rotation = num(a, 1, 0.0);
                    return ctx -> fieldOfView(ctx, angle, rotation);
                }),
                Map.entry("distance-from-location", (SkillCatalog.ConditionFactory) a -> {
                    double x = coordinateArg(a, 0, 0, "x");
                    double y = coordinateArg(a, 1, 0, "y");
                    double z = coordinateArg(a, 2, 0, "z");
                    String worldName = keyedArg(a, "world", "w");
                    String distanceRaw = keyedArg(a, "distance", "d");
                    if (distanceRaw == null && a != null) {
                        int index = worldName == null && a.size() == 4 ? 3 : 4;
                        if (a.size() > index && !a.get(index).contains("=")) distanceRaw = a.get(index);
                    }
                    NumberRange range = rangeValue(distanceRaw);
                    final String selectedWorld = worldName;
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        if (target == null || range == null || !Double.isFinite(x)
                                || !Double.isFinite(y) || !Double.isFinite(z)) return false;
                        Location targetLocation = target.getLocation();
                        if (targetLocation == null || targetLocation.getWorld() == null) return false;
                        org.bukkit.World world = targetLocation.getWorld();
                        if (selectedWorld != null && !selectedWorld.isBlank()) {
                            world = org.bukkit.Bukkit.getWorld(selectedWorld);
                            if (world == null || world != targetLocation.getWorld()) return false;
                        }
                        return range.contains(targetLocation.distance(new Location(world, x, y, z)));
                    };
                }),
                Map.entry("x-diff", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> coordinateDifference(ctx, 'x', range);
                }),
                Map.entry("y-diff", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> coordinateDifference(ctx, 'y', range);
                }),
                Map.entry("z-diff", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> coordinateDifference(ctx, 'z', range);
                }),
                Map.entry("origin-location", (SkillCatalog.ConditionFactory) a -> {
                    String locationRaw = keyedArg(a, "location", "loc", "l", "c");
                    if (locationRaw == null && a != null && !a.isEmpty() && a.get(0).contains(",")) {
                        locationRaw = a.get(0);
                    }
                    double x = coordinateArg(a, 0, 0, "x");
                    double y = coordinateArg(a, 1, 0, "y");
                    double z = coordinateArg(a, 2, 0, "z");
                    if (locationRaw != null) {
                        String[] parts = locationRaw.split(",");
                        if (parts.length >= 3) {
                            try {
                                x = Double.parseDouble(parts[0].trim());
                                y = Double.parseDouble(parts[1].trim());
                                z = Double.parseDouble(parts[2].trim());
                            } catch (NumberFormatException ignored) {
                                x = y = z = Double.NaN;
                            }
                        } else {
                            x = y = z = Double.NaN;
                        }
                    }
                    int exactIndex = locationRaw == null ? 3 : 1;
                    boolean exact = keyedBoolean(a, exactIndex, false, "exact", "e");
                    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                        throw new IllegalArgumentException("origin-location coordinates must be finite");
                    }
                    final double expectedX = x;
                    final double expectedY = y;
                    final double expectedZ = z;
                    return ctx -> {
                        Location origin = ctx == null ? null : ctx.origin().orElse(null);
                        if (origin == null || origin.getWorld() == null
                                || !Double.isFinite(expectedX) || !Double.isFinite(expectedY)
                                || !Double.isFinite(expectedZ)) return false;
                        if (exact) {
                            return Math.abs(origin.getX() - expectedX) <= 1.0e-6
                                    && Math.abs(origin.getY() - expectedY) <= 1.0e-6
                                    && Math.abs(origin.getZ() - expectedZ) <= 1.0e-6;
                        }
                        return origin.getBlockX() == (int) Math.floor(expectedX)
                                && origin.getBlockY() == (int) Math.floor(expectedY)
                                && origin.getBlockZ() == (int) Math.floor(expectedZ);
                    };
                }),
                Map.entry("cuboid", (SkillCatalog.ConditionFactory) a -> {
                    String first = keyedArg(a, "location1", "loc1", "l1", "a");
                    if (first == null) first = str(a, 0, "");
                    String second = keyedArg(a, "location2", "loc2", "l2", "b");
                    if (second == null) second = str(a, 1, "");
                    boolean relative = keyedBoolean(a, 2, false, "relative", "r");
                    final String firstPoint = first;
                    final String secondPoint = second;
                    return ctx -> boundsMatch(ctx, "cuboid", firstPoint, secondPoint,
                            null, null, null, relative);
                }),
                Map.entry("in-bounds", (SkillCatalog.ConditionFactory) a -> {
                    String shape = keyedArg(a, "shape");
                    if (shape == null) shape = str(a, 0, "cuboid");
                    String first = keyedArg(a, "corner1", "c1");
                    String second = keyedArg(a, "corner2", "c2");
                    String center = keyedArg(a, "location", "loc", "l", "center", "centre", "origin");
                    String radius = keyedArg(a, "radius", "r");
                    String height = keyedArg(a, "height", "h");
                    if (first == null && second == null && shape.equalsIgnoreCase("cuboid")) {
                        first = str(a, 1, "");
                        second = str(a, 2, "");
                    } else if (center == null && !shape.equalsIgnoreCase("cuboid")) {
                        center = str(a, 1, "");
                        if (radius == null) radius = str(a, 2, "");
                        if (height == null && shape.equalsIgnoreCase("cylinder")) height = str(a, 3, "");
                    }
                    boolean relative = keyedBoolean(a, 4, false, "relative", "rel");
                    final String selectedShape = shape;
                    final String firstPoint = first;
                    final String secondPoint = second;
                    final String selectedCenter = center;
                    final String selectedRadius = radius;
                    final String selectedHeight = height;
                    return ctx -> boundsMatch(ctx, selectedShape, firstPoint, secondPoint,
                            selectedCenter, selectedRadius, selectedHeight, relative);
                }),
                Map.entry("is-in-survival-mode", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof Player player
                                && player.getGameMode() == GameMode.SURVIVAL),
                Map.entry("is-using-spyglass", (SkillCatalog.ConditionFactory) a ->
                        ctx -> targetOnlyEntity(ctx) instanceof Player player
                                && player.hasActiveItem()
                                && player.getActiveItem() != null
                                && player.getActiveItem().getType() == Material.SPYGLASS),
                Map.entry("target-game-mode-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "survival");
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        return target instanceof Player player
                                && player.getGameMode().name().equalsIgnoreCase(want);
                    };
                }),

                // ---- 范围统计 ----

                Map.entry("players-in-radius", (SkillCatalog.ConditionFactory) a -> {
                    int min = nonNegativeCount(a, 0, 1);
                    double radius = nonNegativeRadius(a, 1, 16);
                    return ctx -> countNear(ctx, radius, false) >= min;
                }),
                Map.entry("entities-in-radius", (SkillCatalog.ConditionFactory) a -> {
                    int min = nonNegativeCount(a, 0, 1);
                    double radius = nonNegativeRadius(a, 1, 16);
                    return ctx -> countNear(ctx, radius, true) >= min;
                }),
                Map.entry("mobs-in-radius", (SkillCatalog.ConditionFactory) a -> {
                    int min = nonNegativeCount(a, 0, 1);
                    double radius = nonNegativeRadius(a, 1, 16);
                    return ctx -> countMobsNear(ctx, radius) >= min;
                }),
                Map.entry("mobs-near-origin", (SkillCatalog.ConditionFactory) a -> {
                    Comparison amount = comparison(a, 0, ">=");
                    double radius = nonNegativeRadius(a, 1, 5);
                    String types = str(a, 2, "");
                    return ctx -> compare(countMobsNearOrigin(ctx, radius, types), amount);
                }),
                Map.entry("living-in-radius", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    double radius = nonNegativeRadius(a, 1, 5);
                    return ctx -> compare(countLivingNear(ctx, radius), comparison);
                }),
                Map.entry("mobs-in-chunk", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> compare(countMobsInChunk(ctx), comparison);
                }),
                Map.entry("dimension-is", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> matchesDimension(selfLoc(ctx), wanted);
                }),
                Map.entry("ender-dragon-alive", (SkillCatalog.ConditionFactory) a ->
                        ctx -> {
                            Location location = conditionLocation(ctx);
                            if (location == null || location.getWorld() == null) return false;
                            return location.getWorld().getEntitiesByClass(org.bukkit.entity.EnderDragon.class)
                                    .stream().anyMatch(entity -> entity.isValid() && !entity.isDead());
                        }),
                Map.entry("ender-dragon-phase", (SkillCatalog.ConditionFactory) a -> {
                    String raw = str(a, 0, "");
                    if (raw.isBlank()) throw new IllegalArgumentException("phase list must not be empty");
                    java.util.Set<org.bukkit.entity.EnderDragon.Phase> phases = new java.util.HashSet<>();
                    for (String token : raw.split(",", -1)) {
                        if (token.isBlank()) throw new IllegalArgumentException("phase list contains an empty value");
                        try {
                            phases.add(org.bukkit.entity.EnderDragon.Phase.valueOf(
                                    token.trim().toUpperCase(Locale.ROOT)));
                        } catch (IllegalArgumentException invalidPhase) {
                            throw new IllegalArgumentException("unknown EnderDragon phase: " + token.trim());
                        }
                    }
                    java.util.Set<org.bukkit.entity.EnderDragon.Phase> expected = java.util.Set.copyOf(phases);
                    return ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.EnderDragon dragon
                            && expected.contains(dragon.getPhase());
                }),
                Map.entry("distance-from-spawn", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> {
                        Location location = conditionLocation(ctx);
                        if (range == null || location == null || location.getWorld() == null) return false;
                        return range.contains(location.distance(location.getWorld().getSpawnLocation()));
                    };
                }),
                Map.entry("distance-from-tracked-location", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = trackedLocationRange(a);
                    return ctx -> {
                        if (ctx == null || !ctx.instanceValid()) return false;
                        Entity caster = ctx.instance().baseEntity().orElse(null);
                        if (caster instanceof Player) return false;
                        Location current = caster == null ? ctx.instance().location() : caster.getLocation();
                        Location tracked = ctx.skillTree()
                                .flatMap(tree -> tree.location(SkillTreeContext.MYTHIC_TRACKED_LOCATION))
                                .orElse(null);
                        if (current == null || tracked == null || current.getWorld() == null
                                || current.getWorld() != tracked.getWorld()) return false;
                        return range.contains(current.distance(tracked));
                    };
                }),
                Map.entry("players-online", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> compare(org.bukkit.Bukkit.getOnlinePlayers().size(), comparison);
                }),
                Map.entry("players-in-world", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> {
                        Location location = selfLoc(ctx);
                        return location != null && location.getWorld() != null
                                && compare(location.getWorld().getPlayers().size(), comparison);
                    };
                }),
                Map.entry("mobs-in-world", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> {
                        Location location = selfLoc(ctx);
                        if (location == null || location.getWorld() == null) return false;
                        long mobs = location.getWorld().getEntities().stream()
                                .filter(entity -> entity instanceof org.bukkit.entity.Mob mob && mob.isValid())
                                .count();
                        return compare(mobs, comparison);
                    };
                }),
                Map.entry("player-within", (SkillCatalog.ConditionFactory) a -> {
                    double radius = num(a, 0, 0);
                    return ctx -> hasPlayerWithin(ctx, radius);
                }),
                Map.entry("player-not-within", (SkillCatalog.ConditionFactory) a -> {
                    double radius = num(a, 0, 0);
                    return ctx -> !hasPlayerWithin(ctx, radius);
                }),
                Map.entry("y-level-below", (SkillCatalog.ConditionFactory) a -> {
                    double y = num(a, 0, 64);
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        return l != null && l.getY() < y;
                    };
                }),
                Map.entry("y-level-above", (SkillCatalog.ConditionFactory) a -> {
                    double y = num(a, 0, 64);
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        return l != null && l.getY() > y;
                    };
                }),
                Map.entry("armor-gte", (SkillCatalog.ConditionFactory) a -> {
                    double v = num(a, 0, 0);
                    // Paper 1.21 仍用 GENERIC_ 前缀的枚举常量名；
                    // LivingEntity#getArmorValue 已被属性系统取代，不能再调。
                    return ctx -> selfLiving(ctx)
                            .map(e -> e.getAttribute(
                                    org.bukkit.attribute.Attribute.GENERIC_ARMOR))
                            .map(org.bukkit.attribute.AttributeInstance::getValue)
                            .orElse(0.0) >= v;
                }),
                Map.entry("hunger-below", (SkillCatalog.ConditionFactory) a -> {
                    double v = num(a, 0, 10);
                    return ctx -> ctx.target()
                            .filter(p -> p.getGameMode() == GameMode.SURVIVAL)
                            .map(p -> p.getFoodLevel() <= v).orElse(false);
                }),
                Map.entry("velocity-y-gte", (SkillCatalog.ConditionFactory) a -> {
                    double v = num(a, 0, 0);
                    return ctx -> selfLiving(ctx)
                            .map(e -> e.getVelocity().getY() >= v).orElse(false);
                }),
                Map.entry("motion-x", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> targetOnlyEntity(ctx) != null
                            && compare(targetOnlyEntity(ctx).getVelocity().getX(), comparison);
                }),
                Map.entry("motion-y", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> targetOnlyEntity(ctx) != null
                            && compare(targetOnlyEntity(ctx).getVelocity().getY(), comparison);
                }),
                Map.entry("directional-velocity", (SkillCatalog.ConditionFactory) a -> {
                    String xRaw = keyedArg(a, "x", "s", "side");
                    String yRaw = keyedArg(a, "y", "up", "down", "vertical", "v");
                    String zRaw = keyedArg(a, "z", "f", "forward");
                    if (xRaw == null && a != null && !a.isEmpty() && !a.get(0).contains("=")) {
                        xRaw = a.get(0);
                    }
                    if (yRaw == null && a != null && a.size() > 1 && !a.get(1).contains("=")) {
                        yRaw = a.get(1);
                    }
                    if (zRaw == null && a != null && a.size() > 2 && !a.get(2).contains("=")) {
                        zRaw = a.get(2);
                    }
                    NumberRange x = rangeValue(xRaw);
                    NumberRange y = rangeValue(yRaw);
                    NumberRange z = rangeValue(zRaw);
                    boolean relative = keyedBoolean(a, 3, false, "relative", "rel");
                    boolean absX = keyedBoolean(a, 4, relative, "absx", "ax", "abss", "as");
                    boolean absY = keyedBoolean(a, 5, false, "absy", "ay");
                    boolean absZ = keyedBoolean(a, 6, false, "absz", "az", "absf", "af");
                    if (x == null && y == null && z == null) return ctx -> false;
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        if (target == null) return false;
                        org.bukkit.util.Vector velocity = target.getVelocity();
                        double vx = velocity.getX();
                        double vy = velocity.getY();
                        double vz = velocity.getZ();
                        if (relative) {
                            org.bukkit.util.Vector forward = target.getLocation().getDirection().setY(0);
                            if (forward.lengthSquared() < 1.0e-9) return false;
                            forward.normalize();
                            org.bukkit.util.Vector side = new org.bukkit.util.Vector(
                                    forward.getZ(), 0, -forward.getX());
                            vx = velocity.getX() * forward.getX() + velocity.getZ() * forward.getZ();
                            vz = velocity.getX() * side.getX() + velocity.getZ() * side.getZ();
                        }
                        return (x == null || x.contains(absX ? Math.abs(vx) : vx))
                                && (y == null || y.contains(absY ? Math.abs(vy) : vy))
                                && (z == null || z.contains(absZ ? Math.abs(vz) : vz));
                    };
                }),
                Map.entry("fall-speed", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = numberRange(a, 0);
                    return ctx -> {
                        LivingEntity target = targetOnlyEntity(ctx);
                        if (range == null || target == null) return false;
                        return range.contains(Math.max(0.0, -target.getVelocity().getY()));
                    };
                }),
                Map.entry("motion-z", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> targetOnlyEntity(ctx) != null
                            && compare(targetOnlyEntity(ctx).getVelocity().getZ(), comparison);
                }),
                Map.entry("velocity", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> targetOnlyEntity(ctx) != null
                            && compare(targetOnlyEntity(ctx).getVelocity().length(), comparison);
                }),

                // ---- 变量 / 分数 / 冷却 ----

                Map.entry("trigger-is-living", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx != null && ctx.trigger().filter(org.bukkit.entity.LivingEntity.class::isInstance).isPresent()),
                Map.entry("trigger-is-player", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx != null && ctx.trigger().filter(org.bukkit.entity.Player.class::isInstance).isPresent()),
                Map.entry("targets", (SkillCatalog.ConditionFactory) a -> {
                    String amount = keyedArg(a, "amount", "a", "count", "min", "minimum", "n");
                    if (amount == null) amount = str(a, 0, ">0");
                    String targeter = keyedArg(a, "targeter");
                    final NumberRange range = numberRange(List.of(amount), 0);
                    final String selectedBy = targeter;
                    return ctx -> {
                        if (ctx == null || range == null) return false;
                        List<LivingEntity> candidates = ctx.targets();
                        if (candidates.isEmpty()) {
                            candidates = ctx.targetEntity().map(List::of).orElseGet(List::of);
                        }
                        int count;
                        if (selectedBy == null || selectedBy.isBlank()) {
                            count = candidates.size();
                        } else {
                            count = dev.helstera.api.behavior.Targeters
                                    .select(selectedBy, ctx, candidates, List.of()).size();
                        }
                        return range.contains(count);
                    };
                }),
                Map.entry("target-count-at-least", (SkillCatalog.ConditionFactory) a -> {
                    int minimum = (int) num(a, 0, 1);
                    return ctx -> targetEntities(ctx).size() >= Math.max(0, minimum);
                }),
                Map.entry("trigger-is", (SkillCatalog.ConditionFactory) a -> {
                    String type = str(a, 0, "");
                    return ctx -> ctx != null && ctx.trigger().map(e ->
                            e.getType().name().equalsIgnoreCase(type)).orElse(false);
                }),
                Map.entry("has-var", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    return ctx -> hasVariable(ctx, ref);
                }),
                Map.entry("variable-is-set", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    return ctx -> hasVariable(ctx, ref);
                }),
                Map.entry("branch-unlocked", (SkillCatalog.ConditionFactory) a -> {
                    String branch = str(a, 0, "");
                    if (branch.startsWith("branch=")) branch = branch.substring("branch=".length()).trim();
                    String expected = branch;
                    return ctx -> ctx != null && !expected.isBlank()
                            && ctx.skillTree().map(tree -> tree.branchUnlocked(expected)).orElse(false);
                }),
                Map.entry("branch-locked", (SkillCatalog.ConditionFactory) a -> {
                    String branch = str(a, 0, "");
                    if (branch.startsWith("branch=")) branch = branch.substring("branch=".length()).trim();
                    String expected = branch;
                    return ctx -> ctx == null || expected.isBlank()
                            || ctx.skillTree().map(tree -> !tree.branchUnlocked(expected)).orElse(true);
                }),
                Map.entry("variable-gte", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    double threshold = variableNumber(a, 1, 0, "value", "val", "amount", "threshold");
                    return ctx -> varOf(ctx, ref) >= threshold;
                }),
                Map.entry("variable-lte", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    double threshold = variableNumber(a, 1, Double.MAX_VALUE,
                            "value", "val", "amount", "threshold");
                    return ctx -> varOf(ctx, ref) <= threshold;
                }),
                Map.entry("variable-equals", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String expected = keyedArg(a, "value", "val", "equals", "expected");
                    if (expected == null) expected = str(a, 1, "");
                    String wanted = expected;
                    return ctx -> variableEquals(ctx, ref, Placeholders.resolve(wanted, ctx));
                }),
                Map.entry("variable-not-equals", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String expected = keyedArg(a, "value", "val", "equals", "expected");
                    if (expected == null) expected = str(a, 1, "");
                    String wanted = expected;
                    return ctx -> hasVariable(ctx, ref) && !variableEquals(ctx, ref, wanted);
                }),
                Map.entry("variable-in-range", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    NumberRange range = variableConditionRange(a);
                    return ctx -> rawVariable(ctx, ref) instanceof Number current
                            && range.contains(current.doubleValue());
                }),
                Map.entry("variable-contains", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String expected = keyedArg(a, "value", "val", "contains");
                    if (expected == null) expected = str(a, 1, "");
                    String compareType = keyedArg(a, "comparetype", "compare", "comp");
                    if (compareType == null) compareType = str(a, 2, "ALL");
                    final String wanted = expected;
                    final String mode = compareType;
                    return ctx -> variableContains(ctx, ref, wanted, mode);
                }),
                Map.entry("variable-not-contains", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String expected = keyedArg(a, "value", "val", "contains");
                    if (expected == null) expected = str(a, 1, "");
                    String compareType = keyedArg(a, "comparetype", "compare", "comp");
                    if (compareType == null) compareType = str(a, 2, "ALL");
                    final String wanted = expected;
                    final String mode = compareType;
                    return ctx -> hasVariable(ctx, ref) && !variableContains(ctx, ref, wanted, mode);
                }),
                Map.entry("variable-index-equals", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    int index = integerArg(a, 0, "index", "i");
                    String expected = keyedArg(a, "value", "val", "equals", "expected");
                    if (expected == null) expected = str(a, 2, "");
                    String wanted = expected;
                    return ctx -> variableEqualsValue(indexedVariable(ctx, ref, index), wanted);
                }),
                Map.entry("variable-index-gte", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    int index = integerArg(a, 0, "index", "i");
                    double threshold = variableNumber(a, 2, Double.NaN, "value", "val", "threshold");
                    return ctx -> numericValue(indexedVariable(ctx, ref, index)) >= threshold;
                }),
                Map.entry("variable-index-lte", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    int index = integerArg(a, 0, "index", "i");
                    double threshold = variableNumber(a, 2, Double.NaN, "value", "val", "threshold");
                    return ctx -> numericValue(indexedVariable(ctx, ref, index)) <= threshold;
                }),
                Map.entry("variable-index-in-range", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    int index = integerArg(a, 0, "index", "i");
                    double minimum = variableNumber(a, 2, Double.NaN, "min", "minimum", "from");
                    double maximum = variableNumber(a, 3, Double.NaN, "max", "maximum", "to");
                    return ctx -> {
                        double value = numericValue(indexedVariable(ctx, ref, index));
                        return Double.isFinite(value) && value >= minimum && value <= maximum;
                    };
                }),
                Map.entry("variable-key-exists", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String key = keyedArg(a, "key", "k");
                    if (key == null) key = str(a, 1, "");
                    String wanted = key;
                    return ctx -> mapKeyExists(rawVariable(ctx, ref), wanted);
                }),
                Map.entry("variable-key-not-exists", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String key = keyedArg(a, "key", "k");
                    if (key == null) key = str(a, 1, "");
                    String wanted = key;
                    return ctx -> hasVariable(ctx, ref) && !mapKeyExists(rawVariable(ctx, ref), wanted);
                }),
                Map.entry("variable-map-value-equals", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String key = keyedArg(a, "key", "k");
                    if (key == null) key = str(a, 1, "");
                    String expected = keyedArg(a, "value", "val", "equals", "expected");
                    if (expected == null) expected = str(a, 2, "");
                    String wantedKey = key;
                    String wantedValue = expected;
                    return ctx -> mapValueEquals(rawVariable(ctx, ref), wantedKey, wantedValue);
                }),
                Map.entry("variable-map-value-not-equals", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String key = keyedArg(a, "key", "k");
                    if (key == null) key = str(a, 1, "");
                    String expected = keyedArg(a, "value", "val", "equals", "expected");
                    if (expected == null) expected = str(a, 2, "");
                    String wantedKey = key;
                    String wantedValue = expected;
                    return ctx -> hasVariable(ctx, ref)
                            && !mapValueEquals(rawVariable(ctx, ref), wantedKey, wantedValue);
                }),
                Map.entry("variable-map-value-gte", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String key = keyedArg(a, "key", "k");
                    if (key == null) key = str(a, 1, "");
                    double threshold = variableNumber(a, 2, Double.NaN, "value", "val", "threshold");
                    String wantedKey = key;
                    return ctx -> mapNumericValue(rawVariable(ctx, ref), wantedKey) >= threshold;
                }),
                Map.entry("variable-map-value-lte", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String key = keyedArg(a, "key", "k");
                    if (key == null) key = str(a, 1, "");
                    double threshold = variableNumber(a, 2, Double.NaN, "value", "val", "threshold");
                    String wantedKey = key;
                    return ctx -> mapNumericValue(rawVariable(ctx, ref), wantedKey) <= threshold;
                }),
                Map.entry("variable-map-value-in-range", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String key = keyedArg(a, "key", "k");
                    if (key == null) key = str(a, 1, "");
                    double minimum = variableNumber(a, 2, Double.NaN, "min", "minimum", "from");
                    double maximum = variableNumber(a, 3, Double.NaN, "max", "maximum", "to");
                    String wantedKey = key;
                    return ctx -> {
                        double value = mapNumericValue(rawVariable(ctx, ref), wantedKey);
                        return Double.isFinite(value) && value >= minimum && value <= maximum;
                    };
                }),
                Map.entry("variable-map-value-not-in-range", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String key = keyedArg(a, "key", "k");
                    if (key == null) key = str(a, 1, "");
                    double minimum = variableNumber(a, 2, Double.NaN, "min", "minimum", "from");
                    double maximum = variableNumber(a, 3, Double.NaN, "max", "maximum", "to");
                    String wantedKey = key;
                    return ctx -> {
                        double value = mapNumericValue(rawVariable(ctx, ref), wantedKey);
                        return Double.isFinite(value) && (value < minimum || value > maximum);
                    };
                }),
                Map.entry("has-score", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    return ctx -> hasScore(ctx, name);
                }),
                Map.entry("score-gte", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    int threshold = (int) num(a, 1, 0);
                    return ctx -> scoreOf(ctx, name) >= threshold;
                }),
                // ---- 连杀 ----
                Map.entry("kill-streak-at-least", (SkillCatalog.ConditionFactory) a -> {
                    int threshold = (int) num(a, 0, 0);
                    // 未注入表时一律判 false 而不是抛 NPE：条件求值在决策链里，
                    // 抛异常会打断整轮决策，症状是「这个 Boss 的技能全都不动了」
                    return ctx -> hasInstance(ctx) && streak(ctx) >= threshold;
                }),
                Map.entry("kill-streak-active", (SkillCatalog.ConditionFactory) a ->
                        ctx -> hasInstance(ctx) && streak(ctx) > 0),
                Map.entry("player-kills", (SkillCatalog.ConditionFactory) a -> {
                    NumberRange range = a == null || a.isEmpty()
                            ? new NumberRange(0, 0, true, true) : numberRange(a, 0);
                    if (range == null || Double.isNaN(range.min()) || Double.isNaN(range.max())
                            || range.min() > range.max()
                            || (range.min() == Double.POSITIVE_INFINITY
                            && range.max() == Double.POSITIVE_INFINITY)
                            || (range.min() == Double.NEGATIVE_INFINITY
                            && range.max() == Double.NEGATIVE_INFINITY)) {
                        throw new IllegalArgumentException("player-kills requires a valid number range");
                    }
                    return ctx -> hasInstance(ctx) && playerKillCount != null
                            && range.contains(playerKillCount.count(id(ctx)));
                }),
                // ---- 仇恨 ----
                // 未接线或档案未启用 threat 时一律判 false：那两种情况下
                // 「没有仇恨表」与「仇恨为 0」对作者是同一件事，判 false 最保守
                Map.entry("threat-above", (SkillCatalog.ConditionFactory) a -> {
                    double threshold = num(a, 0, 0);
                    return ctx -> targetThreat(ctx) > threshold;
                }),
                Map.entry("threat-below", (SkillCatalog.ConditionFactory) a -> {
                    double threshold = num(a, 0, 0);
                    // 用 targetThreat 而非表对象：无表/无目标时返回 NaN，
                    // 与任何阈值的比较都是 false，不会误放行
                    return ctx -> targetThreat(ctx) < threshold;
                }),
                Map.entry("has-threat", (SkillCatalog.ConditionFactory) a -> {
                    double threshold = num(a, 0, 0.01);
                    return ctx -> targetThreat(ctx) > threshold;
                }),
                // 仇恨表里有几个有效目标：对应 MM 里「周围还有 N 个人值得打」
                Map.entry("threat-targets-at-least", (SkillCatalog.ConditionFactory) a -> {
                    int n = (int) num(a, 0, 2);
                    return ctx -> {
                        var t = threatOf(ctx);
                        return t != null && t.size() >= n;
                    };
                }),
                Map.entry("skill-cooldown", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    double seconds = num(a, 1, 0);
                    return ctx -> globalCooldownReady(ctx, name, seconds);
                }),
                Map.entry("global-cooldown", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    double seconds = num(a, 1, 0);
                    return ctx -> globalCooldownReady(ctx, name, seconds);
                }),
                Map.entry("offgcd", (SkillCatalog.ConditionFactory) a ->
                        ctx -> globalCooldownReady(ctx, "__mythic_gcd", 1)),
                Map.entry("random-chance", (SkillCatalog.ConditionFactory) a -> {
                    // 参数是百分数：20 表示 20%
                    double percent = num(a, 0, 100);
                    if (!Double.isFinite(percent) || percent < 0 || percent > 100) {
                        throw new IllegalArgumentException("random-chance percent must be between 0 and 100");
                    }
                    return ctx -> java.util.concurrent.ThreadLocalRandom.current()
                            .nextDouble() * 100 < percent;
                }),
                Map.entry("distance-from-pin", (SkillCatalog.ConditionFactory) a -> {
                    String pin = pinArgument(a, "pin", "p");
                    PinDistanceMatcher limit = pinDistanceMatcher(a);
                    if (pin.isBlank() || limit == null) {
                        throw new IllegalArgumentException("distance-from-pin requires pin and a non-negative distance");
                    }
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        if (entity == null) return false;
                        var distance = nearestPinDistance(entity.getLocation(), pin);
                        return distance.isPresent() && limit.matches(distance.getAsDouble());
                    };
                }),
                Map.entry("origin-distance-from-pin", (SkillCatalog.ConditionFactory) a -> {
                    String pin = pinArgument(a, "pin", "p");
                    PinDistanceMatcher limit = pinDistanceMatcher(a);
                    if (pin.isBlank() || limit == null) {
                        throw new IllegalArgumentException("origin-distance-from-pin requires pin and a non-negative distance");
                    }
                    return ctx -> {
                        Location origin = selfLoc(ctx);
                        if (origin == null) return false;
                        var distance = nearestPinDistance(origin, pin);
                        return distance.isPresent() && limit.matches(distance.getAsDouble());
                    };
                }),
                Map.entry("in-pin-region", (SkillCatalog.ConditionFactory) a -> {
                    String first = pinArgument(a, "pin1", "p1");
                    String second = pinArgument(a, "pin2", "p2");
                    if (first.isBlank() || second.isBlank()) {
                        throw new IllegalArgumentException("in-pin-region requires pin1 and pin2");
                    }
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        if (entity == null) return false;
                        PinRegistry registry = pinRegistry;
                        if (registry == null) return false;
                        Location point = entity.getLocation();
                        for (Location a1 : registry.locations(first)) {
                            for (Location b1 : registry.locations(second)) {
                                if (a1.getWorld() == null || a1.getWorld() != b1.getWorld()
                                        || point.getWorld() != a1.getWorld()) continue;
                                if (point.getX() >= Math.min(a1.getX(), b1.getX())
                                        && point.getX() <= Math.max(a1.getX(), b1.getX())
                                        && point.getY() >= Math.min(a1.getY(), b1.getY())
                                        && point.getY() <= Math.max(a1.getY(), b1.getY())
                                        && point.getZ() >= Math.min(a1.getZ(), b1.getZ())
                                        && point.getZ() <= Math.max(a1.getZ(), b1.getZ())) return true;
                            }
                        }
                        return false;
                    };
                })
        );
    }

    private static String pinArgument(List<String> args, String... keys) {
        String value = keyedArg(args, keys);
        if (value != null) return value.trim();
        return str(args, 0, "").trim();
    }

    private record PinDistanceMatcher(NumberRange range, Comparison comparison) {
        boolean matches(double distance) {
            return range == null ? compare(distance, comparison) : range.contains(distance);
        }
    }

    private static PinDistanceMatcher pinDistanceMatcher(List<String> args) {
        String value = keyedArg(args, "distance", "d");
        if (value == null) value = str(args, 1, "");
        if (value.isBlank()) return null;
        if (value.toLowerCase(Locale.ROOT).contains("to") || value.indexOf('-', 1) > 0) {
            NumberRange range = numberRange(List.of(value), 0);
            if (range == null || Double.isNaN(range.min()) || Double.isNaN(range.max())
                    || range.min() > range.max()) return null;
            return new PinDistanceMatcher(range, null);
        }
        Comparison comparison = comparison(List.of(value), 0, "<=");
        return Double.isFinite(comparison.value())
                ? new PinDistanceMatcher(null, comparison) : null;
    }

    private static java.util.OptionalDouble nearestPinDistance(Location point, String name) {
        PinRegistry registry = pinRegistry;
        if (registry == null || point == null || point.getWorld() == null) return java.util.OptionalDouble.empty();
        double nearest = Double.POSITIVE_INFINITY;
        for (Location pin : registry.locations(name)) {
            if (pin.getWorld() == point.getWorld()) nearest = Math.min(nearest, point.distance(pin));
        }
        return Double.isFinite(nearest) ? java.util.OptionalDouble.of(nearest) : java.util.OptionalDouble.empty();
    }

    static boolean matchesTime(String raw, long worldTime) {
        String spec = raw == null ? "day" : raw.trim().toLowerCase(Locale.ROOT);
        long time = Math.floorMod(worldTime, 24_000L);
        if (spec.equals("day") || spec.equals("daytime")) return time < 12_000L;
        if (spec.equals("night") || spec.equals("nighttime")) return time >= 13_000L;
        if (spec.equals("dawn")) return time < 1_000L;
        if (spec.equals("morning")) return time >= 1_000L && time < 6_000L;
        if (spec.equals("noon")) return time >= 6_000L && time < 12_000L;
        if (spec.equals("dusk") || spec.equals("sunset")) return time >= 12_000L && time < 13_000L;
        if (spec.equals("midnight")) return time >= 18_000L && time < 19_000L;
        int dash = spec.indexOf('-');
        if (dash > 0 && dash < spec.length() - 1) {
            try {
                long from = Math.floorMod(Long.parseLong(spec.substring(0, dash).trim()), 24_000L);
                long to = Math.floorMod(Long.parseLong(spec.substring(dash + 1).trim()), 24_000L);
                return from <= to ? time >= from && time <= to : time >= from || time <= to;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        Comparison comparison = comparison(List.of(spec), 0, "=");
        return compare(time, comparison);
    }

    private static SkillCatalog.ConditionFactory timeAlias(String name) {
        return ignored -> ctx -> {
            Location location = selfLoc(ctx);
            if (location == null || location.getWorld() == null) return false;
            return matchesTime(name, location.getWorld().getTime());
        };
    }

    static boolean matchesWeather(String raw, boolean raining, boolean thundering) {
        String spec = raw == null ? "clear" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (spec) {
            case "clear", "sun", "sunny" -> !raining && !thundering;
            case "rain", "raining" -> raining && !thundering;
            case "storm", "thunder", "thunderstorm", "thundering" -> thundering;
            default -> false;
        };
    }

    static boolean isSnowingAt(Location location) {
        if (location == null || location.getWorld() == null) return false;
        try {
            return location.getWorld().hasStorm()
                    && location.getWorld().getBlockAt(location.getBlockX(), location.getBlockY(),
                    location.getBlockZ()).getTemperature() < 0.15;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static SkillCatalog.ConditionFactory snowingCondition() {
        return args -> {
            if (args != null && !args.isEmpty()) {
                throw new IllegalArgumentException("snowing takes no arguments");
            }
            return ctx -> isSnowingAt(conditionLocation(ctx));
        };
    }

    private static SkillCatalog.ConditionFactory weatherAlias(String name) {
        return ignored -> ctx -> {
            Location location = selfLoc(ctx);
            if (location == null || location.getWorld() == null) return false;
            return matchesWeather(name, location.getWorld().hasStorm(), location.getWorld().isThundering());
        };
    }

    private static boolean isPaperServer() {
        try {
            String name = org.bukkit.Bukkit.getName();
            String version = org.bukkit.Bukkit.getVersion();
            String identity = (name == null ? "" : name) + " "
                    + (version == null ? "" : version);
            String normalized = identity.toLowerCase(Locale.ROOT);
            return normalized.contains("paper") || normalized.contains("folia")
                    || normalized.contains("purpur");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean serverNmsVersionMatches(String expected) {
        String actual = detectedNmsVersion();
        return actual != null && actual.equalsIgnoreCase(expected);
    }

    private static String detectedNmsVersion() {
        try {
            String packageVersion = nmsVersionFromServerPackage(org.bukkit.Bukkit.getServer());
            if (packageVersion != null) return packageVersion;
        } catch (Throwable ignored) {
            // Some forks do not expose a versioned CraftBukkit package.
        }
        try {
            String minecraftVersion = org.bukkit.Bukkit.getMinecraftVersion();
            return KNOWN_NMS_VERSIONS.get(minecraftVersion == null
                    ? "" : minecraftVersion.trim());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String nmsVersionFromServerPackage(org.bukkit.Server server) {
        if (server == null) return null;
        for (String segment : server.getClass().getPackageName().split("\\.")) {
            if (CRAFTBUKKIT_VERSION_SEGMENT.matcher(segment).matches()) {
                return normalizeNmsVersion(segment);
            }
        }
        return null;
    }

    private static String normalizeNmsVersion(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (!NMS_VERSION_PATTERN.matcher(value).matches()) return null;
        return value.regionMatches(true, 0, "v", 0, 1)
                ? "v" + value.substring(1) : "v" + value;
    }

    private enum VersionCheck { EXACT, AFTER_OR_EQUAL }

    private static boolean serverVersionMatches(String expected, VersionCheck check) {
        int[] actual = parseServerVersion(org.bukkit.Bukkit.getMinecraftVersion());
        int[] wanted = parseServerVersion(expected);
        if (actual == null || wanted == null) return false;
        int length = Math.max(actual.length, wanted.length);
        for (int i = 0; i < length; i++) {
            int a = i < actual.length ? actual[i] : 0;
            int b = i < wanted.length ? wanted[i] : 0;
            if (a != b) return check == VersionCheck.AFTER_OR_EQUAL ? a > b : false;
        }
        return check == VersionCheck.EXACT || check == VersionCheck.AFTER_OR_EQUAL;
    }

    private static int[] parseServerVersion(String raw) {
        if (raw == null) return null;
        String[] parts = raw.trim().replaceFirst("^[vV]", "").split("\\.");
        int[] parsed = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                String digits = parts[i].replaceFirst("[^0-9].*$", "");
                if (digits.isBlank()) return null;
                parsed[i] = Integer.parseInt(digits);
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    static java.util.function.Consumer<BehaviorContext> branchUnlockAction(
            List<String> args,
            java.util.function.BiFunction<BehaviorContext, List<String>, Boolean> evaluator) {
        String branch = str(args, 0, "");
        String conditionText = args.size() <= 1 ? "" : String.join(" ", args.subList(1, args.size()));
        if (branch.startsWith("branch=")) branch = branch.substring("branch=".length()).trim();
        conditionText = conditionText.replaceFirst("^(?:condition|conditions)=", "").trim();
        final String branchName = branch;
        List<String> conditions = conditionText.isBlank() ? List.of()
                : conditionText.contains("||") ? List.of(conditionText)
                : java.util.Arrays.stream(conditionText.split("\\|"))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        return context -> {
            if (context == null || branchName.isBlank()) return;
            if (!conditions.isEmpty() && (evaluator == null
                    || !Boolean.TRUE.equals(evaluator.apply(context, conditions)))) return;
            context.skillTree().ifPresent(tree -> tree.unlockBranch(branchName));
        };
    }

    private static java.util.function.Consumer<BehaviorContext> mythicAuraAction(List<String> args) {
        validateAuraArguments(args, AURA_ACTION_ARGUMENTS);
        String configuredName = keyedArg(args, "auraname", "aura", "b", "buff", "buffname",
                "debuff", "debuffname", "n", "name");
        String configuredType = keyedArg(args, "auratype", "auragroup", "group", "type", "g");
        String durationRaw = keyedArg(args, "duration", "ticks", "d", "time", "t");
        String maxStacksRaw = keyedArg(args, "maxstacks", "ms");
        if (durationRaw != null && !durationRaw.contains("<")) parseAuraTicks(durationRaw, 200);
        int maxStacks = parseAuraCount(maxStacksRaw, 1);
        boolean mergeAll = auraBoolean(args, false, "mergeall", "ma");
        boolean overwriteSameCaster = auraBoolean(args, false, "overwritesamecaster", "osc", "oc");
        boolean overwriteAll = auraBoolean(args, false, "overwriteall", "overwrite", "ow");
        boolean defaultMergeSameCaster = !mergeAll && !overwriteSameCaster && !overwriteAll;
        boolean mergeSameCaster = auraBoolean(args, defaultMergeSameCaster, "mergesamecaster", "msc", "mc");
        boolean refreshDuration = auraBoolean(args, true, "refreshduration", "rd");
        if (overwriteSameCaster && overwriteAll
                || (overwriteSameCaster || overwriteAll) && (mergeSameCaster || mergeAll)) {
            throw new IllegalArgumentException("aura overwrite options cannot be combined with merge options");
        }

        return ctx -> {
            AuraRegistry store = auraRegistry;
            if (store == null || ctx == null) return;
            long duration = parseAuraTicks(durationRaw, 200, ctx);
            if (duration <= 0) return;
            String name = resolveAuraValue(configuredName, ctx);
            if (configuredName != null && name == null) return;
            if (name == null || name.isBlank()) name = "default";
            String type = resolveAuraValue(configuredType, ctx);
            if (configuredType != null && type == null) return;
            Entity caster = ctx.instanceValid() ? ctx.instance().baseEntity().orElse(null) : null;
            UUID casterId = caster == null ? null : caster.getUniqueId();
            for (LivingEntity target : targetEntities(ctx)) {
                if (target == null || !target.isValid() || target.isDead()) continue;
                try {
                    store.apply(target.getUniqueId(), casterId, name, type, duration, maxStacks,
                            refreshDuration, mergeSameCaster, mergeAll, overwriteSameCaster, overwriteAll);
                } catch (RuntimeException ignored) {
                    SkillFaults.swallow("SkillExtras#mythicAura");
                }
            }
        };
    }

    private static java.util.function.Consumer<BehaviorContext> mythicAuraRemoveAction(List<String> args) {
        validateAuraArguments(args, AURA_REMOVE_ARGUMENTS, "name", "stacks");
        String configuredName = keyedArg(args, "aura", "auraname", "b", "buff", "buffname",
                "debuff", "debuffname", "n", "name");
        List<String> positional = args == null ? List.of() : args.stream()
                .filter(arg -> arg != null && arg.indexOf('=') < 0).toList();
        if (configuredName == null) configuredName = positional.isEmpty() ? "default" : positional.get(0);
        String configuredStacks = keyedArg(args, "stacks", "s");
        if (configuredStacks == null && positional.size() > 1) configuredStacks = positional.get(1);
        if (configuredName.isBlank()) throw new IllegalArgumentException("aura name is required");
        if (configuredStacks != null && !configuredStacks.contains("<")) {
            parseAuraRemovalStacks(configuredStacks);
        }

        String nameSpec = configuredName;
        String stacksSpec = configuredStacks;
        return ctx -> {
            AuraRegistry store = auraRegistry;
            if (store == null || ctx == null) return;
            String name = resolveAuraValue(nameSpec, ctx);
            if (name == null || name.isBlank()) return;
            int stacks = Integer.MAX_VALUE;
            if (stacksSpec != null) {
                String resolved = Placeholders.resolve(stacksSpec, ctx);
                if (resolved == null || resolved.isBlank()
                        || stacksSpec.contains("<") && resolved.equals(stacksSpec)) return;
                try {
                    stacks = parseAuraRemovalStacks(resolved);
                } catch (IllegalArgumentException ignored) {
                    return;
                }
            }
            for (LivingEntity target : targetEntities(ctx)) {
                if (target == null || !target.isValid() || target.isDead()) continue;
                try {
                    store.remove(target.getUniqueId(), name, stacks);
                } catch (RuntimeException ignored) {
                    SkillFaults.swallow("SkillExtras#mythicAuraRemove");
                }
            }
        };
    }

    private static void validateAuraArguments(List<String> args,
                                              Map<String, String> aliases,
                                              String... positionalGroups) {
        Set<String> seen = new java.util.HashSet<>();
        int positionalIndex = 0;
        if (args == null) return;
        for (String raw : args) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("empty aura argument");
            }
            int equals = raw.indexOf('=');
            String canonical;
            if (equals < 0) {
                if (positionalIndex >= positionalGroups.length) {
                    throw new IllegalArgumentException("unsupported positional aura argument: " + raw);
                }
                canonical = positionalGroups[positionalIndex++];
            } else {
                if (equals == 0 || equals == raw.length() - 1) {
                    throw new IllegalArgumentException("invalid aura argument: " + raw);
                }
                String key = raw.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                canonical = aliases.get(key);
                if (canonical == null) throw new IllegalArgumentException("unsupported aura argument: " + key);
            }
            if (!seen.add(canonical)) throw new IllegalArgumentException("duplicate aura argument: " + canonical);
        }
    }

    private static boolean auraBoolean(List<String> args, boolean fallback, String... keys) {
        String raw = keyedArg(args, keys);
        if (raw == null) return fallback;
        if (raw.equalsIgnoreCase("true")) return true;
        if (raw.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException("invalid aura boolean: " + raw);
    }

    private static int parseAuraCount(String raw, int fallback) {
        if (raw == null) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1) throw new IllegalArgumentException("aura maxStacks must be positive");
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid aura maxStacks: " + raw, error);
        }
    }

    private static int parseAuraRemovalStacks(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1) throw new IllegalArgumentException("aura removal stacks must be positive");
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid aura removal stacks: " + raw, error);
        }
    }

    private static long parseAuraTicks(String raw, long fallback) {
        if (raw == null) return fallback;
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value) || value < 1 || value >= Long.MAX_VALUE) {
                throw new IllegalArgumentException("aura duration must be a positive tick count");
            }
            return (long) Math.ceil(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid aura duration: " + raw, error);
        }
    }

    private static long parseAuraTicks(String raw, long fallback, BehaviorContext context) {
        if (raw == null) return fallback;
        String resolved = Placeholders.resolve(raw, context);
        if (resolved == null || resolved.isBlank() || raw.contains("<") && resolved.equals(raw)) return -1;
        try {
            return parseAuraTicks(resolved, fallback);
        } catch (IllegalArgumentException ignored) {
            return -1;
        }
    }

    private static String resolveAuraValue(String raw, BehaviorContext context) {
        if (raw == null) return null;
        String resolved = Placeholders.resolve(raw, context);
        if (resolved == null || raw.contains("<") && resolved.equals(raw)) return null;
        return resolved.trim();
    }

    public static Map<String, SkillCatalog.ActionFactory> actions() {
        return Map.ofEntries(
                Map.entry("mythic-aura", (SkillCatalog.ActionFactory) SkillExtras::mythicAuraAction),
                Map.entry("mythic-aura-remove", (SkillCatalog.ActionFactory) SkillExtras::mythicAuraRemoveAction),
                Map.entry("move-pin", (SkillCatalog.ActionFactory) a -> {
                    String pin = keyedArg(a, "pin", "p");
                    if (pin == null) pin = str(a, 0, "");
                    String pinName = pin.trim();
                    return ctx -> {
                        PinRegistry registry = pinRegistry;
                        LivingEntity target = ctx == null ? null : ctx.targetEntity().orElse(null);
                        if (registry == null || pinName.isBlank() || target == null || !target.isValid()) return;
                        try {
                            registry.moveSingle(pinName, target.getLocation());
                        } catch (RuntimeException ignored) {
                            SkillFaults.swallow("SkillExtras#movePin");
                        }
                    };
                }),
                // ---- 跨技能信号 ----

                Map.entry("remove-self", (SkillCatalog.ActionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return;
                    Entity caster = ctx.instance().baseEntity().orElse(null);
                    if (caster != null && caster.isValid()) caster.remove();
                }),
                Map.entry("set-owner", (SkillCatalog.ActionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return;
                    Entity mob = ctx.instance().baseEntity().orElse(null);
                    Entity owner = ctx.targetEntity().orElse(null);
                    if (!(mob instanceof LivingEntity) || !(owner instanceof LivingEntity)) return;
                    dev.helstera.api.behavior.MobRelations.setOwner(mob, owner);
                }),
                Map.entry("remove-owner", (SkillCatalog.ActionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return;
                    Entity mob = ctx.instance().baseEntity().orElse(null);
                    dev.helstera.api.behavior.MobRelations.clearOwner(mob);
                }),
                Map.entry("set-faction", (SkillCatalog.ActionFactory) a -> {
                    String faction = keyedArg(a, "faction", "name", "f");
                    if (faction == null) faction = str(a, 0, "");
                    String value = normalizeFactionValue(faction);
                    return ctx -> {
                        if (ctx == null || !ctx.instanceValid()) return;
                        FactionSetter setter = factionSetter;
                        if (setter == null) return;
                        try { setter.set(ctx.instance().baseEntity().orElse(null), value); }
                        catch (RuntimeException ignored) { SkillFaults.swallow("SkillExtras#setFaction"); }
                    };
                }),
                Map.entry("set-faction-targets", (SkillCatalog.ActionFactory) a -> {
                    String faction = keyedArg(a, "faction", "name", "f");
                    if (faction == null) faction = str(a, 0, "");
                    String value = normalizeFactionValue(faction);
                    return ctx -> {
                        FactionSetter setter = factionSetter;
                        if (setter == null) return;
                        for (LivingEntity target : targetEntities(ctx)) {
                            try { setter.set(target, value); }
                            catch (RuntimeException ignored) { SkillFaults.swallow("SkillExtras#setFactionTargets"); }
                        }
                    };
                }),
                Map.entry("set-level", (SkillCatalog.ActionFactory) a -> {
                    var level = dynamicNum(a, 0, 1);
                    return ctx -> {
                        if (ctx == null || !ctx.instanceValid()
                                || !(ctx.instance() instanceof dev.helstera.runtime.instance.ModelInstanceImpl instance)) {
                            return;
                        }
                        try {
                            instance.level = Math.max(1, (int) Math.round(level.applyAsDouble(ctx)));
                        } catch (RuntimeException ignored) {
                            SkillFaults.swallow("SkillExtras#setLevel");
                        }
                    };
                }),
                Map.entry("set-weather", (SkillCatalog.ActionFactory) a -> {
                    String weather = keyedArg(a, "weather", "type", "w");
                    if (weather == null) weather = str(a, 0, "clear");
                    String durationRaw = keyedArg(a, "duration", "ticks", "time", "t");
                    if (durationRaw == null) durationRaw = str(a, 1, "0");
                    var duration = dynamicNum(List.of(durationRaw), 0, 0);
                    String mode = weather.trim().toLowerCase(Locale.ROOT);
                    return ctx -> {
                        Location location = selfLoc(ctx);
                        if (location == null || location.getWorld() == null) return;
                        org.bukkit.World world = location.getWorld();
                        int ticks = Math.max(0, (int) Math.round(duration.applyAsDouble(ctx)));
                        try {
                            switch (mode) {
                                case "clear", "sun", "sunny" -> {
                                    world.setStorm(false);
                                    world.setThundering(false);
                                }
                                case "rain", "raining" -> {
                                    world.setStorm(true);
                                    world.setThundering(false);
                                }
                                case "thunder", "thunderstorm", "storm", "thundering" -> {
                                    world.setStorm(true);
                                    world.setThundering(true);
                                }
                                default -> { return; }
                            }
                            if (ticks > 0) {
                                world.setWeatherDuration(ticks);
                                world.setThunderDuration(ticks);
                            }
                        } catch (RuntimeException ignored) {
                            SkillFaults.swallow("SkillExtras#setWeather");
                        }
                    };
                }),
                Map.entry("set-time", (SkillCatalog.ActionFactory) a -> {
                    String raw = keyedArg(a, "time", "ticks", "t");
                    if (raw == null) raw = str(a, 0, "day");
                    String value = raw.trim().toLowerCase(Locale.ROOT);
                    java.util.function.ToDoubleFunction<BehaviorContext> time;
                    if (value.contains("<")) {
                        time = ctx -> Placeholders.resolveNumber(value, ctx, 0);
                    } else {
                        long parsed = switch (value) {
                            case "dawn" -> 0L;
                            case "day" -> 1_000L;
                            case "noon" -> 6_000L;
                            case "dusk" -> 12_000L;
                            case "night" -> 13_000L;
                            case "midnight" -> 18_000L;
                            default -> {
                                try { yield Long.parseLong(value); }
                                catch (NumberFormatException e) {
                                    throw new IllegalArgumentException("invalid world time: " + raw, e);
                                }
                            }
                        };
                        time = ctx -> parsed;
                    }
                    return ctx -> {
                        Location location = selfLoc(ctx);
                        if (location == null || location.getWorld() == null) return;
                        try {
                            long ticks = (long) Math.round(time.applyAsDouble(ctx));
                            location.getWorld().setTime(Math.floorMod(ticks, 24_000L));
                        } catch (RuntimeException ignored) {
                            SkillFaults.swallow("SkillExtras#setTime");
                        }
                    };
                }),
                Map.entry("lightning", (SkillCatalog.ActionFactory) SkillExtras::lightningAction),
                Map.entry("lightning-targets", (SkillCatalog.ActionFactory) SkillExtras::lightningAction),
                Map.entry("strike-lightning-effect", (SkillCatalog.ActionFactory) a -> lightningAction(List.of("true"))),
                Map.entry("set-parent", (SkillCatalog.ActionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return;
                    Entity child = ctx.instance().baseEntity().orElse(null);
                    Entity parent = ctx.targetEntity().orElse(null);
                    if (!(child instanceof LivingEntity) || !(parent instanceof LivingEntity)) return;
                    dev.helstera.api.behavior.MobRelations.setParent(child, parent);
                }),
                Map.entry("remove-parent", (SkillCatalog.ActionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return;
                    dev.helstera.api.behavior.MobRelations.clearParent(
                            ctx.instance().baseEntity().orElse(null));
                }),
                Map.entry("add-tag", (SkillCatalog.ActionFactory) a -> {
                    String tag = scoreboardTag(a);
                    return ctx -> {
                        if (ctx == null || !ctx.instanceValid()) return;
                        addScoreboardTag(ctx.instance().baseEntity().orElse(null), tag);
                    };
                }),
                Map.entry("remove-tag", (SkillCatalog.ActionFactory) a -> {
                    String tag = scoreboardTag(a);
                    return ctx -> {
                        if (ctx == null || !ctx.instanceValid()) return;
                        removeScoreboardTag(ctx.instance().baseEntity().orElse(null), tag);
                    };
                }),
                Map.entry("add-tag-targets", (SkillCatalog.ActionFactory) a -> {
                    String tag = scoreboardTag(a);
                    return ctx -> targetEntities(ctx).forEach(entity -> addScoreboardTag(entity, tag));
                }),
                Map.entry("remove-tag-targets", (SkillCatalog.ActionFactory) a -> {
                    String tag = scoreboardTag(a);
                    return ctx -> targetEntities(ctx).forEach(entity -> removeScoreboardTag(entity, tag));
                }),
                Map.entry("equip", (SkillCatalog.ActionFactory) SkillExtras::equipmentAction),
                Map.entry("set-item", (SkillCatalog.ActionFactory) SkillExtras::equipmentAction),
                Map.entry("set-offhand", (SkillCatalog.ActionFactory) SkillExtras::setOffhandAction),
                Map.entry("set-offhand-targets", (SkillCatalog.ActionFactory)
                        SkillExtras::setOffhandTargetsAction),
                Map.entry("set-armor", (SkillCatalog.ActionFactory) SkillExtras::setArmorAction),
                Map.entry("set-armor-targets", (SkillCatalog.ActionFactory)
                        SkillExtras::setArmorTargetsAction),
                Map.entry("equip-targets", (SkillCatalog.ActionFactory) SkillExtras::equipmentTargetsAction),
                Map.entry("set-item-targets", (SkillCatalog.ActionFactory) SkillExtras::equipmentTargetsAction),
                Map.entry("drop-item", (SkillCatalog.ActionFactory) SkillExtras::dropItemAction),
                Map.entry("drop-item-targets", (SkillCatalog.ActionFactory) SkillExtras::dropItemTargetsAction),
                Map.entry("drop-items", (SkillCatalog.ActionFactory) args -> dropItemsAction(args, false)),
                Map.entry("drop-items-targets", (SkillCatalog.ActionFactory) args -> dropItemsAction(args, true)),
                Map.entry("give-item", (SkillCatalog.ActionFactory) SkillExtras::giveItemAction),
                Map.entry("take-item", (SkillCatalog.ActionFactory) SkillExtras::takeItemAction),
                Map.entry("disarm", (SkillCatalog.ActionFactory) SkillExtras::removeHeldItemAction),
                // MythicMobs' official name; keep disarm as the established alias.
                Map.entry("remove-held-item", (SkillCatalog.ActionFactory) SkillExtras::removeHeldItemAction),
                Map.entry("set-material-cooldown", (SkillCatalog.ActionFactory)
                        SkillExtras::setMaterialCooldownAction),
                Map.entry("set-game-mode", (SkillCatalog.ActionFactory) SkillExtras::setGameModeAction),
                Map.entry("swing", (SkillCatalog.ActionFactory) SkillExtras::swingAction),
                // Explicit aliases keep migrated hand-specific mechanics readable.
                Map.entry("swing-main-hand", (SkillCatalog.ActionFactory) a ->
                        ctx -> swingMainHandAction(ctx)),
                Map.entry("swing-off-hand", (SkillCatalog.ActionFactory) a ->
                        ctx -> swingOffHandAction(ctx)),
                Map.entry("feed", (SkillCatalog.ActionFactory) a -> {
                    var amount = dynamicNum(a, 0, 1);
                    return ctx -> {
                        int value = (int) Math.round(amount.applyAsDouble(ctx));
                        if (value == 0) return;
                        for (LivingEntity target : itemTargets(ctx)) {
                            if (!(target instanceof Player player)) continue;
                            int food = Math.max(0, Math.min(20, player.getFoodLevel() + value));
                            player.setFoodLevel(food);
                            float saturation = Math.max(0.0f,
                                    Math.min(food, player.getSaturation() + value));
                            player.setSaturation(saturation);
                        }
                    };
                }),
                Map.entry("close-inventory", (SkillCatalog.ActionFactory) a -> ctx -> {
                    for (LivingEntity target : itemTargets(ctx)) {
                        if (target instanceof org.bukkit.entity.HumanEntity human) {
                            try { human.closeInventory(); }
                            catch (RuntimeException ignored) { SkillFaults.swallow("SkillExtras#closeInventory"); }
                        }
                    }
                }),
                Map.entry("clear-inventory", (SkillCatalog.ActionFactory) a -> ctx -> {
                    for (LivingEntity target : itemTargets(ctx)) {
                        if (!(target instanceof Player player)) continue;
                        try { player.getInventory().clear(); }
                        catch (RuntimeException ignored) { SkillFaults.swallow("SkillExtras#clearInventory"); }
                    }
                }),
                Map.entry("clear-experience", (SkillCatalog.ActionFactory) a -> ctx -> {
                    for (LivingEntity target : itemTargets(ctx)) {
                        if (target instanceof Player player) {
                            try { player.setExp(0.0f); }
                            catch (RuntimeException ignored) { SkillFaults.swallow("SkillExtras#clearExperience"); }
                        }
                    }
                }),
                Map.entry("clear-experience-levels", (SkillCatalog.ActionFactory) a -> ctx -> {
                    for (LivingEntity target : itemTargets(ctx)) {
                        if (target instanceof Player player) {
                            try {
                                player.setLevel(0);
                                player.setExp(0.0f);
                            } catch (RuntimeException ignored) {
                                SkillFaults.swallow("SkillExtras#clearExperienceLevels");
                            }
                        }
                    }
                }),
                Map.entry("give-experience-levels", (SkillCatalog.ActionFactory) a -> {
                    var amount = dynamicNum(a, 0, 0);
                    return ctx -> {
                        int levels = (int) Math.round(amount.applyAsDouble(ctx));
                        if (levels == 0) return;
                        for (LivingEntity target : itemTargets(ctx)) {
                            if (target instanceof Player player) {
                                try { player.giveExpLevels(levels); }
                                catch (RuntimeException ignored) { SkillFaults.swallow("SkillExtras#giveExperienceLevels"); }
                            }
                        }
                    };
                }),
                Map.entry("give-experience", (SkillCatalog.ActionFactory) a -> {
                    var amount = dynamicNum(a, 0, 0);
                    return ctx -> {
                        int experience = (int) Math.round(amount.applyAsDouble(ctx));
                        if (experience == 0) return;
                        for (LivingEntity target : itemTargets(ctx)) {
                            if (!(target instanceof Player player)) continue;
                            try { player.giveExp(experience); }
                            catch (RuntimeException ignored) { SkillFaults.swallow("SkillExtras#giveExperience"); }
                        }
                    };
                }),
                Map.entry("set-absorption", (SkillCatalog.ActionFactory) a -> {
                    var amount = dynamicNum(a, 0, 0);
                    return ctx -> {
                        double value = Math.max(0.0, amount.applyAsDouble(ctx));
                        for (LivingEntity target : itemTargets(ctx)) {
                            if (!(target instanceof org.bukkit.entity.Damageable damageable)) continue;
                            ensureAbsorptionCapacity(target, value);
                            try { damageable.setAbsorptionAmount(value); }
                            catch (RuntimeException ignored) { SkillFaults.swallow("SkillExtras#setAbsorption"); }
                        }
                    };
                }),
                Map.entry("oxygen", (SkillCatalog.ActionFactory) a -> {
                    var amount = dynamicNum(a, 0, 1);
                    return ctx -> {
                        int value = (int) Math.round(amount.applyAsDouble(ctx));
                        if (value == 0) return;
                        for (LivingEntity target : itemTargets(ctx)) {
                            if (!(target instanceof Player player)) continue;
                            try {
                                int air = Math.max(0, Math.min(player.getMaximumAir(),
                                        player.getRemainingAir() + value));
                                player.setRemainingAir(air);
                            } catch (RuntimeException ignored) {
                                SkillFaults.swallow("SkillExtras#oxygen");
                            }
                        }
                    };
                }),
                Map.entry("consume-slot", (SkillCatalog.ActionFactory) a -> {
                    String slot = keyedArg(a, "slot", "s");
                    if (slot == null) slot = str(a, 0, "HAND");
                    String amountRaw = keyedArg(a, "amount", "a");
                    if (amountRaw == null) amountRaw = str(a, 1, "1");
                    var amount = dynamicNum(List.of(amountRaw), 0, 1);
                    final String selectedSlot = slot;
                    final var amountResolver = amount;
                    return ctx -> {
                        int requested = Math.max(1, (int) Math.round(amountResolver.applyAsDouble(ctx)));
                        for (LivingEntity target : itemTargets(ctx)) {
                            if (!(target instanceof Player player)) continue;
                            ItemStack current = inventoryItem(player, selectedSlot);
                            if (current == null) continue;
                            int remaining = current.getAmount() - requested;
                            if (remaining > 0) {
                                current.setAmount(remaining);
                                setInventoryItem(player, selectedSlot, current);
                            } else {
                                setInventoryItem(player, selectedSlot, null);
                            }
                        }
                    };
                }),
                Map.entry("set-block", (SkillCatalog.ActionFactory) SkillExtras::setBlockAction),
                Map.entry("break-block", (SkillCatalog.ActionFactory) SkillExtras::breakBlockAction),
                Map.entry("mount", (SkillCatalog.ActionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return;
                    Entity caster = ctx.instance().baseEntity().orElse(null);
                    Entity target = ctx.targetEntity().orElse(null);
                    if (caster == null || target == null || caster == target) return;
                    try {
                        caster.addPassenger(target);
                    } catch (RuntimeException ignored) {
                        SkillFaults.swallow("SkillExtras#1410");
                    }
                }),
                Map.entry("dismount", (SkillCatalog.ActionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return;
                    Entity caster = ctx.instance().baseEntity().orElse(null);
                    if (caster == null || !caster.isInsideVehicle()) return;
                    try {
                        caster.leaveVehicle();
                    } catch (RuntimeException ignored) {
                        SkillFaults.swallow("SkillExtras#1415");
                    }
                }),
                Map.entry("eject-passenger", (SkillCatalog.ActionFactory) a -> ctx -> {
                    Entity caster = casterEntity(ctx);
                    if (caster == null) return;
                    try { caster.eject(); }
                    catch (RuntimeException ignored) { SkillFaults.swallow("SkillExtras#ejectPassenger"); }
                }),
                Map.entry("cancel-event", (SkillCatalog.ActionFactory) a -> ctx ->
                        ctx.cancellableEvent().ifPresent(event -> event.setCancelled(true))),
                Map.entry("signal", (SkillCatalog.ActionFactory) a -> {
                    String parsedName = keyedArg(a, "signal", "name", "s");
                    if (parsedName == null) parsedName = str(a, 0, "");
                    String parsedPayload = keyedArg(a, "payload", "data", "p");
                    if (parsedPayload == null && a.size() > 1) parsedPayload = str(a, 1, "");
                    String scope = keyedArg(a, "global", "broadcast", "g");
                    // 第三个参数写 global 则广播；默认只发给自己实例
                    boolean global = "true".equalsIgnoreCase(scope)
                            || "global".equalsIgnoreCase(scope)
                            || "broadcast".equalsIgnoreCase(scope)
                            || a.size() > 2 && "global".equalsIgnoreCase(str(a, 2, ""));
                    final String name = parsedName == null ? "" : parsedName;
                    final String payload = parsedPayload;
                    final boolean globalScope = global;
                    return ctx -> {
                        if (name.isEmpty() || !ctx.instanceValid()) return;
                        int id = ctx.instance().instanceId();
                        SkillSignals.global().emit(globalScope ? null : id, id, name, payload);
                    };
                }),

                // ---- 数值修改 ----

                Map.entry("set-health", (SkillCatalog.ActionFactory) a -> {
                    var v = dynamicNum(a, 0, 0);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            double value = v.applyAsDouble(ctx);
                            e.setHealth(Math.max(0.1, Math.min(e.getMaxHealth(), value)));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#407");
                        }
                    });
                }),
                Map.entry("set-health-targets", (SkillCatalog.ActionFactory) a -> {
                    var value = dynamicNum(a, 0, 0);
                    return ctx -> {
                        double health = value.applyAsDouble(ctx);
                        for (LivingEntity target : targetEntities(ctx)) {
                            try { target.setHealth(Math.max(0.1, Math.min(target.getMaxHealth(), health))); }
                            catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setHealthTargets"); }
                        }
                    };
                }),
                Map.entry("set-max-health", (SkillCatalog.ActionFactory) a -> {
                    var v = dynamicNum(a, 0, 20);
                    return ctx -> selfLiving(ctx).ifPresent(entity -> {
                        try {
                            var attribute = entity.getAttribute(
                                    org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                            if (attribute == null) return;
                            double maximum = Math.max(0.1, v.applyAsDouble(ctx));
                            attribute.setBaseValue(maximum);
                            if (entity.getHealth() > maximum) entity.setHealth(maximum);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#setMaxHealth");
                        }
                    });
                }),
                Map.entry("set-max-health-targets", (SkillCatalog.ActionFactory) a -> {
                    var value = dynamicNum(a, 0, 20);
                    return ctx -> {
                        double maximum = Math.max(0.1, value.applyAsDouble(ctx));
                        for (LivingEntity target : targetEntities(ctx)) {
                            try {
                                var attribute = target.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                                if (attribute == null) continue;
                                attribute.setBaseValue(maximum);
                                if (target.getHealth() > maximum) target.setHealth(maximum);
                            } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setMaxHealthTargets"); }
                        }
                    };
                }),
                Map.entry("modify-max-health", (SkillCatalog.ActionFactory) a -> {
                    var v = dynamicNum(a, 0, 0);
                    return ctx -> selfLiving(ctx).ifPresent(entity -> {
                        try {
                            var attribute = entity.getAttribute(
                                    org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                            if (attribute == null) return;
                            double maximum = Math.max(0.1, attribute.getBaseValue() + v.applyAsDouble(ctx));
                            attribute.setBaseValue(maximum);
                            if (entity.getHealth() > maximum) entity.setHealth(maximum);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#modifyMaxHealth");
                        }
                    });
                }),
                Map.entry("modify-max-health-targets", (SkillCatalog.ActionFactory) a -> {
                    var delta = dynamicNum(a, 0, 0);
                    return ctx -> {
                        double change = delta.applyAsDouble(ctx);
                        for (LivingEntity target : targetEntities(ctx)) {
                            try {
                                var attribute = target.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                                if (attribute == null) continue;
                                double maximum = Math.max(0.1, attribute.getBaseValue() + change);
                                attribute.setBaseValue(maximum);
                                if (target.getHealth() > maximum) target.setHealth(maximum);
                            } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#modifyMaxHealthTargets"); }
                        }
                    };
                }),
                Map.entry("modify-health", (SkillCatalog.ActionFactory) a -> {
                    var delta = dynamicNum(a, 0, 0);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            double value = delta.applyAsDouble(ctx);
                            e.setHealth(Math.max(0.1,
                                    Math.min(e.getMaxHealth(), e.getHealth() + value)));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#417");
                        }
                    });
                }),
                Map.entry("modify-health-targets", (SkillCatalog.ActionFactory) a -> {
                    var delta = dynamicNum(a, 0, 0);
                    return ctx -> {
                        double change = delta.applyAsDouble(ctx);
                        for (LivingEntity target : targetEntities(ctx)) {
                            try {
                                target.setHealth(Math.max(0.1,
                                        Math.min(target.getMaxHealth(), target.getHealth() + change)));
                            } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#modifyHealthTargets"); }
                        }
                    };
                }),
                Map.entry("heal-percent", (SkillCatalog.ActionFactory) a -> {
                    var pct = dynamicNum(a, 0, 10);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            double heal = e.getMaxHealth() * pct.applyAsDouble(ctx) / 100.0;
                            e.setHealth(Math.min(e.getMaxHealth(), e.getHealth() + heal));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#427");
                        }
                    });
                }),
                Map.entry("heal-percent-targets", (SkillCatalog.ActionFactory) a -> {
                    var pct = dynamicNum(a, 0, 10);
                    return ctx -> {
                        double percent = pct.applyAsDouble(ctx);
                        for (LivingEntity target : targetEntities(ctx)) {
                            try {
                                double maximum = target.getMaxHealth();
                                if (maximum > 0) {
                                    double heal = maximum * percent / 100.0;
                                    target.setHealth(Math.min(maximum, target.getHealth() + heal));
                                }
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#healPercentTargets");
                            }
                        }
                    };
                }),
                Map.entry("modify-speed", (SkillCatalog.ActionFactory) a -> {
                    var factor = dynamicNum(a, 0, 1.0);
                    return ctx -> selfLiving(ctx).ifPresent(e ->
                            scaleAttribute(e, org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED,
                                    factor.applyAsDouble(ctx)));
                }),
                Map.entry("modify-speed-targets", (SkillCatalog.ActionFactory) a -> {
                    var factor = dynamicNum(a, 0, 1.0);
                    return ctx -> {
                        double value = factor.applyAsDouble(ctx);
                        targetEntities(ctx).forEach(entity -> scaleAttribute(entity,
                                org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED, value));
                    };
                }),
                Map.entry("set-tick-speed", (SkillCatalog.ActionFactory) a -> {
                    var factor = dynamicNum(a, 0, 1.0);
                    return ctx -> selfLiving(ctx).ifPresent(e ->
                            scaleAttribute(e, org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED,
                                    factor.applyAsDouble(ctx)));
                }),
                Map.entry("set-tick-speed-targets", (SkillCatalog.ActionFactory) a -> {
                    var factor = dynamicNum(a, 0, 1.0);
                    return ctx -> {
                        double value = factor.applyAsDouble(ctx);
                        targetEntities(ctx).forEach(entity -> scaleAttribute(entity,
                                org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED, value));
                    };
                }),

                // ---- 状态开关 ----

                Map.entry("invisible", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setInvisible(on);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#449");
                        }
                    });
                }),
                Map.entry("invisible-targets", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> targetEntities(ctx).forEach(entity -> {
                        try { entity.setInvisible(on); } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#invisibleTargets"); }
                    });
                }),
                Map.entry("glowing", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setGlowing(on);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#458");
                        }
                    });
                }),
                Map.entry("glowing-targets", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> targetEntities(ctx).forEach(entity -> {
                        try { entity.setGlowing(on); } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#glowingTargets"); }
                    });
                }),
                Map.entry("set-invulnerable", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setInvulnerable(on);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#467");
                        }
                    });
                }),
                Map.entry("set-invulnerable-targets", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> targetEntities(ctx).forEach(entity -> {
                        try { entity.setInvulnerable(on); } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#invulnerableTargets"); }
                    });
                }),
                Map.entry("set-ai", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        if (!(e instanceof org.bukkit.entity.Mob mob)) return;
                        try { mob.setAI(on); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#478"); }
                    });
                }),
                Map.entry("set-ai-targets", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> targetEntities(ctx).forEach(entity -> {
                        if (!(entity instanceof org.bukkit.entity.Mob mob)) return;
                        try { mob.setAI(on); } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setAiTargets"); }
                    });
                }),
                Map.entry("set-gravity", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> {
                        if (ctx == null || !ctx.instanceValid()) return;
                        var entity = ctx.instance().baseEntity().orElse(null);
                        if (entity == null) return;
                        try { entity.setGravity(on); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#487"); }
                    };
                }),
                Map.entry("set-gravity-targets", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> targetEntities(ctx).forEach(entity -> {
                        try { entity.setGravity(on); } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setGravityTargets"); }
                    });
                }),
                Map.entry("set-silent", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> {
                        if (ctx == null || !ctx.instanceValid()) return;
                        var entity = ctx.instance().baseEntity().orElse(null);
                        if (entity == null) return;
                        try { entity.setSilent(on); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#496"); }
                    };
                }),
                Map.entry("set-silent-targets", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> targetEntities(ctx).forEach(entity -> {
                        try { entity.setSilent(on); } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setSilentTargets"); }
                    });
                }),
                Map.entry("set-collidable", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try { e.setCollidable(on); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#505"); }
                    });
                }),
                Map.entry("set-collidable-targets", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> targetEntities(ctx).forEach(entity -> {
                        try { entity.setCollidable(on); } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setCollidableTargets"); }
                    });
                }),
                Map.entry("set-sneaking", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).ifPresent(entity -> {
                        try { entity.setSneaking(on); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setSneaking"); }
                    });
                }),
                Map.entry("set-sneaking-targets", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> targetEntities(ctx).forEach(entity -> {
                        try { entity.setSneaking(on); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setSneakingTargets"); }
                    });
                }),
                Map.entry("set-gliding", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).ifPresent(entity -> {
                        try { entity.setGliding(on); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setGliding"); }
                    });
                }),
                Map.entry("set-gliding-targets", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> targetEntities(ctx).forEach(entity -> {
                        try { entity.setGliding(on); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setGlidingTargets"); }
                    });
                }),
                Map.entry("set-swimming", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).ifPresent(entity -> {
                        try { entity.setSwimming(on); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setSwimming"); }
                    });
                }),
                Map.entry("set-swimming-targets", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> targetEntities(ctx).forEach(entity -> {
                        try { entity.setSwimming(on); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setSwimmingTargets"); }
                    });
                }),
                Map.entry("set-sprinting", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).filter(Player.class::isInstance).map(Player.class::cast)
                            .ifPresent(player -> {
                                try { player.setSprinting(on); }
                                catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setSprinting"); }
                            });
                }),
                Map.entry("set-sprinting-targets", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> targetEntities(ctx).forEach(entity -> {
                        if (!(entity instanceof Player player)) return;
                        try { player.setSprinting(on); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setSprintingTargets"); }
                    });
                }),
                Map.entry("set-flying", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> selfLiving(ctx).filter(Player.class::isInstance).map(Player.class::cast)
                            .ifPresent(player -> {
                                try { player.setFlying(on); }
                                catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setFlying"); }
                            });
                }),
                Map.entry("set-flying-targets", (SkillCatalog.ActionFactory) a -> {
                    boolean on = bool(a, 0, true);
                    return ctx -> targetEntities(ctx).forEach(entity -> {
                        if (!(entity instanceof Player player)) return;
                        try { player.setFlying(on); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setFlyingTargets"); }
                    });
                }),
                Map.entry("set-no-damage-ticks", (SkillCatalog.ActionFactory) a -> {
                    var ticks = dynamicNum(a, 0, 0);
                    return ctx -> selfLiving(ctx).ifPresent(entity -> {
                        try { entity.setNoDamageTicks(Math.max(0, (int) ticks.applyAsDouble(ctx))); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setNoDamageTicks"); }
                    });
                }),
                Map.entry("set-no-damage-ticks-targets", (SkillCatalog.ActionFactory) a -> {
                    var ticks = dynamicNum(a, 0, 0);
                    return ctx -> {
                        int value = Math.max(0, (int) ticks.applyAsDouble(ctx));
                        targetEntities(ctx).forEach(entity -> {
                            try { entity.setNoDamageTicks(value); }
                            catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setNoDamageTicksTargets"); }
                        });
                    };
                }),
                Map.entry("set-maximum-no-damage-ticks", (SkillCatalog.ActionFactory) a -> {
                    var ticks = dynamicNum(a, 0, 20);
                    return ctx -> selfLiving(ctx).ifPresent(entity -> {
                        try { entity.setMaximumNoDamageTicks(Math.max(0, (int) ticks.applyAsDouble(ctx))); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setMaximumNoDamageTicks"); }
                    });
                }),
                Map.entry("set-maximum-no-damage-ticks-targets", (SkillCatalog.ActionFactory) a -> {
                    var ticks = dynamicNum(a, 0, 20);
                    return ctx -> {
                        int value = Math.max(0, (int) ticks.applyAsDouble(ctx));
                        targetEntities(ctx).forEach(entity -> {
                            try { entity.setMaximumNoDamageTicks(value); }
                            catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setMaximumNoDamageTicksTargets"); }
                        });
                    };
                }),
                Map.entry("set-walk-speed", (SkillCatalog.ActionFactory) a -> {
                    var speed = dynamicNum(a, 0, 0.2);
                    return ctx -> selfLiving(ctx).filter(Player.class::isInstance).map(Player.class::cast)
                            .ifPresent(player -> {
                                try {
                                    player.setWalkSpeed((float) Math.max(-1.0,
                                            Math.min(1.0, speed.applyAsDouble(ctx))));
                                } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setWalkSpeed"); }
                            });
                }),
                Map.entry("set-walk-speed-targets", (SkillCatalog.ActionFactory) a -> {
                    var speed = dynamicNum(a, 0, 0.2);
                    return ctx -> {
                        float value = (float) Math.max(-1.0,
                                Math.min(1.0, speed.applyAsDouble(ctx)));
                        targetEntities(ctx).forEach(entity -> {
                            if (!(entity instanceof Player player)) return;
                            try { player.setWalkSpeed(value); }
                            catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setWalkSpeedTargets"); }
                        });
                    };
                }),
                Map.entry("set-fire", (SkillCatalog.ActionFactory) a -> {
                    var ticks = dynamicNum(a, 0, 40);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try { e.setFireTicks(Math.max(0, (int) ticks.applyAsDouble(ctx))); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#514"); }
                    });
                }),
                Map.entry("set-fire-targets", (SkillCatalog.ActionFactory) a -> {
                    var ticks = dynamicNum(a, 0, 40);
                    return ctx -> {
                        int value = Math.max(0, (int) ticks.applyAsDouble(ctx));
                        targetEntities(ctx).forEach(entity -> {
                            try { entity.setFireTicks(value); } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setFireTargets"); }
                        });
                    };
                }),
                Map.entry("clear-fire", (SkillCatalog.ActionFactory) a ->
                        ctx -> selfLiving(ctx).ifPresent(e -> {
                            try { e.setFireTicks(0); }
                            catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#523"); }
                        })),
                Map.entry("clear-fire-targets", (SkillCatalog.ActionFactory) a ->
                        ctx -> targetEntities(ctx).forEach(entity -> {
                            try { entity.setFireTicks(0); } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#clearFireTargets"); }
                        })),
                Map.entry("freeze", (SkillCatalog.ActionFactory) a -> {
                    var ticks = dynamicNum(a, 0, 140);
                    return ctx -> {
                        int value = Math.max(0, (int) Math.round(ticks.applyAsDouble(ctx)));
                        for (LivingEntity entity : itemTargets(ctx)) {
                            try { entity.setFreezeTicks(value); }
                            catch (RuntimeException ignored) { SkillFaults.swallow("SkillExtras#freeze"); }
                        }
                    };
                }),

                // ---- 位移 ----

                Map.entry("jump", (SkillCatalog.ActionFactory) SkillExtras::jumpAction),
                Map.entry("jump-targets", (SkillCatalog.ActionFactory) SkillExtras::jumpTargetsAction),

                Map.entry("pull", (SkillCatalog.ActionFactory) a -> {
                    var radius = dynamicNum(a, 0, 6);
                    var power = dynamicNum(a, 1, 1.0);
                    return ctx -> {
                        Location me = selfLoc(ctx);
                        if (me == null) return;
                        double range = radius.applyAsDouble(ctx);
                        double strength = power.applyAsDouble(ctx);
                        Entity source = ctx != null && ctx.instanceValid()
                                ? ctx.instance().baseEntity().orElse(null) : null;
                        for (LivingEntity e : nearby(me, range)) {
                            if (e == source) continue;
                            try {
                                var v = me.toVector()
                                        .subtract(e.getLocation().toVector())
                                        .normalize().multiply(strength);
                                e.setVelocity(v);
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#486");
                            }
                        }
                    };
                }),
                Map.entry("force-pull", (SkillCatalog.ActionFactory) SkillExtras::forcePullAction),
                Map.entry("force-pull-targets", (SkillCatalog.ActionFactory) SkillExtras::forcePullAction),
                Map.entry("leap", (SkillCatalog.ActionFactory) a -> {
                    var power = dynamicNum(a, 0, 1.2);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setVelocity(e.getVelocity().setY(power.applyAsDouble(ctx)));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#496");
                        }
                    });
                }),
                Map.entry("leap-targets", (SkillCatalog.ActionFactory) a -> {
                    var power = dynamicNum(a, 0, 1.2);
                    return ctx -> {
                        double value = power.applyAsDouble(ctx);
                        targetEntities(ctx).forEach(entity -> {
                            try { entity.setVelocity(entity.getVelocity().setY(value)); }
                            catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#leapTargets"); }
                        });
                    };
                }),
                Map.entry("teleport-self", (SkillCatalog.ActionFactory) a -> {
                    var dx = dynamicNum(a, 0, 0);
                    var dy = dynamicNum(a, 1, 0);
                    var dz = dynamicNum(a, 2, 0);
                    return ctx -> {
                        var self = ctx != null && ctx.instanceValid()
                                ? ctx.instance().baseEntity().orElse(null) : null;
                        Location me = selfLoc(ctx);
                        if (self == null || me == null) return;
                        try {
                            self.teleport(me.clone().add(dx.applyAsDouble(ctx), dy.applyAsDouble(ctx),
                                    dz.applyAsDouble(ctx)));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#511");
                        }
                    };
                }),
                Map.entry("teleport", (SkillCatalog.ActionFactory) a -> {
                    String destination = str(a, 0, "@origin");
                    return ctx -> {
                        var self = ctx != null && ctx.instanceValid()
                                ? ctx.instance().baseEntity().orElse(null) : null;
                        if (self == null) return;
                        var location = dev.helstera.api.behavior.Targeters.resolveLocation(destination, ctx).orElse(null);
                        if (location == null) return;
                        try { self.teleport(location); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#530"); }
                    };
                }),
                Map.entry("teleport-caster", (SkillCatalog.ActionFactory) a -> {
                    String destination = str(a, 0, "@origin");
                    return ctx -> {
                        var self = ctx != null && ctx.instanceValid()
                                ? ctx.instance().baseEntity().orElse(null) : null;
                        var location = dev.helstera.api.behavior.Targeters.resolveLocation(destination, ctx).orElse(null);
                        if (self == null || location == null) return;
                        try { self.teleport(location); }
                        catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#531"); }
                    };
                }),
                Map.entry("teleport-mythic", (SkillCatalog.ActionFactory) a -> {
                    var spreadH = teleportSpread(a, 0, 0, "spreadh", "spread-horizontal", "horizontalspread");
                    var spreadV = teleportSpread(a, 1, 0, "spreadv", "spread-vertical", "verticalspread");
                    boolean unsafe = teleportBoolean(a, 2, false, "unsafe");
                    String yaw = teleportOrientation(a, 3, "preserve", "yaw");
                    String pitch = teleportOrientation(a, 4, "preserve", "pitch");
                    return ctx -> executeMythicTeleport(ctx, spreadH, spreadV, unsafe, yaw, pitch);
                }),
                Map.entry("look-at", (SkillCatalog.ActionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return;
                    Entity self = ctx.instance().baseEntity().orElse(null);
                    LivingEntity target = targetEntities(ctx).stream().findFirst().orElse(null);
                    if (self == null || target == null) return;
                    rotateToward(self, target.getLocation());
                }),
                Map.entry("set-rotation", (SkillCatalog.ActionFactory) a -> {
                    var yaw = dynamicNum(a, 0, 0);
                    var pitch = dynamicNum(a, 1, 0);
                    return ctx -> {
                        if (ctx == null || !ctx.instanceValid()) return;
                        Entity self = ctx.instance().baseEntity().orElse(null);
                        if (self == null) return;
                        try {
                            Location location = self.getLocation().clone();
                            location.setYaw((float) yaw.applyAsDouble(ctx));
                            location.setPitch((float) pitch.applyAsDouble(ctx));
                            self.teleport(location);
                        } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#540"); }
                    };
                }),

                // ---- 伤害变体 ----

                Map.entry("damage-percent", (SkillCatalog.ActionFactory) a -> {
                    var pct = dynamicNum(a, 0, 10);
                    return ctx -> {
                        LivingEntity t = ctx.targetEntity().orElse(null);
                        double percent = pct.applyAsDouble(ctx);
                        if (t == null || percent <= 0) return;
                        applyDamage(ctx, t, t.getMaxHealth() * percent / 100.0, "MAGIC");
                    };
                }),
                Map.entry("damage-percent-targets", (SkillCatalog.ActionFactory) a -> {
                    var pct = dynamicNum(a, 0, 10);
                    return ctx -> {
                        double percent = pct.applyAsDouble(ctx);
                        if (percent <= 0) return;
                        for (LivingEntity target : targetEntities(ctx)) {
                            applyDamage(ctx, target, target.getMaxHealth() * percent / 100.0, "MAGIC");
                        }
                    };
                }),
                Map.entry("damage-melee", (SkillCatalog.ActionFactory) a -> {
                    var dmg = dynamicNum(a, 0, 3);
                    double radius = num(a, 1, 2.5);
                    return ctx -> {
                        Location me = selfLoc(ctx);
                        double amount = dmg.applyAsDouble(ctx);
                        if (me == null || amount <= 0) return;
                        var src = ctx.instanceValid() ? ctx.instance().baseEntity().orElse(null) : null;
                        for (LivingEntity e : nearby(me, radius, src)) {
                            try {
                                applyDamage(ctx, e, amount, "ENTITY_ATTACK");
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#541");
                            }
                        }
                    };
                }),
                Map.entry("dot", (SkillCatalog.ActionFactory) a -> {
                    return dotFactory(a, false);
                }),
                Map.entry("dot-targets", (SkillCatalog.ActionFactory) a -> {
                    return dotFactory(a, true);
                }),
                Map.entry("suicide", (SkillCatalog.ActionFactory) a ->
                        ctx -> selfLiving(ctx).ifPresent(e -> {
                            try {
                                e.setHealth(0);
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#574");
                            }
                        })),
                Map.entry("kill-targets", (SkillCatalog.ActionFactory) a ->
                        ctx -> targetEntities(ctx).forEach(target -> {
                            try {
                                if (target.isValid() && !target.isDead()) target.setHealth(0);
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#killTargets");
                            }
                        })),

                // ---- 增益 / 减益 ----

                Map.entry("effect-self", (SkillCatalog.ActionFactory) a -> {
                    String potion = str(a, 0, "");
                    var ticks = dynamicNum(a, 1, 60);
                    var amp = dynamicNum(a, 2, 0);
                    boolean ambient = bool(a, 3, false);
                    boolean particles = bool(a, 4, true);
                    boolean icon = bool(a, 5, true);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        PotionEffectType type = potionType(potion);
                        int duration = (int) ticks.applyAsDouble(ctx);
                        int amplifier = (int) amp.applyAsDouble(ctx);
                        if (type == null || duration <= 0) return;
                        try {
                            e.addPotionEffect(new PotionEffect(type, duration, amplifier,
                                    ambient, particles, icon));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#589");
                        }
                    });
                }),
                Map.entry("remove-effect", (SkillCatalog.ActionFactory) a -> {
                    String potion = str(a, 0, "");
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        PotionEffectType type = potionType(potion);
                        if (type == null) return;
                        try {
                            if (e.hasPotionEffect(type)) {
                                e.removePotionEffect(type);
                                notifyPotionEffectEnd(ctx, e, type);
                            }
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#600");
                        }
                    });
                }),
                Map.entry("remove-effect-targets", (SkillCatalog.ActionFactory) a -> {
                    String potion = str(a, 0, "");
                    return ctx -> {
                        PotionEffectType type = potionType(potion);
                        if (type == null) return;
                        for (LivingEntity target : targetEntities(ctx)) {
                            try {
                                if (target.hasPotionEffect(type)) {
                                    target.removePotionEffect(type);
                                    notifyPotionEffectEnd(ctx, target, type);
                                }
                            }
                            catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#removeEffectTargets"); }
                        }
                    };
                }),
                Map.entry("remove-all-effects", (SkillCatalog.ActionFactory) a ->
                        ctx -> selfLiving(ctx).ifPresent(e -> {
                            try {
                                for (var eff : new ArrayList<>(e.getActivePotionEffects())) {
                                    e.removePotionEffect(eff.getType());
                                    notifyPotionEffectEnd(ctx, e, eff.getType());
                                }
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#610");
                            }
                        })),
                Map.entry("remove-all-effects-targets", (SkillCatalog.ActionFactory) a ->
                        ctx -> targetEntities(ctx).forEach(entity -> {
                            try {
                                for (var eff : new ArrayList<>(entity.getActivePotionEffects())) {
                                    entity.removePotionEffect(eff.getType());
                                    notifyPotionEffectEnd(ctx, entity, eff.getType());
                                }
                            } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#removeAllEffectsTargets"); }
                        })),

                // ---- 变量 / 分数 ----

                Map.entry("set-stance", (SkillCatalog.ActionFactory) a -> {
                    String stance = str(a, 0, "");
                    return ctx -> {
                        if (!hasInstance(ctx) || stance.isBlank()) return;
                        STANCES.put(id(ctx), stance);
                    };
                }),

                Map.entry("variable-unset", (SkillCatalog.ActionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    return ctx -> unsetVariable(ctx, ref);
                }),
                Map.entry("var-unset", (SkillCatalog.ActionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    return ctx -> unsetVariable(ctx, ref);
                }),
                Map.entry("location-set", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    double x = num(a, 1, Double.NaN);
                    double y = num(a, 2, Double.NaN);
                    double z = num(a, 3, Double.NaN);
                    return ctx -> {
                        if (name.isEmpty() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
                        Location base = ctx == null ? null : ctx.origin().orElse(null);
                        if (base == null && hasInstance(ctx)) base = ctx.instance().location();
                        if (base == null || base.getWorld() == null) return;
                        Location finalBase = base;
                        ctx.skillTree().ifPresent(tree -> tree.setLocation(name, finalBase.clone().add(x, y, z)));
                    };
                }),
                Map.entry("location-copy", (SkillCatalog.ActionFactory) a -> {
                    String destination = str(a, 0, "");
                    String source = str(a, 1, "origin");
                    return ctx -> {
                        if (ctx == null || destination.isEmpty()) return;
                        var tree = ctx.skillTree().orElse(null);
                        if (tree == null) return;
                        Location location = source.equalsIgnoreCase("origin")
                                ? ctx.origin().orElse(null) : tree.location(source).orElse(null);
                        if (location != null) tree.setLocation(destination, location);
                    };
                }),
                Map.entry("location-unset", (SkillCatalog.ActionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    return ctx -> unsetLocation(ctx, ref);
                }),
                Map.entry("track-location", (SkillCatalog.ActionFactory) a -> {
                    if (a != null && !a.isEmpty()) {
                        throw new IllegalArgumentException("track-location accepts no arguments");
                    }
                    return ctx -> {
                        if (ctx == null || !ctx.instanceValid()) return;
                        Entity caster = ctx.instance().baseEntity().orElse(null);
                        if (caster instanceof Player) return;
                        Location location = ctx.targetLocation().orElseGet(() ->
                                ctx.targetEntity().map(Entity::getLocation).orElse(null));
                        if (location == null || location.getWorld() == null) return;
                        ctx.skillTree().ifPresent(tree ->
                                tree.setLocation(SkillTreeContext.MYTHIC_TRACKED_LOCATION, location));
                    };
                }),
                Map.entry("branch-unlock", (SkillCatalog.ActionFactory) a -> branchUnlockAction(a, null)),
                Map.entry("variable-location-set", (SkillCatalog.ActionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    return ctx -> {
                        if (ctx == null || ref.name().isEmpty()) return;
                        Location location = ctx.targetLocation().orElseGet(() -> ctx.targetEntity()
                                .map(org.bukkit.entity.Entity::getLocation)
                                .orElseGet(() -> ctx.origin().orElse(null)));
                        if (location == null) return;
                        if (ref.scope().equals("skill")) {
                            ctx.skillTree().ifPresent(tree -> tree.setLocation(ref.name(), location));
                        } else {
                            VariableLocations.set(ref.scope(), ref.name(), ctx, location);
                        }
                    };
                }),
                Map.entry("set-variable", (SkillCatalog.ActionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    Object value = parseVariableValue(a);
                    return ctx -> setRawVariable(ctx, ref, value);
                }),                Map.entry("var-set", (SkillCatalog.ActionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    double value = variableNumber(a, 1, 0, "value", "val", "amount");
                    return ctx -> setVariable(ctx, ref, value);
                }),
                Map.entry("var-add", (SkillCatalog.ActionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    double amount = variableNumber(a, 1, 0, "value", "val", "amount");
                    return ctx -> {
                        if (ctx == null || ref.name().isEmpty()) return;
                        double current = varOf(ctx, ref);
                        if (current == Double.NEGATIVE_INFINITY) current = 0;
                        setVariable(ctx, ref, current + amount);
                    };
                }),
                Map.entry("var-math", (SkillCatalog.ActionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String operation = keyedArg(a, "op", "operation");
                    if (operation == null) operation = str(a, 1, "+");
                    double value = variableNumber(a, 2, 0, "value", "val", "amount");
                    String op = operation;
                    return ctx -> {
                        if (ctx == null || ref.name().isEmpty()) return;
                        double current = varOf(ctx, ref);
                        if (current == Double.NEGATIVE_INFINITY) current = 0;
                        double next = switch (op) {
                            case "+" -> current + value;
                            case "-" -> current - value;
                            case "*" -> current * value;
                            case "/" -> value == 0 ? current : current / value;
                            default -> current;
                        };
                        setVariable(ctx, ref, next);
                    };
                }),                Map.entry("score-set", (SkillCatalog.ActionFactory) a -> {
                    String parsedName = keyedArg(a, "objective", "score", "name", "o");
                    String name = parsedName == null ? str(a, 0, "") : parsedName;
                    int v = (int) variableNumber(a, 1, 0, "value", "amount", "v");
                    return ctx -> {
                        if (!hasInstance(ctx) || name.isEmpty()) return;
                        scoresOf(id(ctx)).put(name, v);
                    };
                }),
                Map.entry("score-add", (SkillCatalog.ActionFactory) a -> {
                    String parsedName = keyedArg(a, "objective", "score", "name", "o");
                    String name = parsedName == null ? str(a, 0, "") : parsedName;
                    int v = (int) variableNumber(a, 1, 0, "value", "amount", "v");
                    return ctx -> {
                        if (!hasInstance(ctx) || name.isEmpty()) return;
                        scoresOf(id(ctx)).merge(name, v, Integer::sum);
                    };
                }),
                // 连杀清零：给「领奖一次后打回原形」这类玩法用。
                // 不提供「直接置数」动作——连杀只能由真实击杀累积，
                // 允许动作改写会让 on-kill-player 的奖励凭空刷出来。
                Map.entry("kill-streak-reset", (SkillCatalog.ActionFactory) a ->
                        ctx -> {
                            if (!hasInstance(ctx)) return;
                            var table = streaks();
                            if (table == null) return;
                            var killer = table.lastKillerOf(id(ctx));
                            if (killer != null) table.reset(killer);
                        }),
                // ---- 仇恨动作（对标 MM 的 threat / clearThreat） ----
                Map.entry("threat-add", (SkillCatalog.ActionFactory) a -> {
                    double amount = num(a, 0, 0);
                    return ctx -> {
                        var t = threatOf(ctx);
                        // 无目标时静默跳过：MM 的 threat 动作同样需要一个作用对象，
                        // 找不到对象却仍加仇恨只会凭空造出一个不存在的目标
                        var tgt = ctx.targetEntity();
                        if (t == null || tgt.isEmpty() || amount <= 0) return;
                        t.addThreat(tgt.get().getUniqueId(), amount);
                    };
                }),
                Map.entry("threat-clear", (SkillCatalog.ActionFactory) a -> {
                    boolean only = bool(a, 0, false);
                    return ctx -> {
                        var t = threatOf(ctx);
                        if (t == null) return;
                        if (!only) {
                            t.clear();
                            return;
                        }
                        // threat-clear true：只清当前目标，保留其余人的仇恨
                        var tgt = ctx.targetEntity();
                        if (tgt.isPresent()) t.remove(tgt.get().getUniqueId());
                    };
                }),
                // 把某目标顶成首要目标：等价于给它一次性高仇恨。
                // 刻意不提供「直接设指定玩家的仇恨值」——那会绕过仇恨表的
                // 距离权重与衰减，直接改写内部状态，破坏排序平衡。
                Map.entry("threat-focus", (SkillCatalog.ActionFactory) a ->
                        ctx -> {
                            var t = threatOf(ctx);
                            var tgt = ctx.targetEntity();
                            if (t == null || tgt.isEmpty()) return;
                            var id2 = tgt.get().getUniqueId();
                            t.focus(id2);
                        }),
                Map.entry("set-global-cooldown", (SkillCatalog.ActionFactory) a ->
                        globalCooldownAction(a, false)),
                Map.entry("gcd", (SkillCatalog.ActionFactory) a ->
                        globalCooldownAction(a, true)),
                Map.entry("setgcd", (SkillCatalog.ActionFactory) a ->
                        globalCooldownAction(a, true)),
                Map.entry("setglobalcooldown", (SkillCatalog.ActionFactory) a ->
                        globalCooldownAction(a, true)),
                Map.entry("set-skill-cooldown", (SkillCatalog.ActionFactory) a -> {
                    String skillName = str(a, 0, "");
                    var seconds = dynamicNum(a, 1, 0);
                    return ctx -> {
                        SkillCooldownSetter setter = skillCooldownSetter;
                        if (setter == null || skillName.isBlank()) return;
                        long millis = Math.max(0L, (long) (seconds.applyAsDouble(ctx) * 1000.0));
                        setter.set(skillName, ctx, millis);
                    };
                }),
                Map.entry("add-to-audience", (SkillCatalog.ActionFactory) a ->
                        audienceMutationAction(a, AudienceMutation.ADD)),
                Map.entry("remove-from-audience", (SkillCatalog.ActionFactory) a ->
                        audienceMutationAction(a, AudienceMutation.REMOVE)),
                Map.entry("unset-audience", (SkillCatalog.ActionFactory) a ->
                        audienceMutationAction(a, AudienceMutation.UNSET)),

                // ---- 信息 ----

                Map.entry("broadcast", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    return ctx -> {
                        if (raw.isBlank()) return;
                        try {
                            var component = net.kyori.adventure.text.Component.text(
                                    Placeholders.resolve(raw, ctx));
                            org.bukkit.Bukkit.getConsoleSender().sendMessage(component);
                            for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                                if (ExecutionAudiences.allows(ctx, player)) player.sendMessage(component);
                            }
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#687");
                        }
                    };
                }),
                Map.entry("title-audience", (SkillCatalog.ActionFactory) a -> {
                    String audience = str(a, 0, "target");
                    String title = str(a, 1, "");
                    int in = (int) num(a, 2, 10);
                    int stay = (int) num(a, 3, 40);
                    int out = (int) num(a, 4, 10);
                    return ctx -> {
                        if (title.isBlank()) return;
                        String rendered = org.bukkit.ChatColor.translateAlternateColorCodes('&', Placeholders.resolve(title, ctx));
                        for (Player player : audiencePlayers(ctx, audience, num(a, 5, 32))) {
                            player.sendTitle(rendered, "", in, stay, out);
                        }
                    };
                }),
                Map.entry("audience-title", (SkillCatalog.ActionFactory) a -> {
                    String audience = str(a, 0, "target");
                    String title = str(a, 1, "");
                    int in = (int) num(a, 2, 10);
                    int stay = (int) num(a, 3, 40);
                    int out = (int) num(a, 4, 10);
                    return ctx -> {
                        if (title.isBlank()) return;
                        String rendered = org.bukkit.ChatColor.translateAlternateColorCodes('&', Placeholders.resolve(title, ctx));
                        for (Player player : audiencePlayers(ctx, audience, num(a, 5, 32))) player.sendTitle(rendered, "", in, stay, out);
                    };
                }),
                Map.entry("title", (SkillCatalog.ActionFactory) a -> {
                    String title = str(a, 0, "");
                    String subtitle = str(a, 1, "");
                    int in = (int) num(a, 2, 10);
                    int stay = (int) num(a, 3, 40);
                    int out = (int) num(a, 4, 10);
                    return ctx -> {
                        if (title.isBlank() && subtitle.isBlank()) return;
                        String renderedTitle = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                Placeholders.resolve(title, ctx));
                        String renderedSubtitle = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                Placeholders.resolve(subtitle, ctx));
                        for (Player player : audiencePlayers(ctx, "target", 32)) {
                            player.sendTitle(renderedTitle, renderedSubtitle, in, stay, out);
                        }
                    };
                }),
                Map.entry("particle-audience", (SkillCatalog.ActionFactory) a -> {
                    String audience = str(a, 0, "target");
                    String particleName = str(a, 1, "");
                    int count = (int) num(a, 2, 8);
                    double radius = num(a, 3, 32);
                    return ctx -> {
                        if (particleName.isBlank()) return;
                        org.bukkit.Particle particle;
                        try { particle = org.bukkit.Particle.valueOf(particleName.toUpperCase(Locale.ROOT)); }
                        catch (IllegalArgumentException ignored) { return; }
                        for (Player player : audiencePlayers(ctx, audience, radius)) {
                            player.spawnParticle(particle, player.getLocation(), Math.max(0, count), 0, 0, 0, 0);
                        }
                    };
                }),
                Map.entry("audience-particle", (SkillCatalog.ActionFactory) a -> {
                    String audience = str(a, 0, "target");
                    String particleName = str(a, 1, "");
                    int count = (int) num(a, 2, 8);
                    double radius = num(a, 3, 32);
                    return ctx -> {
                        if (particleName.isBlank()) return;
                        org.bukkit.Particle particle;
                        try { particle = org.bukkit.Particle.valueOf(particleName.toUpperCase(Locale.ROOT)); }
                        catch (IllegalArgumentException ignored) { return; }
                        for (Player player : audiencePlayers(ctx, audience, radius)) player.spawnParticle(particle, player.getLocation(), Math.max(0, count), 0, 0, 0, 0);
                    };
                }),
                Map.entry("actionbar-audience", (SkillCatalog.ActionFactory) a -> {
                    String audience = str(a, 0, "target");
                    String raw = str(a, 1, "");
                    double radius = num(a, 2, 32);
                    return ctx -> {
                        if (raw.isBlank()) return;
                        String out = org.bukkit.ChatColor.translateAlternateColorCodes('&', Placeholders.resolve(raw, ctx));
                        var components = net.md_5.bungee.api.chat.TextComponent.fromLegacyText(out);
                        for (Player player : audiencePlayers(ctx, audience, radius)) {
                            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, components);
                        }
                    };
                }),
                Map.entry("audience-actionbar", (SkillCatalog.ActionFactory) a -> {
                    String audience = str(a, 0, "target");
                    String raw = str(a, 1, "");
                    double radius = num(a, 2, 32);
                    return ctx -> {
                        if (raw.isBlank()) return;
                        String out = org.bukkit.ChatColor.translateAlternateColorCodes('&', Placeholders.resolve(raw, ctx));
                        var components = net.md_5.bungee.api.chat.TextComponent.fromLegacyText(out);
                        for (Player player : audiencePlayers(ctx, audience, radius)) {
                            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, components);
                        }
                    };
                }),
                Map.entry("actionbar", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    double radius = num(a, 1, 32);
                    return ctx -> {
                        if (raw.isBlank()) return;
                        String out = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                Placeholders.resolve(raw, ctx));
                        var components = net.md_5.bungee.api.chat.TextComponent.fromLegacyText(out);
                        for (Player player : audiencePlayers(ctx, "target", radius)) {
                            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, components);
                        }
                    };
                }),
                Map.entry("message-audience", (SkillCatalog.ActionFactory) a -> {
                    String audience = str(a, 0, "target");
                    String raw = str(a, 1, "");
                    double radius = num(a, 2, 32);
                    return ctx -> {
                        if (raw.isBlank()) return;
                        String out = org.bukkit.ChatColor.translateAlternateColorCodes('&', Placeholders.resolve(raw, ctx));
                        for (Player player : audiencePlayers(ctx, audience, radius)) player.sendMessage(out);
                    };
                }),
                Map.entry("audience-message", (SkillCatalog.ActionFactory) a -> {
                    String audience = str(a, 0, "target");
                    String raw = str(a, 1, "");
                    double radius = num(a, 2, 32);
                    return ctx -> {
                        if (raw.isBlank()) return;
                        String out = org.bukkit.ChatColor.translateAlternateColorCodes('&', Placeholders.resolve(raw, ctx));
                        for (Player player : audiencePlayers(ctx, audience, radius)) player.sendMessage(out);
                    };
                }),
                Map.entry("message-all", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    double radius = num(a, 1, 32);
                    return ctx -> {
                        Location me = selfLoc(ctx);
                        if (me == null || raw.isBlank()) return;
                        String out = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                Placeholders.resolve(raw, ctx));
                        try {
                            for (Player p : me.getWorld().getNearbyPlayers(me, radius)) {
                                if (ExecutionAudiences.allows(ctx, p)) p.sendMessage(out);
                            }
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#730");
                        }
                    };
                }),
                Map.entry("message-self", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    return ctx -> {
                        if (raw.isBlank()) return;
                        selfLiving(ctx).filter(e -> e instanceof Player)
                                .filter(e -> ExecutionAudiences.allows(ctx, (Player) e)).ifPresent(p -> {
                            try {
                                ((Player) p).sendMessage(org.bukkit.ChatColor
                                        .translateAlternateColorCodes('&',
                                                Placeholders.resolve(raw, ctx)));
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#743");
                            }
                        });
                    };
                }),
                Map.entry("set-nameplate", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setCustomName(raw.isEmpty() ? null
                                    : org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                    Placeholders.resolve(raw, ctx)));
                            e.setCustomNameVisible(!raw.isEmpty());
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#756");
                        }
                    });
                }),
                Map.entry("set-name", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    boolean visible = bool(a, 1, true);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            String name = raw.isEmpty() ? null
                                    : org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                    Placeholders.resolve(raw, ctx));
                            e.setCustomName(name);
                            e.setCustomNameVisible(name != null && visible);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#770");
                        }
                    });
                }),
                Map.entry("set-name-targets", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    boolean visible = bool(a, 1, true);
                    return ctx -> {
                        String name = raw.isEmpty() ? null
                                : org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                Placeholders.resolve(raw, ctx));
                        for (LivingEntity target : targetEntities(ctx)) {
                            try {
                                target.setCustomName(name);
                                target.setCustomNameVisible(name != null && visible);
                            } catch (Throwable ignored) { SkillFaults.swallow("SkillExtras#setNameTargets"); }
                        }
                    };
                }),
                Map.entry("random-message", (SkillCatalog.ActionFactory) a -> {
                    String pool = str(a, 0, "");
                    return ctx -> {
                        Player t = ctx.target().orElse(null);
                        if (t == null || pool.isEmpty() || !ExecutionAudiences.allows(ctx, t)) return;
                        String[] options = pool.split("\\|");
                        String pick = options[java.util.concurrent.ThreadLocalRandom.current()
                                .nextInt(options.length)].trim();
                        try {
                            t.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                                    Placeholders.resolve(pick, ctx)));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#771");
                        }
                    };
                }),
                Map.entry("play-sound-at", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    String where = str(a, 1, "self");
                    String audience = str(a, 2, "target");
                    return ctx -> {
                        if (name.isEmpty()) return;
                        org.bukkit.Sound sound;
                        try {
                            sound = org.bukkit.Sound.valueOf(name.toUpperCase(Locale.ROOT));
                        } catch (Throwable t) {
                            return;
                        }
                        if (where.equalsIgnoreCase("target")) {
                            for (Player player : audiencePlayers(ctx, audience, 32)) {
                                player.playSound(player.getLocation(), sound, 1f, 1f);
                            }
                            return;
                        }
                        Location l = selfLoc(ctx);
                        if (l == null) return;
                        try {
                            var executionAudience = ctx.executionAudience().orElse(null);
                            if (executionAudience == null) {
                                l.getWorld().playSound(l, sound, 1f, 1f);
                            } else {
                                for (Player player : l.getWorld().getPlayers()) {
                                    if (executionAudience.contains(player.getUniqueId())) {
                                        player.playSound(l, sound, 1f, 1f);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#799");
                        }
                    };
                }),

                // ---- 生命周期 ----

                Map.entry("despawn", (SkillCatalog.ActionFactory) a -> ctx -> {
                    Despawner d = despawner;
                    if (d == null || ctx == null || !ctx.instanceValid()) return;
                    try {
                        d.despawn(ctx.instance().instanceId());
                    } catch (Throwable ignored) {
                        SkillFaults.swallow("SkillExtras#811");
                    }
                }),

                // ---- 强制位移：推离 ----

                Map.entry("push-away", (SkillCatalog.ActionFactory) a ->
                        pushAwayAction(dynamicNum(a, 0, 1.0), dynamicNum(a, 1, 0.25))),

                // ---- 移动状态 ----

                // MythicMobs 的 glide / stopglide 是同一件事的开关；stop-glide 复用同一个
                // 工厂，避免两份几乎相同的闭包各改一遍却总有一份忘了改。
                Map.entry("glide", (SkillCatalog.ActionFactory) a -> glideAction(a, true)),
                Map.entry("stop-glide", (SkillCatalog.ActionFactory) a -> glideAction(a, false)),
                Map.entry("glide-targets", (SkillCatalog.ActionFactory) a -> glideAction(a, true)),
                Map.entry("stop-glide-targets", (SkillCatalog.ActionFactory) a -> glideAction(a, false)),

                // ---- 饥饿度 ----

                // 绝对值。与 feed（增量）刻意分开：把绝对值接成增量的话，
                // `set-hunger 20` 会把已经吃饱的玩家写成 40，运行期被 clamp 掩盖成「没事」。
                Map.entry("set-hunger", (SkillCatalog.ActionFactory) a ->
                        setHungerAction(a, false)),
                Map.entry("set-hunger-targets", (SkillCatalog.ActionFactory) a ->
                        setHungerAction(a, true)),

                // ---- 物品槽 ----

                Map.entry("set-slot", (SkillCatalog.ActionFactory) a -> {
                    String slotKey = keyedArg(a, "slot", "s");
                    if (slotKey == null) slotKey = str(a, 0, "");
                    String itemKey = keyedArg(a, "item", "material", "m", "type");
                    if (itemKey == null) itemKey = str(a, 1, "");
                    final String slot = slotKey.trim();
                    final String item = itemKey.trim();
                    final boolean armorSlot = isArmorSlot(slot);
                    final boolean clearing = item.isEmpty() || "AIR".equalsIgnoreCase(item)
                            || "NONE".equalsIgnoreCase(item);
                    return ctx -> {
                        if (slot.isEmpty()) return;
                        for (LivingEntity target : itemTargets(ctx)) {
                            if (!(target instanceof Player player)) continue;
                            if (armorSlot) {
                                // 护甲槽写原版装备栏，不走背包索引——否则头盔会被塞进背包第一格。
                                applyArmorSlot(player, slot, clearing ? null : singleItem(item));
                                continue;
                            }
                            setInventoryItem(player, slot, clearing ? null : singleItem(item));
                        }
                    };
                })
        );
    }

    /**
     * 推离向量：从 {@code from} 指向 {@code to} 的水平单位向量乘强度，再加纵向分量。
     *
     * <p>抽成静态纯函数是为了让「两者水平位置重合」这个分支可离线验证：
     * 那时水平方向为零，目标原地不动——推离类机制最容易写成「看着在推、其实没动」。
     * 返回 null 表示不该施加任何速度（世界不同 / 强度非法 / 位置重合）。</p>
     */
    static org.bukkit.util.Vector pushAwayVector(Location from, Location to,
                                                 double strength, double lift) {
        if (from == null || to == null) return null;
        if (from.getWorld() == null || to.getWorld() == null) return null;
        if (!from.getWorld().equals(to.getWorld())) return null;
        if (!Double.isFinite(strength) || strength <= 0.0) return null;
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (!Double.isFinite(length) || length <= 1.0e-9) return null;
        double y = Double.isFinite(lift) ? lift : 0.0;
        return new org.bukkit.util.Vector(dx / length * strength, y, dz / length * strength);
    }

    private static java.util.function.Consumer<BehaviorContext> pushAwayAction(
            java.util.function.ToDoubleFunction<BehaviorContext> power,
            java.util.function.ToDoubleFunction<BehaviorContext> lift) {
        return ctx -> {
            Location origin = selfLoc(ctx);
            double strength = power.applyAsDouble(ctx);
            double rise = lift.applyAsDouble(ctx);
            for (LivingEntity target : itemTargets(ctx)) {
                org.bukkit.util.Vector away;
                try {
                    away = pushAwayVector(origin, target.getLocation(), strength, rise);
                } catch (Throwable ignored) {
                    SkillFaults.swallow("SkillExtras#pushAwayLocation");
                    continue;
                }
                if (away == null) continue;
                try {
                    target.setVelocity(away);
                } catch (Throwable ignored) {
                    SkillFaults.swallow("SkillExtras#pushAway");
                }
            }
        };
    }

    /**
     * 绝对值饥饿度：饥饿值钳在 0..20，饱和度可选（给了才写，避免把饱和度顺手清零）。
     *
     * <p>{@code targetsOnly} 区分「只作用于 @Targeter 选出的目标」与「无目标时退回自身」，
     * 与其余 {@code *-targets} 变体的语义保持一致。</p>
     */
    private static java.util.function.Consumer<BehaviorContext> setHungerAction(List<String> args,
                                                                               boolean targetsOnly) {
        var amount = dynamicNum(args, 0, 20);
        var saturation = dynamicNum(args, 1, 0);
        final boolean saturationGiven = args != null && args.size() > 1
                && args.get(1) != null && !args.get(1).isBlank();
        return ctx -> {
            double raw = amount.applyAsDouble(ctx);
            if (!Double.isFinite(raw)) return;
            int food = clampFoodLevel(raw);
            double sat = saturationGiven ? saturation.applyAsDouble(ctx) : Double.NaN;
            List<LivingEntity> entities = targetsOnly ? targetEntities(ctx) : itemTargets(ctx);
            for (LivingEntity target : entities) {
                if (!(target instanceof Player player)) continue;
                try {
                    player.setFoodLevel(food);
                    if (Double.isFinite(sat)) {
                        player.setSaturation(clampSaturation(sat, food));
                    }
                } catch (RuntimeException ignored) {
                    SkillFaults.swallow("SkillExtras#setHunger");
                }
            }
        };
    }

    /** 统一开关滑翔：{@code glide} 与 {@code stop-glide} 共用一份闭包。 */
    private static java.util.function.Consumer<BehaviorContext> glideAction(List<String> args,
                                                                           boolean defaultGliding) {        boolean gliding = keyedBoolean(args, 0, defaultGliding,
                "state", "g", "value", "v", "gliding");
        return ctx -> {
            for (LivingEntity target : itemTargets(ctx)) {
                try {
                    target.setGliding(gliding);
                } catch (Throwable ignored) {
                    SkillFaults.swallow("SkillExtras#glide");
                }
            }
        };
    }

    /** 食物栏上限 20 点；负数按 0，超出按 20，NaN 按 0。 */
    static int clampFoodLevel(double raw) {
        if (!Double.isFinite(raw)) return 0;
        return (int) Math.max(0, Math.min(20, Math.round(raw)));
    }

    /** 饱和度不得超过当前饥饿值，否则原版会在下一 tick 静默回写。 */
    static float clampSaturation(double raw, int foodLevel) {
        if (!Double.isFinite(raw) || raw <= 0.0) return 0.0f;
        return (float) Math.min(Math.max(0, foodLevel), raw);
    }

    private static boolean isArmorSlot(String slot) {
        if (slot == null) return false;
        return switch (slot.trim().toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "HEAD", "HELMET", "CHEST", "CHESTPLATE", "LEGS", "LEGGINGS", "FEET", "BOOTS" -> true;
            default -> false;
        };
    }

    /** 单件物品模板；解析不到名字时返回 null（等价于清空该槽，而不是偷偷保留旧物品）。 */
    private static ItemStack singleItem(String itemName) {
        ItemStack template = resolveItem(itemName, 1);
        if (template == null) return null;
        ItemStack single = template.clone();
        single.setAmount(1);
        return single;
    }

    private static void applyArmorSlot(Player player, String slot, ItemStack item) {
        if (player == null) return;
        String key = slot.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            var inventory = player.getInventory();
            switch (key) {
                case "HEAD", "HELMET" -> inventory.setHelmet(item);
                case "CHEST", "CHESTPLATE" -> inventory.setChestplate(item);
                case "LEGS", "LEGGINGS" -> inventory.setLeggings(item);
                case "FEET", "BOOTS" -> inventory.setBoots(item);
                default -> { }
            }
        } catch (RuntimeException ignored) {
            SkillFaults.swallow("SkillExtras#setSlotArmor");
        }
    }

    private static EquipmentSlot equipmentSlot(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "offhand", "off-hand" -> EquipmentSlot.OFF_HAND;
            case "helmet", "head" -> EquipmentSlot.HEAD;
            case "chest", "chestplate" -> EquipmentSlot.CHEST;
            case "legs", "leggings" -> EquipmentSlot.LEGS;
            case "feet", "boots" -> EquipmentSlot.FEET;
            default -> EquipmentSlot.HAND;
        };
    }

    private static java.util.function.Consumer<BehaviorContext> equipmentAction(List<String> args) {
        String first = str(args, 0, "");
        String slotName = str(args, 1, "hand");
        String third = str(args, 2, "1");
        String materialName = first;
        String amountToken = third;
        if (isNumber(first) && !isNumber(third)) {
            amountToken = first;
            materialName = third;
        }
        EquipmentSlot slot = equipmentSlot(slotName);
        int amount = Math.max(1, parseAmount(amountToken));
        String itemName = materialName;
        int itemAmount = amount;
        return ctx -> selfLiving(ctx).ifPresent(entity -> {
            EntityEquipment equipment = entity.getEquipment();
            ItemStack item = resolveItem(itemName, itemAmount);
            if (equipment == null || item == null) return;
            item.setAmount(itemAmount);
            equipment.setItem(slot, item);
        });
    }

    private static java.util.function.Consumer<BehaviorContext> setOffhandAction(List<String> args) {
        return equipmentAction(fixedEquipmentArgs(args, "off-hand"));
    }

    private static java.util.function.Consumer<BehaviorContext> setOffhandTargetsAction(
            List<String> args) {
        return equipmentTargetsAction(fixedEquipmentArgs(args, "off-hand"));
    }

    private static List<String> fixedEquipmentArgs(List<String> args, String slot) {
        return List.of(str(args, 0, ""), slot, str(args, 1, "1"));
    }

    private static java.util.function.Consumer<BehaviorContext> setArmorAction(List<String> args) {
        return setArmorAction(args, false);
    }

    private static java.util.function.Consumer<BehaviorContext> setArmorTargetsAction(
            List<String> args) {
        return setArmorAction(args, true);
    }

    private static java.util.function.Consumer<BehaviorContext> setArmorAction(
            List<String> args, boolean targets) {
        String helmet = str(args, 0, "");
        String chest = str(args, 1, "");
        String legs = str(args, 2, "");
        String feet = str(args, 3, "");
        return ctx -> {
            List<LivingEntity> entities = targets ? targetEntities(ctx)
                    : selfLiving(ctx).map(List::of).orElseGet(List::of);
            for (LivingEntity entity : entities) {
                EntityEquipment equipment = entity.getEquipment();
                if (equipment == null) continue;
                setArmorItem(equipment, EquipmentSlot.HEAD, helmet);
                setArmorItem(equipment, EquipmentSlot.CHEST, chest);
                setArmorItem(equipment, EquipmentSlot.LEGS, legs);
                setArmorItem(equipment, EquipmentSlot.FEET, feet);
            }
        };
    }

    private static void setArmorItem(EntityEquipment equipment, EquipmentSlot slot, String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("AIR")
                || raw.equalsIgnoreCase("NONE")) {
            equipment.setItem(slot, null);
            return;
        }
        ItemStack item = resolveItem(raw, 1);
        if (item == null) return;
        item.setAmount(1);
        equipment.setItem(slot, item);
    }

    private static java.util.function.Consumer<BehaviorContext> equipmentTargetsAction(List<String> args) {
        String first = str(args, 0, "");
        String slotName = str(args, 1, "hand");
        String third = str(args, 2, "1");
        String materialName = first;
        String amountToken = third;
        if (isNumber(first) && !isNumber(third)) {
            amountToken = first;
            materialName = third;
        }
        EquipmentSlot slot = equipmentSlot(slotName);
        int amount = Math.max(1, parseAmount(amountToken));
        String itemName = materialName;
        return ctx -> {
            ItemStack template = resolveItem(itemName, amount);
            if (template == null) return;
            for (LivingEntity entity : targetEntities(ctx)) {
                EntityEquipment equipment = entity.getEquipment();
                if (equipment == null) continue;
                ItemStack item = template.clone();
                item.setAmount(amount);
                equipment.setItem(slot, item);
            }
        };
    }

    private static java.util.function.Consumer<BehaviorContext> dropItemAction(List<String> args) {
        String itemName = str(args, 0, "");
        var amount = dropItemAmount(args);
        boolean naturally = dropItemNaturally(args);
        String ownerName = args != null && args.size() > 3 && !args.get(3).isEmpty() ? args.get(3) : null;
        return ctx -> {
            int count = dropItemCount(amount.applyAsDouble(ctx));
            if (count == 0) return;
            Location location = selfLoc(ctx);
            ItemStack item = resolveItem(itemName, count);
            if (location == null || item == null) return;
            item.setAmount(count);
            UUID owner = ownerName == null ? null : dropItemOwner(ownerName, ctx);
            if (ownerName != null && owner == null) return;
            dropItemAt(location, item, naturally, owner, "SkillExtras#1384");
        };
    }

    private static java.util.function.Consumer<BehaviorContext> dropItemsAction(List<String> args, boolean targeted) {
        boolean hasOwner = args != null && args.size() % 2 == 0;
        int pairsEnd = hasOwner ? args.size() - 1 : args == null ? 0 : args.size();
        if (args == null || pairsEnd < 5 || pairsEnd % 2 == 0) {
            throw new IllegalArgumentException("drop-items requires a natural flag and at least two item/count pairs");
        }
        String naturally = args.get(0);
        String owner = hasOwner ? args.get(args.size() - 1) : null;
        var actions = new ArrayList<java.util.function.Consumer<BehaviorContext>>();
        for (int i = 1; i < pairsEnd; i += 2) {
            var itemArgs = List.of(args.get(i), args.get(i + 1), naturally,
                    owner == null ? "" : owner);
            actions.add(targeted ? dropItemTargetsAction(itemArgs) : dropItemAction(itemArgs));
        }
        return ctx -> actions.forEach(action -> action.accept(ctx));
    }

    private static java.util.function.Consumer<BehaviorContext> dropItemTargetsAction(List<String> args) {
        String itemName = str(args, 0, "");
        var amount = dropItemAmount(args);
        boolean naturally = dropItemNaturally(args);
        String ownerName = args != null && args.size() > 3 && !args.get(3).isEmpty() ? args.get(3) : null;
        return ctx -> {
            int count = dropItemCount(amount.applyAsDouble(ctx));
            if (count == 0) return;
            ItemStack template = resolveItem(itemName, count);
            if (template == null) return;
            Location locationTarget = ctx.targetLocation().orElse(null);
            if (locationTarget != null && locationTarget.getWorld() != null) {
                UUID owner = ownerName == null ? null : dropItemOwner(ownerName, ctx);
                if (ownerName != null && owner == null) return;
                ItemStack item = template.clone();
                item.setAmount(count);
                dropItemAt(locationTarget, item, naturally, owner, "SkillExtras#dropItemTargets");
                return;
            }
            for (LivingEntity target : targetEntities(ctx)) {
                Location location = target.getLocation();
                if (location == null || location.getWorld() == null) continue;
                BehaviorContext targetContext = ctx.withTargetEntity(target);
                UUID owner = ownerName == null ? null : dropItemOwner(ownerName, targetContext);
                if (ownerName != null && owner == null) continue;
                ItemStack item = template.clone();
                item.setAmount(count);
                dropItemAt(location, item, naturally, owner, "SkillExtras#dropItemTargets");
            }
        };
    }

    private static UUID dropItemOwner(String raw, BehaviorContext context) {
        if (raw == null || raw.isBlank()) return null;
        String resolved = Placeholders.resolve(raw, context);
        if (resolved == null) return null;
        try {
            UUID owner = UUID.fromString(resolved.trim());
            return owner.toString().equalsIgnoreCase(resolved.trim()) ? owner : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void dropItemAt(Location location, ItemStack item, boolean naturally,
                                   UUID owner, String faultLocation) {
        try {
            org.bukkit.entity.Item dropped = naturally
                    ? location.getWorld().dropItemNaturally(location, item)
                    : location.getWorld().dropItem(location, item);
            if (owner != null && dropped != null) dropped.setOwner(owner);
        } catch (RuntimeException ignored) {
            SkillFaults.swallow(faultLocation);
        }
    }

    private static int dropItemCount(double amount) {
        return Double.isFinite(amount) && amount >= 1 && amount <= Integer.MAX_VALUE
                && amount == Math.rint(amount) ? (int) amount : 0;
    }

    private static boolean dropItemNaturally(List<String> args) {
        if (args == null || args.size() <= 2) return true;
        String value = args.get(2);
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("invalid drop-item naturally flag: " + value);
    }

    private static java.util.function.ToDoubleFunction<BehaviorContext> dropItemAmount(List<String> args) {
        return args == null || args.size() <= 1 || args.get(1) == null || args.get(1).isBlank()
                ? ctx -> 1 : dynamicNum(args, 1, Double.NaN);
    }

    private static java.util.function.Consumer<BehaviorContext> giveItemAction(List<String> args) {
        String itemName = str(args, 0, "");
        int amount = Math.max(1, parseAmount(str(args, 1, "1")));
        return ctx -> {
            ItemStack template = resolveItem(itemName, amount);
            if (template == null) return;
            for (LivingEntity target : itemTargets(ctx)) {
                if (!(target instanceof Player player)) continue;
                ItemStack item = template.clone();
                item.setAmount(amount);
                try {
                    var leftovers = player.getInventory().addItem(item);
                    for (ItemStack leftover : leftovers.values()) {
                        if (leftover != null && !leftover.getType().isAir()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                        }
                    }
                } catch (RuntimeException ignored) {
                    SkillFaults.swallow("SkillExtras#1398");
                }
            }
        };
    }

    private static java.util.function.Consumer<BehaviorContext> takeItemAction(List<String> args) {
        String itemName = str(args, 0, "");
        int amount = Math.max(1, parseAmount(str(args, 1, "1")));
        return ctx -> {
            ItemStack expected = resolveItem(itemName, 1);
            if (expected == null) return;
            for (LivingEntity target : itemTargets(ctx)) {
                if (!(target instanceof Player player)) continue;
                removeMatchingItems(player, expected, amount, vanillaItemName(itemName));
            }
        };
    }

    private static java.util.function.Consumer<BehaviorContext> setBlockAction(List<String> args) {
        String materialName = str(args, 0, "AIR");
        return ctx -> {
            Location location = blockActionLocation(ctx);
            if (location == null || location.getWorld() == null) return;
            Material material = Material.matchMaterial(materialName);
            if (material == null) return;
            try {
                location.getBlock().setType(material, false);
            } catch (RuntimeException ignored) {
                SkillFaults.swallow("SkillExtras#1430");
            }
        };
    }

    private static java.util.function.Consumer<BehaviorContext> breakBlockAction(List<String> args) {
        return ctx -> {
            Location location = blockActionLocation(ctx);
            if (location == null || location.getWorld() == null) return;
            try {
                location.getBlock().breakNaturally();
            } catch (RuntimeException ignored) {
                SkillFaults.swallow("SkillExtras#1441");
            }
        };
    }

    private static Location blockActionLocation(BehaviorContext ctx) {
        if (ctx == null) return null;
        Location location = ctx.targetLocation().orElse(null);
        if (location != null) return location;
        Location target = ctx.targetEntity().map(Entity::getLocation).orElse(null);
        return target == null ? selfLoc(ctx) : target;
    }

    private static java.util.function.Consumer<BehaviorContext> jumpAction(List<String> args) {
        var velocity = dynamicKeyedNum(args, 0, 0.42, "velocity", "power", "strength", "v");
        return ctx -> selfLiving(ctx).ifPresent(entity -> {
            try {
                entity.setVelocity(entity.getVelocity().setY(velocity.applyAsDouble(ctx)));
            } catch (RuntimeException ignored) {
                SkillFaults.swallow("SkillExtras#jump");
            }
        });
    }

    private static java.util.function.Consumer<BehaviorContext> jumpTargetsAction(List<String> args) {
        var velocity = dynamicKeyedNum(args, 0, 0.42, "velocity", "power", "strength", "v");
        return ctx -> {
            double value = velocity.applyAsDouble(ctx);
            for (LivingEntity entity : targetEntities(ctx)) {
                try {
                    entity.setVelocity(entity.getVelocity().setY(value));
                } catch (RuntimeException ignored) {
                    SkillFaults.swallow("SkillExtras#jumpTargets");
                }
            }
        };
    }

    private static java.util.function.Consumer<BehaviorContext> forcePullAction(List<String> args) {
        var power = dynamicKeyedNum(args, 0, 1.0, "velocity", "power", "strength", "v");
        var vertical = dynamicKeyedNum(args, 1, Double.NaN,
                "vertical", "y", "yvelocity", "y-velocity", "yv");
        return ctx -> {
            Location source = selfLoc(ctx);
            if (source == null) return;
            double strength = power.applyAsDouble(ctx);
            if (!Double.isFinite(strength) || strength <= 0) return;
            double y = vertical.applyAsDouble(ctx);
            for (LivingEntity target : targetEntities(ctx)) {
                if (target == null) continue;
                try {
                    org.bukkit.util.Vector toward = source.toVector()
                            .subtract(target.getLocation().toVector());
                    if (toward.lengthSquared() < 1.0e-9) continue;
                    toward.normalize().multiply(strength);
                    if (Double.isFinite(y)) toward.setY(y);
                    target.setVelocity(toward);
                } catch (RuntimeException ignored) {
                    SkillFaults.swallow("SkillExtras#forcePull");
                }
            }
        };
    }

    private static java.util.function.Consumer<BehaviorContext> removeHeldItemAction(List<String> args) {
        return ctx -> {
            for (LivingEntity target : itemTargets(ctx)) {
                EntityEquipment equipment = target.getEquipment();
                if (equipment == null) continue;
                ItemStack held = equipment.getItem(EquipmentSlot.HAND);
                if (held != null && !held.getType().isAir()) {
                    try {
                        equipment.setItem(EquipmentSlot.HAND, null);
                    } catch (RuntimeException ignored) {
                        SkillFaults.swallow("SkillExtras#removeHeldItem");
                    }
                }
            }
        };
    }

    private static java.util.function.Consumer<BehaviorContext> swingAction(List<String> args) {
        String hand = keyedArg(args, "hand", "h");
        if (hand == null) hand = str(args, 0, "MAIN_HAND");
        boolean offHand = isOffHand(hand, args);
        return offHand ? SkillExtras::swingOffHandAction : SkillExtras::swingMainHandAction;
    }

    private static boolean isOffHand(String hand, List<String> args) {
        String explicit = keyedArg(args, "offhand", "off-hand", "off_hand");
        if (explicit != null) return Boolean.parseBoolean(explicit.trim());
        return hand != null && switch (hand.trim().toLowerCase(Locale.ROOT)) {
            case "off", "offhand", "off-hand", "off_hand", "left" -> true;
            default -> false;
        };
    }

    private static void swingMainHandAction(BehaviorContext ctx) {
        for (LivingEntity target : itemTargets(ctx)) {
            try { target.swingMainHand(); }
            catch (RuntimeException ignored) { SkillFaults.swallow("SkillExtras#swingMainHand"); }
        }
    }

    private static void swingOffHandAction(BehaviorContext ctx) {
        for (LivingEntity target : itemTargets(ctx)) {
            try { target.swingOffHand(); }
            catch (RuntimeException ignored) { SkillFaults.swallow("SkillExtras#swingOffHand"); }
        }
    }

    private static java.util.function.Consumer<BehaviorContext> setMaterialCooldownAction(
            List<String> args) {
        Material material = materialArgument(args);
        var ticks = dynamicKeyedNum(args, 1, 0, "ticks", "duration", "time", "t");
        return ctx -> {
            int value = cooldownTicks(ticks.applyAsDouble(ctx));
            for (LivingEntity target : itemTargets(ctx)) {
                if (!(target instanceof HumanEntity human)) continue;
                try {
                    human.setCooldown(material, value);
                } catch (RuntimeException ignored) {
                    SkillFaults.swallow("SkillExtras#setMaterialCooldown");
                }
            }
        };
    }

    private static java.util.function.Consumer<BehaviorContext> setGameModeAction(List<String> args) {
        GameMode mode = gameModeArgument(args);
        return ctx -> {
            for (LivingEntity target : itemTargets(ctx)) {
                if (!(target instanceof Player player)) continue;
                try {
                    player.setGameMode(mode);
                } catch (RuntimeException ignored) {
                    SkillFaults.swallow("SkillExtras#setGameMode");
                }
            }
        };
    }

    private static Material materialArgument(List<String> args) {
        String raw = keyedArg(args, "material", "mat", "m", "type", "item");
        if (raw == null) raw = str(args, 0, "");
        Material material = Material.matchMaterial(raw.trim());
        // Avoid Material#isAir here: on a Paper-free unit-test JVM it touches
        // Paper's registry bridge. The name check is sufficient for the
        // cooldown mechanic and keeps binding independent of server bootstrap.
        if (material == null || material.name().endsWith("_AIR") || "AIR".equals(material.name())) {
            throw new IllegalArgumentException("unknown cooldown material: " + raw);
        }
        return material;
    }

    private static GameMode gameModeArgument(List<String> args) {
        String raw = keyedArg(args, "mode", "gamemode", "game-mode", "m");
        if (raw == null) raw = str(args, 0, "SURVIVAL");
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        GameMode mode = switch (normalized) {
            case "0" -> GameMode.SURVIVAL;
            case "1" -> GameMode.CREATIVE;
            case "2" -> GameMode.ADVENTURE;
            case "3" -> GameMode.SPECTATOR;
            default -> {
                try {
                    yield GameMode.valueOf(normalized);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("unknown game mode: " + raw, e);
                }
            }
        };
        return mode;
    }

    private static int cooldownTicks(double value) {
        if (!Double.isFinite(value)) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, Math.round(value)));
    }

    private static List<LivingEntity> itemTargets(BehaviorContext ctx) {
        if (ctx == null) return List.of();
        if (!ctx.targets().isEmpty()) return ctx.targets();
        return ctx.targetEntity().map(List::of).orElseGet(() -> {
            if (!ctx.instanceValid()) return List.of();
            return ctx.instance().baseEntity()
                    .filter(LivingEntity.class::isInstance)
                    .map(LivingEntity.class::cast)
                    .map(List::of).orElseGet(List::of);
        });
    }

    /**
     * Paper clamps absorption to the MAX_ABSORPTION attribute. Older Paper
     * exposes that attribute as GENERIC_MAX_ABSORPTION, so resolve it by name.
     */
    private static void ensureAbsorptionCapacity(LivingEntity target, double amount) {
        if (target == null || amount <= 0.0 || !Double.isFinite(amount)) return;
        try {
            org.bukkit.attribute.Attribute attribute = null;
            for (String name : List.of("MAX_ABSORPTION", "GENERIC_MAX_ABSORPTION")) {
                try {
                    attribute = org.bukkit.attribute.Attribute.valueOf(name);
                    break;
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (attribute == null) return;
            org.bukkit.attribute.AttributeInstance instance = target.getAttribute(attribute);
            if (instance == null) {
                try {
                    target.registerAttribute(attribute);
                    instance = target.getAttribute(attribute);
                } catch (RuntimeException ignored) {
                    return;
                }
            }
            if (instance != null && instance.getValue() < amount) {
                instance.setBaseValue(Math.max(instance.getBaseValue(), amount));
            }
        } catch (RuntimeException ignored) {
            SkillFaults.swallow("SkillExtras#ensureAbsorptionCapacity");
        }
    }

    private static List<LivingEntity> targetEntities(BehaviorContext ctx) {
        if (ctx == null) return List.of();
        if (!ctx.targets().isEmpty()) return ctx.targets();
        return ctx.targetEntity().map(List::of).orElseGet(List::of);
    }

    private static String scoreboardTag(List<String> args) {
        String tag = str(args, 0, "");
        if (tag.startsWith("tag=")) tag = tag.substring(4);
        else if (tag.startsWith("t=")) tag = tag.substring(2);
        return tag.trim();
    }

    private static String normalizeFactionValue(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("none")
                || raw.equalsIgnoreCase("clear") || raw.equalsIgnoreCase("null")) return null;
        return raw.trim();
    }

    private static java.util.function.Consumer<BehaviorContext> lightningAction(List<String> args) {
        boolean effectOnly = keyedBoolean(args, 0, false,
                "effectonly", "effect-only", "visual", "visual-only");
        return ctx -> {
            List<Location> locations = new ArrayList<>();
            ctx.targetLocation().ifPresent(locations::add);
            for (LivingEntity target : targetEntities(ctx)) {
                Location location = target == null ? null : target.getLocation();
                if (location != null) locations.add(location);
            }
            if (locations.isEmpty()) {
                Location origin = selfLoc(ctx);
                if (origin != null) locations.add(origin);
            }
            for (Location location : locations) {
                if (location.getWorld() == null) continue;
                try {
                    if (effectOnly) location.getWorld().strikeLightningEffect(location);
                    else location.getWorld().strikeLightning(location);
                } catch (RuntimeException ignored) {
                    SkillFaults.swallow("SkillExtras#lightning");
                }
            }
        };
    }

    private static void addScoreboardTag(Entity entity, String tag) {
        if (entity == null || tag == null || tag.isBlank()) return;
        try {
            entity.addScoreboardTag(tag);
        } catch (RuntimeException ignored) {
            SkillFaults.swallow("SkillExtras#addScoreboardTag");
        }
    }

    private static void removeScoreboardTag(Entity entity, String tag) {
        if (entity == null || tag == null || tag.isBlank()) return;
        try {
            entity.removeScoreboardTag(tag);
        } catch (RuntimeException ignored) {
            SkillFaults.swallow("SkillExtras#removeScoreboardTag");
        }
    }

    private static void removeMatchingItems(Player player, ItemStack expected, int requested, boolean vanilla) {
        int remaining = requested;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir()
                    || !matchesItem(stack, expected, vanilla)) continue;
            int removed = Math.min(remaining, stack.getAmount());
            int after = stack.getAmount() - removed;
            if (after <= 0) player.getInventory().setItem(slot, null);
            else stack.setAmount(after);
            remaining -= removed;
        }
    }

    private static boolean isNumber(String value) {
        return value != null && value.trim().matches("[-+]?\\d+(?:\\.\\d+)?");
    }

    private static int parseAmount(String value) {
        try {
            return (int) Double.parseDouble(value.trim());
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    private static ItemStack resolveItem(String raw, int amount) {
        var resolver = itemResolver;
        if (resolver != null) {
            try {
                ItemStack resolved = resolver.apply(raw);
                if (resolved != null) {
                    Material type = resolved.getType();
                    // A custom resolver owns the item identity.  Accept a mock or
                    // version-specific stack with no Material type rather than
                    // falling through to Bukkit's registry-dependent lookup.
                    if (type == null || !"AIR".equalsIgnoreCase(type.name())) return resolved;
                }
            } catch (RuntimeException ignored) {
                SkillFaults.swallow("SkillExtras#1362");
            }
        }
        if (claimedItem.test(raw)) return null;
        Material material = Material.matchMaterial(raw);
        return material == null || material.isAir() ? null : new ItemStack(material, amount);
    }

    private static boolean vanillaItemName(String raw) {
        if (raw == null) return false;
        String name = raw.trim();
        int separator = name.indexOf(':');
        if (separator >= 0 && !name.substring(0, separator).equalsIgnoreCase("minecraft")) return false;
        return Material.matchMaterial(name) != null;
    }

    private static boolean matchesItem(ItemStack stack, ItemStack expected, boolean vanilla) {
        return stack.isSimilar(expected) || (vanilla && stack.getType() == expected.getType());
    }

    private static int countMatchingItems(LivingEntity entity, ItemStack expected, String slotName, boolean vanilla) {
        if (entity instanceof Player player && "all".equalsIgnoreCase(slotName)) {
            return countMatching(player.getInventory().getContents(), expected, vanilla);
        }
        if (entity.getEquipment() == null) return 0;
        String slot = slotName == null ? "all" : slotName.trim().toLowerCase(Locale.ROOT);
        if (!"all".equals(slot)) {
            EquipmentSlot equipmentSlot = equipmentSlot(slot);
            return countMatching(new ItemStack[]{entity.getEquipment().getItem(equipmentSlot)}, expected, vanilla);
        }
        int total = countMatching(new ItemStack[]{
                entity.getEquipment().getItem(EquipmentSlot.HAND),
                entity.getEquipment().getItem(EquipmentSlot.OFF_HAND),
                entity.getEquipment().getItem(EquipmentSlot.HEAD),
                entity.getEquipment().getItem(EquipmentSlot.CHEST),
                entity.getEquipment().getItem(EquipmentSlot.LEGS),
                entity.getEquipment().getItem(EquipmentSlot.FEET)
        }, expected, vanilla);
        return total;
    }

    private static int countMatching(ItemStack[] contents, ItemStack expected, boolean vanilla) {
        int total = 0;
        if (contents == null) return total;
        for (ItemStack stack : contents) {
            Material type = stack == null ? null : stack.getType();
            boolean sameItem = stack != null && type != null
                    && !"AIR".equalsIgnoreCase(type.name())
                    && matchesItem(stack, expected, vanilla);
            if (sameItem) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private enum AudienceMutation { ADD, REMOVE, UNSET }

    private static java.util.function.Consumer<BehaviorContext> audienceMutationAction(
            List<String> args, AudienceMutation mutation) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args == null ? List.<String>of() : args) {
            int equals = arg.indexOf('=');
            if (equals <= 0 || equals == arg.length() - 1) {
                throw new IllegalArgumentException("audience mechanics accept named options only");
            }
            String key = arg.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String normalized = switch (key) {
                case "a", "audience" -> "audience";
                case "m", "mobs" -> "mobs";
                case "isa", "ignoreskillaudience", "ignore-skill-audience" -> "ignore-skill-audience";
                default -> throw new IllegalArgumentException("unknown audience option: " + key);
            };
            if (options.putIfAbsent(normalized, arg.substring(equals + 1).trim()) != null) {
                throw new IllegalArgumentException("duplicate audience option: " + normalized);
            }
        }
        String audienceSpec = options.get("audience");
        if (audienceSpec != null && !ExecutionAudiences.isSupported(audienceSpec)) {
            throw new IllegalArgumentException("unsupported audience selector: " + audienceSpec);
        }
        String mobsSpec = options.get("mobs");
        if (mobsSpec != null && !mobsSpec.startsWith("@")) {
            throw new IllegalArgumentException("mobs must be an @Targeter");
        }
        String ignoreRaw = options.getOrDefault("ignore-skill-audience", "false");
        if (!ignoreRaw.equalsIgnoreCase("true") && !ignoreRaw.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("ignoreSkillAudience must be true or false");
        }
        boolean ignoreSkillAudience = Boolean.parseBoolean(ignoreRaw);

        return context -> {
            if (context == null) return;
            Set<UUID> selectedPlayers = selectAudiencePlayers(context, audienceSpec);
            if (selectedPlayers == null) return;
            if (!ignoreSkillAudience) {
                Set<UUID> current = context.executionAudience().orElse(null);
                Set<UUID> updated = mutateAudience(current, selectedPlayers, mutation);
                if (!Objects.equals(current, updated)) context.setExecutionAudience(updated);
            }
            Set<ModelInstance> mobTargets = new LinkedHashSet<>();
            if (!ignoreSkillAudience && context.instanceValid()) mobTargets.add(context.instance());
            if (mobsSpec != null) {
                AudienceMobSelector selector = audienceMobSelector;
                if (selector != null) {
                    Collection<? extends ModelInstance> selected;
                    try {
                        selected = selector.select(mobsSpec, context);
                    } catch (RuntimeException ignored) {
                        selected = null;
                    }
                    if (selected != null) mobTargets.addAll(selected);
                }
            }
            for (ModelInstance target : mobTargets) {
                if (target == null || !target.isValid()) continue;
                Set<UUID> current = target.executionAudience().orElse(null);
                Set<UUID> updated = mutateAudience(current, selectedPlayers, mutation);
                if (!Objects.equals(current, updated)) updateExecutionAudience(target, updated);
            }
        };
    }

    private static Set<UUID> selectAudiencePlayers(BehaviorContext context, String audienceSpec) {
        if (audienceSpec == null) {
            Set<UUID> selected = new LinkedHashSet<>();
            for (LivingEntity entity : targetEntities(context)) {
                if (entity instanceof Player player) selected.add(player.getUniqueId());
            }
            return selected;
        }
        ExecutionAudiences.Resolution resolution = ExecutionAudiences.resolve(audienceSpec, context);
        return resolution.supported() ? resolution.players() : null;
    }

    private static Set<UUID> mutateAudience(Set<UUID> current, Set<UUID> selected,
                                            AudienceMutation mutation) {
        if (mutation == AudienceMutation.UNSET) return null;
        if (mutation == AudienceMutation.ADD && current == null) return Set.copyOf(selected);
        Set<UUID> result = current == null ? allKnownPlayerIds() : new LinkedHashSet<>(current);
        if (mutation == AudienceMutation.ADD) result.addAll(selected);
        else result.removeAll(selected);
        return Set.copyOf(result);
    }

    private static Set<UUID> allKnownPlayerIds() {
        Set<UUID> players = new LinkedHashSet<>();
        try {
            for (org.bukkit.OfflinePlayer player : org.bukkit.Bukkit.getOfflinePlayers()) {
                if (player.getUniqueId() != null) players.add(player.getUniqueId());
            }
            for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (player.getUniqueId() != null) players.add(player.getUniqueId());
            }
        } catch (RuntimeException ignored) {
            for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (player.getUniqueId() != null) players.add(player.getUniqueId());
            }
        }
        return players;
    }

    private static java.util.List<Player> audiencePlayers(BehaviorContext ctx, String audience, double radius) {
        AudienceSpec spec = AudienceSpec.parse(audience, radius);
        String kind = spec.kind();
        java.util.LinkedHashSet<Player> players = new java.util.LinkedHashSet<>();
        if (ctx == null) return new ArrayList<>();
        if (kind.equals("target")) ctx.target().ifPresent(players::add);
        else if (kind.equals("targets")) {
            for (LivingEntity living : targetEntities(ctx)) {
                if (living instanceof Player player) players.add(player);
            }
        }
        else if (kind.equals("self")) ctx.instance().baseEntity().ifPresent(entity -> { if (entity instanceof Player player) players.add(player); });
        else if (kind.equals("all")) {
            players.addAll(org.bukkit.Bukkit.getOnlinePlayers());
            ctx.target().ifPresent(players::add);
        }
        else {
            Location origin = ctx.origin().orElse(null);
            if (origin == null && (kind.equals("nearby") || kind.equals("radius"))) {
                origin = ctx.target().map(Entity::getLocation).orElse(null);
            }
            final Location queryOrigin = origin;
            if (origin != null && origin.getWorld() != null) {
                if (kind.equals("world")) {
                    players.addAll(origin.getWorld().getPlayers());
                    ctx.target().ifPresent(players::add);
                }
                else if (kind.equals("nearby") || kind.equals("radius")) {
                    // Bukkit's spatial query only considers loaded chunks and can
                    // omit the context target during a same-tick skill dispatch.
                    // Audience semantics are online-player based, so filter the
                    // authoritative online set with the same world/distance rule.
                    for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                        if (withinRadius(queryOrigin, player.getLocation(), spec.radius())) {
                            players.add(player);
                        }
                    }
                }
            }
        }
        int candidateCount = players.size();
        players.removeIf(player -> !audiencePermissionAllowed(
                player, spec.permission(), spec.permissionExcluded()));
        if (spec.faction() != null) {
            var table = dev.helstera.api.behavior.Factions.table();
            players.removeIf(player -> table == null || !audienceFactionAllowed(
                    spec.faction(), table.factionOf(player), spec.factionExcluded()));
        }
        players.removeIf(player -> !ExecutionAudiences.allows(ctx, player));
        var diagnostic = audienceDiagnostic;
        if (diagnostic != null) {
            Location diagnosticOrigin = ctx.origin().orElse(null);
            Location diagnosticTarget = ctx.target().map(Entity::getLocation).orElse(null);
            Entity diagnosticSource = ctx.instanceValid()
                    ? ctx.instance().baseEntity().orElse(null) : null;
            var factionTable = dev.helstera.api.behavior.Factions.table();
            String sourceFaction = factionTable == null ? null : factionTable.factionOf(diagnosticSource);
            String targetFaction = factionTable == null
                    ? null : ctx.target().map(factionTable::factionOf).orElse(null);
            boolean allied = factionTable != null && diagnosticSource != null
                    && ctx.target().map(target -> factionTable.allied(diagnosticSource, target)).orElse(false);
            diagnostic.accept("kind=" + kind + " radius=" + spec.radius()
                    + " permission=" + spec.permission() + " faction=" + spec.faction()
                    + " candidates=" + candidateCount + " recipients=" + players.size()
                    + " source-faction=" + sourceFaction + " target-faction=" + targetFaction
                    + " allied=" + allied
                    + " origin-world=" + worldName(diagnosticOrigin)
                    + " target-world=" + worldName(diagnosticTarget)
                    + " origin=" + xyz(diagnosticOrigin)
                    + " target=" + xyz(diagnosticTarget));
        }
        return List.copyOf(players);
    }

    private static String worldName(Location location) {
        return location == null || location.getWorld() == null ? "null" : location.getWorld().getName();
    }

    private static String xyz(Location location) {
        return location == null ? "null" : String.format(Locale.ROOT, "%.1f,%.1f,%.1f",
                location.getX(), location.getY(), location.getZ());
    }

    static boolean withinRadius(Location origin, Location target, double radius) {
        if (origin == null || target == null || origin.getWorld() == null || target.getWorld() == null
                || !origin.getWorld().getName().equals(target.getWorld().getName())
                || !Double.isFinite(radius) || radius < 0) return false;
        double dx = target.getX() - origin.getX();
        double dy = target.getY() - origin.getY();
        double dz = target.getZ() - origin.getZ();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    static boolean audiencePermissionAllowed(Player player, String permission) {
        return audiencePermissionAllowed(player, permission, false);
    }

    static boolean audiencePermissionAllowed(Player player, String permission, boolean excluded) {
        if (player == null) return false;
        if (permission == null || permission.isBlank()) return true;
        boolean matches = player.hasPermission(permission);
        return excluded ? !matches : matches;
    }

    static boolean audienceFactionAllowed(String expected, String actual) {
        return audienceFactionAllowed(expected, actual, false);
    }

    static boolean audienceFactionAllowed(String expected, String actual, boolean excluded) {
        // An explicit faction selector filters recipients by faction. Alliance
        // relation is a separate targeting concern and must not depend on the
        // caster having a faction binding.
        if (expected == null || expected.isBlank()) return true;
        boolean matches = actual != null && expected.equalsIgnoreCase(actual);
        return excluded ? !matches : matches;
    }

    static record AudienceSpec(String kind, double radius, String permission, String faction,
                               boolean permissionExcluded, boolean factionExcluded) {
        static AudienceSpec parse(String raw, double fallbackRadius) {
            String value = raw == null ? "target" : raw.trim();
            if (value.startsWith("@")) value = value.substring(1);
            String permission = null;
            String faction = null;
            boolean permissionExcluded = false;
            boolean factionExcluded = false;
            int open = value.indexOf('{');
            if (open >= 0 && value.endsWith("}")) {
                String body = value.substring(open + 1, value.length() - 1);
                for (String part : body.split("[;,]")) {
                    int eq = part.indexOf('=');
                    if (eq <= 0) continue;
                    String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    String val = part.substring(eq + 1).trim();
                    boolean excluded = val.startsWith("!") || val.startsWith("-");
                    if (excluded) val = val.substring(1).trim();
                    if (key.equals("permission") || key.equals("perm")) {
                        permission = val;
                        permissionExcluded = excluded;
                    } else if (key.equals("faction")) {
                        faction = val;
                        factionExcluded = excluded;
                    }
                    else if (key.equals("radius") || key.equals("r")) {
                        try { fallbackRadius = Double.parseDouble(val); } catch (NumberFormatException ignored) { }
                    }
                }
                value = value.substring(0, open);
            }
            if (open < 0) {
                int colon = value.indexOf(':');
                if (colon > 0) {
                    String prefix = value.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String suffix = value.substring(colon + 1).trim();
                    if (prefix.equals("permission") || prefix.equals("perm")) {
                        permissionExcluded = suffix.startsWith("!") || suffix.startsWith("-");
                        if (permissionExcluded) suffix = suffix.substring(1).trim();
                        permission = suffix;
                        value = "all";
                    } else if (prefix.equals("faction")) {
                        factionExcluded = suffix.startsWith("!") || suffix.startsWith("-");
                        if (factionExcluded) suffix = suffix.substring(1).trim();
                        faction = suffix;
                        value = "world";
                    }
                }
            }
            String normalized = value.toLowerCase(Locale.ROOT);
            if (normalized.equals("pir") || normalized.equals("playersinradius") || normalized.equals("players-in-radius")) normalized = "nearby";
            if (normalized.equals("p") || normalized.equals("players")) normalized = "world";
            return new AudienceSpec(normalized, Math.max(0, fallbackRadius), permission, faction,
                    permissionExcluded, factionExcluded);
        }
    }

    private static Location selfLoc(BehaviorContext ctx) {
        if (ctx == null) return null;
        Location l = ctx.origin().orElse(null);
        if (l == null && ctx.instanceValid()) l = ctx.instance().location();
        return l == null || l.getWorld() == null ? null : l;
    }

    private static java.util.function.ToDoubleFunction<BehaviorContext> teleportSpread(
            List<String> args, int index, double fallback, String... names) {
        String raw = teleportArgument(args, index, names);
        if (raw == null || raw.isBlank()) return ctx -> fallback;
        if (raw.contains("<")) {
            return ctx -> {
                double value = Placeholders.resolveNumber(raw, ctx, Double.NaN);
                return Double.isFinite(value) && value >= 0 ? value : Double.NaN;
            };
        }
        double value = SpecArgs.num(List.of(raw), 0, fallback, "teleport spread");
        if (value < 0) throw new IllegalArgumentException("teleport spread must be non-negative: " + raw);
        return ctx -> value;
    }

    private static boolean teleportBoolean(List<String> args, int index, boolean fallback, String... names) {
        String raw = teleportArgument(args, index, names);
        return raw == null ? fallback : SpecArgs.bool(List.of(raw), 0, fallback, "teleport unsafe");
    }

    private static String teleportOrientation(List<String> args, int index, String fallback, String... names) {
        String raw = teleportArgument(args, index, names);
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim();
        if (value.equalsIgnoreCase("preserve") || value.equalsIgnoreCase("caster")
                || value.equalsIgnoreCase("target") || value.contains("<")) return value;
        try {
            if (!Double.isFinite(Double.parseDouble(value))) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("teleport orientation must be preserve, target or finite degrees: " + raw);
        }
    }

    private static String teleportArgument(List<String> args, int index, String... names) {
        String keyed = keyedArg(args, names);
        if (keyed != null && !keyed.isBlank()) return keyed.trim();
        if (args == null) return null;
        int positional = 0;
        for (String raw : args) {
            if (raw == null || raw.isBlank() || raw.contains("=")) continue;
            if (positional++ == index) return raw.trim();
        }
        return null;
    }

    private static void executeMythicTeleport(BehaviorContext ctx,
                                               java.util.function.ToDoubleFunction<BehaviorContext> spreadH,
                                               java.util.function.ToDoubleFunction<BehaviorContext> spreadV,
                                               boolean unsafe, String yaw, String pitch) {
        if (ctx == null || !ctx.instanceValid()) return;
        Entity caster = ctx.instance().baseEntity().orElse(null);
        if (caster == null) return;
        Location destination = ctx.targetLocation().orElseGet(() ->
                ctx.targetEntity().map(entity -> entity.getLocation().clone()).orElse(null));
        Location casterLocation;
        try {
            casterLocation = caster.getLocation();
        } catch (Throwable ignored) {
            return;
        }
        if (destination == null || destination.getWorld() == null
                || casterLocation == null || casterLocation.getWorld() == null) return;

        Location landing = destination.clone();
        if (!unsafe) {
            double horizontal = spreadH.applyAsDouble(ctx);
            double vertical = spreadV.applyAsDouble(ctx);
            if (!Double.isFinite(horizontal) || !Double.isFinite(vertical)
                    || horizontal < 0 || vertical < 0) return;
            if (horizontal > 0) {
                landing.add(java.util.concurrent.ThreadLocalRandom.current().nextDouble(-horizontal, horizontal), 0,
                        java.util.concurrent.ThreadLocalRandom.current().nextDouble(-horizontal, horizontal));
            }
            if (vertical > 0) {
                landing.add(0, java.util.concurrent.ThreadLocalRandom.current().nextDouble(-vertical, vertical), 0);
            }
            landing = findSafeLanding(caster, landing);
            if (landing == null) return;
        }
        Float resolvedYaw = teleportOrientationValue(yaw, ctx, casterLocation, landing, true);
        Float resolvedPitch = teleportOrientationValue(pitch, ctx, casterLocation, landing, false);
        if (resolvedYaw == null || resolvedPitch == null) return;
        landing.setYaw(resolvedYaw);
        landing.setPitch(resolvedPitch);
        try {
            caster.teleport(landing);
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#mythicTeleport");
        }
    }

    private static Float teleportOrientationValue(String raw, BehaviorContext ctx,
                                                   Location caster, Location destination,
                                                   boolean yaw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("preserve")
                || raw.equalsIgnoreCase("caster")) return yaw ? caster.getYaw() : caster.getPitch();
        if (raw.equalsIgnoreCase("target")) return yaw ? destination.getYaw() : destination.getPitch();
        double value = raw.contains("<")
                ? Placeholders.resolveNumber(raw, ctx, Double.NaN) : parseFinite(raw);
        if (!Double.isFinite(value)) return null;
        if (!yaw) value = Math.max(-90, Math.min(90, value));
        return (float) value;
    }

    private static double parseFinite(String raw) {
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    /** Finds a nearby standing position while retaining the requested x/z location. */
    private static Location findSafeLanding(Entity caster, Location requested) {
        if (caster == null || requested == null || requested.getWorld() == null) return null;
        int baseY = requested.getBlockY();
        for (int distance = 0; distance <= 16; distance++) {
            int[] candidates = distance == 0 ? new int[]{baseY}
                    : new int[]{baseY + distance, baseY - distance};
            for (int y : candidates) {
                Location candidate = requested.clone();
                candidate.setY(y);
                if (safeLandingAt(caster, candidate)) return candidate;
            }
        }
        return null;
    }

    private static boolean safeLandingAt(Entity caster, Location candidate) {
        org.bukkit.World world = candidate.getWorld();
        if (world == null) return false;
        Location source = caster.getLocation();
        org.bukkit.util.BoundingBox box = caster.getBoundingBox();
        double minXOffset = box == null ? -0.3 : box.getMinX() - source.getX();
        double maxXOffset = box == null ? 0.3 : box.getMaxX() - source.getX();
        double minZOffset = box == null ? -0.3 : box.getMinZ() - source.getZ();
        double maxZOffset = box == null ? 0.3 : box.getMaxZ() - source.getZ();
        double minYOffset = box == null ? 0 : box.getMinY() - source.getY();
        double maxYOffset = box == null ? 1.8 : box.getMaxY() - source.getY();
        int minX = (int) Math.floor(candidate.getX() + minXOffset + 1.0e-7);
        int maxX = (int) Math.floor(candidate.getX() + maxXOffset - 1.0e-7);
        int minZ = (int) Math.floor(candidate.getZ() + minZOffset + 1.0e-7);
        int maxZ = (int) Math.floor(candidate.getZ() + maxZOffset - 1.0e-7);
        int minY = (int) Math.floor(candidate.getY() + minYOffset + 1.0e-7);
        int maxY = (int) Math.floor(candidate.getY() + maxYOffset - 1.0e-7);
        if (minX > maxX || minZ > maxZ || minY > maxY) return false;
        try {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    org.bukkit.block.Block ground = world.getBlockAt(x, minY - 1, z);
                    // Block#isPassable reads the live block collision shape and avoids
                    // touching Material's server registry during offline execution.
                    if (ground == null || ground.isLiquid() || ground.isPassable()) return false;
                    for (int y = minY; y <= maxY; y++) {
                        org.bukkit.block.Block body = world.getBlockAt(x, y, z);
                        if (body == null || body.isLiquid() || !body.isPassable()) return false;
                    }
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void rotateToward(Entity self, Location target) {
        if (self == null || target == null) return;
        try {
            Location from = self.getLocation().clone();
            double dx = target.getX() - from.getX();
            double dy = target.getY() - from.getY();
            double dz = target.getZ() - from.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            from.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
            from.setPitch((float) Math.toDegrees(Math.atan2(-dy, horizontal)));
            self.teleport(from);
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#541");
        }
    }

    private static java.util.Optional<LivingEntity> selfLiving(BehaviorContext ctx) {
        if (ctx == null || !ctx.instanceValid()) return java.util.Optional.empty();
        var e = ctx.instance().baseEntity().orElse(null);
        return e instanceof LivingEntity le ? java.util.Optional.of(le) : java.util.Optional.empty();
    }

    private record VariableRef(String name, String scope) {
    }

    private record LocationValue(String reference, String world, double x, double y, double z) {
        boolean isReference() {
            return reference != null;
        }
    }

    private static VariableRef variableRef(List<String> args) {
        String name = null;
        String scope = null;
        List<String> positional = new ArrayList<>();
        if (args != null) {
            for (String raw : args) {
                if (raw == null || raw.isBlank()) continue;
                int equals = raw.indexOf('=');
                if (equals > 0) {
                    String key = raw.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                    String value = raw.substring(equals + 1).trim();
                    if (key.equals("var") || key.equals("variable") || key.equals("name")) name = value;
                    else if (key.equals("scope")) scope = value;
                    else { }
                } else {
                    positional.add(raw.trim());
                }
            }
        }
        if (scope == null && positional.size() > 1
                && VariableLocations.normalizeScope(positional.get(0)) != null) {
            scope = positional.get(0);
            if (name == null) name = positional.get(1);
        } else {
            if (name == null && !positional.isEmpty()) name = positional.get(0);
            if (scope == null && positional.size() > 1) scope = positional.get(1);
        }
        if (name == null) name = "";
        if (scope == null && name.indexOf('.') > 0) {
            int dot = name.indexOf('.');
            scope = name.substring(0, dot);
            name = name.substring(dot + 1);
        }
        String normalized = VariableLocations.normalizeScope(scope);
        return new VariableRef(name == null ? "" : name.trim(), normalized == null ? "skill" : normalized);
    }

    private static String keyedArg(List<String> args, String... names) {
        if (args == null) return null;
        for (String raw : args) {
            if (raw == null) continue;
            int equals = raw.indexOf('=');
            if (equals <= 0) continue;
            String key = raw.substring(0, equals).trim();
            for (String wanted : names) {
                if (key.equalsIgnoreCase(wanted)) return raw.substring(equals + 1).trim();
            }
        }
        return null;
    }

    private static double variableNumber(List<String> args, int positionalIndex, double fallback,
                                         String... names) {
        String keyed = keyedArg(args, names);
        if (keyed == null || keyed.isBlank()) return num(args, positionalIndex, fallback);
        try {
            return Double.parseDouble(keyed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("variable value is not numeric: " + keyed, e);
        }
    }

    private static NumberRange variableConditionRange(List<String> args) {
        String expression = keyedArg(args, "value", "val", "range", "r");
        String minimumText = keyedArg(args, "min", "minimum", "from");
        String maximumText = keyedArg(args, "max", "maximum", "to");
        List<String> positional = args == null ? List.of() : args.stream()
                .filter(raw -> raw != null && raw.indexOf('=') < 0)
                .map(String::trim).filter(raw -> !raw.isEmpty()).toList();
        boolean keyedVariable = keyedArg(args, "var", "variable", "name") != null;
        int rangeStart = 0;
        if (!keyedVariable && !positional.isEmpty()) {
            boolean positionalScope = VariableLocations.normalizeScope(positional.get(0)) != null
                    && positional.size() > 1;
            rangeStart = positionalScope ? 2 : 1;
        }
        List<String> positionalBounds = positional.size() <= rangeStart
                ? List.of() : positional.subList(rangeStart, positional.size());

        if (expression != null) {
            if (minimumText != null || maximumText != null || !positionalBounds.isEmpty()) {
                throw new IllegalArgumentException("variable range cannot mix value and min/max bounds");
            }
            NumberRange range = numberRange(List.of(expression), 0);
            if (!validVariableConditionRange(range)) {
                throw new IllegalArgumentException("variable range is invalid: " + expression);
            }
            return range;
        }

        if (minimumText == null && maximumText == null && positionalBounds.isEmpty()) {
            throw new IllegalArgumentException("variable range is required");
        }
        if ((minimumText != null || maximumText != null) && !positionalBounds.isEmpty()) {
            throw new IllegalArgumentException("variable range cannot mix named and positional bounds");
        }
        if (!positionalBounds.isEmpty()) {
            if (positionalBounds.size() > 2) {
                throw new IllegalArgumentException("variable range accepts at most two positional bounds");
            }
            minimumText = positionalBounds.get(0);
            if (positionalBounds.size() == 2) maximumText = positionalBounds.get(1);
        }

        double minimum = minimumText == null ? Double.NEGATIVE_INFINITY
                : finiteVariableBound(minimumText);
        double maximum = maximumText == null ? Double.POSITIVE_INFINITY
                : finiteVariableBound(maximumText);
        NumberRange range = new NumberRange(minimum, maximum, true, true);
        if (!validVariableConditionRange(range)) {
            throw new IllegalArgumentException("variable range minimum exceeds maximum");
        }
        return range;
    }

    private static double finiteVariableBound(String raw) {
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value)) throw new IllegalArgumentException("variable range bound must be finite");
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("variable range bound is not numeric: " + raw, e);
        }
    }

    private static boolean validVariableConditionRange(NumberRange range) {
        if (range == null || Double.isNaN(range.min()) || Double.isNaN(range.max())
                || range.min() > range.max()) return false;
        return (Double.isFinite(range.min()) || range.min() == Double.NEGATIVE_INFINITY)
                && (Double.isFinite(range.max()) || range.max() == Double.POSITIVE_INFINITY);
    }

    private static Object parseVariableValue(List<String> args) {
        String type = keyedArg(args, "type");
        if (type == null) type = "DOUBLE";
        String raw = keyedArg(args, "value", "val");
        if (raw == null) raw = str(args, 1, "");
        return switch (type.trim().toUpperCase(Locale.ROOT)) {
            case "INTEGER", "INT" -> Integer.valueOf(raw.trim());
            case "LONG" -> Long.valueOf(raw.trim());
            case "FLOAT" -> Float.valueOf(raw.trim());
            case "DOUBLE", "NUMBER", "DECIMAL" -> Double.valueOf(raw.trim());
            case "BOOLEAN", "BOOL" -> Boolean.parseBoolean(raw.trim());
            case "LIST" -> List.of(raw.split("\\s*[,|]\\s*"));
            case "SET" -> java.util.Set.copyOf(List.of(raw.split("\\s*[,|]\\s*")));
            case "MAP" -> parseVariableMap(raw);
            case "LOCATION", "LOC" -> parseLocationValue(raw);
            case "STRING", "TEXT" -> raw;
            default -> throw new IllegalArgumentException("unsupported variable type: " + type);
        };
    }

    private static LocationValue parseLocationValue(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("location value is empty");
        if (value.startsWith("@")) return new LocationValue(value, null, 0, 0, 0);
        String[] parts = value.split("\\s*,\\s*");
        String world = null;
        int offset = 0;
        if (parts.length == 4) {
            world = parts[0].isBlank() ? null : parts[0].trim();
            offset = 1;
        } else if (parts.length != 3) {
            throw new IllegalArgumentException("location must be x,y,z or world,x,y,z");
        }
        try {
            return new LocationValue(null, world,
                    Double.parseDouble(parts[offset].trim()),
                    Double.parseDouble(parts[offset + 1].trim()),
                    Double.parseDouble(parts[offset + 2].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("location coordinates must be numeric", e);
        }
    }

    private static Map<String, String> parseVariableMap(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String entry : raw.split("[,|]")) {
            int separator = entry.indexOf(':');
            if (separator <= 0) continue;
            values.put(entry.substring(0, separator).trim(), entry.substring(separator + 1).trim());
        }
        return Map.copyOf(values);
    }

    private static void setRawVariable(BehaviorContext ctx, VariableRef ref, Object value) {
        if (ctx == null || ref == null || ref.name().isEmpty() || value == null) return;
        if (value instanceof LocationValue locationValue) {
            Location location = resolveLocationValue(ctx, locationValue);
            if (location == null) return;
            if (ref.scope().equals("skill")) {
                ctx.skillTree().ifPresent(tree -> tree.setLocation(ref.name(), location));
            } else {
                VariableLocations.set(ref.scope(), ref.name(), ctx, location);
            }
            return;
        }
        if (ref.scope().equals("skill")) {
            if (ctx.skillTree().isPresent()) ctx.skillTree().orElseThrow().setVariable(ref.name(), value);
            else if (hasInstance(ctx) && value instanceof Number number) varsOf(id(ctx)).put(ref.name(), number.doubleValue());
            return;
        }
        String key = VariableLocations.scopeKey(ref.scope(), ctx);
        if (key != null) scopedValuesOf(key).put(ref.name(), value);
    }

    private static Location resolveLocationValue(BehaviorContext ctx, LocationValue value) {
        if (value.isReference()) {
            String reference = value.reference().substring(1).trim().toLowerCase(Locale.ROOT);
            return switch (reference) {
                case "origin" -> ctx.origin().orElseGet(() -> hasInstance(ctx) ? ctx.instance().location() : null);
                case "selflocation", "casterlocation" -> ctx.instanceValid()
                        ? ctx.instance().baseEntity().map(Entity::getLocation).orElse(ctx.instance().location()) : null;
                case "targetlocation" -> ctx.targetEntity().map(Entity::getLocation).orElse(null);
                case "triggerlocation" -> ctx.trigger().map(Entity::getLocation).orElse(null);
                default -> null;
            };
        }
        org.bukkit.World world = null;
        if (value.world() != null && !value.world().isBlank()) {
            world = org.bukkit.Bukkit.getWorld(value.world());
            if (world == null) {
                world = ctx.origin().map(Location::getWorld)
                        .filter(candidate -> candidate != null
                                && candidate.getName().equalsIgnoreCase(value.world()))
                        .orElse(null);
            }
        }
        if (world == null) world = ctx.origin().map(Location::getWorld).orElse(null);
        return new Location(world, value.x(), value.y(), value.z());
    }

    private static void setVariable(BehaviorContext ctx, VariableRef ref, double value) {
        setRawVariable(ctx, ref, value);
    }
    private static double varOf(BehaviorContext ctx, VariableRef ref) {
        if (ctx == null || ref == null || ref.name().isEmpty()) return Double.NEGATIVE_INFINITY;
        Object raw = rawVariable(ctx, ref);
        return raw instanceof Number number ? number.doubleValue() : Double.NEGATIVE_INFINITY;
    }
    private static boolean hasVariable(BehaviorContext ctx, VariableRef ref) {
        if (ctx == null || ref == null || ref.name().isEmpty()) return false;
        if (ref.scope().equals("skill")) {
            return hasVar(ctx, ref.name())
                    || ctx.skillTree().flatMap(tree -> tree.location(ref.name())).isPresent();
        }
        String key = VariableLocations.scopeKey(ref.scope(), ctx);
        return key != null && (scopedValuesOf(key).containsKey(ref.name())
                || VariableLocations.get(ref.scope(), ref.name(), ctx).isPresent());
    }

    private static void unsetScopedVariable(BehaviorContext ctx, VariableRef ref) {
        if (ctx == null || ref == null || ref.name().isEmpty()) return;
        if (ref.scope().equals("skill")) {
            if (ctx.skillTree().isPresent()) {
                ctx.skillTree().orElseThrow().unsetVariable(ref.name());
                ctx.skillTree().orElseThrow().unsetLocation(ref.name());
            } else if (hasInstance(ctx)) {
                varsOf(id(ctx)).remove(ref.name());
            }
            return;
        }
        String key = VariableLocations.scopeKey(ref.scope(), ctx);
        if (key != null) {
            Map<String, Object> values = scopedValuesOf(key);
            values.remove(ref.name());
            if (values.isEmpty()) SCOPED_VALUES.remove(key, values);
            VariableLocations.unset(ref.scope(), ref.name(), ctx);
        }
    }
    private static void unsetVariable(BehaviorContext ctx, VariableRef ref) {
        unsetScopedVariable(ctx, ref);
        if (ctx == null || ref == null || ref.name().isEmpty()) return;
        VariableLocations.unset(ref.scope(), ref.name(), ctx);
    }

    private static void unsetLocation(BehaviorContext ctx, VariableRef ref) {
        if (ctx == null || ref == null || ref.name().isEmpty()) return;
        if (ref.scope().equals("skill")) {
            ctx.skillTree().ifPresent(tree -> tree.unsetLocation(ref.name()));
        } else {
            VariableLocations.unset(ref.scope(), ref.name(), ctx);
        }
    }
    private static boolean hasInstance(BehaviorContext ctx) {
        return ctx != null && ctx.instanceValid();
    }

    private static int id(BehaviorContext ctx) {
        return ctx.instance().instanceId();
    }

    private static Map<String, Double> varsOf(int id) {
        return VARS.computeIfAbsent(id, k -> new HashMap<>());
    }
    private static Map<String, Object> scopedValuesOf(String key) {
        return SCOPED_VALUES.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>());
    }

    private static Map<String, Integer> scoresOf(int id) {
        return SCORES.computeIfAbsent(id, k -> new HashMap<>());
    }

    private static Object rawVariable(BehaviorContext ctx, VariableRef ref) {
        if (ctx == null || ref == null || ref.name().isEmpty()) return null;
        if (ref.scope().equals("skill")) {
            Object value = ctx.skillTree().flatMap(tree -> tree.variable(ref.name())).orElse(null);
            if (value != null) return value;
            Location location = ctx.skillTree().flatMap(tree -> tree.location(ref.name())).orElse(null);
            if (location != null) return location;
            if (hasInstance(ctx)) return varsOf(id(ctx)).get(ref.name());
            return null;
        }
        String key = VariableLocations.scopeKey(ref.scope(), ctx);
        if (key == null) return null;
        Object value = scopedValuesOf(key).get(ref.name());
        return value != null ? value : VariableLocations.get(ref.scope(), ref.name(), ctx).orElse(null);
    }

    private static boolean variableEquals(BehaviorContext ctx, VariableRef ref, String expected) {
        Object actual = rawVariable(ctx, ref);
        if (actual == null) return false;
        if (actual instanceof Number number) {
            try {
                return Double.compare(number.doubleValue(), Double.parseDouble(expected)) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if (actual instanceof Boolean bool) return bool.toString().equalsIgnoreCase(expected);
        return String.valueOf(actual).equals(expected);
    }

    private static boolean variableContains(BehaviorContext ctx, VariableRef ref, String expected,
                                            String compareType) {
        Object actual = rawVariable(ctx, ref);
        String mode = compareType == null ? "ALL" : compareType.trim().toUpperCase(Locale.ROOT);
        if (actual instanceof CharSequence text) {
            String value = text.toString();
            return switch (mode) {
                case "STARTS_WITH" -> value.startsWith(expected);
                case "ENDS_WITH" -> value.endsWith(expected);
                case "ANY", "ALL" -> value.contains(expected);
                default -> false;
            };
        }
        if (actual instanceof java.util.Collection<?> values) {
            List<String> wanted = List.of(expected.split("\\s*[,|]\\s*"));
            java.util.function.Predicate<String> contains = value -> values.stream()
                    .anyMatch(actualValue -> variableEqualsValue(actualValue, value));
            return switch (mode) {
                case "ANY" -> wanted.stream().anyMatch(contains);
                case "ALL" -> wanted.stream().allMatch(contains);
                default -> false;
            };
        }
        if (actual instanceof java.util.Map<?, ?> values) {
            List<String> wanted = List.of(expected.split("\\s*[,|]\\s*"));
            java.util.function.Predicate<String> contains = value ->
                    values.keySet().stream().anyMatch(actualValue -> variableEqualsValue(actualValue, value))
                            || values.values().stream().anyMatch(actualValue -> variableEqualsValue(actualValue, value));
            return switch (mode) {
                case "ANY" -> wanted.stream().anyMatch(contains);
                case "ALL" -> wanted.stream().allMatch(contains);
                default -> false;
            };
        }
        return false;
    }

    private static int integerArg(List<String> args, int positionalIndex, String... names) {
        String raw = keyedArg(args, names);
        if (raw == null) raw = str(args, positionalIndex, "-1");
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static Object indexedVariable(BehaviorContext ctx, VariableRef ref, int index) {
        if (index < 0) return null;
        Object actual = rawVariable(ctx, ref);
        if (actual instanceof List<?> values) {
            return index < values.size() ? values.get(index) : null;
        }
        if (actual != null && actual.getClass().isArray()) {
            return index < java.lang.reflect.Array.getLength(actual)
                    ? java.lang.reflect.Array.get(actual, index) : null;
        }
        return null;
    }

    private static boolean mapKeyExists(Object actual, String key) {
        return actual instanceof Map<?, ?> values && key != null && values.containsKey(key);
    }

    private static boolean mapValueEquals(Object actual, String key, String expected) {
        if (!(actual instanceof Map<?, ?> values) || key == null || !values.containsKey(key)) return false;
        return variableEqualsValue(values.get(key), expected);
    }

    private static boolean variableEqualsValue(Object actual, String expected) {
        if (actual == null || expected == null) return false;
        if (actual instanceof Number number) {
            try {
                return Double.compare(number.doubleValue(), Double.parseDouble(expected)) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if (actual instanceof Boolean bool) return bool.toString().equalsIgnoreCase(expected);
        return String.valueOf(actual).equals(expected);
    }

    private static double numericValue(Object actual) {
        if (actual instanceof Number number) return number.doubleValue();
        if (actual instanceof String text) {
            try { return Double.parseDouble(text.trim()); }
            catch (NumberFormatException ignored) { }
        }
        return Double.NaN;
    }

    private static double mapNumericValue(Object actual, String key) {
        if (!(actual instanceof Map<?, ?> values) || !values.containsKey(key)) return Double.NaN;
        return numericValue(values.get(key));
    }
    private static double varOf(BehaviorContext ctx, String name) {
        if (ctx == null || name.isEmpty()) return Double.NEGATIVE_INFINITY;
        Object scoped = ctx.skillTree().flatMap(tree -> tree.variable(name)).orElse(null);
        if (scoped instanceof Number number) return number.doubleValue();
        if (!hasInstance(ctx)) return Double.NEGATIVE_INFINITY;
        return varsOf(id(ctx)).getOrDefault(name, Double.NEGATIVE_INFINITY);
    }

    private static boolean hasVar(BehaviorContext ctx, String name) {
        if (ctx == null || name.isEmpty()) return false;
        return ctx.skillTree().flatMap(tree -> tree.variable(name)).isPresent()
                || (hasInstance(ctx) && varsOf(id(ctx)).containsKey(name));
    }

    private static void applyDamage(BehaviorContext ctx, LivingEntity target, double amount) {
        String cause = damageCause == null ? "MAGIC" : damageCause.apply(ctx, target);
        applyDamage(ctx, target, amount, cause);
    }

    private static void applyDamage(BehaviorContext ctx, LivingEntity target, double amount, String cause) {
        if (target == null || amount <= 0) return;
        try {
            DamagePolicy policy = damagePolicy;
            if (cause == null || cause.isBlank()) cause = "MAGIC";
            if (policy != null) amount = policy.apply(ctx, target, amount, cause);
            if (amount <= 0) return;
            target.damage(amount, ctx != null && ctx.instanceValid()
                    ? ctx.instance().baseEntity().orElse(null) : null);
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#damage");
        }
    }

    /** Builds a bounded Mythic-style DoT while retaining the legacy infinite form. */
    private static java.util.function.Consumer<BehaviorContext> dotFactory(List<String> args, boolean targeterAware) {
        String damageRaw = keyedArg(args, "damage", "amount", "d");
        if (damageRaw == null) damageRaw = str(args, 0, "1");
        String durationRaw = keyedArg(args, "duration", "dur", "time");
        String intervalRaw = keyedArg(args, "interval", "period", "ticks");
        boolean bounded = durationRaw != null || args.size() >= 3;
        if (bounded && durationRaw == null) durationRaw = str(args, 1, "20");
        if (intervalRaw == null) intervalRaw = bounded ? str(args, 2, "20") : str(args, 1, "20");
        final java.util.function.ToDoubleFunction<BehaviorContext> damage =
                dynamicNum(List.of(damageRaw), 0, 1.0);
        final int duration = durationRaw == null ? -1 : dotTicks(durationRaw, 20);
        final int interval = Math.max(1, dotTicks(intervalRaw, 20));
        return ctx -> {
            Plugin plugin = host;
            if (plugin == null || ctx == null || !ctx.instanceValid()) return;
            List<LivingEntity> targets = targeterAware
                    ? List.copyOf(targetEntities(ctx))
                    : ctx.targetEntity().map(List::of).orElse(List.of());
            if (targets.isEmpty() || damage.applyAsDouble(ctx) <= 0) return;
            try {
                final int[] elapsed = {0};
                org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                    boolean live = false;
                    double amount = damage.applyAsDouble(ctx);
                    for (LivingEntity target : targets) {
                        if (target.isValid() && !target.isDead()) {
                            live = true;
                            applyDamage(ctx, target, amount, "MAGIC");
                        }
                    }
                    elapsed[0] += interval;
                    if (!live || duration >= 0 && elapsed[0] >= Math.max(interval, duration)) {
                        task.cancel();
                    }
                }, 0L, interval);
            } catch (Throwable ignored) {
                SkillFaults.swallow("SkillExtras#dot");
            }
        };
    }

    private static int dotTicks(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (value.endsWith("s")) return Math.max(1, (int) Math.ceil(
                    Double.parseDouble(value.substring(0, value.length() - 1)) * 20.0));
            if (value.endsWith("t")) value = value.substring(0, value.length() - 1).trim();
            return Math.max(1, (int) Math.ceil(Double.parseDouble(value)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int scoreOf(BehaviorContext ctx, String name) {
        if (!hasInstance(ctx) || name.isEmpty()) return Integer.MIN_VALUE;
        return scoresOf(id(ctx)).getOrDefault(name, Integer.MIN_VALUE);
    }

    private static boolean hasScore(BehaviorContext ctx, String name) {
        return hasInstance(ctx) && !name.isEmpty() && scoresOf(id(ctx)).containsKey(name);
    }

    private static java.util.function.Consumer<BehaviorContext> globalCooldownAction(
            List<String> args, boolean mythicTicks) {
        String name = mythicTicks ? "__mythic_gcd" : str(args, 0, "");
        String raw = mythicTicks ? str(args, 0, "20") : str(args, 1, "1");
        boolean ticks = mythicTicks || raw.toLowerCase(Locale.ROOT).endsWith("t");
        if (raw.toLowerCase(Locale.ROOT).endsWith("t")) {
            raw = raw.substring(0, raw.length() - 1).trim();
        }
        var duration = dynamicNum(List.of(raw), 0, mythicTicks ? 20.0 : 1.0);
        String cooldownName = name;
        boolean tickDuration = ticks;
        return ctx -> {
            if (cooldownName.isEmpty()) return;
            double value = duration.applyAsDouble(ctx) / (tickDuration ? 20.0 : 1.0);
            String key = globalCooldownKey(ctx, cooldownName);
            if (value <= 0) {
                GLOBAL_CD.remove(key);
                return;
            }
            GLOBAL_CD.put(key, System.currentTimeMillis() + (long) (value * 1000));
        };
    }

    private static String globalCooldownKey(BehaviorContext ctx, String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return hasInstance(ctx) ? id(ctx) + ":" + normalized : normalized;
    }

    private static boolean globalCooldownReady(BehaviorContext ctx, String name, double seconds) {
        if (name == null || name.isEmpty() || seconds <= 0) return true;
        Long until = GLOBAL_CD.get(globalCooldownKey(ctx, name));
        return until == null || System.currentTimeMillis() >= until;
    }

    private static int countNear(BehaviorContext ctx, double radius, boolean includeMobs) {
        Location me = selfLoc(ctx);
        if (me == null || radius <= 0) return 0;
        int n = 0;
        try {
            for (var e : me.getWorld().getNearbyEntities(me, radius, radius, radius)) {
                if (e instanceof Player p) {
                    if (p.getGameMode() != GameMode.SPECTATOR
                            && p.getGameMode() != GameMode.CREATIVE) n++;
                } else if (includeMobs && e instanceof LivingEntity le && le.isValid()) {
                    n++;
                }
                if (n > 512) break;   // 上限保护：密集怪物区不该让一次条件求值遍历上千实体
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#887");
        }
        return n;
    }

    private static int countMobsNear(BehaviorContext ctx, double radius) {
        Location me = selfLoc(ctx);
        if (me == null || radius <= 0) return 0;
        Entity source = ctx != null && ctx.instanceValid()
                ? ctx.instance().baseEntity().orElse(null) : null;
        int n = 0;
        try {
            for (var entity : me.getWorld().getNearbyEntities(me, radius, radius, radius)) {
                if (entity != source && entity instanceof LivingEntity living
                        && !(living instanceof Player) && living.isValid()) {
                    n++;
                }
                if (n > 512) break;
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#countMobsNear");
        }
        return n;
    }

    private static int countMobsNearOrigin(BehaviorContext ctx, double radius, String types) {
        Location origin = ctx == null ? null : ctx.origin().orElse(null);
        if (origin == null || origin.getWorld() == null || !Double.isFinite(radius) || radius < 0) {
            return 0;
        }
        Entity source = ctx.instanceValid()
                ? ctx.instance().baseEntity().orElse(null) : null;
        Set<String> wanted = new java.util.HashSet<>();
        if (types != null && !types.isBlank()) {
            for (String raw : types.split(",")) {
                String type = raw.trim().toUpperCase(Locale.ROOT);
                if (type.isBlank()) continue;
                try {
                    wanted.add(org.bukkit.entity.EntityType.valueOf(type).name());
                } catch (IllegalArgumentException ignored) {
                    return 0;
                }
            }
        }
        int count = 0;
        try {
            for (Entity entity : origin.getWorld().getNearbyEntities(origin, radius, radius, radius)) {
                if (entity == source || !(entity instanceof org.bukkit.entity.Mob mob)
                        || !mob.isValid()) continue;
                if (!wanted.isEmpty() && !wanted.contains(entity.getType().name())) continue;
                count++;
                if (count > 512) break;
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#countMobsNearOrigin");
        }
        return count;
    }

    private static int countLivingNear(BehaviorContext ctx, double radius) {
        Location me = selfLoc(ctx);
        if (me == null || !Double.isFinite(radius) || radius < 0) return 0;
        int count = 0;
        try {
            for (Entity entity : me.getWorld().getNearbyEntities(me, radius, radius, radius)) {
                if (entity instanceof LivingEntity living && living.isValid()) count++;
                if (count > 512) break;
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#countLivingNear");
        }
        return count;
    }

    private static int countChildren(BehaviorContext ctx) {
        if (ctx == null || !ctx.instanceValid()) return 0;
        Entity parent = ctx.instance().baseEntity().orElse(null);
        if (parent == null) return 0;
        int count = 0;
        try {
            for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
                for (Entity candidate : world.getEntities()) {
                    if (candidate instanceof LivingEntity
                            && dev.helstera.api.behavior.MobRelations.isChild(parent, candidate)) count++;
                }
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#countChildren");
            return 0;
        }
        return count;
    }

    private static int countMobsInChunk(BehaviorContext ctx) {
        Location location = selfLoc(ctx);
        if (location == null || location.getWorld() == null) return 0;
        int count = 0;
        try {
            for (Entity entity : location.getChunk().getEntities()) {
                if (entity instanceof org.bukkit.entity.Mob mob && mob.isValid()) count++;
                if (count > 512) break;
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#countMobsInChunk");
        }
        return count;
    }

    private static boolean matchesDimension(Location location, String wanted) {
        if (location == null || location.getWorld() == null || wanted == null || wanted.isBlank()) {
            return false;
        }
        String actual = location.getWorld().getEnvironment().name();
        for (String raw : wanted.split(",")) {
            String expected = raw.trim().toUpperCase(Locale.ROOT);
            expected = switch (expected) {
                case "OVERWORLD" -> "NORMAL";
                case "END" -> "THE_END";
                default -> expected;
            };
            if (actual.equals(expected)) return true;
        }
        return false;
    }

    private static boolean hasPlayerWithin(BehaviorContext ctx, double radius) {
        if (!Double.isFinite(radius) || radius < 0) return false;
        LivingEntity target = targetOnlyEntity(ctx);
        Location origin = target == null ? selfLoc(ctx) : target.getLocation();
        if (origin == null || origin.getWorld() == null) return false;
        double squared = radius * radius;
        try {
            return org.bukkit.Bukkit.getOnlinePlayers().stream()
                    .filter(player -> player.getGameMode() != GameMode.SPECTATOR)
                    .filter(player -> player.getWorld().equals(origin.getWorld()))
                    .anyMatch(player -> player.getLocation().distanceSquared(origin) <= squared);
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#hasPlayerWithin");
            return false;
        }
    }

    private static java.util.OptionalDouble targetAiDistance(BehaviorContext ctx) {
        LivingEntity subject = targetOnlyEntity(ctx);
        if (subject == null && ctx != null && ctx.instanceValid()) {
            Entity caster = ctx.instance().baseEntity().orElse(null);
            subject = caster instanceof LivingEntity living ? living : null;
        }
        if (!(subject instanceof org.bukkit.entity.Mob mob)) {
            return ctx != null && ctx.distanceToTarget() >= 0
                    ? java.util.OptionalDouble.of(ctx.distanceToTarget())
                    : java.util.OptionalDouble.empty();
        }
        LivingEntity aiTarget = mob.getTarget();
        if (aiTarget == null || !aiTarget.isValid() || aiTarget.getWorld() != subject.getWorld()) {
            return ctx != null && ctx.distanceToTarget() >= 0
                    ? java.util.OptionalDouble.of(ctx.distanceToTarget())
                    : java.util.OptionalDouble.empty();
        }
        return java.util.OptionalDouble.of(subject.getLocation().distance(aiTarget.getLocation()));
    }

    private static java.util.Optional<Boolean> targetAiLineOfSight(BehaviorContext ctx) {
        LivingEntity subject = targetOnlyEntity(ctx);
        if (subject == null && ctx != null && ctx.instanceValid()) {
            Entity caster = ctx.instance().baseEntity().orElse(null);
            subject = caster instanceof LivingEntity living ? living : null;
        }
        if (!(subject instanceof org.bukkit.entity.Mob mob)) return java.util.Optional.empty();
        LivingEntity aiTarget = mob.getTarget();
        if (aiTarget == null || !aiTarget.isValid() || aiTarget.getWorld() != subject.getWorld()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(subject.hasLineOfSight(aiTarget));
    }

    /** 半径内的其它生物；明确排除动作施法者，避免近战动作自伤。 */
    private static List<LivingEntity> nearby(Location center, double radius) {
        return nearby(center, radius, null);
    }

    private static List<LivingEntity> nearby(Location center, double radius, Entity excluded) {
        List<LivingEntity> out = new ArrayList<>();
        if (radius <= 0 || center.getWorld() == null) return out;
        try {
            for (var e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (e != excluded && e instanceof LivingEntity le && le.isValid()) out.add(le);
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#900");
        }
        return out;
    }

    private static void scaleAttribute(LivingEntity e,
                                       org.bukkit.attribute.Attribute attr, double factor) {
        try {
            var inst = e.getAttribute(attr);
            if (inst != null) {
                inst.setBaseValue(inst.getBaseValue() * Math.max(0.1, factor));
            }
        } catch (Throwable ignored) {
            SkillFaults.swallow("SkillExtras#912");
        }
    }

    private static RegionBox regionArgs(List<String> args) {
        if (args == null || args.size() < 6) return null;
        int offset = args.size() >= 7 ? 1 : 0;
        try {
            String world = offset == 1 ? args.get(0) : null;
            double x1 = Double.parseDouble(args.get(offset));
            double y1 = Double.parseDouble(args.get(offset + 1));
            double z1 = Double.parseDouble(args.get(offset + 2));
            double x2 = Double.parseDouble(args.get(offset + 3));
            double y2 = Double.parseDouble(args.get(offset + 4));
            double z2 = Double.parseDouble(args.get(offset + 5));
            return RegionBox.of(world, x1, y1, z1, x2, y2, z2);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String regionArgument(List<String> args) {
        if (args == null || args.isEmpty()) return "";
        String value = null;
        for (String raw : args) {
            if (raw == null || raw.isBlank()) return "";
            String candidate = raw.trim();
            int equals = candidate.indexOf('=');
            if (equals >= 0) {
                if (equals == 0 || equals != candidate.lastIndexOf('=')) return "";
                String key = candidate.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                if (!key.equals("region") && !key.equals("r")) return "";
                candidate = candidate.substring(equals + 1).trim();
            }
            if (candidate.isBlank() || candidate.chars().anyMatch(Character::isWhitespace)
                    || candidate.indexOf(';') >= 0 || candidate.indexOf(',') >= 0) return "";
            if (value != null) return "";
            value = candidate;
        }
        return value == null ? "" : value;
    }

    private static Boolean externalRegion(String region, Location location) {
        if (location == null || location.getWorld() == null) return null;
        var lookup = regionLookup;
        if (lookup == null) return null;
        try {
            return lookup.apply(region, location);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static PotionEffectType potionType(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }
}
