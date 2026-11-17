package dev.helstera.api.util;

import java.util.List;
import java.util.Locale;

/** Static float speed contract from MythicMobs 5.7.2 SetSpeedMechanic. */
public record SetSpeedSpec(boolean flying, float multiplier) {
    public SetSpeedSpec {
        if (!Float.isFinite(multiplier) || !Float.isFinite(multiplier * (flying ? 0.1f : 0.2f))) {
            throw invalid("non-finite speed");
        }
    }

    public static boolean supports(String name) {
        return name.equalsIgnoreCase("setspeed") || name.equalsIgnoreCase("set-speed");
    }

    public static SetSpeedSpec parse(List<String> args) {
        String type = null;
        String speed = null;
        for (String raw : args == null ? List.<String>of() : args) {
            if (raw == null || raw.isBlank()) throw invalid("empty argument");
            int equals = raw.indexOf('=');
            String key = equals < 0 ? "speed" : raw.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = equals < 0 ? raw.trim() : raw.substring(equals + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (value.isEmpty()) throw invalid("empty " + key);
            switch (key) {
                case "speed", "s", "amount", "a", "value", "v", "multiplier", "m" -> {
                    if (speed != null) throw invalid("duplicate speed");
                    speed = value;
                }
                case "type", "t" -> {
                    if (type != null) throw invalid("duplicate type");
                    type = value;
                }
                default -> throw invalid("unsupported argument " + key);
            }
        }
        // Upstream uses contains("FLY"); all other nonempty type values select WALK.
        // It reads a static float, not a numeric placeholder or skill-power value.
        if (type != null && (type.contains("<") || type.contains(">"))) throw invalid("dynamic type");
        return new SetSpeedSpec(type != null && type.toUpperCase(Locale.ROOT).contains("FLY"),
                speed == null ? 1f : Float.parseFloat(speed));
    }

    public float speed() { return multiplier * (flying ? 0.1f : 0.2f); }

    public List<String> arguments() {
        return List.of("type=" + (flying ? "FLY" : "WALK"), "speed=" + Float.toString(multiplier));
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("setspeed: " + reason);
    }
}
