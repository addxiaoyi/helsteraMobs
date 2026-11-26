package dev.helstera.api.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Static strings and priorities consumed by the public 5.7.2 AI target handler. */
public record AiTargetSelectorSpec(String selector) {
    public enum Kind {
        CLEAR, PLAYERS, MONSTERS, VILLAGERS, IRON_GOLEMS, HURT_BY,
        OWNER_TARGET, OWNER_HURT_BY, CONDITIONAL, OTHER_FACTION,
        OTHER_FACTION_MONSTERS, SPECIFIC_FACTION, SPECIFIC_FACTION_MONSTERS, UNKNOWN
    }

    public record Rule(String source, int priority, Kind kind, Map<String, String> fields, String data) {
        public Rule { fields = Map.copyOf(fields); }
        public String text(String fallback, String... aliases) {
            return MythicGeneratedLootSpec.first(fields, fallback, aliases);
        }
        public int interval() {
            try { return Integer.parseInt(text("0", "interval", "int", "i")); }
            catch (NumberFormatException invalid) { return 0; }
        }
        public boolean mustSee() { return Boolean.parseBoolean(text("true", "mustsee", "ms")); }
        public boolean mustReach() { return Boolean.parseBoolean(text("false", "mustreach", "mr")); }
        public String conditions() { return text("null", "targetconditions", "conditions", "cond", "c"); }
        public String factions() { return text(data, "faction", "f"); }
    }

    public static String canonical(String name) {
        if (name == null) return "";
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "runaitargetselector", "aitarget", "run-ai-target-selector" -> "run-ai-target-selector";
            default -> "";
        };
    }
    public static boolean supports(String name) { return !canonical(name).isEmpty(); }
    public static AiTargetSelectorSpec parse(List<String> arguments) {
        // Missing input and unknown selectors are native no-ops. In particular, goal is not an alias.
        return new AiTargetSelectorSpec(MythicGeneratedLootSpec.first(MythicGeneratedLootSpec.named(arguments),
                null, "aitargetselector", "targetselector", "target", "string", "s"));
    }
    public List<Rule> rules() { return selector == null ? List.of() : List.of(rule(protectBlock(selector), 1)); }

    /** List order increments the implicit priority even for clear and unknown rules. */
    public static Rule rule(String source, int defaultPriority) {
        if (source == null) throw new IllegalArgumentException("Null AI target rule");
        String[] parts = source.split(" ");
        if (parts.length == 0) throw new IllegalArgumentException("Empty AI target rule");
        int at = 0, priority = defaultPriority;
        if (parts[0].matches("[0-9]*")) {
            try { priority = Integer.parseInt(parts[at++]); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid AI priority", invalid); }
        }
        if (at >= parts.length) throw new IllegalArgumentException("Missing AI target rule after priority");
        String token = parts[at++];
        String data = at < parts.length ? parts[at] : null;
        int brace = token.indexOf('{');
        Map<String, String> fields = new LinkedHashMap<>();
        if (brace >= 0 && token.lastIndexOf('}') > brace) {
            int end = token.lastIndexOf('}');
            if (end > brace) {
                for (String field : splitFields(token.substring(brace + 1, end))) {
                    int equal = field.indexOf('=');
                    if (equal > 0) fields.put(field.substring(0, equal).trim().toLowerCase(Locale.ROOT),
                            field.substring(equal + 1).replace("<&csp>", " ").replace("<&da>", "-").trim());
                }
            }
            token = token.substring(0, brace);
        }
        Kind kind = switch (token.toLowerCase(Locale.ROOT)) {
            case "clear", "reset" -> Kind.CLEAR;
            case "players", "player" -> Kind.PLAYERS;
            case "monsters", "monster" -> Kind.MONSTERS;
            case "villagers", "villager" -> Kind.VILLAGERS;
            case "irongolems", "irongolem", "iron_golems", "iron_golem" -> Kind.IRON_GOLEMS;
            case "hurtbytarget", "attacker", "damager" -> Kind.HURT_BY;
            // The loaded pinned catalog resolves the colliding ownerHurt alias to OwnerHurtGoal.
            case "ownertarget", "ownerattack", "ownerhurt" -> Kind.OWNER_TARGET;
            case "ownerhurtby", "ownerhurtbytarget", "ownerdamager", "ownerattacker" -> Kind.OWNER_HURT_BY;
            case "nearestconditionaltarget", "nearestconditional", "nearestif" -> Kind.CONDITIONAL;
            case "nearestotherfaction", "otherfaction" -> Kind.OTHER_FACTION;
            case "nearestotherfactionmonsters", "otherfactionmonsters" -> Kind.OTHER_FACTION_MONSTERS;
            case "nearestspecificfaction", "specificfaction" -> Kind.SPECIFIC_FACTION;
            case "nearestspecificfactionmonsters", "specificfactionmonsters" -> Kind.SPECIFIC_FACTION_MONSTERS;
            default -> Kind.UNKNOWN;
        };
        return new Rule(source, priority, kind, fields, data);
    }

    /** unparseBlock protects spaces within nested blocks; it does not unquote the rule. */
    public static String protectBlock(String source) {
        source = protectQuoted(protectQuoted(source, '"'), '\'');
        StringBuilder out = new StringBuilder();
        int depth = 0, start = 0, copied = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{' && depth++ == 0) start = i;
            if (c == '}' && --depth == 0) {
                out.append(source, copied, start);
                out.append(source.substring(start, i).replace(" ", "<&csp>").replace("-", "<&da>"));
                copied = i;
            }
        }
        out.append(source, copied, source.length());
        return out.toString();
    }

    private static String protectQuoted(String source, char quote) {
        if (source.indexOf(quote) < 0) return source;
        String[] parts = source.split(String.valueOf(quote));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 0) out.append(parts[i]);
            else out.append(quote).append(parts[i].replace("-", "<&da>").replace("\\", "<&bs>")
                    .replace("/", "<&fs>").replace(" ", "<&sp>").replace(",", "<&cm>")
                    .replace(";", "<&sc>").replace("=", "<&eq>").replace("{", "<&lc>")
                    .replace("}", "<&rc>").replace("[", "<&lb>").replace("]", "<&rb>")
                    .replace("'", "<&sq>")).append(quote);
        }
        return out.toString();
    }

    private static List<String> splitFields(String source) {
        List<String> result = new ArrayList<>();
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '{' || c == '[' || c == '(') depth++;
            else if (c == '}' || c == ']' || c == ')') depth--;
            else if (c == ';' && depth == 0) { result.add(source.substring(start, i)); start = i + 1; }
        }
        result.add(source.substring(start));
        return result;
    }
}
