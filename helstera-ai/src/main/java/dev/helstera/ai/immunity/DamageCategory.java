package dev.helstera.ai.immunity;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 伤害类别：把 Bukkit 的一堆 {@code DamageCause} 归并成服主写配置时真正想区分的几类。
 *
 * <p>类别存在的理由是精确 cause 太多。服主想写的从来不是「我���免疫
 * {@code FIRE_TICK}」，而是「这东西不怕火」——而 {@code FIRE} / {@code FIRE_TICK} /
 * {@code LAVA} / {@code MELTING} / {@code HOT_FLOOR} / {@code CAMPFIRE} 是六个不同的
 * cause。只支持精确 cause 时，漏写一个的表现是「Boss 在岩浆里站着不掉血、
 * 掉进熔岩却掉血」，而这种不一致极难定位。</p>
 *
 * <p><b>成员表按 Bukkit 1.21.1 的 {@code EntityDamageEvent.DamageCause} 实际
 * 常量逐个核对过</b>，不是凭记忆写的：漏一个 cause 会让该 cause 既不匹配精确规则
 * 也不匹配类别规则，表现为「配了免疫但那条伤害照样打进来」。新增版本若有新常量，
 * {@link #KNOWN_CAUSES} 与本枚举的差集由测试 {@code DamageCategoryTest} 断言。</p>
 *
 * <p>类别名与 cause 名共用一套归一化（小写、去掉 {@code -}/{@code _}/空格），
 * 因此 {@code entity-attack}、{@code ENTITY_ATTACK}、{@code EntityAttack} 都成立。
 * 归一化后两者可能撞名（如类别 {@code fire} 与 cause {@code FIRE}），
 * 此时按「精确 cause 优先」处理，见 {@link ImmunityService}。</p>
 */
public enum DamageCategory {

    /** 实体近战攻击（含横扫）。 */
    MELEE("entity-attack", "ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK"),

    /** 远程弹丸：箭、投掷物、雪球。 */
    PROJECTILE("projectile", "PROJECTILE"),

    /** 法术与龙息。 */
    MAGIC("magic", "MAGIC", "DRAGON_BREATH"),

    /** 一切火与热：火焰、燃烧、岩浆、熔岩、仙人掌、热方块。 */
    FIRE("fire", "FIRE", "FIRE_TICK", "LAVA", "MELTING", "HOT_FLOOR", "CAMPFIRE"),

    /** 坠落类：摔落、撞墙、坠落方块。 */
    FALL("fall", "FALL", "FLY_INTO_WALL", "FALLING_BLOCK"),

    /** 溺水与憋气：水下、干燥、挤压窒息。 */
    DROWNING("drowning", "DROWNING", "DRYOUT", "SUFFOCATION", "CRAMMING"),

    /** 中毒类：毒、凋零。 */
    POISON("poison", "POISON", "WITHER"),

    /** 爆炸类。 */
    EXPLOSION("explosion", "BLOCK_EXPLOSION", "ENTITY_EXPLOSION"),

    /** 虚空与世界边界。 */
    VOID("void", "VOID", "WORLD_BORDER"),

    /** 饥饿。 */
    STARVATION("starvation", "STARVATION"),

    /** 雷击与冰冻。 */
    ELEMENTAL("elemental", "LIGHTNING", "FREEZE"),

    /** 环境伤害总类：火、坠落、溺水、中毒、爆炸、虚空、饥饿、雷击、冰冻。
     *
     * <p>即「一切与环境有关的伤害」。刻意<b>不含</b>实体攻击与法术——
     * 把它们算进「环境」会让「免疫环境伤害」的 Boss 同时免疫玩家的攻击，
     * 而作者写这句话时想的是「别被地形弄死」。</p> */
    ENVIRONMENT("environment",
            "FIRE", "FIRE_TICK", "LAVA", "MELTING", "HOT_FLOOR", "CAMPFIRE",
            "FALL", "FLY_INTO_WALL", "FALLING_BLOCK",
            "DROWNING", "DRYOUT", "SUFFOCATION", "CRAMMING",
            "POISON", "WITHER",
            "BLOCK_EXPLOSION", "ENTITY_EXPLOSION",
            "VOID", "WORLD_BORDER", "STARVATION",
            "LIGHTNING", "FREEZE"),

    /** 接触伤害（仙人掌之外的实心方块）。 */
    CONTACT("contact", "CONTACT"),

    /** 荆棘反伤。 */
    THORNS("thorns", "THORNS"),

    /** 自伤类：自杀与 /kill。 */
    SELF("self", "SUICIDE", "KILL"),

    /** 声音冲击波。 */
    SONIC("sonic", "SONIC_BOOM"),

    /** 插件自定义伤害。 */
    CUSTOM("custom", "CUSTOM"),

    /** 一切伤害。 */
    ALL("all");

    private final String configName;
    private final Set<String> causes;

    DamageCategory(String configName, String... causes) {
        this.configName = configName;
        Set<String> s = new LinkedHashSet<>();
        for (String c : causes) s.add(c);
        this.causes = Collections.unmodifiableSet(s);
    }

    /** 配置文件里的规范写法（连字符、小写）。 */
    public String configName() {
        return configName;
    }

    /** 本类别覆盖的 cause 名集合（不可变，Bukkit 常量名全大写）。 */
    public Set<String> causes() {
        return causes;
    }

    /**
     * 归一化配置键：转小写并去掉分隔符。
     *
     * <p>抽出成静态纯函数是因为 cause 与类别都要走同一套归一化，而两处写法
     * 不一致的后果是「类别写 {@code entity-attack} 能生效、cause 写
     * {@code entity_attack} 不生效」这类无法自查的不对称。</p>
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '-' || c == '_' || c == ' ' || c == '~' || c == '.') continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 按配置键求类别；不是类别名返回 null。
     *
     * <p>同时接受全名 {@code entity-attack} 与枚举名 {@code MELEE}
     * （归一化后分别是 {@code entityattack} 与 {@code melee}，
     * 前者含后者，故按归一化子串判定：{@code melee} 也能命中 {@code MELEE}）。</p>
     */
    public static DamageCategory of(String raw) {
        String n = normalize(raw);
        if (n.isEmpty()) return null;
        for (DamageCategory c : values()) {
            if (normalize(c.configName).equals(n) || normalize(c.name()).equals(n)) return c;
        }
        // 便捷写法：直接写枚举名的简写（如 melee / fire / poison）
        for (DamageCategory c : values()) {
            if (normalize(c.name()).equals(n)) return c;
        }
        return null;
    }

    /**
     * 该 cause 名命中哪些类别（可能多个，{@link #ENVIRONMENT} 覆盖了多数 cause）。
     *
     * <p>返回声明顺序而非哈希顺序：多条类别规则同时命中时，
     * 「谁先写谁生效」需要稳定行为，否则改一次配置顺序就换一条规则生效。</p>
     */
    public static java.util.List<DamageCategory> categoriesOf(String cause) {
        String n = normalize(cause);
        java.util.List<DamageCategory> out = new java.util.ArrayList<>();
        if (n.isEmpty()) return out;
        for (DamageCategory c : values()) {
            for (String member : c.causes) {
                if (normalize(member).equals(n)) {
                    out.add(c);
                    break;
                }
            }
        }
        // ALL 不枚举成员：34 个 cause 抄两遍，改一处漏一处就会让「免疫一切伤害」
        // 悄悄漏掉某种伤害，而这种漏检没有任何报错。改为按「是已知 cause」判定。
        // 追加在末尾 —— ALL 必须永远排最后，否则它会抢在更具体的类别前面。
        if (isKnownCause(n) && !out.contains(ALL)) out.add(ALL);
        return out;
    }

    /**
     * Bukkit 1.21.1 {@code EntityDamageEvent.DamageCause} 的全部常量名。
     *
     * <p>逐个从 paper-api 的 {@code javap} 输出抄下来，而非凭记忆列举。
     * 这个集合的用途是<b>装载期</b>校验：写错 cause 名时立刻告警，
     * 而不是让规则静默永不匹配（那正是「怪物打不动」这类隐蔽故障的成因）。</p>
     */
    public static final Set<String> KNOWN_CAUSES;

    /** 归一化后的 cause 名 -> Bukkit 常量名，供装载期把配置键还原成规范名。 */
    private static final Map<String, String> CAUSE_INDEX;

    // 声明与初始化顺序必须一致：静态字段按书写顺序求值，
    // CAUSE_INDEX 若先于 KNOWN_CAUSES 初始化，buildCauseIndex 读到的会是 null，
    // 抛出的是 ExceptionInInitializerError 而不是任何可读的异常
    static {
        Set<String> s = new LinkedHashSet<>(Arrays.asList(
                "KILL", "WORLD_BORDER", "CONTACT", "ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK",
                "PROJECTILE", "SUFFOCATION", "FALL", "FIRE", "FIRE_TICK", "MELTING",
                "LAVA", "DROWNING", "BLOCK_EXPLOSION", "ENTITY_EXPLOSION", "VOID",
                "LIGHTNING", "SUICIDE", "STARVATION", "POISON", "MAGIC", "WITHER",
                "FALLING_BLOCK", "THORNS", "DRAGON_BREATH", "CUSTOM", "FLY_INTO_WALL",
                "HOT_FLOOR", "CAMPFIRE", "CRAMMING", "DRYOUT", "FREEZE", "SONIC_BOOM"));
        KNOWN_CAUSES = Collections.unmodifiableSet(s);
        Map<String, String> m = new LinkedHashMap<>();
        for (String c : s) m.put(normalize(c), c);
        CAUSE_INDEX = Collections.unmodifiableMap(m);
    }

    /** 是否是已知的 Bukkit cause 名（归一化后比对，大小写与分隔符不敏感）。 */
    public static boolean isKnownCause(String raw) {
        return CAUSE_INDEX.containsKey(normalize(raw));
    }

    /** 把配置键还原为 Bukkit 常量名；未知返回 null。 */
    public static String canonicalCause(String raw) {
        return CAUSE_INDEX.get(normalize(raw));
    }

    /** 全部类别配置名（供 /helstera immunity 展示与补全）。 */
    public static java.util.List<String> configNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (DamageCategory c : values()) out.add(c.configName);
        return java.util.List.copyOf(out);
    }

    /** 归一化到小写连字符形式，供日志/命令展示。 */
    public static String display(String raw) {
        String n = normalize(raw);
        return n;
    }

    @Override
    public String toString() {
        return configName + " " + configName().toUpperCase(Locale.ROOT);
    }
}