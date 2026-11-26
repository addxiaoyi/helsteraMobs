package dev.helstera.api.util;

import dev.helstera.api.behavior.Targeters;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Argument contracts verified against the MythicMobs 5.7.2 public entity setters. */
public record EntitySetterSpec(String mechanic, Map<String, String> values) {
    public EntitySetterSpec {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static String canonical(String name) {
        if (name == null) return "";
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "setpathfindingmalus", "setmalus", "malus", "set-pathfinding-malus" -> "set-pathfinding-malus";
            case "setleashholder", "setleasher", "set-leash-holder" -> "set-leash-holder";
            case "setchunkforceloaded", "set-chunk-force-loaded" -> "set-chunk-force-loaded";
            case "settextdisplay", "settext", "set-text-display" -> "set-text-display";
            case "setdisplayentityitem", "set-display-entity-item" -> "set-display-entity-item";
            case "setinteractionsize", "interactionsize", "set-interaction-size" -> "set-interaction-size";
            case "setraidercanjoinraid", "setcanjoinraid", "set-raider-can-join-raid" -> "set-raider-can-join-raid";
            case "setraiderpatrolleader", "setraiderleader", "set-raider-patrol-leader" -> "set-raider-patrol-leader";
            case "setraiderpatrolblock", "setraiderblock", "set-raider-patrol-block" -> "set-raider-patrol-block";
            default -> "";
        };
    }

    public static boolean supports(String name) { return !canonical(name).isEmpty(); }

    public static Set<String> names() {
        return Set.of("set-pathfinding-malus", "set-leash-holder", "set-chunk-force-loaded",
                "set-text-display", "set-display-entity-item", "set-interaction-size",
                "set-raider-can-join-raid", "set-raider-patrol-leader", "set-raider-patrol-block");
    }

