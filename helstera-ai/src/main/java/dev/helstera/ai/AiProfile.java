package dev.helstera.ai;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 行为档案（ai.yml profiles 节）。
 *
 * <p>{@code require} / {@code onDecision} 是决策链扩展点：前者是自定义条件名列表
 * （全部为 true 才执行内置决策），后者是每次决策后执行的自定义动作名列表。
 * 两者都经 {@code BehaviorRegistry} 解析，使第三方插件无需改动 AI 内核即可扩展行为。</p>
 */
public final class AiProfile {

    public final String name;
    public double sightRadius = 16;
    public double attackRadius = 2.0;
    public double attackDamage = 3.0;
    public double attackCooldown = 1.2;
    public double fleeHealthRatio = 0.3;
    public double patrolRadius = 8;
    public double patrolInterval = 4.0;
    public double moveSpeed = 0.28;
    public boolean canChase = true;
    public boolean canFlee = false;
    public boolean canPatrol = true;
    public boolean canAttack = true;

    /**
     * 本档案所属阵营名；null 表示不归队（与所有阵营敌对）。
     *
     * <p>取自配置键 {@code faction}。阵营影响三处行为：目标选择跳过同伙、
     * 同伙之间免伤、命令可查询。名称在加载期不校验是否存在——跨文件的引用
     * （mobs/*.yml 的档案覆盖）在加载顺序上可能先于 factions 段完成，
     * 提前判错会把合法配置误判为坏配置。真正的校验在 {@code /helstera check}。</p>
     */
    public String faction;

    // ---- 仇恨表 ----

    /** 是否启用多目标仇恨排序。关闭时退回「最近者」的单目标行为。 */
    public boolean threatEnabled = false;

    /**
     * 是否启用 A* 寻路。默认<b>关闭</b>：存量配置不改一行也不该行为突变，
     * 而寻路的通行判定依赖世界地形，无法在无世界的情况下验证。
     */
    public boolean canPathfind = false;
    /**
     * 单次寻路的展开节点预算；<=0 时由 {@code PathFollower} 退回默认值 256。
     *
     * <p>调大会让长距离路径更可能找到，代价是单帧最坏开销上升。调小则更容易
     * 触发「预算超限」并退化为直连——{@code /helstera nav} 的失败率会先涨。</p>
     */
    public int pathBudget = 256;
    /**
     * 档案区域（AABB），供 on-enter-region / on-leave-region 求差分；null 表示未配置。
     * 支持小数坐标——生物移动是连续的，按格点判定会在边界反复进出。
     */
    public dev.helstera.ai.skill.RegionBox region;
    /**
     * 是否 Boss 档案，决定 on-spawn-boss 是否触发。
     * 刻意用显式字段而非血量阈值：阈值是猜的，且不同档案血量体系不可比。
     */
    public boolean boss = false;
    /** 威胁每秒衰减比例 0..1；0 = 不衰减。 */
    public double threatDecayPerSecond = 0.05;
    /**
     * 距离权重衰减尺度（格）。越大则远端输出贡献的仇恨越低。
     * 0 表示距离不影响权重。
     */
    public double threatDistanceWeight = 24.0;

    /**
     * 免疫与伤害倍率配置行（{@code immunities} + {@code damage-modifiers}）。
     *
     * <p>保留<b>原始行</b>而非编译后的表：档案可被 mobs/*.yml 就地覆盖，
     * 每次覆盖都要重新编译，缓存的表会与配置不同步。而伤害事件在热路径上，
     * 所以编译结果另存于 {@link #immunityTable}，由失效动作重建。</p>
     */
    public dev.helstera.ai.immunity.ImmunityService.Config immunity =
            dev.helstera.ai.immunity.ImmunityService.Config.empty();

    /** Boss 血条配置；null 表示未配置。 */
    public dev.helstera.ai.bossbar.BossBarConfig.Parsed bossBar;

    /** 编译后的规则表；null 表示未配置。不可变，可安全跨事件复用。 */
    private transient dev.helstera.ai.immunity.ImmunityService.Table immunityTable;

    /** 前置自定义条件（全部为 true 才执行内置决策）；为空表示不限制。 */
    public final List<String> require = new ArrayList<>();
    /** 每次决策后执行的自定义动作。 */
    public final List<String> onDecision = new ArrayList<>();
    /** 事件驱动触发器：事件名 -> 规格。 */
    public final Map<String, TriggerSpec> triggers = new LinkedHashMap<>();
    /** Boss 血量阶段；为空表示该档案未启用分阶段。 */
    public final List<BossPhase> phases = new ArrayList<>();

