package dev.helstera.ai.immunity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 免疫与伤害倍率服务（对标 MythicMobs 的 {@code Immunities} / {@code DamageModifiers}）。
 *
 * <p><b>本类刻意不含任何 Bukkit 事件代码</b>：解析、校验、匹配、算伤害全是纯逻辑，
 * 只有 {@link #loadSection} 那一处读 {@code ConfigurationSection}。理由与
 * {@link dev.helstera.ai.FactionService} 相同——判定逻辑才是容易写错的部分，
 * 而它必须能在没有服务端的环境里被单测覆盖；一旦混进事件代码，测试就得起
 * MockBukkit/假服务端，测试比被测代码还脆。</p>
 *
 * <p><b>未配置 = 不免疫</b>。这条是硬约束：漏配的表现是「怪物打不动」，
 * 比多配隐蔽得多（多配至少还有日志，漏配什么都没有）。因此本类绝不做
 * 「默认免疫环境伤害」这类猜测，只按写下来的规则改。</p>
 *
 * <p><b>倍率作用于事件原始值</b>：{@code 原值 × 倍率}，而不是拿已修正过的值再乘。
 * 后者会让 0.5 逐次衰减成 0.25、0.125，表现为「Boss 越打越像不掉血」，
 * 而每一步的数值都合法，排查时看不出任何异常。</p>
 *
 * <p><b>回血用负值表达</b>：{@code multiplier: -1} 得到负伤害，Bukkit 的
 * {@code LivingEntity#damage} 遇负值走治疗分支。不另造「回血」机制——
 * 两种机制并存会让「为什么这条规则不回血」无从排查。</p>
 */
public final class ImmunityService {

    /** 规则的匹配层级。 */
    public enum Kind {
        /** 精确匹配单个 Bukkit {@code DamageCause}。 */
        CAUSE,
        /** 匹配一个伤害类别（含其全部 cause）。 */
        CATEGORY
    }

    /**
     * 一条已编译的规则。
     *
     * @param key 归一化后的配置键（用于展示与去重）
     * @param kind 匹配层级
     * @param multiplier 伤害倍率；1.0 表示不改
     * @param negate 是否强制归零
     * @param conditions 生效条件（全部为真才生效）；空列表表示无条件生效
     */
    public record Rule(String key, Kind kind, double multiplier, boolean negate,
                        List<String> conditions) {
        public Rule {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }

        /** 无条件生效。 */
        public boolean unconditional() {
            return conditions.isEmpty();
        }

        /** 展示用：{@code fire ×0.5} 或 {@code fire 免疫}，带条件时附加条件摘要。 */
        public String describe() {
            String base = negate ? key + " 免疫" : key + " ×" + trim(multiplier);
            return conditions.isEmpty()
                    ? base
                    : base + " [当 " + String.join(" & ", conditions) + "]";
        }

        private static String trim(double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        }
    }

    /**
     * 一条配置原始行（与 Bukkit 无关，供单测直接构造）。
     *
     * <p>刻意把「原始行」与「已编译规则」分成两种类型：原始行允许携带非法值
     * （未知名、非数字、NaN），这些必须在装载期变成告警而不是抛异常——
     * 配置文件里的一个笔误不该让整个服务器起不来。</p>
     *
     * @param key 配置键（cause 名或类别名）
     * @param multiplier 倍率；null 表示未给
     * @param negate 是否声明为免疫；null 表示未给
     * @param conditions 生效条件列表；空列表表示无条件
     */
    public record Row(String key, Double multiplier, Boolean negate, List<String> conditions) {
        public Row {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }

        public static Row of(String key, double multiplier) {
            return new Row(key, multiplier, null, List.of());
        }

        public static Row immune(String key) {
            return new Row(key, null, Boolean.TRUE, List.of());
        }

        /** 带生效条件的倍率行。 */
        public static Row of(String key, double multiplier, List<String> conditions) {
            return new Row(key, multiplier, null, conditions);
        }

        /** 带生效条件的免疫行。 */
        public static Row immune(String key, List<String> conditions) {
            return new Row(key, null, Boolean.TRUE, conditions);
        }
    }

    /**
     * 一组配置原始行（纯数据）。
     *
     * <p>{@code immunities} 列表与 {@code damageModifiers} 键值表在配置里是两处，
     * 装载后合并成同一个有序行列表——顺序即服主的书写顺序，类别优先级依赖它。</p>
     */
    public record Config(List<Row> rows) {
        public Config {
            // 逐项过滤 null，而不是直接 List.copyOf：后者遇到 null 元素抛 NPE，
            // 而配置解析出来的列表完全可能含空项，不该让整个服务起不来。
            // 用显式循环而非 Stream.of(array)：后者把元素擦成 Object，收不回来。
            if (rows == null || rows.isEmpty()) {
                rows = List.of();
            } else {
                List<Row> clean = new ArrayList<>(rows.size());
                for (Row r : rows) if (r != null) clean.add(r);
                rows = List.copyOf(clean);
            }
        }

        public static Config empty() {
            return new Config(List.of());
        }
    }

    /**
     * 一次判定的结果。
     *
     * @param damage 修正后的伤害值；未命中时等于传入的原值
     * @param matched 是否命中任何规则
     * @param rule 命中的规则；未命中为 null
     */
    public record Result(double damage, boolean matched, Rule rule) {
        /** 未命中：原值原样返回，调用方据此决定是否碰事件。 */
        public static Result unchanged(double original) {
            return new Result(original, false, null);
        }

        public String matchedKey() {
            return rule == null ? null : rule.key();
        }

        /**
         * 是否应回血（负倍率）。
         *
         * <p>抽成纯函数是为了能单测「什么算回血、回多少」——
         * 这段判断若只写在事件监听器里，唯一的验证方式是真服挨一次打。</p>
         */
        public boolean isHeal() {
            return matched && damage < 0;
        }

        /** 回血量（正数）；不是回血时返回 0。 */
        public double healAmount() {
            return isHeal() ? -damage : 0.0;
        }
    }

    /**
     * 已编译的规则表。
     *
     * <p>用 {@link LinkedHashMap} 而非 {@code EnumMap}：{@code EnumMap} 按 ordinal
     * 排列，会把服主的书写顺序打乱，而类别优先级恰恰依赖书写顺序
     * （两条类别规则都命中时，先写的那条生效）。此前有人用 EnumMap 却在注释里
     * 写「保持插入顺序」——文案与行为不符，比没有注释更糟。</p>
     *
     * <p>键为 {@code kind + ":" + 归一化名}：类别 {@code fire} 与 cause {@code FIRE}
     * 归一化后同名，不带 kind 前缀会互相覆盖，而两者语义不同。</p> */
    public static final class Table {
        private final List<Rule> ordered;
        private final Map<String, Rule> byKey;
        private final List<String> warnings;

        private Table(List<Rule> ordered, Map<String, Rule> byKey, List<String> warnings) {
            this.ordered = Collections.unmodifiableList(ordered);
            this.byKey = Collections.unmodifiableMap(byKey);
            this.warnings = Collections.unmodifiableList(warnings);
        }

        /** 规则快照（按服主书写顺序）。 */
        public List<Rule> rules() {
            return ordered;
        }

        /** 装载期告警（未知名 / 参数非法），供 {@code /helstera check} 展示。 */
        public List<String> warnings() {
            return warnings;
        }

        public boolean isEmpty() {
            return ordered.isEmpty();
        }

        public int size() {
            return ordered.size();
        }

        /** 是否存在带条件的规则；全为无条件规则时返回 false。 */
        public boolean hasConditions() {
            for (Rule r : ordered) {
                if (!r.unconditional()) return true;
            }
            return false;
        }

        /**
         * 对一次伤害事件求值。
         *
         * <p><b>优先级：精确 cause &gt; 类别</b>。两者都命中时精确规则生效——
         * 配置里同时写了 {@code FIRE} 与「所有实体攻击减伤」时，
         * FIRE 精确规则优先。</p>
         *
         * <p>多条类别规则都命中时，<b>先写的那条生效</b>，而不是「最具体的那条」。
         * 最具体需要一套类别之间的包含关系（ENVIRONMENT ⊃ FIRE），
         * 而那会让「我把 FIRE 写在前面却没生效」变成可能，书写顺序反而失去意义。</p>
         *
         * @param cause Bukkit 的 {@code DamageCause} 名
         * @param originalDamage 事件原始伤害值（不是已修正过的值）
         */
        public Result evaluate(String cause, double originalDamage) {
            return evaluate(cause, originalDamage, null);
        }

        /**
         * 带条件求值。
         *
         * <p><b>条件不满足时按「该条规则不生效」处理，并继续向下找下一条</b>，
         * 而不是直接返回原值不修改。这让「Boss 只在脱战时免疫火焰」这类
         * 条件规则能和无条件规则共存：条件不成立时退回到类别/精确的下一条，
         * 而非把伤害整体冻结。</p>
         *
         * @param cond 条件求值器；null 表示所有规则无条件成立（等于无参调用）
         */
        public Result evaluate(String cause, double originalDamage, Predicate<String> cond) {
            Rule hit = ruleFor(cause, cond);
            if (hit == null) return Result.unchanged(originalDamage);
            if (hit.negate()) return new Result(0.0, true, hit);
            // 关键：乘在 originalDamage 上，而不是乘在「当前值」上。
            // 拿当前值再乘会让倍率逐次衰减（0.5 -> 0.25 -> 0.125）。
            return new Result(originalDamage * hit.multiplier(), true, hit);
        }

        /** 该 cause 会命中哪条规则（无条件）；未命中返回 null。 */
        public Rule ruleFor(String cause) {
            return ruleFor(cause, null);
        }

        /**
         * 找命中该 cause 且条件成立的规则；未命中返回 null。
         *
         * <p>精确规则条件不成立时会<b>继续向下找类别规则</b>——精确优先只作用于
         * 「哪条规则最终生效」，不该把「不生效」与「阻断后续查找」混为一谈。
         * 否则写了一条带条件�� ENTITY_ATTACK 会让类别层的减伤也一起失效。</p>
         */
        public Rule ruleFor(String cause, Predicate<String> cond) {
            String n = DamageCategory.normalize(cause);
            if (n.isEmpty()) return null;
            // 第一层：精确 cause。与顺序无关，这是「精确优先」的落点。
            Rule exact = byKey.get(Kind.CAUSE.name() + ":" + n);
            if (exact != null && satisfied(exact, cond)) return exact;
            // 第二层：类别。必须按服主书写顺序扫描（即 ordered），
            // 不能按 categoriesOf() 的枚举声明顺序——那等于改成「哪条类别更具体就先赢」，
            // 于是书写顺序彻底失去意义，保序的 LinkedHashMap 也白用了。
            java.util.List<DamageCategory> cats = DamageCategory.categoriesOf(n);
            for (Rule r : ordered) {
                if (r.kind() != Kind.CATEGORY) continue;
                DamageCategory c = DamageCategory.of(r.key());
                if (c != null && cats.contains(c) && satisfied(r, cond)) return r;
            }
            return null;
        }

        /**
         * 规则当前是否成立。
         *
         * <p>无 {@code cond} 求值器时，带条件的规则一律视为不成立——
         * 「无法判断」不能当成「成立」，否则漏接线会直接变成免疫全场。</p>
         */
        private static boolean satisfied(Rule r, Predicate<String> cond) {
            if (r.unconditional()) return true;
            if (cond == null) return false;
            for (String c : r.conditions()) {
                boolean ok;
                try {
                    ok = cond.test(c);
                } catch (Throwable t) {
                    ok = false;   // 单条条件出错不得让整条免疫链崩掉
                }
                if (!ok) return false;
            }
            return true;
        }

        /** 该 cause 是否会被任何规则改动（供诊断命令展示）。 */
        public boolean affects(String cause) {
            return ruleFor(cause) != null;
        }
    }

    /**
     * 编译配置行：校验、归一化、去重。
     *
     * <p>非法项一律<b>跳过并告警</b>，不抛异常。运行期则静默跳过——
     * 装载期已经把话说清楚了，再在每次伤害事件里打日志只会刷屏。</p>
     */
    public static Table compile(Config cfg) {
        List<Rule> ordered = new ArrayList<>();
        Map<String, Rule> byKey = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (cfg == null) return new Table(ordered, byKey, warnings);

        for (Row row : cfg.rows()) {
            if (row == null) continue;
            String key = row.key();
            String n = DamageCategory.normalize(key);
            if (n.isEmpty()) {
                warnings.add("免疫/伤害倍率有一条空的键，已跳过");
                continue;
            }

            DamageCategory cat = DamageCategory.of(n);
            boolean knownCause = DamageCategory.isKnownCause(n);
            if (!knownCause && cat == null) {
                // 未知名必须告警：规则静默永不匹配时，现场表现与「没配这条」完全一致
                warnings.add("未知的伤害类型 \"" + key + "\"（既不是 cause 也不是类别），已跳过。"
                        + "类别可用: " + String.join(", ", DamageCategory.configNames()));
                continue;
            }

            boolean negate = Boolean.TRUE.equals(row.negate());
            Double rawMul = row.multiplier();
            double multiplier = 1.0;
            if (rawMul != null) {
                double m = rawMul;
                if (Double.isNaN(m) || Double.isInfinite(m)) {
                    warnings.add("伤害类型 \"" + key + "\" 的倍率非法（" + m + "），已跳过");
                    continue;
                }
                if (!negate && m == 0.0) {
                    // 0 倍率与免疫等价，但作者多半是想写 0.5 而漏了小数点
                    warnings.add("伤害类型 \"" + key + "\" 的倍率为 0（等同免疫），确认是否笔误");
                }
                multiplier = m;
            }
            if (!negate && rawMul == null) {
                // 既没倍率也没 negate：这条规则什么也不做，写它的人多半以为有效果
                warnings.add("伤害类型 \"" + key + "\" 既没有倍率也没有 immune: true，该条不产生任何效果");
                continue;
            }
            if (negate) multiplier = 0.0;

            // 一个键可能同时是 cause 名与类别名（fire / FIRE、void、poison…）。
            // 只挑一个注册会让另一半永远匹配不到：写 fire 却只挡 FIRE、
            // 结果 FIRE_TICK / LAVA 照样打进来 —— 而现象与「没配」完全一致。
            // 因此两种解释都注册；ruleFor 仍保证「精确 cause 优先于类别」。
            List<Kind> kinds = new ArrayList<>(2);
            if (knownCause) kinds.add(Kind.CAUSE);
            if (cat != null) kinds.add(Kind.CATEGORY);
            if (kinds.isEmpty()) kinds.add(Kind.CAUSE);

            // 展示用的 kind 优先取类别（信息量更大：能覆盖多少 cause 一眼可见）
            Kind displayKind = kinds.contains(Kind.CATEGORY) ? Kind.CATEGORY : Kind.CAUSE;
            // 条件整体由条件名列表承载：全部为真才生效。
            // 刻意不用 ConditionExpr 的组合语法 —— 解析失败会把整段降级成
            // 一个未知条件叶子（条件不成立 = 规则静默失效），而倍率表里
            // 一行一个条件名已经够用，不值得引入这个失败模式。
            List<String> conditions = row.conditions() == null
                    ? List.of() : row.conditions().stream()
                    .filter(c -> c != null && !c.isBlank())
                    .map(String::trim)
                    .toList();
            Rule rule = new Rule(key, displayKind, multiplier, negate, conditions);
            boolean duplicate = false;
            for (Kind k : kinds) {
                String mapKey = k.name() + ":" + n;
                Rule prev = byKey.get(mapKey);
                if (prev != null) duplicate = true;
                byKey.put(mapKey, rule);
            }
            if (duplicate) {
                warnings.add("伤害类型 \"" + key + "\" 重复声明（后者覆盖前者）");
                // 整体替换而非留两条：两条都生效会让倍率叠乘，
                // 而每一处的数值都合法，排查时看不出任何异常
                final Kind dk = displayKind;
                ordered.removeIf(r -> r.kind() == dk && n.equals(DamageCategory.normalize(r.key())));
            }
            ordered.add(rule);
        }
        return new Table(ordered, byKey, warnings);
    }

    /**
     * 从 Bukkit 配置节读取 {@code immunities} 与 {@code damage-modifiers}。
     *
     * <p>本类是<b>唯一</b>碰 {@code ConfigurationSection} 的地方。顺序取
     * {@code immunities} 在前、{@code damage-modifiers} 在后，与 YAML 书写顺序一致
     * （前者通常是「免疫清单」，后者是「倍率表」）。</p>
     *
     * <p>{@code damage-modifiers} 的值接受三种写法：裸数字（{@code 0.5}）、
     * 字符串数字、含 {@code multiplier}/{@code immune} 的子节。子节还可带
     * {@code when}（单个条件）或 {@code conditions}（多条件），使该条规则只在
     * 条件成立时生效。</p>
     *
     * <p>{@code immunities} 同样接受裸列表与带条件的子节两种形态，
     * 因为「脱战时免疫火焰」这类需求不写倍率也要能表达。</p>
     */
    public static Config rowsFromSection(org.bukkit.configuration.ConfigurationSection root) {
        List<Row> rows = new ArrayList<>();
        if (root == null) return new Config(rows);

        Object immRaw = root.get("immunities");
        org.bukkit.configuration.ConfigurationSection immSec =
                immRaw instanceof org.bukkit.configuration.ConfigurationSection s ? s : null;
        if (immSec != null) {
            for (String key : immSec.getKeys(false)) {
                rows.add(Row.immune(key, conditionsOf(immSec, key)));
            }
        } else {
            // 裸列表；也兼容「只写一条时退化成字符串」——此时 getStringList 返回空，
            // 写了一条却静默失效且无任何报错
            if (immRaw instanceof String one) {
                rows.add(Row.immune(one));
            } else {
                for (String name : root.getStringList("immunities")) {
                    rows.add(Row.immune(name));
                }
            }
        }

        org.bukkit.configuration.ConfigurationSection mods =
                root.getConfigurationSection("damage-modifiers");
        if (mods != null) {
            for (String key : mods.getKeys(false)) {
                Object raw = mods.get(key);
                if (raw == null) {
                    rows.add(Row.immune(key, conditionsOf(mods, key)));
                    continue;
                }
                if (raw instanceof org.bukkit.configuration.ConfigurationSection sub) {
                    List<String> conds = conditionsOf(sub, null);
                    Double mul = sub.contains("multiplier") ? sub.getDouble("multiplier") : null;
                    boolean immune = sub.getBoolean("immune", false);
                    if (mul == null && !immune) {
                        rows.add(new Row(key, null, Boolean.FALSE, conds));
                    } else {
                        rows.add(new Row(key, mul, immune ? Boolean.TRUE : null, conds));
                    }
                    continue;
                }
                Double mul = asNumber(raw);
                if (mul == null) {
                    // 非数字：交给 compile 产生告警，而不是在这里静默吞掉
                    rows.add(new Row(key, null, Boolean.FALSE, List.of()));
                    continue;
                }
                rows.add(new Row(key, mul, null, List.of()));
            }
        }
        return new Config(rows);
    }

    /**
     * 读生效条件：{@code when}（单条）与 {@code conditions}（列表）。
     *
     * <p>只读字符串，不做任何解析或校验：条件名要到运行期才有注册表可比对，
     * 装载期一律放行。写错条件名的表现是「规则永不生效」，
     * 因此这条风险由 {@code /helstera immunity} 逐 cause 试算暴露，
     * 而不是在装载期猜条件名是否合法。</p>
     */
    private static List<String> conditionsOf(
            org.bukkit.configuration.ConfigurationSection sec, String key) {
        org.bukkit.configuration.ConfigurationSection c = key == null
                ? sec : sec.getConfigurationSection(key);
        if (c == null) return List.of();
        List<String> out = new ArrayList<>();
        String when = c.getString("when");
        if (when != null && !when.isBlank()) out.add(when.trim());
        for (String s : c.getStringList("conditions")) {
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /** 配置值转数字；失败返回 null（由调用方决定是告警还是跳过）。 */
    private static Double asNumber(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        String s = String.valueOf(raw).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 便捷入口：直接编译一个配置节。 */
    public static Table loadSection(org.bukkit.configuration.ConfigurationSection root) {
        return compile(rowsFromSection(root));
    }

    /** 便捷入口：编译已解析好的行。 */
    public static Table load(Config cfg) {
        return compile(cfg);
    }

    /** 空表：未配置任何免疫/倍率时的语义（不改任何伤害）。 */
    public static Table emptyTable() {
        return compile(Config.empty());
    }

    /**
     * 计算加血后的目标血量（纯算术，可脱离服务端单测）。
     *
     * <p>抽成纯函数的原因：这段是「回血」唯一真正会出错的地方——
     * {@code setHealth} 传越界值会被服务端<b>夹到 0</b>，也就是把生物打死。
     * 「Boss 莫名其妙暴毙」比「不回血」难解释得多，而它就发生在这三行算术里。</p>
     *
     * @param current 当前血量
     * @param max 最大血量
     * @param amount 回血量（非正数表示不治疗）
     * @return 目标血量；无需治疗时原样返回 {@code current}
     */
    public static double clampHeal(double current, double max, double amount) {
        if (amount <= 0 || max <= 0) return current;
        // 夹到 maxHealth：越界的 setHealth 会被服务端夹到 0（生物暴毙）
        double target = Math.min(max, current + amount);
        // 已经满血时不再 setHealth：避免一次无意义的写入与事件派发
        return target <= current ? current : target;
    }
}