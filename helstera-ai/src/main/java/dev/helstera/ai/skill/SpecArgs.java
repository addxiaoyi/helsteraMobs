package dev.helstera.ai.skill;

import java.util.List;

/**
 * 技能条件的参数解析：把「参数写错」从静默兜底变成装载期告警。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>技能工厂里解析参数原先一律「解析失败就返回默认值」。这看起来稳妥，
 * 实际是最难排查的一类缺陷：作者写 {@code set-scale abc}（本意 1.5），
 * 服务端拿到的是默认值 1.0，技能<b>照常执行</b>、<b>不报任何错</b>，
 * 表现是「技能有时没效果」——现场与「配置写对了但条件没满足」无法区分。
 * 本项目最常见的缺陷形态正是这一类。</p>
 *
 * <p>更隐蔽的是布尔：{@link Boolean#parseBoolean} 对除 "true" 以外的
 * <b>任何</b>输入都返回 false。于是 {@code loop yes}、{@code loop 1}、
 * {@code loop on} 全被当成 false，而作者以为启用了循环。</p>
 *
 * <h2>为什么可以放心抛异常</h2>
 *
 * <p>工厂里的 {@code num()/bool()} 只在<b>绑定期</b>调用一次——即
 * {@code ActionFactory.create(args)} 执行时，产出闭包之前。实测全部
 * 133 个动作工厂无一例外（见 {@code SpecArgBinderTest} 的说明性断言）。
 * 因此异常只会从「加载 skills.yml」这条路径冒出，正好被
 * {@code SkillService.bindAction} 已有的 try-catch 接住并记进
 * {@code warnings()}，最终出现在 {@code /helstera check} 里。</p>
 *
 * <p>换句话说，{@code bindAction} 早就为「工厂抛异常」准备好了处理，
 * 只是这些解析助手一直不抛——机制形同虚设。</p>
 */
public final class SpecArgs {

    private SpecArgs() {
    }

    /**
     * 装载期严格解析：参数缺失返回默认值，<b>参数存在但格式非法则抛异常</b>。
     *
     * @param args  全部参数
     * @param i     参数下标
     * @param def   参数缺失时的默认值
     * @param what  用于报错的参数名（如 "数值"）
     * @throws IllegalArgumentException 参数存在但无法解析
     */
    public static double num(List<String> args, int i, double def, String what) {
        if (args == null || args.size() <= i) return def;
        String raw = args.get(i) == null ? "" : args.get(i).trim();
        if (raw.isEmpty()) return def;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    what + "参数 \"" + raw + "\" 不是合法数值（应为 1 / 0.5 / -2 之类）");
        }
    }

    /**
     * 装载期严格解析布尔。
     *
     * <p>只接受 {@code true/false}（大小写不敏感）与 {@code yes/no/on/off/1/0}。
     * 其余一律视为非法而非静默 false。</p>
     */
    public static boolean bool(List<String> args, int i, boolean def, String what) {
        if (args == null || args.size() <= i) return def;
        String raw = args.get(i) == null ? "" : args.get(i).trim();
        if (raw.isEmpty()) return def;
        switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "yes", "on", "1":
                return true;
            case "false", "no", "off", "0":
                return false;
            default:
                throw new IllegalArgumentException(
                        what + "参数 \"" + raw + "\" 不是合法布尔值"
                                + "（可用 true/false，也接受 yes/no/on/off/1/0）");
        }
    }

    /** 同 {@link #num}，但不带参数名（用于无法确定语义的场合）。 */
    public static double num(List<String> args, int i, double def) {
        return num(args, i, def, "第 " + (i + 1) + " 个");
    }

    /** 同 {@link #bool}，但不带参数名。 */
    public static boolean bool(List<String> args, int i, boolean def) {
        return bool(args, i, def, "第 " + (i + 1) + " 个");
    }
}