    /**
     * 等级缩放配置：每个等级提升时哪些属性按比例增长。
     *
     * <p>为空表示该档案无等级系统；配置形如：
     * {@code levels: [{property: health, base: 1000, growthPerLevel: 1.05}]}</p>
     */
    public final List<dev.helstera.ai.level.MobLevel.ScalingConfig> levels = new ArrayList<>();

    /** 当前等级；1 表示基础档，不配置时保持 1 且不触发缩放。 */
    public int level = 1;

    /**
     * 单个事件触发器：{@code require} 全部满足时才执行 {@code actions}。
     *
     * <p>事件名取值见 {@link dev.helstera.ai.skill.SkillTrigger}：on-spawn / on-timer /
     * on-damage / on-attacked / on-death / on-remove / on-state 等。</p>
     */
    public static final class TriggerSpec {
        public final List<String> require = new ArrayList<>();
        public final List<String> actions = new ArrayList<>();
        /**
         * 触发周期（tick）。仅 {@code on-timer} 使用；其余触发器由事件驱动，字段留 0。
         *
         * <p>不能沿用「事件来了就跑」：定时技能必须有自己的节奏，
         * 而这个节奏要能被配置作者按技能单独调整（同一个档案里可能有
         * 一个 1 tick 的脉冲和一个 200 tick 的光环）。</p>
         */
        public int intervalTicks;
        /** 首次触发前的延迟（tick）。0 表示与 interval 同延。 */
        public int startDelayTicks;
        /**
         * {@code on-lower-health} 的血量阈值（百分比 0..100）。
         *
         * <p>该触发器必须带一个阈值，否则「血量变低」本身没有含义——
         * 而 {@code require} 里的条件是在边沿<b>之后</b>才求值的，拿它当阈值会
         * 造成「必须已经低于阈值才触发低于阈值」的循环依赖。故单列此字段。</p>
         */
        public double lowerHealthPercent = 50;
        /**
         * 是否要求本次调度跑在技能链之外（对应 MythicMobs 的 sync）。
         * 留作占位，目前调度器按实例串行执行，sync 与否行为一致。
         */
        public boolean sync;

        /** 实际生效的首次延迟：未显式配置时等于周期。 */
        public int effectiveStartDelay() {
            return startDelayTicks > 0 ? startDelayTicks : intervalTicks;
        }
    }

    public AiProfile(String name) {
        this.name = name;
    }

    /**
     * 复制构造：用于派生「基于某档案的局部覆盖」实例，避免改动共享缓存档案。
     * 标量字段、require / onDecision 列表与事件触发器一并复制。
     */
    public AiProfile(AiProfile base) {
        this.name = base.name;
        this.sightRadius = base.sightRadius;
        this.attackRadius = base.attackRadius;
        this.attackDamage = base.attackDamage;
        this.attackCooldown = base.attackCooldown;
        this.fleeHealthRatio = base.fleeHealthRatio;
        this.patrolRadius = base.patrolRadius;
        this.patrolInterval = base.patrolInterval;
        this.moveSpeed = base.moveSpeed;
        this.canChase = base.canChase;
        this.canFlee = base.canFlee;
        this.canPatrol = base.canPatrol;
        this.canAttack = base.canAttack;
        this.faction = base.faction;
        this.immunity = base.immunity;
        this.immunityTable = base.immunityTable;
        this.threatEnabled = base.threatEnabled;
        this.canPathfind = base.canPathfind;
        this.pathBudget = base.pathBudget;
        this.threatDecayPerSecond = base.threatDecayPerSecond;
        this.threatDistanceWeight = base.threatDistanceWeight;
        this.require.addAll(base.require);
        this.onDecision.addAll(base.onDecision);
        this.phases.addAll(base.phases);
        for (var e : base.triggers.entrySet()) {
            TriggerSpec src = e.getValue();
            TriggerSpec dst = new TriggerSpec();
            dst.require.addAll(src.require);
            dst.actions.addAll(src.actions);
            dst.intervalTicks = src.intervalTicks;
            dst.startDelayTicks = src.startDelayTicks;
            dst.lowerHealthPercent = src.lowerHealthPercent;
            dst.sync = src.sync;
            this.triggers.put(e.getKey(), dst);
        }
    }

