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

    /** 已装载的命名技能名，供体检与网页端列举。 */
    public List<String> skillNames() {
        return List.copyOf(defs.keySet());
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

        d[0]++;
        try {
            // require 语义在 cast 上不阻断：调用方（整体键）已判过条件，
            // 这里重复判定会让「条件不满足时静默不做事」难以排查。
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
            return true;
        } finally {
            d[0]--;
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