package dev.helstera.ai.loot;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
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

    private final Map<String, DropTable> tables = new ConcurrentHashMap<>();
    private final Logger log;
    private final Random random = new Random();
    private final List<String> problems = new ArrayList<>();
    /** 可选：自定义命名空间物品解析器（ItemAdder / CraftEngine 接入点）。 */
    private volatile java.util.function.Function<String, ItemStack> customItemResolver;
    /** 击杀者档位解析；未注入时按「未知」处理，门槛型掉落照常参与掷骰。 */
    private volatile TierResolver tierResolver;

    public LootService(Logger log) {
        this.log = log;
    }

    /** 注入击杀者档位解析器，使 {@code min-tier-level} 在运行期生效。 */
    public void setTierResolver(TierResolver resolver) {
        this.tierResolver = resolver;
    }

    /**
     * 注入自定义物品解析器。返回 null 表示"该 ID 解析不了"，服务会回落到
     * 原版材质匹配。这样 ItemAdder / CraftEngine 未安装时掉落表仍能工作。
     */
    public void setCustomItemResolver(java.util.function.Function<String, ItemStack> resolver) {
        this.customItemResolver = resolver;
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
        return rollPlan(t, luck, rnd, -1);
    }

    /**
     * 带击杀者档位的纯函数重载。
     *
     * <p>{@code killerTier} 小于 0 表示击杀者未知：不套用任何 tier 门槛，
     * 免得因为拿不到档位就把所有带门槛的掉落全吞掉。</p>
     */
    public List<Hit> rollPlan(DropTable t, double luck, Random rnd, int killerTier) {
        if (t == null || t.isEmpty()) return List.of();
        List<Hit> hits = new ArrayList<>(t.entries().size());
        for (DropTable.Entry e : t.entries()) {
            // 击杀者档位门槛：不满足时直接跳过，连 chance 都不掷
            if (e.minTierLevel() != null && killerTier >= 0 && killerTier < e.minTierLevel()) {
                continue;
            }
            double chance = e.chance();
            if (e.luckScaling()) {
                // 幸运值只往上加成，永不把 chance 推到 1 以外
                chance = clampChance(chance + Math.max(0, luck) * t.luckFactor(), 0, 1);
            }
            if (rnd.nextDouble() >= chance) continue;
            hits.add(new Hit(e, pickAmount(e, rnd)));
        }
        return hits;
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
        double total = 0;
        for (DropTable.Tier t : e.tiers()) total += t.weight();
        if (total <= 0) return e.amountMin();

        double roll = rnd.nextDouble() * total;
        DropTable.Tier chosen = e.tiers().get(e.tiers().size() - 1);
        double acc = 0;
        for (DropTable.Tier t : e.tiers()) {
            acc += t.weight();
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
        List<ItemStack> items = materialize(rollPlan(t, luck, random, tierOf(killer)));
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
        DropTable t = table(tableName);
        int playerTier = tierOf(killer);
        // 两者都未知 → -1；否则取最大值（任一已知都算有效档位）
        int effectiveTier;
        if (playerTier < 0 && mobLevel <= 0) {
            effectiveTier = -1;
        } else if (playerTier < 0) {
            effectiveTier = mobLevel;
        } else if (mobLevel <= 0) {
            effectiveTier = playerTier;
        } else {
            effectiveTier = Math.max(playerTier, mobLevel);
        }
        List<ItemStack> items = materialize(rollPlan(t, luck, random, effectiveTier));
        drop(loc, items);
        return items.size();
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