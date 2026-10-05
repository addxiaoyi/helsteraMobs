package dev.helstera.migration.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MythicMobs 条件叶子 → helstera 条件名的映射。
 *
 * <p><b>为什么必须在迁移中心自持一份</b>（与 {@link MythicSpec} 同一理由）：
 * 迁移中心只依赖 {@code helstera-api}，而打包 profile 里 phase4
 * （integrations + migration）不含 helstera-ai。引入对 ai 模块的依赖会让
 * 「AI 子系统缺失时迁移中心也跟着加载失败」——而迁移恰好是最需要
 * AI 起不来时也能救场的功能。</p>
 *
 * <p><b>为什么必须有这张表</b>：MM 写的是 {@code ?health{<50%}}，
 * helstera 认的是 {@code health-below 0.5}。不翻译就直接搬运的话，
 * 迁移出来的条件<b>永远匹配不到任何注册条件</b>，而免疫规则的条件求值
 * 是 fail-closed——表现为「配了条件免疫，永远不免疫」，且没有任何报错。
 * 这与「没迁移」对用户完全等价，却让他以为已经迁移成功。</p>
 *
 * <p><b>未映射的条件一律原样保留 + 由调用方告警</b>，绝不静默丢弃：
 * 丢掉的话用户在报告里看不到任何痕迹，只能靠对比原文件才发现少了东西。</p>
 */
public final class MmConditions {

    private MmConditions() {
    }

    /** 一条条件的翻译结果。 */
    public record Mapped(String condition, boolean translated) {
        /** 翻译后的条件，或未识别时的原样文本。 */
        public String condition() {
            return condition;
        }
    }

    /**
     * 翻译单个 MM 条件。
     *
     * @return 未识别时 {@code translated=false}，条件文本原样返回
     */
    public static Mapped translate(String raw) {
        if (raw == null) return new Mapped(null, false);
        String s = raw.trim();
        if (s.isEmpty()) return new Mapped(null, false);

        // 剥掉 MM 的 ? / ~ 前缀与触发器冒号写法
        String t = s;
        if (t.startsWith("?") || t.startsWith("~")) t = t.substring(1).trim();

        // 去掉 {…} 参数块，只留条件名参与匹配
        String head = t;
        String body = null;
        int brace = t.indexOf('{');
        if (brace >= 0) {
            head = t.substring(0, brace).trim();
            int close = t.lastIndexOf('}');
            body = close > brace ? t.substring(brace + 1, close).trim() : "";
        }
        String name = head.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");

        // ---- 比较式：?health{<50%} / ?health{>50%} ----
        if ("health".equals(name) && body != null) {
            return compare(body, "health");
        }
        if ("targetdistance".equals(name) && body != null) {
            return compare(body, "distance");
        }

        // ---- 无参等价 ----
        if ("hastarget".equals(name)) return new Mapped("has-target", true);
        if ("targetisplayer".equals(name)) return new Mapped("target-is player", true);
        if ("targetismob".equals(name) || "targetisliving".equals(name)) {
            return new Mapped("target-is minecraft", true);
        }

        return new Mapped(s, false);
    }

    /**
     * 解析比较式 {@code <50%} / {@code >0.3} / {@code >=50}。
     *
     * <p>MM 允许把百分号写进参数（{@code <50%}），而 helstera 统一用 0..1 的
     * 比值。不处理百分号会让 {@code health-below 50} 恒假——
     * 条件永不成立，规则永不生效，同样没有任何报错。</p>
     */
    private static Mapped compare(String body, String kind) {
        String s = body.trim();
        boolean percent = s.endsWith("%");
        if (percent) s = s.substring(0, s.length() - 1).trim();
        boolean less;
        if (s.startsWith("<=")) {
            less = true;
            s = s.substring(2).trim();
        } else if (s.startsWith(">=")) {
            less = false;
            s = s.substring(2).trim();
        } else if (s.startsWith("<")) {
            less = true;
            s = s.substring(1).trim();
        } else if (s.startsWith(">")) {
            less = false;
            s = s.substring(1).trim();
        } else {
            return new Mapped(body, false);
        }
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return new Mapped(body, false);
        }
        // 只有血量才需要换算：MM 允许百分号（<50%），helstera 统一 0..1。
        // 不换算会让 health-below 50 恒假——条件永不成立，规则永不生效，且无任何报错。
        //
        // 距离【绝不】换算：它是格数不是百分比。把 >1 当百分数会让「5 格」
        // 变成 0.05 格，条件恒真——免疫范围从 5 格悄悄缩到 1/20 格，
        // 看起来完全正常，只是 Boss 变得异常脆弱。
        if ("health".equals(kind) && (percent || v > 1.0)) v = v / 100.0;
        String prefix = "health".equals(kind) ? "health-" : "distance-";
        // helstera 的 health-below/above 是闭区间（<= / >=），
        // MM 的严格不等号无法逐字保留；此处按同值处理并在调用方报告里说明，
        // 而不是悄悄改动语义——临界点差一个取值比明说更危险。
        return new Mapped(prefix + (less ? "below " : "above ") + fmt(v), true);
    }

    private static String fmt(double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    /**
     * 翻译一批条件，返回翻译结果与未映射项。
     *
     * @param translatedOut 已翻译的条件（顺序保持）
     * @param unmappedOut   未映射的原始条件，供报告告警
     */
    public static void translateAll(List<String> in, List<String> translatedOut,
                                    List<String> unmappedOut) {
        for (String raw : in == null ? List.<String>of() : in) {
            Mapped m = translate(raw);
            if (m.condition() == null) continue;
            if (m.translated()) {
                translatedOut.add(m.condition());
            } else {
                translatedOut.add(m.condition());
                unmappedOut.add(m.condition());
            }
        }
    }

    /** 便捷入口：只取翻译结果。 */
    public static List<String> translateOnly(List<String> in) {
        List<String> out = new ArrayList<>();
        translateAll(in, out, new ArrayList<>());
        return out;
    }
}