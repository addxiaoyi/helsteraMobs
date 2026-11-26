package dev.helstera.api.util;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;

/** Typed spawning fields from the public 5.7.2 mechanics. */
public record SpecialSummonSpec(String mechanic, Map<String, String> fields) {
    public SpecialSummonSpec { fields = Map.copyOf(fields); }

    public static String canonical(String name) {
        if (name == null) return "";
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "summonareaeffectcloud", "summoncloud", "summon-cloud" -> "summon-cloud";
            case "summonfallingblock", "summon-falling-block" -> "summon-falling-block";
            case "summonpassenger", "summon-passenger" -> "summon-passenger";
            default -> "";
        };
    }
    public static boolean supports(String name) { return !canonical(name).isEmpty(); }
    public static SpecialSummonSpec parse(String name, List<String> arguments) {
        String mechanic = canonical(name);
        if (mechanic.isEmpty()) throw new IllegalArgumentException("Unknown special summon");
        var spec = new SpecialSummonSpec(mechanic, MythicGeneratedLootSpec.named(arguments));
        if (mechanic.equals("summon-cloud")) {
            MythicParticleSpec.parse(spec.fields);
            for (String value : List.of(spec.text("0", "radiusreductiononuse", "rrou"),
                    spec.text("0", "radiusreductionontick", "rrot"))) {
                try { finite(Float.parseFloat(value)); }
                catch (NumberFormatException ignored) { /* Placeholder or native fallback. */ }
            }
        }
        return spec;
    }
    public String text(String fallback, String... aliases) {
        return MythicGeneratedLootSpec.first(fields, fallback, aliases);
    }
    public String block() { return MythicMessageText.prepare(text("DIRT", "types", "type", "t", "material", "mat", "m")); }
    public String passenger() { return text("", "passenger", "p", "rider", "r", "type", "t"); }
    public boolean stack() { return Boolean.parseBoolean(text("false", "stack", "s")); }
    public int integer(String fallback, UnaryOperator<String> resolver, RandomGenerator random, String... aliases) {
        return ShieldBreakSpec.parse(List.of("duration=" + text(fallback, aliases))).duration(resolver, random);
    }
    public float floating(String fallback, UnaryOperator<String> resolver, RandomGenerator random, String... aliases) {
        return number(text(fallback, aliases), resolver, random);
    }
    static float number(String source, UnaryOperator<String> resolver, RandomGenerator random) {
        // PlaceholderFloat.of uses RandomFloat.matches, not its wider matchesRange grammar.
        if (source.matches("^-?[0-9]*(\\.\\d+)?(to|-)?[0-9]\\d*(\\.\\d+)?")) {
            String separator = source.contains("to") ? "to" : !source.startsWith("-") && source.contains("-") ? "-" : null;
            if (separator != null) {
                String[] pair = source.split(separator);
                try {
                    float a = finite(Float.parseFloat(pair[0])), b = finite(Float.parseFloat(pair[1]));
                    return finite(a + (float) random.nextDouble() * (b - a));
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException invalid) { return 1; }
            }
        }
        try { return finite(Float.parseFloat(resolver.apply(MythicMessageText.prepare(source)))); }
        catch (NumberFormatException invalid) { return 1; }
    }
    private static float finite(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite summon value");
        return value;
    }
}
