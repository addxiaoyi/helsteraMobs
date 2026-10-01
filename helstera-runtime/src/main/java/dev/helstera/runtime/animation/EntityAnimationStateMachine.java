package dev.helstera.runtime.animation;

import dev.helstera.api.animation.AnimationOptions;
import dev.helstera.core.model.ModelDefinitionImpl;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;

/**
 * 实体动画状态机：AI 决策只产生动作意图（状态 + 移动速度 + 在地面），
 * 本状态机负责选择与过渡动画。决策与动画解耦。
 */
public final class EntityAnimationStateMachine {

    /** 实体动画状态。 */
    public enum AnimState {
        IDLE, WALK, RUN, ATTACK, HURT, DEATH, JUMP, SKILL, CUSTOM
    }

    /** 状态 -> 动画名（可由配置覆盖）。 */
    private final Map<AnimState, String> mapping = new HashMap<>();
    private final ModelDefinitionImpl model;
    private final AnimationControllerImpl controller;

    private AnimState state = AnimState.IDLE;
    private boolean onGround = true;
    private double moveSpeed;

    public EntityAnimationStateMachine(ModelDefinitionImpl model, AnimationControllerImpl controller,
                                       ConfigurationSection cfg) {
        this.model = model;
        this.controller = controller;
        mapping.put(AnimState.IDLE, "idle");
        mapping.put(AnimState.WALK, "walk");
        mapping.put(AnimState.RUN, "run");
        mapping.put(AnimState.ATTACK, "attack");
        mapping.put(AnimState.HURT, "hurt");
        mapping.put(AnimState.DEATH, "death");
        mapping.put(AnimState.JUMP, "jump");
        mapping.put(AnimState.SKILL, "skill");
        if (cfg != null) {
            for (String key : cfg.getKeys(false)) {
                try {
                    mapping.put(AnimState.valueOf(key.toUpperCase()), cfg.getString(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    public AnimState state() {
        return state;
    }

    public void setOnGround(boolean onGround) {
        this.onGround = onGround;
    }

    public void setMoveSpeed(double blocksPerSecond) {
        this.moveSpeed = blocksPerSecond;
    }

    /** AI/伤害触发意图。ATTACK/HURT/DEATH/SKILL 为一次性动画，播完自动回落。 */
    public void intent(AnimState s) {
        String anim = mapping.get(s);
        if (anim == null || !controller.hasAnimation(anim)) return;
        switch (s) {
            case ATTACK -> controller.play(anim, AnimationOptions.defaults()
                    .priority(5).transition(0.05f).interruptible(false));
            case HURT -> controller.play(anim, AnimationOptions.defaults()
                    .priority(4).transition(0.05f));
            case DEATH -> controller.play(anim, AnimationOptions.defaults()
                    .priority(10).transition(0.05f).interruptible(false).loop(false));
            case SKILL -> controller.play(anim, AnimationOptions.defaults()
                    .priority(6).transition(0.05f).interruptible(false));
            default -> controller.play(anim, AnimationOptions.defaults().priority(3));
        }
        state = s;
    }

    /** 每 Tick 调用：一次性动画结束后按移动状态回落。 */
    public void tick() {
        if (state == AnimState.DEATH) return;
        if (controller.currentAnimation().isEmpty()) {
            enterLocomotion();
        }
    }

    /** 按移速切换待机/行走/奔跑/跳跃。 */
    public void enterLocomotion() {
        AnimState target;
        if (!onGround) {
            target = controller.hasAnimation(mapping.get(AnimState.JUMP)) ? AnimState.JUMP : AnimState.IDLE;
        } else if (moveSpeed > 4.0) {
            target = AnimState.RUN;
        } else if (moveSpeed > 0.15) {
            target = AnimState.WALK;
        } else {
            target = AnimState.IDLE;
        }
        String anim = mapping.get(target);
        if (anim == null || !controller.hasAnimation(anim)) anim = mapping.get(AnimState.IDLE);
        if (anim != null && controller.hasAnimation(anim)
                && controller.currentAnimation().filter(anim::equals).isEmpty()) {
            controller.play(anim, AnimationOptions.defaults().loop(true).priority(0));
        }
        state = target;
    }
}