    public static AiProfile fromSection(String name, ConfigurationSection s) {
        AiProfile p = new AiProfile(name);
        if (s == null) return p;
        p.sightRadius = s.getDouble("sight-radius", p.sightRadius);
        p.attackRadius = s.getDouble("attack-radius", p.attackRadius);
        p.attackDamage = s.getDouble("attack-damage", p.attackDamage);
        p.attackCooldown = s.getDouble("attack-cooldown", p.attackCooldown);
        p.fleeHealthRatio = s.getDouble("flee-health-ratio", p.fleeHealthRatio);
        p.patrolRadius = s.getDouble("patrol-radius", p.patrolRadius);
        p.patrolInterval = s.getDouble("patrol-interval", p.patrolInterval);
        p.moveSpeed = s.getDouble("move-speed", p.moveSpeed);
        p.canChase = s.getBoolean("can-chase", p.canChase);
        p.canFlee = s.getBoolean("can-flee", p.canFlee);
        p.canPatrol = s.getBoolean("can-patrol", p.canPatrol);
        p.canAttack = s.getBoolean("can-attack", p.canAttack);
        p.faction = trimToNull(s.getString("faction"));
        p.immunity = dev.helstera.ai.immunity.ImmunityService.rowsFromSection(s);
        p.bossBar = dev.helstera.ai.bossbar.BossBarConfig.parse(
                s.getConfigurationSection("bossbar"), new ArrayList<>());
        p.threatEnabled = s.getBoolean("threat-enabled", p.threatEnabled);
        p.canPathfind = s.getBoolean("can-pathfind", p.canPathfind);
        p.pathBudget = Math.max(1, Math.min(8192, s.getInt("path-budget", p.pathBudget)));
        p.threatDecayPerSecond = s.getDouble("threat-decay", p.threatDecayPerSecond);
        p.threatDistanceWeight = s.getDouble("threat-distance-weight", p.threatDistanceWeight);
        p.require.clear();
        p.require.addAll(s.getStringList("require"));
        p.onDecision.clear();
        p.onDecision.addAll(s.getStringList("on-decision"));
        p.triggers.clear();
        p.applyTriggersFrom(s);
        p.phases.clear();
        p.phases.addAll(BossPhase.parseList(s, new ArrayList<>()));
        p.levels.clear();
        p.levels.addAll(parseLevels(s));
        p.level = Math.max(1, s.getInt("level", p.level));
        return p;
    }

    /**
     * 用配置节的 {@code triggers} 节覆盖本档案的触发器（整体替换，不合并）。
     *
     * <p>{@link #fromSection} 与生物级档案覆盖（mobs/*.yml 的 ai 节）都要读这一段，
     * 此前只有前者解析，导致 mobs/*.yml 里写的 triggers 被静默忽略、
     * on-spawn/on-damage 永不触发。</p>
     */
    public void applyTriggersFrom(ConfigurationSection s) {
        org.bukkit.configuration.ConfigurationSection trSec = s.getConfigurationSection("triggers");
        if (trSec == null) return;
        for (String event : trSec.getKeys(false)) {
            var evSec = trSec.getConfigurationSection(event);
            if (evSec == null) continue;
            TriggerSpec spec = new TriggerSpec();
            spec.require.addAll(evSec.getStringList("require"));
            spec.actions.addAll(evSec.getStringList("do"));
            // 周期字段只对 on-timer 有意义，但这里不做条件判断：
            // 让写在 on-damage 下的 interval 也照读，写错时由 /helstera check 提示，
            // 而不是在加载期静默丢弃一个作者认为生效了的参数。
            spec.intervalTicks = readTicks(evSec.get("interval"), 20);
            spec.startDelayTicks = readTicks(evSec.get("start-delay"), 0);
            // on-lower-health 的阈值单列：读百分数（50 / 50%），缺省 50。
            // 越界值按 0..100 夹取而不是丢弃——写了 200 的作者本意是「永不触发」，
            // 而静默丢弃会让他以为阈值生效了。
            spec.lowerHealthPercent = readPercent(evSec.get("health-below"),
                    spec.lowerHealthPercent);
            spec.sync = evSec.getBoolean("sync", false);
            this.triggers.put(event.toLowerCase(java.util.Locale.ROOT), spec);
        }
    }

