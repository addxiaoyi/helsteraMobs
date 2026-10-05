package dev.helstera.ai.codex;

import java.util.ArrayList;
import java.util.List;

/**
 * 图鉴页面排版：把条目渲染成书本页面。
 *
 * <p><b>纯逻辑，不碰 Bukkit</b>。页面宽度、每页行数都受客户端字体限制，
 * 超长会被客户端静默截断——表现是「图鉴后半段全白」，而服务端没有任何报错。
 * 把排版做成纯函数，才能对「会不会溢出」这一点做单元测试。</p>
 *
 * <p>行宽按<b>显示宽度</b>计算而非 {@code String.length()}：中文与全角符号
 * 在游戏里占两格，按字符数算会让中文行的实际宽度是估算的两倍，于是每行都会
 * 被截断。这是最容易写错、也最难靠肉眼在测试里发现的一处。</p>
 */
public final class CodexBook {

    /** 客户端书本正文的可用行数（留一行给翻页控件）。 */
    public static final int LINES_PER_PAGE = 13;

    /** 客户端书本正文的可用显示宽度（半角字符数）。 */
    public static final int COLUMNS = 28;

    private static final String NEWLINE = "§n";

    private CodexBook() {
    }

    /**
     * 计算一段文本的显示宽度：全角字符算 2 格。
     *
     * <p>颜色代码不计入宽度——它们不占位。若按 {@code length()} 计算，
     * 一行带色的标题会被误判为超宽而提前换行，视觉上表现为「标题右边莫名空一截」。</p>
     */
    public static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        boolean inColor = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') {
                inColor = true;
                continue;
            }
            if (inColor) {
                inColor = false;
                continue;
            }
            if (Character.isHighSurrogate(c)) {
                // 代理对：把两个 char 当一个码位判断，否则中文扩展区
                // 会被拆成两个单独的高/低代理而双双判成半角，行宽少算一半
                if (i + 1 < s.length()) {
                    int cp = Character.toCodePoint(c, s.charAt(i + 1));
                    w += isWideCodePoint(cp) ? 2 : 1;
                    i++;
                } else {
                    w += 1;
                }
                continue;
            }
            w += isWide(c) ? 2 : 1;
        }
        return w;
    }

    /**
     * 是否为全角字符。
     *
     * <p>覆盖 CJK 统一表意文字、全角标点与日韩文字。CJK 区按区段判断而非逐字枚举，
     * 因为表意文字区往后还有扩展区（扩展 B 起），枚举会漏掉大量生僻字，
     * 而漏判的代价是那一行溢出截断。</p>
     */
    static boolean isWideCodePoint(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0x303E)
                || (cp >= 0x3041 && cp <= 0x33FF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xA000 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x20000 && cp <= 0x3FFFD); // CJK 扩展 B 及以后
    }

    static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)      // 韩文字母
                || (c >= 0x2E80 && c <= 0x303E)  // CJK 部首、中文标点
                || (c >= 0x3041 && c <= 0x33FF)  // 假名、韩文、CJK 兼容
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0x4E00 && c <= 0x9FFF)  // 基本区
                || (c >= 0xA000 && c <= 0xA4CF)  // 彝文
                || (c >= 0xAC00 && c <= 0xD7A3)  // 韩文音节
                || (c >= 0xF900 && c <= 0xFAFF)  // CJK 兼容表意
                || (c >= 0xFE30 && c <= 0xFE6F)  // 竖排标点
                || (c >= 0xFF00 && c <= 0xFF60)  // 全角 ASCII
                || (c >= 0xFFE0 && c <= 0xFFE6); // 全角符号
    }

    /**
     * 按显示宽度折行。
     *
     * <p>颜色代码跟随所属片段，不会被折行截断在中间——那样会留下一个
     * 未闭合的 {@code §} 码，其后整段文本都会带上颜色。</p>
     */
    public static List<String> wrap(String line) {
        List<String> out = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int w = 0;
        // pendingColor 记录最近一次出现的颜色码，折行时补到新行开头
        String pendingColor = "";
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '§' && i + 1 < line.length()) {
                String code = line.substring(i, i + 2);
                // 颜色码/格式码不占宽度，直接附着到当前片段
                cur.append(code);
                if (isColorCode(code)) pendingColor = code;
                i += 2;
                continue;
            }
            int cw = isWide(c) ? 2 : 1;
            if (w + cw > COLUMNS) {
                // 行首补上颜色码，避免续行丢失样式
                out.add(cur.toString());
                cur = new StringBuilder(pendingColor);
                w = 0;
            }
            cur.append(c);
            w += cw;
            i++;
        }
        out.add(cur.toString());
        return out;
    }

    private static boolean isColorCode(String code) {
        char t = code.charAt(1);
        return t == '0' || t == '1' || t == '2' || t == '3' || t == '4'
                || t == '5' || t == '6' || t == '7' || t == '8' || t == '9'
                || t == 'a' || t == 'b' || t == 'c' || t == 'd' || t == 'e' || t == 'f';
    }

    /**
     * 把若干行排成页面，超出每页行数则分页。
     *
     * @param lines 原始行（可含 {@code §n} 表示主动换行）
     * @return 每页的行数组；入参为空返回单页空数组
     */
    public static List<List<String>> paginate(List<String> lines) {
        List<String> flat = new ArrayList<>();
        if (lines != null) {
            for (String l : lines) {
                if (l == null) continue;
                if (l.contains(NEWLINE)) {
                    // §n 是本类内部的换行标记，展开后交给 wrap 再处理
                    for (String part : l.split("§n", -1)) {
                        flat.addAll(wrap(part));
                    }
                } else {
                    flat.addAll(wrap(l));
                }
            }
        }
        List<List<String>> pages = new ArrayList<>();
        List<String> page = new ArrayList<>();
        for (String l : flat) {
            page.add(l);
            if (page.size() >= LINES_PER_PAGE) {
                pages.add(List.copyOf(page));
                page = new ArrayList<>();
            }
        }
        if (!page.isEmpty() || pages.isEmpty()) pages.add(List.copyOf(page));
        return List.copyOf(pages);
    }

    /** 渲染单个条目的页面行（不含分页）。 */
    public static List<String> renderEntry(CodexEntry e) {
        List<String> lines = new ArrayList<>();
        if (e == null) return lines;
        lines.add("§b§l" + nz(e.name()));
        lines.add("§8" + nz(e.id()));
        lines.add("");
        lines.add("§7作者: §f" + nz(e.author()));
        lines.add("§7版本: §f" + nz(e.version()));
        lines.add("§7资源包: §f" + nz(e.pack()));
        lines.add("§7格式: §f" + nz(e.sourceFormat()));
        lines.add("");
        lines.add("§7骨骼: §f" + e.boneCount() + " §7动画: §f" + e.animationCount());
        lines.add("§7缩放: §f" + e.scaleText() + " §7碰撞盒: §f" + e.hitboxText());
        if (!e.animationNames().isEmpty()) {
            lines.add("");
            lines.add("§7动画: " + String.join("§8, ", e.animationNames()));
        }
        if (e.isSparse()) {
            // 明确提示「这条看着存在但不会发生什么」，而不是让管理员自己猜
            lines.add("");
            lines.add("§e⚠ 该条目缺少骨骼或动画，功能可能不会生效");
        }
        return lines;
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "[未填]" : s;
    }
}