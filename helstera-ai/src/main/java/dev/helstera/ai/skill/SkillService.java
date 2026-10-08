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
    /** 冷却记录："实例id|技能名" -> 上次放行时间（epoch 毫秒）。 */
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    /** 活跃读条："实例id|技能名" -> 施法记录。 */
    private final Map<String, SkillCast> activeCasts = new ConcurrentHashMap<>();
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
    public record SkillCast(SkillDef def, long startMs, int instanceId) {
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

        QueuedSkill(SkillDef def, dev.helstera.api.behavior.BehaviorContext ctx) {
            this.def = def;
            this.ctx = ctx;
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
        boolean allPass = true;
        for (String condKey : def.require) {
            boolean ok = registry.testCondition(condKey, ctx);
            trace.add(new CondTrace(condKey, ok));
            if (!ok) allPass = false;
        }
        boolean cooling = def.cooldownMillis > 0
                && !cooldownReady(def, cooldownKey(def, ctx), System.currentTimeMillis());
        return new Preview(def.name, List.copyOf(trace), allPass,
                List.copyOf(def.actions), cooling, def.priority);
    }

    /** 绑定一条条件定义，返回可写入 profile 的键；失败返回 null。 */
    public String bindCondition(String spec) {
        if (spec == null || spec.isBlank()) return null;
        List<String> parts = split(spec);
        String name = parts.get(0).toLowerCase(Locale.ROOT);
        List<String> args = parts.subList(1, parts.size());
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
                warn("条件 \"" + key + "\" 参数非法: " + e.getMessage());
                return null;
            }
        }
        return key;
    }

    /** 绑定一条动作定义，返回可写入 profile 的键；失败返回 null。 */
    public String bindAction(String spec) {
        if (spec == null || spec.isBlank()) return null;
        List<String> parts = split(spec);
        String name = parts.get(0).toLowerCase(Locale.ROOT);
        List<String> args = parts.subList(1, parts.size());

        // 嵌套技能：cast-skill X 与 skill{s=X} 走专用绑定，不查动作目录
        String ref = parseSkillRef(spec);
        if (ref != null) return bindCast(ref);

        SkillCatalog.ActionFactory f = actionFactories.get(name);
        if (f == null) {
            if (!registry.hasAction(name)) {
                warn("未知动作 \"" + name + "\"（可用内置: " + actionFactories.keySet() + "）");
            }
            return registry.hasAction(name) ? name : null;
        }
        String key = name + (args.isEmpty() ? "" : ":" + String.join(" ", args));
        if (bound.add("a:" + key)) {
            try {
                registry.registerAction(key, f.create(args));
            } catch (RuntimeException e) {
                warn("动作 \"" + key + "\" 参数非法: " + e.getMessage());
                return null;
            }
        }
        return key;
    }

    /**
     * 装载命名技能。
     *
     * <p>顺序：先解析全部技能与依赖关系并做环检测，再逐个绑定内部条件/动作，
     * 最后注册 {@code skill:名字} 整体键。反过来做会让
     * 「A 调 B、B 调 A」在绑定期就找不到对方的键。</p>
     */
    public void loadSkills(ConfigurationSection root) {
        defs.clear();
        cooldowns.clear();
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
        for (String skillName : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(skillName);
            if (s == null) continue;
            String key = skillName.toLowerCase(Locale.ROOT);
            SkillDef def = new SkillDef(key);
            def.cooldownMillis = parseCooldownMillis(s.get("cooldown"));
            def.castMillis = parseCooldownMillis(s.get("cast-duration"));
            if (s.contains("cast-label")) def.castLabel = s.getString("cast-label", "施法中");
            if (s.contains("priority")) def.priority = (int) s.get("priority");
            for (String spec : stringOrList(s, "require")) {
                if (spec == null || spec.isBlank()) continue;
                String dep = parseSkillRef(spec);
                if (dep != null) def.deps.add(dep);
            }
            for (String spec : stringOrList(s, "on-decision")) {
                if (spec == null || spec.isBlank()) continue;
                String dep = parseSkillRef(spec);
                if (dep != null) def.deps.add(dep);
            }
            defs.put(key, def);
        }
        if (defs.isEmpty()) return;

        // 2) 环检测
        detectCycles();

        // 3) 绑定内部条件/动作
        for (SkillDef def : defs.values()) {
            ConfigurationSection s = root.getConfigurationSection(def.name);
            if (s == null) continue;
            for (String spec : stringOrList(s, "require")) {
                String k = bindCondition(spec);
                if (k != null) def.require.add(k);
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
                    for (String k : captured) {
                        if (!registry.testCondition(k, ctx)) return false;
                    }
                    return true;
                });
            }
            if (bound.add("a:" + key)) {
                List<String> captured = List.copyOf(def.actions);
                registry.registerAction(key, ctx -> {
                    for (String k : captured) registry.runAction(k, ctx);
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

        int[] d = depth.get();
        if (d[0] >= MAX_DEPTH) {
            warn("技能 \"" + def.name + "\" 递归超过 " + MAX_DEPTH + " 层，已中止");
            return false;
        }

        long now = System.currentTimeMillis();
        String cdKey = cooldownKey(def, ctx);
        if (!cooldownReady(def, cdKey, now)) return false;
        if (def.cooldownMillis > 0) cooldowns.put(cdKey, now);

        // 队列门控：同一实例有技能正在执行时入队
        int instId = ctx != null && ctx.instance() != null ? ctx.instance().instanceId() : -1;
        if (instId >= 0 && isInstanceBusy(instId)) {
            enqueueSkill(instId, def, ctx);
            return true;
        }

        d[0]++;
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
    private boolean isInstanceBusy(int instId) {
        // 读条完成判定：activeCasts 里有无该实例的活跃读条
        boolean hasActiveCast = activeCasts.values().stream().anyMatch(c -> c.instanceId() == instId && !c.isComplete());
        if (hasActiveCast) return true;
        // 已有排队任务
        SkillQueueEntry q = skillQueue.get(instId);
        return q != null && !q.items.isEmpty();
    }

    /** 把技能请求按优先级插入排序到实例队列中。 */
    private void enqueueSkill(int instId, SkillDef def, dev.helstera.api.behavior.BehaviorContext ctx) {
        skillQueue.compute(instId, (k, q) -> {
            if (q == null) q = new SkillQueueEntry();
            q.items.add(new QueuedSkill(def, ctx));
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
                int[] d = depth.get();
                d[0]++;
                try {
                    executeSkillActions(qs.def, qs.ctx);
                } finally {
                    d[0]--;
                }
                // 继续处理下一个
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
        for (String k : def.actions) {
            try {
                registry.runAction(k, ctx);
            } catch (Throwable t) {
                warn("技能 \"" + def.name + "\" 动作 \"" + k + "\" 失败: " + t);
            }
        }
        // 启动读条：有 cast-duration 时计时，完成后自动清除
        if (def.castMillis > 0) {
            int instId = ctx.instance() != null ? ctx.instance().instanceId() : -1;
            String castKey = instId + "|" + def.name;
            activeCasts.put(castKey, new SkillCast(def, System.currentTimeMillis(), instId));
        }
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
        int instId = ctx.instance() != null ? ctx.instance().instanceId() : -1;
        String castKey = instId + "|" + def.name;
        activeCasts.put(castKey, new SkillCast(def, System.currentTimeMillis(), instId));
        return true;
    }

    /**
     * 取消指定实例的活跃读条。
     *
     * @return 是否成功取消
     */
    public boolean cancelCast(int instId) {
        return activeCasts.values().removeIf(c -> c.instanceId() == instId);
    }

    /**
     * 清除某个实例的所有活跃读条（通常在死亡或 despawn 时调用）。
     */
    public void clearAllCastsFor(int instId) {
        activeCasts.values().removeIf(c -> c.instanceId() == instId);
    }

    /**
     * 清除指定实例的技能队列与处理标记。
     */
    public void clearQueueFor(int instId) {
        skillQueue.remove(instId);
        queueProcessing.remove(instId);
    }

    /**
     * 获取实例当前读条进度；无读条时返回 null。
     *
     * @return Cast 读条数据（label + progress）；无读条返回 null
     */
    public dev.helstera.ai.bossbar.BossBarState.Cast getCastProgress(int instId) {
        for (var c : activeCasts.values()) {
            if (c.instanceId() == instId) {
                if (c.isComplete()) {
                    activeCasts.remove(c.instanceId() + "|" + c.def().name);
                    return null;
                }
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
        return id + "|" + def.name;
    }

    private boolean cooldownReady(SkillDef def, String key, long now) {
        if (def.cooldownMillis <= 0) return true;
        Long prev = cooldowns.get(key);
        return prev == null || (now - prev) >= def.cooldownMillis;
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
        if (s.regionMatches(true, 0, "cast-skill", 0, "cast-skill".length())) {
            String rest = s.substring("cast-skill".length()).trim();
            return rest.isEmpty() ? null : rest;
        }
        int brace = s.indexOf('{');
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
    private static List<String> split(String spec) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
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

    private void warn(String msg) {
        warnings.add(msg);
        if (log != null) log.warning("[技能] " + msg);
    }
}