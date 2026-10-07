package dev.helstera.api.event;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;

/**
 * 实体受到免疫/倍率完全否定（伤害归零）时派发。
 *
 * <p>由 {@code ImmunityListener} 在 IMMUNITY 判定结果为伤害归零时触发，
 * 可用于「被免疫时触发反击」「免疫触发后附加debuff」等场景。</p>
 */
public record ModelDamageNegatedEvent(ModelInstance instance, Location location, String cause, double originalDamage) {
}