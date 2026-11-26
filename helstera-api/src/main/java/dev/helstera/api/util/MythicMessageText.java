package dev.helstera.api.util;

/** Shared native PlaceholderString preprocessing; JSON escapes remain literal. */
public final class MythicMessageText {
    private MythicMessageText() { }

    public static String prepare(String raw) {
        String text = unquote(unquote(raw, '"'), '\'');
        text = text.replace("<mob", "<caster").replace("<dropper", "<caster")
                .replace("<player", "<caster").replace("<killer", "<trigger");
        return specialCharacters(text);
    }

    public static String specialCharacters(String text) {
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
