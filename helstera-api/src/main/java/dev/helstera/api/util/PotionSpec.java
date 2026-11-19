package dev.helstera.api.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Registry-independent Potion contract shared by migration and runtime (Paper 1.21.1). */
public record PotionSpec(String type, String duration, String level,
                         boolean ambient, boolean particles, boolean icon, boolean force) {
    private static final List<String> POSITIONAL =
            List.of("type", "duration", "level", "ambient", "particles", "icon", "force");
    private static final Set<String> TYPES = Set.of(
            "SPEED", "SLOWNESS", "HASTE", "MINING_FATIGUE", "STRENGTH", "INSTANT_HEALTH",
            "INSTANT_DAMAGE", "JUMP_BOOST", "NAUSEA", "REGENERATION", "RESISTANCE",
            "FIRE_RESISTANCE", "WATER_BREATHING", "INVISIBILITY", "BLINDNESS", "NIGHT_VISION",
            "HUNGER", "WEAKNESS", "POISON", "WITHER", "HEALTH_BOOST", "ABSORPTION",
            "SATURATION", "GLOWING", "LEVITATION", "LUCK", "UNLUCK", "SLOW_FALLING",
            "CONDUIT_POWER", "DOLPHINS_GRACE", "BAD_OMEN", "HERO_OF_THE_VILLAGE", "DARKNESS",
            "TRIAL_OMEN", "RAID_OMEN", "WIND_CHARGED", "WEAVING", "OOZING", "INFESTED");

    public static PotionSpec parse(List<String> arguments) {
        Map<String, String> values = new HashMap<>();
        int positional = 0;
        for (String argument : arguments == null ? List.<String>of() : arguments) {
            if (argument == null || argument.isBlank()) throw invalid("blank argument");
            int equals = PlaceholderSyntax.argumentEquals(argument);
            String key;
            String value;
            if (equals < 0) {
                if (positional >= POSITIONAL.size()) throw invalid("too many positional arguments");
                key = POSITIONAL.get(positional++);
                value = argument.trim();
            } else {
                key = canonicalKey(argument.substring(0, equals).trim());
                value = argument.substring(equals + 1).trim();
            }
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (value.isBlank()) throw invalid("empty " + key);
            if (values.putIfAbsent(key, value) != null) throw invalid("duplicate " + key);
        }
        String type = values.getOrDefault("type", "SLOWNESS");
        if (!dynamic(type)) type = normalizeType(type);
        // Preserve the historical Helstera positional duration; Mythic named/default syntax uses 100 ticks.
        String duration = values.getOrDefault("duration", positional > 0 ? "60" : "100");
        String level = values.getOrDefault("level", "0");
        NumericExpression.validate(duration);
        NumericExpression.validate(level);
        if (!NumericExpression.dynamic(duration)) integer(duration, 1);
        if (!NumericExpression.dynamic(level)) integer(level, 0);
        return new PotionSpec(type, duration, level,
                bool(values.getOrDefault("ambient", "false")),
                bool(values.getOrDefault("particles", "true")),
                bool(values.getOrDefault("icon", "true")),
                bool(values.getOrDefault("force", "false")));
    }

    private static String canonicalKey(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "type", "t", "effect" -> "type";
            case "duration", "d" -> "duration";
            case "level", "lvl", "l", "amplifier" -> "level";
            case "force", "overwrite", "ow", "override", "or" -> "force";
            case "ambientparticles", "ambient", "a" -> "ambient";
            case "hasparticles", "particles", "p", "show-particles" -> "particles";
            case "hasicon", "icon", "i", "show-icon" -> "icon";
            default -> throw invalid("unknown argument " + key);
        };
    }

    public static String normalizeType(String raw) {
        String type = raw.trim().toUpperCase(Locale.ROOT);
        if (type.startsWith("MINECRAFT:")) type = type.substring("MINECRAFT:".length());
        type = switch (type) {
            case "SLOW" -> "SLOWNESS";
            case "FAST_DIGGING" -> "HASTE";
            case "SLOW_DIGGING" -> "MINING_FATIGUE";
            case "INCREASE_DAMAGE" -> "STRENGTH";
            case "HEAL" -> "INSTANT_HEALTH";
            case "HARM" -> "INSTANT_DAMAGE";
            case "JUMP" -> "JUMP_BOOST";
            case "CONFUSION" -> "NAUSEA";
            case "DAMAGE_RESISTANCE" -> "RESISTANCE";
            default -> type;
        };
        if (!TYPES.contains(type)) throw invalid("unknown effect type " + raw);
        return type;
    }

    /** Resolve every dynamic value before touching an entity; invalid values never become defaults. */
    public Optional<Resolved> resolve(UnaryOperator<String> resolver) {
        try {
            return Optional.of(new Resolved(normalizeType(resolver.apply(type)),
                    integer(NumericExpression.resolveText(duration, resolver), 1),
                    integer(NumericExpression.resolveText(level, resolver), 0),
                    ambient, particles, icon, force));
        } catch (IllegalArgumentException | ArithmeticException | NullPointerException e) {
            return Optional.empty();
        }
    }

    public record Resolved(String type, int duration, int level,
                           boolean ambient, boolean particles, boolean icon, boolean force) { }

    /** Canonical migration output retains legacy positional consumers and optional effect flags. */
    public List<String> positionalArguments() {
        var result = new ArrayList<>(List.of(type, duration, level));
        if (ambient || !particles || !icon || force) {
            result.add(Boolean.toString(ambient));
            result.add(Boolean.toString(particles));
            result.add(Boolean.toString(icon));
            if (force) result.add("true");
        }
        return List.copyOf(result);
    }

    private static boolean dynamic(String raw) {
        return PlaceholderSyntax.isToken(raw);
    }

    private static int integer(String raw, int minimum) {
        double number;
        try { number = Double.parseDouble(raw.trim()); }
        catch (NumberFormatException e) { throw invalid("expected integer: " + raw); }
        if (!Double.isFinite(number) || number < minimum || number > Integer.MAX_VALUE
                || number != Math.rint(number)) throw invalid("integer out of range: " + raw);
        return (int) number;
    }

    private static boolean bool(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw invalid("expected boolean: " + raw);
        };
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Potion: " + reason);
    }
}
