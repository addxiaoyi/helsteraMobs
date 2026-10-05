package dev.helstera.ai.lever;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拉杆服务：管理拉杆定义、区域命中与冷却。
 *
 * <p><b>冷却用 UUID 而非方块位置</b>：位置可能被两个不同拉杆覆盖，而玩家维度
 * 只能有一个 UUID。用位置做键会让「站在交叉区域里」触发两个拉杆的冷却互相顶掉，
 * 表现为冷却看似失效。</p>
 *
 * <p><b>区域命中取首个匹配而非全部</b>：交叉配置下同时触发多个拉杆会让一次交互
 * 引发多组动作，现场极难复现。取首个 + 告警更可控。</p>
 */
public final class LeverService {

    /**
     * 单个拉杆定义。
     *
     * @param id 拉杆名（配置键）
     * @param region 作用区域
     * @param triggers 触发后要执行的动作文本（沿用技能动作语法）
     * @param cooldownTicks 冷却 tick；<=0 表示无冷却
     * @param permission 所需权限；null 或空表示所有人可用
     */
    public record Lever(String id, LeverRegion region, List<String> triggers,
                        int cooldownTicks, String permission) {
        public Lever {
            triggers = triggers == null ? List.of() : List.copyOf(triggers);
            permission = permission == null || permission.isBlank() ? null : permission.trim();
            cooldownTicks = Math.max(0, cooldownTicks);
        }
    }

    /** 命中结果：命中的拉杆 + 失败原因。 */
    public record Hit(Lever lever, String reason) {
        public boolean ok() {
            return lever != null;
        }

        public static Hit fail(String reason) {
            return new Hit(null, reason);
        }
    }

    private final List<Lever> levers = new ArrayList<>();
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    /** 冷却时钟注入，便于单测推进时间。 */
    private final java.util.function.LongSupplier clock;

    public LeverService() {
        this(System::currentTimeMillis);
    }

    public LeverService(java.util.function.LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * 装载拉杆定义。重复 id <b>由后者真正覆盖</b>并告警。
     *
     * <p>此前是「告警说覆盖、实际两个都留着」，于是一次点击触发两组动作。
     * 文案说谎比不告警更糟：服主会以为已覆盖而查不出重复配置。</p>
     */
    public void load(List<Lever> source) {
        levers.clear();
        cooldowns.clear();
        warnings.clear();
        if (source == null) return;
        // 用 LinkedHashMap 保证「后者覆盖」且保留首次出现的顺序
        var byId = new java.util.LinkedHashMap<String, Lever>();
        for (Lever l : source) {
            if (l == null || l.id() == null || l.id().isBlank()) {
                warnings.add("拉杆缺少 id，已跳过");
                continue;
            }
            if (l.region() == null) {
                warnings.add("拉杆 " + l.id() + " 缺少区域，已跳过");
                continue;
            }
            String id = l.id();
            if (byId.containsKey(id)) warnings.add("拉杆 " + id + " 重复声明，后者覆盖前者");
            if (l.region().volume() > 4096) {
                warnings.add("拉杆 " + id + " 区域体积 " + l.region().volume()
                        + " 格偏大，坐标可能写错");
            }
            if (l.triggers().isEmpty()) {
                // 配置写完不报错、按下去什么也不发生——与死配置触发器同类
                warnings.add("拉杆 " + id + " 没有配置任何动作");
            }
            byId.put(id, l);
        }
        levers.addAll(byId.values());
    }

    public List<Lever> levers() {
        return List.copyOf(levers);
    }

    public int size() {
        return levers.size();
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * 尝试在某格点触发。
     *
     * <p>顺序固定为：权限 → 命中 → 冷却。权限先于命中，是为了不把「你没权限」
     * 表现成「这里没拉杆」——后者会让人以为坐标写错了。</p>
     */
    public Hit trigger(String playerUuid, String world, int x, int y, int z,
                       java.util.function.Predicate<String> hasPermission) {
        Lever found = null;
        for (Lever l : levers) {
            if (l.region().contains(world, x, y, z)) {
                found = l;
                break;
            }
        }
        if (found == null) return Hit.fail("该位置没有拉杆");
        if (found.permission() != null) {
            // 权限由调用方以谓词注入：服务层不依赖 Bukkit 权限系统，
            // 这段逻辑才能在无服务端环境下测试
            if (hasPermission == null || !hasPermission.test(found.permission())) {
                return Hit.fail("权限不足");
            }
        }
        long now = clock.getAsLong();
        Long until = cooldowns.get(playerUuid + "#" + found.id());
        if (until != null && now < until) {
            return Hit.fail("冷却中（剩余 " + ((until - now + 49) / 50) + " 秒）");
        }
        if (found.cooldownTicks() > 0) {
            cooldowns.put(playerUuid + "#" + found.id(),
                    now + found.cooldownTicks() * 50L);
        }
        return new Hit(found, null);
    }

    /** 清空冷却（reload 时调用）。 */
    public void resetCooldowns() {
        cooldowns.clear();
    }

    /**
     * 从配置节装载拉杆定义。
 *
 * <p>区域支持两种写法：{@code pos1}/{@code pos2} 给立方体，或 {@code at} 给单点。
 * 两者都不存在时告警并跳过——静默忽略会让服主以为拉杆已配好。</p>
     */
    public static List<Lever> fromSection(org.bukkit.configuration.ConfigurationSection root) {
        List<Lever> out = new ArrayList<>();
        if (root == null) return out;
        for (String id : root.getKeys(false)) {
            var s = root.getConfigurationSection(id);
            if (s == null) continue;
            LeverRegion region = null;
            if (s.isConfigurationSection("pos1") && s.isConfigurationSection("pos2")) {
                var a = s.getConfigurationSection("pos1");
                var b = s.getConfigurationSection("pos2");
                region = LeverRegion.of(
                        s.getString("world", ""),
                        a.getInt("x"), a.getInt("y"), a.getInt("z"),
                        b.getInt("x"), b.getInt("y"), b.getInt("z"));
            } else if (s.contains("at")) {
                List<Integer> at = s.getIntegerList("at");
                if (at.size() >= 3) {
                    region = LeverRegion.block(s.getString("world", ""), at.get(0), at.get(1), at.get(2));
                }
            }
            out.add(new Lever(id, region, s.getStringList("actions"),
                    s.getInt("cooldown", 0), s.getString("permission")));
        }
        return out;
    }

    /** 归一化材质/事件名，供配置解析复用。 */
    public static String norm(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }
}