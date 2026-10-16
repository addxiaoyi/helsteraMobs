package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.VariableLocations;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    @FunctionalInterface
    public interface DamagePolicy {
        double apply(BehaviorContext context, LivingEntity target, double amount, String cause);
    }

    private static volatile DamagePolicy damagePolicy;
    private static volatile java.util.function.BiFunction<BehaviorContext, LivingEntity, String> damageCause;
    private static volatile java.util.function.Function<String, ItemStack> itemResolver;
    private static volatile java.util.function.Consumer<String> audienceDiagnostic;
    private static volatile java.util.function.BiFunction<BehaviorContext, List<String>, Boolean> branchConditionEvaluator;

    /** Optional integration-only recipient diagnostics; null in normal runtime. */
    public static void audienceDiagnostic(java.util.function.Consumer<String> diagnostic) {
        audienceDiagnostic = diagnostic;
    }

    public static void branchConditionEvaluator(
            java.util.function.BiFunction<BehaviorContext, List<String>, Boolean> evaluator) {
        branchConditionEvaluator = evaluator;
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

    public static void killStreak(KillStreak s) {
        streaks = s;
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

    /** 全局冷却：名称 -> 解除冷却的时刻。 */
    private static final Map<String, Long> GLOBAL_CD = new ConcurrentHashMap<>();

    /** 实例销毁时回收其变量与分数。 */
    public static void forget(int instanceId) {
        VARS.remove(instanceId);
        SCORES.remove(instanceId);
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

    private static LivingEntity conditionEntity(BehaviorContext ctx) {
        if (ctx == null) return null;
        LivingEntity target = ctx.targetEntity().orElse(null);
        if (target != null) return target;
        if (!ctx.instanceValid()) return null;
        Entity entity = ctx.instance().baseEntity().orElse(null);
        return entity instanceof LivingEntity living ? living : null;
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
                    Comparison comparison = comparison(a, 0, "<=");
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        return entity != null && compare(entity.getHealth(), comparison);
                    };
                }),
                Map.entry("health-percent", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<=");
                    return ctx -> {
                        LivingEntity entity = conditionEntity(ctx);
                        if (entity == null || entity.getMaxHealth() <= 0) return false;
                        double percent = entity.getHealth() / entity.getMaxHealth() * 100.0;
                        return compare(percent, comparison);
                    };
                }),
                Map.entry("distance", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<=");
                    return ctx -> ctx != null && ctx.distanceToTarget() >= 0
                            && compare(ctx.distanceToTarget(), comparison);
                }),

                Map.entry("on-ground", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    Location l = selfLoc(ctx);
                    if (l == null) return false;
                    try {
                        return l.getBlock().getType().isSolid();
                    } catch (Throwable t) {
                        return false;
                    }
                }),
                Map.entry("in-water", (SkillCatalog.ConditionFactory) a -> ctx -> {
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
                Map.entry("world-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "");
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        return l != null && l.getWorld() != null
                                && want.equalsIgnoreCase(l.getWorld().getName());
                    };
                }),
                Map.entry("biome-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "");
                    return ctx -> {
                        Location l = selfLoc(ctx);
                        if (l == null) return false;
                        try {
                            return l.getBlock().getBiome().getKey().getKey()
                                    .equalsIgnoreCase(want);
                        } catch (Throwable t) {
                            return false;
                        }
                    };
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
                    String wanted = str(a, 0, "");
                    return ctx -> {
                        Entity entity = ctx == null ? null : ctx.targetEntity().orElse(null);
                        if (entity == null && ctx != null && ctx.instanceValid()) {
                            entity = ctx.instance().baseEntity().orElse(null);
                        }
                        return entity != null && !wanted.isBlank()
                                && entity.getType().name().equalsIgnoreCase(wanted);
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
                Map.entry("is-raider", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Raider),
                Map.entry("is-undead", (SkillCatalog.ConditionFactory) a ->
                        ctx -> {
                            LivingEntity entity = conditionEntity(ctx);
                            if (entity == null || entity.getType() == null) return false;
                            return Set.of("ZOMBIE", "HUSK", "DROWNED", "ZOMBIFIED_PIGLIN",
                                    "SKELETON", "STRAY", "WITHER_SKELETON", "WITHER",
                                    "PHANTOM", "ZOGLIN", "PIGLIN", "PIGLIN_BRUTE",
                                    "ZOMBIE_VILLAGER", "GIANT").contains(entity.getType().name());
                        }),
                Map.entry("is-creature", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Creature),
                Map.entry("is-baby", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Ageable age
                                && !age.isAdult()),
                Map.entry("is-adult", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Ageable age
                                && age.isAdult()),
                Map.entry("is-tamed", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Tameable tameable
                                && tameable.isTamed()),
                Map.entry("is-sheared", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Sheep sheep
                                && sheep.isSheared()),
                Map.entry("is-saddled", (SkillCatalog.ConditionFactory) a ->
                        ctx -> {
                            if (!(conditionEntity(ctx) instanceof org.bukkit.entity.AbstractHorse horse)
                                    || horse.getInventory() == null) return false;
                            ItemStack saddle = horse.getInventory().getSaddle();
                            return saddle != null && !saddle.getType().isAir();
                        }),
                Map.entry("is-powered", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof org.bukkit.entity.Creeper creeper
                                && creeper.isPowered()),
                Map.entry("has-owner", (SkillCatalog.ConditionFactory) a ->
                        ctx -> {
                            LivingEntity entity = conditionEntity(ctx);
                            return entity != null && (dev.helstera.api.behavior.MobRelations.hasOwner(entity)
                                    || entity instanceof org.bukkit.entity.Tameable tameable
                                    && tameable.getOwner() != null);
                        }),
                Map.entry("has-tag", (SkillCatalog.ConditionFactory) a -> {
                    String tag = str(a, 0, "");
                    if (tag.startsWith("tag=")) tag = tag.substring(4);
                    else if (tag.startsWith("t=")) tag = tag.substring(2);
                    String expected = tag.trim();
                    return ctx -> !expected.isBlank() && conditionEntity(ctx) != null
                            && conditionEntity(ctx).getScoreboardTags().contains(expected);
                }),
                Map.entry("has-parent", (SkillCatalog.ConditionFactory) a ->
                        ctx -> dev.helstera.api.behavior.MobRelations.hasParent(conditionEntity(ctx))),
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
                        ctx -> dev.helstera.api.behavior.MobRelations.hasParent(conditionEntity(ctx))),
                Map.entry("is-sibling", (SkillCatalog.ConditionFactory) a -> ctx -> {
                    if (ctx == null || !ctx.instanceValid()) return false;
                    Entity caster = ctx.instance().baseEntity().orElse(null);
                    Entity target = ctx.targetEntity().orElse(null);
                    return dev.helstera.api.behavior.MobRelations.areSiblings(caster, target);
                }),
                Map.entry("target-is-sneaking", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof Player player && player.isSneaking()),
                Map.entry("mounted", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living && living.isInsideVehicle()),
                Map.entry("moving", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living
                                && Double.isFinite(living.getVelocity().lengthSquared())
                                && living.getVelocity().lengthSquared() > 0.0),
                Map.entry("has-passenger", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living
                                && !living.getPassengers().isEmpty()),
                Map.entry("is-leashed", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living && living.isLeashed()),
                Map.entry("target-is-sprinting", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof Player player && player.isSprinting()),
                Map.entry("target-is-flying", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof Player player && player.isFlying()),
                Map.entry("target-is-blocking", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof Player player && player.isBlocking()),
                Map.entry("target-is-gliding", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living && living.isGliding()),
                Map.entry("target-is-swimming", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living && living.isSwimming()),
                Map.entry("target-on-fire", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) != null && conditionEntity(ctx).getFireTicks() > 0),
                Map.entry("target-in-air", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living && !living.isOnGround()),
                Map.entry("target-in-water", (SkillCatalog.ConditionFactory) a -> {
                    return ctx -> {
                        LivingEntity target = conditionEntity(ctx);
                        if (target == null) return false;
                        try {
                            org.bukkit.Material type = target.getLocation().getBlock().getType();
                            return type == org.bukkit.Material.WATER || type == org.bukkit.Material.BUBBLE_COLUMN;
                        } catch (RuntimeException ignored) {
                            return false;
                        }
                    };
                }),
                Map.entry("target-in-lava", (SkillCatalog.ConditionFactory) a -> {
                    return ctx -> {
                        LivingEntity target = conditionEntity(ctx);
                        if (target == null) return false;
                        try {
                            return target.getLocation() != null
                                    && target.getLocation().getWorld() != null
                                    && target.getLocation().getBlock().getType() == org.bukkit.Material.LAVA;
                        } catch (RuntimeException ignored) {
                            return false;
                        }
                    };
                }),
                Map.entry("target-is-invisible", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) != null && conditionEntity(ctx).isInvisible()),
                Map.entry("target-is-climbing", (SkillCatalog.ConditionFactory) a ->
                        ctx -> conditionEntity(ctx) instanceof LivingEntity living && living.isClimbing()),
                Map.entry("target-is-blocked", (SkillCatalog.ConditionFactory) a -> {
                    double above = num(a, 0, 1.0);
                    return ctx -> {
                        LivingEntity target = conditionEntity(ctx);
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
                Map.entry("stance-is", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> ctx != null && !wanted.isBlank()
                            && ctx.state() != null && wanted.equalsIgnoreCase(ctx.state().trim());
                }),
                Map.entry("in-combat", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx.targetEntity().isPresent()),
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

                // ---- 权限 / 持握 / 状态 ----

                Map.entry("has-permission", (SkillCatalog.ConditionFactory) a -> {
                    String perm = str(a, 0, "");
                    return ctx -> ctx.target().map(p -> p.hasPermission(perm)).orElse(false);
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
                Map.entry("has-item", (SkillCatalog.ConditionFactory) a -> {
                    String itemName = str(a, 0, "");
                    Comparison amount = comparison(a, 1, ">=");
                    String slot = str(a, 2, "all");
                    ItemStack expected = resolveItem(itemName, 1);
                    return ctx -> {
                        LivingEntity target = ctx == null ? null : ctx.targetEntity().orElse(null);
                        if (target == null && ctx != null) target = ctx.target().orElse(null);
                        if (target == null) target = conditionEntity(ctx);
                        if (target == null || expected == null) return false;
                        return compare(countMatchingItems(target, expected, slot), amount);
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
                Map.entry("food-level", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, ">=");
                    return ctx -> ctx.target().map(player ->
                            compare(player.getFoodLevel(), comparison)).orElse(false);
                }),
                Map.entry("air-level", (SkillCatalog.ConditionFactory) a -> {
                    Comparison comparison = comparison(a, 0, "<=");
                    return ctx -> {
                        LivingEntity target = conditionEntity(ctx);
                        return target != null && compare(target.getRemainingAir(), comparison);
                    };
                }),
                Map.entry("damage-cause", (SkillCatalog.ConditionFactory) a -> {
                    String wanted = str(a, 0, "");
                    return ctx -> ctx != null && !wanted.isBlank()
                            && ctx.damageCause().map(cause -> cause.name().equalsIgnoreCase(wanted)).orElse(false);
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
                Map.entry("game-mode-is", (SkillCatalog.ConditionFactory) a -> {
                    String want = str(a, 0, "survival");
                    return ctx -> ctx.target()
                            .map(p -> p.getGameMode().name().equalsIgnoreCase(want)).orElse(false);
                }),

                // ---- 范围统计 ----

                Map.entry("players-in-radius", (SkillCatalog.ConditionFactory) a -> {
                    int min = (int) num(a, 0, 1);
                    double radius = num(a, 1, 16);
                    return ctx -> countNear(ctx, radius, false) >= min;
                }),
                Map.entry("entities-in-radius", (SkillCatalog.ConditionFactory) a -> {
                    int min = (int) num(a, 0, 1);
                    double radius = num(a, 1, 16);
                    return ctx -> countNear(ctx, radius, true) >= min;
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

                // ---- 变量 / 分数 / 冷却 ----

                Map.entry("trigger-is-living", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx != null && ctx.trigger().filter(org.bukkit.entity.LivingEntity.class::isInstance).isPresent()),
                Map.entry("trigger-is-player", (SkillCatalog.ConditionFactory) a ->
                        ctx -> ctx != null && ctx.trigger().filter(org.bukkit.entity.Player.class::isInstance).isPresent()),
                Map.entry("target-count-at-least", (SkillCatalog.ConditionFactory) a -> {
                    int minimum = (int) num(a, 0, 1);
                    return ctx -> ctx != null && ctx.targets().size() >= Math.max(0, minimum);
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
                    return ctx -> variableEquals(ctx, ref, wanted);
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
                    double minimum = variableNumber(a, 1, Double.NEGATIVE_INFINITY, "min", "minimum", "from");
                    double maximum = variableNumber(a, 2, Double.POSITIVE_INFINITY, "max", "maximum", "to");
                    return ctx -> {
                        double current = varOf(ctx, ref);
                        return current >= minimum && current <= maximum;
                    };
                }),
                Map.entry("variable-contains", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String expected = keyedArg(a, "value", "val", "contains");
                    if (expected == null) expected = str(a, 1, "");
                    String wanted = expected;
                    return ctx -> variableContains(ctx, ref, wanted);
                }),
                Map.entry("variable-not-contains", (SkillCatalog.ConditionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    String expected = keyedArg(a, "value", "val", "contains");
                    if (expected == null) expected = str(a, 1, "");
                    String wanted = expected;
                    return ctx -> hasVariable(ctx, ref) && !variableContains(ctx, ref, wanted);
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
                    return ctx -> globalCooldownReady(name, seconds);
                }),
                Map.entry("global-cooldown", (SkillCatalog.ConditionFactory) a -> {
                    String name = str(a, 0, "");
                    double seconds = num(a, 1, 0);
                    return ctx -> globalCooldownReady(name, seconds);
                }),
                Map.entry("random-chance", (SkillCatalog.ConditionFactory) a -> {
                    // 参数是百分数：20 表示 20%
                    double percent = num(a, 0, 100);
                    return ctx -> java.util.concurrent.ThreadLocalRandom.current()
                            .nextDouble() * 100 < percent;
                })
        );
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

    static boolean matchesWeather(String raw, boolean raining, boolean thundering) {
        String spec = raw == null ? "clear" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (spec) {
            case "clear", "sun", "sunny" -> !raining && !thundering;
            case "rain", "raining" -> raining && !thundering;
            case "storm", "thunder", "thunderstorm", "thundering" -> thundering;
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    public static Map<String, SkillCatalog.ActionFactory> actions() {
        return Map.ofEntries(
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
                Map.entry("equip", (SkillCatalog.ActionFactory) SkillExtras::equipmentAction),
                Map.entry("set-item", (SkillCatalog.ActionFactory) SkillExtras::equipmentAction),
                Map.entry("drop-item", (SkillCatalog.ActionFactory) SkillExtras::dropItemAction),
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
                Map.entry("cancel-event", (SkillCatalog.ActionFactory) a -> ctx ->
                        ctx.cancellableEvent().ifPresent(event -> event.setCancelled(true))),
                Map.entry("signal", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    String payload = a.size() > 1 ? str(a, 1, "") : null;
                    // 第三个参数写 global 则广播；默认只发给自己实例
                    boolean global = a.size() > 2 && "global".equalsIgnoreCase(str(a, 2, ""));
                    return ctx -> {
                        if (name.isEmpty() || !ctx.instanceValid()) return;
                        int id = ctx.instance().instanceId();
                        SkillSignals.global().emit(global ? null : id, name, payload);
                    };
                }),

                // ---- 数值修改 ----

                Map.entry("set-health", (SkillCatalog.ActionFactory) a -> {
                    double v = num(a, 0, 0);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setHealth(Math.max(0.1, Math.min(e.getMaxHealth(), v)));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#407");
                        }
                    });
                }),
                Map.entry("modify-health", (SkillCatalog.ActionFactory) a -> {
                    double delta = num(a, 0, 0);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setHealth(Math.max(0.1,
                                    Math.min(e.getMaxHealth(), e.getHealth() + delta)));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#417");
                        }
                    });
                }),
                Map.entry("heal-percent", (SkillCatalog.ActionFactory) a -> {
                    double pct = num(a, 0, 10);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            double heal = e.getMaxHealth() * pct / 100.0;
                            e.setHealth(Math.min(e.getMaxHealth(), e.getHealth() + heal));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#427");
                        }
                    });
                }),
                Map.entry("modify-speed", (SkillCatalog.ActionFactory) a -> {
                    double factor = num(a, 0, 1.0);
                    return ctx -> selfLiving(ctx).ifPresent(e ->
                            scaleAttribute(e, org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED, factor));
                }),
                Map.entry("set-tick-speed", (SkillCatalog.ActionFactory) a -> {
                    double factor = num(a, 0, 1.0);
                    return ctx -> selfLiving(ctx).ifPresent(e ->
                            scaleAttribute(e, org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED, factor));
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

                // ---- 位移 ----

                Map.entry("pull", (SkillCatalog.ActionFactory) a -> {
                    double radius = num(a, 0, 6);
                    double power = num(a, 1, 1.0);
                    return ctx -> {
                        Location me = selfLoc(ctx);
                        if (me == null) return;
                        for (LivingEntity e : nearby(me, radius)) {
                            try {
                                var v = me.toVector()
                                        .subtract(e.getLocation().toVector())
                                        .normalize().multiply(power);
                                e.setVelocity(v);
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#486");
                            }
                        }
                    };
                }),
                Map.entry("leap", (SkillCatalog.ActionFactory) a -> {
                    double power = num(a, 0, 1.2);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        try {
                            e.setVelocity(e.getVelocity().setY(power));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#496");
                        }
                    });
                }),
                Map.entry("teleport-self", (SkillCatalog.ActionFactory) a -> {
                    double dx = num(a, 0, 0);
                    double dy = num(a, 1, 0);
                    double dz = num(a, 2, 0);
                    return ctx -> {
                        var self = ctx != null && ctx.instanceValid()
                                ? ctx.instance().baseEntity().orElse(null) : null;
                        Location me = selfLoc(ctx);
                        if (self == null || me == null) return;
                        try {
                            self.teleport(me.clone().add(dx, dy, dz));
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

                // ---- 伤害变体 ----

                Map.entry("damage-percent", (SkillCatalog.ActionFactory) a -> {
                    double pct = num(a, 0, 10);
                    return ctx -> {
                        LivingEntity t = ctx.targetEntity().orElse(null);
                        if (t == null || pct <= 0) return;
                        applyDamage(ctx, t, t.getMaxHealth() * pct / 100.0, "MAGIC");
                    };
                }),
                Map.entry("damage-melee", (SkillCatalog.ActionFactory) a -> {
                    double dmg = num(a, 0, 3);
                    double radius = num(a, 1, 2.5);
                    return ctx -> {
                        Location me = selfLoc(ctx);
                        if (me == null || dmg <= 0) return;
                        var src = ctx.instanceValid() ? ctx.instance().baseEntity().orElse(null) : null;
                        for (LivingEntity e : nearby(me, radius)) {
                            try {
                                applyDamage(ctx, e, dmg, "ENTITY_ATTACK");
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#541");
                            }
                        }
                    };
                }),
                Map.entry("dot", (SkillCatalog.ActionFactory) a -> {
                    double perTick = num(a, 0, 1.0);
                    int ticks = (int) num(a, 1, 20);
                    return ctx -> {
                        Plugin h = host;
                        LivingEntity t = ctx.targetEntity().orElse(null);
                        var src = ctx.instanceValid() ? ctx.instance().baseEntity().orElse(null) : null;
                        if (h == null || t == null || perTick <= 0) return;
                        try {
                            org.bukkit.Bukkit.getScheduler().runTaskTimer(h, r -> {
                                try {
                                    if (!t.isValid() || t.isDead()) {
                                        r.cancel();
                                        return;
                                    }
                                    applyDamage(ctx, t, perTick, "MAGIC");
                                } catch (Throwable ignored) {
                                    r.cancel();
                                }
                            }, 0L, Math.max(1, (long) ticks));
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#566");
                        }
                    };
                }),
                Map.entry("suicide", (SkillCatalog.ActionFactory) a ->
                        ctx -> selfLiving(ctx).ifPresent(e -> {
                            try {
                                e.setHealth(0);
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#574");
                            }
                        })),

                // ---- 增益 / 减益 ----

                Map.entry("effect-self", (SkillCatalog.ActionFactory) a -> {
                    String potion = str(a, 0, "");
                    int ticks = (int) num(a, 1, 60);
                    int amp = (int) num(a, 2, 0);
                    return ctx -> selfLiving(ctx).ifPresent(e -> {
                        PotionEffectType type = potionType(potion);
                        if (type == null || ticks <= 0) return;
                        try {
                            e.addPotionEffect(new PotionEffect(type, ticks, amp));
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
                            e.removePotionEffect(type);
                        } catch (Throwable ignored) {
                            SkillFaults.swallow("SkillExtras#600");
                        }
                    });
                }),
                Map.entry("remove-all-effects", (SkillCatalog.ActionFactory) a ->
                        ctx -> selfLiving(ctx).ifPresent(e -> {
                            try {
                                for (var eff : new ArrayList<>(e.getActivePotionEffects())) {
                                    e.removePotionEffect(eff.getType());
                                }
                            } catch (Throwable ignored) {
                                SkillFaults.swallow("SkillExtras#610");
                            }
                        })),

                // ---- 变量 / 分数 ----

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
                Map.entry("branch-unlock", (SkillCatalog.ActionFactory) a -> {
                    String branch = str(a, 0, "");
                    String conditionText = a.size() <= 1 ? "" : String.join(" ", a.subList(1, a.size()));
                    if (branch.startsWith("branch=")) branch = branch.substring("branch=".length()).trim();
                    conditionText = conditionText.replaceFirst("^(?:condition|conditions)=", "").trim();
                    final String branchName = branch;
                    final String conditionValue = conditionText;
                    List<String> conditions = conditionValue.isBlank() ? List.of()
                            : conditionValue.contains("||") ? List.of(conditionValue)
                            : java.util.Arrays.stream(conditionValue.split("\\|"))
                            .map(String::trim).filter(value -> !value.isBlank()).toList();
                    return ctx -> {
                        if (ctx == null || branchName.isBlank()) return;
                        var evaluator = branchConditionEvaluator;
                        if (evaluator != null && !Boolean.TRUE.equals(evaluator.apply(ctx, conditions))) return;
                        ctx.skillTree().ifPresent(tree -> tree.unlockBranch(branchName));
                    };
                }),
                Map.entry("variable-location-set", (SkillCatalog.ActionFactory) a -> {
                    VariableRef ref = variableRef(a);
                    return ctx -> {
                        if (ctx == null || ref.name().isEmpty()) return;
                        Location location = ctx.targetEntity().map(org.bukkit.entity.Entity::getLocation)
                                .orElseGet(() -> ctx.origin().orElse(null));
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
                    String name = str(a, 0, "");
                    int v = (int) num(a, 1, 0);
                    return ctx -> {
                        if (!hasInstance(ctx) || name.isEmpty()) return;
                        scoresOf(id(ctx)).put(name, v);
                    };
                }),
                Map.entry("score-add", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    int v = (int) num(a, 1, 0);
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
                            t.addThreat(id2, t.threatOf(id2) + Math.max(1, t.size()) * 1000.0);
                        }),
                Map.entry("set-global-cooldown", (SkillCatalog.ActionFactory) a -> {
                    String name = str(a, 0, "");
                    double seconds = num(a, 1, 1);
                    return ctx -> {
                        if (name.isEmpty() || seconds <= 0) return;
                        GLOBAL_CD.put(name.toLowerCase(Locale.ROOT),
                                System.currentTimeMillis() + (long) (seconds * 1000));
                    };
                }),

                // ---- 信息 ----

                Map.entry("broadcast", (SkillCatalog.ActionFactory) a -> {
                    String raw = str(a, 0, "");
                    return ctx -> {
                        if (raw.isBlank()) return;
                        try {
                            // Paper 1.21 移除了 Bukkit#broadcast(String)，
                            // 必须走 Adventure 的 Component 重载。
                            org.bukkit.Bukkit.broadcast(net.kyori.adventure.text.Component
                                    .text(Placeholders.resolve(raw, ctx)));
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
                }),                Map.entry("particle-audience", (SkillCatalog.ActionFactory) a -> {
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
                                p.sendMessage(out);
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
                        selfLiving(ctx).filter(e -> e instanceof Player).ifPresent(p -> {
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
                Map.entry("random-message", (SkillCatalog.ActionFactory) a -> {
                    String pool = str(a, 0, "");
                    return ctx -> {
                        Player t = ctx.target().orElse(null);
                        if (t == null || pool.isEmpty()) return;
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
                            l.getWorld().playSound(l, sound, 1f, 1f);
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
                })
        );
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

    private static java.util.function.Consumer<BehaviorContext> dropItemAction(List<String> args) {
        String itemName = str(args, 0, "");
        int amount = Math.max(1, (int) num(args, 1, 1));
        return ctx -> {
            Location location = selfLoc(ctx);
            ItemStack item = resolveItem(itemName, amount);
            if (location == null || item == null) return;
            item.setAmount(amount);
            try {
                location.getWorld().dropItemNaturally(location, item);
            } catch (RuntimeException ignored) {
                SkillFaults.swallow("SkillExtras#1384");
            }
        };
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
                if (resolved != null && resolved.getType() != Material.AIR) return resolved;
            } catch (RuntimeException ignored) {
                SkillFaults.swallow("SkillExtras#1362");
            }
        }
        Material material = Material.matchMaterial(raw);
        return material == null || material.isAir() ? null : new ItemStack(material, amount);
    }

    private static int countMatchingItems(LivingEntity entity, ItemStack expected, String slotName) {
        if (entity instanceof Player player && "all".equalsIgnoreCase(slotName)) {
            return countMatching(player.getInventory().getContents(), expected);
        }
        if (entity.getEquipment() == null) return 0;
        String slot = slotName == null ? "all" : slotName.trim().toLowerCase(Locale.ROOT);
        if (!"all".equals(slot)) {
            EquipmentSlot equipmentSlot = equipmentSlot(slot);
            return countMatching(new ItemStack[]{entity.getEquipment().getItem(equipmentSlot)}, expected);
        }
        int total = countMatching(new ItemStack[]{
                entity.getEquipment().getItem(EquipmentSlot.HAND),
                entity.getEquipment().getItem(EquipmentSlot.OFF_HAND),
                entity.getEquipment().getItem(EquipmentSlot.HEAD),
                entity.getEquipment().getItem(EquipmentSlot.CHEST),
                entity.getEquipment().getItem(EquipmentSlot.LEGS),
                entity.getEquipment().getItem(EquipmentSlot.FEET)
        }, expected);
        return total;
    }

    private static int countMatching(ItemStack[] contents, ItemStack expected) {
        int total = 0;
        if (contents == null) return total;
        for (ItemStack stack : contents) {
            boolean sameItem = stack != null && stack.getType() != Material.AIR
                    && (stack.isSimilar(expected) || stack.getType() == expected.getType());
            if (sameItem) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static java.util.List<Player> audiencePlayers(BehaviorContext ctx, String audience, double radius) {
        AudienceSpec spec = AudienceSpec.parse(audience, radius);
        String kind = spec.kind();
        java.util.LinkedHashSet<Player> players = new java.util.LinkedHashSet<>();
        if (ctx == null) return new ArrayList<>();
        if (kind.equals("target")) ctx.target().ifPresent(players::add);
        else if (kind.equals("targets")) {
            for (LivingEntity living : ctx.targets()) {
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
        players.removeIf(player -> !audiencePermissionAllowed(player, spec.permission()));
        if (spec.faction() != null) {
            var table = dev.helstera.api.behavior.Factions.table();
            players.removeIf(player -> table == null || !audienceFactionAllowed(
                    spec.faction(), table.factionOf(player)));
        }
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
        return player != null && (permission == null || permission.isBlank() || player.hasPermission(permission));
    }

    static boolean audienceFactionAllowed(String expected, String actual) {
        // An explicit faction selector filters recipients by faction. Alliance
        // relation is a separate targeting concern and must not depend on the
        // caster having a faction binding.
        return expected == null || expected.isBlank()
                || (actual != null && expected.equalsIgnoreCase(actual));
    }

    static record AudienceSpec(String kind, double radius, String permission, String faction) {
        static AudienceSpec parse(String raw, double fallbackRadius) {
            String value = raw == null ? "target" : raw.trim();
            if (value.startsWith("@")) value = value.substring(1);
            String permission = null;
            String faction = null;
            int open = value.indexOf('{');
            if (open >= 0 && value.endsWith("}")) {
                String body = value.substring(open + 1, value.length() - 1);
                for (String part : body.split("[;,]")) {
                    int eq = part.indexOf('=');
                    if (eq <= 0) continue;
                    String key = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    String val = part.substring(eq + 1).trim();
                    if (key.equals("permission") || key.equals("perm")) permission = val;
                    else if (key.equals("faction")) faction = val;
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
                        permission = suffix;
                        value = "all";
                    } else if (prefix.equals("faction")) {
                        faction = suffix;
                        value = "world";
                    }
                }
            }
            String normalized = value.toLowerCase(Locale.ROOT);
            if (normalized.equals("pir") || normalized.equals("playersinradius") || normalized.equals("players-in-radius")) normalized = "nearby";
            if (normalized.equals("p") || normalized.equals("players")) normalized = "world";
            return new AudienceSpec(normalized, Math.max(0, fallbackRadius), permission, faction);
        }
    }

    private static Location selfLoc(BehaviorContext ctx) {
        if (ctx == null) return null;
        Location l = ctx.origin().orElse(null);
        if (l == null && ctx.instanceValid()) l = ctx.instance().location();
        return l == null || l.getWorld() == null ? null : l;
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

    private static boolean variableContains(BehaviorContext ctx, VariableRef ref, String expected) {
        Object actual = rawVariable(ctx, ref);
        if (actual instanceof CharSequence text) return text.toString().contains(expected);
        if (actual instanceof java.util.Collection<?> values) {
            return values.stream().anyMatch(value -> variableEqualsValue(value, expected));
        }
        if (actual instanceof java.util.Map<?, ?> values) {
            return values.keySet().stream().anyMatch(value -> variableEqualsValue(value, expected))
                    || values.values().stream().anyMatch(value -> variableEqualsValue(value, expected));
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

    private static int scoreOf(BehaviorContext ctx, String name) {
        if (!hasInstance(ctx) || name.isEmpty()) return Integer.MIN_VALUE;
        return scoresOf(id(ctx)).getOrDefault(name, Integer.MIN_VALUE);
    }

    private static boolean hasScore(BehaviorContext ctx, String name) {
        return hasInstance(ctx) && !name.isEmpty() && scoresOf(id(ctx)).containsKey(name);
    }

    private static boolean globalCooldownReady(String name, double seconds) {
        if (name == null || name.isEmpty() || seconds <= 0) return true;
        Long until = GLOBAL_CD.get(name.toLowerCase(Locale.ROOT));
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

    /** 半径内的其它生物；自身排除在外。 */
    private static List<LivingEntity> nearby(Location center, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        if (radius <= 0 || center.getWorld() == null) return out;
        try {
            for (var e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
                if (e instanceof LivingEntity le && le.isValid()) out.add(le);
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

    private static PotionEffectType potionType(String name) {
        if (name == null || name.isEmpty()) return null;
        try {
            return PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
        } catch (Throwable t) {
            return null;
        }
    }
}
