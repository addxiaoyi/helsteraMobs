package dev.helstera.render.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 旧版颜色码解析（自包含，不依赖 adventure 的 legacy 序列化器）。
 *
 * <p>配置里的 {@code display-name: "&6Emberling"} 这类写法需要转成 Component 才能正确显示为金色；
 * 直接把它塞给 {@code TextDisplay#setText(String)} 会把 {@code &6} 原样显示成文本
 * （就是游戏里看到 {@code &6Emberling} 的原因）。</p>
 *
 * <p>同时兼容 {@code &} 与 {@code §} 两种前缀，支持 0-9a-f 颜色与 k-o 样式，{@code &r} 重置。</p>
 */
public final class LegacyText {

    private LegacyText() {
    }

    /** 把含 {@code &}/{@code §} 颜色码的文本解析为 Component；无颜色码时等价于纯文本。 */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        Style.Builder style = Style.style();
        net.kyori.adventure.text.TextComponent.Builder out = Component.text();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < raw.length()) {
                char code = Character.toLowerCase(raw.charAt(i + 1));
                NamedTextColor color = colorOf(code);
                TextDecoration deco = decoOf(code);
                if (color != null || deco != null || code == 'r') {
                    if (buf.length() > 0) {
                        out.append(Component.text(buf.toString(), style.build()));
                        buf.setLength(0);
                    }
                    if (code == 'r') {
                        style = Style.style();
                    } else if (color != null) {
                        style.color(color);
                    } else {
                        style.decoration(deco, TextDecoration.State.TRUE);
                    }
                    i++;
                    continue;
                }
            }
            buf.append(c);
        }
        if (buf.length() > 0) out.append(Component.text(buf.toString(), style.build()));
        return out.build();
    }

    /** 去掉颜色/样式码，仅保留可见文本（用于日志、网页等纯文本场景）。 */
    public static String strip(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < raw.length()) {
                char code = Character.toLowerCase(raw.charAt(i + 1));
                if (colorOf(code) != null || decoOf(code) != null || code == 'r') {
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 旧版单字符颜色码 → 颜色。
     * 注意：不能走 {@code NamedTextColor.NAMES.value("6")}——NAMES 的键是 "gold" 这类名字，
     * 按 0-9a-f 查一定返回 null（这正是 &6 显示成字面量的根因），必须显式映射。
     */
    private static NamedTextColor colorOf(char code) {
        return switch (code) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            case 'f' -> NamedTextColor.WHITE;
            default -> null;
        };
    }

    private static TextDecoration decoOf(char code) {
        return switch (code) {
            case 'k' -> TextDecoration.OBFUSCATED;
            case 'l' -> TextDecoration.BOLD;
            case 'm' -> TextDecoration.STRIKETHROUGH;
            case 'n' -> TextDecoration.UNDERLINED;
            case 'o' -> TextDecoration.ITALIC;
            default -> null;
        };
    }
}
