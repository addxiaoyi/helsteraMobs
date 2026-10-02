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
                        String note) {
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
        double luckFactor = clamp(sec.getDouble("luck-factor", 0.05), 0, 1);
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
                entries.add(new Entry(
                        item.trim(), min, max, chance,
                        es.getBoolean("luck-scaling", true),
                        Collections.unmodifiableMap(ench),
                        es.getString("name"),
                        cmd,
                        es.getBoolean("glow", false),
                        es.getString("note")));
            }
        }
        return new DropTable(name, luckFactor, entries);
    }

    static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
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