    public static EntitySetterSpec parse(String name, List<String> args) {
        String mechanic = canonical(name);
        if (mechanic.isEmpty()) throw new IllegalArgumentException("Unknown entity setter: " + name);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String argument : args == null ? List.<String>of() : args) {
            if (argument == null || argument.isBlank()) throw invalid(mechanic, "empty argument");
            int equals = PlaceholderSyntax.argumentEquals(argument);
            if (equals <= 0) throw invalid(mechanic, "named arguments required");
            String key = key(mechanic, argument.substring(0, equals).trim().toLowerCase(Locale.ROOT));
            if (key == null) throw invalid(mechanic, "unknown argument " + argument.substring(0, equals));
            String value = argument.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
            }
            if (value.isEmpty() && !key.equals("text")) throw invalid(mechanic, "empty " + key);
            if (fields.putIfAbsent(key, value) != null) throw invalid(mechanic, "duplicate " + key);
        }
        switch (mechanic) {
            case "set-pathfinding-malus" -> {
                String type = fields.getOrDefault("malus", "OPEN").toUpperCase(Locale.ROOT);
                if (!MythicPathfindingMalus.isSupportedPathType(type)) throw invalid(mechanic, "unknown PathType " + type);
                fields.put("malus", type);
                fields.put("value", Float.toString(finiteFloat(fields.getOrDefault("value", "0"))));
            }
            case "set-leash-holder" -> flag(fields, "remove", false);
            case "set-chunk-force-loaded" -> flag(fields, "loaded", true);
            case "set-raider-can-join-raid", "set-raider-patrol-leader" -> flag(fields, "bool", true);
            case "set-display-entity-item" -> {
                fields.putIfAbsent("item", "AIR");
                if (fields.get("item").contains("<") || fields.get("item").contains(">"))
                    throw invalid(mechanic, "item is static");
            }
            case "set-interaction-size" -> {
                fields.putIfAbsent("height", "1"); fields.putIfAbsent("width", "1");
                for (String key : List.of("height", "width")) {
                    NumericExpression.validate(fields.get(key));
                    if (!NumericExpression.dynamic(fields.get(key))) finiteFloat(fields.get(key));
                }
            }
            case "set-raider-patrol-block" -> {
                String selector = fields.get("location");
                if (selector == null || !selector.startsWith("@"))
                    throw invalid(mechanic, "location requires an explicit @location selector");
                if (!Targeters.validLocationTargeterSpec(selector)
                        || !Targeters.positionTargeterSpec(selector).conditions().isEmpty())
                    throw invalid(mechanic, "unsupported nested location selector");
            }
            default -> { }
        }
        return new EntitySetterSpec(mechanic, fields);
    }

    /** Setter-only guard: unknown outer selector names must not import or bind as inert actions. */
    public static void requireKnownTargeter(String selector) {
        if (selector == null) return;
        var parts = new java.util.ArrayList<String>();
        int depth = 0, start = 0;
        boolean quoted = false;
        for (int i = 0; i < selector.length(); i++) {
            char character = selector.charAt(i);
            if (quoted && character == '\\' && i + 1 < selector.length()) { i++; continue; }
            if (character == '"') quoted = !quoted;
            if (quoted) continue;
            if (character == '{') depth++;
            if (character == '}') depth--;
            if (depth < 0) throw new IllegalArgumentException("Malformed setter targeter");
            if (character == '>' && depth == 0) { parts.add(selector.substring(start, i)); start = i + 1; }
        }
        if (quoted || depth != 0) throw new IllegalArgumentException("Malformed setter targeter");
        parts.add(selector.substring(start));
        for (String part : parts) {
            String value = part.trim().replaceFirst("^@", "");
            int open = value.indexOf('{');
            String name = (open < 0 ? value : value.substring(0, open)).trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) throw new IllegalArgumentException("Empty setter targeter");
            if (Targeters.isPositionTargeter(value)) {
                String compact = name.replace("-", "").replace("_", "");
                if (!compact.equals("pin") && !compact.equals("blocksinpinregion")
                        && !Targeters.validLocationTargeterSpec(Targeters.positionTargeterSpec(value).selector()))
                    throw new IllegalArgumentException("Invalid setter location targeter " + name);
                continue;
            }
            if (!Targeters.names().contains(name) && !Set.of("self", "caster", "target", "targets", "trigger", "attacker",
                    "owner", "parent", "targetoftarget", "children", "child", "passengers", "passenger",
                    "vehicle", "mount", "lastdamager", "nearestconditional", "nearestif").contains(name))
                throw new IllegalArgumentException("Unsupported setter targeter " + name);
        }
    }

    /** Splits a native body without losing duplicate/empty arguments or quoted text delimiters. */
    public static List<String> bodyArguments(String body) {
        if (body == null || body.isBlank()) return List.of();
        var result = new java.util.ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char character = body.charAt(i);
            if (quoted && character == '\\' && i + 1 < body.length()) {
                current.append(character).append(body.charAt(++i));
                continue;
            }
            if (character == '"') quoted = !quoted;
            if (!quoted) {
                if (character == '{' || character == '[' || character == '(') depth++;
                if (character == '}' || character == ']' || character == ')') depth--;
                if (depth < 0) throw new IllegalArgumentException("Unbalanced setter argument");
                boolean comma = character == ',' && body.substring(i + 1)
                        .matches("(?s)\\s*[A-Za-z][A-Za-z0-9_-]*\\s*=.*");
                if (depth == 0 && (character == ';' || comma)) {
                    if (current.toString().isBlank()) throw new IllegalArgumentException("Empty setter argument");
                    result.add(current.toString().trim()); current.setLength(0);
                    continue;
                }
            }
            current.append(character);
        }
        if (quoted || depth != 0 || current.toString().isBlank())
            throw new IllegalArgumentException("Malformed setter arguments");
        result.add(current.toString().trim());
        return List.copyOf(result);
    }

    public List<String> arguments() {
        return values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
    }

    public String value(String key) { return values.get(key); }
    public boolean flag(String key) { return Boolean.parseBoolean(value(key)); }

    public static float finiteFloat(String value) {
        try {
            float parsed = Float.parseFloat(value);
            if (!Float.isFinite(parsed)) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Expected finite float: " + value, failure);
        }
    }

    private static void flag(Map<String, String> fields, String key, boolean fallback) {
        String value = fields.getOrDefault(key, Boolean.toString(fallback));
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("Expected boolean for " + key);
        fields.put(key, value.toLowerCase(Locale.ROOT));
    }

    private static String key(String mechanic, String key) {
        return switch (mechanic) {
            case "set-pathfinding-malus" -> switch (key) {
                case "malus", "type", "malustype" -> "malus";
                case "value", "amount" -> "value";
                default -> null;
            };
            case "set-leash-holder" -> Set.of("remove", "r", "boolean", "bool", "b").contains(key) ? "remove" : null;
            case "set-chunk-force-loaded" -> Set.of("loaded", "l").contains(key) ? "loaded" : null;
            case "set-text-display" -> Set.of("text", "t").contains(key) ? "text" : null;
            case "set-display-entity-item" -> Set.of("item", "i", "type", "t", "material", "mat", "m").contains(key) ? "item" : null;
            case "set-interaction-size" -> switch (key) {
                case "height", "h" -> "height";
                case "width", "w" -> "width";
                default -> null;
            };
            case "set-raider-can-join-raid" -> Set.of("bool", "b", "can", "c").contains(key) ? "bool" : null;
            case "set-raider-patrol-leader" -> Set.of("bool", "b", "leader", "l").contains(key) ? "bool" : null;
            case "set-raider-patrol-block" -> Set.of("location", "l", "block", "b").contains(key) ? "location" : null;
            default -> null;
        };
    }

    private static IllegalArgumentException invalid(String mechanic, String reason) {
        return new IllegalArgumentException(mechanic + ": " + reason);
    }
}
