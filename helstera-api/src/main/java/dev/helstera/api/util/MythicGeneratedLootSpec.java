package dev.helstera.api.util;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Public 5.7.2 GiveItemFromTarget arguments. Table lookup precedes inline decoding. */
public record MythicGeneratedLootSpec(String items, boolean fakeLooting) {
    public static String canonical(String name) {
        if (name == null) return "";
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "giveitemfromtarget", "givefromtarget", "giveitemsfromtarget", "itemgivefromtarget",
                    "give-item-from-target" -> "give-item-from-target";
            default -> "";
        };
    }

    public static boolean supports(String name) { return !canonical(name).isEmpty(); }

    public static MythicGeneratedLootSpec parse(List<String> arguments) {
        Map<String, String> values = named(arguments);
        String items = first(values, null, "items", "item", "i");
        if (items == null || items.isBlank()) throw new IllegalArgumentException("items/item/i is required");
        return new MythicGeneratedLootSpec(items,
                Boolean.parseBoolean(first(values, "true", "dofakelooting", "fakelooting", "fl")));
    }

    /** Called only after the raw items value failed a named-table lookup. */
    public List<String> inlineDrops() {
        String raw = items;
        if (raw.startsWith("\"")) {
            if (raw.length() < 2 || !raw.endsWith("\""))
                throw new IllegalArgumentException("Unterminated items value");
            raw = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    MythicMessageText.specialCharacters(raw.substring(1, raw.length() - 1)));
        }
        // Native uses String.split, including its trailing-empty-field behavior.
        return Arrays.asList(raw.split(","));
    }

    public static Map<String, String> named(List<String> arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            int equal = argument == null ? -1 : PlaceholderSyntax.argumentEquals(argument);
            if (equal <= 0 || argument.substring(0, equal).isBlank())
                throw new IllegalArgumentException("Named arguments are required");
            values.put(argument.substring(0, equal).trim().toLowerCase(Locale.ROOT),
                    argument.substring(equal + 1).trim());
        }
        return values;
    }

    public static String first(Map<String, String> values, String fallback, String... aliases) {
        for (String alias : aliases) if (values.containsKey(alias)) return values.get(alias);
        return fallback;
    }

}
