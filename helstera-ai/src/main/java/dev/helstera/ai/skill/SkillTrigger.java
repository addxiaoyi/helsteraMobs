package dev.helstera.ai.skill;

import java.util.Locale;
import java.util.Map;

/**
 * 技能触发器种类。
 *
 * <p>此前触发器名只是 {@code AiProfile.triggers} 的裸字符串键，{@code SkillTriggers}
 * 内部用五个写死的 if 分支去对应。加入第 6 个触发器就要同时改解析、分发与调试输出三处，
 * 而漏改任一处的表现都是「配置写了但永不触发」且无任何报错——这类故障最难排查。
 * 收敛成枚举后，新增触发器只剩「加一个枚举值 + 注册分发器」两处可改。</p>
 *
 * <p>配置名容忍多种写法：{@code on-spawn} / {@code onSpawn} / {@code on_spawn} /
 * {@code spawn} 都指向同一触发器。迁移中心从 MythicMobs 导入时产出的是
 * {@code ~onSpawn} 风格，与手写配置的连字符风格并存。</p>
 */
public enum SkillTrigger {

    SPAWN("on-spawn", true),
    TIMER("on-timer", true),
    DAMAGE("on-damage", true),
    ATTACKED("on-attacked", true),
    DEATH("on-death", true),
    REMOVE("on-remove", true),
    STATE("on-state", true),
    INTERACT("on-interact", true),
    KILL_PLAYER("on-kill-player", true),
    // 由 EntityShootBowEvent 驱动。此前误判为「模型实例不会自己发射弹丸」而标 false，
    // 但载体的 entity.type 可以是 player —— 这类载体确实能射箭，事件可达，
    // 标 false 会让 /helstera check 劝退用户不要用一个可用机制。
    ENTITY_SHOOT("on-entity-shoot", true),
    // 由 ModelSpawnEvent 驱动，按档案 boss 字段判定（不按血量猜）
    SPAWN_BOSS("on-spawn-boss", true),
    CONDITION_MET("on-condition-met", true),
    CONDITION_LOST("on-condition-lost", true),
    // 以下五个没有独立事件来源：它们是 AI 状态的自变量（有无目标、目标是谁、
    // 血量是否越线），只能在采样节拍里靠「记住上次值 + 求差分」得到。
    // 边沿推导见 DerivedTriggerLatch，采样驱动见 SkillTriggers#pollDerived。
    ENTER_COMBAT("on-enter-combat", true),
    LEAVE_COMBAT("on-leave-combat", true),
    TARGET_CHANGE("on-target-change", true),
    LOWER_HEALTH("on-lower-health", true),
    LOST_TARGET("on-lost-target", true),
    ON_SIGNAL("on-signal", true),
    // 由 EntityPotionEffectEvent 驱动：ADDED 且为增益 -> on-buff，
    // REMOVED/CLEARED -> on-potion-effect-end（CHANGED 被刻意排除）
    BUFF("on-buff", true),
    POTION_EFFECT_END("on-potion-effect-end", true),
    // 由档案配置的 AABB 求差分得出（无对应 Bukkit 事件），与进出水同一机制
    ENTER_REGION("on-enter-region", true),
    LEAVE_REGION("on-leave-region", true),
    // 动画标记事件（AnimationMarkerEvent 的 attack_hit）一直存在，
    // 但此前只用来结算伤害，没有派发本触发器。现在桥接处已连上。
    ATTACK_HIT("on-attack-hit", true),
    // 同样没有事件来源（Bukkit 无「实体入水」事件），但与上面五个同源：
    // 由采样节拍对 isInWater 求差分即可，边沿推导复用 DerivedTriggerLatch。
    ENTER_WATER("on-enter-water", true),
    LEAVE_WATER("on-leave-water", true),
    // 由 WeatherChangeEvent 驱动；需在配置中开启（跨 Biome 时可按 world 限制）
    TOGGLE_WEATHER("on-toggle-weather", true),
    // Paper 无「生物成年」事件（TransformReason 里没有 AGED），
// 由采样 Ageable#isAdult() 求差分，属派生触发器路线，已接线。
    AGE("on-age", true),
    // 由 EntityTransformEvent 的 SHEARED 原因驱动
    SHEAR("on-shear", true),
    // 以下标记曾长期错误地写成 false，实际都已有派发点：
    // SUMMON 由 ModelSpawnEvent + MinionService.isMinion 驱动；LEASH 由
    // PlayerLeashEntityEvent 驱动（Paper 无 EntityLeashEvent）。
    // 标错的后果是 /helstera check 劝退用户不要用一个本来能用的机制。
    SUMMON("on-summon", true),
    LEASH("on-leash", true),
    PRE_TARGET("on-pre-target", true),
    ON_DAMAGE_NEGATION("on-damage-negation", true),
    ON_DEATH_SKILL("on-death-skill", true);

