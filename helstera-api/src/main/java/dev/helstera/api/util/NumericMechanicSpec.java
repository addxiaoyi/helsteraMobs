package dev.helstera.api.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Argument contract for the existing numeric mechanics, independent of Bukkit registries. */
public record NumericMechanicSpec(List<String> arguments, int numericCount) {
    public NumericMechanicSpec { arguments = List.copyOf(arguments); }

    private record Field(String fallback, List<String> aliases) { }

    private static Field field(String fallback, String... aliases) {
        return new Field(fallback, List.of(aliases));
    }

    public static String canonical(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "damage" -> "damage-target";
            case "heal" -> "heal-self";
            case "ignite" -> "ignite-targets";
            case "knockback" -> "knockback-targets";
            case "damagepercent" -> "damage-percent";
            case "healpercent" -> "heal-percent";
            case "damagemelee", "meleedamage" -> "damage-melee";
            case "sethealth" -> "set-health";
            case "modifyhealth" -> "modify-health";
            case "setmaxhealth" -> "set-max-health";
            case "modifymaxhealth" -> "modify-max-health";
            case "modifyspeed" -> "modify-speed";
            case "settickspeed" -> "set-tick-speed";
            case "setnodamageticks", "setndt", "set-ndt" -> "set-no-damage-ticks";
            case "setmaxnodamageticks", "setmaximumnodamageticks" -> "set-maximum-no-damage-ticks";
            case "setwalkspeed" -> "set-walk-speed";
            case "setfire", "ignite-self" -> "set-fire";
            case "forcepull" -> "force-pull";
            default -> name.toLowerCase(Locale.ROOT);
        };
    }

    private static List<Field> fields(String name) {
        String base = canonical(name);
        if (base.endsWith("-targets")) {
            // Only explicit runtime factories have target variants; radial pull/melee do not.
            if (!List.of("heal-targets", "ignite-targets", "knockback-targets",
                    "heal-percent-targets", "damage-percent-targets", "set-health-targets",
                    "modify-health-targets", "set-max-health-targets", "modify-max-health-targets",
                    "modify-speed-targets", "set-tick-speed-targets", "set-no-damage-ticks-targets",
                    "set-maximum-no-damage-ticks-targets", "set-fire-targets", "set-walk-speed-targets",
                    "leap-targets", "jump-targets", "force-pull-targets").contains(base)) return List.of();
            base = base.substring(0, base.length() - 8);
        }
        return switch (base) {
            case "damage-target" -> List.of(field("0", "amount", "a", "damage", "value"));
            case "heal-self", "heal" -> List.of(field("0", "amount", "a", "heal", "health", "value", "damage"));
            case "heal-percent", "damage-percent" -> List.of(field("10", "amount", "a", "percent", "p", "value", "health", "damage"));
            case "set-health", "modify-health", "modify-max-health" -> List.of(field("0", "amount", "a", "value", "health"));
            case "set-max-health" -> List.of(field("20", "amount", "a", "value", "health"));
            case "modify-speed", "set-tick-speed" -> List.of(field("1", "amount", "a", "speed", "factor", "value"));
            case "set-no-damage-ticks" -> List.of(field("0", "ticks", "duration", "time", "t"));
            case "set-maximum-no-damage-ticks" -> List.of(field("20", "ticks", "duration", "time", "t"));
            case "set-fire", "ignite" -> List.of(field("40", "ticks", "duration", "time", "t"));
            case "freeze" -> List.of(field("140", "duration", "ticks", "time", "t"));
            case "set-walk-speed" -> List.of(field("0.2", "speed", "s", "amount", "value", "v"));
            case "knockback" -> List.of(field("1", "power", "strength", "amount", "value"), field("0.4", "vertical", "up", "y"));
            case "pull" -> List.of(field("6", "radius", "r"), field("1", "power", "velocity", "strength", "amount", "value"));
            case "leap" -> List.of(field("1.2", "velocity", "power", "amount", "strength", "value"));
            case "jump" -> List.of(field("0.42", "velocity", "power", "strength", "v"));
            case "force-pull" -> List.of(field("1", "velocity", "power", "strength", "v"), field(null, "vertical", "y", "yvelocity", "y-velocity", "yv"));
            case "damage-melee" -> List.of(field("3", "amount", "a", "damage", "value"), field("2.5", "radius", "r"));
            default -> List.of();
        };
    }

    public static boolean supports(String name) { return !fields(name).isEmpty(); }

    public static NumericMechanicSpec parse(String name, List<String> args) {
        return parse(name, args, false);
    }

    /** Preserve the importer's historical defaults until their upstream semantic audit. */
    public static NumericMechanicSpec parseImported(String name, List<String> args) {
        return parse(name, args, true);
    }

    private static NumericMechanicSpec parse(String name, List<String> args, boolean imported) {
        List<Field> fields = fields(name);
        if (fields.isEmpty()) throw new IllegalArgumentException("Unknown numeric mechanic: " + name);
        Map<Integer, String> values = new HashMap<>();
        Map<String, String> tags = new LinkedHashMap<>();
        int position = 0;
        for (String raw : args == null ? List.<String>of() : args) {
            if (raw == null || raw.isBlank()) throw invalid(name, "empty argument");
            int equals = raw.indexOf('=');
            int index = -1;
            String value = equals < 0 ? raw.trim() : raw.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (value.isBlank()) throw invalid(name, "empty value");
            if (equals < 0) index = position++;
            else {
                String key = raw.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                if (canonical(name).equals("damage-target") &&
                        List.of("tags", "tag", "rawtags", "rawtag", "damagetype", "element").contains(key)) {
                    key = key.equals("tag") ? "tags" : key.equals("rawtag") ? "rawtags" : key;
                    if (tags.putIfAbsent(key, value) != null) throw invalid(name, "duplicate " + key);
                    continue;
                }
                for (int i = 0; i < fields.size(); i++) {
                    if (fields.get(i).aliases().contains(key)) { index = i; break; }
                }
                if (index < 0) throw invalid(name, "unknown argument " + key);
            }
            if (index >= fields.size()) throw invalid(name, "too many positional arguments");
            if (values.putIfAbsent(index, value) != null) throw invalid(name, "duplicate " + fields.get(index).aliases().getFirst());
            if (!dynamic(value)) finite(value);
        }
        List<String> normalized = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            String fallback = fields.get(i).fallback();
            if (imported && i == 0 && List.of("heal-percent", "damage-percent", "modify-speed").contains(canonical(name))) {
                fallback = "0";
            }
            String value = values.getOrDefault(i, fallback);
            if (value != null) normalized.add(value);
        }
        int numericCount = normalized.size();
        tags.forEach((key, value) -> normalized.add(key + "=" + value));
        return new NumericMechanicSpec(normalized, numericCount);
    }

    public boolean dynamic() {
        return arguments.subList(0, numericCount).stream().anyMatch(NumericMechanicSpec::dynamic);
    }

    public Optional<List<String>> resolve(UnaryOperator<String> resolver) {
        List<String> resolved = new ArrayList<>(arguments);
        try {
            for (int i = 0; i < numericCount; i++) {
                String raw = arguments.get(i);
                String value = dynamic(raw) ? resolver.apply(raw) : raw;
                finite(value);
                resolved.set(i, value);
            }
            return Optional.of(List.copyOf(resolved));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static boolean dynamic(String raw) { return raw.matches("<[a-zA-Z0-9_.:-]+>"); }

    private static void finite(String value) {
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number)) throw new IllegalArgumentException("non-finite numeric argument: " + value);
    }

    private static IllegalArgumentException invalid(String name, String reason) {
        return new IllegalArgumentException(name + ": " + reason);
    }
}
