package dev.helstera.ai.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * 条件表达式树：支持 {@code &&} {@code ||} {@code !} 与括号组合。
 *
 * <p>MythicMobs 的条件可以任意嵌套：{@code ?onGround|?inWater ?health{<50%}}。
 * 此前 helstera 的 {@code require} 只是一个「全部为真」的扁平行列表，
 * 表达不了「A 或 B」——于是导入器遇到 OR 条件只能整条丢弃，
 * 这是迁移损耗最大的一类字段。</p>
 *
 * <p><b>运算优先级</b>：{@code !} &gt; {@code &&} &gt; {@code ||}。
 * 与 Java 一致，作者不需要额外记忆规则。</p>
 *
 * <p><b>短路求值</b>：与运算一旦为假就不再评估右侧。这不只是优化——
 * 组合式里常见 {@code hasTarget && distanceAbove 5}，若不短路，
 * 无目标时就会去求值距离条件并拿到错误结果。</p>
 *
 * <p>纯函数：叶子条件用注入的 {@code Predicate} 求值，因此可在无服务端
 * 环境下单测整棵树的逻辑。</p>
 */
public final class ConditionExpr {

    private final Node node;

    private ConditionExpr(Node node) {
        this.node = node;
    }

    /** 节点类型。 */
    private interface Node {
        boolean eval(Eval eval);
    }

    /** 叶子求值上下文：给定条件文本，返回是否满足。 */
    @FunctionalInterface
    public interface Eval {
        boolean test(String conditionSpec);
    }

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    /** 单个叶子条件。 */
    public static ConditionExpr of(String spec) {
        return new ConditionExpr(new Leaf(spec));
    }

    /** 恒真（无条件）。 */
    public static ConditionExpr always() {
        return new ConditionExpr((e) -> true);
    }

    /** 恒假。 */
    public static ConditionExpr never() {
        return new ConditionExpr((e) -> false);
    }

    /**
     * AND 连接。空列表视为恒真。
     *
     * <p>空列表返恒真而非恒假：「没写任何条件」在配置语义里是「不加限制」，
     * 返恒假会让所有省略 conditions 的刷怪点静默停摆。</p>
     */
    public static ConditionExpr and(List<ConditionExpr> parts) {
        List<ConditionExpr> list = compact(parts);
        if (list.isEmpty()) return always();
        if (list.size() == 1) return list.get(0);
        return new ConditionExpr(e -> {
            for (ConditionExpr p : list) {
                if (!p.node.eval(e)) return false;   // 短路
            }
            return true;
        });
    }

    /** OR 连接。空列表视为恒假（没写条件且要求「至少满足一个」= 无解）。 */
    public static ConditionExpr or(List<ConditionExpr> parts) {
        List<ConditionExpr> list = compact(parts);
        if (list.isEmpty()) return never();
        if (list.size() == 1) return list.get(0);
        return new ConditionExpr(e -> {
            for (ConditionExpr p : list) {
                if (p.node.eval(e)) return true;    // 短路
            }
            return false;
        });
    }

    public ConditionExpr not() {
        ConditionExpr self = this;
        return new ConditionExpr(e -> !self.node.eval(e));
    }