    /**
     * 是否已有真实的 Bukkit 事件/总线来源。
     *
     * <p>false 的触发器目前只在配置层可写：写进 mobs/*.yml 不会报错，但也不会触发。
     * 这类「已知未接线」的种类单列出来，好让 {@code /helstera check} 能提示
     * 「你写的 on-interact 暂未接线」而不是笼统说「未知名」。</p>
     */
    private final boolean wired;

    private final String configName;

    SkillTrigger(String configName, boolean wired) {
        this.configName = configName;
        this.wired = wired;
    }

    /** YAML 中的规范写法（连字符）。 */
    public String configName() {
        return configName;
    }

    /** 是否已接入真实事件来源。 */
    public boolean wired() {
        return wired;
    }

    /**
     * 全部「可写但不触发」的触发器配置名。
     *
     * <p>收口成唯一入口的原因：此前各处分���手工判断 {@code wired}，而手工标注
     * 必然会出错——本会话就出现过新增枚举值时把未接线者标成 {@code true}，
     * 导致 {@code /helstera check} 谎报「已支持」。改成从枚举集中推导后，
     * 忘记更新标记的后果由测试直接暴露，而非等到用户上服才发现。</p>
     *
     * @return 未接线触发器的规范配置名；全部已接线时返回空列表
     */
    public static java.util.List<String> unwiredNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SkillTrigger t : values()) {
            if (!t.wired) out.add(t.configName);
        }
        return java.util.List.copyOf(out);
    }

    /**
     * 给 check 命令用的接线状态文案。
     *
     * <p>调用方必须区分三态：解析成功且已接线 / 解析成功但未接线 / 名字根本不认识。
     * 三者若被混为一谈，用户会把「功能还没做」当成「自己拼错了名字」，
     * 前者要等插件更新，后者改个键名就能解决——混淆会让用户白等。</p>
     */
    public String wireHint() {
        return wired ? configName + " 已接线"
                     : configName + " 暂未接线：写进 mobs/*.yml 不会报错，但也不会触发";
    }

    /** 是否需要调度器主动驱动（目前只有 on-timer 需要独立周期任务）。 */
    public boolean needsTick() {
        return this == TIMER;
    }

    /** 去掉 on 前缀的短名，排查时更易读。 */
    public String shortName() {
        return configName.startsWith("on-") ? configName.substring(3) : configName;
    }

    /** 归一化：大小写与分隔符不敏感，用于查表。 */
    private static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '_' || c == ' ' || c == '~') continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 按多种写法解析触发器名；无法识别时返回 null。
     *
     * <p>容忍 {@code ~} 前缀：MythicMobs 的触发器写法是 {@code ~onSpawn:Skill}，
     * 导入器会原样带过来。</p>
     */
    public static SkillTrigger of(String name) {
        if (name == null) return null;
        String n = normalize(name);
        if (n.isEmpty()) return null;
        // 先按全名匹配（onspawn / onspawn / on-spawn）
        for (SkillTrigger t : values()) {
            if (normalize(t.configName).equals(n)) return t;
        }
        // 再按短名匹配（spawn / timer / damage）
        for (SkillTrigger t : values()) {
            if (normalize(t.shortName()).equals(n)) return t;
        }
        // "事件" 这类冗余前缀写法
        if (n.startsWith("event")) {
            for (SkillTrigger t : values()) {
                if (normalize(t.configName).equals("event" + normalize(t.configName))) return t;
            }
        }
        return null;
    }

    /** 归一化名 -> 枚举，避免每次解析都遍历数组。 */
    private static final Map<String, SkillTrigger> INDEX = buildIndex();

    private static Map<String, SkillTrigger> buildIndex() {
        var m = new java.util.LinkedHashMap<String, SkillTrigger>();
        for (SkillTrigger t : values()) {
            m.put(normalize(t.configName), t);
            m.put(normalize(t.shortName()), t);
            m.put(normalize("event" + t.configName), t);
            m.put(normalize(t.name().toLowerCase(Locale.ROOT)), t);
        }
        return Map.copyOf(m);
    }

    /** 供 {@code SkillTriggers} 按归一化名建索引；未知名返回 null。 */
    public static SkillTrigger byNormalized(String normalized) {
        return normalized == null ? null : INDEX.get(normalized);
    }

    /** 归一化工具，供外部解析配置键时保持与本枚举一致。 */
    public static String normalizeName(String s) {
        return normalize(s);
    }

    /** 全部规范配置名，供 {@code /helstera help} 与迁移报告列举。 */
    public static java.util.List<String> configNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SkillTrigger t : values()) out.add(t.configName);
        return java.util.List.copyOf(out);
    }
}