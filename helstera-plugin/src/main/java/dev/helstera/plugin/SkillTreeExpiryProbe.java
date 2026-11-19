package dev.helstera.plugin;

import dev.helstera.api.behavior.SkillTreeContext;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Opt-in Paper restart observations; all deadlines use the real wall clock. */
final class SkillTreeExpiryProbe {
    private SkillTreeExpiryProbe() { }

    static SkillTreeContext create(LivingEntity entity, Location at, long offline, long retained) {
        var parent = new SkillTreeContext(entity, List.of(entity));
        parent.setVariable("expiry.parent", entity.getUniqueId().toString(), retained);
        parent.setLocation("expiry.parent-location", at, retained);
        var tree = new SkillTreeContext(entity, List.of(entity), at, parent);
        tree.setVariable("expiry.offline-deadline", offline);
        tree.setVariable("expiry.retained-deadline", retained);
        tree.setVariable("expiry.location", new SkillTreeContext.LocationSnapshot(
                at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch()).toString());
        // Persist location coordinates independently so each boot can compare exact values.
        tree.setLocation("expiry.control-location", at);
        for (var window : Map.of("offline", offline, "retained", retained).entrySet()) {
            values(entity.getUniqueId()).forEach((type, value) ->
                    tree.setVariable("expiry." + window.getKey() + "." + type, value, window.getValue()));
            tree.setLocation("expiry." + window.getKey() + ".location", at, window.getValue());
        }
        tree.setVariable("expiry.permanent", entity.getUniqueId().toString());
        tree.setVariable("expiry.replaced", 7, offline);
        tree.setVariable("expiry.replaced", "permanent-replacement");
        tree.setVariable("expiry.unset", 7, retained);
        tree.unsetVariable("expiry.unset");
        tree.setLocation("expiry.replaced-location", at, offline);
        tree.setLocation("expiry.replaced-location", at);
        tree.setLocation("expiry.unset-location", at, retained);
        tree.unsetLocation("expiry.unset-location");
        return tree;
    }

    private static Map<String, Object> values(UUID entity) {
        return Map.of("integer", 7, "float", 3.25F, "string", entity.toString(), "boolean", true,
                "list", List.of("red", 7, false), "set", Set.of("birch", "oak"),
                "map", Map.of("dotted.key", List.of(entity.toString(), 9)));
    }

    static boolean inspect(SkillTreeContext.Snapshot snapshot, UUID entity, String stage, Consumer<String> log) {
        if (snapshot == null) return false;
        Object offlineValue = snapshot.variables().get("expiry.offline-deadline");
        Object retainedValue = snapshot.variables().get("expiry.retained-deadline");
        if (!(offlineValue instanceof Long offline) || !(retainedValue instanceof Long retained)) return false;
        long now = System.currentTimeMillis();
        boolean first = "write".equals(stage), last = "final".equals(stage);
        boolean passed = first ? now < offline : last ? now >= retained : now >= offline && now < retained;
        var controlLocation = snapshot.locations().get("expiry.control-location");
        passed &= controlLocation != null && controlLocation.toString().equals(snapshot.variables().get("expiry.location"));
        int checks = 0;
        for (var window : Map.of("offline", offline, "retained", retained).entrySet()) {
            boolean present = first || (!last && window.getKey().equals("retained"));
            for (var value : values(entity).entrySet()) {
                String name = "expiry." + window.getKey() + "." + value.getKey();
                passed &= check(snapshot.variables(), snapshot.variableExpirations(), name, value.getValue(),
                        present, window.getValue(), entity, stage, log);
                checks++;
            }
            passed &= check(snapshot.locations(), snapshot.locationExpirations(), "expiry." + window.getKey()
                    + ".location", controlLocation, present, window.getValue(), entity, stage, log);
            checks++;
        }
        var parent = snapshot.parent().orElse(null);
        if (parent == null) return false;
        passed &= check(parent.variables(), parent.variableExpirations(), "expiry.parent", entity.toString(),
                !last, retained, entity, stage, log);
        passed &= check(parent.locations(), parent.locationExpirations(), "expiry.parent-location", controlLocation,
                !last, retained, entity, stage, log);
        passed &= check(snapshot.variables(), snapshot.variableExpirations(), "expiry.permanent", entity.toString(),
                true, 0, entity, stage, log);
        passed &= check(snapshot.variables(), snapshot.variableExpirations(), "expiry.replaced", "permanent-replacement",
                true, 0, entity, stage, log);
        passed &= check(snapshot.variables(), snapshot.variableExpirations(), "expiry.unset", null,
                false, 0, entity, stage, log);
        passed &= check(snapshot.locations(), snapshot.locationExpirations(), "expiry.replaced-location", controlLocation,
                true, 0, entity, stage, log);
        passed &= check(snapshot.locations(), snapshot.locationExpirations(), "expiry.unset-location", null,
                false, 0, entity, stage, log);
        checks += 7;
        log.accept("[SelfTest] skilltree-expiry stage=" + stage + " entity=" + entity + " checks=" + checks
                + " offline-deadline=" + offline + " retained-deadline=" + retained + " observed-at=" + now
                + " result=" + (passed ? "passed" : "failed"));
        return passed;
    }

    private static boolean check(Map<String, ?> values, Map<String, Long> deadlines, String name, Object expected,
                                 boolean present, long deadline, UUID entity, String stage, Consumer<String> log) {
        boolean passed = values.containsKey(name) == present
                && (!present || java.util.Objects.equals(expected, values.get(name)))
                && deadlines.getOrDefault(name, 0L) == (present ? deadline : 0);
        log.accept("[SelfTest] skilltree-expiry-check stage=" + stage + " entity=" + entity + " name=" + name
                + " expected-present=" + present + " actual-present=" + values.containsKey(name)
                + " expected-deadline=" + (present ? deadline : 0) + " actual-deadline="
                + deadlines.getOrDefault(name, 0L) + " result=" + (passed ? "passed" : "failed"));
        return passed;
    }
}
