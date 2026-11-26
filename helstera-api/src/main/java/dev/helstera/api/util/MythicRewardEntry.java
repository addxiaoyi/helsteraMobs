package dev.helstera.api.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/** A generated item, experience or command drop, retaining fractional native amounts. */
public record MythicRewardEntry(String item, double minimum, double maximum, double chance, Command command) {
    private static final String DECIMAL = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?";
    private static final Pattern RANGE = Pattern.compile("^(" + DECIMAL + ")(?:to|-)(" + DECIMAL + ")$",
            Pattern.CASE_INSENSITIVE);

    public record Command(String text, boolean asCaster, boolean asTrigger, boolean asOperator) { }

    public MythicRewardEntry {
        if (item == null || item.isBlank() || !Double.isFinite(minimum) || !Double.isFinite(maximum)
                || minimum < 0 || maximum < minimum || maximum > Integer.MAX_VALUE
                || !Double.isFinite(chance) || chance < 0)
            throw new IllegalArgumentException("Invalid generated drop");
    }

    public boolean experience() { return Set.of("exp", "experience", "xp").contains(item.toLowerCase(Locale.ROOT)); }

    public double amount(RandomGenerator random) {
        return minimum == maximum ? minimum : minimum + random.nextDouble() * (maximum - minimum);
    }

    public static MythicRewardEntry parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Empty generated drop");
        List<String> tokens = tokens(raw.trim());
        String item = tokens.getFirst();
        Command command = null;
        int brace = item.indexOf('{');
        if (brace >= 0) {
            String type = item.substring(0, brace).toLowerCase(Locale.ROOT);
            if (!type.equals("command") || !item.endsWith("}"))
                throw new IllegalArgumentException("Unsupported configured drop: " + item);
            Map<String, String> fields = MythicGeneratedLootSpec.named(arguments(item.substring(brace + 1, item.length() - 1)));
            String text = MythicGeneratedLootSpec.first(fields, null, "command", "cmd", "c");
            if (text == null || text.isBlank()) throw new IllegalArgumentException("Command drop requires command/cmd/c");
            // PlaceholderString removes matching quotes, translates role aliases and special characters.
            text = MythicMessageText.prepare(text);
            command = new Command(text,
                    Boolean.parseBoolean(MythicGeneratedLootSpec.first(fields, "false", "ascaster", "caster", "ac", "sudo", "asmob")),
                    Boolean.parseBoolean(MythicGeneratedLootSpec.first(fields, "false", "astrigger", "trigger", "at")),
                    Boolean.parseBoolean(MythicGeneratedLootSpec.first(fields, "false", "asop", "op", "operator")));
            item = "command";
        }
        if (item.contains(":") && !item.regionMatches(true, 0, "minecraft:", 0, 10))
            throw new IllegalArgumentException("External drop provider is not installed: " + item);
        if (tokens.size() > 3) throw new IllegalArgumentException("Unsupported drop arguments: " + raw);
        double minimum = 1, maximum = 1, chance = 1;
        if (tokens.size() > 1) {
            var range = RANGE.matcher(tokens.get(1));
            if (range.matches()) {
                minimum = Double.parseDouble(range.group(1));
                maximum = Double.parseDouble(range.group(2));
            } else {
                minimum = Double.parseDouble(tokens.get(1));
                maximum = minimum;
                // A lone scalar below one is native probability shorthand.
                if (tokens.size() == 2 && minimum < 1) { chance = minimum; minimum = maximum = 1; }
            }
        }
        if (tokens.size() == 3) {
            String probability = tokens.get(2);
            boolean percent = probability.endsWith("%");
            chance = Double.parseDouble(percent ? probability.substring(0, probability.length() - 1) : probability);
            if (percent || chance > 1) chance /= 100;
        }
        return new MythicRewardEntry(item, minimum, maximum, chance, command);
    }

    private static List<String> arguments(String body) {
        List<String> out = new ArrayList<>();
        int start = 0;
        char quote = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quote != 0 && c == '\\') { i++; continue; }
            if (c == quote) quote = 0;
            else if (quote == 0 && (c == '\'' || c == '"')) quote = c;
            else if (quote == 0 && (c == ';' || c == ',')) {
                if (i > start) out.add(body.substring(start, i));
                start = i + 1;
            }
        }
        if (quote != 0) throw new IllegalArgumentException("Unterminated command quotes");
        if (start < body.length()) out.add(body.substring(start));
        return out;
    }

    private static List<String> tokens(String raw) {
        List<String> out = new ArrayList<>();
        int start = 0, depth = 0;
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0 && c == '\\') { i++; continue; }
            if (c == quote) quote = 0;
            else if (quote == 0 && (c == '\'' || c == '"')) quote = c;
            else if (quote == 0 && c == '{') depth++;
            else if (quote == 0 && c == '}') depth--;
            else if (quote == 0 && depth == 0 && Character.isWhitespace(c)) {
                if (i > start) out.add(raw.substring(start, i));
                start = i + 1;
            }
            if (depth < 0) throw new IllegalArgumentException("Malformed drop arguments");
        }
        if (quote != 0 || depth != 0) throw new IllegalArgumentException("Unterminated drop arguments");
        if (start < raw.length()) out.add(raw.substring(start));
        return out;
    }
}
