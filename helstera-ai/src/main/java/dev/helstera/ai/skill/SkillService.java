package dev.helstera.ai.skill;

import dev.helstera.ai.AiProfile;
import dev.helstera.api.behavior.BehaviorRegistry;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 技能装载器：把 skills.yml 与 profile 中的文本定义解析成可执行的条件/动作。
 *
 * <p>核心是「按需绑定」——{@code health-below 0.3} 这行文本在加载期被切成
 * 条件名与参数，构造闭包后注册为 {@code health-below:0.3} 这样的确定性键。
 * 运行期决策节拍只按键查表，不做字符串解析与 IO。</p>
 *
 * <p><b>嵌套技能</b>：{@code cast-skill X} 与 MythicMobs 风格的 {@code skill{s=X}}
 * 都指向另一个命名技能。装载期建依赖图并做环检测（{@code a → b → a}），
 * 环上的技能会被拦下并告警，而不是等到运行期栈溢出；运行期另有深度守卫兜底
 * ——配置是外部输入，不能假设它一定无环。</p>
 *
 * <p><b>冷却</b>：技能可写 {@code cooldown: 6s}，按「实例 + 技能」记录上次放行时间。</p>
 *
 * <p>线程约束：全部方法要求主线程，在插件启用阶段调用。</p>
 */
public final class SkillService {

    /** 运行期递归深度上限。超过即中止并告警。 */
    private static final int MAX_DEPTH = 32;

