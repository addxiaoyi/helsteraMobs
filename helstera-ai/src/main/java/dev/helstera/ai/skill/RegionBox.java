package dev.helstera.ai.skill;

import java.util.Locale;

/**
 * 档案级 AABB 区域，供 on-enter-region / on-leave-region 求差分。
 *
 * <p><b>刻意与 {@code LeverRegion} 语义一致但不共用类</b>：拉杆区域是
 * 「世界名 + 格点范围」，本类额外支持小数坐标（生物移动是连续的，按格点
 * 判定会让生物在边界上反复进出）。两者格式一致是刻意的——服主不该为两套
 * 概念记两种坐标写法；但实现分开，因为精度需求不同。</p>
 *
 * <p>世界名为空表示不限世界。</p>
 */
public record RegionBox(String world, double x1, double y1, double z1,
                        double x2, double y2, double z2) {

    /** 规整为 min/max。坐标写反是最常见的配置错误，不规整会得到空区域。 */
    public static RegionBox of(String world, double x1, double y1, double z1,
                               double x2, double y2, double z2) {
        String w = world == null || world.isBlank() ? null : world.trim().toLowerCase(Locale.ROOT);
        return new RegionBox(w,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** 是否覆盖该点。world 为 null（不限）时只看坐标。 */
    public boolean contains(String w, double x, double y, double z) {
        if (world != null) {
            if (w == null || !world.equals(w.trim().toLowerCase(Locale.ROOT))) return false;
        }
        return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }

    /** 解析失败或坐标缺失时返回 null —— 调用方据此跳过区域判定而非判定为「不在区域内」。 */
    public static RegionBox parse(org.bukkit.configuration.ConfigurationSection s) {
        if (s == null) return null;
        try {
            double x1 = s.getDouble("x1");
            double y1 = s.getDouble("y1");
            double z1 = s.getDouble("z1");
            double x2 = s.getDouble("x2");
            double y2 = s.getDouble("y2");
            double z2 = s.getDouble("z2");
            return of(s.getString("world"), x1, y1, z1, x2, y2, z2);
        } catch (RuntimeException e) {
            // 配置非法不该让整个档案加载失败：跳过区域，两个触发器自然不触发
            return null;
        }
    }
}