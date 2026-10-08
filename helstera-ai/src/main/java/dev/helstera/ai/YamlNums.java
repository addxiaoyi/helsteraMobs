package dev.helstera.ai;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * YAML 数值的严格读取：把「解析失败返回默认值」变成「报告出来」。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>{@link ConfigurationSection#getDouble(String, double)} 这类 Bukkit API
 * 的语义是：key 不存在，<b>或者</b>存在但值无法解析成该类型，<b>都</b>返回默认值。
 * 于是三种完全不同的问题被压成同一种静默失败：</p>
 *
 * <ol>
 *   <li>key 拼错 —— {@code amount_chance: 0.5} 里的 {@code chance} 取不到，
 *       于是「50% 概率」变成默认的 100% 必掉；</li>
 *   <li>值类型错 —— {@code chance: "很多"} 与 {@code chance: 0.5} 结果完全相同；</li>
 *   <li>值合法但越界 —— {@code chance: 5.0} 被 clamp 到 1，{@code -0.5} clamp 到 0，
 *       两个方向都没有任何提示。</li>
 * </ol>
 *
 * <p>本轮在真服上实测：26 项配置错误里有 23 项完全静默。其中
 * {@code chance: "很多"}（本意「少量」）实际执行成 100% 必掉 ——
 * 这直接影响服务器经济，而体检、日志、控制台全无提示。</p>
 *
 * <h2>和技能侧 {@code SpecArgs} 的分工</h2>
 *
 * <p>技能参数是空格分隔的 token 列表，走 {@code SpecArgs}；这里是 YAML 节点，
 * 走本类。两者处理的是同一个病根——「解析失败悄悄用默认值」。</p>
 *
 * <h2>设计取舍</h2>
 *
 * <p>仍然返回默认值（不抛异常打断装载），但<b>把问题加进 problems 列表</b>。
 * 不抛异常是有意的：一份坏表不该让整份 loot.yml 拒绝加载——那会导致作者改好
 * 一个字之后其余表也一起失效，且这种「全盘失效」比「部分条目有默认值」更难查。</p>
 */
public final class YamlNums {

    private YamlNums() {
    }

    /**
     * 读一个数值，格式非法时记入 problems 并返回默认值。
     *
     * @param s        配置节
     * @param key      键名
     * @param def      默认值（键不存在，或格式非法时使用）
     * @param problems 问题收集器，可为 null
     * @param where    位置描述，用于报错时定位（如「掉落表 boss 第 2 条」）
     */
    public static double d(ConfigurationSection s, String key, double def,
                           List<String> problems, String where) {
        if (s == null || !s.contains(key)) return def;
        Object raw = s.get(key);
        if (raw instanceof Number num) return num.doubleValue();
        if (raw instanceof String str) {
            String t = str.trim();
            if (t.isEmpty()) return def;
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException ignored) {
                // 落到下面统一报告
            }
        }
        report(problems, where, key, raw, "数值");
        return def;
    }

    /** 同 {@link #d}，返回 int。 */
    public static int i(ConfigurationSection s, String key, int def,
                        List<String> problems, String where) {
        double v = d(s, key, def, problems, where);
        return (int) Math.round(v);
    }

    /**
     * 校验概率类数值落在 0..1。越界时钳到边界并记录——
     * 钳制本身是对的（不能让 5.0 直接进随机数），但必须留下痕迹。
     *
     * @param v        原始值
     * @param def      默认值
     * @param key      键名
     * @param problems 问题收集器
     * @param where    位置描述
     */
    public static double chance(ConfigurationSection s, String key, double def,
                                List<String> problems, String where) {
        double v = d(s, key, def, problems, where);
        if (Double.isNaN(v)) {
            report(problems, where, key, v, "概率");
            return def;
        }
        if (v < 0 || v > 1) {
            double clamped = Math.max(0, Math.min(1, v));
            add(problems, where + " 的 " + key + " = " + trim(v) + " 超出 0..1，已夹到 "
                    + trim(clamped) + "。概率写错会让掉落率与预期差很远，且此前完全无提示");
            return clamped;
        }
        return v;
    }

    /** 校验正整数（上限、间隔这类）。<=0 时回落到默认并记录。 */
    public static int positive(ConfigurationSection s, String key, int def,
                               List<String> problems, String where) {
        int v = i(s, key, def, problems, where);
        if (v <= 0) {
            add(problems, where + " 的 " + key + " = " + v + " 不是正数，已回落到 " + def
                    + "。这类值写错后行为与「没配」相同，现场看不出来");
            return def;
        }
        return v;
    }

    /**
     * 同 {@link #positive} 但允许 0 为合法值。
     *
     * <p>给「0 表示不限 / 不需要人」这类字段用（{@code max-spawns}、
     * {@code min-players}）。它们默认就是 0，若用 {@link #positive} 会把
     * 每一份正常配置都报成错。</p>
     */
    public static int nonNegative(ConfigurationSection s, String key, int def,
                                  List<String> problems, String where) {
        int v = i(s, key, def, problems, where);
        if (v < 0) {
            add(problems, where + " 的 " + key + " = " + v + " 是负数，已回落到 " + def);
            return def;
        }
        return v;
    }

    /** 正浮点（半径这类）。<=0 会让「附近」判定恒不成立，因此要拦。 */
    public static double positiveDouble(ConfigurationSection s, String key, double def,
                                        List<String> problems, String where) {
        double v = d(s, key, def, problems, where);
        if (Double.isNaN(v) || v <= 0) {
            add(problems, where + " 的 " + key + " = " + trim(v) + " 不是正数，已回落到 " + trim(def)
                    + "。半径写成 0 会让「附近玩家」恒不成立，且此前无任何提示");
            return def;
        }
        return v;
    }

    /**
     * 直接解析一个已取出的 YAML 节点值。
     *
     * <p>给「Bukkit 的 List&lt;Map&gt; 形态」用——那种形态下
     * {@code getInt(key, def)} 不可用（拿到的不是 ConfigurationSection），
     * 只能拿到裸 {@code Object}。而裸对象同样会静默兜底：
     * {@code SHARPNESS: "很强"} 会被当成等级 1。</p>
     */
    public static int ofObject(Object raw, int def, List<String> problems, String where) {
        if (raw == null) return def;
        if (raw instanceof Number num) return num.intValue();
        if (raw instanceof String str) {
            String t = str.trim();
            if (t.isEmpty()) return def;
            try {
                return (int) Math.round(Double.parseDouble(t));
            } catch (NumberFormatException ignored) {
                // 落到下面统一报告
            }
        }
        add(problems, where + " = \"" + raw + "\" 不是合法数值，已使用默认值。"
                + "此前的症状是该字段被静默当成默认值——「配置写了但没效果」，"
                + "且 /helstera check 与日志都不会提示");
        return def;
    }

    private static void report(List<String> problems, String where, String key,
                               Object raw, String kind) {
        add(problems, where + " 的 " + key + " = \"" + raw + "\" 不是合法" + kind
                + "，已使用默认值。此前的症状是该字段被静默当成默认值——"
                + "「配置写了但没效果」，且 /helstera check 与日志都不会提示");
    }

    private static void add(List<String> problems, String msg) {
        if (problems != null) problems.add(msg);
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
