package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.util.DisplayTransformationSpec;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Applies one native transformation component and restarts its configured interpolation delay. */
final class DisplayTransformationActions {
    private DisplayTransformationActions() { }

    static void apply(DisplayTransformationSpec spec, BehaviorContext context) {
        if (context == null || !context.instanceValid() || context.targetLocation().isPresent()) return;
        List<? extends Entity> recipients = !context.targets().isEmpty() ? List.copyOf(context.targets())
                : context.entityRecipient().map(List::of).orElseGet(List::of);
        for (Entity recipient : recipients) {
            if (!(recipient instanceof Display display) || !display.isValid() || display.isDead()) continue;
            try {
                var scoped = context.withEntityRecipient(display);
                float[] values = spec.resolve(text -> Placeholders.resolve(text, scoped), ThreadLocalRandom.current());
                Transformation changed = transform(display.getTransformation(), spec, values);
                display.setInterpolationDelay(display.getInterpolationDelay());
                display.setTransformation(changed);
            } catch (RuntimeException failure) { SkillFaults.swallow("DisplayTransformationActions#apply", failure); }
        }
    }

    static Transformation transform(Transformation previous, DisplayTransformationSpec spec, float[] value) {
        var translation = new Vector3f(previous.getTranslation());
        var scale = new Vector3f(previous.getScale());
        var left = new Quaternionf(previous.getLeftRotation());
        var right = new Quaternionf(previous.getRightRotation());
        if (spec.type() == DisplayTransformationSpec.Type.TRANSLATION || spec.type() == DisplayTransformationSpec.Type.SCALE) {
            Vector3f vector = spec.type() == DisplayTransformationSpec.Type.TRANSLATION ? translation : scale;
            switch (spec.action()) {
                case SET -> vector.set(value[0], value[1], value[2]);
                case ADD -> vector.add(value[0], value[1], value[2]);
                case MULTIPLY -> vector.mul(value[0], value[1], value[2]);
                case DIVIDE -> vector.div(value[0], value[1], value[2]);
            }
        } else {
            Quaternionf rotation = spec.type() == DisplayTransformationSpec.Type.LEFT_ROTATION ? left : right;
            switch (spec.action()) {
                case SET -> rotation.set(value[0], value[1], value[2], value[3]);
                case ADD -> rotation.add(value[0], value[1], value[2], value[3]);
                case MULTIPLY -> rotation.mul(value[0], value[1], value[2], value[3]);
                case DIVIDE -> rotation.div(new Quaternionf(value[0], value[1], value[2], value[3]));
            }
        }
        // Reject division by zero and overflow atomically; native does not guard these.
        if (!translation.isFinite() || !scale.isFinite() || !left.isFinite() || !right.isFinite())
            throw new IllegalArgumentException("Non-finite display transformation result");
        return new Transformation(translation, left, scale, right);
    }
}