    /**
     * 读周期值，接受 {@code 1s} / {@code 200t} / 裸数字（tick）。
     *
     * <p>作者写「6s」几乎总是想表达 6 秒，而把它当 6 tick 意味着每 0.3 秒触发一次——
     * 表现为技能疯狂刷屏，且没有任何报错。</p>
     */
    static int readTicks(Object raw, int def) {
        if (raw == null) return def;
        if (raw instanceof Number n) return Math.max(1, n.intValue());
        String s = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return def;
        try {
            if (s.endsWith("t") || s.endsWith("tick") || s.endsWith("ticks")) {
                return Math.max(1, Integer.parseInt(s.replaceAll("(t|ticks?)$", "").trim()));
            }
            if (s.endsWith("s") || s.endsWith("sec") || s.endsWith("secs")) {
                return Math.max(1, Integer.parseInt(s.replaceAll("(s|sec|secs?)$", "").trim()) * 20);
            }
            if (s.endsWith("m") || s.endsWith("min") || s.endsWith("mins")) {
                return Math.max(1, Integer.parseInt(s.replaceAll("(m|min|mins?)$", "").trim()) * 1200);
            }
            return Math.max(1, Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 归一化配置里的字符串键：去空白，空白视为「未设置」。
     *
     * <p>YAML 里写 {@code faction: ""} 或 {@code faction: "  "} 都不该被当作
     * 一个名为空串的阵营——那会让 {@code allied} 把所有空串归为一伙，出现
     * 「两个都没配置阵营的生物互相免伤」这种完全反直觉的结果。</p>
     */
    static String trimToNull(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 读百分比值，接受 {@code 50} / {@code 50%} / {@code 0.5}。
     *
     * <p>小数形式按比例解读：0.5 与 50 等价。作者写 0.5 想表达的一定是
     * 「一半血」，当成 0.5% 会让 on-lower-health 在开局第一 tick 就触发，
     * 且没有任何报错。</p>
     *
     * @param raw 配置原始值
     * @param def 缺省值；raw 缺失或不可解析时使用
     * @return 0..100 区间的百分比
     */
    static double readPercent(Object raw, double def) {
        if (raw == null) return def;
        String s = raw instanceof Number
                ? String.valueOf(((Number) raw).doubleValue())
                : String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return def;
        boolean pct = s.endsWith("%");
        if (pct) s = s.substring(0, s.length() - 1).trim();
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
        // 无百分号且落在 (0,1] 区间：按比例解读。写成 0.5 的作者本意是「一半血」，
        // 当成 0.5% 会让 on-lower-health 在开局第一 tick 就触发，且无任何报错。
        if (!pct && v > 0 && v <= 1.0) v *= 100.0;
        return Math.max(0, Math.min(100, v));
    }

    /**
     * 用 mobs/*.yml 的 {@code ai} 节覆盖本档案的全部可覆盖项。
     *
     * <p>抽成这里而不是留在插件层，是因为这段逻辑此前只有一半被接线：
     * 标量与 {@code triggers} 有覆盖，而 {@code require} / {@code on-decision} /
     * {@code phases} 三项从未被读取——mobs/*.yml 里写 Boss 阶段会被静默忽略，
     * 配置越详细越容易踩。放进 {@code AiProfile} 才能被单元测试覆盖。</p>
     *
     * <p>列表类项（require / on-decision / triggers / phases）一律整体替换而非合并：
     * 生物级配置的意图是「这个生物的行为是另一种」，叠加会让「清空某项」
     * 无法表达。</p>
     */
    public void applyOverridesFrom(ConfigurationSection s) {
        if (s == null) return;
        sightRadius = s.getDouble("sight-radius", sightRadius);
        attackRadius = s.getDouble("attack-radius", attackRadius);
        attackDamage = s.getDouble("attack-damage", attackDamage);
        attackCooldown = s.getDouble("attack-cooldown", attackCooldown);
        fleeHealthRatio = s.getDouble("flee-health-ratio", fleeHealthRatio);
        patrolRadius = s.getDouble("patrol-radius", patrolRadius);
        patrolInterval = s.getDouble("patrol-interval", patrolInterval);
        moveSpeed = s.getDouble("move-speed", moveSpeed);
        canChase = s.getBoolean("can-chase", canChase);
        canFlee = s.getBoolean("can-flee", canFlee);
        canPatrol = s.getBoolean("can-patrol", canPatrol);
        canAttack = s.getBoolean("can-attack", canAttack);
        faction = trimToNull(s.getString("faction"));
        threatEnabled = s.getBoolean("threat-enabled", threatEnabled);
        canPathfind = s.getBoolean("can-pathfind", canPathfind);
        pathBudget = Math.max(1, Math.min(8192, s.getInt("path-budget", pathBudget)));
        this.region = dev.helstera.ai.skill.RegionBox.parse(
                s.getConfigurationSection("region"));
        this.boss = s.getBoolean("boss", boss);
        threatDecayPerSecond = s.getDouble("threat-decay", threatDecayPerSecond);
        threatDistanceWeight = s.getDouble("threat-distance-weight", threatDistanceWeight);

        if (s.contains("require")) {
            require.clear();
            require.addAll(stringOrList(s, "require"));
        }
        if (s.contains("on-decision")) {
            onDecision.clear();
            onDecision.addAll(stringOrList(s, "on-decision"));
        }
        if (s.isConfigurationSection("triggers")) {
            triggers.clear();
            applyTriggersFrom(s);
        }
        if (s.contains("phases")) {
            phases.clear();
            phases.addAll(BossPhase.parseList(s, new ArrayList<>()));
        }
        if (s.contains("levels")) {
            levels.clear();
            levels.addAll(parseLevels(s));
        }
        level = Math.max(1, s.getInt("level", level));
        if (s.contains("immunities") || s.contains("damage-modifiers")) {
            // 整体替换而非合并：与 require / phases 同理，「清空某项」必须能表达。
            // 免疫漏配的表现是「怪物打不动」，合并语义会让作者写一条就悄悄继承档案的全部免疫。
            immunity = dev.helstera.ai.immunity.ImmunityService.rowsFromSection(s);
            immunityTable = null;
        }
        if (s.contains("bossbar")) {
            bossBar = dev.helstera.ai.bossbar.BossBarConfig.parse(
                    s.getConfigurationSection("bossbar"), new ArrayList<>());
        }
    }

    /**
     * 编译后的免疫/倍率规则表；未配置任何规则时返回空表（不改任何伤害）。
     *
     * <p>惰性编译并缓存：档案可被 mobs/*.yml 就地覆盖，而每次覆盖都重编译会让
     * 伤害事件（热路径）在同一 tick 内反复编译同一份规则。</p>
     */
    public dev.helstera.ai.immunity.ImmunityService.Table immunityTable() {
        if (immunityTable == null) {
            immunityTable = dev.helstera.ai.immunity.ImmunityService.compile(immunity);
        }
        return immunityTable;
    }

    /** 免疫/倍率的装载期告警（未知名 / 参数非法）；供 {@code /helstera check} 展示。 */
    public java.util.List<String> immunityWarnings() {
        return immunityTable().warnings();
    }

    /**
     * 当前等级对应的缩放结果。
     *
     * <p>等级未配置（{@code levels} 为空）时返回 {@link dev.helstera.ai.level.MobLevel#empty()}，
     * 调用方拿到后直接取属性值即可，不需要再做额外的缩放计算。</p>
     */
    public dev.helstera.ai.level.MobLevel.LevelResult levelResult() {
        return dev.helstera.ai.level.MobLevel.compute(level, levels.toArray(new dev.helstera.ai.level.MobLevel.ScalingConfig[0]));
    }

    /**
     * 读列表键，同时接受单条字符串。
     *
     * <p>只写一条时 YAML/Bukkit 会退化成字符串，此时 {@code getStringList}
     * 返回空列表——配置写了却不生效，且没有任何报错。用 {@code contains} 判断
     * 存在性再兼容两种形态，才能让「写一条」与「写列表」等价。</p>
     */
    private static List<String> stringOrList(ConfigurationSection s, String key) {
        Object raw = s.get(key);
        if (raw instanceof String one) return List.of(one);
        return s.getStringList(key);
    }

    /**
     * 解析等级缩放配置列表。
     *
     * <p>接受「列表项是映射」的标准 YAML 形态，也兼容「只写一条时退化成单个映射」
     * 的 Bukkit 退化行为——否则作者写单条等级配置时会静默失效。</p>
     */
    private static List<dev.helstera.ai.level.MobLevel.ScalingConfig> parseLevels(ConfigurationSection s) {
        List<dev.helstera.ai.level.MobLevel.ScalingConfig> out = new ArrayList<>();
        Object raw = s.get("levels");
        if (raw == null) return out;
        List<?> items;
        if (raw instanceof List<?> l) {
            items = l;
        } else if (raw instanceof Map<?, ?>) {
            items = List.of(raw);
        } else {
            return out;
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) continue;
            String property = map.get("property") == null ? null : String.valueOf(map.get("property")).trim().toLowerCase();
            double base = map.get("base") instanceof Number n ? n.doubleValue() : 0;
            double growth = map.get("growthPerLevel") instanceof Number n ? n.doubleValue() : 1.0;
            if (property.isEmpty()) continue;
            out.add(new dev.helstera.ai.level.MobLevel.ScalingConfig(property, base, growth));
        }
        return out;
    }
}
