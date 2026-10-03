package dev.helstera.ai.loot;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
            return weight > 0 && !Double.isNaN(weight)
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
                        Integer minTierLevel) {

        /** 兼容旧构造：单档均匀区间，无击杀者门槛。 */
        public Entry(String itemId, int amountMin, int amountMax, double chance, boolean luckScaling,
                     Map<String, Integer> enchantments, String displayName, Integer customModelData,
                     boolean glow, String note) {
            this(itemId, amountMin, amountMax, chance, luckScaling, enchantments, displayName,
                    customModelData, glow, note, List.of(), null);
        }

        /** 是否配置了分层权重。 */
        public boolean hasTiers() {
            return tiers != null && !tiers.isEmpty();
        }
    }

    private final String name;
    private final double luckFactor;
    private final List<Entry> entries;

    private DropTable(String name, double luckFactor, List<Entry> entries) {
        this.name = name;
        this.luckFactor = luckFactor;
        this.entries = List.copyOf(entries);
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
        double luckFactor = sec == null ? 0.05 : clamp(sec.getDouble("luck-factor", 0.05), 0, 1);
        List<Entry> entries = new ArrayList<>();
        if (sec != null) {
            int idx = 0;
            // Bukkit 的 YAML 里「列表中的映射」不是 ConfigurationSection，而是 List<Map>。
            // 只 instanceof ConfigurationSection 会让整张表静默变空——掉落全都不生效，
            // 而且没有任何报错。所以两种形态都要接。
            for (Object o : sec.getList("entries", List.of())) {
                idx++;
                ConfigurationSection es;
                if (o instanceof ConfigurationSection cs) {
                    es = cs;
                } else if (o instanceof Map<?, ?> map) {
                    es = asSection(map);
                } else {
                    problems.add("掉落表 " + name + " 第 " + idx + " 条不是映射（应写成 - item: ...），已跳过");
                    continue;
                }
                String item = es.getString("item");
                if (item == null || item.isBlank()) {
                    problems.add("掉落表 " + name + " 第 " + idx + " 条缺少 item，已跳过");
                    continue;
                }
                int min = Math.max(1, es.getInt("amount-min", 1));
                int max = Math.max(1, es.getInt("amount-max", min));
                if (max < min) {
                    problems.add("掉落表 " + name + " 第 " + idx + " 条 amount-max < amount-min，已交换");
                    int t = min;
                    min = max;
                    max = t;
                }
                double chance = clamp(es.getDouble("chance", 1.0), 0, 1);
                Map<String, Integer> ench = new LinkedHashMap<>();
                // 嵌套的 enchantments 同样是「列表项里的映射」，形态跟着父节点走
                Object encRaw = es.get("enchantments");
                if (encRaw instanceof ConfigurationSection encSec) {
                    for (String k : encSec.getKeys(false)) {
                        int lvl = encSec.getInt(k, 1);
                        if (lvl > 0) ench.put(k.toUpperCase(Locale.ROOT), lvl);
                    }
                } else if (encRaw instanceof Map<?, ?> encMap) {
                    for (Map.Entry<?, ?> en : encMap.entrySet()) {
                        if (en.getKey() == null) continue;
                        int lvl = toInt(en.getValue(), 1);
                        if (lvl > 0) ench.put(String.valueOf(en.getKey()).toUpperCase(Locale.ROOT), lvl);
                    }
                }
                Integer cmd = es.contains("custom-model-data") ? es.getInt("custom-model-data") : null;
                // 分层权重：同样是「列表项里的映射」，沿用上面的形态兼容处理
                List<Tier> tiers = new ArrayList<>();
                for (Object to : secList(es, "tiers")) {
                    ConfigurationSection ts = to instanceof ConfigurationSection cs
                            ? cs
                            : (to instanceof Map<?, ?> m ? asSection(m) : null);
                    if (ts == null) {
                        problems.add("掉落表 " + name + " 第 " + idx + " 条的 tiers 项不是映射，已跳过");
                        continue;
                    }
                    int tMin = Math.max(1, ts.getInt("amount-min", 1));
                    int tMax = Math.max(tMin, ts.getInt("amount-max", tMin));
                    Tier tier = new Tier(tMin, tMax, ts.getDouble("weight", 1.0), ts.getString("note"));
                    if (!tier.valid()) {
                        problems.add("掉落表 " + name + " 第 " + idx + " 条的档位 weight<=0 或区间非法，已跳过");
                        continue;
                    }
                    tiers.add(tier);
                }
                Integer minTier = null;
                if (es.contains("min-tier-level")) {
                    int v = es.getInt("min-tier-level");
                    if (v > 0) minTier = v;
                    else problems.add("掉落表 " + name + " 第 " + idx + " 条 min-tier-level 非正数，已忽略该门槛");
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
                        minTier));
            }
        }
        return new DropTable(name, luckFactor, entries);
    }

    static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
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