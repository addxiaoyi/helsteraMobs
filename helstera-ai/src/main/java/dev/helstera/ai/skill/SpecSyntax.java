package dev.helstera.ai.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 定义行归一化：把 MythicMobs 风格的 {@code mechanic{key=value;k2="v 2"}} 折成
 * helstera 内部使用的 {@code mechanic value "v 2"} 形式。
 *
 * <p>helstera 内部的绑定流程一直是「按空白切分 + 引号保护」
 * （见 {@code SkillService#split}）。这套切分无法表达
 * {@code damage{amount=5;aoe=true}}——大括号内容会被当成一整个 token。
 * 结果是导入器即使把 MythicMobs 的原样行搬过来，绑定期也认不出来，
 * 表现为「配置写了没反应」。</p>
 *
 * <p>本类只做词法层面的事：识别大括号、按 {@code ;} 或 {@code ,} 拆参数、
 * 剥掉 {@code key=} 前缀。参数<b>顺序即位置</b>，所以
 * {@code damage{amount=5;aoe=true}} 与 {@code damage-target 5 true} 等价——
 * 这正是 {@code SkillCatalog} 里那些 {@code num(a, 0, ...)} 期望的形态。</p>
 *
 * <p>刻意不支持具名取值（如 {@code amount=$<skill.damage>}）：
 * 那需要把参数解析成 map 而非 list，会改动全部既有工厂的签名，
 * 而目录里已有 28 个动作都是按位置写的。</p>
 */
public final class SpecSyntax {

    private SpecSyntax() {
    }

    /** 归一化一行定义；无大括号时原样返回（trim 后）。 */
    public static String normalize(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (s.isEmpty()) return s;
        int brace = s.indexOf('{');
        if (brace < 0) return s;

        String head = s.substring(0, brace).trim();
        int close = s.lastIndexOf('}');
        String body = close > brace ? s.substring(brace + 1, close) : s.substring(brace + 1);

        List<String> parts = splitBody(body);
        if (parts.isEmpty()) return head;
        return head + " " + String.join(" ", parts);
    }

    /**
     * 拆大括号内容为参数序列。
     *
     * <p>分隔符规则不对称，是刻意的：{@code ;} <b>永远</b>是分隔符；
     * {@code ,} 是否为分隔符取决于<b>前瞻</b>——见 {@link #keyFollows}。</p>
     *
     * <p>这个歧义是真实的：{@code {amount=5,aoe=true}}（逗号分隔参数对）
     * 与 {@code {loc=1,2,3}}（坐标里的逗号）光看当前片段无法区分。
     * 切错的后果都是静默错位：前者会把 {@code aoe=true} 当成坐标的一部分，
     * 后者会把坐标碎成三段。值本身需要包含逗号又必须被分隔时，
     * 用引号或方括号显式界定，二者在本方法里均被保护。</p>
     */
    private static List<String> splitBody(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
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

    /**
     * 前瞻判定：从 {@code commaIndex} 之后到下一个 {@code ;}（或结尾）之间，
     * 是否还存在 {@code =}。
     *
     * <p>存在则说明逗号后面还有 {@code key=value} 参数对，本逗号是两个参数之间的
     * 分隔符；不存在则说明后面是一串裸值（如 {@code 1,2,3}），逗号属于值内部。</p>
     *
     * <p>遇到 {@code ;} 即停：分号是无歧义的主分隔符，
     * 它之后的 {@code =} 与本逗号无关。</p>
     */
    private static boolean keyFollows(String body, int commaIndex) {
        for (int j = commaIndex + 1; j < body.length(); j++) {
            char c = body.charAt(j);
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

    /**
     * 剥掉 {@code key=} 前缀。
     *
     * <p>只在前缀是合法标识符时剥：{@code loc=1,2,3} 要留下
     * {@code 1,2,3}，而 {@code some-value} 不该被切成 {@code some} + {@code -value}
     * ——后者一旦拼回空白分隔的形式就彻底错位了。</p>
     */
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

    /** 拆出定义行的大括号内容；无大括号返回 null。 */
    public static String bodyOf(String spec) {
        if (spec == null) return null;
        int brace = spec.indexOf('{');
        if (brace < 0) return null;
        int close = spec.lastIndexOf('}');
        return close > brace ? spec.substring(brace + 1, close) : spec.substring(brace + 1);
    }

    /** 拆出定义行的大括号键（{@code skill{s=X}} 的 {@code s}）。 */
    public static String keyOf(String spec, String... keys) {
        String body = bodyOf(spec);
        if (body == null) return null;
        for (String part : body.split("[;,]")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            String k = p.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            for (String want : keys) {
                if (k.equalsIgnoreCase(want)) return p.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /** 取大括号里第 N 个参数值（位置语义，与 {@code SkillCatalog} 的 args[i] 对齐）。 */
    public static String argAt(String spec, int index) {
        List<String> parts = splitBody(bodyOf(spec));
        return index >= 0 && index < parts.size() ? parts.get(index) : null;
    }

    /** 大括号内的参数个数。 */
    public static int argCount(String spec) {
        return splitBody(bodyOf(spec)).size();
    }
}