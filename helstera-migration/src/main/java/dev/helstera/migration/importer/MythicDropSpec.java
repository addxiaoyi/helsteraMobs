package dev.helstera.migration.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

/** Parsed, lossless boundary model for one MythicMobs drop entry. */
public record MythicDropSpec(
        String item,
        String table,
        int amountMin,
        int amountMax,
        double chance,
        String chanceExpression,
        List<String> conditions,
        Object source,
        boolean external) {

    public MythicDropSpec {
        conditions = List.copyOf(conditions == null ? List.of() : conditions);
    }

    public boolean isTable() {
        return table != null && !table.isBlank();
    }

    /** Returns the safe runtime projection, or an empty map for external entries. */
    public Map<String, Object> runtimeEntry() {
        if (external || (item == null && !isTable())) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(isTable() ? "table" : "item", isTable() ? table : item);
        out.put("amount-min", amountMin);
        out.put("amount-max", amountMax);
        out.put("chance", chance);
        if (chanceExpression != null && !chanceExpression.isBlank()) {
            out.put("chance-expression", chanceExpression);
        }
        if (!conditions.isEmpty()) out.put("conditions", conditions);
        return Map.copyOf(out);
    }

    public static MythicDropSpec parse(Object raw) {
        if (raw instanceof Map<?, ?> map) return parseMap(map, raw);
        if (raw == null) return null;
        return parseText(String.valueOf(raw), raw);
    }

    private static MythicDropSpec parseMap(Map<?, ?> map, Object source) {
        String item = text(map, "item", "Item", "type", "Type");
        String table = text(map, "table", "Table", "drop-table", "DropTable");
        int min = integer(map, 1, "amount-min", "amountMin", "amount");
        int max = integer(map, min, "amount-max", "amountMax");
        double chance = number(map, 1.0, "chance", "Chance");
        String expression = text(map, "chance-expression", "chanceExpression", "dynamic-chance", "dynamicChance");
        List<String> conditions = list(map.get("conditions"));
        return finish(item, table, min, max, chance, expression, conditions, source);
    }

    private static MythicDropSpec parseText(String raw, Object source) {
        String[] parts = raw.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) return null;
        int min = 1;
        int max = 1;
        double chance = 1.0;
        if (parts.length > 1) {
            int dash = parts[1].indexOf('-');
            try {
                min = Integer.parseInt(dash > 0 ? parts[1].substring(0, dash) : parts[1]);
                max = Integer.parseInt(dash > 0 ? parts[1].substring(dash + 1) : parts[1]);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (parts.length > 2) {
            try {
                chance = Double.parseDouble(parts[2].replace("%", ""));
                if (parts[2].contains("%") || chance > 1) chance /= 100.0;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        List<String> conditions = new ArrayList<>();
        StringBuilder pending = null;
        for (int i = 3; i < parts.length; i++) {
            String token = parts[i].trim();
            if (token.startsWith("?")) {
                if (pending != null) conditions.add(pending.toString().trim());
                String condition = token.substring(1).trim();
                pending = condition.isBlank() ? null : new StringBuilder(condition);
            } else if (pending != null) {
                pending.append(' ').append(token);
            }
        }
        if (pending != null) conditions.add(pending.toString().trim());
        return finish(parts[0], null, min, max, chance, null, conditions, source);
    }

    private static MythicDropSpec finish(String item, String table, int min, int max,
                                         double chance, String chanceExpression,
                                         List<String> conditions, Object source) {
        if (min < 1 || max < min || !Double.isFinite(chance) || chance < 0 || chance > 1) return null;
        String token = item == null ? table : item;
        boolean external = token != null && token.contains(":")
                && !token.toLowerCase(Locale.ROOT).startsWith("minecraft:");
        return new MythicDropSpec(item, table, min, max, chance, chanceExpression,
                conditions, source, external);
    }

    private static String text(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value).trim();
        }
        return null;
    }

    private static int integer(Map<?, ?> map, int fallback, String... keys) {
        String value = text(map, keys);
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return Integer.MIN_VALUE; }
    }

    private static double number(Map<?, ?> map, double fallback, String... keys) {
        String value = text(map, keys);
        if (value == null) return fallback;
        try {
            double parsed = Double.parseDouble(value.replace("%", ""));
            return value.contains("%") || parsed > 1 ? parsed / 100.0 : parsed;
        } catch (NumberFormatException ignored) { return Double.NaN; }
    }

    private static List<String> list(Object value) {
        if (value instanceof List<?> values) {
            List<String> out = new ArrayList<>();
            for (Object entry : values) if (entry != null && !String.valueOf(entry).isBlank()) out.add(String.valueOf(entry));
            return out;
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }
}
