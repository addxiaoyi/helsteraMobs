package dev.helstera.ai;

import dev.helstera.api.behavior.FactionTable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 阵营服务：从配置装载阵营与同盟关系，回答「这两个实体敌不敌对」。
 *
 * <p><b>为何模型实例要反查档案而不是自己存一份</b>：档案可在运行期被
 * {@code mobs/*.yml} 覆盖（网页端热改），实例上也存一份就必然出现两份不同步，
 * 而不同步的表现是「改了配置但部分生物还在打同伙」，极难排查。</p>
 *
 * <p><b>未归队 = 与所有人敌对</b>。这样不写 {@code faction} 的存量配置行为
 * 完全不变；反过来默认「无阵营=中立」会给所有配置套上一层意外的免伤。</p>
 *
 * <p>同盟关系在装载期展开成<b>对称闭包</b>：A 声明 B 为盟友，则 B 也自动把 A
 * 视为盟友。不要求作者写两遍——漏写一遍的典型表现是「A 不打 B，但 B 还打 A」，
 * 单看任一方向都像配置正确。</p>
 */
public final class FactionService implements FactionTable {

    /** 一个阵营的声明。 */
    public record Faction(String name, String display, Set<String> allies) {
    }

    private final Map<String, Faction> factions = new LinkedHashMap<>();
    /** 归一化名 -> 同盟集合（含自身），已展开为对称。 */
    private final Map<String, Set<String>> allyClosure = new HashMap<>();
    private final List<String> warnings = new ArrayList<>();
    /** 玩家默认阵营名；null 表示玩家不归队。 */
    private String playerFaction;
    /** 是否对同阵营伤害免疫。 */
    private boolean blockFriendlyFire = true;
    /** 模型实例的 UUID -> 阵营名缓存，随实例生成/销毁维护。 */
    private final Map<String, String> instanceFactions = new HashMap<>();

    /**
     * 阵营配置的纯数据形态（已从 YAML 读出，与 Bukkit 无关）。
     *
     * <p>刻意把「解析」与「装载」分成两步：{@link #load(ConfigurationSection)}
     * 只负责把 YAML 翻成这个结构，之后的同盟展开、校验、判定全是纯逻辑。
     * 这样这些逻辑能被单元测试直接覆盖，而测试里根本不需要 Bukkit
     * ——否则就只能起一个假服务端，测试比被测代码还脆。</p>
     *
     * @param players 玩家阵营名；null 表示玩家不归队
     * @param blockFriendlyFire 同阵营是否免伤
     * @param displays 阵营名 -> 显示名（可空）
     * @param allies 阵营名 -> 其声明的盟友名（可空）
     */
    public record Config(String players, boolean blockFriendlyFire,
                         Map<String, String> displays, Map<String, List<String>> allies) {
        public static Config empty() {
            return new Config(null, true, Map.of(), Map.of());
        }
    }

    /**
     * 装载已解析的配置（纯逻辑，不碰 Bukkit）。
     *
     * <p>同名不同阵营会被后者覆盖并告警；盟友名指向不存在的阵营会被忽略并告警。
     * 两者都不抛异常——配置文件里的一个笔误不该让整个服务器起不来。</p>
     */
    public void load(Config cfg) {
        factions.clear();
        allyClosure.clear();
        warnings.clear();
        Config c = cfg == null ? Config.empty() : cfg;
        playerFaction = AiProfile.trimToNull(c.players());
        blockFriendlyFire = c.blockFriendlyFire();

        Map<String, String> displays = c.displays() == null ? Map.of() : c.displays();
        Map<String, List<String>> allies = c.allies() == null ? Map.of() : c.allies();
        for (Map.Entry<String, String> e : displays.entrySet()) {
            String id = AiProfile.trimToNull(e.getKey());
            if (id == null) {
                warnings.add("阵营名不能为空，已跳过");
                continue;
            }
            id = id.toLowerCase(Locale.ROOT);
            String display = e.getValue() == null || e.getValue().isBlank() ? id : e.getValue();
            List<String> raw = allies.getOrDefault(e.getKey(), List.of());
            Set<String> set = new java.util.LinkedHashSet<>();
            for (String a : raw) {
                String al = AiProfile.trimToNull(a);
                if (al != null) set.add(al.toLowerCase(Locale.ROOT));
            }
            factions.put(id, new Faction(id, display, Set.copyOf(set)));
        }
        expandAllies();
    }

    /**
     * 从 Bukkit 配置节装载。
     *
     * @param root {@code ai.factions} 节；可为 null（等价于未配置任何阵营）
     */
    public void load(ConfigurationSection root) {
        if (root == null) {
            load(Config.empty());
            return;
        }
        Map<String, String> displays = new LinkedHashMap<>();
        Map<String, List<String>> allies = new LinkedHashMap<>();
        ConfigurationSection defs = root.getConfigurationSection("factions");
        if (defs != null) {
            for (String key : defs.getKeys(false)) {
                displays.put(key, defs.getString("display", key));
                ConfigurationSection sec = defs.getConfigurationSection(key);
                if (sec != null) allies.put(key, sec.getStringList("allies"));
            }
        }
        load(new Config(root.getString("players"),
                root.getBoolean("block-friendly-fire", true),
                displays, allies));
    }

    /** 展开对称闭包并校验盟友名是否存在。 */
    private void expandAllies() {
        allyClosure.clear();
        for (String id : factions.keySet()) allyClosure.put(id, new HashSet<>(Set.of(id)));
        for (Faction f : factions.values()) {
            for (String other : f.allies()) {
                if (!factions.containsKey(other)) {
                    warnings.add("阵营 \"" + f.display() + "\" 的盟友 \"" + other + "\" 未定义，将被忽略");
                    continue;
                }
                allyClosure.get(f.name()).add(other);
                // 对称化：作者只需写一个方向
                allyClosure.get(other).add(f.name());
            }
        }
        if (playerFaction != null && !factions.containsKey(playerFaction.toLowerCase(Locale.ROOT))) {
            warnings.add("factions.players 指向未定义的阵营 \"" + playerFaction + "\"");
        }
    }

    @Override
    public String factionOf(Entity entity) {
        if (entity == null) return null;
        // 先查模型实例：其阵营来自档案，可能随 mobs/*.yml 热改
        String cached = instanceFactions.get(entity.getUniqueId().toString());
        if (cached != null) return cached;
        if (entity instanceof Player) {
            return playerFaction == null ? null
                    : playerFaction.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    /** 登记模型实例的阵营；传 null 表示该实例不归队。 */
    public void bindInstance(UUID entityId, String faction) {
        if (entityId == null) return;
        if (faction == null) instanceFactions.remove(entityId.toString());
        else instanceFactions.put(entityId.toString(), faction.toLowerCase(Locale.ROOT));
    }

    /** 注销模型实例（销毁时调用），避免 map 随实例生成无限增长。 */
    public void unbindInstance(UUID entityId) {
        if (entityId != null) instanceFactions.remove(entityId.toString());
    }

    @Override
    public boolean allied(Entity a, Entity b) {
        if (a == null || b == null) return false;
        String fa = factionOf(a);
        if (fa == null) return false;
        Set<String> group = allyClosure.get(fa);
        // 未登记的阵营名（配置写错）视作独立阵营，只与自己同盟
        if (group == null) return fa.equals(factionOf(b));
        return group.contains(factionOf(b));
    }

    @Override
    public Collection<String> names() {
        return java.util.List.copyOf(factions.keySet());
    }

    /** 装载期告警，供 /helstera check 展示。 */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** 同阵营伤害是否免疫。 */
    public boolean blockFriendlyFire() {
        return blockFriendlyFire;
    }

    public void blockFriendlyFire(boolean on) {
        this.blockFriendlyFire = on;
    }

    public String playerFaction() {
        return playerFaction;
    }

    /** 阵营显示名；未登记返回 null。 */
    public String displayOf(String faction) {
        Faction f = factions.get(faction == null ? null : faction.toLowerCase(Locale.ROOT));
        return f == null ? null : f.display();
    }

    /** 同盟闭包快照（不可变），供命令层展示。 */
    public Set<String> alliesOf(String faction) {
        Set<String> g = allyClosure.get(faction == null ? null : faction.toLowerCase(Locale.ROOT));
        return g == null ? Set.of() : Set.copyOf(g);
    }

    }