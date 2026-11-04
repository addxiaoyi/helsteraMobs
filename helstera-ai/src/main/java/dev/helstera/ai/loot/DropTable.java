package dev.helstera.ai.loot;

import dev.helstera.ai.YamlNums;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 掉落表定义（loot.yml 的 {@code tables.<name>} 节）。
 *
 * <p>刻意把「概率与数量决策」（{@link LootService#rollPlan}）与「物品实体化」
 * （{@link LootService#materialize}）分开：前者是纯函数，可以在没有运行中服务端的
 * 单元测试里验证概率与堆叠，后者才需要 Bukkit 的 ItemStack/Bukkit 材质注册表。</p>
 */
public final class DropTable {

    /**
     * 单个数量档位：按 weight 与同表其它档位竞争。
     *
     * <p>用于表达「70% 掉 1 个 / 25% 掉 2~3 个 / 5% 掉 5 个」这类分布。
     * 单纯的 amount-min/max 只能在区间内均匀取，无法表达这种偏斜。</p>
     */
    public record Tier(int amountMin, int amountMax, double weight, String note) {

        public static Tier of(int min, int max, double weight) {
            return new Tier(min, max, weight, null);
        }

        /** 权重必须为正：非正权重会让该档位永远抽不中，且分母为零时抛异常。 */
        public boolean valid() {
            return weight > 0 && Double.isFinite(weight)
                    && amountMin > 0 && amountMax >= amountMin;
        }
    }

    /** 单条掉落。itemId 可为原版材质名（DIAMOND）或自定义命名空间 ID（helstera:shard）。 */
    public record Entry(String itemId,
                        int amountMin,
                        int amountMax,
                        double chance,
                        boolean luckScaling,
                        Map<String, Integer> enchantments,
                        String displayName,
                        Integer customModelData,
                        boolean glow,
                        String note,
                        List<Tier> tiers,
                        Integer minTierLevel,
                        List<String> conditions,
                        String chanceExpression) {

        /** 兼容旧构造：单档均匀区间，无击杀者门槛。 */
        public Entry(String itemId, int amountMin, int amountMax, double chance, boolean luckScaling,
                     Map<String, Integer> enchantments, String displayName, Integer customModelData,
                     boolean glow, String note) {
            this(itemId, amountMin, amountMax, chance, luckScaling, enchantments, displayName,
                    customModelData, glow, note, List.of(), null, List.of(), null);
        }

        /** 是否配置了分层权重。 */
        public boolean hasTiers() {
            return tiers != null && !tiers.isEmpty();
        }

        public boolean isExperienceDrop() {
            return Set.of("exp", "xp", "experience").contains(itemId.toLowerCase(Locale.ROOT));
        }
    }

    private final String name;
    private final double luckFactor;
    private final List<Entry> entries;
    private final int experienceMin;
    private final int experienceMax;
    private final List<String> triggerConditions;
    private final List<String> conditions;
    /** Fixed generated drop-line count; 0 means unset. */
    private final int totalItems;
    /** Generated drop-line lower bound; 0 means unset. */
    private final int minItems;
    /** Generated drop-line upper bound; 0 means unset. */
    private final int maxItems;
    private final double bonusLevelItemsMin;
    private final double bonusLevelItemsMax;
    private final double bonusLuckItemsMin;
    private final double bonusLuckItemsMax;

    private DropTable(String name, double luckFactor, List<Entry> entries,
                      int experienceMin, int experienceMax) {
        this(name, luckFactor, entries, experienceMin, experienceMax, 0, 0, 0,
                0, 0, 0, 0, List.of(), List.of());
    }

    private DropTable(String name, double luckFactor, List<Entry> entries,
                      int experienceMin, int experienceMax, int totalItems, int minItems, int maxItems,
                      double bonusLevelItemsMin, double bonusLevelItemsMax,
                      double bonusLuckItemsMin, double bonusLuckItemsMax,
                      List<String> triggerConditions, List<String> conditions) {
        this.name = name;
        this.luckFactor = luckFactor;
        this.entries = List.copyOf(entries);
        this.experienceMin = experienceMin;
        this.experienceMax = experienceMax;
        this.triggerConditions = List.copyOf(triggerConditions);
        this.conditions = List.copyOf(conditions);
        this.totalItems = Math.max(0, totalItems);
        this.minItems = Math.max(0, minItems);
        this.maxItems = Math.max(0, maxItems);
        this.bonusLevelItemsMin = bonusLevelItemsMin;
        this.bonusLevelItemsMax = bonusLevelItemsMax;
        this.bonusLuckItemsMin = bonusLuckItemsMin;
        this.bonusLuckItemsMax = bonusLuckItemsMax;
    }

    public String name() {
        return name;
    }

    public double luckFactor() {
        return luckFactor;
    }

    public List<Entry> entries() {
        return entries;
    }

    public int experienceMin() { return experienceMin; }

    public int experienceMax() { return experienceMax; }

    /** Killer-side conditions required before this table can roll. */
    public List<String> triggerConditions() { return triggerConditions; }

    /** Dropper-side conditions checked against the entity whose loot is rolling. */
    public List<String> conditions() { return conditions; }

    /** Fixed generated drop-line count; 0 means unset. */
    public int totalItems() { return totalItems; }

    /** Generated drop-line lower bound; 0 means unset. */
    public int minItems() { return minItems; }

    /** Generated drop-line upper bound; 0 means unset. */
    public int maxItems() { return maxItems; }

    public double bonusLevelItemsMin() { return bonusLevelItemsMin; }

    public double bonusLevelItemsMax() { return bonusLevelItemsMax; }

    public double bonusLuckItemsMin() { return bonusLuckItemsMin; }

    public double bonusLuckItemsMax() { return bonusLuckItemsMax; }

    public boolean hasBonusLevelItems() { return bonusLevelItemsMax > 0; }

    public boolean hasBonusLuckItems() { return bonusLuckItemsMax > 0; }

    /** Whether the table has a count bound or fixed line count. */
    public boolean hasItemBounds() { return minItems > 0 || maxItems > 0; }

    public boolean usesWeightedSelection() {
        return totalItems > 0 || (minItems > 0 && maxItems > 0)
                || hasBonusLevelItems() || hasBonusLuckItems();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 从配置节解析掉落表。
     *
     * <p>非法条目（缺 item、amount 区间颠倒、chance 越界）跳过并记入 problems，
     * 而不是让整张表加载失败——一张表里写错一行不该让怪物不掉落任何东西。</p>
     */
    public static DropTable parse(String name, ConfigurationSection sec, List<String> problems) {
        // 先判空再读字段：此前先读 luck-factor 才判 null，
        // 调用方传 null 节（如 tables.<name> 是空节点）会直接 NPE。
        // luck-factor 同样走严格读取：写成 "很高" 时此前按默认 0.05 静默执行。
        double luckFactor = YamlNums.chance(sec, "luck-factor", 0.05, problems, "掉落表 " + name);
        List<Entry> entries = new ArrayList<>();
        int experienceMin = readExperience(sec, "experience-min", "exp-min", "experience", 0, problems, name);
        int experienceMax = readExperience(sec, "experience-max", "exp-max", "experience", experienceMin,
                problems, name);
        if (experienceMax < experienceMin) {
            problems.add("����� " + name + " experience-max < experience-min���ѽ���");
            int swap = experienceMin;
            experienceMin = experienceMax;
            experienceMax = swap;
        }
        if (sec != null) {
            int idx = 0;
            List<?> configuredEntries = sec.getList("entries", List.of());
            if (configuredEntries.isEmpty()) {
                ConfigurationSection indexedEntries = sec.getConfigurationSection("entries");
                if (indexedEntries != null) {
                    List<String> keys = new ArrayList<>(indexedEntries.getKeys(false));
                    keys.sort(java.util.Comparator
                            .comparingInt((String key) -> {
                                try { return Integer.parseInt(key); }
                                catch (NumberFormatException ignored) { return Integer.MAX_VALUE; }
                            }).thenComparing(String::compareTo));
                    List<Object> indexed = new ArrayList<>(keys.size());
                    for (String key : keys) indexed.add(indexedEntries.get(key));
                    configuredEntries = indexed;
                }
            }
            // Bukkit 的 YAML 里「列表中的映射」不是 ConfigurationSection，而是 List<Map>。
            // 迁移器历史上还会输出带数字键的 ConfigurationSection，保持两种形式都可消费。
            for (Object o : configuredEntries) {
                idx++;
                ConfigurationSection es;
                boolean nullChance = o instanceof Map<?, ?> map
                        && map.containsKey("chance") && map.get("chance") == null;
                boolean nullConditions = o instanceof Map<?, ?> map
                        && map.containsKey("conditions") && map.get("conditions") == null;
                boolean nullExpression = o instanceof Map<?, ?> map
                        && ((map.containsKey("chance-expression") && map.get("chance-expression") == null)
                        || (map.containsKey("dynamic-chance") && map.get("dynamic-chance") == null));
                boolean nullMinTier = o instanceof Map<?, ?> map
                        && map.containsKey("min-tier-level") && map.get("min-tier-level") == null;
                if (o instanceof ConfigurationSection cs) {
                    es = cs;
                } else if (o instanceof Map<?, ?> map) {
                    es = asSection(map);
                } else {
                    problems.add("掉落表 " + name + " 第 " + idx + " 条不是映射（应写成 - item: ...），已跳过");
                    continue;
                }
                String item = es.getString("item");
                String nestedTable = es.getString("table");
                if ((item == null || item.isBlank()) && nestedTable != null && !nestedTable.isBlank()) {
                    item = "table:" + nestedTable.trim();
                }
                if (item == null || item.isBlank()) {
                    problems.add("掉落表 " + name + " 第 " + idx + " 条缺少 item，已跳过");
                    continue;
                }
                // 这里以前是裸 getInt/getDouble：Bukkit 对「值无法解析」与「键不存在」
                // 返回同一个默认值，于是 chance: "很多" 与 chance: 0.5 结果完全相同
                // （都是默认的 1.0，即 100% 必掉），amount_chance 拼错也同样变成必掉。
                // 真服实测这两项都完全静默。
                String where = "掉落表 " + name + " 第 " + idx + " 条";
                boolean hasExpression = es.contains("chance-expression");
                boolean hasDynamic = es.contains("dynamic-chance");
                Object expressionValue = hasExpression ? es.get("chance-expression") : es.get("dynamic-chance");
                if (nullExpression || (hasExpression && hasDynamic)
                        || ((hasExpression || hasDynamic) && (!(expressionValue instanceof String value)
                        || value.isBlank() || value.equals(dev.helstera.api.util.ConditionListParser.INVALID_CONDITION)))) {
                    problems.add(where + " has an invalid or ambiguous dynamic chance expression; entry skipped");
                    continue;
                }
                int amount = es.contains("amount") ? YamlNums.i(es, "amount", 1, problems, where) : 1;
                if (amount < 1) {
                    problems.add(where + " amount 必须为正数，已使用 1");
                    amount = 1;
                }
                int min = Math.max(1, YamlNums.i(es, "amount-min", amount, problems, where));
                int max = Math.max(1, YamlNums.i(es, "amount-max", min, problems, where));
                if (max < min) {
                    problems.add(where + " amount-max < amount-min，已交换");
                    int t = min;
                    min = max;
                    max = t;
                }
                if (nullChance || (es.contains("chance")
                        && !Double.isFinite(YamlNums.d(es, "chance", Double.NaN, null, where)))) {
                    problems.add(where + " chance = " + (nullChance ? "null" : es.get("chance"))
                            + " 不是有效的有限数值，已跳过该掉落");
                    continue;
                }
                double chance = YamlNums.chance(es, "chance", 1.0, problems, where);
                Map<String, Integer> ench = new LinkedHashMap<>();
                // 嵌套的 enchantments 同样是「列表项里的映射」，形态跟着父节点走
                Object encRaw = es.get("enchantments");
                if (encRaw instanceof ConfigurationSection encSec) {
                    for (String k : encSec.getKeys(false)) {
                        // 严格读取：SHARPNESS: "很强" 此前按默认等级 1 静默执行
                        int lvl = YamlNums.i(encSec, k, 1, problems, where + " 的 enchantments." + k);
                        if (lvl > 0) ench.put(k.toUpperCase(Locale.ROOT), lvl);
                        else problems.add(where + " 的 enchantments." + k + " = " + lvl
                                + " 不是正数等级，已忽略该附魔");
                    }
                } else if (encRaw instanceof Map<?, ?> encMap) {
                    // 这是 Bukkit 里「列表项中的嵌套映射」的实际形态——比
                    // ConfigurationSection 分支更常见。此前这里用 toInt 静默兜底，
                    // 所以 SHARPNESS: "很强" 在实际配置里根本没被报出来。
                    for (Map.Entry<?, ?> en : encMap.entrySet()) {
                        if (en.getKey() == null) continue;
                        String key = String.valueOf(en.getKey());
                        int lvl = YamlNums.ofObject(en.getValue(), 1, problems,
                                where + " 的 enchantments." + key);
                        if (lvl > 0) ench.put(key.toUpperCase(Locale.ROOT), lvl);
                        else problems.add(where + " 的 enchantments." + key + " = " + lvl
                                + " 不是正数等级，已忽略该附魔");
                    }
                }
                Integer cmd = es.contains("custom-model-data") ? es.getInt("custom-model-data") : null;
                // 分层权重：同样是「列表项里的映射」，沿用上面的形态兼容处理
                List<Tier> tiers = new ArrayList<>();
                for (Object to : secList(es, "tiers")) {
                    if (to instanceof Map<?, ?> tierMap
                            && tierMap.containsKey("weight") && tierMap.get("weight") == null) {
                        problems.add(where + " 的 tiers weight = null 不是有效的有限数值，已跳过该档位");
                        continue;
                    }
                    ConfigurationSection ts = to instanceof ConfigurationSection cs
                            ? cs
                            : (to instanceof Map<?, ?> m ? asSection(m) : null);
                    if (ts == null) {
                        problems.add("掉落表 " + name + " 第 " + idx + " 条的 tiers 项不是映射，已跳过");
                        continue;
                    }
                    boolean nullRange = to instanceof Map<?, ?> tierMap
                            && ((tierMap.containsKey("amount-min") && tierMap.get("amount-min") == null)
                            || (tierMap.containsKey("amount-max") && tierMap.get("amount-max") == null));
                    double rawMin = YamlNums.d(ts, "amount-min", ts.contains("amount-min") ? Double.NaN : 1, null, where);
                    double rawMax = YamlNums.d(ts, "amount-max", ts.contains("amount-max") ? Double.NaN : rawMin, null, where);
                    if (nullRange || !Double.isFinite(rawMin) || !Double.isFinite(rawMax)
                            || rawMin < 1 || rawMax < rawMin || rawMax > Integer.MAX_VALUE
                            || rawMin != Math.rint(rawMin) || rawMax != Math.rint(rawMax)) {
                        problems.add(where + " 的 tiers amount range is invalid; tier skipped");
                        continue;
                    }
                    int tMin = (int) rawMin;
                    int tMax = (int) rawMax;
                    double weight = YamlNums.d(ts, "weight", ts.contains("weight") ? Double.NaN : 1.0,
                            null, where);
                    if (!Double.isFinite(weight)) {
                        problems.add(where + " 的 tiers weight = " + ts.get("weight")
                                + " 不是有效的有限数值，已跳过该档位");
                        continue;
                    }
                    Tier tier = new Tier(tMin, tMax, weight, ts.getString("note"));
                    if (!tier.valid()) {
                        problems.add("掉落表 " + name + " 第 " + idx + " 条的档位 weight<=0、非有限或区间非法，已跳过");
                        continue;
                    }
                    tiers.add(tier);
                }
                Integer minTier = null;
                if (nullMinTier || es.contains("min-tier-level")) {
                    int v = nullMinTier ? Integer.MIN_VALUE
                            : YamlNums.i(es, "min-tier-level", Integer.MIN_VALUE, null, where);
                    if (v <= 0) {
                        problems.add(where + " min-tier-level is invalid; entry skipped");
                        continue;
                    }
                    minTier = v;
                }
                List<String> conditions;
                if (nullConditions) {
                    problems.add(where + " conditions contains an invalid condition; it will fail closed");
                    conditions = List.of("");
                } else {
                    conditions = readConditions(es, "conditions", problems, where);
                }
                entries.add(new Entry(
                        item.trim(), min, max, chance,
                        es.getBoolean("luck-scaling", true),
                        Collections.unmodifiableMap(ench),
                        es.getString("name"),
                        cmd,
                        es.getBoolean("glow", false),
                        es.getString("note"),
                        Collections.unmodifiableList(tiers),
                        minTier, Collections.unmodifiableList(conditions),
                        chanceExpression(es)));
            }
        }
        int totalItems = readItems(sec, problems, name, "total-items", "totalItems", "TotalItems");
        int minItems = readItems(sec, problems, name, "min-items", "minItems", "MinItems");
        int maxItems = readItems(sec, problems, name, "max-items", "maxItems", "MaxItems");
        double[] bonusLevelItems = readMultiplier(sec, problems, name,
                "bonus-level-items", "bonusLevelItems", "BonusLevelItems");
        double[] bonusLuckItems = readMultiplier(sec, problems, name,
                "bonus-luck-items", "bonusLuckItems", "BonusLuckItems");
        if (totalItems > 0 && maxItems == 0) maxItems = totalItems;
        if (minItems > 0 && maxItems > 0 && minItems > maxItems) {
            problems.add("掉落表 " + name + " min-items (" + minItems + ") > max-items ("
                    + maxItems + ")，已交换");
            int swap = minItems;
            minItems = maxItems;
            maxItems = swap;
        }
        if ((bonusLevelItems[1] > 0 || bonusLuckItems[1] > 0)
                && totalItems == 0 && minItems == 0 && maxItems == 0) {
            problems.add("掉落表 " + name + " BonusLevelItems/BonusLuckItems 需要配置 total-items、min-items 或 max-items；原值已保留，不应用加成");
            bonusLevelItems = new double[] {0, 0};
            bonusLuckItems = new double[] {0, 0};
        }
        return new DropTable(name, luckFactor, entries, experienceMin, experienceMax,
                totalItems, minItems, maxItems, bonusLevelItems[0], bonusLevelItems[1],
                bonusLuckItems[0], bonusLuckItems[1],
                readConditions(sec, "trigger-conditions", problems, "掉落表 " + name),
                readConditions(sec, "conditions", problems, "掉落表 " + name));
    }

    private static double[] readMultiplier(ConfigurationSection sec, List<String> problems,
                                           String tableName, String... keys) {
        if (sec == null) return new double[] {0, 0};
        String key = configuredKey(sec, keys);
        if (key == null) return new double[] {0, 0};
        String raw = String.valueOf(sec.get(key)).trim();
        String[] parts = raw.split("(?i)to", -1);
        if (parts.length < 1 || parts.length > 2) {
            problems.add("掉落表 " + tableName + " " + key + " 范围格式无效，已忽略");
            return new double[] {0, 0};
        }
        try {
            double min = Double.parseDouble(parts[0].trim());
            double max = parts.length == 1 ? min : Double.parseDouble(parts[1].trim());
            if (!Double.isFinite(min) || !Double.isFinite(max) || min < 0 || max < min) {
                throw new NumberFormatException("out of range");
            }
            return new double[] {min, max};
        } catch (NumberFormatException ex) {
            problems.add("掉落表 " + tableName + " " + key + " 必须是非负有限数值或递增范围，已忽略");
            return new double[] {0, 0};
        }
    }

    private static List<String> readConditions(ConfigurationSection sec, String path,
                                               List<String> problems, String where) {
        if (sec == null) return List.of();
        List<String> conditions = new ArrayList<>();
        Object raw = sec.get(path);
        List<?> values = raw instanceof List<?> list ? list : raw == null ? List.of() : List.of(raw);
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank()) {
                problems.add(where + " " + path + " contains an invalid condition; it will fail closed");
                conditions.add("");
            } else {
                List<String> parsed = dev.helstera.api.util.ConditionListParser.parseFailClosed(text);
                for (String condition : parsed) {
                    if (dev.helstera.api.util.ConditionListParser.INVALID_CONDITION.equals(condition)) {
                        problems.add(where + " " + path + " contains an invalid condition; it will fail closed");
                        conditions.add("");
                    } else conditions.add(condition);
                }
            }
        }
        return List.copyOf(conditions);
    }

    /** 读整表件数约束；负数与非数字折成 0（= 不限制）并留告警。 */
    private static int readItems(ConfigurationSection sec, List<String> problems,
                                 String tableName, String... keys) {
        // 空节（loot.yml 里只写了表名没写内容）应当按「不限制」处理，
        // 而不是让 contains() 抛 NPE 把整份掉落表带崩。
        if (sec == null) return 0;
        String key = configuredKey(sec, keys);
        if (key == null) return 0;
        int value = YamlNums.i(sec, key, 0, problems, "掉落表 " + tableName);
        if (value < 0) {
            problems.add("掉落表 " + tableName + " " + key + " = " + value + " 为负数，已按 0（不限制）处理");
            return 0;
        }
        return value;
    }

    private static String configuredKey(ConfigurationSection sec, String... keys) {
        for (String key : keys) {
            if (sec.contains(key)) return key;
        }
        return null;
    }

    private static String chanceExpression(ConfigurationSection sec) {
        String expression = sec.getString("chance-expression");
        if (expression == null || expression.isBlank()) expression = sec.getString("dynamic-chance");
        return expression == null || expression.isBlank() ? null : expression.trim();
    }

    private static int readExperience(ConfigurationSection sec, String primary, String alias,
                                      String single, int def, List<String> problems, String name) {
        if (sec == null) return def;
        String key = sec.contains(primary) ? primary : sec.contains(alias) ? alias : single;
        if (!sec.contains(key)) return def;
        return Math.max(0, YamlNums.i(sec, key, def, problems, "����� " + name));
    }

    /**
     * 读取列表字段，兼容「原生 List」与「单个映射」两种写法。
     *
     * <p>Bukkit 在只写一条时可能把列表退化成单个映射，写 {@code tiers: {weight: 1}}
     * 而不是 {@code tiers: [ {weight: 1} ]} 时也能解析——否则只配一档的表会静默丢档。</p>
     */
    private static List<Object> secList(ConfigurationSection sec, String path) {
        Object raw = sec.get(path);
        if (raw == null) return List.of();
        if (raw instanceof List<?> l) return new ArrayList<>(l);
        if (raw instanceof ConfigurationSection || raw instanceof Map<?, ?>) return List.of(raw);
        return List.of();
    }

    /** 把 YAML 列表项里的 Map 包成 ConfigurationSection，以便复用 getString/getInt 读取。 */
    private static ConfigurationSection asSection(Map<?, ?> map) {
        org.bukkit.configuration.MemoryConfiguration mc = new org.bukkit.configuration.MemoryConfiguration();
        ConfigurationSection s = mc.createSection("entry");
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() == null) continue;
            s.set(String.valueOf(e.getKey()), e.getValue());
        }
        return s;
    }

    private static int toInt(Object v, int def) {
        return v instanceof Number n ? n.intValue() : def;
    }
}
