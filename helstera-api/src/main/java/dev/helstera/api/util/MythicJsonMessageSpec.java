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
            if (values.containsKey(key)) return new MythicJsonMessageSpec(MythicMessageText.prepare(values.get(key)));
        }
        throw new IllegalArgumentException("JSON messages require message/msg/m");
    }

}
