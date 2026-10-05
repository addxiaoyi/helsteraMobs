package dev.helstera.migration.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MythicMobs 定义行的词法解析（导入器自持版）。
 *
 * <p><b>刻意不复用 helstera-ai 里的 {@code SpecSyntax}</b>：迁移中心只依赖
 * {@code helstera-api}，而打包 profile 里 phase4（integrations + migration）
 * 不含 helstera-ai。若此处引入对 ai 模块的依赖，AI 子系统缺失时整个迁移
 * 中心就跟着加载失败——而迁移恰好是最需要「AI 起不来时也能救场」的功能。
 * 代价是两份归一化逻辑可能漂移，故此处只保留导入真正用到的子集。</p>
 *
 * <p>职责仅限词法：识别大括号、按分隔符切参数、剥掉 {@code key=} 前缀。
 * 语义映射（{@code damage} → {@code damage-target}）在 {@link MythicMobsImporter}。</p>
 */
public final class MythicSpec {

    private MythicSpec() {
    }

    /** 归一化一行定义；无大括号时仅 trim。 */
    public static String normalize(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        int brace = s.indexOf('{');
        if (brace < 0) return s;
        String head = s.substring(0, brace).trim();
        String body = s.substring(brace + 1);
        int close = body.lastIndexOf('}');
        if (close >= 0) body = body.substring(0, close);
        List<String> parts = splitBody(body);
        return parts.isEmpty() ? head : head + " " + String.join(" ", parts);
    }

    /**
     * 切大括号内容。
     *
     * <p>{@code ;} 永远是分隔符；{@code ,} 仅当下一个 {@code ;} 之前还出现
     * {@code =} 时才算分隔符。这条不对称规则用来同时正确切分
     * {@code {amount=5,aoe=true}}（逗号分隔参数对）与 {@code {loc=1,2,3}}
     * （坐标值内含逗号）——两者只看当前片段无法区分，判错会让坐标静默错位。</p>
     */
    private static List<String> splitBody(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        int bracket = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted) {
                if (c == '[' || c == '(') bracket++;
                else if (c == ']' || c == ')') bracket = Math.max(0, bracket - 1);
                else if (bracket == 0) {
                    if (c == ';') {
                        flush(out, cur);
                        continue;
                    }
                    if (c == ',' && keyFollows(body, i)) {
                        flush(out, cur);
                        continue;
                    }
                }
            }
            cur.append(c);
        }
        flush(out, cur);
        return out;
    }

    /** 从逗号位置往后扫到下一个 {@code ;}，其间出现 {@code =} 则是参数分隔符。 */
    private static boolean keyFollows(String body, int from) {
        for (int i = from + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ';') return false;
            if (c == '=') return true;
        }
        return false;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        String v = cur.toString().trim();
        cur.setLength(0);
        if (v.isEmpty()) return;
        out.add(stripKey(v));
    }

    private static String stripKey(String v) {
        int eq = v.indexOf('=');
        if (eq <= 0) return v;
        String key = v.substring(0, eq).trim();
        if (key.isEmpty()) return v.substring(eq + 1).trim();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') return v;
        }
        String val = v.substring(eq + 1).trim();
        return val.isEmpty() ? v : val;
    }

    /** 取大括号里 {@code s=} / {@code skill=} 的值；不是技能引用返回 null。 */
    public static String skillRef(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (s.regionMatches(true, 0, "cast-skill", 0, 10)) {
            String rest = s.substring(10).trim();
            return rest.isEmpty() ? null : rest;
        }
        int brace = s.indexOf('{');
        if (brace < 0) return null;
        String head = s.substring(0, brace).trim().toLowerCase(Locale.ROOT);
        if (!head.equals("skill") && !head.equals("skillmechanic")) return null;
        String body = s.substring(brace + 1);
        int close = body.lastIndexOf('}');
        if (close >= 0) body = body.substring(0, close);
        for (String part : body.split("[;,]")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String k = p.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if (k.equals("s") || k.equals("skill")) {
                String v = p.substring(eq + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /**
     * 解析 MythicMobs 的 {@code ~Trigger:SkillName} 写法。
     *
     * @return 触发器名（小写连字符）；不是触发器写法返回 null
     */
    public static String triggerCall(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (!s.startsWith("~")) return null;
        int colon = s.indexOf(':');
        if (colon < 0) return null;
        String head = s.substring(1, colon).trim();
        String tail = s.substring(colon + 1).trim();
        if (head.isEmpty() || tail.isEmpty()) return null;
        return head.toLowerCase(Locale.ROOT).replaceAll("(?<!^)(?=[A-Z])", "-") + "|" + tail;
    }

    /** 取大括号内第 N 个参数（位置语义）。 */
    public static String argAt(String spec, int index) {
        int brace = spec == null ? -1 : spec.indexOf('{');
        if (brace < 0) return null;
        String body = spec.substring(brace + 1);
        int close = body.lastIndexOf('}');
        if (close >= 0) body = body.substring(0, close);
        List<String> parts = splitBody(body);
        return index >= 0 && index < parts.size() ? parts.get(index) : null;
    }

    /** 定义行的大括号键值（不含大括号）。 */
    public static String keyOf(String spec, String key) {
        int brace = spec == null ? -1 : spec.indexOf('{');
        if (brace < 0) return null;
        String body = spec.substring(brace + 1);
        int close = body.lastIndexOf('}');
        if (close >= 0) body = body.substring(0, close);
        for (String part : body.split("[;,]")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            if (p.substring(0, eq).trim().equalsIgnoreCase(key)) {
                return p.substring(eq + 1).trim();
            }
        }
        return null;
    }
}