    private final BehaviorRegistry registry;
    private final Logger log;
    private final Map<String, SkillCatalog.ConditionFactory> conditionFactories;
    private final Map<String, SkillCatalog.ActionFactory> actionFactories;
    /** 已绑定过的键，避免重复注册与重复告警。 */
    private final Set<String> bound = new LinkedHashSet<>();
    private final List<String> warnings = new ArrayList<>();
    /** 命名技能 -> 解析结果。reloadSkills 会重建，故每次 loadSkills 先清空。 */
    private final Map<String, SkillDef> defs = new LinkedHashMap<>();
    private final ThreadLocal<Set<String>> evaluatingSkillConditions = ThreadLocal.withInitial(LinkedHashSet::new);
    /** 冷却记录："实例id|技能名" -> 上次放行时间（epoch 毫秒）。 */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    /** Runtime SetSkillCooldown overrides: "实例id|技能名" -> duration in milliseconds. */
    private final Map<String, Long> cooldownOverrides = new ConcurrentHashMap<>();
    private volatile java.util.function.Function<org.bukkit.entity.Entity,
            dev.helstera.api.instance.ModelInstance> instanceLookup = ignored -> null;
    /** 活跃读条："实例id|技能名" -> 施法记录。 */
    private final Map<String, SkillCast> activeCasts = new ConcurrentHashMap<>();
    /** Per-instance generation invalidates delayed/repeated actions after cancel, death, or reload. */
    private final Map<Integer, Long> executionGenerations = new ConcurrentHashMap<>();
    /** Number of delayed/repeated continuations still waiting for each instance. */
    private final Map<Integer, Integer> pendingScheduledActions = new ConcurrentHashMap<>();
    /** In-flight action chains, invalidated if their instance is detached. */
    private final Map<Integer, List<boolean[]>> inFlightActions = new HashMap<>();
    /** Per-instance SkillTree state retained across separate casts. */
    private final Map<Integer, dev.helstera.api.behavior.SkillTreeContext.Snapshot> persistedTrees =
            new ConcurrentHashMap<>();
    /** 运行期深度守卫。主线程单线程执行，用 ThreadLocal 免去改 BehaviorContext 的成本。 */
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);

    /**
     * 技能执行队列。key 为实例 id，value 为按优先级降序排列的技能请求链表。
     *
     * <p>当同一实例已有技能正在执行时，新触发的技能入队而非立即执行；
     * 队列按 {@link SkillDef#priority} 降序排列，高优先级先出队执行。</p>
     *
     * <p>队列消费在主线程进行，无需额外同步——Bukkit 事件回调也都在主线程。</p>
     */
    private final Map<Integer, SkillQueueEntry> skillQueue = new ConcurrentHashMap<>();
    /** 标记每个实例是否有队列正在被消费（防止递归消费）。 */
    private final Map<Integer, Boolean> queueProcessing = new ConcurrentHashMap<>();

    /**
     * 单个命名技能的装载结果。
     *
     * <p>刻意不持有冷却状态：冷却键含实例 id，而实例 id 只在运行期的
     * BehaviorContext 里才有。把它做成静态数据 + 由外部持有状态，
     * 才能让同一份定义被多个实例共用。</p>
     */
    public static final class SkillDef {
        public final String name;
        /** 全部满足才算一个整体条件。 */
        public final List<String> require = new ArrayList<>();
        public final List<String> targetRequire = new ArrayList<>();
        public final List<String> triggerRequire = new ArrayList<>();
        public boolean invalidCondition;
        /** 按序执行的已绑定动作键。 */
        public final List<String> actions = new ArrayList<>();
        /** 本技能直接调用的其它技能名。 */
        public final Set<String> deps = new LinkedHashSet<>();
        /** 冷却毫秒；0 表示无冷却。 */
        public long cooldownMillis;
        /** 施法持续时间（读条时长）；0 表示无读条。 */
        public long castMillis;
        /** 读条标签（标题后缀），默认 "施法中"。 */
        public String castLabel = "施法中";
        /** 是否被判定参与环。 */
        public boolean cyclic;
        /**
         * 执行优先级：数值越大越先执行。
         * 同一 tick 内多个技能触发时，高优先级先进队列；默认 0。
         */
        public int priority;
        /** Optional player scope inherited by every action in this skill. */
        public String executionAudience;
        public boolean invalidExecutionAudience;
        /** Optional SkillTree branch gate. Empty means the skill is ungated. */
        public String branch = "";
        public final Set<String> branchRequires = new LinkedHashSet<>();

        SkillDef(String name) {
            this.name = name;
        }
    }

    /**
     * 正在施法的技能实例。
     *
     * @param def       技能定义
     * @param startMs   开始施法的 epoch 毫秒
     * @param instanceId 所属实例 id
     */
    public record SkillCast(SkillDef def, long startMs, int instanceId,
                            dev.helstera.api.behavior.BehaviorContext context) {
        public double progress() {
            if (def.castMillis <= 0) return 1.0;
            return Math.min(1.0, (double)(System.currentTimeMillis() - startMs) / def.castMillis);
        }
        public boolean isComplete() {
            return def.castMillis <= 0 || System.currentTimeMillis() - startMs >= def.castMillis;
        }
    }

    /**
     * 技能队列条目：一个待执行的技能请求。
     */
    private static final class QueuedSkill {
        final SkillDef def;
        final dev.helstera.api.behavior.BehaviorContext ctx;
        final boolean restoreTree;

        QueuedSkill(SkillDef def, dev.helstera.api.behavior.BehaviorContext ctx, boolean restoreTree) {
            this.def = def;
            this.ctx = ctx;
            this.restoreTree = restoreTree;
        }
    }

    /**
     * 技能执行队列：每个实例一个，按优先级排序。
     */
    private static final class SkillQueueEntry {
        final java.util.List<QueuedSkill> items = new java.util.ArrayList<>();
    }

    public SkillService(BehaviorRegistry registry, Logger log) {
        this(registry, log, SkillCatalog.conditions(), SkillCatalog.actions());
    }

    public SkillService(BehaviorRegistry registry, Logger log,
                        Map<String, SkillCatalog.ConditionFactory> conditionFactories,
                        Map<String, SkillCatalog.ActionFactory> actionFactories) {
        this.registry = registry;
        this.log = log;
        // 合并两份目录：内置基础动作（SkillCatalog）+ gameplay 扩展动作（SkillExtras）。
        // 扩展条目同名时以后者为准——它代表更新的实现，静默保留旧实现会让作者
        // 改了 SkillExtras 却看不到效果。
        Map<String, SkillCatalog.ConditionFactory> conds = new LinkedHashMap<>(conditionFactories);
        conds.putAll(SkillExtras.conditions());
        Map<String, SkillCatalog.ActionFactory> acts = new LinkedHashMap<>(actionFactories);
        acts.putAll(SkillExtras.actions());
        acts.put("branch-unlock", args -> SkillExtras.branchUnlockAction(args, (context, specs) -> {
            for (String spec : specs) {
                String key = bindCondition(spec);
                if (key == null || !registry.testCondition(key, context)) return false;
            }
            return true;
        }));
        this.conditionFactories = Map.copyOf(conds);
        this.actionFactories = Map.copyOf(acts);
    }

    /** 加载期告警（未知名、参数缺失、环引用等），供 /helstera debug 与启动日志展示。 */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * 取出并清空全部告警：加载期 + 条件工厂运行期。
     *
     * <p>供 {@code /helstera check} 与启动日志消费——只有把两类告警合并展示，
     * 「参数取值非法导致技能永不触发」这类问题才不会重新变成静默故障。</p>
     */
    public List<String> drainAllWarnings() {
        var runtime = SkillCatalog.drainRuntimeWarnings();
        var out = new ArrayList<String>(warnings.size() + runtime.size());
        out.addAll(warnings);
        out.addAll(runtime);
        return List.copyOf(out);
    }

    /** 已装载的命名技能名，供体检与网页端列举。 */
    public List<String> skillNames() {
        return List.copyOf(defs.keySet());
    }

    /**
     * 一条 require 的求值结果。
     *
     * <p>命名带 {@code Cond} 前缀而非裸 {@code Trace}：本类已有多个嵌套类型，
     * 裸名会在同文件里与其他 {@code Trace} 撞车。</p>
     *
     * @param key    已绑定的条件键
     * @param passed 该条件在给定上下文下是否为真
     */
    public record CondTrace(String key, boolean passed) {
    }

    /**
     * 技能预览结果：把「这条技能会不会触发、为什么」摊开给配置作者看。
     *
     * @param skillName 技能名
     * @param trace     逐条 require 的求值轨迹（键 + 是否通过）
     * @param wouldRun  require 全通过时为 true；否则为 false
     * @param actions   若执行会依次跑的动作键
     * @param cooldown  该技能当前是否在冷却中
     * @param priority  优先级
     */
    public record Preview(String skillName, List<CondTrace> trace, boolean wouldRun,
                          List<String> actions, boolean cooldown, int priority) {
        /** 首条未通过的 require；全通过时返回 null。 */
        public String firstFailing() {
            for (CondTrace t : trace) {
                if (!t.passed()) return t.key();
            }
            return null;
        }
    }

    /**
     * 预览一条命名技能在给定上下文下是否会触发，<b>不执行任何动作</b>。
     *
     * <p>存在的理由：「配置写了但永不触发且无任何报错」是本项目反复出现的缺陷类别。
     * 真服上要验证一条技能只能等 Boss 挨打到那个血量——被动且慢，于是配置作者
     * 改错条件后继续等待，形成死循环。这里把判定链路完整走一遍并输出轨迹，
     * 把排查从「等」变成「一条命令」。</p>
     *
     * <p>刻意不执行动作：预览是诊断工具，在真 Boss 上放一遍 AoE/点燃会造成真实伤害。
     * 「会不会触发」能答，「触发后打多少伤害」不能，也不该在这里答。</p>
     *
     * @param ctx 模拟上下文；可用 {@code BehaviorContext.of} 构造标量快照
     * @return 预览结果；技能不存在返回 {@code null}
     */
    public Preview preview(String skillName, dev.helstera.api.behavior.BehaviorContext ctx) {
        if (skillName == null || skillName.isBlank()) return null;
        SkillDef def = defs.get(skillName.toLowerCase(Locale.ROOT));
        if (def == null) return null;

        List<CondTrace> trace = new ArrayList<>();
        boolean allPass = !def.invalidCondition;
        if (def.invalidCondition) trace.add(new CondTrace("invalid-condition", false));
        for (String condKey : def.require) {
            boolean ok = registry.testCondition(condKey, ctx);
            trace.add(new CondTrace(condKey, ok));
            if (!ok) allPass = false;
        }
        if (ctx == null) {
            trace.add(new CondTrace("context", false));
            allPass = false;
        } else {
            var filtered = filterTargetConditions(def.targetRequire, ctx, false);
            if (!def.targetRequire.isEmpty()) {
                boolean ok = filtered != null;
                trace.add(new CondTrace("target-conditions", ok));
                if (!ok) allPass = false;
            }
            if (!def.triggerRequire.isEmpty()) {
                boolean ok = filtered != null
                        && filtered.trigger().orElse(null) instanceof org.bukkit.entity.LivingEntity
                        && conditionsPass(def.triggerRequire, triggerConditionContext(filtered));
                trace.add(new CondTrace("trigger-conditions", ok));
                if (!ok) allPass = false;
            }
        }
        String previewCooldownKey = cooldownKey(def, ctx);
        boolean cooling = cooldownOverrides.getOrDefault(previewCooldownKey, def.cooldownMillis) > 0
                && !cooldownReady(def, previewCooldownKey, System.currentTimeMillis());
        return new Preview(def.name, List.copyOf(trace), allPass,
                List.copyOf(def.actions), cooling, def.priority);
    }

    /** 绑定一条条件定义，返回可写入 profile 的键；失败返回 null。 */
    public String bindCondition(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String conditionSpec = spec.trim();
        if (conditionSpec.startsWith("?")) {
            conditionSpec = conditionSpec.substring(1).trim();
            if (conditionSpec.isEmpty()) return null;
        }
        String composite = bindCompositeCondition(conditionSpec);
        if (composite != null) return composite;
        String normalized = conditionSpec.indexOf('{') >= 0
                ? SpecSyntax.normalize(conditionSpec) : conditionSpec;
        List<String> parts = split(normalized);
        String name = canonicalConditionName(parts.get(0).toLowerCase(Locale.ROOT));
        List<String> args = new ArrayList<>(parts.subList(1, parts.size()));
        if (name.startsWith("skill:")) {
            String referenced = name.substring("skill:".length());
            if (args.isEmpty() && !referenced.isBlank() && defs.containsKey(referenced)) return name;
            warn("未知技能条件 \"" + spec + "\"");
            return null;
        }
        if ("skill-on-cooldown".equals(name)) {
            String skillName = skillOnCooldownName(conditionSpec);
            if (skillName == null) {
                warn("技能冷却条件参数无效 \"" + spec + "\"");
                return null;
            }
            String normalizedSkill = skillName.toLowerCase(Locale.ROOT);
            String key = "skill-on-cooldown:" + normalizedSkill;
            if (bound.add("c:" + key)) {
                registry.registerCondition(key, ctx -> skillOnCooldown(normalizedSkill, ctx));
            }
            return key;
        }
        if (Set.of("world-is", "weather-is", "time-is").contains(name)) {
            if (args.size() != 1 || args.get(0).isBlank()) return null;
            int open = conditionSpec.indexOf('{');
            if (open >= 0) {
                int close = conditionSpec.lastIndexOf('}');
                if (close != conditionSpec.length() - 1 || close <= open
                        || conditionSpec.indexOf('{', open + 1) >= 0
                        || conditionSpec.indexOf('}', open + 1) != close) return null;
                String body = conditionSpec.substring(open + 1, close).trim();
                if (body.indexOf(';') >= 0 || body.indexOf(',') >= 0) return null;
                int equals = body.indexOf('=');
                if (equals >= 0) {
                    String key = body.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                    boolean validKey = key.equals("name") || key.equals("value")
                            || (name.equals("world-is") && Set.of("world", "w").contains(key))
                            || (name.equals("weather-is") && Set.of("weather", "type").contains(key))
                            || (name.equals("time-is") && Set.of("time", "t").contains(key));
                    if (!validKey || body.substring(equals + 1).isBlank()) return null;
                }
            }
        }
        if (!args.isEmpty() && Set.of("night", "raining", "thundering", "day", "daytime", "dawn",
                "dusk", "sunset", "morning", "noon", "midnight", "clear", "sun", "sunny", "storm",
                "thunderstorm", "is-living",
                "is-player", "is-mob", "is-monster", "is-animal", "is-villager", "is-tameable", "is-raider",
                "is-baby", "is-child", "is-adult", "is-tamed", "is-sheared", "is-saddled", "is-powered",
                "target-is-baby", "target-is-adult", "target-is-tamed", "target-is-sheared",
                "target-is-saddled", "target-is-powered", "is-sneaking", "is-sprinting", "is-flying",
                "is-climbing", "is-invisible", "is-glowing", "falling", "target-is-climbing",
                "target-is-invisible", "target-is-glowing", "target-is-frozen",
                "is-blocking", "is-gliding", "is-swimming", "in-water", "in-lava", "on-fire", "in-air",
                "target-is-sneaking", "target-is-sprinting", "target-is-flying", "target-is-blocking",
                "target-is-gliding", "target-is-swimming", "target-in-water", "target-in-lava",
                "target-on-fire", "target-in-air", "target-is-player", "target-is-living", "target-is-mob",
                "target-is-monster", "target-is-animal", "target-is-villager", "target-is-tameable",
                "target-is-raider", "has-ai", "has-gravity", "is-silent", "is-dead", "is-alive", "is-valid",
                "target-has-ai", "target-has-gravity", "target-is-silent", "target-dead", "target-is-alive",
                "target-is-valid", "is-in-audience", "has-free-inventory-slot")
                .contains(name)) return null;
        if ("last-signal".equals(name) && conditionSpec.indexOf('{') >= 0) {
            try {
                int open = conditionSpec.indexOf('{');
                int close = conditionSpec.lastIndexOf('}');
                if (close < open || conditionSpec.substring(open + 1, close).indexOf(';') >= 0
                        || conditionSpec.substring(open + 1, close).indexOf(',') >= 0) return null;
                MythicSkillSpec parsed = MythicSkillSpec.parse(conditionSpec);
                if (parsed.arguments().size() + parsed.positional().size() > 1
                        || parsed.arguments().keySet().stream().anyMatch(key -> !key.equals("signal") && !key.equals("s"))
                        || parsed.arguments().values().stream().anyMatch(String::isBlank)) return null;
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
        if (isVariableCondition(name) && conditionSpec.indexOf('{') >= 0) {
            try {
                MythicSkillSpec parsed = MythicSkillSpec.parse(conditionSpec);
                List<String> encoded = new ArrayList<>(parsed.positional());
                parsed.arguments().forEach((key, value) -> encoded.add(key + "=" + value));
                args = encoded;
            } catch (IllegalArgumentException e) {
                warn("invalid Mythic condition line \"" + spec + "\": " + e.getMessage());
                return null;
            }
        }
        SkillCatalog.ConditionFactory f = conditionFactories.get(name);
        if (f == null) {
            if (!registry.hasCondition(name)) {
                warn("未知条件 \"" + name + "\"（可用内置: " + conditionFactories.keySet() + "）");
            }
            // 可能是 Java 侧注册的条件名，直接按名引用
            return registry.hasCondition(name) ? name : null;
        }
        String key = name + (args.isEmpty() ? "" : ":" + String.join(" ", args));
        if (bound.add("c:" + key)) {
            try {
                registry.registerCondition(key, f.create(args));
            } catch (RuntimeException e) {
                bound.remove("c:" + key);
                warn("条件 \"" + key + "\" 参数非法: " + e.getMessage());
                return null;
            }
        }
        return key;
    }

    private static String skillOnCooldownName(String raw) {
        try {
            MythicSkillSpec parsed = MythicSkillSpec.parse(raw);
            String mechanic = parsed.mechanic().replace("_", "").replace("-", "");
            if (!"skill-on-cooldown".equals(canonicalConditionName(mechanic))
                    || parsed.targeter() != null || parsed.trigger() != null || parsed.chance() != null
                    || !parsed.inlineConditions().isEmpty() || !parsed.targetConditions().isEmpty()) {
                return null;
            }
            String value = null;
            if (!parsed.arguments().isEmpty()) {
                if (parsed.arguments().size() != 1 || !parsed.positional().isEmpty()) return null;
                var argument = parsed.arguments().entrySet().iterator().next();
                if (!Set.of("skill", "s").contains(argument.getKey())) return null;
                value = argument.getValue();
            } else if (parsed.positional().size() == 1) {
                value = parsed.positional().get(0);
                int equals = value.indexOf('=');
                if (equals >= 0) {
                    String key = value.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                    if (!Set.of("skill", "s").contains(key)) return null;
                    value = value.substring(equals + 1).trim();
                }
            } else {
                return null;
            }
            return value == null || value.isBlank() ? null : value.trim();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean skillOnCooldown(String skillName,
                                    dev.helstera.api.behavior.BehaviorContext context) {
        if (context == null) return false;
        dev.helstera.api.instance.ModelInstance instance = context.instanceValid()
                ? context.instance() : null;
        org.bukkit.entity.LivingEntity entity = context.targetEntity().orElse(null);
        if (entity == null && instance != null) {
            org.bukkit.entity.Entity base = instance.baseEntity().orElse(null);
            if (base instanceof org.bukkit.entity.LivingEntity living) entity = living;
        }
        if (entity == null || !entity.isValid() || entity.isDead()) return false;
        if (!ownsEntity(instance, entity)) {
            try {
                instance = instanceLookup.apply(entity);
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        if (!ownsEntity(instance, entity)) return false;
        SkillDef definition = defs.get(skillName);
        if (definition == null) return false;
        String key = cooldownKey(definition, instance.instanceId());
        long duration = cooldownOverrides.getOrDefault(key, definition.cooldownMillis);
        return duration > 0 && !cooldownReady(definition, key, System.currentTimeMillis());
    }

    private static boolean ownsEntity(dev.helstera.api.instance.ModelInstance instance,
                                      org.bukkit.entity.Entity entity) {
        if (instance == null || entity == null || !instance.isValid()) return false;
        return instance.baseEntity().map(base -> base.getUniqueId().equals(entity.getUniqueId()))
                .orElse(false);
    }

    private String bindCompositeCondition(String raw) {
        String spec = raw.trim();
        String operator = null;
        boolean negate = false;
        List<String> children = new ArrayList<>();
        if (isWrappedCondition(spec)) {
            return bindCondition(spec.substring(1, spec.length() - 1).trim());
        } else if (topLevelCompositeOperator(spec) != null) {
            String delimiter = topLevelCompositeOperator(spec);
            operator = delimiter.equals("&&") ? "all" : "any";
            children = splitCompositeChildren(spec, delimiter);
        } else if (spec.startsWith("!")) {
            negate = true;
            operator = "not";
            String child = spec.substring(1).trim();
            if (!child.isEmpty()) children.add(child);
        } else if (spec.startsWith("not ") || spec.startsWith("not:")) {
            negate = true;
            children.add(spec.substring(spec.indexOf(spec.startsWith("not ") ? ' ' : ':') + 1).trim());
            operator = "not";
        } else if (spec.startsWith("all{") || spec.startsWith("and{")) {
            operator = "all";
            children = compositeChildren(spec);
        } else if (spec.startsWith("any{") || spec.startsWith("or{")) {
            operator = "any";
            children = compositeChildren(spec);
        } else if (spec.startsWith("not{")) {
            operator = "not";
            negate = true;
            children = compositeChildren(spec);
        }
        if (operator == null || children.isEmpty() || (negate && children.size() != 1)) return null;
        List<String> keys = new ArrayList<>();
        for (String child : children) {
            String key = bindCondition(child.trim());
            if (key == null) return null;
            keys.add(key);
        }
        String key = "composite-" + operator + ":" + String.join("|", keys);
        if (bound.add("c:" + key)) {
            List<String> captured = List.copyOf(keys);
            String capturedOperator = operator;
            boolean capturedNegate = negate;
            registry.registerCondition(key, ctx -> {
                if (capturedNegate || capturedOperator.equals("not")) {
                    return !registry.testConditionForComposition(captured.get(0), ctx);
                }
                boolean all = capturedOperator.equals("all");
                for (String child : captured) {
                    boolean passed = registry.testConditionForComposition(child, ctx);
                    if (all && !passed) return false;
                    if (!all && passed) return true;
                }
                return all;
            });
        }
        return key;
    }

    private static List<String> compositeChildren(String spec) {
        int open = spec.indexOf('{');
        int close = spec.lastIndexOf('}');
        if (open < 0 || close != spec.length() - 1 || close <= open) return List.of();
        int depth = 0;
        for (int i = open; i <= close; i++) {
            char ch = spec.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}' && (--depth < 0 || (depth == 0 && i != close))) return List.of();
        }
        if (depth != 0) return List.of();
        String body = spec.substring(open + 1, close).trim();
        if (body.startsWith("conditions=")) body = body.substring("conditions=".length()).trim();
        String delimiter = topLevelCompositeOperator(body);
        if (delimiter != null) return splitCompositeChildren(body, delimiter);
        return splitCompositeChildren(body, ";");
    }

    private static String topLevelCompositeOperator(String text) {
        int depth = 0;
        int parentheses = 0;
        boolean hasAnd = false;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '{') {
                depth++;
                continue;
            }
            if (current == '}') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && current == '(') { parentheses++; continue; }
            if (depth == 0 && current == ')') { parentheses--; continue; }
            if (depth != 0 || parentheses != 0 || i + 1 >= text.length()) continue;
            String pair = text.substring(i, i + 2);
            if (pair.equals("||")) return "||";
            if (pair.equals("&&")) hasAnd = true;
        }
        return hasAnd ? "&&" : null;
    }

    private static boolean isWrappedCondition(String spec) {
        if (spec.length() < 2 || spec.charAt(0) != '(' || spec.charAt(spec.length() - 1) != ')') {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < spec.length(); i++) {
            char current = spec.charAt(i);
            if (current == '(') depth++;
            else if (current == ')' && --depth == 0 && i != spec.length() - 1) return false;
            if (depth < 0) return false;
        }
        return depth == 0;
    }

    private static List<String> splitCompositeChildren(String text, String delimiter) {
        List<String> children = new ArrayList<>();
        int depth = 0;
        int parentheses = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '{') {
                depth++;
                continue;
            }
            if (current == '}') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && current == '(') { parentheses++; continue; }
            if (depth == 0 && current == ')') { parentheses--; continue; }
            boolean matches = depth == 0 && parentheses == 0 && text.startsWith(delimiter, i);
            if (!matches && delimiter.equals(";") && depth == 0 && parentheses == 0 && current == '|') matches = true;
            if (!matches) continue;
            String child = text.substring(start, i).trim();
            if (child.isEmpty()) return List.of();
            children.add(child);
            start = i + delimiter.length();
            if (delimiter.equals(";") && current == '|') start = i + 1;
            i = start - 1;
        }
        String tail = text.substring(start).trim();
        if (tail.isEmpty()) return List.of();
        children.add(tail);
        return List.copyOf(children);
    }

    /** 绑定一条动作定义，返回可写入 profile 的键；失败返回 null。 */
    public String bindAction(String spec) {
        if (spec == null || spec.isBlank()) return null;

        // Keep the original action arguments, but remove Mythic execution metadata.
        String normalized = spec.indexOf('{') >= 0 ? SpecSyntax.normalize(spec) : spec.trim();
        List<String> parts = split(normalized);
        String name = parts.get(0).toLowerCase(Locale.ROOT);
        name = canonicalActionName(name);
        List<String> args = new ArrayList<>();
        for (String part : parts.subList(1, parts.size())) {
            if (part.startsWith("@") || part.startsWith("?") || part.startsWith("~on")
                    || part.matches("\\d+(?:\\.\\d+)?%")) continue;
            args.add(part);
        }
        MythicSkillSpec parsed = null;
        if (spec.indexOf('{') >= 0 || spec.contains("@") || spec.contains("?")
                || spec.contains("~") || spec.contains("%")) {
            try {
                parsed = MythicSkillSpec.parse(spec);
            } catch (IllegalArgumentException e) {
                warn("invalid Mythic skill line \"" + spec + "\": " + e.getMessage());
                return null;
            }
            if (parsed.trigger() != null) {
                warn("event trigger requires event routing in skill line \"" + spec + "\"");
                return null;
            }
            List<String> mechanicArgs = new ArrayList<>(parsed.positional());
            if (isVariableMechanic(name) || "mythic-aura".equals(name)) {
                parsed.arguments().forEach((key, value) -> {
                    if (!isExecutionAttribute(key)) mechanicArgs.add(key + "=" + value);
                });
            } else {
                parsed.arguments().forEach((key, value) -> {
                    if (!isExecutionAttribute(key)) mechanicArgs.add(value);
                });
            }
            if (!mechanicArgs.isEmpty()) args = mechanicArgs;
            if (name.equals("branch-unlock")) {
                List<String> branchArgs = new ArrayList<>();
                String branch = parsed.arguments().get("branch");
                if (branch == null && !parsed.positional().isEmpty()) branch = parsed.positional().get(0);
                if (branch != null) branchArgs.add(branch);
                String condition = parsed.arguments().get("condition");
                if (condition == null) condition = parsed.arguments().get("conditions");
                if (condition != null) branchArgs.add(condition);
                args = branchArgs;
            }
        }

        Double chance = parsed == null ? null : parsed.chance();
        String targeterSpec = parsed == null ? null : parsed.targeter();
        long delayTicks;
        int repeat;
        long repeatInterval;
        try {
            delayTicks = parsed == null ? 0 : parseExecutionTicks(parsed.arguments().get("delay"));
            repeat = parsed == null ? 0 : parseExecutionCount(parsed.arguments().get("repeat"));
            repeatInterval = parsed == null ? 0 : parseExecutionTicks(parsed.arguments().getOrDefault(
                    "repeatinterval", parsed.arguments().get("repeat-interval")));
            if (repeat == Integer.MAX_VALUE && repeatInterval > 0) {
                throw new IllegalArgumentException("repeat count overflows execution count");
            }
        } catch (IllegalArgumentException e) {
            warn("invalid execution options in \"" + spec + "\": " + e.getMessage());
            return null;
        }
        String ref = parseSkillRef(spec);
        String nestedAction = ref != null ? bindCast(ref)
                : "random-skill".equals(name) ? bindRandomSkill(args) : null;
        if ("random-skill".equals(name) && nestedAction == null) return null;
        String actionName = nestedAction != null ? nestedAction
                : targeterSpec == null ? name : targetableMechanicName(name);
        SkillCatalog.ActionFactory factory = actionFactories.get(actionName);
        boolean customRegisteredAction = nestedAction != null;
        if (nestedAction == null && factory == null && targeterSpec != null && registry.hasAction(name)) {
            actionName = name;
            customRegisteredAction = true;
        }
        if (factory == null && (parsed == null || targeterSpec == null)
                && registry.hasAction(actionName)
                && (parsed == null || parsed.chance() == null && parsed.inlineConditions().isEmpty()
                && parsed.targetConditions().isEmpty()
                && parsed.arguments().keySet().stream().noneMatch(SkillService::isExecutionAttribute))) {
            return actionName;
        }
        if (factory == null && !customRegisteredAction && parsed != null
                && targeterSpec != null) {
            warn("Unknown action \\\"" + actionName + "\\\". Available actions: " + actionFactories.keySet());
            return null;
        }

        List<String> conditionKeys = new ArrayList<>();
        List<String> targetConditionKeys = new ArrayList<>();
        if (parsed != null) {
            for (String condition : parsed.inlineConditions()) {
                String key = bindCondition(condition);
                if (key == null) return null;
                conditionKeys.add(key);
            }
            for (String condition : parsed.targetConditions()) {
                String key = bindCondition(condition);
                if (key == null) return null;
                targetConditionKeys.add(key);
            }
        }
        if (!targetConditionKeys.isEmpty() && targeterSpec != null) {
            String targeterName = targeterSpec.trim().replaceFirst("^@", "");
            int targeterArguments = targeterName.indexOf('{');
            if (targeterArguments >= 0) targeterName = targeterName.substring(0, targeterArguments);
            if (dev.helstera.api.behavior.Targeters.isLocationName(targeterName)
                    || dev.helstera.api.behavior.Targeters.isVariableLocationName(targeterName)
                    || PinTargeter.recognizes(targeterSpec)) {
                warn("per-target inline conditions require an entity targeter in \"" + spec + "\"");
                return null;
            }
        }

        List<String> factoryArgs = args;
        String key = actionName + (factoryArgs.isEmpty() ? "" : ":" + String.join(" ", factoryArgs));
        if (chance != null || !conditionKeys.isEmpty() || !targetConditionKeys.isEmpty() || targeterSpec != null
                || delayTicks > 0 || repeat > 0 || repeatInterval > 0) {
            key += "|meta:" + (chance == null ? "" : chance) + ":"
                    + String.join(",", conditionKeys) + ":@" + String.join(",", targetConditionKeys)
                    + ":" + (targeterSpec == null ? "" : targeterSpec)
                    + ":delay=" + delayTicks + ":repeat=" + repeat + ":interval=" + repeatInterval;
        }
        if (bound.add("a:" + key)) {
            try {
                final String registeredActionName = actionName;
                Consumer<dev.helstera.api.behavior.BehaviorContext> action;
                if ("start-cast".equals(actionName) && factory != null) {
                    String skill = factoryArgs.isEmpty() ? "" : factoryArgs.get(0);
                    action = ctx -> {
                        if (ctx != null && ctx.instanceValid() && !skill.isBlank()) startCast(skill, ctx);
                    };
                } else if ("cancel-cast".equals(actionName) && factory != null) {
                    action = ctx -> {
                        if (ctx != null && ctx.instanceValid()) cancelCast(ctx.instance().instanceId());
                    };
                } else {
                    action = factory != null ? factory.create(factoryArgs)
                            : ctx -> registry.runAction(registeredActionName, ctx);
                }
                Double requiredChance = chance;
                long requiredDelay = delayTicks;
                int requiredRepeat = repeat;
                long requiredRepeatInterval = repeatInterval;
                registry.registerAction(key, ctx -> {
                    Consumer<dev.helstera.api.behavior.BehaviorContext> guarded = attempt -> {
                        if (requiredChance != null
                                && ThreadLocalRandom.current().nextDouble(100.0) >= requiredChance) return;
                        for (String condition : conditionKeys) {
                            if (!registry.testCondition(condition, attempt)) return;
                        }
                        if (targeterSpec != null) {
                            invokeTargeted(action, targeterSpec, attempt, targetConditionKeys);
                        } else if (!targetConditionKeys.isEmpty()) {
                            invokeTargeted(action, "targets", attempt, targetConditionKeys);
                        } else {
                            action.accept(attempt);
                        }
                    };
                    invokeTimed(guarded, ctx, requiredDelay, requiredRepeat, requiredRepeatInterval);
                });
            } catch (RuntimeException e) {
                bound.remove("a:" + key);
                warn("���� \"" + key + "\" �����Ƿ�: " + e.getMessage());
                return null;
            }
        }
        return key;
    }

    /** Executes a previously bound action in the supplied skill context. */
    public boolean runAction(String key, dev.helstera.api.behavior.BehaviorContext context) {
        if (key == null || key.isBlank() || context == null) return false;
        return registry.runAction(key, context);
    }

    /**
     * 装载命名技能。
     *
     * <p>顺序：先解析全部技能与依赖关系并做环检测，再逐个绑定内部条件/动作，
     * 最后注册 {@code skill:名字} 整体键。反过来做会让
     * 「A 调 B、B 调 A」在绑定期就找不到对方的键。</p>
     */


    private void invokeTargeted(Consumer<dev.helstera.api.behavior.BehaviorContext> action,
                                String targeterSpec,
                                dev.helstera.api.behavior.BehaviorContext ctx,
                                List<String> targetConditionKeys) {
        if (ctx == null) return;
        String targeterName = targeterSpec.trim();
        if (targeterName.startsWith("@")) targeterName = targeterName.substring(1);
        int argumentStart = targeterName.indexOf('{');
        if (argumentStart >= 0) targeterName = targeterName.substring(0, argumentStart);
        if (dev.helstera.api.behavior.Targeters.isLocationName(targeterName.trim())
                || dev.helstera.api.behavior.Targeters.isVariableLocationName(targeterName.trim())) {
            dev.helstera.api.behavior.Targeters.resolveLocation(targeterSpec, ctx)
                    .filter(location -> location.getWorld() != null)
                    .ifPresent(location -> action.accept(ctx.withLocationTarget(location)));
            return;
        }
        if (PinTargeter.recognizes(targeterSpec)) {
            List<org.bukkit.Location> locations = PinTargeter.select(
                    targeterSpec, SkillExtras.pinRegistry());
            if (locations == null) return;
            for (org.bukkit.Location location : locations) {
                action.accept(ctx.withLocationTarget(location));
            }
            return;
        }
        List<org.bukkit.entity.LivingEntity> candidates = ctx.skillTree()
                .map(dev.helstera.api.behavior.SkillTreeContext::targets)
                .orElse(ctx.targets());
        if (candidates.isEmpty() && ctx.instanceValid()) {
            org.bukkit.Location origin = ctx.origin().orElse(ctx.instance().location());
            if (origin != null && origin.getWorld() != null) {
                candidates = new ArrayList<>();
                for (org.bukkit.entity.Entity entity : origin.getWorld()
                        .getNearbyEntities(origin, 32, 32, 32)) {
                    if (entity instanceof org.bukkit.entity.LivingEntity living
                            && living.isValid()
                            && ctx.instance().baseEntity().orElse(null) != living
                            && !dev.helstera.api.behavior.Factions.allied(
                                    ctx.instance().baseEntity().orElse(null), living)) {
                        candidates.add(living);
                    }
                }
            }
        }
        List<org.bukkit.entity.LivingEntity> selected = dev.helstera.api.behavior.Targeters.select(
                targeterSpec, ctx, candidates, List.of());
        if (selected.isEmpty()) return;
        if (!targetConditionKeys.isEmpty()) {
            List<org.bukkit.entity.LivingEntity> passing = new ArrayList<>();
            for (var candidate : selected) {
                if (conditionsPass(targetConditionKeys,
                        targetConditionContext(ctx, candidate, List.of(candidate)))) {
                    passing.add(candidate);
                }
            }
            selected = passing;
            if (selected.isEmpty()) return;
        }
        var tree = ctx.skillTree().orElse(null);
        if (tree != null) tree.targets(selected);
        org.bukkit.entity.LivingEntity activeTarget = selected.get(0);
        double targetHealth = activeTarget.getMaxHealth() > 0
                ? activeTarget.getHealth() / activeTarget.getMaxHealth() : 0;
        double targetDistance = -1;
        org.bukkit.Location origin = ctx.origin().orElse(null);
        if (origin != null && origin.getWorld() != null
                && origin.getWorld().equals(activeTarget.getWorld())) {
            targetDistance = origin.distance(activeTarget.getLocation());
        }
        var scoped = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                ctx.instance(), activeTarget, targetHealth, targetDistance, ctx.decisionCount(),
                ctx.state(), ctx.payload().orElse(null), ctx.trigger().orElse(null), selected,
                ctx.origin().orElse(null), tree, ctx.cancellableEvent().orElse(null),
                ctx.damageCause().orElse(null)).withExecutionAudienceFrom(ctx);
        action.accept(scoped);
    }

    private static boolean isExecutionAttribute(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "delay", "repeat", "repeatinterval", "repeat-interval" -> true;
            default -> false;
        };
    }

    private static long parseExecutionTicks(String raw) {
        if (raw == null) return 0;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        try {
            double ticks;
            if (value.endsWith("ms")) ticks = Double.parseDouble(value.substring(0, value.length() - 2)) / 50.0;
            else if (value.endsWith("s")) ticks = Double.parseDouble(value.substring(0, value.length() - 1)) * 20.0;
            else ticks = Double.parseDouble(value);
            if (!Double.isFinite(ticks) || ticks < 0 || ticks >= Long.MAX_VALUE) {
                throw new NumberFormatException();
            }
            return (long) Math.ceil(ticks);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid execution duration: " + raw, e);
        }
    }

    private static int parseExecutionCount(String raw) {
        if (raw == null) return 0;
        try {
            int count = Integer.parseInt(raw.trim());
            if (count < 0) throw new NumberFormatException();
            return count;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid repeat count: " + raw, e);
        }
    }

    private void invokeTimed(Consumer<dev.helstera.api.behavior.BehaviorContext> action,
                             dev.helstera.api.behavior.BehaviorContext ctx,
                             long delayTicks, int repeat, long repeatInterval) {
        int executions = repeat <= 0 ? 1 : (repeatInterval > 0 ? repeat + 1 : repeat);
        int instanceId = ctx != null && ctx.instance() != null ? ctx.instance().instanceId() : -1;
        long generation = executionGenerations.getOrDefault(instanceId, 0L);
        scheduleInvocation(action, ctx, Math.max(0, delayTicks), executions,
                Math.max(0, repeatInterval), instanceId, generation);
    }

    private void scheduleInvocation(Consumer<dev.helstera.api.behavior.BehaviorContext> action,
                                     dev.helstera.api.behavior.BehaviorContext ctx,
                                     long delayTicks, int remaining, long intervalTicks,
                                     int instanceId, long generation) {
        boolean scheduled = delayTicks != 0 || remaining != 1;
        Runnable run = () -> {
            try {
                if (executionGenerations.getOrDefault(instanceId, 0L) != generation) return;
                action.accept(ctx);
                if (remaining > 1) {
                    scheduleInvocation(action, ctx, intervalTicks, remaining - 1, intervalTicks,
                            instanceId, generation);
                }
            } finally {
                if (scheduled) pendingScheduledActions.computeIfPresent(instanceId, (key, count) ->
                        count <= 1 ? null : count - 1);
                cleanupExecutionGeneration(instanceId);
            }
        };
        if (!scheduled) {
            run.run();
            return;
        }
        pendingScheduledActions.merge(instanceId, 1, Integer::sum);
        if (!SkillCatalog.schedule(run, delayTicks)) {
            pendingScheduledActions.computeIfPresent(instanceId, (key, count) ->
                    count <= 1 ? null : count - 1);
            cleanupExecutionGeneration(instanceId);
            warn("延迟技能动作无法调度：Bukkit 插件调度器未就绪");
        }
    }

    private void cleanupExecutionGeneration(int instanceId) {
        if (!pendingScheduledActions.containsKey(instanceId)) {
            executionGenerations.remove(instanceId);
        }
    }

    private static String targetableMechanicName(String mechanic) {
        return switch (mechanic) {
            case "damage" -> "damage-target";
            case "damage-percent" -> "damage-percent-targets";
            case "message" -> "message-target";
            // A targeter changes the recipient of heal; without this route
            // `heal @Target` silently healed the caster after selecting a target.
            case "heal", "heal-self" -> "heal-targets";
            case "heal-percent" -> "heal-percent-targets";
            case "set-health" -> "set-health-targets";
            case "modify-health" -> "modify-health-targets";
            case "set-max-health" -> "set-max-health-targets";
            case "modify-max-health" -> "modify-max-health-targets";
            case "modify-speed" -> "modify-speed-targets";
            case "set-tick-speed" -> "set-tick-speed-targets";
            case "jump" -> "jump-targets";
            case "force-pull" -> "force-pull-targets";
            case "set-offhand" -> "set-offhand-targets";
            case "set-armor" -> "set-armor-targets";
            case "set-faction" -> "set-faction-targets";
            case "lightning" -> "lightning-targets";
            case "leap" -> "leap-targets";
            case "dot" -> "dot-targets";
            case "ignite" -> "ignite-targets";
            case "knockback" -> "knockback-targets";
            case "effect" -> "effect-targets";
            case "teleport" -> "teleport-targets";
            case "teleport-mythic" -> "teleport-mythic";
            case "velocity" -> "velocity-targets";
            case "set-name" -> "set-name-targets";
            case "invisible" -> "invisible-targets";
            case "glowing" -> "glowing-targets";
            case "set-invulnerable" -> "set-invulnerable-targets";
            case "add-tag" -> "add-tag-targets";
            case "remove-tag" -> "remove-tag-targets";
            case "equip", "set-item" -> mechanic + "-targets";
            case "drop-item" -> "drop-item-targets";
            case "drop-items" -> "drop-items-targets";
            case "set-ai" -> "set-ai-targets";
            case "set-gravity" -> "set-gravity-targets";
            case "set-silent" -> "set-silent-targets";
            case "set-collidable" -> "set-collidable-targets";
            case "set-sneaking", "set-gliding", "set-swimming", "set-sprinting", "set-flying",
                    "set-no-damage-ticks", "set-maximum-no-damage-ticks", "set-walk-speed" -> mechanic + "-targets";
            case "set-fire" -> "set-fire-targets";
            case "clear-fire" -> "clear-fire-targets";
            case "remove-effect" -> "remove-effect-targets";
            case "remove-all-effects" -> "remove-all-effects-targets";
            case "set-target", "settarget", "target" -> "set-target";
            case "shoot", "projectile" -> "shoot-projectile";
            // 带 @Targeter 的单目标动作：键名是复数 --targets，不映射就会退化成
            // 「在别的实体身上改自己的滑翔/饥饿/槽位」这种静默错配。
            case "glide", "stop-glide", "set-hunger" -> mechanic + "-targets";
            default -> mechanic;
        };
    }

    public void loadSkills(ConfigurationSection root) {
        inFlightActions.values().forEach(chains -> chains.forEach(chain -> chain[0] = false));
        inFlightActions.clear();
        // Invalidate old delayed continuations before replacing definitions.
        // Clearing the generation map would make an old task with generation 0
        // look current again after reload, so keep its tombstone until every
        // pending continuation has observed the new generation.
        Set<Integer> oldScheduledInstances = new LinkedHashSet<>(executionGenerations.keySet());
        oldScheduledInstances.addAll(pendingScheduledActions.keySet());
        for (Integer instanceId : oldScheduledInstances) {
            executionGenerations.merge(instanceId, 1L, Long::sum);
        }
        for (Integer instanceId : oldScheduledInstances) {
            cleanupExecutionGeneration(instanceId);
        }
        defs.clear();
        cooldowns.clear();
        cooldownOverrides.clear();
        activeCasts.clear();
        skillQueue.clear();
        queueProcessing.clear();
        // warnings 必须清，否则每次 reload 都把上一轮的告警**叠加**上去：
        // 作者改对了 skills.yml 并 reload 后，/helstera check 仍显示旧的「参数非法」，
        // 像是修复没生效——会让人反复去检查那份其实已经正确的配置。
        // （真服验证：写入合法配置后 reload，告警仍在。）
        // bound 一并清掉，保持「每次 loadSkills 从零开始」的语义。
        // 它只用于「同一键不重复注册」这个去重；不清也不会让动作注册失败
        // ——registerAction 是覆盖式 put，闭包本来就会被新值替换。
        warnings.clear();
        bound.clear();
        if (root == null) return;

        // 1) 解析原始定义
        Map<String, ConfigurationSection> skillSections = new LinkedHashMap<>();
        for (String skillName : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(skillName);
            if (s == null) continue;
            String key = skillName.toLowerCase(Locale.ROOT);
            skillSections.put(key, s);
            SkillDef def = new SkillDef(key);
            def.cooldownMillis = parseCooldownMillis(s.get("cooldown"));
            def.castMillis = parseCooldownMillis(s.get("cast-duration"));
            if (s.contains("cast-label")) def.castLabel = s.getString("cast-label", "施法中");
            if (s.contains("priority")) def.priority = (int) s.get("priority");
            def.branch = s.getString("branch", "").trim();
            def.branchRequires.addAll(s.getStringList("branch-requires"));
            if (s.contains("execution-audience")) {
                Object configuredAudience = s.get("execution-audience");
                if (configuredAudience instanceof String audience && !audience.isBlank()
                        && ExecutionAudiences.isSupported(audience)) {
                    def.executionAudience = audience.trim();
                } else {
                    def.invalidExecutionAudience = true;
                    warn("技能 \"" + def.name + "\" 的 execution-audience 无效，已阻止执行");
                }
            }
            for (String spec : stringOrList(s, "require")) {
                if (spec == null || spec.isBlank()) continue;
                String dep = parseSkillRef(spec);
                if (dep != null) def.deps.add(dep);
            }
            for (String field : List.of("on-decision", "target-conditions", "trigger-conditions")) {
                for (String spec : stringOrList(s, field)) {
                    if (spec == null || spec.isBlank()) continue;
                    String dep = parseSkillRef(spec);
                    if (dep != null) def.deps.add(dep);
                def.deps.addAll(randomSkillRefs(spec));
                }
            }
            defs.put(key, def);
        }
        if (defs.isEmpty()) return;

        // 2) 环检测
        detectCycles();

        // 3) 绑定内部条件/动作
        for (SkillDef def : defs.values()) {
            ConfigurationSection s = skillSections.get(def.name);
            if (s == null) continue;
            for (String spec : conditionSpecs(s, "require")) {
                String k = bindCondition(spec);
                if (k != null) def.require.add(k); else def.invalidCondition = true;
            }
            for (String spec : conditionSpecs(s, "target-conditions")) {
                String k = bindCondition(spec);
                if (k != null) def.targetRequire.add(k); else def.invalidCondition = true;
            }
            for (String spec : conditionSpecs(s, "trigger-conditions")) {
                String k = bindCondition(spec);
                if (k != null) def.triggerRequire.add(k); else def.invalidCondition = true;
            }
            for (String spec : conditionSpecs(s, "TargetConditions")) {
                String k = bindCondition(spec);
                if (k != null) def.targetRequire.add(k); else def.invalidCondition = true;
            }
            for (String spec : conditionSpecs(s, "TriggerConditions")) {
                String k = bindCondition(spec);
                if (k != null) def.triggerRequire.add(k); else def.invalidCondition = true;
            }
            for (String spec : stringOrList(s, "on-decision")) {
                String k = bindAction(spec);
                if (k != null) def.actions.add(k);
            }
        }

        // 4) 嵌套技能的分发键（必须在第 3 步之后，否则被调方还没绑定完）
        for (SkillDef def : defs.values()) {
            for (String dep : def.deps) {
                if (!defs.containsKey(dep)) {
                    warn("技能 \"" + def.name + "\" 引用了不存在的技能 \"" + dep + "\"");
                }
            }
        }

        // 5) 整体键：skill:名字 作为条件（require 全满足）与动作（顺序执行）
        for (SkillDef def : defs.values()) {
            String key = "skill:" + def.name;
            if (bound.add("c:" + key)) {
                List<String> captured = List.copyOf(def.require);
                registry.registerCondition(key, ctx -> {
                    if (defs.get(def.name) != def) throw new IllegalStateException("Stale skill: " + def.name);
                    if (def.invalidCondition) throw new IllegalStateException("Invalid conditions in skill: " + def.name);
                    Set<String> evaluating = evaluatingSkillConditions.get();
                    if (!evaluating.add(def.name)) throw new IllegalStateException("Cyclic skill condition: " + def.name);
                    try {
                        for (String k : captured) {
                            boolean passed = registry.testConditionForComposition(k, ctx);
                            if (defs.get(def.name) != def) throw new IllegalStateException("Stale skill: " + def.name);
                            if (!passed) return false;
                        }
                        return true;
                    } finally {
                        evaluating.remove(def.name);
                        if (evaluating.isEmpty()) evaluatingSkillConditions.remove();
                    }
                });
            }
            if (bound.add("a:" + key)) {
                List<String> captured = List.copyOf(def.actions);
                registry.registerAction(key, ctx -> {
                    if (def.invalidCondition) return;
                    for (String k : captured) {
                        if (defs.get(def.name) != def) return;
                        registry.runAction(k, ctx);
                    }
                });
            }
        }
    }

    /**
     * 绑定一条嵌套技能调用为可执行动作键。
     *
     * <p>同一技能名只注册一次分发器；冷却与环判定在分发器内部按上下文实例计算，
     * 因此 A 与 B 两个不同实例调用同一技能互不干扰。</p>
     */
    private String bindCast(String skillName) {
        String name = skillName.toLowerCase(Locale.ROOT);
        String key = "cast-skill:" + name;
        if (bound.add("a:" + key)) {
            registry.registerAction(key, ctx -> castSkill(name, ctx));
        }
        if (!defs.containsKey(name)) {
            // 允许「先引用后定义」：运行期再取一次，但此处已提示配置很可能有误
            warn("技能引用 \"" + name + "\" 在本次装载中未找到定义");
        }
        return key;
    }

    /** Binds MythicMobs randomskill into the same guarded named-skill path. */
    private String bindRandomSkill(List<String> args) {
        List<String> requested = normalizeRandomSkillArgs(args);
        if (requested.isEmpty()) {
            warn("random-skill 缺少候选技能");
            return null;
        }
        List<String> choices = new ArrayList<>();
        for (String candidate : requested) {
            String name = candidate.toLowerCase(Locale.ROOT);
            if (defs.containsKey(name)) {
                choices.add(name);
            } else {
                warn("random-skill 引用了不存在的技能 \"" + candidate + "\"");
            }
        }
        if (choices.isEmpty()) return null;

        String key = "random-skill:" + String.join(",", choices);
        if (bound.add("a:" + key)) {
            List<String> captured = List.copyOf(choices);
            registry.registerAction(key, ctx -> {
                if (captured.isEmpty()) return;
                String selected = captured.get(ThreadLocalRandom.current().nextInt(captured.size()));
                castSkill(selected, ctx);
            });
        }
        return key;
    }

    private static List<String> randomSkillRefs(String spec) {
        if (spec == null || spec.isBlank()) return List.of();
        if (spec.indexOf('{') >= 0) {
            try {
                MythicSkillSpec parsed = MythicSkillSpec.parse(spec);
                if (!"random-skill".equals(canonicalActionName(parsed.mechanic()))) return List.of();
                List<String> candidates = new ArrayList<>(parsed.positional());
                parsed.arguments().forEach((key, value) -> {
                    if (key.equals("skill") || key.equals("skills")) candidates.add(value);
                });
                return normalizeRandomSkillArgs(candidates);
            } catch (IllegalArgumentException e) {
                return List.of();
            }
        }
        String normalized = spec.indexOf('{') >= 0 ? SpecSyntax.normalize(spec) : spec.trim();
        List<String> parts = split(normalized);
        if (parts.isEmpty() || !"random-skill".equals(canonicalActionName(
                parts.get(0).toLowerCase(Locale.ROOT)))) return List.of();
        return normalizeRandomSkillArgs(parts.subList(1, parts.size()));
    }

    private static List<String> normalizeRandomSkillArgs(List<String> args) {
        List<String> out = new ArrayList<>();
        if (args == null) return out;
        for (String raw : args) {
            if (raw == null || raw.isBlank()) continue;
            for (String token : raw.split("[,;|]")) {
                String value = token.trim();
                int equals = value.indexOf('=');
                if (equals >= 0) {
                    String key = value.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                    if (key.equals("skill") || key.equals("skills")) {
                        value = value.substring(equals + 1).trim();
                    }
                }
                if (!value.isBlank()) out.add(value);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 执行一个命名技能（带冷却与深度守卫）。
     *
     * <p>供 {@code cast-skill} 动作与触发器层的 MythicMobs 风格
     * {@code ~onSpawn:SkillName} 共同使用。</p>
     *
     * @return 是否真正执行
     */
    public boolean castSkill(String skillName, dev.helstera.api.behavior.BehaviorContext ctx) {
        if (skillName == null || skillName.isBlank()) return false;
        SkillDef def = defs.get(skillName.toLowerCase(Locale.ROOT));
        if (def == null) return false;
        if (def.invalidCondition || def.invalidExecutionAudience) return false;
        if (def.executionAudience != null) {
            var resolution = ExecutionAudiences.resolve(def.executionAudience, ctx);
            if (!resolution.supported()) return false;
            if (ctx == null) {
                ctx = dev.helstera.api.behavior.BehaviorContext.execution(null, null,
                        1.0, -1, 0, "skill", null, null, List.of(), null);
            }
            ctx = ctx.withNarrowedExecutionAudience(resolution.players());
        }
        if (!conditionsPass(def.require, ctx)) return false;
        ctx = filterTargetConditions(def.targetRequire, ctx);
        if (ctx == null || (!def.triggerRequire.isEmpty()
                && (!(ctx.trigger().orElse(null) instanceof org.bukkit.entity.LivingEntity)
                || !conditionsPass(def.triggerRequire, triggerConditionContext(ctx))))) return false;

        int[] d = depth.get();
        if (d[0] >= MAX_DEPTH) {
            warn("技能 \"" + def.name + "\" 递归超过 " + MAX_DEPTH + " 层，已中止");
            return false;
        }

        long now = System.currentTimeMillis();
        String cdKey = cooldownKey(def, ctx);
        if (!cooldownReady(def, cdKey, now)) return false;
        var tree = ctx == null ? null : ctx.skillTree().orElse(null);
        boolean restoreQueuedTree = tree == null;
        if (tree == null && ctx != null) {
            int treeInstanceId = ctx.instance() == null ? -1 : ctx.instance().instanceId();
            var saved = persistedTrees.get(treeInstanceId);
            tree = saved == null
                    ? new dev.helstera.api.behavior.SkillTreeContext(
                    ctx.trigger().orElse(null), ctx.targets())
                    : dev.helstera.api.behavior.SkillTreeContext.restore(saved,
                    ctx.trigger().orElse(null), ctx.targets(), org.bukkit.Bukkit::getWorld);
            ctx = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                    ctx.instance(), ctx.targetEntity().orElse(null), ctx.healthRatio(),
                    ctx.distanceToTarget(), ctx.decisionCount(), ctx.state(),
                    ctx.payload().orElse(null), ctx.trigger().orElse(null),
                    ctx.targets(), ctx.origin().orElse(null), tree,
                    ctx.cancellableEvent().orElse(null), ctx.damageCause().orElse(null))
                    .withExecutionAudienceFrom(ctx);
        }
        for (SkillDef declared : defs.values()) {
            if (!declared.branch.isBlank()) {
                tree.defineBranch(declared.branch, declared.branchRequires);
            }
        }
        if (!def.branch.isBlank()) {
            if (!tree.branchUnlocked(def.branch)) {
                return false;
            }
        }
        if (cooldownOverrides.getOrDefault(cdKey, def.cooldownMillis) > 0) cooldowns.put(cdKey, now);

        // 队列门控：同一实例有技能正在执行时入队
        int instId = ctx.instance() != null ? ctx.instance().instanceId() : -1;
        if (instId >= 0 && isInstanceBusy(instId)) {
            enqueueSkill(instId, def, ctx, restoreQueuedTree);
            return true;
        }

        d[0]++;
        if (def.castMillis > 0) {
            try {
                beginCast(def, ctx);
                return true;
            } finally {
                d[0]--;
            }
        }
        try {
            executeSkillActions(def, ctx);
            return true;
        } finally {
            d[0]--;
            // 执行完毕后尝试消费队列
            processQueue(instId);
        }
    }

    /** 检查该实例是否有技能正在执行或队列非空。 */
    private boolean conditionsPass(List<String> conditions,
                                  dev.helstera.api.behavior.BehaviorContext ctx) {
        for (String condition : conditions) {
            if (!registry.testCondition(condition, ctx)) return false;
        }
        return true;
    }

    private dev.helstera.api.behavior.BehaviorContext targetConditionContext(
            dev.helstera.api.behavior.BehaviorContext ctx,
            org.bukkit.entity.LivingEntity target,
            List<org.bukkit.entity.LivingEntity> targets) {
        double ratio = target.getMaxHealth() > 0 ? target.getHealth() / target.getMaxHealth() : 0;
        double distance = -1;
        var origin = ctx.origin().orElse(null);
        if (origin != null && origin.getWorld() != null && target.getWorld() != null
                && origin.getWorld().equals(target.getWorld())) {
            distance = origin.distance(target.getLocation());
        }
        return dev.helstera.api.behavior.BehaviorContext.continueExecution(
                ctx.instance(), target, ratio, distance, ctx.decisionCount(), ctx.state(),
                ctx.payload().orElse(null), ctx.trigger().orElse(null), targets,
                ctx.origin().orElse(null), ctx.skillTree().orElse(null),
                ctx.cancellableEvent().orElse(null), ctx.damageCause().orElse(null))
                .withExecutionAudienceFrom(ctx);
    }

    private dev.helstera.api.behavior.BehaviorContext filterTargetConditions(
            List<String> conditions, dev.helstera.api.behavior.BehaviorContext ctx) {
        return filterTargetConditions(conditions, ctx, true);
    }

    private dev.helstera.api.behavior.BehaviorContext filterTargetConditions(
            List<String> conditions, dev.helstera.api.behavior.BehaviorContext ctx, boolean updateTree) {
        if (ctx == null || conditions.isEmpty()) return ctx;
        List<org.bukkit.entity.LivingEntity> targets = ctx.targets();
        if (targets.isEmpty()) {
            var target = ctx.targetEntity().orElse(null);
            if (target == null) return conditionsPass(conditions, ctx) ? ctx : null;
            if (!conditionsPass(conditions, targetConditionContext(ctx, target, List.of(target)))) return null;
            if (updateTree) ctx.skillTree().ifPresent(tree -> tree.targets(List.of(target)));
            return targetConditionContext(ctx, target, List.of(target));
        }

        List<org.bukkit.entity.LivingEntity> passing = new ArrayList<>();
        for (var target : targets) {
            if (conditionsPass(conditions, targetConditionContext(ctx, target, List.of(target)))) {
                passing.add(target);
            }
        }
        if (passing.isEmpty()) return null;
        var active = passing.get(0);
        var filtered = targetConditionContext(ctx, active, passing);
        if (updateTree) ctx.skillTree().ifPresent(tree -> tree.targets(passing));
        return filtered;
    }

    private dev.helstera.api.behavior.BehaviorContext triggerConditionContext(
            dev.helstera.api.behavior.BehaviorContext ctx) {
        if (ctx == null) return null;
        var trigger = ctx.trigger().orElse(null);
        if (!(trigger instanceof org.bukkit.entity.LivingEntity living)) return ctx;
        double ratio = living.getMaxHealth() > 0 ? living.getHealth() / living.getMaxHealth() : 0;
        List<org.bukkit.entity.LivingEntity> inherited = ctx.targets();
        return dev.helstera.api.behavior.BehaviorContext.continueExecution(
                ctx.instance(), living, ratio, ctx.distanceToTarget(), ctx.decisionCount(), ctx.state(),
                ctx.payload().orElse(null), trigger, inherited, ctx.origin().orElse(null),
                ctx.skillTree().orElse(null), ctx.cancellableEvent().orElse(null),
                ctx.damageCause().orElse(null)).withExecutionAudienceFrom(ctx);
    }
    private boolean isInstanceBusy(int instId) {
        // 读条完成判定：activeCasts 里有无该实例的活跃读条
        boolean hasActiveCast = activeCasts.values().stream().anyMatch(c -> c.instanceId() == instId && !c.isComplete());
        if (hasActiveCast) return true;
        // 已有排队任务
        SkillQueueEntry q = skillQueue.get(instId);
        return q != null && !q.items.isEmpty();
    }

    /** 把技能请求按优先级插入排序到实例队列中。 */
    private void enqueueSkill(int instId, SkillDef def, dev.helstera.api.behavior.BehaviorContext ctx,
                              boolean restoreTree) {
        skillQueue.compute(instId, (k, q) -> {
            if (q == null) q = new SkillQueueEntry();
            q.items.add(new QueuedSkill(def, ctx, restoreTree));
            // 按优先级降序插入
            q.items.sort((a, b) -> Integer.compare(b.def.priority, a.def.priority));
            return q;
        });
    }

    /** 尝试消费该实例的队列；仅在当前无任务时启动。 */
    private void processQueue(int instId) {
        if (queueProcessing.getOrDefault(instId, false)) return;
        queueProcessing.put(instId, true);
        try {
            SkillQueueEntry q = skillQueue.get(instId);
            while (q != null && !q.items.isEmpty()) {
                QueuedSkill qs = q.items.remove(0);
                var context = qs.ctx;
                var saved = qs.restoreTree ? persistedTrees.get(instId) : null;
                if (saved != null) {
                    var tree = dev.helstera.api.behavior.SkillTreeContext.restore(saved,
                            context.trigger().orElse(null), context.targets(), org.bukkit.Bukkit::getWorld);
                    context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                            context.instance(), context.targetEntity().orElse(null), context.healthRatio(),
                            context.distanceToTarget(), context.decisionCount(), context.state(),
                            context.payload().orElse(null), context.trigger().orElse(null), context.targets(),
                            context.origin().orElse(null), tree, context.cancellableEvent().orElse(null),
                            context.damageCause().orElse(null)).withExecutionAudienceFrom(context);
                }
                int[] d = depth.get();
                d[0]++;
                try {
                    if (qs.def.castMillis > 0) beginCast(qs.def, context);
                    else executeSkillActions(qs.def, context);
                } finally {
                    d[0]--;
                }
                if (qs.def.castMillis > 0) break;
                q = skillQueue.get(instId);
            }
        } finally {
            queueProcessing.remove(instId);
            if (skillQueue.get(instId) != null && skillQueue.get(instId).items.isEmpty()) {
                skillQueue.remove(instId);
            }
        }
    }

    /** 执行技能动作集合并启动读条（复用 castSkill 的逻辑分支）。 */
    private void executeSkillActions(SkillDef def, dev.helstera.api.behavior.BehaviorContext ctx) {
        Integer instanceId = ctx == null || ctx.instance() == null ? null : ctx.instance().instanceId();
        boolean[] valid = {true};
        inFlightActions.computeIfAbsent(instanceId, ignored -> new ArrayList<>()).add(valid);
        try {
            for (String k : def.actions) {
                if (!valid[0]) return;
                try {
                    registry.runAction(k, ctx);
                } catch (Throwable t) {
                    warn("技能 \"" + def.name + "\" 动作 \"" + k + "\" 失败: " + t);
                }
            }
            if (valid[0]) rememberSkillTree(ctx);
        } finally {
            inFlightActions.computeIfPresent(instanceId, (ignored, chains) -> {
                chains.remove(valid);
                return chains.isEmpty() ? null : chains;
            });
        }
    }

    private void rememberSkillTree(dev.helstera.api.behavior.BehaviorContext ctx) {
        if (ctx == null || ctx.instance() == null || ctx.skillTree().isEmpty()) return;
        try {
            persistedTrees.put(ctx.instance().instanceId(), ctx.skillTree().orElseThrow().snapshot());
        } catch (IllegalStateException ex) {
            warn("SkillTree state was not persisted: " + ex.getMessage());
        }
    }

    /** Clears persisted skill-tree state when an instance is permanently removed. */
    public void clearSkillTree(int instanceId) {
        persistedTrees.remove(instanceId);
    }

    public java.util.Optional<dev.helstera.api.behavior.SkillTreeContext.Snapshot> snapshotOf(int instanceId) {
        return java.util.Optional.ofNullable(persistedTrees.get(instanceId));
    }

    public void restoreSkillTreeSnapshot(int instanceId,
                                         dev.helstera.api.behavior.SkillTreeContext.Snapshot snapshot) {
        if (snapshot != null) persistedTrees.put(instanceId, snapshot);
    }

    /** Writes the currently retained per-instance trees as one snapshot file per instance. */
    public int savePersistedSkillTrees(java.io.File directory) {
        if (directory == null) return 0;
        if (!directory.isDirectory() && !directory.mkdirs()) return 0;
        int saved = 0;
        for (var entry : persistedTrees.entrySet()) {
            try {
                dev.helstera.api.behavior.SkillTreeSnapshotStore.save(
                        new java.io.File(directory, entry.getKey() + ".yml"), entry.getValue());
                saved++;
            } catch (java.io.IOException | RuntimeException ex) {
                warn("SkillTree snapshot save failed for instance " + entry.getKey() + ": " + ex.getMessage());
            }
        }
        return saved;
    }

    /** Loads per-instance trees for in-process instance-id restoration. */
    public int loadPersistedSkillTrees(java.io.File directory) {
        if (directory == null || !directory.isDirectory()) return 0;
        int loaded = 0;
        java.io.File[] files = directory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return 0;
        for (java.io.File file : files) {
            try {
                String id = file.getName().substring(0, file.getName().length() - 4);
                int instanceId;
                try {
                    instanceId = Integer.parseInt(id);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                persistedTrees.put(instanceId,
                        dev.helstera.api.behavior.SkillTreeSnapshotStore.load(file));
                loaded++;
            } catch (java.io.IOException | RuntimeException ex) {
                warn("SkillTree snapshot load failed for " + file.getName() + ": " + ex.getMessage());
            }
        }
        return loaded;
    }

    /**
     * 开始一个命名技能的读条（不执行技能动作本身，只启动计时器）。
     * 用于「施法前摇」类场景：先读条，读条完成后再执行实际伤害。
     *
     * @return 是否成功启动读条
     */
    public boolean startCast(String skillName, dev.helstera.api.behavior.BehaviorContext ctx) {
        if (skillName == null || skillName.isBlank()) return false;
        SkillDef def = defs.get(skillName.toLowerCase(Locale.ROOT));
        if (def == null || def.castMillis <= 0) return false;
        return castSkill(skillName, ctx);
    }

    /**
     * 取消指定实例的活跃读条。
     *
     * @return 是否成功取消
     */
    private void beginCast(SkillDef def, dev.helstera.api.behavior.BehaviorContext ctx) {
        int instId = ctx != null && ctx.instance() != null ? ctx.instance().instanceId() : -1;
        activeCasts.put(instId + "|" + def.name,
                new SkillCast(def, System.currentTimeMillis(), instId, ctx));
    }

    /** Completes ready casts on the main-thread scheduler and releases queued skills. */
    public void tickCasts() {
        for (var entry : new ArrayList<>(activeCasts.entrySet())) {
            SkillCast cast = entry.getValue();
            if (!cast.isComplete() || !activeCasts.remove(entry.getKey(), cast)) continue;
            if (cast.context() == null) continue;
            executeSkillActions(cast.def(), cast.context());
            processQueue(cast.instanceId());
        }
    }

    public boolean cancelCast(int instId) {
        boolean removed = activeCasts.values().removeIf(c -> c.instanceId() == instId);
        invalidateScheduledActions(instId);
        if (removed) processQueue(instId);
        return removed;
    }

    /**
     * 清除某个实例的所有活跃读条（通常在死亡或 despawn 时调用）。
     */
    public void clearAllCastsFor(int instId) {
        activeCasts.values().removeIf(c -> c.instanceId() == instId);
        invalidateScheduledActions(instId);
        persistedTrees.remove(instId);
    }

    /**
     * 清除指定实例的技能队列与处理标记。
     */
    public void clearQueueFor(int instId) {
        skillQueue.remove(instId);
        queueProcessing.remove(instId);
        invalidateScheduledActions(instId);
    }

    private void invalidateScheduledActions(int instId) {
        executionGenerations.merge(instId, 1L, Long::sum);
        cleanupExecutionGeneration(instId);
    }

    /**
     * 获取实例当前读条进度；无读条时返回 null。
     *
     * @return Cast 读条数据（label + progress）；无读条返回 null
     */
    public dev.helstera.ai.bossbar.BossBarState.Cast getCastProgress(int instId) {
        for (var c : activeCasts.values()) {
            if (c.instanceId() == instId) {
                if (c.isComplete()) return null;
                return new dev.helstera.ai.bossbar.BossBarState.Cast(c.def().castLabel, c.progress());
            }
        }
        return null;
    }

    /** 当前活跃读条总数（供诊断命令与统计面板使用）。 */
    public int activeCastCount() {
        return activeCasts.size();
    }

    /** 当前排队中的技能总数（供诊断命令使用）。 */
    public int queuedSkillCount() {
        int total = 0;
        for (SkillQueueEntry q : skillQueue.values()) total += q.items.size();
        return total;
    }

    /**
     * 冷却键按「实例 + 技能」组合。
     *
     * <p>不按技能单独计时：同一技能被 50 只生物共用是常态，按技能计时会让
     * 第一只生物放行后其余 49 只在冷却期内全部失效——从配置上看完全正确，
     * 表现却是「技能偶尔只对一只生效」。</p>
     */
    private String cooldownKey(SkillDef def, dev.helstera.api.behavior.BehaviorContext ctx) {
        int id = ctx != null && ctx.instance() != null ? ctx.instance().instanceId() : -1;
        return cooldownKey(def, id);
    }

    private static String cooldownKey(SkillDef def, int instanceId) {
        return instanceId + "|" + def.name;
    }

    /** Resolves the managed instance which owns an entity evaluated by SkillOnCooldown. */
    public void instanceLookup(java.util.function.Function<org.bukkit.entity.Entity,
            dev.helstera.api.instance.ModelInstance> lookup) {
        instanceLookup = lookup == null ? ignored -> null : lookup;
    }

    /** Dynamically changes one named skill's cooldown for the current instance. */
    public boolean setSkillCooldown(String skillName,
                                    dev.helstera.api.behavior.BehaviorContext ctx,
                                    long durationMillis) {
        if (skillName == null || skillName.isBlank()) return false;
        String normalized = skillName.trim().toLowerCase(Locale.ROOT);
        SkillDef def = defs.get(normalized);
        if (def == null) return false;
        cooldownOverrides.put(cooldownKey(def, ctx), Math.max(0L, durationMillis));
        return true;
    }

    /** Releases per-instance dynamic cooldown state during entity teardown. */
    public void forget(int instanceId) {
        invalidateScheduledActions(instanceId);
        var chains = inFlightActions.remove(instanceId);
        if (chains != null) chains.forEach(chain -> chain[0] = false);
        String prefix = instanceId + "|";
        cooldowns.keySet().removeIf(key -> key.startsWith(prefix));
        cooldownOverrides.keySet().removeIf(key -> key.startsWith(prefix));
        activeCasts.keySet().removeIf(key -> key.startsWith(prefix));
        skillQueue.remove(instanceId);
        queueProcessing.remove(instanceId);
        persistedTrees.remove(instanceId);
    }

    private boolean cooldownReady(SkillDef def, String key, long now) {
        long duration = cooldownOverrides.getOrDefault(key, def.cooldownMillis);
        if (duration <= 0) return true;
        Long prev = cooldowns.get(key);
        return prev == null || (now - prev) >= duration;
    }

    /**
     * 迭代式 DFS 检环。
     *
     * <p>用显式栈而非递归：技能数量来自外部配置文件，递归实现遇到长链会
     * 直接 StackOverflowError，而这种崩溃发生在 enable 阶段，堆栈里全是引擎代码，
     * 很难反推到是哪条配置写成了环。</p>
     */
    private void detectCycles() {
        // 0=未访问 1=在当前路径上 2=已完成
        Map<String, Integer> color = new HashMap<>();
        for (String start : defs.keySet()) {
            if (color.getOrDefault(start, 0) != 0) continue;
            Deque<String> stack = new ArrayDeque<>();
            Deque<Integer> iter = new ArrayDeque<>();
            stack.push(start);
            iter.push(0);
            color.put(start, 1);
            while (!stack.isEmpty()) {
                String cur = stack.peek();
                SkillDef def = defs.get(cur);
                List<String> deps = def == null ? List.of() : new ArrayList<>(def.deps);
                int i = iter.pop();
                if (i < deps.size()) {
                    iter.push(i + 1);
                    String next = deps.get(i);
                    if (!defs.containsKey(next)) continue;
                    int c = color.getOrDefault(next, 0);
                    if (c == 1) {
                        markCycle(stack, next);
                    } else if (c == 0) {
                        color.put(next, 1);
                        stack.push(next);
                        iter.push(0);
                    }
                } else {
                    color.put(cur, 2);
                    stack.pop();
                }
            }
        }
    }

    /** 把栈中从 {@code from} 到栈顶的一段标记为环，并告警一次。 */
    private void markCycle(Deque<String> stack, String from) {
        List<String> path = new ArrayList<>();
        boolean collecting = false;
        for (String s : stack) {           // ArrayDeque 的迭代自栈顶向栈底
            if (s.equals(from)) collecting = true;
            if (collecting) path.add(s);
        }
        java.util.Collections.reverse(path);   // 还原成 栈底->栈顶 的调用顺序
        path.add(from);
        for (String s : path) {
            SkillDef d = defs.get(s);
            if (d != null) d.cyclic = true;
        }
        warn("技能循环引用: " + String.join(" -> ", path) + "（已跳过递归展开）");
    }

    /** 冷却时间解析：6s / 200t / 1m / 200ms / 裸数字（按秒）。 */
    private static long parseCooldownMillis(Object raw) {
        if (raw == null) return 0;
        if (raw instanceof Number n) return Math.max(0, (long) (n.doubleValue() * 1000));
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return 0;
        try {
            // 先剥掉后缀再按后缀换算。剥不掉就说明后缀不认识，此时若强行 parseLong
            // "0.5" 会抛异常并静默回落成 0 —— 作者写了 0.5 秒却得到「无冷却」，
            // 表现为技能疯狂触发，比直接报错更难定位。
            if (s.endsWith("ms") || s.endsWith("milli") || s.endsWith("millis")) {
                return (long) Math.max(0, Double.parseDouble(s.replaceAll("(ms|milli|millis?)$", "").trim()));
            }
            if (s.endsWith("t") || s.endsWith("tick") || s.endsWith("ticks")) {
                return (long) Math.max(0, Double.parseDouble(s.replaceAll("(t|ticks?)$", "").trim()) * 50L);
            }
            if (s.endsWith("m") || s.endsWith("min") || s.endsWith("mins")) {
                return (long) Math.max(0, Double.parseDouble(s.replaceAll("(m|min|mins?)$", "").trim()) * 60_000);
            }
            if (s.endsWith("s") || s.endsWith("sec") || s.endsWith("secs")) {
                return (long) Math.max(0, Double.parseDouble(s.replaceAll("(s|sec|secs?)$", "").trim()) * 1000);
            }
            return (long) Math.max(0, Double.parseDouble(s) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 从一行定义里解析「嵌套技能引用」。
     *
     * <p>容忍三种写法：{@code cast-skill X}、{@code skill{s=X}}、
     * {@code skill{s=X;cooldown=3s}}。返回 null 表示这行不是技能引用。</p>
     */
    public static String parseSkillRef(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (s.isEmpty()) return null;
        if (s.regionMatches(true, 0, "cast-skill:", 0, "cast-skill:".length())) {
            String bound = s.substring("cast-skill:".length()).trim();
            return bound.isEmpty() ? null : bound;
        }
        if (s.regionMatches(true, 0, "cast-skill", 0, "cast-skill".length())) {
            try {
                MythicSkillSpec parsed = MythicSkillSpec.parse(s);
                if (!parsed.mechanic().equals("cast-skill") || parsed.positional().size() != 1) return null;
                String ref = parsed.positional().get(0).trim();
                return ref.isEmpty() ? null : ref;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        int brace = s.indexOf('{');
        if (brace >= 0 && (s.regionMatches(true, 0, "skill{", 0, 6)
                || s.regionMatches(true, 0, "skillmechanic{", 0, 14))) {
            try {
                MythicSkillSpec parsed = MythicSkillSpec.parse(s);
                String ref = parsed.arguments().get("s");
                if (ref == null) ref = parsed.arguments().get("skill");
                return ref == null || ref.isBlank() ? null : ref;
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if (brace < 0) {
            // MythicMobs 的触发器写法：~onSpawn:SkillName / onSpawn:SkillName。
            // 冒号左侧必须是已知触发器，否则 "on-hit:fire" 这类自定义 mechanic{...}
            // 之外的写法会被误判成技能引用。
            int colon = s.indexOf(':');
            if (colon <= 0 || colon == s.length() - 1) return null;
            if (SkillTrigger.of(s.substring(0, colon)) == null) return null;
            return s.substring(colon + 1).trim();
        }
        String head = s.substring(0, brace).trim();
        if (!head.equalsIgnoreCase("skill") && !head.equalsIgnoreCase("skillmechanic")) return null;
        String body = s.substring(brace + 1);
        int close = body.lastIndexOf('}');
        if (close >= 0) body = body.substring(0, close);
        for (String part : body.split("[;,]")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "s=", 0, 2) || p.regionMatches(true, 0, "skill=", 0, 6)) {
                int eq = p.indexOf('=');
                String v = p.substring(eq + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** 将 profile 中 require / on-decision 的文本定义展开为已绑定的键。 */
    public void expand(AiProfile profile, List<String> skillNames) {
        for (String s : skillNames) {
            String k = bindCondition("skill:" + s);
            if (k != null) profile.require.add(k);
            String a = bindAction("skill:" + s);
            if (a != null) profile.onDecision.add(a);
        }
    }

    /**
     * 展开 profile 的事件触发器：把 require / do 中的文本定义替换为已绑定的键。
     * 原地修改，便于在 loadProfiles 阶段一次性完成解析。
     */
    public void expandTriggers(AiProfile profile) {
        if (profile.triggers.isEmpty()) return;
        for (var entry : profile.triggers.entrySet()) {
            AiProfile.TriggerSpec spec = entry.getValue();
            List<String> require = new ArrayList<>();
            for (String s : spec.require) {
                String k = bindCondition(s);
                if (k != null) require.add(k);
            }
            List<String> actions = new ArrayList<>();
            for (String s : spec.actions) {
                String k = bindAction(s);
                if (k != null) actions.add(k);
            }
            spec.require.clear();
            spec.require.addAll(require);
            spec.actions.clear();
            spec.actions.addAll(actions);
        }
    }

    /** 保留引号内的空格，其余按空白切分。 */
    private static boolean isVariableCondition(String name) {
        return switch (name) {
            case "has-var", "variable-is-set", "variable-equals", "variable-in-range",
                    "variable-not-equals", "variable-contains", "variable-not-contains",
                    "variable-index-equals", "variable-index-gte", "variable-index-lte", "variable-index-in-range", "variable-key-exists",
                    "variable-key-not-exists", "variable-map-value-equals", "variable-map-value-not-equals",
                    "variable-map-value-gte", "variable-map-value-lte", "variable-map-value-in-range",
                    "variable-map-value-not-in-range",
                    "variable-gte", "variable-lte", "directional-velocity", "distance-from-location",
                    "distance-from-tracked-location",
                    "has-aura-stacks", "mythic-has-aura", "mythic-has-aura-stacks",
                    "mythic-has-aura-type", "mythic-target-has-aura", "health", "health-percent",
                    "compare-values", "score", "targets",
                    "cuboid", "in-bounds", "global-score" -> true;
            default -> false;
        };
    }

    private static String canonicalConditionName(String name) {
        return switch (name) {
            case "isinaudience", "inaudience", "audiencecontains", "is-in-audience" -> "is-in-audience";
            case "iscancelled", "iscanceled", "cancelled", "canceled", "is-cancelled", "is-canceled" -> "is-cancelled";
            case "permission", "permission-is", "haspermission" -> "has-permission";
            case "casterpermission", "caster-permission", "casterhaspermission" -> "caster-has-permission";
            case "entity-type", "entitytype", "entitytypeis" -> "entity-type-is";
            case "name", "name-is", "nameis" -> "name-is";
            case "color", "clr", "color-is" -> "color";
            case "spawnreason", "spawn-reason" -> "spawn-reason";
            case "isliving", "living", "is-living" -> "is-living";
            case "isplayer", "player" -> "is-player";
            case "ismob", "mob" -> "is-mob";
            case "ismonster", "monster" -> "is-monster";
            case "isanimal", "animal" -> "is-animal";
            case "isvillager", "villager" -> "is-villager";
            case "istameable", "tameable" -> "is-tameable";
            case "iswatermob", "watermob", "is-water-mob" -> "is-water-mob";
            case "iszombie", "zombie", "is-zombie" -> "is-zombie";
            case "aerial", "isaerial", "is-aerial" -> "aerial";
            case "isenderman", "enderman", "is-enderman" -> "is-enderman";
            case "israider", "raider" -> "is-raider";
            case "israiderpatrolleader", "is-patrol-leader" -> "is-raider-patrol-leader";
            case "isundead", "undead" -> "is-undead";
            case "iscreature", "creature" -> "is-creature";
            case "isbaby", "baby" -> "is-baby";
            case "ischild", "child", "is-child" -> "is-child";
            case "isadult", "adult" -> "is-adult";
            case "mobsize", "mob-size", "size" -> "mob-size";
            case "angry", "isangry", "is-angry" -> "angry";
            case "pollinated", "ispollinated", "is-pollinated", "hasnectar", "has-nectar" -> "pollinated";
            case "istamed", "tamed" -> "is-tamed";
            case "issheared", "sheared" -> "is-sheared";
            case "issaddled", "saddled" -> "is-saddled";
            case "ispowered", "powered", "ischarged", "charged" -> "is-powered";
            case "iscollidable", "is-collidable", "collidable" -> "is-collidable";
            case "hasoffhand", "has-offhand", "offhand" -> "has-offhand";
            case "isop", "is-op", "isoperator", "is-operator" -> "is-op";
            case "hasai", "has-ai" -> "has-ai";
            case "hasgravity", "has-gravity" -> "has-gravity";
            case "issilent", "silent", "is-silent" -> "is-silent";
            case "isdead", "dead", "is-dead" -> "is-dead";
            case "isalive", "alive", "is-alive" -> "is-alive";
            case "isvalid", "valid", "is-valid" -> "is-valid";
            case "targetisplayer", "target-is-player" -> "target-is-player";
            case "iscaster", "is-caster" -> "is-caster";
            case "targetisop", "target-is-op", "targetisoperator", "target-is-operator" -> "target-is-op";
            case "targetisliving", "target-is-living" -> "target-is-living";
            case "targetismob", "target-is-mob" -> "target-is-mob";
            case "targetismonster", "target-is-monster" -> "target-is-monster";
            case "targetisanimal", "target-is-animal" -> "target-is-animal";
            case "targetisvillager", "target-is-villager" -> "target-is-villager";
            case "targetistameable", "target-is-tameable" -> "target-is-tameable";
            case "targetisraider", "target-is-raider" -> "target-is-raider";
            case "targetisundead", "target-is-undead" -> "target-is-undead";
            case "targetiscreature", "target-is-creature" -> "target-is-creature";
            case "targetisbaby", "target-is-baby", "targetischild", "target-is-child" -> "target-is-baby";
            case "targetisadult", "target-is-adult" -> "target-is-adult";
            case "targetistamed", "target-is-tamed" -> "target-is-tamed";
            case "targetissheared", "target-is-sheared" -> "target-is-sheared";
            case "targetissaddled", "target-is-saddled" -> "target-is-saddled";
            case "targetispowered", "target-is-powered", "targetischarged", "target-is-charged" -> "target-is-powered";
            case "targetiscollidable", "target-is-collidable" -> "target-is-collidable";
            case "targethasoffhand", "target-has-offhand", "targetoffhand" -> "target-has-offhand";
            case "targetissneaking", "target-is-sneaking" -> "target-is-sneaking";
            case "targethasai", "target-has-ai" -> "target-has-ai";
            case "targethasgravity", "target-has-gravity" -> "target-has-gravity";
            case "targetissilent", "target-is-silent" -> "target-is-silent";
            case "targetisvalid", "target-is-valid" -> "target-is-valid";
            case "targetissprinting", "target-is-sprinting" -> "target-is-sprinting";
            case "targetisflying", "target-is-flying" -> "target-is-flying";
            case "targetisblocking", "target-is-blocking" -> "target-is-blocking";
            case "targetisgliding", "target-is-gliding" -> "target-is-gliding";
            case "targetisswimming", "target-is-swimming" -> "target-is-swimming";
            case "targetonfire", "target-on-fire", "targetburning" -> "target-on-fire";
            case "targetinair", "target-in-air", "targetairborne" -> "target-in-air";
            case "targetinwater", "target-in-water" -> "target-in-water";
            case "targetinlava", "target-in-lava" -> "target-in-lava";
            case "targetisglowing", "target-is-glowing" -> "target-is-glowing";
            case "targetisinvisible", "target-is-invisible" -> "target-is-invisible";
            case "targetisclimbing", "target-is-climbing" -> "target-is-climbing";
            case "targetisfrozen", "targetfrozen", "target-is-frozen" -> "target-is-frozen";
            case "targetisblocked", "target-is-blocked" -> "target-is-blocked";
            case "inside", "in" -> "inside";
            case "outside", "out" -> "outside";
            case "targetisdead", "target-is-dead" -> "target-dead";
            case "targetisalive", "target-is-alive" -> "target-is-alive";
            case "targetisonground", "target-is-on-ground" -> "target-on-ground";
            case "targetisairborne", "target-is-airborne" -> "target-airborne";
            case "targethaspermission", "target-has-permission", "targetpermission" -> "target-has-permission";
            case "targetgamemode", "target-gamemode", "targetgamemodeis", "target-game-mode-is" ->
                    "target-game-mode-is";
            case "targetfoodlevel", "target-food-level", "targetfoodlevelis", "target-food-level-is" ->
                    "target-food-level";
            case "foodsaturation", "food-saturation", "saturation" -> "food-saturation";
            case "targetfoodsaturation", "target-food-saturation" -> "target-food-saturation";
            case "targetairlevel", "target-air-level", "targetremainingair", "target-remaining-air" ->
                    "target-air-level";
            case "targethasitem", "target-has-item", "targetitemcount", "target-item-count" ->
                    "target-has-item";
            case "targetpotioneffectactive", "target-potion-effect-active", "targetpotionactive" ->
                    "target-potion-effect-active";
            case "targethasauras" -> "mythic-target-has-aura";
            case "mythic-target-has-aura", "target-has-mythic-aura" -> "mythic-target-has-aura";
            case "kill", "kill-targets" -> "kill-targets";
            case "playerkills", "player-kills", "player-kill-count" -> "player-kills";
            case "targethaspotion", "target-has-potion", "targethaspotioneffect",
                    "target-has-potion-effect" ->
                    "target-potion-effect-active";
            case "targetholding", "target-holding" -> "target-holding";
            case "targetwearing", "target-wearing" -> "target-wearing";
            case "ownerisplayer", "owner-is-player" -> "owner-is-player";
            case "ownerisonline", "owner-is-online" -> "owner-is-online";
            case "owner", "isowner", "is-owner" -> "is-owner";
            case "children", "children-count", "child-count" -> "children";
            case "samefaction", "same-faction" -> "same-faction";
            case "differentfaction", "different-faction" -> "different-faction";
            case "stance", "stanceis" -> "stance-is";
            case "target", "target-exists", "targetexists", "hastarget" -> "has-target";
            case "hastag", "has-tag", "hasscoreboardtag", "has-scoreboard-tag" -> "has-tag";
            case "targethastag", "target-has-tag", "targethasscoreboardtag",
                    "target-has-scoreboard-tag" -> "target-has-tag";
            case "targettype", "target-type", "targettypeis", "target-type-is" -> "target-is";
            case "lineofsight", "haslineofsight",
                    "target-line-of-sight" -> "has-line-of-sight";
            case "sneaking", "issneaking", "sneakingis" -> "is-sneaking";
            case "sprinting", "issprinting", "sprintingis" -> "is-sprinting";
            case "glowing", "isglowing", "glowingis" -> "is-glowing";
            case "invisible", "isinvisible", "invisibleis" -> "is-invisible";
            case "invulnerable", "isinvulnerable", "invulnerableis" -> "is-invulnerable";
            case "swimming", "isswimming", "swimmingis" -> "is-swimming";
            case "climbing", "isclimbing", "climbingis" -> "is-climbing";
            case "inair", "isinair", "is-in-air", "airborne", "isairborne" -> "in-air";
            case "gliding", "isgliding", "glidingis" -> "is-gliding";
            case "flying", "isflying", "flyingis" -> "is-flying";
            case "blocking", "isblocking", "blockingis" -> "is-blocking";
            case "facingtarget", "facing-target" -> "facing-target";
            case "onground", "isonground", "is-on-ground" -> "on-ground";
            case "inwater", "isinwater", "is-in-water" -> "in-water";
            case "inlava", "isinlava", "is-in-lava" -> "in-lava";
            case "onfire", "isonfire", "is-on-fire", "isburning" -> "on-fire";
            case "isblocked", "is-blocked", "blocked" -> "is-blocked";
            case "standon", "stand-on", "standingon", "standing-on" -> "stand-on";
            case "daylight", "isdaylight", "is-daylight" -> "daylight";
            case "playersinradius" -> "players-in-radius";
            case "entitiesinradius" -> "entities-in-radius";
            case "mobsinradius" -> "mobs-in-radius";
            case "mobsnearorigin", "mobs-near-origin" -> "mobs-near-origin";
            case "livinginradius", "living-in-radius" -> "living-in-radius";
            case "mobsinchunk", "mobs-in-chunk" -> "mobs-in-chunk";
            case "dimension", "dimension-is" -> "dimension-is";
            case "enderdragonalive", "isdragonalive", "dragonalive", "ender-dragon-alive" ->
                    "ender-dragon-alive";
            case "enderdragonphase", "ender-dragon-phase", "edragonphase" -> "ender-dragon-phase";
            case "distancefromspawn", "distance-from-spawn" -> "distance-from-spawn";
            case "distancefromlocation", "distance-from-location" -> "distance-from-location";
            case "distancefromtrackedlocation", "distance-from-tracked-location", "distancefromtl" ->
                    "distance-from-tracked-location";
            case "xdiff", "x-diff" -> "x-diff";
            case "ydiff", "y-diff" -> "y-diff";
            case "zdiff", "z-diff" -> "z-diff";
            case "originlocation", "origin-location" -> "origin-location";
            case "playersonline", "onlineplayercount", "onlineplayers" -> "players-online";
            case "playersinworld" -> "players-in-world";
            case "mobsinworld" -> "mobs-in-world";
            case "playerwithin", "playerswithin" -> "player-within";
            case "playernotwithin", "playersnotwithin" -> "player-not-within";
            case "incombat" -> "in-combat";
            case "targetcount", "target-count" -> "target-count-at-least";
            case "hungerbelow", "hunger-below" -> "hunger-below";
            case "motionx", "motion-x", "motx" -> "motion-x";
            case "motiony", "motion-y", "moty" -> "motion-y";
            case "directionalvelocity", "directional-velocity", "dvelocity" -> "directional-velocity";
            case "fallspeed", "fall-speed", "falling-speed" -> "fall-speed";
            case "motionz", "motion-z", "motz" -> "motion-z";
            case "velocity" -> "velocity";
            case "height", "height-is" -> "height";
            case "altitude", "height-from-surface" -> "altitude";
            case "heightabove", "height-above" -> "height-above";
            case "heightbelow", "height-below" -> "height-below";
            case "pitch" -> "pitch";
            case "yaw" -> "yaw";
            case "onblock", "on-block" -> "on-block";
            case "moist", "ismoist", "is-moist" -> "moist";
            case "moisturelevel", "moisture-level", "moistureness", "moistness" ->
                    "moisture-level";
            case "blocktype", "block-type-is", "block-type" -> "block-type-is";
            case "blocktypeinradius", "block-type-in-radius" -> "block-type-in-radius";
            case "biometype", "biome-type-is", "biomecategory" -> "biome-type-is";
            case "lightlevel", "light-level", "lightlevelis", "light-level-is" -> "light-level";
            case "lightlevelbelow" -> "light-level-below";
            case "lightlevelabove" -> "light-level-above";
            case "skylightlevel", "sky-light-level" -> "sky-light-level";
            case "lightlevelfromblocks", "light-level-from-blocks", "blocklightlevel" ->
                    "light-level-from-blocks";
            case "ylevelbelow" -> "y-level-below";
            case "ylevelabove" -> "y-level-above";
            case "targethealthbelow" -> "target-health-below";
            case "targethealthabove" -> "target-health-above";
            case "targethealthpercent", "targethealthpercentage" -> "target-health-percent";
            case "targethealth" -> "target-health";
            case "targetinvulnerable", "target-is-invulnerable", "targetinvulnerableis" -> "target-invulnerable";
            case "gamemode", "gamemodeis" -> "game-mode-is";
            case "serverispaper", "is-paper", "ispaper" -> "server-is-paper";
            case "servernmsversion", "server-nms-version", "servernms", "nmsversion" ->
                    "server-nms-version";
            case "plugin", "plugin-exists", "pluginexists", "hasplugin", "has-plugin" -> "plugin";
            case "serverversion", "server-version", "server", "version" -> "server-version";
            case "serveraftereq", "serverafter", "versionaftereq", "server-version-after-or-equal" ->
                    "server-version-after-or-equal";
            case "serverbefore", "versionbefore", "server-version-before" -> "server-version-before";
            case "ismythicmob", "is-mythic-mob", "ismm" -> "is-mythic-mob";
            case "mythicmobtype", "mythic-mob-type", "mmtype" -> "mythic-mob-type";
            case "iscreeperprimed", "is-creeper-primed" -> "is-creeper-primed";
            case "boundingboxesoverlap", "bounding-boxes-overlap", "bbsoverlap" ->
                    "bounding-boxes-overlap";
            case "cuboid", "incuboid" -> "cuboid";
            case "inbounds", "in-bounds", "withinbounds", "insidebounds" -> "in-bounds";
            case "fieldofview", "field-of-view", "fov", "infieldofview" -> "field-of-view";
            case "isinsurvivalmode", "in-survival-mode", "insurvivalmode" -> "is-in-survival-mode";
            case "isusingspyglass", "is-using-spyglass", "isscoping" -> "is-using-spyglass";
            case "foodlevel", "food-level", "foodlevelis", "food-level-is" -> "food-level";
            case "enchantinglevel", "enchanting-level" -> "enchanting-level";
            case "enchantingexperience", "enchanting-experience", "enchantingexp",
                    "enchanting-exp", "enchantexperience", "enchant-exp" -> "enchanting-experience";
            case "airlevel", "air-level", "remainingair", "remaining-air", "air" -> "air-level";
            case "damagecause", "damage-cause", "cause" -> "damage-cause";
            case "damageamount", "damage-amount", "amount", "a" -> "damage-amount";
            case "damagetag", "damage-tag", "damagehastag", "damage-has-tag" -> "damage-tag";
            case "lastdamagecause", "last-damage-cause" -> "last-damage-cause";
            case "hasitem", "has-item", "item-count", "itemcount" -> "has-item";
            case "hasenchantment", "has-enchantment", "hasenchant", "has-enchant" ->
                    "has-enchantment";
            case "haspotioneffect", "has-potion-effect" -> "has-potion-effect";
            case "hasdialogopen", "has-dialog-open", "dialogopen", "dialog-open" ->
                    "has-dialog-open";
            case "isincinematic", "is-in-cinematic", "incinematic" -> "is-in-cinematic";
            case "materialisoncooldown", "material-is-on-cooldown", "materialcooldown",
                    "matcooldown" -> "material-on-cooldown";
            case "triggeritemtype", "trigger-item-type", "triggeringitemtype" -> "trigger-item-type";
            case "itemtype", "item-type" -> "trigger-item-type";
            case "triggerblocktype", "trigger-block-type", "triggeringblocktype" -> "trigger-block-type";
            case "entityitemtype", "entity-item-type" -> "entity-item-type";
            case "entitymaterialtype", "entity-material-type" -> "entity-material-type";
            case "entityitemissimilar", "entity-item-is-similar" -> "entity-item-is-similar";
            case "itemissimilar", "item-is-similar", "issimilar", "similarto", "similar-to" ->
                    "item-is-similar";
            case "itemrecharging", "item-recharging", "recharging" -> "item-recharging";
            case "hasfreeslot", "has-free-slot", "hasfreeinventoryslot",
                    "has-free-inventory-slot" -> "has-free-inventory-slot";
            case "potioneffectactive", "potionactive" -> "potion-effect-active";
            case "haspassenger", "has-passenger", "haspassengers" -> "has-passenger";
            case "isvehicle", "is-vehicle", "vehicle" -> "is-vehicle";
            case "targethaspassenger", "target-has-passenger", "targethaspassengers" -> "target-has-passenger";
            case "targetisvehicle", "target-is-vehicle" -> "target-is-vehicle";
            case "randomchance" -> "random-chance";
            case "offgcd", "off-global-cooldown" -> "offgcd";
            case "healthbelow" -> "health-below";
            case "healthabove" -> "health-above";
            case "healthpercent", "health-percentage" -> "health-percent";
            case "level-is", "mob-level", "moblevel" -> "level";
            case "isfrozen", "is-frozen", "frozen" -> "is-frozen";
            case "lunarphase", "lunar-phase", "moonphase", "moon-phase" -> "lunar-phase";
            case "lastsignal", "last-signal" -> "last-signal";
            case "projectilehasended", "projectile-has-ended" -> "projectile-has-ended";
            case "absorptionamount", "absorption-amount", "hasabsorption", "has-absorption" ->
                    "absorption";
            case "distancebelow" -> "distance-below";
            case "distanceabove" -> "distance-above";
            case "world", "worldis" -> "world-is";
            case "biome", "biomeis" -> "biome-is";
            case "weather", "weatheris" -> "weather-is";
            case "time", "timeis" -> "time-is";
            case "worldtime", "world-time" -> "world-time";
            case "night", "nighttime" -> "night";
            case "raining", "israining", "rain" -> "raining";
            case "thundering", "isthundering", "thunder" -> "thundering";
            case "falling", "isfalling" -> "falling";
            case "drowning", "isdrowning" -> "drowning";
            case "hasauras", "has-auras", "haspotion", "has-potion" -> "has-auras";
            case "hasaura", "has-aura", "hasbuff", "has-buff", "hasdebuff", "has-debuff" ->
                    "mythic-has-aura";
            case "hasaurastacks", "hasaura-stacks", "hasbuffstacks", "has-buff-stacks",
                    "hasdebuffstacks", "has-debuff-stacks", "aurastacks", "aura-stacks",
                    "buffstacks", "buff-stacks", "debuffstacks", "debuff-stacks" ->
                    "mythic-has-aura-stacks";
            case "hasauratype", "has-aura-type", "hasbufftype", "has-buff-type",
                    "hasdebufftype", "has-debuff-type" -> "mythic-has-aura-type";
            case "isriding", "riding", "is-mounted" -> "mounted";
            case "vehicleisdead", "vehicle-is-dead" -> "vehicle-is-dead";
            case "targetwithin", "target-within" -> "target-within";
            case "targetnotwithin", "target-not-within" -> "target-not-within";
            case "targetinlineofsight", "targetlineofsight", "target-has-line-of-sight" -> "target-has-line-of-sight";
            case "targetnotinlineofsight", "target-not-in-line-of-sight" -> "target-not-in-line-of-sight";
            case "lineofsightfromorigin", "line-of-sight-from-origin", "inlineofsightfromorigin" ->
                    "line-of-sight-from-origin";
            case "structure", "structures" -> "structure";
            case "stringequals", "string-equals", "stringeq" -> "string-equals";
            case "stringempty", "string-empty", "isempty" -> "string-empty";
            case "stringnotempty", "string-not-empty", "notempty" -> "string-not-empty";
            case "isskill", "is-skill", "skillexists", "skill-exists" -> "is-skill";
            case "skilloncooldown", "skill-on-cooldown" -> "skill-on-cooldown";
            case "globalscore", "global-score", "scoreglobal" -> "global-score";
            case "score" -> "score";
            case "comparevalues", "compare-values", "comparevalue" -> "compare-values";
            case "lookingat", "looking-at" -> "looking-at";
            case "bowtension", "bow-tension", "bowshoottension" -> "bow-tension";
            case "variableisset", "variable-is-set" -> "variable-is-set";
            case "branchunlocked", "branch-unlocked", "hasbranch", "has-branch" -> "branch-unlocked";
            case "branchlocked", "branch-locked" -> "branch-locked";
            case "variableequals", "variable-equals" -> "variable-equals";
            case "variablenotequals", "variable-ne", "variable-not-equal" -> "variable-not-equals";
            case "variableinrange", "variable-in-range" -> "variable-in-range";
            case "variablecontains", "variable-contains" -> "variable-contains";
            case "variablenotcontains", "variable-not-contains" -> "variable-not-contains";
            case "variableindexequals", "variable-index-equals", "variableat" -> "variable-index-equals";
            case "variableindexgte", "variable-index-gte" -> "variable-index-gte";
            case "variableindexlte", "variable-index-lte" -> "variable-index-lte";
            case "variableindexinrange", "variable-index-in-range" -> "variable-index-in-range";
            case "variablekeyexists", "variable-key-exists", "mapkeyexists" -> "variable-key-exists";
            case "variablekeynotexists", "variable-key-not-exists", "mapkeynotexists" -> "variable-key-not-exists";
            case "variablemapvalueequals", "variable-map-value-equals", "mapvalueequals" -> "variable-map-value-equals";
            case "variablemapvaluenotequals", "variable-map-value-not-equals", "mapvaluenotequals" -> "variable-map-value-not-equals";
            case "variablemapvaluegte", "variable-map-value-gte", "mapvaluegte" -> "variable-map-value-gte";
            case "variablemapvaluelte", "variable-map-value-lte", "mapvaluelte" -> "variable-map-value-lte";
            case "variablemapvalueinrange", "variable-map-value-in-range", "mapvalueinrange" -> "variable-map-value-in-range";
            case "variablemapvaluenotinrange", "variable-map-value-not-in-range", "mapvaluenotinrange" -> "variable-map-value-not-in-range";
            default -> name;
        };
    }
    private static boolean isVariableMechanic(String name) {
        return switch (name) {
            case "variable-unset", "var-unset", "variable-location-set", "location-unset",
                    "var-set", "set-variable", "var-add", "var-math",
                    "score-set", "score-add", "signal", "dot", "mythic-aura",
                    "mythic-aura-remove", "add-to-audience", "remove-from-audience",
                    "unset-audience", "teleport-mythic" -> true;
            default -> false;
        };
    }

    private static String canonicalActionName(String name) {
        return switch (name) {
            case "addtoaudience", "addaudience", "audienceadd", "add-to-audience" -> "add-to-audience";
            case "removefromaudience", "removeaudience", "audienceremove", "remove-from-audience" -> "remove-from-audience";
            case "unsetaudience", "clearaudience", "audienceclear", "unset-audience" -> "unset-audience";
            case "variableunset", "unsetvariable", "unsetvar", "varunset" -> "variable-unset";
            case "setvariable", "setvar" -> "set-variable";
            case "variableadd", "varadd" -> "var-add";
            case "variablemath", "varmath" -> "var-math";
            case "setvariablelocation", "variablelocationset", "setvarloc" -> "variable-location-set";
            case "locationunset" -> "location-unset";
            case "tracklocation", "settrackedlocation", "stl", "track-location" -> "track-location";
            case "aura", "buff", "debuff", "mythicaura" -> "mythic-aura";
            case "auraremove", "aura-remove", "mythicauraremove", "removeaura", "remove-aura",
                    "removebuff", "remove-buff", "removedebuff", "remove-debuff" -> "mythic-aura-remove";
            case "explosion" -> "explode";
            case "aoedamage" -> "aoe-damage";
            case "damage" -> "damage-target";
            case "heal" -> "heal-self";
            case "message", "msg" -> "message-target";
            case "actionbar" -> "actionbar";
            case "title" -> "title";
            case "effect", "potion" -> "effect-targets";
            case "ignite" -> "ignite-targets";
            case "knockback" -> "knockback-targets";
            case "settarget", "target" -> "set-target";
            case "cleartarget", "untarget" -> "clear-target";
            case "setvelocity", "velocity" -> "velocity-targets";
            case "setscale" -> "set-scale";
            case "setintent" -> "set-intent";
            case "settickspeed", "set-tick-speed" -> "set-tick-speed";
            case "playanimation" -> "play-animation";
            case "stopanimation" -> "stop-animation";
            case "setname" -> "set-name";
            case "addtag", "add-tag", "addscoreboardtag", "add-scoreboard-tag" -> "add-tag";
            case "removetag", "remove-tag", "removescoreboardtag", "remove-scoreboard-tag" -> "remove-tag";
            case "setscore", "set-score", "score-set" -> "score-set";
            case "addscore", "add-score", "score-add" -> "score-add";
            case "setitem" -> "set-item";
            case "dropitem" -> "drop-item";
            case "giveitem", "give" -> "give-item";
            case "takeitem", "take", "consume", "removeitem", "remove-item" -> "take-item";
            case "closeinventory", "close-inventory" -> "close-inventory";
            case "clearinventory", "clear-inventory" -> "clear-inventory";
            case "clearexperience", "clear-experience", "clearexp" -> "clear-experience";
            case "clearexperiencelevels", "clear-experience-levels", "clearexplevels" ->
                    "clear-experience-levels";
            case "giveexperiencelevels", "give-experience-levels", "giveexplevels" ->
                    "give-experience-levels";
            case "giveexperience", "give-experience", "givexp", "give-xp" -> "give-experience";
            case "setabsorption", "set-absorption" -> "set-absorption";
            case "oxygen" -> "oxygen";
            case "consumeslot", "consume-slot", "consumeslotitem" -> "consume-slot";
            case "disarm", "remove-held-item", "removehelditem" -> "disarm";
            case "setmaterialcooldown", "set-material-cooldown", "setitemcooldown",
                    "set-item-cooldown", "materialcooldown" ->
                    "set-material-cooldown";
            case "setgamemode", "set-game-mode", "gamemode" -> "set-game-mode";
            case "forcepull", "force-pull" -> "force-pull";
            case "setoffhand", "set-offhand" -> "set-offhand";
            case "setarmor", "set-armor" -> "set-armor";
            case "setfaction", "set-faction" -> "set-faction";
            case "setlevel", "set-level" -> "set-level";
            case "setweather", "set-weather" -> "set-weather";
            case "settime", "set-time" -> "set-time";
            case "lightning", "strike-lightning" -> "lightning";
            case "feed", "food" -> "feed";
            case "setblock", "set-block" -> "set-block";
            case "breakblock", "break-block" -> "break-block";
            case "setai", "set-ai" -> "set-ai";
            case "setgravity", "set-gravity", "setnogravity" -> "set-gravity";
            case "setsilent", "set-silent" -> "set-silent";
            case "setcollidable", "set-collidable" -> "set-collidable";
            case "setsneaking", "set-sneaking" -> "set-sneaking";
            case "setgliding", "set-gliding" -> "set-gliding";
            case "setswimming", "set-swimming" -> "set-swimming";
            case "setsprinting", "set-sprinting" -> "set-sprinting";
            case "setflying", "set-flying" -> "set-flying";
            case "setnodamageticks", "set-no-damage-ticks", "setndt" -> "set-no-damage-ticks";
            case "setmaxnodamageticks", "set-maximum-no-damage-ticks", "setmaximumnodamageticks" ->
                    "set-maximum-no-damage-ticks";
            case "setwalkspeed", "set-walk-speed" -> "set-walk-speed";
            case "setinvincible", "set-invincible" -> "set-invulnerable";
            case "setfire", "set-fire", "ignite-self" -> "set-fire";
            case "clearfire", "clear-fire", "extinguish" -> "clear-fire";
            case "freeze" -> "freeze";
            case "look", "lookat", "look-at" -> "look-at";
            case "setrotation", "set-rotation", "rotation" -> "set-rotation";
            case "setstance", "set-stance" -> "set-stance";
            case "randomskill", "random-skill" -> "random-skill";
            case "remove", "remove-self" -> "remove-self";
            case "setowner", "set-owner" -> "set-owner";
            case "removeowner", "remove-owner" -> "remove-owner";
            case "setparent", "set-parent" -> "set-parent";
            case "removeparent", "remove-parent" -> "remove-parent";
            case "addthreat", "threatadd" -> "threat-add";
            case "clearthreat", "threatclear" -> "threat-clear";
            case "threatfocus", "focusthreat" -> "threat-focus";
            case "messageall" -> "message-all";
            case "messageself" -> "message-self";
            case "randommessage" -> "random-message";
            case "setskillcooldown", "skillcooldown", "setskillcd", "skillcd" -> "set-skill-cooldown";
            case "teleportself", "teleport-self" -> "teleport-self";
            case "teleportcaster", "teleport-caster" -> "teleport-caster";
            case "sethealth", "set-health" -> "set-health";
            case "modifyhealth", "modify-health" -> "modify-health";
            case "modifyspeed", "modify-speed" -> "modify-speed";
            case "knockbackself", "knockback-self" -> "knockback-self";
            case "startcast", "start-cast" -> "start-cast";
            case "cancelcast", "cancel-cast" -> "cancel-cast";
            case "startdialogue", "start-dialogue", "startdialog" -> "start-dialogue";
            case "setinvulnerable", "set-invulnerable" -> "set-invulnerable";
            case "mount" -> "mount";
            case "dismount", "unmount" -> "dismount";
            case "ejectpassenger", "eject_passenger", "eject-passenger" -> "eject-passenger";
            case "cancelevent" -> "cancel-event";
            case "damagepercent" -> "damage-percent";
            case "damagemelee", "meleedamage" -> "damage-melee";
            case "healpercent" -> "heal-percent";
            case "setmaxhealth", "set-max-health" -> "set-max-health";
            case "modifymaxhealth", "modify-max-health" -> "modify-max-health";
            case "removeeffect" -> "remove-effect";
            case "removealleffects" -> "remove-all-effects";
            case "shootprojectile" -> "shoot-projectile";
            case "shoot", "projectile" -> "shoot-projectile";
            default -> name;
        };
    }
    private static List<String> split(String spec) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (quoted && c == '\\' && i + 1 < spec.length()
                    && (spec.charAt(i + 1) == '"' || spec.charAt(i + 1) == '\\')) {
                cur.append(spec.charAt(++i));
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(c) && !quoted) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** 读列表键，同时接受单条字符串。 */
    private static List<String> stringOrList(ConfigurationSection s, String key) {
        Object raw = s.get(key);
        if (raw instanceof String one) return List.of(one);
        List<String> l = s.getStringList(key);
        return l == null ? List.of() : l;
    }

    private List<String> conditionSpecs(ConfigurationSection section, String key) {
        Object raw = section.get(key);
        if (raw == null) return List.of();
        List<?> values = raw instanceof List<?> list ? list : List.of(raw);
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof String text && !text.isBlank()) result.add(text);
            else {
                warn("技能条件字段 \"" + key + "\" 包含空值或非字符串，已阻止执行");
                result.add("");
            }
        }
        return result;
    }

    private void warn(String msg) {
        warnings.add(msg);
        if (log != null) log.warning("[技能] " + msg);
    }
}
