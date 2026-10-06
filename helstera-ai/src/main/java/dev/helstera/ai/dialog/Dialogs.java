package dev.helstera.ai.dialog;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 对话系统决策层：对话定义与步进。
 *
 * <p>纯数据，不依赖 Bukkit：{@code DialogueEntry} 来自 {@code dialogs.yml} 解析，
 * {@code CinematicStep} 是单次 cinematic 里的一个动作或等待。
 * {@link DialogueService} 负责装载和运行，本类只描述数据结构。</p>
 */
public final class Dialogs {

    private Dialogs() {
    }

    /**
     * 单个 cinematic 步进：要么执行一个动作，要么等待若干 tick。
     *
     * @param type  {@code wait} / {@code say} / {@code move} / {@code look}
     * @param args  语义取决于 type；wait 时 args[0]=ticks，say 时 args[0]=text 等
     */
    public record CinematicStep(String type, List<String> args) {
        public static CinematicStep wait(int ticks) {
            return new CinematicStep("wait", List.of(String.valueOf(ticks)));
        }

        /** 是否纯等待步进（不产生物理效果）。 */
        public boolean isWait() {
            return "wait".equalsIgnoreCase(type);
        }
    }

    /**
     * 单个对话条目（dialogs.yml 的顶层 key）。
     *
     * @param name      对话名（ID），玩家/技能引用时用
     * @param cinematics 命名的 cinematic 列表；播放时按顺序取用
     */
    public record DialogueEntry(String name, List<CinematicStep> cinematic) {
        public static DialogueEntry of(String name, List<CinematicStep> cinematic) {
            return new DialogueEntry(
                    name == null ? "" : name.trim().toLowerCase(Locale.ROOT),
                    cinematic == null ? Collections.emptyList() : cinematic);
        }
    }

    /**
     * 从配置节解析单个 cinematic 的步骤列表。
     *
     * @param section   {@code cinematics.<name>} 节下的命令列表；null 时返回空
     * @param problems  装载期告警收集器；解析失败时追加
     */
    public static List<CinematicStep> parseCinematic(
            ConfigurationSection section, List<String> problems) {
        if (section == null) return List.of();
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) section.getList("commands");
        if (raw == null) return List.of();
        List<CinematicStep> out = new ArrayList<>();
        for (Object cmd : raw) {
            if (!(cmd instanceof String s)) {
                problems.add("对话 cinematic 命令类型非法（非字符串），已跳过: " + cmd);
                continue;
            }
            CinematicStep step = parseStep(s, problems);
            if (step != null) out.add(step);
        }
        return out;
    }

    /**
     * 解析单条命令字符串为 CinematicStep。
     *
     * <p>格式：{@code <action> [arg1] [arg2] ...}。未知动作名记录告警并跳过。
     * 已知动作但参数不足时静默丢弃（与 SkillCatalog 同一策略）。</p>
     */
    public static CinematicStep parseStep(String s, List<String> problems) {
        if (s.isBlank()) return null;
        int space = s.indexOf(' ');
        String action = space < 0 ? s.trim().toLowerCase(Locale.ROOT)
                : s.substring(0, space).trim().toLowerCase(Locale.ROOT);
        String rest = space < 0 ? "" : s.substring(space).trim();
        List<String> args = tokenize(rest);
        switch (action) {
            case "wait" -> {
                if (args.isEmpty()) {
                    problems.add("\"wait\" 命令缺少 tick 数参数");
                    return null;
                }
                try {
                    int ticks = Integer.parseInt(args.get(0));
                    if (ticks <= 0) throw newNumberFormatException();
                    return CinematicStep.wait(ticks);
                } catch (NumberFormatException e) {
                    problems.add("\"wait\" 参数无效（需正整数）: " + args.get(0));
                    return null;
                }
            }
            case "say" -> {
                // say <speaker> <text>  →  speaker 暂忽略（统一发送给近端玩家），text 保留
                return new CinematicStep("say", args);
            }
            case "move" -> {
                // move <dx> <dy> <dz> <speed>
                if (args.size() < 4) {
                    problems.add("\"move\" 命令需要至少 4 个参数 (dx dy dz speed)");
                    return null;
                }
                return new CinematicStep("move", new ArrayList<>(args.subList(0, 4)));
            }
            case "look" -> {
                // look <yaw> <pitch>
                if (args.size() < 2) {
                    problems.add("\"look\" 命令需要至少 2 个参数 (yaw pitch)");
                    return null;
                }
                return new CinematicStep("look", new ArrayList<>(args.subList(0, 2)));
            }
            default -> {
                problems.add("未知对话动作: " + action + "（可用: say, move, look, wait）");
                return null;
            }
        }
    }

    /**
     * 把命令字符串按空白切分，同时保留引号内的空格（与 {@code SkillCatalog.split} 同构）。
     */
    public static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(c) && !quoted) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static NumberFormatException newNumberFormatException() {
        return new NumberFormatException("正整数");
    }
}