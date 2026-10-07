package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.entity.Player;

/**
 * 生物即将选择目标时派发。
 *
 * <p>在 {@code AiController.attack()} 设置目标之前触发，
 * 允许技能在此刻修改仇恨表或施加 debuff，影响后续目标选择。</p>
 */
public record ModelPreTargetEvent(ModelInstance instance, Player potentialTarget) {
}