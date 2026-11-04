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
        int brace = mechanicOpen(s);
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
     * <p>{@code ;} 是分隔符；{@code ,} 仅当紧随其后的是参数键和
     * {@code =} 时才算分隔符。这条规则用来同时正确切分
     * {@code {amount=5,aoe=true}}（逗号分隔参数对）与 {@code {loc=1,2,3}}
     * （坐标值内含逗号）。</p>
     */
    private static List<String> splitBody(String body) {
        return splitBody(body, true);
    }

    static List<String> keyedArguments(String spec) {
        int open = mechanicOpen(spec);
        int close = open < 0 ? -1 : matchingClose(spec, open);
        return close < 0 ? List.of() : splitBody(spec.substring(open + 1, close), false);
    }

    static int mechanicOpen(String spec) {
        if (spec == null) return -1;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '{') return i;
            if (Character.isWhitespace(c)) return -1;
        }
        return -1;
    }

    static boolean ambiguousExecutionSuffix(String spec) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (escaped) { token.append(c); escaped = false; continue; }
            if (quote != 0 && c == '\\') { token.append(c); escaped = true; continue; }
            if (quote != 0 && c == quote) quote = 0;
            else if (quote == 0 && (c == '\'' || c == '"')) quote = c;
            else if (quote == 0 && (c == '{' || c == '[' || c == '(')) depth++;
            else if (quote == 0 && (c == '}' || c == ']' || c == ')')) depth--;
            boolean chainSeparator = c == '>' && (token.length() > 0 && token.charAt(0) == '@'
                    || token.isEmpty() && !tokens.isEmpty()
                    && (tokens.get(tokens.size() - 1).startsWith("@")
                    || tokens.get(tokens.size() - 1).equals(">")));
            if (quote == 0 && depth == 0 && (Character.isWhitespace(c) || chainSeparator)) {
                if (!token.isEmpty()) { tokens.add(token.toString()); token.setLength(0); }
                if (chainSeparator) tokens.add(">");
            } else token.append(c);
        }
        if (!token.isEmpty()) tokens.add(token.toString());
        boolean targeter = false;
        boolean chance = false;
        for (int i = 1; i < tokens.size(); i++) {
            String part = tokens.get(i);
            if (part.equals("@") || part.equals("?")) return true;
            if (part.equals(">")) {
                if (i == 1 || !tokens.get(i - 1).startsWith("@")
                        || i + 1 >= tokens.size() || !tokens.get(i + 1).startsWith("@")
                        || tokens.get(i + 1).length() == 1) return true;
                continue;
            }
            if (part.startsWith("@") && part.length() > 1) {
                if (targeter && !tokens.get(i - 1).equals(">")) return true;
                targeter = true;
            } else if (part.endsWith("%")) {
                if (chance || !part.matches("[+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?%")) return true;
                double value;
                try { value = Double.parseDouble(part.substring(0, part.length() - 1)); }
                catch (NumberFormatException ignored) { return true; }
                if (!Double.isFinite(value) || value < 0 || value > 100) return true;
                chance = true;
            }
        }
        return false;
    }

    private static List<String> splitBody(String body, boolean stripKeys) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        int bracket = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quoted && c == '\\' && i + 1 < body.length()
                    && (body.charAt(i + 1) == '"' || body.charAt(i + 1) == '\\')) {
                cur.append(body.charAt(++i));
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted) {
                if (c == '[' || c == '(') bracket++;
                else if (c == ']' || c == ')') bracket = Math.max(0, bracket - 1);
                else if (bracket == 0) {
                    if (c == ';') {
                        flush(out, cur, stripKeys);
                        continue;
                    }
                    if (c == ',' && keyFollows(body, i)) {
                        flush(out, cur, stripKeys);
                        continue;
                    }
                }
            }
            cur.append(c);
        }
        flush(out, cur, stripKeys);
        return out;
    }

    /** 只检查逗号后紧邻的键，避免跨越值中的其他逗号。 */
    private static boolean keyFollows(String body, int from) {
        int i = from + 1;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        int start = i;
        while (i < body.length()) {
            char c = body.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '-') break;
            i++;
        }
        if (i == start) return false;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        return i < body.length() && body.charAt(i) == '=';
    }

    private static void flush(List<String> out, StringBuilder cur, boolean stripKeys) {
        String v = cur.toString().trim();
        cur.setLength(0);
        if (v.isEmpty()) return;
        out.add(stripKeys ? stripKey(v) : v);
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
        int close = matchingBrace(s, brace);
        if (close < 0) return null;
        String body = s.substring(brace + 1, close);
        String ref = null;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String part : splitBody(body, false)) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) return null;
            String k = p.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            if (k.equals("repeat-interval")) k = "repeatinterval";
            if (k.equals("skill")) k = "s";
            if (!java.util.Set.of("s", "delay", "repeat", "repeatinterval").contains(k)
                    || !seen.add(k)) return null;
            String v = p.substring(eq + 1).trim();
            if (v.isEmpty()) return null;
            if (k.equals("s")) {
                ref = v;
            }
        }
        return ref != null && ref.chars().noneMatch(Character::isWhitespace) ? ref : null;
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && quoted) { escaped = true; continue; }
            if (c == '"') { quoted = !quoted; continue; }
            if (quoted) continue;
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
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
        // Split camel-case before lower-casing; otherwise onKill becomes onkill
        // and the migration output no longer has the canonical trigger key.
        String normalized = head.replaceAll("(?<!^)(?=[A-Z])", "-")
                .toLowerCase(Locale.ROOT);
        return normalized + "|" + tail;
    }

    /** 取大括号内第 N 个参数（位置语义）。 */
    public static String argAt(String spec, int index) {
        int brace = mechanicOpen(spec);
        if (brace < 0) return null;
        String body = spec.substring(brace + 1);
        int close = body.lastIndexOf('}');
        if (close >= 0) body = body.substring(0, close);
        List<String> parts = splitBody(body);
        return index >= 0 && index < parts.size() ? parts.get(index) : null;
    }

    /** 定义行的大括号键值（不含大括号）。 */
    public static String keyOf(String spec, String key) {
        int brace = mechanicOpen(spec);
        if (brace < 0) return null;
        int close = matchingClose(spec, brace);
        String body = close > brace ? spec.substring(brace + 1, close) : spec.substring(brace + 1);
        for (String part : splitBody(body, false)) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) continue;
            if (p.substring(0, eq).trim().equalsIgnoreCase(key)) {
                return p.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static int matchingClose(String value, int open) {
        return matchingBrace(value, open);
    }
}
