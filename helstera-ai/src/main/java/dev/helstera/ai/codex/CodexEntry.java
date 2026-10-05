package dev.helstera.ai.codex;

import dev.helstera.api.model.ModelDefinition;

import java.util.List;

/**
 * 图鉴条目：从 {@link ModelDefinition} 聚合出的只读快照。
 *
 * <p><b>为何聚合而非直接暴露模型</b>：图鉴要展示的是「作者写了什么」，
 * 而不是「渲染器需要什么」。直接暴露 {@code ModelDefinition} 会让图鉴
 * 跟着渲染层的字段变动，而它真正需要的那几项（骨骼数、动画数、缩放）
 * 恰恰是稳定的描述性数据。</p>
 *
 * <p>所有字符串字段在构造时已归一（空白转 null、缺省填占位），
 * 因此渲染层不需要再判空——判空散落在各处是图鉴出现「§7§7」这类
 * 空段落的常见原因。</p>
 */
public record CodexEntry(
        String id,
        String name,
        String author,
        String version,
        String pack,
        int boneCount,
        int animationCount,
        List<String> animationNames,
        double scale,
        double hitboxWidth,
        double hitboxHeight,
        String sourceFormat) {

    /** 空白的占位符。用中文方括号而非留空，是为了让管理员一眼看出「作者没填」。 */
    private static final String UNSET = "[未填]";

    /**
     * 归一化放在这里而非工厂方法：record 的紧凑构造器是<b>所有</b>构造路径的
     * 必经之处。只在 {@link #of} 里归一的话，直接 {@code new CodexEntry(...)}
     * （测试、网页端构造、未来的反序列化）就会拿到 null 字段，
     * 而类注释承诺的是「所有字符串字段在构造时已归一」——契约必须对每条路径成立。
     *
     * <p>不归一 {@code id}：它是身份字段，被归一成 {@code [未填]} 会让
     * 「这个条目是谁」变得无法回答。</p>
     */
    public CodexEntry {
        name = text(name);
        author = text(author);
        version = text(version);
        sourceFormat = text(sourceFormat);
    }

    public static CodexEntry of(ModelDefinition m) {
        if (m == null) throw new IllegalArgumentException("模型定义不能为 null");
        var hb = m.hitbox();
        return new CodexEntry(
                // id 不走 text()：它是身份字段，被填成占位符会让「这个条目是谁」无法回答
                m.id() == null ? null : m.id().trim(),
                text(m.name()),
                text(m.author()),
                text(m.version()),
                packOf(m.id()),
                m.allBones() == null ? 0 : m.allBones().size(),
                m.animationNames() == null ? 0 : m.animationNames().size(),
                m.animationNames() == null ? List.of() : List.copyOf(m.animationNames()),
                m.scale(),
                hb == null ? 0 : hb.width(),
                hb == null ? 0 : hb.height(),
                text(m.sourceFormat()));
    }

    /**
     * 模型 ID 的第一段即资源包名。
     *
     * <p>与 {@code ResourcePackBuilder#packOf} 保持同一规则，否则图鉴会显示
     * 一个资源包里根本不存在的包名，管理员按它去找纹理必然扑空。</p>
     */
    private static String packOf(String id) {
        if (id == null) return "default";
        int i = id.indexOf('/');
        return i < 0 ? "default" : id.substring(0, i);
    }

    private static String text(String s) {
        // null 与空白串同义：都要显示占位符。此前对 null 直接返回 null，
        // 使紧凑构造器里的归一形同虚设——直接构造时字段仍是 null。
        if (s == null) return UNSET;
        String t = s.trim();
        return t.isEmpty() ? UNSET : t;
    }

    /** 缩放格式化为两位小数，避免图鉴里出现 {@code 1.0} 与 {@code 1} 混排。 */
    public String scaleText() {
        return String.format(java.util.Locale.ROOT, "%.2f", scale);
    }

    /** 碰撞盒格式化为一位小数（格）。 */
    public String hitboxText() {
        return String.format(java.util.Locale.ROOT, "%.1f×%.1f", hitboxWidth, hitboxHeight);
    }

    /** 是否被归入「内容极少」的条目（图鉴里需要角标提示作者补全）。 */
    public boolean isSparse() {
        return boneCount == 0 || animationCount == 0;
    }
}