package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.entity.LivingEntity;

/** One native-style metadata snapshot per tracker, reused by its start/tick/end callbacks. */
final class AuraCallbackState {
    private BehaviorContext tracker;

    AuraCallbackState(BehaviorContext source, LivingEntity target) {
        tracker = source.copyForAuraTracker(target);
    }

    BehaviorContext start(LivingEntity target) {
        return tracker.withTargetEntity(target).copyForSkillMechanic();
    }

    BehaviorContext tick(LivingEntity target) {
        BehaviorContext callback = tracker.periodicTarget(target);
        // Native run updates the stored origin, then deepClone copies scalar power for the
        // callback while retaining the tracker's variable registry. Callback POWER changes
        // therefore do not change the base power of the next tick or end callback.
        tracker = callback.copyForSkillMechanic();
        return callback;
    }

    BehaviorContext end(LivingEntity target) {
        return tracker.auraEndTarget(target);
    }
}
