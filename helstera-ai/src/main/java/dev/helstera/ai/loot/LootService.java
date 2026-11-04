package dev.helstera.ai.loot;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 掉落服务：装载 loot.yml、掷骰、实体化掉落物。
 *
 * <p>分两层是为了可测性。{@link #rollPlan} 只依赖 {@link DropTable} 与
 * {@link Random}，不碰任何 Bukkit 类型，可以在单元测试里验证概率、堆叠与幸运值影响；
 * {@link #materialize} 才负责把决策结果变成 {@link ItemStack}。</p>
 *
 * <p>线程约束：{@link #rollPlan} 纯函数可任意线程调用；{@link #materialize} 与
 * {@link #drop} 需要主线程（会读取 Bukkit 材质注册表并操作世界）。</p>
 */
public final class LootService {

    /** 一次投掷的命中结果：某条目 + 本次数量。 */
    public record Hit(DropTable.Entry entry, int amount) {
    }

    /**
     * 击杀者档位提供者：把玩家映射为可用于 {@code min-tier-level} 比较的整数。
     *
     * <p>做成注入钩子而非在服务里读玩家：档位语义属于玩法配置（权限组、
     * 队伍等级、第三方插件的评分都可能），而 LootService 只关心拿到一个数字。
     * 返回负数表示「档位未知」，此时不套用任何门槛。</p>
     */
    public interface TierResolver {
        int tierOf(Player killer);
    }

    @FunctionalInterface
    public interface ConditionEvaluator {
        boolean test(String condition, org.bukkit.Location location, Player killer);
    }

    @FunctionalInterface
    public interface DropperConditionEvaluator {
        boolean test(String condition, org.bukkit.Location location, org.bukkit.entity.Entity dropper);
    }

    @FunctionalInterface
    public interface DynamicChanceEvaluator {
        double evaluate(String expression, org.bukkit.Location location, Player killer, double luck);
    }

    private final Map<String, DropTable> tables = new ConcurrentHashMap<>();
    private final Logger log;
    private final Random random = new Random();
    private final List<String> problems = new ArrayList<>();
    /** 可选：自定义命名空间物品解析器（ItemAdder / CraftEngine 接入点）。 */
    private volatile java.util.function.Function<String, ItemStack> customItemResolver;
    private volatile java.util.function.Predicate<String> claimedItem = id -> false;
    /** 击杀者档位解析；未注入时按「未知」处理，门槛型掉落照常参与掷骰。 */
    private volatile TierResolver tierResolver;
    private volatile ConditionEvaluator conditionEvaluator;
    private volatile DropperConditionEvaluator dropperConditionEvaluator;
    private volatile DynamicChanceEvaluator dynamicChanceEvaluator;

    public LootService(Logger log) {
        this.log = log;
    }

    /** Loads native loot configuration, preserving relevant explicit null fields. */
    public void loadFile(java.nio.file.Path path) throws java.io.IOException {
        try {
            String yaml = java.nio.file.Files.readString(path);
            YamlConfiguration config = new YamlConfiguration();
            config.loadFromString(yaml);
            var root = new org.yaml.snakeyaml.Yaml().compose(new java.io.StringReader(yaml));
            var tablesNode = mappingValue(root, "tables");
            var tablesSection = config.getConfigurationSection("tables");
            if (tablesNode instanceof org.yaml.snakeyaml.nodes.MappingNode && tablesSection != null) {
                for (String key : tablesSection.getKeys(false)) {
                    var table = mappingValue(tablesNode, key);
                    var section = tablesSection.getConfigurationSection(key);
                    if (section == null) continue;
                    restoreNullConditions(table, section, "conditions", "trigger-conditions");
                    var entriesNode = mappingValue(table, "entries");
                    var entries = section.getConfigurationSection("entries");
                    if (entriesNode instanceof org.yaml.snakeyaml.nodes.MappingNode && entries != null) {
                        for (String entryKey : entries.getKeys(false)) {
                            var entrySection = entries.getConfigurationSection(entryKey);
                            if (entrySection != null) {
                                var entryNode = mappingValue(entriesNode, entryKey);
                                restoreNullConditions(entryNode, entrySection, "conditions");
                                restoreNullConditions(entryNode, entrySection, "chance-expression", "dynamic-chance");
                                restoreNullConditions(entryNode, entrySection, "min-tier-level");
                                var chance = mappingValue(entryNode, "chance");
                                if (chance != null && org.yaml.snakeyaml.nodes.Tag.NULL.equals(chance.getTag())) {
                                    entrySection.set("chance", "null");
                                }
                                var tierNode = mappingValue(entryNode, "tiers");
                                var tierSection = entrySection.getConfigurationSection("tiers");
                                if (tierSection != null) {
                                    restoreNullConditions(tierNode, tierSection, "amount-min", "amount-max");
                                }
                                var weight = mappingValue(tierNode, "weight");
                                if (tierSection != null && weight != null
                                        && org.yaml.snakeyaml.nodes.Tag.NULL.equals(weight.getTag())) {
                                    tierSection.set("weight", "null");
                                }
                            }
                        }
                    }
                }
            }
            load(tablesSection);
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException | RuntimeException ex) {
            load(null);
            throw new java.io.IOException("Unable to load loot configuration " + path, ex);
        }
    }

    private static org.yaml.snakeyaml.nodes.Node mappingValue(org.yaml.snakeyaml.nodes.Node node, String key) {
        return mappingValue(node, key, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
    }

    private static org.yaml.snakeyaml.nodes.Node mappingValue(org.yaml.snakeyaml.nodes.Node node, String key,
            Set<org.yaml.snakeyaml.nodes.Node> visiting, int depth) {
        if (!(node instanceof org.yaml.snakeyaml.nodes.MappingNode mapping)) return null;
        if (depth > 64 || !visiting.add(node)) throw new IllegalArgumentException("Invalid YAML merge cycle or depth");
        org.yaml.snakeyaml.nodes.Node result = null;
        try {
            for (var tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof org.yaml.snakeyaml.nodes.ScalarNode scalar
                        && !org.yaml.snakeyaml.nodes.Tag.MERGE.equals(scalar.getTag())
                        && scalar.getValue().equals(key)) result = tuple.getValueNode();
            }
            if (result != null) return result;
            for (var tuple : mapping.getValue()) {
                if (!org.yaml.snakeyaml.nodes.Tag.MERGE.equals(tuple.getKeyNode().getTag())) continue;
                var value = tuple.getValueNode();
                if (value instanceof org.yaml.snakeyaml.nodes.SequenceNode sequence) {
                    for (var item : sequence.getValue()) {
                        result = mappingValue(item, key, visiting, depth + 1);
                        if (result != null) return result;
                    }
                } else {
                    result = mappingValue(value, key, visiting, depth + 1);
                    if (result != null) return result;
                }
            }
            return null;
        } finally {
            visiting.remove(node);
        }
    }

    private static void restoreNullConditions(org.yaml.snakeyaml.nodes.Node node,
                                              ConfigurationSection section, String... keys) {
        for (String key : keys) {
            var value = mappingValue(node, key);
            if (value != null && org.yaml.snakeyaml.nodes.Tag.NULL.equals(value.getTag())) {
                section.set(key, dev.helstera.api.util.ConditionListParser.INVALID_CONDITION);
            }
        }
    }

    /** 注入击杀者档位解析器，使 {@code min-tier-level} 在运行期生效。 */
    public void setTierResolver(TierResolver resolver) {
        this.tierResolver = resolver;
    }

    public void setConditionEvaluator(ConditionEvaluator evaluator) {
        this.conditionEvaluator = evaluator;
    }

    public void setDropperConditionEvaluator(DropperConditionEvaluator evaluator) {
        this.dropperConditionEvaluator = evaluator;
    }

    public void setDynamicChanceEvaluator(DynamicChanceEvaluator evaluator) {
        this.dynamicChanceEvaluator = evaluator;
    }

    /**
     * 注入自定义物品解析器。返回 null 表示该 ID 无法解析；仅原版名称可以
     * 回退到材质匹配，外部命名空间不会被替换为原版物品。
     */
    public void setCustomItemResolver(java.util.function.Function<String, ItemStack> resolver) {
        this.customItemResolver = resolver;
    }

    public void setClaimedItemPredicate(java.util.function.Predicate<String> predicate) {
        claimedItem = predicate == null ? id -> false : predicate;
    }

    /**
     * 运行期的概率钳制。
     *
     * <p>与装载期 {@code YamlNums.chance} 的分工：那里校验<b>作者写的配置</b>
     * （越界要告警），这里保证<b>投掷时</b>概率合法——幸运加成后不能超过 1，
     * 否则 {@code rnd.nextDouble() >= chance} 恒不成立，掉落率会静默变成 100%。
     * 两者都需要，只是一个该告警、一个不该。</p>
     */
    private static double clampChance(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    /** 装载 {@code loot.yml} 的 {@code tables} 节。 */
    public void load(ConfigurationSection tablesSec) {        tables.clear();
        // problems 必须清，否则每次 reload 都把上一轮的告警叠加上去。
        //
        // 真服验证：把 loot.yml 换成完全合法的内容再 reload，/helstera check 仍报
        // 上一轮的「amount-max < amount-min」——而且每 reload 一次多一条。
        // 作者看到自己已经改对的配置仍在报错，会以为修复没生效，转去反复检查
        // 那份其实正确的文件。比不报错更误导。
        //
        // （SpawnerService.load 就清对了，SkillService.loadSkills 也已修；这里漏了。）
        problems.clear();
        if (tablesSec == null) return;
        for (String key : tablesSec.getKeys(false)) {
            ConfigurationSection sec = tablesSec.getConfigurationSection(key);
            if (sec == null) continue;
            DropTable t = DropTable.parse(key.toLowerCase(Locale.ROOT), sec, problems);
            tables.put(t.name(), t);
        }
        for (DropTable parent : tables.values()) {
            for (DropTable.Entry entry : parent.entries()) {
                if (!entry.itemId().regionMatches(true, 0, "table:", 0, 6)) continue;
                String childName = entry.itemId().substring(6).trim();
                DropTable child = table(childName);
                if (child == null) {
                    problems.add("掉落表 " + parent.name() + " 引用不存在的子表 " + childName);
                    continue;
                }
                if (entry.amountMin() != 1 || entry.amountMax() != 1 || entry.hasTiers()) {
                    problems.add("掉落表 " + parent.name() + " 的子表 " + childName
                            + " 数量范围或档位尚未应用");
                }
                if (hasNestedExperience(child, parent.name())) {
                    problems.add("掉落表 " + parent.name() + " 的子表 " + childName
                            + " 经验不会计入父表经验");
                }
            }
        }
        if (!problems.isEmpty()) {
            for (String p : problems) {
                if (log != null) log.warning("[掉落] " + p);
            }
        }
        if (log != null) log.info("已装载掉落表 " + tables.size() + " 张");
    }

    private boolean hasNestedExperience(DropTable child, String parentName) {
        var pending = new java.util.ArrayDeque<DropTable>();
        var visited = new HashSet<String>();
        visited.add(parentName);
        pending.add(child);
        while (!pending.isEmpty()) {
            DropTable current = pending.removeFirst();
            if (!visited.add(current.name())) continue;
            if (current.experienceMin() > 0 || current.experienceMax() > 0
                    || current.entries().stream().anyMatch(DropTable.Entry::isExperienceDrop)) return true;
            for (DropTable.Entry entry : current.entries()) {
                if (!entry.itemId().regionMatches(true, 0, "table:", 0, 6)) continue;
                DropTable nested = table(entry.itemId().substring(6).trim());
                if (nested != null) pending.addLast(nested);
            }
        }
        return false;
    }

    public Collection<String> tableNames() {
        return Collections.unmodifiableSet(tables.keySet());
    }

    public DropTable table(String name) {
        return name == null ? null : tables.get(name.toLowerCase(Locale.ROOT));
    }

    public int size() {
        return tables.size();
    }

    /** Rolls table-level experience without affecting the item drop random stream. */
    public int experience(String tableName, Random rnd) {
        return experience(tableName, rnd, null, null, null);
    }

    /** Rolls table experience only when the table-level trigger conditions pass. */
    public int experience(String tableName, Random rnd,
                          org.bukkit.Location location, Player killer) {
        DropTable table = table(tableName);
        if (table == null || !triggerConditionsPass(table.triggerConditions(), location, killer)
                || !dropperConditionsPass(table.conditions(), location, null)) return 0;
        return experienceEntries(table, experienceBase(table, rnd), 0, rnd, location, killer, tierOf(killer));
    }

    public int experience(String tableName, Random rnd, org.bukkit.Location location,
                          Player killer, org.bukkit.entity.Entity dropper) {
        DropTable table = table(tableName);
        if (table == null || !triggerConditionsPass(table.triggerConditions(), location, killer)
                || !dropperConditionsPass(table.conditions(), location, dropper)) return 0;
        return experienceEntries(table, experienceBase(table, rnd), 0, rnd, location, killer, tierOf(killer));
    }

    public int experience(String tableName, Random rnd, double luck, org.bukkit.Location location,
                          Player killer, org.bukkit.entity.Entity dropper) {
        return experience(tableName, rnd, luck, location, killer, 0, dropper);
    }

    public int experience(String tableName, Random rnd, double luck, org.bukkit.Location location,
                          Player killer, int mobLevel, org.bukkit.entity.Entity dropper) {
        DropTable table = table(tableName);
        if (table == null || !triggerConditionsPass(table.triggerConditions(), location, killer)
                || !dropperConditionsPass(table.conditions(), location, dropper)) return 0;
        return experienceEntries(table, experienceBase(table, rnd), luck, rnd, location, killer,
                effectiveTier(tierOf(killer), mobLevel));
    }

    private static int experienceBase(DropTable table, Random rnd) {
        int min = table.experienceMin();
        int max = table.experienceMax();
        return randomInclusive(min, max, rnd);
    }

    private int experienceEntries(DropTable table, int base, double luck, Random rnd,
                                  org.bukkit.Location location, Player killer, int killerTier) {
        long total = base;
        for (DropTable.Entry entry : table.entries()) {
            if (!entry.isExperienceDrop() || !conditionsPass(entry, location, killer)) continue;
            if (entry.minTierLevel() != null && killerTier >= 0 && killerTier < entry.minTierLevel()) continue;
            double chance = entry.chance();
            if (entry.chanceExpression() != null) {
                DynamicChanceEvaluator evaluator = dynamicChanceEvaluator;
                if (evaluator == null) continue;
                try {
                    chance = evaluator.evaluate(entry.chanceExpression(), location, killer, luck);
                } catch (RuntimeException ignored) {
                    continue;
                }
            }
            if (!Double.isFinite(chance)) continue;
            if (entry.luckScaling()) {
                chance = clampChance(chance + Math.max(0, luck) * table.luckFactor(), 0, 1);
            }
            if (rnd.nextDouble() < chance) total += pickAmount(entry, rnd);
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public int experience(String tableName) {
        return experience(tableName, random);
    }

    public List<String> warnings() {
        return List.copyOf(problems);
    }

    /**
     * 掷骰：按每条的 chance 决定是否掉落，并决定数量。纯函数，不产生副作用。
     *
     * @param tableName 掉落表名
     * @param luck      幸运值输入；正值可提升启用 luck scaling 的条目概率
     */
    public List<Hit> rollPlan(String tableName, double luck) {
        DropTable t = table(tableName);
        if (t == null || t.isEmpty()) return List.of();
        return rollPlan(t, luck, random);
    }

    /** 可注入随机源的纯函数重载，供单元测试使用。 */
    public List<Hit> rollPlan(DropTable t, double luck, Random rnd) {
        return rollPlan(t, luck, rnd, -1, null, null);
    }

    /**
     * 带击杀者档位的纯函数重载。
     *
     * <p>{@code killerTier} 小于 0 表示击杀者未知：不套用任何 tier 门槛，
     * 免得因为拿不到档位就把所有带门槛的掉落全吞掉。</p>
     */
    public List<Hit> rollPlan(DropTable t, double luck, Random rnd, int killerTier) {
        return rollPlan(t, luck, rnd, killerTier, null, null);
    }

    /** Deterministic drop-table roll with the killer context used by table conditions. */
    public List<Hit> rollPlan(DropTable t, double luck, Random rnd,
                              org.bukkit.Location location, Player killer) {
        return rollPlan(t, luck, rnd, tierOf(killer), location, killer, 0);
    }

    /** Rolls with independent killer-tier and mob-level inputs. */
    public List<Hit> rollPlan(DropTable t, double luck, Random rnd,
                              org.bukkit.Location location, Player killer, int mobLevel) {
        return rollPlan(t, luck, rnd, tierOf(killer), location, killer, mobLevel, null);
    }

    public List<Hit> rollPlan(DropTable t, double luck, Random rnd,
                              org.bukkit.Location location, Player killer, int mobLevel,
                              org.bukkit.entity.Entity dropper) {
        return rollPlan(t, luck, rnd, tierOf(killer), location, killer, mobLevel, dropper);
    }

    /** Rolls with an already resolved killer tier and no mob-level bonus. */
    public List<Hit> rollPlan(DropTable t, double luck, Random rnd, int killerTier,
                              org.bukkit.Location location, Player killer) {
        return rollPlan(t, luck, rnd, killerTier, location, killer, 0);
    }

    /** Rolls with a pre-resolved killer tier and the managed mob's configured level. */
    public List<Hit> rollPlan(DropTable t, double luck, Random rnd, int killerTier,
                              org.bukkit.Location location, Player killer, int mobLevel) {
        return rollPlan(t, luck, rnd, killerTier, location, killer, mobLevel, null);
    }

    public List<Hit> rollPlan(DropTable t, double luck, Random rnd, int killerTier,
                              org.bukkit.Location location, Player killer, int mobLevel,
                              org.bukkit.entity.Entity dropper) {
        return rollPlanInternal(t, luck, rnd, killerTier, location, killer, mobLevel, dropper);
    }

    private List<Hit> rollPlanInternal(DropTable t, double luck, Random rnd, int killerTier,
                                       org.bukkit.Location location, Player killer, int mobLevel,
                                       org.bukkit.entity.Entity dropper) {
        return rollPlan(t, luck, rnd, killerTier, new HashSet<>(), 0, location, killer, mobLevel, dropper);
    }

    private List<Hit> rollPlan(DropTable t, double luck, Random rnd, int killerTier,
                               Set<String> stack, int depth,
                               org.bukkit.Location location, Player killer, int mobLevel,
                               org.bukkit.entity.Entity dropper) {
        if (t == null || t.isEmpty() || !triggerConditionsPass(t.triggerConditions(), location, killer)
                || !dropperConditionsPass(t.conditions(), location, dropper)) {
            return List.of();
        }
        if (depth >= 32 || !stack.add(t.name())) return List.of();
        List<Hit> hits = new ArrayList<>(t.entries().size());
        try {
            int bonusLines = 0;
            if (t.hasBonusLevelItems() || t.hasBonusLuckItems()) {
                double bonus = mobLevel * sampledMultiplier(t.bonusLevelItemsMin(), t.bonusLevelItemsMax(), rnd)
                        + luck * sampledMultiplier(t.bonusLuckItemsMin(), t.bonusLuckItemsMax(), rnd);
                bonusLines = sampleBonusLines(bonus, rnd);
            }
            if (t.usesWeightedSelection()) {
                int baseLines;
                if (t.totalItems() > 0) baseLines = t.totalItems();
                else if (t.minItems() > 0 && t.maxItems() > 0) {
                    baseLines = randomInclusive(t.minItems(), t.maxItems(), rnd);
                } else {
                    baseLines = Math.max(t.minItems(), t.maxItems());
                }
                long requestedLines = Math.max(0L, (long) baseLines + bonusLines);
                int lines = (int) Math.min(Integer.MAX_VALUE, requestedLines);
                List<WeightedEntry> pool = weightedPool(t, luck, killerTier, location, killer);
                if (pool.isEmpty()) return List.of();
                double scale = pool.stream().mapToDouble(WeightedEntry::weight).max().orElseThrow();
                double totalWeight = pool.stream().mapToDouble(e -> e.weight() / scale).sum();
                for (int line = 0; line < lines; line++) {
                    DropTable.Entry selected = pickWeighted(pool, totalWeight, scale, rnd);
                    List<Hit> generated = rollSelectedEntry(selected, luck, rnd, killerTier,
                            stack, depth, location, killer, mobLevel, dropper);
                    for (Hit hit : generated) {
                        if (hits.size() >= lines) break;
                        hits.add(hit);
                    }
                    if (hits.size() >= lines) break;
                }
                return List.copyOf(hits);
            }

            int lineLimit = t.maxItems() > 0
                    ? (int) Math.min(Integer.MAX_VALUE, Math.max(0L, (long) t.maxItems() + bonusLines))
                    : Integer.MAX_VALUE;
            int minimumLines = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0L, (long) t.minItems() + bonusLines));
            for (DropTable.Entry e : t.entries()) {
                if (hits.size() >= lineLimit) break;
                List<Hit> generated = rollEntry(e, t, luck, rnd, killerTier,
                        stack, depth, location, killer, mobLevel, dropper);
                int remaining = lineLimit - hits.size();
                if (generated.size() > remaining) {
                    hits.addAll(generated.subList(0, remaining));
                } else {
                    hits.addAll(generated);
                }
                if (t.minItems() > 0 && hits.size() >= minimumLines) break;
            }
            return hits;
        } finally {
            stack.remove(t.name());
        }
    }

    private record WeightedEntry(DropTable.Entry entry, double weight) { }

    private List<WeightedEntry> weightedPool(DropTable table, double luck, int killerTier,
                                              org.bukkit.Location location, Player killer) {
        List<WeightedEntry> pool = new ArrayList<>();
        for (DropTable.Entry entry : table.entries()) {
            if (entry.isExperienceDrop()) continue;
            if (entry.minTierLevel() != null && killerTier >= 0 && killerTier < entry.minTierLevel()) continue;
            if (!conditionsPass(entry, location, killer)) continue;
            double weight = entry.chance();
            if (entry.chanceExpression() != null) {
                DynamicChanceEvaluator evaluator = dynamicChanceEvaluator;
                if (evaluator == null) continue;
                try {
                    weight = evaluator.evaluate(entry.chanceExpression(), location, killer, luck);
                } catch (RuntimeException ignored) {
                    continue;
                }
            }
            if (Double.isFinite(weight) && weight > 0) pool.add(new WeightedEntry(entry, weight));
        }
        return pool;
    }

    private static DropTable.Entry pickWeighted(List<WeightedEntry> pool, double totalWeight,
                                                 double scale, Random rnd) {
        double pick = rnd.nextDouble() * totalWeight;
        for (WeightedEntry candidate : pool) {
            pick -= candidate.weight() / scale;
            if (pick < 0) return candidate.entry();
        }
        return pool.get(pool.size() - 1).entry();
    }

    private static double sampledMultiplier(double min, double max, Random rnd) {
        if (max <= min) return min;
        return min + rnd.nextDouble() * (max - min);
    }

    private static int sampleBonusLines(double bonus, Random rnd) {
        if (!Double.isFinite(bonus)) return bonus > 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        if (bonus >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (bonus <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        double floor = Math.floor(bonus);
        int whole = (int) floor;
        double fractional = bonus - floor;
        return whole + (fractional > 0 && rnd.nextDouble() < fractional ? 1 : 0);
    }

    private List<Hit> rollSelectedEntry(DropTable.Entry entry, double luck, Random rnd, int killerTier,
                                        Set<String> stack, int depth,
                                        org.bukkit.Location location, Player killer, int mobLevel,
                                        org.bukkit.entity.Entity dropper) {
        if (entry.itemId().regionMatches(true, 0, "table:", 0, 6)) {
            DropTable child = table(entry.itemId().substring(6).trim());
            return child == null ? List.of()
                    : rollPlan(child, luck, rnd, killerTier, stack, depth + 1, location, killer,
                            mobLevel, dropper);
        }
        return List.of(new Hit(entry, pickAmount(entry, rnd)));
    }

    private List<Hit> rollEntry(DropTable.Entry entry, DropTable table, double luck, Random rnd,
                                 int killerTier, Set<String> stack, int depth,
                                 org.bukkit.Location location, Player killer, int mobLevel,
                                 org.bukkit.entity.Entity dropper) {
        if (entry.isExperienceDrop()) return List.of();
        if (entry.minTierLevel() != null && killerTier >= 0 && killerTier < entry.minTierLevel()) {
            return List.of();
        }
        double chance = entry.chance();
        if (entry.chanceExpression() != null) {
            DynamicChanceEvaluator evaluator = dynamicChanceEvaluator;
            if (evaluator == null) return List.of();
            try {
                chance = evaluator.evaluate(entry.chanceExpression(), location, killer, luck);
            } catch (RuntimeException ignored) {
                return List.of();
            }
        }
        if (!Double.isFinite(chance)) return List.of();
        if (entry.luckScaling()) {
            chance = clampChance(chance + Math.max(0, luck) * table.luckFactor(), 0, 1);
        }
        if (rnd.nextDouble() >= chance || !conditionsPass(entry, location, killer)) return List.of();
        return rollSelectedEntry(entry, luck, rnd, killerTier, stack, depth, location, killer,
                mobLevel, dropper);
    }

    private static int randomInclusive(int min, int max, Random rnd) {
        if (max <= min) return min;
        long width = (long) max - min + 1;
        return (int) (min + rnd.nextLong(width));
    }

    private boolean conditionsPass(DropTable.Entry entry, org.bukkit.Location location, Player killer) {
        return conditionsPass(entry.conditions(), location, killer);
    }

    private boolean triggerConditionsPass(List<String> conditions, org.bukkit.Location location,
                                          Player killer) {
        return conditions.isEmpty() || (killer != null && conditionsPass(conditions, location, killer));
    }

    private boolean dropperConditionsPass(List<String> conditions, org.bukkit.Location location,
                                          org.bukkit.entity.Entity dropper) {
        if (conditions == null || conditions.isEmpty()) return true;
        DropperConditionEvaluator evaluator = dropperConditionEvaluator;
        if (dropper == null || evaluator == null) return false;
        for (String condition : conditions) {
            if (condition == null || condition.isBlank()) return false;
            try {
                if (!evaluator.test(condition, location, dropper)) return false;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return true;
    }

    private boolean conditionsPass(List<String> conditions, org.bukkit.Location location, Player killer) {
        if (conditions == null || conditions.isEmpty()) return true;
        ConditionEvaluator evaluator = conditionEvaluator;
        if (evaluator == null) return false;
        for (String condition : conditions) {
            if (condition == null || condition.isBlank()) return false;
            try {
                if (!evaluator.test(condition, location, killer)) return false;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按分层权重抽数量；未配置档位时退回原来的均匀区间。
     *
     * <p>分母为 0 时退回均匀区间：权重全是非正数属于配置错误，但不该让掉落整条消失。</p>
     */
    private static int pickAmount(DropTable.Entry e, Random rnd) {
        if (!e.hasTiers()) {
            return e.amountMin() >= e.amountMax()
                    ? e.amountMin()
                    : e.amountMin() + rnd.nextInt(e.amountMax() - e.amountMin() + 1);
        }
        double scale = 0;
        for (DropTable.Tier t : e.tiers()) scale = Math.max(scale, t.weight());
        double total = 0;
        for (DropTable.Tier t : e.tiers()) total += t.weight() / scale;
        if (total <= 0) return e.amountMin();

        double roll = rnd.nextDouble() * total;
        DropTable.Tier chosen = e.tiers().get(e.tiers().size() - 1);
        double acc = 0;
        for (DropTable.Tier t : e.tiers()) {
            acc += t.weight() / scale;
            if (roll < acc) {
                chosen = t;
                break;
            }
        }
        return chosen.amountMin() >= chosen.amountMax()
                ? chosen.amountMin()
                : chosen.amountMin() + rnd.nextInt(chosen.amountMax() - chosen.amountMin() + 1);
    }

    /**
     * 把命中结果实体化为物品。自定义解析器优先；只有无命名空间或 minecraft
     * 命名空间的 ID 可以回退到原版材质。
     */
    public List<ItemStack> materialize(List<Hit> hits) {
        List<ItemStack> out = new ArrayList<>();
        for (Hit h : hits) {
            ItemStack base = resolve(h.entry().itemId());
            if (base == null || base.getType() == Material.AIR) continue;
            ItemStack stack = base.clone();
            stack.setAmount(h.amount());
            applyMeta(stack, h.entry());
            out.add(stack);
        }
        return out;
    }

    private ItemStack resolve(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        java.util.function.Function<String, ItemStack> r = customItemResolver;
        if (r != null) {
            try {
                ItemStack custom = r.apply(itemId);
                if (custom != null && custom.getType() != Material.AIR) return custom;
            } catch (Throwable ignored) {
            }
        }
        if (claimedItem.test(itemId)) return null;
        int separator = itemId.indexOf(':');
        if (separator >= 0 && !itemId.substring(0, separator).equalsIgnoreCase("minecraft")) return null;
        String bare = separator >= 0 ? itemId.substring(separator + 1) : itemId;
        Material m = Material.matchMaterial(bare.toUpperCase(Locale.ROOT));
        return m == null ? null : new ItemStack(m);
    }

    private void applyMeta(ItemStack stack, DropTable.Entry e) {
        try {
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) return;
            boolean touched = false;
            if (e.displayName() != null && !e.displayName().isBlank()) {
                meta.setDisplayName(e.displayName());
                touched = true;
            }
            if (e.customModelData() != null && e.customModelData() > 0) {
                meta.setCustomModelData(e.customModelData());
                touched = true;
            }
            if (e.glow()) {
                meta.setEnchantmentGlintOverride(true);
                touched = true;
            }
            for (Map.Entry<String, Integer> en : e.enchantments().entrySet()) {
                var type = org.bukkit.enchantments.Enchantment.getByName(en.getKey().toLowerCase(Locale.ROOT));
                if (type != null) meta.addEnchant(type, en.getValue(), true);
                else if (log != null) log.warning("[掉落] 未知附魔 " + en.getKey() + "，已忽略");
                touched = true;
            }
            if (touched) stack.setItemMeta(meta);
        } catch (Throwable t) {
            if (log != null) log.warning("[掉落] 写入物品元数据失败: " + t);
        }
    }

    /** 在世界里生成掉落物并做自然拾取。需要主线程。 */
    public void drop(org.bukkit.Location loc, List<ItemStack> items) {
        if (loc == null || loc.getWorld() == null || items.isEmpty()) return;
        for (ItemStack it : items) {
            try {
                loc.getWorld().dropItemNaturally(loc, it);
            } catch (Throwable t) {
                if (log != null) log.warning("[掉落] 生成掉落物失败: " + t);
            }
        }
    }

    /** 便捷方法：掷骰 + 实体化 + 落地。 */
    public int rollAndDrop(org.bukkit.Location loc, String tableName, double luck) {
        return rollAndDrop(loc, tableName, luck, null);
    }

    /**
     * 掷骰 + 实体化 + 落地，并按击杀者档位套用 {@code min-tier-level} 门槛。
     *
     * <p>killer 为 null 时按档位未知处理：门槛全部放行，而不是因为拿不到
     * 击杀者就把整张表的高端掉落吞掉。</p>
     */
    public int rollAndDrop(org.bukkit.Location loc, String tableName, double luck, Player killer) {
        DropTable t = table(tableName);
        List<ItemStack> items = materialize(rollPlan(t, luck, random, tierOf(killer), loc, killer));
        drop(loc, items);
        return items.size();
    }

    /**
     * 掷骰 + 实体化 + 落地，并合并击杀者档位与 mob 等级为有效档位。
     *
     * <p>有效档位取 {@code max(玩家档位, mob等级)}：高等级 mob 的稀有掉落不会因为
     * 低档位玩家击杀而凭空出现，同时低等级 mob 的高端掉落也不会被高等级玩家意外跳过。
     * 两者都为负时回落为「未知」，门槛全部放行。</p>
     */
    public int rollAndDrop(org.bukkit.Location loc, String tableName, double luck,
                           Player killer, int mobLevel) {
        return rollAndDrop(loc, tableName, luck, killer, mobLevel, null);
    }

    public int rollAndDrop(org.bukkit.Location loc, String tableName, double luck,
                           Player killer, int mobLevel, org.bukkit.entity.Entity dropper) {
        List<ItemStack> items = rollItems(loc, tableName, luck, killer, mobLevel, dropper);
        drop(loc, items);
        return items.size();
    }

    public List<ItemStack> rollItems(org.bukkit.Location loc, String tableName, double luck,
                                     Player killer, int mobLevel, org.bukkit.entity.Entity dropper) {
        DropTable t = table(tableName);
        return materialize(rollPlan(t, luck, random, effectiveTier(tierOf(killer), mobLevel),
                loc, killer, mobLevel, dropper));
    }

    private static int effectiveTier(int playerTier, int mobLevel) {
        if (mobLevel <= 0) return playerTier;
        return playerTier < 0 ? mobLevel : Math.max(playerTier, mobLevel);
    }

    /**
     * 取击杀者档位；解析器缺失、抛异常或返回负值时一律视为未知（-1）。
     *
     * <p>档位未知必须与档位 0 区分开：门槛配置写的是「至少 5 档才能掉」，
     * 拿不到档位时按 0 处理会让击杀奖励凭空消失。</p>
     */
    public int tierOf(Player killer) {
        TierResolver r = tierResolver;
        if (r == null || killer == null) return -1;
        try {
            int tier = r.tierOf(killer);
            return tier < 0 ? -1 : tier;
        } catch (Throwable t) {
            if (log != null) log.warning("[掉落] 解析击杀者档位失败，按未知处理: " + t);
            return -1;
        }
    }
}
