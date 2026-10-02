package dev.helstera.ai.loot;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
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

    private final Map<String, DropTable> tables = new ConcurrentHashMap<>();
    private final Logger log;
    private final Random random = new Random();
    private final List<String> problems = new ArrayList<>();
    /** 可选：自定义命名空间物品解析器（ItemAdder / CraftEngine 接入点）。 */
    private volatile java.util.function.Function<String, ItemStack> customItemResolver;

    public LootService(Logger log) {
        this.log = log;
    }

    /**
     * 注入自定义物品解析器。返回 null 表示"该 ID 解析不了"，服务会回落到
     * 原版材质匹配。这样 ItemAdder / CraftEngine 未安装时掉落表仍能工作。
     */
    public void setCustomItemResolver(java.util.function.Function<String, ItemStack> resolver) {
        this.customItemResolver = resolver;
    }

    /** 装载 {@code loot.yml} 的 {@code tables} 节。 */
    public void load(ConfigurationSection tablesSec) {
        tables.clear();
        if (tablesSec == null) return;
        for (String key : tablesSec.getKeys(false)) {
            ConfigurationSection sec = tablesSec.getConfigurationSection(key);
            if (sec == null) continue;
            DropTable t = DropTable.parse(key.toLowerCase(Locale.ROOT), sec, problems);
            tables.put(t.name(), t);
        }
        if (!problems.isEmpty()) {
            for (String p : problems) {
                if (log != null) log.warning("[掉落] " + p);
            }
        }
        if (log != null) log.info("已装载掉落表 " + tables.size() + " 张");
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

    public List<String> warnings() {
        return List.copyOf(problems);
    }

    /**
     * 掷骰：按每条的 chance 决定是否掉落，并决定数量。纯函数，不产生副作用。
     *
     * @param tableName 掉落表名
     * @param luck      幸运值（如击杀者的抢夺等级），提升 chance-scaling 条目的概率
     */
    public List<Hit> rollPlan(String tableName, double luck) {
        DropTable t = table(tableName);
        if (t == null || t.isEmpty()) return List.of();
        return rollPlan(t, luck, random);
    }

    /** 可注入随机源的纯函数重载，供单元测试使用。 */
    public List<Hit> rollPlan(DropTable t, double luck, Random rnd) {
        if (t == null || t.isEmpty()) return List.of();
        List<Hit> hits = new ArrayList<>(t.entries().size());
        for (DropTable.Entry e : t.entries()) {
            double chance = e.chance();
            if (e.luckScaling()) {
                // 幸运值只往上加成，永不把 chance 推到 1 以外
                chance = DropTable.clamp(chance + Math.max(0, luck) * t.luckFactor(), 0, 1);
            }
            if (rnd.nextDouble() >= chance) continue;
            int amount = e.amountMin() >= e.amountMax()
                    ? e.amountMin()
                    : e.amountMin() + rnd.nextInt(e.amountMax() - e.amountMin() + 1);
            hits.add(new Hit(e, Math.max(1, amount)));
        }
        return hits;
    }

    /**
     * 把命中结果实体化为物品。原版材质按名称匹配；带命名空间的 ID 先交给
     * 自定义解析器（ItemAdder / CraftEngine），解析不到再尝试去掉命名空间匹配材质。
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
        String bare = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
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
        List<ItemStack> items = materialize(rollPlan(tableName, luck));
        drop(loc, items);
        return items.size();
    }
}