    private static List<ConditionExpr> compact(List<ConditionExpr> parts) {
        List<ConditionExpr> out = new ArrayList<>();
        if (parts == null) return out;
        for (ConditionExpr p : parts) {
            if (p != null && p.node != null) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 求值
    // ------------------------------------------------------------------

    public boolean test(Eval eval) {
        if (eval == null) return false;
        try {
            return node.eval(eval);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 单个叶子。 */
    private static final class Leaf implements Node {
        private final String spec;

        Leaf(String spec) {
            this.spec = spec == null ? "" : spec.trim();
        }

        @Override
        public boolean eval(Eval eval) {
            if (spec.isEmpty()) return true;
            return eval.test(spec);
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析组合表达式。
     *
     * <p>文法：<pre>
     *   or   := and ('||' and)*
     *   and  := unary ('&amp;&amp;' unary)*
     *   unary:= '!' unary | primary
     *   prim := '(' or ')' | 一行定义
     * </pre>
     *
     * <p>无法归约成单行定义时，整段原样作为一个叶子交给上层。
     * 宁可让上层报「未知条件」，也不要在这里静默截断——
     * 截断会把 {@code a || b} 变成只判 {@code a}，表现为条件偶发失效。</p>
     */
    public static ConditionExpr parse(String text) {
        if (text == null || text.isBlank()) return always();
        List<String> tokens = tokenize(text);
        if (tokens.size() <= 1) {
            return of(normalizeLeaf(text));
        }
        Parser p = new Parser(tokens);
        ConditionExpr parsed = p.parseOr();
        // 有剩余 token 说明文法不匹配：整体作为一个叶子交给上层
        return p.atEnd() ? parsed : of(text.trim());
    }

    /** 解析多行：行内可用 && ||，行间默认 AND。 */
    public static ConditionExpr parseAll(List<String> lines) {
        List<ConditionExpr> parts = new ArrayList<>();
        if (lines == null) return always();
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            parts.add(parse(line));
        }
        return and(parts);
    }

    /**
     * 叶子归一化：剥掉 MythicMobs 的 {@code ?} / {@code ~} 前缀与触发器冒号写法。
     *
     * <p>{@code ?health{<0.5}} 是 MythicMobs 的比较式条件，helstera 侧对应
     * {@code health-below 0.5}。这种映射无法在词法层通用处理，
     * 因此这里只剥前缀，语义转换留给导入器。</p>
     */
    static String normalizeLeaf(String raw) {
        String s = raw.trim();
        if (s.startsWith("?")) s = s.substring(1).trim();
        else if (s.startsWith("~")) s = s.substring(1).trim();
        // ~onSpawn:SkillName -> cast-skill SkillName
        int colon = s.indexOf(':');
        if (colon > 0) {
            String head = s.substring(0, colon).trim();
            String tail = s.substring(colon + 1).trim();
            if (SkillTrigger.byNormalized(SkillTrigger.normalizeName(head)) != null && !tail.isEmpty()) {
                return "cast-skill " + tail;
            }
        }
        return s;
    }

    /** 切词：保留括号与运算符，引号内整体作为一个 token。 */
    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted) {
                if (c == '(' || c == ')') {
                    flush(out, cur);
                    out.add(String.valueOf(c));
                    continue;
                }
                // '!' 必须单独成词。若不切，tokenize("!!a") 会得到单个 token "!!a"，
                // 文法匹配 size<=1 后整段降级成叶子，双重取反静默失效——
                // 表现为 "!cond" 里的取反偶尔不生效，且没有任何报错。
                if (c == '!') {
                    flush(out, cur);
                    out.add("!");
                    continue;
                }
                if (c == '&' && i + 1 < text.length() && text.charAt(i + 1) == '&') {
                    flush(out, cur);
                    out.add("&&");
                    i++;
                    continue;
                }
                if (c == '|' && i + 1 < text.length() && text.charAt(i + 1) == '|') {
                    flush(out, cur);
                    out.add("||");
                    i++;
                    continue;
                }
            }
            if (Character.isWhitespace(c) && !quoted) {
                flush(out, cur);
                continue;
            }
            cur.append(c);
        }
        flush(out, cur);
        return out;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        String v = cur.toString().trim();
        cur.setLength(0);
        if (!v.isEmpty()) out.add(v);
    }

    /** 递归下降解析器。 */
    private static final class Parser {
        private final List<String> tokens;
        private int pos;

        Parser(List<String> tokens) {
            this.tokens = tokens;
        }

        boolean atEnd() {
            return pos >= tokens.size();
        }

        ConditionExpr parseOr() {
            List<ConditionExpr> parts = new ArrayList<>();
            parts.add(parseAnd());
            while (!atEnd() && tokens.get(pos).equals("||")) {
                pos++;
                parts.add(parseAnd());
            }
            return or(parts);
        }

        ConditionExpr parseAnd() {
            List<ConditionExpr> parts = new ArrayList<>();
            parts.add(parseUnary());
            while (!atEnd() && tokens.get(pos).equals("&&")) {
                pos++;
                parts.add(parseUnary());
            }
            return and(parts);
        }

        ConditionExpr parseUnary() {
            if (!atEnd() && tokens.get(pos).equals("!")) {
                pos++;
                return parseUnary().not();
            }
            return parsePrimary();
        }

        ConditionExpr parsePrimary() {
            if (atEnd()) return always();
            String tok = tokens.get(pos);
            if (tok.equals("(")) {
                pos++;
                ConditionExpr inner = parseOr();
                if (!atEnd() && tokens.get(pos).equals(")")) pos++;
                return inner;
            }
            if (tok.equals(")") || tok.equals("&&") || tok.equals("||")) {
                return always();   // 病态输入：不推进，交给上层回退成整段叶子
            }
            pos++;
            // 紧跟的 token 若不是运算符，说明是「health-below 0.3」这类多词定义
            if (!atEnd() && !isOp(tokens.get(pos))) {
                StringBuilder merged = new StringBuilder(tok);
                while (!atEnd() && !isOp(tokens.get(pos)) && !tokens.get(pos).equals(")")) {
                    merged.append(' ').append(tokens.get(pos));
                    pos++;
                }
                return of(normalizeLeaf(merged.toString()));
            }
            return of(normalizeLeaf(tok));
        }

        private static boolean isOp(String t) {
            return t.equals("&&") || t.equals("||") || t.equals(")");
        }
    }
}