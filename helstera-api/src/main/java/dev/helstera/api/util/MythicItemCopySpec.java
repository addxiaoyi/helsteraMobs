package dev.helstera.api.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Raw argument contract for the MythicMobs 5.7.2 equipment-copy mechanics. */
public record MythicItemCopySpec(String mechanic, Slot slot, boolean fakeLooting) {
    public enum Slot { HEAD, CHEST, LEGS, FEET, HAND, OFFHAND, NONE }

    public static String canonical(String name) {
        if (name == null) return "";
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "equipcopy", "equip-copy" -> "equip-copy";
            case "giveitemfromslot", "givefromslot", "give-item-from-slot" -> "give-item-from-slot";
            default -> "";
        };
    }

    public static boolean supports(String name) { return !canonical(name).isEmpty(); }

    public static Set<String> names() { return Set.of("equip-copy", "give-item-from-slot"); }

    public static MythicItemCopySpec parse(String name, List<String> arguments) {
        String mechanic = canonical(name);
        if (mechanic.isEmpty()) throw new IllegalArgumentException("Unknown item-copy mechanic: " + name);
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            if (argument == null) throw new IllegalArgumentException("Item-copy mechanics require named arguments");
            int equal = PlaceholderSyntax.argumentEquals(argument);
            if (equal <= 0 || argument.substring(0, equal).isBlank())
                throw new IllegalArgumentException("Item-copy mechanics require named arguments");
            values.put(argument.substring(0, equal).trim().toLowerCase(Locale.ROOT),
                    argument.substring(equal + 1).trim());
        }
        // EquipCopy reads slots/s but never populates its slot list in the pinned native build.
        // Thus every configured value, including NONE or malformed text, still copies all slots.
        if (mechanic.equals("equip-copy")) return new MythicItemCopySpec(mechanic, Slot.NONE, false);
        return new MythicItemCopySpec(mechanic, slot(first(values, "NONE", "slot", "s")),
                Boolean.parseBoolean(first(values, "false", "dofakelooting", "fakelooting", "fl")));
    }

    private static Slot slot(String value) {
        // Native EquipSlot.of compares raw strings case-insensitively; it does not decode quotes.
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "HEAD", "4", "HELMET", "HELM", "HAT" -> Slot.HEAD;
            case "CHEST", "3", "CHESTPIECE", "CHESTPLATE", "BODY" -> Slot.CHEST;
            case "LEGS", "2", "LEGGINGS", "PANTS" -> Slot.LEGS;
            case "FEET", "1", "BOOTS", "SHOES" -> Slot.FEET;
            case "HAND", "0", "WEAPON", "MAINHAND" -> Slot.HAND;
            case "OFFHAND", "5", "SHIELD" -> Slot.OFFHAND;
            default -> Slot.NONE;
        };
    }

    private static String first(Map<String, String> values, String fallback, String... aliases) {
        for (String alias : aliases) if (values.containsKey(alias)) return values.get(alias);
        return fallback;
    }
}
