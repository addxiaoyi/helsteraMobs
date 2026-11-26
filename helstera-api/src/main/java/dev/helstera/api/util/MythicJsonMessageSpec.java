package dev.helstera.api.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Raw component payload for public MythicMobs 5.7.2 MessageJSON. */
public record MythicJsonMessageSpec(String message) {
    public static String canonical(String name) {
        if (name == null) return "";
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "messagejson", "jsonmessage", "jmsg", "jm", "message-json" -> "message-json";
            default -> "";
        };
    }

    public static boolean supports(String name) { return !canonical(name).isEmpty(); }

    public static MythicJsonMessageSpec parse(List<String> arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            int equal = argument == null ? -1 : PlaceholderSyntax.argumentEquals(argument);
            if (equal <= 0 || argument.substring(0, equal).isBlank())
                throw new IllegalArgumentException("JSON messages require named arguments");
            values.put(argument.substring(0, equal).trim().toLowerCase(Locale.ROOT),
                    argument.substring(equal + 1).trim());
        }
        for (String key : List.of("message", "msg", "m")) {
            if (values.containsKey(key)) return new MythicJsonMessageSpec(prepare(values.get(key)));
        }
        throw new IllegalArgumentException("JSON messages require message/msg/m");
    }

    private static String prepare(String raw) {
        // PlaceholderParser removes matching outer double quotes, then single
        // quotes. JSON escapes inside the payload are never unescaped here.
        String text = unquote(unquote(raw, '"'), '\'');
        text = text.replace("<mob", "<caster").replace("<dropper", "<caster")
                .replace("<player", "<caster").replace("<killer", "<trigger");
        String[] keys = {"co", "sq", "da", "bs", "fs", "sp", "cm", "sc", "eq", "dq",
                "rb", "lb", "rc", "lc", "nl", "nm", "skull", "heart"};
        String[] values = {":", "'", "-", "\\", "/", " ", ",", ";", "=", "\"",
                "]", "[", "}", "{", "\n", "#", "☠", "❤"};
        for (int i = 0; i < keys.length; i++) text = text.replace("<&" + keys[i] + ">", values[i]);
        return text;
    }

    private static String unquote(String text, char quote) {
        return text.length() >= 2 && text.charAt(0) == quote && text.charAt(text.length() - 1) == quote
                ? text.substring(1, text.length() - 1) : text;
    }
}
