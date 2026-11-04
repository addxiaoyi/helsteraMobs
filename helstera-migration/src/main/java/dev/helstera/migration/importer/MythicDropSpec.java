package dev.helstera.migration.importer;

import dev.helstera.api.util.ConditionListParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

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

    private static final java.util.regex.Pattern DECIMAL_CHANCE = java.util.regex.Pattern.compile(
            "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    public MythicDropSpec {
        conditions = List.copyOf(conditions == null ? List.of() : conditions);
    }

    public boolean isTable() {
        return table != null && !table.isBlank();
    }

    /** Returns the safe runtime projection, or an empty map for unsupported entries. */
    public Map<String, Object> runtimeEntry() {
        if (external || (item == null && !isTable())) return Map.of();
        if (isTable() && (amountMin != 1 || amountMax != 1)) return Map.of();
        if (isTable()) {
            int dot = table.indexOf('.');
            String identifier = dot > 0 ? table.substring(dot + 1) : table;
            if (!identifier.matches("(?s).*[A-Za-z0-9].*")) return Map.of();
        }
        if (item != null && Set.of("exp", "xp", "experience").contains(item.toLowerCase(Locale.ROOT))) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("item", "exp");
            out.put("amount-min", amountMin);
            out.put("amount-max", amountMax);
            out.put("chance", chance);
            if (chanceExpression != null && !chanceExpression.isBlank()) {
                out.put("chance-expression", chanceExpression);
            }
            if (!conditions.isEmpty()) {
                List<String> translated = new ArrayList<>();
                List<String> unmapped = new ArrayList<>();
                MmConditions.translateAll(conditions, translated, unmapped);
                if (!unmapped.isEmpty()) return Map.of();
                out.put("conditions", translated);
            }
            return Map.copyOf(out);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(isTable() ? "table" : "item", isTable() ? MythicMobsImporter.normalizeId(table) : item);
        out.put("amount-min", amountMin);
        out.put("amount-max", amountMax);
        out.put("chance", chance);
        if (chanceExpression != null && !chanceExpression.isBlank()) {
            out.put("chance-expression", chanceExpression);
        }
        if (!conditions.isEmpty()) {
            List<String> translated = new ArrayList<>();
            List<String> unmapped = new ArrayList<>();
            MmConditions.translateAll(conditions, translated, unmapped);
            if (!unmapped.isEmpty()) return Map.of();
            out.put("conditions", translated);
        }
        return Map.copyOf(out);
    }

    public static MythicDropSpec parse(Object raw) {
        if (raw instanceof MythicDropSpec spec) return spec;
        if (raw instanceof Map<?, ?> map) return parseMap(map, raw);
        if (raw == null) return null;
        return parseText(String.valueOf(raw), raw);
    }

    private static MythicDropSpec parseMap(Map<?, ?> map, Object source) {
        Set<String> recognized = Set.of("item", "Item", "type", "Type", "table", "Table",
                "drop-table", "DropTable", "amount-min", "amountMin", "amount", "amount-max", "amountMax",
                "chance", "Chance", "chance-expression", "chanceExpression", "dynamic-chance", "dynamicChance",
                "conditions", "Conditions", "drop-if", "dropIf", "DropIf", "no-drop-if", "noDropIf", "NoDropIf");
        if (!recognized.containsAll(map.keySet())) return null;
        for (List<String> aliases : List.of(
                List.of("item", "Item", "type", "Type", "table", "Table", "drop-table", "DropTable"),
                List.of("amount-min", "amountMin", "amount"), List.of("amount-max", "amountMax"),
                List.of("chance", "Chance"),
                List.of("chance-expression", "chanceExpression", "dynamic-chance", "dynamicChance"))) {
            int present = 0;
            for (String key : aliases) {
                if (!map.containsKey(key)) continue;
                Object value = map.get(key);
                if (!(value instanceof String || value instanceof Number) || String.valueOf(value).isBlank()) return null;
                present++;
            }
            if (present > 1) return null;
        }
        for (String key : List.of("conditions", "Conditions", "drop-if", "dropIf", "DropIf",
                "no-drop-if", "noDropIf", "NoDropIf")) {
            if (map.containsKey(key) && !validConditionInput(map.get(key))) return null;
        }
        String item = text(map, "item", "Item", "type", "Type");
        String table = text(map, "table", "Table", "drop-table", "DropTable");
        int min = integer(map, 1, "amount-min", "amountMin", "amount");
        int max = integer(map, min, "amount-max", "amountMax");
        double chance = number(map, 1.0, "chance", "Chance");
        String expression = text(map, "chance-expression", "chanceExpression", "dynamic-chance", "dynamicChance");
        // Mythic 的 Drop 条目同时允许 DropIf（必须全满足）与 NoDropIf（任一满足即不掉）。
        // 以前只读 conditions，这两种写法会被静默忽略——条目变成无条件必掉，
        // 而配置看上去完全正常。前者与 conditions 同义合并，后者取反后并入，
        // 保证「命中即不掉」的语义在运行时仍然是同一个条件求值器判定的。
        List<String> conditions = new ArrayList<>();
        for (String key : List.of("conditions", "Conditions", "drop-if", "dropIf", "DropIf")) {
            for (String dropIf : list(map.get(key))) conditions.add(stripPrefix(dropIf.trim()));
        }
        for (String key : List.of("no-drop-if", "noDropIf", "NoDropIf")) {
            for (String veto : list(map.get(key))) {
                String trimmed = stripPrefix(veto.trim());
                conditions.add(trimmed.startsWith("!") ? trimmed.substring(1).trim() : "!" + trimmed);
            }
        }
        return finish(item, table, min, max, chance, expression, conditions, source);
    }

    /**
     * 剥掉 Mythic 条件前缀 {@code ?}。
     *
     * <p>{@code ?} 是 Mythic 的「本条件不计入重复保护」标记，属于执行期语义，
     * 不是条件名的一部分。{@code conditions()} 对外给出的是可读的条件名，
     * 保留前缀会让同一个条件在拼接、去重、日志里出现两种写法。</p>
     */
    private static String stripPrefix(String value) {
        String result = value;
        while (result.startsWith("?")) result = result.substring(1).trim();
        return result;
    }

    private static MythicDropSpec parseText(String raw, Object source) {
        String[] parts = raw.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) return null;
        int min = 1;
        int max = 1;
        double chance = 1.0;
        if (parts.length > 1) {
            try {
                String amount = parts[1].trim();
                java.util.regex.Matcher range = java.util.regex.Pattern
                        .compile("^(\\d+)(?:-(\\d+)|to(\\d+))$", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(amount);
                if (range.matches()) {
                    min = Integer.parseInt(range.group(1));
                    int upperExclusive = Integer.parseInt(range.group(2) == null
                            ? range.group(3) : range.group(2));
                    max = upperExclusive - 1;
                } else {
                    min = Integer.parseInt(amount);
                    max = min;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (parts.length > 2) {
            try {
                chance = parseChance(parts[2]);
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
                if (condition.isBlank()) return null;
                pending = new StringBuilder(condition);
            } else if (pending != null) {
                pending.append(' ').append(token);
            } else return null;
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

    private static String text(Map<?, ?> map, String... keys) {        for (String key : keys) {
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
            return parseChance(value);
        } catch (NumberFormatException ignored) { return Double.NaN; }
    }

    private static double parseChance(String raw) {
        String value = raw.trim();
        boolean percent = value.endsWith("%");
        if (percent) value = value.substring(0, value.length() - 1);
        if (!DECIMAL_CHANCE.matcher(value).matches()) throw new NumberFormatException("invalid chance");
        double parsed = Double.parseDouble(value);
        return percent || parsed > 1 ? parsed / 100.0 : parsed;
    }

    private static List<String> list(Object value) {
        return ConditionListParser.parse(value);
    }

    private static boolean validConditionInput(Object raw) {
        if (raw == null) return false;
        List<?> values = raw instanceof List<?> list ? list : List.of(raw);
        for (Object value : values) {
            if (!(value instanceof String text)) return false;
            List<String> parsed = ConditionListParser.parseFailClosed(text);
            if (parsed.isEmpty() || parsed.contains(ConditionListParser.INVALID_CONDITION)) return false;
            for (String condition : parsed) {
                if (stripPrefix(condition.trim()).isBlank()) return false;
            }
        }
        return true;
    }
}
